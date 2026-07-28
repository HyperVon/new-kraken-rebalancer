package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.OrderResult
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
                val settings = Settings(
                    loopDelaySeconds = 0L,
                    deviationTriggerPercent = 2.0,
                    dustThresholdUSD = 1.0,
                    dryRun = true,
                    fiatMaxDrawdown = 0.0,
                    fiatDeploymentExponent = 1.0,
                )

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

        "should cap buys that fit under cash but exceed the 99% reserve" {
            runTest {
                krakenService.orderResultFactory = { pair, _, side, volume ->
                    OrderResult(success = true, pair = pair, side = side, volume = volume)
                }

                val buyOrders = mapOf(Asset.ETH to BigDecimal("995.00"))
                val sellOrders = emptyMap<String, BigDecimal>()
                val currentValuesUSD = mapOf(Asset.USD to BigDecimal("1000.00"))
                val prices = mapOf(Asset.ETH to BigDecimal("1000.00"))
                val settings = Settings(
                    loopDelaySeconds = 0L,
                    deviationTriggerPercent = 2.0,
                    dustThresholdUSD = 1.0,
                    dryRun = true,
                    fiatMaxDrawdown = 0.0,
                    fiatDeploymentExponent = 1.0,
                )

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
                val settings = Settings(
                    loopDelaySeconds = 0L,
                    deviationTriggerPercent = 2.0,
                    dustThresholdUSD = 1.0,
                    dryRun = true,
                    fiatMaxDrawdown = 0.0,
                    fiatDeploymentExponent = 1.0,
                )

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
                val settings = Settings(
                    loopDelaySeconds = 0L,
                    deviationTriggerPercent = 2.0,
                    dustThresholdUSD = 1.0,
                    dryRun = true,
                    fiatMaxDrawdown = 0.0,
                    fiatDeploymentExponent = 1.0,
                )

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

        "should execute sell at exact dust threshold boundary" {
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
                    Settings(
                        loopDelaySeconds = 0L,
                        deviationTriggerPercent = 2.0,
                        dustThresholdUSD = 1.0,
                        dryRun = true,
                        fiatMaxDrawdown = 0.0,
                        fiatDeploymentExponent = 1.0,
                    ),
                    actionLog = mutableListOf(),
                )

                krakenService.executedOrders.size shouldBe 1
                krakenService.executedOrders.single().side shouldBe "sell"
                krakenService.executedOrders.single().volume.shouldBeEqualComparingTo(BigDecimal("0.00002"))
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
                    Settings(
                        loopDelaySeconds = 0L,
                        deviationTriggerPercent = 0.0,
                        dustThresholdUSD = 0.0,
                        dryRun = true,
                        fiatMaxDrawdown = 0.0,
                        fiatDeploymentExponent = 1.0,
                    ),
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

        "CQ-12-L3: reports a capped sell that falls below the dust threshold" {
            runTest {
                val actionLog = mutableListOf<String>()
                orderExecutor.executeOrders(
                    buyOrders = emptyMap(),
                    sellOrders = mapOf(Asset.BTC to BigDecimal("0.01")),
                    currentValuesUSD = mapOf(Asset.USD to BigDecimal.ZERO, Asset.BTC to BigDecimal("0.01")),
                    prices = mapOf(Asset.BTC to BigDecimal("500000.00")),
                    settings =
                    Settings(
                        loopDelaySeconds = 0L,
                        deviationTriggerPercent = 0.0,
                        dustThresholdUSD = 0.007,
                        dryRun = true,
                    ),
                    actionLog = actionLog,
                    availableBalances = mapOf("XXBT" to BigDecimal("0.00000001")),
                )

                krakenService.executedOrders shouldBe emptyList()
                actionLog shouldBe listOf("Skipping dust sell for BTC ($0.01)")
            }
        }

        "should skip sell just below dust threshold" {
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
                    Settings(
                        loopDelaySeconds = 0L,
                        deviationTriggerPercent = 2.0,
                        dustThresholdUSD = 1.0,
                        dryRun = true,
                        fiatMaxDrawdown = 0.0,
                        fiatDeploymentExponent = 1.0,
                    ),
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
                    Settings(
                        loopDelaySeconds = 0L,
                        deviationTriggerPercent = 2.0,
                        dustThresholdUSD = 1.0,
                        dryRun = false,
                        fiatMaxDrawdown = 0.0,
                        fiatDeploymentExponent = 1.0,
                    ),
                    actionLog = mutableListOf(),
                )

                krakenService.getBalancesCallCount shouldBe 3
                krakenService.executedOrders.size shouldBe 1
                krakenService.executedOrders.single().side shouldBe "sell"
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
                    Settings(
                        loopDelaySeconds = 0L,
                        deviationTriggerPercent = 2.0,
                        dustThresholdUSD = 1.0,
                        dryRun = false,
                        fiatMaxDrawdown = 0.0,
                        fiatDeploymentExponent = 1.0,
                    ),
                    actionLog = mutableListOf(),
                )

                krakenService.getBalancesCallCount shouldBe 3
                krakenService.executedOrders.size shouldBe 2
                krakenService.executedOrders[1].side shouldBe "buy"
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
                    Settings(
                        loopDelaySeconds = 0L,
                        deviationTriggerPercent = 2.0,
                        dustThresholdUSD = 1.0,
                        dryRun = true,
                        fiatMaxDrawdown = 0.0,
                        fiatDeploymentExponent = 1.0,
                    ),
                    actionLog = mutableListOf(),
                )

                krakenService.getBalancesCallCount shouldBe 0
                krakenService.executedOrders.size shouldBe 2
                krakenService.executedOrders[0].side shouldBe "sell"
                krakenService.executedOrders[1].side shouldBe "buy"
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
                    Settings(
                        loopDelaySeconds = 0L,
                        deviationTriggerPercent = 2.0,
                        dustThresholdUSD = 1.0,
                        dryRun = false,
                        fiatMaxDrawdown = 0.0,
                        fiatDeploymentExponent = 1.0,
                    ),
                    actionLog = mutableListOf(),
                )

                krakenService.getBalancesCallCount shouldBe 1
                krakenService.executedOrders.size shouldBe 2
                krakenService.executedOrders[1].side shouldBe "buy"
                // Buy budget = 99% of observed $190 = $188.10 → volume 0.1881
                krakenService.executedOrders[1].volume.shouldBeEqualComparingTo(BigDecimal("0.1881"))
            }
        }

        "should not send a zero-volume sell when dustThresholdUSD is 0 and amount is 0" {
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
                    Settings(
                        loopDelaySeconds = 0L,
                        deviationTriggerPercent = 2.0,
                        dustThresholdUSD = 0.0,
                        dryRun = true,
                        fiatMaxDrawdown = 0.0,
                        fiatDeploymentExponent = 1.0,
                    ),
                    actionLog = mutableListOf(),
                )

                krakenService.executedOrders shouldBe emptyList()
            }
        }

        "should not send a zero-volume buy when budget trims cost to 0 with dustThresholdUSD 0" {
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
                    Settings(
                        loopDelaySeconds = 0L,
                        deviationTriggerPercent = 2.0,
                        dustThresholdUSD = 0.0,
                        dryRun = true,
                        fiatMaxDrawdown = 0.0,
                        fiatDeploymentExponent = 1.0,
                    ),
                    actionLog = mutableListOf(),
                )

                krakenService.executedOrders.size shouldBe 1
                krakenService.executedOrders.single().side shouldBe "buy"
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
                    Settings(
                        loopDelaySeconds = 0L,
                        deviationTriggerPercent = 2.0,
                        dustThresholdUSD = 1.0,
                        dryRun = false,
                        fiatMaxDrawdown = 0.0,
                        fiatDeploymentExponent = 1.0,
                    ),
                    actionLog = mutableListOf(),
                )

                krakenService.getBalancesCallCount shouldBe 2
                krakenService.executedOrders.size shouldBe 2
                krakenService.executedOrders[1].side shouldBe "buy"
                krakenService.executedOrders[1].volume.shouldBeEqualComparingTo(BigDecimal("0.1881"))
            }
        }

        // CQ-3-15: failed buys must not shrink remainingBuyBudget / actualCash for later buys.
        "should not reduce cycle buy budget when a prior buy fails" {
            runTest {
                var buyAttempts = 0
                krakenService.orderResultFactory = { pair, _, side, volume ->
                    if (side == "buy") {
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
                    Settings(
                        loopDelaySeconds = 0L,
                        deviationTriggerPercent = 2.0,
                        dustThresholdUSD = 1.0,
                        dryRun = true,
                        fiatMaxDrawdown = 0.0,
                        fiatDeploymentExponent = 1.0,
                    ),
                    actionLog = actionLog,
                )

                krakenService.executedOrders.size shouldBe 2
                krakenService.executedOrders[0].side shouldBe "buy"
                krakenService.executedOrders[0].volume.shouldBeEqualComparingTo(BigDecimal("0.5"))
                krakenService.executedOrders[1].side shouldBe "buy"
                krakenService.executedOrders[1].volume.shouldBeEqualComparingTo(BigDecimal("0.5"))
                actionLog.any { it.contains("FAILED BUY ETH") } shouldBe true
            }
        }

        // CQ-3-24: budget trim that lands strictly below a positive dust threshold → skip, no order.
        "should skip budget-trimmed buy below positive dust threshold without sending an order" {
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
                    Settings(
                        loopDelaySeconds = 0L,
                        deviationTriggerPercent = 2.0,
                        dustThresholdUSD = 10.0,
                        dryRun = true,
                        fiatMaxDrawdown = 0.0,
                        fiatDeploymentExponent = 1.0,
                    ),
                    actionLog = actionLog,
                )

                krakenService.executedOrders.size shouldBe 1
                krakenService.executedOrders.single().side shouldBe "buy"
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
                    Settings(
                        loopDelaySeconds = 0L,
                        deviationTriggerPercent = 2.0,
                        dustThresholdUSD = 1.0,
                        dryRun = true,
                        fiatMaxDrawdown = 0.0,
                        fiatDeploymentExponent = 1.0,
                    ),
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
                    Settings(
                        loopDelaySeconds = 0L,
                        deviationTriggerPercent = 2.0,
                        dustThresholdUSD = 1.0,
                        dryRun = true,
                        fiatMaxDrawdown = 0.0,
                        fiatDeploymentExponent = 1.0,
                    ),
                    actionLog = mutableListOf(),
                    cycleId = cycleId,
                )

                krakenService.executedOrders.single().clOrdId shouldBe expectedBuyClOrdId
                OrderExecutorImpl.clientOrderId("", Asset.ETH, "buy") shouldBe null
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
                    Settings(
                        loopDelaySeconds = 0L,
                        deviationTriggerPercent = 2.0,
                        dustThresholdUSD = 1.0,
                        dryRun = true,
                        fiatMaxDrawdown = 0.0,
                        fiatDeploymentExponent = 1.0,
                    ),
                    actionLog = mutableListOf(),
                    cycleId = cycleId,
                )

                krakenService.executedOrders.single().side shouldBe "sell"
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
                    Settings(
                        loopDelaySeconds = 0L,
                        deviationTriggerPercent = 2.0,
                        dustThresholdUSD = 1.0,
                        dryRun = true,
                        fiatMaxDrawdown = 0.0,
                        fiatDeploymentExponent = 1.0,
                    ),
                    actionLog = mutableListOf(),
                    cycleId = "",
                )

                krakenService.executedOrders.single().clOrdId shouldBe null
            }
        }

        "sizes live buys from fill-confirmed net proceeds matched by order txid" {
            runTest {
                val sellTxid = "OID-FILL-1"
                krakenService.orderResultFactory = { pair, _, side, volume ->
                    OrderResult(
                        success = true,
                        pair = pair,
                        side = side,
                        volume = volume,
                        orderTxid = if (side == "sell") sellTxid else null,
                    )
                }
                // Gross cost $100 − fee $1 → net $99; opening $100 → fill-confirmed $199.
                // Balance peek $199 agrees → buy budget 99% = $197.01 → vol 0.19701
                krakenService.tradeHistorySupplier = { _, _ ->
                    listOf(
                        TradeRecord(
                            timestamp = Instant.now(),
                            pair = Asset.BTC_USD_PAIR,
                            side = "SELL",
                            symbol = Asset.BTC,
                            volume = BigDecimal("0.1"),
                            usdAmount = BigDecimal("100.00"),
                            success = true,
                            dryRun = false,
                            price = BigDecimal("1000.00"),
                            fee = BigDecimal("1.00"),
                            orderTxid = sellTxid,
                        ),
                    )
                }
                krakenService.balanceSupplier = { mapOf(Asset.USD to BigDecimal("199.00")) }

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
                    Settings(
                        loopDelaySeconds = 0L,
                        deviationTriggerPercent = 2.0,
                        dustThresholdUSD = 1.0,
                        dryRun = false,
                        fiatMaxDrawdown = 0.0,
                        fiatDeploymentExponent = 1.0,
                    ),
                    actionLog = mutableListOf(),
                    cycleId = "cycle-fill-1",
                )

                krakenService.getTradeHistoryCallCount shouldBe 1
                krakenService.getBalancesCallCount shouldBe 1
                krakenService.executedOrders.size shouldBe 2
                krakenService.executedOrders[1].side shouldBe "buy"
                krakenService.executedOrders[1].volume.shouldBeEqualComparingTo(BigDecimal("0.19701"))
            }
        }

        "sums multiple fill legs sharing the same sell orderTxid for buy budget" {
            runTest {
                val sellTxid = "OID-MULTI-LEG"
                krakenService.orderResultFactory = { pair, _, side, volume ->
                    OrderResult(
                        success = true,
                        pair = pair,
                        side = side,
                        volume = volume,
                        orderTxid = if (side == "sell") sellTxid else null,
                    )
                }
                // Two legs: $50 − $0.50 fee each → net $49.50 × 2 = $99; opening $100 → $199.
                krakenService.tradeHistorySupplier = { _, _ ->
                    listOf(
                        TradeRecord(
                            timestamp = Instant.now(),
                            pair = Asset.BTC_USD_PAIR,
                            side = "SELL",
                            symbol = Asset.BTC,
                            volume = BigDecimal("0.05"),
                            usdAmount = BigDecimal("50.00"),
                            success = true,
                            dryRun = false,
                            price = BigDecimal("1000.00"),
                            fee = BigDecimal("0.50"),
                            orderTxid = sellTxid,
                        ),
                        TradeRecord(
                            timestamp = Instant.now(),
                            pair = Asset.BTC_USD_PAIR,
                            side = "SELL",
                            symbol = Asset.BTC,
                            volume = BigDecimal("0.05"),
                            usdAmount = BigDecimal("50.00"),
                            success = true,
                            dryRun = false,
                            price = BigDecimal("1000.00"),
                            fee = BigDecimal("0.50"),
                            orderTxid = sellTxid,
                        ),
                    )
                }
                krakenService.balanceSupplier = { mapOf(Asset.USD to BigDecimal("199.00")) }

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
                    Settings(
                        loopDelaySeconds = 0L,
                        deviationTriggerPercent = 2.0,
                        dustThresholdUSD = 1.0,
                        dryRun = false,
                        fiatMaxDrawdown = 0.0,
                        fiatDeploymentExponent = 1.0,
                    ),
                    actionLog = mutableListOf(),
                    cycleId = "cycle-multi-leg",
                )

                krakenService.getTradeHistoryCallCount shouldBe 1
                krakenService.getBalancesCallCount shouldBe 1
                krakenService.executedOrders.size shouldBe 2
                krakenService.executedOrders[1].side shouldBe "buy"
                krakenService.executedOrders[1].volume.shouldBeEqualComparingTo(BigDecimal("0.19701"))
            }
        }

        "ignores non-matching trade history legs when summing fill proceeds" {
            runTest {
                val sellTxid = "OID-FILTER"
                krakenService.orderResultFactory = { pair, _, side, volume ->
                    OrderResult(
                        success = true,
                        pair = pair,
                        side = side,
                        volume = volume,
                        orderTxid = if (side == "sell") sellTxid else null,
                    )
                }
                // Matching leg net $99 → fill-confirmed $199. Decoys would inflate well above that
                // if filters fail; peek is deliberately high so the min(fill, peek) cap cannot hide it.
                krakenService.tradeHistorySupplier = { _, _ ->
                    listOf(
                        TradeRecord(
                            timestamp = Instant.now(),
                            pair = Asset.BTC_USD_PAIR,
                            side = "SELL",
                            symbol = Asset.BTC,
                            volume = BigDecimal("0.1"),
                            usdAmount = BigDecimal("100.00"),
                            success = true,
                            dryRun = false,
                            price = BigDecimal("1000.00"),
                            fee = BigDecimal("1.00"),
                            orderTxid = sellTxid,
                        ),
                        TradeRecord(
                            timestamp = Instant.now(),
                            pair = Asset.BTC_USD_PAIR,
                            side = "SELL",
                            symbol = Asset.BTC,
                            volume = BigDecimal("1.0"),
                            usdAmount = BigDecimal("5000.00"),
                            success = true,
                            dryRun = false,
                            price = BigDecimal("5000.00"),
                            orderTxid = null,
                        ),
                        TradeRecord(
                            timestamp = Instant.now(),
                            pair = Asset.BTC_USD_PAIR,
                            side = "SELL",
                            symbol = Asset.BTC,
                            volume = BigDecimal("1.0"),
                            usdAmount = BigDecimal("5000.00"),
                            success = false,
                            dryRun = false,
                            price = BigDecimal("5000.00"),
                            orderTxid = sellTxid,
                        ),
                        TradeRecord(
                            timestamp = Instant.now(),
                            pair = Asset.ETH_USD_PAIR,
                            side = "BUY",
                            symbol = Asset.ETH,
                            volume = BigDecimal("1.0"),
                            usdAmount = BigDecimal("5000.00"),
                            success = true,
                            dryRun = false,
                            price = BigDecimal("5000.00"),
                            orderTxid = sellTxid,
                        ),
                        TradeRecord(
                            timestamp = Instant.now(),
                            pair = Asset.BTC_USD_PAIR,
                            side = "SELL",
                            symbol = Asset.BTC,
                            volume = BigDecimal("1.0"),
                            usdAmount = BigDecimal("5000.00"),
                            success = true,
                            dryRun = false,
                            price = BigDecimal("5000.00"),
                            orderTxid = "OID-WRONG",
                        ),
                    )
                }
                krakenService.balanceSupplier = { mapOf(Asset.USD to BigDecimal("10000.00")) }

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
                    Settings(
                        loopDelaySeconds = 0L,
                        deviationTriggerPercent = 2.0,
                        dustThresholdUSD = 1.0,
                        dryRun = false,
                        fiatMaxDrawdown = 0.0,
                        fiatDeploymentExponent = 1.0,
                    ),
                    actionLog = mutableListOf(),
                )

                krakenService.getTradeHistoryCallCount shouldBe 1
                krakenService.getBalancesCallCount shouldBe 1
                krakenService.executedOrders.size shouldBe 2
                krakenService.executedOrders[1].volume.shouldBeEqualComparingTo(BigDecimal("0.19701"))
            }
        }

        "failed sell among multiple sells must not inflate projected cash for buys" {
            runTest {
                krakenService.orderResultFactory = { pair, _, side, volume ->
                    when {
                        side == "sell" && pair == Asset.BTC_USD_PAIR ->
                            OrderResult(
                                success = false,
                                pair = pair,
                                side = side,
                                volume = volume,
                                errorMessage = "simulated BTC sell failure",
                            )
                        else ->
                            OrderResult(success = true, pair = pair, side = side, volume = volume)
                    }
                }

                orderExecutor.executeOrders(
                    buyOrders = mapOf(Asset.SOL to BigDecimal("500.00")),
                    sellOrders =
                    linkedMapOf(
                        Asset.BTC to BigDecimal("100.00"),
                        Asset.ETH to BigDecimal("100.00"),
                    ),
                    currentValuesUSD = mapOf(Asset.USD to BigDecimal("100.00")),
                    prices =
                    mapOf(
                        Asset.BTC to BigDecimal("1000.00"),
                        Asset.ETH to BigDecimal("1000.00"),
                        Asset.SOL to BigDecimal("1000.00"),
                    ),
                    settings =
                    Settings(
                        loopDelaySeconds = 0L,
                        deviationTriggerPercent = 2.0,
                        dustThresholdUSD = 1.0,
                        dryRun = true,
                        fiatMaxDrawdown = 0.0,
                        fiatDeploymentExponent = 1.0,
                    ),
                    actionLog = mutableListOf(),
                )

                // Opening $100 + successful ETH sell $100 → $200 projected; 99% → $198 buy budget → 0.198 SOL.
                // If failed BTC sell were wrongly counted, budget would be $297 → 0.297.
                krakenService.executedOrders.size shouldBe 3
                krakenService.executedOrders[0].side shouldBe "sell"
                krakenService.executedOrders[0].pair shouldBe Asset.BTC_USD_PAIR
                krakenService.executedOrders[1].side shouldBe "sell"
                krakenService.executedOrders[1].pair shouldBe Asset.ETH_USD_PAIR
                krakenService.executedOrders[2].side shouldBe "buy"
                krakenService.executedOrders[2].volume.shouldBeEqualComparingTo(BigDecimal("0.198"))
                krakenService.getTradeHistoryCallCount shouldBe 0
                krakenService.getBalancesCallCount shouldBe 0
            }
        }

        "caps fill-confirmed cash to lower observed balance when history leads spendable USD" {
            runTest {
                val sellTxid = "OID-FILL-CAP"
                krakenService.orderResultFactory = { pair, _, side, volume ->
                    OrderResult(
                        success = true,
                        pair = pair,
                        side = side,
                        volume = volume,
                        orderTxid = if (side == "sell") sellTxid else null,
                    )
                }
                krakenService.tradeHistorySupplier = { _, _ ->
                    listOf(
                        TradeRecord(
                            timestamp = Instant.now(),
                            pair = Asset.BTC_USD_PAIR,
                            side = "SELL",
                            symbol = Asset.BTC,
                            volume = BigDecimal("0.1"),
                            usdAmount = BigDecimal("100.00"),
                            success = true,
                            dryRun = false,
                            price = BigDecimal("1000.00"),
                            fee = BigDecimal.ZERO,
                            orderTxid = sellTxid,
                        ),
                    )
                }
                // History says $200; balance only shows $150 → buy budget from $150.
                krakenService.balanceSupplier = { mapOf(Asset.USD to BigDecimal("150.00")) }

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
                    Settings(
                        loopDelaySeconds = 0L,
                        deviationTriggerPercent = 2.0,
                        dustThresholdUSD = 1.0,
                        dryRun = false,
                        fiatMaxDrawdown = 0.0,
                        fiatDeploymentExponent = 1.0,
                    ),
                    actionLog = mutableListOf(),
                )

                krakenService.executedOrders.size shouldBe 2
                krakenService.executedOrders[1].volume.shouldBeEqualComparingTo(BigDecimal("0.1485"))
            }
        }

        "falls back to balance poll when sell txids do not match trade history" {
            runTest {
                krakenService.orderResultFactory = { pair, _, side, volume ->
                    OrderResult(
                        success = true,
                        pair = pair,
                        side = side,
                        volume = volume,
                        orderTxid = if (side == "sell") "OID-MISSING" else null,
                    )
                }
                krakenService.tradeHistorySupplier = { _, _ ->
                    listOf(
                        TradeRecord(
                            timestamp = Instant.now(),
                            pair = Asset.BTC_USD_PAIR,
                            side = "SELL",
                            symbol = Asset.BTC,
                            volume = BigDecimal("0.1"),
                            usdAmount = BigDecimal("100.00"),
                            success = true,
                            dryRun = false,
                            price = BigDecimal("1000.00"),
                            orderTxid = "OID-OTHER-CYCLE",
                        ),
                    )
                }
                krakenService.balanceSupplier = { mapOf(Asset.USD to BigDecimal("190.00")) }

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
                    Settings(
                        loopDelaySeconds = 0L,
                        deviationTriggerPercent = 2.0,
                        dustThresholdUSD = 1.0,
                        dryRun = false,
                        fiatMaxDrawdown = 0.0,
                        fiatDeploymentExponent = 1.0,
                    ),
                    actionLog = mutableListOf(),
                )

                // Three fill polls (no match) then one early-accept balance poll at 95%.
                krakenService.getTradeHistoryCallCount shouldBe 3
                krakenService.getBalancesCallCount shouldBe 1
                krakenService.executedOrders.size shouldBe 2
                krakenService.executedOrders[1].volume.shouldBeEqualComparingTo(BigDecimal("0.1881"))
            }
        }

        "does not inflate buy budget from unmatched prior-cycle sell fills" {
            runTest {
                krakenService.orderResultFactory = { pair, _, side, volume ->
                    OrderResult(
                        success = true,
                        pair = pair,
                        side = side,
                        volume = volume,
                        orderTxid = if (side == "sell") "OID-CURRENT" else null,
                    )
                }
                // Only a prior-cycle fill is visible; current OID never appears → fail-closed abort.
                krakenService.tradeHistorySupplier = { _, _ ->
                    listOf(
                        TradeRecord(
                            timestamp = Instant.now(),
                            pair = Asset.BTC_USD_PAIR,
                            side = "SELL",
                            symbol = Asset.BTC,
                            volume = BigDecimal("1.0"),
                            usdAmount = BigDecimal("5000.00"),
                            success = true,
                            dryRun = false,
                            price = BigDecimal("5000.00"),
                            orderTxid = "OID-PRIOR",
                        ),
                    )
                }
                krakenService.balanceSupplier = { emptyMap() }

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
                    Settings(
                        loopDelaySeconds = 0L,
                        deviationTriggerPercent = 2.0,
                        dustThresholdUSD = 1.0,
                        dryRun = false,
                        fiatMaxDrawdown = 0.0,
                        fiatDeploymentExponent = 1.0,
                    ),
                    actionLog = mutableListOf(),
                )

                krakenService.executedOrders.size shouldBe 1
                krakenService.executedOrders.single().side shouldBe "sell"
            }
        }

        "sums fill proceeds across history pages when matching sell is on page 2" {
            runTest {
                val sellTxid = "OID-PAGE-2"
                val pageSize = OrderExecutorImpl.TRADE_HISTORY_PAGE_SIZE
                krakenService.orderResultFactory = { pair, _, side, volume ->
                    OrderResult(
                        success = true,
                        pair = pair,
                        side = side,
                        volume = volume,
                        orderTxid = if (side == "sell") sellTxid else null,
                    )
                }
                val padding =
                    List(pageSize) { idx ->
                        TradeRecord(
                            timestamp = Instant.now().minusSeconds(idx.toLong()),
                            pair = Asset.ETH_USD_PAIR,
                            side = "BUY",
                            symbol = Asset.ETH,
                            volume = BigDecimal("0.01"),
                            usdAmount = BigDecimal("10.00"),
                            success = true,
                            dryRun = false,
                            price = BigDecimal("1000.00"),
                            orderTxid = "OID-PAD-$idx",
                        )
                    }
                val matchingFill =
                    TradeRecord(
                        timestamp = Instant.now(),
                        pair = Asset.BTC_USD_PAIR,
                        side = "SELL",
                        symbol = Asset.BTC,
                        volume = BigDecimal("0.1"),
                        usdAmount = BigDecimal("100.00"),
                        success = true,
                        dryRun = false,
                        price = BigDecimal("1000.00"),
                        fee = BigDecimal("1.00"),
                        orderTxid = sellTxid,
                    )
                krakenService.tradeHistorySupplier = { _, offset ->
                    val all = padding + matchingFill
                    val start = offset ?: 0
                    all.drop(start).take(pageSize)
                }
                // Peek empty → cap to projected ($200); net fill would be $199.
                krakenService.balanceSupplier = { emptyMap() }

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
                    Settings(
                        loopDelaySeconds = 0L,
                        deviationTriggerPercent = 2.0,
                        dustThresholdUSD = 1.0,
                        dryRun = false,
                        fiatMaxDrawdown = 0.0,
                        fiatDeploymentExponent = 1.0,
                    ),
                    actionLog = mutableListOf(),
                )

                // Two history pages fetched on the first fill-poll attempt.
                (krakenService.getTradeHistoryCallCount >= 2) shouldBe true
                krakenService.executedOrders.size shouldBe 2
                // Net fill $99 + opening $100 = $199 → 99% budget → vol 0.19701
                krakenService.executedOrders[1].volume.shouldBeEqualComparingTo(BigDecimal("0.19701"))
            }
        }

        "CQ-12-L4: deduplicates shifted fill ids while retaining identical id-less legs" {
            runTest {
                val sellTxid = "OID-SHIFTED"
                val duplicateFill =
                    TradeRecord(
                        timestamp = Instant.now(),
                        pair = Asset.BTC_USD_PAIR,
                        side = "SELL",
                        symbol = Asset.BTC,
                        volume = BigDecimal("0.10"),
                        usdAmount = BigDecimal("100.00"),
                        success = true,
                        dryRun = false,
                        price = BigDecimal("1000.00"),
                        fee = BigDecimal("1.00"),
                        orderTxid = sellTxid,
                        tradeId = "T-SHIFTED",
                    )
                val padding =
                    List(OrderExecutorImpl.TRADE_HISTORY_PAGE_SIZE - 1) { index ->
                        duplicateFill.copy(
                            side = "BUY",
                            orderTxid = "OID-PADDING-$index",
                            tradeId = "T-PADDING-$index",
                        )
                    }
                val idLessLeg =
                    duplicateFill.copy(
                        volume = BigDecimal("0.01"),
                        usdAmount = BigDecimal("10.00"),
                        fee = BigDecimal.ZERO,
                        tradeId = null,
                    )
                krakenService.tradeHistorySupplier = { _, offset ->
                    when (offset ?: 0) {
                        0 -> listOf(duplicateFill) + padding
                        OrderExecutorImpl.TRADE_HISTORY_PAGE_SIZE ->
                            listOf(duplicateFill, idLessLeg, idLessLeg.copy())
                        else -> emptyList()
                    }
                }
                krakenService.balanceSupplier = { emptyMap() }
                krakenService.orderResultFactory = { pair, _, side, volume ->
                    OrderResult(
                        success = true,
                        pair = pair,
                        side = side,
                        volume = volume,
                        orderTxid = if (side == "sell") sellTxid else null,
                    )
                }

                orderExecutor.executeOrders(
                    buyOrders = mapOf(Asset.ETH to BigDecimal("500.00")),
                    sellOrders = mapOf(Asset.BTC to BigDecimal("500.00")),
                    currentValuesUSD = mapOf(Asset.USD to BigDecimal("100.00")),
                    prices =
                    mapOf(
                        Asset.BTC to BigDecimal("1000.00"),
                        Asset.ETH to BigDecimal("1000.00"),
                    ),
                    settings =
                    Settings(
                        loopDelaySeconds = 0L,
                        deviationTriggerPercent = 2.0,
                        dustThresholdUSD = 1.0,
                        dryRun = false,
                        fiatMaxDrawdown = 0.0,
                        fiatDeploymentExponent = 1.0,
                    ),
                    actionLog = mutableListOf(),
                )

                // Opening $100 + ($99 unique identified leg + two distinct $10 id-less legs) = $219.
                krakenService.executedOrders[1].volume.shouldBeEqualComparingTo(BigDecimal("0.21681"))
            }
        }

        "caps fill-confirmed cash to projected when balance peek is unavailable" {
            runTest {
                val sellTxid = "OID-OVERSTATED"
                krakenService.orderResultFactory = { pair, _, side, volume ->
                    OrderResult(
                        success = true,
                        pair = pair,
                        side = side,
                        volume = volume,
                        orderTxid = if (side == "sell") sellTxid else null,
                    )
                }
                // History overstates proceeds vs the $100 sell intent.
                krakenService.tradeHistorySupplier = { _, _ ->
                    listOf(
                        TradeRecord(
                            timestamp = Instant.now(),
                            pair = Asset.BTC_USD_PAIR,
                            side = "SELL",
                            symbol = Asset.BTC,
                            volume = BigDecimal("0.5"),
                            usdAmount = BigDecimal("500.00"),
                            success = true,
                            dryRun = false,
                            price = BigDecimal("1000.00"),
                            fee = BigDecimal.ZERO,
                            orderTxid = sellTxid,
                        ),
                    )
                }
                krakenService.balanceSupplier = { emptyMap() }

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
                    Settings(
                        loopDelaySeconds = 0L,
                        deviationTriggerPercent = 2.0,
                        dustThresholdUSD = 1.0,
                        dryRun = false,
                        fiatMaxDrawdown = 0.0,
                        fiatDeploymentExponent = 1.0,
                    ),
                    actionLog = mutableListOf(),
                )

                // Uncapped fill would be $600; projected cap $200 → buy vol 0.198
                krakenService.executedOrders.size shouldBe 2
                krakenService.executedOrders[1].volume.shouldBeEqualComparingTo(BigDecimal("0.198"))
            }
        }

        "ambiguous live failure remains unresolved and blocks the next cycle" {
            runTest {
                coEvery { tradeHistoryService.saveTrade(any()) } returns 42
                coEvery { tradeHistoryService.hasPendingSubmissions() } returnsMany listOf(false, true)
                krakenService.orderResultFactory = { pair, _, side, volume ->
                    OrderResult(
                        success = false,
                        pair = pair,
                        side = side,
                        volume = volume,
                        errorMessage = "response lost",
                        submissionUncertain = true,
                    )
                }
                val settings =
                    Settings(
                        loopDelaySeconds = 0L,
                        deviationTriggerPercent = 2.0,
                        dustThresholdUSD = 1.0,
                        dryRun = false,
                        fiatMaxDrawdown = 0.0,
                        fiatDeploymentExponent = 1.0,
                    )
                val values = mapOf(Asset.USD to BigDecimal("100.00"))
                val prices =
                    mapOf(
                        Asset.BTC to BigDecimal("1000.00"),
                        Asset.ETH to BigDecimal("1000.00"),
                    )

                repeat(2) {
                    orderExecutor.executeOrders(
                        buyOrders =
                        linkedMapOf(
                            Asset.BTC to BigDecimal("25.00"),
                            Asset.ETH to BigDecimal("25.00"),
                        ),
                        sellOrders = emptyMap(),
                        currentValuesUSD = values,
                        prices = prices,
                        settings = settings,
                        actionLog = mutableListOf(),
                        cycleId = "cycle-$it",
                    )
                }

                krakenService.executedOrders.size shouldBe 1
                coVerify {
                    tradeHistoryService.updateTrade(
                        any(),
                        match { it.id == 42 && it.submissionState == OrderSubmissionState.UNCERTAIN },
                    )
                }
            }
        }

        "CQ-12-2: live IOException marks the intent uncertain, rethrows, and blocks retry" {
            runTest {
                coEvery { tradeHistoryService.saveTrade(any()) } returns 51
                coEvery { tradeHistoryService.hasPendingSubmissions() } returnsMany listOf(false, true)
                val original = IOException("connection reset after submission")
                krakenService.executeOrderAction = { _, _, _, _ -> throw original }
                val settings =
                    Settings(
                        loopDelaySeconds = 0L,
                        deviationTriggerPercent = 2.0,
                        dustThresholdUSD = 1.0,
                        dryRun = false,
                        fiatMaxDrawdown = 0.0,
                        fiatDeploymentExponent = 1.0,
                    )

                shouldThrow<IOException> {
                    orderExecutor.executeOrders(
                        buyOrders = mapOf(Asset.BTC to BigDecimal("25.00")),
                        sellOrders = emptyMap(),
                        currentValuesUSD = mapOf(Asset.USD to BigDecimal("100.00")),
                        prices = mapOf(Asset.BTC to BigDecimal("1000.00")),
                        settings = settings,
                        actionLog = mutableListOf(),
                        cycleId = "live-io-cycle",
                    )
                } shouldBe original

                orderExecutor.executeOrders(
                    buyOrders = mapOf(Asset.BTC to BigDecimal("25.00")),
                    sellOrders = emptyMap(),
                    currentValuesUSD = mapOf(Asset.USD to BigDecimal("100.00")),
                    prices = mapOf(Asset.BTC to BigDecimal("1000.00")),
                    settings = settings,
                    actionLog = mutableListOf(),
                    cycleId = "blocked-cycle",
                )

                krakenService.executedOrders.size shouldBe 1
                coVerify {
                    tradeHistoryService.updateTrade(
                        any(),
                        match {
                            it.id == 51 &&
                                it.submissionState == OrderSubmissionState.UNCERTAIN &&
                                it.errorMessage == original.message
                        },
                    )
                }
            }
        }

        "CQ-12-2: live cancellation marks the intent uncertain and rethrows the same exception" {
            runTest {
                coEvery { tradeHistoryService.saveTrade(any()) } returns 52
                coEvery { tradeHistoryService.hasPendingSubmissions() } returns false
                val original = CancellationException("placement cancelled")
                krakenService.executeOrderAction = { _, _, _, _ -> throw original }
                val thrown =
                    shouldThrow<CancellationException> {
                        orderExecutor.executeOrders(
                            buyOrders = mapOf(Asset.BTC to BigDecimal("25.00")),
                            sellOrders = emptyMap(),
                            currentValuesUSD = mapOf(Asset.USD to BigDecimal("100.00")),
                            prices = mapOf(Asset.BTC to BigDecimal("1000.00")),
                            settings =
                            Settings(
                                loopDelaySeconds = 0L,
                                deviationTriggerPercent = 2.0,
                                dustThresholdUSD = 1.0,
                                dryRun = false,
                                fiatMaxDrawdown = 0.0,
                                fiatDeploymentExponent = 1.0,
                            ),
                            actionLog = mutableListOf(),
                            cycleId = "live-cancel-cycle",
                        )
                    }

                thrown shouldBe original
                coVerify {
                    tradeHistoryService.updateTrade(
                        any(),
                        match { it.id == 52 && it.submissionState == OrderSubmissionState.UNCERTAIN },
                    )
                }
            }
        }

        "CQ-12-2: journaling failure never masks the original submission exception" {
            runTest {
                coEvery { tradeHistoryService.saveTrade(any()) } returns 53
                coEvery { tradeHistoryService.hasPendingSubmissions() } returns false
                val original = IOException("connection failed after submission")
                val persistenceFailure = IllegalStateException("trade journal unavailable")
                coEvery { tradeHistoryService.updateTrade(any(), any()) } throws persistenceFailure
                krakenService.executeOrderAction = { _, _, _, _ -> throw original }

                val thrown =
                    shouldThrow<IOException> {
                        orderExecutor.executeOrders(
                            buyOrders = mapOf(Asset.BTC to BigDecimal("25.00")),
                            sellOrders = emptyMap(),
                            currentValuesUSD = mapOf(Asset.USD to BigDecimal("100.00")),
                            prices = mapOf(Asset.BTC to BigDecimal("1000.00")),
                            settings = Settings(loopDelaySeconds = 0L, deviationTriggerPercent = 2.0, dryRun = false),
                            actionLog = mutableListOf(),
                            cycleId = "live-journal-failure-cycle",
                        )
                    }

                thrown shouldBe original
                thrown.suppressed.single() shouldBe persistenceFailure
            }
        }

        "CQ-12-2: journaling failure never masks the original cancellation" {
            runTest {
                coEvery { tradeHistoryService.saveTrade(any()) } returns 54
                coEvery { tradeHistoryService.hasPendingSubmissions() } returns false
                val original = CancellationException("placement cancelled")
                val persistenceFailure = IllegalStateException("trade journal unavailable")
                coEvery { tradeHistoryService.updateTrade(any(), any()) } throws persistenceFailure
                krakenService.executeOrderAction = { _, _, _, _ -> throw original }

                val thrown =
                    shouldThrow<CancellationException> {
                        orderExecutor.executeOrders(
                            buyOrders = mapOf(Asset.BTC to BigDecimal("25.00")),
                            sellOrders = emptyMap(),
                            currentValuesUSD = mapOf(Asset.USD to BigDecimal("100.00")),
                            prices = mapOf(Asset.BTC to BigDecimal("1000.00")),
                            settings = Settings(loopDelaySeconds = 0L, deviationTriggerPercent = 2.0, dryRun = false),
                            actionLog = mutableListOf(),
                            cycleId = "live-journal-cancellation-cycle",
                        )
                    }

                thrown shouldBe original
                thrown.suppressed.single() shouldBe persistenceFailure
            }
        }

        "CQ-12-3: dry-run backend exception replaces pending text with the failure" {
            runTest {
                coEvery { tradeHistoryService.saveTrade(any()) } returns 61
                val original = IOException("dry-run backend unavailable")
                krakenService.executeOrderAction = { _, _, _, _ -> throw original }

                shouldThrow<IOException> {
                    orderExecutor.executeOrders(
                        buyOrders = mapOf(Asset.BTC to BigDecimal("25.00")),
                        sellOrders = emptyMap(),
                        currentValuesUSD = mapOf(Asset.USD to BigDecimal("100.00")),
                        prices = mapOf(Asset.BTC to BigDecimal("1000.00")),
                        settings =
                        Settings(
                            loopDelaySeconds = 0L,
                            deviationTriggerPercent = 2.0,
                            dustThresholdUSD = 1.0,
                            dryRun = true,
                            fiatMaxDrawdown = 0.0,
                            fiatDeploymentExponent = 1.0,
                        ),
                        actionLog = mutableListOf(),
                    )
                } shouldBe original

                coVerify {
                    tradeHistoryService.updateTrade(
                        any(),
                        match {
                            it.id == 61 && !it.success && it.dryRun &&
                                it.submissionState == null && it.errorMessage == original.message
                        },
                    )
                }
            }
        }

        "CQ-12-3: simulation backend exception persists a failed non-live estimate" {
            runTest {
                coEvery { tradeHistoryService.saveTrade(any()) } returns 62
                val original = IOException("emulator placement failed")
                krakenService.executeOrderAction = { _, _, _, _ -> throw original }

                shouldThrow<IOException> {
                    orderExecutor.executeOrders(
                        buyOrders = mapOf(Asset.BTC to BigDecimal("25.00")),
                        sellOrders = emptyMap(),
                        currentValuesUSD = mapOf(Asset.USD to BigDecimal("100.00")),
                        prices = mapOf(Asset.BTC to BigDecimal("1000.00")),
                        settings =
                        Settings(
                            loopDelaySeconds = 0L,
                            deviationTriggerPercent = 2.0,
                            dustThresholdUSD = 1.0,
                            dryRun = false,
                            fiatMaxDrawdown = 0.0,
                            fiatDeploymentExponent = 1.0,
                            simulation = true,
                        ),
                        actionLog = mutableListOf(),
                    )
                } shouldBe original

                coVerify {
                    tradeHistoryService.updateTrade(
                        any(),
                        match {
                            it.id == 62 && !it.success && !it.dryRun &&
                                it.submissionState == null && it.errorMessage == original.message
                        },
                    )
                }
            }
        }

        "simulation records never create a live submission gate" {
            runTest {
                coEvery { tradeHistoryService.saveTrade(any()) } returns 7
                orderExecutor.executeOrders(
                    buyOrders = mapOf(Asset.BTC to BigDecimal("50.00")),
                    sellOrders = emptyMap(),
                    currentValuesUSD = mapOf(Asset.USD to BigDecimal("100.00")),
                    prices = mapOf(Asset.BTC to BigDecimal("1000.00")),
                    settings =
                    Settings(
                        loopDelaySeconds = 0L,
                        deviationTriggerPercent = 2.0,
                        dustThresholdUSD = 1.0,
                        dryRun = false,
                        fiatMaxDrawdown = 0.0,
                        fiatDeploymentExponent = 1.0,
                        simulation = true,
                    ),
                    actionLog = mutableListOf(),
                    cycleId = "simulation-cycle",
                )

                coVerify(exactly = 0) { tradeHistoryService.hasPendingSubmissions() }
                coVerify { tradeHistoryService.saveTrade(match { it.submissionState == null }) }
            }
        }
    }
}
