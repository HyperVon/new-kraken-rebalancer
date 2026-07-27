package com.gemini.krakenbot.model

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.service.PortfolioValues
import com.gemini.krakenbot.service.impl.OrderExecutorImpl
import com.gemini.krakenbot.service.impl.PortfolioCalculations
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant

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

        "testPortfolioSnapshot" {
            val asset =
                PortfolioSnapshot.AssetSnapshot(
                    symbol = Asset.BTC,
                    balance = BigDecimal.ONE,
                    price = BigDecimal.TEN,
                    valueUSD = BigDecimal.TEN,
                    targetPercent = BigDecimal.ONE,
                    currentPercent = BigDecimal.ONE,
                    deviationPercent = BigDecimal.ZERO,
                    deviationUSD = BigDecimal.ZERO,
                )
            val asset2 = asset.copy()
            asset2 shouldBe asset
            asset.hashCode() shouldBe asset2.hashCode()
            asset.toString().shouldNotBeNull()
            asset.symbol.value shouldBe Asset.BTC

            val snapshot =
                PortfolioSnapshot(
                    timestamp = Instant.EPOCH,
                    totalValueUSD = BigDecimal.TEN,
                    assets = mapOf(Asset.BTC to asset),
                    actions = listOf("BUY"),
                    drawdownPercent = BigDecimal.ZERO,
                    fiatDeploymentPercent = BigDecimal.ZERO,
                    effectiveUsdTargetPercent = BigDecimal.ZERO,
                )
            val snapshot2 = snapshot.copy()
            snapshot2 shouldBe snapshot
            snapshot.hashCode() shouldBe snapshot2.hashCode()
            snapshot.toString().shouldNotBeNull()
        }

        "testSettings" {
            val settings =
                Settings(
                    loopDelaySeconds = 60,
                    deviationTriggerPercent = 2.0,
                    dustThresholdUSD = 1.0,
                    dryRun = true,
                    fiatMaxDrawdown = 50.0,
                    fiatDeploymentExponent = 1.0,
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
            val pv =
                PortfolioValues(
                    totalValueUSD = BigDecimal.TEN,
                    currentValuesUSD = mapOf(Asset.BTC to BigDecimal.TEN),
                )
            pv.totalValueUSD.shouldBeEqualComparingTo(BigDecimal.TEN)
            pv.currentValuesUSD shouldBe mapOf(Asset.BTC to BigDecimal.TEN)

            val pv2 = pv.copy()
            pv2 shouldBe pv
            pv.hashCode() shouldBe pv2.hashCode()
            pv.toString().shouldNotBeNull()
        }

        "testServiceCompanions" {
            OrderExecutorImpl.CASH_RESERVE_FACTOR.shouldBeEqualComparingTo(BigDecimal("0.99"))
            OrderExecutorImpl.FEE_RATE_ESTIMATE.shouldBeEqualComparingTo(BigDecimal("0.006"))

            PortfolioCalculations.HUNDRED.shouldBeEqualComparingTo(BigDecimal("100"))
            PortfolioCalculations.SCALE_PERCENT shouldBe 4
            PortfolioCalculations.SCALE_PRICE shouldBe 8
            PortfolioCalculations.SCALE_USD shouldBe 2
        }

        "testOrderResultCompanionFactory" {
            val successResult =
                OrderResult(
                    success = true,
                    pair = "XBTUSD",
                    side = "BUY",
                    volume = BigDecimal.ONE,
                    dryRun = false,
                )
            successResult.success shouldBe true
            (successResult as OrderResult.Success).errorMessage shouldBe null

            val failureResult =
                OrderResult(
                    success = false,
                    pair = "XBTUSD",
                    side = "BUY",
                    volume = BigDecimal.ONE,
                    dryRun = false,
                    errorMessage = "Insufficient funds",
                )
            failureResult.success shouldBe false
            (failureResult as OrderResult.Failure).errorMessage shouldBe "Insufficient funds"

            val defaultFailure =
                OrderResult(
                    success = false,
                    pair = "XBTUSD",
                    side = "BUY",
                    volume = BigDecimal.ONE,
                )
            (defaultFailure as OrderResult.Failure).errorMessage shouldBe "Unknown error"
        }

        "testTradeRecordExtensions" {
            val now = Instant.now()
            val t1 =
                TradeRecord(
                    now,
                    "XBTUSD",
                    "BUY",
                    "BTC",
                    BigDecimal.ONE,
                    BigDecimal(
                        "50000.00",
                    ),
                    success = true,
                    dryRun = false,
                    id = 1,
                    fee = BigDecimal("10.00"),
                )
            val t2 =
                TradeRecord(
                    now,
                    "XXBTZUSD",
                    "BUY",
                    "BTC",
                    BigDecimal.ONE,
                    BigDecimal(
                        "50000.00",
                    ),
                    success = true,
                    dryRun = false,
                    id = 2,
                    fee = BigDecimal("100.00"),
                )
            val t3 =
                TradeRecord(
                    now.plusSeconds(
                        300,
                    ),
                    "XDGUSD",
                    "SELL",
                    "DOGE",
                    BigDecimal.TEN,
                    BigDecimal("10.00"),
                    success = true,
                    dryRun = false,
                    id = 3,
                    fee = BigDecimal("0.10"),
                )

            t1.isSameSymbolAndSide(t2) shouldBe true
            t1.isSameSymbolAndSide(t3) shouldBe false

            // Different fees with identical provenance → not an alias duplicate
            t1.isPairAliasDuplicateOf(t2) shouldBe false
            t1.isPairAliasDuplicateOf(t3) shouldBe false
            t1.copy(fee = t2.fee).isPairAliasDuplicateOf(t2) shouldBe true

            t1.feePercentDiffersMateriallyFrom(t2) shouldBe true

            val zeroAmount = t1.copy(usdAmount = BigDecimal.ZERO)
            zeroAmount.feePercentDiffersMateriallyFrom(t2) shouldBe false

            t1.isMatchingApiTrade(t2, listOf("BTC", "DOGE")) shouldBe true

            // CQ-8-L1: dry-run locals must not match API fills (would promote to live API_FILL).
            t1.copy(dryRun = true).isMatchingApiTrade(t2, listOf("BTC", "DOGE")) shouldBe false
        }

        "isMatchingApiTrade rejects when volume within tolerance but USD differs by more than 1 percent" {
            val now = Instant.now()
            val local =
                TradeRecord(
                    now,
                    "XBTUSD",
                    "BUY",
                    "BTC",
                    BigDecimal("1.0"),
                    BigDecimal("100.00"),
                    success = true,
                    dryRun = false,
                )
            val api =
                TradeRecord(
                    now,
                    "XXBTZUSD",
                    "BUY",
                    "BTC",
                    BigDecimal("1.005"),
                    BigDecimal("110.00"),
                    success = true,
                    dryRun = false,
                )
            local.isMatchingApiTrade(api, listOf("BTC", "DOGE")) shouldBe false
            local.isMatchingApiTrade(api.copy(volume = BigDecimal("1.0")), listOf("BTC", "DOGE")) shouldBe true
        }
    }
}
