package com.gemini.krakenbot.view.component

import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.service.history.HistoryBalanceCheckpoint
import com.gemini.krakenbot.service.history.HistoryEventQuery
import com.gemini.krakenbot.service.history.HistoryLedger
import com.gemini.krakenbot.service.history.HistoryObservation
import com.gemini.krakenbot.service.history.HistoryPage
import com.gemini.krakenbot.service.history.HistorySyncState
import com.gemini.krakenbot.service.history.HistorySyncStatus
import com.gemini.krakenbot.service.history.HistoryTrade
import com.gemini.krakenbot.view.util.ActiveNav
import com.gemini.krakenbot.view.util.CdnIntegrity
import com.gemini.krakenbot.view.util.CdnUrls
import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.FormFields
import com.gemini.krakenbot.view.util.HtmlIds
import com.gemini.krakenbot.view.util.Routes
import com.gemini.krakenbot.view.util.ViewText
import com.gemini.krakenbot.view.util.brandWithMode
import com.gemini.krakenbot.view.util.cdnScript
import com.gemini.krakenbot.view.util.commonMetadataAndStyles
import com.gemini.krakenbot.view.util.div
import com.gemini.krakenbot.view.util.h2
import com.gemini.krakenbot.view.util.loopControl
import com.gemini.krakenbot.view.util.p
import com.gemini.krakenbot.view.util.primaryNav
import com.gemini.krakenbot.view.util.span
import com.gemini.krakenbot.view.util.withQuery
import kotlinx.html.ButtonType
import kotlinx.html.DIV
import kotlinx.html.FormMethod
import kotlinx.html.HTML
import kotlinx.html.InputType
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.button
import kotlinx.html.form
import kotlinx.html.head
import kotlinx.html.header
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.title
import kotlinx.html.tr
import kotlinx.html.unsafe
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Everything the History page renders; assembled by the controller from [HistoryService] queries. */
data class HistoryPageData(
    val range: String,
    val filters: HistoryEventQuery,
    val tradesOffset: Int,
    val ledgersOffset: Int,
    val observations: List<HistoryObservation>,
    val checkpoints: HistoryPage<HistoryBalanceCheckpoint>,
    val trades: HistoryPage<HistoryTrade>,
    val ledgers: HistoryPage<HistoryLedger>,
    val syncStatuses: List<HistorySyncStatus>,
    val accountAvailable: Boolean,
)

