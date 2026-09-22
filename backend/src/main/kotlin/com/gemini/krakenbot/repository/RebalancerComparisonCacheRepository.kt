package com.gemini.krakenbot.repository

import com.gemini.krakenbot.model.RebalancerComparison

/**
 * Exact external OHLC evidence consumed by a successful comparison calculation.
 *
 * The consumed evidence domain is the request window `[sinceEpochSecond, upToEpochSecond]`:
 * [candleContentHash] covers only completed candles that start at or after [sinceEpochSecond]
 * and close at or before [upToEpochSecond] — the same no-lookahead bound the historical price
 * resolver applies. Candles closing after [upToEpochSecond] (normal future market growth) are
 * not consumed evidence and must never invalidate this dependency.
 *
 * [freshnessDeadlineEpochSecond] marks the wall-clock instant at which this external evidence
 * must be revalidated against the exchange before a cached comparison can be trusted again.
 */
data class ConsumedOhlcDependency(
    val pair: String,
    val intervalMinutes: Int,
    val sinceEpochSecond: Long,
    val upToEpochSecond: Long,
    val fetchedAtEpochSecond: Long,
    val freshnessDeadlineEpochSecond: Long,
    val candleContentHash: String,
) {
    fun isFresh(nowEpochSecond: Long): Boolean = nowEpochSecond < freshnessDeadlineEpochSecond
}

/** A previously calculated, evidence-versioned comparison result. */
data class RebalancerComparisonCacheEntry(
    val inputFingerprint: String,
    val comparison: RebalancerComparison,
    val ohlcDependencies: List<ConsumedOhlcDependency> = emptyList(),
)

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
        ohlcDependencies: List<ConsumedOhlcDependency> = emptyList(),
    )

    /** Refreshes freshness deadlines for consumed dependencies without re-serializing the result payload. */
    suspend fun updateOhlcDependencies(
        fromEpochMillis: Long,
        toEpochMillis: Long,
        ohlcDependencies: List<ConsumedOhlcDependency>,
    )

    /**
     * Atomically refreshes dependencies only when the stored list still equals [expectedOhlcDependencies].
     * Returns false (writing nothing) when the entry is missing or was replaced. The background OHLC
     * refresh validates outside the evidence lock, so it must not overwrite a concurrently replayed
     * entry with the older list it validated; synchronous writers run under the lock and use the
     * unconditional [updateOhlcDependencies].
     */
    suspend fun updateOhlcDependenciesIfExpected(
        fromEpochMillis: Long,
        toEpochMillis: Long,
        expectedOhlcDependencies: List<ConsumedOhlcDependency>,
        ohlcDependencies: List<ConsumedOhlcDependency>,
    ): Boolean

    /** Deletes an invalidated cache entry. */
    suspend fun delete(fromEpochMillis: Long, toEpochMillis: Long)
}
