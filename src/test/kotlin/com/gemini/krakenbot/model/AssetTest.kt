package com.gemini.krakenbot.model

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class AssetTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "fromTradingPair resolves all standard USD-quoted BTC aliases" {
            val allocations = listOf(Asset.BTC, Asset.USD)
            Asset.fromTradingPair("XBTUSD", allocations) shouldBe Asset.BTC
            Asset.fromTradingPair("BTCUSD", allocations) shouldBe Asset.BTC
            Asset.fromTradingPair("XXBTZUSD", allocations) shouldBe Asset.BTC
        }

        "fromTradingPair resolves ETH aliases and is case-insensitive" {
            val allocations = listOf(Asset.ETH, Asset.USD)
            Asset.fromTradingPair("ETHUSD", allocations) shouldBe Asset.ETH
            Asset.fromTradingPair("XETHZUSD", allocations) shouldBe Asset.ETH
            Asset.fromTradingPair("xethzusd", allocations) shouldBe Asset.ETH
        }

        "fromTradingPair does not mis-resolve a non-USD quote via prefix collision" {
            val allocations = listOf(Asset.BTC, Asset.USD)
            // A USDT/USDC-quoted BTC pair must not resolve to BTC (exact matching).
            Asset.fromTradingPair("XBTUSDT", allocations) shouldBe null
            Asset.fromTradingPair("XBTUSDC", allocations) shouldBe null
        }

        "fromTradingPair falls back to known symbols when not in allocations" {
            val allocations = listOf(Asset.USD)
            Asset.fromTradingPair("XBTUSD", allocations) shouldBe Asset.BTC
        }

        "fromTradingPair returns null for an unrelated pair" {
            val allocations = listOf(Asset.BTC, Asset.USD)
            Asset.fromTradingPair("ADAEUR", allocations) shouldBe null
        }
    }
}
