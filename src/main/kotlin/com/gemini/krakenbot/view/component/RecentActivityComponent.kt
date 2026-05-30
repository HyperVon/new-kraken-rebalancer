package com.gemini.krakenbot.view.component

import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.view.util.CssClasses
import com.gemini.krakenbot.view.util.CssClasses.CUSTOM_SCROLLBAR_MAX_H_100
import com.gemini.krakenbot.view.util.CssClasses.EMPTY_HISTORY_BOX
import com.gemini.krakenbot.view.util.CssClasses.HOVERABLE
import com.gemini.krakenbot.view.util.CssClasses.MONO_COL
import com.gemini.krakenbot.view.util.CssClasses.RECENT_ACTIVITY_DOT_MARKER
import com.gemini.krakenbot.view.util.CssClasses.RECENT_ACTIVITY_EMPTY_TEXT
import com.gemini.krakenbot.view.util.CssClasses.RECENT_ACTIVITY_ROW_CONTAINER
import com.gemini.krakenbot.view.util.CssClasses.TABLE_WRAPPER
import com.gemini.krakenbot.view.util.Icons.EMPTY_PIE
import com.gemini.krakenbot.view.util.Icons.PULSE
import com.gemini.krakenbot.view.util.Icons.icon
import com.gemini.krakenbot.view.util.Layouts.glassPanel
import com.gemini.krakenbot.view.util.ViewText.HEADER_ACTION
import com.gemini.krakenbot.view.util.ViewText.HEADER_TIME
import com.gemini.krakenbot.view.util.ViewText.NO_TRADES_EXECUTED
import com.gemini.krakenbot.view.util.ViewText.NO_TRADING_HISTORY
import com.gemini.krakenbot.view.util.ViewText.RECENT_ACTIVITY
import kotlinx.html.*
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class RecentActivityComponent {

    private enum class TradeAction(val badgeClass: String, val label: String) {
        BUY(CssClasses.BADGE_BUY, "BUY"),
        SELL(CssClasses.BADGE_SELL, "SELL"),
        INFO(CssClasses.BADGE_INFO, "INFO");

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

    private val activityTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd hh:mm:ss a")
            .withZone(ZoneId.systemDefault())

    fun DIV.render(history: List<PortfolioSnapshot>) {
        glassPanel(RECENT_ACTIVITY, PULSE) {
            if (history.isEmpty()) {
                div(EMPTY_HISTORY_BOX) {
                    icon(EMPTY_PIE)
                    h3 { +RECENT_ACTIVITY }
                    p { +NO_TRADING_HISTORY }
                }
            } else {
                div("$TABLE_WRAPPER $CUSTOM_SCROLLBAR_MAX_H_100") {
                    table {
                        thead {
                            tr {
                                th { +HEADER_TIME }
                                th { +HEADER_ACTION }
                            }
                        }
                        tbody {
                            history.forEach { snapshot ->
                                val timeStr =
                                    activityTimeFormatter.format(snapshot.timestamp)
                                if (snapshot.actions.isEmpty()) {
                                    renderEmptyActionsRow(timeStr)
                                } else {
                                    snapshot.actions.forEach { action ->
                                        renderActionRow(
                                            timeStr,
                                            action
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun TBODY.renderEmptyActionsRow(timeStr: String) {
        tr(HOVERABLE) {
            td(MONO_COL) { +timeStr }
            td {
                span(RECENT_ACTIVITY_EMPTY_TEXT) {
                    span(RECENT_ACTIVITY_DOT_MARKER) {}
                    +NO_TRADES_EXECUTED
                }
            }
        }
    }

    private fun TBODY.renderActionRow(timeStr: String, action: String) {
        val tradeAction = TradeAction.from(action)
        tr(HOVERABLE) {
            td(MONO_COL) { +timeStr }
            td {
                div(RECENT_ACTIVITY_ROW_CONTAINER) {
                    span(tradeAction.badgeClass) { +tradeAction.label }
                    span { +action }
                }
            }
        }
    }
}
