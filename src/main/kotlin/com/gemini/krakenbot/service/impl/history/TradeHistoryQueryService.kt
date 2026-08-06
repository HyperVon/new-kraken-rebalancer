package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.HistoryStats
import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.RebalancerComparison
import com.gemini.krakenbot.model.RewardsOverTime
import com.gemini.krakenbot.model.RewardsOverTimePoint
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.repository.LedgerRepository
import com.gemini.krakenbot.repository.PortfolioStatsRepository
import com.gemini.krakenbot.repository.TradeRepository
import com.gemini.krakenbot.util.PrecisionConstants
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

class TradeHistoryQueryService(
    private val repository: TradeRepository,
    private val portfolioStatsRepository: PortfolioStatsRepository,
    private val ledgerRepository: LedgerRepository,
) {
    suspend fun getHistory(): List<PortfolioSnapshot> = repository.load()

    suspend fun getLatestSnapshot(): PortfolioSnapshot? = repository.load().firstOrNull()

    suspend fun getSnapshotsInRange(from: Instant, to: Instant): List<PortfolioSnapshot> =
        repository.getSnapshotsInRange(from, to)

    suspend fun getTradesInRange(from: Instant, to: Instant): List<TradeRecord> = repository.getTradesInRange(from, to)

    suspend fun getLedgersInRange(from: Instant, to: Instant): List<LedgerEvent> =
        ledgerRepository.getLedgersInRange(from, to)

    suspend fun getHistoryStats(): HistoryStats = getHistoryStats(Instant.EPOCH, Instant.now())

    suspend fun getRebalancerComparison(from: Instant, to: Instant): RebalancerComparison {
        val snapshots = getSnapshotsInRange(from, to)
        if (snapshots.size < 2) {
            return RebalancerComparisonCalculator.calculate(snapshots, emptyList())
        }
        val firstTimestamp = snapshots.minOf { it.timestamp }
        val lastTimestamp = snapshots.maxOf { it.timestamp }
        val trades = getTradesInRange(firstTimestamp, lastTimestamp)
        val rewards =
            ledgerRepository
                .getLedgersInRange(firstTimestamp, lastTimestamp)
                .filter { it.type == LedgerEvent.TYPE_STAKING }
        return RebalancerComparisonCalculator.calculate(snapshots, trades, rewards)
    }

    suspend fun getRewardsOverTime(from: Instant, to: Instant): RewardsOverTime {
        val snapshots = getSnapshotsInRange(from, to).sortedBy { it.timestamp }
        val stakingEvents =
            ledgerRepository
                .getLedgersInRange(from, to)
                .filter { it.type == LedgerEvent.TYPE_STAKING }
                .sortedBy { it.time }
        val cumulativeByAsset = mutableMapOf<String, BigDecimal>()
        var eventIndex = 0
        val points = snapshots.map { snapshot ->
            while (eventIndex < stakingEvents.size && stakingEvents[eventIndex].time <= snapshot.timestamp) {
                val event = stakingEvents[eventIndex]
                val symbol = event.asset.uppercase()
                cumulativeByAsset[symbol] = (cumulativeByAsset[symbol] ?: BigDecimal.ZERO).add(event.amount)
                eventIndex++
            }
            var cumulativeUSD = BigDecimal.ZERO
            val perAssetUSD = mutableMapOf<String, BigDecimal>()
            for ((symbol, cumulative) in cumulativeByAsset) {
                val price =
                    if (symbol == Asset.USD) BigDecimal.ONE else snapshot.assets[symbol]?.price ?: continue
                val valueUSD = cumulative.multiply(price).setScale(PrecisionConstants.SCALE_USD, RoundingMode.HALF_UP)
                perAssetUSD[symbol] = valueUSD
                cumulativeUSD = cumulativeUSD.add(valueUSD)
            }
            RewardsOverTimePoint(
                timestamp = snapshot.timestamp,
                cumulativeUSD = cumulativeUSD.setScale(PrecisionConstants.SCALE_USD, RoundingMode.HALF_UP),
                perAssetUSD = perAssetUSD,
            )
        }
        val totalRewardsUSD =
            points.lastOrNull()?.cumulativeUSD
                ?: BigDecimal.ZERO.setScale(PrecisionConstants.SCALE_USD, RoundingMode.HALF_UP)
        return RewardsOverTime(totalRewardsUSD = totalRewardsUSD, points = points)
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
