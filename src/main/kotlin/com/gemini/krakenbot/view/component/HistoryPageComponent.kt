package com.gemini.krakenbot.view.component

import com.fasterxml.jackson.databind.ObjectMapper
import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.model.TimeRange
import com.gemini.krakenbot.util.PrecisionConstants
import com.gemini.krakenbot.view.util.ActiveNav
import com.gemini.krakenbot.view.util.CdnIntegrity
import com.gemini.krakenbot.view.util.CdnUrls
import com.gemini.krakenbot.view.util.ChartProps
import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.HtmlAttrs
import com.gemini.krakenbot.view.util.HtmlIds
import com.gemini.krakenbot.view.util.Icons
import com.gemini.krakenbot.view.util.Icons.icon
import com.gemini.krakenbot.view.util.ViewText
import com.gemini.krakenbot.view.util.ZoomActions
import com.gemini.krakenbot.view.util.brandWithMode
import com.gemini.krakenbot.view.util.button
import com.gemini.krakenbot.view.util.cdnScript
import com.gemini.krakenbot.view.util.commonMetadataAndStyles
import com.gemini.krakenbot.view.util.div
import com.gemini.krakenbot.view.util.h2
import com.gemini.krakenbot.view.util.label
import com.gemini.krakenbot.view.util.p
import com.gemini.krakenbot.view.util.primaryNav
import com.gemini.krakenbot.view.util.rebalancerJsSrc
import com.gemini.krakenbot.view.util.span
import com.gemini.krakenbot.view.util.statusCard
import com.gemini.krakenbot.view.util.td
import kotlinx.html.BUTTON
import kotlinx.html.ButtonType
import kotlinx.html.DIV
import kotlinx.html.HTML
import kotlinx.html.InputType
import kotlinx.html.InputType.checkBox
import kotlinx.html.body
import kotlinx.html.canvas
import kotlinx.html.classes
import kotlinx.html.head
import kotlinx.html.header
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.script
import kotlinx.html.select
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.title
import kotlinx.html.tr
import kotlinx.html.unsafe

class HistoryPageComponent(private val objectMapper: ObjectMapper) {

    context(html: HTML)
    fun render(settings: Settings, symbolColorMap: Map<String, String> = emptyMap()) {
        html.head {
            commonMetadataAndStyles()
            title("${ViewText.HISTORY_TITLE} - ${ViewText.APP_TITLE}")
            cdnScript(CdnUrls.CHART_JS, CdnIntegrity.CHART_JS)
            cdnScript(CdnUrls.CHART_JS_DATE_FNS, CdnIntegrity.CHART_JS_DATE_FNS)
            cdnScript(CdnUrls.HAMMER_JS, CdnIntegrity.HAMMER_JS)
            cdnScript(CdnUrls.CHART_JS_ZOOM, CdnIntegrity.CHART_JS_ZOOM)
        }
        html.body {
            div(CssClass.Layout.Container) {
                renderHeader(settings)
                renderSyncProgressBanner()
                renderToolbar()
                renderStatsGrid()
                renderComparisonChartSection()
                HistoryChartSection.entries.forEach { chart ->
                    renderChartSection(chart)
                }
                renderTradeTable()
            }
            if (symbolColorMap.isNotEmpty()) {
                val json = objectMapper.writeValueAsString(symbolColorMap)
                script {
                    unsafe {
                        raw("window.${ChartProps.ASSET_COLORS_GLOBAL_KEY}=$json")
                    }
                }
            }
            script(src = rebalancerJsSrc()) {}
        }
    }

    private fun DIV.renderHeader(settings: Settings) {
        header {
            brandWithMode(settings)
            primaryNav(ActiveNav.HISTORY)
        }
    }

    private fun DIV.renderStatsGrid() {
        div(CssClass.History.StatsGrid) {
            id = HtmlIds.HISTORY_STATS
            HistoryStatCardDefinition.entries.forEach { card ->
                statusCard(
                    title = card.title,
                    iconSvg = card.iconSvg,
                    value = ViewText.PLACEHOLDER_DASHES,
                    valueId = card.valueId,
                    titleId = card.titleId,
                )
            }
        }
    }

    private fun DIV.renderToolbar() {
        div(CssClass.History.ToolbarRow) {
            renderTimeRangeSelector()
            renderViewsToolbar()
        }
    }

    private fun DIV.renderTimeRangeSelector() {
        div(CssClass.History.TimeRangeSelector) {
            TimeRange.entries.forEach { range ->
                val isActive = range == TimeRange.THIRTY_DAYS
                val btnClass = if (isActive) CssClass.History.TimeRangeBtnActive else CssClass.History.TimeRangeBtn
                button(btnClass) {
                    attributes[HtmlAttrs.DATA_RANGE] = range.key
                    +if (range == TimeRange.ALL) ViewText.LABEL_ALL else range.key
                }
            }
        }
    }

