package com.intertrust.actors

import java.time.Instant

import akka.actor.{Actor, ActorLogging, ActorRef, Cancellable, Props}
import com.intertrust.actors.SimulationClockActor.{AskStartTimeMsg, ReplyStartTimeMsg, StartTickingMsg, TickMessage}

import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}
import scala.concurrent.duration._
import scala.language.postfixOps

/**
  * Keeps track of the global simulation clock and distributes common timing ticks to event simulator actors.
  *
  * The basic idea behind the clock is that every tick corresponds to 1 second in real time and simulation time with
  * simulationTimeFactor = 1. If the simulation time factor is increased, the the simulation time will also run
  * faster so that each simulation "second" will go faster, i.e. with factor 10 each tick will occur every 1/10 second,
  * i.e. one second simulation time will be over in 100ms of real time.
  */
object SimulationClockActor {
  def props(simulationTimeFactor: Float, simulatedActors: List[ActorRef]): Props =
    Props(new SimulationClockActor(simulationTimeFactor, simulatedActors))

  case class ReplyStartTimeMsg(time: Instant)

  case object AskStartTimeMsg

  case object StartTickingMsg

  case class TickMessage(time: Instant)

}


class SimulationClockActor(simulationTimeFactor: Float, simulatedActors: List[ActorRef]) extends Actor with ActorLogging {

  private var cancellable: Option[Cancellable] = None

  private var simulationTime: Option[Instant] = Option.empty

  private var expectedReplies = 0

  private var outputTime: Option[Instant] = Option.empty

  private val simHour = 0

  implicit val ec: ExecutionContextExecutor = ExecutionContext.global

  override def postStop(): Unit = {
    cancellable.foreach(_.cancel())
    cancellable = None
  }


  override def receive: Receive = onMessage(simHour)

  override def preStart(): Unit = {
    expectedReplies = simulatedActors.size
    simulatedActors.foreach(a => a ! AskStartTimeMsg)
  }

  def processTick(simTime: Instant): Instant = {
    simulatedActors.foreach(a => a ! TickMessage(simTime))
    simTime.plusSeconds(1)
  }

  private def onMessage(simHour: Int): Receive = {

    case ReplyStartTimeMsg(time) =>
      simulationTime = if (simulationTime.isEmpty) Option(time)
      else if (time.isBefore(simulationTime.get)) Option(time) else simulationTime
      expectedReplies -= 1
      // Everyone has replied -> start ticking
      if (expectedReplies == 0) self ! StartTickingMsg

    case TickMessage =>
      simulationTime = if (simulationTime.isDefined) Option(processTick(simulationTime.get)) else simulationTime
      if (outputTime.isDefined) {
        if (simulationTime.get.isAfter(outputTime.get)) {
          context.become(onMessage(simHour + 1))
          log.info("Simulation hour {} passed @ {}", simHour, simulationTime.get)
          outputTime = Option(outputTime.get.plusSeconds(3600))
        }
      } else {
        outputTime = if (simulationTime.isDefined) Option(simulationTime.get.plusSeconds(3600)) else Option.empty
      }

    case StartTickingMsg =>
      log.info("Simulation time starting at {} with speed rate of {}x (1 tick = {}ms)", simulationTime.get, simulationTimeFactor, 1000 / simulationTimeFactor)
      cancellable = Option(context.system.scheduler.schedule(0 seconds, (1000 / simulationTimeFactor) millis, self, TickMessage))
  }
}