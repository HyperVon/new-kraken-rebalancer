package com.gemini.krakenbot.api

/** History `/api/history/stats` JSON body — decimal and timestamp fields are strings. */
data class HistoryStats(
    val allTimeHigh: String,
    val totalTradesExecuted: Long,
    val totalVolumeTraded: String,
    val totalFeesPaid: String,
    val latestSnapshotTime: String?,
    val avgFeeRatePercent: String = "0",
    val avgSlippagePercent: String? = null,
    val failedTradeCount: Long = 0L,
    val dryRunTradeCount: Long = 0L,
)
