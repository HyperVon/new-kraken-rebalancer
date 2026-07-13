package com.gemini.krakenbot.frontend

import kotlin.test.Test
import kotlin.test.assertEquals

class HistoryTest {
    @Test
    fun testFormatUSD() {
        // Test basic formatting
        assertEquals("$1,234.56", formatUSD(1234.56))
        assertEquals("$0.00", formatUSD(0.0))
        assertEquals("$-12.35", formatUSD(-12.3456))
    }

    @Test
    fun testFormatPair() {
        // Test trade pair display formatting
        val trade1: dynamic = js("({ symbol: 'BTC' })")
        assertEquals("BTC/USD", formatPair(trade1))

        val trade2: dynamic = js("({ symbol: null })")
        assertEquals("", formatPair(trade2))

        val trade3: dynamic = js("({})")
        assertEquals("", formatPair(trade3))

        assertEquals("", formatPair(null))
    }
}
