package com.gemini.krakenbot.view.component

import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.view.util.CssClasses.BTN_SECONDARY
import com.gemini.krakenbot.view.util.CssClasses.DATA_AGE_CONTAINER
import com.gemini.krakenbot.view.util.CssClasses.DATA_AGE_LABEL
import com.gemini.krakenbot.view.util.CssClasses.DATA_AGE_TIME
import com.gemini.krakenbot.view.util.CssClasses.DATA_AGE_VALUE
import com.gemini.krakenbot.view.util.CssClasses.DATA_AGE_VALUE_STALE
import com.gemini.krakenbot.view.util.CssClasses.DETAIL_GRID
import com.gemini.krakenbot.view.util.CssClasses.HEADER_ACTIONS
import com.gemini.krakenbot.view.util.CssClasses.HEADER_TITLE_SECTION
import com.gemini.krakenbot.view.util.CssClasses.STATUS_BADGE_DELAYED
import com.gemini.krakenbot.view.util.CssClasses.STATUS_BADGE_LIVE
import com.gemini.krakenbot.view.util.HtmlAttrs.DATA_EPOCH
import com.gemini.krakenbot.view.util.Icons.COG
import com.gemini.krakenbot.view.util.Icons.icon
import com.gemini.krakenbot.view.util.Routes.SETTINGS
import com.gemini.krakenbot.view.util.ViewText.APP_TITLE
import com.gemini.krakenbot.view.util.ViewText.DATA_AGE
import com.gemini.krakenbot.view.util.ViewText.DELAYED
import com.gemini.krakenbot.view.util.ViewText.LIVE
import com.gemini.krakenbot.view.util.ViewText.SETTINGS_TITLE
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
    private val timeFormatter =
        DateTimeFormatter.ofPattern("hh:mm:ss a").withZone(ZoneId.systemDefault())

    context(div: DIV)
    fun render(
        latest: PortfolioSnapshot,
        history: List<PortfolioSnapshot>
    ) {
        val timeSinceUpdate =
            0L.coerceAtLeast(
                Instant.now().epochSecond - latest.timestamp.epochSecond
            )
        val isStale = timeSinceUpdate > 90

        renderHeaderSection(latest, timeSinceUpdate, isStale)
        overviewGridComponent.render(latest)

        div.div(DETAIL_GRID) {
            allocationChartComponent.render(latest)
            performanceTableComponent.render(latest)
        }

        recentActivityComponent.render(history)
    }

    context(div: DIV)
    private fun renderHeaderSection(
        latest: PortfolioSnapshot,
        timeSinceUpdate: Long,
        isStale: Boolean
    ) {
        div.header {
            div(HEADER_TITLE_SECTION) {
                h1 { +APP_TITLE }
                val badgeClass =
                    if (isStale) STATUS_BADGE_DELAYED else STATUS_BADGE_LIVE
                val badgeText = if (isStale) DELAYED else LIVE
                div(badgeClass) { +badgeText }
            }

            div(HEADER_ACTIONS) {
                div(DATA_AGE_CONTAINER) {
                    div(DATA_AGE_LABEL) { +DATA_AGE }
                    val ageClass =
                        if (isStale) DATA_AGE_VALUE_STALE else DATA_AGE_VALUE
                    div(ageClass) { +"${timeSinceUpdate}s ago" }
                    div(DATA_AGE_TIME) {
                        attributes[DATA_EPOCH] =
                            latest.timestamp.toEpochMilli().toString()
                        +timeFormatter.format(latest.timestamp)
                    }
                }
                a(href = SETTINGS, classes = BTN_SECONDARY) {
                    icon(COG)
                    span { +SETTINGS_TITLE }
                }
            }
        }
    }
}
