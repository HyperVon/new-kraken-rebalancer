package com.gemini.krakenbot.model

import java.math.BigDecimal
import java.time.Instant

data class PortfolioSnapshot(
    val timestamp: Instant,
    val totalValueUSD: BigDecimal,
    val assets: Map<String, AssetSnapshot>,
    val actions: List<String>,
    val drawdownPercent: BigDecimal,
    val fiatDeploymentPercent: BigDecimal,
    val effectiveUsdTargetPercent: BigDecimal
) {
    data class AssetSnapshot(
        val symbol: Asset,
        val balance: BigDecimal,
        val price: BigDecimal,
        val valueUSD: BigDecimal,
        val targetPercent: BigDecimal,
        val currentPercent: BigDecimal,
        val deviationPercent: BigDecimal,
        val deviationUSD: BigDecimal
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
                deviationUSD: BigDecimal
            ): AssetSnapshot = AssetSnapshot(
                Asset(symbol),
                balance,
                price,
                valueUSD,
                targetPercent,
                currentPercent,
                deviationPercent,
                deviationUSD
            )
        }
    }
}
