package com.gemini.krakenbot.model

import java.math.BigDecimal
import java.time.Instant

/**
 * Aggregate statistics for the History page summary cards.
 */
data class HistoryStats(
    val allTimeHigh: BigDecimal,
    val totalTradesExecuted: Long,
    val totalVolumeTraded: BigDecimal,
    val totalFeesPaid: BigDecimal,
    val latestSnapshotTime: Instant?,
    val avgFeeRatePercent: BigDecimal = BigDecimal.ZERO,
    val avgSlippagePercent: BigDecimal? = null,
    val failedTradeCount: Long = 0L,
    val dryRunTradeCount: Long = 0L,
)
