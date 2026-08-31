package com.gemini.krakenbot.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.gemini.krakenbot.api.buildSyncProgressResponse
import com.gemini.krakenbot.api.toApiDto
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.InvalidConfigurationException
import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.domain.PortfolioCalculations
import com.gemini.krakenbot.model.OrderIntentState
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.model.TimeRange
import com.gemini.krakenbot.service.AssetColorAssigner
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.OrderIntentService
import com.gemini.krakenbot.service.PortfolioManager
import com.gemini.krakenbot.service.RebalanceOperationalStatus
import com.gemini.krakenbot.service.TradeHistoryService
import com.gemini.krakenbot.view.DashboardView
import com.gemini.krakenbot.view.css.CssStyles
import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.FormFields
import com.gemini.krakenbot.view.util.HealthStatusKeys
import com.gemini.krakenbot.view.util.HtmxHeaders
import com.gemini.krakenbot.view.util.HtmxValues
import com.gemini.krakenbot.view.util.QueryParamKeys
import com.gemini.krakenbot.view.util.Routes
import com.gemini.krakenbot.view.util.ViewText
import com.gemini.krakenbot.view.util.symbolColorMap
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
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
import org.slf4j.LoggerFactory
import java.lang.management.ManagementFactory
import java.math.BigDecimal
import java.time.DateTimeException
import java.time.Instant
import java.time.temporal.ChronoUnit

