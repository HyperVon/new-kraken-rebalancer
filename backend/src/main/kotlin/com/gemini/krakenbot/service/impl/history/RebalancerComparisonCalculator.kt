package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.ComparisonAvailability
import com.gemini.krakenbot.model.ComparisonConfidence
import com.gemini.krakenbot.model.ComparisonUnavailableReason
import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.model.OrderSide
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.RebalancerComparison
import com.gemini.krakenbot.model.RebalancerComparisonPoint
import com.gemini.krakenbot.model.TradeOwnership
import com.gemini.krakenbot.model.TradeOwnershipClassifier
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.util.PrecisionConstants
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

object RebalancerComparisonCalculator {
    private val baselineMismatchTolerance = BigDecimal("0.01")
    private val externalBalanceLedgerTypes = LedgerEvent.EXTERNAL_BALANCE_TYPES

    fun calculate(
        snapshots: List<PortfolioSnapshot>,
        trades: List<TradeRecord>,
        rewards: List<LedgerEvent> = emptyList(),
        knownRebalancerOrderTxids: Set<String> = emptySet(),
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

        val periodLedgers = rewards.filter {
            it.type in externalBalanceLedgerTypes &&
                it.time > baseline.timestamp
        }

        val balanceResult =
            validateTrackedBalanceChanges(
                snapshots = orderedSnapshots,
                trades = trades,
                ledgers = periodLedgers,
                knownRebalancerOrderTxids = knownRebalancerOrderTxids,
            )

        if (balanceResult is TrackedBalanceValidation.Failed) {
            return unavailable(
                reason = balanceResult.reason,
                unavailableAt = balanceResult.unavailableAt ?: baseline.timestamp,
                baselineTimestamp = baseline.timestamp,
            )
        }

        val benchmarkEvents = buildBenchmarkEvents(
            trades = trades.filter { it.success && !it.dryRun && it.timestamp > baseline.timestamp },
            ledgers = periodLedgers,
            knownRebalancerOrderTxids = knownRebalancerOrderTxids,
        )

        val baselineBalances = extractBaselineBalances(baseline)
        val runningSyntheticBalances = baselineBalances.toMutableMap()
        var eventIndex = 0

        val points = mutableListOf<RebalancerComparisonPoint>()
        for (snapshot in orderedSnapshots) {
            while (eventIndex < benchmarkEvents.size && benchmarkEvents[eventIndex].timestamp <= snapshot.timestamp) {
                replayBenchmarkEvent(runningSyntheticBalances, benchmarkEvents[eventIndex])
                eventIndex++
            }

            val buyAndHoldValue = calculateBuyAndHoldValue(runningSyntheticBalances, snapshot)
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
            confidence = ComparisonConfidence.RECONCILED,
            baselineTimestamp = baseline.timestamp,
            points = correctedPoints,
            latestDifferenceUSD = latestDiffUSD,
            latestDifferencePercent = latestDiffPct,
            unavailableReason = null,
            unavailableAt = null,
        )
    }

