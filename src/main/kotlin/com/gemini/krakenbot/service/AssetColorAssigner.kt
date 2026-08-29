package com.gemini.krakenbot.service

import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.view.util.ChartProps
import kotlin.math.abs

object AssetColorAssigner {

    private val knownDefaults = mapOf(
        Asset.BTC to ChartProps.SOLID_BTC,
        Asset.ETH to ChartProps.SOLID_ETH,
        Asset.USD to ChartProps.SOLID_USD,
    )

    private val HEX_COLOR = Regex("^#[0-9a-f]{6}$")

    /** Returns lowercase `#rrggbb`, or null if blank/invalid. */
    fun normalizeHex(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        val withHash = if (trimmed.startsWith("#")) trimmed else "#$trimmed"
        val normalized = withHash.lowercase()
        return normalized.takeIf { HEX_COLOR.matches(it) }
    }

    fun assignMissingColors(allocations: List<Allocation>): List<Allocation> {
        val normalized = allocations.map { alloc ->
            alloc.copy(color = normalizeHex(alloc.color))
        }
        val usedColors = normalized.mapNotNull { it.color }.toMutableSet()
        return normalized.map { alloc ->
            if (alloc.color != null) return@map alloc
            val symbol = alloc.symbol.value.uppercase()
            val known = knownDefaults[symbol]
            if (known != null && known !in usedColors) {
                usedColors.add(known)
                return@map alloc.copy(color = known)
            }
            val color = generateColor(symbol, usedColors)
            usedColors.add(color)
            alloc.copy(color = color)
        }
    }

    private fun generateColor(symbol: String, usedColors: MutableSet<String>): String {
        var hue = (symbol.hashCode().toLong() * GOLDEN_RATIO_CONJUGATE)
            .let { ((it % 360) + 360) % 360 }
            .toInt()
        for (attempt in 0 until MAX_ATTEMPTS) {
            val slPair = slValues[attempt % slValues.size]
            val hex = hslToHex(hue, slPair.first, slPair.second)
            if (hex !in usedColors) return hex
            hue = (hue + 47) % 360
        }
        return hslToHex(hue, slValues.first().first, slValues.first().second)
    }

    private val slValues = listOf(
        70 to 55,
        60 to 65,
        80 to 45,
        50 to 70,
        90 to 35,
    )

    private fun hslToHex(h: Int, s: Int, l: Int): String {
        val normalized = h.toFloat() / 360
        val sFrac = s.toFloat() / 100
        val lFrac = l.toFloat() / 100
        val c = (1 - abs(2 * lFrac - 1)) * sFrac
        val x = c * (1 - abs((normalized * 6) % 2 - 1))
        val m = lFrac - c / 2
        val (r, g, b) = when {
            normalized < 1.0 / 6 -> Triple(c, x, 0f)
            normalized < 2.0 / 6 -> Triple(x, c, 0f)
            normalized < 3.0 / 6 -> Triple(0f, c, x)
            normalized < 4.0 / 6 -> Triple(0f, x, c)
            normalized < 5.0 / 6 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val ri = ((r + m) * 255).toInt().coerceIn(0, 255)
        val gi = ((g + m) * 255).toInt().coerceIn(0, 255)
        val bi = ((b + m) * 255).toInt().coerceIn(0, 255)
        return "#%02x%02x%02x".format(ri, gi, bi)
    }

    private const val GOLDEN_RATIO_CONJUGATE = 0.618033988749895
    private const val MAX_ATTEMPTS = 360
}
