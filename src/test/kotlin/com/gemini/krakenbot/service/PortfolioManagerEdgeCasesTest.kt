package com.gemini.krakenbot.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.PortfolioStats
import com.gemini.krakenbot.repository.PortfolioStatsRepository
import com.gemini.krakenbot.service.impl.PortfolioManagerImpl
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.booleans.shouldBeTrue
import io.mockk.slot
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import java.math.BigDecimal

class PortfolioManagerEdgeCasesTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    private val krakenService = FakeKrakenService()
    private val configService = mockk<ConfigService>(relaxed = true)
    private val tradeHistoryService = mockk<TradeHistoryService>(relaxed = true)
    private val portfolioStatsRepository = mockk<PortfolioStatsRepository>(relaxed = true)
    private lateinit var portfolioManager: PortfolioManagerImpl

    init {
        beforeTest {
            every { portfolioStatsRepository.load() } returns PortfolioStats(BigDecimal.ZERO)
            portfolioManager = PortfolioManagerImpl(krakenService, configService, tradeHistoryService, portfolioStatsRepository)
        }

        "runLoop_respectsLoopDelay" {
            runTest {
                val settings = Settings(60L, 2.0, 1.0, true, 0.0, 1.0)
                val config = AppConfig(KrakenCredentials("k", "s"), settings, emptyList())
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

                val allocs = listOf(Allocation("USD", 100.0))
                val settings = Settings(0L, 2.0, 1.0, true, 0.0, 1.0)
                val config = AppConfig(KrakenCredentials("k", "s"), settings, allocs)
                every { configService.getConfig() } returns config

                portfolioManager.startRebalancingLoop()
                portfolioManager.performRebalanceCycle()

                krakenService.getBalancesCallCount shouldBe 1
            }
        }

        "performRebalanceCycle_PriceNotFoundAbort" {
            runTest {
                krakenService.balanceSupplier = { mapOf("BTC" to 1.0) }
                krakenService.pricesSupplier = { emptyMap() }

                val allocs = listOf(Allocation("BTC", 100.0))
                val settings = Settings(0L, 2.0, 1.0, true, 0.0, 1.0)
                val config = AppConfig(KrakenCredentials("k", "s"), settings, allocs)
                every { configService.getConfig() } returns config

                portfolioManager.startRebalancingLoop()
                portfolioManager.performRebalanceCycle()

                verify(exactly = 0) { tradeHistoryService.addSnapshot(any()) }
            }
        }

        "testDistributeFiatCorrection_NoCounterbalancingAssets" {
            val allDevs = mapOf(
                "USD" to BigDecimal("100.0"),
                "A" to BigDecimal("10.0")
            )
            val buyOrders = mutableMapOf<String, BigDecimal>()
            val sellOrders = mutableMapOf<String, BigDecimal>()

            portfolioManager.distributeFiatCorrection(BigDecimal("100.0"), allDevs, buyOrders, sellOrders, mutableListOf())
            
            buyOrders.isEmpty().shouldBeTrue()
            sellOrders.isEmpty().shouldBeTrue()
        }

        "testFiatDeploymentRatioExceedsOne" {
            runTest {
                every { portfolioStatsRepository.load() } returns PortfolioStats(BigDecimal("2000.0"))

                val allocs = listOf(Allocation("A", 50.0), Allocation("USD", 50.0))
                every { configService.getConfig() } returns 
                    AppConfig(
                        KrakenCredentials("k", "s"),
                        Settings(0L, 2.0, 1.0, true, 50.0, 1.0),
                        allocs
                    )

                krakenService.balanceSupplier = { mapOf("A" to 2.5, "USD" to 250.0) }
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

            val priceEth = portfolioManager.resolvePriceFromTicker("ETH", rawPrices)
            priceEth shouldBe BigDecimal("3100.0")

            val priceMissing = portfolioManager.resolvePriceFromTicker("LTC", rawPrices)
            priceMissing.compareTo(BigDecimal.ZERO) shouldBe 0
        }

        "testExecuteOrders_ZeroPriceContinues" {
            runTest {
                val buyOrders = mapOf("ETH" to BigDecimal.TEN)
                val sellOrders = mapOf("BTC" to BigDecimal.TEN)
                val currentValuesUSD = mapOf("USD" to BigDecimal.valueOf(1000.0))
                val prices = emptyMap<String, BigDecimal>()
                val settings = Settings(0L, 2.0, 1.0, false, 0.0, 1.0)
                val actionLog = mutableListOf<String>()

                portfolioManager.executeOrders(buyOrders, sellOrders, currentValuesUSD, prices, settings, actionLog)

                krakenService.executedOrders.isEmpty().shouldBeTrue()
            }
        }

        "testExecuteOrders_UpdateCashException" {
            runTest {
                val buyOrders = mapOf("ETH" to BigDecimal.TEN)
                val sellOrders = mapOf("BTC" to BigDecimal.valueOf(100.0))
                val currentValuesUSD = mapOf("USD" to BigDecimal.valueOf(1000.0))
                val prices = mapOf("BTC" to BigDecimal.TEN, "ETH" to BigDecimal.valueOf(5))
                val settings = Settings(0L, 2.0, 1.0, false, 0.0, 1.0)
                val actionLog = mutableListOf<String>()

                krakenService.balanceSupplier = { throw RuntimeException("balances api error") }

                portfolioManager.executeOrders(buyOrders, sellOrders, currentValuesUSD, prices, settings, actionLog)

                krakenService.executedOrders.size shouldBe 2
                krakenService.executedOrders[0].pair shouldBe "XBTUSD"
                krakenService.executedOrders[0].side shouldBe "sell"
                krakenService.executedOrders[0].volume.compareTo(BigDecimal.TEN) shouldBe 0
                krakenService.executedOrders[1].pair shouldBe "ETHUSD"
                krakenService.executedOrders[1].side shouldBe "buy"
                krakenService.executedOrders[1].volume.compareTo(BigDecimal.valueOf(2)) shouldBe 0
            }
        }

        "testExecuteOrders_UpdateBalancesNullOrEmpty" {
            runTest {
                val buyOrders = mapOf("ETH" to BigDecimal.TEN)
                val sellOrders = mapOf("BTC" to BigDecimal.valueOf(100.0))
                val currentValuesUSD = mapOf("USD" to BigDecimal.valueOf(1000.0))
                val prices = mapOf("BTC" to BigDecimal.TEN, "ETH" to BigDecimal.valueOf(5))
                val settings = Settings(0L, 2.0, 1.0, false, 0.0, 1.0)
                val actionLog = mutableListOf<String>()

                krakenService.balanceSupplier = { emptyMap() }

                portfolioManager.executeOrders(buyOrders, sellOrders, currentValuesUSD, prices, settings, actionLog)

                krakenService.executedOrders.size shouldBe 2
                krakenService.executedOrders[0].pair shouldBe "XBTUSD"
                krakenService.executedOrders[0].side shouldBe "sell"
                krakenService.executedOrders[0].volume.compareTo(BigDecimal.TEN) shouldBe 0
                krakenService.executedOrders[1].pair shouldBe "ETHUSD"
                krakenService.executedOrders[1].side shouldBe "buy"
                krakenService.executedOrders[1].volume.compareTo(BigDecimal.valueOf(2)) shouldBe 0
            }
        }

        "testUpdateAthAndCalculateDrawdown_NewAth" {
            every { portfolioStatsRepository.load() } returns PortfolioStats(BigDecimal("1000.0"))
            val drawdown = portfolioManager.updateAthAndCalculateDrawdown(BigDecimal("1500.0"), depositDetected = false)
            drawdown.compareTo(BigDecimal.ZERO) shouldBe 0
            verify { portfolioStatsRepository.save(any()) }
        }

        "testUpdateAthAndCalculateDrawdown_RecalibrateOnDeposit" {
            every { portfolioStatsRepository.load() } returns PortfolioStats(BigDecimal("10000.0"))
            val drawdown = portfolioManager.updateAthAndCalculateDrawdown(BigDecimal("9000.0"), depositDetected = true)
            drawdown.compareTo(BigDecimal.ZERO) shouldBe 0
            verify { portfolioStatsRepository.save(any()) }
        }

        "testExecuteOrders_UpdateBalancesEmptyUsdOrNull" {
            runTest {
                val buyOrders = mapOf("ETH" to BigDecimal.TEN)
                val sellOrders = mapOf("BTC" to BigDecimal.valueOf(100.0))
                val currentValuesUSD = mapOf("USD" to BigDecimal.valueOf(1000.0))
                val prices = mapOf("BTC" to BigDecimal.TEN, "ETH" to BigDecimal.valueOf(5))
                val settings = Settings(0L, 2.0, 1.0, false, 0.0, 1.0)
                val actionLog = mutableListOf<String>()

                krakenService.balanceSupplier = { mapOf("BTC" to 1.0, "ZUSD" to 0.0) }

                portfolioManager.executeOrders(buyOrders, sellOrders, currentValuesUSD, prices, settings, actionLog)

                krakenService.executedOrders.size shouldBe 2
                krakenService.executedOrders[0].pair shouldBe "XBTUSD"
                krakenService.executedOrders[0].side shouldBe "sell"
                krakenService.executedOrders[0].volume.compareTo(BigDecimal.TEN) shouldBe 0
                krakenService.executedOrders[1].pair shouldBe "ETHUSD"
                krakenService.executedOrders[1].side shouldBe "buy"
                krakenService.executedOrders[1].volume.compareTo(BigDecimal.valueOf(2)) shouldBe 0
            }
        }

        "testExecuteOrders_SkipDustBuys" {
            runTest {
                val buyOrders = mapOf("ETH" to BigDecimal.valueOf(0.5))
                val sellOrders = emptyMap<String, BigDecimal>()
                val currentValuesUSD = mapOf("USD" to BigDecimal.valueOf(1000.0))
                val prices = mapOf("ETH" to BigDecimal.valueOf(5))
                val settings = Settings(0L, 2.0, 1.0, false, 0.0, 1.0)
                val actionLog = mutableListOf<String>()

                portfolioManager.executeOrders(buyOrders, sellOrders, currentValuesUSD, prices, settings, actionLog)

                krakenService.executedOrders.isEmpty().shouldBeTrue()
            }
        }

        "testAnalyzeDeviations_UsdTriggeredButOrdersNotEmpty" {
            val currentValuesUSD = mapOf("USD" to BigDecimal.valueOf(1100.0), "BTC" to BigDecimal.valueOf(900.0))
            val totalVal = BigDecimal.valueOf(2000.0)
            val effUsdTarget = BigDecimal.valueOf(50.0)
            val cryptoScale = BigDecimal.ONE
            val buyOrders = mutableMapOf<String, BigDecimal>()
            val sellOrders = mutableMapOf<String, BigDecimal>()
            val actionLog = mutableListOf<String>()

            val allocs = listOf(Allocation("USD", 50.0), Allocation("BTC", 50.0))
            val settings = Settings(0L, 2.0, 1.0, true, 0.0, 1.0)
            every { configService.getConfig() } returns AppConfig(KrakenCredentials("k", "s"), settings, allocs)

            portfolioManager.analyzeDeviations(totalVal, currentValuesUSD, effUsdTarget, cryptoScale, buyOrders, sellOrders, actionLog)

            buyOrders.isEmpty() shouldBe false
        }
    }
}
