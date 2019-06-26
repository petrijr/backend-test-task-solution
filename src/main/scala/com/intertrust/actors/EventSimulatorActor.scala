package com.intertrust.actors

import java.time.Instant

import akka.actor.{Actor, ActorRef}
import com.intertrust.actors.SimulationClockActor.{AskStartTimeMsg, ReplyStartTimeMsg, TickMessage}

import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}

case object ReadyMessage

/** Base class for simulating event sending in correct simulation time.
  *
  * @param events   Items to send.
  * @param muxActor Actor that will receive the events.
  * @tparam T Event type as defined by the inheriting class.
  */
abstract class EventSimulatorActor[T](events: Iterator[T], muxActor: ActorRef) extends Actor {

  private var hostApplication: Option[ActorRef] = None
  private var firstEvent: Option[T] = Option.empty
  implicit val ec: ExecutionContextExecutor = ExecutionContext.global

  override def receive: Receive = {
    case AskStartTimeMsg => sender() ! ReplyStartTimeMsg(startTime())

    case TickMessage(time) => {
      firstEvent = processTickEvents(time, firstEvent)
      if (firstEvent.isEmpty) {
        context.stop(self)
        hostApplication.foreach(a => a ! "Done")
      }
    }

    case ReadyMessage => hostApplication = Option.apply(sender())
  }

  def startTime(): Instant = {

    if (firstEvent.isEmpty)
      firstEvent = Option.apply(events.next())

    getTimestamp(firstEvent.get)
  }


  /** Sends all events for specified time instant, if there are any.
    *
    * @param simTime    Current simulation time
    * @param firstEvent First event for the next batch or empty if there is no event waiting to be send.
    * @return First event to be sent on the next batch or empty if there are no events waiting to be sent.
    */
  def processTickEvents(simTime: Instant, firstEvent: Option[T]): Option[T] = {

    if (!events.hasNext) {
      return Option.empty
    }

    // Check if we have a previous event that should be send first
    firstEvent match {
      case Some(ev) =>
        if (!getTimestamp(ev).isAfter(simTime)) {
          // Simulation timehas proceeded to next batch, so send first event of batch
          muxActor ! ev
        } else {
          // Time for event hasn't yet arrived, so return it to be send at next tick
          return Option.apply(ev)
        }
      case None =>
    }

    // First event for the next batch was sent or there was none, so proceed with events
    val nextEvent = events.next()

    if (!getTimestamp(nextEvent).isAfter(simTime)) {
      // Event time is at most simtime so can be sent
      muxActor ! nextEvent
      processTickEvents(simTime, Option.empty)
    } else {
      // Nope, this event can't be sent yet so return it as first event for next batch
      Option.apply(nextEvent)
    }
  }

  def getTimestamp(event: T): Instant
}
