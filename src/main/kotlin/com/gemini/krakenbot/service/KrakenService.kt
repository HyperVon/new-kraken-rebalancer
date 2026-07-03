package com.gemini.krakenbot.service

import com.gemini.krakenbot.model.OrderResult
import com.gemini.krakenbot.model.TradeRecord
import java.math.BigDecimal

typealias RawBalances = Map<String, BigDecimal>
typealias RawPrices = Map<String, BigDecimal>

interface KrakenService {
    suspend fun getBalances(): RawBalances
    suspend fun getTickerPrices(pairs: String): RawPrices
    suspend fun executeOrder(
        pair: String,
        type: String,
        side: String,
        volume: BigDecimal
    ): OrderResult
    suspend fun getTradeHistory(startSec: Long? = null, offset: Int? = null): List<TradeRecord>
}
