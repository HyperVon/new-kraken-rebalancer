package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.model.TradeOwnership
import com.gemini.krakenbot.model.TradeRecord
import java.math.BigDecimal
import java.time.Instant

/**
 * Economic events that affect either actual portfolio balances or synthetic Buy & Hold holdings.
 */
sealed class BenchmarkEvent : Comparable<BenchmarkEvent> {
    abstract val timestamp: Instant

    override fun compareTo(other: BenchmarkEvent): Int = this.timestamp.compareTo(other.timestamp)

    /**
     * Strategy-neutral external balance movement (rewards, adjustments, or
     * consumer-transaction spend/receive legs). Replays in-kind: the Buy & Hold
     * portfolio absorbs the same balance change. Owner capital and internal
     * moves have their own categories below.
     */
    data class ExternalBalance(
        override val timestamp: Instant,
        val asset: String,
        val netAmount: BigDecimal,
        val event: LedgerEvent,
    ) : BenchmarkEvent()

    /**
     * Genuine owner contribution after inception, allocated by ORIGINAL
     * inception value weights (never added to the contributed asset alone:
     * that would leave new money in cash and invent Rebalancer alpha).
     * Existing synthetic holdings are untouched. [allocations] maps normalized
     * asset symbol to units bought at contribution-time prices.
     */
    data class OwnerContribution(
        override val timestamp: Instant,
        val contributionUsd: BigDecimal,
        val allocations: Map<String, BigDecimal>,
        val event: LedgerEvent,
    ) : BenchmarkEvent()

    /**
     * Genuine owner withdrawal after inception. Replays as a proportional
     * reduction of the whole synthetic portfolio by market value, so the cash
     * event itself creates no artificial alpha for either side.
     */
    data class OwnerWithdrawal(
        override val timestamp: Instant,
        val withdrawalUsd: BigDecimal,
        val event: LedgerEvent,
    ) : BenchmarkEvent()

    /**
     * Trade execution (rebalancer bot, manual user trade, or unknown provenance).
     */
    data class Trade(
        override val timestamp: Instant,
        val trade: TradeRecord,
        val ownership: TradeOwnership,
        val usdNotional: BigDecimal,
    ) : BenchmarkEvent()
}

/**
 * Contribution-time market prices for Buy & Hold owner-flow allocation.
 * Returns null when no trustworthy price exists; callers fail closed.
 * Never backed by a live ticker for old events — only recorded history.
 */
fun interface HistoricalPriceProvider {
    suspend fun priceAt(symbol: String, time: Instant): BigDecimal?
}
