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
    if (hasPendingSubmissions() || hasUnresolvedOrderIntents()) {
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
                if (shutdownOwner === Thread.currentThread()) return
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

        try {
            val attachedServer = synchronized(lock) { server }
            try {
                attachedServer?.stop()
            } catch (exception: Exception) {
                log.warn("HTTP server shutdown failed; continuing application cleanup.", exception)
            }

            val stoppedWorker = portfolioManager.stopRebalancingLoop()
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

            applicationScope.cancel()
            try {
                httpClient.close()
            } catch (exception: Exception) {
                log.error("Could not close the HTTP client during application shutdown.", exception)
            }
            try {
                stopContainer()
            } catch (exception: Exception) {
                log.error("Could not stop the dependency container during application shutdown.", exception)
            }
        } catch (failure: Throwable) {
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
