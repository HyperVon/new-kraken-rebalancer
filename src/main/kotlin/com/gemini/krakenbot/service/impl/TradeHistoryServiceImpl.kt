package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.model.HistoryStats
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.repository.PortfolioStatsRepository
import com.gemini.krakenbot.repository.TradeRepository
import com.gemini.krakenbot.service.TradeHistoryService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

class TradeHistoryServiceImpl(
    private val repository: TradeRepository,
    private val portfolioStatsRepository: PortfolioStatsRepository
) : TradeHistoryService {

    private val history = CopyOnWriteArrayList<PortfolioSnapshot>()
    private val maxHistorySize = 50
    private val snapshotFlow =
        MutableSharedFlow<PortfolioSnapshot>(extraBufferCapacity = 16)

    override fun init() {
        val loaded = repository.load()
        if (loaded.isNotEmpty()) {
            history.addAll(loaded)
        }
    }

    override fun addSnapshot(snapshot: PortfolioSnapshot) {
        history.add(0, snapshot)
        if (history.size > maxHistorySize) {
            history.removeLast()
        }
        repository.saveSnapshot(snapshot)
        snapshotFlow.tryEmit(snapshot)
    }

    override fun getHistory(): List<PortfolioSnapshot> = ArrayList(history)

    override fun getLatestSnapshot(): PortfolioSnapshot? = history.firstOrNull()

    override fun getHistoryFlow(): Flow<PortfolioSnapshot> =
        snapshotFlow.asSharedFlow()

    override fun saveTrade(trade: TradeRecord) {
        repository.saveTrade(trade)
    }

    override fun getSnapshotsInRange(
        from: Instant,
        to: Instant
    ): List<PortfolioSnapshot> {
        return repository.getSnapshotsInRange(from, to)
    }

    override fun getTradesInRange(
        from: Instant,
        to: Instant
    ): List<TradeRecord> {
        return repository.getTradesInRange(from, to)
    }

    override fun getHistoryStats(): HistoryStats {
        val stats = portfolioStatsRepository.load()
        return HistoryStats(
            allTimeHigh = stats.allTimeHigh ?: BigDecimal.ZERO,
            totalTradesExecuted = repository.getTotalTradeCount(),
            totalVolumeTraded = repository.getTotalVolumeTraded(),
            firstSnapshotTime = repository.getFirstSnapshotTime(),
            latestSnapshotTime = repository.getLatestSnapshotTime()
        )
    }
}
