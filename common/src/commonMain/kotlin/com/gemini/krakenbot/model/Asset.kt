package com.gemini.krakenbot.model

import kotlin.jvm.JvmInline

/** Portfolio symbol wrapper; Kraken ticker/pair/balance-key aliases live here for JVM + JS. */
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

        /** Allocation-symbol format gate shared by JVM config validation and the JS settings form. */
        const val SYMBOL_PATTERN_STRING = "^[A-Z0-9]{1,16}$"

        // Kraken's own ticker codes differ from common symbols for these two.
        operator fun invoke(value: String): Asset = Asset(value)

        /** BTC→XBT, DOGE→XDG; other symbols pass through uppercased. */
        fun toKrakenTicker(symbol: String): String {
            val normalizedSymbol = normalizedSymbol(symbol)
            return KRAKEN_TICKER_BY_SYMBOL[normalizedSymbol] ?: normalizedSymbol
        }

        /** Uppercase a symbol and map Kraken ticker aliases to the application symbol. */
        fun canonicalSymbol(symbol: String): String {
            val normalizedSymbol = normalizedSymbol(symbol)
            return CANONICAL_BY_KRAKEN_ALIAS[normalizedSymbol] ?: normalizedSymbol
        }

        fun tradingPair(symbol: String): String = "${toKrakenTicker(symbol)}$USD"

        val BTC_USD_PAIR: String = tradingPair(BTC)
        val ETH_USD_PAIR: String = tradingPair(ETH)

        /**
         * Map an exchange pair string to an allocation symbol.
         * Tries non-USD allocations, then USD; if not in allocations, extracts and canonicalizes
         * the base symbol for any USD-quoted market pair (e.g. `SOLUSD` → `SOL`, `XXBTZUSD` → `BTC`).
         * Returns null for non-USD quoted pairs (e.g. `ADAEUR`, `XBTUSDT`).
         */
        fun fromTradingPair(pair: String, allocations: List<String>): String? {
            val normalizedPair = pair.trim().uppercase()
            if (normalizedPair.isEmpty()) return null

            // 1. Try matching against user's configured allocations first
            allocations
                .filterNot(::isUsdSymbol)
                .firstOrNull { symbol -> matchesTradingPair(normalizedPair, symbol) }
                ?.let { return canonicalSymbol(it) }

            allocations
                .firstOrNull { symbol -> isUsdSymbol(symbol) && matchesTradingPair(normalizedPair, symbol) }
                ?.let { return canonicalSymbol(it) }

            // 2. Generic USD-quoted market extraction: e.g. "XETHZUSD" -> "XETH", "SOLUSD" -> "SOL"
            val base = when {
                normalizedPair.endsWith("Z$USD") && normalizedPair.length > ("Z$USD").length ->
                    normalizedPair.removeSuffix("Z$USD")

                normalizedPair.endsWith(USD) && normalizedPair.length > USD.length ->
                    normalizedPair.removeSuffix(USD)

                else -> null
            } ?: return null

            val canonical = normalizeLedgerAsset(base)
            if (matchesTradingPair(normalizedPair, canonical)) {
                return canonical
            }
            return null
        }

        private fun normalizedSymbol(symbol: String): String = symbol.uppercase()

        private fun isUsdSymbol(symbol: String): Boolean = normalizeLedgerAsset(symbol).equals(USD, ignoreCase = true)

        private fun matchesTradingPair(normalizedPair: String, symbol: String): Boolean =
            normalizedPair in acceptedKrakenPairs(symbol)

        /**
         * Exact USD-quoted Kraken pair aliases accepted for [symbol] (e.g. BTC →
         * `XBTUSD`, `BTCUSD`, `XXBTZUSD`). Exact equality prevents prefix collisions
         * where e.g. a `XBTUSDT`/`XBTUSDC` quote could be mis-resolved to BTC.
         */
        fun acceptedUsdQuotedPairs(symbol: String): Set<String> {
            val normalizedSymbol = canonicalSymbol(symbol)
            val krakenTicker = toKrakenTicker(normalizedSymbol)
            return setOf(
                "$krakenTicker$USD",
                "$normalizedSymbol$USD",
                "X${krakenTicker}Z$USD",
                "X${normalizedSymbol}Z$USD",
            )
        }

        fun matchesUsdQuotedPair(pairKey: String, symbol: String): Boolean =
            pairKey.uppercase() in acceptedUsdQuotedPairs(symbol)

        private fun acceptedKrakenPairs(symbol: String): Set<String> = acceptedUsdQuotedPairs(symbol)

        /** Balance-map key candidates, including Kraken X/Z asset prefixes (e.g. XXBT, ZUSD). */
        fun possibleBalanceKeys(symbol: String): List<String> {
            val normalized = canonicalSymbol(symbol)
            val krakenTicker = toKrakenTicker(normalized)
            return listOf(
                symbol,
                normalized,
                "X$normalized",
                "Z$normalized",
                krakenTicker,
                "X$krakenTicker",
            ).distinct()
        }

        /**
         * Normalize a Kraken Ledgers asset code to the application symbol: strips Earn-migration
         * suffixes (`DOT.S` → `DOT`, `AVAX.S` → `AVAX`) and maps legacy Kraken ISO-4217 prefixes
         * (`XXBT` → `BTC`, `ZUSD` → `USD`, `XETH` → `ETH`).
         */
        fun normalizeLedgerAsset(asset: String): String {
            var upper = asset.trim().uppercase()
            if (upper.isEmpty()) return upper

            for (suffix in EARN_ASSET_SUFFIXES) {
                if (upper.endsWith(suffix) && upper.length > suffix.length) {
                    upper = upper.removeSuffix(suffix)
                    break
                }
            }
            return canonicalSymbol(upper)
        }
    }
}
