package com.gemini.krakenbot.config

import com.gemini.krakenbot.model.Asset

data class Allocation(
    val symbol: Asset,
    val targetPercent: Double
) {
    companion object {
        operator fun invoke(symbol: String, targetPercent: Double): Allocation =
            Allocation(Asset(symbol), targetPercent)
    }
}
