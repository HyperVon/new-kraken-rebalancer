package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.api.PortfolioSnapshot
import com.gemini.krakenbot.api.TradeRecord
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.OrderSide
import com.gemini.krakenbot.util.PrecisionConstants
import com.gemini.krakenbot.view.util.ChartProps
import com.gemini.krakenbot.view.util.HtmlIds
import com.gemini.krakenbot.view.util.ViewText
import kotlin.js.Date
import kotlin.js.json

private val assetColorMap: Map<String, String> by lazy {
    val global = js("window.${ChartProps.ASSET_COLORS_GLOBAL_KEY}")
    if (global != null && global != undefined) {
        val map = mutableMapOf<String, String>()
        val keys: Array<String> = js("Object.keys(${ChartProps.ASSET_COLORS_GLOBAL_KEY})")
        for (key in keys) {
            val v: String = js("${ChartProps.ASSET_COLORS_GLOBAL_KEY}[key]")
            map[key] = v
        }
        map
    } else {
        emptyMap()
    }
}

private fun colorForSymbol(symbol: String, fallbackIndex: Int): String =
    assetColorMap[symbol.uppercase()] ?: ChartProps.borderColorForSymbol(symbol, fallbackIndex)

private fun bgColorForSymbol(symbol: String, fallbackIndex: Int): String {
    val solid = assetColorMap[symbol.uppercase()]
    if (solid != null) {
        return hexToRgba(solid, 0.1) ?: ChartProps.backgroundColorForSymbol(symbol, fallbackIndex)
    }
    return ChartProps.backgroundColorForSymbol(symbol, fallbackIndex)
}

private fun hexToRgba(hex: String, alpha: Double): String? {
    val clean = hex.removePrefix("#")
    if (clean.length != 6) return null
    val r = clean.substring(0, 2).toIntOrNull(16) ?: return null
    val g = clean.substring(2, 4).toIntOrNull(16) ?: return null
    val b = clean.substring(4, 6).toIntOrNull(16) ?: return null
    return "rgba($r, $g, $b, $alpha)"
}

fun formatUSD(valDouble: Double): String {
    val options: dynamic = json()
    options.minimumFractionDigits = PrecisionConstants.SCALE_USD
    options.maximumFractionDigits = PrecisionConstants.SCALE_USD
    // Format the magnitude, then place the sign before the $ ("-$1.23", not "$-1.23").
    val absVal = if (valDouble < 0) -valDouble else valDouble
    val formatted = absVal.asDynamic().toLocaleString(EN_US, options) as String
    return if (valDouble < 0) "-$$formatted" else "$$formatted"
}

fun formatPctTick(v: Double, includePlus: Boolean = true): String {
    val d = dynamicNumber(v) ?: 0.0
    val sign = if (includePlus && d >= 0.0) "+" else ""
    val options: dynamic = json()
    options.minimumFractionDigits = 0
    options.maximumFractionDigits = PrecisionConstants.SCALE_USD
    return sign + d.asDynamic().toLocaleString(EN_US, options) + "%"
}

internal fun getUniqueSymbols(snapshots: List<PortfolioSnapshot>, excludeUsd: Boolean = true): List<String> {
    val symbolsSet = mutableSetOf<String>()
    snapshots.forEach { snapshot ->
        snapshot.assets.keys.forEach { symbolsSet.add(it) }
    }
    return if (excludeUsd) {
        symbolsSet.filter { it != Asset.USD }.sorted()
    } else {
        symbolsSet.sorted()
    }
}

internal fun mapSnapshotsToPoints(
    snapshots: List<PortfolioSnapshot>,
    valueSelector: (PortfolioSnapshot) -> Double,
): Array<dynamic> = snapshots
    .map { snapshot ->
        json("x" to snapshot.timestamp, "y" to valueSelector(snapshot))
    }.toTypedArray()

