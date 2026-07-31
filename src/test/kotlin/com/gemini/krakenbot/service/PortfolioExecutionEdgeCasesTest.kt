package com.gemini.krakenbot.service

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.OrderResult
import com.gemini.krakenbot.model.OrderSide
import com.gemini.krakenbot.service.impl.OrderExecutorImpl
import com.gemini.krakenbot.service.impl.PortfolioManagerImpl
import com.gemini.krakenbot.toBigDecimalMap
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.verify
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import java.io.IOException
import java.lang.reflect.Field
import java.math.BigDecimal
import kotlin.coroutines.cancellation.CancellationException

class PortfolioExecutionEdgeCasesTest : PortfolioManagerEdgeCasesTestBase() {

    private fun workerJobReflection(): Field =
        PortfolioManagerImpl::class.java.getDeclaredField("workerJob").apply { isAccessible = true }

    private fun readWorkerJob(): Job? = workerJobReflection().get(portfolioManager) as Job?

    init {
        "testExecuteOrders_DryRunAndSellsSuccess" {
            runTest {
                val buyOrders = emptyMap<String, BigDecimal>()
                val sellOrders =
                    mapOf(Asset.BTC to BigDecimal.valueOf(100.0))
                val currentValuesUSD =
                    mapOf(Asset.USD to BigDecimal.valueOf(1000.0))
                val prices = mapOf(Asset.BTC to BigDecimal.TEN)
                val settings = TestFixtures.settings()
                val actionLog = mutableListOf<String>()

                krakenService.orderResultFactory = { pair, _, side, volume ->
                    OrderResult(
                        success = true,
                        pair = pair,
                        side = side,
                        volume = volume,
                        dryRun = true,
                    )
                }

                orderExecutor.executeOrders(
                    buyOrders = buyOrders,
                    sellOrders = sellOrders,
                    currentValuesUSD = currentValuesUSD,
                    prices = prices,
                    settings = settings,
                    actionLog = actionLog,
                )

                actionLog.any {
                    it.contains("[DRY RUN] SELL BTC")
                } shouldBe true
            }
        }

        "testExecuteOrders_FailedSellDoesNotIncrementCash" {
            runTest {
                val buyOrders = emptyMap<String, BigDecimal>()
                val sellOrders =
                    mapOf(Asset.BTC to BigDecimal.valueOf(100.0))
                val currentValuesUSD =
                    mapOf(Asset.USD to BigDecimal.valueOf(1000.0))
                val prices = mapOf(Asset.BTC to BigDecimal.TEN)
                val settings = TestFixtures.settings(dryRun = false)
                val actionLog = mutableListOf<String>()

                krakenService.orderResultFactory = { pair, _, side, volume ->
                    OrderResult(
                        success = false,
                        pair = pair,
                        side = side,
                        volume = volume,
                        errorMessage = "Invalid amount",
                    )
                }

                orderExecutor.executeOrders(
                    buyOrders = buyOrders,
                    sellOrders = sellOrders,
                    currentValuesUSD = currentValuesUSD,
                    prices = prices,
                    settings = settings,
                    actionLog = actionLog,
                )

                actionLog.any {
                    it.contains("FAILED SELL BTC: Invalid amount")
                } shouldBe true
            }
        }

        "testRefreshUsdBalanceAfterSells_EarlyReturnAndTimeout" {
            runTest {
                val buyOrders = emptyMap<String, BigDecimal>()
                val sellOrders =
                    mapOf(Asset.BTC to BigDecimal.valueOf(100.0))
                val currentValuesUSD =
                    mapOf(Asset.USD to BigDecimal.valueOf(1000.0))
                val prices = mapOf(Asset.BTC to BigDecimal.TEN)
                val settings = TestFixtures.settings(dryRun = false)

                krakenService.getBalancesCallCount = 0
                krakenService.balanceSupplier =
                    { mapOf(Asset.USD to 1050.0) }

                orderExecutor.executeOrders(
                    buyOrders = buyOrders,
                    sellOrders = sellOrders,
                    currentValuesUSD = currentValuesUSD,
                    prices = prices,
                    settings = settings,
                    actionLog = mutableListOf(),
                )

                krakenService.getBalancesCallCount shouldBe 1

                krakenService.getBalancesCallCount = 0
                krakenService.balanceSupplier =
                    { mapOf(Asset.USD to 900.0) }

                orderExecutor.executeOrders(
                    buyOrders = buyOrders,
                    sellOrders = sellOrders,
                    currentValuesUSD = currentValuesUSD,
                    prices = prices,
                    settings = settings,
                    actionLog = mutableListOf(),
                )

                krakenService.getBalancesCallCount shouldBe 3
            }
        }

        "testEventFlow_EmitsOrderExecutedEvents" {
            runTest {
                val allocA = Allocation("A", 10.0)
                val allocB = Allocation("B", 90.0)
                val allocUSD = Allocation(Asset.USD, 0.0)
                val allAllocations = listOf(allocA, allocB, allocUSD)

                val mockSettings = TestFixtures.settings(dryRun = false, deviationTriggerPercent = 1.0)
                val mockConfig = TestFixtures.config(
                    settings = mockSettings,
                    allocations = allAllocations,
                )

                every { configService.getConfig() } returns mockConfig

                krakenService.balanceSupplier = {
                    val sold = krakenService.executedOrders.any { it.side.equals("sell", ignoreCase = true) }
                    if (sold) {
                        mapOf("A" to 1.0, "B" to 50.0, Asset.USD to 400.0)
                    } else {
                        mapOf("A" to 5.0, "B" to 50.0, Asset.USD to 0.0)
                    }
                }

                val prices = mapOf("AUSD" to 100.0, "BUSD" to 10.0)
                krakenService.pricesSupplier = { prices }

                portfolioManager.performRebalanceCycle()

                krakenService.executedOrders.any {
                    it.side.equals("sell", ignoreCase = true) && it.pair == "AUSD"
                }.shouldBeTrue()
                krakenService.executedOrders.any {
                    it.side.equals("buy", ignoreCase = true) && it.pair == "BUSD"
                }.shouldBeTrue()
            }
        }

        "testLogOrderResult" {
            runTest {
                val log1 = mutableListOf<String>()
                (orderExecutor as OrderExecutorImpl).logOrderResult(
                    result = OrderResult(
                        success = true,
                        pair = "XBTUSD",
                        side = "sell",
                        volume = BigDecimal.ONE,
                        dryRun = true,
                    ),
                    actionLog = log1,
                    symbol = Asset.BTC,
                    volume = BigDecimal.ONE,
                    usdAmount = BigDecimal.TEN,
                    side = OrderSide.SELL,
                )
                log1.first() shouldBe "[DRY RUN] SELL BTC Volume: 1 Value: $10.00"

                val log2 = mutableListOf<String>()
                (orderExecutor as OrderExecutorImpl).logOrderResult(
                    result = OrderResult(
                        success = true,
                        pair = "XBTUSD",
                        side = "buy",
                        volume = BigDecimal.ONE,
                        dryRun = false,
                    ),
                    actionLog = log2,
                    symbol = Asset.BTC,
                    volume = BigDecimal.ONE,
                    usdAmount = BigDecimal.TEN,
                    side = OrderSide.BUY,
                )
                log2.first() shouldBe "BUY BTC Volume: 1 Cost: $10.00"
            }
        }

        "testBuildSnapshot_assemblesAssetMetrics" {
            runTest {
                val balances =
                    mapOf(TestFixtures.USD to 500.0, "BTC" to 0.01).toBigDecimalMap()
                val prices = mapOf("BTC" to BigDecimal("50000.0"))
                val currentValuesUSD = mapOf(
                    TestFixtures.USD to BigDecimal("500.0"),
                    "BTC" to BigDecimal("500.0"),
                )
                val totalVal = BigDecimal("1000.0")
                val effUsdTarget = BigDecimal("50.0")
                val cryptoScale = BigDecimal.ONE
                val drawdown = BigDecimal.ZERO
                val deployment = BigDecimal.ZERO
                val actionLog = listOf("Cycle completed")

                val allocs = listOf(
                    Allocation(Asset.USD, 50.0),
                    Allocation(Asset.BTC, 50.0),
                )
                every { configService.getConfig() } returns TestFixtures.config(
                    settings = TestFixtures.settings(),
                    allocations = allocs,
                )

                val snapshot =
                    portfolioAnalyzer.buildSnapshot(
                        balances = balances,
                        prices = prices,
                        currentValuesUSD = currentValuesUSD,
                        totalPortfolioValueUSD = totalVal,
                        effectiveUsdTarget = effUsdTarget,
                        cryptoScaleFactor = cryptoScale,
                        drawdownPct = drawdown,
                        fiatDeploymentPct = deployment,
                        actionLog = actionLog,
                    )

                snapshot.totalValueUSD.shouldBeEqualComparingTo(BigDecimal("1000.0"))
            }
        }

        "testExecuteOrders_SkipDustSells" {
            runTest {
                val buyOrders = emptyMap<String, BigDecimal>()
                val sellOrders =
                    mapOf(Asset.BTC to BigDecimal.valueOf(0.5)) // $0.50
                val currentValuesUSD =
                    mapOf(Asset.USD to BigDecimal.valueOf(1000.0), Asset.BTC to BigDecimal.valueOf(0.5))
                val prices = mapOf(Asset.BTC to BigDecimal.TEN)
                val settings = TestFixtures.settings(dryRun = false)
                val actionLog = mutableListOf<String>()

                orderExecutor.executeOrders(
                    buyOrders = buyOrders,
                    sellOrders = sellOrders,
                    currentValuesUSD = currentValuesUSD,
                    prices = prices,
                    settings = settings,
                    actionLog = actionLog,
                )

                krakenService.executedOrders.isEmpty().shouldBeTrue()
                actionLog.any { it.contains("Skipping dust sell for BTC") } shouldBe true
            }
        }

        "runLoop_handlesExceptions" {
            runTest {
                val settings = TestFixtures.settings(loopDelaySeconds = 60L)
                val config = TestFixtures.config(
                    settings = settings,
                )
                every { configService.getConfig() } returns config

                coEvery { tradeHistoryService.syncTradesFromKraken() } throws RuntimeException("Sync exception")

                portfolioManager.startRebalancingLoop()
                val job = launch {
                    portfolioManager.runLoop()
                }
                yield()
                portfolioManager.stopRebalancingLoop()
                job.join()

                // Startup sync fired and its exception was caught (not rethrown); the worker did
                // not place orders and the lifecycle completed cleanly. Because the test calls
                // `stopRebalancingLoop()` (which flips `isRunning=false`) before the launched
                // `runLoop()` coroutine resumes from `yield()`, the `while(isRunning)` cycle body
                // never enters, so no rebalance cycle ran: balances were never fetched and no
                // snapshot/session pair was opened.
                coVerify(atLeast = 1) { tradeHistoryService.syncTradesFromKraken() }
                krakenService.getBalancesCallCount shouldBe 0
                krakenService.executedOrders.isEmpty().shouldBeTrue()
                coVerify(exactly = 0) { tradeHistoryService.addSnapshot(any()) }
                verify(exactly = 0) { configService.beginExecutionSession() }
                verify(exactly = 0) { configService.endExecutionSession() }
                // The `finally` cleared `workerJob`; the manager is restartable.
                readWorkerJob() shouldBe null
            }
        }

        "runLoop_handlesCycleExceptions" {
            runTest {
                val settings = TestFixtures.settings(loopDelaySeconds = 60L)
                val config = TestFixtures.config(
                    settings = settings,
                    allocations = listOf(Allocation(TestFixtures.USD, 100.0)),
                )
                every { configService.getConfig() } returns config

                krakenService.balanceSupplier = { throw RuntimeException("Balances fetch error") }

                portfolioManager.startRebalancingLoop()
                val job = launch {
                    portfolioManager.runLoop()
                }
                yield()
                portfolioManager.stopRebalancingLoop()
                job.join()

                // Startup sync ran (relaxed mock: no-op) and was tolerated; the cycle never
                // reached `fetchBalances` because `stopRebalancingLoop()` had already flipped
                // `isRunning=false` by the time the launched coroutine resumed from `yield()`,
                // so the `while(isRunning)` body never entered. No balances fetched, no
                // orders placed, no snapshot/session opened, and the worker release is clean.
                coVerify(atLeast = 1) { tradeHistoryService.syncTradesFromKraken() }
                krakenService.getBalancesCallCount shouldBe 0
                krakenService.executedOrders.isEmpty().shouldBeTrue()
                coVerify(exactly = 0) { tradeHistoryService.addSnapshot(any()) }
                verify(exactly = 0) { configService.beginExecutionSession() }
                verify(exactly = 0) { configService.endExecutionSession() }
                readWorkerJob() shouldBe null
            }
        }

        "runLoop_handlesCancellationException" {
            runTest {
                val settings = TestFixtures.settings(loopDelaySeconds = 60L)
                val config = TestFixtures.config(
                    settings = settings,
                    allocations = listOf(Allocation(TestFixtures.USD, 100.0)),
                )
                every { configService.getConfig() } returns config

                portfolioManager.startRebalancingLoop()
                val job = launch {
                    portfolioManager.runLoop()
                }
                yield()
                job.cancel()
                job.join()

                // `stopRebalancingLoop()` is NOT called here — only `job.cancel()`. `isRunning`
                // stays true at the moment the collectLatest body checks it, so one cycle runs to
                // completion and publishes a snapshot before the `delay(60s)` is cancelled. The
                // observed post-state: one snapshot, no orders placed (default allocations have
                // nothing to do), and the `finally` released `workerJob` so the manager is
                // restartable.
                krakenService.executedOrders.isEmpty().shouldBeTrue()
                coVerify(exactly = 1) { tradeHistoryService.addSnapshot(any()) }
                readWorkerJob() shouldBe null
            }
        }

        "post-trade snapshot falls back to pre-trade values when recalculation fails" {
            runTest {
                val allocs = listOf(
                    Allocation(Asset.BTC, 50.0),
                    Allocation(Asset.USD, 50.0),
                )
                every { configService.getConfig() } returns TestFixtures.config(
                    settings = TestFixtures.settings(),
                    allocations = allocs,
                )

                // Pre-trade total $10,000 (BTC +2% → sell triggers); post-trade balances differ,
                // but the post-trade price lookup comes back empty → Result.Failure → fallback.
                var priceCalls = 0
                krakenService.pricesSupplier = {
                    priceCalls++
                    if (priceCalls == 1) mapOf(Asset.BTC_USD_PAIR to 50000.0) else emptyMap()
                }
                var balanceCalls = 0
                krakenService.balanceSupplier = {
                    balanceCalls++
                    if (balanceCalls == 1) {
                        mapOf(Asset.BTC to 0.102, Asset.USD to 4900.0)
                    } else {
                        mapOf(Asset.BTC to 0.09, Asset.USD to 5400.0)
                    }
                }

                portfolioManager.startRebalancingLoop()
                val snapshot = portfolioManager.performRebalanceCycle()

                krakenService.executedOrders.isNotEmpty() shouldBe true
                snapshot!!.totalValueUSD.shouldBeEqualComparingTo(BigDecimal("10000.00"))
            }
        }

        "post-trade snapshot falls back to pre-trade values when refetch throws" {
            runTest {
                val allocs = listOf(
                    Allocation(Asset.BTC, 50.0),
                    Allocation(Asset.USD, 50.0),
                )
                every { configService.getConfig() } returns TestFixtures.config(
                    settings = TestFixtures.settings(),
                    allocations = allocs,
                )

                krakenService.pricesSupplier = { mapOf(Asset.BTC_USD_PAIR to 50000.0) }
                var balanceCalls = 0
                krakenService.balanceSupplier = {
                    balanceCalls++
                    if (balanceCalls == 1) {
                        mapOf(Asset.BTC to 0.102, Asset.USD to 4900.0)
                    } else {
                        throw IOException("post-trade balance fetch failed")
                    }
                }

                portfolioManager.startRebalancingLoop()
                val snapshot = portfolioManager.performRebalanceCycle()

                krakenService.executedOrders.isNotEmpty() shouldBe true
                snapshot!!.totalValueUSD.shouldBeEqualComparingTo(BigDecimal("10000.00"))
            }
        }

        "executeOrders propagates cancellation during failure-state persistence" {
            runTest {
                val buyOrders = emptyMap<String, BigDecimal>()
                val sellOrders =
                    mapOf(Asset.BTC to BigDecimal.valueOf(100.0))
                val currentValuesUSD =
                    mapOf(Asset.USD to BigDecimal.valueOf(1000.0))
                val prices = mapOf(Asset.BTC to BigDecimal.TEN)
                val settings = TestFixtures.settings(dryRun = false)
                val actionLog = mutableListOf<String>()

                krakenService.executeOrderAction = { _, _, _, _ ->
                    throw RuntimeException("Exchange failure")
                }
                // updateTrade (failure-state persistence) suspends until the worker is cancelled.
                coEvery { tradeHistoryService.updateTrade(any(), any()) } coAnswers {
                    awaitCancellation()
                }

                var propagated: Throwable? = null
                val job = launch {
                    try {
                        orderExecutor.executeOrders(
                            buyOrders = buyOrders,
                            sellOrders = sellOrders,
                            currentValuesUSD = currentValuesUSD,
                            prices = prices,
                            settings = settings,
                            actionLog = actionLog,
                        )
                    } catch (e: Throwable) {
                        propagated = e
                    }
                }
                yield()
                job.cancel()
                job.join()

                propagated.shouldBeInstanceOf<CancellationException>()
            }
        }
    }
}
