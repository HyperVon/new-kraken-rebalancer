package com.gemini.krakenbot.view.component

import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.view.util.CssClasses
import com.gemini.krakenbot.view.util.HtmlAttrs
import com.gemini.krakenbot.view.util.Icons
import com.gemini.krakenbot.view.util.Icons.icon
import com.gemini.krakenbot.view.util.Routes
import com.gemini.krakenbot.view.util.ViewText
import kotlinx.html.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class DashboardFragmentComponent(
    private val overviewGridComponent: OverviewGridComponent,
    private val allocationChartComponent: AllocationChartComponent,
    private val performanceTableComponent: PerformanceTableComponent,
    private val recentActivityComponent: RecentActivityComponent
) {
    private val timeFormatter = DateTimeFormatter.ofPattern("hh:mm:ss a").withZone(ZoneId.systemDefault())

    fun DIV.render(latest: PortfolioSnapshot, history: List<PortfolioSnapshot>) {
        val timeSinceUpdate = 0L.coerceAtLeast(Instant.now().epochSecond - latest.timestamp.epochSecond)
        val isStale = timeSinceUpdate > 90

        renderHeaderSection(latest, timeSinceUpdate, isStale)
        with(overviewGridComponent) { render(latest) }

        div(CssClasses.DETAIL_GRID) {
            with(allocationChartComponent) { render(latest) }
            with(performanceTableComponent) { render(latest) }
        }

        with(recentActivityComponent) { render(history) }
    }

    private fun DIV.renderHeaderSection(latest: PortfolioSnapshot, timeSinceUpdate: Long, isStale: Boolean) {
        header {
            div(CssClasses.HEADER_TITLE_SECTION) {
                h1 { +ViewText.APP_TITLE }
                val badgeClass = if (isStale) CssClasses.STATUS_BADGE_DELAYED else CssClasses.STATUS_BADGE_LIVE
                val badgeText = if (isStale) ViewText.DELAYED else ViewText.LIVE
                div(badgeClass) { +badgeText }
            }

            div(CssClasses.HEADER_ACTIONS) {
                div(CssClasses.DATA_AGE_CONTAINER) {
                    div(CssClasses.DATA_AGE_LABEL) { +ViewText.DATA_AGE }
                    val ageClass = if (isStale) CssClasses.DATA_AGE_VALUE_STALE else CssClasses.DATA_AGE_VALUE
                    div(ageClass) { +"${timeSinceUpdate}s ago" }
                    div(CssClasses.DATA_AGE_TIME) {
                        attributes[HtmlAttrs.DATA_EPOCH] = latest.timestamp.toEpochMilli().toString()
                        +timeFormatter.format(latest.timestamp)
                    }
                }
                a(href = Routes.SETTINGS, classes = CssClasses.BTN_SECONDARY) {
                    icon(Icons.COG)
                    span { +ViewText.SETTINGS_TITLE }
                }
            }
        }
    }
}
