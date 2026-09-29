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
     * Accepted comparison anchor in ISO-8601. Anchors the Buy & Hold comparison at a
     * verified later snapshot than [inceptionDate] while the strategy start itself is
     * preserved. Requires [inceptionDate] to be set and must not precede it.
     */
    val comparisonStartDate: String? = null,
    /**
     * Drawdown threshold percent deadband (0.0 to 100.0). No fiat is deployed until drawdown exceeds this value.
     */
    val fiatDeploymentThresholdPercent: Double = 0.0,
    /**
     * Fundamental quality scores per allocation symbol, refreshed outside the bot. Persisted
     * scores are positive and at most [MAX_QUALITY_SCORE]; the settings form treats zero or a blank
     * value as clearing that symbol's score. Assets with no entry (cash, gold) are excluded from
     * the weighted-quality metric rather than scored as zero.
     */
    val qualityScores: Map<String, Double> = emptyMap(),
) {
    companion object {
        const val MAX_QUALITY_SCORE = 10.0
    }
}
