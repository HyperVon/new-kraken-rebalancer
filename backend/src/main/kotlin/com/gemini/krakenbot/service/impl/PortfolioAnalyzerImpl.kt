package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.domain.AssetDeviations
import com.gemini.krakenbot.domain.AssetPrices
import com.gemini.krakenbot.domain.AssetValues
import com.gemini.krakenbot.domain.MutableRebalanceOrders
import com.gemini.krakenbot.domain.PortfolioCalculations
import com.gemini.krakenbot.domain.PortfolioValues
import com.gemini.krakenbot.domain.RawBalances
import com.gemini.krakenbot.domain.RawPrices
import com.gemini.krakenbot.domain.RebalanceEvent
import com.gemini.krakenbot.domain.RebalancePlan
import com.gemini.krakenbot.domain.RebalancerEngine
import com.gemini.krakenbot.domain.toUsdScale
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.Result
import com.gemini.krakenbot.repository.PortfolioStatsRepository
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.service.ObservedBalances
import com.gemini.krakenbot.service.PortfolioAnalyzer
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.Instant
import com.gemini.krakenbot.domain.resolveBalance as resolveBalanceFromKeys

class PortfolioAnalyzerImpl(
    private val krakenService: KrakenService,
    private val configService: ConfigService,
    private val portfolioStatsRepository: PortfolioStatsRepository,
    private val nowProvider: () -> Instant = Instant::now,
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

    override suspend fun updateAthAndCalculateDrawdown(totalPortfolioValueUSD: BigDecimal): BigDecimal {
        val stats = portfolioStatsRepository.load()
        var ath = stats.allTimeHigh

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

        return RebalancerEngine.calculateDrawdown(totalPortfolioValueUSD, ath)
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

    override fun distributeFiatCorrection(
        usdDev: BigDecimal,
        allDevs: AssetDeviations,
        buyOrders: MutableRebalanceOrders,
        sellOrders: MutableRebalanceOrders,
        events: MutableList<RebalanceEvent>,
    ) {
        RebalancerEngine.distributeFiatCorrectionPlan(usdDev, allDevs, buyOrders, sellOrders, events)
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
