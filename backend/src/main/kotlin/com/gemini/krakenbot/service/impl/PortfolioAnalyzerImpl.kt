package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.domain.AssetPrices
import com.gemini.krakenbot.domain.AssetValues
import com.gemini.krakenbot.domain.PortfolioCalculations
import com.gemini.krakenbot.domain.PortfolioValues
import com.gemini.krakenbot.domain.RawBalances
import com.gemini.krakenbot.domain.RawPrices
import com.gemini.krakenbot.domain.RebalancePlan
import com.gemini.krakenbot.domain.RebalancerEngine
import com.gemini.krakenbot.domain.toUsdScale
import com.gemini.krakenbot.model.ActualOwnerFlowContext
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.CardFeePriceProvider
import com.gemini.krakenbot.model.FlowCategory
import com.gemini.krakenbot.model.FundingProvenanceResolver
import com.gemini.krakenbot.model.KrakenApiConstants
import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.model.LedgerFlowClassifier
import com.gemini.krakenbot.model.NormalizedFundingTransaction
import com.gemini.krakenbot.model.OrderSide
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.Result
import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.model.TimedAssetDelta
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.model.UnusableDecidedFundingContext
import com.gemini.krakenbot.repository.AppliedAthFlow
import com.gemini.krakenbot.repository.LedgerRepository
import com.gemini.krakenbot.repository.PortfolioStatsRepository
import com.gemini.krakenbot.repository.TradeRepository
import com.gemini.krakenbot.service.AthTrustFailureException
import com.gemini.krakenbot.service.AthTrustFailureReason
import com.gemini.krakenbot.service.AthUpdateResult
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.service.ObservedBalances
import com.gemini.krakenbot.service.PortfolioAnalyzer
import com.gemini.krakenbot.service.impl.history.CardFundingNormalizer
import com.gemini.krakenbot.util.PrecisionConstants
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import com.gemini.krakenbot.domain.resolveBalance as resolveBalanceFromKeys

