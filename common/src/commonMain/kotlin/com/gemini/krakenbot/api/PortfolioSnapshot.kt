package com.gemini.krakenbot.api

/** History `/api/history/snapshots` JSON element — decimal and timestamp fields are strings. */
data class PortfolioSnapshot(
    val timestamp: String,
    val totalValueUSD: String,
    val assets: Map<String, AssetSnapshot>,
    val actions: List<String>,
    val drawdownPercent: String,
    val fiatDeploymentPercent: String,
    val effectiveUsdTargetPercent: String,
) {
    data class AssetSnapshot(
        val symbol: String,
        val balance: String,
        val price: String,
        val valueUSD: String,
        val targetPercent: String,
        val currentPercent: String,
        val deviationPercent: String,
        val deviationUSD: String,
    )
}
