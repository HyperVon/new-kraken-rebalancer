package com.gemini.krakenbot.controller

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.InvalidConfigurationException
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.ComparisonAvailability
import com.gemini.krakenbot.model.ComparisonProposalStatus
import com.gemini.krakenbot.model.ComparisonUnavailableReason
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.service.ComparisonStartProposal
import com.gemini.krakenbot.service.InceptionDisplayInfo
import com.gemini.krakenbot.service.InceptionDisplayStatus
import com.gemini.krakenbot.service.SettingsComparisonStatus
import com.gemini.krakenbot.view.util.HtmlIds
import com.gemini.krakenbot.view.util.ViewText
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.formUrlEncode
import io.ktor.http.parametersOf
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import java.math.BigDecimal
import java.time.Instant

// Keep HTTP boundary expectations independent from the generated common catalogs.
private object Routes {
    const val ROOT = "/"
    const val SETTINGS = "/settings"
    const val FRAGMENT_DASHBOARD = "/fragments/dashboard"
    const val FRAGMENT_SETTINGS_PROPOSAL = "/fragments/settings-proposal"
    const val FRAGMENT_SETTINGS_ALLOCATIONS_PREVIEW = "/fragments/settings-allocations-preview"
    const val API_STATUS_STREAM = "/api/status/stream"
    const val STATIC_STYLE_CSS = "/static/style.css"
    const val STATIC_REBALANCER_JS = "/static/rebalancer.js"
}

private object FormFields {
    const val CSRF_TOKEN = "csrfToken"
    const val LOOP_DELAY_SECONDS = "loopDelaySeconds"
    const val DEVIATION_TRIGGER_PERCENT = "deviationTriggerPercent"
    const val MINIMUM_ORDER_SIZE_USD = "minimumOrderSizeUSD"
    const val DRY_RUN = "dryRun"
    const val SIMULATION = "simulation"
    const val FIAT_MAX_DRAWDOWN = "fiatMaxDrawdown"
    const val FIAT_DEPLOYMENT_EXPONENT = "fiatDeploymentExponent"
    const val SYMBOLS = "symbols"
    const val TARGETS = "targets"
    const val COLORS = "colors"
    const val SCORES = "scores"
    const val SCORE_EMPHASIS = "scoreEmphasis"
    const val SCORE_SLEEVE_PERCENT = "scoreSleevePercent"
    const val INCEPTION_DATE = "inceptionDate"
    const val COMPARISON_START_DATE = "comparisonStartDate"
    const val FIAT_DEPLOYMENT_THRESHOLD_PERCENT = "fiatDeploymentThresholdPercent"
}

private object HtmxHeaders {
    const val HX_REDIRECT = "HX-Redirect"
    const val HX_REFRESH = "HX-Refresh"
    const val HX_RESWAP = "HX-Reswap"
    const val HX_RETARGET = "HX-Retarget"
}

private object HtmxValues {
    const val BODY = "body"
    const val INNER_HTML = "innerHTML"
    const val TRUE = "true"
}

private suspend fun HttpClient.postAllocationPreview(
    csrfToken: String,
    csrfCookie: String,
    symbols: List<String> = listOf("BTC", "ETH"),
    targets: List<String> = listOf("50", "50"),
    colors: List<String> = listOf("#ff0000", "#00ff00"),
    scores: List<String> = listOf("9.0", "8.0"),
    sleeve: String? = null,
): HttpResponse {
    val fields = mutableListOf(
        FormFields.CSRF_TOKEN to listOf(csrfToken),
        FormFields.SYMBOLS to symbols,
        FormFields.TARGETS to targets,
        FormFields.COLORS to colors,
        FormFields.SCORES to scores,
    )
    if (sleeve != null) fields += FormFields.SCORE_SLEEVE_PERCENT to listOf(sleeve)
    return post(Routes.FRAGMENT_SETTINGS_ALLOCATIONS_PREVIEW) {
        setBody(parametersOf(*fields.toTypedArray()).formUrlEncode())
        header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
        header(HttpHeaders.Cookie, csrfCookie)
    }
}

class DashboardControllerTest : DashboardControllerTestBase() {

    private fun dashboardConfig(
        settings: Settings = TestFixtures.settings(loopDelaySeconds = 60L),
        credentials: KrakenCredentials = KrakenCredentials(TestFixtures.TEST_API_KEY, "private-key"),
    ): AppConfig = TestFixtures.config(
        settings = settings,
        allocations = listOf(Allocation(Asset.USD, 100.0)),
        kraken = credentials,
    )

