package com.gemini.krakenbot.model

import java.math.BigDecimal
import java.time.Instant

/**
 * Represents a single executed trade/order event.
 * Provides structured data instead of relying on string-based action logs.
 */
data class TradeRecord(
    val timestamp: Instant,
    val pair: String,
    val side: String,
    val symbol: String,
    val volume: BigDecimal,
    val usdAmount: BigDecimal,
    val success: Boolean,
    val dryRun: Boolean,
    val errorMessage: String? = null
)
