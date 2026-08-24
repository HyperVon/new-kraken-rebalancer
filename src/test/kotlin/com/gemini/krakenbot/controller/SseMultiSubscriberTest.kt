package com.gemini.krakenbot.controller

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.model.PortfolioSnapshot
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.ktor.client.plugins.sse.sse
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.every
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import java.math.BigDecimal
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds
import io.ktor.client.plugins.sse.SSE as ClientSSE

/**
 * Controller-level multi-SSE concurrent-subscriber coverage (CQ-14-M9).
 *
 * Complements `DashboardHistoryApiTest` (single-subscriber cold flow) and
 * `TradeHistoryFlowTest` (service-level hot SharedFlow broadcast) by wiring the
 * production `/api/status/stream` route through a real `MutableSharedFlow`
 * matching `TradeHistorySnapshotStore.snapshotFlow` (replay = 1,
 * extraBufferCapacity = 16, DROP_OLDEST) and asserting that multiple concurrent
 * HTTP SSE subscribers each receive the persisted initial snapshot followed by
 * hot-flow broadcasts — the documented behavior in `DashboardController.kt`
 * lines 308-324 (replay closes the subscribe race; per-session errors stay
 * local; slow collectors never block producers).
 */
class SseMultiSubscriberTest : DashboardControllerTestBase() {

    private companion object {
        /** Mirrors `TradeHistorySnapshotStore.snapshotFlow` configuration. */
        const val REPLAY = 1
        const val EXTRA_BUFFER = 16

        /** Upper bound for all concurrent SSE connections to subscribe before broadcasting. */
        const val SUBSCRIBE_TIMEOUT_MS = 30_000L
    }

    /**
     * Waits until [flow] has [expected] active collectors, so a broadcast is only emitted after all
     * concurrent SSE connections have subscribed server-side. Bounded in real time because this is
     * a real-HTTP integration test that cannot use a virtual scheduler.
     */
    private suspend fun awaitSubscribers(flow: MutableSharedFlow<*>, expected: Int) {
        withTimeout(SUBSCRIBE_TIMEOUT_MS.milliseconds) {
            flow.subscriptionCount.first { active -> active >= expected }
        }
    }

