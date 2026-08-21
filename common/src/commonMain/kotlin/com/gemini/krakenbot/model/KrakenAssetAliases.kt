package com.gemini.krakenbot.model

/**
 * Kraken-specific asset codes, legacy ISO-4217 prefixes, and ticker aliases.
 * Centralizes all exchange-specific symbol mappings away from domain models.
 */
object KrakenAssetAliases {
    const val BTC = "BTC"
    const val XBT = "XBT"
    const val XXBT = "XXBT"

    const val DOGE = "DOGE"
    const val XDG = "XDG"
    const val XXDG = "XXDG"

    const val ETH = "ETH"
    const val XETH = "XETH"

    const val LTC = "LTC"
    const val XLTC = "XLTC"

    const val XRP = "XRP"
    const val XXRP = "XXRP"

    const val XLM = "XLM"
    const val XXLM = "XXLM"

    const val XMR = "XMR"
    const val XXMR = "XXMR"

    const val ZEC = "ZEC"
    const val XZEC = "XZEC"

    const val ETC = "ETC"
    const val XETC = "XETC"

    const val REP = "REP"
    const val XREP = "XREP"

    const val MLN = "MLN"
    const val XMLN = "XMLN"

    const val USD = "USD"
    const val ZUSD = "ZUSD"

    const val EUR = "EUR"
    const val ZEUR = "ZEUR"

    const val CAD = "CAD"
    const val ZCAD = "ZCAD"

    const val GBP = "GBP"
    const val ZGBP = "ZGBP"

    const val JPY = "JPY"
    const val ZJPY = "ZJPY"

    const val CHF = "CHF"
    const val ZCHF = "ZCHF"

    const val AUD = "AUD"
    const val ZAUD = "ZAUD"

    /** Kraken-specific ticker code for common symbols where they differ (BTC→XBT, DOGE→XDG). */
    val KRAKEN_TICKER_BY_SYMBOL: Map<String, String> = mapOf(
        BTC to XBT,
        DOGE to XDG,
    )

    /** Legacy Kraken ISO-4217 asset codes and ticker aliases mapped to canonical application symbols. */
    val CANONICAL_BY_KRAKEN_ALIAS: Map<String, String> = mapOf(
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
    val EARN_ASSET_SUFFIXES: List<String> = listOf(".S", ".M", ".F", ".B", ".P")
}
