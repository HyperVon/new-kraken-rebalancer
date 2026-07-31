package com.gemini.krakenbot.model

import com.gemini.krakenbot.TestFixtures
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class AssetTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "fromTradingPair resolves all standard USD-quoted BTC aliases" {
            val allocations = listOf(Asset.BTC, Asset.USD)
            Asset.fromTradingPair(TestFixtures.XBTUSD, allocations) shouldBe Asset.BTC
            Asset.fromTradingPair(TestFixtures.BTCUSD, allocations) shouldBe Asset.BTC
            Asset.fromTradingPair(TestFixtures.XXBTZUSD, allocations) shouldBe Asset.BTC
        }

        "fromTradingPair returns canonical symbols for lower-case Kraken aliases" {
            Asset.fromTradingPair(TestFixtures.XBTUSD, listOf(TestFixtures.BTC_LOWER, "usd")) shouldBe Asset.BTC
            Asset.fromTradingPair(Asset.tradingPair("xdg"), listOf("doge", "usd")) shouldBe Asset.DOGE
        }

        "fromTradingPair resolves ETH aliases and is case-insensitive" {
            val allocations = listOf(Asset.ETH, Asset.USD)
            Asset.fromTradingPair(TestFixtures.ETHUSD, allocations) shouldBe Asset.ETH
            Asset.fromTradingPair(TestFixtures.XETHZUSD, allocations) shouldBe Asset.ETH
            Asset.fromTradingPair(TestFixtures.XETHZUSD.lowercase(), allocations) shouldBe Asset.ETH
        }

        "fromTradingPair does not mis-resolve a non-USD quote via prefix collision" {
            val allocations = listOf(Asset.BTC, Asset.USD)
            // A USDT/USDC-quoted BTC pair must not resolve to BTC (exact matching).
            Asset.fromTradingPair("XBTUSDT", allocations) shouldBe null
            Asset.fromTradingPair("XBTUSDC", allocations) shouldBe null
        }

        "fromTradingPair falls back to known symbols when not in allocations" {
            val allocations = listOf(Asset.USD)
            Asset.fromTradingPair(TestFixtures.XBTUSD, allocations) shouldBe Asset.BTC
        }

        "fromTradingPair returns null for an unrelated pair" {
            val allocations = listOf(Asset.BTC, Asset.USD)
            Asset.fromTradingPair(TestFixtures.ADAEUR, allocations) shouldBe null
        }
    }
}
