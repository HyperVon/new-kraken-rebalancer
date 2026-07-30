@file:OptIn(ExperimentalCoroutinesApi::class)

package com.gemini.krakenbot.service

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.service.impl.history.TradeHistoryServiceImpl
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import java.io.File
import java.math.BigDecimal
import java.time.Instant

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
                val tmpFile = File.createTempFile("lifecycle-migrate-", ".json").apply { deleteOnExit() }
                val file = tmpFile
                val bakFile = File("${tmpFile.absolutePath}.bak")
                try {
                    bakFile.delete()

                    val snapshot = TestFixtures.emptySnapshot(Instant.now(), BigDecimal("15000.00"))

                    file.writeText(objectMapper.writeValueAsString(listOf(snapshot)))

                    val tradeHistoryService = createService(tradeHistoryFilePath = tmpFile.absolutePath)
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
                val tmpFile = File.createTempFile("lifecycle-migrate-fail-", ".json").apply { deleteOnExit() }
                val file = tmpFile
                val bakFile = File("${tmpFile.absolutePath}.bak")
                try {
                    bakFile.delete()

                    val snapshot = TestFixtures.emptySnapshot(Instant.now(), BigDecimal("15000.00"))

                    file.writeText(objectMapper.writeValueAsString(listOf(snapshot)))

                    val tradeHistoryService = createService(tradeHistoryFilePath = tmpFile.absolutePath)
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

                val snapshot = TestFixtures.emptySnapshot(Instant.now(), BigDecimal.ZERO)

                tradeHistoryService.addSnapshot(snapshot)
                coVerify(exactly = 1) { repository.saveSnapshot(snapshot) }
            }
        }

        "addSnapshot_SuccessfullyPrunes" {
            runTest {
                val tradeHistoryService = createService()
                coEvery { repository.pruneSnapshotsOlderThan(any()) } returns 5

                val snapshot = TestFixtures.emptySnapshot(Instant.now(), BigDecimal.ZERO)

                tradeHistoryService.addSnapshot(snapshot)
                coVerify(exactly = 1) { repository.saveSnapshot(snapshot) }
                coVerify(exactly = 1) { repository.pruneSnapshotsOlderThan(any()) }
                coVerify(exactly = 1) { repository.pruneTradesOlderThan(any()) }
            }
        }
    }
}
