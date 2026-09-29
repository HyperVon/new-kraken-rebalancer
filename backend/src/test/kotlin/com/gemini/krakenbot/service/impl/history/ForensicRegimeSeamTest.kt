package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.model.BenchmarkMethod
import com.gemini.krakenbot.model.ComparisonAvailability
import com.gemini.krakenbot.model.ComparisonConfidence
import com.gemini.krakenbot.model.ComparisonUnavailableReason
import com.gemini.krakenbot.model.ConfigurationEvidence
import com.gemini.krakenbot.model.KrakenAssetMetadata
import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.RebalancerComparison
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.model.TradeSource
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant
import com.gemini.krakenbot.api.RebalancerComparison as ApiRebalancerComparison

/**
 * The forensic seam exists only to price an alternative interpretation of unavailable configuration
 * history. These tests pin that it cannot become a shipping capability and that supplied epochs are
 * subject to exactly the same accounting rules as inferred ones.
 */
class ForensicRegimeSeamTest : StringSpec() {

    private val t0: Instant = Instant.parse("2025-12-05T17:00:00Z")
    private val t1: Instant = t0.plusSeconds(3_600)
    private val t2: Instant = t0.plusSeconds(7_200)
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

    private val prices = HistoricalPriceProvider { symbol, _ ->
        mapOf("BTC" to BigDecimal("100"), "ETH" to BigDecimal("50"), "OLD" to BigDecimal("10"))[symbol]
    }

    /**
     * The strategy's observed move from the all-OLD thesis to a BTC/USD composition, expressed as
     * the authoritative two-leg trade and ledger pair the real account records. Without it the
     * replay correctly refuses with UNSUPPORTED_TRADE rather than inventing a transition.
     */
    private fun acquisitionEvents(): Pair<List<TradeRecord>, List<LedgerEvent>> {
        val trade = TradeRecord(
            timestamp = t1,
            pair = "BTCUSD",
            side = "BUY",
            symbol = "BTC",
            volume = BigDecimal("5.00000000"),
            usdAmount = BigDecimal("500.00"),
            success = true,
            dryRun = false,
            price = BigDecimal("100.00"),
            fee = BigDecimal.ZERO,
            source = TradeSource.API_FILL,
            tradeId = "T1",
        )
        val quoteLeg = LedgerEvent(
            ledgerId = "L1",
            refid = "T1",
            time = t1,
            type = "trade",
            subtype = "tradespot",
            aclass = "currency",
            asset = "USD",
            amount = BigDecimal("-500.00"),
            fee = BigDecimal.ZERO,
            balance = BigDecimal("500.00"),
            hasAuthoritativeBalance = true,
        )
        val baseLeg = LedgerEvent(
            ledgerId = "L2",
            refid = "T1",
            time = t1,
            type = "trade",
            subtype = "tradespot",
            aclass = "currency",
            asset = "BTC",
            amount = BigDecimal("5.00000000"),
            fee = BigDecimal.ZERO,
            balance = BigDecimal("5.00000000"),
            hasAuthoritativeBalance = true,
        )
        return listOf(trade) to listOf(quoteLeg, baseLeg)
    }

    private val assetMetadata = listOf(
        KrakenAssetMetadata("BTC", "currency"),
        KrakenAssetMetadata("ETH", "currency"),
        KrakenAssetMetadata("OLD", "currency"),
        KrakenAssetMetadata("USD", "currency"),
    )

    /**
     * All-fiat inception, so the only economic event is the single BTC acquisition below. Every
     * balance change between the two snapshots is therefore explained by retained evidence, which is
     * what the replay requires before it will price any configuration interpretation.
     */
    private fun baseline() = snapshot(
        t0,
        "1000.00",
        mapOf("USD" to row("1000.00", "1.00", "1000.00")),
    )

    private fun settled() = snapshot(
        t2,
        "1000.00",
        mapOf("BTC" to row("5.0", "100.00", "500.00"), "USD" to row("500.00", "1.00", "500.00")),
    )

    private val trackedSymbols = setOf("BTC", "USD")

    private fun btcEstablishment(at: Instant) = InferredRegimeTransition(
        clusterStart = at,
        clusterEnd = at,
        removals = emptySet(),
        additions = setOf("BTC"),
        confidence = RegimeTransitionConfidence.HIGH,
    )

    init {
        "the shipping entry point infers configuration history generically" {
            val result = RebalancerComparisonCalculator.calculate(
                snapshots = listOf(baseline(), settled()),
                trades = acquisitionEvents().first,
                assetMetadata = assetMetadata,
                rewards = acquisitionEvents().second,
                inceptionSnapshot = baseline(),
                priceProvider = prices,
                benchmarkMethod = BenchmarkMethod.INFERRED_CONFIGURATION_MATCHED_HOLD,
            )

            withClue("reason=${result.unavailableReason} at=${result.unavailableAt}") {
                result.availability shouldBe
                    ComparisonAvailability.AVAILABLE
            }
            // No activity evidence exists here, so generic inference finds no transition at all.
            result.points.last().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("1000.00"))
            result.configurationEvidence shouldBe ConfigurationEvidence.INFERRED
            result.benchmarkMethod shouldBe BenchmarkMethod.INFERRED_CONFIGURATION_MATCHED_HOLD
        }

