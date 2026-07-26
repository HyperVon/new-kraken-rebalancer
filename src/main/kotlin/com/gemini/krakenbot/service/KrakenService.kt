package com.gemini.krakenbot.service

import com.gemini.krakenbot.model.OrderResult
import com.gemini.krakenbot.model.TradeRecord
import java.math.BigDecimal

typealias RawBalances = Map<String, BigDecimal>
typealias RawPrices = Map<String, BigDecimal>

interface KrakenService {
    suspend fun getBalances(): RawBalances

    suspend fun getTickerPrices(pairs: String): RawPrices

    /**
     * @param dryRun when non-null, overrides the current config so a mid-cycle settings flip
     * cannot change dry-run vs live placement for this order.
     */
    suspend fun executeOrder(
        pair: String,
        type: String,
        side: String,
        volume: BigDecimal,
        dryRun: Boolean? = null,
        userref: Int? = null,
    ): OrderResult

    suspend fun getTradeHistory(startSec: Long? = null, offset: Int? = null): List<TradeRecord>

    suspend fun getOHLC(pair: String, interval: Int = 1440, since: Long? = null): List<Pair<Long, BigDecimal>>

    /**
     * Total trade count from the last [getTradeHistory] response (Kraken `count` / sim total).
     * Used for sync progress pagination metadata without downcasting the port.
     */
    fun getLastTradeHistoryTotalCount(): Int = 0

    /** Current private-API call-counter load; 0 for backends without a rate limiter. */
    suspend fun getApiCallCounter(): Double = 0.0

    /**
     * Runs [block] with a stable backend selection passed as the argument.
     * Default passes `this`. [DynamicKrakenService] pins live vs simulation in the
     * coroutine context at top-level entry; nested calls reuse the outer pin so a
     * mid-cycle `simulation` flip cannot mix backends. Concurrent top-level calls
     * each capture their own entry-time backend.
     */
    suspend fun <T> withStableBackend(block: suspend (KrakenService) -> T): T = block(this)
}
