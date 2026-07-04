package com.gemini.krakenbot.model

import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.service.PortfolioValues
import com.gemini.krakenbot.service.impl.OrderExecutorImpl
import com.gemini.krakenbot.service.impl.PortfolioAnalyzerImpl
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant

class ModelTest : StringSpec() {
    init {
        "testAssetMappings" {
            val btc = Asset(Asset.BTC)
            btc.krakenTicker shouldBe Asset.XBT
            btc.tradingPair shouldBe "XBTUSD"
            btc.isUsd shouldBe false

            val doge = Asset(Asset.DOGE)
            doge.krakenTicker shouldBe Asset.XDG
            doge.tradingPair shouldBe "XDGUSD"

            val usd = Asset(Asset.USD)
            usd.isUsd shouldBe true
            usd.tradingPair shouldBe "USDUSD"

            val eth = Asset(Asset.ETH)
            eth.krakenTicker shouldBe Asset.ETH
            eth.tradingPair shouldBe "ETHUSD"

            Asset.toKrakenTicker("btc") shouldBe Asset.XBT
            Asset.toKrakenTicker("doge") shouldBe Asset.XDG
            Asset.toKrakenTicker("eth") shouldBe "ETH"

            Asset.tradingPair("btc") shouldBe "XBTUSD"
            Asset.tradingPair("eth") shouldBe "ETHUSD"

            Asset.BTC_USD_PAIR shouldBe "XBTUSD"
        }

        "testPortfolioSnapshot" {
            val asset = PortfolioSnapshot.AssetSnapshot(
                symbol = Asset.BTC,
                balance = BigDecimal.ONE,
                price = BigDecimal.TEN,
                valueUSD = BigDecimal.TEN,
                targetPercent = BigDecimal.ONE,
                currentPercent = BigDecimal.ONE,
                deviationPercent = BigDecimal.ZERO,
                deviationUSD = BigDecimal.ZERO
            )
            val asset2 = asset.copy()
            asset2 shouldBe asset
            asset.hashCode() shouldBe asset2.hashCode()
            asset.toString().shouldNotBeNull()
            asset.symbol.value shouldBe Asset.BTC

            val snapshot = PortfolioSnapshot(
                timestamp = Instant.EPOCH,
                totalValueUSD = BigDecimal.TEN,
                assets = mapOf(Asset.BTC to asset),
                actions = listOf("BUY"),
                drawdownPercent = BigDecimal.ZERO,
                fiatDeploymentPercent = BigDecimal.ZERO,
                effectiveUsdTargetPercent = BigDecimal.ZERO
            )
            val snapshot2 = snapshot.copy()
            snapshot2 shouldBe snapshot
            snapshot.hashCode() shouldBe snapshot2.hashCode()
            snapshot.toString().shouldNotBeNull()
        }

        "testSettings" {
            val settings = Settings(
                loopDelaySeconds = 60,
                deviationTriggerPercent = 2.0,
                dustThresholdUSD = 1.0,
                dryRun = true,
                fiatMaxDrawdown = 50.0,
                fiatDeploymentExponent = 1.0
            )
            val settings5 = settings.copy()
            settings5 shouldBe settings
            settings.hashCode() shouldBe settings5.hashCode()
            settings.toString().shouldNotBeNull()
        }

        "testPortfolioStats" {
            val stats = PortfolioStats(allTimeHigh = BigDecimal.TEN)
            val stats2 = stats.copy()
            stats2 shouldBe stats
            stats.hashCode() shouldBe stats2.hashCode()
            stats.toString().shouldNotBeNull()
        }

        "testPortfolioValues" {
            val pv = PortfolioValues(
                totalValueUSD = BigDecimal.TEN,
                currentValuesUSD = mapOf("BTC" to BigDecimal.TEN)
            )
            pv.totalValueUSD shouldBe BigDecimal.TEN
            pv.currentValuesUSD shouldBe mapOf("BTC" to BigDecimal.TEN)
            
            val pv2 = pv.copy()
            pv2 shouldBe pv
            pv.hashCode() shouldBe pv2.hashCode()
            pv.toString().shouldNotBeNull()
        }

        "testServiceCompanions" {
            OrderExecutorImpl.CASH_RESERVE_FACTOR shouldBe BigDecimal("0.99")
            OrderExecutorImpl.FEE_RATE_ESTIMATE shouldBe BigDecimal("0.0026")

            PortfolioAnalyzerImpl.HUNDRED shouldBe BigDecimal("100")
            PortfolioAnalyzerImpl.SCALE_PERCENT shouldBe 4
            PortfolioAnalyzerImpl.SCALE_PRICE shouldBe 8
            PortfolioAnalyzerImpl.SCALE_USD shouldBe 2
        }
    }
}
