package com.gemini.krakenbot.model

import com.gemini.krakenbot.domain.EngineTestFixtures
import com.gemini.krakenbot.domain.OrderResult
import com.gemini.krakenbot.domain.PortfolioCalculations
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant

class EngineModelTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
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
                EngineTestFixtures.tradeRecord(
                    timestamp = now,
                    pair = "XBTUSD",
                    side = "BUY",
                    symbol = "BTC",
                    volume = BigDecimal.ONE,
                    usdAmount = BigDecimal("50000.00"),
                    id = 1,
                    fee = BigDecimal("10.00"),
                )
            val t2 =
                EngineTestFixtures.tradeRecord(
                    timestamp = now,
                    pair = "XXBTZUSD",
                    side = "BUY",
                    symbol = "BTC",
                    volume = BigDecimal.ONE,
                    usdAmount = BigDecimal("50000.00"),
                    id = 2,
                    fee = BigDecimal("100.00"),
                )
            val t3 =
                EngineTestFixtures.tradeRecord(
                    timestamp = now.plusSeconds(300),
                    pair = "XDGUSD",
                    side = "SELL",
                    symbol = "DOGE",
                    volume = BigDecimal.TEN,
                    usdAmount = BigDecimal("10.00"),
                    id = 3,
                    fee = BigDecimal("0.10"),
                )

            t1.isSameSymbolAndSide(t2) shouldBe true
            t1.isSameSymbolAndSide(t3) shouldBe false

            // Different fees with identical provenance -> not an alias duplicate
            t1.isPairAliasDuplicateOf(t2) shouldBe false
            t1.isPairAliasDuplicateOf(t3) shouldBe false
            t1.copy(fee = t2.fee).isPairAliasDuplicateOf(t2) shouldBe true

            t1.feePercentDiffersMateriallyFrom(t2) shouldBe true

            val zeroAmount = t1.copy(usdAmount = BigDecimal.ZERO)
            zeroAmount.feePercentDiffersMateriallyFrom(t2) shouldBe false

            t1.isMatchingApiTrade(t2, listOf("BTC", "DOGE")) shouldBe true
            t1.copy(dryRun = true).isMatchingApiTrade(t2, listOf("BTC", "DOGE")) shouldBe false
        }

        "isMatchingApiTrade rejects when volume within tolerance but USD differs by more than 1 percent" {
            val now = Instant.now()
            val local =
                EngineTestFixtures.tradeRecord(
                    timestamp = now,
                    pair = "XBTUSD",
                    side = "BUY",
                    symbol = "BTC",
                    volume = BigDecimal("1.0"),
                    usdAmount = BigDecimal("100.00"),
                )
            val api =
                EngineTestFixtures.tradeRecord(
                    timestamp = now,
                    pair = "XXBTZUSD",
                    side = "BUY",
                    symbol = "BTC",
                    volume = BigDecimal("1.005"),
                    usdAmount = BigDecimal("110.00"),
                )
            local.isMatchingApiTrade(api, listOf("BTC", "DOGE")) shouldBe false
            local.isMatchingApiTrade(api.copy(volume = BigDecimal("1.0")), listOf("BTC", "DOGE")) shouldBe true
        }

        "pair alias matching rejects conflicting trade identity and economics" {
            val now = Instant.now()
            val base = EngineTestFixtures.tradeRecord(
                timestamp = now,
                pair = "XBTUSD",
                side = "BUY",
                symbol = "BTC",
                volume = BigDecimal.ONE,
                usdAmount = BigDecimal("50000.00"),
                price = BigDecimal("50000.00"),
                fee = BigDecimal("100.00"),
                source = TradeSource.API_FILL,
            )
            fun alias(record: TradeRecord) = record.copy(pair = "XXBTZUSD")

            base.copy(tradeId = "fill-a").isPairAliasDuplicateOf(alias(base.copy(tradeId = "fill-b"))) shouldBe false
            base.copy(tradeId = "fill-a").isPairAliasDuplicateOf(alias(base.copy(tradeId = "fill-a"))) shouldBe true
            base.copy(tradeId = "fill-a").isPairAliasDuplicateOf(
                alias(base.copy(source = TradeSource.LEGACY_UNKNOWN)),
            ) shouldBe false
            base.copy(source = TradeSource.LEGACY_UNKNOWN).isPairAliasDuplicateOf(
                alias(base.copy(tradeId = "fill-b")),
            ) shouldBe false
            base.isPairAliasDuplicateOf(alias(base.copy(symbol = "DOGE"))) shouldBe false
            base.isPairAliasDuplicateOf(base.copy(pair = "XBTUSD")) shouldBe false
            base.isPairAliasDuplicateOf(alias(base.copy(success = false))) shouldBe false
            base.isPairAliasDuplicateOf(alias(base.copy(dryRun = true))) shouldBe false
            base.isPairAliasDuplicateOf(alias(base.copy(volume = BigDecimal("1.02")))) shouldBe false
            base.isPairAliasDuplicateOf(alias(base.copy(usdAmount = BigDecimal("51000.00")))) shouldBe false
            base.isPairAliasDuplicateOf(alias(base.copy(fee = BigDecimal("101.00")))) shouldBe false
            base.isPairAliasDuplicateOf(alias(base.copy(price = BigDecimal("49000.00")))) shouldBe false
            base.isPairAliasDuplicateOf(
                alias(base.copy(source = TradeSource.LOCAL_ESTIMATE, slippagePercent = BigDecimal.ZERO)),
            ) shouldBe true
            base.isPairAliasDuplicateOf(alias(base)) shouldBe true
        }

        "tradeRecord provenance and effectiveSource inference" {
            val now = Instant.now()
            val withExplicitSource = EngineTestFixtures.tradeRecord(source = TradeSource.API_FILL)
            withExplicitSource.effectiveSource() shouldBe TradeSource.API_FILL
            withExplicitSource.isSettledApiFill() shouldBe true
            withExplicitSource.isLocalEstimate() shouldBe false
            withExplicitSource.isLegacyUnknown() shouldBe false

            val withSlippage = EngineTestFixtures.tradeRecord(
                source = null,
                slippagePercent = BigDecimal("0.10"),
            )
            withSlippage.effectiveSource() shouldBe TradeSource.LOCAL_ESTIMATE
            withSlippage.isLocalEstimate() shouldBe true

            val legacySuccess = EngineTestFixtures.tradeRecord(
                source = null,
                slippagePercent = null,
                success = true,
                dryRun = false,
                errorMessage = null,
            )
            legacySuccess.effectiveSource() shouldBe TradeSource.LEGACY_UNKNOWN
            legacySuccess.isLegacyUnknown() shouldBe true

            val failureNoSource = EngineTestFixtures.tradeRecord(
                source = null,
                slippagePercent = null,
                success = false,
                errorMessage = "Failed",
            )
            failureNoSource.effectiveSource() shouldBe null

            val localEst = EngineTestFixtures.tradeRecord(source = TradeSource.LOCAL_ESTIMATE)
            val apiFill = EngineTestFixtures.tradeRecord(source = TradeSource.API_FILL)
            localEst.hasDifferentTradeProvenanceFrom(apiFill) shouldBe true
            apiFill.hasDifferentTradeProvenanceFrom(localEst) shouldBe true
            localEst.hasDifferentTradeProvenanceFrom(localEst) shouldBe false
        }

        "isLocalEstimateDuplicateOf tests" {
            val now = Instant.now()
            val t1 = EngineTestFixtures.tradeRecord(
                timestamp = now,
                pair = "XBTUSD",
                side = "BUY",
                symbol = "BTC",
                volume = BigDecimal.ONE,
                usdAmount = BigDecimal("50000.00"),
            )
            val t2 = t1.copy(timestamp = now.plusMillis(5000))
            val tFar = t1.copy(timestamp = now.plusMillis(20000))
            val tDiffPair = t1.copy(symbol = "ETH", pair = "ETHUSD")
            val tDiffVol = t1.copy(volume = BigDecimal("2.0"))
            val tDiffUsd = t1.copy(usdAmount = BigDecimal("100000.00"))

            t1.isLocalEstimateDuplicateOf(t2) shouldBe true
            t1.isLocalEstimateDuplicateOf(tFar) shouldBe false
            t1.isLocalEstimateDuplicateOf(tDiffPair) shouldBe false
            t1.isLocalEstimateDuplicateOf(tDiffVol) shouldBe false
            t1.isLocalEstimateDuplicateOf(tDiffUsd) shouldBe false
        }

        "isMatchingApiTrade tests all rejection branches" {
            val now = Instant.now()
            val local = EngineTestFixtures.tradeRecord(
                timestamp = now,
                pair = "XBTUSD",
                side = "BUY",
                symbol = "BTC",
                volume = BigDecimal("1.0"),
                usdAmount = BigDecimal("50000.00"),
                dryRun = false,
            )
            val api = local.copy(pair = "XXBTZUSD", source = TradeSource.API_FILL)

            // Dry run rejected
            local.copy(dryRun = true).isMatchingApiTrade(api, listOf("BTC")) shouldBe false

            // Time difference > 10s
            local.copy(timestamp = now.minusMillis(15000)).isMatchingApiTrade(api, listOf("BTC")) shouldBe false

            // Side mismatch
            local.copy(side = "SELL").isMatchingApiTrade(api, listOf("BTC")) shouldBe false

            // Symbol mismatch
            local.copy(symbol = "ETH", pair = "ETHUSD").isMatchingApiTrade(api, listOf("BTC", "ETH")) shouldBe false

            // Volume outside tolerance
            local.copy(volume = BigDecimal("1.5")).isMatchingApiTrade(api, listOf("BTC")) shouldBe false

            // Matching with exact volume and differing USD within tolerance
            local.isMatchingApiTrade(api.copy(usdAmount = BigDecimal("50100.00")), listOf("BTC")) shouldBe true
        }

        "feePercentDiffersMateriallyFrom edge cases" {
            val now = Instant.now()
            val t1 = EngineTestFixtures.tradeRecord(
                timestamp = now,
                usdAmount = BigDecimal("1000.00"),
                fee = BigDecimal("2.00"),
            )
            val tZeroUsd = t1.copy(usdAmount = BigDecimal.ZERO)
            val tCloseFee = t1.copy(fee = BigDecimal("2.50"))
            val tDiffFee = t1.copy(fee = BigDecimal("10.00"))

            t1.feePercentDiffersMateriallyFrom(tZeroUsd) shouldBe false
            tZeroUsd.feePercentDiffersMateriallyFrom(t1) shouldBe false
            t1.feePercentDiffersMateriallyFrom(tCloseFee) shouldBe false
            t1.feePercentDiffersMateriallyFrom(tDiffFee) shouldBe true
        }

        "AssetSnapshot companion invoke factory" {
            val snapshot = PortfolioSnapshot.AssetSnapshot(
                symbol = "BTC",
                balance = BigDecimal("1.5"),
                price = BigDecimal("50000.00"),
                valueUSD = BigDecimal("75000.00"),
                targetPercent = BigDecimal("50.00"),
                currentPercent = BigDecimal("60.00"),
                deviationPercent = BigDecimal("10.00"),
                deviationUSD = BigDecimal("5000.00"),
            )
            snapshot.symbol.value shouldBe "BTC"
            snapshot.balance.shouldBeEqualComparingTo(BigDecimal("1.5"))
        }

        "AssetMetrics properties and components" {
            val metrics = PortfolioCalculations.AssetMetrics(
                symbol = Asset("BTC"),
                baseTargetPercent = BigDecimal("50.00"),
                calcTargetPercent = BigDecimal("50.00"),
                currentPercent = BigDecimal("60.00"),
                deviationUSD = BigDecimal("1000.00"),
                deviationPercent = BigDecimal("20.00"),
                targetValueUSD = BigDecimal("5000.00"),
                isSignificant = true,
            )
            metrics.symbol.value shouldBe "BTC"
            metrics.baseTargetPercent.shouldBeEqualComparingTo(BigDecimal("50.00"))
            metrics.calcTargetPercent.shouldBeEqualComparingTo(BigDecimal("50.00"))
            metrics.currentPercent.shouldBeEqualComparingTo(BigDecimal("60.00"))
            metrics.deviationUSD.shouldBeEqualComparingTo(BigDecimal("1000.00"))
            metrics.deviationPercent.shouldBeEqualComparingTo(BigDecimal("20.00"))
            metrics.targetValueUSD.shouldBeEqualComparingTo(BigDecimal("5000.00"))
            metrics.isSignificant shouldBe true

            val (s, btp, ctp, cp, du, dp, tv, isSig) = metrics
            s.value shouldBe "BTC"
            isSig shouldBe true
            metrics.toString().isNotEmpty() shouldBe true
            metrics.hashCode() shouldBe metrics.hashCode()
        }

        "TradeRecord data class methods and components" {
            val now = Instant.now()
            val trade = EngineTestFixtures.tradeRecord(timestamp = now, id = 42)
            trade.id shouldBe 42
            trade.component1() shouldBe now
            trade.component2() shouldBe "XBTUSD"
            trade.component3() shouldBe "BUY"
            trade.component4() shouldBe "BTC"
            trade.component5() shouldBe BigDecimal.ONE
            trade.component6() shouldBe BigDecimal("50000.00")
            trade.component7() shouldBe true
            trade.component8() shouldBe false
            trade.toString().isNotEmpty() shouldBe true
            trade.hashCode() shouldBe trade.hashCode()
        }

        "OrderSubmissionState enum values" {
            OrderSubmissionState.PENDING.name shouldBe "PENDING"
            OrderSubmissionState.UNCERTAIN.name shouldBe "UNCERTAIN"
            OrderSubmissionState.values().size shouldBe 2
        }

        "portfolio snapshot properties and instantiation" {
            val now = Instant.now()
            val btcSnapshot = PortfolioSnapshot.AssetSnapshot(
                symbol = "BTC",
                balance = BigDecimal("0.1"),
                price = BigDecimal("60000.00"),
                valueUSD = BigDecimal("6000.00"),
                targetPercent = BigDecimal("50.00"),
                currentPercent = BigDecimal("60.00"),
                deviationPercent = BigDecimal("10.00"),
                deviationUSD = BigDecimal("1000.00"),
            )
            val snap = PortfolioSnapshot(
                timestamp = now,
                totalValueUSD = BigDecimal("10000.00"),
                assets = mapOf(
                    "BTC" to btcSnapshot,
                ),
                actions = listOf("BUY BTC Volume: 0.1 Cost: $5000.00"),
                drawdownPercent = BigDecimal("5.00"),
                fiatDeploymentPercent = BigDecimal("10.00"),
                effectiveUsdTargetPercent = BigDecimal("15.00"),
            )
            snap.timestamp shouldBe now
            snap.totalValueUSD.shouldBeEqualComparingTo(BigDecimal("10000.00"))
            snap.assets.size shouldBe 1
            snap.actions.size shouldBe 1
            snap.drawdownPercent.shouldBeEqualComparingTo(BigDecimal("5.00"))
            snap.fiatDeploymentPercent.shouldBeEqualComparingTo(BigDecimal("10.00"))
            snap.effectiveUsdTargetPercent.shouldBeEqualComparingTo(BigDecimal("15.00"))
            snap.assets["BTC"]?.symbol?.value shouldBe "BTC"
            snap.assets["BTC"]?.balance?.shouldBeEqualComparingTo(BigDecimal("0.1"))
            snap.assets["BTC"]?.price?.shouldBeEqualComparingTo(BigDecimal("60000.00"))
            snap.assets["BTC"]?.valueUSD?.shouldBeEqualComparingTo(BigDecimal("6000.00"))
            snap.assets["BTC"]?.targetPercent?.shouldBeEqualComparingTo(BigDecimal("50.00"))
            snap.assets["BTC"]?.currentPercent?.shouldBeEqualComparingTo(BigDecimal("60.00"))
            snap.assets["BTC"]?.deviationPercent?.shouldBeEqualComparingTo(BigDecimal("10.00"))
            snap.assets["BTC"]?.deviationUSD?.shouldBeEqualComparingTo(BigDecimal("1000.00"))

            val copySnap = snap.copy(totalValueUSD = BigDecimal("12000.00"))
            copySnap.totalValueUSD.shouldBeEqualComparingTo(BigDecimal("12000.00"))
            (snap == copySnap) shouldBe false
            snap.hashCode() shouldBe snap.hashCode()
            snap.toString().isNotEmpty() shouldBe true
        }

        "OrderResult property accessors" {
            val success = OrderResult.Success(
                pair = "XBTUSD",
                side = "BUY",
                volume = BigDecimal.ONE,
                dryRun = false,
                orderTxid = "tx-123",
            )
            success.pair shouldBe "XBTUSD"
            success.side shouldBe "BUY"
            success.volume shouldBe BigDecimal.ONE
            success.dryRun shouldBe false
            success.orderTxid shouldBe "tx-123"
            success.success shouldBe true
            success.errorMessage shouldBe null
            success.submissionUncertain shouldBe false

            val failure = OrderResult.Failure(
                pair = "XETHZUSD",
                side = "SELL",
                volume = BigDecimal.TEN,
                dryRun = true,
                errorMessage = "Rate limit",
                orderTxid = null,
                submissionUncertain = true,
            )
            failure.pair shouldBe "XETHZUSD"
            failure.side shouldBe "SELL"
            failure.volume shouldBe BigDecimal.TEN
            failure.dryRun shouldBe true
            failure.errorMessage shouldBe "Rate limit"
            failure.orderTxid shouldBe null
            failure.submissionUncertain shouldBe true
            failure.success shouldBe false
        }
    }
}
