package com.gemini.krakenbot.view.component

import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.Icons
import com.gemini.krakenbot.view.util.Icons.icon
import com.gemini.krakenbot.view.util.Layouts.glassPanel
import com.gemini.krakenbot.view.util.ViewText
import kotlinx.html.*
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class RecentActivityComponent {

    private enum class TradeAction(val badgeClass: String, val label: String) {
        BUY(CssClass.Badge.Buy.value, "BUY"),
        SELL(CssClass.Badge.Sell.value, "SELL"),
        INFO(CssClass.Badge.Info.value, "INFO");

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
                div(classes = CssClass.Activity.EmptyHistoryBox.value) {
                    icon(Icons.EMPTY_PIE)
                    h3 { +ViewText.RECENT_ACTIVITY }
                    p { +ViewText.NO_TRADING_HISTORY }
                }
            } else {
                div(classes = "${CssClass.Table.Wrapper.value} ${CssClass.Activity.CustomScrollbarMaxH100.value}") {
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
        tr(classes = CssClass.Table.Hoverable.value) {
            td(classes = CssClass.Table.MonoCol.value) { +timeStr }
            td {
                span(classes = CssClass.Activity.EmptyText.value) {
                    span(classes = CssClass.Activity.DotMarker.value) {}
                    +ViewText.NO_TRADES_EXECUTED
                }
            }
        }
    }

    private fun TBODY.renderActionRow(timeStr: String, action: String) {
        val tradeAction = TradeAction.from(action)
        tr(classes = CssClass.Table.Hoverable.value) {
            td(classes = CssClass.Table.MonoCol.value) { +timeStr }
            td {
                div(classes = CssClass.Activity.RowContainer.value) {
                    span(classes = tradeAction.badgeClass) { +tradeAction.label }
                    span { +action }
                }
            }
        }
    }
}
