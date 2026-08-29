package com.gemini.krakenbot

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.domain.OrderResult
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.repository.PortfolioStatsRepository
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.FakeKrakenService
import com.gemini.krakenbot.service.TradeHistoryService
import com.gemini.krakenbot.service.impl.ConfigServiceImpl
import com.gemini.krakenbot.service.impl.OrderExecutorImpl
import com.gemini.krakenbot.service.impl.PortfolioAnalyzerImpl
import com.gemini.krakenbot.service.impl.PortfolioManagerImpl
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeTrue
import io.ktor.client.plugins.sse.sse
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import java.io.File
import java.io.IOException
import java.math.BigDecimal
import java.time.Instant
import io.ktor.client.plugins.sse.SSE as ClientSSE

internal fun EvaluationScenariosTest.registerScenarios22To28() {
    "Scenario 22: Order Failure Logging & Snapshot Mapping" {
        runTest {
            val fakeKraken = FakeKrakenService()
            val mockConfig = mockk<ConfigService>(relaxed = true)
            val appConfig =
                TestFixtures.config(
                    settings =
                    TestFixtures.settings(loopDelaySeconds = 60L, dryRun = false),
                    allocations =
                    listOf(
                        Allocation(Asset.BTC, 50.0),
                        Allocation(Asset.USD, 50.0),
                    ),
                )
            every { mockConfig.getConfig() } returns appConfig

            fakeKraken.balanceSupplier = {
                mapOf(
                    Asset.BTC to 0.0,
                    Asset.USD to 1000.0,
                )
            }
            fakeKraken.pricesSupplier = {
                mapOf(TestFixtures.XBTUSD to 50000.0)
            }
            fakeKraken.orderResultFactory = { pair, _, side, volume ->
                OrderResult(
                    success = false,
                    pair = pair,
                    side = side,
                    volume = volume,
                    errorMessage = "Insufficient funds",
                )
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

            val mockHistory = mockk<TradeHistoryService>(relaxed = true)
            val capturedActions = mutableListOf<String>()
            coEvery { mockHistory.addSnapshot(any()) } answers {
                capturedActions.addAll(firstArg<PortfolioSnapshot>().actions)
            }

            val pm =
                PortfolioManagerImpl(
                    mockConfig,
                    mockHistory,
                    analyzer,
                    executor,
                )
            pm.performRebalanceCycle()

            val failureLogged = capturedActions.any { it.contains("FAILED BUY BTC: Insufficient funds") }

            val evidence =
                "Target: buy 0.01 BTC ($500).\n" +
                    "Order result mocked to fail with 'Insufficient funds'.\n" +
                    "Captured actions in history snapshot: $capturedActions\n" +
                    "Error successfully logged in snapshot: $failureLogged"

            failureLogged.shouldBeTrue()
            EvaluationScenariosTest.recordResult(
                "Scenario 22",
                "Order Failure Logging & Snapshot Mapping",
                TestFixtures.PASS,
                evidence,
            )
        }
    }

    "Scenario 23: Complete Authentication API Failure" {
        runTest {
            val fakeKraken = FakeKrakenService()
            val mockConfig = mockk<ConfigService>(relaxed = true)
            val appConfig =
                TestFixtures.config(
                    settings =
                    TestFixtures.settings(loopDelaySeconds = 60L, dryRun = false),
                    allocations =
                    listOf(
                        Allocation(Asset.BTC, 50.0),
                        Allocation(Asset.USD, 50.0),
                    ),
                )
            every { mockConfig.getConfig() } returns appConfig

            fakeKraken.balanceSupplier = {
                throw IOException("EAPI:Invalid key or signature")
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

            val mockHistory = mockk<TradeHistoryService>(relaxed = true)
            val pm =
                PortfolioManagerImpl(
                    mockConfig,
                    mockHistory,
                    analyzer,
                    executor,
                )

            val exception =
                shouldThrow<IOException> {
                    pm.performRebalanceCycle()
                }

            val noOrders = fakeKraken.executedOrders.isEmpty()
            val signatureFail = exception.message?.contains("Invalid key or signature") == true

            val evidence =
                "Balances API call threw: ${exception.message}\n" +
                    "Executed orders count: ${fakeKraken.executedOrders.size}\n" +
                    "Rebalance cycle aborted safely: ${noOrders && signatureFail}"

            (noOrders && signatureFail).shouldBeTrue()
            EvaluationScenariosTest.recordResult(
                "Scenario 23",
                "Complete Authentication API Failure",
                TestFixtures.PASS,
                evidence,
            )
        }
    }

    "Scenario 24: Config File Writer Failure Protection" {
        runTest {
            val mapper = jacksonObjectMapper()
            val tempFile = File.createTempFile("scenario24-", ".json").apply { deleteOnExit() }

            val validSettings =
                TestFixtures.settings(loopDelaySeconds = 10)
            val validConfig =
                AppConfig(
                    KrakenCredentials("k", "s"),
                    validSettings,
                    listOf(Allocation(Asset.USD, 100.0)),
                )

            mapper.writeValue(tempFile, validConfig)

            val configService = ConfigServiceImpl(mapper, tempFile.absolutePath)

            tempFile.delete()
            tempFile.mkdirs()

            val exception =
                shouldThrow<RuntimeException> {
                    configService.updateConfig(validConfig)
                }

            tempFile.delete()

            val failureDetected = exception.message?.contains("Failed to save configuration") == true
            val evidence =
                "Config file path replaced by directory: ${tempFile.absolutePath}\n" +
                    "Update config threw RuntimeException as expected: $failureDetected (Msg: ${exception.message})"

            failureDetected.shouldBeTrue()
            EvaluationScenariosTest.recordResult(
                "Scenario 24",
                "Config File Writer Failure Protection",
                TestFixtures.PASS,
                evidence,
            )
        }
    }

    "Scenario 25: Minimum Order Size Rejection Recovery" {
        runTest {
            val fakeKraken = FakeKrakenService()
            val mockConfig = mockk<ConfigService>(relaxed = true)
            val appConfig =
                TestFixtures.config(
                    settings =
                    TestFixtures.settings(loopDelaySeconds = 60L, dryRun = false),
                    allocations =
                    listOf(
                        Allocation(Asset.BTC, 30.0),
                        Allocation(Asset.ETH, 30.0),
                        Allocation(Asset.USD, 40.0),
                    ),
                )
            every { mockConfig.getConfig() } returns appConfig

            fakeKraken.balanceSupplier = {
                mapOf(
                    Asset.BTC to 0.0,
                    Asset.ETH to 0.0,
                    Asset.USD to 1000.0,
                )
            }
            fakeKraken.pricesSupplier = {
                mapOf(
                    TestFixtures.XBTUSD to 50000.0,
                    TestFixtures.ETHUSD to 2000.0,
                )
            }

            fakeKraken.orderResultFactory = { pair, _, side, volume ->
                if (pair == TestFixtures.XBTUSD) {
                    OrderResult(false, pair, side, volume, errorMessage = "Order minimum size not met")
                } else {
                    OrderResult(true, pair, side, volume, orderTxid = "FAKE-$pair")
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

            val mockHistory = mockk<TradeHistoryService>(relaxed = true)
            val capturedActions = mutableListOf<String>()
            coEvery { mockHistory.addSnapshot(any()) } answers {
                capturedActions.addAll(firstArg<PortfolioSnapshot>().actions)
            }

            val pm =
                PortfolioManagerImpl(
                    mockConfig,
                    mockHistory,
                    analyzer,
                    executor,
                )
            pm.performRebalanceCycle()

            val btcFailedLogged = capturedActions.any { it.contains("FAILED BUY BTC: Order minimum size not met") }
            val ethSucceededLogged = capturedActions.any {
                it.startsWith("BUY ETH ") && !it.startsWith("FAILED BUY ETH")
            }
            val ethFailedLogged = capturedActions.any { it.startsWith("FAILED BUY ETH") }
            val ordersPlaced = fakeKraken.executedOrders.size == 2

            val success = btcFailedLogged && ethSucceededLogged && !ethFailedLogged && ordersPlaced
            val evidence =
                "Executed order calls: ${fakeKraken.executedOrders}\n" +
                    "Captured actions in history snapshot: $capturedActions\n" +
                    "BTC failure logged: $btcFailedLogged, ETH success logged: $ethSucceededLogged, " +
                    "ETH failure logged: $ethFailedLogged"

            success.shouldBeTrue()
            EvaluationScenariosTest.recordResult(
                "Scenario 25",
                "Minimum Order Size Rejection Recovery",
                TestFixtures.PASS,
                evidence,
            )
        }
    }

    "Scenario 26: Pure Cash Injection (No Sells, Only Buys)" {
        runTest {
            val fakeKraken = FakeKrakenService()
            val mockConfig = mockk<ConfigService>(relaxed = true)
            val appConfig =
                TestFixtures.config(
                    settings =
                    TestFixtures.settings(loopDelaySeconds = 60L, dryRun = false),
                    allocations =
                    listOf(
                        Allocation(Asset.BTC, 50.0),
                        Allocation(Asset.USD, 50.0),
                    ),
                )
            every { mockConfig.getConfig() } returns appConfig

            fakeKraken.balanceSupplier = {
                mapOf(
                    Asset.BTC to 0.5,
                    Asset.USD to 75000.0,
                )
            }
            fakeKraken.pricesSupplier = {
                mapOf(TestFixtures.XBTUSD to 50000.0)
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

            val sells = fakeKraken.executedOrders.filter { it.side == TestFixtures.SELL }
            val buys = fakeKraken.executedOrders.filter { it.side == TestFixtures.BUY }

            val onlyBtcBuy =
                buys.size == 1 && buys[0].pair == TestFixtures.XBTUSD &&
                    buys[0].volume.compareTo(BigDecimal("0.5")) == 0
            val zeroSells = sells.isEmpty()

            val success = onlyBtcBuy && zeroSells
            val evidence =
                "Executed buy orders: $buys\n" +
                    "Executed sell orders: $sells\n" +
                    "Correctly generated single buy of 0.5 BTC: $onlyBtcBuy"

            success.shouldBeTrue()
            EvaluationScenariosTest.recordResult(
                "Scenario 26",
                "Pure Cash Injection (No Sells, Only Buys)",
                TestFixtures.PASS,
                evidence,
            )
        }
    }

    "Scenario 27: Concurrency of Multiple SSE Listeners" {
        testApplication {
            application {
                configureTestEnv()
            }

            val snap =
                PortfolioSnapshot(
                    timestamp = Instant.now(),
                    totalValueUSD = BigDecimal("9999.99"),
                    assets = emptyMap(),
                    actions = listOf("CONCURRENT_SSE_TEST"),
                    drawdownPercent = BigDecimal.ZERO,
                    fiatDeploymentPercent = BigDecimal.ZERO,
                    effectiveUsdTargetPercent = BigDecimal.ZERO,
                )
            coEvery { tradeHistoryService.getLatestSnapshot() } returns snap
            val streamFlow = MutableSharedFlow<PortfolioSnapshot>(replay = 1, extraBufferCapacity = 1)
            streamFlow.tryEmit(snap).shouldBeTrue()
            every { tradeHistoryService.getHistoryFlow() } returns streamFlow

            val clientSse = createClient { install(ClientSSE) }

            val results = mutableListOf<String>()
            val jobs =
                (1..5).map { id ->
                    launch {
                        clientSse.sse("/api/status/stream") {
                            val event = incoming.take(1).toList().firstOrNull()
                            if (event != null && event.data?.contains("CONCURRENT_SSE_TEST") == true) {
                                synchronized(results) {
                                    results.add("Client $id OK")
                                }
                            }
                        }
                    }
                }

            jobs.joinAll()

            val ssePass = results.size == 5
            val evidence =
                "Connected 5 clients to the hot SSE flow.\n" +
                    "Clients that successfully received broadcast: $results\n" +
                    "All 5 clients received the snapshot: $ssePass"

            ssePass.shouldBeTrue()
            EvaluationScenariosTest.recordResult(
                "Scenario 27",
                "Concurrency of Multiple SSE Listeners",
                TestFixtures.PASS,
                evidence,
            )
        }
    }

    "Scenario 28: Zero Balance Division by Zero Prevention" {
        runTest {
            val fakeKraken = FakeKrakenService()
            val mockConfig = mockk<ConfigService>(relaxed = true)
            val appConfig =
                TestFixtures.config(
                    settings =
                    TestFixtures.settings(loopDelaySeconds = 60L, dryRun = false),
                    allocations =
                    listOf(
                        Allocation(Asset.BTC, 50.0),
                        Allocation(Asset.USD, 50.0),
                    ),
                )
            every { mockConfig.getConfig() } returns appConfig

            fakeKraken.balanceSupplier = {
                mapOf(
                    Asset.BTC to 0.0,
                    Asset.USD to 0.0,
                )
            }
            fakeKraken.pricesSupplier = {
                mapOf(TestFixtures.XBTUSD to 50000.0)
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

            val zeroOrders = fakeKraken.executedOrders.isEmpty()
            val evidence =
                "Zero balances supplied for BTC and USD.\n" +
                    "Executed orders count: ${fakeKraken.executedOrders.size}\n" +
                    "Rebalance cycle terminated safely: $zeroOrders"

            zeroOrders.shouldBeTrue()
            EvaluationScenariosTest.recordResult(
                "Scenario 28",
                "Zero Balance Division by Zero Prevention",
                TestFixtures.PASS,
                evidence,
            )
        }
    }
}
