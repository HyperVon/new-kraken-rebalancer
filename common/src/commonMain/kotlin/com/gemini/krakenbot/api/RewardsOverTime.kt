package com.gemini.krakenbot.api

/**
 * Staking, dividend, and Earn rewards over time, aligned to portfolio snapshot timestamps.
 *
 * History `/api/history/rewards` JSON body — decimal and timestamp fields are strings.
 */
data class RewardsOverTime(val totalRewardsUSD: String, val points: List<RewardsOverTimePoint>)
