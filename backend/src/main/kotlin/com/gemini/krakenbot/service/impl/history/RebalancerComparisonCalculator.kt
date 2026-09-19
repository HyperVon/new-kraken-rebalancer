package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.CardFeePriceProvider
import com.gemini.krakenbot.model.ComparisonAvailability
import com.gemini.krakenbot.model.ComparisonConfidence
import com.gemini.krakenbot.model.ComparisonUnavailableReason
import com.gemini.krakenbot.model.FlowCategory
import com.gemini.krakenbot.model.FundingProvenanceResolver
import com.gemini.krakenbot.model.KrakenApiConstants
import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.model.LedgerFlowClassifier
import com.gemini.krakenbot.model.NormalizedFundingTransaction
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.RebalancerComparison
import com.gemini.krakenbot.model.RebalancerComparisonPoint
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.model.TradeSource
import com.gemini.krakenbot.util.PrecisionConstants
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

object RebalancerComparisonCalculator {
    private val log = LoggerFactory.getLogger(RebalancerComparisonCalculator::class.java)

    private val baselineMismatchTolerance = BigDecimal("0.01")

    // A HALF_UP four-decimal fee parse can lose at most half of one 4-decimal unit.
    // This is also the sole envelope under which a persisted authoritative ledger balance may
    // correct the replayed net delta (amount - fee). When |authoritativeDelta - netDelta|
    // exceeds it, the authoritative checkpoint is rejected: a Spot-continuing row fails closed
    // with UNEXPLAINED_BALANCE_CHANGE rather than silently switching to the checkpoint delta,
    // and a non-Spot row (opaque staking sub-ledger) is excluded from Spot replay so ledger
    // net-delta economics carry the series. Never widen this without rebalancing both classes.
    private val legacyLedgerFeeDeltaTolerance = BigDecimal("0.00005")

    // A persisted authoritative balance may be accepted as the gross ledger movement only when
    // it matches the raw amount at crypto precision; the wider fee tolerance must not turn a
    // stale checkpoint into a false exact match.
    private val ledgerGrossDeltaTolerance = BigDecimal("0.00000001")
    private val externalBalanceLedgerTypes = LedgerEvent.EXTERNAL_BALANCE_TYPES
    private val supportedLedgerTypes =
        (setOf(KrakenApiConstants.LEDGER_TYPE_TRADE) + externalBalanceLedgerTypes)
            .map(String::lowercase)
            .toSet()

    // Bounded window (1,000ms) admitting clock skew and exchange timestamp truncation/precision differences
    // when an exchange event was already executed and reflected in observed balances.
    internal const val MAX_EVENT_OBSERVATION_CLOCK_SKEW_MILLIS = 1_000L

    // A shared cap bounds the combined initial/late search to at most 2^12 assignments.
    private const val MAX_BOUNDARY_EVENT_CANDIDATES = 12

    // Inception weight fractions: 10 decimals, far below any allocation dust.
    private const val WEIGHT_DIVISION_SCALE = 10

    // Synthetic contribution units: crypto scale 8 like the rest of the engine.
    private const val ALLOCATION_UNIT_SCALE = 8

    // Withdrawal shrink factor: 10 decimals so proportional cuts stay exact.
    private const val WITHDRAWAL_FACTOR_SCALE = 10

    // Movement attribution fraction: 16 decimals so a partially basket-held drawdown keeps
    // its proportion without rounding value into or out of the synthetic basket.
    private const val MOVEMENT_FRACTION_SCALE = 16

    // Withdrawals overshooting synthetic holdings by more than a dollar of
    // rounding dust fail closed instead of flooring to a false fresh start.
    private val OVERDRAWN_DUST_TOLERANCE_USD = BigDecimal("1.00")

