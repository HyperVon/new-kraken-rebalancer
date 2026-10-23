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
import kotlinx.html.div
import kotlinx.html.html
import kotlinx.html.stream.createHTML
import java.math.BigDecimal
import java.time.Instant

class DashboardViewDefaultsTest :
    StringSpec({
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
    })
