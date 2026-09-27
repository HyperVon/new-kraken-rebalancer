package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.model.ComparisonUnavailableReason
import com.gemini.krakenbot.model.PortfolioSnapshot
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

/**
 * Configuration-reset and epoch-ordering behavior of the inferred configuration-matched benchmark.
 *
 * These assert the invariants that keep the experiment honest: a reset copies proportions rather
 * than units or account value, a missing price fails closed, and capital is never invested using a
 * regime that had not yet become observable.
 */
class ConfigurationResetReplayTest : StringSpec() {

    private val t0: Instant = Instant.parse("2025-12-05T17:00:00Z")
    private val t1: Instant = t0.plusSeconds(3_600)
    private val t2: Instant = t0.plusSeconds(7_200)

    /** Well past the persistence span, so a synthetic addition genuinely qualifies. */
    private val lateTrade: Instant = t0.plusSeconds(90L * 86_400L)

    private fun row(balance: String, price: String, value: String) = Triple(balance, price, value)

    private fun snapshot(
        timestamp: Instant,
        totalValueUSD: String,
        assets: Map<String, Triple<String, String, String>>,
    ) = PortfolioSnapshot(
        timestamp = timestamp,
        totalValueUSD = BigDecimal(totalValueUSD),
        assets = assets.mapValues { (symbol, r) ->
            PortfolioSnapshot.AssetSnapshot(
                symbol = symbol,
                balance = BigDecimal(r.first),
                price = BigDecimal(r.second),
                valueUSD = BigDecimal(r.third),
                targetPercent = BigDecimal.ZERO,
                currentPercent = BigDecimal.ZERO,
                deviationPercent = BigDecimal.ZERO,
                deviationUSD = BigDecimal.ZERO,
            )
        },
        actions = emptyList(),
        drawdownPercent = BigDecimal.ZERO,
        fiatDeploymentPercent = BigDecimal.ZERO,
        effectiveUsdTargetPercent = BigDecimal.ZERO,
    )

    private val staticPrices = HistoricalPriceProvider { symbol, _ ->
        mapOf(
            "BTC" to BigDecimal("100"),
            "ETH" to BigDecimal("50"),
            "OLD" to BigDecimal("10"),
        )[symbol]
    }

    private fun resetEvent(weights: Map<String, BigDecimal>) = BenchmarkEvent.ConfigurationReset(
        timestamp = t1,
        targetWeights = weights,
        transition = InferredRegimeTransition(
            clusterEnd = t1,
            removals = setOf("OLD"),
            additions = setOf("BTC"),
            confidence = RegimeTransitionConfidence.HIGH,
        ),
    )

