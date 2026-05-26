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
        val symbol: String,
        val balance: BigDecimal,
        val price: BigDecimal,
        val valueUSD: BigDecimal,
        val targetPercent: BigDecimal,
        val currentPercent: BigDecimal,
        val deviationPercent: BigDecimal,
        val deviationUSD: BigDecimal
    )
}
