package com.gemini.krakenbot.util

/**
 * Maps user-facing allocation symbols to Kraken asset codes and USD trading pair names.
 */
object KrakenSymbols {
    const val USD = "USD"
    const val BTC = "BTC"
    const val ETH = "ETH"
    const val DOGE = "DOGE"

    const val XBT = "XBT"
    const val XDG = "XDG"

    const val HMAC_SHA512 = "HmacSHA512"
    const val SHA_256 = "SHA-256"

    fun toKrakenTicker(symbol: String): String = when (symbol.uppercase()) {
        BTC -> XBT
        DOGE -> XDG
        else -> symbol.uppercase()
    }

    /** Kraken REST pair name used for ticker queries and AddOrder (e.g. XBTUSD). */
    fun tradingPair(symbol: String): String = "${toKrakenTicker(symbol)}$USD"

    val BTC_USD_PAIR: String = tradingPair(BTC)
}
