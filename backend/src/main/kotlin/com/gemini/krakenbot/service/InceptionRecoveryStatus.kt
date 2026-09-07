package com.gemini.krakenbot.service

/**
 * Durable, operator-facing state for the bounded strategy-inception recovery pass.
 *
 * The fields are strings because the same values are emitted directly by the JSON history status
 * endpoint and because an empty value is the wire-compatible representation of unavailable progress.
 */
data class InceptionRecoveryStatus(
    val status: String = NOT_STARTED,
    val tradeOffset: String = "",
    val tradeTotal: String = "",
    val ledgerOffset: String = "",
    val ledgerTotal: String = "",
    val candidateTime: String? = null,
    val reason: String? = null,
    val coverageHorizon: String? = null,
) {
    companion object {
        const val NOT_STARTED = "NOT_STARTED"
        const val IN_PROGRESS = "IN_PROGRESS"
        const val COMPLETE = "COMPLETE"
        const val COMPLETE_NO_BOT_EVIDENCE = "COMPLETE_NO_BOT_EVIDENCE"
        const val CONFIRMED = "CONFIRMED"
        const val FAILED = "FAILED"
        const val AMBIGUOUS = "AMBIGUOUS"
        const val BASELINE_UNAVAILABLE = "BASELINE_UNAVAILABLE"
        const val UNAVAILABLE = "UNAVAILABLE"
        const val MANUAL_OVERRIDE = "MANUAL_OVERRIDE"
    }
}
