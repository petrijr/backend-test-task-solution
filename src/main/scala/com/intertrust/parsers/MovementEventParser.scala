package com.intertrust.parsers

import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneId}

import com.intertrust.parsers.MovementEventParser.{timestampFormat, vesselPattern}
import com.intertrust.protocol._

class MovementEventParser extends EventParser[MovementEvent] {
  override protected def parseEvent(columns: Array[String]): MovementEvent = {
    MovementEvent(
      engineerId = columns(2),
      location = parseLocationId(columns(1)),
      movement = Movement.withName(columns(3)),
      timestamp = Instant.from(timestampFormat.parse(columns(0)))
    )
  }

  private def parseLocationId(s: String) = s match {
    case vesselPattern(id) => Vessel(id)
    case other => Turbine(other)
  }
}

object MovementEventParser {
  private val timestampFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.of("UTC"))
  private val vesselPattern = "Vessel (.*)".r
}
