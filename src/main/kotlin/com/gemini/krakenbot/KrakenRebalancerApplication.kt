package com.gemini.krakenbot

import com.gemini.krakenbot.config.ErrorHandlingConfig.configureErrorHandling
import com.gemini.krakenbot.config.appModule
import com.gemini.krakenbot.config.configureCORS
import com.gemini.krakenbot.config.configureSerialization
import com.gemini.krakenbot.controller.dashboardRouting
import com.gemini.krakenbot.service.PortfolioManager
import io.ktor.client.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.sse.*
import kotlinx.coroutines.*
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.logger.slf4jLogger

fun main() {
    startKoin {
        slf4jLogger()
        modules(appModule)
    }

    val koin = GlobalContext.get()
    val portfolioManager = koin.get<PortfolioManager>()
    val httpClient = koin.get<HttpClient>()

    portfolioManager.startRebalancingLoop()
    val applicationScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    applicationScope.launch {
        portfolioManager.runLoop()
    }

    Runtime.getRuntime().addShutdownHook(Thread {
        portfolioManager.stopRebalancingLoop()
        applicationScope.cancel()
        httpClient.close()
        stopKoin()
    })

    startServer()
}

private fun startServer() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0") {
        install(SSE)
        configureErrorHandling()
        configureSerialization()
        configureCORS()

        dashboardRouting()
    }.start(wait = true)
}
