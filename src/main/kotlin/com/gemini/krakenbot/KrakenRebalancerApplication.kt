package com.gemini.krakenbot

import com.gemini.krakenbot.config.ErrorHandlingConfig.configureErrorHandling
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
import org.koin.logger.slf4jLogger
import org.slf4j.LoggerFactory

private const val REBALANCING_SHUTDOWN_TIMEOUT_MILLIS = 5_000L

private val log = LoggerFactory.getLogger("KrakenRebalancerApplication")

internal suspend fun joinRebalancingWorker(workerJob: Job?): Boolean =
    withTimeoutOrNull(REBALANCING_SHUTDOWN_TIMEOUT_MILLIS) {
        workerJob?.join()
        true
    } ?: false

fun main() {
    startKoin {
        slf4jLogger()
        modules(appModule)
    }

    val koin = GlobalContext.get()
    val portfolioManager = koin.get<PortfolioManager>()
    val tradeHistoryService = koin.get<TradeHistoryService>()
    val httpClient = koin.get<HttpClient>()

    // Blocking: history cleanup/migration/simulation seeding must finish before runLoop is launched
    // below and before the server accepts traffic, so cycle one and the dashboard see seeded state.
    runBlocking { tradeHistoryService.init() }

    val applicationScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    val workerJob = portfolioManager.startRebalancingLoop(applicationScope)

    Runtime.getRuntime().addShutdownHook(
        Thread {
            // Stop and join the worker before closing its Koin-managed client and dependency graph.
            portfolioManager.stopRebalancingLoop()
            val workerJoined = runBlocking { joinRebalancingWorker(workerJob) }
            if (!workerJoined) {
                log.warn("Timed out waiting for the rebalance worker to finish during shutdown.")
            }
            applicationScope.cancel()
            httpClient.close()
            stopKoin()
        },
    )

    startServer()
}

private fun startServer() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0") {
        install(SSE)
        configureCompression()
        configureCachingAndConditionalHeaders()
        configureErrorHandling()
        configureSerialization()
        configureCORS()
        dashboardRouting()
    }.start(wait = true)
}
