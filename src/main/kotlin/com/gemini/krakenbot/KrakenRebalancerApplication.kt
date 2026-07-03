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
import kotlinx.coroutines.cancel
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
        applicationScope.cancel()
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
            allowOrigins { origin ->
                isLocalOrPrivateOrigin(origin)
            }
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

private fun isLocalOrPrivateOrigin(origin: String): Boolean {
    val clean = origin.removePrefix("http://").removePrefix("https://").substringBefore(":")
    if (clean.equals("localhost", ignoreCase = true) || clean == "127.0.0.1" || clean == "::1") {
        return true
    }
    if (clean.endsWith(".local", ignoreCase = true)) {
        return true
    }
    if (clean.startsWith("192.168.") || clean.startsWith("10.") || clean.startsWith("169.254.")) {
        return true
    }
    if (clean.startsWith("172.")) {
        val parts = clean.split(".")
        if (parts.size >= 2) {
            val secondOctet = parts[1].toIntOrNull()
            if (secondOctet != null && secondOctet in 16..31) {
                return true
            }
        }
    }
    return false
}
