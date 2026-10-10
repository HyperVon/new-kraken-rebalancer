package com.gemini.krakenbot.view.component

import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.service.actual.ActualAssetStatus
import com.gemini.krakenbot.service.actual.ActualObservation
import com.gemini.krakenbot.service.actual.ActualObservationPage
import com.gemini.krakenbot.service.actual.ActualObservationStatus
import com.gemini.krakenbot.service.actual.ActualPageState
import com.gemini.krakenbot.service.actual.benchmark.BenchmarkComparisonPoint
import com.gemini.krakenbot.service.actual.benchmark.BenchmarkComparisonResult
import com.gemini.krakenbot.service.actual.benchmark.BenchmarkStatus
import com.gemini.krakenbot.view.util.ActiveNav
import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.FormFields
import com.gemini.krakenbot.view.util.HtmxAttrs
import com.gemini.krakenbot.view.util.Routes
import com.gemini.krakenbot.view.util.ViewText
import com.gemini.krakenbot.view.util.brandWithMode
import com.gemini.krakenbot.view.util.button
import com.gemini.krakenbot.view.util.commonMetadataAndStyles
import com.gemini.krakenbot.view.util.div
import com.gemini.krakenbot.view.util.h2
import com.gemini.krakenbot.view.util.loopControl
import com.gemini.krakenbot.view.util.p
import com.gemini.krakenbot.view.util.primaryNav
import com.gemini.krakenbot.view.util.span
import com.gemini.krakenbot.view.util.td
import com.gemini.krakenbot.view.util.th
import kotlinx.html.ButtonType
import kotlinx.html.DIV
import kotlinx.html.FormMethod
import kotlinx.html.HTML
import kotlinx.html.InputType
import kotlinx.html.body
import kotlinx.html.form
import kotlinx.html.head
import kotlinx.html.header
import kotlinx.html.input
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.thead
import kotlinx.html.title
import kotlinx.html.tr
import kotlinx.html.unsafe
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.util.Locale

class ActualObservationsPageComponent {
    context(html: HTML)
    fun render(settings: Settings, page: ActualObservationPage, paused: Boolean = false, csrfToken: String? = null) {
        html.head {
            commonMetadataAndStyles()
            title("${ViewText.ACTUAL_TITLE} - ${ViewText.APP_TITLE}")
        }
        html.body {
            div(CssClass.Layout.Container + CssClass.Actual.Content) {
                header {
                    brandWithMode(settings) { loopControl(paused, csrfToken) }
                    div(CssClass.Layout.HeaderActions) { primaryNav(ActiveNav.ACTUAL) }
                }
                p(CssClass.Actual.Intro) { +ViewText.ACTUAL_INTRO }
                renderLatest(page)
                renderBenchmark(page, csrfToken)
                renderChart(page.observations, settings.loopDelaySeconds, page.benchmark)
                renderAssets(page.observations.lastOrNull())
            }
        }
    }

