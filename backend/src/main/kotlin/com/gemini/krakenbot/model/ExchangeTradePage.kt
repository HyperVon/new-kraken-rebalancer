package com.gemini.krakenbot.model

/** One Kraken trade page used to confirm fills before the Rebalancer spends sell proceeds. */
data class ExchangeTradePage(
    val entries: List<ExchangeTradeFill>,
    val nextCursor: String?,
    val valid: Boolean,
)

/** Trade fields needed to prove a USD sell fill and calculate its net proceeds. */
data class ExchangeTradeFill(
    val tradeId: String,
    val orderTxid: String?,
    val baseAsset: String?,
    val quoteCurrency: String?,
    val feeCurrency: String?,
    val type: String?,
    val price: String?,
    val cost: String?,
    val fee: String?,
    val volume: String?,
    val hasValidTime: Boolean,
    val hasValidPrice: Boolean,
    val hasValidCost: Boolean,
    val hasValidFee: Boolean,
    val hasValidVolume: Boolean,
    val isValidRecord: Boolean,
)
