package com.intertrust.actors

import java.time.Instant
import java.time.temporal.ChronoUnit

import akka.actor.{ActorRef, FSM, Props}
import com.intertrust.EnableAlerts
import com.intertrust.actors.TurbineDatas._
import com.intertrust.actors.TurbineStates._
import com.intertrust.protocol._

import scala.concurrent.duration._
import scala.language.postfixOps

object TurbineStateActor {
  def props(alertActor: ActorRef, turbineId: String, simulationTimeRate: Float):
  Props = Props(new TurbineStateActor(alertActor, turbineId, simulationTimeRate))
}

final case class RemainsBrokenTick(notificationTargetTime: Instant)

object NotFixedTick

// States
object TurbineStates {

  sealed trait TurbineState

  case object Indeterminate extends TurbineState

  case object Broken extends TurbineState

  case object Fixing extends TurbineState

  case object Fixed extends TurbineState

  case object Working extends TurbineState

}

// Datas
object TurbineDatas {

  sealed trait TurbineData {
    val alertsEnabled: Boolean
  }

  final case class Uninitialized(alertsEnabled: Boolean) extends TurbineData

  final case class BrokeData(alertsEnabled: Boolean, time: Instant) extends TurbineData

  final case class WorkingData(alertsEnabled: Boolean) extends TurbineData

  final case class FixingEngineer(alertsEnabled: Boolean, time: Instant, engineerId: String) extends TurbineData

  final case class FixedData(alertsEnabled: Boolean, time: Instant, engineerId: String) extends TurbineData

}

class TurbineStateActor(alertActor: ActorRef, turbineId: String, simulationTimeFactor: Float) extends FSM[TurbineStates.TurbineState, TurbineDatas.TurbineData] {

  private val brokenTimeoutName = "brokenTimeout"
  private val remainsBrokenNotificationTime = 4
  private val fixedTimeout = "fixedTimeout"

  startWith(Indeterminate, Uninitialized(false))

  // Initial state after just creating the turbine without any events
  when(Indeterminate) {

    case Event(EnableAlerts(), _) =>
      stay() using Uninitialized(true)

    // Initially turbine is working
    case Event(e: TurbineEvent, u: Uninitialized) if isWorking(e) =>
      goto(Working) using WorkingData(u.alertsEnabled)

    // Initially turbine is broken
    case Event(e: TurbineEvent, u: Uninitialized) if isBroken(e) =>
      goto(Broken) using BrokeData(u.alertsEnabled, e.timestamp)

    case Event(_: MovementEvent, _) =>
      stay()
  }

  // Turbine is working
  when(Working) {

    case Event(EnableAlerts(), w: WorkingData) =>
      stay() using w

    // Turbine is still working
    case Event(e: TurbineEvent, w: WorkingData) if isWorking(e) =>
      cancelTimer(fixedTimeout)
      cancelTimer(brokenTimeoutName)
      stay() using w

    // Was working, but is now broken
    case Event(e: TurbineEvent, w: WorkingData) if isBroken(e) =>
      goto(Broken).using(BrokeData(w.alertsEnabled, e.timestamp))

    // Turbine is working, but engineer entered into it, maybe preventive maintenance?
    case Event(e: MovementEvent, w: WorkingData) if engineerEnters(e) =>
      stay() using WorkingData(w.alertsEnabled)
  }

  // Turbine is broken
  when(Broken) {

    case Event(EnableAlerts(), t: BrokeData) =>
      stay() using BrokeData(t.alertsEnabled, t.time)

    // Turbine is broken and remains broken
    case Event(e: TurbineEvent, t: BrokeData) if isBroken(e) =>
      stay() using BrokeData(t.alertsEnabled, t.time)

    // Turbine was broken but started working
    case Event(e: TurbineEvent, b: BrokeData) if isWorking(e) =>
      cancelTimer(brokenTimeoutName)
      goto(Working) using WorkingData(b.alertsEnabled)

    // Turbine is broken and engineer enters, assume fixing
    case Event(e: MovementEvent, b: BrokeData) if engineerEnters(e) =>
      cancelTimer(brokenTimeoutName)
      goto(Fixing) using FixingEngineer(b.alertsEnabled, e.timestamp, e.engineerId)

    // Turbine is broken and 4 hour timeout triggered
    case Event(bt: RemainsBrokenTick, b: BrokeData) =>
      cancelTimer(brokenTimeoutName)
      if (b.alertsEnabled)
        alertActor ! TurbineAlert(bt.notificationTargetTime, turbineId, s"Turbine has been broken for 4 hours (since ${b.time}) without engineer!")
      stay

    // Turbine is broken and not fixed timeout triggered
    case Event(NotFixedTick, b: BrokeData) =>
      cancelTimer(fixedTimeout)
      if (b.alertsEnabled)
        alertActor ! TurbineAlert(b.time, turbineId, s"Turbine $turbineId was not fixed by the engineer!")
      stay

  }

