package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.api.PortfolioSnapshot
import com.gemini.krakenbot.api.RewardsOverTime
import com.gemini.krakenbot.api.TradeRecord
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.OrderSide
import com.gemini.krakenbot.util.PrecisionConstants
import com.gemini.krakenbot.view.util.ChartProps
import com.gemini.krakenbot.view.util.HtmlIds
import com.gemini.krakenbot.view.util.ViewText
import kotlinx.browser.document
import kotlin.js.Date
import kotlin.js.json

/**
 * A Chart.js line dataset with the shared structural props. `fill`/`borderDash` are nullable so
 * their absence is preserved (a dataset without a `fill` key renders differently from `fill:false`).
 */
internal fun lineDataset(
    label: String,
    data: Array<dynamic>,
    borderColor: String,
    backgroundColor: String,
    primary: Boolean,
    borderPrimary: Boolean = primary,
    fill: Boolean? = null,
    borderDash: Array<dynamic>? = null,
): dynamic {
    val ds: dynamic = json(
        ChartProps.LABEL to label,
        ChartProps.DATA to data,
        ChartProps.BORDER_COLOR to borderColor,
        ChartProps.BACKGROUND_COLOR to backgroundColor,
        ChartProps.TENSION to ChartProps.TENSION_CURVED,
        ChartProps.BORDER_WIDTH to
            if (borderPrimary) ChartProps.BORDER_WIDTH_PRIMARY else ChartProps.BORDER_WIDTH_SECONDARY,
        ChartProps.POINT_RADIUS to pointRadiusForCount(data.size, primary),
        ChartProps.POINT_HOVER_RADIUS to pointHoverRadiusForCount(data.size, primary),
        ChartProps.POINT_HIT_RADIUS to ChartProps.POINT_HIT_RADIUS_DEFAULT,
    )
    if (fill != null) ds[ChartProps.FILL] = fill
    if (borderDash != null) ds[ChartProps.BORDER_DASH] = borderDash
    return ds
}

