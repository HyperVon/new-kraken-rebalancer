package com.gemini.krakenbot

import com.gemini.krakenbot.config.appModule
import com.gemini.krakenbot.controller.dashboardRouting
import com.gemini.krakenbot.service.PortfolioManager
import io.ktor.serialization.jackson.jackson
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.http.HttpMethod
import kotlinx.coroutines.launch
import org.koin.core.context.startKoin
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger

fun main() {
    startKoin {
        slf4jLogger()
        modules(appModule)
    }

    val koin = org.koin.core.context.GlobalContext.get()
    val portfolioManager = koin.get<PortfolioManager>()
    
    // Start the background rebalancing loop
    portfolioManager.startRebalancingLoop()
    val applicationScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob())
    applicationScope.launch {
        portfolioManager.runLoop()
    }

    embeddedServer(Netty, port = 8080, host = "0.0.0.0") {
        install(ContentNegotiation) {
            jackson {
                findAndRegisterModules()
            }
        }
        install(CORS) {
            anyHost()
            allowMethod(HttpMethod.Options)
            allowMethod(HttpMethod.Put)
            allowMethod(HttpMethod.Delete)
            allowMethod(HttpMethod.Patch)
            allowHeader(io.ktor.http.HttpHeaders.Authorization)
            allowHeader(io.ktor.http.HttpHeaders.ContentType)
        }
        install(Koin) {
            slf4jLogger()
            modules(appModule)
        }
        
        dashboardRouting()
    }.start(wait = true)
}
