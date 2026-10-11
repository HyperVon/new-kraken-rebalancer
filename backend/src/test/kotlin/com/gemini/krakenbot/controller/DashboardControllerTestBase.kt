package com.gemini.krakenbot.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.CurrentPortfolioState
import com.gemini.krakenbot.service.OrderIntentService
import com.gemini.krakenbot.service.PortfolioManager
import com.gemini.krakenbot.service.actual.ActualObservationDispatcher
import com.gemini.krakenbot.view.DashboardView
import com.gemini.krakenbot.view.component.ActualObservationsPageComponent
import com.gemini.krakenbot.view.component.AllocationChartComponent
import com.gemini.krakenbot.view.component.DashboardFragmentComponent
import com.gemini.krakenbot.view.component.DashboardShellComponent
import com.gemini.krakenbot.view.component.OverviewGridComponent
import com.gemini.krakenbot.view.component.PerformanceTableComponent
import com.gemini.krakenbot.view.component.RecentActivityComponent
import com.gemini.krakenbot.view.component.SettingsFormComponent
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.ktor.server.sse.SSE as ServerSSE

abstract class DashboardControllerTestBase : StringSpec() {
    protected data class CsrfTestToken(val value: String, val cookie: String)

    override fun isolationMode() = IsolationMode.InstancePerTest

    protected val configService = mockk<ConfigService>(relaxed = true)
    protected val portfolioManager = mockk<PortfolioManager>(relaxed = true)
    protected val orderIntentService = mockk<OrderIntentService>(relaxed = true)
    protected val actualObservationDispatcher = mockk<ActualObservationDispatcher>(relaxed = true)
    protected val currentPortfolioState = CurrentPortfolioState()
    protected val objectMapper: ObjectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())

    protected fun Application.configureTestEnv() {
        install(ServerSSE)
        val view = DashboardView(
            shellComponent = DashboardShellComponent(),
            settingsFormComponent = SettingsFormComponent(),
            fragmentComponent = DashboardFragmentComponent(
                overviewGridComponent = OverviewGridComponent(),
                allocationChartComponent = AllocationChartComponent(),
                performanceTableComponent = PerformanceTableComponent(),
                recentActivityComponent = RecentActivityComponent(),
            ),
            actualObservationsPageComponent = ActualObservationsPageComponent(),
        )
        val controller = DashboardController(
            configService = configService,
            objectMapper = objectMapper,
            dashboardView = view,
            portfolioManager = portfolioManager,
            currentPortfolioState = currentPortfolioState,
            orderIntentService = orderIntentService,
            actualObservationDispatcher = actualObservationDispatcher,
        )
        routing { controller.registerRoutes(this) }
    }

    protected suspend fun HttpClient.settingsCsrf(): CsrfTestToken {
        val response = get("/settings")
        val token = Regex("""name="csrfToken" value="([^"]+)"""")
            .find(response.bodyAsText())
            ?.groupValues
            ?.get(1)
            ?: error("Settings page did not contain a CSRF token")
        val cookie = response.headers[HttpHeaders.SetCookie]?.substringBefore(';')
            ?: error("Settings page did not issue a CSRF cookie")
        return CsrfTestToken(token, cookie)
    }
}
