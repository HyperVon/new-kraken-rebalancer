package com.gemini.krakenbot.view.component

import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.Icons
import com.gemini.krakenbot.view.util.Icons.icon
import com.gemini.krakenbot.view.util.Layouts.glassPanel
import com.gemini.krakenbot.view.util.ViewText
import com.gemini.krakenbot.view.util.div
import com.gemini.krakenbot.view.util.h3
import com.gemini.krakenbot.view.util.p
import com.gemini.krakenbot.view.util.span
import com.gemini.krakenbot.view.util.td
import com.gemini.krakenbot.view.util.tr
import kotlinx.html.*
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class RecentActivityComponent {

    private enum class TradeAction(val badgeClass: CssClass, val label: String) {
        BUY(CssClass.Badge.Buy, "BUY"),
        SELL(CssClass.Badge.Sell, "SELL"),
        INFO(CssClass.Badge.Info, "INFO");

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

    context(div: DIV)
    fun render(history: List<PortfolioSnapshot>) {
        div.glassPanel(ViewText.RECENT_ACTIVITY, Icons.PULSE) {
            if (history.isEmpty()) {
                div(CssClass.Activity.EmptyHistoryBox) {
                    icon(Icons.EMPTY_PIE)
                    h3 { +ViewText.RECENT_ACTIVITY }
                    p { +ViewText.NO_TRADING_HISTORY }
                }
            } else {
                div(classes = CssClass.Table.Wrapper + CssClass.Activity.CustomScrollbarMaxH100) {
                    table {
                        thead {
                            tr {
                                th { +ViewText.HEADER_TIME }
                                th { +ViewText.HEADER_ACTION }
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
        tr(CssClass.Table.Hoverable) {
            td(CssClass.Table.MonoCol) { +timeStr }
            td {
                span(CssClass.Activity.EmptyText) {
                    span(CssClass.Activity.DotMarker) {}
                    +ViewText.NO_TRADES_EXECUTED
                }
            }
        }
    }

    private fun TBODY.renderActionRow(timeStr: String, action: String) {
        val tradeAction = TradeAction.from(action)
        tr(CssClass.Table.Hoverable) {
            td(CssClass.Table.MonoCol) { +timeStr }
            td {
                div(CssClass.Activity.RowContainer) {
                    span(tradeAction.badgeClass) { +tradeAction.label }
                    span { +action }
                }
            }
        }
    }
}
