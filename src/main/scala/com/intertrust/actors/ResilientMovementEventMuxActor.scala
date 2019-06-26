package com.intertrust.actors

import java.time.Instant

import akka.actor.{ActorContext, ActorLogging, ActorRef, Props}
import akka.persistence.{PersistentActor, RecoveryCompleted, SnapshotOffer}
import com.intertrust.{EnableAlerts, EventTimeRequest, MovementEventTimeResponse, TurbineEventTimeResponse}
import com.intertrust.protocol.{MovementEvent, TurbineEvent}

object ResilientMovementEventMuxActor {
  def props(alertActor: ActorRef, simulationTimeRate: Float): Props =
    Props(new ResilientMovementEventMuxActor(alertActor, simulationTimeRate))

  //def eId(engineerId: String): String = "engineerStateActor" + engineerId
}

case class PersistentEngineerState() {

  var engineers = Map.empty[String, ActorRef]
  var latestEventTimes = Map.empty[String, Instant]

  def update(context: ActorContext, alertActor: ActorRef, simulationTimeRate: Float, event: MovementEvent, recoveryRunning: Boolean): (PersistentEngineerState, ActorRef) = {

    val engineer = context.child(MovementEventMuxActor.eId(event.engineerId)).getOrElse {
      val engineer = context.actorOf(EngineerStateActor.props(alertActor, event.engineerId), MovementEventMuxActor.eId(event.engineerId))
      engineers += (MovementEventMuxActor.eId(event.engineerId) -> engineer)
      engineer
    }

    // Update latest seen timestamp for this turbine
    latestEventTimes += TurbineEventMuxActor.tId(event.engineerId) -> event.timestamp

    (this, engineer)
  }

  def notifyRecoveryCompleted(): Unit = {
    engineers.values.foreach(t => t ! EnableAlerts())
  }

  def canProcess(event: MovementEvent): Boolean =
    !latestEventTimes.contains(MovementEventMuxActor.eId(event.engineerId)) ||
      latestEventTimes(MovementEventMuxActor.eId(event.engineerId)).isBefore(event.timestamp)

}

class ResilientMovementEventMuxActor(alertActor: ActorRef, simulationTimeRate: Float) extends PersistentActor with ActorLogging {

  private var state = PersistentEngineerState()
  val snapShotInterval = 1000
  private var latestEventRecoveryTime: Option[Instant] = Option.empty

  override def persistenceId: String = "movement-event-mux-id"

  def updateState(event: MovementEvent): ActorRef = {
    latestEventRecoveryTime = Option.apply(event.timestamp)
    val updateResult = state.update(context, alertActor, simulationTimeRate, event, recoveryRunning)
    state = updateResult._1
    updateResult._2
  }

  override def receiveRecover: Receive = {
    case event: MovementEvent =>
      latestEventRecoveryTime = Option(event.timestamp)
      updateState(event)
    case SnapshotOffer(_, snapshot: PersistentEngineerState) => state = snapshot
    case RecoveryCompleted => state.notifyRecoveryCompleted()
  }

  override def receiveCommand: Receive = {
    case EventTimeRequest() =>
      sender() ! MovementEventTimeResponse(state.latestEventTimes)
    case event: MovementEvent =>
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


}
