package com.gemini.krakenbot.service

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal

class ServiceUtilsTest : StringSpec({
    "safeParseBigDecimal parses valid string" {
        val result = safeParseBigDecimal("123.45")
        result shouldBe BigDecimal("123.45")
    }

    "safeParseBigDecimal returns default on invalid string" {
        val result = safeParseBigDecimal("invalid")
        result shouldBe BigDecimal.ZERO
    }

    "safeParseBigDecimal returns default on null" {
        val result = safeParseBigDecimal(null)
        result shouldBe BigDecimal.ZERO
    }

    "safeParseBigDecimal uses custom default" {
        val result = safeParseBigDecimal("invalid", BigDecimal("99.99"))
        result shouldBe BigDecimal("99.99")
    }

    "safeParseBigDecimal with scale and rounding" {
        val result = safeParseBigDecimal("123.456", scale = 2)
        result shouldBe BigDecimal("123.46")
    }

    "safeParseBigDecimal handles empty string" {
        val result = safeParseBigDecimal("")
        result shouldBe BigDecimal.ZERO
    }

    "safeParseBigDecimal handles whitespace" {
        val result = safeParseBigDecimal("  ")
        result shouldBe BigDecimal.ZERO
    }

    "safeParseBigDecimal preserves precision" {
        val result = safeParseBigDecimal("0.00000001")
        result shouldBe BigDecimal("0.00000001")
    }
})
