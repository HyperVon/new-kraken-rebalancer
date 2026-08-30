package com.gemini.krakenbot.model

import com.gemini.krakenbot.codegen.GenerateApiMapper
import java.math.BigDecimal
import java.time.Instant
import com.gemini.krakenbot.api.HistoryStats as ApiHistoryStats

@GenerateApiMapper(ApiHistoryStats::class)
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
