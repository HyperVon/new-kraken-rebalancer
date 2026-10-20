package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.domain.AssetPrices
import com.gemini.krakenbot.service.KrakenService

/**
 * Encapsulates execution state and pinned dependencies for a single rebalance cycle.
 */
data class RebalanceSessionContext(
    val cycleId: String,
    val backend: KrakenService,
    val prices: AssetPrices,
    val settings: Settings,
    val actionLog: MutableList<String>,
    val attempts: OrderAttemptTracker = OrderAttemptTracker(),
)

/** Records structured submission attempts for the cycle outcome and post-order sync signal. */
class OrderAttemptTracker {
    @Volatile
    var liveSubmissionAttempted: Boolean = false

    @Volatile
    var exchangeOrderAttempted: Boolean = false

    fun record(isLiveSubmission: Boolean) {
        if (isLiveSubmission) liveSubmissionAttempted = true
        exchangeOrderAttempted = true
    }
}
