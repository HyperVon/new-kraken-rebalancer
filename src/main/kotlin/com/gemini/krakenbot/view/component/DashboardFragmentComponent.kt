package com.gemini.krakenbot.view.component

import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.HtmlAttrs
import com.gemini.krakenbot.view.util.Icons
import com.gemini.krakenbot.view.util.Icons.icon
import com.gemini.krakenbot.view.util.Routes
import com.gemini.krakenbot.view.util.ViewText
import com.gemini.krakenbot.view.util.a
import com.gemini.krakenbot.view.util.div
import com.gemini.krakenbot.util.PrecisionConstants
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
        DateTimeFormatter.ofPattern("hh:mm:ss a")
            .withZone(ZoneId.systemDefault())

    context(div: DIV)
    fun render(
        latest: PortfolioSnapshot,
        history: List<PortfolioSnapshot>
    ) {
        val timeSinceUpdate =
            0L.coerceAtLeast(
                Instant.now().epochSecond - latest.timestamp.epochSecond
            )
        val isStale = timeSinceUpdate > PrecisionConstants.STALE_THRESHOLD_SECONDS

        renderHeaderSection(latest, timeSinceUpdate, isStale)
        overviewGridComponent.render(latest)

        div.div(CssClass.Layout.DetailGrid) {
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
            div(CssClass.Layout.HeaderTitleSection) {
                h1 { +ViewText.APP_TITLE }
                val badgeClass = if (isStale) CssClass.StatusCard.Delayed else CssClass.StatusCard.Live
                val badgeText = if (isStale) ViewText.DELAYED else ViewText.LIVE
                div(badgeClass) { +badgeText }
            }

            div(CssClass.Layout.HeaderActions) {
                div(CssClass.DataAge.Container) {
                    div(CssClass.DataAge.Label) { +ViewText.DATA_AGE }
                    val ageClass = if (isStale) CssClass.DataAge.ValueStale else CssClass.DataAge.Value
                    div(ageClass) { +"$timeSinceUpdate${ViewText.AGO_SECONDS}" }
                    div(CssClass.DataAge.Time) {
                        attributes[HtmlAttrs.DATA_EPOCH] =
                            latest.timestamp.toEpochMilli().toString()
                        +timeFormatter.format(latest.timestamp)
                    }
                }
                a(CssClass.Button.Secondary, href = Routes.HISTORY) {
                    icon(Icons.CHART)
                    span { +ViewText.NAV_HISTORY }
                }
                a(CssClass.Button.Secondary, href = Routes.SETTINGS) {
                    icon(Icons.COG)
                    span { +ViewText.SETTINGS_TITLE }
                }
            }
        }
    }
}
