package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.model.PortfolioStats
import com.gemini.krakenbot.repository.PortfolioStatsRepository
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.util.KrakenSymbols
import org.slf4j.LoggerFactory
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.pow

class PortfolioAnalyzer(
    private val krakenService: KrakenService,
    private val configService: ConfigService,
    private val portfolioStatsRepository: PortfolioStatsRepository
) {
    private val log = LoggerFactory.getLogger(PortfolioAnalyzer::class.java)

    suspend fun fetchBalances(): Map<String, Double> {
        val balances = krakenService.getBalances()
        log.info("Available Balance Keys: {}", balances.keys)
        return balances
    }

    suspend fun fetchPrices(): Map<String, BigDecimal> {
        val allocations = configService.getConfig().allocations
        val nonUsd = allocations.filter { !it.symbol.equals(KrakenSymbols.USD, ignoreCase = true) }
        if (nonUsd.isEmpty()) return emptyMap()

        val pairs = nonUsd.joinToString(",") { KrakenSymbols.tradingPair(it.symbol) }
        val rawPrices = krakenService.getTickerPrices(pairs)

        return nonUsd.associate { allocation ->
            allocation.symbol to resolvePriceFromTicker(allocation.symbol, rawPrices)
        }
    }

    fun resolvePriceFromTicker(symbol: String, rawPrices: Map<String, Double>): BigDecimal {
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

    fun calculatePortfolioValues(
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

    fun resolveBalance(symbol: String, balances: Map<String, Double>): Double {
        return balances[symbol]
            ?: balances["X$symbol"]
            ?: balances["Z$symbol"]
            ?: balances[KrakenSymbols.toKrakenTicker(symbol)]
            ?: balances["X${KrakenSymbols.toKrakenTicker(symbol)}"]
            ?: 0.0
    }

    fun updateAthAndCalculateDrawdown(totalPortfolioValueUSD: BigDecimal): BigDecimal {
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

    fun calculateFiatDeployment(drawdownPct: BigDecimal, settings: Settings): BigDecimal {
        if (settings.fiatMaxDrawdown <= 0.0) return BigDecimal.ZERO

        val maxDD = BigDecimal.valueOf(settings.fiatMaxDrawdown)
        var ratio = drawdownPct.divide(maxDD, 4, RoundingMode.HALF_UP)
        if (ratio > BigDecimal.ONE) ratio = BigDecimal.ONE

        val deployDouble = ratio.toDouble().pow(settings.fiatDeploymentExponent) * 100.0
        return BigDecimal.valueOf(deployDouble)
    }

    fun calculateEffectiveUsdTarget(fiatDeploymentPct: BigDecimal): BigDecimal {
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

    fun calculateCryptoScaleFactor(effectiveUsdTarget: BigDecimal): BigDecimal {
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
}
