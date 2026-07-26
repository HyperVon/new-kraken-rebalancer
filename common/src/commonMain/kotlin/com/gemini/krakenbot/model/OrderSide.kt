package com.gemini.krakenbot.model

/** Represents the trading direction for order executions. */
enum class OrderSide(val apiValue: String) {
    BUY("buy"),
    SELL("sell"),
    ;

    val uppercaseName: String get() = name

    companion object {
        /** Canonical stored form (`BUY` / `SELL`). Unknown values are uppercased as-is. */
        fun normalize(side: String): String = side.uppercase()

        fun isBuy(side: String): Boolean = normalize(side) == BUY.name

        fun isSell(side: String): Boolean = normalize(side) == SELL.name
    }
}

/** Represents the order execution type for Kraken API. */
enum class OrderType(val apiValue: String) {
    MARKET("market"),
}
