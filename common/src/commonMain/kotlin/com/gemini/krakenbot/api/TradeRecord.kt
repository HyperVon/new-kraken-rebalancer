package com.gemini.krakenbot.api

/** History `/api/history/trades` JSON element — decimal and timestamp fields are strings. */
data class TradeRecord(
    val timestamp: String,
    val pair: String,
    val side: String,
    val symbol: String,
    val volume: String,
    val usdAmount: String,
    val success: Boolean,
    val dryRun: Boolean,
    val errorMessage: String? = null,
    val price: String = "0",
    val fee: String = "0",
    val slippagePercent: String? = null,
    val expectedPrice: String? = null,
    val source: String? = null,
    val id: Int? = null,
)
