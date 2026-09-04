package com.gemini.krakenbot.config

data class Settings(
    val loopDelaySeconds: Long,
    val deviationTriggerPercent: Double,
    /** Minimum \$2 enforced in ConfigService + UI. */
    val minimumOrderSizeUSD: Double = 5.0,
    // No Kotlin default — must be set in JSON/tests. Distinct from [simulation]: suppresses
    // order submission inside the active backend ([DRY RUN] / [EMULATOR DRY RUN]).
    val dryRun: Boolean,
    val fiatMaxDrawdown: Double = 0.0,
    val fiatDeploymentExponent: Double = 1.0,
    // Routes DynamicKrakenService to SimulatedKrakenService. Orthogonal to dryRun.
    val simulation: Boolean = false,
    /**
     * Strategy inception date in ISO-8601 (e.g. "2026-01-01" or "2026-01-01T00:00:00Z").
     * When null or blank, auto-detection from trade history is used.
     */
    val inceptionDate: String? = null,
    /**
     * Drawdown threshold percent deadband (0.0 to 100.0). No fiat is deployed until drawdown exceeds this value.
     */
    val fiatDeploymentThresholdPercent: Double = 0.0,
)
