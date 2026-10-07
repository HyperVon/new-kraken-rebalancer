package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.model.ComparisonUnavailableReason
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.TradeRecord
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

/**
 * Edge conditions of the configuration-reset evidence chain: what counts as usable funding evidence,
 * what makes a transition unapplicable, and how unusable prices are refused.
 *
 * Each case here exists because it can occur in a real retained account, so each kills a distinct
 * defect class rather than restating a case already covered.
 */
class ConfigurationResetEvidenceTest : StringSpec() {

    private val t0: Instant = Instant.parse("2025-12-05T17:00:00Z")
    private val t1: Instant = t0.plusSeconds(3_600)
    private val t2: Instant = t0.plusSeconds(7_200)
    private val t3: Instant = t2.plusSeconds(3_600)
    private val t4: Instant = t3.plusSeconds(3_600)
    private val late: Instant = t0.plusSeconds(90L * 86_400L)

    private val unitPrices = mapOf(
        "HBAR" to BigDecimal("10"),
        "XMR" to BigDecimal("10"),
        "AVAX" to BigDecimal("10"),
        "LINK" to BigDecimal("10"),
        "TRX" to BigDecimal("1"),
    )

    private val prices = HistoricalPriceProvider { symbol, _ -> unitPrices[symbol] }

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

    private fun fill(
        symbol: String,
        at: Instant,
        side: String,
        usd: String,
        success: Boolean = true,
        dryRun: Boolean = false,
    ) = TradeRecord(
        timestamp = at,
        pair = "$symbol/USD",
        side = side,
        symbol = symbol,
        volume = BigDecimal("1"),
        usdAmount = BigDecimal(usd),
        success = success,
        dryRun = dryRun,
        price = BigDecimal("1"),
        fee = BigDecimal.ZERO,
    )

    private fun removal(symbol: String) = AssetRegimeActivity(
        symbol = symbol,
        fillCount = 1,
        firstFill = t0,
        lastFill = t0,
        fullExitAt = t1,
        economicallyPresentSpanMillis = 3_600_000L,
        establishedAt = t0,
        materiallyPresentAtBaseline = true,
    )

