package com.gemini.krakenbot.service

import com.gemini.krakenbot.model.OrderResult
import java.math.BigDecimal

interface KrakenService {
    suspend fun getBalances(): Map<String, Double>
    suspend fun getTickerPrices(pairs: String): Map<String, Double>
    suspend fun executeOrder(
        pair: String,
        type: String,
        side: String,
        volume: BigDecimal
    ): OrderResult
}
