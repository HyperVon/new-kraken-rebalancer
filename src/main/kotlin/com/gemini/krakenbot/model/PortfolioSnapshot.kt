package com.gemini.krakenbot.model

import java.math.BigDecimal
import java.time.Instant

data class PortfolioSnapshot(
    var timestamp: Instant? = null,
    var totalValueUSD: BigDecimal? = null,
    var assets: Map<String, AssetSnapshot>? = null,
    var actions: List<String>? = null,
    var drawdownPercent: BigDecimal? = null,
    var fiatDeploymentPercent: BigDecimal? = null,
    var effectiveUsdTargetPercent: BigDecimal? = null
) {
    data class AssetSnapshot(
        var symbol: String? = null,
        var balance: BigDecimal? = null,
        var price: BigDecimal? = null,
        var valueUSD: BigDecimal? = null,
        var targetPercent: BigDecimal? = null,
        var currentPercent: BigDecimal? = null,
        var deviationPercent: BigDecimal? = null,
        var deviationUSD: BigDecimal? = null
    )
}
