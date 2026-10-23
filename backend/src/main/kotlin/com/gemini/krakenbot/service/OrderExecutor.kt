package com.gemini.krakenbot.service

import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.domain.AssetPrices
import com.gemini.krakenbot.domain.AssetValues
import com.gemini.krakenbot.domain.RawBalances
import com.gemini.krakenbot.domain.RebalanceOrders

/**
 * Structured execution result; callers never parse formatted action text to infer whether
 * a submission occurred. [liveSubmissionAttempted] is true only when a real-money AddOrder
 * reached the live exchange path in this cycle. [blockedByUnresolvedIntents] distinguishes a
 * deliberately blocked live cycle from a normal no-trade cycle.
 */
data class OrderExecutionOutcome(
    val liveSubmissionAttempted: Boolean = false,
    val exchangeOrderAttempted: Boolean = false,
    val blockedByUnresolvedIntents: Boolean = false,
)

/**
 * Records structured submission attempts for the cycle outcome and post-order sync signal. The
 * caller owns the tracker so attempt evidence survives an exception thrown after the submission.
 */
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

interface OrderExecutor {
    suspend fun executeOrders(
        buyOrders: RebalanceOrders,
        sellOrders: RebalanceOrders,
        currentValuesUSD: AssetValues,
        prices: AssetPrices,
        settings: Settings,
        actionLog: MutableList<String>,
        cycleId: String = "",
        availableBalances: RawBalances? = null,
        attempts: OrderAttemptTracker = OrderAttemptTracker(),
    ): OrderExecutionOutcome
}
