package com.gemini.krakenbot.view.component

import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.OrderSide
import com.gemini.krakenbot.service.history.HistoryBalanceCheckpoint
import com.gemini.krakenbot.service.history.HistoryBalanceStatus
import com.gemini.krakenbot.service.history.HistoryEventFilterOptions
import com.gemini.krakenbot.service.history.HistoryEventQuery
import com.gemini.krakenbot.service.history.HistoryFeed
import com.gemini.krakenbot.service.history.HistoryLedger
import com.gemini.krakenbot.service.history.HistoryObservation
import com.gemini.krakenbot.service.history.HistoryObservationStatus
import com.gemini.krakenbot.service.history.HistoryPage
import com.gemini.krakenbot.service.history.HistorySyncState
import com.gemini.krakenbot.service.history.HistorySyncStatus
import com.gemini.krakenbot.service.history.HistoryTrade
import com.gemini.krakenbot.service.history.HistoryTradeSummary
import com.gemini.krakenbot.util.PrecisionConstants
import com.gemini.krakenbot.view.util.ActiveNav
import com.gemini.krakenbot.view.util.CdnIntegrity
import com.gemini.krakenbot.view.util.CdnUrls
import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.FormFields
import com.gemini.krakenbot.view.util.Formatter
import com.gemini.krakenbot.view.util.HtmlIds
import com.gemini.krakenbot.view.util.Icons
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
import com.gemini.krakenbot.view.util.rebalancerJsSrc
import com.gemini.krakenbot.view.util.span
import com.gemini.krakenbot.view.util.statusCard
import com.gemini.krakenbot.view.util.td
import com.gemini.krakenbot.view.util.th
import com.gemini.krakenbot.view.util.tr
import com.gemini.krakenbot.view.util.withQuery
import kotlinx.html.ButtonType
import kotlinx.html.DIV
import kotlinx.html.FormMethod
import kotlinx.html.HTML
import kotlinx.html.InputType
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.button
import kotlinx.html.dd
import kotlinx.html.details
import kotlinx.html.dl
import kotlinx.html.dt
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.head
import kotlinx.html.header
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.nav
import kotlinx.html.option
import kotlinx.html.script
import kotlinx.html.select
import kotlinx.html.summary
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.thead
import kotlinx.html.title
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
    val filterOptions: HistoryEventFilterOptions = HistoryEventFilterOptions(),
    val tradeSummary: HistoryTradeSummary = HistoryTradeSummary(),
)

