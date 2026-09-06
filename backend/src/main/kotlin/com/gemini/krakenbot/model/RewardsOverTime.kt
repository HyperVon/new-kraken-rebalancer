package com.gemini.krakenbot.model

import com.gemini.krakenbot.codegen.GenerateApiMapper
import java.math.BigDecimal
import java.time.Instant
import com.gemini.krakenbot.api.RewardsOverTime as ApiRewardsOverTime
import com.gemini.krakenbot.api.RewardsOverTimePoint as ApiRewardsOverTimePoint

/** Cumulative staking, dividend, and Earn reward value over time, aligned to portfolio snapshot timestamps. */
@GenerateApiMapper(ApiRewardsOverTime::class)
data class RewardsOverTime(val totalRewardsUSD: BigDecimal, val points: List<RewardsOverTimePoint>)

/** Cumulative staking, dividend, and Earn reward value at one snapshot time, per asset (USD) and in total (USD). */
@GenerateApiMapper(ApiRewardsOverTimePoint::class)
data class RewardsOverTimePoint(
    val timestamp: Instant,
    val cumulativeUSD: BigDecimal,
    val perAssetUSD: Map<String, BigDecimal>,
)
