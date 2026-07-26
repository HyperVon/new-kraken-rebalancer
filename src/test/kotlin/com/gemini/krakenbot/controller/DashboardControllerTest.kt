package com.gemini.krakenbot.controller

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.*
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.HistoryStats
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.TimeRange
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.TradeHistoryService
import com.gemini.krakenbot.view.DashboardView
import com.gemini.krakenbot.view.component.*
import com.gemini.krakenbot.view.util.FormFields
import com.gemini.krakenbot.view.util.HtmxHeaders
import com.gemini.krakenbot.view.util.Routes
import com.gemini.krakenbot.view.util.ViewText
import com.gemini.krakenbot.view.util.withRange
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
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.math.BigDecimal
import java.time.Instant
import io.ktor.client.plugins.sse.SSE as ClientSSE
import io.ktor.server.sse.SSE as ServerSSE

class DashboardControllerTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    private val tradeHistoryService = mockk<TradeHistoryService>(relaxed = true)
    private val configService = mockk<ConfigService>(relaxed = true)
    private val objectMapper =
        jacksonObjectMapper().registerModule(JavaTimeModule())

    private fun Application.configureTestEnv() {
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
                single { HistoryPageComponent() }
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

        "getDashboardShell_ReturnsHtml" {
            every { configService.getConfig() } returns AppConfig(
                KrakenCredentials(apiKey = TestFixtures.TEST_API_KEY, privateKey = "k"),
                Settings(
                    loopDelaySeconds = 60L,
                    deviationTriggerPercent = 2.0,
                    dustThresholdUSD = 5.0,
                    dryRun = true,
                    fiatMaxDrawdown = 0.0,
                    fiatDeploymentExponent = 1.0,
                ),
                listOf(Allocation(Asset.USD, 100.0)),
            )
            testApplication {
                application {
                    configureTestEnv()
                }
                val response = client.get(Routes.ROOT)
                response.status shouldBe HttpStatusCode.OK
                response.headers[HttpHeaders.ContentType] shouldContain TestFixtures.TEXT_HTML
                response.bodyAsText() shouldContain ViewText.APP_TITLE
                response.bodyAsText() shouldContain "sse-connect=\"${Routes.API_STATUS_STREAM}\""
                response.bodyAsText() shouldContain ViewText.MODE_DRY_RUN
            }
        }

        "getDashboardFragment_NoSnapshot_ReturnsWaitingMessage" {
            coEvery { tradeHistoryService.getLatestSnapshot() } returns null

            testApplication {
                application {
                    configureTestEnv()
                }
                val response = client.get(Routes.FRAGMENT_DASHBOARD)
                response.status shouldBe HttpStatusCode.OK
                response.headers[HttpHeaders.ContentType] shouldContain TestFixtures.TEXT_HTML
                response.bodyAsText() shouldContain ViewText.WAITING_FIRST_CYCLE
            }
        }

        "getDashboardFragment_WithSnapshot_ReturnsPopulatedHtml" {
            val nowTime = Instant.now()
            val snapshot =
                PortfolioSnapshot(
                    timestamp = nowTime,
                    totalValueUSD = BigDecimal("15000.00"),
                    assets =
                    mapOf(
                        Asset.USD to
                            PortfolioSnapshot.AssetSnapshot(
                                symbol = Asset.USD,
                                balance = BigDecimal("5000.0"),
                                price = BigDecimal("1.0"),
                                valueUSD = BigDecimal("5000.0"),
                                targetPercent = BigDecimal("33.33"),
                                currentPercent = BigDecimal("33.33"),
                                deviationPercent = BigDecimal("0.0"),
                                deviationUSD = BigDecimal("0.0"),
                            ),
                        Asset.BTC to
                            PortfolioSnapshot.AssetSnapshot(
                                symbol = Asset.BTC,
                                balance = BigDecimal("0.1"),
                                price = BigDecimal("50000.0"),
                                valueUSD = BigDecimal("5000.0"),
                                targetPercent = BigDecimal("33.33"),
                                currentPercent = BigDecimal("33.33"),
                                deviationPercent = BigDecimal("5.0"),
                                deviationUSD = BigDecimal("250.0"),
                            ),
                        Asset.ETH to
                            PortfolioSnapshot.AssetSnapshot(
                                symbol = Asset.ETH,
                                balance = BigDecimal("2.5"),
                                price = BigDecimal("2000.0"),
                                valueUSD = BigDecimal("5000.0"),
                                targetPercent = BigDecimal("33.33"),
                                currentPercent = BigDecimal("33.33"),
                                deviationPercent = BigDecimal("-2.0"),
                                deviationUSD = BigDecimal("-100.0"),
                            ),
                    ),
                    actions = listOf("BUY BTC 0.1"),
                    drawdownPercent = BigDecimal.ZERO,
                    fiatDeploymentPercent = BigDecimal.ZERO,
                    effectiveUsdTargetPercent = BigDecimal("33.33"),
                )
            coEvery { tradeHistoryService.getLatestSnapshot() } returns snapshot
            coEvery { tradeHistoryService.getHistory() } returns listOf(snapshot)

            testApplication {
                application {
                    configureTestEnv()
                }
                val response = client.get(Routes.FRAGMENT_DASHBOARD)
                response.status shouldBe HttpStatusCode.OK
                response.headers[HttpHeaders.ContentType] shouldContain TestFixtures.TEXT_HTML

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
            val config =
                AppConfig(
                    kraken = KrakenCredentials("real-api-key", "real-private-key"),
                    settings =
                    Settings(
                        loopDelaySeconds = 60L,
                        deviationTriggerPercent = 2.0,
                        dustThresholdUSD = 1.0,
                        dryRun = true,
                        fiatMaxDrawdown = 0.0,
                        fiatDeploymentExponent = 1.0,
                    ),
                    allocations =
                    listOf(
                        Allocation(
                            symbol = Asset.USD,
                            targetPercent = 100.0,
                        ),
                    ),
                )
            every { configService.getConfig() } returns config

            testApplication {
                application {
                    configureTestEnv()
                }
                val response = client.get(Routes.SETTINGS)
                response.status shouldBe HttpStatusCode.OK
                response.headers[HttpHeaders.ContentType] shouldContain TestFixtures.TEXT_HTML
                response.bodyAsText() shouldContain ViewText.GLOBAL_PARAMETERS
                response.bodyAsText() shouldContain FormFields.LOOP_DELAY_SECONDS
            }
        }

        "postSettings_SucceedsAndSetsHxRedirectHeader" {
            val serverConfig =
                AppConfig(
                    kraken =
                    KrakenCredentials(
                        apiKey = TestFixtures.TEST_SERVER_API_KEY,
                        privateKey = TestFixtures.TEST_SERVER_API_SECRET,
                    ),
                    settings =
                    Settings(
                        loopDelaySeconds = 60L,
                        deviationTriggerPercent = 2.0,
                        dustThresholdUSD = 1.0,
                        dryRun = true,
                        fiatMaxDrawdown = 0.0,
                        fiatDeploymentExponent = 1.0,
                    ),
                    allocations =
                    listOf(
                        Allocation(
                            symbol = Asset.USD,
                            targetPercent = 100.0,
                        ),
                    ),
                )
            val captured = slot<AppConfig>()
            every { configService.getConfig() } returns serverConfig
            every { configService.updateConfig(capture(captured)) } returns Unit

            testApplication {
                application {
                    configureTestEnv()
                }
                val response =
                    client.post(Routes.SETTINGS) {
                        setBody(
                            parametersOf(
                                FormFields.LOOP_DELAY_SECONDS to listOf("120"),
                                FormFields.DEVIATION_TRIGGER_PERCENT to listOf("3.5"),
                                FormFields.DUST_THRESHOLD_USD to listOf("2.0"),
                                FormFields.FIAT_MAX_DRAWDOWN to listOf("5.0"),
                                FormFields.FIAT_DEPLOYMENT_EXPONENT to listOf("1.5"),
                                FormFields.DRY_RUN to listOf("on"),
                                FormFields.SIMULATION to listOf("on"),
                                FormFields.SYMBOLS to listOf(Asset.USD),
                                FormFields.TARGETS to listOf("100.0"),
                            ).formUrlEncode(),
                        )
                        header(
                            HttpHeaders.ContentType,
                            ContentType.Application.FormUrlEncoded.toString(),
                        )
                    }
                response.status shouldBe HttpStatusCode.OK
                response.headers[HtmxHeaders.HX_REDIRECT] shouldBe Routes.ROOT
            }

            captured.captured.settings.simulation shouldBe true
            verify { configService.updateConfig(any()) }
        }

        "postSettings_OnValidationError_ReturnsErrorHtmlBody" {
            val serverConfig =
                AppConfig(
                    kraken =
                    KrakenCredentials(
                        apiKey = TestFixtures.TEST_SERVER_API_KEY,
                        privateKey = TestFixtures.TEST_SERVER_API_SECRET,
                    ),
                    settings =
                    Settings(
                        loopDelaySeconds = 60L,
                        deviationTriggerPercent = 2.0,
                        dustThresholdUSD = 1.0,
                        dryRun = true,
                        fiatMaxDrawdown = 0.0,
                        fiatDeploymentExponent = 1.0,
                    ),
                    allocations =
                    listOf(
                        Allocation(
                            symbol = Asset.USD,
                            targetPercent = 100.0,
                        ),
                    ),
                )
            every { configService.getConfig() } returns serverConfig
            every { configService.updateConfig(any()) } throws
                InvalidConfigurationException(
                    "Total allocation percentage must be exactly 100%.",
                )

            testApplication {
                application {
                    configureTestEnv()
                }
                val response =
                    client.post(Routes.SETTINGS) {
                        setBody(
                            parametersOf(
                                FormFields.LOOP_DELAY_SECONDS to listOf("60"),
                                FormFields.DEVIATION_TRIGGER_PERCENT to listOf("2.0"),
                                FormFields.DUST_THRESHOLD_USD to listOf("1.0"),
                                FormFields.SYMBOLS to listOf(Asset.USD),
                                FormFields.TARGETS to listOf("90.0"), // sum != 100
                            ).formUrlEncode(),
                        )
                        header(
                            HttpHeaders.ContentType,
                            ContentType.Application.FormUrlEncoded.toString(),
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

        "getStaticResource_ReturnsRebalancerJsFile" {
            testApplication {
                application {
                    configureTestEnv()
                }
                val response = client.get(Routes.STATIC_REBALANCER_JS)
                response.status shouldBe HttpStatusCode.OK
                response.headers[HttpHeaders.ContentType] shouldContain "javascript"
            }
        }

        "postSettings_WithMissingOrInvalidParams_UsesDefaultsAndHandlesValidation" {
            val serverConfig =
                AppConfig(
                    kraken =
                    KrakenCredentials(
                        apiKey = TestFixtures.TEST_SERVER_API_KEY,
                        privateKey = TestFixtures.TEST_SERVER_API_SECRET,
                    ),
                    settings =
                    Settings(
                        loopDelaySeconds = 60L,
                        deviationTriggerPercent = 2.0,
                        dustThresholdUSD = 1.0,
                        dryRun = true,
                        fiatMaxDrawdown = 0.0,
                        fiatDeploymentExponent = 1.0,
                    ),
                    allocations =
                    listOf(
                        Allocation(
                            symbol = Asset.USD,
                            targetPercent = 100.0,
                        ),
                    ),
                )
            every { configService.getConfig() } returns serverConfig
            val capturedConfig = slot<AppConfig>()
            every {
                configService.updateConfig(capture(capturedConfig))
            } throws
                InvalidConfigurationException(
                    "Mocked validation error",
                )

            testApplication {
                application {
                    configureTestEnv()
                }
                val response =
                    client.post(Routes.SETTINGS) {
                        setBody(
                            parametersOf(
                                FormFields.LOOP_DELAY_SECONDS to listOf(TestFixtures.INVALID),
                                FormFields.DEVIATION_TRIGGER_PERCENT to listOf(TestFixtures.INVALID),
                                FormFields.DUST_THRESHOLD_USD to listOf(TestFixtures.INVALID),
                                FormFields.FIAT_MAX_DRAWDOWN to listOf(TestFixtures.INVALID),
                                FormFields.FIAT_DEPLOYMENT_EXPONENT to listOf(TestFixtures.INVALID),
                                // "dryRun" is absent, meaning false
                                FormFields.SYMBOLS to
                                    listOf(
                                        Asset.BTC,
                                        Asset.ETH,
                                    ),
                                FormFields.TARGETS to listOf(TestFixtures.INVALID, "30.0"),
                            ).formUrlEncode(),
                        )
                        header(
                            HttpHeaders.ContentType,
                            ContentType.Application.FormUrlEncoded.toString(),
                        )
                    }
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain "Mocked validation error"
            }

            capturedConfig.captured.settings.loopDelaySeconds shouldBe 60L
            capturedConfig.captured.settings.deviationTriggerPercent shouldBe 5.0
            capturedConfig.captured.settings.dustThresholdUSD shouldBe 5.0
            capturedConfig.captured.settings.dryRun shouldBe false
            capturedConfig.captured.settings.fiatMaxDrawdown shouldBe 0.0
            capturedConfig.captured.settings.fiatDeploymentExponent shouldBe 1.0
            capturedConfig.captured.allocations.size shouldBe 2
            capturedConfig.captured.allocations[0]
                .symbol.value shouldBe Asset.BTC
            capturedConfig.captured.allocations[0].targetPercent shouldBe 0.0
            capturedConfig.captured.allocations[1]
                .symbol.value shouldBe Asset.ETH
            capturedConfig.captured.allocations[1].targetPercent shouldBe 30.0
        }

        "postSettings_WithAbsentParamsAndNullErrorMessage_UsesDefaultsAndFallbackMessage" {
            val serverConfig =
                AppConfig(
                    kraken =
                    KrakenCredentials(
                        apiKey = TestFixtures.TEST_SERVER_API_KEY,
                        privateKey = TestFixtures.TEST_SERVER_API_SECRET,
                    ),
                    settings =
                    Settings(
                        loopDelaySeconds = 60L,
                        deviationTriggerPercent = 2.0,
                        dustThresholdUSD = 1.0,
                        dryRun = true,
                        fiatMaxDrawdown = 0.0,
                        fiatDeploymentExponent = 1.0,
                    ),
                    allocations =
                    listOf(
                        Allocation(
                            symbol = Asset.USD,
                            targetPercent = 100.0,
                        ),
                    ),
                )
            every { configService.getConfig() } returns serverConfig
            val capturedConfig = slot<AppConfig>()
            every {
                configService.updateConfig(capture(capturedConfig))
            } throws
                InvalidConfigurationException(
                    null,
                )

            testApplication {
                application {
                    configureTestEnv()
                }
                val response =
                    client.post(Routes.SETTINGS) {
                        setBody(
                            parametersOf().formUrlEncode(),
                        )
                        header(
                            HttpHeaders.ContentType,
                            ContentType.Application.FormUrlEncoded.toString(),
                        )
                    }
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain "Invalid configuration"
            }

            capturedConfig.captured.settings.loopDelaySeconds shouldBe 60L
            capturedConfig.captured.settings.deviationTriggerPercent shouldBe 5.0
            capturedConfig.captured.settings.dustThresholdUSD shouldBe 5.0
            capturedConfig.captured.settings.dryRun shouldBe false
            capturedConfig.captured.settings.fiatMaxDrawdown shouldBe 0.0
            capturedConfig.captured.settings.fiatDeploymentExponent shouldBe 1.0
            capturedConfig.captured.allocations shouldBe emptyList()
        }

        "sseStatusStream_EmitsInitialAndFlowSnapshots" {
            val snapshot1 =
                PortfolioSnapshot(
                    timestamp = Instant.now(),
                    totalValueUSD = BigDecimal("10000.0"),
                    assets = emptyMap(),
                    actions = emptyList(),
                    drawdownPercent = BigDecimal.ZERO,
                    fiatDeploymentPercent = BigDecimal.ZERO,
                    effectiveUsdTargetPercent = BigDecimal.ZERO,
                )
            val snapshot2 =
                PortfolioSnapshot(
                    timestamp = Instant.now().plusSeconds(60),
                    totalValueUSD = BigDecimal("12000.0"),
                    assets = emptyMap(),
                    actions = emptyList(),
                    drawdownPercent = BigDecimal.ZERO,
                    fiatDeploymentPercent = BigDecimal.ZERO,
                    effectiveUsdTargetPercent = BigDecimal.ZERO,
                )

            coEvery { tradeHistoryService.getLatestSnapshot() } returns snapshot1
            every { tradeHistoryService.getHistoryFlow() } returns
                flowOf(
                    snapshot2,
                )

            testApplication {
                val client =
                    createClient {
                        install(ClientSSE)
                    }
                application {
                    configureTestEnv()
                }
                client.sse(Routes.API_STATUS_STREAM) {
                    val events = incoming.take(2).toList()
                    events[0].data shouldBe
                        objectMapper.writeValueAsString(
                            snapshot1,
                        )
                    events[1].data shouldBe
                        objectMapper.writeValueAsString(
                            snapshot2,
                        )
                }
            }
        }

        "sseStatusStream_HandlesCancellationException" {
            coEvery { tradeHistoryService.getLatestSnapshot() } returns null
            every { tradeHistoryService.getHistoryFlow() } returns
                flow {
                    throw CancellationException("Simulated cancel")
                }

            testApplication {
                val client =
                    createClient {
                        install(ClientSSE)
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
            coEvery { tradeHistoryService.getLatestSnapshot() } returns null
            every { tradeHistoryService.getHistoryFlow() } returns
                flow {
                    throw RuntimeException("Simulated error")
                }

            testApplication {
                val client =
                    createClient {
                        install(ClientSSE)
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

        "getHistoryPage_ReturnsHtml" {
            testApplication {
                application {
                    configureTestEnv()
                }
                val response = client.get(Routes.HISTORY)
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain "History - Kraken Rebalancer"
            }
        }

        "getApiHistorySnapshots_ReturnsJson" {
            coEvery { tradeHistoryService.getSnapshotsInRange(any(), any()) } returns emptyList()
            testApplication {
                application {
                    configureTestEnv()
                }
                val response = client.get(Routes.API_HISTORY_SNAPSHOTS.withRange("24h"))
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldBe "[]"
            }
        }

        "getApiHistoryTrades_ReturnsJson" {
            coEvery { tradeHistoryService.getTradesInRange(any(), any()) } returns emptyList()
            testApplication {
                application {
                    configureTestEnv()
                }
                val response = client.get(Routes.API_HISTORY_TRADES.withRange(TimeRange.ALL))
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldBe "[]"
            }
        }

        "getApiHistoryStats_ReturnsJson" {
            val stats =
                HistoryStats(
                    allTimeHigh = BigDecimal("15000.00"),
                    totalTradesExecuted = 12L,
                    totalVolumeTraded = BigDecimal("50000.00"),
                    totalFeesPaid = BigDecimal("25.50"),
                    latestSnapshotTime = Instant.now(),
                )
            coEvery { tradeHistoryService.getHistoryStats(any(), any()) } returns stats
            testApplication {
                application {
                    configureTestEnv()
                }
                val response = client.get(Routes.API_HISTORY_STATS.withRange(TimeRange.SEVEN_DAYS))
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain "\"allTimeHigh\":15000.00"
            }
        }

        "getApiHistoryStats_NoRangeParam_UsesNoArgGetHistoryStats" {
            val stats =
                HistoryStats(
                    allTimeHigh = BigDecimal("15000.00"),
                    totalTradesExecuted = 12L,
                    totalVolumeTraded = BigDecimal("50000.00"),
                    totalFeesPaid = BigDecimal("25.50"),
                    latestSnapshotTime = Instant.now(),
                )
            // No-arg path only — with-range stub omitted so wrong branch would fail
            coEvery { tradeHistoryService.getHistoryStats() } returns stats
            testApplication {
                application {
                    configureTestEnv()
                }
                val response = client.get(Routes.API_HISTORY_STATS)
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain "\"allTimeHigh\":15000.00"
            }
        }

        "getApiHistorySnapshots_RangeFilters_Branches" {
            coEvery { tradeHistoryService.getSnapshotsInRange(any(), any()) } returns emptyList()
            testApplication {
                application {
                    configureTestEnv()
                }
                // Test "7d" range
                client.get(Routes.API_HISTORY_SNAPSHOTS.withRange(TimeRange.SEVEN_DAYS)).status shouldBe
                    HttpStatusCode.OK
                // Test "30d" range
                client.get(Routes.API_HISTORY_SNAPSHOTS.withRange(TimeRange.THIRTY_DAYS)).status shouldBe
                    HttpStatusCode.OK
                // Test "90d" range
                client.get(Routes.API_HISTORY_SNAPSHOTS.withRange(TimeRange.NINETY_DAYS)).status shouldBe
                    HttpStatusCode.OK
                // Test fallback else range
                client.get(Routes.API_HISTORY_SNAPSHOTS.withRange("invalid")).status shouldBe HttpStatusCode.OK
            }
        }

        "getApiHistorySnapshots_NoRangeParam_DefaultsTo30d" {
            coEvery { tradeHistoryService.getSnapshotsInRange(any(), any()) } returns emptyList()
            testApplication {
                application {
                    configureTestEnv()
                }
                // No range parameter at all — exercises the `?: "30d"` null coalescing
                val response = client.get(Routes.API_HISTORY_SNAPSHOTS)
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldBe "[]"
            }
        }

        "getApiHealth_ReturnsJsonWithStats" {
            val stats =
                HistoryStats(
                    allTimeHigh = BigDecimal("15000.00"),
                    totalTradesExecuted = 12L,
                    totalVolumeTraded = BigDecimal("50000.00"),
                    totalFeesPaid = BigDecimal("25.50"),
                    latestSnapshotTime = Instant.now(),
                )
            val snapshot =
                PortfolioSnapshot(
                    timestamp = Instant.now(),
                    totalValueUSD = BigDecimal("12000.0"),
                    assets = emptyMap(),
                    actions = emptyList(),
                    drawdownPercent = BigDecimal.ZERO,
                    fiatDeploymentPercent = BigDecimal.ZERO,
                    effectiveUsdTargetPercent = BigDecimal.ZERO,
                )
            coEvery { tradeHistoryService.getHistoryStats() } returns stats
            coEvery { tradeHistoryService.getLatestSnapshot() } returns snapshot

            testApplication {
                application {
                    configureTestEnv()
                }
                val response = client.get(Routes.API_HEALTH)
                response.status shouldBe HttpStatusCode.OK
                response.headers[HttpHeaders.ContentType] shouldContain TestFixtures.APPLICATION_JSON
                val body = response.bodyAsText()
                body shouldContain "\"status\":\"UP\""
                body shouldContain "\"totalTradesExecuted\":12"
                body shouldContain "\"totalVolumeTraded\":50000.00"
            }
        }

        "getApiHealth_NoLatestSnapshot_ReturnsJsonWithFallback" {
            val stats =
                HistoryStats(
                    allTimeHigh = BigDecimal("15000.00"),
                    totalTradesExecuted = 12L,
                    totalVolumeTraded = BigDecimal("50000.00"),
                    totalFeesPaid = BigDecimal("25.50"),
                    latestSnapshotTime = Instant.now(),
                )
            coEvery { tradeHistoryService.getHistoryStats() } returns stats
            coEvery { tradeHistoryService.getLatestSnapshot() } returns null

            testApplication {
                application {
                    configureTestEnv()
                }
                val response = client.get(Routes.API_HEALTH)
                response.status shouldBe HttpStatusCode.OK
                val body = response.bodyAsText()
                body shouldContain "\"lastSnapshotTime\":\"N/A\""
                body shouldContain "\"lastSnapshotTotalValueUSD\":0"
            }
        }

        "getSyncProgress_ReturnsJson" {
            coEvery { tradeHistoryService.isHistorySeeded() } returns false
            coEvery { tradeHistoryService.getSyncMetadata("sync_offset") } returns "123"
            coEvery { tradeHistoryService.getSyncMetadata("sync_total") } returns "456"

            testApplication {
                application {
                    configureTestEnv()
                }
                val response = client.get(Routes.API_HISTORY_SYNC_PROGRESS)
                response.status shouldBe HttpStatusCode.OK
                response.headers[HttpHeaders.ContentType] shouldContain TestFixtures.APPLICATION_JSON
                val body = response.bodyAsText()
                body shouldContain "\"seeded\":false"
                body shouldContain "\"offset\":\"123\""
                body shouldContain "\"total\":\"456\""
            }
        }
    }
}
