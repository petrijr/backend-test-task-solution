package com.intertrust

import java.time.Instant

import akka.actor.{ActorSystem, Props}
import akka.pattern.ask
import akka.util.Timeout
import com.intertrust.actors._
import com.intertrust.parsers.{MovementEventParser, TurbineEventParser}
import com.intertrust.protocol.{MovementEvent, TurbineEvent}

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.io.Source
import scala.language.postfixOps

case class EnableAlerts()

case class EventTimeRequest()

case class TurbineEventTimeResponse(turbineEventTimes: Map[String, Instant]) {
  def canProcessEvent(event: TurbineEvent): Boolean =
    !turbineEventTimes.contains(TurbineEventMuxActor.tId(event.turbineId)) ||
      event.timestamp.isAfter(turbineEventTimes(TurbineEventMuxActor.tId(event.turbineId)))
}


case class MovementEventTimeResponse(movementEventTimes: Map[String, Instant]) {
  def canProcessEvent(event: MovementEvent): Boolean =
    !movementEventTimes.contains(MovementEventMuxActor.eId(event.engineerId)) ||
      event.timestamp.isAfter(movementEventTimes(MovementEventMuxActor.eId(event.engineerId)))
}

object Simulator {
  def main(args: Array[String]): Unit = {

    val system = ActorSystem("simulator")

    // How many times faster is the simulation time compared to calendar time
    // 1 = Real-time (1 tick = 1 second)
    // 2 = Twice as fast (1 tick = 500ms)
    // 10 = Ten times as fast (1 tick = 100ms)
    val sevenMinutesSimtime = (7 * 24 * 3600) / (7 * 60) // Use this to run the whole simulation through in ~7 minutes
    val seventySecSimtime = (7 * 24 * 3600) / 70 // Use this to run the whole simulation through in ~70 sec

    // You can change desired simulation rate below
    val simulationTimeRate: Float = seventySecSimtime

    // You can change this
    // True to use resilience, False to not use resilience
    val isResilient = false

    val alertsActor = system.actorOf(Props(classOf[AlertsActor]), "alerts")

    val movementEvents = new MovementEventParser().parseEvents(Source.fromResource("movements.csv"))
    val turbineEvents = new TurbineEventParser().parseEvents(Source.fromResource("turbines.csv"))

    // TODO: Implement events processing that sends alerts to the `alertsActor`
    val turbineMuxActor =
      if (isResilient) system.actorOf(ResilientTurbineEventMuxActor.props(alertsActor, simulationTimeRate), name = "turbineMuxActor")
      else system.actorOf(TurbineEventMuxActor.props(alertsActor, simulationTimeRate), name = "turbineMuxActor")

    implicit val timeout: Timeout = Timeout(10000 second)
    val turbineEventTimesFuture: Future[Any] = turbineMuxActor ? EventTimeRequest()
    Await.result(turbineEventTimesFuture, Duration.Inf)
    val latestTurbineTimes: TurbineEventTimeResponse = turbineEventTimesFuture.value.get.get.asInstanceOf[TurbineEventTimeResponse]

    val movementMuxActor =
      if (isResilient) system.actorOf(ResilientMovementEventMuxActor.props(alertsActor, simulationTimeRate), name = "movementMuxActor")
      else system.actorOf(MovementEventMuxActor.props(alertsActor, simulationTimeRate), name = "movementMuxActor")

    val movementEventTimesFuture: Future[Any] = movementMuxActor ? EventTimeRequest()
    Await.result(movementEventTimesFuture, Duration.Inf)
    val latestMovementTimes: MovementEventTimeResponse = movementEventTimesFuture.value.get.get.asInstanceOf[MovementEventTimeResponse]

    val turbineTickActor = system.actorOf(TurbineEventSimulatorActor.props(
      turbineEvents
        .filter(t => isValid(t))
        .filter(t => latestTurbineTimes.canProcessEvent(t)),
      turbineMuxActor)
      , "turbineTickActor"
    )

    val movementTickActor = system.actorOf(MovementEventSimulatorActor.props(
      movementEvents
        .filter(t => latestMovementTimes.canProcessEvent(t)),
      movementMuxActor), "movementTickActor")

    system.actorOf(SimulationClockActor.props(simulationTimeRate, List(turbineTickActor, movementTickActor)), name = "simClockActor")

    val ready1 = movementTickActor ? ReadyMessage
    val ready2 = turbineTickActor ? ReadyMessage
    Await.result(ready1, Duration.Inf)
    Await.result(ready2, Duration.Inf)

    println("All events consumed, terminating simulator!\n")
    system.terminate()
  }

  // Checks the validity of a TurbineEvent
  def isValid(event: TurbineEvent): Boolean = {
    !event.turbineId.matches("Location")
  }
}
