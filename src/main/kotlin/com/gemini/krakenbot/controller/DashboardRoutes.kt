package com.gemini.krakenbot.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.InvalidConfigurationException
import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.TradeHistoryService
import com.gemini.krakenbot.view.DashboardView
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.html.*
import io.ktor.server.http.content.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import io.ktor.sse.*
import kotlinx.html.*
import kotlinx.html.stream.createHTML
import org.koin.ktor.ext.inject

@Suppress("GrazieInspection")
fun Application.dashboardRouting() {
    val tradeHistoryService: TradeHistoryService by inject()
    val configService: ConfigService by inject()
    val objectMapper: ObjectMapper by inject()
    val dashboardView: DashboardView by inject()

    routing {
        staticResources("/static", "static")

        get("/") {
            call.respondHtml(HttpStatusCode.OK) {
                with(dashboardView) {
                    renderDashboardShell()
                }
            }
        }

        get("/settings") {
            val config = configService.getConfig()
            call.respondHtml(HttpStatusCode.OK) {
                with(dashboardView) {
                    renderSettingsPage(config, null)
                }
            }
        }

        post("/settings") {
            handlePostSettings(dashboardView, configService)
        }

        get("/fragments/dashboard") {
            handleGetDashboardFragment(dashboardView, tradeHistoryService)
        }

        route("/api") {
            sse("/status/stream") {
                handleSseStream(tradeHistoryService, objectMapper)
            }
        }
    }
}

private suspend fun RoutingContext.handlePostSettings(
    dashboardView: DashboardView,
    configService: ConfigService
) {
    val params = call.receiveParameters()
    val loopDelaySeconds = params["loopDelaySeconds"]?.toLongOrNull() ?: 60L
    val deviationTriggerPercent = params["deviationTriggerPercent"]?.toDoubleOrNull() ?: 2.0
    val dustThresholdUSD = params["dustThresholdUSD"]?.toDoubleOrNull() ?: 1.0
    val dryRun = params["dryRun"] != null
    val fiatMaxDrawdown = params["fiatMaxDrawdown"]?.toDoubleOrNull() ?: 0.0
    val fiatDeploymentExponent = params["fiatDeploymentExponent"]?.toDoubleOrNull() ?: 1.0

    val symbols = params.getAll("symbols") ?: emptyList()
    val targets = params.getAll("targets") ?: emptyList()

    val allocations = symbols.zip(targets).map { (symbol, targetStr) ->
        Allocation(symbol, targetStr.toDoubleOrNull() ?: 0.0)
    }

    val currentConfig = configService.getConfig()
    val updatedConfig = AppConfig(
        currentConfig.kraken,
        Settings(loopDelaySeconds, deviationTriggerPercent, dustThresholdUSD, dryRun, fiatMaxDrawdown, fiatDeploymentExponent),
        allocations
    )

    try {
        configService.updateConfig(updatedConfig)
        call.response.header("HX-Redirect", "/")
        call.respond(HttpStatusCode.OK)
    } catch (e: InvalidConfigurationException) {
        val errHtml = createHTML(prettyPrint = false).html {
            with(dashboardView) {
                renderSettingsPage(updatedConfig, e.message ?: "Invalid configuration")
            }
        }
        val formBody = errHtml.substringAfter("<body>").substringBefore("</body>")
        call.respondText(formBody, ContentType.Text.Html)
    }
}

private suspend fun RoutingContext.handleGetDashboardFragment(
    dashboardView: DashboardView,
    tradeHistoryService: TradeHistoryService
) {
    val latest = tradeHistoryService.getLatestSnapshot()
    val history = tradeHistoryService.getHistory()

    if (latest == null) {
        val noSnapshotHtml = createHTML(prettyPrint = false).div("spinner-container") {
            h2 {
                style = "font-size: 1.25rem; font-weight: 600; color: #e2e8f0;"
                +"Waiting for first rebalance cycle"
            }
            p {
                style = "color: #94a3b8; font-size: 0.875rem; text-align: center; max-width: 24rem;"
                +"The rebalancer is running. Portfolio data will appear here after the first cycle completes."
            }
        }
        call.respondText(noSnapshotHtml, ContentType.Text.Html)
        return
    }

    val html = createHTML(prettyPrint = false).div {
        with(dashboardView) {
            renderDashboardFragment(latest, history)
        }
    }
    call.respondText(html, ContentType.Text.Html)
}

private suspend fun ServerSSESession.handleSseStream(
    tradeHistoryService: TradeHistoryService,
    objectMapper: ObjectMapper
) {
    try {
        val latest = tradeHistoryService.getLatestSnapshot()
        if (latest != null) {
            val json = objectMapper.writeValueAsString(latest)
            send(ServerSentEvent(data = json))
        }

        tradeHistoryService.getHistoryFlow().collect { snapshot ->
            val json = objectMapper.writeValueAsString(snapshot)
            send(ServerSentEvent(data = json))
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        // Handle client disconnect / closed channel gracefully without logging annoying stack traces
    }
}
