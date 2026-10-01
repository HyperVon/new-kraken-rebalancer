package com.gemini.krakenbot.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.gemini.krakenbot.api.buildSyncProgressResponse
import com.gemini.krakenbot.api.toApiDto
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.InvalidConfigurationException
import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.domain.PortfolioCalculations
import com.gemini.krakenbot.domain.QualityAllocation
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.BenchmarkMethod
import com.gemini.krakenbot.model.ComparisonProposalStatus
import com.gemini.krakenbot.model.OrderIntentState
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.model.TimeRange
import com.gemini.krakenbot.service.AssetColorAssigner
import com.gemini.krakenbot.service.ComparisonStartProposal
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.OrderIntentService
import com.gemini.krakenbot.service.PortfolioManager
import com.gemini.krakenbot.service.RebalanceOperationalStatus
import com.gemini.krakenbot.service.SettingsComparisonStatus
import com.gemini.krakenbot.service.TradeHistoryService
import com.gemini.krakenbot.service.impl.history.HistoryEvidenceCoordinator
import com.gemini.krakenbot.service.impl.history.InceptionDiscoveryService
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
import com.gemini.krakenbot.view.util.HtmxHeaders
import com.gemini.krakenbot.view.util.HtmxValues
import com.gemini.krakenbot.view.util.QueryParamKeys
import com.gemini.krakenbot.view.util.Routes
import com.gemini.krakenbot.view.util.ViewText
import com.gemini.krakenbot.view.util.modePlate
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
import kotlinx.coroutines.NonCancellable
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
import java.time.DateTimeException
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private const val HX_REQUEST_HEADER = "HX-Request"
private const val ERROR_FRAGMENT_HEADER = "X-Rebalancer-Error-Fragment"
private const val CSRF_TOKEN_RESPONSE_HEADER = "X-Rebalancer-CSRF-Token"
private const val CSRF_SESSION_EXPIRED_HEADER = "X-Rebalancer-CSRF-Session-Expired"
private const val SETTINGS_FORM_ERROR_FRAGMENT = "settings-form"
private const val ORDER_INTENT_ERROR_FRAGMENT = "order-intent"

