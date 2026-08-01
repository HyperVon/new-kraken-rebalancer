package com.gemini.krakenbot.model

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.service.impl.PortfolioCalculations
import com.gemini.krakenbot.util.CASH_RESERVE_FACTOR
import com.gemini.krakenbot.util.PrecisionConstants
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
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

        "testServiceCompanions" {
            PrecisionConstants.CASH_RESERVE_FACTOR.shouldBeEqualComparingTo(BigDecimal("0.99"))

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
                TestFixtures.tradeRecord(
                    now,
                    "XBTUSD",
                    "BUY",
                    "BTC",
                    BigDecimal.ONE,
                    BigDecimal(
                        "50000.00",
                    ),
                    id = 1,
                    fee = BigDecimal("10.00"),
                )
            val t2 =
                TestFixtures.tradeRecord(
                    now,
                    "XXBTZUSD",
                    "BUY",
                    "BTC",
                    BigDecimal.ONE,
                    BigDecimal(
                        "50000.00",
                    ),
                    id = 2,
                    fee = BigDecimal("100.00"),
                )
            val t3 =
                TestFixtures.tradeRecord(
                    now.plusSeconds(
                        300,
                    ),
                    "XDGUSD",
                    "SELL",
                    "DOGE",
                    BigDecimal.TEN,
                    BigDecimal("10.00"),
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
                TestFixtures.tradeRecord(
                    now,
                    "XBTUSD",
                    "BUY",
                    "BTC",
                    BigDecimal("1.0"),
                    BigDecimal("100.00"),
                )
            val api =
                TestFixtures.tradeRecord(
                    now,
                    "XXBTZUSD",
                    "BUY",
                    "BTC",
                    BigDecimal("1.005"),
                    BigDecimal("110.00"),
                )
            local.isMatchingApiTrade(api, listOf("BTC", "DOGE")) shouldBe false
            local.isMatchingApiTrade(api.copy(volume = BigDecimal("1.0")), listOf("BTC", "DOGE")) shouldBe true
        }

        "pair alias matching rejects conflicting trade identity and economics" {
            val now = Instant.now()
            val base = TestFixtures.tradeRecord(
                timestamp = now,
                pair = TestFixtures.XBTUSD,
                side = TestFixtures.BUY_UPPER,
                symbol = Asset.BTC,
                volume = BigDecimal.ONE,
                usdAmount = BigDecimal("50000.00"),
                price = BigDecimal("50000.00"),
                fee = BigDecimal("100.00"),
                source = TradeSource.API_FILL,
            )
            fun alias(record: TradeRecord) = record.copy(pair = TestFixtures.XXBTZUSD)

            base.copy(tradeId = "fill-a").isPairAliasDuplicateOf(alias(base.copy(tradeId = "fill-b"))) shouldBe false
            base.copy(tradeId = "fill-a").isPairAliasDuplicateOf(
                alias(base.copy(source = TradeSource.LEGACY_UNKNOWN)),
            ) shouldBe false
            base.isPairAliasDuplicateOf(alias(base.copy(symbol = Asset.DOGE))) shouldBe false
            base.isPairAliasDuplicateOf(base.copy(pair = TestFixtures.XBTUSD)) shouldBe false
            base.isPairAliasDuplicateOf(alias(base.copy(success = false))) shouldBe false
            base.isPairAliasDuplicateOf(alias(base.copy(dryRun = true))) shouldBe false
            base.isPairAliasDuplicateOf(alias(base.copy(volume = BigDecimal("1.02")))) shouldBe false
            base.isPairAliasDuplicateOf(alias(base.copy(usdAmount = BigDecimal("51000.00")))) shouldBe false
            base.isPairAliasDuplicateOf(alias(base.copy(fee = BigDecimal("101.00")))) shouldBe false
            base.isPairAliasDuplicateOf(
                alias(base.copy(source = TradeSource.LOCAL_ESTIMATE, slippagePercent = BigDecimal.ZERO)),
            ) shouldBe true
            base.isPairAliasDuplicateOf(alias(base)) shouldBe true
        }
    }
}
