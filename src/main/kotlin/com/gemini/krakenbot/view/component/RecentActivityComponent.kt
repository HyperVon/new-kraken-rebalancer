package com.gemini.krakenbot.view.component

import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.Icons
import com.gemini.krakenbot.view.util.Icons.icon
import com.gemini.krakenbot.view.util.ViewText
import com.gemini.krakenbot.view.util.div
import com.gemini.krakenbot.view.util.glassPanel
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
        INFO(CssClass.Badge.Info, "INFO"),
        ;

        companion object {
            fun from(action: String): TradeAction {
                val stripped =
                    action.uppercase()
                        .removePrefix("[DRY RUN] ")
                        .trim()
                return when {
                    stripped.startsWith("BUY") -> BUY
                    stripped.startsWith("SELL") -> SELL
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
                div(CssClass.Table.Wrapper + CssClass.Activity.CustomScrollbarMaxH100) {
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
                                            action,
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
        tr(CssClass.Table.Hoverable + CssClass.Activity.RowInfo) {
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
        val isTrade = tradeAction == TradeAction.BUY || tradeAction == TradeAction.SELL
        val rowClass =
            if (isTrade) {
                CssClass.Table.Hoverable + CssClass.Activity.RowTrade
            } else {
                CssClass.Table.Hoverable + CssClass.Activity.RowInfo
            }
        val messageClass =
            if (isTrade) CssClass.Activity.Message else CssClass.Activity.MessageMuted
        tr(rowClass) {
            td(CssClass.Table.MonoCol) { +timeStr }
            td {
                div(CssClass.Activity.RowContainer) {
                    span(tradeAction.badgeClass) { +tradeAction.label }
                    span(messageClass) { +action }
                }
            }
        }
    }
}
