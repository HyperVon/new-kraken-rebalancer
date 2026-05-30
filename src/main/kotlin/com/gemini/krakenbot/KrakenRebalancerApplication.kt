package com.gemini.krakenbot

import com.fasterxml.jackson.databind.SerializationFeature
import com.gemini.krakenbot.config.appModule
import com.gemini.krakenbot.controller.dashboardRouting
import com.gemini.krakenbot.service.PortfolioManager
import io.ktor.client.*
import io.ktor.http.*
import io.ktor.serialization.jackson.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.sse.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
    val applicationScope =
        CoroutineScope(Dispatchers.Default + SupervisorJob())
    applicationScope.launch {
        portfolioManager.runLoop()
    }

    Runtime.getRuntime().addShutdownHook(Thread {
        portfolioManager.stopRebalancingLoop()
        httpClient.close()
        stopKoin()
    })

    embeddedServer(Netty, port = 8080, host = "0.0.0.0") {
        install(SSE)
        install(ContentNegotiation) {
            jackson {
                findAndRegisterModules()
                disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            }
        }

        install(CORS) {
            anyHost()
            allowMethod(HttpMethod.Options)
            allowMethod(HttpMethod.Put)
            allowMethod(HttpMethod.Delete)
            allowMethod(HttpMethod.Patch)
            allowHeader(HttpHeaders.Authorization)
            allowHeader(HttpHeaders.ContentType)
        }

        dashboardRouting()
    }.start(wait = true)
}
