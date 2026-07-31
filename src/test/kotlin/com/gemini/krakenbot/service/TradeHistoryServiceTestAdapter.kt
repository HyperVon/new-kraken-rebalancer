package com.gemini.krakenbot.service

import com.gemini.krakenbot.model.HistoryStats
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.RebalancerComparison
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.repository.TradeRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.time.Instant

/**
 * Test-only [TradeHistoryService] that forwards the durable live-order journal surface
 * ([saveTrade] / [updateTrade] / [hasPendingSubmissions]) and [getTradesInRange] straight to a
 * real [TradeRepository]. Everything else is stubbed — used to drive [OrderExecutorImpl]
 * against a real SQLite repository in E2E submission-journal tests without wiring the full
 * [com.gemini.krakenbot.service.impl.history.TradeHistoryServiceImpl] façade
 * (which needs [com.gemini.krakenbot.service.KrakenService] / portfolioAnalyzer / stats repo).
 */
class TradeHistoryServiceTestAdapter(private val repository: TradeRepository) : TradeHistoryService {
    override suspend fun init() = Unit
    override suspend fun addSnapshot(snapshot: PortfolioSnapshot) = Unit
    override suspend fun getHistory(): List<PortfolioSnapshot> = emptyList()
    override suspend fun getLatestSnapshot(): PortfolioSnapshot? = null
    override fun getHistoryFlow(): Flow<PortfolioSnapshot> = emptyFlow()
    override suspend fun saveTrade(trade: TradeRecord): Int = repository.saveTrade(trade)
    override suspend fun updateTrade(oldTrade: TradeRecord, newTrade: TradeRecord) =
        repository.updateTrade(oldTrade, newTrade)
    override suspend fun hasPendingSubmissions(): Boolean = repository.hasPendingSubmissions()
    override suspend fun getSnapshotsInRange(from: Instant, to: Instant): List<PortfolioSnapshot> =
        repository.getSnapshotsInRange(from, to)
    override suspend fun getTradesInRange(from: Instant, to: Instant): List<TradeRecord> =
        repository.getTradesInRange(from, to)
    override suspend fun getHistoryStats(): HistoryStats = throw NotImplementedError()
    override suspend fun getHistoryStats(from: Instant, to: Instant): HistoryStats = throw NotImplementedError()
    override suspend fun syncTradesFromKraken(): Unit = throw NotImplementedError()
    override suspend fun getSyncMetadata(key: String): String? = null
    override suspend fun setSyncMetadata(key: String, value: String) = Unit
    override suspend fun isHistorySeeded(): Boolean = false
    override suspend fun getRebalancerComparison(from: Instant, to: Instant): RebalancerComparison =
        throw NotImplementedError()
}