    private sealed class TrackedBalanceValidation {
        data object Passed : TrackedBalanceValidation()
        data class Failed(val reason: ComparisonUnavailableReason, val unavailableAt: Instant?) :
            TrackedBalanceValidation()
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
        val baselineKeys = baseline.assets.keys
        for (snapshot in snapshots) {
            for (symbol in baselineKeys) {
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

    private fun validateTrackedBalanceChanges(
        snapshots: List<PortfolioSnapshot>,
        trades: List<TradeRecord>,
        ledgers: List<LedgerEvent>,
        knownRebalancerOrderTxids: Set<String>,
    ): TrackedBalanceValidation {
        val baseline = snapshots.first()
        val lastSnapshot = snapshots.last()
        val successfulTrades = trades.filter {
            it.success && !it.dryRun && it.timestamp > baseline.timestamp && it.timestamp <= lastSnapshot.timestamp
        }
        val externalEvents = ledgers.filter {
            it.type in externalBalanceLedgerTypes && it.time > baseline.timestamp && it.time <= lastSnapshot.timestamp
        }
        for (trade in successfulTrades) {
            val ownership = TradeOwnershipClassifier.classify(
                trade = trade,
                knownRebalancerOrderTxids = knownRebalancerOrderTxids,
            )
            if (ownership == TradeOwnership.UNKNOWN && trade.symbol in baseline.assets.keys) {
                return TrackedBalanceValidation.Failed(
                    reason = ComparisonUnavailableReason.AMBIGUOUS_TRADE_OWNERSHIP,
                    unavailableAt = trade.timestamp,
                )
            }
        }

        for (i in 1 until snapshots.size) {
            val prev = snapshots[i - 1]
            val curr = snapshots[i]

            val intervalTrades = successfulTrades.filter { trade ->
                trade.timestamp > prev.timestamp && trade.timestamp <= curr.timestamp
            }
            val intervalLedgers = externalEvents.filter { event ->
                event.time > prev.timestamp && event.time <= curr.timestamp
            }

            val impliedBalances = prev.assets.mapValues { (_, asset) -> asset.balance }.toMutableMap()
            for (trade in intervalTrades) {
                if (!applyRealizedTrade(impliedBalances, trade)) {
                    return TrackedBalanceValidation.Failed(
                        reason = ComparisonUnavailableReason.UNSUPPORTED_TRADE,
                        unavailableAt = trade.timestamp,
                    )
                }
            }
            for (event in intervalLedgers) {
                val symbol = Asset.normalizeLedgerAsset(event.asset).uppercase()
                if (symbol in impliedBalances) {
                    impliedBalances[symbol] = impliedBalances.getValue(symbol).add(event.netBalanceDelta())
                }
            }

            for ((symbol, expectedBalance) in impliedBalances) {
                val actualBalance = curr.assets[symbol]?.balance ?: return TrackedBalanceValidation.Failed(
                    reason = ComparisonUnavailableReason.UNSUPPORTED_TRADE,
                    unavailableAt = curr.timestamp,
                )
                val scale = if (symbol == Asset.USD) {
                    PrecisionConstants.SCALE_USD
                } else {
                    PrecisionConstants.SCALE_CRYPTO
                }
                val roundedExpected = expectedBalance.setScale(scale, RoundingMode.HALF_UP)
                val roundedActual = actualBalance.setScale(scale, RoundingMode.HALF_UP)
                if (roundedExpected.compareTo(roundedActual) != 0) {
                    return TrackedBalanceValidation.Failed(
                        reason = ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE,
                        unavailableAt = curr.timestamp,
                    )
                }
            }
        }
        return TrackedBalanceValidation.Passed
    }

    private fun buildBenchmarkEvents(
        trades: List<TradeRecord>,
        ledgers: List<LedgerEvent>,
        knownRebalancerOrderTxids: Set<String>,
    ): List<BenchmarkEvent> {
        val events = mutableListOf<BenchmarkEvent>()
        for (ledger in ledgers) {
            events += BenchmarkEvent.ExternalBalance(
                timestamp = ledger.time,
                asset = ledger.asset,
                netAmount = ledger.netBalanceDelta(),
                event = ledger,
            )
        }
        for (trade in trades) {
            val ownership = TradeOwnershipClassifier.classify(
                trade = trade,
                knownRebalancerOrderTxids = knownRebalancerOrderTxids,
            )
            events += BenchmarkEvent.Trade(
                timestamp = trade.timestamp,
                trade = trade,
                ownership = ownership,
            )
        }
        events.sort()
        return events
    }

    private fun replayBenchmarkEvent(balances: MutableMap<String, BigDecimal>, event: BenchmarkEvent) {
        when (event) {
            is BenchmarkEvent.ExternalBalance -> {
                val symbol = Asset.normalizeLedgerAsset(event.asset).uppercase()
                if (symbol in balances) {
                    balances[symbol] = balances.getValue(symbol).add(event.netAmount)
                }
            }

            is BenchmarkEvent.Trade -> {
                if (event.ownership == TradeOwnership.MANUAL_OR_EXTERNAL) {
                    applyRealizedTrade(balances, event.trade)
                }
            }
        }
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

        when {
            OrderSide.isBuy(side) -> {
                balances[symbol] = assetBalance.add(trade.volume)
                val usdCost = trade.usdAmount.add(trade.fee)
                balances[Asset.USD] = usdBalance.subtract(usdCost)
            }

            OrderSide.isSell(side) -> {
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
        syntheticBalances: Map<String, BigDecimal>,
        snapshot: PortfolioSnapshot,
    ): BigDecimal {
        var total = BigDecimal.ZERO
        for ((symbol, balance) in syntheticBalances) {
            val price = if (symbol == Asset.USD) {
                BigDecimal.ONE
            } else {
                snapshot.assets[symbol]?.price
                    ?: error(
                        "Asset $symbol missing in snapshot ${snapshot.timestamp}; validatePrices should have caught this",
                    )
            }
            val product = balance.multiply(price)
            total = total.add(product)
        }
        return total
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
