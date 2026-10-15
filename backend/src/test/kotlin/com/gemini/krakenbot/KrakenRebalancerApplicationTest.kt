package com.gemini.krakenbot

import com.gemini.krakenbot.config.appModule
import com.gemini.krakenbot.controller.DashboardController
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.CurrentPortfolioState
import com.gemini.krakenbot.service.PortfolioManager
import com.gemini.krakenbot.service.impl.PortfolioAnalyzerImpl
import com.gemini.krakenbot.service.impl.PortfolioManagerImpl
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
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
import org.koin.dsl.module
import org.koin.test.KoinTest
import org.koin.test.inject

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("unused")
class KrakenRebalancerApplicationTest :
    StringSpec(),
    KoinTest {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "verify koin modules" {
            val previousDataDir = System.getProperty(com.gemini.krakenbot.config.DataDirectoryConfig.DATA_DIR_PROPERTY)
            val tempDir = java.nio.file.Files.createTempDirectory("rebalancer-app-test-")
            com.gemini.krakenbot.config.DataDirectoryConfig.initializeDataDirectory(tempDir)
            System.setProperty(com.gemini.krakenbot.config.DataDirectoryConfig.DATA_DIR_PROPERTY, tempDir.toString())
            try {
                stopKoin()
                val config = mockk<ConfigService>(relaxed = true)
                every { config.getConfig() } returns TestFixtures.DEFAULT_TEST_CONFIG
                startKoin {
                    allowOverride(true)
                    modules(
                        appModule,
                        module { single<ConfigService> { config } },
                    )
                }
                val pm: PortfolioManager by inject()
                pm.shouldBeInstanceOf<PortfolioManagerImpl>()
                val analyzer: com.gemini.krakenbot.service.PortfolioAnalyzer by inject()
                analyzer.shouldBeInstanceOf<PortfolioAnalyzerImpl>()
                val currentState: CurrentPortfolioState by inject()
                currentState.snapshot.value shouldBe null
                val controller: DashboardController by inject()
                controller.shouldBeInstanceOf<DashboardController>()
            } finally {
                stopKoin()
                if (previousDataDir != null) {
                    System.setProperty(
                        com.gemini.krakenbot.config.DataDirectoryConfig.DATA_DIR_PROPERTY,
                        previousDataDir,
                    )
                } else {
                    System.clearProperty(com.gemini.krakenbot.config.DataDirectoryConfig.DATA_DIR_PROPERTY)
                }
                tempDir.toFile().deleteRecursively()
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
