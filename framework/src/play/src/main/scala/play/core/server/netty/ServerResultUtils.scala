package play.core.server.netty

import org.jboss.netty.handler.codec.http.HttpHeaders.Names._
import play.api.mvc.Cookies

/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

object ServerResultUtils {

  def splitSetCookieHeaders(headers: Map[String, String]): Iterable[(String, String)] = {
    if (headers.contains(SET_COOKIE)) {
      // Rewrite the headers with Set-Cookie split into separate headers
      headers.toSeq.flatMap {
        case (SET_COOKIE, value) =>
          splitSetCookieHeaderValue(value)
            .map { cookiePart =>
              SET_COOKIE -> cookiePart
            }
        case (name, value) =>
          Seq((name, value))
      }
    } else {
      // No Set-Cookie header so we can just use the headers as they are
      headers
    }
  }

  def splitSetCookieHeaderValue(value: String): Seq[String] = {
    Cookies.SetCookieHeaderSeparatorRegex.split(value)
  }
}