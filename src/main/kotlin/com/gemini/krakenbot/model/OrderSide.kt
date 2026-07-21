package com.gemini.krakenbot.model

/** Represents the trading direction for order executions. */
enum class OrderSide(val apiValue: String) {
    BUY("buy"),
    SELL("sell");

    val uppercaseName: String get() = name

    companion object {
        fun fromString(value: String): OrderSide =
            entries.firstOrNull { it.apiValue.equals(value, ignoreCase = true) || it.name.equals(value, ignoreCase = true) }
                ?: BUY
    }
}

/** Represents the order execution type for Kraken API. */
enum class OrderType(val apiValue: String) {
    MARKET("market");

    companion object {
        fun fromString(value: String): OrderType =
            entries.firstOrNull { it.apiValue.equals(value, ignoreCase = true) || it.name.equals(value, ignoreCase = true) }
                ?: MARKET
    }
}
