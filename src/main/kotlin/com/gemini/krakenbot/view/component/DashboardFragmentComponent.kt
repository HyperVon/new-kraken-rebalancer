package com.gemini.krakenbot.view.component

import com.gemini.krakenbot.model.PortfolioSnapshot
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
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

    fun DIV.render(latest: PortfolioSnapshot, history: List<PortfolioSnapshot>) {
        val timeSinceUpdate = 0L.coerceAtLeast(Instant.now().epochSecond - latest.timestamp.epochSecond)
        val isStale = timeSinceUpdate > 90

        renderHeaderSection(latest, timeSinceUpdate, isStale)
        with(overviewGridComponent) { render(latest) }
        
        div("detail-grid") {
            with(allocationChartComponent) { render(latest) }
            with(performanceTableComponent) { render(latest) }
        }
        
        with(recentActivityComponent) { render(history) }
    }

    private fun DIV.renderHeaderSection(latest: PortfolioSnapshot, timeSinceUpdate: Long, isStale: Boolean) {
        header {
            div("header-title-section") {
                h1 { +"Kraken Rebalancer" }
                val badgeClass = if (isStale) "status-badge delayed" else "status-badge live"
                val badgeText = if (isStale) "DELAYED" else "LIVE"
                div(badgeClass) { +badgeText }
            }

            div("header-actions") {
                div("data-age-container") {
                    div("data-age-label") { +"Data Age" }
                    val ageClass = if (isStale) "data-age-value stale" else "data-age-value"
                    div(ageClass) { +"${timeSinceUpdate}s ago" }
                    div("data-age-time") {
                        attributes["data-epoch"] = latest.timestamp.toEpochMilli().toString()
                        +timeFormatter.format(latest.timestamp)
                    }
                }
                a(href = "/settings", classes = "btn btn-secondary") {
                    unsafe {
                        +"""<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.1a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z"></path><circle cx="12" cy="12" r="3"></circle></svg>"""
                    }
                    span { +"Settings" }
                }
            }
        }
    }
}
