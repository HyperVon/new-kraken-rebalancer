package com.gemini.krakenbot.config

data class Settings(
    val loopDelaySeconds: Long,
    val deviationTriggerPercent: Double,
    val dustThresholdUSD: Double = 5.0,
    val dryRun: Boolean,
    val fiatMaxDrawdown: Double = 0.0,
    val fiatDeploymentExponent: Double = 1.0
)
