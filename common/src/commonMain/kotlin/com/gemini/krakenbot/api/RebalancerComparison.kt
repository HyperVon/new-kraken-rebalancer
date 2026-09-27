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
    val proposedBaselineTimestamp: String? = null,
    val proposalSearchStatus: String? = null,
    /**
     * Which synthetic benchmark produced [points]. `FIXED_INCEPTION_HOLD` freezes the approved
     * inception portfolio forever; `INFERRED_CONFIGURATION_MATCHED_HOLD` follows the strategy's own
     * inferred major allocation changes and is the primary comparison.
     */
    val benchmarkMethod: String = "FIXED_INCEPTION_HOLD",
    /**
     * Provenance of the allocation history behind [benchmarkMethod]. `INFERRED` means allocation
     * changes were inferred from persistent behavior, not retained as proven configuration history.
     * Kept separate from [confidence] so a reconciled comparison is never read as proven history.
     */
    val configurationEvidence: String = "NOT_APPLICABLE",
)

data class RebalancerComparisonPoint(
    val timestamp: String,
    val rebalancerValueUSD: String,
    val buyAndHoldValueUSD: String,
    val differenceUSD: String,
    val differencePercent: String,
)
