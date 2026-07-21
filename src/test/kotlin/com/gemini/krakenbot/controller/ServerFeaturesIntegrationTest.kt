package com.gemini.krakenbot.controller

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gemini.krakenbot.config.configureCachingAndConditionalHeaders
import com.gemini.krakenbot.config.configureCompression
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.TradeHistoryService
import com.gemini.krakenbot.view.DashboardView
import com.gemini.krakenbot.view.component.*
import com.gemini.krakenbot.view.util.Routes
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.sse.*
import io.ktor.server.testing.*
import io.mockk.mockk
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module

@Suppress("unused")
class ServerFeaturesIntegrationTest : StringSpec() {

    init {
        val testModule = module {
            single { mockk<TradeHistoryService>(relaxed = true) }
            single { mockk<ConfigService>(relaxed = true) }
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
                    recentActivityComponent = get()
                )
            }
            single { HistoryPageComponent() }
            single {
                DashboardView(
                    shellComponent = get(),
                    settingsFormComponent = get(),
                    fragmentComponent = get(),
                    historyPageComponent = get()
                )
            }
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
                val response = client.get(Routes.ROOT) {
                    header(HttpHeaders.AcceptEncoding, "gzip")
                }
                response.status shouldBe HttpStatusCode.OK
                response.headers[HttpHeaders.ContentEncoding] shouldBe "gzip"
            }
        }

        "caching features set cache headers on CSS stylesheet responses" {
            testApplication {
                application {
                    install(SSE)
                    configureCachingAndConditionalHeaders()
                    dashboardRouting()
                }
                val response = client.get(Routes.STATIC_STYLE_CSS)
                response.status shouldBe HttpStatusCode.OK
                response.headers[HttpHeaders.CacheControl] shouldContain "max-age=86400"
            }
        }
    }
}

