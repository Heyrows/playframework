/*
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package play.core.server.netty

import play.api.mvc._
import play.api.libs.iteratee._
import play.api._

import org.jboss.netty.handler.codec.http.HttpHeaders.Names._
import org.jboss.netty.buffer.ChannelBuffers
import org.jboss.netty.channel._
import org.jboss.netty.handler.codec.http._
import org.jboss.netty.handler.codec.http.HttpHeaders.Values._

import com.typesafe.netty.http.pipelining.{ OrderedDownstreamChannelEvent, OrderedUpstreamMessageEvent }

import scala.concurrent.Future
import scala.util.{ Failure, Success }

/**
 * Streams Play results to Netty
 */
object NettyResultStreamer {

  import NettyFuture._

  // A channel status holds whether the connection must be closed and the last subsequence sent
  class ChannelStatus(val closeConnection: Boolean, val lastSubsequence: Int)

  /**
   * Send the result to netty
   *
   * @return A Future that will be redeemed when the result is completely sent
   */
  def sendResult(result: Result, closeConnection: Boolean, httpVersion: HttpVersion, startSequence: Int)(implicit ctx: ChannelHandlerContext, oue: OrderedUpstreamMessageEvent): Future[_] = {
    // Result of this iteratee is a completion status
    val sentResponse: Future[ChannelStatus] = result match {

      case res if res.header.headers.exists(_._2 == null) => {
        // Make sure enumerator knows it's done, so that any resources it uses can be cleaned up
        result.body |>> Done(())

        Play.logger.debug("Response with headers set to null, sending 500 response.")
        val error = Results.InternalServerError("")
        error.body |>>> nettyStreamIteratee(createNettyResponse(error.header, true, httpVersion), startSequence, true)
      }

      // Sanitisation: ensure that we don't send chunked responses to HTTP 1.0 requests
      case UsesTransferEncoding() if httpVersion == HttpVersion.HTTP_1_0 => {
        // Make sure enumerator knows it's done, so that any resources it uses can be cleaned up
        result.body |>> Done(())

        Play.logger.debug("Chunked response to HTTP/1.0 request, sending 505 response.")
        val error = Results.HttpVersionNotSupported("The response to this request is chunked and hence requires HTTP 1.1 to be sent, but this is a HTTP 1.0 request.")
        error.body |>>> nettyStreamIteratee(createNettyResponse(error.header, closeConnection, httpVersion), startSequence, closeConnection)
      }

      case CloseConnection() => {
        result.body |>>> nettyStreamIteratee(createNettyResponse(result.header, true, httpVersion), startSequence, true)
      }

      case EndOfBodyInProtocol() => {
        result.body |>>> nettyStreamIteratee(createNettyResponse(result.header, closeConnection, httpVersion), startSequence, closeConnection)
      }

      case _ => {
        result.body |>>> bufferingIteratee(createNettyResponse(result.header, closeConnection, httpVersion), startSequence, closeConnection, httpVersion)
      }

    }

    // Clean up
    import play.api.libs.iteratee.Execution.Implicits.trampoline

    sentResponse.onComplete {
      case Success(cs: ChannelStatus) =>
        if (cs.closeConnection) {
          // Close in an orderely fashion.
          val channel = oue.getChannel;
          val closeEvent = new DownstreamChannelStateEvent(
            channel, channel.getCloseFuture, ChannelState.OPEN, java.lang.Boolean.FALSE);
          val ode = new OrderedDownstreamChannelEvent(oue, cs.lastSubsequence + 1, true, closeEvent)
          ctx.sendDownstream(ode)
        }
      case Failure(ex) =>
        Play.logger.error("Exception while sending chunked response to client:", ex)
        Channels.close(oue.getChannel)
    }
    sentResponse
  }

  /**
   * An iteratee that buffers the first element from the enumerator, and if it then receives EOF, sends the a result
   * immediately with the body and a content length.
   *
   * If there is more than one element from the enumerator, it sends the response either as chunked or as a stream that
   * gets closed, depending on whether the protocol is HTTP 1.0 or HTTP 1.1.
   */
  def bufferingIteratee(nettyResponse: HttpResponse, startSequence: Int, closeConnection: Boolean, httpVersion: HttpVersion)(implicit ctx: ChannelHandlerContext, e: OrderedUpstreamMessageEvent): Iteratee[Array[Byte], ChannelStatus] = {

    // Left is the first chunk if there was more than one chunk, right is the zero or one and only chunk
    def takeUpToOneChunk(chunk: Option[Array[Byte]]): Iteratee[Array[Byte], Either[Array[Byte], Option[Array[Byte]]]] = Cont {
      // We have a second chunk, fail with left
      case in @ Input.El(data) if chunk.isDefined => Done(Left(chunk.get), in)
      // This is the first chunk
      case Input.El(data) => takeUpToOneChunk(Some(data))
      case Input.Empty => takeUpToOneChunk(chunk)
      // We reached EOF, which means we either have one or zero chunks
      case Input.EOF => Done(Right(chunk))
    }

    import play.api.libs.iteratee.Execution.Implicits.trampoline

    takeUpToOneChunk(None).flatMap {
      case Right(chunk) => {
        val buffer = chunk.map(ChannelBuffers.wrappedBuffer).getOrElse(ChannelBuffers.EMPTY_BUFFER)
        // We successfully buffered it, so set the content length and send the whole thing as one buffer
        nettyResponse.headers().set(CONTENT_LENGTH, buffer.readableBytes)
        nettyResponse.setContent(buffer)
        val promise = sendDownstream(startSequence, !closeConnection, nettyResponse).toScala
        val done = Done[Array[Byte], ChannelStatus](new ChannelStatus(closeConnection, startSequence))
        Iteratee.flatten(promise.map(_ => done).recover {
          case _ => done
        })
      }
      case Left(chunk) => {
        val bufferedAsEnumerator = Enumerator(chunk)

        // Get the iteratee, maybe chunked or maybe not according HTTP version
        val bodyIteratee = if (httpVersion == HttpVersion.HTTP_1_0) {
          nettyStreamIteratee(nettyResponse, startSequence, true)
        } else {
          // Chunk it
          nettyResponse.headers().set(TRANSFER_ENCODING, CHUNKED)
          Results.chunk &>> nettyStreamIteratee(nettyResponse, startSequence, closeConnection)
        }

        // Feed the buffered content into the iteratee, and return the iteratee so that future content can continue
        // to be fed directly in as normal
        Iteratee.flatten(bufferedAsEnumerator |>> bodyIteratee)
      }
    }

  }

