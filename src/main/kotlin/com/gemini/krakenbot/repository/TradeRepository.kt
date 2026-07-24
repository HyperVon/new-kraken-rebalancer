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
)

interface TradeRepository {
    fun save(history: List<PortfolioSnapshot>)

    fun load(): List<PortfolioSnapshot>

    fun getTradeSummaryStats(): TradeSummaryStats

    fun getTradeSummaryStats(from: Instant, to: Instant): TradeSummaryStats

    // History page query methods
    fun saveSnapshot(snapshot: PortfolioSnapshot)

    fun saveTrade(trade: TradeRecord)

    fun updateTrade(oldTrade: TradeRecord, newTrade: TradeRecord)

    fun getSnapshotsInRange(from: Instant, to: Instant): List<PortfolioSnapshot>

    fun getTradesInRange(from: Instant, to: Instant): List<TradeRecord>

    fun getLatestTradeTime(): Instant?

    fun isHistorySeeded(): Boolean

    fun setHistorySeeded(seeded: Boolean)

    fun getSyncMetadata(key: String): String?

    fun setSyncMetadata(key: String, value: String)

    fun pruneSnapshotsOlderThan(cutoff: Instant): Int

    fun cleanupDuplicateTrades()
}
