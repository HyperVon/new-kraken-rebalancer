package com.gemini.krakenbot.view.util

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal

class FormatterTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "formatCurrency formats values" {
            Formatter.formatCurrency(BigDecimal("1234.567")) shouldBe "1,234.57"
        }

        "formatPercent formats values" {
            Formatter.formatPercent(BigDecimal("12.3456")) shouldBe "12.35"
        }

        "getDeviationClass returns correct class for deviation" {
            Formatter.getDeviationClass(BigDecimal("1.0"))?.value shouldBe "text-overweight"
            Formatter.getDeviationClass(BigDecimal("-1.0"))?.value shouldBe "text-underweight"
            Formatter.getDeviationClass(BigDecimal.ZERO) shouldBe null
        }

        "getDeviationSign returns plus for positive deviation" {
            Formatter.getDeviationSign(BigDecimal("1.0")) shouldBe "+"
            Formatter.getDeviationSign(BigDecimal.ZERO) shouldBe ""
        }

        "priceDigitsForDisplay mirrors FormatSpec tiers" {
            Formatter.priceDigitsForDisplay(BigDecimal("150.00")) shouldBe 2
            Formatter.priceDigitsForDisplay(BigDecimal("50.00")) shouldBe 4
            Formatter.priceDigitsForDisplay(BigDecimal("0.05")) shouldBe 6
            Formatter.priceDigitsForDisplay(BigDecimal("0.001")) shouldBe 8
            Formatter.priceDigitsForDisplay(BigDecimal("-0.001")) shouldBe 8
        }

        "feeDigitsForDisplay mirrors FormatSpec tiers" {
            Formatter.feeDigitsForDisplay(BigDecimal("2.00")) shouldBe 2
            Formatter.feeDigitsForDisplay(BigDecimal("0.50")) shouldBe 4
        }
    }
}
