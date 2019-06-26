package com.intertrust.actors

import java.time.Instant

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestKit}
import com.intertrust.actors.SimulationClockActor.{AskStartTimeMsg, ReplyStartTimeMsg, TickMessage}
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, Matchers, WordSpecLike}

class SimulationClockActorSpec extends TestKit(ActorSystem("SimulationClockActorSpec")) with ImplicitSender
  with WordSpecLike
  with Matchers
  with BeforeAndAfterAll
  with BeforeAndAfterEach {

  override def afterAll: Unit = {
    TestKit.shutdownActorSystem(system)
  }

  "Simulation clock actor" must {

    "request simulation start time when initialized" in {

      val simTime = Instant.now()
      val clock = system.actorOf(SimulationClockActor.props(1.0f, List(self)))
      expectMsg(AskStartTimeMsg)
      clock ! ReplyStartTimeMsg(simTime)
      expectMsg(TickMessage(simTime))

    }
  }
}
