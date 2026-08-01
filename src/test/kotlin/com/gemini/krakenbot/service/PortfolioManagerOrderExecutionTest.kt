package com.gemini.krakenbot.service

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.PortfolioStats
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal

class PortfolioManagerOrderExecutionTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    private lateinit var fixture: PortfolioManagerTestFixture

    init {
        beforeTest {
            fixture = createPortfolioManagerTestFixture()
            coEvery {
                fixture.portfolioStatsRepository.load()
            } returns PortfolioStats(BigDecimal.ZERO)
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

                val mockSettings = TestFixtures.settings(dryRun = false, deviationTriggerPercent = 1.0)
                val mockConfig = TestFixtures.config(
                    settings = mockSettings,
                    allocations = allAllocations,
                )

                every { fixture.configService.getConfig() } returns mockConfig

                fixture.krakenService.balanceSupplier = {
                    val sold = fixture.krakenService.executedOrders.any { it.side.equals("sell", ignoreCase = true) }
                    if (sold) {
                        mapOf("A" to 1.0, "B" to 50.0, Asset.USD to 400.0)
                    } else {
                        mapOf("A" to 5.0, "B" to 50.0, Asset.USD to 0.0)
                    }
                }

                val prices = mapOf("AUSD" to 100.0, "BUSD" to 10.0)
                fixture.krakenService.pricesSupplier = { prices }

                fixture.portfolioManager.performRebalanceCycle()

                fixture.krakenService.executedOrders.size shouldBe 2
                fixture.krakenService.executedOrders[0].pair shouldBe "AUSD"
                fixture.krakenService.executedOrders[0].side shouldBe "sell"
                fixture.krakenService.executedOrders[1].pair shouldBe "BUSD"
                fixture.krakenService.executedOrders[1].side shouldBe "buy"
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

                val mockSettings = TestFixtures.settings(
                    dryRun = false,
                    deviationTriggerPercent = 0.1,
                    dustThresholdUSD = 10.0,
                )
                val mockConfig = TestFixtures.config(
                    settings = mockSettings,
                    allocations = allAllocations,
                )

                every { fixture.configService.getConfig() } returns mockConfig

                val balances = mapOf("A" to 1.05, Asset.USD to 895.0)
                fixture.krakenService.balanceSupplier = { balances }

                val prices = mapOf("AUSD" to 100.0)
                fixture.krakenService.pricesSupplier = { prices }

                fixture.portfolioManager.performRebalanceCycle()

                fixture.krakenService.executedOrders.none {
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
                val mockSettings = TestFixtures.settings(dryRun = false, deviationTriggerPercent = 1.0)
                val mockConfig = TestFixtures.config(
                    settings = mockSettings,
                    allocations = allAllocations,
                )
                every { fixture.configService.getConfig() } returns mockConfig

                val initialBalances =
                    mapOf("A" to 5.0, "B" to 50.0, Asset.USD to 0.0)

                var callCount = 0
                fixture.krakenService.balanceSupplier = {
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
                fixture.krakenService.pricesSupplier = { prices }

                fixture.portfolioManager.performRebalanceCycle()

                // Fail-closed: balance polls throw → no positive USD observed → buys aborted
                fixture.krakenService.executedOrders.size shouldBe 1
                fixture.krakenService.executedOrders[0].pair shouldBe "AUSD"
                fixture.krakenService.executedOrders[0].side shouldBe "sell"
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
                val mockSettings = TestFixtures.settings(dryRun = false, deviationTriggerPercent = 1.0)
                val mockConfig = TestFixtures.config(
                    settings = mockSettings,
                    allocations = allAllocations,
                )
                every { fixture.configService.getConfig() } returns mockConfig

                val initialBalances =
                    mapOf("A" to 5.0, "B" to 50.0, Asset.USD to 0.0)
                val updatedBalances =
                    mapOf("A" to 2.0, "B" to 50.0, Asset.USD to 200.0)

                var callCount = 0
                fixture.krakenService.balanceSupplier = {
                    callCount++
                    if (callCount == 1) initialBalances else updatedBalances
                }

                val prices = mapOf("AUSD" to 100.0, "BUSD" to 10.0)
                fixture.krakenService.pricesSupplier = { prices }

                fixture.portfolioManager.performRebalanceCycle()

                fixture.krakenService.executedOrders.size shouldBe 2
                fixture.krakenService.executedOrders[0].pair shouldBe "AUSD"
                fixture.krakenService.executedOrders[0].side shouldBe "sell"
                fixture.krakenService.executedOrders[1].pair shouldBe "BUSD"
                fixture.krakenService.executedOrders[1].side shouldBe "buy"
                (
                    fixture.krakenService.executedOrders[1].volume.subtract(
                        BigDecimal.valueOf(
                            19.8,
                        ),
                    ).abs() < BigDecimal("0.1")
                    ).shouldBeTrue()
            }
        }

        "testExecution_BuyVolumeFloorsAtCryptoPrecision" {
            runTest {
                val actionLog = mutableListOf<String>()

                fixture.orderExecutor.executeOrders(
                    buyOrders = mapOf(Asset.BTC to BigDecimal("1.00")),
                    sellOrders = emptyMap(),
                    currentValuesUSD = mapOf(Asset.USD to BigDecimal("10.00")),
                    prices = mapOf(Asset.BTC to BigDecimal("6.00")),
                    settings = TestFixtures.settings(
                        dryRun = false,
                        dustThresholdUSD = 0.99,
                        simulation = true,
                    ),
                    actionLog = actionLog,
                    cycleId = "buy-volume-floor",
                    availableBalances = null,
                )

                val order = fixture.krakenService.executedOrders.single()
                order.volume.shouldBeEqualComparingTo(BigDecimal("0.16666666"))
                (order.volume.multiply(BigDecimal("6.00")) <= BigDecimal("1.00")).shouldBeTrue()
            }
        }
    }
}
