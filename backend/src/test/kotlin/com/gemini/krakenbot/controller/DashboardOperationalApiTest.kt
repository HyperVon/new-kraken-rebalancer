package com.gemini.krakenbot.controller

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.InvalidConfigurationException
import com.gemini.krakenbot.domain.QualityAllocation
import com.gemini.krakenbot.model.ComparisonProposalStatus
import com.gemini.krakenbot.model.HistoryStats
import com.gemini.krakenbot.model.OrderIntent
import com.gemini.krakenbot.model.OrderIntentState
import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.service.AthTrustFailureReason
import com.gemini.krakenbot.service.ComparisonStartProposal
import com.gemini.krakenbot.service.RebalanceOperationalStatus
import com.gemini.krakenbot.view.util.FormFields
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
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import java.math.BigDecimal
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private fun previousProcessCsrfToken(): String {
    val nonce = ByteArray(32) { (it + 1).toByte() }
    val previousProcessSecret = ByteArray(32) { (it + 33).toByte() }
    val signature = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(previousProcessSecret, "HmacSHA256"))
        doFinal(nonce)
    }
    val encoder = Base64.getUrlEncoder().withoutPadding()
    return "${encoder.encodeToString(nonce)}.${encoder.encodeToString(signature)}"
}

@Suppress("unused")
class DashboardOperationalApiTest : DashboardControllerTestBase() {
    init {
        "readiness reports ready only after a running loop has a snapshot and no unresolved state" {
            val snapshot = TestFixtures.emptySnapshot(Instant.parse("2026-08-09T12:00:00Z"), BigDecimal("1000.00"))
            coEvery { tradeHistoryService.getHistoryStats() } returns HistoryStats(
                allTimeHigh = BigDecimal.ZERO,
                totalTradesExecuted = 0L,
                totalVolumeTraded = BigDecimal.ZERO,
                totalFeesPaid = BigDecimal.ZERO,
                latestSnapshotTime = snapshot.timestamp,
            )
            coEvery { tradeHistoryService.getLatestSnapshot() } returns snapshot
            coEvery { tradeHistoryService.hasPendingSubmissions() } returns false
            coEvery { tradeHistoryService.getSyncMetadata(SyncMetadataKeys.SYNC_WATERMARK_EPOCH_SEC) } returns
                "1786276800"
            coEvery { tradeHistoryService.getSyncMetadata(SyncMetadataKeys.SYNC_OFFSET) } returns "5"
            coEvery { tradeHistoryService.getSyncMetadata(SyncMetadataKeys.SYNC_TOTAL) } returns "10"
            coEvery { tradeHistoryService.isHistorySeeded() } returns true
            coEvery { orderIntentService.countUnresolvedIntents() } returns 0L
            every { portfolioManager.isLoopPaused() } returns false
            every { portfolioManager.isLoopRunning() } returns true
            every { portfolioManager.getOperationalStatus() } returns RebalanceOperationalStatus(
                lastCycleStartedAt = snapshot.timestamp.minusSeconds(30),
                lastCycleCompletedAt = snapshot.timestamp,
                lastCycleSyncWarning = "Trade synchronization during cycle failed (RuntimeException)",
                lastAthDeferredReason = AthTrustFailureReason.AMBIGUOUS_FUNDING,
            )
            every { configService.getConfig() } returns TestFixtures.config(
                settings = TestFixtures.settings(simulation = true),
            )

            testApplication {
                application { configureTestEnv() }

                val response = client.get("/api/readiness")
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain "\"readiness\":\"READY\""
                response.bodyAsText() shouldContain "\"activeMode\":\"SIMULATION\""
                response.bodyAsText() shouldContain "lastCycleSyncWarning"
                response.bodyAsText() shouldContain "\"lastAthDeferredReason\":\"AMBIGUOUS_FUNDING\""

                val syncResponse = client.get("/api/history/sync-progress")
                syncResponse.status shouldBe HttpStatusCode.OK
                syncResponse.bodyAsText() shouldContain "\"seeded\":true"
                syncResponse.bodyAsText() shouldContain "\"offset\":\"5\""
            }
        }

        "unresolved order intents are listed and resolution requires CSRF" {
            val intent = OrderIntent(
                id = 7,
                cycleId = "cycle-id",
                clientOrderId = "client-id",
                pair = "XBTUSD",
                symbol = "BTC",
                side = "BUY",
                volume = BigDecimal("0.01"),
                usdAmount = BigDecimal("500.00"),
                expectedPrice = BigDecimal("50000.00"),
                createdAt = Instant.parse("2026-08-09T12:00:00Z"),
                state = OrderIntentState.UNCERTAIN,
            )
            coEvery { orderIntentService.getUnresolvedIntents() } returns listOf(intent)

            testApplication {
                application { configureTestEnv() }

                val listResponse = client.get("/api/order-intents")
                listResponse.status shouldBe HttpStatusCode.OK
                listResponse.bodyAsText() shouldContain "\"id\":7"
                listResponse.bodyAsText() shouldContain "\"state\":\"UNCERTAIN\""

                val forbiddenResponse = client.post("/api/order-intents/7/resolve") {
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                    setBody(parametersOf(FormFields.ORDER_INTENT_STATE, "CONFIRMED").formUrlEncode())
                }
                forbiddenResponse.status shouldBe HttpStatusCode.Forbidden
                forbiddenResponse.headers[HttpHeaders.ContentType] shouldContain "application/json"
                forbiddenResponse.bodyAsText() shouldContain "\"error\":"
                forbiddenResponse.bodyAsText() shouldNotContain "error-banner"
            }
        }

        "valid CSRF resolves an order intent with exchange evidence" {
            every { configService.getConfig() } returns TestFixtures.config()
            coEvery {
                orderIntentService.resolve(
                    7,
                    OrderIntentState.CONFIRMED,
                    "Kraken txid O-123",
                    "O-123",
                )
            } returns Unit

            testApplication {
                application { configureTestEnv() }
                val csrf = client.settingsCsrf()
                val response = client.post("/api/order-intents/7/resolve") {
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                    header(HttpHeaders.Cookie, csrf.cookie)
                    setBody(
                        parametersOf(
                            FormFields.CSRF_TOKEN to listOf(csrf.value),
                            FormFields.ORDER_INTENT_STATE to listOf("CONFIRMED"),
                            FormFields.ORDER_INTENT_EVIDENCE to listOf("Kraken txid O-123"),
                            FormFields.ORDER_INTENT_ORDER_TXID to listOf("O-123"),
                        ).formUrlEncode(),
                    )
                }

                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain "\"resolved\":true"
                response.bodyAsText() shouldContain "\"state\":\"CONFIRMED\""
            }

            coVerify {
                orderIntentService.resolve(
                    7,
                    OrderIntentState.CONFIRMED,
                    "Kraken txid O-123",
                    "O-123",
                )
            }
        }

        "HTMX intent resolution refreshes on success and returns a safe targeted error fragment" {
            every { configService.getConfig() } returns TestFixtures.config()
            coEvery {
                orderIntentService.resolve(7, OrderIntentState.CONFIRMED, "Verified Kraken fill")
            } returns Unit
            coEvery {
                orderIntentService.resolve(7, OrderIntentState.REJECTED, "No matching fill")
            } throws IllegalArgumentException("Evidence <must> be checked before resolving.")
            coEvery {
                orderIntentService.resolve(7, OrderIntentState.CONFIRMED, "Already resolved")
            } throws IllegalStateException("Order intent 7 is missing or already resolved.")

            testApplication {
                application { configureTestEnv() }
                val csrf = client.settingsCsrf()
                val success = client.post("/api/order-intents/7/resolve") {
                    header("HX-Request", "true")
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                    header(HttpHeaders.Cookie, csrf.cookie)
                    setBody(
                        parametersOf(
                            FormFields.CSRF_TOKEN to listOf(csrf.value),
                            FormFields.ORDER_INTENT_STATE to listOf("CONFIRMED"),
                            FormFields.ORDER_INTENT_EVIDENCE to listOf("Verified Kraken fill"),
                        ).formUrlEncode(),
                    )
                }
                success.status shouldBe HttpStatusCode.OK
                success.headers["HX-Refresh"] shouldBe "true"
                success.bodyAsText() shouldBe ""

                val failure = client.post("/api/order-intents/7/resolve") {
                    header("HX-Request", "true")
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                    header(HttpHeaders.Cookie, csrf.cookie)
                    setBody(
                        parametersOf(
                            FormFields.CSRF_TOKEN to listOf(csrf.value),
                            FormFields.ORDER_INTENT_STATE to listOf("REJECTED"),
                            FormFields.ORDER_INTENT_EVIDENCE to listOf("No matching fill"),
                        ).formUrlEncode(),
                    )
                }
                failure.status shouldBe HttpStatusCode.UnprocessableEntity
                failure.headers["X-Rebalancer-Error-Fragment"] shouldBe "order-intent"
                failure.headers["HX-Retarget"] shouldBe "#order-intent-feedback"
                failure.headers["HX-Reswap"] shouldBe "outerHTML"
                failure.headers[HttpHeaders.ContentType] shouldContain TestFixtures.TEXT_HTML
                val errorHtml = failure.bodyAsText()
                errorHtml shouldContain "id=\"order-intent-feedback\""
                errorHtml shouldContain "class=\"error-banner\""
                errorHtml shouldContain "Evidence &lt;must&gt; be checked before resolving."
                errorHtml shouldNotContain "<script>"

                val forbidden = client.post("/api/order-intents/7/resolve") {
                    header("HX-Request", "true")
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                    header(HttpHeaders.Cookie, csrf.cookie)
                    header(HttpHeaders.Origin, "http://localhost")
                    setBody(
                        parametersOf(
                            FormFields.CSRF_TOKEN to listOf("forged-token"),
                            FormFields.ORDER_INTENT_STATE to listOf("CONFIRMED"),
                        ).formUrlEncode(),
                    )
                }
                forbidden.status shouldBe HttpStatusCode.Forbidden
                forbidden.headers["X-Rebalancer-Error-Fragment"] shouldBe "order-intent"
                forbidden.headers["HX-Retarget"] shouldBe "#order-intent-feedback"
                forbidden.headers[HttpHeaders.ContentType] shouldContain TestFixtures.TEXT_HTML
                forbidden.headers["X-Rebalancer-CSRF-Token"] shouldBe csrf.value
                forbidden.headers["X-Rebalancer-CSRF-Session-Expired"] shouldBe null
                forbidden.bodyAsText() shouldContain "id=\"order-intent-feedback\""

                val noOrigin = client.post("/api/order-intents/7/resolve") {
                    header("HX-Request", "true")
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                    setBody(
                        parametersOf(
                            FormFields.CSRF_TOKEN to listOf("forged-token"),
                            FormFields.ORDER_INTENT_STATE to listOf("CONFIRMED"),
                        ).formUrlEncode(),
                    )
                }
                noOrigin.status shouldBe HttpStatusCode.Forbidden
                noOrigin.headers["X-Rebalancer-Error-Fragment"] shouldBe null
                noOrigin.headers["X-Rebalancer-CSRF-Token"] shouldBe null
                noOrigin.bodyAsText() shouldBe ""

                val conflict = client.post("/api/order-intents/7/resolve") {
                    header("HX-Request", "true")
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                    header(HttpHeaders.Cookie, csrf.cookie)
                    setBody(
                        parametersOf(
                            FormFields.CSRF_TOKEN to listOf(csrf.value),
                            FormFields.ORDER_INTENT_STATE to listOf("CONFIRMED"),
                            FormFields.ORDER_INTENT_EVIDENCE to listOf("Already resolved"),
                        ).formUrlEncode(),
                    )
                }
                conflict.status shouldBe HttpStatusCode.Conflict
                conflict.headers["X-Rebalancer-Error-Fragment"] shouldBe "order-intent"
                conflict.headers["HX-Retarget"] shouldBe "#order-intent-feedback"
                conflict.headers[HttpHeaders.ContentType] shouldContain TestFixtures.TEXT_HTML
                conflict.bodyAsText() shouldContain "id=\"order-intent-feedback\""
            }
        }

        "HTMX intent resolution refreshes a prior-process token without resolving the intent" {
            every { configService.getConfig() } returns TestFixtures.config()
            val staleToken = previousProcessCsrfToken()

            testApplication {
                application { configureTestEnv() }
                val response = client.post("/api/order-intents/7/resolve") {
                    header("HX-Request", "true")
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                    header(HttpHeaders.Cookie, "rebalancer-csrf=$staleToken")
                    header(HttpHeaders.Origin, "http://localhost")
                    setBody(
                        parametersOf(
                            FormFields.CSRF_TOKEN to listOf(staleToken),
                            FormFields.ORDER_INTENT_STATE to listOf("CONFIRMED"),
                            FormFields.ORDER_INTENT_EVIDENCE to listOf("Verified Kraken fill"),
                        ).formUrlEncode(),
                    )
                }

                response.status shouldBe HttpStatusCode.Forbidden
                response.headers["X-Rebalancer-Error-Fragment"] shouldBe "order-intent"
                response.headers["HX-Retarget"] shouldBe "#order-intent-feedback"
                response.headers["X-Rebalancer-CSRF-Session-Expired"] shouldBe "true"
                val freshToken = response.headers["X-Rebalancer-CSRF-Token"]
                    ?: error("Expired-token response did not issue a fresh CSRF token")
                (freshToken != staleToken) shouldBe true
                freshToken.matches(Regex("^[A-Za-z0-9_-]{43}\\.[A-Za-z0-9_-]{43}$")) shouldBe true
                response.headers[HttpHeaders.SetCookie]?.substringBefore(';') shouldBe
                    "rebalancer-csrf=$freshToken"
                response.bodyAsText() shouldContain "id=\"order-intent-feedback\""
                response.bodyAsText() shouldContain "security token is invalid or expired"
            }

            coVerify(exactly = 0) {
                orderIntentService.resolve(any(), any(), any(), any())
            }
        }

        "whitespace order txid resolves as absent exchange evidence" {
            every { configService.getConfig() } returns TestFixtures.config()
            coEvery {
                orderIntentService.resolve(7, OrderIntentState.CONFIRMED, "Kraken response checked")
            } returns Unit

            testApplication {
                application { configureTestEnv() }
                val csrf = client.settingsCsrf()
                val response = client.post("/api/order-intents/7/resolve") {
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                    header(HttpHeaders.Cookie, csrf.cookie)
                    setBody(
                        parametersOf(
                            FormFields.CSRF_TOKEN to listOf(csrf.value),
                            FormFields.ORDER_INTENT_STATE to listOf("CONFIRMED"),
                            FormFields.ORDER_INTENT_EVIDENCE to listOf("Kraken response checked"),
                            FormFields.ORDER_INTENT_ORDER_TXID to listOf(" \t "),
                        ).formUrlEncode(),
                    )
                }

                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain "\"resolved\":true"
                response.bodyAsText() shouldContain "\"state\":\"CONFIRMED\""
            }

            coVerify {
                orderIntentService.resolve(7, OrderIntentState.CONFIRMED, "Kraken response checked")
            }
        }

        "order-intent resolution validates the path, terminal state, evidence, and conflicts" {
            every { configService.getConfig() } returns TestFixtures.config()
            coEvery {
                orderIntentService.resolve(7, OrderIntentState.CONFIRMED, "")
            } throws IllegalArgumentException("Resolution evidence is required.")
            coEvery {
                orderIntentService.resolve(7, OrderIntentState.PENDING, "evidence")
            } throws IllegalArgumentException("Only terminal outcomes can resolve an order intent.")
            coEvery {
                orderIntentService.resolve(7, OrderIntentState.REJECTED, "already checked")
            } throws IllegalStateException("Order intent 7 is missing or already resolved.")
            coEvery {
                orderIntentService.resolve(7, OrderIntentState.CONFIRMED, "service omitted validation detail")
            } throws IllegalArgumentException()
            coEvery {
                orderIntentService.resolve(7, OrderIntentState.REJECTED, "service omitted conflict detail")
            } throws IllegalStateException()

            testApplication {
                application { configureTestEnv() }
                val csrf = client.settingsCsrf()

                suspend fun resolve(id: String, state: String?, evidence: String?) =
                    client.post("/api/order-intents/$id/resolve") {
                        header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                        header(HttpHeaders.Cookie, csrf.cookie)
                        val fields = mutableListOf(
                            FormFields.CSRF_TOKEN to listOf(csrf.value),
                        )
                        state?.let { fields += (FormFields.ORDER_INTENT_STATE to listOf(it)) }
                        evidence?.let { fields += (FormFields.ORDER_INTENT_EVIDENCE to listOf(it)) }
                        setBody(
                            parametersOf(*fields.toTypedArray()).formUrlEncode(),
                        )
                    }

                resolve("not-an-id", "CONFIRMED", "evidence").status shouldBe HttpStatusCode.BadRequest
                resolve("0", "CONFIRMED", "evidence").status shouldBe HttpStatusCode.BadRequest
                resolve("-5", "CONFIRMED", "evidence").status shouldBe HttpStatusCode.BadRequest
                val invalidState = resolve("7", "NOT_A_STATE", "evidence")
                invalidState.status shouldBe HttpStatusCode.UnprocessableEntity
                invalidState.headers[HttpHeaders.ContentType] shouldContain "application/json"
                invalidState.bodyAsText() shouldContain "\"error\":"
                invalidState.bodyAsText() shouldNotContain "error-banner"
                resolve("7", "PENDING", "evidence").status shouldBe HttpStatusCode.UnprocessableEntity
                resolve("7", "CONFIRMED", "").status shouldBe HttpStatusCode.UnprocessableEntity
                resolve("7", state = null, evidence = "evidence").status shouldBe
                    HttpStatusCode.UnprocessableEntity
                resolve("7", state = "CONFIRMED", evidence = null).status shouldBe
                    HttpStatusCode.UnprocessableEntity
                val conflict = resolve("7", "REJECTED", "already checked")
                conflict.status shouldBe HttpStatusCode.Conflict
                conflict.headers[HttpHeaders.ContentType] shouldContain "application/json"
                conflict.bodyAsText() shouldContain "\"error\":"
                conflict.bodyAsText() shouldNotContain "error-banner"

                val missingValidationMessage = resolve("7", "CONFIRMED", "service omitted validation detail")
                missingValidationMessage.status shouldBe HttpStatusCode.UnprocessableEntity
                missingValidationMessage.bodyAsText() shouldContain "Invalid order intent resolution."

                val missingConflictMessage = resolve("7", "REJECTED", "service omitted conflict detail")
                missingConflictMessage.status shouldBe HttpStatusCode.Conflict
                missingConflictMessage.bodyAsText() shouldContain "Order intent could not be resolved."
            }
        }

        "readiness prioritizes paused, unresolved, missing-snapshot, and failed-cycle states" {
            val snapshot = TestFixtures.emptySnapshot(Instant.parse("2026-08-09T12:00:00Z"), BigDecimal("1000.00"))
            val stats = HistoryStats(
                allTimeHigh = BigDecimal.ZERO,
                totalTradesExecuted = 0L,
                totalVolumeTraded = BigDecimal.ZERO,
                totalFeesPaid = BigDecimal.ZERO,
                latestSnapshotTime = snapshot.timestamp,
            )
            val liveConfig = TestFixtures.config(settings = TestFixtures.settings(dryRun = false))
            val dryRunConfig = TestFixtures.config(settings = TestFixtures.settings(dryRun = true))
            coEvery { tradeHistoryService.getHistoryStats() } returns stats
            coEvery { tradeHistoryService.getLatestSnapshot() } returnsMany listOf(
                snapshot,
                snapshot,
                null,
                snapshot,
                snapshot,
            )
            coEvery { tradeHistoryService.hasPendingSubmissions() } returns false
            coEvery { tradeHistoryService.getSyncMetadata(SyncMetadataKeys.SYNC_WATERMARK_EPOCH_SEC) } returns "invalid"
            coEvery { orderIntentService.countUnresolvedIntents() } returnsMany listOf(0L, 1L, 0L, 0L, 0L)
            every { portfolioManager.isLoopPaused() } returnsMany listOf(true, false, false, false, false)
            every { portfolioManager.isLoopRunning() } returns true
            every { portfolioManager.getOperationalStatus() } returnsMany listOf(
                RebalanceOperationalStatus(),
                RebalanceOperationalStatus(),
                RebalanceOperationalStatus(),
                RebalanceOperationalStatus(lastCycleError = "cycle failed"),
                RebalanceOperationalStatus(),
            )
            every { configService.getConfig() } returnsMany listOf(
                liveConfig,
                liveConfig,
                liveConfig,
                liveConfig,
                dryRunConfig,
            )

            testApplication {
                application { configureTestEnv() }

                client.get("/api/readiness").apply {
                    status shouldBe HttpStatusCode.ServiceUnavailable
                    bodyAsText() shouldContain "\"readinessReason\":\"PAUSED\""
                    bodyAsText() shouldContain "\"activeMode\":\"LIVE\""
                }
                client.get("/api/readiness").bodyAsText() shouldContain
                    "\"readinessReason\":\"UNRESOLVED_ORDER_INTENT\""
                client.get("/api/readiness").bodyAsText() shouldContain "\"readinessReason\":\"NO_SNAPSHOT\""
                client.get("/api/readiness").bodyAsText() shouldContain "\"readinessReason\":\"LAST_CYCLE_FAILED\""
                client.get("/api/health").bodyAsText() shouldContain "\"activeMode\":\"DRY_RUN\""
            }
        }

        "readiness reports stopped and unknown when runtime state or config is unavailable" {
            coEvery { tradeHistoryService.getHistoryStats() } returns HistoryStats(
                allTimeHigh = BigDecimal.ZERO,
                totalTradesExecuted = 0L,
                totalVolumeTraded = BigDecimal.ZERO,
                totalFeesPaid = BigDecimal.ZERO,
                latestSnapshotTime = null,
            )
            coEvery { tradeHistoryService.getLatestSnapshot() } returns null
            coEvery { tradeHistoryService.hasPendingSubmissions() } returns false
            coEvery { tradeHistoryService.getSyncMetadata(SyncMetadataKeys.SYNC_WATERMARK_EPOCH_SEC) } returns "invalid"
            coEvery { orderIntentService.countUnresolvedIntents() } returns 0L
            every { portfolioManager.isLoopPaused() } returns false
            every { portfolioManager.isLoopRunning() } returns false
            every { portfolioManager.getOperationalStatus() } returns RebalanceOperationalStatus()
            every { configService.getConfig() } throws IllegalStateException("config unavailable")

            testApplication {
                application { configureTestEnv() }

                val response = client.get("/api/readiness")
                response.status shouldBe HttpStatusCode.ServiceUnavailable
                response.bodyAsText() shouldContain "\"readinessReason\":\"LOOP_NOT_RUNNING\""
                response.bodyAsText() shouldContain "\"activeMode\":\"UNKNOWN\""
                response.bodyAsText() shouldContain "\"lastTradeSyncTime\":\"N/A\""
            }
        }

        "readiness stays blocked when the legacy submission guard is unresolved" {
            val snapshot = TestFixtures.emptySnapshot(Instant.parse("2026-08-09T12:00:00Z"), BigDecimal("1000.00"))
            coEvery { tradeHistoryService.getHistoryStats() } returns HistoryStats(
                allTimeHigh = BigDecimal.ZERO,
                totalTradesExecuted = 0L,
                totalVolumeTraded = BigDecimal.ZERO,
                totalFeesPaid = BigDecimal.ZERO,
                latestSnapshotTime = snapshot.timestamp,
            )
            coEvery { tradeHistoryService.getLatestSnapshot() } returns snapshot
            coEvery { tradeHistoryService.getSyncMetadata(SyncMetadataKeys.SYNC_WATERMARK_EPOCH_SEC) } returns null
            coEvery { tradeHistoryService.hasPendingSubmissions() } returns true
            coEvery { orderIntentService.countUnresolvedIntents() } returns 0L
            every { portfolioManager.isLoopPaused() } returns false
            every { portfolioManager.isLoopRunning() } returns true
            every { portfolioManager.getOperationalStatus() } returns RebalanceOperationalStatus(
                lastCycleCompletedAt = snapshot.timestamp,
            )
            every { configService.getConfig() } returns TestFixtures.config()

            testApplication {
                application { configureTestEnv() }

                val response = client.get("/api/readiness")
                response.status shouldBe HttpStatusCode.ServiceUnavailable
                response.bodyAsText() shouldContain "\"readinessReason\":\"UNRESOLVED_ORDER_INTENT\""
            }
        }

        "readiness fails closed for unavailable config and invalid sync watermark" {
            val snapshot = TestFixtures.emptySnapshot(Instant.parse("2026-08-09T12:00:00Z"), BigDecimal("1000.00"))
            coEvery { tradeHistoryService.getHistoryStats() } returns HistoryStats(
                allTimeHigh = BigDecimal.ZERO,
                totalTradesExecuted = 0L,
                totalVolumeTraded = BigDecimal.ZERO,
                totalFeesPaid = BigDecimal.ZERO,
                latestSnapshotTime = snapshot.timestamp,
            )
            coEvery { tradeHistoryService.getLatestSnapshot() } returns snapshot
            coEvery { tradeHistoryService.hasPendingSubmissions() } returns false
            coEvery { tradeHistoryService.getSyncMetadata(SyncMetadataKeys.SYNC_WATERMARK_EPOCH_SEC) } returns
                Long.MAX_VALUE.toString()
            coEvery { orderIntentService.countUnresolvedIntents() } returns 0L
            every { portfolioManager.isLoopPaused() } returns false
            every { portfolioManager.isLoopRunning() } returns true
            every { portfolioManager.getOperationalStatus() } returns RebalanceOperationalStatus(
                lastCycleCompletedAt = snapshot.timestamp,
            )
            every { configService.getConfig() } throws IllegalStateException("config unavailable")

            testApplication {
                application { configureTestEnv() }

                val response = client.get("/api/readiness")
                response.status shouldBe HttpStatusCode.ServiceUnavailable
                response.bodyAsText() shouldContain "\"readinessReason\":\"CONFIG_UNAVAILABLE\""
                response.bodyAsText() shouldContain "\"lastTradeSyncTime\":\"N/A\""
            }
        }

        "valid settings POST with CSRF returns 200 and redirects to the dashboard" {
            every { configService.getConfig() } returns TestFixtures.config()
            coEvery { configService.updateConfig(any()) } returns Unit

            testApplication {
                application { configureTestEnv() }
                val csrf = client.settingsCsrf()
                val response = client.post("/settings") {
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                    header(HttpHeaders.Cookie, csrf.cookie)
                    setBody(
                        parametersOf(
                            FormFields.CSRF_TOKEN to listOf(csrf.value),
                            FormFields.DEVIATION_TRIGGER_PERCENT to listOf("2.0"),
                            FormFields.MINIMUM_ORDER_SIZE_USD to listOf("5.0"),
                            FormFields.LOOP_DELAY_SECONDS to listOf("0"),
                            FormFields.FIAT_MAX_DRAWDOWN to listOf("0.0"),
                            FormFields.FIAT_DEPLOYMENT_EXPONENT to listOf("1.0"),
                            FormFields.DRY_RUN to listOf("on"),
                            FormFields.SYMBOLS to listOf("BTC"),
                            FormFields.TARGETS to listOf("50.0"),
                            FormFields.COLORS to listOf("#ffffff"),
                            FormFields.SCORES to listOf(""),
                        ).formUrlEncode(),
                    )
                }

                response.status shouldBe HttpStatusCode.OK
                response.headers["HX-Redirect"] shouldBe "/"
            }

            coVerify { configService.updateConfig(any()) }
        }

        "settings POST accepts a blank allocation color as no color" {
            every { configService.getConfig() } returns TestFixtures.config()
            val updatedConfig = slot<AppConfig>()
            coEvery { configService.updateConfig(capture(updatedConfig)) } returns Unit

            testApplication {
                application { configureTestEnv() }
                val csrf = client.settingsCsrf()
                val response = client.post("/settings") {
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                    header(HttpHeaders.Cookie, csrf.cookie)
                    setBody(
                        parametersOf(
                            FormFields.CSRF_TOKEN to listOf(csrf.value),
                            FormFields.DEVIATION_TRIGGER_PERCENT to listOf("2.0"),
                            FormFields.MINIMUM_ORDER_SIZE_USD to listOf("5.0"),
                            FormFields.LOOP_DELAY_SECONDS to listOf("0"),
                            FormFields.FIAT_MAX_DRAWDOWN to listOf("0.0"),
                            FormFields.FIAT_DEPLOYMENT_EXPONENT to listOf("1.0"),
                            FormFields.SYMBOLS to listOf("USD"),
                            FormFields.TARGETS to listOf("100.0"),
                            FormFields.COLORS to listOf(""),
                            FormFields.SCORES to listOf(""),
                        ).formUrlEncode(),
                    )
                }

                response.status shouldBe HttpStatusCode.OK
                response.headers["HX-Redirect"] shouldBe "/"
            }

            updatedConfig.captured.allocations.single().color shouldBe null
        }

        "settings POST treats a whitespace deployment threshold as zero" {
            every { configService.getConfig() } returns TestFixtures.config(
                settings = TestFixtures.settings().copy(fiatDeploymentThresholdPercent = 12.5),
            )
            val updatedConfig = slot<AppConfig>()
            coEvery { configService.updateConfig(capture(updatedConfig)) } returns Unit

            testApplication {
                application { configureTestEnv() }
                val csrf = client.settingsCsrf()
                val response = client.post("/settings") {
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                    header(HttpHeaders.Cookie, csrf.cookie)
                    setBody(
                        parametersOf(
                            FormFields.CSRF_TOKEN to listOf(csrf.value),
                            FormFields.DEVIATION_TRIGGER_PERCENT to listOf("2.0"),
                            FormFields.MINIMUM_ORDER_SIZE_USD to listOf("5.0"),
                            FormFields.LOOP_DELAY_SECONDS to listOf("0"),
                            FormFields.FIAT_MAX_DRAWDOWN to listOf("0.0"),
                            FormFields.FIAT_DEPLOYMENT_EXPONENT to listOf("1.0"),
                            FormFields.FIAT_DEPLOYMENT_THRESHOLD_PERCENT to listOf(" \t "),
                            FormFields.SYMBOLS to listOf("USD"),
                            FormFields.TARGETS to listOf("100.0"),
                            FormFields.COLORS to listOf("#ffffff"),
                            FormFields.SCORES to listOf(""),
                        ).formUrlEncode(),
                    )
                }

                response.status shouldBe HttpStatusCode.OK
                response.headers["HX-Redirect"] shouldBe "/"
            }

            updatedConfig.captured.settings.fiatDeploymentThresholdPercent shouldBe 0.0
        }

        "settings POST accepts the threshold boundary and rejects invalid optional dates and thresholds" {
            every { configService.getConfig() } returns TestFixtures.config()
            val updatedConfig = slot<AppConfig>()
            coEvery { configService.updateConfig(capture(updatedConfig)) } returns Unit

            testApplication {
                application { configureTestEnv() }
                val csrf = client.settingsCsrf()

                suspend fun save(extraFields: List<Pair<String, List<String>>>): HttpStatusCode {
                    val fields = listOf(
                        FormFields.CSRF_TOKEN to listOf(csrf.value),
                        FormFields.DEVIATION_TRIGGER_PERCENT to listOf("2.0"),
                        FormFields.MINIMUM_ORDER_SIZE_USD to listOf("5.0"),
                        FormFields.LOOP_DELAY_SECONDS to listOf("0"),
                        FormFields.FIAT_MAX_DRAWDOWN to listOf("0.0"),
                        FormFields.FIAT_DEPLOYMENT_EXPONENT to listOf("1.0"),
                        FormFields.SYMBOLS to listOf("USD"),
                        FormFields.TARGETS to listOf("100.0"),
                        FormFields.COLORS to listOf("#ffffff"),
                        FormFields.SCORES to listOf(""),
                    ) + extraFields
                    return client.post("/settings") {
                        header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                        header(HttpHeaders.Cookie, csrf.cookie)
                        setBody(parametersOf(*fields.toTypedArray()).formUrlEncode())
                    }.status
                }

                save(
                    listOf(
                        FormFields.FIAT_DEPLOYMENT_THRESHOLD_PERCENT to listOf("100.0"),
                        FormFields.INCEPTION_DATE to listOf("  "),
                        FormFields.COMPARISON_START_DATE to listOf("  "),
                    ),
                ) shouldBe HttpStatusCode.OK
                updatedConfig.captured.settings.fiatDeploymentThresholdPercent shouldBe 100.0
                updatedConfig.captured.settings.inceptionDate shouldBe null
                updatedConfig.captured.settings.comparisonStartDate shouldBe null

                val invalidOptionalFields = listOf(
                    listOf(FormFields.FIAT_DEPLOYMENT_THRESHOLD_PERCENT to listOf("Infinity")),
                    listOf(FormFields.FIAT_DEPLOYMENT_THRESHOLD_PERCENT to listOf("-0.1")),
                    listOf(FormFields.FIAT_DEPLOYMENT_THRESHOLD_PERCENT to listOf("100.1")),
                    listOf(FormFields.INCEPTION_DATE to listOf("not-a-date")),
                    listOf(FormFields.COMPARISON_START_DATE to listOf("not-a-date")),
                    listOf(FormFields.COMPARISON_START_DATE to listOf("2026-08-20")),
                    listOf(
                        FormFields.INCEPTION_DATE to listOf("2026-08-21"),
                        FormFields.COMPARISON_START_DATE to listOf("2026-08-20"),
                    ),
                )
                invalidOptionalFields.forEach { fields ->
                    save(fields) shouldBe HttpStatusCode.UnprocessableEntity
                }
            }

            coVerify(exactly = 1) { configService.updateConfig(any()) }
        }

        "settings POST rejects a verified comparison proposal without its snapshot id" {
            every { configService.getConfig() } returns TestFixtures.config()
            coEvery { tradeHistoryService.getComparisonStartProposal(any()) } returns ComparisonStartProposal(
                status = ComparisonProposalStatus.VERIFIED,
                timestamp = Instant.parse("2026-06-07T00:00:00Z"),
                snapshotId = null,
            )

            testApplication {
                application { configureTestEnv() }
                val csrf = client.settingsCsrf()
                val response = client.post("/settings") {
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                    header(HttpHeaders.Cookie, csrf.cookie)
                    setBody(
                        parametersOf(
                            FormFields.CSRF_TOKEN to listOf(csrf.value),
                            FormFields.DEVIATION_TRIGGER_PERCENT to listOf("2.0"),
                            FormFields.MINIMUM_ORDER_SIZE_USD to listOf("5.0"),
                            FormFields.LOOP_DELAY_SECONDS to listOf("0"),
                            FormFields.FIAT_MAX_DRAWDOWN to listOf("0.0"),
                            FormFields.FIAT_DEPLOYMENT_EXPONENT to listOf("1.0"),
                            FormFields.SYMBOLS to listOf("USD"),
                            FormFields.TARGETS to listOf("100.0"),
                            FormFields.COLORS to listOf("#ffffff"),
                            FormFields.SCORES to listOf(""),
                            FormFields.INCEPTION_DATE to listOf("2026-06-06"),
                            FormFields.COMPARISON_START_DATE to listOf("2026-06-07"),
                        ).formUrlEncode(),
                    )
                }

                response.status shouldBe HttpStatusCode.UnprocessableEntity
                response.bodyAsText() shouldContain "comparison start must be a valid ISO-8601"
            }

            coVerify(exactly = 0) { configService.updateConfig(any()) }
        }

        "settings POST without CSRF token is forbidden" {
            every { configService.getConfig() } returns TestFixtures.config()

            testApplication {
                application { configureTestEnv() }
                val response = client.post("/settings") {
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                    setBody(
                        parametersOf(
                            FormFields.DEVIATION_TRIGGER_PERCENT to listOf("2.0"),
                            FormFields.MINIMUM_ORDER_SIZE_USD to listOf("5.0"),
                        ).formUrlEncode(),
                    )
                }

                response.status shouldBe HttpStatusCode.Forbidden
            }
        }

        "native stale-token settings save restores submitted values without applying them" {
            every { configService.getConfig() } returns TestFixtures.config()
            val staleToken = previousProcessCsrfToken()

            testApplication {
                application { configureTestEnv() }
                val response = client.post("/settings") {
                    header(HttpHeaders.Origin, "http://localhost")
                    header(HttpHeaders.Cookie, "rebalancer-csrf=$staleToken")
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                    setBody(
                        parametersOf(
                            FormFields.CSRF_TOKEN to listOf(staleToken),
                            FormFields.LOOP_DELAY_SECONDS to listOf("17"),
                            FormFields.DEVIATION_TRIGGER_PERCENT to listOf("3.4"),
                            FormFields.MINIMUM_ORDER_SIZE_USD to listOf("12.5"),
                            FormFields.FIAT_MAX_DRAWDOWN to listOf("24"),
                            FormFields.FIAT_DEPLOYMENT_EXPONENT to listOf("1.7"),
                            // Omission follows the settings parser's zero default.
                            FormFields.INCEPTION_DATE to listOf("2026-08-20"),
                            FormFields.COMPARISON_START_DATE to listOf("2026-08-25"),
                            FormFields.SIMULATION to listOf("on"),
                            FormFields.SYMBOLS to listOf("<img src=x onerror=alert(1)>"),
                            FormFields.TARGETS to listOf("75.0"),
                            FormFields.COLORS to listOf("#aabbcc"),
                            FormFields.SCORES to listOf("2.5"),
                            FormFields.SCORE_EMPHASIS to listOf("6"),
                            FormFields.SCORE_SLEEVE_PERCENT to listOf("45"),
                        ).formUrlEncode(),
                    )
                }

                response.status shouldBe HttpStatusCode.Forbidden
                val replacementToken = response.headers["X-Rebalancer-CSRF-Token"]
                    ?: error("Stale settings recovery did not return a replacement token")
                val html = response.bodyAsText()
                html shouldContain "name=\"csrfToken\" value=\"$replacementToken\""
                html shouldContain "name=\"loopDelaySeconds\""
                html shouldContain "value=\"17\""
                html shouldContain "name=\"deviationTriggerPercent\""
                html shouldContain "value=\"3.4\""
                html shouldContain "name=\"fiatDeploymentThresholdPercent\""
                html shouldContain "value=\"0.0\""
                html shouldContain "name=\"inceptionDate\""
                html shouldContain "value=\"2026-08-20\""
                html shouldContain "name=\"scoreEmphasis\" value=\"6\""
                html shouldContain "name=\"scoreSleevePercent\""
                html shouldContain "value=\"45\""
                html shouldContain "&lt;img src=x onerror=alert(1)&gt;"
                html shouldNotContain "<img src=x onerror=alert(1)>"
                html shouldContain "value=\"75.0\""
                html shouldContain "value=\"2.5\""
            }

            coVerify(exactly = 0) { configService.updateConfig(any()) }
        }

        "native stale settings recovery defaults incomplete scalar and allocation fields" {
            every { configService.getConfig() } returns TestFixtures.config()
            val staleToken = previousProcessCsrfToken()

            data class AllocationRows(
                val symbols: List<String>,
                val targets: List<String>,
                val colors: List<String>,
                val scores: List<String>,
            )

            val completeRows = AllocationRows(
                symbols = listOf("BTC", "ETH"),
                targets = listOf("75.0", "25.0"),
                colors = listOf("#112233", "#445566"),
                scores = listOf("3.0", "4.0"),
            )
            val allocationMismatches = listOf(
                Triple(FormFields.TARGETS, completeRows.copy(targets = listOf("75.0")), listOf("75.0", "")),
                Triple(FormFields.COLORS, completeRows.copy(colors = listOf("#112233")), listOf("#112233", "")),
                Triple(FormFields.SCORES, completeRows.copy(scores = listOf("3.0")), listOf("3.0", "")),
            )

            testApplication {
                application { configureTestEnv() }

                fun inputTags(html: String, name: String): List<String> =
                    Regex("""<input\b(?=[^>]*\bname="$name")[^>]*>""")
                        .findAll(html)
                        .map { it.value }
                        .toList()

                fun inputValues(html: String, name: String): List<String> = inputTags(html, name).map { tag ->
                    Regex("""\bvalue="([^"]*)"""").find(tag)?.groupValues?.get(1)
                        ?: error("Input $name did not render a value")
                }

                suspend fun recoverSettings(fields: List<Pair<String, List<String>>>): String {
                    val response = client.post("/settings") {
                        header(HttpHeaders.Origin, "http://localhost")
                        header(HttpHeaders.Cookie, "rebalancer-csrf=$staleToken")
                        header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                        val formFields = listOf(FormFields.CSRF_TOKEN to listOf(staleToken)) + fields
                        setBody(parametersOf(*formFields.toTypedArray()).formUrlEncode())
                    }
                    response.status shouldBe HttpStatusCode.Forbidden
                    return response.bodyAsText()
                }

                fun AllocationRows.toFormFields(): List<Pair<String, List<String>>> = listOf(
                    FormFields.SYMBOLS to symbols,
                    FormFields.TARGETS to targets,
                    FormFields.COLORS to colors,
                    FormFields.SCORES to scores,
                )

                val incompleteSave = recoverSettings(
                    listOf(
                        // Missing and duplicate singleton controls render empty instead of a config value.
                        FormFields.DEVIATION_TRIGGER_PERCENT to listOf("3.4", "9.9"),
                        FormFields.MINIMUM_ORDER_SIZE_USD to listOf("12.5"),
                        FormFields.FIAT_MAX_DRAWDOWN to listOf("24"),
                        FormFields.FIAT_DEPLOYMENT_EXPONENT to listOf("1.7"),
                        FormFields.FIAT_DEPLOYMENT_THRESHOLD_PERCENT to listOf("   "),
                        FormFields.DRY_RUN to listOf("on"),
                    ) + completeRows.copy(symbols = listOf("BTC")).toFormFields(),
                )
                inputValues(incompleteSave, FormFields.LOOP_DELAY_SECONDS) shouldBe listOf("")
                inputValues(incompleteSave, FormFields.DEVIATION_TRIGGER_PERCENT) shouldBe listOf("")
                inputValues(incompleteSave, FormFields.FIAT_DEPLOYMENT_THRESHOLD_PERCENT) shouldBe listOf("0.0")
                inputValues(incompleteSave, FormFields.SCORE_EMPHASIS) shouldBe
                    listOf(QualityAllocation.DEFAULT_EMPHASIS.toString())
                inputValues(incompleteSave, FormFields.SYMBOLS) shouldBe listOf("BTC", "")
                inputValues(incompleteSave, FormFields.TARGETS).size shouldBe 2
                inputTags(incompleteSave, FormFields.SIMULATION).single() shouldNotContain "checked"
                inputTags(incompleteSave, FormFields.DRY_RUN).single() shouldContain "checked"

                allocationMismatches.forEach { (missingField, rows, expectedValues) ->
                    val html = recoverSettings(rows.toFormFields())
                    inputValues(html, FormFields.SYMBOLS).size shouldBe 2
                    inputValues(html, missingField) shouldBe expectedValues
                }

                val preview = client.post("/fragments/settings-allocations-preview") {
                    header(HttpHeaders.Origin, "http://localhost")
                    header(HttpHeaders.Cookie, "rebalancer-csrf=$staleToken")
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                    setBody(
                        parametersOf(
                            FormFields.CSRF_TOKEN to listOf(staleToken),
                            FormFields.FIAT_DEPLOYMENT_THRESHOLD_PERCENT to listOf(" 8.25 "),
                        ).formUrlEncode(),
                    )
                }
                preview.status shouldBe HttpStatusCode.Forbidden
                val previewHtml = preview.bodyAsText()
                inputValues(previewHtml, FormFields.FIAT_DEPLOYMENT_THRESHOLD_PERCENT) shouldBe listOf("8.25")
                inputValues(previewHtml, FormFields.SCORE_EMPHASIS) shouldBe
                    listOf(QualityAllocation.FALLBACK_EMPHASIS.toString())
            }

            coVerify(exactly = 0) { configService.updateConfig(any()) }
        }

        "stale-token HTMX settings save refreshes the token without swapping the form" {
            every { configService.getConfig() } returns TestFixtures.config()
            val staleToken = previousProcessCsrfToken()

            testApplication {
                application { configureTestEnv() }
                val response = client.post("/settings") {
                    header("HX-Request", "true")
                    header(HttpHeaders.Origin, "http://localhost")
                    header(HttpHeaders.Cookie, "rebalancer-csrf=$staleToken")
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                    setBody(
                        parametersOf(
                            FormFields.CSRF_TOKEN to listOf(staleToken),
                            FormFields.DEVIATION_TRIGGER_PERCENT to listOf("3.4"),
                            FormFields.SYMBOLS to listOf("BTC"),
                            FormFields.TARGETS to listOf("100"),
                            FormFields.COLORS to listOf("#aabbcc"),
                            FormFields.SCORES to listOf(""),
                        ).formUrlEncode(),
                    )
                }

                response.status shouldBe HttpStatusCode.Forbidden
                response.headers["X-Rebalancer-CSRF-Session-Expired"] shouldBe "true"
                val replacementToken = response.headers["X-Rebalancer-CSRF-Token"]
                    ?: error("Stale settings recovery did not return a replacement token")
                (replacementToken != staleToken) shouldBe true
                response.headers[HttpHeaders.SetCookie]?.substringBefore(';') shouldBe
                    "rebalancer-csrf=$replacementToken"
                response.headers["HX-Retarget"] shouldBe null
                response.headers["HX-Reswap"] shouldBe null
                response.headers["X-Rebalancer-Error-Fragment"] shouldBe null
                response.bodyAsText() shouldBe ""
            }

            coVerify(exactly = 0) { configService.updateConfig(any()) }
        }

        "stale allocation preview refreshes its token without swapping submitted row edits" {
            every { configService.getConfig() } returns TestFixtures.config()
            val staleToken = previousProcessCsrfToken()

            testApplication {
                application { configureTestEnv() }
                val response = client.post("/fragments/settings-allocations-preview") {
                    header("HX-Request", "true")
                    header(HttpHeaders.Origin, "http://localhost")
                    header(HttpHeaders.Cookie, "rebalancer-csrf=$staleToken")
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                    setBody(
                        parametersOf(
                            FormFields.CSRF_TOKEN to listOf(staleToken),
                            FormFields.LOOP_DELAY_SECONDS to listOf("29"),
                            FormFields.DEVIATION_TRIGGER_PERCENT to listOf("4.2"),
                            FormFields.MINIMUM_ORDER_SIZE_USD to listOf("15"),
                            FormFields.FIAT_MAX_DRAWDOWN to listOf("32"),
                            FormFields.FIAT_DEPLOYMENT_EXPONENT to listOf("2.1"),
                            FormFields.SYMBOLS to listOf("ETH"),
                            FormFields.TARGETS to listOf("63.5"),
                            FormFields.COLORS to listOf("#123abc"),
                            FormFields.SCORES to listOf("3.5"),
                            FormFields.SCORE_SLEEVE_PERCENT to listOf("63.5"),
                        ).formUrlEncode(),
                    )
                }

                response.status shouldBe HttpStatusCode.Forbidden
                response.headers["X-Rebalancer-CSRF-Session-Expired"] shouldBe "true"
                val replacementToken = response.headers["X-Rebalancer-CSRF-Token"]
                    ?: error("Stale preview recovery did not return a replacement token")
                (replacementToken != staleToken) shouldBe true
                response.headers[HttpHeaders.SetCookie]?.substringBefore(';') shouldBe
                    "rebalancer-csrf=$replacementToken"
                response.headers["HX-Retarget"] shouldBe null
                response.headers["HX-Reswap"] shouldBe null
                response.headers["X-Rebalancer-Error-Fragment"] shouldBe null
                response.bodyAsText() shouldBe ""
            }

            coVerify(exactly = 0) { configService.updateConfig(any()) }
        }

        "settings POST with non-numeric deviation value returns 422" {
            every { configService.getConfig() } returns TestFixtures.config()

            testApplication {
                application { configureTestEnv() }
                val csrf = client.settingsCsrf()
                val response = client.post("/settings") {
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                    header(HttpHeaders.Cookie, csrf.cookie)
                    setBody(
                        parametersOf(
                            FormFields.CSRF_TOKEN to listOf(csrf.value),
                            FormFields.DEVIATION_TRIGGER_PERCENT to listOf("not-a-number"),
                            FormFields.MINIMUM_ORDER_SIZE_USD to listOf("5.0"),
                            FormFields.LOOP_DELAY_SECONDS to listOf("0"),
                            FormFields.FIAT_MAX_DRAWDOWN to listOf("0.0"),
                            FormFields.FIAT_DEPLOYMENT_EXPONENT to listOf("1.0"),
                            FormFields.SYMBOLS to listOf("BTC"),
                            FormFields.TARGETS to listOf("50.0"),
                            FormFields.COLORS to listOf("#ffffff"),
                            FormFields.SCORES to listOf(""),
                        ).formUrlEncode(),
                    )
                }

                response.status shouldBe HttpStatusCode.UnprocessableEntity
            }
        }

        "settings POST with missing required field returns 422" {
            every { configService.getConfig() } returns TestFixtures.config()

            testApplication {
                application { configureTestEnv() }
                val csrf = client.settingsCsrf()
                val response = client.post("/settings") {
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                    header(HttpHeaders.Cookie, csrf.cookie)
                    setBody(
                        parametersOf(
                            FormFields.CSRF_TOKEN to listOf(csrf.value),
                            FormFields.MINIMUM_ORDER_SIZE_USD to listOf("5.0"),
                            FormFields.LOOP_DELAY_SECONDS to listOf("0"),
                            FormFields.FIAT_MAX_DRAWDOWN to listOf("0.0"),
                            FormFields.FIAT_DEPLOYMENT_EXPONENT to listOf("1.0"),
                            FormFields.SYMBOLS to listOf("BTC"),
                            FormFields.TARGETS to listOf("50.0"),
                            FormFields.COLORS to listOf("#ffffff"),
                            FormFields.SCORES to listOf(""),
                        ).formUrlEncode(),
                    )
                }

                response.status shouldBe HttpStatusCode.UnprocessableEntity
            }
        }

        "settings POST with duplicate deviation value returns 422" {
            every { configService.getConfig() } returns TestFixtures.config()

            testApplication {
                application { configureTestEnv() }
                val csrf = client.settingsCsrf()
                val response = client.post("/settings") {
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                    header(HttpHeaders.Cookie, csrf.cookie)
                    setBody(
                        parametersOf(
                            FormFields.CSRF_TOKEN to listOf(csrf.value),
                            FormFields.DEVIATION_TRIGGER_PERCENT to listOf("2.0", "2.0"),
                            FormFields.MINIMUM_ORDER_SIZE_USD to listOf("5.0"),
                            FormFields.LOOP_DELAY_SECONDS to listOf("0"),
                            FormFields.FIAT_MAX_DRAWDOWN to listOf("0.0"),
                            FormFields.FIAT_DEPLOYMENT_EXPONENT to listOf("1.0"),
                            FormFields.SYMBOLS to listOf("BTC"),
                            FormFields.TARGETS to listOf("50.0"),
                            FormFields.COLORS to listOf("#ffffff"),
                            FormFields.SCORES to listOf(""),
                        ).formUrlEncode(),
                    )
                }

                response.status shouldBe HttpStatusCode.UnprocessableEntity
            }
        }

        "settings POST with non-finite deviation value returns 422" {
            every { configService.getConfig() } returns TestFixtures.config()

            testApplication {
                application { configureTestEnv() }
                val csrf = client.settingsCsrf()
                val response = client.post("/settings") {
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                    header(HttpHeaders.Cookie, csrf.cookie)
                    setBody(
                        parametersOf(
                            FormFields.CSRF_TOKEN to listOf(csrf.value),
                            FormFields.DEVIATION_TRIGGER_PERCENT to listOf("Infinity"),
                            FormFields.MINIMUM_ORDER_SIZE_USD to listOf("5.0"),
                            FormFields.LOOP_DELAY_SECONDS to listOf("0"),
                            FormFields.FIAT_MAX_DRAWDOWN to listOf("0.0"),
                            FormFields.FIAT_DEPLOYMENT_EXPONENT to listOf("1.0"),
                            FormFields.SYMBOLS to listOf("BTC"),
                            FormFields.TARGETS to listOf("50.0"),
                            FormFields.COLORS to listOf("#ffffff"),
                            FormFields.SCORES to listOf(""),
                        ).formUrlEncode(),
                    )
                }

                response.status shouldBe HttpStatusCode.UnprocessableEntity
            }
        }

        "settings POST with mismatched allocation sizes returns 422" {
            every { configService.getConfig() } returns TestFixtures.config()

            testApplication {
                application { configureTestEnv() }
                val csrf = client.settingsCsrf()
                val response = client.post("/settings") {
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                    header(HttpHeaders.Cookie, csrf.cookie)
                    setBody(
                        parametersOf(
                            FormFields.CSRF_TOKEN to listOf(csrf.value),
                            FormFields.DEVIATION_TRIGGER_PERCENT to listOf("2.0"),
                            FormFields.MINIMUM_ORDER_SIZE_USD to listOf("5.0"),
                            FormFields.LOOP_DELAY_SECONDS to listOf("0"),
                            FormFields.FIAT_MAX_DRAWDOWN to listOf("0.0"),
                            FormFields.FIAT_DEPLOYMENT_EXPONENT to listOf("1.0"),
                            FormFields.SYMBOLS to listOf("BTC"),
                            FormFields.TARGETS to listOf("50.0", "25.0"),
                            FormFields.COLORS to listOf("#ffffff"),
                            FormFields.SCORES to listOf(""),
                        ).formUrlEncode(),
                    )
                }

                response.status shouldBe HttpStatusCode.UnprocessableEntity
            }
        }

        "settings POST rejects empty, misaligned, duplicate-canonical, and malformed allocation rows" {
            every { configService.getConfig() } returns TestFixtures.config()

            testApplication {
                application { configureTestEnv() }
                val csrf = client.settingsCsrf()

                suspend fun saveRows(
                    symbols: List<String>,
                    targets: List<String>,
                    colors: List<String>,
                    scores: List<String>,
                ): HttpStatusCode {
                    val fields = listOf(
                        FormFields.CSRF_TOKEN to listOf(csrf.value),
                        FormFields.DEVIATION_TRIGGER_PERCENT to listOf("2.0"),
                        FormFields.MINIMUM_ORDER_SIZE_USD to listOf("5.0"),
                        FormFields.LOOP_DELAY_SECONDS to listOf("0"),
                        FormFields.FIAT_MAX_DRAWDOWN to listOf("0.0"),
                        FormFields.FIAT_DEPLOYMENT_EXPONENT to listOf("1.0"),
                        FormFields.SYMBOLS to symbols,
                        FormFields.TARGETS to targets,
                        FormFields.COLORS to colors,
                        FormFields.SCORES to scores,
                    )
                    return client.post("/settings") {
                        header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                        header(HttpHeaders.Cookie, csrf.cookie)
                        setBody(parametersOf(*fields.toTypedArray()).formUrlEncode())
                    }.status
                }

                saveRows(emptyList(), emptyList(), emptyList(), emptyList()) shouldBe
                    HttpStatusCode.UnprocessableEntity
                saveRows(listOf("BTC"), listOf("100"), listOf("#ffffff", "#000000"), listOf("")) shouldBe
                    HttpStatusCode.UnprocessableEntity
                saveRows(listOf("BTC"), listOf("100"), listOf("#ffffff"), listOf("", "3.0")) shouldBe
                    HttpStatusCode.UnprocessableEntity
                saveRows(
                    listOf("BTC", "XBT"),
                    listOf("50", "50"),
                    listOf("#ffffff", "#000000"),
                    listOf("", ""),
                ) shouldBe HttpStatusCode.UnprocessableEntity
                saveRows(listOf("BAD-SYMBOL"), listOf("100"), listOf("#ffffff"), listOf("")) shouldBe
                    HttpStatusCode.UnprocessableEntity
            }

            coVerify(exactly = 0) { configService.updateConfig(any()) }
        }

        "settings POST with invalid hex color returns 422" {
            every { configService.getConfig() } returns TestFixtures.config()

            testApplication {
                application { configureTestEnv() }
                val csrf = client.settingsCsrf()
                val response = client.post("/settings") {
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                    header(HttpHeaders.Cookie, csrf.cookie)
                    setBody(
                        parametersOf(
                            FormFields.CSRF_TOKEN to listOf(csrf.value),
                            FormFields.DEVIATION_TRIGGER_PERCENT to listOf("2.0"),
                            FormFields.MINIMUM_ORDER_SIZE_USD to listOf("5.0"),
                            FormFields.LOOP_DELAY_SECONDS to listOf("0"),
                            FormFields.FIAT_MAX_DRAWDOWN to listOf("0.0"),
                            FormFields.FIAT_DEPLOYMENT_EXPONENT to listOf("1.0"),
                            FormFields.SYMBOLS to listOf("BTC"),
                            FormFields.TARGETS to listOf("50.0"),
                            FormFields.COLORS to listOf("not-a-color"),
                            FormFields.SCORES to listOf(""),
                        ).formUrlEncode(),
                    )
                }

                response.status shouldBe HttpStatusCode.UnprocessableEntity
            }
        }

        "settings POST that fails config validation returns 422" {
            every { configService.getConfig() } returns TestFixtures.config()
            coEvery { configService.updateConfig(any()) } throws
                InvalidConfigurationException("allocations must sum to 100%")

            testApplication {
                application { configureTestEnv() }
                val csrf = client.settingsCsrf()
                val response = client.post("/settings") {
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                    header(HttpHeaders.Cookie, csrf.cookie)
                    setBody(
                        parametersOf(
                            FormFields.CSRF_TOKEN to listOf(csrf.value),
                            FormFields.DEVIATION_TRIGGER_PERCENT to listOf("2.0"),
                            FormFields.MINIMUM_ORDER_SIZE_USD to listOf("5.0"),
                            FormFields.LOOP_DELAY_SECONDS to listOf("0"),
                            FormFields.FIAT_MAX_DRAWDOWN to listOf("0.0"),
                            FormFields.FIAT_DEPLOYMENT_EXPONENT to listOf("1.0"),
                            FormFields.SYMBOLS to listOf("BTC"),
                            FormFields.TARGETS to listOf("50.0"),
                            FormFields.COLORS to listOf("#ffffff"),
                            FormFields.SCORES to listOf(""),
                        ).formUrlEncode(),
                    )
                }

                response.status shouldBe HttpStatusCode.UnprocessableEntity
            }
        }

        "health stays up while readiness reports an ordinary diagnostic failure" {
            coEvery { tradeHistoryService.getHistoryStats() } throws
                IllegalStateException("history diagnostic unavailable")
            coEvery { tradeHistoryService.getLatestSnapshot() } returns null
            coEvery { tradeHistoryService.hasPendingSubmissions() } returns false
            coEvery { orderIntentService.countUnresolvedIntents() } returns 0L
            coEvery { tradeHistoryService.getSyncMetadata(SyncMetadataKeys.SYNC_WATERMARK_EPOCH_SEC) } returns null
            every { portfolioManager.isLoopPaused() } returns false
            every { portfolioManager.isLoopRunning() } returns true
            every { portfolioManager.getOperationalStatus() } returns RebalanceOperationalStatus()
            every { configService.getConfig() } returns TestFixtures.config()

            testApplication {
                application { configureTestEnv() }

                val healthResponse = client.get("/api/health")
                healthResponse.status shouldBe HttpStatusCode.OK
                healthResponse.bodyAsText() shouldContain "\"status\":\"UP\""
                healthResponse.bodyAsText() shouldContain "\"readinessReason\":\"DIAGNOSTICS_UNAVAILABLE\""

                val readinessResponse = client.get("/api/readiness")
                readinessResponse.status shouldBe HttpStatusCode.ServiceUnavailable
                readinessResponse.bodyAsText() shouldContain "\"status\":\"UP\""
                readinessResponse.bodyAsText() shouldContain "\"readiness\":\"NOT_READY\""
                readinessResponse.bodyAsText() shouldContain "\"readinessReason\":\"DIAGNOSTICS_UNAVAILABLE\""
            }
        }

        "health and readiness expose diagnostic cancellation as an internal server error" {
            coEvery { tradeHistoryService.getHistoryStats() } throws CancellationException("Parent job cancelled")

            testApplication {
                application { configureTestEnv() }

                // testApplication converts the unhandled CancellationException to HTTP 500; the
                // route must not swallow it and fabricate a normal diagnostic response.
                val healthResponse = client.get("/api/health")
                healthResponse.status shouldBe HttpStatusCode.InternalServerError

                val readinessResponse = client.get("/api/readiness")
                readinessResponse.status shouldBe HttpStatusCode.InternalServerError
            }
        }
    }
}
