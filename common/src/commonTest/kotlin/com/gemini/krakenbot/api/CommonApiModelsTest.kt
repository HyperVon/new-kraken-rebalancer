package com.gemini.krakenbot.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CommonApiModelsTest {
    @Test
    fun tradeRecordAppliesOptionalFieldDefaults() {
        val record =
            TradeRecord(
                timestamp = "2024-01-01T00:00:00Z",
                pair = "XBTUSD",
                side = "buy",
                symbol = "BTC",
                volume = "0.1",
                usdAmount = "5000",
                success = true,
                dryRun = false,
            )
        assertEquals("0", record.price)
        assertEquals("0", record.fee)
        assertNull(record.errorMessage)
        assertNull(record.slippagePercent)
        assertNull(record.expectedPrice)
        assertNull(record.source)
        assertNull(record.id)
    }

    @Test
    fun historyStatsAppliesOptionalDefaults() {
        val stats =
            HistoryStats(
                allTimeHigh = "100000",
                totalTradesExecuted = 10,
                totalVolumeTraded = "100000",
                totalFeesPaid = "50",
                latestSnapshotTime = null,
            )
        assertEquals("0", stats.avgFeeRatePercent)
        assertEquals(0L, stats.failedTradeCount)
        assertEquals(0L, stats.dryRunTradeCount)
        assertNull(stats.avgSlippagePercent)
    }

    @Test
    fun historyStatsPreservesProvidedOptionalFields() {
        val stats =
            HistoryStats(
                allTimeHigh = "100000",
                totalTradesExecuted = 10,
                totalVolumeTraded = "100000",
                totalFeesPaid = "50",
                latestSnapshotTime = "2024-01-02T00:00:00Z",
                avgFeeRatePercent = "0.05",
                avgSlippagePercent = "0.2",
                failedTradeCount = 2,
                dryRunTradeCount = 3,
            )
        assertEquals("0.05", stats.avgFeeRatePercent)
        assertEquals("0.2", stats.avgSlippagePercent)
        assertEquals(2, stats.failedTradeCount)
        assertEquals(3, stats.dryRunTradeCount)
    }

    @Test
    fun portfolioSnapshotEqualityReflectsNestedAssetSnapshots() {
        val asset =
            PortfolioSnapshot.AssetSnapshot(
                symbol = "BTC",
                balance = "1",
                price = "50000",
                valueUSD = "50000",
                targetPercent = "50",
                currentPercent = "50",
                deviationPercent = "0",
                deviationUSD = "0",
            )
        val snapshot =
            PortfolioSnapshot(
                timestamp = "t",
                totalValueUSD = "50000",
                assets = mapOf("BTC" to asset),
                actions = listOf("rebalance"),
                drawdownPercent = "10",
                fiatDeploymentPercent = "20",
                effectiveUsdTargetPercent = "50",
            )
        val copy = snapshot.copy(totalValueUSD = "60000")
        assertEquals("60000", copy.totalValueUSD)
        assertEquals(snapshot.assets, copy.assets)
        assertEquals(asset, snapshot.assets["BTC"])
    }

    @Test
    fun rebalancerComparisonPreservesPointsAndNullableReason() {
        val point =
            RebalancerComparisonPoint(
                timestamp = "t",
                rebalancerValueUSD = "100",
                buyAndHoldValueUSD = "90",
                differenceUSD = "10",
                differencePercent = "11.1",
            )
        val comparison =
            RebalancerComparison(
                availability = "available",
                confidence = "high",
                baselineTimestamp = "b",
                points = listOf(point),
                latestDifferenceUSD = "10",
                latestDifferencePercent = "11.1",
                unavailableReason = null,
                unavailableAt = null,
            )
        assertEquals(1, comparison.points.size)
        assertEquals(point, comparison.points.first())
        assertNull(comparison.unavailableReason)
        assertNull(comparison.unavailableAt)
    }
}