    private fun DIV.renderLatest(page: ActualObservationPage) {
        val latest = page.observations.lastOrNull()
        val latestComplete = page.observations.lastOrNull { it.status == ActualObservationStatus.COMPLETE }
        div(CssClass.Layout.GlassPanel) {
            h2(CssClass.Utility.GlassPanelTitle) { +ViewText.ACTUAL_LATEST_HEADING }
            div(CssClass.Actual.SummaryGrid) {
                summaryCard(
                    ViewText.ACTUAL_VALUE_LABEL,
                    latestComplete?.totalUsd
                        ?.let(::formatUsdExact) ?: ViewText.ACTUAL_NO_VALUE,
                )
                summaryCard(
                    ViewText.ACTUAL_VALUE_CAPTURED_AT,
                    latestComplete?.observedAt?.let(::formatInstant) ?: ViewText.EM_DASH,
                )
                summaryCard(
                    ViewText.ACTUAL_PRICE_CAPTURED_AT,
                    latestComplete?.let {
                        "${formatInstant(it.priceRequestStartedAt)} – ${formatInstant(it.priceResponseEndedAt)}"
                    } ?: ViewText.EM_DASH,
                )
                summaryCard(ViewText.ACTUAL_OBSERVED_AT, latest?.observedAt?.let(::formatInstant) ?: ViewText.EM_DASH)
                val freshnessText = when {
                    latest == null -> ViewText.EM_DASH
                    page.stale -> ViewText.ACTUAL_STALE
                    else -> ViewText.ACTUAL_FRESH
                }
                val freshnessClass = when {
                    latest == null -> CssClass.Actual.Muted
                    page.stale -> CssClass.Actual.Stale
                    else -> CssClass.Actual.Fresh
                }
                summaryCard(ViewText.ACTUAL_FRESHNESS, freshnessText, freshnessClass)
                summaryCard(ViewText.ACTUAL_ACCOUNT, page.accountReference ?: ViewText.EM_DASH)
                val statusText = when (latest?.status) {
                    ActualObservationStatus.COMPLETE -> ViewText.ACTUAL_COMPLETE
                    ActualObservationStatus.INCOMPLETE -> ViewText.ACTUAL_INCOMPLETE
                    null -> ViewText.EM_DASH
                }
                summaryCard(
                    ViewText.ACTUAL_OBSERVATION_STATUS,
                    statusText,
                    if (latest?.status == ActualObservationStatus.INCOMPLETE) CssClass.Actual.Stale else null,
                )
            }
            p(CssClass.Actual.Muted) {
                +"${ViewText.ACTUAL_SCOPE}: ${page.scopeSymbols.joinToString(", ").ifEmpty { ViewText.EM_DASH }}"
            }
            p(CssClass.Actual.Muted) { +ViewText.ACTUAL_SCOPE_LIMITATION }
            when (page.state) {
                ActualPageState.SIMULATION -> stateBanner(ViewText.ACTUAL_SIMULATION)

                ActualPageState.ACCOUNT_UNVERIFIED -> stateBanner(ViewText.ACTUAL_UNVERIFIED_ACCOUNT)

                ActualPageState.NO_OBSERVATIONS -> stateBanner(ViewText.ACTUAL_NO_OBSERVATIONS)

                ActualPageState.STORAGE_UNAVAILABLE -> stateBanner(ViewText.ACTUAL_STORAGE_UNAVAILABLE)

                ActualPageState.READY -> if (latest?.status == ActualObservationStatus.INCOMPLETE) {
                    stateBanner(ViewText.ACTUAL_INCOMPLETE)
                }
            }
            if (latest?.incompleteReasons?.isNotEmpty() == true) {
                p(CssClass.Actual.Incomplete) {
                    +"${ViewText.ACTUAL_INCOMPLETE_REASON_LABEL}: ${latest.incompleteReasons.joinToString("; ")}"
                }
            }
            page.lastCaptureIssue?.let { issue ->
                p(CssClass.Actual.Incomplete) { +"${ViewText.ACTUAL_CAPTURE_ISSUE_LABEL}: $issue" }
            }
        }
    }

