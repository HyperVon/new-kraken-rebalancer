package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.repository.PortfolioStatsRepository
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.service.PortfolioManager
import com.gemini.krakenbot.service.TradeHistoryService
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import kotlin.math.pow
import kotlin.time.Duration.Companion.milliseconds

class PortfolioManagerImpl(
    private val krakenService: KrakenService,
    private val configService: ConfigService,
    private val tradeHistoryService: TradeHistoryService,
    private val portfolioStatsRepository: PortfolioStatsRepository
) : PortfolioManager {

    private val log = LoggerFactory.getLogger(PortfolioManagerImpl::class.java)

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
                log.info("Starting Rebalance Cycle. DryRun: {}", settings.dryRun)
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
        val totalPortfolioValueUSD = calculatePortfolioValues(balances, prices, currentValuesUSD) ?: return

        log.info("Total Portfolio Value: $${totalPortfolioValueUSD.setScale(2, RoundingMode.HALF_UP)}")

        val drawdownPct = updateAthAndCalculateDrawdown(totalPortfolioValueUSD)
        val fiatDeploymentPct = calculateFiatDeployment(drawdownPct)

        if (fiatDeploymentPct > BigDecimal.ZERO) {
            log.info("Drawdown Detected: {}%. Fiat Deployment: {}%", 
                drawdownPct.setScale(2, RoundingMode.HALF_UP),
                fiatDeploymentPct.setScale(2, RoundingMode.HALF_UP))
        }

        val effectiveUsdTarget = calculateEffectiveUsdTarget(fiatDeploymentPct)
        val cryptoScaleFactor = calculateCryptoScaleFactor(effectiveUsdTarget)

        val buyOrders = mutableMapOf<String, BigDecimal>()
        val sellOrders = mutableMapOf<String, BigDecimal>()
        
        analyzeDeviations(totalPortfolioValueUSD, currentValuesUSD, effectiveUsdTarget, cryptoScaleFactor,
            buyOrders, sellOrders, actionLog)

        val s = configService.getConfig().settings
        executeOrders(buyOrders, sellOrders, currentValuesUSD, prices, s, actionLog)

        val snapshot = buildSnapshot(balances, prices, currentValuesUSD,
            totalPortfolioValueUSD, effectiveUsdTarget, cryptoScaleFactor,
            drawdownPct, fiatDeploymentPct, actionLog)
            
        tradeHistoryService.addSnapshot(snapshot)

        log.info("--- Cycle Complete ---")
    }

    internal suspend fun fetchBalances(): Map<String, Double> {
        val balances = krakenService.getBalances()
        log.info("Available Balance Keys: {}", balances.keys)
        return balances
    }

    internal suspend fun fetchPrices(): Map<String, Double> {
        val allocations = configService.getConfig().allocations
        val pairs = allocations
            .filter { !it.symbol.equals("USD", ignoreCase = true) }
            .joinToString(",") { "${mapToKrakenTicker(it.symbol)}USD" }
            
        return if (pairs.isNotEmpty()) krakenService.getTickerPrices(pairs) else emptyMap()
    }

    private fun calculatePortfolioValues(
        balances: Map<String, Double>, 
        prices: Map<String, Double>,
        currentValuesUSD: MutableMap<String, BigDecimal>
    ): BigDecimal? {
        var totalPortfolioValueUSD = BigDecimal.ZERO

        for (a in configService.getConfig().allocations) {
            val symbol = a.symbol
            val balance = resolveBalance(symbol, balances)
            val bal = BigDecimal.valueOf(balance)
            var price = BigDecimal.ONE

            if (!symbol.equals("USD", ignoreCase = true)) {
                val p = getCurrentPrice(symbol, prices)
                if (p.compareTo(BigDecimal.ZERO) == 0) {
                    log.error("Price not found for {}. Aborting rebalance cycle to prevent erroneous trades.", symbol)
                    return null
                }
                price = p
            }

            val valUSD = bal * price
            currentValuesUSD[symbol] = valUSD
            totalPortfolioValueUSD += valUSD
        }

        return totalPortfolioValueUSD
    }

    private fun resolveBalance(symbol: String, balances: Map<String, Double>): Double {
        return balances[symbol]
            ?: balances["X$symbol"]
            ?: balances["Z$symbol"]
            ?: balances[mapToKrakenTicker(symbol)]
            ?: balances["X${mapToKrakenTicker(symbol)}"]
            ?: 0.0
    }

    internal fun updateAthAndCalculateDrawdown(totalPortfolioValueUSD: BigDecimal): BigDecimal {
        val stats = portfolioStatsRepository.load()
        var ath = stats.allTimeHigh
        
        if (ath == null || totalPortfolioValueUSD > ath) {
            ath = totalPortfolioValueUSD
            stats.allTimeHigh = ath
            portfolioStatsRepository.save(stats)
            log.info("New All-Time High detected: $$ath")
        }

        return if (ath != null && ath > BigDecimal.ZERO && totalPortfolioValueUSD < ath) {
            val diff = ath - totalPortfolioValueUSD
            diff.divide(ath, 4, RoundingMode.HALF_UP) * BigDecimal.valueOf(100)
        } else {
            BigDecimal.ZERO
        }
    }

    private fun calculateFiatDeployment(drawdownPct: BigDecimal): BigDecimal {
        val s = configService.getConfig().settings
        if (s.fiatMaxDrawdown <= 0.0) return BigDecimal.ZERO

        val maxDD = BigDecimal.valueOf(s.fiatMaxDrawdown)
        var ratio = drawdownPct.divide(maxDD, 4, RoundingMode.HALF_UP)
        if (ratio > BigDecimal.ONE) ratio = BigDecimal.ONE

        val deployDouble = ratio.toDouble().pow(s.fiatDeploymentExponent) * 100.0
        return BigDecimal.valueOf(deployDouble)
    }

    private fun calculateEffectiveUsdTarget(fiatDeploymentPct: BigDecimal): BigDecimal {
        val baseUsdTarget = configService.getConfig().allocations
            .filter { it.symbol.equals("USD", ignoreCase = true) }
            .sumOf { it.targetPercent.toBigDecimal() }

        return if (fiatDeploymentPct > BigDecimal.ZERO) {
            val factor = BigDecimal.ONE - fiatDeploymentPct.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP)
            baseUsdTarget * factor
        } else {
            baseUsdTarget
        }
    }

    private fun calculateCryptoScaleFactor(effectiveUsdTarget: BigDecimal): BigDecimal {
        val totalNonUsdTarget = configService.getConfig().allocations
            .filter { !it.symbol.equals("USD", ignoreCase = true) }
            .sumOf { it.targetPercent.toBigDecimal() }

        val remainingForCrypto = BigDecimal.valueOf(100) - effectiveUsdTarget
        return if (totalNonUsdTarget > BigDecimal.ZERO) {
            remainingForCrypto.divide(totalNonUsdTarget, 8, RoundingMode.HALF_UP)
        } else {
            BigDecimal.ONE
        }
    }

    internal fun analyzeDeviations(
        totalPortfolioValueUSD: BigDecimal,
        currentValuesUSD: Map<String, BigDecimal>,
        effectiveUsdTarget: BigDecimal,
        cryptoScaleFactor: BigDecimal,
        buyOrders: MutableMap<String, BigDecimal>,
        sellOrders: MutableMap<String, BigDecimal>,
        actionLog: MutableList<String>
    ) {
        val s = configService.getConfig().settings
        var usdTriggered = false
        var usdDeviationAmount = BigDecimal.ZERO
        val allDeviations = mutableMapOf<String, BigDecimal>()

        configService.getConfig().allocations.forEach { a ->
            var targetPct = BigDecimal.valueOf(a.targetPercent)

            targetPct = if (a.symbol.equals("USD", ignoreCase = true)) {
                effectiveUsdTarget
            } else {
                targetPct.multiply(cryptoScaleFactor)
            }

            targetPct = targetPct.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP)
            val targetValue = totalPortfolioValueUSD.multiply(targetPct)
            val currentVal = currentValuesUSD[a.symbol] ?: BigDecimal.ZERO

            val deviationUSD = currentVal.subtract(targetValue)
            var deviationPct = BigDecimal.ZERO

            if (targetValue > BigDecimal.ZERO) {
                deviationPct = deviationUSD.abs().divide(targetValue, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
            } else if (currentVal > BigDecimal.ZERO) {
                deviationPct = BigDecimal.valueOf(100.0)
            }

            allDeviations[a.symbol] = deviationUSD

            log.info("Analysis [{}]: Dev: {}% ($ {}). Threshold: {}%",
                a.symbol, deviationPct, deviationUSD.setScale(2, RoundingMode.HALF_UP),
                s.deviationTriggerPercent)

            if (deviationPct.toDouble() >= s.deviationTriggerPercent) {
                actionLog.add("Deviation Triggered details: ${a.symbol} Dev: $deviationPct%")
            }

            if (a.symbol.equals("USD", ignoreCase = true)) {
                if (deviationPct.toDouble() >= s.deviationTriggerPercent) {
                    log.info("Asset USD Deviation: {}% (Trigger: {}%). USD Dev: {}",
                        deviationPct, s.deviationTriggerPercent, deviationUSD)
                    usdTriggered = true
                    usdDeviationAmount = deviationUSD
                }
            } else {
                if (deviationPct.toDouble() >= s.deviationTriggerPercent) {
                    log.info("Asset {} Deviation: {}% (Trigger: {}%). USD Dev: {}",
                        a.symbol, deviationPct, s.deviationTriggerPercent, deviationUSD)

                    if (deviationUSD > BigDecimal.ZERO) {
                        sellOrders[a.symbol] = deviationUSD
                    } else {
                        buyOrders[a.symbol] = deviationUSD.abs()
                    }
                }
            }
        }

        if (buyOrders.isEmpty() && sellOrders.isEmpty() && usdTriggered) {
            log.info("USD Deviation triggered but no individual asset triggers. Enforcing fiat correction.")
            actionLog.add("USD Deviation Triggered. Enforcing fiat correction.")
            distributeFiatCorrection(usdDeviationAmount, allDeviations, buyOrders, sellOrders, actionLog)
        }
    }

    internal suspend fun executeOrders(
        buyOrders: Map<String, BigDecimal>,
        sellOrders: Map<String, BigDecimal>,
        currentValuesUSD: Map<String, BigDecimal>,
        prices: Map<String, Double>,
        s: Settings,
        actionLog: MutableList<String>
    ) {
        var projectedCash = currentValuesUSD["USD"] ?: BigDecimal.ZERO
        var executedSells = false

        for ((symbol, usdToSell) in sellOrders) {
            if (usdToSell < BigDecimal.valueOf(s.dustThresholdUSD)) {
                log.info("Skipping dust sell for {} ($ {})", symbol, usdToSell)
                actionLog.add("Skipping dust sell for $symbol ($$usdToSell)")
                continue
            }

            val price = getCurrentPrice(symbol, prices)
            if (price.compareTo(BigDecimal.ZERO) == 0) continue

            val volume = usdToSell.divide(price, 8, RoundingMode.HALF_UP)
            krakenService.executeOrder("${symbol}USD", "market", "sell", volume.toDouble())
            projectedCash = projectedCash.add(usdToSell)
            executedSells = true
            actionLog.add("SELL $symbol Volume: $volume Value: $$usdToSell")
        }

        var actualCash = projectedCash
        if (executedSells && !s.dryRun) {
            try {
                delay(100.milliseconds)
                val updatedBalances = krakenService.getBalances()
                if (updatedBalances.isNotEmpty()) {
                    val usdBalance = resolveBalance("USD", updatedBalances)
                    if (usdBalance > 0) {
                        actualCash = BigDecimal.valueOf(usdBalance)
                        log.info("Updated USD balance after sells: $$actualCash")
                    }
                }
            } catch (e: Exception) {
                log.warn("Failed to fetch updated USD balance before buys, using previous snapshot.", e)
            }
        }

        for ((symbol, originalCost) in buyOrders) {
            var cost = originalCost
            if (cost > actualCash) {
                log.warn("Not enough cash to buy {}. Cost: {}, Cash: {}. Reducing.", symbol, cost, actualCash)
                cost = actualCash.multiply(BigDecimal.valueOf(0.99))
            }

            if (cost < BigDecimal.valueOf(s.dustThresholdUSD)) {
                log.info("Skipping dust buy for {} ($ {})", symbol, cost)
                actionLog.add("Skipping dust buy for $symbol ($$cost)")
                continue
            }

            val price = getCurrentPrice(symbol, prices)
            if (price.compareTo(BigDecimal.ZERO) == 0) continue

            val volume = cost.divide(price, 8, RoundingMode.HALF_UP)
            krakenService.executeOrder("${symbol}USD", "market", "buy", volume.toDouble())
            actualCash = actualCash.subtract(cost)
            actionLog.add("BUY $symbol Volume: $volume Cost: $$cost")
        }
    }

    private fun buildSnapshot(
        balances: Map<String, Double>,
        prices: Map<String, Double>,
        currentValuesUSD: Map<String, BigDecimal>,
        totalPortfolioValueUSD: BigDecimal,
        effectiveUsdTarget: BigDecimal,
        cryptoScaleFactor: BigDecimal,
        drawdownPct: BigDecimal,
        fiatDeploymentPct: BigDecimal,
        actionLog: List<String>
    ): PortfolioSnapshot {
        val assetSnapshots = mutableMapOf<String, PortfolioSnapshot.AssetSnapshot>()

        for (a in configService.getConfig().allocations) {
            val symbol = a.symbol
            val balance = BigDecimal.valueOf(resolveBalance(symbol, balances))
            val valUSD = currentValuesUSD[symbol] ?: BigDecimal.ZERO
            val price = if (!symbol.equals("USD", ignoreCase = true)) getCurrentPrice(symbol, prices) else BigDecimal.ONE

            val baseTargetPct = BigDecimal.valueOf(a.targetPercent)
            var snapshotTargetPct = baseTargetPct
            val calcTargetPct: BigDecimal

            if (symbol.equals("USD", ignoreCase = true)) {
                calcTargetPct = effectiveUsdTarget
            } else {
                calcTargetPct = baseTargetPct.multiply(cryptoScaleFactor)
                snapshotTargetPct = calcTargetPct
            }

            var currentPct = BigDecimal.ZERO
            if (totalPortfolioValueUSD > BigDecimal.ZERO) {
                currentPct = valUSD.divide(totalPortfolioValueUSD, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
            }

            val targetVal = totalPortfolioValueUSD.multiply(calcTargetPct).divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP)
            val deviationUSD = valUSD.subtract(targetVal)
            var devPct = BigDecimal.ZERO

            if (targetVal > BigDecimal.ZERO) {
                devPct = deviationUSD.divide(targetVal, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
            }

            assetSnapshots[symbol] = PortfolioSnapshot.AssetSnapshot(
                symbol, balance, price, valUSD, snapshotTargetPct, currentPct, devPct, deviationUSD
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

    internal fun distributeFiatCorrection(
        usdDev: BigDecimal,
        allDevs: Map<String, BigDecimal>,
        buyOrders: MutableMap<String, BigDecimal>,
        sellOrders: MutableMap<String, BigDecimal>,
        actionLog: MutableList<String>
    ) {
        val deviationAbs = usdDev.abs()
        val isDeposit = usdDev > BigDecimal.ZERO
        var totalCounterDev = BigDecimal.ZERO
        val candidates = mutableListOf<String>()

        for ((symbol, d) in allDevs) {
            if (symbol.equals("USD", ignoreCase = true)) continue

            if (isDeposit && d < BigDecimal.ZERO) {
                candidates.add(symbol)
                totalCounterDev = totalCounterDev.add(d.abs())
            } else if (!isDeposit && d > BigDecimal.ZERO) {
                candidates.add(symbol)
                totalCounterDev = totalCounterDev.add(d)
            }
        }

        if (totalCounterDev.compareTo(BigDecimal.ZERO) == 0) {
            log.info("Fiat correction required but no suitable counter-balancing assets found.")
            return
        }

        log.info("Distributing Fiat Correction ($${deviationAbs.setScale(2, RoundingMode.HALF_UP)}) among ${candidates.size} candidates. Total Counter-Dev: $${totalCounterDev.setScale(2, RoundingMode.HALF_UP)}")
        actionLog.add("Distributing Fiat Correction ($${deviationAbs.setScale(2, RoundingMode.HALF_UP)}) among ${candidates.size} candidates.")

        for (symbol in candidates) {
            val assetDev = allDevs[symbol]!!.abs()
            val ratio = assetDev.divide(totalCounterDev, 8, RoundingMode.HALF_UP)
            val share = deviationAbs.multiply(ratio)

            if (isDeposit) {
                buyOrders[symbol] = share
            } else {
                sellOrders[symbol] = share
            }
        }
    }

    private fun mapToKrakenTicker(symbol: String): String {
        return when (symbol.uppercase()) {
            "BTC" -> "XBT"
            "DOGE" -> "XDG"
            else -> symbol
        }
    }

    internal fun getCurrentPrice(symbol: String, prices: Map<String, Double>): BigDecimal {
        val krakenSymbol = mapToKrakenTicker(symbol)
        for (k in prices.keys) {
            if (k.contains(krakenSymbol) && k.contains("USD")) {
                return BigDecimal.valueOf(prices[k]!!)
            }
        }
        return BigDecimal.ZERO
    }
}
