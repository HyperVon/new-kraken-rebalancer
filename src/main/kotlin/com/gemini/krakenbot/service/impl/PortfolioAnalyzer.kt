package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.repository.PortfolioStatsRepository
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.service.RawBalances
import com.gemini.krakenbot.service.RawPrices
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.pow

typealias AssetPrices = Map<String, BigDecimal>
typealias AssetValues = Map<String, BigDecimal>
typealias AssetDeviations = Map<String, BigDecimal>
typealias RebalanceOrders = Map<String, BigDecimal>
typealias MutableRebalanceOrders = MutableMap<String, BigDecimal>

data class PortfolioValues(
    val totalValueUSD: BigDecimal,
    val currentValuesUSD: AssetValues
)

data class AnalysisResult(
    val buyOrders: RebalanceOrders,
    val sellOrders: RebalanceOrders,
    val actionLog: List<String>
)

class PortfolioAnalyzer(
    private val krakenService: KrakenService,
    private val configService: ConfigService,
    private val portfolioStatsRepository: PortfolioStatsRepository
) {
    private val log = LoggerFactory.getLogger(PortfolioAnalyzer::class.java)

    suspend fun fetchBalances(): RawBalances {
        val balances = krakenService.getBalances()
        log.info("Available Balance Keys: {}", balances.keys)
        return balances
    }

    suspend fun fetchPrices(): AssetPrices {
        val allocations = configService.getConfig().allocations
        val nonUsd = allocations.filter { !it.symbol.isUsd }
        if (nonUsd.isEmpty()) return emptyMap()

        val pairs =
            nonUsd.joinToString(",") {
                it.symbol.tradingPair
            }
        val rawPrices = krakenService.getTickerPrices(pairs)

        return nonUsd.associate { (symbol, _) ->
            symbol.value to resolvePriceFromTicker(
                symbol.value,
                rawPrices
            )
        }
    }

    fun resolvePriceFromTicker(
        symbol: String,
        rawPrices: RawPrices
    ): BigDecimal {
        val expectedPair = Asset.tradingPair(symbol)
        rawPrices[expectedPair]?.let { return BigDecimal.valueOf(it) }

        val krakenTicker = Asset.toKrakenTicker(symbol)
        for ((key, value) in rawPrices) {
            if (key.contains(krakenTicker) &&
                key.contains(Asset.USD)
            ) {
                return BigDecimal.valueOf(value)
            }
        }
        return BigDecimal.ZERO
    }

    fun calculatePortfolioValues(
        balances: RawBalances,
        prices: AssetPrices
    ): PortfolioValues? {
        val currentValuesUSD = mutableMapOf<String, BigDecimal>()
        var totalPortfolioValueUSD = BigDecimal.ZERO

        for ((asset, _) in configService.getConfig().allocations) {
            val symbol = asset.value
            val balance = resolveBalance(symbol, balances)
            val bal = BigDecimal.valueOf(balance)
            var price = BigDecimal.ONE

            if (!asset.isUsd) {
                val p = prices[symbol] ?: BigDecimal.ZERO
                if (p.signum() == 0) {
                    log.error(
                        "Price not found for {}. Aborting rebalance cycle to prevent erroneous trades.",
                        symbol
                    )
                    return null
                }
                price = p
            }

            val valUSD = bal * price
            currentValuesUSD[symbol] = valUSD
            totalPortfolioValueUSD += valUSD
        }

        return PortfolioValues(totalPortfolioValueUSD, currentValuesUSD)
    }

    fun resolveBalance(symbol: String, balances: RawBalances): Double {
        return balances[symbol]
            ?: balances["X$symbol"]
            ?: balances["Z$symbol"]
            ?: balances[Asset.toKrakenTicker(symbol)]
            ?: balances["X${Asset.toKrakenTicker(symbol)}"]
            ?: 0.0
    }

    fun updateAthAndCalculateDrawdown(totalPortfolioValueUSD: BigDecimal): BigDecimal {
        val stats = portfolioStatsRepository.load()
        var ath = stats.allTimeHigh

        when {
            ath == null || ath <= BigDecimal.ZERO -> {
                ath = totalPortfolioValueUSD
                log.info(
                    "Initial ATH set to ${
                        ath.setScale(
                            2,
                            RoundingMode.HALF_UP
                        )
                    }"
                )
            }

            totalPortfolioValueUSD > ath -> {
                ath = totalPortfolioValueUSD
                log.info(
                    "New All-Time High detected: ${
                        ath.setScale(
                            2,
                            RoundingMode.HALF_UP
                        )
                    }"
                )
            }
        }

        stats.allTimeHigh = ath
        runCatching { portfolioStatsRepository.save(stats) }
            .onFailure { e -> log.error("Failed to persist portfolio ATH", e) }

        return if (ath > BigDecimal.ZERO && totalPortfolioValueUSD < ath) {
            val diff = ath - totalPortfolioValueUSD
            diff.divide(
                ath,
                4,
                RoundingMode.HALF_UP
            ) * BigDecimal.valueOf(100)
        } else {
            BigDecimal.ZERO
        }
    }

    fun calculateFiatDeployment(
        drawdownPct: BigDecimal,
        settings: Settings
    ): BigDecimal {
        if (settings.fiatMaxDrawdown <= 0.0) return BigDecimal.ZERO

        val maxDD = BigDecimal.valueOf(settings.fiatMaxDrawdown)
        var ratio = drawdownPct.divide(
            maxDD,
            4,
            RoundingMode.HALF_UP
        )
        ratio = ratio.coerceAtMost(BigDecimal.ONE)

        val deployDouble =
            ratio.toDouble().pow(settings.fiatDeploymentExponent) * 100.0
        return BigDecimal.valueOf(deployDouble)
    }

    fun calculateEffectiveUsdTarget(fiatDeploymentPct: BigDecimal): BigDecimal {
        val baseUsdTarget = configService.getConfig()
            .allocations
            .filter { it.symbol.isUsd }
            .sumOf { it.targetPercent.toBigDecimal() }

        return if (fiatDeploymentPct > BigDecimal.ZERO) {
            val factor = BigDecimal.ONE - fiatDeploymentPct.divide(
                BigDecimal.valueOf(100),
                4,
                RoundingMode.HALF_UP
            )
            baseUsdTarget * factor
        } else {
            baseUsdTarget
        }
    }

    fun calculateCryptoScaleFactor(effectiveUsdTarget: BigDecimal): BigDecimal {
        val totalNonUsdTarget = configService.getConfig()
            .allocations
            .filter { !it.symbol.isUsd }
            .sumOf { it.targetPercent.toBigDecimal() }

        val remainingForCrypto = BigDecimal.valueOf(100) - effectiveUsdTarget
        return if (totalNonUsdTarget > BigDecimal.ZERO) {
            remainingForCrypto.divide(
                totalNonUsdTarget,
                8,
                RoundingMode.HALF_UP
            )
        } else {
            BigDecimal.ONE
        }
    }

    fun analyzeDeviations(
        totalPortfolioValueUSD: BigDecimal,
        currentValuesUSD: AssetValues,
        effectiveUsdTarget: BigDecimal,
        cryptoScaleFactor: BigDecimal
    ): AnalysisResult {
        val buyOrders = mutableMapOf<String, BigDecimal>()
        val sellOrders = mutableMapOf<String, BigDecimal>()
        val actionLog = mutableListOf<String>()

        val s = configService.getConfig().settings
        var usdTriggered = false
        var usdDeviationAmount = BigDecimal.ZERO
        val allDeviations = mutableMapOf<String, BigDecimal>()

        configService.getConfig().allocations.forEach { (symbol, targetPercent) ->
            val symbolVal = symbol.value
            var targetPct = BigDecimal.valueOf(targetPercent)

            targetPct =
                if (symbol.isUsd) {
                    effectiveUsdTarget
                } else {
                    targetPct.multiply(cryptoScaleFactor)
                }

            targetPct = targetPct.divide(
                BigDecimal.valueOf(100),
                4,
                RoundingMode.HALF_UP
            )
            val targetValue =
                totalPortfolioValueUSD.multiply(targetPct)
            val currentVal = currentValuesUSD[symbolVal] ?: BigDecimal.ZERO

            val deviationUSD = currentVal.subtract(targetValue)
            var deviationPct = BigDecimal.ZERO

            if (targetValue > BigDecimal.ZERO) {
                deviationPct =
                    deviationUSD
                        .abs()
                        .divide(
                            targetValue,
                            4,
                            RoundingMode.HALF_UP
                        )
                        .multiply(BigDecimal.valueOf(100))
            } else if (currentVal > BigDecimal.ZERO) {
                deviationPct = BigDecimal.valueOf(100.0)
            }

            allDeviations[symbolVal] = deviationUSD

            log.info(
                "Analysis [{}]: Dev: {}% ($ {}). Threshold: {}%",
                symbolVal,
                deviationPct,
                deviationUSD.setScale(2, RoundingMode.HALF_UP),
                s.deviationTriggerPercent
            )

            val isDeviationSignificant =
                deviationUSD.abs() >= BigDecimal.valueOf(s.dustThresholdUSD)

            if (deviationPct.toDouble() >= s.deviationTriggerPercent && isDeviationSignificant) {
                actionLog.add(
                    "Deviation Triggered details: $symbolVal Dev: $deviationPct%"
                )
            }

            if (symbol.isUsd) {
                if (deviationPct.toDouble() >= s.deviationTriggerPercent && isDeviationSignificant) {
                    log.info(
                        "Asset USD Deviation: {}% (Trigger: {}%). USD Dev: {}",
                        deviationPct,
                        s.deviationTriggerPercent,
                        deviationUSD
                    )
                    usdTriggered = true
                    usdDeviationAmount = deviationUSD
                }
            } else {
                if (deviationPct.toDouble() >= s.deviationTriggerPercent && isDeviationSignificant) {
                    log.info(
                        "Asset {} Deviation: {}% (Trigger: {}%). USD Dev: {}",
                        symbolVal,
                        deviationPct,
                        s.deviationTriggerPercent,
                        deviationUSD
                    )

                    if (deviationUSD > BigDecimal.ZERO) {
                        sellOrders[symbolVal] = deviationUSD
                    } else {
                        buyOrders[symbolVal] = deviationUSD.abs()
                    }
                }
            }
        }

        if (buyOrders.isEmpty() && sellOrders.isEmpty() && usdTriggered) {
            log.info(
                "USD Deviation triggered but no individual asset triggers. " +
                        "Enforcing fiat correction."
            )
            actionLog.add("USD Deviation Triggered. Enforcing fiat correction.")
            distributeFiatCorrection(
                usdDev = usdDeviationAmount,
                allDevs = allDeviations,
                buyOrders = buyOrders,
                sellOrders = sellOrders,
                actionLog = actionLog
            )
        }

        return AnalysisResult(buyOrders, sellOrders, actionLog)
    }

    fun distributeFiatCorrection(
        usdDev: BigDecimal,
        allDevs: AssetDeviations,
        buyOrders: MutableRebalanceOrders,
        sellOrders: MutableRebalanceOrders,
        actionLog: MutableList<String>
    ) {
        val deviationAbs = usdDev.abs()
        val isDeposit = usdDev > BigDecimal.ZERO
        var totalCounterDev = BigDecimal.ZERO
        val candidates = mutableListOf<String>()

        for ((symbol, d) in allDevs) {
            if (symbol.equals(Asset.USD, ignoreCase = true)) continue

            if (isDeposit && d < BigDecimal.ZERO) {
                candidates.add(symbol)
                totalCounterDev = totalCounterDev.add(d.abs())
            } else if (!isDeposit && d > BigDecimal.ZERO) {
                candidates.add(symbol)
                totalCounterDev = totalCounterDev.add(d)
            }
        }

        if (totalCounterDev.signum() == 0) {
            log.info(
                "Fiat correction required but no suitable " +
                        "counter-balancing assets found."
            )
            return
        }

        log.info(
            "Distributing Fiat Correction ($${
                deviationAbs.setScale(
                    2,
                    RoundingMode.HALF_UP
                )
            }) among ${candidates.size} candidates. Total Counter-Dev: $${
                totalCounterDev.setScale(
                    2,
                    RoundingMode.HALF_UP
                )
            }"
        )
        actionLog.add(
            "Distributing Fiat Correction ($${
                deviationAbs.setScale(
                    2,
                    RoundingMode.HALF_UP
                )
            }) among ${candidates.size} candidates."
        )

        for (symbol in candidates) {
            val assetDev = allDevs[symbol]!!.abs()
            val ratio =
                assetDev.divide(
                    totalCounterDev,
                    8,
                    RoundingMode.HALF_UP
                )
            val share = deviationAbs.multiply(ratio)

            if (isDeposit) {
                buyOrders[symbol] = share
            } else {
                sellOrders[symbol] = share
            }
        }
    }
}