  // Turbine is being fixed (engineer has entered to turbine)
  when(Fixing) {

    case Event(EnableAlerts(), f: FixingEngineer) =>
      stay() using FixingEngineer(f.alertsEnabled, f.time, f.engineerId)

    // Turbine is being fixed but it is still broken so stay in this state
    case Event(e: TurbineEvent, _: FixingEngineer) if isBroken(e) =>
      stay

    // Turbine started working while it was being fixed
    case Event(e: TurbineEvent, f: FixingEngineer) if isWorking(e) =>
      goto(Working) using WorkingData(f.alertsEnabled)

    // Apparently another engineer enters after first has started fixing it
    case Event(e: MovementEvent, _: FixingEngineer) if engineerEnters(e) =>
      stay

    // Engineer exists, so assume fixed and start 3min timer
    case Event(e: MovementEvent, f: FixingEngineer) if engineerExits(e) =>
      setTimer(fixedTimeout, NotFixedTick, (3 / simulationTimeFactor) minutes, repeat = true)
      goto(Fixed) using FixedData(f.alertsEnabled, e.timestamp, e.engineerId)

  }

  // Turbine is supposed to be fixed after fixing engineer exited
  when(Fixed) {

    case Event(EnableAlerts(), f: FixedData) =>
      stay() using FixedData(f.alertsEnabled, f.time, f.engineerId)

    // Turbine started working after fixing
    case Event(e: TurbineEvent, f: FixedData) if isWorking(e) =>
      cancelTimer(fixedTimeout)
      goto(Working) using WorkingData(f.alertsEnabled)

    // Turbine was broken after fixing
    case Event(e: TurbineEvent, f: FixedData) if isBroken(e) =>
      goto(Broken) using BrokeData(f.alertsEnabled, e.timestamp)

    // Looks like all updates for turbine stopped coming after being fixed?!?
    case Event(NotFixedTick, f: FixedData) =>
      if (f.alertsEnabled)
        alertActor ! TurbineAlert(f.time, turbineId, s"Turbine was fixed at ${f.time} but there hasn't been any turbine state events since!")
      stay

  }

  whenUnhandled {
    case Event(e, s) =>
      log.warning("received unhandled message {} in state {}/{}", e, stateName, s)
      stay
  }

  onTransition {
    case Indeterminate -> Working =>
      log.info("Turbine {} registered in Working status!", turbineId)

    case (Working | Indeterminate) -> Broken =>
      if (stateName.equals(Indeterminate)) log.info("Turbine {} registered in Broken status!", turbineId)
      nextStateData match {
        case BrokeData(alertsEnabled, t) =>
          if (alertsEnabled) {
            alertActor ! TurbineAlert(t, turbineId, s"Turbine $turbineId broke!")
          }
          setTimer(brokenTimeoutName, RemainsBrokenTick(t.plus(remainsBrokenNotificationTime, ChronoUnit.HOURS)), (remainsBrokenNotificationTime / simulationTimeFactor) hours, repeat = false)
        case _ => // Nothing to do
      }

    case Broken -> Working =>
      log.info("Turbine {} started working by itself after having been broken.", turbineId)

    case Broken -> Fixing =>
      nextStateData match {
        case e: FixingEngineer => log.info("Engineer {} started fixing turbine {} at {}", e.engineerId, turbineId, e.time)
        case _ => // Nothing to do
      }

    case Fixing -> Working =>
      stateData match {
        case f: FixingEngineer => log.info("Turbine {} started working after being fixed by engineer {}.", turbineId, f.engineerId)
        case _ => // Nothing to do
      }
  }

  initialize()

  def engineerEnters(e: MovementEvent): Boolean =
    e.movement == Movement.Enter

  def engineerExits(e: MovementEvent): Boolean =
    e.movement == Movement.Exit

  def isBroken(e: TurbineEvent): Boolean =
    e.status == TurbineStatus.Broken

  def isWorking(e: TurbineEvent): Boolean =
    e.status == TurbineStatus.Working

}