    private fun DIV.renderBenchmark(page: ActualObservationPage, csrfToken: String?) {
        div(CssClass.Layout.GlassPanel + CssClass.Actual.BenchmarkCard) {
            h2(CssClass.Utility.GlassPanelTitle) { +ViewText.ACTUAL_BENCHMARK_HEADING }
            val benchmark = page.benchmark
            if (benchmark == null || benchmark.status == BenchmarkStatus.NO_ACTIVE_SEGMENT) {
                p(CssClass.Actual.Muted) { +ViewText.ACTUAL_BENCHMARK_STATUS_NOT_STARTED }
                if (
                    page.state == ActualPageState.READY &&
                    page.observations.any { it.status == ActualObservationStatus.COMPLETE }
                ) {
                    renderBenchmarkActionButton(ViewText.ACTUAL_BENCHMARK_START_BUTTON, csrfToken)
                }
            } else {
                div(CssClass.Actual.SummaryGrid) {
                    val statusText = when (benchmark.status) {
                        BenchmarkStatus.READY -> ViewText.ACTUAL_BENCHMARK_STATUS_TRACKING
                        BenchmarkStatus.TERMINATED -> ViewText.ACTUAL_BENCHMARK_STATUS_TERMINATED
                        BenchmarkStatus.PENDING_EVIDENCE -> ViewText.ACTUAL_BENCHMARK_STATUS_PENDING
                        BenchmarkStatus.UNAVAILABLE -> ViewText.ACTUAL_BENCHMARK_STATUS_UNAVAILABLE
                        BenchmarkStatus.NO_ACTIVE_SEGMENT -> ViewText.ACTUAL_BENCHMARK_STATUS_NOT_STARTED
                    }
                    val statusClass = when (benchmark.status) {
                        BenchmarkStatus.READY -> CssClass.Actual.Fresh
                        BenchmarkStatus.PENDING_EVIDENCE -> CssClass.Actual.Stale
                        BenchmarkStatus.TERMINATED, BenchmarkStatus.UNAVAILABLE -> CssClass.Actual.Incomplete
                        BenchmarkStatus.NO_ACTIVE_SEGMENT -> CssClass.Actual.Muted
                    }
                    summaryCard(ViewText.ACTUAL_BENCHMARK_STATUS_LABEL, statusText, statusClass)
                    summaryCard(
                        ViewText.ACTUAL_BENCHMARK_BASELINE_AT_LABEL,
                        benchmark.segment?.baselineAt?.let(::formatInstant) ?: ViewText.EM_DASH,
                    )
                    summaryCard(
                        ViewText.ACTUAL_BENCHMARK_BASELINE_VALUE_LABEL,
                        benchmark.segment?.baselineTotalUsd?.let(::formatUsdExact) ?: ViewText.EM_DASH,
                    )
                    val latestPoint = benchmark.latestPoint
                    summaryCard(
                        ViewText.ACTUAL_BENCHMARK_HOLD_VALUE_LABEL,
                        latestPoint?.holdValueUsd?.let(::formatUsdExact) ?: ViewText.EM_DASH,
                    )
                    summaryCard(
                        ViewText.ACTUAL_BENCHMARK_ACTUAL_VALUE_LABEL,
                        latestPoint?.actualValueUsd?.let(::formatUsdExact) ?: ViewText.EM_DASH,
                    )
                    val diffText = latestPoint?.let {
                        val sign = if (it.differenceUsd >= BigDecimal.ZERO) "+" else ""
                        "${formatUsdExact(it.differenceUsd)} ($sign${it.differencePercent.toPlainString()}%)"
                    } ?: ViewText.EM_DASH
                    val diffClass = when {
                        latestPoint == null -> null
                        latestPoint.differenceUsd > BigDecimal.ZERO -> CssClass.Actual.Fresh
                        latestPoint.differenceUsd < BigDecimal.ZERO -> CssClass.Actual.Incomplete
                        else -> null
                    }
                    summaryCard(ViewText.ACTUAL_BENCHMARK_DIFFERENCE_LABEL, diffText, diffClass)
                }
                benchmark.unavailableReason?.let { reason ->
                    p(CssClass.Actual.Incomplete) { +reason }
                }
                benchmark.segment?.terminationReason?.let { reason ->
                    p(CssClass.Actual.Incomplete) { +"${ViewText.ACTUAL_BENCHMARK_STATUS_TERMINATED}: $reason" }
                }
                if (benchmark.status == BenchmarkStatus.TERMINATED || benchmark.status == BenchmarkStatus.UNAVAILABLE) {
                    renderBenchmarkActionButton(ViewText.ACTUAL_BENCHMARK_RESTART_BUTTON, csrfToken)
                }
            }
        }
    }

