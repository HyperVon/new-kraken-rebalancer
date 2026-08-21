package com.gemini.krakenbot.view.component

import com.gemini.krakenbot.model.OrderSide
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.Formatter
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
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class RecentActivityComponent {

    private enum class TradeAction(val badgeClass: CssClass, val label: String) {
        BUY(CssClass.Badge.Buy, OrderSide.BUY.uppercaseName),
        SELL(CssClass.Badge.Sell, OrderSide.SELL.uppercaseName),
        INFO(CssClass.Badge.Info, ViewText.ACTIVITY_INFO),
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
        DateTimeFormatter.ofPattern("MMM d · h:mm a")
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
        val tradeActions = snapshot.actions.filter { TradeAction.from(it) != TradeAction.INFO }
        val infoAction = snapshot.actions.firstOrNull { TradeAction.from(it) == TradeAction.INFO }
        div(CssClass.Activity.Cycle) {
            div(CssClass.Activity.CycleHeader) {
                span(CssClass.Activity.CycleMeta) {
                    +if (tradeActions.isNotEmpty()) {
                        "${tradeActions.size}${tradeSuffix(tradeActions.size)}"
                    } else {
                        ViewText.ACTIVITY_NO_TRADES
                    }
                }
                span(CssClass.Activity.CycleTime) {
                    +"${relativeTime(snapshot.timestamp, now)} · ${cycleTimeFormatter.format(snapshot.timestamp)}"
                }
            }
            val displayedActions = listOfNotNull(infoAction) + tradeActions
            if (displayedActions.isNotEmpty()) {
                div(CssClass.Activity.CycleBody) {
                    displayedActions.forEach { action ->
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
            if (isTrade) {
                span(tradeAction.badgeClass) { +tradeAction.label }
            }
            span(CssClass.Activity.ItemText) {
                +when {
                    tradeAction == TradeAction.INFO -> humanizeInfoAction(action)
                    else -> humanizeTradeAction(action)
                }
            }
        }
    }

    private companion object {
        const val MAX_CYCLES = 6

        fun tradeSuffix(count: Int): String =
            if (count == 1) ViewText.ACTIVITY_ACTION_SUFFIX else ViewText.ACTIVITY_ACTIONS_SUFFIX

        fun humanizeInfoAction(action: String): String {
            val normalized = action.removePrefix(ViewText.DRY_RUN_PREFIX)
            return when {
                normalized.startsWith(ViewText.ACTION_DEVIATION_PREFIX) ->
                    normalized.removePrefix(ViewText.ACTION_DEVIATION_PREFIX) + " drift detected"

                normalized == ViewText.ACTION_FIAT_CORRECTION_ENFORCED ->
                    "Cash drift detected; applying correction"

                normalized.startsWith(ViewText.ACTION_DISTRIBUTING_FIAT_PREFIX) ->
                    "Cash correction distributed across underweight assets"

                else -> normalized
            }
        }

        fun humanizeTradeAction(action: String): String {
            val dryRun = action.startsWith(ViewText.DRY_RUN_PREFIX)
            val normalized = action.removePrefix(ViewText.DRY_RUN_PREFIX)
            val parts = normalized.split(' ')
            if (parts.size < 6 || parts[0] !in setOf(OrderSide.BUY.uppercaseName, OrderSide.SELL.uppercaseName)) {
                return action
            }
            val side = parts[0]
            val symbol = parts[1]
            val volume = parts[3]
            val amount = parts[5]
            val amountLabels = setOf("${ViewText.ACTION_VALUE_LABEL}:", "${ViewText.ACTION_COST_LABEL}:")
            if (parts[2] != "${ViewText.ACTION_VOLUME_LABEL}:" || parts[4] !in amountLabels) return action
            return try {
                val quantity = BigDecimal(volume).stripTrailingZeros().toPlainString()
                val currency = Formatter.formatCurrency(BigDecimal(amount.removePrefix("$")))
                buildString {
                    append(side)
                    append(' ')
                    append(symbol)
                    append(" · ")
                    append(quantity)
                    append(" · $")
                    append(currency)
                    if (dryRun) append(" · DRY RUN")
                }
            } catch (_: NumberFormatException) {
                action
            }
        }

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
