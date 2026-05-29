package com.gemini.krakenbot.util

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

@Suppress("unused")
class KrakenSymbolsTest : StringSpec({
    "toKrakenTicker_mapsKnownSymbols" {
        KrakenSymbols.toKrakenTicker("btc") shouldBe KrakenSymbols.XBT  // lowercase tests case-insensitivity
        KrakenSymbols.toKrakenTicker(KrakenSymbols.DOGE) shouldBe KrakenSymbols.XDG
        KrakenSymbols.toKrakenTicker("eth") shouldBe KrakenSymbols.ETH  // lowercase tests case-insensitivity
    }

    "tradingPair_buildsUsdPair" {
        KrakenSymbols.tradingPair(KrakenSymbols.BTC) shouldBe KrakenSymbols.BTC_USD_PAIR
        KrakenSymbols.tradingPair(KrakenSymbols.DOGE) shouldBe "XDGUSD"
    }
})
