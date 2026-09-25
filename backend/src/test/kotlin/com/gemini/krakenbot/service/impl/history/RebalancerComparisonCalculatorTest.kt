package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.TestFixtures.assetSnapshot
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.ComparisonAvailability
import com.gemini.krakenbot.model.ComparisonConfidence
import com.gemini.krakenbot.model.ComparisonUnavailableReason
import com.gemini.krakenbot.model.DepositStatusRecord
import com.gemini.krakenbot.model.FundingEvidence
import com.gemini.krakenbot.model.FundingProvenanceFailure
import com.gemini.krakenbot.model.FundingProvenanceFailureReason
import com.gemini.krakenbot.model.FundingProvenanceResolver
import com.gemini.krakenbot.model.KrakenApiConstants
import com.gemini.krakenbot.model.KrakenAssetMetadata
import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.RebalancerComparison
import com.gemini.krakenbot.model.SimpleFundingProvenanceResolver
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.model.TradeSource
import com.gemini.krakenbot.model.WithdrawStatusRecord
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant

class RebalancerComparisonCalculatorTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    private val now = Instant.parse("2026-07-01T12:00:00Z")

    private val testAssetMetadata = listOf(
        "ADA",
        "ATOM",
        "AVAX",
        "BABY",
        "BTC",
        "DOGE",
        "ETH",
        "MORPHO",
        "DOT",
        "PAXG",
        "SOL",
        "SOL03",
        "STRC",
        "UNKNOWN",
        "USD",
        "USDC",
        "USDG",
        "USDT",
        "VET",
        "XLM",
        "XMR",
        "XRP",
        "XXBT",
        "XXLM",
        "ZEC",
        "ZUSD",
    ).map { KrakenAssetMetadata(assetId = it, assetClass = "currency") }

    private fun tokenizedStrcAssetMetadata(): List<KrakenAssetMetadata> = testAssetMetadata.map { metadata ->
        if (Asset.normalizeLedgerAsset(metadata.assetId).equals("STRC", ignoreCase = true)) {
            metadata.copy(assetClass = "tokenized_asset")
        } else {
            metadata
        }
    }

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
        anchorSnapshot: PortfolioSnapshot? = null,
        inceptionSnapshot: PortfolioSnapshot? = null,
        knownInceptionTime: Instant? = null,
        historyTruncated: Boolean = false,
        priceProvider: HistoricalPriceProvider? = null,
        provenanceResolver: FundingProvenanceResolver = testProvenanceResolver,
        inceptionUnavailableReason: ComparisonUnavailableReason? = null,
        ledgerContext: List<LedgerEvent> = emptyList(),
        configuredAssetUniverse: Set<String>? = null,
        assetMetadata: List<KrakenAssetMetadata> = testAssetMetadata,
    ): RebalancerComparison = RebalancerComparisonCalculator.calculate(
        snapshots = snapshots,
        trades = trades,
        assetMetadata = assetMetadata,
        rewards = rewards,
        anchorSnapshot = anchorSnapshot,
        inceptionSnapshot = inceptionSnapshot,
        knownInceptionTime = knownInceptionTime,
        historyTruncated = historyTruncated,
        priceProvider = priceProvider,
        provenanceResolver = provenanceResolver,
        inceptionUnavailableReason = inceptionUnavailableReason,
        ledgerContext = ledgerContext,
        configuredAssetUniverse = configuredAssetUniverse,
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
                    result.points.first().differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
                    result.points.drop(1).forEach {
                        it.buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("103.00")
                        it.differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
                    }
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
                listOf(manualTrade(now.plusSeconds(13), "buy", "BTC", "1", "1")),
                emptyList(),
            )
            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE
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
                    result.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("164.00")
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
            check(result.availability == ComparisonAvailability.AVAILABLE) {
                "${result.unavailableReason} at ${result.unavailableAt}"
            }
            result.points.size shouldBe 2
            result.points.forEach { it.differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO }
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
                        ledgerEvent(now.plusMillis(9800 + index * 10L), "BTC", (1 shl index).toString())
                    },
                )
                if (count <= 12) {
                    result.availability shouldBe ComparisonAvailability.AVAILABLE
                    result.points.size shouldBe 2
                    result.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo
                        BigDecimal(1 + totalReward)
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

        "asset added without explaining events: returns UNEXPLAINED_BALANCE_CHANGE" {
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
            result.unavailableReason shouldBe ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE
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

            val result = calculate(
                snapshots,
                emptyList(),
                configuredAssetUniverse = setOf("BTC", "ETH"),
            )

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.ASSET_UNIVERSE_CHANGED
        }

        "post-baseline purchase of an unheld asset affects actual value only" {
            val t0 = now
            val t1 = now.plusSeconds(1800)
            val t2 = now.plusSeconds(3600)
            val snapshots = listOf(
                snapshot(
                    t0,
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "40000.00", "40000.00"),
                        "USD" to assetRow("60000.00", "1.00", "60000.00"),
                    ),
                ),
                snapshot(
                    t2,
                    "103002.10",
                    mapOf(
                        "BTC" to assetRow("1.0", "40000.00", "40000.00"),
                        "USD" to assetRow("56997.90", "1.00", "56997.90"),
                        "STRC" to assetRow("30.0", "200.14", "6004.20"),
                    ),
                ),
            )
            val strcBuy = trade(
                t1,
                side = "buy",
                symbol = "STRCZUSD",
                volume = "30.0",
                usdAmount = "3002.10",
                price = "100.07",
            ).copy(pair = "STRCZUSD")

            val result = calculate(snapshots, listOf(strcBuy))

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            val last = result.points.last()
            last.rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("103002.10")
            last.buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100000.00")
            last.differenceUSD shouldBeEqualComparingTo BigDecimal("3002.10")
        }

        "purchase and liquidation of an unheld asset remain actual-only" {
            val t0 = now
            val t2 = now.plusSeconds(3600)
            val t3 = now.plusSeconds(5400)
            val t4 = now.plusSeconds(7200)
            val buySnapshot = snapshot(
                t2,
                "103002.10",
                mapOf(
                    "BTC" to assetRow("1.0", "40000.00", "40000.00"),
                    "USD" to assetRow("56997.90", "1.00", "56997.90"),
                    "STRC" to assetRow("30.0", "200.14", "6004.20"),
                ),
            )
            val snapshots = listOf(
                snapshot(
                    t0,
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "40000.00", "40000.00"),
                        "USD" to assetRow("60000.00", "1.00", "60000.00"),
                    ),
                ),
                buySnapshot,
                snapshot(
                    t4,
                    "103002.10",
                    mapOf(
                        "BTC" to assetRow("1.0", "40000.00", "40000.00"),
                        "USD" to assetRow("63002.10", "1.00", "63002.10"),
                    ),
                ),
            )
            val strcBuy = trade(
                now.plusSeconds(1800),
                side = "buy",
                symbol = "STRCZUSD",
                volume = "30.0",
                usdAmount = "3002.10",
                price = "100.07",
            ).copy(pair = "STRCZUSD")
            val strcSell = trade(
                t3,
                side = "sell",
                symbol = "STRCZUSD",
                volume = "30.0",
                usdAmount = "6004.20",
                price = "200.14",
            ).copy(pair = "STRCZUSD")

            val result = calculate(snapshots, listOf(strcBuy, strcSell))

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            val last = result.points.last()
            last.rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("103002.10")
            last.buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100000.00")
            last.differenceUSD shouldBeEqualComparingTo BigDecimal("3002.10")
        }

        "liquidation of an unheld asset remains outside the benchmark" {
            val t0 = now
            val t1 = now.plusSeconds(1800)
            val t2 = now.plusSeconds(3600)
            val snapshots = listOf(
                snapshot(
                    t0,
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "40000.00", "40000.00"),
                        "USD" to assetRow("60000.00", "1.00", "60000.00"),
                    ),
                ),
                snapshot(
                    t2,
                    "100299.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "40000.00", "40000.00"),
                        "USD" to assetRow("60299.00", "1.00", "60299.00"),
                    ),
                ),
            )
            // Reconstructed history settles the quote proceeds of a pre-series holding
            // (production MORPHO) without recording the base asset anywhere in the series.
            val morphoSell = trade(
                t1,
                side = "sell",
                symbol = "MORPHO",
                volume = "300.0",
                usdAmount = "300.00",
                price = "1.00",
                fee = "1.00",
            )

            val result = calculate(snapshots, listOf(morphoSell))

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            val last = result.points.last()
            last.rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("100299.00")
            last.buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100000.00")
            last.differenceUSD shouldBeEqualComparingTo BigDecimal("299.00")
        }

        "purchase settled outside the recorded quote universe keeps the comparison available" {
            val t0 = now
            val t1 = now.plusSeconds(1800)
            val t2 = now.plusSeconds(3600)
            val snapshots = listOf(
                snapshot(
                    t0,
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "40000.00", "40000.00"),
                        "USD" to assetRow("60000.00", "1.00", "60000.00"),
                    ),
                ),
                snapshot(
                    t2,
                    "100500.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "40000.00", "40000.00"),
                        "USD" to assetRow("60000.00", "1.00", "60000.00"),
                        "ATOM" to assetRow("10.0", "50.00", "500.00"),
                    ),
                ),
            )
            val atomBuy = trade(
                t1,
                side = "buy",
                symbol = "ATOM",
                volume = "10.0",
                usdAmount = "500.00",
                price = "50.00",
            ).copy(pair = "ATOMUSDT")

            val result = calculate(snapshots, listOf(atomBuy))

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            val last = result.points.last()
            last.rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("100500.00")
            last.buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100000.00")
            last.differenceUSD shouldBeEqualComparingTo BigDecimal("500.00")
        }

        "tracked quote with no recorded balance fails closed" {
            val t0 = now
            val t1 = now.plusSeconds(1800)
            val t2 = now.plusSeconds(3600)
            val snapshots = listOf(
                snapshot(
                    t0,
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "40000.00", "40000.00"),
                        "USD" to assetRow("60000.00", "1.00", "60000.00"),
                    ),
                ),
                snapshot(
                    t2,
                    "100500.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "40000.00", "40000.00"),
                        "USD" to assetRow("60000.00", "1.00", "60000.00"),
                        "ATOM" to assetRow("10.0", "50.00", "500.00"),
                        "USDT" to assetRow("0.0", "1.00", "0.00"),
                    ),
                ),
            )
            val atomBuy = trade(
                t1,
                side = "buy",
                symbol = "ATOM",
                volume = "10.0",
                usdAmount = "500.00",
                price = "50.00",
            ).copy(pair = "ATOMUSDT")

            val result = calculate(snapshots, listOf(atomBuy))

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.UNSUPPORTED_TRADE
            result.unavailableAt shouldBe t1
        }

        "spot staking transfer leaving one quantum of dust still reconciles" {
            val t0 = now
            val t1 = now.plusSeconds(1800)
            val t2 = now.plusSeconds(3600)
            val snapshots = listOf(
                snapshot(
                    t0,
                    "101000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "40000.00", "40000.00"),
                        "USD" to assetRow("60000.00", "1.00", "60000.00"),
                        "SOL" to assetRow("10.00000009", "100.00", "1000.00"),
                    ),
                ),
                snapshot(
                    t2,
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "40000.00", "40000.00"),
                        "USD" to assetRow("60000.00", "1.00", "60000.00"),
                        "SOL" to assetRow("0.00000001", "100.00", "0.00"),
                    ),
                ),
            )
            val spotLeg = ledgerEvent(
                t1,
                "SOL",
                "-10.00000009",
                type = "transfer",
                subtype = "spottostaking",
                refid = "STAKEPAIR",
                ledgerId = "SOL-SPOT-1",
            )
            val stakingLeg = ledgerEvent(
                t1,
                "SOL03",
                "+10.00000009",
                type = "transfer",
                subtype = "spottostaking",
                refid = "STAKEPAIR",
                ledgerId = "SOL-STAKING-1",
            )
            val result = calculate(
                snapshots,
                emptyList(),
                listOf(spotLeg, stakingLeg),
                priceProvider = mapPriceProvider(mapOf("SOL" to BigDecimal("100.00"))),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points.last().rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("101000.00")
        }

        "two-quantum dust difference still fails closed" {
            val t0 = now
            val t1 = now.plusSeconds(1800)
            val t2 = now.plusSeconds(3600)
            val snapshots = listOf(
                snapshot(
                    t0,
                    "101000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "40000.00", "40000.00"),
                        "USD" to assetRow("60000.00", "1.00", "60000.00"),
                        "SOL" to assetRow("10.00000009", "100.00", "1000.00"),
                    ),
                ),
                snapshot(
                    t2,
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "40000.00", "40000.00"),
                        "USD" to assetRow("60000.00", "1.00", "60000.00"),
                        "SOL" to assetRow("0.00000002", "100.00", "0.00"),
                    ),
                ),
            )
            val spotLeg = ledgerEvent(
                t1,
                "SOL",
                "-10.00000009",
                type = "transfer",
                subtype = "spottostaking",
                refid = "STAKEPAIR",
                ledgerId = "SOL-SPOT-1",
            )
            val stakingLeg = ledgerEvent(
                t1,
                "SOL03",
                "+10.00000009",
                type = "transfer",
                subtype = "spottostaking",
                refid = "STAKEPAIR",
                ledgerId = "SOL-STAKING-1",
            )

            val result = calculate(snapshots, emptyList(), listOf(spotLeg, stakingLeg))

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE
            result.unavailableAt shouldBe t2
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

            check(result.availability == ComparisonAvailability.AVAILABLE) {
                "${result.unavailableReason} at ${result.unavailableAt}"
            }
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
                    source = TradeSource.MANUAL,
                    cycleId = null,
                    tradeId = "MANUAL-FILL-1",
                    orderTxid = "MANUAL-ORDER-1",
                    price = "50000.00",
                ),
            )

            val result = calculate(snapshots, trades)

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100000.00")
            result.points[1].differenceUSD shouldBeEqualComparingTo BigDecimal("-26.00")
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
                    source = TradeSource.MANUAL,
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
                    source = TradeSource.MANUAL,
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
                    source = TradeSource.MANUAL,
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
                    source = TradeSource.MANUAL,
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
                    source = TradeSource.MANUAL,
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

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100000.00")
            result.points[1].differenceUSD shouldBeEqualComparingTo BigDecimal("-26.00")

            val lowercaseSymbolResult = calculate(
                snapshots = snapshots,
                trades = trades.map { it.copy(symbol = "btc", pair = "btcUSD") },
            )

            lowercaseSymbolResult.availability shouldBe ComparisonAvailability.AVAILABLE
            lowercaseSymbolResult.confidence shouldBe ComparisonConfidence.RECONCILED
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
                    source = TradeSource.MANUAL,
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
            result.unavailableReason shouldBe ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE
            result.unavailableAt shouldBe now.plusSeconds(1)
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
                    source = TradeSource.MANUAL,
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
                    source = TradeSource.MANUAL,
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

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
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
                    source = TradeSource.MANUAL,
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
                    source = TradeSource.MANUAL,
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
                    source = TradeSource.MANUAL,
                    cycleId = null,
                    tradeId = "MANUAL-FILL-12",
                    orderTxid = "MANUAL-ORDER-12",
                    price = "0.10",
                ),
            )

            val result = calculate(snapshots, trades)

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100000.00")
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal("-26.00")
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
                    source = TradeSource.MANUAL,
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
                    source = TradeSource.MANUAL,
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
                    source = TradeSource.MANUAL,
                    cycleId = null,
                    tradeId = "LEGACY-ROUNDED-FILL",
                    orderTxid = "LEGACY-ROUNDED-ORDER",
                    price = "4361.24",
                ),
            )

            val result = calculate(snapshots, trades)

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal("-0.12")

            val legacyObservationResult = calculate(
                snapshots = snapshots.map { it.copy(balancesObservedAt = null) },
                trades = trades,
            )

            legacyObservationResult.availability shouldBe ComparisonAvailability.AVAILABLE
            legacyObservationResult.confidence shouldBe ComparisonConfidence.RECONCILED
            legacyObservationResult.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal("-0.12")

            val incompatiblePersistedCostResult = calculate(
                snapshots = snapshots.map { it.copy(balancesObservedAt = null) },
                trades = trades.map { it.copy(usdAmount = BigDecimal("21.01")) },
            )

            incompatiblePersistedCostResult.availability shouldBe ComparisonAvailability.UNAVAILABLE
            incompatiblePersistedCostResult.unavailableReason shouldBe
                ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE
        }

        "legacy rounded cost fallback keeps the authoritative base leg" {
            val result = calculate(
                snapshots = listOf(
                    snapshot(
                        now,
                        "200.00",
                        mapOf(
                            "BTC" to assetRow("1.00000000", "100", "100.00"),
                            "USD" to assetRow("100.00", "1", "100.00"),
                        ),
                    ),
                    snapshot(
                        now.plusSeconds(10),
                        "199.99",
                        mapOf(
                            "BTC" to assetRow("1.10000000", "100", "110.00"),
                            "USD" to assetRow("89.99", "1", "89.99"),
                        ),
                    ),
                ),
                trades = listOf(
                    trade(
                        timestamp = now.plusSeconds(5),
                        side = "buy",
                        symbol = "BTC",
                        volume = "0.1",
                        usdAmount = "10.00",
                        fee = "0.01",
                        source = TradeSource.MANUAL,
                        cycleId = null,
                        tradeId = "ROUNDED-TRADE",
                        price = "100",
                    ),
                ),
                rewards = listOf(
                    ledgerEvent(
                        timestamp = now.plusSeconds(5),
                        asset = "XXBT",
                        amount = "0.1",
                        type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                        refid = "ROUNDED-TRADE",
                        ledgerId = "ROUNDED-BASE",
                    ),
                    ledgerEvent(
                        timestamp = now.plusSeconds(5),
                        asset = "ZUSD",
                        amount = "-10.001",
                        fee = "0.01",
                        type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                        refid = "ROUNDED-TRADE",
                        ledgerId = "ROUNDED-QUOTE",
                    ),
                ),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points.last().rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("199.99")
            result.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("200.00")
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal("-0.01")
        }

        "API fill uses matching USD ledger economics when rounded cost disagrees with notional" {
            val t0 = now
            val tradeTime = now.plusSeconds(1800)
            val t1 = now.plusSeconds(3600)
            val tradeId = "API-FILL-LEDGER-USD-COST"
            val snapshots = listOf(
                snapshot(
                    t0,
                    "100.00",
                    mapOf(
                        "USD" to assetRow("100.00", "1.00", "100.00"),
                        "XLM" to assetRow("0.00", "10.01", "0.00"),
                    ),
                ),
                snapshot(
                    t1,
                    "100.01",
                    mapOf(
                        "USD" to assetRow("90.00", "1.00", "90.00"),
                        "XLM" to assetRow("1.00", "10.01", "10.01"),
                    ),
                ),
            )
            val fill = trade(
                timestamp = tradeTime,
                side = "BUY",
                symbol = "XLM",
                volume = "1.00",
                usdAmount = "10.00",
                source = TradeSource.API_FILL,
                cycleId = null,
                tradeId = tradeId,
                price = "10.01",
            )
            val tradeLedgers = listOf(
                ledgerEvent(
                    timestamp = tradeTime,
                    asset = "XLM",
                    amount = "1.00",
                    balance = "1.00",
                    type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                    ledgerId = "api-fill-base-leg",
                    refid = tradeId,
                ),
                ledgerEvent(
                    timestamp = tradeTime,
                    asset = "USD",
                    amount = "-10.00",
                    balance = "90.00",
                    type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                    ledgerId = "api-fill-quote-leg",
                    refid = tradeId,
                ),
            )

            val result = calculate(snapshots, trades = listOf(fill), rewards = tradeLedgers)

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points.last().rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("100.01")
            result.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100.00")
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal("0.01")
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
                result.points.first().differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
                result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal("-4.00")
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
                        type = KrakenApiConstants.LEDGER_TYPE_REWARD,
                        fee = "0.1731",
                        balance = "1.40391909",
                    ),
                ),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points.size shouldBe 2
            // The held BTC reward is part of the passive thesis; the authoritative post-event
            // balance also absorbs the legacy fee-rounding difference, so both sides remain equal.
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
        }

        // The AVAX staking event family exercises the authoritative-vs-netDelta policy:
        // an authoritative checkpoint may correct representational rounding only when its
        // implied delta is compatible with the stored amount-fee economics; otherwise the
        // replay retains `amount - fee`. Fail-conservative by contract, never silent rounding.
        //
        // Envelope note: a Spot-continuing staking row that drifts beyond the fee tolerance is
        // fail-closed by AuthoritativeLedgerBalanceValidator before the interval walk — the
        // beyond-tolerance class has no silent fallback at this layer. (This is a synthetic
        // scalar-class fixture; production diagnosis established the June-11 comparison blocker
        // was cross-window context loss on a BABY staking credit, since resolved separately.)

        "AVAX-style authoritative delta beyond fee tolerance fails closed at the Spot checkpoint" {
            // Spot-continuing checkpoint: netDelta +0.03007118 but the authoritative balance
            // implies +0.02991868 (difference 0.00015250 > 0.00005). The validator refuses the
            // Spot continuation and there is no consistent fallback scope, so the comparison
            // fails closed at the event.
            val seed = ledgerEvent(
                timestamp = now.minusSeconds(1),
                asset = "AVAX",
                amount = "0.04297118",
                type = KrakenApiConstants.LEDGER_TYPE_REWARD,
                fee = "0",
                balance = "70.09957609",
                ledgerId = "avax-spot-seed-beyond",
            )
            val eventAt = now.plusSeconds(5)
            val result = calculate(
                snapshots = listOf(
                    snapshot(
                        now,
                        "70.09957609",
                        mapOf("AVAX" to assetRow("70.09957609", "1", "70.09957609")),
                    ),
                    snapshot(
                        now.plusSeconds(10),
                        "70.12949477",
                        mapOf("AVAX" to assetRow("70.12949477", "1", "70.12949477")),
                    ),
                ),
                rewards = listOf(
                    seed,
                    ledgerEvent(
                        timestamp = eventAt,
                        asset = "AVAX",
                        amount = "0.04297118",
                        fee = "0.0129",
                        balance = "70.12949477",
                        ledgerId = "avax-staking-beyond",
                    ),
                ),
            )

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE
            result.unavailableAt shouldBe eventAt
            result.points shouldBe emptyList()
        }

        "AVAX-style authoritative delta inside fee tolerance applies the authoritative correction" {
            // Spot-continuing checkpoint: netDelta +0.03007118, authoritative +0.03003118;
            // difference 0.00004 <= 0.00005, so the checkpoint correction is the applied delta.
            val seed = ledgerEvent(
                timestamp = now.minusSeconds(1),
                asset = "AVAX",
                amount = "0.04297118",
                type = KrakenApiConstants.LEDGER_TYPE_REWARD,
                fee = "0",
                balance = "70.09957609",
                ledgerId = "avax-spot-seed",
            )
            val result = calculate(
                snapshots = listOf(
                    snapshot(
                        now,
                        "70.09957609",
                        mapOf("AVAX" to assetRow("70.09957609", "1", "70.09957609")),
                    ),
                    snapshot(
                        now.plusSeconds(10),
                        "70.12960727",
                        mapOf("AVAX" to assetRow("70.12960727", "1", "70.12960727")),
                    ),
                ),
                rewards = listOf(
                    seed,
                    ledgerEvent(
                        timestamp = now.plusSeconds(5),
                        asset = "AVAX",
                        amount = "0.04297118",
                        fee = "0.0129",
                        balance = "70.12960727",
                        ledgerId = "avax-staking-spot",
                    ),
                ),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points.size shouldBe 2
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "AVAX-style authoritative delta exactly at the fee tolerance boundary is accepted" {
            // difference exactly 0.00005 -> both envelopes are inclusive -> authoritative wins.
            val seed = ledgerEvent(
                timestamp = now.minusSeconds(1),
                asset = "AVAX",
                amount = "0.04297118",
                type = KrakenApiConstants.LEDGER_TYPE_REWARD,
                fee = "0",
                balance = "70.09957609",
                ledgerId = "avax-spot-seed-inclusive",
            )
            val result = calculate(
                snapshots = listOf(
                    snapshot(
                        now,
                        "70.09957609",
                        mapOf("AVAX" to assetRow("70.09957609", "1", "70.09957609")),
                    ),
                    snapshot(
                        now.plusSeconds(10),
                        "70.12969727",
                        mapOf("AVAX" to assetRow("70.12969727", "1", "70.12969727")),
                    ),
                ),
                rewards = listOf(
                    seed,
                    ledgerEvent(
                        timestamp = now.plusSeconds(5),
                        asset = "AVAX",
                        amount = "0.04297118",
                        fee = "0.0129",
                        balance = "70.12969727",
                        ledgerId = "avax-staking-inclusive",
                    ),
                ),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points.size shouldBe 2
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "AVAX-style authoritative delta just above the fee tolerance boundary fails closed" {
            // A Spot continuation 0.00006 above ledger economics is outside both rounding
            // envelopes: the validator refuses the Spot continuation, and since the row is not
            // balance-continuous with a zero-based sub-ledger (its authoritative balance
            // asserts a live Spot holding), no fallback scope manages it. The comparison
            // therefore fails closed exactly once at the event.
            val seed = ledgerEvent(
                timestamp = now.minusSeconds(1),
                asset = "AVAX",
                amount = "0.04297118",
                type = KrakenApiConstants.LEDGER_TYPE_REWARD,
                fee = "0",
                balance = "70.09957609",
                ledgerId = "avax-spot-seed-above",
            )
            val eventAt = now.plusSeconds(5)
            val result = calculate(
                snapshots = listOf(
                    snapshot(
                        now,
                        "70.09957609",
                        mapOf("AVAX" to assetRow("70.09957609", "1", "70.09957609")),
                    ),
                    snapshot(
                        now.plusSeconds(10),
                        "70.12970727",
                        mapOf("AVAX" to assetRow("70.12970727", "1", "70.12970727")),
                    ),
                ),
                rewards = listOf(
                    seed,
                    ledgerEvent(
                        timestamp = eventAt,
                        asset = "AVAX",
                        amount = "0.04297118",
                        fee = "0.0129",
                        balance = "70.12970727",
                        ledgerId = "avax-staking-above",
                    ),
                ),
            )

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE
            result.unavailableAt shouldBe eventAt
            result.points shouldBe emptyList()
        }

        "top-level promotion reward remains available when it credits an anchor holding" {
            val result = calculate(
                snapshots = listOf(
                    snapshot(
                        now,
                        "100.00",
                        mapOf(
                            "BTC" to assetRow("1.00000000", "1", "1.00"),
                            "USD" to assetRow("99.00", "1", "99.00"),
                        ),
                    ),
                    snapshot(
                        now.plusSeconds(10),
                        "100.09",
                        mapOf(
                            "BTC" to assetRow("1.09000000", "1", "1.09"),
                            "USD" to assetRow("99.00", "1", "99.00"),
                        ),
                    ),
                ),
                trades = emptyList(),
                rewards = listOf(
                    ledgerEvent(
                        timestamp = now.plusSeconds(5),
                        asset = "BTC",
                        amount = "0.10",
                        type = KrakenApiConstants.LEDGER_TYPE_REWARD,
                        fee = "0.01",
                        balance = "1.09",
                    ),
                ),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
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
                        type = KrakenApiConstants.LEDGER_TYPE_REWARD,
                        balance = "1.00000000",
                    ),
                ),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "authoritative staking balance cannot override Spot ledger replay" {
            val t0 = now
            val result = calculate(
                snapshots = listOf(
                    snapshot(
                        t0,
                        "101.00",
                        mapOf(
                            "BTC" to assetRow("1.00000000", "1", "1.00"),
                            "USD" to assetRow("100.00", "1", "100.00"),
                        ),
                    ),
                    snapshot(
                        t0.plusSeconds(10),
                        "101.00",
                        mapOf(
                            "BTC" to assetRow("1.00000000", "1", "1.00"),
                            "USD" to assetRow("100.00", "1", "100.00"),
                        ),
                    ),
                ),
                trades = emptyList(),
                rewards = listOf(
                    ledgerEvent(
                        timestamp = t0.plusSeconds(5),
                        asset = "BTC",
                        amount = "0.10000000",
                        balance = "1.00000000",
                    ),
                ),
            )

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE
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
            result.points shouldBe emptyList()
        }

        // Approved cross-asset reward entitlement policy: a Kraken BTC staking reward paid in
        // BABY credits the qualifying exposure owner (BTC balance present at the event), and
        // the reward is received in kind — only a deterministic documented BABY->BTC mapping
        // grants cross-asset entitlement; no other asset pair, no heuristic inference.
        "BABY staking reward credits to qualifying BTC exposure and buys and hold is mirrored in kind" {
            val eventAt = now.plusSeconds(5)
            val result = calculate(
                snapshots = listOf(
                    snapshot(
                        now,
                        "100.00",
                        mapOf(
                            "BTC" to assetRow("1.00000000", "1", "1.00"),
                            "USD" to assetRow("99.00", "1", "99.00"),
                        ),
                    ),
                    snapshot(
                        now.plusSeconds(10),
                        "100.09",
                        mapOf(
                            "BTC" to assetRow("1.00000000", "1", "1.00"),
                            "BABY" to assetRow("0.50000000", "0.18", "0.09"),
                            "USD" to assetRow("99.00", "1", "99.00"),
                        ),
                    ),
                ),
                rewards = listOf(
                    ledgerEvent(
                        timestamp = eventAt,
                        asset = "BABY",
                        amount = "0.5",
                        fee = "0",
                        balance = "0.5",
                        ledgerId = "baby-btc-staking-rule",
                    ),
                ),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.unavailableAt shouldBe null
            result.points.size shouldBe 2
            result.points.first().rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("100.00")
            result.points.first().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100.00")
            result.points.first().differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
            result.points.last().rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("100.09")
            result.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100.09")
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "cross-asset staking reward omitted from snapshot rows reconciles against portfolio total" {
            val eventAt = now.plusSeconds(5)
            val snapshotAssets = mapOf(
                "BTC" to assetRow("1.00000000", "1", "1.00"),
                "USD" to assetRow("99.00", "1", "99.00"),
            )
            val baseline = snapshot(now, "100.00", snapshotAssets)
            val reward = ledgerEvent(
                timestamp = eventAt,
                asset = "BABY",
                amount = "0.5",
                fee = "0",
                balance = "0.5",
                type = KrakenApiConstants.LEDGER_TYPE_STAKING,
                ledgerId = "unlisted-baby-btc-staking-reward",
            )

            val result = calculate(
                snapshots = listOf(
                    baseline,
                    snapshot(now.plusSeconds(10), "100.09", snapshotAssets),
                ),
                rewards = listOf(reward),
                inceptionSnapshot = baseline,
                configuredAssetUniverse = setOf("BTC", "USD"),
                priceProvider = mapPriceProvider(
                    mapOf("BTC" to BigDecimal.ONE, "BABY" to BigDecimal("0.18")),
                ),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points.last().rebalancerValueUSD.shouldBeEqualComparingTo(BigDecimal("100.09"))
            result.points.last().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("100.09"))
            result.points.last().differenceUSD.shouldBeEqualComparingTo(BigDecimal.ZERO)
        }

        "zero-net BABY staking reward with BTC exposure leaves comparison NAV unchanged" {
            val baseline = snapshot(
                now,
                "100.00",
                mapOf(
                    "BTC" to assetRow("1.00000000", "100.00", "100.00"),
                    "USD" to assetRow("0.00", "1.00", "0.00"),
                ),
            )
            val after = snapshot(
                now.plusSeconds(60),
                "100.00",
                mapOf(
                    "BTC" to assetRow("1.00000000", "100.00", "100.00"),
                    "USD" to assetRow("0.00", "1.00", "0.00"),
                ),
            )
            val zeroReward = ledgerEvent(
                timestamp = now.plusSeconds(30),
                asset = "BABY",
                amount = "0.00",
                fee = "0",
                type = KrakenApiConstants.LEDGER_TYPE_STAKING,
                ledgerId = "zero-net-baby-btc-staking-reward",
            )

            val result = calculate(
                snapshots = listOf(baseline, after),
                rewards = listOf(zeroReward),
                inceptionSnapshot = baseline,
                configuredAssetUniverse = setOf("BTC", "USD"),
                priceProvider = mapPriceProvider(
                    mapOf("BTC" to BigDecimal("100.00"), "BABY" to BigDecimal("0.18")),
                ),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points.last().rebalancerValueUSD.shouldBeEqualComparingTo(BigDecimal("100.00"))
            result.points.last().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("100.00"))
            result.points.last().differenceUSD.shouldBeEqualComparingTo(BigDecimal.ZERO)
        }

        "cross-asset staking reward already included in total is not added twice" {
            val eventAt = now.plusSeconds(5)
            val baselineAssets = mapOf(
                "BTC" to assetRow("1.00000000", "1.00", "1.00"),
                "USD" to assetRow("99.00", "1.00", "99.00"),
            )
            val baseline = snapshot(now, "100.00", baselineAssets)
            val snapshotWithUnpricedReward = mapOf(
                "BTC" to assetRow("1.00000000", "1.00", "1.00"),
                "BABY" to assetRow("0.50000000", "0.00", "0.00"),
                "USD" to assetRow("99.00", "1.00", "99.00"),
            )
            val reward = ledgerEvent(
                timestamp = eventAt,
                asset = "BABY",
                amount = "0.5",
                fee = "0",
                type = KrakenApiConstants.LEDGER_TYPE_STAKING,
                ledgerId = "unpriced-baby-btc-staking-reward",
            )

            val result = calculate(
                snapshots = listOf(
                    baseline,
                    snapshot(now.plusSeconds(10), "100.09", snapshotWithUnpricedReward),
                    snapshot(now.plusSeconds(20), "100.09", snapshotWithUnpricedReward),
                ),
                rewards = listOf(reward),
                inceptionSnapshot = baseline,
                configuredAssetUniverse = setOf("BTC", "USD"),
                priceProvider = mapPriceProvider(
                    mapOf("BTC" to BigDecimal.ONE, "BABY" to BigDecimal("0.18")),
                ),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points.last().rebalancerValueUSD.shouldBeEqualComparingTo(BigDecimal("100.09"))
            result.points.last().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("100.09"))
            result.points.last().differenceUSD.shouldBeEqualComparingTo(BigDecimal.ZERO)
        }

        "BABY staking reward without qualifying BTC exposure fails closed" {
            val eventAt = now.plusSeconds(5)
            val result = calculate(
                snapshots = listOf(
                    snapshot(
                        now,
                        "99.00",
                        mapOf(
                            "BTC" to assetRow("0.00000000", "1", "0.00"),
                            "USD" to assetRow("99.00", "1", "99.00"),
                        ),
                    ),
                    snapshot(
                        now.plusSeconds(10),
                        "99.09",
                        mapOf(
                            "BTC" to assetRow("0.00000000", "1", "0.00"),
                            "BABY" to assetRow("0.50000000", "0.18", "0.09"),
                            "USD" to assetRow("99.00", "1", "99.00"),
                        ),
                    ),
                ),
                rewards = listOf(
                    ledgerEvent(
                        timestamp = eventAt,
                        asset = "BABY",
                        amount = "0.5",
                        fee = "0",
                        balance = "0.5",
                        ledgerId = "baby-no-btc-exposure",
                    ),
                ),
            )

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE
            result.unavailableAt shouldBe now.plusSeconds(10)
            result.points shouldBe emptyList()
        }

        "rounded opaque BABY staking checkpoint stays outside Spot and remains BTC-entitled" {
            val t0 = now
            val depositAt = t0.plusSeconds(10)
            val afterDepositAt = t0.plusSeconds(20)
            val rewardAt = t0.plusSeconds(30)
            val afterRewardAt = t0.plusSeconds(40)
            val baseline = snapshot(
                t0,
                "100.00",
                mapOf(
                    "BTC" to assetRow("1.00000000", "1.00", "1.00"),
                    "USD" to assetRow("99.00", "1.00", "99.00"),
                    "BABY" to assetRow("0", "0.18", "0.00"),
                ),
            )
            val afterDeposit = snapshot(
                afterDepositAt,
                "100.18",
                mapOf(
                    "BTC" to assetRow("1.00000000", "1.00", "1.00"),
                    "USD" to assetRow("99.00", "1.00", "99.00"),
                    "BABY" to assetRow("1.00000000", "0.18", "0.18"),
                ),
            )
            val afterReward = afterDeposit.copy(timestamp = afterRewardAt)
            val babyDeposit = ledgerEvent(
                timestamp = depositAt,
                asset = "BABY",
                amount = "1.00000000",
                type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                balance = "1.00000000",
                ledgerId = "baby-spot-deposit",
            )
            val babyReward = ledgerEvent(
                timestamp = rewardAt,
                asset = "BABY",
                amount = "1.08452",
                fee = "0.3254",
                balance = "0.75917",
                ledgerId = "baby-opaque-rounded-checkpoint",
            )
            val scopeValidation = AuthoritativeLedgerBalanceValidator.validate(listOf(babyDeposit, babyReward))
            val storedSpotBalance = afterReward.assets.getValue("BABY").balance

            scopeValidation.isValid shouldBe true
            scopeValidation.resolvedScopes[babyReward.ledgerId] shouldBe
                AuthoritativeLedgerBalanceValidator.LedgerWalletScope.OPAQUE_STAKING
            RewardEntitlements.qualifyingSourceAsset(babyReward) shouldBe Asset.BTC
            storedSpotBalance shouldBeEqualComparingTo BigDecimal("1.00000000")

            val result = calculate(
                snapshots = listOf(baseline, afterDeposit, afterReward),
                rewards = listOf(babyDeposit, babyReward),
                inceptionSnapshot = baseline,
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal.ONE, "BABY" to BigDecimal("0.18"))),
                configuredAssetUniverse = setOf("BTC", "USD", "BABY"),
            )

            check(result.availability == ComparisonAvailability.AVAILABLE) {
                "${result.unavailableReason} at ${result.unavailableAt}"
            }
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.unavailableAt shouldBe null
            result.points.size shouldBe 3
            result.points[1].rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("100.18")
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100.18")
            result.points.last().rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("100.32")
            result.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100.32")
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
            afterReward.assets.getValue("BABY").balance shouldBeEqualComparingTo BigDecimal("1.00000000")
            afterReward.totalValueUSD shouldBeEqualComparingTo BigDecimal("100.18")
        }

        "pre-baseline non-Spot balance larger than the Spot anchor fails closed" {
            val baseline = snapshot(
                now,
                "110.00",
                mapOf(
                    "BTC" to assetRow("0.10000000", "100.00", "10.00"),
                    "USD" to assetRow("100.00", "1.00", "100.00"),
                ),
            )
            val after = baseline.copy(
                timestamp = now.plusSeconds(10),
                balancesObservedAt = now.plusSeconds(10),
            )
            val stakingCheckpoint = ledgerEvent(
                timestamp = now.minusSeconds(1),
                asset = "BTC",
                amount = "0.50000000",
                type = KrakenApiConstants.LEDGER_TYPE_STAKING,
                balance = "0.50000000",
                ledgerId = "pre-baseline-non-spot-exceeds-anchor",
            )

            val result = calculate(
                snapshots = listOf(baseline, after),
                rewards = listOf(stakingCheckpoint),
                inceptionSnapshot = baseline,
                configuredAssetUniverse = setOf("BTC", "USD"),
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal("100.00"))),
            )

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE
            result.unavailableAt shouldBe baseline.timestamp
        }

        "opaque BABY dust sweep scope is validated before full-wallet replay" {
            val t0 = now
            val depositAt = t0.plusSeconds(10)
            val rewardAt = t0.plusSeconds(20)
            val afterRewardAt = t0.plusSeconds(25)
            val sweepAt = t0.plusSeconds(30)
            val afterSweepAt = t0.plusSeconds(40)
            val baseline = snapshot(
                t0,
                "100.00",
                mapOf(
                    "BTC" to assetRow("1.00000000", "1.00", "1.00"),
                    "USD" to assetRow("99.00", "1.00", "99.00"),
                    "BABY" to assetRow("0", "0.18", "0.00"),
                ),
            )
            val afterDeposit = snapshot(
                depositAt,
                "100.18",
                mapOf(
                    "BTC" to assetRow("1.00000000", "1.00", "1.00"),
                    "USD" to assetRow("99.00", "1.00", "99.00"),
                    "BABY" to assetRow("1.00000000", "0.18", "0.18"),
                ),
            )
            val afterReward = afterDeposit.copy(timestamp = afterRewardAt)
            val afterSweep = snapshot(
                afterSweepAt,
                "100.192",
                mapOf(
                    "BTC" to assetRow("1.00000000", "1.00", "1.00"),
                    "USD" to assetRow("99.012", "1.00", "99.012"),
                    "BABY" to assetRow("1.00000000", "0.18", "0.18"),
                ),
            )
            val babyDeposit = ledgerEvent(
                timestamp = depositAt,
                asset = "BABY",
                amount = "1.00000000",
                type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                balance = "1.00000000",
                ledgerId = "baby-sweep-spot-deposit",
            )
            val babyReward = ledgerEvent(
                timestamp = rewardAt,
                asset = "BABY",
                amount = "1.08452",
                fee = "0.3254",
                balance = "0.75917",
                ledgerId = "baby-sweep-opaque-reward",
            )
            val sweepRefid = "baby-dust-sweep"
            val babySweep = ledgerEvent(
                timestamp = sweepAt,
                asset = "BABY",
                amount = "-0.75917",
                type = KrakenApiConstants.LEDGER_TYPE_SPEND,
                subtype = "dustsweeping",
                ledgerId = "baby-dust-sweep-spend",
                refid = sweepRefid,
            )
            val usdSweep = ledgerEvent(
                timestamp = sweepAt,
                asset = "USD",
                amount = "0.012",
                type = KrakenApiConstants.LEDGER_TYPE_RECEIVE,
                subtype = "dustsweeping",
                balance = "99.012",
                ledgerId = "baby-dust-sweep-receive",
                refid = sweepRefid,
            )
            val scopeValidation = AuthoritativeLedgerBalanceValidator.validate(
                listOf(babyDeposit, babyReward, babySweep, usdSweep),
            )
            scopeValidation.isValid shouldBe true
            scopeValidation.resolvedScopes[babyReward.ledgerId] shouldBe
                AuthoritativeLedgerBalanceValidator.LedgerWalletScope.OPAQUE_STAKING
            scopeValidation.resolvedScopes[babySweep.ledgerId] shouldBe
                AuthoritativeLedgerBalanceValidator.LedgerWalletScope.OPAQUE_STAKING
            scopeValidation.resolvedScopes[usdSweep.ledgerId] shouldBe
                AuthoritativeLedgerBalanceValidator.LedgerWalletScope.SPOT
            RewardEntitlements.qualifyingSourceAsset(babyReward) shouldBe Asset.BTC

            val result = calculate(
                snapshots = listOf(baseline, afterDeposit, afterReward, afterSweep),
                rewards = listOf(babyDeposit, babyReward, babySweep, usdSweep),
                inceptionSnapshot = baseline,
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal.ONE, "BABY" to BigDecimal("0.18"))),
                configuredAssetUniverse = setOf("BTC", "USD", "BABY"),
            )

            check(result.availability == ComparisonAvailability.AVAILABLE) {
                "${result.unavailableReason} at ${result.unavailableAt}"
            }
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.unavailableAt shouldBe null
            result.points.size shouldBe 4
            result.points[1].rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("100.18")
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100.18")
            result.points[2].rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("100.32")
            result.points[2].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100.32")
            result.points.last().rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("100.19")
            result.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100.32")
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal("-0.12")
            afterSweep.assets.getValue("BABY").balance shouldBeEqualComparingTo BigDecimal("1.00000000")
            afterSweep.totalValueUSD shouldBeEqualComparingTo BigDecimal("100.192")
        }

        "undocumented asset pair cannot self-invent cross-asset entitlement even with BTC exposure" {
            val eventAt = now.plusSeconds(5)
            val result = calculate(
                snapshots = listOf(
                    snapshot(
                        now,
                        "100.00",
                        mapOf(
                            "BTC" to assetRow("1.00000000", "1", "1.00"),
                            "USD" to assetRow("99.00", "1", "99.00"),
                        ),
                    ),
                    snapshot(
                        now.plusSeconds(10),
                        "100.09",
                        mapOf(
                            "BTC" to assetRow("1.00000000", "1", "1.00"),
                            "DOGE" to assetRow("100.00000000", "0.0009", "0.09"),
                            "USD" to assetRow("99.00", "1", "99.00"),
                        ),
                    ),
                ),
                rewards = listOf(
                    ledgerEvent(
                        timestamp = eventAt,
                        asset = "DOGE",
                        amount = "100",
                        fee = "0",
                        balance = "100",
                        ledgerId = "doge-not-documented",
                    ),
                ),
            )

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE
            result.unavailableAt shouldBe now.plusSeconds(10)
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

        "legacy boundary ledger already embodied in previous balance is not re-proposed" {
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
                    ledgerEvent(
                        timestamp = now.minusSeconds(3600),
                        asset = "BTC",
                        amount = "1.0",
                        type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                        balance = "1.0",
                        ledgerId = "legacy-embodied-btc",
                    ),
                ),
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal("50000.00"))),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "legacy boundary ledger with mismatched authority is still reconciled once" {
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
                    ledgerEvent(
                        timestamp = now.plusSeconds(1),
                        asset = "BTC",
                        amount = "0.1",
                        type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                        balance = "999.0",
                        ledgerId = "legacy-mismatched-btc",
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
            // The SOL reward is excluded because the anchor held no SOL; BTC and ETH rewards are
            // holding-dependent and therefore remain in the passive basket.
            result.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100800.00")
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal("50.00")
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

        "lone internal transfer markers are unavailable instead of silently skipped" {
            val snapshots = listOf(
                snapshot(
                    now,
                    "100.00",
                    mapOf("BTC" to assetRow("1.00", "100.00", "100.00")),
                ),
                snapshot(
                    now.plusSeconds(3600),
                    "200.00",
                    mapOf("BTC" to assetRow("2.00", "100.00", "200.00")),
                ),
            )
            val loneInternalMarker = ledgerEvent(
                timestamp = now.plusSeconds(1800),
                asset = "BTC",
                amount = "1.00",
                type = KrakenApiConstants.LEDGER_TYPE_TRANSFER,
                subtype = "spottostaking",
                refid = "incomplete-internal-transfer",
            )

            val result = calculate(snapshots, rewards = listOf(loneInternalMarker))

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.UNSUPPORTED_LEDGER_TYPE
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

        "trade for a symbol absent from the snapshot series fails closed" {
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
                    usdAmount = "1000.00",
                    fee = "0",
                ),
            )

            val result = calculate(snapshots, trades)

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE
            result.unavailableAt shouldBe now.plusSeconds(3600)
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

        "negative baseline holding fails closed before non-positive replay" {
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

            result.unavailableReason shouldBe ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE
            result.baselineTimestamp shouldBe now
            result.unavailableAt shouldBe now
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

        "pre-regulars staking reward reconciles against anchor-relative balances" {
            // Production July-2026 shape: a staking leg dated before every regular event
            // must snap against the pre-regulars anchor, not the post-regulars running
            // state (whose newer regular effects a stale snap would overwrite, leaving
            // no matching subset). The context deposit seeds the validator SPOT scope;
            // the in-window deposit is a forced regular, never a subset candidate, so
            // the staking leg is the sole late candidate and the unique-match rule
            // cannot multi-match. Without anchor-relative evaluation this fails
            // UNAVAILABLE: the staking snap lands on post-regulars state.
            val snapshots = listOf(
                snapshot(
                    timestamp = now,
                    totalValueUSD = "100000.00",
                    assets = mapOf(
                        "BTC" to assetRow("1.0", "50000", "50000.00"),
                        "USD" to assetRow("50000", "1", "50000.00"),
                    ),
                    balancesObservedAt = null,
                ),
                snapshot(
                    timestamp = now.plusSeconds(3600),
                    totalValueUSD = "129995.05",
                    assets = mapOf(
                        "BTC" to assetRow("1.599901", "50000", "79995.05"),
                        "USD" to assetRow("50000", "1", "50000.00"),
                    ),
                    balancesObservedAt = null,
                ),
            )
            // Context (pre-window) funding of the opening balance: feeds validator
            // scope resolution only, never reconciliation events.
            val context = listOf(
                ledgerEvent(
                    now.minusSeconds(3600),
                    "BTC",
                    "1.0",
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    fee = "0",
                    balance = "1.0",
                ),
            )
            // Boundary staking leg (within 1000ms of prev): the 4dp-rounded fee leaves
            // amount-minus-fee (0.0999) 1e-6 below the recorded post, inside the
            // compatibility gate. Chronological posts chain 1.0 -> 1.099901 -> 1.599901.
            val rewards = listOf(
                ledgerEvent(now.plusMillis(500), "BTC", "0.1", fee = "0.0001", balance = "1.099901"),
                ledgerEvent(
                    now.plusSeconds(1500),
                    "BTC",
                    "0.5",
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    fee = "0",
                    balance = "1.599901",
                ),
            )

            val result = calculate(
                snapshots,
                emptyList(),
                rewards,
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal("50000.00"))),
                ledgerContext = context,
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
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
            result.points[1].differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "tokenized holdings are omitted from comparison NAV without requesting their mark" {
            val baseline = snapshot(
                now,
                "12000.00",
                mapOf(
                    "BTC" to assetRow("0.1", "50000.00", "5000.00"),
                    "USD" to assetRow("5000.00", "1.00", "5000.00"),
                    "STRC" to assetRow("10.00", "0", "2000.00"),
                ),
            )
            val later = baseline.copy(timestamp = now.plusSeconds(3600), balancesObservedAt = now.plusSeconds(3600))
            val requestedPrices = mutableListOf<String>()
            val result = calculate(
                listOf(baseline, later),
                priceProvider = HistoricalPriceProvider { symbol, _ ->
                    requestedPrices += symbol
                    null
                },
                assetMetadata = tokenizedStrcAssetMetadata(),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points.first().rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("10000.00")
            result.points.first().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("10000.00")
            result.points.last().rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("10000.00")
            requestedPrices.any { Asset.normalizeLedgerAsset(it).equals("STRC", ignoreCase = true) } shouldBe false
        }

        "unallocated ADA MORPHO and XMR remain in the comparison currency scope" {
            val assets = mapOf(
                "USD" to assetRow("100.00", "1.00", "100.00"),
                "ADA" to assetRow("2.00", "10.00", "20.00"),
                "MORPHO" to assetRow("5.00", "2.00", "10.00"),
                "XMR" to assetRow("0.50", "20.00", "10.00"),
            )
            val result = calculate(
                listOf(
                    snapshot(now, "140.00", assets),
                    snapshot(now.plusSeconds(3600), "140.00", assets),
                ),
                configuredAssetUniverse = setOf("USD"),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points.first().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("140.00")
            result.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("140.00")
            result.points.last().rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("140.00")
        }

        "tokenized security buy debit and later sale proceeds replay once without token profit" {
            val buyTime = now.plusSeconds(1800)
            val saleTime = now.plusSeconds(5400)
            val result = calculate(
                snapshots = listOf(
                    snapshot(now, "10000.00", mapOf("USD" to assetRow("10000.00", "1.00", "10000.00"))),
                    snapshot(
                        now.plusSeconds(3600),
                        "10000.00",
                        mapOf(
                            "USD" to assetRow("7700.00", "1.00", "7700.00"),
                            "STRC" to assetRow("23.00", "0", "2300.00"),
                        ),
                    ),
                    snapshot(
                        now.plusSeconds(7200),
                        "11700.00",
                        mapOf(
                            "USD" to assetRow("11700.00", "1.00", "11700.00"),
                            "STRC" to assetRow("0", "0", "0"),
                        ),
                    ),
                ),
                trades = listOf(
                    manualTrade(buyTime, "buy", "STRC", "23.00", "2299.00", fee = "1.00"),
                    manualTrade(saleTime, "sell", "STRC", "23.00", "4001.00", fee = "1.00"),
                ),
                assetMetadata = tokenizedStrcAssetMetadata(),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("7700.00")
            result.points[1].rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("7700.00")
            result.points[2].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("11700.00")
            result.points[2].rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("11700.00")
        }

        "tokenized linked trade legs replay exact USD quote cash once" {
            val buyTime = now.plusSeconds(1800)
            val saleTime = now.plusSeconds(5400)
            val buyTradeId = "STRC-LINKED-BUY"
            val sellTradeId = "STRC-LINKED-SELL"
            val result = calculate(
                snapshots = listOf(
                    snapshot(now, "10000.00", mapOf("USD" to assetRow("10000.00", "1.00", "10000.00"))),
                    snapshot(
                        now.plusSeconds(3600),
                        "10000.00",
                        mapOf(
                            "USD" to assetRow("7700.00", "1.00", "7700.00"),
                            "STRC" to assetRow("23.00", "0", "2300.00"),
                        ),
                    ),
                    snapshot(
                        now.plusSeconds(7200),
                        "11700.00",
                        mapOf(
                            "USD" to assetRow("11700.00", "1.00", "11700.00"),
                            "STRC" to assetRow("0", "0", "0"),
                        ),
                    ),
                ),
                trades = listOf(
                    manualTrade(
                        buyTime,
                        "buy",
                        "STRC",
                        "23.00",
                        "2299.00",
                        fee = "1.00",
                        tradeId = buyTradeId,
                    ),
                    manualTrade(
                        saleTime,
                        "sell",
                        "STRC",
                        "23.00",
                        "4001.00",
                        fee = "1.00",
                        tradeId = sellTradeId,
                    ),
                ),
                rewards = listOf(
                    ledgerEvent(
                        timestamp = buyTime,
                        asset = "STRC",
                        amount = "23.00",
                        type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                        refid = buyTradeId,
                    ),
                    ledgerEvent(
                        timestamp = buyTime,
                        asset = "USD",
                        amount = "-2299.00",
                        fee = "1.00",
                        balance = "7700.00",
                        type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                        refid = buyTradeId,
                    ),
                    ledgerEvent(
                        timestamp = saleTime,
                        asset = "STRC",
                        amount = "-23.00",
                        type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                        refid = sellTradeId,
                    ),
                    ledgerEvent(
                        timestamp = saleTime,
                        asset = "USD",
                        amount = "4001.00",
                        fee = "1.00",
                        balance = "11700.00",
                        type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                        refid = sellTradeId,
                    ),
                ),
                assetMetadata = tokenizedStrcAssetMetadata(),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points[1].rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("7700.00")
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("7700.00")
            result.points.last().rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("11700.00")
            result.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("11700.00")
        }

        "source-proven USD security dividend uses net amount and anchor weights while unknown dividend fails" {
            val dividendAt = now.plusSeconds(1800)
            val snapshots = listOf(
                snapshot(
                    now,
                    "1000.00",
                    mapOf(
                        "BTC" to assetRow("1.00", "500.00", "500.00"),
                        "USD" to assetRow("500.00", "1.00", "500.00"),
                    ),
                ),
                snapshot(
                    dividendAt.plusSeconds(10),
                    "1550.00",
                    mapOf(
                        "BTC" to assetRow("1.00", "1000.00", "1000.00"),
                        "USD" to assetRow("550.00", "1.00", "550.00"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(3600),
                    "2550.00",
                    mapOf(
                        "BTC" to assetRow("1.00", "2000.00", "2000.00"),
                        "USD" to assetRow("550.00", "1.00", "550.00"),
                    ),
                ),
            )
            val dividend = ledgerEvent(
                timestamp = dividendAt,
                asset = "USD",
                amount = "50.10",
                fee = "0.10",
                type = KrakenApiConstants.LEDGER_TYPE_DIVIDEND,
                subtype = "cashdividend",
            )
            val priceProvider = HistoricalPriceProvider { symbol, timestamp ->
                if (symbol == "BTC" && timestamp == dividendAt) BigDecimal("1000.00") else null
            }
            val proven = calculate(snapshots, rewards = listOf(dividend), priceProvider = priceProvider)
            val unknown = calculate(
                snapshots,
                rewards = listOf(dividend.copy(subtype = null, ledgerId = "unproven-usd-dividend")),
            )

            proven.availability shouldBe ComparisonAvailability.AVAILABLE
            proven.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("1550.00")
            proven.points[1].differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
            proven.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("2575.00")
            proven.points.last().rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("2550.00")
            unknown.availability shouldBe ComparisonAvailability.UNAVAILABLE
            unknown.unavailableReason shouldBe ComparisonUnavailableReason.AMBIGUOUS_LEDGER_TYPE
        }

        "source-proven USD security dividend fails closed when an anchor-weight price is missing" {
            val dividendAt = now.plusSeconds(1800)
            val snapshots = listOf(
                snapshot(
                    now,
                    "1000.00",
                    mapOf(
                        "BTC" to assetRow("1.00", "500.00", "500.00"),
                        "USD" to assetRow("500.00", "1.00", "500.00"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(3600),
                    "1050.00",
                    mapOf(
                        "BTC" to assetRow("1.00", "500.00", "500.00"),
                        "USD" to assetRow("550.00", "1.00", "550.00"),
                    ),
                ),
            )
            val dividend = ledgerEvent(
                timestamp = dividendAt,
                asset = "USD",
                amount = "50.00",
                type = KrakenApiConstants.LEDGER_TYPE_DIVIDEND,
                subtype = "cashdividend",
                balance = "550.00",
            )
            val priceProvider = HistoricalPriceProvider { symbol, timestamp ->
                when {
                    Asset.normalizeLedgerAsset(symbol).equals("BTC", ignoreCase = true) && timestamp != dividendAt ->
                        BigDecimal("500.00")

                    Asset.normalizeLedgerAsset(symbol).equals("USD", ignoreCase = true) -> BigDecimal.ONE

                    else -> null
                }
            }

            val result = calculate(snapshots, rewards = listOf(dividend), priceProvider = priceProvider)

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.MISSING_PRICE
            result.unavailableAt shouldBe dividendAt
        }

        "authoritative related tokenized ledger proves a USD security dividend source" {
            val dividendAt = now.plusSeconds(1800)
            val snapshots = listOf(
                snapshot(
                    now,
                    "2000.00",
                    mapOf(
                        "BTC" to assetRow("1.00", "500.00", "500.00"),
                        "USD" to assetRow("500.00", "1.00", "500.00"),
                        "STRC" to assetRow("1.00", "0", "1000.00"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(3600),
                    "2050.00",
                    mapOf(
                        "BTC" to assetRow("1.00", "500.00", "500.00"),
                        "USD" to assetRow("550.00", "1.00", "550.00"),
                        "STRC" to assetRow("1.00", "0", "1000.00"),
                    ),
                ),
            )
            val securitySource = ledgerEvent(
                timestamp = dividendAt,
                asset = "STRC",
                amount = "0",
                type = KrakenApiConstants.LEDGER_TYPE_DIVIDEND,
                balance = "1.00",
                ledgerId = "tokenized-dividend-source",
                refid = "security-dividend-1",
            )
            val cashDividend = ledgerEvent(
                timestamp = dividendAt,
                asset = "USD",
                amount = "50.00",
                type = KrakenApiConstants.LEDGER_TYPE_DIVIDEND,
                ledgerId = "usd-security-dividend",
                refid = "security-dividend-1",
            )
            val requestedPrices = mutableListOf<String>()
            val result = calculate(
                snapshots,
                rewards = listOf(securitySource, cashDividend),
                priceProvider = HistoricalPriceProvider { symbol, _ ->
                    requestedPrices += symbol
                    if (symbol == "BTC") BigDecimal("500.00") else null
                },
                assetMetadata = tokenizedStrcAssetMetadata(),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points.last().rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("1050.00")
            result.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("1050.00")
            requestedPrices.any { Asset.normalizeLedgerAsset(it).equals("STRC", ignoreCase = true) } shouldBe false
        }

        "negative source-proven USD cash dividend is neutral to NAV" {
            val dividendAt = now.plusSeconds(1800)
            val snapshots = listOf(
                snapshot(now, "1000.00", mapOf("USD" to assetRow("1000.00", "1.00", "1000.00"))),
                snapshot(
                    now.plusSeconds(3600),
                    "990.00",
                    mapOf("USD" to assetRow("990.00", "1.00", "990.00")),
                ),
            )
            val dividend = ledgerEvent(
                timestamp = dividendAt,
                asset = "USD",
                amount = "-10.00",
                type = KrakenApiConstants.LEDGER_TYPE_DIVIDEND,
                subtype = "cashdividend",
                balance = "990.00",
            )

            val result = calculate(snapshots, rewards = listOf(dividend))

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points.last().timestamp shouldBe snapshots.last().timestamp
            result.points.last().rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("990.00")
            result.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("990.00")
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "source-proven USD cash dividend fee remains a neutral outflow when gross amount is zero" {
            val dividendAt = now.plusSeconds(1800)
            val snapshots = listOf(
                snapshot(now, "1000.00", mapOf("USD" to assetRow("1000.00", "1.00", "1000.00"))),
                snapshot(
                    now.plusSeconds(3600),
                    "999.99",
                    mapOf("USD" to assetRow("999.99", "1.00", "999.99")),
                ),
            )
            val dividend = ledgerEvent(
                timestamp = dividendAt,
                asset = "USD",
                amount = "0",
                fee = "0.01",
                type = KrakenApiConstants.LEDGER_TYPE_DIVIDEND,
                subtype = "cashdividend",
                balance = "999.99",
                hasAuthoritativeFee = true,
            )

            val result = calculate(snapshots, rewards = listOf(dividend))

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points.last().rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("999.99")
            result.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("999.99")
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "same-refid currency ledger does not prove a USD security dividend source" {
            val dividendAt = now.plusSeconds(1800)
            val refid = "BTC-CURRENCY-NOT-SECURITY-PROOF"
            val snapshots = listOf(
                snapshot(
                    now,
                    "1000.00",
                    mapOf(
                        "BTC" to assetRow("1.00", "500.00", "500.00"),
                        "USD" to assetRow("500.00", "1.00", "500.00"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(3600),
                    "1050.00",
                    mapOf(
                        "BTC" to assetRow("1.00", "500.00", "500.00"),
                        "USD" to assetRow("550.00", "1.00", "550.00"),
                    ),
                ),
            )
            val relatedBitcoinLedger = ledgerEvent(
                timestamp = dividendAt,
                asset = "BTC",
                amount = "0.00",
                type = KrakenApiConstants.LEDGER_TYPE_DIVIDEND,
                balance = "1.00",
                ledgerId = "currency-btc-dividend-source",
                refid = refid,
            )
            val cashDividend = ledgerEvent(
                timestamp = dividendAt,
                asset = "USD",
                amount = "50.00",
                type = KrakenApiConstants.LEDGER_TYPE_DIVIDEND,
                balance = "550.00",
                ledgerId = "usd-dividend-with-currency-source",
                refid = refid,
            )

            val result = calculate(
                snapshots,
                rewards = listOf(relatedBitcoinLedger, cashDividend),
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal("500.00"))),
            )

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.AMBIGUOUS_LEDGER_TYPE
            result.unavailableAt shouldBe dividendAt
        }

        "tokenized source proof requires a nonblank matching refid and authoritative balance" {
            val dividendAt = now.plusSeconds(1800)
            val snapshots = listOf(
                snapshot(
                    now,
                    "2000.00",
                    mapOf(
                        "BTC" to assetRow("1.00", "500.00", "500.00"),
                        "USD" to assetRow("500.00", "1.00", "500.00"),
                        "STRC" to assetRow("1.00", "0", "1000.00"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(3600),
                    "2050.00",
                    mapOf(
                        "BTC" to assetRow("1.00", "500.00", "500.00"),
                        "USD" to assetRow("550.00", "1.00", "550.00"),
                        "STRC" to assetRow("1.00", "0", "1000.00"),
                    ),
                ),
            )
            data class SourceEvidenceCase(
                val dividendRefid: String,
                val sourceRefid: String,
                val sourceBalance: String?,
            )
            val invalidEvidence = listOf(
                SourceEvidenceCase("actual-security-dividend", "different-security-event", "1.00"),
                SourceEvidenceCase("   ", "   ", "1.00"),
                SourceEvidenceCase("actual-security-dividend", "actual-security-dividend", null),
            )

            for (evidence in invalidEvidence) {
                val unrelatedSecurityLedger = ledgerEvent(
                    timestamp = dividendAt,
                    asset = "STRC",
                    amount = "0.00",
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    balance = evidence.sourceBalance,
                    ledgerId = "unrelated-tokenized-source",
                    refid = evidence.sourceRefid,
                )
                val cashDividend = ledgerEvent(
                    timestamp = dividendAt,
                    asset = "USD",
                    amount = "50.00",
                    type = KrakenApiConstants.LEDGER_TYPE_DIVIDEND,
                    balance = "550.00",
                    ledgerId = "usd-dividend-with-unrelated-tokenized-source",
                    refid = evidence.dividendRefid,
                )

                val result = calculate(
                    snapshots,
                    rewards = listOf(cashDividend),
                    ledgerContext = listOf(unrelatedSecurityLedger),
                    assetMetadata = tokenizedStrcAssetMetadata(),
                    priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal("500.00"))),
                )

                result.availability shouldBe ComparisonAvailability.UNAVAILABLE
                result.unavailableReason shouldBe ComparisonUnavailableReason.AMBIGUOUS_LEDGER_TYPE
                result.unavailableAt shouldBe dividendAt
            }
        }

        "source-proven zero-net USD dividend with nonzero balance delta fails closed" {
            val dividendAt = now.plusSeconds(1800)
            val snapshots = listOf(
                snapshot(now, "100.00", mapOf("USD" to assetRow("100.00", "1.00", "100.00"))),
                snapshot(
                    now.plusSeconds(3600),
                    "99.99995",
                    mapOf("USD" to assetRow("99.99995", "1.00", "99.99995")),
                ),
            )
            val dividend = ledgerEvent(
                timestamp = dividendAt,
                asset = "USD",
                amount = "0.0001",
                fee = "0.0001",
                type = KrakenApiConstants.LEDGER_TYPE_DIVIDEND,
                subtype = "cashdividend",
                balance = "99.99995",
                hasAuthoritativeFee = true,
            )

            val result = calculate(snapshots, rewards = listOf(dividend))

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.AMBIGUOUS_LEDGER_TYPE
            result.unavailableAt shouldBe dividendAt
        }

        "zero-amount source-proven USD dividend with material balance delta fails closed" {
            val dividendAt = now.plusSeconds(1800)
            val snapshots = listOf(
                snapshot(now, "100.00", mapOf("USD" to assetRow("100.00", "1.00", "100.00"))),
                snapshot(
                    now.plusSeconds(3600),
                    "99.99995",
                    mapOf("USD" to assetRow("99.99995", "1.00", "99.99995")),
                ),
            )
            val dividend = ledgerEvent(
                timestamp = dividendAt,
                asset = "USD",
                amount = "0",
                fee = "0",
                type = KrakenApiConstants.LEDGER_TYPE_DIVIDEND,
                subtype = "cashdividend",
                balance = "99.99995",
                hasAuthoritativeFee = true,
            )

            val result = calculate(snapshots, rewards = listOf(dividend))

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.AMBIGUOUS_LEDGER_TYPE
            result.unavailableAt shouldBe dividendAt
        }

        "source-proven zero-net USD dividend without a balance movement is a no-op" {
            val dividendAt = now.plusSeconds(1800)
            val snapshots = listOf(
                snapshot(now, "1000.00", mapOf("USD" to assetRow("1000.00", "1.00", "1000.00"))),
                snapshot(
                    now.plusSeconds(3600),
                    "1000.00",
                    mapOf("USD" to assetRow("1000.00", "1.00", "1000.00")),
                ),
            )
            val dividend = ledgerEvent(
                timestamp = dividendAt,
                asset = "USD",
                amount = "0.01",
                fee = "0.01",
                type = KrakenApiConstants.LEDGER_TYPE_DIVIDEND,
                subtype = "cashdividend",
                balance = "1000.00",
                hasAuthoritativeFee = true,
            )

            val result = calculate(snapshots, rewards = listOf(dividend))

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points.last().rebalancerValueUSD.shouldBeEqualComparingTo(BigDecimal("1000.00"))
            result.points.last().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("1000.00"))
            result.points.last().differenceUSD.shouldBeEqualComparingTo(BigDecimal.ZERO)
        }

        "material wallet asset metadata must be present and unambiguous" {
            val snapshots = listOf(
                snapshot(
                    now,
                    "1000.00",
                    mapOf("BTC" to assetRow("1.00", "500.00", "500.00"), "USD" to assetRow("500.00", "1.00", "500.00")),
                ),
                snapshot(
                    now.plusSeconds(3600),
                    "1000.00",
                    mapOf("BTC" to assetRow("1.00", "500.00", "500.00"), "USD" to assetRow("500.00", "1.00", "500.00")),
                ),
            )
            val incomplete = testAssetMetadata.filterNot {
                Asset.normalizeLedgerAsset(it.assetId).equals("BTC", ignoreCase = true)
            }
            val conflicting = testAssetMetadata + KrakenAssetMetadata("XBT", "tokenized_asset")
            val unknownClass = incomplete + KrakenAssetMetadata("BTC", "security")

            for (metadata in listOf(incomplete, conflicting, unknownClass)) {
                val result = calculate(snapshots, assetMetadata = metadata)
                result.availability shouldBe ComparisonAvailability.UNAVAILABLE
                result.unavailableReason shouldBe ComparisonUnavailableReason.ASSET_UNIVERSE_CHANGED
            }
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
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "legacy boundary ledger beside an observed previous snapshot does not disturb reconciliation" {
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
                        balancesObservedAt = now,
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
                    ledgerEvent(
                        timestamp = now.plusSeconds(1),
                        asset = "BTC",
                        amount = "0.1",
                        type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                        balance = "999.0",
                        ledgerId = "legacy-observed-mismatched-btc",
                    ),
                ),
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal("50000.00"))),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "stale pre-window authority matching previous balance is not re-proposed" {
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
                    ledgerEvent(
                        timestamp = now.minusSeconds(86400),
                        asset = "BTC",
                        amount = "1.0",
                        type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                        balance = "1.0",
                        ledgerId = "legacy-stale-embodied-btc",
                    ),
                ),
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal("50000.00"))),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "causal in-span deposit wins over identical out-of-span twin" {
            val current = now.plusSeconds(3600)
            val result = calculate(
                snapshots = listOf(
                    snapshot(
                        now,
                        "100000.00",
                        mapOf(
                            "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                            "USD" to assetRow("50000.00", "1.0", "50000.00"),
                        ),
                    ),
                    snapshot(
                        current,
                        "100100.00",
                        mapOf(
                            "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                            "USD" to assetRow("50100.00", "1.0", "50100.00"),
                        ),
                    ),
                ),
                rewards = listOf(
                    ledgerEvent(
                        timestamp = now.minusMillis(500),
                        asset = "USD",
                        amount = "100.00",
                        type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                        ledgerId = "twin-out",
                    ),
                    ledgerEvent(
                        timestamp = now.plusMillis(500),
                        asset = "USD",
                        amount = "100.00",
                        type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                        ledgerId = "twin-in",
                    ),
                ),
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal("50000.00"))),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "minimal single-event explanation wins over fragmented twin legs" {
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
                        "100200.00",
                        mapOf(
                            "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                            "USD" to assetRow("50200.00", "1.0", "50200.00"),
                        ),
                    ),
                ),
                rewards = listOf(
                    ledgerEvent(
                        timestamp = now.plusMillis(500),
                        asset = "USD",
                        amount = "200.00",
                        type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                        ledgerId = "single-200",
                    ),
                    ledgerEvent(
                        timestamp = now.plusMillis(600),
                        asset = "USD",
                        amount = "60.00",
                        type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                        ledgerId = "frag-60",
                    ),
                    ledgerEvent(
                        timestamp = now.plusMillis(700),
                        asset = "USD",
                        amount = "140.00",
                        type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                        ledgerId = "frag-140",
                    ),
                ),
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal("50000.00"))),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "identical twin deposits with no causal distinction stay fail-closed" {
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
                            "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                            "USD" to assetRow("50100.00", "1.0", "50100.00"),
                        ),
                    ),
                ),
                rewards = listOf(
                    ledgerEvent(
                        timestamp = now.plusMillis(500),
                        asset = "USD",
                        amount = "100.00",
                        type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                        ledgerId = "twin-a",
                    ),
                    ledgerEvent(
                        timestamp = now.plusMillis(600),
                        asset = "USD",
                        amount = "100.00",
                        type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                        ledgerId = "twin-b",
                    ),
                ),
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal("50000.00"))),
            )

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE
            result.unavailableAt shouldBe current
        }

        "sub-skew first-interval deposit is absorbed into the opening state" {
            val current = now.plusMillis(500)
            val result = calculate(
                snapshots = listOf(
                    snapshot(
                        now,
                        "100000.00",
                        mapOf(
                            "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                            "USD" to assetRow("50000.00", "1.0", "50000.00"),
                        ),
                        balancesObservedAt = now,
                    ),
                    snapshot(
                        current,
                        "100000.00",
                        mapOf(
                            "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                            "USD" to assetRow("50000.00", "1.0", "50000.00"),
                        ),
                    ),
                ),
                trades = emptyList(),
                rewards = listOf(
                    ledgerEvent(
                        timestamp = now.plusMillis(250),
                        asset = "BTC",
                        amount = "0.1",
                        type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                        ledgerId = "legacy-subskew-deposit-btc",
                    ),
                ),
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal("50000.00"))),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "first-burst contribution beside a single stale mismatch reconciles under observed previous snapshot" {
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
                        balancesObservedAt = now,
                    ),
                    snapshot(
                        current,
                        "100200.00",
                        mapOf(
                            "BTC" to assetRow("1.1", "50000.00", "55000.00"),
                            "USD" to assetRow("45200.00", "1.0", "45200.00"),
                        ),
                        balancesObservedAt = null,
                    ),
                ),
                trades = listOf(
                    manualTrade(
                        timestamp = now.plusSeconds(1),
                        side = "buy",
                        symbol = "BTC",
                        volume = "0.1",
                        usdAmount = "5000.00",
                    ),
                ),
                rewards = listOf(
                    ledgerEvent(
                        timestamp = now.plusMillis(1100),
                        asset = "USD",
                        amount = "100.00",
                        type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                        ledgerId = "first-burst-deposit-late-usd",
                    ),
                    ledgerEvent(
                        timestamp = now.plusMillis(500),
                        asset = "USD",
                        amount = "100.00",
                        type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                        ledgerId = "first-burst-deposit-usd",
                    ),
                    ledgerEvent(
                        timestamp = now.minusMillis(500),
                        asset = "BTC",
                        amount = "0.1",
                        type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                        balance = "999.0",
                        ledgerId = "legacy-observed-stale-mismatched-btc",
                    ),
                ),
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal("50000.00"))),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "corroborated pre-baseline contradiction fails closed" {
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
                        balancesObservedAt = now,
                    ),
                    snapshot(
                        current,
                        "100200.00",
                        mapOf(
                            "BTC" to assetRow("1.1", "50000.00", "55000.00"),
                            "USD" to assetRow("45200.00", "1.0", "45200.00"),
                        ),
                        balancesObservedAt = null,
                    ),
                ),
                trades = listOf(
                    manualTrade(
                        timestamp = now.plusSeconds(1),
                        side = "buy",
                        symbol = "BTC",
                        volume = "0.1",
                        usdAmount = "5000.00",
                    ),
                ),
                rewards = listOf(
                    ledgerEvent(
                        timestamp = now.plusMillis(1100),
                        asset = "USD",
                        amount = "100.00",
                        type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                        ledgerId = "first-burst-deposit-late-usd",
                    ),
                    ledgerEvent(
                        timestamp = now.plusMillis(500),
                        asset = "USD",
                        amount = "100.00",
                        type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                        ledgerId = "first-burst-deposit-usd",
                    ),
                    ledgerEvent(
                        timestamp = now.minusMillis(500),
                        asset = "BTC",
                        amount = "0.1",
                        type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                        balance = "999.0",
                        ledgerId = "legacy-observed-stale-mismatched-btc",
                    ),
                    ledgerEvent(
                        timestamp = now.minusSeconds(86400),
                        asset = "BTC",
                        amount = "0.1",
                        type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                        balance = "999.0",
                        ledgerId = "legacy-observed-ancient-mismatched-btc",
                    ),
                ),
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal("50000.00"))),
            )

            // Two independent pre-baseline authorities corroborate against the recorded
            // baseline, so the opening balance cannot be trusted: fail closed.
            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE
        }

        "same-instant contradictory authoritative ledgers fail closed" {
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
                        balancesObservedAt = now,
                    ),
                    snapshot(
                        current,
                        "100000.00",
                        mapOf(
                            "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                            "USD" to assetRow("50000.00", "1.0", "50000.00"),
                        ),
                        balancesObservedAt = null,
                    ),
                ),
                trades = emptyList(),
                rewards = listOf(
                    ledgerEvent(
                        timestamp = now.minusMillis(500),
                        asset = "BTC",
                        amount = "0.1",
                        type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                        balance = "1.0",
                        ledgerId = "contradiction-a-btc",
                    ),
                    ledgerEvent(
                        timestamp = now.minusMillis(500),
                        asset = "BTC",
                        amount = "0.1",
                        type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                        balance = "999.0",
                        ledgerId = "contradiction-b-btc",
                    ),
                ),
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal("50000.00"))),
            )

            // Two same-instant authorities disagree, so no reading can establish the true
            // balance: the balance change is unexplained, not merely ambiguous between owners.
            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE
        }

        "ancient non-matching authority beside unobserved snapshots does not disturb reconciliation" {
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
                        "100000.00",
                        mapOf(
                            "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                            "USD" to assetRow("50000.00", "1.0", "50000.00"),
                        ),
                        balancesObservedAt = null,
                    ),
                ),
                trades = emptyList(),
                rewards = listOf(
                    ledgerEvent(
                        timestamp = now.minusSeconds(86400),
                        asset = "BTC",
                        amount = "0.1",
                        type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                        balance = "999.0",
                        ledgerId = "legacy-null-ancient-mismatched-btc",
                    ),
                ),
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal("50000.00"))),
            )

            // A day-old authority far outside every observation window cannot adjudicate
            // the null-observation interval, and nothing corroborates it: tolerated.
            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "legacy boundary ledger with fully unobserved snapshots is still reconciled once" {
            val current = now.plusSeconds(3600)
            val tradeTime = now.plusSeconds(1800)
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
                        balancesObservedAt = null,
                    ),
                ),
                trades = listOf(
                    manualTrade(
                        timestamp = tradeTime,
                        side = "buy",
                        symbol = "BTC",
                        volume = "0.1",
                        usdAmount = "5000.00",
                    ),
                ),
                rewards = listOf(
                    ledgerEvent(
                        timestamp = tradeTime.plusMillis(100),
                        asset = "USD",
                        amount = "100.00",
                        type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    ),
                    ledgerEvent(
                        timestamp = now.plusSeconds(1),
                        asset = "BTC",
                        amount = "0.1",
                        type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                        balance = "999.0",
                        ledgerId = "legacy-unobserved-mismatched-btc",
                    ),
                ),
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal("50000.00"))),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "post-window ambiguous-provenance deposit still fails closed" {
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
                    ledgerEvent(
                        timestamp = current.plusMillis(700),
                        asset = "USD",
                        amount = "50.00",
                        type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                        subtype = "staking",
                        ledgerId = "legacy-post-window-ambiguous-usd",
                    ),
                ),
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal("50000.00"))),
            )

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.AMBIGUOUS_LEDGER_TYPE
            result.unavailableAt shouldBe current.plusMillis(700)
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

        "trade for an asset absent from the snapshot series fails closed" {
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

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE
            result.unavailableAt shouldBe now.plusSeconds(3600)
        }

        "out-of-universe round trip that nets to zero is not fatal" {
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
                trade(now.plusSeconds(900), "BUY", "XLM", "100.0", "105.00"),
                trade(now.plusSeconds(1800), "SELL", "XLM", "100.0", "105.00"),
            )

            val result = calculate(snapshots, trades, emptyList())

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100000.00")
        }

        // --- SECTION 15 REGRESSION & DOMAIN SCENARIO SUITE ---

        "reward classifiers separate holding-dependent credits from account-level credits" {
            fun event(type: String, subtype: String? = null) =
                ledgerEvent(now, "BTC", "1", type = type, subtype = subtype)

            RebalancerComparisonCalculator.isHoldingDependentReward(
                event(KrakenApiConstants.LEDGER_TYPE_DIVIDEND),
            ) shouldBe true
            RebalancerComparisonCalculator.isHoldingDependentReward(
                event(KrakenApiConstants.LEDGER_TYPE_STAKING),
            ) shouldBe true
            RebalancerComparisonCalculator.isHoldingDependentReward(
                event(KrakenApiConstants.LEDGER_TYPE_EARN),
            ) shouldBe true
            RebalancerComparisonCalculator.isHoldingDependentReward(
                event(KrakenApiConstants.LEDGER_TYPE_REWARD, "equityfpsl"),
            ) shouldBe true
            RebalancerComparisonCalculator.isHoldingDependentReward(
                event(KrakenApiConstants.LEDGER_TYPE_ADJUSTMENT, "cashdividend"),
            ) shouldBe true
            RebalancerComparisonCalculator.isHoldingDependentReward(
                event(KrakenApiConstants.LEDGER_TYPE_REWARD),
            ) shouldBe true
            RebalancerComparisonCalculator.isHoldingDependentReward(
                event(KrakenApiConstants.LEDGER_TYPE_REWARD, "welcomebonus"),
            ) shouldBe false
            RebalancerComparisonCalculator.isHoldingDependentReward(
                event(KrakenApiConstants.LEDGER_TYPE_TRANSFER, "airdrop"),
            ) shouldBe false
            RebalancerComparisonCalculator.isHoldingDependentReward(
                event(KrakenApiConstants.LEDGER_TYPE_DEPOSIT),
            ) shouldBe false

            RebalancerComparisonCalculator.isAccountLevelIndependentCredit(
                event(KrakenApiConstants.LEDGER_TYPE_REWARD, "welcomebonus"),
            ) shouldBe true
            RebalancerComparisonCalculator.isAccountLevelIndependentCredit(
                event(KrakenApiConstants.LEDGER_TYPE_TRANSFER, "airdrop"),
            ) shouldBe true
            RebalancerComparisonCalculator.isAccountLevelIndependentCredit(
                event(KrakenApiConstants.LEDGER_TYPE_TRANSFER, "reward"),
            ) shouldBe true
            RebalancerComparisonCalculator.isAccountLevelIndependentCredit(
                event(KrakenApiConstants.LEDGER_TYPE_REWARD),
            ) shouldBe false
            RebalancerComparisonCalculator.isAccountLevelIndependentCredit(
                event(KrakenApiConstants.LEDGER_TYPE_DIVIDEND, "cashdividend"),
            ) shouldBe false
            RebalancerComparisonCalculator.isAccountLevelIndependentCredit(
                event(KrakenApiConstants.LEDGER_TYPE_TRANSFER),
            ) shouldBe false
        }

        "attributed movement scales every leg to the smallest held fraction" {
            val balances = mutableMapOf("BTC" to BigDecimal("0.5"), "USD" to BigDecimal("100.00"))

            RebalancerComparisonCalculator.applyAttributedMovement(
                balances,
                mapOf("BTC" to BigDecimal("-1.0"), "USD" to BigDecimal("100.00")),
            )

            balances.getValue("BTC") shouldBeEqualComparingTo BigDecimal.ZERO
            balances.getValue("USD") shouldBeEqualComparingTo BigDecimal("150.00")
        }

        "attributed movement is skipped entirely when a drawn asset is not held" {
            val balances = mutableMapOf("BTC" to BigDecimal.ZERO, "USD" to BigDecimal("100.00"))

            RebalancerComparisonCalculator.applyAttributedMovement(
                balances,
                mapOf("BTC" to BigDecimal("-1.0"), "USD" to BigDecimal("100.00")),
            )

            balances.getValue("BTC") shouldBeEqualComparingTo BigDecimal.ZERO
            balances.getValue("USD") shouldBeEqualComparingTo BigDecimal("100.00")
        }

        "attributed movement applies full deltas once a new asset enters the basket" {
            val balances = mutableMapOf("BTC" to BigDecimal("2.0"))

            RebalancerComparisonCalculator.applyAttributedMovement(
                balances,
                mapOf("BTC" to BigDecimal("-1.0"), "USD" to BigDecimal("10.00")),
            )

            balances.getValue("BTC") shouldBeEqualComparingTo BigDecimal("1.0")
            balances.getValue("USD") shouldBeEqualComparingTo BigDecimal("10.00")
        }

        "equity-labelled USD rewards become neutral anchor-weighted contributions" {
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

            val result = calculate(
                snapshots,
                emptyList(),
                listOf(
                    ledgerEvent(
                        now.plusSeconds(1800),
                        "USD",
                        "25.00",
                        type = KrakenApiConstants.LEDGER_TYPE_REWARD,
                        subtype = "equityfpsl",
                    ),
                ),
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal("50000.00"))),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100025.00")
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "cash-dividend labelled USD rewards become neutral anchor-weighted contributions" {
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

            val result = calculate(
                snapshots,
                emptyList(),
                listOf(
                    ledgerEvent(
                        now.plusSeconds(1800),
                        "USD",
                        "25.00",
                        type = KrakenApiConstants.LEDGER_TYPE_REWARD,
                        subtype = "cashdividend",
                    ),
                ),
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal("50000.00"))),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100025.00")
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "negative mid-series snapshot values fail the whole window" {
            val baseline = snapshot(
                now,
                "1000.00",
                mapOf(
                    "BTC" to assetRow("1", "500", "500"),
                    "USD" to assetRow("500", "1", "500"),
                ),
            )
            val negativeTotal = snapshot(
                now.plusSeconds(60),
                "-1.00",
                mapOf(
                    "BTC" to assetRow("1", "500", "500"),
                    "USD" to assetRow("-501", "1", "-501"),
                ),
            )
            val negativeValue = snapshot(
                now.plusSeconds(90),
                "1000.00",
                mapOf(
                    "BTC" to assetRow("1", "500", "500"),
                    "USD" to assetRow("500", "1", "500"),
                    "XRP" to assetRow("10", "2", "-20"),
                ),
            )
            val later = snapshot(
                now.plusSeconds(120),
                "1000.00",
                mapOf(
                    "BTC" to assetRow("1", "500", "500"),
                    "USD" to assetRow("500", "1", "500"),
                ),
            )

            val totalFailure = calculate(listOf(baseline, negativeTotal, later))
            totalFailure.availability shouldBe ComparisonAvailability.UNAVAILABLE
            totalFailure.unavailableReason shouldBe ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE

            val valueFailure = calculate(listOf(baseline, negativeValue, later))
            valueFailure.availability shouldBe ComparisonAvailability.UNAVAILABLE
            valueFailure.unavailableReason shouldBe ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE
        }

        "negative baseline value fails closed" {
            val baseline = snapshot(
                now,
                "1000.00",
                mapOf(
                    "BTC" to assetRow("1", "500", "500"),
                    "USD" to assetRow("500", "1", "-20"),
                ),
            )
            val later = snapshot(
                now.plusSeconds(60),
                "1000.00",
                mapOf(
                    "BTC" to assetRow("1", "500", "500"),
                    "USD" to assetRow("500", "1", "500"),
                ),
            )

            val result = calculate(listOf(baseline, later))

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE
        }

        "negative stored price fails with a missing-price reason" {
            val baseline = snapshot(
                now,
                "1000.00",
                mapOf(
                    "BTC" to assetRow("1", "500", "500"),
                    "USD" to assetRow("500", "1", "500"),
                ),
            )
            val negativePrice = snapshot(
                now.plusSeconds(60),
                "1000.00",
                mapOf(
                    "BTC" to assetRow("1", "-1", "500"),
                    "USD" to assetRow("500", "1", "500"),
                ),
            )

            val result = calculate(listOf(baseline, negativePrice))

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.MISSING_PRICE
            result.unavailableAt shouldBe now.plusSeconds(60)
        }

        "stored price gaps fall back to the price provider" {
            val baseline = snapshot(
                now,
                "1000.00",
                mapOf(
                    "BTC" to assetRow("1", "500", "500"),
                    "USD" to assetRow("500", "1", "500"),
                ),
            )
            val unpriced = snapshot(
                now.plusSeconds(60),
                "1000.00",
                mapOf(
                    "BTC" to assetRow("1", "0", "0"),
                    "USD" to assetRow("500", "1", "500"),
                ),
            )
            val snapshots = listOf(baseline, unpriced)

            val withProvider = calculate(
                snapshots,
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal("600"))),
            )
            withProvider.availability shouldBe ComparisonAvailability.AVAILABLE
            withProvider.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("1100.00")

            val withoutProvider = calculate(snapshots, priceProvider = mapPriceProvider(emptyMap()))
            withoutProvider.availability shouldBe ComparisonAvailability.UNAVAILABLE
            withoutProvider.unavailableReason shouldBe ComparisonUnavailableReason.MISSING_PRICE
        }

        "non-USD owner contribution is valued through the historical price provider" {
            val baseline = snapshot(
                now,
                "1000.00",
                mapOf(
                    "BTC" to assetRow("1", "500", "500"),
                    "USD" to assetRow("500", "1", "500"),
                ),
            )
            val afterContribution = snapshot(
                now.plusSeconds(60),
                "1500.00",
                mapOf(
                    "BTC" to assetRow("2", "500", "1000"),
                    "USD" to assetRow("500", "1", "500"),
                ),
            )
            val contribution = ledgerEvent(
                now.plusSeconds(30),
                "BTC",
                "1",
                type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
            )

            val result = calculate(
                listOf(baseline, afterContribution),
                rewards = listOf(contribution),
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal("500"))),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "zero-balance anchor rows receive no benchmark weight" {
            val baseline = snapshot(
                now,
                "1010.00",
                mapOf(
                    "BTC" to assetRow("1.00000000", "500.00", "500.00"),
                    "USD" to assetRow("500.00", "1.00", "500.00"),
                    "XRP" to assetRow("5.00000000", "2.00", "10.00"),
                    "SOL" to assetRow("0", "3.00", "0"),
                ),
            )
            val afterContribution = snapshot(
                now.plusSeconds(3600),
                "1110.00",
                mapOf(
                    "BTC" to assetRow("1.00000000", "500.00", "500.00"),
                    "USD" to assetRow("600.00", "1.00", "600.00"),
                    "XRP" to assetRow("5.00000000", "2.00", "10.00"),
                    "SOL" to assetRow("0", "3.00", "0"),
                ),
            )

            val result = calculate(
                listOf(baseline, afterContribution),
                rewards = listOf(
                    ledgerEvent(
                        now.plusSeconds(1800),
                        "USD",
                        "100.00",
                        type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                        refid = "ACH-ZERO-WEIGHT",
                    ),
                ),
                priceProvider = mapPriceProvider(
                    mapOf(
                        "BTC" to BigDecimal("500"),
                        "XRP" to BigDecimal("2"),
                    ),
                ),
                provenanceResolver = SimpleFundingProvenanceResolver(
                    deposits = listOf(
                        DepositStatusRecord(
                            refid = "ACH-ZERO-WEIGHT",
                            asset = "USD",
                            amount = BigDecimal("100.00"),
                            time = now.plusSeconds(1800),
                            status = "Success",
                            method = "ACH",
                        ),
                    ),
                ),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("1110.00")
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "a zero provider price still fails a stored-price gap closed" {
            val baseline = snapshot(
                now,
                "1000.00",
                mapOf(
                    "BTC" to assetRow("1.00000000", "500.00", "500.00"),
                    "USD" to assetRow("500.00", "1.00", "500.00"),
                ),
            )
            val stalePrice = snapshot(
                now.plusSeconds(3600),
                "500.00",
                mapOf(
                    "BTC" to assetRow("1.00000000", "0", "0"),
                    "USD" to assetRow("500.00", "1.00", "500.00"),
                ),
            )

            val result = calculate(
                listOf(baseline, stalePrice),
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal.ZERO)),
            )

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.MISSING_PRICE
        }

        "a non-USD contribution without a historical price provider fails closed" {
            val baseline = snapshot(
                now,
                "1000.00",
                mapOf(
                    "BTC" to assetRow("1.00000000", "500.00", "500.00"),
                    "USD" to assetRow("500.00", "1.00", "500.00"),
                ),
            )
            val afterContribution = snapshot(
                now.plusSeconds(3600),
                "1500.00",
                mapOf(
                    "BTC" to assetRow("2.00000000", "500.00", "1000.00"),
                    "USD" to assetRow("500.00", "1.00", "500.00"),
                ),
            )

            val result = calculate(
                listOf(baseline, afterContribution),
                rewards = listOf(
                    ledgerEvent(
                        now.plusSeconds(1800),
                        "BTC",
                        "1.00000000",
                        type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    ),
                ),
            )

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.MISSING_PRICE
        }

        "a non-reward external credit stays in-kind for a held asset" {
            val baseline = snapshot(
                now,
                "1000.00",
                mapOf(
                    "BTC" to assetRow("1.00000000", "500.00", "500.00"),
                    "USD" to assetRow("500.00", "1.00", "500.00"),
                ),
            )
            val afterCredit = snapshot(
                now.plusSeconds(3600),
                "1030.00",
                mapOf(
                    "BTC" to assetRow("1.06000000", "500.00", "530.00"),
                    "USD" to assetRow("500.00", "1.00", "500.00"),
                ),
            )

            val result = calculate(
                listOf(baseline, afterCredit),
                rewards = listOf(
                    ledgerEvent(now.plusSeconds(1800), "BTC", "0.06000000", type = KrakenApiConstants.LEDGER_TYPE_SALE),
                ),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("1030.00")
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "a negative external adjustment reduces the passive basket" {
            val baseline = snapshot(
                now,
                "1000.00",
                mapOf(
                    "BTC" to assetRow("1.00000000", "500.00", "500.00"),
                    "USD" to assetRow("500.00", "1.00", "500.00"),
                ),
            )
            val afterAdjustment = snapshot(
                now.plusSeconds(3600),
                "970.00",
                mapOf(
                    "BTC" to assetRow("0.94000000", "500.00", "470.00"),
                    "USD" to assetRow("500.00", "1.00", "500.00"),
                ),
            )

            val result = calculate(
                listOf(baseline, afterAdjustment),
                rewards = listOf(
                    ledgerEvent(
                        now.plusSeconds(1800),
                        "BTC",
                        "-0.06000000",
                        type = KrakenApiConstants.LEDGER_TYPE_ADJUSTMENT,
                    ),
                ),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("970.00")
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "a zero-amount external ledger row leaves the passive basket unchanged" {
            val baseline = snapshot(
                now,
                "1000.00",
                mapOf("BTC" to assetRow("1", "500", "500"), "USD" to assetRow("500", "1", "500")),
            )
            val later = snapshot(
                now.plusSeconds(3600),
                "1000.00",
                mapOf("BTC" to assetRow("1", "500", "500"), "USD" to assetRow("500", "1", "500")),
            )
            val result = calculate(
                listOf(baseline, later),
                rewards = listOf(
                    ledgerEvent(
                        now.plusSeconds(1800),
                        "BTC",
                        "0.00000000",
                        type = KrakenApiConstants.LEDGER_TYPE_SALE,
                    ),
                ),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("1000.00")
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "a crypto dividend-labelled adjustment stays held-asset dependent" {
            val baseline = snapshot(
                now,
                "1000.00",
                mapOf("BTC" to assetRow("1", "500", "500"), "USD" to assetRow("500", "1", "500")),
            )
            val later = snapshot(
                now.plusSeconds(3600),
                "1030.00",
                mapOf("BTC" to assetRow("1.06", "500", "530"), "USD" to assetRow("500", "1", "500")),
            )
            val result = calculate(
                listOf(baseline, later),
                rewards = listOf(
                    ledgerEvent(
                        now.plusSeconds(1800),
                        "BTC",
                        "0.06000000",
                        type = KrakenApiConstants.LEDGER_TYPE_ADJUSTMENT,
                        subtype = "cashdividend",
                    ),
                ),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("1030.00")
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "a credited asset without a historical price fails closed" {
            val baseline = snapshot(
                now,
                "100.00",
                mapOf("BTC" to assetRow("1.00", "100.00", "100.00"), "ETH" to assetRow("0.00", "100.00", "0.00")),
            )
            val later = snapshot(
                now.plusSeconds(3600),
                "110.00",
                mapOf("BTC" to assetRow("1.00", "100.00", "100.00"), "ETH" to assetRow("0.10", "0", "0")),
            )
            val unpriced = calculate(
                listOf(baseline, later),
                rewards = listOf(
                    ledgerEvent(
                        now.plusSeconds(1800),
                        "ETH",
                        "0.10",
                        type = KrakenApiConstants.LEDGER_TYPE_REWARD,
                        subtype = "welcomebonus",
                    ),
                ),
                priceProvider = mapPriceProvider(emptyMap()),
            )
            val priced = calculate(
                listOf(baseline, later),
                rewards = listOf(
                    ledgerEvent(
                        now.plusSeconds(1800),
                        "ETH",
                        "0.10",
                        type = KrakenApiConstants.LEDGER_TYPE_REWARD,
                        subtype = "welcomebonus",
                    ),
                ),
                priceProvider = mapPriceProvider(mapOf("ETH" to BigDecimal("100.00"))),
            )

            unpriced.availability shouldBe ComparisonAvailability.UNAVAILABLE
            unpriced.unavailableReason shouldBe ComparisonUnavailableReason.MISSING_PRICE
            unpriced.unavailableAt shouldBe later.timestamp
            priced.availability shouldBe ComparisonAvailability.AVAILABLE
            priced.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("110.00")
            priced.points.last().rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("110.00")
            priced.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "a mid-series target change cannot reweight the fixed anchor contribution" {
            fun configured(
                btcTargetPercent: String,
                timestamp: Instant,
                btcBalance: String,
                btcPrice: String,
                usdBalance: String,
                total: String,
            ): PortfolioSnapshot = PortfolioSnapshot(
                timestamp = timestamp,
                totalValueUSD = BigDecimal(total),
                assets = mapOf(
                    "BTC" to assetSnapshot(
                        symbol = "BTC",
                        balance = BigDecimal(btcBalance),
                        price = BigDecimal(btcPrice),
                        valueUSD = BigDecimal(btcBalance).multiply(BigDecimal(btcPrice)),
                        targetPercent = BigDecimal(btcTargetPercent),
                    ),
                    "USD" to assetSnapshot(
                        symbol = "USD",
                        balance = BigDecimal(usdBalance),
                        price = BigDecimal.ONE,
                        valueUSD = BigDecimal(usdBalance),
                        targetPercent = BigDecimal("100.0").subtract(BigDecimal(btcTargetPercent)),
                    ),
                ),
                actions = emptyList(),
                drawdownPercent = BigDecimal.ZERO,
                fiatDeploymentPercent = BigDecimal.ZERO,
                effectiveUsdTargetPercent = BigDecimal.ZERO,
                balancesObservedAt = timestamp,
            )

            val result = calculate(
                snapshots = listOf(
                    configured("20.0", now, "1.00000000", "500.00", "500.00", "1000.00"),
                    configured("90.0", now.plusSeconds(3600), "1.00000000", "600.00", "600.00", "1200.00"),
                ),
                rewards = listOf(
                    ledgerEvent(
                        now.plusSeconds(1800),
                        "USD",
                        "100.00",
                        type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                        ledgerId = "dep-fixed-weight",
                        refid = "ACH-FIXED-WEIGHT",
                    ),
                ),
                provenanceResolver = SimpleFundingProvenanceResolver(
                    deposits = listOf(
                        DepositStatusRecord(
                            refid = "ACH-FIXED-WEIGHT",
                            asset = "USD",
                            amount = BigDecimal("100.00"),
                            time = now.plusSeconds(1800),
                            status = "Success",
                            method = "ACH",
                        ),
                    ),
                ),
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal("500.00"))),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("1210.00")
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal("-10.00")
        }

        "Scenario A: source-proven USD cash dividend is neutral in the crypto-cash benchmark" {
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
                    subtype = "cashdividend",
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
            result.points.size shouldBe 2
            result.points[1].rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("100025.00")
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100025.00")
            result.points[1].differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
            result.latestDifferenceUSD!! shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "Scenario B: source-proven cash dividend allocates its exact net actual delta" {
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
                    subtype = "cashdividend",
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
            result.points[1].rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("100024.90")
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100024.90")
            result.points[1].differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
            result.latestDifferenceUSD!! shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "Scenario C: Zero-baseline reward in a newly credited asset remains actual-only" {
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
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100000.00")
            result.points[1].differenceUSD shouldBeEqualComparingTo BigDecimal("6000.00")
            result.latestDifferenceUSD!! shouldBeEqualComparingTo BigDecimal("6000.00")
        }

        "Scenario D: Manual authoritative BUY is reconciled but does not change pure Buy & Hold" {
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
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("110000.00")
            result.points[1].differenceUSD shouldBeEqualComparingTo BigDecimal("5000.00")
            result.latestDifferenceUSD!! shouldBeEqualComparingTo BigDecimal("5000.00")
        }

        "Scenario E: Manual authoritative SELL is reconciled but does not change pure Buy & Hold" {
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
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("120000.00")
            result.points[1].differenceUSD shouldBeEqualComparingTo BigDecimal("-10000.00")
            result.latestDifferenceUSD!! shouldBeEqualComparingTo BigDecimal("-10000.00")
        }

        "Scenario F: Manual trade fees are reconciled without creating synthetic trades" {
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
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("110000.00")
            result.points[1].differenceUSD shouldBeEqualComparingTo BigDecimal("4974.00")
            result.latestDifferenceUSD!! shouldBeEqualComparingTo BigDecimal("4974.00")
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

        "Scenario I: Mixed manual and bot trades are both ignored by pure Buy & Hold" {
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
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("110000.00")
            result.latestDifferenceUSD!! shouldBeEqualComparingTo BigDecimal("15000.00")
        }

        "Scenario J: Manual multi-fill order is reconciled without changing pure Buy & Hold" {
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
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("110000.00")
            result.latestDifferenceUSD!! shouldBeEqualComparingTo BigDecimal("5000.00")
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

        "Scenario L: UNKNOWN trade is reconciled without changing pure Buy & Hold" {
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

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("110000.00")
            result.latestDifferenceUSD!! shouldBeEqualComparingTo BigDecimal("5000.00")
        }

        "Scenario M: Genuine USD contribution is allocated by fixed anchor weights" {
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
                    refid = "ACH-SCENARIO-M",
                ),
            )

            val result = calculate(
                snapshots,
                emptyList(),
                ledgers,
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal("50000.00"))),
                provenanceResolver = SimpleFundingProvenanceResolver(
                    deposits = listOf(
                        DepositStatusRecord(
                            refid = "ACH-SCENARIO-M",
                            asset = "USD",
                            amount = BigDecimal("10000.00"),
                            time = now.plusSeconds(1800),
                            status = "Success",
                            method = "ACH",
                        ),
                    ),
                ),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points[1].rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("110000.00")
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("110000.00")
            result.latestDifferenceUSD!! shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "comparison uses the immutable prepared provenance resolver" {
            var prepareCalls = 0
            val preparedResolver = object : FundingProvenanceResolver {
                override fun resolve(event: LedgerEvent): FundingEvidence = FundingEvidence.EXTERNAL
            }
            val productionShapeResolver = object : FundingProvenanceResolver {
                override fun resolve(event: LedgerEvent): FundingEvidence = FundingEvidence.UNRESOLVED

                override suspend fun prepare(events: Collection<LedgerEvent>): FundingProvenanceResolver {
                    prepareCalls++
                    return preparedResolver
                }
            }
            val deposit = ledgerEvent(
                timestamp = now.plusSeconds(1800),
                asset = Asset.USD,
                amount = "100.00",
                type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                refid = "PREPARED-COMPARISON-DEPOSIT",
            )
            val result = calculate(
                snapshots = listOf(
                    snapshot(
                        now,
                        "1000.00",
                        assets = mapOf(
                            "BTC" to assetRow("0.0", "50000.00", "0.00"),
                            "USD" to assetRow("1000.00", "1.00", "1000.00"),
                        ),
                    ),
                    snapshot(
                        now.plusSeconds(3600),
                        "1100.00",
                        assets = mapOf(
                            "BTC" to assetRow("0.0", "50000.00", "0.00"),
                            "USD" to assetRow("1100.00", "1.00", "1100.00"),
                        ),
                    ),
                ),
                rewards = listOf(deposit),
                provenanceResolver = productionShapeResolver,
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points.last().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("1100.00"))
            prepareCalls shouldBe 1
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

        "Scenario O: Genuine crypto contribution is allocated by fixed anchor weights" {
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

        "pure benchmark preserves every positive anchor holding" {
            val t0 = Instant.parse("2026-06-01T12:00:00Z")
            val t1 = Instant.parse("2026-06-02T12:00:00Z")
            val inceptionBaseline = PortfolioSnapshot(
                timestamp = t0,
                totalValueUSD = BigDecimal("1200.00"),
                assets = mapOf(
                    "BTC" to assetSnapshot(
                        symbol = "BTC",
                        balance = BigDecimal("6.00000000"),
                        price = BigDecimal("100.00"),
                        valueUSD = BigDecimal("600.00"),
                        targetPercent = BigDecimal("20.0"),
                    ),
                    "ETH" to assetSnapshot(
                        symbol = "ETH",
                        balance = BigDecimal("4.00000000"),
                        price = BigDecimal("100.00"),
                        valueUSD = BigDecimal("400.00"),
                        targetPercent = BigDecimal("80.0"),
                    ),
                    "MORPHO" to assetSnapshot(
                        symbol = "MORPHO",
                        balance = BigDecimal("200.00000000"),
                        price = BigDecimal.ONE,
                        valueUSD = BigDecimal("200.00"),
                        targetPercent = BigDecimal.ZERO,
                    ),
                ),
                actions = emptyList(),
                drawdownPercent = BigDecimal.ZERO,
                fiatDeploymentPercent = BigDecimal.ZERO,
                effectiveUsdTargetPercent = BigDecimal.ZERO,
            )
            val later = inceptionBaseline.copy(
                timestamp = t1,
                totalValueUSD = BigDecimal("1800.00"),
                balancesObservedAt = t1,
                assets = inceptionBaseline.assets + mapOf(
                    "BTC" to inceptionBaseline.assets.getValue("BTC").copy(
                        price = BigDecimal("200.00"),
                        valueUSD = BigDecimal("1200.00"),
                        // A later target edit is current policy, not a historical rewrite.
                        targetPercent = BigDecimal("90.0"),
                    ),
                    "ETH" to inceptionBaseline.assets.getValue("ETH").copy(
                        targetPercent = BigDecimal("10.0"),
                    ),
                ),
            )

            val result = calculate(
                snapshots = listOf(inceptionBaseline, later),
                inceptionSnapshot = inceptionBaseline,
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points[0].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("1200.00")
            // Pure Buy & Hold preserves all anchor holdings: 6 BTC ($1200) + 4 ETH ($400) + 200 MORPHO ($200) = $1800.00.
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("1800.00")
            result.points[1].differenceUSD shouldBeEqualComparingTo BigDecimal("0.00")
        }

        "pure benchmark preserves all positive anchor holdings despite target edits" {
            val t0 = Instant.parse("2026-06-01T12:00:00Z")
            val t1 = Instant.parse("2026-06-02T12:00:00Z")
            val t2 = Instant.parse("2026-06-03T12:00:00Z")
            val inceptionBaseline = PortfolioSnapshot(
                timestamp = t0,
                totalValueUSD = BigDecimal("1000.00"),
                assets = mapOf(
                    "BTC" to assetSnapshot(
                        symbol = "BTC",
                        balance = BigDecimal("3.00000000"),
                        price = BigDecimal("100.00"),
                        valueUSD = BigDecimal("300.00"),
                        targetPercent = BigDecimal("60.0"),
                    ),
                    "ETH" to assetSnapshot(
                        symbol = "ETH",
                        balance = BigDecimal("20.00000000"),
                        price = BigDecimal("20.00"),
                        valueUSD = BigDecimal("400.00"),
                        targetPercent = BigDecimal("40.0"),
                    ),
                    "MORPHO" to assetSnapshot(
                        symbol = "MORPHO",
                        balance = BigDecimal("300.00000000"),
                        price = BigDecimal.ONE,
                        valueUSD = BigDecimal("300.00"),
                        targetPercent = BigDecimal.ZERO,
                    ),
                ),
                actions = emptyList(),
                drawdownPercent = BigDecimal.ZERO,
                fiatDeploymentPercent = BigDecimal.ZERO,
                effectiveUsdTargetPercent = BigDecimal.ZERO,
            )

            fun revalue(timestamp: Instant, total: String, btcPrice: String, morphoPrice: String): PortfolioSnapshot =
                inceptionBaseline.copy(
                    timestamp = timestamp,
                    totalValueUSD = BigDecimal(total),
                    balancesObservedAt = timestamp,
                    assets = inceptionBaseline.assets + mapOf(
                        "BTC" to inceptionBaseline.assets.getValue("BTC").copy(
                            price = BigDecimal(btcPrice),
                            valueUSD = BigDecimal("300.00") * BigDecimal(btcPrice) / BigDecimal("100.00"),
                        ),
                        "ETH" to inceptionBaseline.assets.getValue("ETH").copy(
                            price = BigDecimal("20.00"),
                            valueUSD = BigDecimal("400.00"),
                        ),
                        "MORPHO" to inceptionBaseline.assets.getValue("MORPHO").copy(
                            price = BigDecimal(morphoPrice),
                            valueUSD = BigDecimal("300.00") * BigDecimal(morphoPrice),
                        ),
                    ),
                )

            val result = calculate(
                snapshots = listOf(
                    revalue(t0, "1000.00", "100.00", "1.00"),
                    revalue(t1, "1300.00", "100.00", "2.00"),
                    revalue(t2, "1600.00", "200.00", "2.00"),
                ),
                inceptionSnapshot = inceptionBaseline,
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points[0].rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("1000.00")
            result.points[0].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("1000.00")
            result.points[0].differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
            // Pure Buy & Hold preserves the 300 MORPHO holding: at t1 MORPHO price doubles to $2, total is $1300.
            result.points[1].rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("1300.00")
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("1300.00")
            // At t2 BTC doubles to $200 (3 * $200 = $600) + 20 ETH ($400) + 300 MORPHO ($600) = $1600.
            result.points[2].rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("1600.00")
            result.points[2].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("1600.00")
        }

        "original value weights preserve full capital when normalization needs a residual" {
            val t0 = Instant.parse("2026-06-01T12:00:00Z")
            val t1 = Instant.parse("2026-06-02T12:00:00Z")
            val inceptionBaseline = PortfolioSnapshot(
                timestamp = t0,
                totalValueUSD = BigDecimal("100000000.00"),
                assets = mapOf(
                    "BTC" to assetSnapshot(
                        symbol = "BTC",
                        balance = BigDecimal("33333333.33"),
                        price = BigDecimal.ONE,
                        valueUSD = BigDecimal("33333333.33"),
                        targetPercent = BigDecimal.ONE,
                    ),
                    "ETH" to assetSnapshot(
                        symbol = "ETH",
                        balance = BigDecimal("33333333.33"),
                        price = BigDecimal.ONE,
                        valueUSD = BigDecimal("33333333.33"),
                        targetPercent = BigDecimal.ONE,
                    ),
                    "SOL" to assetSnapshot(
                        symbol = "SOL",
                        balance = BigDecimal("33333333.34"),
                        price = BigDecimal.ONE,
                        valueUSD = BigDecimal("33333333.34"),
                        targetPercent = BigDecimal.ONE,
                    ),
                ),
                actions = emptyList(),
                drawdownPercent = BigDecimal.ZERO,
                fiatDeploymentPercent = BigDecimal.ZERO,
                effectiveUsdTargetPercent = BigDecimal.ZERO,
            )

            val result = calculate(
                snapshots = listOf(inceptionBaseline, inceptionBaseline.copy(timestamp = t1, balancesObservedAt = t1)),
                inceptionSnapshot = inceptionBaseline,
            )

            check(result.availability == ComparisonAvailability.AVAILABLE) {
                "${result.unavailableReason} at ${result.unavailableAt}"
            }
            result.points[0].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100000000.00")
            result.points[0].differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "series beginning after the approved anchor retains zero-valued targets for reconciliation" {
            val t0 = Instant.parse("2026-06-01T12:00:00Z")
            val t1 = Instant.parse("2026-06-02T12:00:00Z")
            val t2 = Instant.parse("2026-06-03T12:00:00Z")
            val inception = PortfolioSnapshot(
                timestamp = t0,
                totalValueUSD = BigDecimal("200.00"),
                assets = mapOf(
                    "BTC" to assetSnapshot(
                        symbol = "BTC",
                        balance = BigDecimal("1.00000000"),
                        price = BigDecimal("100.00"),
                        valueUSD = BigDecimal("100.00"),
                        targetPercent = BigDecimal("50.0"),
                    ),
                    "ETH" to assetSnapshot(
                        symbol = "ETH",
                        balance = BigDecimal.ZERO,
                        price = BigDecimal.ZERO,
                        valueUSD = BigDecimal.ZERO,
                        targetPercent = BigDecimal("50.0"),
                    ),
                    "MORPHO" to assetSnapshot(
                        symbol = "MORPHO",
                        balance = BigDecimal("100.00000000"),
                        price = BigDecimal.ONE,
                        valueUSD = BigDecimal("100.00"),
                        targetPercent = BigDecimal.ZERO,
                    ),
                ),
                actions = emptyList(),
                drawdownPercent = BigDecimal.ZERO,
                fiatDeploymentPercent = BigDecimal.ZERO,
                effectiveUsdTargetPercent = BigDecimal.ZERO,
            )
            val laterWithoutZeroTarget = inception.copy(
                timestamp = t1,
                balancesObservedAt = t1,
                assets = inception.assets.filterKeys { it == "BTC" },
                totalValueUSD = BigDecimal("100.00"),
            )
            val result = calculate(
                snapshots = listOf(laterWithoutZeroTarget, laterWithoutZeroTarget.copy(timestamp = t2)),
                inceptionSnapshot = inception,
            )

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.ASSET_UNIVERSE_CHANGED
            result.unavailableAt shouldBe t1
        }

        "complete-wallet baseline reconciles against the legacy configured-universe series" {
            val fixture = legacySeriesScopeFixture()
            val result = calculate(
                fixture.snapshots,
                fixture.trades,
                fixture.rewards,
                priceProvider = mapPriceProvider(
                    mapOf(
                        "MORPHO" to BigDecimal("1.2917"),
                        "XMR" to BigDecimal("395.69"),
                        "BTC" to BigDecimal.ONE,
                    ),
                ),
                configuredAssetUniverse = setOf("USD", "BTC"),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points.size shouldBe 2
        }

        "historical-only holding priced by the provider contributes to buy-and-hold NAV" {
            val fixture = legacySeriesScopeFixture()
            val result = calculate(
                fixture.snapshots,
                fixture.trades,
                fixture.rewards,
                priceProvider = mapPriceProvider(
                    mapOf(
                        "MORPHO" to BigDecimal("1.2917"),
                        "XMR" to BigDecimal("395.69"),
                        "BTC" to BigDecimal.ONE,
                    ),
                ),
                configuredAssetUniverse = setOf("USD", "BTC"),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points.size shouldBe 2
            result.points.first().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("1860.79")
            // USD 1490.5632 + MORPHO 286.4401 * 1.2917 + BTC 0.24 * 1: the out-of-scope holding
            // the legacy series never records still carries benchmark value from the anchor.
            result.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("1860.80")
        }

        "off-series trade base and historical-only anchor holding stay in actual NAV" {
            val baseline = snapshot(
                now,
                "1100.00",
                mapOf(
                    "ADA" to assetRow("100.00", "1.00", "100.00"),
                    "USD" to assetRow("1000.00", "1.00", "1000.00"),
                ),
            )
            val afterBuy = snapshot(
                now.plusSeconds(60),
                "800.00",
                mapOf("USD" to assetRow("800.00", "1.00", "800.00")),
                balancesObservedAt = now.plusSeconds(60),
            )
            val result = calculate(
                snapshots = listOf(baseline, afterBuy),
                trades = listOf(
                    manualTrade(
                        timestamp = now.plusSeconds(30),
                        side = "buy",
                        symbol = "XMR",
                        volume = "2.00",
                        usdAmount = "200.00",
                        tradeId = "first-point-xmr-buy",
                    ),
                ),
                priceProvider = mapPriceProvider(
                    mapOf("ADA" to BigDecimal.ONE, "XMR" to BigDecimal("100.00")),
                ),
                configuredAssetUniverse = setOf("USD"),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points.last().rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("1100.00")
            result.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("1100.00")
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "same-time trade replay infers rounded checkpoint order independent of input order" {
            val t0 = now
            val tradeTime = now.plusSeconds(30)
            val t1 = now.plusSeconds(60)
            val baseline = snapshot(t0, "100.00", mapOf("USD" to assetRow("100.00", "1.00", "100.00")))
            val after = snapshot(
                t1,
                "80.00",
                mapOf("USD" to assetRow("80.00", "1.00", "80.00")),
            )
            val first = trade(
                timestamp = tradeTime,
                side = "buy",
                symbol = "XLM",
                volume = "1.00",
                usdAmount = "10.00",
                source = TradeSource.API_FILL,
                tradeId = "rounded-same-time-first",
                price = "10.00",
            )
            val second = trade(
                timestamp = tradeTime,
                side = "buy",
                symbol = "XLM",
                volume = "1.00",
                usdAmount = "10.00",
                source = TradeSource.API_FILL,
                tradeId = "rounded-same-time-second",
                price = "10.00",
            )
            val firstBase = ledgerEvent(
                timestamp = tradeTime,
                asset = "XLM",
                amount = "1.00",
                balance = "1.00",
                type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                refid = first.tradeId,
                ledgerId = "rounded-same-time-first-base",
            )
            val firstQuote = ledgerEvent(
                timestamp = tradeTime,
                asset = "USD",
                amount = "-10.00",
                type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                refid = first.tradeId,
                ledgerId = "rounded-same-time-first-quote",
            )
            val secondBase = ledgerEvent(
                timestamp = tradeTime,
                asset = "XLM",
                amount = "1.00",
                balance = "2.000000005",
                type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                refid = second.tradeId,
                ledgerId = "rounded-same-time-second-base",
            )
            val secondQuote = ledgerEvent(
                timestamp = tradeTime,
                asset = "USD",
                amount = "-10.00",
                type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                refid = second.tradeId,
                ledgerId = "rounded-same-time-second-quote",
            )
            val ledgers = listOf(firstBase, firstQuote, secondBase, secondQuote)

            val forward = calculate(
                snapshots = listOf(baseline, after),
                trades = listOf(first, second),
                rewards = ledgers,
                inceptionSnapshot = baseline,
                configuredAssetUniverse = setOf("USD"),
                priceProvider = mapPriceProvider(mapOf("XLM" to BigDecimal("10.00"))),
            )
            val reversed = calculate(
                snapshots = listOf(baseline, after),
                trades = listOf(second, first),
                rewards = ledgers.reversed(),
                inceptionSnapshot = baseline,
                configuredAssetUniverse = setOf("USD"),
                priceProvider = mapPriceProvider(mapOf("XLM" to BigDecimal("10.00"))),
            )

            forward.availability shouldBe ComparisonAvailability.AVAILABLE
            reversed.availability shouldBe ComparisonAvailability.AVAILABLE
            forward.points.last().rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("100.00")
            reversed.points.last().rebalancerValueUSD shouldBeEqualComparingTo forward.points.last().rebalancerValueUSD
            forward.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100.00")
        }

        "complete spot-to-staking transfer preserves full-wallet actual NAV" {
            val t0 = now
            val t1 = now.plusSeconds(60)
            val t2 = now.plusSeconds(120)
            val baseline = snapshot(
                t0,
                "2000.00",
                mapOf(
                    "ATOM" to assetRow("10.00", "100.00", "1000.00"),
                    "USD" to assetRow("1000.00", "1.00", "1000.00"),
                ),
            )
            val spotDebit = ledgerEvent(
                timestamp = t1,
                asset = "ATOM",
                amount = "-10.00",
                type = KrakenApiConstants.LEDGER_TYPE_TRANSFER,
                subtype = "spottostaking",
                balance = "0.00",
                ledgerId = "atom-spot-staking-debit",
                refid = "ATOM-SPOT-TO-STAKING",
            )
            val stakingCredit = ledgerEvent(
                timestamp = t1,
                asset = "ATOM",
                amount = "10.00",
                type = KrakenApiConstants.LEDGER_TYPE_TRANSFER,
                subtype = "spottostaking",
                balance = "10.00",
                ledgerId = "atom-spot-staking-credit",
                refid = "ATOM-SPOT-TO-STAKING",
            )
            val spotOnly = snapshot(
                t1,
                "1000.00",
                mapOf("USD" to assetRow("1000.00", "1.00", "1000.00")),
            )
            val result = calculate(
                snapshots = listOf(baseline, spotOnly, spotOnly.copy(timestamp = t2)),
                rewards = listOf(spotDebit, stakingCredit),
                inceptionSnapshot = baseline,
                configuredAssetUniverse = setOf("USD"),
                priceProvider = mapPriceProvider(mapOf("ATOM" to BigDecimal("100.00"))),
            )

            check(result.availability == ComparisonAvailability.AVAILABLE) {
                "${result.unavailableReason} at ${result.unavailableAt}"
            }
            result.points[1].rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("2000.00")
            result.points[2].rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("2000.00")
        }

        "spot-to-staking transfer before first retained observation moves a hidden holding once" {
            val t0 = now
            val transferAt = t0.plusSeconds(10)
            val observedAt = t0.plusSeconds(60)
            val laterAt = t0.plusSeconds(120)
            val baseline = snapshot(
                t0,
                "2000.00",
                mapOf(
                    "MORPHO" to assetRow("10.00", "100.00", "1000.00"),
                    "USD" to assetRow("1000.00", "1.00", "1000.00"),
                ),
                balancesObservedAt = observedAt,
            )
            val observed = snapshot(
                observedAt,
                "1000.00",
                mapOf("USD" to assetRow("1000.00", "1.00", "1000.00")),
            )
            val debit = ledgerEvent(
                timestamp = transferAt,
                asset = "MORPHO",
                amount = "-10.00",
                type = KrakenApiConstants.LEDGER_TYPE_TRANSFER,
                subtype = "spottostaking",
                refid = "MORPHO-OBSERVATION-TRANSFER",
                ledgerId = "morpho-observation-spot-debit",
            )
            val credit = ledgerEvent(
                timestamp = transferAt,
                asset = "MORPHO",
                amount = "10.00",
                type = KrakenApiConstants.LEDGER_TYPE_TRANSFER,
                subtype = "spottostaking",
                refid = "MORPHO-OBSERVATION-TRANSFER",
                ledgerId = "morpho-observation-staking-credit",
            )

            val result = calculate(
                snapshots = listOf(
                    observed,
                    observed.copy(timestamp = laterAt, balancesObservedAt = laterAt),
                ),
                rewards = listOf(debit, credit),
                inceptionSnapshot = baseline,
                configuredAssetUniverse = setOf("USD"),
                priceProvider = mapPriceProvider(mapOf("MORPHO" to BigDecimal("100.00"))),
            )

            check(result.availability == ComparisonAvailability.AVAILABLE) {
                "${result.unavailableReason} at ${result.unavailableAt}"
            }
            result.points.first().rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("2000.00")
            result.points.first().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("2000.00")
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "refid-linked transfer legs at different timestamps retain their wallet scopes" {
            val t0 = now
            val transferDebitAt = t0.plusSeconds(10)
            val transferCreditAt = t0.plusSeconds(11)
            val observedAt = t0.plusSeconds(60)
            val baseline = snapshot(
                t0,
                "2000.00",
                mapOf(
                    "MORPHO" to assetRow("10.00", "100.00", "1000.00"),
                    "USD" to assetRow("1000.00", "1.00", "1000.00"),
                ),
                balancesObservedAt = observedAt,
            )
            val observed = snapshot(
                observedAt,
                "1000.00",
                mapOf("USD" to assetRow("1000.00", "1.00", "1000.00")),
            )
            val debit = ledgerEvent(
                timestamp = transferDebitAt,
                asset = "MORPHO",
                amount = "-10.00",
                type = KrakenApiConstants.LEDGER_TYPE_TRANSFER,
                subtype = "spottostaking",
                refid = "MORPHO-ASYNC-TRANSFER",
                ledgerId = "morpho-async-spot-debit",
            )
            val credit = ledgerEvent(
                timestamp = transferCreditAt,
                asset = "MORPHO",
                amount = "10.00",
                type = KrakenApiConstants.LEDGER_TYPE_TRANSFER,
                subtype = "spottostaking",
                refid = "MORPHO-ASYNC-TRANSFER",
                ledgerId = "morpho-async-staking-credit",
            )

            val result = calculate(
                snapshots = listOf(
                    observed,
                    observed.copy(
                        timestamp = observedAt.plusSeconds(60),
                        balancesObservedAt = observedAt.plusSeconds(60),
                    ),
                ),
                rewards = listOf(debit, credit),
                inceptionSnapshot = baseline,
                configuredAssetUniverse = setOf("USD"),
                priceProvider = mapPriceProvider(mapOf("MORPHO" to BigDecimal("100.00"))),
            )

            check(result.availability == ComparisonAvailability.AVAILABLE) {
                "${result.unavailableReason} at ${result.unavailableAt}"
            }
            result.points.first().rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("2000.00")
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "non-Spot staking reward is mirrored when the configured Spot series has a zero row" {
            val t0 = now
            val transferTime = now.plusSeconds(30)
            val rewardTime = now.plusSeconds(60)
            val t1 = now.plusSeconds(90)
            val t2 = now.plusSeconds(120)
            val baseline = snapshot(
                t0,
                "200.00",
                mapOf(
                    "ATOM" to assetRow("1.00", "100.00", "100.00"),
                    "USD" to assetRow("100.00", "1.00", "100.00"),
                ),
            )
            val afterReward = snapshot(
                t1,
                "100.00",
                mapOf(
                    "ATOM" to assetRow("0.00", "100.00", "0.00"),
                    "USD" to assetRow("100.00", "1.00", "100.00"),
                ),
            )
            val transferRefid = "atom-stake-reward-transfer"
            val spotDebit = ledgerEvent(
                timestamp = transferTime,
                asset = "ATOM",
                amount = "-1.00",
                balance = "0.00",
                type = KrakenApiConstants.LEDGER_TYPE_TRANSFER,
                subtype = "spottostaking",
                refid = transferRefid,
                ledgerId = "atom-stake-reward-spot-debit",
            )
            val stakingCredit = ledgerEvent(
                timestamp = transferTime,
                asset = "ATOM",
                amount = "1.00",
                balance = "1.00",
                type = KrakenApiConstants.LEDGER_TYPE_TRANSFER,
                subtype = "spottostaking",
                refid = transferRefid,
                ledgerId = "atom-stake-reward-staking-credit",
            )
            val stakingReward = ledgerEvent(
                timestamp = rewardTime,
                asset = "ATOM",
                amount = "1.00",
                balance = "2.00",
                type = KrakenApiConstants.LEDGER_TYPE_STAKING,
                ledgerId = "atom-nonspot-staking-reward",
            )

            val result = calculate(
                snapshots = listOf(baseline, afterReward, afterReward.copy(timestamp = t2)),
                rewards = listOf(spotDebit, stakingCredit, stakingReward),
                inceptionSnapshot = baseline,
                configuredAssetUniverse = setOf("ATOM", "USD"),
                priceProvider = mapPriceProvider(mapOf("ATOM" to BigDecimal("100.00"))),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points[1].rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("300.00")
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("300.00")
            result.points[1].differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "recorded anchor aliases carry the same buy-and-hold balance into canonical snapshots" {
            val baseline = snapshot(
                now,
                "100.00",
                mapOf(
                    "XXBT" to assetRow("1.00", "50.00", "50.00"),
                    "USD" to assetRow("50.00", "1.00", "50.00"),
                ),
            )
            val after = snapshot(
                now.plusSeconds(10),
                "110.00",
                mapOf(
                    "BTC" to assetRow("1.00", "60.00", "60.00"),
                    "USD" to assetRow("50.00", "1.00", "50.00"),
                ),
            )

            val result = calculate(
                snapshots = listOf(baseline, after),
                inceptionSnapshot = baseline,
                configuredAssetUniverse = setOf("BTC", "USD"),
            )

            check(result.availability == ComparisonAvailability.AVAILABLE) {
                "${result.unavailableReason} at ${result.unavailableAt}"
            }
            result.points.first().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100.00")
            result.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("110.00")
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "historical-only duplicate aliases stay aggregated when later snapshots omit the asset" {
            val baseline = snapshot(
                now,
                "250.00",
                mapOf(
                    "BTC" to assetRow("1.50", "100.00", "150.00"),
                    "USD" to assetRow("100.00", "1.00", "100.00"),
                ),
            )
            val aliasObservation = snapshot(
                now.plusSeconds(5),
                "250.00",
                mapOf(
                    "XXBT" to assetRow("0.50", "100.00", "50.00"),
                    "BTC" to assetRow("1.00", "100.00", "100.00"),
                    "USD" to assetRow("100.00", "1.00", "100.00"),
                ),
            )
            val after = snapshot(
                now.plusSeconds(10),
                "100.00",
                mapOf("USD" to assetRow("100.00", "1.00", "100.00")),
            )

            val result = calculate(
                snapshots = listOf(baseline, aliasObservation, after),
                inceptionSnapshot = baseline,
                configuredAssetUniverse = setOf("USD"),
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal("100.00"))),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points[1].rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("250.00")
            result.points.last().rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("250.00")
            result.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("250.00")
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "unsupported events strictly before the approved baseline do not block comparison" {
            val baseline = snapshot(
                now,
                "100.00",
                mapOf("USD" to assetRow("100.00", "1.00", "100.00")),
            )
            val after = baseline.copy(
                timestamp = now.plusSeconds(10),
                balancesObservedAt = now.plusSeconds(10),
            )
            val oldUnsupportedEvent = ledgerEvent(
                timestamp = now.minusSeconds(3600),
                asset = "USD",
                amount = "1.00",
                type = "unsupported-legacy-event",
                ledgerId = "pre-baseline-unsupported-event",
            )

            val result = calculate(
                snapshots = listOf(baseline, after),
                rewards = listOf(oldUnsupportedEvent),
                inceptionSnapshot = baseline,
                configuredAssetUniverse = setOf("USD"),
            )

            check(result.availability == ComparisonAvailability.AVAILABLE) {
                "${result.unavailableReason} at ${result.unavailableAt}"
            }
            result.points.first().differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "malformed orphan trade ledgers strictly before the approved baseline are ignored" {
            val baseline = snapshot(
                now,
                "100.00",
                mapOf("USD" to assetRow("100.00", "1.00", "100.00")),
            )
            val after = baseline.copy(
                timestamp = now.plusSeconds(10),
                balancesObservedAt = now.plusSeconds(10),
            )
            val oldOrphanTradeLeg = ledgerEvent(
                timestamp = now.minusSeconds(3600),
                asset = "XLM",
                amount = "1.00",
                type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                refid = "pre-baseline-orphan-trade-refid",
                ledgerId = "pre-baseline-orphan-trade-leg",
            )

            val result = calculate(
                snapshots = listOf(baseline, after),
                rewards = listOf(oldOrphanTradeLeg),
                inceptionSnapshot = baseline,
                configuredAssetUniverse = setOf("USD"),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points.first().differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "malformed orphan trade ledgers after the approved baseline still fail closed" {
            val baseline = snapshot(
                now,
                "100.00",
                mapOf("USD" to assetRow("100.00", "1.00", "100.00")),
            )
            val after = baseline.copy(
                timestamp = now.plusSeconds(10),
                balancesObservedAt = now.plusSeconds(10),
            )
            val orphanTradeLeg = ledgerEvent(
                timestamp = now.plusSeconds(5),
                asset = "XLM",
                amount = "1.00",
                type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                refid = "post-baseline-orphan-trade-refid",
                ledgerId = "post-baseline-orphan-trade-leg",
            )

            val result = calculate(
                snapshots = listOf(baseline, after),
                rewards = listOf(orphanTradeLeg),
                inceptionSnapshot = baseline,
                configuredAssetUniverse = setOf("USD"),
            )

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE
            result.unavailableAt shouldBe orphanTradeLeg.time
        }

        "unpriceable historical-only holding fails closed instead of disappearing from NAV" {
            val fixture = legacySeriesScopeFixture()
            val result = calculate(
                fixture.snapshots,
                fixture.trades,
                fixture.rewards,
                priceProvider = mapPriceProvider(
                    mapOf("XMR" to BigDecimal("395.69"), "BTC" to BigDecimal.ONE),
                ),
                configuredAssetUniverse = setOf("USD", "BTC"),
            )

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.MISSING_PRICE
            result.unavailableAt shouldBe fixture.successorTimestamp
        }

        "reward-introduced asset without a price fails closed until priced" {
            val eventAt = now.plusSeconds(5)
            val snapshots = listOf(
                snapshot(
                    now,
                    "100.00",
                    mapOf(
                        "BTC" to assetRow("1.00000000", "1", "1.00"),
                        "USD" to assetRow("99.00", "1", "99.00"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(10),
                    "100.00",
                    mapOf(
                        "BTC" to assetRow("1.00000000", "1", "1.00"),
                        "BABY" to assetRow("0.50000000", "0", "0"),
                        "USD" to assetRow("99.00", "1", "99.00"),
                    ),
                ),
            )
            val rewards = listOf(
                ledgerEvent(
                    timestamp = eventAt,
                    asset = "BABY",
                    amount = "0.5",
                    fee = "0",
                    balance = "0.5",
                    ledgerId = "baby-unpriced-nav",
                ),
            )

            val unpriced = calculate(snapshots, rewards = rewards, priceProvider = mapPriceProvider(emptyMap()))
            val priced = calculate(
                snapshots,
                rewards = rewards,
                priceProvider = mapPriceProvider(mapOf("BABY" to BigDecimal("0.18"))),
            )

            unpriced.availability shouldBe ComparisonAvailability.UNAVAILABLE
            unpriced.unavailableReason shouldBe ComparisonUnavailableReason.MISSING_PRICE
            unpriced.unavailableAt shouldBe snapshots.last().timestamp
            priced.availability shouldBe ComparisonAvailability.AVAILABLE
            priced.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100.09")
            priced.points.last().rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("100.09")
            priced.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "new unpriced snapshot holding already included in the row total is not added twice" {
            val baseline = snapshot(
                now,
                "100.00",
                mapOf(
                    "BTC" to assetRow("1.00000000", "100.00", "100.00"),
                    "USD" to assetRow("0", "1.00", "0.00"),
                    "XLM" to assetRow("0", "12.00", "0.00"),
                ),
            )
            val after = snapshot(
                now.plusSeconds(10),
                "112.00",
                mapOf(
                    "BTC" to assetRow("1.00000000", "100.00", "100.00"),
                    "USD" to assetRow("0", "1.00", "0.00"),
                    "XLM" to assetRow("1.00000000", "0", "10.00"),
                ),
            )
            val deposit = ledgerEvent(
                timestamp = now.plusSeconds(5),
                asset = "XLM",
                amount = "1.00000000",
                type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                balance = "1.00000000",
                ledgerId = "new-unpriced-xlm-deposit",
            )
            val result = calculate(
                snapshots = listOf(baseline, after),
                rewards = listOf(deposit),
                inceptionSnapshot = baseline,
                priceProvider = mapPriceProvider(
                    mapOf("BTC" to BigDecimal("100.00"), "XLM" to BigDecimal("12.00")),
                ),
            )

            check(result.availability == ComparisonAvailability.AVAILABLE) {
                "${result.unavailableReason} at ${result.unavailableAt}"
            }
            result.points.first().differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
            result.points.last().rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("112.00")
            result.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("112.00")
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "provider-valued staking reward reconciles a matching row residual but rejects an unexplained one" {
            val baseline = snapshot(
                now,
                "100.00",
                mapOf(
                    "BTC" to assetRow("1.00000000", "100.00", "100.00"),
                    "USD" to assetRow("0.00", "1.00", "0.00"),
                ),
            )
            val reward = ledgerEvent(
                timestamp = now.plusSeconds(5),
                asset = "BABY",
                amount = "1.00000000",
                fee = "0",
                type = KrakenApiConstants.LEDGER_TYPE_STAKING,
                ledgerId = "provider-valued-baby-residual",
            )

            suspend fun withRecordedBtcValue(recordedBtcValue: String) = calculate(
                snapshots = listOf(
                    baseline,
                    snapshot(
                        now.plusSeconds(10),
                        recordedBtcValue,
                        mapOf(
                            "BTC" to assetRow("1.00000000", "100.00", recordedBtcValue),
                            "BABY" to assetRow("1.00000000", "0.00", "0.00"),
                            "USD" to assetRow("0.00", "1.00", "0.00"),
                        ),
                    ),
                ),
                rewards = listOf(reward),
                inceptionSnapshot = baseline,
                configuredAssetUniverse = setOf("BTC", "USD"),
                priceProvider = mapPriceProvider(
                    mapOf("BTC" to BigDecimal("100.00"), "BABY" to BigDecimal("0.18")),
                ),
            )

            val reconciled = withRecordedBtcValue("100.18")
            reconciled.availability shouldBe ComparisonAvailability.AVAILABLE
            reconciled.points.last().rebalancerValueUSD.shouldBeEqualComparingTo(BigDecimal("100.18"))
            reconciled.points.last().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("100.18"))
            reconciled.points.last().differenceUSD.shouldBeEqualComparingTo(BigDecimal.ZERO)

            val unexplained = withRecordedBtcValue("100.21")
            unexplained.availability shouldBe ComparisonAvailability.UNAVAILABLE
            unexplained.unavailableReason shouldBe ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE
            unexplained.unavailableAt shouldBe now.plusSeconds(10)
        }

        "same-time trade replay orders fills from authoritative quote checkpoints" {
            val tradeTime = now.plusSeconds(30)
            val baseline = snapshot(
                now,
                "100.00",
                mapOf("USD" to assetRow("100.00", "1.00", "100.00")),
            )
            val after = snapshot(
                now.plusSeconds(60),
                "80.00",
                mapOf("USD" to assetRow("80.00", "1.00", "80.00")),
            )
            val first = trade(
                timestamp = tradeTime,
                side = "buy",
                symbol = "XLM",
                volume = "1.00",
                usdAmount = "10.00",
                source = TradeSource.API_FILL,
                tradeId = "quote-checkpoint-first",
                price = "10.00",
            )
            val second = first.copy(tradeId = "quote-checkpoint-second")
            val ledgers = listOf(
                ledgerEvent(
                    timestamp = tradeTime,
                    asset = "XLM",
                    amount = "1.00",
                    type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                    subtype = "tradespot",
                    refid = first.tradeId,
                    ledgerId = "quote-checkpoint-first-base",
                ),
                ledgerEvent(
                    timestamp = tradeTime,
                    asset = "USD",
                    amount = "-10.00",
                    balance = "90.00",
                    type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                    subtype = "tradespot",
                    refid = first.tradeId,
                    ledgerId = "quote-checkpoint-first-quote",
                ),
                ledgerEvent(
                    timestamp = tradeTime,
                    asset = "XLM",
                    amount = "1.00",
                    type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                    subtype = "tradespot",
                    refid = second.tradeId,
                    ledgerId = "quote-checkpoint-second-base",
                ),
                ledgerEvent(
                    timestamp = tradeTime,
                    asset = "USD",
                    amount = "-10.00",
                    balance = "80.00",
                    type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                    subtype = "tradespot",
                    refid = second.tradeId,
                    ledgerId = "quote-checkpoint-second-quote",
                ),
            )

            val forward = calculate(
                snapshots = listOf(baseline, after),
                trades = listOf(first, second),
                rewards = ledgers,
                inceptionSnapshot = baseline,
                configuredAssetUniverse = setOf("USD"),
                priceProvider = mapPriceProvider(mapOf("XLM" to BigDecimal("10.00"))),
            )
            val reversed = calculate(
                snapshots = listOf(baseline, after),
                trades = listOf(second, first),
                rewards = ledgers.reversed(),
                inceptionSnapshot = baseline,
                configuredAssetUniverse = setOf("USD"),
                priceProvider = mapPriceProvider(mapOf("XLM" to BigDecimal("10.00"))),
            )

            forward.availability shouldBe ComparisonAvailability.AVAILABLE
            reversed.availability shouldBe ComparisonAvailability.AVAILABLE
            forward.points.last().rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("100.00")
            reversed.points.last().rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("100.00")
            forward.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100.00")
        }

        "same-instant interacting trades with disconnected quote checkpoints fail closed" {
            val tradeTime = now.plusSeconds(30)
            val baseline = snapshot(
                now,
                "100.00",
                mapOf("USD" to assetRow("100.00", "1.00", "100.00")),
            )
            val after = snapshot(
                now.plusSeconds(60),
                "80.00",
                mapOf("USD" to assetRow("80.00", "1.00", "80.00")),
            )
            val first = trade(
                timestamp = tradeTime,
                side = "buy",
                symbol = "XLM",
                volume = "1.00",
                usdAmount = "10.00",
                source = TradeSource.API_FILL,
                tradeId = "disconnected-checkpoint-first",
                price = "10.00",
            )
            val second = first.copy(tradeId = "disconnected-checkpoint-second")
            val ledgers = listOf(
                ledgerEvent(
                    timestamp = tradeTime,
                    asset = "XLM",
                    amount = "1.00",
                    type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                    subtype = "tradespot",
                    refid = first.tradeId,
                    ledgerId = "disconnected-checkpoint-first-base",
                ),
                ledgerEvent(
                    timestamp = tradeTime,
                    asset = "USD",
                    amount = "-10.00",
                    balance = "90.00",
                    type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                    subtype = "tradespot",
                    refid = first.tradeId,
                    ledgerId = "disconnected-checkpoint-first-quote",
                ),
                ledgerEvent(
                    timestamp = tradeTime,
                    asset = "XLM",
                    amount = "1.00",
                    type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                    subtype = "tradespot",
                    refid = second.tradeId,
                    ledgerId = "disconnected-checkpoint-second-base",
                ),
                ledgerEvent(
                    timestamp = tradeTime,
                    asset = "USD",
                    amount = "-10.00",
                    type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                    subtype = "tradespot",
                    refid = second.tradeId,
                    ledgerId = "disconnected-checkpoint-second-quote",
                ),
            )

            val result = calculate(
                snapshots = listOf(baseline, after),
                trades = listOf(first, second),
                rewards = ledgers,
                inceptionSnapshot = baseline,
                configuredAssetUniverse = setOf("USD"),
                priceProvider = mapPriceProvider(mapOf("XLM" to BigDecimal("10.00"))),
            )

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.UNSUPPORTED_TRADE
            result.unavailableAt shouldBe tradeTime
            result.points shouldBe emptyList()
        }

        "same-instant different pairs fail closed when only the right shared-USD quote is checkpointed" {
            val tradeTime = now.plusSeconds(30)
            val baseline = snapshot(
                now,
                "100.00",
                mapOf(
                    "XLM" to assetRow("1.00", "10.00", "10.00"),
                    "ETH" to assetRow("1.00", "10.00", "10.00"),
                    "USD" to assetRow("80.00", "1.00", "80.00"),
                ),
            )
            val after = snapshot(
                now.plusSeconds(60),
                "100.00",
                mapOf(
                    "XLM" to assetRow("2.00", "10.00", "20.00"),
                    "ETH" to assetRow("2.00", "10.00", "20.00"),
                    "USD" to assetRow("60.00", "1.00", "60.00"),
                ),
            )
            val xlmBuy = trade(
                timestamp = tradeTime,
                side = "buy",
                symbol = "XLM",
                volume = "1.00",
                usdAmount = "10.00",
                source = TradeSource.API_FILL,
                tradeId = "shared-usd-uncheckpointed-left",
                price = "10.00",
            ).copy(pair = "XLMUSD")
            val ethBuy = trade(
                timestamp = tradeTime,
                side = "buy",
                symbol = "ETH",
                volume = "1.00",
                usdAmount = "10.00",
                source = TradeSource.API_FILL,
                tradeId = "shared-usd-checkpointed-right",
                price = "10.00",
            ).copy(pair = "ETHUSD")
            val ledgers = listOf(
                ledgerEvent(
                    timestamp = tradeTime,
                    asset = "XLM",
                    amount = "1.00",
                    balance = "2.00",
                    type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                    subtype = "tradespot",
                    refid = xlmBuy.tradeId,
                    ledgerId = "shared-usd-left-base",
                ),
                ledgerEvent(
                    timestamp = tradeTime,
                    asset = "USD",
                    amount = "-10.00",
                    type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                    subtype = "tradespot",
                    refid = xlmBuy.tradeId,
                    ledgerId = "shared-usd-left-quote",
                ),
                ledgerEvent(
                    timestamp = tradeTime,
                    asset = "ETH",
                    amount = "1.00",
                    balance = "2.00",
                    type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                    subtype = "tradespot",
                    refid = ethBuy.tradeId,
                    ledgerId = "shared-usd-right-base",
                ),
                ledgerEvent(
                    timestamp = tradeTime,
                    asset = "USD",
                    amount = "-10.00",
                    balance = "60.00",
                    type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                    subtype = "tradespot",
                    refid = ethBuy.tradeId,
                    ledgerId = "shared-usd-right-quote",
                ),
            )

            val result = calculate(
                snapshots = listOf(baseline, after),
                trades = listOf(xlmBuy, ethBuy),
                rewards = ledgers,
                inceptionSnapshot = baseline,
                configuredAssetUniverse = setOf("USD"),
                priceProvider = mapPriceProvider(
                    mapOf("XLM" to BigDecimal("10.00"), "ETH" to BigDecimal("10.00")),
                ),
            )

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.UNSUPPORTED_TRADE
            result.unavailableAt shouldBe tradeTime
            result.points shouldBe emptyList()
        }

        "same-instant USD quote checkpoints prove the order of different-pair fills" {
            val tradeTime = now.plusSeconds(30)
            val baseline = snapshot(
                now,
                "100.00",
                mapOf(
                    "XLM" to assetRow("1.00", "10.00", "10.00"),
                    "ETH" to assetRow("1.00", "10.00", "10.00"),
                    "USD" to assetRow("80.00", "1.00", "80.00"),
                ),
            )
            val after = snapshot(
                now.plusSeconds(60),
                "100.00",
                mapOf(
                    "XLM" to assetRow("2.00", "10.00", "20.00"),
                    "ETH" to assetRow("2.00", "10.00", "20.00"),
                    "USD" to assetRow("60.00", "1.00", "60.00"),
                ),
            )
            val xlmBuy = trade(
                timestamp = tradeTime,
                side = "buy",
                symbol = "XLM",
                volume = "1.00",
                usdAmount = "10.00",
                source = TradeSource.API_FILL,
                tradeId = "shared-usd-quote-first",
                price = "10.00",
            ).copy(pair = "XLMUSD")
            val ethBuy = trade(
                timestamp = tradeTime,
                side = "buy",
                symbol = "ETH",
                volume = "1.00",
                usdAmount = "10.00",
                source = TradeSource.API_FILL,
                tradeId = "shared-usd-quote-second",
                price = "10.00",
            ).copy(pair = "ETHUSD")
            val ledgers = listOf(
                ledgerEvent(
                    timestamp = tradeTime,
                    asset = "XLM",
                    amount = "1.00",
                    balance = "2.00",
                    type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                    subtype = "tradespot",
                    refid = xlmBuy.tradeId,
                    ledgerId = "shared-usd-first-base",
                ),
                ledgerEvent(
                    timestamp = tradeTime,
                    asset = "USD",
                    amount = "-10.00",
                    balance = "70.00",
                    type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                    subtype = "tradespot",
                    refid = xlmBuy.tradeId,
                    ledgerId = "shared-usd-first-quote",
                ),
                ledgerEvent(
                    timestamp = tradeTime,
                    asset = "ETH",
                    amount = "1.00",
                    balance = "2.00",
                    type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                    subtype = "tradespot",
                    refid = ethBuy.tradeId,
                    ledgerId = "shared-usd-second-base",
                ),
                ledgerEvent(
                    timestamp = tradeTime,
                    asset = "USD",
                    amount = "-10.00",
                    balance = "60.00",
                    type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                    subtype = "tradespot",
                    refid = ethBuy.tradeId,
                    ledgerId = "shared-usd-second-quote",
                ),
            )

            val result = calculate(
                snapshots = listOf(baseline, after),
                trades = listOf(xlmBuy, ethBuy),
                rewards = ledgers,
                inceptionSnapshot = baseline,
                configuredAssetUniverse = setOf("USD"),
                priceProvider = mapPriceProvider(
                    mapOf("XLM" to BigDecimal("10.00"), "ETH" to BigDecimal("10.00")),
                ),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points.last().rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("100.00")
            result.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100.00")
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "same-instant shared quote and base checkpoints do not invent a trade order" {
            val tradeTime = now.plusSeconds(30)
            val baseline = snapshot(
                now,
                "100.00",
                mapOf(
                    "XLM" to assetRow("1.00", "10.00", "10.00"),
                    "USDT" to assetRow("10.00", "1.00", "10.00"),
                    "USD" to assetRow("80.00", "1.00", "80.00"),
                ),
            )
            val after = snapshot(
                now.plusSeconds(60),
                "100.00",
                mapOf(
                    "XLM" to assetRow("2.00", "10.00", "20.00"),
                    "USDT" to assetRow("10.00", "1.00", "10.00"),
                    "USD" to assetRow("70.00", "1.00", "70.00"),
                ),
            )
            val xlmBuy = trade(
                timestamp = tradeTime,
                side = "buy",
                symbol = "XLM",
                volume = "1.00",
                usdAmount = "10.00",
                source = TradeSource.API_FILL,
                tradeId = "shared-usdt-quote-left",
                price = "10.00",
            ).copy(pair = "XLMUSDT")
            val usdtBuy = trade(
                timestamp = tradeTime,
                side = "buy",
                symbol = "USDT",
                volume = "10.00",
                usdAmount = "10.00",
                source = TradeSource.API_FILL,
                tradeId = "shared-usdt-base-right",
                price = "1.00",
            ).copy(pair = "USDTUSD")
            val ledgers = listOf(
                ledgerEvent(
                    timestamp = tradeTime,
                    asset = "XLM",
                    amount = "1.00",
                    balance = "2.00",
                    type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                    subtype = "tradespot",
                    refid = xlmBuy.tradeId,
                    ledgerId = "shared-usdt-left-base",
                ),
                ledgerEvent(
                    timestamp = tradeTime,
                    asset = "USDT",
                    amount = "-10.00",
                    type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                    subtype = "tradespot",
                    refid = xlmBuy.tradeId,
                    ledgerId = "shared-usdt-left-quote",
                ),
                ledgerEvent(
                    timestamp = tradeTime,
                    asset = "USDT",
                    amount = "10.00",
                    balance = "10.00",
                    type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                    subtype = "tradespot",
                    refid = usdtBuy.tradeId,
                    ledgerId = "shared-usdt-right-base",
                ),
                ledgerEvent(
                    timestamp = tradeTime,
                    asset = "USD",
                    amount = "-10.00",
                    balance = "70.00",
                    type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                    subtype = "tradespot",
                    refid = usdtBuy.tradeId,
                    ledgerId = "shared-usdt-right-quote",
                ),
            )

            val result = calculate(
                snapshots = listOf(baseline, after),
                trades = listOf(xlmBuy, usdtBuy),
                rewards = ledgers,
                inceptionSnapshot = baseline,
                configuredAssetUniverse = setOf("USD"),
                priceProvider = mapPriceProvider(
                    mapOf("XLM" to BigDecimal("10.00"), "USDT" to BigDecimal.ONE),
                ),
            )

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.UNSUPPORTED_TRADE
            result.unavailableAt shouldBe tradeTime
            result.points shouldBe emptyList()
        }

        "same-instant shared USD spends replay from independent base checkpoints" {
            val tradeTime = now.plusSeconds(30)
            val baseline = snapshot(
                now,
                "100.00",
                mapOf(
                    "XLM" to assetRow("1.00", "10.00", "10.00"),
                    "ETH" to assetRow("1.00", "10.00", "10.00"),
                    "USD" to assetRow("80.00", "1.00", "80.00"),
                ),
            )
            val after = snapshot(
                now.plusSeconds(60),
                "100.00",
                mapOf(
                    "XLM" to assetRow("2.00", "10.00", "20.00"),
                    "ETH" to assetRow("2.00", "10.00", "20.00"),
                    "USD" to assetRow("60.00", "1.00", "60.00"),
                ),
            )
            val xlmBuy = trade(
                timestamp = tradeTime,
                side = "buy",
                symbol = "XLM",
                volume = "1.00",
                usdAmount = "10.00",
                source = TradeSource.API_FILL,
                tradeId = "shared-usd-uncheckpointed-first",
                price = "10.00",
            ).copy(pair = "XLMUSD")
            val ethBuy = trade(
                timestamp = tradeTime,
                side = "buy",
                symbol = "ETH",
                volume = "1.00",
                usdAmount = "10.00",
                source = TradeSource.API_FILL,
                tradeId = "shared-usd-uncheckpointed-second",
                price = "10.00",
            ).copy(pair = "ETHUSD")
            val ledgers = listOf(
                ledgerEvent(
                    timestamp = tradeTime,
                    asset = "XLM",
                    amount = "1.00",
                    balance = "2.00",
                    type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                    subtype = "tradespot",
                    refid = xlmBuy.tradeId,
                    ledgerId = "shared-usd-uncheckpointed-first-base",
                ),
                ledgerEvent(
                    timestamp = tradeTime,
                    asset = "USD",
                    amount = "-10.00",
                    type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                    subtype = "tradespot",
                    refid = xlmBuy.tradeId,
                    ledgerId = "shared-usd-uncheckpointed-first-quote",
                ),
                ledgerEvent(
                    timestamp = tradeTime,
                    asset = "ETH",
                    amount = "1.00",
                    balance = "2.00",
                    type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                    subtype = "tradespot",
                    refid = ethBuy.tradeId,
                    ledgerId = "shared-usd-uncheckpointed-second-base",
                ),
                ledgerEvent(
                    timestamp = tradeTime,
                    asset = "USD",
                    amount = "-10.00",
                    type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                    subtype = "tradespot",
                    refid = ethBuy.tradeId,
                    ledgerId = "shared-usd-uncheckpointed-second-quote",
                ),
            )

            val result = calculate(
                snapshots = listOf(baseline, after),
                trades = listOf(xlmBuy, ethBuy),
                rewards = ledgers,
                inceptionSnapshot = baseline,
                configuredAssetUniverse = setOf("USD"),
                priceProvider = mapPriceProvider(
                    mapOf("XLM" to BigDecimal("10.00"), "ETH" to BigDecimal("10.00")),
                ),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points.last().rebalancerValueUSD.shouldBeEqualComparingTo(BigDecimal("100.00"))
            result.points.last().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("100.00"))
            result.points.last().differenceUSD.shouldBeEqualComparingTo(BigDecimal.ZERO)
        }

        "same-instant right-base checkpoint without a prior quote chain fails closed" {
            val tradeTime = now.plusSeconds(30)
            val baseline = snapshot(
                now,
                "100.00",
                mapOf(
                    "XLM" to assetRow("1.00", "10.00", "10.00"),
                    "USDT" to assetRow("10.00", "1.00", "10.00"),
                    "USD" to assetRow("80.00", "1.00", "80.00"),
                ),
            )
            val after = snapshot(
                now.plusSeconds(60),
                "100.00",
                mapOf(
                    "XLM" to assetRow("2.00", "10.00", "20.00"),
                    "USDT" to assetRow("10.00", "1.00", "10.00"),
                    "USD" to assetRow("70.00", "1.00", "70.00"),
                ),
            )
            val usdtBuy = trade(
                timestamp = tradeTime,
                side = "buy",
                symbol = "USDT",
                volume = "10.00",
                usdAmount = "10.00",
                source = TradeSource.API_FILL,
                tradeId = "usdt-funding-base-first",
                price = "1.00",
            ).copy(pair = "USDTUSD")
            val xlmBuy = trade(
                timestamp = tradeTime,
                side = "buy",
                symbol = "XLM",
                volume = "1.00",
                usdAmount = "10.00",
                source = TradeSource.API_FILL,
                tradeId = "usdt-funding-quote-second",
                price = "10.00",
            ).copy(pair = "XLMUSDT")
            val ledgers = listOf(
                ledgerEvent(
                    timestamp = tradeTime,
                    asset = "USDT",
                    amount = "10.00",
                    balance = "20.00",
                    type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                    subtype = "tradespot",
                    refid = usdtBuy.tradeId,
                    ledgerId = "usdt-funding-base-leg",
                ),
                ledgerEvent(
                    timestamp = tradeTime,
                    asset = "USD",
                    amount = "-10.00",
                    balance = "70.00",
                    type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                    subtype = "tradespot",
                    refid = usdtBuy.tradeId,
                    ledgerId = "usdt-funding-quote-leg",
                ),
                ledgerEvent(
                    timestamp = tradeTime,
                    asset = "XLM",
                    amount = "1.00",
                    balance = "2.00",
                    type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                    subtype = "tradespot",
                    refid = xlmBuy.tradeId,
                    ledgerId = "usdt-funded-xlm-base-leg",
                ),
                ledgerEvent(
                    timestamp = tradeTime,
                    asset = "USDT",
                    amount = "-10.00",
                    balance = "10.00",
                    type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                    subtype = "tradespot",
                    refid = xlmBuy.tradeId,
                    ledgerId = "usdt-funded-xlm-quote-leg",
                ),
            )

            val result = calculate(
                snapshots = listOf(baseline, after),
                trades = listOf(usdtBuy, xlmBuy),
                rewards = ledgers,
                inceptionSnapshot = baseline,
                configuredAssetUniverse = setOf("USD"),
                priceProvider = mapPriceProvider(
                    mapOf("XLM" to BigDecimal("10.00"), "USDT" to BigDecimal.ONE),
                ),
            )

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE
            result.unavailableAt shouldBe tradeTime
            result.points shouldBe emptyList()
        }

        "partial Spot-to-staking transfer keeps non-Spot units out of Spot and in full-wallet NAV" {
            val baseline = snapshot(
                now,
                "2000.00",
                mapOf(
                    "ATOM" to assetRow("10.00", "100.00", "1000.00"),
                    "USD" to assetRow("1000.00", "1.00", "1000.00"),
                ),
            )
            val spotOnly = snapshot(
                now.plusSeconds(60),
                "1800.00",
                mapOf(
                    "ATOM" to assetRow("8.00", "100.00", "800.00"),
                    "USD" to assetRow("1000.00", "1.00", "1000.00"),
                ),
            )
            val transferTime = now.plusSeconds(30)
            val transferRefid = "partial-atom-spot-to-staking"
            val spotDebit = ledgerEvent(
                timestamp = transferTime,
                asset = "ATOM",
                amount = "-2.00",
                type = KrakenApiConstants.LEDGER_TYPE_TRANSFER,
                subtype = "spottostaking",
                refid = transferRefid,
                ledgerId = "partial-atom-spot-debit",
            )
            val stakingCredit = ledgerEvent(
                timestamp = transferTime,
                asset = "ATOM",
                amount = "2.00",
                type = KrakenApiConstants.LEDGER_TYPE_TRANSFER,
                subtype = "spottostaking",
                refid = transferRefid,
                ledgerId = "partial-atom-staking-credit",
            )

            val result = calculate(
                snapshots = listOf(baseline, spotOnly),
                rewards = listOf(spotDebit, stakingCredit),
                inceptionSnapshot = baseline,
                configuredAssetUniverse = setOf("ATOM", "USD"),
                priceProvider = mapPriceProvider(mapOf("ATOM" to BigDecimal("100.00"))),
            )

            check(result.availability == ComparisonAvailability.AVAILABLE) {
                "${result.unavailableReason} at ${result.unavailableAt}"
            }
            result.points.last().rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("2000.00")
            result.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("2000.00")
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "pre-baseline staking checkpoints preserve a hidden position outside the configured series" {
            val baseline = snapshot(
                now,
                "120.00",
                mapOf(
                    "XLM" to assetRow("2.00", "10.00", "20.00"),
                    "USD" to assetRow("100.00", "1.00", "100.00"),
                ),
            )
            val after = snapshot(
                now.plusSeconds(60),
                "100.00",
                mapOf("USD" to assetRow("100.00", "1.00", "100.00")),
            )
            val firstStaking = ledgerEvent(
                timestamp = now.minusSeconds(20),
                asset = "XLM",
                amount = "0.50",
                balance = "0.50",
                type = KrakenApiConstants.LEDGER_TYPE_STAKING,
                ledgerId = "pre-baseline-xlm-staking-open",
            )
            val sameTimeCheckpoints = listOf(
                ledgerEvent(
                    timestamp = now.minusSeconds(10),
                    asset = "XLM",
                    amount = "0.25",
                    balance = "0.75",
                    type = KrakenApiConstants.LEDGER_TYPE_STAKING,
                    ledgerId = "pre-baseline-xlm-staking-checkpoint-a",
                ),
                ledgerEvent(
                    timestamp = now.minusSeconds(10),
                    asset = "XLM",
                    amount = "0.25",
                    balance = "1.00",
                    type = KrakenApiConstants.LEDGER_TYPE_STAKING,
                    ledgerId = "pre-baseline-xlm-staking-checkpoint-b",
                ),
            )

            val result = calculate(
                snapshots = listOf(baseline, after),
                rewards = listOf(firstStaking) + sameTimeCheckpoints,
                inceptionSnapshot = baseline,
                configuredAssetUniverse = setOf("USD"),
                priceProvider = mapPriceProvider(mapOf("XLM" to BigDecimal("10.00"))),
            )

            check(result.availability == ComparisonAvailability.AVAILABLE) {
                "${result.unavailableReason} at ${result.unavailableAt}"
            }
            result.points.last().rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("120.00")
            result.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("120.00")
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "unpriced snapshot row for a baseline holding preserves its recorded value during wallet revaluation" {
            val baseline = snapshot(
                now,
                "110.00",
                mapOf(
                    "BTC" to assetRow("1.00", "100.00", "100.00"),
                    "ADA" to assetRow("1.00", "10.00", "10.00"),
                    "USD" to assetRow("0.00", "1.00", "0.00"),
                ),
            )
            val after = snapshot(
                now.plusSeconds(60),
                "98.00",
                mapOf(
                    "BTC" to assetRow("1.00", "0.00", "98.00"),
                    "USD" to assetRow("0.00", "1.00", "0.00"),
                ),
            )

            val result = calculate(
                snapshots = listOf(baseline, after),
                inceptionSnapshot = baseline,
                configuredAssetUniverse = setOf("BTC", "USD"),
                priceProvider = mapPriceProvider(
                    mapOf("BTC" to BigDecimal("90.00"), "ADA" to BigDecimal("10.00")),
                ),
            )

            check(result.availability == ComparisonAvailability.AVAILABLE) {
                "${result.unavailableReason} at ${result.unavailableAt}"
            }
            result.points.last().rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("108.00")
            result.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100.00")
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal("8.00")
        }

        "partial snapshot with an unexplained total residual fails closed during actual NAV reconstruction" {
            val baseline = snapshot(
                now,
                "110.00",
                mapOf(
                    "BTC" to assetRow("1.00", "100.00", "100.00"),
                    "ADA" to assetRow("1.00", "10.00", "10.00"),
                    "USD" to assetRow("0.00", "1.00", "0.00"),
                ),
            )
            val inconsistentPartial = snapshot(
                now.plusSeconds(60),
                "105.00",
                mapOf(
                    "BTC" to assetRow("1.00", "100.00", "100.00"),
                    "USD" to assetRow("0.00", "1.00", "0.00"),
                ),
            )

            val result = calculate(
                snapshots = listOf(baseline, inconsistentPartial),
                inceptionSnapshot = baseline,
                configuredAssetUniverse = setOf("BTC", "USD"),
                priceProvider = mapPriceProvider(
                    mapOf("BTC" to BigDecimal("100.00"), "ADA" to BigDecimal("10.00")),
                ),
            )

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE
            result.unavailableAt shouldBe inconsistentPartial.timestamp
        }

        "fully disposed historical-only holdings leave NAV when later snapshots omit the row" {
            val baseline = snapshot(
                now,
                "120.00",
                mapOf(
                    "XLM" to assetRow("2.00", "10.00", "20.00"),
                    "USD" to assetRow("100.00", "1.00", "100.00"),
                ),
            )
            val saleTime = now.plusSeconds(30)
            val afterSale = snapshot(
                now.plusSeconds(60),
                "120.00",
                mapOf("USD" to assetRow("120.00", "1.00", "120.00")),
            )
            val sale = trade(
                timestamp = saleTime,
                side = "sell",
                symbol = "XLM",
                volume = "2.00",
                usdAmount = "20.00",
                source = TradeSource.API_FILL,
                tradeId = "historical-only-xlm-sale",
                price = "10.00",
            )
            val saleLedgers = listOf(
                ledgerEvent(
                    timestamp = saleTime,
                    asset = "XLM",
                    amount = "-2.00",
                    balance = "0.00",
                    type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                    subtype = "tradespot",
                    refid = sale.tradeId,
                    ledgerId = "historical-only-xlm-sale-base",
                ),
                ledgerEvent(
                    timestamp = saleTime,
                    asset = "USD",
                    amount = "20.00",
                    balance = "120.00",
                    type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                    subtype = "tradespot",
                    refid = sale.tradeId,
                    ledgerId = "historical-only-xlm-sale-quote",
                ),
            )

            val result = calculate(
                snapshots = listOf(baseline, afterSale),
                trades = listOf(sale),
                rewards = saleLedgers,
                inceptionSnapshot = baseline,
                configuredAssetUniverse = setOf("USD"),
                priceProvider = mapPriceProvider(mapOf("XLM" to BigDecimal("12.00"))),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points.last().rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("120.00")
            result.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("124.00")
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal("-4.00")
        }

        "hidden-wallet trade without retained ledger legs cannot be reconciled" {
            val baseline = snapshot(
                now,
                "100.00",
                mapOf(
                    "XLM" to assetRow("2.00", "10.00", "20.00"),
                    "USDT" to assetRow("2.00", "10.00", "20.00"),
                    "USD" to assetRow("60.00", "1.00", "60.00"),
                ),
            )
            val tradeTime = now.plusSeconds(30)
            val after = snapshot(
                now.plusSeconds(60),
                "60.00",
                mapOf("USD" to assetRow("60.00", "1.00", "60.00")),
            )
            val hiddenTrade = trade(
                timestamp = tradeTime,
                side = "buy",
                symbol = "XLM",
                volume = "1.00",
                usdAmount = "10.00",
                source = TradeSource.API_FILL,
                tradeId = "hidden-xlm-usdt-without-ledgers",
                price = "10.00",
            ).copy(pair = "XLMUSDT")

            val result = calculate(
                snapshots = listOf(baseline, after),
                trades = listOf(hiddenTrade),
                inceptionSnapshot = baseline,
                configuredAssetUniverse = setOf("USD"),
                priceProvider = mapPriceProvider(
                    mapOf("XLM" to BigDecimal("10.00"), "USDT" to BigDecimal("10.00")),
                ),
            )

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE
            result.unavailableAt shouldBe tradeTime
        }

        "context-known hidden-wallet fill without a ledger effect fails closed at the trade" {
            val firstSnapshotTime = now.plusSeconds(60)
            val depositTime = firstSnapshotTime.plusSeconds(1800)
            val tradeTime = firstSnapshotTime.plusSeconds(3600)
            val lastSnapshotTime = firstSnapshotTime.plusSeconds(7200)
            val inception = snapshot(
                now,
                "100.00",
                mapOf("USD" to assetRow("100.00", "1.00", "100.00")),
                balancesObservedAt = null,
            )
            val firstSnapshot = snapshot(
                firstSnapshotTime,
                "100.00",
                mapOf("USD" to assetRow("100.00", "1.00", "100.00")),
                balancesObservedAt = null,
            )
            val lastSnapshot = snapshot(
                lastSnapshotTime,
                "110.00",
                mapOf("USD" to assetRow("100.00", "1.00", "100.00")),
                balancesObservedAt = null,
            )
            val xlmDeposit = ledgerEvent(
                timestamp = depositTime,
                asset = "XLM",
                amount = "1.00",
                balance = "1.00",
                type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                ledgerId = "known-hidden-xlm-context",
            )

            for (pair in listOf("XLMUSDT", "XLMEUR")) {
                val trade = trade(
                    timestamp = tradeTime,
                    side = "sell",
                    symbol = "XLM",
                    volume = "1.00",
                    usdAmount = "10.00",
                    source = TradeSource.API_FILL,
                    tradeId = "context-known-hidden-xlm-sell-$pair",
                    price = "10.00",
                ).copy(pair = pair)
                val result = calculate(
                    snapshots = listOf(firstSnapshot, lastSnapshot),
                    trades = listOf(trade),
                    rewards = listOf(xlmDeposit),
                    inceptionSnapshot = inception,
                    configuredAssetUniverse = setOf("USD"),
                    priceProvider = mapPriceProvider(
                        mapOf("XLM" to BigDecimal("10.00"), "USDT" to BigDecimal.ONE),
                    ),
                )

                result.availability shouldBe ComparisonAvailability.UNAVAILABLE
                result.unavailableReason shouldBe ComparisonUnavailableReason.UNSUPPORTED_TRADE
                result.unavailableAt shouldBe tradeTime
                result.points shouldBe emptyList()
            }
        }

        "unassigned hidden-wallet buy with retained legs preserves full-wallet NAV" {
            val firstSnapshotTime = now.plusSeconds(60)
            val depositTime = firstSnapshotTime.plusSeconds(600)
            val tradeTime = firstSnapshotTime.plusSeconds(900)
            val lastSnapshotTime = firstSnapshotTime.plusSeconds(3600)
            val inception = snapshot(
                now,
                "100.00",
                mapOf("USD" to assetRow("100.00", "1.00", "100.00")),
            )
            val trade = trade(
                timestamp = tradeTime,
                side = "buy",
                symbol = "XLM",
                volume = "1.00",
                usdAmount = "10.00",
                source = TradeSource.API_FILL,
                tradeId = "hidden-xlm-usdt-buy-with-legs",
                price = "10.00",
            ).copy(pair = "XLMUSDT")
            val ledgers = listOf(
                ledgerEvent(
                    timestamp = depositTime,
                    asset = "USDT",
                    amount = "20.00",
                    balance = "20.00",
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    ledgerId = "hidden-xlm-usdt-buy-deposit",
                ),
                ledgerEvent(
                    timestamp = tradeTime,
                    asset = "XLM",
                    amount = "1.00",
                    balance = "1.00",
                    type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                    subtype = "tradespot",
                    refid = trade.tradeId,
                    ledgerId = "hidden-xlm-usdt-buy-base",
                ),
                ledgerEvent(
                    timestamp = tradeTime,
                    asset = "USDT",
                    amount = "-10.00",
                    balance = "10.00",
                    type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                    subtype = "tradespot",
                    refid = trade.tradeId,
                    ledgerId = "hidden-xlm-usdt-buy-quote",
                ),
            )

            val result = calculate(
                snapshots = listOf(
                    snapshot(
                        firstSnapshotTime,
                        "100.00",
                        mapOf("USD" to assetRow("100.00", "1.00", "100.00")),
                        balancesObservedAt = null,
                    ),
                    snapshot(
                        lastSnapshotTime,
                        "120.00",
                        mapOf("USD" to assetRow("100.00", "1.00", "100.00")),
                        balancesObservedAt = null,
                    ),
                ),
                trades = listOf(trade),
                rewards = ledgers,
                inceptionSnapshot = inception,
                configuredAssetUniverse = setOf("USD"),
                priceProvider = mapPriceProvider(
                    mapOf("XLM" to BigDecimal("10.00"), "USDT" to BigDecimal.ONE),
                ),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points.last().rebalancerValueUSD.shouldBeEqualComparingTo(BigDecimal("120.00"))
            result.points.last().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("120.00"))
            result.points.last().differenceUSD.shouldBeEqualComparingTo(BigDecimal.ZERO)
        }

        "unassigned hidden-wallet sell with retained legs preserves full-wallet NAV" {
            val firstSnapshotTime = now.plusSeconds(60)
            val depositTime = firstSnapshotTime.plusSeconds(600)
            val tradeTime = firstSnapshotTime.plusSeconds(900)
            val lastSnapshotTime = firstSnapshotTime.plusSeconds(3600)
            val inception = snapshot(
                now,
                "100.00",
                mapOf("USD" to assetRow("100.00", "1.00", "100.00")),
            )
            val trade = trade(
                timestamp = tradeTime,
                side = "sell",
                symbol = "XLM",
                volume = "1.00",
                usdAmount = "10.00",
                source = TradeSource.API_FILL,
                tradeId = "hidden-xlm-usdt-sell-with-legs",
                price = "10.00",
            ).copy(pair = "XLMUSDT")
            val ledgers = listOf(
                ledgerEvent(
                    timestamp = depositTime,
                    asset = "XLM",
                    amount = "1.00",
                    balance = "1.00",
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    ledgerId = "hidden-xlm-usdt-sell-deposit",
                ),
                ledgerEvent(
                    timestamp = tradeTime,
                    asset = "XLM",
                    amount = "-1.00",
                    balance = "0.00",
                    type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                    subtype = "tradespot",
                    refid = trade.tradeId,
                    ledgerId = "hidden-xlm-usdt-sell-base",
                ),
                ledgerEvent(
                    timestamp = tradeTime,
                    asset = "USDT",
                    amount = "10.00",
                    balance = "10.00",
                    type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                    subtype = "tradespot",
                    refid = trade.tradeId,
                    ledgerId = "hidden-xlm-usdt-sell-quote",
                ),
            )

            val result = calculate(
                snapshots = listOf(
                    snapshot(
                        firstSnapshotTime,
                        "100.00",
                        mapOf("USD" to assetRow("100.00", "1.00", "100.00")),
                        balancesObservedAt = null,
                    ),
                    snapshot(
                        lastSnapshotTime,
                        "110.00",
                        mapOf("USD" to assetRow("100.00", "1.00", "100.00")),
                        balancesObservedAt = null,
                    ),
                ),
                trades = listOf(trade),
                rewards = ledgers,
                inceptionSnapshot = inception,
                configuredAssetUniverse = setOf("USD"),
                priceProvider = mapPriceProvider(
                    mapOf("XLM" to BigDecimal("10.00"), "USDT" to BigDecimal.ONE),
                ),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points.last().rebalancerValueUSD.shouldBeEqualComparingTo(BigDecimal("110.00"))
            result.points.last().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("110.00"))
            result.points.last().differenceUSD.shouldBeEqualComparingTo(BigDecimal.ZERO)
        }

        "unassigned hidden-wallet sell retains new quote proceeds in actual NAV" {
            val baseline = snapshot(
                now,
                "100.00",
                mapOf(
                    "XLM" to assetRow("2.00", "10.00", "20.00"),
                    "USD" to assetRow("80.00", "1.00", "80.00"),
                ),
            )
            val saleTime = now.plusSeconds(30)
            val after = snapshot(
                now.plusSeconds(60),
                "80.00",
                mapOf("USD" to assetRow("80.00", "1.00", "80.00")),
            )
            val sale = trade(
                timestamp = saleTime,
                side = "sell",
                symbol = "XLM",
                volume = "1.00",
                usdAmount = "10.00",
                source = TradeSource.API_FILL,
                tradeId = "hidden-xlm-usdt-sell-with-ledgers",
                price = "10.00",
            ).copy(pair = "XLMUSDT")
            val saleLedgers = listOf(
                ledgerEvent(
                    timestamp = saleTime,
                    asset = "XLM",
                    amount = "-1.00",
                    balance = "1.00",
                    type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                    subtype = "tradespot",
                    refid = sale.tradeId,
                    ledgerId = "hidden-xlm-usdt-sell-base",
                ),
                ledgerEvent(
                    timestamp = saleTime,
                    asset = "USDT",
                    amount = "10.00",
                    balance = "10.00",
                    type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                    subtype = "tradespot",
                    refid = sale.tradeId,
                    ledgerId = "hidden-xlm-usdt-sell-quote",
                ),
            )

            val result = calculate(
                snapshots = listOf(baseline, after),
                trades = listOf(sale),
                rewards = saleLedgers,
                inceptionSnapshot = baseline,
                configuredAssetUniverse = setOf("USD"),
                priceProvider = mapPriceProvider(
                    mapOf("XLM" to BigDecimal("10.00"), "USDT" to BigDecimal.ONE),
                ),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points.last().rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("100.00")
            result.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100.00")
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "unassigned hidden-wallet trade with complete retained ledger legs replays without changing NAV" {
            val baseline = snapshot(
                now,
                "100.00",
                mapOf(
                    "XLM" to assetRow("2.00", "10.00", "20.00"),
                    "USDT" to assetRow("2.00", "10.00", "20.00"),
                    "USD" to assetRow("60.00", "1.00", "60.00"),
                ),
            )
            val tradeTime = now.plusSeconds(30)
            val after = snapshot(
                now.plusSeconds(60),
                "60.00",
                mapOf("USD" to assetRow("60.00", "1.00", "60.00")),
            )
            val hiddenTrade = trade(
                timestamp = tradeTime,
                side = "buy",
                symbol = "XLM",
                volume = "1.00",
                usdAmount = "10.00",
                source = TradeSource.API_FILL,
                tradeId = "hidden-xlm-usdt-with-ledgers",
                price = "10.00",
            ).copy(pair = "XLMUSDT")
            val legs = listOf(
                ledgerEvent(
                    timestamp = tradeTime,
                    asset = "XLM",
                    amount = "1.00",
                    balance = "3.00",
                    type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                    subtype = "tradespot",
                    refid = hiddenTrade.tradeId,
                    ledgerId = "hidden-xlm-usdt-base",
                ),
                ledgerEvent(
                    timestamp = tradeTime,
                    asset = "USDT",
                    amount = "-1.00",
                    balance = "1.00",
                    type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                    subtype = "tradespot",
                    refid = hiddenTrade.tradeId,
                    ledgerId = "hidden-xlm-usdt-quote",
                ),
            )

            val result = calculate(
                snapshots = listOf(baseline, after),
                trades = listOf(hiddenTrade),
                rewards = legs,
                inceptionSnapshot = baseline,
                configuredAssetUniverse = setOf("USD"),
                priceProvider = mapPriceProvider(
                    mapOf("XLM" to BigDecimal("10.00"), "USDT" to BigDecimal("10.00")),
                ),
            )

            check(result.availability == ComparisonAvailability.AVAILABLE) {
                "${result.unavailableReason} at ${result.unavailableAt}"
            }
            result.points.last().rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("100.00")
            result.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100.00")
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "unpriced hidden-wallet acquisition fails closed for absent and non-positive marks" {
            val baseline = snapshot(
                now,
                "100.00",
                mapOf(
                    "USDT" to assetRow("2.00", "10.00", "20.00"),
                    "USD" to assetRow("80.00", "1.00", "80.00"),
                ),
            )
            val tradeTime = now.plusSeconds(30)
            val after = snapshot(
                now.plusSeconds(60),
                "80.00",
                mapOf("USD" to assetRow("80.00", "1.00", "80.00")),
            )
            val hiddenTrade = trade(
                timestamp = tradeTime,
                side = "buy",
                symbol = "XLM",
                volume = "1.00",
                usdAmount = "10.00",
                source = TradeSource.API_FILL,
                tradeId = "unpriced-hidden-xlm-usdt-buy",
                price = "10.00",
            ).copy(pair = "XLMUSDT")
            val legs = listOf(
                ledgerEvent(
                    timestamp = tradeTime,
                    asset = "XLM",
                    amount = "1.00",
                    balance = "1.00",
                    type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                    subtype = "tradespot",
                    refid = hiddenTrade.tradeId,
                    ledgerId = "unpriced-hidden-xlm-base",
                ),
                ledgerEvent(
                    timestamp = tradeTime,
                    asset = "USDT",
                    amount = "-1.00",
                    balance = "1.00",
                    type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                    subtype = "tradespot",
                    refid = hiddenTrade.tradeId,
                    ledgerId = "unpriced-hidden-xlm-quote",
                ),
            )

            for (xlmPrice in listOf(null, BigDecimal.ZERO)) {
                val prices = buildMap {
                    put("USDT", BigDecimal("10.00"))
                    xlmPrice?.let { put("XLM", it) }
                }
                val result = calculate(
                    snapshots = listOf(baseline, after),
                    trades = listOf(hiddenTrade),
                    rewards = legs,
                    inceptionSnapshot = baseline,
                    configuredAssetUniverse = setOf("USD"),
                    priceProvider = mapPriceProvider(prices),
                )

                result.availability shouldBe ComparisonAvailability.UNAVAILABLE
                result.unavailableReason shouldBe ComparisonUnavailableReason.MISSING_PRICE
                result.unavailableAt shouldBe after.timestamp
            }
        }

        "accepted out-of-series buy without ledger legs remains in actual wallet NAV" {
            val t0 = now
            val t1 = now.plusSeconds(10)
            val baseline = snapshot(t0, "1000.00", mapOf("USD" to assetRow("1000", "1", "1000")))
            val after = snapshot(t1, "799.50", mapOf("USD" to assetRow("799.50", "1", "799.50")))

            val result = calculate(
                snapshots = listOf(baseline, after),
                trades = listOf(
                    manualTrade(
                        timestamp = now.plusSeconds(5),
                        side = "BUY",
                        symbol = "XMR",
                        volume = "1",
                        usdAmount = "200",
                        fee = "0.50",
                        tradeId = "out-of-series-buy-without-ledgers",
                    ),
                ),
                inceptionSnapshot = baseline,
                configuredAssetUniverse = setOf("USD"),
                priceProvider = mapPriceProvider(mapOf("XMR" to BigDecimal("200"))),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points.first().differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
            result.points.last().rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("999.50")
            result.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("1000.00")
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal("-0.50")
        }

        "buy after an earlier zero-target row survives later partial snapshots" {
            val t0 = now
            val zeroRowAt = t0.plusSeconds(10)
            val buyAt = t0.plusSeconds(20)
            val afterAt = t0.plusSeconds(30)
            val baseline = snapshot(t0, "1000.00", mapOf("USD" to assetRow("1000", "1", "1000")))
            val zeroRow = snapshot(
                zeroRowAt,
                "1000.00",
                mapOf(
                    "USD" to assetRow("1000", "1", "1000"),
                    "XLM" to assetRow("0", "10", "0"),
                ),
            )
            val partial = snapshot(afterAt, "799.50", mapOf("USD" to assetRow("799.50", "1", "799.50")))

            val result = calculate(
                snapshots = listOf(baseline, zeroRow, partial),
                trades = listOf(
                    manualTrade(
                        timestamp = buyAt,
                        side = "BUY",
                        symbol = "XLM",
                        volume = "20",
                        usdAmount = "200",
                        fee = "0.50",
                        tradeId = "out-of-series-buy-after-zero-row",
                    ),
                ),
                inceptionSnapshot = baseline,
                configuredAssetUniverse = setOf("USD"),
                priceProvider = mapPriceProvider(mapOf("XLM" to BigDecimal("10"))),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points.last().rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("999.50")
            result.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("1000.00")
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal("-0.50")
        }

        "off-series crypto quote proceeds from an assigned sell remain in actual NAV" {
            val t0 = now
            val t1 = now.plusSeconds(10)
            val baseline = snapshot(
                t0,
                "100.00",
                mapOf("BTC" to assetRow("1", "100", "100"), "USD" to assetRow("0", "1", "0")),
            )
            val after = snapshot(
                t1,
                "0.00",
                mapOf("BTC" to assetRow("0", "100", "0"), "USD" to assetRow("0", "1", "0")),
            )
            val trade = manualTrade(
                timestamp = now.plusSeconds(5),
                side = "SELL",
                symbol = "BTC",
                volume = "1",
                usdAmount = "10",
                tradeId = "out-of-series-quote-sell",
            ).copy(pair = "BTCUSDT", price = BigDecimal("10"))
            val legs = listOf(
                ledgerEvent(
                    timestamp = trade.timestamp,
                    asset = "BTC",
                    amount = "-1",
                    balance = "0",
                    type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                    refid = trade.tradeId,
                    ledgerId = "out-of-series-quote-sell-base",
                    hasAuthoritativeFee = true,
                ),
                ledgerEvent(
                    timestamp = trade.timestamp,
                    asset = "USDT",
                    amount = "10",
                    balance = "10",
                    type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                    refid = trade.tradeId,
                    ledgerId = "out-of-series-quote-sell-quote",
                    hasAuthoritativeFee = true,
                ),
            )

            val result = calculate(
                snapshots = listOf(baseline, after),
                trades = listOf(trade),
                rewards = legs,
                inceptionSnapshot = baseline,
                configuredAssetUniverse = setOf("BTC", "USD"),
                priceProvider = mapPriceProvider(
                    mapOf("BTC" to BigDecimal("100"), "USDT" to BigDecimal.ONE),
                ),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points.last().rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("10.00")
            result.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100.00")
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal("-90.00")
        }

        "off-series owner deposit without an authoritative balance is replayed once" {
            val t0 = now
            val t1 = now.plusSeconds(10)
            val baseline = snapshot(t0, "100.00", mapOf("USD" to assetRow("100", "1", "100")))
            val after = snapshot(t1, "100.00", mapOf("USD" to assetRow("100", "1", "100")))
            val deposit = ledgerEvent(
                timestamp = now.plusSeconds(5),
                asset = "XLM",
                amount = "10",
                type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                ledgerId = "off-series-owner-deposit-no-checkpoint",
            )

            val result = calculate(
                snapshots = listOf(baseline, after),
                rewards = listOf(deposit),
                inceptionSnapshot = baseline,
                configuredAssetUniverse = setOf("USD"),
                priceProvider = mapPriceProvider(mapOf("XLM" to BigDecimal.ONE)),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points.last().rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("110.00")
            result.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("110.00")
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "same-time off-series deposits follow their authoritative balance chain" {
            val t0 = now
            val t1 = now.plusSeconds(10)
            val baseline = snapshot(t0, "100.00", mapOf("USD" to assetRow("100.00", "1.00", "100.00")))
            val after = snapshot(t1, "100.00", mapOf("USD" to assetRow("100.00", "1.00", "100.00")))
            val earlierCheckpoint = ledgerEvent(
                timestamp = t1,
                asset = "XLM",
                amount = "1.00",
                balance = "1.00",
                type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                ledgerId = "same-time-xlm-deposit-a",
                refid = "same-time-xlm-deposit-refid-a",
            )
            val laterCheckpoint = ledgerEvent(
                timestamp = t1,
                asset = "XLM",
                amount = "1.00",
                balance = "2.00",
                type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                ledgerId = "same-time-xlm-deposit-b",
                refid = "same-time-xlm-deposit-refid-b",
            )
            val provenance = SimpleFundingProvenanceResolver(
                deposits = listOf(
                    DepositStatusRecord(
                        refid = "same-time-xlm-deposit-refid-a",
                        txid = "same-time-xlm-deposit-tx-a",
                        asset = "XLM",
                        amount = BigDecimal.ONE,
                        time = t1,
                        status = "Success",
                        method = "Cryptocurrency",
                    ),
                    DepositStatusRecord(
                        refid = "same-time-xlm-deposit-refid-b",
                        txid = "same-time-xlm-deposit-tx-b",
                        asset = "XLM",
                        amount = BigDecimal.ONE,
                        time = t1,
                        status = "Success",
                        method = "Cryptocurrency",
                    ),
                ),
            )

            val result = calculate(
                snapshots = listOf(baseline, after),
                rewards = listOf(laterCheckpoint, earlierCheckpoint),
                inceptionSnapshot = baseline,
                configuredAssetUniverse = setOf("USD"),
                priceProvider = mapPriceProvider(mapOf("XLM" to BigDecimal.ONE)),
                provenanceResolver = provenance,
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points.last().rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("102.00")
            result.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("102.00")
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "off-series withdrawal without a known starting balance fails closed" {
            val t0 = now
            val withdrawalTime = now.plusSeconds(5)
            val t1 = now.plusSeconds(10)
            val baseline = snapshot(t0, "100.00", mapOf("USD" to assetRow("100", "1", "100")))
            val after = snapshot(t1, "100.00", mapOf("USD" to assetRow("100", "1", "100")))
            val withdrawal = ledgerEvent(
                timestamp = withdrawalTime,
                asset = "XLM",
                amount = "-10",
                type = KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL,
                ledgerId = "off-series-withdrawal-unknown-start",
            )

            val result = calculate(
                snapshots = listOf(baseline, after),
                rewards = listOf(withdrawal),
                inceptionSnapshot = baseline,
                configuredAssetUniverse = setOf("USD"),
                priceProvider = mapPriceProvider(mapOf("XLM" to BigDecimal.ONE)),
            )

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE
            result.unavailableAt shouldBe withdrawalTime
        }

        "authoritative off-series withdrawal checkpoint cannot invent its starting balance" {
            val t0 = now
            val withdrawalTime = now.plusSeconds(5)
            val t1 = now.plusSeconds(10)
            val baseline = snapshot(t0, "100.00", mapOf("USD" to assetRow("100", "1", "100")))
            val after = snapshot(t1, "100.00", mapOf("USD" to assetRow("100", "1", "100")))
            val refid = "off-series-authoritative-withdrawal-refid"
            val withdrawal = ledgerEvent(
                timestamp = withdrawalTime,
                asset = "XLM",
                amount = "-10",
                balance = "0",
                type = KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL,
                ledgerId = "off-series-authoritative-withdrawal-unknown-start",
                refid = refid,
            )
            val provenance = SimpleFundingProvenanceResolver(
                withdrawals = listOf(
                    WithdrawStatusRecord(
                        refid = refid,
                        txid = "off-series-authoritative-withdrawal-tx",
                        asset = "XLM",
                        amount = BigDecimal("10"),
                        time = withdrawalTime,
                        status = "Success",
                        method = "Cryptocurrency",
                    ),
                ),
            )

            val result = calculate(
                snapshots = listOf(baseline, after),
                rewards = listOf(withdrawal),
                inceptionSnapshot = baseline,
                configuredAssetUniverse = setOf("USD"),
                priceProvider = mapPriceProvider(mapOf("XLM" to BigDecimal.ONE)),
                provenanceResolver = provenance,
            )

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE
            result.unavailableAt shouldBe withdrawalTime
        }

        "off-series overdraw cannot be hidden by a later deposit before the next snapshot" {
            val t0 = now
            val withdrawalTime = now.plusSeconds(3)
            val depositTime = now.plusSeconds(7)
            val t1 = now.plusSeconds(10)
            val baseline = snapshot(
                t0,
                "105.00",
                mapOf("USD" to assetRow("100", "1", "100"), "XLM" to assetRow("5", "1", "5")),
            )
            val after = snapshot(t1, "100.00", mapOf("USD" to assetRow("100", "1", "100")))
            val overdraw = ledgerEvent(
                timestamp = withdrawalTime,
                asset = "XLM",
                amount = "-10",
                type = KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL,
                ledgerId = "off-series-overdraw-before-deposit",
            )
            val laterDeposit = ledgerEvent(
                timestamp = depositTime,
                asset = "XLM",
                amount = "5",
                type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                ledgerId = "off-series-deposit-after-overdraw",
            )

            val result = calculate(
                snapshots = listOf(baseline, after),
                rewards = listOf(overdraw, laterDeposit),
                inceptionSnapshot = baseline,
                configuredAssetUniverse = setOf("USD"),
                priceProvider = mapPriceProvider(mapOf("XLM" to BigDecimal.ONE)),
            )

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE
            result.unavailableAt shouldBe withdrawalTime
        }

        "unreplayable legacy trade fails closed with an out-of-universe baseline holding" {
            val fixture = legacySeriesScopeFixture()
            val result = calculate(fixture.snapshots, fixture.trades, fixture.rewards)

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.UNSUPPORTED_TRADE
            result.unavailableAt shouldBe fixture.successorTimestamp
        }

        "baseline holding inside the configured universe cannot vanish from the series" {
            val fixture = legacySeriesScopeFixture()
            val result = calculate(
                fixture.snapshots,
                fixture.trades,
                fixture.rewards,
                configuredAssetUniverse = setOf("USD", "BTC", "MORPHO"),
            )

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.ASSET_UNIVERSE_CHANGED
            result.unavailableAt shouldBe fixture.successorTimestamp
        }

        "exact series anchor missing a configured zero-balance target fails closed" {
            val t0 = Instant.parse("2026-06-01T12:00:00Z")
            val t1 = Instant.parse("2026-06-02T12:00:00Z")
            val inception = PortfolioSnapshot(
                timestamp = t0,
                totalValueUSD = BigDecimal("100.00"),
                assets = mapOf(
                    "BTC" to assetSnapshot(
                        symbol = "BTC",
                        balance = BigDecimal("1.00000000"),
                        price = BigDecimal("100.00"),
                        valueUSD = BigDecimal("100.00"),
                        targetPercent = BigDecimal("50.0"),
                    ),
                    "ETH" to assetSnapshot(
                        symbol = "ETH",
                        balance = BigDecimal.ZERO,
                        price = BigDecimal.ZERO,
                        valueUSD = BigDecimal.ZERO,
                        targetPercent = BigDecimal("50.0"),
                    ),
                ),
                actions = emptyList(),
                drawdownPercent = BigDecimal.ZERO,
                fiatDeploymentPercent = BigDecimal.ZERO,
                effectiveUsdTargetPercent = BigDecimal.ZERO,
            )
            val incompleteSeriesAnchor = inception.copy(
                assets = mapOf("BTC" to inception.assets.getValue("BTC")),
            )
            val result = calculate(
                snapshots = listOf(incompleteSeriesAnchor, incompleteSeriesAnchor.copy(timestamp = t1)),
                inceptionSnapshot = inception,
                configuredAssetUniverse = setOf("BTC", "ETH"),
            )

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.ASSET_UNIVERSE_CHANGED
            result.unavailableAt shouldBe t1
        }

        "same-time configured-only row cannot replace the full approved anchor" {
            val t0 = Instant.parse("2026-06-01T12:00:00Z")
            val t1 = Instant.parse("2026-06-02T12:00:00Z")
            val inception = PortfolioSnapshot(
                timestamp = t0,
                totalValueUSD = BigDecimal("200.00"),
                assets = mapOf(
                    "BTC" to assetSnapshot(
                        symbol = "BTC",
                        balance = BigDecimal.ONE,
                        price = BigDecimal("100.00"),
                        valueUSD = BigDecimal("100.00"),
                        targetPercent = BigDecimal("100.0"),
                    ),
                    "MORPHO" to assetSnapshot(
                        symbol = "MORPHO",
                        balance = BigDecimal("100.00000000"),
                        price = BigDecimal.ONE,
                        valueUSD = BigDecimal("100.00"),
                        targetPercent = BigDecimal.ZERO,
                    ),
                ),
                actions = emptyList(),
                drawdownPercent = BigDecimal.ZERO,
                fiatDeploymentPercent = BigDecimal.ZERO,
                effectiveUsdTargetPercent = BigDecimal.ZERO,
                balancesObservedAt = t0,
            )
            val configuredOnlyRow = inception.copy(
                assets = mapOf("BTC" to inception.assets.getValue("BTC")),
            )
            val laterFullRow = inception.copy(
                timestamp = t1,
                balancesObservedAt = t1,
            )

            val result = calculate(
                snapshots = listOf(configuredOnlyRow, laterFullRow),
                inceptionSnapshot = inception,
                configuredAssetUniverse = setOf("BTC"),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points.first().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("200.00")
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "same-time incomplete row with a changed configured balance fails closed" {
            val t0 = Instant.parse("2026-06-01T12:00:00Z")
            val t1 = Instant.parse("2026-06-02T12:00:00Z")
            val inception = PortfolioSnapshot(
                timestamp = t0,
                totalValueUSD = BigDecimal("200.00"),
                assets = mapOf(
                    "BTC" to assetSnapshot(
                        symbol = "BTC",
                        balance = BigDecimal.ONE,
                        price = BigDecimal("100.00"),
                        valueUSD = BigDecimal("100.00"),
                        targetPercent = BigDecimal("100.0"),
                    ),
                    "MORPHO" to assetSnapshot(
                        symbol = "MORPHO",
                        balance = BigDecimal("100.00000000"),
                        price = BigDecimal.ONE,
                        valueUSD = BigDecimal("100.00"),
                        targetPercent = BigDecimal.ZERO,
                    ),
                ),
                actions = emptyList(),
                drawdownPercent = BigDecimal.ZERO,
                fiatDeploymentPercent = BigDecimal.ZERO,
                effectiveUsdTargetPercent = BigDecimal.ZERO,
                balancesObservedAt = t0,
            )
            val incompleteChangedRow = inception.copy(
                assets = mapOf(
                    "BTC" to inception.assets.getValue("BTC").copy(
                        balance = BigDecimal("2.00000000"),
                        valueUSD = BigDecimal("200.00"),
                    ),
                ),
            )
            val laterFullRow = inception.copy(timestamp = t1, balancesObservedAt = t1)

            val result = calculate(
                snapshots = listOf(incompleteChangedRow, laterFullRow),
                inceptionSnapshot = inception,
                configuredAssetUniverse = setOf("BTC"),
            )

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE
            result.unavailableAt shouldBe t0
        }

        "same-time complete row with a positive unconfigured target fails closed" {
            val t0 = Instant.parse("2026-06-01T12:00:00Z")
            val t1 = Instant.parse("2026-06-02T12:00:00Z")
            val inception = PortfolioSnapshot(
                timestamp = t0,
                totalValueUSD = BigDecimal("200.00"),
                assets = mapOf(
                    "BTC" to assetSnapshot(
                        symbol = "BTC",
                        balance = BigDecimal.ONE,
                        price = BigDecimal("100.00"),
                        valueUSD = BigDecimal("100.00"),
                        targetPercent = BigDecimal("100.0"),
                    ),
                    "MORPHO" to assetSnapshot(
                        symbol = "MORPHO",
                        balance = BigDecimal("100.00000000"),
                        price = BigDecimal.ONE,
                        valueUSD = BigDecimal("100.00"),
                        targetPercent = BigDecimal.ZERO,
                    ),
                ),
                actions = emptyList(),
                drawdownPercent = BigDecimal.ZERO,
                fiatDeploymentPercent = BigDecimal.ZERO,
                effectiveUsdTargetPercent = BigDecimal.ZERO,
                balancesObservedAt = t0,
            )
            val sameTimePositiveExtra = inception.copy(
                assets = inception.assets + (
                    "ETH" to assetSnapshot(
                        symbol = "ETH",
                        balance = BigDecimal.ZERO,
                        price = BigDecimal.ZERO,
                        valueUSD = BigDecimal.ZERO,
                        targetPercent = BigDecimal("1.0"),
                    )
                    ),
            )
            val laterFullRow = inception.copy(
                timestamp = t1,
                balancesObservedAt = t1,
            )

            val result = calculate(
                snapshots = listOf(sameTimePositiveExtra, laterFullRow),
                inceptionSnapshot = inception,
                configuredAssetUniverse = setOf("BTC"),
            )

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.ASSET_UNIVERSE_CHANGED
            result.unavailableAt shouldBe t0
        }

        "same-time complete row with a different balance distribution fails closed" {
            val t0 = Instant.parse("2026-06-01T12:00:00Z")
            val t1 = Instant.parse("2026-06-02T12:00:00Z")
            val inception = PortfolioSnapshot(
                timestamp = t0,
                totalValueUSD = BigDecimal("200.00"),
                assets = mapOf(
                    "BTC" to assetSnapshot(
                        symbol = "BTC",
                        balance = BigDecimal.ONE,
                        price = BigDecimal("100.00"),
                        valueUSD = BigDecimal("100.00"),
                        targetPercent = BigDecimal("100.0"),
                    ),
                    "MORPHO" to assetSnapshot(
                        symbol = "MORPHO",
                        balance = BigDecimal("100.00000000"),
                        price = BigDecimal.ONE,
                        valueUSD = BigDecimal("100.00"),
                        targetPercent = BigDecimal.ZERO,
                    ),
                ),
                actions = emptyList(),
                drawdownPercent = BigDecimal.ZERO,
                fiatDeploymentPercent = BigDecimal.ZERO,
                effectiveUsdTargetPercent = BigDecimal.ZERO,
                balancesObservedAt = t0,
            )
            val sameTimeDifferentDistribution = inception.copy(
                assets = inception.assets + (
                    "BTC" to inception.assets.getValue("BTC").copy(
                        balance = BigDecimal("2.00000000"),
                        valueUSD = BigDecimal("200.00"),
                    )
                    ) + (
                    "MORPHO" to inception.assets.getValue("MORPHO").copy(
                        balance = BigDecimal.ZERO,
                        valueUSD = BigDecimal.ZERO,
                    )
                    ),
            )
            val laterSameDistribution = sameTimeDifferentDistribution.copy(
                timestamp = t1,
                balancesObservedAt = t1,
            )

            val result = calculate(
                snapshots = listOf(sameTimeDifferentDistribution, laterSameDistribution),
                inceptionSnapshot = inception,
                configuredAssetUniverse = setOf("BTC"),
            )

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE
            result.unavailableAt shouldBe t0
        }

        "same-time distribution mismatch is checked when a pre-baseline anchor exists" {
            val anchorTime = Instant.parse("2026-05-31T12:00:00Z")
            val t0 = Instant.parse("2026-06-01T12:00:00Z")
            val t1 = Instant.parse("2026-06-02T12:00:00Z")
            val baseline = PortfolioSnapshot(
                timestamp = t0,
                totalValueUSD = BigDecimal("200.00"),
                assets = mapOf(
                    "BTC" to assetSnapshot(
                        symbol = "BTC",
                        balance = BigDecimal.ONE,
                        price = BigDecimal("100.00"),
                        valueUSD = BigDecimal("100.00"),
                        targetPercent = BigDecimal("100.0"),
                    ),
                    "MORPHO" to assetSnapshot(
                        symbol = "MORPHO",
                        balance = BigDecimal("100.00000000"),
                        price = BigDecimal.ONE,
                        valueUSD = BigDecimal("100.00"),
                        targetPercent = BigDecimal.ZERO,
                    ),
                ),
                actions = emptyList(),
                drawdownPercent = BigDecimal.ZERO,
                fiatDeploymentPercent = BigDecimal.ZERO,
                effectiveUsdTargetPercent = BigDecimal.ZERO,
                balancesObservedAt = t0,
            )
            val differingDistribution = baseline.copy(
                assets = baseline.assets + (
                    "BTC" to baseline.assets.getValue("BTC").copy(
                        balance = BigDecimal("2.00000000"),
                        valueUSD = BigDecimal("200.00"),
                    )
                    ) + (
                    "MORPHO" to baseline.assets.getValue("MORPHO").copy(
                        balance = BigDecimal.ZERO,
                        valueUSD = BigDecimal.ZERO,
                    )
                    ),
            )
            val anchor = differingDistribution.copy(timestamp = anchorTime, balancesObservedAt = anchorTime)
            val later = differingDistribution.copy(timestamp = t1, balancesObservedAt = t1)

            val result = calculate(
                snapshots = listOf(differingDistribution, later),
                anchorSnapshot = anchor,
                inceptionSnapshot = baseline,
                configuredAssetUniverse = setOf("BTC"),
            )

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE
            result.unavailableAt shouldBe t0
        }

        "historical-only baseline holdings remain replayable outside configured universe" {
            val t0 = Instant.parse("2026-06-01T12:00:00Z")
            val t1 = Instant.parse("2026-06-02T12:00:00Z")
            val inception = PortfolioSnapshot(
                timestamp = t0,
                totalValueUSD = BigDecimal("200.00"),
                assets = mapOf(
                    "BTC" to assetSnapshot(
                        symbol = "BTC",
                        balance = BigDecimal.ONE,
                        price = BigDecimal("100.00"),
                        valueUSD = BigDecimal("100.00"),
                        targetPercent = BigDecimal("100.0"),
                    ),
                    "MORPHO" to assetSnapshot(
                        symbol = "MORPHO",
                        balance = BigDecimal("100.00000000"),
                        price = BigDecimal.ONE,
                        valueUSD = BigDecimal("100.00"),
                        targetPercent = BigDecimal.ZERO,
                    ),
                ),
                actions = emptyList(),
                drawdownPercent = BigDecimal.ZERO,
                fiatDeploymentPercent = BigDecimal.ZERO,
                effectiveUsdTargetPercent = BigDecimal.ZERO,
                balancesObservedAt = t0,
            )
            val configuredOnlyRow = inception.copy(
                assets = mapOf("BTC" to inception.assets.getValue("BTC")),
            )
            val later = configuredOnlyRow.copy(
                timestamp = t1,
                balancesObservedAt = t1,
                totalValueUSD = BigDecimal("100.00"),
            )

            val result = calculate(
                snapshots = listOf(configuredOnlyRow, later),
                rewards = listOf(
                    ledgerEvent(
                        timestamp = t0.plusSeconds(10),
                        asset = "MORPHO",
                        amount = "-100.00000000",
                        type = KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL,
                    ),
                ),
                inceptionSnapshot = inception,
                priceProvider = mapPriceProvider(
                    mapOf("BTC" to BigDecimal("100.00"), "MORPHO" to BigDecimal.ONE),
                ),
                configuredAssetUniverse = setOf("BTC"),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100.00")
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "still-held historical-only asset omitted after inception reconciles as writer-scope artifact" {
            val t0 = Instant.parse("2026-06-01T12:00:00Z")
            val t1 = Instant.parse("2026-06-02T12:00:00Z")
            val inception = PortfolioSnapshot(
                timestamp = t0,
                totalValueUSD = BigDecimal("200.00"),
                assets = mapOf(
                    "BTC" to assetSnapshot(
                        symbol = "BTC",
                        balance = BigDecimal.ONE,
                        price = BigDecimal("100.00"),
                        valueUSD = BigDecimal("100.00"),
                        targetPercent = BigDecimal("100.0"),
                    ),
                    "MORPHO" to assetSnapshot(
                        symbol = "MORPHO",
                        balance = BigDecimal("100.00000000"),
                        price = BigDecimal.ONE,
                        valueUSD = BigDecimal("100.00"),
                        targetPercent = BigDecimal.ZERO,
                    ),
                ),
                actions = emptyList(),
                drawdownPercent = BigDecimal.ZERO,
                fiatDeploymentPercent = BigDecimal.ZERO,
                effectiveUsdTargetPercent = BigDecimal.ZERO,
                balancesObservedAt = t0,
            )
            val laterConfiguredOnly = inception.copy(
                timestamp = t1,
                balancesObservedAt = t1,
                assets = mapOf("BTC" to inception.assets.getValue("BTC")),
                totalValueUSD = BigDecimal("100.00"),
            )
            val latestConfiguredOnly = laterConfiguredOnly.copy(
                timestamp = t1.plusSeconds(3600),
                balancesObservedAt = t1.plusSeconds(3600),
            )

            val result = calculate(
                snapshots = listOf(laterConfiguredOnly, latestConfiguredOnly),
                inceptionSnapshot = inception,
                priceProvider = mapPriceProvider(
                    mapOf("BTC" to BigDecimal("100.00"), "MORPHO" to BigDecimal.ONE),
                ),
                configuredAssetUniverse = setOf("BTC"),
            )

            check(result.availability == ComparisonAvailability.AVAILABLE) {
                "${result.unavailableReason} at ${result.unavailableAt}"
            }
            result.points.size shouldBe 2
            // The approved anchor owns the complete wallet, so the benchmark keeps the holding
            // the legacy recorded series cannot observe.
            result.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("200.00")
        }

        "omitted historical-only row records a legacy boundary ledger without crashing" {
            val t0 = Instant.parse("2026-06-01T12:00:00Z")
            val t1 = t0.plusSeconds(3600)
            val t2 = t0.plusSeconds(7200)
            val baseline = snapshot(
                t0,
                "200.00",
                mapOf(
                    "BTC" to assetRow("1", "100.00", "100.00"),
                    "MORPHO" to assetRow("100.00000000", "1", "100.00"),
                ),
                balancesObservedAt = t0,
            )
            val omitted = snapshot(
                t1,
                "100.00",
                mapOf("BTC" to assetRow("1", "100.00", "100.00")),
                balancesObservedAt = null,
            )
            val later = omitted.copy(timestamp = t2)

            val result = calculate(
                snapshots = listOf(omitted, later),
                rewards = listOf(
                    ledgerEvent(
                        timestamp = t0.plusSeconds(10),
                        asset = "MORPHO",
                        amount = "-100.00000000",
                        type = KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL,
                    ),
                    ledgerEvent(
                        timestamp = t2,
                        asset = "MORPHO",
                        amount = "2.50000000",
                        type = KrakenApiConstants.LEDGER_TYPE_STAKING,
                    ),
                ),
                inceptionSnapshot = baseline,
                priceProvider = mapPriceProvider(
                    mapOf("BTC" to BigDecimal("100.00"), "MORPHO" to BigDecimal.ONE),
                ),
                configuredAssetUniverse = setOf("BTC"),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points.size shouldBe 2
            result.points.first().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100.00")
            // The pro-rata withdrawal leaves some MORPHO exposure in the benchmark, so its
            // subsequent same-asset staking reward is entitled and adds $2.50 once.
            result.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("102.50")
        }

        "actual NAV retains omitted anchor assets and an out-of-series trade acquisition" {
            val t0 = Instant.parse("2025-12-05T17:00:56.973Z")
            val t1 = t0.plusMillis(101)
            val tradeId = "T6Z73R-C6OVC-AFWUA3"
            val baseline = snapshot(
                t0,
                "1861.22",
                mapOf(
                    "ADA" to assetRow("1.12464668", "0.439651", "0.49"),
                    "BTC" to assetRow("0.00000272", "89332.4", "0.24"),
                    "MORPHO" to assetRow("286.4401", "1.29143929", "369.92"),
                    "USD" to assetRow("1490.5632", "1", "1490.56"),
                    "XMR" to assetRow("0", "0", "0"),
                ),
                balancesObservedAt = null,
            )
            val configuredSnapshot = snapshot(
                t1,
                "1267.86",
                mapOf(
                    "BTC" to assetRow("0.00000272", "89332.4", "0.24"),
                    "USD" to assetRow("1267.6143", "1", "1267.61"),
                ),
                balancesObservedAt = null,
            )

            val result = calculate(
                snapshots = listOf(baseline, configuredSnapshot),
                trades = listOf(
                    trade(
                        timestamp = t1,
                        side = "BUY",
                        symbol = "XMR",
                        volume = "0.56119848",
                        usdAmount = "222.06",
                        fee = "0.8882",
                        source = TradeSource.API_FILL,
                        cycleId = null,
                        tradeId = tradeId,
                        price = "395.69",
                    ),
                ),
                rewards = listOf(
                    ledgerEvent(
                        timestamp = t1,
                        asset = "XMR",
                        amount = "0.56119848",
                        type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                        subtype = "tradespot",
                        ledgerId = "LIG3K7-KLC4B-REA3ZV",
                        refid = tradeId,
                    ),
                    ledgerEvent(
                        timestamp = t1,
                        asset = "USD",
                        amount = "-222.0607",
                        fee = "0.8882",
                        type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                        subtype = "tradespot",
                        ledgerId = "LOG2MN-OXNJG-XFDHZM",
                        refid = tradeId,
                    ),
                ),
                inceptionSnapshot = baseline,
                priceProvider = mapPriceProvider(
                    mapOf(
                        "ADA" to BigDecimal("0.439651"),
                        "BTC" to BigDecimal("89332.4"),
                        "MORPHO" to BigDecimal("1.29143929"),
                        "XMR" to BigDecimal("395.69"),
                    ),
                ),
                configuredAssetUniverse = setOf("BTC", "USD"),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points.first().differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
            result.points.last().rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("1860.33")
            result.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("1861.22")
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal("-0.89")
        }

        "off-series owner deposit is valued before its later sale proceeds" {
            val t0 = Instant.parse("2025-12-05T17:00:56.973Z")
            val depositTime = t0.plusSeconds(60)
            val dustSaleTime = depositTime.plusSeconds(30)
            val saleTime = t0.plusSeconds(120)
            val saleTradeId = "XLM-SALE-TRADE"
            val baseline = snapshot(
                t0,
                "1000.00",
                mapOf("USD" to assetRow("1000.00", "1", "1000.00")),
                balancesObservedAt = null,
            )
            val afterDeposit = baseline.copy(timestamp = depositTime)
            val afterSale = snapshot(
                saleTime,
                "1009.90",
                mapOf("USD" to assetRow("1009.90", "1", "1009.90")),
                balancesObservedAt = null,
            )
            val deposit = ledgerEvent(
                timestamp = depositTime,
                asset = "XLM",
                amount = "10.00007082",
                balance = "10.00007082",
                type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                ledgerId = "LLMOLK-AYSUZ-7YOVPK",
            )
            val dustSaleTradeId = "XLM-DUST-SALE-TRADE"
            val dustSale = trade(
                timestamp = dustSaleTime,
                side = "SELL",
                symbol = "XLM",
                volume = "0.00007082",
                usdAmount = "0",
                fee = "0",
                source = TradeSource.API_FILL,
                cycleId = null,
                tradeId = dustSaleTradeId,
                price = "0.241537",
            )
            val dustSaleLedger = ledgerEvent(
                timestamp = dustSaleTime,
                asset = "XXLM",
                amount = "-0.00007082",
                balance = "10.00",
                type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                subtype = "tradespot",
                ledgerId = "XLM-DUST-SALE-BASE-LEDGER",
                refid = dustSaleTradeId,
            )
            val trade = trade(
                timestamp = saleTime,
                side = "SELL",
                symbol = "XLM",
                volume = "10.00",
                usdAmount = "10.00",
                fee = "0.10",
                source = TradeSource.API_FILL,
                cycleId = null,
                tradeId = saleTradeId,
                price = "1.00",
            )
            val tradeLedgers = listOf(
                ledgerEvent(
                    timestamp = saleTime,
                    asset = "XLM",
                    amount = "-10.00",
                    balance = "0.00",
                    type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                    subtype = "tradespot",
                    ledgerId = "XLM-BASE-LEDGER",
                    refid = saleTradeId,
                ),
                ledgerEvent(
                    timestamp = saleTime,
                    asset = "USD",
                    amount = "10.00",
                    fee = "0.10",
                    balance = "1009.90",
                    type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                    subtype = "tradespot",
                    ledgerId = "XLM-QUOTE-LEDGER",
                    refid = saleTradeId,
                ),
            )

            val result = calculate(
                snapshots = listOf(baseline, afterDeposit, afterSale),
                trades = listOf(dustSale, trade),
                rewards = listOf(deposit, dustSaleLedger) + tradeLedgers,
                inceptionSnapshot = baseline,
                priceProvider = mapPriceProvider(mapOf("XLM" to BigDecimal.ONE)),
                configuredAssetUniverse = setOf("USD"),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points[1].rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("1010.00")
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("1010.00")
            result.points.last().rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("1009.90")
            result.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("1010.00")
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal("-0.10")
        }

        "same-instant conversion keeps the snapshot matching authoritative post-event balances" {
            val t0 = Instant.parse("2025-12-05T17:00:56.973Z")
            val conversionTime = t0.plusSeconds(60)
            val baseline = snapshot(
                t0,
                "1000.00",
                mapOf(
                    "USD" to assetRow("1000.00", "1", "1000.00"),
                    "USDT" to assetRow("0.00", "1", "0.00"),
                ),
                balancesObservedAt = null,
            )
            val stalePreConversion = snapshot(
                conversionTime,
                "1000.00",
                mapOf(
                    "USD" to assetRow("1000.00", "1", "1000.00"),
                    "USDT" to assetRow("0.00", "1", "0.00"),
                ),
                balancesObservedAt = null,
            )
            val postConversion = snapshot(
                conversionTime,
                "999.90",
                mapOf(
                    "USD" to assetRow("0.00", "1", "0.00"),
                    "USDT" to assetRow("999.90", "1", "999.90"),
                ),
                balancesObservedAt = null,
            )
            val conversion = listOf(
                ledgerEvent(
                    timestamp = conversionTime,
                    asset = "USD",
                    amount = "-1000.00",
                    type = "spend",
                    balance = "0.00",
                    ledgerId = "conversion-usd-spend",
                    refid = "conversion-same-instant",
                ),
                ledgerEvent(
                    timestamp = conversionTime,
                    asset = "USDT",
                    amount = "999.90",
                    type = "receive",
                    balance = "999.90",
                    ledgerId = "conversion-usdt-receive",
                    refid = "conversion-same-instant",
                ),
            )

            val result = calculate(
                snapshots = listOf(baseline, stalePreConversion, postConversion),
                rewards = conversion,
                inceptionSnapshot = baseline,
                priceProvider = mapPriceProvider(mapOf("USDT" to BigDecimal.ONE)),
                configuredAssetUniverse = setOf("USD", "USDT"),
            )

            check(result.availability == ComparisonAvailability.AVAILABLE) {
                "${result.unavailableReason} at ${result.unavailableAt}"
            }
            result.points.size shouldBe 2
            result.points.last().timestamp shouldBe conversionTime
            result.points.last().rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("999.90")
            result.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("1000.00")
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal("-0.10")
        }

        "off-series conversion legs share the tracked leg effective timestamp" {
            val baselineTime = now
            val trackedSnapshotTime = now.plusSeconds(60)
            val afterTime = now.plusSeconds(120)
            val conversionTime = trackedSnapshotTime.plusMillis(500)
            val baseline = snapshot(
                baselineTime,
                "1000.00",
                mapOf("USD" to assetRow("1000.00", "1.00", "1000.00")),
            )
            val postConversion = snapshot(
                trackedSnapshotTime,
                "0.00",
                mapOf("USD" to assetRow("0.00", "1.00", "0.00")),
            )
            val later = postConversion.copy(timestamp = afterTime)
            val conversionRefid = "OFF-SERIES-CONVERSION-EFFECTIVE-TIME"
            val conversion = listOf(
                ledgerEvent(
                    timestamp = conversionTime,
                    asset = "USD",
                    amount = "-1000.00",
                    balance = "0.00",
                    type = KrakenApiConstants.LEDGER_TYPE_CONVERSION,
                    ledgerId = "effective-conversion-usd-leg",
                    refid = conversionRefid,
                    hasAuthoritativeFee = true,
                ).copy(aclass = "currency"),
                ledgerEvent(
                    timestamp = conversionTime,
                    asset = "USDT",
                    amount = "999.90",
                    balance = "999.90",
                    type = KrakenApiConstants.LEDGER_TYPE_CONVERSION,
                    ledgerId = "effective-conversion-usdt-leg",
                    refid = conversionRefid,
                    hasAuthoritativeFee = true,
                ).copy(aclass = "currency"),
            )

            val result = calculate(
                snapshots = listOf(baseline, postConversion, later),
                rewards = conversion,
                inceptionSnapshot = baseline,
                configuredAssetUniverse = setOf("USD"),
                priceProvider = mapPriceProvider(mapOf("USDT" to BigDecimal.ONE)),
            )

            check(result.availability == ComparisonAvailability.AVAILABLE) {
                "${result.unavailableReason} at ${result.unavailableAt}"
            }
            result.points[1].rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("999.90")
            result.points[2].rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("999.90")
        }

        "same-instant pre-conversion row does not double-count hidden conversion proceeds" {
            val t0 = Instant.parse("2026-06-01T12:00:00Z")
            val t1 = t0.plusSeconds(60)
            val t2 = t1.plusSeconds(60)
            val baseline = snapshot(
                t0,
                "1000.00",
                mapOf(
                    "USD" to assetRow("1000.00", "1.00", "1000.00"),
                    "USDT" to assetRow("0.00", "1.00", "0.00"),
                ),
                balancesObservedAt = t0,
            )
            val preConversion = snapshot(
                t1,
                "1000.00",
                mapOf(
                    "USD" to assetRow("1000.00", "1.00", "1000.00"),
                    "USDT" to assetRow("0.00", "1.00", "0.00"),
                ),
                balancesObservedAt = t1,
            )
            val postConversion = snapshot(
                t1,
                "999.90",
                mapOf(
                    "USD" to assetRow("0.00", "1.00", "0.00"),
                    "USDT" to assetRow("999.90", "1.00", "999.90"),
                ),
                balancesObservedAt = t1.plusSeconds(1),
            )
            val later = postConversion.copy(timestamp = t2, balancesObservedAt = t2)
            val refid = "SAME-INSTANT-CONVERSION-WITH-500MS-SKEW"
            val conversion = listOf(
                ledgerEvent(
                    timestamp = t1.plusMillis(500),
                    asset = "USD",
                    amount = "-1000.00",
                    type = KrakenApiConstants.LEDGER_TYPE_CONVERSION,
                    balance = "0.00",
                    ledgerId = "skewed-conversion-usd-leg",
                    refid = refid,
                    hasAuthoritativeFee = true,
                ).copy(aclass = "currency"),
                ledgerEvent(
                    timestamp = t1.plusMillis(500),
                    asset = "USDT",
                    amount = "999.90",
                    type = KrakenApiConstants.LEDGER_TYPE_CONVERSION,
                    balance = "999.90",
                    ledgerId = "skewed-conversion-usdt-leg",
                    refid = refid,
                    hasAuthoritativeFee = true,
                ).copy(aclass = "currency"),
            )

            val result = calculate(
                snapshots = listOf(baseline, preConversion, postConversion, later),
                rewards = conversion,
                inceptionSnapshot = baseline,
                configuredAssetUniverse = setOf("USD", "USDT"),
                priceProvider = mapPriceProvider(mapOf("USDT" to BigDecimal.ONE)),
            )

            check(result.availability == ComparisonAvailability.AVAILABLE) {
                "${result.unavailableReason} at ${result.unavailableAt}"
            }
            result.points[1].rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("1000.00")
            result.points[2].rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("999.90")
            result.points.last().rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("999.90")
        }

        "snapshot compaction cannot leave a comparison with no validated interval" {
            val baselineTime = now
            val eventTime = now.plusSeconds(10)
            val baseline = snapshot(
                baselineTime,
                "100.00",
                mapOf(
                    "BTC" to assetRow("1.00", "100.00", "100.00"),
                    "USD" to assetRow("0.00", "1.00", "0.00"),
                ),
            )
            val beforeCredit = snapshot(
                eventTime,
                "100.00",
                mapOf(
                    "BTC" to assetRow("1.00", "100.00", "100.00"),
                    "USD" to assetRow("0.00", "1.00", "0.00"),
                ),
            )
            val afterCredit = snapshot(
                eventTime,
                "200.00",
                mapOf(
                    "BTC" to assetRow("2.00", "100.00", "200.00"),
                    "USD" to assetRow("0.00", "1.00", "0.00"),
                ),
            )
            val credit = ledgerEvent(
                timestamp = eventTime,
                asset = "BTC",
                amount = "1.00",
                balance = "2.00",
                type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                ledgerId = "single-effective-snapshot-credit",
            )

            val result = calculate(
                snapshots = listOf(beforeCredit, afterCredit),
                rewards = listOf(credit),
                inceptionSnapshot = baseline,
                configuredAssetUniverse = setOf("BTC", "USD"),
            )

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.INSUFFICIENT_SNAPSHOTS
        }

        "same-time duplicate snapshots without a positive observation interval are unavailable" {
            val baseline = snapshot(
                now,
                "100.00",
                mapOf("USD" to assetRow("100.00", "1.00", "100.00")),
                balancesObservedAt = null,
            )

            val result = calculate(
                snapshots = listOf(baseline, baseline.copy()),
                configuredAssetUniverse = setOf("USD"),
            )

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.INSUFFICIENT_SNAPSHOTS
        }

        "historical-only row omitted under legacy observation reconciles as writer-scope artifact" {
            val t0 = Instant.parse("2026-06-01T12:00:00Z")
            val t1 = t0.plusSeconds(3600)
            val baseline = snapshot(
                t0,
                "200.00",
                mapOf(
                    "BTC" to assetRow("1", "100.00", "100.00"),
                    "MORPHO" to assetRow("100.00000000", "1", "100.00"),
                ),
                balancesObservedAt = t0,
            )
            val omitted = snapshot(
                t1,
                "100.00",
                mapOf("BTC" to assetRow("1", "100.00", "100.00")),
                balancesObservedAt = null,
            )

            val result = calculate(
                snapshots = listOf(omitted, omitted.copy(timestamp = t1.plusSeconds(3600))),
                inceptionSnapshot = baseline,
                priceProvider = mapPriceProvider(
                    mapOf("BTC" to BigDecimal("100.00"), "MORPHO" to BigDecimal.ONE),
                ),
                configuredAssetUniverse = setOf("BTC"),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points.size shouldBe 2
            result.points.forEach {
                it.buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("200.00")
            }
        }

        "historical-only row reappearing with a nonzero authoritative balance fails closed" {
            val t0 = Instant.parse("2026-06-01T12:00:00Z")
            val t1 = t0.plusSeconds(3600)
            val t2 = t0.plusSeconds(7200)
            val baseline = snapshot(
                t0,
                "200.00",
                mapOf(
                    "BTC" to assetRow("1", "100.00", "100.00"),
                    "MORPHO" to assetRow("100.00000000", "1", "100.00"),
                ),
                balancesObservedAt = t0,
            )
            val omitted = snapshot(
                t1,
                "100.00",
                mapOf("BTC" to assetRow("1", "100.00", "100.00")),
                balancesObservedAt = null,
            )
            val reappeared = snapshot(
                t2,
                "150.00",
                mapOf(
                    "BTC" to assetRow("1", "100.00", "100.00"),
                    "MORPHO" to assetRow("50.00000000", "1", "50.00"),
                ),
                balancesObservedAt = null,
            )

            val result = calculate(
                snapshots = listOf(omitted, reappeared),
                rewards = listOf(
                    ledgerEvent(
                        timestamp = t0.plusSeconds(10),
                        asset = "MORPHO",
                        amount = "-100.00000000",
                        type = KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL,
                    ),
                    ledgerEvent(
                        timestamp = t2,
                        asset = "MORPHO",
                        amount = "50.00000000",
                        type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                        balance = "50.00000000",
                    ),
                ),
                inceptionSnapshot = baseline,
                priceProvider = mapPriceProvider(
                    mapOf("BTC" to BigDecimal("100.00"), "MORPHO" to BigDecimal.ONE),
                ),
                configuredAssetUniverse = setOf("BTC"),
            )

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE
            result.unavailableAt shouldBe t2
        }

        "configured zero-weight assets still belong to the target universe" {
            val t0 = Instant.parse("2026-06-01T12:00:00Z")
            val t1 = Instant.parse("2026-06-02T12:00:00Z")
            val baseline = PortfolioSnapshot(
                timestamp = t0,
                totalValueUSD = BigDecimal("100.00"),
                assets = mapOf(
                    "BTC" to assetSnapshot(
                        symbol = "BTC",
                        balance = BigDecimal.ONE,
                        price = BigDecimal("100.00"),
                        valueUSD = BigDecimal("100.00"),
                        targetPercent = BigDecimal("100.0"),
                    ),
                    "ETH" to assetSnapshot(
                        symbol = "ETH",
                        balance = BigDecimal.ZERO,
                        price = BigDecimal.ZERO,
                        valueUSD = BigDecimal.ZERO,
                        targetPercent = BigDecimal.ZERO,
                    ),
                ),
                actions = emptyList(),
                drawdownPercent = BigDecimal.ZERO,
                fiatDeploymentPercent = BigDecimal.ZERO,
                effectiveUsdTargetPercent = BigDecimal.ZERO,
            )
            val result = calculate(
                snapshots = listOf(
                    baseline,
                    baseline.copy(
                        timestamp = t1,
                        assets = mapOf("BTC" to baseline.assets.getValue("BTC")),
                    ),
                ),
                configuredAssetUniverse = setOf("BTC", "ETH"),
            )

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.ASSET_UNIVERSE_CHANGED
            result.unavailableAt shouldBe t1
        }

        "configured asset absent from the baseline fails closed even when later zero-valued" {
            val t0 = Instant.parse("2026-06-01T12:00:00Z")
            val t1 = Instant.parse("2026-06-02T12:00:00Z")
            val btc = assetSnapshot(
                symbol = "BTC",
                balance = BigDecimal.ONE,
                price = BigDecimal("100.00"),
                valueUSD = BigDecimal("100.00"),
                targetPercent = BigDecimal("100.0"),
            )
            val baseline = PortfolioSnapshot(
                timestamp = t0,
                totalValueUSD = BigDecimal("100.00"),
                assets = mapOf("BTC" to btc),
                actions = emptyList(),
                drawdownPercent = BigDecimal.ZERO,
                fiatDeploymentPercent = BigDecimal.ZERO,
                effectiveUsdTargetPercent = BigDecimal.ZERO,
            )
            val later = baseline.copy(
                timestamp = t1,
                assets = baseline.assets + (
                    "ETH" to assetSnapshot(
                        symbol = "ETH",
                        balance = BigDecimal.ZERO,
                        price = BigDecimal.ZERO,
                        valueUSD = BigDecimal.ZERO,
                        targetPercent = BigDecimal.ZERO,
                    )
                    ),
            )

            val result = calculate(
                snapshots = listOf(baseline, later),
                configuredAssetUniverse = setOf("BTC", "ETH"),
            )

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.ASSET_UNIVERSE_CHANGED
            result.unavailableAt shouldBe t0
        }

        "removed configured target fails closed even when its baseline balance was zero" {
            val t0 = Instant.parse("2026-06-01T12:00:00Z")
            val t1 = Instant.parse("2026-06-02T12:00:00Z")
            val baseline = PortfolioSnapshot(
                timestamp = t0,
                totalValueUSD = BigDecimal("100.00"),
                assets = mapOf(
                    "BTC" to assetSnapshot(
                        symbol = "BTC",
                        balance = BigDecimal.ONE,
                        price = BigDecimal("100.00"),
                        valueUSD = BigDecimal("100.00"),
                        targetPercent = BigDecimal("50.0"),
                    ),
                    "ETH" to assetSnapshot(
                        symbol = "ETH",
                        balance = BigDecimal.ZERO,
                        price = BigDecimal.ZERO,
                        valueUSD = BigDecimal.ZERO,
                        targetPercent = BigDecimal("50.0"),
                    ),
                ),
                actions = emptyList(),
                drawdownPercent = BigDecimal.ZERO,
                fiatDeploymentPercent = BigDecimal.ZERO,
                effectiveUsdTargetPercent = BigDecimal.ZERO,
            )
            val result = calculate(
                snapshots = listOf(
                    baseline,
                    baseline.copy(
                        timestamp = t1,
                        assets = mapOf("BTC" to baseline.assets.getValue("BTC")),
                    ),
                ),
                configuredAssetUniverse = setOf("BTC"),
            )

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.ASSET_UNIVERSE_CHANGED
            result.unavailableAt shouldBe t0
        }

        "later positive target outside configured universe fails closed" {
            val t0 = Instant.parse("2026-06-01T12:00:00Z")
            val t1 = Instant.parse("2026-06-02T12:00:00Z")
            val baseline = PortfolioSnapshot(
                timestamp = t0,
                totalValueUSD = BigDecimal("100.00"),
                assets = mapOf(
                    "BTC" to assetSnapshot(
                        symbol = "BTC",
                        balance = BigDecimal.ONE,
                        price = BigDecimal("100.00"),
                        valueUSD = BigDecimal("100.00"),
                        targetPercent = BigDecimal("100.0"),
                    ),
                ),
                actions = emptyList(),
                drawdownPercent = BigDecimal.ZERO,
                fiatDeploymentPercent = BigDecimal.ZERO,
                effectiveUsdTargetPercent = BigDecimal.ZERO,
            )
            val later = baseline.copy(
                timestamp = t1,
                assets = baseline.assets + (
                    "ETH" to assetSnapshot(
                        symbol = "ETH",
                        balance = BigDecimal.ZERO,
                        price = BigDecimal.ZERO,
                        valueUSD = BigDecimal.ZERO,
                        targetPercent = BigDecimal("1.0"),
                    )
                    ),
            )

            val result = calculate(
                snapshots = listOf(baseline, later),
                configuredAssetUniverse = setOf("BTC"),
            )

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.ASSET_UNIVERSE_CHANGED
            result.unavailableAt shouldBe t1
        }

        "same-time recorded twin keeps the full anchor without synthetic trade replay" {
            val t0 = Instant.parse("2026-06-01T12:00:00Z")
            val t1 = Instant.parse("2026-06-02T12:00:00Z")
            val inception = PortfolioSnapshot(
                timestamp = t0,
                totalValueUSD = BigDecimal("225.00"),
                assets = mapOf(
                    "BTC" to assetSnapshot(
                        symbol = "BTC",
                        balance = BigDecimal.ONE,
                        price = BigDecimal("100.00"),
                        valueUSD = BigDecimal("100.00"),
                        targetPercent = BigDecimal("50.0"),
                    ),
                    "ETH" to assetSnapshot(
                        symbol = "ETH",
                        balance = BigDecimal.ONE,
                        price = BigDecimal("100.00"),
                        valueUSD = BigDecimal("100.00"),
                        targetPercent = BigDecimal("50.0"),
                    ),
                    "MORPHO" to assetSnapshot(
                        symbol = "MORPHO",
                        balance = BigDecimal("25.00"),
                        price = BigDecimal.ONE,
                        valueUSD = BigDecimal("25.00"),
                        targetPercent = BigDecimal.ZERO,
                    ),
                ),
                actions = emptyList(),
                drawdownPercent = BigDecimal.ZERO,
                fiatDeploymentPercent = BigDecimal.ZERO,
                effectiveUsdTargetPercent = BigDecimal.ZERO,
            )
            val recordedTwin = inception.copy(
                balancesObservedAt = t0,
                totalValueUSD = BigDecimal("225.00"),
                assets = inception.assets.mapValues { (k, v) ->
                    if (k == "ETH") v.copy(targetPercent = BigDecimal.ZERO) else v
                },
            )
            val result = calculate(
                snapshots = listOf(recordedTwin, recordedTwin.copy(timestamp = t1, balancesObservedAt = t1)),
                inceptionSnapshot = inception,
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points.first().rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("225.00")
            result.points.first().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("225.00")
        }

        "zero-balance target receives no synthetic inception capital" {
            val t0 = Instant.parse("2026-06-01T12:00:00Z")
            val t1 = Instant.parse("2026-06-02T12:00:00Z")
            val inceptionBaseline = PortfolioSnapshot(
                timestamp = t0,
                totalValueUSD = BigDecimal("1000.00"),
                assets = mapOf(
                    "BTC" to assetSnapshot(
                        symbol = "BTC",
                        balance = BigDecimal("3.00000000"),
                        price = BigDecimal("100.00"),
                        valueUSD = BigDecimal("300.00"),
                        targetPercent = BigDecimal("60.0"),
                    ),
                    "ETH" to assetSnapshot(
                        symbol = "ETH",
                        balance = BigDecimal.ZERO,
                        price = BigDecimal.ZERO,
                        valueUSD = BigDecimal.ZERO,
                        targetPercent = BigDecimal("40.0"),
                    ),
                    "MORPHO" to assetSnapshot(
                        symbol = "MORPHO",
                        balance = BigDecimal("700.00000000"),
                        price = BigDecimal.ONE,
                        valueUSD = BigDecimal("700.00"),
                        targetPercent = BigDecimal.ZERO,
                    ),
                ),
                actions = emptyList(),
                drawdownPercent = BigDecimal.ZERO,
                fiatDeploymentPercent = BigDecimal.ZERO,
                effectiveUsdTargetPercent = BigDecimal.ZERO,
            )
            val later = inceptionBaseline.copy(
                timestamp = t1,
                balancesObservedAt = t1,
                assets = inceptionBaseline.assets + mapOf(
                    "ETH" to inceptionBaseline.assets.getValue("ETH").copy(price = BigDecimal("40.00")),
                ),
            )

            val result = calculate(
                snapshots = listOf(inceptionBaseline, later),
                inceptionSnapshot = inceptionBaseline,
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal("100.00"))),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points[0].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("1000.00")
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("1000.00")
        }

        "positive recorded value with a missing inception price uses the historical source" {
            val t0 = Instant.parse("2026-06-01T12:00:00Z")
            val t1 = Instant.parse("2026-06-02T12:00:00Z")
            val inceptionBaseline = PortfolioSnapshot(
                timestamp = t0,
                totalValueUSD = BigDecimal("100.00"),
                assets = mapOf(
                    "BTC" to assetSnapshot(
                        symbol = "BTC",
                        balance = BigDecimal.ONE,
                        price = BigDecimal.ZERO,
                        valueUSD = BigDecimal("100.00"),
                        targetPercent = BigDecimal("100.0"),
                    ),
                ),
                actions = emptyList(),
                drawdownPercent = BigDecimal.ZERO,
                fiatDeploymentPercent = BigDecimal.ZERO,
                effectiveUsdTargetPercent = BigDecimal.ZERO,
            )
            val later = inceptionBaseline.copy(
                timestamp = t1,
                totalValueUSD = BigDecimal("200.00"),
                balancesObservedAt = t1,
                assets = mapOf(
                    "BTC" to inceptionBaseline.assets.getValue("BTC").copy(
                        price = BigDecimal("200.00"),
                        valueUSD = BigDecimal("200.00"),
                    ),
                ),
            )

            val result = calculate(
                snapshots = listOf(inceptionBaseline, later),
                inceptionSnapshot = inceptionBaseline,
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal("100.00"))),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points[0].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100.00")
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("200.00")
        }

        "positive recorded value with no inception price source fails closed" {
            val t0 = Instant.parse("2026-06-01T12:00:00Z")
            val t1 = Instant.parse("2026-06-02T12:00:00Z")
            val inceptionBaseline = PortfolioSnapshot(
                timestamp = t0,
                totalValueUSD = BigDecimal("100.00"),
                assets = mapOf(
                    "BTC" to assetSnapshot(
                        symbol = "BTC",
                        balance = BigDecimal.ONE,
                        price = BigDecimal.ZERO,
                        valueUSD = BigDecimal("100.00"),
                        targetPercent = BigDecimal("100.0"),
                    ),
                ),
                actions = emptyList(),
                drawdownPercent = BigDecimal.ZERO,
                fiatDeploymentPercent = BigDecimal.ZERO,
                effectiveUsdTargetPercent = BigDecimal.ZERO,
            )
            val later = inceptionBaseline.copy(
                timestamp = t1,
                totalValueUSD = BigDecimal("200.00"),
                balancesObservedAt = t1,
                assets = mapOf(
                    "BTC" to inceptionBaseline.assets.getValue("BTC").copy(
                        price = BigDecimal("200.00"),
                        valueUSD = BigDecimal("200.00"),
                    ),
                ),
            )

            val result = calculate(
                snapshots = listOf(inceptionBaseline, later),
                inceptionSnapshot = inceptionBaseline,
            )

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.MISSING_PRICE

            val sourceError = calculate(
                snapshots = listOf(inceptionBaseline, later),
                inceptionSnapshot = inceptionBaseline,
                priceProvider = HistoricalPriceProvider { _, time ->
                    throw HistoricalPriceSourceException("BTC", "historical source unavailable", time)
                },
            )

            sourceError.availability shouldBe ComparisonAvailability.UNAVAILABLE
            sourceError.unavailableReason shouldBe ComparisonUnavailableReason.HISTORICAL_PRICE_SOURCE_ERROR
            sourceError.unavailableAt shouldBe t0
        }

        "without an original configured holding the benchmark is unavailable" {
            val t0 = Instant.parse("2026-06-01T12:00:00Z")
            val t1 = Instant.parse("2026-06-02T12:00:00Z")
            val inceptionBaseline = PortfolioSnapshot(
                timestamp = t0,
                totalValueUSD = BigDecimal.ZERO,
                assets = mapOf(
                    "BTC" to assetSnapshot(
                        symbol = "BTC",
                        balance = BigDecimal.ZERO,
                        price = BigDecimal.ZERO,
                        valueUSD = BigDecimal.ZERO,
                        targetPercent = BigDecimal("60.0"),
                    ),
                    "ETH" to assetSnapshot(
                        symbol = "ETH",
                        balance = BigDecimal.ZERO,
                        price = BigDecimal.ZERO,
                        valueUSD = BigDecimal.ZERO,
                        targetPercent = BigDecimal("40.0"),
                    ),
                    "MORPHO" to assetSnapshot(
                        symbol = "MORPHO",
                        balance = BigDecimal.ZERO,
                        price = BigDecimal.ONE,
                        valueUSD = BigDecimal.ZERO,
                        targetPercent = BigDecimal.ZERO,
                    ),
                ),
                actions = emptyList(),
                drawdownPercent = BigDecimal.ZERO,
                fiatDeploymentPercent = BigDecimal.ZERO,
                effectiveUsdTargetPercent = BigDecimal.ZERO,
            )
            val later = inceptionBaseline.copy(
                timestamp = t1,
                balancesObservedAt = t1,
                assets = inceptionBaseline.assets + mapOf(
                    "BTC" to inceptionBaseline.assets.getValue("BTC").copy(price = BigDecimal("110.00")),
                    "ETH" to inceptionBaseline.assets.getValue("ETH").copy(price = BigDecimal("22.00")),
                ),
            )

            val result = calculate(
                snapshots = listOf(inceptionBaseline, later),
                inceptionSnapshot = inceptionBaseline,
                priceProvider = mapPriceProvider(
                    mapOf(
                        "BTC" to BigDecimal("100.00"),
                        "ETH" to BigDecimal("20.00"),
                    ),
                ),
            )

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.NON_POSITIVE_BASELINE
        }

        "owner contribution is allocated once while a complete conversion stays benchmark-neutral" {
            val t0 = Instant.parse("2026-06-01T12:00:00Z")
            val t1 = Instant.parse("2026-06-02T12:00:00Z")
            val t2 = Instant.parse("2026-06-03T12:00:00Z")
            val inception = snapshot(
                t0,
                "1000.00",
                mapOf(
                    "BTC" to assetRow("0.01000000", "50000.00", "500.00"),
                    "USD" to assetRow("500.00", "1.0", "500.00"),
                ),
            ).copy(
                assets = mapOf(
                    "BTC" to assetSnapshot(
                        symbol = "BTC",
                        balance = BigDecimal("0.01000000"),
                        price = BigDecimal("50000.00"),
                        valueUSD = BigDecimal("500.00"),
                        targetPercent = BigDecimal("50.0"),
                    ),
                    "USD" to assetSnapshot(
                        symbol = "USD",
                        balance = BigDecimal("500.00"),
                        price = BigDecimal.ONE,
                        valueUSD = BigDecimal("500.00"),
                        targetPercent = BigDecimal("50.0"),
                    ),
                ),
            )
            val afterEvents = inception.copy(
                timestamp = t1,
                totalValueUSD = BigDecimal("1100.00"),
                balancesObservedAt = t1,
                assets = inception.assets + mapOf(
                    "BTC" to inception.assets.getValue("BTC").copy(
                        balance = BigDecimal("0.02100000"),
                        valueUSD = BigDecimal("1050.00"),
                    ),
                    "USD" to inception.assets.getValue("USD").copy(
                        balance = BigDecimal("50.00"),
                        valueUSD = BigDecimal("50.00"),
                    ),
                ),
            )
            val later = afterEvents.copy(
                timestamp = t2,
                totalValueUSD = BigDecimal("1310.00"),
                balancesObservedAt = t2,
                assets = afterEvents.assets + mapOf(
                    "BTC" to afterEvents.assets.getValue("BTC").copy(
                        price = BigDecimal("60000.00"),
                        valueUSD = BigDecimal("1260.00"),
                    ),
                ),
            )

            val result = calculate(
                snapshots = listOf(inception, afterEvents, later),
                rewards = listOf(
                    ledgerEvent(
                        timestamp = t1,
                        asset = "USD",
                        amount = "100.00",
                        type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                        refid = "OWNER-CONTRIBUTION-CONVERSION",
                    ),
                    ledgerEvent(
                        timestamp = t1,
                        asset = "USD",
                        amount = "-550.00",
                        balance = "0.00",
                        ledgerId = "conversion-source-owner",
                        refid = "OWNER-CONVERSION",
                        type = KrakenApiConstants.LEDGER_TYPE_CONVERSION,
                        hasAuthoritativeFee = true,
                    ),
                    ledgerEvent(
                        timestamp = t1,
                        asset = "BTC",
                        amount = "0.01100000",
                        balance = "0.02200000",
                        ledgerId = "conversion-destination-owner",
                        refid = "OWNER-CONVERSION",
                        type = KrakenApiConstants.LEDGER_TYPE_CONVERSION,
                        hasAuthoritativeFee = true,
                    ),
                ),
                inceptionSnapshot = inception,
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal("50000.00"))),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("1100.00")
            result.points[2].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("1210.00")
            result.points[2].differenceUSD shouldBeEqualComparingTo BigDecimal("100.00")
        }

        "complete conversion and manual trade do not change pure Buy & Hold" {
            val t0 = Instant.parse("2026-06-01T12:00:00Z")
            val t1 = Instant.parse("2026-06-02T12:00:00Z")
            val t2 = Instant.parse("2026-06-03T12:00:00Z")
            val inception = snapshot(
                t0,
                "1000.00",
                mapOf(
                    "BTC" to assetRow("0.01000000", "50000.00", "500.00"),
                    "USD" to assetRow("500.00", "1.0", "500.00"),
                ),
            ).copy(
                assets = mapOf(
                    "BTC" to assetSnapshot(
                        symbol = "BTC",
                        balance = BigDecimal("0.01000000"),
                        price = BigDecimal("50000.00"),
                        valueUSD = BigDecimal("500.00"),
                        targetPercent = BigDecimal("50.0"),
                    ),
                    "USD" to assetSnapshot(
                        symbol = "USD",
                        balance = BigDecimal("500.00"),
                        price = BigDecimal.ONE,
                        valueUSD = BigDecimal("500.00"),
                        targetPercent = BigDecimal("50.0"),
                    ),
                ),
            )
            val afterEvents = inception.copy(
                timestamp = t1,
                balancesObservedAt = t1,
                assets = inception.assets + mapOf(
                    "BTC" to inception.assets.getValue("BTC").copy(
                        balance = BigDecimal("0.01100000"),
                        valueUSD = BigDecimal("550.00"),
                    ),
                    "USD" to inception.assets.getValue("USD").copy(
                        balance = BigDecimal("450.00"),
                        valueUSD = BigDecimal("450.00"),
                    ),
                ),
            )
            val later = afterEvents.copy(
                timestamp = t2,
                totalValueUSD = BigDecimal("1110.00"),
                balancesObservedAt = t2,
                assets = afterEvents.assets + mapOf(
                    "BTC" to afterEvents.assets.getValue("BTC").copy(
                        price = BigDecimal("60000.00"),
                        valueUSD = BigDecimal("660.00"),
                    ),
                ),
            )

            val result = calculate(
                snapshots = listOf(inception, afterEvents, later),
                trades = listOf(manualTrade(t1, "sell", "BTC", "0.01000000", "500.00")),
                rewards = listOf(
                    ledgerEvent(
                        timestamp = t1,
                        asset = "USD",
                        amount = "-550.00",
                        balance = "450.00",
                        ledgerId = "conversion-source-trade",
                        refid = "CONVERSION-TRADE",
                        type = KrakenApiConstants.LEDGER_TYPE_CONVERSION,
                        hasAuthoritativeFee = true,
                    ),
                    ledgerEvent(
                        timestamp = t1,
                        asset = "BTC",
                        amount = "0.01100000",
                        balance = "0.01100000",
                        ledgerId = "conversion-destination-trade",
                        refid = "CONVERSION-TRADE",
                        type = KrakenApiConstants.LEDGER_TYPE_CONVERSION,
                        hasAuthoritativeFee = true,
                    ),
                ),
                inceptionSnapshot = inception,
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal("50000.00"))),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("1000.00")
            result.points[2].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("1100.00")
            result.points[2].differenceUSD shouldBeEqualComparingTo BigDecimal("10.00")
        }

        "card withdrawal source-leg collision with target trade fails closed" {
            val t0 = Instant.parse("2026-06-01T12:00:00Z")
            val fundingTime = Instant.parse("2026-06-02T12:00:00Z")
            val collisionTime = fundingTime.plusSeconds(5)
            val receiveTime = fundingTime.plusSeconds(10)
            val t2 = Instant.parse("2026-06-03T12:00:00Z")
            val cardRef = "CARD-WITHDRAWAL-SOURCE-COLLISION"
            val inception = PortfolioSnapshot(
                timestamp = t0,
                totalValueUSD = BigDecimal("1000.00"),
                assets = mapOf(
                    "BTC" to assetSnapshot(
                        symbol = "BTC",
                        balance = BigDecimal("1.00000000"),
                        price = BigDecimal("100.00"),
                        valueUSD = BigDecimal("100.00"),
                        targetPercent = BigDecimal("50.0"),
                    ),
                    "USD" to assetSnapshot(
                        symbol = "USD",
                        balance = BigDecimal("900.00"),
                        price = BigDecimal.ONE,
                        valueUSD = BigDecimal("900.00"),
                        targetPercent = BigDecimal("50.0"),
                    ),
                ),
                actions = emptyList(),
                drawdownPercent = BigDecimal.ZERO,
                fiatDeploymentPercent = BigDecimal.ZERO,
                effectiveUsdTargetPercent = BigDecimal.ZERO,
                balancesObservedAt = t0,
            )
            val later = inception.copy(
                timestamp = t2,
                totalValueUSD = BigDecimal("900.00"),
                balancesObservedAt = t2,
                assets = inception.assets + mapOf(
                    "USD" to inception.assets.getValue("USD").copy(
                        balance = BigDecimal("800.00"),
                        valueUSD = BigDecimal("800.00"),
                    ),
                ),
            )
            val cardLedgers = listOf(
                ledgerEvent(
                    timestamp = fundingTime,
                    asset = "USD",
                    amount = "-100.00",
                    type = KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL,
                    refid = cardRef,
                    ledgerId = "card-withdrawal-funding",
                ),
                ledgerEvent(
                    timestamp = collisionTime,
                    asset = "BTC",
                    amount = "-0.10000000",
                    type = KrakenApiConstants.LEDGER_TYPE_SPEND,
                    refid = cardRef,
                    ledgerId = "card-withdrawal-spend",
                ),
                ledgerEvent(
                    timestamp = receiveTime,
                    asset = "USD",
                    amount = "10.00",
                    type = KrakenApiConstants.LEDGER_TYPE_RECEIVE,
                    refid = cardRef,
                    ledgerId = "card-withdrawal-receive",
                ),
            )
            val cardProvenance = SimpleFundingProvenanceResolver(
                withdrawals = listOf(
                    WithdrawStatusRecord(
                        refid = cardRef,
                        txid = "card-withdrawal-tx",
                        asset = "USD",
                        amount = BigDecimal("100.00"),
                        time = fundingTime,
                        status = "Success",
                        method = "Visa",
                    ),
                ),
            )

            val result = calculate(
                snapshots = listOf(inception, later),
                trades = listOf(manualTrade(collisionTime, "buy", "BTC", "0.10000000", "10.00")),
                rewards = cardLedgers,
                inceptionSnapshot = inception,
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal("100.00"))),
                provenanceResolver = cardProvenance,
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("900.00")
            result.points[1].rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("900.00")
            result.points[1].differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "owner withdrawal remains proportional when actual trading is disjoint" {
            val t0 = Instant.parse("2026-06-01T12:00:00Z")
            val tradeTime = Instant.parse("2026-06-02T12:00:00Z")
            val withdrawalTime = tradeTime.plusMillis(500)
            val t2 = Instant.parse("2026-06-03T12:00:00Z")
            val inception = PortfolioSnapshot(
                timestamp = t0,
                totalValueUSD = BigDecimal("1000.00"),
                assets = mapOf(
                    "BTC" to assetSnapshot(
                        symbol = "BTC",
                        balance = BigDecimal("3.00000000"),
                        price = BigDecimal("100.00"),
                        valueUSD = BigDecimal("300.00"),
                        targetPercent = BigDecimal("60.0"),
                    ),
                    "ETH" to assetSnapshot(
                        symbol = "ETH",
                        balance = BigDecimal("20.00000000"),
                        price = BigDecimal("20.00"),
                        valueUSD = BigDecimal("400.00"),
                        targetPercent = BigDecimal("40.0"),
                    ),
                    "MORPHO" to assetSnapshot(
                        symbol = "MORPHO",
                        balance = BigDecimal("300.00000000"),
                        price = BigDecimal.ONE,
                        valueUSD = BigDecimal("300.00"),
                        targetPercent = BigDecimal.ZERO,
                    ),
                    "USD" to assetSnapshot(
                        symbol = "USD",
                        balance = BigDecimal.ZERO,
                        price = BigDecimal.ONE,
                        valueUSD = BigDecimal.ZERO,
                        targetPercent = BigDecimal.ZERO,
                    ),
                ),
                actions = emptyList(),
                drawdownPercent = BigDecimal.ZERO,
                fiatDeploymentPercent = BigDecimal.ZERO,
                effectiveUsdTargetPercent = BigDecimal.ZERO,
                balancesObservedAt = t0,
            )
            val later = inception.copy(
                timestamp = t2,
                totalValueUSD = BigDecimal("950.00"),
                balancesObservedAt = t2,
                assets = inception.assets + mapOf(
                    "MORPHO" to inception.assets.getValue("MORPHO").copy(
                        balance = BigDecimal("200.00000000"),
                        valueUSD = BigDecimal("200.00"),
                    ),
                    "USD" to inception.assets.getValue("USD").copy(
                        balance = BigDecimal("50.00"),
                        valueUSD = BigDecimal("50.00"),
                    ),
                ),
            )

            val result = calculate(
                snapshots = listOf(inception, later),
                trades = listOf(manualTrade(tradeTime, "sell", "MORPHO", "100.00000000", "100.00")),
                rewards = listOf(
                    ledgerEvent(
                        timestamp = withdrawalTime,
                        asset = "USD",
                        amount = "-50.00",
                        type = KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL,
                    ),
                ),
                inceptionSnapshot = inception,
                priceProvider = mapPriceProvider(
                    mapOf("BTC" to BigDecimal("100.00"), "ETH" to BigDecimal("20.00"), "MORPHO" to BigDecimal.ONE),
                ),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("950.00")
        }

        "zero-balance target does not require a historical price" {
            val t0 = Instant.parse("2026-06-01T12:00:00Z")
            val t1 = Instant.parse("2026-06-02T12:00:00Z")
            val inceptionBaseline = PortfolioSnapshot(
                timestamp = t0,
                totalValueUSD = BigDecimal("1000.00"),
                assets = mapOf(
                    "BTC" to assetSnapshot(
                        symbol = "BTC",
                        balance = BigDecimal("3.00000000"),
                        price = BigDecimal("100.00"),
                        valueUSD = BigDecimal("300.00"),
                        targetPercent = BigDecimal("60.0"),
                    ),
                    "ETH" to assetSnapshot(
                        symbol = "ETH",
                        balance = BigDecimal.ZERO,
                        price = BigDecimal.ZERO,
                        valueUSD = BigDecimal.ZERO,
                        targetPercent = BigDecimal("40.0"),
                    ),
                    "MORPHO" to assetSnapshot(
                        symbol = "MORPHO",
                        balance = BigDecimal("700.00000000"),
                        price = BigDecimal.ONE,
                        valueUSD = BigDecimal("700.00"),
                        targetPercent = BigDecimal.ZERO,
                    ),
                ),
                actions = emptyList(),
                drawdownPercent = BigDecimal.ZERO,
                fiatDeploymentPercent = BigDecimal.ZERO,
                effectiveUsdTargetPercent = BigDecimal.ZERO,
            )
            val later = inceptionBaseline.copy(
                timestamp = t1,
                balancesObservedAt = t1,
                assets = inceptionBaseline.assets + mapOf(
                    "ETH" to inceptionBaseline.assets.getValue("ETH").copy(price = BigDecimal("40.00")),
                ),
            )
            val snapshots = listOf(inceptionBaseline, later)

            val missingPrice = calculate(snapshots, inceptionSnapshot = inceptionBaseline)
            missingPrice.availability shouldBe ComparisonAvailability.AVAILABLE
            missingPrice.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("1000.00")

            val sourceError = calculate(
                snapshots,
                inceptionSnapshot = inceptionBaseline,
                priceProvider = HistoricalPriceProvider { _, time ->
                    throw HistoricalPriceSourceException("unexpected", "historical source unavailable", time)
                },
            )
            sourceError.availability shouldBe ComparisonAvailability.AVAILABLE
            sourceError.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("1000.00")
        }

        "sale of an anchor-held asset does not change pure Buy & Hold" {
            val t0 = Instant.parse("2026-06-01T12:00:00Z")
            val t1 = Instant.parse("2026-06-02T12:00:00Z")
            val inceptionBaseline = PortfolioSnapshot(
                timestamp = t0,
                totalValueUSD = BigDecimal("1000.00"),
                assets = mapOf(
                    "BTC" to assetSnapshot(
                        symbol = "BTC",
                        balance = BigDecimal("3.00000000"),
                        price = BigDecimal("100.00"),
                        valueUSD = BigDecimal("300.00"),
                        targetPercent = BigDecimal("60.0"),
                    ),
                    "ETH" to assetSnapshot(
                        symbol = "ETH",
                        balance = BigDecimal("20.00000000"),
                        price = BigDecimal("20.00"),
                        valueUSD = BigDecimal("400.00"),
                        targetPercent = BigDecimal("40.0"),
                    ),
                    "USD" to assetSnapshot(
                        symbol = "USD",
                        balance = BigDecimal("0.00"),
                        price = BigDecimal.ONE,
                        valueUSD = BigDecimal.ZERO,
                        targetPercent = BigDecimal.ZERO,
                    ),
                    "MORPHO" to assetSnapshot(
                        symbol = "MORPHO",
                        balance = BigDecimal("300.00000000"),
                        price = BigDecimal.ONE,
                        valueUSD = BigDecimal("300.00"),
                        targetPercent = BigDecimal.ZERO,
                    ),
                ),
                actions = emptyList(),
                drawdownPercent = BigDecimal.ZERO,
                fiatDeploymentPercent = BigDecimal.ZERO,
                effectiveUsdTargetPercent = BigDecimal.ZERO,
            )
            val afterSale = inceptionBaseline.copy(
                timestamp = t1,
                assets = inceptionBaseline.assets + mapOf(
                    "MORPHO" to inceptionBaseline.assets.getValue("MORPHO").copy(
                        balance = BigDecimal.ZERO,
                        valueUSD = BigDecimal.ZERO,
                        price = BigDecimal.ONE,
                    ),
                    "USD" to inceptionBaseline.assets.getValue("USD").copy(
                        balance = BigDecimal("300.00"),
                        valueUSD = BigDecimal("300.00"),
                    ),
                ),
                balancesObservedAt = t1,
            )
            val sale = trade(
                timestamp = t0.plusSeconds(1800),
                side = "sell",
                symbol = "MORPHO",
                volume = "300.00000000",
                usdAmount = "300.00",
                source = TradeSource.MANUAL,
                cycleId = null,
                tradeId = "external-morpho-sale",
                orderTxid = "external-morpho-order",
                price = "1.00",
            )

            val result = calculate(
                snapshots = listOf(inceptionBaseline, afterSale),
                trades = listOf(sale),
                inceptionSnapshot = inceptionBaseline,
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points[0].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("1000.00")
            result.points[1].rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("1000.00")
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("1000.00")
            result.points[1].differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "later owner contributions use fixed original value weights" {
            val t0 = Instant.parse("2026-06-01T12:00:00Z")
            val t1 = Instant.parse("2026-06-02T12:00:00Z")
            val t2 = Instant.parse("2026-06-03T12:00:00Z")
            val inceptionBaseline = PortfolioSnapshot(
                timestamp = t0,
                totalValueUSD = BigDecimal("1000.00"),
                assets = mapOf(
                    "BTC" to assetSnapshot(
                        symbol = "BTC",
                        balance = BigDecimal("3.00000000"),
                        price = BigDecimal("100.00"),
                        valueUSD = BigDecimal("300.00"),
                        targetPercent = BigDecimal("60.0"),
                    ),
                    "ETH" to assetSnapshot(
                        symbol = "ETH",
                        balance = BigDecimal("20.00000000"),
                        price = BigDecimal("20.00"),
                        valueUSD = BigDecimal("400.00"),
                        targetPercent = BigDecimal("40.0"),
                    ),
                    "MORPHO" to assetSnapshot(
                        symbol = "MORPHO",
                        balance = BigDecimal("300.00000000"),
                        price = BigDecimal.ONE,
                        valueUSD = BigDecimal("300.00"),
                        targetPercent = BigDecimal.ZERO,
                    ),
                ),
                actions = emptyList(),
                drawdownPercent = BigDecimal.ZERO,
                fiatDeploymentPercent = BigDecimal.ZERO,
                effectiveUsdTargetPercent = BigDecimal.ZERO,
            )
            val contribution = ledgerEvent(
                timestamp = t0.plusSeconds(1800),
                asset = "BTC",
                amount = "1.00000000",
                type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
            )
            val afterContribution = inceptionBaseline.copy(
                timestamp = t1,
                totalValueUSD = BigDecimal("1100.00"),
                balancesObservedAt = t1,
                assets = inceptionBaseline.assets + mapOf(
                    "BTC" to inceptionBaseline.assets.getValue("BTC").copy(
                        balance = BigDecimal("4.00000000"),
                        valueUSD = BigDecimal("400.00"),
                    ),
                ),
            )
            val rally = afterContribution.copy(
                timestamp = t2,
                totalValueUSD = BigDecimal("1500.00"),
                balancesObservedAt = t2,
                assets = afterContribution.assets + mapOf(
                    "BTC" to afterContribution.assets.getValue("BTC").copy(
                        price = BigDecimal("200.00"),
                        valueUSD = BigDecimal("800.00"),
                    ),
                ),
            )

            val result = calculate(
                snapshots = listOf(inceptionBaseline, afterContribution, rally),
                rewards = listOf(contribution),
                inceptionSnapshot = inceptionBaseline,
                priceProvider = mapPriceProvider(
                    mapOf(
                        "BTC" to BigDecimal("100.00"),
                        "ETH" to BigDecimal("20.00"),
                        "MORPHO" to BigDecimal.ONE,
                    ),
                ),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            // The $100 contribution is allocated by the original 30/40/30 BTC/ETH/MORPHO split: $30
            // BTC, $40 ETH, $30 MORPHO. At t1 total is $1100.00. At t2 BTC doubles to $200, total is $1430.00.
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("1100.00")
            result.points[2].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("1430.00")
        }

        "same-timestamp owner contribution and target trade fail closed without sequence evidence" {
            val t0 = Instant.parse("2026-06-01T12:00:00Z")
            val t1 = Instant.parse("2026-06-02T12:00:00Z")
            val t2 = Instant.parse("2026-06-03T12:00:00Z")
            val inception = snapshot(
                t0,
                "1000.00",
                mapOf(
                    "BTC" to assetRow("0.01000000", "50000.00", "500.00"),
                    "USD" to assetRow("500.00", "1.0", "500.00"),
                ),
            ).copy(
                assets = mapOf(
                    "BTC" to assetSnapshot(
                        symbol = "BTC",
                        balance = BigDecimal("0.01000000"),
                        price = BigDecimal("50000.00"),
                        valueUSD = BigDecimal("500.00"),
                        targetPercent = BigDecimal("50.0"),
                    ),
                    "USD" to assetSnapshot(
                        symbol = "USD",
                        balance = BigDecimal("500.00"),
                        price = BigDecimal.ONE,
                        valueUSD = BigDecimal("500.00"),
                        targetPercent = BigDecimal("50.0"),
                    ),
                ),
            )
            val afterEvents = inception.copy(
                timestamp = t1,
                totalValueUSD = BigDecimal("1100.00"),
                balancesObservedAt = t1,
                assets = inception.assets + mapOf(
                    "BTC" to inception.assets.getValue("BTC").copy(
                        balance = BigDecimal("0.02100000"),
                        valueUSD = BigDecimal("1050.00"),
                    ),
                    "USD" to inception.assets.getValue("USD").copy(
                        balance = BigDecimal("50.00"),
                        valueUSD = BigDecimal("50.00"),
                    ),
                ),
            )
            val later = afterEvents.copy(
                timestamp = t2,
                totalValueUSD = BigDecimal("1310.00"),
                balancesObservedAt = t2,
                assets = afterEvents.assets + mapOf(
                    "BTC" to afterEvents.assets.getValue("BTC").copy(
                        price = BigDecimal("60000.00"),
                        valueUSD = BigDecimal("1260.00"),
                    ),
                ),
            )
            val result = calculate(
                snapshots = listOf(inception, afterEvents, later),
                trades = listOf(manualTrade(t1, "buy", "BTC", "0.01100000", "550.00")),
                rewards = listOf(
                    ledgerEvent(
                        timestamp = t1,
                        asset = "USD",
                        amount = "100.00",
                        type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    ),
                ),
                inceptionSnapshot = inception,
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal("50000.00"))),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("1100.00")
            result.points[2].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("1210.00")
            result.points[2].differenceUSD shouldBeEqualComparingTo BigDecimal("100.00")
        }

        "a configured-target value above the full inception total fails closed" {
            val t0 = now
            val t1 = now.plusSeconds(3600)
            val assets = mapOf(
                "BTC" to assetRow("0.005", "100000.00", "500.00"),
                "USD" to assetRow("500.00", "1.0", "500.00"),
            )
            val inceptionBaseline = snapshot(t0, "999.999", assets).copy(
                assets = mapOf(
                    "BTC" to assetSnapshot(
                        symbol = "BTC",
                        balance = BigDecimal("0.005"),
                        price = BigDecimal("100000.00"),
                        valueUSD = BigDecimal("500.00"),
                        targetPercent = BigDecimal("50.0"),
                    ),
                    "USD" to assetSnapshot(
                        symbol = "USD",
                        balance = BigDecimal("500.00"),
                        price = BigDecimal.ONE,
                        valueUSD = BigDecimal("500.00"),
                        targetPercent = BigDecimal("50.0"),
                    ),
                ),
            )

            val result = calculate(
                snapshots = listOf(snapshot(t0, "1050.00", assets), snapshot(t1, "1050.00", assets)),
                inceptionSnapshot = inceptionBaseline,
            )

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.BASELINE_MISMATCH
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

        "bare transfer fails closed with AMBIGUOUS_LEDGER_TYPE" {
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
            val s2 = snapshot(
                t2,
                "100500.00",
                mapOf(
                    "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                    "USD" to assetRow("50500.00", "1.0", "50500.00"),
                ),
            )

            val result = calculate(
                snapshots = listOf(s1, s2),
                trades = emptyList(),
                rewards = listOf(
                    ledgerEvent(
                        timestamp = tMid,
                        asset = "USD",
                        amount = "500.00",
                        type = KrakenApiConstants.LEDGER_TYPE_TRANSFER,
                    ),
                ),
                inceptionSnapshot = inceptionSnap,
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal("50000.00"))),
            )

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.AMBIGUOUS_LEDGER_TYPE
            result.unavailableAt shouldBe tMid
        }

        "funding provenance preparation failure stays explicit in comparison" {
            val t0 = Instant.parse("2026-06-01T12:00:00Z")
            val t1 = Instant.parse("2026-06-10T12:00:00Z")
            val tMid = t1.plusSeconds(1800)
            val t2 = Instant.parse("2026-06-10T13:00:00Z")
            val flatAssets = mapOf(
                "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                "USD" to assetRow("50000.00", "1.0", "50000.00"),
            )
            val s1 = snapshot(t1, "100000.00", flatAssets)
            val s2 = snapshot(
                t2,
                "100500.00",
                mapOf(
                    "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                    "USD" to assetRow("50500.00", "1.0", "50500.00"),
                ),
            )

            val result = calculate(
                snapshots = listOf(s1, s2),
                rewards = listOf(
                    ledgerEvent(
                        timestamp = tMid,
                        asset = "USD",
                        amount = "500.00",
                        type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    ),
                ),
                provenanceResolver = FundingProvenanceResolver.unavailable(
                    FundingProvenanceFailure(
                        reason = FundingProvenanceFailureReason.PERMISSION_DENIED,
                        message = "DepositStatus permission denied",
                    ),
                ),
            )

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.FUNDING_PROVENANCE_UNAVAILABLE
            result.unavailableAt shouldBe tMid
        }

        "pre-baseline funding preparation failure does not block the approved comparison" {
            val t0 = Instant.parse("2026-06-01T12:00:00Z")
            val t1 = t0.plusSeconds(3600)
            val snapshots = listOf(
                snapshot(t0, "100.00", mapOf("USD" to assetRow("100", "1", "100"))),
                snapshot(t1, "100.00", mapOf("USD" to assetRow("100", "1", "100"))),
            )
            val preBaselineFunding = ledgerEvent(
                timestamp = t0.minusSeconds(3600),
                asset = "USD",
                amount = "50",
                type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                ledgerId = "pre-baseline-funding-evidence",
            )
            val preparedLedgerIds = mutableListOf<String>()
            val resolver = object : FundingProvenanceResolver {
                override fun resolve(event: LedgerEvent): FundingEvidence = FundingEvidence.EXTERNAL

                override suspend fun prepare(events: Collection<LedgerEvent>): FundingProvenanceResolver {
                    preparedLedgerIds += events.map(LedgerEvent::ledgerId)
                    return if (events.any { it.ledgerId == preBaselineFunding.ledgerId }) {
                        FundingProvenanceResolver.unavailable(
                            FundingProvenanceFailure(
                                reason = FundingProvenanceFailureReason.REQUEST_FAILED,
                                message = "pre-baseline evidence unavailable",
                            ),
                        )
                    } else {
                        this
                    }
                }
            }

            val result = calculate(
                snapshots = snapshots,
                rewards = listOf(preBaselineFunding),
                provenanceResolver = resolver,
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            preparedLedgerIds shouldBe emptyList()
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

        "Non-positive funding amounts fail closed before moving the benchmark" {
            val t0 = Instant.parse("2026-06-01T12:00:00Z")
            val t1 = Instant.parse("2026-06-10T12:00:00Z")
            val tMid = Instant.parse("2026-06-10T18:00:00Z")
            val t2 = Instant.parse("2026-06-11T12:00:00Z")
            val flatAssets = mapOf(
                "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                "USD" to assetRow("50000.00", "1.0", "50000.00"),
            )
            val inceptionSnap = snapshot(t0, "100000.00", flatAssets)
            // A negative deposit is malformed at the ledger boundary and must not become a
            // negative owner contribution.
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

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE
        }

        "Malformed positive-amount withdrawal fails closed before moving the benchmark" {
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

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE
        }

        "Same-timestamp deposit and larger spend are not reclassified as a withdrawal" {
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
            // +3000 funding, -5000 spend at one instant has a negative net.
            // Keep the already-typed owner contribution and neutral spend
            // separate rather than inventing an owner withdrawal.
            val s2 = snapshot(
                t2,
                "108000.00",
                mapOf(
                    "BTC" to assetRow("1.0", "60000.00", "60000.00"),
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
                        refid = "NET-PLUMBING-1",
                    ),
                    ledgerEvent(
                        timestamp = tMid,
                        asset = "USD",
                        amount = "-5000.00",
                        type = KrakenApiConstants.LEDGER_TYPE_SPEND,
                        refid = "NET-PLUMBING-1",
                    ),
                ),
                inceptionSnapshot = inceptionSnap,
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal("50000.00"))),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("113300.00")
            result.points[1].differenceUSD shouldBeEqualComparingTo BigDecimal("-5300.00")
        }

        "Same-timestamp funding passthrough preserves a positive typed net contribution" {
            val t0 = Instant.parse("2026-06-01T12:00:00Z")
            val t1 = t0.plusSeconds(60)
            val t2 = t0.plusSeconds(120)
            val baselineAssets = mapOf(
                "BTC" to assetRow("0.00", "50000.00", "0.00"),
                "USD" to assetRow("10000.00", "1.0", "10000.00"),
            )
            val result = calculate(
                snapshots = listOf(
                    snapshot(t0, "10000.00", baselineAssets),
                    snapshot(
                        t2,
                        "15000.00",
                        mapOf(
                            "BTC" to assetRow("0.08", "50000.00", "4000.00"),
                            "USD" to assetRow("11000.00", "1.0", "11000.00"),
                        ),
                    ),
                ),
                rewards = listOf(
                    ledgerEvent(
                        timestamp = t1,
                        asset = "USD",
                        amount = "5000.00",
                        type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                        refid = "PLUMBING-PASSTHROUGH-1",
                    ),
                    ledgerEvent(
                        timestamp = t1,
                        asset = "USD",
                        amount = "-4000.00",
                        type = KrakenApiConstants.LEDGER_TYPE_SPEND,
                        refid = "PLUMBING-PASSTHROUGH-1",
                    ),
                    ledgerEvent(
                        timestamp = t1,
                        asset = "BTC",
                        amount = "0.08",
                        type = KrakenApiConstants.LEDGER_TYPE_RECEIVE,
                        refid = "PLUMBING-PASSTHROUGH-1",
                    ),
                ),
                inceptionSnapshot = snapshot(t0, "10000.00", baselineAssets),
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal("50000.00"))),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("15000.00")
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "Same-timestamp funding passthrough preserves a negative typed net withdrawal" {
            val t0 = Instant.parse("2026-06-01T12:00:00Z")
            val t1 = t0.plusSeconds(60)
            val t2 = t0.plusSeconds(120)
            val baselineAssets = mapOf(
                "BTC" to assetRow("0.00", "50000.00", "0.00"),
                "USD" to assetRow("10000.00", "1.0", "10000.00"),
            )
            val result = calculate(
                snapshots = listOf(
                    snapshot(t0, "10000.00", baselineAssets),
                    snapshot(
                        t2,
                        "9000.00",
                        mapOf(
                            "BTC" to assetRow("0.00", "50000.00", "0.00"),
                            "USD" to assetRow("9000.00", "1.0", "9000.00"),
                        ),
                    ),
                ),
                rewards = listOf(
                    ledgerEvent(
                        timestamp = t1,
                        asset = "USD",
                        amount = "-5000.00",
                        type = KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL,
                        refid = "ROUNDTRIP-WITHDRAWAL",
                    ),
                    ledgerEvent(
                        timestamp = t1,
                        asset = "USD",
                        amount = "4000.00",
                        type = KrakenApiConstants.LEDGER_TYPE_RECEIVE,
                        refid = "ROUNDTRIP-WITHDRAWAL",
                    ),
                ),
                inceptionSnapshot = snapshot(t0, "10000.00", baselineAssets),
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal("50000.00"))),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("9000.00")
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "passthrough source group spanning the comparison baseline fails closed" {
            val t0 = Instant.parse("2026-06-01T12:00:00Z")
            val t1 = t0.plusSeconds(60)
            val t2 = t0.plusSeconds(120)
            val baseline = snapshot(
                t0,
                "100.00",
                mapOf("USD" to assetRow("100.00", "1.0", "100.00")),
            )
            val result = calculate(
                snapshots = listOf(
                    baseline,
                    snapshot(t2, "100.00", mapOf("USD" to assetRow("100.00", "1.0", "100.00"))),
                ),
                rewards = listOf(
                    ledgerEvent(
                        timestamp = t0.minusSeconds(30),
                        asset = "USD",
                        amount = "-10.00",
                        type = KrakenApiConstants.LEDGER_TYPE_SPEND,
                        refid = "CROSS-BASELINE-PASSTHROUGH",
                    ),
                    ledgerEvent(
                        timestamp = t1,
                        asset = "BTC",
                        amount = "0.0002",
                        type = KrakenApiConstants.LEDGER_TYPE_RECEIVE,
                        refid = "CROSS-BASELINE-PASSTHROUGH",
                    ),
                ),
                inceptionSnapshot = baseline,
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal("50000.00"))),
            )

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.AMBIGUOUS_LEDGER_TYPE
            result.unavailableAt shouldBe t1
        }

        "same-timestamp plumbing with multiple funding legs stays separately typed" {
            val t0 = Instant.parse("2026-06-01T12:00:00Z")
            val t1 = t0.plusSeconds(60)
            val t2 = t0.plusSeconds(120)
            val baselineAssets = mapOf(
                "BTC" to assetRow("0.00", "50000.00", "0.00"),
                "USD" to assetRow("10000.00", "1.0", "10000.00"),
            )
            val result = calculate(
                snapshots = listOf(
                    snapshot(t0, "10000.00", baselineAssets),
                    snapshot(
                        t2,
                        "10050.00",
                        baselineAssets + (
                            "USD" to assetRow("10050.00", "1.0", "10050.00")
                            ),
                    ),
                ),
                rewards = listOf(
                    ledgerEvent(
                        timestamp = t1,
                        asset = "USD",
                        amount = "100.00",
                        type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                        refid = "MULTI-FUNDING-A",
                    ),
                    ledgerEvent(
                        timestamp = t1,
                        asset = "USD",
                        amount = "50.00",
                        type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                        refid = "MULTI-FUNDING-B",
                    ),
                    ledgerEvent(
                        timestamp = t1,
                        asset = "USD",
                        amount = "-100.00",
                        type = KrakenApiConstants.LEDGER_TYPE_SPEND,
                        refid = "MULTI-SPEND",
                    ),
                ),
                inceptionSnapshot = snapshot(t0, "10000.00", baselineAssets),
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal("50000.00"))),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("10150.00")
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal("-100.00")
        }

        "same-timestamp plumbing with a blank passthrough refid stays separately typed" {
            val t0 = Instant.parse("2026-06-01T12:00:00Z")
            val t1 = t0.plusSeconds(60)
            val t2 = t0.plusSeconds(120)
            val baselineAssets = mapOf(
                "BTC" to assetRow("0.00", "50000.00", "0.00"),
                "USD" to assetRow("10000.00", "1.0", "10000.00"),
            )
            val result = calculate(
                snapshots = listOf(
                    snapshot(t0, "10000.00", baselineAssets),
                    snapshot(t2, "10000.00", baselineAssets),
                ),
                rewards = listOf(
                    ledgerEvent(
                        timestamp = t1,
                        asset = "USD",
                        amount = "100.00",
                        type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                        refid = "BLANK-PASSTHROUGH-FUNDING",
                    ),
                    ledgerEvent(
                        timestamp = t1,
                        asset = "USD",
                        amount = "-100.00",
                        type = KrakenApiConstants.LEDGER_TYPE_SPEND,
                    ),
                ),
                inceptionSnapshot = snapshot(t0, "10000.00", baselineAssets),
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal("50000.00"))),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("10100.00")
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal("-100.00")
        }

        "Same-timestamp opposite plumbing legs without a shared identity stay separate" {
            val t0 = Instant.parse("2026-06-01T12:00:00Z")
            val t1 = t0.plusSeconds(60)
            val t2 = t0.plusSeconds(120)
            val result = calculate(
                snapshots = listOf(
                    snapshot(
                        t0,
                        "100000.00",
                        mapOf(
                            "BTC" to assetRow("1.0", "100000.00", "100000.00"),
                            "USD" to assetRow("0.00", "1.0", "0.00"),
                        ),
                    ),
                    snapshot(
                        t2,
                        "120040.00",
                        mapOf(
                            "BTC" to assetRow("1.0", "120000.00", "120000.00"),
                            "USD" to assetRow("40.00", "1.0", "40.00"),
                        ),
                    ),
                ),
                rewards = listOf(
                    ledgerEvent(
                        timestamp = t1,
                        asset = "USD",
                        amount = "100.00",
                        type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                        refid = "UNRELATED-DEPOSIT",
                    ),
                    ledgerEvent(
                        timestamp = t1,
                        asset = "USD",
                        amount = "-60.00",
                        type = KrakenApiConstants.LEDGER_TYPE_SPEND,
                        refid = "UNRELATED-SPEND",
                    ),
                ),
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal("100000.00"))),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            // The deposit is already recognized as owner capital and invested by inception
            // weights, so the basket never holds the deposited cash. The uncorrelated spend
            // therefore cannot draw the synthetic account negative; it stays a separate,
            // unmirrored movement instead of a phantom negative cash balance.
            result.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("120120.00")
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal("-80.00")
        }

        "a manual sale of a target asset the basket never held is not mirrored into a negative position" {
            val t0 = Instant.parse("2026-06-01T12:00:00Z")
            val t1 = t0.plusSeconds(60)
            val t2 = t0.plusSeconds(120)
            val t3 = t0.plusSeconds(180)
            val result = calculate(
                snapshots = listOf(
                    snapshot(
                        t0,
                        "10000.00",
                        mapOf(
                            "BTC" to assetRow("0.0", "100000.00", "0.00"),
                            "USD" to assetRow("10000.00", "1.0", "10000.00"),
                        ),
                    ),
                    snapshot(
                        t3,
                        "110000.00",
                        mapOf(
                            "BTC" to assetRow("0.0", "100000.00", "0.00"),
                            "USD" to assetRow("110000.00", "1.0", "110000.00"),
                        ),
                    ),
                ),
                trades = listOf(
                    manualTrade(t2, side = "sell", symbol = "BTC", volume = "1.0", usdAmount = "100000.00"),
                ),
                rewards = listOf(
                    ledgerEvent(
                        timestamp = t1,
                        asset = "BTC",
                        amount = "1.0",
                        type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                        refid = "BTC-DEPOSIT",
                    ),
                ),
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal("100000.00"))),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            // The deposited BTC is owner capital invested by inception weights (all USD), so the
            // basket never holds the deposited base. Mirroring the sale would create an impossible
            // negative position; the movement is skipped instead.
            result.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("110000.00")
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal("0.00")
        }

        "proportional attribution mirrors only the basket-held share of a movement" {
            val skipped = mutableMapOf("BTC" to BigDecimal("0.00"), "USD" to BigDecimal("0.00"))
            RebalancerComparisonCalculator.applyAttributedMovement(
                balances = skipped,
                deltas = mapOf("BTC" to BigDecimal("-1.0"), "USD" to BigDecimal("100.00")),
            )
            skipped.getValue("BTC") shouldBeEqualComparingTo BigDecimal("0.00")
            skipped.getValue("USD") shouldBeEqualComparingTo BigDecimal("0.00")

            val partial = mutableMapOf("BTC" to BigDecimal("0.5"), "USD" to BigDecimal("50.00"))
            RebalancerComparisonCalculator.applyAttributedMovement(
                balances = partial,
                deltas = mapOf("BTC" to BigDecimal("0.002"), "USD" to BigDecimal("-80.00")),
            )
            partial.getValue("BTC") shouldBeEqualComparingTo BigDecimal("0.50125")
            partial.getValue("USD") shouldBeEqualComparingTo BigDecimal("0.00")

            val full = mutableMapOf("BTC" to BigDecimal("0.5"), "USD" to BigDecimal("80.00"))
            RebalancerComparisonCalculator.applyAttributedMovement(
                balances = full,
                deltas = mapOf("BTC" to BigDecimal("0.002"), "USD" to BigDecimal("-80.00")),
            )
            full.getValue("BTC") shouldBeEqualComparingTo BigDecimal("0.502")
            full.getValue("USD") shouldBeEqualComparingTo BigDecimal("0.00")

            val creditsOnly = mutableMapOf("BTC" to BigDecimal("0.50"), "USD" to BigDecimal("0.00"))
            RebalancerComparisonCalculator.applyAttributedMovement(
                balances = creditsOnly,
                deltas = mapOf("BTC" to BigDecimal("0.25"), "USD" to BigDecimal("10.00")),
            )
            creditsOnly.getValue("BTC") shouldBeEqualComparingTo BigDecimal("0.75")
            creditsOnly.getValue("USD") shouldBeEqualComparingTo BigDecimal("10.00")
        }

        "fail-closed reasons still resolve without any recorded observations" {
            val coverageGap = calculate(
                snapshots = emptyList(),
                inceptionUnavailableReason = ComparisonUnavailableReason.HISTORICAL_COVERAGE_GAP,
            )
            coverageGap.availability shouldBe ComparisonAvailability.UNAVAILABLE
            coverageGap.unavailableReason shouldBe ComparisonUnavailableReason.HISTORICAL_COVERAGE_GAP
            coverageGap.points shouldBe emptyList()

            val truncated = calculate(snapshots = emptyList(), historyTruncated = true)
            truncated.unavailableReason shouldBe ComparisonUnavailableReason.INCEPTION_HISTORY_TRUNCATED

            val pruned = calculate(snapshots = emptyList(), knownInceptionTime = now)
            pruned.unavailableReason shouldBe ComparisonUnavailableReason.INCEPTION_SNAPSHOT_PRUNED
        }

        "Same-timestamp same-sign funding plumbing is never netted into owner capital" {
            val t0 = Instant.parse("2026-06-01T12:00:00Z")
            val t1 = t0.plusSeconds(60)
            val t2 = t0.plusSeconds(120)
            val result = calculate(
                snapshots = listOf(
                    snapshot(
                        t0,
                        "100000.00",
                        mapOf(
                            "BTC" to assetRow("1.0", "100000.00", "100000.00"),
                            "USD" to assetRow("0.00", "1.0", "0.00"),
                        ),
                    ),
                    snapshot(
                        t2,
                        "120150.00",
                        mapOf(
                            "BTC" to assetRow("1.0", "120000.00", "120000.00"),
                            "USD" to assetRow("150.00", "1.0", "150.00"),
                        ),
                    ),
                ),
                rewards = listOf(
                    ledgerEvent(
                        timestamp = t1,
                        asset = "USD",
                        amount = "100.00",
                        type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                        refid = "CREDIT-1",
                    ),
                    ledgerEvent(
                        timestamp = t1,
                        asset = "USD",
                        amount = "50.00",
                        type = KrakenApiConstants.LEDGER_TYPE_RECEIVE,
                        refid = "CREDIT-2",
                    ),
                ),
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal("100000.00"))),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("120120.00")
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal("30.00")
        }

        "explicit account-level independent credit may introduce an unheld asset" {
            val t0 = now
            val t1 = now.plusSeconds(3600)
            val result = calculate(
                snapshots = listOf(
                    snapshot(
                        t0,
                        "100.00",
                        mapOf(
                            "BTC" to assetRow("1.00", "100.00", "100.00"),
                            "ETH" to assetRow("0.00", "100.00", "0.00"),
                        ),
                    ),
                    snapshot(
                        t1,
                        "110.00",
                        mapOf(
                            "BTC" to assetRow("1.00", "100.00", "100.00"),
                            "ETH" to assetRow("0.10", "100.00", "10.00"),
                        ),
                    ),
                ),
                rewards = listOf(
                    ledgerEvent(
                        timestamp = t0.plusSeconds(1800),
                        asset = "ETH",
                        amount = "0.10",
                        type = KrakenApiConstants.LEDGER_TYPE_REWARD,
                        subtype = "welcomebonus",
                    ),
                ),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points[0].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100.00")
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("110.00")
            result.points[1].differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "off-series independent credit enters the actual wallet replay universe" {
            val t0 = now
            val creditTime = now.plusSeconds(1800)
            val t1 = now.plusSeconds(3600)
            val baseline = snapshot(t0, "100.00", mapOf("USD" to assetRow("100.00", "1.00", "100.00")))
            val after = snapshot(t1, "100.00", mapOf("USD" to assetRow("100.00", "1.00", "100.00")))
            val airdrop = ledgerEvent(
                timestamp = creditTime,
                asset = "XLM",
                amount = "10.00",
                type = KrakenApiConstants.LEDGER_TYPE_TRANSFER,
                subtype = "airdrop",
                ledgerId = "off-series-airdrop-credit",
            )

            val result = calculate(
                snapshots = listOf(baseline, after),
                rewards = listOf(airdrop),
                configuredAssetUniverse = setOf("USD"),
                priceProvider = mapPriceProvider(mapOf("XLM" to BigDecimal.ONE)),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points.last().rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("110.00")
            result.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("110.00")
        }

        "Same-timestamp reward and owner funding are additive and preserve parity" {
            val t0 = Instant.parse("2026-06-01T12:00:00Z")
            val t1 = t0.plusSeconds(60)
            val t2 = t0.plusSeconds(120)
            val baselineAssets = mapOf(
                "BTC" to assetRow("1.0", "100000.00", "100000.00"),
                "USD" to assetRow("0.00", "1.0", "0.00"),
            )
            val result = calculate(
                snapshots = listOf(
                    snapshot(t0, "100000.00", baselineAssets),
                    snapshot(
                        t2,
                        "120100.00",
                        mapOf(
                            "BTC" to assetRow("1.2", "100000.00", "120000.00"),
                            "USD" to assetRow("100.00", "1.0", "100.00"),
                        ),
                    ),
                ),
                rewards = listOf(
                    ledgerEvent(
                        timestamp = t1,
                        asset = "BTC",
                        amount = "0.2",
                        type = KrakenApiConstants.LEDGER_TYPE_STAKING,
                        ledgerId = "reward-same-time",
                    ),
                    ledgerEvent(
                        timestamp = t1,
                        asset = "USD",
                        amount = "100.00",
                        type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                        ledgerId = "owner-same-time",
                        refid = "FT-owner-same-time",
                    ),
                ),
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal("100000.00"))),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("120100.00")
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "Near-timestamp reward and owner funding are additive and preserve parity" {
            val t0 = Instant.parse("2026-06-01T12:00:00Z")
            val t1 = t0.plusSeconds(60)
            val t2 = t0.plusSeconds(120)
            val baselineAssets = mapOf(
                "BTC" to assetRow("1.0", "100000.00", "100000.00"),
                "USD" to assetRow("0.00", "1.0", "0.00"),
            )
            val result = calculate(
                snapshots = listOf(
                    snapshot(t0, "100000.00", baselineAssets),
                    snapshot(
                        t2,
                        "120100.00",
                        mapOf(
                            "BTC" to assetRow("1.2", "100000.00", "120000.00"),
                            "USD" to assetRow("100.00", "1.0", "100.00"),
                        ),
                    ),
                ),
                rewards = listOf(
                    ledgerEvent(
                        timestamp = t1,
                        asset = "BTC",
                        amount = "0.2",
                        type = KrakenApiConstants.LEDGER_TYPE_STAKING,
                        ledgerId = "reward-near-time",
                    ),
                    ledgerEvent(
                        timestamp = t1.plusMillis(500),
                        asset = "USD",
                        amount = "100.00",
                        type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                        ledgerId = "owner-near-time",
                        refid = "FT-owner-near-time",
                    ),
                ),
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal("100000.00"))),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("120100.00")
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "Near-timestamp bot trade does not make an owner withdrawal unavailable" {
            val t0 = Instant.parse("2026-06-01T12:00:00Z")
            val t1 = t0.plusSeconds(60)
            val t2 = t0.plusSeconds(120)
            val result = calculate(
                snapshots = listOf(
                    snapshot(
                        t0,
                        "100100.00",
                        mapOf(
                            "BTC" to assetRow("1.0", "100000.00", "100000.00"),
                            "USD" to assetRow("100.00", "1.0", "100.00"),
                        ),
                    ),
                    snapshot(
                        t2,
                        "100000.00",
                        mapOf(
                            "BTC" to assetRow("0.999", "100000.00", "99900.00"),
                            "USD" to assetRow("100.00", "1.0", "100.00"),
                        ),
                    ),
                ),
                trades = listOf(
                    trade(
                        timestamp = t1.plusMillis(500),
                        side = "SELL",
                        symbol = "BTC",
                        volume = "0.001",
                        usdAmount = "100.00",
                        price = "100000.00",
                    ),
                ),
                rewards = listOf(
                    ledgerEvent(
                        timestamp = t1,
                        asset = "USD",
                        amount = "-100.00",
                        type = KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL,
                        refid = "WITHDRAWAL-BOT-1",
                    ),
                ),
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal("100000.00"))),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100000.00")
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "Exactly one second between owner withdrawal and manual trade remains the ordering boundary" {
            val t0 = Instant.parse("2026-06-01T12:00:00Z")
            val t1 = t0.plusSeconds(60)
            val t2 = t0.plusSeconds(120)
            val result = calculate(
                snapshots = listOf(
                    snapshot(
                        t0,
                        "100100.00",
                        mapOf(
                            "BTC" to assetRow("1.0", "100000.00", "100000.00"),
                            "USD" to assetRow("100.00", "1.0", "100.00"),
                        ),
                    ),
                    snapshot(
                        t2,
                        "100000.00",
                        mapOf(
                            "BTC" to assetRow("0.999", "100000.00", "99900.00"),
                            "USD" to assetRow("100.00", "1.0", "100.00"),
                        ),
                    ),
                ),
                trades = listOf(
                    manualTrade(
                        timestamp = t1.plusSeconds(1),
                        side = "SELL",
                        symbol = "BTC",
                        volume = "0.001",
                        usdAmount = "100.00",
                    ),
                ),
                rewards = listOf(
                    ledgerEvent(
                        timestamp = t1,
                        asset = "USD",
                        amount = "-100.00",
                        type = KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL,
                        refid = "WITHDRAWAL-EXACT-1",
                    ),
                ),
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal("100000.00"))),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
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

        "Scenario S: source-proven stock dividend in USD is neutral" {
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
                    subtype = "cashdividend",
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
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("110000.00")
            result.latestDifferenceUSD!! shouldBeEqualComparingTo BigDecimal("5000.00")
        }

        "base-denominated ledger fee settles from the retained trade legs" {
            val t0 = now.minusSeconds(86400)
            val t1 = now
            val snapshots = listOf(
                snapshot(
                    t0,
                    "100000.00",
                    mapOf(
                        "BTC" to assetRow("1.0", "40000.00", "40000.00"),
                        "USD" to assetRow("60000.00", "1.0", "60000.00"),
                    ),
                ),
                snapshot(
                    t1,
                    "99996.00",
                    mapOf(
                        "BTC" to assetRow("0.8999", "40000.00", "35996.00"),
                        "USD" to assetRow("64000.00", "1.0", "64000.00"),
                    ),
                ),
            )
            // Kraken reports a quote-equivalent fee, but the retained legs show the fee was
            // charged in BTC; the wallet effect must come from the legs, not the TradeRecord.
            val trades = listOf(
                manualTrade(
                    timestamp = t1,
                    side = "sell",
                    symbol = "BTC",
                    volume = "0.1",
                    usdAmount = "4000.00",
                    fee = "4.0",
                    tradeId = "TKRAKEN1",
                ),
            )
            val ledgers = listOf(
                ledgerEvent(
                    timestamp = t1,
                    asset = "BTC",
                    amount = "-0.1001",
                    type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                    refid = "TKRAKEN1",
                    balance = "0.8999",
                ),
                ledgerEvent(
                    timestamp = t1,
                    asset = "USD",
                    amount = "4000.00",
                    type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                    refid = "TKRAKEN1",
                    balance = "64000.00",
                ),
            )

            val result = calculate(snapshots, trades, ledgers)

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points[1].rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("99996.00")
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100000.00")
            result.latestDifferenceUSD!! shouldBeEqualComparingTo BigDecimal("-4.00")
        }

        "UNKNOWN SELL trade does not affect pure Buy & Hold when balances reconcile" {
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

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100000.00")
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "UNKNOWN trade affecting only the tracked quote does not affect pure Buy & Hold" {
            val t0 = now
            val result = calculate(
                snapshots = listOf(
                    snapshot(
                        t0,
                        "100.00",
                        mapOf("USD" to assetRow("100.00", "1.0", "100.00")),
                    ),
                    snapshot(
                        t0.plusSeconds(3600),
                        "90.00",
                        mapOf("USD" to assetRow("90.00", "1.0", "90.00")),
                    ),
                ),
                trades = listOf(
                    trade(
                        timestamp = t0.plusSeconds(1800),
                        side = "BUY",
                        symbol = "DOGE",
                        volume = "1.0",
                        usdAmount = "10.00",
                        source = TradeSource.API_FILL,
                        cycleId = null,
                        tradeId = "UNKNOWN-QUOTE-ONLY",
                        orderTxid = null,
                    ),
                ),
                priceProvider = mapPriceProvider(mapOf("DOGE" to BigDecimal("10"))),
            )

            result.points.last().rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("100.00")
            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100.00")
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "UNKNOWN multi-fill order does not affect pure Buy & Hold when balances reconcile" {
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

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100000.00")
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
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

        "Scenario AA: Manual Buy Crypto with existing cash is ignored by pure Buy & Hold" {
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
            result.points[2].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("10000.00")
            result.points[2].differenceUSD shouldBeEqualComparingTo BigDecimal("1000.00")
            result.latestDifferenceUSD!! shouldBeEqualComparingTo BigDecimal("1000.00")
        }

        "linked spend and receive plumbing is consumed without changing pure Buy & Hold" {
            val refid = "LINKED-PASSTHROUGH-OUTSIDE-BASKET"
            val baseline = snapshot(
                now,
                "10000.00",
                mapOf("USD" to assetRow("10000.00", "1.0", "10000.00")),
            )
            val receive = ledgerEvent(
                timestamp = now.plusSeconds(1800),
                asset = "USD",
                amount = "75.61",
                type = KrakenApiConstants.LEDGER_TYPE_RECEIVE,
                refid = refid,
                ledgerId = "linked-receive",
            )
            val spend = ledgerEvent(
                timestamp = now.plusSeconds(1800),
                asset = "USDC",
                amount = "-75.63",
                type = KrakenApiConstants.LEDGER_TYPE_SPEND,
                refid = refid,
                ledgerId = "linked-spend",
            )
            val built = RebalancerComparisonCalculator.buildBenchmarkEventsForTest(
                ledgers = listOf(receive, spend),
                baseline = baseline,
                inceptionWeights = mapOf("USD" to BigDecimal.ONE),
                priceProvider = null,
                provenanceResolver = FundingProvenanceResolver.NONE,
            )
            built.filterIsInstance<BenchmarkEvent.ExternalBalance>() shouldBe emptyList()

            val result = calculate(
                snapshots = listOf(
                    baseline,
                    baseline.copy(
                        timestamp = now.plusSeconds(3600),
                        totalValueUSD = BigDecimal("10075.61"),
                        assets = mapOf(
                            "USD" to baseline.assets.getValue("USD").copy(
                                balance = BigDecimal("10075.61"),
                                valueUSD = BigDecimal("10075.61"),
                            ),
                        ),
                        balancesObservedAt = now.plusSeconds(3600),
                    ),
                ),
                rewards = listOf(receive, spend),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("10000.00")
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal("75.61")
        }

        "complete off-series dust sweep removes the converted asset from full-wallet actual NAV" {
            val refid = "OFF-SERIES-DUST-SWEEP"
            val baseline = snapshot(
                now,
                "10000.00",
                mapOf("USD" to assetRow("10000.00", "1.0", "10000.00")),
            )
            val afterReward = baseline.copy(
                timestamp = now.plusSeconds(3600),
                balancesObservedAt = now.plusSeconds(3600),
            )
            val afterSweep = baseline.copy(
                timestamp = now.plusSeconds(7200),
                totalValueUSD = BigDecimal("10005.00"),
                assets = mapOf(
                    "USD" to baseline.assets.getValue("USD").copy(
                        balance = BigDecimal("10005.00"),
                        valueUSD = BigDecimal("10005.00"),
                    ),
                ),
                balancesObservedAt = now.plusSeconds(7200),
            )
            val result = calculate(
                snapshots = listOf(baseline, afterReward, afterSweep),
                rewards = listOf(
                    ledgerEvent(
                        timestamp = now.plusSeconds(1800),
                        asset = "BABY",
                        amount = "10",
                        type = KrakenApiConstants.LEDGER_TYPE_STAKING,
                        balance = "10",
                        ledgerId = "off-series-baby-reward",
                    ),
                    ledgerEvent(
                        timestamp = now.plusSeconds(5400),
                        asset = "BABY",
                        amount = "-10",
                        type = KrakenApiConstants.LEDGER_TYPE_SPEND,
                        subtype = "dustsweeping",
                        refid = refid,
                        ledgerId = "off-series-baby-sweep",
                    ),
                    ledgerEvent(
                        timestamp = now.plusSeconds(5400),
                        asset = "USD",
                        amount = "5",
                        type = KrakenApiConstants.LEDGER_TYPE_RECEIVE,
                        subtype = "dustsweeping",
                        balance = "10005",
                        refid = refid,
                        ledgerId = "off-series-usd-receive",
                    ),
                ),
                priceProvider = mapPriceProvider(mapOf("BABY" to BigDecimal.ONE)),
                configuredAssetUniverse = setOf("USD"),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points[1].rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("10010.00")
            result.points.last().rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("10005.00")
            result.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("10000.00")
        }

        "incomplete linked passthrough groups remain unavailable" {
            val baseline = snapshot(
                now,
                "10000.00",
                mapOf("USD" to assetRow("10000.00", "1.0", "10000.00")),
            )
            val refid = "INCOMPLETE-PASSTHROUGH"
            val result = calculate(
                snapshots = listOf(
                    baseline,
                    baseline.copy(timestamp = now.plusSeconds(3600), balancesObservedAt = now.plusSeconds(3600)),
                ),
                rewards = listOf(
                    ledgerEvent(
                        timestamp = now.plusSeconds(1800),
                        asset = "USDC",
                        amount = "-75.63",
                        type = KrakenApiConstants.LEDGER_TYPE_SPEND,
                        ledgerId = "incomplete-spend-1",
                        refid = refid,
                    ),
                    ledgerEvent(
                        timestamp = now.plusSeconds(1800),
                        asset = "USDC",
                        amount = "-0.01",
                        type = KrakenApiConstants.LEDGER_TYPE_SPEND,
                        ledgerId = "incomplete-spend-2",
                        refid = refid,
                    ),
                ),
            )

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.AMBIGUOUS_LEDGER_TYPE
            result.unavailableAt shouldBe now.plusSeconds(1800)
        }

        "Scenario BB: confirmed card Buy Crypto applies only inception-weighted net owner contribution" {
            val cardRef = "CARD-BUY-2026-07-01T1230Z"
            val cardTime = now.plusSeconds(1800)
            val snapshots = listOf(
                snapshot(
                    now,
                    "50000.00",
                    mapOf(
                        "BTC" to assetRow("0.50", "50000.00", "25000.00"),
                        "USD" to assetRow("25000.00", "1.0", "25000.00"),
                    ),
                ),
                snapshot(
                    cardTime.plusSeconds(1),
                    "54980.00",
                    mapOf(
                        "BTC" to assetRow("0.5996", "50000.00", "29980.00"),
                        "USD" to assetRow("25000.00", "1.0", "25000.00"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(3600),
                    "60976.00",
                    mapOf(
                        "BTC" to assetRow("0.5996", "60000.00", "35976.00"),
                        "USD" to assetRow("25000.00", "1.0", "25000.00"),
                    ),
                ),
            )
            val ledgers = listOf(
                ledgerEvent(
                    timestamp = cardTime,
                    asset = "USD",
                    amount = "5000.00",
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    refid = cardRef,
                ),
                ledgerEvent(
                    timestamp = cardTime,
                    asset = "USD",
                    amount = "-4980.00",
                    fee = "20.00",
                    type = KrakenApiConstants.LEDGER_TYPE_SPEND,
                    refid = cardRef,
                ),
                ledgerEvent(
                    timestamp = cardTime,
                    asset = "BTC",
                    amount = "0.0996",
                    type = KrakenApiConstants.LEDGER_TYPE_RECEIVE,
                    refid = cardRef,
                ),
            )
            val provenance = SimpleFundingProvenanceResolver(
                deposits = listOf(
                    DepositStatusRecord(
                        refid = cardRef,
                        txid = "card-deposit-tx-20260701",
                        asset = "USD",
                        amount = BigDecimal("5000.00"),
                        time = cardTime,
                        status = "Success",
                        method = "Visa",
                    ),
                ),
            )

            val result = calculate(
                snapshots = snapshots,
                rewards = ledgers,
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal("50000.00"))),
                provenanceResolver = provenance,
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            // The card funding is a real $5,000 owner contribution with a $20
            // spend fee ($4,980 net investable capital). The benchmark invests
            // it strictly by original inception weights (50/50: $2,490 BTC / $2,490 USD),
            // and the actual spend and receive legs are consumed as plumbing evidence
            // without being replayed into B&H.
            result.points[1].rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("54980.00")
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("54980.00")
            result.points[1].differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
            result.points[2].rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("60976.00")
            result.points[2].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("60478.00")
            result.points[2].differenceUSD shouldBeEqualComparingTo BigDecimal("498.00")

            val builtEvents = RebalancerComparisonCalculator.buildBenchmarkEventsForTest(
                ledgers = ledgers,
                baseline = snapshots.first(),
                inceptionWeights = mapOf("BTC" to BigDecimal("0.5"), "USD" to BigDecimal("0.5")),
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal("50000.00"))),
                provenanceResolver = provenance,
            )
            builtEvents.filterIsInstance<BenchmarkEvent.ExternalBalance>() shouldBe emptyList()
            val contribution = builtEvents.filterIsInstance<BenchmarkEvent.OwnerContribution>().single()
            contribution.contributionUsd shouldBeEqualComparingTo BigDecimal("4980.00")
            contribution.sourceLedgerIds shouldContainExactlyInAnyOrder ledgers.map { it.ledgerId }

            val variedLegTimes = ledgers.mapIndexed { index, ledger ->
                if (index == 2) ledger.copy(time = cardTime.plusSeconds(1)) else ledger
            }
            val variedContribution = RebalancerComparisonCalculator.buildBenchmarkEventsForTest(
                ledgers = variedLegTimes,
                baseline = snapshots.first(),
                inceptionWeights = mapOf("BTC" to BigDecimal("0.5"), "USD" to BigDecimal("0.5")),
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal("50000.00"))),
                provenanceResolver = provenance,
            ).filterIsInstance<BenchmarkEvent.OwnerContribution>().single()
            variedContribution.sourceEventTimestamps shouldBe setOf(cardTime, cardTime.plusSeconds(1))
        }

        "a lone card deposit contributes its net balance once to buy and hold" {
            val loneRef = "LONE-PAYPAL-2026-07-01T1230Z"
            val loneTime = now.plusSeconds(1800)
            val ledgers = listOf(
                ledgerEvent(
                    timestamp = loneTime,
                    asset = "USD",
                    amount = "50.00",
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    refid = loneRef,
                    fee = "1.03",
                    ledgerId = "lone-card-deposit",
                ),
            )
            val provenance = SimpleFundingProvenanceResolver(
                deposits = listOf(
                    DepositStatusRecord(
                        refid = loneRef,
                        txid = "lone-card-tx",
                        asset = "USD",
                        amount = BigDecimal("50.00"),
                        fee = BigDecimal("1.03"),
                        time = loneTime,
                        status = "Success",
                        method = "PayPal",
                    ),
                ),
            )

            val builtEvents = RebalancerComparisonCalculator.buildBenchmarkEventsForTest(
                ledgers = ledgers,
                baseline = snapshot(
                    now,
                    "1000.00",
                    mapOf("USD" to assetRow("1000.00", "1.0", "1000.00")),
                ),
                inceptionWeights = mapOf("USD" to BigDecimal("1.0")),
                priceProvider = mapPriceProvider(emptyMap()),
                provenanceResolver = provenance,
            )

            builtEvents.filterIsInstance<BenchmarkEvent.ExternalBalance>() shouldBe emptyList()
            val contribution = builtEvents.filterIsInstance<BenchmarkEvent.OwnerContribution>().single()
            contribution.contributionUsd shouldBeEqualComparingTo BigDecimal("48.97")
            contribution.sourceLedgerIds shouldContainExactlyInAnyOrder listOf("lone-card-deposit")
        }

        "an out-of-universe XLM owner-capital deposit is valued into benchmark capital without an XLM weight" {
            val depTime = now.plusSeconds(600)
            val xlmRef = "XLM-EXTERNAL-DEPOSIT-2026-07-01T1210Z"
            val ledgers = listOf(
                ledgerEvent(
                    timestamp = depTime,
                    asset = "XLM",
                    amount = "820.770368",
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    refid = xlmRef,
                    ledgerId = "xlm-external-deposit",
                ),
            )
            val provenance = SimpleFundingProvenanceResolver(
                deposits = listOf(
                    DepositStatusRecord(
                        refid = xlmRef,
                        txid = "xlm-external-deposit-tx",
                        asset = "XLM",
                        amount = BigDecimal("820.770368"),
                        time = depTime,
                        status = "Success",
                        method = "Cryptocurrency",
                    ),
                ),
            )

            val builtEvents = RebalancerComparisonCalculator.buildBenchmarkEventsForTest(
                ledgers = ledgers,
                baseline = snapshot(
                    now,
                    "50000.00",
                    mapOf(
                        "BTC" to assetRow("0.50", "50000.00", "25000.00"),
                        "USD" to assetRow("25000.00", "1.0", "25000.00"),
                    ),
                ),
                inceptionWeights = mapOf("BTC" to BigDecimal("0.5"), "USD" to BigDecimal("0.5")),
                priceProvider = mapPriceProvider(
                    mapOf("XLM" to BigDecimal("0.239635"), "BTC" to BigDecimal("50000.00")),
                ),
                provenanceResolver = provenance,
            )

            // The out-of-universe deposit becomes synthetic capital allocated by fixed anchor
            // weights, not a direct holding in the deposited asset.
            builtEvents.filterIsInstance<BenchmarkEvent.ExternalBalance>() shouldBe emptyList()
            val contribution = builtEvents.filterIsInstance<BenchmarkEvent.OwnerContribution>().single()
            contribution.contributionUsd shouldBeEqualComparingTo BigDecimal("196.68530713568")
            contribution.sourceLedgerIds shouldContainExactlyInAnyOrder listOf("xlm-external-deposit")
            // The contribution is funded only into the configured benchmark targets.
            contribution.allocations.keys shouldBe setOf("BTC", "USD")
        }

        "stablecoin owner-capital deposits use their historical USD rate instead of assuming one dollar" {
            val depTime = now.plusSeconds(900)
            val usdtRef = "USDT-EXTERNAL-DEPOSIT-2026-07-01T1215Z"
            val usdcRef = "USDC-EXTERNAL-DEPOSIT-2026-07-01T1215Z"
            val ledgers = listOf(
                ledgerEvent(
                    timestamp = depTime,
                    asset = "USDT",
                    amount = "2754.929533",
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    refid = usdtRef,
                    ledgerId = "usdt-external-deposit",
                ),
                ledgerEvent(
                    timestamp = depTime.plusSeconds(1),
                    asset = "USDC",
                    amount = "492.34",
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    refid = usdcRef,
                    ledgerId = "usdc-external-deposit",
                ),
            )
            val provenance = SimpleFundingProvenanceResolver(
                deposits = listOf(
                    DepositStatusRecord(
                        refid = usdtRef,
                        txid = "usdt-external-deposit-tx",
                        asset = "USDT",
                        amount = BigDecimal("2754.929533"),
                        time = depTime,
                        status = "Success",
                        method = "Cryptocurrency",
                    ),
                    DepositStatusRecord(
                        refid = usdcRef,
                        txid = "usdc-external-deposit-tx",
                        asset = "USDC",
                        amount = BigDecimal("492.34"),
                        time = depTime.plusSeconds(1),
                        status = "Success",
                        method = "Cryptocurrency",
                    ),
                ),
            )

            val builtEvents = RebalancerComparisonCalculator.buildBenchmarkEventsForTest(
                ledgers = ledgers,
                baseline = snapshot(
                    now,
                    "50000.00",
                    mapOf(
                        "BTC" to assetRow("0.50", "50000.00", "25000.00"),
                        "USD" to assetRow("25000.00", "1.0", "25000.00"),
                    ),
                ),
                inceptionWeights = mapOf("BTC" to BigDecimal("0.5"), "USD" to BigDecimal("0.5")),
                priceProvider = mapPriceProvider(
                    mapOf(
                        "USDT" to BigDecimal("0.9992"),
                        "USDC" to BigDecimal("0.9998"),
                        "BTC" to BigDecimal("50000.00"),
                    ),
                ),
                provenanceResolver = provenance,
            )

            val contributions = builtEvents.filterIsInstance<BenchmarkEvent.OwnerContribution>()
            contributions.size shouldBe 2
            val byAsset = contributions.associateBy { it.event.asset }
            byAsset.getValue("USDT").contributionUsd shouldBeEqualComparingTo BigDecimal("2752.7255893736")
            byAsset.getValue("USDC").contributionUsd shouldBeEqualComparingTo BigDecimal("492.241532")
            contributions.forEach { contribution ->
                contribution.allocations.keys shouldBe setOf("BTC", "USD")
            }
        }

        "out-of-universe funding is allocated by fixed anchor weights" {
            val depTime = now.plusSeconds(1200)
            val xlmRef = "XLM-OUT-OF-UNIVERSE-2026-07-01T1220Z"
            val usdtRef = "USDT-OUT-OF-UNIVERSE-2026-07-01T1220Z"
            val ledgers = listOf(
                ledgerEvent(
                    timestamp = depTime,
                    asset = "XLM",
                    amount = "100.0",
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    refid = xlmRef,
                    ledgerId = "xlm-out-of-universe",
                ),
                ledgerEvent(
                    timestamp = depTime.plusSeconds(1),
                    asset = "USDT",
                    amount = "250.0",
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    refid = usdtRef,
                    ledgerId = "usdt-out-of-universe",
                ),
            )
            val provenance = SimpleFundingProvenanceResolver(
                deposits = listOf(
                    DepositStatusRecord(
                        refid = xlmRef,
                        txid = "xlm-out-of-universe-tx",
                        asset = "XLM",
                        amount = BigDecimal("100.0"),
                        time = depTime,
                        status = "Success",
                        method = "Cryptocurrency",
                    ),
                    DepositStatusRecord(
                        refid = usdtRef,
                        txid = "usdt-out-of-universe-tx",
                        asset = "USDT",
                        amount = BigDecimal("250.0"),
                        time = depTime.plusSeconds(1),
                        status = "Success",
                        method = "Cryptocurrency",
                    ),
                ),
            )

            val builtEvents = RebalancerComparisonCalculator.buildBenchmarkEventsForTest(
                ledgers = ledgers,
                baseline = snapshot(
                    now,
                    "50000.00",
                    mapOf(
                        "BTC" to assetRow("0.50", "50000.00", "25000.00"),
                        "USD" to assetRow("25000.00", "1.0", "25000.00"),
                    ),
                ),
                inceptionWeights = mapOf("BTC" to BigDecimal("0.5"), "USD" to BigDecimal("0.5")),
                priceProvider = mapPriceProvider(
                    mapOf("XLM" to BigDecimal("0.25"), "USDT" to BigDecimal("1.0"), "BTC" to BigDecimal("50000.00")),
                ),
                provenanceResolver = provenance,
            )

            builtEvents.filterIsInstance<BenchmarkEvent.ExternalBalance>() shouldBe emptyList()
            val contributions = builtEvents.filterIsInstance<BenchmarkEvent.OwnerContribution>()
            contributions.size shouldBe 2
            contributions.flatMap { it.allocations.keys }.toSet() shouldBe setOf("BTC", "USD")
        }

        "each owner-capital contribution from an out-of-universe asset is counted exactly once" {
            val depTime = now.plusSeconds(1500)
            val firstRef = "XLM-ONCE-DEPOSIT-A-2026-07-01"
            val secondRef = "XLM-ONCE-DEPOSIT-B-2026-07-01"
            val ledgers = listOf(
                ledgerEvent(
                    timestamp = depTime,
                    asset = "XLM",
                    amount = "100.0",
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    refid = firstRef,
                    ledgerId = "xlm-once-deposit-a",
                ),
                ledgerEvent(
                    timestamp = depTime.plusSeconds(2),
                    asset = "XLM",
                    amount = "300.0",
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    refid = secondRef,
                    ledgerId = "xlm-once-deposit-b",
                ),
            )
            val provenance = SimpleFundingProvenanceResolver(
                deposits = listOf(
                    DepositStatusRecord(
                        refid = firstRef,
                        txid = "xlm-once-tx-a",
                        asset = "XLM",
                        amount = BigDecimal("100.0"),
                        time = depTime,
                        status = "Success",
                        method = "Cryptocurrency",
                    ),
                    DepositStatusRecord(
                        refid = secondRef,
                        txid = "xlm-once-tx-b",
                        asset = "XLM",
                        amount = BigDecimal("300.0"),
                        time = depTime.plusSeconds(2),
                        status = "Success",
                        method = "Cryptocurrency",
                    ),
                ),
            )

            val builtEvents = RebalancerComparisonCalculator.buildBenchmarkEventsForTest(
                ledgers = ledgers,
                baseline = snapshot(
                    now,
                    "50000.00",
                    mapOf(
                        "BTC" to assetRow("0.50", "50000.00", "25000.00"),
                        "USD" to assetRow("25000.00", "1.0", "25000.00"),
                    ),
                ),
                inceptionWeights = mapOf("BTC" to BigDecimal("0.5"), "USD" to BigDecimal("0.5")),
                priceProvider = mapPriceProvider(mapOf("XLM" to BigDecimal("0.25"), "BTC" to BigDecimal("50000.00"))),
                provenanceResolver = provenance,
            )

            builtEvents.filterIsInstance<BenchmarkEvent.ExternalBalance>() shouldBe emptyList()
            val contributions = builtEvents.filterIsInstance<BenchmarkEvent.OwnerContribution>()
            contributions.size shouldBe 2
            val total = contributions.fold(BigDecimal.ZERO) { acc, contribution -> acc + contribution.contributionUsd }
            // 100 XLM + 300 XLM, each valued once at 0.25 USD.
            total shouldBeEqualComparingTo BigDecimal("100.0000")
        }

        "an owner withdrawal from an out-of-universe asset without a known starting balance fails closed" {
            val wdTime = now.plusSeconds(3600)
            val wdRef = "XLM-EXTERNAL-WITHDRAWAL-2026-07-01T1300Z"
            val snapshots = listOf(
                snapshot(
                    now,
                    "50000.00",
                    mapOf(
                        "BTC" to assetRow("0.50", "50000.00", "25000.00"),
                        "USD" to assetRow("25000.00", "1.0", "25000.00"),
                    ),
                ),
                snapshot(
                    wdTime.plusSeconds(60),
                    "50000.00",
                    mapOf(
                        "BTC" to assetRow("0.50", "50000.00", "25000.00"),
                        "USD" to assetRow("25000.00", "1.0", "25000.00"),
                    ),
                ),
            )
            val ledgers = listOf(
                ledgerEvent(
                    timestamp = wdTime,
                    asset = "XLM",
                    amount = "-410.0",
                    type = KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL,
                    refid = wdRef,
                    ledgerId = "xlm-external-withdrawal",
                ),
            )
            val provenance = SimpleFundingProvenanceResolver(
                withdrawals = listOf(
                    WithdrawStatusRecord(
                        refid = wdRef,
                        txid = "xlm-external-withdrawal-tx",
                        asset = "XLM",
                        amount = BigDecimal("410.0"),
                        time = wdTime,
                        status = "Success",
                        method = "Cryptocurrency",
                    ),
                ),
            )

            val result = calculate(
                snapshots = snapshots,
                rewards = ledgers,
                priceProvider = mapPriceProvider(mapOf("XLM" to BigDecimal("0.24"), "BTC" to BigDecimal("50000.00"))),
                provenanceResolver = provenance,
            )

            // The database establishes a 410 XLM withdrawal but gives no anchor or prior
            // checkpoint for the source balance. Applying it to B&H while actual snapshots
            // omit the asset would compare different wallet scopes.
            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE
            result.unavailableAt shouldBe wdTime
            val builtEvents = RebalancerComparisonCalculator.buildBenchmarkEventsForTest(
                ledgers = ledgers,
                baseline = snapshots.first(),
                inceptionWeights = mapOf("BTC" to BigDecimal("0.5"), "USD" to BigDecimal("0.5")),
                priceProvider = mapPriceProvider(mapOf("XLM" to BigDecimal("0.24"), "BTC" to BigDecimal("50000.00"))),
                provenanceResolver = provenance,
            )
            val withdrawal = builtEvents.filterIsInstance<BenchmarkEvent.OwnerWithdrawal>().single()
            withdrawal.withdrawalUsd shouldBeEqualComparingTo BigDecimal("98.4000")
        }

        "an unpriceable out-of-universe owner contribution still fails closed" {
            val depTime = now.plusSeconds(600)
            val xlmRef = "XLM-UNPRICEABLE-DEPOSIT-2026-07-01T1210Z"
            val snapshots = listOf(
                snapshot(now, "50000.00", mapOf("BTC" to assetRow("0.50", "50000.00", "25000.00"))),
                snapshot(
                    depTime.plusSeconds(60),
                    "50000.00",
                    mapOf("BTC" to assetRow("0.50", "50000.00", "25000.00")),
                ),
            )
            val ledgers = listOf(
                ledgerEvent(
                    timestamp = depTime,
                    asset = "XLM",
                    amount = "820.770368",
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    refid = xlmRef,
                    ledgerId = "xlm-unpriceable-deposit",
                ),
            )
            val provenance = SimpleFundingProvenanceResolver(
                deposits = listOf(
                    DepositStatusRecord(
                        refid = xlmRef,
                        txid = "xlm-unpriceable-tx",
                        asset = "XLM",
                        amount = BigDecimal("820.770368"),
                        time = depTime,
                        status = "Success",
                        method = "Cryptocurrency",
                    ),
                ),
            )

            val result = calculate(
                snapshots = snapshots,
                rewards = ledgers,
                priceProvider = mapPriceProvider(emptyMap()),
                provenanceResolver = provenance,
            )

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.MISSING_PRICE
            result.unavailableAt shouldBe depTime
        }

        "a historical price source outage is not persisted as missing price evidence" {
            val depTime = now.plusSeconds(600)
            val xlmRef = "XLM-SOURCE-OUTAGE-DEPOSIT-2026-07-01T1210Z"
            val snapshots = listOf(
                snapshot(now, "50000.00", mapOf("BTC" to assetRow("0.50", "50000.00", "25000.00"))),
                snapshot(
                    depTime.plusSeconds(60),
                    "50000.00",
                    mapOf("BTC" to assetRow("0.50", "50000.00", "25000.00")),
                ),
            )
            val ledger = ledgerEvent(
                timestamp = depTime,
                asset = "XLM",
                amount = "820.770368",
                type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                refid = xlmRef,
                ledgerId = "xlm-source-outage-deposit",
            )
            val provenance = SimpleFundingProvenanceResolver(
                deposits = listOf(
                    DepositStatusRecord(
                        refid = xlmRef,
                        txid = "xlm-source-outage-tx",
                        asset = "XLM",
                        amount = BigDecimal("820.770368"),
                        time = depTime,
                        status = "Success",
                        method = "Cryptocurrency",
                    ),
                ),
            )

            val result = calculate(
                snapshots = snapshots,
                rewards = listOf(ledger),
                priceProvider = HistoricalPriceProvider { _, timestamp ->
                    throw HistoricalPriceSourceException("XLM", "source unavailable", timestamp)
                },
                provenanceResolver = provenance,
            )

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.HISTORICAL_PRICE_SOURCE_ERROR
            result.unavailableAt shouldBe depTime
        }

        "an orphan trade ledger group with no retained fill fails closed at its ledger time" {
            val orphanTime = now.plusSeconds(900)
            val snapshots = listOf(
                snapshot(now, "1000.00", mapOf("USD" to assetRow("1000.00", "1.00", "1000.00"))),
                snapshot(
                    orphanTime.plusSeconds(60),
                    "1000.00",
                    mapOf("USD" to assetRow("1000.00", "1.00", "1000.00")),
                ),
            )
            val orphan = ledgerEvent(
                timestamp = orphanTime,
                asset = Asset.BTC,
                amount = "0.01",
                type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                ledgerId = "orphan-trade-ledger",
                refid = "orphan-trade-refid",
            )

            val result = calculate(snapshots = snapshots, rewards = listOf(orphan))

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE
            result.unavailableAt shouldBe orphanTime
        }

        "Scenario BB: card Buy Crypto legs with sub-second and several-second offsets are linked within 120s" {
            val cardRef = "CARD-BUY-OFFSET-2026-07-01"
            val cardTime = now.plusSeconds(1800)
            val snapshots = listOf(
                snapshot(
                    now,
                    "50000.00",
                    mapOf(
                        "BTC" to assetRow("0.50", "50000.00", "25000.00"),
                        "USD" to assetRow("25000.00", "1.0", "25000.00"),
                    ),
                ),
                snapshot(
                    cardTime.plusSeconds(60),
                    "54980.00",
                    mapOf(
                        "BTC" to assetRow("0.5996", "50000.00", "29980.00"),
                        "USD" to assetRow("25000.00", "1.0", "25000.00"),
                    ),
                ),
            )
            // Deposit at T, spend at T+350ms, receive at T+45s (all within 120s)
            val ledgers = listOf(
                ledgerEvent(
                    timestamp = cardTime,
                    asset = "USD",
                    amount = "5000.00",
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    refid = cardRef,
                ),
                ledgerEvent(
                    timestamp = cardTime.plusMillis(350),
                    asset = "USD",
                    amount = "-4980.00",
                    fee = "20.00",
                    type = KrakenApiConstants.LEDGER_TYPE_SPEND,
                    refid = cardRef,
                ),
                ledgerEvent(
                    timestamp = cardTime.plusSeconds(45),
                    asset = "BTC",
                    amount = "0.0996",
                    type = KrakenApiConstants.LEDGER_TYPE_RECEIVE,
                    refid = cardRef,
                ),
            )
            val provenance = SimpleFundingProvenanceResolver(
                deposits = listOf(
                    DepositStatusRecord(
                        refid = cardRef,
                        asset = "USD",
                        amount = BigDecimal("5000.00"),
                        time = cardTime,
                        status = "Success",
                        method = "Visa",
                    ),
                ),
            )

            val result = calculate(
                snapshots = snapshots,
                rewards = ledgers,
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal("50000.00"))),
                provenanceResolver = provenance,
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("54980.00")
        }

        "card funding replays off-series receive at its own ledger time" {
            val cardRef = "CARD-OFF-SERIES-RECEIVE-TIMING"
            val cardTime = now.plusSeconds(1800)
            val baseline = snapshot(
                now,
                "1000.00",
                mapOf("USD" to assetRow("1000.00", "1.00", "1000.00")),
            )
            val betweenSpendAndReceive = snapshot(
                cardTime.plusSeconds(10),
                "1000.00",
                mapOf("USD" to assetRow("1000.00", "1.00", "1000.00")),
            )
            val afterReceive = snapshot(
                cardTime.plusSeconds(60),
                "1000.00",
                mapOf("USD" to assetRow("1000.00", "1.00", "1000.00")),
            )
            val ledgers = listOf(
                ledgerEvent(
                    timestamp = cardTime,
                    asset = "USD",
                    amount = "100.00",
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    refid = cardRef,
                ),
                ledgerEvent(
                    timestamp = cardTime.plusSeconds(1),
                    asset = "USD",
                    amount = "-90.00",
                    fee = "10.00",
                    type = KrakenApiConstants.LEDGER_TYPE_SPEND,
                    refid = cardRef,
                ),
                ledgerEvent(
                    timestamp = cardTime.plusSeconds(45),
                    asset = "XLM",
                    amount = "90.00",
                    type = KrakenApiConstants.LEDGER_TYPE_RECEIVE,
                    refid = cardRef,
                ),
            )
            val provenance = SimpleFundingProvenanceResolver(
                deposits = listOf(
                    DepositStatusRecord(
                        refid = cardRef,
                        asset = "USD",
                        amount = BigDecimal("100.00"),
                        time = cardTime,
                        status = "Success",
                        method = "Visa",
                    ),
                ),
            )

            val result = calculate(
                snapshots = listOf(baseline, betweenSpendAndReceive, afterReceive),
                rewards = ledgers,
                configuredAssetUniverse = setOf("USD"),
                priceProvider = mapPriceProvider(mapOf("XLM" to BigDecimal.ONE)),
                provenanceResolver = provenance,
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points[1].rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("1000.00")
            result.points[2].rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("1090.00")
            result.points[2].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("1090.00")
        }

        "Scenario BB: card Buy Crypto legs spanning greater than 120s fail closed as ambiguous" {
            val cardRef = "CARD-BUY-DISTANT-2026-07-01"
            val cardTime = now.plusSeconds(1800)
            val snapshots = listOf(
                snapshot(
                    now,
                    "50000.00",
                    mapOf(
                        "BTC" to assetRow("0.50", "50000.00", "25000.00"),
                        "USD" to assetRow("25000.00", "1.0", "25000.00"),
                    ),
                ),
                snapshot(
                    cardTime.plusSeconds(300),
                    "54980.00",
                    mapOf(
                        "BTC" to assetRow("0.5996", "50000.00", "29980.00"),
                        "USD" to assetRow("25000.00", "1.0", "25000.00"),
                    ),
                ),
            )
            // Deposit at T, receive at T+125s (>120s span)
            val ledgers = listOf(
                ledgerEvent(
                    timestamp = cardTime,
                    asset = "USD",
                    amount = "5000.00",
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    refid = cardRef,
                ),
                ledgerEvent(
                    timestamp = cardTime.plusSeconds(30),
                    asset = "USD",
                    amount = "-4980.00",
                    fee = "20.00",
                    type = KrakenApiConstants.LEDGER_TYPE_SPEND,
                    refid = cardRef,
                ),
                ledgerEvent(
                    timestamp = cardTime.plusSeconds(125),
                    asset = "BTC",
                    amount = "0.0996",
                    type = KrakenApiConstants.LEDGER_TYPE_RECEIVE,
                    refid = cardRef,
                ),
            )
            val provenance = SimpleFundingProvenanceResolver(
                deposits = listOf(
                    DepositStatusRecord(
                        refid = cardRef,
                        asset = "USD",
                        amount = BigDecimal("5000.00"),
                        time = cardTime,
                        status = "Success",
                        method = "Visa",
                    ),
                ),
            )

            val result = calculate(
                snapshots = snapshots,
                rewards = ledgers,
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal("50000.00"))),
                provenanceResolver = provenance,
            )

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.AMBIGUOUS_LEDGER_TYPE
        }

        "Scenario BB: card Buy Crypto with non-USD fee converts fee at event time before subtracting" {
            val cardRef = "CARD-BUY-FEE-2026-07-01"
            val cardTime = now.plusSeconds(1800)
            val snapshots = listOf(
                snapshot(
                    now,
                    "50000.00",
                    mapOf(
                        "BTC" to assetRow("0.50", "50000.00", "25000.00"),
                        "USD" to assetRow("25000.00", "1.0", "25000.00"),
                    ),
                ),
                snapshot(
                    cardTime.plusSeconds(60),
                    "54975.00",
                    mapOf(
                        "BTC" to assetRow("0.5995", "50000.00", "29975.00"),
                        "USD" to assetRow("25000.00", "1.0", "25000.00"),
                    ),
                ),
            )
            // Spend fee $20 USD + Receive fee 0.0001 BTC @ $50k ($5 USD) = $25 USD total fee
            // Net owner capital = $5,000 - $25 = $4,975 USD
            val ledgers = listOf(
                ledgerEvent(
                    timestamp = cardTime,
                    asset = "USD",
                    amount = "5000.00",
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    refid = cardRef,
                ),
                ledgerEvent(
                    timestamp = cardTime,
                    asset = "USD",
                    amount = "-4980.00",
                    fee = "20.00",
                    type = KrakenApiConstants.LEDGER_TYPE_SPEND,
                    refid = cardRef,
                ),
                ledgerEvent(
                    timestamp = cardTime,
                    asset = "BTC",
                    amount = "0.0996",
                    fee = "0.0001",
                    type = KrakenApiConstants.LEDGER_TYPE_RECEIVE,
                    refid = cardRef,
                ),
            )
            val provenance = SimpleFundingProvenanceResolver(
                deposits = listOf(
                    DepositStatusRecord(
                        refid = cardRef,
                        asset = "USD",
                        amount = BigDecimal("5000.00"),
                        time = cardTime,
                        status = "Success",
                        method = "Visa",
                    ),
                ),
            )

            val result = calculate(
                snapshots = snapshots,
                rewards = ledgers,
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal("50000.00"))),
                provenanceResolver = provenance,
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            val builtEvents = RebalancerComparisonCalculator.buildBenchmarkEventsForTest(
                ledgers = ledgers,
                baseline = snapshots.first(),
                inceptionWeights = mapOf("BTC" to BigDecimal("0.5"), "USD" to BigDecimal("0.5")),
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal("50000.00"))),
                provenanceResolver = provenance,
            )
            val contribution = builtEvents.filterIsInstance<BenchmarkEvent.OwnerContribution>().single()
            contribution.contributionUsd shouldBeEqualComparingTo BigDecimal("4975.00")
        }

        "Scenario BB: card Buy Crypto with unpriceable crypto fee fails closed with missing price" {
            val cardRef = "CARD-BUY-UNPRICEABLE-2026-07-01"
            val cardTime = now.plusSeconds(1800)
            val snapshots = listOf(
                snapshot(
                    now,
                    "50000.00",
                    mapOf(
                        "BTC" to assetRow("0.50", "50000.00", "25000.00"),
                        "USD" to assetRow("25000.00", "1.0", "25000.00"),
                    ),
                ),
                snapshot(
                    cardTime.plusSeconds(60),
                    "54975.00",
                    mapOf(
                        "BTC" to assetRow("0.5995", "50000.00", "29975.00"),
                        "USD" to assetRow("25000.00", "1.0", "25000.00"),
                    ),
                ),
            )
            val ledgers = listOf(
                ledgerEvent(
                    timestamp = cardTime,
                    asset = "USD",
                    amount = "5000.00",
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    refid = cardRef,
                ),
                ledgerEvent(
                    timestamp = cardTime,
                    asset = "USD",
                    amount = "-4980.00",
                    fee = "20.00",
                    type = KrakenApiConstants.LEDGER_TYPE_SPEND,
                    refid = cardRef,
                ),
                ledgerEvent(
                    timestamp = cardTime,
                    asset = "BTC",
                    amount = "0.0996",
                    fee = "0.0001",
                    type = KrakenApiConstants.LEDGER_TYPE_RECEIVE,
                    refid = cardRef,
                ),
            )
            val provenance = SimpleFundingProvenanceResolver(
                deposits = listOf(
                    DepositStatusRecord(
                        refid = cardRef,
                        asset = "USD",
                        amount = BigDecimal("5000.00"),
                        time = cardTime,
                        status = "Success",
                        method = "Visa",
                    ),
                ),
            )

            // Missing price provider for BTC
            val result = calculate(
                snapshots = snapshots,
                rewards = ledgers,
                priceProvider = mapPriceProvider(emptyMap()),
                provenanceResolver = provenance,
            )

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.MISSING_PRICE
        }

        "Scenario BB: non-funding fee exceeding deposit flips sign and marks transaction ambiguous" {
            val cardRef = "CARD-OVERDRAWN-2026-07-01"
            val cardTime = now.plusSeconds(1800)
            val snapshots = listOf(
                snapshot(
                    now,
                    "50000.00",
                    mapOf(
                        "BTC" to assetRow("0.50", "50000.00", "25000.00"),
                        "USD" to assetRow("25000.00", "1.0", "25000.00"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(3600),
                    "49985.00",
                    mapOf(
                        "BTC" to assetRow("0.5002", "50000.00", "25010.00"),
                        "USD" to assetRow("24975.00", "1.0", "24975.00"),
                    ),
                ),
            )
            val ledgers = listOf(
                ledgerEvent(
                    timestamp = cardTime,
                    asset = "USD",
                    amount = "10.00",
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    refid = cardRef,
                ),
                ledgerEvent(
                    timestamp = cardTime,
                    asset = "USD",
                    amount = "-10.00",
                    fee = "25.00",
                    type = KrakenApiConstants.LEDGER_TYPE_SPEND,
                    refid = cardRef,
                ),
                ledgerEvent(
                    timestamp = cardTime,
                    asset = "BTC",
                    amount = "0.0002",
                    type = KrakenApiConstants.LEDGER_TYPE_RECEIVE,
                    refid = cardRef,
                ),
            )
            val provenance = SimpleFundingProvenanceResolver(
                deposits = listOf(
                    DepositStatusRecord(
                        refid = cardRef,
                        txid = "tx-overdrawn",
                        asset = "USD",
                        amount = BigDecimal("10.00"),
                        time = cardTime,
                        status = "Success",
                    ),
                ),
            )
            val result = calculate(
                snapshots = snapshots,
                rewards = ledgers,
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal("50000.00"))),
                provenanceResolver = provenance,
            )
            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.AMBIGUOUS_LEDGER_TYPE
        }

        "Scenario BB: confirmed card withdrawal with non-USD passthrough collapses into owner withdrawal" {
            val cardRef = "CARD-WITHDRAW-2026-07-01"
            val cardTime = now.plusSeconds(1800)
            val snapshots = listOf(
                snapshot(
                    now,
                    "50000.00",
                    mapOf(
                        "BTC" to assetRow("0.50", "50000.00", "25000.00"),
                        "USD" to assetRow("25000.00", "1.0", "25000.00"),
                    ),
                ),
                snapshot(
                    cardTime.plusSeconds(1),
                    "49020.00",
                    mapOf(
                        "BTC" to assetRow("0.48", "50000.00", "24000.00"),
                        "USD" to assetRow("25020.00", "1.0", "25020.00"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(3600),
                    "49020.00",
                    mapOf(
                        "BTC" to assetRow("0.48", "50000.00", "24000.00"),
                        "USD" to assetRow("25020.00", "1.0", "25020.00"),
                    ),
                ),
            )
            val ledgers = listOf(
                ledgerEvent(
                    timestamp = cardTime,
                    asset = "USD",
                    amount = "-1000.00",
                    type = KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL,
                    refid = cardRef,
                ),
                ledgerEvent(
                    timestamp = cardTime,
                    asset = "USD",
                    amount = "1000.00",
                    fee = "20.00",
                    type = KrakenApiConstants.LEDGER_TYPE_RECEIVE,
                    refid = cardRef,
                ),
                ledgerEvent(
                    timestamp = cardTime,
                    asset = "BTC",
                    amount = "-0.02",
                    type = KrakenApiConstants.LEDGER_TYPE_SPEND,
                    refid = cardRef,
                ),
            )
            val provenance = SimpleFundingProvenanceResolver(
                withdrawals = listOf(
                    WithdrawStatusRecord(
                        refid = cardRef,
                        txid = "tx-withdraw",
                        asset = "USD",
                        amount = BigDecimal("1000.00"),
                        time = cardTime,
                        status = "Success",
                    ),
                ),
            )
            val builtEvents = RebalancerComparisonCalculator.buildBenchmarkEventsForTest(
                ledgers = ledgers,
                baseline = snapshots.first(),
                inceptionWeights = mapOf("BTC" to BigDecimal("0.5"), "USD" to BigDecimal("0.5")),
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal("50000.00"))),
                provenanceResolver = provenance,
            )
            builtEvents.filterIsInstance<BenchmarkEvent.ExternalBalance>() shouldBe emptyList()
            val withdrawal = builtEvents.filterIsInstance<BenchmarkEvent.OwnerWithdrawal>().single()
            withdrawal.withdrawalUsd shouldBeEqualComparingTo BigDecimal("980.00")
            withdrawal.sourceLedgerIds shouldContainExactlyInAnyOrder ledgers.map { it.ledgerId }
        }

        "Scenario BB: card funding with net zero amount after fee is ambiguous and unavailable" {
            val cardRef = "CARD-ZERO-NET"
            val cardTime = now.plusSeconds(1800)
            val snapshots = listOf(
                snapshot(
                    now,
                    "50000.00",
                    mapOf(
                        "BTC" to assetRow("0.50", "50000.00", "25000.00"),
                        "USD" to assetRow("25000.00", "1.0", "25000.00"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(3600),
                    "50000.00",
                    mapOf(
                        "BTC" to assetRow("0.5004", "50000.00", "25020.00"),
                        "USD" to assetRow("24980.00", "1.0", "24980.00"),
                    ),
                ),
            )
            val ledgers = listOf(
                ledgerEvent(
                    timestamp = cardTime,
                    asset = "USD",
                    amount = "20.00",
                    fee = "0.00",
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    refid = cardRef,
                ),
                ledgerEvent(
                    timestamp = cardTime,
                    asset = "USD",
                    amount = "-20.00",
                    fee = "20.00",
                    type = KrakenApiConstants.LEDGER_TYPE_SPEND,
                    refid = cardRef,
                ),
                ledgerEvent(
                    timestamp = cardTime,
                    asset = "BTC",
                    amount = "0.0004",
                    type = KrakenApiConstants.LEDGER_TYPE_RECEIVE,
                    refid = cardRef,
                ),
            )
            val provenance = SimpleFundingProvenanceResolver(
                deposits = listOf(
                    DepositStatusRecord(
                        refid = cardRef,
                        txid = "tx-zero-net",
                        asset = "USD",
                        amount = BigDecimal("20.00"),
                        time = cardTime,
                        status = "Success",
                        method = "Visa",
                    ),
                ),
            )
            val result = calculate(
                snapshots = snapshots,
                rewards = ledgers,
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal("50000.00"))),
                provenanceResolver = provenance,
            )
            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.AMBIGUOUS_LEDGER_TYPE
            result.unavailableAt shouldBe cardTime
        }

        "Scenario BB ambiguous: card deposit and Buy Crypto refs without a shared parent stay unavailable" {
            val cardTime = now.plusSeconds(1800)
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
                    "35976.00",
                    mapOf(
                        "BTC" to assetRow("0.5996", "60000.00", "35976.00"),
                        "USD" to assetRow("0", "1.0", "0"),
                    ),
                ),
            )
            val ledgers = listOf(
                ledgerEvent(
                    timestamp = cardTime,
                    asset = "USD",
                    amount = "5000.00",
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    refid = "CARD-DEPOSIT-20260701",
                ),
                ledgerEvent(
                    timestamp = cardTime,
                    asset = "USD",
                    amount = "-4980.00",
                    fee = "20.00",
                    type = KrakenApiConstants.LEDGER_TYPE_SPEND,
                    refid = "BUY-CRYPTO-20260701",
                ),
                ledgerEvent(
                    timestamp = cardTime,
                    asset = "BTC",
                    amount = "0.0996",
                    type = KrakenApiConstants.LEDGER_TYPE_RECEIVE,
                    refid = "BUY-CRYPTO-20260701",
                ),
            )
            val provenance = SimpleFundingProvenanceResolver(
                deposits = listOf(
                    DepositStatusRecord(
                        refid = "CARD-DEPOSIT-20260701",
                        txid = "card-deposit-tx-20260701",
                        asset = "USD",
                        amount = BigDecimal("5000.00"),
                        time = cardTime,
                        status = "Success",
                        method = "Visa",
                    ),
                ),
            )

            val result = calculate(
                snapshots = snapshots,
                rewards = ledgers,
                provenanceResolver = provenance,
            )

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.AMBIGUOUS_LEDGER_TYPE
            result.unavailableAt shouldBe cardTime
        }

        "Scenario BB incomplete: mixed-asset funding without a USD spend stays unavailable" {
            val cardRef = "CARD-BUY-INCOMPLETE-20260701"
            val cardTime = now.plusSeconds(1800)
            val snapshots = listOf(
                snapshot(
                    now,
                    "50000.00",
                    mapOf(
                        "BTC" to assetRow("0.50", "50000.00", "25000.00"),
                        "USD" to assetRow("25000.00", "1.0", "25000.00"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(3600),
                    "59980.00",
                    mapOf(
                        "BTC" to assetRow("0.5996", "50000.00", "29980.00"),
                        "USD" to assetRow("30000.00", "1.0", "30000.00"),
                    ),
                ),
            )
            val result = calculate(
                snapshots = snapshots,
                rewards = listOf(
                    ledgerEvent(
                        timestamp = cardTime,
                        asset = "USD",
                        amount = "5000.00",
                        type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                        refid = cardRef,
                    ),
                    ledgerEvent(
                        timestamp = cardTime,
                        asset = "BTC",
                        amount = "0.0996",
                        type = KrakenApiConstants.LEDGER_TYPE_RECEIVE,
                        refid = cardRef,
                    ),
                ),
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal("50000.00"))),
                provenanceResolver = SimpleFundingProvenanceResolver(
                    deposits = listOf(
                        DepositStatusRecord(
                            refid = cardRef,
                            asset = "USD",
                            amount = BigDecimal("5000.00"),
                            time = cardTime,
                            status = "Success",
                            method = "Visa",
                        ),
                    ),
                ),
            )

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.AMBIGUOUS_LEDGER_TYPE
            result.unavailableAt shouldBe cardTime
        }

        "Scenario BB incomplete: mixed-asset funding without a purchased asset receive stays unavailable" {
            val cardRef = "CARD-BUY-NO-RECEIVE-20260701"
            val cardTime = now.plusSeconds(1800)
            val snapshots = listOf(
                snapshot(
                    now,
                    "50000.00",
                    mapOf(
                        "BTC" to assetRow("0.50", "50000.00", "25000.00"),
                        "USD" to assetRow("25000.00", "1.0", "25000.00"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(3600),
                    "50000.00",
                    mapOf(
                        "BTC" to assetRow("0.50", "50000.00", "25000.00"),
                        "USD" to assetRow("25000.00", "1.0", "25000.00"),
                    ),
                ),
            )
            val result = calculate(
                snapshots = snapshots,
                rewards = listOf(
                    ledgerEvent(
                        timestamp = cardTime,
                        asset = "USD",
                        amount = "5000.00",
                        type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                        refid = cardRef,
                    ),
                    ledgerEvent(
                        timestamp = cardTime,
                        asset = "USD",
                        amount = "-4980.00",
                        fee = "20.00",
                        type = KrakenApiConstants.LEDGER_TYPE_SPEND,
                        refid = cardRef,
                    ),
                ),
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal("50000.00"))),
                provenanceResolver = SimpleFundingProvenanceResolver(
                    deposits = listOf(
                        DepositStatusRecord(
                            refid = cardRef,
                            asset = "USD",
                            amount = BigDecimal("5000.00"),
                            time = cardTime,
                            status = "Success",
                            method = "Visa",
                        ),
                    ),
                ),
            )

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.AMBIGUOUS_LEDGER_TYPE
            result.unavailableAt shouldBe cardTime
        }

        "Scenario BB malformed: mixed-asset funding with same-direction USD legs stays unavailable" {
            val cardRef = "CARD-BUY-MALFORMED-20260701"
            val cardTime = now.plusSeconds(1800)
            val snapshots = listOf(
                snapshot(
                    now,
                    "50000.00",
                    mapOf(
                        "BTC" to assetRow("0.50", "50000.00", "25000.00"),
                        "USD" to assetRow("25000.00", "1.0", "25000.00"),
                    ),
                ),
                snapshot(
                    now.plusSeconds(3600),
                    "64940.00",
                    mapOf(
                        "BTC" to assetRow("0.5996", "50000.00", "29980.00"),
                        "USD" to assetRow("34960.00", "1.0", "34960.00"),
                    ),
                ),
            )
            val result = calculate(
                snapshots = snapshots,
                rewards = listOf(
                    ledgerEvent(
                        timestamp = cardTime,
                        asset = "USD",
                        amount = "5000.00",
                        type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                        refid = cardRef,
                    ),
                    ledgerEvent(
                        timestamp = cardTime,
                        asset = "USD",
                        amount = "4980.00",
                        fee = "20.00",
                        type = KrakenApiConstants.LEDGER_TYPE_SPEND,
                        refid = cardRef,
                    ),
                    ledgerEvent(
                        timestamp = cardTime,
                        asset = "BTC",
                        amount = "0.0996",
                        type = KrakenApiConstants.LEDGER_TYPE_RECEIVE,
                        refid = cardRef,
                    ),
                ),
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal("50000.00"))),
                provenanceResolver = SimpleFundingProvenanceResolver(
                    deposits = listOf(
                        DepositStatusRecord(
                            refid = cardRef,
                            asset = "USD",
                            amount = BigDecimal("5000.00"),
                            time = cardTime,
                            status = "Success",
                            method = "Visa",
                        ),
                    ),
                ),
            )

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE
            result.unavailableAt shouldBe cardTime
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
            // Consumer spend/receive plumbing is actual-account activity, not a passive trade or
            // owner flow. The original $10,000 cash anchor therefore remains the B&H thesis.
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("10000.00")
            result.latestDifferenceUSD!! shouldBeEqualComparingTo BigDecimal("-60.00")
        }

        "Scenario DD: Crypto-to-crypto conversion does not alter the pure Buy & Hold basket" {
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
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("105000.00")
            result.latestDifferenceUSD!! shouldBeEqualComparingTo BigDecimal("-42.00")
        }

        "Scenario EE: Unlinked asset plumbing does not inject a new basket holding" {
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
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("6000.00")
            result.latestDifferenceUSD!! shouldBeEqualComparingTo BigDecimal("500.00")
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
                    subtype = "cashdividend",
                ),
            )

            val result = calculate(
                snapshots = listOf(s1, s2),
                trades = emptyList(),
                rewards = ledgers,
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal("50000.00"))),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points[1].rebalancerValueUSD shouldBeEqualComparingTo BigDecimal("100025.00")
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100025.00")
            result.points[1].differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
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
                    subtype = "cashdividend",
                ),
            )

            val result = calculate(
                snapshots = listOf(s1, s2),
                trades = emptyList(),
                rewards = ledgers,
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal("50000.00"))),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points[0].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100000.00")
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100025.00")
            result.points[1].differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
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
                    subtype = "cashdividend",
                ),
            )

            val result = calculate(
                snapshots = listOf(s1, s2),
                trades = trades,
                rewards = ledgers,
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal("50000.00"))),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100500.00")
            result.points[1].differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
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

        "No anchor: UNKNOWN trade among initial candidates does not affect pure Buy & Hold" {
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

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100000.00")
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
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
                source = TradeSource.MANUAL,
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

        "unsupported transfer provenance reports the first timestamp and preserves ambiguous tie precedence" {
            val t0 = Instant.parse("2026-06-01T12:00:00Z")
            val t1 = Instant.parse("2026-06-10T12:00:00Z")
            val unsupportedAt = t1.plusSeconds(1800)
            val ambiguousAt = unsupportedAt.plusSeconds(1800)
            val t2 = t1.plusSeconds(7200)
            val flatAssets = mapOf(
                "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                "USD" to assetRow("50000.00", "1.0", "50000.00"),
            )
            val afterAssets = mapOf(
                "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                "USD" to assetRow("50010.00", "1.0", "50010.00"),
            )
            val inceptionSnap = snapshot(t0, "100000.00", flatAssets)
            val s1 = snapshot(t1, "100000.00", flatAssets)
            val s2 = snapshot(t2, "100010.00", afterAssets)
            val unsupportedLedger = ledgerEvent(
                timestamp = unsupportedAt,
                asset = "USD",
                amount = "5.00",
                ledgerId = "unsupported-ledger-first",
                type = KrakenApiConstants.LEDGER_TYPE_TRANSFER,
            )
            val ambiguousLedger = ledgerEvent(
                timestamp = ambiguousAt,
                asset = "USD",
                amount = "5.00",
                ledgerId = "ambiguous-ledger-second",
                type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                subtype = "mystery-plumbing",
            )
            val provenanceResolver = FundingProvenanceResolver { event ->
                if (event.ledgerId == unsupportedLedger.ledgerId) {
                    FundingEvidence.INTERNAL
                } else {
                    FundingEvidence.UNRESOLVED
                }
            }

            val result = calculate(
                snapshots = listOf(s1, s2),
                trades = emptyList(),
                rewards = listOf(ambiguousLedger, unsupportedLedger),
                inceptionSnapshot = inceptionSnap,
                provenanceResolver = provenanceResolver,
            )

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.UNSUPPORTED_LEDGER_TYPE
            result.unavailableAt shouldBe unsupportedAt

            val tiedResult = calculate(
                snapshots = listOf(s1, s2),
                trades = emptyList(),
                rewards = listOf(unsupportedLedger, ambiguousLedger.copy(time = unsupportedAt)),
                inceptionSnapshot = inceptionSnap,
                provenanceResolver = provenanceResolver,
            )

            tiedResult.availability shouldBe ComparisonAvailability.UNAVAILABLE
            tiedResult.unavailableReason shouldBe ComparisonUnavailableReason.AMBIGUOUS_LEDGER_TYPE
            tiedResult.unavailableAt shouldBe unsupportedAt
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
                subtype = "spotfromfutures",
                refid = "internal-transfer",
            )
            val transferOut = ledgerEvent(
                timestamp = tMid,
                asset = "USD",
                amount = "-500.00",
                type = "transfer",
                subtype = "spotfromfutures",
                refid = "internal-transfer",
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
                rewards = listOf(transfer, transferOut, tradeRow),
                inceptionSnapshot = inceptionSnap,
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points.size shouldBe 2
            result.points[0].buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("100000.00"))
        }

        "complete top-level conversion is consumed without changing pure Buy & Hold" {
            val conversionTime = now.plusSeconds(1800)
            val inception = snapshot(
                timestamp = now,
                totalValueUSD = "100.00",
                assets = mapOf(
                    "BTC" to assetRow("1.00", "100.00", "100.00"),
                    "ETH" to assetRow("0.00", "10.00", "0.00"),
                    "USD" to assetRow("0.00", "1.00", "0.00"),
                ),
            )
            val after = snapshot(
                timestamp = now.plusSeconds(3600),
                totalValueUSD = "98.80",
                assets = mapOf(
                    "BTC" to assetRow("0.49", "100.00", "49.00"),
                    "ETH" to assetRow("4.98", "10.00", "49.80"),
                    "USD" to assetRow("0.00", "1.00", "0.00"),
                ),
            )
            val source = ledgerEvent(
                timestamp = conversionTime,
                asset = "BTC",
                amount = "-0.50",
                fee = "0.01",
                balance = "0.49",
                ledgerId = "conversion-source",
                refid = "CONVERSION-BENCHMARK",
                type = KrakenApiConstants.LEDGER_TYPE_CONVERSION,
                hasAuthoritativeFee = true,
            )
            val destination = ledgerEvent(
                timestamp = conversionTime,
                asset = "ETH",
                amount = "5.00",
                fee = "0.02",
                balance = "4.98",
                ledgerId = "conversion-destination",
                refid = "CONVERSION-BENCHMARK",
                type = KrakenApiConstants.LEDGER_TYPE_CONVERSION,
                hasAuthoritativeFee = true,
            )
            val ledgers = listOf(source, destination)

            val built = RebalancerComparisonCalculator.buildBenchmarkEventsForTest(
                ledgers = ledgers,
                baseline = inception,
                inceptionWeights = mapOf("BTC" to BigDecimal.ONE),
                priceProvider = null,
                provenanceResolver = FundingProvenanceResolver.NONE,
            )
            built.filterIsInstance<BenchmarkEvent.ExternalBalance>() shouldBe emptyList()
            built.filterIsInstance<BenchmarkEvent.OwnerContribution>() shouldBe emptyList()

            val result = calculate(
                snapshots = listOf(inception, after),
                rewards = ledgers,
                inceptionSnapshot = inception,
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("100.00")
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal("-1.20")
        }

        "complete conversion outside the tracked reconciliation universe is ignored" {
            val baseline = snapshot(
                timestamp = now,
                totalValueUSD = "100.00",
                assets = mapOf("BTC" to assetRow("1.00", "100.00", "100.00")),
            )
            val conversionTime = now.plusSeconds(1800)
            val source = ledgerEvent(
                timestamp = conversionTime,
                asset = "USD",
                amount = "-100.00",
                balance = "0.00",
                ledgerId = "untracked-conversion-source",
                refid = "UNTRACKED-CONVERSION",
                type = KrakenApiConstants.LEDGER_TYPE_CONVERSION,
                hasAuthoritativeFee = true,
            )
            val destination = ledgerEvent(
                timestamp = conversionTime,
                asset = "USDG",
                amount = "100.00",
                balance = "100.00",
                ledgerId = "untracked-conversion-destination",
                refid = "UNTRACKED-CONVERSION",
                type = KrakenApiConstants.LEDGER_TYPE_CONVERSION,
                hasAuthoritativeFee = true,
            )
            val trackedReward = ledgerEvent(
                timestamp = conversionTime,
                asset = "BTC",
                amount = "1.00",
                balance = "2.00",
                ledgerId = "tracked-reward",
                type = KrakenApiConstants.LEDGER_TYPE_REWARD,
            )
            val built = RebalancerComparisonCalculator.buildBenchmarkEventsForTest(
                ledgers = listOf(source, destination),
                baseline = baseline,
                inceptionWeights = mapOf("BTC" to BigDecimal.ONE),
                priceProvider = null,
                provenanceResolver = FundingProvenanceResolver.NONE,
            )

            val result = calculate(
                snapshots = listOf(
                    baseline,
                    baseline.copy(
                        timestamp = conversionTime.plusSeconds(1),
                        balancesObservedAt = conversionTime.plusSeconds(1),
                        totalValueUSD = BigDecimal("200.00"),
                        assets = mapOf(
                            "BTC" to baseline.assets.getValue("BTC").copy(
                                balance = BigDecimal("2.00"),
                                valueUSD = BigDecimal("200.00"),
                            ),
                        ),
                    ),
                ),
                rewards = listOf(source, destination, trackedReward),
                inceptionSnapshot = baseline,
            )

            built shouldBe emptyList()
            result.availability shouldBe ComparisonAvailability.AVAILABLE
            // The tracked reward still replays at the same instant; the unrelated USD -> USDG
            // conversion must not collide with or suppress it.
            result.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("200.00")
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "one-legged top-level conversion remains unavailable" {
            val inception = snapshot(
                timestamp = now,
                totalValueUSD = "100.00",
                assets = mapOf(
                    "BTC" to assetRow("1.00", "100.00", "100.00"),
                    "USD" to assetRow("0.00", "1.00", "0.00"),
                ),
            )
            val result = calculate(
                snapshots = listOf(inception, inception.copy(timestamp = now.plusSeconds(3600))),
                rewards = listOf(
                    ledgerEvent(
                        timestamp = now.plusSeconds(1800),
                        asset = "BTC",
                        amount = "-0.50",
                        balance = "0.50",
                        ledgerId = "conversion-only",
                        refid = "CONVERSION-INCOMPLETE",
                        type = KrakenApiConstants.LEDGER_TYPE_CONVERSION,
                        hasAuthoritativeFee = true,
                    ),
                ),
                inceptionSnapshot = inception,
            )

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.UNSUPPORTED_LEDGER_TYPE
        }

        "universe-split conversion with a complete refid pair reconciles" {
            val fixture = universeSplitConversionFixture()

            val result = calculate(
                snapshots = fixture.snapshots,
                rewards = fixture.ledgers,
                ledgerContext = fixture.ledgerContext,
                priceProvider = mapPriceProvider(mapOf("USDG" to BigDecimal.ONE)),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.unavailableReason shouldBe null
            // The deposit replays once as owner capital. The conversion stays benchmark-neutral
            // while the full-wallet actual replay retains its off-series USDG proceeds.
            result.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("1389.28")
            result.points.last().differenceUSD shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "universe-split conversion without its counterpart stays closed" {
            val fixture = universeSplitConversionFixture()

            val result = calculate(
                snapshots = fixture.snapshots,
                rewards = fixture.ledgers.filter { it.asset != "USDG" },
                ledgerContext = fixture.ledgerContext,
            )

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.UNSUPPORTED_LEDGER_TYPE
        }

        "universe-split conversion with a time-mismatched counterpart stays closed" {
            val fixture = universeSplitConversionFixture()
            val lateReceive = fixture.ledgers.single { it.asset == "USDG" }.copy(
                ledgerId = "universe-split-receive-late",
                time = fixture.conversionTime.plusSeconds(60),
            )
            val result = calculate(
                snapshots = fixture.snapshots,
                rewards = fixture.ledgers.filter { it.asset != "USDG" } + lateReceive,
                ledgerContext = fixture.ledgerContext,
            )

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.AMBIGUOUS_LEDGER_TYPE
        }

        "universe-split conversion with an over-complete refid group stays closed" {
            val fixture = universeSplitConversionFixture()
            val extraReceive = fixture.ledgers.single { it.asset == "USDG" }.copy(
                ledgerId = "universe-split-receive-extra",
            )
            val result = calculate(
                snapshots = fixture.snapshots,
                rewards = fixture.ledgers + extraReceive,
                ledgerContext = fixture.ledgerContext,
            )

            // An over-complete refid group is uninterpretable, not merely ambiguous between
            // readings: no two-leg pairing can be isolated, so classification fails closed.
            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.UNSUPPORTED_LEDGER_TYPE
        }

        "universe-split conversion with a pre-baseline counterpart stays closed" {
            val fixture = universeSplitConversionFixture()
            val straddlingReceive = fixture.ledgers.single { it.asset == "USDG" }.copy(
                ledgerId = "universe-split-receive-straddling",
                time = fixture.snapshots.first().timestamp,
            )
            val result = calculate(
                snapshots = fixture.snapshots,
                rewards = fixture.ledgers.filter { it.asset != "USDG" } + straddlingReceive,
                ledgerContext = fixture.ledgerContext,
            )

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.AMBIGUOUS_LEDGER_TYPE
        }

        "universe-split conversion with a backfilled pre-baseline counterpart stays closed" {
            val fixture = universeSplitConversionFixture()
            // The counterpart predates the baseline state yet clears the entry filter via a
            // backfilled first observation: a straddling conversion cannot rescue its leg.
            val backfilledSnapshots = fixture.snapshots.mapIndexed { index, row ->
                if (index == 0) {
                    row.copy(balancesObservedAt = row.timestamp.minusSeconds(3600))
                } else {
                    row
                }
            }
            val earlyCounterpart = fixture.ledgers.single { it.asset == "USDG" }.copy(
                ledgerId = "universe-split-receive-early",
                time = fixture.snapshots.first().timestamp.minusSeconds(1800),
            )
            val result = calculate(
                snapshots = backfilledSnapshots,
                rewards = fixture.ledgers.filter { it.asset != "USDG" } + earlyCounterpart,
                ledgerContext = fixture.ledgerContext,
            )

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.AMBIGUOUS_LEDGER_TYPE
        }

        "universe-split conversion with a blank refid stays closed" {
            val fixture = universeSplitConversionFixture(conversionRefid = null)

            val result = calculate(
                snapshots = fixture.snapshots,
                rewards = fixture.ledgers,
                ledgerContext = fixture.ledgerContext,
            )

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.UNSUPPORTED_LEDGER_TYPE
        }

        "conversion into an unobserved zero-balance anchor asset reconciles" {
            // Production shape: the complete-wallet anchor records a stablecoin key with zero
            // balance, the legacy series never records that asset, and the conversion
            // counterpart leg is therefore never assigned. The linked pair is structurally
            // complete and classified internal, so the one-legged reconciled view must not
            // surface as an ambiguous funding event.
            val baseline = snapshot(
                timestamp = now,
                totalValueUSD = "200.00",
                assets = mapOf(
                    "USD" to assetRow("100.00", "1", "100.00"),
                    "BTC" to assetRow("1.00", "100.00", "100.00"),
                    "USDG" to assetRow("0.00", "1", "0.00"),
                ),
                balancesObservedAt = null,
            )
            val conversionTime = now.plusSeconds(1800)
            val source = ledgerEvent(
                timestamp = conversionTime,
                asset = "USD",
                amount = "-100.00",
                balance = "0.00",
                ledgerId = "stablecoin-conversion-source",
                refid = "CONV-STABLECOIN",
                type = KrakenApiConstants.LEDGER_TYPE_CONVERSION,
                hasAuthoritativeFee = true,
            ).copy(aclass = "currency")
            val destination = ledgerEvent(
                timestamp = conversionTime,
                asset = "USDG",
                amount = "100.00",
                balance = "100.00",
                ledgerId = "stablecoin-conversion-destination",
                refid = "CONV-STABLECOIN",
                type = KrakenApiConstants.LEDGER_TYPE_CONVERSION,
                hasAuthoritativeFee = true,
            ).copy(aclass = "currency")

            val legacySuccessor = snapshot(
                timestamp = conversionTime.minusSeconds(1),
                totalValueUSD = "200.00",
                assets = mapOf(
                    "USD" to assetRow("100.00", "1", "100.00"),
                    "BTC" to assetRow("1.00", "100.00", "100.00"),
                ),
                balancesObservedAt = null,
            )
            val result = calculate(
                snapshots = listOf(
                    baseline,
                    legacySuccessor,
                    snapshot(
                        conversionTime.plusSeconds(1),
                        "100.00",
                        mapOf(
                            "USD" to assetRow("0.00", "1", "0.00"),
                            "BTC" to assetRow("1.00", "100.00", "100.00"),
                        ),
                        balancesObservedAt = null,
                    ),
                ),
                rewards = listOf(source, destination),
                inceptionSnapshot = baseline,
                priceProvider = mapPriceProvider(mapOf("USDG" to BigDecimal.ONE)),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.unavailableReason shouldBe null
        }

        "universe-split conversion into a tracked asset stays closed" {
            val fixture = universeSplitConversionFixture()
            val trackedSnapshots = fixture.snapshots.map { row ->
                row.copy(
                    assets = row.assets + (
                        "USDG" to assetSnapshot(
                            symbol = "USDG",
                            balance = BigDecimal.ZERO,
                            price = BigDecimal.ONE,
                            valueUSD = BigDecimal.ZERO,
                            targetPercent = BigDecimal.ZERO,
                        )
                        ),
                )
            }

            val result = calculate(
                snapshots = trackedSnapshots,
                rewards = fixture.ledgers,
                ledgerContext = fixture.ledgerContext,
            )

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.AMBIGUOUS_LEDGER_TYPE
        }

        "malformed conversion groups never become benchmark events" {
            val baseline = snapshot(
                timestamp = now,
                totalValueUSD = "100.00",
                assets = mapOf(
                    "BTC" to assetRow("1.00", "100.00", "100.00"),
                    "ETH" to assetRow("0.00", "10.00", "0.00"),
                    "USD" to assetRow("0.00", "1.00", "0.00"),
                ),
            )
            val conversionTime = now.plusSeconds(1800)
            val source = ledgerEvent(
                timestamp = conversionTime,
                asset = "BTC",
                amount = "-0.50",
                fee = "0.01",
                balance = "0.49",
                ledgerId = "malformed-source",
                refid = "MALFORMED-CONVERSION",
                type = KrakenApiConstants.LEDGER_TYPE_CONVERSION,
                hasAuthoritativeFee = true,
            )
            val destination = ledgerEvent(
                timestamp = conversionTime,
                asset = "ETH",
                amount = "5.00",
                fee = "0.02",
                balance = "4.98",
                ledgerId = "malformed-destination",
                refid = "MALFORMED-CONVERSION",
                type = KrakenApiConstants.LEDGER_TYPE_CONVERSION,
                hasAuthoritativeFee = true,
            )
            val malformedGroups = listOf(
                listOf(source.copy(refid = null), destination.copy(refid = null)),
                listOf(source.copy(time = now.minusSeconds(1)), destination),
                listOf(source.copy(hasAuthoritativeBalance = false), destination),
                listOf(source.copy(time = now.minusSeconds(1)), destination.copy(time = now.minusSeconds(2))),
            )

            for (group in malformedGroups) {
                RebalancerComparisonCalculator.buildBenchmarkEventsForTest(
                    ledgers = group,
                    baseline = baseline,
                    inceptionWeights = mapOf("BTC" to BigDecimal.ONE),
                    priceProvider = null,
                    provenanceResolver = FundingProvenanceResolver.NONE,
                ) shouldBe emptyList()
            }
        }

        "calculate with inception snapshot whose asset universe grows reports unexplained balance change" {
            val t0 = now.minusSeconds(86400 * 30)
            val t1 = now

            val inceptionSnap = snapshot(
                timestamp = t0,
                totalValueUSD = "100000.00",
                assets = mapOf(
                    "BTC" to assetRow("1.0", "50000.00", "50000.00"),
                    "USD" to assetRow("50000.00", "1.00", "50000.00"),
                ),
            )

            // ETH appears in the series with no trade or ledger that could have acquired it.
            val s1 = snapshot(
                timestamp = t1,
                totalValueUSD = "120000.00",
                assets = mapOf(
                    "BTC" to assetRow("1.0", "40000.00", "40000.00"),
                    "ETH" to assetRow("10.0", "3000.00", "30000.00"),
                    "USD" to assetRow("50000.00", "1.00", "50000.00"),
                ),
            )

            val result = calculate(
                snapshots = listOf(inceptionSnap, s1),
                trades = emptyList(),
                anchorSnapshot = null,
                inceptionSnapshot = inceptionSnap,
            )

            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE
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

        "display-window boundaries cannot split an economic card transaction across window partitions" {
            val cardRef = "CARD-WINDOW-BOUNDARY-TEST"
            val cardTime = now.plusSeconds(3600)
            val inceptionSnap = snapshot(
                timestamp = now,
                totalValueUSD = "50000.00",
                assets = mapOf(
                    "BTC" to assetRow("0.50", "50000.00", "25000.00"),
                    "USD" to assetRow("25000.00", "1.0", "25000.00"),
                ),
            )
            val tEnd = now.plusSeconds(7200)
            val endSnap = snapshot(
                timestamp = tEnd,
                totalValueUSD = "60976.00",
                assets = mapOf(
                    "BTC" to assetRow("0.5996", "60000.00", "35976.00"),
                    "USD" to assetRow("25000.00", "1.0", "25000.00"),
                ),
            )

            // Economic transaction: deposit @ T-30s, spend @ T, receive @ T+20s
            val cardDeposit = ledgerEvent(
                timestamp = cardTime.minusSeconds(30),
                asset = "USD",
                amount = "5000.00",
                type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                ledgerId = "boundary-card-deposit",
                refid = cardRef,
            )
            val cardSpend = ledgerEvent(
                timestamp = cardTime,
                asset = "USD",
                amount = "-4980.00",
                fee = "20.00",
                type = KrakenApiConstants.LEDGER_TYPE_SPEND,
                ledgerId = "boundary-card-spend",
                refid = cardRef,
            )
            val cardReceive = ledgerEvent(
                timestamp = cardTime.plusSeconds(20),
                asset = "BTC",
                amount = "0.0996",
                type = KrakenApiConstants.LEDGER_TYPE_RECEIVE,
                ledgerId = "boundary-card-receive",
                refid = cardRef,
            )
            val allLedgers = listOf(cardDeposit, cardSpend, cardReceive)

            val provenance = SimpleFundingProvenanceResolver(
                deposits = listOf(
                    DepositStatusRecord(
                        refid = cardRef,
                        txid = "boundary-card-tx",
                        asset = "USD",
                        amount = BigDecimal("5000.00"),
                        time = cardTime.minusSeconds(30),
                        status = "Success",
                        method = "Visa",
                    ),
                ),
            )
            val priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal("50000.00")))

            val builtEvents = RebalancerComparisonCalculator.buildBenchmarkEventsForTest(
                ledgers = allLedgers,
                baseline = inceptionSnap,
                inceptionWeights = mapOf("BTC" to BigDecimal("0.5"), "USD" to BigDecimal("0.5")),
                priceProvider = priceProvider,
                provenanceResolver = provenance,
            )
            val contribution = builtEvents.filterIsInstance<BenchmarkEvent.OwnerContribution>().single()
            contribution.contributionUsd shouldBeEqualComparingTo BigDecimal("4980.00")
            val syntheticBtc = BigDecimal("0.50").add(contribution.allocations.getValue("BTC"))
            val syntheticUsd = BigDecimal("25000.00").add(contribution.allocations.getValue("USD"))
            syntheticBtc shouldBeEqualComparingTo BigDecimal("0.54980000")
            syntheticUsd shouldBeEqualComparingTo BigDecimal("27490.00000000")

            // Partition 1: Window starting at T-60s (before any card legs)
            val snapTMinus60 = snapshot(
                timestamp = cardTime.minusSeconds(60),
                totalValueUSD = "50000.00",
                assets = mapOf(
                    "BTC" to assetRow("0.50", "50000.00", "25000.00"),
                    "USD" to assetRow("25000.00", "1.0", "25000.00"),
                ),
            )
            val res1 = calculate(
                snapshots = listOf(snapTMinus60, endSnap),
                rewards = allLedgers,
                inceptionSnapshot = inceptionSnap,
                priceProvider = priceProvider,
                provenanceResolver = provenance,
            )
            res1.availability shouldBe ComparisonAvailability.AVAILABLE
            res1.points.size shouldBe 2
            res1.points.first().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("50000.00")
            res1.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("60478.00")

            // Partition 2: Window starting at T (at card spend, after card deposit)
            val snapT = snapshot(
                timestamp = cardTime,
                totalValueUSD = "50000.00",
                assets = mapOf(
                    "BTC" to assetRow("0.50", "50000.00", "25000.00"),
                    "USD" to assetRow("25000.00", "1.0", "25000.00"),
                ),
            )
            val res2 = calculate(
                snapshots = listOf(snapT, endSnap),
                rewards = allLedgers,
                inceptionSnapshot = inceptionSnap,
                priceProvider = priceProvider,
                provenanceResolver = provenance,
            )
            res2.availability shouldBe ComparisonAvailability.AVAILABLE
            res2.points.size shouldBe 2
            res2.points.first().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("54980.00")
            res2.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("60478.00")

            // Partition 3: Window starting at T+10s (between spend and receive)
            val snapTPlus10 = snapshot(
                timestamp = cardTime.plusSeconds(10),
                totalValueUSD = "50000.00",
                assets = mapOf(
                    "BTC" to assetRow("0.50", "50000.00", "25000.00"),
                    "USD" to assetRow("25000.00", "1.0", "25000.00"),
                ),
            )
            val res3 = calculate(
                snapshots = listOf(snapTPlus10, endSnap),
                rewards = allLedgers,
                inceptionSnapshot = inceptionSnap,
                priceProvider = priceProvider,
                provenanceResolver = provenance,
            )
            res3.availability shouldBe ComparisonAvailability.AVAILABLE
            res3.points.size shouldBe 2
            res3.points.first().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("54980.00")
            res3.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("60478.00")

            // Partition 4: Window starting at T+60s (after complete card transaction)
            val snapTPlus60 = snapshot(
                timestamp = cardTime.plusSeconds(60),
                totalValueUSD = "54980.00",
                assets = mapOf(
                    "BTC" to assetRow("0.5996", "50000.00", "29980.00"),
                    "USD" to assetRow("25000.00", "1.0", "25000.00"),
                ),
            )
            val res4 = calculate(
                snapshots = listOf(snapTPlus60, endSnap),
                rewards = allLedgers,
                inceptionSnapshot = inceptionSnap,
                priceProvider = priceProvider,
                provenanceResolver = provenance,
            )
            res4.availability shouldBe ComparisonAvailability.AVAILABLE
            res4.points.size shouldBe 2
            res4.points.first().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("54980.00")
            res4.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("60478.00")
        }

        "provenance preparation lifecycle executes prepare exactly once during comparison calculation" {
            val cardRef = "CARD-PREPARE-ONCE"
            val cardTime = now.plusSeconds(1800)
            val snapshots = listOf(
                snapshot(
                    now,
                    "50000.00",
                    mapOf(
                        "BTC" to assetRow("0.50", "50000.00", "25000.00"),
                        "USD" to assetRow("25000.00", "1.0", "25000.00"),
                    ),
                ),
                snapshot(
                    cardTime.plusSeconds(1),
                    "54980.00",
                    mapOf(
                        "BTC" to assetRow("0.5996", "50000.00", "29980.00"),
                        "USD" to assetRow("25000.00", "1.0", "25000.00"),
                    ),
                ),
            )
            val ledgers = listOf(
                ledgerEvent(
                    timestamp = cardTime,
                    asset = "USD",
                    amount = "5000.00",
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    refid = cardRef,
                ),
                ledgerEvent(
                    timestamp = cardTime,
                    asset = "USD",
                    amount = "-4980.00",
                    fee = "20.00",
                    type = KrakenApiConstants.LEDGER_TYPE_SPEND,
                    refid = cardRef,
                ),
                ledgerEvent(
                    timestamp = cardTime,
                    asset = "BTC",
                    amount = "0.0996",
                    type = KrakenApiConstants.LEDGER_TYPE_RECEIVE,
                    refid = cardRef,
                ),
            )
            var prepareCount = 0
            val countingResolver = object : FundingProvenanceResolver {
                override fun resolve(event: LedgerEvent): FundingEvidence =
                    if (event.refid == cardRef) FundingEvidence.EXTERNAL else FundingEvidence.UNRESOLVED

                override fun isCardFunding(event: LedgerEvent): Boolean = event.refid == cardRef

                override suspend fun prepare(events: Collection<LedgerEvent>): FundingProvenanceResolver {
                    prepareCount++
                    return this
                }
            }

            val result = calculate(
                snapshots = snapshots,
                rewards = ledgers,
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal("50000.00"))),
                provenanceResolver = countingResolver,
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            prepareCount shouldBe 1
        }

        "legacy boundary ledger already embodied in previous snapshot balance is excluded from subsequent interval" {
            val t0 = now
            val result = calculate(
                snapshots = listOf(
                    snapshot(
                        t0,
                        "101.00",
                        mapOf(
                            "BTC" to assetRow("1.00000000", "1", "1.00"),
                            "USD" to assetRow("100.00", "1", "100.00"),
                        ),
                        balancesObservedAt = null,
                    ),
                    snapshot(
                        t0.plusMillis(200),
                        "102.00",
                        mapOf(
                            "BTC" to assetRow("1.00000000", "1", "1.00"),
                            "USD" to assetRow("101.00", "1", "101.00"),
                        ),
                        balancesObservedAt = null,
                    ),
                ),
                trades = emptyList(),
                rewards = listOf(
                    // Embodied in t0 snapshot: ledger balance matches BTC balance at t0
                    ledgerEvent(
                        timestamp = t0,
                        asset = "BTC",
                        amount = "0.50000000",
                        type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                        balance = "1.00000000",
                    ),
                    // Mutation for interval [t0, t0.plusMillis(200)]
                    ledgerEvent(
                        timestamp = t0.plusMillis(200),
                        asset = "USD",
                        amount = "1.00",
                        type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                        balance = "101.00",
                    ),
                ),
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal.ONE)),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
        }

        "legacy boundary ledger not embodied in previous snapshot balance is evaluated as boundary candidate" {
            val t0 = now
            val result = calculate(
                snapshots = listOf(
                    snapshot(
                        t0,
                        "101.00",
                        mapOf(
                            "BTC" to assetRow("1.00000000", "1", "1.00"),
                            "USD" to assetRow("100.00", "1", "100.00"),
                        ),
                        balancesObservedAt = null,
                    ),
                    snapshot(
                        t0.plusMillis(500),
                        "102.00",
                        mapOf(
                            "BTC" to assetRow("1.50000000", "1", "1.50"),
                            "USD" to assetRow("100.50", "1", "100.50"),
                        ),
                        balancesObservedAt = null,
                    ),
                ),
                trades = emptyList(),
                rewards = listOf(
                    // Not embodied in t0 (balance 1.50 != 1.00), so evaluated for interval
                    ledgerEvent(
                        timestamp = t0.minusMillis(50),
                        asset = "BTC",
                        amount = "0.50000000",
                        type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                        balance = "1.50000000",
                    ),
                    ledgerEvent(
                        timestamp = t0.plusMillis(200),
                        asset = "USD",
                        amount = "0.50",
                        type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                        balance = "100.50",
                    ),
                ),
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal.ONE)),
            )

            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
        }

        "authoritative ledger unresolved in ledgerScopes returns UNEXPLAINED_BALANCE_CHANGE" {
            val t0 = now
            val result = calculate(
                snapshots = listOf(
                    snapshot(t0, "100.00", mapOf("USD" to assetRow("100.00", "1", "100.00"))),
                    snapshot(t0.plusSeconds(10), "100.00", mapOf("USD" to assetRow("100.00", "1", "100.00"))),
                ),
                trades = emptyList(),
                rewards = listOf(
                    // Opaque staking ledger with authoritative balance cannot be resolved to SPOT or known scope
                    ledgerEvent(
                        timestamp = t0.plusSeconds(5),
                        asset = "DOT.S",
                        amount = "1.0",
                        type = KrakenApiConstants.LEDGER_TYPE_STAKING,
                        balance = "10.0",
                        hasAuthoritativeFee = false,
                    ),
                ),
            )
            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE
        }

        "external ledger with invalid fee returns UNEXPLAINED_BALANCE_CHANGE" {
            val t0 = now
            val result = calculate(
                snapshots = listOf(
                    snapshot(t0, "100.00", mapOf("USD" to assetRow("100.00", "1", "100.00"))),
                    snapshot(t0.plusSeconds(10), "101.00", mapOf("USD" to assetRow("101.00", "1", "101.00"))),
                ),
                trades = emptyList(),
                rewards = listOf(
                    ledgerEvent(
                        timestamp = t0.plusSeconds(5),
                        asset = "USD",
                        amount = "1.00",
                        type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                        balance = "101.00",
                    ).copy(hasValidFee = false),
                ),
            )
            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE
        }

        "calculate returns UNEXPLAINED_BALANCE_CHANGE when owner flow and balance movement are near pairs" {
            val t0 = now
            val t1 = t0.plusSeconds(60)
            val depositTime = t0.plusSeconds(10)
            val withdrawalTime = depositTime.plusMillis(200)
            val stakingTime = withdrawalTime.plusMillis(200)
            val depRef = "FT-near-deposit"
            val wdrRef = "WIRE-near-withdrawal"
            val provenance = SimpleFundingProvenanceResolver(
                deposits = listOf(
                    DepositStatusRecord(
                        refid = depRef,
                        txid = "tx-dep",
                        asset = "USD",
                        amount = BigDecimal("100.00"),
                        time = depositTime,
                        status = "Success",
                        method = "Wire",
                    ),
                ),
                withdrawals = listOf(
                    WithdrawStatusRecord(
                        refid = wdrRef,
                        txid = "tx-wdr",
                        asset = "USD",
                        amount = BigDecimal("50.00"),
                        time = withdrawalTime,
                        status = "Success",
                        method = "Wire",
                    ),
                ),
            )
            val result = calculate(
                snapshots = listOf(
                    snapshot(
                        t0,
                        "1000.00",
                        mapOf(
                            "USD" to assetRow("500.00", "1", "500.00"),
                            "BTC" to assetRow("0.01", "50000.00", "500.00"),
                        ),
                    ),
                    snapshot(
                        t1,
                        "1150.00",
                        mapOf(
                            "USD" to assetRow("550.00", "1", "550.00"),
                            "BTC" to assetRow("0.012", "50000.00", "600.00"),
                        ),
                    ),
                ),
                trades = emptyList(),
                rewards = listOf(
                    ledgerEvent(
                        timestamp = depositTime,
                        asset = "USD",
                        amount = "100.00",
                        type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                        balance = "600.00",
                        refid = depRef,
                    ),
                    ledgerEvent(
                        timestamp = withdrawalTime,
                        asset = "USD",
                        amount = "-50.00",
                        type = KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL,
                        balance = "550.00",
                        refid = wdrRef,
                    ),
                    ledgerEvent(
                        timestamp = stakingTime,
                        asset = "BTC",
                        amount = "0.002",
                        type = KrakenApiConstants.LEDGER_TYPE_STAKING,
                        balance = "0.012",
                    ),
                ),
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal("50000.00"))),
                provenanceResolver = provenance,
            )
            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE
        }

        "calculate reconciles interval via PERSISTED_ROUNDED_COST when trade has ledger legs and rounded cost" {
            val t0 = now
            val t1 = t0.plusSeconds(20)
            val tradeTime = t0.plusSeconds(10)
            val tradeId = "PERSISTED-ROUND-T1"
            val buyTrade = trade(
                timestamp = tradeTime,
                side = "BUY",
                symbol = "BTC",
                volume = "1.00000000",
                price = "50000.004",
                usdAmount = "50000.00",
                fee = "0",
                source = TradeSource.API_FILL,
                tradeId = tradeId,
            )
            val baseLeg = ledgerEvent(
                timestamp = tradeTime,
                asset = "BTC",
                amount = "1.00000000",
                type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                refid = tradeId,
                balance = "2.00000000",
            )
            val quoteLeg = ledgerEvent(
                timestamp = tradeTime,
                asset = "USD",
                amount = "-50000.004",
                type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                refid = tradeId,
                balance = "49999.996",
            )
            val s0 = snapshot(
                timestamp = t0,
                totalValueUSD = "150000.00",
                assets = mapOf(
                    "BTC" to assetRow("1.00000000", "50000.00", "50000.00"),
                    "USD" to assetRow("100000.00", "1", "100000.00"),
                ),
            )
            val s1 = snapshot(
                timestamp = t1,
                totalValueUSD = "150000.00",
                assets = mapOf(
                    "BTC" to assetRow("2.00000000", "50000.00", "100000.00"),
                    "USD" to assetRow("50000.00", "1", "50000.00"),
                ),
            )
            val result = calculate(
                snapshots = listOf(s0, s1),
                trades = listOf(buyTrade),
                rewards = listOf(baseLeg, quoteLeg),
            )
            result.availability shouldBe ComparisonAvailability.AVAILABLE
        }

        "buildBenchmarkEventsForTest handles unpriceable and skipped owner flow edges" {
            val t0 = now
            val baseline = snapshot(t0, "1000.00", mapOf("USD" to assetRow("1000.00", "1", "1000.00")))

            // Unpriceable deposit due to empty inception weights
            val unpriceableDeposit = listOf(
                ledgerEvent(
                    timestamp = t0.plusSeconds(5),
                    asset = "USD",
                    amount = "100.00",
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    refid = "FT-test-dep",
                ),
            )
            RebalancerComparisonCalculator.buildBenchmarkEventsForTest(
                ledgers = unpriceableDeposit,
                baseline = baseline,
                inceptionWeights = emptyMap(),
                priceProvider = null,
                provenanceResolver = FundingProvenanceResolver.NONE,
            ) shouldBe emptyList()

            // Skipped deposit due to zero or negative amount
            val zeroDeposit = listOf(
                ledgerEvent(
                    timestamp = t0.plusSeconds(5),
                    asset = "USD",
                    amount = "0.00",
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    refid = "FT-test-zero-dep",
                ),
            )
            RebalancerComparisonCalculator.buildBenchmarkEventsForTest(
                ledgers = zeroDeposit,
                baseline = baseline,
                inceptionWeights = mapOf("USD" to BigDecimal.ONE),
                priceProvider = null,
                provenanceResolver = FundingProvenanceResolver.NONE,
            ) shouldBe emptyList()

            // Skipped withdrawal due to zero withdrawalUsd
            val skipWithdrawalRef = "WIRE-test-skip-wdr"
            val zeroWithdrawal = listOf(
                ledgerEvent(
                    timestamp = t0.plusSeconds(5),
                    asset = "USD",
                    amount = "0.00",
                    type = KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL,
                    refid = skipWithdrawalRef,
                ),
            )
            val skipProvenance = SimpleFundingProvenanceResolver(
                withdrawals = listOf(
                    WithdrawStatusRecord(
                        refid = skipWithdrawalRef,
                        txid = "tx-skip-wdr",
                        asset = "USD",
                        amount = BigDecimal.ZERO,
                        time = t0.plusSeconds(5),
                        status = "Success",
                        method = "Wire",
                    ),
                ),
            )
            RebalancerComparisonCalculator.buildBenchmarkEventsForTest(
                ledgers = zeroWithdrawal,
                baseline = baseline,
                inceptionWeights = mapOf("USD" to BigDecimal.ONE),
                priceProvider = null,
                provenanceResolver = skipProvenance,
            ) shouldBe emptyList()

            // Unpriceable non-USD withdrawal without price provider
            val unpriceableEthRef = "WIRE-test-unpriceable-eth"
            val unpriceableEthWithdrawal = listOf(
                ledgerEvent(
                    timestamp = t0.plusSeconds(5),
                    asset = "ETH",
                    amount = "-1.00",
                    type = KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL,
                    refid = unpriceableEthRef,
                ),
            )
            val unpriceableProvenance = SimpleFundingProvenanceResolver(
                withdrawals = listOf(
                    WithdrawStatusRecord(
                        refid = unpriceableEthRef,
                        txid = "tx-unpriceable-eth",
                        asset = "ETH",
                        amount = BigDecimal("1.00"),
                        time = t0.plusSeconds(5),
                        status = "Success",
                        method = "Cryptocurrency",
                    ),
                ),
            )
            RebalancerComparisonCalculator.buildBenchmarkEventsForTest(
                ledgers = unpriceableEthWithdrawal,
                baseline = baseline,
                inceptionWeights = mapOf("USD" to BigDecimal.ONE),
                priceProvider = null,
                provenanceResolver = unpriceableProvenance,
            ) shouldBe emptyList()
        }

        "calculate returns UNEXPLAINED_BALANCE_CHANGE when owner withdrawal exceeds synthetic total by more than dust" {
            val t0 = now
            val t1 = t0.plusSeconds(20)
            val wdrTime = t0.plusSeconds(10)
            val wdrRef = "WIRE-overdrawn-large"
            val provenance = SimpleFundingProvenanceResolver(
                withdrawals = listOf(
                    WithdrawStatusRecord(
                        refid = wdrRef,
                        txid = "tx-wdr-large",
                        asset = "USD",
                        amount = BigDecimal("110.00"),
                        time = wdrTime,
                        status = "Success",
                        method = "Wire",
                    ),
                ),
            )
            val result = calculate(
                snapshots = listOf(
                    snapshot(t0, "100.00", mapOf("USD" to assetRow("100.00", "1", "100.00"))),
                    snapshot(t1, "0.00", mapOf("USD" to assetRow("0.00", "1", "0.00"))),
                ),
                trades = emptyList(),
                rewards = listOf(
                    ledgerEvent(
                        timestamp = wdrTime,
                        asset = "USD",
                        amount = "-110.00",
                        type = KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL,
                        balance = "0.00",
                        refid = wdrRef,
                    ),
                ),
                provenanceResolver = provenance,
            )
            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE
        }

        "calculate absorbs owner withdrawal exceeding synthetic total within dust tolerance" {
            val t0 = now
            val t1 = t0.plusSeconds(30)
            val sellTime = t0.plusSeconds(5)
            val wdrTime = t0.plusSeconds(15)
            val wdrRef = "WIRE-overdrawn-dust"
            val provenance = SimpleFundingProvenanceResolver(
                withdrawals = listOf(
                    WithdrawStatusRecord(
                        refid = wdrRef,
                        txid = "tx-wdr-dust",
                        asset = "USD",
                        amount = BigDecimal("100.00"),
                        time = wdrTime,
                        status = "Success",
                        method = "Wire",
                    ),
                ),
            )
            // Baseline has USD 90.00 and BTC 1.0 @ $10.00 = $100.00
            val s0 = snapshot(
                t0,
                "100.00",
                mapOf(
                    "USD" to assetRow("90.00", "1.0", "90.00"),
                    "BTC" to assetRow("1.00000000", "10.00", "10.00"),
                ),
            )
            // In actual, BTC is sold for USD 10.00, raising actual USD balance to 100.00
            val sellTrade = trade(
                timestamp = sellTime,
                side = "SELL",
                symbol = "BTC",
                volume = "1.00000000",
                price = "10.00",
                usdAmount = "10.00",
                source = TradeSource.API_FILL,
                tradeId = "SELL-BTC-1",
            )
            val s1 = snapshot(
                t1,
                "0.00",
                mapOf(
                    "USD" to assetRow("0.00", "1.0", "0.00"),
                    "BTC" to assetRow("0.00000000", "9.50", "0.00"),
                ),
            )
            // At withdrawal time, BTC price in synthetic benchmark has dropped to $9.50, so synthetic total is 90 + 9.50 = 99.50
            // Owner withdrawal of $100.00 exceeds synthetic total of 99.50 by $0.50 (within $1.00 dust tolerance)
            val priceProvider = HistoricalPriceProvider { symbol, time ->
                if (symbol == "BTC") {
                    if (time >= wdrTime) BigDecimal("9.50") else BigDecimal("10.00")
                } else {
                    BigDecimal.ONE
                }
            }
            val result = calculate(
                snapshots = listOf(s0, s1),
                trades = listOf(sellTrade),
                rewards = listOf(
                    ledgerEvent(
                        timestamp = wdrTime,
                        asset = "USD",
                        amount = "-100.00",
                        type = KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL,
                        balance = "0.00",
                        refid = wdrRef,
                    ),
                ),
                priceProvider = priceProvider,
                provenanceResolver = provenance,
            )
            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.NON_POSITIVE_BASELINE
        }

        "buildBenchmarkEventsForTest returns ambiguous when funding normalization straddles baseline" {
            val t0 = now
            val baseline = snapshot(t0, "5000.00", mapOf("USD" to assetRow("5000.00", "1", "5000.00")))
            val ref = "CARD-STRADDLE-TEST"
            val ledgers = listOf(
                ledgerEvent(
                    timestamp = t0.minusSeconds(10),
                    asset = "USD",
                    amount = "5000.00",
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    refid = ref,
                    ledgerId = "dep-pre-baseline",
                ),
                ledgerEvent(
                    timestamp = t0.plusSeconds(5),
                    asset = "USD",
                    amount = "-100.00",
                    type = KrakenApiConstants.LEDGER_TYPE_SPEND,
                    refid = ref,
                    ledgerId = "spend-post-baseline",
                ),
                ledgerEvent(
                    timestamp = t0.plusSeconds(5),
                    asset = "BTC",
                    amount = "0.002",
                    type = KrakenApiConstants.LEDGER_TYPE_RECEIVE,
                    refid = ref,
                    ledgerId = "recv-post-baseline",
                ),
            )
            val provenance = SimpleFundingProvenanceResolver(
                deposits = listOf(
                    DepositStatusRecord(
                        refid = ref,
                        txid = "tx-card-straddle",
                        asset = "USD",
                        amount = BigDecimal("5000.00"),
                        time = t0.minusSeconds(10),
                        status = "Success",
                        method = "Visa",
                    ),
                ),
            )
            RebalancerComparisonCalculator.buildBenchmarkEventsForTest(
                ledgers = ledgers,
                baseline = baseline,
                inceptionWeights = mapOf("USD" to BigDecimal.ONE),
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal("50000.00"))),
                provenanceResolver = provenance,
            ) shouldBe emptyList()
        }
        "Test A: sub-cent trade must be consumed when evidenced by raw snapshot transition" {
            val t0 = now
            val t1 = now.plusSeconds(3)
            val s0 = snapshot(
                timestamp = t0,
                totalValueUSD = "100.0002",
                assets = mapOf("USD" to assetRow("100.0002", "1", "100.0002")),
                balancesObservedAt = null,
            )
            val s1 = snapshot(
                timestamp = t1,
                totalValueUSD = "100.0000",
                assets = mapOf("USD" to assetRow("100.0000", "1", "100.0000")),
                balancesObservedAt = null,
            )
            val subCentTrade = trade(
                timestamp = t1,
                side = "BUY",
                symbol = "ATOM",
                volume = "0.0001",
                usdAmount = "0.00",
                price = "2.0",
                fee = "0",
                source = TradeSource.API_FILL,
                tradeId = "TRADE-SUBCENT-1",
            )
            val tradeLedgerUSD = ledgerEvent(
                timestamp = t1,
                asset = "USD",
                amount = "-0.0002",
                type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                refid = "TRADE-SUBCENT-1",
                balance = "100.0000",
            )
            val tradeLedgerBase = ledgerEvent(
                timestamp = t1,
                asset = "ATOM",
                amount = "0.0001",
                type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                refid = "TRADE-SUBCENT-1",
                balance = "0.0001",
            )
            val result = calculate(
                snapshots = listOf(s0, s1),
                trades = listOf(subCentTrade),
                rewards = listOf(tradeLedgerUSD, tradeLedgerBase),
                priceProvider = mapPriceProvider(mapOf("ATOM" to BigDecimal("2.0"))),
            )
            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points.size shouldBe 2
        }

        "Test B: later interval with whole-dollar trade becomes unique after sub-cent trade is consumed" {
            val t0 = now
            val t1 = now.plusSeconds(30)
            val t2 = now.plusSeconds(60)
            val s0 = snapshot(
                timestamp = t0,
                totalValueUSD = "150.00",
                assets = mapOf(
                    "USD" to assetRow("100.0002", "1", "100.0002"),
                    "ATOM" to assetRow("24.9999", "2", "49.9998"),
                ),
                balancesObservedAt = null,
            )
            val s1 = snapshot(
                timestamp = t1,
                totalValueUSD = "150.00",
                assets = mapOf(
                    "USD" to assetRow("100.0000", "1", "100.0000"),
                    "ATOM" to assetRow("25.0000", "2", "50.0000"),
                ),
                balancesObservedAt = null,
            )
            val s2 = snapshot(
                timestamp = t2,
                totalValueUSD = "150.00",
                assets = mapOf(
                    "USD" to assetRow("150.0000", "1", "150.0000"),
                    "ATOM" to assetRow("0.0000", "2", "0.0000"),
                ),
                balancesObservedAt = null,
            )
            val subCentTrade = trade(
                timestamp = t1,
                side = "BUY",
                symbol = "ATOM",
                volume = "0.0001",
                usdAmount = "0.00",
                price = "2.0",
                fee = "0",
                source = TradeSource.API_FILL,
                tradeId = "TRADE-SUBCENT-1",
            )
            val subCentLedgerUSD = ledgerEvent(
                timestamp = t1,
                asset = "USD",
                amount = "-0.0002",
                type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                refid = "TRADE-SUBCENT-1",
                balance = "100.0000",
            )
            val subCentLedgerBase = ledgerEvent(
                timestamp = t1,
                asset = "ATOM",
                amount = "0.0001",
                type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                refid = "TRADE-SUBCENT-1",
                balance = "25.0000",
            )
            val laterTrade = trade(
                timestamp = t2,
                side = "SELL",
                symbol = "ATOM",
                volume = "25.0",
                usdAmount = "50.00",
                price = "2.0",
                fee = "0",
                source = TradeSource.API_FILL,
                tradeId = "TRADE-LATER-1",
            )
            val laterLedgerUSD = ledgerEvent(
                timestamp = t2,
                asset = "USD",
                amount = "50.0000",
                type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                refid = "TRADE-LATER-1",
                balance = "150.0000",
            )
            val laterLedgerBase = ledgerEvent(
                timestamp = t2,
                asset = "ATOM",
                amount = "-25.0",
                type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                refid = "TRADE-LATER-1",
                balance = "0.0",
            )
            val result = calculate(
                snapshots = listOf(s0, s1, s2),
                trades = listOf(subCentTrade, laterTrade),
                rewards = listOf(subCentLedgerUSD, subCentLedgerBase, laterLedgerUSD, laterLedgerBase),
            )
            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points.size shouldBe 3
        }

        "Test C: tiny unexplained numerical noise without matching trade remains tolerated" {
            val t0 = now
            val t1 = now.plusSeconds(5)
            // USD has tiny noise (0.002) which rounds away at 2 decimals (100.00 == 100.00)
            val s0 = snapshot(
                timestamp = t0,
                totalValueUSD = "100.0020",
                assets = mapOf("USD" to assetRow("100.0020", "1", "100.0020")),
                balancesObservedAt = null,
            )
            val s1 = snapshot(
                timestamp = t1,
                totalValueUSD = "100.0000",
                assets = mapOf("USD" to assetRow("100.0000", "1", "100.0000")),
                balancesObservedAt = null,
            )
            val result = calculate(
                snapshots = listOf(s0, s1),
                trades = emptyList(),
                rewards = emptyList(),
            )
            result.availability shouldBe ComparisonAvailability.AVAILABLE
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.points.size shouldBe 2
        }

        "Test D: genuine ambiguity between identical candidate subsets still fails closed" {
            val t0 = now
            val t1 = now.plusSeconds(5)
            val s0 = snapshot(
                timestamp = t0,
                totalValueUSD = "100.00",
                assets = mapOf("USD" to assetRow("100.00", "1", "100.00")),
                balancesObservedAt = null,
            )
            val s1 = snapshot(
                timestamp = t1,
                totalValueUSD = "150.00",
                assets = mapOf("USD" to assetRow("150.00", "1", "150.00")),
                balancesObservedAt = null,
            )
            val tradeA = trade(
                timestamp = t1,
                side = "SELL",
                symbol = "ATOM",
                volume = "25.0",
                usdAmount = "50.00",
                price = "2.0",
                fee = "0",
                source = TradeSource.API_FILL,
                tradeId = "TRADE-AMBIG-A",
            )
            val ledgerUSDA = ledgerEvent(
                timestamp = t1,
                asset = "USD",
                amount = "50.0000",
                type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                refid = "TRADE-AMBIG-A",
                balance = "150.0000",
            )
            val ledgerBaseA = ledgerEvent(
                timestamp = t1,
                asset = "ATOM",
                amount = "-25.0",
                type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                refid = "TRADE-AMBIG-A",
                balance = "0.0",
            )
            val tradeB = trade(
                timestamp = t1,
                side = "SELL",
                symbol = "ATOM",
                volume = "25.0",
                usdAmount = "50.00",
                price = "2.0",
                fee = "0",
                source = TradeSource.API_FILL,
                tradeId = "TRADE-AMBIG-B",
            )
            val ledgerUSDB = ledgerEvent(
                timestamp = t1,
                asset = "USD",
                amount = "50.0000",
                type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                refid = "TRADE-AMBIG-B",
                balance = "150.0000",
            )
            val ledgerBaseB = ledgerEvent(
                timestamp = t1,
                asset = "ATOM",
                amount = "-25.0",
                type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                refid = "TRADE-AMBIG-B",
                balance = "0.0",
            )
            val result = calculate(
                snapshots = listOf(s0, s1),
                trades = listOf(tradeA, tradeB),
                rewards = listOf(ledgerUSDA, ledgerBaseA, ledgerUSDB, ledgerBaseB),
            )
            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE
        }
        "dec6 fix A exact boundary ledger at curr timestamp reconciles" {
            val t0 = now
            val t1 = t0.plusMillis(200)
            val result = calculate(
                snapshots = listOf(
                    snapshot(
                        t0,
                        "100.00",
                        mapOf(
                            "BTC" to assetRow("0.00000000", "1", "0.00"),
                            "USD" to assetRow("100.00", "1", "100.00"),
                        ),
                        balancesObservedAt = null,
                    ),
                    snapshot(
                        t1,
                        "101.00",
                        mapOf(
                            "BTC" to assetRow("1.00000000", "1", "1.00"),
                            "USD" to assetRow("100.00", "1", "100.00"),
                        ),
                        balancesObservedAt = null,
                    ),
                ),
                rewards = listOf(
                    ledgerEvent(
                        timestamp = t1,
                        asset = "BTC",
                        amount = "1.00000000",
                        type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                        balance = "1.00000000",
                    ),
                ),
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal("1"))),
            )
            result.availability shouldBe ComparisonAvailability.AVAILABLE
        }

        "dec6 fix B ledger embodied in prev snapshot is not reused" {
            val t0 = now
            val t1 = t0.plusMillis(200)
            val snapshots = listOf(
                snapshot(
                    t0,
                    "101.00",
                    mapOf(
                        "BTC" to assetRow("1.00000000", "1", "1.00"),
                        "USD" to assetRow("100.00", "1", "100.00"),
                    ),
                    balancesObservedAt = null,
                ),
                snapshot(
                    t1,
                    "101.00",
                    mapOf(
                        "BTC" to assetRow("1.00000000", "1", "1.00"),
                        "USD" to assetRow("100.00", "1", "100.00"),
                    ),
                    balancesObservedAt = null,
                ),
            )
            val result = calculate(
                snapshots = snapshots,
                rewards = listOf(
                    ledgerEvent(
                        timestamp = t0,
                        asset = "BTC",
                        amount = "0.50000000",
                        type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                        balance = "1.00000000",
                    ),
                ),
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal("1"))),
            )
            result.availability shouldBe ComparisonAvailability.AVAILABLE
        }

        "dec6 fix C future ledger after curr timestamp fails closed" {
            val t0 = now
            val t1 = t0.plusMillis(200)
            val result = calculate(
                snapshots = listOf(
                    snapshot(
                        t0,
                        "100.00",
                        mapOf(
                            "BTC" to assetRow("0.00000000", "1", "0.00"),
                            "USD" to assetRow("100.00", "1", "100.00"),
                        ),
                        balancesObservedAt = null,
                    ),
                    snapshot(
                        t1,
                        "101.00",
                        mapOf(
                            "BTC" to assetRow("1.00000000", "1", "1.00"),
                            "USD" to assetRow("100.00", "1", "100.00"),
                        ),
                        balancesObservedAt = null,
                    ),
                ),
                rewards = listOf(
                    ledgerEvent(
                        timestamp = t1.plusSeconds(5),
                        asset = "BTC",
                        amount = "1.00000000",
                        type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                        balance = "1.00000000",
                    ),
                ),
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal("1"))),
            )
            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE
        }

        "dec6 fix D contradictory authoritative post balance fails closed" {
            val t0 = now
            val t1 = t0.plusMillis(200)
            val result = calculate(
                snapshots = listOf(
                    snapshot(
                        t0,
                        "100.00",
                        mapOf(
                            "BTC" to assetRow("100.00000000", "1", "100.00"),
                            "USD" to assetRow("0.00", "1", "0.00"),
                        ),
                        balancesObservedAt = null,
                    ),
                    snapshot(
                        t1,
                        "150.00",
                        mapOf(
                            "BTC" to assetRow("150.00000000", "1", "150.00"),
                            "USD" to assetRow("0.00", "1", "0.00"),
                        ),
                        balancesObservedAt = null,
                    ),
                ),
                rewards = listOf(
                    ledgerEvent(
                        timestamp = t1,
                        asset = "BTC",
                        amount = "50.00000000",
                        type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                        balance = "200.00000000",
                    ),
                ),
                priceProvider = mapPriceProvider(mapOf("BTC" to BigDecimal("1"))),
            )
            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE
        }

        "dec6 fix E boundary ownership prev versus prev-plus-1ms versus curr versus curr-plus-1ms" {
            val t0 = now
            val t1 = t0.plusMillis(200)
            fun snapshotsWithDelta(): List<PortfolioSnapshot> = listOf(
                snapshot(
                    t0,
                    "100.00",
                    mapOf(
                        "BTC" to assetRow("0.00000000", "1", "0.00"),
                        "USD" to assetRow("100.00", "1", "100.00"),
                    ),
                    balancesObservedAt = null,
                ),
                snapshot(
                    t1,
                    "101.00",
                    mapOf(
                        "BTC" to assetRow("1.00000000", "1", "1.00"),
                        "USD" to assetRow("100.00", "1", "100.00"),
                    ),
                    balancesObservedAt = null,
                ),
            )
            fun depositAt(timestamp: Instant): LedgerEvent = ledgerEvent(
                timestamp = timestamp,
                asset = "BTC",
                amount = "1.00000000",
                type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                balance = "1.00000000",
            )
            val prices = mapPriceProvider(mapOf("BTC" to BigDecimal("1")))
            calculate(
                snapshots = snapshotsWithDelta(),
                rewards = listOf(depositAt(t0.plusMillis(1))),
                priceProvider = prices,
            ).availability shouldBe ComparisonAvailability.AVAILABLE
            calculate(
                snapshots = snapshotsWithDelta(),
                rewards = listOf(depositAt(t1)),
                priceProvider = prices,
            ).availability shouldBe ComparisonAvailability.AVAILABLE
            val future = calculate(
                snapshots = snapshotsWithDelta(),
                rewards = listOf(depositAt(t1.plusMillis(1))),
                priceProvider = prices,
            )
            future.availability shouldBe ComparisonAvailability.UNAVAILABLE
            future.unavailableReason shouldBe ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE
        }

        "dec7 real sell plus zero effect dust at prev timestamp reconciles minimally" {
            val t0 = now
            val t1 = t0.plusMillis(200)
            val snapshots = listOf(
                snapshot(
                    t0,
                    "100.00",
                    mapOf(
                        "BTC" to assetRow("0.00000000", "1", "0.00"),
                        "USD" to assetRow("100.00", "1", "100.00"),
                    ),
                    balancesObservedAt = null,
                ),
                snapshot(
                    t1,
                    "166.87",
                    mapOf(
                        "BTC" to assetRow("0.00000000", "1", "0.00"),
                        "USD" to assetRow("166.87", "1", "166.87"),
                    ),
                    balancesObservedAt = null,
                ),
            )
            val prices = mapPriceProvider(mapOf("BTC" to BigDecimal("1")))
            val result = calculate(
                snapshots = snapshots,
                trades = listOf(
                    trade(
                        timestamp = t1,
                        side = "sell",
                        symbol = "XLM",
                        volume = "277.77777778",
                        usdAmount = "67.00",
                        fee = "0.13",
                        price = "0.2412",
                    ),
                    trade(
                        timestamp = t0,
                        side = "sell",
                        symbol = "XLM",
                        volume = "0.00007082",
                        usdAmount = "0",
                        fee = "0",
                        price = "0.24",
                    ),
                ),
                priceProvider = prices,
            )
            result.availability shouldBe ComparisonAvailability.AVAILABLE
        }

        "dec7 two genuinely different explanations still fail closed" {
            val t0 = now
            val t1 = t0.plusMillis(200)
            val snapshots = listOf(
                snapshot(
                    t0,
                    "100.00",
                    mapOf(
                        "BTC" to assetRow("100.00000000", "1", "100.00"),
                        "USD" to assetRow("0.00", "1", "0.00"),
                    ),
                    balancesObservedAt = null,
                ),
                snapshot(
                    t1,
                    "150.00",
                    mapOf(
                        "BTC" to assetRow("150.00000000", "1", "150.00"),
                        "USD" to assetRow("0.00", "1", "0.00"),
                    ),
                    balancesObservedAt = null,
                ),
            )
            val prices = mapPriceProvider(mapOf("BTC" to BigDecimal("1")))
            val result = calculate(
                snapshots = snapshots,
                rewards = listOf(
                    ledgerEvent(
                        timestamp = t1,
                        asset = "BTC",
                        amount = "50.00000000",
                        type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                        balance = "150.00000000",
                        ledgerId = "dec7-ambig-a",
                    ),
                    ledgerEvent(
                        timestamp = t1,
                        asset = "BTC",
                        amount = "50.00000000",
                        type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                        balance = "150.00000000",
                        ledgerId = "dec7-ambig-b",
                    ),
                ),
                priceProvider = prices,
            )
            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE
        }

        "dec7 real sell plus two zero effect dusts reconciles with smallest explanation" {
            val t0 = now
            val t1 = t0.plusMillis(200)
            val snapshots = listOf(
                snapshot(
                    t0,
                    "100.00",
                    mapOf(
                        "BTC" to assetRow("0.00000000", "1", "0.00"),
                        "USD" to assetRow("100.00", "1", "100.00"),
                    ),
                    balancesObservedAt = null,
                ),
                snapshot(
                    t1,
                    "166.87",
                    mapOf(
                        "BTC" to assetRow("0.00000000", "1", "0.00"),
                        "USD" to assetRow("166.87", "1", "166.87"),
                    ),
                    balancesObservedAt = null,
                ),
            )
            val prices = mapPriceProvider(mapOf("BTC" to BigDecimal("1")))
            val result = calculate(
                snapshots = snapshots,
                trades = listOf(
                    trade(
                        timestamp = t1,
                        side = "sell",
                        symbol = "XLM",
                        volume = "277.77777778",
                        usdAmount = "67.00",
                        fee = "0.13",
                        price = "0.2412",
                    ),
                    trade(
                        timestamp = t0,
                        side = "sell",
                        symbol = "XLM",
                        volume = "0.00007082",
                        usdAmount = "0",
                        fee = "0",
                        price = "0.24",
                    ),
                    trade(
                        timestamp = t0.plusMillis(1),
                        side = "sell",
                        symbol = "XLM",
                        volume = "0.00004130",
                        usdAmount = "0",
                        fee = "0",
                        price = "0.24",
                    ),
                ),
                priceProvider = prices,
            )
            result.availability shouldBe ComparisonAvailability.AVAILABLE
        }

        "dec8 fractured dust sweep with uniform subtype reconciles as atomic transformation" {
            val t0 = now
            val t1 = t0.plusMillis(200)
            val snapshots = listOf(
                snapshot(
                    t0,
                    "101.00",
                    mapOf(
                        "BTC" to assetRow("0.00001116", "90000.00", "1.00"),
                        "XRP" to assetRow("0.00000782", "2.00", "0.00"),
                        "USD" to assetRow("100.00", "1", "100.00"),
                    ),
                    balancesObservedAt = null,
                ),
                snapshot(
                    t1,
                    "100.00",
                    mapOf(
                        "BTC" to assetRow("0E-8", "90000.00", "0.00"),
                        "XRP" to assetRow("0E-8", "2.00", "0.00"),
                        "USD" to assetRow("100.00", "1", "100.00"),
                    ),
                    balancesObservedAt = null,
                ),
            )
            val prices = mapPriceProvider(
                mapOf(
                    "BTC" to BigDecimal("90000.00"),
                    // Preserve the raw value of the BTC and XRP inputs in the DOGE receive.
                    "DOGE" to BigDecimal("0.141268022503516174"),
                ),
            )
            val result = calculate(
                snapshots = snapshots,
                rewards = listOf(
                    ledgerEvent(
                        timestamp = t1,
                        asset = "BTC",
                        amount = "-0.00001116",
                        type = KrakenApiConstants.LEDGER_TYPE_SPEND,
                        subtype = "dustsweeping",
                        balance = "0E-8",
                        ledgerId = "dec8-btc-spend",
                        refid = "dec8-sweep-1",
                    ),
                    ledgerEvent(
                        timestamp = t1,
                        asset = "XRP",
                        amount = "-0.00000782",
                        type = KrakenApiConstants.LEDGER_TYPE_SPEND,
                        subtype = "dustsweeping",
                        balance = "0E-8",
                        ledgerId = "dec8-xrp-spend",
                        refid = "dec8-sweep-1",
                    ),
                    ledgerEvent(
                        timestamp = t1,
                        asset = "DOGE",
                        amount = "7.11000000",
                        type = KrakenApiConstants.LEDGER_TYPE_RECEIVE,
                        subtype = "dustsweeping",
                        balance = "7.11000000",
                        ledgerId = "dec8-doge-receive",
                        refid = "dec8-sweep-1",
                    ),
                ),
                priceProvider = prices,
            )
            result.availability shouldBe ComparisonAvailability.AVAILABLE
        }

        "dec8 fractured sweep with blank subtypes still fails closed as ambiguous" {
            val t0 = now
            val t1 = t0.plusMillis(200)
            val snapshots = listOf(
                snapshot(
                    t0,
                    "101.00",
                    mapOf(
                        "BTC" to assetRow("0.00001116", "90000.00", "1.00"),
                        "XRP" to assetRow("0.00000782", "2.00", "0.00"),
                        "USD" to assetRow("100.00", "1", "100.00"),
                    ),
                    balancesObservedAt = null,
                ),
                snapshot(
                    t1,
                    "100.00",
                    mapOf(
                        "BTC" to assetRow("0E-8", "90000.00", "0.00"),
                        "XRP" to assetRow("0E-8", "2.00", "0.00"),
                        "USD" to assetRow("100.00", "1", "100.00"),
                    ),
                    balancesObservedAt = null,
                ),
            )
            val prices = mapPriceProvider(mapOf("BTC" to BigDecimal("90000.00")))
            val result = calculate(
                snapshots = snapshots,
                rewards = listOf(
                    ledgerEvent(
                        timestamp = t1,
                        asset = "BTC",
                        amount = "-0.00001116",
                        type = KrakenApiConstants.LEDGER_TYPE_SPEND,
                        balance = "0E-8",
                        ledgerId = "dec8-blank-btc-spend",
                        refid = "dec8-sweep-blank",
                    ),
                    ledgerEvent(
                        timestamp = t1,
                        asset = "XRP",
                        amount = "-0.00000782",
                        type = KrakenApiConstants.LEDGER_TYPE_SPEND,
                        balance = "0E-8",
                        ledgerId = "dec8-blank-xrp-spend",
                        refid = "dec8-sweep-blank",
                    ),
                ),
                priceProvider = prices,
            )
            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.AMBIGUOUS_LEDGER_TYPE
        }

        "dec8 fractured sweep consuming assigned receive leg reconciles" {
            val t0 = now
            val t1 = t0.plusMillis(200)
            val snapshots = listOf(
                snapshot(
                    t0,
                    "101.00",
                    mapOf(
                        "BTC" to assetRow("0.00001116", "90000.00", "1.00"),
                        "XRP" to assetRow("0E-8", "2.00", "0.00"),
                        "USD" to assetRow("100.00", "1", "100.00"),
                    ),
                    balancesObservedAt = null,
                ),
                snapshot(
                    t1,
                    "100.00",
                    mapOf(
                        "BTC" to assetRow("0E-8", "90000.00", "0.00"),
                        "XRP" to assetRow("0.00000782", "2.00", "0.00"),
                        "USD" to assetRow("100.00", "1", "100.00"),
                    ),
                    balancesObservedAt = null,
                ),
            )
            val prices = mapPriceProvider(mapOf("BTC" to BigDecimal("90000.00")))
            val result = calculate(
                snapshots = snapshots,
                rewards = listOf(
                    ledgerEvent(
                        timestamp = t1,
                        asset = "BTC",
                        amount = "-0.00001116",
                        type = KrakenApiConstants.LEDGER_TYPE_SPEND,
                        subtype = "dustsweeping",
                        balance = "0E-8",
                        ledgerId = "dec8-c-btc-spend",
                        refid = "dec8-sweep-c",
                    ),
                    ledgerEvent(
                        timestamp = t1,
                        asset = "XRP",
                        amount = "0.00000782",
                        type = KrakenApiConstants.LEDGER_TYPE_RECEIVE,
                        subtype = "dustsweeping",
                        balance = "0.00000782",
                        ledgerId = "dec8-c-xrp-receive",
                        refid = "dec8-sweep-c",
                    ),
                    ledgerEvent(
                        timestamp = t1,
                        asset = "ZEC",
                        amount = "-0.00000347",
                        type = KrakenApiConstants.LEDGER_TYPE_SPEND,
                        subtype = "dustsweeping",
                        balance = "0E-8",
                        ledgerId = "dec8-c-zec-spend",
                        refid = "dec8-sweep-c",
                    ),
                ),
                priceProvider = prices,
            )
            result.availability shouldBe ComparisonAvailability.AVAILABLE
        }

        "dec8 fractured sweep with mixed subtypes still fails closed as ambiguous" {
            val t0 = now
            val t1 = t0.plusMillis(200)
            val snapshots = listOf(
                snapshot(
                    t0,
                    "101.00",
                    mapOf(
                        "BTC" to assetRow("0.00001116", "90000.00", "1.00"),
                        "XRP" to assetRow("0.00000782", "2.00", "0.00"),
                        "USD" to assetRow("100.00", "1", "100.00"),
                    ),
                    balancesObservedAt = null,
                ),
                snapshot(
                    t1,
                    "100.00",
                    mapOf(
                        "BTC" to assetRow("0E-8", "90000.00", "0.00"),
                        "XRP" to assetRow("0E-8", "2.00", "0.00"),
                        "USD" to assetRow("100.00", "1", "100.00"),
                    ),
                    balancesObservedAt = null,
                ),
            )
            val prices = mapPriceProvider(mapOf("BTC" to BigDecimal("90000.00")))
            val result = calculate(
                snapshots = snapshots,
                rewards = listOf(
                    ledgerEvent(
                        timestamp = t1,
                        asset = "BTC",
                        amount = "-0.00001116",
                        type = KrakenApiConstants.LEDGER_TYPE_SPEND,
                        subtype = "dustsweeping",
                        balance = "0E-8",
                        ledgerId = "dec8-mixed-btc-spend",
                        refid = "dec8-sweep-mixed",
                    ),
                    ledgerEvent(
                        timestamp = t1,
                        asset = "XRP",
                        amount = "-0.00000782",
                        type = KrakenApiConstants.LEDGER_TYPE_SPEND,
                        balance = "0E-8",
                        ledgerId = "dec8-mixed-xrp-spend",
                        refid = "dec8-sweep-mixed",
                    ),
                ),
                priceProvider = prices,
            )
            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.AMBIGUOUS_LEDGER_TYPE
        }

        "jan4 in span fill wins exact tie against fuzzy future fill" {
            val t0 = now
            val t1 = t0.plusMillis(200)
            val t2 = t1.plusMillis(500)
            val snapshots = listOf(
                snapshot(
                    t0,
                    "100.00",
                    mapOf(
                        "USD" to assetRow("100.00", "1", "100.00"),
                    ),
                    balancesObservedAt = null,
                ),
                snapshot(
                    t1,
                    "81.75",
                    mapOf(
                        "USD" to assetRow("81.75", "1", "81.75"),
                    ),
                    balancesObservedAt = null,
                ),
                snapshot(
                    t2,
                    "81.75",
                    mapOf(
                        "USD" to assetRow("81.75", "1", "81.75"),
                    ),
                    balancesObservedAt = null,
                ),
            )
            val prices = mapPriceProvider(
                mapOf("BTC" to BigDecimal("1"), "VET" to BigDecimal("0.01190200")),
            )
            val result = calculate(
                snapshots = snapshots,
                trades = listOf(
                    trade(
                        timestamp = t1,
                        side = "buy",
                        symbol = "VET",
                        volume = "1533",
                        usdAmount = "18.25",
                        fee = "0",
                        price = "0.01190200",
                    ),
                    trade(
                        timestamp = t1.plusMillis(137),
                        side = "buy",
                        symbol = "VET",
                        volume = "1533",
                        usdAmount = "18.25",
                        fee = "0",
                        price = "0.01190200",
                    ),
                ),
                priceProvider = prices,
            )
            result.availability shouldBe ComparisonAvailability.AVAILABLE
        }

        "jan4 same span identical fills still fail closed as ambiguous" {
            val t0 = now
            val t1 = t0.plusMillis(200)
            val snapshots = listOf(
                snapshot(
                    t0,
                    "100.00",
                    mapOf(
                        "USD" to assetRow("100.00", "1", "100.00"),
                    ),
                    balancesObservedAt = null,
                ),
                snapshot(
                    t1,
                    "81.75",
                    mapOf(
                        "USD" to assetRow("81.75", "1", "81.75"),
                    ),
                    balancesObservedAt = null,
                ),
            )
            val prices = mapPriceProvider(mapOf("BTC" to BigDecimal("1")))
            val result = calculate(
                snapshots = snapshots,
                trades = listOf(
                    trade(
                        timestamp = t1,
                        side = "buy",
                        symbol = "VET",
                        volume = "1533",
                        usdAmount = "18.25",
                        fee = "0",
                        price = "0.01190200",
                    ),
                    trade(
                        timestamp = t1,
                        side = "buy",
                        symbol = "VET",
                        volume = "1533",
                        usdAmount = "18.25",
                        fee = "0",
                        price = "0.01190200",
                    ),
                ),
                priceProvider = prices,
            )
            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE
        }

        "jan4 lone fuzzy future fill still reconciles without in span rival" {
            val t0 = now
            val t1 = t0.plusMillis(200)
            val t2 = t1.plusMillis(500)
            val snapshots = listOf(
                snapshot(
                    t0,
                    "100.00",
                    mapOf(
                        "USD" to assetRow("100.00", "1", "100.00"),
                    ),
                    balancesObservedAt = null,
                ),
                snapshot(
                    t1,
                    "81.75",
                    mapOf(
                        "USD" to assetRow("81.75", "1", "81.75"),
                    ),
                    balancesObservedAt = null,
                ),
                snapshot(
                    t2,
                    "81.75",
                    mapOf(
                        "USD" to assetRow("81.75", "1", "81.75"),
                    ),
                    balancesObservedAt = null,
                ),
            )
            val prices = mapPriceProvider(
                mapOf("BTC" to BigDecimal("1"), "VET" to BigDecimal("0.01190200")),
            )
            val result = calculate(
                snapshots = snapshots,
                trades = listOf(
                    trade(
                        timestamp = t1.plusMillis(137),
                        side = "buy",
                        symbol = "VET",
                        volume = "1533",
                        usdAmount = "18.25",
                        fee = "0",
                        price = "0.01190200",
                    ),
                ),
                priceProvider = prices,
            )
            result.availability shouldBe ComparisonAvailability.AVAILABLE
        }
    }

    private data class LegacySeriesScopeFixture(
        val snapshots: List<PortfolioSnapshot>,
        val trades: List<TradeRecord>,
        val rewards: List<LedgerEvent>,
        val successorTimestamp: Instant,
    )

    // Production shape at the 2025-12-05 inception boundary: the approved baseline owns the
    // complete wallet while the legacy series writes only configured-universe rows. The XMR
    // buy settles through its USD quote leg; MORPHO is a baseline holding the legacy series
    // never records.
    private fun legacySeriesScopeFixture(): LegacySeriesScopeFixture {
        val t0 = Instant.parse("2025-12-05T17:00:56.973Z")
        val t1 = t0.plusMillis(101)
        val baseline = snapshot(
            t0,
            "1860.7932",
            mapOf(
                "USD" to assetRow("1490.5632", "1", "1490.5632"),
                "MORPHO" to assetRow("286.4401", "1.2917", "369.99"),
                "BTC" to assetRow("0.24", "1", "0.24"),
                "XMR" to assetRow("0", "395.69", "0"),
            ),
            balancesObservedAt = null,
        )
        val successor = snapshot(
            t1,
            "1267.8543",
            mapOf(
                "USD" to assetRow("1267.6143", "1", "1267.6143"),
                "BTC" to assetRow("0.24", "1", "0.24"),
            ),
            balancesObservedAt = null,
        )
        val buy = trade(
            timestamp = t1,
            side = "BUY",
            symbol = "XMR",
            volume = "0.56119848",
            usdAmount = "222.06",
            fee = "0.8882",
            source = TradeSource.API_FILL,
            tradeId = "T6Z73R-C6OVC-AFWUA3",
            orderTxid = "O7T5FM-XMEVI-QXMFVW",
            price = "395.69",
        )
        val legs = listOf(
            ledgerEvent(
                t1,
                "XMR",
                "0.56119848",
                type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                subtype = "tradespot",
                fee = "0",
                balance = "0.56119848",
                ledgerId = "LIG3K7-KLC4B-REA3ZV",
                refid = "T6Z73R-C6OVC-AFWUA3",
                hasAuthoritativeFee = true,
            ),
            ledgerEvent(
                t1,
                "USD",
                "-222.0607",
                type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                subtype = "tradespot",
                fee = "0.8882",
                balance = "1267.6143",
                ledgerId = "LOG2MN-OXNJG-XFDHZM",
                refid = "T6Z73R-C6OVC-AFWUA3",
                hasAuthoritativeFee = true,
            ),
        )
        return LegacySeriesScopeFixture(listOf(baseline, successor), listOf(buy), legs, t1)
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
                source = TradeSource.MANUAL,
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
        source = TradeSource.MANUAL,
        cycleId = null,
        clientOrderId = null,
        tradeId = tradeId,
        orderTxid = orderTxid,
    )

    private data class UniverseSplitConversionFixture(
        val snapshots: List<PortfolioSnapshot>,
        val ledgers: List<LedgerEvent>,
        val ledgerContext: List<LedgerEvent>,
        val conversionTime: Instant,
    )

    private fun universeSplitConversionFixture(conversionRefid: String? = "Unknown"): UniverseSplitConversionFixture {
        val t = now.plusSeconds(3600)
        fun usd(balance: String, valueUSD: String) = assetRow(balance, "1", valueUSD)
        val snapshots = listOf(
            snapshot(
                t.minusSeconds(3600),
                "389.28",
                mapOf("USD" to usd("389.2793", "389.28")),
                balancesObservedAt = null,
            ),
            snapshot(t, "389.28", mapOf("USD" to usd("389.2793", "389.28")), balancesObservedAt = null),
            snapshot(t, "1389.28", mapOf("USD" to usd("1389.2793", "1389.28")), balancesObservedAt = null),
            snapshot(t, "389.28", mapOf("USD" to usd("389.2793", "389.28")), balancesObservedAt = null),
        )
        val ledgerContext = listOf(
            ledgerEvent(
                t.minusSeconds(3600),
                "USD",
                "389.2793",
                type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                balance = "389.2793",
                ledgerId = "universe-split-dep-0",
                refid = "FT-universe-split-0",
                hasAuthoritativeFee = true,
            ),
        ).map { it.copy(aclass = "currency") }
        val ledgers = listOf(
            ledgerEvent(
                t,
                "USD",
                "1000",
                type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                balance = "1389.2793",
                ledgerId = "universe-split-dep-1",
                refid = "FT-universe-split-1",
                hasAuthoritativeFee = true,
            ),
            ledgerEvent(
                t,
                "USD",
                "-1000",
                type = KrakenApiConstants.LEDGER_TYPE_CONVERSION,
                balance = "389.2793",
                ledgerId = "universe-split-spend",
                refid = conversionRefid,
                hasAuthoritativeFee = true,
            ),
            ledgerEvent(
                t,
                "USDG",
                "1000",
                type = KrakenApiConstants.LEDGER_TYPE_CONVERSION,
                balance = "1000",
                ledgerId = "universe-split-receive",
                refid = conversionRefid,
                hasAuthoritativeFee = true,
            ),
        ).map { it.copy(aclass = "currency") }
        return UniverseSplitConversionFixture(snapshots, ledgers, ledgerContext, t)
    }

    private fun ledgerEvent(
        timestamp: Instant,
        asset: String,
        amount: String,
        type: String = KrakenApiConstants.LEDGER_TYPE_STAKING,
        subtype: String? = null,
        fee: String = "0",
        balance: String? = null,
        ledgerId: String? = null,
        refid: String? = null,
        hasAuthoritativeFee: Boolean = fee != "0",
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
            subtype = subtype,
            asset = asset,
            amount = BigDecimal(amount),
            fee = BigDecimal(fee),
            balance = balance?.let(::BigDecimal) ?: BigDecimal.ZERO,
            hasAuthoritativeBalance = balance != null,
            hasAuthoritativeFee = hasAuthoritativeFee,
        )
    }
}
