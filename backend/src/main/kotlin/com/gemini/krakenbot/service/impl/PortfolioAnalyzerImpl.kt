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
    ): BigDecimal {
        val stats = portfolioStatsRepository.load()
        var ath = stats.allTimeHigh

        val (effectiveNetFlow, pendingWatermarkSec) = if (netExternalFlowUSD.signum() != 0) {
            netExternalFlowUSD to null
        } else {
            val flowCalc = calculateUnappliedExternalFlow()
            flowCalc.netFlow to flowCalc.pendingWatermarkSec
        }

        if (effectiveNetFlow.signum() != 0 && ath > BigDecimal.ZERO) {
            val preFlowValueUSD = totalPortfolioValueUSD.subtract(effectiveNetFlow)
            val adjustedAth = RebalancerEngine.adjustAthForCashFlow(ath, preFlowValueUSD, effectiveNetFlow)
            if (adjustedAth.compareTo(ath) != 0) {
                log.info(
                    "ATH adjusted for external cash flow (netFlow={}): {} -> {}",
                    effectiveNetFlow.toUsdScale(),
                    ath.toUsdScale(),
                    adjustedAth.toUsdScale(),
                )
                ath = adjustedAth
            }
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

        if (pendingWatermarkSec != null) {
            tradeRepository?.setSyncMetadata(
                SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC,
                pendingWatermarkSec.toString(),
            )
        }

        return RebalancerEngine.calculateDrawdown(totalPortfolioValueUSD, ath)
    }

    private data class ExternalFlowCalculation(val netFlow: BigDecimal, val pendingWatermarkSec: Long?)

    private suspend fun calculateUnappliedExternalFlow(): ExternalFlowCalculation {
        val ledgersRepo = ledgerRepository ?: return ExternalFlowCalculation(BigDecimal.ZERO, null)
        val tradesRepo = tradeRepository ?: return ExternalFlowCalculation(BigDecimal.ZERO, null)

        val now = nowProvider()
        val currentEpochSec = now.epochSecond
        val watermarkUpper = Instant.ofEpochSecond(currentEpochSec)
        val watermarkStr = tradesRepo.getSyncMetadata(SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC)
        if (watermarkStr == null) {
            return ExternalFlowCalculation(BigDecimal.ZERO, currentEpochSec)
        }

        val watermarkSec = watermarkStr.toLongOrNull() ?: currentEpochSec
        if (watermarkSec >= currentEpochSec) {
            return ExternalFlowCalculation(BigDecimal.ZERO, watermarkSec)
        }

        val watermark = Instant.ofEpochSecond(watermarkSec)
        val events = ledgersRepo.getLedgersInRange(watermark, watermarkUpper)
            .filter {
                it.type in LedgerEvent.EXTERNAL_BALANCE_TYPES && it.time > watermark && it.time <= watermarkUpper
            }

        if (events.isEmpty()) return ExternalFlowCalculation(BigDecimal.ZERO, currentEpochSec)

        var netFlow = BigDecimal.ZERO
        for (event in events) {
            val delta = event.netBalanceDelta()
            val asset = Asset.normalizeLedgerAsset(event.asset).uppercase()
            val usdDelta = if (asset == "USD" || asset == "ZUSD") {
                delta
            } else {
                try {
                    val pair = Asset(asset).tradingPair
                    val raw = krakenService.getTickerPrices(pair)
                    val price = resolvePriceFromTicker(asset, raw)
                    delta.multiply(price)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    BigDecimal.ZERO
                }
            }
            netFlow = netFlow.add(usdDelta)
        }
        return ExternalFlowCalculation(
            netFlow = netFlow.setScale(PrecisionConstants.SCALE_USD, RoundingMode.HALF_UP),
            pendingWatermarkSec = currentEpochSec,
        )
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
