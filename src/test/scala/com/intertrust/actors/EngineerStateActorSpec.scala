package com.intertrust.actors

import java.time.Instant

import akka.actor.ActorSystem
import akka.testkit.{EventFilter, ImplicitSender, TestFSMRef, TestKit}
import com.intertrust.EnableAlerts
import com.intertrust.actors.EngineerDatas.{OutsideData, TurbineData, Uninitialized, VesselData}
import com.intertrust.actors.EngineerStates.{EngineerIndeterminate, InTurbine, InVessel, Outside}
import com.intertrust.protocol._
import com.typesafe.config.ConfigFactory
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, Matchers, WordSpecLike}

class EngineerStateActorSpec extends TestKit(ActorSystem("EngineerStateActorSpec", ConfigFactory.parseString("""akka.loggers = ["akka.testkit.TestEventListener"]""")))
  with ImplicitSender
  with WordSpecLike
  with Matchers
  with BeforeAndAfterAll
  with BeforeAndAfterEach {

  var engineer: TestFSMRef[EngineerStates.EngineerState, EngineerDatas.EngineerData, EngineerStateActor] = _

  override def beforeEach(): Unit = {
    engineer = TestFSMRef(new EngineerStateActor(testActor, "ENG1"))
    engineer ! EnableAlerts()
  }

  override def afterAll: Unit = {
    TestKit.shutdownActorSystem(system)
  }

  "Engineer state actor" must {

    "start in indeterminate state and with uninitialized data" in {
      engineer.stateName should be(EngineerIndeterminate)
      engineer.stateData should be(Uninitialized(alertsEnabled = true))
    }

    "log unhandled messages" in {
      EventFilter.warning(pattern = "received unhandled message RandomMessage*", occurrences = 1) intercept {
        engineer ! "RandomMessage"
      }
    }

    "initially enter vessel" in {
      val enterTime = Instant.now()

      engineer ! MovementEvent("ENG1", Vessel("V1"), Movement.Enter, enterTime)

      expectNoMessage()
      engineer.stateName should be(InVessel)
      engineer.stateData should be(VesselData(alertsEnabled = true, enterTime, "V1"))
    }

    "initially exit vessel" in {
      val exitTime = Instant.now()

      engineer ! MovementEvent("ENG1", Vessel("V1"), Movement.Exit, exitTime)

      expectNoMessage()
      engineer.stateName should be(Outside)
      engineer.stateData should be(OutsideData(alertsEnabled = true, exitTime))
    }

    "initially enter turbine" in {
      val enterTime = Instant.now()
      val evEnter = MovementEvent("ENG1", Turbine("T1"), Movement.Enter, enterTime)

      engineer ! MovementEvent("ENG1", Turbine("T1"), Movement.Enter, enterTime)

      expectNoMessage()
      engineer.stateName should be(InTurbine)
      engineer.stateData should be(TurbineData(alertsEnabled = true, evEnter))
    }

    "initially exit turbine" in {
      val exitTime = Instant.now()

      engineer ! MovementEvent("ENG1", Turbine("TUR1"), Movement.Exit, exitTime)

      expectNoMessage()
      engineer.stateName should be(Outside)
      engineer.stateData should be(OutsideData(alertsEnabled = true, exitTime))
    }

    "initially enter turbine twice" in {
      val enterTime1 = Instant.now()
      val enterTime2 = Instant.now()
      val exitTime = enterTime2.plusSeconds(60)
      val evEnter1 = MovementEvent("ENG1", Turbine("T1"), Movement.Enter, enterTime1)
      val evEnter2 = MovementEvent("ENG1", Turbine("T1"), Movement.Enter, enterTime2)

      // First enter is ok
      engineer ! evEnter1
      engineer.stateName should be(InTurbine)
      engineer.stateData should be(TurbineData(alertsEnabled = true, evEnter1))
      expectNoMessage()

      // Second enter is problematic
      engineer ! evEnter2
      expectMsg(MovementAlert(enterTime2, "ENG1", "Engineer enters turbine Turbine(T1) but he was already inside it!"))
      engineer.stateName should be(InTurbine)
      engineer.stateData should be(TurbineData(alertsEnabled = true, evEnter2))

      // Existing turbine to outside works even after multiple enters
      engineer ! MovementEvent("ENG1", Turbine("T1"), Movement.Exit, exitTime)
      expectNoMessage()
      engineer.stateName should be(Outside)
      engineer.stateData should be(OutsideData(alertsEnabled = true, exitTime))
    }

    "initially enter vessel twice" in {
      val enterTime1 = Instant.now()
      val enterTime2 = Instant.now()
      val exitTime = enterTime2.plusSeconds(60)
      val evEnter1 = MovementEvent("ENG1", Vessel("V1"), Movement.Enter, enterTime1)
      val evEnter2 = MovementEvent("ENG1", Vessel("V1"), Movement.Enter, enterTime2)

      // First enter is ok
      engineer ! evEnter1
      engineer.stateName should be(InVessel)
      engineer.stateData should be(VesselData(alertsEnabled = true, enterTime1, "V1"))
      expectNoMessage()

      // Second enter is problematic
      engineer ! evEnter2
      expectMsg(MovementAlert(enterTime2, "ENG1", "Engineer enters vessel Vessel(V1) but he was already inside it!"))
      engineer.stateName should be(InVessel)
      engineer.stateData should be(VesselData(alertsEnabled = true, enterTime1, "V1"))

      // Existing turbine to outside works even after multiple enters
      engineer ! MovementEvent("ENG1", Vessel("V1"), Movement.Exit, exitTime)
      expectNoMessage()
      engineer.stateName should be(Outside)
      engineer.stateData should be(OutsideData(alertsEnabled = true, exitTime))
    }

    "enter a turbine when inside vessel without exiting vessel first" in {
      val enterTimeV = Instant.now()
      val enterTimeT = Instant.now()
      val evEnter1 = MovementEvent("ENG1", Turbine("T1"), Movement.Enter, enterTimeT)

      // Initially engineer enters into vessel
      engineer ! MovementEvent("ENG1", Vessel("V1"), Movement.Enter, enterTimeV)
      expectNoMessage()
      engineer.stateName should be(InVessel)
      engineer.stateData should be(VesselData(alertsEnabled = true, enterTimeV, "V1"))

      // But then becomes error, engineer enters turbine without exiting vessel
      // We notify about this, but we will still let engineer move into turbine
      engineer ! evEnter1
      engineer.stateName should be(InTurbine)
      engineer.stateData should be(TurbineData(alertsEnabled = true, evEnter1))
      expectMsg(MovementAlert(enterTimeT, "ENG1", "Engineer enters Turbine(T1) but he didn't exit vessel (V1)!"))
    }

    "exit a turbine when inside vessel without exiting vessel/entering turbine first" in {
      val enterTimeV = Instant.now()
      val exitTimeT = Instant.now()
      val evEnter1 = MovementEvent("ENG1", Turbine("T1"), Movement.Exit, exitTimeT)

      // Initially engineer enters into vessel
      engineer ! MovementEvent("ENG1", Vessel("V1"), Movement.Enter, enterTimeV)
      expectNoMessage()
      engineer.stateName should be(InVessel)
      engineer.stateData should be(VesselData(alertsEnabled = true, enterTimeV, "V1"))

      // But then becomes error, engineer exits turbine without exiting vessel and entering turbine
      // We notify about this, but we will still let engineer move outside
      engineer ! evEnter1
      engineer.stateName should be(Outside)
      engineer.stateData should be(OutsideData(alertsEnabled = true, exitTimeT))
      expectMsg(MovementAlert(exitTimeT, "ENG1", "Engineer exits Turbine(T1) but he was in vessel V1!"))
    }

    "move into turbine from outside" in {
      val exitTime = Instant.now()
      val enterTime = Instant.now().plusSeconds(60)
      val evEnter = MovementEvent("ENG1", Turbine("TUR2"), Movement.Enter, enterTime)

      engineer ! MovementEvent("ENG1", Turbine("TUR1"), Movement.Exit, exitTime)
      expectNoMessage()
      engineer.stateName should be(Outside)
      engineer.stateData should be(OutsideData(alertsEnabled = true, exitTime))

      engineer ! MovementEvent("ENG1", Turbine("TUR2"), Movement.Enter, enterTime)
      expectNoMessage()
      engineer.stateName should be(InTurbine)
      engineer.stateData should be(TurbineData(alertsEnabled = true, evEnter))
    }

    "move into vessel from outside" in {
      val exitTime = Instant.now()
      val enterTime = Instant.now().plusSeconds(60)

      // Initially moving to outside from turbine
      engineer ! MovementEvent("ENG1", Turbine("TUR1"), Movement.Exit, exitTime)
      expectNoMessage()
      engineer.stateName should be(Outside)
      engineer.stateData should be(OutsideData(alertsEnabled = true, exitTime))

      engineer ! MovementEvent("ENG1", Vessel("V1"), Movement.Enter, enterTime)
      expectNoMessage()
      engineer.stateName should be(InVessel)
      engineer.stateData should be(VesselData(alertsEnabled = true, enterTime, "V1"))
    }

    "exit vessel/turbine but is already outside" in {
      val exitTime = Instant.now()
      val exitTimeT = Instant.now().plusSeconds(60)
      val exitTimeV = Instant.now().plusSeconds(120)

      // Initially exiting turbine to get into outside-state
      engineer ! MovementEvent("ENG1", Turbine("TUR1"), Movement.Exit, exitTime)
      expectNoMessage()
      engineer.stateName should be(Outside)
      engineer.stateData should be(OutsideData(alertsEnabled = true, exitTime))

      // Exiting from vessel -> ALERT
      engineer ! MovementEvent("ENG1", Vessel("V1"), Movement.Exit, exitTimeV)
      expectMsg(MovementAlert(exitTimeV, "ENG1", "Engineer exits Vessel(V1) but he was already outside!"))
      engineer.stateName should be(Outside)
      engineer.stateData should be(OutsideData(alertsEnabled = true, exitTimeV))

      // Exiting from turbine -> ALERT
      engineer ! MovementEvent("ENG1", Turbine("V1"), Movement.Exit, exitTimeT)
      expectMsg(MovementAlert(exitTimeT, "ENG1", "Engineer exits Turbine(V1) but he was already outside!"))
      engineer.stateName should be(Outside)
      engineer.stateData should be(OutsideData(alertsEnabled = true, exitTimeT))
    }

  }

}
