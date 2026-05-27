package com.gemini.krakenbot.util

/**
 * Maps user-facing allocation symbols to Kraken asset codes and USD trading pair names.
 */
object KrakenSymbols {

    fun toKrakenTicker(symbol: String): String = when (symbol.uppercase()) {
        "BTC" -> "XBT"
        "DOGE" -> "XDG"
        else -> symbol.uppercase()
    }

    /** Kraken REST pair name used for ticker queries and AddOrder (e.g. XBTUSD). */
    fun tradingPair(symbol: String): String = "${toKrakenTicker(symbol)}USD"
}
