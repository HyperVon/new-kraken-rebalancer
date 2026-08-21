package com.gemini.krakenbot.service

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.domain.OrderResult
import com.gemini.krakenbot.domain.RebalanceEvent
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.OrderSide
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.PortfolioStats
import com.gemini.krakenbot.service.impl.OrderExecutorImpl
import com.gemini.krakenbot.service.impl.PortfolioManagerImpl
import com.gemini.krakenbot.toBigDecimalMap
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import java.io.IOException
import java.math.BigDecimal
import kotlin.time.Duration.Companion.milliseconds

class PortfolioManagerEdgeCasesTest : PortfolioManagerEdgeCasesTestBase() {

    init {
        "runLoop_respectsLoopDelay" {
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
                delay(10.milliseconds)
                krakenService.getBalancesCallCount shouldBe 1

                portfolioManager.stopRebalancingLoop()
                job.cancel()
                krakenService.getBalancesCallCount shouldBe 1
            }
        }

        "performRebalanceCycle_NullBalances" {
            runTest {
                krakenService.balanceSupplier = { emptyMap() }

                every { configService.getConfig() } returns singleAllocConfig()

                portfolioManager.startRebalancingLoop()
                portfolioManager.performRebalanceCycle()

                krakenService.getBalancesCallCount shouldBe 1
            }
        }

        "performRebalanceCycle_PriceNotFoundAbort" {
            runTest {
                krakenService.balanceSupplier =
                    { mapOf(Asset.BTC to 1.0) }
                krakenService.pricesSupplier = { emptyMap() }

                every { configService.getConfig() } returns singleAllocConfig(Asset.BTC)

                portfolioManager.startRebalancingLoop()
                portfolioManager.performRebalanceCycle()

                coVerify(exactly = 0) { tradeHistoryService.addSnapshot(any()) }
            }
        }

        "testDistributeFiatCorrection_NoCounterbalancingAssets" {
            runTest {
                val allDevs = mapOf(
                    Asset.USD to BigDecimal("100.0"),
                    "A" to BigDecimal("10.0"),
                )
                val buyOrders = mutableMapOf<String, BigDecimal>()
                val sellOrders = mutableMapOf<String, BigDecimal>()

                portfolioAnalyzer.distributeFiatCorrection(
                    usdDev = BigDecimal("100.0"),
                    allDevs = allDevs,
                    buyOrders = buyOrders,
                    sellOrders = sellOrders,
                    events = mutableListOf<RebalanceEvent>(),
                )

                buyOrders.isEmpty().shouldBeTrue()
                sellOrders.isEmpty().shouldBeTrue()
            }
        }

        "testFiatDeploymentRatioExceedsOne" {
            runTest {
                coEvery {
                    portfolioStatsRepository.load()
                } returns PortfolioStats(
                    BigDecimal("2000.0"),
                )

                val allocs = listOf(
                    Allocation("A", 50.0),
                    Allocation(Asset.USD, 50.0),
                )
                every { configService.getConfig() } returns
                    TestFixtures.config(
                        settings = TestFixtures.settings(fiatMaxDrawdown = 50.0),
                        allocations = allocs,
                    )

                krakenService.balanceSupplier =
                    { mapOf("A" to 2.5, Asset.USD to 250.0) }
                krakenService.pricesSupplier = { mapOf("AUSD" to 100.0) }

                portfolioManager.startRebalancingLoop()
                portfolioManager.performRebalanceCycle()

                val captor = slot<PortfolioSnapshot>()
                coVerify { tradeHistoryService.addSnapshot(capture(captor)) }
                captor.captured.fiatDeploymentPercent.shouldBeEqualComparingTo(BigDecimal("100.0"))
            }
        }

        "testResolvePriceFromTicker_ExplicitPairAndFallback" {
            runTest {
                val rawPrices = mapOf("ETHEUR" to 3000.0, "ETHUSD" to 3100.0).toBigDecimalMap()

                val priceEth = portfolioAnalyzer.resolvePriceFromTicker(
                    Asset.ETH,
                    rawPrices,
                )
                priceEth.shouldBeEqualComparingTo(BigDecimal("3100.0"))

                val priceMissing =
                    portfolioAnalyzer
                        .resolvePriceFromTicker("LTC", rawPrices)
                priceMissing.shouldBeEqualComparingTo(BigDecimal.ZERO)
            }
        }

