package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.model.Asset

object SimulationDefaults {
    val INITIAL_PRICES = mapOf(
        Asset.BTC to 60000.0,
        Asset.ETH to 3000.0,
        Asset.USD to 1.0,
        Asset.USDT to 1.0,
        Asset.USDC to 1.0,
        Asset.DOGE to 0.15,
        Asset.SOL to 140.0,
        Asset.ADA to 0.50,
        Asset.XRP to 0.60,
        Asset.DOT to 6.0,
        Asset.LINK to 15.0,
        Asset.LTC to 80.0
    )
}
