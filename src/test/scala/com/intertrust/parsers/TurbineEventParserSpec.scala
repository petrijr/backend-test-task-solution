package com.intertrust.parsers

import java.time.Instant

import com.intertrust.protocol.TurbineStatus.{Broken, Working}
import com.intertrust.protocol._
import org.scalatest.{Matchers, WordSpec}

import scala.io.Source

class TurbineEventParserSpec extends WordSpec with Matchers {
  "Turbine event parser" should {
    "successfully parse turbine events" when {
      "single working event" in {
        val input =
          """Date,ID,ActivePower (MW),Status
            |2015-11-23 00:00:00,B4B,3.18,Working""".stripMargin

        eventsFrom(input) should contain only TurbineEvent("B4B", Working, 3.18, Instant.parse("2015-11-23T00:00:00.00Z"))
      }
      "single broken event" in {
        val input =
          """Date,ID,ActivePower (MW),Status
            |2015-11-24 03:12:00,B3A,-0.11,Broken""".stripMargin

        eventsFrom(input) should contain only TurbineEvent("B3A", Broken, -0.11, Instant.parse("2015-11-24T03:12:00.00Z"))
      }
      "multiple events" in {
        val input =
          """Date,ID,ActivePower (MW),Status
            |2015-11-23 03:33:00,H7R,3.00,Working
            |2015-11-23 03:33:00,B3A,-0.11,Broken
            |2015-11-23 03:33:00,E7J,3.32,Working
            |2015-11-23 03:33:00,F10H_PR,3.09,Working""".stripMargin

        eventsFrom(input) shouldBe Seq(
          TurbineEvent("H7R", Working, 3.00, Instant.parse("2015-11-23T03:33:00Z")),
          TurbineEvent("B3A", Broken, -0.11, Instant.parse("2015-11-23T03:33:00Z")),
          TurbineEvent("E7J", Working, 3.32, Instant.parse("2015-11-23T03:33:00Z")),
          TurbineEvent("F10H_PR", Working, 3.09, Instant.parse("2015-11-23T03:33:00Z"))
        )
      }
    }
    "fail to parse turbine events" when {
      "malformed row" in {
        val input =
          """Date,ID,ActivePower (MW),Status
            |bunch of monkeys""".stripMargin

        an[EventParserException] shouldBe thrownBy(eventsFrom(input))
      }
      "invalid status" in {
        val input =
          """Date,ID,ActivePower (MW),Status
            |2015-11-24 03:12:00,B3A,-0.11,Failed""".stripMargin

        an[EventParserException] shouldBe thrownBy(eventsFrom(input))
      }
      "invalid date format" in {
        val input =
          """Date,ID,ActivePower (MW),Status
            |24.11.2015 03:12,B3A,-0.11,Broken""".stripMargin

        an[EventParserException] shouldBe thrownBy(eventsFrom(input))
      }
    }
  }

  private def eventsFrom(input: String) = new TurbineEventParser().parseEvents(Source.fromString(input)).toSeq
}
