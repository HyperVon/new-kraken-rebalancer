@file:OptIn(ExperimentalCoroutinesApi::class)

package com.gemini.krakenbot.service

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.model.PortfolioStats
import com.gemini.krakenbot.model.Result
import com.gemini.krakenbot.repository.PortfolioStatsRepository
import com.gemini.krakenbot.service.impl.OrderExecutorImpl
import com.gemini.krakenbot.service.impl.PortfolioAnalyzerImpl
import com.gemini.krakenbot.service.impl.PortfolioManagerImpl
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.math.BigDecimal
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
                yield()
                portfolioManager.stopRebalancingLoop()
                job.join()
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
                    AnalysisResult(
                        buyOrders = mapOf(TestFixtures.A to BigDecimal("10.00")),
                        sellOrders = emptyMap(),
                        actionLog = emptyList(),
                    )
                }

                cycleJob = launch { manager.performRebalanceCycle() }
                cycleJob.join()

                coVerify(exactly = 0) {
                    executor.executeOrders(any(), any(), any(), any(), any(), any(), any(), any())
                }
                coVerify(exactly = 1) { configService.beginExecutionSession() }
                coVerify(exactly = 1) { configService.endExecutionSession() }
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
    }
}
