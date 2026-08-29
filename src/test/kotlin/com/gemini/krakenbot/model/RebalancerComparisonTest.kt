package com.gemini.krakenbot.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import java.math.BigDecimal
import java.time.Instant

@Suppress("unused")
class RebalancerComparisonTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        val baseline = Instant.parse("2026-07-01T00:00:00Z")
        val later = baseline.plusSeconds(60)
        val zero = BigDecimal("0.00")
        val value = BigDecimal("100.00")

        fun point(
            timestamp: Instant = baseline,
            rebalancer: BigDecimal = value,
            buyAndHold: BigDecimal = value,
            difference: BigDecimal = zero,
            differencePercent: BigDecimal = zero,
        ) = RebalancerComparisonPoint(
            timestamp = timestamp,
            rebalancerValueUSD = rebalancer,
            buyAndHoldValueUSD = buyAndHold,
            differenceUSD = difference,
            differencePercent = differencePercent,
        )

        fun available(
            confidence: ComparisonConfidence? = ComparisonConfidence.RECONCILED,
            baselineTimestamp: Instant? = baseline,
            points: List<RebalancerComparisonPoint> = listOf(point(), point(later)),
            latestDifferenceUSD: BigDecimal? = zero,
            latestDifferencePercent: BigDecimal? = zero,
            unavailableReason: ComparisonUnavailableReason? = null,
            unavailableAt: Instant? = null,
        ) = RebalancerComparison(
            availability = ComparisonAvailability.AVAILABLE,
            confidence = confidence,
            baselineTimestamp = baselineTimestamp,
            points = points,
            latestDifferenceUSD = latestDifferenceUSD,
            latestDifferencePercent = latestDifferencePercent,
            unavailableReason = unavailableReason,
            unavailableAt = unavailableAt,
        )

        fun unavailable(
            confidence: ComparisonConfidence? = null,
            points: List<RebalancerComparisonPoint> = emptyList(),
            latestDifferenceUSD: BigDecimal? = null,
            latestDifferencePercent: BigDecimal? = null,
            reason: ComparisonUnavailableReason? = ComparisonUnavailableReason.INSUFFICIENT_SNAPSHOTS,
        ) = RebalancerComparison(
            availability = ComparisonAvailability.UNAVAILABLE,
            confidence = confidence,
            baselineTimestamp = baseline,
            points = points,
            latestDifferenceUSD = latestDifferenceUSD,
            latestDifferencePercent = latestDifferencePercent,
            unavailableReason = reason,
            unavailableAt = baseline,
        )

        "available comparison rejects missing required metadata" {
            shouldThrow<IllegalArgumentException> { available(confidence = null) }
            shouldThrow<IllegalArgumentException> { available(baselineTimestamp = null) }
            shouldThrow<IllegalArgumentException> { available(latestDifferenceUSD = null) }
            shouldThrow<IllegalArgumentException> { available(latestDifferencePercent = null) }
        }

        "available comparison rejects unavailable-only metadata" {
            shouldThrow<IllegalArgumentException> {
                available(unavailableReason = ComparisonUnavailableReason.MISSING_PRICE)
            }
            shouldThrow<IllegalArgumentException> { available(unavailableAt = later) }
        }

        "available comparison rejects invalid point collections" {
            shouldThrow<IllegalArgumentException> { available(points = listOf(point())) }
            shouldThrow<IllegalArgumentException> {
                available(points = listOf(point(difference = BigDecimal.ONE), point(later)))
            }
            shouldThrow<IllegalArgumentException> {
                available(points = listOf(point(differencePercent = BigDecimal.ONE), point(later)))
            }
            shouldThrow<IllegalArgumentException> {
                available(points = listOf(point(rebalancer = BigDecimal("101.00")), point(later)))
            }
            shouldThrow<IllegalArgumentException> {
                available(baselineTimestamp = later)
            }
            shouldThrow<IllegalArgumentException> {
                available(points = listOf(point(later), point(baseline)))
            }
        }

        "unavailable comparison rejects available-only fields" {
            shouldThrow<IllegalArgumentException> { unavailable(confidence = ComparisonConfidence.RECONCILED) }
            shouldThrow<IllegalArgumentException> { unavailable(points = listOf(point())) }
            shouldThrow<IllegalArgumentException> { unavailable(latestDifferenceUSD = zero) }
            shouldThrow<IllegalArgumentException> { unavailable(latestDifferencePercent = zero) }
            shouldThrow<IllegalArgumentException> { unavailable(reason = null) }
        }
    }
}
