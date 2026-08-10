package com.gemini.krakenbot.model

import java.math.BigDecimal
import java.time.Instant

/** Durable identity and submission state for one real Kraken AddOrder attempt. */
data class OrderIntent(
    val id: Int? = null,
    val cycleId: String?,
    val clientOrderId: String?,
    val pair: String,
    val symbol: String,
    val side: String,
    val volume: BigDecimal,
    val usdAmount: BigDecimal,
    val expectedPrice: BigDecimal?,
    val createdAt: Instant,
    val state: OrderIntentState,
    val orderTxid: String? = null,
    val errorMessage: String? = null,
    val resolvedAt: Instant? = null,
    val resolutionEvidence: String? = null,
)

enum class OrderIntentState {
    PENDING,
    UNCERTAIN,
    CONFIRMED,
    REJECTED,
}
