package com.intertrust.actors

import java.time.Instant

import akka.actor.{ActorRef, FSM, Props}
import com.intertrust.EnableAlerts
import com.intertrust.actors.EngineerDatas._
import com.intertrust.actors.EngineerStates.{EngineerIndeterminate, EngineerState, InTurbine, InVessel, Outside}
import com.intertrust.protocol._

object EngineerStateActor {
  def props(alertActor: ActorRef, engineerId: String): Props = Props(new EngineerStateActor(alertActor, engineerId))
}

// States
object EngineerStates {

  sealed trait EngineerState

  case object EngineerIndeterminate extends EngineerState

  case object InVessel extends EngineerState

  case object InTurbine extends EngineerState

  case object Outside extends EngineerState

}

// Datas
object EngineerDatas {

  sealed trait EngineerData {
    val alertsEnabled: Boolean
  }

  final case class Uninitialized(alertsEnabled: Boolean) extends EngineerData

  final case class OutsideData(alertsEnabled: Boolean, time: Instant) extends EngineerData

  final case class VesselData(alertsEnabled: Boolean, time: Instant, vesselId: String) extends EngineerData

  final case class TurbineData(alertsEnabled: Boolean, event: MovementEvent) extends EngineerData

}

/**
  * State machine for simulating states of a single engineer.
  *
  * @param alertActor Actor to receive alerts from this engineer.
  * @param engineerId Identifies the engineer.
  */
class EngineerStateActor(alertActor: ActorRef, engineerId: String) extends FSM[EngineerState, EngineerData] {

  startWith(EngineerIndeterminate, Uninitialized(false))

  // Initial state when we see this engineer first time
  when(EngineerIndeterminate) {
    case Event(EnableAlerts(), _) =>
      stay() using Uninitialized(true)

    case Event(e: MovementEvent, d: Uninitialized)
      if engineerEnters(e, classOf[Vessel]) =>
      goto(InVessel) using VesselData(d.alertsEnabled, e.timestamp, e.location.id)

    case Event(e: MovementEvent, d: Uninitialized) if engineerExits(e, classOf[Vessel]) =>
      goto(Outside) using OutsideData(d.alertsEnabled, e.timestamp)

    case Event(e: MovementEvent, d: Uninitialized) if engineerEnters(e, classOf[Turbine]) =>
      goto(InTurbine) using TurbineData(d.alertsEnabled, e)

    case Event(e: MovementEvent, d: Uninitialized) if engineerExits(e, classOf[Turbine]) =>
      goto(Outside) using OutsideData(d.alertsEnabled, e.timestamp)
  }

  // Engineer is in vessel
  when(InVessel) {
    case Event(EnableAlerts(), v: VesselData) =>
      stay using VesselData(v.alertsEnabled, v.time, v.vesselId)

    case Event(e: MovementEvent, v: VesselData) if engineerExits(e, classOf[Vessel]) =>
      goto(Outside) using OutsideData(v.alertsEnabled, e.timestamp)

    case Event(e: MovementEvent, v: VesselData) if engineerEnters(e, classOf[Vessel]) =>
      if (v.alertsEnabled)
        alertActor ! MovementAlert(e.timestamp, engineerId, s"Engineer enters vessel ${e.location} but he was already inside it!")
      stay() using VesselData(v.alertsEnabled, e.timestamp, e.location.id)

    case Event(e: MovementEvent, v: VesselData) if engineerEnters(e, classOf[Turbine]) =>
      if (v.alertsEnabled)
        alertActor ! MovementAlert(e.timestamp, engineerId, s"Engineer enters ${e.location} but he didn't exit vessel (${v.vesselId})!")
      goto(InTurbine) using TurbineData(v.alertsEnabled, e)

    case Event(e: MovementEvent, v: VesselData) if engineerExits(e, classOf[Turbine]) =>
      if (v.alertsEnabled)
        alertActor ! MovementAlert(e.timestamp, engineerId, s"Engineer exits ${e.location} but he was in vessel ${v.vesselId}!")
      goto(Outside) using OutsideData(v.alertsEnabled, e.timestamp)
  }

  // Engineer is in turbine
  when(InTurbine) {
    case Event(EnableAlerts(), t: TurbineData) =>
      stay() using TurbineData(t.alertsEnabled, t.event)

    case Event(e: MovementEvent, t: TurbineData) if engineerExits(e, classOf[Turbine]) =>
      goto(Outside) using OutsideData(t.alertsEnabled, e.timestamp)

    case Event(e: MovementEvent, t: TurbineData) if engineerEnters(e, classOf[Turbine]) =>
      if (t.alertsEnabled)
        alertActor ! MovementAlert(e.timestamp, engineerId, s"Engineer enters turbine ${e.location} but he was already inside it!")
      stay() using TurbineData(t.alertsEnabled, e)
  }

  // Engineer is outside of vessel/turbine
  when(Outside) {
    case Event(EnableAlerts(), o: OutsideData) =>
      stay() using OutsideData(o.alertsEnabled, o.time)

    case Event(e: MovementEvent, o: OutsideData) if e.movement == Movement.Exit =>
      if (o.alertsEnabled)
        alertActor ! MovementAlert(e.timestamp, engineerId, s"Engineer exits ${e.location} but he was already outside!")
      stay() using OutsideData(o.alertsEnabled, e.timestamp)

    case Event(e: MovementEvent, o: OutsideData) if engineerEnters(e, classOf[Turbine]) =>
      goto(InTurbine) using TurbineData(o.alertsEnabled, e)

    case Event(e: MovementEvent, o: OutsideData) if engineerEnters(e, classOf[Vessel]) =>
      goto(InVessel) using VesselData(o.alertsEnabled, e.timestamp, e.location.id)
  }

  whenUnhandled {
    case Event(e, s) =>
      log.warning("received unhandled message {} in state {}/{}", e, stateName, s)
      stay
  }

  onTransition {

    case EngineerIndeterminate -> InTurbine =>
      nextStateData match {
        case data: TurbineData => context.child(TurbineEventMuxActor.tId(data.event.location.id)).foreach(turbine => turbine ! data.event)
        case _ =>
      }

    // Notify turbine-actor when engineer enters turbine from outside
    case Outside -> InTurbine =>
      nextStateData match {
        case data: TurbineData =>
          context.actorSelection("akka://simulator/user/turbineMuxActor/".concat(TurbineEventMuxActor.tId(data.event.location.id))) ! data.event
        case _ =>
      }

    // Notify turbine-actor when engineer exits the turbine
    case InTurbine -> Outside =>
      stateData match {
        case data: TurbineData => context.actorSelection("akka://simulator/user/turbineMuxActor/".concat(TurbineEventMuxActor.tId(data.event.location.id))) ! data.event
        case _ =>
      }

  }

  def engineerEnters(e: MovementEvent, a: Class[_]): Boolean =
    e.movement == Movement.Enter && a.isInstance(e.location)

  def engineerExits(e: MovementEvent, a: Class[_]): Boolean =
    e.movement == Movement.Exit && a.isInstance(e.location)

}
