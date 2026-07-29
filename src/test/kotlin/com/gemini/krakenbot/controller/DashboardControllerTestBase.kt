package com.gemini.krakenbot.controller

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gemini.krakenbot.config.*
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.TradeHistoryService
import com.gemini.krakenbot.view.DashboardView
import com.gemini.krakenbot.view.component.*
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.ktor.client.plugins.sse.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.testing.*
import io.mockk.mockk
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import io.ktor.server.sse.SSE as ServerSSE

abstract class DashboardControllerTestBase : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    protected val tradeHistoryService = mockk<TradeHistoryService>(relaxed = true)
    protected val configService = mockk<ConfigService>(relaxed = true)
    protected val objectMapper =
        jacksonObjectMapper().registerModule(JavaTimeModule())

    // Route tests intentionally omit app-wide CORS: the dashboard has no user auth, and the
    // local/private-origin trust boundary is covered by NetworkUtilsTest.
    protected fun Application.configureTestEnv() {
        install(ServerSSE)
        dashboardRouting()
    }

    init {
        val testModule =
            module {
                single { tradeHistoryService }
                single { configService }
                single { objectMapper }
                single { DashboardShellComponent() }
                single { SettingsFormComponent() }
                single { OverviewGridComponent() }
                single { AllocationChartComponent() }
                single { PerformanceTableComponent() }
                single { RecentActivityComponent() }
                single {
                    DashboardFragmentComponent(
                        overviewGridComponent = get(),
                        allocationChartComponent = get(),
                        performanceTableComponent = get(),
                        recentActivityComponent = get(),
                    )
                }
                single { HistoryPageComponent(get()) }
                single {
                    DashboardView(
                        shellComponent = get(),
                        settingsFormComponent = get(),
                        fragmentComponent = get(),
                        historyPageComponent = get(),
                    )
                }
                single { DashboardController(get(), get(), get(), get()) }
            }

        beforeTest {
            stopKoin()
            startKoin {
                modules(testModule)
            }
        }

        afterTest {
            stopKoin()
        }
    }
}
