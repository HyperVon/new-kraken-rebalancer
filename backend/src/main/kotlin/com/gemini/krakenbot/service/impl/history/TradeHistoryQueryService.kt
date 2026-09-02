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
import com.gemini.krakenbot.repository.OrderIntentRepository
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
    private val orderIntentRepository: OrderIntentRepository? = null,
) {
    suspend fun getHistory(): List<PortfolioSnapshot> = repository.load()

    suspend fun getLatestSnapshot(): PortfolioSnapshot? = repository.getLatestSnapshot()

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
        val orderedSnapshots = snapshots.sortedBy { it.timestamp }
        val firstSnapshot = orderedSnapshots.first()
        val lastSnapshot = orderedSnapshots.last()
        val firstTimestamp = firstSnapshot.timestamp
        val lastTimestamp = lastSnapshot.timestamp
        val firstObservationTime = firstSnapshot.balancesObservedAt ?: firstTimestamp
        val lastObservationTime = lastSnapshot.balancesObservedAt ?: lastTimestamp

        val anchorSnapshot = repository.getSnapshotBefore(firstTimestamp)
        val queryFrom = minOf(
            anchorSnapshot?.balancesObservedAt ?: anchorSnapshot?.timestamp ?: firstObservationTime,
            firstObservationTime,
        ).minusMillisIfLegacyObservation(anchorSnapshot, firstSnapshot)
        val queryTo = maxOf(lastTimestamp, lastObservationTime)
            .plusMillis(RebalancerComparisonCalculator.MAX_EVENT_OBSERVATION_CLOCK_SKEW_MILLIS)

        val trades = getTradesInRange(queryFrom, queryTo)
        val ledgers =
            ledgerRepository
                .getLedgersInRange(queryFrom, queryTo)
                .filter { it.type in LedgerEvent.EXTERNAL_BALANCE_TYPES }
        val candidateTrades = trades.filter { it.success && !it.dryRun }
        val candidateOrderTxids = candidateTrades.mapNotNull {
            it.orderTxid?.trim()?.takeIf(String::isNotBlank)
        }.toSet()
        val candidateClientOrderIds = candidateTrades.mapNotNull {
            it.clientOrderId?.trim()?.takeIf(String::isNotBlank)
        }.toSet()
        val knownRebalancerOrderTxids = orderIntentRepository
            ?.getKnownRebalancerOrderIdentities(candidateOrderTxids, candidateClientOrderIds)
            ?.orderTxids
            .orEmpty()
        return RebalancerComparisonCalculator.calculate(
            snapshots = orderedSnapshots,
            trades = trades,
            rewards = ledgers,
            knownRebalancerOrderTxids = knownRebalancerOrderTxids,
            anchorSnapshot = anchorSnapshot,
        )
    }

    private fun Instant.minusMillisIfLegacyObservation(
        anchorSnapshot: PortfolioSnapshot?,
        firstSnapshot: PortfolioSnapshot,
    ): Instant = if ((anchorSnapshot != null && anchorSnapshot.balancesObservedAt == null) ||
        firstSnapshot.balancesObservedAt == null
    ) {
        minusMillis(RebalancerComparisonCalculator.MAX_EVENT_OBSERVATION_CLOCK_SKEW_MILLIS)
    } else {
        this
    }

    suspend fun getRewardsOverTime(from: Instant, to: Instant): RewardsOverTime {
        val snapshots = getSnapshotsInRange(from, to).sortedBy { it.timestamp }
        val rewardEvents =
            ledgerRepository
                .getLedgersInRange(from, to)
                .filter { it.type in LedgerEvent.REWARD_TYPES }
                .sortedBy { it.time }
        val cumulativeByAsset = mutableMapOf<String, BigDecimal>()
        var eventIndex = 0
        val points = snapshots.map { snapshot ->
            while (eventIndex < rewardEvents.size && rewardEvents[eventIndex].time <= snapshot.timestamp) {
                val event = rewardEvents[eventIndex]
                val symbol = Asset.normalizeLedgerAsset(event.asset).uppercase()
                if (symbol != Asset.USD) {
                    cumulativeByAsset[symbol] =
                        (cumulativeByAsset[symbol] ?: BigDecimal.ZERO).add(event.netBalanceDelta())
                }
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
