package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.repository.PortfolioStatsRepository
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.KrakenService
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

    constructor(
        krakenService: KrakenService,
        configService: ConfigService,
        tradeHistoryService: TradeHistoryService,
        portfolioStatsRepository: PortfolioStatsRepository
    ) : this(
        configService,
        tradeHistoryService,
        PortfolioAnalyzer(
            krakenService,
            configService,
            portfolioStatsRepository
        ),
        krakenService
    )

    private constructor(
        configService: ConfigService,
        tradeHistoryService: TradeHistoryService,
        portfolioAnalyzer: PortfolioAnalyzer,
        krakenService: KrakenService
    ) : this(
        configService,
        tradeHistoryService,
        portfolioAnalyzer,
        OrderExecutor(krakenService, configService, portfolioAnalyzer)
    )

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

        val balances = fetchBalances()
        val prices = fetchPrices()
        val currentValuesUSD = mutableMapOf<String, BigDecimal>()
        val totalPortfolioValueUSD =
            calculatePortfolioValues(balances, prices, currentValuesUSD)
                ?: return

        log.info(
            "Total Portfolio Value: $${
                totalPortfolioValueUSD.setScale(
                    2,
                    RoundingMode.HALF_UP
                )
            }"
        )

        val drawdownPct = updateAthAndCalculateDrawdown(totalPortfolioValueUSD)
        val fiatDeploymentPct = calculateFiatDeployment(drawdownPct)

        if (fiatDeploymentPct > BigDecimal.ZERO) {
            log.info(
                "Drawdown Detected: {}%. Fiat Deployment: {}%",
                drawdownPct.setScale(2, RoundingMode.HALF_UP),
                fiatDeploymentPct.setScale(2, RoundingMode.HALF_UP)
            )
        }

        val effectiveUsdTarget = calculateEffectiveUsdTarget(fiatDeploymentPct)
        val cryptoScaleFactor = calculateCryptoScaleFactor(effectiveUsdTarget)

        val buyOrders = mutableMapOf<String, BigDecimal>()
        val sellOrders = mutableMapOf<String, BigDecimal>()
        analyzeDeviations(
            totalPortfolioValueUSD,
            currentValuesUSD,
            effectiveUsdTarget,
            cryptoScaleFactor,
            buyOrders,
            sellOrders,
            actionLog
        )

        val s = configService.getConfig().settings
        executeOrders(
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

    // Delegate to PortfolioAnalyzer for compatibility with existing tests
    internal suspend fun fetchBalances(): Map<String, Double> =
        portfolioAnalyzer.fetchBalances()

    internal suspend fun fetchPrices(): Map<String, BigDecimal> =
        portfolioAnalyzer.fetchPrices()

    internal fun resolvePriceFromTicker(
        symbol: String,
        rawPrices: Map<String, Double>
    ): BigDecimal =
        portfolioAnalyzer.resolvePriceFromTicker(symbol, rawPrices)

    internal fun updateAthAndCalculateDrawdown(totalPortfolioValueUSD: BigDecimal): BigDecimal =
        portfolioAnalyzer.updateAthAndCalculateDrawdown(totalPortfolioValueUSD)

    internal fun calculateFiatDeployment(drawdownPct: BigDecimal): BigDecimal =
        portfolioAnalyzer.calculateFiatDeployment(
            drawdownPct,
            configService.getConfig().settings
        )

    internal fun calculateEffectiveUsdTarget(fiatDeploymentPct: BigDecimal): BigDecimal =
        portfolioAnalyzer.calculateEffectiveUsdTarget(fiatDeploymentPct)

    internal fun calculateCryptoScaleFactor(effectiveUsdTarget: BigDecimal): BigDecimal =
        portfolioAnalyzer.calculateCryptoScaleFactor(effectiveUsdTarget)

    // Delegate to OrderExecutor for compatibility with existing tests
    internal fun analyzeDeviations(
        totalPortfolioValueUSD: BigDecimal,
        currentValuesUSD: Map<String, BigDecimal>,
        effectiveUsdTarget: BigDecimal,
        cryptoScaleFactor: BigDecimal,
        buyOrders: MutableMap<String, BigDecimal>,
        sellOrders: MutableMap<String, BigDecimal>,
        actionLog: MutableList<String>
    ) = orderExecutor.analyzeDeviations(
        totalPortfolioValueUSD,
        currentValuesUSD,
        effectiveUsdTarget,
        cryptoScaleFactor,
        buyOrders,
        sellOrders,
        actionLog
    )

    internal suspend fun executeOrders(
        buyOrders: Map<String, BigDecimal>,
        sellOrders: Map<String, BigDecimal>,
        currentValuesUSD: Map<String, BigDecimal>,
        prices: Map<String, BigDecimal>,
        s: Settings,
        actionLog: MutableList<String>
    ) = orderExecutor.executeOrders(
        buyOrders,
        sellOrders,
        currentValuesUSD,
        prices,
        s,
        actionLog
    )

    internal fun distributeFiatCorrection(
        usdDev: BigDecimal,
        allDevs: Map<String, BigDecimal>,
        buyOrders: MutableMap<String, BigDecimal>,
        sellOrders: MutableMap<String, BigDecimal>,
        actionLog: MutableList<String>
    ) = orderExecutor.distributeFiatCorrection(
        usdDev,
        allDevs,
        buyOrders,
        sellOrders,
        actionLog
    )

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
                    symbol,
                    balances
                )
            )
            val valUSD = currentValuesUSD[symbol] ?: BigDecimal.ZERO
            val price =
                if (!symbol.equals(KrakenSymbols.USD, ignoreCase = true)) {
                    prices[symbol] ?: BigDecimal.ONE
                } else {
                    BigDecimal.ONE
                }

            val baseTargetPct = BigDecimal.valueOf(a.targetPercent)
            var snapshotTargetPct = baseTargetPct
            val calcTargetPct: BigDecimal

            if (symbol.equals(KrakenSymbols.USD, ignoreCase = true)) {
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

            assetSnapshots[symbol] = PortfolioSnapshot.AssetSnapshot(
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

    private fun calculatePortfolioValues(
        balances: Map<String, Double>,
        prices: Map<String, BigDecimal>,
        currentValuesUSD: MutableMap<String, BigDecimal>
    ): BigDecimal? = portfolioAnalyzer.calculatePortfolioValues(
        balances,
        prices,
        currentValuesUSD
    )

    private fun resolveBalance(
        symbol: String,
        balances: Map<String, Double>
    ): Double =
        portfolioAnalyzer.resolveBalance(symbol, balances)

    private fun logOrderResult(
        result: com.gemini.krakenbot.model.OrderResult,
        actionLog: MutableList<String>,
        symbol: String,
        volume: BigDecimal,
        usdAmount: BigDecimal,
        side: String
    ) = orderExecutor.logOrderResult(
        result,
        actionLog,
        symbol,
        volume,
        usdAmount,
        side
    )
}
