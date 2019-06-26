package com.intertrust.actors

import java.time.Instant

import akka.actor.{ActorRef, Props}
import com.intertrust.protocol.TurbineEvent

object TurbineEventSimulatorActor {
  def props(turbineEvents: Iterator[TurbineEvent], problemAnalyzerActor: ActorRef): Props =
    Props(new TurbineEventSimulatorActor(turbineEvents, problemAnalyzerActor))
}

class TurbineEventSimulatorActor(turbineEvents: Iterator[TurbineEvent], eventMuxActor: ActorRef)
  extends EventSimulatorActor[TurbineEvent](turbineEvents, eventMuxActor) {
  override def getTimestamp(event: TurbineEvent): Instant = event.timestamp
}
