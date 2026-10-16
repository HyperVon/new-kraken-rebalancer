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

        "formatFeeWithCurrency styles USD fees and annotates other denominations" {
            Formatter.formatFeeWithCurrency(BigDecimal("8.0"), "USD") shouldBe "\$8.00"
            Formatter.formatFeeWithCurrency(BigDecimal("0.0001"), "BTC") shouldBe "0.0001 BTC"
            Formatter.formatFeeWithCurrency(BigDecimal("0.5"), null) shouldBe "0.5 (currency not reported)"
            Formatter.formatFeeWithCurrency(BigDecimal.ZERO, "USD") shouldBe "—"
            Formatter.formatFeeWithCurrency(null, "USD") shouldBe "—"
        }

        "formatCount groups thousands" {
            Formatter.formatCount(1234L) shouldBe "1,234"
            Formatter.formatCount(0L) shouldBe "0"
        }

        "formatQuantity preserves the sign of ledger outflows" {
            Formatter.formatQuantity(BigDecimal("-1.5")) shouldBe "-1.5"
            Formatter.formatQuantity(BigDecimal("-1234.5678")) shouldBe "-1,234.5678"
        }

        "formatUsdOrDash places the sign before the currency symbol" {
            Formatter.formatUsdOrDash(BigDecimal("-200.0"), 2, 2) shouldBe "-\$200.00"
            Formatter.formatUsdOrDash(BigDecimal("200.0"), 2, 2) shouldBe "\$200.00"
        }

        "price and fee helpers dash absent or zero values" {
            Formatter.formatPriceOrDash(null) shouldBe "—"
            Formatter.formatPriceOrDash(BigDecimal.ZERO) shouldBe "—"
            Formatter.formatFeeOrDash(BigDecimal.ZERO) shouldBe "—"
        }

        "formatPair falls back to the raw pair and formatCompactTradeTime blanks null" {
            Formatter.formatPair("BTC", null, "XXBTZUSD") shouldBe "XXBTZUSD"
            Formatter.formatPair(null, null, null) shouldBe ""
            Formatter.formatCompactTradeTime(null) shouldBe ""
        }

        "formatSignedUsd prefixes positives without a sign" {
            Formatter.formatSignedUsd(BigDecimal("200.0")) shouldBe "\$200.00"
            Formatter.formatSignedUsd(BigDecimal("-200.0")) shouldBe "-\$200.00"
        }
    }
}
