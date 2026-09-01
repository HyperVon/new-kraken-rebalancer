package com.gemini.krakenbot.model

import java.math.BigDecimal
import java.time.Instant

/**
 * One entry from the Kraken private Ledgers endpoint (e.g. `staking` rewards, `dividend` payouts,
 * `deposit`, `withdrawal`, and `transfer` entries). Amounts are signed (+ for credit, - for debit)
 * and denominated in the ledger asset. Fees are non-negative.
 *
 * [ledgerId] is the Kraken ledger entry id (the response map key), unique per entry;
 * [refid] is the reference id of the parent transaction that caused the entry and may
 * be shared by several entries or absent.
 *
 * Strategy-neutral external balance events ([EXTERNAL_BALANCE_TYPES]) affect both actual portfolio
 * balance reconciliation and the synthetic Buy & Hold benchmark equally. Trade ledger rows
 * (`trade`) are ignored because `TradesHistory` is authoritative for trade executions.
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
) {
    /**
     * Net balance delta contributed by this ledger event: `amount - fee`.
     * For a credit (+X with fee F), net credit is +X - F.
     * For a debit (-X with fee F), net debit is -X - F.
     */
    fun netBalanceDelta(): BigDecimal = amount.subtract(fee)

    companion object {
        /** Ledger types displayed in the History Rewards chart (staking rewards and asset/cash dividends). */
        val REWARD_TYPES: Set<String> =
            setOf(
                KrakenApiConstants.LEDGER_TYPE_STAKING,
                KrakenApiConstants.LEDGER_TYPE_DIVIDEND,
            )

        /** Strategy-neutral external balance ledger types that alter account balances without rebalancing trades. */
        val EXTERNAL_BALANCE_TYPES: Set<String> =
            setOf(
                KrakenApiConstants.LEDGER_TYPE_STAKING,
                KrakenApiConstants.LEDGER_TYPE_DIVIDEND,
                KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL,
                KrakenApiConstants.LEDGER_TYPE_TRANSFER,
                KrakenApiConstants.LEDGER_TYPE_ADJUSTMENT,
                KrakenApiConstants.LEDGER_TYPE_SPEND,
                KrakenApiConstants.LEDGER_TYPE_RECEIVE,
            )
    }
}
