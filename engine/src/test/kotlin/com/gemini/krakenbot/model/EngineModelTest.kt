package com.gemini.krakenbot.model

import com.gemini.krakenbot.api.toApiDto
import com.gemini.krakenbot.domain.EngineTestFixtures
import com.gemini.krakenbot.domain.OrderResult
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
                    dryRun = true,
                    orderTxid = "TX-1",
                )
            (successResult as OrderResult.Success).orderTxid shouldBe "TX-1"
            successResult.pair shouldBe "XBTUSD"
            successResult.side shouldBe "BUY"
            successResult.volume.shouldBeEqualComparingTo(BigDecimal.ONE)
            successResult.dryRun shouldBe true

            val failureResult =
                OrderResult(
                    success = false,
                    pair = "XXBTZUSD",
                    side = "SELL",
                    volume = BigDecimal.TEN,
                    errorMessage = "Insufficient funds",
                    submissionUncertain = true,
                )
            failureResult.success shouldBe false
            (failureResult as OrderResult.Failure).submissionUncertain shouldBe true
            failureResult.pair shouldBe "XXBTZUSD"
            failureResult.side shouldBe "SELL"
            failureResult.volume.shouldBeEqualComparingTo(BigDecimal.TEN)
            failureResult.dryRun shouldBe false

            val defaultFailure =
                OrderResult(
                    success = false,
                    pair = "XBTUSD",
                    side = "BUY",
                    volume = BigDecimal.ONE,
                )
            defaultFailure.success shouldBe false
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

        "OrderSubmissionState enum values" {
            OrderSubmissionState.PENDING.name shouldBe "PENDING"
            OrderSubmissionState.UNCERTAIN.name shouldBe "UNCERTAIN"
            OrderSubmissionState.values().size shouldBe 2
        }

        "isPairAliasDuplicateOf guards trade-id conflicts and provenance differences" {
            val base =
                EngineTestFixtures.tradeRecord(
                    usdAmount = BigDecimal("50000.00"),
                    price = BigDecimal("50000.00"),
                    fee = BigDecimal("80.00"),
                )
            val alias = base.copy(pair = "XXBTZUSD", id = 2)

            base.copy(tradeId = "T-1").isPairAliasDuplicateOf(alias.copy(tradeId = "T-2")) shouldBe false
            base.copy(tradeId = "T-1").isPairAliasDuplicateOf(alias.copy(tradeId = "T-1")) shouldBe true
            base.copy(source = TradeSource.LEGACY_UNKNOWN).isPairAliasDuplicateOf(alias.copy(tradeId = "T-3")) shouldBe
                false
            base.copy(
                fee = BigDecimal("80.50"),
            ).isPairAliasDuplicateOf(alias.copy(source = TradeSource.API_FILL)) shouldBe
                true
        }

        "OrderResult defaults keep pre-submission outcomes unclaimed" {
            val pendingSuccess = OrderResult.Success("XBTUSD", "BUY", BigDecimal.ONE)
            pendingSuccess.dryRun shouldBe false
            pendingSuccess.orderTxid shouldBe null
            pendingSuccess.submissionUncertain shouldBe false

            val submittedFailure = OrderResult.Failure(
                "XXBTZUSD",
                "SELL",
                BigDecimal.ONE,
                errorMessage = "Insufficient funds",
            )
            submittedFailure.orderTxid shouldBe null
            submittedFailure.submissionUncertain shouldBe false
            submittedFailure.errorMessage shouldBe "Insufficient funds"
        }

        "generated wire mapping carries snapshot aggregates to the API boundary" {
            val snapshot = PortfolioSnapshot(
                timestamp = Instant.parse("2026-08-21T00:00:00Z"),
                totalValueUSD = BigDecimal("1000.00"),
                assets = mapOf(
                    "BTC" to PortfolioSnapshot.AssetSnapshot(
                        symbol = "BTC",
                        balance = BigDecimal("0.02"),
                        price = BigDecimal("50000.00"),
                        valueUSD = BigDecimal("1000.00"),
                        targetPercent = BigDecimal("50.00"),
                        currentPercent = BigDecimal("100.00"),
                        deviationPercent = BigDecimal("50.00"),
                        deviationUSD = BigDecimal("500.00"),
                    ),
                ),
                actions = listOf("BUY BTC Volume: 0.1 Cost: \$5000.00"),
                drawdownPercent = BigDecimal("12.50"),
                fiatDeploymentPercent = BigDecimal("25.00"),
                effectiveUsdTargetPercent = BigDecimal("40.00"),
            )

            val dto = snapshot.toApiDto()
            dto.timestamp shouldBe "2026-08-21T00:00:00Z"
            dto.totalValueUSD shouldBe "1000.00"
            dto.assets.keys shouldBe setOf("BTC")
            dto.actions shouldBe listOf("BUY BTC Volume: 0.1 Cost: \$5000.00")
            dto.drawdownPercent shouldBe "12.50"
            dto.fiatDeploymentPercent shouldBe "25.00"
            dto.effectiveUsdTargetPercent shouldBe "40.00"
        }

        "generated wire mapping carries trade identity for history endpoints" {
            val trade = EngineTestFixtures.tradeRecord(id = 7, tradeId = "TTXN-1")

            val dto = trade.toApiDto()
            dto.id shouldBe 7
            dto.pair shouldBe "XBTUSD"
            dto.source shouldBe "LOCAL_ESTIMATE"
        }
    }
}
