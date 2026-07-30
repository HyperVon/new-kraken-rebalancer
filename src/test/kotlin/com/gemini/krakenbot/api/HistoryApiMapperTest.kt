package com.gemini.krakenbot.api

import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.ComparisonAvailability
import com.gemini.krakenbot.model.ComparisonConfidence
import com.gemini.krakenbot.model.ComparisonUnavailableReason
import com.gemini.krakenbot.model.HistoryStats
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.RebalancerComparison
import com.gemini.krakenbot.model.RebalancerComparisonPoint
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.model.TradeSource
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant

class HistoryApiMapperTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "toApiDto maps TradeRecord with all fields" {
            val now = Instant.parse("2026-01-15T10:30:00Z")
            val trade = TradeRecord(
                timestamp = now,
                pair = "XBTUSD",
                side = "BUY",
                symbol = "BTC",
                volume = BigDecimal("0.12345678"),
                usdAmount = BigDecimal("5000.00"),
                success = true,
                dryRun = false,
                errorMessage = null,
                price = BigDecimal("40500.00"),
                fee = BigDecimal("5.00"),
                slippagePercent = BigDecimal("0.05"),
                expectedPrice = BigDecimal("40480.00"),
                source = TradeSource.API_FILL,
                id = 42,
                cycleId = "cycle-1",
                orderTxid = "OTX-123",
                tradeId = "T-456",
                clientOrderId = "CL-789",
                submissionState = null,
            )

            val dto = trade.toApiDto()

