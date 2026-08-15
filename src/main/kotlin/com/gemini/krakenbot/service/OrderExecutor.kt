package com.gemini.krakenbot.service

import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.domain.AssetPrices
import com.gemini.krakenbot.domain.AssetValues
import com.gemini.krakenbot.domain.RawBalances
import com.gemini.krakenbot.domain.RebalanceOrders

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
