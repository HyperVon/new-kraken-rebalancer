package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.view.util.ChartProps
import com.gemini.krakenbot.view.util.backgroundColorForSymbol
import com.gemini.krakenbot.view.util.borderColorForSymbol

private val assetColorMap: Map<String, String> by lazy {
    val global = js("window.${ChartProps.ASSET_COLORS_GLOBAL_KEY}")
    if (global != null && global != undefined) {
        val map = mutableMapOf<String, String>()
        val keys: Array<String> = js("Object.keys(${ChartProps.ASSET_COLORS_GLOBAL_KEY})")
        for (key in keys) {
            val v: String = js("${ChartProps.ASSET_COLORS_GLOBAL_KEY}[key]")
            map[key] = v
        }
        map
    } else {
        emptyMap()
    }
}

internal fun colorForSymbol(symbol: String, fallbackIndex: Int): String =
    assetColorMap[symbol.uppercase()] ?: borderColorForSymbol(symbol, fallbackIndex)

internal fun bgColorForSymbol(symbol: String, fallbackIndex: Int): String {
    val solid = assetColorMap[symbol.uppercase()]
    if (solid != null) {
        return hexToRgba(solid, 0.1) ?: backgroundColorForSymbol(symbol, fallbackIndex)
    }
    return backgroundColorForSymbol(symbol, fallbackIndex)
}

internal fun hexToRgba(hex: String, alpha: Double): String? {
    val clean = hex.removePrefix("#")
    if (clean.length != 6) return null
    val r = clean.substring(0, 2).toIntOrNull(16) ?: return null
    val g = clean.substring(2, 4).toIntOrNull(16) ?: return null
    val b = clean.substring(4, 6).toIntOrNull(16) ?: return null
    return "rgba($r, $g, $b, $alpha)"
}