            dto.timestamp shouldBe "2026-01-15T10:30:00Z"
            dto.pair shouldBe "XBTUSD"
            dto.side shouldBe "BUY"
            dto.symbol shouldBe "BTC"
            dto.volume shouldBe "0.12345678"
            dto.usdAmount shouldBe "5000.00"
            dto.success shouldBe true
            dto.dryRun shouldBe false
            dto.errorMessage.shouldBeNull()
            dto.price shouldBe "40500.00"
            dto.fee shouldBe "5.00"
            dto.slippagePercent shouldBe "0.05"
            dto.expectedPrice shouldBe "40480.00"
            dto.source shouldBe "API_FILL"
            dto.id shouldBe 42
        }

        "toApiDto maps TradeRecord with null optional fields" {
            val trade = TradeRecord(
                timestamp = Instant.EPOCH,
                pair = "ETHUSD",
                side = "SELL",
                symbol = "ETH",
                volume = BigDecimal("1.0"),
                usdAmount = BigDecimal("2000.00"),
                success = true,
                dryRun = true,
            )

            val dto = trade.toApiDto()

            dto.slippagePercent.shouldBeNull()
            dto.expectedPrice.shouldBeNull()
            dto.source.shouldBeNull()
            dto.id.shouldBeNull()
        }

        "toApiDto maps HistoryStats with all fields" {
            val stats = HistoryStats(
                allTimeHigh = BigDecimal("75000.00"),
                totalTradesExecuted = 150L,
                totalVolumeTraded = BigDecimal("50000.00"),
                totalFeesPaid = BigDecimal("125.00"),
                latestSnapshotTime = Instant.parse("2026-01-15T12:00:00Z"),
                avgFeeRatePercent = BigDecimal("0.25"),
                avgSlippagePercent = BigDecimal("0.05"),
                failedTradeCount = 3L,
                dryRunTradeCount = 25L,
            )

            val dto = stats.toApiDto()

            dto.allTimeHigh shouldBe "75000.00"
            dto.totalTradesExecuted shouldBe 150L
            dto.totalVolumeTraded shouldBe "50000.00"
            dto.totalFeesPaid shouldBe "125.00"
            dto.latestSnapshotTime shouldBe "2026-01-15T12:00:00Z"
            dto.avgFeeRatePercent shouldBe "0.25"
            dto.avgSlippagePercent shouldBe "0.05"
            dto.failedTradeCount shouldBe 3L
            dto.dryRunTradeCount shouldBe 25L
        }

        "toApiDto maps HistoryStats with null latestSnapshotTime" {
            val stats = HistoryStats(
                allTimeHigh = BigDecimal("50000.00"),
                totalTradesExecuted = 10L,
                totalVolumeTraded = BigDecimal("10000.00"),
                totalFeesPaid = BigDecimal("25.00"),
                latestSnapshotTime = null,
            )

            val dto = stats.toApiDto()

            dto.latestSnapshotTime.shouldBeNull()
        }

        "toApiDto maps PortfolioSnapshot with assets" {
            val now = Instant.parse("2026-01-15T10:00:00Z")
            val snapshot = PortfolioSnapshot(
                timestamp = now,
                totalValueUSD = BigDecimal("10000.00"),
                assets = mapOf(
                    "BTC" to PortfolioSnapshot.AssetSnapshot(
                        symbol = Asset.BTC,
                        balance = BigDecimal("0.25"),
                        price = BigDecimal("40000.00"),
                        valueUSD = BigDecimal("10000.00"),
                        targetPercent = BigDecimal("50.0"),
                        currentPercent = BigDecimal("48.0"),
                        deviationPercent = BigDecimal("2.0"),
                        deviationUSD = BigDecimal("200.00"),
                    ),
                ),
                actions = listOf("BUY"),
                drawdownPercent = BigDecimal("5.0"),
                fiatDeploymentPercent = BigDecimal("25.0"),
                effectiveUsdTargetPercent = BigDecimal("20.0"),
            )

            val dto = snapshot.toApiDto()

            dto.timestamp shouldBe "2026-01-15T10:00:00Z"
            dto.totalValueUSD shouldBe "10000.00"
            dto.actions shouldBe listOf("BUY")
            dto.drawdownPercent shouldBe "5.0"
            dto.fiatDeploymentPercent shouldBe "25.0"
            dto.effectiveUsdTargetPercent shouldBe "20.0"
            dto.assets.keys shouldHaveSize 1
            val btc = dto.assets["BTC"]!!
            btc.symbol shouldBe "BTC"
            btc.balance shouldBe "0.25"
            btc.price shouldBe "40000.00"
            btc.valueUSD shouldBe "10000.00"
            btc.targetPercent shouldBe "50.0"
            btc.currentPercent shouldBe "48.0"
            btc.deviationPercent shouldBe "2.0"
            btc.deviationUSD shouldBe "200.00"
        }

        "buildSyncProgressResponse maps seeded with offset and total" {
            val response = buildSyncProgressResponse(
                seeded = true,
                offset = "100",
                total = "500",
            )

            response.seeded shouldBe true
            response.offset shouldBe "100"
            response.total shouldBe "500"
        }

        "buildSyncProgressResponse maps unseeded with null offset and total" {
            val response = buildSyncProgressResponse(
                seeded = false,
                offset = null,
                total = null,
            )

            response.seeded shouldBe false
            response.offset shouldBe ""
            response.total shouldBe ""
        }

        "toApiDto maps RebalancerComparison with points" {
            val now = Instant.parse("2026-01-15T10:00:00Z")
            val comparison = RebalancerComparison(
                availability = ComparisonAvailability.AVAILABLE,
                confidence = ComparisonConfidence.RECONCILED,
                baselineTimestamp = now,
                points = listOf(
                    RebalancerComparisonPoint(
                        timestamp = now,
                        rebalancerValueUSD = BigDecimal("10000.00"),
                        buyAndHoldValueUSD = BigDecimal("10000.00"),
                        differenceUSD = BigDecimal("0.00"),
                        differencePercent = BigDecimal("0.00"),
                    ),
                    RebalancerComparisonPoint(
                        timestamp = now.plusSeconds(3600),
                        rebalancerValueUSD = BigDecimal("10500.00"),
                        buyAndHoldValueUSD = BigDecimal("10200.00"),
                        differenceUSD = BigDecimal("300.00"),
                        differencePercent = BigDecimal("2.94"),
                    ),
                ),
                latestDifferenceUSD = BigDecimal("300.00"),
                latestDifferencePercent = BigDecimal("2.94"),
                unavailableReason = null,
                unavailableAt = null,
            )

            val dto = comparison.toApiDto()

            dto.availability shouldBe "AVAILABLE"
            dto.confidence shouldBe "RECONCILED"
            dto.baselineTimestamp shouldBe "2026-01-15T10:00:00Z"
            dto.points shouldHaveSize 2
            dto.points[0].timestamp shouldBe "2026-01-15T10:00:00Z"
            dto.points[0].rebalancerValueUSD shouldBe "10000.00"
            dto.points[0].buyAndHoldValueUSD shouldBe "10000.00"
            dto.points[0].differenceUSD shouldBe "0.00"
            dto.points[0].differencePercent shouldBe "0.00"
            dto.points[1].timestamp shouldBe "2026-01-15T11:00:00Z"
            dto.points[1].rebalancerValueUSD shouldBe "10500.00"
            dto.points[1].buyAndHoldValueUSD shouldBe "10200.00"
            dto.points[1].differenceUSD shouldBe "300.00"
            dto.points[1].differencePercent shouldBe "2.94"
            dto.latestDifferenceUSD shouldBe "300.00"
            dto.latestDifferencePercent shouldBe "2.94"
            dto.unavailableReason.shouldBeNull()
            dto.unavailableAt.shouldBeNull()
        }

        "toApiDto maps RebalancerComparison with unavailable status" {
            val comparison = RebalancerComparison(
                availability = ComparisonAvailability.UNAVAILABLE,
                confidence = null,
                baselineTimestamp = null,
                points = emptyList(),
                latestDifferenceUSD = null,
                latestDifferencePercent = null,
                unavailableReason = ComparisonUnavailableReason.INSUFFICIENT_SNAPSHOTS,
                unavailableAt = Instant.parse("2026-01-15T10:00:00Z"),
            )

            val dto = comparison.toApiDto()

            dto.availability shouldBe "UNAVAILABLE"
            dto.confidence.shouldBeNull()
            dto.baselineTimestamp.shouldBeNull()
            dto.points shouldHaveSize 0
            dto.latestDifferenceUSD.shouldBeNull()
            dto.latestDifferencePercent.shouldBeNull()
            dto.unavailableReason shouldBe "INSUFFICIENT_SNAPSHOTS"
            dto.unavailableAt shouldBe "2026-01-15T10:00:00Z"
        }
    }
}
