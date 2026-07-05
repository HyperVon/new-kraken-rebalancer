package com.gemini.krakenbot.service

import com.gemini.krakenbot.model.Result
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Utility functions for service operations including retry logic, parsing, and common patterns.
 * Promotes consistent error handling and reduces code duplication.
 */

/**
 * Retry logic with exponential backoff.
 * Useful for API calls that may fail temporarily.
 */
suspend inline fun <T> retryWithExponentialBackoff(
    maxAttempts: Int = 5,
    initialDelayMs: Long = 2000,
    maxDelayMs: Long = 32000,
    block: suspend (attempt: Int) -> T
): Result<T> {
    var delay = initialDelayMs
    var lastException: Exception? = null

    repeat(maxAttempts) { attempt ->
        try {
            return Result.Success(block(attempt))
        } catch (ex: Exception) {
            lastException = ex
            if (attempt < maxAttempts - 1) {
                kotlinx.coroutines.delay(delay)
                delay = (delay * 2).coerceAtMost(maxDelayMs)
            }
        }
    }

    return Result.Failure(lastException ?: Exception("Max retries exceeded"))
}

/**
 * Safe BigDecimal parsing that returns ZERO on failure instead of throwing.
 * Replaces try-catch patterns for BigDecimal parsing.
 */
fun safeParseBigDecimal(value: String?, default: BigDecimal = BigDecimal.ZERO): BigDecimal =
    value?.let {
        try {
            BigDecimal(it)
        } catch (_: NumberFormatException) {
            default
        }
    } ?: default

/**
 * Safe BigDecimal parsing with scale and rounding.
 */
fun safeParseBigDecimal(
    value: String?,
    scale: Int,
    mode: RoundingMode = RoundingMode.HALF_UP,
    default: BigDecimal = BigDecimal.ZERO
): BigDecimal =
    safeParseBigDecimal(value, default).setScale(scale, mode)

/**
 * Extension function for safe collection mapping with default.
 */
fun <K, V> Map<K, V>.getOrDefault(key: K, defaultValue: V): V =
    this[key] ?: defaultValue
