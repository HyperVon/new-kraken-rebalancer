package com.gemini.krakenbot.controller

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.model.PortfolioSnapshot
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.ktor.client.plugins.sse.sse
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import java.math.BigDecimal
import java.time.Instant
import io.ktor.client.plugins.sse.SSE as ClientSSE

class SseMultiSubscriberTest : DashboardControllerTestBase() {
    init {
        "concurrent SSE subscribers receive the latest current snapshot and its next update" {
            val initial = TestFixtures.emptySnapshot(Instant.parse("2026-10-10T12:00:00Z"), BigDecimal("10000.00"))
            val update = TestFixtures.emptySnapshot(Instant.parse("2026-10-10T12:01:00Z"), BigDecimal("12000.00"))
            currentPortfolioState.publish(initial, "test-source")

            testApplication {
                val client = createClient { install(ClientSSE) }
                application { configureTestEnv() }
                val subscriberCount = 3
                val firstEventSeen = List(subscriberCount) { CompletableDeferred<Unit>() }
                val received = List(subscriberCount) { mutableListOf<String>() }
                val jobs = received.indices.map { index ->
                    async {
                        client.sse("/api/status/stream") {
                            incoming.take(2).onEach { event ->
                                received[index].add(event.data.orEmpty())
                                if (received[index].size == 1) firstEventSeen[index].complete(Unit)
                            }.toList()
                        }
                    }
                }

                firstEventSeen.awaitAll()
                currentPortfolioState.publish(update, "test-source")
                jobs.awaitAll()

                val expected = listOf(
                    objectMapper.writeValueAsString(initial),
                    objectMapper.writeValueAsString(update),
                )
                received.forEach { it shouldContainExactly expected }
            }
        }

        "late SSE subscribers start at the current snapshot without replaying retired history" {
            val initial = TestFixtures.emptySnapshot(Instant.parse("2026-10-10T12:00:00Z"), BigDecimal("10000.00"))
            val current = TestFixtures.emptySnapshot(Instant.parse("2026-10-10T12:02:00Z"), BigDecimal("13000.00"))
            currentPortfolioState.publish(initial, "test-source")
            currentPortfolioState.publish(current, "test-source")

            testApplication {
                val client = createClient { install(ClientSSE) }
                application { configureTestEnv() }
                client.sse("/api/status/stream") {
                    val event = incoming.take(1).toList().single()
                    event.data shouldBe objectMapper.writeValueAsString(current)
                }
            }
        }
    }
}
