package com.gemini.krakenbot.replay

import com.fasterxml.jackson.databind.ObjectMapper
import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.model.Asset
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import java.io.File
import java.math.BigDecimal

/**
 * Runs [ReplayComparator] against a locally supplied historical fixture.
 *
 * The fixture is deliberately **not** committed: it is derived from a real account and contains
 * real balances, prices and funding history. Point the suite at one with
 *
 * ```
 * REPLAY_FIXTURE_PATH=/path/to/fixture.json ./gradlew :backend:test --tests "*LocalReplayFixtureTest*"
 * ```
 *
 * and it reports the rebalanced-versus-buy-and-hold comparison for that history. Without the
 * variable the spec passes trivially so CI never depends on private data. This mirrors the
 * `SCENARIOS_REPORT_PATH` convention used by the evaluation suite.
 */
class LocalReplayFixtureTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    private class AllocationDto {
        var symbol: String = ""
        var targetPercent: Double = 0.0
    }

    private class FlowDto {
        var dayIndex: Int = 0
        var usd: String = "0"
    }

    private class Fixture {
        var allocations: List<AllocationDto> = emptyList()
        var feeRate: String = "0.0035"
        var deviationTriggerPercent: Double = 5.0
        var minimumOrderSizeUSD: Double = 5.0
        var openingCapital: String = "0"
        var closes: Map<String, List<String>> = emptyMap()
        var flows: List<FlowDto> = emptyList()

        /** > 0 enables sell suppression for assets at their highest close over this window. */
        var trendLookbackDays: Int = 0
    }

    init {
        "historical replay compares the rebalancer with buy-and-hold on real data" {
            val path = System.getenv("REPLAY_FIXTURE_PATH")
            if (path.isNullOrBlank()) {
                // No private fixture available: nothing to measure, and nothing to fail.
                true.shouldBeTrue()
            } else {
                replay(path)
            }
        }
    }

    private fun replay(path: String) {
        val fixture = ObjectMapper().readValue(File(path), Fixture::class.java)
        val comparator = ReplayComparator(
            allocations = fixture.allocations.map { Allocation(Asset(it.symbol), it.targetPercent) },
            settings = TestFixtures.settings(
                dryRun = true,
                deviationTriggerPercent = fixture.deviationTriggerPercent,
                minimumOrderSizeUSD = fixture.minimumOrderSizeUSD,
            ),
            feeRate = BigDecimal(fixture.feeRate),
        )
        val closes = fixture.closes.mapValues { (_, series) -> series.map(::BigDecimal) }
        val lookback = fixture.trendLookbackDays
        val outcome = comparator.run(
            closes = closes,
            flows = fixture.flows.map { ReplayComparator.Flow(it.dayIndex, BigDecimal(it.usd)) },
            openingCapital = BigDecimal(fixture.openingCapital),
            trendingSymbols = { day, prices ->
                if (lookback <= 0) {
                    emptySet()
                } else {
                    val from = maxOf(0, day - lookback + 1)
                    closes.keys.filterTo(mutableSetOf()) { symbol ->
                        val window = closes.getValue(symbol).subList(from, day + 1)
                        window.isNotEmpty() && prices.getValue(symbol) >= window.max()
                    }
                }
            },
        )

        println("=== LOCAL REPLAY ($path) ===")
        println("  rebalanced NAV      ${outcome.nav}")
        println("  buy-and-hold NAV    ${outcome.buyAndHoldNav}")
        println("  rebalanced - hold   ${outcome.nav.subtract(outcome.buyAndHoldNav)}")
        println("  rebalanced fees     ${outcome.fees}")
        println("  buy-and-hold fees   ${outcome.buyAndHoldFees}")
        println("  trades              ${outcome.tradeCount}")
        println("  suppressed sells    ${outcome.suppressedSells}")

        (outcome.nav.signum() >= 0).shouldBeTrue()
        (outcome.buyAndHoldNav.signum() >= 0).shouldBeTrue()
    }
}
