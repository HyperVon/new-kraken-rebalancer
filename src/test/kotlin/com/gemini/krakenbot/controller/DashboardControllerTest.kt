package com.gemini.krakenbot.controller

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.*
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.TradeHistoryService
import com.gemini.krakenbot.util.KrakenSymbols
import com.gemini.krakenbot.view.DashboardView
import com.gemini.krakenbot.view.component.*
import com.gemini.krakenbot.view.util.FormFields
import com.gemini.krakenbot.view.util.HtmxHeaders
import com.gemini.krakenbot.view.util.Routes
import com.gemini.krakenbot.view.util.ViewText
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.plugins.sse.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.testing.*
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.math.BigDecimal
import java.time.Instant

class DashboardControllerTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    private val tradeHistoryService = mockk<TradeHistoryService>(relaxed = true)
    private val configService = mockk<ConfigService>(relaxed = true)
    private val objectMapper =
        jacksonObjectMapper().registerModule(JavaTimeModule())

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
            single { DashboardFragmentComponent(
                get(),
                get(),
                get(),
                get()
            ) }
            single { DashboardView(
                get(),
                get(),
                get()
            ) }
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
                val response = client.get(Routes.ROOT)
                response.status shouldBe HttpStatusCode.OK
                response.headers[HttpHeaders.ContentType] shouldContain "text/html"
                response.bodyAsText() shouldContain ViewText.APP_TITLE
                response.bodyAsText() shouldContain "sse-connect=\"${Routes.API_STATUS_STREAM}\""
            }
        }

        "getDashboardFragment_NoSnapshot_ReturnsWaitingMessage" {
            every { tradeHistoryService.getLatestSnapshot() } returns null

            testApplication {
                application {
                    configureTestEnv()
                }
                val response = client.get(Routes.FRAGMENT_DASHBOARD)
                response.status shouldBe HttpStatusCode.OK
                response.headers[HttpHeaders.ContentType] shouldContain "text/html"
                response.bodyAsText() shouldContain ViewText.WAITING_FIRST_CYCLE
            }
        }

        "getDashboardFragment_WithSnapshot_ReturnsPopulatedHtml" {
            val nowTime = Instant.now()
            val snapshot = PortfolioSnapshot(
                nowTime,
                BigDecimal("15000.00"),
                mapOf(
                    KrakenSymbols.USD to PortfolioSnapshot.AssetSnapshot(
                        KrakenSymbols.USD,
                        BigDecimal("5000.0"),
                        BigDecimal("1.0"),
                        BigDecimal("5000.0"),
                        BigDecimal("33.33"),
                        BigDecimal("33.33"),
                        BigDecimal("0.0"),
                        BigDecimal("0.0")
                    ),
                    KrakenSymbols.BTC to PortfolioSnapshot.AssetSnapshot(
                        KrakenSymbols.BTC,
                        BigDecimal("0.1"),
                        BigDecimal("50000.0"),
                        BigDecimal("5000.0"),
                        BigDecimal("33.33"),
                        BigDecimal("33.33"),
                        BigDecimal("5.0"),
                        BigDecimal("250.0")
                    ),
                    KrakenSymbols.ETH to PortfolioSnapshot.AssetSnapshot(
                        KrakenSymbols.ETH,
                        BigDecimal("2.5"),
                        BigDecimal("2000.0"),
                        BigDecimal("5000.0"),
                        BigDecimal("33.33"),
                        BigDecimal("33.33"),
                        BigDecimal("-2.0"),
                        BigDecimal("-100.0")
                    )
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
                val response = client.get(Routes.FRAGMENT_DASHBOARD)
                response.status shouldBe HttpStatusCode.OK
                response.headers[HttpHeaders.ContentType] shouldContain "text/html"

                val body = response.bodyAsText()
                body shouldContain ViewText.TOTAL_PORTFOLIO
                body shouldContain ViewText.CASH_USD
                body shouldContain ViewText.CRYPTO_ASSETS
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
                Settings(
                    60L,
                    2.0,
                    1.0,
                    true,
                    0.0,
                    1.0
                ),
                listOf(Allocation(
                    KrakenSymbols.USD,
                    100.0
                ))
            )
            every { configService.getConfig() } returns config

            testApplication {
                application {
                    configureTestEnv()
                }
                val response = client.get(Routes.SETTINGS)
                response.status shouldBe HttpStatusCode.OK
                response.headers[HttpHeaders.ContentType] shouldContain "text/html"
                response.bodyAsText() shouldContain ViewText.GLOBAL_PARAMETERS
                response.bodyAsText() shouldContain FormFields.LOOP_DELAY_SECONDS
            }
        }

        "postSettings_SucceedsAndSetsHxRedirectHeader" {
            val serverConfig = AppConfig(
                KrakenCredentials(
                    TestFixtures.TEST_SERVER_API_KEY,
                    TestFixtures.TEST_SERVER_API_SECRET
                ),
                Settings(
                    60L,
                    2.0,
                    1.0,
                    true,
                    0.0,
                    1.0
                ),
                listOf(Allocation(
                    KrakenSymbols.USD,
                    100.0
                ))
            )
            every { configService.getConfig() } returns serverConfig

            testApplication {
                application {
                    configureTestEnv()
                }
                val response = client.post(Routes.SETTINGS) {
                    setBody(
                        parametersOf(
                            FormFields.LOOP_DELAY_SECONDS to listOf("120"),
                            FormFields.DEVIATION_TRIGGER_PERCENT to listOf("3.5"),
                            FormFields.DUST_THRESHOLD_USD to listOf("2.0"),
                            FormFields.FIAT_MAX_DRAWDOWN to listOf("5.0"),
                            FormFields.FIAT_DEPLOYMENT_EXPONENT to listOf("1.5"),
                            FormFields.DRY_RUN to listOf("on"),
                            FormFields.SYMBOLS to listOf(KrakenSymbols.USD),
                            FormFields.TARGETS to listOf("100.0")
                        ).formUrlEncode()
                    )
                    header(
                        HttpHeaders.ContentType,
                        ContentType.Application.FormUrlEncoded.toString()
                    )
                }
                response.status shouldBe HttpStatusCode.OK
                response.headers[HtmxHeaders.HX_REDIRECT] shouldBe Routes.ROOT
            }

            verify { configService.updateConfig(any()) }
        }

        "postSettings_OnValidationError_ReturnsErrorHtmlBody" {
            val serverConfig = AppConfig(
                KrakenCredentials(
                    TestFixtures.TEST_SERVER_API_KEY,
                    TestFixtures.TEST_SERVER_API_SECRET
                ),
                Settings(
                    60L,
                    2.0,
                    1.0,
                    true,
                    0.0,
                    1.0
                ),
                listOf(Allocation(
                    KrakenSymbols.USD,
                    100.0
                ))
            )
            every { configService.getConfig() } returns serverConfig
            every { configService.updateConfig(any()) } throws InvalidConfigurationException(
                "Total allocation percentage must be exactly 100%."
            )

            testApplication {
                application {
                    configureTestEnv()
                }
                val response = client.post(Routes.SETTINGS) {
                    setBody(
                        parametersOf(
                            FormFields.LOOP_DELAY_SECONDS to listOf("60"),
                            FormFields.DEVIATION_TRIGGER_PERCENT to listOf("2.0"),
                            FormFields.DUST_THRESHOLD_USD to listOf("1.0"),
                            FormFields.SYMBOLS to listOf(KrakenSymbols.USD),
                            FormFields.TARGETS to listOf("90.0") // sum != 100
                        ).formUrlEncode()
                    )
                    header(
                        HttpHeaders.ContentType,
                        ContentType.Application.FormUrlEncoded.toString()
                    )
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
                val response = client.get(Routes.STATIC_STYLE_CSS)
                response.status shouldBe HttpStatusCode.OK
                response.headers[HttpHeaders.ContentType] shouldContain "text/css"
            }
        }

        "getStaticResource_ReturnsJsFile" {
            testApplication {
                application {
                    configureTestEnv()
                }
                val response = client.get(Routes.STATIC_DASHBOARD_JS)
                response.status shouldBe HttpStatusCode.OK
                response.headers[HttpHeaders.ContentType] shouldContain "javascript"
            }
        }

        "getStaticResource_ReturnsSettingsJsFile" {
            testApplication {
                application {
                    configureTestEnv()
                }
                val response = client.get(Routes.STATIC_SETTINGS_JS)
                response.status shouldBe HttpStatusCode.OK
                response.headers[HttpHeaders.ContentType] shouldContain "javascript"
            }
        }

        "postSettings_WithMissingOrInvalidParams_UsesDefaultsAndHandlesValidation" {
            val serverConfig = AppConfig(
                KrakenCredentials(
                    TestFixtures.TEST_SERVER_API_KEY,
                    TestFixtures.TEST_SERVER_API_SECRET
                ),
                Settings(
                    60L,
                    2.0,
                    1.0,
                    true,
                    0.0,
                    1.0
                ),
                listOf(Allocation(
                    KrakenSymbols.USD,
                    100.0
                ))
            )
            every { configService.getConfig() } returns serverConfig
            val capturedConfig = slot<AppConfig>()
            every {
                configService.updateConfig(capture(capturedConfig))
            } throws InvalidConfigurationException(
                "Mocked validation error"
            )

            testApplication {
                application {
                    configureTestEnv()
                }
                val response = client.post(Routes.SETTINGS) {
                    setBody(
                        parametersOf(
                            FormFields.LOOP_DELAY_SECONDS to listOf("invalid"),
                            FormFields.DEVIATION_TRIGGER_PERCENT to listOf("invalid"),
                            FormFields.DUST_THRESHOLD_USD to listOf("invalid"),
                            FormFields.FIAT_MAX_DRAWDOWN to listOf("invalid"),
                            FormFields.FIAT_DEPLOYMENT_EXPONENT to listOf("invalid"),
                            // "dryRun" is absent, meaning false
                            FormFields.SYMBOLS to listOf(
                                KrakenSymbols.BTC,
                                KrakenSymbols.ETH
                            ),
                            FormFields.TARGETS to listOf("invalid", "30.0")
                        ).formUrlEncode()
                    )
                    header(
                        HttpHeaders.ContentType,
                        ContentType.Application.FormUrlEncoded.toString()
                    )
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
            capturedConfig.captured.allocations[0].symbol shouldBe KrakenSymbols.BTC
            capturedConfig.captured.allocations[0].targetPercent shouldBe 0.0
            capturedConfig.captured.allocations[1].symbol shouldBe KrakenSymbols.ETH
            capturedConfig.captured.allocations[1].targetPercent shouldBe 30.0
        }

        "postSettings_WithAbsentParamsAndNullErrorMessage_UsesDefaultsAndFallbackMessage" {
            val serverConfig = AppConfig(
                KrakenCredentials(
                    TestFixtures.TEST_SERVER_API_KEY,
                    TestFixtures.TEST_SERVER_API_SECRET
                ),
                Settings(
                    60L,
                    2.0,
                    1.0,
                    true,
                    0.0,
                    1.0
                ),
                listOf(Allocation(
                    KrakenSymbols.USD,
                    100.0
                ))
            )
            every { configService.getConfig() } returns serverConfig
            val capturedConfig = slot<AppConfig>()
            every {
                configService.updateConfig(capture(capturedConfig))
            } throws InvalidConfigurationException(
                null
            )

            testApplication {
                application {
                    configureTestEnv()
                }
                val response = client.post(Routes.SETTINGS) {
                    setBody(
                        parametersOf().formUrlEncode()
                    )
                    header(
                        HttpHeaders.ContentType,
                        ContentType.Application.FormUrlEncoded.toString()
                    )
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
            every { tradeHistoryService.getHistoryFlow() } returns flowOf(snapshot2)

            testApplication {
                val client = createClient {
                    install(SSE)
                }
                application {
                    configureTestEnv()
                }
                client.sse(Routes.API_STATUS_STREAM) {
                    val events = incoming.take(2).toList()
                    events[0].data shouldBe objectMapper.writeValueAsString(
                        snapshot1
                    )
                    events[1].data shouldBe objectMapper.writeValueAsString(
                        snapshot2
                    )
                }
            }
        }

        "sseStatusStream_HandlesCancellationException" {
            every { tradeHistoryService.getLatestSnapshot() } returns null
            every { tradeHistoryService.getHistoryFlow() } returns flow {
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
                    client.sse(Routes.API_STATUS_STREAM) {
                        incoming.collect {}
                    }
                } catch (_: Exception) {
                    // Expect cancellation exception or channel close
                }
            }
        }

        "sseStatusStream_HandlesGenericExceptionGracefully" {
            every { tradeHistoryService.getLatestSnapshot() } returns null
            every { tradeHistoryService.getHistoryFlow() } returns flow {
                throw RuntimeException("Simulated error")
            }

            testApplication {
                val client = createClient {
                    install(SSE)
                }
                application {
                    configureTestEnv()
                }
                client.sse(Routes.API_STATUS_STREAM) {
                    val events = incoming.toList()
                    events.isEmpty() shouldBe true
                }
            }
        }
    }
}
