package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.ComparisonAvailability
import com.gemini.krakenbot.model.ComparisonConfidence
import com.gemini.krakenbot.model.ComparisonUnavailableReason
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.RebalancerComparison
import com.gemini.krakenbot.model.RebalancerComparisonPoint
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.util.PrecisionConstants
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

object RebalancerComparisonCalculator {

    private val SCALE_CRYPTO = PrecisionConstants.SCALE_CRYPTO
    private val SCALE_USD = PrecisionConstants.SCALE_USD
    private val SCALE_PERCENT = PrecisionConstants.SCALE_PERCENT
    private val USD = Asset.USD

    fun calculate(snapshots: List<PortfolioSnapshot>, trades: List<TradeRecord>): RebalancerComparison {
        if (snapshots.size < 2) {
            return unavailable(
                ComparisonUnavailableReason.INSUFFICIENT_SNAPSHOTS,
                snapshots.firstOrNull()?.timestamp,
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

        val balanceError = validateTrackedBalanceChanges(orderedSnapshots, trades)
        if (balanceError != null) return balanceError

        val baselineBalances = extractBaselineBalances(baseline)

        val points = mutableListOf<RebalancerComparisonPoint>()
        for (snapshot in orderedSnapshots) {
            val buyAndHoldValue = calculateBuyAndHoldValue(baselineBalances, snapshot)
            if (buyAndHoldValue.signum() <= 0) {
                return unavailable(
                    ComparisonUnavailableReason.NON_POSITIVE_BASELINE,
                    snapshot.timestamp,
                    baseline.timestamp,
                )
            }
            val rebalancerValue = snapshot.totalValueUSD
            val differenceUSD = rebalancerValue.subtract(buyAndHoldValue)
            val differencePercent = calculateDifferencePercent(differenceUSD, buyAndHoldValue)
            points += RebalancerComparisonPoint(
                timestamp = snapshot.timestamp,
                rebalancerValueUSD = rebalancerValue.setScale(SCALE_USD, RoundingMode.HALF_UP),
                buyAndHoldValueUSD = buyAndHoldValue.setScale(SCALE_USD, RoundingMode.HALF_UP),
                differenceUSD = differenceUSD.setScale(SCALE_USD, RoundingMode.HALF_UP),
                differencePercent = differencePercent.setScale(SCALE_PERCENT, RoundingMode.HALF_UP),
            )
        }

        val latestDiffUSD = points.last().differenceUSD
        val latestDiffPct = points.last().differencePercent

        val baselineFirstPoint = points.first()
        val firstDiffFromCalc = baselineFirstPoint.rebalancerValueUSD
            .subtract(baselineFirstPoint.buyAndHoldValueUSD)
            .abs()
        if (firstDiffFromCalc > BigDecimal("0.01")) {
            return unavailable(ComparisonUnavailableReason.BASELINE_MISMATCH, baseline.timestamp)
        }

        val correctedPoints = points.mapIndexed { index, point ->
            if (index == 0) {
                point.copy(
                    rebalancerValueUSD = baseline.totalValueUSD.setScale(SCALE_USD, RoundingMode.HALF_UP),
                    buyAndHoldValueUSD = baseline.totalValueUSD.setScale(SCALE_USD, RoundingMode.HALF_UP),
                    differenceUSD = BigDecimal.ZERO.setScale(SCALE_USD, RoundingMode.HALF_UP),
                    differencePercent = BigDecimal.ZERO.setScale(SCALE_PERCENT, RoundingMode.HALF_UP),
                )
            } else {
                point
            }
        }

        return RebalancerComparison(
            availability = ComparisonAvailability.AVAILABLE,
            confidence = ComparisonConfidence.RECONCILED,
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
            return unavailable(ComparisonUnavailableReason.NON_POSITIVE_BASELINE, baseline.timestamp)
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
                    ComparisonUnavailableReason.ASSET_UNIVERSE_CHANGED,
                    snapshot.timestamp,
                    baseline.timestamp,
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
                if (symbol == USD) continue
                val assetRow = snapshot.assets[symbol] ?: return unavailable(
                    ComparisonUnavailableReason.MISSING_PRICE,
                    snapshot.timestamp,
                    baseline.timestamp,
                )
                if (assetRow.price.signum() <= 0) {
                    return unavailable(
                        ComparisonUnavailableReason.MISSING_PRICE,
                        snapshot.timestamp,
                        baseline.timestamp,
                    )
                }
            }
        }
        return null
    }

    private fun validateTrackedBalanceChanges(
        snapshots: List<PortfolioSnapshot>,
        trades: List<TradeRecord>,
    ): RebalancerComparison? {
        val successfulTrades = trades.filter { it.success && !it.dryRun }
        val baselineTimestamp = snapshots.first().timestamp

        for (i in 1 until snapshots.size) {
            val prev = snapshots[i - 1]
            val curr = snapshots[i]

            val intervalTrades = successfulTrades.filter { trade ->
                trade.timestamp > prev.timestamp && trade.timestamp <= curr.timestamp
            }

            val impliedBalances = prev.assets.mapValues { (_, asset) -> asset.balance }.toMutableMap()
            for (trade in intervalTrades) {
                val result = applyRealizedTrade(impliedBalances, trade, baselineTimestamp)
                if (result != null) return result
            }

            for ((symbol, expectedBalance) in impliedBalances) {
                val actualBalance = curr.assets[symbol]?.balance
                    ?: return unavailable(
                        ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE,
                        curr.timestamp,
                        baselineTimestamp,
                    )
                val scale = if (symbol == USD) SCALE_USD else SCALE_CRYPTO
                val roundedExpected = expectedBalance.setScale(scale, RoundingMode.HALF_UP)
                val roundedActual = actualBalance.setScale(scale, RoundingMode.HALF_UP)
                if (roundedExpected.compareTo(roundedActual) != 0) {
                    return unavailable(
                        ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE,
                        curr.timestamp,
                        baselineTimestamp,
                    )
                }
            }
        }
        return null
    }

    private fun applyRealizedTrade(
        balances: MutableMap<String, BigDecimal>,
        trade: TradeRecord,
        baselineTimestamp: Instant,
    ): RebalancerComparison? {
        val side = trade.side.uppercase()
        val symbol = trade.symbol.uppercase()
        if (
            symbol == USD ||
            symbol !in balances ||
            !Asset.matchesUsdQuotedPair(trade.pair, symbol) ||
            trade.volume.signum() < 0 ||
            trade.usdAmount.signum() < 0 ||
            trade.fee.signum() < 0
        ) {
            return unavailable(ComparisonUnavailableReason.UNSUPPORTED_TRADE, trade.timestamp, baselineTimestamp)
        }
        if (USD !in balances) {
            return unavailable(ComparisonUnavailableReason.UNSUPPORTED_TRADE, trade.timestamp, baselineTimestamp)
        }
        val usdBalance = balances[USD] ?: BigDecimal.ZERO
        val assetBalance = balances.getValue(symbol)

        when (side) {
            "BUY" -> {
                balances[symbol] = assetBalance.add(trade.volume)
                val usdCost = trade.usdAmount.add(trade.fee)
                balances[USD] = usdBalance.subtract(usdCost)
            }
            "SELL" -> {
                balances[symbol] = assetBalance.subtract(trade.volume)
                val usdProceeds = trade.usdAmount.subtract(trade.fee)
                balances[USD] = usdBalance.add(usdProceeds)
            }
            else -> return unavailable(
                ComparisonUnavailableReason.UNSUPPORTED_TRADE,
                trade.timestamp,
                baselineTimestamp,
            )
        }
        return null
    }

    private fun extractBaselineBalances(baseline: PortfolioSnapshot): Map<String, BigDecimal> =
        baseline.assets.mapValues { (_, asset) -> asset.balance }

    private fun calculateBuyAndHoldValue(
        baselineBalances: Map<String, BigDecimal>,
        snapshot: PortfolioSnapshot,
    ): BigDecimal {
        var total = BigDecimal.ZERO
        for ((symbol, startBalance) in baselineBalances) {
            if (startBalance.signum() == 0) continue
            val price = if (symbol == USD) {
                BigDecimal.ONE
            } else {
                snapshot.assets[symbol]?.price ?: BigDecimal.ZERO
            }
            val product = startBalance.multiply(price)
            total = total.add(product)
        }
        return total
    }

    private fun calculateDifferencePercent(differenceUSD: BigDecimal, buyAndHoldValue: BigDecimal): BigDecimal =
        differenceUSD
            .multiply(BigDecimal(PrecisionConstants.HUNDRED_INT))
            .divide(buyAndHoldValue, SCALE_PERCENT, RoundingMode.HALF_UP)

    private fun unavailable(
        reason: ComparisonUnavailableReason,
        unavailableAt: Instant?,
        baselineTimestamp: Instant? = unavailableAt,
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
