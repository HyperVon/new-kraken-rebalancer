package com.gemini.krakenbot.view.component

import com.gemini.krakenbot.model.OrderSide
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.Icons
import com.gemini.krakenbot.view.util.Icons.icon
import com.gemini.krakenbot.view.util.Routes
import com.gemini.krakenbot.view.util.ViewText
import com.gemini.krakenbot.view.util.a
import com.gemini.krakenbot.view.util.div
import com.gemini.krakenbot.view.util.glassPanel
import com.gemini.krakenbot.view.util.h3
import com.gemini.krakenbot.view.util.p
import com.gemini.krakenbot.view.util.span
import kotlinx.html.DIV
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class RecentActivityComponent {

    private enum class TradeAction(val badgeClass: CssClass, val label: String) {
        BUY(CssClass.Badge.Buy, OrderSide.BUY.uppercaseName),
        SELL(CssClass.Badge.Sell, OrderSide.SELL.uppercaseName),
        INFO(CssClass.Badge.Info, "INFO"),
        ;

        companion object {
            fun from(action: String): TradeAction {
                val stripped =
                    action.uppercase()
                        .removePrefix(ViewText.DRY_RUN_PREFIX.uppercase())
                        .trim()
                return when {
                    stripped.startsWith(OrderSide.BUY.uppercaseName) -> BUY
                    stripped.startsWith(OrderSide.SELL.uppercaseName) -> SELL
                    else -> INFO
                }
            }
        }
    }

    private val cycleTimeFormatter =
        DateTimeFormatter.ofPattern("MMM d · hh:mm:ss a")
            .withLocale(Locale.US)
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
                val now = Instant.now()
                div(CssClass.Activity.Feed) {
                    history.take(MAX_CYCLES).forEach { snapshot ->
                        renderCycle(snapshot, now)
                    }
                }
                div(CssClass.Activity.FeedFooter) {
                    a(CssClass.Activity.ViewAll, href = Routes.HISTORY) {
                        +ViewText.ACTIVITY_VIEW_ALL
                    }
                }
            }
        }
    }

    private fun DIV.renderCycle(snapshot: PortfolioSnapshot, now: Instant) {
        val actionCount = snapshot.actions.size
        div(CssClass.Activity.Cycle) {
            div(CssClass.Activity.CycleHeader) {
                span(CssClass.Badge.Info) { +ViewText.ACTIVITY_CYCLE_PREFIX }
                span(CssClass.Activity.CycleMeta) {
                    +if (actionCount > 0) "$actionCount${tradeSuffix(actionCount)}" else ViewText.ACTIVITY_NO_TRADES
                }
                span(CssClass.Activity.CycleTime) {
                    +"${relativeTime(snapshot.timestamp, now)} · ${cycleTimeFormatter.format(snapshot.timestamp)}"
                }
            }
            if (snapshot.actions.isNotEmpty()) {
                div(CssClass.Activity.CycleBody) {
                    snapshot.actions.forEach { action ->
                        renderItem(action)
                    }
                }
            }
        }
    }

    private fun DIV.renderItem(action: String) {
        val tradeAction = TradeAction.from(action)
        val isTrade = tradeAction != TradeAction.INFO
        val itemClass = if (isTrade) CssClass.Activity.ItemTrade else CssClass.Activity.Item
        div(itemClass) {
            span(tradeAction.badgeClass) { +tradeAction.label }
            span(CssClass.Activity.ItemText) { +action }
        }
    }

    private companion object {
        const val MAX_CYCLES = 6

        fun tradeSuffix(count: Int): String =
            if (count == 1) ViewText.ACTIVITY_ACTION_SUFFIX else ViewText.ACTIVITY_ACTIONS_SUFFIX

        fun relativeTime(timestamp: Instant, now: Instant): String {
            val seconds = Duration.between(timestamp, now).seconds.coerceAtLeast(0)
            return when {
                seconds < 60 -> ViewText.ACTIVITY_JUST_NOW
                seconds < 3_600 -> "${seconds / 60}${ViewText.ACTIVITY_MINUTES_AGO_SUFFIX}"
                seconds < 86_400 -> "${seconds / 3_600}${ViewText.ACTIVITY_HOURS_AGO_SUFFIX}"
                else -> "${seconds / 86_400}${ViewText.ACTIVITY_DAYS_AGO_SUFFIX}"
            }
        }
    }
}
