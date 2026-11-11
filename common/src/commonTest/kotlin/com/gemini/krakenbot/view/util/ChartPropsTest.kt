package com.gemini.krakenbot.view.util

import com.gemini.krakenbot.model.Asset
import kotlin.test.Test
import kotlin.test.assertEquals

class ChartPropsTest {
    @Test
    fun mapsKnownAssetsToTheirSolidColors() {
        assertEquals("#fbbf24", solidColorForSymbol(Asset.BTC))
        assertEquals("#a78bfa", solidColorForSymbol(Asset.ETH.lowercase()))
        assertEquals("#94a3b8", solidColorForSymbol(Asset.USD))
    }

    @Test
    fun rotatesThroughSolidFallbackPalette() {
        val expected =
            listOf(
                "#60a5fa",
                "#34d399",
                "#fbbf24",
                "#a78bfa",
                "#f87171",
                "#2dd4bf",
                "#fb923c",
                "#e879f9",
                "#60a5fa",
            )

        assertEquals(expected, expected.indices.map { solidColorForSymbol("OTHER", it) })
    }

    @Test
    fun handlesNegativeFallbackIndicesSafely() {
        assertEquals("#e879f9", solidColorForSymbol("OTHER", -1))
    }
}
