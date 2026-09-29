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
import java.time.Instant
import kotlin.time.Duration.Companion.minutes

/**
 * Isolated fixed-inception replay used to confirm the inferred benchmark did not perturb the
 * validated fixed-inception benchmark. Runs one method against a disposable copy.
 */
class FixedInceptionIsolationTest :
    StringSpec(),
    KoinTest {
    init {
        "fixed-inception benchmark replays independently of the inferred method"
            .config(timeout = 40.minutes, invocationTimeout = 40.minutes) {
                val dbPath = System.getenv("ACCEPTANCE_DB_PATH") ?: return@config
                val previous = System.getProperty("kraken.db.path")
                System.setProperty("kraken.db.path", dbPath)
                try {
                    stopKoin()
                    startKoin { loadKoinModules(appModule) }
                    val query = get<TradeHistoryQueryService>()
                    val end = Instant.now()
                    val method = System.getenv("ACCEPTANCE_METHOD")
                        ?.let { BenchmarkMethod.valueOf(it) }
                        ?: BenchmarkMethod.FIXED_INCEPTION_HOLD
                    val result = query.getRebalancerComparison(Instant.EPOCH, end, method)
                    val last = result.points.lastOrNull()
                    println(
                        "ISOLATION method=$method availability=${result.availability} " +
                            "reason=${result.unavailableReason} at=${result.unavailableAt} points=${result.points.size}",
                    )
                    if (last != null) {
                        println(
                            "ISOLATION method=$method finalActual=${last.rebalancerValueUSD} " +
                                "finalBenchmark=${last.buyAndHoldValueUSD} difference=${last.differenceUSD}",
                        )
                    }
                    check(result.availability == ComparisonAvailability.AVAILABLE)
                } finally {
                    stopKoin()
                    if (previous != null) {
                        System.setProperty("kraken.db.path", previous)
                    } else {
                        System.clearProperty("kraken.db.path")
                    }
                }
            }
    }
}
