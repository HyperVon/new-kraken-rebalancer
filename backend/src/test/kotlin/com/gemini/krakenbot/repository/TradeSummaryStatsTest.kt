package com.gemini.krakenbot.repository

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.math.BigDecimal
import java.time.Instant

class TradeSummaryStatsTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "summary statistics retain value semantics across copies and destructuring" {
            val snapshotTime = Instant.parse("2026-10-11T12:00:00Z")
            val stats = TradeSummaryStats(
                totalTradesExecuted = 12,
                totalVolumeTraded = BigDecimal("1234.50"),
                totalFeesPaid = BigDecimal("2.50"),
                latestSnapshotTime = snapshotTime,
                periodHigh = BigDecimal("1500.00"),
                avgFeeRatePercent = BigDecimal("0.20"),
                avgSlippagePercent = BigDecimal("0.03"),
                failedTradeCount = 2,
                dryRunTradeCount = 3,
            )
            val (
                trades,
                volume,
                fees,
                latest,
                high,
                feeRate,
                slippage,
                failed,
                dryRun,
            ) = stats

            listOf(trades, volume, fees, latest, high, feeRate, slippage, failed, dryRun) shouldBe listOf(
                12L,
                BigDecimal("1234.50"),
                BigDecimal("2.50"),
                snapshotTime,
                BigDecimal("1500.00"),
                BigDecimal("0.20"),
                BigDecimal("0.03"),
                2L,
                3L,
            )
            stats.copy() shouldBe stats
            stats.copy(dryRunTradeCount = 4).dryRunTradeCount shouldBe 4L
            stats.hashCode() shouldBe stats.copy().hashCode()
            stats.toString() shouldContain "totalTradesExecuted=12"
        }

        "summary statistics retain defaults for fields added to older reports" {
            val stats = TradeSummaryStats(
                totalTradesExecuted = 0,
                totalVolumeTraded = BigDecimal.ZERO,
                totalFeesPaid = BigDecimal.ZERO,
                latestSnapshotTime = null,
            )

            stats.periodHigh shouldBe null
            stats.avgFeeRatePercent shouldBe BigDecimal.ZERO
            stats.avgSlippagePercent shouldBe null
            stats.failedTradeCount shouldBe 0L
            stats.dryRunTradeCount shouldBe 0L
        }
    }
}
