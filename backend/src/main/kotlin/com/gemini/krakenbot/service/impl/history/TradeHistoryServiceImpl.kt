package com.gemini.krakenbot.service.impl.history

import com.fasterxml.jackson.databind.ObjectMapper
import com.gemini.krakenbot.model.HistoryStats
import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.RebalancerComparison
import com.gemini.krakenbot.model.RewardsOverTime
import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.repository.LedgerRepository
import com.gemini.krakenbot.repository.OrderIntentRepository
import com.gemini.krakenbot.repository.PortfolioStatsRepository
import com.gemini.krakenbot.repository.TradeRepository
import com.gemini.krakenbot.service.ComparisonStartProposal
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.InceptionDisplayInfo
import com.gemini.krakenbot.service.InceptionRecoveryStatus
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.service.TradeHistoryService
import kotlinx.coroutines.flow.Flow
import java.time.Instant

class TradeHistoryServiceImpl(
    private val snapshotStore: TradeHistorySnapshotStore,
    private val queryService: TradeHistoryQueryService,
    private val syncService: TradeHistorySyncService,
    private val ledgersSyncService: LedgersSyncService,
    private val inceptionRecoveryService: InceptionRecoveryService? = null,
) : TradeHistoryService {
    constructor(
        repository: TradeRepository,
        portfolioStatsRepository: PortfolioStatsRepository,
        ledgerRepository: LedgerRepository,
        krakenService: KrakenService,
        configService: ConfigService,
        objectMapper: ObjectMapper,
        tradeHistoryFilePath: String = "trade-history.json",
        syncNowProvider: () -> Instant = Instant::now,
        orderIntentRepository: OrderIntentRepository? = null,
        inceptionRecoveryService: InceptionRecoveryService? = null,
        accountHistoryScopeGuard: AccountHistoryScopeGuard? = null,
    ) : this(
        snapshotStore =
        TradeHistorySnapshotStore(
            repository = repository,
            krakenService = krakenService,
            configService = configService,
            objectMapper = objectMapper,
            portfolioStatsRepository = portfolioStatsRepository,
            tradeHistoryFilePath = tradeHistoryFilePath,
            nowProvider = syncNowProvider,
        ),
        queryService =
        TradeHistoryQueryService(
            repository = repository,
            portfolioStatsRepository = portfolioStatsRepository,
            ledgerRepository = ledgerRepository,
            orderIntentRepository = orderIntentRepository,
            nowProvider = syncNowProvider,
        ),
        syncService =
        TradeHistorySyncService(
            repository = repository,
            krakenService = krakenService,
            configService = configService,
            nowProvider = syncNowProvider,
            accountHistoryScopeGuard = accountHistoryScopeGuard,
            ledgerRepository = ledgerRepository,
            reconstructionService =
            TradeHistoryReconstructionService(
                repository = repository,
                ledgerRepository = ledgerRepository,
                krakenService = krakenService,
                configService = configService,
                portfolioStatsRepository = portfolioStatsRepository,
                nowProvider = syncNowProvider,
                accountHistoryScopeGuard = accountHistoryScopeGuard,
            ),
        ),
        ledgersSyncService =
        LedgersSyncService(
            repository = ledgerRepository,
            krakenService = krakenService,
            configService = configService,
            tradeRepository = repository,
            nowProvider = syncNowProvider,
            accountHistoryScopeGuard = accountHistoryScopeGuard,
        ),
        inceptionRecoveryService = inceptionRecoveryService,
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

    override suspend fun getLedgersInRange(from: Instant, to: Instant): List<LedgerEvent> =
        queryService.getLedgersInRange(from, to)

    override suspend fun getRewardsOverTime(from: Instant, to: Instant): RewardsOverTime =
        queryService.getRewardsOverTime(from, to)

    override suspend fun getHistoryStats(): HistoryStats = queryService.getHistoryStats()

    override suspend fun getHistoryStats(from: Instant, to: Instant): HistoryStats =
        queryService.getHistoryStats(from, to)

    override suspend fun syncTradesFromKraken() = syncService.syncTradesFromKraken()

    override suspend fun syncLedgersFromKraken() = ledgersSyncService.syncLedgersFromKraken()

    override suspend fun rebuildHistoricalSnapshotsIfNeeded() = syncService.rebuildHistoricalSnapshotsIfNeeded()

    override suspend fun getSyncMetadata(key: String): String? = syncService.getSyncMetadata(key)

    override suspend fun setSyncMetadata(key: String, value: String) = syncService.setSyncMetadata(key, value)

    override suspend fun isHistorySeeded(): Boolean = syncService.isHistorySeeded()

    override suspend fun getInceptionRecoveryStatus(): InceptionRecoveryStatus =
        inceptionRecoveryService?.getStatus() ?: InceptionRecoveryStatus()

    override suspend fun getDetectedInceptionDisplayInfo(): InceptionDisplayInfo =
        inceptionRecoveryService?.getLocalInceptionDisplayInfo() ?: InceptionDisplayInfo()

    override suspend fun findVerifiedLaterComparisonStart(after: Instant): Instant? =
        queryService.findVerifiedLaterComparisonStart(after)

    override suspend fun getComparisonStartProposal(after: Instant): ComparisonStartProposal? =
        queryService.getComparisonStartProposal(after)

    override suspend fun getRebalancerComparison(from: Instant, to: Instant): RebalancerComparison =
        queryService.getRebalancerComparison(from, to)
}
