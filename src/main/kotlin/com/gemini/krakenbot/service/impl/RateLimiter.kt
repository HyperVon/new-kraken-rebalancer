package com.gemini.krakenbot.service.impl

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds

/**
 * Thread-safe rate limiter for API calls using exponential decay.
 * Implements Kraken's call counter algorithm with configurable costs per endpoint.
 */
class RateLimiter(
    private val safeLimit: Double = 12.0,
    private val decayRate: Double = 0.33
) {
    private val callCounter = AtomicLong(0)
    private val lastUpdateTimeMs = AtomicLong(System.currentTimeMillis())

    private val _rateLimitState = MutableSharedFlow<RateLimitEvent>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    fun getRateLimitFlow(): Flow<RateLimitEvent> = _rateLimitState.asSharedFlow()

    suspend fun acquireWithCost(cost: Double): Long {
        val now = System.currentTimeMillis()
        val lastUpdate = lastUpdateTimeMs.get()
        val elapsedSeconds = (now - lastUpdate) / 1000.0

        // Apply decay to counter
        val decayedCounter = (callCounter.get() / 1000.0) - (elapsedSeconds * decayRate)
        val adjustedCounter = maxOf(0.0, decayedCounter * 1000.0).toLong()
        callCounter.set(adjustedCounter)
        lastUpdateTimeMs.set(now)

        // Calculate wait time if needed
        val currentCounter = callCounter.get() / 1000.0
        if (currentCounter + cost > safeLimit) {
            val neededDecay = (currentCounter + cost) - safeLimit
            val waitSeconds = neededDecay / decayRate
            val waitMs = (waitSeconds * 1000).toLong()
            if (waitMs > 0) {
                _rateLimitState.emit(RateLimitExceeded(waitMs))
                delay(waitMs.milliseconds)
                callCounter.set(((safeLimit - cost) * 1000).toLong())
                lastUpdateTimeMs.set(System.currentTimeMillis())
            }
        }

        // Increment counter with cost
        callCounter.addAndGet((cost * 1000).toLong())
        _rateLimitState.emit(RateLimitAcquired(cost))
        return callCounter.get()
    }

    fun getCurrentCounter(): Double = callCounter.get() / 1000.0

    fun reset() {
        callCounter.set(0)
        lastUpdateTimeMs.set(System.currentTimeMillis())
    }
}

sealed class RateLimitEvent
data class RateLimitExceeded(val waitMs: Long) : RateLimitEvent()
data class RateLimitAcquired(val cost: Double) : RateLimitEvent()
