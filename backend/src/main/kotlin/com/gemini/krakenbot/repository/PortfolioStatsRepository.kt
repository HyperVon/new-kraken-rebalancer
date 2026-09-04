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
     * Atomically (single SQLite transaction) persists ATH stats and journals the
     * decided flow identities (applied, net-zero, and consciously-skipped
     * rows), then advances the ATH flow watermark. Journal rows are a
     * lifetime decision log: reconciliation of late-arriving history is
     * identity-driven, so entries are never pruned by watermark (a pruned
     * entry for a still-retained ledger row would re-apply the flow and
     * double-scale ATH). A crash before commit retries safely; after commit
     * nothing is double-applied.
     */
    suspend fun saveAthStateWithFlowCheckpoint(
        stats: PortfolioStats,
        appliedFlows: List<AppliedAthFlow>,
        flowWatermarkSec: Long?,
    )

    /**
     * One-time upgrade migration helper: inserts the given identities into the
     * decision journal without touching stats or the watermark. Used to record
     * pre-migration rows below the legacy watermark as presumed-decided so
     * legacy-applied flows are never re-applied by the identity-driven scan.
     */
    suspend fun journalPresumedDecidedFlows(flows: List<AppliedAthFlow>)

    /** Flow identities already decided (subset of [ledgerIds] that were recorded). */
    suspend fun getAppliedAthFlowIds(ledgerIds: List<String>): Set<String>
}
