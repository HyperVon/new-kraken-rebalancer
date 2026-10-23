package com.gemini.krakenbot.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CommonAssetTest {

    @Test
    fun normalizeKrakenAssetCodeStripsEarnSuffixes() {
        assertEquals("DOT", Asset.normalizeKrakenAssetCode("DOT.S"))
        assertEquals("DOT", Asset.normalizeKrakenAssetCode("DOT.M"))
        assertEquals("DOT", Asset.normalizeKrakenAssetCode("DOT.P"))
        assertEquals("USDT", Asset.normalizeKrakenAssetCode("USDT.F"))
        assertEquals("USDT", Asset.normalizeKrakenAssetCode("USDT.B"))
        assertEquals("AVAX", Asset.normalizeKrakenAssetCode("AVAX.S"))
        assertEquals("NEAR", Asset.normalizeKrakenAssetCode("NEAR.M"))
        assertEquals("ATOM", Asset.normalizeKrakenAssetCode("ATOM.S"))
        assertEquals("SOL", Asset.normalizeKrakenAssetCode("SOL.S"))
        assertEquals("ETH", Asset.normalizeKrakenAssetCode("ETH.S"))
        assertEquals("SUI", Asset.normalizeKrakenAssetCode("SUI.S"))
    }

    @Test
    fun normalizeKrakenAssetCodeMapsLegacyAliases() {
        assertEquals("BTC", Asset.normalizeKrakenAssetCode("XXBT"))
        assertEquals("BTC", Asset.normalizeKrakenAssetCode("XBT"))
        assertEquals("DOGE", Asset.normalizeKrakenAssetCode("XXDG"))
        assertEquals("DOGE", Asset.normalizeKrakenAssetCode("XDG"))
        assertEquals("ETH", Asset.normalizeKrakenAssetCode("XETH"))
        assertEquals("SOL", Asset.normalizeKrakenAssetCode("SOL03"))
        assertEquals("LTC", Asset.normalizeKrakenAssetCode("XLTC"))
        assertEquals("XRP", Asset.normalizeKrakenAssetCode("XXRP"))
        assertEquals("XLM", Asset.normalizeKrakenAssetCode("XXLM"))
        assertEquals("XMR", Asset.normalizeKrakenAssetCode("XXMR"))
        assertEquals("ZEC", Asset.normalizeKrakenAssetCode("XZEC"))
        assertEquals("USD", Asset.normalizeKrakenAssetCode("ZUSD"))
        assertEquals("EUR", Asset.normalizeKrakenAssetCode("ZEUR"))
        assertEquals("CAD", Asset.normalizeKrakenAssetCode("ZCAD"))
        assertEquals("GBP", Asset.normalizeKrakenAssetCode("ZGBP"))
        assertEquals("JPY", Asset.normalizeKrakenAssetCode("ZJPY"))
        assertEquals("CHF", Asset.normalizeKrakenAssetCode("ZCHF"))
        assertEquals("AUD", Asset.normalizeKrakenAssetCode("ZAUD"))
    }

    @Test
    fun normalizeKrakenAssetCodeHandlesStakedAliases() {
        assertEquals("BTC", Asset.normalizeKrakenAssetCode("XXBT.S"))
        assertEquals("BTC", Asset.normalizeKrakenAssetCode("XBT.M"))
        assertEquals("ETH", Asset.normalizeKrakenAssetCode("XETH.S"))
        assertEquals("DOGE", Asset.normalizeKrakenAssetCode("XXDG.F"))
    }

    @Test
    fun normalizeKrakenAssetCodePreservesModernTickers() {
        assertEquals("XRP", Asset.normalizeKrakenAssetCode("XRP"))
        assertEquals("XTZ", Asset.normalizeKrakenAssetCode("XTZ"))
        assertEquals("ZEC", Asset.normalizeKrakenAssetCode("ZEC"))
        assertEquals("ZETA", Asset.normalizeKrakenAssetCode("ZETA"))
        assertEquals("XAUT", Asset.normalizeKrakenAssetCode("XAUT"))
        assertEquals("ZRO", Asset.normalizeKrakenAssetCode("ZRO"))
        assertEquals("ZRX", Asset.normalizeKrakenAssetCode("ZRX"))
        assertEquals("ZEN", Asset.normalizeKrakenAssetCode("ZEN"))
        assertEquals("ZIL", Asset.normalizeKrakenAssetCode("ZIL"))
    }

    @Test
    fun fromTradingPairResolvesConfiguredAllocations() {
        val allocations = listOf("SOL", "USD")
        assertEquals("SOL", Asset.fromTradingPair("SOLUSD", allocations))
        assertEquals("SOL", Asset.fromTradingPair("XSOLZUSD", allocations))
    }

    @Test
    fun fromTradingPairGenericExtractionForUnconfiguredUsdPairs() {
        assertEquals("SOL", Asset.fromTradingPair("SOLUSD", emptyList()))
        assertEquals("AVAX", Asset.fromTradingPair("AVAXUSD", emptyList()))
        assertEquals("BTC", Asset.fromTradingPair("XBTUSD", emptyList()))
        assertEquals("BTC", Asset.fromTradingPair("BTCUSD", emptyList()))
        assertEquals("BTC", Asset.fromTradingPair("XXBTZUSD", emptyList()))
        assertEquals("ETH", Asset.fromTradingPair("ETHUSD", emptyList()))
        assertEquals("ETH", Asset.fromTradingPair("XETHZUSD", emptyList()))
        assertEquals("DOGE", Asset.fromTradingPair("XDGUSD", emptyList()))
        assertEquals("DOGE", Asset.fromTradingPair("XXDGZUSD", emptyList()))
    }

    @Test
    fun fromTradingPairRejectsNonUsdPairs() {
        assertNull(Asset.fromTradingPair("ADAEUR", emptyList()))
        assertNull(Asset.fromTradingPair("BTCEUR", emptyList()))
        assertNull(Asset.fromTradingPair("XBTUSDT", emptyList()))
        assertNull(Asset.fromTradingPair("XBTUSDC", emptyList()))
        assertNull(Asset.fromTradingPair("", emptyList()))
        assertNull(Asset.fromTradingPair("USD", emptyList()))
    }

    @Test
    fun splitTradingPairResolvesKnownQuotes() {
        assertEquals(TradingPairSplit("SEI", "USD", "SEIUSD"), Asset.splitTradingPair("SEIUSD"))
        assertEquals(TradingPairSplit("BTC", "USD", "XXBTZUSD"), Asset.splitTradingPair("XXBTZUSD"))
        assertEquals(TradingPairSplit("ATOM", "USDT", "ATOMUSDT"), Asset.splitTradingPair("ATOMUSDT"))
        assertEquals(TradingPairSplit("USDG", "USDC", "USDGUSDC"), Asset.splitTradingPair("USDGUSDC"))
        assertEquals(TradingPairSplit("USDT", "USD", "USDTZUSD"), Asset.splitTradingPair("USDTZUSD"))
        assertEquals(TradingPairSplit("SOL", "USDT", "SOLUSDT"), Asset.splitTradingPair("solusdt"))
    }

    @Test
    fun splitTradingPairKeepsModernBasesEndingInZ() {
        assertEquals(TradingPairSplit("CHZ", "USD", "CHZUSD"), Asset.splitTradingPair("CHZUSD"))
        assertEquals(TradingPairSplit("XTZ", "USD", "XTZUSD"), Asset.splitTradingPair("XTZUSD"))
        assertEquals(TradingPairSplit("STRC", "USD", "STRCZUSD"), Asset.splitTradingPair("STRCZUSD"))
    }

    @Test
    fun splitTradingPairRejectsUnknownQuotesAndDegeneratePairs() {
        assertNull(Asset.splitTradingPair("ADAEUR"))
        assertNull(Asset.splitTradingPair("BTCEUR"))
        assertNull(Asset.splitTradingPair("USD"))
        assertNull(Asset.splitTradingPair(""))
        assertNull(Asset.splitTradingPair("   "))
        assertNull(Asset.splitTradingPair("USDCUSDC"))
    }

    @Test
    fun krakenAssetAliasesTickerMapping() {
        assertEquals("XBT", KRAKEN_TICKER_BY_SYMBOL[KrakenAssetAliases.BTC])
        assertEquals("XDG", KRAKEN_TICKER_BY_SYMBOL[KrakenAssetAliases.DOGE])
        assertEquals(2, KRAKEN_TICKER_BY_SYMBOL.size)
        assertEquals("BTC", CANONICAL_BY_KRAKEN_ALIAS[KrakenAssetAliases.XXBT])
        assertEquals("USD", CANONICAL_BY_KRAKEN_ALIAS[KrakenAssetAliases.ZUSD])
    }
}
