package com.gemini.krakenbot.service

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.domain.OrderResult
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.service.impl.PortfolioManagerImpl
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.math.BigDecimal

class PortfolioManagerComprehensiveTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    private lateinit var fixture: PortfolioManagerTestFixture
    private val krakenService get() = fixture.krakenService
    private val configService get() = fixture.configService
    private val tradeHistoryService get() = fixture.tradeHistoryService
    private val portfolioManager: PortfolioManagerImpl get() = fixture.portfolioManager

    /**
     * Scenario settings: 2% trigger, $1 dust. `dryRun=false` so FakeKraken records non-dry-run
     * placements (still not a live exchange — see [FakeKrakenService]).
     */
    private fun makeConfig(vararg allocs: Allocation) = TestFixtures.config(
        settings = TestFixtures.settings(dryRun = false, loopDelaySeconds = 60L),
        allocations = allocs.toList(),
    )

    init {
        beforeTest {
            fixture = createPortfolioManagerTestFixture()
        }

        "Scenario: Balanced Portfolio - No Trades Expected" {
            runTest {
                every { configService.getConfig() } returns makeConfig(
                    Allocation(TestFixtures.A, 50.0),
                    Allocation(TestFixtures.B, 50.0),
                )
                krakenService.pricesSupplier =
                    { mapOf(TestFixtures.AUSD to 100.0, TestFixtures.BUSD to 100.0) }
                krakenService.balanceSupplier =
                    { mapOf(TestFixtures.A to 10.0, TestFixtures.B to 10.0) }

                portfolioManager.performRebalanceCycle()

                krakenService.executedOrders.size shouldBe 0
            }
        }

        "Scenario: Simple Rebalance - Asset A Overweight, B Underweight" {
            runTest {
                every { configService.getConfig() } returns makeConfig(
                    Allocation(TestFixtures.A, 50.0),
                    Allocation(TestFixtures.B, 50.0),
                )
                krakenService.pricesSupplier =
                    { mapOf(TestFixtures.AUSD to 100.0, TestFixtures.BUSD to 100.0) }
                krakenService.balanceSupplier = {
                    val sold = krakenService.executedOrders.any { it.side == TestFixtures.SELL }
                    if (sold) {
                        mapOf(TestFixtures.A to 10.0, TestFixtures.B to 9.0, Asset.USD to 100.0)
                    } else {
                        mapOf(TestFixtures.A to 11.0, TestFixtures.B to 9.0)
                    }
                }

                portfolioManager.performRebalanceCycle()

                val sell =
                    krakenService.executedOrders.first { it.side == TestFixtures.SELL }
                sell.pair shouldBe TestFixtures.AUSD
                sell.volume.subtract(BigDecimal.ONE)
                    .abs() shouldBeLessThan BigDecimal("0.0001")

                val buy =
                    krakenService.executedOrders.first { it.side == TestFixtures.BUY }
                buy.pair shouldBe TestFixtures.BUSD
                // Buy capped to 99% of cash raised from the sell ($100 → $99)
                buy.volume.subtract(BigDecimal("0.99"))
                    .abs() shouldBeLessThan BigDecimal("0.0001")
            }
        }

        "Scenario: Fiat Deposit - Distribute Excess Cash" {
            runTest {
                every { configService.getConfig() } returns makeConfig(
                    Allocation(TestFixtures.A, 40.0),
                    Allocation(TestFixtures.B, 40.0),
                    Allocation(Asset.USD, 20.0),
                )
                krakenService.pricesSupplier =
                    { mapOf(TestFixtures.AUSD to 100.0, TestFixtures.BUSD to 100.0) }
                krakenService.balanceSupplier = {
                    mapOf(
                        TestFixtures.A to 4.0,
                        TestFixtures.B to 4.0,
                        Asset.USD to 1200.0,
                    )
                }

                portfolioManager.performRebalanceCycle()

                val buyA =
                    krakenService.executedOrders.first {
                        it.pair == TestFixtures.AUSD && it.side == TestFixtures.BUY
                    }
                buyA.volume.subtract(BigDecimal.valueOf(4.0))
                    .abs() shouldBeLessThan BigDecimal("0.0001")

                val buyB =
                    krakenService.executedOrders.first {
                        it.pair == TestFixtures.BUSD && it.side == TestFixtures.BUY
                    }
                buyB.volume.subtract(BigDecimal.valueOf(4.0))
                    .abs() shouldBeLessThan BigDecimal("0.0001")
            }
        }

        "Scenario: Fiat Withdrawal - Prevent Buys if No Cash" {
            runTest {
                every { configService.getConfig() } returns makeConfig(
                    Allocation(TestFixtures.A, 10.0),
                    Allocation(TestFixtures.B, 90.0),
                )
                krakenService.pricesSupplier =
                    { mapOf(TestFixtures.AUSD to 100.0, TestFixtures.BUSD to 100.0) }
                // Balance polls never show settled USD → fail-closed aborts buys
                krakenService.balanceSupplier =
                    { mapOf(TestFixtures.A to 5.0, TestFixtures.B to 0.0, Asset.USD to 0.0) }

                portfolioManager.performRebalanceCycle()

                val sell =
                    krakenService.executedOrders.first { it.side == TestFixtures.SELL }
                sell.pair shouldBe TestFixtures.AUSD
                sell.volume.subtract(BigDecimal.valueOf(4.5))
                    .abs() shouldBeLessThan BigDecimal("0.0001")

                krakenService.executedOrders.none { it.side == TestFixtures.BUY }.shouldBeTrue()
            }
        }

        "Scenario: Minimum Order Sizes - Skip Tiny Orders" {
            runTest {
                every { configService.getConfig() } returns makeConfig(
                    Allocation(
                        TestFixtures.A,
                        50.0,
                    ),
                    Allocation(
                        TestFixtures.B,
                        50.0,
                    ),
                )
                krakenService.pricesSupplier =
                    { mapOf(TestFixtures.AUSD to 100.0, TestFixtures.BUSD to 100.0) }
                krakenService.balanceSupplier =
                    { mapOf(TestFixtures.A to 10.005, TestFixtures.B to 9.995) }

                portfolioManager.performRebalanceCycle()

                krakenService.executedOrders.size shouldBe 0
            }
        }

        "Scenario: 0% Allocation - Sell Everything" {
            runTest {
                every { configService.getConfig() } returns makeConfig(
                    Allocation(
                        TestFixtures.A,
                        0.0,
                    ),
                    Allocation(
                        Asset.USD,
                        100.0,
                    ),
                )
                krakenService.pricesSupplier = { mapOf(TestFixtures.AUSD to 100.0) }
                krakenService.balanceSupplier =
                    { mapOf(TestFixtures.A to 10.0, Asset.USD to 0.0) }

                portfolioManager.performRebalanceCycle()

                val sell =
                    krakenService.executedOrders.first { it.side == TestFixtures.SELL }
                sell.pair shouldBe TestFixtures.AUSD
                sell.volume.subtract(BigDecimal.TEN)
                    .abs() shouldBeLessThan BigDecimal("0.0001")
            }
        }

        "Scenario: New Asset Entry - Buy from Scratch" {
            runTest {
                every { configService.getConfig() } returns makeConfig(
                    Allocation(
                        TestFixtures.A,
                        100.0,
                    ),
                    Allocation(
                        Asset.USD,
                        0.0,
                    ),
                )
                krakenService.pricesSupplier = { mapOf(TestFixtures.AUSD to 100.0) }
                krakenService.balanceSupplier =
                    { mapOf(TestFixtures.A to 0.0, Asset.USD to 1000.0) }

                portfolioManager.performRebalanceCycle()

                val buy =
                    krakenService.executedOrders.first { it.side == TestFixtures.BUY }
                buy.pair shouldBe TestFixtures.AUSD
                // Full deployment capped to 99% of available USD ($1000 → $990 → 9.9 units)
                buy.volume.subtract(BigDecimal("9.9"))
                    .abs() shouldBeLessThan BigDecimal("0.0001")
            }
        }

        "Scenario: Market Moon - All Assets Overweight (Sell to Rebalance)" {
            runTest {
                every { configService.getConfig() } returns makeConfig(
                    Allocation(
                        TestFixtures.A,
                        50.0,
                    ),
                    Allocation(
                        Asset.USD,
                        50.0,
                    ),
                )
                krakenService.pricesSupplier = { mapOf(TestFixtures.AUSD to 200.0) }
                krakenService.balanceSupplier =
                    { mapOf(TestFixtures.A to 10.0, Asset.USD to 1000.0) }

                portfolioManager.performRebalanceCycle()

                val sell =
                    krakenService.executedOrders.first { it.side == TestFixtures.SELL }
                sell.pair shouldBe TestFixtures.AUSD
                sell.volume.subtract(BigDecimal.valueOf(2.5))
                    .abs() shouldBeLessThan BigDecimal("0.0001")
            }
        }

        "Scenario: Price Lookup Failure - Abort Cycle" {
            runTest {
                every { configService.getConfig() } returns makeConfig(
                    Allocation(
                        TestFixtures.A,
                        100.0,
                    ),
                    Allocation(
                        Asset.USD,
                        0.0,
                    ),
                )
                krakenService.pricesSupplier = { emptyMap() }
                krakenService.balanceSupplier = { mapOf(TestFixtures.A to 10.0) }

                portfolioManager.performRebalanceCycle()

                krakenService.executedOrders.size shouldBe 0
                portfolioManager.getOperationalStatus().lastCycleError shouldBe "Cycle produced no snapshot"
            }
        }

        "Scenario: Partial Price Lookup Failure - Skip Asset" {
            runTest {
                every { configService.getConfig() } returns makeConfig(
                    Allocation(
                        TestFixtures.A,
                        50.0,
                    ),
                    Allocation(
                        TestFixtures.B,
                        50.0,
                    ),
                )
                krakenService.pricesSupplier = { mapOf(TestFixtures.BUSD to 100.0) }
                krakenService.balanceSupplier =
                    { mapOf(TestFixtures.A to 10.0, TestFixtures.B to 20.0) }

                portfolioManager.performRebalanceCycle()

                krakenService.executedOrders.none {
                    it.pair == TestFixtures.AUSD
                } shouldBe true
            }
        }

        "Scenario: API Exception - Safe Recovery" {
            runTest {
                every { configService.getConfig() } returns makeConfig(
                    Allocation(
                        TestFixtures.A,
                        100.0,
                    ),
                    Allocation(
                        Asset.USD,
                        0.0,
                    ),
                )
                krakenService.pricesSupplier = { mapOf(TestFixtures.AUSD to 100.0) }
                krakenService.balanceSupplier =
                    { mapOf(TestFixtures.A to 0.0, Asset.USD to 1000.0) }
                krakenService.orderResultFactory = { pair, _, side, volume ->
                    OrderResult(
                        success = false,
                        pair = pair,
                        side = side,
                        volume = volume,
                        errorMessage = "Kraken Down",
                    )
                }

                val snapshots = mutableListOf<PortfolioSnapshot>()
                coEvery {
                    tradeHistoryService.addSnapshot(any<PortfolioSnapshot>())
                } answers {
                    snapshots.add(
                        firstArg(),
                    )
                }

                portfolioManager.performRebalanceCycle()

                val order = krakenService.executedOrders.first()
                order.pair shouldBe TestFixtures.AUSD
                order.side shouldBe TestFixtures.BUY

                snapshots.single().actions.any {
                    it.startsWith("FAILED BUY A")
                }.shouldBeTrue()
            }
        }

        "Scenario: Order Execution Exception - Snapshot Retained and Cycle Marked Failed" {
            runTest {
                every { configService.getConfig() } returns makeConfig(
                    Allocation(TestFixtures.A, 100.0),
                    Allocation(Asset.USD, 0.0),
                )
                krakenService.pricesSupplier = { mapOf(TestFixtures.AUSD to 100.0) }
                krakenService.balanceSupplier = { mapOf(TestFixtures.A to 0.0, Asset.USD to 1000.0) }
                val original = IOException("exchange unavailable")
                krakenService.executeOrderAction = { _, _, _, _ -> throw original }

                portfolioManager.performRebalanceCycle()

                portfolioManager.getOperationalStatus().lastCycleError shouldBe "Order execution failed"
            }
        }
    }
}
