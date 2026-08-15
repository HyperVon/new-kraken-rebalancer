package com.gemini.krakenbot.service

import com.gemini.krakenbot.domain.OrderResult
import com.gemini.krakenbot.domain.RawBalances
import com.gemini.krakenbot.domain.RawPrices
import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.model.TradeRecord
import java.math.BigDecimal

interface KrakenService {
    suspend fun getBalances(): RawBalances

    suspend fun getTickerPrices(pairs: String): RawPrices

    /**
     * @param dryRun when non-null, overrides the current config so a mid-cycle settings flip
     * cannot change dry-run vs live placement for this order.
     * @param clOrdId optional Kraken `cl_ord_id` (client order id). When set, Kraken enforces
     * uniqueness among the client's *open* orders — mutually exclusive with `userref`.
     */
    suspend fun executeOrder(
        pair: String,
        type: String,
        side: String,
        volume: BigDecimal,
        dryRun: Boolean? = null,
        clOrdId: String? = null,
    ): OrderResult

    suspend fun getTradeHistory(startSec: Long? = null, offset: Int? = null): List<TradeRecord>

    suspend fun getOHLC(pair: String, interval: Int = 1440, since: Long? = null): List<Pair<Long, BigDecimal>>

    /**
     * Total trade count from the last [getTradeHistory] response (Kraken `count` / sim total).
     * Used for sync progress pagination metadata without downcasting the port.
     */
    fun getLastTradeHistoryTotalCount(): Int = 0

    /**
     * Ledger entries (staking rewards, dividends) in [startSec, endSec] starting at [offset],
     * optionally filtered to [types] (e.g. `staking`). Defaults to empty for backends
     * without ledger support (simulation, test fakes).
     */
    suspend fun getLedgers(
        startSec: Long? = null,
        offset: Int? = null,
        endSec: Long? = null,
        types: Set<String>? = null,
    ): List<LedgerEvent> = emptyList()

    /** Total ledger entry count from the last [getLedgers] response (Kraken `count`). */
    fun getLastLedgerTotalCount(): Int = 0

    /** Current private-API call-counter load; 0 for backends without a rate limiter. */
    suspend fun getApiCallCounter(): Double = 0.0

    /**
     * Runs [block] with a stable backend selection passed as the argument.
     * Default passes `this`. `DynamicKrakenService` pins live vs simulation in
     * the coroutine context at top-level entry; nested calls reuse the outer pin
     * so a mid-cycle `simulation` flip cannot mix backends. Concurrent top-level
     * calls each capture their own entry-time backend.
     */
    suspend fun <T> withStableBackend(block: suspend (KrakenService) -> T): T = block(this)
}

/** Optional capability for backends that can pass an inclusive TradesHistory end bound. */
interface BoundedTradeHistoryService {
    suspend fun getTradeHistoryUntil(startSec: Long?, offset: Int?, endSec: Long?): List<TradeRecord>
}

/** Uses the stable-bound capability when available and preserves two-argument test fakes otherwise. */
suspend fun KrakenService.getTradeHistoryUntil(startSec: Long?, offset: Int?, endSec: Long?): List<TradeRecord> =
    (this as? BoundedTradeHistoryService)?.getTradeHistoryUntil(startSec, offset, endSec)
        ?: getTradeHistory(startSec, offset)
