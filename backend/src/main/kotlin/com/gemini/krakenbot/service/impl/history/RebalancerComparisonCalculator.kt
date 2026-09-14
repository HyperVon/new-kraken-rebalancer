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
import com.gemini.krakenbot.model.TradeOwnership
import com.gemini.krakenbot.model.TradeOwnershipClassifier
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
    private val legacyLedgerFeeDeltaTolerance = BigDecimal("0.00005")
    private val externalBalanceLedgerTypes = LedgerEvent.EXTERNAL_BALANCE_TYPES

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
        knownRebalancerOrderTxids: Set<String> = emptySet(),
        anchorSnapshot: PortfolioSnapshot? = null,
        inceptionSnapshot: PortfolioSnapshot? = null,
        knownInceptionTime: Instant? = null,
        historyTruncated: Boolean = false,
        priceProvider: HistoricalPriceProvider? = null,
        provenanceResolver: FundingProvenanceResolver = FundingProvenanceResolver.NONE,
        inceptionUnavailableReason: ComparisonUnavailableReason? = null,
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
        // The benchmark starts with the actual value held in configured target assets. Reconstructed
        // inception baselines may carry historical-only holdings recovered for accounting; they stay
        // out of the B&H basket, while their economic value remains part of the benchmark's starting
        // capital. A current target with no inception value contributes no original weight.
        val benchmarkInception = inceptionSnapshot?.restrictToOriginalBenchmarkHoldings()
        val hasConfiguredBenchmark = inceptionSnapshot?.assets?.any { (_, asset) ->
            asset.targetPercent.signum() > 0
        } == true
        val requiredReconciliationSymbols = if (hasConfiguredBenchmark) {
            inceptionSnapshot.assets.keys
                .filter { symbol -> inceptionSnapshot.assets.getValue(symbol).targetPercent.signum() > 0 }
                .map { Asset.normalizeLedgerAsset(it).uppercase() }
                .toSet()
        } else {
            emptySet()
        }
        val (baseline, effectiveSnapshots) = if (benchmarkInception != null) {
            val trimmed = if (!hasConfiguredBenchmark) {
                if (orderedSnapshots.first().timestamp < benchmarkInception.timestamp) {
                    val postInception = orderedSnapshots.filter { it.timestamp >= benchmarkInception.timestamp }
                    if (postInception.isEmpty() || postInception.first().timestamp > benchmarkInception.timestamp) {
                        // Preserve the legacy planless-fixture behavior: the explicit inception
                        // snapshot still supplies the economic baseline and fills a missing point.
                        listOf(inceptionSnapshot) + postInception
                    } else {
                        postInception
                    }
                } else {
                    // When the first retained point is already at or after the planless baseline,
                    // retain the existing point sequence and avoid a duplicate synthetic point.
                    orderedSnapshots
                }
            } else {
                when {
                    orderedSnapshots.first().timestamp < benchmarkInception.timestamp -> {
                        val postInception = orderedSnapshots.filter { it.timestamp >= benchmarkInception.timestamp }
                        if (postInception.isEmpty() || postInception.first().timestamp > benchmarkInception.timestamp) {
                            // Validation and point replay need the full wallet map (cash and quote
                            // balances) recorded at inception; only the benchmark weights stay
                            // restricted to configured targets.
                            listOf(inceptionSnapshot) + postInception
                        } else {
                            postInception
                        }
                    }

                    orderedSnapshots.first().timestamp > benchmarkInception.timestamp -> {
                        // A retained series may begin after the approved anchor. Preserve the full
                        // actual wallet as the first economic point and use the target-only view below
                        // for reconciliation, including zero-valued configured targets.
                        listOf(inceptionSnapshot) + orderedSnapshots
                    }

                    else -> orderedSnapshots
                }
            }
            if (trimmed.size < 2) {
                return unavailable(
                    reason = ComparisonUnavailableReason.INSUFFICIENT_SNAPSHOTS,
                    unavailableAt = orderedSnapshots.last().timestamp,
                    baselineTimestamp = benchmarkInception.timestamp,
                )
            }
            // Universe check runs against the trimmed first snapshot, not a pre-inception one.
            // Historical-only holdings may extend the series universe; every benchmark target
            // still has to be observable, while extra assets are legitimate actual-side holdings.
            if (!trimmed.first().assets.keys.containsAll(benchmarkInception.assets.keys)) {
                return unavailable(
                    reason = ComparisonUnavailableReason.ASSET_UNIVERSE_CHANGED,
                    unavailableAt = trimmed.first().timestamp,
                    baselineTimestamp = benchmarkInception.timestamp,
                )
            }
            benchmarkInception to trimmed
        } else {
            orderedSnapshots.first() to orderedSnapshots
        }
        // The recorded series still supplies the target universe and the wallet balance transitions
        // used for reconciliation. It is deliberately separate from the economic inception above:
        // reconstructed target percentages are current-plan projections, not historical weights.
        // A retained history may contain both an approved full-wallet anchor and a same-time
        // recorded twin. Prefer the same-time row that contains every current target key so a
        // stale twin's historical target metadata cannot drop a zero-valued configured asset.
        val reconciliationBaseline = if (benchmarkInception != null) {
            val sameTimestampSnapshots = effectiveSnapshots.filter {
                it.timestamp == benchmarkInception.timestamp
            }
            val exactSeriesSnapshot = if (hasConfiguredBenchmark) {
                sameTimestampSnapshots.firstOrNull { snapshot ->
                    snapshot.assets.keys
                        .map { Asset.normalizeLedgerAsset(it).uppercase() }
                        .toSet()
                        .containsAll(requiredReconciliationSymbols)
                } ?: sameTimestampSnapshots.firstOrNull()
            } else {
                sameTimestampSnapshots.firstOrNull()
            }
            exactSeriesSnapshot?.let {
                if (hasConfiguredBenchmark) {
                    it.restrictToReconciliationSymbols(requiredReconciliationSymbols)
                } else {
                    it.restrictToBenchmarkTargets()
                }
            }
                ?: if (hasConfiguredBenchmark && sameTimestampSnapshots.isEmpty()) {
                    inceptionSnapshot.restrictToReconciliationSymbols(requiredReconciliationSymbols)
                } else {
                    benchmarkInception
                }
        } else {
            baseline
        }
        val reconciliationSymbols = reconciliationBaseline.assets.keys
            .map { Asset.normalizeLedgerAsset(it).uppercase() }
            .toSet()
        if (!reconciliationSymbols.containsAll(requiredReconciliationSymbols)) {
            return unavailable(
                reason = ComparisonUnavailableReason.ASSET_UNIVERSE_CHANGED,
                unavailableAt = reconciliationBaseline.timestamp,
                baselineTimestamp = baseline.timestamp,
            )
        }
        val historicalOnlyInceptionValue = if (benchmarkInception != null) {
            inceptionSnapshot.excludedBenchmarkValue(benchmarkInception)
        } else {
            BigDecimal.ZERO
        }
        if (historicalOnlyInceptionValue.signum() < 0) {
            return unavailable(
                reason = ComparisonUnavailableReason.BASELINE_MISMATCH,
                unavailableAt = baseline.timestamp,
                baselineTimestamp = baseline.timestamp,
            )
        }

        val universeError = validateAssetUniverse(effectiveSnapshots, reconciliationBaseline)
        if (universeError != null) return universeError

        // The full inception wallet validates the economic starting capital, while the recorded
        // series baseline above validates the target-universe balance transitions.
        val baselineError = validateBaseline(
            if (inceptionSnapshot != null && benchmarkInception != null) inceptionSnapshot else baseline,
        )
        if (baselineError != null) return baselineError

        val priceError = validatePrices(effectiveSnapshots, reconciliationBaseline)
        if (priceError != null) return priceError

        val effectiveAnchor = anchorSnapshot?.takeIf {
            it.timestamp < baseline.timestamp && it.assets.keys.containsAll(reconciliationBaseline.assets.keys)
        }
        val validationSnapshots = if (effectiveAnchor != null) {
            listOf(effectiveAnchor) + effectiveSnapshots
        } else {
            effectiveSnapshots
        }

        // The recorded series tracks spot-wallet balances: ledger rows resolved to another
        // Kraken wallet scope (staking/futures) never moved the series, so they must not move
        // reconciliation balances either. Unresolved scopes keep the previous behavior.
        val ledgerScopes = AuthoritativeLedgerBalanceValidator.validate(rewards).resolvedScopes
        val spotRewards = rewards.filter { ledger ->
            val scope = ledgerScopes[ledger.ledgerId]
            scope == null || scope == AuthoritativeLedgerBalanceValidator.LedgerWalletScope.SPOT
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
            knownRebalancerOrderTxids = knownRebalancerOrderTxids,
            baseline = reconciliationBaseline,
            tradeLegsByRefId = tradeLegsByRefId,
            tradeLegsByTradeIdentity = tradeLegsByTradeIdentity,
            resolvedScopes = ledgerScopes,
            orphanTradeLedgerIds = orphanTradeLedgerIds,
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

        // Original inception value weights define the benchmark thesis. Every later owner
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
                trades = intermediateTrades + reconciledTrades,
                ledgers = intermediateLedgers + reconciledLedgers.filterNot {
                    it.ledger.ledgerId in orphanTradeLedgerIds
                },
                knownRebalancerOrderTxids = knownRebalancerOrderTxids,
                baseline = reconciliationBaseline,
                inceptionWeights = inceptionWeights,
                priceProvider = priceProvider,
                classifications = ledgerClassifications,
                cardNormalizations = cardNormalizations,
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
            baselineAssetSymbols = reconciliationBaseline.assets.keys
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

        val syntheticBaseline = if (inceptionSnapshot != null && benchmarkInception != null &&
            inceptionSnapshot.assets.any { (_, asset) -> asset.targetPercent.signum() > 0 }
        ) {
            try {
                syntheticBaselineBalances(
                    fullBaseline = inceptionSnapshot,
                    benchmarkBaseline = benchmarkInception,
                    weights = inceptionWeights,
                    priceProvider = priceProvider,
                )
            } catch (e: HistoricalPriceSourceException) {
                return unavailable(
                    reason = ComparisonUnavailableReason.HISTORICAL_PRICE_SOURCE_ERROR,
                    unavailableAt = e.eventTime,
                    baselineTimestamp = baseline.timestamp,
                )
            } ?: return unavailable(
                reason = ComparisonUnavailableReason.MISSING_PRICE,
                unavailableAt = baseline.timestamp,
                baselineTimestamp = baseline.timestamp,
            )
        } else {
            null
        }
        val baselineBalances = syntheticBaseline?.balances ?: run {
            // Planless fixtures and legacy snapshots have no configured target weights. Preserve
            // their established keep-all behavior until a real configured benchmark is present.
            extractBaselineBalances(baseline)
        }
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
                        tradeLegsByRefId,
                        tradeLegsByTradeIdentity,
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
                baselinePrices = syntheticBaseline?.prices.orEmpty(),
            )
            if (buyAndHoldValue.signum() <= 0) {
                return unavailable(
                    reason = ComparisonUnavailableReason.NON_POSITIVE_BASELINE,
                    unavailableAt = snapshot.timestamp,
                    baselineTimestamp = baseline.timestamp,
                )
            }
            val rebalancerValue = if (
                inceptionSnapshot != null && snapshot.timestamp == inceptionSnapshot.timestamp
            ) {
                // Series queries hide the preserved identity anchor when a reconstructed row
                // shares its instant. The anchor still represents the full actual wallet, so a
                // historical-only holding remains visible on the actual side of the first point.
                inceptionSnapshot.totalValueUSD
            } else {
                snapshot.totalValueUSD
            }
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
            // Both sides represent the same economic capital at inception. Historical-only
            // holdings are transformed into configured-target units in [baselineBalances], not
            // dropped from the benchmark, so the expected initial difference is zero.
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
            if (index == 0 && isStartingAtBaseline && historicalOnlyInceptionValue.signum() == 0) {
                point.copy(
                    rebalancerValueUSD = (inceptionSnapshot?.totalValueUSD ?: baseline.totalValueUSD).setScale(
                        PrecisionConstants.SCALE_USD,
                        RoundingMode.HALF_UP,
                    ),
                    buyAndHoldValueUSD = (inceptionSnapshot?.totalValueUSD ?: baseline.totalValueUSD).setScale(
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
            if (!snapshot.assets.keys.containsAll(baselineKeys)) {
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
                    val zeroBalanceTargetAtBaseline = snapshot.timestamp == baseline.timestamp &&
                        assetRow.balance.signum() == 0 &&
                        (baseline.assets[symbol]?.targetPercent?.signum() ?: 0) > 0
                    if (!zeroBalanceTargetAtBaseline) {
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

    private fun validateTrackedBalanceChanges(
        snapshots: List<PortfolioSnapshot>,
        trades: List<TradeRecord>,
        ledgers: List<LedgerEvent>,
        knownRebalancerOrderTxids: Set<String>,
        baseline: PortfolioSnapshot,
        tradeLegsByRefId: Map<String, List<LedgerEvent>>,
        tradeLegsByTradeIdentity: Map<String, List<LedgerEvent>>,
        resolvedScopes: Map<String, AuthoritativeLedgerBalanceValidator.LedgerWalletScope>,
        orphanTradeLedgerIds: Set<String>,
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
                (it.type in externalBalanceLedgerTypes || it.ledgerId in orphanTradeLedgerIds) &&
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

        val baselineAssetSymbols = baseline.assets.keys
            .map { Asset.normalizeLedgerAsset(it).uppercase() }
            .toSet()
        for ((_, trade) in indexedTrades.filter { (_, trade) -> trade.timestamp <= lastObservationTime }) {
            val ownership = TradeOwnershipClassifier.classify(
                trade = trade,
                knownRebalancerOrderTxids = knownRebalancerOrderTxids,
            )
            if (ownership == TradeOwnership.UNKNOWN && tradeTouchesAssets(trade, baselineAssetSymbols)) {
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

            // A trade can change tracked balances through either leg: a configured-target base
            // is tracked directly, and a supported quote is tracked whenever the quote asset is
            // part of the baseline (USD always is). Historical-only bases therefore still settle
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

            val lateTradeCandidates = indexedTrades
                .filter { (index, trade) ->
                    index !in assignedTradeIndexes &&
                        index !in initialTradeIndices &&
                        trade.timestamp > currObs &&
                        trade.timestamp <= latestCandidateTime(curr) &&
                        affectsTrackedBalances(trade)
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
                val lateAssignment = if (!balancesMatchSnapshot(impliedBalances, curr)) {
                    findLateAssignment(
                        lateCandidates,
                        impliedBalances,
                        curr,
                        intervalAccountingMode,
                        useAuthoritativeLedgerBalances,
                        tradeLegsByRefId,
                        tradeLegsByTradeIdentity,
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

            // A fully liquidated historical-only asset can disappear from the recorded series,
            // so only keys with a materially non-zero balance must still be observable.
            if (impliedBalances.any { (symbol, balance) ->
                    symbol !in curr.assets &&
                        balance.setScale(balanceScale(symbol), RoundingMode.HALF_UP).signum() != 0
                }
            ) {
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
    ): Boolean {
        for ((symbol, expectedBalance) in expectedBalances) {
            val scale = balanceScale(symbol)
            // Only crypto quantities can carry the one-unit replay offset; quote cash is cent-exact.
            val tolerance =
                if (symbol == Asset.USD) BigDecimal.ZERO else BigDecimal.ONE.movePointLeft(scale)
            val roundedExpected = expectedBalance.setScale(scale, RoundingMode.HALF_UP)
            val snapshotBalance = snapshot.assets[symbol]?.balance
            if (snapshotBalance == null) {
                // A fully liquidated position may be dropped from the recorded series.
                if (roundedExpected.signum() == 0) continue
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
            // A holding the replayed events never produced is unexplained: historical-only
            // assets are tolerated only when the recorded trades or ledgers acquired them.
            val scale = balanceScale(symbol)
            val tolerance =
                if (symbol == Asset.USD) BigDecimal.ZERO else BigDecimal.ONE.movePointLeft(scale)
            val net = asset.balance.setScale(scale, RoundingMode.HALF_UP).abs()
            if (net.compareTo(tolerance) > 0) return false
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
                        tradeLegsByRefId,
                        tradeLegsByTradeIdentity,
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
        tradeLegsByRefId: Map<String, List<LedgerEvent>>,
        tradeLegsByTradeIdentity: Map<String, List<LedgerEvent>>,
    ): LateAssignment? {
        if (candidates.isEmpty() || candidates.size > MAX_BOUNDARY_EVENT_CANDIDATES) return null
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

        var match: LateAssignment? = null
        var multipleMatches = false
        val selectedTrades = mutableListOf<Int>()
        val selectedLedgers = mutableListOf<Int>()
        val selectedLedgerDeltas = mutableMapOf<Int, BigDecimal>()

        fun search(position: Int, balances: Map<String, BigDecimal>) {
            if (multipleMatches) return
            if (position == orderedCandidates.size) {
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
     * Restrict a reconstructed inception baseline to configured benchmark targets (assets with a
     * positive target percent). Baselines without any positive target keep all assets so planless
     * fixtures and legacy snapshots behave exactly as before.
     */
    private fun PortfolioSnapshot.restrictToBenchmarkTargets(): PortfolioSnapshot {
        val targeted = assets.filterValues { asset ->
            asset.targetPercent.signum() > 0
        }
        return if (targeted.isEmpty()) {
            this
        } else {
            copy(
                assets = targeted,
                totalValueUSD = targeted.values.fold(BigDecimal.ZERO) { total, asset ->
                    total.add(asset.valueUSD)
                },
            )
        }
    }

    /**
     * Keep the configured reconciliation rows by symbol, including zero-valued targets. A
     * same-time recorded twin may carry an older target projection where a configured cash row
     * has target zero; its presence is still required for balance reconciliation.
     */
    private fun PortfolioSnapshot.restrictToReconciliationSymbols(symbols: Set<String>): PortfolioSnapshot {
        val retained = assets.filterKeys { symbol ->
            Asset.normalizeLedgerAsset(symbol).uppercase() in symbols
        }
        return copy(
            assets = retained,
            totalValueUSD = retained.values.fold(BigDecimal.ZERO) { total, asset ->
                total.add(asset.valueUSD)
            },
        )
    }

    /**
     * Keep only configured target assets that had actual inception value. This is the historical
     * basket whose value weights seed synthetic Buy & Hold units; zero-valued targets and
     * historical-only assets must not receive inception capital.
     */
    private fun PortfolioSnapshot.restrictToOriginalBenchmarkHoldings(): PortfolioSnapshot {
        if (assets.none { (_, asset) -> asset.targetPercent.signum() > 0 }) return this
        val originalHoldings = assets.filterValues { asset ->
            asset.targetPercent.signum() > 0 && asset.valueUSD.signum() > 0
        }
        return copy(
            assets = originalHoldings,
            totalValueUSD = originalHoldings.values.fold(BigDecimal.ZERO) { total, asset ->
                total.add(asset.valueUSD)
            },
        )
    }

    private fun PortfolioSnapshot.excludedBenchmarkValue(benchmark: PortfolioSnapshot): BigDecimal =
        totalValueUSD.subtract(benchmark.totalValueUSD)

    /**
     * Original inception value weights (normalized symbol to fraction, summing exactly to one at
     * [WEIGHT_DIVISION_SCALE]). Target percentages describe the current plan, not a historical
     * allocation record, so they must not rewrite the benchmark's inception thesis.
     */
    private fun baselineValueWeights(baseline: PortfolioSnapshot): Map<String, BigDecimal> {
        val total = baseline.totalValueUSD
        if (total.signum() <= 0) return emptyMap()
        val raw = baseline.assets.mapValues { (_, asset) ->
            asset.valueUSD.divide(total, WEIGHT_DIVISION_SCALE, RoundingMode.HALF_UP)
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

    /**
     * Converts full actual inception capital into synthetic units of the original configured
     * holdings. Historical-only assets and zero-valued targets are intentionally absent; their
     * value is represented by value-weighted units of the original holdings exactly once.
     */
    private data class SyntheticBaseline(val balances: Map<String, BigDecimal>, val prices: Map<String, BigDecimal>)

    private suspend fun syntheticBaselineBalances(
        fullBaseline: PortfolioSnapshot,
        benchmarkBaseline: PortfolioSnapshot,
        weights: Map<String, BigDecimal>,
        priceProvider: HistoricalPriceProvider?,
    ): SyntheticBaseline? {
        val totalCapital = fullBaseline.totalValueUSD
        if (totalCapital.signum() <= 0) return null
        val prices = mutableMapOf<String, BigDecimal>()
        for ((symbol, _) in weights) {
            val asset = benchmarkBaseline.assets[symbol] ?: return null
            val price = if (symbol == Asset.USD) {
                BigDecimal.ONE
            } else {
                asset.price.takeIf { it.signum() > 0 }
                    ?: priceProvider?.priceAt(symbol, fullBaseline.timestamp)
            }
            if (price == null || price.signum() <= 0) return null
            prices[symbol] = price
        }
        return SyntheticBaseline(
            balances = weights.mapValues { (symbol, weight) ->
                totalCapital
                    .multiply(weight)
                    .divide(prices.getValue(symbol), ALLOCATION_UNIT_SCALE, RoundingMode.HALF_UP)
            },
            prices = prices,
        )
    }

    private suspend fun buildBenchmarkEvents(
        trades: List<ReconciledTrade>,
        ledgers: List<ReconciledLedger>,
        knownRebalancerOrderTxids: Set<String>,
        baseline: PortfolioSnapshot,
        inceptionWeights: Map<String, BigDecimal>,
        priceProvider: HistoricalPriceProvider?,
        classifications: Map<String, FlowCategory>,
        cardNormalizations: List<NormalizedFundingTransaction>,
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

        // A complete top-level conversion is one linked economic event. Keep its legs together
        // so Buy & Hold transforms the same holdings as the actual account without treating the
        // destination credit as owner capital. A group straddling the baseline is not safely
        // replayable from one side and therefore remains unavailable.
        val conversionGroups = ledgers
            .filter { isConversionLedger(it.ledger) }
            .groupBy { it.ledger.refid?.trim().orEmpty() }
        val trackedBaselineSymbols = baseline.assets.keys
            .map { Asset.normalizeLedgerAsset(it).uppercase() }
            .toSet()
        for ((refid, group) in conversionGroups) {
            val postGroup = group.filter { postBaselineById.containsKey(it.ledger.ledgerId) }
            if (postGroup.isEmpty()) continue
            val conversionAt = postGroup.minOf { it.timestamp }
            if (refid.isBlank() || postGroup.size != group.size ||
                group.size != 2 || group.any { classifications[it.ledger.ledgerId] != FlowCategory.INTERNAL_MOVE }
            ) {
                return BuiltEvents(
                    events = emptyList(),
                    unpriceableAt = null,
                    ambiguousAt = conversionAt,
                )
            }
            if (group.none {
                    Asset.normalizeLedgerAsset(it.ledger.asset).uppercase() in trackedBaselineSymbols
                }
            ) {
                // The reconstructed spot series does not carry conversions wholly outside its
                // tracked universe (for example USD -> USDG). Do not replay those legs into B&H or
                // let them collide with an unrelated tracked-asset event at the same instant.
                consumedLedgerIds += group.map { it.ledger.ledgerId }
                continue
            }
            events += BenchmarkEvent.InternalConversion(
                timestamp = conversionAt,
                legs = group
                    .sortedWith(compareBy({ it.ledger.time }, { it.ledger.ledgerId }))
                    .map { BenchmarkEvent.ConversionLeg(it.ledger, it.netBalanceDelta) },
            )
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
                            netBalanceDelta = norm.netOwnerCapitalUsd,
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
                            netBalanceDelta = norm.netOwnerCapitalUsd,
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
        // external credits/debits. Replaying the rows separately can credit a tracked receive
        // while silently dropping a spend whose source asset is outside the synthetic basket.
        // Preserve atomicity for complete groups and fail closed for linked passthrough groups
        // whose shape is incomplete; unlinked rows retain their existing external-balance policy.
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
                return BuiltEvents(
                    events = emptyList(),
                    unpriceableAt = null,
                    ambiguousAt = groupAt,
                )
            }
            events += BenchmarkEvent.InternalConversion(
                timestamp = groupAt,
                legs = group
                    .sortedWith(compareBy({ it.ledger.time }, { it.ledger.ledgerId }))
                    .map { BenchmarkEvent.ConversionLeg(it.ledger, it.netBalanceDelta) },
            )
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
                // Complete groups were emitted atomically above. Any remaining row is a
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
        return BuiltEvents(events, null)
    }

    private fun isConversionLedger(ledger: LedgerEvent): Boolean =
        ledger.type.equals(KrakenApiConstants.LEDGER_TYPE_CONVERSION, ignoreCase = true)

    /**
     * Event timestamps alone do not establish whether a balance movement was
     * applied before or after a trade or owner flow. Additive movements in
     * established baseline assets are safe, but owner withdrawals, same-instant
     * owner contributions paired with trades, trades on unallocated assets, and
     * non-plumbing balance changes are not commutative.
     * Passthrough USD legs are exempt because [CardFundingNormalizer] has
     * already collapsed their typed economics into one owner event where that
     * is safe; otherwise defer instead of replaying an arbitrary sort order.
     */
    private fun findUnorderedBenchmarkEventTimestamp(
        events: List<BenchmarkEvent>,
        baselineAssetSymbols: Set<String>,
    ): Instant? {
        val ownerContributions = events.filterIsInstance<BenchmarkEvent.OwnerContribution>()
        val ownerWithdrawals = events.filterIsInstance<BenchmarkEvent.OwnerWithdrawal>()
        val trades = events.filterIsInstance<BenchmarkEvent.Trade>()
            .filter { it.ownership == TradeOwnership.MANUAL_OR_EXTERNAL }
        val externalBalances = events.filterIsInstance<BenchmarkEvent.ExternalBalance>()
        val internalConversions = events.filterIsInstance<BenchmarkEvent.InternalConversion>()
        val balanceMovements: List<BenchmarkEvent> = externalBalances + internalConversions
        val movementAssetsByEvent = buildMap<BenchmarkEvent, Set<String>> {
            externalBalances.forEach { event ->
                put(event, setOf(Asset.normalizeLedgerAsset(event.asset).uppercase()))
            }
            internalConversions.forEach { event ->
                put(
                    event,
                    event.legs.map {
                        Asset.normalizeLedgerAsset(it.event.asset).uppercase()
                    }.toSet(),
                )
            }
        }

        fun syntheticTradeAssets(event: BenchmarkEvent.Trade): Set<String> =
            listOfNotNull(Asset.splitTradingPair(event.trade.pair))
                .filter { it.base in baselineAssetSymbols }
                .flatMap { listOf(it.base, it.quote) }
                .toSet()

        val unallocatedTrades = trades.filter { trade ->
            Asset.normalizeLedgerAsset(trade.trade.symbol).uppercase() !in baselineAssetSymbols &&
                syntheticTradeAssets(trade).isNotEmpty()
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
        val tradeInteractions = trades.map { event ->
            SourceInteraction(
                timestamp = event.timestamp,
                sourceEventTimestamps = setOf(event.trade.timestamp),
                assets = syntheticTradeAssets(event),
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
        val withdrawalTradeCandidates = trades.filter { trade ->
            syntheticTradeAssets(trade).isNotEmpty()
        }
        val withdrawalMovementCandidates = balanceMovements.filter { movement ->
            movementAssetsByEvent.getValue(movement).any { it in baselineAssetSymbols }
        }
        earliestNearPairTimestamp(
            ownerWithdrawals,
            withdrawalTradeCandidates + withdrawalMovementCandidates,
        )?.let(unorderedTimes::add)
        // Contributions are allocated across the original configured inception basket, so
        // a mirrorable target trade may consume newly contributed capital. A
        // source-time collision has no exchange sequence evidence and remains
        // order-dependent. New/unallocated assets retain the existing bounded
        // clock-skew guard. Card plumbing legs have already been collapsed by
        // [CardFundingNormalizer] into one owner event; strategy-neutral
        // balance changes remain additive.
        earliestNearPairTimestamp(ownerContributions, unallocatedTrades)?.let(unorderedTimes::add)
        earliestNearPairTimestamp(ownerContributions, ownerWithdrawals)?.let(unorderedTimes::add)

        val targetAssetConversions = internalConversions.filter { conversion ->
            movementAssetsByEvent.getValue(conversion).any { it in baselineAssetSymbols }
        }
        val targetConversionInteractions = targetAssetConversions.map { event ->
            SourceInteraction(
                timestamp = event.timestamp,
                sourceEventTimestamps = event.legs.map { it.event.time }.toSet(),
                assets = movementAssetsByEvent.getValue(event).intersect(baselineAssetSymbols),
            )
        }
        // Internal conversions can consume synthetic target units. When their source timestamp
        // is indistinguishable from an owner contribution or target trade, replay order is not
        // evidence-backed; defer instead of selecting the construction order's outcome.
        earliestSourceOverlapPairTimestamp(ownerWithdrawalInteractions, ownerContributionInteractions)
            ?.let(unorderedTimes::add)
        earliestSourceOverlapPairTimestamp(ownerWithdrawalInteractions, targetConversionInteractions)
            ?.let(unorderedTimes::add)
        earliestSourceOverlapPairTimestamp(ownerWithdrawalInteractions, tradeInteractions)
            ?.let(unorderedTimes::add)
        earliestSourceOverlapPairTimestamp(ownerContributionInteractions, tradeInteractions)
            ?.let(unorderedTimes::add)
        earliestSourceOverlapPairTimestamp(ownerContributionInteractions, targetConversionInteractions)
            ?.let(unorderedTimes::add)
        earliestSourceOverlapPairTimestamp(targetConversionInteractions, tradeInteractions)
            ?.let(unorderedTimes::add)

        val newAssetBalanceMovements = balanceMovements.filter { movement ->
            movementAssetsByEvent.getValue(movement).any { it !in baselineAssetSymbols }
        }
        earliestNearPairTimestamp(
            newAssetBalanceMovements,
            trades.filter { trade ->
                val symbol = Asset.normalizeLedgerAsset(trade.trade.symbol).uppercase()
                newAssetBalanceMovements.any { movement ->
                    symbol in movementAssetsByEvent.getValue(movement)
                }
            },
        )?.let(unorderedTimes::add)

        return unorderedTimes.minOrNull()
    }

    /**
     * Maps a genuine owner-capital ledger row to a typed benchmark event.
     * Contributions are invested by the fixed original inception value weights at
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
        inceptionWeights: Map<String, BigDecimal>,
        priceProvider: HistoricalPriceProvider?,
        sourceLedgerIds: List<String>,
        sourceEventTimestamps: Set<Instant> = setOf(ledger.time),
    ): OwnerFlowBuild {
        val symbol = Asset.normalizeLedgerAsset(ledger.asset).uppercase()
        val unitPrice = if (symbol == Asset.USD) {
            BigDecimal.ONE
        } else {
            priceProvider?.priceAt(symbol, ledger.time)
        }
        if (unitPrice == null || unitPrice.signum() <= 0) return OwnerFlowBuild.Unpriceable
        val cashUsd = netBalanceDelta.multiply(unitPrice)
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
        tradeLegsByRefId: Map<String, List<LedgerEvent>>,
        tradeLegsByTradeIdentity: Map<String, List<LedgerEvent>>,
    ): Pair<ComparisonUnavailableReason, Instant>? {
        when (event) {
            is BenchmarkEvent.ExternalBalance -> {
                val symbol = Asset.normalizeLedgerAsset(event.asset).uppercase()
                // A reward is benchmark entitlement only when the synthetic basket already held
                // that asset. A reward in an otherwise unheld asset belongs to the actual account
                // but cannot be attributed to the fixed original B&H thesis without entitlement
                // evidence; skip it instead of creating synthetic capital.
                val isUnheldReward = LedgerEvent.isRewardEvent(event.event) &&
                    event.netAmount.signum() > 0 &&
                    (balances[symbol]?.signum() ?: 0) <= 0
                if (!isUnheldReward) {
                    applyAttributedMovement(balances, mapOf(symbol to event.netAmount))
                }
            }

            is BenchmarkEvent.InternalConversion -> {
                // Apply each leg exactly once. No raw amount netting is attempted across assets;
                // the persisted per-leg net delta already includes that leg's authoritative fee.
                val deltas = event.legs.groupingBy { leg ->
                    Asset.normalizeLedgerAsset(leg.event.asset).uppercase()
                }.fold(BigDecimal.ZERO) { total, leg ->
                    total.add(leg.netBalanceDelta)
                }
                applyAttributedMovement(balances, deltas)
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

            is BenchmarkEvent.Trade -> {
                // Buy & Hold mirrors only trades in its configured-target basket: a
                // historical-only base must never acquire a synthetic benchmark holding.
                val base = Asset.splitTradingPair(event.trade.pair)?.base
                if (event.ownership == TradeOwnership.MANUAL_OR_EXTERNAL && base != null && base in balances) {
                    applyMirroredTrade(balances, event, tradeLegsByRefId, tradeLegsByTradeIdentity)
                }
            }
        }
        return null
    }

    /**
     * Mirrors a manual trade only to the extent the synthetic basket can source it. The trade is
     * first replayed against a copy so its realized per-asset deltas stay owned by
     * [applyRealizedTrade]; the copy is then committed through [applyAttributedMovement].
     */
    private fun applyMirroredTrade(
        balances: MutableMap<String, BigDecimal>,
        event: BenchmarkEvent.Trade,
        tradeLegsByRefId: Map<String, List<LedgerEvent>>,
        tradeLegsByTradeIdentity: Map<String, List<LedgerEvent>>,
    ) {
        val trial = balances.toMutableMap()
        // A quote the basket does not hold still has to reach attribution. Seeding it at zero lets
        // applyQuoteLeg record the drawdown, so the movement is skipped or scaled by its
        // basket-held share instead of mirroring the base at full size with no funding leg.
        Asset.splitTradingPair(event.trade.pair)?.quote?.let { quote -> trial.putIfAbsent(quote, BigDecimal.ZERO) }
        if (!applyRealizedTrade(
                trial,
                event.trade,
                event.usdNotional,
                trial.keys,
                tradeLegsByRefId,
                tradeLegsByTradeIdentity,
            )
        ) {
            return
        }
        val deltas = trial.mapNotNull { (symbol, value) ->
            val delta = value.subtract(balances[symbol] ?: BigDecimal.ZERO)
            if (delta.signum() == 0) null else symbol to delta
        }.toMap()
        applyAttributedMovement(balances, deltas)
    }

    /**
     * Applies balance deltas only for the fraction the basket already holds of every asset being
     * drawn down. A shortfall means the quantity entered the account outside the synthetic basket
     * — owner capital already valued and allocated by fixed original inception value weights, or historical-only
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
        trackedUniverse: Set<String>,
        tradeLegsByRefId: Map<String, List<LedgerEvent>>,
        tradeLegsByTradeIdentity: Map<String, List<LedgerEvent>>,
    ): Boolean = applyRealizedTrade(
        balances,
        trade,
        realizedUsdNotional(trade, accountingMode),
        trackedUniverse,
        tradeLegsByRefId,
        tradeLegsByTradeIdentity,
    )

    private fun applyRealizedTrade(
        balances: MutableMap<String, BigDecimal>,
        trade: TradeRecord,
        usdNotional: BigDecimal,
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
        val ledgerEffect = replay.ledgerEffect
        if (ledgerEffect != null) {
            // Kraken charges the fee in the leg's own asset and ledger amounts can round from
            // the reported volume; the retained legs are the wallet truth.
            val baseBalance = balances[replay.base]
            when {
                baseBalance != null -> balances[replay.base] = baseBalance.add(ledgerEffect.baseNetDelta)

                replay.isBuy && replay.base in trackedUniverse ->
                    balances[replay.base] = ledgerEffect.baseNetDelta
            }
            if (!applyQuoteLeg(balances, replay.quote, trackedUniverse, ledgerEffect.quoteNetDelta)) {
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
            // Liquidation of a historical-only holding is not an error: reconstruction
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
     * Applies the quote leg of a recorded trade: the leg is invisible when the quote never
     * belongs to the recorded universe, while a tracked quote without a recorded balance is a
     * genuine gap that fails closed. Benchmark trade mirroring seeds an unheld quote at zero
     * before calling this, so the drawdown still reaches proportional attribution instead of
     * being dropped.
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
            accountingMode == TradeAccountingMode.PERSISTED_ROUNDED_COST &&
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
        baseline.assets.mapValues { (_, asset) -> asset.balance }

    private fun calculateBuyAndHoldValue(
        syntheticBalances: Map<String, BigDecimal>,
        snapshot: PortfolioSnapshot,
        baselineTimestamp: Instant? = null,
        baselinePrices: Map<String, BigDecimal> = emptyMap(),
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
            trades = emptyList(),
            ledgers = reconciled,
            knownRebalancerOrderTxids = emptySet(),
            baseline = baseline,
            inceptionWeights = inceptionWeights,
            priceProvider = priceProvider,
            classifications = classifications,
            cardNormalizations = cardNormalizations,
        ).events
    }
}
