package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.view.util.ChartGestureCss
import com.gemini.krakenbot.view.util.ChartGestureEvents
import com.gemini.krakenbot.view.util.ChartGestureProps
import com.gemini.krakenbot.view.util.ChartGestureText
import com.gemini.krakenbot.view.util.ChartProps
import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.HistoryChartActions
import com.gemini.krakenbot.view.util.HtmlAttrs
import com.gemini.krakenbot.view.util.HtmlEvents
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.Element
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import kotlin.js.json
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class ChartRange(val min: Double, val max: Double) {
    val span: Double
        get() = max - min
}

/** Single source of truth for a chart's visible window; buttons and gestures both reduce on it. */
internal data class ChartViewport(val full: ChartRange, val visible: ChartRange) {
    val isZoomed: Boolean
        get() = visible.span < full.span

    val fraction: Double
        get() {
            val movable = full.span - visible.span
            return if (movable <= 0.0) 0.0 else ((visible.min - full.min) / movable).coerceIn(0.0, 1.0)
        }

    fun zoom(factor: Double, anchor: Double): ChartViewport {
        if (factor <= 0.0 || visible.span <= 0.0) return this
        val span = (visible.span * factor).coerceIn(minSpan(), full.span)
        val ratio = span / visible.span
        return withVisible(ChartRange(anchor - (anchor - visible.min) * ratio, anchor - (anchor - visible.max) * ratio))
    }

    fun panBy(deltaMs: Double): ChartViewport = withVisible(
        ChartRange(visible.min + deltaMs, visible.max + deltaMs),
    )

    fun panToFraction(fraction: Double): ChartViewport {
        val movable = full.span - visible.span
        if (movable <= 0.0) return this
        val start = full.min + movable * fraction.coerceIn(0.0, 1.0)
        return withVisible(ChartRange(start, start + visible.span))
    }

    fun select(fromMs: Double, toMs: Double): ChartViewport = withVisible(
        ChartRange(minOf(fromMs, toMs), maxOf(fromMs, toMs)),
    )

    fun reset(): ChartViewport = ChartViewport(full, full)

    private fun minSpan(): Double = minOf(ChartGestureProps.MIN_SPAN_MS, full.span)

    private fun withVisible(range: ChartRange): ChartViewport {
        val span = range.span.coerceIn(minSpan(), full.span)
        var min = range.min
        var max = min + span
        if (min < full.min) {
            min = full.min
            max = min + span
        }
        if (max > full.max) {
            max = full.max
            min = max - span
        }
        return ChartViewport(full, ChartRange(min, max))
    }
}

internal fun zoomedViewport(viewport: ChartViewport, action: String): ChartViewport = when (action) {
    HistoryChartActions.ZOOM_IN ->
        viewport.zoom(ChartGestureProps.ZOOM_BUTTON_FACTOR_IN, viewport.visible.max)

    HistoryChartActions.ZOOM_OUT ->
        viewport.zoom(ChartGestureProps.ZOOM_BUTTON_FACTOR_OUT, viewport.visible.max)

    else -> viewport.reset()
}

internal fun pannedViewport(viewport: ChartViewport, direction: String): ChartViewport {
    val step = viewport.visible.span * ChartGestureProps.PAN_BUTTON_FRACTION
    return if (direction == HistoryChartActions.PAN_EARLIER) viewport.panBy(-step) else viewport.panBy(step)
}

internal fun gestureWidth(canvas: HTMLCanvasElement): Double {
    val rect = canvas.getBoundingClientRect()
    return if (rect.width > 0.0) rect.width else canvas.width.toDouble()
}

internal fun chartPixelToTime(chart: dynamic, px: Double, width: Double, fallback: ChartRange): Double {
    val scaleX = chart?.scales?.x
    val getValueForPixel = scaleX?.getValueForPixel
    if (getValueForPixel != null && getValueForPixel != undefined && jsTypeOf(getValueForPixel) == "function") {
        val time = (scaleX.getValueForPixel(px) as? Number)?.toDouble()
        if (time != null && time.isFinite()) return time
    }
    if (width <= 0.0 || fallback.span <= 0.0) return fallback.min
    return fallback.min + (px / width) * fallback.span
}