    private fun DIV.renderBenchmarkActionButton(label: String, csrfToken: String?) {
        div(CssClass.Actual.BenchmarkActions) {
            form(action = Routes.ACTUAL_BENCHMARK_START, method = FormMethod.post) {
                attributes[HtmxAttrs.HX_POST] = Routes.ACTUAL_BENCHMARK_START
                if (csrfToken != null) {
                    input(type = InputType.hidden, name = FormFields.CSRF_TOKEN) {
                        value = csrfToken
                    }
                }
                button(CssClass.Button.Secondary, type = ButtonType.submit) {
                    +label
                }
            }
        }
    }

    private fun DIV.renderChart(
        observations: List<ActualObservation>,
        loopDelaySeconds: Long,
        benchmark: BenchmarkComparisonResult? = null,
    ) {
        div(CssClass.Layout.GlassPanel) {
            h2(CssClass.Utility.GlassPanelTitle) { +ViewText.ACTUAL_CHART_HEADING }
            val ordered = observations.sortedBy { it.observedAt }
            val values = ordered.mapNotNull { observation ->
                observation.totalUsd?.takeIf { observation.status == ActualObservationStatus.COMPLETE }
                    ?.let { observation.observedAt to it }
            }
            if (values.isEmpty()) {
                p(CssClass.Actual.Muted) {
                    +(if (observations.isEmpty()) ViewText.ACTUAL_CHART_EMPTY else ViewText.ACTUAL_CHART_NO_COMPLETE)
                }
            } else {
                if (benchmark?.points?.isNotEmpty() == true) {
                    div(CssClass.Actual.ChartLegend) {
                        div(CssClass.Actual.ChartLegendItem) {
                            span(CssClass.Actual.ChartLegendSwatchActual) {}
                            +ViewText.ACTUAL_BENCHMARK_LEGEND_ACTUAL
                        }
                        div(CssClass.Actual.ChartLegendItem) {
                            span(CssClass.Actual.ChartLegendSwatchHold) {}
                            +ViewText.ACTUAL_BENCHMARK_LEGEND_HOLD
                        }
                    }
                }
                div(CssClass.Actual.Chart) {
                    unsafe {
                        raw(
                            renderActualObservationChartSvg(
                                observations = ordered,
                                loopDelaySeconds = loopDelaySeconds,
                                benchmarkPoints = benchmark?.points.orEmpty(),
                            ),
                        )
                    }
                }
                val first = ordered.firstOrNull()?.observedAt
                val last = ordered.lastOrNull()?.observedAt
                if (first != null && last != null) {
                    p(CssClass.Actual.ChartCaption) {
                        +"${formatInstant(first)} — ${formatInstant(last)}. ${ViewText.ACTUAL_CHART_CAPTION}"
                    }
                }
            }
        }
    }

