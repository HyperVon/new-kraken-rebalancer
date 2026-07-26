package com.gemini.krakenbot.model

/** Order direction; [apiValue] is lowercase Kraken REST, [name] is the stored/UI form. */
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

/** Only MARKET is wired through live and emulator executeOrder paths. */
enum class OrderType(val apiValue: String) {
    MARKET("market"),
}