class PortfolioAnalyzerImpl(
    private val krakenService: KrakenService,
    private val configService: ConfigService,
    private val portfolioStatsRepository: PortfolioStatsRepository,
    private val nowProvider: () -> Instant = Instant::now,
    private val ledgerRepository: LedgerRepository? = null,
    private val tradeRepository: TradeRepository? = null,
    private val defaultProvenanceResolver: FundingProvenanceResolver = FundingProvenanceResolver.NONE,
) : PortfolioAnalyzer {
    private val log = LoggerFactory.getLogger(PortfolioAnalyzerImpl::class.java)

    /**
     * Fetches current account balances and records the local balance-request start boundary.
     * Capturing [observedAt] BEFORE initiating [KrakenService.getBalances] ensures a conservative
     * lower temporal boundary: any exchange events occurring after this timestamp cannot safely
     * be assumed to already be reflected in the returned balance snapshot unless reconciliation
     * proves they were.
     */
    override suspend fun fetchObservedBalances(): ObservedBalances {
        val observedAt = nowProvider()
        val balances = krakenService.getBalances()
        log.info("Available Balance Keys: {}", balances.keys)
        return ObservedBalances(balances = balances, observedAt = observedAt)
    }

    override suspend fun fetchBalances(): RawBalances = fetchObservedBalances().balances

    override suspend fun fetchPrices(): AssetPrices {
        val allocations = configService.getConfig().allocations
        val nonUsd = allocations.filter { !it.symbol.isUsd }
        if (nonUsd.isEmpty()) return emptyMap()

        val pairs =
            nonUsd.joinToString(",") {
                it.symbol.tradingPair
            }
        val rawPrices = krakenService.getTickerPrices(pairs)

        return nonUsd.associate { (symbol, _) ->
            symbol.value to
                resolvePriceFromTicker(
                    symbol.value,
                    rawPrices,
                )
        }
    }

    override fun resolvePriceFromTicker(symbol: String, rawPrices: RawPrices): BigDecimal =
        RebalancerEngine.resolvePriceFromTicker(symbol, rawPrices)

    override fun calculatePortfolioValues(balances: RawBalances, prices: AssetPrices): Result<PortfolioValues> =
        RebalancerEngine.calculatePortfolioValues(balances, prices, configService.getConfig().allocations)

    override fun resolveBalance(symbol: String, balances: RawBalances): BigDecimal =
        resolveBalanceFromKeys(symbol, balances)

    override suspend fun updateAthAndCalculateDrawdown(
        totalPortfolioValueUSD: BigDecimal,
        netExternalFlowUSD: BigDecimal,
        balancesObservedAt: Instant?,
    ): AthUpdateResult = updateAthAndCalculateDrawdown(
        totalPortfolioValueUSD,
        netExternalFlowUSD,
        balancesObservedAt,
        defaultProvenanceResolver,
    )

    override suspend fun updateAthAndCalculateDrawdown(
        totalPortfolioValueUSD: BigDecimal,
        netExternalFlowUSD: BigDecimal,
        balancesObservedAt: Instant?,
        provenanceResolver: FundingProvenanceResolver,
    ): AthUpdateResult {
        val stats = portfolioStatsRepository.load()
        var ath = stats.allTimeHigh
        // Initial ATH may only be established from a zero starting point. A
        // positive ATH that flows zeroed mid-cycle must stay zero: resurrecting
        // it from the current (post-withdrawal) total would invent performance.
        val hadAthAtEntry = ath > BigDecimal.ZERO
        // Checkpointed in the same transaction as the ATH value itself, so a
        // crash can neither lose an applied flow nor apply one twice.
        val appliedFlows = mutableListOf<AppliedAthFlow>()
        var pendingFlowWatermarkSec: Long? = null

        if (netExternalFlowUSD.signum() != 0) {
            if (ath > BigDecimal.ZERO) {
                val preFlowValueUSD = totalPortfolioValueUSD.subtract(netExternalFlowUSD)
                val adjustedAth = RebalancerEngine.adjustAthForCashFlow(ath, preFlowValueUSD, netExternalFlowUSD)
                if (adjustedAth.compareTo(ath) != 0) {
                    log.info(
                        "ATH adjusted for external cash flow (netFlow={}): {} -> {}",
                        netExternalFlowUSD.toUsdScale(),
                        ath.toUsdScale(),
                        adjustedAth.toUsdScale(),
                    )
                    ath = adjustedAth
                }
            }
        } else {
            // No ATH history to scale yet: fold all flows into initial ATH
            // below. Still advance the watermark so the unapplied window does
            // not grow unboundedly across fresh-start cycles.
            //
            // An unpriceable or unbasis-able flow defers the whole update
            // instead of aborting the cycle: aborting would skip the snapshot
            // save and could stall later cycles forever, while deferring keeps
            // snapshotting (which is exactly what future basis resolution
            // needs) with deployment forced to zero.
            val flowCalc = try {
                if (ath > BigDecimal.ZERO) {
                    calculateUnappliedExternalFlow(balancesObservedAt, provenanceResolver)
                } else {
                    val coverage = ledgerRepository
                        ?.getSyncMetadata(SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC)
                        ?.toLongOrNull()
                    // A present ledger subsystem with unknown coverage plus a
                    // dated observation means the total may contain unseen
                    // owner capital: no initial ATH may be established from it.
                    // A null subsystem (no ledger history exists anywhere, so
                    // no sync could ever confirm coverage) keeps the legacy
                    // proceed behavior instead of deferring forever.
                    if (ledgerRepository != null && coverage == null && balancesObservedAt != null) {
                        // Fails closed via the IllegalStateException handler
                        // above.
                        throw AthTrustFailureException(
                            reason = AthTrustFailureReason.LEDGER_COVERAGE_UNKNOWN,
                            message = "ledger coverage unknown for balances observed at $balancesObservedAt",
                        )
                    }
                    // Mirror of the ath>0 temporal gate: a balance observed
                    // past coverage may contain owner capital the ledger has
                    // not confirmed, so no baseline may be established from it.
                    val coverageInstant = coverage?.let(Instant::ofEpochSecond)
                    if (coverageInstant != null &&
                        balancesObservedAt != null &&
                        balancesObservedAt.isAfter(coverageInstant)
                    ) {
                        throw AthTrustFailureException(
                            reason = AthTrustFailureReason.LEDGER_COVERAGE_STALE,
                            message = "ledger coverage $coverage predates balances observed at $balancesObservedAt",
                        )
                    }
                    // The initial ATH is the current total, which already
                    // contains every flow up to the observation and nothing
                    // the ledger has not confirmed: absorb to the earlier of
                    // coverage and the observation. Rows above the observation
                    // are not in the baseline and scale on later cycles.
                    val absorbHorizon = coverageInstant?.let { coverageTime ->
                        balancesObservedAt?.let { observation -> minOf(coverageTime, observation) } ?: coverageTime
                    }
                    if (absorbHorizon != null) {
                        appliedFlows.addAll(
                            absorbUnappliedFlowsIntoInitialAth(
                                absorbHorizon,
                                provenanceResolver,
                                balancesObservedAt,
                            ),
                        )
                    }
                    // Hold the watermark at the absorb horizon: rows above it
                    // were not in the baseline, and a later one-time migration
                    // must never presume them decided.
                    ExternalFlowCalculation(emptyList(), absorbHorizon?.epochSecond)
                }
            } catch (e: CancellationException) {
                // Coroutine cancellation is an IllegalStateException subtype:
                // it must propagate, never degrade into a deferral.
                throw e
            } catch (e: AthTrustFailureException) {
                log.warn(
                    "Deferring ATH update: reason={} detail={}",
                    e.reason,
                    e.message,
                )
                return AthUpdateResult.Deferred(stats.lastTrustedDrawdownPct, e.reason)
            } catch (e: IllegalStateException) {
                log.warn(
                    "Deferring ATH update: reason={} detail={}",
                    AthTrustFailureReason.PERSISTENCE_FAILURE,
                    e.message,
                )
                return AthUpdateResult.Deferred(stats.lastTrustedDrawdownPct, AthTrustFailureReason.PERSISTENCE_FAILURE)
            } catch (e: Exception) {
                log.warn(
                    "Deferring ATH update: reason={} detail={}",
                    AthTrustFailureReason.PERSISTENCE_FAILURE,
                    e.message,
                )
                return AthUpdateResult.Deferred(stats.lastTrustedDrawdownPct, AthTrustFailureReason.PERSISTENCE_FAILURE)
            }
            if (flowCalc.coverageStale) {
                // The balance may already contain owner capital the ledger
                // window has not seen yet. It must neither establish a new ATH
                // nor produce a drawdown that drives fiat deployment. Preserve
                // the previous trusted state untouched and let the caller fail
                // closed on deployment.
                log.warn(
                    "Deferring ATH update: balances observed at {} are not covered by confirmed ledger history " +
                        "(coverage horizon: {}); preserving trusted ATH {}",
                    balancesObservedAt,
                    flowCalc.coverageHorizon?.toString() ?: "unknown",
                    ath.toUsdScale(),
                )
                return AthUpdateResult.Deferred(
                    stats.lastTrustedDrawdownPct,
                    AthTrustFailureReason.LEDGER_COVERAGE_STALE,
                )
            }
            var brokeEarly = false
            // Consciously-skipped decisions (internal move, trade ignored, external balance,
            // off-universe owner capital) are terminal regardless of what the scaling loop
            // below does, so they join the journal now and are never re-warned.
            appliedFlows.addAll(flowCalc.skippedDecided)
            // Bases resolve lazily per group: groups after an early break are
            // never priced, so an unbasis-able later flow cannot fail a cycle
            // whose applicable prefix is fine (it is retried next cycle).
            for ((groupIndex, step) in flowCalc.sequentialFlows.withIndex()) {
                // A step is checkpointed only when its scaling was actually
                // applied. Anything after an early break stays unacknowledged
                // and is retried next cycle.
                if (ath <= BigDecimal.ZERO) {
                    brokeEarly = true
                    break
                }
                if (step.flowUSD.signum() == 0) {
                    // Net-zero simultaneous group: no scaling, but its
                    // identities are still consumed so they are not retried.
                    for (ledgerId in step.ledgerIds) {
                        appliedFlows.add(
                            flowCalc.appliedFlowSemantics.getValue(ledgerId),
                        )
                    }
                    continue
                }
                // Fail-closed: an unbasis-able group defers the whole update
                // with no writes (the checkpoint below is skipped), so the
                // prefix is retried verbatim next cycle.
                val basis = try {
                    flowCalc.groupBasisResolver.basisFor(groupIndex)
                } catch (e: AthTrustFailureException) {
                    log.warn(
                        "Deferring ATH update: reason={} detail={}",
                        e.reason,
                        e.message,
                    )
                    return AthUpdateResult.Deferred(stats.lastTrustedDrawdownPct, e.reason)
                } catch (e: IllegalStateException) {
                    log.warn(
                        "Deferring ATH update: reason={} detail={}",
                        AthTrustFailureReason.PERSISTENCE_FAILURE,
                        e.message,
                    )
                    return AthUpdateResult.Deferred(
                        stats.lastTrustedDrawdownPct,
                        AthTrustFailureReason.PERSISTENCE_FAILURE,
                    )
                }
                if (basis == null) {
                    // The flow predates every retained snapshot: its effect is
                    // already inside the initial ATH baseline. Journal it as a
                    // conscious skip (never silently scale with a residual guess).
                    log.warn(
                        "ATH flow(s) at {} predate all retained snapshots; treating as baked into the " +
                            "initial ATH baseline (ledgerIds: {})",
                        step.eventTime,
                        step.ledgerIds,
                    )
                    for (ledgerId in step.ledgerIds) {
                        appliedFlows.add(
                            flowCalc.appliedFlowSemantics.getValue(ledgerId),
                        )
                    }
                    continue
                }
                val adjustedAth = RebalancerEngine.adjustAthForCashFlow(ath, basis, step.flowUSD)
                if (adjustedAth.compareTo(ath) != 0) {
                    log.info(
                        "ATH adjusted for owner-capital flow at {} (flow={}): {} -> {}",
                        step.eventTime,
                        step.flowUSD.toUsdScale(),
                        ath.toUsdScale(),
                        adjustedAth.toUsdScale(),
                    )
                    ath = adjustedAth
                }
                for (ledgerId in step.ledgerIds) {
                    appliedFlows.add(
                        flowCalc.appliedFlowSemantics.getValue(ledgerId),
                    )
                }
            }
            // Hold the watermark when scaling stopped early: the journal (not
            // the timestamp) is the exact record, and unapplied flows below
            // an advanced watermark would be lost. They are reprocessed next
            // cycle while recorded identities are skipped.
            pendingFlowWatermarkSec = if (brokeEarly) null else flowCalc.pendingWatermarkSec
        }

        when {
            ath <= BigDecimal.ZERO && !hadAthAtEntry -> {
                ath = totalPortfolioValueUSD
                log.info(
                    "Initial ATH set to {}",
                    ath.toUsdScale(),
                )
            }

            ath > BigDecimal.ZERO && totalPortfolioValueUSD > ath -> {
                ath = totalPortfolioValueUSD
                log.info(
                    "New All-Time High detected: {}",
                    ath.toUsdScale(),
                )
            }
        }
        val drawdownPct = RebalancerEngine.calculateDrawdown(totalPortfolioValueUSD, ath)
        val updatedStats = stats.copy(allTimeHigh = ath, lastTrustedDrawdownPct = drawdownPct)
        try {
            portfolioStatsRepository.saveAthStateWithFlowCheckpoint(
                stats = updatedStats,
                appliedFlows = appliedFlows,
                flowWatermarkSec = pendingFlowWatermarkSec,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Fail closed: a lost ATH understates drawdown and would over-deploy crypto into a
            // real drawdown next cycle. The cycle must not plan against an ATH it could not store.
            // The checkpoint shares the same transaction, so nothing is half-applied: the next
            // cycle retries the same flows exactly once.
            log.error("Failed to persist portfolio ATH; aborting the cycle", e)
            throw e
        }

        return AthUpdateResult.Trusted(drawdownPct)
    }

    private data class SequentialFlowStep(val ledgerIds: List<String>, val eventTime: Instant, val flowUSD: BigDecimal)

    private data class ExternalFlowCalculation(
        val sequentialFlows: List<SequentialFlowStep>,
        val pendingWatermarkSec: Long?,
        val coverageStale: Boolean = false,
        val coverageHorizon: Instant? = null,
        val skippedDecided: List<AppliedAthFlow> = emptyList(),
        val appliedFlowSemantics: Map<String, AppliedAthFlow> = emptyMap(),
        val groupBasisResolver: GroupBasisResolver = noFlowsResolver(),
    )

    private data class ScannedLedgers(
        @JvmField val unapplied: List<LedgerEvent>,
        @JvmField val classifications: Map<String, FlowCategory>,
        @JvmField val allRetained: List<LedgerEvent>,
        @JvmField val decidedLedgerIds: Set<String>,
        @JvmField val preparedProvenanceResolver: FundingProvenanceResolver,
    )

    /**
     * A completed card group as it actually changed account balances. The
     * owner-capital amount remains the synthetic ATH/B&H flow; these deltas
     * are used only when reconstructing a later pre-flow portfolio basis.
     */
    private data class CardActualFlow(
        val representativeLedgerId: String,
        val eventTime: Instant,
        val sourceLedgerIds: Set<String>,
        val sourceTimes: List<Instant>,
        val actualPortfolioDeltas: List<TimedAssetDelta>,
    )

    /**
     * Identity-driven reconciliation: every retained ledger row up to
     * [horizon] minus the decision journal, classified with refid pairing.
     * The journal (not the watermark timestamp) records what has already been
     * decided, so sync backfill and window-straddling rows are reconciled
     * exactly once and crash replays stay idempotent.
     */
    private suspend fun scanUndecidedLedgerEvents(
        horizon: Instant,
        provenanceResolver: FundingProvenanceResolver,
    ): ScannedLedgers {
        val ledgersRepo = ledgerRepository ?: return ScannedLedgers(
            unapplied = emptyList(),
            classifications = emptyMap(),
            allRetained = emptyList(),
            decidedLedgerIds = emptySet(),
            preparedProvenanceResolver = provenanceResolver,
        )
        val lookaheadHorizon = horizon.plus(CardFundingNormalizer.MAX_CARD_TRANSACTION_SPAN)
        val allRows = ledgersRepo.getLedgersInRange(Instant.EPOCH, lookaheadHorizon)
            .sortedBy { it.time }
        if (allRows.isEmpty()) {
            return ScannedLedgers(
                unapplied = emptyList(),
                classifications = emptyMap(),
                allRetained = emptyList(),
                decidedLedgerIds = emptySet(),
                preparedProvenanceResolver = provenanceResolver,
            )
        }
        val preHorizonRows = allRows.filter { !it.time.isAfter(horizon) }
        val preHorizonRefids = preHorizonRows.mapNotNull { it.refid?.trim() }.filter { it.isNotEmpty() }.toSet()
        val postHorizonLookaheadRows =
            allRows.filter { it.time.isAfter(horizon) && it.refid?.trim() in preHorizonRefids }
        // Lookahead is context only. Future rows never enter provenance,
        // classification, normalization, economics, or journal state.
        val rows = preHorizonRows
        // Resolve funding provenance once for the confirmed batch. The
        // classifier itself stays pure and never performs one request per row.
        val preparedResolver = try {
            provenanceResolver.prepare(rows)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw AthTrustFailureException(
                reason = AthTrustFailureReason.FUNDING_PROVENANCE_UNAVAILABLE,
                message = "Funding provenance preparation failed: ${e.message ?: e::class.simpleName}",
                cause = e,
            )
        }
        preparedResolver.preparationFailure?.let { failure ->
            throw AthTrustFailureException(
                reason = AthTrustFailureReason.FUNDING_PROVENANCE_UNAVAILABLE,
                message = failure.message,
            )
        }
        val decided = portfolioStatsRepository.getAppliedAthFlowIds(rows.map { it.ledgerId })
        val unapplied = preHorizonRows.filterNot { it.ledgerId in decided }
        // Any undecided pre-horizon leg sharing a refid with a post-horizon
        // sibling is held back. This includes passthrough-first arrival, where
        // classifying spend/receive as a neutral balance change would otherwise
        // journal it before the funding leg and break group atomicity.
        val postHorizonRefids = CardFundingNormalizer.identifyCandidateGroups(postHorizonLookaheadRows).keys
        val unappliedRefids = CardFundingNormalizer.identifyCandidateGroups(unapplied).keys
        val incompleteLookaheadRefids = postHorizonRefids
            .intersect(unappliedRefids)
        if (incompleteLookaheadRefids.isNotEmpty()) {
            throw AthTrustFailureException(
                reason = AthTrustFailureReason.AMBIGUOUS_FUNDING,
                message = "card funding group(s) extend beyond the confirmed horizon: " +
                    incompleteLookaheadRefids.joinToString(),
            )
        }
        return ScannedLedgers(
            unapplied = unapplied,
            classifications = LedgerFlowClassifier.classifyAll(rows, preparedResolver),
            allRetained = rows,
            decidedLedgerIds = decided,
            preparedProvenanceResolver = preparedResolver,
        )
    }

    /**
     * Initial-ATH absorption: when ATH is established from the current total,
     * that total already contains every confirmed flow below the coverage
     * horizon, so all undecided decision-bearing rows are journaled as
     * absorbed. Without this the identity scan would re-apply lifetime history
     * against the post-fold baseline on the next cycle (the old watermark
     * advance used to absorb them implicitly).
     */
    private suspend fun absorbUnappliedFlowsIntoInitialAth(
        horizon: Instant,
        provenanceResolver: FundingProvenanceResolver,
        balancesObservedAt: Instant?,
    ): List<AppliedAthFlow> {
        val scanned = scanUndecidedLedgerEvents(horizon, provenanceResolver)
        val unapplied = scanned.unapplied
        val classifications = scanned.classifications
        if (unapplied.isEmpty()) return emptyList()

        val unappliedIds = unapplied.mapTo(mutableSetOf()) { it.ledgerId }
        val decidedIds = scanned.decidedLedgerIds
        val straddlingGroup = CardFundingNormalizer.identifyCandidateGroups(scanned.allRetained)
            .entries
            .firstOrNull { (_, group) ->
                val groupIds = group.map { it.ledgerId }.toSet()
                group.any(CardFundingNormalizer::isFundingLeg) &&
                    group.any {
                        classifications.getValue(it.ledgerId) != FlowCategory.INTERNAL_MOVE &&
                            classifications.getValue(it.ledgerId) != FlowCategory.TRADE_IGNORED
                    } &&
                    groupIds.any { it in decidedIds } &&
                    groupIds.any { it in unappliedIds }
            }
        if (straddlingGroup != null) {
            throw AthTrustFailureException(
                reason = AthTrustFailureReason.AMBIGUOUS_FUNDING,
                message = "ledger identity group ${straddlingGroup.key} has decided and newly arrived " +
                    "funding/plumbing siblings; refusing partial migration replay",
            )
        }
        val relevantRefids = CardFundingNormalizer.identifyCandidateGroups(scanned.allRetained)
            .filterValues { group -> group.any { it.ledgerId in unappliedIds } }
            .keys
        val feePriceProvider = CardFeePriceProvider { asset, timestamp ->
            tradeRepository?.let { resolvePriceForEvent(asset, timestamp, balancesObservedAt, it) }
        }
        val cardNormalizations = CardFundingNormalizer.normalizeAll(
            events = scanned.allRetained,
            provenanceResolver = scanned.preparedProvenanceResolver,
            priceProvider = feePriceProvider,
        )
        for (norm in cardNormalizations) {
            val normRefid = when (norm) {
                is NormalizedFundingTransaction.Ambiguous -> norm.refid
                is NormalizedFundingTransaction.UnpriceableFee -> norm.refid
                is NormalizedFundingTransaction.OwnerFlow -> norm.refid
                NormalizedFundingTransaction.NotApplicable -> null
            }
            if (normRefid !in relevantRefids) continue
            when (norm) {
                is NormalizedFundingTransaction.Ambiguous -> throw AthTrustFailureException(
                    reason = AthTrustFailureReason.AMBIGUOUS_FUNDING,
                    message = norm.reason,
                )

                is NormalizedFundingTransaction.UnpriceableFee -> throw AthTrustFailureException(
                    reason = AthTrustFailureReason.HISTORICAL_PRICE_UNAVAILABLE,
                    message = "Cannot price fee asset ${norm.asset} for card refid ${norm.refid}",
                )

                else -> Unit
            }
        }
        val absorbed = mutableListOf<AppliedAthFlow>()
        val cardGroupIdsByLedger = cardNormalizations
            .filterIsInstance<NormalizedFundingTransaction.OwnerFlow>()
            .flatMap { norm -> norm.sourceLedgerIds.map { it to norm.refid } }
            .toMap()
        for (event in unapplied) {
            val category = classifications.getValue(event.ledgerId)
            if (category == FlowCategory.AMBIGUOUS || category == FlowCategory.UNSUPPORTED) {
                log.warn(
                    "Cannot establish initial ATH baseline: ledger history contains unresolved " +
                        "ambiguous funding event {} ({}, {}) at {}",
                    event.ledgerId,
                    event.type,
                    event.asset,
                    event.time,
                )
                throw AthTrustFailureException(
                    reason = if (category == FlowCategory.UNSUPPORTED) {
                        AthTrustFailureReason.UNSUPPORTED_LEDGER_EVENT
                    } else {
                        AthTrustFailureReason.AMBIGUOUS_FUNDING
                    },
                    message = "Cannot establish initial ATH baseline: unresolved ambiguous funding event " +
                        "${event.ledgerId} (${event.type}, ${event.asset}) at ${event.time}",
                )
            }
            if (category != FlowCategory.INTERNAL_MOVE && category != FlowCategory.TRADE_IGNORED) {
                absorbed.add(
                    cardGroupIdsByLedger[event.ledgerId]
                        ?.let { appliedFlowFor(event, FlowCategory.OWNER_CAPITAL, it) }
                        ?: appliedFlowFor(event, category),
                )
            }
        }
        if (absorbed.isNotEmpty()) {
            log.info(
                "Initial ATH established from the current total: journaling {} flow(s) as absorbed " +
                    "(their effect is already inside the baseline)",
                absorbed.size,
            )
        }
        return absorbed
    }

    private suspend fun calculateUnappliedExternalFlow(
        balancesObservedAt: Instant?,
        provenanceResolver: FundingProvenanceResolver,
    ): ExternalFlowCalculation {
        val ledgersRepo = ledgerRepository ?: return ExternalFlowCalculation(emptyList(), null)
        val tradesRepo = tradeRepository ?: return ExternalFlowCalculation(emptyList(), null)

        val ledgerCoverageSec = ledgersRepo.getSyncMetadata(SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC)?.toLongOrNull()
        if (ledgerCoverageSec == null) {
            // No confirmed ledger coverage (sync never succeeded or metadata
            // corrupt). With a dated observation the total may contain unseen
            // owner capital, so defer rather than ratchet ATH on it. Callers
            // without an observation keep the previous proceed-without-flows
            // behavior. Fails closed via the IllegalStateException handler.
            if (balancesObservedAt != null) {
                throw AthTrustFailureException(
                    reason = AthTrustFailureReason.LEDGER_COVERAGE_UNKNOWN,
                    message = "ledger coverage unknown for balances observed at $balancesObservedAt",
                )
            }
            return ExternalFlowCalculation(emptyList(), null)
        }

        // Temporal coverage gate: balances observed after ledger coverage may
        // include capital the ledger window has not seen yet. The caller
        // treats a stale gate as an untrusted balance: no flow processing, no
        // ATH ratchet, no deployment-driving drawdown.
        val ledgerCoverage = Instant.ofEpochSecond(ledgerCoverageSec)
        if (balancesObservedAt != null && balancesObservedAt.isAfter(ledgerCoverage)) {
            return ExternalFlowCalculation(
                emptyList(),
                null,
                coverageStale = true,
                coverageHorizon = ledgerCoverage,
            )
        }
        // Coverage may run past the observation (production observes balances
        // before the sync that confirms coverage): flows between the
        // observation and coverage are not reflected in the total yet, so cap
        // the scan horizon at the observation; they reconcile on later cycles.
        val confirmedHorizon = balancesObservedAt?.let { minOf(ledgerCoverage, it) } ?: ledgerCoverage

        val watermarkStr = tradesRepo.getSyncMetadata(SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC)
        if (watermarkStr == null) {
            // Bootstrap: first dated coverage establishes the observability
            // watermark; everything below it predates flow tracking entirely
            // and is folded into the initial ATH baseline like a fresh install.
            return ExternalFlowCalculation(
                emptyList(),
                confirmedHorizon.epochSecond,
                skippedDecided = absorbUnappliedFlowsIntoInitialAth(
                    confirmedHorizon,
                    provenanceResolver,
                    balancesObservedAt,
                ),
            )
        }

        val watermarkSec = watermarkStr.toLongOrNull()
            // A corrupt watermark must not silently advance past unapplied
            // flows (skipped withdrawals would overstate drawdown and
            // over-deploy). Fail closed via the IllegalStateException handler;
            // the operator repairs the key and the missing-watermark path above
            // re-establishes the window.
            ?: throw AthTrustFailureException(
                reason = AthTrustFailureReason.LEDGER_COVERAGE_UNKNOWN,
                message = "malformed ATH flow watermark: $watermarkStr",
            )

        var scanned = scanUndecidedLedgerEvents(confirmedHorizon, provenanceResolver)
        if (ledgersRepo.getSyncMetadata(SyncMetadataKeys.ATH_FLOW_JOURNAL_MIGRATED) == null) {
            if (Instant.ofEpochSecond(watermarkSec).isAfter(confirmedHorizon)) {
                throw AthTrustFailureException(
                    reason = AthTrustFailureReason.PRE_FLOW_BASIS_UNCERTAIN,
                    message = "legacy ATH watermark exceeds the confirmed horizon; journal migration deferred",
                )
            }
            // One-time upgrade from the timestamp-window semantics: rows below
            // the legacy watermark were already decided (applied or skipped)
            // and their journal entries were pruned by the old watermark
            // delete. Presume them decided so the identity scan never
            // re-applies legacy flows and double-scales ATH. Late-arriving
            // history below the legacy watermark is a documented casualty of
            // the upgrade; rows arriving after migration reconcile by
            // identity.
            val legacyRows = ledgersRepo.getLedgersInRange(Instant.EPOCH, Instant.ofEpochSecond(watermarkSec))
            portfolioStatsRepository.journalPresumedDecidedFlows(
                legacyRows.map { AppliedAthFlow(ledgerId = it.ledgerId, eventTimeSec = it.time.epochSecond) },
            )
            ledgersRepo.setSyncMetadata(SyncMetadataKeys.ATH_FLOW_JOURNAL_MIGRATED, "true")
            val legacyIds = legacyRows.mapTo(mutableSetOf()) { it.ledgerId }
            scanned = scanned.copy(
                unapplied = scanned.unapplied.filterNot { it.ledgerId in legacyIds },
                decidedLedgerIds = scanned.decidedLedgerIds + legacyIds,
            )
            if (legacyRows.isNotEmpty()) {
                log.warn(
                    "ATH flow journal migration: presumed {} pre-watermark ledger row(s) already decided; " +
                        "clearing {} and {} re-presumes them — a genuine re-scan requires restoring " +
                        "a database backup taken before the scaling",
                    legacyRows.size,
                    SyncMetadataKeys.ATH_FLOW_JOURNAL_MIGRATED,
                    SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC,
                )
            }
        }

        // Identity-driven reconciliation: scan every retained ledger row up to
        // confirmed coverage, not just the window above the watermark. Sync
        // backfill and refid pairs straddling the old window boundary are
        // decided exactly once because the decision journal (not the
        // watermark timestamp) filters what has already been through
        // classification.
        val unapplied = scanned.unapplied
        val classifications = scanned.classifications
        val allRetained = scanned.allRetained
        if (unapplied.isEmpty()) return ExternalFlowCalculation(emptyList(), confirmedHorizon.epochSecond)

        val unappliedIds = unapplied.mapTo(mutableSetOf()) { it.ledgerId }
        val decidedIds = scanned.decidedLedgerIds
        val straddlingGroup = CardFundingNormalizer.identifyCandidateGroups(allRetained)
            .entries
            .firstOrNull { (_, group) ->
                val groupIds = group.map { it.ledgerId }.toSet()
                group.any(CardFundingNormalizer::isFundingLeg) &&
                    group.any {
                        classifications.getValue(it.ledgerId) != FlowCategory.INTERNAL_MOVE &&
                            classifications.getValue(it.ledgerId) != FlowCategory.TRADE_IGNORED
                    } &&
                    groupIds.any { it in decidedIds } &&
                    groupIds.any { it in unappliedIds }
            }
        if (straddlingGroup != null) {
            throw AthTrustFailureException(
                reason = AthTrustFailureReason.AMBIGUOUS_FUNDING,
                message = "ledger identity group ${straddlingGroup.key} has decided and newly arrived " +
                    "funding/plumbing siblings; refusing partial migration replay",
            )
        }
        val candidateGroups = CardFundingNormalizer.identifyCandidateGroups(allRetained)
        val relevantRefids = candidateGroups
            .filterValues { group -> group.any { it.ledgerId in unappliedIds } }
            .keys

        // Classify with refid pairing: internal wallet moves, trade rows, and
        // external balance events must never scale ATH. Terminal neutral events
        // (INTERNAL_MOVE, TRADE_IGNORED) and performance events (EXTERNAL_BALANCE)
        // are acknowledged in the journal so they are not re-processed.
        // Off-universe OWNER_CAPITAL is acknowledged as skipped.
        // Crucially, AMBIGUOUS and UNSUPPORTED funding events MUST NOT be journaled
        // as decided: they fail-closed by deferring the ATH update until affirmative
        // evidence or resolution arrives, preserving exact-once replay later.
        val universe = configService.getConfig().allocations.map { it.symbol.value.uppercase() }.toSet()
        val feePriceProvider = CardFeePriceProvider { feeAsset, timestamp ->
            resolvePriceForEvent(feeAsset, timestamp, balancesObservedAt, tradesRepo)
        }

        // 1. Economic owner-capital normalization: ONLY for groups that intersect undecided identities.
        // Full normalization requires converting crypto-denominated fees to USD.
        val cardNormalizations = mutableListOf<NormalizedFundingTransaction>()
        for (refid in relevantRefids) {
            val group = candidateGroups.getValue(refid)
            val norm = CardFundingNormalizer.normalizeGroup(
                refid = refid,
                group = group,
                provenanceResolver = scanned.preparedProvenanceResolver,
                priceProvider = feePriceProvider,
            )
            if (norm !is NormalizedFundingTransaction.NotApplicable) {
                cardNormalizations.add(norm)
            }
        }
        for (norm in cardNormalizations) {
            when (norm) {
                is NormalizedFundingTransaction.Ambiguous -> {
                    log.warn(
                        "Card funding normalization ambiguous at {} (refid {}): {}",
                        norm.unavailableAt,
                        norm.refid,
                        norm.reason,
                    )
                    throw AthTrustFailureException(
                        reason = AthTrustFailureReason.AMBIGUOUS_FUNDING,
                        message = norm.reason,
                    )
                }

                is NormalizedFundingTransaction.UnpriceableFee -> {
                    log.warn(
                        "Cannot price fee asset {} at {} for card refid {}",
                        norm.asset,
                        norm.unavailableAt,
                        norm.refid,
                    )
                    throw AthTrustFailureException(
                        reason = AthTrustFailureReason.HISTORICAL_PRICE_UNAVAILABLE,
                        message = "Cannot price fee asset ${norm.asset} for card refid ${norm.refid}",
                    )
                }

                else -> Unit
            }
        }

        val cardOwnerFlows = cardNormalizations.filterIsInstance<NormalizedFundingTransaction.OwnerFlow>()
            .associateBy { it.representativeLedgerId }

        val undecidedActualFlows = cardNormalizations.filterIsInstance<NormalizedFundingTransaction.OwnerFlow>()
            .map { norm ->
                CardActualFlow(
                    representativeLedgerId = norm.representativeLedgerId,
                    eventTime = norm.eventTime,
                    sourceLedgerIds = norm.sourceLedgerIds.toSet(),
                    sourceTimes = allRetained.filter { it.ledgerId in norm.sourceLedgerIds }.map { it.time },
                    actualPortfolioDeltas = norm.actualPortfolioDeltas,
                )
            }

        // 2. Actual-balance context extraction: for already-decided historical groups.
        // Uses raw netBalanceDelta() directly from ledger rows; does NOT re-price historical fees.
        val decidedGroups = candidateGroups.filterKeys { it !in relevantRefids }
        val decidedActualFlows = mutableListOf<CardActualFlow>()
        val unusableDecidedGroups = mutableListOf<UnusableDecidedFundingContext>()
        for ((refid, group) in decidedGroups) {
            val parsed = CardFundingNormalizer.parseCardFundingGroup(
                refid = refid,
                group = group,
                provenanceResolver = scanned.preparedProvenanceResolver,
            )
            when (parsed) {
                is CardFundingNormalizer.ParsedGroup.Valid -> {
                    decidedActualFlows.add(
                        CardActualFlow(
                            representativeLedgerId = parsed.representative.ledgerId,
                            eventTime = parsed.representative.time,
                            sourceLedgerIds = parsed.sourceLedgerIds,
                            sourceTimes = allRetained.filter { it.ledgerId in parsed.sourceLedgerIds }.map { it.time },
                            actualPortfolioDeltas = parsed.actualPortfolioDeltas,
                        ),
                    )
                }

                is CardFundingNormalizer.ParsedGroup.Ambiguous -> {
                    var minTime = group.first().time
                    var maxTime = group.first().time
                    for (item in group) {
                        if (item.time < minTime) minTime = item.time
                        if (item.time > maxTime) maxTime = item.time
                    }
                    unusableDecidedGroups.add(
                        UnusableDecidedFundingContext(
                            refid = refid,
                            sourceLedgerIds = group.map { it.ledgerId }.toSet(),
                            minTime = minTime,
                            maxTime = maxTime,
                            reason = parsed.reason,
                        ),
                    )
                }

                CardFundingNormalizer.ParsedGroup.NotApplicable -> Unit
            }
        }

        val cardActualFlows = undecidedActualFlows + decidedActualFlows
        val allCardSourceIds = cardActualFlows.flatMap { it.sourceLedgerIds }.toSet()
        val unusableCardSourceIds = unusableDecidedGroups.flatMap { it.sourceLedgerIds }.toSet()
        val allKnownCardSourceIds = allCardSourceIds + unusableCardSourceIds
        val allCardPlumbingIds = allCardSourceIds - cardActualFlows.map { it.representativeLedgerId }.toSet()

        val skippedDecided = mutableListOf<AppliedAthFlow>()
        val events = mutableListOf<LedgerEvent>()
        for (event in unapplied) {
            if (event.ledgerId in allCardPlumbingIds) {
                continue
            }
            val category = classifications.getValue(event.ledgerId)
            if (category == FlowCategory.AMBIGUOUS || category == FlowCategory.UNSUPPORTED) {
                log.warn(
                    "Deferring ATH update: unapplied ambiguous funding event {} (type={}, asset={}, amount={}) " +
                        "at {} cannot be classified as owner capital or internal move",
                    event.ledgerId,
                    event.type,
                    event.asset,
                    event.amount,
                    event.time,
                )
                throw AthTrustFailureException(
                    reason = if (category == FlowCategory.UNSUPPORTED) {
                        AthTrustFailureReason.UNSUPPORTED_LEDGER_EVENT
                    } else {
                        AthTrustFailureReason.AMBIGUOUS_FUNDING
                    },
                    message =
                    "unapplied ambiguous funding event ${event.ledgerId} (${event.type}, ${event.asset}) at ${event.time}",
                )
            }
            if (category == FlowCategory.OWNER_CAPITAL &&
                isInAthUniverse(Asset.normalizeLedgerAsset(event.asset).uppercase(), universe)
            ) {
                events.add(event)
            } else if (category != FlowCategory.INTERNAL_MOVE && category != FlowCategory.TRADE_IGNORED) {
                log.warn(
                    "Skipping ATH scaling for off-universe or terminal {} flow {} at {} (category {})",
                    event.type,
                    event.ledgerId,
                    event.time,
                    category,
                )
                skippedDecided.add(appliedFlowFor(event, category))
            }
        }
        events.sortWith(compareBy({ it.time }, { it.ledgerId }))
        val appliedFlowSemantics = buildMap {
            for (event in events) {
                put(event.ledgerId, appliedFlowFor(event, FlowCategory.OWNER_CAPITAL))
            }
            for (norm in cardNormalizations.filterIsInstance<NormalizedFundingTransaction.OwnerFlow>()) {
                norm.sourceLedgerIds.forEach { ledgerId ->
                    val event = allRetained.first { it.ledgerId == ledgerId }
                    put(ledgerId, appliedFlowFor(event, FlowCategory.OWNER_CAPITAL, norm.refid))
                }
            }
        }
        if (events.isEmpty()) {
            return ExternalFlowCalculation(
                emptyList(),
                confirmedHorizon.epochSecond,
                skippedDecided = skippedDecided,
            )
        }

        val externalBalanceEvents = allRetained.filter {
            classifications[it.ledgerId] == FlowCategory.EXTERNAL_BALANCE &&
                it.ledgerId !in allCardSourceIds
        }
        val cardObservationEvents = allRetained.filter { it.ledgerId in allCardSourceIds }
        val candidateOwnerEvents = events.filter { it.ledgerId !in allCardPlumbingIds }

        // 2.5. Conservative overlap check: if another currently-undecided owner-capital event
        // falls strictly inside the source-time interval of an undecided card transaction:
        // min(card source timestamp) < other owner flow time < max(card source timestamp),
        // fail closed with EVENT_ORDERING_UNCERTAIN. Neither conflicting economic decision is journaled.
        for (norm in cardNormalizations.filterIsInstance<NormalizedFundingTransaction.OwnerFlow>()) {
            val cardTimes = allRetained.filter { it.ledgerId in norm.sourceLedgerIds }.map { it.time }
            val minCardTime = cardTimes.minOrNull() ?: norm.eventTime
            val maxCardTime = cardTimes.maxOrNull() ?: norm.eventTime
            if (minCardTime < maxCardTime) {
                val conflictingEvent = allRetained.firstOrNull { leg ->
                    leg.ledgerId in unappliedIds &&
                        classifications[leg.ledgerId] == FlowCategory.OWNER_CAPITAL &&
                        leg.ledgerId !in norm.sourceLedgerIds &&
                        leg.time > minCardTime &&
                        leg.time < maxCardTime
                }
                if (conflictingEvent != null) {
                    log.warn(
                        "Undecided owner flow {} at {} falls strictly inside undecided card transaction {} source span ({} to {}); failing closed",
                        conflictingEvent.ledgerId,
                        conflictingEvent.time,
                        norm.refid,
                        minCardTime,
                        maxCardTime,
                    )
                    throw AthTrustFailureException(
                        reason = AthTrustFailureReason.EVENT_ORDERING_UNCERTAIN,
                        message = "undecided owner flow ${conflictingEvent.ledgerId} at ${conflictingEvent.time} " +
                            "falls strictly inside undecided card transaction ${norm.refid} source span ($minCardTime to $maxCardTime)",
                    )
                }
            }
        }

        // 3. Extract already-decided ordinary owner flows as actual-balance context.
        // Distinguishes decision status (already applied/journaled to ATH) from whether
        // their actual balance effect is required to reconstruct historical holdings for
        // a later-discovered earlier-timestamp flow.
        val decidedJournalFlows = portfolioStatsRepository.getAppliedAthFlows(allRetained.map { it.ledgerId })
            .associateBy { it.ledgerId }
        val decidedOrdinaryOwnerEvents = mutableListOf<LedgerEvent>()
        val decidedOwnerFlowContexts = mutableListOf<ActualOwnerFlowContext>()
        for (event in allRetained) {
            if (event.ledgerId !in decidedIds || event.ledgerId in allKnownCardSourceIds) continue
            val journal = decidedJournalFlows[event.ledgerId]
            val currentCategory = classifications[event.ledgerId]
            val journalCategory = journal?.decisionCategory
            if (journalCategory == FlowCategory.OWNER_CAPITAL.name) {
                val journalAsset = journal.asset
                val journalDelta = journal.actualBalanceDelta
                if (journal.decisionVersion != 1 || journal.normalizedGroupId != null ||
                    journalAsset == null || journalDelta == null ||
                    journal.eventTimeSec != event.time.epochSecond ||
                    Asset.normalizeLedgerAsset(journalAsset).uppercase() !=
                    Asset.normalizeLedgerAsset(event.asset).uppercase() ||
                    journalDelta.compareTo(event.netBalanceDelta()) != 0
                ) {
                    unusableDecidedGroups.add(
                        unusableDecidedFlow(event, "durable ATH flow semantics disagree with ledger", journal),
                    )
                    continue
                }
                if (isInAthUniverse(Asset.normalizeLedgerAsset(journalAsset).uppercase(), universe)) {
                    decidedOrdinaryOwnerEvents.add(event)
                    decidedOwnerFlowContexts.add(
                        ActualOwnerFlowContext(
                            ledgerId = event.ledgerId,
                            timestamp = Instant.ofEpochSecond(journal.eventTimeSec),
                            asset = Asset.normalizeLedgerAsset(journalAsset).uppercase(),
                            actualBalanceDelta = journalDelta,
                            isCardRepresentative = false,
                            normalizedGroupId = journal.normalizedGroupId,
                        ),
                    )
                }
            } else if (currentCategory == FlowCategory.OWNER_CAPITAL && journalCategory == null) {
                decidedOrdinaryOwnerEvents.add(event)
                decidedOwnerFlowContexts.add(
                    ActualOwnerFlowContext(
                        ledgerId = event.ledgerId,
                        timestamp = event.time,
                        asset = Asset.normalizeLedgerAsset(event.asset).uppercase(),
                        actualBalanceDelta = event.netBalanceDelta(),
                        isCardRepresentative = false,
                        normalizedGroupId = null,
                    ),
                )
            } else if (journalCategory == null && currentCategory == FlowCategory.AMBIGUOUS) {
                unusableDecidedGroups.add(unusableDecidedFlow(event, "missing durable owner-capital semantics"))
            } else if (journalCategory != null && journalCategory !in setOf(
                    FlowCategory.EXTERNAL_BALANCE.name,
                    FlowCategory.INTERNAL_MOVE.name,
                    FlowCategory.TRADE_IGNORED.name,
                    FlowCategory.UNSUPPORTED.name,
                )
            ) {
                unusableDecidedGroups.add(unusableDecidedFlow(event, "unknown durable category '$journalCategory'"))
            }
        }

        // Sequential oldest-first adjustment: each flow scales the ATH that
        // was current just before it. Flows themselves are priced via
        // snapshots or the bounded ticker (fail-closed); each pre-flow basis
        // is reconstructed at event time (see resolveEventTimeBasis).
        val pricedFlows = candidateOwnerEvents.map { event ->
            val cardFlow = cardOwnerFlows[event.ledgerId]
            val pricedAmount = cardFlow?.netOwnerCapitalUsd
                ?: priceOwnerCapitalFlow(event, balancesObservedAt, tradesRepo)
            event to pricedAmount
        }
        val maxEventTime = pricedFlows.maxOf { (event, _) -> event.time }
        // Include snapshots saved shortly after a flow so an observation
        // boundary can distinguish a real predecessor from a row that was
        // merely saved later. resolveEventTimeBasis still refuses a future
        // save when no snapshot saved before the flow can establish the
        // pre-flow state.
        val history = tradesRepo.getSnapshotsInRange(
            Instant.EPOCH,
            maxEventTime.plusSeconds(MAX_PREDECESSOR_GAP_SECONDS),
        )
        // Successful non-dry trades between the predecessor snapshot and each
        // flow move portfolio value without being owner capital; replaying
        // them (at predecessor prices) keeps the reconstructed basis honest
        // about fee drag and inventory changes.
        val tradeHistory = tradesRepo.getTradesInRange(Instant.EPOCH, maxEventTime)
            .filter { it.success && !it.dryRun }
        val groupStarts = mutableListOf<Int>()
        var cursor = 0
        while (cursor < pricedFlows.size) {
            groupStarts.add(cursor)
            val groupTime = pricedFlows[cursor].first.time
            do {
                cursor++
            } while (cursor < pricedFlows.size && pricedFlows[cursor].first.time == groupTime)
        }
        // Economically simultaneous flows net into one scaling step. Applying
        // each against the same basis would compound them (second flow scaling
        // an ATH the first already moved); netting is order-independent and
        // exact for shared timestamps.
        val steps = groupStarts.mapIndexed { groupIndex, groupStart ->
            val groupEnd =
                if (groupIndex + 1 < groupStarts.size) groupStarts[groupIndex + 1] else pricedFlows.size
            val groupNet = pricedFlows.subList(groupStart, groupEnd)
                .fold(BigDecimal.ZERO) { acc, (_, usd) -> acc.add(usd) }
            val ledgerIds = pricedFlows.subList(groupStart, groupEnd).flatMap { (event, _) ->
                cardOwnerFlows[event.ledgerId]?.sourceLedgerIds
                    ?: listOf(event.ledgerId)
            }
            SequentialFlowStep(
                ledgerIds = ledgerIds,
                eventTime = pricedFlows[groupStart].first.time,
                flowUSD = groupNet.setScale(PrecisionConstants.SCALE_USD, RoundingMode.HALF_UP),
            )
        }
        return ExternalFlowCalculation(
            sequentialFlows = steps,
            pendingWatermarkSec = confirmedHorizon.epochSecond,
            skippedDecided = skippedDecided,
            appliedFlowSemantics = appliedFlowSemantics,
            groupBasisResolver = GroupBasisResolver(
                resolve = { eventTime, priorFlows, snapHistory, snapTrades, currentFlowRepresentativeIds ->
                    resolveEventTimeBasis(
                        eventTime = eventTime,
                        priorFlows = priorFlows,
                        decidedOwnerFlows = decidedOwnerFlowContexts,
                        decidedOwnerLedgerEvents = decidedOrdinaryOwnerEvents,
                        unusableDecidedGroups = unusableDecidedGroups,
                        history = snapHistory,
                        trades = snapTrades,
                        externalBalances = externalBalanceEvents,
                        cardActualFlows = cardActualFlows,
                        cardObservationEvents = cardObservationEvents,
                        currentFlowRepresentativeIds = currentFlowRepresentativeIds,
                        balancesObservedAt = balancesObservedAt,
                        tradesRepo = tradesRepo,
                    )
                },
                pricedFlows,
                groupStarts,
                history,
                tradeHistory,
            ),
        )
    }

    /**
     * Resolves pre-flow bases lazily per timestamp group. Groups after an
     * early break are never priced, so an unbasis-able later flow cannot fail
     * a cycle whose applicable prefix is fine.
     */
    private class GroupBasisResolver(
        private val resolve: suspend (
            eventTime: Instant,
            priorFlows: List<Pair<LedgerEvent, BigDecimal>>,
            history: List<PortfolioSnapshot>,
            trades: List<TradeRecord>,
            currentFlowRepresentativeIds: Set<String>,
        ) -> BigDecimal?,
        private val pricedFlows: List<Pair<LedgerEvent, BigDecimal>>,
        private val groupStarts: List<Int>,
        private val history: List<PortfolioSnapshot>,
        private val trades: List<TradeRecord>,
    ) {
        suspend fun basisFor(groupIndex: Int): BigDecimal? {
            val start = groupStarts[groupIndex]
            val end = if (groupIndex + 1 < groupStarts.size) groupStarts[groupIndex + 1] else pricedFlows.size
            return resolve(
                pricedFlows[start].first.time,
                pricedFlows.subList(0, start),
                history,
                trades,
                pricedFlows.subList(start, end).mapTo(mutableSetOf()) { it.first.ledgerId },
            )
        }
    }

    /**
     * Best defensible portfolio value immediately BEFORE an owner flow:
     * reconstructs the tracked portfolio holdings immediately before the flow
     * (starting from the predecessor snapshot's asset balances, replaying intervening
     * trades and prior flows), then values them at flow-time historical prices.
     *
     * This properly accounts for market price movement between the predecessor snapshot
     * and the flow event. Returns null when no predecessor snapshot exists (baked into
     * initial baseline). Fails closed (via Deferred state) if flow-time prices cannot
     * be resolved or if the predecessor snapshot is older than [MAX_PREDECESSOR_GAP_SECONDS].
     */
    private suspend fun resolveEventTimeBasis(
        eventTime: Instant,
        priorFlows: List<Pair<LedgerEvent, BigDecimal>>,
        decidedOwnerFlows: List<ActualOwnerFlowContext>,
        decidedOwnerLedgerEvents: List<LedgerEvent>,
        unusableDecidedGroups: List<UnusableDecidedFundingContext>,
        history: List<PortfolioSnapshot>,
        trades: List<TradeRecord>,
        externalBalances: List<LedgerEvent>,
        cardActualFlows: List<CardActualFlow>,
        cardObservationEvents: List<LedgerEvent>,
        currentFlowRepresentativeIds: Set<String>,
        balancesObservedAt: Instant?,
        tradesRepo: TradeRepository,
    ): BigDecimal? {
        // `timestamp` is when the snapshot was saved; `balancesObservedAt` is
        // the request-start lower bound for the balance state it contains.
        // Rows between those instants cannot be assigned from timestamps alone.
        // Legacy rows retain the conservative old boundary (their save time).
        fun observationBoundary(snapshot: PortfolioSnapshot): Instant =
            snapshot.balancesObservedAt ?: snapshot.timestamp

        val observationEligible = history.filter { !observationBoundary(it).isAfter(eventTime) }
        val predecessor = observationEligible
            .filter { !it.timestamp.isAfter(eventTime) }
            .maxByOrNull { it.timestamp }
            ?: if (observationEligible.any { it.timestamp.isAfter(eventTime) }) {
                throw AthTrustFailureException(
                    reason = AthTrustFailureReason.PRE_FLOW_BASIS_UNCERTAIN,
                    message = "all snapshots covering the observation boundary were saved after flow at $eventTime; " +
                        "pre-flow state is uncertain",
                )
            } else {
                // No retained snapshot has an observation boundary before the
                // event. This is the existing initial-baseline case.
                return null
            }
        val predecessorObservationBoundary = predecessor.balancesObservedAt ?: predecessor.timestamp
        val predecessorGap = Duration.between(predecessorObservationBoundary, eventTime)
        val gapSeconds = predecessorGap.seconds
        if (predecessorGap > Duration.ofSeconds(MAX_PREDECESSOR_GAP_SECONDS)) {
            throw AthTrustFailureException(
                reason = AthTrustFailureReason.PRE_FLOW_BASIS_UNCERTAIN,
                message = "Predecessor observation at $predecessorObservationBoundary is older than maximum " +
                    "allowed gap (${MAX_PREDECESSOR_GAP_SECONDS}s; gap was ${gapSeconds}s) for event at $eventTime",
            )
        }

        // The current group is about to be applied as a synthetic owner-capital
        // flow, and eligible card flows are replayed through their actual per-leg
        // timed deltas below.
        val currentFlowCardSourceIds = cardActualFlows
            .filter { it.representativeLedgerId in currentFlowRepresentativeIds }
            .flatMapTo(mutableSetOf()) { it.sourceLedgerIds }
        val eligibleCardFlows = cardActualFlows.filter {
            it.representativeLedgerId !in currentFlowRepresentativeIds
        }
        val eligibleCardSourceIds = eligibleCardFlows.flatMapTo(mutableSetOf()) { it.sourceLedgerIds }
        val eligibleCardObservationEvents = cardObservationEvents.filter {
            it.ledgerId !in currentFlowCardSourceIds
        }

        // A persisted timestamp does not establish whether a trade or a
        // performance ledger row happened before or after the owner flow
        // when the records are simultaneous or within exchange-clock skew.
        // Do not impose an arbitrary lexical order on money-moving events;
        // the next cycle can retry once a more precise source or balance
        // boundary is available.
        if (externalBalances.any { isNearEventTime(it.time, eventTime) } ||
            trades.any { isNearEventTime(it.timestamp, eventTime) } ||
            eligibleCardFlows.any { flow ->
                val times = flow.actualPortfolioDeltas.map { it.timestamp }
                    .ifEmpty { flow.sourceTimes.ifEmpty { listOf(flow.eventTime) } }
                times.any { isNearEventTime(it, eventTime) }
            } ||
            decidedOwnerFlows.any { isNearEventTime(it.timestamp, eventTime) }
        ) {
            throw AthTrustFailureException(
                reason = AthTrustFailureReason.EVENT_ORDERING_UNCERTAIN,
                message = "cannot establish ordering of a near-instant performance event or decided flow and " +
                    "owner flow at $eventTime",
            )
        }

        // 1. Reconstruct tracked holdings immediately before the flow
        val reconstructedHoldings = mutableMapOf<String, BigDecimal>()
        if (predecessor.assets.isEmpty()) {
            if (predecessor.totalValueUSD > BigDecimal.ZERO) {
                reconstructedHoldings["USD"] = predecessor.totalValueUSD
            }
        } else {
            for ((asset, assetSnap) in predecessor.assets) {
                reconstructedHoldings[Asset.normalizeLedgerAsset(asset).uppercase()] = assetSnap.balance
            }
            if (!reconstructedHoldings.containsKey("USD") && !reconstructedHoldings.containsKey("ZUSD")) {
                val nonUsdTotal = predecessor.assets.values.sumOf { it.valueUSD }
                val residualFiat = predecessor.totalValueUSD.subtract(nonUsdTotal)
                if (residualFiat > BigDecimal.ZERO) {
                    reconstructedHoldings["USD"] = residualFiat
                }
            }
        }

        // Replay intervening trades between predecessor snapshot and flow time
        val universe = configService.getConfig().allocations.map { it.symbol.value.uppercase() }.toSet()
        val uniqueInterveningLedgerEvents =
            (eligibleCardObservationEvents + externalBalances + decidedOwnerLedgerEvents + priorFlows.map { it.first })
                .filter { ledger ->
                    isInAthUniverse(Asset.normalizeLedgerAsset(ledger.asset).uppercase(), universe) &&
                        !ledger.time.isAfter(eventTime)
                }
                .groupBy { it.ledgerId }
                .values
                .map { sameId ->
                    if (sameId.distinct().size != 1) {
                        throw AthTrustFailureException(
                            reason = AthTrustFailureReason.BALANCE_OBSERVATION_UNCERTAIN,
                            message = "conflicting ledger rows for ${sameId.first().ledgerId}",
                        )
                    }
                    sameId.first()
                }
        val uncertainLedgerEvents = uniqueInterveningLedgerEvents.filter { ledger ->
            ledger.time.isAfter(predecessorObservationBoundary) &&
                !ledger.time.isAfter(predecessor.timestamp)
        }
        val embeddedLedgerIds = resolveEmbeddedLedgerIds(uncertainLedgerEvents, predecessor)

        // 1.5. Evaluate unusable decided funding context relative to the reconstruction interval:
        // (predecessor actual-state boundary, target owner-flow time]
        for (unusable in unusableDecidedGroups) {
            if (unusable.minTime.isAfter(eventTime)) continue
            if (!unusable.maxTime.isAfter(predecessorObservationBoundary)) continue

            log.warn(
                "Unusable decided funding context {} intersects reconstruction interval ({} to {}): {}",
                unusable.refid,
                predecessorObservationBoundary,
                eventTime,
                unusable.reason,
            )
            throw AthTrustFailureException(
                reason = AthTrustFailureReason.PRE_FLOW_BASIS_UNCERTAIN,
                message = "unusable decided funding context ${unusable.refid} intersects " +
                    "reconstruction interval: ${unusable.reason}",
            )
        }

        val uncertainTrades = trades.filter { trade ->
            trade.timestamp.isAfter(predecessorObservationBoundary) &&
                !trade.timestamp.isAfter(predecessor.timestamp) &&
                !trade.timestamp.isAfter(eventTime)
        }
        if (uncertainTrades.isNotEmpty()) {
            throw AthTrustFailureException(
                reason = AthTrustFailureReason.BALANCE_OBSERVATION_UNCERTAIN,
                message = "trade state falls inside the uncertain balance-observation interval before $eventTime",
            )
        }
        for (trade in trades) {
            if (trade.timestamp.isAfter(predecessor.timestamp) && !trade.timestamp.isAfter(eventTime)) {
                val asset = Asset.normalizeLedgerAsset(trade.symbol).uppercase()
                val isTrackedCrypto = isTrackedCrypto(asset, universe)
                if (OrderSide.isBuy(trade.side)) {
                    if (isTrackedCrypto) {
                        reconstructedHoldings[asset] =
                            (reconstructedHoldings[asset] ?: BigDecimal.ZERO).add(trade.volume)
                    }
                    reconstructedHoldings["USD"] = (reconstructedHoldings["USD"] ?: BigDecimal.ZERO)
                        .subtract(trade.usdAmount).subtract(trade.fee)
                } else {
                    if (isTrackedCrypto) {
                        reconstructedHoldings[asset] =
                            (reconstructedHoldings[asset] ?: BigDecimal.ZERO).subtract(trade.volume)
                    }
                    reconstructedHoldings["USD"] = (reconstructedHoldings["USD"] ?: BigDecimal.ZERO)
                        .add(trade.usdAmount).subtract(trade.fee)
                }
            }
        }

        // Replay intervening external balance events (staking, dividends, adjustments, spend/receive, etc.)
        for (extBal in externalBalances) {
            val replayAfterSnapshot = extBal.time.isAfter(predecessor.timestamp) && !extBal.time.isAfter(eventTime)
            val replayAfterObservation = extBal.ledgerId !in embeddedLedgerIds &&
                extBal.time.isAfter(predecessorObservationBoundary) &&
                !extBal.time.isAfter(predecessor.timestamp) &&
                !extBal.time.isAfter(eventTime)
            if (replayAfterSnapshot || replayAfterObservation) {
                val asset = Asset.normalizeLedgerAsset(extBal.asset).uppercase()
                if (isInAthUniverse(asset, universe)) {
                    reconstructedHoldings[asset] =
                        (reconstructedHoldings[asset] ?: BigDecimal.ZERO).add(extBal.netBalanceDelta())
                }
            }
        }

        // Replay intervening ordinary owner flows (both already-decided and current-batch prior flows).
        // Card representatives and card plumbing are excluded here and replayed exclusively
        // through their actual per-leg TimedAssetDelta list below.
        val priorOwnerFlowContexts = priorFlows.map { (event, _) ->
            val isCardRep = cardActualFlows.any { it.representativeLedgerId == event.ledgerId }
            ActualOwnerFlowContext(
                ledgerId = event.ledgerId,
                timestamp = event.time,
                asset = Asset.normalizeLedgerAsset(event.asset).uppercase(),
                actualBalanceDelta = event.netBalanceDelta(),
                isCardRepresentative = isCardRep,
                normalizedGroupId = null,
            )
        }
        val allHistoricalOwnerFlows = decidedOwnerFlows + priorOwnerFlowContexts
        for (flow in allHistoricalOwnerFlows) {
            if (flow.isCardRepresentative || flow.ledgerId in eligibleCardSourceIds) continue
            val replayAfterSnapshot =
                flow.timestamp.isAfter(predecessor.timestamp) && !flow.timestamp.isAfter(eventTime)
            val replayAfterObservation = flow.ledgerId !in embeddedLedgerIds &&
                flow.timestamp.isAfter(predecessorObservationBoundary) &&
                !flow.timestamp.isAfter(predecessor.timestamp)
            if (replayAfterSnapshot || replayAfterObservation) {
                val asset = Asset.normalizeLedgerAsset(flow.asset).uppercase()
                if (isInAthUniverse(asset, universe)) {
                    reconstructedHoldings[asset] =
                        (reconstructedHoldings[asset] ?: BigDecimal.ZERO).add(flow.actualBalanceDelta)
                }
            }
        }

        // Replay completed card groups through their actual per-leg timed effects,
        // never through the representative USD deposit. This preserves the
        // bought asset and each fee exactly once when a later owner flow needs
        // a pre-flow basis, while preventing temporal look-ahead of future legs.
        for (cardFlow in eligibleCardFlows) {
            val sourceTimes = cardFlow.sourceTimes.ifEmpty { listOf(cardFlow.eventTime) }
            val allBeforeOrAtSnapshot = sourceTimes.all { !it.isAfter(predecessor.timestamp) }
            val allAfterSnapshot = sourceTimes.all { it.isAfter(predecessor.timestamp) }
            val allBeforeOrAtObservation = sourceTimes.all { !it.isAfter(predecessorObservationBoundary) }
            val allAfterObservation = sourceTimes.all { it.isAfter(predecessorObservationBoundary) }

            // A predecessor saved in the middle of a multi-leg card group
            // cannot establish whether the snapshot already contains the
            // whole group. Do not guess and risk double-counting a fee or
            // asset conversion.
            if (!allBeforeOrAtSnapshot && !allAfterSnapshot) {
                throw AthTrustFailureException(
                    reason = AthTrustFailureReason.BALANCE_OBSERVATION_UNCERTAIN,
                    message = "card funding group ${cardFlow.representativeLedgerId} straddles predecessor snapshot",
                )
            }
            if (!allBeforeOrAtObservation && !allAfterObservation &&
                cardFlow.sourceLedgerIds.none { it in embeddedLedgerIds }
            ) {
                throw AthTrustFailureException(
                    reason = AthTrustFailureReason.BALANCE_OBSERVATION_UNCERTAIN,
                    message = "card funding group ${cardFlow.representativeLedgerId} straddles balance observation",
                )
            }

            for (delta in cardFlow.actualPortfolioDeltas) {
                // Future deltas are never replayed into earlier pre-flow portfolio bases
                if (delta.timestamp.isAfter(eventTime)) continue

                val replayAfterSnapshot = delta.timestamp.isAfter(predecessor.timestamp)
                val replayAfterObservation = delta.ledgerId !in embeddedLedgerIds &&
                    delta.timestamp.isAfter(predecessorObservationBoundary) &&
                    !delta.timestamp.isAfter(predecessor.timestamp)
                if (replayAfterSnapshot || replayAfterObservation) {
                    val asset = Asset.normalizeLedgerAsset(delta.asset).uppercase()
                    if (isInAthUniverse(asset, universe)) {
                        reconstructedHoldings[asset] =
                            (reconstructedHoldings[asset] ?: BigDecimal.ZERO).add(delta.amount)
                    }
                }
            }
        }

        // 2. Value reconstructed holdings at flow-time prices
        var totalUSD = BigDecimal.ZERO
        for ((asset, balance) in reconstructedHoldings) {
            if (balance.compareTo(BigDecimal.ZERO) == 0) continue
            if (asset == "USD" || asset == "ZUSD") {
                totalUSD = totalUSD.add(balance)
                continue
            }
            val price = resolvePriceForEvent(asset, eventTime, balancesObservedAt, tradesRepo)
            totalUSD = totalUSD.add(balance.multiply(price))
        }

        val basis = totalUSD.setScale(PrecisionConstants.SCALE_USD, RoundingMode.HALF_UP)
        if (basis > BigDecimal.ZERO) {
            log.info(
                "ATH flow basis reconstructed from holdings at {} with flow-time prices (gap {}s) for event at {}: basis={}",
                predecessor.timestamp,
                gapSeconds,
                eventTime,
                basis,
            )
            return basis
        }
        throw AthTrustFailureException(
            reason = AthTrustFailureReason.PRE_FLOW_BASIS_UNCERTAIN,
            message = "Cannot establish a positive pre-flow portfolio basis for event at $eventTime: basis=$basis",
        )
    }

    /**
     * Assigns ledger rows that fall between a balance request start and the
     * later snapshot timestamp. An authoritative post-event balance permits a
     * unique prefix/suffix split; every other case is ambiguous and must defer
     * the ATH update rather than double-count or drop the row.
     */
    private fun resolveEmbeddedLedgerIds(
        uncertainEvents: List<LedgerEvent>,
        predecessor: PortfolioSnapshot,
    ): Set<String> {
        if (uncertainEvents.isEmpty()) return emptySet()
        val embeddedIds = mutableSetOf<String>()
        for ((asset, events) in uncertainEvents.groupBy {
            Asset.normalizeLedgerAsset(it.asset).uppercase()
        }) {
            val ordered = events.sortedWith(compareBy({ it.time }, { it.ledgerId }))
            if (ordered.zipWithNext().any { (first, second) -> first.time == second.time }) {
                throw AthTrustFailureException(
                    reason = AthTrustFailureReason.BALANCE_OBSERVATION_UNCERTAIN,
                    message = "cannot order same-timestamp ledger rows in uncertain balance-observation interval " +
                        "for $asset",
                )
            }
            if (ordered.any { !it.hasAuthoritativeBalance }) {
                throw AthTrustFailureException(
                    reason = AthTrustFailureReason.BALANCE_OBSERVATION_UNCERTAIN,
                    message = "ledger row in uncertain balance-observation interval has no authoritative balance: " +
                        ordered.first { !it.hasAuthoritativeBalance }.ledgerId,
                )
            }
            val inconsistentChain = ordered.zipWithNext().firstOrNull { (previous, current) ->
                previous.balance
                    .add(current.netBalanceDelta())
                    .subtract(current.balance)
                    .abs()
                    .compareTo(INTERVENING_BALANCE_TOLERANCE) > 0
            }
            if (inconsistentChain != null) {
                throw AthTrustFailureException(
                    reason = AthTrustFailureReason.BALANCE_OBSERVATION_UNCERTAIN,
                    message = "ledger balance chain is inconsistent between " +
                        "${inconsistentChain.first.ledgerId} and ${inconsistentChain.second.ledgerId} for $asset",
                )
            }
            val observedBalance = predecessor.assets.entries.firstOrNull {
                Asset.normalizeLedgerAsset(it.key).uppercase() == asset
            }?.value?.balance ?: BigDecimal.ZERO
            val possibleCutoffs = (0..ordered.size).filter { cutoff ->
                val expected = if (cutoff == 0) {
                    ordered.first().balance.subtract(ordered.first().netBalanceDelta())
                } else {
                    ordered[cutoff - 1].balance
                }
                expected.subtract(observedBalance).abs() <= INTERVENING_BALANCE_TOLERANCE
            }
            if (possibleCutoffs.size != 1) {
                throw AthTrustFailureException(
                    reason = AthTrustFailureReason.BALANCE_OBSERVATION_UNCERTAIN,
                    message = "cannot uniquely assign ${ordered.size} ledger row(s) in uncertain " +
                        "balance-observation interval for $asset",
                )
            }
            embeddedIds += ordered.take(possibleCutoffs.single()).map { it.ledgerId }
        }
        return embeddedIds
    }

    private fun isTrackedCrypto(normalizedAsset: String, universe: Set<String>): Boolean =
        normalizedAsset != "USD" && normalizedAsset != "ZUSD" &&
            (universe.isEmpty() || universe.contains(normalizedAsset))

    private fun isInAthUniverse(normalizedAsset: String, universe: Set<String>): Boolean =
        normalizedAsset == "USD" || normalizedAsset == "ZUSD" || isTrackedCrypto(normalizedAsset, universe)

    private fun unusableDecidedFlow(
        event: LedgerEvent,
        reason: String,
        journal: AppliedAthFlow? = null,
    ): UnusableDecidedFundingContext {
        val journalTime = journal?.let { Instant.ofEpochSecond(it.eventTimeSec) } ?: event.time
        return UnusableDecidedFundingContext(
            refid = event.refid ?: event.ledgerId,
            sourceLedgerIds = setOf(event.ledgerId),
            minTime = minOf(event.time, journalTime),
            maxTime = maxOf(event.time, journalTime),
            reason = reason,
        )
    }

    private fun appliedFlowFor(
        event: LedgerEvent,
        category: FlowCategory,
        normalizedGroupId: String? = null,
    ): AppliedAthFlow = AppliedAthFlow(
        ledgerId = event.ledgerId,
        eventTimeSec = event.time.epochSecond,
        decisionCategory = category.name,
        asset = Asset.normalizeLedgerAsset(event.asset).uppercase(),
        actualBalanceDelta = event.netBalanceDelta(),
        normalizedGroupId = normalizedGroupId,
        decisionVersion = 1,
    )

    internal fun isLinkedPassthroughLeg(event: LedgerEvent, allRetained: List<LedgerEvent>): Boolean {
        if (!CardFundingNormalizer.isPassthroughLeg(event)) return false
        val refid = event.refid?.trim()?.takeIf(String::isNotEmpty) ?: return false
        val group = allRetained.filter { it.refid?.trim() == refid }
        return group.any(CardFundingNormalizer::isFundingLeg)
    }

    internal suspend fun priceOwnerCapitalFlow(
        event: LedgerEvent,
        balancesObservedAt: Instant?,
        tradesRepo: TradeRepository,
        allRetained: List<LedgerEvent> = emptyList(),
    ): BigDecimal {
        val delta = event.netBalanceDelta()
        val asset = Asset.normalizeLedgerAsset(event.asset).uppercase()
        if (asset == "USD" || asset == "ZUSD") {
            if (!event.refid.isNullOrBlank() && allRetained.isNotEmpty()) {
                val group = allRetained.filter { it.refid?.trim() == event.refid.trim() }
                val hasPassthrough = group.any(CardFundingNormalizer::isPassthroughLeg)
                if (hasPassthrough) {
                    val fundingLegs = group.filter(CardFundingNormalizer::isFundingLeg)
                    val isRepresentative = fundingLegs.minWithOrNull(
                        compareBy<LedgerEvent>({ it.time }, { it.ledgerId }),
                    )?.ledgerId == event.ledgerId
                    if (isRepresentative) {
                        val nonFundingFees = group.filter(CardFundingNormalizer::isPassthroughLeg)
                            .fold(BigDecimal.ZERO) { acc, leg -> acc.add(leg.fee) }
                        return if (delta.signum() > 0) {
                            delta.subtract(nonFundingFees)
                        } else {
                            delta.add(nonFundingFees)
                        }
                    }
                }
            }
            return delta
        }
        val historicalPrice = resolvePriceForEvent(asset, event.time, balancesObservedAt, tradesRepo)
        return delta.multiply(historicalPrice)
    }

    private suspend fun resolvePriceForEvent(
        asset: String,
        eventTime: Instant,
        balancesObservedAt: Instant?,
        tradesRepo: TradeRepository,
    ): BigDecimal {
        val historicalPrice = resolveHistoricalPrice(asset, eventTime, tradesRepo)
        if (historicalPrice != null && historicalPrice > BigDecimal.ZERO) {
            return historicalPrice
        }

        // Bounded fallback to exchange ticker: live prices only proxy
        // historical cost when the event is near-real-time (<= 300s).
        val referenceTime = balancesObservedAt ?: nowProvider()
        val eventAgeMillis = kotlin.math.abs(referenceTime.toEpochMilli() - eventTime.toEpochMilli())
        val eventAgeSeconds = eventAgeMillis / 1000L
        if (eventAgeMillis <= MAX_NEAR_REALTIME_TICKER_WINDOW_SECONDS * 1000L) {
            try {
                val pair = Asset(asset).tradingPair
                val raw = krakenService.getTickerPrices(pair)
                val price = resolvePriceFromTicker(asset, raw)
                if (price > BigDecimal.ZERO) {
                    return price
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Failed to fetch ticker price for asset {} at {}: {}", asset, eventTime, e.message)
            }
        } else {
            log.warn(
                "Skipping live-ticker fallback for stale flow: asset {} at {} (age {}s > {}s)",
                asset,
                eventTime,
                eventAgeSeconds,
                MAX_NEAR_REALTIME_TICKER_WINDOW_SECONDS,
            )
        }

        throw AthTrustFailureException(
            reason = AthTrustFailureReason.HISTORICAL_PRICE_UNAVAILABLE,
            message = "Cannot reliably price external capital flow for asset $asset at $eventTime " +
                "(age ${eventAgeSeconds}s exceeds ${MAX_NEAR_REALTIME_TICKER_WINDOW_SECONDS}s and no historical " +
                "trade/snapshot/OHLC price found)",
        )
    }

    private suspend fun resolveHistoricalPrice(
        asset: String,
        eventTime: Instant,
        tradesRepo: TradeRepository,
    ): BigDecimal? {
        // 1. If a trade occurred at or near flow time (within +/- 180 seconds), use the trade execution price
        val recentTradeStart = eventTime.minusSeconds(180)
        val recentTrade = tradesRepo.getTradesInRange(recentTradeStart, eventTime.plusSeconds(180))
            .filter {
                it.success &&
                    !it.dryRun &&
                    !it.timestamp.isBefore(recentTradeStart) &&
                    !it.timestamp.isAfter(eventTime) &&
                    Asset.normalizeLedgerAsset(it.symbol).equals(asset, ignoreCase = true)
            }
            .minByOrNull { kotlin.math.abs(it.timestamp.toEpochMilli() - eventTime.toEpochMilli()) }
        if (recentTrade != null && recentTrade.volume > BigDecimal.ZERO && recentTrade.usdAmount > BigDecimal.ZERO) {
            return recentTrade.usdAmount.divide(
                recentTrade.volume,
                PrecisionConstants.SCALE_CRYPTO,
                RoundingMode.HALF_UP,
            )
        }

        // 2. Look for closest recorded portfolio snapshot within +/- 180 seconds.
        val recentSnapshotStart = eventTime.minusSeconds(180)
        val nearestSnap = tradesRepo.getSnapshotsInRange(
            recentSnapshotStart,
            eventTime,
        ).filter {
            !it.timestamp.isBefore(recentSnapshotStart) &&
                !it.timestamp.isAfter(eventTime) &&
                !(it.balancesObservedAt ?: it.timestamp).isAfter(eventTime)
        }.minByOrNull { kotlin.math.abs(it.timestamp.toEpochMilli() - eventTime.toEpochMilli()) }

        val snapPrice = nearestSnap?.assets?.get(asset)?.price
        if (snapPrice != null && snapPrice > BigDecimal.ZERO) {
            return snapPrice
        }

        // 3. Use only a completed intraday candle. Selecting a daily candle
        // whose start is before the event would otherwise use that candle's
        // close, which includes price movement after the funding event.
        try {
            val pair = Asset(asset).tradingPair
            val sinceSec = eventTime.minusSeconds(86400).epochSecond
            val candles = krakenService.getOHLC(
                pair,
                interval = HISTORICAL_OHLC_INTERVAL_MINUTES,
                since = sinceSec,
            )
            val candleDurationSeconds = HISTORICAL_OHLC_INTERVAL_MINUTES * 60L
            val earliestCandleStart = eventTime.minusSeconds(86400)
            val matched = candles.filter {
                val candleStart = Instant.ofEpochSecond(it.first)
                !candleStart.isBefore(earliestCandleStart) &&
                    candleStart.plusSeconds(candleDurationSeconds) <= eventTime
            }
                .maxByOrNull { it.first }
            if (matched != null && matched.second > BigDecimal.ZERO) {
                return matched.second
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Failed to fetch OHLC price for asset {} at {}: {}", asset, eventTime, e.message)
        }

        return null
    }

    override fun calculateFiatDeployment(drawdownPct: BigDecimal, settings: Settings): BigDecimal =
        RebalancerEngine.calculateFiatDeployment(drawdownPct, settings)

    override fun calculateEffectiveUsdTarget(fiatDeploymentPct: BigDecimal): BigDecimal =
        RebalancerEngine.calculateEffectiveUsdTarget(fiatDeploymentPct, configService.getConfig().allocations)

    override fun calculateCryptoScaleFactor(effectiveUsdTarget: BigDecimal): BigDecimal =
        RebalancerEngine.calculateCryptoScaleFactor(effectiveUsdTarget, configService.getConfig().allocations)

    override fun analyzeDeviations(
        totalPortfolioValueUSD: BigDecimal,
        currentValuesUSD: AssetValues,
        effectiveUsdTarget: BigDecimal,
        cryptoScaleFactor: BigDecimal,
    ): RebalancePlan {
        val config = configService.getConfig()
        return RebalancerEngine.analyzeDeviationsPlan(
            totalPortfolioValueUSD = totalPortfolioValueUSD,
            currentValuesUSD = currentValuesUSD,
            effectiveUsdTarget = effectiveUsdTarget,
            cryptoScaleFactor = cryptoScaleFactor,
            allocations = config.allocations,
            settings = config.settings,
        )
    }

    override fun buildSnapshot(
        balances: RawBalances,
        prices: AssetPrices,
        currentValuesUSD: AssetValues,
        totalPortfolioValueUSD: BigDecimal,
        effectiveUsdTarget: BigDecimal,
        cryptoScaleFactor: BigDecimal,
        drawdownPct: BigDecimal,
        fiatDeploymentPct: BigDecimal,
        actionLog: List<String>,
        balancesObservedAt: Instant,
    ): PortfolioSnapshot {
        val assetSnapshots = mutableMapOf<String, PortfolioSnapshot.AssetSnapshot>()
        val config = configService.getConfig()
        val settings = config.settings

        for ((symbol, targetPercent) in config.allocations) {
            val balance = resolveBalance(symbol = symbol.value, balances = balances)
            val valUSD = currentValuesUSD[symbol.value] ?: BigDecimal.ZERO
            val price =
                if (symbol.isUsd) {
                    BigDecimal.ONE
                } else {
                    prices[symbol.value] ?: error("Unresolved price for ${symbol.value}")
                }

            val metrics =
                PortfolioCalculations.calculateAssetMetrics(
                    symbol = symbol,
                    baseTargetPercent = BigDecimal.valueOf(targetPercent),
                    currentValueUSD = valUSD,
                    totalPortfolioValueUSD = totalPortfolioValueUSD,
                    effectiveUsdTarget = effectiveUsdTarget,
                    cryptoScaleFactor = cryptoScaleFactor,
                    minimumOrderSizeUSD = settings.minimumOrderSizeUSD,
                )

            assetSnapshots[symbol.value] =
                PortfolioCalculations.createAssetSnapshot(
                    symbol = symbol.value,
                    balance = balance,
                    price = price,
                    valueUSD = valUSD,
                    metrics = metrics,
                )
        }

        return PortfolioSnapshot(
            timestamp = Instant.now(),
            totalValueUSD = totalPortfolioValueUSD,
            assets = assetSnapshots,
            actions = actionLog,
            drawdownPercent = drawdownPct,
            fiatDeploymentPercent = fiatDeploymentPct,
            effectiveUsdTargetPercent = effectiveUsdTarget,
            balancesObservedAt = balancesObservedAt,
        )
    }

    companion object {
        const val MAX_PREDECESSOR_GAP_SECONDS = 7L * 86400L
        const val MAX_NEAR_REALTIME_TICKER_WINDOW_SECONDS = 300L
        const val HISTORICAL_OHLC_INTERVAL_MINUTES = 15
        private const val MAX_EVENT_ORDERING_SKEW_MILLIS = 1_000L
        private val INTERVENING_BALANCE_TOLERANCE = BigDecimal("0.00000001")

        private fun isNearEventTime(first: Instant, second: Instant): Boolean =
            kotlin.math.abs(first.toEpochMilli() - second.toEpochMilli()) < MAX_EVENT_ORDERING_SKEW_MILLIS

        /**
         * Placeholder resolver: flow-less calculations never resolve a basis,
         * so this instance is never invoked.
         */
        private fun noFlowsResolver(): GroupBasisResolver = GroupBasisResolver(
            resolve = { _, _, _, _, _ -> error("Basis resolution without flows") },
            pricedFlows = emptyList(),
            groupStarts = emptyList(),
            history = emptyList(),
            trades = emptyList(),
        )
    }
}
