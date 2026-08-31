package com.gemini.krakenbot.service.impl

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.milliseconds

/** Conservative IP-scoped limiter for Kraken Spot public endpoints. */
open class PublicRateLimiter(
    private val minIntervalMs: Long = 1_000L,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val mutex = Mutex()
    private var lastRequestAtMs: Long? = null

    open suspend fun acquire() {
        while (true) {
            val waitMs = mutex.withLock {
                val now = clock()
                val last = lastRequestAtMs
                val remaining = if (last == null) 0L else minIntervalMs - (now - last)
                if (remaining <= 0L) {
                    lastRequestAtMs = now
                    0L
                } else {
                    remaining
                }
            }
            if (waitMs == 0L) return
            delay(waitMs.milliseconds)
        }
    }
}
