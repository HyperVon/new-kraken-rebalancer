package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.ComparisonAvailability
import com.gemini.krakenbot.model.ComparisonConfidence
import com.gemini.krakenbot.model.ComparisonUnavailableReason
import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.RebalancerComparison
import com.gemini.krakenbot.model.RebalancerComparisonPoint
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.util.PrecisionConstants
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

object RebalancerComparisonCalculator {
    private val baselineMismatchTolerance = BigDecimal("0.01")

    fun calculate(
        snapshots: List<PortfolioSnapshot>,
        trades: List<TradeRecord>,
        rewards: List<LedgerEvent> = emptyList(),
    ): RebalancerComparison {
        if (snapshots.size < 2) {
            val firstTime = snapshots.firstOrNull()?.timestamp
            return unavailable(
                reason = ComparisonUnavailableReason.INSUFFICIENT_SNAPSHOTS,
                unavailableAt = firstTime,
                baselineTimestamp = firstTime,
            )
        }
        val orderedSnapshots = snapshots.sortedBy(PortfolioSnapshot::timestamp)

        val baseline = orderedSnapshots.first()

        val universeError = validateAssetUniverse(orderedSnapshots, baseline)
        if (universeError != null) return universeError

        val baselineError = validateBaseline(baseline)
        if (baselineError != null) return baselineError

        val priceError = validatePrices(orderedSnapshots, baseline)
        if (priceError != null) return priceError

        val periodRewards = rewards.filter { it.type == LedgerEvent.TYPE_STAKING && it.time > baseline.timestamp }

        val balanceResult = validateTrackedBalanceChanges(orderedSnapshots, trades, periodRewards)
        if (balanceResult == null) {
            return unavailable(
                reason = ComparisonUnavailableReason.UNSUPPORTED_TRADE,
                unavailableAt = baseline.timestamp,
                baselineTimestamp = baseline.timestamp,
            )
        }

        val baselineBalances = extractBaselineBalances(baseline)

        val points = mutableListOf<RebalancerComparisonPoint>()
        for (snapshot in orderedSnapshots) {
            val buyAndHoldValue = calculateBuyAndHoldValue(baselineBalances, snapshot, periodRewards)
            if (buyAndHoldValue.signum() <= 0) {
                return unavailable(
                    reason = ComparisonUnavailableReason.NON_POSITIVE_BASELINE,
                    unavailableAt = snapshot.timestamp,
                    baselineTimestamp = baseline.timestamp,
                )
            }
            val rebalancerValue = snapshot.totalValueUSD
            val differenceUSD = rebalancerValue.subtract(buyAndHoldValue)
            val differencePercent = calculateDifferencePercent(differenceUSD, buyAndHoldValue)
            points += RebalancerComparisonPoint(
                timestamp = snapshot.timestamp,
                rebalancerValueUSD = rebalancerValue.setScale(PrecisionConstants.SCALE_USD, RoundingMode.HALF_UP),
                buyAndHoldValueUSD = buyAndHoldValue.setScale(PrecisionConstants.SCALE_USD, RoundingMode.HALF_UP),
                differenceUSD = differenceUSD.setScale(PrecisionConstants.SCALE_USD, RoundingMode.HALF_UP),
                differencePercent = differencePercent.setScale(PrecisionConstants.SCALE_PERCENT, RoundingMode.HALF_UP),
            )
        }

        val baselineFirstPoint = points.first()
        val firstDiffFromCalc = baselineFirstPoint.rebalancerValueUSD
            .subtract(baselineFirstPoint.buyAndHoldValueUSD)
            .abs()
        if (firstDiffFromCalc > baselineMismatchTolerance) {
            return unavailable(
                reason = ComparisonUnavailableReason.BASELINE_MISMATCH,
                unavailableAt = baseline.timestamp,
                baselineTimestamp = baseline.timestamp,
            )
        }

        val correctedPoints = points.mapIndexed { index, point ->
            if (index == 0) {
                point.copy(
                    rebalancerValueUSD = baseline.totalValueUSD.setScale(
                        PrecisionConstants.SCALE_USD,
                        RoundingMode.HALF_UP,
                    ),
                    buyAndHoldValueUSD = baseline.totalValueUSD.setScale(
                        PrecisionConstants.SCALE_USD,
                        RoundingMode.HALF_UP,
                    ),
                    differenceUSD = BigDecimal.ZERO.setScale(PrecisionConstants.SCALE_USD, RoundingMode.HALF_UP),
                    differencePercent = BigDecimal.ZERO.setScale(
                        PrecisionConstants.SCALE_PERCENT,
                        RoundingMode.HALF_UP,
                    ),
                )
            } else {
                point
            }
        }

        val latestDiffUSD = correctedPoints.last().differenceUSD
        val latestDiffPct = correctedPoints.last().differencePercent

        return RebalancerComparison(
            availability = ComparisonAvailability.AVAILABLE,
            confidence = if (balanceResult) ComparisonConfidence.RECONCILED else ComparisonConfidence.ESTIMATED,
            baselineTimestamp = baseline.timestamp,
            points = correctedPoints,
            latestDifferenceUSD = latestDiffUSD,
            latestDifferencePercent = latestDiffPct,
            unavailableReason = null,
            unavailableAt = null,
        )
    }