internal fun buildPortfolioValueChart(snapshots: List<PortfolioSnapshot>) {
    if (snapshots.isEmpty()) {
        clearChart(HtmlIds.PORTFOLIO_VALUE_CHART)
        return
    }

    val pointCount = snapshots.size
    val symbolList = getUniqueSymbols(snapshots)

    val totalPortfolioData =
        mapSnapshotsToPoints(snapshots) { snapshot ->
            dynamicNumber(snapshot.totalValueUSD) ?: 0.0
        }

    val datasets = mutableListOf<dynamic>()
    datasets.add(
        json(
            ChartProps.LABEL to ViewText.TOTAL_PORTFOLIO,
            ChartProps.DATA to totalPortfolioData,
            ChartProps.BORDER_COLOR to ChartProps.COLOR_BLUE,
            ChartProps.BACKGROUND_COLOR to ChartProps.COLOR_BLUE_BG,
            ChartProps.FILL to true,
            ChartProps.TENSION to ChartProps.TENSION_CURVED,
            ChartProps.BORDER_WIDTH to ChartProps.BORDER_WIDTH_PRIMARY,
            ChartProps.POINT_RADIUS to pointRadiusForCount(pointCount, primary = true),
            ChartProps.POINT_HOVER_RADIUS to pointHoverRadiusForCount(pointCount, primary = true),
            ChartProps.POINT_HIT_RADIUS to ChartProps.POINT_HIT_RADIUS_DEFAULT,
        ),
    )

    symbolList.forEachIndexed { i, sym ->
        val color = colorForSymbol(sym, i)
        val symbolData =
            mapSnapshotsToPoints(snapshots) { snapshot ->
                dynamicNumber(snapshot.assets[sym]?.valueUSD) ?: 0.0
            }

        datasets.add(
            json(
                ChartProps.LABEL to sym,
                ChartProps.DATA to symbolData,
                ChartProps.BORDER_COLOR to color,
                ChartProps.BACKGROUND_COLOR to ChartProps.TRANSPARENT,
                ChartProps.TENSION to ChartProps.TENSION_CURVED,
                ChartProps.BORDER_WIDTH to ChartProps.BORDER_WIDTH_SECONDARY,
                ChartProps.POINT_RADIUS to pointRadiusForCount(pointCount, primary = false),
                ChartProps.POINT_HOVER_RADIUS to pointHoverRadiusForCount(pointCount, primary = false),
                ChartProps.POINT_HIT_RADIUS to ChartProps.POINT_HIT_RADIUS_DEFAULT,
            ),
        )
    }

    val options = getClonedChartOptions()
    options.plugins.tooltip.callbacks = json()
    options.plugins.tooltip.callbacks.label = { ctx: dynamic ->
        val label = ctx.dataset.label.toString()
        val yVal = dynamicNumber(ctx.parsed.y) ?: 0.0
        "$label: ${formatUSD(yVal)}"
    }

    options.scales.y.ticks.callback = { v: Double, _: dynamic, _: dynamic ->
        formatUSD(v)
    }

    createOrUpdate(
        HtmlIds.PORTFOLIO_VALUE_CHART,
        createLineChartConfig(datasets.toTypedArray(), options),
    )
}

internal fun buildAssetHoldingsChart(snapshots: List<PortfolioSnapshot>) {
    if (snapshots.isEmpty()) {
        clearChart(HtmlIds.ASSET_HOLDINGS_CHART)
        return
    }

    val pointCount = snapshots.size
    val symbolList = getUniqueSymbols(snapshots)

    val baseline = snapshots[0]
    val baselines = mutableMapOf<String, Double>()
    symbolList.forEach { sym ->
        baselines[sym] = dynamicNumber(baseline.assets[sym]?.balance) ?: 0.0
    }

    val datasets =
        symbolList
            .mapIndexed { i, sym ->
                val color = colorForSymbol(sym, i)
                val symbolData =
                    mapSnapshotsToPoints(snapshots) { snapshot ->
                        val current = dynamicNumber(snapshot.assets[sym]?.balance) ?: 0.0
                        val base = baselines[sym] ?: 0.0
                        if (base > 0.0) {
                            ((current - base) / base) * PrecisionConstants.TOTAL_ALLOCATION_PERCENTAGE
                        } else {
                            0.0
                        }
                    }

                json(
                    ChartProps.LABEL to sym,
                    ChartProps.DATA to symbolData,
                    ChartProps.BORDER_COLOR to color,
                    ChartProps.BACKGROUND_COLOR to ChartProps.TRANSPARENT,
                    ChartProps.TENSION to ChartProps.TENSION_CURVED,
                    ChartProps.BORDER_WIDTH to ChartProps.BORDER_WIDTH_PRIMARY,
                    ChartProps.POINT_RADIUS to pointRadiusForCount(pointCount, primary = false),
                    ChartProps.POINT_HOVER_RADIUS to pointHoverRadiusForCount(pointCount, primary = false),
                    ChartProps.POINT_HIT_RADIUS to ChartProps.POINT_HIT_RADIUS_DEFAULT,
                )
            }.toTypedArray()

    val options = getClonedChartOptions()
    options.plugins.tooltip.callbacks = json()
    options.plugins.tooltip.callbacks.label = { ctx: dynamic ->
        val sym = ctx.dataset.label.toString()
        val pctChange = dynamicNumber(ctx.parsed.y) ?: 0.0
        val snapshot = snapshots[ctx.dataIndex as Int]
        val balance = dynamicNumber(snapshot.assets[sym]?.balance) ?: 0.0
        val pctSign = if (pctChange >= 0.0) "+" else ""
        val balOpts: dynamic = json()
        balOpts.minimumFractionDigits = PrecisionConstants.MIN_CRYPTO_DECIMAL_PLACES
        balOpts.maximumFractionDigits = PrecisionConstants.SCALE_CRYPTO
        val balFormatted = balance.asDynamic().toLocaleString(EN_US, balOpts)
        "$sym: $pctSign${pctChange.toFixed(PrecisionConstants.SCALE_USD)}% ($balFormatted)"
    }

    options.scales.y.ticks.callback = { v: Double, _: dynamic, _: dynamic ->
        formatPctTick(v, includePlus = true)
    }

    createOrUpdate(HtmlIds.ASSET_HOLDINGS_CHART, createLineChartConfig(datasets, options))
}

