package com.gemini.krakenbot

import com.gemini.krakenbot.config.appModule
import com.gemini.krakenbot.model.FundingProvenanceResolver
import com.gemini.krakenbot.service.PortfolioAnalyzer
import com.gemini.krakenbot.service.PortfolioManager
import com.gemini.krakenbot.service.impl.DynamicKrakenService
import com.gemini.krakenbot.service.impl.KrakenFundingProvenanceResolver
import com.gemini.krakenbot.service.impl.PortfolioAnalyzerImpl
import com.gemini.krakenbot.service.impl.PortfolioManagerImpl
import com.gemini.krakenbot.service.impl.history.TradeHistoryQueryService
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.test.KoinTest
import org.koin.test.inject
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("unused")
class KrakenRebalancerApplicationTest :
    StringSpec(),
    KoinTest {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "verify koin modules" {
            // appModule builds ConfigServiceImpl against this relative path, so the graph only
            // resolves if a config exists in the working directory. Synthesize a throwaway one when
            // the developer has none, and never delete a real config that was already there.
            val configFile = File("rebalancer-config.json")
            val existed = configFile.exists()
            if (!existed) {
                configFile.writeText(
                    """
                    {
                      "kraken": { "apiKey": "k", "privateKey": "s" },
                      "settings": { "loopDelaySeconds": 60, "deviationTriggerPercent": 2.0, "minimumOrderSizeUSD": 5.0, "dryRun": true, "fiatMaxDrawdown": 0.0, "fiatDeploymentExponent": 1.0 },
                      "allocations": [ { "symbol": "USD", "targetPercent": 100.0 } ]
                    }
                    """.trimIndent(),
                )
            }

            try {
                // Koin's context is global: another spec may have left one running.
                stopKoin()
                startKoin {
                    modules(appModule)
                }
                val pm: PortfolioManager by inject()
                pm.shouldBeInstanceOf<PortfolioManagerImpl>()
                val krakenField = PortfolioManagerImpl::class.java.getDeclaredField("krakenService")
                krakenField.isAccessible = true
                krakenField.get(pm).shouldBeInstanceOf<DynamicKrakenService>()
                val resolver: FundingProvenanceResolver by inject()
                resolver.shouldBeInstanceOf<KrakenFundingProvenanceResolver>()
                val analyzer: PortfolioAnalyzer by inject()
                val analyzerField = PortfolioAnalyzerImpl::class.java
                    .getDeclaredField("defaultProvenanceResolver")
                    .also { it.isAccessible = true }
                analyzerField.get(analyzer) shouldBe resolver
                val query: TradeHistoryQueryService by inject()
                val queryField = TradeHistoryQueryService::class.java
                    .getDeclaredField("fundingProvenanceResolver")
                    .also { it.isAccessible = true }
                queryField.get(query) shouldBe resolver
            } finally {
                stopKoin()
                if (!existed) {
                    configFile.delete()
                }
            }
        }

        "shutdown join extends the wait while live submissions are pending" {
            runTest {
                var pending = true
                val releasePending = CompletableDeferred<Unit>()
                val worker = Job()
                val cleanup = launch {
                    withContext(NonCancellable) {
                        releasePending.await()
                        worker.complete()
                    }
                }

                val joiner = launch {
                    // The first 5 s budget elapses while the worker is still draining; pending
                    // submissions must escalate to an extended wait instead of returning false.
                    joinRebalancingWorker(worker) { pending } shouldBe true
                }
                runCurrent()
                joiner.isCompleted shouldBe false

                // Fire the shutdown timeout: with pending submissions still true, the joiner
                // must escalate into a wait on the worker instead of completing false.
                testScheduler.advanceTimeBy(5_001L)
                runCurrent()
                joiner.isCompleted shouldBe false

                // Allow the worker to finish; the extended wait resolves and the joiner returns true.
                pending = false
                releasePending.complete(Unit)
                runCurrent()
                joiner.join()
                cleanup.join()
            }
        }

        "shutdown join does not extend the wait when no submissions are pending" {
            runTest {
                var pending = false
                val releaseWorker = CompletableDeferred<Unit>()
                val worker = Job()
                val cleanup = launch {
                    withContext(NonCancellable) {
                        releaseWorker.await()
                        worker.complete()
                    }
                }

                val joiner = launch {
                    joinRebalancingWorker(worker) { pending } shouldBe false
                }
                runCurrent()
                joiner.isCompleted shouldBe false

                // Fire the shutdown timeout: no pending submissions, so the joiner returns false
                // and does not escalate into a wait on the worker.
                testScheduler.advanceTimeBy(5_001L)
                runCurrent()
                joiner.isCompleted shouldBe true

                releaseWorker.complete(Unit)
                runCurrent()
                cleanup.join()
            }
        }

        "shutdown join waits for worker cleanup before dependencies are released" {
            runTest {
                val cleanupStarted = CompletableDeferred<Unit>()
                val releaseCleanup = CompletableDeferred<Unit>()
                val cleanupFinished = CompletableDeferred<Unit>()
                val worker = Job()
                val cleanup = launch {
                    withContext(NonCancellable) {
                        cleanupStarted.complete(Unit)
                        releaseCleanup.await()
                        cleanupFinished.complete(Unit)
                        worker.complete()
                    }
                }

                cleanupStarted.await()
                var dependenciesReleased = false
                val joiner = launch {
                    joinRebalancingWorker(worker) shouldBe true
                    dependenciesReleased = true
                }
                runCurrent()

                dependenciesReleased shouldBe false
                cleanupFinished.isCompleted shouldBe false

                releaseCleanup.complete(Unit)
                runCurrent()
                joiner.join()
                cleanup.join()

                dependenciesReleased shouldBe true
                cleanupFinished.isCompleted shouldBe true
            }
        }
    }
}
