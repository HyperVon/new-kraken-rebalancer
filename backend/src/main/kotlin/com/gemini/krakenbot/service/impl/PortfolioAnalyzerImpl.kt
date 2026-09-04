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
import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.Result
import com.gemini.krakenbot.model.SyncMetadataKeys
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
        val appliedFlows = mutableListOf<com.gemini.krakenbot.repository.AppliedAthFlow>()
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
            // No ATH history to scale yet: every flow folds into initial ATH
            // below. Still advance the watermark so the unapplied window does
            // not grow unboundedly across fresh-start cycles.
            val flowCalc = if (ath > BigDecimal.ZERO) {
                calculateUnappliedExternalFlow(totalPortfolioValueUSD, balancesObservedAt)
            } else {
                val coverage = ledgerRepository
                    ?.getSyncMetadata(SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC)
                    ?.toLongOrNull()
                ExternalFlowCalculation(emptyList(), coverage)
            }
            if (flowCalc.coverageStale) {
                // The balance may already contain owner capital the ledger
                // window has not seen yet. It must neither establish a new ATH
                // nor produce a drawdown that drives fiat deployment. Preserve
                // the previous trusted state untouched and let the caller fail
                // closed on deployment.
                log.warn(
                    "Deferring ATH update: balances observed at {} are newer than ledger coverage {}; " +
                        "preserving trusted ATH {}",
                    balancesObservedAt,
                    flowCalc.coverageHorizon,
                    ath.toUsdScale(),
                )
                return AthUpdateResult.Deferred(stats.lastTrustedDrawdownPct)
            }
            var brokeEarly = false
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
                val basis = flowCalc.groupBasisResolver.basisFor(groupIndex)
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
                        com.gemini.krakenbot.repository.AppliedAthFlow(
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
        val groupBasisResolver: GroupBasisResolver = noFlowsResolver(),
    )

    private suspend fun calculateUnappliedExternalFlow(
        currentTotalUSD: BigDecimal,
        balancesObservedAt: Instant?,
    ): ExternalFlowCalculation {
        val ledgersRepo = ledgerRepository ?: return ExternalFlowCalculation(emptyList(), null)
        val tradesRepo = tradeRepository ?: return ExternalFlowCalculation(emptyList(), null)

        val ledgerCoverageSec = ledgersRepo.getSyncMetadata(SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC)?.toLongOrNull()
            ?: return ExternalFlowCalculation(emptyList(), null)

        val confirmedHorizon = Instant.ofEpochSecond(ledgerCoverageSec)
        // Temporal coverage gate: balances observed after ledger coverage may
        // include capital the ledger window has not seen yet. The caller
        // treats a stale gate as an untrusted balance: no flow processing, no
        // ATH ratchet, no deployment-driving drawdown.
        if (balancesObservedAt != null && balancesObservedAt.epochSecond > ledgerCoverageSec) {
            return ExternalFlowCalculation(emptyList(), null, coverageStale = true, coverageHorizon = confirmedHorizon)
        }

        val watermarkStr = tradesRepo.getSyncMetadata(SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC)
        if (watermarkStr == null) {
            return ExternalFlowCalculation(emptyList(), ledgerCoverageSec)
        }

        val watermarkSec = watermarkStr.toLongOrNull() ?: ledgerCoverageSec
        if (watermarkSec >= ledgerCoverageSec) {
            return ExternalFlowCalculation(emptyList(), watermarkSec)
        }

        val watermark = Instant.ofEpochSecond(watermarkSec)
        val candidates = ledgersRepo.getLedgersInRange(watermark, confirmedHorizon)
            .filter { it.time > watermark && it.time <= confirmedHorizon }
            .sortedBy { it.time }
        if (candidates.isEmpty()) return ExternalFlowCalculation(emptyList(), ledgerCoverageSec)

        // Crash idempotency: the journal records exactly which flows were
        // applied. Anything recorded is skipped even if it still falls inside
        // the watermark window (e.g. the watermark was held after an early
        // break, or overlapping sync windows re-report it).
        val alreadyApplied = portfolioStatsRepository.getAppliedAthFlowIds(candidates.map { it.ledgerId })
        val unapplied = if (alreadyApplied.isEmpty()) {
            candidates
        } else {
            candidates.filterNot { it.ledgerId in alreadyApplied }
        }
        if (unapplied.isEmpty()) return ExternalFlowCalculation(emptyList(), ledgerCoverageSec)

        // Classify with refid pairing: internal wallet moves and trade rows
        // must never scale ATH. Only OWNER_CAPITAL proceeds.
        val classifications = com.gemini.krakenbot.model.LedgerFlowClassifier.classifyAll(unapplied)
        val universe = configService.getConfig().allocations.map { it.symbol.value.uppercase() }.toSet()
        val events = unapplied
            .filter { event ->
                classifications[event.ledgerId] == com.gemini.krakenbot.model.FlowCategory.OWNER_CAPITAL &&
                    isInAthUniverse(Asset.normalizeLedgerAsset(event.asset).uppercase(), universe)
            }
            .sortedWith(compareBy({ it.time }, { it.ledgerId }))
        if (events.isEmpty()) return ExternalFlowCalculation(emptyList(), ledgerCoverageSec)

        // Sequential oldest-first adjustment: each flow scales the ATH that
        // was current just before it. Flows themselves are priced via
        // snapshots or the bounded ticker (fail-closed); each pre-flow basis
        // is reconstructed at event time (see resolveEventTimeBasis).
        val pricedFlows = events.map { event ->
            event to priceOwnerCapitalFlow(event, tradesRepo)
        }
        val history = tradesRepo.getSnapshotsInRange(
            Instant.EPOCH,
            pricedFlows.maxOf { (event, _) -> event.time },
        )
        val totalNet = pricedFlows.fold(BigDecimal.ZERO) { acc, (_, usd) -> acc.add(usd) }
        val residualBase = currentTotalUSD.subtract(totalNet)
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
            pendingWatermarkSec = ledgerCoverageSec,
            groupBasisResolver = GroupBasisResolver(
                ::resolveEventTimeBasis,
                pricedFlows,
                groupStarts,
                history,
                residualBase,
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
            residualFallback: BigDecimal,
        ) -> BigDecimal,
        private val pricedFlows: List<Pair<LedgerEvent, BigDecimal>>,
        private val groupStarts: List<Int>,
        private val history: List<PortfolioSnapshot>,
        private val residualBase: BigDecimal,
    ) {
        fun basisFor(groupIndex: Int): BigDecimal {
            val start = groupStarts[groupIndex]
            val time = pricedFlows[start].first.time
            var priorNet = BigDecimal.ZERO
            for (index in 0 until start) {
                priorNet = priorNet.add(pricedFlows[index].second)
            }
            return resolve(
                time,
                pricedFlows.subList(0, start),
                history,
                residualBase.add(priorNet),
            )
        }
    }

    /**
     * Best defensible portfolio value immediately BEFORE an owner flow:
     * 1. a recorded snapshot within +/-180s of the event (exact);
     * 2. otherwise the latest snapshot at/before the event plus priced owner
     *    flows strictly after it (reconstructed; gap duration is logged);
     * 3. otherwise — no snapshot exists at/before the event anywhere in the
     *    database (fresh or migrated install) — the residual approximation
     *    (current total minus not-yet-applied flows) with an explicit warning.
     *    Residual error equals market movement between the event and now, so
     *    it is only acceptable when no event-time state exists at all.
     *
     * Fail-closed when no positive basis can be established.
     */
    private fun resolveEventTimeBasis(
        eventTime: Instant,
        priorFlows: List<Pair<LedgerEvent, BigDecimal>>,
        history: List<PortfolioSnapshot>,
        residualFallback: BigDecimal,
    ): BigDecimal {
        val exact = history
            .filter {
                kotlin.math.abs(it.timestamp.toEpochMilli() - eventTime.toEpochMilli()) <=
                    EVENT_BASIS_SNAPSHOT_WINDOW_SECONDS * 1000L
            }
            .minByOrNull { kotlin.math.abs(it.timestamp.toEpochMilli() - eventTime.toEpochMilli()) }
        if (exact != null && exact.totalValueUSD > BigDecimal.ZERO) {
            return exact.totalValueUSD
        }
        val predecessor = history.filter { !it.timestamp.isAfter(eventTime) }.maxByOrNull { it.timestamp }
        if (predecessor != null) {
            val subsequentFlows = priorFlows
                .filter { (event, _) -> event.time.isAfter(predecessor.timestamp) }
                .fold(BigDecimal.ZERO) { acc, (_, usd) -> acc.add(usd) }
            val reconstructed = predecessor.totalValueUSD.add(subsequentFlows)
            if (reconstructed > BigDecimal.ZERO) {
                log.info(
                    "ATH flow basis reconstructed from snapshot at {} (gap {}s) for event at {}",
                    predecessor.timestamp,
                    eventTime.epochSecond - predecessor.timestamp.epochSecond,
                    eventTime,
                )
                return reconstructed
            }
        }
        if (residualFallback > BigDecimal.ZERO) {
            log.warn(
                "ATH flow basis for event at {} uses residual approximation (no prior snapshot); " +
                    "error equals market movement between event and now",
                eventTime,
            )
            return residualFallback
        }
        throw IllegalStateException("Cannot establish a positive pre-flow portfolio basis for event at $eventTime")
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
        /** A recorded snapshot this close to a flow event is its exact pre-flow basis. */
        const val EVENT_BASIS_SNAPSHOT_WINDOW_SECONDS = 180L

        /**
         * Placeholder resolver: flow-less calculations never resolve a basis,
         * so this instance is never invoked.
         */
        private fun noFlowsResolver(): GroupBasisResolver = GroupBasisResolver(
            resolve = { _, _, _, _ -> error("Basis resolution without flows") },
            pricedFlows = emptyList(),
            groupStarts = emptyList(),
            history = emptyList(),
            residualBase = BigDecimal.ZERO,
        )
    }
}
