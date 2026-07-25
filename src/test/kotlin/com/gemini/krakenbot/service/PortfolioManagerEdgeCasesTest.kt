package com.gemini.krakenbot.service

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.OrderResult
import com.gemini.krakenbot.model.OrderSide
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.PortfolioStats
import com.gemini.krakenbot.service.impl.OrderExecutorImpl
import com.gemini.krakenbot.service.impl.PortfolioManagerImpl
import com.gemini.krakenbot.toBigDecimalMap
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import io.mockk.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import java.io.IOException
import java.math.BigDecimal
import kotlin.time.Duration.Companion.milliseconds

class PortfolioManagerEdgeCasesTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    private lateinit var fixture: PortfolioManagerTestFixture
    private val krakenService get() = fixture.krakenService
    private val configService get() = fixture.configService
    private val tradeHistoryService get() = fixture.tradeHistoryService
    private val portfolioStatsRepository get() = fixture.portfolioStatsRepository
    private val portfolioAnalyzer get() = fixture.portfolioAnalyzer
    private val orderExecutor get() = fixture.orderExecutor
    private val portfolioManager: PortfolioManagerImpl get() = fixture.portfolioManager

    init {
        beforeTest {
            fixture = createPortfolioManagerTestFixture()
            coEvery { portfolioStatsRepository.load() } returns PortfolioStats(
                BigDecimal.ZERO,
            )
            every { configService.watchConfigChanges() } answers {
                flowOf(configService.getConfig().settings)
            }
        }

        "runLoop_respectsLoopDelay" {
            runTest {
                val settings = Settings(
                    loopDelaySeconds = 60L,
                    deviationTriggerPercent = 2.0,
                    dustThresholdUSD = 1.0,
                    dryRun = true,
                    fiatMaxDrawdown = 0.0,
                    fiatDeploymentExponent = 1.0,
                )
                val config = AppConfig(
                    kraken = KrakenCredentials(apiKey = "k", privateKey = "s"),
                    settings = settings,
                    allocations = emptyList(),
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

                val allocs = listOf(
                    Allocation(
                        symbol = Asset.USD,
                        targetPercent = 100.0,
                    ),
                )
                val settings = Settings(
                    loopDelaySeconds = 0L,
                    deviationTriggerPercent = 2.0,
                    dustThresholdUSD = 1.0,
                    dryRun = true,
                    fiatMaxDrawdown = 0.0,
                    fiatDeploymentExponent = 1.0,
                )
                val config =
                    AppConfig(
                        kraken = KrakenCredentials(
                            apiKey = "k",
                            privateKey = "s",
                        ),
                        settings = settings,
                        allocations = allocs,
                    )
                every { configService.getConfig() } returns config

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

                val allocs = listOf(
                    Allocation(
                        symbol = Asset.BTC,
                        targetPercent = 100.0,
                    ),
                )
                val settings = Settings(
                    loopDelaySeconds = 0L,
                    deviationTriggerPercent = 2.0,
                    dustThresholdUSD = 1.0,
                    dryRun = true,
                    fiatMaxDrawdown = 0.0,
                    fiatDeploymentExponent = 1.0,
                )
                val config =
                    AppConfig(
                        kraken = KrakenCredentials(
                            apiKey = "k",
                            privateKey = "s",
                        ),
                        settings = settings,
                        allocations = allocs,
                    )
                every { configService.getConfig() } returns config

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
                    actionLog = mutableListOf(),
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
                    AppConfig(
                        kraken = KrakenCredentials(
                            apiKey = "k",
                            privateKey = "s",
                        ),
                        settings = Settings(
                            loopDelaySeconds = 0L,
                            deviationTriggerPercent = 2.0,
                            dustThresholdUSD = 1.0,
                            dryRun = true,
                            fiatMaxDrawdown = 50.0,
                            fiatDeploymentExponent = 1.0,
                        ),
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
                priceMissing.compareTo(BigDecimal.ZERO) shouldBe 0
            }
        }

        "testExecuteOrders_ZeroPriceContinues" {
            runTest {
                val buyOrders = mapOf(Asset.ETH to BigDecimal.TEN)
                val sellOrders = mapOf(Asset.BTC to BigDecimal.TEN)
                val currentValuesUSD =
                    mapOf(Asset.USD to BigDecimal.valueOf(1000.0))
                val prices = emptyMap<String, BigDecimal>()
                val settings = Settings(
                    loopDelaySeconds = 0L,
                    deviationTriggerPercent = 2.0,
                    dustThresholdUSD = 1.0,
                    dryRun = false,
                    fiatMaxDrawdown = 0.0,
                    fiatDeploymentExponent = 1.0,
                )
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
                val settings = Settings(
                    loopDelaySeconds = 0L,
                    deviationTriggerPercent = 2.0,
                    dustThresholdUSD = 1.0,
                    dryRun = false,
                    fiatMaxDrawdown = 0.0,
                    fiatDeploymentExponent = 1.0,
                )
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
                krakenService.executedOrders[0].volume.compareTo(BigDecimal.TEN) shouldBe 0
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
                val settings = Settings(
                    loopDelaySeconds = 0L,
                    deviationTriggerPercent = 2.0,
                    dustThresholdUSD = 1.0,
                    dryRun = false,
                    fiatMaxDrawdown = 0.0,
                    fiatDeploymentExponent = 1.0,
                )
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
                krakenService.executedOrders[0].volume.compareTo(BigDecimal.TEN) shouldBe 0
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
                drawdown.compareTo(BigDecimal.ZERO) shouldBe 0
                coVerify { portfolioStatsRepository.save(any()) }
            }
        }

        "testExecuteOrders_UpdateBalancesEmptyUsdOrNull" {
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
                val settings = Settings(
                    loopDelaySeconds = 0L,
                    deviationTriggerPercent = 2.0,
                    dustThresholdUSD = 1.0,
                    dryRun = false,
                    fiatMaxDrawdown = 0.0,
                    fiatDeploymentExponent = 1.0,
                )
                val actionLog = mutableListOf<String>()

                krakenService.balanceSupplier =
                    { mapOf(Asset.BTC to 1.0, "ZUSD" to 0.0) }

                orderExecutor.executeOrders(
                    buyOrders = buyOrders,
                    sellOrders = sellOrders,
                    currentValuesUSD = currentValuesUSD,
                    prices = prices,
                    settings = settings,
                    actionLog = actionLog,
                )

                krakenService.executedOrders.size shouldBe 1
                krakenService.executedOrders[0].pair shouldBe "XBTUSD"
                krakenService.executedOrders[0].side shouldBe "sell"
                krakenService.executedOrders[0].volume.compareTo(BigDecimal.TEN) shouldBe 0
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
                val settings = Settings(
                    loopDelaySeconds = 0L,
                    deviationTriggerPercent = 2.0,
                    dustThresholdUSD = 1.0,
                    dryRun = false,
                    fiatMaxDrawdown = 0.0,
                    fiatDeploymentExponent = 1.0,
                )
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
                val actionLog = mutableListOf<String>()

                val allocs = listOf(
                    Allocation(Asset.USD, 50.0),
                    Allocation(Asset.BTC, 50.0),
                )
                val settings = Settings(
                    loopDelaySeconds = 0L,
                    deviationTriggerPercent = 2.0,
                    dustThresholdUSD = 1.0,
                    dryRun = true,
                    fiatMaxDrawdown = 0.0,
                    fiatDeploymentExponent = 1.0,
                )
                every { configService.getConfig() } returns AppConfig(
                    kraken = KrakenCredentials(apiKey = "k", privateKey = "s"),
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
                actionLog.addAll(result.actionLog)

                buyOrders.isEmpty() shouldBe false
            }
        }

        "testPerformRebalanceCycle_TradeHistorySaveIOException" {
            runTest {
                krakenService.balanceSupplier =
                    { mapOf(Asset.USD to 1000.0) }
                val allocs = listOf(
                    Allocation(
                        symbol = Asset.USD,
                        targetPercent = 100.0,
                    ),
                )
                val settings = Settings(
                    loopDelaySeconds = 0L,
                    deviationTriggerPercent = 2.0,
                    dustThresholdUSD = 1.0,
                    dryRun = true,
                    fiatMaxDrawdown = 0.0,
                    fiatDeploymentExponent = 1.0,
                )
                val config =
                    AppConfig(
                        kraken = KrakenCredentials(
                            apiKey = "k",
                            privateKey = "s",
                        ),
                        settings = settings,
                        allocations = allocs,
                    )
                every { configService.getConfig() } returns config

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
                priceEurOnly.compareTo(BigDecimal.ZERO) shouldBe 0
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
                every { configService.getConfig() } returns AppConfig(
                    kraken = KrakenCredentials(apiKey = "k", privateKey = "s"),
                    settings = Settings(
                        loopDelaySeconds = 0L,
                        deviationTriggerPercent = 2.0,
                        dustThresholdUSD = 1.0,
                        dryRun = true,
                        fiatMaxDrawdown = 0.0,
                        fiatDeploymentExponent = 1.0,
                    ),
                    allocations = allocs,
                )

                val result = portfolioAnalyzer.calculatePortfolioValues(
                    balances,
                    prices,
                )
                (result.exceptionOrNull() != null) shouldBe true
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
                every { configService.getConfig() } returns AppConfig(
                    kraken = KrakenCredentials(apiKey = "k", privateKey = "s"),
                    settings = Settings(
                        loopDelaySeconds = 0L,
                        deviationTriggerPercent = 2.0,
                        dustThresholdUSD = 1.0,
                        dryRun = true,
                        fiatMaxDrawdown = 0.0,
                        fiatDeploymentExponent = 1.0,
                    ),
                    allocations = allocs,
                )

                val result = portfolioAnalyzer.calculatePortfolioValues(
                    balances,
                    prices,
                )
                (result.exceptionOrNull() != null) shouldBe true
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
                drawdown.compareTo(BigDecimal.ZERO) shouldBe 0
                coVerify {
                    portfolioStatsRepository.save(
                        match {
                            it.allTimeHigh.compareTo(BigDecimal("1000.0")) == 0
                        },
                    )
                }
            }
        }

        "testUpdateAthAndCalculateDrawdown_NullAth" {
            runTest {
                coEvery { portfolioStatsRepository.load() } returns PortfolioStats(
                    BigDecimal.ZERO,
                )
                val drawdown =
                    portfolioAnalyzer.updateAthAndCalculateDrawdown(
                        BigDecimal("1200.0"),
                    )
                drawdown.compareTo(BigDecimal.ZERO) shouldBe 0
                coVerify {
                    portfolioStatsRepository.save(
                        match {
                            it.allTimeHigh.compareTo(BigDecimal("1200.0")) == 0
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

                val drawdown =
                    portfolioAnalyzer.updateAthAndCalculateDrawdown(
                        BigDecimal("800.0"),
                    )
                drawdown.shouldBeEqualComparingTo(BigDecimal("20.0"))
            }
        }

        "testUpdateAthAndCalculateDrawdown_NewAthSaveFailure" {
            runTest {
                coEvery { portfolioStatsRepository.load() } returns PortfolioStats(
                    BigDecimal("1000.0"),
                )
                coEvery { portfolioStatsRepository.save(any()) } throws IOException("Save failed")

                val drawdown = portfolioAnalyzer.updateAthAndCalculateDrawdown(
                    BigDecimal("1500.0"),
                )
                drawdown.shouldBeEqualComparingTo(BigDecimal.ZERO)
            }
        }

        "testPerformRebalanceCycle_DeviationExactTriggerGeneratesOrders" {
            runTest {
                val allocs = listOf(
                    Allocation(Asset.BTC, 50.0),
                    Allocation(Asset.USD, 50.0),
                )
                every { configService.getConfig() } returns AppConfig(
                    kraken = KrakenCredentials(apiKey = "k", privateKey = "s"),
                    settings = Settings(
                        loopDelaySeconds = 0L,
                        deviationTriggerPercent = 2.0,
                        dustThresholdUSD = 1.0,
                        dryRun = false,
                        fiatMaxDrawdown = 0.0,
                        fiatDeploymentExponent = 1.0,
                    ),
                    allocations = allocs,
                )

                krakenService.pricesSupplier = { mapOf(Asset.BTC_USD_PAIR to 50000.0) }
                // Total $10,000: BTC $5,100 (+2.0% vs $5,000 target), USD $4,900
                krakenService.balanceSupplier = {
                    mapOf(Asset.BTC to 0.102, Asset.USD to 4900.0)
                }

                portfolioManager.startRebalancingLoop()
                portfolioManager.performRebalanceCycle()

                krakenService.executedOrders.isNotEmpty() shouldBe true
                krakenService.executedOrders.any { it.pair == Asset.BTC_USD_PAIR } shouldBe true

                val captor = slot<PortfolioSnapshot>()
                coVerify { tradeHistoryService.addSnapshot(capture(captor)) }
                captor.captured.actions.any { it.contains("Deviation: BTC") } shouldBe true
            }
        }

        "testPerformRebalanceCycle_DeviationJustBelowTriggerNoCryptoOrders" {
            runTest {
                val allocs = listOf(
                    Allocation(Asset.BTC, 50.0),
                    Allocation(Asset.USD, 50.0),
                )
                every { configService.getConfig() } returns AppConfig(
                    kraken = KrakenCredentials(apiKey = "k", privateKey = "s"),
                    settings = Settings(
                        loopDelaySeconds = 0L,
                        deviationTriggerPercent = 2.0,
                        dustThresholdUSD = 1.0,
                        dryRun = false,
                        fiatMaxDrawdown = 0.0,
                        fiatDeploymentExponent = 1.0,
                    ),
                    allocations = allocs,
                )

                krakenService.pricesSupplier = { mapOf(Asset.BTC_USD_PAIR to 50000.0) }
                // Total $10,000: BTC $5,099.50 (+1.99% vs $5,000 target), USD $4,900.50
                krakenService.balanceSupplier = {
                    mapOf(Asset.BTC to 0.10199, Asset.USD to 4900.50)
                }

                portfolioManager.startRebalancingLoop()
                portfolioManager.performRebalanceCycle()

                krakenService.executedOrders.isEmpty() shouldBe true
            }
        }

        "testAnalyzeDeviations_MissingSymbolInCurrentValues" {
            runTest {
                val totalVal = BigDecimal.valueOf(1000.0)
                val currentValuesUSD =
                    mapOf(Asset.USD to BigDecimal.valueOf(1000.0))
                val effUsdTarget = BigDecimal.valueOf(50.0)
                val cryptoScale = BigDecimal.valueOf(0.5)
                val buyOrders = mutableMapOf<String, BigDecimal>()
                val sellOrders = mutableMapOf<String, BigDecimal>()
                val actionLog = mutableListOf<String>()

                val allocs = listOf(
                    Allocation(Asset.USD, 50.0),
                    Allocation(Asset.BTC, 50.0),
                )
                every { configService.getConfig() } returns AppConfig(
                    kraken = KrakenCredentials(apiKey = "k", privateKey = "s"),
                    settings = Settings(
                        loopDelaySeconds = 0L,
                        deviationTriggerPercent = 2.0,
                        dustThresholdUSD = 1.0,
                        dryRun = true,
                        fiatMaxDrawdown = 0.0,
                        fiatDeploymentExponent = 1.0,
                    ),
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
                actionLog.addAll(result.actionLog)
                buyOrders[Asset.BTC]?.compareTo(
                    BigDecimal("250.0"),
                ) shouldBe 0
            }
        }

        "testAnalyzeDeviations_USDTriggerOnlyEnforcesFiatCorrection" {
            runTest {
                val allocs = listOf(
                    Allocation(Asset.USD, 20.0),
                    Allocation(Asset.BTC, 40.0),
                    Allocation(Asset.ETH, 40.0),
                )
                val settings = Settings(
                    loopDelaySeconds = 0L,
                    deviationTriggerPercent = 15.0,
                    dustThresholdUSD = 1.0,
                    dryRun = true,
                    fiatMaxDrawdown = 0.0,
                    fiatDeploymentExponent = 1.0,
                )
                every { configService.getConfig() } returns AppConfig(
                    kraken = KrakenCredentials(apiKey = "k", privateKey = "s"),
                    settings = settings,
                    allocations = allocs,
                )

                val currentValuesUSD = mapOf(
                    Asset.USD to BigDecimal("240.0"),
                    Asset.BTC to BigDecimal("380.0"),
                    Asset.ETH to BigDecimal("380.0"),
                )
                val buyOrders = mutableMapOf<String, BigDecimal>()
                val sellOrders = mutableMapOf<String, BigDecimal>()
                val actionLog = mutableListOf<String>()

                val result = portfolioAnalyzer.analyzeDeviations(
                    totalPortfolioValueUSD = BigDecimal("1000.0"),
                    currentValuesUSD = currentValuesUSD,
                    effectiveUsdTarget = BigDecimal("20.0"),
                    cryptoScaleFactor = BigDecimal.ONE,
                )
                buyOrders.putAll(result.buyOrders)
                sellOrders.putAll(result.sellOrders)
                actionLog.addAll(result.actionLog)

                buyOrders.isNotEmpty() shouldBe true
                buyOrders[Asset.BTC]!!.compareTo(
                    BigDecimal("20.0"),
                ) shouldBe 0
                buyOrders[Asset.ETH]!!.compareTo(
                    BigDecimal("20.0"),
                ) shouldBe 0
            }
        }

        "CQ-7-1: testAnalyzeDeviations_USDAndCryptoTriggersCreateCryptoOrders" {
            runTest {
                val allocs = listOf(
                    Allocation(Asset.USD, 20.0),
                    Allocation(Asset.BTC, 40.0),
                    Allocation(Asset.ETH, 40.0),
                )
                val settings = Settings(
                    loopDelaySeconds = 0L,
                    deviationTriggerPercent = 15.0,
                    dustThresholdUSD = 1.0,
                    dryRun = true,
                    fiatMaxDrawdown = 0.0,
                    fiatDeploymentExponent = 1.0,
                )
                every { configService.getConfig() } returns AppConfig(
                    kraken = KrakenCredentials(apiKey = "k", privateKey = "s"),
                    settings = settings,
                    allocations = allocs,
                )

                val result = portfolioAnalyzer.analyzeDeviations(
                    totalPortfolioValueUSD = BigDecimal("1000.0"),
                    currentValuesUSD = mapOf(
                        Asset.USD to BigDecimal("240.0"),
                        Asset.BTC to BigDecimal("340.0"),
                        Asset.ETH to BigDecimal("420.0"),
                    ),
                    effectiveUsdTarget = BigDecimal("20.0"),
                    cryptoScaleFactor = BigDecimal.ONE,
                )

                result.buyOrders[Asset.BTC]!!.shouldBeEqualComparingTo(BigDecimal("60.0"))
                result.actionLog.any { it == "USD Deviation Triggered. Enforcing fiat correction." } shouldBe false
            }
        }

        "CQ-7-2: testAnalyzeDeviations_ExactTriggerBelowDustSkipsAssetOrder" {
            runTest {
                val allocs = listOf(
                    Allocation(Asset.USD, 90.0),
                    Allocation(Asset.BTC, 10.0),
                )
                val settings = Settings(
                    loopDelaySeconds = 0L,
                    deviationTriggerPercent = 2.0,
                    dustThresholdUSD = 0.50,
                    dryRun = true,
                    fiatMaxDrawdown = 0.0,
                    fiatDeploymentExponent = 1.0,
                )
                every { configService.getConfig() } returns AppConfig(
                    kraken = KrakenCredentials(apiKey = "k", privateKey = "s"),
                    settings = settings,
                    allocations = allocs,
                )

                val result = portfolioAnalyzer.analyzeDeviations(
                    totalPortfolioValueUSD = BigDecimal("249.50"),
                    currentValuesUSD = mapOf(
                        Asset.USD to BigDecimal("224.051"),
                        Asset.BTC to BigDecimal("25.449"),
                    ),
                    effectiveUsdTarget = BigDecimal("90.0"),
                    cryptoScaleFactor = BigDecimal.ONE,
                )

                result.buyOrders.containsKey(Asset.BTC) shouldBe false
                result.sellOrders.containsKey(Asset.BTC) shouldBe false
                result.actionLog.any { it.contains("Deviation: BTC") } shouldBe false
            }
        }

        "testAnalyzeDeviations_dustDeviationIsIgnored" {
            runTest {
                val totalVal = BigDecimal.valueOf(1000.0)
                val currentValuesUSD = mapOf(
                    Asset.USD to BigDecimal("0.0001"),
                    Asset.BTC to BigDecimal("999.9999"),
                )
                val effUsdTarget = BigDecimal.ZERO
                val cryptoScale = BigDecimal("2.0")
                val allocs = listOf(
                    Allocation(Asset.USD, 50.0),
                    Allocation(Asset.BTC, 50.0),
                )
                val settings = Settings(
                    loopDelaySeconds = 0L,
                    deviationTriggerPercent = 2.0,
                    dustThresholdUSD = 5.0,
                    dryRun = true,
                    fiatMaxDrawdown = 0.0,
                    fiatDeploymentExponent = 1.0,
                )
                every { configService.getConfig() } returns AppConfig(
                    kraken = KrakenCredentials(apiKey = "k", privateKey = "s"),
                    settings = settings,
                    allocations = allocs,
                )

                val result = portfolioAnalyzer.analyzeDeviations(
                    totalPortfolioValueUSD = totalVal,
                    currentValuesUSD = currentValuesUSD,
                    effectiveUsdTarget = effUsdTarget,
                    cryptoScaleFactor = cryptoScale,
                )

                result.buyOrders.isEmpty() shouldBe true
                result.sellOrders.isEmpty() shouldBe true
                result.actionLog.none { it.contains("USD Dev") } shouldBe true
            }
        }

        "testExecuteOrders_DryRunAndSellsSuccess" {
            runTest {
                val buyOrders = emptyMap<String, BigDecimal>()
                val sellOrders =
                    mapOf(Asset.BTC to BigDecimal.valueOf(100.0))
                val currentValuesUSD =
                    mapOf(Asset.USD to BigDecimal.valueOf(1000.0))
                val prices = mapOf(Asset.BTC to BigDecimal.TEN)
                val settings = Settings(
                    loopDelaySeconds = 0L,
                    deviationTriggerPercent = 2.0,
                    dustThresholdUSD = 1.0,
                    dryRun = true,
                    fiatMaxDrawdown = 0.0,
                    fiatDeploymentExponent = 1.0,
                )
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
                val settings = Settings(
                    loopDelaySeconds = 0L,
                    deviationTriggerPercent = 2.0,
                    dustThresholdUSD = 1.0,
                    dryRun = false,
                    fiatMaxDrawdown = 0.0,
                    fiatDeploymentExponent = 1.0,
                )
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
                val settings = Settings(
                    loopDelaySeconds = 0L,
                    deviationTriggerPercent = 2.0,
                    dustThresholdUSD = 1.0,
                    dryRun = false,
                    fiatMaxDrawdown = 0.0,
                    fiatDeploymentExponent = 1.0,
                )

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

                val mockSettings = Settings(
                    loopDelaySeconds = 0L,
                    deviationTriggerPercent = 1.0,
                    dustThresholdUSD = 1.0,
                    dryRun = false,
                )
                val mockConfig = AppConfig(
                    kraken = KrakenCredentials("k", "s"),
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

        "testBuildSnapshot_Reflection" {
            runTest {
                val buildSnapshotMethod =
                    PortfolioManagerImpl::class.java.getDeclaredMethod(
                        "buildSnapshot",
                        Map::class.java,
                        Map::class.java,
                        Map::class.java,
                        BigDecimal::class.java,
                        BigDecimal::class.java,
                        BigDecimal::class.java,
                        BigDecimal::class.java,
                        BigDecimal::class.java,
                        List::class.java,
                    ).apply { isAccessible = true }

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
                every { configService.getConfig() } returns AppConfig(
                    kraken = KrakenCredentials(apiKey = "k", privateKey = "s"),
                    settings = Settings(
                        loopDelaySeconds = 0L,
                        deviationTriggerPercent = 2.0,
                        dustThresholdUSD = 1.0,
                        dryRun = true,
                        fiatMaxDrawdown = 0.0,
                        fiatDeploymentExponent = 1.0,
                    ),
                    allocations = allocs,
                )

                val snapshot = buildSnapshotMethod.invoke(
                    portfolioManager,
                    balances,
                    prices,
                    currentValuesUSD,
                    totalVal,
                    effUsdTarget,
                    cryptoScale,
                    drawdown,
                    deployment,
                    actionLog,
                ) as PortfolioSnapshot

                snapshot.totalValueUSD.compareTo(BigDecimal("1000.0")) shouldBe 0

                val currentValuesUSDMissing =
                    mapOf(TestFixtures.USD to BigDecimal("500.0"))
                val pricesMissing = emptyMap<String, BigDecimal>()

                val snapshotFallback = buildSnapshotMethod.invoke(
                    portfolioManager,
                    balances,
                    pricesMissing,
                    currentValuesUSDMissing,
                    totalVal,
                    effUsdTarget,
                    cryptoScale,
                    drawdown,
                    deployment,
                    actionLog,
                ) as PortfolioSnapshot

                val btcSnap = snapshotFallback.assets[Asset.BTC]
                btcSnap!!.valueUSD.compareTo(BigDecimal.ZERO) shouldBe 0
                btcSnap.price.compareTo(BigDecimal.ONE) shouldBe 0
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
                val settings = Settings(
                    loopDelaySeconds = 0L,
                    deviationTriggerPercent = 2.0,
                    dustThresholdUSD = 1.0, // $1.00 dust threshold
                    dryRun = false,
                    fiatMaxDrawdown = 0.0,
                    fiatDeploymentExponent = 1.0,
                )
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
                val settings = Settings(
                    loopDelaySeconds = 60L,
                    deviationTriggerPercent = 2.0,
                    dustThresholdUSD = 1.0,
                    dryRun = true,
                    fiatMaxDrawdown = 0.0,
                    fiatDeploymentExponent = 1.0,
                )
                val config = AppConfig(
                    kraken = KrakenCredentials(apiKey = "k", privateKey = "s"),
                    settings = settings,
                    allocations = emptyList(),
                )
                every { configService.getConfig() } returns config

                // Throw exception during sync to cover catch blocks
                coEvery { tradeHistoryService.syncTradesFromKraken() } throws RuntimeException("Sync exception")

                portfolioManager.startRebalancingLoop()
                val job = launch {
                    portfolioManager.runLoop()
                }
                yield()
                portfolioManager.stopRebalancingLoop()
                job.join()
            }
        }

        "testRecordTrade_EdgeCases" {
            runTest {
                val result = OrderResult(
                    success = true,
                    pair = "XBTUSD",
                    side = "BUY",
                    volume = BigDecimal.ZERO,
                    dryRun = false,
                )
                // Test zero volume and zero price to hit the else branches
                (orderExecutor as OrderExecutorImpl).recordTrade(
                    result = result,
                    symbol = "BTC",
                    pair = "XBTUSD",
                    side = OrderSide.BUY,
                    volume = BigDecimal.ZERO,
                    usdAmount = BigDecimal.ZERO,
                    prices = emptyMap(),
                )
                coVerify(exactly = 1) { tradeHistoryService.saveTrade(any()) }
            }
        }

        "runLoop_handlesCycleExceptions" {
            runTest {
                val settings = Settings(
                    loopDelaySeconds = 60L,
                    deviationTriggerPercent = 2.0,
                    dustThresholdUSD = 1.0,
                    dryRun = true,
                    fiatMaxDrawdown = 0.0,
                    fiatDeploymentExponent = 1.0,
                )
                val config = AppConfig(
                    kraken = KrakenCredentials(apiKey = "k", privateKey = "s"),
                    settings = settings,
                    allocations = listOf(Allocation(TestFixtures.USD, 100.0)),
                )
                every { configService.getConfig() } returns config

                // Make kraken service throw to trigger the cycle exception in runLoop
                krakenService.balanceSupplier = { throw RuntimeException("Balances fetch error") }

                portfolioManager.startRebalancingLoop()
                val job = launch {
                    portfolioManager.runLoop()
                }
                yield()
                portfolioManager.stopRebalancingLoop()
                job.join()
            }
        }

        "runLoop_handlesCancellationException" {
            runTest {
                val settings = Settings(
                    loopDelaySeconds = 60L,
                    deviationTriggerPercent = 2.0,
                    dustThresholdUSD = 1.0,
                    dryRun = true,
                    fiatMaxDrawdown = 0.0,
                    fiatDeploymentExponent = 1.0,
                )
                val config = AppConfig(
                    kraken = KrakenCredentials(apiKey = "k", privateKey = "s"),
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
            }
        }

        "post-trade snapshot falls back to pre-trade values when recalculation fails" {
            runTest {
                val allocs = listOf(
                    Allocation(Asset.BTC, 50.0),
                    Allocation(Asset.USD, 50.0),
                )
                every { configService.getConfig() } returns AppConfig(
                    kraken = KrakenCredentials(apiKey = "k", privateKey = "s"),
                    settings = Settings(
                        loopDelaySeconds = 0L,
                        deviationTriggerPercent = 2.0,
                        dustThresholdUSD = 1.0,
                        dryRun = true,
                        fiatMaxDrawdown = 0.0,
                        fiatDeploymentExponent = 1.0,
                    ),
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
                every { configService.getConfig() } returns AppConfig(
                    kraken = KrakenCredentials(apiKey = "k", privateKey = "s"),
                    settings = Settings(
                        loopDelaySeconds = 0L,
                        deviationTriggerPercent = 2.0,
                        dustThresholdUSD = 1.0,
                        dryRun = true,
                        fiatMaxDrawdown = 0.0,
                        fiatDeploymentExponent = 1.0,
                    ),
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
    }
}