/** Default USD tooltip label and y-axis tick formatting shared by value charts. */
internal fun applyUsdLabeling(options: dynamic) {
    options.plugins.tooltip.callbacks = json()
    options.plugins.tooltip.callbacks.label = { ctx: dynamic ->
        val label = ctx.dataset.label.toString()
        val yVal = dynamicNumber(ctx.parsed.y) ?: 0.0
        "$label: ${formatUSD(yVal)}"
    }
    options.scales.y.ticks.callback = { v: Double, _: dynamic, _: dynamic -> formatUSD(v) }
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

    val symbolList = getUniqueSymbols(snapshots)

    val totalPortfolioData =
        mapSnapshotsToPoints(snapshots) { snapshot ->
            dynamicNumber(snapshot.totalValueUSD) ?: 0.0
        }

    val datasets = mutableListOf<dynamic>()
    datasets.add(
        lineDataset(
            label = ViewText.TOTAL_PORTFOLIO,
            data = totalPortfolioData,
            borderColor = ChartProps.COLOR_BLUE,
            backgroundColor = ChartProps.COLOR_BLUE_BG,
            primary = true,
            fill = true,
        ),
    )

    symbolList.forEachIndexed { i, sym ->
        val symbolData =
            mapSnapshotsToPoints(snapshots) { snapshot ->
                dynamicNumber(snapshot.assets[sym]?.valueUSD) ?: 0.0
            }
        datasets.add(
            lineDataset(
                label = sym,
                data = symbolData,
                borderColor = colorForSymbol(sym, i),
                backgroundColor = ChartProps.TRANSPARENT,
                primary = false,
            ),
        )
    }

    val options = getClonedChartOptions()
    applyUsdLabeling(options)
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

    val symbolList = getUniqueSymbols(snapshots)

    val baseline = snapshots[0]
    val baselines = mutableMapOf<String, Double>()
    symbolList.forEach { sym ->
        baselines[sym] = dynamicNumber(baseline.assets[sym]?.balance) ?: 0.0
    }

    val datasets =
        symbolList
            .mapIndexed { i, sym ->
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

                lineDataset(
                    label = sym,
                    data = symbolData,
                    borderColor = colorForSymbol(sym, i),
                    backgroundColor = ChartProps.TRANSPARENT,
                    primary = false,
                    borderPrimary = true,
                )
            }.toTypedArray()

    val options = getClonedChartOptions()
    options.plugins.tooltip.callbacks = json()
    options.plugins.tooltip.callbacks.label = { ctx: dynamic ->
        val sym = ctx.dataset.label.toString()
        val pctChange = dynamicNumber(ctx.parsed.y) ?: 0.0
        val idx = dynamicNumber(ctx.dataIndex)?.toInt() ?: -1
        val snapshot = snapshots.getOrNull(idx)
        val balance = snapshot?.let { dynamicNumber(it.assets[sym]?.balance) } ?: 0.0
        val pctSign = if (pctChange >= 0.0) "+" else ""
        val balFormatted = usdOptionsToLocale(
            balance,
            PrecisionConstants.MIN_CRYPTO_DECIMAL_PLACES,
            PrecisionConstants.SCALE_CRYPTO,
        )
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

    val symbolList = getUniqueSymbols(snapshots, excludeUsd = false)

    val datasets =
        symbolList
            .mapIndexed { i, sym ->
                val bg = bgColorForSymbol(sym, i)
                val symbolData =
                    mapSnapshotsToPoints(snapshots) { snapshot ->
                        dynamicNumber(snapshot.assets[sym]?.deviationPercent) ?: 0.0
                    }

                lineDataset(
                    label = sym,
                    data = symbolData,
                    borderColor = colorForSymbol(sym, i),
                    backgroundColor = bg,
                    primary = false,
                    borderPrimary = true,
                    fill = false,
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
    val datasets =
        arrayOf(
            lineDataset(
                label = grossLabel,
                data = grossChartData,
                borderColor = ChartProps.COLOR_EMERALD,
                backgroundColor = ChartProps.COLOR_GREEN_BG,
                primary = true,
                fill = true,
            ),
            lineDataset(
                label = netLabel,
                data = netChartData,
                borderColor = ChartProps.COLOR_AMBER,
                backgroundColor = ChartProps.TRANSPARENT,
                primary = false,
                fill = false,
                borderDash = arrayOf(ChartProps.BORDER_DASH_SEGMENT, ChartProps.BORDER_DASH_GAP),
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

internal fun buildRewardsChart(rewards: RewardsOverTime) {
    val totalEl = document.getElementById(HtmlIds.REWARDS_TOTAL)
    val total = dynamicNumber(rewards.totalRewardsUSD) ?: 0.0
    totalEl?.textContent = formatUSD(total)

    val points =
        rewards.points
            .map { point -> json("x" to point.timestamp, "y" to (dynamicNumber(point.cumulativeUSD) ?: 0.0)) }
            .toTypedArray()
    if (points.isEmpty()) {
        clearChart(HtmlIds.REWARDS_CHART)
        return
    }

    val datasets =
        arrayOf(
            lineDataset(
                label = ViewText.HISTORY_STAKING_REWARDS,
                data = points,
                borderColor = ChartProps.COLOR_EMERALD,
                backgroundColor = ChartProps.COLOR_GREEN_BG,
                primary = true,
                fill = true,
            ),
        )
    val options = getClonedChartOptions()
    applyUsdLabeling(options)
    options.plugins.tooltip.callbacks.footer = { items: Array<dynamic> ->
        val rawIndex = items.firstOrNull()?.dataIndex
        val index = if (rawIndex == null || rawIndex == undefined) -1 else rawIndex.toString().toInt()
        val perAsset = rewards.points.getOrNull(index)?.perAssetUSD
        perAsset?.entries?.map { entry ->
            val amount = dynamicNumber(entry.value) ?: 0.0
            "${entry.key}: ${formatUSD(amount)}"
        }?.toTypedArray() ?: emptyArray()
    }
    createOrUpdate(HtmlIds.REWARDS_CHART, createLineChartConfig(datasets, options))
}