internal fun wheelZoomFactor(deltaY: Double): Double =
    (1.0 + deltaY * ChartGestureProps.WHEEL_ZOOM_SENSITIVITY).coerceIn(
        ChartGestureProps.WHEEL_ZOOM_MIN_FACTOR,
        ChartGestureProps.WHEEL_ZOOM_MAX_FACTOR,
    )

/** Representative subset of a dense series: gaps stay null breaks and the endpoints stay pinned. */
internal fun representativePoints(
    points: List<HistoryChartPoint>,
    limit: Int = ChartGestureProps.MAX_VIEWPORT_POINTS,
): List<HistoryChartPoint> {
    if (points.size <= limit) return points
    val gaps = points.filter { it.value == null }
    if (gaps.size >= limit) return strideSample(points, limit)
    return (strideSample(points.filter { it.value != null }, limit - gaps.size) + gaps).sortedBy { it.time }
}

private fun strideSample(points: List<HistoryChartPoint>, limit: Int): List<HistoryChartPoint> {
    if (points.size <= limit) return points
    val step = (points.size - 1).toDouble() / (limit - 1)
    return (0 until limit).map { points[(it * step).roundToInt().coerceIn(0, points.size - 1)] }
}

/** Seam for the follow-up track: re-query GET /api/history/charts for the zoomed interval. */
internal var historyViewportDetailHandler: ((String, Double, Double) -> Unit)? = null

internal fun requestViewportDetail(chartId: String, fromMs: Double, toMs: Double) {
    historyViewportDetailHandler?.invoke(chartId, fromMs, toMs)
}

internal val historyChartSessions = mutableMapOf<String, HistoryChartSession>()

/**
 * Start of the selected period a chart's cumulative series is measured from. A zoomed viewport
 * keeps this baseline, so the running total does not restart inside the zoom.
 */
internal val historyChartBaselines = mutableMapOf<String, String>()

internal val historyLegendHidden = mutableMapOf<String, MutableSet<String>>()

internal class HistoryChartSession(
    val container: Element,
    val canvas: HTMLCanvasElement,
    val chart: dynamic,
    val evidenceSignature: String,
    fullRange: ChartRange,
) {
    var viewport: ChartViewport = ChartViewport(fullRange, fullRange)
    val disposers = mutableListOf<() -> Unit>()

    fun setViewport(next: ChartViewport) {
        viewport = next
        setChartXRange(chart, next.visible)
        updateChart(chart)
        syncHistoryScrubber(this)
        syncHistoryFollowLatestButton(container, next.isZoomed)
        requestViewportDetail(container.id, next.visible.min, next.visible.max)
    }

    fun dispose() {
        disposers.toList().forEach { it() }
        disposers.clear()
        historyChartSessions.remove(container.id)
        val destroy = chart?.destroy
        if (destroy != null && destroy != undefined && jsTypeOf(destroy) == "function") destroy.call(chart)
        canvas.remove()
    }
}

internal fun disposeDetachedHistoryCharts() {
    historyChartSessions.values.filter { !it.container.isConnected }.forEach { it.dispose() }
}

/**
 * Pins the visible window on the chart options. Chart.js otherwise rounds a linear axis to its own
 * "nice" bounds, which would shrink the axis to the plotted points and hide the selected period.
 * The zoom plugin keeps its own scale state, so it is aligned with the same window afterwards.
 */
