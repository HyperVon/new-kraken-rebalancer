package com.gemini.krakenbot.service.impl.history

import com.fasterxml.jackson.databind.ObjectMapper
import com.gemini.krakenbot.model.HistoryStats
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.repository.PortfolioStatsRepository
import com.gemini.krakenbot.repository.TradeRepository
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.service.PortfolioAnalyzer
import com.gemini.krakenbot.service.TradeHistoryService
import kotlinx.coroutines.flow.Flow
import java.time.Instant

class TradeHistoryServiceImpl(
    private val snapshotStore: TradeHistorySnapshotStore,
    private val queryService: TradeHistoryQueryService,
    private val syncService: TradeHistorySyncService,
) : TradeHistoryService {
    constructor(
        repository: TradeRepository,
        portfolioStatsRepository: PortfolioStatsRepository,
        krakenService: KrakenService,
        configService: ConfigService,
        objectMapper: ObjectMapper,
        portfolioAnalyzer: PortfolioAnalyzer,
        tradeHistoryFilePath: String = "trade-history.json",
    ) : this(
        snapshotStore =
        TradeHistorySnapshotStore(
            repository = repository,
            configService = configService,
            objectMapper = objectMapper,
            tradeHistoryFilePath = tradeHistoryFilePath,
        ),
        queryService =
        TradeHistoryQueryService(
            repository = repository,
            portfolioStatsRepository = portfolioStatsRepository,
        ),
        syncService =
        TradeHistorySyncService(
            repository = repository,
            krakenService = krakenService,
            configService = configService,
            reconstructionService =
            TradeHistoryReconstructionService(
                repository = repository,
                krakenService = krakenService,
                configService = configService,
                portfolioAnalyzer = portfolioAnalyzer,
            ),
        ),
    )

    override suspend fun init() = snapshotStore.init()

    override suspend fun addSnapshot(snapshot: PortfolioSnapshot) = snapshotStore.addSnapshot(snapshot)

    override suspend fun getHistory(): List<PortfolioSnapshot> = queryService.getHistory()

    override suspend fun getLatestSnapshot(): PortfolioSnapshot? = queryService.getLatestSnapshot()

    override fun getHistoryFlow(): Flow<PortfolioSnapshot> = snapshotStore.getHistoryFlow()

    override suspend fun saveTrade(trade: TradeRecord): Int = snapshotStore.saveTrade(trade)

    override suspend fun updateTrade(oldTrade: TradeRecord, newTrade: TradeRecord) =
        snapshotStore.updateTrade(oldTrade, newTrade)

    override suspend fun hasPendingSubmissions(): Boolean = snapshotStore.hasPendingSubmissions()

    override suspend fun getSnapshotsInRange(from: Instant, to: Instant): List<PortfolioSnapshot> =
        queryService.getSnapshotsInRange(from, to)

    override suspend fun getTradesInRange(from: Instant, to: Instant): List<TradeRecord> =
        queryService.getTradesInRange(from, to)

    override suspend fun getHistoryStats(): HistoryStats = queryService.getHistoryStats()

    override suspend fun getHistoryStats(from: Instant, to: Instant): HistoryStats =
        queryService.getHistoryStats(from, to)

    override suspend fun syncTradesFromKraken() = syncService.syncTradesFromKraken()

    override suspend fun getSyncMetadata(key: String): String? = syncService.getSyncMetadata(key)

    override suspend fun setSyncMetadata(key: String, value: String) = syncService.setSyncMetadata(key, value)

    override suspend fun isHistorySeeded(): Boolean = syncService.isHistorySeeded()
}