class HistoryPageComponent {
    private val timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC)

    context(html: HTML)
    fun render(settings: Settings, data: HistoryPageData, csrfToken: String?, paused: Boolean) {
        html.head {
            commonMetadataAndStyles()
            title("${ViewText.HISTORY_TITLE} - ${ViewText.APP_TITLE}")
            cdnScript(CdnUrls.HTMX, CdnIntegrity.HTMX)
        }
        html.body {
            div(CssClass.Layout.Container) {
                header {
                    brandWithMode(settings) { loopControl(paused, csrfToken) }
                    div(CssClass.Layout.HeaderActions) { primaryNav(ActiveNav.HISTORY) }
                }
                renderSync(data, csrfToken)
                renderToolbar(data)
                renderValueChart(data)
                renderCheckpointChart(data)
                renderTrades(data)
                renderLedgers(data)
            }
        }
    }

    private fun DIV.renderSync(data: HistoryPageData, csrfToken: String?) {
        div(CssClass.Layout.GlassPanel + CssClass.History.SyncBanner) {
            id = HtmlIds.HISTORY_SYNC_BANNER
            span(CssClass.History.SyncTitle) { +ViewText.HISTORY_SYNC_TITLE }
            if (!data.accountAvailable) {
                p(CssClass.History.SyncText) { +ViewText.HISTORY_ACCOUNT_UNAVAILABLE }
            }
            data.syncStatuses.forEach { status ->
                p(CssClass.History.SyncText) {
                    +syncLine(status)
                }
            }
            if (data.accountAvailable) {
                form(action = Routes.API_HISTORY_SYNC_START, method = FormMethod.post) {
                    input(type = InputType.hidden, name = FormFields.CSRF_TOKEN) {
                        value = csrfToken.orEmpty()
                    }
                    button(type = ButtonType.submit, classes = CssClass.Button.Secondary.value) {
                        id = HtmlIds.HISTORY_SYNC_BUTTON
                        +ViewText.HISTORY_SYNC_BUTTON
                    }
                }
            }
        }
    }

    private fun syncLine(status: HistorySyncStatus): String {
        val state = when (status.state) {
            HistorySyncState.NOT_STARTED -> "not started"
            HistorySyncState.RUNNING -> "running"
            HistorySyncState.COMPLETE -> "complete"
            HistorySyncState.PAUSED -> "paused"
            HistorySyncState.FAILED -> "failed: ${status.lastError ?: "unknown error"}"
        }
        val range = listOfNotNull(status.windowStart, status.windowEnd).joinToString(" – ") { timeFormatter.format(it) }
        return "${status.feed.name.lowercase()}: $state; ${status.storedCount} stored, ${status.fetchedCount} fetched" +
            if (range.isNotEmpty()) "; window $range" else ""
    }

    private fun DIV.renderToolbar(data: HistoryPageData) {
        div(CssClass.History.ToolbarRow) {
            div(CssClass.History.TimeRangeSelector) {
                RANGES.forEach { (key, label) ->
                    a(
                        href = pageUrl(data, range = key, tradesOffset = 0, ledgersOffset = 0),
                        classes = (
                            if (data.range ==
                                key
                            ) {
                                CssClass.History.TimeRangeBtnActive
                            } else {
                                CssClass.History.TimeRangeBtn
                            }
                            ).value,
                    ) { +label }
                }
            }
            form(action = Routes.HISTORY, method = FormMethod.get, classes = CssClass.History.FilterRow.value) {
                input(type = InputType.hidden, name = "range") { value = data.range }
                filterInput(HtmlIds.HISTORY_FILTER_ASSET, "asset", ViewText.HISTORY_FILTER_ASSET, data.filters.assetKey)
                filterInput(HtmlIds.HISTORY_FILTER_PAIR, "pair", ViewText.HISTORY_FILTER_PAIR, data.filters.pair)
                filterInput(HtmlIds.HISTORY_FILTER_TYPE, "type", ViewText.HISTORY_FILTER_TYPE, data.filters.type)
                button(type = ButtonType.submit, classes = CssClass.History.PaginationBtn.value) { +"Apply" }
            }
        }
    }

    private fun kotlinx.html.FORM.filterInput(inputId: String, name: String, placeholder: String, current: String?) {
        input(type = InputType.text, name = name, classes = CssClass.History.FilterInput.value) {
            id = inputId
            this.placeholder = placeholder
            value = current.orEmpty()
        }
    }

    private fun DIV.renderValueChart(data: HistoryPageData) {
        val points = data.observations.mapNotNull { observation ->
            observation.totalUsd?.let { observation.observedAt to it }
        }.sortedBy { it.first }
        div(CssClass.Layout.GlassPanel) {
            h2(CssClass.Utility.GlassPanelTitle) { +ViewText.HISTORY_PORTFOLIO_VALUE }
            div(CssClass.History.ChartContainer) {
                id = HtmlIds.HISTORY_VALUE_CHART
                if (points.isEmpty()) {
                    p(CssClass.History.MutedSmallText) { +ViewText.HISTORY_NO_DATA }
                } else {
                    unsafe { +pointSvg(points) }
                }
            }
            p(CssClass.History.ChartCaption) { +ViewText.HISTORY_VALUE_CAPTION }
        }
    }

    private fun DIV.renderCheckpointChart(data: HistoryPageData) {
        val asset = data.filters.assetKey
        val points = data.checkpoints.items.asReversed().mapNotNull { checkpoint ->
            val at = checkpoint.observedAt
            val quantity = checkpoint.quantity
            if (at != null && quantity != null) at to quantity else null
        }
        div(CssClass.Layout.GlassPanel) {
            h2(CssClass.Utility.GlassPanelTitle) {
                +"${ViewText.HISTORY_ASSET_HOLDINGS}${asset?.let { " ($it)" }.orEmpty()}"
            }
            div(CssClass.History.ChartContainer) {
                id = HtmlIds.HISTORY_ASSET_CHART
                when {
                    asset.isNullOrBlank() -> p(CssClass.History.MutedSmallText) { +ViewText.HISTORY_SELECT_ASSET }
                    points.isEmpty() -> p(CssClass.History.MutedSmallText) { +ViewText.HISTORY_NO_DATA }
                    else -> unsafe { +pointSvg(points) }
                }
            }
            p(CssClass.History.ChartCaption) { +ViewText.HISTORY_CHECKPOINT_CAPTION }
        }
    }

    private fun DIV.renderTrades(data: HistoryPageData) {
        div(CssClass.Layout.GlassPanel + CssClass.History.TradeLog) {
            h2(CssClass.Utility.GlassPanelTitle) { +ViewText.HISTORY_TRADE_LOG }
            div(CssClass.Table.Wrapper) {
                table {
                    thead {
                        tr {
                            listOf(
                                ViewText.HEADER_TIME,
                                ViewText.HEADER_PAIR,
                                ViewText.HEADER_SIDE,
                                ViewText.HEADER_VOLUME,
                                ViewText.HEADER_PRICE,
                                ViewText.HISTORY_COST,
                                ViewText.HEADER_FEE,
                            ).forEach { th { +it } }
                        }
                    }
                    tbody {
                        id = HtmlIds.HISTORY_TRADES_BODY
                        if (data.trades.items.isEmpty()) emptyRow(TRADE_COLUMNS)
                        data.trades.items.forEach { t ->
                            tr {
                                td { +(t.time?.let(timeFormatter::format) ?: t.rawTime.orEmpty()) }
                                td { +t.pair.orEmpty() }
                                td { +t.type.orEmpty() }
                                td { +t.volume.orEmpty() }
                                td { +t.price.orEmpty() }
                                td { +t.cost.orEmpty() }
                                // Kraken does not establish the trade fee denomination in this response.
                                td { +"${t.fee.orEmpty()} ${t.feeCurrency ?: ViewText.HISTORY_UNKNOWN_CURRENCY}" }
                            }
                        }
                    }
                }
            }
            pagination(data, data.trades, isTrades = true)
        }
    }

    private fun DIV.renderLedgers(data: HistoryPageData) {
        div(CssClass.Layout.GlassPanel + CssClass.History.TradeLog) {
            h2(CssClass.Utility.GlassPanelTitle) { +ViewText.HISTORY_TRANSACTION_LOG }
            div(CssClass.Table.Wrapper) {
                table {
                    thead {
                        tr {
                            listOf(
                                ViewText.HEADER_TIME,
                                ViewText.HISTORY_TYPE,
                                ViewText.HISTORY_ASSET,
                                ViewText.HISTORY_AMOUNT,
                                ViewText.HEADER_FEE,
                                ViewText.HISTORY_BALANCE,
                            ).forEach { th { +it } }
                        }
                    }
                    tbody {
                        id = HtmlIds.HISTORY_LEDGERS_BODY
                        if (data.ledgers.items.isEmpty()) emptyRow(LEDGER_COLUMNS)
                        data.ledgers.items.forEach { l ->
                            tr {
                                td { +(l.time?.let(timeFormatter::format) ?: l.rawTime.orEmpty()) }
                                td { +listOfNotNull(l.type, l.subtype).joinToString(" / ") }
                                td { +l.asset.orEmpty() }
                                td { +l.amount.orEmpty() }
                                td { +l.fee.orEmpty() }
                                td { +l.balance.orEmpty() }
                            }
                        }
                    }
                }
            }
            pagination(data, data.ledgers, isTrades = false)
        }
    }

    private fun kotlinx.html.TBODY.emptyRow(columns: Int) {
        tr {
            td(CssClass.History.EmptyTableCell.value) {
                colSpan = columns.toString()
                +ViewText.HISTORY_NO_DATA
            }
        }
    }

    private fun DIV.pagination(data: HistoryPageData, page: HistoryPage<*>, isTrades: Boolean) {
        div(CssClass.History.Pagination) {
            val offset = page.offset
            val previous = (offset - page.limit).coerceAtLeast(0)
            val next = offset + page.limit
            if (offset > 0) {
                a(
                    href = pageUrl(
                        data,
                        tradesOffset = if (isTrades) previous else null,
                        ledgersOffset = if (isTrades) null else previous,
                    ),
                    classes = CssClass.History.PaginationBtn.value,
                ) {
                    id = if (isTrades) HtmlIds.HISTORY_TRADES_PREV else HtmlIds.HISTORY_LEDGERS_PREV
                    +ViewText.HISTORY_PREVIOUS
                }
            }
            span(CssClass.History.MutedSmallText) {
                +"${offset + 1}–${offset + page.items.size}${page.total?.let { " of $it" }.orEmpty()}"
            }
            if (page.hasMore) {
                a(
                    href = pageUrl(
                        data,
                        tradesOffset = if (isTrades) next else null,
                        ledgersOffset = if (isTrades) null else next,
                    ),
                    classes = CssClass.History.PaginationBtn.value,
                ) {
                    id = if (isTrades) HtmlIds.HISTORY_TRADES_NEXT else HtmlIds.HISTORY_LEDGERS_NEXT
                    +ViewText.HISTORY_NEXT
                }
            }
        }
    }

    private fun pageUrl(
        data: HistoryPageData,
        range: String = data.range,
        tradesOffset: Int? = null,
        ledgersOffset: Int? = null,
    ): String {
        var url = Routes.HISTORY.withQuery("range", range)
        data.filters.assetKey?.let { url = url.withQuery("asset", it) }
        data.filters.pair?.let { url = url.withQuery("pair", it) }
        data.filters.type?.let { url = url.withQuery("type", it) }
        // A pagination link changes only its own table's offset and preserves the other.
        val trades = tradesOffset ?: data.tradesOffset
        val ledgers = ledgersOffset ?: data.ledgersOffset
        if (trades > 0) url = url.withQuery("tradesOffset", trades)
        if (ledgers > 0) url = url.withQuery("ledgersOffset", ledgers)
        return url
    }

    /** Time-proportional SVG markers show direct samples without implying values between them. */
    private fun pointSvg(points: List<Pair<Instant, BigDecimal>>): String {
        val minT = points.minOf { it.first.epochSecond }
        val maxT = points.maxOf { it.first.epochSecond }
        val minV = points.minOf { it.second }
        val maxV = points.maxOf { it.second }
        val spanT = (maxT - minT).coerceAtLeast(1).toDouble()
        val spanV = maxV.subtract(minV).takeIf { it.signum() != 0 } ?: BigDecimal.ONE
        fun coordinate(point: Pair<Instant, BigDecimal>): Pair<Double, Double> {
            val (time, value) = point
            val x = (time.epochSecond - minT) / spanT * (SVG_WIDTH - 2 * SVG_PAD) + SVG_PAD
            val ratio = value.subtract(minV).divide(spanV, 6, RoundingMode.HALF_UP).toDouble()
            val y = SVG_HEIGHT - SVG_PAD - ratio * (SVG_HEIGHT - 2 * SVG_PAD)
            return x to y
        }
        val markers = points.joinToString("") { point ->
            val (x, y) = coordinate(point)
            """<circle class="${CssClass.History.Point.value}" cx="%.2f" cy="%.2f" r="4"/>"""
                .format(java.util.Locale.US, x, y)
        }
        return """<svg class="${CssClass.History.ChartSvg.value}" viewBox="0 0 $SVG_WIDTH $SVG_HEIGHT" """ +
            """preserveAspectRatio="none">$markers""" +
            """<text class="${CssClass.History.AxisLabel.value}" x="4" y="12">${minV.max(
                maxV,
            ).stripTrailingZeros().toPlainString()}</text>""" +
            """<text class="${CssClass.History.AxisLabel.value}" x="4" y="${SVG_HEIGHT - 4}">${minV.stripTrailingZeros().toPlainString()}</text></svg>"""
    }

    private companion object {
        val RANGES = listOf("24h" to "24h", "7d" to "7d", "30d" to "30d", "90d" to "90d", "all" to "All")
        const val TRADE_COLUMNS = 7
        const val LEDGER_COLUMNS = 6
        const val SVG_WIDTH = 800
        const val SVG_HEIGHT = 240
        const val SVG_PAD = 32
    }
}
