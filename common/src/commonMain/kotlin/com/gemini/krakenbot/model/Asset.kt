package com.gemini.krakenbot.model

import kotlin.jvm.JvmInline

@JvmInline
value class Asset(val value: String) {
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
        const val SOL = "SOL"
        const val USDT = "USDT"
        const val USDC = "USDC"
        const val ADA = "ADA"
        const val XRP = "XRP"
        const val DOT = "DOT"
        const val LINK = "LINK"
        const val LTC = "LTC"

        const val XBT = "XBT"
        const val XDG = "XDG"

        val ASSET_USD = Asset(USD)
        val ASSET_BTC = Asset(BTC)
        val ASSET_ETH = Asset(ETH)
        val ASSET_DOGE = Asset(DOGE)
        val ASSET_SOL = Asset(SOL)

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
        val ETH_USD_PAIR: String = tradingPair(ETH)
        val DOGE_USD_PAIR: String = tradingPair(DOGE)
        val SOL_USD_PAIR: String = tradingPair(SOL)

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
