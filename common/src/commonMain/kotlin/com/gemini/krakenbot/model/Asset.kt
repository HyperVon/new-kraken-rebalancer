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
        private val KRAKEN_TICKER_BY_SYMBOL = mapOf(
            BTC to XBT,
            DOGE to XDG,
        )

        // Keep allocation symbols on the application side of Kraken's ticker aliases.
        private val CANONICAL_SYMBOL_BY_ALIAS = mapOf(
            XBT to BTC,
            XDG to DOGE,
        )

        private val FALLBACK_SYMBOLS = listOf(BTC, ETH, DOGE)

        operator fun invoke(value: String): Asset = Asset(value)

        /** BTC→XBT, DOGE→XDG; other symbols pass through uppercased. */
        fun toKrakenTicker(symbol: String): String {
            val normalizedSymbol = normalizedSymbol(symbol)
            return KRAKEN_TICKER_BY_SYMBOL[normalizedSymbol] ?: normalizedSymbol
        }

        /** Uppercase a symbol and map Kraken ticker aliases to the application symbol. */
        fun canonicalSymbol(symbol: String): String {
            val normalizedSymbol = normalizedSymbol(symbol)
            return CANONICAL_SYMBOL_BY_ALIAS[normalizedSymbol] ?: normalizedSymbol
        }

        fun tradingPair(symbol: String): String = "${toKrakenTicker(symbol)}$USD"

        val BTC_USD_PAIR: String = tradingPair(BTC)
        val ETH_USD_PAIR: String = tradingPair(ETH)

        /**
         * Map an exchange pair string to an allocation symbol.
         * Tries non-USD allocations, then USD, then [FALLBACK_SYMBOLS]; null if nothing matches.
         */
        fun fromTradingPair(pair: String, allocations: List<String>): String? {
            val normalizedPair = pair.uppercase()

            return allocations
                .filterNot(::isUsdSymbol)
                .firstOrNull { symbol -> matchesTradingPair(normalizedPair, symbol) }
                ?.let(::canonicalSymbol)
                ?: allocations
                    .firstOrNull { symbol -> isUsdSymbol(symbol) && matchesTradingPair(normalizedPair, symbol) }
                    ?.let(::canonicalSymbol)
                ?: FALLBACK_SYMBOLS
                    .firstOrNull { symbol -> matchesTradingPair(normalizedPair, symbol) }
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

        /** Assets the application can track; ledger entries for anything else are stored raw. */
        private val KNOWN_LEDGER_ASSETS =
            setOf(USD, BTC, ETH, DOGE, SOL, USDT, USDC, ADA, XRP, DOT, LINK, LTC, XBT, XDG)

        /**
         * Earn-migration suffixes: Kraken balances/ledger entries use e.g. `DOT.S`, `USDT.F` for
         * read-only yield-bearing assets; the base asset remains the transactable one.
         */
        private val EARN_ASSET_SUFFIXES = listOf(".S", ".M", ".F", ".B")

        /**
         * Normalize a Kraken Ledgers asset code to the application symbol: strips Earn-migration
         * suffixes (`DOT.S` → `DOT`) and legacy X/Z prefixes (`XXBT` → `BTC`, `ZUSD` → `USD`).
         * Unknown or foreign assets (e.g. `ZGBP`) pass through unchanged so they are never
         * mis-attributed to a tracked symbol.
         */
        fun normalizeLedgerAsset(asset: String): String {
            val upper = asset.uppercase()
            EARN_ASSET_SUFFIXES.firstOrNull(upper::endsWith)?.let { suffix ->
                val base = upper.removeSuffix(suffix)
                if (base in KNOWN_LEDGER_ASSETS) return canonicalSymbol(base)
            }
            if (upper.length > 1 && (upper[0] == 'X' || upper[0] == 'Z')) {
                val stripped = upper.substring(1)
                if (stripped in KNOWN_LEDGER_ASSETS) return canonicalSymbol(stripped)
            }
            return CANONICAL_SYMBOL_BY_ALIAS[upper] ?: upper
        }
    }
}
