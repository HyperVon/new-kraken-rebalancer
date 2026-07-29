@file:OptIn(ExperimentalCoroutinesApi::class)

package com.gemini.krakenbot.service

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.OrderSide
import com.gemini.krakenbot.model.OrderSubmissionState
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.PortfolioStats
import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.model.TradeSource
import com.gemini.krakenbot.repository.PortfolioStatsRepository
import com.gemini.krakenbot.repository.TradeRepository
import com.gemini.krakenbot.repository.TradeSummaryStats
import com.gemini.krakenbot.service.impl.DynamicKrakenService
import com.gemini.krakenbot.service.impl.KrakenServiceImpl
import com.gemini.krakenbot.service.impl.SimulatedKrakenService
import com.gemini.krakenbot.service.impl.history.TradeHistoryReconstructionService
import com.gemini.krakenbot.service.impl.history.TradeHistoryServiceImpl
import com.gemini.krakenbot.util.TradeCalculator
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import java.io.File
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.time.Duration.Companion.milliseconds

class TradeHistorySyncLifecycleTest : TradeHistoryServiceTestBase() {

    init {
        "init_InSimulationMode_SeedsHistoricalSnapshots" {
            runTest {
                val appConfig = AppConfig(
                    kraken =
                    KrakenCredentials(
                        TestFixtures.TRADE_HISTORY_API_KEY,
                        TestFixtures.TRADE_HISTORY_API_SECRET,
                    ),
                    settings = TestFixtures.settings(
                        dryRun = false,
                        simulation = true,
                        loopDelaySeconds = 60,
                        deviationTriggerPercent = 5.0,
                        dustThresholdUSD = 5.0,
                        fiatMaxDrawdown = 30.0,
                    ),
                    allocations = listOf(
                        Allocation(Asset("UNKNOWN"), 50.0),
                        Allocation(Asset(TestFixtures.USD), 50.0),
                    ),
                )
                every { configService.getConfig() } returns appConfig
                coEvery { repository.load() } returns emptyList()

                val tradeHistoryService = TradeHistoryServiceImpl(
                    repository,
                    statsRepository,
                    krakenService,
                    configService,
                    objectMapper,
                    portfolioAnalyzer,
                    TestFixtures.TEST_TRADE_HISTORY_JSON,
                )
                tradeHistoryService.init()

                // Simulation seed: ~15 days of 6h snapshots written as one batch.
                coVerify(exactly = 1) { repository.save(match { it.isNotEmpty() }) }
            }
        }

        "init_ThrowsExceptionDuringSeeding_HandledGracefully" {
            runTest {
                val appConfig = AppConfig(
                    kraken =
                    KrakenCredentials(
                        TestFixtures.TRADE_HISTORY_API_KEY,
                        TestFixtures.TRADE_HISTORY_API_SECRET,
                    ),
                    settings = TestFixtures.settings(
                        dryRun = false,
                        simulation = true,
                        loopDelaySeconds = 60,
                        deviationTriggerPercent = 5.0,
                        dustThresholdUSD = 5.0,
                        fiatMaxDrawdown = 30.0,
                    ),
                    allocations = listOf(
                        Allocation(Asset(Asset.BTC), 50.0),
                        Allocation(Asset(TestFixtures.USD), 50.0),
                    ),
                )
                every { configService.getConfig() } returns appConfig
                coEvery { repository.load() } returns emptyList()
                coEvery { repository.save(any()) } throws RuntimeException("Seeding failed")

                val tradeHistoryService = TradeHistoryServiceImpl(
                    repository,
                    statsRepository,
                    krakenService,
                    configService,
                    objectMapper,
                    portfolioAnalyzer,
                    TestFixtures.TEST_TRADE_HISTORY_JSON,
                )

                tradeHistoryService.init()
            }
        }

        "init_MigratesTradeHistoryJsonIfEmpty" {
            runTest {
                val file = File(TestFixtures.TEST_TRADE_HISTORY_JSON)
                val bakFile = File("test-trade-history.json.bak")
                try {
                    file.delete()
                    bakFile.delete()

                    val snapshot = PortfolioSnapshot(
                        timestamp = Instant.now(),
                        totalValueUSD = BigDecimal("15000.00"),
                        assets = emptyMap(),
                        actions = emptyList(),
                        drawdownPercent = BigDecimal.ZERO,
                        fiatDeploymentPercent = BigDecimal.ZERO,
                        effectiveUsdTargetPercent = BigDecimal.ZERO,
                    )

                    file.writeText(objectMapper.writeValueAsString(listOf(snapshot)))

                    val tradeHistoryService = createService()
                    coEvery { repository.load() } returns emptyList()

                    tradeHistoryService.init()

                    coVerify(exactly = 1) { repository.save(any()) }

                    file.exists() shouldBe false
                    bakFile.exists() shouldBe true
                } finally {
                    file.delete()
                    bakFile.delete()
                }
            }
        }

        "init_MigrationSaveFailureLeavesJsonUnrenamed" {
            runTest {
                val file = File(TestFixtures.TEST_TRADE_HISTORY_JSON)
                val bakFile = File("test-trade-history.json.bak")
                try {
                    file.delete()
                    bakFile.delete()

                    val snapshot = PortfolioSnapshot(
                        timestamp = Instant.now(),
                        totalValueUSD = BigDecimal("15000.00"),
                        assets = emptyMap(),
                        actions = emptyList(),
                        drawdownPercent = BigDecimal.ZERO,
                        fiatDeploymentPercent = BigDecimal.ZERO,
                        effectiveUsdTargetPercent = BigDecimal.ZERO,
                    )

                    file.writeText(objectMapper.writeValueAsString(listOf(snapshot)))

                    val tradeHistoryService = createService()
                    coEvery { repository.load() } returns emptyList()
                    coEvery { repository.save(any()) } throws RuntimeException("migrate save failed")

                    tradeHistoryService.init()

                    coVerify(exactly = 1) { repository.save(any()) }
                    file.exists() shouldBe true
                    bakFile.exists() shouldBe false
                } finally {
                    file.delete()
                    bakFile.delete()
                }
            }
        }

        "addSnapshot_HandlesPruneException" {
            runTest {
                val tradeHistoryService = createService()
                coEvery { repository.pruneSnapshotsOlderThan(any()) } throws RuntimeException("Prune failed")

                val snapshot = PortfolioSnapshot(
                    timestamp = Instant.now(),
                    totalValueUSD = BigDecimal.ZERO,
                    assets = emptyMap(),
                    actions = emptyList(),
                    drawdownPercent = BigDecimal.ZERO,
                    fiatDeploymentPercent = BigDecimal.ZERO,
                    effectiveUsdTargetPercent = BigDecimal.ZERO,
                )

                tradeHistoryService.addSnapshot(snapshot)
                coVerify(exactly = 1) { repository.saveSnapshot(snapshot) }
            }
        }

        "addSnapshot_SuccessfullyPrunes" {
            runTest {
                val tradeHistoryService = createService()
                coEvery { repository.pruneSnapshotsOlderThan(any()) } returns 5

                val snapshot = PortfolioSnapshot(
                    timestamp = Instant.now(),
                    totalValueUSD = BigDecimal.ZERO,
                    assets = emptyMap(),
                    actions = emptyList(),
                    drawdownPercent = BigDecimal.ZERO,
                    fiatDeploymentPercent = BigDecimal.ZERO,
                    effectiveUsdTargetPercent = BigDecimal.ZERO,
                )

                tradeHistoryService.addSnapshot(snapshot)
                coVerify(exactly = 1) { repository.saveSnapshot(snapshot) }
                coVerify(exactly = 1) { repository.pruneSnapshotsOlderThan(any()) }
                coVerify(exactly = 1) { repository.pruneTradesOlderThan(any()) }
            }
        }
    }
}
