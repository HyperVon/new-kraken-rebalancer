package com.gemini.krakenbot.view.util

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal

@Suppress("unused")
class FormatterTest : StringSpec({
    "formatCurrency formats values and handles null" {
        Formatter.formatCurrency(BigDecimal("1234.567")) shouldBe "1,234.57"
        Formatter.formatCurrency(null) shouldBe "0.00"
    }

    "formatPercent formats values and handles null" {
        Formatter.formatPercent(BigDecimal("12.3456")) shouldBe "12.35"
        Formatter.formatPercent(null) shouldBe "0.00"
    }

    "getDeviationClass returns correct class for deviation" {
        Formatter.getDeviationClass(BigDecimal("1.0")) shouldBe CssClass.Utility.TextDanger.value
        Formatter.getDeviationClass(BigDecimal("-1.0")) shouldBe CssClass.Utility.TextSuccess.value
        Formatter.getDeviationClass(BigDecimal.ZERO) shouldBe ""
        Formatter.getDeviationClass(null) shouldBe ""
    }

    "getDeviationSign returns plus for positive deviation" {
        Formatter.getDeviationSign(BigDecimal("1.0")) shouldBe "+"
        Formatter.getDeviationSign(BigDecimal.ZERO) shouldBe ""
        Formatter.getDeviationSign(null) shouldBe ""
    }
})