    private fun DIV.renderViewsToolbar() {
        div(CssClass.History.ViewsToolbar) {
            label(CssClass.History.ViewsLabel) {
                htmlFor = HtmlIds.HISTORY_VIEWS_SELECT
                +ViewText.HISTORY_VIEWS
            }
            select {
                id = HtmlIds.HISTORY_VIEWS_SELECT
                classes = setOf(CssClass.History.ViewsSelect.value)
            }
            div(CssClass.History.ViewsActions) {
                button(CssClass.History.ViewsBtn) {
                    id = HtmlIds.HISTORY_SAVE_VIEW_BTN
                    type = ButtonType.button
                    +ViewText.HISTORY_SAVE_VIEW
                }
                button(CssClass.History.ViewsBtn) {
                    id = HtmlIds.HISTORY_SET_DEFAULT_BTN
                    type = ButtonType.button
                    +ViewText.HISTORY_SET_DEFAULT
                }
                button(CssClass.Button.DangerGhost) {
                    id = HtmlIds.HISTORY_DELETE_VIEW_BTN
                    type = ButtonType.button
                    +ViewText.HISTORY_DELETE_VIEW
                }
            }
        }
    }

    private fun DIV.renderComparisonChartSection() {
        div(CssClass.Layout.GlassPanel) {
            div(CssClass.History.ChartHeader + CssClass.History.ComparisonHeader) {
                h2(CssClass.Utility.GlassPanelTitle + CssClass.History.ChartHeaderTitle) {
                    icon(Icons.CHART)
                    +ViewText.HISTORY_REBALANCER_VS_BUY_AND_HOLD
                }
                div(CssClass.History.ComparisonDelta) {
                    id = HtmlIds.COMPARISON_LATEST_DIFFERENCE
                    +ViewText.EM_DASH
                }
                div(CssClass.History.ChartTools) {
                    zoomButton(HtmlIds.REBALANCER_COMPARISON_CHART, ZoomActions.OUT, ViewText.HISTORY_ZOOM_OUT)
                    zoomButton(HtmlIds.REBALANCER_COMPARISON_CHART, ZoomActions.IN, ViewText.HISTORY_ZOOM_IN)
                    zoomButton(HtmlIds.REBALANCER_COMPARISON_CHART, ZoomActions.RESET, ViewText.HISTORY_ZOOM_RESET)
                }
            }
            div(CssClass.History.ComparisonChartArea) {
                id = HtmlIds.COMPARISON_CHART_CONTENT
                div(CssClass.History.ChartContainer) {
                    canvas {
                        id = HtmlIds.REBALANCER_COMPARISON_CHART
                    }
                }
            }
            div(CssClass.History.ComparisonUnavailable) {
                id = HtmlIds.COMPARISON_AVAILABILITY_MESSAGE
            }
            span(CssClass.History.ComparisonConfidenceBadge) {
                id = HtmlIds.COMPARISON_CONFIDENCE_BADGE
            }
            p(CssClass.History.ChartCaption) { +ViewText.COMPARISON_CAPTION }
            div(CssClass.History.ChartScrubber) {
                input(classes = CssClass.History.ChartScrubberInput.value, type = InputType.range) {
                    min = "0"
                    max = "100"
                    step = "0.1"
                    value = "0"
                    disabled = true
                    attributes[HtmlAttrs.DATA_CHART_ID] = HtmlIds.REBALANCER_COMPARISON_CHART
                }
            }
        }
    }

    private fun DIV.renderChartSection(chart: HistoryChartSection) {
        // HIST-2: one 44px header row (title + compact zoom) instead of three stacked rows.
        div(CssClass.Layout.GlassPanel) {
            div(CssClass.History.ChartHeader) {
                h2(CssClass.Utility.GlassPanelTitle + CssClass.History.ChartHeaderTitle) {
                    icon(chart.iconSvg)
                    +chart.title
                }
                div(CssClass.History.ChartTools) {
                    zoomButton(chart.canvasId, ZoomActions.OUT, ViewText.HISTORY_ZOOM_OUT)
                    zoomButton(chart.canvasId, ZoomActions.IN, ViewText.HISTORY_ZOOM_IN)
                    zoomButton(chart.canvasId, ZoomActions.RESET, ViewText.HISTORY_ZOOM_RESET)
                }
            }
            div(CssClass.History.ChartContainer) {
                canvas {
                    id = chart.canvasId
                }
            }
            if (chart.caption != null) {
                p(CssClass.History.ChartCaption) { +chart.caption }
            }
            div(CssClass.History.ChartScrubber) {
                input(classes = CssClass.History.ChartScrubberInput.value, type = InputType.range) {
                    min = "0"
                    max = "100"
                    step = "0.1"
                    value = "0"
                    // Starts disabled; :frontend-js enables once the chart has a zoomable range.
                    disabled = true
                    attributes[HtmlAttrs.DATA_CHART_ID] = chart.canvasId
                }
            }
        }
    }

    private fun DIV.zoomButton(canvasId: String, action: String, label: String) {
        button(CssClass.History.ZoomBtn) {
            type = ButtonType.button
            attributes[HtmlAttrs.DATA_CHART_ID] = canvasId
            attributes[HtmlAttrs.DATA_ZOOM_ACTION] = action
            +label
        }
    }