    private fun addition(symbol: String) = AssetRegimeActivity(
        symbol = symbol,
        fillCount = 40,
        firstFill = t0,
        lastFill = late,
        fullExitAt = null,
        establishedAt = t1,
        materiallyPresentAtBaseline = false,
        economicallyPresentSpanMillis = Duration.between(t1, late).toMillis(),
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

    private val inceptionWeights = mapOf(
        "HBAR" to BigDecimal("0.5"),
        "TRX" to BigDecimal("0.5"),
    )

    private val baseline = snapshot(
        t0,
        mapOf(
            "HBAR" to row("100.0", "10.00", "1000.00"),
            "TRX" to row("1000.0", "1.00", "1000.00"),
        ),
    )

    /** Settled anchor: HBAR drained, the listed assets established. */
    private fun settled(avax: String, link: String, timestamp: Instant) = snapshot(
        timestamp,
        mapOf(
            "HBAR" to row("0.0", "10.00", "0.00"),
            "TRX" to row("0.0", "1.00", "0.00"),
            "AVAX" to row(avax, "10.00", (BigDecimal(avax) * BigDecimal("10")).toPlainString()),
            "LINK" to row(link, "10.00", (BigDecimal(link) * BigDecimal("10")).toPlainString()),
        ),
    )

    private fun reset(
        removals: Set<String>,
        additions: Set<String> = emptySet(),
        shares: Map<String, BigDecimal> = emptyMap(),
    ) = BenchmarkEvent.ConfigurationReset(
        timestamp = t1,
        transition = InferredRegimeTransition(
            clusterStart = t1,
            clusterEnd = t1,
            removals = removals,
            additions = additions,
            confidence = RegimeTransitionConfidence.HIGH,
        ),
        additionFundingShares = shares,
        configurationAllocation = emptyMap(),
    )

    init {
        "a zero price from the provider is refused rather than dividing by zero" {
            val zeroPriced = HistoricalPriceProvider { symbol, _ ->
                if (symbol == "HBAR") BigDecimal("10") else BigDecimal.ZERO
            }
            val balances = mutableMapOf("HBAR" to BigDecimal("100.0"))

            val outcome = RebalancerComparisonCalculator.replayConfigurationResetForTest(
                balances = balances,
                event = reset(
                    removals = setOf("HBAR"),
                    additions = setOf("AVAX"),
                    shares = mapOf("AVAX" to BigDecimal("1.0")),
                ),
                priceProvider = zeroPriced,
            )

            outcome.shouldBeInstanceOf<RebalancerComparisonCalculator.ConfigurationResetOutcome.Failed>()
            outcome.reason shouldBe ComparisonUnavailableReason.MISSING_PRICE
            // Nothing may be mutated before the failure is proven.
            balances.containsKey("AVAX") shouldBe false
        }

        "a symbol named by both sides of a transition is treated as a removal only" {
            val balances = mutableMapOf("HBAR" to BigDecimal("100.0"))

            val turnover = (
                RebalancerComparisonCalculator.replayConfigurationResetForTest(
                    balances = balances,
                    event = reset(
                        removals = setOf("HBAR"),
                        additions = setOf("HBAR"),
                        shares = mapOf("HBAR" to BigDecimal("1.0")),
                    ),
                    priceProvider = prices,
                ) as RebalancerComparisonCalculator.ConfigurationResetOutcome.Applied
                ).turnover

            // A removal and an addition of the same asset in one transition is contradictory
            // evidence, so the removal wins and the value rests as cash rather than being rebought.
            balances.containsKey("HBAR") shouldBe false
            balances.getValue("USD").shouldBeEqualComparingTo(BigDecimal("1000.0"))
            turnover.cryptoBuyNotional.shouldBeEqualComparingTo(BigDecimal("0.0"))
        }

        "cluster funding ignores failed, dry-run, and out-of-window fills" {
            // Only the SELL inside the window counts, so the net is negative and the cluster is
            // rejected as evidence in favour of the anchor set.
            val regimes = RebalancerComparisonCalculator.anchoredRegimesForTest(
                activities = listOf(removal("HBAR"), addition("AVAX"), held),
                snapshots = listOf(
                    baseline,
                    settled("300.0", "0.0", t2),
                    settled("300.0", "0.0", t3),
                ),
                baseline = baseline,
                comparisonAssetSymbols = assetScope,
                inceptionWeights = inceptionWeights,
                trades = listOf(
                    fill("AVAX", t1, "BUY", "500.00", success = false),
                    fill("AVAX", t1, "BUY", "500.00", dryRun = true),
                    fill("AVAX", late, "BUY", "500.00"),
                    fill("AVAX", t1, "SELL", "400.00"),
                ),
            )

            regimes shouldHaveSize 1
            // The anchor set is a single addition, so it necessarily takes the whole funding.
            regimes.single().additionFundingShares.getValue("AVAX")
                .shouldBeEqualComparingTo(BigDecimal("1.0"))
        }

        "repeated in-window fills for one addition accumulate into its net acquisition" {
            val regimes = RebalancerComparisonCalculator.anchoredRegimesForTest(
                activities = listOf(removal("HBAR"), addition("AVAX"), addition("LINK"), held),
                snapshots = listOf(
                    baseline,
                    // Anchored values would imply 750/250.
                    settled("900.0", "100.0", t2),
                    settled("900.0", "100.0", t3),
                ),
                baseline = baseline,
                comparisonAssetSymbols = assetScope,
                inceptionWeights = inceptionWeights,
                trades = listOf(
                    fill("AVAX", t1, "BUY", "500.00"),
                    fill("AVAX", t1, "BUY", "1000.00"),
                    fill("LINK", t1, "BUY", "500.00"),
                ),
            )

            regimes shouldHaveSize 1
            val shares = regimes.single().additionFundingShares
            shares.getValue("AVAX").shouldBeEqualComparingTo(BigDecimal("0.75"))
            shares.getValue("LINK").shouldBeEqualComparingTo(BigDecimal("0.25"))
        }

        "a removal-only transition needs no funding evidence and rests its weight in cash" {
            val regimes = RebalancerComparisonCalculator.anchoredRegimesForTest(
                activities = listOf(removal("HBAR"), held),
                snapshots = listOf(
                    baseline,
                    settled("0.0", "0.0", t2),
                    settled("0.0", "0.0", t3),
                ),
                baseline = baseline,
                comparisonAssetSymbols = assetScope,
                inceptionWeights = inceptionWeights,
            )

            regimes shouldHaveSize 1
            regimes.single().additionFundingShares shouldBe emptyMap()
            // Half the inception weight was HBAR, and with no addition to fund it that weight
            // rests in the synthetic configuration's cash, exactly as the reset treats released
            // value. TRX keeps its own relative weight.
            val allocation = regimes.single().configurationAllocation
            allocation.keys shouldBe setOf("TRX", "USD")
            allocation.getValue("TRX").setScale(6, java.math.RoundingMode.HALF_UP)
                .shouldBeEqualComparingTo(BigDecimal("0.500000"))
            allocation.getValue("USD").setScale(6, java.math.RoundingMode.HALF_UP)
                .shouldBeEqualComparingTo(BigDecimal("0.500000"))
        }

        "an addition priced to zero at the anchor cannot prove its funding share" {
            // The anchor lists AVAX with a zero price, so no value can be attributed to it.
            val brokenAnchor = snapshot(
                t2,
                mapOf(
                    "HBAR" to row("0.0", "10.00", "0.00"),
                    "TRX" to row("0.0", "1.00", "0.00"),
                    "AVAX" to row("300.0", "0.00", "0.00"),
                ),
            )
            val regimes = RebalancerComparisonCalculator.anchoredRegimesForTest(
                activities = listOf(removal("HBAR"), addition("AVAX"), held),
                snapshots = listOf(baseline, brokenAnchor, brokenAnchor),
                baseline = baseline,
                comparisonAssetSymbols = assetScope,
                inceptionWeights = inceptionWeights,
            )

            regimes shouldHaveSize 0
        }

        "a pre-baseline observation is not an anchor candidate" {
            val preBaseline = snapshot(
                t0.minusSeconds(3_600),
                mapOf("HBAR" to row("50.0", "10.00", "500.00")),
            )
            val regimes = RebalancerComparisonCalculator.anchoredRegimesForTest(
                activities = listOf(removal("HBAR"), addition("AVAX"), held),
                snapshots = listOf(
                    preBaseline,
                    baseline,
                    settled("300.0", "0.0", t2),
                    settled("300.0", "0.0", t3),
                ),
                baseline = baseline,
                comparisonAssetSymbols = assetScope,
                inceptionWeights = inceptionWeights,
            )

            regimes shouldHaveSize 1
            regimes.single().anchor.timestamp shouldBe t2
        }

        "a fill with a negative recorded notional is not usable funding evidence" {
            val regimes = RebalancerComparisonCalculator.anchoredRegimesForTest(
                activities = listOf(removal("HBAR"), addition("AVAX"), held),
                snapshots = listOf(
                    baseline,
                    settled("300.0", "0.0", t2),
                    settled("300.0", "0.0", t3),
                ),
                baseline = baseline,
                comparisonAssetSymbols = assetScope,
                inceptionWeights = inceptionWeights,
                // A malformed row must not be read as a negative acquisition, which would otherwise
                // satisfy the funding check with a value the account never paid.
                trades = listOf(fill("AVAX", t1, "BUY", "-500.00")),
            )

            regimes shouldHaveSize 1
            regimes.single().additionFundingShares.getValue("AVAX")
                .shouldBeEqualComparingTo(BigDecimal("1.0"))
        }

        "aliased anchor rows for one addition accumulate into a single funding share" {
            val aliasedAnchor = snapshot(
                t2,
                mapOf(
                    "HBAR" to row("0.0", "10.00", "0.00"),
                    "TRX" to row("0.0", "1.00", "0.00"),
                    // XBT and BTC normalize onto one canonical holding, so both legs must count.
                    "BTC" to row("60.0", "10.00", "600.00"),
                    "XBT" to row("40.0", "10.00", "400.00"),
                ),
            )
            val regimes = RebalancerComparisonCalculator.anchoredRegimesForTest(
                activities = listOf(removal("HBAR"), addition("BTC"), held),
                snapshots = listOf(baseline, aliasedAnchor, aliasedAnchor),
                baseline = baseline,
                comparisonAssetSymbols = assetScope + "BTC",
                inceptionWeights = inceptionWeights,
            )

            regimes shouldHaveSize 1
            regimes.single().additionFundingShares.getValue("BTC")
                .shouldBeEqualComparingTo(BigDecimal("1.0"))
        }

        "an addition that is cash is funded from the settled anchor's own cash value" {
            val cashAnchor = snapshot(
                t2,
                mapOf(
                    "HBAR" to row("0.0", "10.00", "0.00"),
                    "TRX" to row("0.0", "1.00", "0.00"),
                    "USD" to row("4000.0", "1.00", "4000.0"),
                ),
            )
            val regimes = RebalancerComparisonCalculator.anchoredRegimesForTest(
                activities = listOf(removal("HBAR"), addition("USD"), held),
                snapshots = listOf(baseline, cashAnchor, cashAnchor),
                baseline = baseline,
                comparisonAssetSymbols = assetScope,
                inceptionWeights = inceptionWeights,
            )

            regimes shouldHaveSize 1
            regimes.single().additionFundingShares.getValue("USD")
                .shouldBeEqualComparingTo(BigDecimal("1.0"))
        }

        "an addition outside the comparison scope cannot be funded and is not applied" {
            val regimes = RebalancerComparisonCalculator.anchoredRegimesForTest(
                activities = listOf(removal("HBAR"), addition("OUTSIDE"), held),
                snapshots = listOf(
                    baseline,
                    settled("300.0", "0.0", t2),
                    settled("300.0", "0.0", t3),
                ),
                baseline = baseline,
                comparisonAssetSymbols = assetScope,
                inceptionWeights = inceptionWeights,
            )

            regimes shouldHaveSize 0
        }

        "a removal absent from the configuration releases nothing and the vector is renormalized" {
            val regimes = RebalancerComparisonCalculator.anchoredRegimesForTest(
                // XMR is named as a removal but carries no weight in the inception configuration.
                activities = listOf(removal("HBAR"), removal("XMR"), held),
                snapshots = listOf(
                    baseline,
                    settled("0.0", "0.0", t2),
                    settled("0.0", "0.0", t3),
                ),
                baseline = baseline,
                comparisonAssetSymbols = assetScope,
                inceptionWeights = inceptionWeights,
            )

            regimes shouldHaveSize 1
            val allocation = regimes.single().configurationAllocation
            allocation.keys shouldBe setOf("TRX", "USD")
            allocation.getValue("TRX").setScale(6, java.math.RoundingMode.HALF_UP)
                .shouldBeEqualComparingTo(BigDecimal("0.500000"))
        }

        "a configuration that loses its whole membership rests entirely in cash" {
            val regimes = RebalancerComparisonCalculator.anchoredRegimesForTest(
                activities = listOf(removal("HBAR"), held),
                snapshots = listOf(
                    snapshot(t0, mapOf("HBAR" to row("100.0", "10.00", "1000.00"))),
                    settled("0.0", "0.0", t2),
                    settled("0.0", "0.0", t3),
                ),
                baseline = snapshot(t0, mapOf("HBAR" to row("100.0", "10.00", "1000.00"))),
                comparisonAssetSymbols = assetScope,
                inceptionWeights = mapOf("HBAR" to BigDecimal("1.0")),
            )

            regimes shouldHaveSize 1
            // No addition claimed the released weight, so the whole configuration is benchmark cash.
            regimes.single().configurationAllocation.keys shouldBe setOf("USD")
            regimes.single().configurationAllocation.getValue("USD")
                .shouldBeEqualComparingTo(BigDecimal("1.0"))
        }

        "an addition the configuration already holds is topped up rather than replaced" {
            // TRX is both a surviving configuration member and the named addition here, and the
            // anchor must show it established before the transition can be anchored at all.
            val established = snapshot(
                t2,
                mapOf(
                    "HBAR" to row("0.0", "10.00", "0.00"),
                    "TRX" to row("4000.0", "1.00", "4000.0"),
                ),
            )
            val regimes = RebalancerComparisonCalculator.anchoredRegimesForTest(
                activities = listOf(removal("HBAR"), addition("TRX"), held),
                snapshots = listOf(baseline, established, established),
                baseline = baseline,
                comparisonAssetSymbols = assetScope,
                inceptionWeights = inceptionWeights,
                trades = listOf(fill("TRX", t1, "BUY", "1000.00")),
            )

            regimes shouldHaveSize 1
            // HBAR's 0.5 weight is released to TRX, which already held 0.5.
            regimes.single().configurationAllocation.getValue("TRX")
                .setScale(6, java.math.RoundingMode.HALF_UP)
                .shouldBeEqualComparingTo(BigDecimal("1.000000"))
        }

        "an addition that has not landed yet is not a settled anchor" {
            // The addition is named but the anchor still shows the pre-transition composition, so
            // there is no evidence the transition landed and it must not be applied at all.
            val regimes = RebalancerComparisonCalculator.anchoredRegimesForTest(
                activities = listOf(removal("HBAR"), addition("AVAX"), held),
                snapshots = listOf(
                    baseline,
                    settled("0.0", "0.0", t2),
                    settled("0.0", "0.0", t3),
                    settled("0.0", "0.0", t4),
                ),
                baseline = baseline,
                comparisonAssetSymbols = assetScope,
                inceptionWeights = inceptionWeights,
            )

            regimes shouldHaveSize 0
        }

        "an unfunded reset fails explicitly without mutating the benchmark book" {
            val balances = mutableMapOf("HBAR" to BigDecimal("100.0"))

            val failure = RebalancerComparisonCalculator.replayBenchmarkEventForTest(
                balances = balances,
                event = reset(
                    removals = setOf("HBAR"),
                    additions = setOf("AVAX"),
                    shares = emptyMap(),
                ),
                priceProvider = prices,
            )

            failure shouldBe (ComparisonUnavailableReason.CONFIGURATION_FUNDING_EVIDENCE_MISSING to t1)
            balances.getValue("HBAR").shouldBeEqualComparingTo(BigDecimal("100.0"))
            balances.containsKey("AVAX") shouldBe false
        }

        "a completed transition whose named assets are still moving settles at a later observation" {
            // t2 is complete but still settling, so the quiescence check rejects it and t3 -- which
            // repeats unchanged -- becomes the anchor.
            val stillMoving = snapshot(
                t2,
                mapOf(
                    "HBAR" to row("0.0", "10.00", "0.00"),
                    "TRX" to row("0.0", "1.00", "0.00"),
                    "AVAX" to row("300.0", "10.00", "3000.00"),
                ),
            )
            val settledLater = snapshot(
                t3,
                mapOf(
                    "HBAR" to row("0.0", "10.00", "0.00"),
                    "TRX" to row("0.0", "1.00", "0.00"),
                    "AVAX" to row("450.0", "10.00", "4500.00"),
                ),
            )
            val regimes = RebalancerComparisonCalculator.anchoredRegimesForTest(
                activities = listOf(removal("HBAR"), addition("AVAX"), held),
                snapshots = listOf(baseline, stillMoving, settledLater, settledLater),
                baseline = baseline,
                comparisonAssetSymbols = assetScope,
                inceptionWeights = inceptionWeights,
            )

            regimes shouldHaveSize 1
            regimes.single().anchor.timestamp shouldBe t3
        }

        "an out-of-scope addition row at the anchor cannot be valued into a funding share" {
            val anchorWithOutOfScopeRow = snapshot(
                t2,
                mapOf(
                    "HBAR" to row("0.0", "10.00", "0.00"),
                    "TRX" to row("0.0", "1.00", "0.00"),
                    // Present in the snapshot, but not part of the comparison universe.
                    "OUTSIDE" to row("300.0", "10.00", "3000.00"),
                ),
            )
            val regimes = RebalancerComparisonCalculator.anchoredRegimesForTest(
                activities = listOf(removal("HBAR"), addition("OUTSIDE"), held),
                snapshots = listOf(baseline, anchorWithOutOfScopeRow, anchorWithOutOfScopeRow),
                baseline = baseline,
                comparisonAssetSymbols = assetScope,
                inceptionWeights = inceptionWeights,
            )

            regimes shouldHaveSize 0
        }

        "an additions-only transition leaves the configuration untouched rather than diluting it" {
            val regimes = RebalancerComparisonCalculator.anchoredRegimesForTest(
                // No removal at all: the transition is additions-only, so nothing can fund them.
                activities = listOf(addition("AVAX"), addition("LINK"), held),
                snapshots = listOf(
                    baseline,
                    settled("300.0", "100.0", t2),
                    settled("300.0", "100.0", t3),
                ),
                baseline = baseline,
                comparisonAssetSymbols = assetScope,
                inceptionWeights = inceptionWeights,
            )

            regimes shouldHaveSize 1
            // With no removal there is nothing to fund the additions, so the transition is anchored
            // but contributes no allocation: the configuration the book holds stays authoritative
            // for later contributions.
            val allocation = regimes.single().configurationAllocation
            allocation.keys shouldBe inceptionWeights.keys
            allocation.getValue("HBAR").setScale(6, java.math.RoundingMode.HALF_UP)
                .shouldBeEqualComparingTo(BigDecimal("0.500000"))
            allocation.getValue("TRX").setScale(6, java.math.RoundingMode.HALF_UP)
                .shouldBeEqualComparingTo(BigDecimal("0.500000"))
        }

        "released weight is added to cash the configuration already held" {
            val regimes = RebalancerComparisonCalculator.anchoredRegimesForTest(
                activities = listOf(removal("HBAR"), held),
                snapshots = listOf(
                    baseline,
                    settled("0.0", "0.0", t2),
                    settled("0.0", "0.0", t3),
                ),
                baseline = baseline,
                comparisonAssetSymbols = assetScope,
                inceptionWeights = mapOf(
                    "HBAR" to BigDecimal("0.4"),
                    "TRX" to BigDecimal("0.4"),
                    "USD" to BigDecimal("0.2"),
                ),
            )

            regimes shouldHaveSize 1
            val allocation = regimes.single().configurationAllocation
            // The 0.4 released by the removal lands on top of the 0.2 cash weight already held.
            allocation.getValue("TRX").setScale(6, java.math.RoundingMode.HALF_UP)
                .shouldBeEqualComparingTo(BigDecimal("0.400000"))
            allocation.getValue("USD").setScale(6, java.math.RoundingMode.HALF_UP)
                .shouldBeEqualComparingTo(BigDecimal("0.600000"))
        }

        "a configuration member whose share rounds to zero is dropped from later allocations" {
            val regimes = RebalancerComparisonCalculator.anchoredRegimesForTest(
                activities = listOf(removal("HBAR"), addition("AVAX"), held),
                snapshots = listOf(
                    baseline,
                    settled("300.0", "0.0", t2),
                    settled("300.0", "0.0", t3),
                ),
                baseline = baseline,
                comparisonAssetSymbols = assetScope,
                inceptionWeights = mapOf(
                    "HBAR" to BigDecimal("0.5"),
                    // A dust crumb that carries no weight in the configuration.
                    "XMR" to BigDecimal("0.0"),
                    "TRX" to BigDecimal("0.5"),
                ),
            )

            regimes shouldHaveSize 1
            regimes.single().configurationAllocation.keys shouldBe setOf("TRX", "AVAX")
        }

        "a removal that is not held contributes nothing while a held one still releases its value" {
            val balances = mutableMapOf("HBAR" to BigDecimal("100.0"), "TRX" to BigDecimal("1000.0"))

            val turnover = (
                RebalancerComparisonCalculator.replayConfigurationResetForTest(
                    balances = balances,
                    event = reset(
                        removals = setOf("HBAR", "UNHELD"),
                        additions = setOf("AVAX"),
                        shares = mapOf("AVAX" to BigDecimal("1.0")),
                    ),
                    priceProvider = prices,
                ) as RebalancerComparisonCalculator.ConfigurationResetOutcome.Applied
                ).turnover

            // Only the held removal is valued, so the addition is funded by exactly that.
            balances.getValue("AVAX").shouldBeEqualComparingTo(BigDecimal("100.0"))
            turnover.cryptoSellNotional.shouldBeEqualComparingTo(BigDecimal("1000.0"))
        }

        "a removal-only reset on a book already holding cash adds the proceeds to it" {
            val balances = mutableMapOf(
                "HBAR" to BigDecimal("100.0"),
                "USD" to BigDecimal("250.0"),
            )

            val turnover = (
                RebalancerComparisonCalculator.replayConfigurationResetForTest(
                    balances = balances,
                    event = reset(removals = setOf("HBAR")),
                    priceProvider = prices,
                ) as RebalancerComparisonCalculator.ConfigurationResetOutcome.Applied
                ).turnover

            balances.getValue("USD").shouldBeEqualComparingTo(BigDecimal("1250.0"))
            turnover.cashDelta.shouldBeEqualComparingTo(BigDecimal("1000.0"))
        }
    }
}
