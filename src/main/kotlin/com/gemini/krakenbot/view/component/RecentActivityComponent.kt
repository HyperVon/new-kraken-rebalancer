package com.gemini.krakenbot.view.component

import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.view.util.CssClasses
import com.gemini.krakenbot.view.util.Icons
import com.gemini.krakenbot.view.util.Icons.icon
import com.gemini.krakenbot.view.util.Layouts.glassPanel
import com.gemini.krakenbot.view.util.ViewText
import kotlinx.html.*
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class RecentActivityComponent {

    private enum class TradeAction(val badgeClass: String, val label: String) {
        BUY("badge badge-buy", "BUY"),
        SELL("badge badge-sell", "SELL"),
        INFO("badge badge-info", "INFO");

        companion object {
            fun from(action: String): TradeAction {
                val upper = action.uppercase()
                return when {
                    upper.startsWith("BUY") -> BUY
                    upper.startsWith("SELL") -> SELL
                    else -> INFO
                }
            }
        }
    }

    private val activityTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd hh:mm:ss a").withZone(ZoneId.systemDefault())

    fun DIV.render(history: List<PortfolioSnapshot>) {
        glassPanel(ViewText.RECENT_ACTIVITY, Icons.PULSE) {
            if (history.isEmpty()) {
                div("empty-history-box") {
                    icon(Icons.EMPTY_PIE)
                    h3 { +ViewText.RECENT_ACTIVITY }
                    p { +ViewText.NO_TRADING_HISTORY }
                }
            } else {
                div("${CssClasses.TABLE_WRAPPER} custom-scrollbar max-h-100") {
                    table {
                        thead {
                            tr {
                                th { +ViewText.HEADER_TIME }
                                th { +ViewText.HEADER_ACTION }
                            }
                        }
                        tbody {
                            history.forEach { snapshot ->
                                val timeStr = activityTimeFormatter.format(snapshot.timestamp)
                                if (snapshot.actions.isEmpty()) {
                                    renderEmptyActionsRow(timeStr)
                                } else {
                                    snapshot.actions.forEach { action -> renderActionRow(timeStr, action) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun TBODY.renderEmptyActionsRow(timeStr: String) {
        tr(CssClasses.HOVERABLE) {
            td(CssClasses.MONO_COL) { +timeStr }
            td {
                span {
                    style = "color: var(--color-text-muted); font-style: italic; display: flex; align-items: center; gap: 0.5rem;"
                    span {
                        style = "width: 0.375rem; height: 0.375rem; border-radius: 50%; background-color: var(--color-text-muted);"
                    }
                    +ViewText.NO_TRADES_EXECUTED
                }
            }
        }
    }

    private fun TBODY.renderActionRow(timeStr: String, action: String) {
        val tradeAction = TradeAction.from(action)
        tr(CssClasses.HOVERABLE) {
            td(CssClasses.MONO_COL) { +timeStr }
            td {
                div {
                    style = "display: flex; align-items: center; gap: 0.75rem;"
                    span(tradeAction.badgeClass) { +tradeAction.label }
                    span { +action }
                }
            }
        }
    }
}
