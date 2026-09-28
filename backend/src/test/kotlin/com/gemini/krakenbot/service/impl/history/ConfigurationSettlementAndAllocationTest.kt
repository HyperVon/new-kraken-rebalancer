package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.TradeRecord
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

/**
 * How a transition becomes an *applicable* synthetic action: which observation anchors it, what
 * evidence splits its funding, and what configuration state later contributions are invested by.
 *
 * These pin the three ways routine Actual state could previously leak in, so each is now closed by
 * construction: the anchor must be settled, funding is derived only across the addition set, and the
 * configuration allocation is carried forward from the benchmark's own history.
 */
class ConfigurationSettlementAndAllocationTest : StringSpec() {

    private val t0: Instant = Instant.parse("2025-12-05T17:00:00Z")
    private val t1: Instant = t0.plusSeconds(3_600)
    private val t2: Instant = t0.plusSeconds(7_200)
    private val t3: Instant = t2.plusSeconds(3_600)
    private val t4: Instant = t3.plusSeconds(3_600)
    private val late: Instant = t0.plusSeconds(90L * 86_400L)

    private fun row(balance: String, price: String, value: String) = Triple(balance, price, value)

    private fun snapshot(timestamp: Instant, assets: Map<String, Triple<String, String, String>>) = PortfolioSnapshot(
        timestamp = timestamp,
        totalValueUSD = assets.values.fold(BigDecimal.ZERO) { acc, r -> acc.add(BigDecimal(r.third)) },
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

    private fun buy(symbol: String, at: Instant, usd: String) = TradeRecord(
        timestamp = at,
        pair = "$symbol/USD",
        side = "BUY",
        symbol = symbol,
        volume = BigDecimal("1"),
        usdAmount = BigDecimal(usd),
        success = true,
        dryRun = false,
        price = BigDecimal("1"),
        fee = BigDecimal.ZERO,
    )

    private fun removal(symbol: String, at: Instant = t1) = AssetRegimeActivity(
        symbol = symbol,
        fillCount = 1,
        firstFill = t0,
        lastFill = t0,
        fullExitAt = at,
        economicallyPresentSpanMillis = 3_600_000L,
        establishedAt = t0,
        materiallyPresentAtBaseline = true,
    )

    private fun addition(symbol: String, at: Instant = t1) = AssetRegimeActivity(
        symbol = symbol,
        fillCount = 40,
        firstFill = t0,
        lastFill = late,
        fullExitAt = null,
        establishedAt = at,
        materiallyPresentAtBaseline = false,
        economicallyPresentSpanMillis = Duration.between(at, late).toMillis(),
    )

    private val held = AssetRegimeActivity(
        symbol = "TRX",
        fillCount = 40,
        firstFill = t0,
        lastFill = late,
        fullExitAt = null,
        establishedAt = t0,
        materiallyPresentAtBaseline = true,
        economicallyPresentSpanMillis = Duration.between(t0, late).toMillis(),
    )

    private val assetScope = setOf("HBAR", "XMR", "TRX", "AVAX", "LINK", "USD")

    /** Weight vectors are normalized to 16 decimal places, so equality is asserted at a stated scale. */
    private fun rounded(weight: BigDecimal) = weight.setScale(6, java.math.RoundingMode.HALF_UP)

    private fun sumOfWeights(weights: Map<String, BigDecimal>): BigDecimal =
        weights.values.fold(BigDecimal.ZERO) { acc, weight -> acc.add(weight) }

    private val inceptionWeights = mapOf(
        "HBAR" to BigDecimal("0.333333333333333333"),
        "XMR" to BigDecimal("0.333333333333333333"),
        "TRX" to BigDecimal("0.333333333333333333"),
    )

    private val baseline = snapshot(
        t0,
        mapOf(
            "HBAR" to row("100.0", "10.00", "1000.00"),
            "XMR" to row("100.0", "10.00", "1000.00"),
            "TRX" to row("1000.0", "1.00", "1000.00"),
        ),
    )

    /** The removals have landed and the additions are established. */
    private fun settled(avax: String, link: String, usd: String, timestamp: Instant) = snapshot(
        timestamp,
        mapOf(
            "HBAR" to row("0.0", "10.00", "0.00"),
            "XMR" to row("0.0", "10.00", "0.00"),
            "TRX" to row("0.0", "1.00", "0.00"),
            "AVAX" to row(avax, "10.00", (BigDecimal(avax) * BigDecimal("10")).toPlainString()),
            "LINK" to row(link, "10.00", (BigDecimal(link) * BigDecimal("10")).toPlainString()),
            "USD" to row(usd, "1.00", usd),
        ),
    )

    init {
        "the anchor waits for the membership change to land instead of taking the first snapshot" {
            // t2 is mid-cycle: the removal leg has not executed and the addition is not established.
            val midCycle = snapshot(
                t2,
                mapOf(
                    "HBAR" to row("100.0", "10.00", "1000.00"),
                    "XMR" to row("100.0", "10.00", "1000.00"),
                    "TRX" to row("1000.0", "1.00", "1000.00"),
                ),
            )

            val regimes = RebalancerComparisonCalculator.anchoredRegimesForTest(
                activities = listOf(removal("HBAR"), removal("XMR"), addition("AVAX"), held),
                snapshots = listOf(
                    baseline,
                    midCycle,
                    settled("300.0", "0.0", "0.00", t3),
                    settled("300.0", "0.0", "0.00", t4),
                ),
                baseline = baseline,
                comparisonAssetSymbols = assetScope,
                inceptionWeights = inceptionWeights,
            )

            regimes shouldHaveSize 1
            // t2 is the earliest snapshot at or after the cluster but its named assets are still
            // moving, so it cannot be the anchor.
            regimes.single().anchor.timestamp shouldBe t3
        }

        "an unpriceable or absent addition at the anchor leaves the transition unapplied" {
            val regimes = RebalancerComparisonCalculator.anchoredRegimesForTest(
                activities = listOf(removal("HBAR"), addition("AVAX"), held),
                // AVAX is never established, so no evidence can prove how to fund it.
                snapshots = listOf(
                    baseline,
                    settled("0.0", "0.0", "0.00", t2),
                    settled("0.0", "0.0", "0.00", t3),
                ),
                baseline = baseline,
                comparisonAssetSymbols = assetScope,
                inceptionWeights = inceptionWeights,
            )

            regimes shouldHaveSize 0
        }

        "intra-cluster fills outrank anchor values when splitting funding across additions" {
            // The transition itself bought 1500.00 of AVAX and 500.00 of LINK.
            val regimes = RebalancerComparisonCalculator.anchoredRegimesForTest(
                activities = listOf(removal("HBAR"), removal("XMR"), addition("AVAX"), addition("LINK"), held),
                snapshots = listOf(
                    baseline,
                    // Anchored values would imply 9000/1000 = 0.90/0.10.
                    settled("900.0", "100.0", "0.00", t2),
                    settled("900.0", "100.0", "0.00", t3),
                ),
                baseline = baseline,
                comparisonAssetSymbols = assetScope,
                inceptionWeights = inceptionWeights,
                trades = listOf(
                    buy("AVAX", t1, "1500.00"),
                    buy("LINK", t1, "500.00"),
                ),
            )

            regimes shouldHaveSize 1
            val shares = regimes.single().additionFundingShares
            shares.getValue("AVAX").shouldBeEqualComparingTo(BigDecimal("0.75"))
            shares.getValue("LINK").shouldBeEqualComparingTo(BigDecimal("0.25"))
        }

        "anchor values across the addition set are the fallback when the cluster bought nothing" {
            val regimes = RebalancerComparisonCalculator.anchoredRegimesForTest(
                activities = listOf(removal("HBAR"), removal("XMR"), addition("AVAX"), addition("LINK"), held),
                snapshots = listOf(
                    baseline,
                    settled("900.0", "100.0", "0.00", t2),
                    settled("900.0", "100.0", "0.00", t3),
                ),
                baseline = baseline,
                comparisonAssetSymbols = assetScope,
                inceptionWeights = inceptionWeights,
            )

            regimes shouldHaveSize 1
            val shares = regimes.single().additionFundingShares
            shares.getValue("AVAX").shouldBeEqualComparingTo(BigDecimal("0.9"))
            shares.getValue("LINK").shouldBeEqualComparingTo(BigDecimal("0.1"))
            // Only the addition set is ever consulted, never a whole-portfolio proportion.
            shares.keys shouldBe setOf("AVAX", "LINK")
        }

        "a partially funded cluster does not silently split on mixed evidence" {
            val regimes = RebalancerComparisonCalculator.anchoredRegimesForTest(
                activities = listOf(removal("HBAR"), removal("XMR"), addition("AVAX"), addition("LINK"), held),
                snapshots = listOf(
                    baseline,
                    settled("900.0", "100.0", "0.00", t2),
                    settled("900.0", "100.0", "0.00", t3),
                ),
                baseline = baseline,
                comparisonAssetSymbols = assetScope,
                inceptionWeights = inceptionWeights,
                // The cluster only bought AVAX, so it proves nothing about how to fund LINK.
                trades = listOf(buy("AVAX", t1, "1500.00")),
            )

            regimes shouldHaveSize 1
            val shares = regimes.single().additionFundingShares
            // The incomplete cluster is discarded as a whole, so the anchor set is used instead of a
            // mixture of both sources.
            shares.getValue("AVAX").shouldBeEqualComparingTo(BigDecimal("0.9"))
            shares.getValue("LINK").shouldBeEqualComparingTo(BigDecimal("0.1"))
        }

        "contributions follow the configuration in force, not Actual drift or its cash buffer" {
            val regimes = RebalancerComparisonCalculator.anchoredRegimesForTest(
                activities = listOf(removal("HBAR"), removal("XMR"), addition("AVAX"), held),
                snapshots = listOf(
                    baseline,
                    settled("300.0", "0.0", "250.00", t2),
                    settled("300.0", "0.0", "250.00", t3),
                ),
                baseline = baseline,
                comparisonAssetSymbols = assetScope,
                inceptionWeights = inceptionWeights,
            )

            regimes shouldHaveSize 1
            val allocation = regimes.single().configurationAllocation
            // The named removal left; TRX keeps its relative weight, untouched by drift, and the
            // released weight becomes AVAX. Actual's 250.00 cash buffer is not adopted.
            allocation.keys shouldBe setOf("TRX", "AVAX")
            rounded(allocation.getValue("TRX")).shouldBeEqualComparingTo(BigDecimal("0.333333"))
            rounded(allocation.getValue("AVAX")).shouldBeEqualComparingTo(BigDecimal("0.666667"))
            rounded(sumOfWeights(allocation)).shouldBeEqualComparingTo(BigDecimal("1.000000"))
        }

        "successive transitions compose without re-weighting the survivors" {
            // Far enough apart that the clustering gap separates them into distinct transitions.
            val t2a: Instant = t0.plusSeconds(10L * 86_400L)
            val secondRemoval = removal("TRX", t2a)
            val secondAddition = addition("LINK", t2a)
            val regimes = RebalancerComparisonCalculator.anchoredRegimesForTest(
                activities = listOf(
                    removal("HBAR"),
                    removal("XMR"),
                    addition("AVAX"),
                    held,
                    secondRemoval,
                    secondAddition,
                ),
                snapshots = listOf(
                    baseline,
                    settled("300.0", "0.0", "0.00", t2),
                    settled("300.0", "0.0", "0.00", t3),
                    settled("300.0", "100.0", "0.00", t2a),
                    settled("300.0", "100.0", "0.00", t2a.plusSeconds(3_600)),
                ),
                baseline = baseline,
                comparisonAssetSymbols = assetScope,
                inceptionWeights = inceptionWeights,
            )

            regimes shouldHaveSize 2
            regimes.first().configurationAllocation.keys shouldBe setOf("TRX", "AVAX")
            val second = regimes.last().configurationAllocation
            // The second transition removes TRX and funds LINK out of it; AVAX is carried at the
            // weight the first transition gave it, not re-derived from the anchor.
            second.keys shouldBe setOf("AVAX", "LINK")
            rounded(second.getValue("AVAX")).shouldBeEqualComparingTo(BigDecimal("0.666667"))
            rounded(second.getValue("LINK")).shouldBeEqualComparingTo(BigDecimal("0.333333"))
        }

        "transitions settling on one snapshot both apply, in cluster order" {
            val activities = listOf(
                removal("HBAR"),
                removal("XMR"),
                addition("AVAX"),
                held,
            )
            val snapshots = listOf(
                baseline,
                settled("300.0", "0.0", "0.00", t2),
                settled("300.0", "0.0", "0.00", t3),
            )

            val forward = RebalancerComparisonCalculator.anchoredRegimesForTest(
                activities = activities,
                snapshots = snapshots,
                baseline = baseline,
                comparisonAssetSymbols = assetScope,
                inceptionWeights = inceptionWeights,
            )
            val reversed = RebalancerComparisonCalculator.anchoredRegimesForTest(
                activities = activities.reversed(),
                snapshots = snapshots,
                baseline = baseline,
                comparisonAssetSymbols = assetScope,
                inceptionWeights = inceptionWeights,
            )

            // Two distinct membership changes are never collapsed into one reallocation, and the
            // composition does not depend on the order the transitions were supplied in.
            forward shouldHaveSize 1
            reversed shouldHaveSize 1
            forward.single().transition shouldBe reversed.single().transition
            forward.single().anchor.timestamp shouldBe reversed.single().anchor.timestamp
            forward.single().additionFundingShares shouldBe reversed.single().additionFundingShares
            forward.single().configurationAllocation shouldBe reversed.single().configurationAllocation
            forward.single().configurationAllocation.keys shouldBe setOf("TRX", "AVAX")
        }

        "a transition with no retained observation after its cluster is not anchored" {
            val regimes = RebalancerComparisonCalculator.anchoredRegimesForTest(
                activities = listOf(removal("HBAR"), addition("AVAX"), held),
                snapshots = listOf(baseline),
                baseline = baseline,
                comparisonAssetSymbols = assetScope,
                inceptionWeights = inceptionWeights,
            )

            regimes shouldHaveSize 0
        }
    }
}
