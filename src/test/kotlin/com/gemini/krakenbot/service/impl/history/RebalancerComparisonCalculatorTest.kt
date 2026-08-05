package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.TestFixtures.assetSnapshot
import com.gemini.krakenbot.model.ComparisonAvailability
import com.gemini.krakenbot.model.ComparisonConfidence
import com.gemini.krakenbot.model.ComparisonUnavailableReason
import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.TradeRecord
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
            (result.latestDifferenceUSD!!.compareTo(BigDecimal.ZERO) > 0) shouldBe true
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
            (result.latestDifferenceUSD!!.compareTo(BigDecimal.ZERO) < 0) shouldBe true
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

        "deposit: unexplained balance change returns ESTIMATED confidence" {
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

            val result = RebalancerComparisonCalculator.calculate(snapshots, emptyList())

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.ESTIMATED
        }

        "withdrawal: unexplained balance decrease returns ESTIMATED confidence" {
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
                    "50000.00",
                    mapOf(
                        "BTC" to assetRow("0.5", "50000.00", "25000.00"),
                        "USD" to assetRow("25000.00", "1.0", "25000.00"),
                    ),
                ),
            )

            val result = RebalancerComparisonCalculator.calculate(snapshots, emptyList())

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.ESTIMATED
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

        "dry-run ignored: estimates confidence" {
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

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.ESTIMATED
        }

        "failed trade ignored: estimates confidence" {
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

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.ESTIMATED
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

        "trade for unknown symbol: returns UNSUPPORTED_TRADE" {
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

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.UNSUPPORTED_TRADE
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
                        "ETH" to assetRow("0", "0", "0"),
                        "USD" to assetRow("50000.00", "1.0", "50000.00"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(7200),
                    "150000.00",
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

        "staking rewards inflate the buy and hold line" {
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

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.ESTIMATED
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("122000.00")
        }

        "dividend ledger events are excluded from comparison" {
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
            val rewards = listOf(ledgerEvent(now.plusSeconds(1800), "BTC", "0.1", LedgerEvent.TYPE_DIVIDEND))

            val result = RebalancerComparisonCalculator.calculate(snapshots, emptyList(), rewards)

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.ESTIMATED
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100000.00")
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
                    "105000.00",
                    mapOf(
                        "BTC" to assetRow("1.1", "50000.00", "55000.00"),
                        "USD" to assetRow("50000.00", "1.0", "50000.00"),
                    ),
                ),
            )
            val rewards = listOf(ledgerEvent(now.minusSeconds(3600), "BTC", "0.1"))

            val result = RebalancerComparisonCalculator.calculate(snapshots, emptyList(), rewards)

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.ESTIMATED
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
    }

    private fun assetRow(balance: String, price: String, valueUSD: String): Triple<String, String, String> =
        Triple(balance, price, valueUSD)

    private fun snapshot(
        timestamp: Instant,
        totalValueUSD: String,
        assets: Map<String, Triple<String, String, String>>,
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
    ): TradeRecord = TradeRecord(
        timestamp = timestamp,
        pair = "${symbol}USD",
        side = side,
        symbol = symbol,
        volume = BigDecimal(volume),
        usdAmount = BigDecimal(usdAmount),
        success = success,
        dryRun = dryRun,
        price = BigDecimal.ZERO,
        fee = BigDecimal(fee),
    )

    private fun ledgerEvent(
        timestamp: Instant,
        asset: String,
        amount: String,
        type: String = LedgerEvent.TYPE_STAKING,
    ): LedgerEvent = LedgerEvent(
        refid = "ref-$timestamp-$asset",
        time = timestamp,
        type = type,
        asset = asset,
        amount = BigDecimal(amount),
    )
}