class HistoryPageComponent {
    private val timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC)

    context(html: HTML)
    fun render(settings: Settings, data: HistoryPageData, csrfToken: String?, paused: Boolean) {
        html.head {
            commonMetadataAndStyles()
            title("${ViewText.HISTORY_TITLE} - ${ViewText.APP_TITLE}")
            cdnScript(CdnUrls.HTMX, CdnIntegrity.HTMX)
            cdnScript(CdnUrls.CHART_JS, CdnIntegrity.CHART_JS)
        }
        html.body {
            div(CssClass.Layout.Container) {
                header {
                    brandWithMode(settings) { loopControl(paused, csrfToken) }
                    div(CssClass.Layout.HeaderActions) { primaryNav(ActiveNav.HISTORY) }
                }
                div(CssClass.History.PageHeading) {
                    h1 { +ViewText.HISTORY_TITLE }
                    p { +ViewText.HISTORY_PAGE_INTRO }
                }
                renderToolbar(data)
                renderSectionNav()
                renderSync(data, csrfToken)
                renderStatsGrid(data)
                renderValueChart(data)
                renderCheckpointChart(data)
                renderTrades(data)
                renderLedgers(data)
            }
            script(src = rebalancerJsSrc()) {}
        }
    }

    private fun DIV.renderSectionNav() {
        nav(classes = CssClass.History.SectionNav.value) {
            listOf(
                HtmlIds.HISTORY_PORTFOLIO_SECTION to ViewText.HISTORY_JUMP_PORTFOLIO,
                HtmlIds.HISTORY_TRADES_SECTION to ViewText.HISTORY_JUMP_TRADES,
                HtmlIds.HISTORY_LEDGERS_SECTION to ViewText.HISTORY_JUMP_LEDGERS,
            ).forEach { (section, label) ->
                a(href = "#$section", classes = CssClass.History.TimeRangeBtn.value) { +label }
            }
        }
    }

    private fun DIV.renderSync(data: HistoryPageData, csrfToken: String?) {
        div(CssClass.Layout.GlassPanel + CssClass.History.SyncBanner) {
            id = HtmlIds.HISTORY_SYNC_BANNER
            div(CssClass.History.SyncHeader) {
                h2(CssClass.History.SyncTitle) { +ViewText.HISTORY_SYNC_TITLE }
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
            if (!data.accountAvailable) {
                p(CssClass.History.SyncText) { +ViewText.HISTORY_ACCOUNT_UNAVAILABLE }
            } else if (data.syncStatuses.isEmpty()) {
                p(CssClass.History.SyncText) { +ViewText.HISTORY_SYNC_NO_STATUS }
            }
            data.syncStatuses.forEach { status ->
                div(CssClass.History.SyncFeed) {
                    span(CssClass.History.SyncTitle) { +syncFeedLabel(status.feed) }
                    val badge = when (status.state) {
                        HistorySyncState.FAILED -> CssClass.Badge.Sell
                        HistorySyncState.COMPLETE -> CssClass.Badge.Buy
                        else -> CssClass.Badge.Info
                    }
                    span(badge) { +syncStateLabel(status.state) }
                    if (status.state == HistorySyncState.FAILED) {
                        p(CssClass.History.SyncText) {
                            +(status.lastError ?: ViewText.HISTORY_SYNC_UNKNOWN_ERROR)
                        }
                    }
                }
            }
            if (data.syncStatuses.isNotEmpty()) {
                details {
                    summary { +ViewText.HISTORY_SYNC_DETAILS }
                    data.syncStatuses.forEach { status ->
                        div(CssClass.History.SyncText) {
                            span(CssClass.History.SyncTitle) { +syncFeedLabel(status.feed) }
                            dl {
                                dt { +ViewText.HISTORY_SYNC_STORED }
                                dd { +status.storedCount.toString() }
                                dt { +ViewText.HISTORY_SYNC_FETCHED }
                                dd { +status.fetchedCount.toString() }
                                status.lastPageAt?.let { updated ->
                                    dt { +ViewText.HISTORY_SYNC_UPDATED }
                                    dd { +timeFormatter.format(updated) }
                                }
                                val window = listOfNotNull(status.windowStart, status.windowEnd)
                                if (window.isNotEmpty()) {
                                    dt { +ViewText.HISTORY_SYNC_WINDOW }
                                    dd { +window.joinToString(" – ", transform = timeFormatter::format) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun syncFeedLabel(feed: HistoryFeed): String = when (feed) {
        HistoryFeed.TRADES -> ViewText.HISTORY_SYNC_TRADES
        HistoryFeed.LEDGERS -> ViewText.HISTORY_SYNC_LEDGERS
    }

    private fun syncStateLabel(state: HistorySyncState): String = when (state) {
        HistorySyncState.NOT_STARTED -> ViewText.HISTORY_SYNC_NOT_STARTED
        HistorySyncState.RUNNING -> ViewText.HISTORY_SYNC_RUNNING
        HistorySyncState.COMPLETE -> ViewText.HISTORY_SYNC_COMPLETE
        HistorySyncState.PAUSED -> ViewText.HISTORY_SYNC_PAUSED
        HistorySyncState.FAILED -> ViewText.HISTORY_SYNC_FAILED
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
                filterSelect(
                    HtmlIds.HISTORY_FILTER_ASSET,
                    "asset",
                    ViewText.HISTORY_FILTER_ASSET,
                    ViewText.HISTORY_FILTER_ALL_ASSETS,
                    data.filters.assetKey,
                    data.filterOptions.assets,
                ) { asset ->
                    val normalized = Asset.normalizeLedgerAsset(asset)
                    if (normalized.equals(asset, ignoreCase = true)) asset else "$asset ($normalized)"
                }
                filterSelect(
                    HtmlIds.HISTORY_FILTER_PAIR,
                    "pair",
                    ViewText.HISTORY_FILTER_PAIR,
                    ViewText.HISTORY_FILTER_ALL_PAIRS,
                    data.filters.pair,
                    data.filterOptions.pairs,
                )
                filterSelect(
                    HtmlIds.HISTORY_FILTER_TYPE,
                    "type",
                    ViewText.HISTORY_FILTER_TYPE,
                    ViewText.HISTORY_FILTER_ALL_TYPES,
                    data.filters.type,
                    data.filterOptions.types,
                )
                button(type = ButtonType.submit, classes = CssClass.History.PaginationBtn.value) {
                    +ViewText.HISTORY_APPLY_FILTERS
                }
                if (data.filters.assetKey != null || data.filters.pair != null || data.filters.type != null) {
                    a(
                        href = Routes.HISTORY.withQuery("range", data.range),
                        classes = CssClass.History.PaginationBtn.value,
                    ) { +ViewText.HISTORY_CLEAR_FILTERS }
                }
            }
            renderSavedViews()
            p(CssClass.History.MutedSmallText) { +ViewText.HISTORY_FILTER_SCOPE }
        }
    }

    private fun DIV.renderSavedViews() {
        div(CssClass.History.FilterRow) {
            id = HtmlIds.HISTORY_SAVED_VIEWS
            attributes["hidden"] = "hidden"
            label {
                htmlFor = HtmlIds.HISTORY_SAVED_VIEW
                +ViewText.HISTORY_SAVED_VIEWS
                select(classes = CssClass.History.FilterInput.value) {
                    id = HtmlIds.HISTORY_SAVED_VIEW
                    option {
                        value = ""
                        +ViewText.HISTORY_CURRENT_VIEW
                    }
                }
            }
            button(type = ButtonType.button, classes = CssClass.History.PaginationBtn.value) {
                id = HtmlIds.HISTORY_SAVE_VIEW
                +ViewText.HISTORY_SAVE_VIEW
            }
            button(type = ButtonType.button, classes = CssClass.History.PaginationBtn.value) {
                id = HtmlIds.HISTORY_DELETE_VIEW
                disabled = true
                +ViewText.HISTORY_DELETE_VIEW
            }
        }
    }

    private fun kotlinx.html.FORM.filterSelect(
        inputId: String,
        name: String,
        labelText: String,
        allLabel: String,
        current: String?,
        options: List<String>,
        display: (String) -> String = { it },
    ) {
        label {
            htmlFor = inputId
            +labelText
            select(classes = CssClass.History.FilterInput.value) {
                this.name = name
                id = inputId
                option {
                    value = ""
                    selected = current.isNullOrEmpty()
                    +allLabel
                }
                (options + listOfNotNull(current)).filter(String::isNotBlank).distinct().sorted().forEach { value ->
                    option {
                        this.value = value
                        selected = current == value
                        +display(value)
                    }
                }
            }
        }
    }

    private fun DIV.renderStatsGrid(data: HistoryPageData) {
        div(CssClass.History.StatsGrid) {
            HistoryStatCardDefinition.entries.forEach { card ->
                statusCard(
                    title = card.title,
                    iconSvg = card.iconSvg,
                    value = card.formatValue(data),
                    valueId = card.valueId,
                )
            }
        }
    }

    private fun DIV.renderValueChart(data: HistoryPageData) {
        val observations = data.observations.sortedBy { it.observedAt }
        val hasCompleteValue = observations.any {
            it.status == HistoryObservationStatus.COMPLETE && it.totalUsd != null
        }
        div(CssClass.Layout.GlassPanel) {
            id = HtmlIds.HISTORY_PORTFOLIO_SECTION
            div(CssClass.History.ChartHeader) {
                h2(CssClass.Utility.GlassPanelTitle + CssClass.History.ChartHeaderTitle) {
                    +ViewText.HISTORY_PORTFOLIO_VALUE
                }
                chartControls()
            }
            if (observations.isNotEmpty() && !hasCompleteValue) {
                p(CssClass.History.MutedSmallText) { +ViewText.HISTORY_VALUE_NO_DATA }
            }
            div(CssClass.History.ChartContainer) {
                id = HtmlIds.HISTORY_VALUE_CHART
                if (observations.isEmpty()) {
                    p(CssClass.History.MutedSmallText) { +ViewText.HISTORY_VALUE_NO_DATA }
                }
                if (observations.isNotEmpty()) unsafe { +valuePointSvg(observations) }
            }
            if (hasCompleteValue && observations.any { it.status != HistoryObservationStatus.COMPLETE }) {
                p(CssClass.History.SyncText) { +ViewText.HISTORY_VALUE_PARTIAL }
            }
            p(CssClass.History.ChartCaption) { +ViewText.HISTORY_VALUE_CAPTION }
            p(CssClass.History.ChartCaption) { +ViewText.HISTORY_CHART_HELP }
        }
    }

    private fun DIV.chartControls(pointsOnly: Boolean = false) {
        div(CssClass.History.ChartControls) {
            attributes["data-history-controls"] = "true"
            attributes["hidden"] = "hidden"
            if (!pointsOnly) {
                chartButton("data-history-view", "line", ViewText.HISTORY_CHART_LINE)
                chartButton("data-history-view", "points", ViewText.HISTORY_CHART_POINTS)
            }
            chartButton("data-history-zoom", "in", ViewText.HISTORY_CHART_ZOOM_IN)
            chartButton("data-history-zoom", "out", ViewText.HISTORY_CHART_ZOOM_OUT)
            chartButton("data-history-pan", "earlier", ViewText.HISTORY_CHART_EARLIER)
            chartButton("data-history-pan", "later", ViewText.HISTORY_CHART_LATER)
            chartButton("data-history-zoom", "reset", ViewText.HISTORY_CHART_RESET)
            chartButton("data-history-expand", "true", ViewText.HISTORY_CHART_EXPAND)
        }
    }

    private fun DIV.chartButton(attribute: String, value: String, text: String) {
        button(type = ButtonType.button, classes = CssClass.History.PaginationBtn.value) {
            attributes[attribute] = value
            +text
        }
    }

    private fun DIV.renderCheckpointChart(data: HistoryPageData) {
        val asset = data.filters.assetKey
        val points = data.checkpoints.items.asReversed().mapNotNull { checkpoint ->
            val at = checkpoint.observedAt
            val quantity = checkpoint.quantity
            if (checkpoint.status == HistoryBalanceStatus.VALID && at != null && quantity != null) {
                at to quantity
            } else {
                null
            }
        }
        div(CssClass.Layout.GlassPanel) {
            div(CssClass.History.ChartHeader) {
                h2(CssClass.Utility.GlassPanelTitle + CssClass.History.ChartHeaderTitle) {
                    +"${ViewText.HISTORY_ASSET_HOLDINGS}${asset?.let { " ($it)" }.orEmpty()}"
                }
                chartControls(pointsOnly = true)
            }
            if (asset.isNullOrBlank()) {
                p(CssClass.History.MutedSmallText) { +ViewText.HISTORY_SELECT_ASSET }
            } else {
                div(CssClass.History.ChartContainer) {
                    id = HtmlIds.HISTORY_ASSET_CHART
                    if (points.isEmpty()) {
                        p(CssClass.History.MutedSmallText) { +ViewText.HISTORY_NO_DATA }
                    } else {
                        unsafe { +pointSvg(points) }
                    }
                }
            }
            p(CssClass.History.ChartCaption) { +ViewText.HISTORY_CHECKPOINT_CAPTION }
        }
    }

    private fun DIV.renderTrades(data: HistoryPageData) {
        div(CssClass.Layout.GlassPanel + CssClass.History.TradeLog) {
            id = HtmlIds.HISTORY_TRADES_SECTION
            div(CssClass.History.TradeLogHeader) {
                h2(CssClass.Utility.GlassPanelTitle + CssClass.History.TitleNoMargin) {
                    +ViewText.HISTORY_TRADE_LOG
                }
                data.trades.total?.let { total ->
                    span(CssClass.Badge.Info) { +total.toString() }
                }
            }
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
                            tr(CssClass.Table.Hoverable) {
                                td(CssClass.Table.MonoCol) {
                                    (t.time?.let(timeFormatter::format) ?: t.rawTime?.takeIf { it.isNotBlank() })
                                        ?.let { title = it }
                                    +(t.time?.let(Formatter::formatCompactTradeTime) ?: t.rawTime.orEmpty())
                                }
                                td(CssClass.Table.SymbolCol) {
                                    +Formatter.formatPair(t.baseAsset, t.quoteCurrency, t.pair)
                                }
                                td {
                                    val side = t.type.orEmpty()
                                    val badgeClass = when (side.lowercase()) {
                                        OrderSide.BUY.name.lowercase() -> CssClass.Badge.Buy
                                        OrderSide.SELL.name.lowercase() -> CssClass.Badge.Sell
                                        else -> CssClass.Badge.Info
                                    }
                                    span(badgeClass) { +side.uppercase() }
                                }
                                td(CssClass.Table.MonoCol) {
                                    t.volume?.toBigDecimalOrNull()?.let { +Formatter.formatQuantity(it) }
                                        ?: +t.volume.orEmpty()
                                }
                                td(CssClass.Table.MonoCol) {
                                    +Formatter.formatPriceOrDash(t.price?.toBigDecimalOrNull())
                                }
                                td(CssClass.Table.MonoCol) {
                                    t.cost?.toBigDecimalOrNull()?.let { +Formatter.formatSignedUsd(it) }
                                        ?: +t.cost.orEmpty()
                                }
                                td(CssClass.Table.MonoCol) {
                                    // Kraken omits a fee currency on TradesHistory; spot trade fees settle in
                                    // the quote currency (confirmed against matching USD ledger fee legs).
                                    +Formatter.formatFeeWithCurrency(
                                        t.fee?.toBigDecimalOrNull(),
                                        t.feeCurrency ?: t.quoteCurrency,
                                    )
                                }
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
            id = HtmlIds.HISTORY_LEDGERS_SECTION
            div(CssClass.History.TradeLogHeader) {
                h2(CssClass.Utility.GlassPanelTitle + CssClass.History.TitleNoMargin) {
                    +ViewText.HISTORY_TRANSACTION_LOG
                }
                data.ledgers.total?.let { total ->
                    span(CssClass.Badge.Info) { +total.toString() }
                }
            }
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
                            tr(CssClass.Table.Hoverable) {
                                td(CssClass.Table.MonoCol) {
                                    (l.time?.let(timeFormatter::format) ?: l.rawTime?.takeIf { it.isNotBlank() })
                                        ?.let { title = it }
                                    +(l.time?.let(Formatter::formatCompactTradeTime) ?: l.rawTime.orEmpty())
                                }
                                td {
                                    val type = l.type.orEmpty().replaceFirstChar { c -> c.uppercase() }
                                    val subtype = l.subtype.orEmpty()
                                    val showSubtype = subtype.isNotBlank() &&
                                        !subtype.equals("tradespot", ignoreCase = true) &&
                                        !subtype.equals(l.type, ignoreCase = true)
                                    +if (showSubtype) "$type ($subtype)" else type
                                }
                                td(CssClass.Table.SymbolCol) {
                                    +Asset.normalizeLedgerAsset(l.asset.orEmpty())
                                }
                                td(CssClass.Table.MonoCol) {
                                    l.amount?.toBigDecimalOrNull()?.let { +Formatter.formatQuantity(it) }
                                        ?: +l.amount.orEmpty()
                                }
                                td(CssClass.Table.MonoCol) {
                                    // Kraken settles ledger fees in the ledger's own asset field.
                                    +Formatter.formatFeeWithCurrency(
                                        l.fee?.toBigDecimalOrNull(),
                                        Asset.normalizeLedgerAsset(l.asset.orEmpty()),
                                    )
                                }
                                td(CssClass.Table.MonoCol) {
                                    l.balance?.toBigDecimalOrNull()?.let { +Formatter.formatQuantity(it) }
                                        ?: +l.balance.orEmpty()
                                }
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
            td(CssClass.History.EmptyTableCell) {
                colSpan = columns.toString()
                +ViewText.HISTORY_EVENTS_EMPTY
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
                    ) + "#" + if (isTrades) HtmlIds.HISTORY_TRADES_SECTION else HtmlIds.HISTORY_LEDGERS_SECTION,
                    classes = CssClass.History.PaginationBtn.value,
                ) {
                    id = if (isTrades) HtmlIds.HISTORY_TRADES_PREV else HtmlIds.HISTORY_LEDGERS_PREV
                    +ViewText.HISTORY_PREVIOUS
                }
            }
            span(CssClass.History.MutedSmallText) {
                val shown = if (page.items.isEmpty()) "0–0" else "${offset + 1}–${offset + page.items.size}"
                +"$shown${page.total?.let { " of $it" }.orEmpty()}"
            }
            if (page.hasMore) {
                a(
                    href = pageUrl(
                        data,
                        tradesOffset = if (isTrades) next else null,
                        ledgersOffset = if (isTrades) null else next,
                    ) + "#" + if (isTrades) HtmlIds.HISTORY_TRADES_SECTION else HtmlIds.HISTORY_LEDGERS_SECTION,
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
            """<circle class="${CssClass.History.Point.value}" cx="%.2f" cy="%.2f" r="4" data-time="${point.first.toEpochMilli()}" data-value="${point.second.toPlainString()}">"""
                .format(java.util.Locale.US, x, y) +
                "<title>${timeFormatter.format(
                    point.first,
                )} · ${Formatter.formatQuantity(point.second)}</title></circle>"
        }
        return """<svg class="${CssClass.History.ChartSvg.value}" viewBox="0 0 $SVG_WIDTH $SVG_HEIGHT" """ +
            """preserveAspectRatio="none"><title>Exchange-reported balance checkpoints</title>${svgGrid()}$markers""" +
            """<text class="${CssClass.History.AxisLabel.value}" x="4" y="$SVG_PAD">${minV.max(
                maxV,
            ).stripTrailingZeros().toPlainString()}</text>""" +
            """<text class="${CssClass.History.AxisLabel.value}" x="4" y="${SVG_HEIGHT - SVG_PAD}">${minV.stripTrailingZeros().toPlainString()}</text>${timeAxis(
                minT,
                maxT,
            )}</svg>"""
    }

    /** Connect adjacent complete values and leave a visible break wherever an incomplete cycle occurs. */
    private fun valuePointSvg(observations: List<HistoryObservation>): String {
        val completePoints = observations.mapNotNull { observation ->
            if (observation.status == HistoryObservationStatus.COMPLETE) {
                observation.totalUsd?.let { observation.observedAt to it }
            } else {
                null
            }
        }
        val minT = observations.minOf { it.observedAt.epochSecond }
        val maxT = observations.maxOf { it.observedAt.epochSecond }
        val spanT = (maxT - minT).coerceAtLeast(1).toDouble()
        val minV = completePoints.minOfOrNull { it.second }
        val maxV = completePoints.maxOfOrNull { it.second }
        val spanV = if (minV != null && maxV != null) {
            maxV.subtract(minV).takeIf { it.signum() != 0 } ?: BigDecimal.ONE
        } else {
            BigDecimal.ONE
        }
        fun xCoordinate(time: Instant): Double = (time.epochSecond - minT) / spanT * (SVG_WIDTH - 2 * SVG_PAD) + SVG_PAD
        fun coordinate(time: Instant, value: BigDecimal): Pair<Double, Double> {
            val x = xCoordinate(time)
            val ratio = value.subtract(minV!!).divide(spanV, 6, RoundingMode.HALF_UP).toDouble()
            val y = SVG_HEIGHT - SVG_PAD - ratio * (SVG_HEIGHT - 2 * SVG_PAD)
            return x to y
        }

        val lineSegments = mutableListOf<List<Pair<Double, Double>>>()
        var currentSegment = mutableListOf<Pair<Double, Double>>()
        observations.forEach { observation ->
            val value = observation.totalUsd.takeIf { observation.status == HistoryObservationStatus.COMPLETE }
            if (value == null) {
                if (currentSegment.size > 1) lineSegments += currentSegment
                currentSegment = mutableListOf()
            } else {
                currentSegment += coordinate(observation.observedAt, value)
            }
        }
        if (currentSegment.size > 1) lineSegments += currentSegment
        val lines = lineSegments.joinToString("") { segment ->
            val coordinates = segment.joinToString(" ") { (x, y) ->
                "%.2f,%.2f".format(java.util.Locale.US, x, y)
            }
            """<polyline class="${CssClass.History.Line.value}" points="$coordinates"/>"""
        }

        val markers = completePoints.joinToString("") { (time, value) ->
            val (x, y) = coordinate(time, value)
            """<circle class="${CssClass.History.Point.value}" cx="%.2f" cy="%.2f" r="4" data-time="${time.toEpochMilli()}" data-value="${value.toPlainString()}">"""
                .format(java.util.Locale.US, x, y) +
                "<title>${timeFormatter.format(time)} · ${Formatter.formatSignedUsd(value)}</title></circle>"
        }
        val gapMarkers = observations.filter { it.status == HistoryObservationStatus.INCOMPLETE }.joinToString("") {
            val x = xCoordinate(it.observedAt)
            """<circle class="${CssClass.History.GapMarker.value}" cx="%.2f" cy="${SVG_HEIGHT - SVG_PAD + 8}" r="4" data-time="${it.observedAt.toEpochMilli()}" data-gap="true">"""
                .format(java.util.Locale.US, x) +
                """<title>${timeFormatter.format(
                    it.observedAt,
                )} · Incomplete observation; no portfolio value is plotted</title></circle>"""
        }
        val axisLabels = if (minV != null && maxV != null) {
            """<text class="${CssClass.History.AxisLabel.value}" x="4" y="$SVG_PAD">""" +
                Formatter.formatSignedUsd(maxV) + "</text>" +
                """<text class="${CssClass.History.AxisLabel.value}" x="4" y="${SVG_HEIGHT - SVG_PAD}">""" +
                Formatter.formatSignedUsd(minV) + "</text>"
        } else {
            ""
        }
        return """<svg class="${CssClass.History.ChartSvg.value}" viewBox="0 0 $SVG_WIDTH $SVG_HEIGHT" """ +
            """preserveAspectRatio="none"><title>Portfolio samples; gaps mark incomplete cycles</title>""" +
            svgGrid() + lines + markers + gapMarkers + axisLabels + timeAxis(minT, maxT) + "</svg>"
    }

    private fun svgGrid(): String = (0..4).joinToString("") { tick ->
        val y = SVG_PAD + tick * (SVG_HEIGHT - 2 * SVG_PAD) / 4
        """<line class="${CssClass.History.GridLine.value}" x1="$SVG_PAD" x2="${SVG_WIDTH - SVG_PAD}" y1="$y" y2="$y"/>"""
    }

    private fun timeAxis(minT: Long, maxT: Long): String = listOf(minT, maxT).mapIndexed { index, time ->
        val x = if (index == 0) SVG_PAD else SVG_WIDTH - SVG_PAD
        val anchor = if (index == 0) "start" else "end"
        val label = DateTimeFormatter.ofPattern("MMM d HH:mm 'UTC'").withZone(ZoneOffset.UTC)
            .format(Instant.ofEpochSecond(time))
        """<text class="${CssClass.History.AxisLabel.value}" x="$x" y="${SVG_HEIGHT - 4}" text-anchor="$anchor">$label</text>"""
    }.joinToString("")

    private companion object {
        val RANGES = listOf("24h" to "24h", "7d" to "7d", "30d" to "30d", "90d" to "90d", "all" to "All")
        const val TRADE_COLUMNS = 7
        const val LEDGER_COLUMNS = 6
        const val SVG_WIDTH = 800
        const val SVG_HEIGHT = 240
        const val SVG_PAD = 32
    }
}

private enum class HistoryStatCardDefinition(
    val title: String,
    val iconSvg: String,
    val valueId: String,
    val formatValue: (HistoryPageData) -> String,
) {
    PERIOD_HIGH(
        ViewText.PERIOD_HIGH,
        Icons.TREND_UP,
        HtmlIds.STAT_PERIOD_HIGH,
        { data ->
            Formatter.formatUsdOrDash(
                data.observations.mapNotNull { it.totalUsd }.maxOrNull(),
                PrecisionConstants.SCALE_USD,
                PrecisionConstants.SCALE_USD,
            )
        },
    ),
    TOTAL_TRADES(
        ViewText.HISTORY_TOTAL_TRADES,
        Icons.CHART,
        HtmlIds.STAT_TOTAL_TRADES,
        { data -> Formatter.formatCount(data.tradeSummary.totalTrades) },
    ),
    TOTAL_VOLUME(
        ViewText.HISTORY_TOTAL_VOLUME,
        Icons.WALLET,
        HtmlIds.STAT_TOTAL_VOLUME,
        { data ->
            Formatter.formatUsdOrDash(
                data.tradeSummary.totalVolumeUsd,
                PrecisionConstants.SCALE_USD,
                PrecisionConstants.SCALE_USD,
            )
        },
    ),
    TOTAL_FEES(
        ViewText.HISTORY_TOTAL_FEES,
        Icons.DOLLAR_CIRCLE,
        HtmlIds.STAT_TOTAL_FEES,
        { data ->
            Formatter.formatUsdOrDash(
                data.tradeSummary.totalFeesPaid,
                PrecisionConstants.SCALE_USD,
                PrecisionConstants.SCALE_USD,
            )
        },
    ),
    AVG_FEE_RATE(
        ViewText.HISTORY_AVG_FEE_RATE,
        Icons.DOLLAR_CIRCLE,
        HtmlIds.STAT_AVG_FEE_RATE,
        { data ->
            if (data.tradeSummary.totalVolumeUsd.signum() == 0) {
                ViewText.EM_DASH
            } else {
                "${Formatter.formatPercent(data.tradeSummary.avgFeeRatePercent)}%"
            }
        },
    ),
}
