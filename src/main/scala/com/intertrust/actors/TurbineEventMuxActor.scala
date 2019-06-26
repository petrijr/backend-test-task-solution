package com.intertrust.actors

import java.time.Instant

import akka.actor.{Actor, ActorRef, Props}
import com.intertrust.protocol.TurbineEvent
import com.intertrust.{EnableAlerts, EventTimeRequest, TurbineEventTimeResponse}

/**
  * Receives turbine and movement events and multiplexes them to individual stateful actors that analyze problems related
  * to specific turbine or engineer.
  */
object TurbineEventMuxActor {
  def props(alertActor: ActorRef, simulationTimeRate: Float): Props =
    Props(new TurbineEventMuxActor(alertActor, simulationTimeRate))

  def tId(turbineId: String): String = "turbineStateActor" + turbineId
}

class TurbineEventMuxActor(alertActor: ActorRef, simulationTimeRate: Float) extends Actor {

  private val turbines = Map.empty[String, ActorRef]

  override def receive: Receive = onMessage(turbines)

  private def onMessage(turbines: Map[String, ActorRef]): Receive = {
    case EventTimeRequest() =>
      sender() ! TurbineEventTimeResponse(Map.empty[String, Instant])
    case event: TurbineEvent =>
      context.child(TurbineEventMuxActor.tId(event.turbineId)).getOrElse {
        val turbine = context.actorOf(TurbineStateActor.props(alertActor, event.turbineId, simulationTimeRate), TurbineEventMuxActor.tId(event.turbineId))
        context.become(onMessage(turbines + (TurbineEventMuxActor.tId(event.turbineId) -> turbine)))
        turbine ! EnableAlerts()
        turbine
      } ! event

  }
}

