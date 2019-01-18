package com.intertrust.actors

import akka.actor.{Actor, ActorLogging}
import com.intertrust.protocol.{MovementAlert, TurbineAlert}

class AlertsActor extends Actor with ActorLogging {
  override def receive: Receive = {
    case a: TurbineAlert => log.error(a.toString)
    case a: MovementAlert => log.error(a.toString)
  }
}
