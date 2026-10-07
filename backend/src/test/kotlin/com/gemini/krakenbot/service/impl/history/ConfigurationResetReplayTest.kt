package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.model.ComparisonUnavailableReason
import com.gemini.krakenbot.model.PortfolioSnapshot
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

/**
 * Membership-scoped configuration-reset semantics and the economically required turnover they imply.
 *
 * The invariant these pin: an inferred transition may move the synthetic benchmark **only** for the
 * assets it names. Nothing about Actual's portfolio at the anchor -- including a transiently zero
 * balance, portfolio drift, or an incidental cash buffer -- may rewrite a holding the transition
 * never mentioned. A reset re-weights in place, so turnover is the sum of value deltas rather than a
 * full liquidation and repurchase.
 */
class ConfigurationResetReplayTest : StringSpec() {

    private val t0: Instant = Instant.parse("2025-12-05T17:00:00Z")
    private val t1: Instant = t0.plusSeconds(3_600)
    private val t2: Instant = t0.plusSeconds(7_200)
    private val t3: Instant = t2.plusSeconds(60)
    private val late: Instant = t0.plusSeconds(90L * 86_400L)

    private val unitPrices = mapOf(
        "BTC" to BigDecimal("100"),
        "ETH" to BigDecimal("50"),
        "TRX" to BigDecimal("1"),
        "HBAR" to BigDecimal("10"),
        "XMR" to BigDecimal("10"),
        "AVAX" to BigDecimal("10"),
        "LINK" to BigDecimal("10"),
    )

    private val prices = HistoricalPriceProvider { symbol, _ -> unitPrices[symbol] }

    private fun priceOf(symbol: String): BigDecimal = unitPrices[symbol] ?: BigDecimal.ONE

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

    private fun navOf(balances: Map<String, BigDecimal>): BigDecimal = balances.entries
        .filter { it.value.signum() != 0 }
        .fold(BigDecimal.ZERO) { acc, (symbol, balance) -> acc.add(balance.multiply(priceOf(symbol))) }

    /** Three equal 1000.00 legs plus cash, so every pre-value is unambiguous. */
    private fun flatBook() = mutableMapOf(
        "HBAR" to BigDecimal("100.0"),
        "XMR" to BigDecimal("100.0"),
        "TRX" to BigDecimal("1000.0"),
        "USD" to BigDecimal("0.0"),
    )

    private fun reset(
        removals: Set<String> = emptySet(),
        additions: Set<String> = emptySet(),
        shares: Map<String, BigDecimal> = emptyMap(),
        allocation: Map<String, BigDecimal> = emptyMap(),
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
        configurationAllocation = allocation,
    )

    private fun applied(outcome: RebalancerComparisonCalculator.ConfigurationResetOutcome) =
        (outcome as RebalancerComparisonCalculator.ConfigurationResetOutcome.Applied).turnover

    /** Activities that infer exactly "HBAR and XMR out, AVAX in" as one high-confidence transition. */
    private fun transitionActivities() = listOf(
        AssetRegimeActivity(
            symbol = "HBAR",
            fillCount = 1,
            firstFill = t0,
            lastFill = t0,
            fullExitAt = t1,
            economicallyPresentSpanMillis = 3_600_000L,
            establishedAt = t0,
            materiallyPresentAtBaseline = true,
        ),
        AssetRegimeActivity(
            symbol = "XMR",
            fillCount = 1,
            firstFill = t0,
            lastFill = t0,
            fullExitAt = t1,
            economicallyPresentSpanMillis = 3_600_000L,
            establishedAt = t0,
            materiallyPresentAtBaseline = true,
        ),
        AssetRegimeActivity(
            symbol = "AVAX",
            fillCount = 40,
            firstFill = t0,
            lastFill = late,
            fullExitAt = null,
            establishedAt = t1,
            materiallyPresentAtBaseline = false,
            economicallyPresentSpanMillis = Duration.between(t1, late).toMillis(),
        ),
        AssetRegimeActivity(
            symbol = "TRX",
            fillCount = 40,
            firstFill = t0,
            lastFill = late,
            fullExitAt = null,
            establishedAt = t0,
            materiallyPresentAtBaseline = true,
            economicallyPresentSpanMillis = Duration.between(t0, late).toMillis(),
        ),
    )

