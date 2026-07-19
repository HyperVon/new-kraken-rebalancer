package com.gemini.krakenbot.model

import com.fasterxml.jackson.annotation.JsonValue

@JvmInline
value class Asset(@get:JsonValue val value: String) {
    override fun toString(): String = value

    val krakenTicker: String
        get() = toKrakenTicker(value)

    val tradingPair: String
        get() = tradingPair(value)

    val isUsd: Boolean
        get() = isUsdSymbol(value)

    companion object {
        const val USD = "USD"
        const val BTC = "BTC"
        const val ETH = "ETH"
        const val DOGE = "DOGE"

        const val XBT = "XBT"
        const val XDG = "XDG"

        private val KRAKEN_TICKER_BY_SYMBOL = mapOf(
            BTC to XBT,
            DOGE to XDG
        )

        private val FALLBACK_SYMBOLS = listOf(BTC, ETH, DOGE)

        operator fun invoke(value: String): Asset = Asset(value)

        fun toKrakenTicker(symbol: String): String {
            val normalizedSymbol = normalizedSymbol(symbol)
            return KRAKEN_TICKER_BY_SYMBOL[normalizedSymbol] ?: normalizedSymbol
        }

        fun tradingPair(symbol: String): String =
            "${toKrakenTicker(symbol)}$USD"

        val BTC_USD_PAIR: String = tradingPair(BTC)

        fun fromTradingPair(pair: String, allocations: List<String>): String? {
            val normalizedPair = pair.uppercase()

            return allocations
                .filterNot(::isUsdSymbol)
                .firstOrNull { symbol -> matchesTradingPair(normalizedPair, symbol) }
                ?: allocations
                    .firstOrNull { symbol -> isUsdSymbol(symbol) && matchesTradingPair(normalizedPair, symbol) }
                ?: FALLBACK_SYMBOLS
                    .firstOrNull { symbol -> matchesTradingPair(normalizedPair, symbol) }
        }

        private fun normalizedSymbol(symbol: String): String =
            symbol.uppercase()

        private fun isUsdSymbol(symbol: String): Boolean =
            symbol.equals(USD, ignoreCase = true)

        private fun matchesTradingPair(normalizedPair: String, symbol: String): Boolean {
            val normalizedSymbol = normalizedSymbol(symbol)
            val krakenTicker = toKrakenTicker(normalizedSymbol)
            return normalizedPair.startsWith(krakenTicker) ||
                    normalizedPair.startsWith(normalizedSymbol) ||
                    normalizedPair == "${krakenTicker}USD" ||
                    normalizedPair == "${normalizedSymbol}USD" ||
                    normalizedPair == "X${krakenTicker}ZUSD" ||
                    normalizedPair == "X${normalizedSymbol}ZUSD"
        }
    }
}
