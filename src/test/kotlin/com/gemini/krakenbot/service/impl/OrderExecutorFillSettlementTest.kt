package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.OrderResult
import com.gemini.krakenbot.service.FakeKrakenService
import com.gemini.krakenbot.service.TradeHistoryService
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import java.time.Instant

class OrderExecutorFillSettlementTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    private val krakenService = FakeKrakenService()
    private val tradeHistoryService = mockk<TradeHistoryService>(relaxed = true)
    private val orderExecutor = OrderExecutorImpl(krakenService, tradeHistoryService)

    init {
        "sizes live buys from fill-confirmed net proceeds matched by order txid" {
            runTest {
                val sellTxid = "OID-FILL-1"
                krakenService.orderResultFactory = { pair, _, side, volume ->
                    OrderResult(
                        success = true,
                        pair = pair,
                        side = side,
                        volume = volume,
                        orderTxid = if (side == TestFixtures.SELL) sellTxid else null,
                    )
                }
                // Gross cost $100 − fee $1 → net $99; opening $100 → fill-confirmed $199.
                // Balance peek $199 agrees → buy budget 99% = $197.01 → vol 0.19701
                krakenService.tradeHistorySupplier = { _, _ ->
                    listOf(
                        TestFixtures.tradeRecord(
                            timestamp = Instant.now(),
                            pair = Asset.BTC_USD_PAIR,
                            side = "SELL",
                            symbol = Asset.BTC,
                            volume = BigDecimal("0.1"),
                            usdAmount = BigDecimal("100.00"),
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
                    TestFixtures.settings(dryRun = false),
                    actionLog = mutableListOf(),
                    cycleId = "cycle-fill-1",
                )

                krakenService.getTradeHistoryCallCount shouldBe 1
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
                        success = true,
                        pair = pair,
                        side = side,
                        volume = volume,
                        orderTxid = if (side == TestFixtures.SELL) sellTxid else null,
                    )
                }
                // Two legs: $50 − $0.50 fee each → net $49.50 × 2 = $99; opening $100 → $199.
                krakenService.tradeHistorySupplier = { _, _ ->
                    listOf(
                        TestFixtures.tradeRecord(
                            timestamp = Instant.now(),
                            pair = Asset.BTC_USD_PAIR,
                            side = "SELL",
                            symbol = Asset.BTC,
                            volume = BigDecimal("0.05"),
                            usdAmount = BigDecimal("50.00"),
                            price = BigDecimal("1000.00"),
                            fee = BigDecimal("0.50"),
                            orderTxid = sellTxid,
                        ),
                        TestFixtures.tradeRecord(
                            timestamp = Instant.now(),
                            pair = Asset.BTC_USD_PAIR,
                            side = "SELL",
                            symbol = Asset.BTC,
                            volume = BigDecimal("0.05"),
                            usdAmount = BigDecimal("50.00"),
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
                    TestFixtures.settings(dryRun = false),
                    actionLog = mutableListOf(),
                    cycleId = "cycle-multi-leg",
                )

                krakenService.getTradeHistoryCallCount shouldBe 1
                krakenService.getBalancesCallCount shouldBe 1
                krakenService.executedOrders.size shouldBe 2
                krakenService.executedOrders[1].side shouldBe TestFixtures.BUY
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
                        orderTxid = if (side == TestFixtures.SELL) sellTxid else null,
                    )
                }
                // Matching leg net $99 → fill-confirmed $199. Decoys would inflate well above that
                // if filters fail; peek is deliberately high so the min(fill, peek) cap cannot hide it.
                krakenService.tradeHistorySupplier = { _, _ ->
                    listOf(
                        TestFixtures.tradeRecord(
                            timestamp = Instant.now(),
                            pair = Asset.BTC_USD_PAIR,
                            side = "SELL",
                            symbol = Asset.BTC,
                            volume = BigDecimal("0.1"),
                            usdAmount = BigDecimal("100.00"),
                            price = BigDecimal("1000.00"),
                            fee = BigDecimal("1.00"),
                            orderTxid = sellTxid,
                        ),
                        TestFixtures.tradeRecord(
                            timestamp = Instant.now(),
                            pair = Asset.BTC_USD_PAIR,
                            side = "SELL",
                            symbol = Asset.BTC,
                            volume = BigDecimal("1.0"),
                            usdAmount = BigDecimal("5000.00"),
                            price = BigDecimal("5000.00"),
                            orderTxid = null,
                        ),
                        TestFixtures.tradeRecord(
                            timestamp = Instant.now(),
                            pair = Asset.BTC_USD_PAIR,
                            side = "SELL",
                            symbol = Asset.BTC,
                            volume = BigDecimal("1.0"),
                            usdAmount = BigDecimal("5000.00"),
                            success = false,
                            price = BigDecimal("5000.00"),
                            orderTxid = sellTxid,
                        ),
                        TestFixtures.tradeRecord(
                            timestamp = Instant.now(),
                            pair = Asset.ETH_USD_PAIR,
                            side = "BUY",
                            symbol = Asset.ETH,
                            volume = BigDecimal("1.0"),
                            usdAmount = BigDecimal("5000.00"),
                            price = BigDecimal("5000.00"),
                            orderTxid = sellTxid,
                        ),
                        TestFixtures.tradeRecord(
                            timestamp = Instant.now(),
                            pair = Asset.BTC_USD_PAIR,
                            side = "SELL",
                            symbol = Asset.BTC,
                            volume = BigDecimal("1.0"),
                            usdAmount = BigDecimal("5000.00"),
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
                    TestFixtures.settings(dryRun = false),
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
                        side == TestFixtures.SELL && pair == Asset.BTC_USD_PAIR ->
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
                        orderTxid = if (side == TestFixtures.SELL) sellTxid else null,
                    )
                }
                krakenService.tradeHistorySupplier = { _, _ ->
                    listOf(
                        TestFixtures.tradeRecord(
                            timestamp = Instant.now(),
                            pair = Asset.BTC_USD_PAIR,
                            side = "SELL",
                            symbol = Asset.BTC,
                            volume = BigDecimal("0.1"),
                            usdAmount = BigDecimal("100.00"),
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
                    TestFixtures.settings(dryRun = false),
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
                        orderTxid = if (side == TestFixtures.SELL) "OID-MISSING" else null,
                    )
                }
                krakenService.tradeHistorySupplier = { _, _ ->
                    listOf(
                        TestFixtures.tradeRecord(
                            timestamp = Instant.now(),
                            pair = Asset.BTC_USD_PAIR,
                            side = "SELL",
                            symbol = Asset.BTC,
                            volume = BigDecimal("0.1"),
                            usdAmount = BigDecimal("100.00"),
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
                    TestFixtures.settings(dryRun = false),
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
                        orderTxid = if (side == TestFixtures.SELL) "OID-CURRENT" else null,
                    )
                }
                // Only a prior-cycle fill is visible; current OID never appears → fail-closed abort.
                krakenService.tradeHistorySupplier = { _, _ ->
                    listOf(
                        TestFixtures.tradeRecord(
                            timestamp = Instant.now(),
                            pair = Asset.BTC_USD_PAIR,
                            side = "SELL",
                            symbol = Asset.BTC,
                            volume = BigDecimal("1.0"),
                            usdAmount = BigDecimal("5000.00"),
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
                    TestFixtures.settings(dryRun = false),
                    actionLog = mutableListOf(),
                )

                krakenService.executedOrders.size shouldBe 1
                krakenService.executedOrders.single().side shouldBe TestFixtures.SELL
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
                        orderTxid = if (side == TestFixtures.SELL) sellTxid else null,
                    )
                }
                val padding =
                    List(pageSize) { idx ->
                        TestFixtures.tradeRecord(
                            timestamp = Instant.now().minusSeconds(idx.toLong()),
                            pair = Asset.ETH_USD_PAIR,
                            side = "BUY",
                            symbol = Asset.ETH,
                            volume = BigDecimal("0.01"),
                            usdAmount = BigDecimal("10.00"),
                            price = BigDecimal("1000.00"),
                            orderTxid = "OID-PAD-$idx",
                        )
                    }
                val matchingFill =
                    TestFixtures.tradeRecord(
                        timestamp = Instant.now(),
                        pair = Asset.BTC_USD_PAIR,
                        side = "SELL",
                        symbol = Asset.BTC,
                        volume = BigDecimal("0.1"),
                        usdAmount = BigDecimal("100.00"),
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
                    TestFixtures.settings(dryRun = false),
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
                    TestFixtures.tradeRecord(
                        timestamp = Instant.now(),
                        pair = Asset.BTC_USD_PAIR,
                        side = "SELL",
                        symbol = Asset.BTC,
                        volume = BigDecimal("0.10"),
                        usdAmount = BigDecimal("100.00"),
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
                        orderTxid = if (side == TestFixtures.SELL) sellTxid else null,
                    )
                }
                // History overstates proceeds vs the $100 sell intent.
                krakenService.tradeHistorySupplier = { _, _ ->
                    listOf(
                        TestFixtures.tradeRecord(
                            timestamp = Instant.now(),
                            pair = Asset.BTC_USD_PAIR,
                            side = "SELL",
                            symbol = Asset.BTC,
                            volume = BigDecimal("0.5"),
                            usdAmount = BigDecimal("500.00"),
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
                    TestFixtures.settings(dryRun = false),
                    actionLog = mutableListOf(),
                )

                // Uncapped fill would be $600; projected cap $200 → buy vol 0.198
                krakenService.executedOrders.size shouldBe 2
                krakenService.executedOrders[1].volume.shouldBeEqualComparingTo(BigDecimal("0.198"))
            }
        }
    }
}
