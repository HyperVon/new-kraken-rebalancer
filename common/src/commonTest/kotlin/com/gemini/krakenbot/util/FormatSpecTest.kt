package com.gemini.krakenbot.util

import kotlin.test.Test
import kotlin.test.assertEquals

class FormatSpecTest {
    @Test
    fun priceDigitsTiers() {
        assertEquals(2, FormatSpec.priceDigits(150.0))
        assertEquals(4, FormatSpec.priceDigits(50.0))
        assertEquals(4, FormatSpec.priceDigits(1.0))
        assertEquals(6, FormatSpec.priceDigits(0.05))
        assertEquals(6, FormatSpec.priceDigits(0.01))
        assertEquals(8, FormatSpec.priceDigits(0.001))
        assertEquals(8, FormatSpec.priceDigits(0.0))
        assertEquals(8, FormatSpec.priceDigits(-0.001))
    }

    @Test
    fun feeDigitsTiers() {
        assertEquals(2, FormatSpec.feeDigits(2.0))
        assertEquals(2, FormatSpec.feeDigits(1.0))
        assertEquals(4, FormatSpec.feeDigits(0.99))
        assertEquals(4, FormatSpec.feeDigits(0.5))
        assertEquals(4, FormatSpec.feeDigits(-0.5))
    }

    @Test
    fun quantityDigitsIsCryptoScale() {
        assertEquals(PrecisionConstants.SCALE_CRYPTO, FormatSpec.quantityDigits())
    }
}
