package com.gemini.krakenbot.api

data class RebalancerComparison(
    val availability: String,
    val confidence: String?,
    val baselineTimestamp: String?,
    val points: List<RebalancerComparisonPoint>,
    val latestDifferenceUSD: String?,
    val latestDifferencePercent: String?,
    val unavailableReason: String?,
    val unavailableAt: String?,
)

data class RebalancerComparisonPoint(
    val timestamp: String,
    val rebalancerValueUSD: String,
    val buyAndHoldValueUSD: String,
    val differenceUSD: String,
    val differencePercent: String,
)
