package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.service.AssetPrices
import com.gemini.krakenbot.service.KrakenService

/**
 * Encapsulates execution state and pinned dependencies for a single rebalance cycle.
 */
data class RebalanceSessionContext(
    val cycleId: String,
    val backend: KrakenService,
    val prices: AssetPrices,
    val settings: Settings,
    val actionLog: MutableList<String>,
    val cycleTradeIds: MutableList<Int>,
)
