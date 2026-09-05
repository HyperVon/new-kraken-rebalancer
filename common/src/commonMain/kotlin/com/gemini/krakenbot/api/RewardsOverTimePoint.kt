package com.gemini.krakenbot.api

/**
 * Cumulative staking, dividend, and Earn reward value at one snapshot time, per asset (USD) and in total (USD).
 */
data class RewardsOverTimePoint(val timestamp: String, val cumulativeUSD: String, val perAssetUSD: Map<String, String>)
