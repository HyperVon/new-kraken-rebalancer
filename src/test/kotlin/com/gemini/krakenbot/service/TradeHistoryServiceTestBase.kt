@file:OptIn(ExperimentalCoroutinesApi::class)

package com.gemini.krakenbot.service

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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.math.BigDecimal
import java.time.Instant

abstract class TradeHistoryServiceTestBase : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    protected val objectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())
    protected val repository = mockk<TradeRepository>(relaxed = true)
    protected val statsRepository = mockk<PortfolioStatsRepository>(relaxed = true)
    protected val ledgerRepository = mockk<LedgerRepository>(relaxed = true)
    protected val krakenService = mockk<KrakenService>(relaxed = true).also { stubWithStableBackend(it) }
    protected val configService = mockk<ConfigService>(relaxed = true)
    protected val portfolioAnalyzer = mockk<PortfolioAnalyzer>(relaxed = true)

    protected fun stubWithStableBackend(service: KrakenService) {
        coEvery { service.withStableBackend(any<suspend (KrakenService) -> Any?>()) } coAnswers {
            val block = firstArg<suspend (KrakenService) -> Any?>()
            block(service)
        }
    }

    protected fun createService(
        tradeHistoryFilePath: String = TestFixtures.TEST_TRADE_HISTORY_JSON,
        syncNowProvider: () -> Instant = Instant::now,
    ): TradeHistoryServiceImpl {
        val appConfig = AppConfig(
            kraken = KrakenCredentials(TestFixtures.TRADE_HISTORY_API_KEY, TestFixtures.TRADE_HISTORY_API_SECRET),
            settings = TestFixtures.settings(
                dryRun = false,
                loopDelaySeconds = 60,
                deviationTriggerPercent = 5.0,
                dustThresholdUSD = 5.0,
                fiatMaxDrawdown = 30.0,
            ),
            allocations = emptyList(),
        )
        every { configService.getConfig() } returns appConfig

        val savedSnapshots = mutableListOf<PortfolioSnapshot>()
        coEvery { repository.saveSnapshot(any()) } answers {
            savedSnapshots.add(0, firstArg())
        }
        coEvery { repository.load() } answers { savedSnapshots.take(50) }

        return TradeHistoryServiceImpl(
            repository,
            statsRepository,
            ledgerRepository,
            krakenService,
            configService,
            objectMapper,
            portfolioAnalyzer,
            tradeHistoryFilePath,
            syncNowProvider,
        )
    }

    protected fun snapshotWorth(totalValueUSD: BigDecimal) =
        TestFixtures.emptySnapshot(timestamp = Instant.now(), totalValueUSD = totalValueUSD)
}
