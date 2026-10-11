package com.gemini.krakenbot.controller

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.InvalidConfigurationException
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.OrderIntent
import com.gemini.krakenbot.model.OrderIntentState
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.service.RebalanceOperationalStatus
import com.gemini.krakenbot.view.util.FormFields
import com.gemini.krakenbot.view.util.HtmxHeaders
import com.gemini.krakenbot.view.util.HtmxValues
import com.gemini.krakenbot.view.util.Routes
import com.gemini.krakenbot.view.util.ViewText
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.slot
import java.math.BigDecimal
import java.time.Instant

class DashboardMutationRoutesTest : DashboardControllerTestBase() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    private fun requestFields(csrf: String, values: Map<String, List<String>> = emptyMap()) =
        parametersOf(values + (FormFields.CSRF_TOKEN to listOf(csrf)))

    private fun allocationFields(
        csrf: String,
        symbols: List<String> = listOf("BTC", "ETH", "USD"),
        targets: List<String> = listOf("50", "30", "20"),
        colors: List<String> = listOf("#ff0000", "#00ff00", "#0000ff"),
        scores: List<String> = listOf("9.5", "8.5", ""),
        emphasis: String? = "2",
        sleeve: String? = null,
    ) = requestFields(
        csrf,
        buildMap {
            put(FormFields.SYMBOLS, symbols)
            put(FormFields.TARGETS, targets)
            put(FormFields.COLORS, colors)
            put(FormFields.SCORES, scores)
            emphasis?.let { put(FormFields.SCORE_EMPHASIS, listOf(it)) }
            sleeve?.let { put(FormFields.SCORE_SLEEVE_PERCENT, listOf(it)) }
        },
    )

    private suspend fun io.ktor.client.HttpClient.postForm(
        path: String,
        params: io.ktor.http.Parameters,
        cookie: String,
        htmx: Boolean = false,
    ) = post(path) {
        setBody(params.formUrlEncode())
        header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
        header(HttpHeaders.Cookie, cookie)
        header(HttpHeaders.Origin, "http://localhost")
        if (htmx) header("HX-Request", "true")
    }

    init {
        "settings update saves validated current fields and preserves only positive quality scores" {
            every { configService.getConfig() } returns TestFixtures.DEFAULT_TEST_CONFIG
            val captured = slot<AppConfig>()
            coEvery { configService.updateConfig(capture(captured)) } returns Unit

            testApplication {
                application { configureTestEnv() }
                val csrf = client.settingsCsrf()
                val response = client.postForm(
                    Routes.SETTINGS,
                    requestFields(
                        csrf.value,
                        mapOf(
                            FormFields.LOOP_DELAY_SECONDS to listOf("75"),
                            FormFields.DEVIATION_TRIGGER_PERCENT to listOf("3.25"),
                            FormFields.MINIMUM_ORDER_SIZE_USD to listOf("7.5"),
                            FormFields.DRY_RUN to listOf("on"),
                            FormFields.SIMULATION to listOf("on"),
                            FormFields.SYMBOLS to listOf("BTC", "ETH", "USD"),
                            FormFields.TARGETS to listOf("45", "35", "20"),
                            FormFields.COLORS to listOf("#FF0000", "", "#0000ff"),
                            FormFields.SCORES to listOf("9.5", "0", ""),
                        ),
                    ),
                    csrf.cookie,
                )

                response.status shouldBe HttpStatusCode.OK
                response.headers[HtmxHeaders.HX_REDIRECT] shouldBe Routes.ROOT
                captured.captured.settings.loopDelaySeconds shouldBe 75L
                captured.captured.settings.deviationTriggerPercent shouldBe 3.25
                captured.captured.settings.minimumOrderSizeUSD shouldBe 7.5
                captured.captured.settings.dryRun shouldBe true
                captured.captured.settings.simulation shouldBe true
                captured.captured.settings.qualityScores shouldBe mapOf("BTC" to 9.5)
                captured.captured.allocations.map { it.symbol.value } shouldBe listOf("BTC", "ETH", "USD")
                captured.captured.allocations[0].color shouldBe "#ff0000"
                captured.captured.allocations[1].color shouldBe null
                coVerify(exactly = 1) { configService.updateConfig(any()) }
            }
        }

        "settings update rejects malformed values and config validation while retaining entered values" {
            every { configService.getConfig() } returns TestFixtures.DEFAULT_TEST_CONFIG

            testApplication {
                application { configureTestEnv() }
                val csrf = client.settingsCsrf()
                val malformed = client.postForm(
                    Routes.SETTINGS,
                    requestFields(
                        csrf.value,
                        mapOf(
                            FormFields.DEVIATION_TRIGGER_PERCENT to listOf("2"),
                            FormFields.MINIMUM_ORDER_SIZE_USD to listOf("5"),
                            FormFields.LOOP_DELAY_SECONDS to listOf("nope"),
                        ),
                    ),
                    csrf.cookie,
                )
                malformed.status shouldBe HttpStatusCode.UnprocessableEntity
                malformed.bodyAsText() shouldContain ViewText.INVALID_LOOP_DELAY
                malformed.bodyAsText() shouldContain "value=\"nope\""

                coEvery { configService.updateConfig(any()) } throws InvalidConfigurationException("allocation total")
                val rejected = client.postForm(
                    Routes.SETTINGS,
                    requestFields(
                        csrf.value,
                        mapOf(
                            FormFields.LOOP_DELAY_SECONDS to listOf("60"),
                            FormFields.DEVIATION_TRIGGER_PERCENT to listOf("2"),
                            FormFields.MINIMUM_ORDER_SIZE_USD to listOf("5"),
                            FormFields.SYMBOLS to listOf("BTC", "USD"),
                            FormFields.TARGETS to listOf("60", "30"),
                            FormFields.COLORS to listOf("#ff0000", "#0000ff"),
                            FormFields.SCORES to listOf("8", ""),
                        ),
                    ),
                    csrf.cookie,
                )
                rejected.status shouldBe HttpStatusCode.UnprocessableEntity
                rejected.bodyAsText() shouldContain "allocation total"
                coVerify(exactly = 1) { configService.updateConfig(any()) }
            }
        }

        "allocation preview computes whole-book concentration and rejects invalid row and score data" {
            every { configService.getConfig() } returns TestFixtures.DEFAULT_TEST_CONFIG

            testApplication {
                application { configureTestEnv() }
                val csrf = client.settingsCsrf()
                val valid = client.postForm(
                    Routes.FRAGMENT_SETTINGS_ALLOCATIONS_PREVIEW,
                    allocationFields(csrf.value),
                    csrf.cookie,
                )
                val html = valid.bodyAsText()
                valid.status shouldBe HttpStatusCode.OK
                html shouldContain "44.43"
                html shouldContain "2.75"
                html shouldContain "of the whole book"
                html shouldContain "scored assets only"
                html shouldContain ViewText.ALLOCATION_PREVIEW_WARNING
                coVerify(exactly = 0) { configService.updateConfig(any()) }

                val invalidCases = listOf(
                    allocationFields(csrf.value, targets = listOf("50")) to ViewText.INVALID_ALLOCATION_FIELDS,
                    allocationFields(csrf.value, symbols = listOf("BTC", "XBT", "USD")) to
                        ViewText.INVALID_ALLOCATION_FIELDS,
                    allocationFields(csrf.value, symbols = listOf("BTC!", "ETH", "USD")) to
                        ViewText.INVALID_ALLOCATION_FIELDS,
                    allocationFields(csrf.value, targets = listOf("-1", "30", "71")) to
                        ViewText.INVALID_ALLOCATION_TARGET,
                    allocationFields(csrf.value, colors = listOf("oops", "#00ff00", "#0000ff")) to
                        ViewText.INVALID_ALLOCATION_COLOR,
                    allocationFields(csrf.value, scores = listOf("bad", "8.5", "")) to
                        ViewText.INVALID_ALLOCATION_SCORE,
                    allocationFields(csrf.value, scores = listOf("11", "8.5", "")) to
                        ViewText.INVALID_ALLOCATION_SCORE,
                    allocationFields(csrf.value, scores = listOf("", "0", "")) to
                        ViewText.ALLOCATION_SCORE_REQUIRED,
                    allocationFields(csrf.value, emphasis = "abc") to ViewText.INVALID_ALLOCATION_EMPHASIS,
                    allocationFields(csrf.value, emphasis = "99") to ViewText.INVALID_ALLOCATION_EMPHASIS,
                    allocationFields(csrf.value, sleeve = "0") to ViewText.INVALID_ALLOCATION_TARGET,
                    allocationFields(csrf.value, sleeve = "101") to ViewText.INVALID_ALLOCATION_TARGET,
                    allocationFields(csrf.value, targets = listOf("25", "25", "50"), sleeve = "60") to
                        ViewText.INVALID_ALLOCATION_TARGET,
                    allocationFields(
                        csrf.value,
                        symbols = listOf("BTC", "USD"),
                        targets = listOf("0", "100"),
                        colors = listOf("#ff0000", "#0000ff"),
                        scores = listOf("9", ""),
                        sleeve = "",
                    ) to ViewText.INVALID_ALLOCATION_TARGET,
                )
                invalidCases.forEach { (params, message) ->
                    val response = client.postForm(Routes.FRAGMENT_SETTINGS_ALLOCATIONS_PREVIEW, params, csrf.cookie)
                    response.status shouldBe HttpStatusCode.UnprocessableEntity
                    response.bodyAsText() shouldContain message
                    response.headers[HtmxHeaders.HX_RETARGET] shouldBe HtmxValues.BODY
                }

                val explicitSleeve = client.postForm(
                    Routes.FRAGMENT_SETTINGS_ALLOCATIONS_PREVIEW,
                    allocationFields(
                        csrf.value,
                        targets = listOf("25", "25", "50"),
                        sleeve = "50",
                    ),
                    csrf.cookie,
                )
                explicitSleeve.status shouldBe HttpStatusCode.OK
                explicitSleeve.bodyAsText() shouldContain "value=\"27.77\""
            }
        }

        "order intent route returns current intents and resolves with validated evidence" {
            val intent = OrderIntent(
                id = 7,
                cycleId = "cycle-7",
                clientOrderId = "client-7",
                pair = "XXBTZUSD",
                symbol = "BTC",
                side = "buy",
                volume = BigDecimal("0.01"),
                usdAmount = BigDecimal("100"),
                expectedPrice = BigDecimal("10000"),
                createdAt = Instant.parse("2026-10-10T12:00:00Z"),
                state = OrderIntentState.UNCERTAIN,
            )
            coEvery { orderIntentService.getUnresolvedIntents() } returns listOf(intent)
            testApplication {
                application { configureTestEnv() }
                client.get(Routes.API_ORDER_INTENTS).bodyAsText() shouldContain "cycle-7"
                val csrf = client.settingsCsrf()
                val resolved = client.postForm(
                    Routes.API_ORDER_INTENTS_RESOLVE_TEMPLATE.replace("{id}", "7"),
                    requestFields(
                        csrf.value,
                        mapOf(
                            FormFields.ORDER_INTENT_STATE to listOf("confirmed"),
                            FormFields.ORDER_INTENT_EVIDENCE to listOf("matched exchange record"),
                            FormFields.ORDER_INTENT_ORDER_TXID to listOf("  OABC-123  "),
                        ),
                    ),
                    csrf.cookie,
                )
                resolved.status shouldBe HttpStatusCode.OK
                resolved.bodyAsText() shouldContain "\"resolved\":true"
                resolved.bodyAsText() shouldContain "\"state\":\"CONFIRMED\""
                coVerify {
                    orderIntentService.resolve(
                        id = 7,
                        state = OrderIntentState.CONFIRMED,
                        evidence = "matched exchange record",
                        orderTxid = "OABC-123",
                    )
                }

                val resolvedWithoutTxid = client.postForm(
                    Routes.API_ORDER_INTENTS_RESOLVE_TEMPLATE.replace("{id}", "8"),
                    requestFields(
                        csrf.value,
                        mapOf(
                            FormFields.ORDER_INTENT_STATE to listOf("REJECTED"),
                            FormFields.ORDER_INTENT_EVIDENCE to listOf("operator confirmed no order"),
                        ),
                    ),
                    csrf.cookie,
                    htmx = true,
                )
                resolvedWithoutTxid.status shouldBe HttpStatusCode.OK
                resolvedWithoutTxid.headers[HtmxHeaders.HX_REFRESH] shouldBe "true"
                coVerify {
                    orderIntentService.resolve(
                        id = 8,
                        state = OrderIntentState.REJECTED,
                        evidence = "operator confirmed no order",
                    )
                }
            }
        }

        "order intent resolution rejects invalid ids and states and maps service conflicts" {
            testApplication {
                application { configureTestEnv() }
                val csrf = client.settingsCsrf()
                suspend fun resolve(id: String, state: String, htmx: Boolean = false) = client.postForm(
                    Routes.API_ORDER_INTENTS_RESOLVE_TEMPLATE.replace("{id}", id),
                    requestFields(
                        csrf.value,
                        mapOf(FormFields.ORDER_INTENT_STATE to listOf(state)),
                    ),
                    csrf.cookie,
                    htmx,
                )

                val invalidId = resolve("nope", "CONFIRMED")
                invalidId.status shouldBe HttpStatusCode.BadRequest
                invalidId.bodyAsText() shouldContain "positive integer"
                val zeroId = resolve("0", "CONFIRMED")
                zeroId.status shouldBe HttpStatusCode.BadRequest
                listOf("PENDING", "UNCERTAIN", "UNKNOWN").forEach { state ->
                    val invalidState = resolve("7", state)
                    invalidState.status shouldBe HttpStatusCode.UnprocessableEntity
                    invalidState.bodyAsText() shouldContain "CONFIRMED or REJECTED"
                }

                coEvery { orderIntentService.resolve(8, OrderIntentState.REJECTED, any(), null) } throws
                    IllegalArgumentException("rejection evidence required")
                val badEvidence = resolve("8", "REJECTED")
                badEvidence.status shouldBe HttpStatusCode.UnprocessableEntity
                badEvidence.bodyAsText() shouldContain "rejection evidence required"

                coEvery { orderIntentService.resolve(9, OrderIntentState.CONFIRMED, any(), null) } throws
                    IllegalStateException("intent was already resolved")
                val conflict = resolve("9", "CONFIRMED", htmx = true)
                conflict.status shouldBe HttpStatusCode.Conflict
                conflict.headers["X-Rebalancer-Error-Fragment"] shouldBe "order-intent"
                conflict.headers[HtmxHeaders.HX_RETARGET] shouldBe "#order-intent-feedback"
                conflict.bodyAsText() shouldContain "intent was already resolved"

                val invalidCsrf = client.postForm(
                    Routes.API_ORDER_INTENTS_RESOLVE_TEMPLATE.replace("{id}", "9"),
                    requestFields("stale-token", mapOf(FormFields.ORDER_INTENT_STATE to listOf("CONFIRMED"))),
                    csrf.cookie,
                    htmx = true,
                )
                invalidCsrf.status shouldBe HttpStatusCode.Forbidden
                invalidCsrf.headers["X-Rebalancer-Error-Fragment"] shouldBe "order-intent"
                invalidCsrf.bodyAsText() shouldContain ViewText.CSRF_SESSION_EXPIRED

                coVerify(exactly = 0) {
                    orderIntentService.resolve(0, any(), any(), any())
                }
            }
        }

        "settings and preview reject stale CSRF tokens while returning safe recovery content" {
            every { configService.getConfig() } returns TestFixtures.DEFAULT_TEST_CONFIG

            testApplication {
                application { configureTestEnv() }
                val csrf = client.settingsCsrf()
                val native = client.postForm(
                    Routes.SETTINGS,
                    requestFields("stale-token", mapOf(FormFields.LOOP_DELAY_SECONDS to listOf("88"))),
                    csrf.cookie,
                )
                native.status shouldBe HttpStatusCode.Forbidden
                native.bodyAsText() shouldContain ViewText.CSRF_SESSION_EXPIRED
                native.bodyAsText() shouldContain "value=\"88\""
                native.headers["X-Rebalancer-Error-Fragment"] shouldBe "settings-form"

                val htmx = client.postForm(
                    Routes.SETTINGS,
                    requestFields("stale-token"),
                    csrf.cookie,
                    htmx = true,
                )
                htmx.status shouldBe HttpStatusCode.Forbidden
                htmx.bodyAsText() shouldBe ""
                htmx.headers["X-Rebalancer-CSRF-Session-Expired"] shouldBe "true"
                htmx.headers["X-Rebalancer-CSRF-Token"] shouldBe csrf.value

                val preview = client.postForm(
                    Routes.FRAGMENT_SETTINGS_ALLOCATIONS_PREVIEW,
                    allocationFields("stale-token"),
                    csrf.cookie,
                )
                preview.status shouldBe HttpStatusCode.Forbidden
                preview.bodyAsText() shouldContain ViewText.CSRF_SESSION_EXPIRED
                coVerify(exactly = 0) { configService.updateConfig(any()) }
            }
        }

        "dashboard fragment renders waiting and current rows with unresolved-intent controls" {
            every { configService.getConfig() } returns TestFixtures.DEFAULT_TEST_CONFIG
            val unresolved = listOf(
                OrderIntent(
                    id = null,
                    cycleId = "legacy",
                    clientOrderId = null,
                    pair = "XXBTZUSD",
                    symbol = "BTC",
                    side = "buy",
                    volume = BigDecimal("0.01"),
                    usdAmount = BigDecimal("100"),
                    expectedPrice = null,
                    createdAt = Instant.now(),
                    state = OrderIntentState.UNCERTAIN,
                ),
                OrderIntent(
                    id = 10,
                    cycleId = "cycle-10",
                    clientOrderId = "client-10",
                    pair = "XXBTZUSD",
                    symbol = "BTC",
                    side = "buy",
                    volume = BigDecimal("0.02"),
                    usdAmount = BigDecimal("200"),
                    expectedPrice = BigDecimal("10000"),
                    createdAt = Instant.now(),
                    state = OrderIntentState.UNCERTAIN,
                    errorMessage = "exchange response was ambiguous",
                ),
            )
            coEvery { orderIntentService.getUnresolvedIntents() } returns unresolved

            testApplication {
                application { configureTestEnv() }
                val waiting = client.get(Routes.FRAGMENT_DASHBOARD).bodyAsText()
                waiting shouldContain "Waiting for first rebalance cycle"

                val snapshot = TestFixtures.emptySnapshot(Instant.now().minusSeconds(10_000), BigDecimal("1000"))
                    .copy(
                        assets = mapOf(
                            "BTC" to PortfolioSnapshot.AssetSnapshot(
                                Asset.BTC,
                                BigDecimal("0.1"),
                                BigDecimal("10000"),
                                BigDecimal("1000"),
                                BigDecimal("100"),
                                BigDecimal("100"),
                                BigDecimal("0"),
                                BigDecimal.ZERO,
                            ),
                        ),
                        actions = listOf("BUY BTC"),
                    )
                currentPortfolioState.publish(snapshot)
                val current = client.get(Routes.FRAGMENT_DASHBOARD).bodyAsText()
                current shouldContain "Intent #10"
                current shouldContain "exchange response was ambiguous"
                current shouldContain "value=\"${csrfTokenFrom(current)}\"" // token is rendered into the resolver form
                current shouldContain "BTC"
                current shouldContain "sortTable(this"
            }
        }

        "pause and resume require a valid same-session CSRF token and refresh the UI" {
            testApplication {
                application { configureTestEnv() }
                val csrf = client.settingsCsrf()
                val invalid = client.post("/api/pause") {
                    setBody(parametersOf().formUrlEncode())
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                }
                invalid.status shouldBe HttpStatusCode.Forbidden

                val pause = client.postForm("/api/pause", requestFields(csrf.value), csrf.cookie)
                pause.status shouldBe HttpStatusCode.OK
                pause.bodyAsText() shouldContain "\"paused\":true"
                pause.headers[HtmxHeaders.HX_REFRESH] shouldBe "true"

                val resume = client.postForm("/api/resume", requestFields(csrf.value), csrf.cookie)
                resume.status shouldBe HttpStatusCode.OK
                resume.bodyAsText() shouldContain "\"paused\":false"
                resume.headers[HtmxHeaders.HX_REFRESH] shouldBe "true"
                coVerify(exactly = 1) { portfolioManager.pauseLoop() }
                coVerify(exactly = 1) { portfolioManager.resumeLoop() }
            }
        }

        "health remains available and readiness reports unavailable diagnostics safely" {
            every { configService.getConfig() } throws IllegalStateException("configuration unavailable")
            every { portfolioManager.isLoopRunning() } returns true

            testApplication {
                application { configureTestEnv() }
                val health = client.get(Routes.API_HEALTH)
                health.status shouldBe HttpStatusCode.OK
                health.bodyAsText() shouldContain "\"activeMode\":\"UNKNOWN\""
                health.bodyAsText() shouldContain "\"readiness\":\"NOT_READY\""
                health.bodyAsText() shouldContain "CONFIG_UNAVAILABLE"

                every { configService.getConfig() } returns TestFixtures.DEFAULT_TEST_CONFIG
                every { portfolioManager.getOperationalStatus() } throws
                    IllegalStateException("cycle diagnostics unavailable")
                val readiness = client.get(Routes.API_READINESS)
                readiness.status shouldBe HttpStatusCode.ServiceUnavailable
                readiness.bodyAsText() shouldContain "DIAGNOSTICS_UNAVAILABLE"
            }
        }

        "readiness distinguishes loop, intent, snapshot, cycle, and active-mode states" {
            every { configService.getConfig() } returns TestFixtures.DEFAULT_TEST_CONFIG
            every { portfolioManager.isLoopPaused() } returns false
            every { portfolioManager.isLoopRunning() } returns false
            every { portfolioManager.getOperationalStatus() } returns RebalanceOperationalStatus()
            coEvery { orderIntentService.countUnresolvedIntents() } returns 0

            testApplication {
                application { configureTestEnv() }

                val stopped = client.get(Routes.API_READINESS)
                stopped.status shouldBe HttpStatusCode.ServiceUnavailable
                stopped.bodyAsText() shouldContain "LOOP_NOT_RUNNING"

                every { portfolioManager.isLoopRunning() } returns true
                val noSnapshot = client.get(Routes.API_READINESS)
                noSnapshot.bodyAsText() shouldContain "NO_SNAPSHOT"

                currentPortfolioState.publish(
                    TestFixtures.emptySnapshot(Instant.parse("2026-10-10T12:00:00Z"), BigDecimal("100")),
                )
                coEvery { orderIntentService.countUnresolvedIntents() } returns 1
                val unresolved = client.get(Routes.API_READINESS)
                unresolved.status shouldBe HttpStatusCode.ServiceUnavailable
                unresolved.bodyAsText() shouldContain "UNRESOLVED_ORDER_INTENT"

                coEvery { orderIntentService.countUnresolvedIntents() } returns 0
                every { portfolioManager.getOperationalStatus() } returns
                    RebalanceOperationalStatus(lastCycleError = "exchange request failed")
                val failedCycle = client.get(Routes.API_READINESS)
                failedCycle.bodyAsText() shouldContain "LAST_CYCLE_FAILED"

                every { portfolioManager.getOperationalStatus() } returns RebalanceOperationalStatus()
                every { portfolioManager.isLoopPaused() } returns true
                val paused = client.get(Routes.API_READINESS)
                paused.bodyAsText() shouldContain "PAUSED"

                every { portfolioManager.isLoopPaused() } returns false
                val simulationConfig = TestFixtures.DEFAULT_TEST_CONFIG.copy(
                    settings = TestFixtures.DEFAULT_TEST_CONFIG.settings.copy(simulation = true, dryRun = false),
                )
                every { configService.getConfig() } returns simulationConfig
                val simulation = client.get(Routes.API_HEALTH)
                simulation.bodyAsText() shouldContain "\"activeMode\":\"SIMULATION\""

                every { configService.getConfig() } returns simulationConfig.copy(
                    settings = simulationConfig.settings.copy(simulation = false, dryRun = true),
                )
                val dryRun = client.get(Routes.API_HEALTH)
                dryRun.bodyAsText() shouldContain "\"activeMode\":\"DRY_RUN\""

                every { configService.getConfig() } returns simulationConfig.copy(
                    settings = simulationConfig.settings.copy(simulation = false, dryRun = false),
                )
                val live = client.get(Routes.API_HEALTH)
                live.bodyAsText() shouldContain "\"activeMode\":\"LIVE\""
            }
        }
    }

    private fun csrfTokenFrom(html: String): String =
        Regex("""name="csrfToken" value="([^"]+)"""").find(html)?.groupValues?.get(1)
            ?: error("Rendered dashboard fragment did not include a CSRF token")
}
