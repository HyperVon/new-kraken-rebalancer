package com.gemini.krakenbot.kraken

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import kotlin.time.Duration.Companion.milliseconds

class KrakenApiExample(
    private val apiKey: String,
    private val apiSecret: String
) {
    private val log = LoggerFactory.getLogger(KrakenApiExample::class.java)

    // Mirrors service/impl/RateLimiter.kt: a linearly-decaying private call
    // counter, NOT a mutex held across the whole call. safeLimit (20.0) is the
    // standard account-counter ceiling; decayRate (0.5) is points/sec. The mutex
    // guards counter updates only and is released before delay() so waiters do
    // not HOL-block one another (CQ-7-L1).
    private val rateLimiterMutex = Mutex()
    private var callCounter = 0.0
    private var lastUpdateTimeMs = System.currentTimeMillis()
    private val safeLimit = 20.0
    private val decayRate = 0.5

    suspend fun getAccountBalances(): Map<String, BigDecimal> {
        acquireCost(cost = 1.0)
        log.info("Fetching private account balances from Kraken API...")

        // Map raw Kraken API symbols to normalized display symbols
        val rawBalances = fetchRawBalancesFromKraken()

        return rawBalances.mapKeys { (symbol, _) ->
            when (symbol) {
                "XXBT", "XBT" -> "BTC"
                "XXDG", "XDG", "DOGE" -> "DOGE"
                "ZUSD", "USD" -> "USD"
                else -> symbol
            }
        }
    }

    private suspend fun acquireCost(cost: Double) {
        while (true) {
            var waitMs = 0L
            rateLimiterMutex.withLock {
                val now = System.currentTimeMillis()
                val rawElapsedMs = now - lastUpdateTimeMs
                val elapsedMs = rawElapsedMs.coerceIn(0L, (safeLimit / decayRate * 1000).toLong())
                val elapsedSeconds = elapsedMs / 1000.0
                callCounter = maxOf(0.0, callCounter - (elapsedSeconds * decayRate))
                lastUpdateTimeMs = if (rawElapsedMs < 0) lastUpdateTimeMs else now
                if (callCounter + cost > safeLimit) {
                    val neededDecay = (callCounter + cost) - safeLimit
                    waitMs = (neededDecay / decayRate * 1000).toLong().coerceAtLeast(1L)
                } else {
                    callCounter += cost
                    return
                }
            }
            if (waitMs > 0) {
                delay(waitMs.milliseconds)
            }
        }
    }

    private suspend fun fetchRawBalancesFromKraken(): Map<String, BigDecimal> {
        // Simulated rate-limited API call
        delay(100)
        return mapOf(
            "XXBT" to BigDecimal("0.75000000"),
            "ZUSD" to BigDecimal("2500.50")
        )
    }
}
