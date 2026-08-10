package com.gemini.krakenbot.domain

import java.math.BigDecimal

sealed interface RebalanceEvent {
    data class DeviationTriggered(val symbol: String, val deviationPercent: BigDecimal) : RebalanceEvent

    data object FiatCorrectionEnforced : RebalanceEvent

    data class FiatCorrectionDistributed(val usdAmount: BigDecimal, val candidateCount: Int) : RebalanceEvent

    data object NoCounterBalancingAssets : RebalanceEvent
}

data class RebalancePlan(
    val buyOrders: Map<String, BigDecimal>,
    val sellOrders: Map<String, BigDecimal>,
    val events: List<RebalanceEvent>,
)
