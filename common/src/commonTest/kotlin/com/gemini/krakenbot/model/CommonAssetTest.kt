package com.gemini.krakenbot.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CommonAssetTest {

    @Test
    fun normalizeLedgerAssetStripsEarnSuffixesForAnyAsset() {
        assertEquals("DOT", Asset.normalizeLedgerAsset("DOT.S"))
        assertEquals("DOT", Asset.normalizeLedgerAsset("DOT.M"))
        assertEquals("DOT", Asset.normalizeLedgerAsset("DOT.P"))
        assertEquals("USDT", Asset.normalizeLedgerAsset("USDT.F"))
        assertEquals("USDT", Asset.normalizeLedgerAsset("USDT.B"))
        assertEquals("AVAX", Asset.normalizeLedgerAsset("AVAX.S"))
        assertEquals("NEAR", Asset.normalizeLedgerAsset("NEAR.M"))
        assertEquals("ATOM", Asset.normalizeLedgerAsset("ATOM.S"))
        assertEquals("SOL", Asset.normalizeLedgerAsset("SOL.S"))
        assertEquals("ETH", Asset.normalizeLedgerAsset("ETH.S"))
        assertEquals("SUI", Asset.normalizeLedgerAsset("SUI.S"))
    }

    @Test
    fun normalizeLedgerAssetMapsLegacyKrakenIsoAliases() {
        assertEquals("BTC", Asset.normalizeLedgerAsset("XXBT"))
        assertEquals("BTC", Asset.normalizeLedgerAsset("XBT"))
        assertEquals("DOGE", Asset.normalizeLedgerAsset("XXDG"))
        assertEquals("DOGE", Asset.normalizeLedgerAsset("XDG"))
        assertEquals("ETH", Asset.normalizeLedgerAsset("XETH"))
        assertEquals("LTC", Asset.normalizeLedgerAsset("XLTC"))
        assertEquals("XRP", Asset.normalizeLedgerAsset("XXRP"))
        assertEquals("XLM", Asset.normalizeLedgerAsset("XXLM"))
        assertEquals("XMR", Asset.normalizeLedgerAsset("XXMR"))
        assertEquals("ZEC", Asset.normalizeLedgerAsset("XZEC"))
        assertEquals("USD", Asset.normalizeLedgerAsset("ZUSD"))
        assertEquals("EUR", Asset.normalizeLedgerAsset("ZEUR"))
        assertEquals("CAD", Asset.normalizeLedgerAsset("ZCAD"))
        assertEquals("GBP", Asset.normalizeLedgerAsset("ZGBP"))
        assertEquals("JPY", Asset.normalizeLedgerAsset("ZJPY"))
        assertEquals("CHF", Asset.normalizeLedgerAsset("ZCHF"))
        assertEquals("AUD", Asset.normalizeLedgerAsset("ZAUD"))
    }

    @Test
    fun normalizeLedgerAssetHandlesStakedLegacyAliases() {
        assertEquals("BTC", Asset.normalizeLedgerAsset("XXBT.S"))
        assertEquals("BTC", Asset.normalizeLedgerAsset("XBT.M"))
        assertEquals("ETH", Asset.normalizeLedgerAsset("XETH.S"))
        assertEquals("DOGE", Asset.normalizeLedgerAsset("XXDG.F"))
    }

    @Test
    fun normalizeLedgerAssetPreservesModernTickersStartingWithXOrZ() {
        assertEquals("XRP", Asset.normalizeLedgerAsset("XRP"))
        assertEquals("XTZ", Asset.normalizeLedgerAsset("XTZ"))
        assertEquals("ZEC", Asset.normalizeLedgerAsset("ZEC"))
        assertEquals("ZETA", Asset.normalizeLedgerAsset("ZETA"))
        assertEquals("XAUT", Asset.normalizeLedgerAsset("XAUT"))
        assertEquals("ZRO", Asset.normalizeLedgerAsset("ZRO"))
        assertEquals("ZRX", Asset.normalizeLedgerAsset("ZRX"))
        assertEquals("ZEN", Asset.normalizeLedgerAsset("ZEN"))
        assertEquals("ZIL", Asset.normalizeLedgerAsset("ZIL"))
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
    fun krakenAssetAliasesTickerMapping() {
        assertEquals("XBT", KrakenAssetAliases.KRAKEN_TICKER_BY_SYMBOL[KrakenAssetAliases.BTC])
        assertEquals("XDG", KrakenAssetAliases.KRAKEN_TICKER_BY_SYMBOL[KrakenAssetAliases.DOGE])
        assertEquals(2, KrakenAssetAliases.KRAKEN_TICKER_BY_SYMBOL.size)
        assertEquals("BTC", KrakenAssetAliases.CANONICAL_BY_KRAKEN_ALIAS[KrakenAssetAliases.XXBT])
        assertEquals("USD", KrakenAssetAliases.CANONICAL_BY_KRAKEN_ALIAS[KrakenAssetAliases.ZUSD])
    }
}
