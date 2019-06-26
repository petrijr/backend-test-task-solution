package com.intertrust.actors

import java.time.Instant

import akka.actor.{Actor, ActorRef, Props}
import com.intertrust.protocol.MovementEvent
import com.intertrust.{EnableAlerts, EventTimeRequest, MovementEventTimeResponse}

object MovementEventMuxActor {
  def props(alertActor: ActorRef, simulationTimeRate: Float): Props =
    Props(new MovementEventMuxActor(alertActor, simulationTimeRate))

  def eId(engineerId: String): String = "engineerStateActor" + engineerId
}

class MovementEventMuxActor(alertActor: ActorRef, simulationTimeRate: Float) extends Actor {

  private val engineers = Map.empty[String, ActorRef]

  override def receive: Receive = onMessage(engineers)

  private def onMessage(engineers: Map[String, ActorRef]): Receive = {
    case EventTimeRequest() =>
      sender() ! MovementEventTimeResponse(Map.empty[String, Instant])

    case event: MovementEvent =>
      context.child(MovementEventMuxActor.eId(event.engineerId)).getOrElse {
        val engineer = context.actorOf(EngineerStateActor.props(alertActor, event.engineerId), MovementEventMuxActor.eId(event.engineerId))
        context.become(onMessage(engineers + (MovementEventMuxActor.eId(event.engineerId) -> engineer)))
        engineer ! EnableAlerts()
        engineer
      } ! event

  }
}