class DashboardController(
    private val tradeHistoryService: TradeHistoryService,
    private val configService: ConfigService,
    private val objectMapper: ObjectMapper,
    private val dashboardView: DashboardView,
    private val portfolioManager: PortfolioManager,
    private val orderIntentService: OrderIntentService,
    private val nowProvider: () -> Instant = Instant::now,
    private val historyEvidenceCoordinator: HistoryEvidenceCoordinator = HistoryEvidenceCoordinator(),
) {
    private val log = LoggerFactory.getLogger(DashboardController::class.java)
    private val activeProposalRequests = AtomicInteger(0)
    private val proposalRequestSequence = AtomicLong(0)

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
                // Proposal discovery can require an expensive later-candidate comparison scan;
                // render immediately and let the HTMX fragment resolve the proposal out-of-band.
                val config = configService.getConfig()
                val csrfToken = CsrfProtection.issueToken(call)
                val inceptionDisplay = tradeHistoryService.getDetectedInceptionDisplayInfo()
                call.respondHtml(HttpStatusCode.OK) {
                    dashboardView.renderSettingsPage(
                        config = config,
                        errorMessage = null,
                        csrfToken = csrfToken,
                        paused = portfolioManager.isLoopPaused(),
                        inceptionDisplay = inceptionDisplay,
                        laterStartProposal = null,
                        laterStartProposalAsync = true,
                    )
                }
            }

            post(Routes.SETTINGS) {
                handlePostSettings()
            }

            get(Routes.FRAGMENT_SETTINGS_PROPOSAL) {
                handleGetSettingsProposalFragment()
            }

            post(Routes.FRAGMENT_SETTINGS_ALLOCATIONS_PREVIEW) {
                handlePostSettingsAllocationsPreview()
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
            respondSettingsCsrfFailure(params, QualityAllocation.DEFAULT_EMPHASIS)
            return
        }
        historyEvidenceCoordinator.withLock {
            handlePostSettingsUnderEvidenceLock(params)
        }
    }

    private suspend fun RoutingContext.handlePostSettingsUnderEvidenceLock(params: Parameters) {
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
        val acceptedComparisonSnapshotId = try {
            resolveAcceptedComparisonSnapshotId(currentConfig, updatedConfig)
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

        val comparisonStartChanged = comparisonStartChanged(currentConfig, updatedConfig)
        val inceptionChanged = inceptionDateChanged(currentConfig, updatedConfig)
        val previousAcceptedComparisonSnapshotId = if (comparisonStartChanged) {
            tradeHistoryService.getSyncMetadataUnderEvidenceLock(
                SyncMetadataKeys.INCEPTION_COMPARISON_START_SNAPSHOT_ID,
            )
        } else {
            null
        }
        val previousRetentionFloor = if (inceptionChanged) {
            tradeHistoryService.getSyncMetadataUnderEvidenceLock(
                SyncMetadataKeys.INCEPTION_RETENTION_FLOOR_EPOCH_MS,
            )
        } else {
            null
        }
        var comparisonStartMetadataWriteStarted = false
        var retentionFloorMetadataWriteStarted = false
        try {
            // Publish the exact identity before the config file. If config persistence fails, the
            // old config remains authoritative. The catch block restores the old metadata so a
            // failed update cannot leave the stores describing different accepted anchors.
            if (comparisonStartChanged) {
                comparisonStartMetadataWriteStarted = true
                tradeHistoryService.setSyncMetadataUnderEvidenceLock(
                    SyncMetadataKeys.INCEPTION_COMPARISON_START_SNAPSHOT_ID,
                    acceptedComparisonSnapshotId?.toString().orEmpty(),
                )
            }
            if (inceptionChanged) {
                configuredRetentionFloorEpochMs(updatedConfig)?.toString()?.let { retentionFloor ->
                    retentionFloorMetadataWriteStarted = true
                    tradeHistoryService.setSyncMetadataUnderEvidenceLock(
                        SyncMetadataKeys.INCEPTION_RETENTION_FLOOR_EPOCH_MS,
                        retentionFloor,
                    )
                }
            }
            configService.updateConfig(updatedConfig)
        } catch (cancelled: CancellationException) {
            val restoreFailure = restoreSettingsMetadata(
                comparisonStartMetadataWriteStarted = comparisonStartMetadataWriteStarted,
                previousAcceptedComparisonSnapshotId = previousAcceptedComparisonSnapshotId,
                retentionFloorMetadataWriteStarted = retentionFloorMetadataWriteStarted,
                previousRetentionFloor = previousRetentionFloor,
            )
            if (restoreFailure != null) {
                cancelled.addSuppressed(restoreFailure)
                log.error("Failed to restore settings metadata after cancellation", restoreFailure)
            }
            throw cancelled
        } catch (e: Exception) {
            val restoreFailure = restoreSettingsMetadata(
                comparisonStartMetadataWriteStarted = comparisonStartMetadataWriteStarted,
                previousAcceptedComparisonSnapshotId = previousAcceptedComparisonSnapshotId,
                retentionFloorMetadataWriteStarted = retentionFloorMetadataWriteStarted,
                previousRetentionFloor = previousRetentionFloor,
            )
            if (restoreFailure != null) {
                e.addSuppressed(restoreFailure)
                log.error("Failed to restore settings metadata after persistence failure", restoreFailure)
                throw e
            }
            if (e is InvalidConfigurationException) {
                // updateConfig rejected the parsed settings: the error fragment must render the
                // last server-saved state, not the rejected-but-parsed one, so any proposal
                // affordance stays consistent with what the service actually holds.
                respondSettingsFormError(
                    config = currentConfig,
                    message = e.message ?: ViewText.INVALID_CONFIGURATION_FALLBACK,
                    csrfToken = CsrfProtection.currentToken(call),
                    paused = portfolioManager.isLoopPaused(),
                    status = HttpStatusCode.UnprocessableEntity,
                )
                return
            } else {
                throw e
            }
        }
        call.response.header(HtmxHeaders.HX_REDIRECT, Routes.ROOT)
        call.respond(HttpStatusCode.OK)
    }

    private suspend fun restoreAcceptedComparisonSnapshotId(previousSnapshotId: String?): Exception? = try {
        withContext(NonCancellable) {
            tradeHistoryService.setSyncMetadataUnderEvidenceLock(
                SyncMetadataKeys.INCEPTION_COMPARISON_START_SNAPSHOT_ID,
                previousSnapshotId.orEmpty(),
            )
        }
        null
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (restoreFailure: Exception) {
        restoreFailure
    }

    private suspend fun restoreSettingsMetadata(
        comparisonStartMetadataWriteStarted: Boolean,
        previousAcceptedComparisonSnapshotId: String?,
        retentionFloorMetadataWriteStarted: Boolean,
        previousRetentionFloor: String?,
    ): Exception? {
        val failures = mutableListOf<Exception>()
        if (comparisonStartMetadataWriteStarted) {
            restoreAcceptedComparisonSnapshotId(previousAcceptedComparisonSnapshotId)?.let(failures::add)
        }
        if (retentionFloorMetadataWriteStarted) {
            restoreRetentionFloor(previousRetentionFloor)?.let(failures::add)
        }
        val firstFailure = failures.firstOrNull() ?: return null
        failures.drop(1).forEach(firstFailure::addSuppressed)
        return firstFailure
    }

    private suspend fun restoreRetentionFloor(previousFloor: String?): Exception? = try {
        withContext(NonCancellable) {
            tradeHistoryService.setSyncMetadataUnderEvidenceLock(
                SyncMetadataKeys.INCEPTION_RETENTION_FLOOR_EPOCH_MS,
                previousFloor.orEmpty(),
            )
        }
        null
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (restoreFailure: Exception) {
        restoreFailure
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
        val fiatMaxDrawdown =
            params.requiredSingle(FormFields.FIAT_MAX_DRAWDOWN, ViewText.INVALID_FIAT_MAX_DRAWDOWN)
                .requiredFiniteDouble(ViewText.INVALID_FIAT_MAX_DRAWDOWN)
        val fiatDeploymentExponent =
            params.requiredSingle(FormFields.FIAT_DEPLOYMENT_EXPONENT, ViewText.INVALID_FIAT_DEPLOYMENT_EXPONENT)
                .requiredFiniteDouble(ViewText.INVALID_FIAT_DEPLOYMENT_EXPONENT)
        val fiatDeploymentThresholdPercent =
            params[FormFields.FIAT_DEPLOYMENT_THRESHOLD_PERCENT]?.trim()?.takeIf(String::isNotBlank)?.let {
                it.toDoubleOrNull()?.takeIf { d -> d.isFinite() && d >= 0.0 && d <= 100.0 }
                    ?: throw IllegalArgumentException(ViewText.INVALID_FIAT_DEPLOYMENT_THRESHOLD)
            } ?: 0.0
        val inceptionDate = params[FormFields.INCEPTION_DATE]?.trim()?.takeIf(String::isNotBlank)?.let { dateStr ->
            InceptionDiscoveryService.parseInceptionDate(dateStr)
                ?: throw IllegalArgumentException(ViewText.INVALID_INCEPTION_DATE)
            dateStr
        }
        val comparisonStartDate =
            params[FormFields.COMPARISON_START_DATE]?.trim()?.takeIf(String::isNotBlank)?.let { dateStr ->
                val parsed = InceptionDiscoveryService.parseInceptionDate(dateStr)
                    ?: throw IllegalArgumentException(ViewText.INVALID_COMPARISON_START_DATE)
                val strategyStart = inceptionDate?.let(InceptionDiscoveryService::parseInceptionDate)
                require(strategyStart != null) { ViewText.INVALID_COMPARISON_START_DATE }
                require(!parsed.isBefore(strategyStart)) { ViewText.INVALID_COMPARISON_START_DATE }
                dateStr
            }
        val settings =
            Settings(
                loopDelaySeconds = loopDelaySeconds,
                deviationTriggerPercent = deviationTriggerPercent,
                minimumOrderSizeUSD = minimumOrderSizeUSD,
                dryRun = params[FormFields.DRY_RUN] != null,
                simulation = params[FormFields.SIMULATION] != null,
                fiatMaxDrawdown = fiatMaxDrawdown,
                fiatDeploymentExponent = fiatDeploymentExponent,
                fiatDeploymentThresholdPercent = fiatDeploymentThresholdPercent,
                inceptionDate = inceptionDate,
                comparisonStartDate = comparisonStartDate,
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

    private suspend fun resolveAcceptedComparisonSnapshotId(currentConfig: AppConfig, updatedConfig: AppConfig): Int? {
        if (!comparisonStartChanged(currentConfig, updatedConfig)) return null
        val acceptedStart = updatedConfig.settings.comparisonStartDate
            ?.let(InceptionDiscoveryService::parseInceptionDate)
            ?: return null
        val strategyStart = updatedConfig.settings.inceptionDate
            ?.let(InceptionDiscoveryService::parseInceptionDate)
            ?: throw IllegalArgumentException(ViewText.INVALID_COMPARISON_START_DATE)
        val proposal = tradeHistoryService.getComparisonStartProposalUnderEvidenceLock(strategyStart)
        require(
            proposal?.status == ComparisonProposalStatus.VERIFIED &&
                proposal.timestamp == acceptedStart &&
                proposal.snapshotId != null,
        ) { ViewText.INVALID_COMPARISON_START_DATE }
        return proposal.snapshotId
    }

    private fun comparisonStartChanged(currentConfig: AppConfig, updatedConfig: AppConfig): Boolean =
        currentConfig.settings.comparisonStartDate
            ?.let(InceptionDiscoveryService::parseInceptionDate) !=
            updatedConfig.settings.comparisonStartDate
                ?.let(InceptionDiscoveryService::parseInceptionDate)

    private fun inceptionDateChanged(currentConfig: AppConfig, updatedConfig: AppConfig): Boolean =
        currentConfig.settings.inceptionDate
            ?.let(InceptionDiscoveryService::parseInceptionDate) !=
            updatedConfig.settings.inceptionDate
                ?.let(InceptionDiscoveryService::parseInceptionDate)

    private fun configuredRetentionFloorEpochMs(config: AppConfig): Long? = config.settings.inceptionDate
        ?.let(InceptionDiscoveryService::parseInceptionDate)
        ?.takeIf { !it.isAfter(nowProvider()) }
        ?.toEpochMilli()

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
            fiatMaxDrawdown = singleValue(FormFields.FIAT_MAX_DRAWDOWN),
            fiatDeploymentExponent = singleValue(FormFields.FIAT_DEPLOYMENT_EXPONENT),
            // The settings parser treats an omitted or blank threshold as zero.
            fiatDeploymentThresholdPercent = this[FormFields.FIAT_DEPLOYMENT_THRESHOLD_PERCENT]
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?: "0.0",
            inceptionDate = this[FormFields.INCEPTION_DATE].orEmpty(),
            comparisonStartDate = this[FormFields.COMPARISON_START_DATE].orEmpty(),
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
        val inceptionDisplay = tradeHistoryService.getDetectedInceptionDisplayInfo()
        val errHtml =
            createHTML(prettyPrint = false).div {
                dashboardView.renderSettingsFormFragment(
                    this,
                    config,
                    message,
                    csrfToken,
                    paused,
                    inceptionDisplay,
                    null,
                    laterStartProposalAsync = true,
                    formValues = formValues,
                )
            }
        call.respondText(errHtml, ContentType.Text.Html, status)
    }

    /** Async slot body: resolves the comparison status without blocking the Settings page. */
    private suspend fun RoutingContext.handleGetSettingsProposalFragment() {
        val requestId = proposalRequestSequence.incrementAndGet()
        val active = activeProposalRequests.incrementAndGet()
        val startedNanos = System.nanoTime()
        try {
            if (log.isDebugEnabled) {
                log.debug("settings proposal fragment start; requestId={} active={}", requestId, active)
            }
            val config = configService.getConfig()
            val status =
                runCatching { resolveComparisonStatus(config.settings) }
                    .onFailure {
                        if (it is CancellationException) throw it
                        log.warn("Comparison baseline status resolution failed in settings fragment", it)
                    }
                    .getOrNull()
            val html =
                createHTML(prettyPrint = false).div {
                    id = HtmlIds.COMPARISON_PROPOSAL_SLOT
                    dashboardView.renderSettingsProposalFragment(
                        this,
                        status,
                        config.settings.comparisonStartDate,
                    )
                }
            call.respondText(html, ContentType.Text.Html)
            if (log.isDebugEnabled) {
                log.debug(
                    "settings proposal fragment done; requestId={} active={} elapsedMs={} availability={} proposal={}",
                    requestId,
                    activeProposalRequests.get() - 1,
                    (System.nanoTime() - startedNanos) / 1_000_000,
                    status?.comparisonAvailability,
                    status?.proposal?.status,
                )
            }
        } finally {
            activeProposalRequests.decrementAndGet()
        }
    }

    /**
     * Resolves the Settings affordance through the same comparison-availability policy as
     * History. Approved baseline readiness is only one input; later ownership/reconciliation
     * evidence can still make the comparison unavailable. The query is read-only.
     */
    private suspend fun resolveComparisonStatus(settings: Settings): SettingsComparisonStatus? {
        val anchor = settings.comparisonStartDate?.takeIf(String::isNotBlank)
            ?.let { InceptionDiscoveryService.parseInceptionDate(it) }
            ?: InceptionDiscoveryService.parseInceptionDate(settings.inceptionDate)
        return anchor?.let { tradeHistoryService.getSettingsComparisonStatus(it) }
    }

    private suspend fun RoutingContext.handleGetDashboardFragment() {
        val history = tradeHistoryService.getHistory()
        val latest = history.firstOrNull()
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

        val cutoff24h = latest.timestamp.minus(1, ChronoUnit.DAYS)
        val historyForDelta =
            if (history.any { it.timestamp <= cutoff24h }) {
                history
            } else {
                // Persisted times have millisecond precision. Advance one millisecond so the strict-before lookup
                // includes a snapshot exactly at the cutoff.
                val baseline = tradeHistoryService.getSnapshotBefore(cutoff24h.plusMillis(1))
                history + listOfNotNull(baseline)
            }
        val delta24h = PortfolioCalculations.compute24hDelta(latest, historyForDelta)
        val unresolvedIntents = orderIntentService.getUnresolvedIntents()
        val csrfToken = CsrfProtection.issueToken(call)
        val html =
            createHTML(prettyPrint = false).div {
                dashboardView.renderDashboardFragment(
                    latest = latest,
                    history = history,
                    settings = config.settings,
                    allocations = allocations,
                    delta24h = delta24h,
                    unresolvedIntents = unresolvedIntents,
                    csrfToken = csrfToken,
                    qualityScores = config.settings.qualityScores
                        .mapValues { BigDecimal.valueOf(it.value) },
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
        respondJson(
            buildSyncProgressResponse(
                seeded = seeded,
                offset = offset,
                total = total,
                recovery = tradeHistoryService.getInceptionRecoveryStatus(),
            ),
        )
    }

    private suspend fun RoutingContext.handleGetHistoryComparison() {
        val (from, to) = parseTimeRange(call)
        // The operator-facing comparison is the configuration-matched benchmark: it follows the
        // strategy's own inferred major allocation changes and otherwise holds, which is the
        // comparison that isolates routine rebalancing from asset selection. The fixed-inception
        // benchmark is selectable as a forensic reference, which prices the opportunity cost of
        // every later allocation decision rather than the value of routine rebalancing.
        val method = call.request.queryParameters["benchmark"]
            ?.let { requested ->
                BenchmarkMethod.entries.firstOrNull { it.name == requested }
            }
            ?: BenchmarkMethod.INFERRED_CONFIGURATION_MATCHED_HOLD
        respondJson(
            tradeHistoryService.getRebalancerComparison(from, to, method).toApiDto(),
        )
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

        val state = try {
            OrderIntentState.valueOf(params[FormFields.ORDER_INTENT_STATE].orEmpty().uppercase())
        } catch (_: IllegalArgumentException) {
            null
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
            HealthStatusKeys.LAST_ATH_DEFERRED_REASON to
                (cycleStatus.lastAthDeferredReason?.name ?: "N/A"),
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
