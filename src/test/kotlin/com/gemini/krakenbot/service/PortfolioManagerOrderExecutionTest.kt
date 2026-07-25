package com.gemini.krakenbot.service

import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.PortfolioStats
import com.gemini.krakenbot.repository.PortfolioStatsRepository
import com.gemini.krakenbot.service.impl.OrderExecutorImpl
import com.gemini.krakenbot.service.impl.PortfolioAnalyzerImpl
import com.gemini.krakenbot.service.impl.PortfolioManagerImpl
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal

class PortfolioManagerOrderExecutionTest : StringSpec() {

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
            coEvery {
                portfolioStatsRepository.load()
            } returns PortfolioStats(BigDecimal.ZERO)
            portfolioAnalyzer = PortfolioAnalyzerImpl(
                krakenService = krakenService,
                configService = configService,
                portfolioStatsRepository = portfolioStatsRepository,
            )
            orderExecutor = OrderExecutorImpl(krakenService, tradeHistoryService)
            portfolioManager = PortfolioManagerImpl(
                configService = configService,
                tradeHistoryService = tradeHistoryService,
                portfolioAnalyzer = portfolioAnalyzer,
                orderExecutor = orderExecutor,
            )
        }

        "testExecutionOrder_SellsBeforeBuys" {
            runTest {
                val allocA = Allocation("A", 10.0)
                val allocB = Allocation("B", 90.0)
                val allocUSD = Allocation(
                    Asset.USD,
                    0.0,
                )
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

                krakenService.executedOrders.size shouldBe 2
                krakenService.executedOrders[0].pair shouldBe "AUSD"
                krakenService.executedOrders[0].side shouldBe "sell"
                krakenService.executedOrders[1].pair shouldBe "BUSD"
                krakenService.executedOrders[1].side shouldBe "buy"
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

        "testExecution_SkipDustSells" {
            runTest {
                val allocA = Allocation("A", 10.0)
                val allocUSD = Allocation(
                    Asset.USD,
                    90.0,
                )
                val allAllocations = listOf(allocA, allocUSD)

                val mockSettings = Settings(
                    loopDelaySeconds = 0L,
                    deviationTriggerPercent = 0.1,
                    dustThresholdUSD = 10.0,
                    dryRun = false,
                )
                val mockConfig = AppConfig(
                    kraken = KrakenCredentials("k", "s"),
                    settings = mockSettings,
                    allocations = allAllocations,
                )

                every { configService.getConfig() } returns mockConfig

                val balances = mapOf("A" to 1.05, Asset.USD to 895.0)
                krakenService.balanceSupplier = { balances }

                val prices = mapOf("AUSD" to 100.0)
                krakenService.pricesSupplier = { prices }

                portfolioManager.performRebalanceCycle()

                krakenService.executedOrders.none {
                    it.pair == "AUSD" && it.side == "sell"
                } shouldBe true
            }
        }

        "testExecution_CashVerificationFallback" {
            runTest {
                val allAllocations =
                    listOf(
                        Allocation(
                            "A",
                            10.0,
                        ),
                        Allocation(
                            "B",
                            90.0,
                        ),
                    )
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

                val initialBalances =
                    mapOf("A" to 5.0, "B" to 50.0, Asset.USD to 0.0)

                var callCount = 0
                krakenService.balanceSupplier = {
                    callCount++
                    if (callCount == 1) {
                        initialBalances
                    } else {
                        throw RuntimeException(
                            "API Error during verification!",
                        )
                    }
                }

                val prices = mapOf("AUSD" to 100.0, "BUSD" to 10.0)
                krakenService.pricesSupplier = { prices }

                portfolioManager.performRebalanceCycle()

                // Fail-closed: balance polls throw → no positive USD observed → buys aborted
                krakenService.executedOrders.size shouldBe 1
                krakenService.executedOrders[0].pair shouldBe "AUSD"
                krakenService.executedOrders[0].side shouldBe "sell"
            }
        }

        "testExecution_PartialFillCashUpdate" {
            runTest {
                val allAllocations =
                    listOf(
                        Allocation(
                            "A",
                            10.0,
                        ),
                        Allocation(
                            "B",
                            90.0,
                        ),
                    )
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

                val initialBalances =
                    mapOf("A" to 5.0, "B" to 50.0, Asset.USD to 0.0)
                val updatedBalances =
                    mapOf("A" to 2.0, "B" to 50.0, Asset.USD to 200.0)

                var callCount = 0
                krakenService.balanceSupplier = {
                    callCount++
                    if (callCount == 1) initialBalances else updatedBalances
                }

                val prices = mapOf("AUSD" to 100.0, "BUSD" to 10.0)
                krakenService.pricesSupplier = { prices }

                portfolioManager.performRebalanceCycle()

                krakenService.executedOrders.size shouldBe 2
                krakenService.executedOrders[0].pair shouldBe "AUSD"
                krakenService.executedOrders[0].side shouldBe "sell"
                krakenService.executedOrders[1].pair shouldBe "BUSD"
                krakenService.executedOrders[1].side shouldBe "buy"
                (
                    krakenService.executedOrders[1].volume.subtract(
                        BigDecimal.valueOf(
                            19.8,
                        ),
                    ).abs() < BigDecimal("0.1")
                    ).shouldBeTrue()
            }
        }
    }
}
