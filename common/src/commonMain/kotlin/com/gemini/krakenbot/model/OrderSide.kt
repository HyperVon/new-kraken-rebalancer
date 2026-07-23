package com.gemini.krakenbot.model

/** Represents the trading direction for order executions. */
enum class OrderSide(val apiValue: String) {
    BUY("buy"),
    SELL("sell");

    val uppercaseName: String get() = name
}

/** Represents the order execution type for Kraken API. */
enum class OrderType(val apiValue: String) {
    MARKET("market");
}
