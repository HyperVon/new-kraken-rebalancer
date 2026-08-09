package com.gemini.krakenbot.controller

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.PortfolioManager
import com.gemini.krakenbot.service.TradeHistoryService
import com.gemini.krakenbot.view.DashboardView
import com.gemini.krakenbot.view.component.AllocationChartComponent
import com.gemini.krakenbot.view.component.DashboardFragmentComponent
import com.gemini.krakenbot.view.component.DashboardShellComponent
import com.gemini.krakenbot.view.component.HistoryPageComponent
import com.gemini.krakenbot.view.component.OverviewGridComponent
import com.gemini.krakenbot.view.component.PerformanceTableComponent
import com.gemini.krakenbot.view.component.RecentActivityComponent
import com.gemini.krakenbot.view.component.SettingsFormComponent
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.mockk.mockk
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import io.ktor.server.sse.SSE as ServerSSE

abstract class DashboardControllerTestBase : StringSpec() {
    protected data class CsrfTestToken(val value: String, val cookie: String)

    override fun isolationMode() = IsolationMode.InstancePerTest

    protected suspend fun HttpClient.settingsCsrf(): CsrfTestToken {
        val response = get("/settings")
        val token =
            Regex("""name="csrfToken" value="([^"]+)"""")
                .find(response.bodyAsText())
                ?.groupValues
                ?.get(1)
                ?: error("Settings page did not contain a CSRF token")
        val cookie = response.headers[HttpHeaders.SetCookie]?.substringBefore(';')
            ?: error("Settings page did not issue a CSRF cookie")
        return CsrfTestToken(token, cookie)
    }

    protected val tradeHistoryService = mockk<TradeHistoryService>(relaxed = true)
    protected val configService = mockk<ConfigService>(relaxed = true)
    protected val portfolioManager = mockk<PortfolioManager>(relaxed = true)
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
                single { portfolioManager }
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
                single { DashboardController(get(), get(), get(), get(), get()) }
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
