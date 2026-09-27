package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.config.appModule
import com.gemini.krakenbot.model.ComparisonAvailability
import com.gemini.krakenbot.model.RebalancerComparison
import io.kotest.core.spec.style.StringSpec
import org.koin.core.context.loadKoinModules
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.test.KoinTest
import org.koin.test.get
import java.math.BigDecimal
import java.time.Instant
import kotlin.time.Duration.Companion.minutes

/**
 * Historical-inference sensitivity analysis.
 *
 * Exact historical configuration changes were never retained, so the shipping benchmark *infers*
 * major regime changes. This harness prices several alternative interpretations of that same
 * unavailable history so the uncertainty is disclosed rather than hidden. It exists only to measure
 * sensitivity: no variant here is a shipping benchmark, and none of them may change the generic
 * algorithm unless a concrete correctness defect is proven.
 *
 * Every variant runs through the same accounting code with the same evidence, so a difference in
 * the result is attributable to configuration history alone. The forensic path deliberately bypasses
 * the comparison cache, so no variant can be served from or persisted into product cache state.
 */
class ConfigurationInferenceSensitivityTest :
    StringSpec(),
    KoinTest {

    private fun at(text: String): Instant = Instant.parse(text)

    /** Regime changes as the generic algorithm infers them. */
    private fun genericRegimes() = listOf(
        transition(
            "2025-12-10T11:46:27.736Z",
            setOf("MORPHO"),
            setOf("BTC", "ETH", "INJ", "LINK", "RENDER", "SEI", "SOL", "TRX", "XMR", "XRP"),
        ),
        transition("2026-01-22T15:05:04.373Z", emptySet(), setOf("HBAR", "PAXG")),
        transition("2026-03-12T01:11:33.021Z", setOf("SEI"), emptySet()),
        transition("2026-05-06T23:04:06.210Z", setOf("HBAR", "XMR"), setOf("AVAX")),
    )

    /** Regime changes as the earlier hand-derived interpretation understood them. */
    private fun handDerivedRegimes() = listOf(
        transition("2025-12-05T17:41:08.855Z", setOf("MORPHO"), setOf("BTC", "ETH")),
        transition("2025-12-12T23:55:51.540Z", emptySet(), setOf("INJ", "LINK", "RENDER", "SOL", "TRX", "XRP")),
        transition("2026-01-16T15:01:42.822Z", setOf("EIGEN"), setOf("HBAR")),
        transition("2026-01-22T15:05:04.373Z", emptySet(), setOf("PAXG")),
        transition("2026-05-06T23:04:06.210Z", setOf("HBAR", "XMR"), setOf("AVAX")),
    )

    private fun transition(end: String, removals: Set<String>, additions: Set<String>) = InferredRegimeTransition(
        clusterEnd = at(end),
        removals = removals,
        additions = additions,
        confidence = RegimeTransitionConfidence.HIGH,
    )

    private val genericDecember = transition(
        "2025-12-10T11:46:27.736Z",
        setOf("MORPHO"),
        setOf("BTC", "ETH", "INJ", "LINK", "RENDER", "SEI", "SOL", "TRX", "XMR", "XRP"),
    )
    private val genericJanuary = transition("2026-01-22T15:05:04.373Z", emptySet(), setOf("HBAR", "PAXG"))
    private val genericSei = transition("2026-03-12T01:11:33.021Z", setOf("SEI"), emptySet())
    private val genericMay = transition("2026-05-06T23:04:06.210Z", setOf("HBAR", "XMR"), setOf("AVAX"))

    init {
        "inference sensitivity prices every configuration interpretation"
            .config(timeout = 90.minutes, invocationTimeout = 90.minutes) {
                val dbPath = System.getenv(ACCEPTANCE_DB_PATH_ENV) ?: run {
                    println("Sensitivity analysis skipped: $ACCEPTANCE_DB_PATH_ENV is not set")
                    return@config
                }
                val previousDbPath = System.getProperty("kraken.db.path")
                System.setProperty("kraken.db.path", dbPath)
                try {
                    stopKoin()
                    startKoin { loadKoinModules(appModule) }
                    val query = get<TradeHistoryQueryService>()
                    val requestEnd = Instant.now()

                    val variants = linkedMapOf(
                        "A0" to genericRegimes(),
                        "A1_decemberSplit" to listOf(
                            transition("2025-12-05T17:41:08.855Z", setOf("MORPHO"), setOf("BTC", "ETH")),
                            transition(
                                "2025-12-12T23:55:51.540Z",
                                emptySet(),
                                setOf("INJ", "LINK", "RENDER", "SOL", "TRX", "XRP"),
                            ),
                            genericJanuary,
                            genericSei,
                            genericMay,
                        ),
                        "A2_eigenRemoval" to listOf(
                            genericDecember,
                            transition("2026-01-22T15:05:04.373Z", setOf("EIGEN"), setOf("HBAR", "PAXG")),
                            genericSei,
                            genericMay,
                        ),
                        "A3_noSeiEpoch" to listOf(genericDecember, genericJanuary, genericMay),
                        "A4_januarySplit" to listOf(
                            genericDecember,
                            transition("2026-01-16T15:01:42.822Z", setOf("EIGEN"), setOf("HBAR")),
                            transition("2026-01-22T15:05:04.373Z", emptySet(), setOf("PAXG")),
                            genericSei,
                            genericMay,
                        ),
                        "B_handDerived" to handDerivedRegimes(),
                    )

                    val results = linkedMapOf<String, RebalancerComparison>()
                    for ((label, regimes) in variants) {
                        val started = System.nanoTime()
                        val comparison = query.getForensicRebalancerComparison(
                            Instant.EPOCH,
                            requestEnd,
                            regimes,
                        )
                        val elapsedMs = (System.nanoTime() - started) / 1_000_000
                        results[label] = comparison
                        val last = comparison.points.lastOrNull()
                        println(
                            "VARIANT $label availability=${comparison.availability} " +
                                "reason=${comparison.unavailableReason} at=${comparison.unavailableAt} " +
                                "points=${comparison.points.size} elapsedMs=$elapsedMs",
                        )
                        if (last != null) {
                            println(
                                "VARIANT $label finalActual=${last.rebalancerValueUSD} " +
                                    "finalBenchmark=${last.buyAndHoldValueUSD} " +
                                    "difference=${last.differenceUSD} percent=${last.differencePercent}",
                            )
                        }
                    }

                    println("=== SENSITIVITY SUMMARY (final benchmark NAV) ===")
                    fun nav(label: String) = results[label]?.points?.lastOrNull()?.buyAndHoldValueUSD
                    fun diff(label: String) = results[label]?.points?.lastOrNull()?.differenceUSD
                    val a0 = nav("A0")
                    results.keys.forEach { label ->
                        val value = nav(label)
                        println(
                            "label=$label benchmark=$value actualVsBenchmark=${diff(label)}" +
                                (
                                    if (label != "A0" && a0 != null && value != null) {
                                        " deltaVsA0=${value.subtract(a0)}"
                                    } else {
                                        ""
                                    }
                                    ),
                        )
                    }
                    val individual = listOf("A1_decemberSplit", "A2_eigenRemoval", "A3_noSeiEpoch", "A4_januarySplit")
                        .mapNotNull { label -> nav(label)?.let { it.subtract(a0 ?: return@mapNotNull null) } }
                    val combined = nav("B_handDerived")?.let { it.subtract(a0 ?: return@let null) }
                    if (individual.isNotEmpty() && combined != null) {
                        val sum = individual.fold(BigDecimal.ZERO) { acc, d -> acc.add(d) }
                        println(
                            "sumOfIndividualDeltas=$sum combinedBAminusA=$combined " +
                                "interactionResidual=${combined.subtract(sum)}",
                        )
                    }
                    check(results.values.all { it.availability == ComparisonAvailability.AVAILABLE }) {
                        "A sensitivity variant was unavailable: " +
                            results.entries.filter { it.value.availability != ComparisonAvailability.AVAILABLE }
                                .map { it.key }
                    }
                } finally {
                    stopKoin()
                    if (previousDbPath != null) {
                        System.setProperty("kraken.db.path", previousDbPath)
                    } else {
                        System.clearProperty("kraken.db.path")
                    }
                }
            }
    }

    private companion object {
        const val ACCEPTANCE_DB_PATH_ENV = "ACCEPTANCE_DB_PATH"
    }
}
