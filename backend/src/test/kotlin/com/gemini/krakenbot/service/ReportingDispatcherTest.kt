package com.gemini.krakenbot.service

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.TradeRecord
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.time.Instant

class ReportingDispatcherTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "simulation writes require initialization and share one initialized reporting service" {
            runBlocking {
                val history = mockk<TradeHistoryService>(relaxed = true)
                coEvery { history.saveTrade(any()) } returns 1
                val dispatcher = ReportingDispatcher(
                    historyServiceProvider = { history },
                    projectionServiceProvider = { error("projection is not used by simulation writes") },
                )
                val trade = trade()
                val snapshot = TestFixtures.emptySnapshot(Instant.parse("2026-10-08T12:00:00Z"), trade.usdAmount)

                shouldThrow<IllegalArgumentException> { dispatcher.persistSimulationTrade(trade) }
                shouldThrow<IllegalArgumentException> { dispatcher.updateSimulationTrade(trade, trade) }
                shouldThrow<IllegalArgumentException> { dispatcher.persistSimulationSnapshot(snapshot) }

                dispatcher.initializeBeforeSimulationCycle()
                dispatcher.initializeBeforeSimulationCycle()
                dispatcher.persistSimulationTrade(trade) shouldBe 1
                dispatcher.updateSimulationTrade(trade, trade)
                dispatcher.persistSimulationSnapshot(snapshot)

                coVerify(exactly = 1) { history.init() }
                coVerify(exactly = 1) { history.saveTrade(trade) }
                coVerify(exactly = 1) { history.updateTrade(trade, trade) }
                coVerify(exactly = 1) { history.addSnapshot(snapshot) }
            }
        }

        "background reporting failures do not stop the worker or delay queue handling" {
            runBlocking {
                val history = mockk<TradeHistoryService>(relaxed = true)
                val tradeAttempt = CompletableDeferred<Unit>()
                val snapshotAttempt = CompletableDeferred<Unit>()
                coEvery { history.saveTrade(any()) } coAnswers {
                    tradeAttempt.complete(Unit)
                    throw IOException("reporting write locked")
                }
                coEvery { history.addSnapshot(any()) } coAnswers {
                    snapshotAttempt.complete(Unit)
                    throw IOException("snapshot write locked")
                }
                val projectionAttempt = CompletableDeferred<Unit>()
                val projection = mockk<TradeProjectionService>()
                coEvery { projection.projectPending() } coAnswers {
                    projectionAttempt.complete(Unit)
                    throw IOException("reporting projection unavailable")
                }
                val dispatcher = ReportingDispatcher({ history }, { projection })
                dispatcher.initializeBeforeSimulationCycle()
                dispatcher.enqueueTrade(trade())
                dispatcher.enqueueSnapshot(
                    TestFixtures.emptySnapshot(Instant.parse("2026-10-08T12:00:00Z"), java.math.BigDecimal("25.00")),
                )
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val worker = dispatcher.start(scope)
                try {
                    withTimeout(5_000) {
                        tradeAttempt.await()
                        snapshotAttempt.await()
                        projectionAttempt.await()
                    }
                    worker.isActive shouldBe true
                    shouldThrow<IllegalStateException> { dispatcher.start(scope) }
                } finally {
                    worker.cancelAndJoin()
                    scope.cancel()
                }
                coVerify(exactly = 1) { history.saveTrade(any()) }
                coVerify(exactly = 1) { history.addSnapshot(any()) }
                coVerify(exactly = 1) { projection.projectPending() }
            }
        }

        "failed background initialization is retried without invoking history projections" {
            runBlocking {
                val history = mockk<TradeHistoryService>(relaxed = true)
                val initAttempt = CompletableDeferred<Unit>()
                coEvery { history.init() } coAnswers {
                    initAttempt.complete(Unit)
                    throw IOException("reporting database unavailable")
                }
                val projection = mockk<TradeProjectionService>(relaxed = true)
                val dispatcher = ReportingDispatcher({ history }, { projection })
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val worker = dispatcher.start(scope)
                try {
                    withTimeout(5_000) { initAttempt.await() }
                    worker.isActive shouldBe true
                    coVerify(exactly = 0) { projection.projectPending() }
                } finally {
                    worker.cancelAndJoin()
                    scope.cancel()
                }
            }
        }
    }

    private fun trade(): TradeRecord = TestFixtures.tradeRecord(
        timestamp = Instant.parse("2026-10-08T12:00:00Z"),
        pair = TestFixtures.XBTUSD,
        side = TestFixtures.BUY,
        symbol = Asset.BTC,
        volume = java.math.BigDecimal("0.025"),
        usdAmount = java.math.BigDecimal("25.00"),
    )
}
