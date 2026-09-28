package com.gemini.krakenbot.api

import com.gemini.krakenbot.model.BenchmarkMethod
import com.gemini.krakenbot.model.ConfigurationEvidence

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
     *
     * Defaults to the primary method — the same one the endpoint defaults to — so a response
     * missing the field cannot be read as the forensic reference.
     */
    val benchmarkMethod: String = BenchmarkMethod.INFERRED_CONFIGURATION_MATCHED_HOLD.name,
    /**
     * Provenance of the allocation history behind [benchmarkMethod]. `INFERRED` means allocation
     * changes were inferred from persistent behavior, not retained as proven configuration history.
     * Kept separate from [confidence] so a reconciled comparison is never read as proven history.
     *
     * Defaults to `INFERRED` to stay consistent with the default [benchmarkMethod], so a response
     * that lost both fields cannot claim the reference uses no configuration history at all.
     */
    val configurationEvidence: String = ConfigurationEvidence.INFERRED.name,
)

data class RebalancerComparisonPoint(
    val timestamp: String,
    val rebalancerValueUSD: String,
    val buyAndHoldValueUSD: String,
    val differenceUSD: String,
    val differencePercent: String,
)
