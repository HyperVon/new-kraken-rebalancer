package com.gemini.krakenbot.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.coVerify

import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.repository.PortfolioStatsRepository
import com.gemini.krakenbot.service.impl.PortfolioManagerImpl
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

class PortfolioManagerComprehensiveTest : StringSpec() {

    override fun isolationMode() = io.kotest.core.spec.IsolationMode.InstancePerTest

    private val krakenService = FakeKrakenService()
    private val configService = mockk<ConfigService>(relaxed = true)
    private val tradeHistoryService = mockk<TradeHistoryService>(relaxed = true)
    private val portfolioStatsRepository = mockk<PortfolioStatsRepository>(relaxed = true)
    private lateinit var portfolioManager: PortfolioManagerImpl

    /** Builds an [AppConfig] with the given allocations and default settings (2% deviation, 1 USD dust). */
    private fun makeConfig(vararg allocs: Allocation) = AppConfig(
        KrakenCredentials("k", "s"),
        Settings(60L, 2.0, 1.0, false, 0.0, 1.0),
        allocs.toList()
    )

    init {
        beforeTest {
            krakenService.executedOrders.clear()
            portfolioManager = PortfolioManagerImpl(
                krakenService, configService, tradeHistoryService, portfolioStatsRepository
            )
        }

        "Scenario: Balanced Portfolio - No Trades Expected" {
            runTest {
                every { configService.getConfig() } returns makeConfig(Allocation("A", 50.0), Allocation("B", 50.0))
                krakenService.pricesSupplier = { mapOf("AUSD" to 100.0, "BUSD" to 100.0) }
                krakenService.balanceSupplier = { mapOf("A" to 10.0, "B" to 10.0) }

                portfolioManager.performRebalanceCycle()

                krakenService.executedOrders.size shouldBe 0
            }
        }

        "Scenario: Simple Rebalance - Asset A Overweight, B Underweight" {
            runTest {
                every { configService.getConfig() } returns makeConfig(Allocation("A", 50.0), Allocation("B", 50.0))
                krakenService.pricesSupplier = { mapOf("AUSD" to 100.0, "BUSD" to 100.0) }
                krakenService.balanceSupplier = { mapOf("A" to 11.0, "B" to 9.0) }

                portfolioManager.performRebalanceCycle()

                val sell = krakenService.executedOrders.first { it.side == "sell" }
                sell.pair shouldBe "AUSD"
                (Math.abs(sell.volume - 1.0) < 0.0001) shouldBe true

                val buy = krakenService.executedOrders.first { it.side == "buy" }
                buy.pair shouldBe "BUSD"
                (Math.abs(buy.volume - 1.0) < 0.0001) shouldBe true
            }
        }

        "Scenario: Fiat Deposit - Distribute Excess Cash" {
            runTest {
                every { configService.getConfig() } returns makeConfig(
                    Allocation("A", 40.0), Allocation("B", 40.0), Allocation("USD", 20.0)
                )
                krakenService.pricesSupplier = { mapOf("AUSD" to 100.0, "BUSD" to 100.0) }
                krakenService.balanceSupplier = { mapOf("A" to 4.0, "B" to 4.0, "USD" to 1200.0) }

                portfolioManager.performRebalanceCycle()

                val buyA = krakenService.executedOrders.first { it.pair == "AUSD" && it.side == "buy" }
                (Math.abs(buyA.volume - 4.0) < 0.0001) shouldBe true

                val buyB = krakenService.executedOrders.first { it.pair == "BUSD" && it.side == "buy" }
                (Math.abs(buyB.volume - 4.0) < 0.0001) shouldBe true
            }
        }

        "Scenario: Fiat Withdrawal - Prevent Buys if No Cash" {
            runTest {
                every { configService.getConfig() } returns makeConfig(Allocation("A", 10.0), Allocation("B", 90.0))
                krakenService.pricesSupplier = { mapOf("AUSD" to 100.0, "BUSD" to 100.0) }
                krakenService.balanceSupplier = { mapOf("A" to 5.0, "B" to 0.0, "USD" to 0.0) }

                portfolioManager.performRebalanceCycle()

                val sell = krakenService.executedOrders.first { it.side == "sell" }
                sell.pair shouldBe "AUSD"
                (Math.abs(sell.volume - 4.5) < 0.0001) shouldBe true

                val buy = krakenService.executedOrders.first { it.side == "buy" }
                buy.pair shouldBe "BUSD"
                (Math.abs(buy.volume - 4.5) < 0.05) shouldBe true
            }
        }

        "Scenario: Dust Thresholds - Skip Tiny Orders" {
            runTest {
                every { configService.getConfig() } returns makeConfig(Allocation("A", 50.0), Allocation("B", 50.0))
                krakenService.pricesSupplier = { mapOf("AUSD" to 100.0, "BUSD" to 100.0) }
                krakenService.balanceSupplier = { mapOf("A" to 10.005, "B" to 9.995) }

                portfolioManager.performRebalanceCycle()

                krakenService.executedOrders.size shouldBe 0
            }
        }

        "Scenario: 0% Allocation - Sell Everything" {
            runTest {
                every { configService.getConfig() } returns makeConfig(Allocation("A", 0.0), Allocation("USD", 100.0))
                krakenService.pricesSupplier = { mapOf("AUSD" to 100.0) }
                krakenService.balanceSupplier = { mapOf("A" to 10.0, "USD" to 0.0) }

                portfolioManager.performRebalanceCycle()

                val sell = krakenService.executedOrders.first { it.side == "sell" }
                sell.pair shouldBe "AUSD"
                (Math.abs(sell.volume - 10.0) < 0.0001) shouldBe true
            }
        }

        "Scenario: New Asset Entry - Buy from Scratch" {
            runTest {
                every { configService.getConfig() } returns makeConfig(Allocation("A", 100.0), Allocation("USD", 0.0))
                krakenService.pricesSupplier = { mapOf("AUSD" to 100.0) }
                krakenService.balanceSupplier = { mapOf("A" to 0.0, "USD" to 1000.0) }

                portfolioManager.performRebalanceCycle()

                val buy = krakenService.executedOrders.first { it.side == "buy" }
                buy.pair shouldBe "AUSD"
                (Math.abs(buy.volume - 10.0) < 0.0001) shouldBe true
            }
        }

        "Scenario: Market Moon - All Assets Overweight (Sell to Rebalance)" {
            runTest {
                every { configService.getConfig() } returns makeConfig(Allocation("A", 50.0), Allocation("USD", 50.0))
                krakenService.pricesSupplier = { mapOf("AUSD" to 200.0) }
                krakenService.balanceSupplier = { mapOf("A" to 10.0, "USD" to 1000.0) }

                portfolioManager.performRebalanceCycle()

                val sell = krakenService.executedOrders.first { it.side == "sell" }
                sell.pair shouldBe "AUSD"
                (Math.abs(sell.volume - 2.5) < 0.0001) shouldBe true
            }
        }

        "Scenario: Price Lookup Failure - Abort Cycle" {
            runTest {
                every { configService.getConfig() } returns makeConfig(Allocation("A", 100.0), Allocation("USD", 0.0))
                krakenService.pricesSupplier = { emptyMap() }
                krakenService.balanceSupplier = { mapOf("A" to 10.0) }

                portfolioManager.performRebalanceCycle()

                krakenService.executedOrders.size shouldBe 0
            }
        }

        "Scenario: Partial Price Lookup Failure - Skip Asset" {
            runTest {
                every { configService.getConfig() } returns makeConfig(Allocation("A", 50.0), Allocation("B", 50.0))
                krakenService.pricesSupplier = { mapOf("BUSD" to 100.0) }
                krakenService.balanceSupplier = { mapOf("A" to 10.0, "B" to 20.0) }

                portfolioManager.performRebalanceCycle()

                krakenService.executedOrders.none { it.pair == "AUSD" } shouldBe true
            }
        }

        "Scenario: API Exception - Safe Recovery" {
            runTest {
                every { configService.getConfig() } returns makeConfig(Allocation("A", 100.0), Allocation("USD", 0.0))
                krakenService.pricesSupplier = { mapOf("AUSD" to 100.0) }
                krakenService.balanceSupplier = { mapOf("A" to 0.0, "USD" to 1000.0) }
                // Make executeOrder record the call and then throw.
                krakenService.executeOrderAction = { _, _, _, _ -> throw RuntimeException("Kraken Down") }

                try {
                    portfolioManager.performRebalanceCycle()
                } catch (_: Exception) {}

                val order = krakenService.executedOrders.first()
                order.pair shouldBe "AUSD"
                order.side shouldBe "buy"
            }
        }
    }
}
