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

    /**
     * One synthetic reallocation for a single inferred configuration-regime change.
     *
     * The reset is **membership-scoped**: it moves only what [InferredRegimeTransition] actually
     * states changed, and nothing else.
     *
     * - [InferredRegimeTransition.removals] leave the synthetic benchmark entirely. Their synthetic
     *   value is released and becomes this reset's funding pool.
     * - [InferredRegimeTransition.additions] are bought from that pool, split by
     *   [additionFundingShares].
     * - Every asset named by neither set keeps its exact existing units. Routine Actual drift at the
     *   anchor never rewrites it, and a transiently zero Actual balance never removes it.
     * - Benchmark cash changes only by this reset's own arithmetic: released value is spent on the
     *   additions and any unspent remainder, or all of it when there are no additions, lands in USD.
     *   Actual's incidental cash buffer is never copied.
     *
     * The benchmark keeps its own NAV throughout, so a reallocation is value-preserving by
     * construction. The full book is never liquidated, which is why the economically required
     * turnover is the sum of the value deltas rather than the portfolio twice over.
     *
     * @param additionFundingShares share of the released value assigned to each named addition.
     *   Derived from retained evidence and normalized across the addition set alone; never from
     *   whole-portfolio proportions. Empty when the transition names no additions.
     * @param configurationAllocation the benchmark's configuration allocation after this transition,
     *   used to invest later owner contributions. It carries forward the surviving relative weights
     *   unchanged and adds or removes only the assets this transition names.
     */
    data class ConfigurationReset(
        override val timestamp: Instant,
        val transition: InferredRegimeTransition,
        val additionFundingShares: Map<String, BigDecimal>,
        val configurationAllocation: Map<String, BigDecimal>,
    ) : BenchmarkEvent()
}

/**
 * Economically required synthetic turnover for one configuration reset.
 *
 * A reset is a value-preserving reallocation, so only the per-asset *deltas* are trades. Liquidating
 * and repurchasing the whole book would describe trades the benchmark never needs to make, and would
 * inflate any future fee estimate proportionally.
 */
data class ConfigurationResetTurnover(
    /** Synthetic value per asset immediately before the reset, valued at reset-time prices. */
    val preValue: Map<String, BigDecimal>,
    /** Synthetic value per asset immediately after the reset, at the same prices. */
    val postValue: Map<String, BigDecimal>,
    /** [postValue] minus [preValue] per asset. A named removal is a negative crypto delta. */
    val delta: Map<String, BigDecimal>,
    /** Crypto value that must be sold to reach [postValue]. */
    val cryptoSellNotional: BigDecimal,
    /** Crypto value that must be bought to reach [postValue]. */
    val cryptoBuyNotional: BigDecimal,
    /**
     * Cash movement. This is settlement capital, not a trade: released value that was not spent on
     * additions, or the cash raised to fund them. It must never be charged a trading fee.
     */
    val cashDelta: BigDecimal,
)

/**
 * Contribution-time market prices for Buy & Hold owner-flow allocation.
 * Returns null when no trustworthy price exists; callers fail closed.
 * Never backed by a live ticker for old events — only recorded history.
 */
fun interface HistoricalPriceProvider {
    suspend fun priceAt(symbol: String, time: Instant): BigDecimal?
}
