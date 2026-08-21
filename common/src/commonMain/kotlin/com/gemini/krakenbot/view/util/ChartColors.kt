package com.gemini.krakenbot.view.util

import com.gemini.krakenbot.model.Asset

val ChartProps.PALETTE_BORDER_COLORS: Array<String>
    get() =
        arrayOf(
            ChartProps.COLOR_BLUE,
            ChartProps.COLOR_EMERALD,
            ChartProps.COLOR_AMBER,
            ChartProps.COLOR_VIOLET,
            ChartProps.COLOR_RED,
            ChartProps.COLOR_TEAL,
            ChartProps.COLOR_ORANGE,
            ChartProps.COLOR_FUCHSIA,
        )

val ChartProps.PALETTE_BG_COLORS: Array<String>
    get() =
        arrayOf(
            ChartProps.COLOR_BLUE_BG_PALETTE,
            ChartProps.COLOR_EMERALD_BG_PALETTE,
            ChartProps.COLOR_AMBER_BG_PALETTE,
            ChartProps.COLOR_VIOLET_BG_PALETTE,
            ChartProps.COLOR_RED_BG_PALETTE,
            ChartProps.COLOR_TEAL_BG_PALETTE,
            ChartProps.COLOR_ORANGE_BG_PALETTE,
            ChartProps.COLOR_FUCHSIA_BG_PALETTE,
        )

val ChartProps.SOLID_FALLBACK_PALETTE: Array<String>
    get() =
        arrayOf(
            ChartProps.SOLID_BLUE,
            ChartProps.SOLID_EMERALD,
            ChartProps.SOLID_AMBER,
            ChartProps.SOLID_VIOLET,
            ChartProps.SOLID_RED,
            ChartProps.SOLID_TEAL,
            ChartProps.SOLID_ORANGE,
            ChartProps.SOLID_FUCHSIA,
        )

private class SymbolColors(val btc: String, val eth: String, val usd: String, val fallbackPalette: Array<String>)

private val BORDER_COLORS =
    SymbolColors(
        btc = ChartProps.COLOR_AMBER,
        eth = ChartProps.COLOR_VIOLET,
        usd = ChartProps.COLOR_SLATE,
        fallbackPalette =
        arrayOf(
            ChartProps.COLOR_BLUE,
            ChartProps.COLOR_EMERALD,
            ChartProps.COLOR_AMBER,
            ChartProps.COLOR_VIOLET,
            ChartProps.COLOR_RED,
            ChartProps.COLOR_TEAL,
            ChartProps.COLOR_ORANGE,
            ChartProps.COLOR_FUCHSIA,
        ),
    )

private val BG_COLORS =
    SymbolColors(
        btc = ChartProps.COLOR_AMBER_BG_PALETTE,
        eth = ChartProps.COLOR_VIOLET_BG_PALETTE,
        usd = ChartProps.COLOR_SLATE_BG_PALETTE,
        fallbackPalette =
        arrayOf(
            ChartProps.COLOR_BLUE_BG_PALETTE,
            ChartProps.COLOR_EMERALD_BG_PALETTE,
            ChartProps.COLOR_AMBER_BG_PALETTE,
            ChartProps.COLOR_VIOLET_BG_PALETTE,
            ChartProps.COLOR_RED_BG_PALETTE,
            ChartProps.COLOR_TEAL_BG_PALETTE,
            ChartProps.COLOR_ORANGE_BG_PALETTE,
            ChartProps.COLOR_FUCHSIA_BG_PALETTE,
        ),
    )

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
fun ChartProps.borderColorForSymbol(symbol: String, fallbackIndex: Int = 0): String =
    colorForSymbol(symbol, fallbackIndex, BORDER_COLORS)

fun ChartProps.backgroundColorForSymbol(symbol: String, fallbackIndex: Int = 0): String =
    colorForSymbol(symbol, fallbackIndex, BG_COLORS)

fun ChartProps.solidColorForSymbol(symbol: String, fallbackIndex: Int = 0): String =
    colorForSymbol(symbol, fallbackIndex, SOLID_COLORS)
