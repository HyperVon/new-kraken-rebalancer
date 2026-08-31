package com.gemini.krakenbot.service.impl

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.roundToLong
import kotlin.time.Duration.Companion.milliseconds

/**
 * Coroutine-safe Kraken call-counter limiter: counter decays linearly at [decayRate]/sec
 * (default 0.5) and blocks until `counter + cost ≤ [safeLimit]` (default 20, standard account).
 *
 * The mutex is **not** held across [delay] so other callers are not head-of-line
 * blocked while one waiter sleeps (CQ-7-L1).
 *
 * @param clock Millisecond epoch supplier (injectable for deterministic tests).
 * Open for test subclasses that record acquire costs without MockK.
 */
open class RateLimiter(
    private val safeLimit: Double = 20.0,
    private val decayRate: Double = 0.5,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val mutex = Mutex()

    // Guarded by [mutex] — every read and write must happen inside `mutex.withLock`.
    private var callCounter: Double = 0.0
    private var lastUpdateTimeMs: Long = clock()

    /**
     * Blocks until `callCounter + cost ≤ safeLimit`, then charges [cost] and returns the new counter.
     * The wait uses linear decay (`decayRate`/sec) and releases the mutex during the delay.
     */
    open suspend fun acquireWithCost(cost: Double): Double {
        require(cost > 0.0) { "Cost must be strictly positive: $cost" }
        require(cost <= safeLimit) { "Requested cost $cost exceeds safeLimit $safeLimit" }
        while (true) {
            var waitMs = 0L
            var acquired: Double? = null
            mutex.withLock {
                val now = clock()
                val rawElapsedMs = now - lastUpdateTimeMs
                // Forward NTP jump would otherwise decay to 0 and permit a burst; cap forward
                // decay to at most one safeLimit fill (safeLimit/decayRate seconds) and treat
                // backward jumps as 0.
                val elapsedMs = rawElapsedMs.coerceIn(0L, (safeLimit / decayRate * 1000).toLong())
                val elapsedSeconds = elapsedMs / 1000.0
                callCounter = maxOf(0.0, callCounter - (elapsedSeconds * decayRate))
                // Keep the internal baseline monotonic when the wall clock moves backward.
                lastUpdateTimeMs = if (rawElapsedMs < 0) lastUpdateTimeMs else now

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

    /** Returns the decayed [callCounter] without charging — linear decay since last update. */
    suspend fun getCurrentCounter(): Double = mutex.withLock {
        val now = clock()
        val rawElapsedMs = now - lastUpdateTimeMs
        val elapsedMs = rawElapsedMs.coerceIn(0L, (safeLimit / decayRate * 1000).toLong())
        val elapsedSeconds = elapsedMs / 1000.0
        return maxOf(0.0, callCounter - (elapsedSeconds * decayRate))
    }

    /** Resets the counter to zero and re-baselines the decay clock to `clock()`. */
    suspend fun reset() = mutex.withLock {
        callCounter = 0.0
        lastUpdateTimeMs = clock()
    }
}
