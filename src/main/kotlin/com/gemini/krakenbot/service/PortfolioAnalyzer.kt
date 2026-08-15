package com.gemini.krakenbot.service

import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.domain.AnalysisResult
import com.gemini.krakenbot.domain.AssetDeviations
import com.gemini.krakenbot.domain.AssetPrices
import com.gemini.krakenbot.domain.AssetValues
import com.gemini.krakenbot.domain.MutableRebalanceOrders
import com.gemini.krakenbot.domain.PortfolioValues
import com.gemini.krakenbot.domain.RawBalances
import com.gemini.krakenbot.domain.RawPrices
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.Result
import java.math.BigDecimal

interface PortfolioAnalyzer {
    suspend fun fetchBalances(): RawBalances

    suspend fun fetchPrices(): AssetPrices

    fun resolvePriceFromTicker(symbol: String, rawPrices: RawPrices): BigDecimal

    fun calculatePortfolioValues(balances: RawBalances, prices: AssetPrices): Result<PortfolioValues>

    fun resolveBalance(symbol: String, balances: RawBalances): BigDecimal

    suspend fun updateAthAndCalculateDrawdown(totalPortfolioValueUSD: BigDecimal): BigDecimal

    fun calculateFiatDeployment(drawdownPct: BigDecimal, settings: Settings): BigDecimal

    fun calculateEffectiveUsdTarget(fiatDeploymentPct: BigDecimal): BigDecimal

    fun calculateCryptoScaleFactor(effectiveUsdTarget: BigDecimal): BigDecimal

    fun analyzeDeviations(
        totalPortfolioValueUSD: BigDecimal,
        currentValuesUSD: AssetValues,
        effectiveUsdTarget: BigDecimal,
        cryptoScaleFactor: BigDecimal,
    ): AnalysisResult

    fun distributeFiatCorrection(
        usdDev: BigDecimal,
        allDevs: AssetDeviations,
        buyOrders: MutableRebalanceOrders,
        sellOrders: MutableRebalanceOrders,
        actionLog: MutableList<String>,
    )

    fun buildSnapshot(
        balances: RawBalances,
        prices: AssetPrices,
        currentValuesUSD: AssetValues,
        totalPortfolioValueUSD: BigDecimal,
        effectiveUsdTarget: BigDecimal,
        cryptoScaleFactor: BigDecimal,
        drawdownPct: BigDecimal,
        fiatDeploymentPct: BigDecimal,
        actionLog: List<String>,
    ): PortfolioSnapshot
}
