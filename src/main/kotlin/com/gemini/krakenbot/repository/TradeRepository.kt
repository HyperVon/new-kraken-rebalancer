package com.gemini.krakenbot.repository

import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.TradeRecord
import java.math.BigDecimal
import java.time.Instant

interface TradeRepository {
    fun save(history: List<PortfolioSnapshot>)
    fun load(): List<PortfolioSnapshot>

    // History page query methods
    fun saveSnapshot(snapshot: PortfolioSnapshot)
    fun saveTrade(trade: TradeRecord)
    fun updateTrade(oldTrade: TradeRecord, newTrade: TradeRecord)
    fun getSnapshotsInRange(from: Instant, to: Instant): List<PortfolioSnapshot>
    fun getTradesInRange(from: Instant, to: Instant): List<TradeRecord>
    fun getTotalTradeCount(): Long
    fun getTotalVolumeTraded(): BigDecimal
    fun getFirstSnapshotTime(): Instant?
    fun getLatestSnapshotTime(): Instant?
    fun getLatestTradeTime(): Instant?
    fun isHistorySeeded(): Boolean
    fun setHistorySeeded(seeded: Boolean)
    fun pruneSnapshotsOlderThan(cutoff: Instant): Int
}
