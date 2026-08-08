package com.gemini.krakenbot

import com.fasterxml.jackson.databind.ObjectMapper
import com.gemini.krakenbot.config.APPLICATION_SCOPE_QUALIFIER
import com.gemini.krakenbot.config.ErrorHandlingConfig.configureErrorHandling
import com.gemini.krakenbot.config.ServerConfig
import com.gemini.krakenbot.config.appModule
import com.gemini.krakenbot.config.configureCORS
import com.gemini.krakenbot.config.configureCachingAndConditionalHeaders
import com.gemini.krakenbot.config.configureCompression
import com.gemini.krakenbot.config.configureSerialization
import com.gemini.krakenbot.controller.dashboardRouting
import com.gemini.krakenbot.service.PortfolioManager
import com.gemini.krakenbot.service.TradeHistoryService
import io.ktor.client.HttpClient
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.sse.SSE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.qualifier.named
import org.koin.logger.slf4jLogger
import org.slf4j.LoggerFactory

private const val REBALANCING_SHUTDOWN_TIMEOUT_MILLIS = 5_000L

private val log = LoggerFactory.getLogger("KrakenRebalancerApplication")

internal suspend fun joinRebalancingWorker(
    workerJob: Job?,
    hasPendingSubmissions: suspend () -> Boolean = { false },
): Boolean {
    val joinedWithinBudget = withTimeoutOrNull(REBALANCING_SHUTDOWN_TIMEOUT_MILLIS) {
        workerJob?.join()
        true
    } ?: false
    if (joinedWithinBudget) return true
    if (hasPendingSubmissions()) {
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

fun main() {
    startKoin {
        slf4jLogger()
        modules(appModule)
    }

    val koin = GlobalContext.get()
    val portfolioManager = koin.get<PortfolioManager>()
    val tradeHistoryService = koin.get<TradeHistoryService>()
    val httpClient = koin.get<HttpClient>()
    val objectMapper = koin.get<ObjectMapper>()

    // Blocking: history cleanup/migration/simulation seeding must finish before runLoop is launched
    // below and before the server accepts traffic, so cycle one and the dashboard see seeded state.
    runBlocking { tradeHistoryService.init() }

    val applicationScope: CoroutineScope = koin.get(
        qualifier = named(APPLICATION_SCOPE_QUALIFIER),
    )
    val workerJob = portfolioManager.startRebalancingLoop(applicationScope)

    Runtime.getRuntime().addShutdownHook(
        Thread {
            // Stop and join the worker before closing its Koin-managed client and dependency graph.
            portfolioManager.stopRebalancingLoop()
            val workerJoined =
                runBlocking {
                    joinRebalancingWorker(workerJob) { tradeHistoryService.hasPendingSubmissions() }
                }
            if (!workerJoined) {
                log.warn("Timed out waiting for the rebalance worker to finish during shutdown.")
            }
            applicationScope.cancel()
            httpClient.close()
            stopKoin()
        },
    )

    startServer(objectMapper)
}

private fun startServer(objectMapper: ObjectMapper) {
    // Bind the IPv6 wildcard. On dual-stack kernels (macOS/Windows default; Linux when
    // net.ipv6.bindv6only=0) the same socket also accepts IPv4-mapped clients. The IPv4
    // wildcard `0.0.0.0` refuses native IPv6, which breaks hostname clients that prefer AAAA.
    // IPv4 literals (e.g. http://10.0.0.x:8080/) never use AAAA — bind family does not affect them.
    // Hosts with IPv6 disabled or bindv6only=1 need an IPv4-capable network stack; keep the
    // host firewall covering both families (see SECURITY.md).
    embeddedServer(Netty, port = ServerConfig.resolveServerPort(), host = "::") {
        install(SSE)
        configureCompression()
        configureCachingAndConditionalHeaders()
        configureErrorHandling(objectMapper)
        configureSerialization()
        configureCORS()
        dashboardRouting()
    }.start(wait = true)
}