    init {
        "getDashboardShell_ReturnsHtml" {
            every { configService.getConfig() } returns dashboardConfig(
                settings = TestFixtures.settings(loopDelaySeconds = 60L, minimumOrderSizeUSD = 5.0),
                credentials = KrakenCredentials(apiKey = TestFixtures.TEST_API_KEY, privateKey = "k"),
            )
            testApplication {
                application {
                    configureTestEnv()
                }
                val response = client.get(Routes.ROOT)
                response.status shouldBe HttpStatusCode.OK
                response.headers[HttpHeaders.ContentType] shouldContain TestFixtures.TEXT_HTML
                response.bodyAsText() shouldContain "Kraken Rebalancer"
                response.bodyAsText() shouldContain "sse-connect=\"${Routes.API_STATUS_STREAM}\""
                response.bodyAsText() shouldContain "DRY RUN"
                response.bodyAsText() shouldContain "id=\"loop-control\""
                response.bodyAsText() shouldContain "hx-post=\"/api/pause\""
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
                response.bodyAsText() shouldContain "Waiting for first rebalance cycle"
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
                            TestFixtures.assetSnapshot(
                                symbol = Asset.USD,
                                balance = BigDecimal("5000.0"),
                                price = BigDecimal("1.0"),
                                valueUSD = BigDecimal("5000.0"),
                                targetPercent = BigDecimal("33.33"),
                            ),
                        Asset.BTC to
                            TestFixtures.assetSnapshot(
                                symbol = Asset.BTC,
                                balance = BigDecimal("0.1"),
                                price = BigDecimal("50000.0"),
                                valueUSD = BigDecimal("5000.0"),
                                targetPercent = BigDecimal("33.33"),
                                deviationPercent = BigDecimal("5.0"),
                                deviationUSD = BigDecimal("250.0"),
                            ),
                        Asset.ETH to
                            TestFixtures.assetSnapshot(
                                symbol = Asset.ETH,
                                balance = BigDecimal("2.5"),
                                price = BigDecimal("2000.0"),
                                valueUSD = BigDecimal("5000.0"),
                                targetPercent = BigDecimal("33.33"),
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
                body shouldContain "Total Portfolio"
                body shouldContain "Cash (USD)"
                body shouldContain "Crypto Assets"
                body shouldContain "BUY BTC 0.1"

                body shouldContain "data-epoch=\"${nowTime.toEpochMilli()}\""

                body shouldContain "class=\"sortable asc\""
                body shouldContain "onclick=\"sortTable(this, 5)\""
                body shouldContain "tabindex=\"0\""
                body shouldContain "data-sort=\"ascending\""
                body shouldContain "onkeydown=\"if(event.key === 'Enter' || event.key === ' ')"

                val ethIdx = body.indexOf("symbol-col\">ETH")
                val btcIdx = body.indexOf("symbol-col\">BTC")
                (ethIdx != -1) shouldBe true
                (btcIdx != -1) shouldBe true
                (ethIdx < btcIdx) shouldBe true
            }
        }

        "getDashboardFragment_uses24hBaselineOutsideRecentHistoryWindow" {
            val now = Instant.now()
            val latest = TestFixtures.emptySnapshot(now, BigDecimal("11000.00"))
            val recentHistory =
                listOf(latest) +
                    (1L..49L).map { minutesAgo ->
                        TestFixtures.emptySnapshot(now.minusSeconds(minutesAgo * 60), BigDecimal("10500.00"))
                    }
            val baseline = TestFixtures.emptySnapshot(now.minusSeconds(86_400), BigDecimal("10000.00"))
            val baselineLookup = now.minusSeconds(86_400).plusMillis(1)
            coEvery { tradeHistoryService.getHistory() } returns recentHistory
            coEvery { tradeHistoryService.getSnapshotBefore(baselineLookup) } returns baseline
            every { configService.getConfig() } returns dashboardConfig()

            testApplication {
                application { configureTestEnv() }

                val body = client.get(Routes.FRAGMENT_DASHBOARD).bodyAsText()
                body shouldContain "class=\"hero-delta up\">+10%"
                body shouldContain "24H"
                coVerify(exactly = 1) { tradeHistoryService.getSnapshotBefore(baselineLookup) }
            }
        }

        "getDashboardFragment_uses24hBaselineInRecentHistoryWithoutExtraLookup" {
            val now = Instant.now()
            val latest = TestFixtures.emptySnapshot(now, BigDecimal("11000.00"))
            val baseline = TestFixtures.emptySnapshot(now.minusSeconds(86_400), BigDecimal("10000.00"))
            coEvery { tradeHistoryService.getHistory() } returns listOf(latest, baseline)
            every { configService.getConfig() } returns dashboardConfig()

            testApplication {
                application { configureTestEnv() }

                val body = client.get(Routes.FRAGMENT_DASHBOARD).bodyAsText()
                body shouldContain "class=\"hero-delta up\">+10%"
                body shouldContain "24H"
                coVerify(exactly = 0) { tradeHistoryService.getSnapshotBefore(any()) }
            }
        }

        "dashboard fragment refreshes the mode plate from current settings and keeps stream status separate" {
            val snapshot = TestFixtures.emptySnapshot(Instant.parse("2026-08-09T12:00:00Z"), BigDecimal("1000.00"))
            coEvery { tradeHistoryService.getHistory() } returns listOf(snapshot)
            every { configService.getConfig() } returnsMany listOf(
                dashboardConfig(settings = TestFixtures.settings(dryRun = true, simulation = false)),
                dashboardConfig(settings = TestFixtures.settings(dryRun = false, simulation = true)),
            )

            testApplication {
                application { configureTestEnv() }

                val first = client.get(Routes.FRAGMENT_DASHBOARD).bodyAsText()
                first shouldContain "class=\"mode-plate mode-dry-run\""
                first shouldContain "id=\"mode-plate\" hx-swap-oob=\"true\""
                first shouldContain "id=\"header-status\" hx-swap-oob=\"true\""

                val next = client.get(Routes.FRAGMENT_DASHBOARD).bodyAsText()
                next shouldContain "class=\"mode-plate mode-simulation\""
                next shouldContain "SIMULATION"
                next shouldContain "id=\"header-status\" hx-swap-oob=\"true\""
            }
        }

        "getSettingsPage_ReturnsSettingsForm" {
            val config = dashboardConfig(
                credentials = KrakenCredentials("real-api-key", "real-private-key"),
            )
            every { configService.getConfig() } returns config

            testApplication {
                application {
                    configureTestEnv()
                }
                val response = client.get(Routes.SETTINGS)
                response.status shouldBe HttpStatusCode.OK
                response.headers[HttpHeaders.ContentType] shouldContain TestFixtures.TEXT_HTML
                response.bodyAsText() shouldContain "Global Parameters"
                response.bodyAsText() shouldContain FormFields.LOOP_DELAY_SECONDS
                response.bodyAsText() shouldContain FormFields.INCEPTION_DATE
                response.bodyAsText() shouldContain FormFields.FIAT_DEPLOYMENT_THRESHOLD_PERCENT
            }
        }

        "getSettings_RendersConfiguredInceptionDate" {
            every { configService.getConfig() } returns dashboardConfig(
                settings = TestFixtures.settings().copy(inceptionDate = "2026-06-06"),
            )
            testApplication {
                application {
                    configureTestEnv()
                }
                val response = client.get(Routes.SETTINGS)
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain "value=\"2026-06-06\""
            }
        }

        "getSettings_rendersAsyncProposalSlotWithoutBlockingOnDiscovery" {
            every { configService.getConfig() } returns dashboardConfig(
                settings = TestFixtures.settings().copy(inceptionDate = "2026-06-06"),
            )
            coEvery { tradeHistoryService.getDetectedInceptionDisplayInfo() } returns
                InceptionDisplayInfo(
                    status = InceptionDisplayStatus.APPROVED_READY,
                    message = "Comparison baseline established at 2026-06-06T00:00:00Z",
                )
            testApplication {
                application {
                    configureTestEnv()
                }
                val body = client.get(Routes.SETTINGS).bodyAsText()
                body shouldContain """hx-get="${Routes.FRAGMENT_SETTINGS_PROPOSAL}""""
                body shouldContain "Comparison baseline established at 2026-06-06T00:00:00Z"
                body shouldNotContain "Earliest verified comparison start"
            }
        }

        "getSettings_asyncSlot_neverResolvesProposalDuringPageRender" {
            every { configService.getConfig() } returns dashboardConfig(
                settings = TestFixtures.settings().copy(inceptionDate = "2026-06-06"),
            )
            coEvery { tradeHistoryService.getDetectedInceptionDisplayInfo() } returns
                InceptionDisplayInfo(
                    status = InceptionDisplayStatus.APPROVED_UNAVAILABLE,
                    message = "No trustworthy baseline could be established for the approved start",
                )
            testApplication {
                application {
                    configureTestEnv()
                }
                val body = client.get(Routes.SETTINGS).bodyAsText()
                body shouldContain """hx-get="/fragments/settings-proposal""""
                body shouldContain """hx-target="#comparison-proposal-slot""""
                body shouldContain """hx-target="body""""
                body shouldContain "Determining effective comparison baseline"
                coVerify(exactly = 0) { tradeHistoryService.getComparisonStartProposal(any()) }
                coVerify(exactly = 0) { tradeHistoryService.requestSettingsComparisonStatus(any()) }
            }
        }

        "getSettingsProposalFragment_rendersVerifiedLaterStartProposal" {
            every { configService.getConfig() } returns dashboardConfig(
                settings = TestFixtures.settings().copy(inceptionDate = "2026-06-06"),
            )
            coEvery { tradeHistoryService.getDetectedInceptionDisplayInfo() } returns
                InceptionDisplayInfo(
                    status = InceptionDisplayStatus.APPROVED_UNAVAILABLE,
                    message = "No trustworthy baseline could be established for the approved start: " +
                        "historical price unavailable",
                )
            coEvery { tradeHistoryService.requestSettingsComparisonStatus(any()) } returns
                SettingsComparisonStatus(
                    comparisonAvailability = ComparisonAvailability.UNAVAILABLE,
                    unavailableReason = ComparisonUnavailableReason.MISSING_PRICE,
                    proposal = ComparisonStartProposal(
                        status = ComparisonProposalStatus.VERIFIED,
                        timestamp = Instant.parse("2026-08-01T10:30:00Z"),
                        snapshotId = 7,
                    ),
                )
            testApplication {
                application {
                    configureTestEnv()
                }
                val body = client.get(Routes.FRAGMENT_SETTINGS_PROPOSAL).bodyAsText()
                body shouldContain """id="comparison-proposal-slot""""
                body shouldContain "Earliest verified comparison start"
                body shouldContain "Use verified start"
                body shouldNotContain """hx-trigger="load""""
                body shouldNotContain """hx-get="/fragments/settings-proposal""""
            }
        }

        "getSettingsProposalFragment_keepsPollingWhileBackgroundEvaluationRuns" {
            every { configService.getConfig() } returns dashboardConfig(
                settings = TestFixtures.settings().copy(inceptionDate = "2026-06-06"),
            )
            coEvery { tradeHistoryService.getDetectedInceptionDisplayInfo() } returns
                InceptionDisplayInfo(
                    status = InceptionDisplayStatus.APPROVED_UNAVAILABLE,
                    message = "No trustworthy baseline could be established for the approved start",
                )
            coEvery { tradeHistoryService.requestSettingsComparisonStatus(any()) } returns
                SettingsComparisonStatus(evaluationInProgress = true)

            testApplication {
                application {
                    configureTestEnv()
                }
                val body = client.get(Routes.FRAGMENT_SETTINGS_PROPOSAL).bodyAsText()
                body shouldContain "Determining effective comparison baseline"
                body shouldContain "hx-get=\"/fragments/settings-proposal\""
                body shouldContain "hx-trigger=\"every 5s\""
                body shouldContain "hx-target=\"#comparison-proposal-slot\""
                body shouldContain "hx-swap=\"outerHTML\""
            }
        }

        "getSettingsProposalFragment_rendersEffectiveBaselineWhenComparisonAvailable" {
            every { configService.getConfig() } returns dashboardConfig(
                settings = TestFixtures.settings().copy(inceptionDate = "2026-06-06"),
            )
            coEvery { tradeHistoryService.getDetectedInceptionDisplayInfo() } returns
                InceptionDisplayInfo(
                    status = InceptionDisplayStatus.APPROVED_UNAVAILABLE,
                    message = "No trustworthy baseline could be established for the approved start",
                )
            coEvery { tradeHistoryService.requestSettingsComparisonStatus(any()) } returns
                SettingsComparisonStatus(
                    comparisonAvailability = ComparisonAvailability.AVAILABLE,
                    baselineTimestamp = "2026-06-08T03:09:55.608Z",
                )
            testApplication {
                application {
                    configureTestEnv()
                }
                val body = client.get(Routes.FRAGMENT_SETTINGS_PROPOSAL).bodyAsText()
                body shouldContain """id="comparison-proposal-slot""""
                body shouldContain "Automatic"
                body shouldContain "Effective comparison baseline: 2026-06-08T03:09:55.608Z"
                body shouldNotContain """hx-trigger="load""""
                body shouldNotContain """hx-get="/fragments/settings-proposal""""
                body shouldNotContain "Use verified start"
            }
        }

        "getSettingsProposalFragment_marksManualOverrideSeparately" {
            every { configService.getConfig() } returns dashboardConfig(
                settings = TestFixtures.settings().copy(
                    inceptionDate = "2026-06-06",
                    comparisonStartDate = "2026-06-07",
                ),
            )
            coEvery { tradeHistoryService.getDetectedInceptionDisplayInfo() } returns
                InceptionDisplayInfo(
                    status = InceptionDisplayStatus.APPROVED_UNAVAILABLE,
                    message = "No trustworthy baseline could be established for the approved start",
                )
            coEvery { tradeHistoryService.requestSettingsComparisonStatus(any()) } returns
                SettingsComparisonStatus(
                    comparisonAvailability = ComparisonAvailability.AVAILABLE,
                    baselineTimestamp = "2026-06-08T03:09:55.608Z",
                )
            testApplication {
                application {
                    configureTestEnv()
                }
                val body = client.get(Routes.FRAGMENT_SETTINGS_PROPOSAL).bodyAsText()
                body shouldContain "Manual override active"
                body shouldContain "Requested comparison start: 2026-06-07"
                body shouldContain "Effective comparison baseline: 2026-06-08T03:09:55.608Z"
                body shouldNotContain "Automatic"
                body shouldNotContain """hx-trigger="load""""
                body shouldNotContain """hx-get="/fragments/settings-proposal""""
            }
        }

        "getSettingsProposalFragment_rendersIncompleteSearchInsideTheSlot" {
            every { configService.getConfig() } returns dashboardConfig(
                settings = TestFixtures.settings().copy(inceptionDate = "2026-06-06"),
            )
            coEvery { tradeHistoryService.getDetectedInceptionDisplayInfo() } returns
                InceptionDisplayInfo(
                    status = InceptionDisplayStatus.APPROVED_UNAVAILABLE,
                    message = "No trustworthy baseline could be established for the approved start",
                )
            coEvery { tradeHistoryService.requestSettingsComparisonStatus(any()) } returns
                SettingsComparisonStatus(
                    comparisonAvailability = ComparisonAvailability.UNAVAILABLE,
                    unavailableReason = ComparisonUnavailableReason.MISSING_PRICE,
                    proposal = ComparisonStartProposal(ComparisonProposalStatus.INCOMPLETE),
                )
            testApplication {
                application {
                    configureTestEnv()
                }
                val body = client.get(Routes.FRAGMENT_SETTINGS_PROPOSAL).bodyAsText()
                body shouldContain """id="comparison-proposal-slot""""
                body shouldContain "Later-start verification is still in progress"
                body shouldNotContain "Use verified start"
                body shouldNotContain """hx-trigger="load""""
                body shouldNotContain """hx-get="/fragments/settings-proposal""""
            }
        }

        "getSettingsProposalFragment_rendersExhaustedSearchInsideTheSlot" {
            every { configService.getConfig() } returns dashboardConfig(
                settings = TestFixtures.settings().copy(inceptionDate = "2026-06-06"),
            )
            coEvery { tradeHistoryService.getDetectedInceptionDisplayInfo() } returns
                InceptionDisplayInfo(
                    status = InceptionDisplayStatus.APPROVED_UNAVAILABLE,
                    message = "No trustworthy baseline could be established for the approved start",
                )
            coEvery { tradeHistoryService.requestSettingsComparisonStatus(any()) } returns
                SettingsComparisonStatus(
                    comparisonAvailability = ComparisonAvailability.UNAVAILABLE,
                    unavailableReason = ComparisonUnavailableReason.MISSING_PRICE,
                    proposal = ComparisonStartProposal(ComparisonProposalStatus.EXHAUSTED),
                )
            testApplication {
                application {
                    configureTestEnv()
                }
                val body = client.get(Routes.FRAGMENT_SETTINGS_PROPOSAL).bodyAsText()
                body shouldContain """id="comparison-proposal-slot""""
                body shouldContain "No retained later start passed complete reconciliation."
                body shouldNotContain "Use verified start"
                body shouldNotContain """hx-trigger="load""""
                body shouldNotContain """hx-get="/fragments/settings-proposal""""
            }
        }

        "getSettingsProposalFragment_rendersEmptySlotWhenResolutionFails" {
            every { configService.getConfig() } returns dashboardConfig(
                settings = TestFixtures.settings().copy(inceptionDate = "2026-06-06"),
            )
            coEvery { tradeHistoryService.getDetectedInceptionDisplayInfo() } returns
                InceptionDisplayInfo(
                    status = InceptionDisplayStatus.APPROVED_UNAVAILABLE,
                    message = "No trustworthy baseline could be established for the approved start",
                )
            coEvery { tradeHistoryService.requestSettingsComparisonStatus(any()) } throws
                IllegalStateException("comparison source temporarily unavailable")
            testApplication {
                application {
                    configureTestEnv()
                }
                val response = client.get(Routes.FRAGMENT_SETTINGS_PROPOSAL)
                response.status shouldBe HttpStatusCode.OK
                val body = response.bodyAsText()
                body shouldContain """id="comparison-proposal-slot""""
                body shouldContain "Unable to load comparison baseline status."
                body shouldNotContain "Earliest verified comparison start"
                body shouldNotContain """hx-trigger="load""""
                body shouldNotContain """hx-get="/fragments/settings-proposal""""
            }
        }

        "getSettingsProposalFragment_anchorsScanAtAcceptedComparisonStart" {
            every { configService.getConfig() } returns dashboardConfig(
                settings = TestFixtures.settings().copy(
                    inceptionDate = "2026-06-06",
                    comparisonStartDate = "2026-06-07",
                ),
            )
            coEvery { tradeHistoryService.getDetectedInceptionDisplayInfo() } returns
                InceptionDisplayInfo(
                    status = InceptionDisplayStatus.APPROVED_UNAVAILABLE,
                    message = "No trustworthy baseline could be established for the approved start: " +
                        "historical price unavailable",
                )
            val anchorSlot = slot<Instant>()
            coEvery { tradeHistoryService.requestSettingsComparisonStatus(capture(anchorSlot)) } returns
                SettingsComparisonStatus(
                    comparisonAvailability = ComparisonAvailability.UNAVAILABLE,
                    unavailableReason = ComparisonUnavailableReason.MISSING_PRICE,
                    proposal = ComparisonStartProposal(
                        status = ComparisonProposalStatus.VERIFIED,
                        timestamp = Instant.parse("2026-06-07T00:00:00Z"),
                        snapshotId = 42,
                    ),
                )
            testApplication {
                application {
                    configureTestEnv()
                }
                val body = client.get(Routes.FRAGMENT_SETTINGS_PROPOSAL).bodyAsText()
                body shouldContain "Earliest verified comparison start"
            }
            anchorSlot.captured shouldBe Instant.parse("2026-06-07T00:00:00Z")
        }

        "getSettings_DetectedInception_rendersDisplayOnlyWithoutCopyingIntoInput" {
            every { configService.getConfig() } returns dashboardConfig()
            coEvery { tradeHistoryService.getDetectedInceptionDisplayInfo() } returns
                InceptionDisplayInfo(
                    status = InceptionDisplayStatus.CONFIRMED,
                    dateText = "2024-03-15",
                    source = "auto",
                )
            testApplication {
                application {
                    configureTestEnv()
                }
                val body = client.get(Routes.SETTINGS).bodyAsText()
                body shouldContain "Auto-detected inception"
                body shouldContain "2024-03-15"
                val inceptionInput =
                    Regex("<input[^>]*name=\"inceptionDate\"[^>]*>").find(body)?.value
                inceptionInput.shouldNotBeNull()
                inceptionInput shouldNotContain "2024-03-15"
            }
        }

        "postSettings_RejectsMissingCsrfToken" {
            every { configService.getConfig() } returns dashboardConfig()
            coEvery { configService.updateConfig(any()) } returns Unit

            testApplication {
                application {
                    configureTestEnv()
                }
                val response = client.post(Routes.SETTINGS) {
                    setBody(parametersOf().formUrlEncode())
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                    header(HttpHeaders.Origin, "http://localhost")
                }

                response.status shouldBe HttpStatusCode.Forbidden
                response.bodyAsText() shouldContain
                    "This page's security token is invalid or expired. Your action was not applied. Please try again."
                response.bodyAsText() shouldContain "name=\"${FormFields.CSRF_TOKEN}\""
                response.headers[HttpHeaders.SetCookie].shouldNotBeNull()
                coVerify(exactly = 0) { configService.updateConfig(any()) }
            }
        }

        "postSettings_ReturnsConflictAndPreservesValuesWhileHistoryEvidenceIsBusy" {
            every { configService.getConfig() } returns dashboardConfig()
            every { portfolioManager.isLoopPaused() } returns false

            testApplication {
                application { configureTestEnv() }
                val csrf = client.settingsCsrf()
                val response = historyEvidenceCoordinator.withLock {
                    client.post(Routes.SETTINGS) {
                        setBody(
                            parametersOf(
                                FormFields.CSRF_TOKEN to listOf(csrf.value),
                                FormFields.LOOP_DELAY_SECONDS to listOf("75"),
                            ).formUrlEncode(),
                        )
                        header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                        header(HttpHeaders.Cookie, csrf.cookie)
                        header(HttpHeaders.Origin, "http://localhost")
                    }
                }

                response.status shouldBe HttpStatusCode.Conflict
                response.bodyAsText() shouldContain ViewText.SETTINGS_SAVE_HISTORY_BUSY
                response.bodyAsText() shouldContain "value=\"75\""
                coVerify(exactly = 0) { configService.updateConfig(any()) }
                coVerify(exactly = 0) { tradeHistoryService.setSyncMetadataUnderEvidenceLock(any(), any()) }
            }
        }

        "postSettings_RejectsWrongCsrfTokenAndReturnsTheValidCookieToken" {
            val serverConfig = dashboardConfig()
            every { configService.getConfig() } returns serverConfig
            coEvery { configService.updateConfig(any()) } returns Unit

            testApplication {
                application {
                    configureTestEnv()
                }
                val csrf = client.settingsCsrf()
                val response = client.post(Routes.SETTINGS) {
                    setBody(
                        parametersOf(FormFields.CSRF_TOKEN to listOf("wrong-token")).formUrlEncode(),
                    )
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                    header(HttpHeaders.Cookie, csrf.cookie)
                    header(HttpHeaders.Origin, "http://localhost")
                    header("HX-Request", "true")
                }

                response.status shouldBe HttpStatusCode.Forbidden
                response.bodyAsText() shouldBe ""
                val newToken = response.headers["X-Rebalancer-CSRF-Token"]
                    ?: error("CSRF recovery response did not contain a token header")
                response.headers["X-Rebalancer-CSRF-Session-Expired"] shouldBe "true"
                response.headers[HtmxHeaders.HX_RESWAP] shouldBe null
                response.headers[HtmxHeaders.HX_RETARGET] shouldBe null
                newToken shouldBe csrf.value
                response.headers[HttpHeaders.SetCookie] shouldBe null

                val followUpResponse = client.post(Routes.SETTINGS) {
                    setBody(
                        parametersOf(
                            FormFields.LOOP_DELAY_SECONDS to listOf("60"),
                            FormFields.DEVIATION_TRIGGER_PERCENT to listOf("2.0"),
                            FormFields.MINIMUM_ORDER_SIZE_USD to listOf("1.0"),
                            FormFields.FIAT_MAX_DRAWDOWN to listOf("0.0"),
                            FormFields.FIAT_DEPLOYMENT_EXPONENT to listOf("1.0"),
                            FormFields.CSRF_TOKEN to listOf(newToken),
                            FormFields.SYMBOLS to listOf(Asset.USD),
                            FormFields.TARGETS to listOf("100.0"),
                            FormFields.COLORS to listOf("#94a3b8"),
                            FormFields.SCORES to listOf(""),
                        ).formUrlEncode(),
                    )
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                    header(HttpHeaders.Cookie, csrf.cookie)
                    header(HttpHeaders.Origin, "http://localhost")
                }
                followUpResponse.status shouldBe HttpStatusCode.OK
                followUpResponse.headers[HtmxHeaders.HX_REDIRECT] shouldBe Routes.ROOT
                coVerify(exactly = 1) { configService.updateConfig(any()) }
            }
        }

        "postSettings uses fallback message when comparison proposal throws without a message" {
            val serverConfig = dashboardConfig(
                settings = TestFixtures.settings().copy(inceptionDate = "2026-06-06"),
            )
            every { configService.getConfig() } returns serverConfig
            coEvery { tradeHistoryService.getComparisonStartProposal(any()) } throws IllegalArgumentException()

            testApplication {
                application { configureTestEnv() }
                val csrf = client.settingsCsrf()
                val response = client.post(Routes.SETTINGS) {
                    setBody(
                        parametersOf(
                            FormFields.LOOP_DELAY_SECONDS to listOf("60"),
                            FormFields.DEVIATION_TRIGGER_PERCENT to listOf("2.0"),
                            FormFields.MINIMUM_ORDER_SIZE_USD to listOf("1.0"),
                            FormFields.FIAT_MAX_DRAWDOWN to listOf("0.0"),
                            FormFields.FIAT_DEPLOYMENT_EXPONENT to listOf("1.0"),
                            FormFields.CSRF_TOKEN to listOf(csrf.value),
                            FormFields.SYMBOLS to listOf(Asset.USD),
                            FormFields.TARGETS to listOf("100.0"),
                            FormFields.COLORS to listOf("#94a3b8"),
                            FormFields.SCORES to listOf(""),
                            FormFields.INCEPTION_DATE to listOf("2026-06-06"),
                            FormFields.COMPARISON_START_DATE to listOf("2026-06-07"),
                        ).formUrlEncode(),
                    )
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                    header(HttpHeaders.Cookie, csrf.cookie)
                }

                response.status shouldBe HttpStatusCode.UnprocessableEntity
                response.bodyAsText() shouldContain ViewText.INVALID_CONFIGURATION_FALLBACK
                coVerify(exactly = 0) { configService.updateConfig(any()) }
            }
        }

        "postSettings_RejectsMissingFormTokenWithCookie" {
            val serverConfig = dashboardConfig()
            every { configService.getConfig() } returns serverConfig
            coEvery { configService.updateConfig(any()) } returns Unit

            testApplication {
                application {
                    configureTestEnv()
                }
                val csrf = client.settingsCsrf()
                val response = client.post(Routes.SETTINGS) {
                    setBody(parametersOf().formUrlEncode())
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                    header(HttpHeaders.Cookie, csrf.cookie)
                    header(HttpHeaders.Origin, "http://localhost")
                }

                response.status shouldBe HttpStatusCode.Forbidden
                response.bodyAsText() shouldContain
                    "This page's security token is invalid or expired. Your action was not applied. Please try again."
                coVerify(exactly = 0) { configService.updateConfig(any()) }
            }
        }

        "postSettings preserves submitted values in native CSRF recovery" {
            val serverConfig = dashboardConfig(
                settings = TestFixtures.settings(dryRun = true, simulation = false),
            )
            every { configService.getConfig() } returns serverConfig
            every { portfolioManager.isLoopPaused() } returns true

            testApplication {
                application { configureTestEnv() }
                val csrf = client.settingsCsrf()
                val response = client.post(Routes.SETTINGS) {
                    setBody(
                        parametersOf(
                            FormFields.CSRF_TOKEN to listOf("stale-form-token"),
                            FormFields.LOOP_DELAY_SECONDS to listOf("75"),
                            FormFields.DEVIATION_TRIGGER_PERCENT to listOf("6.5"),
                            FormFields.MINIMUM_ORDER_SIZE_USD to listOf("12.5"),
                            FormFields.FIAT_MAX_DRAWDOWN to listOf("17.0"),
                            FormFields.FIAT_DEPLOYMENT_EXPONENT to listOf("1.7"),
                            FormFields.FIAT_DEPLOYMENT_THRESHOLD_PERCENT to listOf("4.5"),
                            FormFields.SYMBOLS to listOf("BTC", "ETH"),
                            FormFields.TARGETS to listOf("33.5", "66.5"),
                            FormFields.COLORS to listOf("#112233", "#445566"),
                            FormFields.SCORES to listOf("8.5", "6.5"),
                            FormFields.SIMULATION to listOf("on"),
                            FormFields.SCORE_EMPHASIS to listOf("7"),
                            FormFields.SCORE_SLEEVE_PERCENT to listOf("40"),
                            FormFields.INCEPTION_DATE to listOf("2026-06-06"),
                            FormFields.COMPARISON_START_DATE to listOf("2026-06-07"),
                        ).formUrlEncode(),
                    )
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                    header(HttpHeaders.Cookie, csrf.cookie)
                    header(HttpHeaders.Origin, "http://localhost")
                }

                response.status shouldBe HttpStatusCode.Forbidden
                val body = response.bodyAsText()
                body shouldContain "This page's security token is invalid or expired."
                body shouldContain "value=\"75\""
                body shouldContain "value=\"6.5\""
                body shouldContain "value=\"12.5\""
                body shouldContain "value=\"17.0\""
                body shouldContain "value=\"1.7\""
                body shouldContain "value=\"4.5\""
                body shouldContain "name=\"symbols\" value=\"BTC\""
                body shouldContain "name=\"symbols\" value=\"ETH\""
                body shouldContain "name=\"targets\""
                body shouldContain "value=\"33.5\""
                body shouldContain "value=\"66.5\""
                body shouldContain "value=\"#112233\""
                body shouldContain "value=\"#445566\""
                body shouldContain "value=\"8.5\""
                body shouldContain "value=\"6.5\""
                body shouldContain "name=\"inceptionDate\" id=\"inceptionDate\" value=\"2026-06-06\""
                body shouldContain "name=\"comparisonStartDate\" id=\"comparisonStartDate\" value=\"2026-06-07\""
                body shouldContain "name=\"scoreEmphasis\" value=\"7\""
                body shouldContain "name=\"scoreSleevePercent\""
                body shouldContain "value=\"40\""
                Regex("""<input[^>]*name=\"simulation\"[^>]*checked""").containsMatchIn(body).shouldBeTrue()
                Regex("""<input[^>]*name=\"dryRun\"[^>]*checked""").containsMatchIn(body) shouldBe false
                body shouldContain "PAUSED"
                body shouldContain "hx-post=\"/api/resume\""
                body shouldContain "name=\"${FormFields.CSRF_TOKEN}\" value=\"${csrf.value}\""
                coVerify(exactly = 0) { configService.updateConfig(any()) }
            }
        }

        "postSettings recovery clears ambiguous scalar values and preserves uneven allocation rows" {
            every { configService.getConfig() } returns dashboardConfig(
                settings = TestFixtures.settings(dryRun = true, simulation = true),
            )

            testApplication {
                application { configureTestEnv() }
                val csrf = client.settingsCsrf()
                val response = client.post(Routes.SETTINGS) {
                    setBody(
                        parametersOf(
                            FormFields.CSRF_TOKEN to listOf("stale-form-token"),
                            FormFields.LOOP_DELAY_SECONDS to listOf("17", "18"),
                            FormFields.FIAT_DEPLOYMENT_THRESHOLD_PERCENT to listOf("  "),
                            FormFields.SYMBOLS to listOf("BTC", "ETH"),
                            FormFields.TARGETS to listOf("35"),
                            FormFields.SCORES to listOf("8", "6", "4"),
                        ).formUrlEncode(),
                    )
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                    header(HttpHeaders.Cookie, csrf.cookie)
                    header(HttpHeaders.Origin, "http://localhost")
                }

                response.status shouldBe HttpStatusCode.Forbidden
                val body = response.bodyAsText()
                fun inputHasValue(name: String, value: String) = Regex(
                    "<input[^>]*name=\"${Regex.escape(name)}\"[^>]*value=\"${Regex.escape(value)}\"",
                ).containsMatchIn(body)

                inputHasValue("loopDelaySeconds", "") shouldBe true
                inputHasValue("fiatDeploymentThresholdPercent", "0.0") shouldBe true
                inputHasValue("symbols", "BTC") shouldBe true
                inputHasValue("symbols", "ETH") shouldBe true
                inputHasValue("symbols", "") shouldBe true
                inputHasValue("targets", "35") shouldBe true
                inputHasValue("targets", "") shouldBe true
                inputHasValue("scores", "8") shouldBe true
                inputHasValue("scores", "6") shouldBe true
                inputHasValue("scores", "4") shouldBe true
                inputHasValue("scoreEmphasis", "4") shouldBe true
                Regex("""<input[^>]*name=\"simulation\"[^>]*checked""").containsMatchIn(body) shouldBe false
                Regex("""<input[^>]*name=\"dryRun\"[^>]*checked""").containsMatchIn(body) shouldBe false
                coVerify(exactly = 0) { configService.updateConfig(any()) }
            }
        }

        "postSettings without an explicit request origin does not issue a CSRF recovery token" {
            every { configService.getConfig() } returns dashboardConfig()

            testApplication {
                application { configureTestEnv() }
                val csrf = client.settingsCsrf()
                val response = client.post(Routes.SETTINGS) {
                    setBody(parametersOf().formUrlEncode())
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                    header(HttpHeaders.Cookie, csrf.cookie)
                }

                response.status shouldBe HttpStatusCode.Forbidden
                response.bodyAsText() shouldBe ""
                response.headers["X-Rebalancer-CSRF-Token"] shouldBe null
                response.headers["X-Rebalancer-CSRF-Session-Expired"] shouldBe null
                coVerify(exactly = 0) { configService.updateConfig(any()) }
            }
        }

        "postSettings_SucceedsAndSetsHxRedirectHeader" {
            val serverConfig = dashboardConfig(
                credentials = KrakenCredentials(
                    apiKey = TestFixtures.TEST_SERVER_API_KEY,
                    privateKey = TestFixtures.TEST_SERVER_API_SECRET,
                ),
            )
            val captured = slot<AppConfig>()
            every { configService.getConfig() } returns serverConfig
            coEvery { configService.updateConfig(capture(captured)) } returns Unit
            coEvery { tradeHistoryService.getComparisonStartProposal(any()) } returns ComparisonStartProposal(
                status = ComparisonProposalStatus.VERIFIED,
                timestamp = Instant.parse("2026-06-07T00:00:00Z"),
                snapshotId = 42,
            )

            testApplication {
                application {
                    configureTestEnv()
                }
                val csrf = client.settingsCsrf()
                val response =
                    client.post(Routes.SETTINGS) {
                        setBody(
                            parametersOf(
                                FormFields.LOOP_DELAY_SECONDS to listOf("120"),
                                FormFields.DEVIATION_TRIGGER_PERCENT to listOf("3.5"),
                                FormFields.MINIMUM_ORDER_SIZE_USD to listOf("2.0"),
                                FormFields.FIAT_MAX_DRAWDOWN to listOf("5.0"),
                                FormFields.FIAT_DEPLOYMENT_EXPONENT to listOf("1.5"),
                                FormFields.CSRF_TOKEN to listOf(csrf.value),
                                FormFields.DRY_RUN to listOf("on"),
                                FormFields.SIMULATION to listOf("on"),
                                FormFields.SYMBOLS to listOf(Asset.USD),
                                FormFields.TARGETS to listOf("100.0"),
                                FormFields.COLORS to listOf("#94A3B8"),
                                FormFields.SCORES to listOf(""),
                                FormFields.INCEPTION_DATE to listOf("2026-06-06"),
                                FormFields.COMPARISON_START_DATE to listOf("2026-06-07"),
                                FormFields.FIAT_DEPLOYMENT_THRESHOLD_PERCENT to listOf("4.5"),
                            ).formUrlEncode(),
                        )
                        header(
                            HttpHeaders.ContentType,
                            ContentType.Application.FormUrlEncoded.toString(),
                        )
                        header(HttpHeaders.Cookie, csrf.cookie)
                    }
                response.status shouldBe HttpStatusCode.OK
                response.headers[HtmxHeaders.HX_REDIRECT] shouldBe Routes.ROOT
            }

            captured.captured.settings.simulation shouldBe true
            captured.captured.settings.inceptionDate shouldBe "2026-06-06"
            captured.captured.settings.comparisonStartDate shouldBe "2026-06-07"
            captured.captured.settings.fiatDeploymentThresholdPercent shouldBe 4.5
            captured.captured.allocations.single().color shouldBe "#94a3b8"
            coVerify { configService.updateConfig(any()) }
            coVerify {
                tradeHistoryService.setSyncMetadata(
                    SyncMetadataKeys.INCEPTION_COMPARISON_START_SNAPSHOT_ID,
                    "42",
                )
            }
            coVerifyOrder {
                tradeHistoryService.setSyncMetadata(
                    SyncMetadataKeys.INCEPTION_COMPARISON_START_SNAPSHOT_ID,
                    "42",
                )
                configService.updateConfig(any())
            }
        }

        "postSettings rejects out-of-range fiat deployment threshold" {
            val serverConfig = dashboardConfig(
                credentials = KrakenCredentials(
                    apiKey = TestFixtures.TEST_SERVER_API_KEY,
                    privateKey = TestFixtures.TEST_SERVER_API_SECRET,
                ),
            )
            every { configService.getConfig() } returns serverConfig
            coEvery { configService.updateConfig(any()) } returns Unit

            testApplication {
                application {
                    configureTestEnv()
                }
                val csrf = client.settingsCsrf()
                val response = client.post(Routes.SETTINGS) {
                    setBody(
                        parametersOf(
                            FormFields.LOOP_DELAY_SECONDS to listOf("120"),
                            FormFields.DEVIATION_TRIGGER_PERCENT to listOf("3.5"),
                            FormFields.MINIMUM_ORDER_SIZE_USD to listOf("2.0"),
                            FormFields.FIAT_MAX_DRAWDOWN to listOf("5.0"),
                            FormFields.FIAT_DEPLOYMENT_EXPONENT to listOf("1.5"),
                            FormFields.FIAT_DEPLOYMENT_THRESHOLD_PERCENT to listOf("150"),
                            FormFields.CSRF_TOKEN to listOf(csrf.value),
                            FormFields.SYMBOLS to listOf(Asset.USD),
                            FormFields.TARGETS to listOf("100.0"),
                            FormFields.COLORS to listOf("#94a3b8"),
                            FormFields.SCORES to listOf(""),
                        ).formUrlEncode(),
                    )
                    header(
                        HttpHeaders.ContentType,
                        ContentType.Application.FormUrlEncoded.toString(),
                    )
                    header(HttpHeaders.Cookie, csrf.cookie)
                }
                response.bodyAsText() shouldContain "drawdown activation threshold"
            }
        }

        "postSettings rejects an unverified comparison-start proposal" {
            val serverConfig = dashboardConfig(
                credentials = KrakenCredentials(
                    apiKey = TestFixtures.TEST_SERVER_API_KEY,
                    privateKey = TestFixtures.TEST_SERVER_API_SECRET,
                ),
            )
            every { configService.getConfig() } returns serverConfig
            coEvery { configService.updateConfig(any()) } returns Unit
            coEvery { tradeHistoryService.getComparisonStartProposal(any()) } returns
                ComparisonStartProposal(ComparisonProposalStatus.INCOMPLETE)

            testApplication {
                application {
                    configureTestEnv()
                }
                val csrf = client.settingsCsrf()
                val response = client.post(Routes.SETTINGS) {
                    setBody(
                        parametersOf(
                            FormFields.LOOP_DELAY_SECONDS to listOf("120"),
                            FormFields.DEVIATION_TRIGGER_PERCENT to listOf("3.5"),
                            FormFields.MINIMUM_ORDER_SIZE_USD to listOf("2.0"),
                            FormFields.FIAT_MAX_DRAWDOWN to listOf("5.0"),
                            FormFields.FIAT_DEPLOYMENT_EXPONENT to listOf("1.5"),
                            FormFields.CSRF_TOKEN to listOf(csrf.value),
                            FormFields.SYMBOLS to listOf(Asset.USD),
                            FormFields.TARGETS to listOf("100.0"),
                            FormFields.COLORS to listOf("#94a3b8"),
                            FormFields.SCORES to listOf(""),
                            FormFields.INCEPTION_DATE to listOf("2026-06-06"),
                            FormFields.COMPARISON_START_DATE to listOf("2026-06-07"),
                        ).formUrlEncode(),
                    )
                    header(
                        HttpHeaders.ContentType,
                        ContentType.Application.FormUrlEncoded.toString(),
                    )
                    header(HttpHeaders.Cookie, csrf.cookie)
                }
                response.status shouldBe HttpStatusCode.UnprocessableEntity
                response.headers["X-Rebalancer-Error-Fragment"] shouldBe "settings-form"
                response.headers[HttpHeaders.ContentType] shouldContain TestFixtures.TEXT_HTML
                response.bodyAsText() shouldContain "comparison start must be a valid ISO-8601"
            }

            coVerify(exactly = 0) { configService.updateConfig(any()) }
        }

        "postSettings clears the accepted snapshot identity when comparison start is removed" {
            val serverConfig = dashboardConfig(
                settings = TestFixtures.settings().copy(
                    inceptionDate = "2026-06-06",
                    comparisonStartDate = "2026-06-07",
                ),
                credentials = KrakenCredentials(
                    apiKey = TestFixtures.TEST_SERVER_API_KEY,
                    privateKey = TestFixtures.TEST_SERVER_API_SECRET,
                ),
            )
            every { configService.getConfig() } returns serverConfig
            coEvery { configService.updateConfig(any()) } returns Unit

            testApplication {
                application {
                    configureTestEnv()
                }
                val csrf = client.settingsCsrf()
                val response = client.post(Routes.SETTINGS) {
                    setBody(
                        parametersOf(
                            FormFields.LOOP_DELAY_SECONDS to listOf("120"),
                            FormFields.DEVIATION_TRIGGER_PERCENT to listOf("3.5"),
                            FormFields.MINIMUM_ORDER_SIZE_USD to listOf("2.0"),
                            FormFields.FIAT_MAX_DRAWDOWN to listOf("5.0"),
                            FormFields.FIAT_DEPLOYMENT_EXPONENT to listOf("1.5"),
                            FormFields.CSRF_TOKEN to listOf(csrf.value),
                            FormFields.SYMBOLS to listOf(Asset.USD),
                            FormFields.TARGETS to listOf("100.0"),
                            FormFields.COLORS to listOf("#94a3b8"),
                            FormFields.SCORES to listOf(""),
                            FormFields.INCEPTION_DATE to listOf("2026-06-06"),
                        ).formUrlEncode(),
                    )
                    header(
                        HttpHeaders.ContentType,
                        ContentType.Application.FormUrlEncoded.toString(),
                    )
                    header(HttpHeaders.Cookie, csrf.cookie)
                }
                response.status shouldBe HttpStatusCode.OK
            }

            coVerify {
                tradeHistoryService.setSyncMetadata(
                    SyncMetadataKeys.INCEPTION_COMPARISON_START_SNAPSHOT_ID,
                    "",
                )
            }
        }

        "postSettings_preservesDurableRetentionFloorWhenInceptionMovesToTheFuture" {
            val serverConfig = dashboardConfig(
                credentials = KrakenCredentials(
                    apiKey = TestFixtures.TEST_SERVER_API_KEY,
                    privateKey = TestFixtures.TEST_SERVER_API_SECRET,
                ),
            )
            every { configService.getConfig() } returns serverConfig
            coEvery { configService.updateConfig(any()) } returns Unit
            coEvery {
                tradeHistoryService.getSyncMetadata(SyncMetadataKeys.INCEPTION_RETENTION_FLOOR_EPOCH_MS)
            } returns "1760000000000"

            testApplication {
                application {
                    configureTestEnv()
                }
                val csrf = client.settingsCsrf()
                val response = client.post(Routes.SETTINGS) {
                    setBody(
                        parametersOf(
                            FormFields.LOOP_DELAY_SECONDS to listOf("120"),
                            FormFields.DEVIATION_TRIGGER_PERCENT to listOf("3.5"),
                            FormFields.MINIMUM_ORDER_SIZE_USD to listOf("2.0"),
                            FormFields.FIAT_MAX_DRAWDOWN to listOf("5.0"),
                            FormFields.FIAT_DEPLOYMENT_EXPONENT to listOf("1.5"),
                            FormFields.CSRF_TOKEN to listOf(csrf.value),
                            FormFields.SYMBOLS to listOf(Asset.USD),
                            FormFields.TARGETS to listOf("100.0"),
                            FormFields.COLORS to listOf("#94a3b8"),
                            FormFields.SCORES to listOf(""),
                            FormFields.INCEPTION_DATE to listOf("2099-01-01"),
                        ).formUrlEncode(),
                    )
                    header(
                        HttpHeaders.ContentType,
                        ContentType.Application.FormUrlEncoded.toString(),
                    )
                    header(HttpHeaders.Cookie, csrf.cookie)
                }
                response.status shouldBe HttpStatusCode.OK
            }

            coVerify(exactly = 0) {
                tradeHistoryService.setSyncMetadata(
                    SyncMetadataKeys.INCEPTION_RETENTION_FLOOR_EPOCH_MS,
                    any(),
                )
            }
        }

        "postSettings restores the prior snapshot identity when config persistence fails" {
            val serverConfig = dashboardConfig(
                credentials = KrakenCredentials(
                    apiKey = TestFixtures.TEST_SERVER_API_KEY,
                    privateKey = TestFixtures.TEST_SERVER_API_SECRET,
                ),
            )
            every { configService.getConfig() } returns serverConfig
            coEvery { configService.updateConfig(any()) } throws
                InvalidConfigurationException("configuration write rejected")
            coEvery { tradeHistoryService.getComparisonStartProposal(any()) } returns ComparisonStartProposal(
                status = ComparisonProposalStatus.VERIFIED,
                timestamp = Instant.parse("2026-06-07T00:00:00Z"),
                snapshotId = 42,
            )
            coEvery {
                tradeHistoryService.getSyncMetadata(SyncMetadataKeys.INCEPTION_COMPARISON_START_SNAPSHOT_ID)
            } returns "17"

            testApplication {
                application {
                    configureTestEnv()
                }
                val csrf = client.settingsCsrf()
                val response = client.post(Routes.SETTINGS) {
                    setBody(
                        parametersOf(
                            FormFields.LOOP_DELAY_SECONDS to listOf("120"),
                            FormFields.DEVIATION_TRIGGER_PERCENT to listOf("3.5"),
                            FormFields.MINIMUM_ORDER_SIZE_USD to listOf("2.0"),
                            FormFields.FIAT_MAX_DRAWDOWN to listOf("5.0"),
                            FormFields.FIAT_DEPLOYMENT_EXPONENT to listOf("1.5"),
                            FormFields.CSRF_TOKEN to listOf(csrf.value),
                            FormFields.SYMBOLS to listOf(Asset.USD),
                            FormFields.TARGETS to listOf("100.0"),
                            FormFields.COLORS to listOf("#94a3b8"),
                            FormFields.SCORES to listOf(""),
                            FormFields.INCEPTION_DATE to listOf("2026-06-06"),
                            FormFields.COMPARISON_START_DATE to listOf("2026-06-07"),
                        ).formUrlEncode(),
                    )
                    header(
                        HttpHeaders.ContentType,
                        ContentType.Application.FormUrlEncoded.toString(),
                    )
                    header(HttpHeaders.Cookie, csrf.cookie)
                }
                response.status shouldBe HttpStatusCode.UnprocessableEntity
                response.bodyAsText() shouldContain "configuration write rejected"
            }

            coVerifyOrder {
                tradeHistoryService.setSyncMetadata(
                    SyncMetadataKeys.INCEPTION_COMPARISON_START_SNAPSHOT_ID,
                    "42",
                )
                configService.updateConfig(any())
                tradeHistoryService.setSyncMetadata(
                    SyncMetadataKeys.INCEPTION_COMPARISON_START_SNAPSHOT_ID,
                    "17",
                )
            }
            coVerify(exactly = 1) {
                tradeHistoryService.setSyncMetadata(
                    SyncMetadataKeys.INCEPTION_RETENTION_FLOOR_EPOCH_MS,
                    "",
                )
            }
        }

        "postSettings restores an empty identity when clearing comparison start fails" {
            val serverConfig = dashboardConfig(
                settings = TestFixtures.settings().copy(
                    inceptionDate = "2026-06-06",
                    comparisonStartDate = "2026-06-07",
                ),
                credentials = KrakenCredentials(
                    apiKey = TestFixtures.TEST_SERVER_API_KEY,
                    privateKey = TestFixtures.TEST_SERVER_API_SECRET,
                ),
            )
            every { configService.getConfig() } returns serverConfig
            coEvery {
                tradeHistoryService.getSyncMetadata(SyncMetadataKeys.INCEPTION_COMPARISON_START_SNAPSHOT_ID)
            } returns null
            coEvery { configService.updateConfig(any()) } throws
                InvalidConfigurationException("configuration write rejected")

            testApplication {
                application {
                    configureTestEnv()
                }
                val csrf = client.settingsCsrf()
                val response = client.post(Routes.SETTINGS) {
                    setBody(
                        parametersOf(
                            FormFields.LOOP_DELAY_SECONDS to listOf("120"),
                            FormFields.DEVIATION_TRIGGER_PERCENT to listOf("3.5"),
                            FormFields.MINIMUM_ORDER_SIZE_USD to listOf("2.0"),
                            FormFields.FIAT_MAX_DRAWDOWN to listOf("5.0"),
                            FormFields.FIAT_DEPLOYMENT_EXPONENT to listOf("1.5"),
                            FormFields.CSRF_TOKEN to listOf(csrf.value),
                            FormFields.SYMBOLS to listOf(Asset.USD),
                            FormFields.TARGETS to listOf("100.0"),
                            FormFields.COLORS to listOf("#94a3b8"),
                            FormFields.SCORES to listOf(""),
                            FormFields.INCEPTION_DATE to listOf("2026-06-06"),
                        ).formUrlEncode(),
                    )
                    header(
                        HttpHeaders.ContentType,
                        ContentType.Application.FormUrlEncoded.toString(),
                    )
                    header(HttpHeaders.Cookie, csrf.cookie)
                }
                response.status shouldBe HttpStatusCode.UnprocessableEntity
            }

            coVerifyOrder {
                tradeHistoryService.setSyncMetadata(
                    SyncMetadataKeys.INCEPTION_COMPARISON_START_SNAPSHOT_ID,
                    "",
                )
                configService.updateConfig(any())
                tradeHistoryService.setSyncMetadata(
                    SyncMetadataKeys.INCEPTION_COMPARISON_START_SNAPSHOT_ID,
                    "",
                )
            }
        }

        "postSettings restores the prior identity when an unexpected config failure propagates" {
            val serverConfig = dashboardConfig(
                credentials = KrakenCredentials(
                    apiKey = TestFixtures.TEST_SERVER_API_KEY,
                    privateKey = TestFixtures.TEST_SERVER_API_SECRET,
                ),
            )
            every { configService.getConfig() } returns serverConfig
            coEvery { configService.updateConfig(any()) } throws
                IllegalStateException("configuration service unavailable")
            coEvery { tradeHistoryService.getComparisonStartProposal(any()) } returns ComparisonStartProposal(
                status = ComparisonProposalStatus.VERIFIED,
                timestamp = Instant.parse("2026-06-07T00:00:00Z"),
                snapshotId = 42,
            )
            coEvery {
                tradeHistoryService.getSyncMetadata(SyncMetadataKeys.INCEPTION_COMPARISON_START_SNAPSHOT_ID)
            } returns "17"

            testApplication {
                application {
                    configureTestEnv()
                }
                val csrf = client.settingsCsrf()
                val response = client.post(Routes.SETTINGS) {
                    setBody(
                        parametersOf(
                            FormFields.LOOP_DELAY_SECONDS to listOf("120"),
                            FormFields.DEVIATION_TRIGGER_PERCENT to listOf("3.5"),
                            FormFields.MINIMUM_ORDER_SIZE_USD to listOf("2.0"),
                            FormFields.FIAT_MAX_DRAWDOWN to listOf("5.0"),
                            FormFields.FIAT_DEPLOYMENT_EXPONENT to listOf("1.5"),
                            FormFields.CSRF_TOKEN to listOf(csrf.value),
                            FormFields.SYMBOLS to listOf(Asset.USD),
                            FormFields.TARGETS to listOf("100.0"),
                            FormFields.COLORS to listOf("#94a3b8"),
                            FormFields.SCORES to listOf(""),
                            FormFields.INCEPTION_DATE to listOf("2026-06-06"),
                            FormFields.COMPARISON_START_DATE to listOf("2026-06-07"),
                        ).formUrlEncode(),
                    )
                    header(
                        HttpHeaders.ContentType,
                        ContentType.Application.FormUrlEncoded.toString(),
                    )
                    header(HttpHeaders.Cookie, csrf.cookie)
                }
                response.status shouldBe HttpStatusCode.InternalServerError
            }

            coVerifyOrder {
                tradeHistoryService.setSyncMetadata(
                    SyncMetadataKeys.INCEPTION_COMPARISON_START_SNAPSHOT_ID,
                    "42",
                )
                configService.updateConfig(any())
                tradeHistoryService.setSyncMetadata(
                    SyncMetadataKeys.INCEPTION_COMPARISON_START_SNAPSHOT_ID,
                    "17",
                )
            }
        }

        "postSettings fails closed when accepted identity restoration fails" {
            val serverConfig = dashboardConfig(
                credentials = KrakenCredentials(
                    apiKey = TestFixtures.TEST_SERVER_API_KEY,
                    privateKey = TestFixtures.TEST_SERVER_API_SECRET,
                ),
            )
            every { configService.getConfig() } returns serverConfig
            coEvery { configService.updateConfig(any()) } throws
                InvalidConfigurationException("configuration write rejected")
            coEvery { tradeHistoryService.getComparisonStartProposal(any()) } returns ComparisonStartProposal(
                status = ComparisonProposalStatus.VERIFIED,
                timestamp = Instant.parse("2026-06-07T00:00:00Z"),
                snapshotId = 42,
            )
            coEvery {
                tradeHistoryService.getSyncMetadata(SyncMetadataKeys.INCEPTION_COMPARISON_START_SNAPSHOT_ID)
            } returns "17"
            coEvery {
                tradeHistoryService.setSyncMetadata(
                    SyncMetadataKeys.INCEPTION_COMPARISON_START_SNAPSHOT_ID,
                    "17",
                )
            } throws IllegalStateException("metadata store unavailable")

            testApplication {
                application {
                    configureTestEnv()
                }
                val csrf = client.settingsCsrf()
                val response = client.post(Routes.SETTINGS) {
                    setBody(
                        parametersOf(
                            FormFields.LOOP_DELAY_SECONDS to listOf("120"),
                            FormFields.DEVIATION_TRIGGER_PERCENT to listOf("3.5"),
                            FormFields.MINIMUM_ORDER_SIZE_USD to listOf("2.0"),
                            FormFields.FIAT_MAX_DRAWDOWN to listOf("5.0"),
                            FormFields.FIAT_DEPLOYMENT_EXPONENT to listOf("1.5"),
                            FormFields.CSRF_TOKEN to listOf(csrf.value),
                            FormFields.SYMBOLS to listOf(Asset.USD),
                            FormFields.TARGETS to listOf("100.0"),
                            FormFields.COLORS to listOf("#94a3b8"),
                            FormFields.SCORES to listOf(""),
                            FormFields.INCEPTION_DATE to listOf("2026-06-06"),
                            FormFields.COMPARISON_START_DATE to listOf("2026-06-07"),
                        ).formUrlEncode(),
                    )
                    header(
                        HttpHeaders.ContentType,
                        ContentType.Application.FormUrlEncoded.toString(),
                    )
                    header(HttpHeaders.Cookie, csrf.cookie)
                }
                response.status shouldBe HttpStatusCode.InternalServerError
            }

            coVerifyOrder {
                tradeHistoryService.setSyncMetadata(
                    SyncMetadataKeys.INCEPTION_COMPARISON_START_SNAPSHOT_ID,
                    "42",
                )
                configService.updateConfig(any())
                tradeHistoryService.setSyncMetadata(
                    SyncMetadataKeys.INCEPTION_COMPARISON_START_SNAPSHOT_ID,
                    "17",
                )
            }
        }

        "postSettings restores the prior identity before rethrowing cancellation" {
            val serverConfig = dashboardConfig(
                credentials = KrakenCredentials(
                    apiKey = TestFixtures.TEST_SERVER_API_KEY,
                    privateKey = TestFixtures.TEST_SERVER_API_SECRET,
                ),
            )
            every { configService.getConfig() } returns serverConfig
            coEvery { configService.updateConfig(any()) } throws
                CancellationException("settings update cancelled")
            coEvery { tradeHistoryService.getComparisonStartProposal(any()) } returns ComparisonStartProposal(
                status = ComparisonProposalStatus.VERIFIED,
                timestamp = Instant.parse("2026-06-07T00:00:00Z"),
                snapshotId = 42,
            )
            coEvery {
                tradeHistoryService.getSyncMetadata(SyncMetadataKeys.INCEPTION_COMPARISON_START_SNAPSHOT_ID)
            } returns "17"

            testApplication {
                application {
                    configureTestEnv()
                }
                val csrf = client.settingsCsrf()
                val response = client.post(Routes.SETTINGS) {
                    setBody(
                        parametersOf(
                            FormFields.LOOP_DELAY_SECONDS to listOf("120"),
                            FormFields.DEVIATION_TRIGGER_PERCENT to listOf("3.5"),
                            FormFields.MINIMUM_ORDER_SIZE_USD to listOf("2.0"),
                            FormFields.FIAT_MAX_DRAWDOWN to listOf("5.0"),
                            FormFields.FIAT_DEPLOYMENT_EXPONENT to listOf("1.5"),
                            FormFields.CSRF_TOKEN to listOf(csrf.value),
                            FormFields.SYMBOLS to listOf(Asset.USD),
                            FormFields.TARGETS to listOf("100.0"),
                            FormFields.COLORS to listOf("#94a3b8"),
                            FormFields.SCORES to listOf(""),
                            FormFields.INCEPTION_DATE to listOf("2026-06-06"),
                            FormFields.COMPARISON_START_DATE to listOf("2026-06-07"),
                        ).formUrlEncode(),
                    )
                    header(
                        HttpHeaders.ContentType,
                        ContentType.Application.FormUrlEncoded.toString(),
                    )
                    header(HttpHeaders.Cookie, csrf.cookie)
                }
                response.status shouldBe HttpStatusCode.InternalServerError
            }

            coVerifyOrder {
                tradeHistoryService.setSyncMetadata(
                    SyncMetadataKeys.INCEPTION_COMPARISON_START_SNAPSHOT_ID,
                    "42",
                )
                configService.updateConfig(any())
                tradeHistoryService.setSyncMetadata(
                    SyncMetadataKeys.INCEPTION_COMPARISON_START_SNAPSHOT_ID,
                    "17",
                )
            }
        }

        "postSettings surfaces cancellation rollback failure" {
            val serverConfig = dashboardConfig(
                credentials = KrakenCredentials(
                    apiKey = TestFixtures.TEST_SERVER_API_KEY,
                    privateKey = TestFixtures.TEST_SERVER_API_SECRET,
                ),
            )
            every { configService.getConfig() } returns serverConfig
            coEvery { configService.updateConfig(any()) } throws
                CancellationException("settings update cancelled")
            coEvery { tradeHistoryService.getComparisonStartProposal(any()) } returns ComparisonStartProposal(
                status = ComparisonProposalStatus.VERIFIED,
                timestamp = Instant.parse("2026-06-07T00:00:00Z"),
                snapshotId = 42,
            )
            coEvery {
                tradeHistoryService.getSyncMetadata(SyncMetadataKeys.INCEPTION_COMPARISON_START_SNAPSHOT_ID)
            } returns "17"
            coEvery {
                tradeHistoryService.setSyncMetadata(
                    SyncMetadataKeys.INCEPTION_COMPARISON_START_SNAPSHOT_ID,
                    "17",
                )
            } throws IllegalStateException("metadata store unavailable")

            testApplication {
                application {
                    configureTestEnv()
                }
                val csrf = client.settingsCsrf()
                val response = client.post(Routes.SETTINGS) {
                    setBody(
                        parametersOf(
                            FormFields.LOOP_DELAY_SECONDS to listOf("120"),
                            FormFields.DEVIATION_TRIGGER_PERCENT to listOf("3.5"),
                            FormFields.MINIMUM_ORDER_SIZE_USD to listOf("2.0"),
                            FormFields.FIAT_MAX_DRAWDOWN to listOf("5.0"),
                            FormFields.FIAT_DEPLOYMENT_EXPONENT to listOf("1.5"),
                            FormFields.CSRF_TOKEN to listOf(csrf.value),
                            FormFields.SYMBOLS to listOf(Asset.USD),
                            FormFields.TARGETS to listOf("100.0"),
                            FormFields.COLORS to listOf("#94a3b8"),
                            FormFields.SCORES to listOf(""),
                            FormFields.INCEPTION_DATE to listOf("2026-06-06"),
                            FormFields.COMPARISON_START_DATE to listOf("2026-06-07"),
                        ).formUrlEncode(),
                    )
                    header(
                        HttpHeaders.ContentType,
                        ContentType.Application.FormUrlEncoded.toString(),
                    )
                    header(HttpHeaders.Cookie, csrf.cookie)
                }
                response.status shouldBe HttpStatusCode.InternalServerError
            }

            coVerifyOrder {
                tradeHistoryService.setSyncMetadata(
                    SyncMetadataKeys.INCEPTION_COMPARISON_START_SNAPSHOT_ID,
                    "42",
                )
                configService.updateConfig(any())
                tradeHistoryService.setSyncMetadata(
                    SyncMetadataKeys.INCEPTION_COMPARISON_START_SNAPSHOT_ID,
                    "17",
                )
            }
        }

        "postSettings rethrows cancellation without rollback when comparison start is unchanged" {
            val serverConfig = dashboardConfig(
                credentials = KrakenCredentials(
                    apiKey = TestFixtures.TEST_SERVER_API_KEY,
                    privateKey = TestFixtures.TEST_SERVER_API_SECRET,
                ),
            )
            every { configService.getConfig() } returns serverConfig
            coEvery { configService.updateConfig(any()) } throws
                CancellationException("settings update cancelled")

            testApplication {
                application {
                    configureTestEnv()
                }
                val csrf = client.settingsCsrf()
                val response = client.post(Routes.SETTINGS) {
                    setBody(
                        parametersOf(
                            FormFields.LOOP_DELAY_SECONDS to listOf("120"),
                            FormFields.DEVIATION_TRIGGER_PERCENT to listOf("3.5"),
                            FormFields.MINIMUM_ORDER_SIZE_USD to listOf("2.0"),
                            FormFields.FIAT_MAX_DRAWDOWN to listOf("5.0"),
                            FormFields.FIAT_DEPLOYMENT_EXPONENT to listOf("1.5"),
                            FormFields.CSRF_TOKEN to listOf(csrf.value),
                            FormFields.SYMBOLS to listOf(Asset.USD),
                            FormFields.TARGETS to listOf("100.0"),
                            FormFields.COLORS to listOf("#94a3b8"),
                            FormFields.SCORES to listOf(""),
                        ).formUrlEncode(),
                    )
                    header(
                        HttpHeaders.ContentType,
                        ContentType.Application.FormUrlEncoded.toString(),
                    )
                    header(HttpHeaders.Cookie, csrf.cookie)
                }
                response.status shouldBe HttpStatusCode.InternalServerError
            }

            coVerify(exactly = 1) { configService.updateConfig(any()) }
            coVerify(exactly = 0) {
                tradeHistoryService.setSyncMetadata(SyncMetadataKeys.INCEPTION_COMPARISON_START_SNAPSHOT_ID, any())
            }
        }

        "postSettings rejects a comparison start without or before an inception date" {
            val serverConfig = dashboardConfig(
                credentials = KrakenCredentials(
                    apiKey = TestFixtures.TEST_SERVER_API_KEY,
                    privateKey = TestFixtures.TEST_SERVER_API_SECRET,
                ),
            )
            every { configService.getConfig() } returns serverConfig
            coEvery { configService.updateConfig(any()) } returns Unit

            testApplication {
                application {
                    configureTestEnv()
                }
                val csrf = client.settingsCsrf()
                val orphanResponse =
                    client.post(Routes.SETTINGS) {
                        setBody(
                            parametersOf(
                                FormFields.LOOP_DELAY_SECONDS to listOf("120"),
                                FormFields.DEVIATION_TRIGGER_PERCENT to listOf("3.5"),
                                FormFields.MINIMUM_ORDER_SIZE_USD to listOf("2.0"),
                                FormFields.FIAT_MAX_DRAWDOWN to listOf("5.0"),
                                FormFields.FIAT_DEPLOYMENT_EXPONENT to listOf("1.5"),
                                FormFields.CSRF_TOKEN to listOf(csrf.value),
                                FormFields.SYMBOLS to listOf(Asset.USD),
                                FormFields.TARGETS to listOf("100.0"),
                                FormFields.COLORS to listOf("#94a3b8"),
                                FormFields.SCORES to listOf(""),
                                FormFields.COMPARISON_START_DATE to listOf("2026-06-07"),
                            ).formUrlEncode(),
                        )
                        header(
                            HttpHeaders.ContentType,
                            ContentType.Application.FormUrlEncoded.toString(),
                        )
                        header(HttpHeaders.Cookie, csrf.cookie)
                    }
                orphanResponse.bodyAsText() shouldContain
                    "comparison start must be a valid ISO-8601"

                val malformedResponse =
                    client.post(Routes.SETTINGS) {
                        setBody(
                            parametersOf(
                                FormFields.LOOP_DELAY_SECONDS to listOf("120"),
                                FormFields.DEVIATION_TRIGGER_PERCENT to listOf("3.5"),
                                FormFields.MINIMUM_ORDER_SIZE_USD to listOf("2.0"),
                                FormFields.FIAT_MAX_DRAWDOWN to listOf("5.0"),
                                FormFields.FIAT_DEPLOYMENT_EXPONENT to listOf("1.5"),
                                FormFields.CSRF_TOKEN to listOf(csrf.value),
                                FormFields.INCEPTION_DATE to listOf("2026-06-06"),
                                FormFields.COMPARISON_START_DATE to listOf("not-a-date"),
                                FormFields.SYMBOLS to listOf(Asset.USD),
                                FormFields.TARGETS to listOf("100.0"),
                                FormFields.COLORS to listOf("#94a3b8"),
                                FormFields.SCORES to listOf(""),
                            ).formUrlEncode(),
                        )
                        header(
                            HttpHeaders.ContentType,
                            ContentType.Application.FormUrlEncoded.toString(),
                        )
                        header(HttpHeaders.Cookie, csrf.cookie)
                    }
                malformedResponse.bodyAsText() shouldContain
                    "comparison start must be a valid ISO-8601"

                val beforeResponse =
                    client.post(Routes.SETTINGS) {
                        setBody(
                            parametersOf(
                                FormFields.LOOP_DELAY_SECONDS to listOf("120"),
                                FormFields.DEVIATION_TRIGGER_PERCENT to listOf("3.5"),
                                FormFields.MINIMUM_ORDER_SIZE_USD to listOf("2.0"),
                                FormFields.FIAT_MAX_DRAWDOWN to listOf("5.0"),
                                FormFields.FIAT_DEPLOYMENT_EXPONENT to listOf("1.5"),
                                FormFields.CSRF_TOKEN to listOf(csrf.value),
                                FormFields.SYMBOLS to listOf(Asset.USD),
                                FormFields.TARGETS to listOf("100.0"),
                                FormFields.COLORS to listOf("#94a3b8"),
                                FormFields.SCORES to listOf(""),
                                FormFields.INCEPTION_DATE to listOf("2026-06-06"),
                                FormFields.COMPARISON_START_DATE to listOf("2026-06-05"),
                            ).formUrlEncode(),
                        )
                        header(
                            HttpHeaders.ContentType,
                            ContentType.Application.FormUrlEncoded.toString(),
                        )
                        header(HttpHeaders.Cookie, csrf.cookie)
                    }
                beforeResponse.bodyAsText() shouldContain
                    "comparison start must be a valid ISO-8601"

                coVerify(exactly = 0) { configService.updateConfig(any()) }
            }
        }

        "CQ-12-L1: post settings rejects unpaired allocation fields without updating config" {
            val serverConfig = dashboardConfig(
                credentials = KrakenCredentials(
                    apiKey = TestFixtures.TEST_SERVER_API_KEY,
                    privateKey = TestFixtures.TEST_SERVER_API_SECRET,
                ),
            )
            every { configService.getConfig() } returns serverConfig
            coEvery { configService.updateConfig(any()) } returns Unit

            testApplication {
                application {
                    configureTestEnv()
                }
                val csrf = client.settingsCsrf()
                val response = client.post(Routes.SETTINGS) {
                    setBody(
                        parametersOf(
                            FormFields.LOOP_DELAY_SECONDS to listOf("60"),
                            FormFields.DEVIATION_TRIGGER_PERCENT to listOf("2.0"),
                            FormFields.MINIMUM_ORDER_SIZE_USD to listOf("1.0"),
                            FormFields.FIAT_MAX_DRAWDOWN to listOf("0.0"),
                            FormFields.FIAT_DEPLOYMENT_EXPONENT to listOf("1.0"),
                            FormFields.CSRF_TOKEN to listOf(csrf.value),
                            FormFields.SYMBOLS to listOf(Asset.USD, Asset.BTC),
                            FormFields.TARGETS to listOf("100.0"),
                            FormFields.COLORS to listOf("#94a3b8", "#fbbf24"),
                            FormFields.SCORES to listOf("", ""),
                        ).formUrlEncode(),
                    )
                    header(
                        HttpHeaders.ContentType,
                        ContentType.Application.FormUrlEncoded.toString(),
                    )
                    header(HttpHeaders.Cookie, csrf.cookie)
                }
                response.bodyAsText() shouldContain ViewText.INVALID_ALLOCATION_FIELDS

                val invalidColorResponse =
                    client.post(Routes.SETTINGS) {
                        setBody(
                            parametersOf(
                                FormFields.LOOP_DELAY_SECONDS to listOf("60"),
                                FormFields.DEVIATION_TRIGGER_PERCENT to listOf("2.0"),
                                FormFields.MINIMUM_ORDER_SIZE_USD to listOf("1.0"),
                                FormFields.FIAT_MAX_DRAWDOWN to listOf("0.0"),
                                FormFields.FIAT_DEPLOYMENT_EXPONENT to listOf("1.0"),
                                FormFields.CSRF_TOKEN to listOf(csrf.value),
                                FormFields.SYMBOLS to listOf(Asset.USD),
                                FormFields.TARGETS to listOf("100.0"),
                                FormFields.COLORS to listOf("#94a3b8"),
                                FormFields.COLORS to listOf("not-a-color"),
                                FormFields.SCORES to listOf(""),
                            ).formUrlEncode(),
                        )
                        header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                        header(HttpHeaders.Cookie, csrf.cookie)
                    }
                invalidColorResponse.bodyAsText() shouldContain
                    "Invalid allocation fields: supplied colors must use six-digit hex format."
            }

            coVerify(exactly = 0) { configService.updateConfig(any()) }
        }

        "CQ-12-L1: post settings rejects malformed required trading values before persistence" {
            val serverConfig = dashboardConfig(
                settings = TestFixtures.settings(loopDelaySeconds = 60, minimumOrderSizeUSD = 5.0),
                credentials = KrakenCredentials(TestFixtures.TEST_SERVER_API_KEY, TestFixtures.TEST_SERVER_API_SECRET),
            )
            every { configService.getConfig() } returns serverConfig
            coEvery { configService.updateConfig(any()) } returns Unit

            val validFields =
                mapOf(
                    FormFields.LOOP_DELAY_SECONDS to "60",
                    FormFields.DEVIATION_TRIGGER_PERCENT to "2.0",
                    FormFields.MINIMUM_ORDER_SIZE_USD to "1.0",
                    FormFields.FIAT_MAX_DRAWDOWN to "5.0",
                    FormFields.FIAT_DEPLOYMENT_EXPONENT to "1.5",
                    FormFields.TARGETS to "100.0",
                )
            val invalidValues =
                mapOf(
                    FormFields.LOOP_DELAY_SECONDS to "not-a-long",
                    FormFields.DEVIATION_TRIGGER_PERCENT to "NaN",
                    FormFields.MINIMUM_ORDER_SIZE_USD to "Infinity",
                    FormFields.FIAT_MAX_DRAWDOWN to "not-a-number",
                    FormFields.FIAT_DEPLOYMENT_EXPONENT to "-Infinity",
                    FormFields.TARGETS to "not-a-target",
                )

            testApplication {
                application { configureTestEnv() }
                val csrf = client.settingsCsrf()
                invalidValues.forEach { (invalidField, invalidValue) ->
                    val fields = validFields + (invalidField to invalidValue)
                    val response =
                        client.post(Routes.SETTINGS) {
                            setBody(
                                parametersOf(
                                    FormFields.LOOP_DELAY_SECONDS to
                                        listOf(fields.getValue(FormFields.LOOP_DELAY_SECONDS)),
                                    FormFields.DEVIATION_TRIGGER_PERCENT to
                                        listOf(fields.getValue(FormFields.DEVIATION_TRIGGER_PERCENT)),
                                    FormFields.MINIMUM_ORDER_SIZE_USD to
                                        listOf(fields.getValue(FormFields.MINIMUM_ORDER_SIZE_USD)),
                                    FormFields.FIAT_MAX_DRAWDOWN to
                                        listOf(fields.getValue(FormFields.FIAT_MAX_DRAWDOWN)),
                                    FormFields.FIAT_DEPLOYMENT_EXPONENT to
                                        listOf(fields.getValue(FormFields.FIAT_DEPLOYMENT_EXPONENT)),
                                    FormFields.CSRF_TOKEN to listOf(csrf.value),
                                    FormFields.SYMBOLS to listOf(Asset.USD),
                                    FormFields.TARGETS to listOf(fields.getValue(FormFields.TARGETS)),
                                    FormFields.COLORS to listOf("#94a3b8"),
                                    FormFields.SCORES to listOf(""),
                                ).formUrlEncode(),
                            )
                            header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                            header(HttpHeaders.Cookie, csrf.cookie)
                        }
                    response.bodyAsText() shouldContain "Invalid settings field"
                }
            }

            coVerify(exactly = 0) { configService.updateConfig(any()) }
        }

        "CQ-12-L1: post settings rejects mismatched colors without updating config" {
            val serverConfig = dashboardConfig(
                settings = TestFixtures.settings(loopDelaySeconds = 60, minimumOrderSizeUSD = 5.0),
                credentials = KrakenCredentials(TestFixtures.TEST_SERVER_API_KEY, TestFixtures.TEST_SERVER_API_SECRET),
            )
            every { configService.getConfig() } returns serverConfig
            coEvery { configService.updateConfig(any()) } returns Unit

            testApplication {
                application { configureTestEnv() }
                val csrf = client.settingsCsrf()
                val response =
                    client.post(Routes.SETTINGS) {
                        setBody(
                            parametersOf(
                                FormFields.LOOP_DELAY_SECONDS to listOf("60"),
                                FormFields.DEVIATION_TRIGGER_PERCENT to listOf("2.0"),
                                FormFields.MINIMUM_ORDER_SIZE_USD to listOf("1.0"),
                                FormFields.FIAT_MAX_DRAWDOWN to listOf("0.0"),
                                FormFields.FIAT_DEPLOYMENT_EXPONENT to listOf("1.0"),
                                FormFields.CSRF_TOKEN to listOf(csrf.value),
                                FormFields.SYMBOLS to listOf(Asset.USD, Asset.BTC),
                                FormFields.TARGETS to listOf("50.0", "50.0"),
                                FormFields.COLORS to listOf("#94a3b8"),
                                FormFields.SCORES to listOf("", ""),
                            ).formUrlEncode(),
                        )
                        header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                        header(HttpHeaders.Cookie, csrf.cookie)
                    }
                response.bodyAsText() shouldContain ViewText.INVALID_ALLOCATION_FIELDS
            }

            coVerify(exactly = 0) { configService.updateConfig(any()) }
        }

        "CQ-12-L1: post settings rejects duplicate singleton values without updating config" {
            val serverConfig = dashboardConfig(
                settings = Settings(loopDelaySeconds = 60, deviationTriggerPercent = 2.0, dryRun = true),
                credentials = KrakenCredentials(TestFixtures.TEST_SERVER_API_KEY, TestFixtures.TEST_SERVER_API_SECRET),
            )
            every { configService.getConfig() } returns serverConfig
            coEvery { configService.updateConfig(any()) } returns Unit

            testApplication {
                application { configureTestEnv() }
                val csrf = client.settingsCsrf()
                val response =
                    client.post(Routes.SETTINGS) {
                        setBody(
                            parametersOf(
                                FormFields.LOOP_DELAY_SECONDS to listOf("60"),
                                FormFields.DEVIATION_TRIGGER_PERCENT to listOf("2.0", "3.0"),
                                FormFields.MINIMUM_ORDER_SIZE_USD to listOf("1.0"),
                                FormFields.FIAT_MAX_DRAWDOWN to listOf("0.0"),
                                FormFields.FIAT_DEPLOYMENT_EXPONENT to listOf("1.0"),
                                FormFields.CSRF_TOKEN to listOf(csrf.value),
                                FormFields.SYMBOLS to listOf(Asset.USD),
                                FormFields.TARGETS to listOf("100.0"),
                                FormFields.COLORS to listOf("#94a3b8"),
                                FormFields.SCORES to listOf(""),
                            ).formUrlEncode(),
                        )
                        header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                        header(HttpHeaders.Cookie, csrf.cookie)
                    }
                response.bodyAsText() shouldContain
                    "Invalid settings field: deviation trigger percent is required and must be a finite number."
            }

            coVerify(exactly = 0) { configService.updateConfig(any()) }
        }

        "postSettings_OnValidationError_ReturnsErrorHtmlBody" {
            val serverConfig = dashboardConfig(
                credentials = KrakenCredentials(
                    apiKey = TestFixtures.TEST_SERVER_API_KEY,
                    privateKey = TestFixtures.TEST_SERVER_API_SECRET,
                ),
            )
            every { configService.getConfig() } returns serverConfig
            coEvery { configService.updateConfig(any()) } throws
                InvalidConfigurationException(
                    "Total allocation percentage must be exactly 100%.",
                )

            testApplication {
                application {
                    configureTestEnv()
                }
                val csrf = client.settingsCsrf()
                val response =
                    client.post(Routes.SETTINGS) {
                        setBody(
                            parametersOf(
                                FormFields.LOOP_DELAY_SECONDS to listOf("60"),
                                FormFields.DEVIATION_TRIGGER_PERCENT to listOf("2.0"),
                                FormFields.MINIMUM_ORDER_SIZE_USD to listOf("1.0"),
                                FormFields.FIAT_MAX_DRAWDOWN to listOf("0.0"),
                                FormFields.FIAT_DEPLOYMENT_EXPONENT to listOf("1.0"),
                                FormFields.CSRF_TOKEN to listOf(csrf.value),
                                FormFields.SYMBOLS to listOf(Asset.USD),
                                FormFields.TARGETS to listOf("90.0"),
                                FormFields.COLORS to listOf("#94a3b8"),
                                FormFields.SCORES to listOf(""),
                            ).formUrlEncode(),
                        )
                        header(
                            HttpHeaders.ContentType,
                            ContentType.Application.FormUrlEncoded.toString(),
                        )
                        header(HttpHeaders.Cookie, csrf.cookie)
                    }
                response.status shouldBe HttpStatusCode.UnprocessableEntity
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

        "postSettings_WithInvalidDeviationTrigger_RejectsWithoutUpdatingConfig" {
            val serverConfig = dashboardConfig(
                credentials = KrakenCredentials(
                    apiKey = TestFixtures.TEST_SERVER_API_KEY,
                    privateKey = TestFixtures.TEST_SERVER_API_SECRET,
                ),
            )
            every { configService.getConfig() } returns serverConfig
            coEvery { configService.updateConfig(any()) } returns Unit

            testApplication {
                application {
                    configureTestEnv()
                }
                val csrf = client.settingsCsrf()
                val response =
                    client.post(Routes.SETTINGS) {
                        setBody(
                            parametersOf(
                                FormFields.LOOP_DELAY_SECONDS to listOf(TestFixtures.INVALID),
                                FormFields.DEVIATION_TRIGGER_PERCENT to listOf(TestFixtures.INVALID),
                                FormFields.MINIMUM_ORDER_SIZE_USD to listOf("5.0"),
                                FormFields.FIAT_MAX_DRAWDOWN to listOf(TestFixtures.INVALID),
                                FormFields.FIAT_DEPLOYMENT_EXPONENT to listOf(TestFixtures.INVALID),
                                FormFields.CSRF_TOKEN to listOf(csrf.value),
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
                        header(HttpHeaders.Cookie, csrf.cookie)
                    }
                response.status shouldBe HttpStatusCode.UnprocessableEntity
                response.bodyAsText() shouldContain
                    "Invalid settings field: deviation trigger percent is required and must be a finite number."
            }

            coVerify(exactly = 0) { configService.updateConfig(any()) }
        }

        "postSettings_WithInvalidDustThreshold_RejectsWithoutUpdatingConfig" {
            val serverConfig = dashboardConfig(
                credentials = KrakenCredentials(
                    apiKey = TestFixtures.TEST_SERVER_API_KEY,
                    privateKey = TestFixtures.TEST_SERVER_API_SECRET,
                ),
            )
            every { configService.getConfig() } returns serverConfig
            coEvery { configService.updateConfig(any()) } returns Unit

            testApplication {
                application {
                    configureTestEnv()
                }
                val csrf = client.settingsCsrf()
                val response =
                    client.post(Routes.SETTINGS) {
                        setBody(
                            parametersOf(
                                FormFields.LOOP_DELAY_SECONDS to listOf("60"),
                                FormFields.DEVIATION_TRIGGER_PERCENT to listOf("5.0"),
                                FormFields.MINIMUM_ORDER_SIZE_USD to listOf(TestFixtures.INVALID),
                                FormFields.CSRF_TOKEN to listOf(csrf.value),
                                FormFields.SYMBOLS to listOf(Asset.USD),
                                FormFields.TARGETS to listOf("100.0"),
                                FormFields.COLORS to listOf("#94a3b8"),
                                FormFields.SCORES to listOf(""),
                            ).formUrlEncode(),
                        )
                        header(
                            HttpHeaders.ContentType,
                            ContentType.Application.FormUrlEncoded.toString(),
                        )
                        header(HttpHeaders.Cookie, csrf.cookie)
                    }
                response.status shouldBe HttpStatusCode.UnprocessableEntity
                response.bodyAsText() shouldContain
                    "Invalid settings field: minimum order size USD is required and must be a finite number."
            }

            coVerify(exactly = 0) { configService.updateConfig(any()) }
        }

        "postSettings_WithAbsentDeviationAndDust_RejectsWithoutUpdatingConfig" {
            val serverConfig = dashboardConfig(
                credentials = KrakenCredentials(
                    apiKey = TestFixtures.TEST_SERVER_API_KEY,
                    privateKey = TestFixtures.TEST_SERVER_API_SECRET,
                ),
            )
            every { configService.getConfig() } returns serverConfig
            coEvery { configService.updateConfig(any()) } returns Unit

            testApplication {
                application {
                    configureTestEnv()
                }
                val csrf = client.settingsCsrf()
                val response =
                    client.post(Routes.SETTINGS) {
                        setBody(
                            parametersOf(FormFields.CSRF_TOKEN to listOf(csrf.value)).formUrlEncode(),
                        )
                        header(
                            HttpHeaders.ContentType,
                            ContentType.Application.FormUrlEncoded.toString(),
                        )
                        header(HttpHeaders.Cookie, csrf.cookie)
                    }
                response.status shouldBe HttpStatusCode.UnprocessableEntity
                response.bodyAsText() shouldContain
                    "Invalid settings field: deviation trigger percent is required and must be a finite number."
            }

            coVerify(exactly = 0) { configService.updateConfig(any()) }
        }

        "postSettings_WithValidatableConfigError_UsesFallbackMessageWhenNull" {
            val serverConfig = dashboardConfig(
                credentials = KrakenCredentials(
                    apiKey = TestFixtures.TEST_SERVER_API_KEY,
                    privateKey = TestFixtures.TEST_SERVER_API_SECRET,
                ),
            )
            every { configService.getConfig() } returns serverConfig
            val capturedConfig = slot<AppConfig>()
            coEvery {
                configService.updateConfig(capture(capturedConfig))
            } throws
                InvalidConfigurationException(
                    null,
                )

            testApplication {
                application {
                    configureTestEnv()
                }
                val csrf = client.settingsCsrf()
                val response =
                    client.post(Routes.SETTINGS) {
                        setBody(
                            parametersOf(
                                FormFields.LOOP_DELAY_SECONDS to listOf("60"),
                                FormFields.DEVIATION_TRIGGER_PERCENT to listOf("5.0"),
                                FormFields.MINIMUM_ORDER_SIZE_USD to listOf("5.0"),
                                FormFields.FIAT_MAX_DRAWDOWN to listOf("0.0"),
                                FormFields.FIAT_DEPLOYMENT_EXPONENT to listOf("1.0"),
                                FormFields.CSRF_TOKEN to listOf(csrf.value),
                                FormFields.SYMBOLS to listOf(Asset.USD),
                                FormFields.TARGETS to listOf("100.0"),
                                FormFields.COLORS to listOf("#94a3b8"),
                                FormFields.SCORES to listOf(""),
                            ).formUrlEncode(),
                        )
                        header(
                            HttpHeaders.ContentType,
                            ContentType.Application.FormUrlEncoded.toString(),
                        )
                        header(HttpHeaders.Cookie, csrf.cookie)
                    }
                response.status shouldBe HttpStatusCode.UnprocessableEntity
                response.bodyAsText() shouldContain "Invalid configuration"
            }

            capturedConfig.captured.settings.deviationTriggerPercent shouldBe 5.0
            capturedConfig.captured.settings.minimumOrderSizeUSD shouldBe 5.0
        }

        "getSettings_SetCookieCarriesPathHttpOnlySameSiteStrictAttributes" {
            every { configService.getConfig() } returns dashboardConfig()

            testApplication {
                application {
                    configureTestEnv()
                }
                val response = client.get(Routes.SETTINGS)
                val setCookie = response.headers[HttpHeaders.SetCookie]
                    ?: error("Settings page did not issue a CSRF cookie")
                setCookie shouldContain "Path=/"
                setCookie shouldContain "HttpOnly"
                setCookie shouldContain "SameSite=Strict"
                setCookie shouldNotContain "Secure"
                setCookie shouldContain "Max-Age=86400"
                setCookie shouldNotContain "Domain="
            }
        }

        "postSettings_RejectsDuplicateMatchingCsrfFormTokens" {
            val serverConfig = dashboardConfig()
            every { configService.getConfig() } returns serverConfig
            coEvery { configService.updateConfig(any()) } returns Unit

            testApplication {
                application {
                    configureTestEnv()
                }
                val csrf = client.settingsCsrf()
                // Two identical form tokens that both match the cookie: the duplicate branch in
                // CsrfProtection.isValid (formTokens.size != 1) must reject before the constant-
                // time equality check is reached, so the handler returns Forbidden and never
                // mutates config.
                val response = client.post(Routes.SETTINGS) {
                    setBody(
                        parametersOf(FormFields.CSRF_TOKEN to listOf(csrf.value, csrf.value)).formUrlEncode(),
                    )
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                    header(HttpHeaders.Cookie, csrf.cookie)
                    header(HttpHeaders.Origin, "http://localhost")
                }

                response.status shouldBe HttpStatusCode.Forbidden
                response.bodyAsText() shouldContain
                    "This page's security token is invalid or expired. Your action was not applied. Please try again."
                response.headers[HttpHeaders.SetCookie] shouldBe null
                coVerify(exactly = 0) { configService.updateConfig(any()) }
            }
        }

        "postSettings_RejectsInvalidLoopDelayOrNonFiniteNumbers" {
            val serverConfig = dashboardConfig()
            every { configService.getConfig() } returns serverConfig

            testApplication {
                application {
                    configureTestEnv()
                }
                val csrf = client.settingsCsrf()
                val response = client.post(Routes.SETTINGS) {
                    setBody(
                        parametersOf(
                            FormFields.CSRF_TOKEN to listOf(csrf.value),
                            FormFields.LOOP_DELAY_SECONDS to listOf("not-a-number"),
                            FormFields.DEVIATION_TRIGGER_PERCENT to listOf("5.0"),
                            FormFields.MINIMUM_ORDER_SIZE_USD to listOf("10.0"),
                            FormFields.FIAT_MAX_DRAWDOWN to listOf("20.0"),
                            FormFields.FIAT_DEPLOYMENT_EXPONENT to listOf("1.0"),
                            FormFields.SYMBOLS to listOf("BTC", "USD"),
                            FormFields.TARGETS to listOf("80.0", "20.0"),
                            FormFields.COLORS to listOf("#f7931a", "#85bb65"),
                            FormFields.SCORES to listOf("", ""),
                        ).formUrlEncode(),
                    )
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                    header(HttpHeaders.Cookie, csrf.cookie)
                }

                response.status shouldBe HttpStatusCode.UnprocessableEntity
                response.bodyAsText() shouldContain
                    "Invalid settings field: loop delay is required and must be an integer."
            }
        }

        "postSettings_RejectsInvalidAllocationColorOrDuplicateParams" {
            val serverConfig = dashboardConfig()
            every { configService.getConfig() } returns serverConfig

            testApplication {
                application {
                    configureTestEnv()
                }
                val csrf = client.settingsCsrf()
                val response = client.post(Routes.SETTINGS) {
                    setBody(
                        parametersOf(
                            FormFields.CSRF_TOKEN to listOf(csrf.value),
                            FormFields.LOOP_DELAY_SECONDS to listOf("60"),
                            FormFields.DEVIATION_TRIGGER_PERCENT to listOf("5.0"),
                            FormFields.MINIMUM_ORDER_SIZE_USD to listOf("10.0"),
                            FormFields.FIAT_MAX_DRAWDOWN to listOf("20.0"),
                            FormFields.FIAT_DEPLOYMENT_EXPONENT to listOf("1.0"),
                            FormFields.SYMBOLS to listOf("BTC", "USD"),
                            FormFields.TARGETS to listOf("80.0", "20.0"),
                            FormFields.COLORS to listOf("invalid-color", "#85bb65"),
                            FormFields.SCORES to listOf("", ""),
                        ).formUrlEncode(),
                    )
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                    header(HttpHeaders.Cookie, csrf.cookie)
                }

                response.status shouldBe HttpStatusCode.UnprocessableEntity
                response.bodyAsText() shouldContain
                    "Invalid allocation fields: supplied colors must use six-digit hex format."
            }
        }

        "postSettings_RejectsInvalidInceptionDate" {
            val serverConfig = dashboardConfig()
            every { configService.getConfig() } returns serverConfig

            testApplication {
                application {
                    configureTestEnv()
                }
                val csrf = client.settingsCsrf()
                val response = client.post(Routes.SETTINGS) {
                    setBody(
                        parametersOf(
                            FormFields.CSRF_TOKEN to listOf(csrf.value),
                            FormFields.LOOP_DELAY_SECONDS to listOf("60"),
                            FormFields.DEVIATION_TRIGGER_PERCENT to listOf("5.0"),
                            FormFields.MINIMUM_ORDER_SIZE_USD to listOf("10.0"),
                            FormFields.FIAT_MAX_DRAWDOWN to listOf("20.0"),
                            FormFields.FIAT_DEPLOYMENT_EXPONENT to listOf("1.0"),
                            FormFields.INCEPTION_DATE to listOf("not-a-valid-date"),
                            FormFields.SYMBOLS to listOf("BTC", "USD"),
                            FormFields.TARGETS to listOf("80.0", "20.0"),
                            FormFields.COLORS to listOf("#f7931a", "#85bb65"),
                            FormFields.SCORES to listOf("", ""),
                        ).formUrlEncode(),
                    )
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                    header(HttpHeaders.Cookie, csrf.cookie)
                }

                response.status shouldBe HttpStatusCode.UnprocessableEntity
                response.bodyAsText() shouldContain
                    "Invalid settings field: inception date must be a valid ISO-8601 date or timestamp."
            }
        }

        "getDashboardFragment_WhenNoSnapshot_RendersWaitingState" {
            coEvery { tradeHistoryService.getHistory() } returns emptyList()
            every { configService.getConfig() } returns dashboardConfig()

            testApplication {
                application {
                    configureTestEnv()
                }
                val response = client.get(Routes.FRAGMENT_DASHBOARD)
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain "Waiting for first rebalance cycle"
                response.bodyAsText() shouldContain
                    "The rebalancer is running. Portfolio data will appear here after the first cycle completes."
            }
        }

        "allocationsPreview_RecomputesFromScoresWithoutSaving" {
            val serverConfig = dashboardConfig()
            every { configService.getConfig() } returns serverConfig

            testApplication {
                application { configureTestEnv() }
                val csrf = client.settingsCsrf()
                val response = client.post(Routes.FRAGMENT_SETTINGS_ALLOCATIONS_PREVIEW) {
                    setBody(
                        parametersOf(
                            FormFields.CSRF_TOKEN to listOf(csrf.value),
                            FormFields.SYMBOLS to listOf("BTC", "ETH", "USD"),
                            FormFields.TARGETS to listOf("50", "30", "20"),
                            FormFields.COLORS to listOf("#ff0000", "#00ff00", "#0000ff"),
                            FormFields.SCORES to listOf("9.5", "8.5", ""),
                            FormFields.SCORE_EMPHASIS to listOf("2"),
                        ).formUrlEncode(),
                    )
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                    header(HttpHeaders.Cookie, csrf.cookie)
                }

                response.status shouldBe HttpStatusCode.OK
                val body = response.bodyAsText()
                body shouldContain "Preview only"
                // Concentration is a claim about the whole portfolio, so it counts the unscored USD
                // leg too. The sleeve is 80 (50 + 30) and emphasis 2 splits it 9.5^2 : 8.5^2 =
                // 90.25 : 72.25, giving BTC 44.43 and ETH 35.57 with USD held at 20. The three legs
                // sum to 100, so the largest position is 44.43% of the book and 2.75 effective bets
                // — not the 55.54% and 1.98 the old scored-sleeve denominator reported.
                body shouldContain "44.43"
                body shouldContain "2.75"
                body shouldContain "of the whole book"
                body shouldContain "scored assets only"
                // The response is the container's contents, so it must not re-wrap them in a
                // second element carrying the same id.
                body shouldNotContain """id="${HtmlIds.ALLOCATIONS_CONTAINER}"""
                body shouldContain "name=\"${FormFields.TARGETS}\""
                coVerify(exactly = 0) { configService.updateConfig(any()) }
            }
        }

        "allocationsPreview_RejectsMissingCsrfToken" {
            val serverConfig = dashboardConfig()
            every { configService.getConfig() } returns serverConfig

            testApplication {
                application { configureTestEnv() }
                val csrf = client.settingsCsrf()
                val response = client.post(Routes.FRAGMENT_SETTINGS_ALLOCATIONS_PREVIEW) {
                    setBody(
                        parametersOf(
                            FormFields.SYMBOLS to listOf("BTC"),
                            FormFields.TARGETS to listOf("100"),
                            FormFields.COLORS to listOf("#ff0000"),
                            FormFields.SCORES to listOf("9.5"),
                        ).formUrlEncode(),
                    )
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                    header(HttpHeaders.Cookie, csrf.cookie)
                }

                response.status shouldBe HttpStatusCode.Forbidden
                coVerify(exactly = 0) { configService.updateConfig(any()) }
            }
        }

        "allocationsPreview_RequiresAtLeastOneScore" {
            val serverConfig = dashboardConfig()
            every { configService.getConfig() } returns serverConfig

            testApplication {
                application { configureTestEnv() }
                val csrf = client.settingsCsrf()
                val response = client.post(Routes.FRAGMENT_SETTINGS_ALLOCATIONS_PREVIEW) {
                    setBody(
                        parametersOf(
                            FormFields.CSRF_TOKEN to listOf(csrf.value),
                            FormFields.SYMBOLS to listOf("BTC", "ETH"),
                            FormFields.TARGETS to listOf("50", "50"),
                            FormFields.COLORS to listOf("#ff0000", "#00ff00"),
                            FormFields.SCORES to listOf("", "0"),
                        ).formUrlEncode(),
                    )
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                    header(HttpHeaders.Cookie, csrf.cookie)
                }

                response.status shouldBe HttpStatusCode.UnprocessableEntity
                // The trigger swaps the allocations container, so the error has to be retargeted
                // at the body to become visible at all.
                response.headers[HtmxHeaders.HX_RETARGET] shouldBe HtmxValues.BODY
                response.bodyAsText() shouldContain ViewText.ALLOCATION_SCORE_REQUIRED
                coVerify(exactly = 0) { configService.updateConfig(any()) }
            }
        }

        "allocationsPreview_RejectsMismatchedRowArrays" {
            val serverConfig = dashboardConfig()
            every { configService.getConfig() } returns serverConfig

            testApplication {
                application { configureTestEnv() }
                val csrf = client.settingsCsrf()
                val response = client.post(Routes.FRAGMENT_SETTINGS_ALLOCATIONS_PREVIEW) {
                    setBody(
                        parametersOf(
                            FormFields.CSRF_TOKEN to listOf(csrf.value),
                            FormFields.SYMBOLS to listOf("BTC", "ETH"),
                            FormFields.TARGETS to listOf("50"),
                            FormFields.COLORS to listOf("#ff0000", "#00ff00"),
                            FormFields.SCORES to listOf("9.5", "8.5"),
                        ).formUrlEncode(),
                    )
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                    header(HttpHeaders.Cookie, csrf.cookie)
                }

                response.status shouldBe HttpStatusCode.UnprocessableEntity
                response.headers[HtmxHeaders.HX_RETARGET] shouldBe HtmxValues.BODY
                response.bodyAsText() shouldContain ViewText.INVALID_ALLOCATION_FIELDS
                coVerify(exactly = 0) { configService.updateConfig(any()) }
            }
        }

        "allocationsPreview_RejectsDuplicateSymbolsAndCanonicalAliases" {
            val serverConfig = dashboardConfig()
            every { configService.getConfig() } returns serverConfig

            testApplication {
                application { configureTestEnv() }
                val csrf = client.settingsCsrf()
                listOf(
                    listOf("BTC", "btc"),
                    listOf("BTC", "XBT"),
                    listOf("DOGE", "XDG"),
                ).forEach { symbols ->
                    val response = client.postAllocationPreview(
                        csrf.value,
                        csrf.cookie,
                        symbols = symbols,
                    )

                    response.status shouldBe HttpStatusCode.UnprocessableEntity
                    response.headers[HtmxHeaders.HX_RETARGET] shouldBe HtmxValues.BODY
                    response.bodyAsText() shouldContain ViewText.INVALID_ALLOCATION_FIELDS
                }
                coVerify(exactly = 0) { configService.updateConfig(any()) }
            }
        }

        "allocationsPreview_RejectsInvalidAndOutOfRangeScores" {
            val serverConfig = dashboardConfig()
            every { configService.getConfig() } returns serverConfig

            testApplication {
                application { configureTestEnv() }
                val csrf = client.settingsCsrf()
                listOf("not-a-number", "10.5", "-0.5").forEach { score ->
                    val response = client.post(Routes.FRAGMENT_SETTINGS_ALLOCATIONS_PREVIEW) {
                        setBody(
                            parametersOf(
                                FormFields.CSRF_TOKEN to listOf(csrf.value),
                                FormFields.SYMBOLS to listOf("BTC", "ETH"),
                                FormFields.TARGETS to listOf("50", "50"),
                                FormFields.COLORS to listOf("#ff0000", "#00ff00"),
                                FormFields.SCORES to listOf(score, "8.5"),
                            ).formUrlEncode(),
                        )
                        header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                        header(HttpHeaders.Cookie, csrf.cookie)
                    }

                    response.status shouldBe HttpStatusCode.UnprocessableEntity
                    response.bodyAsText() shouldContain ViewText.INVALID_ALLOCATION_SCORE
                }
                coVerify(exactly = 0) { configService.updateConfig(any()) }
            }
        }

        "allocationsPreview_RejectsOutOfRangeEmphasis" {
            val serverConfig = dashboardConfig()
            every { configService.getConfig() } returns serverConfig

            testApplication {
                application { configureTestEnv() }
                val csrf = client.settingsCsrf()
                val response = client.post(Routes.FRAGMENT_SETTINGS_ALLOCATIONS_PREVIEW) {
                    setBody(
                        parametersOf(
                            FormFields.CSRF_TOKEN to listOf(csrf.value),
                            FormFields.SYMBOLS to listOf("BTC", "ETH"),
                            FormFields.TARGETS to listOf("50", "50"),
                            FormFields.COLORS to listOf("#ff0000", "#00ff00"),
                            FormFields.SCORES to listOf("9.5", "8.5"),
                            // Above QualityAllocation.MAX_EMPHASIS; the form's max attribute is
                            // client-side only, so the server has to reject it itself.
                            FormFields.SCORE_EMPHASIS to listOf("99"),
                        ).formUrlEncode(),
                    )
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                    header(HttpHeaders.Cookie, csrf.cookie)
                }

                response.status shouldBe HttpStatusCode.UnprocessableEntity
                response.bodyAsText() shouldContain ViewText.INVALID_ALLOCATION_EMPHASIS
                coVerify(exactly = 0) { configService.updateConfig(any()) }
            }
        }

        "allocationsPreview_RejectsNonNumericEmphasis" {
            val serverConfig = dashboardConfig()
            every { configService.getConfig() } returns serverConfig

            testApplication {
                application { configureTestEnv() }
                val csrf = client.settingsCsrf()
                val response = client.post(Routes.FRAGMENT_SETTINGS_ALLOCATIONS_PREVIEW) {
                    setBody(
                        parametersOf(
                            FormFields.CSRF_TOKEN to listOf(csrf.value),
                            FormFields.SYMBOLS to listOf("BTC", "ETH"),
                            FormFields.TARGETS to listOf("50", "50"),
                            FormFields.COLORS to listOf("#ff0000", "#00ff00"),
                            FormFields.SCORES to listOf("9.5", "8.5"),
                            // Treated the same as an out-of-range value rather than silently
                            // becoming the flattest weighting the operator did not ask for.
                            FormFields.SCORE_EMPHASIS to listOf("not-a-number"),
                        ).formUrlEncode(),
                    )
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                    header(HttpHeaders.Cookie, csrf.cookie)
                }

                response.status shouldBe HttpStatusCode.UnprocessableEntity
                response.bodyAsText() shouldContain ViewText.INVALID_ALLOCATION_EMPHASIS
            }
        }

        "postSettings_PersistsQualityScores" {
            val serverConfig = dashboardConfig(
                credentials = KrakenCredentials(
                    apiKey = TestFixtures.TEST_SERVER_API_KEY,
                    privateKey = TestFixtures.TEST_SERVER_API_SECRET,
                ),
            )
            val captured = slot<AppConfig>()
            every { configService.getConfig() } returns serverConfig
            coEvery { configService.updateConfig(capture(captured)) } returns Unit
            coEvery { tradeHistoryService.getComparisonStartProposal(any()) } returns ComparisonStartProposal(
                status = ComparisonProposalStatus.VERIFIED,
                timestamp = Instant.parse("2026-06-07T00:00:00Z"),
                snapshotId = 42,
            )

            testApplication {
                application { configureTestEnv() }
                val csrf = client.settingsCsrf()
                client.post(Routes.SETTINGS) {
                    setBody(
                        parametersOf(
                            FormFields.LOOP_DELAY_SECONDS to listOf("60"),
                            FormFields.DEVIATION_TRIGGER_PERCENT to listOf("5.0"),
                            FormFields.MINIMUM_ORDER_SIZE_USD to listOf("20.0"),
                            FormFields.FIAT_MAX_DRAWDOWN to listOf("20.0"),
                            FormFields.FIAT_DEPLOYMENT_EXPONENT to listOf("1.0"),
                            FormFields.CSRF_TOKEN to listOf(csrf.value),
                            FormFields.DRY_RUN to listOf("on"),
                            FormFields.SYMBOLS to listOf("BTC", "TAO", "USD"),
                            FormFields.TARGETS to listOf("50", "30", "20"),
                            FormFields.COLORS to listOf("#ff0000", "#00ff00", "#94A3B8"),
                            // A zero score is dropped and a blank clears; a malformed entry is a
                            // validation error rather than a silent drop (see the test below).
                            FormFields.SCORES to listOf("9.5", "0", ""),
                        ).formUrlEncode(),
                    )
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                    header(HttpHeaders.Cookie, csrf.cookie)
                }

                captured.captured.settings.qualityScores shouldBe mapOf("BTC" to 9.5)
            }
        }

        "postSettings_RejectsNonNumericQualityScore" {
            val serverConfig = dashboardConfig(
                credentials = KrakenCredentials(
                    apiKey = TestFixtures.TEST_SERVER_API_KEY,
                    privateKey = TestFixtures.TEST_SERVER_API_SECRET,
                ),
            )
            every { configService.getConfig() } returns serverConfig

            testApplication {
                application { configureTestEnv() }
                val csrf = client.settingsCsrf()
                val response = client.post(Routes.SETTINGS) {
                    setBody(
                        parametersOf(
                            FormFields.LOOP_DELAY_SECONDS to listOf("60"),
                            FormFields.DEVIATION_TRIGGER_PERCENT to listOf("5.0"),
                            FormFields.MINIMUM_ORDER_SIZE_USD to listOf("20.0"),
                            FormFields.FIAT_MAX_DRAWDOWN to listOf("20.0"),
                            FormFields.FIAT_DEPLOYMENT_EXPONENT to listOf("1.0"),
                            FormFields.CSRF_TOKEN to listOf(csrf.value),
                            FormFields.DRY_RUN to listOf("on"),
                            FormFields.SYMBOLS to listOf("BTC", "TAO"),
                            FormFields.TARGETS to listOf("50", "30"),
                            FormFields.COLORS to listOf("#ff0000", "#00ff00"),
                            FormFields.SCORES to listOf("9.5", "oops"),
                        ).formUrlEncode(),
                    )
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                    header(HttpHeaders.Cookie, csrf.cookie)
                }

                response.status shouldBe HttpStatusCode.UnprocessableEntity
                response.bodyAsText() shouldContain ViewText.INVALID_ALLOCATION_SCORE
                coVerify(exactly = 0) { configService.updateConfig(any()) }
            }
        }

        "postSettings_RejectsNonFiniteOrOverMaxQualityScore" {
            val serverConfig = dashboardConfig(
                credentials = KrakenCredentials(
                    apiKey = TestFixtures.TEST_SERVER_API_KEY,
                    privateKey = TestFixtures.TEST_SERVER_API_SECRET,
                ),
            )
            every { configService.getConfig() } returns serverConfig

            testApplication {
                application { configureTestEnv() }
                val csrf = client.settingsCsrf()
                listOf("1e400", "10.5").forEach { score ->
                    val response = client.post(Routes.SETTINGS) {
                        setBody(
                            parametersOf(
                                FormFields.LOOP_DELAY_SECONDS to listOf("60"),
                                FormFields.DEVIATION_TRIGGER_PERCENT to listOf("5.0"),
                                FormFields.MINIMUM_ORDER_SIZE_USD to listOf("20.0"),
                                FormFields.FIAT_MAX_DRAWDOWN to listOf("20.0"),
                                FormFields.FIAT_DEPLOYMENT_EXPONENT to listOf("1.0"),
                                FormFields.CSRF_TOKEN to listOf(csrf.value),
                                FormFields.DRY_RUN to listOf("on"),
                                FormFields.SYMBOLS to listOf("BTC", "TAO"),
                                FormFields.TARGETS to listOf("50", "30"),
                                FormFields.COLORS to listOf("#ff0000", "#00ff00"),
                                // The client-side max is not an input validation boundary.
                                FormFields.SCORES to listOf(score, "8.0"),
                            ).formUrlEncode(),
                        )
                        header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                        header(HttpHeaders.Cookie, csrf.cookie)
                    }

                    response.status shouldBe HttpStatusCode.UnprocessableEntity
                    response.bodyAsText() shouldContain ViewText.INVALID_ALLOCATION_SCORE
                }
                coVerify(exactly = 0) { configService.updateConfig(any()) }
            }
        }

        "allocationsPreview_RejectsMalformedOrNegativeTargets" {
            val serverConfig = dashboardConfig()
            every { configService.getConfig() } returns serverConfig

            testApplication {
                application { configureTestEnv() }
                val csrf = client.settingsCsrf()
                listOf("not-a-target", "-1", "1e400").forEach { invalidTarget ->
                    val response = client.postAllocationPreview(
                        csrf.value,
                        csrf.cookie,
                        symbols = listOf("BTC", "ETH", "USD"),
                        targets = listOf("50", invalidTarget, "20"),
                        colors = listOf("#ff0000", "#00ff00", "#0000ff"),
                        scores = listOf("9.5", "8.0", ""),
                    )

                    response.status shouldBe HttpStatusCode.UnprocessableEntity
                    response.headers[HtmxHeaders.HX_RETARGET] shouldBe HtmxValues.BODY
                    response.bodyAsText() shouldContain ViewText.INVALID_ALLOCATION_TARGET
                }
                coVerify(exactly = 0) { configService.updateConfig(any()) }
            }
        }

        "allocationsPreview_RejectsShortScoreArray" {
            val serverConfig = dashboardConfig()
            every { configService.getConfig() } returns serverConfig

            testApplication {
                application { configureTestEnv() }
                val csrf = client.settingsCsrf()
                val response = client.postAllocationPreview(
                    csrf.value,
                    csrf.cookie,
                    scores = listOf("9.0"),
                )

                response.status shouldBe HttpStatusCode.UnprocessableEntity
                response.headers[HtmxHeaders.HX_RETARGET] shouldBe HtmxValues.BODY
                response.bodyAsText() shouldContain ViewText.INVALID_ALLOCATION_FIELDS
                coVerify(exactly = 0) { configService.updateConfig(any()) }
            }
        }

        "allocationsPreview_RejectsSymbolsThatCannotBeSaved" {
            val serverConfig = dashboardConfig()
            every { configService.getConfig() } returns serverConfig

            testApplication {
                application { configureTestEnv() }
                val csrf = client.settingsCsrf()
                val response = client.postAllocationPreview(
                    csrf.value,
                    csrf.cookie,
                    symbols = listOf("BTC!", "USD"),
                )

                response.status shouldBe HttpStatusCode.UnprocessableEntity
                response.headers[HtmxHeaders.HX_RETARGET] shouldBe HtmxValues.BODY
                response.bodyAsText() shouldContain ViewText.INVALID_ALLOCATION_FIELDS
                coVerify(exactly = 0) { configService.updateConfig(any()) }
            }
        }

        "allocationsPreview_RejectsUnsavableColors" {
            val serverConfig = dashboardConfig()
            every { configService.getConfig() } returns serverConfig

            testApplication {
                application { configureTestEnv() }
                val csrf = client.settingsCsrf()
                val response = client.postAllocationPreview(
                    csrf.value,
                    csrf.cookie,
                    colors = listOf("invalid-color", "#00ff00"),
                )

                response.status shouldBe HttpStatusCode.UnprocessableEntity
                response.headers[HtmxHeaders.HX_RETARGET] shouldBe HtmxValues.BODY
                response.bodyAsText() shouldContain ViewText.INVALID_ALLOCATION_COLOR
                coVerify(exactly = 0) { configService.updateConfig(any()) }
            }
        }

        "allocationsPreview_RejectsInvalidExplicitSleeves" {
            val serverConfig = dashboardConfig()
            every { configService.getConfig() } returns serverConfig

            testApplication {
                application { configureTestEnv() }
                val csrf = client.settingsCsrf()
                listOf("malformed", "0", "-1", "101").forEach { invalidSleeve ->
                    val response = client.postAllocationPreview(
                        csrf.value,
                        csrf.cookie,
                        sleeve = invalidSleeve,
                    )

                    response.status shouldBe HttpStatusCode.UnprocessableEntity
                    response.headers[HtmxHeaders.HX_RETARGET] shouldBe HtmxValues.BODY
                    response.bodyAsText() shouldContain ViewText.INVALID_ALLOCATION_TARGET
                }
                coVerify(exactly = 0) { configService.updateConfig(any()) }
            }
        }

        "allocationsPreview_RejectsExplicitSleeveThatBreaksWholeBookTotal" {
            val serverConfig = dashboardConfig()
            every { configService.getConfig() } returns serverConfig

            testApplication {
                application { configureTestEnv() }
                val csrf = client.settingsCsrf()
                val response = client.postAllocationPreview(
                    csrf.value,
                    csrf.cookie,
                    symbols = listOf("BTC", "ETH", "USD"),
                    targets = listOf("25", "25", "50"),
                    colors = listOf("#ff0000", "#00ff00", "#0000ff"),
                    scores = listOf("9.0", "8.0", ""),
                    sleeve = "60",
                )

                response.status shouldBe HttpStatusCode.UnprocessableEntity
                response.headers[HtmxHeaders.HX_RETARGET] shouldBe HtmxValues.BODY
                response.bodyAsText() shouldContain ViewText.INVALID_ALLOCATION_TARGET
                coVerify(exactly = 0) { configService.updateConfig(any()) }
            }
        }

        "allocationsPreview_FallsBackToConfiguredTargetsWhenSleeveIsBlank" {
            val serverConfig = dashboardConfig()
            every { configService.getConfig() } returns serverConfig

            testApplication {
                application { configureTestEnv() }
                val csrf = client.settingsCsrf()
                val response = client.post(Routes.FRAGMENT_SETTINGS_ALLOCATIONS_PREVIEW) {
                    setBody(
                        parametersOf(
                            FormFields.CSRF_TOKEN to listOf(csrf.value),
                            FormFields.SYMBOLS to listOf("BTC", "TAO"),
                            FormFields.TARGETS to listOf("70", "30"),
                            FormFields.COLORS to listOf("#ff0000", "#00ff00"),
                            FormFields.SCORES to listOf("9.5", "6.0"),
                            FormFields.SCORE_SLEEVE_PERCENT to listOf(""),
                        ).formUrlEncode(),
                    )
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                    header(HttpHeaders.Cookie, csrf.cookie)
                }

                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain "Preview only"
            }
        }

        "allocationsPreview_UsesExplicitPositiveSleeve" {
            val serverConfig = dashboardConfig()
            every { configService.getConfig() } returns serverConfig

            testApplication {
                application { configureTestEnv() }
                val csrf = client.settingsCsrf()
                val response = client.post(Routes.FRAGMENT_SETTINGS_ALLOCATIONS_PREVIEW) {
                    setBody(
                        parametersOf(
                            FormFields.CSRF_TOKEN to listOf(csrf.value),
                            FormFields.SYMBOLS to listOf("BTC", "ETH", "USD"),
                            FormFields.TARGETS to listOf("25", "25", "50"),
                            FormFields.COLORS to listOf("#ff0000", "#00ff00", "#0000ff"),
                            FormFields.SCORES to listOf("9.0", "9.0", ""),
                            FormFields.SCORE_SLEEVE_PERCENT to listOf("50"),
                        ).formUrlEncode(),
                    )
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                    header(HttpHeaders.Cookie, csrf.cookie)
                }

                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain "value=\"25.00\""
            }
        }

        "allocationsPreview_RejectsAutoSleeveWhenScoredTargetsAreZero" {
            val serverConfig = dashboardConfig()
            every { configService.getConfig() } returns serverConfig

            testApplication {
                application { configureTestEnv() }
                val csrf = client.settingsCsrf()
                val response = client.post(Routes.FRAGMENT_SETTINGS_ALLOCATIONS_PREVIEW) {
                    setBody(
                        parametersOf(
                            FormFields.CSRF_TOKEN to listOf(csrf.value),
                            FormFields.SYMBOLS to listOf("BTC", "USD"),
                            FormFields.TARGETS to listOf("0", "100"),
                            FormFields.COLORS to listOf("#ff0000", "#0000ff"),
                            FormFields.SCORES to listOf("9.0", ""),
                            FormFields.SCORE_SLEEVE_PERCENT to listOf(""),
                        ).formUrlEncode(),
                    )
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                    header(HttpHeaders.Cookie, csrf.cookie)
                }

                response.status shouldBe HttpStatusCode.UnprocessableEntity
                response.headers[HtmxHeaders.HX_RETARGET] shouldBe HtmxValues.BODY
                response.bodyAsText() shouldContain ViewText.INVALID_ALLOCATION_TARGET
                coVerify(exactly = 0) { configService.updateConfig(any()) }
            }
        }

        "allocationsPreview_RejectsWhenAllTargetsZero" {
            val serverConfig = dashboardConfig()
            every { configService.getConfig() } returns serverConfig

            testApplication {
                application { configureTestEnv() }
                val csrf = client.settingsCsrf()
                val response = client.post(Routes.FRAGMENT_SETTINGS_ALLOCATIONS_PREVIEW) {
                    setBody(
                        parametersOf(
                            FormFields.CSRF_TOKEN to listOf(csrf.value),
                            FormFields.SYMBOLS to listOf("BTC", "ETH"),
                            FormFields.TARGETS to listOf("0", "0"),
                            FormFields.COLORS to listOf("#ff0000", "#00ff00"),
                            FormFields.SCORES to listOf("9.0", "8.0"),
                            FormFields.SCORE_SLEEVE_PERCENT to listOf("0"),
                        ).formUrlEncode(),
                    )
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                    header(HttpHeaders.Cookie, csrf.cookie)
                }

                response.status shouldBe HttpStatusCode.UnprocessableEntity
                response.headers[HtmxHeaders.HX_RETARGET] shouldBe HtmxValues.BODY
                response.bodyAsText() shouldContain ViewText.INVALID_ALLOCATION_TARGET
                coVerify(exactly = 0) { configService.updateConfig(any()) }
            }
        }
    }
}
