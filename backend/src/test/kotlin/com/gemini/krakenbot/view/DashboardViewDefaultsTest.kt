@file:Suppress("unused")

package com.gemini.krakenbot.view

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.view.component.AllocationChartComponent
import com.gemini.krakenbot.view.component.DashboardFragmentComponent
import com.gemini.krakenbot.view.component.DashboardShellComponent
import com.gemini.krakenbot.view.component.OverviewGridComponent
import com.gemini.krakenbot.view.component.PerformanceTableComponent
import com.gemini.krakenbot.view.component.RecentActivityComponent
import com.gemini.krakenbot.view.component.SettingsFormComponent
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.html.div
import kotlinx.html.html
import kotlinx.html.stream.createHTML
import java.math.BigDecimal
import java.time.Instant

class DashboardViewDefaultsTest : StringSpec() {
    init {
        "waiting fragment replaces previous source freshness without retaining its timestamp" {
            val component = DashboardFragmentComponent(
                overviewGridComponent = OverviewGridComponent(),
                allocationChartComponent = AllocationChartComponent(),
                performanceTableComponent = PerformanceTableComponent(),
                recentActivityComponent = RecentActivityComponent(),
            )
            val fragment = createHTML(prettyPrint = false).div {
                with(this) {
                    component.renderWaiting(
                        settings = TestFixtures.DEFAULT_TEST_CONFIG.settings,
                        unresolvedIntents = emptyList(),
                        csrfToken = null,
                        recentActivities = emptyList(),
                        activitySourceId = "new-source",
                    )
                }
            }
            fragment shouldContain "id=\"header-status\" hx-swap-oob=\"true\""
            fragment shouldContain ">STALE</div>"
            fragment shouldNotContain "data-epoch"
        }

        "view entrypoints render screens with optional UI values omitted" {
            val config = TestFixtures.DEFAULT_TEST_CONFIG
            val dashboardView = DashboardView(
                shellComponent = DashboardShellComponent(),
                settingsFormComponent = SettingsFormComponent(),
                fragmentComponent = DashboardFragmentComponent(
                    overviewGridComponent = OverviewGridComponent(),
                    allocationChartComponent = AllocationChartComponent(),
                    performanceTableComponent = PerformanceTableComponent(),
                    recentActivityComponent = RecentActivityComponent(),
                ),
            )

            val shell = createHTML(prettyPrint = false).html {
                with(this) { dashboardView.renderDashboardShell(config.settings) }
            }
            shell shouldContain "id=\"mode-plate\""
            shell shouldContain "sse-connect=\"/api/dashboard/updates\""
            shell shouldNotContain "sse-connect=\"/api/status/stream\""

            val settings = createHTML(prettyPrint = false).html {
                with(this) {
                    dashboardView.renderSettingsPage(config, errorMessage = null, csrfToken = "test-token")
                }
            }
            settings shouldContain "Settings"

            val form = createHTML(prettyPrint = false).div {
                dashboardView.renderSettingsFormFragment(this, config, errorMessage = null, csrfToken = "test-token")
            }
            form shouldContain "test-token"

            val fragment = createHTML(prettyPrint = false).div {
                with(this) {
                    dashboardView.renderDashboardFragment(
                        settings = config.settings,
                        latest = TestFixtures.emptySnapshot(Instant.now(), BigDecimal("100")),
                    )
                }
            }
            fragment shouldContain "mode-plate"
        }

        "dashboard components retain their defaults when rendered directly" {
            val settings = TestFixtures.DEFAULT_TEST_CONFIG.settings
            val latest = TestFixtures.emptySnapshot(Instant.now(), BigDecimal("100"))
            val shell = createHTML(prettyPrint = false).html {
                with(this) { DashboardShellComponent().render(settings) }
            }
            shell shouldContain "mode-plate"

            val fragmentComponent = DashboardFragmentComponent(
                overviewGridComponent = OverviewGridComponent(),
                allocationChartComponent = AllocationChartComponent(),
                performanceTableComponent = PerformanceTableComponent(),
                recentActivityComponent = RecentActivityComponent(),
            )
            val fragment = createHTML(prettyPrint = false).div {
                with(this) { fragmentComponent.render(settings, latest) }
            }
            fragment shouldContain "Recent Activity"
            fragment shouldContain "data-activity-source-id=\"\""
            fragment shouldNotContain "<form"

            val allocation = createHTML(prettyPrint = false).div {
                with(this) { AllocationChartComponent().render(latest) }
            }
            allocation shouldContain "Portfolio Allocation"

            val performance = createHTML(prettyPrint = false).div {
                with(this) { PerformanceTableComponent().render(latest) }
            }
            performance shouldContain "Asset Performance"
        }
    }
}
