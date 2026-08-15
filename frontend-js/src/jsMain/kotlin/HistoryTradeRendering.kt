package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.api.HistoryStats
import com.gemini.krakenbot.api.TradeRecord
import com.gemini.krakenbot.model.OrderSide
import com.gemini.krakenbot.model.TimeRange
import com.gemini.krakenbot.model.TradeSource
import com.gemini.krakenbot.util.FormatSpec
import com.gemini.krakenbot.util.PrecisionConstants
import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.HtmlIds
import com.gemini.krakenbot.view.util.HtmlTags
import com.gemini.krakenbot.view.util.ViewText
import kotlinx.browser.document
import org.w3c.dom.*
import kotlin.js.Date

fun formatPair(trade: TradeRecord): String {
    if (trade.symbol.isBlank()) return ""
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
    tr.className = (CssClass.Table.Hoverable + CssClass.History.TradeCard).toString()

    val time = formatCompactTradeTime(t.timestamp)
    val fullTime = Date(t.timestamp).asDynamic().toLocaleString().toString()
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
    val isEstimatedEconomics = dryRun || t.source == TradeSource.LOCAL_ESTIMATE.name
    val estimatedTitle =
        if (isEstimatedEconomics) {
            ViewText.SLIPPAGE_ESTIMATED_TITLE
        } else {
            null
        }

    val isPlainSuccess = success && !dryRun

    tr.appendChild(createCell(time, CssClass.Table.MonoCol, fullTime))
    tr.appendChild(createCell(formatPair(t), CssClass.Table.SymbolCol))
    tr.appendChild(createBadgeCell(side, sideClass))
    tr.appendChild(createCell(formatQuantity(vol), CssClass.Table.MonoCol))
    tr.appendChild(createCell(formatUSD(amt), CssClass.Table.MonoCol))
    // Keep ordinary prices and fees calm while preserving meaningful sub-cent precision.
    tr.appendChild(createCell(formatPriceOrDash(price), CssClass.Table.MonoCol, estimatedTitle))
    tr.appendChild(createCell(formatFeeOrDash(fee), CssClass.Table.MonoCol, estimatedTitle))
    tr.appendChild(createSlippageCell(slippage, estimatedTitle))
    tr.appendChild(createStatusCell(statusText, statusClass, t.errorMessage, isPlainSuccess))

    return tr
}

private fun usdCellOrDash(value: Double, min: Int, max: Int): String {
    if (value == 0.0) return ViewText.EM_DASH
    return "$" + usdOptionsToLocale(value, min, max)
}

/** HIST-3: format a trade price at crypto precision, or a muted em-dash when it is zero/absent. */
private fun formatPriceOrDash(value: Double): String =
    usdCellOrDash(value, PrecisionConstants.SCALE_USD, priceDigits(value))

private fun formatFeeOrDash(value: Double): String =
    usdCellOrDash(value, PrecisionConstants.SCALE_USD, feeDigits(value))

private fun formatQuantity(value: Double): String =
    usdOptionsToLocale(value, minDigits = 0, maxDigits = FormatSpec.quantityDigits())

private fun priceDigits(value: Double): Int = FormatSpec.priceDigits(value)

private fun feeDigits(value: Double): Int = FormatSpec.feeDigits(value)

private fun slippageBadgeClass(value: Double): CssClass = when {
    value > 0.0 -> CssClass.Badge.SlippageAdverse
    value < 0.0 -> CssClass.Badge.SlippageFavorable
    else -> CssClass.Badge.SlippageNeutral
}

private fun formatSignedSlippage(value: Double): String = formatPctTick(value, includePlus = true)

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

private fun createCell(text: String, cssClass: CssClass, title: String? = null): HTMLTableCellElement {
    val td = document.createElement(HtmlTags.TD) as HTMLTableCellElement
    td.className = cssClass.toString()
    td.textContent = text
    if (title != null) td.title = title
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
            if (loadedRange == TimeRange.ALL.key) {
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