    private fun DIV.renderAssets(latest: ActualObservation?) {
        div(CssClass.Layout.GlassPanel) {
            h2(CssClass.Utility.GlassPanelTitle) { +ViewText.ACTUAL_ASSET_BREAKDOWN }
            if (latest == null || latest.assets.isEmpty()) {
                p(CssClass.Actual.Muted) { +ViewText.ACTUAL_NO_VALUE }
            } else {
                div(CssClass.Table.Wrapper) {
                    table(classes = CssClass.Actual.AssetTable.value) {
                        thead {
                            tr {
                                th { +ViewText.ACTUAL_ASSET }
                                th { +ViewText.ACTUAL_BALANCE }
                                th { +ViewText.ACTUAL_PRICE }
                                th { +ViewText.ACTUAL_MARKED_VALUE }
                                th { +ViewText.ACTUAL_ASSET_STATUS }
                            }
                        }
                        tbody {
                            latest.assets.forEach { asset ->
                                tr {
                                    td { +asset.symbol }
                                    td { +asset.quantity?.toPlainString().orDash() }
                                    td { +asset.priceUsd?.let(::formatUsdExact).orDash() }
                                    td { +asset.valueUsd?.let(::formatUsdExact).orDash() }
                                    td {
                                        div(
                                            CssClass.Actual.AssetStatus +
                                                if (asset.status == ActualAssetStatus.COMPLETE) {
                                                    CssClass.Actual.Fresh
                                                } else {
                                                    CssClass.Actual.Incomplete
                                                },
                                        ) {
                                            +asset.status.name.replace('_', ' ').lowercase(Locale.ROOT)
                                        }
                                        asset.reason?.let { reason ->
                                            p(CssClass.Actual.Muted) { +reason }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun DIV.summaryCard(label: String, value: String, valueClass: CssClass? = null) {
        div(CssClass.Actual.SummaryCard) {
            p(CssClass.Actual.SummaryLabel) { +label }
            val summaryValueClass =
                valueClass?.let { CssClass.Actual.SummaryValue + it } ?: CssClass.Actual.SummaryValue
            div(summaryValueClass) { +value }
        }
    }

    private fun DIV.stateBanner(message: String) {
        p(CssClass.Actual.StateBanner) { +message }
    }

    private fun formatInstant(value: Instant): String = value.toString()

    private fun formatUsdExact(value: BigDecimal): String {
        val scale = maxOf(2, value.scale())
        val decimal = value.setScale(scale).toPlainString()
        val (integer, fraction) = decimal.removePrefix("-").split('.', limit = 2).let {
            it[0] to it.getOrElse(1) { "" }
        }
        val groupedInteger = integer.reversed().chunked(3).joinToString(",").reversed()
        val sign = if (value.signum() < 0) "-" else ""
        return "$" + sign + groupedInteger + "." + fraction
    }

    private fun String?.orDash(): String = this ?: ViewText.EM_DASH
}

/**
 * Uses elapsed time for horizontal position and breaks the line when observations are more than two
 * configured loop delays apart. Incomplete persisted samples keep their own visible gap marker.
 */
internal fun renderActualObservationChartSvg(
    observations: List<ActualObservation>,
    loopDelaySeconds: Long,
    benchmarkPoints: List<BenchmarkComparisonPoint> = emptyList(),
): String {
    val ordered = observations.sortedBy { it.observedAt }
    val values = ordered.mapNotNull { observation ->
        observation.totalUsd?.takeIf { observation.status == ActualObservationStatus.COMPLETE }
    }
    if (values.isEmpty()) return ""

    val width = 960.0
    val height = 260.0
    val horizontalPadding = 14.0
    val verticalPadding = 12.0
    val allValues = values + benchmarkPoints.map { it.holdValueUsd }
    val min = allValues.minOrNull() ?: return ""
    val max = allValues.maxOrNull() ?: return ""
    val range = max.subtract(min)
    val first = ordered.first().observedAt
    val last = ordered.last().observedAt
    val timeSpan = Duration.between(first, last)
    val timeSpanSeconds = timeSpan.toDoubleSeconds()
    val maxConnectedGapSeconds = loopDelaySeconds.toDouble() * 2
    val points = ordered.map { observation ->
        val value = observation.totalUsd.takeIf { observation.status == ActualObservationStatus.COMPLETE }
        val x = if (timeSpan.isZero) {
            width / 2
        } else {
            val elapsed = Duration.between(first, observation.observedAt).toDoubleSeconds()
            horizontalPadding + (width - horizontalPadding * 2) * elapsed / timeSpanSeconds
        }
        val y = if (value == null || range.signum() == 0) {
            height / 2
        } else {
            height - verticalPadding -
                value.subtract(min).divide(range, 12, RoundingMode.HALF_UP).toDouble() *
                (height - verticalPadding * 2)
        }
        ChartPoint(observation.observedAt, x, y, value != null)
    }
    val segments = mutableListOf<List<ChartPoint>>()
    var segment = mutableListOf<ChartPoint>()
    points.forEach { point ->
        if (!point.complete) {
            if (segment.isNotEmpty()) segments += segment
            segment = mutableListOf()
        } else {
            val previous = segment.lastOrNull()
            if (
                previous != null &&
                Duration.between(previous.observedAt, point.observedAt).toDoubleSeconds() > maxConnectedGapSeconds
            ) {
                segments += segment
                segment = mutableListOf()
            }
            segment += point
        }
    }
    if (segment.isNotEmpty()) segments += segment

    val holdPoints = benchmarkPoints.sortedBy { it.observedAt }.map { point ->
        val x = if (timeSpan.isZero) {
            width / 2
        } else {
            val elapsed = Duration.between(first, point.observedAt).toDoubleSeconds()
            horizontalPadding + (width - horizontalPadding * 2) * elapsed / timeSpanSeconds
        }
        val y = if (range.signum() == 0) {
            height / 2
        } else {
            height - verticalPadding -
                point.holdValueUsd.subtract(min).divide(range, 12, RoundingMode.HALF_UP).toDouble() *
                (height - verticalPadding * 2)
        }
        ChartPoint(point.observedAt, x, y, true)
    }
    val holdSegments = mutableListOf<List<ChartPoint>>()
    var holdSegment = mutableListOf<ChartPoint>()
    holdPoints.forEach { point ->
        val previous = holdSegment.lastOrNull()
        if (
            previous != null &&
            Duration.between(previous.observedAt, point.observedAt).toDoubleSeconds() > maxConnectedGapSeconds
        ) {
            holdSegments += holdSegment
            holdSegment = mutableListOf()
        }
        holdSegment += point
    }
    if (holdSegment.isNotEmpty()) holdSegments += holdSegment

    fun fmt(value: Double): String = String.format(Locale.US, "%.2f", value)
    return buildString {
        append(
            "<svg class=\"${CssClass.Actual.ChartSvg.value}\" viewBox=\"0 0 960 260\" preserveAspectRatio=\"none\">",
        )
        listOf(0.0, height / 2, height).forEach { y ->
            append(
                "<line x1=\"0\" y1=\"${fmt(y)}\" x2=\"$width\" y2=\"${fmt(y)}\" " +
                    "class=\"${CssClass.Actual.GridLine.value}\"/>",
            )
        }
        points.forEach { point ->
            if (!point.complete) {
                append(
                    "<line x1=\"${fmt(point.x)}\" y1=\"$verticalPadding\" " +
                        "x2=\"${fmt(point.x)}\" y2=\"${fmt(height - verticalPadding)}\" " +
                        "class=\"${CssClass.Actual.GapMarker.value}\"/>",
                )
            }
        }
        holdSegments.forEach { pointsInSegment ->
            if (pointsInSegment.size == 1) {
                val point = pointsInSegment.single()
                append(
                    "<circle cx=\"${fmt(point.x)}\" cy=\"${fmt(point.y)}\" r=\"4\" " +
                        "class=\"${CssClass.Actual.HoldPoint.value}\"/>",
                )
            } else {
                append("<polyline points=\"")
                append(pointsInSegment.joinToString(" ") { "${fmt(it.x)},${fmt(it.y)}" })
                append("\" class=\"${CssClass.Actual.HoldLine.value}\"/>")
            }
        }
        segments.forEach { pointsInSegment ->
            if (pointsInSegment.size == 1) {
                val point = pointsInSegment.single()
                append(
                    "<circle cx=\"${fmt(point.x)}\" cy=\"${fmt(point.y)}\" r=\"4\" " +
                        "class=\"${CssClass.Actual.Point.value}\"/>",
                )
            } else {
                append("<polyline points=\"")
                append(pointsInSegment.joinToString(" ") { "${fmt(it.x)},${fmt(it.y)}" })
                append("\" class=\"${CssClass.Actual.Line.value}\"/>")
            }
        }
        append("</svg>")
    }
}

private data class ChartPoint(val observedAt: Instant, val x: Double, val y: Double, val complete: Boolean)

private fun Duration.toDoubleSeconds(): Double = seconds.toDouble() + nano / 1_000_000_000.0
