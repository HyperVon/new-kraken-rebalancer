package com.gemini.krakenbot.service.history

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class HistoryRangeTest : StringSpec() {
    init {
        "rolling presets are durations anchored to one captured now" {
            val now = Instant.parse("2026-03-10T12:00:00Z")
            val resolved = HistoryRanges.resolve(HistoryRangeSelection.rolling(HistoryRangePreset.SEVEN_DAYS), now, UTC)
            resolved.from shouldBe now.minus(Duration.ofDays(7))
            resolved.until shouldBe now
            resolved.capturedAt shouldBe now
        }

        "thirty days is the default selection" {
            HistoryRangeSelection.DEFAULT.preset shouldBe HistoryRangePreset.THIRTY_DAYS
        }

        "all has no lower cutoff but keeps the captured upper bound" {
            val now = Instant.parse("2026-03-10T12:00:00Z")
            val resolved = HistoryRanges.resolve(HistoryRangeSelection.rolling(HistoryRangePreset.ALL), now, UTC)
            resolved.from shouldBe null
            resolved.until shouldBe now
        }

        "custom covers inclusive local start through the end of the last local day" {
            val zone = ZoneId.of("Europe/Berlin")
            val now = Instant.parse("2026-03-10T23:30:00Z")
            val resolved = HistoryRanges.resolve(
                HistoryRangeSelection.custom(LocalDate.parse("2026-03-01"), LocalDate.parse("2026-03-05")),
                now,
                zone,
            )
            resolved.from shouldBe Instant.parse("2026-02-28T23:00:00Z")
            resolved.until shouldBe Instant.parse("2026-03-05T23:00:00Z")
        }

        "custom end-date semantics stay local across a DST transition" {
            val zone = ZoneId.of("America/New_York")
            val now = Instant.parse("2026-03-15T12:00:00Z")
            val resolved = HistoryRanges.resolve(
                HistoryRangeSelection.custom(LocalDate.parse("2026-03-07"), LocalDate.parse("2026-03-09")),
                now,
                zone,
            )
            // 2026-03-08 is the US DST start; the local day after the last selected date is midnight EDT.
            resolved.from shouldBe Instant.parse("2026-03-07T05:00:00Z")
            resolved.until shouldBe Instant.parse("2026-03-10T04:00:00Z")
        }

        "contains treats the upper bound as exclusive and the lower bound as inclusive" {
            val now = Instant.parse("2026-03-10T12:00:00Z")
            val resolved = HistoryRanges.resolve(
                HistoryRangeSelection.rolling(HistoryRangePreset.TWENTY_FOUR_HOURS),
                now,
                UTC,
            )
            val from = checkNotNull(resolved.from)
            resolved.contains(from) shouldBe true
            resolved.contains(now) shouldBe false
            resolved.contains(from.minusSeconds(1)) shouldBe false
        }

        "invalid custom bounds are rejected rather than silently repaired" {
            shouldThrow<IllegalArgumentException> {
                HistoryRangeSelection.custom(LocalDate.parse("2026-03-05"), LocalDate.parse("2026-03-01"))
            }
            shouldThrow<IllegalArgumentException> {
                HistoryRangeSelection(HistoryRangePreset.CUSTOM)
            }
            shouldThrow<IllegalArgumentException> {
                HistoryRangeSelection(HistoryRangePreset.THIRTY_DAYS, LocalDate.parse("2026-03-01"), null)
            }
        }

        "preset keys cover the specified controls and nothing else" {
            HistoryRangePreset.entries.map { it.key } shouldBe listOf("24h", "7d", "30d", "90d", "1y", "all", "custom")
            HistoryRangePreset.fromKey("90d") shouldBe HistoryRangePreset.NINETY_DAYS
            HistoryRangePreset.fromKey("nope") shouldBe null
        }
    }

    private companion object {
        val UTC: ZoneId = ZoneId.of("UTC")
    }
}
