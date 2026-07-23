package com.gemini.krakenbot.view.component

import com.gemini.krakenbot.model.TimeRange
import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.HtmlAttrs
import com.gemini.krakenbot.view.util.HtmlIds
import com.gemini.krakenbot.view.util.Icons
import com.gemini.krakenbot.view.util.Icons.icon
import com.gemini.krakenbot.view.util.glassPanel
import com.gemini.krakenbot.view.util.statusCard
import com.gemini.krakenbot.view.util.Routes
import com.gemini.krakenbot.view.util.ViewText
import com.gemini.krakenbot.view.util.a
import com.gemini.krakenbot.view.util.button
import com.gemini.krakenbot.view.util.commonMetadataAndStyles
import com.gemini.krakenbot.view.util.div
import com.gemini.krakenbot.view.util.h2
import com.gemini.krakenbot.view.util.nav
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
        }
        html.body {
            div(CssClass.Layout.Container) {
                renderHeader()
                renderSyncProgressBanner()
                renderTimeRangeSelector()
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
                h1 { +ViewText.APP_TITLE }
            }
            nav(CssClass.Navigation.Bar) {
                a(CssClass.Navigation.Link, href = Routes.ROOT) {
                    +ViewText.NAV_DASHBOARD
                }
                a(CssClass.Navigation.LinkActive, href = Routes.HISTORY) {
                    +ViewText.NAV_HISTORY
                }
                a(CssClass.Navigation.Link, href = Routes.SETTINGS) {
                    icon(Icons.COG)
                    +ViewText.NAV_SETTINGS
                }
            }
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

    private fun DIV.renderChartSection(chart: HistoryChartSection) {
        glassPanel(chart.title, chart.iconSvg) {
            div(CssClass.History.ChartContainer) {
                canvas {
                    id = chart.canvasId
                }
            }
        }
    }

    private fun DIV.renderTradeTable() {
        div(CssClass.Layout.GlassPanel) {
            div {
                style = STYLE_FLEX_BETWEEN_MB1
                h2(CssClass.Utility.GlassPanelTitle) {
                    style = STYLE_MB0
                    icon(Icons.CHART)
                    +ViewText.HISTORY_TRADE_LOG
                }
                label(classes = CssClass.Form.CheckboxContainer.value) {
                    input(type = checkBox) {
                        id = HtmlIds.SHOW_DRY_RUN_CHECKBOX
                        checked = true
                    }
                    div(CssClass.Form.CheckboxCustom) {}
                    span {
                        style = STYLE_MUTED_SMALL_TEXT
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
                            td {
                                colSpan = TABLE_COLSPAN
                                style = STYLE_EMPTY_TABLE_CELL
                                +ViewText.HISTORY_NO_DATA
                            }
                        }
                    }
                }
            }
        }
    }

    private fun DIV.renderSyncProgressBanner() {
        div(CssClass.Layout.GlassPanel) {
            id = HtmlIds.SYNC_PROGRESS_BANNER
            style = STYLE_SYNC_BANNER
            div {
                style = STYLE_SYNC_HEADER
                span {
                    style = STYLE_SYNC_TITLE
                    div {
                        style = STYLE_SYNC_SPINNER
                    }
                    +ViewText.SYNCHRONIZING_TRADE_HISTORY
                }
                span {
                    id = HtmlIds.SYNC_PROGRESS_TEXT
                    style = STYLE_SYNC_TEXT
                    +ViewText.INITIAL_SYNC_PROGRESS
                }
            }
            div {
                style = STYLE_PROGRESS_TRACK
                div {
                    id = HtmlIds.SYNC_PROGRESS_BAR
                    style = STYLE_PROGRESS_BAR
                }
            }
        }
    }

    private companion object {
        const val CDN_CHART_JS = "https://cdn.jsdelivr.net/npm/chart.js@4.4.7/dist/chart.umd.min.js"
        const val CDN_CHART_JS_DATE_FNS = "https://cdn.jsdelivr.net/npm/chartjs-adapter-date-fns@3.0.0/dist/chartjs-adapter-date-fns.bundle.min.js"
        const val TABLE_COLSPAN = "6"

        const val STYLE_FLEX_BETWEEN_MB1 = "display: flex; justify-content: space-between; align-items: center; margin-bottom: 1rem;"
        const val STYLE_MB0 = "margin-bottom: 0;"
        const val STYLE_MUTED_SMALL_TEXT = "font-size: 0.875rem; color: var(--color-text-muted);"
        const val STYLE_EMPTY_TABLE_CELL = "text-align:center; color: var(--color-text-muted); padding: 2rem;"
        const val STYLE_SYNC_BANNER = "display: none; margin-bottom: 1.5rem; padding: 1.5rem;"
        const val STYLE_SYNC_HEADER = "display: flex; align-items: center; justify-content: space-between; margin-bottom: 0.75rem;"
        const val STYLE_SYNC_TITLE = "font-weight: 600; color: var(--color-text); display: flex; align-items: center; gap: 0.5rem;"
        const val STYLE_SYNC_SPINNER = "width: 1rem; height: 1rem; border: 2px solid var(--color-primary); border-top-color: transparent; border-radius: 50%; animation: spin 1s linear infinite;"
        const val STYLE_SYNC_TEXT = "font-family: var(--font-mono); font-size: 0.875rem; color: var(--color-text-muted);"
        const val STYLE_PROGRESS_TRACK = "width: 100%; height: 0.5rem; background: rgba(255, 255, 255, 0.05); border-radius: 9999px; overflow: hidden;"
        const val STYLE_PROGRESS_BAR = "width: 0%; height: 100%; background: var(--color-primary); transition: width 0.3s ease; border-radius: 9999px;"
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
    object CumulativePL : HistoryChartSection(HtmlIds.CUMULATIVE_PL_CHART, ViewText.HISTORY_CUMULATIVE_PL, Icons.WALLET)

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
