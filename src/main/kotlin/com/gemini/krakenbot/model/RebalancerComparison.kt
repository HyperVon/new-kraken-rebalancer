package com.gemini.krakenbot.model

import java.math.BigDecimal
import java.time.Instant

enum class ComparisonAvailability {
    AVAILABLE,
    UNAVAILABLE,
}

enum class ComparisonConfidence {
    RECONCILED,
}

enum class ComparisonUnavailableReason {
    INSUFFICIENT_SNAPSHOTS,
    NON_POSITIVE_BASELINE,
    BASELINE_MISMATCH,
    MISSING_PRICE,
    ASSET_UNIVERSE_CHANGED,
    UNSUPPORTED_TRADE,
    UNEXPLAINED_BALANCE_CHANGE,
}

data class RebalancerComparisonPoint(
    val timestamp: Instant,
    val rebalancerValueUSD: BigDecimal,
    val buyAndHoldValueUSD: BigDecimal,
    val differenceUSD: BigDecimal,
    val differencePercent: BigDecimal,
)

data class RebalancerComparison(
    val availability: ComparisonAvailability,
    val confidence: ComparisonConfidence?,
    val baselineTimestamp: Instant?,
    val points: List<RebalancerComparisonPoint>,
    val latestDifferenceUSD: BigDecimal?,
    val latestDifferencePercent: BigDecimal?,
    val unavailableReason: ComparisonUnavailableReason?,
    val unavailableAt: Instant?,
) {
    init {
        when (availability) {
            ComparisonAvailability.AVAILABLE -> {
                require(confidence != null) { "Available comparison must have confidence" }
                require(points.size >= 2) { "Available comparison must have at least 2 points" }
                require(baselineTimestamp != null) { "Available comparison must have baselineTimestamp" }
                require(latestDifferenceUSD != null) { "Available comparison must have latestDifferenceUSD" }
                require(latestDifferencePercent != null) { "Available comparison must have latestDifferencePercent" }
                require(unavailableReason == null) { "Available comparison must not have unavailableReason" }
                require(unavailableAt == null) { "Available comparison must not have unavailableAt" }
                val first = points.first()
                require(first.differenceUSD.compareTo(BigDecimal.ZERO) == 0) {
                    "First point must have zero difference"
                }
                require(first.differencePercent.compareTo(BigDecimal.ZERO) == 0) {
                    "First point must have zero percentage difference"
                }
                require(first.rebalancerValueUSD.compareTo(first.buyAndHoldValueUSD) == 0) {
                    "First point must have equal values"
                }
                require(first.timestamp == baselineTimestamp) { "Baseline timestamp must match the first point" }
                for (i in 1 until points.size) {
                    require(points[i].timestamp >= points[i - 1].timestamp) {
                        "Points must be in ascending timestamp order"
                    }
                }
            }
            ComparisonAvailability.UNAVAILABLE -> {
                require(confidence == null) { "Unavailable comparison must not have confidence" }
                require(points.isEmpty()) { "Unavailable comparison must have no points" }
                require(latestDifferenceUSD == null) { "Unavailable comparison must not have latestDifferenceUSD" }
                require(latestDifferencePercent == null) {
                    "Unavailable comparison must not have latestDifferencePercent"
                }
                require(unavailableReason != null) { "Unavailable comparison must have unavailableReason" }
            }
        }
    }
}
