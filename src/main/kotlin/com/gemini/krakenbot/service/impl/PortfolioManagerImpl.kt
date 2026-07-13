package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.service.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.util.*
import kotlin.time.Duration.Companion.seconds

class PortfolioManagerImpl(
    private val configService: ConfigService,
    private val tradeHistoryService: TradeHistoryService,
    private val portfolioAnalyzer: PortfolioAnalyzer,
    private val orderExecutor: OrderExecutor
) : PortfolioManager {

    private val log =
        LoggerFactory.getLogger(PortfolioManagerImpl::class.java)

    @Volatile
    private var isRunning = false



    @Synchronized
    override fun stopRebalancingLoop() {
        this.isRunning = false
        log.info("Rebalancing loop stopped.")
    }

    @Synchronized
    override fun startRebalancingLoop() {
        this.isRunning = true
        log.info("Rebalancing loop started.")
    }

    override suspend fun runLoop() {
        try {
            log.info("Checking and performing historical trades synchronization from Kraken API...")
            tradeHistoryService.syncTradesFromKraken()
        } catch (e: Exception) {
            log.error("Failed to synchronize historical trades on startup", e)
        }

        try {
            // watchConfigChanges() returns a hot SharedFlow of settings.
            // Using collectLatest { ... } ensures that if a config change occurs while the loop
            // is suspended (e.g. delaying on line 69), the current rebalance loop execution is
            // immediately cancelled, and a new one is launched with the updated settings on-the-fly.
            configService.watchConfigChanges().collectLatest { settings ->
                while (isRunning) {
                    try {
                        log.info(
                            "Starting Rebalance Cycle. DryRun: {}",
                            settings.dryRun
                        )
                        try {
                            tradeHistoryService.syncTradesFromKraken()
                        } catch (e: Exception) {
                            log.error("Failed to synchronize historical trades during cycle", e)
                        }
                        performRebalanceCycle()
                    } catch (e: Exception) {
                        log.error("Error in rebalancing cycle", e)
                    }
                    delay(settings.loopDelaySeconds.seconds)
                }
            }
        } catch (e: CancellationException) {
            log.info("Rebalancing loop coroutine cancelled. Shutting down loop.")
            throw e
        }
    }

    internal suspend fun performRebalanceCycle(): PortfolioSnapshot? {
        val cycleId = UUID.randomUUID().toString()
        MDC.put("cycleId", cycleId)
        try {
            log.info("--- Starting Snapshot Phase ---")
            val actionLog = mutableListOf<String>()

            val balances = portfolioAnalyzer.fetchBalances()
            val prices = portfolioAnalyzer.fetchPrices()
            val calculationResult = portfolioAnalyzer.calculatePortfolioValues(balances, prices)

            val (totalPortfolioValueUSD, currentValuesUSD) = calculationResult.fold(
                onSuccess = { it },
                onFailure = {
                    log.error("Failed to calculate portfolio values: {}", it.message)
                    return null
                }
            )

            log.info(
                "Total Portfolio Value: $${
                    totalPortfolioValueUSD.setScale(
                        2,
                        RoundingMode.HALF_UP
                    )
                }"
            )

            val drawdownPct =
                portfolioAnalyzer
                    .updateAthAndCalculateDrawdown(totalPortfolioValueUSD)
            val fiatDeploymentPct =
                portfolioAnalyzer
                    .calculateFiatDeployment(
                        drawdownPct,
                        configService.getConfig().settings
                    )

            if (fiatDeploymentPct > BigDecimal.ZERO) {
                log.info(
                    "Drawdown Detected: {}%. Fiat Deployment: {}%",
                    drawdownPct.setScale(2, RoundingMode.HALF_UP),
                    fiatDeploymentPct.setScale(2, RoundingMode.HALF_UP)
                )
            }

            val effectiveUsdTarget =
                portfolioAnalyzer.calculateEffectiveUsdTarget(fiatDeploymentPct)
            val cryptoScaleFactor =
                portfolioAnalyzer.calculateCryptoScaleFactor(effectiveUsdTarget)

            val (buyOrders, sellOrders, cycleActions) =
                portfolioAnalyzer.analyzeDeviations(
                    totalPortfolioValueUSD = totalPortfolioValueUSD,
                    currentValuesUSD = currentValuesUSD,
                    effectiveUsdTarget = effectiveUsdTarget,
                    cryptoScaleFactor = cryptoScaleFactor
                )
            actionLog.addAll(cycleActions)

            orderExecutor.executeOrders(
                buyOrders = buyOrders,
                sellOrders = sellOrders,
                currentValuesUSD = currentValuesUSD,
                prices = prices,
                settings = configService.getConfig().settings,
                actionLog = actionLog
            )

            val snapshot = buildSnapshot(
                balances = balances,
                prices = prices,
                currentValuesUSD = currentValuesUSD,
                totalPortfolioValueUSD = totalPortfolioValueUSD,
                effectiveUsdTarget = effectiveUsdTarget,
                cryptoScaleFactor = cryptoScaleFactor,
                drawdownPct = drawdownPct,
                fiatDeploymentPct = fiatDeploymentPct,
                actionLog = actionLog
            )

            try {
                tradeHistoryService.addSnapshot(snapshot)
            } catch (e: IOException) {
                log.error("Failed to persist trade history snapshot", e)
                actionLog.add("ERROR: Failed to persist trade history: ${e.message}")
            }

            log.info("--- Cycle Complete ---")
            return snapshot
        } finally {
            MDC.remove("cycleId")
        }
    }


    private fun buildSnapshot(
        balances: RawBalances,
        prices: AssetPrices,
        currentValuesUSD: AssetValues,
        totalPortfolioValueUSD: BigDecimal,
        effectiveUsdTarget: BigDecimal,
        cryptoScaleFactor: BigDecimal,
        drawdownPct: BigDecimal,
        fiatDeploymentPct: BigDecimal,
        actionLog: List<String>
    ): PortfolioSnapshot {
        val assetSnapshots =
            mutableMapOf<String, PortfolioSnapshot.AssetSnapshot>()

        for ((symbol, targetPercent) in configService.getConfig().allocations) {
            val balance = portfolioAnalyzer.resolveBalance(
                symbol = symbol.value,
                balances = balances
            )
            val valUSD = currentValuesUSD[symbol.value] ?: BigDecimal.ZERO
            val price =
                if (!symbol.isUsd) {
                    prices[symbol.value] ?: BigDecimal.ONE
                } else {
                    BigDecimal.ONE
                }

            // Use consolidated calculation logic
            val metrics = PortfolioCalculations.calculateAssetMetrics(
                symbol = symbol,
                baseTargetPercent = BigDecimal.valueOf(targetPercent),
                currentValueUSD = valUSD,
                totalPortfolioValueUSD = totalPortfolioValueUSD,
                effectiveUsdTarget = effectiveUsdTarget,
                cryptoScaleFactor = cryptoScaleFactor,
                dustThresholdUSD = configService.getConfig().settings.dustThresholdUSD
            )

            assetSnapshots[symbol.value] = PortfolioSnapshot.AssetSnapshot(
                symbol = symbol,
                balance = balance,
                price = price,
                valueUSD = valUSD,
                targetPercent = metrics.calcTargetPercent,
                currentPercent = metrics.currentPercent,
                deviationPercent = metrics.deviationPercent,
                deviationUSD = metrics.deviationUSD
            )
        }

        return PortfolioSnapshot(
            timestamp = Instant.now(),
            totalValueUSD = totalPortfolioValueUSD,
            assets = assetSnapshots,
            actions = actionLog,
            drawdownPercent = drawdownPct,
            fiatDeploymentPercent = fiatDeploymentPct,
            effectiveUsdTargetPercent = effectiveUsdTarget
        )
    }

}
