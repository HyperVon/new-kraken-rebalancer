package com.gemini.krakenbot.repository

import com.gemini.krakenbot.model.RebalancerComparison

/** A previously calculated, evidence-versioned comparison result. */
data class RebalancerComparisonCacheEntry(val inputFingerprint: String, val comparison: RebalancerComparison)

/**
 * Durable memoization for the expensive B&amp;H versus Rebalancer calculation.
 *
 * The cache is an optimization only: callers must supply a fingerprint that includes every
 * evidence/configuration revision relevant to the calculation and must treat a miss as normal.
 */
interface RebalancerComparisonCacheRepository {
    /** [fromEpochMillis] and [toEpochMillis] identify the effective reconciled source series. */
    suspend fun load(fromEpochMillis: Long, toEpochMillis: Long): RebalancerComparisonCacheEntry?

    suspend fun save(
        fromEpochMillis: Long,
        toEpochMillis: Long,
        inputFingerprint: String,
        comparison: RebalancerComparison,
    )
}