internal fun setChartXRange(chart: dynamic, range: ChartRange) {
    if (chart.options == null || chart.options == undefined) chart.options = json()
    if (chart.options.scales == null || chart.options.scales == undefined) chart.options.scales = json()
    if (chart.options.scales.x == null || chart.options.scales.x == undefined) chart.options.scales.x = json()
    chart.options.scales.x.min = range.min
    chart.options.scales.x.max = range.max
    val zoomScale = chart?.zoomScale
    if (zoomScale != null && zoomScale != undefined && jsTypeOf(zoomScale) == "function") {
        zoomScale(
            ChartProps.MODE_X,
            json(ChartProps.MIN to range.min, ChartProps.MAX to range.max),
            ChartProps.TRANSITION_NONE,
        )
    }
}

internal fun updateChart(chart: dynamic) {
    val update = chart?.update
    if (update != null && update != undefined && jsTypeOf(update) == "function") chart.update("none")
}

internal fun syncHistoryScrubber(session: HistoryChartSession) {
    val scrubber = queryChartScrubber(session.container.id) ?: return
    val zoomed = session.viewport.isZoomed
    scrubber.disabled = !zoomed
    scrubber.value = (session.viewport.fraction * ChartGestureProps.SCRUBBER_STEPS).roundToInt().toString()
    scrubber.parentElement?.classList?.toggle(CssClass.Utility.Hidden, !zoomed)
}

internal fun syncHistoryFollowLatestButton(container: Element, zoomed: Boolean) {
    container.parentElement
        ?.querySelector("[${HtmlAttrs.DATA_HISTORY_FOLLOW_LATEST}]")
        ?.let { (it as? HTMLElement)?.hidden = !zoomed }
}

internal fun bindHistoryScrubber(scrubber: HTMLInputElement, session: HistoryChartSession) {
    releaseHistoryScrubber(scrubber)
    scrubber.asDynamic().historyScrubberMode = "pan"
    scrubber.min = "0"
    scrubber.max = ChartGestureProps.SCRUBBER_STEPS.toString()
    scrubber.step = "1"
    val dispose = listenDom(scrubber, HtmlEvents.INPUT) {
        val fraction = (scrubber.value.toDoubleOrNull() ?: 0.0) / ChartGestureProps.SCRUBBER_STEPS
        session.setViewport(session.viewport.panToFraction(fraction))
    }
    scrubber.asDynamic().historyScrubberDispose = dispose
    syncHistoryScrubber(session)
}

internal fun releaseHistoryScrubber(scrubber: HTMLInputElement) {
    val dispose = scrubber.asDynamic().historyScrubberDispose
    if (dispose != null && dispose != undefined) dispose()
    scrubber.asDynamic().historyScrubberDispose = null
}

internal fun toggleLegendSeries(chartKey: String, legendItem: dynamic, chart: dynamic) {
    val rawIndex = legendItem?.datasetIndex
    if (rawIndex == null || rawIndex == undefined) return
    val index = (rawIndex as Number).toInt()
    val datasets = chart?.data?.datasets ?: return
    val dataset = datasets[index] ?: return
    val visible = if (jsTypeOf(chart.isDatasetVisible) == "function") {
        chart.isDatasetVisible(index) as Boolean
    } else {
        dataset.hidden != true
    }
    val nowHidden = visible
    if (jsTypeOf(chart.setDatasetVisibility) == "function") {
        chart.setDatasetVisibility(index, !nowHidden)
    } else {
        dataset.hidden = nowHidden
    }
    updateChart(chart)
    val label = dataset.label?.toString() ?: return
    val hiddenSet = historyLegendHidden.getOrPut(chartKey) { mutableSetOf() }
    if (nowHidden) hiddenSet.add(label) else hiddenSet.remove(label)
}

internal fun bindLegendPersistence(chart: dynamic, chartKey: String) {
    val legend = chart?.options?.plugins?.legend ?: return
    legend.onClick = { _: dynamic, legendItem: dynamic, legendContext: dynamic ->
        toggleLegendSeries(chartKey, legendItem, legendContext.chart)
    }
}