    suspend fun calculate(
        snapshots: List<PortfolioSnapshot>,
        trades: List<TradeRecord>,
        rewards: List<LedgerEvent> = emptyList(),
        anchorSnapshot: PortfolioSnapshot? = null,
        inceptionSnapshot: PortfolioSnapshot? = null,
        knownInceptionTime: Instant? = null,
        historyTruncated: Boolean = false,
        priceProvider: HistoricalPriceProvider? = null,
        provenanceResolver: FundingProvenanceResolver = FundingProvenanceResolver.NONE,
        inceptionUnavailableReason: ComparisonUnavailableReason? = null,
        ledgerContext: List<LedgerEvent> = emptyList(),
        configuredAssetUniverse: Set<String>? = null,
    ): RebalancerComparison {
        inceptionUnavailableReason?.let { reason ->
            return unavailable(
                reason = reason,
                unavailableAt = snapshots.lastOrNull()?.timestamp,
                baselineTimestamp = knownInceptionTime,
            )
        }
        if (historyTruncated) {
            // The local history cannot support a lifetime baseline (retention
            // removed pre-inception evidence on a migrated install). Any
            // window-anchored number would be a plausible-looking falsehood.
            return unavailable(
                reason = ComparisonUnavailableReason.INCEPTION_HISTORY_TRUNCATED,
                unavailableAt = snapshots.lastOrNull()?.timestamp,
                baselineTimestamp = knownInceptionTime,
            )
        }
        if (inceptionSnapshot == null && knownInceptionTime != null) {
            // Fail closed: inception is known but its baseline snapshot is no
            // longer retained (pruned). Anchoring B&H to the window start
            // would silently compare against the wrong baseline.
            return unavailable(
                reason = ComparisonUnavailableReason.INCEPTION_SNAPSHOT_PRUNED,
                unavailableAt = snapshots.lastOrNull()?.timestamp,
                baselineTimestamp = knownInceptionTime,
            )
        }
        if (snapshots.size < 2) {
            val firstTime = snapshots.firstOrNull()?.timestamp
            return unavailable(
                reason = ComparisonUnavailableReason.INSUFFICIENT_SNAPSHOTS,
                unavailableAt = firstTime,
                baselineTimestamp = firstTime,
            )
        }
        val orderedSnapshots = snapshots.sortedBy(PortfolioSnapshot::timestamp)
        val (baseline, effectiveSnapshots) = if (inceptionSnapshot != null) {
            val sameInstantSnapshots = orderedSnapshots.filter { it.timestamp == inceptionSnapshot.timestamp }
            val completeSameInstantSnapshots = sameInstantSnapshots.filter { snapshot ->
                normalizedAssetUniverse(snapshot.assets.keys).containsAll(
                    normalizedAssetUniverse(inceptionSnapshot.assets.keys),
                )
            }
            val requiredAssetUniverse = requiredConfiguredAssetUniverse(inceptionSnapshot, configuredAssetUniverse)
            val incompleteSameInstantMismatch = sameInstantSnapshots.firstOrNull { snapshot ->
                !normalizedAssetUniverse(snapshot.assets.keys).containsAll(
                    normalizedAssetUniverse(inceptionSnapshot.assets.keys),
                ) && !configuredBalancesMatch(snapshot, inceptionSnapshot, requiredAssetUniverse)
            }
            if (incompleteSameInstantMismatch != null) {
                return unavailable(
                    reason = ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE,
                    unavailableAt = incompleteSameInstantMismatch.timestamp,
                    baselineTimestamp = inceptionSnapshot.timestamp,
                )
            }
            val postInception = orderedSnapshots.filter { it.timestamp > inceptionSnapshot.timestamp }
            val trimmed = if (orderedSnapshots.first().timestamp <= inceptionSnapshot.timestamp) {
                // The approved baseline is the complete wallet state. A legacy/configured-only
                // row at the same instant is not a valid replacement for it, even when identity
                // filtering has removed the approved anchor from the series. Keep a complete
                // same-time reconstruction when one exists; otherwise restore the approved row.
                (completeSameInstantSnapshots.ifEmpty { listOf(inceptionSnapshot) }) + postInception
            } else {
                orderedSnapshots
            }
            if (trimmed.size < 2) {
                return unavailable(
                    reason = ComparisonUnavailableReason.INSUFFICIENT_SNAPSHOTS,
                    unavailableAt = orderedSnapshots.last().timestamp,
                    baselineTimestamp = inceptionSnapshot.timestamp,
                )
            }
            if (!normalizedAssetUniverse(trimmed.first().assets.keys).containsAll(requiredAssetUniverse)) {
                return unavailable(
                    reason = ComparisonUnavailableReason.ASSET_UNIVERSE_CHANGED,
                    unavailableAt = trimmed.first().timestamp,
                    baselineTimestamp = inceptionSnapshot.timestamp,
                )
            }
            inceptionSnapshot to trimmed
        } else {
            orderedSnapshots.first() to orderedSnapshots
        }

        val universeError = validateAssetUniverse(effectiveSnapshots, baseline, configuredAssetUniverse)
        if (universeError != null) return universeError

        val baselineError = validateBaseline(baseline)
        if (baselineError != null) return baselineError
        val negativeSnapshot = effectiveSnapshots.firstOrNull { snapshot ->
            snapshot.totalValueUSD.signum() < 0 || snapshot.assets.values.any { asset ->
                asset.balance.signum() < 0 || asset.valueUSD.signum() < 0
            }
        }
        if (negativeSnapshot != null) {
            return unavailable(
                reason = ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE,
                unavailableAt = negativeSnapshot.timestamp,
                baselineTimestamp = baseline.timestamp,
            )
        }

        val priceError = try {
            validatePrices(effectiveSnapshots, baseline, priceProvider)
        } catch (e: HistoricalPriceSourceException) {
            return unavailable(
                reason = ComparisonUnavailableReason.HISTORICAL_PRICE_SOURCE_ERROR,
                unavailableAt = e.eventTime,
                baselineTimestamp = baseline.timestamp,
            )
        }
        if (priceError != null) return priceError

        val effectiveAnchor = anchorSnapshot?.takeIf {
            it.timestamp < baseline.timestamp && it.assets.keys.containsAll(baseline.assets.keys)
        }
        val firstEffectiveSnapshot = effectiveSnapshots.firstOrNull()
        val hasMissingPositiveBaselineAsset = firstEffectiveSnapshot != null && baseline.assets.any { (symbol, asset) ->
            asset.balance.signum() > 0 && symbol !in firstEffectiveSnapshot.assets
        }
        val shouldPrependBaseline = firstEffectiveSnapshot != null &&
            (
                hasMissingPositiveBaselineAsset ||
                    (firstEffectiveSnapshot.timestamp == baseline.timestamp && firstEffectiveSnapshot != baseline)
                )
        val validationSnapshots = when {
            effectiveAnchor != null && shouldPrependBaseline -> {
                listOf(effectiveAnchor, baseline) + effectiveSnapshots
            }

            effectiveAnchor != null -> listOf(effectiveAnchor) + effectiveSnapshots

            shouldPrependBaseline -> listOf(baseline) + effectiveSnapshots

            else -> effectiveSnapshots
        }

        // Surface structural ledger errors before balance-continuity validation. The validator
        // intentionally reports balance evidence failures, while the comparison contract keeps
        // unsupported conversion/internal-marker shapes distinguishable from those failures.
        val structuralLedgerClassifications = LedgerFlowClassifier.classifyAll(rewards)
        val structurallyUnsupportedLedger = rewards.firstOrNull { event ->
            val normalizedType = event.type.trim().lowercase()
            structuralLedgerClassifications[event.ledgerId] == FlowCategory.UNSUPPORTED &&
                (
                    normalizedType !in supportedLedgerTypes ||
                        normalizedType == KrakenApiConstants.LEDGER_TYPE_CONVERSION ||
                        LedgerFlowClassifier.isDocumentedInternalTransfer(event)
                    )
        }
        if (structurallyUnsupportedLedger != null) {
            return unavailable(
                reason = ComparisonUnavailableReason.UNSUPPORTED_LEDGER_TYPE,
                unavailableAt = structurallyUnsupportedLedger.time,
                baselineTimestamp = baseline.timestamp,
            )
        }

        // The recorded series tracks spot-wallet balances: ledger rows resolved to another
        // Kraken wallet scope (staking/futures) never moved the series, so they must not move
        // reconciliation balances either. Unresolved scopes keep the previous behavior.
        // Non-authoritative owner/plumbing rows have no post-entry checkpoint to resolve and are
        // therefore deliberately excluded from this wallet-scope search. They remain available
        // to the provenance/classification path below.
        val ledgerValidation = AuthoritativeLedgerBalanceValidator.validate(
            (ledgerContext + rewards).filter(LedgerEvent::hasAuthoritativeBalance),
        )
        ledgerValidation.failure?.let { failure ->
            return unavailable(
                reason = ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE,
                unavailableAt = failure.currentTime,
                baselineTimestamp = baseline.timestamp,
            )
        }
        val ledgerScopes = ledgerValidation.resolvedScopes
        val unresolvedAuthoritativeLedger = rewards.firstOrNull { event ->
            event.hasAuthoritativeBalance && event.ledgerId !in ledgerScopes
        }
        if (unresolvedAuthoritativeLedger != null) {
            return unavailable(
                reason = ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE,
                unavailableAt = unresolvedAuthoritativeLedger.time,
                baselineTimestamp = baseline.timestamp,
            )
        }
        val spotRewards = rewards.filter { ledger ->
            !ledger.hasAuthoritativeBalance ||
                ledgerScopes[ledger.ledgerId] == AuthoritativeLedgerBalanceValidator.LedgerWalletScope.SPOT
        }
        // Kraken charges trade-ledger fees in each leg's own asset (often the base) and ledger
        // amounts can round differently from the reported volume. Retained Spot trade legs are
        // the authoritative wallet effect for comparison; non-Spot legs must not leak into a
        // Spot trade's replay while the TradeRecord economics remain the fallback.
        val tradeLegsByRefId = spotRewards
            .filter { it.type.equals(KrakenApiConstants.LEDGER_TYPE_TRADE, ignoreCase = true) }
            .filter { !it.refid.isNullOrBlank() }
            .groupBy { it.refid!!.trim() }
        val orphanTradeLedgerEvents = AuthoritativeTradeLedgerEvents.collect(rewards, trades, ledgerScopes)
        val ambiguousTradeLedgerRefId = sequenceOf(
            orphanTradeLedgerEvents.incompleteRefIds,
            orphanTradeLedgerEvents.contradictoryRefIds,
            orphanTradeLedgerEvents.ambiguousIdentityRefIds,
        ).flatten().firstOrNull()
        if (ambiguousTradeLedgerRefId != null) {
            val ambiguousAt = rewards.firstOrNull {
                it.refid?.trim() == ambiguousTradeLedgerRefId
            }?.time ?: baseline.timestamp
            return unavailable(
                reason = ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE,
                unavailableAt = ambiguousAt,
                baselineTimestamp = baseline.timestamp,
            )
        }
        val orphanTradeLedgerIds = orphanTradeLedgerEvents.replayableLegs.mapTo(linkedSetOf()) { it.ledgerId }
        val tradeLegsByTradeIdentity = orphanTradeLedgerEvents.tradeLegsByTradeIdentity

        val balanceResult = validateTrackedBalanceChanges(
            snapshots = validationSnapshots,
            trades = trades,
            ledgers = spotRewards,
            baseline = baseline,
            tradeLegsByRefId = tradeLegsByRefId,
            tradeLegsByTradeIdentity = tradeLegsByTradeIdentity,
            resolvedScopes = ledgerScopes,
            orphanTradeLedgerIds = orphanTradeLedgerIds,
            configuredAssetUniverse = configuredAssetUniverse,
        )

        val reconciledLedgers = when (balanceResult) {
            is TrackedBalanceValidation.Failed -> {
                return unavailable(
                    reason = balanceResult.reason,
                    unavailableAt = balanceResult.unavailableAt ?: baseline.timestamp,
                    baselineTimestamp = baseline.timestamp,
                )
            }

            is TrackedBalanceValidation.Passed -> balanceResult.ledgers
        }

        val windowObservationStart = validationSnapshots.first().balancesObservedAt
            ?: validationSnapshots.first().timestamp.minusMillis(MAX_EVENT_OBSERVATION_CLOCK_SKEW_MILLIS)

        // Ledger rows that are unrecognized or economically ambiguous cannot
        // be replayed safely; surface UNAVAILABLE instead of silently dropping
        // a balance-affecting flow.
        val preparedProvenanceResolver = provenanceResolver.prepare(rewards)
        preparedProvenanceResolver.preparationFailure?.let { failure ->
            log.warn(
                "Funding provenance unavailable for comparison ({}): {}",
                failure.reason,
                failure.message,
            )
            return unavailable(
                reason = ComparisonUnavailableReason.FUNDING_PROVENANCE_UNAVAILABLE,
                unavailableAt = rewards.firstOrNull()?.time,
                baselineTimestamp = baseline.timestamp,
            )
        }
        val ledgerClassifications = LedgerFlowClassifier.classifyAll(rewards, preparedProvenanceResolver)
        val ambiguousLedger = rewards.firstOrNull {
            ledgerClassifications[it.ledgerId] == FlowCategory.AMBIGUOUS
        }
        if (ambiguousLedger != null) {
            return unavailable(
                reason = ComparisonUnavailableReason.AMBIGUOUS_LEDGER_TYPE,
                unavailableAt = ambiguousLedger.time,
                baselineTimestamp = baseline.timestamp,
            )
        }
        val unsupportedLedger = rewards.firstOrNull {
            ledgerClassifications[it.ledgerId] == FlowCategory.UNSUPPORTED
        }
        if (unsupportedLedger != null) {
            return unavailable(
                reason = ComparisonUnavailableReason.UNSUPPORTED_LEDGER_TYPE,
                unavailableAt = unsupportedLedger.time,
                baselineTimestamp = baseline.timestamp,
            )
        }

        val intermediateLedgers = if (baseline.timestamp < windowObservationStart) {
            spotRewards.filter {
                it.type in externalBalanceLedgerTypes &&
                    ledgerClassifications[it.ledgerId] != FlowCategory.INTERNAL_MOVE &&
                    ledgerClassifications[it.ledgerId] != FlowCategory.TRADE_IGNORED &&
                    it.time > baseline.timestamp &&
                    it.time <= windowObservationStart
            }.map { ReconciledLedger(it, it.time, it.netBalanceDelta()) }
        } else {
            emptyList()
        }

        // Recorded-anchor value weights define the benchmark thesis. Every later owner
        // contribution is invested by the same weights instead of leaving new money in cash.
        val inceptionWeights = baselineValueWeights(baseline)

        val feePriceProvider = CardFeePriceProvider { feeAsset, timestamp ->
            priceProvider?.priceAt(feeAsset, timestamp)
        }
        val cardNormalizations = try {
            CardFundingNormalizer.normalizeAll(
                events = spotRewards,
                provenanceResolver = preparedProvenanceResolver,
                priceProvider = feePriceProvider,
            )
        } catch (e: HistoricalPriceSourceException) {
            return unavailable(
                reason = ComparisonUnavailableReason.HISTORICAL_PRICE_SOURCE_ERROR,
                unavailableAt = e.eventTime,
                baselineTimestamp = baseline.timestamp,
            )
        }

        val benchmarkBuilt = try {
            buildBenchmarkEvents(
                ledgers = intermediateLedgers + reconciledLedgers.filterNot {
                    it.ledger.ledgerId in orphanTradeLedgerIds
                },
                baseline = baseline,
                inceptionWeights = inceptionWeights,
                priceProvider = priceProvider,
                classifications = ledgerClassifications,
                cardNormalizations = cardNormalizations,
                rewards = rewards,
            )
        } catch (e: HistoricalPriceSourceException) {
            return unavailable(
                reason = ComparisonUnavailableReason.HISTORICAL_PRICE_SOURCE_ERROR,
                unavailableAt = e.eventTime,
                baselineTimestamp = baseline.timestamp,
            )
        }
        if (benchmarkBuilt.ambiguousAt != null) {
            return unavailable(
                reason = ComparisonUnavailableReason.AMBIGUOUS_LEDGER_TYPE,
                unavailableAt = benchmarkBuilt.ambiguousAt,
                baselineTimestamp = baseline.timestamp,
            )
        }
        if (benchmarkBuilt.unpriceableAt != null) {
            return unavailable(
                reason = ComparisonUnavailableReason.MISSING_PRICE,
                unavailableAt = benchmarkBuilt.unpriceableAt,
                baselineTimestamp = baseline.timestamp,
            )
        }

        val benchmarkEvents = benchmarkBuilt.events.sortedBy { it.timestamp }
        val unorderedAt = findUnorderedBenchmarkEventTimestamp(
            events = benchmarkEvents,
            baselineAssetSymbols = baseline.assets.keys
                .map { Asset.normalizeLedgerAsset(it).uppercase() }
                .toSet(),
        )
        if (unorderedAt != null) {
            return unavailable(
                reason = ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE,
                unavailableAt = unorderedAt,
                baselineTimestamp = baseline.timestamp,
            )
        }

        val baselineBalances = extractBaselineBalances(baseline)
        val runningSyntheticBalances = baselineBalances.toMutableMap()
        var eventIndex = 0

        val points = mutableListOf<RebalancerComparisonPoint>()
        for (snapshot in effectiveSnapshots) {
            while (eventIndex < benchmarkEvents.size && benchmarkEvents[eventIndex].timestamp <= snapshot.timestamp) {
                val replayFailure = try {
                    replayBenchmarkEvent(
                        runningSyntheticBalances,
                        benchmarkEvents[eventIndex],
                        priceProvider,
                    )
                } catch (e: HistoricalPriceSourceException) {
                    return unavailable(
                        reason = ComparisonUnavailableReason.HISTORICAL_PRICE_SOURCE_ERROR,
                        unavailableAt = e.eventTime,
                        baselineTimestamp = baseline.timestamp,
                    )
                }
                if (replayFailure != null) {
                    return unavailable(
                        reason = replayFailure.first,
                        unavailableAt = replayFailure.second,
                        baselineTimestamp = baseline.timestamp,
                    )
                }
                eventIndex++
            }

            val buyAndHoldValue = calculateBuyAndHoldValue(
                syntheticBalances = runningSyntheticBalances,
                snapshot = snapshot,
                baselineTimestamp = baseline.timestamp,
                baselinePrices = baseline.assets.mapValues { it.value.price },
                priceProvider = priceProvider,
            )
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

        val isStartingAtBaseline = effectiveSnapshots.first().timestamp == baseline.timestamp
        if (isStartingAtBaseline) {
            val baselineFirstPoint = points.first()
            val firstDiffFromCalc = baselineFirstPoint.rebalancerValueUSD
                .subtract(baselineFirstPoint.buyAndHoldValueUSD)
                .abs()
            val expectedInitialDifference = BigDecimal.ZERO.setScale(
                PrecisionConstants.SCALE_USD,
                RoundingMode.HALF_UP,
            )
            if (firstDiffFromCalc.subtract(expectedInitialDifference).abs() > baselineMismatchTolerance) {
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
        data class Passed(val ledgers: List<ReconciledLedger>) : TrackedBalanceValidation()

        data class Failed(val reason: ComparisonUnavailableReason, val unavailableAt: Instant?) :
            TrackedBalanceValidation()
    }

    private enum class TradeAccountingMode {
        PRECISE_FILL_NOTIONAL,
        PERSISTED_ROUNDED_COST,
        PERSISTED_TRADE_ECONOMICS,
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
        val hasInvalidAsset = baseline.assets.values.any { asset ->
            asset.balance.signum() < 0 ||
                asset.valueUSD.signum() < 0
        }
        if (hasInvalidAsset) {
            return unavailable(
                reason = ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE,
                unavailableAt = baseline.timestamp,
                baselineTimestamp = baseline.timestamp,
            )
        }
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
        configuredAssetUniverse: Set<String>?,
    ): RebalancerComparison? {
        val requiredAssetUniverse = requiredConfiguredAssetUniverse(baseline, configuredAssetUniverse)
        if (!matchesConfiguredAssetUniverse(baseline, requiredAssetUniverse)) {
            return unavailable(
                reason = ComparisonUnavailableReason.ASSET_UNIVERSE_CHANGED,
                unavailableAt = baseline.timestamp,
                baselineTimestamp = baseline.timestamp,
            )
        }
        for (snapshot in snapshots) {
            if (!matchesConfiguredAssetUniverse(snapshot, requiredAssetUniverse)) {
                return unavailable(
                    reason = ComparisonUnavailableReason.ASSET_UNIVERSE_CHANGED,
                    unavailableAt = snapshot.timestamp,
                    baselineTimestamp = baseline.timestamp,
                )
            }
        }
        return null
    }

    private fun matchesConfiguredAssetUniverse(
        snapshot: PortfolioSnapshot,
        requiredAssetUniverse: Set<String>,
    ): Boolean = normalizedAssetUniverse(snapshot.assets.keys).containsAll(requiredAssetUniverse) &&
        snapshot.assets.none { (symbol, asset) ->
            Asset.normalizeLedgerAsset(symbol).uppercase() !in requiredAssetUniverse &&
                asset.targetPercent.signum() > 0
        }

    private suspend fun validatePrices(
        snapshots: List<PortfolioSnapshot>,
        baseline: PortfolioSnapshot,
        priceProvider: HistoricalPriceProvider?,
    ): RebalancerComparison? {
        val baselineKeys = baseline.assets.filterValues { it.balance.signum() != 0 }.keys
        for (snapshot in snapshots) {
            for (symbol in baselineKeys) {
                if (symbol == Asset.USD) continue
                // Configured-universe validation guarantees target rows are present. Historical-only
                // wallet rows may legitimately disappear after their balance reaches zero.
                val assetRow = snapshot.assets[symbol] ?: continue
                if (assetRow.price.signum() < 0) {
                    return unavailable(
                        reason = ComparisonUnavailableReason.MISSING_PRICE,
                        unavailableAt = snapshot.timestamp,
                        baselineTimestamp = baseline.timestamp,
                    )
                }
                val hasPositivePrice = assetRow.price.signum() > 0
                if (!hasPositivePrice) {
                    val historicalPrice = priceProvider?.priceAt(symbol, snapshot.timestamp)
                    if (historicalPrice == null || historicalPrice.signum() <= 0) {
                        return unavailable(
                            reason = ComparisonUnavailableReason.MISSING_PRICE,
                            unavailableAt = snapshot.timestamp,
                            baselineTimestamp = baseline.timestamp,
                        )
                    }
                }
            }
        }
        return null
    }

    private fun requiredConfiguredAssetUniverse(
        baseline: PortfolioSnapshot,
        configuredAssetUniverse: Set<String>?,
    ): Set<String> = configuredAssetUniverse?.let(::normalizedAssetUniverse)
        ?: normalizedAssetUniverse(
            baseline.assets
                .filterValues { it.targetPercent.signum() > 0 }
                .keys,
        )

    private fun normalizedAssetUniverse(symbols: Iterable<String>): Set<String> = symbols
        .map { Asset.normalizeLedgerAsset(it).uppercase() }
        .toSet()

    private fun configuredBalancesMatch(
        snapshot: PortfolioSnapshot,
        baseline: PortfolioSnapshot,
        configuredAssetUniverse: Set<String>,
    ): Boolean {
        val baselineBalances = normalizedAssetBalances(baseline)
        val snapshotBalances = normalizedAssetBalances(snapshot)
        return configuredAssetUniverse
            .all { symbol ->
                (baselineBalances[symbol] ?: BigDecimal.ZERO)
                    .compareTo(snapshotBalances[symbol] ?: BigDecimal.ZERO) == 0
            }
    }

    private fun normalizedAssetBalances(snapshot: PortfolioSnapshot): Map<String, BigDecimal> = snapshot.assets
        .mapKeys { (symbol, _) -> Asset.normalizeLedgerAsset(symbol).uppercase() }
        .mapValues { (_, asset) -> asset.balance }

    private fun validateTrackedBalanceChanges(
        snapshots: List<PortfolioSnapshot>,
        trades: List<TradeRecord>,
        ledgers: List<LedgerEvent>,
        baseline: PortfolioSnapshot,
        tradeLegsByRefId: Map<String, List<LedgerEvent>>,
        tradeLegsByTradeIdentity: Map<String, List<LedgerEvent>>,
        resolvedScopes: Map<String, AuthoritativeLedgerBalanceValidator.LedgerWalletScope>,
        orphanTradeLedgerIds: Set<String>,
        configuredAssetUniverse: Set<String>?,
    ): TrackedBalanceValidation {
        val invalidFeeLedger = ledgers.firstOrNull { it.type in externalBalanceLedgerTypes && !it.hasValidFee }
        if (invalidFeeLedger != null) {
            return TrackedBalanceValidation.Failed(
                reason = ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE,
                unavailableAt = invalidFeeLedger.time,
            )
        }
        val invalidAmountLedger = ledgers.firstOrNull {
            it.type in externalBalanceLedgerTypes && !LedgerFlowClassifier.hasValidAmountShape(it)
        }
        if (invalidAmountLedger != null) {
            return TrackedBalanceValidation.Failed(
                reason = ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE,
                unavailableAt = invalidAmountLedger.time,
            )
        }
        val startObservationTime = snapshots.first().balancesObservedAt
            ?: snapshots.first().timestamp.minusMillis(MAX_EVENT_OBSERVATION_CLOCK_SKEW_MILLIS)
        val lastSnapshot = snapshots.last()
        val lastObservationTime = lastSnapshot.balancesObservedAt ?: lastSnapshot.timestamp
        // With no trustworthy capture time for the final snapshot, nothing dated after its
        // timestamp can be owned by any interval; admitting it would leak a future event
        // backward into history.
        val maxEventTime = if (lastSnapshot.balancesObservedAt == null) {
            lastSnapshot.timestamp
        } else {
            latestCandidateTime(lastSnapshot)
        }

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
                val ok = (it.type in externalBalanceLedgerTypes || it.ledgerId in orphanTradeLedgerIds) &&
                    it.time > startObservationTime &&
                    it.time <= maxEventTime
                ok
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

        val baselineAssetSymbols = baseline.assets.keys
            .map { Asset.normalizeLedgerAsset(it).uppercase() }
            .toSet()

        // The approved baseline may own the complete wallet while the recorded series only
        // snapshots its tracked universe. A baseline holding outside that universe is invisible
        // to every row the series writes, so its absence is a writer-scope artifact, not an
        // unexplained change; reconciliation must not fail on it. An empty universe keeps the
        // strict behavior so the check still fails closed when no scope can be derived.
        val trackedSeriesUniverse = requiredConfiguredAssetUniverse(baseline, configuredAssetUniverse)

        fun untrackedBySeries(symbol: String): Boolean = trackedSeriesUniverse.isNotEmpty() &&
            Asset.normalizeLedgerAsset(symbol).uppercase() !in trackedSeriesUniverse

        val toleratedUntrackedSymbols = linkedSetOf<String>()

        fun reconcileInterval(
            i: Int,
            attempt: ReconciliationState,
            intervalAccountingMode: TradeAccountingMode,
        ): TrackedBalanceValidation.Failed? {
            val assignedTradeIndexes = attempt.assignedTradeIndexes

            // A trade can change tracked balances through either leg: a configured-target base
            // is tracked directly, and a supported quote is tracked whenever the quote asset is
            // part of the baseline (USD always is). Bases absent from the anchor therefore still settle
            // their quote leg instead of being skipped.
            fun affectsTrackedBalances(trade: TradeRecord): Boolean = tradeTouchesAssets(trade, baselineAssetSymbols)
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
                        affectsTrackedBalances(trade)
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

            // Interval ownership ends at curr.timestamp when its observation time is unknown;
            // a post-curr event then belongs to a later interval (or fails closed past the last
            // snapshot) and must not leak backward into this one.
            val lateUpperBound = if (curr.balancesObservedAt == null) {
                curr.timestamp
            } else {
                latestCandidateTime(curr)
            }
            val lateTradeCandidates = indexedTrades
                .filter { (index, trade) ->
                    index !in assignedTradeIndexes &&
                        index !in initialTradeIndices &&
                        trade.timestamp > currObs &&
                        trade.timestamp <= lateUpperBound &&
                        affectsTrackedBalances(trade)
                }
            val lateLedgerCandidates = indexedLedgers
                .filter { (index, ledger) ->
                    index !in assignedLedgerIndexes &&
                        index !in initialLedgerIndices &&
                        ledger.time > currObs &&
                        ledger.time <= lateUpperBound &&
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
                        affectsTrackedBalances(trade)
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
                        !affectsTrackedBalances(trade)
                    ) {
                        false
                    } else {
                        val nearUnknownPreviousBoundary = prev.balancesObservedAt == null &&
                            trade.timestamp > prev.timestamp.minusMillis(MAX_EVENT_OBSERVATION_CLOCK_SKEW_MILLIS) &&
                            trade.timestamp <= prev.timestamp.plusMillis(MAX_EVENT_OBSERVATION_CLOCK_SKEW_MILLIS)
                        val nearCurrentBoundary = if (curr.balancesObservedAt == null) {
                            trade.timestamp > curr.timestamp.minusMillis(MAX_EVENT_OBSERVATION_CLOCK_SKEW_MILLIS) &&
                                trade.timestamp <= curr.timestamp
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
                        val symbol = Asset.normalizeLedgerAsset(ledger.asset).uppercase()
                        // Configured-universe validation guarantees target rows, never historical-only
                        // rows: a reconciled zeroing drops the row from later snapshots. Absence is
                        // consistent only with an authoritative zero post-balance, so any other
                        // post-balance stays a boundary candidate instead of being read as zero.
                        val prevBalance = prev.assets[symbol]?.balance
                        val previousBalanceMatches = prevBalance?.let { it.compareTo(ledger.balance) == 0 }
                            ?: (ledger.balance.signum() == 0)
                        val alreadyEmbodiedInPrevious = ledger.time <= prev.timestamp &&
                            ledger.hasAuthoritativeBalance &&
                            previousBalanceMatches
                        val nearUnknownPreviousBoundary = !alreadyEmbodiedInPrevious &&
                            prev.balancesObservedAt == null &&
                            ledger.time > prev.timestamp.minusMillis(MAX_EVENT_OBSERVATION_CLOCK_SKEW_MILLIS) &&
                            ledger.time <= prev.timestamp.plusMillis(MAX_EVENT_OBSERVATION_CLOCK_SKEW_MILLIS)
                        val nearCurrentBoundary = if (curr.balancesObservedAt == null) {
                            ledger.time > curr.timestamp.minusMillis(MAX_EVENT_OBSERVATION_CLOCK_SKEW_MILLIS) &&
                                ledger.time <= curr.timestamp
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
                val candidateBalances = impliedBalances.toMutableMap()
                if (!applyRealizedTrade(
                        candidateBalances,
                        lateTrade,
                        intervalAccountingMode,
                        curr.assets.keys,
                        tradeLegsByRefId,
                        tradeLegsByTradeIdentity,
                    )
                ) {
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
                    val candidateBalances = impliedBalances.toMutableMap()
                    if (!applyRealizedTrade(
                            candidateBalances,
                            boundaryTrade,
                            intervalAccountingMode,
                            curr.assets.keys,
                            tradeLegsByRefId,
                            tradeLegsByTradeIdentity,
                        )
                    ) {
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
            val useAuthoritativeLedgerBalances = canUseAuthoritativeLedgerBalances(
                intervalLedgerCandidates,
                baseline.assets.keys,
                resolvedScopes,
            )

            // Pre-regulars state for late-ledger evaluation: boundary ledgers dated before
            // every regular event must be evaluated against the state at their own timestamp
            // (see findLateAssignment), not against the post-regulars running state.
            val preRegularBalances = impliedBalances.toMap()
            val minRegularEventTime = buildList {
                regularIntervalTrades.forEach { add(it.value.timestamp) }
                regularIntervalLedgers.forEach { add(it.value.time) }
            }.minOrNull()
            val anchorRelativeLedgerIndexes = lateCandidates
                .filterIsInstance<LateCandidate.Ledger>()
                .filter { minRegularEventTime == null || it.ledger.time < minRegularEventTime }
                .map { it.index }
                .toSet()

            if (initialTradeCandidates.isNotEmpty() || initialLedgerCandidates.isNotEmpty()) {
                for ((_, initialTrade) in initialTradeCandidates) {
                    val candidateBalances = impliedBalances.toMutableMap()
                    if (!applyRealizedTrade(
                            candidateBalances,
                            initialTrade,
                            intervalAccountingMode,
                            curr.assets.keys,
                            tradeLegsByRefId,
                            tradeLegsByTradeIdentity,
                        )
                    ) {
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
                    tradeLegsByRefId = tradeLegsByRefId,
                    tradeLegsByTradeIdentity = tradeLegsByTradeIdentity,
                    isUntrackedBySeries = ::untrackedBySeries,
                ) ?: run {
                    return TrackedBalanceValidation.Failed(
                        reason = ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE,
                        unavailableAt = curr.timestamp,
                    )
                }

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
                // Apply the interval in true chronological order so an authoritative checkpoint is
                // always evaluated against the state that existed at its own timestamp. Equal
                // instants keep reconstruction's ledger-before-trade ordering.
                val intervalEvents = buildIntervalEvents(regularIntervalTrades, regularIntervalLedgers)
                for (event in intervalEvents) {
                    when (event) {
                        is IntervalEvent.Ledger -> {
                            effectiveLedgerDeltas[event.index] = applyLedgerEvent(
                                impliedBalances,
                                event.event,
                                useAuthoritativeLedgerBalances,
                            )
                            assignedLedgerIndexes += event.index
                            effectiveLedgerTimestamps[event.index] =
                                calculateIntervalEventTimestamp(event.event.time, prev, curr)
                        }

                        is IntervalEvent.Trade -> {
                            if (!applyRealizedTrade(
                                    impliedBalances,
                                    event.trade,
                                    intervalAccountingMode,
                                    curr.assets.keys,
                                    tradeLegsByRefId,
                                    tradeLegsByTradeIdentity,
                                )
                            ) {
                                return TrackedBalanceValidation.Failed(
                                    reason = ComparisonUnavailableReason.UNSUPPORTED_TRADE,
                                    unavailableAt = event.trade.timestamp,
                                )
                            }
                            assignedTradeIndexes += event.index
                            effectiveTradeAccountingModes[event.index] = intervalAccountingMode
                            effectiveTradeTimestamps[event.index] =
                                calculateIntervalEventTimestamp(event.trade.timestamp, prev, curr)
                        }
                    }
                }
                val lateAssignment = if (lateCandidates.isNotEmpty()) {
                    findLateAssignment(
                        lateCandidates,
                        impliedBalances,
                        curr,
                        intervalAccountingMode,
                        useAuthoritativeLedgerBalances,
                        tradeLegsByRefId,
                        tradeLegsByTradeIdentity,
                        ::untrackedBySeries,
                        preRegularBalances,
                        anchorRelativeLedgerIndexes,
                        prev.timestamp..curr.timestamp,
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

            // A fully liquidated asset can disappear from the recorded series, and a baseline
            // holding outside the series' tracked universe is never recorded by it, so only
            // tracked keys with a materially non-zero balance must still be observable.
            impliedBalances.forEach { (symbol, balance) ->
                if (symbol !in curr.assets &&
                    balance.setScale(balanceScale(symbol), RoundingMode.HALF_UP).signum() != 0
                ) {
                    if (!untrackedBySeries(symbol)) {
                        return TrackedBalanceValidation.Failed(
                            reason = ComparisonUnavailableReason.UNSUPPORTED_TRADE,
                            unavailableAt = curr.timestamp,
                        )
                    }
                    toleratedUntrackedSymbols.add(symbol)
                }
            }
            if (!balancesMatchSnapshot(impliedBalances, curr, ::untrackedBySeries)) {
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
            if (legacyFailure == null) {
                state = legacyAttempt
                continue
            }

            // A few retained live snapshots were written from the rounded TradeRecord wallet
            // economics while their trade ledger legs describe a different fee denomination.
            // Try that complete reported-fill shape only after both ledger-based modes fail; the
            // interval still has to match every observed balance before it can pass.
            val reportedEconomicsAttempt = state.copyForAttempt()
            val reportedEconomicsFailure = reconcileInterval(
                i,
                reportedEconomicsAttempt,
                TradeAccountingMode.PERSISTED_TRADE_ECONOMICS,
            )
            if (reportedEconomicsFailure != null) return preciseFailure
            state = reportedEconomicsAttempt
        }

        if (toleratedUntrackedSymbols.isNotEmpty()) {
            log.info(
                "Comparison series-scope tolerance: {} baseline holding(s) are never recorded by the legacy series and are reconciled only at the anchor: {}",
                toleratedUntrackedSymbols.size,
                toleratedUntrackedSymbols.sorted().joinToString(", "),
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
        return TrackedBalanceValidation.Passed(ledgers = passedLedgers)
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

    private fun balanceScale(symbol: String): Int =
        if (symbol == Asset.USD) PrecisionConstants.SCALE_USD else PrecisionConstants.SCALE_CRYPTO

    private fun tradeTouchesAssets(trade: TradeRecord, trackedAssets: Set<String>): Boolean {
        val split = Asset.splitTradingPair(trade.pair)
        return if (split == null) {
            Asset.normalizeLedgerAsset(trade.symbol).uppercase() in trackedAssets
        } else {
            split.base in trackedAssets || split.quote in trackedAssets
        }
    }

    private fun balancesMatchSnapshot(
        expectedBalances: Map<String, BigDecimal>,
        snapshot: PortfolioSnapshot,
        isUntrackedBySeries: (String) -> Boolean,
    ): Boolean {
        for ((symbol, expectedBalance) in expectedBalances) {
            val scale = balanceScale(symbol)
            // Only crypto quantities can carry the one-unit replay offset; quote cash is cent-exact.
            val tolerance =
                if (symbol == Asset.USD) BigDecimal.ZERO else BigDecimal.ONE.movePointLeft(scale)
            val roundedExpected = expectedBalance.setScale(scale, RoundingMode.HALF_UP)
            val snapshotBalance = snapshot.assets[symbol]?.balance
            if (snapshotBalance == null) {
                // A fully liquidated position may be dropped from the recorded series, and a
                // baseline holding outside the series' tracked universe is never recorded by it.
                if (roundedExpected.signum() == 0) continue
                if (isUntrackedBySeries(symbol)) continue
                return false
            }
            val roundedActual = snapshotBalance.setScale(scale, RoundingMode.HALF_UP)
            // The recorded series is replayed backwards from live wallet balances, so it can
            // carry a constant one-unit offset at the asset scale that only surfaces when the
            // replayed history cancels a holding to zero.
            if (roundedExpected.subtract(roundedActual).abs().compareTo(tolerance) > 0) return false
        }
        for ((symbol, asset) in snapshot.assets) {
            if (symbol in expectedBalances) continue
            // A holding the replayed events never produced is unexplained: anchor-excluded
            // assets are tolerated only when the recorded trades or ledgers acquired them.
            val scale = balanceScale(symbol)
            val tolerance =
                if (symbol == Asset.USD) BigDecimal.ZERO else BigDecimal.ONE.movePointLeft(scale)
            val net = asset.balance.setScale(scale, RoundingMode.HALF_UP).abs()
            if (net.compareTo(tolerance) > 0) return false
        }
        return true
    }

    private fun balanceResidual(balances: Map<String, BigDecimal>, snapshot: PortfolioSnapshot): BigDecimal {
        var total = BigDecimal.ZERO
        val allSymbols = balances.keys + snapshot.assets.keys
        for (symbol in allSymbols) {
            val calculated = balances[symbol] ?: BigDecimal.ZERO
            val actual = snapshot.assets[symbol]?.balance ?: BigDecimal.ZERO
            total = total.add(calculated.subtract(actual).abs())
        }
        return total
    }

    /**
     * Exact raw-precision equality of two resulting balance maps. Recognises provable no-op
     * inclusions during late-assignment tie-breaking: when two matching subsets leave every
     * tracked balance identical, the extra events moved nothing the snapshot can observe.
     */
    private fun sameResultingBalances(first: Map<String, BigDecimal>, second: Map<String, BigDecimal>): Boolean {
        for (symbol in first.keys + second.keys) {
            val left = first[symbol] ?: BigDecimal.ZERO
            val right = second[symbol] ?: BigDecimal.ZERO
            if (left.compareTo(right) != 0) return false
        }
        return true
    }

    private fun canUseAuthoritativeLedgerBalances(
        ledgers: List<LedgerEvent>,
        trackedAssets: Set<String>,
        resolvedScopes: Map<String, AuthoritativeLedgerBalanceValidator.LedgerWalletScope>,
    ): Boolean {
        val trackedLedgers = ledgers.filter {
            Asset.normalizeLedgerAsset(it.asset).uppercase() in trackedAssets
        }
        if (trackedLedgers.isEmpty()) {
            return false
        }
        // A staking row's balance may belong to a non-Spot wallet. Only a row the validator
        // explicitly resolved to Spot may act as a Spot correction; unresolved or other scopes
        // keep ledger economics, which fails closed when the Spot snapshot does not reflect the
        // row. Internal scope markers have the same ambiguity.
        if (trackedLedgers.any {
                (
                    it.type.equals(KrakenApiConstants.LEDGER_TYPE_STAKING, ignoreCase = true) &&
                        resolvedScopes[it.ledgerId] != AuthoritativeLedgerBalanceValidator.LedgerWalletScope.SPOT
                    ) ||
                    LedgerFlowClassifier.isDocumentedInternalScopeMarker(it)
            }
        ) {
            return false
        }
        // A traded asset's own authoritative row still re-anchors within the per-row legacy
        // fee-delta tolerance applied in applyLedgerEvent; refusing the whole interval would
        // leave every other authoritative row on plain net deltas and recreate chain drift.
        return trackedLedgers.all(LedgerEvent::hasAuthoritativeBalance)
    }

    private sealed interface IntervalEvent {
        val timestamp: Instant
        val order: Int

        data class Ledger(val index: Int, override val timestamp: Instant, val event: LedgerEvent) : IntervalEvent {
            override val order: Int = 0
        }

        data class Trade(val index: Int, override val timestamp: Instant, val trade: TradeRecord) : IntervalEvent {
            override val order: Int = 1
        }
    }

    private fun buildIntervalEvents(
        trades: List<IndexedValue<TradeRecord>>,
        ledgers: List<IndexedValue<LedgerEvent>>,
    ): List<IntervalEvent> = buildList {
        ledgers.forEach { add(IntervalEvent.Ledger(it.index, it.value.time, it.value)) }
        trades.forEach { add(IntervalEvent.Trade(it.index, it.value.timestamp, it.value)) }
    }.sortedWith(compareBy({ it.timestamp }, { it.order }))

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
        tradeLegsByRefId: Map<String, List<LedgerEvent>>,
        tradeLegsByTradeIdentity: Map<String, List<LedgerEvent>>,
        isUntrackedBySeries: (String) -> Boolean,
    ): InitialAssignmentMatch? {
        if (initialCandidates.size + lateCandidates.size > MAX_BOUNDARY_EVENT_CANDIDATES) {
            // Observable boundary saturation: exhaustive search is bounded, so an over-wide
            // boundary degrades to fail-closed rather than silently nulling the assignment.
            log.warn(
                "Late-assignment boundary candidate cap exceeded; failing closed (candidates={}, cap={})",
                initialCandidates.size + lateCandidates.size,
                MAX_BOUNDARY_EVENT_CANDIDATES,
            )
            return null
        }

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

                val postTradeEvents =
                    initialCandidates
                        .filter { it is LateCandidate.Trade && it.index in postTrades }
                        .map { IndexedValue((it as LateCandidate.Trade).index, it.trade) } +
                        regularTrades
                val postLedgerEvents =
                    initialCandidates
                        .filter { it is LateCandidate.Ledger && it.index in postLedgers }
                        .map {
                            val candidate = it as LateCandidate.Ledger
                            IndexedValue(candidate.index, candidate.ledger)
                        } +
                        regularLedgers

                val ledgerDeltas = mutableMapOf<Int, BigDecimal>()
                for (event in buildIntervalEvents(postTradeEvents, postLedgerEvents)) {
                    when (event) {
                        is IntervalEvent.Trade -> {
                            if (!applyRealizedTrade(
                                    testBalances,
                                    event.trade,
                                    accountingMode,
                                    snapshot.assets.keys,
                                    tradeLegsByRefId,
                                    tradeLegsByTradeIdentity,
                                )
                            ) {
                                validEconomics = false
                                break
                            }
                        }

                        is IntervalEvent.Ledger -> {
                            ledgerDeltas[event.index] =
                                applyLedgerEvent(testBalances, event.event, useAuthoritativeLedgerBalances)
                        }
                    }
                }
                if (!validEconomics) return

                val late = if (lateCandidates.isNotEmpty()) {
                    findLateAssignment(
                        lateCandidates,
                        testBalances,
                        snapshot,
                        accountingMode,
                        useAuthoritativeLedgerBalances,
                        tradeLegsByRefId,
                        tradeLegsByTradeIdentity,
                        isUntrackedBySeries,
                    )
                } else {
                    null
                }
                val candidateMatch = if (late != null) {
                    InitialAssignmentMatch(
                        embeddedTradeIndexes = embeddedTrades.toList(),
                        embeddedLedgerIndexes = embeddedLedgers.toList(),
                        postBaselineTradeIndexes = postTrades.toList(),
                        postBaselineLedgerIndexes = postLedgers.toList(),
                        lateAssignment = late,
                        resultingBalances = late.balances,
                        ledgerDeltas = ledgerDeltas + late.ledgerDeltas,
                    )
                } else if (balancesMatchSnapshot(testBalances, snapshot, isUntrackedBySeries)) {
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
                    null
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
        tradeLegsByRefId: Map<String, List<LedgerEvent>>,
        tradeLegsByTradeIdentity: Map<String, List<LedgerEvent>>,
        isUntrackedBySeries: (String) -> Boolean,
        // Pre-regulars balances plus the indexes of late ledgers dated before every regular
        // event. Those ledgers evaluate anchor-relatively (see applyAnchorRelativeLedgerEvent);
        // default callers keep the legacy post-regulars evaluation.
        anchorBalances: Map<String, BigDecimal> = emptyMap(),
        anchorRelativeLedgerIndexes: Set<Int> = emptySet(),
        // Closed interval span [prev, curr] for causal tie-breaking below. Null preserves the
        // legacy behavior of treating every exact tie as ambiguous.
        intervalSpan: ClosedRange<Instant>? = null,
    ): LateAssignment? {
        if (candidates.isEmpty() || candidates.size > MAX_BOUNDARY_EVENT_CANDIDATES) return null

        val emptyMatches = balancesMatchSnapshot(startingBalances, snapshot, isUntrackedBySeries)
        val emptyResidual = if (emptyMatches) balanceResidual(startingBalances, snapshot) else null

        // If the unassigned state already matches the snapshot with exact zero residual at raw
        // precision, boundary candidates cannot explain any remaining delta.
        if (emptyMatches && emptyResidual != null && emptyResidual.compareTo(BigDecimal.ZERO) == 0) {
            return null
        }

        // Explore candidates in the same chronological order as final reconciliation so an
        // included subset evaluates authoritative checkpoints at their true point in time.
        val orderedCandidates = candidates.sortedWith(
            compareBy(
                { candidate ->
                    when (candidate) {
                        is LateCandidate.Trade -> candidate.trade.timestamp
                        is LateCandidate.Ledger -> candidate.ledger.time
                    }
                },
                { candidate -> if (candidate is LateCandidate.Ledger) 0 else 1 },
            ),
        )
        // Global-index to ledger lookup for anchor-relative prefix chaining below.
        val ledgersByIndex = orderedCandidates
            .filterIsInstance<LateCandidate.Ledger>()
            .associate { it.index to it.ledger }
        // Event time by global index for causal span ranking below.
        val candidateTimeByIndex: Map<Int, Instant> = candidates.associate {
            when (it) {
                is LateCandidate.Trade -> it.index to it.trade.timestamp
                is LateCandidate.Ledger -> it.index to it.ledger.time
            }
        }

        // 0 when every event of the subset lies within the interval span (so the subset can
        // have caused the observed state), 1 otherwise. Unknown spans never prefer.
        fun spanRank(tradeIndexes: List<Int>, ledgerIndexes: List<Int>): Int {
            val span = intervalSpan ?: return 1
            val fullyInSpan = (tradeIndexes + ledgerIndexes).all {
                candidateTimeByIndex[it]?.let { t -> t in span } ==
                    true
            }
            return if (fullyInSpan) 0 else 1
        }

        var bestMatch: LateAssignment? = null
        var bestResidual: BigDecimal? = null
        var multipleMatches = false
        val selectedTrades = mutableListOf<Int>()
        val selectedLedgers = mutableListOf<Int>()
        val selectedLedgerDeltas = mutableMapOf<Int, BigDecimal>()

        fun search(position: Int, balances: Map<String, BigDecimal>) {
            if (position == orderedCandidates.size) {
                if ((selectedTrades.isNotEmpty() || selectedLedgers.isNotEmpty()) &&
                    balancesMatchSnapshot(balances, snapshot, isUntrackedBySeries)
                ) {
                    val residual = balanceResidual(balances, snapshot)
                    // If the empty subset already matched within display tolerance, a non-empty
                    // candidate subset is only selected if its exact raw residual strictly improves
                    // upon the unadjusted state.
                    if (emptyResidual == null || residual.compareTo(emptyResidual) < 0) {
                        val currentBest = bestResidual
                        if (currentBest == null || residual.compareTo(currentBest) < 0) {
                            bestResidual = residual
                            bestMatch = LateAssignment(
                                tradeIndexes = selectedTrades.toList(),
                                ledgerIndexes = selectedLedgers.toList(),
                                balances = balances.toMap(),
                                ledgerDeltas = selectedLedgerDeltas.toMap(),
                            )
                            multipleMatches = false
                        } else if (residual.compareTo(currentBest) == 0) {
                            // An exact-residual tie between subsets with identical resulting
                            // balances means the extra events are provable no-ops on tracked
                            // state (e.g. a zero-effect dust fill riding along the real fill
                            // that explains the delta). Keep the minimal explanation instead
                            // of failing closed. Subsets that reach equal residual through
                            // genuinely different balances stay ambiguous and fail closed.
                            val incumbent = bestMatch
                            if (incumbent != null && sameResultingBalances(incumbent.balances, balances)) {
                                val challengerSize = selectedTrades.size + selectedLedgers.size
                                val incumbentSize = incumbent.tradeIndexes.size + incumbent.ledgerIndexes.size
                                when {
                                    challengerSize < incumbentSize -> {
                                        bestMatch = LateAssignment(
                                            tradeIndexes = selectedTrades.toList(),
                                            ledgerIndexes = selectedLedgers.toList(),
                                            balances = balances.toMap(),
                                            ledgerDeltas = selectedLedgerDeltas.toMap(),
                                        )
                                    }

                                    challengerSize == incumbentSize -> {
                                        // Same-size, same-balances, exact-residual ties are only
                                        // genuinely ambiguous when neither subset is causally
                                        // distinguished: a subset fully inside the interval span
                                        // could have caused the observed state, while one relying
                                        // on fuzzy-window neighbors from outside the span could
                                        // not (under the relative timestamp order). Prefer the
                                        // causal subset; same-span ties still fail closed.
                                        val incumbentRank = spanRank(incumbent.tradeIndexes, incumbent.ledgerIndexes)
                                        val challengerRank = spanRank(selectedTrades, selectedLedgers)
                                        when {
                                            challengerRank < incumbentRank -> {
                                                bestMatch = LateAssignment(
                                                    tradeIndexes = selectedTrades.toList(),
                                                    ledgerIndexes = selectedLedgers.toList(),
                                                    balances = balances.toMap(),
                                                    ledgerDeltas = selectedLedgerDeltas.toMap(),
                                                )
                                                multipleMatches = false
                                            }

                                            challengerRank > incumbentRank -> Unit

                                            else -> multipleMatches = true
                                        }
                                    }
                                    // Larger no-op superset: ignore, the minimal subset stands.
                                }
                            } else {
                                multipleMatches = true
                            }
                        }
                    }
                }
                return
            }

            search(position + 1, balances)

            when (val candidate = orderedCandidates[position]) {
                is LateCandidate.Trade -> {
                    val nextBalances = balances.toMutableMap()
                    if (
                        applyRealizedTrade(
                            nextBalances,
                            candidate.trade,
                            accountingMode,
                            snapshot.assets.keys,
                            tradeLegsByRefId,
                            tradeLegsByTradeIdentity,
                        )
                    ) {
                        selectedTrades += candidate.index
                        search(position + 1, nextBalances)
                        selectedTrades.removeAt(selectedTrades.lastIndex)
                    }
                }

                is LateCandidate.Ledger -> {
                    val symbol = Asset.normalizeLedgerAsset(candidate.ledger.asset).uppercase()
                    val isAnchorRelative = candidate.index in anchorRelativeLedgerIndexes
                    val contradictoryAuthoritative = useAuthoritativeLedgerBalances &&
                        candidate.ledger.hasAuthoritativeBalance &&
                        !isSpotResolvedStakingCorrection(candidate.ledger) &&
                        symbol in balances &&
                        (!isAnchorRelative || symbol in anchorBalances) &&
                        run {
                            val netDelta = candidate.ledger.netBalanceDelta()
                            val authoritativeDelta = if (isAnchorRelative) {
                                var prefixDelta = BigDecimal.ZERO
                                for (selectedIndex in selectedLedgers) {
                                    val prior = ledgersByIndex[selectedIndex] ?: continue
                                    if (Asset.normalizeLedgerAsset(prior.asset).uppercase() == symbol) {
                                        prefixDelta = prefixDelta.add(
                                            selectedLedgerDeltas[selectedIndex] ?: BigDecimal.ZERO,
                                        )
                                    }
                                }
                                candidate.ledger.balance.subtract(anchorBalances.getValue(symbol))
                                    .subtract(prefixDelta)
                            } else {
                                candidate.ledger.balance.subtract(balances.getValue(symbol))
                            }
                            authoritativeDelta.subtract(netDelta).abs() > legacyLedgerFeeDeltaTolerance
                        }
                    if (!contradictoryAuthoritative) {
                        val nextBalances = balances.toMutableMap()
                        val appliedDelta = if (isAnchorRelative) {
                            applyAnchorRelativeLedgerEvent(
                                nextBalances,
                                candidate.ledger,
                                useAuthoritativeLedgerBalances,
                                anchorBalances,
                                ledgersByIndex,
                                selectedLedgers,
                                selectedLedgerDeltas,
                            )
                        } else {
                            applyLedgerEvent(
                                nextBalances,
                                candidate.ledger,
                                useAuthoritativeLedgerBalances,
                            )
                        }
                        selectedLedgers += candidate.index
                        selectedLedgerDeltas[candidate.index] = appliedDelta
                        search(position + 1, nextBalances)
                        selectedLedgerDeltas.remove(candidate.index)
                        selectedLedgers.removeAt(selectedLedgers.lastIndex)
                    }
                }
            }
        }

        search(position = 0, balances = startingBalances)
        return if (multipleMatches) null else bestMatch
    }

    /**
     * Mirror of the scope gate in [canUseAuthoritativeLedgerBalances]: a staking row may act
     * as a Spot correction only when validator-resolved to Spot, which is exactly when the
     * interval flag (and hence a non-null authoritativeDelta) is granted for it.
     */
    private fun isSpotResolvedStakingCorrection(ledger: LedgerEvent): Boolean =
        ledger.type.equals(KrakenApiConstants.LEDGER_TYPE_STAKING, ignoreCase = true)

    /**
     * Applies a late boundary ledger dated before every regular event against the pre-regulars
     * [anchorBalances] instead of the post-regulars running state, so a staking-row snap lands
     * on its true point-in-time delta rather than overwriting newer regular effects with a
     * stale post balance. Same-symbol subset ledgers applied earlier in this path are chained
     * via [selectedLedgerDeltas]; net-economics legs are unaffected (addition commutes).
     * Earlier same-symbol subset *trades* are not chained (trade deltas are not recorded), so
     * exotic same-instant trade-plus-ledger layouts stay fail-closed here. Falls back to
     * [applyLedgerEvent] when the symbol is absent from either balance map.
     */
    private fun applyAnchorRelativeLedgerEvent(
        balances: MutableMap<String, BigDecimal>,
        ledger: LedgerEvent,
        useAuthoritativeBalance: Boolean,
        anchorBalances: Map<String, BigDecimal>,
        ledgersByIndex: Map<Int, LedgerEvent>,
        selectedLedgerIndexes: List<Int>,
        selectedLedgerDeltas: Map<Int, BigDecimal>,
    ): BigDecimal {
        val symbol = Asset.normalizeLedgerAsset(ledger.asset).uppercase()
        if (symbol !in balances || symbol !in anchorBalances) {
            return applyLedgerEvent(balances, ledger, useAuthoritativeBalance)
        }
        var prefixDelta = BigDecimal.ZERO
        for (selectedIndex in selectedLedgerIndexes) {
            val prior = ledgersByIndex[selectedIndex] ?: continue
            if (Asset.normalizeLedgerAsset(prior.asset).uppercase() == symbol) {
                prefixDelta = prefixDelta.add(selectedLedgerDeltas[selectedIndex] ?: BigDecimal.ZERO)
            }
        }
        val netDelta = ledger.netBalanceDelta()
        val authoritativeDelta = if (useAuthoritativeBalance && ledger.hasAuthoritativeBalance) {
            ledger.balance.subtract(anchorBalances.getValue(symbol)).subtract(prefixDelta)
        } else {
            null
        }
        val delta = if (
            authoritativeDelta != null &&
            (
                authoritativeDelta.subtract(netDelta).abs() <= legacyLedgerFeeDeltaTolerance ||
                    isSpotResolvedStakingCorrection(ledger)
                )
        ) {
            authoritativeDelta
        } else {
            netDelta
        }
        balances[symbol] = balances.getValue(symbol).add(delta)
        return delta
    }

    private data class BuiltEvents(
        val events: List<BenchmarkEvent>,
        /** Set when an owner flow could not be priced from recorded history. */
        val unpriceableAt: Instant?,
        /** Set when mixed-asset funding plumbing lacks one authoritative identity. */
        val ambiguousAt: Instant? = null,
    )

    private sealed interface OwnerFlowBuild {
        data class Event(val event: BenchmarkEvent) : OwnerFlowBuild

        /** Benign skip (e.g. non-positive cash value); not an error. */
        data object Skip : OwnerFlowBuild

        /** Contribution-time prices unavailable; caller fails closed. */
        data object Unpriceable : OwnerFlowBuild
    }

    /**
     * Recorded-anchor value weights (normalized symbol to fraction, summing exactly to one at
     * [WEIGHT_DIVISION_SCALE]). Target percentages describe the current plan, not a historical
     * allocation record, so they must not rewrite the benchmark's anchor thesis.
     */
    private fun baselineValueWeights(baseline: PortfolioSnapshot): Map<String, BigDecimal> {
        // The benchmark owns the holdings that were actually present at the anchor. A row with
        // zero balance but a stale positive value/target is current-plan metadata, not synthetic
        // capital. Aggregate normalized aliases before calculating weights so the residual cannot
        // be assigned to a duplicate symbol.
        val valuesBySymbol = baseline.assets.entries
            .filter { (_, asset) -> asset.balance.signum() > 0 && asset.valueUSD.signum() > 0 }
            .groupingBy { (symbol, _) -> Asset.normalizeLedgerAsset(symbol).uppercase() }
            .fold(BigDecimal.ZERO) { total, (_, asset) -> total.add(asset.valueUSD) }
        val total = baseline.totalValueUSD
        val raw = valuesBySymbol.mapValues { (_, value) ->
            value.divide(total, WEIGHT_DIVISION_SCALE, RoundingMode.HALF_UP)
        }.filterValues { it.signum() > 0 }
        val sum = raw.values.fold(BigDecimal.ZERO) { acc, weight -> acc.add(weight) }
        if (sum.signum() <= 0) return emptyMap()
        val normalized = raw.mapValues { (_, weight) ->
            weight.divide(sum, WEIGHT_DIVISION_SCALE, RoundingMode.HALF_UP)
        }
        val residual = BigDecimal.ONE.subtract(
            normalized.values.fold(BigDecimal.ZERO) { acc, weight ->
                acc.add(weight)
            },
        )
        val residualSymbol = normalized.maxByOrNull { (_, weight) -> weight }?.key ?: return emptyMap()
        return normalized.toMutableMap().apply {
            this[residualSymbol] = getValue(residualSymbol).add(residual)
        }
    }

    private suspend fun buildBenchmarkEvents(
        ledgers: List<ReconciledLedger>,
        baseline: PortfolioSnapshot,
        inceptionWeights: Map<String, BigDecimal>,
        priceProvider: HistoricalPriceProvider?,
        classifications: Map<String, FlowCategory>,
        cardNormalizations: List<NormalizedFundingTransaction>,
        rewards: List<LedgerEvent>,
    ): BuiltEvents {
        val postBaseline = ledgers.filter { reconciledLedger ->
            !reconciledLedger.embeddedInBaseline &&
                reconciledLedger.timestamp > baseline.timestamp &&
                (
                    baseline.balancesObservedAt == null ||
                        reconciledLedger.ledger.time > baseline.balancesObservedAt
                    )
        }
        val postBaselineById = postBaseline.associateBy { it.ledger.ledgerId }
        val events = mutableListOf<BenchmarkEvent>()
        val consumedLedgerIds = mutableSetOf<String>()

        // A complete top-level conversion is one linked economic event. Validate its shape
        // and consume ledger IDs so they are not treated as external flows, but do not
        // emit a benchmark event into pure Buy & Hold. A group straddling the baseline is
        // not safely replayable from one side and therefore remains unavailable.
        val conversionGroups = ledgers
            .filter { isConversionLedger(it.ledger) }
            .groupBy { it.ledger.refid?.trim().orEmpty() }
        for ((refid, group) in conversionGroups) {
            val postGroup = group.filter { postBaselineById.containsKey(it.ledger.ledgerId) }
            if (postGroup.isEmpty()) continue
            val conversionAt = postGroup.minOf { it.timestamp }
            if (refid.isBlank() || postGroup.size != group.size ||
                group.size != 2 || group.any { classifications[it.ledger.ledgerId] != FlowCategory.INTERNAL_MOVE }
            ) {
                // A complete conversion whose counterpart references an asset outside the
                // tracked universe never enters reconciliation, so the reconciled subset can
                // look one-legged. Rescue only that split shape; everything else stays ambiguous.
                if (!isUniverseSplitConversion(refid, group, rewards, baseline, classifications)) {
                    return BuiltEvents(
                        events = emptyList(),
                        unpriceableAt = null,
                        ambiguousAt = conversionAt,
                    )
                }
            }
            consumedLedgerIds += group.map { it.ledger.ledgerId }
        }

        for (norm in cardNormalizations) {
            val sourceIds = when (norm) {
                is NormalizedFundingTransaction.OwnerContribution -> norm.sourceLedgerIds
                is NormalizedFundingTransaction.OwnerWithdrawal -> norm.sourceLedgerIds
                else -> emptyList()
            }
            if (sourceIds.isNotEmpty()) {
                val anyPostBaseline = sourceIds.any { postBaselineById.containsKey(it) }
                val allPostBaseline = sourceIds.all { postBaselineById.containsKey(it) }
                if (anyPostBaseline && !allPostBaseline) {
                    val straddleTime = when (norm) {
                        is NormalizedFundingTransaction.OwnerContribution -> norm.eventTime
                        is NormalizedFundingTransaction.OwnerWithdrawal -> norm.eventTime
                        else -> baseline.timestamp
                    }
                    return BuiltEvents(
                        events = emptyList(),
                        unpriceableAt = null,
                        ambiguousAt = straddleTime,
                    )
                }
                if (!allPostBaseline) {
                    continue
                }
            }

            when (norm) {
                is NormalizedFundingTransaction.Ambiguous -> {
                    if (norm.unavailableAt > baseline.timestamp) {
                        return BuiltEvents(
                            events = emptyList(),
                            unpriceableAt = null,
                            ambiguousAt = norm.unavailableAt,
                        )
                    }
                }

                is NormalizedFundingTransaction.UnpriceableFee -> {
                    if (norm.unavailableAt > baseline.timestamp) {
                        return BuiltEvents(
                            events = emptyList(),
                            unpriceableAt = norm.unavailableAt,
                            ambiguousAt = null,
                        )
                    }
                }

                is NormalizedFundingTransaction.OwnerContribution -> {
                    consumedLedgerIds.addAll(norm.sourceLedgerIds)
                    val representative = postBaselineById.getValue(norm.representativeLedgerId)
                    when (
                        val built = buildOwnerCapitalEvent(
                            ledger = representative.ledger,
                            ledgerType = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                            timestamp = representative.timestamp,
                            netBalanceDelta = BigDecimal.ZERO,
                            cashUsdOverride = norm.netOwnerCapitalUsd,
                            inceptionWeights = inceptionWeights,
                            priceProvider = priceProvider,
                            sourceLedgerIds = norm.sourceLedgerIds,
                            sourceEventTimestamps = norm.actualPortfolioDeltas.map { it.timestamp }.toSet()
                                .ifEmpty { setOf(representative.ledger.time) },
                        )
                    ) {
                        is OwnerFlowBuild.Event -> events += built.event
                        OwnerFlowBuild.Skip -> Unit
                        OwnerFlowBuild.Unpriceable -> return BuiltEvents(emptyList(), representative.timestamp)
                    }
                }

                is NormalizedFundingTransaction.OwnerWithdrawal -> {
                    consumedLedgerIds.addAll(norm.sourceLedgerIds)
                    val representative = postBaselineById.getValue(norm.representativeLedgerId)
                    when (
                        val built = buildOwnerCapitalEvent(
                            ledger = representative.ledger,
                            ledgerType = KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL,
                            timestamp = representative.timestamp,
                            netBalanceDelta = BigDecimal.ZERO,
                            cashUsdOverride = norm.netOwnerCapitalUsd,
                            inceptionWeights = inceptionWeights,
                            priceProvider = priceProvider,
                            sourceLedgerIds = norm.sourceLedgerIds,
                            sourceEventTimestamps = norm.actualPortfolioDeltas.map { it.timestamp }.toSet()
                                .ifEmpty { setOf(representative.ledger.time) },
                        )
                    ) {
                        is OwnerFlowBuild.Event -> events += built.event
                        OwnerFlowBuild.Skip -> Unit
                        OwnerFlowBuild.Unpriceable -> return BuiltEvents(emptyList(), representative.timestamp)
                    }
                }

                else -> Unit
            }
        }

        val unconsumedPostBaseline = postBaseline.filter { it.ledger.ledgerId !in consumedLedgerIds }

        // A refid-linked spend/receive group is one balance transformation, not two independent
        // external credits/debits. Preserve atomicity for complete groups and fail closed for
        // linked passthrough groups whose shape is incomplete.
        val passthroughGroups = unconsumedPostBaseline
            .filter { reconciled ->
                !reconciled.ledger.refid.isNullOrBlank() &&
                    CardFundingNormalizer.isPassthroughLeg(reconciled.ledger)
            }
            .groupBy { it.ledger.refid!!.trim() }
        for ((_, group) in passthroughGroups) {
            // A lone row may be a genuine standalone external movement; without a sibling leg
            // there is no positive evidence that it is a conversion whose counterpart is missing.
            if (group.size == 1) continue
            val groupAt = group.minOf { it.timestamp }
            if (!CardFundingNormalizer.isCompletePassthroughGroup(group.map { it.ledger })) {
                // Legs of one exchange-labeled atomic transformation (uniform explicit subtype)
                // are plumbing of that known event even when snapshots captured only a subset;
                // consume them like a complete group instead of reporting an ambiguous type.
                if (CardFundingNormalizer.isAtomicTransformationGroup(group.map { it.ledger })) {
                    consumedLedgerIds += group.map { it.ledger.ledgerId }
                    continue
                }
                return BuiltEvents(
                    events = emptyList(),
                    unpriceableAt = null,
                    ambiguousAt = groupAt,
                )
            }
            consumedLedgerIds += group.map { it.ledger.ledgerId }
        }

        for ((timestamp, group) in unconsumedPostBaseline.groupBy { it.ledger.time }) {
            val hasOwnerFunding = group.any {
                classifications[it.ledger.ledgerId] == FlowCategory.OWNER_CAPITAL
            }
            val hasNonUsdPassthrough = group.any {
                CardFundingNormalizer.isPassthroughLeg(it.ledger) && !CardFundingNormalizer.isUsd(it.ledger.asset)
            }
            if (hasOwnerFunding && hasNonUsdPassthrough) {
                log.warn(
                    "Cannot correlate mixed-asset funding plumbing at {}; funding and passthrough refids do not agree",
                    timestamp,
                )
                return BuiltEvents(
                    events = emptyList(),
                    unpriceableAt = null,
                    ambiguousAt = timestamp,
                )
            }
        }

        for (reconciledLedger in postBaseline) {
            if (reconciledLedger.ledger.ledgerId in consumedLedgerIds) {
                continue
            }
            val ledger = reconciledLedger.ledger
            if (isConversionLedger(ledger)) {
                // Complete groups were consumed above. Any remaining row is a
                // defensive fail-closed path for an incomplete reconciliation result.
                return BuiltEvents(
                    events = emptyList(),
                    unpriceableAt = null,
                    ambiguousAt = reconciledLedger.timestamp,
                )
            }
            val category = classifications.getValue(ledger.ledgerId)
            if (category == FlowCategory.INTERNAL_MOVE ||
                category == FlowCategory.TRADE_IGNORED
            ) {
                continue
            }
            if (category == FlowCategory.AMBIGUOUS) {
                return BuiltEvents(
                    events = emptyList(),
                    unpriceableAt = null,
                    ambiguousAt = reconciledLedger.timestamp,
                )
            }
            if (category == FlowCategory.OWNER_CAPITAL) {
                when (
                    val built = buildOwnerCapitalEvent(
                        ledger = ledger,
                        ledgerType = ledger.type,
                        timestamp = reconciledLedger.timestamp,
                        netBalanceDelta = reconciledLedger.netBalanceDelta,
                        inceptionWeights = inceptionWeights,
                        priceProvider = priceProvider,
                        sourceLedgerIds = listOf(ledger.ledgerId),
                    )
                ) {
                    is OwnerFlowBuild.Event -> events += built.event
                    OwnerFlowBuild.Skip -> Unit
                    OwnerFlowBuild.Unpriceable -> return BuiltEvents(events, reconciledLedger.timestamp)
                }
            } else if (CardFundingNormalizer.isPassthroughLeg(ledger)) {
                // An unlinked spend/receive row is still account plumbing, most commonly a Buy
                // Crypto transaction whose counterpart identity was not retained. It is not
                // evidence of an independent passive credit or charge, so exclude it rather than
                // replaying an actual purchase into the benchmark. Linked groups were consumed
                // above; incomplete linked groups already fail closed.
                continue
            } else {
                events += BenchmarkEvent.ExternalBalance(
                    timestamp = reconciledLedger.timestamp,
                    asset = ledger.asset,
                    netAmount = reconciledLedger.netBalanceDelta,
                    event = ledger,
                    sourceLedgerIds = listOf(ledger.ledgerId),
                )
            }
        }
        events.sort()
        return BuiltEvents(events, null)
    }

    private fun isConversionLedger(ledger: LedgerEvent): Boolean =
        ledger.type.equals(KrakenApiConstants.LEDGER_TYPE_CONVERSION, ignoreCase = true)

    /**
     * Recognizes a complete refid-linked conversion split by the tracked-universe boundary.
     * The reconciled subset holds an assigned tracked-side leg while the counterpart, which
     * references an asset absent from the tracked universe, could never be assigned during
     * reconciliation. Rescue requires the full evidence group to be exactly the structurally
     * complete pair: same non-blank refid, same exchange instant, one debit and one credit
     * across two distinct assets with authoritative balances and fees, every leg classified
     * internal, every leg post-baseline, and the counterpart outside the tracked universe.
     * A straddling, underdetermined, or tracked-but-unassigned counterpart stays ambiguous.
     */
    private fun isUniverseSplitConversion(
        refid: String,
        group: List<ReconciledLedger>,
        rewards: List<LedgerEvent>,
        baseline: PortfolioSnapshot,
        classifications: Map<String, FlowCategory>,
    ): Boolean {
        if (refid.isBlank()) return false
        if (group.any { classifications[it.ledger.ledgerId] != FlowCategory.INTERNAL_MOVE }) return false
        val fullGroup = rewards.filter { isConversionLedger(it) && it.refid?.trim() == refid }
        if (fullGroup.size != 2) return false
        val reconciledIds = group.mapTo(mutableSetOf()) { it.ledger.ledgerId }
        val counterparts = fullGroup.filter { it.ledgerId !in reconciledIds }
        if (counterparts.size != 1 || group.size != 1) return false
        val leg = group.single().ledger
        val counterpart = counterparts.single()
        if (counterpart.time != leg.time) return false
        if (counterpart.time <= baseline.timestamp) return false
        if (baseline.balancesObservedAt != null && counterpart.time <= baseline.balancesObservedAt) return false
        val trackedAssets = baseline.assets.keys
            .map { Asset.normalizeLedgerAsset(it).uppercase() }
            .toSet()
        if (Asset.normalizeLedgerAsset(counterpart.asset).uppercase() in trackedAssets) return false
        if (classifications[counterpart.ledgerId] != FlowCategory.INTERNAL_MOVE) return false
        return LedgerFlowClassifier.isCompleteConversionGroup(listOf(leg, counterpart))
    }

    /**
     * Event timestamps alone do not establish whether a balance movement was
     * applied before or after an owner flow. Additive movements in established
     * baseline assets are safe, but owner withdrawals and same-instant owner
     * contributions are not commutative with balance reductions.
     */
    private fun findUnorderedBenchmarkEventTimestamp(
        events: List<BenchmarkEvent>,
        baselineAssetSymbols: Set<String>,
    ): Instant? {
        val ownerContributions = events.filterIsInstance<BenchmarkEvent.OwnerContribution>()
        val ownerWithdrawals = events.filterIsInstance<BenchmarkEvent.OwnerWithdrawal>()
        val externalBalances = events.filterIsInstance<BenchmarkEvent.ExternalBalance>()
        val balanceMovements: List<BenchmarkEvent> = externalBalances
        val movementAssetsByEvent = buildMap<BenchmarkEvent, Set<String>> {
            externalBalances.forEach { event ->
                put(event, setOf(Asset.normalizeLedgerAsset(event.asset).uppercase()))
            }
        }

        fun eventDistanceMillis(first: BenchmarkEvent, second: BenchmarkEvent): Long =
            kotlin.math.abs(first.timestamp.toEpochMilli() - second.timestamp.toEpochMilli())

        data class SourceInteraction(
            val timestamp: Instant,
            val sourceEventTimestamps: Set<Instant>,
            val assets: Set<String>,
        )

        val ownerContributionInteractions = ownerContributions.map { event ->
            SourceInteraction(
                timestamp = event.timestamp,
                sourceEventTimestamps = event.sourceEventTimestamps,
                assets = event.allocations.keys,
            )
        }
        val ownerWithdrawalInteractions = ownerWithdrawals.map { event ->
            SourceInteraction(
                timestamp = event.timestamp,
                sourceEventTimestamps = event.sourceEventTimestamps,
                assets = baselineAssetSymbols,
            )
        }

        fun earliestNearPairTimestamp(first: List<BenchmarkEvent>, second: List<BenchmarkEvent>): Instant? =
            first.asSequence()
                .flatMap { left ->
                    second.asSequence()
                        .filter { right ->
                            left !== right &&
                                eventDistanceMillis(left, right) < MAX_EVENT_OBSERVATION_CLOCK_SKEW_MILLIS
                        }
                        .map { right ->
                            minOf(left.timestamp, right.timestamp)
                        }
                }
                .minOrNull()

        fun earliestSourceOverlapPairTimestamp(
            first: List<SourceInteraction>,
            second: List<SourceInteraction>,
        ): Instant? = first.asSequence()
            .flatMap { left ->
                second.asSequence()
                    .filter { right ->
                        left !== right &&
                            left.sourceEventTimestamps.any { it in right.sourceEventTimestamps } &&
                            left.assets.any {
                                it in right.assets
                            }
                    }
                    .map { right -> minOf(left.timestamp, right.timestamp) }
            }
            .minOrNull()

        val unorderedTimes = mutableListOf<Instant>()
        val withdrawalMovementCandidates = balanceMovements.filter { movement ->
            movementAssetsByEvent.getValue(movement).any { it in baselineAssetSymbols }
        }
        earliestNearPairTimestamp(
            ownerWithdrawals,
            withdrawalMovementCandidates,
        )?.let(unorderedTimes::add)
        earliestNearPairTimestamp(ownerContributions, ownerWithdrawals)?.let(unorderedTimes::add)

        earliestSourceOverlapPairTimestamp(ownerWithdrawalInteractions, ownerContributionInteractions)
            ?.let(unorderedTimes::add)

        return unorderedTimes.minOrNull()
    }

    /**
     * Maps a genuine owner-capital ledger row to a typed benchmark event.
     * Contributions are invested by the fixed recorded-anchor value weights at
     * contribution-time prices; withdrawals become proportional reductions.
     * Returns [OwnerFlowBuild.Unpriceable] when recorded history cannot price
     * the event — callers fail closed rather than using a live ticker for an
     * old contribution.
     */
    private suspend fun buildOwnerCapitalEvent(
        ledger: LedgerEvent,
        ledgerType: String,
        timestamp: Instant,
        netBalanceDelta: BigDecimal,
        cashUsdOverride: BigDecimal? = null,
        inceptionWeights: Map<String, BigDecimal>,
        priceProvider: HistoricalPriceProvider?,
        sourceLedgerIds: List<String>,
        sourceEventTimestamps: Set<Instant> = setOf(ledger.time),
    ): OwnerFlowBuild {
        val symbol = Asset.normalizeLedgerAsset(ledger.asset).uppercase()
        val cashUsd = cashUsdOverride ?: run {
            val unitPrice = if (symbol == Asset.USD) {
                BigDecimal.ONE
            } else {
                priceProvider?.priceAt(symbol, ledger.time)
            }
            if (unitPrice == null || unitPrice.signum() <= 0) return OwnerFlowBuild.Unpriceable
            netBalanceDelta.multiply(unitPrice)
        }
        return when (ledgerType.lowercase()) {
            KrakenApiConstants.LEDGER_TYPE_DEPOSIT -> {
                if (cashUsd.signum() <= 0 || inceptionWeights.isEmpty()) {
                    if (cashUsd.signum() > 0) return OwnerFlowBuild.Unpriceable
                    return OwnerFlowBuild.Skip
                }
                val allocations = mutableMapOf<String, BigDecimal>()
                for ((weightSymbol, weight) in inceptionWeights) {
                    val price = if (weightSymbol == Asset.USD) {
                        BigDecimal.ONE
                    } else {
                        priceProvider?.priceAt(weightSymbol, ledger.time)
                    }
                    if (price == null || price.signum() <= 0) return OwnerFlowBuild.Unpriceable
                    allocations[weightSymbol] =
                        cashUsd.multiply(weight).divide(price, ALLOCATION_UNIT_SCALE, RoundingMode.HALF_UP)
                }
                OwnerFlowBuild.Event(
                    BenchmarkEvent.OwnerContribution(
                        timestamp = timestamp,
                        contributionUsd = cashUsd,
                        allocations = allocations,
                        event = ledger,
                        sourceLedgerIds = sourceLedgerIds,
                        sourceEventTimestamps = sourceEventTimestamps,
                    ),
                )
            }

            KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL -> {
                val withdrawalUsd = cashUsd.negate()
                if (withdrawalUsd.signum() <= 0) return OwnerFlowBuild.Skip
                OwnerFlowBuild.Event(
                    BenchmarkEvent.OwnerWithdrawal(
                        timestamp = timestamp,
                        withdrawalUsd = withdrawalUsd,
                        event = ledger,
                        sourceLedgerIds = sourceLedgerIds,
                        sourceEventTimestamps = sourceEventTimestamps,
                    ),
                )
            }

            else -> {
                // Unreachable: only deposit/withdrawal classify as
                // OWNER_CAPITAL. Replay in-kind rather than inventing
                // contribution semantics.
                OwnerFlowBuild.Event(
                    BenchmarkEvent.ExternalBalance(
                        timestamp = timestamp,
                        asset = ledger.asset,
                        netAmount = netBalanceDelta,
                        event = ledger,
                        sourceLedgerIds = sourceLedgerIds,
                    ),
                )
            }
        }
    }

    /**
     * Replays one benchmark event into synthetic holdings. Returns a
     * (reason, event timestamp) pair when replay cannot proceed honestly
     * (caller fails closed), null on success.
     */
    private suspend fun replayBenchmarkEvent(
        balances: MutableMap<String, BigDecimal>,
        event: BenchmarkEvent,
        priceProvider: HistoricalPriceProvider?,
    ): Pair<ComparisonUnavailableReason, Instant>? {
        when (event) {
            is BenchmarkEvent.ExternalBalance -> {
                val symbol = Asset.normalizeLedgerAsset(event.asset).uppercase()
                if (event.netAmount.signum() > 0) {
                    if (shouldMirrorPositiveExternalMovement(event.event, symbol, balances)) {
                        applyAttributedMovement(balances, mapOf(symbol to event.netAmount))
                    }
                } else if (event.netAmount.signum() < 0) {
                    applyAttributedMovement(balances, mapOf(symbol to event.netAmount))
                }
            }

            is BenchmarkEvent.OwnerContribution -> {
                for ((symbol, units) in event.allocations) {
                    balances[symbol] = (balances[symbol] ?: BigDecimal.ZERO).add(units)
                }
            }

            is BenchmarkEvent.OwnerWithdrawal -> {
                var syntheticTotal = BigDecimal.ZERO
                for ((symbol, balance) in balances) {
                    val price = if (symbol == Asset.USD) {
                        BigDecimal.ONE
                    } else {
                        priceProvider?.priceAt(symbol, event.timestamp)
                            ?: return ComparisonUnavailableReason.MISSING_PRICE to event.timestamp
                    }
                    syntheticTotal = syntheticTotal.add(balance.multiply(price))
                }
                if (syntheticTotal.signum() <= 0) {
                    return ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE to event.timestamp
                }
                val overdrawn = event.withdrawalUsd.subtract(syntheticTotal)
                if (overdrawn.signum() > 0 && overdrawn.compareTo(OVERDRAWN_DUST_TOLERANCE_USD) > 0) {
                    // The synthetic thesis cannot cover this withdrawal: the
                    // history diverged somewhere unobserved. Flooring to zero
                    // would mask real underperformance as a fresh start.
                    log.warn(
                        "Owner withdrawal of {} exceeds synthetic holdings {}; failing closed",
                        event.withdrawalUsd,
                        syntheticTotal,
                    )
                    return ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE to event.timestamp
                }
                if (overdrawn.signum() > 0) {
                    // Within the $1 dust tolerance: sub-cent rounding across
                    // decimal conversions makes exact zero unreachable in
                    // practice, so absorb visibly instead of failing closed.
                    log.info(
                        "Owner withdrawal of {} exceeds synthetic holdings {} by {} (within dust tolerance)",
                        event.withdrawalUsd,
                        syntheticTotal,
                        overdrawn,
                    )
                }
                val factor = syntheticTotal.subtract(event.withdrawalUsd)
                    .divide(syntheticTotal, WITHDRAWAL_FACTOR_SCALE, RoundingMode.HALF_UP)
                    .coerceAtLeast(BigDecimal.ZERO)
                for (symbol in balances.keys.toList()) {
                    balances[symbol] = balances.getValue(symbol).multiply(factor)
                }
            }
        }
        return null
    }

    /**
     * Positive external credits are synthetic only when their economics belong to the anchor
     * thesis. Cash dividends have no underlying equity position in this crypto/cash benchmark, so
     * a USD dividend is excluded. Holding-dependent crypto rewards are mirrored only when the
     * anchor owns the QUALIFYING SOURCE EXPOSURE that generated the reward, not necessarily the
     * credited reward asset itself: same-asset rewards qualify through the credited asset, and a
     * documented cross-asset rule (Kraken BTC staking pays BABY) qualifies through BTC exposure,
     * allowing BABY to become a legitimate new synthetic holding. Explicitly classified
     * account-level credits are independent of anchor holdings and may introduce their credited
     * asset without inventing a position from an ambiguous reward row.
     */
    private fun shouldMirrorPositiveExternalMovement(
        event: LedgerEvent,
        symbol: String,
        balances: Map<String, BigDecimal>,
    ): Boolean {
        if (isAccountLevelIndependentCredit(event)) return true

        val isReward = LedgerEvent.isRewardEvent(event) || isHoldingDependentReward(event)
        if (!isReward) {
            // Only an explicitly classified account-level credit may introduce an asset the
            // anchor never held; every other non-reward credit stays actual-only for unheld assets.
            if ((balances[symbol]?.signum() ?: 0) <= 0) return false
            return true
        }

        val type = event.type.trim().lowercase()
        val subtype = event.subtype?.trim()?.lowercase()
        // Cash dividends and explicitly equity-labelled payouts are never attributable to this
        // basket: the underlying equity position is not part of the passive thesis. A generic
        // dividend in a held crypto asset stays holding-dependent and remains mirrored.
        if (subtype == "cashdividend" || subtype == "equityfpsl") return false
        if (symbol == Asset.USD && type == KrakenApiConstants.LEDGER_TYPE_DIVIDEND) return false

        // Entitlement follows the qualifying source exposure under a supported semantic rule
        // when one exists; otherwise the reward asset itself (existing held-reward-asset rule).
        val qualifyingAsset = RewardEntitlements.qualifyingSourceAsset(event) ?: symbol
        return (balances[qualifyingAsset]?.signum() ?: 0) > 0
    }

    internal fun isHoldingDependentReward(event: LedgerEvent): Boolean {
        val type = event.type.trim().lowercase()
        val subtype = event.subtype?.trim()?.lowercase()
        if (type == KrakenApiConstants.LEDGER_TYPE_DIVIDEND) return true
        if (type == KrakenApiConstants.LEDGER_TYPE_STAKING) return true
        if (type == KrakenApiConstants.LEDGER_TYPE_EARN) return true
        if (subtype == "cashdividend" || subtype == "equityfpsl") return true
        if (type == KrakenApiConstants.LEDGER_TYPE_REWARD && subtype != "welcomebonus") return true
        return false
    }

    internal fun isAccountLevelIndependentCredit(event: LedgerEvent): Boolean {
        val type = event.type.trim().lowercase()
        val subtype = event.subtype?.trim()?.lowercase()
        if (type == KrakenApiConstants.LEDGER_TYPE_REWARD && subtype == "welcomebonus") return true
        if (type == KrakenApiConstants.LEDGER_TYPE_TRANSFER &&
            (subtype == "airdrop" || subtype == "reward")
        ) {
            return true
        }
        return false
    }

    /**
     * Applies balance deltas only for the fraction the basket already holds of every asset being
     * drawn down. A shortfall means the quantity entered the account outside the synthetic basket
     * — owner capital already valued and allocated by fixed recorded-anchor value weights, or anchor-excluded
     * holdings excluded from the basket — so replaying it would create impossible negative
     * holdings and count the same value twice. The whole movement, including its counter-legs, is
     * scaled by the smallest available fraction or skipped entirely.
     */
    internal fun applyAttributedMovement(balances: MutableMap<String, BigDecimal>, deltas: Map<String, BigDecimal>) {
        var fraction = BigDecimal.ONE
        for ((symbol, delta) in deltas) {
            if (delta.signum() >= 0) continue
            val held = balances[symbol] ?: BigDecimal.ZERO
            if (held.signum() <= 0) {
                fraction = BigDecimal.ZERO
                break
            }
            val required = delta.negate()
            if (held < required) {
                val assetFraction = held.divide(required, MOVEMENT_FRACTION_SCALE, RoundingMode.DOWN)
                if (assetFraction < fraction) {
                    fraction = assetFraction
                }
            }
        }
        if (fraction.signum() <= 0) return
        val scaleDeltas = fraction < BigDecimal.ONE
        for ((symbol, delta) in deltas) {
            val applied = if (scaleDeltas) delta.multiply(fraction) else delta
            balances[symbol] = (balances[symbol] ?: BigDecimal.ZERO).add(applied)
        }
    }

    private fun applyLedgerEvent(
        balances: MutableMap<String, BigDecimal>,
        ledger: LedgerEvent,
        useAuthoritativeBalance: Boolean,
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
            // Reward rows are exempt from the compatibility check: Kraken reports staking
            // amount/fee fields rounded (fee to four decimals), so their net economics never
            // reproduce the authoritative post balance. A staking row only reaches this branch
            // with a non-null authoritativeDelta when the interval flag is true, which the
            // validator-certified scope gate grants solely to Spot-resolved staking rows; the
            // stored post is then exchange truth for the Spot wallet and must be honored.
            // No tolerance value changes: the shared compatibility gate still applies to every other kind.
            val delta = if (
                authoritativeDelta != null &&
                (
                    authoritativeDelta.subtract(netDelta).abs() <= legacyLedgerFeeDeltaTolerance ||
                        isSpotResolvedStakingCorrection(ledger)
                    )
            ) {
                authoritativeDelta
            } else {
                netDelta
            }
            balances[symbol] = currentBalance.add(delta)
            return delta
        }
        // Approved cross-asset reward entitlement: only an explicitly documented staking-reward
        // pair (BTC -> BABY) may admit a not-yet-tracked Spot ledger, and only while the
        // qualifying source exposure was held (in-kind grant into the reward token wallet).
        // Every unsupported or unqualified new asset keeps failing closed at this checkpoint.
        val qualifyingAsset = RewardEntitlements.qualifyingSourceAsset(ledger)
        val qualifies = qualifyingAsset != null && (balances[qualifyingAsset]?.signum() ?: 0) > 0
        val delta = ledger.netBalanceDelta()
        if (qualifies && delta.signum() >= 0) {
            balances[symbol] = delta
            return delta
        }
        return delta
    }

    private fun applyRealizedTrade(
        balances: MutableMap<String, BigDecimal>,
        trade: TradeRecord,
        accountingMode: TradeAccountingMode,
        trackedUniverse: Set<String>,
        tradeLegsByRefId: Map<String, List<LedgerEvent>>,
        tradeLegsByTradeIdentity: Map<String, List<LedgerEvent>>,
    ): Boolean = applyRealizedTrade(
        balances,
        trade,
        realizedUsdNotional(trade, accountingMode),
        accountingMode,
        trackedUniverse,
        tradeLegsByRefId,
        tradeLegsByTradeIdentity,
    )

    private fun applyRealizedTrade(
        balances: MutableMap<String, BigDecimal>,
        trade: TradeRecord,
        usdNotional: BigDecimal,
        accountingMode: TradeAccountingMode,
        trackedUniverse: Set<String>,
        tradeLegsByRefId: Map<String, List<LedgerEvent>>,
        tradeLegsByTradeIdentity: Map<String, List<LedgerEvent>>,
    ): Boolean {
        if (
            trade.volume.signum() < 0 ||
            trade.usdAmount.signum() < 0 ||
            trade.price.signum() < 0 ||
            trade.fee.signum() < 0 ||
            usdNotional.signum() < 0
        ) {
            return false
        }
        // The shared historical classifier owns pair parsing, side validation and the
        // authoritative ledger effect; a malformed trade identity fails closed here.
        val replay = when (
            val classification = TradeLedgerReplay.classify(
                trade,
                tradeLegsByRefId,
                tradeLegsByTradeIdentity,
            )
        ) {
            is TradeLedgerReplay.Classification.Unsupported -> return false
            is TradeLedgerReplay.Classification.Replayable -> classification
        }
        val canUseReportedTradeEconomics = accountingMode == TradeAccountingMode.PERSISTED_TRADE_ECONOMICS &&
            replay.quote == Asset.USD &&
            (trade.source == TradeSource.API_FILL || trade.source == TradeSource.MANUAL) &&
            trade.price.signum() > 0 &&
            trade.price.multiply(trade.volume)
                .setScale(PrecisionConstants.SCALE_USD, RoundingMode.HALF_UP)
                .compareTo(trade.usdAmount.setScale(PrecisionConstants.SCALE_USD, RoundingMode.HALF_UP)) == 0
        if (canUseReportedTradeEconomics) {
            val baseDelta = if (replay.isBuy) trade.volume else trade.volume.negate()
            val baseBalance = balances[replay.base]
            when {
                baseBalance != null -> balances[replay.base] = baseBalance.add(baseDelta)

                replay.isBuy && replay.base in trackedUniverse ->
                    balances[replay.base] = baseDelta
            }
            if (!applyQuoteLeg(
                    balances,
                    replay.quote,
                    trackedUniverse,
                    persistedQuoteDelta(replay.isBuy, usdNotional, replay.fee),
                )
            ) {
                return false
            }
            return true
        }
        val ledgerEffect = replay.ledgerEffect
        if (ledgerEffect != null) {
            // Kraken charges the fee in the leg's own asset and ledger amounts can round from
            // the reported volume; the retained legs are the wallet truth. Some persisted
            // snapshots, however, were captured from the two-decimal TradeRecord cost while
            // the ledger retained a more precise quote debit. The legacy retry keeps the
            // authoritative base leg and only falls back to the persisted quote economics when
            // the ledger proves that its quote fee matches the reported fill.
            val baseBalance = balances[replay.base]
            val baseDelta = effectiveTradeLedgerDelta(
                currentBalance = baseBalance,
                checkpoint = ledgerEffect.baseCheckpoint,
                netDelta = ledgerEffect.baseNetDelta,
                grossDelta = ledgerEffect.baseGrossDelta,
            )
            when {
                baseBalance != null -> balances[replay.base] = baseBalance.add(baseDelta)

                replay.isBuy && replay.base in trackedUniverse ->
                    balances[replay.base] = baseDelta
            }
            val ledgerQuoteDelta = effectiveTradeLedgerDelta(
                currentBalance = balances[replay.quote],
                checkpoint = ledgerEffect.quoteCheckpoint,
                netDelta = ledgerEffect.quoteNetDelta,
                grossDelta = ledgerEffect.quoteGrossDelta,
            )
            val quoteDelta = if (
                accountingMode == TradeAccountingMode.PERSISTED_ROUNDED_COST &&
                shouldUsePersistedQuoteEconomics(trade, replay, ledgerEffect, usdNotional)
            ) {
                persistedQuoteDelta(replay.isBuy, usdNotional, replay.fee)
            } else {
                ledgerQuoteDelta
            }
            if (!applyQuoteLeg(balances, replay.quote, trackedUniverse, quoteDelta)) {
                return false
            }
            return true
        }
        if (replay.isBuy) {
            // The recorded series tracks the base only once the asset belongs to it; an
            // out-of-universe purchase settles entirely through its quote leg.
            if (replay.base in balances || replay.base in trackedUniverse) {
                balances[replay.base] = (balances[replay.base] ?: BigDecimal.ZERO).add(replay.volume)
            }
            val quoteDelta = usdNotional.add(replay.fee).negate()
            if (!applyQuoteLeg(balances, replay.quote, trackedUniverse, quoteDelta)) {
                return false
            }
        } else {
            // Liquidation of a holding absent from the tracked series is not an error: reconstruction
            // settles its quote proceeds without recording the base asset.
            balances[replay.base]?.let { baseBalance ->
                balances[replay.base] = baseBalance.subtract(replay.volume)
            }
            if (!applyQuoteLeg(balances, replay.quote, trackedUniverse, usdNotional.subtract(replay.fee))) {
                return false
            }
        }
        return true
    }

    /**
     * A persisted snapshot may use TradeRecord's rounded USD cost even when the ledger quote
     * leg carries the higher-precision fill debit. Keep ledger effects authoritative unless the
     * quote leg itself proves that it includes the TradeRecord fee; this excludes base-fee fills
     * whose quote leg intentionally contains no fee.
     */
    private fun shouldUsePersistedQuoteEconomics(
        trade: TradeRecord,
        replay: TradeLedgerReplay.Classification.Replayable,
        ledgerEffect: TradeLedgerReplay.LedgerEffect,
        persistedNotional: BigDecimal,
    ): Boolean {
        if (replay.quote != Asset.USD) return false
        if (trade.source != TradeSource.API_FILL && trade.source != TradeSource.MANUAL) return false
        if (trade.price.signum() <= 0) return false
        val preciseNotional = trade.price.multiply(trade.volume)
        if (
            preciseNotional.setScale(PrecisionConstants.SCALE_USD, RoundingMode.HALF_UP)
                .compareTo(trade.usdAmount.setScale(PrecisionConstants.SCALE_USD, RoundingMode.HALF_UP)) != 0
        ) {
            return false
        }
        val preciseQuoteDelta = persistedQuoteDelta(replay.isBuy, preciseNotional, replay.fee)
        return ledgerEffect.quoteNetDelta.subtract(preciseQuoteDelta).abs() <= legacyLedgerFeeDeltaTolerance
    }

    private fun persistedQuoteDelta(isBuy: Boolean, notional: BigDecimal, fee: BigDecimal): BigDecimal =
        if (isBuy) notional.add(fee).negate() else notional.subtract(fee)

    private fun effectiveTradeLedgerDelta(
        currentBalance: BigDecimal?,
        checkpoint: BigDecimal?,
        netDelta: BigDecimal,
        grossDelta: BigDecimal,
    ): BigDecimal {
        if (currentBalance == null || checkpoint == null) return netDelta
        val checkpointDelta = checkpoint.subtract(currentBalance)
        val netDeltaTolerance = if (grossDelta.compareTo(netDelta) == 0) {
            ledgerGrossDeltaTolerance
        } else {
            legacyLedgerFeeDeltaTolerance
        }
        return if (
            checkpointDelta.subtract(netDelta).abs() <= netDeltaTolerance ||
            checkpointDelta.subtract(grossDelta).abs() <= ledgerGrossDeltaTolerance
        ) {
            checkpointDelta
        } else {
            netDelta
        }
    }

    /**
     * Applies the quote leg of a recorded trade: the leg is invisible when the quote never
     * belongs to the recorded universe, while a tracked quote without a recorded balance is a
     * genuine gap that fails closed. The passive benchmark does not call this for trade mirroring;
     * the quote-leg helper remains part of actual balance reconciliation.
     */
    private fun applyQuoteLeg(
        balances: MutableMap<String, BigDecimal>,
        quote: String,
        trackedUniverse: Set<String>,
        delta: BigDecimal,
    ): Boolean {
        val quoteBalance = balances[quote]
        if (quoteBalance != null) {
            balances[quote] = quoteBalance.add(delta)
            return true
        }
        return quote !in trackedUniverse
    }

    private fun realizedUsdNotional(trade: TradeRecord, accountingMode: TradeAccountingMode): BigDecimal {
        val hasSettledFillEconomics = trade.source == TradeSource.API_FILL || trade.source == TradeSource.MANUAL
        val preciseNotional = if (hasSettledFillEconomics && trade.price.signum() > 0) {
            trade.price.multiply(trade.volume)
        } else {
            trade.usdAmount
        }
        return if (
            accountingMode != TradeAccountingMode.PRECISE_FILL_NOTIONAL &&
            hasSettledFillEconomics &&
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
        baseline.assets.filterValues { it.balance.signum() != 0 }.mapValues { (_, asset) -> asset.balance }

    private suspend fun calculateBuyAndHoldValue(
        syntheticBalances: Map<String, BigDecimal>,
        snapshot: PortfolioSnapshot,
        baselineTimestamp: Instant?,
        baselinePrices: Map<String, BigDecimal>,
        priceProvider: HistoricalPriceProvider?,
    ): BigDecimal {
        var total = BigDecimal.ZERO
        for ((symbol, balance) in syntheticBalances) {
            val price = if (symbol == Asset.USD) {
                BigDecimal.ONE
            } else {
                // New-asset deposits after baseline legitimately lack a
                // baseline price; skip symbols the current snapshot cannot
                // price rather than crashing the comparison.
                val snapshotPrice = snapshot.assets[symbol]?.price?.takeIf { it.signum() > 0 }
                when {
                    snapshotPrice != null -> snapshotPrice
                    priceProvider != null -> priceProvider.priceAt(symbol, snapshot.timestamp) ?: continue
                    snapshot.timestamp == baselineTimestamp -> baselinePrices[symbol] ?: continue
                    else -> continue
                }
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

    internal suspend fun buildBenchmarkEventsForTest(
        ledgers: List<LedgerEvent>,
        baseline: PortfolioSnapshot,
        inceptionWeights: Map<String, BigDecimal>,
        priceProvider: HistoricalPriceProvider?,
        provenanceResolver: FundingProvenanceResolver,
    ): List<BenchmarkEvent> {
        val prepared = provenanceResolver.prepare(ledgers)
        val classifications = LedgerFlowClassifier.classifyAll(ledgers, prepared)
        val feePriceProvider = CardFeePriceProvider { feeAsset, timestamp ->
            priceProvider?.priceAt(feeAsset, timestamp)
        }
        val cardNormalizations = CardFundingNormalizer.normalizeAll(
            events = ledgers,
            provenanceResolver = prepared,
            priceProvider = feePriceProvider,
        )
        val reconciled = ledgers.map {
            ReconciledLedger(
                ledger = it,
                timestamp = it.time,
                netBalanceDelta = it.netBalanceDelta(),
            )
        }
        return buildBenchmarkEvents(
            ledgers = reconciled,
            baseline = baseline,
            inceptionWeights = inceptionWeights,
            priceProvider = priceProvider,
            classifications = classifications,
            cardNormalizations = cardNormalizations,
            rewards = ledgers,
        ).events
    }
}
