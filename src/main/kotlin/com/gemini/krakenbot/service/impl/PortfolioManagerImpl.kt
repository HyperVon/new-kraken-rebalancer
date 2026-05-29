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
            totalPortfolioValueUSD, currentValuesUSD, effectiveUsdTarget, cryptoScaleFactor,
            buyOrders, sellOrders, actionLog
        )

        val s = configService.getConfig().settings
        executeOrders(buyOrders, sellOrders, currentValuesUSD, prices, s, actionLog)

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

    internal suspend fun fetchBalances(): Map<String, Double> {
        val balances = krakenService.getBalances()
        log.info("Available Balance Keys: {}", balances.keys)
        return balances
    }

    internal suspend fun fetchPrices(): Map<String, BigDecimal> {
        val allocations = configService.getConfig().allocations
        val nonUsd = allocations.filter { !it.symbol.equals(KrakenSymbols.USD, ignoreCase = true) }
        if (nonUsd.isEmpty()) return emptyMap()

        val pairs = nonUsd.joinToString(",") { KrakenSymbols.tradingPair(it.symbol) }
        val rawPrices = krakenService.getTickerPrices(pairs)

        return nonUsd.associate { allocation ->
            allocation.symbol to resolvePriceFromTicker(allocation.symbol, rawPrices)
        }
    }

    internal fun resolvePriceFromTicker(symbol: String, rawPrices: Map<String, Double>): BigDecimal {
        val expectedPair = KrakenSymbols.tradingPair(symbol)
        rawPrices[expectedPair]?.let { return BigDecimal.valueOf(it) }

        val krakenTicker = KrakenSymbols.toKrakenTicker(symbol)
        for ((key, value) in rawPrices) {
            if (key.contains(krakenTicker) && key.contains(KrakenSymbols.USD)) {
                return BigDecimal.valueOf(value)
            }
        }
        return BigDecimal.ZERO
    }

    private fun calculatePortfolioValues(
        balances: Map<String, Double>,
        prices: Map<String, BigDecimal>,
        currentValuesUSD: MutableMap<String, BigDecimal>
    ): BigDecimal? {
        var totalPortfolioValueUSD = BigDecimal.ZERO

        for (a in configService.getConfig().allocations) {
            val symbol = a.symbol
            val balance = resolveBalance(symbol, balances)
            val bal = BigDecimal.valueOf(balance)
            var price = BigDecimal.ONE

            if (!symbol.equals(KrakenSymbols.USD, ignoreCase = true)) {
                val p = prices[symbol] ?: BigDecimal.ZERO
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
            ?: balances[KrakenSymbols.toKrakenTicker(symbol)]
            ?: balances["X${KrakenSymbols.toKrakenTicker(symbol)}"]
            ?: 0.0
    }

    internal fun updateAthAndCalculateDrawdown(
        totalPortfolioValueUSD: BigDecimal
    ): BigDecimal {
        val stats = portfolioStatsRepository.load()
        var ath = stats.allTimeHigh

        if (ath == null || ath <= BigDecimal.ZERO) {
            ath = totalPortfolioValueUSD
            log.info("Initial ATH set to $${ath.setScale(2, RoundingMode.HALF_UP)}")
        } else if (totalPortfolioValueUSD > ath) {
            ath = totalPortfolioValueUSD
            log.info("New All-Time High detected: $${ath.setScale(2, RoundingMode.HALF_UP)}")
        }

        stats.allTimeHigh = ath
        try {
            portfolioStatsRepository.save(stats)
        } catch (e: IOException) {
            log.error("Failed to persist portfolio ATH", e)
        }

        return if (ath > BigDecimal.ZERO && totalPortfolioValueUSD < ath) {
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
            .filter { it.symbol.equals(KrakenSymbols.USD, ignoreCase = true) }
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
            .filter { !it.symbol.equals(KrakenSymbols.USD, ignoreCase = true) }
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

            targetPct = if (a.symbol.equals(KrakenSymbols.USD, ignoreCase = true)) {
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

            log.info(
                "Analysis [{}]: Dev: {}% ($ {}). Threshold: {}%",
                a.symbol, deviationPct, deviationUSD.setScale(2, RoundingMode.HALF_UP),
                s.deviationTriggerPercent
            )

            if (deviationPct.toDouble() >= s.deviationTriggerPercent) {
                actionLog.add("Deviation Triggered details: ${a.symbol} Dev: $deviationPct%")
            }

            if (a.symbol.equals(KrakenSymbols.USD, ignoreCase = true)) {
                if (deviationPct.toDouble() >= s.deviationTriggerPercent) {
                    log.info(
                        "Asset USD Deviation: {}% (Trigger: {}%). USD Dev: {}",
                        deviationPct, s.deviationTriggerPercent, deviationUSD
                    )
                    usdTriggered = true
                    usdDeviationAmount = deviationUSD
                }
            } else {
                if (deviationPct.toDouble() >= s.deviationTriggerPercent) {
                    log.info(
                        "Asset {} Deviation: {}% (Trigger: {}%). USD Dev: {}",
                        a.symbol, deviationPct, s.deviationTriggerPercent, deviationUSD
                    )

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
        prices: Map<String, BigDecimal>,
        s: Settings,
        actionLog: MutableList<String>
    ) {
        var projectedCash = currentValuesUSD[KrakenSymbols.USD] ?: BigDecimal.ZERO
        var executedSells = false

        for ((symbol, usdToSell) in sellOrders) {
            if (usdToSell < BigDecimal.valueOf(s.dustThresholdUSD)) {
                log.info("Skipping dust sell for {} ($ {})", symbol, usdToSell)
                actionLog.add("Skipping dust sell for $symbol ($$usdToSell)")
                continue
            }

            val price = prices[symbol] ?: BigDecimal.ZERO
            if (price.compareTo(BigDecimal.ZERO) == 0) continue

            val volume = usdToSell.divide(price, 8, RoundingMode.HALF_UP)
            val pair = KrakenSymbols.tradingPair(symbol)
            val result = krakenService.executeOrder(pair, "market", "sell", volume)
            logOrderResult(result, actionLog, symbol, volume, usdToSell, "SELL")
            if (result.success) {
                projectedCash = projectedCash.add(usdToSell)
                executedSells = true
            }
        }

        var actualCash = projectedCash
        if (executedSells && !s.dryRun) {
            actualCash = refreshUsdBalanceAfterSells(projectedCash)
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

            val price = prices[symbol] ?: BigDecimal.ZERO
            if (price.compareTo(BigDecimal.ZERO) == 0) continue

            val volume = cost.divide(price, 8, RoundingMode.HALF_UP)
            val pair = KrakenSymbols.tradingPair(symbol)
            val result = krakenService.executeOrder(pair, "market", "buy", volume)
            logOrderResult(result, actionLog, symbol, volume, cost, "BUY")
            if (result.success) {
                actualCash = actualCash.subtract(cost)
            }
        }
    }

    private suspend fun refreshUsdBalanceAfterSells(projectedCash: BigDecimal): BigDecimal {
        val maxAttempts = 3
        val delayMs = 250L
        var bestCash = projectedCash

        repeat(maxAttempts) { attempt ->
            delay(delayMs.milliseconds)
            try {
                val updatedBalances = krakenService.getBalances()
                if (updatedBalances.isNotEmpty()) {
                    val usdBalance = resolveBalance(KrakenSymbols.USD, updatedBalances)
                    if (usdBalance > 0) {
                        bestCash = BigDecimal.valueOf(usdBalance)
                        log.info("Updated USD balance after sells (attempt {}): $${bestCash}", attempt + 1)
                        if (bestCash >= projectedCash.multiply(BigDecimal("0.95"))) {
                            return bestCash
                        }
                    }
                }
            } catch (e: Exception) {
                log.warn("Failed to fetch updated USD balance (attempt {})", attempt + 1, e)
            }
        }

        log.warn("Using best observed USD balance after sell refresh: $${bestCash}")
        return bestCash
    }

    private fun logOrderResult(
        result: com.gemini.krakenbot.model.OrderResult,
        actionLog: MutableList<String>,
        symbol: String,
        volume: BigDecimal,
        usdAmount: BigDecimal,
        side: String
    ) {
        if (result.success) {
            val prefix = if (result.dryRun) "[DRY RUN] " else ""
            if (side == "SELL") {
                actionLog.add("${prefix}SELL $symbol Volume: $volume Value: $$usdAmount")
            } else {
                actionLog.add("${prefix}BUY $symbol Volume: $volume Cost: $$usdAmount")
            }
        } else {
            actionLog.add("FAILED $side $symbol: ${result.errorMessage}")
        }
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
        val assetSnapshots = mutableMapOf<String, PortfolioSnapshot.AssetSnapshot>()

        for (a in configService.getConfig().allocations) {
            val symbol = a.symbol
            val balance = BigDecimal.valueOf(resolveBalance(symbol, balances))
            val valUSD = currentValuesUSD[symbol] ?: BigDecimal.ZERO
            val price = if (!symbol.equals(KrakenSymbols.USD, ignoreCase = true)) {
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
                currentPct = valUSD.divide(totalPortfolioValueUSD, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
            }

            val targetVal = totalPortfolioValueUSD.multiply(calcTargetPct)
                .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP)
            val deviationUSD = valUSD.subtract(targetVal)
            var devPct = BigDecimal.ZERO

            if (targetVal > BigDecimal.ZERO) {
                devPct = deviationUSD.divide(targetVal, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
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
            if (symbol.equals(KrakenSymbols.USD, ignoreCase = true)) continue

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

        log.info(
            "Distributing Fiat Correction ($${deviationAbs.setScale(2, RoundingMode.HALF_UP)}) among ${candidates.size} candidates. Total Counter-Dev: $${totalCounterDev.setScale(2, RoundingMode.HALF_UP)}"
        )
        actionLog.add(
            "Distributing Fiat Correction ($${deviationAbs.setScale(2, RoundingMode.HALF_UP)}) among ${candidates.size} candidates."
        )

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
}
