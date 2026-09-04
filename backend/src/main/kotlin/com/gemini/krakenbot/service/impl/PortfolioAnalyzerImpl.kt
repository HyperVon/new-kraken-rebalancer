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
    ): BigDecimal {
        val stats = portfolioStatsRepository.load()
        var ath = stats.allTimeHigh
        // Written only after the adjusted ATH persists: advancing the flow
        // watermark before a successful save would skip flows the next cycle
        // never applied after a failed persist.
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
            val flowCalc = calculateUnappliedExternalFlow(totalPortfolioValueUSD, balancesObservedAt)
            for (step in flowCalc.sequentialFlows) {
                if (ath <= BigDecimal.ZERO) break
                val adjustedAth = RebalancerEngine.adjustAthForCashFlow(ath, step.preFlowValueUSD, step.flowUSD)
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
            }
            pendingFlowWatermarkSec = flowCalc.pendingWatermarkSec
        }

        when {
            ath <= BigDecimal.ZERO -> {
                ath = totalPortfolioValueUSD
                log.info(
                    "Initial ATH set to {}",
                    ath.toUsdScale(),
                )
            }

            totalPortfolioValueUSD > ath -> {
                ath = totalPortfolioValueUSD
                log.info(
                    "New All-Time High detected: {}",
                    ath.toUsdScale(),
                )
            }
        }
        val updatedStats = stats.copy(allTimeHigh = ath)
        try {
            portfolioStatsRepository.save(updatedStats)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Fail closed: a lost ATH understates drawdown and would over-deploy crypto into a
            // real drawdown next cycle. The cycle must not plan against an ATH it could not store.
            log.error("Failed to persist portfolio ATH; aborting the cycle", e)
            throw e
        }
        if (pendingFlowWatermarkSec != null) {
            tradeRepository?.setSyncMetadata(
                SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC,
                pendingFlowWatermarkSec.toString(),
            )
        }

        return RebalancerEngine.calculateDrawdown(totalPortfolioValueUSD, ath)
    }

    private data class SequentialFlowStep(
        val eventTime: Instant,
        val flowUSD: BigDecimal,
        val preFlowValueUSD: BigDecimal,
    )

    private data class ExternalFlowCalculation(
        val sequentialFlows: List<SequentialFlowStep>,
        val pendingWatermarkSec: Long?,
    )

    private suspend fun calculateUnappliedExternalFlow(
        currentTotalUSD: BigDecimal,
        balancesObservedAt: Instant?,
    ): ExternalFlowCalculation {
        val ledgersRepo = ledgerRepository ?: return ExternalFlowCalculation(emptyList(), null)
        val tradesRepo = tradeRepository ?: return ExternalFlowCalculation(emptyList(), null)

        val ledgerCoverageSec = ledgersRepo.getSyncMetadata(SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC)?.toLongOrNull()
            ?: return ExternalFlowCalculation(emptyList(), null)

        // Temporal coverage gate: balances observed after ledger coverage may
        // include capital the ledger window has not seen yet. Netting flows
        // against such a total double-counts deposits (inflates ATH) or
        // understates withdrawals. Defer flow application until ledgers catch
        // up; pure price movement still updates ATH below.
        if (balancesObservedAt != null && balancesObservedAt.epochSecond > ledgerCoverageSec) {
            log.info(
                "Deferring ATH cash-flow adjustment: balances observed at {} newer than ledger coverage {}",
                balancesObservedAt,
                Instant.ofEpochSecond(ledgerCoverageSec),
            )
            return ExternalFlowCalculation(emptyList(), null)
        }

        val confirmedHorizon = Instant.ofEpochSecond(ledgerCoverageSec)
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

        // Classify with refid pairing: internal wallet moves and trade rows
        // must never scale ATH. Only OWNER_CAPITAL proceeds.
        val classifications = com.gemini.krakenbot.model.LedgerFlowClassifier.classifyAll(candidates)
        val universe = configService.getConfig().allocations.map { it.symbol.value.uppercase() }.toSet()
        val events = candidates.filter { event ->
            classifications[event.ledgerId] == com.gemini.krakenbot.model.FlowCategory.OWNER_CAPITAL &&
                isInAthUniverse(Asset.normalizeLedgerAsset(event.asset).uppercase(), universe)
        }

        if (events.isEmpty()) return ExternalFlowCalculation(emptyList(), ledgerCoverageSec)

        // Sequential oldest-first adjustment: each flow scales the ATH that
        // was current just before it. The pre-flow basis is residual (current
        // total minus not-yet-applied later flows); flows themselves are
        // priced via snapshots or the bounded ticker. Netted sums mis-scale
        // when ATH ratchets or prices move between flows.
        val pricedFlows = events.map { event ->
            event to priceOwnerCapitalFlow(event, tradesRepo)
        }
        val totalNet = pricedFlows.fold(BigDecimal.ZERO) { acc, (_, usd) -> acc.add(usd) }
        var remainingAfter = totalNet
        val steps = pricedFlows.map { (event, flowUSD) ->
            val preFlow = currentTotalUSD.subtract(remainingAfter)
            remainingAfter = remainingAfter.subtract(flowUSD)
            val snapshotBasis = snapshotTotalNear(event.time, tradesRepo) ?: preFlow
            val basis = if (snapshotBasis > BigDecimal.ZERO) snapshotBasis else preFlow
            SequentialFlowStep(
                eventTime = event.time,
                flowUSD = flowUSD.setScale(PrecisionConstants.SCALE_USD, RoundingMode.HALF_UP),
                preFlowValueUSD = basis,
            )
        }
        return ExternalFlowCalculation(
            sequentialFlows = steps,
            pendingWatermarkSec = ledgerCoverageSec,
        )
    }

    private fun isInAthUniverse(normalizedAsset: String, universe: Set<String>): Boolean {
        if (normalizedAsset == "USD" || normalizedAsset == "ZUSD") return true
        if (universe.isEmpty()) return true
        return universe.contains(normalizedAsset)
    }

    private fun snapshotTotalNear(eventTime: Instant, tradesRepo: TradeRepository): BigDecimal? {
        // Synchronous snapshot lookup would need suspension; the async variant
        // is resolved by the caller via resolveHistoricalOrTickerPrice which
        // already prefers snapshots. This hook returns null so the residual
        // basis (current total minus later flows) applies unless a snapshot
        // price path was used. Kept explicit for auditability.
        return null
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
}
