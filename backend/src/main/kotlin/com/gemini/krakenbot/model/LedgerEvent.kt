package com.gemini.krakenbot.model

import java.math.BigDecimal
import java.time.Instant

/**
 * One entry from the Kraken private Ledgers endpoint (e.g. `staking` rewards, `dividend` payouts,
 * observed top-level `reward` promotion credits, modern `earn` activity, `deposit`, `withdrawal`,
 * `transfer`, observed `conversion`, `adjustment`, and consumer-transaction `spend`/`receive`
 * entries). Amounts are signed (+ for credit, - for debit)
 * and denominated in the ledger asset. Fees are non-negative.
 *
 * [ledgerId] is the Kraken ledger entry id (the response map key), unique per entry;
 * [refid] is the reference id of the parent transaction that caused the entry and may
 * be shared by several entries or absent.
 *
 * Balance-affecting ledger events ([EXTERNAL_BALANCE_TYPES]) participate in actual portfolio
 * reconstruction and comparison; [LedgerFlowClassifier] may still classify a linked conversion
 * group as an internal transformation rather than an external flow. Trade ledger rows
 * (`trade`) are ignored for trade economics because `TradesHistory` is authoritative for trade
 * executions, but the inception balance-continuity validator may use their authoritative
 * balances as checkpoints. Kraken app/Buy Crypto activity is represented by the
 * `spend`/`receive` ledger rows instead.
 */
data class LedgerEvent(
    val ledgerId: String,
    val refid: String? = null,
    val time: Instant,
    val type: String,
    val subtype: String? = null,
    val aclass: String? = null,
    val asset: String,
    val amount: BigDecimal,
    val fee: BigDecimal = BigDecimal.ZERO,
    val balance: BigDecimal = BigDecimal.ZERO,
    /** True when [balance] came from Kraken or a persisted database row; false for synthetic events. */
    val hasAuthoritativeBalance: Boolean = false,
    /** True when the fee was present and parsed at the exchange boundary. */
    val hasAuthoritativeFee: Boolean = fee.signum() != 0,
    /** False when the exchange fee field was malformed or had an impossible negative value. */
    val hasValidFee: Boolean = true,
    /** False when the exchange amount field was missing or malformed at the parser boundary. */
    val hasValidAmount: Boolean = true,
) {
    /**
     * Net balance delta contributed by this ledger event: `amount - fee`.
     * For a credit (+X with fee F), net credit is +X - F.
     * For a debit (-X with fee F), net debit is -X - F.
     */
    fun netBalanceDelta(): BigDecimal = amount.subtract(fee)

    companion object {
        /** Legacy ledger types displayed in the History Rewards chart. */
        val REWARD_TYPES: Set<String> =
            setOf(
                KrakenApiConstants.LEDGER_TYPE_STAKING,
                KrakenApiConstants.LEDGER_TYPE_DIVIDEND,
                KrakenApiConstants.LEDGER_TYPE_REWARD,
            )

        private val TRANSFER_REWARD_SUBTYPES = setOf("airdrop", "reward")

        /** True for legacy/promotion rewards, transfer-based airdrops, and the documented Earn reward subtype. */
        fun isRewardEvent(event: LedgerEvent): Boolean = event.type.lowercase() in REWARD_TYPES ||
            (
                event.type.equals(KrakenApiConstants.LEDGER_TYPE_EARN, ignoreCase = true) &&
                    event.subtype?.trim()?.lowercase() == "reward"
                ) ||
            (
                event.type.equals(KrakenApiConstants.LEDGER_TYPE_TRANSFER, ignoreCase = true) &&
                    event.subtype?.trim()?.lowercase() in TRANSFER_REWARD_SUBTYPES
                )

        /** Genuine owner-capital ledger families; transfer is intentionally not included. */
        val OWNER_CAPITAL_TYPES: Set<String> =
            setOf(
                KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL,
            )

        /**
         * Balance-affecting ledger types that must be retained for reconstruction and comparison.
         * Some rows, notably a complete [KrakenApiConstants.LEDGER_TYPE_CONVERSION] group, are
         * later classified as [FlowCategory.INTERNAL_MOVE] and therefore do not become owner
         * capital or rewards.
         */
        val EXTERNAL_BALANCE_TYPES: Set<String> =
            setOf(
                KrakenApiConstants.LEDGER_TYPE_STAKING,
                KrakenApiConstants.LEDGER_TYPE_DIVIDEND,
                KrakenApiConstants.LEDGER_TYPE_EARN,
                KrakenApiConstants.LEDGER_TYPE_REWARD,
                KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL,
                KrakenApiConstants.LEDGER_TYPE_TRANSFER,
                KrakenApiConstants.LEDGER_TYPE_ADJUSTMENT,
                KrakenApiConstants.LEDGER_TYPE_CONVERSION,
                KrakenApiConstants.LEDGER_TYPE_SPEND,
                KrakenApiConstants.LEDGER_TYPE_RECEIVE,
                KrakenApiConstants.LEDGER_TYPE_MARGIN,
                KrakenApiConstants.LEDGER_TYPE_ROLLOVER,
                KrakenApiConstants.LEDGER_TYPE_SETTLED,
                KrakenApiConstants.LEDGER_TYPE_CREDIT,
                KrakenApiConstants.LEDGER_TYPE_SALE,
            )
    }
}
