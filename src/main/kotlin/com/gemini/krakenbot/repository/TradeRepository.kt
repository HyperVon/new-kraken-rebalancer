package com.gemini.krakenbot.repository

import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.TradeRecord
import java.math.BigDecimal
import java.time.Instant

data class TradeSummaryStats(
    val totalTradesExecuted: Long,
    val totalVolumeTraded: BigDecimal,
    val totalFeesPaid: BigDecimal,
    val latestSnapshotTime: Instant?,
    val periodHigh: BigDecimal? = null,
    val avgFeeRatePercent: BigDecimal = BigDecimal.ZERO,
    val avgSlippagePercent: BigDecimal? = null,
    val failedTradeCount: Long = 0L,
    val dryRunTradeCount: Long = 0L,
)

interface TradeRepository {
    suspend fun save(history: List<PortfolioSnapshot>)

    suspend fun load(): List<PortfolioSnapshot>

    suspend fun getTradeSummaryStats(): TradeSummaryStats

    suspend fun getTradeSummaryStats(from: Instant, to: Instant): TradeSummaryStats

    // History page query methods
    suspend fun saveSnapshot(snapshot: PortfolioSnapshot)

    suspend fun saveTrade(trade: TradeRecord)

    suspend fun updateTrade(oldTrade: TradeRecord, newTrade: TradeRecord)

    suspend fun getSnapshotsInRange(from: Instant, to: Instant): List<PortfolioSnapshot>

    suspend fun getTradesInRange(from: Instant, to: Instant): List<TradeRecord>

    suspend fun getLatestTradeTime(): Instant?

    suspend fun isHistorySeeded(): Boolean

    suspend fun setHistorySeeded(seeded: Boolean)

    suspend fun getSyncMetadata(key: String): String?

    suspend fun setSyncMetadata(key: String, value: String)

    suspend fun pruneSnapshotsOlderThan(cutoff: Instant): Int

    suspend fun cleanupDuplicateTrades()
}
