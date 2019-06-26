package com.intertrust.actors

import java.time.Instant

import akka.actor.{ActorRef, Props}
import com.intertrust.protocol.MovementEvent

object MovementEventSimulatorActor {
  def props(movementEvents: Iterator[MovementEvent], problemAnalyzerActor: ActorRef): Props =
    Props(new MovementEventSimulatorActor(movementEvents, problemAnalyzerActor))
}

class MovementEventSimulatorActor(movementEvents: Iterator[MovementEvent], muxActor: ActorRef)
  extends EventSimulatorActor[MovementEvent](movementEvents, muxActor) {
  override def getTimestamp(event: MovementEvent): Instant = event.timestamp
}
