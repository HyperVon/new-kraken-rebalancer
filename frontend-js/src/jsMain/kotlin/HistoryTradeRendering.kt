package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.api.HistoryStats
import com.gemini.krakenbot.api.PortfolioSnapshot
import com.gemini.krakenbot.api.TradeRecord
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.OrderSide
import com.gemini.krakenbot.model.TimeRange
import com.gemini.krakenbot.model.TradeSourceKeys
import com.gemini.krakenbot.util.PrecisionConstants
import com.gemini.krakenbot.view.util.ChartProps
import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.HtmlAttrs
import com.gemini.krakenbot.view.util.HtmlEvents
import com.gemini.krakenbot.view.util.HtmlIds
import com.gemini.krakenbot.view.util.HtmlTags
import com.gemini.krakenbot.view.util.Routes
import com.gemini.krakenbot.view.util.ViewText
import com.gemini.krakenbot.view.util.ZoomActions
import com.gemini.krakenbot.view.util.withRange
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.*
import kotlin.collections.mutableMapOf
import kotlin.js.Date
import kotlin.js.Promise
import kotlin.js.json
import com.gemini.krakenbot.view.util.CssClass.Query.CHART_SCRUBBERS as CHART_SCRUBBERS_QUERY
import com.gemini.krakenbot.view.util.CssClass.Query.TIME_RANGE_BTNS as TIME_RANGE_BTNS_QUERY
import com.gemini.krakenbot.view.util.CssClass.Query.ZOOM_BTNS as ZOOM_BTNS_QUERY

fun formatPair(trade: TradeRecord?): String {
    if (trade?.symbol.isNullOrBlank()) return ""
    return "${trade.symbol}/USD"
}

internal fun renderTradeTable(trades: List<TradeRecord>) {
    val tbody = document.getElementById(HtmlIds.TRADE_TABLE_BODY) ?: return
    tbody.innerHTML = ""

    val showDryRun = (document.getElementById(HtmlIds.SHOW_DRY_RUN_CHECKBOX) as? HTMLInputElement)?.checked ?: true
    val filteredTrades = if (showDryRun) trades else trades.filter { trade -> !trade.dryRun }

    if (filteredTrades.isEmpty()) {
        val tr = document.createElement(HtmlTags.TR)
        val td = document.createElement(HtmlTags.TD) as HTMLTableCellElement
        td.colSpan = PrecisionConstants.TRADE_TABLE_COLSPAN
        td.className = CssClass.History.EmptyTableCell.value
        td.textContent = ViewText.NO_TRADES_FOUND_PERIOD
        tr.appendChild(td)
        tbody.appendChild(tr)
        return
    }

    filteredTrades.forEach { t ->
        tbody.appendChild(renderTradeRow(t))
    }
}

