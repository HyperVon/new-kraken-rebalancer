package com.gemini.krakenbot.service

import com.gemini.krakenbot.model.HistoryStats
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.TradeRecord
import kotlinx.coroutines.flow.Flow
import java.time.Instant

interface TradeHistoryService {
    fun init()
    fun addSnapshot(snapshot: PortfolioSnapshot)
    fun getHistory(): List<PortfolioSnapshot>
    fun getLatestSnapshot(): PortfolioSnapshot?
    fun getHistoryFlow(): Flow<PortfolioSnapshot>

    // History page methods
    fun saveTrade(trade: TradeRecord)
    fun getSnapshotsInRange(from: Instant, to: Instant): List<PortfolioSnapshot>
    fun getTradesInRange(from: Instant, to: Instant): List<TradeRecord>
    fun getHistoryStats(): HistoryStats
}
