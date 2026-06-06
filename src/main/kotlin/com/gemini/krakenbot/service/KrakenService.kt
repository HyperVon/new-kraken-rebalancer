package com.gemini.krakenbot.service

import com.gemini.krakenbot.model.OrderResult
import java.math.BigDecimal

typealias RawBalances = Map<String, Double>
typealias RawPrices = Map<String, Double>

interface KrakenService {
    suspend fun getBalances(): RawBalances
    suspend fun getTickerPrices(pairs: String): RawPrices
    suspend fun executeOrder(
        pair: String,
        type: String,
        side: String,
        volume: BigDecimal
    ): OrderResult
}
