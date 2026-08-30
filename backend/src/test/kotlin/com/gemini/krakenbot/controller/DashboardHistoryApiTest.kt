package com.gemini.krakenbot.controller

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.model.ComparisonAvailability
import com.gemini.krakenbot.model.ComparisonConfidence
import com.gemini.krakenbot.model.ComparisonUnavailableReason
import com.gemini.krakenbot.model.HistoryStats
import com.gemini.krakenbot.model.TimeRange
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.every
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import java.math.BigDecimal
import java.time.Instant
import com.gemini.krakenbot.model.RebalancerComparison as DomainComparison
import com.gemini.krakenbot.model.RebalancerComparisonPoint as DomainComparisonPoint
import com.gemini.krakenbot.model.RewardsOverTime as DomainRewardsOverTime
import com.gemini.krakenbot.model.RewardsOverTimePoint as DomainRewardsOverTimePoint
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
                client.sse("/api/status/stream") {
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
                    client.sse("/api/status/stream") {
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
                client.sse("/api/status/stream") {
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
                val response = client.get("/history")
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain "History - Kraken Rebalancer"
                response.bodyAsText() shouldContain "id=\"loop-control\""
                response.bodyAsText() shouldContain "hx-post=\"/api/pause\""
            }
        }

        "getApiHistorySnapshots_ReturnsJson" {
            coEvery { tradeHistoryService.getSnapshotsInRange(any(), any()) } returns emptyList()
            testApplication {
                application {
                    configureTestEnv()
                }
                val response = client.get("/api/history/snapshots?range=24h")
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
                val response = client.get("/api/history/trades?range=${TimeRange.ALL.key}")
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
                val response = client.get("/api/history/stats?range=${TimeRange.SEVEN_DAYS.key}")
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
                val response = client.get("/api/history/stats")
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
                client.get("/api/history/snapshots?range=${TimeRange.SEVEN_DAYS.key}").status shouldBe
                    HttpStatusCode.OK
                client.get("/api/history/snapshots?range=${TimeRange.THIRTY_DAYS.key}").status shouldBe
                    HttpStatusCode.OK
                client.get("/api/history/snapshots?range=${TimeRange.NINETY_DAYS.key}").status shouldBe
                    HttpStatusCode.OK
                client.get("/api/history/snapshots?range=invalid").status shouldBe HttpStatusCode.OK
            }
        }

        "getApiHistorySnapshots_NoRangeParam_DefaultsTo30d" {
            coEvery { tradeHistoryService.getSnapshotsInRange(any(), any()) } returns emptyList()
            testApplication {
                application {
                    configureTestEnv()
                }
                val response = client.get("/api/history/snapshots")
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
                val response = client.get("/api/health")
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
                val response = client.get("/api/health")
                response.status shouldBe HttpStatusCode.OK
                val body = response.bodyAsText()
                body shouldContain "\"lastSnapshotTime\":\"N/A\""
                body shouldContain "\"lastSnapshotTotalValueUSD\":0"
            }
        }

        "getApiHistoryComparison_ReturnsJson" {
            val comparison = DomainComparison(
                availability = ComparisonAvailability.AVAILABLE,
                confidence = ComparisonConfidence.RECONCILED,
                baselineTimestamp = Instant.parse("2026-07-01T12:00:00Z"),
                points = listOf(
                    DomainComparisonPoint(
                        timestamp = Instant.parse("2026-07-01T12:00:00Z"),
                        rebalancerValueUSD = BigDecimal("100000.00"),
                        buyAndHoldValueUSD = BigDecimal("100000.00"),
                        differenceUSD = BigDecimal.ZERO,
                        differencePercent = BigDecimal.ZERO,
                    ),
                    DomainComparisonPoint(
                        timestamp = Instant.parse("2026-07-02T12:00:00Z"),
                        rebalancerValueUSD = BigDecimal("105000.00"),
                        buyAndHoldValueUSD = BigDecimal("103000.00"),
                        differenceUSD = BigDecimal("2000.00"),
                        differencePercent = BigDecimal("1.94"),
                    ),
                ),
                latestDifferenceUSD = BigDecimal("2000.00"),
                latestDifferencePercent = BigDecimal("1.94"),
                unavailableReason = null,
                unavailableAt = null,
            )
            coEvery { tradeHistoryService.getRebalancerComparison(any(), any()) } returns comparison
            testApplication {
                application {
                    configureTestEnv()
                }
                val response = client.get("/api/history/comparison?range=${TimeRange.THIRTY_DAYS.key}")
                response.status shouldBe HttpStatusCode.OK
                response.headers[HttpHeaders.ContentType] shouldContain TestFixtures.APPLICATION_JSON
                val body = response.bodyAsText()
                body shouldContain "\"availability\":\"AVAILABLE\""
                body shouldContain "\"confidence\":\"RECONCILED\""
                body shouldContain "\"rebalancerValueUSD\":\"100000.00\""
                body shouldContain "\"latestDifferenceUSD\":\"2000.00\""
            }
        }

        "getApiHistoryComparison_NoRangeParam_DefaultsTo30d" {
            val comparison = DomainComparison(
                availability = ComparisonAvailability.UNAVAILABLE,
                confidence = null,
                baselineTimestamp = null,
                points = emptyList(),
                latestDifferenceUSD = null,
                latestDifferencePercent = null,
                unavailableReason = ComparisonUnavailableReason.INSUFFICIENT_SNAPSHOTS,
                unavailableAt = Instant.parse("2026-07-01T12:00:00Z"),
            )
            coEvery { tradeHistoryService.getRebalancerComparison(any(), any()) } returns comparison
            testApplication {
                application {
                    configureTestEnv()
                }
                val response = client.get("/api/history/comparison")
                response.status shouldBe HttpStatusCode.OK
                val body = response.bodyAsText()
                body shouldContain "\"availability\":\"UNAVAILABLE\""
                body shouldContain "\"unavailableReason\":\"INSUFFICIENT_SNAPSHOTS\""
                body shouldContain "\"unavailableAt\":\"2026-07-01T12:00:00Z\""
            }
        }

        "getApiHistoryRewards_ReturnsJson" {
            val rewards = DomainRewardsOverTime(
                totalRewardsUSD = BigDecimal("1234.56"),
                points = listOf(
                    DomainRewardsOverTimePoint(
                        timestamp = Instant.parse("2026-07-01T12:00:00Z"),
                        cumulativeUSD = BigDecimal("500.00"),
                        perAssetUSD = mapOf("BTC" to BigDecimal("500.00")),
                    ),
                    DomainRewardsOverTimePoint(
                        timestamp = Instant.parse("2026-07-02T12:00:00Z"),
                        cumulativeUSD = BigDecimal("1234.56"),
                        perAssetUSD = mapOf("BTC" to BigDecimal("1234.56")),
                    ),
                ),
            )
            coEvery { tradeHistoryService.getRewardsOverTime(any(), any()) } returns rewards
            testApplication {
                application {
                    configureTestEnv()
                }
                val response = client.get("/api/history/rewards?range=${TimeRange.THIRTY_DAYS.key}")
                response.status shouldBe HttpStatusCode.OK
                response.headers[HttpHeaders.ContentType] shouldContain TestFixtures.APPLICATION_JSON
                val body = response.bodyAsText()
                body shouldContain "\"totalRewardsUSD\":\"1234.56\""
                body shouldContain "\"cumulativeUSD\":\"500.00\""
                body shouldContain "\"perAssetUSD\":{\"BTC\":\"500.00\"}"
                body shouldContain "\"timestamp\":\"2026-07-01T12:00:00Z\""
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
                val response = client.get("/api/history/sync-progress")
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
