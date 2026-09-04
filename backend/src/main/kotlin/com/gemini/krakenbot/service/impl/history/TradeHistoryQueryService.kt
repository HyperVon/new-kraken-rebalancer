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
    private val inceptionDiscoveryService: InceptionDiscoveryService? = null,
) {
    suspend fun getHistory(): List<PortfolioSnapshot> = repository.load()

    suspend fun getLatestSnapshot(): PortfolioSnapshot? = repository.getLatestSnapshot()

    suspend fun getSnapshotsInRange(from: Instant, to: Instant): List<PortfolioSnapshot> =
        repository.getSnapshotsInRange(from, to)

    suspend fun getTradesInRange(from: Instant, to: Instant): List<TradeRecord> = repository.getTradesInRange(from, to)

    suspend fun getLedgersInRange(from: Instant, to: Instant): List<LedgerEvent> =
        ledgerRepository.getLedgersInRange(from, to)

    companion object {
        /**
         * Contribution-time prices must come from recorded snapshots near the
         * event. Six hours matches the historical reconstruction grid, so an
         * old contribution still finds its era's prices while a pruned era
         * fails closed instead of borrowing a modern price.
         */
        const val CONTRIBUTION_PRICE_LOOKUP_SECONDS = 21600L
    }

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

        val inceptionResolution = inceptionDiscoveryService?.resolveInception()
        if (inceptionResolution?.confidence == InceptionConfidence.TRUNCATED) {
            // Migrated install whose early history was removed by a previous
            // retention era: no window-anchored number may stand in for a
            // lifetime baseline. The UI text tells the user to configure
            // the inception date manually.
            return RebalancerComparisonCalculator.calculate(
                snapshots = orderedSnapshots,
                trades = emptyList(),
                rewards = emptyList(),
                knownRebalancerOrderTxids = emptySet(),
                anchorSnapshot = null,
                inceptionSnapshot = null,
                knownInceptionTime = inceptionResolution.inceptionTime,
                historyTruncated = true,
            )
        }
        val inceptionSnapshot = inceptionResolution?.inceptionSnapshot
            ?: inceptionResolution?.inceptionTime?.let { time ->
                // Bounded fallback: only accept a snapshot within the same
                // +/-300s discovery window used by InceptionDiscoveryService.
                // An unbounded getSnapshotBefore() here silently anchored the
                // benchmark to an unrelated months-old snapshot when inception
                // was known but its snapshot had been pruned.
                val candidates =
                    repository.getSnapshotsInRange(
                        time.minusSeconds(300),
                        time.plusSeconds(30),
                    )
                candidates.minByOrNull {
                    kotlin.math.abs(
                        it.timestamp.epochSecond - time.epochSecond,
                    )
                }
            }

        val anchorSnapshot = repository.getSnapshotBefore(firstTimestamp)
        val eventQueryStart = listOfNotNull(
            inceptionSnapshot?.balancesObservedAt ?: inceptionSnapshot?.timestamp,
            anchorSnapshot?.balancesObservedAt ?: anchorSnapshot?.timestamp,
            firstObservationTime,
        ).minOrNull() ?: firstObservationTime

        val queryFrom = eventQueryStart.minusMillisIfLegacyObservation(anchorSnapshot, firstSnapshot)
        val queryTo = maxOf(lastTimestamp, lastObservationTime)
            .plusMillis(RebalancerComparisonCalculator.MAX_EVENT_OBSERVATION_CLOCK_SKEW_MILLIS)

        val trades = getTradesInRange(queryFrom, queryTo)
        // Closed world: LedgersSyncService only fetches EXTERNAL_BALANCE_TYPES,
        // so unknown types cannot arrive here. LedgerFlowClassifier inside
        // RebalancerComparisonCalculator is the second layer: it replays the
        // margin-family in-kind and fails closed on anything unrecognized.
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
        // Contribution-time prices come only from recorded snapshots near the
        // event (never a live ticker for an old contribution). Absent prices
        // fail the comparison closed inside the calculator.
        val priceProvider = HistoricalPriceProvider { symbol, time ->
            if (symbol == Asset.USD) {
                BigDecimal.ONE
            } else {
                repository.getSnapshotsInRange(
                    time.minusSeconds(CONTRIBUTION_PRICE_LOOKUP_SECONDS),
                    time.plusSeconds(CONTRIBUTION_PRICE_LOOKUP_SECONDS),
                ).mapNotNull { snapshot ->
                    val price = snapshot.assets[symbol]?.price
                    if (price != null && price.signum() > 0) {
                        snapshot.timestamp to price
                    } else {
                        null
                    }
                }.minByOrNull { (timestamp, _) ->
                    kotlin.math.abs(timestamp.toEpochMilli() - time.toEpochMilli())
                }?.second
            }
        }
        return RebalancerComparisonCalculator.calculate(
            snapshots = orderedSnapshots,
            trades = trades,
            rewards = ledgers,
            knownRebalancerOrderTxids = knownRebalancerOrderTxids,
            anchorSnapshot = anchorSnapshot,
            inceptionSnapshot = inceptionSnapshot,
            knownInceptionTime = inceptionResolution?.inceptionTime,
            priceProvider = priceProvider,
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
