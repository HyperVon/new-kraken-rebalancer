package com.gemini.krakenbot

import com.fasterxml.jackson.databind.ObjectMapper
import com.gemini.krakenbot.config.APPLICATION_SCOPE_QUALIFIER
import com.gemini.krakenbot.config.DataDirectoryConfig
import com.gemini.krakenbot.config.ErrorHandlingConfig.configureErrorHandling
import com.gemini.krakenbot.config.ExecutionJournalBootstrap
import com.gemini.krakenbot.config.ServerConfig
import com.gemini.krakenbot.config.appModule
import com.gemini.krakenbot.config.configureCORS
import com.gemini.krakenbot.config.configureCachingAndConditionalHeaders
import com.gemini.krakenbot.config.configureCompression
import com.gemini.krakenbot.config.configureSerialization
import com.gemini.krakenbot.controller.dashboardRouting
import com.gemini.krakenbot.service.OrderIntentService
import com.gemini.krakenbot.service.PortfolioManager
import io.ktor.client.HttpClient
import io.ktor.server.application.ServerReady
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.sse.SSE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.qualifier.named
import org.koin.logger.slf4jLogger
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture
import kotlin.time.Duration.Companion.milliseconds

private const val INITIALIZE_DATA_FLAG = "--initialize-data"
private const val REBALANCING_SHUTDOWN_TIMEOUT_MILLIS = 5_000L

private val log = LoggerFactory.getLogger("KrakenRebalancerApplication")

internal suspend fun joinRebalancingWorker(
    workerJob: Job?,
    hasPendingSubmissions: suspend () -> Boolean = { false },
    hasUnresolvedOrderIntents: suspend () -> Boolean = { false },
): Boolean {
    val joinedWithinBudget = withTimeoutOrNull(REBALANCING_SHUTDOWN_TIMEOUT_MILLIS.milliseconds) {
        workerJob?.join()
        true
    } ?: false
    if (joinedWithinBudget) return true
    val submissionsPending = try {
        hasPendingSubmissions() || hasUnresolvedOrderIntents()
    } catch (failure: Throwable) {
        // A failed journal check cannot establish that releasing dependencies is safe.
        try {
            workerJob?.join()
        } catch (joinFailure: Throwable) {
            if (joinFailure !== failure) failure.addSuppressed(joinFailure)
        }
        throw failure
    }
    if (submissionsPending) {
        // A live submission (or its journal write) may still be in flight. Releasing the HTTP client
        // and Koin graph now could strand a PENDING/UNCERTAIN record, so extend the wait instead.
        log.warn(
            "Timed out waiting for the rebalance worker while submissions are pending; extending the shutdown wait to preserve the order journal.",
        )
        workerJob?.join()
        return true
    }
    return false
}

fun main(args: Array<String>) {
    if (INITIALIZE_DATA_FLAG in args) {
        DataDirectoryConfig.initializeDataDirectory()
        return
    }
    DataDirectoryConfig.ensureDataDirectory()
    startKoin {
        slf4jLogger()
        modules(appModule)
    }

    val koin = GlobalContext.get()
    val executionJournalBootstrap = koin.get<ExecutionJournalBootstrap>()
    val portfolioManager = koin.get<PortfolioManager>()
    val orderIntentService = koin.get<OrderIntentService>()
    val httpClient = koin.get<HttpClient>()
    val objectMapper = koin.get<ObjectMapper>()

    val applicationScope: CoroutineScope = koin.get(
        qualifier = named(APPLICATION_SCOPE_QUALIFIER),
    )
    val lifecycle =
        KrakenRebalancerApplicationLifecycle(
            portfolioManager = portfolioManager,
            orderIntentService = orderIntentService,
            applicationScope = applicationScope,
            httpClient = httpClient,
        )
    val shutdownHook = Thread(lifecycle::shutdown, "kraken-rebalancer-shutdown")
    var shutdownHookRegistered = false
    try {
        Runtime.getRuntime().addShutdownHook(shutdownHook)
        shutdownHookRegistered = true

        runBlocking { executionJournalBootstrap.initialize() }
        val server = createServer(objectMapper)
        lifecycle.attachServer(server)
        server.start(wait = true)
    } finally {
        lifecycle.shutdown()
        if (shutdownHookRegistered) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook)
            } catch (_: IllegalStateException) {
                // JVM shutdown is already running the registered hook.
            }
        }
    }
}

