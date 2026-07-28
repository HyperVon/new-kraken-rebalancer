package com.gemini.krakenbot.service.impl

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.roundToLong
import kotlin.time.Duration.Companion.milliseconds

/**
 * Coroutine-safe Kraken call-counter limiter: counter decays linearly at [decayRate]/sec
 * (default 0.33) and blocks until `counter + cost ≤ [safeLimit]` (default 12, Intermediate tier).
 *
 * The mutex is **not** held across [delay] so other callers are not head-of-line
 * blocked while one waiter sleeps (CQ-7-L1).
 *
 * @param clock Millisecond epoch supplier (injectable for deterministic tests).
 * Open for test subclasses that record acquire costs without MockK.
 */
open class RateLimiter(
    private val safeLimit: Double = 12.0,
    private val decayRate: Double = 0.33,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val mutex = Mutex()

    // Guarded by [mutex] — every read and write must happen inside `mutex.withLock`.
    private var callCounter: Double = 0.0
    private var lastUpdateTimeMs: Long = clock()

    open suspend fun acquireWithCost(cost: Double): Double {
        while (true) {
            var waitMs = 0L
            var acquired: Double? = null
            mutex.withLock {
                val now = clock()
                val elapsedSeconds = (now - lastUpdateTimeMs).coerceAtLeast(0L) / 1000.0
                callCounter = maxOf(0.0, callCounter - (elapsedSeconds * decayRate))
                // Keep the internal baseline monotonic when the wall clock moves backward.
                lastUpdateTimeMs = maxOf(lastUpdateTimeMs, now)

                if (callCounter + cost > safeLimit) {
                    val neededDecay = (callCounter + cost) - safeLimit
                    // At least 1ms so a rounded-down wait cannot busy-spin under the lock.
                    waitMs = (neededDecay / decayRate * 1000).roundToLong().coerceAtLeast(1L)
                } else {
                    callCounter += cost
                    acquired = callCounter
                }
            }
            if (acquired != null) {
                return acquired
            }
            // Delay outside the lock so other acquires can progress (CQ-7-L1).
            if (waitMs > 0) {
                delay(waitMs.milliseconds)
            }
        }
    }

    suspend fun getCurrentCounter(): Double = mutex.withLock {
        val now = clock()
        val lastUpdate = lastUpdateTimeMs
        val elapsedSeconds = (now - lastUpdate).coerceAtLeast(0L) / 1000.0
        return maxOf(0.0, callCounter - (elapsedSeconds * decayRate))
    }

    suspend fun reset() = mutex.withLock {
        callCounter = 0.0
        lastUpdateTimeMs = clock()
    }
}
