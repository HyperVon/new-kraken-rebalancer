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
import com.gemini.krakenbot.model.TradeSource
import com.gemini.krakenbot.util.PrecisionConstants
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

object RebalancerComparisonCalculator {
    private val baselineMismatchTolerance = BigDecimal("0.01")

    // A HALF_UP four-decimal fee parse can lose at most half of one 4-decimal unit.
    private val legacyLedgerFeeDeltaTolerance = BigDecimal("0.00005")
    private val externalBalanceLedgerTypes = LedgerEvent.EXTERNAL_BALANCE_TYPES

    // Bounded window (1,000ms) admitting clock skew and exchange timestamp truncation/precision differences
    // when an exchange event was already executed and reflected in observed balances.
    internal const val MAX_EVENT_OBSERVATION_CLOCK_SKEW_MILLIS = 1_000L

    // A shared cap bounds the combined initial/late search to at most 2^12 assignments.
    private const val MAX_BOUNDARY_EVENT_CANDIDATES = 12

    fun calculate(
        snapshots: List<PortfolioSnapshot>,
        trades: List<TradeRecord>,
        rewards: List<LedgerEvent> = emptyList(),
        knownRebalancerOrderTxids: Set<String> = emptySet(),
        anchorSnapshot: PortfolioSnapshot? = null,
        inceptionSnapshot: PortfolioSnapshot? = null,
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
        val baseline = if (inceptionSnapshot != null &&
            inceptionSnapshot.timestamp <= orderedSnapshots.first().timestamp &&
            inceptionSnapshot.assets.keys == orderedSnapshots.first().assets.keys
        ) {
            inceptionSnapshot
        } else {
            orderedSnapshots.first()
        }

        val universeError = validateAssetUniverse(orderedSnapshots, baseline)
        if (universeError != null) return universeError

        val baselineError = validateBaseline(baseline)
        if (baselineError != null) return baselineError

        val priceError = validatePrices(orderedSnapshots, baseline)
        if (priceError != null) return priceError

        val effectiveAnchor = anchorSnapshot?.takeIf {
            it.timestamp < baseline.timestamp && it.assets.keys == baseline.assets.keys
        }
        val validationSnapshots = if (effectiveAnchor != null) {
            listOf(effectiveAnchor) + orderedSnapshots
        } else {
            orderedSnapshots
        }

        val balanceResult = validateTrackedBalanceChanges(
            snapshots = validationSnapshots,
            trades = trades,
            ledgers = rewards,
            knownRebalancerOrderTxids = knownRebalancerOrderTxids,
            baseline = baseline,
        )

        val (reconciledTrades, reconciledLedgers) = when (balanceResult) {
            is TrackedBalanceValidation.Failed -> {
                return unavailable(
                    reason = balanceResult.reason,
                    unavailableAt = balanceResult.unavailableAt ?: baseline.timestamp,
                    baselineTimestamp = baseline.timestamp,
                )
            }

            is TrackedBalanceValidation.Passed -> balanceResult.trades to balanceResult.ledgers
        }

        val windowObservationStart = validationSnapshots.first().balancesObservedAt
            ?: validationSnapshots.first().timestamp.minusMillis(MAX_EVENT_OBSERVATION_CLOCK_SKEW_MILLIS)

        val intermediateLedgers = if (baseline.timestamp < windowObservationStart) {
            rewards.filter {
                it.type in externalBalanceLedgerTypes &&
                    it.time > baseline.timestamp &&
                    it.time <= windowObservationStart
            }.map { ReconciledLedger(it, it.time, it.netBalanceDelta()) }
        } else {
            emptyList()
        }

        val intermediateTrades = if (baseline.timestamp < windowObservationStart) {
            trades.filter {
                it.success &&
                    !it.dryRun &&
                    it.timestamp > baseline.timestamp &&
                    it.timestamp <= windowObservationStart &&
                    TradeOwnershipClassifier.classify(
                        it,
                        knownRebalancerOrderTxids,
                    ) == TradeOwnership.MANUAL_OR_EXTERNAL
            }.map { ReconciledTrade(it, it.timestamp, it.usdAmount) }
        } else {
            emptyList()
        }

        val intermediateBenchmarkEvents = buildBenchmarkEvents(
            trades = intermediateTrades,
            ledgers = intermediateLedgers,
            knownRebalancerOrderTxids = knownRebalancerOrderTxids,
            baseline = baseline,
        )

        val windowBenchmarkEvents = buildBenchmarkEvents(
            trades = reconciledTrades,
            ledgers = reconciledLedgers,
            knownRebalancerOrderTxids = knownRebalancerOrderTxids,
            baseline = baseline,
        )

        val benchmarkEvents = (intermediateBenchmarkEvents + windowBenchmarkEvents).sortedBy { it.timestamp }

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

        val isStartingAtBaseline = orderedSnapshots.first().timestamp == baseline.timestamp
        if (isStartingAtBaseline) {
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
        }

        val correctedPoints = points.mapIndexed { index, point ->
            if (index == 0 && isStartingAtBaseline) {
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
        data class Passed(val trades: List<ReconciledTrade>, val ledgers: List<ReconciledLedger>) :
            TrackedBalanceValidation()

        data class Failed(val reason: ComparisonUnavailableReason, val unavailableAt: Instant?) :
            TrackedBalanceValidation()
    }

    private enum class TradeAccountingMode {
        PRECISE_FILL_NOTIONAL,
        PERSISTED_ROUNDED_COST,
    }

    private data class ReconciliationState(
        val effectiveTradeTimestamps: MutableList<Instant>,
        val effectiveTradeAccountingModes: MutableList<TradeAccountingMode>,
        val effectiveLedgerTimestamps: MutableList<Instant>,
        val effectiveLedgerDeltas: MutableList<BigDecimal>,
        val assignedTradeIndexes: MutableSet<Int> = mutableSetOf(),
        val assignedLedgerIndexes: MutableSet<Int> = mutableSetOf(),
        val embeddedTradeIndexes: MutableSet<Int> = mutableSetOf(),
        val embeddedLedgerIndexes: MutableSet<Int> = mutableSetOf(),
    ) {
        fun copyForAttempt(): ReconciliationState = copy(
            effectiveTradeTimestamps = effectiveTradeTimestamps.toMutableList(),
            effectiveTradeAccountingModes = effectiveTradeAccountingModes.toMutableList(),
            effectiveLedgerTimestamps = effectiveLedgerTimestamps.toMutableList(),
            effectiveLedgerDeltas = effectiveLedgerDeltas.toMutableList(),
            assignedTradeIndexes = assignedTradeIndexes.toMutableSet(),
            assignedLedgerIndexes = assignedLedgerIndexes.toMutableSet(),
            embeddedTradeIndexes = embeddedTradeIndexes.toMutableSet(),
            embeddedLedgerIndexes = embeddedLedgerIndexes.toMutableSet(),
        )
    }

    private data class ReconciledTrade(
        val trade: TradeRecord,
        val timestamp: Instant,
        val usdNotional: BigDecimal,
        val embeddedInBaseline: Boolean = false,
    )

    private data class ReconciledLedger(
        val ledger: LedgerEvent,
        val timestamp: Instant,
        val netBalanceDelta: BigDecimal,
        val embeddedInBaseline: Boolean = false,
    )

    private sealed class LateCandidate {
        abstract val index: Int

        data class Trade(override val index: Int, val trade: TradeRecord) : LateCandidate()

        data class Ledger(override val index: Int, val ledger: LedgerEvent) : LateCandidate()
    }

    private data class LateAssignment(
        val tradeIndexes: List<Int>,
        val ledgerIndexes: List<Int>,
        val balances: Map<String, BigDecimal>,
        val ledgerDeltas: Map<Int, BigDecimal>,
    )

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
        baseline: PortfolioSnapshot,
    ): TrackedBalanceValidation {
        val startObservationTime = snapshots.first().balancesObservedAt
            ?: snapshots.first().timestamp.minusMillis(MAX_EVENT_OBSERVATION_CLOCK_SKEW_MILLIS)
        val lastSnapshot = snapshots.last()
        val lastObservationTime = lastSnapshot.balancesObservedAt ?: lastSnapshot.timestamp
        val maxEventTime = latestCandidateTime(lastSnapshot)

        val successfulTrades = trades
            .filter {
                it.success &&
                    !it.dryRun &&
                    it.timestamp > startObservationTime &&
                    it.timestamp <= maxEventTime
            }
            .sortedBy(TradeRecord::timestamp)

        val externalEvents = ledgers
            .filter {
                it.type in externalBalanceLedgerTypes &&
                    it.time > startObservationTime &&
                    it.time <= maxEventTime
            }
            .sortedBy(LedgerEvent::time)

        val indexedTrades = successfulTrades.withIndex().toList()
        val indexedLedgers = externalEvents.withIndex().toList()
        var state = ReconciliationState(
            effectiveTradeTimestamps = successfulTrades.map(TradeRecord::timestamp).toMutableList(),
            effectiveTradeAccountingModes = MutableList(successfulTrades.size) {
                TradeAccountingMode.PRECISE_FILL_NOTIONAL
            },
            effectiveLedgerTimestamps = externalEvents.map(LedgerEvent::time).toMutableList(),
            effectiveLedgerDeltas = externalEvents.map(LedgerEvent::netBalanceDelta).toMutableList(),
        )

        for ((_, trade) in indexedTrades.filter { (_, trade) -> trade.timestamp <= lastObservationTime }) {
            val ownership = TradeOwnershipClassifier.classify(
                trade = trade,
                knownRebalancerOrderTxids = knownRebalancerOrderTxids,
            )
            if (ownership == TradeOwnership.UNKNOWN && trade.symbol.uppercase() in baseline.assets.keys) {
                return TrackedBalanceValidation.Failed(
                    reason = ComparisonUnavailableReason.AMBIGUOUS_TRADE_OWNERSHIP,
                    unavailableAt = trade.timestamp,
                )
            }
        }

        fun reconcileInterval(
            i: Int,
            attempt: ReconciliationState,
            intervalAccountingMode: TradeAccountingMode,
        ): TrackedBalanceValidation.Failed? {
            val assignedTradeIndexes = attempt.assignedTradeIndexes
            val assignedLedgerIndexes = attempt.assignedLedgerIndexes
            val embeddedTradeIndexes = attempt.embeddedTradeIndexes
            val embeddedLedgerIndexes = attempt.embeddedLedgerIndexes
            val effectiveTradeTimestamps = attempt.effectiveTradeTimestamps
            val effectiveTradeAccountingModes = attempt.effectiveTradeAccountingModes
            val effectiveLedgerTimestamps = attempt.effectiveLedgerTimestamps
            val effectiveLedgerDeltas = attempt.effectiveLedgerDeltas
            val prev = snapshots[i - 1]
            val curr = snapshots[i]
            val prevObs = prev.balancesObservedAt ?: prev.timestamp
            val currObs = curr.balancesObservedAt ?: curr.timestamp
            val isInitialNoAnchor = (i == 1 && prev.timestamp == baseline.timestamp)
            val hasUnknownObservation = prev.balancesObservedAt == null || curr.balancesObservedAt == null

            // A legacy baseline has no trustworthy request-start boundary. Treat its nearby
            // events as ordinary interval candidates instead of classifying the whole first
            // burst as embedded/post-baseline in one assignment.
            val initialTradeCandidates = if (isInitialNoAnchor && prev.balancesObservedAt != null) {
                indexedTrades.filter { (index, trade) ->
                    index !in assignedTradeIndexes &&
                        trade.timestamp > prevObs &&
                        trade.timestamp <= minOf(
                            currObs,
                            latestCandidateTime(prev),
                        ) &&
                        trade.symbol.uppercase() in baseline.assets.keys
                }
            } else {
                emptyList()
            }
            val initialLedgerCandidates = if (isInitialNoAnchor && prev.balancesObservedAt != null) {
                indexedLedgers.filter { (index, ledger) ->
                    index !in assignedLedgerIndexes &&
                        ledger.time > prevObs &&
                        ledger.time <= minOf(currObs, latestCandidateTime(prev)) &&
                        Asset.normalizeLedgerAsset(ledger.asset).uppercase() in baseline.assets.keys
                }
            } else {
                emptyList()
            }

            val initialTradeIndices = initialTradeCandidates.map { it.index }.toSet()
            val initialLedgerIndices = initialLedgerCandidates.map { it.index }.toSet()

            val impliedBalances = prev.assets.mapValues { (_, asset) -> asset.balance }.toMutableMap()

            val lateTradeCandidates = indexedTrades
                .filter { (index, trade) ->
                    index !in assignedTradeIndexes &&
                        index !in initialTradeIndices &&
                        trade.timestamp > currObs &&
                        trade.timestamp <= latestCandidateTime(curr) &&
                        trade.symbol.uppercase() in baseline.assets.keys
                }
            val lateLedgerCandidates = indexedLedgers
                .filter { (index, ledger) ->
                    index !in assignedLedgerIndexes &&
                        index !in initialLedgerIndices &&
                        ledger.time > currObs &&
                        ledger.time <= latestCandidateTime(curr) &&
                        Asset.normalizeLedgerAsset(ledger.asset).uppercase() in baseline.assets.keys
                }

            val legacyRegularTradeCandidates = if (hasUnknownObservation) {
                val lowerBound = if (prev.balancesObservedAt == null) {
                    prev.timestamp.plusMillis(MAX_EVENT_OBSERVATION_CLOCK_SKEW_MILLIS)
                } else {
                    prevObs
                }
                val upperBound = if (curr.balancesObservedAt == null) {
                    curr.timestamp.minusMillis(MAX_EVENT_OBSERVATION_CLOCK_SKEW_MILLIS)
                } else {
                    currObs
                }
                indexedTrades.filter { (index, trade) ->
                    index !in assignedTradeIndexes &&
                        index !in initialTradeIndices &&
                        trade.timestamp > lowerBound &&
                        trade.timestamp <= upperBound &&
                        trade.symbol.uppercase() in baseline.assets.keys
                }
            } else {
                emptyList()
            }
            val legacyRegularTradeIndexes = legacyRegularTradeCandidates
                .map(IndexedValue<TradeRecord>::index)
                .toSet()
            val legacyRegularLedgerCandidates = if (hasUnknownObservation) {
                val lowerBound = if (prev.balancesObservedAt == null) {
                    prev.timestamp.plusMillis(MAX_EVENT_OBSERVATION_CLOCK_SKEW_MILLIS)
                } else {
                    prevObs
                }
                val upperBound = if (curr.balancesObservedAt == null) {
                    curr.timestamp.minusMillis(MAX_EVENT_OBSERVATION_CLOCK_SKEW_MILLIS)
                } else {
                    currObs
                }
                indexedLedgers.filter { (index, ledger) ->
                    index !in assignedLedgerIndexes &&
                        index !in initialLedgerIndices &&
                        ledger.time > lowerBound &&
                        ledger.time <= upperBound &&
                        Asset.normalizeLedgerAsset(ledger.asset).uppercase() in baseline.assets.keys
                }
            } else {
                emptyList()
            }
            val legacyRegularLedgerIndexes = legacyRegularLedgerCandidates
                .map(IndexedValue<LedgerEvent>::index)
                .toSet()
            val legacyBoundaryTradeCandidates = if (hasUnknownObservation) {
                indexedTrades.filter { (index, trade) ->
                    if (
                        index in assignedTradeIndexes ||
                        index in initialTradeIndices ||
                        index in legacyRegularTradeIndexes ||
                        trade.symbol.uppercase() !in baseline.assets.keys
                    ) {
                        false
                    } else {
                        val nearUnknownPreviousBoundary = prev.balancesObservedAt == null &&
                            trade.timestamp > prev.timestamp.minusMillis(MAX_EVENT_OBSERVATION_CLOCK_SKEW_MILLIS) &&
                            trade.timestamp <= prev.timestamp.plusMillis(MAX_EVENT_OBSERVATION_CLOCK_SKEW_MILLIS)
                        val nearCurrentBoundary = if (curr.balancesObservedAt == null) {
                            trade.timestamp > curr.timestamp.minusMillis(MAX_EVENT_OBSERVATION_CLOCK_SKEW_MILLIS) &&
                                trade.timestamp <= curr.timestamp.plusMillis(MAX_EVENT_OBSERVATION_CLOCK_SKEW_MILLIS)
                        } else {
                            trade.timestamp > currObs &&
                                trade.timestamp <= latestCandidateTime(curr)
                        }
                        nearUnknownPreviousBoundary || nearCurrentBoundary
                    }
                }
            } else {
                emptyList()
            }
            val legacyBoundaryLedgerCandidates = if (hasUnknownObservation) {
                indexedLedgers.filter { (index, ledger) ->
                    if (
                        index in assignedLedgerIndexes ||
                        index in initialLedgerIndices ||
                        index in legacyRegularLedgerIndexes ||
                        Asset.normalizeLedgerAsset(ledger.asset).uppercase() !in baseline.assets.keys
                    ) {
                        false
                    } else {
                        val nearUnknownPreviousBoundary = prev.balancesObservedAt == null &&
                            ledger.time > prev.timestamp.minusMillis(MAX_EVENT_OBSERVATION_CLOCK_SKEW_MILLIS) &&
                            ledger.time <= prev.timestamp.plusMillis(MAX_EVENT_OBSERVATION_CLOCK_SKEW_MILLIS)
                        val nearCurrentBoundary = if (curr.balancesObservedAt == null) {
                            ledger.time > curr.timestamp.minusMillis(MAX_EVENT_OBSERVATION_CLOCK_SKEW_MILLIS) &&
                                ledger.time <= curr.timestamp.plusMillis(MAX_EVENT_OBSERVATION_CLOCK_SKEW_MILLIS)
                        } else {
                            ledger.time > currObs &&
                                ledger.time <= latestCandidateTime(curr)
                        }
                        nearUnknownPreviousBoundary || nearCurrentBoundary
                    }
                }
            } else {
                emptyList()
            }

            for ((_, lateTrade) in lateTradeCandidates) {
                val ownership = TradeOwnershipClassifier.classify(
                    trade = lateTrade,
                    knownRebalancerOrderTxids = knownRebalancerOrderTxids,
                )
                if (ownership == TradeOwnership.UNKNOWN) {
                    return TrackedBalanceValidation.Failed(
                        reason = ComparisonUnavailableReason.AMBIGUOUS_TRADE_OWNERSHIP,
                        unavailableAt = lateTrade.timestamp,
                    )
                }
                val candidateBalances = impliedBalances.toMutableMap()
                if (!applyRealizedTrade(candidateBalances, lateTrade, intervalAccountingMode)) {
                    return TrackedBalanceValidation.Failed(
                        reason = ComparisonUnavailableReason.UNSUPPORTED_TRADE,
                        unavailableAt = lateTrade.timestamp,
                    )
                }
            }

            val lateCandidates = if (hasUnknownObservation) {
                buildList {
                    legacyBoundaryTradeCandidates.forEach { add(LateCandidate.Trade(it.index, it.value)) }
                    legacyBoundaryLedgerCandidates.forEach { add(LateCandidate.Ledger(it.index, it.value)) }
                }
            } else {
                buildList {
                    lateTradeCandidates.forEach { add(LateCandidate.Trade(it.index, it.value)) }
                    lateLedgerCandidates.forEach { add(LateCandidate.Ledger(it.index, it.value)) }
                }
            }

            if (hasUnknownObservation) {
                for ((_, boundaryTrade) in legacyBoundaryTradeCandidates) {
                    val ownership = TradeOwnershipClassifier.classify(
                        trade = boundaryTrade,
                        knownRebalancerOrderTxids = knownRebalancerOrderTxids,
                    )
                    if (ownership == TradeOwnership.UNKNOWN) {
                        return TrackedBalanceValidation.Failed(
                            reason = ComparisonUnavailableReason.AMBIGUOUS_TRADE_OWNERSHIP,
                            unavailableAt = boundaryTrade.timestamp,
                        )
                    }
                    val candidateBalances = impliedBalances.toMutableMap()
                    if (!applyRealizedTrade(candidateBalances, boundaryTrade, intervalAccountingMode)) {
                        return TrackedBalanceValidation.Failed(
                            reason = ComparisonUnavailableReason.UNSUPPORTED_TRADE,
                            unavailableAt = boundaryTrade.timestamp,
                        )
                    }
                }
            }

            val regularIntervalTrades = if (hasUnknownObservation) {
                legacyRegularTradeCandidates
            } else {
                indexedTrades.filter { (index, trade) ->
                    index !in assignedTradeIndexes &&
                        index !in initialTradeIndices &&
                        trade.timestamp > prevObs &&
                        trade.timestamp <= currObs
                }
            }
            val regularIntervalLedgers = if (hasUnknownObservation) {
                legacyRegularLedgerCandidates
            } else {
                indexedLedgers.filter { (index, ledger) ->
                    index !in assignedLedgerIndexes &&
                        index !in initialLedgerIndices &&
                        ledger.time > prevObs &&
                        ledger.time <= currObs
                }
            }
            val intervalLedgerCandidates = buildList {
                initialLedgerCandidates.forEach { add(it.value) }
                regularIntervalLedgers.forEach { add(it.value) }
                lateCandidates.filterIsInstance<LateCandidate.Ledger>().forEach { add(it.ledger) }
            }
            val intervalTradeCandidates = buildList {
                initialTradeCandidates.forEach { add(it.value) }
                regularIntervalTrades.forEach { add(it.value) }
                lateCandidates.filterIsInstance<LateCandidate.Trade>().forEach { add(it.trade) }
            }
            val useAuthoritativeLedgerBalances = canUseAuthoritativeLedgerBalances(
                intervalTradeCandidates,
                intervalLedgerCandidates,
                baseline.assets.keys,
            )

            if (initialTradeCandidates.isNotEmpty() || initialLedgerCandidates.isNotEmpty()) {
                for ((_, initialTrade) in initialTradeCandidates) {
                    val ownership = TradeOwnershipClassifier.classify(
                        trade = initialTrade,
                        knownRebalancerOrderTxids = knownRebalancerOrderTxids,
                    )
                    if (ownership == TradeOwnership.UNKNOWN) {
                        return TrackedBalanceValidation.Failed(
                            reason = ComparisonUnavailableReason.AMBIGUOUS_TRADE_OWNERSHIP,
                            unavailableAt = initialTrade.timestamp,
                        )
                    }
                    val candidateBalances = impliedBalances.toMutableMap()
                    if (!applyRealizedTrade(candidateBalances, initialTrade, intervalAccountingMode)) {
                        return TrackedBalanceValidation.Failed(
                            reason = ComparisonUnavailableReason.UNSUPPORTED_TRADE,
                            unavailableAt = initialTrade.timestamp,
                        )
                    }
                }

                val initialCandidates = buildList {
                    initialTradeCandidates.forEach { add(LateCandidate.Trade(it.index, it.value)) }
                    initialLedgerCandidates.forEach { add(LateCandidate.Ledger(it.index, it.value)) }
                }

                val initialAssignment = findInitialAssignment(
                    initialCandidates = initialCandidates,
                    regularTrades = regularIntervalTrades,
                    regularLedgers = regularIntervalLedgers,
                    lateCandidates = lateCandidates,
                    startingBalances = impliedBalances,
                    snapshot = curr,
                    accountingMode = intervalAccountingMode,
                    useAuthoritativeLedgerBalances = useAuthoritativeLedgerBalances,
                ) ?: return TrackedBalanceValidation.Failed(
                    reason = ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE,
                    unavailableAt = curr.timestamp,
                )

                for (index in initialAssignment.embeddedTradeIndexes) {
                    assignedTradeIndexes += index
                    effectiveTradeAccountingModes[index] = intervalAccountingMode
                    embeddedTradeIndexes += index
                }
                for (index in initialAssignment.embeddedLedgerIndexes) {
                    assignedLedgerIndexes += index
                    effectiveLedgerDeltas[index] = initialAssignment.ledgerDeltas[index]
                        ?: externalEvents[index].netBalanceDelta()
                    embeddedLedgerIndexes += index
                }
                for (index in initialAssignment.postBaselineTradeIndexes) {
                    assignedTradeIndexes += index
                    effectiveTradeAccountingModes[index] = intervalAccountingMode
                    effectiveTradeTimestamps[index] = calculateIntervalEventTimestamp(
                        successfulTrades[index].timestamp,
                        prev,
                        curr,
                    )
                }
                for (index in initialAssignment.postBaselineLedgerIndexes) {
                    assignedLedgerIndexes += index
                    effectiveLedgerDeltas[index] = initialAssignment.ledgerDeltas[index]
                        ?: externalEvents[index].netBalanceDelta()
                    effectiveLedgerTimestamps[index] = calculateIntervalEventTimestamp(
                        externalEvents[index].time,
                        prev,
                        curr,
                    )
                }
                for ((index, trade) in regularIntervalTrades) {
                    assignedTradeIndexes += index
                    effectiveTradeAccountingModes[index] = intervalAccountingMode
                    effectiveTradeTimestamps[index] = calculateIntervalEventTimestamp(trade.timestamp, prev, curr)
                }
                for ((index, ledger) in regularIntervalLedgers) {
                    effectiveLedgerDeltas[index] = applyLedgerEvent(
                        impliedBalances,
                        ledger,
                        useAuthoritativeLedgerBalances,
                    )
                    assignedLedgerIndexes += index
                    effectiveLedgerTimestamps[index] = calculateIntervalEventTimestamp(ledger.time, prev, curr)
                }
                if (initialAssignment.lateAssignment != null) {
                    for (index in initialAssignment.lateAssignment.tradeIndexes) {
                        assignedTradeIndexes += index
                        effectiveTradeAccountingModes[index] = intervalAccountingMode
                        effectiveTradeTimestamps[index] = curr.timestamp
                    }
                    for (index in initialAssignment.lateAssignment.ledgerIndexes) {
                        assignedLedgerIndexes += index
                        effectiveLedgerDeltas[index] = initialAssignment.lateAssignment.ledgerDeltas[index]
                            ?: externalEvents[index].netBalanceDelta()
                        effectiveLedgerTimestamps[index] = curr.timestamp
                    }
                }
                impliedBalances.clear()
                impliedBalances.putAll(initialAssignment.resultingBalances)
            } else {
                for ((index, trade) in regularIntervalTrades) {
                    if (!applyRealizedTrade(impliedBalances, trade, intervalAccountingMode)) {
                        return TrackedBalanceValidation.Failed(
                            reason = ComparisonUnavailableReason.UNSUPPORTED_TRADE,
                            unavailableAt = trade.timestamp,
                        )
                    }
                    assignedTradeIndexes += index
                    effectiveTradeAccountingModes[index] = intervalAccountingMode
                    effectiveTradeTimestamps[index] = calculateIntervalEventTimestamp(trade.timestamp, prev, curr)
                }
                for ((index, ledger) in regularIntervalLedgers) {
                    effectiveLedgerDeltas[index] = applyLedgerEvent(
                        impliedBalances,
                        ledger,
                        useAuthoritativeLedgerBalances,
                    )
                    assignedLedgerIndexes += index
                    effectiveLedgerTimestamps[index] = calculateIntervalEventTimestamp(ledger.time, prev, curr)
                }
                val lateAssignment = if (!balancesMatchSnapshot(impliedBalances, curr)) {
                    findLateAssignment(
                        lateCandidates,
                        impliedBalances,
                        curr,
                        intervalAccountingMode,
                        useAuthoritativeLedgerBalances,
                    )
                } else {
                    null
                }
                if (lateAssignment != null) {
                    for (index in lateAssignment.tradeIndexes) {
                        assignedTradeIndexes += index
                        effectiveTradeAccountingModes[index] = intervalAccountingMode
                        effectiveTradeTimestamps[index] = curr.timestamp
                    }
                    for (index in lateAssignment.ledgerIndexes) {
                        assignedLedgerIndexes += index
                        effectiveLedgerDeltas[index] = lateAssignment.ledgerDeltas[index]
                            ?: externalEvents[index].netBalanceDelta()
                        effectiveLedgerTimestamps[index] = curr.timestamp
                    }
                    impliedBalances.clear()
                    impliedBalances.putAll(lateAssignment.balances)
                }
            }

            if (impliedBalances.keys.any { it !in curr.assets }) {
                return TrackedBalanceValidation.Failed(
                    reason = ComparisonUnavailableReason.UNSUPPORTED_TRADE,
                    unavailableAt = curr.timestamp,
                )
            }
            if (!balancesMatchSnapshot(impliedBalances, curr)) {
                return TrackedBalanceValidation.Failed(
                    reason = ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE,
                    unavailableAt = curr.timestamp,
                )
            }
            return null
        }

        for (i in 1 until snapshots.size) {
            val preciseAttempt = state.copyForAttempt()
            val preciseFailure = reconcileInterval(i, preciseAttempt, TradeAccountingMode.PRECISE_FILL_NOTIONAL)
            if (preciseFailure == null) {
                state = preciseAttempt
                continue
            }
            if (preciseFailure.reason != ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE) {
                return preciseFailure
            }

            // Reconstructed and live rows can share the same observation-marker shape but
            // use different cost precision. Retry only this interval, preserving assignments
            // from its reconciled prefix and discarding all mutations from the failed attempt.
            val legacyAttempt = state.copyForAttempt()
            val legacyFailure = reconcileInterval(i, legacyAttempt, TradeAccountingMode.PERSISTED_ROUNDED_COST)
            if (legacyFailure != null) return preciseFailure
            state = legacyAttempt
        }

        val passedTrades = state.assignedTradeIndexes.sorted().map { index ->
            ReconciledTrade(
                trade = successfulTrades[index],
                timestamp = state.effectiveTradeTimestamps[index],
                usdNotional = realizedUsdNotional(successfulTrades[index], state.effectiveTradeAccountingModes[index]),
                embeddedInBaseline = index in state.embeddedTradeIndexes,
            )
        }
        val passedLedgers = state.assignedLedgerIndexes.sorted().map { index ->
            ReconciledLedger(
                ledger = externalEvents[index],
                timestamp = state.effectiveLedgerTimestamps[index],
                netBalanceDelta = state.effectiveLedgerDeltas[index],
                embeddedInBaseline = index in state.embeddedLedgerIndexes,
            )
        }
        return TrackedBalanceValidation.Passed(
            trades = passedTrades,
            ledgers = passedLedgers,
        )
    }

    // Request start is a lower bound, not the instant the exchange captured balances.
    // Snapshot creation bounds the other end; clock skew is added only after that end.
    private fun latestCandidateTime(snapshot: PortfolioSnapshot): Instant =
        maxOf(snapshot.timestamp, snapshot.balancesObservedAt ?: snapshot.timestamp)
            .plusMillis(MAX_EVENT_OBSERVATION_CLOCK_SKEW_MILLIS)

    private fun calculateIntervalEventTimestamp(
        eventTime: Instant,
        prev: PortfolioSnapshot,
        curr: PortfolioSnapshot,
    ): Instant = when {
        eventTime <= prev.timestamp -> prev.timestamp.plusMillis(1)
        eventTime > curr.timestamp -> curr.timestamp
        else -> eventTime
    }

    private fun balancesMatchSnapshot(
        expectedBalances: Map<String, BigDecimal>,
        snapshot: PortfolioSnapshot,
    ): Boolean {
        for ((symbol, expectedBalance) in expectedBalances) {
            val actualBalance = snapshot.assets[symbol]?.balance ?: return false
            val scale = if (symbol == Asset.USD) {
                PrecisionConstants.SCALE_USD
            } else {
                PrecisionConstants.SCALE_CRYPTO
            }
            val roundedExpected = expectedBalance.setScale(scale, RoundingMode.HALF_UP)
            val roundedActual = actualBalance.setScale(scale, RoundingMode.HALF_UP)
            if (roundedExpected.compareTo(roundedActual) != 0) return false
        }
        return true
    }

    private fun canUseAuthoritativeLedgerBalances(
        trades: List<TradeRecord>,
        ledgers: List<LedgerEvent>,
        trackedAssets: Set<String>,
    ): Boolean {
        if (trades.any { it.symbol.uppercase() in trackedAssets }) return false
        val trackedLedgers = ledgers.filter {
            Asset.normalizeLedgerAsset(it.asset).uppercase() in trackedAssets
        }
        return trackedLedgers.isNotEmpty() &&
            trackedLedgers.all(LedgerEvent::hasAuthoritativeBalance) &&
            trackedLedgers.groupingBy { Asset.normalizeLedgerAsset(it.asset).uppercase() }
                .eachCount()
                .values
                .all { it == 1 }
    }

    private data class InitialAssignmentMatch(
        val embeddedTradeIndexes: List<Int>,
        val embeddedLedgerIndexes: List<Int>,
        val postBaselineTradeIndexes: List<Int>,
        val postBaselineLedgerIndexes: List<Int>,
        val lateAssignment: LateAssignment?,
        val resultingBalances: Map<String, BigDecimal>,
        val ledgerDeltas: Map<Int, BigDecimal>,
    )

    private fun findInitialAssignment(
        initialCandidates: List<LateCandidate>,
        regularTrades: List<IndexedValue<TradeRecord>>,
        regularLedgers: List<IndexedValue<LedgerEvent>>,
        lateCandidates: List<LateCandidate>,
        startingBalances: Map<String, BigDecimal>,
        snapshot: PortfolioSnapshot,
        accountingMode: TradeAccountingMode,
        useAuthoritativeLedgerBalances: Boolean,
    ): InitialAssignmentMatch? {
        if (initialCandidates.size + lateCandidates.size > MAX_BOUNDARY_EVENT_CANDIDATES) return null

        var match: InitialAssignmentMatch? = null
        var multipleMatches = false
        val embeddedTrades = mutableListOf<Int>()
        val embeddedLedgers = mutableListOf<Int>()
        val postTrades = mutableListOf<Int>()
        val postLedgers = mutableListOf<Int>()

        fun search(position: Int) {
            if (multipleMatches) return
            if (position == initialCandidates.size) {
                val testBalances = startingBalances.toMutableMap()
                var validEconomics = true

                val allPostTrades = (
                    initialCandidates.filter {
                        it is LateCandidate.Trade && it.index in postTrades
                    }.map {
                        (it as LateCandidate.Trade).trade
                    } + regularTrades.map { it.value }
                    ).sortedBy(TradeRecord::timestamp)

                for (trade in allPostTrades) {
                    if (!applyRealizedTrade(testBalances, trade, accountingMode)) {
                        validEconomics = false
                        break
                    }
                }
                if (!validEconomics) return

                val allPostLedgers = (
                    initialCandidates.filter {
                        it is LateCandidate.Ledger && it.index in postLedgers
                    }.map {
                        val candidate = it as LateCandidate.Ledger
                        IndexedValue(candidate.index, candidate.ledger)
                    } + regularLedgers
                    ).sortedBy { it.value.time }

                val ledgerDeltas = mutableMapOf<Int, BigDecimal>()
                for ((index, ledger) in allPostLedgers) {
                    ledgerDeltas[index] = applyLedgerEvent(testBalances, ledger, useAuthoritativeLedgerBalances)
                }

                val candidateMatch = if (balancesMatchSnapshot(testBalances, snapshot)) {
                    InitialAssignmentMatch(
                        embeddedTradeIndexes = embeddedTrades.toList(),
                        embeddedLedgerIndexes = embeddedLedgers.toList(),
                        postBaselineTradeIndexes = postTrades.toList(),
                        postBaselineLedgerIndexes = postLedgers.toList(),
                        lateAssignment = null,
                        resultingBalances = testBalances.toMap(),
                        ledgerDeltas = ledgerDeltas,
                    )
                } else {
                    val late = findLateAssignment(
                        lateCandidates,
                        testBalances,
                        snapshot,
                        accountingMode,
                        useAuthoritativeLedgerBalances,
                    )
                    if (late != null) {
                        InitialAssignmentMatch(
                            embeddedTradeIndexes = embeddedTrades.toList(),
                            embeddedLedgerIndexes = embeddedLedgers.toList(),
                            postBaselineTradeIndexes = postTrades.toList(),
                            postBaselineLedgerIndexes = postLedgers.toList(),
                            lateAssignment = late,
                            resultingBalances = late.balances,
                            ledgerDeltas = ledgerDeltas + late.ledgerDeltas,
                        )
                    } else {
                        null
                    }
                }

                if (candidateMatch != null) {
                    if (match == null) {
                        match = candidateMatch
                    } else {
                        multipleMatches = true
                    }
                }
                return
            }

            val candidate = initialCandidates[position]
            when (candidate) {
                is LateCandidate.Trade -> embeddedTrades.add(candidate.index)
                is LateCandidate.Ledger -> embeddedLedgers.add(candidate.index)
            }
            search(position + 1)
            when (candidate) {
                is LateCandidate.Trade -> embeddedTrades.removeAt(embeddedTrades.lastIndex)
                is LateCandidate.Ledger -> embeddedLedgers.removeAt(embeddedLedgers.lastIndex)
            }
            if (multipleMatches) return

            when (candidate) {
                is LateCandidate.Trade -> postTrades.add(candidate.index)
                is LateCandidate.Ledger -> postLedgers.add(candidate.index)
            }
            search(position + 1)
            when (candidate) {
                is LateCandidate.Trade -> postTrades.removeAt(postTrades.lastIndex)
                is LateCandidate.Ledger -> postLedgers.removeAt(postLedgers.lastIndex)
            }
        }

        search(position = 0)
        return if (multipleMatches) null else match
    }

    private fun findLateAssignment(
        candidates: List<LateCandidate>,
        startingBalances: Map<String, BigDecimal>,
        snapshot: PortfolioSnapshot,
        accountingMode: TradeAccountingMode,
        useAuthoritativeLedgerBalances: Boolean,
    ): LateAssignment? {
        if (candidates.isEmpty() || candidates.size > MAX_BOUNDARY_EVENT_CANDIDATES) return null

        var match: LateAssignment? = null
        var multipleMatches = false
        val selectedTrades = mutableListOf<Int>()
        val selectedLedgers = mutableListOf<Int>()
        val selectedLedgerDeltas = mutableMapOf<Int, BigDecimal>()

        fun search(position: Int, balances: Map<String, BigDecimal>) {
            if (multipleMatches) return
            if (position == candidates.size) {
                if ((selectedTrades.isNotEmpty() || selectedLedgers.isNotEmpty()) &&
                    balancesMatchSnapshot(balances, snapshot)
                ) {
                    val candidateMatch = LateAssignment(
                        tradeIndexes = selectedTrades.toList(),
                        ledgerIndexes = selectedLedgers.toList(),
                        balances = balances.toMap(),
                        ledgerDeltas = selectedLedgerDeltas.toMap(),
                    )
                    if (match == null) {
                        match = candidateMatch
                    } else {
                        multipleMatches = true
                    }
                }
                return
            }

            search(position + 1, balances)
            if (multipleMatches) return

            when (val candidate = candidates[position]) {
                is LateCandidate.Trade -> {
                    val nextBalances = balances.toMutableMap()
                    if (applyRealizedTrade(nextBalances, candidate.trade, accountingMode)) {
                        selectedTrades += candidate.index
                        search(position + 1, nextBalances)
                        selectedTrades.removeAt(selectedTrades.lastIndex)
                    }
                }

                is LateCandidate.Ledger -> {
                    val nextBalances = balances.toMutableMap()
                    val appliedDelta = applyLedgerEvent(
                        nextBalances,
                        candidate.ledger,
                        useAuthoritativeLedgerBalances,
                    )
                    selectedLedgers += candidate.index
                    selectedLedgerDeltas[candidate.index] = appliedDelta
                    search(position + 1, nextBalances)
                    selectedLedgerDeltas.remove(candidate.index)
                    selectedLedgers.removeAt(selectedLedgers.lastIndex)
                }
            }
        }

        search(position = 0, balances = startingBalances)
        return if (multipleMatches) null else match
    }

    private fun buildBenchmarkEvents(
        trades: List<ReconciledTrade>,
        ledgers: List<ReconciledLedger>,
        knownRebalancerOrderTxids: Set<String>,
        baseline: PortfolioSnapshot,
    ): List<BenchmarkEvent> {
        val events = mutableListOf<BenchmarkEvent>()
        for (reconciledLedger in ledgers) {
            val ledger = reconciledLedger.ledger
            if (!reconciledLedger.embeddedInBaseline &&
                reconciledLedger.timestamp > baseline.timestamp &&
                (baseline.balancesObservedAt == null || ledger.time > baseline.balancesObservedAt)
            ) {
                events += BenchmarkEvent.ExternalBalance(
                    timestamp = reconciledLedger.timestamp,
                    asset = ledger.asset,
                    netAmount = reconciledLedger.netBalanceDelta,
                    event = ledger,
                )
            }
        }
        for (reconciledTrade in trades) {
            val trade = reconciledTrade.trade
            if (!reconciledTrade.embeddedInBaseline &&
                reconciledTrade.timestamp > baseline.timestamp &&
                (baseline.balancesObservedAt == null || trade.timestamp > baseline.balancesObservedAt)
            ) {
                val ownership = TradeOwnershipClassifier.classify(
                    trade = trade,
                    knownRebalancerOrderTxids = knownRebalancerOrderTxids,
                )
                events += BenchmarkEvent.Trade(
                    timestamp = reconciledTrade.timestamp,
                    trade = trade,
                    ownership = ownership,
                    usdNotional = reconciledTrade.usdNotional,
                )
            }
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
                    applyRealizedTrade(balances, event.trade, event.usdNotional)
                }
            }
        }
    }

    private fun applyLedgerEvent(
        balances: MutableMap<String, BigDecimal>,
        ledger: LedgerEvent,
        useAuthoritativeBalance: Boolean = false,
    ): BigDecimal {
        val symbol = Asset.normalizeLedgerAsset(ledger.asset).uppercase()
        if (symbol in balances) {
            val currentBalance = balances.getValue(symbol)
            val netDelta = ledger.netBalanceDelta()
            val authoritativeDelta = if (useAuthoritativeBalance && ledger.hasAuthoritativeBalance) {
                ledger.balance.subtract(currentBalance)
            } else {
                null
            }
            // Existing rows may have a fee rounded to four decimals. Use the stored post-event
            // balance only as a compatible correction, not as an arbitrary replacement for the
            // ledger economics; this also keeps an embedded event from becoming a false zero-delta
            // post-baseline match during boundary assignment.
            val delta = if (
                authoritativeDelta != null &&
                authoritativeDelta.subtract(netDelta).abs() <= legacyLedgerFeeDeltaTolerance
            ) {
                authoritativeDelta
            } else {
                netDelta
            }
            balances[symbol] = currentBalance.add(delta)
            return delta
        }
        return ledger.netBalanceDelta()
    }

    private fun applyRealizedTrade(
        balances: MutableMap<String, BigDecimal>,
        trade: TradeRecord,
        accountingMode: TradeAccountingMode,
    ): Boolean = applyRealizedTrade(balances, trade, realizedUsdNotional(trade, accountingMode))

    private fun applyRealizedTrade(
        balances: MutableMap<String, BigDecimal>,
        trade: TradeRecord,
        usdNotional: BigDecimal,
    ): Boolean {
        val side = trade.side.uppercase()
        val symbol = trade.symbol.uppercase()
        if (
            symbol == Asset.USD ||
            !Asset.matchesUsdQuotedPair(trade.pair, symbol) ||
            trade.volume.signum() < 0 ||
            trade.usdAmount.signum() < 0 ||
            trade.price.signum() < 0 ||
            trade.fee.signum() < 0 ||
            usdNotional.signum() < 0
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
                val usdCost = usdNotional.add(trade.fee)
                balances[Asset.USD] = usdBalance.subtract(usdCost)
            }

            OrderSide.isSell(side) -> {
                balances[symbol] = assetBalance.subtract(trade.volume)
                val usdProceeds = usdNotional.subtract(trade.fee)
                balances[Asset.USD] = usdBalance.add(usdProceeds)
            }

            else -> return false
        }
        return true
    }

    private fun realizedUsdNotional(trade: TradeRecord, accountingMode: TradeAccountingMode): BigDecimal {
        val preciseNotional = if (trade.source == TradeSource.API_FILL && trade.price.signum() > 0) {
            trade.price.multiply(trade.volume)
        } else {
            trade.usdAmount
        }
        return if (
            accountingMode == TradeAccountingMode.PERSISTED_ROUNDED_COST &&
            trade.source == TradeSource.API_FILL &&
            trade.price.signum() > 0 &&
            preciseNotional.setScale(PrecisionConstants.SCALE_USD, RoundingMode.HALF_UP)
                .compareTo(trade.usdAmount.setScale(PrecisionConstants.SCALE_USD, RoundingMode.HALF_UP)) == 0
        ) {
            trade.usdAmount
        } else {
            preciseNotional
        }
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
