package com.gemini.krakenbot.controller

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gemini.krakenbot.config.*
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.TradeHistoryService
import com.gemini.krakenbot.view.DashboardView
import com.gemini.krakenbot.view.component.*
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.testing.*
import io.ktor.client.plugins.sse.*
import io.ktor.sse.*
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.collect
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.slot
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.math.BigDecimal
import java.time.Instant

class DashboardControllerTest : StringSpec() {

    override fun isolationMode() = io.kotest.core.spec.IsolationMode.InstancePerTest

    private val tradeHistoryService = mockk<TradeHistoryService>(relaxed = true)
    private val configService = mockk<ConfigService>(relaxed = true)
    private val objectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())

    private fun Application.configureTestEnv() {
        install(io.ktor.server.sse.SSE)
        dashboardRouting()
    }

    init {
        val testModule = module {
            single { tradeHistoryService }
            single { configService }
            single { objectMapper }
            single { DashboardShellComponent() }
            single { SettingsFormComponent() }
            single { OverviewGridComponent() }
            single { AllocationChartComponent() }
            single { PerformanceTableComponent() }
            single { RecentActivityComponent() }
            single { DashboardFragmentComponent(get(), get(), get(), get()) }
            single { DashboardView(get(), get(), get()) }
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

        "getDashboardShell_ReturnsHtml" {
            testApplication {
                application {
                    configureTestEnv()
                }
                val response = client.get("/")
                response.status shouldBe HttpStatusCode.OK
                response.headers[HttpHeaders.ContentType] shouldContain "text/html"
                response.bodyAsText() shouldContain "Kraken Rebalancer"
                response.bodyAsText() shouldContain "sse-connect=\"/api/status/stream\""
            }
        }

        "getDashboardFragment_NoSnapshot_ReturnsWaitingMessage" {
            every { tradeHistoryService.getLatestSnapshot() } returns null

            testApplication {
                application {
                    configureTestEnv()
                }
                val response = client.get("/fragments/dashboard")
                response.status shouldBe HttpStatusCode.OK
                response.headers[HttpHeaders.ContentType] shouldContain "text/html"
                response.bodyAsText() shouldContain "Waiting for first rebalance cycle"
            }
        }

        "getDashboardFragment_WithSnapshot_ReturnsPopulatedHtml" {
            val nowTime = Instant.now()
            val snapshot = PortfolioSnapshot(
                nowTime,
                BigDecimal("15000.00"),
                mapOf(
                    "USD" to PortfolioSnapshot.AssetSnapshot("USD", BigDecimal("5000.0"), BigDecimal("1.0"), BigDecimal("5000.0"), BigDecimal("33.33"), BigDecimal("33.33"), BigDecimal("0.0"), BigDecimal("0.0")),
                    "BTC" to PortfolioSnapshot.AssetSnapshot("BTC", BigDecimal("0.1"), BigDecimal("50000.0"), BigDecimal("5000.0"), BigDecimal("33.33"), BigDecimal("33.33"), BigDecimal("5.0"), BigDecimal("250.0")),
                    "ETH" to PortfolioSnapshot.AssetSnapshot("ETH", BigDecimal("2.5"), BigDecimal("2000.0"), BigDecimal("5000.0"), BigDecimal("33.33"), BigDecimal("33.33"), BigDecimal("-2.0"), BigDecimal("-100.0"))
                ),
                listOf("BUY BTC 0.1"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal("33.33")
            )
            every { tradeHistoryService.getLatestSnapshot() } returns snapshot
            every { tradeHistoryService.getHistory() } returns listOf(snapshot)

            testApplication {
                application {
                    configureTestEnv()
                }
                val response = client.get("/fragments/dashboard")
                response.status shouldBe HttpStatusCode.OK
                response.headers[HttpHeaders.ContentType] shouldContain "text/html"
                
                val body = response.bodyAsText()
                body shouldContain "Total Portfolio"
                body shouldContain "Cash (USD)"
                body shouldContain "Crypto Assets"
                body shouldContain "BUY BTC 0.1"
                
                // Verify epoch data-attribute exists and matches
                body shouldContain "data-epoch=\"${nowTime.toEpochMilli()}\""
                
                // Verify default sort UI indicator
                body shouldContain "class=\"sortable asc\" onclick=\"sortTable(this, 5)\">Dev %"
                
                // Verify that default sorting (Dev % ASC) sorts the crypto assets properly (ETH before BTC)
                val ethIdx = body.indexOf("symbol-col\">ETH")
                val btcIdx = body.indexOf("symbol-col\">BTC")
                (ethIdx != -1) shouldBe true
                (btcIdx != -1) shouldBe true
                (ethIdx < btcIdx) shouldBe true
            }
        }

        "getSettingsPage_ReturnsSettingsForm" {
            val config = AppConfig(
                KrakenCredentials("real-api-key", "real-private-key"),
                Settings(60L, 2.0, 1.0, true, 0.0, 1.0),
                listOf(Allocation("USD", 100.0))
            )
            every { configService.getConfig() } returns config

            testApplication {
                application {
                    configureTestEnv()
                }
                val response = client.get("/settings")
                response.status shouldBe HttpStatusCode.OK
                response.headers[HttpHeaders.ContentType] shouldContain "text/html"
                response.bodyAsText() shouldContain "Global Parameters"
                response.bodyAsText() shouldContain "loopDelaySeconds"
            }
        }

        "postSettings_SucceedsAndSetsHxRedirectHeader" {
            val serverConfig = AppConfig(
                KrakenCredentials("server-key", "server-secret"),
                Settings(60L, 2.0, 1.0, true, 0.0, 1.0),
                listOf(Allocation("USD", 100.0))
            )
            every { configService.getConfig() } returns serverConfig

            testApplication {
                application {
                    configureTestEnv()
                }
                val response = client.post("/settings") {
                    setBody(
                        parametersOf(
                            "loopDelaySeconds" to listOf("120"),
                            "deviationTriggerPercent" to listOf("3.5"),
                            "dustThresholdUSD" to listOf("2.0"),
                            "fiatMaxDrawdown" to listOf("5.0"),
                            "fiatDeploymentExponent" to listOf("1.5"),
                            "dryRun" to listOf("on"),
                            "symbols" to listOf("USD"),
                            "targets" to listOf("100.0")
                        ).formUrlEncode()
                    )
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                }
                response.status shouldBe HttpStatusCode.OK
                response.headers["HX-Redirect"] shouldBe "/"
            }

            verify { configService.updateConfig(any()) }
        }

        "postSettings_OnValidationError_ReturnsErrorHtmlBody" {
            val serverConfig = AppConfig(
                KrakenCredentials("server-key", "server-secret"),
                Settings(60L, 2.0, 1.0, true, 0.0, 1.0),
                listOf(Allocation("USD", 100.0))
            )
            every { configService.getConfig() } returns serverConfig
            every { configService.updateConfig(any()) } throws InvalidConfigurationException("Total allocation percentage must be exactly 100%.")

            testApplication {
                application {
                    configureTestEnv()
                }
                val response = client.post("/settings") {
                    setBody(
                        parametersOf(
                            "loopDelaySeconds" to listOf("60"),
                            "deviationTriggerPercent" to listOf("2.0"),
                            "dustThresholdUSD" to listOf("1.0"),
                            "symbols" to listOf("USD"),
                            "targets" to listOf("90.0") // sum != 100
                        ).formUrlEncode()
                    )
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                }
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain "Total allocation percentage must be exactly 100%."
            }
        }

        "getStaticResource_ReturnsCssFile" {
            testApplication {
                application {
                    configureTestEnv()
                }
                val response = client.get("/static/style.css")
                response.status shouldBe HttpStatusCode.OK
                response.headers[HttpHeaders.ContentType] shouldContain "text/css"
            }
        }

        "postSettings_WithMissingOrInvalidParams_UsesDefaultsAndHandlesValidation" {
            val serverConfig = AppConfig(
                KrakenCredentials("server-key", "server-secret"),
                Settings(60L, 2.0, 1.0, true, 0.0, 1.0),
                listOf(Allocation("USD", 100.0))
            )
            every { configService.getConfig() } returns serverConfig
            val capturedConfig = slot<AppConfig>()
            every { configService.updateConfig(capture(capturedConfig)) } throws InvalidConfigurationException("Mocked validation error")

            testApplication {
                application {
                    configureTestEnv()
                }
                val response = client.post("/settings") {
                    setBody(
                        parametersOf(
                            "loopDelaySeconds" to listOf("invalid"),
                            "deviationTriggerPercent" to listOf("invalid"),
                            "dustThresholdUSD" to listOf("invalid"),
                            "fiatMaxDrawdown" to listOf("invalid"),
                            "fiatDeploymentExponent" to listOf("invalid"),
                            // "dryRun" is absent, meaning false
                            "symbols" to listOf("BTC", "ETH"),
                            "targets" to listOf("invalid", "30.0")
                        ).formUrlEncode()
                    )
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                }
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain "Mocked validation error"
            }

            capturedConfig.captured.settings.loopDelaySeconds shouldBe 60L
            capturedConfig.captured.settings.deviationTriggerPercent shouldBe 2.0
            capturedConfig.captured.settings.dustThresholdUSD shouldBe 1.0
            capturedConfig.captured.settings.dryRun shouldBe false
            capturedConfig.captured.settings.fiatMaxDrawdown shouldBe 0.0
            capturedConfig.captured.settings.fiatDeploymentExponent shouldBe 1.0
            capturedConfig.captured.allocations.size shouldBe 2
            capturedConfig.captured.allocations[0].symbol shouldBe "BTC"
            capturedConfig.captured.allocations[0].targetPercent shouldBe 0.0
            capturedConfig.captured.allocations[1].symbol shouldBe "ETH"
            capturedConfig.captured.allocations[1].targetPercent shouldBe 30.0
        }

        "postSettings_WithAbsentParamsAndNullErrorMessage_UsesDefaultsAndFallbackMessage" {
            val serverConfig = AppConfig(
                KrakenCredentials("server-key", "server-secret"),
                Settings(60L, 2.0, 1.0, true, 0.0, 1.0),
                listOf(Allocation("USD", 100.0))
            )
            every { configService.getConfig() } returns serverConfig
            val capturedConfig = slot<AppConfig>()
            every { configService.updateConfig(capture(capturedConfig)) } throws InvalidConfigurationException(null)

            testApplication {
                application {
                    configureTestEnv()
                }
                val response = client.post("/settings") {
                    setBody(
                        parametersOf().formUrlEncode()
                    )
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                }
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain "Invalid configuration"
            }

            capturedConfig.captured.settings.loopDelaySeconds shouldBe 60L
            capturedConfig.captured.settings.deviationTriggerPercent shouldBe 2.0
            capturedConfig.captured.settings.dustThresholdUSD shouldBe 1.0
            capturedConfig.captured.settings.dryRun shouldBe false
            capturedConfig.captured.settings.fiatMaxDrawdown shouldBe 0.0
            capturedConfig.captured.settings.fiatDeploymentExponent shouldBe 1.0
            capturedConfig.captured.allocations shouldBe emptyList()
        }

        "sseStatusStream_EmitsInitialAndFlowSnapshots" {
            val snapshot1 = PortfolioSnapshot(
                Instant.now(),
                BigDecimal("10000.0"),
                emptyMap(),
                emptyList(),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO
            )
            val snapshot2 = PortfolioSnapshot(
                Instant.now().plusSeconds(60),
                BigDecimal("12000.0"),
                emptyMap(),
                emptyList(),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO
            )

            every { tradeHistoryService.getLatestSnapshot() } returns snapshot1
            every { tradeHistoryService.getHistoryFlow() } returns kotlinx.coroutines.flow.flowOf(snapshot2)

            testApplication {
                val client = createClient {
                    install(SSE)
                }
                application {
                    configureTestEnv()
                }
                client.sse("/api/status/stream") {
                    val events = incoming.take(2).toList()
                    events[0].data shouldBe objectMapper.writeValueAsString(snapshot1)
                    events[1].data shouldBe objectMapper.writeValueAsString(snapshot2)
                }
            }
        }

        "sseStatusStream_HandlesCancellationException" {
            every { tradeHistoryService.getLatestSnapshot() } returns null
            every { tradeHistoryService.getHistoryFlow() } returns kotlinx.coroutines.flow.flow {
                throw kotlinx.coroutines.CancellationException("Simulated cancel")
            }

            testApplication {
                val client = createClient {
                    install(SSE)
                }
                application {
                    configureTestEnv()
                }
                try {
                    client.sse("/api/status/stream") {
                        incoming.collect {}
                    }
                } catch (e: Exception) {
                    // Expect cancellation exception or channel close
                }
            }
        }

        "sseStatusStream_HandlesGenericExceptionGracefully" {
            every { tradeHistoryService.getLatestSnapshot() } returns null
            every { tradeHistoryService.getHistoryFlow() } returns kotlinx.coroutines.flow.flow {
                throw RuntimeException("Simulated error")
            }

            testApplication {
                val client = createClient {
                    install(SSE)
                }
                application {
                    configureTestEnv()
                }
                client.sse("/api/status/stream") {
                    val events = incoming.toList()
                    events.isEmpty() shouldBe true
                }
            }
        }
    }
}
