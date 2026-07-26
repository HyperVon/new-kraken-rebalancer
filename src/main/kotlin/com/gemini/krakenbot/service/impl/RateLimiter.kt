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

    // Mutex is held across [delay] so waiters serialize and the counter cannot race while sleeping.
    open suspend fun acquireWithCost(cost: Double): Double = mutex.withLock {
        val now = clock()
        val elapsedSeconds = (now - lastUpdateTimeMs) / 1000.0

        callCounter = maxOf(0.0, callCounter - (elapsedSeconds * decayRate))
        lastUpdateTimeMs = now

        if (callCounter + cost > safeLimit) {
            val neededDecay = (callCounter + cost) - safeLimit
            val waitSeconds = neededDecay / decayRate
            val waitMs = (waitSeconds * 1000).roundToLong()
            if (waitMs > 0) {
                delay(waitMs.milliseconds)
                // Wait fully decays room for this call when waitMs > 0; += cost then lands at safeLimit.
                callCounter = safeLimit - cost
                lastUpdateTimeMs = clock()
            }
        }

        callCounter += cost

        return callCounter
    }

    suspend fun getCurrentCounter(): Double = mutex.withLock {
        val now = clock()
        val lastUpdate = lastUpdateTimeMs
        val elapsedSeconds = (now - lastUpdate) / 1000.0
        return maxOf(0.0, callCounter - (elapsedSeconds * decayRate))
    }

    suspend fun reset() = mutex.withLock {
        callCounter = 0.0
        lastUpdateTimeMs = clock()
    }
}
