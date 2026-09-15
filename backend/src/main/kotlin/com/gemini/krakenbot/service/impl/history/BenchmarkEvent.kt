package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.model.LedgerEvent
import java.math.BigDecimal
import java.time.Instant

/**
 * Economic events that affect either actual portfolio balances or synthetic Buy & Hold holdings.
 */
sealed class BenchmarkEvent : Comparable<BenchmarkEvent> {
    abstract val timestamp: Instant

    override fun compareTo(other: BenchmarkEvent): Int = this.timestamp.compareTo(other.timestamp)

    /**
     * Strategy-neutral external balance movement (for example a reward or adjustment). Replays
     * in-kind when the movement belongs to the synthetic thesis; holding-dependent reward credits
     * in an asset the basket never held are actual-only. Explicitly classified account-level
     * credits may introduce their credited asset. Consumer-transaction and conversion plumbing is
     * consumed as evidence and does not become a synthetic event in pure Buy & Hold.
     */
    data class ExternalBalance(
        override val timestamp: Instant,
        val asset: String,
        val netAmount: BigDecimal,
        val event: LedgerEvent,
        /** Original ledger identities represented by this economic event. */
        val sourceLedgerIds: List<String> = listOf(event.ledgerId),
    ) : BenchmarkEvent()

    /**
     * Genuine owner contribution after the selected anchor, allocated by the fixed recorded-anchor
     * value weights (never added to the contributed asset alone:
     * that would leave new money in cash and invent Rebalancer alpha).
     * Existing synthetic holdings are untouched. [allocations] maps normalized
     * asset symbol to units bought at contribution-time prices.
     */
    data class OwnerContribution(
        override val timestamp: Instant,
        val contributionUsd: BigDecimal,
        val allocations: Map<String, BigDecimal>,
        val event: LedgerEvent,
        /** Original ledger identities represented by this economic event. */
        val sourceLedgerIds: List<String> = listOf(event.ledgerId),
        /** Source times of every ledger leg represented by this economic event. */
        val sourceEventTimestamps: Set<Instant> = setOf(event.time),
    ) : BenchmarkEvent()

    /**
     * Genuine owner withdrawal after the selected benchmark anchor. Replays as a
     * proportional reduction of the whole synthetic portfolio by market value, so
     * the cash event itself creates no artificial alpha for either side.
     */
    data class OwnerWithdrawal(
        override val timestamp: Instant,
        val withdrawalUsd: BigDecimal,
        val event: LedgerEvent,
        /** Original ledger identities represented by this economic event. */
        val sourceLedgerIds: List<String> = listOf(event.ledgerId),
        /** Source times of every ledger leg represented by this economic event. */
        val sourceEventTimestamps: Set<Instant> = setOf(event.time),
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
