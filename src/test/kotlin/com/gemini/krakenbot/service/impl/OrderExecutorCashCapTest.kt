package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.domain.OrderResult
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.OrderSide
import com.gemini.krakenbot.model.OrderSubmissionState
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.service.FakeKrakenService
import com.gemini.krakenbot.service.TradeHistoryService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.math.BigDecimal
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

class OrderExecutorCashCapTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    private val krakenService = FakeKrakenService()
    private val tradeHistoryService = mockk<TradeHistoryService>(relaxed = true)
    private val orderExecutor = OrderExecutorImpl(krakenService, tradeHistoryService)

    init {
        "should always cap buys to 99% of available cash even when cost equals cash" {
            runTest {
                krakenService.orderResultFactory = { pair, _, side, volume ->
                    OrderResult(success = true, pair = pair, side = side, volume = volume)
                }

                val buyOrders = mapOf(Asset.ETH to BigDecimal("1000.00"))
                val sellOrders = emptyMap<String, BigDecimal>()
                val currentValuesUSD = mapOf(Asset.USD to BigDecimal("1000.00"))
                val prices = mapOf(Asset.ETH to BigDecimal("2000.00"))
                val settings = TestFixtures.settings()

                orderExecutor.executeOrders(
                    buyOrders = buyOrders,
                    sellOrders = sellOrders,
                    currentValuesUSD = currentValuesUSD,
                    prices = prices,
                    settings = settings,
                    actionLog = mutableListOf(),
                )

                // Max affordable = 1000 * 0.99 = 990; volume = 990 / 2000 = 0.495
                krakenService.executedOrders.size shouldBe 1
                krakenService.executedOrders[0].volume.shouldBeEqualComparingTo(BigDecimal("0.495"))
            }
        }

        "should truncate a sub-dollar 99% reserve instead of spending all cash" {
            runTest {
                krakenService.orderResultFactory = { pair, _, side, volume ->
                    OrderResult(success = true, pair = pair, side = side, volume = volume)
                }

                orderExecutor.executeOrders(
                    buyOrders = mapOf(Asset.ETH to BigDecimal("0.50")),
                    sellOrders = emptyMap(),
                    currentValuesUSD = mapOf(Asset.USD to BigDecimal("0.50")),
                    prices = mapOf(Asset.ETH to BigDecimal.ONE),
                    settings = TestFixtures.settings(minimumOrderSizeUSD = 0.0),
                    actionLog = mutableListOf(),
                )

                krakenService.executedOrders.single().volume.shouldBeEqualComparingTo(BigDecimal("0.49"))
            }
        }

        "should cap buys that fit under cash but exceed the 99% reserve" {
            runTest {
                krakenService.orderResultFactory = { pair, _, side, volume ->
                    OrderResult(success = true, pair = pair, side = side, volume = volume)
                }

                val buyOrders = mapOf(Asset.ETH to BigDecimal("995.00"))
                val sellOrders = emptyMap<String, BigDecimal>()
                val currentValuesUSD = mapOf(Asset.USD to BigDecimal("1000.00"))
                val prices = mapOf(Asset.ETH to BigDecimal("1000.00"))
                val settings = TestFixtures.settings()

                orderExecutor.executeOrders(
                    buyOrders = buyOrders,
                    sellOrders = sellOrders,
                    currentValuesUSD = currentValuesUSD,
                    prices = prices,
                    settings = settings,
                    actionLog = mutableListOf(),
                )

                // Max affordable = 990; volume = 990 / 1000 = 0.99
                krakenService.executedOrders.size shouldBe 1
                krakenService.executedOrders[0].volume.shouldBeEqualComparingTo(BigDecimal("0.99"))
            }
        }

        "should not reduce buys that already sit under the 99% cash reserve" {
            runTest {
                krakenService.orderResultFactory = { pair, _, side, volume ->
                    OrderResult(success = true, pair = pair, side = side, volume = volume)
                }

                val buyOrders = mapOf(Asset.ETH to BigDecimal("500.00"))
                val sellOrders = emptyMap<String, BigDecimal>()
                val currentValuesUSD = mapOf(Asset.USD to BigDecimal("1000.00"))
                val prices = mapOf(Asset.ETH to BigDecimal("1000.00"))
                val settings = TestFixtures.settings()

                orderExecutor.executeOrders(
                    buyOrders = buyOrders,
                    sellOrders = sellOrders,
                    currentValuesUSD = currentValuesUSD,
                    prices = prices,
                    settings = settings,
                    actionLog = mutableListOf(),
                )

                krakenService.executedOrders.size shouldBe 1
                krakenService.executedOrders[0].volume.shouldBeEqualComparingTo(BigDecimal("0.5"))
            }
        }

        "should keep aggregate multi-buy spend within 99% of opening cash" {
            runTest {
                krakenService.orderResultFactory = { pair, _, side, volume ->
                    OrderResult(success = true, pair = pair, side = side, volume = volume)
                }

                // Two $500 buys against $1000 cash must spend at most $990 total (not $995).
                val buyOrders =
                    linkedMapOf(
                        Asset.ETH to BigDecimal("500.00"),
                        Asset.BTC to BigDecimal("500.00"),
                    )
                val sellOrders = emptyMap<String, BigDecimal>()
                val currentValuesUSD = mapOf(Asset.USD to BigDecimal("1000.00"))
                val prices =
                    mapOf(
                        Asset.ETH to BigDecimal("1000.00"),
                        Asset.BTC to BigDecimal("1000.00"),
                    )
                val settings = TestFixtures.settings()

                orderExecutor.executeOrders(
                    buyOrders = buyOrders,
                    sellOrders = sellOrders,
                    currentValuesUSD = currentValuesUSD,
                    prices = prices,
                    settings = settings,
                    actionLog = mutableListOf(),
                )

                krakenService.executedOrders.size shouldBe 2
                krakenService.executedOrders[0].volume.shouldBeEqualComparingTo(BigDecimal("0.5"))
                // Remaining cycle budget after first buy: 990 - 500 = 490 → 0.49 BTC
                krakenService.executedOrders[1].volume.shouldBeEqualComparingTo(BigDecimal("0.49"))
            }
        }

        "should execute sell at exact minimum order size boundary" {
            runTest {
                krakenService.orderResultFactory = { pair, _, side, volume ->
                    OrderResult(success = true, pair = pair, side = side, volume = volume)
                }

                orderExecutor.executeOrders(
                    buyOrders = emptyMap(),
                    sellOrders = mapOf(Asset.BTC to BigDecimal("1.00")),
                    currentValuesUSD =
                    mapOf(
                        Asset.USD to BigDecimal("1000.00"),
                        Asset.BTC to BigDecimal("600.00"),
                    ),
                    prices = mapOf(Asset.BTC to BigDecimal("50000.00")),
                    settings =
                    TestFixtures.settings(minimumOrderSizeUSD = 1.0),
                    actionLog = mutableListOf(),
                )

                krakenService.executedOrders.size shouldBe 1
                krakenService.executedOrders.single().side shouldBe TestFixtures.SELL
                krakenService.executedOrders.single().volume.shouldBeEqualComparingTo(BigDecimal("0.00002"))
            }
        }

        "should skip a buy when crypto flooring makes submitted notional smaller than dust" {
            runTest {
                krakenService.orderResultFactory = { pair, _, side, volume ->
                    OrderResult(success = true, pair = pair, side = side, volume = volume)
                }

                orderExecutor.executeOrders(
                    buyOrders = mapOf(Asset.BTC to BigDecimal("1.00")),
                    sellOrders = emptyMap(),
                    currentValuesUSD = mapOf(Asset.USD to BigDecimal("100.00")),
                    prices = mapOf(Asset.BTC to BigDecimal("48523.97")),
                    settings = TestFixtures.settings(minimumOrderSizeUSD = 1.0),
                    actionLog = mutableListOf(),
                )

                // $1.00 intent floors to 0.00002060 BTC, worth $0.999593782 below the $1 dust floor.
                krakenService.executedOrders shouldBe emptyList()
                coVerify(exactly = 0) { tradeHistoryService.saveTrade(any()) }
            }
        }

        "CQ-12-L3: caps a rounded zero-target liquidation to the available crypto balance" {
            runTest {
                coEvery { tradeHistoryService.saveTrade(any()) } returns 71
                orderExecutor.executeOrders(
                    buyOrders = mapOf(Asset.ETH to BigDecimal("0.01")),
                    sellOrders = mapOf(Asset.BTC to BigDecimal("0.01")),
                    currentValuesUSD =
                    mapOf(
                        Asset.USD to BigDecimal.ZERO,
                        Asset.BTC to BigDecimal("0.01"),
                    ),
                    prices =
                    mapOf(
                        Asset.BTC to BigDecimal("500000.00"),
                        Asset.ETH to BigDecimal.ONE,
                    ),
                    settings =
                    TestFixtures.settings(deviationTriggerPercent = 0.0, minimumOrderSizeUSD = 0.0),
                    actionLog = mutableListOf(),
                    availableBalances = mapOf("XXBT" to BigDecimal("0.00000001")),
                )

                // $0.01 / $500,000 rounds HALF_UP to 0.00000002, but only one satoshi is held.
                krakenService.executedOrders.single().volume.shouldBeEqualComparingTo(BigDecimal("0.00000001"))
                coVerify {
                    tradeHistoryService.updateTrade(
                        any(),
                        match { it.id == 71 && it.usdAmount.compareTo(BigDecimal("0.0050000000")) == 0 },
                    )
                }
                // The capped half-cent proceeds round to a $0.00 99% budget, so no buy is invented.
                krakenService.executedOrders.none { it.side == OrderSide.BUY.apiValue } shouldBe true
            }
        }

        "CQ-12-L3: reports a capped sell that falls below the minimum order size" {
            runTest {
                val actionLog = mutableListOf<String>()
                orderExecutor.executeOrders(
                    buyOrders = emptyMap(),
                    sellOrders = mapOf(Asset.BTC to BigDecimal("0.01")),
                    currentValuesUSD = mapOf(Asset.USD to BigDecimal.ZERO, Asset.BTC to BigDecimal("0.01")),
                    prices = mapOf(Asset.BTC to BigDecimal("500000.00")),
                    settings =
                    TestFixtures.settings(deviationTriggerPercent = 0.0, minimumOrderSizeUSD = 0.007),
                    actionLog = actionLog,
                    availableBalances = mapOf("XXBT" to BigDecimal("0.00000001")),
                )

                krakenService.executedOrders shouldBe emptyList()
                actionLog shouldBe listOf("Skipping dust sell for BTC ($0.01)")
            }
        }

        "should skip sell just below minimum order size" {
            runTest {
                krakenService.orderResultFactory = { pair, _, side, volume ->
                    OrderResult(success = true, pair = pair, side = side, volume = volume)
                }

                orderExecutor.executeOrders(
                    buyOrders = emptyMap(),
                    sellOrders = mapOf(Asset.BTC to BigDecimal("0.99")),
                    currentValuesUSD =
                    mapOf(
                        Asset.USD to BigDecimal("1000.00"),
                        Asset.BTC to BigDecimal("600.00"),
                    ),
                    prices = mapOf(Asset.BTC to BigDecimal("50000.00")),
                    settings =
                    TestFixtures.settings(),
                    actionLog = mutableListOf(),
                )

                krakenService.executedOrders shouldBe emptyList()
            }
        }

        "should abort live buys when no positive USD balance is observed after sells" {
            runTest {
                var balancePoll = 0
                krakenService.balanceSupplier = {
                    balancePoll++
                    when (balancePoll) {
                        1 -> error("Temporary balance failure")
                        2 -> emptyMap()
                        else -> mapOf(Asset.USD to BigDecimal.ZERO)
                    }
                }

                // dryRun=false takes the settle-poll path (up to 3 attempts). dry-run would size
                // buys from projected cash without calling getBalances.
                orderExecutor.executeOrders(
                    buyOrders = mapOf(Asset.ETH to BigDecimal("100.00")),
                    sellOrders = mapOf(Asset.BTC to BigDecimal("100.00")),
                    currentValuesUSD = mapOf(Asset.USD to BigDecimal("100.00")),
                    prices =
                    mapOf(
                        Asset.BTC to BigDecimal("1000.00"),
                        Asset.ETH to BigDecimal("1000.00"),
                    ),
                    settings =
                    TestFixtures.settings(dryRun = false),
                    actionLog = mutableListOf(),
                )

                krakenService.getBalancesCallCount shouldBe 3
                krakenService.executedOrders.size shouldBe 1
                krakenService.executedOrders.single().side shouldBe TestFixtures.SELL
            }
        }

        "should cap live buys using the best positive USD balance observed after sells" {
            runTest {
                val observedBalances =
                    listOf(
                        mapOf(Asset.USD to BigDecimal("50.00")),
                        mapOf(Asset.USD to BigDecimal("25.00")),
                        emptyMap(),
                    )
                var balancePoll = 0
                krakenService.balanceSupplier = { observedBalances[balancePoll++] }

                // Best positive poll is $50 (not last/$25); buy budget = 99% → $49.50 → vol 0.0495.
                orderExecutor.executeOrders(
                    buyOrders = mapOf(Asset.ETH to BigDecimal("100.00")),
                    sellOrders = mapOf(Asset.BTC to BigDecimal("100.00")),
                    currentValuesUSD = mapOf(Asset.USD to BigDecimal("100.00")),
                    prices =
                    mapOf(
                        Asset.BTC to BigDecimal("1000.00"),
                        Asset.ETH to BigDecimal("1000.00"),
                    ),
                    settings =
                    TestFixtures.settings(dryRun = false),
                    actionLog = mutableListOf(),
                )

                krakenService.getBalancesCallCount shouldBe 3
                krakenService.executedOrders.size shouldBe 2
                krakenService.executedOrders[1].side shouldBe TestFixtures.BUY
                krakenService.executedOrders[1].volume.shouldBeEqualComparingTo(BigDecimal("0.0495"))
            }
        }

        "dry-run sizes buys from projected cash without refreshing USD balances" {
            runTest {
                krakenService.orderResultFactory = { pair, _, side, volume ->
                    OrderResult(success = true, pair = pair, side = side, volume = volume)
                }

                // Opening cash $100 + dry-run sell $100 → projected $200; buy budget 99% = $198.
                orderExecutor.executeOrders(
                    buyOrders = mapOf(Asset.ETH to BigDecimal("500.00")),
                    sellOrders = mapOf(Asset.BTC to BigDecimal("100.00")),
                    currentValuesUSD = mapOf(Asset.USD to BigDecimal("100.00")),
                    prices =
                    mapOf(
                        Asset.BTC to BigDecimal("1000.00"),
                        Asset.ETH to BigDecimal("1000.00"),
                    ),
                    settings =
                    TestFixtures.settings(),
                    actionLog = mutableListOf(),
                )

                krakenService.getBalancesCallCount shouldBe 0
                krakenService.executedOrders.size shouldBe 2
                krakenService.executedOrders[0].side shouldBe TestFixtures.SELL
                krakenService.executedOrders[1].side shouldBe TestFixtures.BUY
                krakenService.executedOrders[1].volume.shouldBeEqualComparingTo(BigDecimal("0.198"))
            }
        }

        // Opening $100 + sell $100 → projected $200; early-accept threshold = 95% = $190.
        "should stop USD refresh early when balance reaches exactly 95% of projected" {
            runTest {
                krakenService.balanceSupplier = {
                    mapOf(Asset.USD to BigDecimal("190.00"))
                }

                orderExecutor.executeOrders(
                    buyOrders = mapOf(Asset.ETH to BigDecimal("200.00")),
                    sellOrders = mapOf(Asset.BTC to BigDecimal("100.00")),
                    currentValuesUSD = mapOf(Asset.USD to BigDecimal("100.00")),
                    prices =
                    mapOf(
                        Asset.BTC to BigDecimal("1000.00"),
                        Asset.ETH to BigDecimal("1000.00"),
                    ),
                    settings =
                    TestFixtures.settings(dryRun = false),
                    actionLog = mutableListOf(),
                )

                krakenService.getBalancesCallCount shouldBe 1
                krakenService.executedOrders.size shouldBe 2
                krakenService.executedOrders[1].side shouldBe TestFixtures.BUY
                // Buy budget = 99% of observed $190 = $188.10 → volume 0.1881
                krakenService.executedOrders[1].volume.shouldBeEqualComparingTo(BigDecimal("0.1881"))
            }
        }

        "should not send a zero-volume sell when minimumOrderSizeUSD is 0 and amount is 0" {
            runTest {
                krakenService.orderResultFactory = { pair, _, side, volume ->
                    OrderResult(success = true, pair = pair, side = side, volume = volume)
                }

                orderExecutor.executeOrders(
                    buyOrders = emptyMap(),
                    sellOrders = mapOf(Asset.BTC to BigDecimal.ZERO),
                    currentValuesUSD =
                    mapOf(
                        Asset.USD to BigDecimal("1000.00"),
                        Asset.BTC to BigDecimal("600.00"),
                    ),
                    prices = mapOf(Asset.BTC to BigDecimal("50000.00")),
                    settings =
                    TestFixtures.settings(minimumOrderSizeUSD = 0.0),
                    actionLog = mutableListOf(),
                )

                krakenService.executedOrders shouldBe emptyList()
            }
        }

        "should not send a zero-volume buy when budget trims cost to 0 with minimumOrderSizeUSD 0" {
            runTest {
                krakenService.orderResultFactory = { pair, _, side, volume ->
                    OrderResult(success = true, pair = pair, side = side, volume = volume)
                }

                // Opening cash $100; first buy consumes the whole 99% budget ($99),
                // second buy is trimmed to $0 → must be skipped, not sent as volume 0.
                orderExecutor.executeOrders(
                    buyOrders =
                    linkedMapOf(
                        Asset.ETH to BigDecimal("99.00"),
                        Asset.BTC to BigDecimal("50.00"),
                    ),
                    sellOrders = emptyMap(),
                    currentValuesUSD = mapOf(Asset.USD to BigDecimal("100.00")),
                    prices =
                    mapOf(
                        Asset.ETH to BigDecimal("1000.00"),
                        Asset.BTC to BigDecimal("1000.00"),
                    ),
                    settings =
                    TestFixtures.settings(minimumOrderSizeUSD = 0.0),
                    actionLog = mutableListOf(),
                )

                krakenService.executedOrders.size shouldBe 1
                krakenService.executedOrders.single().side shouldBe TestFixtures.BUY
                krakenService.executedOrders.single().volume.shouldBeEqualComparingTo(BigDecimal("0.099"))
            }
        }

        "should keep polling USD when below 95% then accept early once threshold is met" {
            runTest {
                val observedBalances =
                    listOf(
                        mapOf(Asset.USD to BigDecimal("189.99")),
                        mapOf(Asset.USD to BigDecimal("190.00")),
                    )
                var balancePoll = 0
                krakenService.balanceSupplier = { observedBalances[balancePoll++] }

                orderExecutor.executeOrders(
                    buyOrders = mapOf(Asset.ETH to BigDecimal("200.00")),
                    sellOrders = mapOf(Asset.BTC to BigDecimal("100.00")),
                    currentValuesUSD = mapOf(Asset.USD to BigDecimal("100.00")),
                    prices =
                    mapOf(
                        Asset.BTC to BigDecimal("1000.00"),
                        Asset.ETH to BigDecimal("1000.00"),
                    ),
                    settings =
                    TestFixtures.settings(dryRun = false),
                    actionLog = mutableListOf(),
                )

                krakenService.getBalancesCallCount shouldBe 2
                krakenService.executedOrders.size shouldBe 2
                krakenService.executedOrders[1].side shouldBe TestFixtures.BUY
                krakenService.executedOrders[1].volume.shouldBeEqualComparingTo(BigDecimal("0.1881"))
            }
        }

        // CQ-3-15: failed buys must not shrink remainingBuyBudget / actualCash for later buys.
        "should not reduce cycle buy budget when a prior buy fails" {
            runTest {
                var buyAttempts = 0
                krakenService.orderResultFactory = { pair, _, side, volume ->
                    if (side == TestFixtures.BUY) {
                        buyAttempts++
                        OrderResult(
                            success = buyAttempts > 1,
                            pair = pair,
                            side = side,
                            volume = volume,
                            errorMessage = if (buyAttempts == 1) "simulated buy failure" else null,
                        )
                    } else {
                        OrderResult(success = true, pair = pair, side = side, volume = volume)
                    }
                }

                // Opening $1000 → cycle budget $990. First $500 buy fails; second must still see $990.
                // If budget were wrongly reduced on failure, second buy would be trimmed to $490 (0.49).
                val actionLog = mutableListOf<String>()
                orderExecutor.executeOrders(
                    buyOrders =
                    linkedMapOf(
                        Asset.ETH to BigDecimal("500.00"),
                        Asset.BTC to BigDecimal("500.00"),
                    ),
                    sellOrders = emptyMap(),
                    currentValuesUSD = mapOf(Asset.USD to BigDecimal("1000.00")),
                    prices =
                    mapOf(
                        Asset.ETH to BigDecimal("1000.00"),
                        Asset.BTC to BigDecimal("1000.00"),
                    ),
                    settings =
                    TestFixtures.settings(),
                    actionLog = actionLog,
                )

                krakenService.executedOrders.size shouldBe 2
                krakenService.executedOrders[0].side shouldBe TestFixtures.BUY
                krakenService.executedOrders[0].volume.shouldBeEqualComparingTo(BigDecimal("0.5"))
                krakenService.executedOrders[1].side shouldBe TestFixtures.BUY
                krakenService.executedOrders[1].volume.shouldBeEqualComparingTo(BigDecimal("0.5"))
                actionLog.any { it.contains("FAILED BUY ETH") } shouldBe true
            }
        }

        // CQ-3-24: budget trim that lands strictly below a positive minimum order size → skip, no order.
        "should skip budget-trimmed buy below positive minimum order size without sending an order" {
            runTest {
                krakenService.orderResultFactory = { pair, _, side, volume ->
                    OrderResult(success = true, pair = pair, side = side, volume = volume)
                }

                // Opening $100 → cycle budget $99. First buy $90 leaves $9; second $50 trimmed to $9 < $10 dust.
                val actionLog = mutableListOf<String>()
                orderExecutor.executeOrders(
                    buyOrders =
                    linkedMapOf(
                        Asset.ETH to BigDecimal("90.00"),
                        Asset.BTC to BigDecimal("50.00"),
                    ),
                    sellOrders = emptyMap(),
                    currentValuesUSD = mapOf(Asset.USD to BigDecimal("100.00")),
                    prices =
                    mapOf(
                        Asset.ETH to BigDecimal("1000.00"),
                        Asset.BTC to BigDecimal("1000.00"),
                    ),
                    settings =
                    TestFixtures.settings(minimumOrderSizeUSD = 10.0),
                    actionLog = actionLog,
                )

                krakenService.executedOrders.size shouldBe 1
                krakenService.executedOrders.single().side shouldBe TestFixtures.BUY
                krakenService.executedOrders.single().volume.shouldBeEqualComparingTo(BigDecimal("0.09"))
                actionLog.any { it == "Skipping dust buy for BTC ($9.00)" } shouldBe true
            }
        }

        "passes cycle dryRun into executeOrder so mid-cycle config flips cannot go live" {
            runTest {
                krakenService.orderResultFactory = { pair, _, side, volume ->
                    OrderResult(success = true, pair = pair, side = side, volume = volume, dryRun = true)
                }

                orderExecutor.executeOrders(
                    buyOrders = mapOf(Asset.ETH to BigDecimal("100.00")),
                    sellOrders = emptyMap(),
                    currentValuesUSD = mapOf(Asset.USD to BigDecimal("1000.00")),
                    prices = mapOf(Asset.ETH to BigDecimal("2000.00")),
                    settings =
                    TestFixtures.settings(),
                    actionLog = mutableListOf(),
                )

                krakenService.executedOrders.single().dryRun shouldBe true
            }
        }

        "passes deterministic cl_ord_id derived from cycleId symbol and side" {
            runTest {
                val cycleId = "11111111-2222-3333-4444-555555555555"
                // Golden UUID.nameUUIDFromBytes("$cycleId|ETH|buy") — pins mapping, not just wiring.
                val expectedBuyClOrdId = "81d59c12-6abc-354a-87ac-c333585a6093"
                orderExecutor.executeOrders(
                    buyOrders = mapOf(Asset.ETH to BigDecimal("100.00")),
                    sellOrders = emptyMap(),
                    currentValuesUSD = mapOf(Asset.USD to BigDecimal("1000.00")),
                    prices = mapOf(Asset.ETH to BigDecimal("2000.00")),
                    settings =
                    TestFixtures.settings(),
                    actionLog = mutableListOf(),
                    cycleId = cycleId,
                )

                krakenService.executedOrders.single().clOrdId shouldBe expectedBuyClOrdId
                OrderExecutorImpl.clientOrderId("", Asset.ETH, TestFixtures.BUY) shouldBe null
            }
        }

        "passes deterministic cl_ord_id on sell path" {
            runTest {
                val cycleId = "11111111-2222-3333-4444-555555555555"
                val expectedSellClOrdId = "b7abd004-d57a-3c4a-a7a3-dbb08a450589"
                orderExecutor.executeOrders(
                    buyOrders = emptyMap(),
                    sellOrders = mapOf(Asset.BTC to BigDecimal("100.00")),
                    currentValuesUSD = mapOf(Asset.USD to BigDecimal("1000.00")),
                    prices = mapOf(Asset.BTC to BigDecimal("50000.00")),
                    settings =
                    TestFixtures.settings(),
                    actionLog = mutableListOf(),
                    cycleId = cycleId,
                )

                krakenService.executedOrders.single().side shouldBe TestFixtures.SELL
                krakenService.executedOrders.single().clOrdId shouldBe expectedSellClOrdId
            }
        }

        "omits cl_ord_id when cycleId is blank" {
            runTest {
                orderExecutor.executeOrders(
                    buyOrders = mapOf(Asset.ETH to BigDecimal("100.00")),
                    sellOrders = emptyMap(),
                    currentValuesUSD = mapOf(Asset.USD to BigDecimal("1000.00")),
                    prices = mapOf(Asset.ETH to BigDecimal("2000.00")),
                    settings =
                    TestFixtures.settings(),
                    actionLog = mutableListOf(),
                    cycleId = "",
                )

                krakenService.executedOrders.single().clOrdId shouldBe null
            }
        }
    }
}
