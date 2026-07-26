package com.gemini.krakenbot.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.gemini.krakenbot.api.buildSyncProgressResponse
import com.gemini.krakenbot.api.toApiDto
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.InvalidConfigurationException
import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.model.TimeRange
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.TradeHistoryService
import com.gemini.krakenbot.view.DashboardView
import com.gemini.krakenbot.view.css.CssStyles
import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.FormFields
import com.gemini.krakenbot.view.util.HealthStatusKeys
import com.gemini.krakenbot.view.util.HtmxHeaders
import com.gemini.krakenbot.view.util.QueryParamKeys
import com.gemini.krakenbot.view.util.Routes
import com.gemini.krakenbot.view.util.ViewText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.html.respondHtml
import io.ktor.server.http.content.staticResources
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.sse.ServerSSESession
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.CancellationException
import kotlinx.html.div
import kotlinx.html.h2
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
    private val dashboardView: DashboardView,
) {
    fun registerRoutes(routing: Routing) {
        with(routing) {
            get(Routes.STATIC_STYLE_CSS) {
                call.respondText(CssStyles.stylesheet.toString(), ContentType.Text.CSS)
            }
            staticResources(Routes.STATIC_PREFIX, Routes.STATIC_RESOURCES_DIR)

            get(Routes.ROOT) {
                val settings = configService.getConfig().settings
                call.respondHtml(HttpStatusCode.OK) {
                    dashboardView.renderDashboardShell(settings)
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
                val settings = configService.getConfig().settings
                call.respondHtml(HttpStatusCode.OK) {
                    dashboardView.renderHistoryPage(settings)
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
        val currentConfig = configService.getConfig()

        val deviationTriggerPercent =
            params[FormFields.DEVIATION_TRIGGER_PERCENT]?.toDoubleOrNull()
        if (deviationTriggerPercent == null) {
            respondSettingsFormError(currentConfig, ViewText.INVALID_DEVIATION_TRIGGER)
            return
        }
        val dustThresholdUSD = params[FormFields.DUST_THRESHOLD_USD]?.toDoubleOrNull()
        if (dustThresholdUSD == null) {
            respondSettingsFormError(currentConfig, ViewText.INVALID_DUST_THRESHOLD)
            return
        }

        val loopDelaySeconds =
            params[FormFields.LOOP_DELAY_SECONDS]?.toLongOrNull() ?: 60L
        val dryRun = params[FormFields.DRY_RUN] != null
        val simulation = params[FormFields.SIMULATION] != null
        val fiatMaxDrawdown =
            params[FormFields.FIAT_MAX_DRAWDOWN]?.toDoubleOrNull() ?: 0.0
        val fiatDeploymentExponent =
            params[FormFields.FIAT_DEPLOYMENT_EXPONENT]?.toDoubleOrNull() ?: 1.0

        val symbols = params.getAll(FormFields.SYMBOLS) ?: emptyList()
        val targets = params.getAll(FormFields.TARGETS) ?: emptyList()

        val allocations =
            symbols.zip(targets).map { (symbol, targetStr) ->
                Allocation(symbol, targetStr.toDoubleOrNull() ?: 0.0)
            }

        val updatedConfig =
            AppConfig(
                kraken = currentConfig.kraken,
                settings =
                Settings(
                    loopDelaySeconds = loopDelaySeconds,
                    deviationTriggerPercent = deviationTriggerPercent,
                    dustThresholdUSD = dustThresholdUSD,
                    dryRun = dryRun,
                    simulation = simulation,
                    fiatMaxDrawdown = fiatMaxDrawdown,
                    fiatDeploymentExponent = fiatDeploymentExponent,
                ),
                allocations = allocations,
            )

        try {
            configService.updateConfig(updatedConfig)
            call.response.header(HtmxHeaders.HX_REDIRECT, Routes.ROOT)
            call.respond(HttpStatusCode.OK)
        } catch (e: InvalidConfigurationException) {
            respondSettingsFormError(
                updatedConfig,
                e.message ?: ViewText.INVALID_CONFIGURATION_FALLBACK,
            )
        }
    }

    private suspend fun RoutingContext.respondSettingsFormError(config: AppConfig, message: String) {
        val errHtml =
            createHTML(prettyPrint = false).div {
                dashboardView.renderSettingsFormFragment(this, config, message)
            }
        call.respondText(errHtml, ContentType.Text.Html)
    }

    private suspend fun RoutingContext.handleGetDashboardFragment() {
        val history = tradeHistoryService.getHistory()
        val latest = history.firstOrNull()

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

        val html =
            createHTML(prettyPrint = false).div {
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
        val snapshots = tradeHistoryService.getSnapshotsInRange(from, to).map { it.toApiDto() }
        respondJson(snapshots)
    }

    private suspend fun RoutingContext.handleGetHistoryTrades() {
        val (from, to) = parseTimeRange(call)
        val trades = tradeHistoryService.getTradesInRange(from, to).map { it.toApiDto() }
        respondJson(trades)
    }

    private suspend fun RoutingContext.handleGetHistoryStats() {
        val stats =
            if (call.parameters[QueryParamKeys.RANGE] != null) {
                val (from, to) = parseTimeRange(call)
                tradeHistoryService.getHistoryStats(from, to)
            } else {
                tradeHistoryService.getHistoryStats()
            }
        respondJson(stats.toApiDto())
    }

    private suspend fun ServerSSESession.handleSseStream() {
        try {
            // Send persisted state first; replay on the subsequent hot-flow collection closes the
            // read/subscribe race, with a harmless duplicate event possible at connection time.
            val latest = tradeHistoryService.getLatestSnapshot()
            if (latest != null) {
                sendSnapshot(latest)
            }

            tradeHistoryService.getHistoryFlow().collect { snapshot ->
                sendSnapshot(snapshot)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Keep non-cancellation failures local to this client session so other collectors continue.
        }
    }

    private suspend fun ServerSSESession.sendSnapshot(snapshot: PortfolioSnapshot) {
        val json = objectMapper.writeValueAsString(snapshot)
        send(ServerSentEvent(data = json))
    }

    private suspend fun RoutingContext.handleGetSyncProgress() {
        val offset = tradeHistoryService.getSyncMetadata(SyncMetadataKeys.SYNC_OFFSET)
        val total = tradeHistoryService.getSyncMetadata(SyncMetadataKeys.SYNC_TOTAL)
        val seeded = tradeHistoryService.isHistorySeeded()
        respondJson(buildSyncProgressResponse(seeded, offset, total))
    }

    private suspend fun RoutingContext.handleGetHealth() {
        val stats = tradeHistoryService.getHistoryStats()
        val latestSnapshot = tradeHistoryService.getLatestSnapshot()
        val responseMap =
            mapOf(
                HealthStatusKeys.STATUS to HealthStatusKeys.STATUS_UP,
                HealthStatusKeys.TIMESTAMP to Instant.now().toString(),
                HealthStatusKeys.UPTIME_SECONDS to ManagementFactory.getRuntimeMXBean().uptime / 1000,
                HealthStatusKeys.TOTAL_TRADES_EXECUTED to stats.totalTradesExecuted,
                HealthStatusKeys.TOTAL_VOLUME_TRADED to stats.totalVolumeTraded,
                HealthStatusKeys.LAST_SNAPSHOT_TIME to (latestSnapshot?.timestamp?.toString() ?: "N/A"),
                HealthStatusKeys.LAST_SNAPSHOT_TOTAL_VALUE_USD to (latestSnapshot?.totalValueUSD ?: BigDecimal.ZERO),
            )
        respondJson(responseMap)
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
