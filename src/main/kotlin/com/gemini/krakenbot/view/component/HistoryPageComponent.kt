package com.gemini.krakenbot.view.component

import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.Icons
import com.gemini.krakenbot.view.util.Icons.icon
import com.gemini.krakenbot.view.util.Routes
import com.gemini.krakenbot.view.util.ViewText
import kotlinx.html.*
import kotlinx.html.InputType.*


class HistoryPageComponent {

    context(html: HTML)
    fun render() {
        html.head {
            meta(charset = "utf-8")
            meta(
                name = "viewport",
                content = "width=device-width, initial-scale=1.0"
            )
            title("${ViewText.HISTORY_TITLE} - ${ViewText.APP_TITLE}")
            link(rel = "preconnect", href = "https://fonts.googleapis.com")
            link(rel = "preconnect", href = "https://fonts.gstatic.com") {
                attributes["crossorigin"] = ""
            }
            link(
                rel = "stylesheet",
                href = "https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700;800&family=Outfit:wght@400;500;600;700;800&family=Roboto+Mono:wght@400;500;700&display=swap"
            )
            link(rel = "stylesheet", href = Routes.STATIC_STYLE_CSS)
            script(src = "https://cdn.jsdelivr.net/npm/chart.js@4.4.7/dist/chart.umd.min.js") {}
            script(src = "https://cdn.jsdelivr.net/npm/chartjs-adapter-date-fns@3.0.0/dist/chartjs-adapter-date-fns.bundle.min.js") {}
        }
        html.body {
            div(CssClass.Layout.Container.value) {
                renderHeader()
                renderSyncProgressBanner()
                renderStatsGrid()
                renderTimeRangeSelector()
                renderChartSection(
                    "portfolio-value-chart",
                    ViewText.HISTORY_PORTFOLIO_VALUE,
                    Icons.CHART
                )
                renderChartSection(
                    "asset-holdings-chart",
                    ViewText.HISTORY_ASSET_HOLDINGS,
                    Icons.CHART
                )
                renderChartSection(
                    "allocation-drift-chart",
                    ViewText.HISTORY_ALLOCATION_DRIFT,
                    Icons.CHART
                )
                renderChartSection(
                    "cumulative-pl-chart",
                    ViewText.HISTORY_CUMULATIVE_PL,
                    Icons.WALLET
                )
                renderTradeTable()
            }
            script(src = Routes.STATIC_REBALANCER_JS) {}
        }
    }

    private fun DIV.renderHeader() {
        header {
            div(CssClass.Layout.HeaderTitleSection.value) {
                h1 { +ViewText.APP_TITLE }
            }
            nav(CssClass.Navigation.Bar.value) {
                a(href = Routes.ROOT, classes = CssClass.Navigation.Link.value) {
                    +ViewText.NAV_DASHBOARD
                }
                a(href = Routes.HISTORY, classes = CssClass.Navigation.LinkActive.value) {
                    +ViewText.NAV_HISTORY
                }
                a(href = Routes.SETTINGS, classes = CssClass.Navigation.Link.value) {
                    icon(Icons.COG)
                    +ViewText.NAV_SETTINGS
                }
            }
        }
    }

    private fun DIV.renderStatsGrid() {
        div(CssClass.History.StatsGrid.value) {
            id = "history-stats"
            renderStatCard(
                "stat-ath",
                ViewText.HISTORY_ALL_TIME_HIGH,
                Icons.WALLET,
                "--"
            )
            renderStatCard(
                "stat-total-trades",
                ViewText.HISTORY_TOTAL_TRADES,
                Icons.CHART,
                "--"
            )
            renderStatCard(
                "stat-total-volume",
                ViewText.HISTORY_TOTAL_VOLUME,
                Icons.WALLET,
                "--"
            )
            renderStatCard(
                "stat-total-fees",
                ViewText.HISTORY_TOTAL_FEES,
                Icons.DOLLAR_CIRCLE,
                "--"
            )
        }
    }

