package com.intertrust.parsers

import scala.io.Source
import scala.util.control.NonFatal

abstract class EventParser[T] {
  protected def parseEvent(columns: Array[String]): T

  def parseEvents(source: Source): Iterator[T] = {
      source
        .getLines()
        .drop(1) // Drop header
        .map(asEvent)
  }

  private def asEvent(line: String) = {
    val columns = line.split(',')
    try {
      parseEvent(columns)
    } catch {
      case NonFatal(e) => throw EventParserException(s"Failed to parse event from '$line'", e)
    }

  }
}

case class EventParserException(message: String, cause: Throwable) extends RuntimeException(message, cause)
