package com.gemini.krakenbot.util

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class KrakenSymbolsTest : StringSpec({
    "toKrakenTicker_mapsKnownSymbols" {
        KrakenSymbols.toKrakenTicker("btc") shouldBe "XBT"
        KrakenSymbols.toKrakenTicker("DOGE") shouldBe "XDG"
        KrakenSymbols.toKrakenTicker("eth") shouldBe "ETH"
    }

    "tradingPair_buildsUsdPair" {
        KrakenSymbols.tradingPair("BTC") shouldBe "XBTUSD"
        KrakenSymbols.tradingPair("DOGE") shouldBe "XDGUSD"
    }
})
