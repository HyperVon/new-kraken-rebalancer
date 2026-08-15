package com.gemini.krakenbot.controller

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.InvalidConfigurationException
import com.gemini.krakenbot.model.HistoryStats
import com.gemini.krakenbot.model.OrderIntent
import com.gemini.krakenbot.model.OrderIntentState
import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.service.RebalanceOperationalStatus
import com.gemini.krakenbot.view.util.FormFields
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
import java.math.BigDecimal
import java.time.Instant

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

            testApplication {
                application { configureTestEnv() }
                val csrf = client.settingsCsrf()

                suspend fun resolve(id: String, state: String, evidence: String) =
                    client.post("/api/order-intents/$id/resolve") {
                        header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                        header(HttpHeaders.Cookie, csrf.cookie)
                        setBody(
                            parametersOf(
                                FormFields.CSRF_TOKEN to listOf(csrf.value),
                                FormFields.ORDER_INTENT_STATE to listOf(state),
                                FormFields.ORDER_INTENT_EVIDENCE to listOf(evidence),
                            ).formUrlEncode(),
                        )
                    }

                resolve("not-an-id", "CONFIRMED", "evidence").status shouldBe HttpStatusCode.BadRequest
                resolve("0", "CONFIRMED", "evidence").status shouldBe HttpStatusCode.BadRequest
                resolve("-5", "CONFIRMED", "evidence").status shouldBe HttpStatusCode.BadRequest
                resolve("7", "NOT_A_STATE", "evidence").status shouldBe HttpStatusCode.UnprocessableEntity
                resolve("7", "PENDING", "evidence").status shouldBe HttpStatusCode.UnprocessableEntity
                resolve("7", "CONFIRMED", "").status shouldBe HttpStatusCode.UnprocessableEntity
                resolve("7", "REJECTED", "already checked").status shouldBe HttpStatusCode.Conflict
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
                        ).formUrlEncode(),
                    )
                }

                response.status shouldBe HttpStatusCode.OK
                response.headers["HX-Redirect"] shouldBe "/"
            }

            coVerify { configService.updateConfig(any()) }
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
                        ).formUrlEncode(),
                    )
                }

                response.status shouldBe HttpStatusCode.UnprocessableEntity
            }
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
                        ).formUrlEncode(),
                    )
                }

                response.status shouldBe HttpStatusCode.UnprocessableEntity
            }
        }
    }
}
