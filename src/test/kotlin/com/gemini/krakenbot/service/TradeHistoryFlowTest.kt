@file:OptIn(ExperimentalCoroutinesApi::class)

package com.gemini.krakenbot.service

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.OrderSide
import com.gemini.krakenbot.model.OrderSubmissionState
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.PortfolioStats
import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.model.TradeSource
import com.gemini.krakenbot.repository.PortfolioStatsRepository
import com.gemini.krakenbot.repository.TradeRepository
import com.gemini.krakenbot.repository.TradeSummaryStats
import com.gemini.krakenbot.service.impl.DynamicKrakenService
import com.gemini.krakenbot.service.impl.KrakenServiceImpl
import com.gemini.krakenbot.service.impl.SimulatedKrakenService
import com.gemini.krakenbot.service.impl.history.TradeHistoryReconstructionService
import com.gemini.krakenbot.service.impl.history.TradeHistoryServiceImpl
import com.gemini.krakenbot.util.TradeCalculator
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import java.io.File
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.time.Duration.Companion.milliseconds

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
