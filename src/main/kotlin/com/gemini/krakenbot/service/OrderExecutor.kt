package com.gemini.krakenbot.service

import com.gemini.krakenbot.config.Settings
import java.math.BigDecimal

interface OrderExecutor {
    suspend fun executeOrders(
        buyOrders: RebalanceOrders,
        sellOrders: RebalanceOrders,
        currentValuesUSD: AssetValues,
        prices: AssetPrices,
        settings: Settings,
        actionLog: MutableList<String>
    )
}
