package com.gemini.krakenbot.model

import com.gemini.krakenbot.codegen.GenerateApiMapper
import java.math.BigDecimal
import java.time.Instant
import com.gemini.krakenbot.api.PortfolioSnapshot as ApiPortfolioSnapshot

@GenerateApiMapper(ApiPortfolioSnapshot::class)
data class PortfolioSnapshot(
    val timestamp: Instant,
    val totalValueUSD: BigDecimal,
    val assets: Map<String, AssetSnapshot>,
    val actions: List<String>,
    val drawdownPercent: BigDecimal,
    val fiatDeploymentPercent: BigDecimal,
    val effectiveUsdTargetPercent: BigDecimal,
    val balancesObservedAt: Instant = timestamp,
) {
    @GenerateApiMapper(ApiPortfolioSnapshot.AssetSnapshot::class)
    data class AssetSnapshot(
        val symbol: Asset,
        val balance: BigDecimal,
        val price: BigDecimal,
        val valueUSD: BigDecimal,
        val targetPercent: BigDecimal,
        val currentPercent: BigDecimal,
        val deviationPercent: BigDecimal,
        val deviationUSD: BigDecimal,
    ) {
        companion object {
            operator fun invoke(
                symbol: String,
                balance: BigDecimal,
                price: BigDecimal,
                valueUSD: BigDecimal,
                targetPercent: BigDecimal,
                currentPercent: BigDecimal,
                deviationPercent: BigDecimal,
                deviationUSD: BigDecimal,
            ): AssetSnapshot = AssetSnapshot(
                symbol = Asset(symbol),
                balance = balance,
                price = price,
                valueUSD = valueUSD,
                targetPercent = targetPercent,
                currentPercent = currentPercent,
                deviationPercent = deviationPercent,
                deviationUSD = deviationUSD,
            )
        }
    }
}
