package com.gemini.krakenbot.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.InvalidConfigurationException
import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.domain.QualityAllocation
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.OrderIntentState
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.service.AssetColorAssigner
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.CurrentPortfolioState
import com.gemini.krakenbot.service.OrderIntentService
import com.gemini.krakenbot.service.PortfolioManager
import com.gemini.krakenbot.service.actual.ActualObservationDispatcher
import com.gemini.krakenbot.service.actual.ActualObservationPage
import com.gemini.krakenbot.service.actual.ActualObservationValuator
import com.gemini.krakenbot.service.actual.ActualPageState
import com.gemini.krakenbot.util.PrecisionConstants
import com.gemini.krakenbot.view.DashboardView
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
import com.gemini.krakenbot.view.util.modePlate
import com.gemini.krakenbot.view.util.p
import com.gemini.krakenbot.view.util.symbolColorMap
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.withContext
import kotlinx.html.body
import kotlinx.html.div
import kotlinx.html.h2
import kotlinx.html.id
import kotlinx.html.p
import kotlinx.html.stream.createHTML
import kotlinx.html.unsafe
import org.slf4j.LoggerFactory
import java.lang.management.ManagementFactory
import java.math.BigDecimal
import java.time.Instant

private const val HX_REQUEST_HEADER = "HX-Request"
private const val ERROR_FRAGMENT_HEADER = "X-Rebalancer-Error-Fragment"
private const val CSRF_TOKEN_RESPONSE_HEADER = "X-Rebalancer-CSRF-Token"
private const val CSRF_SESSION_EXPIRED_HEADER = "X-Rebalancer-CSRF-Session-Expired"
private const val SETTINGS_FORM_ERROR_FRAGMENT = "settings-form"
private const val ORDER_INTENT_ERROR_FRAGMENT = "order-intent"
private val RETIRED_HISTORY_API_PATHS = listOf(
    Routes.API_HISTORY_SNAPSHOTS,
    Routes.API_HISTORY_TRADES,
    Routes.API_HISTORY_STATS,
    Routes.API_HISTORY_SYNC_PROGRESS,
    Routes.API_HISTORY_COMPARISON,
    Routes.API_HISTORY_REWARDS,
)

class DashboardController(
    private val configService: ConfigService,
    private val objectMapper: ObjectMapper,
    private val dashboardView: DashboardView,
    private val portfolioManager: PortfolioManager,
    private val currentPortfolioState: CurrentPortfolioState = CurrentPortfolioState(),
    private val orderIntentService: OrderIntentService,
    private val actualObservationDispatcher: ActualObservationDispatcher? = null,
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
                call.response.header("Location", Routes.ACTUAL)
                call.respond(HttpStatusCode.MovedPermanently)
            }

            get(Routes.ACTUAL) {
                val config = configService.getConfig()
                val page = actualObservationDispatcher?.readCurrent(config)
                    ?: ActualObservationValuator.scopeSymbols(config).let { scope ->
                        ActualObservationPage(
                            state = ActualPageState.STORAGE_UNAVAILABLE,
                            scopeSymbols = scope,
                            scopeFingerprint = ActualObservationValuator.scopeFingerprint(scope),
                            accountReference = null,
                            observations = emptyList(),
                            stale = false,
                            lastCaptureIssue = null,
                        )
                    }
                val csrfToken = CsrfProtection.issueToken(call)
                call.respondHtml(HttpStatusCode.OK) {
                    dashboardView.renderActualObservationsPage(
                        settings = config.settings,
                        page = page,
                        csrfToken = csrfToken,
                        paused = portfolioManager.isLoopPaused(),
                    )
                }
            }

            post(Routes.ACTUAL_BENCHMARK_START) {
                if (!requireCsrf()) return@post
                val config = configService.getConfig()
                actualObservationDispatcher?.startBenchmark(config)
                val isHtmxRequest = call.request.headers[HX_REQUEST_HEADER].equals("true", ignoreCase = true)
                if (isHtmxRequest) {
                    call.response.header(HtmxHeaders.HX_REFRESH, HtmxValues.TRUE)
                    call.respond(HttpStatusCode.OK)
                } else {
                    call.response.header("Location", Routes.ACTUAL)
                    call.respond(HttpStatusCode.SeeOther)
                }
            }

            RETIRED_HISTORY_API_PATHS.forEach { path ->
                get(path) {
                    respondJson(
                        mapOf(
                            "error" to "Historical reporting was retired; use /actual for forward-only observations.",
                        ),
                        HttpStatusCode.Gone,
                    )
                }
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

        call.response.header(HtmxHeaders.HX_REDIRECT, Routes.ROOT)
        call.respond(HttpStatusCode.OK)
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

    private data class AllocationPreview(
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

    private data class AllocationRows(
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
        require(persistedValue.isFinite()) { ViewText.INVALID_ALLOCATION_SCORE }
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
            val noSnapshotHtml =
                createHTML(prettyPrint = false).div(CssClass.Loading.SpinnerContainer.value) {
                    modePlate(config.settings, outOfBand = true)
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
                )
            }
        call.respondText(html, ContentType.Text.Html)
    }

    private suspend fun RoutingContext.respondJson(data: Any, status: HttpStatusCode = HttpStatusCode.OK) {
        val json = withContext(Dispatchers.Default) { objectMapper.writeValueAsString(data) }
        call.respondText(json, ContentType.Application.Json, status)
    }

    private suspend fun ServerSSESession.handleSseStream() {
        try {
            currentPortfolioState.snapshot.filterNotNull().collect { snapshot ->
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
            return false
        }
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
