package com.gemini.krakenbot.service

import com.gemini.krakenbot.model.HistoryStats
import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.RebalancerComparison
import com.gemini.krakenbot.model.RewardsOverTime
import com.gemini.krakenbot.model.TradeRecord
import kotlinx.coroutines.flow.Flow
import java.time.Instant

interface TradeHistoryService {
    suspend fun init()

    suspend fun addSnapshot(snapshot: PortfolioSnapshot)

    suspend fun getHistory(): List<PortfolioSnapshot>

    suspend fun getLatestSnapshot(): PortfolioSnapshot?

    fun getHistoryFlow(): Flow<PortfolioSnapshot>

    suspend fun saveTrade(trade: TradeRecord): Int
    suspend fun updateTrade(oldTrade: TradeRecord, newTrade: TradeRecord)
    suspend fun hasPendingSubmissions(): Boolean

    suspend fun getSnapshotsInRange(from: Instant, to: Instant): List<PortfolioSnapshot>

    suspend fun getTradesInRange(from: Instant, to: Instant): List<TradeRecord>

    suspend fun getLedgersInRange(from: Instant, to: Instant): List<LedgerEvent>

    suspend fun getRewardsOverTime(from: Instant, to: Instant): RewardsOverTime

    suspend fun getHistoryStats(): HistoryStats

    suspend fun getHistoryStats(from: Instant, to: Instant): HistoryStats

    suspend fun syncTradesFromKraken()

    suspend fun syncLedgersFromKraken()

    /** Rebuilds legacy snapshot history when its reconstruction version is stale. */
    suspend fun rebuildHistoricalSnapshotsIfNeeded() = Unit

    suspend fun getSyncMetadata(key: String): String?

    suspend fun setSyncMetadata(key: String, value: String)

    suspend fun isHistorySeeded(): Boolean

    /** Current durable strategy-inception recovery state for the History status surface. */
    suspend fun getInceptionRecoveryStatus(): InceptionRecoveryStatus = InceptionRecoveryStatus()

    /**
     * Display-only auto-detected inception for the Settings page.
     * Local metadata reads only; never triggers detection or writes.
     */
    suspend fun getDetectedInceptionDisplayInfo(): InceptionDisplayInfo = InceptionDisplayInfo()

    /**
     * Earliest retained snapshot at/after [after] whose complete reconciliation
     * yields an available Buy &amp; Hold comparison, or null when no verified
     * later start exists. The scan does not change settings or financial rows; it may persist
     * bounded search progress in sync metadata.
     */
    suspend fun findVerifiedLaterComparisonStart(after: Instant): Instant? = null

    /**
     * Returns the shared later-start proposal state when the complete retained comparison is
     * unavailable for a reason that a later verified anchor can fix. The result is null when no
     * proposal policy applies; a non-null result distinguishes a verified, incomplete, or
     * exhausted bounded search.
     */
    suspend fun getComparisonStartProposal(after: Instant): ComparisonStartProposal? = null

    suspend fun getRebalancerComparison(from: Instant, to: Instant): RebalancerComparison
}