    private fun DIV.renderStatCard(
        cardId: String,
        title: String,
        iconSvg: String,
        defaultValue: String
    ) {
        div(CssClass.StatusCard.Default.value) {
            div(CssClass.StatusCard.Header.value) {
                span(CssClass.StatusCard.Title.value) { +title }
                div(CssClass.StatusCard.Icon.value) { icon(iconSvg) }
            }
            div(CssClass.StatusCard.Value.value) {
                id = cardId
                +defaultValue
            }
        }
    }

    private fun DIV.renderTimeRangeSelector() {
        div(CssClass.History.TimeRangeSelector.value) {
            button(classes = CssClass.History.TimeRangeBtn.value) {
                attributes["data-range"] = "24h"
                +"24h"
            }
            button(classes = CssClass.History.TimeRangeBtn.value) {
                attributes["data-range"] = "7d"
                +"7d"
            }
            button(classes = CssClass.History.TimeRangeBtnActive.value) {
                attributes["data-range"] = "30d"
                +"30d"
            }
            button(classes = CssClass.History.TimeRangeBtn.value) {
                attributes["data-range"] = "90d"
                +"90d"
            }
            button(classes = CssClass.History.TimeRangeBtn.value) {
                attributes["data-range"] = "all"
                +"All"
            }
        }
    }

    private fun DIV.renderChartSection(
        canvasId: String,
        title: String,
        iconSvg: String
    ) {
        div(CssClass.Layout.GlassPanel.value) {
            h2(CssClass.Utility.GlassPanelTitle.value) {
                icon(iconSvg)
                +title
            }
            div(CssClass.History.ChartContainer.value) {
                canvas {
                    id = canvasId
                }
            }
        }
    }

    private fun DIV.renderTradeTable() {
        div(CssClass.Layout.GlassPanel.value) {
            div {
                style = "display: flex; justify-content: space-between; align-items: center; margin-bottom: 1rem;"
                h2(CssClass.Utility.GlassPanelTitle.value) {
                    style = "margin-bottom: 0;"
                    icon(Icons.CHART)
                    +ViewText.HISTORY_TRADE_LOG
                }
                label(classes = CssClass.Form.CheckboxContainer.value) {
                    input(type = checkBox) {
                        id = "show-dry-run-checkbox"
                        checked = true
                    }
                    div(classes = CssClass.Form.CheckboxCustom.value) {}
                    span {
                        style = "font-size: 0.875rem; color: var(--color-text-muted);"
                        +"Show Dry Run Trades"
                    }
                }
            }

            div(CssClass.Table.Wrapper.value) {
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
                        id = "trade-table-body"
                        tr {
                            td {
                                colSpan = "6"
                                style = "text-align:center; color: var(--color-text-muted); padding: 2rem;"
                                +ViewText.HISTORY_NO_DATA
                            }
                        }
                    }
                }
            }
        }
    }

    private fun DIV.renderSyncProgressBanner() {
        div {
            id = "sync-progress-banner"
            style = "display: none; margin-bottom: 1.5rem; padding: 1.5rem;"
            classes = setOf(CssClass.Layout.GlassPanel.value)
            div {
                style = "display: flex; align-items: center; justify-content: space-between; margin-bottom: 0.75rem;"
                span {
                    style = "font-weight: 600; color: var(--color-text); display: flex; align-items: center; gap: 0.5rem;"
                    div {
                        style = "width: 1rem; height: 1rem; border: 2px solid var(--color-primary); border-top-color: transparent; border-radius: 50%; animation: spin 1s linear infinite;"
                    }
                    +"Synchronizing Kraken Trade History..."
                }
                span {
                    id = "sync-progress-text"
                    style = "font-family: var(--font-mono); font-size: 0.875rem; color: var(--color-text-muted);"
                    +"0 / 0 (0%)"
                }
            }
            div {
                style = "width: 100%; height: 0.5rem; background: rgba(255, 255, 255, 0.05); border-radius: 9999px; overflow: hidden;"
                div {
                    id = "sync-progress-bar"
                    style = "width: 0%; height: 100%; background: var(--color-primary); transition: width 0.3s ease; border-radius: 9999px;"
                }
            }
        }
    }
}
