package com.gemini.krakenbot.service.impl.history

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Serializes history writers with evidence-consuming queries.
 *
 * A proposal or baseline proof is only valid when its snapshot, trade, and ledger reads form one
 * stable local view. The repositories have independent transactions, so the service layer owns
 * this application-lifetime lock around writes and multi-repository evidence reads.
 */
class HistoryEvidenceCoordinator {
    private val mutex = Mutex()
    private val activeOperation = AtomicReference<String?>(null)
    private val log = LoggerFactory.getLogger(HistoryEvidenceCoordinator::class.java)

    /**
     * Keeps evidence reads and writes consistent across the independent repository transactions.
     */
    suspend fun <T> withLock(operation: String = "history", block: suspend () -> T): T {
        val waitStartedNanos = System.nanoTime()
        val ownerAtRequest = activeOperation.get()
        if (!mutex.tryLock()) {
            val waitingForLock = AtomicBoolean(true)
            val waitReporter = CoroutineScope(currentCoroutineContext()).launch {
                delay(SLOW_OPERATION_MILLIS)
                if (waitingForLock.get() && mutex.isLocked) {
                    log.warn(
                        "Still waiting for history evidence lock; operation={} waitMs={} currentOwner={}",
                        operation,
                        (System.nanoTime() - waitStartedNanos) / NANOS_PER_MILLI,
                        activeOperation.get() ?: "unknown",
                    )
                }
            }
            try {
                mutex.lock()
            } finally {
                waitingForLock.set(false)
                waitReporter.cancel()
            }
        }
        val acquiredNanos = System.nanoTime()
        activeOperation.set(operation)
        val waitMillis = (acquiredNanos - waitStartedNanos) / NANOS_PER_MILLI
        if (waitMillis >= SLOW_OPERATION_MILLIS) {
            log.warn(
                "History evidence lock acquired; operation={} waitMs={} ownerAtRequest={}",
                operation,
                waitMillis,
                ownerAtRequest ?: "unknown",
            )
        }
        return try {
            block()
        } finally {
            val heldMillis = (System.nanoTime() - acquiredNanos) / NANOS_PER_MILLI
            if (heldMillis >= SLOW_OPERATION_MILLIS) {
                log.warn("History evidence lock released; operation={} heldMs={}", operation, heldMillis)
            }
            activeOperation.compareAndSet(operation, null)
            mutex.unlock()
        }
    }

    suspend fun tryWithLock(operation: String = "history", block: suspend () -> Unit): Boolean {
        val coroutineContext = currentCoroutineContext()
        coroutineContext.ensureActive()
        if (!mutex.tryLock()) return false
        val acquiredNanos = System.nanoTime()
        activeOperation.set(operation)

        return try {
            coroutineContext.ensureActive()
            block()
            true
        } finally {
            val heldMillis = (System.nanoTime() - acquiredNanos) / NANOS_PER_MILLI
            if (heldMillis >= SLOW_OPERATION_MILLIS) {
                log.warn("History evidence lock released; operation={} heldMs={}", operation, heldMillis)
            }
            activeOperation.compareAndSet(operation, null)
            mutex.unlock()
        }
    }

    private companion object {
        const val SLOW_OPERATION_MILLIS = 5_000L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
