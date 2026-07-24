package com.gemini.krakenbot.service

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.math.RoundingMode

class ServiceUtilsTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "should parse valid BigDecimal string correctly" {
            val result = safeParseBigDecimal("123.456")
            result shouldBeEqualComparingTo BigDecimal("123.456")
        }

        "should return default value on null or invalid String input" {
            val nullResult = safeParseBigDecimal(null, default = BigDecimal("10.00"))
            nullResult shouldBeEqualComparingTo BigDecimal("10.00")

            val invalidResult = safeParseBigDecimal("not-a-number", default = BigDecimal.ZERO)
            invalidResult shouldBeEqualComparingTo BigDecimal.ZERO
        }

        "should parse BigDecimal with explicit scale and rounding mode" {
            val result = safeParseBigDecimal("123.45678", scale = 2, mode = RoundingMode.HALF_UP)
            result.scale() shouldBe 2
            result shouldBeEqualComparingTo BigDecimal("123.46")
        }

        "should evaluate relative tolerance correctly" {
            val a = BigDecimal("100.00")
            val b = BigDecimal("100.005")
            val c = BigDecimal("120.00")

            isWithinRelativeTolerance(a, b, BigDecimal("0.01")) shouldBe true
            isWithinRelativeTolerance(a, c, BigDecimal("0.01")) shouldBe false
            isWithinRelativeTolerance(a, a) shouldBe true
            isWithinRelativeTolerance(BigDecimal.ZERO, BigDecimal.ZERO) shouldBe true
        }
    }
}
