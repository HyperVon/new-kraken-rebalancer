package com.gemini.krakenbot.model

/**
 * History `range` keys shared by SSR buttons, query params, and JS clients.
 * [days] is whole ChronoUnit.DAYS subtracted from "now" (so key `24h` is 1 day, not exact hours);
 * [ALL] has null days → consumers typically start at Instant.EPOCH.
 * [fromQueryParam] defaults unknown/null to [THIRTY_DAYS].
 */
enum class TimeRange(val key: String, val days: Long?) {
    TWENTY_FOUR_HOURS("24h", 1L),
    SEVEN_DAYS("7d", 7L),
    THIRTY_DAYS("30d", 30L),
    NINETY_DAYS("90d", 90L),
    ALL("all", null),
    ;

    companion object {
        fun fromQueryParam(param: String?): TimeRange =
            entries.firstOrNull { it.key.equals(param, ignoreCase = true) } ?: THIRTY_DAYS
    }
}
