package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.TestFixtures.assetSnapshot
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.ComparisonAvailability
import com.gemini.krakenbot.model.ComparisonConfidence
import com.gemini.krakenbot.model.ComparisonUnavailableReason
import com.gemini.krakenbot.model.FundingEvidence
import com.gemini.krakenbot.model.FundingProvenanceResolver
import com.gemini.krakenbot.model.KrakenApiConstants
import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.RebalancerComparison
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

    private val testProvenanceResolver: FundingProvenanceResolver = FundingProvenanceResolver { event ->
        if (event.subtype.isNullOrBlank()) {
            when (event.type) {
                KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL,
                -> FundingEvidence.EXTERNAL

                else -> FundingEvidence.UNRESOLVED
            }
        } else {
            FundingEvidence.UNRESOLVED
        }
    }

    private suspend fun calculate(
        snapshots: List<PortfolioSnapshot>,
        trades: List<TradeRecord> = emptyList(),
        rewards: List<LedgerEvent> = emptyList(),
        knownRebalancerOrderTxids: Set<String> = emptySet(),
        anchorSnapshot: PortfolioSnapshot? = null,
        inceptionSnapshot: PortfolioSnapshot? = null,
        knownInceptionTime: Instant? = null,
        historyTruncated: Boolean = false,
        priceProvider: HistoricalPriceProvider? = null,
        provenanceResolver: FundingProvenanceResolver = testProvenanceResolver,
    ): RebalancerComparison = RebalancerComparisonCalculator.calculate(
        snapshots = snapshots,
        trades = trades,
        rewards = rewards,
        knownRebalancerOrderTxids = knownRebalancerOrderTxids,
        anchorSnapshot = anchorSnapshot,
        inceptionSnapshot = inceptionSnapshot,
        knownInceptionTime = knownInceptionTime,
        historyTruncated = historyTruncated,
        priceProvider = priceProvider,
        provenanceResolver = provenanceResolver,
    )

    init {
        "trade and ledger inside the request window reconcile beyond one second after request start" {
            for (legacyBaseline in listOf(false, true)) {
                val first = snapshot(
                    now,
                    "101.00",
                    mapOf("BTC" to assetRow("1", "1", "1"), "USD" to assetRow("100", "1", "100")),
                    balancesObservedAt = if (legacyBaseline) null else now,
                )
                val next = snapshot(
                    now.plusSeconds(10),
                    "103.00",
                    mapOf("BTC" to assetRow("2", "1", "2"), "USD" to assetRow("101", "1", "101")),
                    balancesObservedAt = now.plusSeconds(8),
                )
                val snapshots = listOf(
                    first,
                    next,
                    next.copy(timestamp = now.plusSeconds(20), balancesObservedAt = now.plusSeconds(19)),
                )
                for (size in listOf(2, 3)) {
                    val result = calculate(
                        snapshots.take(size),
                        listOf(manualTrade(now.plusMillis(10400), "buy", "BTC", "1", "1")),
                        listOf(ledgerEvent(now.plusMillis(10500), "USD", "2")),
                    )
                    result.availability shouldBe ComparisonAvailability.AVAILABLE
                    result.points.size shouldBe size
                    result.points.forEach { it.differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO }
                }
            }
        }

        "events beyond the request window and clock skew still fail closed" {
            val first = snapshot(
                now,
                "101.00",
                mapOf("BTC" to assetRow("1", "1", "1"), "USD" to assetRow("100", "1", "100")),
            )
            val next = snapshot(
                now.plusSeconds(10),
                "101.00",
                mapOf("BTC" to assetRow("2", "1", "2"), "USD" to assetRow("99", "1", "99")),
                balancesObservedAt = now.plusSeconds(8),
            )
            val result = calculate(
                listOf(first, next),
                listOf(manualTrade(now.plusMillis(11001), "buy", "BTC", "1", "1")),
            )
            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE
            result.unavailableAt shouldBe next.timestamp
            result.points shouldBe emptyList()
        }

        "initial and late candidates share one search budget" {
            for (initialCount in listOf(6, 7)) {
                val first = snapshot(
                    now.plusMillis(500),
                    "101.00",
                    mapOf("BTC" to assetRow("1", "1", "1"), "USD" to assetRow("100", "1", "100")),
                    balancesObservedAt = now,
                )
                val next = snapshot(
                    now.plusSeconds(10),
                    "164.00",
                    mapOf("BTC" to assetRow("1", "1", "1"), "USD" to assetRow("163", "1", "163")),
                )
                val initial = (0 until initialCount).map {
                    ledgerEvent(now.plusMillis(100 + it * 10L), "BTC", (1 shl it).toString())
                }
                val late = (0 until 6).map {
                    ledgerEvent(now.plusMillis(10100 + it * 10L), "USD", (1 shl it).toString())
                }
                val result = calculate(
                    listOf(first, next),
                    emptyList(),
                    initial + late,
                )
                if (initialCount == 6) {
                    result.availability shouldBe ComparisonAvailability.AVAILABLE
                    result.points.size shouldBe 2
                    result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
                } else {
                    result.availability shouldBe ComparisonAvailability.UNAVAILABLE
                    result.unavailableReason shouldBe ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE
                    result.unavailableAt shouldBe next.timestamp
                    result.points shouldBe emptyList()
                }
            }
        }

        "initial events across the request window are classified as embedded without double replay" {
            val first = snapshot(
                now.plusSeconds(2),
                "101.00",
                mapOf("BTC" to assetRow("1", "1", "1"), "USD" to assetRow("100", "1", "100")),
                balancesObservedAt = now,
            )
            val next = first.copy(timestamp = now.plusSeconds(10), balancesObservedAt = now.plusSeconds(9))
            val result = calculate(
                listOf(first, next),
                listOf(manualTrade(now.plusMillis(1500), "buy", "BTC", "1", "1")),
                listOf(ledgerEvent(now.plusMillis(1600), "USD", "2")),
            )
            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points.size shouldBe 2
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "twelve distinct boundary events reconcile uniquely but thirteen exceed the search budget" {
            for (count in listOf(9, 12, 13)) {
                val totalReward = (1 shl count) - 1
                val first = snapshot(
                    now,
                    "1.00",
                    mapOf("BTC" to assetRow("1", "1", "1")),
                    balancesObservedAt = null,
                )
                val next = snapshot(
                    now.plusSeconds(10),
                    (1 + totalReward).toString(),
                    mapOf("BTC" to assetRow((1 + totalReward).toString(), "1", (1 + totalReward).toString())),
                    balancesObservedAt = null,
                )
                val result = calculate(
                    listOf(first, next),
                    emptyList(),
                    (0 until count).map { index ->
                        ledgerEvent(now.plusMillis(10100 + index * 10L), "BTC", (1 shl index).toString())
                    },
                )
                if (count <= 12) {
                    result.availability shouldBe ComparisonAvailability.AVAILABLE
                    result.points.size shouldBe 2
                    result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
                } else {
                    result.availability shouldBe ComparisonAvailability.UNAVAILABLE
                    result.unavailableReason shouldBe ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE
                    result.unavailableAt shouldBe next.timestamp
                    result.points shouldBe emptyList()
                }
            }
        }

        "shared baseline: first point has equal values and zero difference" {
            val snapshots = listOf(
                snapshot(now, "50000.00", mapOf("BTC" to assetRow("1.0", "50000.00", "50000.00"))),
                snapshot(now.plusSeconds(3600), "55000.00", mapOf("BTC" to assetRow("1.0", "55000.00", "55000.00"))),
            )

            val result = calculate(snapshots, emptyList())

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

            val result = calculate(snapshots, trades)

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

            val result = calculate(snapshots, trades)

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

            val result = calculate(suffix, emptyList())

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

            val result = calculate(snapshots, emptyList())

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

            val result = calculate(snapshots, emptyList())

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

            val result = calculate(snapshots, emptyList())

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

            val result = calculate(snapshots, emptyList())

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

            val result = calculate(snapshots, emptyList())

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

            val result = calculate(snapshots, emptyList())

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

            val result = calculate(snapshots, emptyList())

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

            val result = calculate(snapshots, emptyList())

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

            val result = calculate(snapshots, trades)

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

            val result = calculate(snapshots, trades)

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

            val result = calculate(snapshots, trades)

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

            val result = calculate(snapshots, trades)

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

            val result = calculate(snapshots, trades)

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

            val result = calculate(snapshots, trades)

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.AMBIGUOUS_TRADE_OWNERSHIP
            result.unavailableAt shouldBe now.plusMillis(1250)

            val lowercaseSymbolResult = calculate(
                snapshots = snapshots,
                trades = trades.map { it.copy(symbol = "btc", pair = "btcUSD") },
            )

            lowercaseSymbolResult.availability shouldBe ComparisonAvailability.UNAVAILABLE
            lowercaseSymbolResult.unavailableReason shouldBe ComparisonUnavailableReason.AMBIGUOUS_TRADE_OWNERSHIP
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

            val result = calculate(snapshots, trades)

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

            val result = calculate(snapshots, trades)

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

            val result = calculate(snapshots, trades)

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

            val result = calculate(snapshots, trades)

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

            val result = calculate(snapshots, trades)

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
            val trades = List(13) { index ->
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

            val result = calculate(snapshots, trades)

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

            val result = calculate(snapshots, trades)

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
        }

        "legacy rounded API cost fallback reconciles a production-shaped fill" {
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
                    "1624.6793492776",
                    mapOf(
                        "PAXG" to assetRow("0.21147716", "4361.24", "922.3026492776"),
                        // The stored Kraken cost is rounded to 21.00, while price * volume is 21.0036882276.
                        "USD" to assetRow("702.3767", "1.0", "702.3767"),
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
                    tradeId = "LEGACY-ROUNDED-FILL",
                    orderTxid = "LEGACY-ROUNDED-ORDER",
                    price = "4361.24",
                ),
            )

            val result = calculate(snapshots, trades)

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO

            val legacyObservationResult = calculate(
                snapshots = snapshots.map { it.copy(balancesObservedAt = null) },
                trades = trades,
            )

            legacyObservationResult.availability shouldBe ComparisonAvailability.AVAILABLE
            legacyObservationResult.confidence shouldBe ComparisonConfidence.RECONCILED
            legacyObservationResult.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO

            val incompatiblePersistedCostResult = calculate(
                snapshots = snapshots.map { it.copy(balancesObservedAt = null) },
                trades = trades.map { it.copy(usdAmount = BigDecimal("21.01")) },
            )

            incompatiblePersistedCostResult.availability shouldBe ComparisonAvailability.UNAVAILABLE
            incompatiblePersistedCostResult.unavailableReason shouldBe
                ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE
        }

        "mixed rounded and precise fill costs reconcile each interval independently" {
            for (knownObservation in listOf(false, true)) {
                val result = calculate(
                    snapshots = mixedCostSnapshots(knownObservation),
                    trades = mixedCostTrades(),
                )

                result.availability shouldBe ComparisonAvailability.AVAILABLE
                result.confidence shouldBe ComparisonConfidence.RECONCILED
                result.points.size shouldBe 4
                result.points.forEach { point ->
                    point.differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
                }
            }
        }

        "authoritative ledger balance reconciles a legacy truncated crypto fee" {
            val result = calculate(
                snapshots = listOf(
                    snapshot(
                        now,
                        "100100.00",
                        mapOf(
                            "BTC" to assetRow("1.00000000", "100000", "100000.00"),
                            "USD" to assetRow("100.00", "1", "100.00"),
                        ),
                    ),
                    snapshot(
                        now.plusSeconds(10),
                        "140491.91",
                        mapOf(
                            "BTC" to assetRow("1.40391909", "100000", "140391.909"),
                            "USD" to assetRow("100.00", "1", "100.00"),
                        ),
                    ),
                ),
                trades = emptyList(),
                rewards = listOf(
                    ledgerEvent(
                        timestamp = now.plusSeconds(5),
                        asset = "BTC",
                        amount = "0.57702727",
                        fee = "0.1731",
                        balance = "1.40391909",
                    ),
                ),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points.size shouldBe 2
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "authoritative ledger balance does not turn an embedded boundary event into a zero delta" {
            val t0 = now
            val result = calculate(
                snapshots = listOf(
                    snapshot(
                        t0.plusMillis(500),
                        "101.00",
                        mapOf(
                            "BTC" to assetRow("1.00000000", "1", "1.00"),
                            "USD" to assetRow("100.00", "1", "100.00"),
                        ),
                        balancesObservedAt = t0,
                    ),
                    snapshot(
                        t0.plusSeconds(10),
                        "101.00",
                        mapOf(
                            "BTC" to assetRow("1.00000000", "1", "1.00"),
                            "USD" to assetRow("100.00", "1", "100.00"),
                        ),
                        balancesObservedAt = t0.plusSeconds(10),
                    ),
                ),
                trades = emptyList(),
                rewards = listOf(
                    ledgerEvent(
                        timestamp = t0.plusMillis(200),
                        asset = "BTC",
                        amount = "0.10000000",
                        balance = "1.00000000",
                    ),
                ),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "legacy cost fallback reports the later interval with an unexplained balance change" {
            val snapshots = mixedCostSnapshots(knownObservation = false).toMutableList()
            val last = snapshots.last()
            snapshots[snapshots.lastIndex] = last.copy(
                assets = last.assets + ("USD" to last.assets.getValue("USD").copy(balance = BigDecimal("93.024"))),
            )

            val result = calculate(snapshots, mixedCostTrades())

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE
            result.unavailableAt shouldBe last.timestamp
            result.points shouldBe emptyList()
        }

        "legacy snapshot boundary trade is reconciled when it is reflected in the next row" {
            val snapshots = listOf(
                snapshot(
                    now,
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                        "USD" to assetRow("50000.00", "1.0", "50000.00"),
                    ),
                    balancesObservedAt = null,
                ),
                snapshot(
                    now.plusMillis(200),
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.1", "50000.00", "55000.00"),
                        "USD" to assetRow("45000.00", "1.0", "45000.00"),
                    ),
                    balancesObservedAt = null,
                ),
            )
            val result = calculate(
                snapshots = snapshots,
                trades = listOf(
                    manualTrade(
                        timestamp = now.plusMillis(100),
                        side = "buy",
                        symbol = "BTC",
                        volume = "0.1",
                        usdAmount = "5000.00",
                    ),
                ),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100000.00")
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "legacy observations with wide intervals reconcile regular trade and ledger events" {
            val first = now
            val second = first.plusSeconds(4)
            val third = first.plusSeconds(8)
            val snapshots = listOf(
                snapshot(
                    first,
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                        "USD" to assetRow("50000.00", "1.0", "50000.00"),
                    ),
                    balancesObservedAt = null,
                ),
                snapshot(
                    second,
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.1", "50000.00", "55000.00"),
                        "USD" to assetRow("45000.00", "1.0", "45000.00"),
                    ),
                ),
                snapshot(
                    third,
                    "100100.00",
                    mapOf(
                        "BTC" to assetRow("1.1", "50000.00", "55000.00"),
                        "USD" to assetRow("45100.00", "1.0", "45100.00"),
                    ),
                    balancesObservedAt = null,
                ),
            )

            val result = calculate(
                snapshots = snapshots,
                trades = listOf(
                    manualTrade(
                        timestamp = first.plusSeconds(2),
                        side = "buy",
                        symbol = "BTC",
                        volume = "0.1",
                        usdAmount = "5000.00",
                    ),
                ),
                rewards = listOf(
                    ledgerEvent(
                        timestamp = second.plusSeconds(2),
                        asset = "USD",
                        amount = "100.00",
                        type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    ),
                ),
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal("50000.00"))),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "legacy previous row with known current boundary reconciles trade and ledger events" {
            val current = now.plusSeconds(2)
            val result = calculate(
                snapshots = listOf(
                    snapshot(
                        now,
                        "100000.00",
                        mapOf(
                            "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                            "USD" to assetRow("50000.00", "1.0", "50000.00"),
                        ),
                        balancesObservedAt = null,
                    ),
                    snapshot(
                        current,
                        "100100.00",
                        mapOf(
                            "BTC" to assetRow("1.1", "50000.00", "55000.00"),
                            "USD" to assetRow("45100.00", "1.0", "45100.00"),
                        ),
                    ),
                ),
                trades = listOf(
                    manualTrade(
                        timestamp = current.plusMillis(500),
                        side = "buy",
                        symbol = "BTC",
                        volume = "0.1",
                        usdAmount = "5000.00",
                    ),
                ),
                rewards = listOf(
                    ledgerEvent(
                        timestamp = current.plusMillis(600),
                        asset = "USD",
                        amount = "100.00",
                        type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    ),
                ),
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal("50000.00"))),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "legacy snapshots with a sub-second observation burst reconcile each ledger once" {
            val snapshots = listOf(
                snapshot(
                    now,
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                        "ETH" to assetRow("1.0", "3000.00", "3000.00"),
                        "SOL" to assetRow("0.0", "100.00", "0.00"),
                        "USD" to assetRow("47000.00", "1.0", "47000.00"),
                    ),
                    balancesObservedAt = null,
                ),
                snapshot(
                    now.plusMillis(200),
                    "100500.00",
                    mapOf(
                        "BTC" to assetRow("1.01", "50000.00", "50500.00"),
                        "ETH" to assetRow("1.0", "3000.00", "3000.00"),
                        "SOL" to assetRow("0.0", "100.00", "0.00"),
                        "USD" to assetRow("47000.00", "1.0", "47000.00"),
                    ),
                    balancesObservedAt = null,
                ),
                snapshot(
                    now.plusMillis(400),
                    "100800.00",
                    mapOf(
                        "BTC" to assetRow("1.01", "50000.00", "50500.00"),
                        "ETH" to assetRow("1.1", "3000.00", "3300.00"),
                        "SOL" to assetRow("0.0", "100.00", "0.00"),
                        "USD" to assetRow("47000.00", "1.0", "47000.00"),
                    ),
                    balancesObservedAt = null,
                ),
                snapshot(
                    now.plusMillis(600),
                    "100850.00",
                    mapOf(
                        "BTC" to assetRow("1.01", "50000.00", "50500.00"),
                        "ETH" to assetRow("1.1", "3000.00", "3300.00"),
                        "SOL" to assetRow("0.5", "100.00", "50.00"),
                        "USD" to assetRow("47000.00", "1.0", "47000.00"),
                    ),
                    balancesObservedAt = null,
                ),
            )
            val ledgers = listOf(
                ledgerEvent(now.plusMillis(100), "BTC", "0.01"),
                ledgerEvent(now.plusMillis(300), "ETH", "0.1"),
                ledgerEvent(now.plusMillis(500), "SOL", "0.5"),
            )

            val result = calculate(snapshots, emptyList(), rewards = ledgers)

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "legacy observation burst with multiple valid ledger assignments fails closed" {
            val snapshots = listOf(
                snapshot(
                    now,
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                        "USD" to assetRow("50000.00", "1.0", "50000.00"),
                    ),
                    balancesObservedAt = null,
                ),
                snapshot(
                    now.plusMillis(200),
                    "105000.00",
                    mapOf(
                        "BTC" to assetRow("1.1", "50000.00", "55000.00"),
                        "USD" to assetRow("50000.00", "1.0", "50000.00"),
                    ),
                    balancesObservedAt = null,
                ),
                snapshot(
                    now.plusMillis(400),
                    "105000.00",
                    mapOf(
                        "BTC" to assetRow("1.1", "50000.00", "55000.00"),
                        "USD" to assetRow("50000.00", "1.0", "50000.00"),
                    ),
                    balancesObservedAt = null,
                ),
            )
            val ledgers = listOf(
                ledgerEvent(now.plusMillis(100), "BTC", "0.1", ledgerId = "AMBIGUOUS-1"),
                ledgerEvent(now.plusMillis(150), "BTC", "0.1", ledgerId = "AMBIGUOUS-2"),
            )

            val result = calculate(snapshots, emptyList(), rewards = ledgers)

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE
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

            val result = calculate(snapshots, trades)

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

            val result = calculate(snapshots, trades)

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

            val result = calculate(snapshots, trades)

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

            val result = calculate(snapshots, trades)

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.UNSUPPORTED_TRADE
        }

        "insufficient history: zero and one snapshot both return INSUFFICIENT_SNAPSHOTS" {
            val zeroResult = calculate(emptyList(), emptyList())
            zeroResult.availability shouldBe ComparisonAvailability.UNAVAILABLE
            zeroResult.unavailableReason shouldBe ComparisonUnavailableReason.INSUFFICIENT_SNAPSHOTS

            val oneResult = calculate(
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

            val result = calculate(snapshots, emptyList())

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

            val result = calculate(snapshots, emptyList())

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

            val result = calculate(snapshots, trades)

            result.availability shouldBe ComparisonAvailability.AVAILABLE
        }

        "out-of-order snapshots: sorts snapshots before calculating" {
            val snapshots = listOf(
                snapshot(now.plusSeconds(3600), "55000.00", mapOf("BTC" to assetRow("1.0", "55000.00", "55000.00"))),
                snapshot(now, "50000.00", mapOf("BTC" to assetRow("1.0", "50000.00", "50000.00"))),
            )

            val result = calculate(snapshots, emptyList())

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

            val result = calculate(snapshots, trades)

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

            val result = calculate(snapshots, listOf(trade))

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

            val result = calculate(snapshots, listOf(trade))

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

            val result = calculate(snapshots, emptyList())

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

            val result = calculate(snapshots, trades)

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

            val result = calculate(snapshots, emptyList(), rewards)

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

            val result = calculate(snapshots, emptyList(), rewards)

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

            val result = calculate(snapshots, emptyList(), rewards)

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

            val result = calculate(snapshots, emptyList(), rewards)

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

            val result = calculate(snapshots, emptyList(), rewards)

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

            val result = calculate(snapshots, trades, emptyList())

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

            val result = calculate(snapshots, emptyList(), ledgers)

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

            val result = calculate(snapshots, emptyList(), ledgers)

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

            val result = calculate(snapshots, emptyList(), ledgers)

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

            val result = calculate(snapshots, trades)

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

            val result = calculate(snapshots, trades)

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

            val result = calculate(snapshots, trades)

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

            val result = calculate(snapshots, trades)

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

            val result = calculate(snapshots, trades)

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

            val result = calculate(snapshots, trades)

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

            val result = calculate(snapshots, trades)

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

            val result = calculate(snapshots, trades)

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

            val result = calculate(
                snapshots,
                emptyList(),
                ledgers,
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal("50000.00"))),
            )

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

            val result = calculate(
                snapshots,
                emptyList(),
                ledgers,
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal("50000.00"))),
            )

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

            // 1 BTC @ 50k contribution = $50,000 invested by inception weights
            // (50/50): +0.5 BTC and +$25,000 cash. The bot instead holds the
            // full coin, so when BTC reaches 60k the bot is ahead by exactly
            // the allocation effect (0.5 * 10k) — strategy signal, not
            // contribution alpha: both sides received the same $50,000.
            val result = calculate(
                snapshots,
                emptyList(),
                ledgers,
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal("50000.00"))),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points[1].rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("170000.00")
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("165000.00")
            result.latestDifferenceUSD!! shouldBeEqualComparingTo BigDecimal("5000.00")
        }

        "confirmed crypto deposit with fee scales net contribution across inception weights" {
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
                    "139940.00",
                    mapOf(
                        "BTC" to assetRow("1.499", "60000.00", "89940.00"),
                        "USD" to assetRow("50000.00", "1.0", "50000.00"),
                    ),
                ),
            )
            val ledgers = listOf(
                ledgerEvent(
                    timestamp = now.plusSeconds(1800),
                    asset = "BTC",
                    amount = "0.500",
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    fee = "0.001",
                ),
            )

            // Net contribution = (0.500 - 0.001) BTC = 0.499 BTC @ $50,000 = $24,950.00 USD.
            // Inception weights (50/50): B&H receives +0.2495 BTC and +$12,475.00 cash.
            // At snap 2 (BTC @ $60k):
            // Actual holds 1.499 BTC @ $60k ($89,940) + $50k USD = $139,940.00.
            // B&H holds 1.2495 BTC @ $60k ($74,970) + $62,475 USD = $137,445.00.
            // Strategy divergence = $139,940 - $137,445 = $2,495.00.
            // The deposit is not replayed as an investment return (which would have added
            // 0.499 BTC directly to B&H with zero divergence).
            val result = calculate(
                snapshots,
                emptyList(),
                ledgers,
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal("50000.00"))),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points[1].rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("139940.00")
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("137445.00")
            result.latestDifferenceUSD!! shouldBeEqualComparingTo BigDecimal("2495.00")
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

            // 0.501 BTC @ 50k withdrawal = $25,050 removed proportionally from
            // the whole synthetic portfolio (factor 0.7495). The bot holds
            // 0.499 BTC into the rally while Buy & Hold holds 0.7495, so the
            // bot trails by the allocation effect — a fair cost of withdrawing
            // an appreciating asset, identical in kind for both sides.
            val result = calculate(
                snapshots,
                emptyList(),
                ledgers,
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal("50000.00"))),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points[1].rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("79940.00")
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("82445.00")
            result.latestDifferenceUSD!! shouldBeEqualComparingTo BigDecimal("-2505.00")
        }

        "Owner contribution is invested by original inception weights with no immediate alpha" {
            val t0 = Instant.parse("2026-06-01T12:00:00Z")
            val t1 = Instant.parse("2026-06-10T12:00:00Z")
            val tMid = Instant.parse("2026-06-10T18:00:00Z")
            val t2 = Instant.parse("2026-06-11T12:00:00Z")
            val t3 = Instant.parse("2026-06-12T12:00:00Z")
            val inceptionAssets = mapOf(
                "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                "ETH" to assetRow("10.0", "3000.00", "30000.00"),
                "USD" to assetRow("20000.00", "1.0", "20000.00"),
            )
            val inceptionSnap = snapshot(t0, "100000.00", inceptionAssets)
            // Pre-contribution drift is shared market movement: both sides agree.
            val s1 = snapshot(
                t1,
                "105000.00",
                mapOf(
                    "BTC" to assetRow("1.0", "55000.00", "55000.00"),
                    "ETH" to assetRow("10.0", "3000.00", "30000.00"),
                    "USD" to assetRow("20000.00", "1.0", "20000.00"),
                ),
            )
            // Bot holds the $10k as cash for now; Buy & Hold invests it by
            // inception weights (50/30/20). Same prices: identical totals.
            val s2 = snapshot(
                t2,
                "115000.00",
                mapOf(
                    "BTC" to assetRow("1.0", "55000.00", "55000.00"),
                    "ETH" to assetRow("10.0", "3000.00", "30000.00"),
                    "USD" to assetRow("30000.00", "1.0", "30000.00"),
                ),
            )
            // BTC +20%, ETH +10%: benchmark moves on its weight-invested
            // holdings (1.09090909 BTC, 11 ETH, 22k USD) while the bot holds
            // cash, so the strategies diverge naturally from here.
            val s3 = snapshot(
                t3,
                "129000.00",
                mapOf(
                    "BTC" to assetRow("1.0", "66000.00", "66000.00"),
                    "ETH" to assetRow("10.0", "3300.00", "33000.00"),
                    "USD" to assetRow("30000.00", "1.0", "30000.00"),
                ),
            )
            val contribution = ledgerEvent(
                timestamp = tMid,
                asset = "USD",
                amount = "10000.00",
                type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
            )
            val prices = mapPriceProvider(
                mapOf("BTC" to BigDecimal("55000.00"), "ETH" to BigDecimal("3000.00")),
            )

            val result = calculate(
                snapshots = listOf(s1, s2, s3),
                trades = emptyList(),
                rewards = listOf(contribution),
                inceptionSnapshot = inceptionSnap,
                priceProvider = prices,
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            // $10k split 5k/3k/2k: no immediate delta from the contribution itself.
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("115000.00")
            result.points[1].differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
            // Benchmark: 1.09090909 BTC @66k + 11 ETH @3.3k + 22k USD = 130300.
            result.points[2].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("130300.00")
            result.points[2].differenceUSD shouldBeEqualComparingTo BigDecimal("-1300.00")
        }

        "Crypto contribution is valued at event time then invested by inception weights" {
            val t0 = Instant.parse("2026-06-01T12:00:00Z")
            val t1 = Instant.parse("2026-06-10T12:00:00Z")
            val tMid = Instant.parse("2026-06-10T18:00:00Z")
            val t2 = Instant.parse("2026-06-11T12:00:00Z")
            val inceptionAssets = mapOf(
                "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                "ETH" to assetRow("10.0", "3000.00", "30000.00"),
                "USD" to assetRow("20000.00", "1.0", "20000.00"),
            )
            val inceptionSnap = snapshot(t0, "100000.00", inceptionAssets)
            val s1 = snapshot(t1, "100000.00", inceptionAssets)
            // 0.2 BTC @ 55k = $11,000 -> 5.5k BTC (0.1) + 3.3k ETH (1.1) + 2.2k USD.
            // Market also lifts baseline BTC 50k -> 55k (+5k): 116k total.
            val s2 = snapshot(
                t2,
                "116000.00",
                mapOf(
                    "BTC" to assetRow("1.2", "55000.00", "66000.00"),
                    "ETH" to assetRow("10.0", "3000.00", "30000.00"),
                    "USD" to assetRow("20000.00", "1.0", "20000.00"),
                ),
            )
            val contribution = ledgerEvent(
                timestamp = tMid,
                asset = "BTC",
                amount = "0.2",
                type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
            )
            val prices = mapPriceProvider(
                mapOf("BTC" to BigDecimal("55000.00"), "ETH" to BigDecimal("3000.00")),
            )

            val result = calculate(
                snapshots = listOf(s1, s2),
                trades = emptyList(),
                rewards = listOf(contribution),
                inceptionSnapshot = inceptionSnap,
                priceProvider = prices,
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("116000.00")
            result.points[1].differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "Owner withdrawal reduces the synthetic portfolio proportionally by market value" {
            val t0 = Instant.parse("2026-06-01T12:00:00Z")
            val t1 = Instant.parse("2026-06-10T12:00:00Z")
            val tMid = Instant.parse("2026-06-10T18:00:00Z")
            val t2 = Instant.parse("2026-06-11T12:00:00Z")
            val inceptionAssets = mapOf(
                "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                "ETH" to assetRow("10.0", "3000.00", "30000.00"),
                "USD" to assetRow("20000.00", "1.0", "20000.00"),
            )
            val inceptionSnap = snapshot(t0, "100000.00", inceptionAssets)
            val s1 = snapshot(t1, "100000.00", inceptionAssets)
            // $10k of $100k withdrawn from cash: bot holds the rest.
            // Benchmark scales the whole thesis by 0.9 -> 45k + 27k + 18k.
            val s2 = snapshot(
                t2,
                "90000.00",
                mapOf(
                    "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                    "ETH" to assetRow("10.0", "3000.00", "30000.00"),
                    "USD" to assetRow("10000.00", "1.0", "10000.00"),
                ),
            )
            val withdrawal = ledgerEvent(
                timestamp = tMid,
                asset = "USD",
                amount = "-10000.00",
                type = KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL,
            )
            val prices = mapPriceProvider(
                mapOf("BTC" to BigDecimal("50000.00"), "ETH" to BigDecimal("3000.00")),
            )

            val result = calculate(
                snapshots = listOf(s1, s2),
                trades = emptyList(),
                rewards = listOf(withdrawal),
                inceptionSnapshot = inceptionSnap,
                priceProvider = prices,
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("90000.00")
            result.points[1].differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "Multiple contributions over time accumulate without artificial alpha" {
            val t0 = Instant.parse("2026-06-01T12:00:00Z")
            val t1 = Instant.parse("2026-06-10T12:00:00Z")
            val tA = Instant.parse("2026-06-10T18:00:00Z")
            val t2 = Instant.parse("2026-06-11T12:00:00Z")
            val tB = Instant.parse("2026-06-11T18:00:00Z")
            val t3 = Instant.parse("2026-06-12T12:00:00Z")
            val flatAssets = mapOf(
                "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                "USD" to assetRow("50000.00", "1.0", "50000.00"),
            )
            val inceptionSnap = snapshot(t0, "100000.00", flatAssets)
            val s1 = snapshot(t1, "100000.00", flatAssets)
            val s2 = snapshot(
                t2,
                "105000.00",
                mapOf(
                    "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                    "USD" to assetRow("55000.00", "1.0", "55000.00"),
                ),
            )
            val s3 = snapshot(
                t3,
                "108000.00",
                mapOf(
                    "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                    "USD" to assetRow("58000.00", "1.0", "58000.00"),
                ),
            )
            val prices = mapPriceProvider(mapOf("BTC" to BigDecimal("50000.00")))

            val result = calculate(
                snapshots = listOf(s1, s2, s3),
                trades = emptyList(),
                rewards = listOf(
                    ledgerEvent(
                        timestamp = tA,
                        asset = "USD",
                        amount = "5000.00",
                        type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    ),
                    ledgerEvent(
                        timestamp = tB,
                        asset = "USD",
                        amount = "3000.00",
                        type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    ),
                ),
                inceptionSnapshot = inceptionSnap,
                priceProvider = prices,
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("105000.00")
            result.points[2].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("108000.00")
            result.latestDifferenceUSD!! shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "Unpriceable contribution fails closed with MISSING_PRICE" {
            val t0 = Instant.parse("2026-06-01T12:00:00Z")
            val t1 = Instant.parse("2026-06-10T12:00:00Z")
            val tMid = Instant.parse("2026-06-10T18:00:00Z")
            val t2 = Instant.parse("2026-06-11T12:00:00Z")
            val inceptionAssets = mapOf(
                "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                "USD" to assetRow("50000.00", "1.0", "50000.00"),
            )
            val inceptionSnap = snapshot(t0, "100000.00", inceptionAssets)
            val s1 = snapshot(t1, "100000.00", inceptionAssets)
            val s2 = snapshot(t2, "150000.00", inceptionAssets)

            // s2 reflects the deposit so reconciliation passes and the
            // failure comes from pricing, not from an unexplained balance.
            val s2funded = snapshot(
                t2,
                "150000.00",
                mapOf(
                    "BTC" to assetRow("2.0", "50000.00", "100000.00"),
                    "USD" to assetRow("50000.00", "1.0", "50000.00"),
                ),
            )

            val result = calculate(
                snapshots = listOf(s1, s2funded),
                trades = emptyList(),
                rewards = listOf(
                    ledgerEvent(
                        timestamp = tMid,
                        asset = "BTC",
                        amount = "1.0",
                        type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    ),
                ),
                inceptionSnapshot = inceptionSnap,
                priceProvider = mapPriceProvider(emptyMap()),
            )

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.MISSING_PRICE
        }

        "Ambiguous ledger fails closed with AMBIGUOUS_LEDGER_TYPE" {
            val t0 = Instant.parse("2026-06-01T12:00:00Z")
            val t1 = Instant.parse("2026-06-10T12:00:00Z")
            val tMid = t1.plusSeconds(1800)
            val t2 = Instant.parse("2026-06-10T13:00:00Z")
            val flatAssets = mapOf(
                "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                "USD" to assetRow("50000.00", "1.0", "50000.00"),
            )
            val inceptionSnap = snapshot(t0, "100000.00", flatAssets)
            val s1 = snapshot(t1, "100000.00", flatAssets)
            // s2 reflects the +500 so reconciliation passes and the failure
            // comes from classification, not from an unexplained balance.
            val s2funded = snapshot(
                t2,
                "100500.00",
                mapOf(
                    "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                    "USD" to assetRow("50500.00", "1.0", "50500.00"),
                ),
            )

            val result = calculate(
                snapshots = listOf(s1, s2funded),
                trades = emptyList(),
                rewards = listOf(
                    LedgerEvent(
                        ledgerId = "ambiguous-1",
                        time = tMid,
                        type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                        subtype = "mystery-plumbing",
                        asset = "USD",
                        amount = BigDecimal("500.00"),
                    ),
                ),
                inceptionSnapshot = inceptionSnap,
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal("50000.00"))),
            )

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.AMBIGUOUS_LEDGER_TYPE
        }

        "Pre-window contribution without prices fails closed at the intermediate build" {
            val t0 = Instant.parse("2026-06-01T12:00:00Z")
            val tMid = Instant.parse("2026-06-05T12:00:00Z")
            val t1 = Instant.parse("2026-06-10T12:00:00Z")
            val t2 = Instant.parse("2026-06-10T13:00:00Z")
            val flatAssets = mapOf(
                "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                "USD" to assetRow("50000.00", "1.0", "50000.00"),
            )
            val inceptionSnap = snapshot(t0, "100000.00", flatAssets)
            // s1 reflects the +1 BTC so reconciliation passes; pricing the
            // pre-window contribution still fails closed.
            val s1 = snapshot(
                t1,
                "150000.00",
                mapOf(
                    "BTC" to assetRow("2.0", "50000.00", "100000.00"),
                    "USD" to assetRow("50000.00", "1.0", "50000.00"),
                ),
            )
            val s2 = snapshot(
                t2,
                "150000.00",
                mapOf(
                    "BTC" to assetRow("2.0", "50000.00", "100000.00"),
                    "USD" to assetRow("50000.00", "1.0", "50000.00"),
                ),
            )

            val result = calculate(
                snapshots = listOf(s1, s2),
                trades = emptyList(),
                rewards = listOf(
                    ledgerEvent(
                        timestamp = tMid,
                        asset = "BTC",
                        amount = "1.0",
                        type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    ),
                ),
                inceptionSnapshot = inceptionSnap,
                priceProvider = mapPriceProvider(emptyMap()),
            )

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.MISSING_PRICE
        }

        "Withdrawal replay without valuation prices fails closed" {
            val t0 = Instant.parse("2026-06-01T12:00:00Z")
            val t1 = Instant.parse("2026-06-10T12:00:00Z")
            val tMid = Instant.parse("2026-06-10T18:00:00Z")
            val t2 = Instant.parse("2026-06-11T12:00:00Z")
            val flatAssets = mapOf(
                "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                "USD" to assetRow("50000.00", "1.0", "50000.00"),
            )
            val inceptionSnap = snapshot(t0, "100000.00", flatAssets)
            val s1 = snapshot(t1, "100000.00", flatAssets)
            val s2 = snapshot(
                t2,
                "90000.00",
                mapOf(
                    "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                    "USD" to assetRow("40000.00", "1.0", "40000.00"),
                ),
            )

            // No BTC price: the $10k withdrawal builds (USD needs none) but
            // valuing the synthetic portfolio for the proportional cut fails.
            val result = calculate(
                snapshots = listOf(s1, s2),
                trades = emptyList(),
                rewards = listOf(
                    ledgerEvent(
                        timestamp = tMid,
                        asset = "USD",
                        amount = "-10000.00",
                        type = KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL,
                    ),
                ),
                inceptionSnapshot = inceptionSnap,
                priceProvider = mapPriceProvider(emptyMap()),
            )

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.MISSING_PRICE
        }

        "Zero contribution-time price fails closed" {
            val t0 = Instant.parse("2026-06-01T12:00:00Z")
            val t1 = Instant.parse("2026-06-10T12:00:00Z")
            val tMid = Instant.parse("2026-06-10T18:00:00Z")
            val t2 = Instant.parse("2026-06-11T12:00:00Z")
            val flatAssets = mapOf(
                "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                "USD" to assetRow("50000.00", "1.0", "50000.00"),
            )
            val inceptionSnap = snapshot(t0, "100000.00", flatAssets)
            val s1 = snapshot(t1, "100000.00", flatAssets)
            // s2 reflects the +0.5 BTC so reconciliation passes and the
            // failure comes from pricing, not from an unexplained balance.
            val s2 = snapshot(
                t2,
                "125000.00",
                mapOf(
                    "BTC" to assetRow("1.5", "50000.00", "75000.00"),
                    "USD" to assetRow("50000.00", "1.0", "50000.00"),
                ),
            )

            val result = calculate(
                snapshots = listOf(s1, s2),
                trades = emptyList(),
                rewards = listOf(
                    ledgerEvent(
                        timestamp = tMid,
                        asset = "BTC",
                        amount = "0.5",
                        type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    ),
                ),
                inceptionSnapshot = inceptionSnap,
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal.ZERO)),
            )

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.MISSING_PRICE
        }

        "Missing allocation-asset price fails the contribution closed" {
            val t0 = Instant.parse("2026-06-01T12:00:00Z")
            val t1 = Instant.parse("2026-06-10T12:00:00Z")
            val tMid = Instant.parse("2026-06-10T18:00:00Z")
            val t2 = Instant.parse("2026-06-11T12:00:00Z")
            val inceptionAssets = mapOf(
                "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                "ETH" to assetRow("10.0", "3000.00", "30000.00"),
                "USD" to assetRow("20000.00", "1.0", "20000.00"),
            )
            val inceptionSnap = snapshot(t0, "100000.00", inceptionAssets)
            val s1 = snapshot(t1, "100000.00", inceptionAssets)
            // s2 reflects the +10k USD so reconciliation passes and the
            // failure comes from allocation pricing, not from balances.
            val s2 = snapshot(
                t2,
                "110000.00",
                mapOf(
                    "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                    "ETH" to assetRow("10.0", "3000.00", "30000.00"),
                    "USD" to assetRow("30000.00", "1.0", "30000.00"),
                ),
            )

            // USD deposit prices fine, but the BTC allocation leg has no
            // contribution-time price: fail closed, do not half-invest.
            val result = calculate(
                snapshots = listOf(s1, s2),
                trades = emptyList(),
                rewards = listOf(
                    ledgerEvent(
                        timestamp = tMid,
                        asset = "USD",
                        amount = "10000.00",
                        type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    ),
                ),
                inceptionSnapshot = inceptionSnap,
                priceProvider = mapPriceProvider(mapOf("ETH" to BigDecimal("3000.00"))),
            )

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.MISSING_PRICE
        }

        "Non-positive funding amounts are skipped without moving the benchmark" {
            val t0 = Instant.parse("2026-06-01T12:00:00Z")
            val t1 = Instant.parse("2026-06-10T12:00:00Z")
            val tMid = Instant.parse("2026-06-10T18:00:00Z")
            val t2 = Instant.parse("2026-06-11T12:00:00Z")
            val flatAssets = mapOf(
                "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                "USD" to assetRow("50000.00", "1.0", "50000.00"),
            )
            val inceptionSnap = snapshot(t0, "100000.00", flatAssets)
            // Bot balance drops $100 on a negative-amount deposit row (a
            // correction-style row): the benchmark skips it rather than
            // investing a negative contribution.
            val s1 = snapshot(t1, "100000.00", flatAssets)
            val s2 = snapshot(
                t2,
                "99900.00",
                mapOf(
                    "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                    "USD" to assetRow("49900.00", "1.0", "49900.00"),
                ),
            )

            val result = calculate(
                snapshots = listOf(s1, s2),
                trades = emptyList(),
                rewards = listOf(
                    ledgerEvent(
                        timestamp = tMid,
                        asset = "USD",
                        amount = "-100.00",
                        type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    ),
                ),
                inceptionSnapshot = inceptionSnap,
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal("50000.00"))),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100000.00")
            result.points[1].differenceUSD shouldBeEqualComparingTo BigDecimal("-100.00")
        }

        "Malformed positive-amount withdrawal is skipped without moving the benchmark" {
            val t0 = Instant.parse("2026-06-01T12:00:00Z")
            val t1 = Instant.parse("2026-06-10T12:00:00Z")
            val tMid = Instant.parse("2026-06-10T18:00:00Z")
            val t2 = Instant.parse("2026-06-11T12:00:00Z")
            val flatAssets = mapOf(
                "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                "USD" to assetRow("50000.00", "1.0", "50000.00"),
            )
            val inceptionSnap = snapshot(t0, "100000.00", flatAssets)
            val s1 = snapshot(t1, "100000.00", flatAssets)
            val s2 = snapshot(
                t2,
                "100100.00",
                mapOf(
                    "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                    "USD" to assetRow("50100.00", "1.0", "50100.00"),
                ),
            )

            val result = calculate(
                snapshots = listOf(s1, s2),
                trades = emptyList(),
                rewards = listOf(
                    ledgerEvent(
                        timestamp = tMid,
                        asset = "USD",
                        amount = "100.00",
                        type = KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL,
                    ),
                ),
                inceptionSnapshot = inceptionSnap,
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal("50000.00"))),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100000.00")
            result.points[1].differenceUSD shouldBeEqualComparingTo BigDecimal("100.00")
        }

        "Same-timestamp deposit and larger spend net to one synthetic withdrawal" {
            val t0 = Instant.parse("2026-06-01T12:00:00Z")
            val t1 = Instant.parse("2026-06-10T12:00:00Z")
            val tMid = Instant.parse("2026-06-10T18:00:00Z")
            val t2 = Instant.parse("2026-06-11T12:00:00Z")
            val flatAssets = mapOf(
                "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                "USD" to assetRow("50000.00", "1.0", "50000.00"),
            )
            val inceptionSnap = snapshot(t0, "100000.00", flatAssets)
            val s1 = snapshot(t1, "100000.00", flatAssets)
            // +3000 funding, -5000 spend at one instant: net -$2000, so the
            // benchmark scales 2% off the whole thesis (factor 0.98).
            val s2 = snapshot(
                t2,
                "98000.00",
                mapOf(
                    "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                    "USD" to assetRow("48000.00", "1.0", "48000.00"),
                ),
            )

            val result = calculate(
                snapshots = listOf(s1, s2),
                trades = emptyList(),
                rewards = listOf(
                    ledgerEvent(
                        timestamp = tMid,
                        asset = "USD",
                        amount = "3000.00",
                        type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    ),
                    ledgerEvent(
                        timestamp = tMid,
                        asset = "USD",
                        amount = "-5000.00",
                        type = KrakenApiConstants.LEDGER_TYPE_SPEND,
                    ),
                ),
                inceptionSnapshot = inceptionSnap,
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal("50000.00"))),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("98000.00")
            result.points[1].differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "Withdrawal beyond synthetic holdings fails closed instead of flooring" {
            val t0 = Instant.parse("2026-06-01T12:00:00Z")
            val t1 = Instant.parse("2026-06-10T12:00:00Z")
            val tMid = Instant.parse("2026-06-10T18:00:00Z")
            val t2 = Instant.parse("2026-06-11T12:00:00Z")
            val flatAssets = mapOf(
                "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                "USD" to assetRow("50000.00", "1.0", "50000.00"),
            )
            val inceptionSnap = snapshot(t0, "100000.00", flatAssets)
            val s1 = snapshot(t1, "100000.00", flatAssets)
            // Mechanically reconcilable (-150k USD against the withdrawal
            // row) but economically impossible: the thesis never held it.
            val s2 = snapshot(
                t2,
                "0.00",
                mapOf(
                    "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                    "USD" to assetRow("-50000.00", "1.0", "-50000.00"),
                ),
            )

            val result = calculate(
                snapshots = listOf(s1, s2),
                trades = emptyList(),
                rewards = listOf(
                    ledgerEvent(
                        timestamp = tMid,
                        asset = "USD",
                        amount = "-150000.00",
                        type = KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL,
                    ),
                ),
                inceptionSnapshot = inceptionSnap,
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal("50000.00"))),
            )

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE
        }

        "Truncated history fails closed with INCEPTION_HISTORY_TRUNCATED" {
            val t1 = Instant.parse("2026-06-10T12:00:00Z")
            val t2 = Instant.parse("2026-06-10T13:00:00Z")
            val flatAssets = mapOf(
                "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                "USD" to assetRow("50000.00", "1.0", "50000.00"),
            )

            val result = calculate(
                snapshots = listOf(
                    snapshot(t1, "100000.00", flatAssets),
                    snapshot(t2, "100000.00", flatAssets),
                ),
                trades = emptyList(),
                rewards = emptyList(),
                inceptionSnapshot = null,
                knownInceptionTime = Instant.parse("2026-01-01T12:00:00Z"),
                historyTruncated = true,
            )

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.INCEPTION_HISTORY_TRUNCATED
            result.baselineTimestamp shouldBe Instant.parse("2026-01-01T12:00:00Z")
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

            val result = calculate(snapshots, emptyList(), ledgers)

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

            val result = calculate(snapshots, emptyList(), ledgers)

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

            val result = calculate(snapshots, trades, ledgers)

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

            val result = calculate(snapshots, trades)

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

            val result = calculate(snapshots, trades)

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

            val result = calculate(snapshots, trades)

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

            val result = calculate(
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

            val result = calculate(
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

            val result = calculate(snapshots, emptyList(), ledgers)

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

            val result = calculate(snapshots, emptyList(), ledgers)

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
                    refid = "FT-CARD-DEP-1",
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

            val result = calculate(snapshots, emptyList(), ledgers)

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

            val result = calculate(snapshots, emptyList(), ledgers)

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

            val result = calculate(snapshots, emptyList(), ledgers)

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

            val result = calculate(snapshots, emptyList(), ledgers)

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

            val result = calculate(listOf(s1, s2), trades)

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

            val result = calculate(
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

            val result = calculate(listOf(s1, s2), trades)

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

            val result = calculate(
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

            val result = calculate(
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

            val result = calculate(
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

            val result = calculate(
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

            val res1 = calculate(
                snapshots = listOf(s1, s2),
                trades = emptyList(),
                anchorSnapshot = anchorFuture,
            )
            res1.availability shouldBe ComparisonAvailability.AVAILABLE

            val res2 = calculate(
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

            val res = calculate(
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

            val res = calculate(
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

            val result = calculate(
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

            val result = calculate(
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

        "No anchor: initial candidate trade NOT embedded reconciles as post-baseline" {
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
                    "BTC" to assetRow("1.1", "50000.00", "55000.00"),
                    "USD" to assetRow("45000.00", "1.0", "45000.00"),
                ),
                balancesObservedAt = t0.plusSeconds(3600),
            )
            val trade = manualTrade(
                timestamp = t0.plusMillis(200),
                side = "buy",
                symbol = "BTC",
                volume = "0.1",
                usdAmount = "5000.00",
            )

            val result = calculate(
                snapshots = listOf(s1, s2),
                trades = listOf(trade),
                anchorSnapshot = null,
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points.size shouldBe 2
            result.points[0].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100000.00")
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100000.00")
            result.points[1].differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "No anchor: initial candidate trade IS embedded reconciles without post-baseline replay" {
            val t0 = now
            val s1 = snapshot(
                timestamp = t0.plusMillis(500),
                totalValueUSD = "100000.00",
                assets = mapOf(
                    "BTC" to assetRow("1.1", "50000.00", "55000.00"),
                    "USD" to assetRow("45000.00", "1.0", "45000.00"),
                ),
                balancesObservedAt = t0,
            )
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
                timestamp = t0.plusMillis(200),
                side = "buy",
                symbol = "BTC",
                volume = "0.1",
                usdAmount = "5000.00",
            )

            val result = calculate(
                snapshots = listOf(s1, s2),
                trades = listOf(trade),
                anchorSnapshot = null,
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points.size shouldBe 2
            result.points[0].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100000.00")
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100000.00")
            result.points[1].differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "No anchor: initial candidate ledger NOT embedded reconciles as post-baseline" {
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
                totalValueUSD = "102500.00",
                assets = mapOf(
                    "BTC" to assetRow("1.05", "50000.00", "52500.00"),
                    "USD" to assetRow("50000.00", "1.0", "50000.00"),
                ),
                balancesObservedAt = t0.plusSeconds(3600),
            )
            val ledger = ledgerEvent(
                timestamp = t0.plusMillis(200),
                asset = "BTC",
                amount = "0.05",
                type = KrakenApiConstants.LEDGER_TYPE_STAKING,
            )

            val result = calculate(
                snapshots = listOf(s1, s2),
                trades = emptyList(),
                rewards = listOf(ledger),
                anchorSnapshot = null,
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points.size shouldBe 2
            result.points[0].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100000.00")
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("102500.00")
            result.points[1].differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "No anchor: initial candidate ledger IS embedded reconciles without post-baseline replay" {
            val t0 = now
            val s1 = snapshot(
                timestamp = t0.plusMillis(500),
                totalValueUSD = "102500.00",
                assets = mapOf(
                    "BTC" to assetRow("1.05", "50000.00", "52500.00"),
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
                timestamp = t0.plusMillis(200),
                asset = "BTC",
                amount = "0.05",
                type = KrakenApiConstants.LEDGER_TYPE_STAKING,
            )

            val result = calculate(
                snapshots = listOf(s1, s2),
                trades = emptyList(),
                rewards = listOf(ledger),
                anchorSnapshot = null,
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points.size shouldBe 2
            result.points[0].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("102500.00")
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("102500.00")
            result.points[1].differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "No anchor: ambiguous initial candidate assignments fail closed" {
            val t0 = now
            val s1 = snapshot(
                timestamp = t0.plusMillis(500),
                totalValueUSD = "100000.00",
                assets = mapOf(
                    "BTC" to assetRow("1.1", "50000.00", "55000.00"),
                    "USD" to assetRow("45000.00", "1.0", "45000.00"),
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
            val trade1 = manualTrade(
                timestamp = t0.plusMillis(100),
                side = "buy",
                symbol = "BTC",
                volume = "0.1",
                usdAmount = "5000.00",
            )
            val trade2 = manualTrade(
                timestamp = t0.plusMillis(200),
                side = "buy",
                symbol = "BTC",
                volume = "0.1",
                usdAmount = "5000.00",
            )

            val result = calculate(
                snapshots = listOf(s1, s2),
                trades = listOf(trade1, trade2),
                anchorSnapshot = null,
            )

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE
        }

        "No anchor: neither initial assignment explains balances fails closed" {
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
                    "BTC" to assetRow("1.3", "50000.00", "65000.00"),
                    "USD" to assetRow("35000.00", "1.0", "35000.00"),
                ),
                balancesObservedAt = t0.plusSeconds(3600),
            )
            val trade = manualTrade(
                timestamp = t0.plusMillis(200),
                side = "buy",
                symbol = "BTC",
                volume = "0.1",
                usdAmount = "5000.00",
            )

            val result = calculate(
                snapshots = listOf(s1, s2),
                trades = listOf(trade),
                anchorSnapshot = null,
            )

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE
        }

        "No anchor: bot trade embedded in initial baseline creates no artificial divergence" {
            val t0 = now
            val s1 = snapshot(
                timestamp = t0.plusMillis(500),
                totalValueUSD = "100000.00",
                assets = mapOf(
                    "BTC" to assetRow("1.1", "50000.00", "55000.00"),
                    "USD" to assetRow("45000.00", "1.0", "45000.00"),
                ),
                balancesObservedAt = t0,
            )
            val s2 = snapshot(
                timestamp = t0.plusSeconds(3600),
                totalValueUSD = "100000.00",
                assets = mapOf(
                    "BTC" to assetRow("1.1", "50000.00", "55000.00"),
                    "USD" to assetRow("45000.00", "1.0", "45000.00"),
                ),
                balancesObservedAt = t0.plusSeconds(3600),
            )
            val botTrade = trade(
                timestamp = t0.plusMillis(200),
                side = "buy",
                symbol = "BTC",
                volume = "0.1",
                usdAmount = "5000.00",
                orderTxid = "BOT-ORDER-INIT",
            )

            val result = calculate(
                snapshots = listOf(s1, s2),
                trades = listOf(botTrade),
                knownRebalancerOrderTxids = setOf("BOT-ORDER-INIT"),
                anchorSnapshot = null,
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points.size shouldBe 2
            result.points[0].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100000.00")
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100000.00")
            result.points[1].differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "No anchor: manual spend and receive pair embedded in initial baseline reconciles" {
            val t0 = now
            val s1 = snapshot(
                timestamp = t0.plusMillis(500),
                totalValueUSD = "100000.00",
                assets = mapOf(
                    "BTC" to assetRow("1.1", "50000.00", "55000.00"),
                    "USD" to assetRow("45000.00", "1.0", "45000.00"),
                ),
                balancesObservedAt = t0,
            )
            val s2 = snapshot(
                timestamp = t0.plusSeconds(3600),
                totalValueUSD = "100000.00",
                assets = mapOf(
                    "BTC" to assetRow("1.1", "50000.00", "55000.00"),
                    "USD" to assetRow("45000.00", "1.0", "45000.00"),
                ),
                balancesObservedAt = t0.plusSeconds(3600),
            )
            val spendLedger = ledgerEvent(
                timestamp = t0.plusMillis(200),
                asset = "USD",
                amount = "-5000.00",
                type = KrakenApiConstants.LEDGER_TYPE_SPEND,
            )
            val receiveLedger = ledgerEvent(
                timestamp = t0.plusMillis(200),
                asset = "BTC",
                amount = "0.1",
                type = KrakenApiConstants.LEDGER_TYPE_RECEIVE,
            )

            val result = calculate(
                snapshots = listOf(s1, s2),
                trades = emptyList(),
                rewards = listOf(spendLedger, receiveLedger),
                anchorSnapshot = null,
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points.size shouldBe 2
            result.points[0].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100000.00")
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100000.00")
            result.points[1].differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "No anchor: UNKNOWN trade among initial candidates fails closed" {
            val t0 = now
            val s1 = snapshot(
                timestamp = t0.plusMillis(500),
                totalValueUSD = "100000.00",
                assets = mapOf(
                    "BTC" to assetRow("1.1", "50000.00", "55000.00"),
                    "USD" to assetRow("45000.00", "1.0", "45000.00"),
                ),
                balancesObservedAt = t0,
            )
            val s2 = snapshot(
                timestamp = t0.plusSeconds(3600),
                totalValueUSD = "100000.00",
                assets = mapOf(
                    "BTC" to assetRow("1.1", "50000.00", "55000.00"),
                    "USD" to assetRow("45000.00", "1.0", "45000.00"),
                ),
                balancesObservedAt = t0.plusSeconds(3600),
            )
            val unknownTrade = trade(
                timestamp = t0.plusMillis(200),
                side = "buy",
                symbol = "BTC",
                volume = "0.1",
                usdAmount = "5000.00",
                source = TradeSource.LEGACY_UNKNOWN,
                cycleId = null,
            )

            val result = calculate(
                snapshots = listOf(s1, s2),
                trades = listOf(unknownTrade),
                anchorSnapshot = null,
            )

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.AMBIGUOUS_TRADE_OWNERSHIP
        }

        "No anchor: unsupported initial trade fails closed with UNSUPPORTED_TRADE" {
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
                    "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                    "USD" to assetRow("50000.00", "1.0", "50000.00"),
                ),
                balancesObservedAt = t0.plusSeconds(3600),
            )
            val badTrade = manualTrade(
                timestamp = t0.plusMillis(200),
                side = "buy",
                symbol = "BTC",
                volume = "0.1",
                usdAmount = "5000.00",
            ).copy(pair = "BTCEUR")

            val result = calculate(
                snapshots = listOf(s1, s2),
                trades = listOf(badTrade),
                anchorSnapshot = null,
            )

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.UNSUPPORTED_TRADE
        }

        "No anchor: exceeding maximum initial candidate cap fails closed" {
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
                    "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                    "USD" to assetRow("50000.00", "1.0", "50000.00"),
                ),
                balancesObservedAt = t0.plusSeconds(3600),
            )
            val excessiveTrades = (1..13).map { i ->
                manualTrade(
                    timestamp = t0.plusMillis(10L * i),
                    side = "buy",
                    symbol = "BTC",
                    volume = "0.01",
                    usdAmount = "500.00",
                )
            }

            val result = calculate(
                snapshots = listOf(s1, s2),
                trades = excessiveTrades,
                anchorSnapshot = null,
            )

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE
        }

        "No anchor: initial candidate embedded alongside regular interval trade and ledger reconciles" {
            val t0 = now
            val s1 = snapshot(
                timestamp = t0.plusMillis(500),
                totalValueUSD = "100000.00",
                assets = mapOf(
                    "BTC" to assetRow("1.1", "50000.00", "55000.00"),
                    "USD" to assetRow("45000.00", "1.0", "45000.00"),
                ),
                balancesObservedAt = t0,
            )
            val s2 = snapshot(
                timestamp = t0.plusSeconds(3600),
                totalValueUSD = "102500.00",
                assets = mapOf(
                    "BTC" to assetRow("1.35", "50000.00", "67500.00"),
                    "USD" to assetRow("35000.00", "1.0", "35000.00"),
                ),
                balancesObservedAt = t0.plusSeconds(3600),
            )
            val initTrade = manualTrade(
                timestamp = t0.plusMillis(200),
                side = "buy",
                symbol = "BTC",
                volume = "0.1",
                usdAmount = "5000.00",
            )
            val regularTrade = manualTrade(
                timestamp = t0.plusSeconds(1800),
                side = "buy",
                symbol = "BTC",
                volume = "0.2",
                usdAmount = "10000.00",
            )
            val regularLedger = ledgerEvent(
                timestamp = t0.plusSeconds(1900),
                asset = "BTC",
                amount = "0.05",
                type = KrakenApiConstants.LEDGER_TYPE_STAKING,
            )

            val result = calculate(
                snapshots = listOf(s1, s2),
                trades = listOf(initTrade, regularTrade),
                rewards = listOf(regularLedger),
                anchorSnapshot = null,
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points.size shouldBe 2
            result.points[1].differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "No anchor: initial candidate embedded alongside late candidate at next snapshot reconciles" {
            val t0 = now
            val t1 = t0.plusSeconds(3600)
            val s1 = snapshot(
                timestamp = t0.plusMillis(500),
                totalValueUSD = "100000.00",
                assets = mapOf(
                    "BTC" to assetRow("1.1", "50000.00", "55000.00"),
                    "ETH" to assetRow("0.0", "3000.00", "0.00"),
                    "USD" to assetRow("45000.00", "1.0", "45000.00"),
                ),
                balancesObservedAt = t0,
            )
            val s2 = snapshot(
                timestamp = t1.plusMillis(500),
                totalValueUSD = "100000.00",
                assets = mapOf(
                    "BTC" to assetRow("1.1", "50000.00", "55000.00"),
                    "ETH" to assetRow("2.0", "3000.00", "6000.00"),
                    "USD" to assetRow("39000.00", "1.0", "39000.00"),
                ),
                balancesObservedAt = t1,
            )
            val initTrade = manualTrade(
                timestamp = t0.plusMillis(200),
                side = "buy",
                symbol = "BTC",
                volume = "0.1",
                usdAmount = "5000.00",
            )
            val lateTradeAtS2 = manualTrade(
                timestamp = t1.plusMillis(200),
                side = "buy",
                symbol = "ETH",
                volume = "2.0",
                usdAmount = "6000.00",
            )

            val result = calculate(
                snapshots = listOf(s1, s2),
                trades = listOf(initTrade, lateTradeAtS2),
                anchorSnapshot = null,
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points.size shouldBe 2
            result.points[1].differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "calculate preserves cumulative difference from inception on sub-window queries" {
            val t0 = now.minusSeconds(86400 * 30)
            val t1 = now
            val t2 = now.plusSeconds(3600)

            // Inception: 1.0 BTC @ 50,000 + 50,000 USD = 100,000 USD
            val inceptionSnap = snapshot(
                timestamp = t0,
                totalValueUSD = "100000.00",
                assets = mapOf(
                    "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                    "USD" to assetRow("50000.00", "1.00", "50000.00"),
                ),
            )

            // At t1: Bot sold 0.5 BTC at 60k earlier, so bot has 0.5 BTC + 80,000 USD.
            // BTC price now 40,000. Bot total = 0.5 * 40k + 80k = 100,000 USD.
            // Buy & Hold (if held 1.0 BTC + 50k USD) = 1.0 * 40k + 50k = 90,000 USD.
            val s1 = snapshot(
                timestamp = t1,
                totalValueUSD = "100000.00",
                assets = mapOf(
                    "BTC" to assetRow("0.5", "40000.00", "20000.00"),
                    "USD" to assetRow("80000.00", "1.00", "80000.00"),
                ),
            )
            // At t2: BTC price drops to 30,000.
            // Bot total = 0.5 * 30k + 80k = 95,000 USD.
            // Buy & Hold = 1.0 * 30k + 50k = 80,000 USD.
            val s2 = snapshot(
                timestamp = t2,
                totalValueUSD = "95000.00",
                assets = mapOf(
                    "BTC" to assetRow("0.5", "30000.00", "15000.00"),
                    "USD" to assetRow("80000.00", "1.00", "80000.00"),
                ),
            )

            val result = calculate(
                snapshots = listOf(s1, s2),
                trades = emptyList(),
                anchorSnapshot = null,
                inceptionSnapshot = inceptionSnap,
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.baselineTimestamp shouldBe t0
            result.points.size shouldBe 2
            // Point 0 (at t1): Bot = 100k, B&H = 90k, diff = +10k
            result.points[0].rebalancerValueUSD.shouldBeEqualComparingTo(BigDecimal("100000.00"))
            result.points[0].buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("90000.00"))
            result.points[0].differenceUSD.shouldBeEqualComparingTo(BigDecimal("10000.00"))
            // Point 1 (at t2): Bot = 95k, B&H = 80k, diff = +15k
            result.points[1].rebalancerValueUSD.shouldBeEqualComparingTo(BigDecimal("95000.00"))
            result.points[1].buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("80000.00"))
            result.points[1].differenceUSD.shouldBeEqualComparingTo(BigDecimal("15000.00"))
        }

        "calculate applies intermediate external ledger and manual trade events before sub-window observation start" {
            val t0 = Instant.parse("2026-06-01T12:00:00Z")
            val tMidLedger = Instant.parse("2026-06-05T12:00:00Z")
            val tMidTrade = Instant.parse("2026-06-06T12:00:00Z")
            val t1 = Instant.parse("2026-06-10T12:00:00Z")
            val t2 = Instant.parse("2026-06-10T13:00:00Z")

            val inceptionSnap = snapshot(
                timestamp = t0,
                totalValueUSD = "100000.00",
                assets = mapOf(
                    "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                    "USD" to assetRow("50000.00", "1.00", "50000.00"),
                ),
            )

            val s1 = snapshot(
                timestamp = t1,
                totalValueUSD = "120000.00",
                assets = mapOf(
                    "BTC" to assetRow("1.1", "50000.00", "55000.00"),
                    "USD" to assetRow("65000.00", "1.00", "65000.00"),
                ),
            )
            val s2 = snapshot(
                timestamp = t2,
                totalValueUSD = "120000.00",
                assets = mapOf(
                    "BTC" to assetRow("1.1", "50000.00", "55000.00"),
                    "USD" to assetRow("65000.00", "1.00", "65000.00"),
                ),
            )

            // Intermediate deposit: +19,000 USD
            val dep = ledgerEvent(
                timestamp = tMidLedger,
                asset = "USD",
                amount = "19000.00",
                type = "deposit",
            )
            // Filtered ledgers: wrong type, pre-inception, and post-observation
            val nonBalanceLedger =
                ledgerEvent(timestamp = tMidLedger, asset = "USD", amount = "1.00", type = "rollover")
            // Trade execution rows defer to TradesHistory and never replay.
            val tradeRowLedger =
                ledgerEvent(timestamp = tMidLedger, asset = "BTC", amount = "0.01", type = "trade")
            val preInceptionLedger =
                ledgerEvent(timestamp = t0.minusSeconds(10), asset = "USD", amount = "5.00", type = "deposit")
            val postObservationLedger =
                ledgerEvent(timestamp = t2.plusSeconds(3600), asset = "USD", amount = "5.00", type = "deposit")

            // Intermediate manual trade: buy 0.1 BTC with 4,000 USD
            val manualBuy = trade(
                timestamp = tMidTrade,
                side = "buy",
                symbol = "BTC",
                volume = "0.1",
                usdAmount = "4000.00",
                source = TradeSource.API_FILL,
                cycleId = null,
                tradeId = "MANUAL-1",
            )
            // Filtered trades: unsuccessful, dryRun, pre-inception, post-observation, and bot rebalancer trade
            val failedTrade = manualBuy.copy(id = 901, tradeId = "T-FAIL", success = false)
            val dryRunTrade = manualBuy.copy(id = 902, tradeId = "T-DRY", dryRun = true)
            val preInceptionTrade = manualBuy.copy(id = 903, tradeId = "T-PRE", timestamp = t0.minusSeconds(10))
            val postObservationTrade = manualBuy.copy(id = 904, tradeId = "T-POST", timestamp = t2.plusSeconds(3600))
            val botTrade = manualBuy.copy(id = 905, tradeId = "T-BOT", orderTxid = "BOT-ORDER-1")

            val result = calculate(
                snapshots = listOf(s1, s2),
                trades = listOf(manualBuy, failedTrade, dryRunTrade, preInceptionTrade, postObservationTrade, botTrade),
                rewards = listOf(dep, nonBalanceLedger, tradeRowLedger, preInceptionLedger, postObservationLedger),
                knownRebalancerOrderTxids = setOf("BOT-ORDER-1"),
                anchorSnapshot = null,
                inceptionSnapshot = inceptionSnap,
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal("50000.00"))),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points.size shouldBe 2
        }

        "calculate with known inception time but pruned snapshot returns INCEPTION_SNAPSHOT_PRUNED" {
            val t0 = Instant.parse("2026-06-01T12:00:00Z")
            val t1 = Instant.parse("2026-06-10T12:00:00Z")
            val t2 = Instant.parse("2026-06-10T13:00:00Z")

            val s1 = snapshot(
                timestamp = t1,
                totalValueUSD = "100000.00",
                assets = mapOf(
                    "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                    "USD" to assetRow("50000.00", "1.00", "50000.00"),
                ),
            )
            val s2 = snapshot(
                timestamp = t2,
                totalValueUSD = "100000.00",
                assets = mapOf(
                    "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                    "USD" to assetRow("50000.00", "1.00", "50000.00"),
                ),
            )

            val result = calculate(
                snapshots = listOf(s1, s2),
                trades = emptyList(),
                rewards = emptyList(),
                inceptionSnapshot = null,
                knownInceptionTime = t0,
            )

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.INCEPTION_SNAPSHOT_PRUNED
            result.baselineTimestamp shouldBe t0
        }

        "calculate with unknown ledger type returns UNSUPPORTED_LEDGER_TYPE" {
            val t0 = Instant.parse("2026-06-01T12:00:00Z")
            val tMid = Instant.parse("2026-06-05T12:00:00Z")
            val t1 = Instant.parse("2026-06-10T12:00:00Z")
            val t2 = Instant.parse("2026-06-10T13:00:00Z")

            val inceptionSnap = snapshot(
                timestamp = t0,
                totalValueUSD = "100000.00",
                assets = mapOf(
                    "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                    "USD" to assetRow("50000.00", "1.00", "50000.00"),
                ),
            )
            val s1 = snapshot(
                timestamp = t1,
                totalValueUSD = "100000.00",
                assets = mapOf(
                    "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                    "USD" to assetRow("50000.00", "1.00", "50000.00"),
                ),
            )
            val s2 = snapshot(
                timestamp = t2,
                totalValueUSD = "100000.00",
                assets = mapOf(
                    "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                    "USD" to assetRow("50000.00", "1.00", "50000.00"),
                ),
            )
            val mystery = ledgerEvent(
                timestamp = tMid,
                asset = "USD",
                amount = "5.00",
                type = "mystery",
            )

            val result = calculate(
                snapshots = listOf(s1, s2),
                trades = emptyList(),
                rewards = listOf(mystery),
                inceptionSnapshot = inceptionSnap,
            )

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.UNSUPPORTED_LEDGER_TYPE
        }

        "calculate skips internal transfers and trade-type ledgers in the benchmark" {
            val t0 = Instant.parse("2026-06-01T12:00:00Z")
            val tMid = Instant.parse("2026-06-05T12:00:00Z")
            val t1 = Instant.parse("2026-06-10T12:00:00Z")
            val t2 = Instant.parse("2026-06-10T13:00:00Z")

            val inceptionSnap = snapshot(
                timestamp = t0,
                totalValueUSD = "100000.00",
                assets = mapOf(
                    "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                    "USD" to assetRow("50000.00", "1.00", "50000.00"),
                ),
            )
            val s1 = snapshot(
                timestamp = t1,
                totalValueUSD = "100000.00",
                assets = mapOf(
                    "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                    "USD" to assetRow("50000.00", "1.00", "50000.00"),
                ),
            )
            val s2 = snapshot(
                timestamp = t2,
                totalValueUSD = "100000.00",
                assets = mapOf(
                    "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                    "USD" to assetRow("50000.00", "1.00", "50000.00"),
                ),
            )
            val transfer = ledgerEvent(
                timestamp = tMid,
                asset = "USD",
                amount = "500.00",
                type = "transfer",
            )
            val tradeRow = ledgerEvent(
                timestamp = tMid.plusSeconds(60),
                asset = "BTC",
                amount = "0.01000000",
                type = "trade",
            )

            val result = calculate(
                snapshots = listOf(s1, s2),
                trades = emptyList(),
                rewards = listOf(transfer, tradeRow),
                inceptionSnapshot = inceptionSnap,
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points.size shouldBe 2
            result.points[0].buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("100000.00"))
        }

        "calculate with inception snapshot whose asset universe differs returns ASSET_UNIVERSE_CHANGED" {
            val t0 = now.minusSeconds(86400 * 30)
            val t1 = now
            val t2 = now.plusSeconds(3600)

            // Inception had BTC and USD
            val inceptionSnap = snapshot(
                timestamp = t0,
                totalValueUSD = "100000.00",
                assets = mapOf(
                    "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                    "USD" to assetRow("50000.00", "1.00", "50000.00"),
                ),
            )

            // Current window snapshots have BTC, ETH, and USD
            val s1 = snapshot(
                timestamp = t1,
                totalValueUSD = "100000.00",
                assets = mapOf(
                    "BTC" to assetRow("0.5", "40000.00", "20000.00"),
                    "ETH" to assetRow("10.0", "3000.00", "30000.00"),
                    "USD" to assetRow("50000.00", "1.00", "50000.00"),
                ),
            )
            val s2 = snapshot(
                timestamp = t2,
                totalValueUSD = "95000.00",
                assets = mapOf(
                    "BTC" to assetRow("0.5", "30000.00", "15000.00"),
                    "ETH" to assetRow("10.0", "3000.00", "30000.00"),
                    "USD" to assetRow("50000.00", "1.00", "50000.00"),
                ),
            )

            val result = calculate(
                snapshots = listOf(s1, s2),
                trades = emptyList(),
                anchorSnapshot = null,
                inceptionSnapshot = inceptionSnap,
            )

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.ASSET_UNIVERSE_CHANGED
            result.unavailableAt shouldBe t1
            result.baselineTimestamp shouldBe t0
        }

        "calculate with request window starting before inception trims output to inception" {
            val tInception = now
            val tPre = now.minusSeconds(3600)
            val tPost1 = now.plusSeconds(3600)
            val tPost2 = now.plusSeconds(7200)

            val inceptionSnap = snapshot(
                timestamp = tInception,
                totalValueUSD = "100000.00",
                assets = mapOf(
                    "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                    "USD" to assetRow("50000.00", "1.00", "50000.00"),
                ),
            )
            val preSnap = snapshot(
                timestamp = tPre,
                totalValueUSD = "90000.00",
                assets = mapOf(
                    "BTC" to assetRow("1.0", "40000.00", "40000.00"),
                    "USD" to assetRow("50000.00", "1.00", "50000.00"),
                ),
            )
            val post1 = snapshot(
                timestamp = tPost1,
                totalValueUSD = "110000.00",
                assets = mapOf(
                    "BTC" to assetRow("1.0", "60000.00", "60000.00"),
                    "USD" to assetRow("50000.00", "1.00", "50000.00"),
                ),
            )
            val post2 = snapshot(
                timestamp = tPost2,
                totalValueUSD = "120000.00",
                assets = mapOf(
                    "BTC" to assetRow("1.0", "70000.00", "70000.00"),
                    "USD" to assetRow("50000.00", "1.00", "50000.00"),
                ),
            )

            // Request window includes preSnap (before inception)
            val result = calculate(
                snapshots = listOf(preSnap, post1, post2),
                trades = emptyList(),
                anchorSnapshot = null,
                inceptionSnapshot = inceptionSnap,
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.baselineTimestamp shouldBe tInception
            // Trimmed output should start at inception!
            result.points.size shouldBe 3
            result.points[0].timestamp shouldBe tInception
            result.points[1].timestamp shouldBe tPost1
            result.points[2].timestamp shouldBe tPost2
        }

        "calculate with request window entirely before inception returns INSUFFICIENT_SNAPSHOTS" {
            val tInception = now
            val tPre1 = now.minusSeconds(7200)
            val tPre2 = now.minusSeconds(3600)

            val inceptionSnap = snapshot(
                timestamp = tInception,
                totalValueUSD = "100000.00",
                assets = mapOf(
                    "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                    "USD" to assetRow("50000.00", "1.00", "50000.00"),
                ),
            )
            val pre1 = snapshot(
                timestamp = tPre1,
                totalValueUSD = "90000.00",
                assets = mapOf(
                    "BTC" to assetRow("1.0", "40000.00", "40000.00"),
                    "USD" to assetRow("50000.00", "1.00", "50000.00"),
                ),
            )
            val pre2 = snapshot(
                timestamp = tPre2,
                totalValueUSD = "95000.00",
                assets = mapOf(
                    "BTC" to assetRow("1.0", "45000.00", "45000.00"),
                    "USD" to assetRow("50000.00", "1.00", "50000.00"),
                ),
            )

            val result = calculate(
                snapshots = listOf(pre1, pre2),
                trades = emptyList(),
                anchorSnapshot = null,
                inceptionSnapshot = inceptionSnap,
            )

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.INSUFFICIENT_SNAPSHOTS
            result.unavailableAt shouldBe tPre2
            result.baselineTimestamp shouldBe tInception
        }

        "calculate with request window containing exact inception timestamp does not prepend duplicate inception" {
            val tInception = now
            val tPre = now.minusSeconds(3600)
            val tPost = now.plusSeconds(3600)

            val inceptionSnap = snapshot(
                timestamp = tInception,
                totalValueUSD = "100000.00",
                assets = mapOf(
                    "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                    "USD" to assetRow("50000.00", "1.00", "50000.00"),
                ),
            )
            val preSnap = snapshot(
                timestamp = tPre,
                totalValueUSD = "90000.00",
                assets = mapOf(
                    "BTC" to assetRow("1.0", "40000.00", "40000.00"),
                    "USD" to assetRow("50000.00", "1.00", "50000.00"),
                ),
            )
            val exactSnap = snapshot(
                timestamp = tInception,
                totalValueUSD = "100000.00",
                assets = mapOf(
                    "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                    "USD" to assetRow("50000.00", "1.00", "50000.00"),
                ),
            )
            val postSnap = snapshot(
                timestamp = tPost,
                totalValueUSD = "110000.00",
                assets = mapOf(
                    "BTC" to assetRow("1.0", "60000.00", "60000.00"),
                    "USD" to assetRow("50000.00", "1.00", "50000.00"),
                ),
            )

            val result = calculate(
                snapshots = listOf(preSnap, exactSnap, postSnap),
                trades = emptyList(),
                anchorSnapshot = null,
                inceptionSnapshot = inceptionSnap,
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.baselineTimestamp shouldBe tInception
            result.points.size shouldBe 2
            result.points[0].timestamp shouldBe tInception
            result.points[1].timestamp shouldBe tPost
        }

        // Regression: before the trim-then-check reorder, a pre-inception snapshot with a
        // different asset set would cause a premature ASSET_UNIVERSE_CHANGED.  After the fix,
        // it must be silently trimmed away and the comparison must be AVAILABLE.
        "pre-inception snapshot with different assets is trimmed before universe check, result is AVAILABLE" {
            val tInception = now
            val tPre = now.minusSeconds(3600)
            val tPost = now.plusSeconds(3600)

            // Pre-inception snapshot had only BTC+USD (no ETH)
            val preSnap = snapshot(
                timestamp = tPre,
                totalValueUSD = "50000.00",
                assets = mapOf(
                    "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                    "USD" to assetRow("0.00", "1.00", "0.00"),
                ),
            )
            // Inception snapshot introduces ETH; asset universe changes vs preSnap
            val inceptionSnap = snapshot(
                timestamp = tInception,
                totalValueUSD = "100000.00",
                assets = mapOf(
                    "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                    "ETH" to assetRow("10.0", "2500.00", "25000.00"),
                    "USD" to assetRow("25000.00", "1.00", "25000.00"),
                ),
            )
            // Post-inception snapshot has the same universe as inception.
            // Balances are identical — only prices change so reconciliation
            // passes with no trades in between.
            val postSnap = snapshot(
                timestamp = tPost,
                totalValueUSD = "108000.00",
                assets = mapOf(
                    "BTC" to assetRow("1.0", "58000.00", "58000.00"),
                    "ETH" to assetRow("10.0", "2500.00", "25000.00"),
                    "USD" to assetRow("25000.00", "1.00", "25000.00"),
                ),
            )

            val result = calculate(
                snapshots = listOf(preSnap, postSnap),
                trades = emptyList(),
                anchorSnapshot = null,
                inceptionSnapshot = inceptionSnap,
            )

            // Pre-inception preSnap is trimmed; universe check must not fire on it
            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.baselineTimestamp shouldBe tInception
            result.points.size shouldBe 2
            result.points[0].timestamp shouldBe tInception
            result.points[1].timestamp shouldBe tPost
        }
    }

    private fun mixedCostSnapshots(knownObservation: Boolean): List<PortfolioSnapshot> =
        listOf("100.006", "97.006", "95.004", "93.004").mapIndexed { index, usd ->
            val timestamp = now.plusSeconds(index * 20L)
            val btc = (10 + index).toString()
            snapshot(
                timestamp = timestamp,
                totalValueUSD = BigDecimal(usd).add(BigDecimal(btc)).toPlainString(),
                assets = mapOf(
                    "BTC" to assetRow(btc, "1", btc),
                    "USD" to assetRow(usd, "1", usd),
                ),
                balancesObservedAt = if (knownObservation) timestamp else null,
            )
        }

    private fun mixedCostTrades(): List<TradeRecord> =
        listOf("3.004" to "3.00", "2.002" to "2.00", "1.996" to "2.00").mapIndexed { index, (price, cost) ->
            trade(
                timestamp = now.plusSeconds(10 + index * 20L),
                side = "BUY",
                symbol = "BTC",
                volume = "1",
                usdAmount = cost,
                source = TradeSource.API_FILL,
                cycleId = null,
                tradeId = "MIXED-COST-FILL-$index",
                price = price,
            )
        }

    /** Deterministic contribution-time prices for owner-flow tests (never a live ticker). */
    private fun mapPriceProvider(prices: Map<String, BigDecimal>): HistoricalPriceProvider =
        HistoricalPriceProvider { symbol, _ ->
            if (symbol == "USD") BigDecimal.ONE else prices[symbol]
        }

    private fun assetRow(balance: String, price: String, valueUSD: String): Triple<String, String, String> =
        Triple(balance, price, valueUSD)

    private fun snapshot(
        timestamp: Instant,
        totalValueUSD: String,
        assets: Map<String, Triple<String, String, String>>,
        balancesObservedAt: Instant? = timestamp,
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
        balance: String? = null,
        ledgerId: String? = null,
        refid: String? = null,
    ): LedgerEvent {
        val resolvedRefid = refid ?: when (type) {
            KrakenApiConstants.LEDGER_TYPE_DEPOSIT -> {
                val norm = Asset.normalizeLedgerAsset(asset).uppercase()
                if (norm == Asset.USD) "FT-${ledgerId ?: "dep-$timestamp"}" else "tx-${ledgerId ?: "dep-$timestamp"}"
            }

            KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL -> "WIRE-${ledgerId ?: "wdr-$timestamp"}"

            else -> null
        }
        return LedgerEvent(
            ledgerId = ledgerId ?: "ledger-$timestamp-$asset-$type",
            refid = resolvedRefid,
            time = timestamp,
            type = type,
            asset = asset,
            amount = BigDecimal(amount),
            fee = BigDecimal(fee),
            balance = balance?.let(::BigDecimal) ?: BigDecimal.ZERO,
            hasAuthoritativeBalance = balance != null,
        )
    }
}
