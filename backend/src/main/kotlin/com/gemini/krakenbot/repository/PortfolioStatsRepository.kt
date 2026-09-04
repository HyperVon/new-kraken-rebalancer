package com.gemini.krakenbot.repository

import com.gemini.krakenbot.model.PortfolioStats

/**
 * A durably applied owner-capital flow identity for ATH crash idempotency.
 */
data class AppliedAthFlow(val ledgerId: String, val eventTimeSec: Long)

interface PortfolioStatsRepository {
    suspend fun load(): PortfolioStats

    suspend fun save(stats: PortfolioStats)

    /**
     * Atomically (single SQLite transaction) persists ATH stats, journals the
     * applied flow identities, advances the ATH flow watermark, and prunes
     * journal rows at or below the watermark. A crash before commit retries
     * safely; after commit nothing is double-applied.
     */
    suspend fun saveAthStateWithFlowCheckpoint(
        stats: PortfolioStats,
        appliedFlows: List<AppliedAthFlow>,
        flowWatermarkSec: Long?,
    )

    /** Flow identities already applied (subset of [ledgerIds] that were recorded). */
    suspend fun getAppliedAthFlowIds(ledgerIds: List<String>): Set<String>
}
