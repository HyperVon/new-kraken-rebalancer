package com.gemini.krakenbot.service

import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.TradeRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

/** A bounded asynchronous boundary between trading and all reporting writes. */
class ReportingDispatcher(
    private val historyServiceProvider: () -> TradeHistoryService,
    private val projectionServiceProvider: () -> TradeProjectionService,
) {
    private val log = LoggerFactory.getLogger(ReportingDispatcher::class.java)
    private val tradeQueue = Channel<TradeRecord>(capacity = TRADE_QUEUE_CAPACITY)
    private val snapshotQueue = Channel<PortfolioSnapshot>(capacity = Channel.CONFLATED)
    private val started = AtomicBoolean(false)
    private val historyInitializationMutex = Mutex()

    @Volatile
    private var initializedHistoryService: TradeHistoryService? = null

    suspend fun initializeBeforeSimulationCycle() {
        historyInitializationMutex.withLock {
            if (initializedHistoryService == null) {
                initializedHistoryService = historyServiceProvider().also { it.init() }
            }
        }
    }

    suspend fun persistSimulationTrade(trade: TradeRecord): Int = requireNotNull(initializedHistoryService) {
        "Simulation reporting must be initialized before persisting emulator trades."
    }.saveTrade(trade)

    suspend fun updateSimulationTrade(oldTrade: TradeRecord, newTrade: TradeRecord) {
        requireNotNull(initializedHistoryService) {
            "Simulation reporting must be initialized before updating emulator trades."
        }.updateTrade(oldTrade, newTrade)
    }

    suspend fun persistSimulationSnapshot(snapshot: PortfolioSnapshot) {
        requireNotNull(initializedHistoryService) {
            "Simulation reporting must be initialized before persisting emulator snapshots."
        }.addSnapshot(snapshot)
    }

    fun enqueueTrade(trade: TradeRecord) {
        if (tradeQueue.trySend(trade).isFailure) {
            log.error("Reporting queue is full; a non-live trade record was not queued")
        }
    }

    fun enqueueSnapshot(snapshot: PortfolioSnapshot) {
        snapshotQueue.trySend(snapshot)
    }

    fun start(scope: CoroutineScope): Job {
        check(started.compareAndSet(false, true)) { "Reporting dispatcher has already been started." }
        return scope.launch(Dispatchers.IO) {
            var historyService = initializedHistoryService
            var nextInitializationAttemptAt = 0L
            var nextProjectionAttemptAt = 0L
            while (isActive) {
                val now = System.currentTimeMillis()
                if (historyService == null && now >= nextInitializationAttemptAt) {
                    try {
                        initializeBeforeSimulationCycle()
                        historyService = initializedHistoryService
                        log.info("Reporting database initialization completed")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.warn("Reporting database is unavailable; background reporting will retry", e)
                        nextInitializationAttemptAt = now + REPORT_RETRY_MILLIS
                    }
                }
                val service = historyService
                if (service != null) {
                    drainOne(tradeQueue.tryReceive().getOrNull()) { service.saveTrade(it) }
                    drainOne(snapshotQueue.tryReceive().getOrNull()) { service.addSnapshot(it) }
                    if (now >= nextProjectionAttemptAt) {
                        try {
                            projectionServiceProvider().projectPending()
                        } catch (e: Exception) {
                            log.warn("Execution trade projection failed; the durable outbox will retry", e)
                        }
                        nextProjectionAttemptAt = now + PROJECTION_RETRY_MILLIS
                    }
                }
                delay(WORKER_POLL_MILLIS)
            }
        }
    }

    private suspend fun <T> drainOne(value: T?, persist: suspend (T) -> Unit) {
        if (value == null) return
        try {
            persist(value)
        } catch (e: Exception) {
            log.warn("Background reporting write failed; live order execution is independent", e)
        }
    }

    private companion object {
        const val TRADE_QUEUE_CAPACITY = 256
        const val WORKER_POLL_MILLIS = 250L
        const val REPORT_RETRY_MILLIS = 10_000L
        const val PROJECTION_RETRY_MILLIS = 2_000L
    }
}
