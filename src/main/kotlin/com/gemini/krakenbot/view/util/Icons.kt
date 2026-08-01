package com.gemini.krakenbot.view.util

import kotlinx.html.HTMLTag
import kotlinx.html.unsafe

object Icons {
    private fun loadIcon(name: String): String = Icons::class.java.getResource("/icons/$name")?.readText() ?: ""

    val COG = loadIcon("cog.svg")
    val TREND_UP = loadIcon("trend_up.svg")
    val WALLET = loadIcon("wallet.svg")
    val CIRCLES = loadIcon("circles.svg")
    val DOLLAR_CIRCLE = loadIcon("dollar_circle.svg")
    val PULSE = loadIcon("pulse.svg")
    val EMPTY_PIE = loadIcon("empty_pie.svg")
    val FLOPPY_DISK = loadIcon("floppy_disk.svg")
    val SHIELD_EXCLAMATION = loadIcon("shield_exclamation.svg")
    val PLUS = loadIcon("plus.svg")
    val CHART = loadIcon("chart.svg")

    fun HTMLTag.icon(rawSvg: String) {
        unsafe { +rawSvg }
    }
}
