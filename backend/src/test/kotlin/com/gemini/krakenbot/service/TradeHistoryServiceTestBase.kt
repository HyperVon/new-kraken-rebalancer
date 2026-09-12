package com.gemini.krakenbot.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.repository.LedgerRepository
import com.gemini.krakenbot.repository.PortfolioStatsRepository
import com.gemini.krakenbot.repository.TradeRepository
import com.gemini.krakenbot.service.impl.history.TradeHistoryServiceImpl
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.mockk.*
import java.math.BigDecimal
import java.time.Instant

abstract class TradeHistoryServiceTestBase : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    protected val objectMapper: ObjectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())
    protected val repository = mockk<TradeRepository>(relaxed = true).also {
        coEvery { it.isHistorySeeded() } returns true
        coEvery { it.getSnapshotBefore(any()) } returns null
        coEvery {
            it.getSyncMetadata(com.gemini.krakenbot.model.SyncMetadataKeys.TRADE_COVERAGE_VERSION)
        } returns com.gemini.krakenbot.service.impl.history.TradeHistorySyncService.CURRENT_TRADE_COVERAGE_VERSION
        coEvery {
            it.getSyncMetadata(com.gemini.krakenbot.model.SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC)
        } answers { Instant.now().epochSecond.toString() }
    }
    protected val statsRepository = mockk<PortfolioStatsRepository>(relaxed = true)
    protected val ledgerRepository = mockk<LedgerRepository>(relaxed = true).also {
        coEvery { it.isLedgersSeeded() } returns true
        coEvery {
            it.getSyncMetadata(com.gemini.krakenbot.model.SyncMetadataKeys.LEDGER_COVERAGE_VERSION)
        } returns com.gemini.krakenbot.service.impl.history.LedgersSyncService.CURRENT_LEDGER_COVERAGE_VERSION
        coEvery {
            it.getSyncMetadata(com.gemini.krakenbot.model.SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC)
        } answers { Instant.now().epochSecond.toString() }
    }
    protected val krakenService = mockk<KrakenService>(relaxed = true).also { stubWithStableBackend(it) }
    protected val configService = mockk<ConfigService>(relaxed = true)
    protected val portfolioAnalyzer = mockk<PortfolioAnalyzer>(relaxed = true)

    protected fun stubWithStableBackend(service: KrakenService) {
        coEvery { service.withStableBackend(any<suspend (KrakenService) -> Any?>()) } coAnswers {
            val block = firstArg<suspend (KrakenService) -> Any?>()
            block(service)
        }
        every { service.hasLastLedgerPageShape() } returns true
        every { service.hasLastTradeHistoryPageShape() } returns true
        every { service.hasLastTradeHistoryTotalCount() } returns false
        every { service.hasLastLedgerTotalCount() } returns false
    }

    protected fun createService(
        tradeHistoryFilePath: String = TestFixtures.TEST_TRADE_HISTORY_JSON,
        inceptionDate: String? = null,
        syncNowProvider: () -> Instant = Instant::now,
    ): TradeHistoryServiceImpl {
        val appConfig = AppConfig(
            kraken = KrakenCredentials(TestFixtures.TRADE_HISTORY_API_KEY, TestFixtures.TRADE_HISTORY_API_SECRET),
            settings = TestFixtures.settings(
                dryRun = false,
                loopDelaySeconds = 60,
                deviationTriggerPercent = 5.0,
                minimumOrderSizeUSD = 5.0,
                fiatMaxDrawdown = 30.0,
            ).copy(inceptionDate = inceptionDate),
            allocations = emptyList(),
        )
        every { configService.getConfig() } returns appConfig

        val savedSnapshots = mutableListOf<PortfolioSnapshot>()
        coEvery { repository.saveSnapshot(any()) } answers {
            savedSnapshots.add(0, firstArg())
            savedSnapshots.size
        }
        coEvery { repository.load() } answers { savedSnapshots.take(50) }
        coEvery { repository.getLatestSnapshot() } coAnswers { repository.load().firstOrNull() }
        coEvery {
            repository.getSyncMetadata(com.gemini.krakenbot.model.SyncMetadataKeys.TRADE_COVERAGE_VERSION)
        } returns com.gemini.krakenbot.service.impl.history.TradeHistorySyncService.CURRENT_TRADE_COVERAGE_VERSION
        coEvery {
            repository.getSyncMetadata(com.gemini.krakenbot.model.SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC)
        } answers { (syncNowProvider().epochSecond + 60).toString() }
        coEvery { ledgerRepository.isLedgersSeeded() } returns true
        coEvery {
            ledgerRepository.getSyncMetadata(com.gemini.krakenbot.model.SyncMetadataKeys.LEDGER_COVERAGE_VERSION)
        } returns com.gemini.krakenbot.service.impl.history.LedgersSyncService.CURRENT_LEDGER_COVERAGE_VERSION
        coEvery {
            ledgerRepository.getSyncMetadata(
                com.gemini.krakenbot.model.SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC,
            )
        } answers { (syncNowProvider().epochSecond + 60).toString() }

        return TradeHistoryServiceImpl(
            repository,
            statsRepository,
            ledgerRepository,
            krakenService,
            configService,
            objectMapper,
            tradeHistoryFilePath,
            syncNowProvider,
        )
    }

    protected fun snapshotWorth(totalValueUSD: BigDecimal) =
        TestFixtures.emptySnapshot(timestamp = Instant.now(), totalValueUSD = totalValueUSD)
}
