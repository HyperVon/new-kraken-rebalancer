package com.gemini.krakenbot.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.InvalidConfigurationException
import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.model.TimeRange
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

fun Application.dashboardRouting() {
    val tradeHistoryService: TradeHistoryService by inject()
    val configService: ConfigService by inject()
    val objectMapper: ObjectMapper by inject()
    val dashboardView: DashboardView by inject()

    routing {
        get(Routes.STATIC_STYLE_CSS) {
            call.respondText(CssStyles.stylesheet.toString(), ContentType.Text.CSS)
        }
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

        get(Routes.API_HISTORY_SYNC_PROGRESS) {
            handleGetSyncProgress(tradeHistoryService, objectMapper)
        }

        get(Routes.API_HEALTH) {
            handleGetHealth(tradeHistoryService, objectMapper)
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
        val bodyMatch = BODY_REGEX.find(errHtml)
        val formBody = bodyMatch?.groupValues?.get(1) ?: errHtml
        call.respondText(formBody, ContentType.Text.Html)
    }
}

private val BODY_REGEX = "<body[^>]*>(.*?)</body>".toRegex(RegexOption.DOT_MATCHES_ALL)

private suspend fun RoutingContext.handleGetDashboardFragment(
    dashboardView: DashboardView,
    tradeHistoryService: TradeHistoryService
) {
    val latest = tradeHistoryService.getLatestSnapshot()
    val history = tradeHistoryService.getHistory()

    if (latest == null) {
        val noSnapshotHtml =
            createHTML(prettyPrint = false).div(CssClass.Loading.SpinnerContainer.value) {
                h2(CssClass.Dashboard.WaitingTitle.value) {
                    +ViewText.WAITING_FIRST_CYCLE
                }
                p(CssClass.Dashboard.WaitingText.value) {
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
    val stats = if (call.parameters["range"] != null) {
        val (from, to) = parseTimeRange(call)
        tradeHistoryService.getHistoryStats(from, to)
    } else {
        tradeHistoryService.getHistoryStats()
    }
    val json = objectMapper.writeValueAsString(stats)
    call.respondText(json, ContentType.Application.Json)
}

internal fun parseTimeRange(call: ApplicationCall): Pair<Instant, Instant> {
    val now = Instant.now()
    val timeRange = TimeRange.fromQueryParam(call.parameters["range"])
    val from = timeRange.calculateFromInstant(now)
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

private suspend fun RoutingContext.handleGetSyncProgress(
    tradeHistoryService: TradeHistoryService,
    objectMapper: ObjectMapper
) {
    val offset = tradeHistoryService.getSyncMetadata(SyncMetadataKeys.SYNC_OFFSET)
    val total = tradeHistoryService.getSyncMetadata(SyncMetadataKeys.SYNC_TOTAL)
    val seeded = tradeHistoryService.isHistorySeeded()
    val responseMap = mapOf(
        "seeded" to seeded,
        "offset" to offset,
        "total" to total
    )
    val json = objectMapper.writeValueAsString(responseMap)
    call.respondText(json, ContentType.Application.Json, HttpStatusCode.OK)
}

private suspend fun RoutingContext.handleGetHealth(
    tradeHistoryService: TradeHistoryService,
    objectMapper: ObjectMapper
) {
    val stats = tradeHistoryService.getHistoryStats()
    val latestSnapshot = tradeHistoryService.getLatestSnapshot()
    val responseMap = mapOf(
        HealthStatusKeys.STATUS to HealthStatusKeys.STATUS_UP,
        HealthStatusKeys.TIMESTAMP to Instant.now().toString(),
        HealthStatusKeys.UPTIME_SECONDS to ManagementFactory.getRuntimeMXBean().uptime / 1000,
        HealthStatusKeys.TOTAL_TRADES_EXECUTED to stats.totalTradesExecuted,
        HealthStatusKeys.TOTAL_VOLUME_TRADED to stats.totalVolumeTraded,
        HealthStatusKeys.LAST_SNAPSHOT_TIME to (latestSnapshot?.timestamp?.toString() ?: "N/A"),
        HealthStatusKeys.LAST_SNAPSHOT_TOTAL_VALUE_USD to (latestSnapshot?.totalValueUSD ?: BigDecimal.ZERO)
    )
    val json = objectMapper.writeValueAsString(responseMap)
    call.respondText(json, ContentType.Application.Json, HttpStatusCode.OK)
}
