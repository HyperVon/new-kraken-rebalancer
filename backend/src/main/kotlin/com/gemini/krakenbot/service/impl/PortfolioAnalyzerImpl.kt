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
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.FlowCategory
import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.model.LedgerFlowClassifier
import com.gemini.krakenbot.model.OrderSide
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.Result
import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.repository.AppliedAthFlow
import com.gemini.krakenbot.repository.LedgerRepository
import com.gemini.krakenbot.repository.PortfolioStatsRepository
import com.gemini.krakenbot.repository.TradeRepository
import com.gemini.krakenbot.service.AthUpdateResult
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.service.ObservedBalances
import com.gemini.krakenbot.service.PortfolioAnalyzer
import com.gemini.krakenbot.util.PrecisionConstants
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import com.gemini.krakenbot.domain.resolveBalance as resolveBalanceFromKeys

class PortfolioAnalyzerImpl(
    private val krakenService: KrakenService,
    private val configService: ConfigService,
    private val portfolioStatsRepository: PortfolioStatsRepository,
    private val nowProvider: () -> Instant = Instant::now,
    private val ledgerRepository: LedgerRepository? = null,
    private val tradeRepository: TradeRepository? = null,
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
                    calculateUnappliedExternalFlow(balancesObservedAt)
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
                        throw IllegalStateException(
                            "ledger coverage unknown for balances observed at $balancesObservedAt",
                        )
                    }
                    // Mirror of the ath>0 temporal gate: a balance observed
                    // past coverage may contain owner capital the ledger has
                    // not confirmed, so no baseline may be established from it.
                    if (coverage != null &&
                        balancesObservedAt != null &&
                        balancesObservedAt.epochSecond > coverage
                    ) {
                        throw IllegalStateException(
                            "ledger coverage $coverage predates balances observed at $balancesObservedAt",
                        )
                    }
                    // The initial ATH is the current total, which already
                    // contains every flow up to the observation and nothing
                    // the ledger has not confirmed: absorb to the earlier of
                    // coverage and the observation. Rows above the observation
                    // are not in the baseline and scale on later cycles.
                    val absorbHorizonSec = coverage?.let { minOf(it, balancesObservedAt?.epochSecond ?: it) }
                    if (absorbHorizonSec != null) {
                        appliedFlows.addAll(absorbUnappliedFlowsIntoInitialAth(Instant.ofEpochSecond(absorbHorizonSec)))
                    }
                    // Hold the watermark at the absorb horizon: rows above it
                    // were not in the baseline, and a later one-time migration
                    // must never presume them decided.
                    ExternalFlowCalculation(emptyList(), absorbHorizonSec)
                }
            } catch (e: CancellationException) {
                // Coroutine cancellation is an IllegalStateException subtype:
                // it must propagate, never degrade into a deferral.
                throw e
            } catch (e: IllegalStateException) {
                log.warn("Deferring ATH update: owner-capital flow cannot be valued reliably ({})", e.message)
                return AthUpdateResult.Deferred(stats.lastTrustedDrawdownPct)
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
                return AthUpdateResult.Deferred(stats.lastTrustedDrawdownPct)
            }
            var brokeEarly = false
            // Consciously-skipped decisions (ambiguous, unsupported, off-universe)
            // are terminal regardless of what the scaling loop below does, so they
            // join the journal now and are never re-warned.
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
                            AppliedAthFlow(
                                ledgerId = ledgerId,
                                eventTimeSec = step.eventTime.epochSecond,
                            ),
                        )
                    }
                    continue
                }
                // Fail-closed: an unbasis-able group defers the whole update
                // with no writes (the checkpoint below is skipped), so the
                // prefix is retried verbatim next cycle.
                val basis = try {
                    flowCalc.groupBasisResolver.basisFor(groupIndex)
                } catch (e: IllegalStateException) {
                    log.warn(
                        "Deferring ATH update: pre-flow basis cannot be established reliably ({})",
                        e.message,
                    )
                    return AthUpdateResult.Deferred(stats.lastTrustedDrawdownPct)
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
                            AppliedAthFlow(
                                ledgerId = ledgerId,
                                eventTimeSec = step.eventTime.epochSecond,
                            ),
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
                        AppliedAthFlow(
                            ledgerId = ledgerId,
                            eventTimeSec = step.eventTime.epochSecond,
                        ),
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
        val groupBasisResolver: GroupBasisResolver = noFlowsResolver(),
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
    ): Pair<List<LedgerEvent>, Map<String, FlowCategory>> {
        val ledgersRepo = ledgerRepository ?: return emptyList<LedgerEvent>() to emptyMap()
        val rows = ledgersRepo.getLedgersInRange(Instant.EPOCH, horizon)
            .sortedBy { it.time }
        if (rows.isEmpty()) return emptyList<LedgerEvent>() to emptyMap()
        val decided = portfolioStatsRepository.getAppliedAthFlowIds(rows.map { it.ledgerId })
        val unapplied = if (decided.isEmpty()) rows else rows.filterNot { it.ledgerId in decided }
        // Classify the full retained set: refid pairing must see decided
        // partners, or a late backfill completing an internal move would
        // classify its lone leg as owner capital and scale ATH again.
        return unapplied to LedgerFlowClassifier.classifyAll(rows)
    }

    /**
     * Initial-ATH absorption: when ATH is established from the current total,
     * that total already contains every confirmed flow below the coverage
     * horizon, so all undecided decision-bearing rows are journaled as
     * absorbed. Without this the identity scan would re-apply lifetime history
     * against the post-fold baseline on the next cycle (the old watermark
     * advance used to absorb them implicitly).
     */
    private suspend fun absorbUnappliedFlowsIntoInitialAth(horizon: Instant): List<AppliedAthFlow> {
        val (unapplied, classifications) = scanUndecidedLedgerEvents(horizon)
        val absorbed = mutableListOf<AppliedAthFlow>()
        for (event in unapplied) {
            val category = classifications[event.ledgerId]
            if (category != FlowCategory.INTERNAL_MOVE && category != FlowCategory.TRADE_IGNORED) {
                absorbed.add(AppliedAthFlow(ledgerId = event.ledgerId, eventTimeSec = event.time.epochSecond))
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

    private suspend fun calculateUnappliedExternalFlow(balancesObservedAt: Instant?): ExternalFlowCalculation {
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
                throw IllegalStateException(
                    "ledger coverage unknown for balances observed at $balancesObservedAt",
                )
            }
            return ExternalFlowCalculation(emptyList(), null)
        }

        // Temporal coverage gate: balances observed after ledger coverage may
        // include capital the ledger window has not seen yet. The caller
        // treats a stale gate as an untrusted balance: no flow processing, no
        // ATH ratchet, no deployment-driving drawdown.
        if (balancesObservedAt != null && balancesObservedAt.epochSecond > ledgerCoverageSec) {
            return ExternalFlowCalculation(
                emptyList(),
                null,
                coverageStale = true,
                coverageHorizon = Instant.ofEpochSecond(ledgerCoverageSec),
            )
        }
        // Coverage may run past the observation (production observes balances
        // before the sync that confirms coverage): flows between the
        // observation and coverage are not reflected in the total yet, so cap
        // the scan horizon at the observation; they reconcile on later cycles.
        val confirmedHorizon = Instant.ofEpochSecond(
            minOf(ledgerCoverageSec, balancesObservedAt?.epochSecond ?: ledgerCoverageSec),
        )

        val watermarkStr = tradesRepo.getSyncMetadata(SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC)
        if (watermarkStr == null) {
            // Bootstrap: first dated coverage establishes the observability
            // watermark; everything below it predates flow tracking entirely
            // and is folded into the initial ATH baseline like a fresh install.
            return ExternalFlowCalculation(emptyList(), confirmedHorizon.epochSecond)
        }

        val watermarkSec = watermarkStr.toLongOrNull()
            // A corrupt watermark must not silently advance past unapplied
            // flows (skipped withdrawals would overstate drawdown and
            // over-deploy). Fail closed via the IllegalStateException handler;
            // the operator repairs the key and the missing-watermark path above
            // re-establishes the window.
            ?: throw IllegalStateException("malformed ATH flow watermark: $watermarkStr")

        if (ledgersRepo.getSyncMetadata(SyncMetadataKeys.ATH_FLOW_JOURNAL_MIGRATED) == null) {
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
        val (unapplied, classifications) = scanUndecidedLedgerEvents(confirmedHorizon)
        if (unapplied.isEmpty()) return ExternalFlowCalculation(emptyList(), confirmedHorizon.epochSecond)

        // Classify with refid pairing: internal wallet moves and trade rows
        // must never scale ATH. Only OWNER_CAPITAL proceeds. Ambiguous,
        // unsupported, and off-universe rows are skipped loudly and journaled
        // as decided: reprocessing them later would decide identically, and
        // without journaling them the identity scan would re-warn them every
        // cycle. INTERNAL_MOVE and TRADE_IGNORED rows are terminal-neutral
        // and never warn, so they are re-derived cheaply instead of journaled.
        val universe = configService.getConfig().allocations.map { it.symbol.value.uppercase() }.toSet()
        val skippedDecided = mutableListOf<AppliedAthFlow>()
        val events = mutableListOf<LedgerEvent>()
        for (event in unapplied) {
            val category = classifications[event.ledgerId]
            if (category == FlowCategory.OWNER_CAPITAL &&
                isInAthUniverse(Asset.normalizeLedgerAsset(event.asset).uppercase(), universe)
            ) {
                events.add(event)
            } else if (category != FlowCategory.INTERNAL_MOVE && category != FlowCategory.TRADE_IGNORED) {
                log.warn(
                    "Skipping ATH scaling for {} flow {} at {} (category {})",
                    event.type,
                    event.ledgerId,
                    event.time,
                    category,
                )
                skippedDecided.add(AppliedAthFlow(ledgerId = event.ledgerId, eventTimeSec = event.time.epochSecond))
            }
        }
        events.sortWith(compareBy({ it.time }, { it.ledgerId }))
        if (events.isEmpty()) {
            return ExternalFlowCalculation(
                emptyList(),
                confirmedHorizon.epochSecond,
                skippedDecided = skippedDecided,
            )
        }

        // Sequential oldest-first adjustment: each flow scales the ATH that
        // was current just before it. Flows themselves are priced via
        // snapshots or the bounded ticker (fail-closed); each pre-flow basis
        // is reconstructed at event time (see resolveEventTimeBasis).
        val pricedFlows = events.map { event ->
            event to priceOwnerCapitalFlow(event, tradesRepo)
        }
        val maxEventTime = pricedFlows.maxOf { (event, _) -> event.time }
        val history = tradesRepo.getSnapshotsInRange(Instant.EPOCH, maxEventTime)
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
            SequentialFlowStep(
                ledgerIds = pricedFlows.subList(groupStart, groupEnd).map { (event, _) -> event.ledgerId },
                eventTime = pricedFlows[groupStart].first.time,
                flowUSD = groupNet.setScale(PrecisionConstants.SCALE_USD, RoundingMode.HALF_UP),
            )
        }
        return ExternalFlowCalculation(
            sequentialFlows = steps,
            pendingWatermarkSec = confirmedHorizon.epochSecond,
            skippedDecided = skippedDecided,
            groupBasisResolver = GroupBasisResolver(
                ::resolveEventTimeBasis,
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
        private val resolve: (
            eventTime: Instant,
            priorFlows: List<Pair<LedgerEvent, BigDecimal>>,
            history: List<PortfolioSnapshot>,
            trades: List<TradeRecord>,
        ) -> BigDecimal?,
        private val pricedFlows: List<Pair<LedgerEvent, BigDecimal>>,
        private val groupStarts: List<Int>,
        private val history: List<PortfolioSnapshot>,
        private val trades: List<TradeRecord>,
    ) {
        fun basisFor(groupIndex: Int): BigDecimal? {
            val start = groupStarts[groupIndex]
            return resolve(
                pricedFlows[start].first.time,
                pricedFlows.subList(0, start),
                history,
                trades,
            )
        }
    }

    /**
     * Best defensible portfolio value immediately BEFORE an owner flow: the
     * latest recorded snapshot at or before the event, plus priced owner flows
     * strictly after it, plus retained successful trades replayed at the
     * predecessor snapshot's prices between that snapshot and the event (fee
     * drag and inventory changes move portfolio value without being owner
     * capital). A snapshot at the event is exact; older ones carry market
     * drift over the logged gap. Snapshots after the event already contain
     * the flow and must never serve as its pre-flow basis.
     *
     * Returns null when no snapshot at or before the event exists anywhere in
     * the database: such a flow predates recorded history, its effect is
     * already baked into the initial ATH baseline, and the caller journals it
     * as consciously skipped. The former residual approximation silently
     * scaled ATH by an error equal to market movement between the event and
     * now, so it is gone.
     *
     * Fail-closed (via the caller's Deferred state) when no positive basis can
     * be established from the reconstructed state.
     */
    private fun resolveEventTimeBasis(
        eventTime: Instant,
        priorFlows: List<Pair<LedgerEvent, BigDecimal>>,
        history: List<PortfolioSnapshot>,
        trades: List<TradeRecord>,
    ): BigDecimal? {
        val predecessor = history.filter { !it.timestamp.isAfter(eventTime) }.maxByOrNull { it.timestamp }
            ?: return null
        var basis = predecessor.totalValueUSD
        for ((event, usd) in priorFlows) {
            if (event.time.isAfter(predecessor.timestamp)) {
                basis = basis.add(usd)
            }
        }
        for (trade in trades) {
            if (trade.timestamp.isAfter(predecessor.timestamp) && !trade.timestamp.isAfter(eventTime)) {
                basis = basis.add(tradeValueDeltaAtPredecessorPrices(trade, predecessor))
            }
        }
        if (basis > BigDecimal.ZERO) {
            log.info(
                "ATH flow basis reconstructed from snapshot at {} (gap {}s) for event at {}",
                predecessor.timestamp,
                eventTime.epochSecond - predecessor.timestamp.epochSecond,
                eventTime,
            )
            return basis
        }
        throw IllegalStateException("Cannot establish a positive pre-flow portfolio basis for event at $eventTime")
    }

    /**
     * Fill value change at predecessor snapshot prices: a BUY converts
     * (usdAmount + fee) fiat into `volume` crypto, a SELL converts `volume`
     * crypto into (usdAmount - fee) fiat. Valuing the crypto leg with the
     * snapshot's own price keeps the basis consistent with how the snapshot
     * total was priced. An asset outside the snapshot universe has no tracked
     * crypto leg: only its fiat movement leaves the recorded total.
     */
    private fun tradeValueDeltaAtPredecessorPrices(trade: TradeRecord, predecessor: PortfolioSnapshot): BigDecimal {
        val asset = Asset.normalizeLedgerAsset(trade.symbol).uppercase()
        if (asset == "USD" || asset == "ZUSD") return BigDecimal.ZERO
        val price = predecessor.assets[asset]?.price?.takeIf { it > BigDecimal.ZERO }
        val cryptoValueUSD = price?.let { trade.volume.multiply(it) }
        return if (OrderSide.isBuy(trade.side)) {
            (cryptoValueUSD ?: BigDecimal.ZERO).subtract(trade.usdAmount).subtract(trade.fee)
        } else {
            trade.usdAmount.subtract(trade.fee).subtract(cryptoValueUSD ?: BigDecimal.ZERO)
        }
    }

    private fun isInAthUniverse(normalizedAsset: String, universe: Set<String>): Boolean {
        if (normalizedAsset == "USD" || normalizedAsset == "ZUSD") return true
        if (universe.isEmpty()) return true
        return universe.contains(normalizedAsset)
    }

    private suspend fun priceOwnerCapitalFlow(event: LedgerEvent, tradesRepo: TradeRepository): BigDecimal {
        val delta = event.netBalanceDelta()
        val asset = Asset.normalizeLedgerAsset(event.asset).uppercase()
        if (asset == "USD" || asset == "ZUSD") return delta
        val historicalPrice = resolveHistoricalOrTickerPrice(asset, event.time, tradesRepo)
        return delta.multiply(historicalPrice)
    }

    private suspend fun resolveHistoricalOrTickerPrice(
        asset: String,
        eventTime: Instant,
        tradesRepo: TradeRepository,
    ): BigDecimal {
        // 1. Look for closest recorded portfolio snapshot within +/- 180 seconds.
        // No broader fallback: an arbitrarily old snapshot can silently corrupt ATH
        // valuations by feeding stale prices through the proportional scaling.
        val nearestSnap = tradesRepo.getSnapshotsInRange(
            eventTime.minusSeconds(180),
            eventTime.plusSeconds(180),
        ).minByOrNull { kotlin.math.abs(it.timestamp.toEpochMilli() - eventTime.toEpochMilli()) }

        val snapPrice = nearestSnap?.assets?.get(asset)?.price
        if (snapPrice != null && snapPrice > BigDecimal.ZERO) {
            return snapPrice
        }

        // 2. Bounded fallback to exchange ticker: live prices only proxy
        // historical cost when the event is recent (<= 24h). An unbounded
        // ticker fallback prices month-old flows at today's price and
        // silently corrupts ATH scaling.
        val eventAgeSeconds = nowProvider().epochSecond - eventTime.epochSecond
        if (eventAgeSeconds in 0..86400) {
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
                "Skipping live-ticker fallback for stale owner-capital flow: asset {} at {} (age {}s > 24h)",
                asset,
                eventTime,
                eventAgeSeconds,
            )
        }

        // 3. Fail closed on unresolved price: do NOT treat as zero, do NOT advance watermark
        throw IllegalStateException("Cannot reliably price external capital flow for asset $asset at $eventTime")
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
        /**
         * Placeholder resolver: flow-less calculations never resolve a basis,
         * so this instance is never invoked.
         */
        private fun noFlowsResolver(): GroupBasisResolver = GroupBasisResolver(
            resolve = { _, _, _, _ -> error("Basis resolution without flows") },
            pricedFlows = emptyList(),
            groupStarts = emptyList(),
            history = emptyList(),
            trades = emptyList(),
        )
    }
}
