package com.gemini.krakenbot.service

import com.gemini.krakenbot.config.Settings

interface OrderExecutor {
    suspend fun executeOrders(
        buyOrders: RebalanceOrders,
        sellOrders: RebalanceOrders,
        currentValuesUSD: AssetValues,
        prices: AssetPrices,
        settings: Settings,
        actionLog: MutableList<String>,
        cycleId: String = "",
        availableBalances: RawBalances? = null,
    )
}