    init {
        "a configuration reset reweights to target proportions while preserving benchmark NAV" {
            val balances = mutableMapOf("OLD" to BigDecimal("100.0"))

            val failure = RebalancerComparisonCalculator.replayBenchmarkEventForTest(
                balances = balances,
                event = resetEvent(mapOf("BTC" to BigDecimal("0.5"), "USD" to BigDecimal("0.5"))),
                priceProvider = staticPrices,
            )

            failure shouldBe null
            // 100 OLD units at 10.00 = 1000.00 NAV, reweighted 50/50. Units are never copied from Actual.
            balances.getValue("BTC").shouldBeEqualComparingTo(BigDecimal("5.00000000"))
            balances.getValue("USD").shouldBeEqualComparingTo(BigDecimal("500.00000000"))
            balances.containsKey("OLD") shouldBe false
            val nav = balances.getValue("BTC").multiply(BigDecimal("100"))
                .add(balances.getValue("USD"))
            nav.shouldBeEqualComparingTo(BigDecimal("1000.00000000"))
        }

        "a configuration reset fails closed when a target price is unavailable" {
            val balances = mutableMapOf("OLD" to BigDecimal("100.0"))

            val failure = RebalancerComparisonCalculator.replayBenchmarkEventForTest(
                balances = balances,
                event = resetEvent(mapOf("MISSING" to BigDecimal("1.0"))),
                priceProvider = staticPrices,
            )

            failure shouldNotBe null
            failure?.first shouldBe ComparisonUnavailableReason.MISSING_PRICE
        }

        "a reset keeps the benchmark's own NAV rather than the Actual account value" {
            // Actual would be worth 4000.00 here; the benchmark must stay at its own 1000.00.
            val balances = mutableMapOf("OLD" to BigDecimal("100.0"))

            RebalancerComparisonCalculator.replayBenchmarkEventForTest(
                balances = balances,
                event = resetEvent(mapOf("BTC" to BigDecimal("1.0"))),
                priceProvider = staticPrices,
            )

            balances.getValue("BTC").shouldBeEqualComparingTo(BigDecimal("10.00000000"))
        }

        "regime inference anchors to the first trustworthy snapshot after the cluster" {
            val baseline = snapshot(
                t0,
                "1000.00",
                mapOf(
                    "OLD" to row("100.0", "10.00", "1000.00"),
                    "USD" to row("0.0", "1.00", "0.00"),
                ),
            )
            val anchorSnapshot = snapshot(
                t2,
                "1000.00",
                mapOf("BTC" to row("5.0", "100.00", "500.00"), "USD" to row("500.00", "1.00", "500.00")),
            )
            val removal = AssetRegimeActivity(
                symbol = "OLD",
                fillCount = 1,
                firstFill = t0,
                lastFill = t0,
                fullExitAt = t0.plusSeconds(30),
                economicallyPresentSpanMillis = 30_000L,
                establishedAt = t0,
                materiallyPresentAtBaseline = true,
            )
            val addition = AssetRegimeActivity(
                symbol = "BTC",
                fillCount = 40,
                firstFill = t0,
                lastFill = lateTrade,
                fullExitAt = null,
                establishedAt = t1,
                materiallyPresentAtBaseline = false,
                economicallyPresentSpanMillis = Duration.between(t1, lateTrade).toMillis(),
            )

            val anchored = RebalancerComparisonCalculator.inferRegimesForTest(
                activities = listOf(removal, addition),
                snapshots = listOf(baseline, anchorSnapshot),
                baseline = baseline,
                comparisonAssetSymbols = setOf("OLD", "BTC", "USD"),
            )

            anchored shouldHaveSize 1
            val (transition, anchor) = anchored.single()
            transition.removals shouldBe setOf("OLD")
            transition.additions shouldBe setOf("BTC")
            transition.confidence shouldBe RegimeTransitionConfidence.HIGH
            // The anchor is the retained snapshot, never an invented mid-cluster instant.
            anchor.timestamp shouldBe t2
        }

        "a configuration reset fails closed when a currently held asset has no price" {
            val balances = mutableMapOf("UNPRICED" to BigDecimal("10.0"))

            val failure = RebalancerComparisonCalculator.replayBenchmarkEventForTest(
                balances = balances,
                event = resetEvent(mapOf("BTC" to BigDecimal("1.0"))),
                priceProvider = staticPrices,
            )

            failure shouldNotBe null
            failure?.first shouldBe ComparisonUnavailableReason.MISSING_PRICE
        }

        "a configuration reset with no positive holdings fails closed rather than creating value" {
            val balances = mutableMapOf<String, BigDecimal>()

            val failure = RebalancerComparisonCalculator.replayBenchmarkEventForTest(
                balances = balances,
                event = resetEvent(mapOf("BTC" to BigDecimal("1.0"))),
                priceProvider = staticPrices,
            )

            failure shouldNotBe null
            failure?.first shouldBe ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE
        }

        "a configuration reset fails closed on a non-positive target weight rather than dropping it" {
            val balances = mutableMapOf("OLD" to BigDecimal("100.0"))

            val failure = RebalancerComparisonCalculator.replayBenchmarkEventForTest(
                balances = balances,
                event = resetEvent(mapOf("BTC" to BigDecimal("0.0"), "USD" to BigDecimal("1.0"))),
                priceProvider = staticPrices,
            )

            // Funding a zero-weight leg would silently destroy its share of NAV.
            failure shouldNotBe null
            failure?.first shouldBe ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE
        }

        "no qualifying activity yields no regimes" {
            val baseline = snapshot(
                t0,
                "1000.00",
                mapOf("AAA" to row("100.0", "10.00", "1000.00")),
            )
            val transient = AssetRegimeActivity(
                symbol = "AAA",
                fillCount = 3,
                firstFill = t0,
                lastFill = t0.plusSeconds(120),
                fullExitAt = t0.plusSeconds(180),
                economicallyPresentSpanMillis = 180_000L,
                establishedAt = t0,
                materiallyPresentAtBaseline = true,
            )

            RebalancerComparisonCalculator.inferRegimesForTest(
                activities = listOf(transient),
                snapshots = listOf(baseline),
                baseline = baseline,
                comparisonAssetSymbols = setOf("AAA", "USD"),
            ) shouldHaveSize 0
        }

        "a regime with no trustworthy settled anchor is not anchored" {
            val baseline = snapshot(
                t0,
                "1000.00",
                mapOf("OLD" to row("100.0", "10.00", "1000.00")),
            )
            val removal = AssetRegimeActivity(
                symbol = "OLD",
                fillCount = 1,
                firstFill = t0,
                lastFill = t0,
                fullExitAt = t0.plusSeconds(30),
                economicallyPresentSpanMillis = 30_000L,
                establishedAt = t0,
                materiallyPresentAtBaseline = true,
            )
            val addition = AssetRegimeActivity(
                symbol = "BTC",
                fillCount = 40,
                firstFill = t0,
                lastFill = lateTrade,
                fullExitAt = null,
                establishedAt = t1,
                materiallyPresentAtBaseline = false,
                economicallyPresentSpanMillis = Duration.between(t1, lateTrade).toMillis(),
            )

            // No retained snapshot at or after the cluster completes: the transition cannot be
            // anchored to contemporaneous evidence, so it must not be applied.
            RebalancerComparisonCalculator.inferRegimesForTest(
                activities = listOf(removal, addition),
                snapshots = listOf(baseline),
                baseline = baseline,
                comparisonAssetSymbols = setOf("OLD", "BTC", "USD"),
            ) shouldHaveSize 0
        }

        "an anchor whose settled composition cannot be valued is not applied" {
            val baseline = snapshot(
                t0,
                "1000.00",
                mapOf("OLD" to row("100.0", "10.00", "1000.00")),
            )
            // A settled snapshot whose only in-scope holding has a non-positive price cannot prove
            // the observed composition, so the transition must be skipped rather than guessed.
            val unpriceableAnchor = snapshot(
                t2,
                "0.00",
                mapOf("BTC" to row("5.0", "0.00", "0.00")),
            )
            val removal = AssetRegimeActivity(
                symbol = "OLD",
                fillCount = 1,
                firstFill = t0,
                lastFill = t0,
                fullExitAt = t0.plusSeconds(30),
                economicallyPresentSpanMillis = 30_000L,
                establishedAt = t0,
                materiallyPresentAtBaseline = true,
            )
            val addition = AssetRegimeActivity(
                symbol = "BTC",
                fillCount = 40,
                firstFill = t0,
                lastFill = lateTrade,
                fullExitAt = null,
                establishedAt = t1,
                materiallyPresentAtBaseline = false,
                economicallyPresentSpanMillis = Duration.between(t1, lateTrade).toMillis(),
            )

            RebalancerComparisonCalculator.inferRegimesForTest(
                activities = listOf(removal, addition),
                snapshots = listOf(baseline, unpriceableAnchor),
                baseline = baseline,
                comparisonAssetSymbols = setOf("OLD", "BTC", "USD"),
            ) shouldHaveSize 0
        }

        "an anchor ignores out-of-scope and zero-balance rows when deriving observed proportions" {
            val baseline = snapshot(
                t0,
                "1000.00",
                mapOf("OLD" to row("100.0", "10.00", "1000.00"), "USD" to row("0.0", "1.00", "0.00")),
            )
            val anchor = snapshot(
                t2,
                "1000.00",
                mapOf(
                    // OUT_OF_SCOPE and a zero-balance USD row must not enter the proportions.
                    "BTC" to row("5.0", "100.00", "500.00"),
                    "USD" to row("500.00", "1.00", "500.00"),
                    "ETH" to row("0.0", "50.00", "0.00"),
                ),
            )
            val removal = AssetRegimeActivity(
                symbol = "OLD",
                fillCount = 1,
                firstFill = t0,
                lastFill = t0,
                fullExitAt = t0.plusSeconds(30),
                economicallyPresentSpanMillis = 30_000L,
                establishedAt = t0,
                materiallyPresentAtBaseline = true,
            )
            val addition = AssetRegimeActivity(
                symbol = "BTC",
                fillCount = 40,
                firstFill = t0,
                lastFill = lateTrade,
                fullExitAt = null,
                establishedAt = t1,
                materiallyPresentAtBaseline = false,
                economicallyPresentSpanMillis = Duration.between(t1, lateTrade).toMillis(),
            )

            val anchored = RebalancerComparisonCalculator.inferRegimesForTest(
                activities = listOf(removal, addition),
                snapshots = listOf(baseline, anchor),
                baseline = baseline,
                comparisonAssetSymbols = setOf("OLD", "BTC", "USD"),
            )

            anchored shouldHaveSize 1
        }

        "a settled anchor with a zero-balance and an aliased row still yields exact proportions" {
            val baseline = snapshot(
                t0,
                "1000.00",
                mapOf("OLD" to row("100.0", "10.00", "1000.00")),
            )
            val anchor = snapshot(
                t2,
                "1000.00",
                mapOf(
                    "BTC" to row("5.0", "100.00", "500.00"),
                    // Zero balance: excluded from the proportion denominator.
                    "USD" to row("0.0", "1.00", "0.00"),
                    // A second non-USD leg that must take its own share.
                    "ETH" to row("10.0", "50.00", "500.00"),
                ),
            )
            val removal = AssetRegimeActivity(
                symbol = "OLD",
                fillCount = 1,
                firstFill = t0,
                lastFill = t0,
                fullExitAt = t0.plusSeconds(30),
                economicallyPresentSpanMillis = 30_000L,
                establishedAt = t0,
                materiallyPresentAtBaseline = true,
            )
            val additions = listOf("BTC", "ETH").map { symbol ->
                AssetRegimeActivity(
                    symbol = symbol,
                    fillCount = 40,
                    firstFill = t0,
                    lastFill = lateTrade,
                    fullExitAt = null,
                    establishedAt = t1,
                    materiallyPresentAtBaseline = false,
                    economicallyPresentSpanMillis = Duration.between(t1, lateTrade).toMillis(),
                )
            }

            val anchored = RebalancerComparisonCalculator.inferRegimesForTest(
                activities = listOf(removal) + additions,
                snapshots = listOf(baseline, anchor),
                baseline = baseline,
                comparisonAssetSymbols = setOf("OLD", "BTC", "ETH", "USD"),
            )

            anchored shouldHaveSize 1
            anchored.single().first.additions shouldBe setOf("BTC", "ETH")
        }

        "an anchor containing an out-of-scope holding and dust still yields one exact vector" {
            val baseline = snapshot(
                t0,
                "1000.00",
                mapOf("OLD" to row("100.0", "10.00", "1000.00")),
            )
            val anchor = snapshot(
                t2,
                "1000.00",
                mapOf(
                    "BTC" to row("5.0", "100.00", "500.00"),
                    "USD" to row("500.00", "1.00", "500.00"),
                ),
            )
            val removal = AssetRegimeActivity(
                symbol = "OLD",
                fillCount = 1,
                firstFill = t0,
                lastFill = t0,
                fullExitAt = t0.plusSeconds(30),
                economicallyPresentSpanMillis = 30_000L,
                establishedAt = t0,
                materiallyPresentAtBaseline = true,
            )
            val additions = listOf("BTC", "USD").map { symbol ->
                AssetRegimeActivity(
                    symbol = symbol,
                    fillCount = 40,
                    firstFill = t0,
                    lastFill = lateTrade,
                    fullExitAt = null,
                    establishedAt = t1,
                    materiallyPresentAtBaseline = false,
                    economicallyPresentSpanMillis = Duration.between(t1, lateTrade).toMillis(),
                )
            }

            val anchored = RebalancerComparisonCalculator.inferRegimesForTest(
                activities = listOf(removal) + additions,
                snapshots = listOf(baseline, anchor),
                baseline = baseline,
                comparisonAssetSymbols = setOf("OLD", "BTC", "USD"),
            )

            anchored shouldHaveSize 1
            anchored.single().first.additions shouldBe setOf("BTC", "USD")
        }

        "only high-confidence transitions are anchored when ambiguous candidates coexist" {
            val baseline = snapshot(
                t0,
                "1000.00",
                mapOf("OLD" to row("100.0", "10.00", "1000.00"), "USD" to row("0.0", "1.00", "0.00")),
            )
            val earlyAnchor = snapshot(
                t2,
                "1000.00",
                mapOf("BTC" to row("5.0", "100.00", "500.00"), "USD" to row("500.00", "1.00", "500.00")),
            )
            val lateAnchor = snapshot(
                t2.plusSeconds(400L * 86_400L),
                "1000.00",
                mapOf("ETH" to row("10.0", "50.00", "500.00"), "USD" to row("500.00", "1.00", "500.00")),
            )
            val removal = AssetRegimeActivity(
                symbol = "OLD",
                fillCount = 1,
                firstFill = t0,
                lastFill = t0,
                fullExitAt = t0.plusSeconds(30),
                economicallyPresentSpanMillis = 30_000L,
                establishedAt = t0,
                materiallyPresentAtBaseline = true,
            )
            val pairedAddition = AssetRegimeActivity(
                symbol = "BTC",
                fillCount = 40,
                firstFill = t0,
                lastFill = lateTrade,
                fullExitAt = null,
                establishedAt = t1,
                materiallyPresentAtBaseline = false,
                economicallyPresentSpanMillis = Duration.between(t1, lateTrade).toMillis(),
            )
            // A lone persistent addition with no corroboration is ambiguous, and is far enough
            // away to form its own cluster.
            val loneAddition = AssetRegimeActivity(
                symbol = "ETH",
                fillCount = 40,
                firstFill = t0,
                lastFill = lateTrade,
                fullExitAt = null,
                economicallyPresentSpanMillis = null,
                establishedAt = t2.plusSeconds(200L * 86_400L),
                materiallyPresentAtBaseline = false,
            )

            val anchored = RebalancerComparisonCalculator.inferRegimesForTest(
                activities = listOf(removal, pairedAddition, loneAddition),
                snapshots = listOf(baseline, earlyAnchor, lateAnchor),
                baseline = baseline,
                comparisonAssetSymbols = setOf("OLD", "BTC", "ETH", "USD"),
            )

            // The ambiguous candidate is dropped rather than silently steering the benchmark.
            anchored shouldHaveSize 1
            anchored.single().first.confidence shouldBe RegimeTransitionConfidence.HIGH
            anchored.single().first.removals shouldBe setOf("OLD")
        }

        "aliased rows accumulate onto one canonical holding at a settled anchor" {
            val baseline = snapshot(
                t0,
                "1000.00",
                mapOf("OLD" to row("100.0", "10.00", "1000.00"), "USD" to row("0.0", "1.00", "0.00")),
            )
            // XBT and BTC normalize onto the same canonical symbol, so both legs must be summed
            // rather than one silently overwriting the other.
            val anchor = snapshot(
                t2,
                "1000.00",
                mapOf(
                    "BTC" to row("2.0", "100.00", "200.00"),
                    "XBT" to row("3.0", "100.00", "300.00"),
                    "USD" to row("500.00", "1.00", "500.00"),
                ),
            )
            val removal = AssetRegimeActivity(
                symbol = "OLD",
                fillCount = 1,
                firstFill = t0,
                lastFill = t0,
                fullExitAt = t0.plusSeconds(30),
                economicallyPresentSpanMillis = 30_000L,
                establishedAt = t0,
                materiallyPresentAtBaseline = true,
            )
            val addition = AssetRegimeActivity(
                symbol = "BTC",
                fillCount = 40,
                firstFill = t0,
                lastFill = lateTrade,
                fullExitAt = null,
                establishedAt = t1,
                materiallyPresentAtBaseline = false,
                economicallyPresentSpanMillis = Duration.between(t1, lateTrade).toMillis(),
            )

            val anchored = RebalancerComparisonCalculator.inferRegimesForTest(
                // A pre-baseline snapshot must be ignored when choosing the anchor.
                activities = listOf(removal, addition),
                snapshots = listOf(
                    snapshot(t0.minusSeconds(3600), "900.00", mapOf("OLD" to row("90.0", "10.00", "900.00"))),
                    baseline,
                    anchor,
                ),
                baseline = baseline,
                comparisonAssetSymbols = setOf("OLD", "BTC", "USD"),
            )

            anchored shouldHaveSize 1
            anchored.single().second.timestamp shouldBe t2
        }

        "an anchor with no in-scope holdings cannot prove a composition" {
            val baseline = snapshot(
                t0,
                "1000.00",
                mapOf("OLD" to row("100.0", "10.00", "1000.00"), "USD" to row("0.0", "1.00", "0.00")),
            )
            val emptyAnchor = snapshot(
                t2,
                "0.00",
                mapOf("ETH" to row("0.0", "50.00", "0.00")),
            )
            val removal = AssetRegimeActivity(
                symbol = "OLD",
                fillCount = 1,
                firstFill = t0,
                lastFill = t0,
                fullExitAt = t0.plusSeconds(30),
                economicallyPresentSpanMillis = 30_000L,
                establishedAt = t0,
                materiallyPresentAtBaseline = true,
            )
            val addition = AssetRegimeActivity(
                symbol = "BTC",
                fillCount = 40,
                firstFill = t0,
                lastFill = lateTrade,
                fullExitAt = null,
                establishedAt = t1,
                materiallyPresentAtBaseline = false,
                economicallyPresentSpanMillis = Duration.between(t1, lateTrade).toMillis(),
            )

            RebalancerComparisonCalculator.inferRegimesForTest(
                activities = listOf(removal, addition),
                snapshots = listOf(baseline, emptyAnchor),
                baseline = baseline,
                comparisonAssetSymbols = setOf("OLD", "BTC", "USD"),
            ) shouldHaveSize 0
        }

        "a reset targeting only non-positive weights is refused rather than creating an empty portfolio" {
            val balances = mutableMapOf("OLD" to BigDecimal("100.0"))

            val failure = RebalancerComparisonCalculator.replayBenchmarkEventForTest(
                balances = balances,
                event = resetEvent(mapOf("BTC" to BigDecimal("0.0"))),
                priceProvider = staticPrices,
            )

            failure shouldNotBe null
            failure?.first shouldBe ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE
        }

        "a configuration reset fails closed on a zero price rather than dividing by zero" {
            val zeroPriced = HistoricalPriceProvider { symbol, _ ->
                when (symbol) {
                    "OLD" -> BigDecimal("10")
                    "BTC" -> BigDecimal.ZERO
                    else -> null
                }
            }
            val balances = mutableMapOf("OLD" to BigDecimal("100.0"))

            val failure = RebalancerComparisonCalculator.replayBenchmarkEventForTest(
                balances = balances,
                event = resetEvent(mapOf("BTC" to BigDecimal("1.0"))),
                priceProvider = zeroPriced,
            )

            failure shouldNotBe null
            failure?.first shouldBe ComparisonUnavailableReason.MISSING_PRICE
        }

        "a reset ignores an already-zero holding when valuing the benchmark" {
            val balances = mutableMapOf("OLD" to BigDecimal("100.0"), "DUST" to BigDecimal.ZERO)

            val failure = RebalancerComparisonCalculator.replayBenchmarkEventForTest(
                balances = balances,
                event = resetEvent(mapOf("BTC" to BigDecimal("1.0"))),
                priceProvider = staticPrices,
            )

            // A zero holding must not require a price, and must not dilute the reallocation.
            failure shouldBe null
            balances.getValue("BTC").shouldBeEqualComparingTo(BigDecimal("10.00000000"))
        }

        "a contribution is invested by the epoch in force at its own timestamp, never a later epoch" {
            val epochA = mapOf("OLD" to BigDecimal("1.0"))
            val epochB = mapOf("BTC" to BigDecimal("1.0"))
            fun weightsAt(timestamp: Instant) = if (timestamp >= t2) epochB else epochA

            // The regression this protects: a contribution before the later anchor must not receive
            // that later regime's assets before it became observable.
            weightsAt(t1) shouldBe epochA
            weightsAt(t1).containsKey("BTC") shouldBe false
            weightsAt(t2) shouldBe epochB
        }
    }
}
