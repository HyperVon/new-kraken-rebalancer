package com.gemini.krakenbot.view.component

import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.util.PrecisionConstants
import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.HtmlAttrs
import com.gemini.krakenbot.view.util.HtmlIds
import com.gemini.krakenbot.view.util.HtmxAttrs
import com.gemini.krakenbot.view.util.HtmxValues
import com.gemini.krakenbot.view.util.ViewText
import com.gemini.krakenbot.view.util.div
import com.gemini.krakenbot.view.util.span
import kotlinx.html.DIV
import kotlinx.html.id
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class DashboardFragmentComponent(
    private val overviewGridComponent: OverviewGridComponent,
    private val allocationChartComponent: AllocationChartComponent,
    private val performanceTableComponent: PerformanceTableComponent,
    private val recentActivityComponent: RecentActivityComponent,
) {
    private val timeFormatter =
        DateTimeFormatter.ofPattern("hh:mm:ss a")
            .withZone(ZoneId.systemDefault())

    context(div: DIV)
    fun render(
        latest: PortfolioSnapshot,
        history: List<PortfolioSnapshot>,
        allocations: List<Allocation> = emptyList(),
    ) {
        val timeSinceUpdate =
            0L.coerceAtLeast(
                Instant.now().epochSecond - latest.timestamp.epochSecond,
            )
        val isStale = timeSinceUpdate > PrecisionConstants.STALE_THRESHOLD_SECONDS

        // Mode plate stays in the shell; this OOB swap only refreshes STREAM/STALE
        // (SSE freshness — StatusCard.Live here means healthy stream, not live trading).
        renderStreamStatus(latest, timeSinceUpdate, isStale)
        overviewGridComponent.render(latest, history)

        div.div(CssClass.Layout.DetailGrid) {
            allocationChartComponent.render(latest, allocations)
            performanceTableComponent.render(latest)
        }

        recentActivityComponent.render(history)
    }

    context(div: DIV)
    private fun renderStreamStatus(latest: PortfolioSnapshot, timeSinceUpdate: Long, isStale: Boolean) {
        div.div(CssClass.Layout.HeaderStatus) {
            id = HtmlIds.HEADER_STATUS
            attributes[HtmxAttrs.HX_SWAP_OOB] = HtmxValues.TRUE
            val badgeClass = if (isStale) CssClass.StatusCard.Delayed else CssClass.StatusCard.Live
            val badgeText = if (isStale) ViewText.STREAM_STALE else ViewText.STREAM
            div(badgeClass) { +badgeText }
            val ageClass = if (isStale) CssClass.DataAge.ValueStale else CssClass.DataAge.Value
            span(ageClass) { +"$timeSinceUpdate${ViewText.AGO_SECONDS}" }
            span(CssClass.DataAge.Time) {
                attributes[HtmlAttrs.DATA_EPOCH] =
                    latest.timestamp.toEpochMilli().toString()
                +timeFormatter.format(latest.timestamp)
            }
        }
    }
}
