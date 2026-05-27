package com.gemini.krakenbot.controller

import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.InvalidConfigurationException
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.TradeHistoryService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

fun Application.dashboardRouting() {
    val tradeHistoryService: TradeHistoryService by inject()
    val configService: ConfigService by inject()

    routing {
        route("/api") {
            get("/status") {
                val snapshot = tradeHistoryService.getLatestSnapshot()
                if (snapshot != null) {
                    call.respond(snapshot)
                } else {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "No snapshot available yet"))
                }
            }

            get("/history") {
                call.respond(tradeHistoryService.getHistory())
            }

            get("/config") {
                val config = configService.getConfig()
                call.respond(FrontendConfig(config.settings, config.allocations))
            }

            post("/config") {
                try {
                    val config = call.receive<FrontendConfig>()
                    val serverCredentials = configService.getConfig().kraken
                    val configWithCredentials = AppConfig(serverCredentials, config.settings, config.allocations)
                    configService.updateConfig(configWithCredentials)
                    val updated = configService.getConfig()
                    call.respond(FrontendConfig(updated.settings, updated.allocations))
                } catch (e: InvalidConfigurationException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid configuration")))
                }
            }
        }
    }
}
