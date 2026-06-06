package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.PortfolioManager
import com.gemini.krakenbot.service.TradeHistoryService
import com.gemini.krakenbot.util.KrakenSymbols
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds

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
        while (isRunning) {
            val settings = configService.getConfig().settings
            try {
                log.info(
                    "Starting Rebalance Cycle. DryRun: {}",
                    settings.dryRun
                )
                performRebalanceCycle()
            } catch (e: Exception) {
                log.error("Error in rebalancing cycle", e)
            }
            delay((settings.loopDelaySeconds * 1000L).milliseconds)
        }
    }

    internal suspend fun performRebalanceCycle() {
        log.info("--- Starting Snapshot Phase ---")
        val actionLog = mutableListOf<String>()

        val balances = portfolioAnalyzer.fetchBalances()
        val prices = portfolioAnalyzer.fetchPrices()
        val currentValuesUSD = mutableMapOf<String, BigDecimal>()
        val totalPortfolioValueUSD =
            portfolioAnalyzer.calculatePortfolioValues(balances, prices, currentValuesUSD)
                ?: return

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

        val buyOrders = mutableMapOf<String, BigDecimal>()
        val sellOrders = mutableMapOf<String, BigDecimal>()
        portfolioAnalyzer.analyzeDeviations(
            totalPortfolioValueUSD,
            currentValuesUSD,
            effectiveUsdTarget,
            cryptoScaleFactor,
            buyOrders,
            sellOrders,
            actionLog
        )

        val s = configService.getConfig().settings
        orderExecutor.executeOrders(
            buyOrders,
            sellOrders,
            currentValuesUSD,
            prices,
            s,
            actionLog
        )

        val snapshot = buildSnapshot(
            balances, prices, currentValuesUSD,
            totalPortfolioValueUSD, effectiveUsdTarget, cryptoScaleFactor,
            drawdownPct, fiatDeploymentPct, actionLog
        )

        try {
            tradeHistoryService.addSnapshot(snapshot)
        } catch (e: IOException) {
            log.error("Failed to persist trade history snapshot", e)
            actionLog.add("ERROR: Failed to persist trade history: ${e.message}")
        }

        log.info("--- Cycle Complete ---")
    }


    private fun buildSnapshot(
        balances: Map<String, Double>,
        prices: Map<String, BigDecimal>,
        currentValuesUSD: Map<String, BigDecimal>,
        totalPortfolioValueUSD: BigDecimal,
        effectiveUsdTarget: BigDecimal,
        cryptoScaleFactor: BigDecimal,
        drawdownPct: BigDecimal,
        fiatDeploymentPct: BigDecimal,
        actionLog: List<String>
    ): PortfolioSnapshot {
        val assetSnapshots =
            mutableMapOf<String, PortfolioSnapshot.AssetSnapshot>()

        for (a in configService.getConfig().allocations) {
            val symbol = a.symbol
            val balance = BigDecimal.valueOf(
                portfolioAnalyzer.resolveBalance(
                    symbol.value,
                    balances
                )
            )
            val valUSD = currentValuesUSD[symbol.value] ?: BigDecimal.ZERO
            val price =
                if (!symbol.value.equals(KrakenSymbols.USD, ignoreCase = true)) {
                    prices[symbol.value] ?: BigDecimal.ONE
                } else {
                    BigDecimal.ONE
                }

            val baseTargetPct = BigDecimal.valueOf(a.targetPercent)
            var snapshotTargetPct = baseTargetPct
            val calcTargetPct: BigDecimal

            if (symbol.value.equals(KrakenSymbols.USD, ignoreCase = true)) {
                calcTargetPct = effectiveUsdTarget
            } else {
                calcTargetPct = baseTargetPct.multiply(cryptoScaleFactor)
                snapshotTargetPct = calcTargetPct
            }

            var currentPct = BigDecimal.ZERO
            if (totalPortfolioValueUSD > BigDecimal.ZERO) {
                currentPct = valUSD.divide(
                    totalPortfolioValueUSD,
                    4,
                    RoundingMode.HALF_UP
                )
                    .multiply(BigDecimal.valueOf(100))
            }

            val targetVal = totalPortfolioValueUSD
                .multiply(calcTargetPct)
                .divide(
                    BigDecimal.valueOf(100),
                    4,
                    RoundingMode.HALF_UP
                )
            val deviationUSD = valUSD.subtract(targetVal)
            var devPct = BigDecimal.ZERO

            if (targetVal > BigDecimal.ZERO) {
                devPct = deviationUSD
                    .divide(
                        targetVal,
                        4,
                        RoundingMode.HALF_UP
                    )
                    .multiply(BigDecimal.valueOf(100))
            }

            assetSnapshots[symbol.value] = PortfolioSnapshot.AssetSnapshot(
                symbol,
                balance,
                price,
                valUSD,
                snapshotTargetPct,
                currentPct,
                devPct,
                deviationUSD
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
