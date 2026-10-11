package com.gemini.krakenbot.service

import com.gemini.krakenbot.domain.AssetPrices
import com.gemini.krakenbot.domain.AssetValues
import com.gemini.krakenbot.domain.PortfolioValues
import com.gemini.krakenbot.domain.RawBalances
import com.gemini.krakenbot.domain.RawPrices
import com.gemini.krakenbot.domain.RebalancePlan
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.Result
import java.math.BigDecimal
import java.time.Instant

data class ObservedBalances(
    val balances: RawBalances,
    val observedAt: Instant = Instant.now(),
    val directCapture: DirectBalanceCapture? = null,
)

data class ObservedPrices(val prices: AssetPrices, val directCapture: DirectTickerCapture? = null)

interface PortfolioAnalyzer {
    suspend fun fetchBalances(): RawBalances

    suspend fun fetchObservedBalances(): ObservedBalances

    suspend fun fetchPrices(): AssetPrices

    /** Direct exchange marks, when the selected backend supports authenticated/live evidence. */
    suspend fun fetchObservedPrices(): ObservedPrices = ObservedPrices(fetchPrices())

    fun resolvePriceFromTicker(symbol: String, rawPrices: RawPrices): BigDecimal

    fun calculatePortfolioValues(balances: RawBalances, prices: AssetPrices): Result<PortfolioValues>

    fun resolveBalance(symbol: String, balances: RawBalances): BigDecimal

    fun analyzeDeviations(
        totalPortfolioValueUSD: BigDecimal,
        currentValuesUSD: AssetValues,
        effectiveUsdTarget: BigDecimal,
        cryptoScaleFactor: BigDecimal,
    ): RebalancePlan

    fun buildSnapshot(
        balances: RawBalances,
        prices: AssetPrices,
        currentValuesUSD: AssetValues,
        totalPortfolioValueUSD: BigDecimal,
        effectiveUsdTarget: BigDecimal,
        cryptoScaleFactor: BigDecimal,
        actionLog: List<String>,
        balancesObservedAt: Instant = Instant.now(),
    ): PortfolioSnapshot
}
