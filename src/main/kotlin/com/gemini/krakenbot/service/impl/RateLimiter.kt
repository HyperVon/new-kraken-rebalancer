package com.gemini.krakenbot.service.impl

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.roundToLong
import kotlin.time.Duration.Companion.milliseconds

/**
 * Thread-safe rate limiter for API calls using exponential decay.
 * Implements Kraken's call counter algorithm with configurable costs per endpoint.
 */
class RateLimiter(
    private val safeLimit: Double = 12.0,
    private val decayRate: Double = 0.33
) {
    private val mutex = Mutex()
    @Volatile
    private var callCounter: Double = 0.0
    @Volatile
    private var lastUpdateTimeMs: Long = System.currentTimeMillis()

    suspend fun acquireWithCost(cost: Double): Double = mutex.withLock {
        val now = System.currentTimeMillis()
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
                lastUpdateTimeMs = System.currentTimeMillis()
            }
        }

        // Increment counter with cost
        callCounter += cost

        return callCounter
    }

    fun getCurrentCounter(): Double {
        val now = System.currentTimeMillis()
        val lastUpdate = lastUpdateTimeMs
        val elapsedSeconds = (now - lastUpdate) / 1000.0
        return maxOf(0.0, callCounter - (elapsedSeconds * decayRate))
    }

    fun reset() {
        callCounter = 0.0
        lastUpdateTimeMs = System.currentTimeMillis()
    }
}
