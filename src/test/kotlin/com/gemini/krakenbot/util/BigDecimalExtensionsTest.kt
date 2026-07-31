package com.gemini.krakenbot.util

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal

class BigDecimalExtensionsTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    init {

        "isZero should correctly identify zero values" {
            BigDecimal.ZERO.isZero shouldBe true
            BigDecimal("0.00").isZero shouldBe true
            BigDecimal("1.50").isZero shouldBe false
        }

        "isPositive should correctly identify positive values" {
            BigDecimal("10.00").isPositive shouldBe true
            BigDecimal.ZERO.isPositive shouldBe false
            BigDecimal("-5.00").isPositive shouldBe false
        }

        "isNegative should correctly identify negative values" {
            BigDecimal("-10.00").isNegative shouldBe true
            BigDecimal.ZERO.isNegative shouldBe false
            BigDecimal("5.00").isNegative shouldBe false
        }

        "scale extension functions should scale correctly" {
            BigDecimal("10.123456789").toUsdScale().toString() shouldBe "10.12"
            BigDecimal("10.123456789").toCryptoScale().toString() shouldBe "10.12345679"
            BigDecimal("10.123456789").toPercentScale().toString() shouldBe "10.1235"
        }
    }
}