private fun renderTradeRow(t: TradeRecord): HTMLTableRowElement {
    val tr = document.createElement(HtmlTags.TR) as HTMLTableRowElement
    tr.className = CssClass.Table.Hoverable.toString()

    val time = Date(t.timestamp).asDynamic().toLocaleString()
    val side = t.side.uppercase()
    val sideClass =
        when (side) {
            OrderSide.BUY.name -> CssClass.Badge.Buy
            OrderSide.SELL.name -> CssClass.Badge.Sell
            else -> CssClass.Badge.Info
        }
    val success = t.success
    val dryRun = t.dryRun
    val statusText =
        when {
            !success -> ViewText.STATUS_FAILED
            dryRun -> ViewText.STATUS_DRY_RUN
            else -> ViewText.STATUS_SUCCESS
        }
    val statusClass =
        when {
            !success -> CssClass.Badge.Failed
            dryRun -> CssClass.Badge.Info
            else -> CssClass.Badge.Success
        }
    val vol = dynamicNumber(t.volume) ?: 0.0
    val amt = dynamicNumber(t.usdAmount) ?: 0.0
    val price = dynamicNumber(t.price) ?: 0.0
    val fee = dynamicNumber(t.fee) ?: 0.0
    val slippage = dynamicNumber(t.slippagePercent)
    val isEstimatedEconomics = dryRun || t.source == TradeSourceKeys.LOCAL_ESTIMATE
    val estimatedTitle =
        if (isEstimatedEconomics) {
            ViewText.SLIPPAGE_ESTIMATED_TITLE
        } else {
            null
        }

    val isPlainSuccess = success && !dryRun

    tr.appendChild(createCell(time, CssClass.Table.MonoCol))
    tr.appendChild(createCell(formatPair(t), CssClass.Table.SymbolCol))
    tr.appendChild(createBadgeCell(side, sideClass))
    tr.appendChild(createCell(vol.toFixed(PrecisionConstants.SCALE_CRYPTO), CssClass.Table.MonoCol))
    tr.appendChild(createCell(formatUSD(amt), CssClass.Table.MonoCol))
    // HIST-3: price keeps crypto precision (4-8dp) and fee keeps up to 4dp; zero/missing
    // economics show a muted em-dash, not 0.00000000.
    tr.appendChild(createCellWithOptionalTitle(formatPriceOrDash(price), CssClass.Table.MonoCol, estimatedTitle))
    tr.appendChild(createCellWithOptionalTitle(formatFeeOrDash(fee), CssClass.Table.MonoCol, estimatedTitle))
    tr.appendChild(createSlippageCell(slippage, estimatedTitle))
    tr.appendChild(createStatusCell(statusText, statusClass, t.errorMessage, isPlainSuccess))

    return tr
}

/** HIST-3: format a trade price at crypto precision, or a muted em-dash when it is zero/absent. */
private fun formatPriceOrDash(value: Double): String {
    if (value == 0.0) return ViewText.EM_DASH
    val options: dynamic = json()
    options.minimumFractionDigits = PrecisionConstants.MIN_CRYPTO_DECIMAL_PLACES
    options.maximumFractionDigits = PrecisionConstants.SCALE_CRYPTO
    return "$" + value.asDynamic().toLocaleString(EN_US, options)
}

/** HIST-3: format a trade fee at up to 4dp, or a muted em-dash when it is zero/absent. */
private fun formatFeeOrDash(value: Double): String {
    if (value == 0.0) return ViewText.EM_DASH
    val options: dynamic = json()
    options.minimumFractionDigits = PrecisionConstants.SCALE_USD
    options.maximumFractionDigits = PrecisionConstants.SCALE_FEE
    return "$" + value.asDynamic().toLocaleString(EN_US, options)
}

private fun slippageBadgeClass(value: Double): CssClass = when {
    value > 0.0 -> CssClass.Badge.SlippageAdverse
    value < 0.0 -> CssClass.Badge.SlippageFavorable
    else -> CssClass.Badge.SlippageNeutral
}

private fun formatSignedSlippage(value: Double): String = formatPctTick(value, includePlus = true)

private fun createCellWithOptionalTitle(text: String, cssClass: CssClass, title: String?): HTMLTableCellElement {
    val td = createCell(text, cssClass)
    if (title != null) td.title = title
    return td
}

private fun createSlippageCell(slippage: Double?, estimatedTitle: String?): HTMLTableCellElement {
    val td = document.createElement(HtmlTags.TD) as HTMLTableCellElement
    td.className = CssClass.Table.MonoCol.toString()
    if (slippage == null) {
        td.textContent = ViewText.EM_DASH
        return td
    }
    val span = document.createElement(HtmlTags.SPAN) as HTMLSpanElement
    span.className = slippageBadgeClass(slippage).toString()
    span.textContent = formatSignedSlippage(slippage)
    if (estimatedTitle != null) span.title = estimatedTitle
    td.appendChild(span)
    return td
}

