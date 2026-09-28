package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.config.appModule
import com.gemini.krakenbot.model.BenchmarkMethod
import com.gemini.krakenbot.model.ComparisonAvailability
import io.kotest.core.spec.style.StringSpec
import org.koin.core.context.loadKoinModules
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.test.KoinTest
import org.koin.test.get
import java.time.Duration
import java.time.Instant
import kotlin.time.Duration.Companion.minutes

/**
 * Production acceptance replay against a disposable copy of the retained account database.
 *
 * Opt-in only: it runs a full lifetime reconstruction and live historical-price resolution, so it
 * must never execute as part of an ordinary test run or against the real account database. Point
 * [ACCEPTANCE_DB_PATH] at a fresh disposable copy to enable it.
 *
 * Read-only with respect to the real source database: nothing here opens it, and the service only
 * ever receives the disposable path.
 */
class ConfigurationMatchedHoldAcceptanceTest :
    StringSpec(),
    KoinTest {

    init {
        "shipping configuration-matched hold replays the lifetime economics from a disposable copy"
            .config(timeout = 40.minutes, invocationTimeout = 40.minutes) {
                val dbPath = System.getenv(ACCEPTANCE_DB_PATH_ENV) ?: run {
                    println("Acceptance replay skipped: $ACCEPTANCE_DB_PATH_ENV is not set")
                    return@config
                }
                val previousDbPath = System.getProperty("kraken.db.path")
                System.setProperty("kraken.db.path", dbPath)
                try {
                    stopKoin()
                    startKoin { loadKoinModules(appModule) }
                    val query = get<TradeHistoryQueryService>()
                    val requestEnd = Instant.now()

                    val coldStart = System.nanoTime()
                    val inferred = query.getRebalancerComparison(
                        Instant.EPOCH,
                        requestEnd,
                        BenchmarkMethod.INFERRED_CONFIGURATION_MATCHED_HOLD,
                    )
                    val coldMs = (System.nanoTime() - coldStart) / 1_000_000

                    println("=== A: CONFIGURATION-MATCHED HOLD (INFERRED), SHIPPING PATH ===")
                    println("availability=${inferred.availability} confidence=${inferred.confidence}")
                    println("benchmarkMethod=${inferred.benchmarkMethod}")
                    println("configurationEvidence=${inferred.configurationEvidence}")
                    println("unavailableReason=${inferred.unavailableReason} at=${inferred.unavailableAt}")
                    println("baselineTimestamp=${inferred.baselineTimestamp}")
                    println("points=${inferred.points.size} coldWallClockMs=$coldMs")
                    inferred.points.lastOrNull()?.let { last ->
                        println("finalTimestamp=${last.timestamp}")
                        println("finalActual=${last.rebalancerValueUSD}")
                        println("finalInferredHold=${last.buyAndHoldValueUSD}")
                        println("finalDifference=${last.differenceUSD}")
                        println("finalDifferencePercent=${last.differencePercent}")
                    }

                    check(inferred.availability == ComparisonAvailability.AVAILABLE) {
                        "Shipping inferred comparison unavailable: ${inferred.unavailableReason} at ${inferred.unavailableAt}"
                    }
                    check(
                        inferred.configurationEvidence ==
                            com.gemini.krakenbot.model.ConfigurationEvidence.INFERRED,
                    )
                    check(inferred.benchmarkMethod == BenchmarkMethod.INFERRED_CONFIGURATION_MATCHED_HOLD)

                    // Economics are deliberately NOT pinned here. This benchmark infers configuration
                    // from observed portfolio behaviour, which the configuration-journal workstream
                    // is replacing: the inception baseline on real history is pre-funding and nearly
                    // all cash, so the inferred additions have no evidence-backed funding source and
                    // the result degenerates to a cash hold. A pinned figure would certify a
                    // definition known to be wrong, and a green test is not a reason to keep it.

                    val warmStart = System.nanoTime()
                    val warm = query.getRebalancerComparison(
                        Instant.EPOCH,
                        requestEnd,
                        BenchmarkMethod.INFERRED_CONFIGURATION_MATCHED_HOLD,
                    )
                    val warmMs = (System.nanoTime() - warmStart) / 1_000_000
                    println("=== A: CACHE REUSE (COLD -> WARM, SAME METHOD) ===")
                    println("warmPoints=${warm.points.size} warmWallClockMs=$warmMs")
                    check(
                        warm.points.zip(inferred.points).all { (a, b) ->
                            a.timestamp == b.timestamp &&
                                a.rebalancerValueUSD.compareTo(b.rebalancerValueUSD) == 0 &&
                                a.buyAndHoldValueUSD.compareTo(b.buyAndHoldValueUSD) == 0 &&
                                a.differenceUSD.compareTo(b.differenceUSD) == 0
                        },
                    ) { "Warm cache result diverged from the cold result" }
                    println("cacheReuseIdentical=true")

                    val fixed = query.getRebalancerComparison(
                        Instant.EPOCH,
                        requestEnd,
                        BenchmarkMethod.FIXED_INCEPTION_HOLD,
                    )
                    println("=== A: FIXED-INCEPTION HOLD (FORENSIC REFERENCE) ===")
                    println("availability=${fixed.availability} points=${fixed.points.size}")
                    fixed.points.lastOrNull()?.let { last ->
                        println("fixedFinalActual=${last.rebalancerValueUSD}")
                        println("fixedFinalBenchmark=${last.buyAndHoldValueUSD}")
                        println("fixedFinalDifference=${last.differenceUSD}")
                    }

                    val slice = query.getRebalancerComparison(
                        requestEnd.minus(Duration.ofDays(30)),
                        requestEnd,
                        BenchmarkMethod.INFERRED_CONFIGURATION_MATCHED_HOLD,
                    )
                    println("=== A: 30d SLICE ===")
                    println("availability=${slice.availability} points=${slice.points.size}")
                    val overlap = slice.points.filter { p -> inferred.points.any { it.timestamp == p.timestamp } }
                    val mismatches = overlap.count { p ->
                        val full = inferred.points.first { it.timestamp == p.timestamp }
                        full.rebalancerValueUSD.compareTo(p.rebalancerValueUSD) != 0 ||
                            full.buyAndHoldValueUSD.compareTo(p.buyAndHoldValueUSD) != 0 ||
                            full.differenceUSD.compareTo(p.differenceUSD) != 0
                    }
                    println("overlapCount=${overlap.size} mismatchCount=$mismatches")
                    check(slice.availability == ComparisonAvailability.AVAILABLE)
                    check(mismatches == 0) { "30d slice disagreed with lifetime economics on $mismatches points" }

                    println("=== A: CACHE ROWS WRITTEN BY SHIPPING PATH ===")
                    printCacheState(dbPath)
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

    private fun printCacheState(dbPath: String) {
        val uri = "file:" + dbPath.replace(" ", "%20") + "?mode=ro"
        java.sql.DriverManager.getConnection("jdbc:sqlite:$uri").use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT COUNT(*) FROM rebalancer_comparison_cache").use { rs ->
                    if (rs.next()) println("comparisonCacheRows=${rs.getInt(1)}")
                }
            }
        }
    }

    private companion object {
        const val ACCEPTANCE_DB_PATH_ENV = "ACCEPTANCE_DB_PATH"
    }
}