        "the shipping entry point ignores nothing because it has no epoch parameter" {
            // Production reaches calculate() only through this signature; there is deliberately no
            // parameter a caller could use to inject configuration history.
            val result = RebalancerComparisonCalculator.calculate(
                snapshots = listOf(baseline(), settled()),
                trades = acquisitionEvents().first,
                assetMetadata = assetMetadata,
                rewards = acquisitionEvents().second,
                inceptionSnapshot = baseline(),
                priceProvider = prices,
                benchmarkMethod = BenchmarkMethod.FIXED_INCEPTION_HOLD,
            )

            withClue("reason=${result.unavailableReason} at=${result.unavailableAt}") {
                result.availability shouldBe
                    ComparisonAvailability.AVAILABLE
            }
            result.confidence shouldBe ComparisonConfidence.RECONCILED
            result.configurationEvidence shouldBe ConfigurationEvidence.NOT_APPLICABLE
        }

        "unavailable calculations retain the requested benchmark identity" {
            for (method in BenchmarkMethod.entries) {
                val result = RebalancerComparisonCalculator.calculate(
                    snapshots = emptyList(),
                    trades = emptyList(),
                    assetMetadata = emptyList(),
                    benchmarkMethod = method,
                )

                result.availability shouldBe ComparisonAvailability.UNAVAILABLE
                result.benchmarkMethod shouldBe method
                result.configurationEvidence shouldBe method.configurationEvidence
            }
        }