private fun createStatusCell(
    text: String,
    badgeClass: CssClass,
    errorMessage: String?,
    isPlainSuccess: Boolean,
): HTMLTableCellElement {
    val td = document.createElement(HtmlTags.TD) as HTMLTableCellElement
    // HIST-3: a plain success is a quiet dot (removes the always-"SUCCESS" constant column);
    // only failures and dry-run rows keep a labelled badge.
    if (isPlainSuccess) {
        val dot = document.createElement(HtmlTags.SPAN) as HTMLSpanElement
        dot.className = CssClass.Table.StatusDot.toString()
        dot.title = ViewText.STATUS_SUCCESS
        dot.setAttribute(HtmlAttrs.ARIA_LABEL, ViewText.STATUS_SUCCESS)
        dot.setAttribute(HtmlAttrs.ROLE, "img")
        td.appendChild(dot)
        return td
    }
    val span = document.createElement(HtmlTags.SPAN) as HTMLSpanElement
    span.className = badgeClass.toString()
    span.textContent = text
    if (!errorMessage.isNullOrBlank()) {
        span.title = ViewText.TRADE_FAILED_TITLE_PREFIX + errorMessage
    }
    td.appendChild(span)
    return td
}

private fun createCell(text: String, cssClass: CssClass): HTMLTableCellElement {
    val td = document.createElement(HtmlTags.TD) as HTMLTableCellElement
    td.className = cssClass.toString()
    td.textContent = text
    return td
}

private fun createBadgeCell(text: String, badgeClass: CssClass): HTMLTableCellElement {
    val td = document.createElement(HtmlTags.TD) as HTMLTableCellElement
    val span = document.createElement(HtmlTags.SPAN) as HTMLSpanElement
    span.className = badgeClass.toString()
    span.textContent = text
    td.appendChild(span)
    return td
}

internal fun updateStats(stats: HistoryStats) {
    val athTitle = document.getElementById(HtmlIds.STAT_ATH_TITLE)
    val ath = document.getElementById(HtmlIds.STAT_ATH)
    val totalTrades = document.getElementById(HtmlIds.STAT_TOTAL_TRADES)
    val totalVolume = document.getElementById(HtmlIds.STAT_TOTAL_VOLUME)
    val totalFees = document.getElementById(HtmlIds.STAT_TOTAL_FEES)
    val avgFeeRate = document.getElementById(HtmlIds.STAT_AVG_FEE_RATE)
    val avgSlippage = document.getElementById(HtmlIds.STAT_AVG_SLIPPAGE)

    if (athTitle != null) {
        athTitle.textContent =
            if (currentRange == TimeRange.ALL.key) {
                ViewText.HISTORY_ALL_TIME_HIGH
            } else {
                ViewText.PERIOD_HIGH
            }
    }
    if (ath != null) ath.textContent = formatUSD(dynamicNumber(stats.allTimeHigh) ?: 0.0)
    if (totalTrades != null) {
        val count = stats.totalTradesExecuted.toDouble()
        totalTrades.textContent = count.asDynamic().toLocaleString()
    }
    if (totalVolume != null) {
        totalVolume.textContent =
            formatUSD(dynamicNumber(stats.totalVolumeTraded) ?: 0.0)
    }
    if (totalFees != null) totalFees.textContent = formatUSD(dynamicNumber(stats.totalFeesPaid) ?: 0.0)
    if (avgFeeRate != null) {
        val rate = dynamicNumber(stats.avgFeeRatePercent)
        avgFeeRate.textContent =
            if (rate == null) {
                ViewText.PLACEHOLDER_DASHES
            } else {
                formatPctTick(rate, includePlus = false)
            }
    }
    if (avgSlippage != null) {
        val slip = dynamicNumber(stats.avgSlippagePercent)
        avgSlippage.textContent =
            if (slip == null) {
                ViewText.PLACEHOLDER_DASHES
            } else {
                formatSignedSlippage(slip)
            }
    }
}