    private fun validateBaseline(baseline: PortfolioSnapshot): RebalancerComparison? {
        if (baseline.totalValueUSD <= BigDecimal.ZERO) {
            return unavailable(
                reason = ComparisonUnavailableReason.NON_POSITIVE_BASELINE,
                unavailableAt = baseline.timestamp,
                baselineTimestamp = baseline.timestamp,
            )
        }
        return null
    }

    private fun validateAssetUniverse(
        snapshots: List<PortfolioSnapshot>,
        baseline: PortfolioSnapshot,
    ): RebalancerComparison? {
        val baselineKeys = baseline.assets.keys
        for (snapshot in snapshots.drop(1)) {
            if (snapshot.assets.keys != baselineKeys) {
                return unavailable(
                    reason = ComparisonUnavailableReason.ASSET_UNIVERSE_CHANGED,
                    unavailableAt = snapshot.timestamp,
                    baselineTimestamp = baseline.timestamp,
                )
            }
        }
        return null
    }

    private fun validatePrices(
        snapshots: List<PortfolioSnapshot>,
        baseline: PortfolioSnapshot,
    ): RebalancerComparison? {
        val baselineBalances = extractBaselineBalances(baseline)
        for (snapshot in snapshots) {
            for ((symbol, startBalance) in baselineBalances) {
                if (startBalance.signum() == 0) continue
                if (symbol == Asset.USD) continue
                val assetRow = snapshot.assets[symbol] ?: return unavailable(
                    reason = ComparisonUnavailableReason.MISSING_PRICE,
                    unavailableAt = snapshot.timestamp,
                    baselineTimestamp = baseline.timestamp,
                )
                if (assetRow.price.signum() <= 0) {
                    return unavailable(
                        reason = ComparisonUnavailableReason.MISSING_PRICE,
                        unavailableAt = snapshot.timestamp,
                        baselineTimestamp = baseline.timestamp,
                    )
                }
            }
        }
        return null
    }

    /**
     * @return `true` if fully reconciled, `false` if balance mismatches found (ESTIMATED), `null` if a hard error
     *         (unsupported trade) makes the comparison impossible.
     */
    private fun validateTrackedBalanceChanges(
        snapshots: List<PortfolioSnapshot>,
        trades: List<TradeRecord>,
        rewards: List<LedgerEvent>,
    ): Boolean? {
        val successfulTrades = trades.filter { it.success && !it.dryRun }
        val stakingRewards = rewards.filter { it.type == LedgerEvent.TYPE_STAKING }
        var allReconciled = true

        for (i in 1 until snapshots.size) {
            val prev = snapshots[i - 1]
            val curr = snapshots[i]

            val intervalTrades = successfulTrades.filter { trade ->
                trade.timestamp > prev.timestamp && trade.timestamp <= curr.timestamp
            }
            val intervalRewards = stakingRewards.filter { event ->
                event.time > prev.timestamp && event.time <= curr.timestamp
            }

            val impliedBalances = prev.assets.mapValues { (_, asset) -> asset.balance }.toMutableMap()
            for (trade in intervalTrades) {
                if (!applyRealizedTrade(impliedBalances, trade)) return null
            }
            for (event in intervalRewards) {
                val symbol = event.asset.uppercase()
                if (symbol == Asset.USD || symbol !in impliedBalances) continue
                impliedBalances[symbol] = impliedBalances.getValue(symbol).add(event.amount)
            }

            for ((symbol, expectedBalance) in impliedBalances) {
                val actualBalance = curr.assets[symbol]?.balance ?: return null
                val scale = if (symbol == Asset.USD) {
                    PrecisionConstants.SCALE_USD
                } else {
                    PrecisionConstants.SCALE_CRYPTO
                }
                val roundedExpected = expectedBalance.setScale(scale, RoundingMode.HALF_UP)
                val roundedActual = actualBalance.setScale(scale, RoundingMode.HALF_UP)
                if (roundedExpected.compareTo(roundedActual) != 0) {
                    allReconciled = false
                }
            }
        }
        return allReconciled
    }

