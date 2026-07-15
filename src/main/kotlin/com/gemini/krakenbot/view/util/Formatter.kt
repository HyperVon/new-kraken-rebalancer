package com.gemini.krakenbot.view.util

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.temporal.ChronoUnit

object Formatter {
    fun formatCurrency(value: BigDecimal?): String =
        value?.let { String.format("%,.2f", it) } ?: "0.00"

    fun formatPercent(value: BigDecimal?): String =
        value?.let { String.format("%.2f", it) } ?: "0.00"

    fun formatPercent(value: Double): String =
        String.format("%.2f", value)

    fun getDeviationClass(deviation: BigDecimal?): String =
        if (deviation == null) "" else when (deviation.signum()) {
            1 -> "text-danger"
            -1 -> "text-success"
            else -> ""
        }

    fun getDeviationSign(deviation: BigDecimal?): String = when {
        deviation == null -> ""
        deviation.signum() > 0 -> "+"
        else -> ""
    }

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

    fun formatDuration(seconds: Long): String = when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
        else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
    }

    fun formatRelativeTime(instant: Instant): String {
        val now = Instant.now()
        val durationMs = ChronoUnit.MILLIS.between(instant, now)
        return when {
            durationMs < 60000 -> "just now"
            durationMs < 3600000 -> "${durationMs / 60000}m ago"
            durationMs < 86400000 -> "${durationMs / 3600000}h ago"
            else -> "${durationMs / 86400000}d ago"
        }
    }
}
