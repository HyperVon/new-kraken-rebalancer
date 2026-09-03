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
    fun mapsKnownAssetsToBorderColors() {
        assertEquals("rgba(251, 191, 36, 1)", borderColorForSymbol(Asset.BTC))
        assertEquals("rgba(167, 139, 250, 1)", borderColorForSymbol(Asset.ETH))
        assertEquals("rgba(148, 163, 184, 1)", borderColorForSymbol(Asset.USD))
    }

    @Test
    fun mapsKnownAssetsToBackgroundColors() {
        assertEquals("rgba(251, 191, 36, 0.1)", backgroundColorForSymbol(Asset.BTC))
        assertEquals("rgba(167, 139, 250, 0.1)", backgroundColorForSymbol(Asset.ETH))
        assertEquals("rgba(148, 163, 184, 0.12)", backgroundColorForSymbol(Asset.USD))
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
    fun rotatesThroughBorderFallbackPalette() {
        val expected =
            listOf(
                "rgba(96, 165, 250, 1)",
                "rgba(52, 211, 153, 1)",
                "rgba(251, 191, 36, 1)",
                "rgba(167, 139, 250, 1)",
                "rgba(248, 113, 113, 1)",
                "rgba(45, 212, 191, 1)",
                "rgba(251, 146, 60, 1)",
                "rgba(232, 121, 249, 1)",
                "rgba(96, 165, 250, 1)",
            )

        assertEquals(expected, expected.indices.map { borderColorForSymbol("OTHER", it) })
    }

    @Test
    fun rotatesThroughBackgroundFallbackPalette() {
        val expected =
            listOf(
                "rgba(96, 165, 250, 0.1)",
                "rgba(52, 211, 153, 0.1)",
                "rgba(251, 191, 36, 0.1)",
                "rgba(167, 139, 250, 0.1)",
                "rgba(248, 113, 113, 0.1)",
                "rgba(45, 212, 191, 0.1)",
                "rgba(251, 146, 60, 0.1)",
                "rgba(232, 121, 249, 0.1)",
                "rgba(96, 165, 250, 0.1)",
            )

        assertEquals(expected, expected.indices.map { backgroundColorForSymbol("OTHER", it) })
    }

    @Test
    fun handlesNegativeFallbackIndicesSafely() {
        assertEquals("#e879f9", solidColorForSymbol("OTHER", -1))
        assertEquals("rgba(232, 121, 249, 1)", borderColorForSymbol("OTHER", -1))
        assertEquals("rgba(232, 121, 249, 0.1)", backgroundColorForSymbol("OTHER", -1))
    }
}