        "testExecuteOrders_ZeroPriceContinues" {
            runTest {
                val buyOrders = mapOf(Asset.ETH to BigDecimal.TEN)
                val sellOrders = mapOf(Asset.BTC to BigDecimal.TEN)
                val currentValuesUSD =
                    mapOf(Asset.USD to BigDecimal.valueOf(1000.0))
                val prices = emptyMap<String, BigDecimal>()
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
            }
        }

        "testExecuteOrders_UpdateCashException" {
            runTest {
                val buyOrders = mapOf(Asset.ETH to BigDecimal.TEN)
                val sellOrders =
                    mapOf(Asset.BTC to BigDecimal.valueOf(100.0))
                val currentValuesUSD =
                    mapOf(Asset.USD to BigDecimal.valueOf(1000.0))
                val prices = mapOf(
                    Asset.BTC to BigDecimal.TEN,
                    Asset.ETH to BigDecimal.valueOf(5),
                )
                val settings = TestFixtures.settings(dryRun = false)
                val actionLog = mutableListOf<String>()

                krakenService.balanceSupplier =
                    { throw RuntimeException("balances api error") }

                orderExecutor.executeOrders(
                    buyOrders = buyOrders,
                    sellOrders = sellOrders,
                    currentValuesUSD = currentValuesUSD,
                    prices = prices,
                    settings = settings,
                    actionLog = actionLog,
                )

                // Fail-closed: no positive observed USD → sell only
                krakenService.executedOrders.size shouldBe 1
                krakenService.executedOrders[0].pair shouldBe Asset.BTC_USD_PAIR
                krakenService.executedOrders[0].side shouldBe OrderSide.SELL.apiValue
                krakenService.executedOrders[0].volume.shouldBeEqualComparingTo(BigDecimal.TEN)
            }
        }

        "testExecuteOrders_UpdateBalancesNullOrEmpty" {
            runTest {
                val buyOrders = mapOf(Asset.ETH to BigDecimal.TEN)
                val sellOrders =
                    mapOf(Asset.BTC to BigDecimal.valueOf(100.0))
                val currentValuesUSD =
                    mapOf(Asset.USD to BigDecimal.valueOf(1000.0))
                val prices = mapOf(
                    Asset.BTC to BigDecimal.TEN,
                    Asset.ETH to BigDecimal.valueOf(5),
                )
                val settings = TestFixtures.settings(dryRun = false)
                val actionLog = mutableListOf<String>()

                krakenService.balanceSupplier = { emptyMap() }

                orderExecutor.executeOrders(
                    buyOrders = buyOrders,
                    sellOrders = sellOrders,
                    currentValuesUSD = currentValuesUSD,
                    prices = prices,
                    settings = settings,
                    actionLog = actionLog,
                )

                krakenService.executedOrders.size shouldBe 1
                krakenService.executedOrders[0].pair shouldBe Asset.BTC_USD_PAIR
                krakenService.executedOrders[0].side shouldBe OrderSide.SELL.apiValue
                krakenService.executedOrders[0].volume.shouldBeEqualComparingTo(BigDecimal.TEN)
            }
        }

        "testUpdateAthAndCalculateDrawdown_NewAth" {
            runTest {
                coEvery { portfolioStatsRepository.load() } returns PortfolioStats(
                    BigDecimal("1000.0"),
                )
                val drawdown = portfolioAnalyzer.updateAthAndCalculateDrawdown(
                    BigDecimal("1500.0"),
                )
                drawdown.shouldBeEqualComparingTo(BigDecimal.ZERO)
                coVerify {
                    portfolioStatsRepository.save(
                        match {
                            it.allTimeHigh.compareTo(BigDecimal("1500.0")) == 0
                        },
                    )
                }
            }
        }

        "testExecuteOrders_SkipDustBuys" {
            runTest {
                val buyOrders =
                    mapOf(Asset.ETH to BigDecimal.valueOf(0.5))
                val sellOrders = emptyMap<String, BigDecimal>()
                val currentValuesUSD =
                    mapOf(Asset.USD to BigDecimal.valueOf(1000.0))
                val prices = mapOf(Asset.ETH to BigDecimal.valueOf(5))
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
            }
        }

