package com.gemini.krakenbot.model

import java.math.BigDecimal
import java.time.Instant

/**
 * One actual balance effect from a normalized funding group.
 *
 * This is deliberately separate from owner capital: Buy & Hold and ATH scaling
 * consume the synthetic USD owner-capital amount, while ATH basis replay uses
 * these raw per-leg effects to preserve the portfolio's real asset conversion
 * and fee drag exactly once.
 *
 * Each delta carries temporal and ledger identity so basis reconstruction at an
 * arbitrary target time never replayed future card legs prematurely.
 */
data class TimedAssetDelta(val ledgerId: String, val timestamp: Instant, val asset: String, val amount: BigDecimal)

/**
 * Actual balance context for an owner flow, used during historical portfolio basis reconstruction.
 *
 * Distinguishes decision status (whether the economic owner-capital event was already applied to ATH)
 * from whether its actual balance change must be replayed between a predecessor snapshot and a target event.
 */
data class ActualOwnerFlowContext(
    val ledgerId: String,
    val timestamp: Instant,
    val asset: String,
    val actualBalanceDelta: BigDecimal,
    val isCardRepresentative: Boolean = false,
    val normalizedGroupId: String? = null,
)

/**
 * Historical failure context for an already-decided funding group that cannot be structurally reconstructed.
 *
 * Evaluated relative to the basis reconstruction interval (predecessor actual-state boundary, target owner-flow time].
 * Only blocks the basis if it intersects that interval.
 */
data class UnusableDecidedFundingContext(
    val refid: String,
    val sourceLedgerIds: Set<String>,
    val minTime: Instant,
    val maxTime: Instant,
    val reason: String,
)

/**
 * Functional pricing interface for valuing transaction fees into USD.
 */
fun interface CardFeePriceProvider {
    suspend fun getPrice(asset: String, timestamp: Instant): BigDecimal?
}

/**
 * Normalized economic interpretation of a linked card/consumer funding transaction.
 * Consumed identically by ATH high-water mark scaling and the Buy & Hold counterfactual.
 */
sealed interface NormalizedFundingTransaction {
    /**
     * Common interface for external capital entering or leaving the portfolio via normalized card flows.
     */
    sealed interface OwnerFlow : NormalizedFundingTransaction {
        val refid: String
        val eventTime: Instant
        val grossFundingUsd: BigDecimal
        val feeUsd: BigDecimal
        val netOwnerCapitalUsd: BigDecimal
        val actualPortfolioDeltas: List<TimedAssetDelta>
        val sourceLedgerIds: List<String>
        val representativeLedgerId: String
    }

    /**
     * Confirmed external capital entering the portfolio via card/consumer purchase.
     * Synthetic Buy & Hold invests [netOwnerCapitalUsd] strictly by original inception weights;
     * ATH scales its high-water mark by [netOwnerCapitalUsd].
     */
    data class OwnerContribution(
        override val refid: String,
        override val eventTime: Instant,
        override val grossFundingUsd: BigDecimal,
        override val feeUsd: BigDecimal,
        override val netOwnerCapitalUsd: BigDecimal,
        override val actualPortfolioDeltas: List<TimedAssetDelta>,
        override val sourceLedgerIds: List<String>,
        override val representativeLedgerId: String,
    ) : OwnerFlow

    /**
     * Confirmed external capital leaving the portfolio via card withdrawal/refund.
     */
    data class OwnerWithdrawal(
        override val refid: String,
        override val eventTime: Instant,
        override val grossFundingUsd: BigDecimal,
        override val feeUsd: BigDecimal,
        override val netOwnerCapitalUsd: BigDecimal,
        override val actualPortfolioDeltas: List<TimedAssetDelta>,
        override val sourceLedgerIds: List<String>,
        override val representativeLedgerId: String,
    ) : OwnerFlow

    /**
     * Conflicting, incomplete, or unconfirmed funding plumbing that cannot be safely collapsed.
     * ATH updates are deferred fail-closed; Buy & Hold comparison surfaces UNAVAILABLE.
     */
    data class Ambiguous(val refid: String?, val unavailableAt: Instant, val reason: String) :
        NormalizedFundingTransaction

    /**
     * A crypto-denominated fee could not be valued into USD at event time using historical prices.
     */
    data class UnpriceableFee(val refid: String, val asset: String, val unavailableAt: Instant) :
        NormalizedFundingTransaction

    /**
     * Group or row is not a linked card funding/plumbing transaction.
     */
    data object NotApplicable : NormalizedFundingTransaction
}
