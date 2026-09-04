package com.gemini.krakenbot.service

import com.gemini.krakenbot.config.Settings
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

data class ObservedBalances(val balances: RawBalances, val observedAt: Instant = Instant.now())

/**
 * Outcome of an ATH/drawdown update.
 *
 * A balance observation that postdates confirmed owner-capital ledger coverage
 * may already contain capital the ledger window has not seen yet. Such a
 * balance must neither establish a new ATH nor drive drawdown-based fiat
 * deployment, so the update comes back [Deferred] with the last trustworthy
 * drawdown preserved and no state written.
 */
sealed interface AthUpdateResult {
    data class Trusted(val drawdownPct: BigDecimal) : AthUpdateResult

    data class Deferred(val lastTrustedDrawdownPct: BigDecimal?) : AthUpdateResult
}

interface PortfolioAnalyzer {
    suspend fun fetchBalances(): RawBalances

    suspend fun fetchObservedBalances(): ObservedBalances

    suspend fun fetchPrices(): AssetPrices

    fun resolvePriceFromTicker(symbol: String, rawPrices: RawPrices): BigDecimal

    fun calculatePortfolioValues(balances: RawBalances, prices: AssetPrices): Result<PortfolioValues>

    fun resolveBalance(symbol: String, balances: RawBalances): BigDecimal

    suspend fun updateAthAndCalculateDrawdown(totalPortfolioValueUSD: BigDecimal): BigDecimal =
        updateAthAndCalculateDrawdown(totalPortfolioValueUSD, BigDecimal.ZERO)

    suspend fun updateAthAndCalculateDrawdown(
        totalPortfolioValueUSD: BigDecimal,
        netExternalFlowUSD: BigDecimal,
    ): BigDecimal = // A null observation never trips the coverage gate, so deferral is
        // impossible here; the cast fails loudly if that invariant breaks.
        (updateAthAndCalculateDrawdown(totalPortfolioValueUSD, netExternalFlowUSD, null) as AthUpdateResult.Trusted)
            .drawdownPct

    suspend fun updateAthAndCalculateDrawdown(
        totalPortfolioValueUSD: BigDecimal,
        netExternalFlowUSD: BigDecimal,
        balancesObservedAt: Instant?,
    ): AthUpdateResult

    fun calculateFiatDeployment(drawdownPct: BigDecimal, settings: Settings): BigDecimal

    fun calculateEffectiveUsdTarget(fiatDeploymentPct: BigDecimal): BigDecimal

    fun calculateCryptoScaleFactor(effectiveUsdTarget: BigDecimal): BigDecimal

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
        drawdownPct: BigDecimal,
        fiatDeploymentPct: BigDecimal,
        actionLog: List<String>,
        balancesObservedAt: Instant = Instant.now(),
    ): PortfolioSnapshot
}
