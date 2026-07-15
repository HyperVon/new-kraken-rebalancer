package com.gemini.krakenbot.view.util

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit

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
        Formatter.getDeviationClass(BigDecimal("1.0")) shouldBe "text-danger"
        Formatter.getDeviationClass(BigDecimal("-1.0")) shouldBe "text-success"
        Formatter.getDeviationClass(BigDecimal.ZERO) shouldBe ""
        Formatter.getDeviationClass(null) shouldBe ""
    }

    "getDeviationSign returns plus for positive deviation" {
        Formatter.getDeviationSign(BigDecimal("1.0")) shouldBe "+"
        Formatter.getDeviationSign(BigDecimal.ZERO) shouldBe ""
        Formatter.getDeviationSign(null) shouldBe ""
    }

    "formatCompact abbreviates large values" {
        Formatter.formatCompact(BigDecimal("1500")) shouldBe "1.50K"
        Formatter.formatCompact(BigDecimal("2500000")) shouldBe "2.50M"
        Formatter.formatCompact(BigDecimal("1200000000")) shouldBe "1.20B"
        Formatter.formatCompact(BigDecimal("42.5")) shouldBe "42.50"
    }

    "formatDuration renders human-readable durations" {
        Formatter.formatDuration(45) shouldBe "45s"
        Formatter.formatDuration(125) shouldBe "2m 5s"
        Formatter.formatDuration(3665) shouldBe "1h 1m"
    }

    "formatRelativeTime renders relative timestamps" {
        val now = Instant.now()
        Formatter.formatRelativeTime(now.minus(30, ChronoUnit.SECONDS)) shouldBe "just now"
        Formatter.formatRelativeTime(now.minus(5, ChronoUnit.MINUTES)) shouldBe "5m ago"
        Formatter.formatRelativeTime(now.minus(3, ChronoUnit.HOURS)) shouldBe "3h ago"
        Formatter.formatRelativeTime(now.minus(2, ChronoUnit.DAYS)) shouldBe "2d ago"
    }
})