internal fun buildAllocationDriftChart(snapshots: List<PortfolioSnapshot>) {
    if (snapshots.isEmpty()) {
        clearChart(HtmlIds.ALLOCATION_DRIFT_CHART)
        return
    }

    val pointCount = snapshots.size
    val symbolList = getUniqueSymbols(snapshots, excludeUsd = false)

    val datasets =
        symbolList
            .mapIndexed { i, sym ->
                val color = colorForSymbol(sym, i)
                val bg = bgColorForSymbol(sym, i)
                val symbolData =
                    mapSnapshotsToPoints(snapshots) { snapshot ->
                        dynamicNumber(snapshot.assets[sym]?.deviationPercent) ?: 0.0
                    }

                json(
                    ChartProps.LABEL to sym,
                    ChartProps.DATA to symbolData,
                    ChartProps.BORDER_COLOR to color,
                    ChartProps.BACKGROUND_COLOR to bg,
                    ChartProps.FILL to false,
                    ChartProps.TENSION to ChartProps.TENSION_CURVED,
                    ChartProps.BORDER_WIDTH to ChartProps.BORDER_WIDTH_PRIMARY,
                    ChartProps.POINT_RADIUS to pointRadiusForCount(pointCount, primary = false),
                    ChartProps.POINT_HOVER_RADIUS to pointHoverRadiusForCount(pointCount, primary = false),
                    ChartProps.POINT_HIT_RADIUS to ChartProps.POINT_HIT_RADIUS_DEFAULT,
                )
            }.toTypedArray()

    val options = getClonedChartOptions()
    options.plugins.tooltip.callbacks = json()
    options.plugins.tooltip.callbacks.label = { ctx: dynamic ->
        val label = ctx.dataset.label.toString()
        val yVal = dynamicNumber(ctx.parsed.y) ?: 0.0
        val sign = if (yVal >= 0.0) "+" else ""
        "$label: $sign${yVal.toFixed(PrecisionConstants.SCALE_USD)}% ${ViewText.HISTORY_VS_TARGET}"
    }

    options.scales.y[ChartProps.BEGIN_AT_ZERO] = true
    options.scales.y.grid.color = { ctx: dynamic ->
        val tickValue = dynamicNumber(ctx.tick?.value)
        if (tickValue == 0.0) ChartProps.COLOR_ZERO_LINE else ChartProps.COLOR_GRID_LINE
    }
    options.scales.y.ticks.callback = { v: Double, _: dynamic, _: dynamic ->
        formatPctTick(v, includePlus = true)
    }

    createOrUpdate(HtmlIds.ALLOCATION_DRIFT_CHART, createLineChartConfig(datasets, options))
}

internal fun calculateCumulativeNetCashFlow(trades: List<TradeRecord>, includeDryRun: Boolean = false): Array<dynamic> =
    calculateSignedCashFlowSeries(trades, includeDryRun) { _, delta ->
        delta
    }

internal fun calculateCumulativeNetAfterFees(
    trades: List<TradeRecord>,
    includeDryRun: Boolean = false,
): Array<dynamic> = calculateSignedCashFlowSeries(trades, includeDryRun) { trade, delta ->
    val fee = dynamicNumber(trade.fee) ?: 0.0
    delta - fee
}

