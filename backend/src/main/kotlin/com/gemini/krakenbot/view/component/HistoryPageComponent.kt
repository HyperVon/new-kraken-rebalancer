package com.gemini.krakenbot.view.component

import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.OrderSide
import com.gemini.krakenbot.service.history.HistoryApiBoundaryKind
import com.gemini.krakenbot.service.history.HistoryApiChartsResponse
import com.gemini.krakenbot.service.history.HistoryApiScopeSelection
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
import com.gemini.krakenbot.service.reporting.HistoryPeriodStatistics
import com.gemini.krakenbot.service.reporting.NativeQuantitySeries
import com.gemini.krakenbot.service.reporting.allocationDeviationPercent
import com.gemini.krakenbot.util.PrecisionConstants
import com.gemini.krakenbot.view.util.ActiveNav
import com.gemini.krakenbot.view.util.CdnIntegrity
import com.gemini.krakenbot.view.util.CdnUrls
import com.gemini.krakenbot.view.util.ChartGestureScripts
import com.gemini.krakenbot.view.util.ChartProps
import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.FormFields
import com.gemini.krakenbot.view.util.Formatter
import com.gemini.krakenbot.view.util.HistoryAnalysis
import com.gemini.krakenbot.view.util.HistoryChartActions
import com.gemini.krakenbot.view.util.HistoryRangeCss
import com.gemini.krakenbot.view.util.HistoryRangeIds
import com.gemini.krakenbot.view.util.HistoryRangeProps
import com.gemini.krakenbot.view.util.HistoryRangeQuery
import com.gemini.krakenbot.view.util.HistoryRangeText
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
import kotlinx.html.DL
import kotlinx.html.FormMethod
import kotlinx.html.HTML
import kotlinx.html.InputType
import kotlinx.html.NAV
import kotlinx.html.TBODY
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
    val statistics: HistoryPeriodStatistics? = null,
    val fundingSeries: List<NativeQuantitySeries> = emptyList(),
    val rewardSeries: List<NativeQuantitySeries> = emptyList(),
    val allocations: List<Allocation> = emptyList(),
    val portfolioChart: HistoryApiChartsResponse? = null,
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
            // Vendored chart stack (Chart.js + Hammer + zoom plugin) keeps interaction CDN-independent.
            script(src = ChartGestureScripts.CHART_JS) {}
            script(src = ChartGestureScripts.HAMMER_JS) {}
            script(src = ChartGestureScripts.CHART_JS_ZOOM) {}
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
                div(CssClass.History.WorkspaceBar) {
                    renderPageTabs()
                    renderToolbar(data)
                }
                div {
                    id = HistoryRangeIds.PANEL_PORTFOLIO
                    attributes[HtmlAttrs.DATA_HISTORY_PANEL] = HistoryRangeQuery.TAB_PORTFOLIO
                    renderValueChart(data)
                    details(CssClass.History.Disclosure.value) {
                        summary { +ViewText.HISTORY_EXPLORE_ANALYSIS }
                        div {
                            renderAnalysisSelector()
                            renderHoldingsChart(data)
                            renderAllocationChart(data)
                            renderCashFlowChart(data)
                            renderRewardsChart(data)
                            renderCheckpointChart(data)
                        }
                    }
                }
                div {
                    id = HistoryRangeIds.PANEL_ACTIVITY
                    attributes[HtmlAttrs.DATA_HISTORY_PANEL] = HistoryRangeQuery.TAB_ACTIVITY
                    attributes["hidden"] = "hidden"
                    renderActivityNav()
                    renderFilters(data)
                    renderNewRecordsNotice()
                    renderBotActivity()
                    renderTrades(data)
                    renderLedgers(data)
                }
                div {
                    id = HistoryRangeIds.PANEL_DATA_STATUS
                    attributes[HtmlAttrs.DATA_HISTORY_PANEL] = HistoryRangeQuery.TAB_DATA_STATUS
                    attributes["hidden"] = "hidden"
                    renderSync(data, csrfToken)
                    renderRefreshStatus()
                    renderCoverage()
                    renderRecordedSources(data)
                    renderSavedViews()
                    renderRepairForm(csrfToken)
                }
            }
            script(src = rebalancerJsSrc()) {}
        }
    }

    private fun DIV.renderAnalysisSelector() {
        label {
            htmlFor = HistoryRangeIds.ANALYSIS_NAV
            +ViewText.HISTORY_ANALYSIS
        }
        select(classes = CssClass.History.FilterInput.value + " " + CssClass.History.AnalysisSelect.value) {
            id = HistoryRangeIds.ANALYSIS_NAV
            attributes[HtmlAttrs.DATA_HISTORY_ANALYSIS] = "true"
            listOf(
                HistoryAnalysis.HOLDINGS to ViewText.HISTORY_ASSET_HOLDINGS,
                HistoryAnalysis.ALLOCATION to ViewText.HISTORY_ALLOCATION_DRIFT,
                HistoryAnalysis.CASH_FLOW to ViewText.HISTORY_NET_CASH_FLOW,
                HistoryAnalysis.REWARDS to ViewText.HISTORY_STAKING_REWARDS,
                HistoryAnalysis.CHECKPOINTS to ViewText.HISTORY_CHECKPOINT_TITLE,
            ).forEach { (analysis, label) ->
                option {
                    value = analysis
                    selected = analysis == HistoryAnalysis.HOLDINGS
                    +label
                }
            }
        }
    }

    private fun DIV.renderPageTabs() {
        nav(classes = CssClass.History.SectionNav.value) {
            id = HistoryRangeIds.TAB_NAV
            pageTab(
                id = HistoryRangeIds.TAB_PORTFOLIO,
                tab = HistoryRangeQuery.TAB_PORTFOLIO,
                label = HistoryRangeText.TAB_PORTFOLIO,
                active = true,
            )
            pageTab(
                id = HistoryRangeIds.TAB_ACTIVITY,
                tab = HistoryRangeQuery.TAB_ACTIVITY,
                label = HistoryRangeText.TAB_ACTIVITY,
            )
            pageTab(
                id = HistoryRangeIds.TAB_DATA_STATUS,
                tab = HistoryRangeQuery.TAB_DATA_STATUS,
                label = HistoryRangeText.TAB_DATA_STATUS,
            )
        }
    }

    private fun NAV.pageTab(id: String, tab: String, label: String, active: Boolean = false) {
        a(
            href = Routes.HISTORY.withQuery(HistoryRangeQuery.PARAM_TAB, tab),
            classes = if (active) {
                CssClass.History.TimeRangeBtnActive.value
            } else {
                CssClass.History.TimeRangeBtn.value
            },
        ) {
            this.id = id
            attributes[HtmlAttrs.DATA_HISTORY_TAB] = tab
            +label
        }
    }

    private fun DIV.renderActivityNav() {
        nav(classes = CssClass.History.SectionNav.value) {
            id = HistoryRangeIds.ACTIVITY_NAV
            activityTab(
                HistoryRangeIds.ACTIVITY_BOT,
                HistoryRangeQuery.ACTIVITY_BOT,
                HistoryRangeText.ACTIVITY_BOT,
                active = true,
            )
            activityTab(
                HistoryRangeIds.ACTIVITY_TRADES,
                HistoryRangeQuery.ACTIVITY_TRADES,
                HistoryRangeText.ACTIVITY_TRADES,
            )
            activityTab(
                HistoryRangeIds.ACTIVITY_TRANSACTIONS,
                HistoryRangeQuery.ACTIVITY_TRANSACTIONS,
                HistoryRangeText.ACTIVITY_TRANSACTIONS,
            )
        }
    }

    private fun NAV.activityTab(id: String, activity: String, label: String, active: Boolean = false) {
        a(
            href = Routes.HISTORY
                .withQuery(HistoryRangeQuery.PARAM_TAB, HistoryRangeQuery.TAB_ACTIVITY)
                .withQuery(HistoryRangeQuery.PARAM_ACTIVITY, activity),
            classes = if (active) {
                CssClass.History.TimeRangeBtnActive.value
            } else {
                CssClass.History.TimeRangeBtn.value
            },
        ) {
            this.id = id
            attributes[HtmlAttrs.DATA_HISTORY_TAB] = HistoryRangeQuery.TAB_ACTIVITY
            attributes[HtmlAttrs.DATA_HISTORY_ACTIVITY_TAB] = activity
            +label
        }
    }

    private fun DIV.renderCoverage() {
        div(CssClass.History.SyncBanner) {
            id = HistoryRangeIds.COVERAGE
            dl {
                dt { +HistoryRangeText.SELECTED_PERIOD }
                dd {
                    id = HistoryRangeIds.COVERAGE_SELECTED
                    +"—"
                }
                dt { +HistoryRangeText.VALUES_RECORDED }
                dd {
                    id = HistoryRangeIds.COVERAGE_VALUES
                    +"—"
                }
                dt { +HistoryRangeText.TRADES_COVERAGE }
                dd {
                    id = HistoryRangeIds.COVERAGE_TRADES
                    +"—"
                }
                dt { +HistoryRangeText.LEDGERS_COVERAGE }
                dd {
                    id = HistoryRangeIds.COVERAGE_LEDGERS
                    +"—"
                }
                dt { +HistoryRangeText.VALUE_SAMPLES }
                dd {
                    id = HistoryRangeIds.COVERAGE_COUNT
                    +"—"
                }
            }
            p(CssClass.History.SyncText) {
                id = HistoryRangeIds.COVERAGE_SPARSE
                attributes["hidden"] = "hidden"
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
            p(CssClass.History.SyncText) {
                id = HistoryRangeIds.SYNC_ERROR
                attributes["hidden"] = "hidden"
            }
        }
    }

    private fun DIV.renderRepairForm(csrfToken: String?) {
        div(CssClass.Layout.GlassPanel + CssClass.History.SyncBanner) {
            h2(CssClass.History.SyncTitle) { +HistoryRangeText.REPAIR_TITLE }
            form(
                action = Routes.API_HISTORY_REPAIR,
                method = FormMethod.post,
                classes = CssClass.History.FilterRow.value,
            ) {
                id = HistoryRangeIds.REPAIR_FORM
                input(type = InputType.hidden, name = FormFields.CSRF_TOKEN) { value = csrfToken.orEmpty() }
                input(type = InputType.hidden, name = HistoryRangeQuery.PARAM_TZ) {
                    id = HistoryRangeIds.REPAIR_TIMEZONE
                }
                label {
                    htmlFor = HistoryRangeIds.REPAIR_START
                    +HistoryRangeText.REPAIR_START
                    input(type = InputType.date, name = HistoryRangeQuery.PARAM_START) {
                        id = HistoryRangeIds.REPAIR_START
                    }
                }
                label {
                    htmlFor = HistoryRangeIds.REPAIR_END
                    +HistoryRangeText.REPAIR_END
                    input(type = InputType.date, name = HistoryRangeQuery.PARAM_END) {
                        id = HistoryRangeIds.REPAIR_END
                    }
                }
                span(CssClass.History.MutedSmallText) { id = HistoryRangeIds.REPAIR_TIMEZONE_LABEL }
                button(type = ButtonType.submit, classes = CssClass.History.PaginationBtn.value) {
                    +HistoryRangeText.REPAIR_SUBMIT
                }
            }
            p(CssClass.History.SyncText) {
                id = HistoryRangeIds.REPAIR_ERROR
                attributes["hidden"] = "hidden"
            }
        }
    }

    private fun DIV.renderBotActivity() {
        div(CssClass.Layout.GlassPanel + CssClass.History.TradeLog) {
            id = HistoryRangeIds.ACTIVITY_BOT_PANEL
            attributes[HtmlAttrs.DATA_HISTORY_ACTIVITY_PANEL] = HistoryRangeQuery.ACTIVITY_BOT
            div(CssClass.History.TradeLogHeader) {
                h2(CssClass.Utility.GlassPanelTitle + CssClass.History.TitleNoMargin) {
                    +HistoryRangeText.ACTIVITY_BOT
                }
                exportButton("activity", Routes.API_HISTORY_EXPORT_ACTIVITY)
            }
            p(CssClass.History.MutedSmallText) { +HistoryRangeText.ACTIVITY_FILTERS_UNSUPPORTED }
            div(CssClass.Table.Wrapper) {
                table {
                    thead {
                        tr {
                            listOf(
                                ViewText.HEADER_TIME,
                                ViewText.HEADER_STATUS,
                                HistoryRangeText.MODE,
                                HistoryRangeText.DETAILS_CYCLE_ID,
                                HistoryRangeText.VIEW_ACTIONS,
                            ).forEach { th { +it } }
                        }
                    }
                    tbody {
                        id = HistoryRangeIds.ACTIVITY_BODY
                        emptyActivityRow()
                    }
                }
            }
            div(CssClass.History.Pagination) {
                id = HistoryRangeIds.ACTIVITY_PAGINATION
                a(
                    href = Routes.HISTORY,
                    classes = CssClass.History.PaginationBtn.value,
                ) {
                    id = HistoryRangeIds.ACTIVITY_PREVIOUS
                    attributes[HtmlAttrs.DATA_HISTORY_ACTIVITY_PAGE] = "previous"
                    attributes["hidden"] = "hidden"
                    +HistoryRangeText.ACTIVITY_PREVIOUS
                }
                span(CssClass.History.MutedSmallText) {
                    id = HistoryRangeIds.ACTIVITY_RANGE
                    +"0–0"
                }
                a(
                    href = Routes.HISTORY,
                    classes = CssClass.History.PaginationBtn.value,
                ) {
                    id = HistoryRangeIds.ACTIVITY_NEXT
                    attributes[HtmlAttrs.DATA_HISTORY_ACTIVITY_PAGE] = "next"
                    attributes["hidden"] = "hidden"
                    +HistoryRangeText.ACTIVITY_NEXT
                }
            }
        }
    }

    private fun DIV.exportButton(kind: String, route: String) {
        a(href = route, classes = CssClass.History.PaginationBtn.value) {
            attributes[HtmlAttrs.DATA_HISTORY_EXPORT] = kind
            +HistoryRangeText.EXPORT_CSV
        }
    }

    private fun DIV.renderRefreshStatus() {
        div(CssClass.Layout.GlassPanel + CssClass.History.SyncBanner) {
            id = HistoryRangeIds.REFRESH_STATUS
            div(CssClass.History.SyncHeader) {
                h2(CssClass.History.SyncTitle) { +HistoryRangeText.FRESHNESS }
                span {
                    attributes["class"] = HistoryRangeCss.CHIP + " " + HistoryRangeCss.CHIP_BUSY
                    id = HistoryRangeIds.REFRESH_CHIP
                    +HistoryRangeText.STATE_UPDATING
                }
            }
            div {
                attributes["class"] = HistoryRangeCss.COVERAGE_ROW
                span {
                    id = HistoryRangeIds.REFRESH_UPDATED
                    +"${HistoryRangeText.UPDATED} -"
                }
                span {
                    id = HistoryRangeIds.REFRESH_NEXT
                    +"${HistoryRangeText.NEXT_REFRESH} -"
                }
            }
            p(CssClass.History.SyncText) {
                id = HistoryRangeIds.REFRESH_ERROR
                attributes["hidden"] = "hidden"
            }
            details {
                id = HistoryRangeIds.REFRESH_DETAILS
                summary { +HistoryRangeText.DETAILS_SUMMARY }
                div {
                    id = HistoryRangeIds.REFRESH_FEED_PROGRESS
                    listOf(
                        "TRADES" to HistoryRangeText.FEED_TRADES,
                        "LEDGERS" to HistoryRangeText.FEED_LEDGERS,
                    ).forEach { (feed, label) ->
                        div {
                            attributes["class"] = HistoryRangeCss.COVERAGE_ROW
                            attributes["data-history-feed"] = feed
                            span(CssClass.History.SyncTitle) { +label }
                            dl {
                                dt { +HistoryRangeText.PROGRESS_RECENT }
                                dd { attributes["data-history-refresh-recent"] = "true" }
                                dt { +HistoryRangeText.PROGRESS_BACKFILL }
                                dd { attributes["data-history-refresh-backfill"] = "true" }
                                dt { +HistoryRangeText.FAILURE }
                                dd { attributes["data-history-refresh-failure"] = "true" }
                                dt { +HistoryRangeText.NEXT_RETRY }
                                dd { attributes["data-history-refresh-retry"] = "true" }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun DIV.renderNewRecordsNotice() {
        div {
            attributes["class"] = HistoryRangeCss.BANNER
            id = HistoryRangeIds.NEW_RECORDS_NOTICE
            attributes["hidden"] = "hidden"
            p { +HistoryRangeText.NEW_RECORDS }
            button(type = ButtonType.button, classes = CssClass.History.PaginationBtn.value) {
                id = HistoryRangeIds.NEW_RECORDS_BUTTON
                +HistoryRangeText.NEW_RECORDS_RETURN
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
                    ) {
                        attributes["data-history-range"] = key
                        +label
                    }
                }
                a(
                    href = "#history-range-custom-panel",
                    classes = CssClass.History.TimeRangeBtn.value,
                ) {
                    attributes["data-history-range"] = "custom"
                    +"Custom"
                }
            }
            div(CssClass.History.FilterRow) {
                id = "history-range-custom-panel"
                attributes["hidden"] = "hidden"
                label {
                    htmlFor = "history-range-custom-start"
                    +"Start date"
                    input(type = InputType.date) { id = "history-range-custom-start" }
                }
                label {
                    htmlFor = "history-range-custom-end"
                    +"End date"
                    input(type = InputType.date) { id = "history-range-custom-end" }
                }
                span(CssClass.History.MutedSmallText) {
                    id = "history-range-custom-tz"
                }
                button(type = ButtonType.button, classes = CssClass.History.PaginationBtn.value) {
                    id = "history-range-custom-apply"
                    +"Apply dates"
                }
                p(CssClass.History.SyncText) {
                    id = "history-range-custom-error"
                    attributes["hidden"] = "hidden"
                }
            }
        }
    }

    /** Account record filters belong to Activity, not to the recorded portfolio value chart. */
    private fun DIV.renderFilters(data: HistoryPageData) {
        form(action = Routes.HISTORY, method = FormMethod.get, classes = CssClass.History.FilterRow.value) {
            input(type = InputType.hidden, name = "range") {
                id = "history-filter-range"
                value = data.range
            }
            input(type = InputType.hidden, name = "scope") { id = "history-filter-scope" }
            input(type = InputType.hidden, name = "start") { id = "history-filter-start" }
            input(type = InputType.hidden, name = "end") { id = "history-filter-end" }
            input(type = InputType.hidden, name = "tz") { id = "history-filter-tz" }
            input(type = InputType.hidden, name = HistoryRangeQuery.PARAM_TAB) {
                id = HistoryRangeIds.FILTER_TAB
                value = HistoryRangeQuery.TAB_ACTIVITY
            }
            input(type = InputType.hidden, name = HistoryRangeQuery.PARAM_ACTIVITY) {
                id = HistoryRangeIds.FILTER_ACTIVITY
                value = HistoryRangeQuery.ACTIVITY_BOT
            }
            filterSelect(
                HtmlIds.HISTORY_FILTER_ASSET,
                "asset",
                ViewText.HISTORY_FILTER_ASSET,
                ViewText.HISTORY_FILTER_ALL_ASSETS,
                data.filters.assetKey,
                data.filterOptions.assets,
            ) { asset -> Asset.normalizeLedgerAsset(asset) }
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
            p(CssClass.History.MutedSmallText) {
                +ViewText.HISTORY_FILTER_SCOPE
            }
        }
    }

    /**
     * Scope provenance lives in Data details: the recorded portfolios, their asset sets and the
     * periods each one covers, so asset-set changes and gaps stay visible without a prominent
     * selector in the default view.
     */
    private fun DIV.renderRecordedSources(data: HistoryPageData) {
        details {
            id = HistoryRangeIds.SCOPE_DETAILS
            attributes["class"] = CssClass.History.SyncBanner.value
            summary { +ViewText.HISTORY_RECORDED_SOURCES }
            div(CssClass.History.FilterRow) {
                label {
                    htmlFor = "history-scope-select"
                    +"Recorded portfolio"
                    select(classes = CssClass.History.FilterInput.value) {
                        id = "history-scope-select"
                        option {
                            value = ""
                            +"Loading recorded portfolios"
                        }
                    }
                }
                a(
                    href = "#history-scope-select",
                    classes = CssClass.History.PaginationBtn.value,
                ) {
                    id = "history-scope-link"
                    attributes["hidden"] = "hidden"
                    +"Earlier recorded portfolios available"
                }
            }
            div(CssClass.History.MutedSmallText) {
                id = HistoryRangeIds.SCOPE_LIST
                data.portfolioChart?.series?.mapNotNull { it.provenance }?.forEach { provenance ->
                    p {
                        +provenance.scope.symbols.joinToString(" / ")
                        +" · ${provenance.scope.earliestIso} – ${provenance.scope.latestIso}"
                    }
                    dl {
                        dt { +ViewText.HISTORY_RECORDED_SOURCES }
                        dd {
                            +listOf(
                                provenance.scope.id,
                                provenance.walletScopes.joinToString(", "),
                                provenance.balanceSources.joinToString(", "),
                                provenance.priceSources.joinToString(", "),
                                provenance.valuationCurrency,
                            ).joinToString(" · ")
                        }
                    }
                }
            }
        }
    }

    /** Saved-view management is collapsed behind a single disclosure control. */
    private fun DIV.renderSavedViews() {
        details {
            id = HtmlIds.HISTORY_SAVED_VIEWS
            attributes["class"] = CssClass.History.FilterRow.value
            attributes["hidden"] = "hidden"
            summary { +ViewText.HISTORY_SAVED_VIEWS }
            div(CssClass.History.FilterRow) {
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
        val statistics = data.statistics
        div(CssClass.History.StatsGrid) {
            statusCard(
                ViewText.HISTORY_LATEST_OBSERVED_VALUE,
                Icons.WALLET,
                statistics?.latestObservedValue?.let { reportUsd(it.valueUsd, it.complete) }
                    ?: ViewText.HISTORY_REPORT_UNAVAILABLE,
                HtmlIds.HISTORY_STAT_LATEST_VALUE,
            )
            statusCard(
                ViewText.HISTORY_OBSERVED_VALUE_CHANGE,
                Icons.CHART,
                statistics?.observedValueChange?.let { reportUsd(it.changeUsd, it.complete) }
                    ?: ViewText.HISTORY_REPORT_UNAVAILABLE,
                HtmlIds.HISTORY_STAT_VALUE_CHANGE,
            )
            statusCard(
                ViewText.HISTORY_RANGE_HIGH,
                Icons.CHART,
                statistics?.rangeExtrema?.high?.let { Formatter.formatSignedUsd(it.valueUsd) }
                    ?: ViewText.HISTORY_REPORT_UNAVAILABLE,
                HtmlIds.HISTORY_STAT_RANGE_HIGH,
            )
        }
    }

    private fun DIV.renderPeriodDetails(data: HistoryPageData) {
        val statistics = data.statistics
        div {
            details(CssClass.History.ReportDetails.value) {
                summary { +ViewText.HISTORY_SELECTED_PERIOD_DETAILS }
                dl {
                    reportRow(
                        ViewText.HISTORY_RANGE_LOW,
                        statistics?.rangeExtrema?.low?.let { Formatter.formatSignedUsd(it.valueUsd) }
                            ?: ViewText.HISTORY_REPORT_UNAVAILABLE,
                        HtmlIds.HISTORY_STAT_RANGE_LOW,
                    )
                    reportRow(
                        ViewText.HISTORY_OBSERVED_AT,
                        latestValueDetails(statistics),
                        HtmlIds.HISTORY_STAT_LATEST_META,
                    )
                    reportRow(
                        ViewText.HISTORY_CHANGE_ENDPOINTS,
                        changeEndpoints(statistics),
                        HtmlIds.HISTORY_STAT_CHANGE_ENDPOINTS,
                    )
                    reportRow(
                        ViewText.HISTORY_OBSERVED_VALUE_CHANGE,
                        statistics?.observedValueChange?.includesFlowsNotice ?: ViewText.HISTORY_VALUE_CHANGE_NOTICE,
                        HtmlIds.HISTORY_STAT_CHANGE_NOTICE,
                    )
                    reportRow(
                        ViewText.HISTORY_RANGE_HIGH,
                        statistics?.rangeExtrema?.high?.let { timeFormatter.format(it.observedAt) }
                            ?: ViewText.HISTORY_REPORT_UNAVAILABLE,
                        HtmlIds.HISTORY_STAT_RANGE_HIGH_AT,
                    )
                    reportRow(
                        ViewText.HISTORY_RANGE_LOW,
                        statistics?.rangeExtrema?.low?.let { timeFormatter.format(it.observedAt) }
                            ?: ViewText.HISTORY_REPORT_UNAVAILABLE,
                        HtmlIds.HISTORY_STAT_RANGE_LOW_AT,
                    )
                    reportRow(
                        ViewText.HISTORY_MATCHING_EXCHANGE_FILLS,
                        statistics?.trades?.let { reportCount(it.tradeCount, it.complete, it.incompleteReason) }
                            ?: ViewText.HISTORY_REPORT_UNAVAILABLE,
                        HtmlIds.HISTORY_STAT_TRADES,
                    )
                    reportRow(
                        ViewText.HISTORY_BOT_CYCLES,
                        statistics?.botCycles?.let { reportCount(it.cycleCount, it.complete, it.incompleteReason) }
                            ?: ViewText.HISTORY_REPORT_UNAVAILABLE,
                        HtmlIds.HISTORY_STAT_BOT_CYCLES,
                    )
                    reportRow(
                        ViewText.HISTORY_VOLUME_BY_CURRENCY,
                        statistics?.trades?.let {
                            reportQuantities(it.volumeByCurrency, it.complete, it.excludedCount, "currency")
                        } ?: ViewText.HISTORY_REPORT_UNAVAILABLE,
                        HtmlIds.HISTORY_STAT_VOLUME,
                    )
                    reportRow(
                        ViewText.HISTORY_FEES_BY_CURRENCY,
                        statistics?.trades?.let {
                            reportQuantities(it.feesByCurrency, it.complete, it.excludedCount, "currency")
                        } ?: ViewText.HISTORY_REPORT_UNAVAILABLE,
                        HtmlIds.HISTORY_STAT_FEES,
                    )
                    reportRow(
                        ViewText.HISTORY_EXCLUDED_AMOUNTS,
                        statistics?.trades?.let { Formatter.formatCount(it.excludedCount.toLong()) }
                            ?: ViewText.HISTORY_REPORT_UNAVAILABLE,
                        HtmlIds.HISTORY_STAT_EXCLUDED,
                    )
                    reportRow(
                        ViewText.HISTORY_ALLOCATION_DEVIATION,
                        allocationDeviationDetails(statistics),
                        HtmlIds.HISTORY_STAT_ALLOCATION,
                    )
                    reportRow(
                        ViewText.HISTORY_DEPOSITS_BY_ASSET,
                        statistics?.depositsWithdrawals?.deposits?.let {
                            reportQuantities(it.quantitiesByAsset, it.complete, it.excludedCount, "asset")
                        } ?: ViewText.HISTORY_REPORT_UNAVAILABLE,
                        HtmlIds.HISTORY_STAT_DEPOSITS,
                    )
                    reportRow(
                        ViewText.HISTORY_WITHDRAWALS_BY_ASSET,
                        statistics?.depositsWithdrawals?.withdrawals?.let {
                            reportQuantities(it.quantitiesByAsset, it.complete, it.excludedCount, "asset")
                        } ?: ViewText.HISTORY_REPORT_UNAVAILABLE,
                        HtmlIds.HISTORY_STAT_WITHDRAWALS,
                    )
                    reportRow(
                        ViewText.HISTORY_REWARDS_BY_ASSET,
                        statistics?.rewards?.let {
                            reportQuantities(it.quantitiesByAsset, it.complete, it.excludedCount, "asset")
                        } ?: ViewText.HISTORY_REPORT_UNAVAILABLE,
                        HtmlIds.HISTORY_STAT_REWARDS,
                    )
                    reportRow(
                        ViewText.HISTORY_LEDGER_CLASSIFICATION_NOTICE,
                        statistics?.depositsWithdrawals?.notice ?: ViewText.HISTORY_LEDGER_CLASSIFICATION_NOTICE,
                        HtmlIds.HISTORY_STAT_LEDGER_NOTICE,
                    )
                }
            }
        }
    }

    private fun DL.reportRow(label: String, value: String, valueId: String) {
        dt { +label }
        dd {
            id = valueId
            +value
        }
    }

    private fun reportUsd(value: BigDecimal?, complete: Boolean): String = if (!complete || value == null) {
        ViewText.HISTORY_REPORT_UNAVAILABLE
    } else if (value.signum() < 0) {
        "-${Formatter.formatSignedUsd(value.abs())}"
    } else {
        Formatter.formatSignedUsd(value)
    }

    private fun latestValueDetails(statistics: HistoryPeriodStatistics?): String {
        val latest = statistics?.latestObservedValue ?: return ViewText.HISTORY_REPORT_UNAVAILABLE
        val time = latest.observedAt?.let(timeFormatter::format) ?: return ViewText.HISTORY_REPORT_UNAVAILABLE
        return if (latest.outsideSelectedPeriod) "$time · ${ViewText.HISTORY_REPORT_OUTSIDE_RANGE}" else time
    }

    private fun changeEndpoints(statistics: HistoryPeriodStatistics?): String {
        val change = statistics?.observedValueChange ?: return ViewText.HISTORY_REPORT_UNAVAILABLE
        if (!change.complete) return ViewText.HISTORY_REPORT_UNAVAILABLE
        val first = change.firstValueUsd ?: return ViewText.HISTORY_REPORT_UNAVAILABLE
        val firstAt = change.firstObservedAt?.let(timeFormatter::format) ?: return ViewText.HISTORY_REPORT_UNAVAILABLE
        val last = change.lastValueUsd ?: return ViewText.HISTORY_REPORT_UNAVAILABLE
        val lastAt = change.lastObservedAt?.let(timeFormatter::format) ?: return ViewText.HISTORY_REPORT_UNAVAILABLE
        return "${Formatter.formatSignedUsd(first)} at $firstAt to ${Formatter.formatSignedUsd(last)} at $lastAt"
    }

    private fun reportCount(count: Long?, complete: Boolean, reason: String?): String {
        val countText = count?.let(Formatter::formatCount) ?: return reason ?: ViewText.HISTORY_REPORT_UNAVAILABLE
        return if (complete) countText else "$countText · ${reason ?: ViewText.HISTORY_REPORT_INCOMPLETE}"
    }

    private fun reportQuantities(
        quantities: Map<String, BigDecimal>,
        complete: Boolean,
        excludedCount: Int,
        unitLabel: String,
    ): String {
        if (!complete && quantities.isEmpty()) return ViewText.HISTORY_REPORT_UNAVAILABLE
        val amounts = if (quantities.isEmpty()) {
            "0"
        } else {
            quantities.entries.joinToString("; ") { (unit, amount) ->
                "${Formatter.formatQuantity(amount)} $unit"
            }
        }
        val status = if (complete) ViewText.HISTORY_REPORT_COMPLETE else ViewText.HISTORY_REPORT_INCOMPLETE
        val excluded = if (excludedCount == 0) "" else "; $excludedCount excluded $unitLabel amounts"
        return "$amounts · $status$excluded"
    }

    private fun allocationDeviationDetails(statistics: HistoryPeriodStatistics?): String {
        val allocation = statistics?.allocationDeviation ?: return ViewText.HISTORY_REPORT_UNAVAILABLE
        val deviations = allocation.targetsUsed.map { (symbol, target) ->
            val deviation = allocation.deviationsBySymbol[symbol]
            if (deviation == null) {
                "$symbol: ${ViewText.HISTORY_REPORT_UNAVAILABLE} (${Formatter.formatPercent(target)}% target)"
            } else {
                val signed = if (deviation.signum() > 0) "+" else ""
                "$symbol: $signed${Formatter.formatPercent(deviation)} pp (${Formatter.formatPercent(target)}% target)"
            }
        }
        if (deviations.isEmpty()) return allocation.incompleteReason ?: ViewText.HISTORY_REPORT_UNAVAILABLE
        val observedAt = allocation.observedAt?.let(timeFormatter::format).orEmpty()
        val status = if (allocation.complete) ViewText.HISTORY_REPORT_COMPLETE else ViewText.HISTORY_REPORT_INCOMPLETE
        return listOf(deviations.joinToString("; "), observedAt, status).filter(String::isNotEmpty).joinToString(" · ")
    }

    private fun DIV.renderValueChart(data: HistoryPageData) {
        val observations = data.observations.sortedBy { it.observedAt }
        val recordedChart = data.portfolioChart?.takeIf {
            it.scopeSelection == HistoryApiScopeSelection.ACCOUNT_RECORDED
        }
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
            renderStatsGrid(data)
            if (recordedChart != null) {
                p(CssClass.History.ChartCaption) {
                    +ViewText.HISTORY_SCOPE_SUMMARY
                    +" ${recordedChart.scope.symbols.joinToString(" / ")}"
                }
                if (recordedChart.boundaries.isNotEmpty()) {
                    p(CssClass.History.ChartCaption) { +ViewText.HISTORY_SCOPE_TRANSITIONS }
                }
                if (recordedChart.boundaries.any { it.kind == HistoryApiBoundaryKind.OVERLAPPING_SCOPES }) {
                    p(CssClass.Utility.TextDanger) { +ViewText.HISTORY_SCOPE_CONFLICT }
                }
            }
            if (observations.isNotEmpty() && !hasCompleteValue) {
                p(CssClass.History.MutedSmallText) { +ViewText.HISTORY_VALUE_NO_DATA }
            }
            val recordedSeries = recordedChart?.series?.map { series ->
                series.legendLabel to series.points.mapNotNull { point ->
                    point.v?.takeIf { point.complete }?.let {
                        HistorySeriesPoint(Instant.ofEpochMilli(point.t), it)
                    }
                }
            }?.filter { it.second.isNotEmpty() }.orEmpty()
            div(CssClass.History.ChartContainer) {
                id = HtmlIds.HISTORY_VALUE_CHART
                if (recordedChart != null) {
                    attributes[HtmlAttrs.DATA_HISTORY_SCOPE_SELECTION] = recordedChart.scopeSelection.name
                    recordedChart.fullRange.fromIso?.let { attributes[HtmlAttrs.DATA_FULL_FROM] = it }
                    recordedChart.fullRange.toIso?.let { attributes[HtmlAttrs.DATA_FULL_TO] = it }
                    recordedChart.series.forEach { series ->
                        div {
                            attributes["hidden"] = "hidden"
                            attributes[HtmlAttrs.DATA_HISTORY_SOURCE_KEY] = series.key
                            attributes[HtmlAttrs.DATA_HISTORY_SOURCE_LABEL] = series.label
                        }
                    }
                    val series = recordedSeries
                    if (series.isEmpty()) {
                        p(CssClass.History.MutedSmallText) { +ViewText.HISTORY_VALUE_NO_DATA }
                    } else {
                        val times = recordedChart.series.flatMap { it.points }.map { Instant.ofEpochMilli(it.t) } +
                            listOfNotNull(recordedChart.fullRange.fromIso, recordedChart.fullRange.toIso)
                                .map(Instant::parse)
                        unsafe { +multiSeriesSvg(series, zeroLine = false, sampleTimes = times) }
                    }
                } else if (observations.isEmpty()) {
                    p(CssClass.History.MutedSmallText) { +ViewText.HISTORY_VALUE_NO_DATA }
                } else {
                    unsafe { +valuePointSvg(observations) }
                }
            }
            if (recordedSeries.size > 1) renderSeriesLegend(recordedSeries)
            div {
                attributes["class"] = HistoryRangeCss.BANNER
                id = HistoryRangeIds.NEW_DATA_BANNER
                attributes["hidden"] = "hidden"
                p { +HistoryRangeText.NEW_DATA_RETURN }
                button(type = ButtonType.button, classes = CssClass.History.PaginationBtn.value) {
                    id = HistoryRangeIds.NEW_DATA_BUTTON
                    +HistoryRangeText.NEW_DATA_ACTION
                }
            }
            if (hasCompleteValue && observations.any { it.status != HistoryObservationStatus.COMPLETE }) {
                p(CssClass.History.SyncText) { +ViewText.HISTORY_VALUE_PARTIAL }
            }
            renderChartScrubber(HtmlIds.HISTORY_VALUE_CHART)
            p(CssClass.History.ChartCaption) { +ViewText.HISTORY_VALUE_CAPTION }
            renderPeriodDetails(data)
        }
    }

    private fun DIV.chartControls(pointsOnly: Boolean = false) {
        div(CssClass.History.ChartControls) {
            attributes[HtmlAttrs.DATA_HISTORY_CONTROLS] = "true"
            attributes["hidden"] = "hidden"
            details(CssClass.History.ToolsMenu.value) {
                summary { +ViewText.HISTORY_CHART_TOOLS }
                div {
                    if (!pointsOnly) {
                        chartButton(
                            HtmlAttrs.DATA_HISTORY_VIEW,
                            HistoryChartActions.VIEW_LINE,
                            ViewText.HISTORY_CHART_LINE,
                        )
                        chartButton(
                            HtmlAttrs.DATA_HISTORY_VIEW,
                            HistoryChartActions.VIEW_POINTS,
                            ViewText.HISTORY_CHART_POINTS,
                        )
                    }
                    chartButton(
                        HtmlAttrs.DATA_HISTORY_ZOOM,
                        HistoryChartActions.ZOOM_IN,
                        ViewText.HISTORY_CHART_ZOOM_IN,
                    )
                    chartButton(
                        HtmlAttrs.DATA_HISTORY_ZOOM,
                        HistoryChartActions.ZOOM_OUT,
                        ViewText.HISTORY_CHART_ZOOM_OUT,
                    )
                    chartButton(
                        HtmlAttrs.DATA_HISTORY_PAN,
                        HistoryChartActions.PAN_EARLIER,
                        ViewText.HISTORY_CHART_EARLIER,
                    )
                    chartButton(HtmlAttrs.DATA_HISTORY_PAN, HistoryChartActions.PAN_LATER, ViewText.HISTORY_CHART_LATER)
                    chartButton(
                        HtmlAttrs.DATA_HISTORY_ZOOM,
                        HistoryChartActions.ZOOM_RESET,
                        ViewText.HISTORY_CHART_RESET,
                    )
                    chartButton(
                        HtmlAttrs.DATA_HISTORY_EXPAND,
                        HistoryChartActions.EXPAND,
                        ViewText.HISTORY_CHART_EXPAND,
                    )
                    p(CssClass.History.ChartCaption) { +ViewText.HISTORY_CHART_HELP }
                }
            }
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
            id = HistoryRangeIds.CHECKPOINTS_SECTION
            analysisPanel(HistoryAnalysis.CHECKPOINTS)
            div(CssClass.History.ChartHeader) {
                h2(CssClass.Utility.GlassPanelTitle + CssClass.History.ChartHeaderTitle) {
                    +(
                        ViewText.HISTORY_CHECKPOINT_TITLE +
                            asset?.let { " (${Asset.normalizeLedgerAsset(it)})" }.orEmpty()
                        )
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
            analysis = HistoryAnalysis.HOLDINGS,
            title = ViewText.HISTORY_ASSET_HOLDINGS,
            series = assetValueSeries(data.observations),
            sampleTimes = data.observations.map { it.observedAt },
            caption = ViewText.HISTORY_HOLDINGS_CAPTION,
            emptyText = ViewText.HISTORY_VALUE_NO_DATA,
        )
    }

    private fun DIV.renderAllocationChart(data: HistoryPageData) {
        renderSeriesChart(
            sectionId = HtmlIds.HISTORY_ALLOCATION_SECTION,
            chartId = HtmlIds.HISTORY_ALLOCATION_CHART,
            analysis = HistoryAnalysis.ALLOCATION,
            title = ViewText.HISTORY_ALLOCATION_DRIFT,
            series = allocationDeviationSeries(
                data.observations,
                data.allocations.associate { it.symbol.value to BigDecimal.valueOf(it.targetPercent) },
            ),
            caption = ViewText.HISTORY_ALLOCATION_CAPTION,
            emptyText = ViewText.HISTORY_VALUE_NO_DATA,
            zeroLine = true,
            sampleTimes = data.observations.map { it.observedAt },
            formatValue = { "${Formatter.formatPercent(it)} pp" },
        )
    }

    private fun DIV.renderCashFlowChart(data: HistoryPageData) {
        renderNativeQuantityChart(
            sectionId = HtmlIds.HISTORY_CASH_FLOW_SECTION,
            chartId = HtmlIds.HISTORY_CASH_FLOW_CHART,
            analysis = HistoryAnalysis.CASH_FLOW,
            title = ViewText.HISTORY_NET_CASH_FLOW,
            series = data.fundingSeries,
            caption = ViewText.HISTORY_CASH_FLOW_CAPTION,
            emptyText = reportSeriesEmptyText(data.accountAvailable),
        )
    }

    private fun DIV.renderRewardsChart(data: HistoryPageData) {
        renderNativeQuantityChart(
            sectionId = HtmlIds.HISTORY_REWARDS_SECTION,
            chartId = HtmlIds.HISTORY_REWARDS_CHART,
            analysis = HistoryAnalysis.REWARDS,
            title = ViewText.HISTORY_STAKING_REWARDS,
            series = data.rewardSeries,
            caption = ViewText.HISTORY_REWARDS_CAPTION,
            emptyText = reportSeriesEmptyText(data.accountAvailable),
        )
    }

    /** Marks one secondary analysis panel; only the selected analysis is visible at a time. */
    private fun DIV.analysisPanel(analysis: String) {
        attributes[HtmlAttrs.DATA_HISTORY_ANALYSIS_PANEL] = analysis
        if (analysis != HistoryAnalysis.HOLDINGS) attributes["hidden"] = "hidden"
    }

    private fun DIV.renderNativeQuantityChart(
        sectionId: String,
        chartId: String,
        analysis: String,
        title: String,
        series: List<NativeQuantitySeries>,
        caption: String,
        emptyText: String,
    ) {
        div(CssClass.Layout.GlassPanel) {
            id = sectionId
            analysisPanel(analysis)
            div(CssClass.History.ChartHeader) {
                h2(CssClass.Utility.GlassPanelTitle + CssClass.History.ChartHeaderTitle) { +title }
            }
            if (series.size > 1) {
                label {
                    +ViewText.HISTORY_SERIES_ASSET_LABEL
                    select {
                        attributes[HtmlAttrs.DATA_HISTORY_SERIES_SELECT] = chartId
                        series.forEachIndexed { index, item ->
                            option {
                                value = item.asset
                                selected = index == 0
                                +Asset.normalizeLedgerAsset(item.asset)
                            }
                        }
                    }
                }
            }
            div(CssClass.History.ChartContainer) {
                id = chartId
                if (series.isEmpty()) {
                    p(CssClass.History.MutedSmallText) { +emptyText }
                } else {
                    series.forEachIndexed { index, item ->
                        div {
                            attributes[HtmlAttrs.DATA_HISTORY_SERIES_ASSET] = item.asset
                            if (index > 0) attributes["hidden"] = "hidden"
                            val points = item.points.map { HistorySeriesPoint(it.observedAt, it.cumulativeQuantity) }
                            val displayAsset = Asset.normalizeLedgerAsset(item.asset)
                            unsafe {
                                +multiSeriesSvg(
                                    series = listOf(displayAsset to points),
                                    zeroLine = false,
                                    sampleTimes = emptyList(),
                                    formatValue = { "${Formatter.formatQuantity(it)} $displayAsset" },
                                )
                            }
                        }
                    }
                }
            }
            p(CssClass.History.ChartCaption) { +caption }
        }
    }

    private fun reportSeriesEmptyText(accountAvailable: Boolean): String =
        if (accountAvailable) ViewText.HISTORY_REPORT_EMPTY else ViewText.HISTORY_REPORT_UNAVAILABLE

    private fun DIV.renderSeriesChart(
        sectionId: String,
        chartId: String,
        analysis: String,
        title: String,
        series: List<Pair<String, List<HistorySeriesPoint>>>,
        caption: String,
        emptyText: String,
        zeroLine: Boolean = false,
        sampleTimes: List<Instant> = emptyList(),
        formatValue: (BigDecimal) -> String = Formatter::formatSignedUsd,
        headerStat: (DIV.() -> Unit)? = null,
    ) {
        div(CssClass.Layout.GlassPanel) {
            id = sectionId
            analysisPanel(analysis)
            div(CssClass.History.ChartHeader) {
                h2(CssClass.Utility.GlassPanelTitle + CssClass.History.ChartHeaderTitle) { +title }
                headerStat?.invoke(this)
            }
            div(CssClass.History.ChartContainer) {
                id = chartId
                if (series.all { it.second.isEmpty() }) {
                    p(CssClass.History.MutedSmallText) { +emptyText }
                } else {
                    unsafe { +multiSeriesSvg(series, zeroLine, sampleTimes, formatValue) }
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
            attributes[HtmlAttrs.DATA_HISTORY_ACTIVITY_PANEL] = HistoryRangeQuery.ACTIVITY_TRADES
            attributes["hidden"] = "hidden"
            div(CssClass.History.TradeLogHeader) {
                h2(CssClass.Utility.GlassPanelTitle + CssClass.History.TitleNoMargin) {
                    +ViewText.HISTORY_TRADE_LOG
                }
                exportButton("trades", Routes.API_HISTORY_EXPORT_TRADES)
                data.trades.total?.let { total ->
                    span(CssClass.Badge.Info) {
                        attributes["data-history-total"] = "trades"
                        +total.toString()
                    }
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
                        data.trades.items.take(HistoryRangeProps.RECORD_PAGE_SIZE).forEach { t ->
                            tr(CssClass.Table.Hoverable) {
                                td(CssClass.Table.MonoCol) {
                                    val timestamp = recordTimestamp(t.time, t.rawTime)
                                    if (timestamp != ViewText.EM_DASH) title = timestamp
                                    details {
                                        listOfNotNull(t.tradeId, t.orderTxid, t.postTxid)
                                            .firstOrNull { it.isNotBlank() }
                                            ?.let { attributes[HtmlAttrs.DATA_HISTORY_DETAIL_KEY] = "trade:$it" }
                                        summary { +timestamp }
                                        dl {
                                            detail(HistoryRangeText.DETAILS_TIMESTAMP, timestamp)
                                            detail(
                                                HistoryRangeText.DETAILS_UNITS,
                                                originalValue(t.volume, t.baseAsset),
                                            )
                                            detail(
                                                HistoryRangeText.DETAILS_PRICE,
                                                originalValue(t.price, t.quoteCurrency),
                                            )
                                            detail(
                                                HistoryRangeText.DETAILS_COST,
                                                originalValue(t.cost, t.quoteCurrency),
                                            )
                                            detail(
                                                HistoryRangeText.DETAILS_FEE,
                                                originalValue(t.fee, t.feeCurrency ?: t.quoteCurrency),
                                            )
                                            detail(HistoryRangeText.DETAILS_STATUS, recordStatus(t.isValidRecord))
                                            detail(HistoryRangeText.DETAILS_EXCHANGE_TRADE_ID, t.tradeId)
                                            detail(HistoryRangeText.DETAILS_EXCHANGE_ORDER_ID, t.orderTxid)
                                            detail(HistoryRangeText.DETAILS_POST_ID, t.postTxid)
                                            detail(
                                                HistoryRangeText.DETAILS_LEDGER_ID,
                                                t.ledgerIds.joinToString().ifEmpty { ViewText.EM_DASH },
                                            )
                                            detail(HistoryRangeText.DETAILS_BOT_LINK, HistoryRangeText.UNKNOWN_LINKAGE)
                                        }
                                    }
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
            pagination(data, visiblePage(data.trades), isTrades = true)
        }
    }

    private fun DIV.renderLedgers(data: HistoryPageData) {
        div(CssClass.Layout.GlassPanel + CssClass.History.TradeLog) {
            id = HtmlIds.HISTORY_LEDGERS_SECTION
            attributes[HtmlAttrs.DATA_HISTORY_ACTIVITY_PANEL] = HistoryRangeQuery.ACTIVITY_TRANSACTIONS
            attributes["hidden"] = "hidden"
            div(CssClass.History.TradeLogHeader) {
                h2(CssClass.Utility.GlassPanelTitle + CssClass.History.TitleNoMargin) {
                    +ViewText.HISTORY_TRANSACTION_LOG
                }
                exportButton("ledgers", Routes.API_HISTORY_EXPORT_LEDGERS)
                data.ledgers.total?.let { total ->
                    span(CssClass.Badge.Info) {
                        attributes["data-history-total"] = "ledgers"
                        +total.toString()
                    }
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
                        data.ledgers.items.take(HistoryRangeProps.RECORD_PAGE_SIZE).forEach { l ->
                            tr(CssClass.Table.Hoverable) {
                                td(CssClass.Table.MonoCol) {
                                    val timestamp = recordTimestamp(l.time, l.rawTime)
                                    if (timestamp != ViewText.EM_DASH) title = timestamp
                                    details {
                                        listOfNotNull(l.ledgerId, l.refid)
                                            .firstOrNull { it.isNotBlank() }
                                            ?.let { attributes[HtmlAttrs.DATA_HISTORY_DETAIL_KEY] = "ledger:$it" }
                                        summary { +timestamp }
                                        dl {
                                            detail(HistoryRangeText.DETAILS_TIMESTAMP, timestamp)
                                            detail(
                                                HistoryRangeText.DETAILS_UNITS,
                                                originalValue(l.amount, l.asset),
                                            )
                                            detail(
                                                HistoryRangeText.DETAILS_FEE,
                                                originalValue(l.fee, l.asset),
                                            )
                                            detail(
                                                HistoryRangeText.DETAILS_BALANCE,
                                                originalValue(l.balance, l.asset),
                                            )
                                            detail(HistoryRangeText.DETAILS_STATUS, recordStatus(l.isValidRecord))
                                            detail(HistoryRangeText.DETAILS_LEDGER_ID, l.ledgerId)
                                            detail(HistoryRangeText.DETAILS_REFERENCE_ID, l.refid)
                                            detail(HistoryRangeText.DETAILS_BOT_LINK, HistoryRangeText.UNKNOWN_LINKAGE)
                                        }
                                    }
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
            pagination(data, visiblePage(data.ledgers), isTrades = false)
        }
    }

    private fun <T> visiblePage(page: HistoryPage<T>): HistoryPage<T> {
        val visibleItems = page.items.take(HistoryRangeProps.RECORD_PAGE_SIZE)
        return page.copy(
            items = visibleItems,
            limit = HistoryRangeProps.RECORD_PAGE_SIZE,
            hasMore = page.hasMore || page.items.size > visibleItems.size,
        )
    }

    private fun recordTimestamp(time: Instant?, rawTime: String?): String =
        time?.let(timeFormatter::format) ?: rawTime?.takeIf(String::isNotBlank) ?: ViewText.EM_DASH

    private fun originalValue(amount: String?, currency: String?): String =
        "${amount?.takeIf(String::isNotBlank) ?: ViewText.EM_DASH} " +
            (currency?.takeIf(String::isNotBlank) ?: ViewText.HISTORY_UNKNOWN_CURRENCY)

    private fun recordStatus(valid: Boolean): String =
        if (valid) HistoryRangeText.RECORD_VALID else HistoryRangeText.RECORD_INCOMPLETE

    private fun DL.detail(label: String, value: String?) {
        dt { +label }
        dd { +(value?.takeIf(String::isNotBlank) ?: ViewText.EM_DASH) }
    }

    private fun TBODY.emptyRow(columns: Int) {
        tr {
            td(CssClass.History.EmptyTableCell) {
                colSpan = columns.toString()
                +ViewText.HISTORY_EVENTS_EMPTY
            }
        }
    }

    private fun TBODY.emptyActivityRow() {
        tr {
            td(CssClass.History.EmptyTableCell) {
                colSpan = "5"
                +HistoryRangeText.ACTIVITY_EMPTY
            }
        }
    }

    private fun DIV.pagination(data: HistoryPageData, page: HistoryPage<*>, isTrades: Boolean) {
        div(CssClass.History.Pagination) {
            val offset = page.offset
            val previous = (offset - page.limit).coerceAtLeast(0)
            val next = offset + page.limit
            val tableName = if (isTrades) "trades" else "ledgers"
            val activity = if (isTrades) {
                HistoryRangeQuery.ACTIVITY_TRADES
            } else {
                HistoryRangeQuery.ACTIVITY_TRANSACTIONS
            }
            attributes["data-history-pagination"] = tableName
            attributes["data-history-offset"] = offset.toString()
            attributes["data-history-limit"] = page.limit.toString()
            if (offset > 0) {
                a(
                    href = pageUrl(
                        data,
                        tradesOffset = if (isTrades) previous else null,
                        ledgersOffset = if (isTrades) null else previous,
                        tab = HistoryRangeQuery.TAB_ACTIVITY,
                        activity = activity,
                    ) + "#" + if (isTrades) HtmlIds.HISTORY_TRADES_SECTION else HtmlIds.HISTORY_LEDGERS_SECTION,
                    classes = CssClass.History.PaginationBtn.value,
                ) {
                    id = if (isTrades) HtmlIds.HISTORY_TRADES_PREV else HtmlIds.HISTORY_LEDGERS_PREV
                    +ViewText.HISTORY_PREVIOUS
                }
            }
            span(CssClass.History.MutedSmallText) {
                attributes["data-history-page-range"] = tableName
                val shown = if (page.items.isEmpty()) "0–0" else "${offset + 1}–${offset + page.items.size}"
                +"$shown${page.total?.let { " of $it" }.orEmpty()}"
            }
            if (page.hasMore) {
                a(
                    href = pageUrl(
                        data,
                        tradesOffset = if (isTrades) next else null,
                        ledgersOffset = if (isTrades) null else next,
                        tab = HistoryRangeQuery.TAB_ACTIVITY,
                        activity = activity,
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
        tab: String? = null,
        activity: String? = null,
    ): String {
        var url = Routes.HISTORY.withQuery("range", range)
        data.filters.assetKey?.let { url = url.withQuery("asset", it) }
        data.filters.pair?.let { url = url.withQuery("pair", it) }
        data.filters.type?.let { url = url.withQuery("type", it) }
        tab?.let { url = url.withQuery(HistoryRangeQuery.PARAM_TAB, it) }
        activity?.let { url = url.withQuery(HistoryRangeQuery.PARAM_ACTIVITY, it) }
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
        sampleTimes: List<Instant>,
        formatValue: (BigDecimal) -> String = Formatter::formatSignedUsd,
    ): String {
        val allPoints = series.flatMap { it.second }
        val times = allPoints.map { it.time } + sampleTimes
        val minT = times.minOf { it.epochSecond }
        val maxT = times.maxOf { it.epochSecond }
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
            val recordedTimes = points.map { it.time }.toSet()
            val gaps = sampleTimes.filter { it !in recordedTimes }.distinct().sorted()
            val line = points.groupBy { point -> gaps.count { it <= point.time } }.values
                .filter { it.size > 1 }.joinToString("") { segment ->
                    val coordinates = segment.joinToString(" ") { point ->
                        "%.2f,%.2f".format(Locale.US, xCoordinate(point.time), yCoordinate(point.value))
                    }
                    """<polyline class="${CssClass.History.Line.value}" points="$coordinates" style="stroke:$color"/>"""
                }
            val gapMarkers = gaps.joinToString("") { time ->
                val x = "%.2f".format(Locale.US, xCoordinate(time))
                """<circle class="${CssClass.History.GapMarker.value}" cx="$x" cy="${SVG_HEIGHT - SVG_PAD + 8}" """ +
                    """r="4" ${HtmlAttrs.DATA_TIME}="${time.toEpochMilli()}" ${HtmlAttrs.DATA_GAP}="true" """ +
                    """${HtmlAttrs.DATA_SERIES}="${escapeSvgText(symbol)}"/>"""
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
            line + gapMarkers +
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
        val seriesAttr = sample.series?.let { " ${HtmlAttrs.DATA_SERIES}=\"${escapeSvgText(it)}\"" } ?: ""
        val hit =
            (
                """<circle cx="%.2f" cy="%.2f" r="7" fill="transparent" """ +
                    "${HtmlAttrs.DATA_TIME}=\"${sample.timeMillis}\" " +
                    "${HtmlAttrs.DATA_VALUE}=\"${sample.valueAttr}\"$seriesAttr>"
                ).format(Locale.US, sample.x, sample.y) + "<title>${escapeSvgText(sample.title)}</title></circle>"
        val visible = if (index % visibleEvery == 0 || index == samples.lastIndex) {
            val fill = color?.let { """ style="fill:$it"""" } ?: ""
            (
                """<circle class="${CssClass.History.Point.value}" cx="%.2f" cy="%.2f" r="$visibleRadius"$fill """ +
                    "${HtmlAttrs.DATA_TIME}=\"${sample.timeMillis}\" " +
                    "${HtmlAttrs.DATA_VALUE}=\"${sample.valueAttr}\"$seriesAttr>"
                ).format(Locale.US, sample.x, sample.y) + "<title>${escapeSvgText(sample.title)}</title></circle>"
        } else {
            ""
        }
        hit + visible
    }.joinToString("")

    private fun escapeSvgText(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

    private class SvgSample(
        val x: Double,
        val y: Double,
        val title: String,
        val timeMillis: Long,
        val valueAttr: String,
        val series: String? = null,
    )

    private companion object {
        val RANGES = listOf(
            "24h" to "24h",
            "7d" to "7d",
            "30d" to "30d",
            "90d" to "90d",
            "1y" to "1y",
            "all" to "All",
        )
        const val TRADE_COLUMNS = 7
        const val LEDGER_COLUMNS = 6
        const val SVG_WIDTH = 960
        const val SVG_HEIGHT = 240
        const val SVG_PAD = 32
        const val SVG_LABEL_GUTTER = 120
        const val SVG_PLOT_RIGHT = SVG_WIDTH - SVG_PAD
    }
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
    // Only configured targets have a reference to deviate from, so only they are charted.
    return targets.keys.toSortedSet().map { symbol ->
        val target = targets.getValue(symbol)
        symbol to ordered.mapNotNull { observation ->
            allocationDeviationPercent(observation, symbol, target)?.let { deviation ->
                HistorySeriesPoint(observation.observedAt, deviation)
            }
        }
    }
}
