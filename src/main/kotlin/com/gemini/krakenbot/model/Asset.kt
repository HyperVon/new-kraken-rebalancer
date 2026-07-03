package com.gemini.krakenbot.model

import com.fasterxml.jackson.annotation.JsonValue

@JvmInline
value class Asset(@get:JsonValue val value: String) {
    override fun toString(): String = value

    val krakenTicker: String
        get() = when (value.uppercase()) {
            BTC -> XBT
            DOGE -> XDG
            else -> value.uppercase()
        }

    val tradingPair: String
        get() = "${krakenTicker}$USD"

    val isUsd: Boolean
        get() = value.equals(USD, ignoreCase = true)

    companion object {
        const val USD = "USD"
        const val BTC = "BTC"
        const val ETH = "ETH"
        const val DOGE = "DOGE"

        const val XBT = "XBT"
        const val XDG = "XDG"

        operator fun invoke(value: String): Asset = Asset(value)

        fun toKrakenTicker(symbol: String): String = when (symbol.uppercase()) {
            BTC -> XBT
            DOGE -> XDG
            else -> symbol.uppercase()
        }

        fun tradingPair(symbol: String): String =
            "${toKrakenTicker(symbol)}$USD"

        val BTC_USD_PAIR: String = tradingPair(BTC)

        fun fromTradingPair(pair: String, allocations: List<String>): String? {
            val normalizedPair = pair.uppercase()
            for (symbol in allocations) {
                val ticker = toKrakenTicker(symbol)
                if (normalizedPair.contains(ticker) || normalizedPair.contains(symbol.uppercase())) {
                    return symbol
                }
            }
            // Fallbacks
            if (normalizedPair.contains("XBT") || normalizedPair.contains("BTC")) return "BTC"
            if (normalizedPair.contains("ETH")) return "ETH"
            if (normalizedPair.contains("XDG") || normalizedPair.contains("DOGE")) return "DOGE"
            return null
        }
    }
}