internal fun applyLegendVisibility(config: dynamic, chartKey: String) {
    val hidden = historyLegendHidden[chartKey] ?: return
    val datasets = config.data?.datasets ?: return
    repeat(datasets.length) { index ->
        val dataset = datasets[index]
        val label = dataset?.label?.toString()
        if (label != null && label in hidden) dataset.hidden = true
    }
}

internal fun showChartFallbackNotice(container: Element, retry: () -> Unit) {
    if (container.querySelector(".${ChartGestureCss.FALLBACK_NOTICE}") != null) return
    val notice = document.createElement("div") as HTMLElement
    notice.className = ChartGestureCss.FALLBACK_NOTICE
    val message = document.createElement("p") as HTMLElement
    message.textContent = ChartGestureText.INTERACTIVE_CHART_UNAVAILABLE
    val retryButton = document.createElement("button") as HTMLElement
    retryButton.setAttribute("type", "button")
    retryButton.textContent = ChartGestureText.RETRY
    retryButton.addEventListener(HtmlEvents.CLICK, {
        notice.remove()
        retry()
    })
    notice.appendChild(message)
    notice.appendChild(retryButton)
    container.appendChild(notice)
}

internal fun removeChartFallbackNotice(container: Element) {
    val notices = container.querySelectorAll(".${ChartGestureCss.FALLBACK_NOTICE}")
    repeat(notices.length) { index -> (notices.item(index) as? HTMLElement)?.remove() }
}

internal fun listenDom(target: dynamic, type: String, options: dynamic = null, handler: (Event) -> Unit): () -> Unit {
    val fn: dynamic = handler
    if (options == null) {
        target.addEventListener(type, fn)
    } else {
        target.addEventListener(type, fn, options)
    }
    return { target.removeEventListener(type, fn) }
}

private class ChartDragState(
    val selecting: Boolean,
    val startX: Double,
    val startViewport: ChartViewport,
    val width: Double,
    val mapPx: (Double) -> Double,
)

/**
 * Desktop gestures own the mouse: plain drag selects an interval (visible rectangle), Shift+drag pans,
 * and Ctrl+wheel zooms around the pointer. The zoom plugin stays gesture-free so it cannot double-handle
 * these events; Hammer covers touch pinch/pan below.
 */