    init {
        "sseStatusStream_MultipleConcurrentSubscribersEachReceivePersistedThenHotBroadcast" {
            val initial = TestFixtures.emptySnapshot(Instant.parse("2026-07-31T10:00:00Z"), BigDecimal("10000.00"))
            val update = TestFixtures.emptySnapshot(Instant.parse("2026-07-31T10:01:00Z"), BigDecimal("12000.00"))
            coEvery { tradeHistoryService.getLatestSnapshot() } returns initial
            val flow = MutableSharedFlow<PortfolioSnapshot>(replay = REPLAY, extraBufferCapacity = EXTRA_BUFFER)
            every { tradeHistoryService.getHistoryFlow() } returns flow

            testApplication {
                val client = createClient { install(ClientSSE) }
                application { configureTestEnv() }

                val subscriberCount = 3
                val collected = List(subscriberCount) { mutableListOf<String>() }
                val jobs = collected.map { received ->
                    async {
                        client.sse("/api/status/stream") {
                            val events = incoming.take(2).toList()
                            events.forEach { event -> received.add(event.data ?: "") }
                        }
                    }
                }

                awaitSubscribers(flow, subscriberCount)
                flow.tryEmit(update)

                jobs.awaitAll()

                val expectedJson = listOf(
                    objectMapper.writeValueAsString(initial),
                    objectMapper.writeValueAsString(update),
                )
                collected.forEach { received -> received shouldContainExactly expectedJson }
            }
        }

        "sseStatusStream_HotFlowReplayServesLateSubscriber" {
            val initial = TestFixtures.emptySnapshot(Instant.parse("2026-07-31T10:00:00Z"), BigDecimal("10000.00"))
            val broadcast = TestFixtures.emptySnapshot(Instant.parse("2026-07-31T10:01:00Z"), BigDecimal("12000.00"))
            coEvery { tradeHistoryService.getLatestSnapshot() } returns initial
            val flow = MutableSharedFlow<PortfolioSnapshot>(replay = REPLAY, extraBufferCapacity = EXTRA_BUFFER)
            every { tradeHistoryService.getHistoryFlow() } returns flow

            // A late subscriber joins after the broadcast has already been emitted; replay = 1
            // closes the subscribe race so the late client still observes it.
            flow.tryEmit(broadcast)

            testApplication {
                val client = createClient { install(ClientSSE) }
                application { configureTestEnv() }

                client.sse("/api/status/stream") {
                    val events = incoming.take(2).toList()
                    events[0].data shouldBe objectMapper.writeValueAsString(initial)
                    events[1].data shouldBe objectMapper.writeValueAsString(broadcast)
                }
            }
        }

        "sseStatusStream_SurvivorSubscriberContinuesReceivingAfterAnotherSubscriberLeaves" {
            val initial = TestFixtures.emptySnapshot(Instant.parse("2026-07-31T10:00:00Z"), BigDecimal("10000.00"))
            val firstBroadcast = TestFixtures.emptySnapshot(
                Instant.parse("2026-07-31T10:01:00Z"),
                BigDecimal("11000.00"),
            )
            val secondBroadcast = TestFixtures.emptySnapshot(
                Instant.parse("2026-07-31T10:02:00Z"),
                BigDecimal("13000.00"),
            )
            coEvery { tradeHistoryService.getLatestSnapshot() } returns initial
            val flow = MutableSharedFlow<PortfolioSnapshot>(replay = REPLAY, extraBufferCapacity = EXTRA_BUFFER)
            every { tradeHistoryService.getHistoryFlow() } returns flow

            testApplication {
                val client = createClient { install(ClientSSE) }
                application { configureTestEnv() }

                // A survivor subscriber stays parked and waits for the initial + two broadcasts.
                val survivor = mutableListOf<String>()
                val survivorJob = async {
                    client.sse("/api/status/stream") {
                        incoming.take(3).toList().forEach { e -> survivor.add(e.data ?: "") }
                    }
                }

                // A second subscriber stays connected through the first broadcast, then closes — its
                // departure must not disturb the survivor. Keeping the client flow active until the
                // first broadcast prevents the server-side collector from leaving before the barrier.
                val departed = async {
                    client.sse("/api/status/stream") {
                        incoming.take(2).toList()
                    }
                }
                awaitSubscribers(flow, 2)
                flow.tryEmit(firstBroadcast)
                departed.await()

                // After the second subscriber has gone, a further broadcast still reaches the survivor.
                flow.tryEmit(secondBroadcast)
                survivorJob.await()

                survivor shouldContainExactly listOf(
                    objectMapper.writeValueAsString(initial),
                    objectMapper.writeValueAsString(firstBroadcast),
                    objectMapper.writeValueAsString(secondBroadcast),
                )
            }
        }

        "sseStatusStream_ReplayDuplicatesInitialSnapshotAtSubscribe_DualPathStillYieldsInitialContent" {
            val initial = TestFixtures.emptySnapshot(Instant.parse("2026-07-31T10:00:00Z"), BigDecimal("10000.00"))
            coEvery { tradeHistoryService.getLatestSnapshot() } returns initial
            val flow = MutableSharedFlow<PortfolioSnapshot>(replay = REPLAY, extraBufferCapacity = EXTRA_BUFFER)
            // The replay cache already holds `initial`, so a freshly-connected client may observe it
            // as both the persisted send and the replayed flow event. Taking two events pins the dual
            // path (persisted send + replayed flow collect) — a regression that drops either branch
            // would reduce the count to one and surface here, while preserving content equality.
            flow.tryEmit(initial)
            every { tradeHistoryService.getHistoryFlow() } returns flow

            testApplication {
                val client = createClient { install(ClientSSE) }
                application { configureTestEnv() }

                client.sse("/api/status/stream") {
                    val events = incoming.take(2).toList()
                    events.forEach { it.data shouldBe objectMapper.writeValueAsString(initial) }
                }
            }
        }
    }
}
