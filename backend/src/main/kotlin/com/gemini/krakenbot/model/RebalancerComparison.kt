package com.gemini.krakenbot.model

import com.gemini.krakenbot.codegen.GenerateApiMapper
import java.math.BigDecimal
import java.time.Instant
import com.gemini.krakenbot.api.RebalancerComparison as ApiRebalancerComparison
import com.gemini.krakenbot.api.RebalancerComparisonPoint as ApiRebalancerComparisonPoint

@GenerateApiMapper(ApiRebalancerComparisonPoint::class)
data class RebalancerComparisonPoint(
    val timestamp: Instant,
    val rebalancerValueUSD: BigDecimal,
    val buyAndHoldValueUSD: BigDecimal,
    val differenceUSD: BigDecimal,
    val differencePercent: BigDecimal,
)

/**
 * Which synthetic benchmark the comparison ran against.
 *
 * Both methods measure the same Actual portfolio; they differ only in what the strategy's major
 * asset-selection decisions are assumed to have been worth.
 */
enum class BenchmarkMethod {
    /** Freezes the approved inception portfolio and holds it forever. Forensic reference. */
    FIXED_INCEPTION_HOLD,

    /**
     * Follows the same major, persistent allocation changes the strategy made, applying one synthetic
     * portfolio transition per inferred regime change and otherwise holding. This is the primary
     * comparison because it isolates the value of routine rebalancing from asset selection.
     */
    INFERRED_CONFIGURATION_MATCHED_HOLD,
}

/**
 * Provenance of the allocation history a benchmark method relies on.
 *
 * Deliberately separate from [ComparisonConfidence]: a comparison can be fully reconciled
 * arithmetically while its configuration history is only inferred.
 */
enum class ConfigurationEvidence {
    /** No configuration history is used beyond the approved inception holdings. */
    NOT_APPLICABLE,

    /**
     * Allocation changes were inferred from persistent trading and balance behavior because exact
     * historical configuration changes were never retained. Not proven history.
     */
    INFERRED,
}

@GenerateApiMapper(ApiRebalancerComparison::class)
data class RebalancerComparison(
    val availability: ComparisonAvailability,
    val confidence: ComparisonConfidence?,
    val baselineTimestamp: Instant?,
    val points: List<RebalancerComparisonPoint>,
    val latestDifferenceUSD: BigDecimal?,
    val latestDifferencePercent: BigDecimal?,
    val unavailableReason: ComparisonUnavailableReason?,
    val unavailableAt: Instant?,
    /** Verified later comparison start proposed while the comparison is unavailable, or null. */
    val proposedBaselineTimestamp: Instant? = null,
    /** Durable state of the bounded later-start search, or null when no search was requested. */
    val proposalSearchStatus: ComparisonProposalStatus? = null,
    /** Which synthetic benchmark produced [points]. */
    val benchmarkMethod: BenchmarkMethod = BenchmarkMethod.FIXED_INCEPTION_HOLD,
    /**
     * How the allocation history behind [benchmarkMethod] was established. Kept separate from
     * [confidence] so a reconciled comparison is never read as proven configuration history.
     */
    val configurationEvidence: ConfigurationEvidence = ConfigurationEvidence.NOT_APPLICABLE,
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
                require(proposedBaselineTimestamp == null) {
                    "Available comparison must not have proposedBaselineTimestamp"
                }
                require(proposalSearchStatus == null) {
                    "Available comparison must not have proposalSearchStatus"
                }
                val first = points.first()
                require(baselineTimestamp <= first.timestamp) {
                    "Baseline timestamp must not be after the first point"
                }
                // A strict configured-target benchmark can intentionally start below the full
                // actual wallet when the approved inception snapshot retains historical-only
                // holdings. The initial difference is therefore evidence, not an invalid state.
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
                if (proposalSearchStatus == ComparisonProposalStatus.VERIFIED) {
                    require(proposedBaselineTimestamp != null) {
                        "Verified proposal status must have proposedBaselineTimestamp"
                    }
                }
                if (proposedBaselineTimestamp != null) {
                    require(proposalSearchStatus == ComparisonProposalStatus.VERIFIED) {
                        "proposedBaselineTimestamp requires verified proposal status"
                    }
                }
                if (proposedBaselineTimestamp != null) {
                    require(baselineTimestamp == null || proposedBaselineTimestamp >= baselineTimestamp) {
                        "Proposed baseline timestamp must not precede the known inception time"
                    }
                }
            }
        }
    }
}
