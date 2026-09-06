package com.gemini.krakenbot.api

/**
 * History `/api/history/sync-progress` JSON body.
 * Property names align with the API-facing sync metadata keys (`seeded`, `offset`, `total`).
 */
data class SyncProgressResponse(
    val seeded: Boolean,
    val offset: String,
    val total: String,
    val recoveryStatus: String = "",
    val recoveryTradeOffset: String = "",
    val recoveryTradeTotal: String = "",
    val recoveryLedgerOffset: String = "",
    val recoveryLedgerTotal: String = "",
    val recoveryCandidate: String? = null,
    val recoveryReason: String? = null,
    val recoveryHorizon: String? = null,
) {
    companion object {
        const val RECOVERY_IN_PROGRESS = "IN_PROGRESS"
        const val RECOVERY_FAILED = "FAILED"
        const val RECOVERY_AMBIGUOUS = "AMBIGUOUS"
        const val RECOVERY_NO_BOT_EVIDENCE = "COMPLETE_NO_BOT_EVIDENCE"
        const val RECOVERY_BASELINE_UNAVAILABLE = "BASELINE_UNAVAILABLE"
        const val RECOVERY_UNAVAILABLE = "UNAVAILABLE"
    }
}
