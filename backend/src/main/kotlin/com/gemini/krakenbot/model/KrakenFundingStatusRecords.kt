package com.gemini.krakenbot.model

import java.math.BigDecimal
import java.time.Instant

/** Kraken DepositStatus evidence used when checking continuity of prospective observations. */
data class DepositStatusRecord(
    val refid: String,
    val txid: String? = null,
    val asset: String,
    val amount: BigDecimal,
    val fee: BigDecimal = BigDecimal.ZERO,
    val time: Instant,
    val status: String,
    val method: String? = null,
    val hasAuthoritativeFee: Boolean = fee.signum() != 0,
    val methodId: String? = null,
)

/** Kraken WithdrawStatus evidence used when checking continuity of prospective observations. */
data class WithdrawStatusRecord(
    val refid: String,
    val txid: String? = null,
    val asset: String,
    val amount: BigDecimal,
    val fee: BigDecimal = BigDecimal.ZERO,
    val time: Instant,
    val status: String,
    val method: String? = null,
    val hasAuthoritativeFee: Boolean = fee.signum() != 0,
    val methodId: String? = null,
)

/** Optional authoritative wallet transfer evidence used by the prospective event verifier. */
data class InternalTransferRecord(
    val refid: String,
    val asset: String,
    val amount: BigDecimal,
    val time: Instant,
    val sourceWallet: String? = null,
    val destinationWallet: String? = null,
    val ledgerType: String? = null,
)
