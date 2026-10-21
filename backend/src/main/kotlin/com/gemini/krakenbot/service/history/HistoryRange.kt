package com.gemini.krakenbot.service.history

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The one validated range contract used by chart queries, summaries, tables, exports
 * and saved views. Rolling presets are durations anchored to a single captured [now];
 * Custom dates are inclusive local calendar days in the selected browser IANA timezone.
 */
enum class HistoryRangePreset(val key: String, val duration: Duration?) {
    TWENTY_FOUR_HOURS("24h", Duration.ofHours(24)),
    SEVEN_DAYS("7d", Duration.ofDays(7)),
    THIRTY_DAYS("30d", Duration.ofDays(30)),
    NINETY_DAYS("90d", Duration.ofDays(90)),
    ONE_YEAR("1y", Duration.ofDays(365)),
    ALL("all", null),
    CUSTOM("custom", null),
    ;

    companion object {
        fun fromKey(key: String?): HistoryRangePreset? = entries.firstOrNull { it.key == key }
    }
}

data class HistoryRangeSelection(
    val preset: HistoryRangePreset,
    val customStart: LocalDate? = null,
    val customEnd: LocalDate? = null,
) {
    init {
        if (preset == HistoryRangePreset.CUSTOM) {
            require(customStart != null && customEnd != null) { "Custom history range requires start and end dates." }
            require(!customStart.isAfter(customEnd)) { "Custom history range start must not follow its end." }
        } else {
            require(customStart == null && customEnd == null) { "Only a custom history range carries explicit dates." }
        }
    }

    companion object {
        val DEFAULT = HistoryRangeSelection(HistoryRangePreset.THIRTY_DAYS)

        fun rolling(preset: HistoryRangePreset): HistoryRangeSelection {
            require(preset != HistoryRangePreset.CUSTOM) { "Use HistoryRangeSelection custom for explicit dates." }
            return HistoryRangeSelection(preset)
        }

        fun custom(start: LocalDate, end: LocalDate): HistoryRangeSelection =
            HistoryRangeSelection(HistoryRangePreset.CUSTOM, start, end)
    }
}

/**
 * Resolved server interval. [from] is null only for All; [until] is exclusive and was
 * captured once per response so every consumer of one response shares the same bounds.
 */
data class ResolvedHistoryRange(
    val selection: HistoryRangeSelection,
    val from: Instant?,
    val until: Instant,
    val capturedAt: Instant,
    val zone: ZoneId,
) {
    fun contains(time: Instant): Boolean = (from == null || !time.isBefore(from)) && time.isBefore(until)
}

object HistoryRanges {
    /**
     * Resolves [selection] against one captured [now]. Custom bounds convert inclusive
     * local start/end dates into `[start of first day, start of day after last)`.
     */
    fun resolve(selection: HistoryRangeSelection, now: Instant, zone: ZoneId): ResolvedHistoryRange =
        when (selection.preset) {
            HistoryRangePreset.ALL -> ResolvedHistoryRange(selection, null, now, now, zone)

            HistoryRangePreset.CUSTOM -> {
                val start = checkNotNull(selection.customStart)
                val end = checkNotNull(selection.customEnd)
                val from = start.atStartOfDay(zone).toInstant()
                val until = end.plusDays(1).atStartOfDay(zone).toInstant()
                ResolvedHistoryRange(selection, from, until, now, zone)
            }

            else -> {
                val duration = checkNotNull(selection.preset.duration)
                ResolvedHistoryRange(selection, now.minus(duration), now, now, zone)
            }
        }

    /** Inclusive query lower bound as epoch seconds, or null for unbounded. */
    fun fromSec(resolved: ResolvedHistoryRange): Long? = resolved.from?.epochSecond

    /** Exclusive query upper bound as epoch seconds. */
    fun untilSec(resolved: ResolvedHistoryRange): Long = resolved.until.epochSecond
}
