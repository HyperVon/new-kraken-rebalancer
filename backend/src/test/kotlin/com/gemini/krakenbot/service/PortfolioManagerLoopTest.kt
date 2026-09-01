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
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.nio.file.Files
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds

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
                tradeHistoryService = tradeHistoryService,
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

        "stopRebalancingLoop_StopsExecution" {
            runTest {
                val settings = TestFixtures.settings(loopDelaySeconds = 60L)
                val config = TestFixtures.config(
                    settings = settings,
                )
                every { configService.getConfig() } returns config

                portfolioManager.startRebalancingLoop()
                portfolioManager.stopRebalancingLoop()

                portfolioManager.runLoop()

                krakenService.getBalancesCallCount shouldBe 0
                coVerify(exactly = 0) { tradeHistoryService.syncTradesFromKraken() }
            }
        }

        "checkAndRunCycle_HandlesExceptionGracefully" {
            runTest {
                val settings = TestFixtures.settings(loopDelaySeconds = 60L)
                val config = TestFixtures.config(
                    settings = settings,
                )
                every { configService.getConfig() } returns config

                krakenService.balanceSupplier =
                    { throw RuntimeException("API Error!") }

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

        "runLoop_HandlesSyncTradesExceptionGracefully" {
            runTest {
                val settings = TestFixtures.settings(loopDelaySeconds = 60L)
                val config = TestFixtures.config(
                    settings = settings,
                )
                every { configService.getConfig() } returns config

                coEvery { tradeHistoryService.syncTradesFromKraken() } throws RuntimeException("Sync error!")

                portfolioManager.startRebalancingLoop()
                val job = launch {
                    portfolioManager.runLoop()
                }
                runCurrent()
                advanceTimeBy(60_001.milliseconds)
                runCurrent()
                portfolioManager.stopRebalancingLoop()
                job.join()

                coVerify(atLeast = 2) { tradeHistoryService.syncTradesFromKraken() }
            }
        }

        "runLoop_SynchronizesLedgersBeforeTrades" {
            runTest {
                val settings = TestFixtures.settings(loopDelaySeconds = 60L)
                val config = TestFixtures.config(settings = settings)
                every { configService.getConfig() } returns config
                krakenService.balanceSupplier = { emptyMap() }
                val syncOrder = mutableListOf<String>()
                coEvery { tradeHistoryService.syncLedgersFromKraken() } answers {
                    syncOrder += "ledgers"
                }
                coEvery { tradeHistoryService.syncTradesFromKraken() } answers {
                    syncOrder += "trades"
                }

                portfolioManager.startRebalancingLoop()
                val job = launch { portfolioManager.runLoop() }
                runCurrent()
                portfolioManager.stopRebalancingLoop()
                job.join()

                syncOrder.take(2) shouldBe listOf("ledgers", "trades")
            }
        }

        "runLoop_ContinuesRebalanceWhenLedgerSyncFails" {
            runTest {
                val settings = TestFixtures.settings(loopDelaySeconds = 60L)
                val config = TestFixtures.config(settings = settings)
                every { configService.getConfig() } returns config
                krakenService.balanceSupplier = { emptyMap() }
                coEvery {
                    tradeHistoryService.syncLedgersFromKraken()
                } throws RuntimeException("Ledger sync error!")

                portfolioManager.startRebalancingLoop()
                val job = launch { portfolioManager.runLoop() }
                runCurrent()
                portfolioManager.stopRebalancingLoop()
                job.join()

                coVerify(atLeast = 1) { tradeHistoryService.syncTradesFromKraken() }
                krakenService.getBalancesCallCount shouldBe 1
                portfolioManager.getOperationalStatus().lastCycleSyncWarning shouldContain
                    "Ledger synchronization"
            }
        }

        "runLoop_ContinuesRebalanceWhenSnapshotRebuildFails" {
            runTest {
                val settings = TestFixtures.settings(loopDelaySeconds = 60L)
                val config = TestFixtures.config(settings = settings)
                every { configService.getConfig() } returns config
                krakenService.balanceSupplier = { emptyMap() }
                coEvery {
                    tradeHistoryService.rebuildHistoricalSnapshotsIfNeeded()
                } throws RuntimeException("Snapshot rebuild error!")

                portfolioManager.startRebalancingLoop()
                val job = launch { portfolioManager.runLoop() }
                runCurrent()
                portfolioManager.stopRebalancingLoop()
                job.join()

                krakenService.getBalancesCallCount shouldBe 1
            }
        }

        "runLoop propagates cancellation from startup sync instead of entering the loop" {
            runTest {
                val settings = TestFixtures.settings(loopDelaySeconds = 60L)
                val config = TestFixtures.config(
                    settings = settings,
                )
                every { configService.getConfig() } returns config

                coEvery {
                    tradeHistoryService.syncTradesFromKraken()
                } throws CancellationException("shutting down")

                portfolioManager.startRebalancingLoop()

                shouldThrow<CancellationException> { portfolioManager.runLoop() }

                // Swallowing cancellation here would log it and start rebalancing anyway.
                krakenService.getBalancesCallCount shouldBe 0
                portfolioManager.isLoopRunning() shouldBe false
            }
        }

        "cancellation during in-cycle sync stops the cycle before any rebalance work" {
            runTest {
                val settings = TestFixtures.settings(loopDelaySeconds = 60L)
                val config = TestFixtures.config(
                    settings = settings,
                )
                every { configService.getConfig() } returns config

                var syncCalls = 0
                coEvery { tradeHistoryService.syncTradesFromKraken() } answers {
                    syncCalls++
                    // Succeed on startup, then cancel once the loop body syncs.
                    if (syncCalls > 1) throw CancellationException("config changed")
                }

                portfolioManager.startRebalancingLoop()
                portfolioManager.runLoop()

                krakenService.getBalancesCallCount shouldBe 0
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

        "hot config lifecycle stops and restarts one managed worker without overlap" {
            runTest {
                val settings = TestFixtures.settings(loopDelaySeconds = 3600L)
                val config = TestFixtures.config(settings = settings)
                every { configService.getConfig() } returns config

                val configFlow = MutableSharedFlow<Settings>(replay = 1, extraBufferCapacity = 8)
                every { configService.watchConfigChanges() } returns configFlow
                val firstCycleSyncStarted = CompletableDeferred<Unit>()
                val restartedCycleSyncStarted = CompletableDeferred<Unit>()
                var syncCalls = 0
                coEvery { tradeHistoryService.syncTradesFromKraken() } coAnswers {
                    syncCalls++
                    if (syncCalls == 2) {
                        firstCycleSyncStarted.complete(Unit)
                        awaitCancellation()
                    }
                    if (syncCalls == 4) {
                        restartedCycleSyncStarted.complete(Unit)
                        awaitCancellation()
                    }
                }

                configFlow.emit(settings)
                val firstWorker = portfolioManager.startRebalancingLoop(this)
                runCurrent()
                firstCycleSyncStarted.await()

                // A second caller must not become a second hot-flow collector or startup sync.
                val duplicateCaller = launch { portfolioManager.runLoop() }
                duplicateCaller.join()
                syncCalls shouldBe 2

                portfolioManager.stopRebalancingLoop()
                firstWorker.join()

                // The replayed setting is still available, but the stopped worker must not consume it.
                configFlow.emit(settings)
                syncCalls shouldBe 2
                val secondWorker = portfolioManager.startRebalancingLoop(this)
                runCurrent()
                restartedCycleSyncStarted.await()

                syncCalls shouldBe 4
                (secondWorker !== firstWorker) shouldBe true

                portfolioManager.stopRebalancingLoop()
                secondWorker.join()
            }
        }

        "restart immediately after stop drains the cancelled worker before the new loop starts" {
            runTest {
                val settings = TestFixtures.settings(loopDelaySeconds = 3600L)
                val config = TestFixtures.config(settings = settings)
                every { configService.getConfig() } returns config

                val configFlow = MutableSharedFlow<Settings>(replay = 1, extraBufferCapacity = 8)
                every { configService.watchConfigChanges() } returns configFlow
                val firstCycleSyncStarted = CompletableDeferred<Unit>()
                val restartedCycleSyncStarted = CompletableDeferred<Unit>()
                val firstWorkerGate = CompletableDeferred<Unit>()
                val restartedWorkerGate = CompletableDeferred<Unit>()
                var syncCalls = 0
                coEvery { tradeHistoryService.syncTradesFromKraken() } coAnswers {
                    syncCalls++
                    // The gate is awaited inside NonCancellable so stop() cannot release the mutex
                    // through cancellation propagation; the new worker must therefore wait for the
                    // predecessor's join (and would never reach its own sync without it).
                    when (syncCalls) {
                        1 -> {
                            firstCycleSyncStarted.complete(Unit)
                            withContext(NonCancellable) { firstWorkerGate.await() }
                        }

                        2 -> {
                            restartedCycleSyncStarted.complete(Unit)
                            withContext(NonCancellable) { restartedWorkerGate.await() }
                        }

                        else -> throw AssertionError("unexpected sync call #$syncCalls")
                    }
                }

                configFlow.emit(settings)
                val firstWorker = portfolioManager.startRebalancingLoop(this)
                runCurrent()
                firstCycleSyncStarted.await()

                // The first worker is still holding runLoopMutex (suspended in NonCancellable),
                // so a stop+start without the join would let the new runLoop hit tryLock-fail
                // and the replacement would never call syncTradesFromKraken again.
                portfolioManager.stopRebalancingLoop()
                val secondWorker = portfolioManager.startRebalancingLoop(this)
                runCurrent()

                // The new worker is blocked on staleJob.join() and has not yet entered runLoop.
                restartedCycleSyncStarted.isCompleted shouldBe false
                syncCalls shouldBe 1

                // Release the first worker; its drain lets the new worker acquire the mutex
                // and run its own startup sync.
                firstWorkerGate.complete(Unit)
                runCurrent()
                restartedCycleSyncStarted.await()
                syncCalls shouldBe 2
                (secondWorker !== firstWorker) shouldBe true

                portfolioManager.stopRebalancingLoop()
                restartedWorkerGate.complete(Unit)
                runCurrent()
                secondWorker.join()
                firstWorker.join()
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
                    tradeHistoryService = tradeHistoryService,
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

        "cycle pins config and backend across sync and staged config publication" {
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
                var realBalanceCalls = 0
                coEvery { realBackend.getBalances() } coAnswers {
                    realBalanceCalls++
                    balances
                }
                coEvery { simulatedBackend.getTickerPrices(any()) } returns prices
                coEvery { realBackend.getTickerPrices(any()) } returns prices
                coEvery { simulatedBackend.getLedgers(any(), any(), any(), any()) } returns emptyList()
                coEvery { realBackend.getLedgers(any(), any(), any(), any()) } returns emptyList()
                coEvery { simulatedBackend.getTradeHistory(any(), any()) } returns emptyList()
                coEvery { realBackend.getTradeHistory(any(), any()) } returns emptyList()
                val successfulOrder = OrderResult(
                    success = true,
                    pair = Asset.BTC_USD_PAIR,
                    side = "sell",
                    volume = BigDecimal("0.02"),
                    dryRun = true,
                )
                coEvery {
                    simulatedBackend.executeOrder(any(), any(), any(), any(), any(), any())
                } returns successfulOrder
                coEvery {
                    realBackend.executeOrder(any(), any(), any(), any(), any(), any())
                } returns successfulOrder

                val statsRepository = mockk<PortfolioStatsRepository>(relaxed = true)
                coEvery { statsRepository.load() } returns PortfolioStats(BigDecimal("10000.00"))
                val tradeHistoryService = mockk<TradeHistoryService>(relaxed = true)
                val syncEntered = CompletableDeferred<Unit>()
                val releaseSync = CompletableDeferred<Unit>()
                val snapshotAdded = CompletableDeferred<Unit>()
                val releaseSnapshot = CompletableDeferred<Unit>()
                var ledgerSyncCalls = 0
                coEvery { tradeHistoryService.syncLedgersFromKraken() } coAnswers {
                    ledgerSyncCalls++
                    dynamicKrakenService.getLedgers()
                    if (ledgerSyncCalls == 2) {
                        syncEntered.complete(Unit)
                        releaseSync.await()
                    }
                }
                coEvery { tradeHistoryService.syncTradesFromKraken() } coAnswers {
                    dynamicKrakenService.getTradeHistory()
                }
                coEvery { tradeHistoryService.addSnapshot(any()) } coAnswers {
                    snapshotAdded.complete(Unit)
                    releaseSnapshot.await()
                }
                val analyzer = PortfolioAnalyzerImpl(
                    krakenService = dynamicKrakenService,
                    configService = realConfigService,
                    portfolioStatsRepository = statsRepository,
                )
                val manager = PortfolioManagerImpl(
                    configService = realConfigService,
                    tradeHistoryService = tradeHistoryService,
                    portfolioAnalyzer = analyzer,
                    orderExecutor = OrderExecutorImpl(dynamicKrakenService, tradeHistoryService),
                    krakenService = dynamicKrakenService,
                )

                val publishedSettings = initialSettings.copy(
                    simulation = false,
                    loopDelaySeconds = 7200L,
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
                syncEntered.await()

                realConfigService.updateConfig(publishedConfig)
                runCurrent()
                objectMapper.readValue(configFile, AppConfig::class.java) shouldBe publishedConfig
                realConfigService.getConfig() shouldBe runtimeInitialConfig
                configEvents shouldBe listOf(initialSettings)

                releaseSync.complete(Unit)
                snapshotAdded.await()
                coVerify(atLeast = 2) { simulatedBackend.getLedgers(any(), any(), any(), any()) }
                coVerify(atLeast = 2) { simulatedBackend.getTradeHistory(any(), any()) }
                coVerify(atLeast = 1) { simulatedBackend.getBalances() }
                coVerify(atLeast = 1) { simulatedBackend.getTickerPrices(any()) }
                coVerify {
                    simulatedBackend.executeOrder(any(), any(), any(), any(), true, any())
                }
                coVerify(exactly = 0) { realBackend.getLedgers(any(), any(), any(), any()) }
                coVerify(exactly = 0) { realBackend.getTradeHistory(any(), any()) }
                coVerify(exactly = 0) { realBackend.getBalances() }
                coVerify(exactly = 0) { realBackend.getTickerPrices(any()) }
                coVerify(exactly = 0) {
                    realBackend.executeOrder(any(), any(), any(), any(), any(), any())
                }

                releaseSnapshot.complete(Unit)
                publication.await()
                realConfigService.getConfig() shouldBe publishedConfig
                configEvents shouldBe listOf(initialSettings, publishedSettings)
                manager.stopRebalancingLoop()
                worker.join()
                configCollector.cancel()

                val realBalanceCallsBeforeUnpinnedRead = realBalanceCalls
                dynamicKrakenService.getBalances()
                realBalanceCalls shouldBe realBalanceCallsBeforeUnpinnedRead + 1
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
                    tradeHistoryService = tradeHistoryService,
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
                coEvery { analyzer.updateAthAndCalculateDrawdown(any()) } returns BigDecimal.ZERO
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
                val settings = TestFixtures.settings(loopDelaySeconds = 60L)
                val config = TestFixtures.config(settings = settings)
                every { configService.getConfig() } returns config
                krakenService.balanceSupplier = { emptyMap() }

                val firstWorkerStarted = CompletableDeferred<Unit>()
                val secondWorkerStarted = CompletableDeferred<Unit>()
                var startupSyncCalls = 0
                coEvery { tradeHistoryService.syncLedgersFromKraken() } coAnswers {
                    startupSyncCalls++
                    when (startupSyncCalls) {
                        1 -> {
                            firstWorkerStarted.complete(Unit)
                            awaitCancellation()
                        }

                        2 -> secondWorkerStarted.complete(Unit)
                    }
                }

                val initialWorker = portfolioManager.startRebalancingLoop(this)
                runCurrent()
                firstWorkerStarted.isCompleted shouldBe true
                portfolioManager.pauseLoop()
                portfolioManager.isLoopPaused() shouldBe true

                portfolioManager.resumeLoop()
                runCurrent()

                portfolioManager.isLoopPaused() shouldBe false
                secondWorkerStarted.isCompleted shouldBe true
                portfolioManager.stopRebalancingLoop()
                initialWorker.join()
            }
        }

        "resumeLoop_WithoutScope_Throws" {
            runTest {
                val pm = PortfolioManagerImpl(
                    configService = configService,
                    tradeHistoryService = tradeHistoryService,
                    portfolioAnalyzer = portfolioAnalyzer,
                    orderExecutor = orderExecutor,
                )
                shouldThrow<IllegalStateException> { pm.resumeLoop() }
            }
        }

        "startRebalancingLoop with active worker returns existing job and reports running" {
            runTest {
                val settings = TestFixtures.settings(loopDelaySeconds = 60L)
                val config = TestFixtures.config(settings = settings)
                every { configService.getConfig() } returns config
                coEvery { tradeHistoryService.syncTradesFromKraken() } coAnswers { awaitCancellation() }

                val job1 = portfolioManager.startRebalancingLoop(this)
                runCurrent()
                portfolioManager.isLoopRunning() shouldBe true

                val job2 = portfolioManager.startRebalancingLoop(this)
                job2 shouldBe job1
                portfolioManager.isLoopRunning() shouldBe true

                portfolioManager.stopRebalancingLoop()
                runCurrent()
                portfolioManager.isLoopRunning() shouldBe false
            }
        }

        "shutdown joins the current worker after pause and resume, not the stale startup worker" {
            runTest {
                val settings = TestFixtures.settings(loopDelaySeconds = 60L)
                val config = TestFixtures.config(settings = settings)
                every { configService.getConfig() } returns config

                val aGate = CompletableDeferred<Unit>()
                val bGate = CompletableDeferred<Unit>()
                var syncCalls = 0
                coEvery { tradeHistoryService.syncTradesFromKraken() } coAnswers {
                    syncCalls++
                    when (syncCalls) {
                        1 -> withContext(NonCancellable) { aGate.await() }
                        2 -> withContext(NonCancellable) { bGate.await() }
                        else -> error("unexpected sync call #$syncCalls")
                    }
                }

                // Worker A starts and parks inside its startup sync.
                val a = portfolioManager.startRebalancingLoop(this)
                runCurrent()
                syncCalls shouldBe 1

                // Pause cancels A, then release A so it can drain to completion.
                portfolioManager.pauseLoop()
                aGate.complete(Unit)
                runCurrent()

                // Resume creates replacement worker B, which parks inside its own startup sync.
                portfolioManager.resumeLoop()
                runCurrent()
                syncCalls shouldBe 2
                portfolioManager.isLoopPaused() shouldBe false

                // Shutdown stops the current worker and returns the job it actually cancelled.
                val stoppedWorker = portfolioManager.stopRebalancingLoop()!!
                (stoppedWorker !== a) shouldBe true

                var dependenciesReleased = false
                val joiner = launch {
                    joinRebalancingWorker(stoppedWorker) shouldBe true
                    dependenciesReleased = true
                }
                runCurrent()

                // B is still draining inside its NonCancellable gate (cancellation is deferred until
                // the gate releases), so the dependency release must not proceed yet. A is already
                // complete, so joining the stale startup worker would have released immediately --
                // proving shutdown now waits on the live worker B.
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
