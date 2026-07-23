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
import java.lang.management.ManagementFactory
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit

class DashboardController(
    private val tradeHistoryService: TradeHistoryService,
    private val configService: ConfigService,
    private val objectMapper: ObjectMapper,
    private val dashboardView: DashboardView
) {
    fun registerRoutes(routing: Routing) {
        with(routing) {
            get(Routes.STATIC_STYLE_CSS) {
                call.respondText(CssStyles.stylesheet.toString(), ContentType.Text.CSS)
            }
            staticResources(Routes.STATIC_PREFIX, Routes.STATIC_RESOURCES_DIR)

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
                handlePostSettings()
            }

            get(Routes.FRAGMENT_DASHBOARD) {
                handleGetDashboardFragment()
            }

            get(Routes.HISTORY) {
                call.respondHtml(HttpStatusCode.OK) {
                    dashboardView.renderHistoryPage()
                }
            }

            get(Routes.API_HISTORY_SNAPSHOTS) {
                handleGetHistorySnapshots()
            }

            get(Routes.API_HISTORY_TRADES) {
                handleGetHistoryTrades()
            }

            get(Routes.API_HISTORY_STATS) {
                handleGetHistoryStats()
            }

            get(Routes.API_HISTORY_SYNC_PROGRESS) {
                handleGetSyncProgress()
            }

            get(Routes.API_HEALTH) {
                handleGetHealth()
            }

            route(Routes.API_ROUTE_PREFIX) {
                sse(Routes.API_STATUS_STREAM.removePrefix(Routes.API_ROUTE_PREFIX)) {
                    handleSseStream()
                }
            }
        }
    }

    private suspend fun RoutingContext.handlePostSettings() {
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

    private suspend fun RoutingContext.handleGetDashboardFragment() {
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

    private suspend fun RoutingContext.respondJson(data: Any) {
        val json = objectMapper.writeValueAsString(data)
        call.respondText(json, ContentType.Application.Json, HttpStatusCode.OK)
    }

    private suspend fun RoutingContext.handleGetHistorySnapshots() {
        val (from, to) = parseTimeRange(call)
        val snapshots = tradeHistoryService.getSnapshotsInRange(from, to)
        respondJson(snapshots)
    }

    private suspend fun RoutingContext.handleGetHistoryTrades() {
        val (from, to) = parseTimeRange(call)
        val trades = tradeHistoryService.getTradesInRange(from, to)
        respondJson(trades)
    }

    private suspend fun RoutingContext.handleGetHistoryStats() {
        val stats = if (call.parameters[QueryParamKeys.RANGE] != null) {
            val (from, to) = parseTimeRange(call)
            tradeHistoryService.getHistoryStats(from, to)
        } else {
            tradeHistoryService.getHistoryStats()
        }
        respondJson(stats)
    }

    private suspend fun ServerSSESession.handleSseStream() {
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

    private suspend fun RoutingContext.handleGetSyncProgress() {
        val offset = tradeHistoryService.getSyncMetadata(SyncMetadataKeys.SYNC_OFFSET)
        val total = tradeHistoryService.getSyncMetadata(SyncMetadataKeys.SYNC_TOTAL)
        val seeded = tradeHistoryService.isHistorySeeded()
        val responseMap = mapOf(
            SyncMetadataKeys.IS_SEEDED to seeded,
            SyncMetadataKeys.OFFSET to offset,
            SyncMetadataKeys.TOTAL to total
        )
        respondJson(responseMap)
    }

    private suspend fun RoutingContext.handleGetHealth() {
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
        respondJson(responseMap)
    }

    private companion object {
        val BODY_REGEX = "<body[^>]*>(.*?)</body>".toRegex(RegexOption.DOT_MATCHES_ALL)
    }
}

fun TimeRange.calculateFromInstant(now: Instant): Instant =
    days?.let { now.minus(it, ChronoUnit.DAYS) } ?: Instant.EPOCH

internal fun parseTimeRange(call: ApplicationCall): Pair<Instant, Instant> {
    val now = Instant.now()
    val timeRange = TimeRange.fromQueryParam(call.parameters[QueryParamKeys.RANGE])
    val from = timeRange.calculateFromInstant(now)
    return Pair(from, now)
}
