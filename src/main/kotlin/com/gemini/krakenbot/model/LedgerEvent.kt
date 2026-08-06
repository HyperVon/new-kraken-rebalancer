package com.gemini.krakenbot.model

import java.math.BigDecimal
import java.time.Instant

/**
 * One entry from the Kraken private Ledgers endpoint (e.g. `staking` rewards and
 * `dividend` payouts). Amounts are signed and denominated in the ledger asset.
 *
 * [ledgerId] is the Kraken ledger entry id (the response map key), unique per entry;
 * [refid] is the reference id of the parent transaction that caused the entry and may
 * be shared by several entries or absent.
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
    companion object {
        const val TYPE_STAKING = "staking"
        const val TYPE_DIVIDEND = "dividend"
    }
}
