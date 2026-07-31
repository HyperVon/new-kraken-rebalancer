package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.model.HistoryStats
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.RebalancerComparison
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.repository.PortfolioStatsRepository
import com.gemini.krakenbot.repository.TradeRepository
import java.math.BigDecimal
import java.time.Instant

class TradeHistoryQueryService(
    private val repository: TradeRepository,
    private val portfolioStatsRepository: PortfolioStatsRepository,
) {
    suspend fun getHistory(): List<PortfolioSnapshot> = repository.load()

    suspend fun getLatestSnapshot(): PortfolioSnapshot? = repository.load().firstOrNull()

    suspend fun getSnapshotsInRange(from: Instant, to: Instant): List<PortfolioSnapshot> =
        repository.getSnapshotsInRange(from, to)

    suspend fun getTradesInRange(from: Instant, to: Instant): List<TradeRecord> = repository.getTradesInRange(from, to)

    suspend fun getHistoryStats(): HistoryStats = getHistoryStats(Instant.EPOCH, Instant.now())

    suspend fun getRebalancerComparison(from: Instant, to: Instant): RebalancerComparison {
        val snapshots = getSnapshotsInRange(from, to)
        if (snapshots.size < 2) {
            return RebalancerComparisonCalculator.calculate(snapshots, emptyList())
        }
        val firstTimestamp = snapshots.minOf { it.timestamp }
        val lastTimestamp = snapshots.maxOf { it.timestamp }
        val trades = getTradesInRange(firstTimestamp, lastTimestamp)
        return RebalancerComparisonCalculator.calculate(snapshots, trades)
    }

    suspend fun getHistoryStats(from: Instant, to: Instant): HistoryStats {
        val stats = portfolioStatsRepository.load()
        val summary = if (from ==
            Instant.EPOCH
        ) {
            repository.getTradeSummaryStats()
        } else {
            repository.getTradeSummaryStats(from, to)
        }
        val ath =
            if (from == Instant.EPOCH) {
                val snapshotMax = summary.periodHigh ?: BigDecimal.ZERO
                if (stats.allTimeHigh > snapshotMax) stats.allTimeHigh else snapshotMax
            } else {
                summary.periodHigh ?: BigDecimal.ZERO
            }
        return HistoryStats(
            allTimeHigh = ath,
            totalTradesExecuted = summary.totalTradesExecuted,
            totalVolumeTraded = summary.totalVolumeTraded,
            totalFeesPaid = summary.totalFeesPaid,
            latestSnapshotTime = summary.latestSnapshotTime,
            avgFeeRatePercent = summary.avgFeeRatePercent,
            avgSlippagePercent = summary.avgSlippagePercent,
            failedTradeCount = summary.failedTradeCount,
            dryRunTradeCount = summary.dryRunTradeCount,
        )
    }
}
