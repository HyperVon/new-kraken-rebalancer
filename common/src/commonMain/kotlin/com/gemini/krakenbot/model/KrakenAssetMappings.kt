package com.gemini.krakenbot.model

/** Kraken-specific ticker code for common symbols where they differ (BTC→XBT, DOGE→XDG). */
val KrakenAssetAliases.KRAKEN_TICKER_BY_SYMBOL: Map<String, String>
    get() = AssetAliasTables.KRAKEN_TICKER_BY_SYMBOL

/** Legacy Kraken ISO-4217 asset codes and ticker aliases mapped to canonical application symbols. */
val KrakenAssetAliases.CANONICAL_BY_KRAKEN_ALIAS: Map<String, String>
    get() = AssetAliasTables.CANONICAL_BY_KRAKEN_ALIAS

/**
 * Earn-migration suffixes: Kraken balances/ledger entries use e.g. `DOT.S`, `USDT.F` for
 * read-only yield-bearing assets; the base asset remains the transactable one.
 */
val KrakenAssetAliases.EARN_ASSET_SUFFIXES: List<String>
    get() = AssetAliasTables.EARN_ASSET_SUFFIXES

/**
 * Hoisted alias tables so hot loops (per parsed trade/balance key/pair) reuse a single
 * allocation instead of rebuilding the maps on every access. The symbol constants are
 * members of [KrakenAssetAliases], so they are qualified below.
 */
internal object AssetAliasTables {
    val KRAKEN_TICKER_BY_SYMBOL: Map<String, String> =
        mapOf(
            KrakenAssetAliases.BTC to KrakenAssetAliases.XBT,
            KrakenAssetAliases.DOGE to KrakenAssetAliases.XDG,
        )

    val CANONICAL_BY_KRAKEN_ALIAS: Map<String, String> =
        mapOf(
            KrakenAssetAliases.XBT to KrakenAssetAliases.BTC,
            KrakenAssetAliases.XXBT to KrakenAssetAliases.BTC,
            KrakenAssetAliases.XDG to KrakenAssetAliases.DOGE,
            KrakenAssetAliases.XXDG to KrakenAssetAliases.DOGE,
            KrakenAssetAliases.XETH to KrakenAssetAliases.ETH,
            KrakenAssetAliases.XLTC to KrakenAssetAliases.LTC,
            KrakenAssetAliases.XXRP to KrakenAssetAliases.XRP,
            KrakenAssetAliases.XXLM to KrakenAssetAliases.XLM,
            KrakenAssetAliases.XXMR to KrakenAssetAliases.XMR,
            KrakenAssetAliases.XZEC to KrakenAssetAliases.ZEC,
            KrakenAssetAliases.XETC to KrakenAssetAliases.ETC,
            KrakenAssetAliases.XREP to KrakenAssetAliases.REP,
            KrakenAssetAliases.XMLN to KrakenAssetAliases.MLN,
            KrakenAssetAliases.ZUSD to KrakenAssetAliases.USD,
            KrakenAssetAliases.ZEUR to KrakenAssetAliases.EUR,
            KrakenAssetAliases.ZCAD to KrakenAssetAliases.CAD,
            KrakenAssetAliases.ZGBP to KrakenAssetAliases.GBP,
            KrakenAssetAliases.ZJPY to KrakenAssetAliases.JPY,
            KrakenAssetAliases.ZCHF to KrakenAssetAliases.CHF,
            KrakenAssetAliases.ZAUD to KrakenAssetAliases.AUD,
        )

    val EARN_ASSET_SUFFIXES: List<String> =
        listOf(".S", ".M", ".F", ".B", ".P")
}
