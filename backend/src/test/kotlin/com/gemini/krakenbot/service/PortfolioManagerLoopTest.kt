@file:OptIn(ExperimentalCoroutinesApi::class)

package com.gemini.krakenbot.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.domain.OrderResult
import com.gemini.krakenbot.domain.PortfolioValues
import com.gemini.krakenbot.domain.RebalancePlan
import com.gemini.krakenbot.joinRebalancingWorker
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.PortfolioStats
import com.gemini.krakenbot.model.Result
import com.gemini.krakenbot.repository.PortfolioStatsRepository
import com.gemini.krakenbot.service.impl.ConfigServiceImpl
import com.gemini.krakenbot.service.impl.DynamicKrakenService
import com.gemini.krakenbot.service.impl.KrakenServiceImpl
import com.gemini.krakenbot.service.impl.OrderExecutorImpl
import com.gemini.krakenbot.service.impl.PortfolioAnalyzerImpl
import com.gemini.krakenbot.service.impl.PortfolioManagerImpl
import com.gemini.krakenbot.service.impl.SimulatedKrakenService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.nio.file.Files
import java.time.Instant

class PortfolioManagerLoopTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    private val krakenService = FakeKrakenService()
    private val configService = mockk<ConfigService>(relaxed = true)
    private val tradeHistoryService = mockk<TradeHistoryService>(relaxed = true)
    private lateinit var portfolioManager: PortfolioManagerImpl
    private lateinit var portfolioAnalyzer: PortfolioAnalyzer
    private lateinit var orderExecutor: OrderExecutor

    init {
        beforeTest {
            val repo = mockk<PortfolioStatsRepository>(relaxed = true)
            coEvery {
                repo.load()
            } returns PortfolioStats(BigDecimal.ZERO)
            portfolioAnalyzer =
                PortfolioAnalyzerImpl(
                    krakenService = krakenService,
                    configService = configService,
                    portfolioStatsRepository = repo,
                )
            orderExecutor = OrderExecutorImpl(krakenService, tradeHistoryService)
            portfolioManager = PortfolioManagerImpl(
                configService = configService,
                portfolioAnalyzer = portfolioAnalyzer,
                orderExecutor = orderExecutor,
            )
            every { configService.watchConfigChanges() } answers {
                flowOf(configService.getConfig().settings)
            }
        }

        "startRebalancingLoop_RunsWhenEnabled" {
            runTest {
                val settings = TestFixtures.settings(loopDelaySeconds = 60L)
                val config = TestFixtures.config(
                    settings = settings,
                )
                every { configService.getConfig() } returns config
                krakenService.balanceSupplier = { emptyMap() }

                portfolioManager.startRebalancingLoop()
                val job = launch {
                    portfolioManager.runLoop()
                }
                runCurrent()
                portfolioManager.stopRebalancingLoop()
                job.cancel()

                krakenService.getBalancesCallCount shouldBe 1
            }
        }

        "normal rebalance cycles do not call retrospective history services" {
            runTest {
                val config = TestFixtures.config(
                    settings = TestFixtures.settings(simulation = true, dryRun = true, loopDelaySeconds = 60L),
                    allocations = listOf(Allocation(Asset.USD, 100.0)),
                )
                every { configService.getConfig() } returns config
                krakenService.balanceSupplier = { mapOf(Asset.USD to 100.0) }

                portfolioManager.startRebalancingLoop()
                val job = launch { portfolioManager.runLoop() }
                runCurrent()
                portfolioManager.stopRebalancingLoop()
                job.join()

                krakenService.getBalancesCallCount shouldBe 1
                coVerify(exactly = 0) { tradeHistoryService.syncLedgersFromKraken() }
                coVerify(exactly = 0) { tradeHistoryService.syncTradesFromKraken() }
                coVerify(exactly = 0) { tradeHistoryService.rebuildHistoricalSnapshotsIfNeeded(any()) }
                coVerify(exactly = 0) { tradeHistoryService.addSnapshot(any()) }
            }
        }

        "runLoop called before start does not enter the trading cycle" {
            runTest {
                every { configService.getConfig() } returns TestFixtures.config(
                    settings = TestFixtures.settings(loopDelaySeconds = 60L),
                )

                portfolioManager.runLoop()

                krakenService.getBalancesCallCount shouldBe 0
                portfolioManager.isLoopRunning() shouldBe false
            }
        }

        "runLoop records a failed cycle and keeps its worker alive for the next interval" {
            runTest {
                every { configService.getConfig() } returns TestFixtures.config(
                    settings = TestFixtures.settings(loopDelaySeconds = 3600L),
                )
                krakenService.balanceSupplier = { throw IllegalStateException("balance endpoint unavailable") }

                val worker = portfolioManager.startRebalancingLoop(this)
                runCurrent()

                krakenService.getBalancesCallCount shouldBe 1
                portfolioManager.getOperationalStatus().lastCycleError shouldBe "IllegalStateException"
                portfolioManager.isLoopRunning() shouldBe true

                portfolioManager.stopRebalancingLoop()
                worker.join()
                portfolioManager.isLoopRunning() shouldBe false
            }
        }

        "an order execution exception still produces an actual portfolio snapshot" {
            runTest {
                val config = TestFixtures.config(
                    settings = TestFixtures.settings(dryRun = true, simulation = false),
                    allocations = listOf(
                        Allocation(Asset.BTC, 50.0),
                        Allocation(Asset.USD, 50.0),
                    ),
                )
                every { configService.getConfig() } returns config
                krakenService.balanceSupplier = { mapOf(Asset.USD to 100.0) }
                krakenService.pricesSupplier = { mapOf(Asset.BTC_USD_PAIR to 100.0) }
                val failedExecutor = mockk<OrderExecutor>()
                coEvery {
                    failedExecutor.executeOrders(any(), any(), any(), any(), any(), any(), any(), any())
                } throws IllegalStateException("order adapter failed")
                val manager = PortfolioManagerImpl(
                    configService = configService,
                    portfolioAnalyzer = portfolioAnalyzer,
                    orderExecutor = failedExecutor,
                    krakenService = krakenService,
                )

                val snapshot = manager.performRebalanceCycle().shouldNotBeNull()

                snapshot.totalValueUSD.shouldBeEqualComparingTo(BigDecimal("100.00"))
                manager.getOperationalStatus().lastCycleError shouldBe "Order execution failed"
                snapshot.actions.any { it.contains("order adapter failed") } shouldBe true
                coVerify(exactly = 1) {
                    failedExecutor.executeOrders(any(), any(), any(), any(), any(), any(), any(), any())
                }
            }
        }

        "post-trade valuation failure retains the independently observed pre-trade snapshot" {
            runTest {
                val config = TestFixtures.config(
                    settings = TestFixtures.settings(dryRun = true, simulation = false),
                    allocations = listOf(
                        Allocation(Asset.BTC, 40.0),
                        Allocation(Asset.USD, 60.0),
                    ),
                )
                every { configService.getConfig() } returns config
                krakenService.balanceSupplier = {
                    mapOf(Asset.BTC to 0.2, Asset.USD to 80.0)
                }
                var priceReads = 0
                krakenService.pricesSupplier = {
                    priceReads += 1
                    if (priceReads == 1) mapOf(Asset.BTC_USD_PAIR to 100.0) else emptyMap()
                }
                val successfulExecutor = mockk<OrderExecutor>(relaxed = true)
                val manager = PortfolioManagerImpl(
                    configService = configService,
                    portfolioAnalyzer = portfolioAnalyzer,
                    orderExecutor = successfulExecutor,
                    krakenService = krakenService,
                )

                val snapshot = manager.performRebalanceCycle().shouldNotBeNull()

                snapshot.totalValueUSD.shouldBeEqualComparingTo(BigDecimal("100.00"))
                snapshot.assets.getValue(Asset.BTC).balance.shouldBeEqualComparingTo(BigDecimal("0.2"))
                manager.getOperationalStatus().lastCycleError shouldBe "Post-trade valuation failed"
                krakenService.getBalancesCallCount shouldBe 2
            }
        }

        "config change mid-delay restarts the loop without waiting out the old delay" {
            runTest {
                val longDelaySettings = TestFixtures.settings(loopDelaySeconds = 3600L)
                val config = TestFixtures.config(
                    settings = longDelaySettings,
                )
                every { configService.getConfig() } returns config
                krakenService.balanceSupplier = { emptyMap() }

                val configFlow = MutableSharedFlow<Settings>(replay = 1, extraBufferCapacity = 8)
                every { configService.watchConfigChanges() } returns configFlow
                configFlow.emit(longDelaySettings)

                portfolioManager.startRebalancingLoop()
                val job = launch { portfolioManager.runLoop() }
                runCurrent()

                val cyclesBeforeChange = krakenService.getBalancesCallCount
                cyclesBeforeChange shouldBe 1

                // Emitted while the loop is parked in the 1h delay: collectLatest must cancel and
                // restart the cycle immediately with the new settings.
                configFlow.emit(longDelaySettings.copy(deviationTriggerPercent = 5.0))
                runCurrent()

                krakenService.getBalancesCallCount shouldBe cyclesBeforeChange + 1

                job.cancel()
            }
        }

        "cancellation during execution rethrows and closes the execution session" {
            runTest {
                val settings = TestFixtures.settings(loopDelaySeconds = 3600L)
                val config = TestFixtures.config(
                    settings = settings,
                    allocations = listOf(
                        Allocation(TestFixtures.A, 50.0),
                        Allocation(TestFixtures.B, 50.0),
                    ),
                )
                every { configService.getConfig() } returns config
                krakenService.pricesSupplier = {
                    mapOf(TestFixtures.AUSD to 100.0, TestFixtures.BUSD to 100.0)
                }
                krakenService.balanceSupplier = {
                    mapOf(TestFixtures.A to 11.0, TestFixtures.B to 9.0)
                }

                val executionStarted = CompletableDeferred<Unit>()
                val blockingExecutor = mockk<OrderExecutor>()
                coEvery {
                    blockingExecutor.executeOrders(any(), any(), any(), any(), any(), any(), any(), any())
                } coAnswers {
                    executionStarted.complete(Unit)
                    awaitCancellation()
                }
                val manager = PortfolioManagerImpl(
                    configService = configService,
                    portfolioAnalyzer = portfolioAnalyzer,
                    orderExecutor = blockingExecutor,
                    krakenService = krakenService,
                )

                val worker = manager.startRebalancingLoop(this)
                executionStarted.await()

                manager.stopRebalancingLoop()
                worker.join()

                manager.isLoopRunning() shouldBe false
                coVerify(exactly = 1) { configService.beginExecutionSession() }
                coVerify(exactly = 1) { configService.endExecutionSession() }
                coVerify(exactly = 0) { tradeHistoryService.addSnapshot(any()) }
            }
        }

        "cycle pins config and backend across staged config publication" {
            runTest {
                val objectMapper = jacksonObjectMapper()
                val configFile = Files.createTempDirectory("cycle-pin").resolve("config.json").toFile()
                val initialConfig = TestFixtures.config(
                    settings = TestFixtures.settings(
                        dryRun = true,
                        simulation = true,
                        loopDelaySeconds = 3600L,
                    ),
                    allocations = listOf(
                        Allocation(Asset.BTC, 50.0),
                        Allocation(Asset.USD, 50.0),
                    ),
                )
                objectMapper.writeValue(configFile, initialConfig)
                val realConfigService = ConfigServiceImpl(objectMapper, configFile.absolutePath)
                val runtimeInitialConfig = realConfigService.getConfig()
                val initialSettings = runtimeInitialConfig.settings
                val realBackend = mockk<KrakenServiceImpl>(relaxed = true)
                val simulatedBackend = mockk<SimulatedKrakenService>(relaxed = true)
                val dynamicKrakenService = DynamicKrakenService(realBackend, simulatedBackend, realConfigService)
                val balances = mapOf(
                    Asset.BTC to BigDecimal("0.12"),
                    Asset.USD to BigDecimal("4000.00"),
                )
                val prices = mapOf(Asset.BTC_USD_PAIR to BigDecimal("50000.00"))
                coEvery { simulatedBackend.getBalances() } returns balances
                coEvery { simulatedBackend.getTickerPrices(any()) } returns prices

                val orderEntered = CompletableDeferred<Unit>()
                val releaseOrder = CompletableDeferred<Unit>()
                val successfulOrder = OrderResult(
                    success = true,
                    pair = Asset.BTC_USD_PAIR,
                    side = "sell",
                    volume = BigDecimal("0.02"),
                    dryRun = true,
                )
                coEvery {
                    simulatedBackend.executeOrder(any(), any(), any(), any(), any(), any())
                } coAnswers {
                    orderEntered.complete(Unit)
                    releaseOrder.await()
                    successfulOrder
                }

                val statsRepository = mockk<PortfolioStatsRepository>(relaxed = true)
                val analyzer = PortfolioAnalyzerImpl(
                    krakenService = dynamicKrakenService,
                    configService = realConfigService,
                    portfolioStatsRepository = statsRepository,
                )
                val manager = PortfolioManagerImpl(
                    configService = realConfigService,
                    portfolioAnalyzer = analyzer,
                    orderExecutor = OrderExecutorImpl(dynamicKrakenService, null),
                    krakenService = dynamicKrakenService,
                )

                val publishedSettings = initialSettings.copy(
                    deviationTriggerPercent = initialSettings.deviationTriggerPercent + 1.0,
                )
                val publishedConfig = runtimeInitialConfig.copy(settings = publishedSettings)
                val configEvents = mutableListOf<Settings>()
                val publication = CompletableDeferred<Unit>()
                val configCollector = launch {
                    realConfigService.watchConfigChanges().collect { settings ->
                        configEvents += settings
                        if (settings == publishedSettings) publication.complete(Unit)
                    }
                }
                runCurrent()
                configEvents shouldBe listOf(initialSettings)

                val worker = manager.startRebalancingLoop(this)
                orderEntered.await()

                realConfigService.updateConfig(publishedConfig)
                runCurrent()
                objectMapper.readValue(configFile, AppConfig::class.java) shouldBe publishedConfig
                realConfigService.getConfig() shouldBe runtimeInitialConfig
                configEvents shouldBe listOf(initialSettings)

                releaseOrder.complete(Unit)
                publication.await()
                coVerify(atLeast = 1) { simulatedBackend.getBalances() }
                coVerify(atLeast = 1) { simulatedBackend.getTickerPrices(any()) }
                coVerify(atLeast = 1) {
                    simulatedBackend.executeOrder(any(), any(), any(), any(), true, any())
                }
                coVerify(exactly = 0) { realBackend.getBalances() }
                coVerify(exactly = 0) { realBackend.getTickerPrices(any()) }
                coVerify(exactly = 0) {
                    realBackend.executeOrder(any(), any(), any(), any(), any(), any())
                }
                coVerify(exactly = 0) { realBackend.getLedgers(any(), any(), any(), any()) }
                coVerify(exactly = 0) { realBackend.getTradeHistory(any(), any()) }

                realConfigService.getConfig() shouldBe publishedConfig
                configEvents shouldBe listOf(initialSettings, publishedSettings)
                manager.stopRebalancingLoop()
                worker.join()
                configCollector.cancel()
            }
        }

        "cancellation after analysis prevents order execution" {
            runTest {
                val settings = TestFixtures.settings(loopDelaySeconds = 60L)
                val config = TestFixtures.config(settings = settings)
                every { configService.getConfig() } returns config

                val analyzer = mockk<PortfolioAnalyzer>()
                val executor = mockk<OrderExecutor>(relaxed = true)
                val manager = PortfolioManagerImpl(
                    configService = configService,
                    portfolioAnalyzer = analyzer,
                    orderExecutor = executor,
                    krakenService = null,
                )
                val balances = emptyMap<String, BigDecimal>()
                val prices = emptyMap<String, BigDecimal>()
                coEvery { analyzer.fetchBalances() } returns balances
                coEvery { analyzer.fetchObservedBalances() } returns ObservedBalances(balances, Instant.now())
                coEvery { analyzer.fetchPrices() } returns prices
                every { analyzer.calculatePortfolioValues(any(), any()) } returns Result.Success(
                    PortfolioValues(
                        totalValueUSD = BigDecimal("100.00"),
                        currentValuesUSD = mapOf(TestFixtures.A to BigDecimal("100.00")),
                    ),
                )
                coEvery { analyzer.updateAthAndCalculateDrawdown(any(), any(), any()) } returns
                    com.gemini.krakenbot.service.AthUpdateResult.Trusted(BigDecimal.ZERO)
                every { analyzer.calculateFiatDeployment(any(), any()) } returns BigDecimal.ZERO
                every { analyzer.calculateEffectiveUsdTarget(any()) } returns BigDecimal.ZERO
                every { analyzer.calculateCryptoScaleFactor(any()) } returns BigDecimal.ONE

                lateinit var cycleJob: Job
                every { analyzer.analyzeDeviations(any(), any(), any(), any()) } answers {
                    cycleJob.cancel()
                    RebalancePlan(
                        buyOrders = mapOf(TestFixtures.A to BigDecimal("10.00")),
                        sellOrders = emptyMap(),
                        events = emptyList(),
                    )
                }

                cycleJob = launch { manager.performRebalanceCycle() }
                cycleJob.join()

                coVerify(exactly = 0) {
                    executor.executeOrders(any(), any(), any(), any(), any(), any(), any(), any())
                }
                // The session is owned by the loop body, not by performRebalanceCycle.
                coVerify(exactly = 0) { configService.beginExecutionSession() }
                coVerify(exactly = 0) { configService.endExecutionSession() }
            }
        }

        "pauseLoop_SetsPausedFlagAndCancelsWorker" {
            runTest {
                val settings = TestFixtures.settings(loopDelaySeconds = 60L)
                val config = TestFixtures.config(settings = settings)
                every { configService.getConfig() } returns config
                krakenService.balanceSupplier = { emptyMap() }

                portfolioManager.startRebalancingLoop()
                portfolioManager.pauseLoop()

                portfolioManager.isLoopPaused() shouldBe true
            }
        }

        "resumeLoop_AfterPause_RestartsWorker" {
            runTest {
                val settings = TestFixtures.settings(simulation = true, dryRun = true, loopDelaySeconds = 60L)
                val config = TestFixtures.config(
                    settings = settings,
                    allocations = listOf(Allocation(Asset.USD, 100.0)),
                )
                every { configService.getConfig() } returns config
                krakenService.balanceSupplier = { mapOf(Asset.USD to 100.0) }

                val initialWorker = portfolioManager.startRebalancingLoop(this)
                runCurrent()
                val firstCycleBalanceCalls = krakenService.getBalancesCallCount
                firstCycleBalanceCalls shouldBe 1
                portfolioManager.pauseLoop()
                portfolioManager.isLoopPaused() shouldBe true

                portfolioManager.resumeLoop()
                runCurrent()

                portfolioManager.isLoopPaused() shouldBe false
                krakenService.getBalancesCallCount shouldBe firstCycleBalanceCalls + 1
                portfolioManager.stopRebalancingLoop()?.join()
                initialWorker.join()
            }
        }

        "resumeLoop_WithoutScope_Throws" {
            runTest {
                val pm = PortfolioManagerImpl(
                    configService = configService,
                    portfolioAnalyzer = portfolioAnalyzer,
                    orderExecutor = orderExecutor,
                )
                shouldThrow<IllegalStateException> { pm.resumeLoop() }
            }
        }

        "startRebalancingLoop with active worker returns existing job and reports running" {
            runTest {
                val settings = TestFixtures.settings(simulation = true, dryRun = true, loopDelaySeconds = 60L)
                val config = TestFixtures.config(
                    settings = settings,
                    allocations = listOf(Allocation(Asset.USD, 100.0)),
                )
                every { configService.getConfig() } returns config
                krakenService.balanceSupplier = { mapOf(Asset.USD to 100.0) }

                val job1 = portfolioManager.startRebalancingLoop(this)
                runCurrent()
                portfolioManager.isLoopRunning() shouldBe true

                val job2 = portfolioManager.startRebalancingLoop(this)
                job2 shouldBe job1
                portfolioManager.isLoopRunning() shouldBe true

                portfolioManager.stopRebalancingLoop()
                runCurrent()
                job1.join()
                portfolioManager.isLoopRunning() shouldBe false
            }
        }

        "shutdown joins the current worker after pause and resume, not the stale worker" {
            runTest {
                val settings = TestFixtures.settings(simulation = true, dryRun = true, loopDelaySeconds = 60L)
                val config = TestFixtures.config(
                    settings = settings,
                    allocations = listOf(
                        Allocation(Asset.BTC, 50.0),
                        Allocation(Asset.USD, 50.0),
                    ),
                )
                every { configService.getConfig() } returns config
                krakenService.balanceSupplier = { mapOf(Asset.USD to 1000.0) }
                krakenService.pricesSupplier = { mapOf(Asset.BTC_USD_PAIR to 100.0) }

                val aGate = CompletableDeferred<Unit>()
                val bGate = CompletableDeferred<Unit>()
                var executionCalls = 0
                val blockingExecutor = mockk<OrderExecutor>()
                coEvery {
                    blockingExecutor.executeOrders(any(), any(), any(), any(), any(), any(), any(), any())
                } coAnswers {
                    executionCalls++
                    when (executionCalls) {
                        1 -> withContext(NonCancellable) { aGate.await() }
                        2 -> withContext(NonCancellable) { bGate.await() }
                        else -> error("unexpected execution call #$executionCalls")
                    }
                }
                val manager = PortfolioManagerImpl(
                    configService = configService,
                    portfolioAnalyzer = portfolioAnalyzer,
                    orderExecutor = blockingExecutor,
                    krakenService = krakenService,
                )

                val a = manager.startRebalancingLoop(this)
                runCurrent()
                executionCalls shouldBe 1

                manager.pauseLoop()
                aGate.complete(Unit)
                runCurrent()

                manager.resumeLoop()
                runCurrent()
                executionCalls shouldBe 2
                manager.isLoopPaused() shouldBe false

                val stoppedWorker = manager.stopRebalancingLoop()!!
                (stoppedWorker !== a) shouldBe true

                var dependenciesReleased = false
                val joiner = launch {
                    joinRebalancingWorker(stoppedWorker) shouldBe true
                    dependenciesReleased = true
                }
                runCurrent()
                stoppedWorker.isCompleted shouldBe false
                a.isCompleted shouldBe true
                dependenciesReleased shouldBe false

                bGate.complete(Unit)
                runCurrent()
                joiner.join()
                dependenciesReleased shouldBe true
                a.join()
                stoppedWorker.join()
            }
        }
    }
}
