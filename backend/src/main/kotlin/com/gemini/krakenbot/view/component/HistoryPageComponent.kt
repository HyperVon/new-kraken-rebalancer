package com.gemini.krakenbot.view.component

import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.KrakenApiConstants
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
import com.gemini.krakenbot.view.util.ChartProps
import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.FormFields
import com.gemini.krakenbot.view.util.Formatter
import com.gemini.krakenbot.view.util.HistoryChartActions
import com.gemini.krakenbot.view.util.HtmlAttrs
import com.gemini.krakenbot.view.util.HtmlIds
import com.gemini.krakenbot.view.util.Icons
import com.gemini.krakenbot.view.util.Routes
import com.gemini.krakenbot.view.util.ViewText
import com.gemini.krakenbot.view.util.borderColorForSymbol
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
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

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
    val fundingLedgers: List<HistoryLedger> = emptyList(),
    val rewardLedgers: List<HistoryLedger> = emptyList(),
    val allocations: List<Allocation> = emptyList(),
)

class HistoryPageComponent(private val displayZone: ZoneId = ZoneId.systemDefault()) {
    private val timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(displayZone)
    private val axisTimeFormatter = DateTimeFormatter.ofPattern("MMM d HH:mm").withZone(displayZone)

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
                renderHoldingsChart(data)
                renderAllocationChart(data)
                renderCashFlowChart(data)
                renderRewardsChart(data)
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
                HtmlIds.HISTORY_HOLDINGS_SECTION to ViewText.HISTORY_JUMP_HOLDINGS,
                HtmlIds.HISTORY_ALLOCATION_SECTION to ViewText.HISTORY_JUMP_ALLOCATION,
                HtmlIds.HISTORY_CASH_FLOW_SECTION to ViewText.HISTORY_JUMP_CASH_FLOW,
                HtmlIds.HISTORY_REWARDS_SECTION to ViewText.HISTORY_JUMP_REWARDS,
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
                p(CssClass.History.SyncText) {
                    id = HtmlIds.HISTORY_SYNC_TEXT
                    +ViewText.HISTORY_ACCOUNT_UNAVAILABLE
                }
            } else if (data.syncStatuses.isEmpty()) {
                p(CssClass.History.SyncText) {
                    id = HtmlIds.HISTORY_SYNC_TEXT
                    +ViewText.HISTORY_SYNC_NO_STATUS
                }
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
                id = HtmlIds.HISTORY_SET_DEFAULT_VIEW
                disabled = true
                +ViewText.HISTORY_SET_DEFAULT
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
            renderChartScrubber(HtmlIds.HISTORY_VALUE_CHART)
            p(CssClass.History.ChartCaption) { +ViewText.HISTORY_VALUE_CAPTION }
            p(CssClass.History.ChartCaption) { +ViewText.HISTORY_CHART_HELP }
        }
    }

    private fun DIV.chartControls(pointsOnly: Boolean = false) {
        div(CssClass.History.ChartControls) {
            attributes[HtmlAttrs.DATA_HISTORY_CONTROLS] = "true"
            attributes["hidden"] = "hidden"
            if (!pointsOnly) {
                chartButton(HtmlAttrs.DATA_HISTORY_VIEW, HistoryChartActions.VIEW_LINE, ViewText.HISTORY_CHART_LINE)
                chartButton(HtmlAttrs.DATA_HISTORY_VIEW, HistoryChartActions.VIEW_POINTS, ViewText.HISTORY_CHART_POINTS)
            }
            chartButton(HtmlAttrs.DATA_HISTORY_ZOOM, HistoryChartActions.ZOOM_IN, ViewText.HISTORY_CHART_ZOOM_IN)
            chartButton(HtmlAttrs.DATA_HISTORY_ZOOM, HistoryChartActions.ZOOM_OUT, ViewText.HISTORY_CHART_ZOOM_OUT)
            chartButton(HtmlAttrs.DATA_HISTORY_PAN, HistoryChartActions.PAN_EARLIER, ViewText.HISTORY_CHART_EARLIER)
            chartButton(HtmlAttrs.DATA_HISTORY_PAN, HistoryChartActions.PAN_LATER, ViewText.HISTORY_CHART_LATER)
            chartButton(HtmlAttrs.DATA_HISTORY_ZOOM, HistoryChartActions.ZOOM_RESET, ViewText.HISTORY_CHART_RESET)
            chartButton(HtmlAttrs.DATA_HISTORY_EXPAND, HistoryChartActions.EXPAND, ViewText.HISTORY_CHART_EXPAND)
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
                    +"${ViewText.HISTORY_CHECKPOINT_TITLE}${asset?.let { " ($it)" }.orEmpty()}"
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

    private fun DIV.renderHoldingsChart(data: HistoryPageData) {
        renderSeriesChart(
            sectionId = HtmlIds.HISTORY_HOLDINGS_SECTION,
            chartId = HtmlIds.HISTORY_HOLDINGS_CHART,
            title = ViewText.HISTORY_ASSET_HOLDINGS,
            series = assetValueSeries(data.observations),
            caption = ViewText.HISTORY_HOLDINGS_CAPTION,
            emptyText = ViewText.HISTORY_VALUE_NO_DATA,
        )
    }

    private fun DIV.renderAllocationChart(data: HistoryPageData) {
        renderSeriesChart(
            sectionId = HtmlIds.HISTORY_ALLOCATION_SECTION,
            chartId = HtmlIds.HISTORY_ALLOCATION_CHART,
            title = ViewText.HISTORY_ALLOCATION_DRIFT,
            series = allocationDeviationSeries(
                data.observations,
                data.allocations.associate { it.symbol.value to BigDecimal.valueOf(it.targetPercent) },
            ),
            caption = ViewText.HISTORY_ALLOCATION_CAPTION,
            emptyText = ViewText.HISTORY_VALUE_NO_DATA,
            zeroLine = true,
            formatValue = { "${Formatter.formatPercent(it)}%" },
        )
    }

    private fun DIV.renderCashFlowChart(data: HistoryPageData) {
        renderSeriesChart(
            sectionId = HtmlIds.HISTORY_CASH_FLOW_SECTION,
            chartId = HtmlIds.HISTORY_CASH_FLOW_CHART,
            title = ViewText.HISTORY_NET_CASH_FLOW,
            series = listOf(Asset.USD to cumulativeFundingFlow(data.fundingLedgers, data.observations)),
            caption = ViewText.HISTORY_CASH_FLOW_CAPTION,
            emptyText = ViewText.HISTORY_NO_DATA,
        )
    }

    private fun DIV.renderRewardsChart(data: HistoryPageData) {
        val (points, total) = cumulativeRewardValue(data.rewardLedgers, data.observations)
        renderSeriesChart(
            sectionId = HtmlIds.HISTORY_REWARDS_SECTION,
            chartId = HtmlIds.HISTORY_REWARDS_CHART,
            title = ViewText.HISTORY_STAKING_REWARDS,
            series = listOf(Asset.USD to points),
            caption = ViewText.HISTORY_REWARDS_CAPTION,
            emptyText = ViewText.HISTORY_NO_DATA,
            headerStat = {
                span(CssClass.History.MutedSmallText) {
                    id = HtmlIds.HISTORY_REWARDS_TOTAL
                    +"${ViewText.HISTORY_TOTAL_REWARDS}: ${Formatter.formatSignedUsd(total)}"
                }
            },
        )
    }

    private fun DIV.renderSeriesChart(
        sectionId: String,
        chartId: String,
        title: String,
        series: List<Pair<String, List<HistorySeriesPoint>>>,
        caption: String,
        emptyText: String,
        zeroLine: Boolean = false,
        formatValue: (BigDecimal) -> String = Formatter::formatSignedUsd,
        headerStat: (DIV.() -> Unit)? = null,
    ) {
        div(CssClass.Layout.GlassPanel) {
            id = sectionId
            div(CssClass.History.ChartHeader) {
                h2(CssClass.Utility.GlassPanelTitle + CssClass.History.ChartHeaderTitle) { +title }
                headerStat?.invoke(this)
            }
            div(CssClass.History.ChartContainer) {
                id = chartId
                if (series.all { it.second.isEmpty() }) {
                    p(CssClass.History.MutedSmallText) { +emptyText }
                } else {
                    unsafe { +multiSeriesSvg(series, zeroLine, formatValue) }
                }
            }
            if (series.count { it.second.isNotEmpty() } > 1) renderSeriesLegend(series)
            renderChartScrubber(chartId)
            p(CssClass.History.ChartCaption) { +caption }
        }
    }

    private fun DIV.renderSeriesLegend(series: List<Pair<String, List<HistorySeriesPoint>>>) {
        div(CssClass.History.ChartLegend) {
            series.filter { it.second.isNotEmpty() }.forEachIndexed { index, (symbol, _) ->
                span(CssClass.History.ChartLegendItem) {
                    span(CssClass.History.ChartLegendSwatch) {
                        attributes["style"] = "background:${borderColorForSymbol(symbol, index)}"
                    }
                    +symbol
                }
            }
        }
    }

    private fun DIV.renderChartScrubber(chartId: String) {
        div(CssClass.History.ChartScrubber) {
            input(classes = CssClass.History.ChartScrubberInput.value, type = InputType.range) {
                min = "0"
                max = "1"
                step = "1"
                value = "0"
                // Starts disabled; :frontend-js enables it once the chart has scrubbable points.
                disabled = true
                attributes[HtmlAttrs.DATA_CHART_ID] = chartId
            }
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
        val plotWidth = (SVG_WIDTH - SVG_LABEL_GUTTER - SVG_PAD).toDouble()
        fun coordinate(point: Pair<Instant, BigDecimal>): Pair<Double, Double> {
            val (time, value) = point
            val x = (time.epochSecond - minT) / spanT * plotWidth + SVG_LABEL_GUTTER
            val ratio = value.subtract(minV).divide(spanV, 6, RoundingMode.HALF_UP).toDouble()
            val y = SVG_HEIGHT - SVG_PAD - ratio * (SVG_HEIGHT - 2 * SVG_PAD)
            return x to y
        }
        val markers = svgSampleMarkers(
            samples = points.map { point ->
                val (x, y) = coordinate(point)
                SvgSample(
                    x = x,
                    y = y,
                    title = "${timeFormatter.format(point.first)} · ${Formatter.formatQuantity(point.second)}",
                    timeMillis = point.first.toEpochMilli(),
                    valueAttr = point.second.toPlainString(),
                )
            },
            visibleEvery = maxOf(1, (points.size + 15) / 16),
            visibleRadius = 3,
            color = null,
        )
        return """<svg class="${CssClass.History.ChartSvg.value}" viewBox="0 0 $SVG_WIDTH $SVG_HEIGHT" """ +
            """preserveAspectRatio="none"><title>Exchange-reported balance checkpoints</title>${svgGrid()}$markers""" +
            valueAxisLabels(
                minV.max(maxV).stripTrailingZeros().toPlainString(),
                minV.stripTrailingZeros().toPlainString(),
            ) + timeAxis(minT, maxT) + "</svg>"
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
        val plotWidth = (SVG_WIDTH - SVG_LABEL_GUTTER - SVG_PAD).toDouble()
        fun xCoordinate(time: Instant): Double = (time.epochSecond - minT) / spanT * plotWidth + SVG_LABEL_GUTTER
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
                "%.2f,%.2f".format(Locale.US, x, y)
            }
            """<polyline class="${CssClass.History.Line.value}" points="$coordinates"/>"""
        }

        val markers = svgSampleMarkers(
            samples = completePoints.map { (time, value) ->
                val (x, y) = coordinate(time, value)
                SvgSample(
                    x = x,
                    y = y,
                    title = "${timeFormatter.format(time)} · ${Formatter.formatSignedUsd(value)}",
                    timeMillis = time.toEpochMilli(),
                    valueAttr = value.toPlainString(),
                )
            },
            visibleEvery = maxOf(1, (completePoints.size + 15) / 16),
            visibleRadius = 3,
            color = null,
        )
        val gapMarkers = observations.filter { it.status == HistoryObservationStatus.INCOMPLETE }.joinToString("") {
            val x = xCoordinate(it.observedAt)
            (
                """<circle class="${CssClass.History.GapMarker.value}" cx="%.2f" """ +
                    """cy="${SVG_HEIGHT - SVG_PAD + 8}" r="4" """ +
                    "${HtmlAttrs.DATA_TIME}=\"${it.observedAt.toEpochMilli()}\" " +
                    "${HtmlAttrs.DATA_GAP}=\"true\">"
                ).format(Locale.US, x) +
                """<title>${timeFormatter.format(
                    it.observedAt,
                )} · Incomplete observation; no portfolio value is plotted</title></circle>"""
        }
        val axisLabels = if (minV != null && maxV != null) {
            valueAxisLabels(Formatter.formatSignedUsd(maxV), Formatter.formatSignedUsd(minV))
        } else {
            ""
        }
        return """<svg class="${CssClass.History.ChartSvg.value}" viewBox="0 0 $SVG_WIDTH $SVG_HEIGHT" """ +
            """preserveAspectRatio="none"><title>Portfolio samples; gaps mark incomplete cycles</title>""" +
            svgGrid() + lines + markers + gapMarkers + axisLabels + timeAxis(minT, maxT) + "</svg>"
    }

    /** Time-proportional series lines with per-point markers; each series keeps its own palette color. */
    private fun multiSeriesSvg(
        series: List<Pair<String, List<HistorySeriesPoint>>>,
        zeroLine: Boolean,
        formatValue: (BigDecimal) -> String = Formatter::formatSignedUsd,
    ): String {
        val allPoints = series.flatMap { it.second }
        val minT = allPoints.minOf { it.time.epochSecond }
        val maxT = allPoints.maxOf { it.time.epochSecond }
        val minV = allPoints.minOf { it.value }
        val maxV = allPoints.maxOf { it.value }
        val spanT = (maxT - minT).coerceAtLeast(1).toDouble()
        val spanV = maxV.subtract(minV).takeIf { it.signum() != 0 } ?: BigDecimal.ONE
        val plotWidth = (SVG_WIDTH - SVG_LABEL_GUTTER - SVG_PAD).toDouble()
        fun xCoordinate(time: Instant): Double = (time.epochSecond - minT) / spanT * plotWidth + SVG_LABEL_GUTTER
        fun yCoordinate(value: BigDecimal): Double {
            val ratio = value.subtract(minV).divide(spanV, 6, RoundingMode.HALF_UP).toDouble()
            return SVG_HEIGHT - SVG_PAD - ratio * (SVG_HEIGHT - 2 * SVG_PAD)
        }
        val bodies = series.mapIndexed { index, (symbol, points) ->
            val color = borderColorForSymbol(symbol, index)
            val line = if (points.size > 1) {
                val coordinates = points.joinToString(" ") { point ->
                    "%.2f,%.2f".format(Locale.US, xCoordinate(point.time), yCoordinate(point.value))
                }
                """<polyline class="${CssClass.History.Line.value}" points="$coordinates" style="stroke:$color"/>"""
            } else {
                ""
            }
            val samples = points.map { point ->
                SvgSample(
                    x = xCoordinate(point.time),
                    y = yCoordinate(point.value),
                    title = "${timeFormatter.format(point.time)} · $symbol · ${formatValue(point.value)}",
                    timeMillis = point.time.toEpochMilli(),
                    valueAttr = point.value.toPlainString(),
                    series = symbol,
                )
            }
            line +
                svgSampleMarkers(
                    samples,
                    visibleEvery = maxOf(1, (points.size + 7) / 8),
                    visibleRadius = 2,
                    color = color,
                )
        }.joinToString("")
        val zero = if (zeroLine && minV.signum() <= 0 && maxV.signum() >= 0) {
            val y = "%.2f".format(Locale.US, yCoordinate(BigDecimal.ZERO))
            """<line x1="$SVG_LABEL_GUTTER" x2="$SVG_PLOT_RIGHT" y1="$y" y2="$y" """ +
                """stroke="${ChartProps.COLOR_TICK}" stroke-width="2" stroke-dasharray="6 5">""" +
                "<title>${ViewText.HISTORY_CHART_ZERO_LINE}</title></line>"
        } else {
            ""
        }
        return """<svg class="${CssClass.History.ChartSvg.value}" viewBox="0 0 $SVG_WIDTH $SVG_HEIGHT" """ +
            """preserveAspectRatio="none">${svgGrid()}$zero$bodies""" +
            valueAxisLabels(formatValue(maxV), formatValue(minV)) + timeAxis(minT, maxT) + "</svg>"
    }

    private fun svgGrid(): String = (0..4).joinToString("") { tick ->
        val y = SVG_PAD + tick * (SVG_HEIGHT - 2 * SVG_PAD) / 4
        """<line class="${CssClass.History.GridLine.value}" x1="$SVG_LABEL_GUTTER" x2="$SVG_PLOT_RIGHT" y1="$y" y2="$y"/>"""
    }

    private fun timeAxis(minT: Long, maxT: Long): String = listOf(minT, maxT).mapIndexed { index, time ->
        val x = if (index == 0) SVG_LABEL_GUTTER else SVG_PLOT_RIGHT
        val anchor = if (index == 0) "start" else "end"
        val label = axisTimeFormatter.format(Instant.ofEpochSecond(time))
        """<text class="${CssClass.History.AxisLabel.value}" x="$x" y="${SVG_HEIGHT - 4}" text-anchor="$anchor">$label</text>"""
    }.joinToString("")

    /** Extreme-value labels sit in the left gutter, clear of the plot band. */
    private fun valueAxisLabels(maxLabel: String, minLabel: String): String {
        val x = SVG_LABEL_GUTTER - 10
        val max = """<text class="${CssClass.History.AxisLabel.value}" x="$x" y="${SVG_PAD + 4}" text-anchor="end">""" +
            "$maxLabel</text>"
        val min = """<text class="${CssClass.History.AxisLabel.value}" x="$x" y="${SVG_HEIGHT - SVG_PAD + 4}" """ +
            """text-anchor="end">$minLabel</text>"""
        return max + min
    }

    /**
     * Dense samples render as thin connective lines: a transparent hit circle at every sample keeps
     * exact-point hover and the scrubber cursor, while visible markers appear only every [visibleEvery]
     * samples (plus the final one) so the SVG fallback stays readable. Every circle keeps its data
     * attributes so the Chart.js enhancement reads the complete series.
     */
    private fun svgSampleMarkers(
        samples: List<SvgSample>,
        visibleEvery: Int,
        visibleRadius: Int,
        color: String?,
    ): String = samples.mapIndexed { index, sample ->
        val seriesAttr = sample.series?.let { " ${HtmlAttrs.DATA_SERIES}=\"$it\"" } ?: ""
        val hit =
            (
                """<circle cx="%.2f" cy="%.2f" r="7" fill="transparent" """ +
                    "${HtmlAttrs.DATA_TIME}=\"${sample.timeMillis}\" " +
                    "${HtmlAttrs.DATA_VALUE}=\"${sample.valueAttr}\"$seriesAttr>"
                ).format(Locale.US, sample.x, sample.y) + "<title>${sample.title}</title></circle>"
        val visible = if (index % visibleEvery == 0 || index == samples.lastIndex) {
            val fill = color?.let { """ style="fill:$it"""" } ?: ""
            (
                """<circle class="${CssClass.History.Point.value}" cx="%.2f" cy="%.2f" r="$visibleRadius"$fill """ +
                    "${HtmlAttrs.DATA_TIME}=\"${sample.timeMillis}\" " +
                    "${HtmlAttrs.DATA_VALUE}=\"${sample.valueAttr}\"$seriesAttr>"
                ).format(Locale.US, sample.x, sample.y) + "<title>${sample.title}</title></circle>"
        } else {
            ""
        }
        hit + visible
    }.joinToString("")

    private class SvgSample(
        val x: Double,
        val y: Double,
        val title: String,
        val timeMillis: Long,
        val valueAttr: String,
        val series: String? = null,
    )

    private companion object {
        val RANGES = listOf("24h" to "24h", "7d" to "7d", "30d" to "30d", "90d" to "90d", "all" to "All")
        const val TRADE_COLUMNS = 7
        const val LEDGER_COLUMNS = 6
        const val SVG_WIDTH = 960
        const val SVG_HEIGHT = 240
        const val SVG_PAD = 32
        const val SVG_LABEL_GUTTER = 120
        const val SVG_PLOT_RIGHT = SVG_WIDTH - SVG_PAD
    }
}

private enum class HistoryStatCardDefinition(
    val title: String,
    val iconSvg: String,
    val valueId: String,
    val formatValue: (HistoryPageData) -> String,
) {
    ALL_TIME_HIGH(
        ViewText.HISTORY_ALL_TIME_HIGH,
        Icons.WALLET,
        HtmlIds.STAT_ALL_TIME_HIGH,
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

internal data class HistorySeriesPoint(val time: Instant, val value: BigDecimal)

internal fun assetValueSeries(observations: List<HistoryObservation>): List<Pair<String, List<HistorySeriesPoint>>> =
    observations
        .filter { it.status == HistoryObservationStatus.COMPLETE }
        .sortedBy { it.observedAt }
        .flatMap { observation ->
            observation.assets.mapNotNull { asset ->
                asset.valueUsd?.let { value -> asset.symbol to HistorySeriesPoint(observation.observedAt, value) }
            }
        }
        .groupBy({ it.first }, { it.second })
        .toSortedMap()
        .map { (symbol, points) -> symbol to points }

internal fun allocationDeviationSeries(
    observations: List<HistoryObservation>,
    targets: Map<String, BigDecimal>,
): List<Pair<String, List<HistorySeriesPoint>>> {
    val ordered = observations.sortedBy { it.observedAt }
    val symbols = (targets.keys + ordered.flatMap { observation -> observation.assets.map { it.symbol } }).toSortedSet()
    return symbols.map { symbol ->
        val target = targets[symbol] ?: BigDecimal.ZERO
        symbol to ordered.mapNotNull { observation ->
            if (observation.status != HistoryObservationStatus.COMPLETE) return@mapNotNull null
            val total = observation.totalUsd ?: return@mapNotNull null
            if (total.signum() <= 0) return@mapNotNull null
            val value = observation.assets.firstOrNull { it.symbol == symbol }?.valueUsd ?: BigDecimal.ZERO
            val actualShare = value
                .multiply(BigDecimal(PrecisionConstants.HUNDRED_INT))
                .divide(total, PrecisionConstants.SCALE_PERCENT, RoundingMode.HALF_UP)
            HistorySeriesPoint(observation.observedAt, actualShare.subtract(target))
        }
    }
}

internal fun cumulativeFundingFlow(
    ledgers: List<HistoryLedger>,
    observations: List<HistoryObservation>,
): List<HistorySeriesPoint> {
    val prices = observedUsdPrices(observations)
    return cumulativeLedgerValue(ledgers) { ledger, time ->
        val type = ledger.type?.lowercase()
        if (type != KrakenApiConstants.LEDGER_TYPE_DEPOSIT && type != KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL) {
            return@cumulativeLedgerValue null
        }
        val amount = ledger.amount?.toBigDecimalOrNull() ?: return@cumulativeLedgerValue null
        observedUsdValue(prices, ledger.asset, time, amount)
    }
}

internal fun cumulativeRewardValue(
    ledgers: List<HistoryLedger>,
    observations: List<HistoryObservation>,
): Pair<List<HistorySeriesPoint>, BigDecimal> {
    val prices = observedUsdPrices(observations)
    val points = cumulativeLedgerValue(ledgers) { ledger, time ->
        val amount = ledger.amount?.toBigDecimalOrNull() ?: return@cumulativeLedgerValue null
        observedUsdValue(prices, ledger.asset, time, amount)
    }
    return points to (points.lastOrNull()?.value ?: BigDecimal.ZERO)
}

private fun cumulativeLedgerValue(
    ledgers: List<HistoryLedger>,
    signedUsdValue: (HistoryLedger, Instant) -> BigDecimal?,
): List<HistorySeriesPoint> {
    var running = BigDecimal.ZERO
    return ledgers
        .sortedWith(compareBy({ it.time ?: Instant.EPOCH }, { it.ledgerId }))
        .mapNotNull { ledger ->
            val time = ledger.time ?: return@mapNotNull null
            val value = signedUsdValue(ledger, time) ?: return@mapNotNull null
            running = running.add(value)
            HistorySeriesPoint(time, running)
        }
}

private fun observedUsdPrices(observations: List<HistoryObservation>): Map<String, List<Pair<Instant, BigDecimal>>> =
    observations
        .flatMap { observation ->
            observation.assets.mapNotNull { asset ->
                asset.priceUsd?.let { price -> asset.symbol to (observation.observedAt to price) }
            }
        }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, samples) -> samples.sortedBy { it.first } }

/** USD ledger legs count at par; other assets need the nearest observed cycle price. */
private fun observedUsdValue(
    prices: Map<String, List<Pair<Instant, BigDecimal>>>,
    asset: String?,
    time: Instant,
    amount: BigDecimal,
): BigDecimal? {
    val symbol = asset?.let(Asset::normalizeLedgerAsset).orEmpty()
    if (symbol.isEmpty()) return null
    if (symbol == Asset.USD) return amount
    val price = prices[symbol]
        ?.minByOrNull { sample -> abs(sample.first.epochSecond - time.epochSecond) }
        ?.second
        ?: return null
    return amount.multiply(price)
}