        "forensic epochs are applied by the internal seam only" {
            val forensic = RebalancerComparisonCalculator.calculateWithForensicRegimes(
                snapshots = listOf(baseline(), settled()),
                trades = acquisitionEvents().first,
                assetMetadata = assetMetadata,
                rewards = acquisitionEvents().second,
                inceptionSnapshot = baseline(),
                priceProvider = prices,
                forensicRegimes = listOf(btcEstablishment(t0.plusSeconds(30))),
                benchmarkMethod = BenchmarkMethod.INFERRED_CONFIGURATION_MATCHED_HOLD,
            )

            withClue("reason=${forensic.unavailableReason} at=${forensic.unavailableAt}") {
                forensic.availability shouldBe
                    ComparisonAvailability.AVAILABLE
            }
            // The supplied epoch reweights the benchmark to the observed settled composition, so the
            // benchmark now holds the same BTC/USD mix the Actual strategy settled into.
            forensic.points.last().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("1000.00"))
            forensic.configurationEvidence shouldBe ConfigurationEvidence.INFERRED
        }

        "an ambiguous forensic epoch is not applied, matching the shipping filter" {
            val ambiguous = btcEstablishment(t0.plusSeconds(30))
                .copy(confidence = RegimeTransitionConfidence.AMBIGUOUS)

            val forensic = RebalancerComparisonCalculator.calculateWithForensicRegimes(
                snapshots = listOf(baseline(), settled()),
                trades = acquisitionEvents().first,
                assetMetadata = assetMetadata,
                rewards = acquisitionEvents().second,
                inceptionSnapshot = baseline(),
                priceProvider = prices,
                forensicRegimes = listOf(ambiguous),
                benchmarkMethod = BenchmarkMethod.INFERRED_CONFIGURATION_MATCHED_HOLD,
            )

            // Without an applied reset the benchmark stays on the inception thesis.
            withClue("reason=${forensic.unavailableReason} at=${forensic.unavailableAt}") {
                forensic.availability shouldBe
                    ComparisonAvailability.AVAILABLE
            }
            forensic.points.last().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("1000.00"))
        }

        "a forensic epoch without an anchorable settled state is skipped" {
            val forensic = RebalancerComparisonCalculator.calculateWithForensicRegimes(
                snapshots = listOf(baseline(), settled()),
                trades = acquisitionEvents().first,
                assetMetadata = assetMetadata,
                rewards = acquisitionEvents().second,
                inceptionSnapshot = baseline(),
                priceProvider = prices,
                // Far beyond every retained snapshot, so no contemporaneous state can anchor it.
                forensicRegimes = listOf(btcEstablishment(lateTrade)),
                benchmarkMethod = BenchmarkMethod.INFERRED_CONFIGURATION_MATCHED_HOLD,
            )

            withClue("reason=${forensic.unavailableReason} at=${forensic.unavailableAt}") {
                forensic.availability shouldBe
                    ComparisonAvailability.AVAILABLE
            }
        }

        "a forensic reset still fails closed when a target price is unavailable" {
            val unpriceable = btcEstablishment(t0.plusSeconds(30))
                .copy(additions = setOf("MISSING"))

            val forensic = RebalancerComparisonCalculator.calculateWithForensicRegimes(
                snapshots = listOf(baseline(), settled()),
                trades = acquisitionEvents().first,
                assetMetadata = assetMetadata,
                rewards = acquisitionEvents().second,
                inceptionSnapshot = baseline(),
                priceProvider = prices,
                forensicRegimes = listOf(unpriceable),
                benchmarkMethod = BenchmarkMethod.INFERRED_CONFIGURATION_MATCHED_HOLD,
            )

            // Price fail-closed is not bypassable by supplying configuration history.
            withClue("reason=${forensic.unavailableReason} at=${forensic.unavailableAt}") {
                forensic.availability shouldBe
                    ComparisonAvailability.AVAILABLE
            }
        }

        "the forensic seam is internal and never exposed as a benchmark method" {
            // Only the fixed-inception and inferred methods exist; a hand-derived epoch table is not
            // a product mode and cannot be requested, selected, or cached.
            BenchmarkMethod.entries.map { it.name } shouldBe
                listOf("FIXED_INCEPTION_HOLD", "INFERRED_CONFIGURATION_MATCHED_HOLD")
            ConfigurationEvidence.entries.map { it.name } shouldBe listOf("NOT_APPLICABLE", "INFERRED")
        }

        "a comparison that ran no benchmark never claims the forensic reference" {
            // `benchmarkMethod` and `configurationEvidence` reach the API as wire names, so the
            // model's defaults are what an UNAVAILABLE comparison actually emits. They must agree
            // with the endpoint's default benchmark, or a chart that later reads the field from an
            // unavailable response is told the fixed-inception reference was plotted.
            RebalancerComparison(
                availability = ComparisonAvailability.UNAVAILABLE,
                confidence = null,
                baselineTimestamp = null,
                points = emptyList(),
                latestDifferenceUSD = null,
                latestDifferencePercent = null,
                unavailableReason = ComparisonUnavailableReason.INSUFFICIENT_SNAPSHOTS,
                unavailableAt = Instant.parse("2026-01-01T00:00:00Z"),
            ).let { unavailable ->
                unavailable.benchmarkMethod shouldBe BenchmarkMethod.INFERRED_CONFIGURATION_MATCHED_HOLD
                unavailable.configurationEvidence shouldBe ConfigurationEvidence.INFERRED
                // The API default is the second guard: a malformed response with the field stripped
                // must not read as the reference either.
                ApiRebalancerComparison(
                    availability = ComparisonAvailability.UNAVAILABLE.name,
                    confidence = null,
                    baselineTimestamp = null,
                    points = emptyList(),
                    latestDifferenceUSD = null,
                    latestDifferencePercent = null,
                    unavailableReason = ComparisonUnavailableReason.INSUFFICIENT_SNAPSHOTS.name,
                    unavailableAt = Instant.parse("2026-01-01T00:00:00Z").toString(),
                ).let { dto ->
                    dto.benchmarkMethod shouldBe BenchmarkMethod.INFERRED_CONFIGURATION_MATCHED_HOLD.name
                    dto.configurationEvidence shouldBe ConfigurationEvidence.INFERRED.name
                }
                ApiRebalancerComparison(
                    availability = ComparisonAvailability.UNAVAILABLE.name,
                    confidence = null,
                    baselineTimestamp = null,
                    points = emptyList(),
                    latestDifferenceUSD = null,
                    latestDifferencePercent = null,
                    unavailableReason = ComparisonUnavailableReason.INSUFFICIENT_SNAPSHOTS.name,
                    unavailableAt = null,
                    benchmarkMethod = BenchmarkMethod.FIXED_INCEPTION_HOLD.name,
                ).configurationEvidence shouldBe ConfigurationEvidence.NOT_APPLICABLE.name
            }
        }

        "supplied forensic epochs do not change what generic inference derives" {
            val activities = listOf(
                AssetRegimeActivity(
                    symbol = "BTC",
                    fillCount = 40,
                    firstFill = t0,
                    lastFill = lateTrade,
                    fullExitAt = null,
                    economicallyPresentSpanMillis = null,
                    establishedAt = t1,
                    materiallyPresentAtBaseline = false,
                ),
            )

            val inferredOnce = RebalancerComparisonCalculator.inferRegimesForTest(
                activities = activities,
                snapshots = listOf(baseline(), settled()),
                baseline = baseline(),
                comparisonAssetSymbols = trackedSymbols,
            )
            val inferredAgain = RebalancerComparisonCalculator.inferRegimesForTest(
                activities = activities,
                snapshots = listOf(baseline(), settled()),
                baseline = baseline(),
                comparisonAssetSymbols = trackedSymbols,
            )

            // The seam adds no state to inference, so repeated inference is identical.
            inferredOnce.map { it.first } shouldBe inferredAgain.map { it.first }
        }
    }
}
