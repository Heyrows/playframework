/*
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package play.core.server

import scala.util.{ Try, Failure }

import org.specs2.mutable.Specification
import play.core.ApplicationProvider
import java.io.File
import scala.util.Random
import play.api.Application
import scala.concurrent.Await
import scala.concurrent.duration._
import play.core.server.netty.ServerResultUtils


object ServerResultUtilsSpec extends Specification {

  class Fake extends ApplicationProvider {
    def path: File = new File(".")
    def get: Try[Application] = Failure(new RuntimeException)
  }

  "splitSetCookieHeaderValue" should {
    "split string into sequence" in {
      val fakeValue = "this is a fake;; value"
      ServerResultUtils.splitSetCookieHeaderValue(fakeValue) must_== List("this is a fake"," value")
    }

    "return a sequence with the base value" in {
      val fakeValue = "there is nothing matching to the regex"
      ServerResultUtils.splitSetCookieHeaderValue(fakeValue) must_== List("there is nothing matching to the regex")
    }
  }

  "splitSetCookieHeaders" should {
    "return initial header if there is not Set-Cookie" in {
      val fakeHeader = Map("Authorization" -> "Bearer ....")
      ServerResultUtils.splitSetCookieHeaders(fakeHeader).toMap must_== fakeHeader
    }

    "should split the Set-Cookie header" in {
      val fakeHeader = Map(
        "Authorization" -> "Bearer ...",
        "Set-Cookie" -> "cookie1=value1; HttpOnly;;cookie2=value2; HttpOnly"
      )
      ServerResultUtils.splitSetCookieHeaders(fakeHeader) must_== Seq(
        "Authorization" -> "Bearer ...",
        "Set-Cookie" -> "cookie1=value1; HttpOnly",
        "Set-Cookie" -> "cookie2=value2; HttpOnly"
      )
    }
  }
}
