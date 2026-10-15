package com.gemini.krakenbot.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.CurrentPortfolioState
import com.gemini.krakenbot.service.OrderIntentService
import com.gemini.krakenbot.service.PortfolioManager
import com.gemini.krakenbot.service.RebalanceOperationalStatus
import com.gemini.krakenbot.view.DashboardView
import com.gemini.krakenbot.view.component.AllocationChartComponent
import com.gemini.krakenbot.view.component.DashboardFragmentComponent
import com.gemini.krakenbot.view.component.DashboardShellComponent
import com.gemini.krakenbot.view.component.OverviewGridComponent
import com.gemini.krakenbot.view.component.PerformanceTableComponent
import com.gemini.krakenbot.view.component.RecentActivityComponent
import com.gemini.krakenbot.view.component.SettingsFormComponent
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import java.math.BigDecimal
import java.time.Instant
import io.ktor.client.plugins.sse.SSE as ClientSSE
import io.ktor.server.sse.SSE as ServerSSE

class DashboardCutoverApiTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    private data class Server(
        val configService: ConfigService,
        val portfolioManager: PortfolioManager,
        val orderIntentService: OrderIntentService,
        val currentPortfolioState: CurrentPortfolioState,
        val objectMapper: ObjectMapper,
        val controller: DashboardController,
    ) {
        val config: AppConfig = TestFixtures.DEFAULT_TEST_CONFIG
    }

    private fun Application.installRoutes(server: Server) {
        install(ServerSSE)
        routing { server.controller.registerRoutes(this) }
    }

    private fun server(
        snapshot: PortfolioSnapshot? = null,
        previousSnapshots: List<PortfolioSnapshot> = emptyList(),
    ): Server {
        val configService = mockk<ConfigService>()
        val portfolioManager = mockk<PortfolioManager>()
        val orderIntentService = mockk<OrderIntentService>()
        val currentPortfolioState = CurrentPortfolioState()
        val objectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())
        every { configService.getConfig() } returns TestFixtures.DEFAULT_TEST_CONFIG
        every { portfolioManager.isLoopPaused() } returns false
        every { portfolioManager.isLoopRunning() } returns true
        every { portfolioManager.getOperationalStatus() } returns RebalanceOperationalStatus()
        coEvery { orderIntentService.countUnresolvedIntents() } returns 0L
        coEvery { orderIntentService.getUnresolvedIntents() } returns emptyList()
        previousSnapshots.forEach { currentPortfolioState.publish(it, "test-source") }
        snapshot?.let { currentPortfolioState.publish(it, "test-source") }
        val view = DashboardView(
            shellComponent = DashboardShellComponent(),
            settingsFormComponent = SettingsFormComponent(),
            fragmentComponent = DashboardFragmentComponent(
                overviewGridComponent = OverviewGridComponent(),
                allocationChartComponent = AllocationChartComponent(),
                performanceTableComponent = PerformanceTableComponent(),
                recentActivityComponent = RecentActivityComponent(),
            ),
        )
        val controller = DashboardController(
            configService = configService,
            objectMapper = objectMapper,
            dashboardView = view,
            portfolioManager = portfolioManager,
            currentPortfolioState = currentPortfolioState,
            orderIntentService = orderIntentService,
        )
        return Server(
            configService,
            portfolioManager,
            orderIntentService,
            currentPortfolioState,
            objectMapper,
            controller,
        )
    }

    init {
        "history route renders history page" {
            val server = server()
            testApplication {
                application { installRoutes(server) }

                val response = client.get("/history")
                response.status shouldBe HttpStatusCode.OK
                val html = response.bodyAsText()
                html shouldContain "History"
            }
        }

        "dashboard serves only the latest in-memory cycle and omits retired trend metrics" {
            val snapshot = TestFixtures.emptySnapshot(Instant.now(), BigDecimal("1234.50"))
            val server = server(snapshot)
            testApplication {
                application { installRoutes(server) }
                val response = client.get("/fragments/dashboard")
                response.status shouldBe HttpStatusCode.OK
                val html = response.bodyAsText()
                html shouldContain "$1,234.50"
                html shouldContain "Total Portfolio"
                html.lowercase() shouldNotContain "24h"
                html.lowercase() shouldNotContain "drawdown"
                html.lowercase() shouldNotContain "recent high"
                html.lowercase() shouldNotContain "view all history"
            }
        }

        "dashboard hero chart uses recent in-memory cycles without a database history read" {
            val earlier = TestFixtures.emptySnapshot(Instant.parse("2026-10-11T12:00:00Z"), BigDecimal("1230.00"))
            val latest = TestFixtures.emptySnapshot(Instant.parse("2026-10-11T12:01:00Z"), BigDecimal("1234.50"))
            val server = server(snapshot = latest, previousSnapshots = listOf(earlier))

            testApplication {
                application { installRoutes(server) }
                val response = client.get("/fragments/dashboard")
                response.status shouldBe HttpStatusCode.OK
                val html = response.bodyAsText()
                html shouldContain "hero-trend-svg"
                html shouldContain "2 completed cycles"
                html shouldContain "points=\"66.00,104.97 514.00,17.03\""
            }
        }

        "health and readiness expose current runtime state without history or sync totals" {
            val server = server()
            testApplication {
                application { installRoutes(server) }
                val health = client.get("/api/health").bodyAsText()
                health shouldContain "activeMode"
                health shouldContain "loopRunning"
                health shouldContain "lastSnapshotTime"
                health shouldContain "unresolvedOrderIntents"
                health.lowercase() shouldNotContain "history"
                health.lowercase() shouldNotContain "sync"

                val readiness = client.get("/api/readiness")
                readiness.status shouldBe HttpStatusCode.ServiceUnavailable
                readiness.bodyAsText().lowercase() shouldNotContain "history"
            }
        }

        "health and readiness report diagnostics-unavailable when intent service throws" {
            val server = server(snapshot = TestFixtures.emptySnapshot(Instant.now(), BigDecimal("100.00")))
            coEvery { server.orderIntentService.countUnresolvedIntents() } throws RuntimeException("DB down")
            testApplication {
                application { installRoutes(server) }
                val response = client.get("/api/readiness")
                response.status shouldBe HttpStatusCode.ServiceUnavailable
                response.bodyAsText() shouldContain "DIAGNOSTICS_UNAVAILABLE"
            }
        }

        "health and readiness report config-unavailable when configService throws" {
            val server = server(snapshot = TestFixtures.emptySnapshot(Instant.now(), BigDecimal("100.00")))
            every { server.configService.getConfig() } throws RuntimeException("Config missing")
            testApplication {
                application { installRoutes(server) }
                val response = client.get("/api/readiness")
                response.status shouldBe HttpStatusCode.ServiceUnavailable
                val body = response.bodyAsText()
                body shouldContain "CONFIG_UNAVAILABLE"

                val health = client.get("/api/health").bodyAsText()
                health shouldContain "\"activeMode\":\"UNKNOWN\""
            }
        }

        "health and readiness report loop-not-running when loop is stopped" {
            val server = server(snapshot = TestFixtures.emptySnapshot(Instant.now(), BigDecimal("100.00")))
            every { server.portfolioManager.isLoopRunning() } returns false
            testApplication {
                application { installRoutes(server) }
                val response = client.get("/api/readiness")
                response.status shouldBe HttpStatusCode.ServiceUnavailable
                response.bodyAsText() shouldContain "LOOP_NOT_RUNNING"
            }
        }

        "health and readiness report READY 200 when all conditions pass" {
            val liveConfig = TestFixtures.DEFAULT_TEST_CONFIG.copy(
                settings = TestFixtures.DEFAULT_TEST_CONFIG.settings.copy(simulation = false, dryRun = false),
            )
            val server = server(snapshot = TestFixtures.emptySnapshot(Instant.now(), BigDecimal("100.00")))
            every { server.configService.getConfig() } returns liveConfig
            testApplication {
                application { installRoutes(server) }
                val readiness = client.get("/api/readiness")
                readiness.status shouldBe HttpStatusCode.OK
                readiness.bodyAsText() shouldContain "\"readiness\":\"READY\""
                readiness.bodyAsText() shouldContain "\"readinessReason\":\"READY\""

                val health = client.get("/api/health").bodyAsText()
                health shouldContain "\"activeMode\":\"LIVE\""
            }
        }

        "SSE emits the latest cycle snapshot without querying persisted history" {
            val snapshot = TestFixtures.emptySnapshot(Instant.parse("2026-10-10T12:00:00Z"), BigDecimal("42.00"))
            val server = server(snapshot)
            testApplication {
                val client = createClient { install(ClientSSE) }
                application { installRoutes(server) }
                client.sse("/api/status/stream") {
                    val events = incoming.take(1).toList()
                    events.single().data shouldBe server.objectMapper.writeValueAsString(snapshot)
                }
            }
        }
    }
}
