package com.gemini.krakenbot.view

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.service.history.HistoryEventQuery
import com.gemini.krakenbot.service.history.HistoryPage
import com.gemini.krakenbot.view.component.AllocationChartComponent
import com.gemini.krakenbot.view.component.DashboardFragmentComponent
import com.gemini.krakenbot.view.component.DashboardShellComponent
import com.gemini.krakenbot.view.component.HistoryPageComponent
import com.gemini.krakenbot.view.component.HistoryPageData
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
                historyPageComponent = HistoryPageComponent(),
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

            val history = createHTML(prettyPrint = false).html {
                with(this) {
                    dashboardView.renderHistoryPage(
                        settings = config.settings,
                        paused = true,
                        csrfToken = "csrf-val",
                        data = HistoryPageData(
                            range = "all",
                            filters = HistoryEventQuery(),
                            tradesOffset = 0,
                            ledgersOffset = 0,
                            observations = emptyList(),
                            checkpoints = HistoryPage(emptyList(), total = 0, limit = 50, offset = 0, hasMore = false),
                            trades = HistoryPage(emptyList(), total = 0, limit = 50, offset = 0, hasMore = false),
                            ledgers = HistoryPage(emptyList(), total = 0, limit = 50, offset = 0, hasMore = false),
                            syncStatuses = emptyList(),
                            accountAvailable = false,
                        ),
                    )
                }
            }
            history shouldContain "History"

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