private fun createServer(objectMapper: ObjectMapper): EmbeddedServer<*, *> {
    // Bind the IPv6 wildcard. On dual-stack kernels (macOS/Windows default; Linux when
    // net.ipv6.bindv6only=0) the same socket also accepts IPv4-mapped clients. The IPv4
    // wildcard `0.0.0.0` refuses native IPv6, which breaks hostname clients that prefer AAAA.
    // IPv4 literals (e.g. http://10.0.0.x:8080/) never use AAAA — bind family does not affect them.
    // Hosts with IPv6 disabled or bindv6only=1 need an IPv4-capable network stack; keep the
    // host firewall covering both families (see SECURITY.md).
    return embeddedServer(Netty, port = ServerConfig.resolveServerPort(), host = "::") {
        install(SSE)
        configureCompression()
        configureCachingAndConditionalHeaders()
        configureErrorHandling(objectMapper)
        configureSerialization()
        configureCORS()
        dashboardRouting()
    }
}

internal class KrakenRebalancerApplicationLifecycle(
    private val portfolioManager: PortfolioManager,
    private val orderIntentService: OrderIntentService,
    private val applicationScope: CoroutineScope,
    private val httpClient: HttpClient,
    private val stopContainer: () -> Unit = ::stopKoin,
) {
    private val lock = Any()
    private val shutdownCompleted = CompletableFuture<Unit>()
    private var server: EmbeddedServer<*, *>? = null
    private var workerStarted = false
    private var shuttingDown = false
    private var shutdownOwner: Thread? = null

    fun attachServer(server: EmbeddedServer<*, *>) {
        synchronized(lock) {
            check(!shuttingDown) { "Cannot attach an HTTP server after application shutdown has started." }
            check(this.server == null) { "An HTTP server is already attached to the application lifecycle." }
            this.server = server
            server.monitor.subscribe(ServerReady) { startWorker() }
        }
    }

    fun shutdown() {
        val shouldPerformShutdown = synchronized(lock) {
            if (shuttingDown) {
                if (shutdownOwner === Thread.currentThread() && !shutdownCompleted.isDone) return
                false
            } else {
                shuttingDown = true
                shutdownOwner = Thread.currentThread()
                true
            }
        }
        if (!shouldPerformShutdown) {
            shutdownCompleted.join()
            return
        }

        var shutdownFailure: Throwable? = null
        var interrupted = false
        var stoppedWorker: Job? = null
        fun cleanup(action: () -> Unit) {
            try {
                action()
            } catch (failure: Throwable) {
                if (failure is InterruptedException) interrupted = true
                val primaryFailure = shutdownFailure
                if (primaryFailure == null) {
                    shutdownFailure = failure
                } else if (primaryFailure !== failure) {
                    primaryFailure.addSuppressed(failure)
                }
            }
        }

        cleanup {
            val attachedServer = synchronized(lock) { server }
            try {
                attachedServer?.stop()
            } catch (exception: Exception) {
                log.warn("HTTP server shutdown failed; continuing application cleanup.", exception)
            }

            stoppedWorker = portfolioManager.stopRebalancingLoop()
            val workerJoined =
                runBlocking {
                    joinRebalancingWorker(
                        workerJob = stoppedWorker,
                        hasUnresolvedOrderIntents = { orderIntentService.hasUnresolvedIntents() },
                    )
                }
            if (!workerJoined) {
                log.warn("Timed out waiting for the rebalance worker to finish during shutdown.")
            }
        }
        if (shutdownFailure != null) {
            // If stopping failed before returning a worker, cancel and drain the owning scope
            // instead: it may still contain the worker and its non-cancellable journal write.
            if (stoppedWorker == null) cleanup { applicationScope.cancel() }
            val jobToDrain = stoppedWorker ?: applicationScope.coroutineContext[Job]
            cleanup {
                // An interrupted runBlocking has a cancelled context. Drain the captured worker in
                // a fresh context before closing resources needed by its NonCancellable journal write.
                while (true) {
                    interrupted = Thread.interrupted() || interrupted
                    try {
                        runBlocking { jobToDrain?.join() }
                        break
                    } catch (interruption: InterruptedException) {
                        interrupted = true
                        shutdownFailure.addSuppressed(interruption)
                    }
                }
            }
        }
        // Keep dependencies alive through the worker's bounded (or journal-extended) drain,
        // but do not let a failed drain prevent the remaining shutdown phases.
        cleanup { applicationScope.cancel() }
        cleanup { httpClient.close() }
        cleanup { stopContainer() }
        if (interrupted) Thread.currentThread().interrupt()
        shutdownFailure?.let { failure ->
            shutdownCompleted.completeExceptionally(failure)
            throw failure
        }
        shutdownCompleted.complete(Unit)
    }

    private fun startWorker() {
        synchronized(lock) {
            if (shuttingDown || workerStarted) return
            portfolioManager.startRebalancingLoop(applicationScope)
            workerStarted = true
        }
    }
}