    private fun applyRealizedTrade(balances: MutableMap<String, BigDecimal>, trade: TradeRecord): Boolean {
        val side = trade.side.uppercase()
        val symbol = trade.symbol.uppercase()
        if (
            symbol == Asset.USD ||
            !Asset.matchesUsdQuotedPair(trade.pair, symbol) ||
            trade.volume.signum() < 0 ||
            trade.usdAmount.signum() < 0 ||
            trade.fee.signum() < 0
        ) {
            return false
        }
        if (Asset.USD !in balances) {
            return false
        }
        if (symbol !in balances) {
            return true
        }
        val usdBalance = balances[Asset.USD] ?: BigDecimal.ZERO
        val assetBalance = balances.getValue(symbol)

        when (side) {
            "BUY" -> {
                balances[symbol] = assetBalance.add(trade.volume)
                val usdCost = trade.usdAmount.add(trade.fee)
                balances[Asset.USD] = usdBalance.subtract(usdCost)
            }

            "SELL" -> {
                balances[symbol] = assetBalance.subtract(trade.volume)
                val usdProceeds = trade.usdAmount.subtract(trade.fee)
                balances[Asset.USD] = usdBalance.add(usdProceeds)
            }

            else -> return false
        }
        return true
    }

    private fun extractBaselineBalances(baseline: PortfolioSnapshot): Map<String, BigDecimal> =
        baseline.assets.mapValues { (_, asset) -> asset.balance }

    private fun calculateBuyAndHoldValue(
        baselineBalances: Map<String, BigDecimal>,
        snapshot: PortfolioSnapshot,
        rewards: List<LedgerEvent>,
    ): BigDecimal {
        val cumulativeRewards = cumulativeRewardsByAsset(rewards, snapshot.timestamp)
        var total = BigDecimal.ZERO
        for ((symbol, startBalance) in baselineBalances) {
            if (startBalance.signum() == 0) continue
            val price = if (symbol == Asset.USD) {
                BigDecimal.ONE
            } else {
                snapshot.assets[symbol]?.price
                    ?: error(
                        "Asset $symbol missing in snapshot ${snapshot.timestamp}; validatePrices should have caught this",
                    )
            }
            val rewardBalance = cumulativeRewards[symbol] ?: BigDecimal.ZERO
            val product = startBalance.add(rewardBalance).multiply(price)
            total = total.add(product)
        }
        return total
    }

    private fun cumulativeRewardsByAsset(rewards: List<LedgerEvent>, upTo: Instant): Map<String, BigDecimal> {
        val cumulative = mutableMapOf<String, BigDecimal>()
        for (event in rewards) {
            if (event.type != LedgerEvent.TYPE_STAKING || event.time > upTo) continue
            val symbol = event.asset.uppercase()
            cumulative[symbol] = (cumulative[symbol] ?: BigDecimal.ZERO).add(event.amount)
        }
        return cumulative
    }

    private fun calculateDifferencePercent(differenceUSD: BigDecimal, buyAndHoldValue: BigDecimal): BigDecimal =
        differenceUSD
            .multiply(BigDecimal(PrecisionConstants.HUNDRED_INT))
            .divide(buyAndHoldValue, PrecisionConstants.SCALE_PERCENT, RoundingMode.HALF_UP)

    private fun unavailable(
        reason: ComparisonUnavailableReason,
        unavailableAt: Instant?,
        baselineTimestamp: Instant?,
    ): RebalancerComparison = RebalancerComparison(
        availability = ComparisonAvailability.UNAVAILABLE,
        confidence = null,
        baselineTimestamp = baselineTimestamp,
        points = emptyList(),
        latestDifferenceUSD = null,
        latestDifferencePercent = null,
        unavailableReason = reason,
        unavailableAt = unavailableAt,
    )
}
