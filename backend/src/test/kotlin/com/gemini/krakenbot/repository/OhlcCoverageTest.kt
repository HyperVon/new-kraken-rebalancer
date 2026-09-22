package com.gemini.krakenbot.repository

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal

class OhlcCoverageTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "short response proves the full requested domain" {
            authoritativeOhlcCoverage(
                requestSinceEpochSecond = 1_000_000L,
                fetchWallEpochSecond = 2_000_000L,
                intervalMinutes = 15,
                completedCandles = listOf(1_000_000L to BigDecimal("0.0175")),
                mayBeTruncated = false,
            ) shouldBe OhlcCoverage(1_000_000L, 2_000_000L)
        }

        "short empty response proves the requested range as negative evidence" {
            authoritativeOhlcCoverage(
                requestSinceEpochSecond = 1_000_000L,
                fetchWallEpochSecond = 2_000_000L,
                intervalMinutes = 15,
                completedCandles = emptyList(),
                mayBeTruncated = false,
            ) shouldBe OhlcCoverage(1_000_000L, 2_000_000L)
        }

        "truncated response proves only its returned completed span" {
            authoritativeOhlcCoverage(
                requestSinceEpochSecond = 1_000_000L,
                fetchWallEpochSecond = 2_000_000L,
                intervalMinutes = 15,
                completedCandles = listOf(
                    1_500_000L to BigDecimal("0.0175"),
                    1_500_900L to BigDecimal("0.0176"),
                    1_501_800L to BigDecimal("0.0177"),
                ),
                mayBeTruncated = true,
            ) shouldBe OhlcCoverage(1_500_000L, 1_501_800L + 900L)
        }

        "truncated response with zero completed rows proves nothing" {
            authoritativeOhlcCoverage(
                requestSinceEpochSecond = 1_000_000L,
                fetchWallEpochSecond = 2_000_000L,
                intervalMinutes = 15,
                completedCandles = emptyList(),
                mayBeTruncated = true,
            ) shouldBe null
        }

        "truncated span deduplicates timestamps and clamps to the request since" {
            authoritativeOhlcCoverage(
                requestSinceEpochSecond = 1_000_000L,
                fetchWallEpochSecond = 2_000_000L,
                intervalMinutes = 15,
                completedCandles = listOf(
                    900_000L to BigDecimal("0.0169"),
                    1_100_000L to BigDecimal("0.0175"),
                    1_100_000L to BigDecimal("0.0175"),
                ),
                mayBeTruncated = true,
            ) shouldBe OhlcCoverage(1_000_000L, 1_100_000L + 900L)
        }

        "truncated singleton proves exactly its one candle span" {
            val coverage = authoritativeOhlcCoverage(
                requestSinceEpochSecond = 1_000_000L,
                fetchWallEpochSecond = 2_000_000L,
                intervalMinutes = 15,
                completedCandles = listOf(1_100_000L to BigDecimal("0.0175")),
                mayBeTruncated = true,
            ) shouldBe OhlcCoverage(1_100_000L, 1_100_900L)
            // The exclusive end is exactly the lone candle's close: a window ending
            // there is covered (consumed start + duration <= upTo), one second past
            // it is not.
            checkNotNull(coverage).contains(1_100_000L, 1_100_900L) shouldBe true
            checkNotNull(coverage).contains(1_100_000L, 1_100_901L) shouldBe false
        }

        "truncated page with every row predating the request proves nothing" {
            authoritativeOhlcCoverage(
                requestSinceEpochSecond = 1_000_000L,
                fetchWallEpochSecond = 2_000_000L,
                intervalMinutes = 15,
                completedCandles = listOf(
                    999_100L to BigDecimal("0.0174"),
                    999_000L to BigDecimal("0.0173"),
                ),
                mayBeTruncated = true,
            ) shouldBe null
        }

        "coverage contains windows inside the proven span only" {
            val coverage = OhlcCoverage(1_500_000L, 2_147_100L)
            coverage.contains(1_500_000L, 2_147_100L) shouldBe true
            coverage.contains(1_600_000L, 1_700_000L) shouldBe true
            coverage.contains(1_000_000L, 1_600_000L) shouldBe false
            coverage.contains(1_500_000L, 2_147_101L) shouldBe false
            coverage.contains(OhlcCoverage(1_600_000L, 1_700_000L)) shouldBe true
            coverage.contains(OhlcCoverage(1_000_000L, 1_600_000L)) shouldBe false
        }
    }
}
