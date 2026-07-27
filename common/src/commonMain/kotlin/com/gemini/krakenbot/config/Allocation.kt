package com.gemini.krakenbot.config

import com.gemini.krakenbot.model.Asset

data class Allocation(val symbol: Asset, val targetPercent: Double, val color: String? = null) {
    companion object {
        operator fun invoke(symbol: String, targetPercent: Double, color: String? = null): Allocation =
            Allocation(Asset(symbol), targetPercent, color)
    }
}