private inline fun calculateSignedCashFlowSeries(
    trades: List<TradeRecord>,
    includeDryRun: Boolean,
    crossinline adjustDelta: (TradeRecord, Double) -> Double,
): Array<dynamic> {
    if (trades.isEmpty()) return emptyArray()

    val sorted =
        trades.sortedWith { a, b ->
            val aTime = Date(a.timestamp).getTime()
            val bTime = Date(b.timestamp).getTime()
            aTime.compareTo(bTime)
        }

    val filtered =
        sorted.filter { trade ->
            trade.success && (includeDryRun || !trade.dryRun)
        }

    if (filtered.isEmpty()) return emptyArray()

    val points = mutableListOf<dynamic>()
    var cumulative = 0.0
    for (trade in filtered) {
        val amt = dynamicNumber(trade.usdAmount) ?: 0.0
        val side = trade.side.uppercase()
        val delta =
            when (side) {
                OrderSide.SELL.name -> amt
                OrderSide.BUY.name -> -amt
                else -> continue
            }
        cumulative += adjustDelta(trade, delta)
        points.add(json("x" to trade.timestamp, "y" to cumulative))
    }

    return points.toTypedArray()
}

internal fun buildCumulativeNetCashFlowChart(trades: List<TradeRecord>, includeDryRun: Boolean = false) {
    val grossData = calculateCumulativeNetCashFlow(trades, includeDryRun)
    val netAfterFeesData = calculateCumulativeNetAfterFees(trades, includeDryRun)
    if (grossData.asDynamic().length == 0) {
        clearChart(HtmlIds.CUMULATIVE_NET_CASH_FLOW_CHART)
        return
    }

    val grossChartData = padSinglePointSeries(grossData)
    val netChartData = padSinglePointSeries(netAfterFeesData)

    val grossLabel = if (includeDryRun) ViewText.NET_CASH_FLOW_ALL else ViewText.NET_CASH_FLOW_REALIZED
    val netLabel =
        if (includeDryRun) {
            ViewText.NET_AFTER_FEES_ESTIMATED
        } else {
            ViewText.NET_AFTER_FEES
        }
    val pointCount = (grossChartData.asDynamic().length as Int)

    val datasets =
        arrayOf(
            json(
                ChartProps.LABEL to grossLabel,
                ChartProps.DATA to grossChartData,
                ChartProps.BORDER_COLOR to ChartProps.COLOR_EMERALD,
                ChartProps.BACKGROUND_COLOR to ChartProps.COLOR_GREEN_BG,
                ChartProps.FILL to true,
                ChartProps.TENSION to ChartProps.TENSION_CURVED,
                ChartProps.BORDER_WIDTH to ChartProps.BORDER_WIDTH_PRIMARY,
                ChartProps.POINT_RADIUS to pointRadiusForCount(pointCount, primary = true),
                ChartProps.POINT_HOVER_RADIUS to pointHoverRadiusForCount(pointCount, primary = true),
                ChartProps.POINT_HIT_RADIUS to ChartProps.POINT_HIT_RADIUS_DEFAULT,
            ),
            json(
                ChartProps.LABEL to netLabel,
                ChartProps.DATA to netChartData,
                ChartProps.BORDER_COLOR to ChartProps.COLOR_AMBER,
                ChartProps.BACKGROUND_COLOR to ChartProps.TRANSPARENT,
                ChartProps.FILL to false,
                ChartProps.TENSION to ChartProps.TENSION_CURVED,
                ChartProps.BORDER_WIDTH to ChartProps.BORDER_WIDTH_SECONDARY,
                ChartProps.BORDER_DASH to
                    arrayOf(
                        ChartProps.BORDER_DASH_SEGMENT,
                        ChartProps.BORDER_DASH_GAP,
                    ),
                ChartProps.POINT_RADIUS to pointRadiusForCount(pointCount, primary = false),
                ChartProps.POINT_HOVER_RADIUS to pointHoverRadiusForCount(pointCount, primary = false),
                ChartProps.POINT_HIT_RADIUS to ChartProps.POINT_HIT_RADIUS_DEFAULT,
            ),
        )

    val options = getClonedChartOptions()
    options.scales.y.ticks.callback = { v: Double, _: dynamic, _: dynamic ->
        formatUSD(v)
    }

    createOrUpdate(HtmlIds.CUMULATIVE_NET_CASH_FLOW_CHART, createLineChartConfig(datasets, options))
}

// A single point renders as a lone dot; prepend a synthetic zero one hour earlier so the
// cumulative series draws as a line from the baseline.
private fun padSinglePointSeries(rawData: Array<dynamic>): Array<dynamic> = if (rawData.size == 1) {
    val firstTradeTime = Date(rawData[0].x.toString()).getTime()
    val startTime = Date(firstTradeTime - PrecisionConstants.ONE_HOUR_MS).toISOString()
    arrayOf(json(ChartProps.X to startTime, ChartProps.Y to 0.0), rawData[0])
} else {
    rawData
}
