package com.gemini.krakenbot

import com.gemini.krakenbot.config.DataDirectoryConfig
import com.gemini.krakenbot.config.appModule
import com.gemini.krakenbot.controller.DashboardController
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.CurrentPortfolioState
import com.gemini.krakenbot.service.OrderIntentService
import com.gemini.krakenbot.service.PortfolioAnalyzer
import com.gemini.krakenbot.service.PortfolioManager
import com.gemini.krakenbot.service.impl.PortfolioAnalyzerImpl
import com.gemini.krakenbot.service.impl.PortfolioManagerImpl
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import org.koin.test.inject
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("unused")
class KrakenRebalancerApplicationTest :
    StringSpec(),
    KoinTest {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "verify koin modules" {
            val previousDataDir = System.getProperty(DataDirectoryConfig.DATA_DIR_PROPERTY)
            val tempDir = Files.createTempDirectory("rebalancer-app-test-")
            DataDirectoryConfig.initializeDataDirectory(tempDir)
            System.setProperty(DataDirectoryConfig.DATA_DIR_PROPERTY, tempDir.toString())
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
                val analyzer: PortfolioAnalyzer by inject()
                analyzer.shouldBeInstanceOf<PortfolioAnalyzerImpl>()
                val currentState: CurrentPortfolioState by inject()
                currentState.snapshot.value shouldBe null
                val controller: DashboardController by inject()
                controller.shouldBeInstanceOf<DashboardController>()
            } finally {
                stopKoin()
                if (previousDataDir != null) {
                    System.setProperty(
                        DataDirectoryConfig.DATA_DIR_PROPERTY,
                        previousDataDir,
                    )
                } else {
                    System.clearProperty(DataDirectoryConfig.DATA_DIR_PROPERTY)
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

        "trading starts after server readiness and shutdown drains before dependencies close" {
            val events = mutableListOf<String>()
            val worker = Job().apply { complete() }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val manager = mockk<PortfolioManager>()
            every { manager.startRebalancingLoop(scope) } answers {
                events += "worker-start"
                worker
            }
            every { manager.stopRebalancingLoop() } answers {
                events += "worker-stop"
                worker
            }
            val client = mockk<HttpClient>()
            every { client.close() } answers {
                events += "client-close"
            }
            val lifecycle =
                KrakenRebalancerApplicationLifecycle(
                    portfolioManager = manager,
                    orderIntentService = mockk<OrderIntentService>(),
                    applicationScope = scope,
                    httpClient = client,
                    stopContainer = { events += "container-stop" },
                )
            val server = embeddedServer(Netty, port = 0, host = "127.0.0.1") {}
            server.monitor.subscribe(ApplicationStopped) { events += "server-stopped" }
            lifecycle.attachServer(server)

            server.start(wait = false)
            events shouldBe listOf("worker-start")

            lifecycle.shutdown()
            lifecycle.shutdown()

            events shouldBe listOf("worker-start", "server-stopped", "worker-stop", "client-close", "container-stop")
            verify(exactly = 1) { manager.startRebalancingLoop(scope) }
            verify(exactly = 1) { manager.stopRebalancingLoop() }
        }

        "concurrent shutdown caller waits for cleanup to complete" {
            val events = mutableListOf<String>()
            val stopStarted = CountDownLatch(1)
            val releaseStop = CountDownLatch(1)
            val worker = Job().apply { complete() }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val manager = mockk<PortfolioManager>()
            every { manager.stopRebalancingLoop() } answers {
                events += "worker-stop"
                stopStarted.countDown()
                releaseStop.await()
                worker
            }
            val client = mockk<HttpClient>()
            every { client.close() } answers {
                events += "client-close"
            }
            val lifecycle =
                KrakenRebalancerApplicationLifecycle(
                    portfolioManager = manager,
                    orderIntentService = mockk<OrderIntentService>(),
                    applicationScope = scope,
                    httpClient = client,
                    stopContainer = { events += "container-stop" },
                )
            val firstShutdown = CompletableFuture.runAsync(lifecycle::shutdown)
            val secondThread = AtomicReference<Thread>()
            var secondShutdown: CompletableFuture<Void>? = null
            try {
                stopStarted.await(5, TimeUnit.SECONDS) shouldBe true
                val secondStarted = CountDownLatch(1)
                secondShutdown =
                    CompletableFuture.runAsync {
                        secondThread.set(Thread.currentThread())
                        secondStarted.countDown()
                        lifecycle.shutdown()
                    }
                secondStarted.await(5, TimeUnit.SECONDS) shouldBe true
                awaitWaitingForShutdown(checkNotNull(secondThread.get())) shouldBe true
            } finally {
                releaseStop.countDown()
            }
            firstShutdown.join()
            checkNotNull(secondShutdown).join()

            events shouldBe listOf("worker-stop", "client-close", "container-stop")
            verify(exactly = 1) { manager.stopRebalancingLoop() }
        }

        "worker stop failure releases dependencies and reaches concurrent and repeated shutdown callers" {
            val events = mutableListOf<String>()
            val stopFailure = IllegalStateException("worker stop failed")
            val stopStarted = CountDownLatch(1)
            val releaseStop = CountDownLatch(1)
            val manager = mockk<PortfolioManager>()
            every { manager.stopRebalancingLoop() } answers {
                events += "worker-stop"
                stopStarted.countDown()
                releaseStop.await()
                throw stopFailure
            }
            val client = mockk<HttpClient>()
            every { client.close() } answers { events += "client-close" }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val lifecycle =
                KrakenRebalancerApplicationLifecycle(
                    portfolioManager = manager,
                    orderIntentService = mockk<OrderIntentService>(),
                    applicationScope = scope,
                    httpClient = client,
                    stopContainer = { events += "container-stop" },
                )
            val firstShutdown = CompletableFuture.runAsync(lifecycle::shutdown)
            val secondThread = AtomicReference<Thread>()
            var secondShutdown: CompletableFuture<Void>? = null
            try {
                stopStarted.await(5, TimeUnit.SECONDS) shouldBe true
                val secondStarted = CountDownLatch(1)
                secondShutdown =
                    CompletableFuture.runAsync {
                        secondThread.set(Thread.currentThread())
                        secondStarted.countDown()
                        lifecycle.shutdown()
                    }
                secondStarted.await(5, TimeUnit.SECONDS) shouldBe true
                awaitWaitingForShutdown(checkNotNull(secondThread.get())) shouldBe true
            } finally {
                releaseStop.countDown()
            }

            shouldThrow<CompletionException> { firstShutdown.join() }.cause shouldBe stopFailure
            shouldThrow<CompletionException> { checkNotNull(secondShutdown).join() }.cause
                .shouldBe(stopFailure)
            shouldThrow<CompletionException> { lifecycle.shutdown() }.cause shouldBe stopFailure

            events shouldBe listOf("worker-stop", "client-close", "container-stop")
            scope.coroutineContext[Job]?.isCancelled shouldBe true
            verify(exactly = 1) { manager.stopRebalancingLoop() }
            verify(exactly = 1) { client.close() }
        }

        "worker stop failure drains the application scope before closing dependencies" {
            val cleanupStarted = CountDownLatch(1)
            val releaseCleanup = CompletableDeferred<Unit>()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val worker = scope.launch {
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        cleanupStarted.countDown()
                        releaseCleanup.await()
                    }
                }
            }
            val stopFailure = IllegalStateException("worker stop failed")
            val manager = mockk<PortfolioManager>()
            every { manager.stopRebalancingLoop() } throws stopFailure
            val client = mockk<HttpClient>()
            every { client.close() } answers { worker.isCompleted shouldBe true }
            val lifecycle = KrakenRebalancerApplicationLifecycle(
                portfolioManager = manager,
                orderIntentService = mockk<OrderIntentService>(),
                applicationScope = scope,
                httpClient = client,
                stopContainer = { worker.isCompleted shouldBe true },
            )
            val shutdown = CompletableFuture.runAsync { lifecycle.shutdown() }
            try {
                cleanupStarted.await(5, TimeUnit.SECONDS) shouldBe true
                shutdown.isDone shouldBe false
                verify(exactly = 0) { client.close() }
                releaseCleanup.complete(Unit)
                shouldThrow<CompletionException> { shutdown.orTimeout(5, TimeUnit.SECONDS).join() }.cause shouldBe
                    stopFailure
                verify(exactly = 1) { client.close() }
            } finally {
                releaseCleanup.complete(Unit)
                scope.cancel()
            }
        }

        "interrupted shutdown drains journal cleanup before releasing dependencies and preserves all failures" {
            val events = mutableListOf<String>()
            val clientFailure = IllegalStateException("client close failed")
            val containerFailure = IllegalStateException("container stop failed")
            val cleanupStarted = CountDownLatch(1)
            val releaseCleanup = CompletableDeferred<Unit>()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val worker = scope.launch {
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        cleanupStarted.countDown()
                        releaseCleanup.await()
                        events += "journal-persisted"
                    }
                }
            }
            val manager = mockk<PortfolioManager>()
            every { manager.stopRebalancingLoop() } answers {
                worker.cancel()
                worker
            }
            val client = mockk<HttpClient>()
            every { client.close() } answers {
                worker.isCompleted shouldBe true
                scope.coroutineContext[Job]?.isCancelled shouldBe true
                events += "client-close"
                throw clientFailure
            }
            val lifecycle = KrakenRebalancerApplicationLifecycle(
                portfolioManager = manager,
                orderIntentService = mockk<OrderIntentService>(),
                applicationScope = scope,
                httpClient = client,
                stopContainer = {
                    events += "container-stop"
                    throw containerFailure
                },
            )

            val shutdown = CompletableFuture<Unit>()
            val repeatedFailure = AtomicReference<Throwable>()
            val interruptRestored = AtomicBoolean()
            val owner = Thread(
                {
                    try {
                        lifecycle.shutdown()
                        shutdown.complete(Unit)
                    } catch (failure: Throwable) {
                        repeatedFailure.set(runCatching { lifecycle.shutdown() }.exceptionOrNull())
                        interruptRestored.set(Thread.currentThread().isInterrupted)
                        shutdown.completeExceptionally(failure)
                    }
                },
                "interrupted-shutdown-test",
            )
            val concurrentThread = AtomicReference<Thread>()
            val concurrentStarted = CountDownLatch(1)
            owner.start()
            try {
                cleanupStarted.await(5, TimeUnit.SECONDS) shouldBe true
                val concurrentShutdown = CompletableFuture.runAsync {
                    concurrentThread.set(Thread.currentThread())
                    concurrentStarted.countDown()
                    lifecycle.shutdown()
                }
                concurrentStarted.await(5, TimeUnit.SECONDS) shouldBe true
                awaitWaitingForShutdown(checkNotNull(concurrentThread.get())) shouldBe true
                owner.interrupt()
                awaitWaitingForShutdown(owner) shouldBe true
                shutdown.isDone shouldBe false
                concurrentShutdown.isDone shouldBe false
                scope.coroutineContext[Job]?.isActive shouldBe true
                events shouldBe emptyList()
                releaseCleanup.complete(Unit)

                val failure = shouldThrow<CompletionException> {
                    shutdown.orTimeout(5, TimeUnit.SECONDS).join()
                }.cause.shouldBeInstanceOf<InterruptedException>()
                shouldThrow<CompletionException> {
                    concurrentShutdown.orTimeout(5, TimeUnit.SECONDS).join()
                }.cause shouldBe failure
                repeatedFailure.get().shouldBeInstanceOf<CompletionException>().cause shouldBe failure
                shouldThrow<CompletionException> { lifecycle.shutdown() }.cause shouldBe failure
                interruptRestored.get() shouldBe true

                failure.suppressed.toList() shouldBe listOf(clientFailure, containerFailure)
                events shouldBe listOf("journal-persisted", "client-close", "container-stop")
                verify(exactly = 1) { manager.stopRebalancingLoop() }
            } finally {
                releaseCleanup.complete(Unit)
                owner.join(5_000)
                scope.coroutineContext[Job]?.cancel()
            }
        }

        "journal diagnostic failure drains the worker before cleanup and remains visible on repeated shutdown" {
            val events = mutableListOf<String>()
            val diagnosticFailure = IllegalStateException("journal check failed")
            val diagnosticStarted = CountDownLatch(1)
            val releaseWorker = CompletableDeferred<Unit>()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val worker = scope.launch {
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        releaseWorker.await()
                        events += "worker-drained"
                    }
                }
            }
            val manager = mockk<PortfolioManager>()
            every { manager.stopRebalancingLoop() } answers {
                worker.cancel()
                worker
            }
            val intents = mockk<OrderIntentService>()
            coEvery { intents.hasUnresolvedIntents() } answers {
                scope.coroutineContext[Job]?.isActive shouldBe true
                events += "journal-check"
                diagnosticStarted.countDown()
                throw diagnosticFailure
            }
            val client = mockk<HttpClient>()
            every { client.close() } answers {
                worker.isCompleted shouldBe true
                scope.coroutineContext[Job]?.isCancelled shouldBe true
                events += "client-close"
            }
            val lifecycle = KrakenRebalancerApplicationLifecycle(
                portfolioManager = manager,
                orderIntentService = intents,
                applicationScope = scope,
                httpClient = client,
                stopContainer = { events += "container-stop" },
            )

            val shutdownThread = AtomicReference<Thread>()
            val shutdown = CompletableFuture.runAsync {
                shutdownThread.set(Thread.currentThread())
                lifecycle.shutdown()
            }
            try {
                diagnosticStarted.await(10, TimeUnit.SECONDS) shouldBe true
                awaitWaitingForShutdown(checkNotNull(shutdownThread.get())) shouldBe true
                shutdown.isDone shouldBe false
                scope.coroutineContext[Job]?.isActive shouldBe true
                events shouldBe listOf("journal-check")
                releaseWorker.complete(Unit)

                shouldThrow<CompletionException> { shutdown.orTimeout(5, TimeUnit.SECONDS).join() }.cause
                    .shouldBe(diagnosticFailure)
                shouldThrow<CompletionException> { lifecycle.shutdown() }.cause shouldBe diagnosticFailure

                events shouldBe listOf("journal-check", "worker-drained", "client-close", "container-stop")
                verify(exactly = 1) { manager.stopRebalancingLoop() }
            } finally {
                releaseWorker.complete(Unit)
                scope.coroutineContext[Job]?.cancel()
            }
        }

        "failed server bind does not start trading and still releases dependencies" {
            val events = mutableListOf<String>()
            val worker = Job().apply { complete() }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val manager = mockk<PortfolioManager>()
            every { manager.startRebalancingLoop(scope) } answers {
                events += "worker-start"
                worker
            }
            every { manager.stopRebalancingLoop() } answers {
                events += "worker-stop"
                worker
            }
            val client = mockk<HttpClient>()
            every { client.close() } answers {
                events += "client-close"
            }
            val lifecycle =
                KrakenRebalancerApplicationLifecycle(
                    portfolioManager = manager,
                    orderIntentService = mockk<OrderIntentService>(),
                    applicationScope = scope,
                    httpClient = client,
                    stopContainer = { events += "container-stop" },
                )

            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { occupiedPort ->
                val server = embeddedServer(Netty, port = occupiedPort.localPort, host = "127.0.0.1") {}
                lifecycle.attachServer(server)

                shouldThrow<Exception> { server.start(wait = false) }
                lifecycle.shutdown()
            }

            events shouldBe listOf("worker-stop", "client-close", "container-stop")
            verify(exactly = 0) { manager.startRebalancingLoop(any()) }
            verify(exactly = 1) { manager.stopRebalancingLoop() }
        }
    }
}

private fun awaitWaitingForShutdown(thread: Thread): Boolean {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (System.nanoTime() < deadline) {
        if (thread.state == Thread.State.WAITING || thread.state == Thread.State.TIMED_WAITING) return true
        if (!thread.isAlive) return false
        Thread.yield()
    }
    return false
}
