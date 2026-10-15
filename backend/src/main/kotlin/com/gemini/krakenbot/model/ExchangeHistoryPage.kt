package com.gemini.krakenbot.model

import java.time.Instant

/** One cursor response plus the immutable request bounds that produced it. */
data class ExchangeHistoryPage<T>(
    val entries: List<T>,
    val startSec: Long?,
    val endSec: Long?,
    val requestedCursor: String?,
    val nextCursor: String?,
    /** Kraken omits this when count retrieval is disabled or cursor paging skips the query. */
    val count: Long?,
    /** Number of raw entries in Kraken's container before any consumer-specific filtering. */
    val rawSize: Int,
    /** Envelope, record-container, and cursor metadata were structurally usable. */
    val valid: Boolean,
)

/** A native Kraken TradesHistory row with source strings retained without precision loss. */
data class ExchangeTradeHistoryEntry(
    /** Kraken's trade transaction ID, supplied as the key in the `trades` object. */
    val tradeId: String,
    val orderTxid: String?,
    val postTxid: String?,
    val pair: String?,
    val baseAsset: String?,
    val quoteCurrency: String?,
    /** Kraken TradesHistory does not return the fee denomination explicitly. */
    val feeCurrency: String?,
    val time: Instant?,
    val rawTime: String?,
    val type: String?,
    val orderType: String?,
    val price: String?,
    val cost: String?,
    val fee: String?,
    val volume: String?,
    val margin: String?,
    val misc: String?,
    val tradeSequenceId: String?,
    val maker: Boolean?,
    val ledgerIds: List<String>?,
    /** Compact native row JSON preserves future Kraken fields not represented above. */
    val rawRecordJson: String?,
    val hasValidTime: Boolean,
    val hasValidPrice: Boolean,
    val hasValidCost: Boolean,
    val hasValidFee: Boolean,
    val hasValidVolume: Boolean,
    val isValidRecord: Boolean,
)

/** A native Kraken Ledgers row; asset codes such as `DOT.F` stay exactly as returned. */
data class ExchangeLedgerHistoryEntry(
    /** Kraken's ledger transaction ID, supplied as the key in the `ledger` object. */
    val ledgerId: String,
    val refid: String?,
    val time: Instant?,
    val rawTime: String?,
    val type: String?,
    val subtype: String?,
    val assetClass: String?,
    val asset: String?,
    val amount: String?,
    val fee: String?,
    val balance: String?,
    /** Compact native row JSON preserves future Kraken fields not represented above. */
    val rawRecordJson: String?,
    val hasValidTime: Boolean,
    val hasValidAmount: Boolean,
    val hasValidFee: Boolean,
    val hasValidBalance: Boolean,
    val isValidRecord: Boolean,
)
