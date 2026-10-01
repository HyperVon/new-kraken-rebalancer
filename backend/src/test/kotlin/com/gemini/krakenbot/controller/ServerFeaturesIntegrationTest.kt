package com.gemini.krakenbot.controller

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.ErrorHandlingConfig.configureErrorHandling
import com.gemini.krakenbot.config.configureCachingAndConditionalHeaders
import com.gemini.krakenbot.config.configureCompression
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.OrderIntentService
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
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.formUrlEncode
import io.ktor.http.parametersOf
import io.ktor.server.application.install
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.testing.testApplication
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module

private data class DashboardCsrfToken(val value: String, val cookie: String)

private suspend fun HttpClient.issueDashboardCsrf(): DashboardCsrfToken {
    val response = get("/")
    val token = Regex("""name="csrfToken" value="([^"]+)"""")
        .find(response.bodyAsText())
        ?.groupValues
        ?.get(1)
        ?: error("Dashboard did not render its CSRF token")
    val cookie = response.headers[HttpHeaders.SetCookie]?.substringBefore(';')
        ?: error("Dashboard did not issue its CSRF cookie")
    return DashboardCsrfToken(token, cookie)
}

@Suppress("unused")
class ServerFeaturesIntegrationTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        var isPaused = false
        val portfolioManager = mockk<PortfolioManager>(relaxed = true)
        val orderIntentService = mockk<OrderIntentService>(relaxed = true)
        val configService = mockk<ConfigService>(relaxed = true)
        every { configService.getConfig() } returns TestFixtures.config()
        every { portfolioManager.isLoopPaused() } answers { isPaused }
        every { portfolioManager.pauseLoop() } answers { isPaused = true }
        every { portfolioManager.resumeLoop() } answers { isPaused = false }

        val testModule =
            module {
                single { mockk<TradeHistoryService>(relaxed = true) }
                single { configService }
                single { portfolioManager }
                single { orderIntentService }
                single { jacksonObjectMapper().registerModule(JavaTimeModule()) }
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
                single { DashboardController(get(), get(), get(), get(), get(), get()) }
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

        "compression feature compresses html responses" {
            testApplication {
                application {
                    install(SSE)
                    configureCompression()
                    dashboardRouting()
                }
                val response =
                    client.get("/") {
                        header(HttpHeaders.AcceptEncoding, TestFixtures.GZIP)
                    }
                response.status shouldBe HttpStatusCode.OK
                response.headers[HttpHeaders.ContentEncoding] shouldBe TestFixtures.GZIP
            }
        }

        "caching features set cache headers on CSS stylesheet responses" {
            testApplication {
                application {
                    install(SSE)
                    configureCachingAndConditionalHeaders()
                    dashboardRouting()
                }
                val response = client.get("/static/style.css")
                response.status shouldBe HttpStatusCode.OK
                response.headers[HttpHeaders.CacheControl] shouldContain "max-age=86400"
            }
        }

        "error handling hides internal 500 details while preserving safe 400 details" {
            testApplication {
                application {
                    configureErrorHandling()
                    routing {
                        get("/test/internal-error") {
                            throw RuntimeException("SQL failed at /private/app/kraken-rebalancer.db")
                        }
                        get("/test/invalid-request") {
                            throw IllegalArgumentException("Allocation total must equal 100%")
                        }
                    }
                }

                val internalError = client.get("/test/internal-error")
                internalError.status shouldBe HttpStatusCode.InternalServerError
                internalError.bodyAsText() shouldContain "An unexpected error occurred."
                internalError.bodyAsText() shouldNotContain "/private/app/kraken-rebalancer.db"

                val invalidRequest = client.get("/test/invalid-request")
                invalidRequest.status shouldBe HttpStatusCode.BadRequest
                invalidRequest.bodyAsText() shouldContain "Allocation total must equal 100%"
            }
        }

        "health endpoint reports paused state as false initially" {
            testApplication {
                application {
                    install(SSE)
                    dashboardRouting()
                }
                val response = client.get("/api/health")
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain "\"paused\":false"
            }
        }

        "pause endpoint sets the rebalancer loop to paused" {
            testApplication {
                application {
                    install(SSE)
                    dashboardRouting()
                }
                val csrf = client.issueDashboardCsrf()
                val pauseResponse = client.post("/api/pause") {
                    setBody(parametersOf("csrfToken", csrf.value).formUrlEncode())
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                    header(HttpHeaders.Cookie, csrf.cookie)
                }
                pauseResponse.status shouldBe HttpStatusCode.OK
                pauseResponse.headers["HX-Refresh"] shouldBe "true"
                pauseResponse.bodyAsText() shouldContain "true"

                val healthAfterPause = client.get("/api/health")
                healthAfterPause.bodyAsText() shouldContain "\"paused\":true"
            }
        }

        "resume endpoint clears the paused state" {
            testApplication {
                application {
                    install(SSE)
                    dashboardRouting()
                }
                val csrf = client.issueDashboardCsrf()
                client.post("/api/pause") {
                    setBody(parametersOf("csrfToken", csrf.value).formUrlEncode())
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                    header(HttpHeaders.Cookie, csrf.cookie)
                }
                val resumeResponse = client.post("/api/resume") {
                    setBody(parametersOf("csrfToken", csrf.value).formUrlEncode())
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                    header(HttpHeaders.Cookie, csrf.cookie)
                }
                resumeResponse.status shouldBe HttpStatusCode.OK
                resumeResponse.headers["HX-Refresh"] shouldBe "true"
                resumeResponse.bodyAsText() shouldContain "false"

                val healthAfterResume = client.get("/api/health")
                healthAfterResume.bodyAsText() shouldContain "\"paused\":false"
            }
        }

        "pause and resume endpoints reject requests without CSRF" {
            testApplication {
                application {
                    install(SSE)
                    dashboardRouting()
                }
                val pauseResponse = client.post("/api/pause") {
                    setBody(parametersOf().formUrlEncode())
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                }
                val resumeResponse = client.post("/api/resume") {
                    setBody(parametersOf().formUrlEncode())
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                }

                pauseResponse.status shouldBe HttpStatusCode.Forbidden
                resumeResponse.status shouldBe HttpStatusCode.Forbidden
                verify(exactly = 0) { portfolioManager.pauseLoop() }
                verify(exactly = 0) { portfolioManager.resumeLoop() }
            }
        }

        "pause and resume reject a stale CSRF pair and return a replacement token" {
            testApplication {
                application {
                    install(SSE)
                    dashboardRouting()
                }
                val staleToken = "A".repeat(43) + "." + "A".repeat(43)

                val pauseResponse = client.post("/api/pause") {
                    header("HX-Request", "true")
                    header(HttpHeaders.Origin, "http://localhost")
                    setBody(parametersOf("csrfToken", staleToken).formUrlEncode())
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                    header(HttpHeaders.Cookie, "rebalancer-csrf=$staleToken")
                }
                val resumeResponse = client.post("/api/resume") {
                    header("HX-Request", "true")
                    header(HttpHeaders.Origin, "http://localhost")
                    setBody(parametersOf("csrfToken", staleToken).formUrlEncode())
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                    header(HttpHeaders.Cookie, "rebalancer-csrf=$staleToken")
                }

                listOf(pauseResponse, resumeResponse).forEach { response ->
                    response.status shouldBe HttpStatusCode.Forbidden
                    response.headers["X-Rebalancer-CSRF-Session-Expired"] shouldBe "true"
                    val replacementToken = response.headers["X-Rebalancer-CSRF-Token"]
                        ?: error("Stale CSRF recovery did not return a replacement token")
                    response.headers[HttpHeaders.SetCookie] shouldContain "rebalancer-csrf=$replacementToken"
                }

                val missingCookie = client.post("/api/pause") {
                    header("HX-Request", "true")
                    header(HttpHeaders.Origin, "http://localhost")
                    setBody(parametersOf("csrfToken", staleToken).formUrlEncode())
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                }
                missingCookie.status shouldBe HttpStatusCode.Forbidden
                missingCookie.headers["X-Rebalancer-CSRF-Session-Expired"] shouldBe "true"
                val replacementToken = missingCookie.headers["X-Rebalancer-CSRF-Token"]
                    ?: error("Missing-cookie recovery did not return a replacement token")
                missingCookie.headers[HttpHeaders.SetCookie] shouldContain "rebalancer-csrf=$replacementToken"

                val currentCsrf = client.issueDashboardCsrf()
                val mismatchedForm = client.post("/api/resume") {
                    header("HX-Request", "true")
                    header(HttpHeaders.Origin, "http://localhost")
                    header(HttpHeaders.Cookie, currentCsrf.cookie)
                    setBody(parametersOf("csrfToken", staleToken).formUrlEncode())
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                }
                mismatchedForm.status shouldBe HttpStatusCode.Forbidden
                mismatchedForm.headers["X-Rebalancer-CSRF-Session-Expired"] shouldBe "true"
                mismatchedForm.headers["X-Rebalancer-CSRF-Token"] shouldBe currentCsrf.value
                mismatchedForm.headers[HttpHeaders.SetCookie] shouldBe null

                val noOrigin = client.post("/api/resume") {
                    header("HX-Request", "true")
                    header(HttpHeaders.Cookie, currentCsrf.cookie)
                    setBody(parametersOf("csrfToken", staleToken).formUrlEncode())
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                }
                noOrigin.status shouldBe HttpStatusCode.Forbidden
                noOrigin.headers["X-Rebalancer-CSRF-Session-Expired"] shouldBe null
                noOrigin.headers["X-Rebalancer-CSRF-Token"] shouldBe null
                verify(exactly = 0) { portfolioManager.pauseLoop() }
                verify(exactly = 0) { portfolioManager.resumeLoop() }
            }
        }
    }
}