        "testAnalyzeDeviations_UsdTriggeredButOrdersNotEmpty" {
            runTest {
                val currentValuesUSD = mapOf(
                    Asset.USD to BigDecimal.valueOf(1100.0),
                    Asset.BTC to BigDecimal.valueOf(900.0),
                )
                val totalVal = BigDecimal.valueOf(2000.0)
                val effUsdTarget = BigDecimal.valueOf(50.0)
                val cryptoScale = BigDecimal.ONE
                val buyOrders = mutableMapOf<String, BigDecimal>()
                val sellOrders = mutableMapOf<String, BigDecimal>()
                val events = mutableListOf<RebalanceEvent>()

                val allocs = listOf(
                    Allocation(Asset.USD, 50.0),
                    Allocation(Asset.BTC, 50.0),
                )
                val settings = TestFixtures.settings()
                every { configService.getConfig() } returns TestFixtures.config(
                    settings = settings,
                    allocations = allocs,
                )

                val result = portfolioAnalyzer.analyzeDeviations(
                    totalPortfolioValueUSD = totalVal,
                    currentValuesUSD = currentValuesUSD,
                    effectiveUsdTarget = effUsdTarget,
                    cryptoScaleFactor = cryptoScale,
                )
                buyOrders.putAll(result.buyOrders)
                sellOrders.putAll(result.sellOrders)
                events.addAll(result.events)

                buyOrders.isEmpty() shouldBe false
            }
        }

        "testPerformRebalanceCycle_TradeHistorySaveIOException" {
            runTest {
                krakenService.balanceSupplier =
                    { mapOf(Asset.USD to 1000.0) }
                every { configService.getConfig() } returns singleAllocConfig()

                coEvery {
                    tradeHistoryService.addSnapshot(any())
                } throws IOException(
                    "Disk full",
                )

                portfolioManager.startRebalancingLoop()
                portfolioManager.performRebalanceCycle()

                krakenService.getBalancesCallCount shouldBe 1
            }
        }

        "testResolvePriceFromTicker_FallbackBranches" {
            runTest {
                val rawPrices = mapOf(
                    "BTCEUR" to 60000.0,
                    "XBTUSD" to 61000.0,
                ).toBigDecimalMap()
                val price = portfolioAnalyzer.resolvePriceFromTicker(
                    Asset.BTC,
                    rawPrices,
                )
                price.shouldBeEqualComparingTo(BigDecimal("61000.0"))

                val rawPricesOnlyEur = mapOf(
                    "XBTEUR" to 55000.0,
                ).toBigDecimalMap()
                val priceEurOnly = portfolioAnalyzer.resolvePriceFromTicker(
                    symbol = Asset.BTC,
                    rawPrices = rawPricesOnlyEur,
                )
                priceEurOnly.shouldBeEqualComparingTo(BigDecimal.ZERO)
            }
        }

        "testResolvePriceFromTicker_RejectsSubstringCollisions" {
            runTest {
                // Substring keys must not match; only exact aliases (ETHUSD / XETHZUSD).
                val colliding = linkedMapOf(
                    "SOMETHINGETHUSD" to 1111.0,
                    "OTHERETHUSD" to 2222.0,
                ).toBigDecimalMap()
                portfolioAnalyzer.resolvePriceFromTicker(Asset.ETH, colliding)
                    .shouldBeEqualComparingTo(BigDecimal.ZERO)

                val exactAlias = linkedMapOf(
                    "SOMETHINGETHUSD" to 1111.0,
                    "XETHZUSD" to 3333.0,
                ).toBigDecimalMap()
                portfolioAnalyzer.resolvePriceFromTicker(Asset.ETH, exactAlias)
                    .shouldBeEqualComparingTo(BigDecimal("3333.0"))
            }
        }

        "testCalculatePortfolioValues_PriceNotFoundAbort" {
            runTest {
                val balances =
                    mapOf(Asset.USD to 1000.0, Asset.BTC to 1.0).toBigDecimalMap()
                val prices = mapOf(Asset.USD to BigDecimal.ONE)

                val allocs = listOf(
                    Allocation(Asset.USD, 50.0),
                    Allocation(Asset.BTC, 50.0),
                )
                every { configService.getConfig() } returns TestFixtures.config(
                    settings = TestFixtures.settings(),
                    allocations = allocs,
                )

                val result = portfolioAnalyzer.calculatePortfolioValues(
                    balances,
                    prices,
                )
                result.fold(onSuccess = { false }, onFailure = { true }) shouldBe true
            }
        }

        "testCalculatePortfolioValues_ZeroTickerPriceAborts" {
            runTest {
                val balances =
                    mapOf(Asset.USD to 1000.0, Asset.BTC to 1.0).toBigDecimalMap()
                val prices =
                    mapOf(
                        Asset.USD to BigDecimal.ONE,
                        Asset.BTC to BigDecimal.ZERO,
                    )

                val allocs = listOf(
                    Allocation(Asset.USD, 50.0),
                    Allocation(Asset.BTC, 50.0),
                )
                every { configService.getConfig() } returns TestFixtures.config(
                    settings = TestFixtures.settings(),
                    allocations = allocs,
                )

                val result = portfolioAnalyzer.calculatePortfolioValues(
                    balances,
                    prices,
                )
                result.fold(onSuccess = { false }, onFailure = { true }) shouldBe true
            }
        }

