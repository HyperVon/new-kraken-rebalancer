package com.gemini.krakenbot.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.CurrentPortfolioState
import com.gemini.krakenbot.service.OrderIntentService
import com.gemini.krakenbot.service.PortfolioManager
import com.gemini.krakenbot.service.history.HistoryApiPresenter
import com.gemini.krakenbot.service.history.HistoryCsvExporter
import com.gemini.krakenbot.service.history.HistoryObservationRecorder
import com.gemini.krakenbot.service.history.HistoryService
import com.gemini.krakenbot.service.history.HistoryStore
import com.gemini.krakenbot.service.history.HistorySyncCoordinator
import com.gemini.krakenbot.service.history.HistorySynchronizer
import com.gemini.krakenbot.service.history.HistoryValuesCoverage
import com.gemini.krakenbot.service.reporting.HistoryReportingService
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
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.runBlocking
import java.time.Instant
import io.ktor.server.sse.SSE as ServerSSE

abstract class DashboardControllerTestBase : StringSpec() {
    protected data class CsrfTestToken(val value: String, val cookie: String)

    override fun isolationMode() = IsolationMode.InstancePerTest

    protected val configService = mockk<ConfigService>(relaxed = true)
    protected val portfolioManager = mockk<PortfolioManager>(relaxed = true)
    protected val orderIntentService = mockk<OrderIntentService>(relaxed = true)
    protected val historyService = mockk<HistoryService>(relaxed = true)
    protected val historyObservationRecorder = mockk<HistoryObservationRecorder>(relaxed = true)
    protected val historySynchronizer = mockk<HistorySynchronizer>(relaxed = true)
    protected val historyStore = mockk<HistoryStore>(relaxed = true)
    protected val historyCsvExporter = spyk(HistoryCsvExporter(historyService, historyStore))
    protected val historySyncCoordinator = mockk<HistorySyncCoordinator>(relaxed = true)
    protected val currentPortfolioState = CurrentPortfolioState()
    protected val objectMapper: ObjectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())
    protected var historyApiNow: () -> Instant = { Instant.now() }
    protected val historyReportingService by lazy { HistoryReportingService(historyService, historyApiNow) }

    init {
        runBlocking {
            coEvery { historyStore.valuesCoverage(any()) } returns HistoryValuesCoverage(
                sourceKey = "ACCOUNT:test",
                firstRecordedAt = null,
                lastRecordedAt = null,
                completeCount = 0L,
                incompleteCount = 0L,
            )
        }
    }

    protected open fun historyApi(): HistoryApiPresenter = HistoryApiPresenter(
        historyService = historyService,
        historyStore = historyStore,
        reportingService = historyReportingService,
        syncCoordinator = historySyncCoordinator,
        nowProvider = historyApiNow,
    )

    protected fun Application.configureTestEnv(
        csvExporter: HistoryCsvExporter? = historyCsvExporter,
        service: HistoryService? = historyService,
    ) {
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
            historyPageComponent = HistoryPageComponent(),
        )
        val controller = DashboardController(
            configService = configService,
            objectMapper = objectMapper,
            dashboardView = view,
            portfolioManager = portfolioManager,
            currentPortfolioState = currentPortfolioState,
            orderIntentService = orderIntentService,
            historyService = service,
            historyObservationRecorder = historyObservationRecorder,
            historySynchronizer = historySynchronizer,
            historyApi = historyApi(),
            historyReportingService = historyReportingService,
            historyCsvExporter = csvExporter,
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
