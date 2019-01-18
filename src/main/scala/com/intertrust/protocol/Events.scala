package com.intertrust.protocol

import java.time.Instant

import enumeratum.{Enum, EnumEntry}

import scala.collection.immutable.IndexedSeq

sealed trait TurbineStatus extends EnumEntry

object TurbineStatus extends Enum[TurbineStatus] {
  override val values: IndexedSeq[TurbineStatus] = findValues

  case object Working extends TurbineStatus

  case object Broken extends TurbineStatus

}

trait Location {
  def id: String
}

case class Vessel(id: String) extends Location

case class Turbine(id: String) extends Location

sealed trait Movement extends EnumEntry

object Movement extends Enum[Movement] {
  override val values: IndexedSeq[Movement] = findValues

  case object Enter extends Movement

  case object Exit extends Movement

}

case class TurbineEvent(turbineId: String, status: TurbineStatus, generation: Double, timestamp: Instant)

case class MovementEvent(engineerId: String, location: Location, movement: Movement, timestamp: Instant)
