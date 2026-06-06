package com.gemini.krakenbot.service

import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.model.OrderResult
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.PortfolioStats
import com.gemini.krakenbot.repository.PortfolioStatsRepository
import com.gemini.krakenbot.service.impl.OrderExecutor
import com.gemini.krakenbot.service.impl.PortfolioAnalyzer
import com.gemini.krakenbot.service.impl.PortfolioManagerImpl
import com.gemini.krakenbot.model.Asset
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import java.io.IOException
import java.math.BigDecimal

@Suppress("unused")
class PortfolioManagerEdgeCasesTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    private val krakenService = FakeKrakenService()
    private val configService = mockk<ConfigService>(relaxed = true)
    private val tradeHistoryService = mockk<TradeHistoryService>(relaxed = true)
    private val portfolioStatsRepository =
        mockk<PortfolioStatsRepository>(relaxed = true)
    private lateinit var portfolioManager: PortfolioManagerImpl
    private lateinit var portfolioAnalyzer: PortfolioAnalyzer
    private lateinit var orderExecutor: OrderExecutor

    init {
        beforeTest {
            every { portfolioStatsRepository.load() } returns PortfolioStats(
                BigDecimal.ZERO
            )
            portfolioAnalyzer = PortfolioAnalyzer(
                krakenService,
                configService,
                portfolioStatsRepository
            )
            orderExecutor = OrderExecutor(krakenService, portfolioAnalyzer)
            portfolioManager = PortfolioManagerImpl(
                configService,
                tradeHistoryService,
                portfolioAnalyzer,
                orderExecutor
            )
        }

        "runLoop_respectsLoopDelay" {
            runTest {
                val settings = Settings(
                    60L,
                    2.0,
                    1.0,
                    true,
                    0.0,
                    1.0
                )
                val config = AppConfig(
                    KrakenCredentials("k", "s"),
                    settings,
                    emptyList()
                )
                every { configService.getConfig() } returns config
                krakenService.balanceSupplier = { emptyMap() }

                portfolioManager.startRebalancingLoop()
                val job = launch {
                    portfolioManager.runLoop()
                }
                yield()
                krakenService.getBalancesCallCount shouldBe 1

                portfolioManager.stopRebalancingLoop()
                job.join()
                krakenService.getBalancesCallCount shouldBe 1
            }
        }

        "performRebalanceCycle_NullBalances" {
            runTest {
                krakenService.balanceSupplier = { emptyMap() }

                val allocs = listOf(Allocation(
                    Asset.USD,
                    100.0
                ))
                val settings = Settings(
                    0L,
                    2.0,
                    1.0,
                    true,
                    0.0,
                    1.0
                )
                val config =
                    AppConfig(KrakenCredentials(
                        "k",
                        "s"
                    ), settings, allocs)
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

                val allocs = listOf(Allocation(
                    Asset.BTC,
                    100.0
                ))
                val settings = Settings(
                    0L,
                    2.0,
                    1.0,
                    true,
                    0.0,
                    1.0
                )
                val config =
                    AppConfig(KrakenCredentials(
                        "k",
                        "s"
                    ), settings, allocs)
                every { configService.getConfig() } returns config

                portfolioManager.startRebalancingLoop()
                portfolioManager.performRebalanceCycle()

                verify(exactly = 0) { tradeHistoryService.addSnapshot(any()) }
            }
        }

        "testDistributeFiatCorrection_NoCounterbalancingAssets" {
            val allDevs = mapOf(
                Asset.USD to BigDecimal("100.0"),
                "A" to BigDecimal("10.0")
            )
            val buyOrders = mutableMapOf<String, BigDecimal>()
            val sellOrders = mutableMapOf<String, BigDecimal>()

            portfolioAnalyzer.distributeFiatCorrection(
                BigDecimal("100.0"),
                allDevs,
                buyOrders,
                sellOrders,
                mutableListOf()
            )

            buyOrders.isEmpty().shouldBeTrue()
            sellOrders.isEmpty().shouldBeTrue()
        }

        "testFiatDeploymentRatioExceedsOne" {
            runTest {
                every {
                    portfolioStatsRepository.load()
                } returns PortfolioStats(
                    BigDecimal("2000.0")
                )

                val allocs = listOf(
                    Allocation("A", 50.0),
                    Allocation(Asset.USD, 50.0)
                )
                every { configService.getConfig() } returns
                        AppConfig(
                            KrakenCredentials("k", "s"),
                            Settings(
                                0L,
                                2.0,
                                1.0,
                                true,
                                50.0,
                                1.0
                            ),
                            allocs
                        )

                krakenService.balanceSupplier =
                    { mapOf("A" to 2.5, Asset.USD to 250.0) }
                krakenService.pricesSupplier = { mapOf("AUSD" to 100.0) }

                portfolioManager.startRebalancingLoop()
                portfolioManager.performRebalanceCycle()

                val captor = slot<PortfolioSnapshot>()
                verify { tradeHistoryService.addSnapshot(capture(captor)) }
                captor.captured.fiatDeploymentPercent.toDouble() shouldBe 100.0
            }
        }

        "testResolvePriceFromTicker_ExplicitPairAndFallback" {
            val rawPrices = mapOf("ETHEUR" to 3000.0, "ETHUSD" to 3100.0)

            val priceEth = portfolioAnalyzer.resolvePriceFromTicker(
                Asset.ETH,
                rawPrices
            )
            priceEth shouldBe BigDecimal("3100.0")

            val priceMissing =
                portfolioAnalyzer
                    .resolvePriceFromTicker("LTC", rawPrices)
            priceMissing.compareTo(BigDecimal.ZERO) shouldBe 0
        }

        "testExecuteOrders_ZeroPriceContinues" {
            runTest {
                val buyOrders = mapOf(Asset.ETH to BigDecimal.TEN)
                val sellOrders = mapOf(Asset.BTC to BigDecimal.TEN)
                val currentValuesUSD =
                    mapOf(Asset.USD to BigDecimal.valueOf(1000.0))
                val prices = emptyMap<String, BigDecimal>()
                val settings = Settings(
                    0L,
                    2.0,
                    1.0,
                    false,
                    0.0,
                    1.0
                )
                val actionLog = mutableListOf<String>()

                orderExecutor.executeOrders(
                    buyOrders,
                    sellOrders,
                    currentValuesUSD,
                    prices,
                    settings,
                    actionLog
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
                    Asset.ETH to BigDecimal.valueOf(5)
                )
                val settings = Settings(
                    0L,
                    2.0,
                    1.0,
                    false,
                    0.0,
                    1.0
                )
                val actionLog = mutableListOf<String>()

                krakenService.balanceSupplier =
                    { throw RuntimeException("balances api error") }

                orderExecutor.executeOrders(
                    buyOrders,
                    sellOrders,
                    currentValuesUSD,
                    prices,
                    settings,
                    actionLog
                )

                krakenService.executedOrders.size shouldBe 2
                krakenService.executedOrders[0].pair shouldBe "XBTUSD"
                krakenService.executedOrders[0].side shouldBe "sell"
                krakenService.executedOrders[0].volume.compareTo(BigDecimal.TEN) shouldBe 0
                krakenService.executedOrders[1].pair shouldBe "ETHUSD"
                krakenService.executedOrders[1].side shouldBe "buy"
                krakenService.executedOrders[1].volume.compareTo(
                    BigDecimal.valueOf(
                        2
                    )
                ) shouldBe 0
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
                    Asset.ETH to BigDecimal.valueOf(5)
                )
                val settings = Settings(
                    0L,
                    2.0,
                    1.0,
                    false,
                    0.0,
                    1.0
                )
                val actionLog = mutableListOf<String>()

                krakenService.balanceSupplier = { emptyMap() }

                orderExecutor.executeOrders(
                    buyOrders,
                    sellOrders,
                    currentValuesUSD,
                    prices,
                    settings,
                    actionLog
                )

                krakenService.executedOrders.size shouldBe 2
                krakenService.executedOrders[0].pair shouldBe "XBTUSD"
                krakenService.executedOrders[0].side shouldBe "sell"
                krakenService.executedOrders[0].volume.compareTo(BigDecimal.TEN) shouldBe 0
                krakenService.executedOrders[1].pair shouldBe "ETHUSD"
                krakenService.executedOrders[1].side shouldBe "buy"
                krakenService.executedOrders[1].volume.compareTo(
                    BigDecimal.valueOf(
                        2
                    )
                ) shouldBe 0
            }
        }

        "testUpdateAthAndCalculateDrawdown_NewAth" {
            every { portfolioStatsRepository.load() } returns PortfolioStats(
                BigDecimal("1000.0")
            )
            val drawdown = portfolioAnalyzer.updateAthAndCalculateDrawdown(
                BigDecimal("1500.0")
            )
            drawdown.compareTo(BigDecimal.ZERO) shouldBe 0
            verify { portfolioStatsRepository.save(any()) }
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
                    Asset.ETH to BigDecimal.valueOf(5)
                )
                val settings = Settings(
                    0L,
                    2.0,
                    1.0,
                    false,
                    0.0,
                    1.0
                )
                val actionLog = mutableListOf<String>()

                krakenService.balanceSupplier =
                    { mapOf(Asset.BTC to 1.0, "ZUSD" to 0.0) }

                orderExecutor.executeOrders(
                    buyOrders,
                    sellOrders,
                    currentValuesUSD,
                    prices,
                    settings,
                    actionLog
                )

                krakenService.executedOrders.size shouldBe 2
                krakenService.executedOrders[0].pair shouldBe "XBTUSD"
                krakenService.executedOrders[0].side shouldBe "sell"
                krakenService.executedOrders[0].volume.compareTo(BigDecimal.TEN) shouldBe 0
                krakenService.executedOrders[1].pair shouldBe "ETHUSD"
                krakenService.executedOrders[1].side shouldBe "buy"
                krakenService.executedOrders[1].volume.compareTo(
                    BigDecimal.valueOf(
                        2
                    )
                ) shouldBe 0
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
                    0L,
                    2.0,
                    1.0,
                    false,
                    0.0,
                    1.0
                )
                val actionLog = mutableListOf<String>()

                orderExecutor.executeOrders(
                    buyOrders,
                    sellOrders,
                    currentValuesUSD,
                    prices,
                    settings,
                    actionLog
                )

                krakenService.executedOrders.isEmpty().shouldBeTrue()
            }
        }

        "testAnalyzeDeviations_UsdTriggeredButOrdersNotEmpty" {
            val currentValuesUSD = mapOf(
                Asset.USD to BigDecimal.valueOf(1100.0),
                Asset.BTC to BigDecimal.valueOf(900.0)
            )
            val totalVal = BigDecimal.valueOf(2000.0)
            val effUsdTarget = BigDecimal.valueOf(50.0)
            val cryptoScale = BigDecimal.ONE
            val buyOrders = mutableMapOf<String, BigDecimal>()
            val sellOrders = mutableMapOf<String, BigDecimal>()
            val actionLog = mutableListOf<String>()

            val allocs = listOf(
                Allocation(Asset.USD, 50.0),
                Allocation(Asset.BTC, 50.0)
            )
            val settings = Settings(
                0L,
                2.0,
                1.0,
                true,
                0.0,
                1.0
            )
            every { configService.getConfig() } returns AppConfig(
                KrakenCredentials("k", "s"),
                settings,
                allocs
            )

            val result = portfolioAnalyzer.analyzeDeviations(
                totalVal,
                currentValuesUSD,
                effUsdTarget,
                cryptoScale
            )
            buyOrders.putAll(result.buyOrders)
            sellOrders.putAll(result.sellOrders)
            actionLog.addAll(result.actionLog)

            buyOrders.isEmpty() shouldBe false
        }

        "testPerformRebalanceCycle_TradeHistorySaveIOException" {
            runTest {
                krakenService.balanceSupplier =
                    { mapOf(Asset.USD to 1000.0) }
                val allocs = listOf(Allocation(
                    Asset.USD,
                    100.0
                ))
                val settings = Settings(
                    0L,
                    2.0,
                    1.0,
                    true,
                    0.0,
                    1.0
                )
                val config =
                    AppConfig(
                        KrakenCredentials(
                            "k",
                            "s"
                        ),
                        settings,
                        allocs
                    )
                every { configService.getConfig() } returns config

                every {
                    tradeHistoryService.addSnapshot(any())
                } throws IOException(
                    "Disk full"
                )

                portfolioManager.startRebalancingLoop()
                portfolioManager.performRebalanceCycle()

                krakenService.getBalancesCallCount shouldBe 1
            }
        }

        "testResolvePriceFromTicker_FallbackBranches" {
            val rawPrices = mapOf(
                "BTCEUR" to 60000.0,
                "XBTUSD" to 61000.0
            )
            val price = portfolioAnalyzer.resolvePriceFromTicker(
                Asset.BTC,
                rawPrices
            )
            price shouldBe BigDecimal("61000.0")

            val rawPricesOnlyEur = mapOf(
                "XBTEUR" to 55000.0
            )
            val priceEurOnly = portfolioAnalyzer.resolvePriceFromTicker(
                Asset.BTC,
                rawPricesOnlyEur
            )
            priceEurOnly.compareTo(BigDecimal.ZERO) shouldBe 0
        }

        "testCalculatePortfolioValues_PriceNotFoundAbort" {
            val balances =
                mapOf(Asset.USD to 1000.0, Asset.BTC to 1.0)
            val prices = mapOf(Asset.USD to BigDecimal.ONE)
            val currentValuesUSD = mutableMapOf<String, BigDecimal>()

            val allocs = listOf(
                Allocation(Asset.USD, 50.0),
                Allocation(Asset.BTC, 50.0)
            )
            every { configService.getConfig() } returns AppConfig(
                KrakenCredentials("k", "s"),
                Settings(
                    0L,
                    2.0,
                    1.0,
                    true,
                    0.0,
                    1.0
                ),
                allocs
            )

            val result = portfolioAnalyzer.calculatePortfolioValues(
                balances,
                prices
            )
            result shouldBe null
        }

        "testResolveBalance_FallbackChain" {
            portfolioAnalyzer.resolveBalance(
                Asset.BTC,
                mapOf(Asset.BTC to 1.1)
            ) shouldBe 1.1
            portfolioAnalyzer.resolveBalance(
                Asset.BTC,
                mapOf("XBTC" to 1.2)
            ) shouldBe 1.2
            portfolioAnalyzer.resolveBalance(
                Asset.USD,
                mapOf("ZUSD" to 1.3)
            ) shouldBe 1.3
            portfolioAnalyzer.resolveBalance(
                Asset.BTC,
                mapOf(Asset.XBT to 1.4)
            ) shouldBe 1.4
            portfolioAnalyzer.resolveBalance(
                Asset.BTC,
                mapOf("XXBT" to 1.5)
            ) shouldBe 1.5
            portfolioAnalyzer.resolveBalance(
                Asset.BTC,
                mapOf(Asset.ETH to 1.6)
            ) shouldBe 0.0
        }

        "testUpdateAthAndCalculateDrawdown_NegativeAth" {
            every { portfolioStatsRepository.load() } returns PortfolioStats(
                BigDecimal("-500.0")
            )
            val drawdown =
                portfolioAnalyzer
                    .updateAthAndCalculateDrawdown(
                        BigDecimal("1000.0")
                    )
            drawdown.compareTo(BigDecimal.ZERO) shouldBe 0
            verify {
                portfolioStatsRepository.save(match {
                    it.allTimeHigh == BigDecimal(
                        "1000.0"
                    )
                })
            }
        }

        "testUpdateAthAndCalculateDrawdown_NullAth" {
            every { portfolioStatsRepository.load() } returns PortfolioStats(
                null
            )
            val drawdown =
                portfolioAnalyzer.updateAthAndCalculateDrawdown(
                    BigDecimal("1200.0"))
            drawdown.compareTo(BigDecimal.ZERO) shouldBe 0
            verify {
                portfolioStatsRepository.save(match {
                    it.allTimeHigh == BigDecimal(
                        "1200.0"
                    )
                })
            }
        }

        "testUpdateAthAndCalculateDrawdown_StatsSaveIOException" {
            every {
                portfolioStatsRepository.load()
            } returns PortfolioStats(
                BigDecimal("1000.0")
            )
            every {
                portfolioStatsRepository.save(any())
            } throws IOException(
                "Save failed"
            )

            val drawdown =
                portfolioAnalyzer.updateAthAndCalculateDrawdown(
                    BigDecimal("800.0")
                )
            drawdown.compareTo(BigDecimal("20.0")) shouldBe 0
        }

        "testAnalyzeDeviations_MissingSymbolInCurrentValues" {
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
                Allocation(Asset.BTC, 50.0)
            )
            every { configService.getConfig() } returns AppConfig(
                KrakenCredentials("k", "s"),
                Settings(
                    0L,
                    2.0,
                    1.0,
                    true,
                    0.0,
                    1.0
                ),
                allocs
            )

            val result = portfolioAnalyzer.analyzeDeviations(
                totalVal,
                currentValuesUSD,
                effUsdTarget,
                cryptoScale
            )
            buyOrders.putAll(result.buyOrders)
            sellOrders.putAll(result.sellOrders)
            actionLog.addAll(result.actionLog)
            buyOrders[Asset.BTC]?.compareTo(
                BigDecimal("250.0")
            ) shouldBe 0
        }

        "testAnalyzeDeviations_USDTriggerOnlyEnforcesFiatCorrection" {
            val allocs = listOf(
                Allocation(Asset.USD, 20.0),
                Allocation(Asset.BTC, 40.0),
                Allocation(Asset.ETH, 40.0)
            )
            val settings = Settings(
                0L,
                15.0,
                1.0,
                true,
                0.0,
                1.0
            )
            every { configService.getConfig() } returns AppConfig(
                KrakenCredentials("k", "s"),
                settings,
                allocs
            )

            val currentValuesUSD = mapOf(
                Asset.USD to BigDecimal("240.0"),
                Asset.BTC to BigDecimal("380.0"),
                Asset.ETH to BigDecimal("380.0")
            )
            val buyOrders = mutableMapOf<String, BigDecimal>()
            val sellOrders = mutableMapOf<String, BigDecimal>()
            val actionLog = mutableListOf<String>()

            val result = portfolioAnalyzer.analyzeDeviations(
                BigDecimal("1000.0"),
                currentValuesUSD,
                BigDecimal("20.0"),
                BigDecimal.ONE
            )
            buyOrders.putAll(result.buyOrders)
            sellOrders.putAll(result.sellOrders)
            actionLog.addAll(result.actionLog)

            buyOrders.isNotEmpty() shouldBe true
            buyOrders[Asset.BTC]!!.compareTo(
                BigDecimal("20.0")
            ) shouldBe 0
            buyOrders[Asset.ETH]!!.compareTo(
                BigDecimal("20.0")
            ) shouldBe 0
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
                    0L,
                    2.0,
                    1.0,
                    true,
                    0.0,
                    1.0
                )
                val actionLog = mutableListOf<String>()

                krakenService.orderResultFactory = { pair, type, side, volume ->
                    OrderResult(
                        success = true,
                        pair = pair,
                        side = side,
                        volume = volume,
                        dryRun = true
                    )
                }

                orderExecutor.executeOrders(
                    buyOrders,
                    sellOrders,
                    currentValuesUSD,
                    prices,
                    settings,
                    actionLog
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
                    0L,
                    2.0,
                    1.0,
                    false,
                    0.0,
                    1.0
                )
                val actionLog = mutableListOf<String>()

                krakenService.orderResultFactory = { pair, type, side, volume ->
                    OrderResult(
                        success = false,
                        pair = pair,
                        side = side,
                        volume = volume,
                        errorMessage = "Invalid amount"
                    )
                }

                orderExecutor.executeOrders(
                    buyOrders,
                    sellOrders,
                    currentValuesUSD,
                    prices,
                    settings,
                    actionLog
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
                    0L,
                    2.0,
                    1.0,
                    false,
                    0.0,
                    1.0
                )

                krakenService.getBalancesCallCount = 0
                krakenService.balanceSupplier =
                    { mapOf(Asset.USD to 1050.0) }

                orderExecutor.executeOrders(
                    buyOrders,
                    sellOrders,
                    currentValuesUSD,
                    prices,
                    settings,
                    mutableListOf()
                )

                krakenService.getBalancesCallCount shouldBe 1

                krakenService.getBalancesCallCount = 0
                krakenService.balanceSupplier =
                    { mapOf(Asset.USD to 900.0) }

                orderExecutor.executeOrders(
                    buyOrders,
                    sellOrders,
                    currentValuesUSD,
                    prices,
                    settings,
                    mutableListOf()
                )

                krakenService.getBalancesCallCount shouldBe 3
            }
        }

        "testLogOrderResult" {
            val log1 = mutableListOf<String>()
            orderExecutor.logOrderResult(
                OrderResult(
                    true,
                    "XBTUSD",
                    "sell",
                    BigDecimal.ONE,
                    dryRun = true
                ),
                log1, Asset.BTC, BigDecimal.ONE, BigDecimal.TEN, "SELL"
            )
            log1.first() shouldBe "[DRY RUN] SELL BTC Volume: 1 Value: $10"

            val log2 = mutableListOf<String>()
            orderExecutor.logOrderResult(
                OrderResult(
                    true,
                    "XBTUSD",
                    "buy",
                    BigDecimal.ONE,
                    dryRun = false
                ),
                log2, Asset.BTC, BigDecimal.ONE, BigDecimal.TEN, "BUY"
            )
            log2.first() shouldBe "BUY BTC Volume: 1 Cost: $10"
        }

        "testBuildSnapshot_Reflection" {
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
                    List::class.java
                ).apply { isAccessible = true }

            val balances =
                mapOf(Asset.USD to 500.0, Asset.BTC to 0.01)
            val prices = mapOf(Asset.BTC to BigDecimal("50000.0"))
            val currentValuesUSD = mapOf(
                Asset.USD to BigDecimal("500.0"),
                Asset.BTC to BigDecimal("500.0")
            )
            val totalVal = BigDecimal("1000.0")
            val effUsdTarget = BigDecimal("50.0")
            val cryptoScale = BigDecimal.ONE
            val drawdown = BigDecimal.ZERO
            val deployment = BigDecimal.ZERO
            val actionLog = listOf("Cycle completed")

            val allocs = listOf(
                Allocation(Asset.USD, 50.0),
                Allocation(Asset.BTC, 50.0)
            )
            every { configService.getConfig() } returns AppConfig(
                KrakenCredentials("k", "s"),
                Settings(
                    0L,
                    2.0,
                    1.0,
                    true,
                    0.0,
                    1.0
                ),
                allocs
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
                actionLog
            ) as PortfolioSnapshot

            snapshot.totalValueUSD.compareTo(BigDecimal("1000.0")) shouldBe 0

            val currentValuesUSDMissing =
                mapOf(Asset.USD to BigDecimal("500.0"))
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
                actionLog
            ) as PortfolioSnapshot

            val btcSnap = snapshotFallback.assets[Asset.BTC]
            btcSnap!!.valueUSD.compareTo(BigDecimal.ZERO) shouldBe 0
            btcSnap.price.compareTo(BigDecimal.ONE) shouldBe 0
        }
    }
}
