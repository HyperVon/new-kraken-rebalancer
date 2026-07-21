package com.gemini.krakenbot.model

import java.time.Instant
import java.time.temporal.ChronoUnit

/** Strongly typed representation of portfolio history time filter ranges. */
enum class TimeRange(val key: String, val days: Long?) {
    TWENTY_FOUR_HOURS("24h", 1L),
    SEVEN_DAYS("7d", 7L),
    THIRTY_DAYS("30d", 30L),
    NINETY_DAYS("90d", 90L),
    ALL("all", null);

    /** Computes starting [Instant] for this time range relative to [now]. */
    fun calculateFromInstant(now: Instant = Instant.now()): Instant =
        if (days != null) {
            now.minus(days, ChronoUnit.DAYS)
        } else {
            Instant.EPOCH
        }

    companion object {
        fun fromQueryParam(param: String?): TimeRange =
            entries.firstOrNull { it.key.equals(param, ignoreCase = true) } ?: THIRTY_DAYS
    }
}
