package com.gemini.krakenbot.config

data class Settings(
    val loopDelaySeconds: Long,
    val deviationTriggerPercent: Double,
    /** Minimum \$2 enforced in ConfigService + UI. */
    val dustThresholdUSD: Double = 5.0,
    // No Kotlin default — must be set in JSON/tests. Distinct from [simulation]: suppresses
    // order submission inside the active backend ([DRY RUN] / [EMULATOR DRY RUN]).
    val dryRun: Boolean,
    val fiatMaxDrawdown: Double = 0.0,
    val fiatDeploymentExponent: Double = 1.0,
    // Routes DynamicKrakenService to SimulatedKrakenService. Orthogonal to dryRun.
    val simulation: Boolean = false,
)
