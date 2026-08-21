package com.gemini.krakenbot.model

/** Kraken-specific ticker code for common symbols where they differ (BTC→XBT, DOGE→XDG). */
val KrakenAssetAliases.KRAKEN_TICKER_BY_SYMBOL: Map<String, String>
    get() = mapOf(
        BTC to XBT,
        DOGE to XDG,
    )

/** Legacy Kraken ISO-4217 asset codes and ticker aliases mapped to canonical application symbols. */
val KrakenAssetAliases.CANONICAL_BY_KRAKEN_ALIAS: Map<String, String>
    get() = mapOf(
        XBT to BTC,
        XXBT to BTC,
        XDG to DOGE,
        XXDG to DOGE,
        XETH to ETH,
        XLTC to LTC,
        XXRP to XRP,
        XXLM to XLM,
        XXMR to XMR,
        XZEC to ZEC,
        XETC to ETC,
        XREP to REP,
        XMLN to MLN,
        ZUSD to USD,
        ZEUR to EUR,
        ZCAD to CAD,
        ZGBP to GBP,
        ZJPY to JPY,
        ZCHF to CHF,
        ZAUD to AUD,
    )

/**
 * Earn-migration suffixes: Kraken balances/ledger entries use e.g. `DOT.S`, `USDT.F` for
 * read-only yield-bearing assets; the base asset remains the transactable one.
 */
val KrakenAssetAliases.EARN_ASSET_SUFFIXES: List<String>
    get() = listOf(".S", ".M", ".F", ".B", ".P")