    private fun DIV.renderTradeTable() {
        div(CssClass.Layout.GlassPanel + CssClass.History.TradeLog) {
            div(CssClass.History.TradeLogHeader) {
                h2(CssClass.Utility.GlassPanelTitle + CssClass.History.TitleNoMargin) {
                    icon(Icons.CHART)
                    +ViewText.HISTORY_TRADE_LOG
                }
                label(CssClass.Form.CheckboxContainer) {
                    input(type = checkBox) {
                        id = HtmlIds.SHOW_DRY_RUN_CHECKBOX
                        checked = true
                    }
                    div(CssClass.Form.CheckboxCustom) {}
                    span(CssClass.History.MutedSmallText) {
                        +ViewText.SHOW_DRY_RUN_TRADES
                    }
                }
            }

            div(CssClass.Table.Wrapper) {
                table {
                    thead {
                        tr {
                            th { +ViewText.HEADER_TIME }
                            th { +ViewText.HEADER_PAIR }
                            th { +ViewText.HEADER_SIDE }
                            th { +ViewText.HEADER_VOLUME }
                            th { +ViewText.HEADER_USD_AMOUNT }
                            th { +ViewText.HEADER_PRICE }
                            th { +ViewText.HEADER_FEE }
                            th { +ViewText.HEADER_SLIPPAGE }
                            th { +ViewText.HEADER_STATUS }
                        }
                    }
                    tbody {
                        id = HtmlIds.TRADE_TABLE_BODY
                        tr {
                            td(CssClass.History.EmptyTableCell) {
                                colSpan = PrecisionConstants.TRADE_TABLE_COLSPAN.toString()
                                +ViewText.HISTORY_NO_DATA
                            }
                        }
                    }
                }
            }
        }
    }

    private fun DIV.renderSyncProgressBanner() {
        div(CssClass.Layout.GlassPanel + CssClass.History.SyncBanner) {
            id = HtmlIds.SYNC_PROGRESS_BANNER
            div(CssClass.History.SyncHeader) {
                span(CssClass.History.SyncTitle) {
                    div(CssClass.History.SyncSpinner) {}
                    +ViewText.SYNCHRONIZING_TRADE_HISTORY
                }
                span(CssClass.History.SyncText) {
                    id = HtmlIds.SYNC_PROGRESS_TEXT
                    +ViewText.INITIAL_SYNC_PROGRESS
                }
            }
            div(CssClass.History.ProgressTrack) {
                div(CssClass.History.ProgressBar) {
                    id = HtmlIds.SYNC_PROGRESS_BAR
                }
            }
        }
    }
}

private enum class HistoryChartSection(
    val canvasId: String,
    val title: String,
    val iconSvg: String,
    val caption: String? = null,
) {
    PORTFOLIO_VALUE(
        HtmlIds.PORTFOLIO_VALUE_CHART,
        ViewText.HISTORY_PORTFOLIO_VALUE,
        Icons.CHART,
    ),
    ASSET_HOLDINGS(
        HtmlIds.ASSET_HOLDINGS_CHART,
        ViewText.HISTORY_ASSET_HOLDINGS,
        Icons.CHART,
    ),
    ALLOCATION_DRIFT(
        HtmlIds.ALLOCATION_DRIFT_CHART,
        ViewText.HISTORY_ALLOCATION_DRIFT,
        Icons.CHART,
    ),
    CUMULATIVE_NET_CASH_FLOW(
        HtmlIds.CUMULATIVE_NET_CASH_FLOW_CHART,
        ViewText.HISTORY_NET_CASH_FLOW,
        Icons.WALLET,
        // HIST-2: legend caveat moved out of the chart legend into a caption.
        ViewText.NET_CASH_FLOW_CAPTION,
    ),
}

private enum class HistoryStatCardDefinition(
    val title: String,
    val iconSvg: String,
    val valueId: String,
    val titleId: String? = null,
) {
    ALL_TIME_HIGH(
        ViewText.HISTORY_ALL_TIME_HIGH,
        Icons.WALLET,
        HtmlIds.STAT_ATH,
        HtmlIds.STAT_ATH_TITLE,
    ),
    TOTAL_TRADES(
        ViewText.HISTORY_TOTAL_TRADES,
        Icons.CHART,
        HtmlIds.STAT_TOTAL_TRADES,
    ),
    TOTAL_VOLUME(
        ViewText.HISTORY_TOTAL_VOLUME,
        Icons.WALLET,
        HtmlIds.STAT_TOTAL_VOLUME,
    ),
    TOTAL_FEES(
        ViewText.HISTORY_TOTAL_FEES,
        Icons.DOLLAR_CIRCLE,
        HtmlIds.STAT_TOTAL_FEES,
    ),
    AVG_FEE_RATE(
        ViewText.HISTORY_AVG_FEE_RATE,
        Icons.DOLLAR_CIRCLE,
        HtmlIds.STAT_AVG_FEE_RATE,
    ),
    AVG_SLIPPAGE(
        ViewText.HISTORY_AVG_SLIPPAGE,
        Icons.CHART,
        HtmlIds.STAT_AVG_SLIPPAGE,
    ),
}
