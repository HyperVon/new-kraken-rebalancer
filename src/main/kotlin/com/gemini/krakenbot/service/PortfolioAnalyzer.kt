package com.gemini.krakenbot.service

import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.model.Result
import java.math.BigDecimal

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

interface PortfolioAnalyzer {
    suspend fun fetchBalances(): RawBalances
    suspend fun fetchPrices(): AssetPrices
    fun resolvePriceFromTicker(symbol: String, rawPrices: RawPrices): BigDecimal
    fun calculatePortfolioValues(balances: RawBalances, prices: AssetPrices): Result<PortfolioValues>
    fun resolveBalance(symbol: String, balances: RawBalances): BigDecimal
    fun updateAthAndCalculateDrawdown(totalPortfolioValueUSD: BigDecimal): BigDecimal
    fun calculateFiatDeployment(drawdownPct: BigDecimal, settings: Settings): BigDecimal
    fun calculateEffectiveUsdTarget(fiatDeploymentPct: BigDecimal): BigDecimal
    fun calculateCryptoScaleFactor(effectiveUsdTarget: BigDecimal): BigDecimal
    fun analyzeDeviations(
        totalPortfolioValueUSD: BigDecimal,
        currentValuesUSD: AssetValues,
        effectiveUsdTarget: BigDecimal,
        cryptoScaleFactor: BigDecimal
    ): AnalysisResult
    fun distributeFiatCorrection(
        usdDev: BigDecimal,
        allDevs: AssetDeviations,
        buyOrders: MutableRebalanceOrders,
        sellOrders: MutableRebalanceOrders,
        actionLog: MutableList<String>
    )
}
