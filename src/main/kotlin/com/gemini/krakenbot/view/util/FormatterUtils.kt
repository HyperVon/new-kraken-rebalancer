package com.gemini.krakenbot.view.util

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.NumberFormat
import java.util.*

/**
 * Comprehensive formatting utilities for common view operations.
 * Provides consistent formatting patterns used throughout the application.
 */
object FormatterUtils {
    private val currencyFormat: NumberFormat = DecimalFormat.getInstance(Locale.US).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 8
    }

    private val percentFormat: NumberFormat = DecimalFormat.getInstance(Locale.US).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 4
    }

    /**
     * Format currency values with up to 8 decimal places.
     */
    fun formatCurrency(value: BigDecimal?): String =
        value?.setScale(2, RoundingMode.HALF_UP)?.toPlainString() ?: "0.00"

    /**
     * Format percentage values with 2 decimal places.
     */
    fun formatPercent(value: BigDecimal?): String =
        value?.setScale(2, RoundingMode.HALF_UP)?.toPlainString() ?: "0.00"

    /**
     * Get deviation CSS class based on value sign.
     * Positive deviation (overweight) gets "text-danger" red color.
     */
    fun getDeviationClass(deviation: BigDecimal?): String =
        if ((deviation?.signum() ?: 0) > 0) "text-danger" else ""

    /**
     * Get deviation sign for display (+/-).
     */
    fun getDeviationSign(deviation: BigDecimal?): String =
        when {
            deviation == null -> ""
            deviation.signum() > 0 -> "+"
            else -> ""
        }

    /**
     * Format a large number with abbreviated suffixes (K, M, B, etc.)
     */
    fun formatCompact(value: BigDecimal): String {
        val abs = value.abs()
        return when {
            abs >= BigDecimal("1000000000") ->
                "${value.divide(BigDecimal("1000000000"), 2, RoundingMode.HALF_UP).toPlainString()}B"
            abs >= BigDecimal("1000000") ->
                "${value.divide(BigDecimal("1000000"), 2, RoundingMode.HALF_UP).toPlainString()}M"
            abs >= BigDecimal("1000") ->
                "${value.divide(BigDecimal("1000"), 2, RoundingMode.HALF_UP).toPlainString()}K"
            else -> value.setScale(2, RoundingMode.HALF_UP).toPlainString()
        }
    }

    /**
     * Format time duration in human-readable format.
     */
    fun formatDuration(seconds: Long): String = when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
        else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
    }

    /**
     * Format timestamp as relative time (e.g., "2 hours ago").
     */
    fun formatRelativeTime(instant: java.time.Instant): String {
        val now = java.time.Instant.now()
        val durationMs = java.time.temporal.ChronoUnit.MILLIS.between(instant, now)
        return when {
            durationMs < 60000 -> "just now"
            durationMs < 3600000 -> "${durationMs / 60000}m ago"
            durationMs < 86400000 -> "${durationMs / 3600000}h ago"
            else -> "${durationMs / 86400000}d ago"
        }
    }
}
