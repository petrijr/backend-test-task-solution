package com.intertrust.actors

import java.time.Instant

import akka.actor.ActorSystem
import akka.pattern.ask
import akka.testkit.{DefaultTimeout, EventFilter, ImplicitSender, TestFSMRef, TestKit}
import com.intertrust.EnableAlerts
import com.intertrust.actors.TurbineDatas._
import com.intertrust.actors.TurbineStates._
import com.intertrust.protocol._
import com.typesafe.config.ConfigFactory
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, Matchers, WordSpecLike}

import scala.concurrent.duration._
import scala.language.postfixOps

class TurbineStateActorSpec extends TestKit(ActorSystem("TurbineStateActorSpec", ConfigFactory.parseString("""akka.loggers = ["akka.testkit.TestEventListener"]""")))
  with ImplicitSender
  with WordSpecLike
  with Matchers
  with BeforeAndAfterAll
  with BeforeAndAfterEach
  with DefaultTimeout {

  var turbine: TestFSMRef[TurbineStates.TurbineState, TurbineDatas.TurbineData, TurbineStateActor] = _
  val simulationTimeRate = 7200

  override def afterAll: Unit = {
    TestKit.shutdownActorSystem(system)
  }

  override protected def beforeEach(): Unit = {
    turbine = TestFSMRef(new TurbineStateActor(testActor, "TUR1", simulationTimeRate))
    turbine ! EnableAlerts()
  }

  override protected def afterEach(): Unit = {
    turbine.stop()
  }

  "Turbine state actor" must {

    "start in indeterminate state with uninitialized data" in {
      turbine.stateName should be(Indeterminate)
      turbine.stateData should be(Uninitialized(alertsEnabled = true))
    }

    "log unhandled messages" in {
      EventFilter.warning(pattern = "received unhandled message RandomMessage*", occurrences = 1) intercept {
        turbine ! "RandomMessage"
      }
    }

    "turbine is initially working, another working event cancels timers" in {
      val workingTime = Instant.now()

      turbine ! TurbineEvent("TUR1", TurbineStatus.Working, 1.0, workingTime)
      expectNoMessage()
      turbine.stateName should be(Working)
      turbine.stateData should be(WorkingData(true))

      turbine setTimer("brokenTimeout", RemainsBrokenTick(workingTime.plusSeconds(60)), 1 hours, repeat = false)
      turbine setTimer("fixedTimeout", NotFixedTick, 10 minutes, repeat = false)
      assert(turbine.isTimerActive("brokenTimeout"))
      assert(turbine.isTimerActive("fixedTimeout"))

      turbine ! TurbineEvent("TUR1", TurbineStatus.Working, 1.0, workingTime)
      expectNoMessage()
      turbine.stateName should be(Working)
      turbine.stateData should be(WorkingData(true))
      assert(!turbine.isTimerActive("brokenTimeout"))
      assert(!turbine.isTimerActive("fixedTimeout"))
    }

    "turbine is initially working, engineer enters there without need" in {
      val workingTime = Instant.now()
      val enterTime = workingTime.plusSeconds(60)

      turbine ! TurbineEvent("TUR1", TurbineStatus.Working, 1.0, workingTime)
      expectNoMessage()
      turbine.stateName should be(Working)
      turbine.stateData should be(WorkingData(true))

      // No warning to log about second entering by the same engineer
      EventFilter.warning(pattern = ".*", occurrences = 0) intercept {
        turbine ! MovementEvent("ENG1", Turbine("TUR1"), Movement.Enter, enterTime)
      }
      expectNoMessage()
      turbine.stateName should be(Working)
      turbine.stateData should be(WorkingData(true))
    }

    "turbine is initially broken and starts working without engineer" in {
      val brokenTime = Instant.now()

      turbine ? TurbineEvent("TUR1", TurbineStatus.Broken, 1.0, brokenTime)
      expectMsg(TurbineAlert(brokenTime, "TUR1", "Turbine TUR1 broke!"))
      turbine.stateName should be(Broken)
      turbine.stateData should be(BrokeData(alertsEnabled = true, brokenTime))
      assert(turbine.isTimerActive("brokenTimeout"))

      // Turbine started working automatically, expect 4h alert timer to be cancelled
      turbine ? TurbineEvent("TUR1", TurbineStatus.Working, 1.0, brokenTime.plusSeconds(5))
      expectNoMessage(1 seconds)
      turbine.stateName should be(Working)
      turbine.stateData should be(WorkingData(true))
      assert(!turbine.isTimerActive("brokenTimeout"))
    }

    "turbine is initially broken and then engineer fixes it" in {
      // Need slower time rate for this
      turbine = TestFSMRef(new TurbineStateActor(testActor, "TUR1", 1))
      turbine ! EnableAlerts()

      val brokenTime = Instant.now()

      turbine ? TurbineEvent("TUR1", TurbineStatus.Broken, 1.0, brokenTime)
      expectMsg(TurbineAlert(brokenTime, "TUR1", "Turbine TUR1 broke!"))
      turbine.stateName should be(Broken)
      turbine.stateData should be(BrokeData(alertsEnabled = true, brokenTime))

      turbine ? TurbineEvent("TUR1", TurbineStatus.Broken, 1.0, brokenTime.plusSeconds(1))
      expectNoMessage(1 seconds)
      turbine.stateName should be(Broken)
      turbine.stateData should be(BrokeData(alertsEnabled = true, brokenTime))
      assert(turbine.isTimerActive("brokenTimeout"))

      // Engineer enters to broken turbine, 4h alert is cancelled and turbine moves to fixing state
      turbine ? MovementEvent("ENG1", Turbine("TUR1"), Movement.Enter, brokenTime.plusSeconds(10))
      expectNoMessage(1 seconds)
      turbine.stateName should be(Fixing)
      turbine.stateData should be(FixingEngineer(alertsEnabled = true, brokenTime.plusSeconds(10), "ENG1"))
      assert(!turbine.isTimerActive("brokenTimeout"))

      // Engineer exits
      turbine ? MovementEvent("ENG1", Turbine("TUR1"), Movement.Exit, brokenTime.plusSeconds(20))
      assert(turbine.isTimerActive("fixedTimeout"))
      expectNoMessage()
      turbine.stateName should be(Fixed)
      turbine.stateData should be(FixedData(alertsEnabled = true, brokenTime.plusSeconds(20), "ENG1"))

      // Turbine becomes fixed
      turbine ? TurbineEvent("TUR1", TurbineStatus.Working, 1.0, brokenTime.plusSeconds(30))
      expectNoMessage(1 seconds)
      turbine.stateName should be(Working)
      turbine.stateData should be(WorkingData(true))
      assert(!turbine.isTimerActive("fixedTimeout"))

    }

    "turbine is initially broken and then engineer fixes it but it doesn't get fixed" in {
      // Need slower time rate for this
      turbine = TestFSMRef(new TurbineStateActor(testActor, "TUR1", 60))
      turbine ! EnableAlerts()

      val brokenTime = Instant.now()

      turbine ? TurbineEvent("TUR1", TurbineStatus.Broken, 1.0, brokenTime)
      expectMsg(TurbineAlert(brokenTime, "TUR1", "Turbine TUR1 broke!"))
      turbine.stateName should be(Broken)
      turbine.stateData should be(BrokeData(alertsEnabled = true, brokenTime))

      turbine ? TurbineEvent("TUR1", TurbineStatus.Broken, 1.0, brokenTime.plusSeconds(1))
      expectNoMessage(1 seconds)
      turbine.stateName should be(Broken)
      turbine.stateData should be(BrokeData(alertsEnabled = true, brokenTime))
      assert(turbine.isTimerActive("brokenTimeout"))

      // Engineer enters to broken turbine, 4h alert is cancelled and turbine moves to fixing state
      turbine ? MovementEvent("ENG1", Turbine("TUR1"), Movement.Enter, brokenTime.plusSeconds(10))
      expectNoMessage(1 seconds)
      turbine.stateName should be(Fixing)
      turbine.stateData should be(FixingEngineer(alertsEnabled = true, brokenTime.plusSeconds(10), "ENG1"))
      assert(!turbine.isTimerActive("brokenTimeout"))

      // Engineer exits
      turbine ? MovementEvent("ENG1", Turbine("TUR1"), Movement.Exit, brokenTime.plusSeconds(20))
      assert(turbine.isTimerActive("fixedTimeout"))
      expectNoMessage()
      turbine.stateName should be(Fixed)
      turbine.stateData should be(FixedData(alertsEnabled = true, brokenTime.plusSeconds(20), "ENG1"))
      assert(turbine.isTimerActive("fixedTimeout"))

      // Turbine is still broken
      turbine ? TurbineEvent("TUR1", TurbineStatus.Broken, 1.0, brokenTime.plusSeconds(30))
      turbine.stateName should be(Broken)
      turbine.stateData should be(BrokeData(alertsEnabled = true, brokenTime.plusSeconds(30)))
      expectMsg(4 seconds, TurbineAlert(brokenTime.plusSeconds(30), "TUR1", s"Turbine TUR1 was not fixed by the engineer!"))
    }

    "turbine is initially broken and then engineer fixes it but it doesn't get fixed but there is no status update from turbine for a while" in {
      // Need slower time rate for this
      turbine = TestFSMRef(new TurbineStateActor(testActor, "TUR1", 60))
      turbine ! EnableAlerts()

      val brokenTime = Instant.now()

      turbine ? TurbineEvent("TUR1", TurbineStatus.Broken, 1.0, brokenTime)
      expectMsg(TurbineAlert(brokenTime, "TUR1", "Turbine TUR1 broke!"))
      turbine.stateName should be(Broken)
      turbine.stateData should be(BrokeData(alertsEnabled = true, brokenTime))

      turbine ? TurbineEvent("TUR1", TurbineStatus.Broken, 1.0, brokenTime.plusSeconds(1))
      expectNoMessage(1 seconds)
      turbine.stateName should be(Broken)
      turbine.stateData should be(BrokeData(alertsEnabled = true, brokenTime))
      assert(turbine.isTimerActive("brokenTimeout"))

      // Engineer enters to broken turbine, 4h alert is cancelled and turbine moves to fixing state
      turbine ? MovementEvent("ENG1", Turbine("TUR1"), Movement.Enter, brokenTime.plusSeconds(10))
      expectNoMessage(1 seconds)
      turbine.stateName should be(Fixing)
      turbine.stateData should be(FixingEngineer(alertsEnabled = true, brokenTime.plusSeconds(10), "ENG1"))
      assert(!turbine.isTimerActive("brokenTimeout"))

      // Engineer exits
      turbine ? MovementEvent("ENG1", Turbine("TUR1"), Movement.Exit, brokenTime.plusSeconds(20))
      assert(turbine.isTimerActive("fixedTimeout"))
      expectNoMessage()
      turbine.stateName should be(Fixed)
      val fixedTime = brokenTime.plusSeconds(20)
      turbine.stateData should be(FixedData(alertsEnabled = true, fixedTime, "ENG1"))
      assert(turbine.isTimerActive("fixedTimeout"))

      // No status update in 4sec, fixedTimeout triggers in FixingState
      expectMsg(4 seconds, TurbineAlert(brokenTime.plusSeconds(20), "TUR1",
        s"Turbine was fixed at $fixedTime but there hasn't been any turbine state events since!"))

      turbine.stateName should be(Fixed)
      turbine.stateData should be(FixedData(alertsEnabled = true, brokenTime.plusSeconds(20), "ENG1"))
    }

    "turbine is working and then breaks and alert is sent and after 4 hours another alert is sent" in {
      val workingTime = Instant.parse("2015-11-23T03:00:00Z")
      val breakingTime = workingTime.plusSeconds(4)

      turbine ! TurbineEvent("TUR1", TurbineStatus.Working, simulationTimeRate, workingTime)
      expectNoMessage()
      turbine.stateName should be(Working)
      turbine.stateData should be(WorkingData(true))

      // Initial breakage
      turbine ? TurbineEvent("TUR1", TurbineStatus.Broken, simulationTimeRate, breakingTime)
      expectMsg(TurbineAlert(breakingTime, "TUR1", "Turbine TUR1 broke!"))
      turbine.stateName should be(Broken)
      turbine.stateData should be(BrokeData(alertsEnabled = true, breakingTime))
      assert(turbine.isTimerActive("brokenTimeout"))

      // Alert is send after 2 seconds in real time (4 hours in simulation time)
      expectMsg(3 seconds, TurbineAlert(breakingTime.plusSeconds(4 * 3600), "TUR1",
        s"Turbine has been broken for 4 hours (since $breakingTime) without engineer!"))
      assert(!turbine.isTimerActive("brokenTimeout"))
    }

  }


}
