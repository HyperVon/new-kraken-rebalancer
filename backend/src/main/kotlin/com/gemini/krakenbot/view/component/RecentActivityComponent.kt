package com.gemini.krakenbot.view.component

import com.gemini.krakenbot.model.OrderSide
import com.gemini.krakenbot.service.CurrentCycleActivity
import com.gemini.krakenbot.service.CurrentCycleActivityMode
import com.gemini.krakenbot.service.CurrentCycleOutcome
import com.gemini.krakenbot.service.CurrentPortfolioState
import com.gemini.krakenbot.util.ActionLogFormatter
import com.gemini.krakenbot.view.util.ActionLogFormat
import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.Icons
import com.gemini.krakenbot.view.util.Icons.icon
import com.gemini.krakenbot.view.util.RecentActivityText
import com.gemini.krakenbot.view.util.ViewText
import com.gemini.krakenbot.view.util.div
import com.gemini.krakenbot.view.util.glassPanel
import com.gemini.krakenbot.view.util.p
import com.gemini.krakenbot.view.util.span
import kotlinx.html.ButtonType
import kotlinx.html.DIV
import kotlinx.html.button
import kotlinx.html.details
import kotlinx.html.id
import kotlinx.html.summary
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
                        .removePrefix(ActionLogFormat.DRY_RUN_PREFIX.uppercase())
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
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withLocale(Locale.US)
            .withZone(ZoneId.systemDefault())

    context(div: DIV)
    fun render(activities: List<CurrentCycleActivity>, sourceId: String) {
        div.glassPanel(ViewText.RECENT_ACTIVITY, Icons.PULSE) {
            div(CssClass.Activity.Feed) {
                id = RecentActivityText.FEED_ID
                attributes[RecentActivityText.SOURCE_ID_ATTRIBUTE] = sourceId
                attributes[RecentActivityText.FILTER_ATTRIBUTE] = RecentActivityText.FILTER_VALUE_ALL
                attributes[RecentActivityText.VISIBLE_COUNT_ATTRIBUTE] = RecentActivityText.PAGE_SIZE
                div(CssClass.Activity.FeedFooter) {
                    renderFilterButton(RecentActivityText.FILTER_VALUE_ALL, RecentActivityText.FILTER_ALL, true)
                    renderFilterButton(RecentActivityText.FILTER_VALUE_ORDERS, RecentActivityText.FILTER_ORDERS)
                    renderFilterButton(RecentActivityText.FILTER_VALUE_PROBLEMS, RecentActivityText.FILTER_PROBLEMS)
                }
                div {
                    id = RecentActivityText.GROUPS_ID
                    val visibleItems = activities.take(CurrentPortfolioState.MAX_RECENT_ACTIVITIES)
                    visibleItems.forEachIndexed { index, activity ->
                        renderCycle(activity, hidden = index >= RecentActivityText.PAGE_SIZE.toInt())
                    }
                }
                div(CssClass.Activity.FeedFooter) {
                    button(type = ButtonType.button, classes = CssClass.Button.Secondary.value) {
                        id = RecentActivityText.SHOW_OLDER_ID
                        if (activities.size <= RecentActivityText.PAGE_SIZE.toInt()) {
                            attributes["hidden"] = "hidden"
                        }
                        +RecentActivityText.SHOW_OLDER
                    }
                }
            }
        }
    }

    private fun DIV.renderFilterButton(value: String, label: String, selected: Boolean = false) {
        button(
            type = ButtonType.button,
            classes = if (selected) CssClass.Button.Primary.value else CssClass.Button.Secondary.value,
        ) {
            attributes[RecentActivityText.FILTER_ATTRIBUTE] = value
            +label
        }
    }

    private fun DIV.renderCycle(activity: CurrentCycleActivity, hidden: Boolean) {
        val outcome = activity.outcome.toLabel()
        val orders = activity.actions.any { TradeAction.from(it) != TradeAction.INFO } ||
            activity.outcome in setOf(CurrentCycleOutcome.DRY_RUN_PLAN, CurrentCycleOutcome.ORDERS_ATTEMPTED)
        val problem = activity.outcome == CurrentCycleOutcome.PROBLEM || activity.actions.any(::isProblemAction)
        val actions = activity.actions
        details(CssClass.Activity.Cycle.value) {
            if (hidden) attributes["hidden"] = "hidden"
            attributes[RecentActivityText.CYCLE_ID_ATTRIBUTE] = activity.cycleId
            attributes[RecentActivityText.OCCURRED_AT_ATTRIBUTE] = activity.occurredAt.toEpochMilli().toString()
            attributes[RecentActivityText.MODE_ATTRIBUTE] = activity.mode.name
            attributes[RecentActivityText.OUTCOME_ATTRIBUTE] = activity.outcome?.name ?: "RECORDED"
            attributes[RecentActivityText.PROBLEM_ATTRIBUTE] = problem.toString()
            attributes[RecentActivityText.ORDERS_ATTRIBUTE] = orders.toString()
            summary(CssClass.Activity.CycleHeader.value) {
                span(CssClass.Activity.CycleTime) {
                    +cycleTimeFormatter.format(activity.occurredAt)
                }
                span(CssClass.Activity.CycleMeta) { +outcome }
                val actionCount = activity.actions.size
                span(CssClass.Activity.CycleMeta) {
                    attributes[RecentActivityText.ACTION_COUNT_ATTRIBUTE] = actionCount.toString()
                    val actionLabel = if (actionCount == 1) {
                        RecentActivityText.ACTION_COUNT_ONE
                    } else {
                        RecentActivityText.ACTION_COUNT_MANY
                    }
                    +"$actionCount $actionLabel"
                }
            }
            div(CssClass.Activity.CycleBody) {
                div(CssClass.Activity.CycleIdentity) {
                    +"${ViewText.ACTIVITY_CYCLE_PREFIX} ${activity.cycleId}"
                }
                div(CssClass.Activity.CycleIdentity) {
                    +activity.mode.label()
                }
                actions.forEach { action ->
                    renderItem(action)
                }
            }
        }
    }

    private fun DIV.renderItem(action: String) {
        val tradeAction = TradeAction.from(action)
        val isTrade = tradeAction != TradeAction.INFO
        val itemClass = if (isTrade) CssClass.Activity.ItemTrade else CssClass.Activity.Item
        div(itemClass) {
            attributes[RecentActivityText.ACTION_ATTRIBUTE] = action
            if (isTrade) {
                span(tradeAction.badgeClass) { +tradeAction.label }
            }
            span(CssClass.Activity.ItemText) {
                +when {
                    tradeAction == TradeAction.INFO -> ActionLogFormatter.renderInfoAction(action)
                    else -> ActionLogFormatter.renderTradeAction(action)
                }
            }
        }
    }

    private companion object {
        fun CurrentCycleOutcome?.toLabel(): String = when (this) {
            CurrentCycleOutcome.NO_TRADE -> RecentActivityText.OUTCOME_NO_TRADE
            CurrentCycleOutcome.DRY_RUN_PLAN -> RecentActivityText.OUTCOME_DRY_RUN_PLAN
            CurrentCycleOutcome.ORDERS_ATTEMPTED -> RecentActivityText.OUTCOME_ORDERS_ATTEMPTED
            CurrentCycleOutcome.PROBLEM -> RecentActivityText.OUTCOME_PROBLEM
            null -> RecentActivityText.OUTCOME_RECORDED
        }

        fun CurrentCycleActivityMode.label(): String = when (this) {
            CurrentCycleActivityMode.SIMULATION -> ViewText.MODE_SIMULATION
            CurrentCycleActivityMode.DRY_RUN -> ViewText.MODE_DRY_RUN
            CurrentCycleActivityMode.LIVE -> ViewText.MODE_LIVE
        }

        fun isProblemAction(action: String): Boolean {
            val normalized = action.uppercase(Locale.ROOT).trimStart()
            return normalized.startsWith("ERROR") || normalized.startsWith("FAILED") ||
                normalized.startsWith("WARNING")
        }
    }
}
