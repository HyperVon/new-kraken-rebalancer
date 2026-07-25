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
    ): OrderResult

    suspend fun getTradeHistory(startSec: Long? = null, offset: Int? = null): List<TradeRecord>

    suspend fun getOHLC(pair: String, interval: Int = 1440, since: Long? = null): List<Pair<Long, BigDecimal>>

    /**
     * Runs [block] with a stable backend selection passed as the receiver argument.
     * Default passes `this`. [DynamicKrakenService] resolves live vs simulation once at entry
     * so concurrent cycles cannot share a process-global pin.
     */
    suspend fun <T> withStableBackend(block: suspend (KrakenService) -> T): T = block(this)
}
