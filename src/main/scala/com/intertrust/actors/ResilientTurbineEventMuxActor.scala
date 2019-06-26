package com.intertrust.actors

import java.time.Instant

import akka.actor.{ActorContext, ActorLogging, ActorRef, Props}
import akka.persistence.{PersistentActor, RecoveryCompleted, SnapshotOffer}
import com.intertrust.protocol.TurbineEvent
import com.intertrust.{EnableAlerts, EventTimeRequest, TurbineEventTimeResponse}


/**
  * Receives turbine and movement events and multiplexes them to individual stateful actors that analyze problems related
  * to specific turbine or engineer.
  */
object ResilientTurbineEventMuxActor {
  def props(alertActor: ActorRef, simulationTimeRate: Float): Props =
    Props(new ResilientTurbineEventMuxActor(alertActor, simulationTimeRate))

  //def tId(turbineId: String): String = "turbineStateActor" + turbineId
}

case class TurbineState() {

  var turbines = Map.empty[String, ActorRef]
  var latestEventTimes = Map.empty[String, Instant]

  def update(context: ActorContext, alertActor: ActorRef, simulationTimeRate: Float, event: TurbineEvent, recoveryRunning: Boolean): (TurbineState, ActorRef) = {

    val turbine = context.child(TurbineEventMuxActor.tId(event.turbineId)).getOrElse {
      val turbine = context.actorOf(TurbineStateActor.props(alertActor, event.turbineId, simulationTimeRate), TurbineEventMuxActor.tId(event.turbineId))
      turbines += (TurbineEventMuxActor.tId(event.turbineId) -> turbine)
      if (!recoveryRunning) turbine ! EnableAlerts()
      turbine
    }

    // Update latest seen timestamp for this turbine
    latestEventTimes += TurbineEventMuxActor.tId(event.turbineId) -> event.timestamp

    (this, turbine)
  }

  def notifyRecoveryCompleted(): Unit = {
    turbines.values.foreach(t => t ! EnableAlerts())
  }

  def canProcess(event: TurbineEvent): Boolean =
    !latestEventTimes.contains(TurbineEventMuxActor.tId(event.turbineId)) ||
      latestEventTimes(TurbineEventMuxActor.tId(event.turbineId)).isBefore(event.timestamp)
}

class ResilientTurbineEventMuxActor(alertActor: ActorRef, simulationTimeRate: Float) extends PersistentActor with ActorLogging {

  private var state = TurbineState()
  val snapShotInterval = 1000
  private var latestEventRecoveryTime: Option[Instant] = Option.empty

  def updateState(event: TurbineEvent): ActorRef = {
    latestEventRecoveryTime = Option.apply(event.timestamp)
    val updateResult = state.update(context, alertActor, simulationTimeRate, event, recoveryRunning)
    state = updateResult._1
    updateResult._2
  }

  override def receiveRecover: Receive = {
    case event: TurbineEvent =>
      latestEventRecoveryTime = Option(event.timestamp)
      updateState(event) ! event
    case SnapshotOffer(_, snapshot: TurbineState) => state = snapshot
    case RecoveryCompleted => state.notifyRecoveryCompleted()
  }

  override def receiveCommand: Receive = {
    case EventTimeRequest() =>
      sender() ! TurbineEventTimeResponse(state.latestEventTimes)
    case event: TurbineEvent =>
      if (state.canProcess(event)) {
        persist(event) {
          event =>
            updateState(event) ! event
            context.system.eventStream.publish(event)
            if (lastSequenceNr % snapShotInterval == 0 && lastSequenceNr != 0) {
              saveSnapshot(state)
            }
        }
      }
  }

  override def persistenceId: String = "turbine-event-mux-id"
}

