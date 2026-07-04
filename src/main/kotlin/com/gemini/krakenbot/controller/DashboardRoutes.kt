package com.gemini.krakenbot.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.InvalidConfigurationException
import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.TradeHistoryService
import com.gemini.krakenbot.view.DashboardView
import com.gemini.krakenbot.view.util.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.html.*
import io.ktor.server.http.content.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import io.ktor.sse.*
import kotlinx.coroutines.CancellationException
import kotlinx.html.div
import kotlinx.html.h2
import kotlinx.html.html
import kotlinx.html.p
import kotlinx.html.stream.createHTML
import org.koin.ktor.ext.inject
import java.lang.management.ManagementFactory
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit

fun Application.dashboardRouting() {
    val tradeHistoryService: TradeHistoryService by inject()
    val configService: ConfigService by inject()
    val objectMapper: ObjectMapper by inject()
    val dashboardView: DashboardView by inject()

    routing {
        staticResources("/static", "static")

        get(Routes.ROOT) {
            call.respondHtml(HttpStatusCode.OK) {
                dashboardView.renderDashboardShell()
            }
        }

        get(Routes.SETTINGS) {
            val config = configService.getConfig()
            call.respondHtml(HttpStatusCode.OK) {
                dashboardView.renderSettingsPage(config, null)
            }
        }

        post(Routes.SETTINGS) {
            handlePostSettings(dashboardView, configService)
        }

        get(Routes.FRAGMENT_DASHBOARD) {
            handleGetDashboardFragment(dashboardView, tradeHistoryService)
        }

        get(Routes.HISTORY) {
            call.respondHtml(HttpStatusCode.OK) {
                dashboardView.renderHistoryPage()
            }
        }

        get(Routes.API_HISTORY_SNAPSHOTS) {
            handleGetHistorySnapshots(tradeHistoryService, objectMapper)
        }

        get(Routes.API_HISTORY_TRADES) {
            handleGetHistoryTrades(tradeHistoryService, objectMapper)
        }

        get(Routes.API_HISTORY_STATS) {
            handleGetHistoryStats(tradeHistoryService, objectMapper)
        }

        get("/api/history/sync-progress") {
            val offset = tradeHistoryService.getSyncMetadata("sync_offset")
            val total = tradeHistoryService.getSyncMetadata("sync_total")
            val seeded = tradeHistoryService.isHistorySeeded()
            val responseMap = mapOf(
                "seeded" to seeded,
                "offset" to offset,
                "total" to total
            )
            val json = objectMapper.writeValueAsString(responseMap)
            call.respondText(json, ContentType.Application.Json, HttpStatusCode.OK)
        }

        get("/api/health") {
            val stats = tradeHistoryService.getHistoryStats()
            val latestSnapshot = tradeHistoryService.getLatestSnapshot()
            val responseMap = mapOf(
                "status" to "UP",
                "timestamp" to Instant.now().toString(),
                "uptimeSeconds" to ManagementFactory.getRuntimeMXBean().uptime / 1000,
                "totalTradesExecuted" to stats.totalTradesExecuted,
                "totalVolumeTraded" to stats.totalVolumeTraded,
                "lastSnapshotTime" to (latestSnapshot?.timestamp?.toString() ?: "N/A"),
                "lastSnapshotTotalValueUSD" to (latestSnapshot?.totalValueUSD ?: BigDecimal.ZERO)
            )
            val json = objectMapper.writeValueAsString(responseMap)
            call.respondText(json, ContentType.Application.Json, HttpStatusCode.OK)
        }

        route(Routes.API_ROUTE_PREFIX) {
            sse(Routes.API_STATUS_STREAM.removePrefix(Routes.API_ROUTE_PREFIX)) {
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
    val loopDelaySeconds =
        params[FormFields.LOOP_DELAY_SECONDS]?.toLongOrNull() ?: 60L
    val deviationTriggerPercent =
        params[FormFields.DEVIATION_TRIGGER_PERCENT]?.toDoubleOrNull() ?: 2.0
    val dustThresholdUSD =
        params[FormFields.DUST_THRESHOLD_USD]?.toDoubleOrNull() ?: 1.0
    val dryRun = params[FormFields.DRY_RUN] != null
    val simulation = params[FormFields.SIMULATION] != null
    val fiatMaxDrawdown =
        params[FormFields.FIAT_MAX_DRAWDOWN]?.toDoubleOrNull() ?: 0.0
    val fiatDeploymentExponent =
        params[FormFields.FIAT_DEPLOYMENT_EXPONENT]?.toDoubleOrNull() ?: 1.0

    val symbols = params.getAll(FormFields.SYMBOLS) ?: emptyList()
    val targets = params.getAll(FormFields.TARGETS) ?: emptyList()

    val allocations = symbols.zip(targets).map { (symbol, targetStr) ->
        Allocation(symbol, targetStr.toDoubleOrNull() ?: 0.0)
    }

    val currentConfig = configService.getConfig()
    val updatedConfig = AppConfig(
        kraken = currentConfig.kraken,
        settings = Settings(
            loopDelaySeconds = loopDelaySeconds,
            deviationTriggerPercent = deviationTriggerPercent,
            dustThresholdUSD = dustThresholdUSD,
            dryRun = dryRun,
            simulation = simulation,
            fiatMaxDrawdown = fiatMaxDrawdown,
            fiatDeploymentExponent = fiatDeploymentExponent
        ),
        allocations = allocations
    )

    try {
        configService.updateConfig(updatedConfig)
        call.response.header(HtmxHeaders.HX_REDIRECT, Routes.ROOT)
        call.respond(HttpStatusCode.OK)
    } catch (e: InvalidConfigurationException) {
        val errHtml = createHTML(prettyPrint = false).html {
            dashboardView.renderSettingsPage(
                updatedConfig,
                e.message ?: "Invalid configuration"
            )
        }
        val formBody =
            errHtml.substringAfter("<body>").substringBefore("</body>")
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
        val noSnapshotHtml =
            createHTML(prettyPrint = false).div(CssClasses.SPINNER_CONTAINER) {
                h2(CssClasses.DASHBOARD_WAITING_TITLE) {
                    +ViewText.WAITING_FIRST_CYCLE
                }
                p(CssClasses.DASHBOARD_WAITING_TEXT) {
                    +ViewText.REBALANCER_RUNNING
                }
            }
        call.respondText(noSnapshotHtml, ContentType.Text.Html)
        return
    }

    val html = createHTML(prettyPrint = false).div {
        dashboardView.renderDashboardFragment(latest, history)
    }
    call.respondText(html, ContentType.Text.Html)
}

private suspend fun RoutingContext.handleGetHistorySnapshots(
    tradeHistoryService: TradeHistoryService,
    objectMapper: ObjectMapper
) {
    val (from, to) = parseTimeRange(call)
    val snapshots = tradeHistoryService.getSnapshotsInRange(from, to)
    val json = objectMapper.writeValueAsString(snapshots)
    call.respondText(json, ContentType.Application.Json)
}

private suspend fun RoutingContext.handleGetHistoryTrades(
    tradeHistoryService: TradeHistoryService,
    objectMapper: ObjectMapper
) {
    val (from, to) = parseTimeRange(call)
    val trades = tradeHistoryService.getTradesInRange(from, to)
    val json = objectMapper.writeValueAsString(trades)
    call.respondText(json, ContentType.Application.Json)
}

private suspend fun RoutingContext.handleGetHistoryStats(
    tradeHistoryService: TradeHistoryService,
    objectMapper: ObjectMapper
) {
    val stats = tradeHistoryService.getHistoryStats()
    val json = objectMapper.writeValueAsString(stats)
    call.respondText(json, ContentType.Application.Json)
}

internal fun parseTimeRange(call: ApplicationCall): Pair<Instant, Instant> {
    val now = Instant.now()
    val range = call.parameters["range"] ?: "30d"
    val from = when (range) {
        "24h" -> now.minus(1, ChronoUnit.DAYS)
        "7d" -> now.minus(7, ChronoUnit.DAYS)
        "30d" -> now.minus(30, ChronoUnit.DAYS)
        "90d" -> now.minus(90, ChronoUnit.DAYS)
        "all" -> Instant.EPOCH
        else -> now.minus(30, ChronoUnit.DAYS)
    }
    return Pair(from, now)
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
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // Handle client disconnect / closed channel gracefully without logging annoying stack traces
    }
}
