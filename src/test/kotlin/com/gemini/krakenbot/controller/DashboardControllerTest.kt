package com.gemini.krakenbot.controller

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.InvalidConfigurationException
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.view.util.ViewText
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
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
import io.ktor.server.application.Application
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.every
import io.mockk.slot
import io.mockk.verify
import java.math.BigDecimal
import java.time.Instant

// Keep HTTP boundary expectations independent from the generated common catalogs.
private object Routes {
    const val ROOT = "/"
    const val SETTINGS = "/settings"
    const val FRAGMENT_DASHBOARD = "/fragments/dashboard"
    const val API_STATUS_STREAM = "/api/status/stream"
    const val STATIC_STYLE_CSS = "/static/style.css"
    const val STATIC_REBALANCER_JS = "/static/rebalancer.js"
}

private object FormFields {
    const val CSRF_TOKEN = "csrfToken"
    const val LOOP_DELAY_SECONDS = "loopDelaySeconds"
    const val DEVIATION_TRIGGER_PERCENT = "deviationTriggerPercent"
    const val DUST_THRESHOLD_USD = "dustThresholdUSD"
    const val DRY_RUN = "dryRun"
    const val SIMULATION = "simulation"
    const val FIAT_MAX_DRAWDOWN = "fiatMaxDrawdown"
    const val FIAT_DEPLOYMENT_EXPONENT = "fiatDeploymentExponent"
    const val SYMBOLS = "symbols"
    const val TARGETS = "targets"
    const val COLORS = "colors"
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
                settings = TestFixtures.settings(loopDelaySeconds = 60L, dustThresholdUSD = 5.0),
                credentials = KrakenCredentials(apiKey = TestFixtures.TEST_API_KEY, privateKey = "k"),
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
                body shouldContain ViewText.TOTAL_PORTFOLIO
                body shouldContain ViewText.CASH_USD
                body shouldContain ViewText.CRYPTO_ASSETS
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
                response.bodyAsText() shouldContain ViewText.GLOBAL_PARAMETERS
                response.bodyAsText() shouldContain FormFields.LOOP_DELAY_SECONDS
            }
        }

        "postSettings_RejectsMissingCsrfToken" {
            every { configService.getConfig() } returns dashboardConfig()
            every { configService.updateConfig(any()) } returns Unit

            testApplication {
                application {
                    configureTestEnv()
                }
                val response = client.post(Routes.SETTINGS) {
                    setBody(parametersOf().formUrlEncode())
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                }

                response.status shouldBe HttpStatusCode.Forbidden
                response.bodyAsText() shouldContain ViewText.CSRF_SESSION_EXPIRED
                response.bodyAsText() shouldContain "name=\"${FormFields.CSRF_TOKEN}\""
                response.headers[HttpHeaders.SetCookie].shouldNotBeNull()
                verify(exactly = 0) { configService.updateConfig(any()) }
            }
        }

        "postSettings_RejectsWrongCsrfTokenAndRotatesRecoveryToken" {
            val serverConfig = dashboardConfig()
            every { configService.getConfig() } returns serverConfig
            every { configService.updateConfig(any()) } returns Unit

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
                }

                val responseBody = response.bodyAsText()
                val newToken =
                    Regex("""name="${FormFields.CSRF_TOKEN}" value="([^"]+)"""")
                        .find(responseBody)
                        ?.groupValues
                        ?.get(1)
                        ?: error("CSRF recovery response did not contain a token")
                response.status shouldBe HttpStatusCode.Forbidden
                responseBody shouldContain ViewText.CSRF_SESSION_EXPIRED
                response.headers[HtmxHeaders.HX_REFRESH] shouldBe HtmxValues.TRUE
                response.headers[HtmxHeaders.HX_RESWAP] shouldBe HtmxValues.INNER_HTML
                response.headers[HtmxHeaders.HX_RETARGET] shouldBe HtmxValues.BODY
                (newToken != csrf.value).shouldBeTrue()
                val newCookie = response.headers[HttpHeaders.SetCookie]?.substringBefore(';')
                    ?: error("CSRF recovery response did not set a cookie")
                newCookie.contains(newToken).shouldBeTrue()

                val followUpResponse = client.post(Routes.SETTINGS) {
                    setBody(
                        parametersOf(
                            FormFields.LOOP_DELAY_SECONDS to listOf("60"),
                            FormFields.DEVIATION_TRIGGER_PERCENT to listOf("2.0"),
                            FormFields.DUST_THRESHOLD_USD to listOf("1.0"),
                            FormFields.FIAT_MAX_DRAWDOWN to listOf("0.0"),
                            FormFields.FIAT_DEPLOYMENT_EXPONENT to listOf("1.0"),
                            FormFields.CSRF_TOKEN to listOf(newToken),
                            FormFields.SYMBOLS to listOf(Asset.USD),
                            FormFields.TARGETS to listOf("100.0"),
                            FormFields.COLORS to listOf("#94a3b8"),
                        ).formUrlEncode(),
                    )
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                    header(HttpHeaders.Cookie, newCookie)
                }
                followUpResponse.status shouldBe HttpStatusCode.OK
                followUpResponse.headers[HtmxHeaders.HX_REDIRECT] shouldBe Routes.ROOT
                verify(exactly = 1) { configService.updateConfig(any()) }
            }
        }

        "postSettings_RejectsMissingFormTokenWithCookie" {
            val serverConfig = dashboardConfig()
            every { configService.getConfig() } returns serverConfig
            every { configService.updateConfig(any()) } returns Unit

            testApplication {
                application {
                    configureTestEnv()
                }
                val csrf = client.settingsCsrf()
                val response = client.post(Routes.SETTINGS) {
                    setBody(parametersOf().formUrlEncode())
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                    header(HttpHeaders.Cookie, csrf.cookie)
                }

                response.status shouldBe HttpStatusCode.Forbidden
                response.bodyAsText() shouldContain ViewText.CSRF_SESSION_EXPIRED
                verify(exactly = 0) { configService.updateConfig(any()) }
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
            every { configService.updateConfig(capture(captured)) } returns Unit

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
                                FormFields.DUST_THRESHOLD_USD to listOf("2.0"),
                                FormFields.FIAT_MAX_DRAWDOWN to listOf("5.0"),
                                FormFields.FIAT_DEPLOYMENT_EXPONENT to listOf("1.5"),
                                FormFields.CSRF_TOKEN to listOf(csrf.value),
                                FormFields.DRY_RUN to listOf("on"),
                                FormFields.SIMULATION to listOf("on"),
                                FormFields.SYMBOLS to listOf(Asset.USD),
                                FormFields.TARGETS to listOf("100.0"),
                                FormFields.COLORS to listOf("#94A3B8"),
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
            captured.captured.allocations.single().color shouldBe "#94a3b8"
            verify { configService.updateConfig(any()) }
        }

        "CQ-12-L1: post settings rejects unpaired allocation fields without updating config" {
            val serverConfig = dashboardConfig(
                credentials = KrakenCredentials(
                    apiKey = TestFixtures.TEST_SERVER_API_KEY,
                    privateKey = TestFixtures.TEST_SERVER_API_SECRET,
                ),
            )
            every { configService.getConfig() } returns serverConfig
            every { configService.updateConfig(any()) } returns Unit

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
                            FormFields.DUST_THRESHOLD_USD to listOf("1.0"),
                            FormFields.FIAT_MAX_DRAWDOWN to listOf("0.0"),
                            FormFields.FIAT_DEPLOYMENT_EXPONENT to listOf("1.0"),
                            FormFields.CSRF_TOKEN to listOf(csrf.value),
                            FormFields.SYMBOLS to listOf(Asset.USD, Asset.BTC),
                            FormFields.TARGETS to listOf("100.0"),
                            FormFields.COLORS to listOf("#94a3b8", "#fbbf24"),
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
                                FormFields.DUST_THRESHOLD_USD to listOf("1.0"),
                                FormFields.FIAT_MAX_DRAWDOWN to listOf("0.0"),
                                FormFields.FIAT_DEPLOYMENT_EXPONENT to listOf("1.0"),
                                FormFields.CSRF_TOKEN to listOf(csrf.value),
                                FormFields.SYMBOLS to listOf(Asset.USD),
                                FormFields.TARGETS to listOf("100.0"),
                                FormFields.COLORS to listOf("#94a3b8"),
                                FormFields.COLORS to listOf("not-a-color"),
                            ).formUrlEncode(),
                        )
                        header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                        header(HttpHeaders.Cookie, csrf.cookie)
                    }
                invalidColorResponse.bodyAsText() shouldContain ViewText.INVALID_ALLOCATION_COLOR
            }

            verify(exactly = 0) { configService.updateConfig(any()) }
        }

        "CQ-12-L1: post settings rejects malformed required trading values before persistence" {
            val serverConfig = dashboardConfig(
                settings = TestFixtures.settings(loopDelaySeconds = 60, dustThresholdUSD = 5.0),
                credentials = KrakenCredentials(TestFixtures.TEST_SERVER_API_KEY, TestFixtures.TEST_SERVER_API_SECRET),
            )
            every { configService.getConfig() } returns serverConfig
            every { configService.updateConfig(any()) } returns Unit

            val validFields =
                mapOf(
                    FormFields.LOOP_DELAY_SECONDS to "60",
                    FormFields.DEVIATION_TRIGGER_PERCENT to "2.0",
                    FormFields.DUST_THRESHOLD_USD to "1.0",
                    FormFields.FIAT_MAX_DRAWDOWN to "5.0",
                    FormFields.FIAT_DEPLOYMENT_EXPONENT to "1.5",
                    FormFields.TARGETS to "100.0",
                )
            val invalidValues =
                mapOf(
                    FormFields.LOOP_DELAY_SECONDS to "not-a-long",
                    FormFields.DEVIATION_TRIGGER_PERCENT to "NaN",
                    FormFields.DUST_THRESHOLD_USD to "Infinity",
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
                                    FormFields.DUST_THRESHOLD_USD to
                                        listOf(fields.getValue(FormFields.DUST_THRESHOLD_USD)),
                                    FormFields.FIAT_MAX_DRAWDOWN to
                                        listOf(fields.getValue(FormFields.FIAT_MAX_DRAWDOWN)),
                                    FormFields.FIAT_DEPLOYMENT_EXPONENT to
                                        listOf(fields.getValue(FormFields.FIAT_DEPLOYMENT_EXPONENT)),
                                    FormFields.CSRF_TOKEN to listOf(csrf.value),
                                    FormFields.SYMBOLS to listOf(Asset.USD),
                                    FormFields.TARGETS to listOf(fields.getValue(FormFields.TARGETS)),
                                    FormFields.COLORS to listOf("#94a3b8"),
                                ).formUrlEncode(),
                            )
                            header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                            header(HttpHeaders.Cookie, csrf.cookie)
                        }
                    response.bodyAsText() shouldContain "Invalid settings field"
                }
            }

            verify(exactly = 0) { configService.updateConfig(any()) }
        }

        "CQ-12-L1: post settings rejects mismatched colors without updating config" {
            val serverConfig = dashboardConfig(
                settings = TestFixtures.settings(loopDelaySeconds = 60, dustThresholdUSD = 5.0),
                credentials = KrakenCredentials(TestFixtures.TEST_SERVER_API_KEY, TestFixtures.TEST_SERVER_API_SECRET),
            )
            every { configService.getConfig() } returns serverConfig
            every { configService.updateConfig(any()) } returns Unit

            testApplication {
                application { configureTestEnv() }
                val csrf = client.settingsCsrf()
                val response =
                    client.post(Routes.SETTINGS) {
                        setBody(
                            parametersOf(
                                FormFields.LOOP_DELAY_SECONDS to listOf("60"),
                                FormFields.DEVIATION_TRIGGER_PERCENT to listOf("2.0"),
                                FormFields.DUST_THRESHOLD_USD to listOf("1.0"),
                                FormFields.FIAT_MAX_DRAWDOWN to listOf("0.0"),
                                FormFields.FIAT_DEPLOYMENT_EXPONENT to listOf("1.0"),
                                FormFields.CSRF_TOKEN to listOf(csrf.value),
                                FormFields.SYMBOLS to listOf(Asset.USD, Asset.BTC),
                                FormFields.TARGETS to listOf("50.0", "50.0"),
                                FormFields.COLORS to listOf("#94a3b8"),
                            ).formUrlEncode(),
                        )
                        header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                        header(HttpHeaders.Cookie, csrf.cookie)
                    }
                response.bodyAsText() shouldContain ViewText.INVALID_ALLOCATION_FIELDS
            }

            verify(exactly = 0) { configService.updateConfig(any()) }
        }

        "CQ-12-L1: post settings rejects duplicate singleton values without updating config" {
            val serverConfig = dashboardConfig(
                settings = Settings(loopDelaySeconds = 60, deviationTriggerPercent = 2.0, dryRun = true),
                credentials = KrakenCredentials(TestFixtures.TEST_SERVER_API_KEY, TestFixtures.TEST_SERVER_API_SECRET),
            )
            every { configService.getConfig() } returns serverConfig
            every { configService.updateConfig(any()) } returns Unit

            testApplication {
                application { configureTestEnv() }
                val csrf = client.settingsCsrf()
                val response =
                    client.post(Routes.SETTINGS) {
                        setBody(
                            parametersOf(
                                FormFields.LOOP_DELAY_SECONDS to listOf("60"),
                                FormFields.DEVIATION_TRIGGER_PERCENT to listOf("2.0", "3.0"),
                                FormFields.DUST_THRESHOLD_USD to listOf("1.0"),
                                FormFields.FIAT_MAX_DRAWDOWN to listOf("0.0"),
                                FormFields.FIAT_DEPLOYMENT_EXPONENT to listOf("1.0"),
                                FormFields.CSRF_TOKEN to listOf(csrf.value),
                                FormFields.SYMBOLS to listOf(Asset.USD),
                                FormFields.TARGETS to listOf("100.0"),
                                FormFields.COLORS to listOf("#94a3b8"),
                            ).formUrlEncode(),
                        )
                        header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                        header(HttpHeaders.Cookie, csrf.cookie)
                    }
                response.bodyAsText() shouldContain ViewText.INVALID_DEVIATION_TRIGGER
            }

            verify(exactly = 0) { configService.updateConfig(any()) }
        }

        "postSettings_OnValidationError_ReturnsErrorHtmlBody" {
            val serverConfig = dashboardConfig(
                credentials = KrakenCredentials(
                    apiKey = TestFixtures.TEST_SERVER_API_KEY,
                    privateKey = TestFixtures.TEST_SERVER_API_SECRET,
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
                val csrf = client.settingsCsrf()
                val response =
                    client.post(Routes.SETTINGS) {
                        setBody(
                            parametersOf(
                                FormFields.LOOP_DELAY_SECONDS to listOf("60"),
                                FormFields.DEVIATION_TRIGGER_PERCENT to listOf("2.0"),
                                FormFields.DUST_THRESHOLD_USD to listOf("1.0"),
                                FormFields.FIAT_MAX_DRAWDOWN to listOf("0.0"),
                                FormFields.FIAT_DEPLOYMENT_EXPONENT to listOf("1.0"),
                                FormFields.CSRF_TOKEN to listOf(csrf.value),
                                FormFields.SYMBOLS to listOf(Asset.USD),
                                FormFields.TARGETS to listOf("90.0"),
                                FormFields.COLORS to listOf("#94a3b8"),
                            ).formUrlEncode(),
                        )
                        header(
                            HttpHeaders.ContentType,
                            ContentType.Application.FormUrlEncoded.toString(),
                        )
                        header(HttpHeaders.Cookie, csrf.cookie)
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

        "postSettings_WithInvalidDeviationTrigger_RejectsWithoutUpdatingConfig" {
            val serverConfig = dashboardConfig(
                credentials = KrakenCredentials(
                    apiKey = TestFixtures.TEST_SERVER_API_KEY,
                    privateKey = TestFixtures.TEST_SERVER_API_SECRET,
                ),
            )
            every { configService.getConfig() } returns serverConfig
            every { configService.updateConfig(any()) } returns Unit

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
                                FormFields.DUST_THRESHOLD_USD to listOf("5.0"),
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
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain ViewText.INVALID_DEVIATION_TRIGGER
            }

            verify(exactly = 0) { configService.updateConfig(any()) }
        }

        "postSettings_WithInvalidDustThreshold_RejectsWithoutUpdatingConfig" {
            val serverConfig = dashboardConfig(
                credentials = KrakenCredentials(
                    apiKey = TestFixtures.TEST_SERVER_API_KEY,
                    privateKey = TestFixtures.TEST_SERVER_API_SECRET,
                ),
            )
            every { configService.getConfig() } returns serverConfig
            every { configService.updateConfig(any()) } returns Unit

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
                                FormFields.DUST_THRESHOLD_USD to listOf(TestFixtures.INVALID),
                                FormFields.CSRF_TOKEN to listOf(csrf.value),
                                FormFields.SYMBOLS to listOf(Asset.USD),
                                FormFields.TARGETS to listOf("100.0"),
                                FormFields.COLORS to listOf("#94a3b8"),
                            ).formUrlEncode(),
                        )
                        header(
                            HttpHeaders.ContentType,
                            ContentType.Application.FormUrlEncoded.toString(),
                        )
                        header(HttpHeaders.Cookie, csrf.cookie)
                    }
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain ViewText.INVALID_DUST_THRESHOLD
            }

            verify(exactly = 0) { configService.updateConfig(any()) }
        }

        "postSettings_WithAbsentDeviationAndDust_RejectsWithoutUpdatingConfig" {
            val serverConfig = dashboardConfig(
                credentials = KrakenCredentials(
                    apiKey = TestFixtures.TEST_SERVER_API_KEY,
                    privateKey = TestFixtures.TEST_SERVER_API_SECRET,
                ),
            )
            every { configService.getConfig() } returns serverConfig
            every { configService.updateConfig(any()) } returns Unit

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
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain ViewText.INVALID_DEVIATION_TRIGGER
            }

            verify(exactly = 0) { configService.updateConfig(any()) }
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
                val csrf = client.settingsCsrf()
                val response =
                    client.post(Routes.SETTINGS) {
                        setBody(
                            parametersOf(
                                FormFields.LOOP_DELAY_SECONDS to listOf("60"),
                                FormFields.DEVIATION_TRIGGER_PERCENT to listOf("5.0"),
                                FormFields.DUST_THRESHOLD_USD to listOf("5.0"),
                                FormFields.FIAT_MAX_DRAWDOWN to listOf("0.0"),
                                FormFields.FIAT_DEPLOYMENT_EXPONENT to listOf("1.0"),
                                FormFields.CSRF_TOKEN to listOf(csrf.value),
                                FormFields.SYMBOLS to listOf(Asset.USD),
                                FormFields.TARGETS to listOf("100.0"),
                                FormFields.COLORS to listOf("#94a3b8"),
                            ).formUrlEncode(),
                        )
                        header(
                            HttpHeaders.ContentType,
                            ContentType.Application.FormUrlEncoded.toString(),
                        )
                        header(HttpHeaders.Cookie, csrf.cookie)
                    }
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain ViewText.INVALID_CONFIGURATION_FALLBACK
            }

            capturedConfig.captured.settings.deviationTriggerPercent shouldBe 5.0
            capturedConfig.captured.settings.dustThresholdUSD shouldBe 5.0
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
                setCookie shouldNotContain "Max-Age"
                setCookie shouldNotContain "Domain="
            }
        }

        "postSettings_RejectsDuplicateMatchingCsrfFormTokens" {
            val serverConfig = dashboardConfig()
            every { configService.getConfig() } returns serverConfig
            every { configService.updateConfig(any()) } returns Unit

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
                }

                response.status shouldBe HttpStatusCode.Forbidden
                response.bodyAsText() shouldContain ViewText.CSRF_SESSION_EXPIRED
                response.headers[HttpHeaders.SetCookie].shouldNotBeNull()
                verify(exactly = 0) { configService.updateConfig(any()) }
            }
        }
    }
}
