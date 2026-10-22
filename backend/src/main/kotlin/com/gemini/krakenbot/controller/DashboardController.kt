package com.gemini.krakenbot.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.InvalidConfigurationException
import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.domain.QualityAllocation
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.KrakenApiConstants
import com.gemini.krakenbot.model.OrderIntentState
import com.gemini.krakenbot.service.AssetColorAssigner
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.CurrentPortfolioState
import com.gemini.krakenbot.service.OrderIntentService
import com.gemini.krakenbot.service.PortfolioManager
import com.gemini.krakenbot.service.history.HISTORY_MAX_PAGE_LIMIT
import com.gemini.krakenbot.service.history.HISTORY_PAGE_LIMIT
import com.gemini.krakenbot.service.history.HistoryApiExportQuery
import com.gemini.krakenbot.service.history.HistoryApiPresenter
import com.gemini.krakenbot.service.history.HistoryApiRangeParams
import com.gemini.krakenbot.service.history.HistoryApiRepairParams
import com.gemini.krakenbot.service.history.HistoryApiRequestException
import com.gemini.krakenbot.service.history.HistoryBalanceCheckpoint
import com.gemini.krakenbot.service.history.HistoryCsvExporter
import com.gemini.krakenbot.service.history.HistoryEventQuery
import com.gemini.krakenbot.service.history.HistoryFeed
import com.gemini.krakenbot.service.history.HistoryLedger
import com.gemini.krakenbot.service.history.HistoryObservationRecorder
import com.gemini.krakenbot.service.history.HistoryPage
import com.gemini.krakenbot.service.history.HistoryRangePreset
import com.gemini.krakenbot.service.history.HistoryRangeSelection
import com.gemini.krakenbot.service.history.HistoryRanges
import com.gemini.krakenbot.service.history.HistoryService
import com.gemini.krakenbot.service.history.HistorySourceMode
import com.gemini.krakenbot.service.history.HistorySourceScope
import com.gemini.krakenbot.service.history.HistorySyncPurpose
import com.gemini.krakenbot.service.history.HistorySyncState
import com.gemini.krakenbot.service.history.HistorySynchronizer
import com.gemini.krakenbot.service.history.HistoryValuationCoordinator
import com.gemini.krakenbot.service.reporting.HistoryReportingService
import com.gemini.krakenbot.util.PrecisionConstants
import com.gemini.krakenbot.view.DashboardView
import com.gemini.krakenbot.view.component.HistoryPageData
import com.gemini.krakenbot.view.component.SettingsAllocationFormValue
import com.gemini.krakenbot.view.component.SettingsFormValues
import com.gemini.krakenbot.view.css.CssStyles
import com.gemini.krakenbot.view.util.AllocationEditor
import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.FormFields
import com.gemini.krakenbot.view.util.HealthStatusKeys
import com.gemini.krakenbot.view.util.HtmlIds
import com.gemini.krakenbot.view.util.HtmxAttrs
import com.gemini.krakenbot.view.util.HtmxHeaders
import com.gemini.krakenbot.view.util.HtmxValues
import com.gemini.krakenbot.view.util.Routes
import com.gemini.krakenbot.view.util.ViewText
import com.gemini.krakenbot.view.util.p
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
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.sse.ServerSSESession
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withContext
import kotlinx.html.body
import kotlinx.html.div
import kotlinx.html.id
import kotlinx.html.stream.createHTML
import kotlinx.html.unsafe
import org.slf4j.LoggerFactory
import java.lang.management.ManagementFactory
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal fun historyRangeSelection(preset: String?, start: String?, end: String?): HistoryRangeSelection? {
    val parsed = HistoryRangePreset.fromKey(preset) ?: return null
    if (parsed != HistoryRangePreset.CUSTOM) return HistoryRangeSelection.rolling(parsed)
    val customStart = start?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
    val customEnd = end?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
    return runCatching { HistoryRangeSelection.custom(customStart, customEnd) }.getOrNull()
}
private const val HX_REQUEST_HEADER = "HX-Request"
private const val ERROR_FRAGMENT_HEADER = "X-Rebalancer-Error-Fragment"
private const val CSRF_TOKEN_RESPONSE_HEADER = "X-Rebalancer-CSRF-Token"
private const val CSRF_SESSION_EXPIRED_HEADER = "X-Rebalancer-CSRF-Session-Expired"
private const val SETTINGS_FORM_ERROR_FRAGMENT = "settings-form"
private const val ORDER_INTENT_ERROR_FRAGMENT = "order-intent"
private val CSV_CONTENT_TYPE = ContentType.parse("text/csv; charset=utf-8")
class DashboardController(
    private val configService: ConfigService,
    private val objectMapper: ObjectMapper,
    private val dashboardView: DashboardView,
    private val portfolioManager: PortfolioManager,
    private val currentPortfolioState: CurrentPortfolioState = CurrentPortfolioState(),
    private val orderIntentService: OrderIntentService,
    private val historyService: HistoryService? = null,
    private val historyObservationRecorder: HistoryObservationRecorder? = null,
    private val historySynchronizer: HistorySynchronizer? = null,
    private val historyApi: HistoryApiPresenter? = null,
    private val historyReportingService: HistoryReportingService? = null,
    private val historyCsvExporter: HistoryCsvExporter? = null,
    private val historyValuationCoordinator: HistoryValuationCoordinator? = null,
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

            post(Routes.FRAGMENT_SETTINGS_ALLOCATIONS_PREVIEW) {
                handlePostSettingsAllocationsPreview()
            }

            get(Routes.FRAGMENT_DASHBOARD) {
                handleGetDashboardFragment()
            }

            get(Routes.HISTORY) {
                handleGetHistoryPage()
            }

            get(Routes.API_HISTORY_TRADES) {
                respondHistoryPage { service, digest ->
                    service.queryTrades(call.historyEventQuery(digest))
                }
            }

            get(Routes.API_HISTORY_LEDGERS) {
                respondHistoryPage { service, digest ->
                    service.queryLedgers(call.historyEventQuery(digest))
                }
            }

            get(Routes.API_HISTORY_CHECKPOINTS) {
                respondHistoryPage { service, digest ->
                    service.queryBalanceCheckpoints(call.historyEventQuery(digest))
                }
            }

            get(Routes.API_HISTORY_SYNC_STATUS) {
                val scope = historyObservationRecorder?.currentSourceScope(configService.getConfig())
                respondJson(historyService?.let { scope?.let { s -> it.getSyncStatus(s) } }.orEmpty())
            }

            get(Routes.API_HISTORY_CHARTS) {
                handleGetHistoryCharts()
            }

            get(Routes.API_HISTORY_HOLDINGS) {
                handleGetHistoryHoldings()
            }

            get(Routes.API_HISTORY_SUMMARY) {
                handleGetHistorySummary()
            }

            get(Routes.API_HISTORY_SCOPES) {
                handleGetHistoryScopes()
            }

            get(Routes.API_HISTORY_ACTIVITIES) {
                handleGetHistoryActivities()
            }

            get(Routes.API_HISTORY_EXPORT_TRADES) {
                val params = call.request.queryParameters
                streamHistoryCsv(
                    filename = "trades",
                    requiresAccount = true,
                    resolveQuery = { api, scope ->
                        api.eventExportQuery(
                            params = call.historyApiRangeParams(),
                            currentScope = scope,
                            scopeId = params["scope"],
                            modeParam = params["mode"],
                            assetKey = params["asset"],
                            pair = params["pair"],
                            type = params["type"],
                        )
                    },
                    writeCsv = { exporter, export, writeChunk ->
                        exporter.exportTrades(export.query, export.zone, writeChunk)
                    },
                )
            }

            get(Routes.API_HISTORY_EXPORT_LEDGERS) {
                val params = call.request.queryParameters
                streamHistoryCsv(
                    filename = "ledgers",
                    requiresAccount = true,
                    resolveQuery = { api, scope ->
                        api.eventExportQuery(
                            params = call.historyApiRangeParams(),
                            currentScope = scope,
                            scopeId = params["scope"],
                            modeParam = params["mode"],
                            assetKey = params["asset"],
                            pair = params["pair"],
                            type = params["type"],
                        )
                    },
                    writeCsv = { exporter, export, writeChunk ->
                        exporter.exportLedgers(export.query, export.zone, writeChunk)
                    },
                )
            }

            get(Routes.API_HISTORY_EXPORT_ACTIVITY) {
                val params = call.request.queryParameters
                streamHistoryCsv(
                    filename = "activity",
                    requiresAccount = false,
                    resolveQuery = { api, scope ->
                        api.activityExportQuery(
                            params = call.historyApiRangeParams(),
                            currentScope = scope,
                            scopeId = params["scope"],
                            modeParam = params["mode"],
                        )
                    },
                    writeCsv = { exporter, export, writeChunk ->
                        exporter.exportActivity(export.query, export.zone, writeChunk)
                    },
                )
            }

            get(Routes.API_HISTORY_EXPORT_ACCOUNT_VALUES) {
                streamHistoryCsv(
                    filename = "account-values",
                    requiresAccount = true,
                    resolveQuery = { api, scope -> api.dailyValueExportQuery(call.historyApiRangeParams(), scope) },
                    writeCsv = { exporter, export, writeChunk ->
                        exporter.exportDailyValues(export.query, writeChunk)
                    },
                )
            }

            get(Routes.API_HISTORY_EXPORT_ACCOUNT_HOLDINGS) {
                val date = call.request.queryParameters["date"].orEmpty()
                streamHistoryCsv(
                    filename = "account-holdings",
                    requiresAccount = true,
                    resolveQuery = { api, scope -> api.dailyHoldingsExportQuery(date, scope) },
                    writeCsv = { exporter, export, writeChunk ->
                        exporter.exportDailyHoldings(export.query, writeChunk)
                    },
                )
            }

            get(Routes.API_HISTORY_DATA_STATUS) {
                handleGetHistoryDataStatus()
            }

            post(Routes.API_HISTORY_REPAIR) {
                handlePostHistoryRepair()
            }

            post(Routes.API_HISTORY_SYNC_START) {
                if (!requireCsrf()) return@post
                val config = configService.getConfig()
                val scope = historyObservationRecorder?.currentSourceScope(config)
                    ?.takeIf { it.mode == HistorySourceMode.ACCOUNT && !it.accountIdentityDigest.isNullOrBlank() }
                val service = historyService
                val synchronizer = historySynchronizer
                if (config.settings.simulation || scope == null || service == null || synchronizer == null) {
                    respondJson(mapOf("error" to ViewText.HISTORY_SYNC_UNAVAILABLE), HttpStatusCode.ServiceUnavailable)
                    return@post
                }
                startHistorySync(synchronizer, service, scope)
                historyValuationCoordinator?.retryFailedWork()
                call.response.header("Location", Routes.HISTORY)
                call.respond(HttpStatusCode.SeeOther)
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
            respondSettingsCsrfFailure(params, QualityAllocation.DEFAULT_EMPHASIS)
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
                formValues = params.toSettingsFormValues(QualityAllocation.DEFAULT_EMPHASIS),
            )
            return
        }

        try {
            configService.updateConfig(updatedConfig)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: InvalidConfigurationException) {
            respondSettingsFormError(
                config = currentConfig,
                message = e.message ?: ViewText.INVALID_CONFIGURATION_FALLBACK,
                csrfToken = CsrfProtection.currentToken(call),
                paused = portfolioManager.isLoopPaused(),
                status = HttpStatusCode.UnprocessableEntity,
                formValues = params.toSettingsFormValues(QualityAllocation.DEFAULT_EMPHASIS),
            )
            return
        }

        val configPublicationPending = configService.isConfigPublicationPending()
        respondJson(
            mapOf(
                "configPublicationPending" to configPublicationPending,
                "savedAt" to Instant.now().toString(),
            ),
        )
    }

    /**
     * Recomputes allocation targets from the quality scores currently in the settings form and
     * renders the replacement rows.
     *
     * Purely a preview: nothing is persisted and no config is touched. The response only swaps
     * the open form's row list, so the operator can keep editing before saving.
     */
    private suspend fun RoutingContext.handlePostSettingsAllocationsPreview() {
        val params = call.receiveParameters()
        if (!CsrfProtection.isValid(call, params)) {
            respondSettingsCsrfFailure(params, QualityAllocation.FALLBACK_EMPHASIS)
            return
        }

        val preview = try {
            computeAllocationPreview(params)
        } catch (e: IllegalArgumentException) {
            respondAllocationsPreviewError(e.message ?: ViewText.INVALID_CONFIGURATION_FALLBACK)
            return
        }

        call.respondHtml(HttpStatusCode.OK) {
            // The trigger swaps the inner HTML of #allocations-container, so the response is that
            // container's contents. Re-wrapping them in a second element with the same id would nest
            // a duplicate id and, carrying no list class, drop the rows out of the grid into a plain
            // block.
            body {
                preview.symbols.forEachIndexed { index, symbol ->
                    val targetForSymbol = preview.computed[symbol]?.toPlainString()
                        ?: preview.targets.getOrNull(index).orEmpty()
                    unsafe {
                        +AllocationEditor.editRow(
                            symbol = symbol,
                            color = AssetColorAssigner.normalizeHex(preview.colors[index]) ?: "#888888",
                            targetPercent = targetForSymbol,
                            score = preview.scores.getOrNull(index).orEmpty(),
                        )
                    }
                }
                allocationSummary(preview)
                p(CssClass.Form.SectionSubtitle) { +ViewText.ALLOCATION_PREVIEW_WARNING }
            }
        }
    }

    /**
     * Redistributes the scored sleeve proportionally to `score^emphasis`, preserving the targets of
     * unscored assets. Pure with respect to configuration: nothing here is persisted, and every
     * rejection is an [IllegalArgumentException] carrying a `ViewText` message the caller renders.
     */
    private fun computeAllocationPreview(params: Parameters): AllocationPreview {
        val rows = params.requiredAllocationRows()
        val symbols = rows.symbols
        val targets = rows.targets
        val colors = rows.colors
        val scores = rows.scores
        val targetValues = targets.map(::requiredAllocationTarget)
        colors.forEach { requiredAllocationColor(it) }

        // An absent emphasis means "not chosen" and falls back to the flattest weighting; a supplied
        // one is rejected unless it is an in-range integer, so a typo cannot be silently absorbed
        // while an out-of-range value is refused.
        val emphasisField = params[FormFields.SCORE_EMPHASIS]?.trim().takeUnless { it.isNullOrEmpty() }
        val emphasis = if (emphasisField == null) {
            QualityAllocation.FALLBACK_EMPHASIS
        } else {
            emphasisField.toIntOrNull()
                ?: throw IllegalArgumentException(ViewText.INVALID_ALLOCATION_EMPHASIS)
        }
        require(emphasis in 1..QualityAllocation.MAX_EMPHASIS) { ViewText.INVALID_ALLOCATION_EMPHASIS }
        val requestedSleeve = params[FormFields.SCORE_SLEEVE_PERCENT]
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let(::requiredAllocationTarget)
            ?.also {
                require(it.signum() > 0 && it <= BigDecimal.valueOf(PrecisionConstants.TOTAL_ALLOCATION_PERCENTAGE)) {
                    ViewText.INVALID_ALLOCATION_TARGET
                }
            }

        val scored = mutableMapOf<String, BigDecimal>()
        for ((index, symbol) in symbols.withIndex()) {
            parseQualityScore(scores[index])?.let { scored[symbol] = it }
        }
        require(scored.isNotEmpty()) { ViewText.ALLOCATION_SCORE_REQUIRED }

        val scoredTargetTotal = symbols.indices
            .filter { symbols[it] in scored }
            .fold(BigDecimal.ZERO) { acc, i -> acc.add(targetValues[i]) }
        val usableSleeve = requestedSleeve ?: scoredTargetTotal.also {
            require(it.signum() > 0) { ViewText.INVALID_ALLOCATION_TARGET }
        }

        val computed = QualityAllocation.proportional(scored, usableSleeve, emphasis)
        val previewTotal = symbols.indices.fold(BigDecimal.ZERO) { total, index ->
            total.add(computed[symbols[index]] ?: targetValues[index])
        }
        val allowedDelta = BigDecimal.valueOf(PrecisionConstants.ALLOCATION_TOLERANCE_DELTA)
        val totalDelta = previewTotal.subtract(BigDecimal.valueOf(PrecisionConstants.TOTAL_ALLOCATION_PERCENTAGE)).abs()
        require(totalDelta <= allowedDelta) { ViewText.INVALID_ALLOCATION_TARGET }

        return AllocationPreview(
            symbols = symbols,
            targets = targets,
            colors = colors,
            scores = scores,
            scored = scored,
            computed = computed,
            // Concentration is a statement about the whole portfolio, so it is measured over every
            // leg the preview will produce: the redistributed scored sleeve plus the unscored
            // targets it preserved. Measuring it over the sleeve alone reported BTC as 86.27% of
            // "largest position" when its actual share of the book was 81.96%.
            combined = buildMap {
                symbols.forEachIndexed { index, symbol ->
                    val weight = computed[symbol] ?: targetValues[index]
                    if (weight.signum() > 0) put(symbol, weight)
                }
            },
        )
    }

    /**
     * Renders the preview's three figures as separate labelled cells rather than one run-on line.
     *
     * The quality score can only average assets that carry a score, so it is labelled as scoped to
     * the scored sleeve; the concentration figures are measured over the whole portfolio and say so.
     * They are omitted rather than crashing when nothing parses to a positive weight.
     */
    private fun kotlinx.html.FlowContent.allocationSummary(preview: AllocationPreview) {
        // No guard on an empty total: computeAllocationPreview already requires a non-empty scored
        // map and a positive sleeve, so proportional always returns positive weights and `combined`
        // is never empty. Both figures therefore have a value to report.
        val scoreValue = QualityAllocation.weightedScore(preview.computed, preview.scored).toPlainString()
        val maxWeight = QualityAllocation.maxWeightPercent(preview.combined).toPlainString() + "%"
        val bets = QualityAllocation.effectiveAssetCount(preview.combined).toPlainString()

        div(CssClass.Form.AllocationStatRow.value) {
            div(CssClass.Form.AllocationStat.value) {
                div(CssClass.Form.AllocationStatValue.value) { +scoreValue }
                div(CssClass.Form.AllocationStatLabel.value) { +ViewText.ALLOCATION_QUALITY_SCORE }
                div(CssClass.Form.AllocationStatNote.value) { +ViewText.ALLOCATION_SCORED_ONLY }
            }
            div(CssClass.Form.AllocationStat.value) {
                div(CssClass.Form.AllocationStatValue.value) { +maxWeight }
                div(CssClass.Form.AllocationStatLabel.value) { +ViewText.ALLOCATION_MAX_WEIGHT }
                div(CssClass.Form.AllocationStatNote.value) { +ViewText.ALLOCATION_WHOLE_BOOK }
            }
            div(CssClass.Form.AllocationStat.value) {
                div(CssClass.Form.AllocationStatValue.value) { +bets }
                div(CssClass.Form.AllocationStatLabel.value) { +ViewText.ALLOCATION_EFFECTIVE_BETS }
                div(CssClass.Form.AllocationStatNote.value) { +ViewText.ALLOCATION_WHOLE_BOOK }
            }
        }
    }

    /**
     * A rejected preview is a form-level error, but the trigger swaps only the allocations
     * container. Retargeting the response at the body is what puts the message where the operator
     * can see it, and mirrors the expired-CSRF path above.
     */
    private suspend fun RoutingContext.respondAllocationsPreviewError(message: String) {
        call.response.header(HtmxHeaders.HX_RESWAP, HtmxValues.INNER_HTML)
        call.response.header(HtmxHeaders.HX_RETARGET, HtmxValues.BODY)
        respondSettingsFormError(
            config = configService.getConfig(),
            message = message,
            csrfToken = CsrfProtection.currentToken(call),
            paused = portfolioManager.isLoopPaused(),
            status = HttpStatusCode.UnprocessableEntity,
        )
    }

    private class AllocationPreview(
        val symbols: List<String>,
        val targets: List<String>,
        val colors: List<String>,
        val scores: List<String>,
        val scored: Map<String, BigDecimal>,
        /** Redistributed weights for the scored sleeve only. */
        val computed: Map<String, BigDecimal>,
        /** Every leg the preview will produce, including preserved unscored targets. */
        val combined: Map<String, BigDecimal>,
    )

    private class AllocationRows(
        val symbols: List<String>,
        val targets: List<String>,
        val colors: List<String>,
        val scores: List<String>,
    )

    private fun Parameters.requiredAllocationRows(): AllocationRows {
        val symbols = getAll(FormFields.SYMBOLS).orEmpty()
        val targets = getAll(FormFields.TARGETS).orEmpty()
        val colors = getAll(FormFields.COLORS).orEmpty()
        val scores = getAll(FormFields.SCORES).orEmpty()
        require(
            symbols.isNotEmpty() && symbols.size == targets.size &&
                symbols.size == colors.size && symbols.size == scores.size,
        ) { ViewText.INVALID_ALLOCATION_FIELDS }
        require(symbols.map { Asset.canonicalSymbol(it) }.toSet().size == symbols.size) {
            ViewText.INVALID_ALLOCATION_FIELDS
        }
        require(symbols.all { Asset.isValidAllocationSymbol(it) }) { ViewText.INVALID_ALLOCATION_FIELDS }
        return AllocationRows(symbols, targets, colors, scores)
    }

    private fun requiredAllocationTarget(raw: String): BigDecimal {
        val value = raw.trim().toBigDecimalOrNull()
        require(value != null && value.signum() >= 0 && value.toDouble().isFinite()) {
            ViewText.INVALID_ALLOCATION_TARGET
        }
        return value
    }

    private fun requiredAllocationColor(raw: String): String? {
        val color = AssetColorAssigner.normalizeHex(raw)
        require(raw.isBlank() || color != null) { ViewText.INVALID_ALLOCATION_COLOR }
        return color
    }

    /** Blank and zero clear a score; every other value must match the persisted score contract. */
    private fun parseQualityScore(raw: String): BigDecimal? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val value = trimmed.toBigDecimalOrNull()
            ?: throw IllegalArgumentException(ViewText.INVALID_ALLOCATION_SCORE)
        require(value.signum() >= 0 && value <= BigDecimal.valueOf(Settings.MAX_QUALITY_SCORE)) {
            ViewText.INVALID_ALLOCATION_SCORE
        }
        val persistedValue = value.toDouble()
        return value.takeIf { persistedValue > 0.0 }
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
        val settings =
            Settings(
                loopDelaySeconds = loopDelaySeconds,
                deviationTriggerPercent = deviationTriggerPercent,
                minimumOrderSizeUSD = minimumOrderSizeUSD,
                dryRun = params[FormFields.DRY_RUN] != null,
                simulation = params[FormFields.SIMULATION] != null,
            )

        val rows = params.requiredAllocationRows()

        val allocations =
            rows.symbols.mapIndexed { index, symbol ->
                val target = requiredAllocationTarget(rows.targets[index]).toDouble()
                val color = requiredAllocationColor(rows.colors[index])
                Allocation(symbol, target, color)
            }

        val qualityScores = mutableMapOf<String, Double>()
        rows.symbols.forEachIndexed { index, symbol ->
            parseQualityScore(rows.scores[index])?.let { qualityScores[symbol] = it.toDouble() }
        }

        return AppConfig(
            kraken = currentConfig.kraken,
            settings = settings.copy(qualityScores = qualityScores),
            allocations = allocations,
        )
    }

    private fun Parameters.requiredSingle(name: String, message: String): String {
        val values = getAll(name)
        require(values?.size == 1) { message }
        return values.single()
    }

    private suspend fun RoutingContext.respondSettingsCsrfFailure(params: Parameters, defaultScoreEmphasis: Int) {
        if (!CsrfProtection.hasExplicitSameOrigin(call)) {
            call.respond(HttpStatusCode.Forbidden)
            return
        }

        val token = CsrfProtection.currentToken(call)
        call.response.header(CSRF_TOKEN_RESPONSE_HEADER, token)
        if (call.request.headers[HX_REQUEST_HEADER].equals("true", ignoreCase = true)) {
            // A browser can retain all edited controls in place while refreshing their CSRF token.
            call.response.header(CSRF_SESSION_EXPIRED_HEADER, "true")
            call.respond(HttpStatusCode.Forbidden)
            return
        }

        // Native form posts replace the document, so render the submitted values for recovery.
        respondSettingsFormError(
            config = configService.getConfig(),
            message = ViewText.CSRF_SESSION_EXPIRED,
            csrfToken = token,
            paused = portfolioManager.isLoopPaused(),
            status = HttpStatusCode.Forbidden,
            formValues = params.toSettingsFormValues(defaultScoreEmphasis),
        )
    }

    private fun Parameters.toSettingsFormValues(defaultScoreEmphasis: Int): SettingsFormValues {
        fun singleValue(name: String): String = getAll(name)?.singleOrNull().orEmpty()

        val symbols = getAll(FormFields.SYMBOLS).orEmpty()
        val targets = getAll(FormFields.TARGETS).orEmpty()
        val colors = getAll(FormFields.COLORS).orEmpty()
        val scores = getAll(FormFields.SCORES).orEmpty()
        val allocationRowCount = maxOf(symbols.size, targets.size, colors.size, scores.size)

        return SettingsFormValues(
            loopDelaySeconds = singleValue(FormFields.LOOP_DELAY_SECONDS),
            deviationTriggerPercent = singleValue(FormFields.DEVIATION_TRIGGER_PERCENT),
            minimumOrderSizeUsd = singleValue(FormFields.MINIMUM_ORDER_SIZE_USD),
            simulation = this[FormFields.SIMULATION] != null,
            dryRun = this[FormFields.DRY_RUN] != null,
            allocations = (0 until allocationRowCount).map { index ->
                SettingsAllocationFormValue(
                    symbol = symbols.getOrNull(index).orEmpty(),
                    targetPercent = targets.getOrNull(index).orEmpty(),
                    color = colors.getOrNull(index).orEmpty(),
                    score = scores.getOrNull(index).orEmpty(),
                )
            },
            // A missing preview control follows computeAllocationPreview's fallback. On a save,
            // the rendered settings page's documented default is retained instead.
            scoreEmphasis = this[FormFields.SCORE_EMPHASIS] ?: defaultScoreEmphasis.toString(),
            scoreSleevePercent = this[FormFields.SCORE_SLEEVE_PERCENT].orEmpty(),
        )
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
        formValues: SettingsFormValues? = null,
    ) {
        call.response.header(ERROR_FRAGMENT_HEADER, SETTINGS_FORM_ERROR_FRAGMENT)
        val errHtml =
            createHTML(prettyPrint = false).div {
                dashboardView.renderSettingsFormFragment(
                    this,
                    config,
                    message,
                    csrfToken,
                    paused,
                    formValues = formValues,
                )
            }
        call.respondText(errHtml, ContentType.Text.Html, status)
    }

    private suspend fun RoutingContext.handleGetDashboardFragment() {
        val dashboardState = currentPortfolioState.readDashboardView()
        val latest = dashboardState.latest
        val config = configService.getConfig()
        val allocations = config.allocations

        if (latest == null) {
            val noSnapshotHtml = createHTML(prettyPrint = false).div {
                dashboardView.renderDashboardWaitingFragment(
                    settings = config.settings,
                    recentActivities = dashboardState.recentActivities,
                    activitySourceId = dashboardState.activitySourceId,
                )
            }
            call.respondText(noSnapshotHtml, ContentType.Text.Html)
            return
        }

        val unresolvedIntents = orderIntentService.getUnresolvedIntents()
        val csrfToken = CsrfProtection.issueToken(call)
        val html =
            createHTML(prettyPrint = false).div {
                dashboardView.renderDashboardFragment(
                    latest = latest,
                    settings = config.settings,
                    allocations = allocations,
                    unresolvedIntents = unresolvedIntents,
                    csrfToken = csrfToken,
                    qualityScores = config.settings.qualityScores
                        .mapValues { BigDecimal.valueOf(it.value) },
                    recentSnapshots = dashboardState.recentSnapshots,
                    recentActivities = dashboardState.recentActivities,
                    activitySourceId = dashboardState.activitySourceId,
                )
            }
        call.respondText(html, ContentType.Text.Html)
    }

    private suspend fun RoutingContext.handleGetHistoryPage() {
        val config = configService.getConfig()
        val csrfToken = CsrfProtection.issueToken(call)
        val data = buildHistoryPageData(config)
        call.respondHtml(HttpStatusCode.OK) {
            dashboardView.renderHistoryPage(
                settings = config.settings,
                data = data,
                csrfToken = csrfToken,
                paused = portfolioManager.isLoopPaused(),
            )
        }
    }

    private suspend fun RoutingContext.buildHistoryPageData(config: AppConfig): HistoryPageData {
        val params = call.request.queryParameters
        val requestedPreset = params["preset"] ?: params["range"]
        val zone = params["tz"]?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
        val resolvedRange = (
            historyRangeSelection(requestedPreset, params["start"], params["end"])
                ?: HistoryRangeSelection.DEFAULT
            )
            .let { HistoryRanges.resolve(it, Instant.now(), zone) }
        val range = resolvedRange.selection.preset.key
        val tradesOffset = params["tradesOffset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val ledgersOffset = params["ledgersOffset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val scope = historyObservationRecorder?.currentSourceScope(config)
        val digest = scope?.accountIdentityDigest?.takeIf { it.isNotBlank() }
        val from = resolvedRange.from
        val portfolioChart = historyApi?.let { api ->
            scope?.let { sourceScope ->
                try {
                    api.charts(
                        params = call.historyApiRangeParams().copy(preset = range),
                        currentScope = sourceScope,
                        scopeId = params["scope"],
                        selectionParam = "portfolio",
                        maxPoints = null,
                        targetAllocations = config.allocations,
                    )
                } catch (_: HistoryApiRequestException) {
                    null
                }
            }
        }
        val accountHoldings = if (historyApi != null && scope?.mode == HistorySourceMode.ACCOUNT) {
            val selectedDay = portfolioChart?.series?.flatMap { it.points }
                ?.lastOrNull { it.dayLabel != null }?.dayLabel
            selectedDay?.let { day -> runCatching { historyApi.holdingsAtDate(scope, day) }.getOrNull() }
        } else {
            null
        }
        val dataStatus = historyApi?.let { api ->
            scope?.let { sourceScope ->
                try {
                    api.dataStatus(call.historyApiRangeParams().copy(preset = range), sourceScope)
                } catch (_: HistoryApiRequestException) {
                    null
                }
            }
        }
        val statistics = historyApi?.let { api ->
            scope?.let { sourceScope ->
                try {
                    api.summary(
                        params = call.historyApiRangeParams().copy(preset = range),
                        currentScope = sourceScope,
                        scopeId = params["scope"],
                        targetAllocations = config.allocations,
                    ).statistics
                } catch (_: HistoryApiRequestException) {
                    null
                }
            }
        }
        val filters = HistoryEventQuery(
            from = from,
            until = resolvedRange.until,
            accountIdentityDigest = digest,
            assetKey = params["asset"]?.trim()?.takeIf(String::isNotEmpty),
            pair = params["pair"]?.trim()?.takeIf(String::isNotEmpty),
            type = params["type"]?.trim()?.takeIf(String::isNotEmpty),
        )
        val service = historyService
        fun empty(offset: Int = 0) = HistoryPage<Nothing>(emptyList(), 0L, HISTORY_PAGE_LIMIT, offset, false)
        if (service == null || digest == null) {
            val observations = if (service != null && scope != null) {
                service.queryChartObservations(
                    sourceScope = scope,
                    from = from,
                    until = resolvedRange.until,
                )
            } else {
                emptyList()
            }
            return HistoryPageData(
                range, filters, tradesOffset, ledgersOffset, observations,
                empty(), empty(tradesOffset), empty(ledgersOffset), emptyList(), accountAvailable = false,
                statistics = statistics,
                allocations = config.allocations,
                portfolioChart = portfolioChart,
                dataStatus = dataStatus,
                accountHoldings = accountHoldings,
            )
        }
        val chartLedgerQuery = filters.copy(pair = null, type = null, limit = HISTORY_MAX_PAGE_LIMIT)
        val fundingLedgers = listOf(
            KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
            KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL,
            KrakenApiConstants.LEDGER_TYPE_DIVIDEND,
        ).flatMap { type -> service.queryAllLedgers(chartLedgerQuery.copy(type = type)) }
        val rewardLedgers = listOf(
            KrakenApiConstants.LEDGER_TYPE_STAKING,
            KrakenApiConstants.LEDGER_TYPE_DIVIDEND,
            KrakenApiConstants.LEDGER_TYPE_EARN,
            KrakenApiConstants.LEDGER_TYPE_REWARD,
            KrakenApiConstants.LEDGER_TYPE_TRANSFER,
        ).flatMap { type -> service.queryAllLedgers(chartLedgerQuery.copy(type = type)) }
        return HistoryPageData(
            range = range,
            filters = filters,
            tradesOffset = tradesOffset,
            ledgersOffset = ledgersOffset,
            observations = service.queryChartObservations(
                sourceScope = scope,
                from = from,
                until = resolvedRange.until,
            ),
            checkpoints = if (filters.assetKey == null) {
                empty()
            } else {
                service.queryAllBalanceCheckpoints(filters.copy(pair = null, type = null))
            },
            trades = service.queryTrades(filters.copy(offset = tradesOffset)),
            ledgers = service.queryLedgers(filters.copy(offset = ledgersOffset)),
            syncStatuses = service.getSyncStatus(scope),
            accountAvailable = true,
            filterOptions = service.queryFilterOptions(filters),
            fundingSeries = historyReportingService?.fundingQuantitySeries(fundingLedgers).orEmpty(),
            rewardSeries = historyReportingService?.rewardQuantitySeries(rewardLedgers).orEmpty(),
            statistics = statistics,
            allocations = config.allocations,
            portfolioChart = portfolioChart,
            dataStatus = dataStatus,
            accountHoldings = accountHoldings,
        )
    }

    private suspend fun HistoryService.queryAllLedgers(query: HistoryEventQuery): List<HistoryLedger> {
        val ledgers = mutableListOf<HistoryLedger>()
        var offset = 0
        while (true) {
            val page = queryLedgers(query.copy(limit = HISTORY_MAX_PAGE_LIMIT, offset = offset))
            ledgers.addAll(page.items)
            if (!page.hasMore || page.items.isEmpty()) return ledgers
            offset += page.items.size
        }
    }

    private suspend fun HistoryService.queryAllBalanceCheckpoints(
        query: HistoryEventQuery,
    ): HistoryPage<HistoryBalanceCheckpoint> {
        val checkpoints = mutableListOf<HistoryBalanceCheckpoint>()
        var total: Long? = null
        var offset = 0
        while (true) {
            val page = queryBalanceCheckpoints(
                query.copy(limit = HISTORY_MAX_PAGE_LIMIT, offset = offset),
            )
            if (total == null) total = page.total
            checkpoints.addAll(page.items)
            if (!page.hasMore || page.items.isEmpty()) {
                return HistoryPage(
                    items = checkpoints,
                    total = total ?: checkpoints.size.toLong(),
                    limit = HISTORY_MAX_PAGE_LIMIT,
                    offset = 0,
                    hasMore = false,
                )
            }
            offset += page.items.size
        }
    }

    private suspend fun RoutingContext.respondHistoryPage(query: suspend (HistoryService, String) -> HistoryPage<*>) {
        val service = historyService
        val digest = historyObservationRecorder?.currentSourceScope(configService.getConfig())
            ?.accountIdentityDigest
            ?.takeIf { it.isNotBlank() }
        if (service == null || digest == null) {
            respondJson(mapOf("error" to ViewText.HISTORY_ACCOUNT_UNAVAILABLE), HttpStatusCode.ServiceUnavailable)
            return
        }
        respondJson(query(service, digest))
    }

    private fun ApplicationCall.historyEventQuery(digest: String): HistoryEventQuery {
        val params = request.queryParameters
        fun instant(name: String) = params[name]?.let { runCatching { Instant.parse(it) }.getOrNull() }
        return HistoryEventQuery(
            from = instant("from"),
            until = instant("until"),
            accountIdentityDigest = digest,
            assetKey = params["asset"]?.trim()?.takeIf(String::isNotEmpty),
            pair = params["pair"]?.trim()?.takeIf(String::isNotEmpty),
            type = params["type"]?.trim()?.takeIf(String::isNotEmpty),
            limit = (params["limit"]?.toIntOrNull() ?: HISTORY_PAGE_LIMIT).coerceIn(1, HISTORY_MAX_PAGE_LIMIT),
            offset = params["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
        )
    }

    private fun ApplicationCall.historyApiRangeParams(): HistoryApiRangeParams {
        val params = request.queryParameters
        return HistoryApiRangeParams(
            preset = params["preset"],
            start = params["start"],
            end = params["end"],
            from = params["from"],
            to = params["to"],
            tz = params["tz"],
            baselineFrom = params["baselineFrom"],
            valueAsset = params["valueAsset"],
        )
    }

    private suspend fun RoutingContext.withHistoryApi(
        block: suspend (HistoryApiPresenter, HistorySourceScope) -> Any,
    ) {
        val api = historyApi
        val scope = historyObservationRecorder?.currentSourceScope(configService.getConfig())
        if (api == null || scope == null) {
            respondJson(mapOf("error" to ViewText.HISTORY_ACCOUNT_UNAVAILABLE), HttpStatusCode.ServiceUnavailable)
            return
        }
        try {
            respondJson(block(api, scope))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: HistoryApiRequestException) {
            respondJson(mapOf("error" to (error.message ?: "")), HttpStatusCode.BadRequest)
        }
    }

    private suspend fun <T> RoutingContext.streamHistoryCsv(
        filename: String,
        requiresAccount: Boolean,
        resolveQuery: suspend (HistoryApiPresenter, HistorySourceScope) -> HistoryApiExportQuery<T>,
        writeCsv: suspend (
            HistoryCsvExporter,
            HistoryApiExportQuery<T>,
            suspend (String) -> Unit,
        ) -> Unit,
    ) {
        val config = configService.getConfig()
        val scope = historyObservationRecorder?.currentSourceScope(config)
        val api = historyApi
        val exporter = historyCsvExporter
        val accountAvailable = scope?.let {
            it.mode == HistorySourceMode.ACCOUNT && !it.accountIdentityDigest.isNullOrBlank()
        } == true
        if (api == null || exporter == null || scope == null ||
            (requiresAccount && (!accountAvailable || historyService == null))
        ) {
            respondJson(mapOf("error" to ViewText.HISTORY_ACCOUNT_UNAVAILABLE), HttpStatusCode.ServiceUnavailable)
            return
        }

        val exportQuery = try {
            resolveQuery(api, scope)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: HistoryApiRequestException) {
            respondJson(mapOf("error" to (error.message ?: "")), HttpStatusCode.BadRequest)
            return
        }

        call.response.header("Content-Disposition", "attachment; filename=\"history-$filename.csv\"")
        call.respondBytesWriter(CSV_CONTENT_TYPE, HttpStatusCode.OK) {
            writeCsv(exporter, exportQuery) { chunk -> writeStringUtf8(chunk) }
        }
    }

    private suspend fun RoutingContext.handleGetHistoryCharts() {
        withHistoryApi { api, scope ->
            val query = call.request.queryParameters
            api.charts(
                params = call.historyApiRangeParams(),
                currentScope = scope,
                selectionParam = query["selection"],
                scopeId = query["scope"],
                maxPoints = query["maxPoints"]?.toIntOrNull(),
                targetAllocations = configService.getConfig().allocations,
            )
        }
    }

    private suspend fun RoutingContext.handleGetHistoryHoldings() {
        withHistoryApi { api, scope ->
            api.holdingsAtDate(scope, call.request.queryParameters["date"].orEmpty())
        }
    }

    private suspend fun RoutingContext.handleGetHistorySummary() {
        withHistoryApi { api, scope ->
            api.summary(
                params = call.historyApiRangeParams(),
                currentScope = scope,
                scopeId = call.request.queryParameters["scope"],
                targetAllocations = configService.getConfig().allocations,
            )
        }
    }

    private suspend fun RoutingContext.handleGetHistoryScopes() {
        withHistoryApi { api, scope -> api.scopes(scope) }
    }

    private suspend fun RoutingContext.handleGetHistoryActivities() {
        withHistoryApi { api, scope ->
            val query = call.request.queryParameters
            api.activities(
                params = call.historyApiRangeParams(),
                currentScope = scope,
                scopeId = query["scope"],
                modeParam = query["mode"],
                cursor = query["cursor"],
                limit = query["limit"]?.toIntOrNull(),
            )
        }
    }

    private suspend fun RoutingContext.handleGetHistoryDataStatus() {
        withHistoryApi { api, scope -> api.dataStatus(call.historyApiRangeParams(), scope) }
    }

    private suspend fun RoutingContext.handlePostHistoryRepair() {
        val params = requireCsrfParams() ?: return
        val config = configService.getConfig()
        val api = historyApi
        val scope = historyObservationRecorder?.currentSourceScope(config)
            ?.takeIf { it.mode == HistorySourceMode.ACCOUNT && !it.accountIdentityDigest.isNullOrBlank() }
        val synchronizer = historySynchronizer
        if (api == null || scope == null || synchronizer == null || config.settings.simulation) {
            respondJson(mapOf("error" to ViewText.HISTORY_SYNC_UNAVAILABLE), HttpStatusCode.ServiceUnavailable)
            return
        }
        fun field(name: String): String? = params[name] ?: call.request.queryParameters[name]
        val window = try {
            api.resolveRepairWindow(
                HistoryApiRepairParams(
                    date = field("date"),
                    start = field("start"),
                    end = field("end"),
                    tz = field("tz"),
                ),
            )
        } catch (error: HistoryApiRequestException) {
            respondJson(mapOf("error" to (error.message ?: "")), HttpStatusCode.BadRequest)
            return
        }
        HistoryFeed.entries.forEach { feed ->
            synchronizer.startSync(
                feed = feed,
                startSec = window.start.epochSecond,
                endSec = window.end.epochSecond,
                purpose = HistorySyncPurpose.REPAIR,
            )
        }
        respondJson(
            mapOf(
                "accepted" to true,
                "startIso" to window.start.toString(),
                "endIso" to window.end.toString(),
                "feeds" to HistoryFeed.entries.map(HistoryFeed::name),
            ),
        )
    }

    private suspend fun startHistorySync(
        synchronizer: HistorySynchronizer,
        service: HistoryService,
        scope: HistorySourceScope,
    ) {
        val existing = service.getSyncStatus(scope).associateBy { it.feed }
        val now = Instant.now().epochSecond
        HistoryFeed.entries.forEach { feed ->
            val status = existing[feed]
            val resumeWindow = status?.takeIf {
                it.state in setOf(
                    HistorySyncState.RUNNING,
                    HistorySyncState.PAUSED,
                ) && it.windowStart != null && it.windowEnd != null
            }
            val start = resumeWindow?.windowStart?.epochSecond ?: 0L
            val end = resumeWindow?.windowEnd?.epochSecond ?: now
            synchronizer.startSync(feed, start, end)
        }
    }

    private suspend fun RoutingContext.respondJson(data: Any, status: HttpStatusCode = HttpStatusCode.OK) {
        val json = withContext(Dispatchers.Default) { objectMapper.writeValueAsString(data) }
        call.respondText(json, ContentType.Application.Json, status)
    }

    private suspend fun ServerSSESession.handleSseStream() {
        try {
            merge(
                currentPortfolioState.snapshot.filterNotNull().map { objectMapper.writeValueAsString(it) },
                currentPortfolioState.activityUpdates.filterNotNull().map { it.cycleId },
            ).collect { data ->
                send(ServerSentEvent(data = data))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Keep non-cancellation failures local to this client session so other collectors continue.
            log.debug("SSE client stream session terminated: {}", e.message)
        }
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

    private suspend fun RoutingContext.requireCsrf(): Boolean = requireCsrfParams() != null

    private suspend fun RoutingContext.requireCsrfParams(): Parameters? {
        val params = call.receiveParameters()
        if (CsrfProtection.isValid(call, params)) return params
        if (
            call.request.headers[HX_REQUEST_HEADER].equals("true", ignoreCase = true) &&
            CsrfProtection.hasExplicitSameOrigin(call)
        ) {
            val token = CsrfProtection.currentToken(call)
            call.response.header(CSRF_TOKEN_RESPONSE_HEADER, token)
            call.response.header(CSRF_SESSION_EXPIRED_HEADER, "true")
            // The request is rejected. The browser updates its hidden token and explains that the
            // operator must retry, including when an expired cookie and an old form token differ.
            call.respond(HttpStatusCode.Forbidden)
            return null
        }
        call.respond(HttpStatusCode.Forbidden)
        return null
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
            val isHtmxRequest = call.request.headers[HX_REQUEST_HEADER].equals("true", ignoreCase = true)
            if (isHtmxRequest && !CsrfProtection.hasExplicitSameOrigin(call)) {
                call.respond(HttpStatusCode.Forbidden)
                return
            }
            if (isHtmxRequest) {
                val staleTokenPair = CsrfProtection.isRefreshableStaleTokenPair(call, params)
                val token = CsrfProtection.currentToken(call)
                call.response.header(CSRF_TOKEN_RESPONSE_HEADER, token)
                if (staleTokenPair) {
                    call.response.header(CSRF_SESSION_EXPIRED_HEADER, "true")
                }
            }
            respondOrderIntentError(ViewText.CSRF_SESSION_EXPIRED, HttpStatusCode.Forbidden)
            return
        }

        val id = call.parameters["id"]?.toIntOrNull()
        if (id == null || id <= 0) {
            respondOrderIntentError(
                "Order intent id must be a positive integer.",
                HttpStatusCode.BadRequest,
            )
            return
        }

        val parsedState = try {
            OrderIntentState.valueOf(params[FormFields.ORDER_INTENT_STATE].orEmpty().uppercase())
        } catch (_: IllegalArgumentException) {
            null
        }
        val state = parsedState?.takeIf {
            it == OrderIntentState.CONFIRMED || it == OrderIntentState.REJECTED
        }
        if (state == null) {
            respondOrderIntentError(
                "Resolution state must be CONFIRMED or REJECTED.",
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
            if (call.request.headers[HX_REQUEST_HEADER].equals("true", ignoreCase = true)) {
                call.response.header(HtmxHeaders.HX_REFRESH, HtmxValues.TRUE)
                call.respond(HttpStatusCode.OK)
            } else {
                respondJson(mapOf("resolved" to true, "id" to id, "state" to state.name))
            }
        } catch (e: IllegalArgumentException) {
            respondOrderIntentError(
                e.message ?: "Invalid order intent resolution.",
                HttpStatusCode.UnprocessableEntity,
            )
        } catch (e: IllegalStateException) {
            respondOrderIntentError(
                e.message ?: "Order intent could not be resolved.",
                HttpStatusCode.Conflict,
            )
        }
    }

    private suspend fun RoutingContext.respondOrderIntentError(message: String, status: HttpStatusCode) {
        if (!call.request.headers[HX_REQUEST_HEADER].equals("true", ignoreCase = true)) {
            respondJson(mapOf("error" to message), status)
            return
        }

        call.response.header(ERROR_FRAGMENT_HEADER, ORDER_INTENT_ERROR_FRAGMENT)
        call.response.header(HtmxHeaders.HX_RESWAP, HtmxValues.SWAP_OUTER_HTML)
        call.response.header(HtmxHeaders.HX_RETARGET, "#${HtmlIds.ORDER_INTENT_FEEDBACK}")
        val html = createHTML(prettyPrint = false).div(CssClass.Utility.ErrorBanner.value) {
            id = HtmlIds.ORDER_INTENT_FEEDBACK
            +message
        }
        call.respondText(html, ContentType.Text.Html, status)
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

        val paused = readDiagnostic("loop pause state") { portfolioManager.isLoopPaused() } ?: false
        val loopRunning = readDiagnostic("loop running state") { portfolioManager.isLoopRunning() } ?: false
        val cycleStatus = readDiagnostic("cycle status") { portfolioManager.getOperationalStatus() }
        val unresolvedOrderIntents = readDiagnostic("order intent status") {
            orderIntentService.countUnresolvedIntents()
        }
        val settings = readDiagnostic("configuration") { configService.getConfig().settings }
        val latestSnapshot = currentPortfolioState.snapshot.value
        val readinessReason = when {
            paused -> HealthStatusKeys.REASON_PAUSED

            !loopRunning -> HealthStatusKeys.LOOP_NOT_RUNNING

            settings == null -> HealthStatusKeys.CONFIG_UNAVAILABLE

            !diagnosticsAvailable || cycleStatus == null || unresolvedOrderIntents == null ->
                HealthStatusKeys.DIAGNOSTICS_UNAVAILABLE

            unresolvedOrderIntents > 0 -> HealthStatusKeys.REASON_UNRESOLVED_ORDER_INTENT

            latestSnapshot == null -> HealthStatusKeys.REASON_NO_SNAPSHOT

            cycleStatus.lastCycleError != null -> HealthStatusKeys.REASON_LAST_CYCLE_FAILED

            else -> HealthStatusKeys.STATUS_READY
        }
        val ready = readinessReason == HealthStatusKeys.STATUS_READY
        return mapOf(
            HealthStatusKeys.STATUS to HealthStatusKeys.STATUS_UP,
            HealthStatusKeys.TIMESTAMP to Instant.now().toString(),
            HealthStatusKeys.UPTIME_SECONDS to ManagementFactory.getRuntimeMXBean().uptime / 1000,
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
            HealthStatusKeys.LAST_CYCLE_STARTED_AT to (cycleStatus?.lastCycleStartedAt?.toString() ?: "N/A"),
            HealthStatusKeys.LAST_CYCLE_COMPLETED_AT to (cycleStatus?.lastCycleCompletedAt?.toString() ?: "N/A"),
            HealthStatusKeys.LAST_CYCLE_ERROR to (cycleStatus?.lastCycleError ?: "N/A"),
            HealthStatusKeys.UNRESOLVED_ORDER_INTENTS to unresolvedOrderIntents,
        ) to ready
    }
}
