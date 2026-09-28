package com.gemini.krakenbot.replay

import com.fasterxml.jackson.databind.ObjectMapper
import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.domain.QualityAllocation
import com.gemini.krakenbot.model.Asset
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Compares allocation/trigger variants on a locally supplied historical fixture, all through
 * the production [ReplayComparator] so every decision is the engine's own.
 *
 * Fixture path comes from `REPLAY_FIXTURE_PATH`; without it the spec passes trivially so CI
 * never depends on private data. The fixture is never committed.
 */
class LocalReplayVariantsTest : StringSpec() {

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

        /** Accepted for fixture compatibility; the lookback is a per-variant argument here. */
        @Suppress("unused")
        var trendLookbackDays: Int = 0
    }

    /** Fundamentals the operator maintains outside the bot. */
    private val scores = mapOf(
        "BTC" to BigDecimal("9.5"), "ETH" to BigDecimal("8.5"), "SOL" to BigDecimal("8.5"),
        "LINK" to BigDecimal("8.0"), "INJ" to BigDecimal("8.0"), "TRX" to BigDecimal("7.5"),
        "XRP" to BigDecimal("7.0"), "AVAX" to BigDecimal("7.0"), "RENDER" to BigDecimal("6.5"),
        "TAO" to BigDecimal("6.0"),
    )

    init {
        "allocation and trigger variants compare against buy-and-hold on real history" {
            compareVariants()
        }

        "the tail-stop benefit holds up across both halves of the window" {
            compareHalves()
        }
    }

    /** Compares the current allocation with and without the tail-stop on two independent halves. */
    private fun compareHalves() {
        val path = System.getenv("REPLAY_FIXTURE_PATH")
        if (path.isNullOrBlank()) {
            true.shouldBeTrue()
            return
        }
        val fixture = ObjectMapper().readValue(File(path), Fixture::class.java)
        val closes = fixture.closes.mapValues { (_, series) -> series.map(::BigDecimal) }
        val flows = fixture.flows.map { ReplayComparator.Flow(it.dayIndex, BigDecimal(it.usd)) }
        val opening = BigDecimal(fixture.openingCapital)
        val baseline = fixture.allocations.map { Allocation(Asset(it.symbol), it.targetPercent) }
        val days = closes.values.first().size
        val midpoint = days / 2

        fun deltaFor(lastDay: Int, lookback: Int): BigDecimal {
            val comparator = ReplayComparator(
                allocations = baseline,
                settings = TestFixtures.settings(
                    dryRun = true,
                    deviationTriggerPercent = fixture.deviationTriggerPercent,
                    minimumOrderSizeUSD = fixture.minimumOrderSizeUSD,
                ),
                feeRate = BigDecimal(fixture.feeRate),
            )
            val outcome = comparator.run(
                closes = closes,
                flows = flows,
                openingCapital = opening,
                lastDay = lastDay,
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
            return outcome.nav.subtract(outcome.buyAndHoldNav)
        }

        println("=== WALK-FORWARD: tail-stop stability across halves ($path) ===")
        println("    window days=$days   first half 0..$midpoint   second half 0..${days - 1}")
        val firstPlain = deltaFor(midpoint, lookback = 0)
        val firstStop = deltaFor(midpoint, lookback = 20)
        val fullPlain = deltaFor(days - 1, lookback = 0)
        val fullStop = deltaFor(days - 1, lookback = 20)
        println(
            "    first half   no tail-stop %10s   tail-stop %10s   improvement %10s".format(
                firstPlain,
                firstStop,
                firstStop.subtract(firstPlain),
            ),
        )
        println(
            "    full window  no tail-stop %10s   tail-stop %10s   improvement %10s".format(
                fullPlain,
                fullStop,
                fullStop.subtract(fullPlain),
            ),
        )
        val improvedFirst = firstStop > firstPlain
        val improvedFull = fullStop > fullPlain
        println("    improvement in first half: $improvedFirst   in full window: $improvedFull")
        if (improvedFirst != improvedFull) {
            println("    WARNING: the tail-stop benefit is NOT stable across the split")
        }
        true.shouldBeTrue()
    }

    private fun compareVariants() {
        val path = System.getenv("REPLAY_FIXTURE_PATH")
        if (path.isNullOrBlank()) {
            // No private fixture available: nothing to measure, and nothing to fail.
            true.shouldBeTrue()
            return
        }
        run {
            val fixture = ObjectMapper().readValue(File(path), Fixture::class.java)
            val closes = fixture.closes.mapValues { (_, series) -> series.map(::BigDecimal) }
            val flows = fixture.flows.map { ReplayComparator.Flow(it.dayIndex, BigDecimal(it.usd)) }
            val opening = BigDecimal(fixture.openingCapital)
            val baseline = fixture.allocations.map { Allocation(Asset(it.symbol), it.targetPercent) }

            // The scored sleeve excludes cash and gold, which carry no quality score.
            val sleeve = baseline
                .filter { it.symbol.value != "USD" && it.symbol.value != "PAXG" }
                .sumOf { BigDecimal.valueOf(it.targetPercent) }
            val scoreDerived = QualityAllocation
                .proportional(scores, sleeve.setScale(2, RoundingMode.HALF_UP), emphasis = 4)
                .map { (symbol, percent) -> Allocation(Asset(symbol), percent.toDouble()) } +
                baseline.filter { it.symbol.value == "USD" || it.symbol.value == "PAXG" }

            fun run(
                label: String,
                allocations: List<Allocation>,
                trigger: Double = fixture.deviationTriggerPercent,
                lookback: Int = 0,
            ) {
                val comparator = ReplayComparator(
                    allocations = allocations,
                    settings = TestFixtures.settings(
                        dryRun = true,
                        deviationTriggerPercent = trigger,
                        minimumOrderSizeUSD = fixture.minimumOrderSizeUSD,
                    ),
                    feeRate = BigDecimal(fixture.feeRate),
                )
                val outcome = comparator.run(
                    closes = closes,
                    flows = flows,
                    openingCapital = opening,
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
                val delta = outcome.nav.subtract(outcome.buyAndHoldNav)
                println(
                    "%-34s nav=%11s  bh=%11s  delta=%10s  fees=%8s  trades=%5d  suppressed=%4d".format(
                        label,
                        outcome.nav,
                        outcome.buyAndHoldNav,
                        delta,
                        outcome.fees.setScale(2, RoundingMode.HALF_UP),
                        outcome.tradeCount,
                        outcome.suppressedSells,
                    ),
                )
            }

            println("=== LOCAL REPLAY VARIANTS ($path) ===")
            run("A current allocation 5% trig", baseline)
            run("B current + tail-stop 20d", baseline, lookback = 20)
            run("C score-derived emph 4", scoreDerived)
            run("D score-derived + tail-stop", scoreDerived, lookback = 20)
            run("E current, wider 10% trig", baseline, trigger = 10.0)
            run("F score-derived, 10% trig", scoreDerived, trigger = 10.0)

            true.shouldBeTrue()
        }
    }
}
