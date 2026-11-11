package com.gemini.krakenbot.view.util

import com.gemini.krakenbot.model.Asset

private class SymbolColors(val btc: String, val eth: String, val usd: String, val fallbackPalette: Array<String>)

private val SOLID_COLORS =
    SymbolColors(
        btc = ChartProps.SOLID_BTC,
        eth = ChartProps.SOLID_ETH,
        usd = ChartProps.SOLID_USD,
        fallbackPalette =
        arrayOf(
            ChartProps.SOLID_BLUE,
            ChartProps.SOLID_EMERALD,
            ChartProps.SOLID_AMBER,
            ChartProps.SOLID_VIOLET,
            ChartProps.SOLID_RED,
            ChartProps.SOLID_TEAL,
            ChartProps.SOLID_ORANGE,
            ChartProps.SOLID_FUCHSIA,
        ),
    )

private fun colorForSymbol(symbol: String, fallbackIndex: Int, colors: SymbolColors): String =
    when (symbol.uppercase()) {
        Asset.BTC -> colors.btc
        Asset.ETH -> colors.eth
        Asset.USD -> colors.usd
        else -> colors.fallbackPalette[fallbackIndex.mod(colors.fallbackPalette.size)]
    }

/** Default per-asset chart colors; Settings-stored colors override when present. */
fun solidColorForSymbol(symbol: String, fallbackIndex: Int = 0): String =
    colorForSymbol(symbol, fallbackIndex, SOLID_COLORS)
