package com.intertrust

import akka.actor.{ActorSystem, Props}
import com.intertrust.actors.AlertsActor
import com.intertrust.parsers.{MovementEventParser, TurbineEventParser}

import scala.io.Source

object Simulator {
  def main(args: Array[String]): Unit = {
    val system = ActorSystem("simulator")

    val alertsActor = system.actorOf(Props(classOf[AlertsActor]), "alerts")

    val movementEvents = new MovementEventParser().parseEvents(Source.fromInputStream(getClass.getResourceAsStream("movements.cvs")))
    val turbineEvents = new TurbineEventParser().parseEvents(Source.fromInputStream(getClass.getResourceAsStream("turbines.cvs")))

    // TODO: Implement events processing that sends alerts to the `alertsActor`

    system.terminate()
  }
}
