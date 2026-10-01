package com.gemini.krakenbot.service.impl.history

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex

/**
 * Serializes history writers with evidence-consuming queries.
 *
 * A proposal or baseline proof is only valid when its snapshot, trade, and ledger reads form one
 * stable local view. The repositories have independent transactions, so the service layer owns
 * this application-lifetime lock around writes and multi-repository evidence reads.
 */
class HistoryEvidenceCoordinator {
    private val mutex = Mutex()

    suspend fun <T> withLock(block: suspend () -> T): T {
        mutex.lock()
        return try {
            block()
        } finally {
            mutex.unlock()
        }
    }

    suspend fun tryWithLock(block: suspend () -> Unit): Boolean {
        val coroutineContext = currentCoroutineContext()
        coroutineContext.ensureActive()
        if (!mutex.tryLock()) return false

        return try {
            coroutineContext.ensureActive()
            block()
            true
        } finally {
            mutex.unlock()
        }
    }
}
