package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.model.HistoryStats
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.repository.PortfolioStatsRepository
import com.gemini.krakenbot.repository.TradeRepository
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.service.TradeHistoryService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

class TradeHistoryServiceImpl(
    private val repository: TradeRepository,
    private val portfolioStatsRepository: PortfolioStatsRepository,
    private val krakenService: KrakenService,
    private val configService: ConfigService
) : TradeHistoryService {

    private val log = LoggerFactory.getLogger(TradeHistoryServiceImpl::class.java)
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

    override suspend fun syncTradesFromKraken() {
        if (repository.isHistorySeeded()) {
            log.info("Historical trades already seeded in database. Skipping API historical fetch.")
            return
        }

        val apiKey = configService.getConfig().kraken.apiKey.value
        if (apiKey.isBlank() || apiKey == "YOUR_KRAKEN_API_KEY") {
            log.warn("Kraken API key is blank or placeholder. Skipping trade history synchronization.")
            return
        }

        log.info("Starting historical trades synchronization from Kraken API...")

        val latestTradeTime = repository.getLatestTradeTime()
        val startSec = latestTradeTime?.epochSecond

        // Load existing boundary signatures to prevent duplicates
        val existingSignatures = if (latestTradeTime != null) {
            repository.getTradesInRange(latestTradeTime, Instant.now())
                .map { "${it.timestamp.toEpochMilli()}_${it.pair}_${it.side.uppercase()}_${it.volume}_${it.usdAmount}" }
                .toSet()
        } else {
            emptySet()
        }

        var offset = 0

        while (true) {
            log.info("Fetching trade history batch with offset={}", offset)
            val apiTrades = krakenService.getTradeHistory(startSec = startSec, offset = offset)
            if (apiTrades.isEmpty()) {
                break
            }

            var addedInBatch = 0
            for (trade: TradeRecord in apiTrades) {
                val signature = "${trade.timestamp.toEpochMilli()}_${trade.pair}_${trade.side.uppercase()}_${trade.volume}_${trade.usdAmount}"
                if (!existingSignatures.contains(signature)) {
                    repository.saveTrade(trade)
                    addedInBatch++
                }
            }

            log.info("Processed batch: added {} new trades out of {} fetched", addedInBatch, apiTrades.size)

            if (apiTrades.size < 50) {
                break
            }
            offset += 50
        }

        repository.setHistorySeeded(true)
        log.info("Historical trades synchronization completed. Database marked as seeded.")
    }
}