internal fun attachChartGestures(session: HistoryChartSession) {
    val canvas = session.canvas
    var drag: ChartDragState? = null
    var selectionBox: HTMLElement? = null
    val transient = mutableListOf<() -> Unit>()

    val finishDrag: () -> Unit = {
        selectionBox?.remove()
        selectionBox = null
        drag = null
        transient.toList().forEach { it() }
        transient.clear()
    }
    session.disposers.add(finishDrag)

    val mouseMove = { event: Event ->
        val state = drag
        if (state != null) {
            val px = (event.asDynamic().clientX as Number).toDouble() - canvas.getBoundingClientRect().left
            if (state.selecting) {
                selectionBox?.style?.let { style ->
                    style.left = "${minOf(state.startX, px)}px"
                    style.width = "${abs(px - state.startX)}px"
                }
            } else {
                val deltaMs = ((state.startX - px) / state.width) * state.startViewport.visible.span
                session.setViewport(state.startViewport.panBy(deltaMs))
            }
        }
    }
    val mouseUp = { event: Event ->
        val state = drag
        if (state != null && state.selecting) {
            val px = (event.asDynamic().clientX as Number).toDouble() - canvas.getBoundingClientRect().left
            if (abs(px - state.startX) >= ChartGestureProps.DRAG_THRESHOLD_PX) {
                session.setViewport(state.startViewport.select(state.mapPx(state.startX), state.mapPx(px)))
            }
        }
        finishDrag()
    }
    val mouseDown = { event: Event ->
        val e = event.asDynamic()
        if ((e.button as Number).toInt() == 0 && drag == null) {
            val width = gestureWidth(canvas)
            val startViewport = session.viewport
            val startX = (e.clientX as Number).toDouble() - canvas.getBoundingClientRect().left
            val selecting = e.shiftKey != true
            drag = ChartDragState(
                selecting = selecting,
                startX = startX,
                startViewport = startViewport,
                width = width,
                mapPx = { px -> chartPixelToTime(session.chart, px, width, startViewport.visible) },
            )
            if (selecting) {
                val box = document.createElement("div") as HTMLElement
                box.className = ChartGestureCss.SELECTION_RECT
                box.setAttribute(
                    "style",
                    "position:absolute;top:0;bottom:0;pointer-events:none;z-index:5;" +
                        "background:${ChartGestureProps.SELECTION_FILL};" +
                        "border-left:${ChartGestureProps.SELECTION_BORDER};" +
                        "border-right:${ChartGestureProps.SELECTION_BORDER}",
                )
                session.container.asDynamic().style.position = "relative"
                session.container.appendChild(box)
                selectionBox = box
            }
            transient.add(listenDom(window, ChartGestureEvents.MOUSE_MOVE, null, mouseMove))
            transient.add(listenDom(window, ChartGestureEvents.MOUSE_UP, null, mouseUp))
        }
    }
    val wheel = { event: Event ->
        val e = event.asDynamic()
        if (e.ctrlKey == true) {
            event.preventDefault()
            val width = gestureWidth(canvas)
            val px = (e.clientX as Number).toDouble() - canvas.getBoundingClientRect().left
            val anchor = chartPixelToTime(session.chart, px, width, session.viewport.visible)
            session.setViewport(session.viewport.zoom(wheelZoomFactor((e.deltaY as Number).toDouble()), anchor))
        }
    }
    session.disposers.add(listenDom(canvas, ChartGestureEvents.MOUSE_DOWN, null, mouseDown))
    session.disposers.add(listenDom(canvas, ChartGestureEvents.WHEEL, json("passive" to false), wheel))
    attachTouchGestures(session)
}

private fun attachTouchGestures(session: HistoryChartSession) {
    val hammerCtor = window.asDynamic().Hammer
    if (hammerCtor == null || hammerCtor == undefined) return
    val hammerCanvas = session.canvas
    val hammer = js("Reflect.construct(hammerCtor, [hammerCanvas])")
    hammer.get(ChartGestureEvents.PINCH).set(json("enable" to true))
    var lastScale = 1.0
    var panStart: ChartViewport? = null
    var panStartX = 0.0

    hammer.on(ChartGestureEvents.PINCH_START, { _: dynamic -> lastScale = 1.0 })
    hammer.on(
        ChartGestureEvents.PINCH_MOVE,
        { event: dynamic ->
            if (event.pointerType == "touch") {
                val scale = (event.scale as Number).toDouble()
                if (scale > 0.0) {
                    val width = gestureWidth(session.canvas)
                    val anchor = chartPixelToTime(session.chart, width / 2.0, width, session.viewport.visible)
                    session.setViewport(session.viewport.zoom(lastScale / scale, anchor))
                    lastScale = scale
                }
            }
        },
    )
    hammer.on(
        ChartGestureEvents.PAN_START,
        { event: dynamic ->
            if (event.pointerType == "touch") {
                panStart = session.viewport
                panStartX = (event.deltaX as Number).toDouble()
            }
        },
    )
    hammer.on(
        ChartGestureEvents.PAN_MOVE,
        { event: dynamic ->
            val start = panStart
            if (event.pointerType == "touch" && start != null) {
                val width = gestureWidth(session.canvas)
                val deltaMs = -(((event.deltaX as Number).toDouble() - panStartX) / width) * start.visible.span
                session.setViewport(start.panBy(deltaMs))
            }
        },
    )
    session.disposers.add {
        val destroy = hammer.destroy
        if (destroy != null && destroy != undefined && jsTypeOf(destroy) == "function") destroy.call(hammer)
    }
}
