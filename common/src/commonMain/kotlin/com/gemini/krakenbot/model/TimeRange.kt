package com.gemini.krakenbot.model

/** Strongly typed representation of portfolio history time filter ranges. */
enum class TimeRange(val key: String, val days: Long?) {
    TWENTY_FOUR_HOURS("24h", 1L),
    SEVEN_DAYS("7d", 7L),
    THIRTY_DAYS("30d", 30L),
    NINETY_DAYS("90d", 90L),
    ALL("all", null),
    ;

    companion object {
        fun fromQueryParam(param: String?): TimeRange = entries.firstOrNull { it.key.equals(param, ignoreCase = true) } ?: THIRTY_DAYS
    }
}