    private val assetScope = setOf("HBAR", "XMR", "TRX", "AVAX", "USD")

    private val inceptionWeights = mapOf(
        "HBAR" to BigDecimal("0.333333333333333333"),
        "XMR" to BigDecimal("0.333333333333333333"),
        "TRX" to BigDecimal("0.333333333333333333"),
    )

    /** The anchor the Actual portfolio reaches: removals drained, AVAX established, TRX momentarily zero. */
    private fun settledAnchor(timestamp: Instant = t2) = snapshot(
        timestamp,
        "3250.00",
        mapOf(
            "HBAR" to row("0.0", "10.00", "0.00"),
            "XMR" to row("0.0", "10.00", "0.00"),
            "TRX" to row("0.0", "1.00", "0.00"),
            "AVAX" to row("300.0", "10.00", "3000.00"),
            "USD" to row("250.00", "1.00", "250.00"),
        ),
    )

    private val baseline = snapshot(
        t0,
        "3000.00",
        mapOf(
            "HBAR" to row("100.0", "10.00", "1000.00"),
            "XMR" to row("100.0", "10.00", "1000.00"),
            "TRX" to row("1000.0", "1.00", "1000.00"),
        ),
    )

    init {
        "a named removal leaves the benchmark and releases its value" {
            val balances = flatBook()

            val turnover = applied(
                RebalancerComparisonCalculator.replayConfigurationResetForTest(
                    balances = balances,
                    event = reset(removals = setOf("HBAR", "XMR")),
                    priceProvider = prices,
                ),
            )

            balances.containsKey("HBAR") shouldBe false
            balances.containsKey("XMR") shouldBe false
            // Removal-only: released value is not spent, so it rests as benchmark cash.
            balances.getValue("USD").shouldBeEqualComparingTo(BigDecimal("2000.0"))
            turnover.cryptoSellNotional.shouldBeEqualComparingTo(BigDecimal("2000.0"))
            turnover.cryptoBuyNotional.shouldBeEqualComparingTo(BigDecimal("0.0"))
            turnover.cashDelta.shouldBeEqualComparingTo(BigDecimal("2000.0"))
        }

        "a named addition is bought from the released removal value" {
            val balances = flatBook()

            RebalancerComparisonCalculator.replayConfigurationResetForTest(
                balances = balances,
                event = reset(
                    removals = setOf("HBAR", "XMR"),
                    additions = setOf("AVAX"),
                    shares = mapOf("AVAX" to BigDecimal("1.0")),
                ),
                priceProvider = prices,
            )

            balances.getValue("AVAX").shouldBeEqualComparingTo(BigDecimal("200.0"))
            balances.containsKey("HBAR") shouldBe false
        }

        "an unnamed asset keeps its exact units" {
            val balances = flatBook()

            RebalancerComparisonCalculator.replayConfigurationResetForTest(
                balances = balances,
                event = reset(
                    removals = setOf("HBAR"),
                    additions = setOf("AVAX"),
                    shares = mapOf("AVAX" to BigDecimal("1.0")),
                ),
                priceProvider = prices,
            )

            balances.getValue("XMR").shouldBeEqualComparingTo(BigDecimal("100.0"))
            balances.getValue("TRX").shouldBeEqualComparingTo(BigDecimal("1000.0"))
        }

        "an unnamed asset whose Actual anchor balance is zero survives the reset" {
            val regimes = RebalancerComparisonCalculator.anchoredRegimesForTest(
                activities = transitionActivities(),
                // Two settled observations after the cluster, both showing TRX at zero.
                snapshots = listOf(baseline, settledAnchor(), settledAnchor(t3)),
                baseline = baseline,
                comparisonAssetSymbols = assetScope,
                inceptionWeights = inceptionWeights,
            )

            regimes shouldHaveSize 1
            val regime = regimes.single()
            regime.transition.removals shouldBe setOf("HBAR", "XMR")
            regime.transition.additions shouldBe setOf("AVAX")

            val balances = flatBook()
            val turnover = applied(
                RebalancerComparisonCalculator.replayConfigurationResetForTest(
                    balances = balances,
                    event = BenchmarkEvent.ConfigurationReset(
                        timestamp = regime.anchor.timestamp,
                        transition = regime.transition,
                        additionFundingShares = regime.additionFundingShares,
                        configurationAllocation = regime.configurationAllocation,
                    ),
                    priceProvider = prices,
                ),
            )

            // TRX is not a removal, so a transiently zero Actual observation cannot remove it.
            balances.getValue("TRX").shouldBeEqualComparingTo(BigDecimal("1000.0"))
            balances.containsKey("HBAR") shouldBe false
            balances.containsKey("XMR") shouldBe false
            balances.getValue("AVAX").shouldBeEqualComparingTo(BigDecimal("200.0"))
            // Actual's incidental cash buffer is never copied into the benchmark.
            balances.getValue("USD").shouldBeEqualComparingTo(BigDecimal("0.0"))
            turnover.delta.getValue("TRX").shouldBeEqualComparingTo(BigDecimal("0.0"))
            navOf(balances).shouldBeEqualComparingTo(BigDecimal("3000.0"))
        }

        "Actual anchor drift cannot rewrite an unnamed asset's exposure" {
            val balances = mutableMapOf("OLD" to BigDecimal("100.0"))

            RebalancerComparisonCalculator.replayConfigurationResetForTest(
                balances = balances,
                event = reset(removals = setOf("ABSENT")),
                priceProvider = prices,
            )

            // The named removal matches nothing held, so no value moves anywhere.
            balances.getValue("OLD").shouldBeEqualComparingTo(BigDecimal("100.0"))
        }

        "a removal's value is split across additions by the funding shares" {
            val balances = flatBook()

            RebalancerComparisonCalculator.replayConfigurationResetForTest(
                balances = balances,
                event = reset(
                    removals = setOf("HBAR", "XMR"),
                    additions = setOf("AVAX", "LINK"),
                    shares = mapOf("AVAX" to BigDecimal("0.75"), "LINK" to BigDecimal("0.25")),
                ),
                priceProvider = prices,
            )

            balances.getValue("AVAX").shouldBeEqualComparingTo(BigDecimal("150.0"))
            balances.getValue("LINK").shouldBeEqualComparingTo(BigDecimal("50.0"))
        }

        "an addition with no released value is left unapplied rather than created from nothing" {
            val balances = flatBook()

            val outcome = RebalancerComparisonCalculator.replayConfigurationResetForTest(
                balances = balances,
                event = reset(
                    additions = setOf("AVAX"),
                    shares = mapOf("AVAX" to BigDecimal("1.0")),
                ),
                priceProvider = prices,
            )

            outcome.shouldBeInstanceOf<RebalancerComparisonCalculator.ConfigurationResetOutcome.Skipped>()
            balances.containsKey("AVAX") shouldBe false
            balances.getValue("TRX").shouldBeEqualComparingTo(BigDecimal("1000.0"))
        }

        "an addition whose funding evidence is unusable is left unapplied" {
            val balances = flatBook()

            val outcome = RebalancerComparisonCalculator.replayConfigurationResetForTest(
                balances = balances,
                event = reset(removals = setOf("HBAR"), additions = setOf("AVAX"), shares = emptyMap()),
                priceProvider = prices,
            )

            outcome.shouldBeInstanceOf<RebalancerComparisonCalculator.ConfigurationResetOutcome.Skipped>()
            balances.containsKey("AVAX") shouldBe false
        }

        "a reset conserves the benchmark's full NAV and unrelated exposure" {
            val balances = flatBook()
            val before = navOf(balances)

            RebalancerComparisonCalculator.replayConfigurationResetForTest(
                balances = balances,
                event = reset(
                    removals = setOf("HBAR", "XMR"),
                    additions = setOf("AVAX", "LINK"),
                    shares = mapOf("AVAX" to BigDecimal("0.6"), "LINK" to BigDecimal("0.4")),
                ),
                priceProvider = prices,
            )

            navOf(balances).shouldBeEqualComparingTo(before)
            balances.getValue("TRX").multiply(priceOf("TRX"))
                .shouldBeEqualComparingTo(BigDecimal("1000.0"))
        }

        "an already-held addition absorbs only the funded amount" {
            val balances = flatBook().also { it["AVAX"] = BigDecimal("50.0") }

            RebalancerComparisonCalculator.replayConfigurationResetForTest(
                balances = balances,
                event = reset(
                    removals = setOf("HBAR", "XMR"),
                    additions = setOf("AVAX"),
                    shares = mapOf("AVAX" to BigDecimal("1.0")),
                ),
                priceProvider = prices,
            )

            // 500.00 already held plus 2000.00 funded -- never a re-purchase of the whole book.
            balances.getValue("AVAX").shouldBeEqualComparingTo(BigDecimal("250.0"))
            navOf(balances).shouldBeEqualComparingTo(BigDecimal("3500.0"))
        }

        "turnover is the sum of value deltas, not a full liquidation and repurchase" {
            val balances = mutableMapOf("BTC" to BigDecimal("50.0"), "ETH" to BigDecimal("100.0"))

            val turnover = applied(
                RebalancerComparisonCalculator.replayConfigurationResetForTest(
                    balances = balances,
                    event = reset(
                        removals = setOf("ETH"),
                        additions = setOf("BTC"),
                        shares = mapOf("BTC" to BigDecimal("1.0")),
                    ),
                    priceProvider = prices,
                ),
            )

            // Sell ETH 5000.00 and buy BTC 5000.00 -- not sell 10000.00 and buy 10000.00.
            turnover.cryptoSellNotional.shouldBeEqualComparingTo(BigDecimal("5000.0"))
            turnover.cryptoBuyNotional.shouldBeEqualComparingTo(BigDecimal("5000.0"))
            turnover.cashDelta.shouldBeEqualComparingTo(BigDecimal("0.0"))
            turnover.delta.getValue("ETH").shouldBeEqualComparingTo(BigDecimal("-5000.0"))
            turnover.delta.getValue("BTC").shouldBeEqualComparingTo(BigDecimal("5000.0"))
            // A full-liquidation reading would be 2 x NAV = 20000.00.
            turnover.cryptoSellNotional.add(turnover.cryptoBuyNotional)
                .shouldBeEqualComparingTo(BigDecimal("10000.0"))
        }

        "a reset fails closed when a currently held asset has no price" {
            val balances = mutableMapOf("UNPRICED" to BigDecimal("10.0"))

            val failure = RebalancerComparisonCalculator.replayBenchmarkEventForTest(
                balances = balances,
                event = reset(
                    removals = setOf("UNPRICED"),
                    additions = setOf("BTC"),
                    shares = mapOf("BTC" to BigDecimal("1.0")),
                ),
                priceProvider = prices,
            )

            failure shouldNotBe null
            failure?.first shouldBe ComparisonUnavailableReason.MISSING_PRICE
        }

        "a reset fails closed when a named addition cannot be priced" {
            val balances = flatBook()

            val failure = RebalancerComparisonCalculator.replayBenchmarkEventForTest(
                balances = balances,
                event = reset(
                    removals = setOf("HBAR"),
                    additions = setOf("UNPRICED"),
                    shares = mapOf("UNPRICED" to BigDecimal("1.0")),
                ),
                priceProvider = prices,
            )

            failure shouldNotBe null
            failure?.first shouldBe ComparisonUnavailableReason.MISSING_PRICE
        }

        "a reset with no positive holdings fails closed rather than creating value" {
            val balances = mutableMapOf<String, BigDecimal>()

            val failure = RebalancerComparisonCalculator.replayBenchmarkEventForTest(
                balances = balances,
                event = reset(additions = setOf("BTC"), shares = mapOf("BTC" to BigDecimal("1.0"))),
                priceProvider = prices,
            )

            failure shouldNotBe null
            failure?.first shouldBe ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE
        }

        "a non-positive funding share is refused rather than destroying that leg's NAV" {
            val balances = flatBook()

            val outcome = RebalancerComparisonCalculator.replayConfigurationResetForTest(
                balances = balances,
                event = reset(
                    removals = setOf("HBAR", "XMR"),
                    additions = setOf("AVAX", "LINK"),
                    shares = mapOf("AVAX" to BigDecimal("1.0"), "LINK" to BigDecimal("0.0")),
                ),
                priceProvider = prices,
            )

            outcome.shouldBeInstanceOf<RebalancerComparisonCalculator.ConfigurationResetOutcome.Skipped>()
            balances.containsKey("AVAX") shouldBe false
        }

        "a zero holding needs no price and does not dilute the reset" {
            val balances = flatBook().also { it["DUST"] = BigDecimal.ZERO }

            RebalancerComparisonCalculator.replayConfigurationResetForTest(
                balances = balances,
                event = reset(
                    removals = setOf("HBAR"),
                    additions = setOf("AVAX"),
                    shares = mapOf("AVAX" to BigDecimal("1.0")),
                ),
                priceProvider = prices,
            )

            balances.getValue("AVAX").shouldBeEqualComparingTo(BigDecimal("100.0"))
        }

        "already-held additions succeed when released value is zero without buying new units" {
            val balances = mutableMapOf(
                "BTC" to BigDecimal("1.5"),
                "ETH" to BigDecimal("10.0"),
                "TRX" to BigDecimal("1000.0"),
            )

            val outcome = RebalancerComparisonCalculator.replayConfigurationResetForTest(
                balances = balances,
                event = reset(
                    removals = emptySet(),
                    additions = setOf("BTC", "ETH"),
                    shares = mapOf("BTC" to BigDecimal("0.5"), "ETH" to BigDecimal("0.5")),
                ),
                priceProvider = prices,
            )

            val turnover = applied(outcome)
            balances.getValue("BTC").shouldBeEqualComparingTo(BigDecimal("1.5"))
            balances.getValue("ETH").shouldBeEqualComparingTo(BigDecimal("10.0"))
            balances.getValue("TRX").shouldBeEqualComparingTo(BigDecimal("1000.0"))
            turnover.cryptoBuyNotional.shouldBeEqualComparingTo(BigDecimal("0.0"))
            turnover.cryptoSellNotional.shouldBeEqualComparingTo(BigDecimal("0.0"))
            turnover.cashDelta.shouldBeEqualComparingTo(BigDecimal("0.0"))
        }

        "additions not held in the benchmark are skipped when released value is zero" {
            val balances = mutableMapOf(
                "TRX" to BigDecimal("1000.0"),
            )

            val outcome = RebalancerComparisonCalculator.replayConfigurationResetForTest(
                balances = balances,
                event = reset(
                    removals = emptySet(),
                    additions = setOf("BTC"),
                    shares = mapOf("BTC" to BigDecimal("1.0")),
                ),
                priceProvider = prices,
            )

            outcome.shouldBeInstanceOf<RebalancerComparisonCalculator.ConfigurationResetOutcome.Skipped>()
            balances.containsKey("BTC") shouldBe false
        }

        "partially held additions are skipped when released value is zero" {
            val balances = mutableMapOf(
                "BTC" to BigDecimal("1.5"),
                "TRX" to BigDecimal("1000.0"),
            )

            val outcome = RebalancerComparisonCalculator.replayConfigurationResetForTest(
                balances = balances,
                event = reset(
                    removals = emptySet(),
                    additions = setOf("BTC", "ETH"),
                    shares = mapOf("BTC" to BigDecimal("0.5"), "ETH" to BigDecimal("0.5")),
                ),
                priceProvider = prices,
            )

            outcome.shouldBeInstanceOf<RebalancerComparisonCalculator.ConfigurationResetOutcome.Skipped>()
            balances.containsKey("ETH") shouldBe false
            balances.getValue("BTC").shouldBeEqualComparingTo(BigDecimal("1.5"))
        }

        "additions held with zero balance are not considered already-held and are skipped when released is zero" {
            val balances = mutableMapOf(
                "BTC" to BigDecimal.ZERO,
                "TRX" to BigDecimal("1000.0"),
            )

            val outcome = RebalancerComparisonCalculator.replayConfigurationResetForTest(
                balances = balances,
                event = reset(
                    removals = emptySet(),
                    additions = setOf("BTC"),
                    shares = mapOf("BTC" to BigDecimal("1.0")),
                ),
                priceProvider = prices,
            )

            outcome.shouldBeInstanceOf<RebalancerComparisonCalculator.ConfigurationResetOutcome.Skipped>()
        }
    }
}