        "testResolveBalance_FallbackChain" {
            runTest {
                portfolioAnalyzer.resolveBalance(
                    Asset.BTC,
                    mapOf(Asset.BTC to 1.1).toBigDecimalMap(),
                ).shouldBeEqualComparingTo(BigDecimal("1.1"))
                portfolioAnalyzer.resolveBalance(
                    Asset.BTC,
                    mapOf("XBTC" to 1.2).toBigDecimalMap(),
                ).shouldBeEqualComparingTo(BigDecimal("1.2"))
                portfolioAnalyzer.resolveBalance(
                    Asset.USD,
                    mapOf("ZUSD" to 1.3).toBigDecimalMap(),
                ).shouldBeEqualComparingTo(BigDecimal("1.3"))
                portfolioAnalyzer.resolveBalance(
                    Asset.BTC,
                    mapOf(Asset.XBT to 1.4).toBigDecimalMap(),
                ).shouldBeEqualComparingTo(BigDecimal("1.4"))
                portfolioAnalyzer.resolveBalance(
                    Asset.BTC,
                    mapOf("XXBT" to 1.5).toBigDecimalMap(),
                ).shouldBeEqualComparingTo(BigDecimal("1.5"))
                portfolioAnalyzer.resolveBalance(
                    Asset.BTC,
                    mapOf(Asset.ETH to 1.6).toBigDecimalMap(),
                ).shouldBeEqualComparingTo(BigDecimal.ZERO)
            }
        }

        "testUpdateAthAndCalculateDrawdown_NegativeAth" {
            runTest {
                coEvery { portfolioStatsRepository.load() } returns PortfolioStats(
                    BigDecimal("-500.0"),
                )
                val drawdown =
                    portfolioAnalyzer
                        .updateAthAndCalculateDrawdown(
                            BigDecimal("1000.0"),
                        )
                drawdown.shouldBeEqualComparingTo(BigDecimal.ZERO)
                coVerify {
                    portfolioStatsRepository.save(
                        match {
                            it.allTimeHigh.compareTo(BigDecimal("1000.0")) == 0
                        },
                    )
                }
            }
        }

        "testUpdateAthAndCalculateDrawdown_StatsSaveIOException" {
            runTest {
                coEvery {
                    portfolioStatsRepository.load()
                } returns PortfolioStats(
                    BigDecimal("1000.0"),
                )
                coEvery {
                    portfolioStatsRepository.save(any())
                } throws IOException(
                    "Save failed",
                )

                val drawdown = portfolioAnalyzer.updateAthAndCalculateDrawdown(BigDecimal("800.0"))

                drawdown.shouldBeEqualComparingTo(BigDecimal("20.0000"))
                coVerify {
                    portfolioStatsRepository.save(
                        match { it.allTimeHigh.compareTo(BigDecimal("1000.0")) == 0 },
                    )
                }
            }
        }

        "testUpdateAthAndCalculateDrawdown_NewAthSaveFailure" {
            runTest {
                coEvery { portfolioStatsRepository.load() } returns PortfolioStats(
                    BigDecimal("1000.0"),
                )
                coEvery { portfolioStatsRepository.save(any()) } throws IOException("Save failed")

                val drawdown = portfolioAnalyzer.updateAthAndCalculateDrawdown(BigDecimal("1500.0"))

                drawdown.shouldBeEqualComparingTo(BigDecimal.ZERO)
                coVerify {
                    portfolioStatsRepository.save(
                        match { it.allTimeHigh.compareTo(BigDecimal("1500.0")) == 0 },
                    )
                }
            }
        }

        "testUpdateAthAndCalculateDrawdown_SaveCancellationPropagates" {
            runTest {
                coEvery { portfolioStatsRepository.load() } returns PortfolioStats(BigDecimal("1000.0"))
                coEvery { portfolioStatsRepository.save(any()) } throws CancellationException("cancelled")

                shouldThrow<CancellationException> {
                    portfolioAnalyzer.updateAthAndCalculateDrawdown(BigDecimal("1500.0"))
                }
            }
        }
    }

    private fun singleAllocConfig(symbol: String = Asset.USD, settings: Settings = TestFixtures.settings()): AppConfig =
        TestFixtures.config(
            settings = settings,
            allocations = listOf(
                Allocation(
                    symbol = symbol,
                    targetPercent = 100.0,
                ),
            ),
        )
}
