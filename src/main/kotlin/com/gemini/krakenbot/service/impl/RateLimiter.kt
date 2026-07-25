package com.gemini.krakenbot.service.impl

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.roundToLong
import kotlin.time.Duration.Companion.milliseconds

/**
 * Thread-safe rate limiter for API calls using exponential decay.
 * Implements Kraken's call counter algorithm with configurable costs per endpoint.
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

    @Volatile
    private var callCounter: Double = 0.0

    @Volatile
    private var lastUpdateTimeMs: Long = clock()

    open suspend fun acquireWithCost(cost: Double): Double = mutex.withLock {
        val now = clock()
        val elapsedSeconds = (now - lastUpdateTimeMs) / 1000.0

        // Apply decay to counter
        callCounter = maxOf(0.0, callCounter - (elapsedSeconds * decayRate))
        lastUpdateTimeMs = now

        // Calculate wait time if needed
        if (callCounter + cost > safeLimit) {
            val neededDecay = (callCounter + cost) - safeLimit
            val waitSeconds = neededDecay / decayRate
            val waitMs = (waitSeconds * 1000).roundToLong()
            if (waitMs > 0) {
                delay(waitMs.milliseconds)
                callCounter = safeLimit - cost
                lastUpdateTimeMs = clock()
            }
        }

        // Increment counter with cost
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
