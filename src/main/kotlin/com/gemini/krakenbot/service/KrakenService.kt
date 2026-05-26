package com.gemini.krakenbot.service

interface KrakenService {
    suspend fun getBalances(): Map<String, Double>
    suspend fun getTickerPrices(pairs: String): Map<String, Double>
    suspend fun executeOrder(pair: String, type: String, side: String, volume: Double)
}
