package com.gemini.krakenbot.frontend

/**
 * Type-safe external interface definitions for dynamic JSON payloads received over REST APIs
 * and SSE streams in the client Kotlin/JS environment.
 */
external interface JsPortfolioSnapshot {
    val timestamp: Double
    val totalValueUSD: Double
    val effectiveUsdTargetPercent: Double
    val drawdownPercent: Double
    val actions: Array<String>?
    val assets: dynamic
}

external interface JsTradeRecord {
    val id: Long?
    val timestamp: Double
    val symbol: String
    val side: String
    val price: Double
    val amount: Double
    val usdAmount: Double
    val feeUSD: Double
}

external interface JsHistoryStats {
    val allTimeHigh: Double
    val totalTradesExecuted: Double
    val totalVolumeTraded: Double
    val totalFeesPaid: Double
    val highValue: Double?
}

external interface JsSyncProgress {
    val isSeeded: Boolean
    val offset: String?
    val total: String?
}
