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
    val firstSnapshotTime: Instant?,
    val latestSnapshotTime: Instant?
)
