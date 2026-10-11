package com.gemini.krakenbot.config

data class Settings(
    val loopDelaySeconds: Long,
    val deviationTriggerPercent: Double,
    /** Minimum \$2 enforced in ConfigService + UI. */
    val minimumOrderSizeUSD: Double = 5.0,
    // No Kotlin default — must be set in JSON/tests. Distinct from [simulation]: suppresses
    // order submission inside the active backend ([DRY RUN] / [EMULATOR DRY RUN]).
    val dryRun: Boolean,
    // Routes DynamicKrakenService to SimulatedKrakenService. Orthogonal to dryRun.
    val simulation: Boolean = false,
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
