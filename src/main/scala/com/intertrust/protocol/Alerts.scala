package com.intertrust.protocol

import java.time.Instant

case class TurbineAlert(timestamp: Instant, turbineId: String, error: String)

case class MovementAlert(timestamp: Instant, engineerId: String, error: String)
