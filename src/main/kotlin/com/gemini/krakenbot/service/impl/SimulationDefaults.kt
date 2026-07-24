package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.model.Asset
import java.math.BigDecimal

object SimulationDefaults {
    val DEFAULT_PRICE: BigDecimal = BigDecimal("10")
    val TOTAL_PORTFOLIO_VALUE_USD: BigDecimal = BigDecimal("100000.00")

    val INITIAL_PRICES: Map<String, BigDecimal> =
        mapOf(
            Asset.BTC to BigDecimal("60000"),
            Asset.ETH to BigDecimal("3000"),
            Asset.USD to BigDecimal.ONE,
            Asset.USDT to BigDecimal.ONE,
            Asset.USDC to BigDecimal.ONE,
            Asset.DOGE to BigDecimal("0.15"),
            Asset.SOL to BigDecimal("140"),
            Asset.ADA to BigDecimal("0.50"),
            Asset.XRP to BigDecimal("0.60"),
            Asset.DOT to BigDecimal("6"),
            Asset.LINK to BigDecimal("15"),
            Asset.LTC to BigDecimal("80"),
        )
}
