package com.gemini.krakenbot.domain

import java.math.BigDecimal

sealed interface RebalanceEvent {
    data class DeviationTriggered(val symbol: String, val deviationPercent: BigDecimal) : RebalanceEvent

    data object FiatCorrectionEnforced : RebalanceEvent

    data class FiatCorrectionDistributed(val usdAmount: BigDecimal, val candidateCount: Int) : RebalanceEvent

    data object NoCounterBalancingAssets : RebalanceEvent

    /**
     * An overweight leg was intentionally not sold because the asset sits at a recent high,
     * the regime where mean-reversion trades historically lose to trend continuation.
     */
    data class TrendSuppressedSell(val symbol: String) : RebalanceEvent
}

data class RebalancePlan(
    val buyOrders: Map<String, BigDecimal>,
    val sellOrders: Map<String, BigDecimal>,
    val events: List<RebalanceEvent>,
)
