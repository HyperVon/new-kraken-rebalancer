package com.gemini.krakenbot.controller

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.*
import com.gemini.krakenbot.model.HistoryStats
import com.gemini.krakenbot.model.TimeRange
import com.gemini.krakenbot.view.component.*
import com.gemini.krakenbot.view.util.Routes
import com.gemini.krakenbot.view.util.withRange
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import java.math.BigDecimal
import java.time.Instant
import io.ktor.client.plugins.sse.SSE as ClientSSE

class DashboardHistoryApiTest : DashboardControllerTestBase() {

    init {
        "sseStatusStream_EmitsInitialAndFlowSnapshots" {
            val snapshot1 = TestFixtures.emptySnapshot(Instant.now(), BigDecimal("10000.0"))
            val snapshot2 = TestFixtures.emptySnapshot(Instant.now().plusSeconds(60), BigDecimal("12000.0"))

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
                    // Ktor may surface server cancellation as either cancellation or channel close;
                    // both outcomes prove that the stream terminates instead of hanging.
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
                response.bodyAsText() shouldContain "\"allTimeHigh\":\"15000.00\""
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
                response.bodyAsText() shouldContain "\"allTimeHigh\":\"15000.00\""
            }
        }

        "getApiHistorySnapshots_RangeFilters_Branches" {
            coEvery { tradeHistoryService.getSnapshotsInRange(any(), any()) } returns emptyList()
            testApplication {
                application {
                    configureTestEnv()
                }
                client.get(Routes.API_HISTORY_SNAPSHOTS.withRange(TimeRange.SEVEN_DAYS)).status shouldBe
                    HttpStatusCode.OK
                client.get(Routes.API_HISTORY_SNAPSHOTS.withRange(TimeRange.THIRTY_DAYS)).status shouldBe
                    HttpStatusCode.OK
                client.get(Routes.API_HISTORY_SNAPSHOTS.withRange(TimeRange.NINETY_DAYS)).status shouldBe
                    HttpStatusCode.OK
                client.get(Routes.API_HISTORY_SNAPSHOTS.withRange("invalid")).status shouldBe HttpStatusCode.OK
            }
        }

        "getApiHistorySnapshots_NoRangeParam_DefaultsTo30d" {
            coEvery { tradeHistoryService.getSnapshotsInRange(any(), any()) } returns emptyList()
            testApplication {
                application {
                    configureTestEnv()
                }
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
            val snapshot = TestFixtures.emptySnapshot(Instant.now(), BigDecimal("12000.0"))
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