  // Construct an iteratee for the purposes of streaming responses to a downstream handler.
  def nettyStreamIteratee(nettyResponse: HttpResponse, startSequence: Int, closeConnection: Boolean)(implicit ctx: ChannelHandlerContext, e: OrderedUpstreamMessageEvent): Iteratee[Array[Byte], ChannelStatus] = {

    def step(subsequence: Int)(in: Input[Array[Byte]]): Iteratee[Array[Byte], ChannelStatus] = in match {
      case Input.El(x) =>
        val b = ChannelBuffers.wrappedBuffer(x)
        nextWhenComplete(sendDownstream(subsequence, false, b), step(subsequence + 1), new ChannelStatus(closeConnection, subsequence))
      case Input.Empty =>
        Cont(step(subsequence))
      case Input.EOF =>
        sendDownstream(subsequence, !closeConnection, ChannelBuffers.EMPTY_BUFFER)
        Done(new ChannelStatus(closeConnection, subsequence))
    }
    nextWhenComplete(sendDownstream(startSequence, false, nettyResponse), step(startSequence + 1), new ChannelStatus(closeConnection, startSequence))
  }

  def createNettyResponse(header: ResponseHeader, closeConnection: Boolean, httpVersion: HttpVersion) = {
    val nettyResponse = new DefaultHttpResponse(httpVersion, HttpResponseStatus.valueOf(header.status))

    import scala.collection.JavaConverters._

    val headers = ServerResultUtils.splitSetCookieHeaders(header.headers)

    headers.foreach {
      case (name, value) => nettyResponse.headers().add(name, value)
    }

    // Response header Connection: Keep-Alive is needed for HTTP 1.0
    if (!closeConnection && httpVersion == HttpVersion.HTTP_1_0) {
      nettyResponse.headers().set(CONNECTION, KEEP_ALIVE)
    } else if (closeConnection && httpVersion == HttpVersion.HTTP_1_1) {
      nettyResponse.headers().set(CONNECTION, CLOSE)
    }

    nettyResponse
  }

  def sendDownstream(subSequence: Int, last: Boolean, message: Object)(implicit ctx: ChannelHandlerContext, oue: OrderedUpstreamMessageEvent) = {
    val ode = new OrderedDownstreamChannelEvent(oue, subSequence, last, message)
    ctx.sendDownstream(ode)
    ode.getFuture
  }

  def nextWhenComplete[E, A](future: ChannelFuture, step: (Input[E]) => Iteratee[E, A], done: A)(implicit ctx: ChannelHandlerContext): Iteratee[E, A] = {
    // If the channel isn't currently connected, then this future will never be redeemed.  This is racey, and impossible
    // to 100% detect, but it's better to fail fast if possible than to sit there waiting forever
    import play.api.libs.iteratee.Execution.Implicits.trampoline
    if (!ctx.getChannel.isConnected) {
      Done(done)
    } else {
      Iteratee.flatten(
        future.toScala.map[Iteratee[E, A]] {
          _ => if (ctx.getChannel.isConnected()) Cont(step) else Done(done)
        }.recover[Iteratee[E, A]] {
          case _ => Done(done)
        }
      )
    }
  }

  /**
   * Extractor object that determines whether the end of the body is specified by the protocol
   */
  object EndOfBodyInProtocol {
    def unapply(result: Result): Boolean = {
      import result.header.headers
      headers.contains(TRANSFER_ENCODING) || headers.contains(CONTENT_LENGTH)
    }
  }

  /**
   * Extractor object that determines whether the result specifies that the connection should be closed
   */
  object CloseConnection {
    def unapply(result: Result): Boolean = result.connection == HttpConnection.Close
  }

  /**
   * Extractor object that determines whether the result uses a transfer encoding
   */
  object UsesTransferEncoding {
    def unapply(result: Result): Boolean = result.header.headers.contains(TRANSFER_ENCODING)
  }

}
