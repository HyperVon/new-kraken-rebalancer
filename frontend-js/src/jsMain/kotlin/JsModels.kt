package com.gemini.krakenbot.frontend

/**
 * Type-safe external interface definitions for dynamic JSON payloads received over REST APIs
 * and SSE streams in the client Kotlin/JS environment.
 */
external interface JsPortfolioSnapshot {
    val timestamp: dynamic
    val totalValueUSD: dynamic
    val effectiveUsdTargetPercent: dynamic
    val drawdownPercent: dynamic
    val actions: Array<String>?
    val assets: dynamic
}

external interface JsTradeRecord {
    val id: Double?
    val timestamp: dynamic
    val pair: String?
    val symbol: String?
    val side: String?
    val price: dynamic
    val volume: dynamic
    val usdAmount: dynamic
    val fee: dynamic
    val success: Boolean?
    val dryRun: Boolean?
    val errorMessage: String?
    val slippagePercent: dynamic
    val expectedPrice: dynamic
    val source: String?
}

external interface JsHistoryStats {
    val allTimeHigh: dynamic
    val totalTradesExecuted: dynamic
    val totalVolumeTraded: dynamic
    val totalFeesPaid: dynamic
    val periodHigh: dynamic
    val avgFeeRatePercent: dynamic
    val avgSlippagePercent: dynamic
    val failedTradeCount: dynamic
    val dryRunTradeCount: dynamic
}

external interface JsSyncProgress {
    val seeded: Boolean?
    val offset: dynamic
    val total: dynamic
}
