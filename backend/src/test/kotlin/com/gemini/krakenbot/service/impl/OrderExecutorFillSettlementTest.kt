package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.domain.OrderResult
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.ExchangeTradeFill
import com.gemini.krakenbot.model.ExchangeTradePage
import com.gemini.krakenbot.service.FakeKrakenService
import com.gemini.krakenbot.service.OrderIntentService
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal

@Suppress("unused")
class OrderExecutorFillSettlementTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    private val krakenService = FakeKrakenService()
    private val orderExecutor = OrderExecutorImpl(krakenService, mockk<OrderIntentService>(relaxed = true))

    init {
        "sizes live buys from fill-confirmed net proceeds matched by order txid" {
            runTest {
                val sellTxid = "OID-FILL-1"
                krakenService.orderResultFactory = { pair, _, side, volume ->
                    OrderResult(
                        pair = pair,
                        success = true,
                        side = side,
                        volume = volume,
                        orderTxid = if (side == TestFixtures.SELL) sellTxid else null,
                    )
                }
                // Gross cost $100 − fee $1 → net $99; opening $100 → fill-confirmed $199.
                // Balance peek $199 agrees → buy budget 99% = $197.01 → vol 0.19701
                krakenService.tradePageSupplier = pages(fill(sellTxid, cost = "100.00", fee = "1.00"))
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
                    TestFixtures.settings(dryRun = false),
                    actionLog = mutableListOf(),
                    cycleId = "cycle-fill-1",
                )

                krakenService.getTradePageCallCount shouldBe 1
                krakenService.getBalancesCallCount shouldBe 1
                krakenService.executedOrders.size shouldBe 2
                krakenService.executedOrders[1].side shouldBe TestFixtures.BUY
                krakenService.executedOrders[1].volume.shouldBeEqualComparingTo(BigDecimal("0.19701"))
            }
        }

        "sums multiple fill legs sharing the same sell orderTxid for buy budget" {
            runTest {
                val sellTxid = "OID-MULTI-LEG"
                krakenService.orderResultFactory = { pair, _, side, volume ->
                    OrderResult(
                        pair = pair,
                        success = true,
                        side = side,
                        volume = volume,
                        orderTxid = if (side == TestFixtures.SELL) sellTxid else null,
                    )
                }
                // Two legs: $50 − $0.50 fee each → net $49.50 × 2 = $99; opening $100 → $199.
                krakenService.tradePageSupplier = pages(
                    fill(sellTxid, cost = "50.00", fee = "0.50"),
                    fill(sellTxid, cost = "50.00", fee = "0.50"),
                )
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
                    TestFixtures.settings(dryRun = false),
                    actionLog = mutableListOf(),
                    cycleId = "cycle-multi-leg",
                )

                krakenService.getTradePageCallCount shouldBe 1
                krakenService.getBalancesCallCount shouldBe 1
                krakenService.executedOrders.size shouldBe 2
                krakenService.executedOrders[1].side shouldBe TestFixtures.BUY
                krakenService.executedOrders[1].volume.shouldBeEqualComparingTo(BigDecimal("0.19701"))
            }
        }

        "ignores non-matching trade fill legs when summing fill proceeds" {
            runTest {
                val sellTxid = "OID-FILTER"
                krakenService.orderResultFactory = { pair, _, side, volume ->
                    OrderResult(
                        pair = pair,
                        success = true,
                        side = side,
                        volume = volume,
                        orderTxid = if (side == TestFixtures.SELL) sellTxid else null,
                    )
                }
                // Matching leg net $99 → fill-confirmed $199. Decoys would inflate well above that
                // if filters fail; peek is deliberately high so the min(fill, peek) cap cannot hide it.
                krakenService.tradePageSupplier = pages(
                    fill(sellTxid, cost = "100.00", fee = "1.00"),
                    fill(null, cost = "5000.00"),
                    fill("OID-WRONG", cost = "5000.00"),
                )
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
                    TestFixtures.settings(dryRun = false),
                    actionLog = mutableListOf(),
                )

                krakenService.getTradePageCallCount shouldBe 1
                krakenService.getBalancesCallCount shouldBe 1
                krakenService.executedOrders.size shouldBe 2
                krakenService.executedOrders[1].volume.shouldBeEqualComparingTo(BigDecimal("0.19701"))
            }
        }

        "failed sell among multiple sells must not inflate projected cash for buys" {
            runTest {
                krakenService.orderResultFactory = { pair, _, side, volume ->
                    when {
                        side == TestFixtures.SELL && pair == Asset.BTC_USD_PAIR ->
                            OrderResult(
                                pair = pair,
                                success = false,
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
                    TestFixtures.settings(),
                    actionLog = mutableListOf(),
                )

                // Opening $100 + successful ETH sell $100 → $200 projected; 99% → $198 buy budget → 0.198 SOL.
                // If failed BTC sell were wrongly counted, budget would be $297 → 0.297.
                krakenService.executedOrders.size shouldBe 3
                krakenService.executedOrders[0].side shouldBe TestFixtures.SELL
                krakenService.executedOrders[0].pair shouldBe Asset.BTC_USD_PAIR
                krakenService.executedOrders[1].side shouldBe TestFixtures.SELL
                krakenService.executedOrders[1].pair shouldBe Asset.ETH_USD_PAIR
                krakenService.executedOrders[2].side shouldBe TestFixtures.BUY
                krakenService.executedOrders[2].volume.shouldBeEqualComparingTo(BigDecimal("0.198"))
                krakenService.getTradePageCallCount shouldBe 0
                krakenService.getBalancesCallCount shouldBe 0
            }
        }

        "caps fill-confirmed cash to lower observed balance when fills lead spendable USD" {
            runTest {
                val sellTxid = "OID-FILL-CAP"
                krakenService.orderResultFactory = { pair, _, side, volume ->
                    OrderResult(
                        pair = pair,
                        success = true,
                        side = side,
                        volume = volume,
                        orderTxid = if (side == TestFixtures.SELL) sellTxid else null,
                    )
                }
                krakenService.tradePageSupplier = pages(fill(sellTxid, cost = "100.00"))
                // Fill records report $200; balance only shows $150 → buy budget from $150.
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
                    TestFixtures.settings(dryRun = false),
                    actionLog = mutableListOf(),
                )

                krakenService.executedOrders.size shouldBe 2
                krakenService.executedOrders[1].volume.shouldBeEqualComparingTo(BigDecimal("0.1485"))
            }
        }

        "falls back to balance poll when sell txids do not match fill records" {
            runTest {
                krakenService.orderResultFactory = { pair, _, side, volume ->
                    OrderResult(
                        pair = pair,
                        success = true,
                        side = side,
                        volume = volume,
                        orderTxid = if (side == TestFixtures.SELL) "OID-MISSING" else null,
                    )
                }
                krakenService.tradePageSupplier = pages(fill("OID-OTHER-CYCLE", cost = "100.00"))
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
                    TestFixtures.settings(dryRun = false),
                    actionLog = mutableListOf(),
                )

                // Three fill polls (no match) then one early-accept balance poll at 95%.
                krakenService.getTradePageCallCount shouldBe 3
                krakenService.getBalancesCallCount shouldBe 1
                krakenService.executedOrders.size shouldBe 2
                krakenService.executedOrders[1].volume.shouldBeEqualComparingTo(BigDecimal("0.1881"))
            }
        }

        "falls back to balance poll when fill lookup throws" {
            runTest {
                val sellTxid = "OID-HISTORY-FAILURE"
                krakenService.orderResultFactory = { pair, _, side, volume ->
                    OrderResult(
                        pair = pair,
                        success = true,
                        side = side,
                        volume = volume,
                        orderTxid = if (side == TestFixtures.SELL) sellTxid else "OID-BUY",
                    )
                }
                krakenService.tradePageSupplier = { _, _, _, _ -> error("fill lookup unavailable") }
                krakenService.balanceSupplier = { mapOf(Asset.USD to BigDecimal("190.00")) }

                orderExecutor.executeOrders(
                    buyOrders = mapOf(Asset.ETH to BigDecimal("200.00")),
                    sellOrders = mapOf(Asset.BTC to BigDecimal("100.00")),
                    currentValuesUSD = mapOf(Asset.USD to BigDecimal("100.00")),
                    prices = mapOf(
                        Asset.BTC to BigDecimal("1000.00"),
                        Asset.ETH to BigDecimal("1000.00"),
                    ),
                    settings = TestFixtures.settings(dryRun = false),
                    actionLog = mutableListOf(),
                )

                krakenService.getTradePageCallCount shouldBe 3
                krakenService.getBalancesCallCount shouldBe 1
                krakenService.executedOrders.size shouldBe 2
                krakenService.executedOrders[1].volume.shouldBeEqualComparingTo(BigDecimal("0.1881"))
            }
        }

        "does not inflate buy budget from unmatched prior-cycle sell fills" {
            runTest {
                krakenService.orderResultFactory = { pair, _, side, volume ->
                    OrderResult(
                        pair = pair,
                        success = true,
                        side = side,
                        volume = volume,
                        orderTxid = if (side == TestFixtures.SELL) "OID-CURRENT" else null,
                    )
                }
                // Only a prior-cycle fill is visible; current OID never appears → fail-closed abort.
                krakenService.tradePageSupplier = pages(fill("OID-PRIOR", cost = "5000.00"))
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
                    TestFixtures.settings(dryRun = false),
                    actionLog = mutableListOf(),
                )

                krakenService.executedOrders.size shouldBe 1
                krakenService.executedOrders.single().side shouldBe TestFixtures.SELL
            }
        }

        "continues fill pagination when filtered page is short" {
            runTest {
                val sellTxid = "OID-PAGE-2"
                krakenService.orderResultFactory = { pair, _, side, volume ->
                    OrderResult(
                        pair = pair,
                        success = true,
                        side = side,
                        volume = volume,
                        orderTxid = if (side == TestFixtures.SELL) sellTxid else null,
                    )
                }
                val paddingFill = fill(orderTxid = "OID-PAD-0", cost = "10.00", side = "buy")
                val matchingFill =
                    fill(orderTxid = sellTxid, cost = "100.00", fee = "1.00", side = "sell")

                krakenService.tradePageSupplier = { _, _, cursor, _ ->
                    if (cursor == null) {
                        ExchangeTradePage(
                            entries = listOf(paddingFill),
                            nextCursor = "cursor-page-2",
                            valid = true,
                        )
                    } else {
                        ExchangeTradePage(
                            entries = listOf(matchingFill),
                            nextCursor = null,
                            valid = true,
                        )
                    }
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
                    TestFixtures.settings(dryRun = false),
                    actionLog = mutableListOf(),
                )

                // Two trade pages fetched on the first fill-poll attempt.
                (krakenService.getTradePageCallCount >= 2) shouldBe true
                krakenService.executedOrders.size shouldBe 2
                // Net fill $99 + opening $100 = $199 → 99% budget → vol 0.19701
                krakenService.executedOrders[1].volume.shouldBeEqualComparingTo(BigDecimal("0.19701"))
            }
        }

        "CQ-12-L4: deduplicates shifted fill ids while retaining identical id-less legs" {
            runTest {
                val sellTxid = "OID-SHIFTED"
                val duplicateFill = fill(
                    orderTxid = sellTxid,
                    cost = "100.00",
                    fee = "1.00",
                    tradeId = "T-SHIFTED",
                )
                val padding = List(5) { index ->
                    fill(
                        orderTxid = "OID-PADDING-$index",
                        cost = "10.00",
                        side = "buy",
                        tradeId = "T-PADDING-$index",
                    )
                }
                val idLessLeg1 = fill(
                    orderTxid = sellTxid,
                    cost = "10.00",
                    fee = "0.00",
                    tradeId = "",
                )
                val idLessLeg2 = fill(
                    orderTxid = sellTxid,
                    cost = "10.00",
                    fee = "0.00",
                    tradeId = "",
                )
                krakenService.tradePageSupplier = { _, _, cursor, _ ->
                    if (cursor == null) {
                        ExchangeTradePage(
                            entries = listOf(duplicateFill) + padding,
                            nextCursor = "cursor-page-2",
                            valid = true,
                        )
                    } else {
                        ExchangeTradePage(
                            entries = listOf(duplicateFill, idLessLeg1, idLessLeg2),
                            nextCursor = null,
                            valid = true,
                        )
                    }
                }
                // Peek empty → cap to projected ($200); net fill would be $199.
                krakenService.balanceSupplier = { emptyMap() }
                krakenService.orderResultFactory = { pair, _, side, volume ->
                    OrderResult(
                        pair = pair,
                        success = true,
                        side = side,
                        volume = volume,
                        orderTxid = if (side == TestFixtures.SELL) sellTxid else null,
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
                    TestFixtures.settings(dryRun = false),
                    actionLog = mutableListOf(),
                )

                // Opening $100 + ($99 unique identified leg + two distinct $10 id-less legs) = $219,
                // but the sell intent was $500 → projected $600, so the $219 fill total is far below
                // the 95% threshold ($570). The settle helper treats this as a truncated fill view and
                // falls through to the balance poll; the poll observes $0 (empty balance) and the buy
                // phase fails closed — only the sell was placed.
                krakenService.executedOrders.size shouldBe 1
                krakenService.executedOrders[0].side shouldBe TestFixtures.SELL
            }
        }

        "caps fill-confirmed cash to projected when balance peek is unavailable" {
            runTest {
                val sellTxid = "OID-OVERSTATED"
                krakenService.orderResultFactory = { pair, _, side, volume ->
                    OrderResult(
                        pair = pair,
                        success = true,
                        side = side,
                        volume = volume,
                        orderTxid = if (side == TestFixtures.SELL) sellTxid else null,
                    )
                }
                // Fill records overstate proceeds vs the $100 sell intent.
                krakenService.tradePageSupplier = pages(fill(sellTxid, cost = "500.00"))
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
                    TestFixtures.settings(dryRun = false),
                    actionLog = mutableListOf(),
                )

                // Uncapped fill would be $600; projected cap $200 → buy vol 0.198
                krakenService.executedOrders.size shouldBe 2
                krakenService.executedOrders[1].volume.shouldBeEqualComparingTo(BigDecimal("0.198"))
            }
        }

        "aborts buy execution when balance peek returns 0.00 spendable USD" {
            runTest {
                val sellTxid = "OID-ZERO-BALANCE"
                krakenService.orderResultFactory = { pair, _, side, volume ->
                    OrderResult(
                        pair = pair,
                        success = true,
                        side = side,
                        volume = volume,
                        orderTxid = if (side == TestFixtures.SELL) sellTxid else null,
                    )
                }
                krakenService.tradePageSupplier = pages(fill(sellTxid, cost = "100.00"))
                // Balance peek returns $0.00 (e.g. funds locked or deducted)
                krakenService.balanceSupplier = { mapOf(Asset.USD to BigDecimal.ZERO) }

                orderExecutor.executeOrders(
                    buyOrders = mapOf(Asset.ETH to BigDecimal("500.00")),
                    sellOrders = mapOf(Asset.BTC to BigDecimal("100.00")),
                    currentValuesUSD = mapOf(Asset.USD to BigDecimal("100.00")),
                    prices = mapOf(
                        Asset.BTC to BigDecimal("1000.00"),
                        Asset.ETH to BigDecimal("1000.00"),
                    ),
                    settings = TestFixtures.settings(dryRun = false),
                    actionLog = mutableListOf(),
                )

                // Sell executes, but buy is aborted fail-closed because spendable USD is $0.00.
                krakenService.executedOrders.size shouldBe 1
                krakenService.executedOrders.single().side shouldBe TestFixtures.SELL
            }
        }
    }

    private var nextFillSequence = 1

    private fun fill(
        orderTxid: String?,
        cost: String,
        fee: String = "0.00",
        tradeId: String? = null,
        volume: String = "0.10000000",
        price: String = "1000.00",
        feeCurrency: String? = Asset.USD,
        side: String = "sell",
    ): ExchangeTradeFill {
        val id = tradeId ?: "FILL-${nextFillSequence++}"
        return ExchangeTradeFill(
            tradeId = id,
            orderTxid = orderTxid,
            baseAsset = Asset.BTC,
            quoteCurrency = Asset.USD,
            feeCurrency = feeCurrency,
            type = side,
            price = price,
            cost = cost,
            fee = fee,
            volume = volume,
            hasValidTime = true,
            hasValidPrice = true,
            hasValidCost = true,
            hasValidFee = true,
            hasValidVolume = true,
            isValidRecord = true,
        )
    }

    private fun pages(vararg entries: ExchangeTradeFill): (Long?, Long?, String?, Int?) -> ExchangeTradePage =
        { _, _, _, _ ->
            ExchangeTradePage(
                entries = entries.toList(),
                nextCursor = null,
                valid = true,
            )
        }
}
