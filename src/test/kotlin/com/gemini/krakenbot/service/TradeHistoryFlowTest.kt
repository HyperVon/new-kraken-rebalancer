@file:OptIn(ExperimentalCoroutinesApi::class)

package com.gemini.krakenbot.service

import com.gemini.krakenbot.model.PortfolioSnapshot
import io.kotest.matchers.collections.shouldContainExactly
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal

class TradeHistoryFlowTest : TradeHistoryServiceTestBase() {

    init {
        "getHistoryFlow_BroadcastsEverySnapshotToAllSubscribers" {
            runTest {
                val service = createService()
                val firstSubscriber = mutableListOf<PortfolioSnapshot>()
                val secondSubscriber = mutableListOf<PortfolioSnapshot>()

                val jobs = listOf(firstSubscriber, secondSubscriber).map { received ->
                    launch { service.getHistoryFlow().collect { received.add(it) } }
                }
                advanceUntilIdle()

                val emitted = List(3) { snapshotWorth(BigDecimal(it)) }
                emitted.forEach { service.addSnapshot(it) }
                advanceUntilIdle()

                firstSubscriber.shouldContainExactly(emitted)
                secondSubscriber.shouldContainExactly(emitted)

                jobs.forEach { it.cancel() }
            }
        }

        "getHistoryFlow_OverflowDropsOldestWithoutBlockingProducer" {
            runTest {
                val service = createService()
                val firstSubscriber = mutableListOf<PortfolioSnapshot>()
                val secondSubscriber = mutableListOf<PortfolioSnapshot>()

                val jobs = listOf(firstSubscriber, secondSubscriber).map { received ->
                    launch { service.getHistoryFlow().collect { received.add(it) } }
                }
                advanceUntilIdle()

                // Both subscribers stay parked while the producer runs past the buffer capacity.
                val emitted = List(SNAPSHOT_FLOW_BUFFER + 4) { snapshotWorth(BigDecimal(it)) }
                emitted.forEach { service.addSnapshot(it) }

                // DROP_OLDEST means the producer never suspends: every snapshot still reached the repository.
                coVerify(exactly = emitted.size) { repository.saveSnapshot(any()) }

                advanceUntilIdle()

                val retained = emitted.takeLast(SNAPSHOT_FLOW_BUFFER)
                firstSubscriber.shouldContainExactly(retained)
                secondSubscriber.shouldContainExactly(retained)

                jobs.forEach { it.cancel() }
            }
        }
    }

    private companion object {
        /** Mirrors `extraBufferCapacity` of `TradeHistorySnapshotStore` snapshotFlow. */
        const val SNAPSHOT_FLOW_REPLAY = 1
        const val SNAPSHOT_FLOW_EXTRA_BUFFER = 16
        const val SNAPSHOT_FLOW_BUFFER = SNAPSHOT_FLOW_REPLAY + SNAPSHOT_FLOW_EXTRA_BUFFER
    }
}
