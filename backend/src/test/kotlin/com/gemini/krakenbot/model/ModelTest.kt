package com.gemini.krakenbot.model

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.domain.CASH_RESERVE_FACTOR
import com.gemini.krakenbot.domain.HUNDRED
import com.gemini.krakenbot.util.PrecisionConstants
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import java.math.BigDecimal

class ModelTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "testAssetMappings" {
            val btc = Asset(Asset.BTC)
            btc.krakenTicker shouldBe Asset.XBT
            btc.tradingPair shouldBe TestFixtures.XBTUSD
            btc.isUsd shouldBe false

            val doge = Asset(Asset.DOGE)
            doge.krakenTicker shouldBe Asset.XDG
            doge.tradingPair shouldBe "XDGUSD"

            val usd = Asset(Asset.USD)
            usd.isUsd shouldBe true
            usd.tradingPair shouldBe "USDUSD"

            val eth = Asset(Asset.ETH)
            eth.krakenTicker shouldBe Asset.ETH
            eth.tradingPair shouldBe TestFixtures.ETHUSD

            Asset.toKrakenTicker(TestFixtures.BTC_LOWER) shouldBe Asset.XBT
            Asset.toKrakenTicker("doge") shouldBe Asset.XDG
            Asset.toKrakenTicker(TestFixtures.ETH_LOWER) shouldBe "ETH"

            Asset.tradingPair(TestFixtures.BTC_LOWER) shouldBe TestFixtures.XBTUSD
            Asset.tradingPair(TestFixtures.ETH_LOWER) shouldBe TestFixtures.ETHUSD

            Asset.BTC_USD_PAIR shouldBe TestFixtures.XBTUSD
        }

        "matchesUsdQuotedPair accepts exact aliases and rejects substring collisions" {
            Asset.matchesUsdQuotedPair("ETHUSD", Asset.ETH) shouldBe true
            Asset.matchesUsdQuotedPair("XETHZUSD", Asset.ETH) shouldBe true
            Asset.matchesUsdQuotedPair("SOMETHINGETHUSD", Asset.ETH) shouldBe false
            Asset.matchesUsdQuotedPair("XBTUSD", Asset.BTC) shouldBe true
            Asset.matchesUsdQuotedPair("XXBTZUSD", Asset.BTC) shouldBe true
            Asset.matchesUsdQuotedPair("XBTUSDT", Asset.BTC) shouldBe false
        }

        "testServiceCompanions" {
            PrecisionConstants.CASH_RESERVE_FACTOR.shouldBeEqualComparingTo(BigDecimal("0.99"))

            PrecisionConstants.HUNDRED.shouldBeEqualComparingTo(BigDecimal("100"))
            PrecisionConstants.SCALE_PERCENT shouldBe 4
            PrecisionConstants.SCALE_CRYPTO shouldBe 8
            PrecisionConstants.SCALE_USD shouldBe 2
        }
    }
}
