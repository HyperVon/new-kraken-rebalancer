package com.gemini.krakenbot

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.DatabaseConfig
import com.gemini.krakenbot.config.InvalidConfigurationException
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.PortfolioStats
import com.gemini.krakenbot.repository.PortfolioStatsRepository
import com.gemini.krakenbot.repository.impl.SqlitePortfolioStatsRepositoryImpl
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.FakeKrakenService
import com.gemini.krakenbot.service.TradeHistoryService
import com.gemini.krakenbot.service.impl.ConfigServiceImpl
import com.gemini.krakenbot.service.impl.OrderExecutorImpl
import com.gemini.krakenbot.service.impl.PortfolioAnalyzerImpl
import com.gemini.krakenbot.service.impl.PortfolioManagerImpl
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.time.Duration.Companion.milliseconds

internal fun EvaluationScenariosTest.registerScenarios8To14() {
    "Scenario 8: Concurrent Multi-Asset Rebalance with Slippage" {
        runTest {
            val fakeKraken = FakeKrakenService()
            val mockConfig = mockk<ConfigService>(relaxed = true)
            val appConfig =
                TestFixtures.config(
                    settings =
                    TestFixtures.settings(dryRun = false, loopDelaySeconds = 60L),
                    allocations =
                    listOf(
                        Allocation(Asset.BTC, 30.0),
                        Allocation(Asset.ETH, 60.0),
                        Allocation(Asset.USD, 10.0),
                    ),
                )
            every { mockConfig.getConfig() } returns appConfig

            fakeKraken.pricesSupplier = { _ ->
                mapOf(
                    TestFixtures.XBTUSD to 50000.0,
                    TestFixtures.ETHUSD to 2000.0,
                )
            }

            fakeKraken.balanceSupplier = {
                mapOf(
                    Asset.BTC to 0.5,
                    "ETH" to 0.0,
                    Asset.USD to 1000.0,
                )
            }

            val orderExecutionLog = mutableListOf<String>()
            // Slippage injection: the post-sell balance poll reports $8,000 instead of the $18,200
            // the sell projected, so the ETH buy is capped at 99% of the cash actually observed
            // (0.99 * $8,000 / $2,000 = 3.96) rather than at the $15,600 ETH target.
            fakeKraken.executeOrderAction = { pair, _, side, volume ->
                orderExecutionLog.add("$side $pair volume=$volume")
                if (side == TestFixtures.SELL) {
                    fakeKraken.balanceSupplier = {
                        mapOf(
                            Asset.BTC to 0.156,
                            "ETH" to 0.0,
                            Asset.USD to 8000.0,
                        )
                    }
                }
            }

            val statsRepo = mockk<PortfolioStatsRepository>(relaxed = true)
            val analyzer =
                PortfolioAnalyzerImpl(
                    fakeKraken,
                    mockConfig,
                    statsRepo,
                )
            val executor =
                OrderExecutorImpl(fakeKraken, tradeHistoryService)
            val pm =
                PortfolioManagerImpl(
                    mockConfig,
                    mockk(relaxed = true),
                    analyzer,
                    executor,
                )

            fakeKraken.executedOrders.clear()
            pm.performRebalanceCycle()

            val btcSell = fakeKraken.executedOrders.firstOrNull {
                it.pair == TestFixtures.XBTUSD &&
                    it.side == TestFixtures.SELL
            }
            val ethBuy = fakeKraken.executedOrders.firstOrNull {
                it.pair == TestFixtures.ETHUSD &&
                    it.side == TestFixtures.BUY
            }

            val sellPass = btcSell != null && btcSell.volume.compareTo(BigDecimal("0.344")) == 0
            val buyPass = ethBuy != null && ethBuy.volume.compareTo(BigDecimal("3.96")) == 0

            val success = sellPass && buyPass
            val evidence =
                "Sell BTC: $btcSell\n" +
                    "Buy ETH: $ethBuy (Expected volume: 3.96 ETH)\n" +
                    "Execution log: $orderExecutionLog"

            success.shouldBeTrue()
            EvaluationScenariosTest.recordResult(
                "Scenario 8",
                "Concurrent Multi-Asset Rebalance with Slippage",
                TestFixtures.PASS,
                evidence,
            )
        }
    }

    "Scenario 9: Run Loop Lifecycle & Timing" {
        runTest {
            val fakeKraken = FakeKrakenService()
            val mockConfig = mockk<ConfigService>(relaxed = true)
            val appConfig =
                TestFixtures.config(
                    settings =
                    TestFixtures.settings(loopDelaySeconds = 1L),
                )
            every { mockConfig.getConfig() } returns appConfig
            every { mockConfig.watchConfigChanges() } answers {
                flowOf(mockConfig.getConfig().settings)
            }
            fakeKraken.balanceSupplier = { emptyMap() }

            val statsRepo = mockk<PortfolioStatsRepository>(relaxed = true)
            val analyzer =
                PortfolioAnalyzerImpl(
                    fakeKraken,
                    mockConfig,
                    statsRepo,
                )
            val executor =
                OrderExecutorImpl(fakeKraken, tradeHistoryService)
            val pm =
                PortfolioManagerImpl(
                    mockConfig,
                    mockk(relaxed = true),
                    analyzer,
                    executor,
                )

            pm.startRebalancingLoop()
            val job =
                launch {
                    pm.runLoop()
                }

            // Virtual time: this returns immediately but advances the clock past two 1s loop
            // delays, so the balance call count is what proves the loop actually iterated.
            delay(2500.milliseconds)

            pm.stopRebalancingLoop()
            job.join()

            val callCount = fakeKraken.getBalancesCallCount
            val loopPass = callCount >= 2

            val evidence =
                "Loop started successfully.\n" +
                    "Executed cycles count: $callCount (expected >= 2)\n" +
                    "Loop stopped cleanly when stopRebalancingLoop() was called."

            loopPass.shouldBeTrue()
            EvaluationScenariosTest.recordResult(
                "Scenario 9",
                "Run Loop Lifecycle & Timing",
                TestFixtures.PASS,
                evidence,
            )
        }
    }

    "Scenario 10: Portfolio Stats Database Failure Resilience" {
        runTest {
            val db = DatabaseConfig.init(TestFixtures.MEMORY_)
            val statsRepo =
                SqlitePortfolioStatsRepositoryImpl(
                    db,
                    objectMapper,
                    evaluationTempPath("10").absolutePath,
                )
            val stats = PortfolioStats(BigDecimal("1234.56"))

            // save() writes SQLite, not the legacy JSON path. Drop the actual stats table to exercise
            // the repository's database-write failure mapping.
            transaction(db) {
                exec(TestFixtures.DROP_TABLE_IF_EXISTS_PORTFOLIO_STATS)
            }

            val failure = shouldThrow<IOException> {
                statsRepo.save(stats)
            }
            val writeFailPass = failure.message == "Database write failed"

            val evidence =
                "Portfolio stats table was removed from the in-memory database.\n" +
                    "Stats save failed with the database-write IOException: $writeFailPass\n" +
                    "Failure message: ${failure.message}"

            writeFailPass.shouldBeTrue()
            EvaluationScenariosTest.recordResult(
                "Scenario 10",
                "Portfolio Stats Database Failure Resilience",
                TestFixtures.PASS,
                evidence,
            )
        }
    }

    "Scenario 11: Configuration Validation Edge Cases" {
        runTest {
            val mapper = jacksonObjectMapper()
            val tempFile = File.createTempFile("scenario11-", ".json").apply { deleteOnExit() }

            val validSettings =
                TestFixtures.settings(loopDelaySeconds = 10)
            val validConfig =
                AppConfig(
                    KrakenCredentials(
                        "k",
                        "s",
                    ),
                    validSettings,
                    listOf(Allocation(Asset.USD, 100.0)),
                )
            mapper.writeValue(tempFile, validConfig)

            val configService = ConfigServiceImpl(mapper, tempFile.absolutePath)

            val badLoop = validConfig.copy(settings = validSettings.copy(loopDelaySeconds = 0))
            val e1 = shouldThrow<InvalidConfigurationException> { configService.updateConfig(badLoop) }

            val badDev = validConfig.copy(settings = validSettings.copy(deviationTriggerPercent = -1.0))
            val e2 = shouldThrow<InvalidConfigurationException> { configService.updateConfig(badDev) }

            val badDrawdown = validConfig.copy(settings = validSettings.copy(fiatMaxDrawdown = 150.0))
            val e3 = shouldThrow<InvalidConfigurationException> { configService.updateConfig(badDrawdown) }

            val badTotal = validConfig.copy(allocations = listOf(Allocation(Asset.USD, 90.0)))
            val e4 = shouldThrow<InvalidConfigurationException> { configService.updateConfig(badTotal) }

            val noUsd = validConfig.copy(allocations = listOf(Allocation(Asset.BTC, 100.0)))
            val e5 = shouldThrow<InvalidConfigurationException> { configService.updateConfig(noUsd) }

            val evidence =
                "Invalid loop delay exception: ${e1.message}\n" +
                    "Invalid deviation exception: ${e2.message}\n" +
                    "Invalid drawdown exception: ${e3.message}\n" +
                    "Invalid total percent exception: ${e4.message}\n" +
                    "Missing USD exception: ${e5.message}"

            EvaluationScenariosTest.recordResult(
                "Scenario 11",
                "Configuration Validation Edge Cases",
                TestFixtures.PASS,
                evidence,
            )
        }
    }

    "Scenario 12: Precision and Rounding Tolerances" {
        runTest {
            val fakeKraken = FakeKrakenService()
            val mockConfig = mockk<ConfigService>(relaxed = true)
            val appConfig =
                TestFixtures.config(
                    settings =
                    TestFixtures.settings(loopDelaySeconds = 10, minimumOrderSizeUSD = 0.0001),
                    allocations =
                    listOf(
                        Allocation(
                            Asset.USD,
                            50.0,
                        ),
                        Allocation(Asset.BTC, 50.0),
                    ),
                )
            every { mockConfig.getConfig() } returns appConfig

            fakeKraken.balanceSupplier = {
                mapOf(
                    Asset.USD to 1.00000001,
                    Asset.BTC to 0.00000001,
                )
            }
            fakeKraken.pricesSupplier = {
                mapOf(TestFixtures.XBTUSD to 48523.97)
            }

            val statsRepo = mockk<PortfolioStatsRepository>(relaxed = true)
            val analyzer =
                PortfolioAnalyzerImpl(
                    fakeKraken,
                    mockConfig,
                    statsRepo,
                )
            val mockHistory = mockk<TradeHistoryService>(relaxed = true)
            val capturedSnapshots = mutableListOf<PortfolioSnapshot>()
            coEvery { mockHistory.addSnapshot(any()) } answers {
                capturedSnapshots.add(firstArg())
            }
            val executor = OrderExecutorImpl(fakeKraken, mockHistory)

            val pm =
                PortfolioManagerImpl(
                    mockConfig,
                    mockHistory,
                    analyzer,
                    executor,
                )
            pm.performRebalanceCycle()

            fakeKraken.executedOrders.size shouldBe 1
            val order = fakeKraken.executedOrders.single()
            order.pair shouldBe TestFixtures.XBTUSD
            order.type shouldBe TestFixtures.MARKET
            order.side shouldBe TestFixtures.BUY
            order.volume.shouldBeEqualComparingTo(BigDecimal("0.00001030"))
            order.dryRun shouldBe true

            capturedSnapshots.size shouldBe 1
            val snapshot = capturedSnapshots.single()
            snapshot.totalValueUSD.shouldBeEqualComparingTo(BigDecimal("1.00"))
            snapshot.assets.getValue(Asset.USD).valueUSD.shouldBeEqualComparingTo(BigDecimal("1.00"))
            snapshot.assets.getValue(Asset.BTC).valueUSD.shouldBeEqualComparingTo(BigDecimal("0.00"))
            snapshot.assets.getValue(Asset.BTC).balance.shouldBeEqualComparingTo(BigDecimal("0.00000001"))
            snapshot.assets.getValue(Asset.BTC).price.shouldBeEqualComparingTo(BigDecimal("48523.97000000"))

            val evidence =
                "Precise inputs: USD=1.00000001, BTC=0.00000001 @ $48523.97\n" +
                    "Portfolio total rounded once to $${snapshot.totalValueUSD}; " +
                    "BTC snapshot value rounded to $${snapshot.assets.getValue(Asset.BTC).valueUSD}\n" +
                    "Dry-run BTC buy volume: ${order.volume} (8-decimal order precision)"

            EvaluationScenariosTest.recordResult(
                "Scenario 12",
                "Precision and Rounding Tolerances",
                TestFixtures.PASS,
                evidence,
            )
        }
    }

    "Scenario 13: High Volatility Slippage Capping" {
        runTest {
            val fakeKraken = FakeKrakenService()
            val mockConfig = mockk<ConfigService>(relaxed = true)
            val appConfig =
                TestFixtures.config(
                    settings =
                    TestFixtures.settings(loopDelaySeconds = 60L, minimumOrderSizeUSD = 10.0, dryRun = false),
                    allocations =
                    listOf(
                        Allocation(Asset.BTC, 40.0),
                        Allocation(Asset.ETH, 40.0),
                        Allocation(Asset.USD, 20.0),
                    ),
                )
            every { mockConfig.getConfig() } returns appConfig

            var balanceUSD = BigDecimal("100.0")
            var balanceBTC = BigDecimal("0.02")
            var balanceETH = BigDecimal("0.0")

            fakeKraken.balanceSupplier = {
                mapOf(
                    Asset.BTC to balanceBTC.toDouble(),
                    Asset.ETH to balanceETH.toDouble(),
                    Asset.USD to balanceUSD.toDouble(),
                )
            }
            fakeKraken.pricesSupplier = {
                mapOf(
                    TestFixtures.XBTUSD to 50000.0,
                    TestFixtures.ETHUSD to 2000.0,
                )
            }

            // Severe slippage: a BTC sell credits a flat $250 whatever the notional, so the ETH buy
            // must size against the $350 actually settled (99% of it), not the projected proceeds.
            fakeKraken.executeOrderAction = { pair, _, side, volume ->
                if (pair == TestFixtures.XBTUSD && side == TestFixtures.SELL) {
                    balanceBTC = balanceBTC.subtract(volume)
                    balanceUSD = balanceUSD.add(BigDecimal("250.0"))
                } else if (pair == TestFixtures.ETHUSD && side == TestFixtures.BUY) {
                    balanceETH = balanceETH.add(volume)
                    balanceUSD = balanceUSD.subtract(volume.multiply(BigDecimal("2000.0")))
                }
            }

            val statsRepo = mockk<PortfolioStatsRepository>(relaxed = true)
            val analyzer =
                PortfolioAnalyzerImpl(
                    fakeKraken,
                    mockConfig,
                    statsRepo,
                )
            val executor =
                OrderExecutorImpl(fakeKraken, tradeHistoryService)
            val pm =
                PortfolioManagerImpl(
                    mockConfig,
                    mockk(relaxed = true),
                    analyzer,
                    executor,
                )

            pm.performRebalanceCycle()

            val ethBuy = fakeKraken.executedOrders.firstOrNull {
                it.pair == TestFixtures.ETHUSD &&
                    it.side == TestFixtures.BUY
            }
            val expectedCost = BigDecimal("350.0").multiply(BigDecimal("0.99"))
            val expectedVolume = expectedCost.divide(BigDecimal("2000.0"), 8, RoundingMode.HALF_UP)

            val success = ethBuy != null && ethBuy.volume.compareTo(expectedVolume) == 0
            val evidence =
                "Sells executed: ${fakeKraken.executedOrders.filter { it.side == TestFixtures.SELL }}\n" +
                    "Buys executed: ${fakeKraken.executedOrders.filter { it.side == TestFixtures.BUY }}\n" +
                    "ETH buy volume expected: $expectedVolume, actual: ${ethBuy?.volume} (Success: $success)"

            success.shouldBeTrue()
            EvaluationScenariosTest.recordResult(
                "Scenario 13",
                "High Volatility Slippage Capping",
                TestFixtures.PASS,
                evidence,
            )
        }
    }

    "Scenario 14: Config File Hot-Reload via loadConfig()" {
        runTest {
            val mapper = jacksonObjectMapper()
            val tempFile = File.createTempFile("scenario14-", ".json").apply { deleteOnExit() }

            val settings1 =
                TestFixtures.settings(loopDelaySeconds = 60L)
            val config1 =
                AppConfig(
                    KrakenCredentials(
                        "key1",
                        "sec1",
                    ),
                    settings1,
                    listOf(Allocation(Asset.USD, 100.0)),
                )
            mapper.writeValue(tempFile, config1)

            val configService = ConfigServiceImpl(mapper, tempFile.absolutePath)
            configService.getConfig().settings.loopDelaySeconds shouldBe 60L

            val settings2 =
                TestFixtures.settings(
                    loopDelaySeconds = 120L,
                    deviationTriggerPercent = 5.0,
                    minimumOrderSizeUSD = 2.0,
                    dryRun = false,
                    fiatMaxDrawdown = 10.0,
                    fiatDeploymentExponent = 1.5,
                )
            val config2 =
                AppConfig(
                    KrakenCredentials("key2", "sec2"),
                    settings2,
                    listOf(Allocation(Asset.USD, 100.0)),
                )
            mapper.writeValue(tempFile, config2)

            configService.loadConfig()

            val updatedConfig = configService.getConfig()
            val reloaded =
                updatedConfig.settings.loopDelaySeconds == 120L &&
                    updatedConfig.settings.deviationTriggerPercent == 5.0

            val evidence =
                "Initial loop delay: 60s\n" +
                    "Modified config loop delay on disk: 120s\n" +
                    "Config service reloaded via loadConfig() (no filesystem watcher): $reloaded"

            reloaded.shouldBeTrue()
            EvaluationScenariosTest.recordResult(
                "Scenario 14",
                "Config File Hot-Reload via loadConfig()",
                TestFixtures.PASS,
                evidence,
            )
        }
    }
}
