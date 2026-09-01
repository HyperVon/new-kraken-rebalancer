package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.TestFixtures.assetSnapshot
import com.gemini.krakenbot.model.ComparisonAvailability
import com.gemini.krakenbot.model.ComparisonConfidence
import com.gemini.krakenbot.model.ComparisonUnavailableReason
import com.gemini.krakenbot.model.KrakenApiConstants
import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.model.TradeSource
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant

class RebalancerComparisonCalculatorTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    private val now = Instant.parse("2026-07-01T12:00:00Z")

    init {
        "shared baseline: first point has equal values and zero difference" {
            val snapshots = listOf(
                snapshot(now, "50000.00", mapOf("BTC" to assetRow("1.0", "50000.00", "50000.00"))),
                snapshot(now.plusSeconds(3600), "55000.00", mapOf("BTC" to assetRow("1.0", "55000.00", "55000.00"))),
            )

            val result = RebalancerComparisonCalculator.calculate(snapshots, emptyList())

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points.size shouldBe 2
            result.points[0].rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("50000.00")
            result.points[0].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("50000.00")
            result.points[0].differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
            result.points[0].differencePercent shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "outperformance: rebalancer ends above buy & hold" {
            val snapshots = listOf(
                snapshot(
                    now,
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                        "USD" to assetRow("50000.00", "1.0", "50000.00"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(3600),
                    "115000.00",
                    mapOf(
                        "BTC" to assetRow("1.5", "60000.00", "90000.00"),
                        "USD" to assetRow("25000.00", "1.0", "25000.00"),
                    ),
                ),
            )
            val trades = listOf(
                trade(
                    now.plusSeconds(1800),
                    side = "buy",
                    symbol = "BTC",
                    volume = "0.5",
                    usdAmount = "25000.00",
                    fee = "0",
                ),
            )

            val result = RebalancerComparisonCalculator.calculate(snapshots, trades)

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            (result.latestDifferenceUSD!! > BigDecimal.ZERO) shouldBe true
        }

        "underperformance: rebalancer ends below buy & hold" {
            val snapshots = listOf(
                snapshot(
                    now,
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("2.0", "50000.00", "100000.00"),
                        "USD" to assetRow("0", "1.0", "0"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(3600),
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "55000.00", "55000.00"),
                        "USD" to assetRow("45000.00", "1.0", "45000.00"),
                    ),
                ),
            )
            val trades = listOf(
                trade(
                    now.plusSeconds(1800),
                    side = "sell",
                    symbol = "BTC",
                    volume = "1.0",
                    usdAmount = "45000.00",
                    fee = "0",
                ),
            )

            val result = RebalancerComparisonCalculator.calculate(snapshots, trades)

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            (result.latestDifferenceUSD!! < BigDecimal.ZERO) shouldBe true
        }

        "range rebasing: suffix uses its own first snapshot as baseline" {
            val snapshots = listOf(
                snapshot(
                    now,
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                        "USD" to assetRow("50000.00", "1.0", "50000.00"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(3600),
                    "105000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "55000.00", "55000.00"),
                        "USD" to assetRow("50000.00", "1.0", "50000.00"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(7200),
                    "107500.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "57500.00", "57500.00"),
                        "USD" to assetRow("50000.00", "1.0", "50000.00"),
                    ),
                ),
            )
            val suffix = snapshots.takeLast(2)

            val result = RebalancerComparisonCalculator.calculate(suffix, emptyList())

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points.size shouldBe 2
            result.points[0].rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("105000.00")
            result.points[0].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("105000.00")
            result.points[0].differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "missing price: zero price returns MISSING_PRICE" {
            val snapshots = listOf(
                snapshot(now, "50000.00", mapOf("BTC" to assetRow("1.0", "50000.00", "50000.00"))),
                snapshot(now.plusSeconds(3600), "0", mapOf("BTC" to assetRow("1.0", "0", "0"))),
            )

            val result = RebalancerComparisonCalculator.calculate(snapshots, emptyList())

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.MISSING_PRICE
            result.baselineTimestamp shouldBe now
            result.unavailableAt shouldBe now.plusSeconds(3600)
        }

        "asset added: returns ASSET_UNIVERSE_CHANGED" {
            val snapshots = listOf(
                snapshot(now, "50000.00", mapOf("BTC" to assetRow("1.0", "50000.00", "50000.00"))),
                snapshot(
                    now.plusSeconds(3600),
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                        "ETH" to assetRow("10.0", "5000.00", "50000.00"),
                    ),
                ),
            )

            val result = RebalancerComparisonCalculator.calculate(snapshots, emptyList())

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.ASSET_UNIVERSE_CHANGED
        }

        "asset removed: returns ASSET_UNIVERSE_CHANGED" {
            val snapshots = listOf(
                snapshot(
                    now,
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                        "ETH" to assetRow("10.0", "5000.00", "50000.00"),
                    ),
                ),
                snapshot(now.plusSeconds(3600), "50000.00", mapOf("BTC" to assetRow("1.0", "50000.00", "50000.00"))),
            )

            val result = RebalancerComparisonCalculator.calculate(snapshots, emptyList())

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.ASSET_UNIVERSE_CHANGED
        }

        "unexplained USD credit fails closed" {
            val snapshots = listOf(
                snapshot(
                    now,
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                        "USD" to assetRow("50000.00", "1.0", "50000.00"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(3600),
                    "105000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                        "USD" to assetRow("55000.00", "1.0", "55000.00"),
                    ),
                ),
            )

            val result = RebalancerComparisonCalculator.calculate(snapshots, emptyList())

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.confidence shouldBe null
            result.unavailableReason shouldBe ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE
            result.unavailableAt shouldBe now.plusSeconds(3600)
        }

        "unexplained USD debit fails closed" {
            val snapshots = listOf(
                snapshot(
                    now,
                    "50000.00",
                    mapOf("USD" to assetRow("50000.00", "1.0", "50000.00")),
                ),
                snapshot(
                    now.plusSeconds(3600),
                    "40000.00",
                    mapOf("USD" to assetRow("40000.00", "1.0", "40000.00")),
                ),
            )

            val result = RebalancerComparisonCalculator.calculate(snapshots, emptyList())

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.confidence shouldBe null
            result.unavailableReason shouldBe ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE
            result.unavailableAt shouldBe now.plusSeconds(3600)
        }

        "unexplained crypto credit fails closed" {
            val snapshots = listOf(
                snapshot(
                    now,
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                        "USD" to assetRow("50000.00", "1.0", "50000.00"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(3600),
                    "105000.00",
                    mapOf(
                        "BTC" to assetRow("1.1", "50000.00", "55000.00"),
                        "USD" to assetRow("50000.00", "1.0", "50000.00"),
                    ),
                ),
            )

            val result = RebalancerComparisonCalculator.calculate(snapshots, emptyList())

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.confidence shouldBe null
            result.unavailableReason shouldBe ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE
            result.unavailableAt shouldBe now.plusSeconds(3600)
        }

        "unexplained crypto debit fails closed" {
            val snapshots = listOf(
                snapshot(
                    now,
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                        "USD" to assetRow("50000.00", "1.0", "50000.00"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(3600),
                    "95000.00",
                    mapOf(
                        "BTC" to assetRow("0.9", "50000.00", "45000.00"),
                        "USD" to assetRow("50000.00", "1.0", "50000.00"),
                    ),
                ),
            )

            val result = RebalancerComparisonCalculator.calculate(snapshots, emptyList())

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.confidence shouldBe null
            result.unavailableReason shouldBe ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE
            result.unavailableAt shouldBe now.plusSeconds(3600)
        }

        "first unexplained interval determines unavailableAt" {
            val snapshots = listOf(
                snapshot(
                    now,
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                        "USD" to assetRow("50000.00", "1.0", "50000.00"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(3600),
                    "105000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                        "USD" to assetRow("55000.00", "1.0", "55000.00"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(7200),
                    "110000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                        "USD" to assetRow("60000.00", "1.0", "60000.00"),
                    ),
                ),
            )

            val result = RebalancerComparisonCalculator.calculate(snapshots, emptyList())

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE
            result.unavailableAt shouldBe now.plusSeconds(3600)
        }

        "tracked buy: asset volume and USD/fee deltas match and remain available" {
            val snapshots = listOf(
                snapshot(
                    now,
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                        "USD" to assetRow("50000.00", "1.0", "50000.00"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(3600),
                    "130000.00",
                    mapOf(
                        "BTC" to assetRow("2.0", "50000.00", "100000.00"),
                        "USD" to assetRow("29974.00", "1.0", "29974.00"),
                    ),
                ),
            )
            val trades = listOf(
                trade(
                    now.plusSeconds(1800),
                    side = "buy",
                    symbol = "BTC",
                    volume = "1.0",
                    usdAmount = "20000.00",
                    fee = "26.00",
                ),
            )

            val result = RebalancerComparisonCalculator.calculate(snapshots, trades)

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
        }

        "fill timestamp may lag a snapshot when the complete balance change reconciles" {
            val snapshots = listOf(
                snapshot(
                    now,
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                        "USD" to assetRow("50000.00", "1.0", "50000.00"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(1),
                    "99974.00",
                    mapOf(
                        "BTC" to assetRow("1.5", "50000.00", "75000.00"),
                        "USD" to assetRow("24974.00", "1.0", "24974.00"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(2),
                    "99974.00",
                    mapOf(
                        "BTC" to assetRow("1.5", "50000.00", "75000.00"),
                        "USD" to assetRow("24974.00", "1.0", "24974.00"),
                    ),
                ),
            )
            val trades = listOf(
                trade(
                    timestamp = now.plusMillis(1250),
                    side = "buy",
                    symbol = "BTC",
                    volume = "0.5",
                    usdAmount = "25000.00",
                    fee = "26.00",
                    source = TradeSource.API_FILL,
                    cycleId = null,
                    tradeId = "MANUAL-FILL-1",
                    orderTxid = "MANUAL-ORDER-1",
                    price = "50000.00",
                ),
            )

            val result = RebalancerComparisonCalculator.calculate(snapshots, trades)

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("99974.00")
        }

        "multiple fills may lag a terminal snapshot when their combined change reconciles" {
            val snapshots = listOf(
                snapshot(
                    now,
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                        "USD" to assetRow("50000.00", "1.0", "50000.00"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(1),
                    "99974.00",
                    mapOf(
                        "BTC" to assetRow("1.7", "50000.00", "85000.00"),
                        "USD" to assetRow("15000.00", "1.0", "15000.00"),
                    ),
                ),
            )
            val trades = listOf(
                trade(
                    timestamp = now.plusMillis(1250),
                    side = "buy",
                    symbol = "BTC",
                    volume = "0.3",
                    usdAmount = "15000.00",
                    source = TradeSource.API_FILL,
                    cycleId = null,
                    tradeId = "MANUAL-FILL-3",
                    orderTxid = "MANUAL-ORDER-3",
                    price = "50000.00",
                ),
                trade(
                    timestamp = now.plusMillis(1500),
                    side = "buy",
                    symbol = "BTC",
                    volume = "0.4",
                    usdAmount = "20000.00",
                    source = TradeSource.API_FILL,
                    cycleId = null,
                    tradeId = "MANUAL-FILL-4",
                    orderTxid = "MANUAL-ORDER-4",
                    price = "50000.00",
                ),
            )

            val result = RebalancerComparisonCalculator.calculate(snapshots, trades)

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100000.00")
        }

        "fill outside the bounded snapshot skew remains unavailable" {
            val snapshots = listOf(
                snapshot(
                    now,
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                        "USD" to assetRow("50000.00", "1.0", "50000.00"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(1),
                    "99974.00",
                    mapOf(
                        "BTC" to assetRow("1.5", "50000.00", "75000.00"),
                        "USD" to assetRow("24974.00", "1.0", "24974.00"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(3),
                    "99974.00",
                    mapOf(
                        "BTC" to assetRow("1.5", "50000.00", "75000.00"),
                        "USD" to assetRow("24974.00", "1.0", "24974.00"),
                    ),
                ),
            )
            val trades = listOf(
                trade(
                    timestamp = now.plusMillis(2001),
                    side = "buy",
                    symbol = "BTC",
                    volume = "0.5",
                    usdAmount = "25000.00",
                    fee = "26.00",
                    source = TradeSource.API_FILL,
                    cycleId = null,
                    tradeId = "MANUAL-FILL-5",
                    orderTxid = "MANUAL-ORDER-5",
                    price = "50000.00",
                ),
            )

            val result = RebalancerComparisonCalculator.calculate(snapshots, trades)

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE
            result.unavailableAt shouldBe now.plusSeconds(1)
        }

        "ambiguous late-fill subsets remain unavailable" {
            val snapshots = listOf(
                snapshot(
                    now,
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                        "USD" to assetRow("50000.00", "1.0", "50000.00"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(1),
                    "99974.00",
                    mapOf(
                        "BTC" to assetRow("1.5", "50000.00", "75000.00"),
                        "USD" to assetRow("24974.00", "1.0", "24974.00"),
                    ),
                ),
            )
            val trades = listOf(
                trade(
                    timestamp = now.plusMillis(1250),
                    side = "buy",
                    symbol = "BTC",
                    volume = "0.5",
                    usdAmount = "25000.00",
                    fee = "26.00",
                    source = TradeSource.API_FILL,
                    cycleId = null,
                    tradeId = "MANUAL-FILL-6",
                    orderTxid = "MANUAL-ORDER-6",
                    price = "50000.00",
                ),
                trade(
                    timestamp = now.plusMillis(1500),
                    side = "buy",
                    symbol = "BTC",
                    volume = "0.5",
                    usdAmount = "25000.00",
                    fee = "26.00",
                    source = TradeSource.API_FILL,
                    cycleId = null,
                    tradeId = "MANUAL-FILL-7",
                    orderTxid = "MANUAL-ORDER-7",
                    price = "50000.00",
                ),
            )

            val result = RebalancerComparisonCalculator.calculate(snapshots, trades)

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE
            result.unavailableAt shouldBe now.plusSeconds(1)
        }

        "unknown terminal fill ownership remains unavailable" {
            val snapshots = listOf(
                snapshot(
                    now,
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                        "USD" to assetRow("50000.00", "1.0", "50000.00"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(1),
                    "99974.00",
                    mapOf(
                        "BTC" to assetRow("1.5", "50000.00", "75000.00"),
                        "USD" to assetRow("24974.00", "1.0", "24974.00"),
                    ),
                ),
            )
            val trades = listOf(
                trade(
                    timestamp = now.plusMillis(1250),
                    side = "buy",
                    symbol = "BTC",
                    volume = "0.5",
                    usdAmount = "25000.00",
                    fee = "26.00",
                    source = TradeSource.LEGACY_UNKNOWN,
                    cycleId = null,
                    price = "50000.00",
                ),
            )

            val result = RebalancerComparisonCalculator.calculate(snapshots, trades)

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.AMBIGUOUS_TRADE_OWNERSHIP
            result.unavailableAt shouldBe now.plusMillis(1250)
        }

        "valid terminal fill plus unknown terminal fill remains unavailable" {
            val snapshots = listOf(
                snapshot(
                    now,
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                        "USD" to assetRow("50000.00", "1.0", "50000.00"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(1),
                    "99974.00",
                    mapOf(
                        "BTC" to assetRow("1.5", "50000.00", "75000.00"),
                        "USD" to assetRow("24974.00", "1.0", "24974.00"),
                    ),
                ),
            )
            val trades = listOf(
                trade(
                    timestamp = now.plusMillis(1250),
                    side = "buy",
                    symbol = "BTC",
                    volume = "0.5",
                    usdAmount = "25000.00",
                    fee = "26.00",
                    source = TradeSource.API_FILL,
                    cycleId = null,
                    tradeId = "MANUAL-FILL-8",
                    orderTxid = "MANUAL-ORDER-8",
                    price = "50000.00",
                ),
                trade(
                    timestamp = now.plusMillis(1500),
                    side = "buy",
                    symbol = "BTC",
                    volume = "0.5",
                    usdAmount = "25000.00",
                    fee = "26.00",
                    source = TradeSource.LEGACY_UNKNOWN,
                    cycleId = null,
                    tradeId = null,
                    orderTxid = null,
                    price = "50000.00",
                ),
            )

            val result = RebalancerComparisonCalculator.calculate(snapshots, trades)

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.AMBIGUOUS_TRADE_OWNERSHIP
            result.unavailableAt shouldBe now.plusMillis(1500)
        }

        "valid terminal fill plus unsupported terminal fill remains unavailable" {
            val snapshots = listOf(
                snapshot(
                    now,
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                        "USD" to assetRow("50000.00", "1.0", "50000.00"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(1),
                    "99974.00",
                    mapOf(
                        "BTC" to assetRow("1.5", "50000.00", "75000.00"),
                        "USD" to assetRow("24974.00", "1.0", "24974.00"),
                    ),
                ),
            )
            val trades = listOf(
                trade(
                    timestamp = now.plusMillis(1250),
                    side = "buy",
                    symbol = "BTC",
                    volume = "0.5",
                    usdAmount = "25000.00",
                    fee = "26.00",
                    source = TradeSource.API_FILL,
                    cycleId = null,
                    tradeId = "MANUAL-FILL-9",
                    orderTxid = "MANUAL-ORDER-9",
                    price = "50000.00",
                ),
                trade(
                    timestamp = now.plusMillis(1500),
                    side = "hold",
                    symbol = "BTC",
                    volume = "0.5",
                    usdAmount = "25000.00",
                    source = TradeSource.API_FILL,
                    cycleId = null,
                    tradeId = "MANUAL-FILL-10",
                    orderTxid = "MANUAL-ORDER-10",
                    price = "50000.00",
                ),
            )

            val result = RebalancerComparisonCalculator.calculate(snapshots, trades)

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.UNSUPPORTED_TRADE
            result.unavailableAt shouldBe now.plusMillis(1500)
        }

        "tracked terminal candidate is validated even when snapshot balances already match" {
            val snapshots = listOf(
                snapshot(
                    now,
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                        "USD" to assetRow("50000.00", "1.0", "50000.00"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(1),
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                        "USD" to assetRow("50000.00", "1.0", "50000.00"),
                    ),
                ),
            )
            val trades = listOf(
                trade(
                    timestamp = now.plusMillis(1500),
                    side = "buy",
                    symbol = "BTC",
                    volume = "0.5",
                    usdAmount = "25000.00",
                    source = TradeSource.LEGACY_UNKNOWN,
                    cycleId = null,
                    price = "50000.00",
                ),
            )

            val result = RebalancerComparisonCalculator.calculate(snapshots, trades)

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.AMBIGUOUS_TRADE_OWNERSHIP
            result.unavailableAt shouldBe now.plusMillis(1500)
        }

        "unsupported tracked terminal candidate is validated even when snapshot balances already match" {
            val snapshots = listOf(
                snapshot(
                    now,
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                        "USD" to assetRow("50000.00", "1.0", "50000.00"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(1),
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                        "USD" to assetRow("50000.00", "1.0", "50000.00"),
                    ),
                ),
            )
            val trades = listOf(
                trade(
                    timestamp = now.plusMillis(1500),
                    side = "hold",
                    symbol = "BTC",
                    volume = "0.5",
                    usdAmount = "25000.00",
                    source = TradeSource.API_FILL,
                    cycleId = null,
                    tradeId = "MANUAL-FILL-13",
                    orderTxid = "MANUAL-ORDER-13",
                    price = "50000.00",
                ),
            )

            val result = RebalancerComparisonCalculator.calculate(snapshots, trades)

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.UNSUPPORTED_TRADE
            result.unavailableAt shouldBe now.plusMillis(1500)
        }

        "untracked terminal fill does not create a false ambiguity" {
            val snapshots = listOf(
                snapshot(
                    now,
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                        "USD" to assetRow("50000.00", "1.0", "50000.00"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(1),
                    "99974.00",
                    mapOf(
                        "BTC" to assetRow("1.5", "50000.00", "75000.00"),
                        "USD" to assetRow("24974.00", "1.0", "24974.00"),
                    ),
                ),
            )
            val trades = listOf(
                trade(
                    timestamp = now.plusMillis(1250),
                    side = "buy",
                    symbol = "BTC",
                    volume = "0.5",
                    usdAmount = "25000.00",
                    fee = "26.00",
                    source = TradeSource.API_FILL,
                    cycleId = null,
                    tradeId = "MANUAL-FILL-11",
                    orderTxid = "MANUAL-ORDER-11",
                    price = "50000.00",
                ),
                trade(
                    timestamp = now.plusMillis(1500),
                    side = "buy",
                    symbol = "DOGE",
                    volume = "100.0",
                    usdAmount = "10.00",
                    source = TradeSource.API_FILL,
                    cycleId = null,
                    tradeId = "MANUAL-FILL-12",
                    orderTxid = "MANUAL-ORDER-12",
                    price = "0.10",
                ),
            )

            val result = RebalancerComparisonCalculator.calculate(snapshots, trades)

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("99974.00")
        }

        "too many late-fill candidates fail closed" {
            val snapshots = listOf(
                snapshot(
                    now,
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                        "USD" to assetRow("50000.00", "1.0", "50000.00"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(1),
                    "99974.00",
                    mapOf(
                        "BTC" to assetRow("1.5", "50000.00", "75000.00"),
                        "USD" to assetRow("24974.00", "1.0", "24974.00"),
                    ),
                ),
            )
            val trades = List(9) { index ->
                trade(
                    timestamp = now.plusMillis(1250 + index * 50L),
                    side = "buy",
                    symbol = "BTC",
                    volume = "0.5",
                    usdAmount = "25000.00",
                    fee = "26.00",
                    source = TradeSource.API_FILL,
                    cycleId = null,
                    tradeId = "MANUAL-FILL-BURST-$index",
                    orderTxid = "MANUAL-ORDER-BURST-$index",
                    price = "50000.00",
                )
            }

            val result = RebalancerComparisonCalculator.calculate(snapshots, trades)

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE
            result.unavailableAt shouldBe now.plusSeconds(1)
        }

        "API fill replay uses precise price-volume notional instead of rounded cost" {
            val snapshots = listOf(
                snapshot(
                    now,
                    "1624.80166105",
                    mapOf(
                        "PAXG" to assetRow("0.20666117", "4361.24", "901.29896105"),
                        "USD" to assetRow("723.5027", "1.0", "723.5027"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(3600),
                    "1624.6756492776",
                    mapOf(
                        "PAXG" to assetRow("0.21147716", "4361.24", "922.3026492776"),
                        "USD" to assetRow("702.373", "1.0", "702.373"),
                    ),
                ),
            )
            val trades = listOf(
                trade(
                    timestamp = now.plusSeconds(1800),
                    side = "buy",
                    symbol = "PAXG",
                    volume = "0.00481599",
                    usdAmount = "21.00",
                    fee = "0.126",
                    source = TradeSource.API_FILL,
                    cycleId = null,
                    tradeId = "MANUAL-FILL-2",
                    orderTxid = "MANUAL-ORDER-2",
                    price = "4361.24",
                ),
            )

            val result = RebalancerComparisonCalculator.calculate(snapshots, trades)

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
        }

        "tracked sell: asset volume and USD/fee deltas match and remain available" {
            val snapshots = listOf(
                snapshot(
                    now,
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("2.0", "50000.00", "100000.00"),
                        "USD" to assetRow("0", "1.0", "0"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(3600),
                    "130000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                        "USD" to assetRow("79974.00", "1.0", "79974.00"),
                    ),
                ),
            )
            val trades = listOf(
                trade(
                    now.plusSeconds(1800),
                    side = "sell",
                    symbol = "BTC",
                    volume = "1.0",
                    usdAmount = "80000.00",
                    fee = "26.00",
                ),
            )

            val result = RebalancerComparisonCalculator.calculate(snapshots, trades)

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
        }

        "dry-run tracked balance change fails closed" {
            val snapshots = listOf(
                snapshot(
                    now,
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                        "USD" to assetRow("50000.00", "1.0", "50000.00"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(3600),
                    "200000.00",
                    mapOf(
                        "BTC" to assetRow("2.0", "50000.00", "100000.00"),
                        "USD" to assetRow("100000.00", "1.0", "100000.00"),
                    ),
                ),
            )
            val trades = listOf(
                trade(
                    now.plusSeconds(1800),
                    side = "buy",
                    symbol = "BTC",
                    volume = "1.0",
                    usdAmount = "20000.00",
                    fee = "26.00",
                    dryRun = true,
                ),
            )

            val result = RebalancerComparisonCalculator.calculate(snapshots, trades)

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.confidence shouldBe null
            result.unavailableReason shouldBe ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE
            result.unavailableAt shouldBe now.plusSeconds(3600)
        }

        "failed trade tracked balance change fails closed" {
            val snapshots = listOf(
                snapshot(
                    now,
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                        "USD" to assetRow("50000.00", "1.0", "50000.00"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(3600),
                    "200000.00",
                    mapOf(
                        "BTC" to assetRow("2.0", "50000.00", "100000.00"),
                        "USD" to assetRow("100000.00", "1.0", "100000.00"),
                    ),
                ),
            )
            val trades = listOf(
                trade(
                    now.plusSeconds(1800),
                    side = "buy",
                    symbol = "BTC",
                    volume = "1.0",
                    usdAmount = "20000.00",
                    fee = "26.00",
                    success = false,
                ),
            )

            val result = RebalancerComparisonCalculator.calculate(snapshots, trades)

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.confidence shouldBe null
            result.unavailableReason shouldBe ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE
            result.unavailableAt shouldBe now.plusSeconds(3600)
        }

        "unsupported side: returns UNSUPPORTED_TRADE" {
            val snapshots = listOf(
                snapshot(
                    now,
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                        "USD" to assetRow("50000.00", "1.0", "50000.00"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(3600),
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                        "USD" to assetRow("50000.00", "1.0", "50000.00"),
                    ),
                ),
            )
            val trades = listOf(
                trade(
                    now.plusSeconds(1800),
                    side = "unknown_side",
                    symbol = "BTC",
                    volume = "1.0",
                    usdAmount = "20000.00",
                    fee = "26.00",
                ),
            )

            val result = RebalancerComparisonCalculator.calculate(snapshots, trades)

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.UNSUPPORTED_TRADE
        }

        "insufficient history: zero and one snapshot both return INSUFFICIENT_SNAPSHOTS" {
            val zeroResult = RebalancerComparisonCalculator.calculate(emptyList(), emptyList())
            zeroResult.availability shouldBe ComparisonAvailability.UNAVAILABLE
            zeroResult.unavailableReason shouldBe ComparisonUnavailableReason.INSUFFICIENT_SNAPSHOTS

            val oneResult = RebalancerComparisonCalculator.calculate(
                listOf(snapshot(now, "100000.00", mapOf("BTC" to assetRow("1.0", "50000.00", "50000.00")))),
                emptyList(),
            )
            oneResult.availability shouldBe ComparisonAvailability.UNAVAILABLE
            oneResult.unavailableReason shouldBe ComparisonUnavailableReason.INSUFFICIENT_SNAPSHOTS
        }

        "non-positive denominator: returns NON_POSITIVE_BASELINE" {
            val snapshots = listOf(
                snapshot(now, "0", mapOf("USD" to assetRow("0", "1.0", "0"))),
                snapshot(now.plusSeconds(3600), "0", mapOf("USD" to assetRow("0", "1.0", "0"))),
            )

            val result = RebalancerComparisonCalculator.calculate(snapshots, emptyList())

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.NON_POSITIVE_BASELINE
        }

        "baseline mismatch: stored total differs from independent calculation by more than $0.01" {
            val snapshots = listOf(
                snapshot(
                    now,
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                        "USD" to assetRow("50001.00", "1.0", "50001.00"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(3600),
                    "110000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "55000.00", "55000.00"),
                        "USD" to assetRow("50001.00", "1.0", "50001.00"),
                    ),
                ),
            )

            val result = RebalancerComparisonCalculator.calculate(snapshots, emptyList())

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.BASELINE_MISMATCH
        }

        "rounding tolerance: small differences below scale do not create false cash-flow failure" {
            val snapshots = listOf(
                snapshot(
                    now,
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                        "USD" to assetRow("50000.00", "1.0", "50000.00"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(3600),
                    "130000.00",
                    mapOf(
                        "BTC" to assetRow("2.0", "50000.00", "100000.00"),
                        "USD" to assetRow("29974.004", "1.0", "29974.004"),
                    ),
                ),
            )
            val trades = listOf(
                trade(
                    now.plusSeconds(1800),
                    side = "buy",
                    symbol = "BTC",
                    volume = "1.0",
                    usdAmount = "20000.00",
                    fee = "26.00",
                ),
            )

            val result = RebalancerComparisonCalculator.calculate(snapshots, trades)

            result.availability shouldBe ComparisonAvailability.AVAILABLE
        }

        "out-of-order snapshots: sorts snapshots before calculating" {
            val snapshots = listOf(
                snapshot(now.plusSeconds(3600), "55000.00", mapOf("BTC" to assetRow("1.0", "55000.00", "55000.00"))),
                snapshot(now, "50000.00", mapOf("BTC" to assetRow("1.0", "50000.00", "50000.00"))),
            )

            val result = RebalancerComparisonCalculator.calculate(snapshots, emptyList())

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.baselineTimestamp shouldBe now
            result.points.first().timestamp shouldBe now
        }

        "trade for unknown symbol: skipped, comparison still available" {
            val snapshots = listOf(
                snapshot(
                    now,
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                        "USD" to assetRow("50000.00", "1.0", "50000.00"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(3600),
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                        "USD" to assetRow("50000.00", "1.0", "50000.00"),
                    ),
                ),
            )
            val trades = listOf(
                trade(
                    now.plusSeconds(1800),
                    side = "buy",
                    symbol = "UNKNOWN",
                    volume = "1.0",
                    usdAmount = "0",
                    fee = "0",
                ),
            )

            val result = RebalancerComparisonCalculator.calculate(snapshots, trades)

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.unavailableReason shouldBe null
        }

        "non-USD quoted trade: returns UNSUPPORTED_TRADE" {
            val snapshots = listOf(
                snapshot(
                    now,
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                        "USD" to assetRow("50000.00", "1.0", "50000.00"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(3600),
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                        "USD" to assetRow("50000.00", "1.0", "50000.00"),
                    ),
                ),
            )
            val trade = trade(
                now.plusSeconds(1800),
                side = "buy",
                symbol = "BTC",
                volume = "1.0",
                usdAmount = "20000.00",
            ).copy(pair = "BTCEUR")

            val result = RebalancerComparisonCalculator.calculate(snapshots, listOf(trade))

            result.unavailableReason shouldBe ComparisonUnavailableReason.UNSUPPORTED_TRADE
        }

        "negative trade economics: returns UNSUPPORTED_TRADE" {
            val snapshots = listOf(
                snapshot(
                    now,
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                        "USD" to assetRow("50000.00", "1.0", "50000.00"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(3600),
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                        "USD" to assetRow("50000.00", "1.0", "50000.00"),
                    ),
                ),
            )
            val trade = trade(
                now.plusSeconds(1800),
                side = "buy",
                symbol = "BTC",
                volume = "-1.0",
                usdAmount = "20000.00",
            )

            val result = RebalancerComparisonCalculator.calculate(snapshots, listOf(trade))

            result.unavailableReason shouldBe ComparisonUnavailableReason.UNSUPPORTED_TRADE
        }

        "later non-positive buy-and-hold value: returns NON_POSITIVE_BASELINE" {
            val snapshots = listOf(
                snapshot(
                    now,
                    "100.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "200.00", "200.00"),
                        "USD" to assetRow("-100.00", "1.0", "-100.00"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(3600),
                    "-50.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "50.00", "50.00"),
                        "USD" to assetRow("-100.00", "1.0", "-100.00"),
                    ),
                ),
            )

            val result = RebalancerComparisonCalculator.calculate(snapshots, emptyList())

            result.unavailableReason shouldBe ComparisonUnavailableReason.NON_POSITIVE_BASELINE
            result.baselineTimestamp shouldBe now
            result.unavailableAt shouldBe now.plusSeconds(3600)
        }

        "down-sampled interval shape: several trades between two snapshots reconcile" {
            val snapshots = listOf(
                snapshot(
                    now,
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                        "ETH" to assetRow("0", "2500.00", "0"),
                        "USD" to assetRow("50000.00", "1.0", "50000.00"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(7200),
                    "149948.00",
                    mapOf(
                        "BTC" to assetRow("1.5", "60000.00", "90000.00"),
                        "ETH" to assetRow("2.0", "10000.00", "20000.00"),
                        "USD" to assetRow("39948.00", "1.0", "39948.00"),
                    ),
                ),
            )
            val trades = listOf(
                trade(
                    now.plusSeconds(1800),
                    side = "buy",
                    symbol = "BTC",
                    volume = "0.5",
                    usdAmount = "5000.00",
                    fee = "13.00",
                ),
                trade(
                    now.plusSeconds(3600),
                    side = "buy",
                    symbol = "ETH",
                    volume = "2.0",
                    usdAmount = "5013.00",
                    fee = "26.00",
                ),
            )

            val result = RebalancerComparisonCalculator.calculate(snapshots, trades)

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
        }

        "staking reward reconciles an otherwise unexplained balance delta: RECONCILED" {
            val snapshots = listOf(
                snapshot(
                    now,
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                        "USD" to assetRow("50000.00", "1.0", "50000.00"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(3600),
                    "105000.00",
                    mapOf(
                        "BTC" to assetRow("1.1", "50000.00", "55000.00"),
                        "USD" to assetRow("50000.00", "1.0", "50000.00"),
                    ),
                ),
            )
            val rewards = listOf(ledgerEvent(now.plusSeconds(1800), "BTC", "0.1"))

            val result = RebalancerComparisonCalculator.calculate(snapshots, emptyList(), rewards)

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
        }

        "staking reward absent from the actual snapshot fails closed" {
            val snapshots = listOf(
                snapshot(
                    now,
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                        "USD" to assetRow("50000.00", "1.0", "50000.00"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(3600),
                    "110000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "60000.00", "60000.00"),
                        "USD" to assetRow("50000.00", "1.0", "50000.00"),
                    ),
                ),
            )
            val rewards = listOf(ledgerEvent(now.plusSeconds(1800), "BTC", "0.2"))

            val result = RebalancerComparisonCalculator.calculate(snapshots, emptyList(), rewards)

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.confidence shouldBe null
            result.unavailableReason shouldBe ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE
            result.unavailableAt shouldBe now.plusSeconds(3600)
        }

        "dividend ledger events for tracked assets are mirrored in buy-and-hold" {
            val snapshots = listOf(
                snapshot(
                    now,
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                        "USD" to assetRow("50000.00", "1.0", "50000.00"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(3600),
                    "105000.00",
                    mapOf(
                        "BTC" to assetRow("1.1", "50000.00", "55000.00"),
                        "USD" to assetRow("50000.00", "1.0", "50000.00"),
                    ),
                ),
            )
            val rewards =
                listOf(ledgerEvent(now.plusSeconds(1800), "BTC", "0.1", KrakenApiConstants.LEDGER_TYPE_DIVIDEND))

            val result = RebalancerComparisonCalculator.calculate(snapshots, emptyList(), rewards)

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("105000.00")
        }

        "rewards before the baseline do not affect the comparison" {
            val snapshots = listOf(
                snapshot(
                    now,
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                        "USD" to assetRow("50000.00", "1.0", "50000.00"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(3600),
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                        "USD" to assetRow("50000.00", "1.0", "50000.00"),
                    ),
                ),
            )
            val rewards = listOf(ledgerEvent(now.minusSeconds(3600), "BTC", "0.1"))

            val result = RebalancerComparisonCalculator.calculate(snapshots, emptyList(), rewards)

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points[0].differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "staking reward for an asset outside the baseline universe is ignored" {
            val snapshots = listOf(
                snapshot(
                    now,
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                        "USD" to assetRow("50000.00", "1.0", "50000.00"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(3600),
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                        "USD" to assetRow("50000.00", "1.0", "50000.00"),
                    ),
                ),
            )
            val rewards = listOf(ledgerEvent(now.plusSeconds(1800), "SOL", "0.5"))

            val result = RebalancerComparisonCalculator.calculate(snapshots, emptyList(), rewards)

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100000.00")
        }

        "trades for assets outside the snapshot universe are skipped, not fatal" {
            val snapshots = listOf(
                snapshot(
                    now,
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                        "USD" to assetRow("50000.00", "1.0", "50000.00"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(3600),
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                        "USD" to assetRow("50000.00", "1.0", "50000.00"),
                    ),
                ),
            )
            val trades = listOf(
                trade(now.plusSeconds(1800), "BUY", "XLM", "100.0", "105.00"),
                trade(now.plusSeconds(900), "BUY", "BTC", "0.5", "25000.00"),
                trade(now.plusSeconds(2700), "SELL", "BTC", "0.5", "25000.00"),
            )

            val result = RebalancerComparisonCalculator.calculate(snapshots, trades, emptyList())

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100000.00")
        }

        // --- SECTION 15 REGRESSION & DOMAIN SCENARIO SUITE ---

        "Scenario A: USD cash dividend maintains zero difference and RECONCILED confidence (original production bug)" {
            val snapshots = listOf(
                snapshot(
                    now,
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                        "USD" to assetRow("50000.00", "1.0", "50000.00"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(3600),
                    "100025.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                        "USD" to assetRow("50025.00", "1.0", "50025.00"),
                    ),
                ),
            )
            val ledgers = listOf(
                ledgerEvent(
                    timestamp = now.plusSeconds(1800),
                    asset = "USD",
                    amount = "25.00",
                    type = KrakenApiConstants.LEDGER_TYPE_DIVIDEND,
                ),
            )

            val result = RebalancerComparisonCalculator.calculate(snapshots, emptyList(), ledgers)

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points.size shouldBe 2
            result.points[1].rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("100025.00")
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100025.00")
            result.points[1].differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
            result.latestDifferenceUSD!! shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "Scenario B: Cash dividend with fee correctly credits net delta (amount - fee)" {
            val snapshots = listOf(
                snapshot(
                    now,
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                        "USD" to assetRow("50000.00", "1.0", "50000.00"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(3600),
                    "100024.90",
                    mapOf(
                        "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                        "USD" to assetRow("50024.90", "1.0", "50024.90"),
                    ),
                ),
            )
            val ledgers = listOf(
                ledgerEvent(
                    timestamp = now.plusSeconds(1800),
                    asset = "USD",
                    amount = "25.00",
                    fee = "0.10",
                    type = KrakenApiConstants.LEDGER_TYPE_DIVIDEND,
                ),
            )

            val result = RebalancerComparisonCalculator.calculate(snapshots, emptyList(), ledgers)

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points[1].rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("100024.90")
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100024.90")
            result.latestDifferenceUSD!! shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "Scenario C: Zero-baseline reward values newly credited asset at subsequent market prices" {
            val snapshots = listOf(
                snapshot(
                    now,
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("0", "50000.00", "0"),
                        "USD" to assetRow("100000.00", "1.0", "100000.00"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(3600),
                    "106000.00",
                    mapOf(
                        "BTC" to assetRow("0.1", "60000.00", "6000.00"),
                        "USD" to assetRow("100000.00", "1.0", "100000.00"),
                    ),
                ),
            )
            val ledgers = listOf(
                ledgerEvent(
                    timestamp = now.plusSeconds(1800),
                    asset = "BTC",
                    amount = "0.1",
                    type = KrakenApiConstants.LEDGER_TYPE_STAKING,
                ),
            )

            val result = RebalancerComparisonCalculator.calculate(snapshots, emptyList(), ledgers)

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points[1].rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("106000.00")
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("106000.00")
            result.latestDifferenceUSD!! shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "Scenario D: Manual authoritative BUY replays into Buy & Hold and creates zero divergence" {
            val snapshots = listOf(
                snapshot(
                    now,
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                        "USD" to assetRow("50000.00", "1.0", "50000.00"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(3600),
                    "115000.00",
                    mapOf(
                        "BTC" to assetRow("1.5", "60000.00", "90000.00"),
                        "USD" to assetRow("25000.00", "1.0", "25000.00"),
                    ),
                ),
            )
            val trades = listOf(
                manualTrade(
                    timestamp = now.plusSeconds(1800),
                    side = "buy",
                    symbol = "BTC",
                    volume = "0.5",
                    usdAmount = "25000.00",
                    fee = "0",
                ),
            )

            val result = RebalancerComparisonCalculator.calculate(snapshots, trades)

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points[1].rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("115000.00")
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("115000.00")
            result.latestDifferenceUSD!! shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "Scenario E: Manual authoritative SELL replays into Buy & Hold and creates zero divergence" {
            val snapshots = listOf(
                snapshot(
                    now,
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("2.0", "50000.00", "100000.00"),
                        "USD" to assetRow("0", "1.0", "0"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(3600),
                    "110000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "60000.00", "60000.00"),
                        "USD" to assetRow("50000.00", "1.0", "50000.00"),
                    ),
                ),
            )
            val trades = listOf(
                manualTrade(
                    timestamp = now.plusSeconds(1800),
                    side = "sell",
                    symbol = "BTC",
                    volume = "1.0",
                    usdAmount = "50000.00",
                    fee = "0",
                ),
            )

            val result = RebalancerComparisonCalculator.calculate(snapshots, trades)

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points[1].rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("110000.00")
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("110000.00")
            result.latestDifferenceUSD!! shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "Scenario F: Manual trade fee hits both actual and Buy & Hold identically" {
            val snapshots = listOf(
                snapshot(
                    now,
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                        "USD" to assetRow("50000.00", "1.0", "50000.00"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(3600),
                    "114974.00",
                    mapOf(
                        "BTC" to assetRow("1.5", "60000.00", "90000.00"),
                        "USD" to assetRow("24974.00", "1.0", "24974.00"),
                    ),
                ),
            )
            val trades = listOf(
                manualTrade(
                    timestamp = now.plusSeconds(1800),
                    side = "buy",
                    symbol = "BTC",
                    volume = "0.5",
                    usdAmount = "25000.00",
                    fee = "26.00",
                ),
            )

            val result = RebalancerComparisonCalculator.calculate(snapshots, trades)

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points[1].rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("114974.00")
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("114974.00")
            result.latestDifferenceUSD!! shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "Scenario G & H: Bot trade is ignored by Buy & Hold and legitimately generates rebalance alpha" {
            val snapshots = listOf(
                snapshot(
                    now,
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                        "USD" to assetRow("50000.00", "1.0", "50000.00"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(3600),
                    "115000.00",
                    mapOf(
                        "BTC" to assetRow("1.5", "60000.00", "90000.00"),
                        "USD" to assetRow("25000.00", "1.0", "25000.00"),
                    ),
                ),
            )
            val trades = listOf(
                trade(
                    now.plusSeconds(1800),
                    side = "buy",
                    symbol = "BTC",
                    volume = "0.5",
                    usdAmount = "25000.00",
                    fee = "0",
                    cycleId = "cycle-1",
                    source = TradeSource.LOCAL_ESTIMATE,
                ),
            )

            val result = RebalancerComparisonCalculator.calculate(snapshots, trades)

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points[1].rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("115000.00")
            // Buy & Hold stayed at 1.0 BTC @ 60k + 50k USD = 110,000.00
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("110000.00")
            result.latestDifferenceUSD!! shouldBeEqualComparingTo BigDecimal("5000.00")
        }

        "Scenario I: Mixed manual and bot trades in same interval isolate bot divergence" {
            val snapshots = listOf(
                snapshot(
                    now,
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                        "USD" to assetRow("50000.00", "1.0", "50000.00"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(3600),
                    "125000.00",
                    mapOf(
                        "BTC" to assetRow("1.7", "60000.00", "102000.00"),
                        "USD" to assetRow("23000.00", "1.0", "23000.00"),
                    ),
                ),
            )
            val trades = listOf(
                // Manual BUY 0.2 BTC for 10k USD
                manualTrade(
                    timestamp = now.plusSeconds(1200),
                    side = "buy",
                    symbol = "BTC",
                    volume = "0.2",
                    usdAmount = "10000.00",
                    tradeId = "man-1",
                ),
                // Bot BUY 0.5 BTC for 17k USD (rebalancer trade)
                trade(
                    timestamp = now.plusSeconds(2400),
                    side = "buy",
                    symbol = "BTC",
                    volume = "0.5",
                    usdAmount = "17000.00",
                    cycleId = "cycle-1",
                ),
            )

            val result = RebalancerComparisonCalculator.calculate(snapshots, trades)

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points[1].rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("125000.00")
            // B&H had baseline 1 BTC + 50k USD, applied manual BUY 0.2 BTC for 10k USD => 1.2 BTC @ 60k + 40k USD = 72k + 40k = 112,000.00
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("112000.00")
            result.latestDifferenceUSD!! shouldBeEqualComparingTo BigDecimal("13000.00")
        }

        "Scenario J: Manual multi-fill order replays all fill legs once into Buy & Hold" {
            val snapshots = listOf(
                snapshot(
                    now,
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                        "USD" to assetRow("50000.00", "1.0", "50000.00"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(3600),
                    "115000.00",
                    mapOf(
                        "BTC" to assetRow("1.5", "60000.00", "90000.00"),
                        "USD" to assetRow("25000.00", "1.0", "25000.00"),
                    ),
                ),
            )
            val trades = listOf(
                manualTrade(
                    timestamp = now.plusSeconds(1200),
                    side = "buy",
                    symbol = "BTC",
                    volume = "0.2",
                    usdAmount = "10000.00",
                    orderTxid = "order-multi-1",
                    tradeId = "fill-1",
                ),
                manualTrade(
                    timestamp = now.plusSeconds(1205),
                    side = "buy",
                    symbol = "BTC",
                    volume = "0.3",
                    usdAmount = "15000.00",
                    orderTxid = "order-multi-1",
                    tradeId = "fill-2",
                ),
            )

            val result = RebalancerComparisonCalculator.calculate(snapshots, trades)

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points[1].rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("115000.00")
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("115000.00")
            result.latestDifferenceUSD!! shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "Scenario K: Bot multi-fill order is completely ignored by Buy & Hold" {
            val snapshots = listOf(
                snapshot(
                    now,
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                        "USD" to assetRow("50000.00", "1.0", "50000.00"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(3600),
                    "115000.00",
                    mapOf(
                        "BTC" to assetRow("1.5", "60000.00", "90000.00"),
                        "USD" to assetRow("25000.00", "1.0", "25000.00"),
                    ),
                ),
            )
            val trades = listOf(
                trade(
                    timestamp = now.plusSeconds(1200),
                    side = "buy",
                    symbol = "BTC",
                    volume = "0.2",
                    usdAmount = "10000.00",
                    cycleId = "cycle-1",
                    orderTxid = "bot-order-1",
                    tradeId = "bot-fill-1",
                ),
                trade(
                    timestamp = now.plusSeconds(1205),
                    side = "buy",
                    symbol = "BTC",
                    volume = "0.3",
                    usdAmount = "15000.00",
                    cycleId = "cycle-1",
                    orderTxid = "bot-order-1",
                    tradeId = "bot-fill-2",
                ),
            )

            val result = RebalancerComparisonCalculator.calculate(snapshots, trades)

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("110000.00")
            result.latestDifferenceUSD!! shouldBeEqualComparingTo BigDecimal("5000.00")
        }

        "Scenario L: Ambiguous or UNKNOWN tracked trade makes comparison unavailable" {
            val snapshots = listOf(
                snapshot(
                    now,
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                        "USD" to assetRow("50000.00", "1.0", "50000.00"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(3600),
                    "115000.00",
                    mapOf(
                        "BTC" to assetRow("1.5", "60000.00", "90000.00"),
                        "USD" to assetRow("25000.00", "1.0", "25000.00"),
                    ),
                ),
            )
            val trades = listOf(
                TradeRecord(
                    timestamp = now.plusSeconds(1800),
                    pair = "BTCUSD",
                    side = "BUY",
                    symbol = "BTC",
                    volume = BigDecimal("0.5"),
                    usdAmount = BigDecimal("25000.00"),
                    success = true,
                    dryRun = false,
                    price = BigDecimal("50000.00"),
                    fee = BigDecimal.ZERO,
                    source = TradeSource.LEGACY_UNKNOWN, // Unknown provenance
                    cycleId = null,
                    clientOrderId = null,
                ),
            )

            val result = RebalancerComparisonCalculator.calculate(snapshots, trades)

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.AMBIGUOUS_TRADE_OWNERSHIP
        }

        "Scenario M: External USD deposit increases both actual and Buy & Hold with zero divergence" {
            val snapshots = listOf(
                snapshot(
                    now,
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                        "USD" to assetRow("50000.00", "1.0", "50000.00"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(3600),
                    "110000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                        "USD" to assetRow("60000.00", "1.0", "60000.00"),
                    ),
                ),
            )
            val ledgers = listOf(
                ledgerEvent(
                    timestamp = now.plusSeconds(1800),
                    asset = "USD",
                    amount = "10000.00",
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                ),
            )

            val result = RebalancerComparisonCalculator.calculate(snapshots, emptyList(), ledgers)

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points[1].rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("110000.00")
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("110000.00")
            result.latestDifferenceUSD!! shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "Scenario N: External USD withdrawal with fee decreases both actual and Buy & Hold with zero divergence" {
            val snapshots = listOf(
                snapshot(
                    now,
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                        "USD" to assetRow("50000.00", "1.0", "50000.00"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(3600),
                    "94990.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                        "USD" to assetRow("44990.00", "1.0", "44990.00"),
                    ),
                ),
            )
            val ledgers = listOf(
                ledgerEvent(
                    timestamp = now.plusSeconds(1800),
                    asset = "USD",
                    amount = "-5000.00",
                    fee = "10.00",
                    type = KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL,
                ),
            )

            val result = RebalancerComparisonCalculator.calculate(snapshots, emptyList(), ledgers)

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points[1].rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("94990.00")
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("94990.00")
            result.latestDifferenceUSD!! shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "Scenario O: External crypto deposit increases both holdings and tracks future price movement identically" {
            val snapshots = listOf(
                snapshot(
                    now,
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                        "USD" to assetRow("50000.00", "1.0", "50000.00"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(3600),
                    "170000.00",
                    mapOf(
                        "BTC" to assetRow("2.0", "60000.00", "120000.00"),
                        "USD" to assetRow("50000.00", "1.0", "50000.00"),
                    ),
                ),
            )
            val ledgers = listOf(
                ledgerEvent(
                    timestamp = now.plusSeconds(1800),
                    asset = "BTC",
                    amount = "1.0",
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                ),
            )

            val result = RebalancerComparisonCalculator.calculate(snapshots, emptyList(), ledgers)

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points[1].rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("170000.00")
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("170000.00")
            result.latestDifferenceUSD!! shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "Scenario P: External crypto withdrawal decreases holdings on both actual and Buy & Hold" {
            val snapshots = listOf(
                snapshot(
                    now,
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                        "USD" to assetRow("50000.00", "1.0", "50000.00"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(3600),
                    "79940.00",
                    mapOf(
                        "BTC" to assetRow("0.499", "60000.00", "29940.00"),
                        "USD" to assetRow("50000.00", "1.0", "50000.00"),
                    ),
                ),
            )
            val ledgers = listOf(
                ledgerEvent(
                    timestamp = now.plusSeconds(1800),
                    asset = "BTC",
                    amount = "-0.5",
                    fee = "0.001",
                    type = KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL,
                ),
            )

            val result = RebalancerComparisonCalculator.calculate(snapshots, emptyList(), ledgers)

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points[1].rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("79940.00")
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("79940.00")
            result.latestDifferenceUSD!! shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "Scenario S: Untracked stock dividend credited in USD is mirrored in Buy & Hold USD" {
            val snapshots = listOf(
                snapshot(
                    now,
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                        "USD" to assetRow("50000.00", "1.0", "50000.00"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(3600),
                    "100050.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                        "USD" to assetRow("50050.00", "1.0", "50050.00"),
                    ),
                ),
            )
            // Kraken raw asset is ZUSD or USD
            val ledgers = listOf(
                ledgerEvent(
                    timestamp = now.plusSeconds(1800),
                    asset = "ZUSD",
                    amount = "50.00",
                    type = KrakenApiConstants.LEDGER_TYPE_DIVIDEND,
                ),
            )

            val result = RebalancerComparisonCalculator.calculate(snapshots, emptyList(), ledgers)

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points[1].rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("100050.00")
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100050.00")
            result.latestDifferenceUSD!! shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "Scenario U: Event exactly at baseline timestamp is already embedded in baseline and not replayed" {
            val snapshots = listOf(
                snapshot(
                    now,
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                        "USD" to assetRow("50000.00", "1.0", "50000.00"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(3600),
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                        "USD" to assetRow("50000.00", "1.0", "50000.00"),
                    ),
                ),
            )
            // Event at exact baseline timestamp `now`
            val ledgers = listOf(
                ledgerEvent(
                    timestamp = now,
                    asset = "USD",
                    amount = "1000.00",
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                ),
            )

            val result = RebalancerComparisonCalculator.calculate(snapshots, emptyList(), ledgers)

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100000.00")
            result.latestDifferenceUSD!! shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "Scenario Z: Trade ledger rows are ignored to prevent double-counting against TradesHistory" {
            val snapshots = listOf(
                snapshot(
                    now,
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                        "USD" to assetRow("50000.00", "1.0", "50000.00"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(3600),
                    "115000.00",
                    mapOf(
                        "BTC" to assetRow("1.5", "60000.00", "90000.00"),
                        "USD" to assetRow("25000.00", "1.0", "25000.00"),
                    ),
                ),
            )
            val trades = listOf(
                manualTrade(
                    timestamp = now.plusSeconds(1800),
                    side = "buy",
                    symbol = "BTC",
                    volume = "0.5",
                    usdAmount = "25000.00",
                ),
            )
            // Kraken ledger rows emitted for the same trade execution
            val ledgers = listOf(
                ledgerEvent(
                    timestamp = now.plusSeconds(1800),
                    asset = "BTC",
                    amount = "0.5",
                    type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                ),
                ledgerEvent(
                    timestamp = now.plusSeconds(1800),
                    asset = "USD",
                    amount = "-25000.00",
                    type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                ),
            )

            val result = RebalancerComparisonCalculator.calculate(snapshots, trades, ledgers)

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points[1].rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("115000.00")
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("115000.00")
            result.latestDifferenceUSD!! shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "UNKNOWN SELL trade makes comparison unavailable with AMBIGUOUS_TRADE_OWNERSHIP" {
            val snapshots = listOf(
                snapshot(
                    now,
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                        "USD" to assetRow("50000.00", "1.0", "50000.00"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(3600),
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("0.5", "50000.00", "25000.00"),
                        "USD" to assetRow("75000.00", "1.0", "75000.00"),
                    ),
                ),
            )
            val trades = listOf(
                trade(
                    timestamp = now.plusSeconds(1800),
                    side = "SELL",
                    symbol = "BTC",
                    volume = "0.5",
                    usdAmount = "25000.00",
                    source = TradeSource.LEGACY_UNKNOWN,
                    cycleId = null,
                    clientOrderId = null,
                ),
            )

            val result = RebalancerComparisonCalculator.calculate(snapshots, trades)

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.AMBIGUOUS_TRADE_OWNERSHIP
        }

        "UNKNOWN multi-fill order makes comparison unavailable with AMBIGUOUS_TRADE_OWNERSHIP" {
            val snapshots = listOf(
                snapshot(
                    now,
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                        "USD" to assetRow("50000.00", "1.0", "50000.00"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(3600),
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.4", "50000.00", "70000.00"),
                        "USD" to assetRow("30000.00", "1.0", "30000.00"),
                    ),
                ),
            )
            val trades = listOf(
                trade(
                    timestamp = now.plusSeconds(1800),
                    side = "BUY",
                    symbol = "BTC",
                    volume = "0.2",
                    usdAmount = "10000.00",
                    source = TradeSource.API_FILL,
                    cycleId = "cycle-1",
                ),
                trade(
                    timestamp = now.plusSeconds(1900),
                    side = "BUY",
                    symbol = "BTC",
                    volume = "0.2",
                    usdAmount = "10000.00",
                    source = TradeSource.LEGACY_UNKNOWN,
                    cycleId = null,
                    clientOrderId = null,
                ),
            )

            val result = RebalancerComparisonCalculator.calculate(snapshots, trades)

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.AMBIGUOUS_TRADE_OWNERSHIP
        }

        "UNKNOWN trade outside comparison range does not affect comparison" {
            val snapshots = listOf(
                snapshot(
                    now,
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                        "USD" to assetRow("50000.00", "1.0", "50000.00"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(3600),
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                        "USD" to assetRow("50000.00", "1.0", "50000.00"),
                    ),
                ),
            )
            val trades = listOf(
                // Trade BEFORE baseline timestamp
                trade(
                    timestamp = now.minusSeconds(1800),
                    side = "BUY",
                    symbol = "BTC",
                    volume = "0.5",
                    usdAmount = "25000.00",
                    source = TradeSource.LEGACY_UNKNOWN,
                    cycleId = null,
                    clientOrderId = null,
                ),
                // Trade AFTER last snapshot
                trade(
                    timestamp = now.plusSeconds(7200),
                    side = "BUY",
                    symbol = "BTC",
                    volume = "0.5",
                    usdAmount = "25000.00",
                    source = TradeSource.LEGACY_UNKNOWN,
                    cycleId = null,
                    clientOrderId = null,
                ),
            )

            val result = RebalancerComparisonCalculator.calculate(snapshots, trades)

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
        }

        "Multi-fill bot order with durable orderTxid evidence is excluded from Buy & Hold" {
            val snapshots = listOf(
                snapshot(
                    now,
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                        "USD" to assetRow("50000.00", "1.0", "50000.00"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(3600),
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.4", "50000.00", "70000.00"),
                        "USD" to assetRow("30000.00", "1.0", "30000.00"),
                    ),
                ),
            )
            // 2 partial fills for orderTxid "BOT-TXID-100", no cycleId on trades
            val trades = listOf(
                trade(
                    timestamp = now.plusSeconds(1800),
                    side = "BUY",
                    symbol = "BTC",
                    volume = "0.2",
                    usdAmount = "10000.00",
                    source = TradeSource.API_FILL,
                    orderTxid = "BOT-TXID-100",
                    tradeId = "FILL-1",
                    cycleId = null,
                    clientOrderId = null,
                ),
                trade(
                    timestamp = now.plusSeconds(1900),
                    side = "BUY",
                    symbol = "BTC",
                    volume = "0.2",
                    usdAmount = "10000.00",
                    source = TradeSource.API_FILL,
                    orderTxid = "BOT-TXID-100",
                    tradeId = "FILL-2",
                    cycleId = null,
                    clientOrderId = null,
                ),
            )

            val result = RebalancerComparisonCalculator.calculate(
                snapshots = snapshots,
                trades = trades,
                knownRebalancerOrderTxids = setOf("BOT-TXID-100"),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            // Bot trades are excluded from Buy & Hold, so Buy & Hold stays at 100,000 USD
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100000.00")
            result.points[1].rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("100000.00")
        }

        "Multi-fill bot order with durable clientOrderId evidence is excluded from Buy & Hold" {
            val snapshots = listOf(
                snapshot(
                    now,
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                        "USD" to assetRow("50000.00", "1.0", "50000.00"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(3600),
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.4", "50000.00", "70000.00"),
                        "USD" to assetRow("30000.00", "1.0", "30000.00"),
                    ),
                ),
            )
            val trades = listOf(
                trade(
                    timestamp = now.plusSeconds(1800),
                    side = "BUY",
                    symbol = "BTC",
                    volume = "0.4",
                    usdAmount = "20000.00",
                    source = TradeSource.API_FILL,
                    clientOrderId = "BOT-CL-ORD-1",
                    tradeId = "FILL-1",
                    cycleId = null,
                ),
            )

            val result = RebalancerComparisonCalculator.calculate(
                snapshots = snapshots,
                trades = trades,
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100000.00")
        }

        "Adjustment ledger event increases both Actual and Buy & Hold with zero divergence" {
            val snapshots = listOf(
                snapshot(
                    now,
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                        "USD" to assetRow("50000.00", "1.0", "50000.00"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(3600),
                    "100050.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                        "USD" to assetRow("50050.00", "1.0", "50050.00"),
                    ),
                ),
            )
            val ledgers = listOf(
                ledgerEvent(
                    timestamp = now.plusSeconds(1800),
                    asset = "USD",
                    amount = "50.00",
                    type = KrakenApiConstants.LEDGER_TYPE_ADJUSTMENT,
                ),
            )

            val result = RebalancerComparisonCalculator.calculate(snapshots, emptyList(), ledgers)

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points[1].rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("100050.00")
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100050.00")
            result.latestDifferenceUSD!! shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "Scenario AA: Manual Buy Crypto with existing cash (USD spend + BTC receive) has zero divergence" {
            val snapshots = listOf(
                snapshot(
                    now,
                    "10000.00",
                    mapOf(
                        "BTC" to assetRow("0", "50000.00", "0"),
                        "USD" to assetRow("10000.00", "1.0", "10000.00"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(3600),
                    "10000.00",
                    mapOf(
                        "BTC" to assetRow("0.10", "50000.00", "5000.00"),
                        "USD" to assetRow("5000.00", "1.0", "5000.00"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(7200),
                    "11000.00",
                    mapOf(
                        "BTC" to assetRow("0.10", "60000.00", "6000.00"),
                        "USD" to assetRow("5000.00", "1.0", "5000.00"),
                    ),
                ),
            )
            val ledgers = listOf(
                ledgerEvent(
                    timestamp = now.plusSeconds(1800),
                    asset = "USD",
                    amount = "-5000.00",
                    type = KrakenApiConstants.LEDGER_TYPE_SPEND,
                    refid = "BUY-CRYPTO-1",
                ),
                ledgerEvent(
                    timestamp = now.plusSeconds(1800),
                    asset = "BTC",
                    amount = "0.10",
                    type = KrakenApiConstants.LEDGER_TYPE_RECEIVE,
                    refid = "BUY-CRYPTO-1",
                ),
            )

            val result = RebalancerComparisonCalculator.calculate(snapshots, emptyList(), ledgers)

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points.size shouldBe 3
            result.points[1].rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("10000.00")
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("10000.00")
            result.points[1].differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
            result.points[2].rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("11000.00")
            result.points[2].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("11000.00")
            result.points[2].differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
            result.latestDifferenceUSD!! shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "Scenario BB: Card-funded Buy Crypto (USD deposit + USD spend + BTC receive) maintains exact parity" {
            val snapshots = listOf(
                snapshot(
                    now,
                    "25000.00",
                    mapOf(
                        "BTC" to assetRow("0.50", "50000.00", "25000.00"),
                        "USD" to assetRow("0", "1.0", "0"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(3600),
                    "36000.00",
                    mapOf(
                        "BTC" to assetRow("0.60", "60000.00", "36000.00"),
                        "USD" to assetRow("0", "1.0", "0"),
                    ),
                ),
            )
            val ledgers = listOf(
                ledgerEvent(
                    timestamp = now.plusSeconds(1800),
                    asset = "USD",
                    amount = "5000.00",
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    refid = "CARD-DEP-1",
                ),
                ledgerEvent(
                    timestamp = now.plusSeconds(1800),
                    asset = "USD",
                    amount = "-5000.00",
                    type = KrakenApiConstants.LEDGER_TYPE_SPEND,
                    refid = "CARD-BUY-1",
                ),
                ledgerEvent(
                    timestamp = now.plusSeconds(1800),
                    asset = "BTC",
                    amount = "0.10",
                    type = KrakenApiConstants.LEDGER_TYPE_RECEIVE,
                    refid = "CARD-BUY-1",
                ),
            )

            val result = RebalancerComparisonCalculator.calculate(snapshots, emptyList(), ledgers)

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points[1].rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("36000.00")
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("36000.00")
            result.latestDifferenceUSD!! shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "Scenario CC: Consumer Buy Crypto with nonzero fees calculates exact net balance delta" {
            val snapshots = listOf(
                snapshot(
                    now,
                    "10000.00",
                    mapOf(
                        "BTC" to assetRow("0", "50000.00", "0"),
                        "USD" to assetRow("10000.00", "1.0", "10000.00"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(3600),
                    "9940.00",
                    mapOf(
                        "BTC" to assetRow("0.099", "50000.00", "4950.00"),
                        "USD" to assetRow("4990.00", "1.0", "4990.00"),
                    ),
                ),
            )
            val ledgers = listOf(
                ledgerEvent(
                    timestamp = now.plusSeconds(1800),
                    asset = "USD",
                    amount = "-5000.00",
                    fee = "10.00",
                    type = KrakenApiConstants.LEDGER_TYPE_SPEND,
                ),
                ledgerEvent(
                    timestamp = now.plusSeconds(1800),
                    asset = "BTC",
                    amount = "0.10",
                    fee = "0.001",
                    type = KrakenApiConstants.LEDGER_TYPE_RECEIVE,
                ),
            )

            val result = RebalancerComparisonCalculator.calculate(snapshots, emptyList(), ledgers)

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points[1].rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("9940.00")
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("9940.00")
            result.latestDifferenceUSD!! shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "Scenario DD: Crypto-to-crypto conversion (ETH spend + BTC receive) maintains exact parity" {
            val snapshots = listOf(
                snapshot(
                    now,
                    "90000.00",
                    mapOf(
                        "ETH" to assetRow("10.0", "3000.00", "30000.00"),
                        "BTC" to assetRow("1.0", "60000.00", "60000.00"),
                        "USD" to assetRow("0", "1.0", "0"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(3600),
                    "104958.00",
                    mapOf(
                        "ETH" to assetRow("7.99", "3500.00", "27965.00"),
                        "BTC" to assetRow("1.0999", "70000.00", "76993.00"),
                        "USD" to assetRow("0", "1.0", "0"),
                    ),
                ),
            )
            val ledgers = listOf(
                ledgerEvent(
                    timestamp = now.plusSeconds(1800),
                    asset = "ETH",
                    amount = "-2.00",
                    fee = "0.01",
                    type = KrakenApiConstants.LEDGER_TYPE_SPEND,
                ),
                ledgerEvent(
                    timestamp = now.plusSeconds(1800),
                    asset = "BTC",
                    amount = "0.10",
                    fee = "0.0001",
                    type = KrakenApiConstants.LEDGER_TYPE_RECEIVE,
                ),
            )

            val result = RebalancerComparisonCalculator.calculate(snapshots, emptyList(), ledgers)

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points[1].rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("104958.00")
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("104958.00")
            result.latestDifferenceUSD!! shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "Scenario EE: Untracked asset conversion updates tracked asset without injecting untracked asset" {
            val snapshots = listOf(
                snapshot(
                    now,
                    "6000.00",
                    mapOf(
                        "BTC" to assetRow("0.10", "50000.00", "5000.00"),
                        "USD" to assetRow("1000.00", "1.0", "1000.00"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(3600),
                    "6500.00",
                    mapOf(
                        "BTC" to assetRow("0.11", "50000.00", "5500.00"),
                        "USD" to assetRow("1000.00", "1.0", "1000.00"),
                    ),
                ),
            )
            val ledgers = listOf(
                ledgerEvent(
                    timestamp = now.plusSeconds(1800),
                    asset = "DOGE",
                    amount = "-1000.00",
                    type = KrakenApiConstants.LEDGER_TYPE_SPEND,
                ),
                ledgerEvent(
                    timestamp = now.plusSeconds(1800),
                    asset = "BTC",
                    amount = "0.01",
                    type = KrakenApiConstants.LEDGER_TYPE_RECEIVE,
                ),
            )

            val result = RebalancerComparisonCalculator.calculate(snapshots, emptyList(), ledgers)

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points[1].rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("6500.00")
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("6500.00")
            result.latestDifferenceUSD!! shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "Case B: trade timestamp before display timestamp but after balance observation belongs to next interval" {
            val t0 = now
            val s1 = snapshot(
                timestamp = t0.plusMillis(500),
                totalValueUSD = "100000.00",
                assets = mapOf(
                    "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                    "USD" to assetRow("50000.00", "1.0", "50000.00"),
                ),
                balancesObservedAt = t0,
            )
            val s2 = snapshot(
                timestamp = t0.plusSeconds(3600),
                totalValueUSD = "100000.00",
                assets = mapOf(
                    "BTC" to assetRow("1.2", "50000.00", "60000.00"),
                    "USD" to assetRow("40000.00", "1.0", "40000.00"),
                ),
                balancesObservedAt = t0.plusSeconds(3600),
            )
            val trades = listOf(
                manualTrade(
                    timestamp = t0.plusMillis(100),
                    side = "buy",
                    symbol = "BTC",
                    volume = "0.2",
                    usdAmount = "10000.00",
                ),
            )

            val result = RebalancerComparisonCalculator.calculate(listOf(s1, s2), trades)

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points.size shouldBe 2
            result.points[0].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100000.00")
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100000.00")
        }

        "Case C: selected baseline already contains skewed trade and does not replay it into buy and hold" {
            val t0 = now
            val s0 = snapshot(
                timestamp = t0.minusSeconds(3600),
                totalValueUSD = "100000.00",
                assets = mapOf(
                    "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                    "USD" to assetRow("50000.00", "1.0", "50000.00"),
                ),
            )
            val s1 = snapshot(
                timestamp = t0,
                totalValueUSD = "100000.00",
                assets = mapOf(
                    "BTC" to assetRow("1.2", "50000.00", "60000.00"),
                    "USD" to assetRow("40000.00", "1.0", "40000.00"),
                ),
                balancesObservedAt = t0,
            )
            val s2 = snapshot(
                timestamp = t0.plusSeconds(3600),
                totalValueUSD = "100000.00",
                assets = mapOf(
                    "BTC" to assetRow("1.2", "50000.00", "60000.00"),
                    "USD" to assetRow("40000.00", "1.0", "40000.00"),
                ),
                balancesObservedAt = t0.plusSeconds(3600),
            )
            val trades = listOf(
                manualTrade(
                    timestamp = t0.plusMillis(250),
                    side = "buy",
                    symbol = "BTC",
                    volume = "0.2",
                    usdAmount = "10000.00",
                ),
            )

            val result = RebalancerComparisonCalculator.calculate(
                snapshots = listOf(s1, s2),
                trades = trades,
                anchorSnapshot = s0,
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points.size shouldBe 2
            result.baselineTimestamp shouldBe s1.timestamp
            result.points[0].timestamp shouldBe s1.timestamp
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100000.00")
            result.latestDifferenceUSD!! shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "Case D: selected baseline does not contain trade executed after balance observation" {
            val t0 = now
            val s1 = snapshot(
                timestamp = t0.plusMillis(500),
                totalValueUSD = "100000.00",
                assets = mapOf(
                    "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                    "USD" to assetRow("50000.00", "1.0", "50000.00"),
                ),
                balancesObservedAt = t0,
            )
            val s2 = snapshot(
                timestamp = t0.plusSeconds(3600),
                totalValueUSD = "100000.00",
                assets = mapOf(
                    "BTC" to assetRow("1.2", "50000.00", "60000.00"),
                    "USD" to assetRow("40000.00", "1.0", "40000.00"),
                ),
                balancesObservedAt = t0.plusSeconds(3600),
            )
            val trades = listOf(
                manualTrade(
                    timestamp = t0.plusMillis(100),
                    side = "buy",
                    symbol = "BTC",
                    volume = "0.2",
                    usdAmount = "10000.00",
                ),
            )

            val result = RebalancerComparisonCalculator.calculate(listOf(s1, s2), trades)

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points.size shouldBe 2
            result.points[0].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100000.00")
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100000.00")
        }

        "Case H: ledger event after snapshot display timestamp but already in observed balances reconciles" {
            val t0 = now
            val s1 = snapshot(
                timestamp = t0,
                totalValueUSD = "100000.00",
                assets = mapOf(
                    "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                    "USD" to assetRow("50000.00", "1.0", "50000.00"),
                ),
            )
            val s2 = snapshot(
                timestamp = t0.plusSeconds(3600),
                totalValueUSD = "100025.00",
                assets = mapOf(
                    "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                    "USD" to assetRow("50025.00", "1.0", "50025.00"),
                ),
                balancesObservedAt = t0.plusSeconds(3600),
            )
            val ledgers = listOf(
                ledgerEvent(
                    timestamp = t0.plusSeconds(3600).plusMillis(250),
                    asset = "USD",
                    amount = "25.00",
                    type = KrakenApiConstants.LEDGER_TYPE_DIVIDEND,
                ),
            )

            val result = RebalancerComparisonCalculator.calculate(
                snapshots = listOf(s1, s2),
                trades = emptyList(),
                rewards = ledgers,
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points[1].rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("100025.00")
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100025.00")
        }

        "Case I: ledger event before display timestamp but after balance observation belongs to next interval" {
            val t0 = now
            val s1 = snapshot(
                timestamp = t0.plusMillis(500),
                totalValueUSD = "100000.00",
                assets = mapOf(
                    "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                    "USD" to assetRow("50000.00", "1.0", "50000.00"),
                ),
                balancesObservedAt = t0,
            )
            val s2 = snapshot(
                timestamp = t0.plusSeconds(3600),
                totalValueUSD = "100025.00",
                assets = mapOf(
                    "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                    "USD" to assetRow("50025.00", "1.0", "50025.00"),
                ),
                balancesObservedAt = t0.plusSeconds(3600),
            )
            val ledgers = listOf(
                ledgerEvent(
                    timestamp = t0.plusMillis(100),
                    asset = "USD",
                    amount = "25.00",
                    type = KrakenApiConstants.LEDGER_TYPE_DIVIDEND,
                ),
            )

            val result = RebalancerComparisonCalculator.calculate(
                snapshots = listOf(s1, s2),
                trades = emptyList(),
                rewards = ledgers,
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points[0].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100000.00")
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100025.00")
        }

        "Case J: trade and ledger close together reconcile jointly" {
            val t0 = now
            val s1 = snapshot(
                timestamp = t0,
                totalValueUSD = "100000.00",
                assets = mapOf(
                    "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                    "USD" to assetRow("50000.00", "1.0", "50000.00"),
                ),
            )
            val s2 = snapshot(
                timestamp = t0.plusSeconds(3600),
                totalValueUSD = "100500.00",
                assets = mapOf(
                    "BTC" to assetRow("1.1", "50000.00", "55000.00"),
                    "USD" to assetRow("45500.00", "1.0", "45500.00"),
                ),
            )
            val trades = listOf(
                manualTrade(
                    timestamp = t0.plusSeconds(1800),
                    side = "buy",
                    symbol = "BTC",
                    volume = "0.1",
                    usdAmount = "5000.00",
                ),
            )
            val ledgers = listOf(
                ledgerEvent(
                    timestamp = t0.plusSeconds(1800),
                    asset = "USD",
                    amount = "500.00",
                    type = KrakenApiConstants.LEDGER_TYPE_DIVIDEND,
                ),
            )

            val result = RebalancerComparisonCalculator.calculate(
                snapshots = listOf(s1, s2),
                trades = trades,
                rewards = ledgers,
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100500.00")
        }

        "Case N: range rebasing with pre-range anchor does not emit anchor point or alter baseline value" {
            val t0 = now
            val s0 = snapshot(
                timestamp = t0.minusSeconds(3600),
                totalValueUSD = "100000.00",
                assets = mapOf(
                    "BTC" to assetRow("0.5", "50000.00", "25000.00"),
                    "USD" to assetRow("75000.00", "1.0", "75000.00"),
                ),
            )
            val s1 = snapshot(
                timestamp = t0,
                totalValueUSD = "100000.00",
                assets = mapOf(
                    "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                    "USD" to assetRow("50000.00", "1.0", "50000.00"),
                ),
            )
            val s2 = snapshot(
                timestamp = t0.plusSeconds(3600),
                totalValueUSD = "100000.00",
                assets = mapOf(
                    "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                    "USD" to assetRow("50000.00", "1.0", "50000.00"),
                ),
            )

            val trades = listOf(
                trade(
                    timestamp = t0.minusSeconds(1800),
                    side = "buy",
                    symbol = "BTC",
                    volume = "0.5",
                    usdAmount = "25000.00",
                    orderTxid = "BOT-ORDER-EARLY",
                ),
            )

            val result = RebalancerComparisonCalculator.calculate(
                snapshots = listOf(s1, s2),
                trades = trades,
                anchorSnapshot = s0,
                knownRebalancerOrderTxids = setOf("BOT-ORDER-EARLY"),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points.size shouldBe 2
            result.baselineTimestamp shouldBe s1.timestamp
            result.points[0].timestamp shouldBe s1.timestamp
            result.points[0].rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("100000.00")
            result.points[0].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100000.00")
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100000.00")
        }

        "Anchor snapshot with timestamp >= baseline or mismatched universe is ignored" {
            val t0 = now
            val s1 = snapshot(
                timestamp = t0,
                totalValueUSD = "100000.00",
                assets = mapOf(
                    "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                    "USD" to assetRow("50000.00", "1.0", "50000.00"),
                ),
            )
            val s2 = snapshot(
                timestamp = t0.plusSeconds(3600),
                totalValueUSD = "100000.00",
                assets = mapOf(
                    "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                    "USD" to assetRow("50000.00", "1.0", "50000.00"),
                ),
            )
            val anchorFuture = snapshot(
                timestamp = t0.plusSeconds(10),
                totalValueUSD = "100000.00",
                assets = mapOf(
                    "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                    "USD" to assetRow("50000.00", "1.0", "50000.00"),
                ),
            )
            val anchorDifferentAssets = snapshot(
                timestamp = t0.minusSeconds(3600),
                totalValueUSD = "100000.00",
                assets = mapOf(
                    "ETH" to assetRow("10.0", "5000.00", "50000.00"),
                    "USD" to assetRow("50000.00", "1.0", "50000.00"),
                ),
            )

            val res1 = RebalancerComparisonCalculator.calculate(
                snapshots = listOf(s1, s2),
                trades = emptyList(),
                anchorSnapshot = anchorFuture,
            )
            res1.availability shouldBe ComparisonAvailability.AVAILABLE

            val res2 = RebalancerComparisonCalculator.calculate(
                snapshots = listOf(s1, s2),
                trades = emptyList(),
                anchorSnapshot = anchorDifferentAssets,
            )
            res2.availability shouldBe ComparisonAvailability.AVAILABLE
        }

        "Unsupported trade attributes fail closed with UNSUPPORTED_TRADE" {
            val t0 = now
            val s1 = snapshot(
                timestamp = t0,
                totalValueUSD = "100000.00",
                assets = mapOf(
                    "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                    "USD" to assetRow("50000.00", "1.0", "50000.00"),
                ),
            )
            val s2 = snapshot(
                timestamp = t0.plusSeconds(3600),
                totalValueUSD = "100000.00",
                assets = mapOf(
                    "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                    "USD" to assetRow("50000.00", "1.0", "50000.00"),
                ),
            )
            val badPairTrade = trade(
                timestamp = t0.plusSeconds(1800),
                side = "buy",
                symbol = "BTC",
                volume = "0.1",
                usdAmount = "5000.00",
            ).copy(pair = "BTCEUR")

            val res = RebalancerComparisonCalculator.calculate(
                snapshots = listOf(s1, s2),
                trades = listOf(badPairTrade),
            )
            res.availability shouldBe ComparisonAvailability.UNAVAILABLE
            res.unavailableReason shouldBe ComparisonUnavailableReason.UNSUPPORTED_TRADE
        }

        "Missing or zero asset prices fail closed with MISSING_PRICE" {
            val t0 = now
            val s1 = snapshot(
                timestamp = t0,
                totalValueUSD = "100000.00",
                assets = mapOf(
                    "BTC" to assetRow("1.0", "0", "0.00"),
                    "USD" to assetRow("50000.00", "1.0", "50000.00"),
                ),
            )
            val s2 = snapshot(
                timestamp = t0.plusSeconds(3600),
                totalValueUSD = "100000.00",
                assets = mapOf(
                    "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                    "USD" to assetRow("50000.00", "1.0", "50000.00"),
                ),
            )

            val res = RebalancerComparisonCalculator.calculate(
                snapshots = listOf(s1, s2),
                trades = emptyList(),
            )
            res.availability shouldBe ComparisonAvailability.UNAVAILABLE
            res.unavailableReason shouldBe ComparisonUnavailableReason.MISSING_PRICE
        }

        "In-flight trade during balance request latency is not assumed in snapshot and reconciles in next interval" {
            // T0: Request start
            val t0 = now
            // T0 + 200ms: Trade executes on exchange
            val tradeTime = t0.plusMillis(200)
            // T0 + 350ms: Get Balance response arrived
            // T0 + 500ms: Snapshot constructed/displayed
            val s1 = snapshot(
                timestamp = t0.plusMillis(500),
                totalValueUSD = "100000.00",
                assets = mapOf(
                    "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                    "USD" to assetRow("50000.00", "1.0", "50000.00"),
                ),
                balancesObservedAt = t0, // Captured at request start BEFORE getBalances()
            )
            // S2: Subsequent snapshot reflects the trade (BTC = 1.1, USD = 45000)
            val s2 = snapshot(
                timestamp = t0.plusSeconds(3600),
                totalValueUSD = "100000.00",
                assets = mapOf(
                    "BTC" to assetRow("1.1", "50000.00", "55000.00"),
                    "USD" to assetRow("45000.00", "1.0", "45000.00"),
                ),
                balancesObservedAt = t0.plusSeconds(3600),
            )
            val trade = manualTrade(
                timestamp = tradeTime,
                side = "buy",
                symbol = "BTC",
                volume = "0.1",
                usdAmount = "5000.00",
            )

            val result = RebalancerComparisonCalculator.calculate(
                snapshots = listOf(s1, s2),
                trades = listOf(trade),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points.size shouldBe 2
            result.points[0].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100000.00")
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100000.00")
        }

        "In-flight ledger during balance request latency is not assumed in snapshot and reconciles in next interval" {
            val t0 = now
            val ledgerTime = t0.plusMillis(200)
            val s1 = snapshot(
                timestamp = t0.plusMillis(500),
                totalValueUSD = "100000.00",
                assets = mapOf(
                    "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                    "USD" to assetRow("50000.00", "1.0", "50000.00"),
                ),
                balancesObservedAt = t0,
            )
            val s2 = snapshot(
                timestamp = t0.plusSeconds(3600),
                totalValueUSD = "102500.00",
                assets = mapOf(
                    "BTC" to assetRow("1.05", "50000.00", "52500.00"),
                    "USD" to assetRow("50000.00", "1.0", "50000.00"),
                ),
                balancesObservedAt = t0.plusSeconds(3600),
            )
            val ledger = ledgerEvent(
                timestamp = ledgerTime,
                asset = "BTC",
                amount = "0.05",
                type = KrakenApiConstants.LEDGER_TYPE_STAKING,
            )

            val result = RebalancerComparisonCalculator.calculate(
                snapshots = listOf(s1, s2),
                trades = emptyList(),
                rewards = listOf(ledger),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points.size shouldBe 2
            result.points[0].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100000.00")
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("102500.00")
        }
    }

    private fun assetRow(balance: String, price: String, valueUSD: String): Triple<String, String, String> =
        Triple(balance, price, valueUSD)

    private fun snapshot(
        timestamp: Instant,
        totalValueUSD: String,
        assets: Map<String, Triple<String, String, String>>,
        balancesObservedAt: Instant = timestamp,
    ): PortfolioSnapshot {
        val assetSnapshots = assets.mapValues { (symbol, triple) ->
            assetSnapshot(
                symbol = symbol,
                balance = BigDecimal(triple.first),
                price = BigDecimal(triple.second),
                valueUSD = BigDecimal(triple.third),
                targetPercent = BigDecimal.ZERO,
            )
        }
        return PortfolioSnapshot(
            timestamp = timestamp,
            totalValueUSD = BigDecimal(totalValueUSD),
            assets = assetSnapshots,
            actions = emptyList(),
            drawdownPercent = BigDecimal.ZERO,
            fiatDeploymentPercent = BigDecimal.ZERO,
            effectiveUsdTargetPercent = BigDecimal.ZERO,
            balancesObservedAt = balancesObservedAt,
        )
    }

    private fun trade(
        timestamp: Instant,
        side: String,
        symbol: String,
        volume: String,
        usdAmount: String,
        fee: String = "0",
        success: Boolean = true,
        dryRun: Boolean = false,
        source: TradeSource? = TradeSource.LOCAL_ESTIMATE,
        cycleId: String? = "cycle-1",
        tradeId: String? = null,
        orderTxid: String? = null,
        clientOrderId: String? = null,
        price: String = "0",
    ): TradeRecord = TradeRecord(
        timestamp = timestamp,
        pair = "${symbol}USD",
        side = side,
        symbol = symbol,
        volume = BigDecimal(volume),
        usdAmount = BigDecimal(usdAmount),
        success = success,
        dryRun = dryRun,
        price = BigDecimal(price),
        fee = BigDecimal(fee),
        source = source,
        cycleId = cycleId,
        tradeId = tradeId,
        orderTxid = orderTxid,
        clientOrderId = clientOrderId,
    )

    private fun manualTrade(
        timestamp: Instant,
        side: String,
        symbol: String,
        volume: String,
        usdAmount: String,
        fee: String = "0",
        tradeId: String = "manual-trade-$timestamp",
        orderTxid: String = "manual-order-$timestamp",
    ): TradeRecord = TradeRecord(
        timestamp = timestamp,
        pair = "${symbol}USD",
        side = side,
        symbol = symbol,
        volume = BigDecimal(volume),
        usdAmount = BigDecimal(usdAmount),
        success = true,
        dryRun = false,
        price = BigDecimal.ZERO,
        fee = BigDecimal(fee),
        source = TradeSource.API_FILL,
        cycleId = null,
        clientOrderId = null,
        tradeId = tradeId,
        orderTxid = orderTxid,
    )

    private fun ledgerEvent(
        timestamp: Instant,
        asset: String,
        amount: String,
        type: String = KrakenApiConstants.LEDGER_TYPE_STAKING,
        fee: String = "0",
        ledgerId: String? = null,
        refid: String? = null,
    ): LedgerEvent = LedgerEvent(
        ledgerId = ledgerId ?: "ledger-$timestamp-$asset-$type",
        refid = refid,
        time = timestamp,
        type = type,
        asset = asset,
        amount = BigDecimal(amount),
        fee = BigDecimal(fee),
    )
}
