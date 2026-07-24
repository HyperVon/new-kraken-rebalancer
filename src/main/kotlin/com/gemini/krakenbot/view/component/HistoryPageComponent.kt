package com.gemini.krakenbot.view.component

import com.gemini.krakenbot.model.TimeRange
import com.gemini.krakenbot.util.PrecisionConstants
import com.gemini.krakenbot.view.util.ActiveNav
import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.HtmlAttrs
import com.gemini.krakenbot.view.util.HtmlIds
import com.gemini.krakenbot.view.util.Icons
import com.gemini.krakenbot.view.util.Icons.icon
import com.gemini.krakenbot.view.util.ZoomActions
import com.gemini.krakenbot.view.util.brandMark
import com.gemini.krakenbot.view.util.glassPanel
import com.gemini.krakenbot.view.util.primaryNav
import com.gemini.krakenbot.view.util.statusCard
import com.gemini.krakenbot.view.util.Routes
import com.gemini.krakenbot.view.util.ViewText
import com.gemini.krakenbot.view.util.button
import com.gemini.krakenbot.view.util.commonMetadataAndStyles
import com.gemini.krakenbot.view.util.div
import com.gemini.krakenbot.view.util.h2
import com.gemini.krakenbot.view.util.label
import com.gemini.krakenbot.view.util.span
import com.gemini.krakenbot.view.util.td
import kotlinx.html.*
import kotlinx.html.InputType.checkBox

class HistoryPageComponent {

    context(html: HTML)
    fun render() {
        html.head {
            commonMetadataAndStyles()
            title("${ViewText.HISTORY_TITLE} - ${ViewText.APP_TITLE}")
            script(src = CDN_CHART_JS) {}
            script(src = CDN_CHART_JS_DATE_FNS) {}
            script(src = CDN_HAMMER_JS) {}
            script(src = CDN_CHART_JS_ZOOM) {}
        }
        html.body {
            div(CssClass.Layout.Container) {
                renderHeader()
                renderSyncProgressBanner()
                renderToolbar()
                renderStatsGrid()
                HistoryChartSection.ALL.forEach { chart ->
                    renderChartSection(chart)
                }
                renderTradeTable()
            }
            script(src = "${Routes.STATIC_REBALANCER_JS}?v=${System.currentTimeMillis()}") {}
        }
    }

    private fun DIV.renderHeader() {
        header {
            div(CssClass.Layout.HeaderTitleSection) {
                brandMark()
            }
            primaryNav(ActiveNav.HISTORY)
        }
    }

    private fun DIV.renderStatsGrid() {
        div(CssClass.History.StatsGrid) {
            id = HtmlIds.HISTORY_STATS
            HistoryStatCardDefinition.ALL.forEach { card ->
                statusCard(
                    title = card.title,
                    iconSvg = card.iconSvg,
                    value = ViewText.PLACEHOLDER_DASHES,
                    valueId = card.valueId,
                    titleId = card.titleId
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
                button(CssClass.History.ViewsBtn) {
                    id = HtmlIds.HISTORY_DELETE_VIEW_BTN
                    type = ButtonType.button
                    +ViewText.HISTORY_DELETE_VIEW
                }
            }
        }
    }

    private fun DIV.renderChartSection(chart: HistoryChartSection) {
        glassPanel(chart.title, chart.iconSvg) {
            div(CssClass.History.ChartTools) {
                zoomButton(chart.canvasId, ZoomActions.OUT, ViewText.HISTORY_ZOOM_OUT)
                zoomButton(chart.canvasId, ZoomActions.IN, ViewText.HISTORY_ZOOM_IN)
                zoomButton(chart.canvasId, ZoomActions.RESET, ViewText.HISTORY_ZOOM_RESET)
            }
            div(CssClass.History.ChartContainer) {
                canvas {
                    id = chart.canvasId
                }
            }
            div(CssClass.History.ChartScrubber) {
                input(classes = CssClass.History.ChartScrubberInput.value, type = InputType.range) {
                    min = "0"
                    max = "100"
                    step = "0.1"
                    value = "0"
                    disabled = true
                    attributes[HtmlAttrs.DATA_CHART_ID] = chart.canvasId
                    attributes[HtmlAttrs.ARIA_LABEL] = "${ViewText.HISTORY_PAN_CHART}: ${chart.title}"
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
        div(CssClass.Layout.GlassPanel) {
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

    private companion object {
        const val CDN_CHART_JS = "https://cdn.jsdelivr.net/npm/chart.js@4.4.7/dist/chart.umd.min.js"
        const val CDN_CHART_JS_DATE_FNS =
            "https://cdn.jsdelivr.net/npm/chartjs-adapter-date-fns@3.0.0/dist/chartjs-adapter-date-fns.bundle.min.js"
        const val CDN_HAMMER_JS = "https://cdn.jsdelivr.net/npm/hammerjs@2.0.8/hammer.min.js"
        const val CDN_CHART_JS_ZOOM =
            "https://cdn.jsdelivr.net/npm/chartjs-plugin-zoom@2.2.0/dist/chartjs-plugin-zoom.min.js"
    }
}

private sealed class HistoryChartSection(
    val canvasId: String,
    val title: String,
    val iconSvg: String
) {
    object PortfolioValue : HistoryChartSection(HtmlIds.PORTFOLIO_VALUE_CHART, ViewText.HISTORY_PORTFOLIO_VALUE, Icons.CHART)
    object AssetHoldings : HistoryChartSection(HtmlIds.ASSET_HOLDINGS_CHART, ViewText.HISTORY_ASSET_HOLDINGS, Icons.CHART)
    object AllocationDrift : HistoryChartSection(HtmlIds.ALLOCATION_DRIFT_CHART, ViewText.HISTORY_ALLOCATION_DRIFT, Icons.CHART)
    object CumulativePL : HistoryChartSection(HtmlIds.CUMULATIVE_PL_CHART, ViewText.HISTORY_NET_CASH_FLOW, Icons.WALLET)

    companion object {
        val ALL = listOf(PortfolioValue, AssetHoldings, AllocationDrift, CumulativePL)
    }
}

private sealed class HistoryStatCardDefinition(
    val title: String,
    val iconSvg: String,
    val valueId: String,
    val titleId: String? = null
) {
    object AllTimeHigh : HistoryStatCardDefinition(ViewText.HISTORY_ALL_TIME_HIGH, Icons.WALLET, HtmlIds.STAT_ATH, HtmlIds.STAT_ATH_TITLE)
    object TotalTrades : HistoryStatCardDefinition(ViewText.HISTORY_TOTAL_TRADES, Icons.CHART, HtmlIds.STAT_TOTAL_TRADES)
    object TotalVolume : HistoryStatCardDefinition(ViewText.HISTORY_TOTAL_VOLUME, Icons.WALLET, HtmlIds.STAT_TOTAL_VOLUME)
    object TotalFees : HistoryStatCardDefinition(ViewText.HISTORY_TOTAL_FEES, Icons.DOLLAR_CIRCLE, HtmlIds.STAT_TOTAL_FEES)

    companion object {
        val ALL = listOf(AllTimeHigh, TotalTrades, TotalVolume, TotalFees)
    }
}
