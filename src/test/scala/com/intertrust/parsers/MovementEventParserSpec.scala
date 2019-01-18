package com.intertrust.parsers

import java.time.Instant

import com.intertrust.protocol.Movement.{Enter, Exit}
import com.intertrust.protocol._
import org.scalatest.{Matchers, WordSpec}

import scala.io.Source

class MovementEventParserSpec extends WordSpec with Matchers {
  "Movement event parser" should {
    "successfully parse movement events" when {
      "single entry to vessel" in {
        val input =
          """Date,Location,Person,Movement type
            |23.11.2015 06:37,Vessel 235098384,P1,Enter""".stripMargin

        eventsFrom(input) should contain only MovementEvent("P1", Vessel("235098384"), Enter, Instant.parse("2015-11-23T06:37:00.00Z"))
      }
      "single exit from turbine" in {
        val input =
          """Date,Location,Person,Movement type
            |24.11.2015 10:57,D5A,P40,Exit""".stripMargin

        eventsFrom(input) should contain only MovementEvent("P40", Turbine("D5A"), Exit, Instant.parse("2015-11-24T10:57:00.00Z"))
      }
      "multiple events" in {
        val input =
          """Date,Location,Person,Movement type
            |23.11.2015 09:21,E8J,P57,Exit
            |23.11.2015 09:21,Vessel 235090838,P57,Enter
            |23.11.2015 09:26,Vessel 235090838,P19,Exit
            |23.11.2015 09:26,F3P,P19,Enter""".stripMargin

        eventsFrom(input) shouldBe Seq(
          MovementEvent("P57", Turbine("E8J"), Exit, Instant.parse("2015-11-23T09:21:00.00Z")),
          MovementEvent("P57", Vessel("235090838"), Enter, Instant.parse("2015-11-23T09:21:00.00Z")),
          MovementEvent("P19", Vessel("235090838"), Exit, Instant.parse("2015-11-23T09:26:00.00Z")),
          MovementEvent("P19", Turbine("F3P"), Enter, Instant.parse("2015-11-23T09:26:00.00Z"))
        )
      }
    }
    "fail to parse movement events" when {
      "malformed row" in {
        val input =
          """Date,Location,Person,Movement type
            |bunch of monkeys""".stripMargin

        an[EventParserException] shouldBe thrownBy(eventsFrom(input))
      }
      "invalid movement type" in {
        val input =
          """Date,Location,Person,Movement type
            |24.11.2015 10:57,D5A,P40,Leave""".stripMargin

        an[EventParserException] shouldBe thrownBy(eventsFrom(input))
      }
      "invalid movement date format" in {
        val input =
          """Date,Location,Person,Movement type
            |23-11-2015 09:21:00,E8J,P57,Enter""".stripMargin

        an[EventParserException] shouldBe thrownBy(eventsFrom(input))
      }
    }
  }

  private def eventsFrom(input: String) = new MovementEventParser().parseEvents(Source.fromString(input)).toSeq
}