class DashboardController(
    private val tradeHistoryService: TradeHistoryService,
    private val configService: ConfigService,
    private val objectMapper: ObjectMapper,
    private val dashboardView: DashboardView,
    private val portfolioManager: PortfolioManager,
    private val orderIntentService: OrderIntentService,
) {
    private val log = LoggerFactory.getLogger(DashboardController::class.java)

    fun registerRoutes(routing: Routing) {
        with(routing) {
            get(Routes.STATIC_STYLE_CSS) {
                call.respondText(CssStyles.stylesheet.toString(), ContentType.Text.CSS)
            }
            staticResources(Routes.STATIC_PREFIX, Routes.STATIC_RESOURCES_DIR)

            get(Routes.ROOT) {
                val settings = configService.getConfig().settings
                val csrfToken = CsrfProtection.issueToken(call)
                call.respondHtml(HttpStatusCode.OK) {
                    dashboardView.renderDashboardShell(
                        settings = settings,
                        csrfToken = csrfToken,
                        paused = portfolioManager.isLoopPaused(),
                    )
                }
            }

            get(Routes.SETTINGS) {
                val config = configService.getConfig()
                val csrfToken = CsrfProtection.issueToken(call)
                call.respondHtml(HttpStatusCode.OK) {
                    dashboardView.renderSettingsPage(
                        config = config,
                        errorMessage = null,
                        csrfToken = csrfToken,
                        paused = portfolioManager.isLoopPaused(),
                    )
                }
            }

            post(Routes.SETTINGS) {
                handlePostSettings()
            }

            get(Routes.FRAGMENT_DASHBOARD) {
                handleGetDashboardFragment()
            }

            get(Routes.HISTORY) {
                val config = configService.getConfig()
                val settings = config.settings
                val symbolColorMap = config.allocations.symbolColorMap()
                val csrfToken = CsrfProtection.issueToken(call)
                call.respondHtml(HttpStatusCode.OK) {
                    dashboardView.renderHistoryPage(
                        settings = settings,
                        symbolColorMap = symbolColorMap,
                        csrfToken = csrfToken,
                        paused = portfolioManager.isLoopPaused(),
                    )
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

            get(Routes.API_HISTORY_COMPARISON) {
                handleGetHistoryComparison()
            }

            get(Routes.API_HISTORY_REWARDS) {
                handleGetHistoryRewards()
            }

            get(Routes.API_HEALTH) {
                handleGetHealth()
            }

            get(Routes.API_READINESS) {
                handleGetReadiness()
            }

            get(Routes.API_ORDER_INTENTS) {
                handleGetOrderIntents()
            }

            post(Routes.API_ORDER_INTENTS_RESOLVE_TEMPLATE) {
                handlePostOrderIntentResolution()
            }

            post(Routes.API_PAUSE) {
                handlePostPause()
            }

            post(Routes.API_RESUME) {
                handlePostResume()
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
        if (!CsrfProtection.isValid(call, params)) {
            val token = CsrfProtection.rotateToken(call)
            call.response.header(HtmxHeaders.HX_REFRESH, HtmxValues.TRUE)
            call.response.header(HtmxHeaders.HX_RESWAP, HtmxValues.INNER_HTML)
            call.response.header(HtmxHeaders.HX_RETARGET, HtmxValues.BODY)
            respondSettingsFormError(
                config = configService.getConfig(),
                message = ViewText.CSRF_SESSION_EXPIRED,
                csrfToken = token,
                paused = portfolioManager.isLoopPaused(),
                status = HttpStatusCode.Forbidden,
            )
            return
        }
        val currentConfig = configService.getConfig()
        val updatedConfig = try {
            parseSettingsForm(params, currentConfig)
        } catch (e: IllegalArgumentException) {
            respondSettingsFormError(
                config = currentConfig,
                message = e.message ?: ViewText.INVALID_CONFIGURATION_FALLBACK,
                csrfToken = CsrfProtection.currentToken(call),
                paused = portfolioManager.isLoopPaused(),
                status = HttpStatusCode.UnprocessableEntity,
            )
            return
        }

        try {
            configService.updateConfig(updatedConfig)
            call.response.header(HtmxHeaders.HX_REDIRECT, Routes.ROOT)
            call.respond(HttpStatusCode.OK)
        } catch (e: InvalidConfigurationException) {
            respondSettingsFormError(
                config = updatedConfig,
                message = e.message ?: ViewText.INVALID_CONFIGURATION_FALLBACK,
                csrfToken = CsrfProtection.currentToken(call),
                paused = portfolioManager.isLoopPaused(),
                status = HttpStatusCode.UnprocessableEntity,
            )
        }
    }

    private fun parseSettingsForm(params: Parameters, currentConfig: AppConfig): AppConfig {
        val deviationTriggerPercent =
            params.requiredSingle(FormFields.DEVIATION_TRIGGER_PERCENT, ViewText.INVALID_DEVIATION_TRIGGER)
                .requiredFiniteDouble(ViewText.INVALID_DEVIATION_TRIGGER)
        val minimumOrderSizeUSD =
            params.requiredSingle(FormFields.MINIMUM_ORDER_SIZE_USD, ViewText.INVALID_MINIMUM_ORDER_SIZE)
                .requiredFiniteDouble(ViewText.INVALID_MINIMUM_ORDER_SIZE)
        val loopDelaySeconds =
            params.requiredSingle(FormFields.LOOP_DELAY_SECONDS, ViewText.INVALID_LOOP_DELAY)
                .requiredLong(ViewText.INVALID_LOOP_DELAY)
        val fiatMaxDrawdown =
            params.requiredSingle(FormFields.FIAT_MAX_DRAWDOWN, ViewText.INVALID_FIAT_MAX_DRAWDOWN)
                .requiredFiniteDouble(ViewText.INVALID_FIAT_MAX_DRAWDOWN)
        val fiatDeploymentExponent =
            params.requiredSingle(FormFields.FIAT_DEPLOYMENT_EXPONENT, ViewText.INVALID_FIAT_DEPLOYMENT_EXPONENT)
                .requiredFiniteDouble(ViewText.INVALID_FIAT_DEPLOYMENT_EXPONENT)
        val settings =
            Settings(
                loopDelaySeconds = loopDelaySeconds,
                deviationTriggerPercent = deviationTriggerPercent,
                minimumOrderSizeUSD = minimumOrderSizeUSD,
                dryRun = params[FormFields.DRY_RUN] != null,
                simulation = params[FormFields.SIMULATION] != null,
                fiatMaxDrawdown = fiatMaxDrawdown,
                fiatDeploymentExponent = fiatDeploymentExponent,
            )

        val symbols = params.getAll(FormFields.SYMBOLS).orEmpty()
        val targets = params.getAll(FormFields.TARGETS).orEmpty()
        val colors = params.getAll(FormFields.COLORS).orEmpty()
        require(symbols.isNotEmpty() && symbols.size == targets.size && symbols.size == colors.size) {
            ViewText.INVALID_ALLOCATION_FIELDS
        }

        val allocations =
            symbols.mapIndexed { index, symbol ->
                val target = targets[index].requiredFiniteDouble(ViewText.INVALID_ALLOCATION_TARGET)
                val rawColor = colors.getOrNull(index)
                val color = AssetColorAssigner.normalizeHex(rawColor)
                require(rawColor.isNullOrBlank() || color != null) {
                    ViewText.INVALID_ALLOCATION_COLOR
                }
                Allocation(symbol, target, color)
            }

        return AppConfig(
            kraken = currentConfig.kraken,
            settings = settings,
            allocations = allocations,
        )
    }

    private fun Parameters.requiredSingle(name: String, message: String): String {
        val values = getAll(name)
        require(values?.size == 1) { message }
        return values.single()
    }

    private fun String?.requiredLong(message: String): Long = requireNotNull(this?.toLongOrNull()) { message }

    private fun String?.requiredFiniteDouble(message: String): Double {
        val value = this?.toDoubleOrNull()
        require(value != null && value.isFinite()) { message }
        return value
    }

    private suspend fun RoutingContext.respondSettingsFormError(
        config: AppConfig,
        message: String,
        csrfToken: String,
        paused: Boolean,
        status: HttpStatusCode,
    ) {
        val errHtml =
            createHTML(prettyPrint = false).div {
                dashboardView.renderSettingsFormFragment(this, config, message, csrfToken, paused)
            }
        call.respondText(errHtml, ContentType.Text.Html, status)
    }

    private suspend fun RoutingContext.handleGetDashboardFragment() {
        val history = tradeHistoryService.getHistory()
        val latest = history.firstOrNull()
        val allocations = configService.getConfig().allocations

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

        val delta24h = PortfolioCalculations.compute24hDelta(latest, history)
        val unresolvedIntents = orderIntentService.getUnresolvedIntents()
        val csrfToken = CsrfProtection.issueToken(call)
        val html =
            createHTML(prettyPrint = false).div {
                dashboardView.renderDashboardFragment(
                    latest = latest,
                    history = history,
                    allocations = allocations,
                    delta24h = delta24h,
                    unresolvedIntents = unresolvedIntents,
                    csrfToken = csrfToken,
                )
            }
        call.respondText(html, ContentType.Text.Html)
    }

    private suspend fun RoutingContext.respondJson(data: Any, status: HttpStatusCode = HttpStatusCode.OK) {
        val json = objectMapper.writeValueAsString(data)
        call.respondText(json, ContentType.Application.Json, status)
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
        } catch (e: Exception) {
            // Keep non-cancellation failures local to this client session so other collectors continue.
            log.debug("SSE client stream session terminated: {}", e.message)
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

    private suspend fun RoutingContext.handleGetHistoryComparison() {
        val (from, to) = parseTimeRange(call)
        respondJson(tradeHistoryService.getRebalancerComparison(from, to).toApiDto())
    }

    private suspend fun RoutingContext.handleGetHistoryRewards() {
        val (from, to) = parseTimeRange(call)
        respondJson(tradeHistoryService.getRewardsOverTime(from, to).toApiDto())
    }

    private suspend fun RoutingContext.handlePostPause() {
        if (!requireCsrf()) return
        portfolioManager.pauseLoop()
        call.response.header(HtmxHeaders.HX_REFRESH, HtmxValues.TRUE)
        respondJson(mapOf("paused" to true))
    }

    private suspend fun RoutingContext.handlePostResume() {
        if (!requireCsrf()) return
        portfolioManager.resumeLoop()
        call.response.header(HtmxHeaders.HX_REFRESH, HtmxValues.TRUE)
        respondJson(mapOf("paused" to false))
    }

    private suspend fun RoutingContext.requireCsrf(): Boolean {
        val params = call.receiveParameters()
        if (CsrfProtection.isValid(call, params)) return true
        call.respond(HttpStatusCode.Forbidden)
        return false
    }

    private suspend fun RoutingContext.handleGetHealth() {
        val (responseMap, _) = buildHealthResponse()
        respondJson(responseMap)
    }

    private suspend fun RoutingContext.handleGetReadiness() {
        val (responseMap, ready) = buildHealthResponse()
        respondJson(responseMap, if (ready) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable)
    }

    private suspend fun RoutingContext.handleGetOrderIntents() {
        respondJson(orderIntentService.getUnresolvedIntents())
    }

    private suspend fun RoutingContext.handlePostOrderIntentResolution() {
        val params = call.receiveParameters()
        if (!CsrfProtection.isValid(call, params)) {
            call.respond(HttpStatusCode.Forbidden)
            return
        }

        val id = call.parameters["id"]?.toIntOrNull()
        if (id == null || id <= 0) {
            respondJson(mapOf("error" to "Order intent id must be a positive integer."), HttpStatusCode.BadRequest)
            return
        }

        val state = try {
            OrderIntentState.valueOf(params[FormFields.ORDER_INTENT_STATE].orEmpty().uppercase())
        } catch (_: IllegalArgumentException) {
            null
        }
        if (state == null) {
            respondJson(
                mapOf("error" to "Resolution state must be CONFIRMED or REJECTED."),
                HttpStatusCode.UnprocessableEntity,
            )
            return
        }

        try {
            val evidence = params[FormFields.ORDER_INTENT_EVIDENCE].orEmpty()
            val orderTxid = params[FormFields.ORDER_INTENT_ORDER_TXID]?.trim()?.takeIf(String::isNotEmpty)
            if (orderTxid == null) {
                orderIntentService.resolve(id = id, state = state, evidence = evidence)
            } else {
                orderIntentService.resolve(
                    id = id,
                    state = state,
                    evidence = evidence,
                    orderTxid = orderTxid,
                )
            }
            respondJson(mapOf("resolved" to true, "id" to id, "state" to state.name))
        } catch (e: IllegalArgumentException) {
            respondJson(
                mapOf("error" to (e.message ?: "Invalid order intent resolution.")),
                HttpStatusCode.UnprocessableEntity,
            )
        } catch (e: IllegalStateException) {
            respondJson(mapOf("error" to (e.message ?: "Order intent could not be resolved.")), HttpStatusCode.Conflict)
        }
    }

    private suspend fun buildHealthResponse(): Pair<Map<String, Any?>, Boolean> {
        var diagnosticsAvailable = true
        suspend fun <T> readDiagnostic(name: String, block: suspend () -> T): T? = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            diagnosticsAvailable = false
            log.warn("Health diagnostic failed: {}", name, e)
            null
        }

        val stats = readDiagnostic("history stats") { tradeHistoryService.getHistoryStats() }
        val latestSnapshot = readDiagnostic("latest snapshot") { tradeHistoryService.getLatestSnapshot() }
        val paused = readDiagnostic("loop pause state") { portfolioManager.isLoopPaused() } ?: false
        val loopRunning = readDiagnostic("loop running state") { portfolioManager.isLoopRunning() } ?: false
        val cycleStatus = readDiagnostic("cycle status") { portfolioManager.getOperationalStatus() }
            ?: RebalanceOperationalStatus()
        val unresolvedOrderIntents = readDiagnostic("order intent status") {
            orderIntentService.countUnresolvedIntents()
        }
        val legacyUnresolvedSubmissions = readDiagnostic("legacy submission status") {
            tradeHistoryService.hasPendingSubmissions()
        }
        val settings = readDiagnostic("configuration") { configService.getConfig().settings }
        val syncTime = readDiagnostic("trade sync watermark") {
            tradeHistoryService.getSyncMetadata(SyncMetadataKeys.SYNC_WATERMARK_EPOCH_SEC)
        }
            ?.toLongOrNull()
            ?.let { epochSecond ->
                try {
                    Instant.ofEpochSecond(epochSecond).toString()
                } catch (_: DateTimeException) {
                    "N/A"
                }
            }
            ?: "N/A"
        val readinessReason = when {
            paused -> HealthStatusKeys.REASON_PAUSED

            !loopRunning -> HealthStatusKeys.LOOP_NOT_RUNNING

            settings == null -> HealthStatusKeys.CONFIG_UNAVAILABLE

            !diagnosticsAvailable -> HealthStatusKeys.DIAGNOSTICS_UNAVAILABLE

            unresolvedOrderIntents == null || legacyUnresolvedSubmissions == null ->
                HealthStatusKeys.DIAGNOSTICS_UNAVAILABLE

            unresolvedOrderIntents > 0 || legacyUnresolvedSubmissions -> HealthStatusKeys.REASON_UNRESOLVED_ORDER_INTENT

            latestSnapshot == null -> HealthStatusKeys.REASON_NO_SNAPSHOT

            cycleStatus.lastCycleError != null -> HealthStatusKeys.REASON_LAST_CYCLE_FAILED

            else -> HealthStatusKeys.STATUS_READY
        }
        val ready = readinessReason == HealthStatusKeys.STATUS_READY
        val responseMap = mapOf(
            HealthStatusKeys.STATUS to HealthStatusKeys.STATUS_UP,
            HealthStatusKeys.TIMESTAMP to Instant.now().toString(),
            HealthStatusKeys.UPTIME_SECONDS to ManagementFactory.getRuntimeMXBean().uptime / 1000,
            HealthStatusKeys.TOTAL_TRADES_EXECUTED to (stats?.totalTradesExecuted ?: 0L),
            HealthStatusKeys.TOTAL_VOLUME_TRADED to (stats?.totalVolumeTraded ?: BigDecimal.ZERO),
            HealthStatusKeys.LAST_SNAPSHOT_TIME to (latestSnapshot?.timestamp?.toString() ?: "N/A"),
            HealthStatusKeys.LAST_SNAPSHOT_TOTAL_VALUE_USD to (latestSnapshot?.totalValueUSD ?: BigDecimal.ZERO),
            HealthStatusKeys.PAUSED to paused,
            HealthStatusKeys.READINESS to
                if (ready) HealthStatusKeys.STATUS_READY else HealthStatusKeys.STATUS_NOT_READY,
            HealthStatusKeys.READINESS_REASON to readinessReason,
            HealthStatusKeys.ACTIVE_MODE to when {
                settings?.simulation == true -> HealthStatusKeys.MODE_SIMULATION
                settings?.dryRun == true -> HealthStatusKeys.MODE_DRY_RUN
                settings != null -> HealthStatusKeys.MODE_LIVE
                else -> HealthStatusKeys.MODE_UNKNOWN
            },
            HealthStatusKeys.LOOP_RUNNING to loopRunning,
            HealthStatusKeys.LAST_CYCLE_STARTED_AT to (cycleStatus.lastCycleStartedAt?.toString() ?: "N/A"),
            HealthStatusKeys.LAST_CYCLE_COMPLETED_AT to (cycleStatus.lastCycleCompletedAt?.toString() ?: "N/A"),
            HealthStatusKeys.LAST_CYCLE_ERROR to (cycleStatus.lastCycleError ?: "N/A"),
            HealthStatusKeys.LAST_CYCLE_SYNC_WARNING to (cycleStatus.lastCycleSyncWarning ?: "N/A"),
            HealthStatusKeys.LAST_TRADE_SYNC_TIME to syncTime,
            HealthStatusKeys.UNRESOLVED_ORDER_INTENTS to unresolvedOrderIntents,
            HealthStatusKeys.LEGACY_UNRESOLVED_SUBMISSIONS to legacyUnresolvedSubmissions,
        )
        return responseMap to ready
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
