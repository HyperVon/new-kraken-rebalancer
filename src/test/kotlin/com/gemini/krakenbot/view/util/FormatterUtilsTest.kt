package com.gemini.krakenbot.view.util

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit

@Suppress("unused")
class FormatterUtilsTest : StringSpec({
    "formatCurrency formats values and handles null" {
        FormatterUtils.formatCurrency(BigDecimal("1234.567")) shouldBe "1234.57"
        FormatterUtils.formatCurrency(null) shouldBe "0.00"
    }

    "formatPercent formats values and handles null" {
        FormatterUtils.formatPercent(BigDecimal("12.3456")) shouldBe "12.35"
        FormatterUtils.formatPercent(null) shouldBe "0.00"
    }

    "getDeviationClass returns danger class for positive deviation" {
        FormatterUtils.getDeviationClass(BigDecimal("1.0")) shouldBe "text-danger"
        FormatterUtils.getDeviationClass(BigDecimal.ZERO) shouldBe ""
        FormatterUtils.getDeviationClass(null) shouldBe ""
    }

    "getDeviationSign returns plus for positive deviation" {
        FormatterUtils.getDeviationSign(BigDecimal("1.0")) shouldBe "+"
        FormatterUtils.getDeviationSign(BigDecimal.ZERO) shouldBe ""
        FormatterUtils.getDeviationSign(null) shouldBe ""
    }

    "formatCompact abbreviates large values" {
        FormatterUtils.formatCompact(BigDecimal("1500")) shouldBe "1.50K"
        FormatterUtils.formatCompact(BigDecimal("2500000")) shouldBe "2.50M"
        FormatterUtils.formatCompact(BigDecimal("1200000000")) shouldBe "1.20B"
        FormatterUtils.formatCompact(BigDecimal("42.5")) shouldBe "42.50"
    }

    "formatDuration renders human-readable durations" {
        FormatterUtils.formatDuration(45) shouldBe "45s"
        FormatterUtils.formatDuration(125) shouldBe "2m 5s"
        FormatterUtils.formatDuration(3665) shouldBe "1h 1m"
    }

    "formatRelativeTime renders relative timestamps" {
        val now = Instant.now()
        FormatterUtils.formatRelativeTime(now.minus(30, ChronoUnit.SECONDS)) shouldBe "just now"
        FormatterUtils.formatRelativeTime(now.minus(5, ChronoUnit.MINUTES)) shouldBe "5m ago"
        FormatterUtils.formatRelativeTime(now.minus(3, ChronoUnit.HOURS)) shouldBe "3h ago"
        FormatterUtils.formatRelativeTime(now.minus(2, ChronoUnit.DAYS)) shouldBe "2d ago"
    }
})
