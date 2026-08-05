package com.gemini.krakenbot.model

import java.math.BigDecimal
import java.time.Instant

/**
 * One entry from the Kraken private Ledgers endpoint (e.g. `staking` rewards and
 * `dividend` payouts). Amounts are signed and denominated in the ledger asset.
 */
data class LedgerEvent(
    val refid: String,
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
