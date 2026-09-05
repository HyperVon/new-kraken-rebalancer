package com.gemini.krakenbot.model

import java.math.BigDecimal
import java.time.Instant

/**
 * Categorization of external vs internal provenance for a ledger funding event.
 */
enum class FundingEvidence {
    /** Positively confirmed as external capital entering or leaving the exchange account. */
    EXTERNAL,

    /** Positively confirmed as internal transfer between exchange wallets (e.g. Spot <-> Futures). */
    INTERNAL,

    /** Insufficient authoritative provenance to prove external owner capital or internal move. */
    UNRESOLVED,
}

/**
 * Authoritative record of a deposit transaction from Kraken's DepositStatus API.
 */
class DepositStatusRecord(
    val refid: String,
    val txid: String? = null,
    val asset: String,
    val amount: BigDecimal,
    val fee: BigDecimal = BigDecimal.ZERO,
    val time: Instant,
    val status: String,
    val method: String? = null,
)

/**
 * Authoritative record of a withdrawal transaction from Kraken's WithdrawStatus API.
 */
class WithdrawStatusRecord(
    val refid: String,
    val txid: String? = null,
    val asset: String,
    val amount: BigDecimal,
    val fee: BigDecimal = BigDecimal.ZERO,
    val time: Instant,
    val status: String,
    val method: String? = null,
)

/**
 * Authoritative record of an internal transfer (e.g. Spot <-> Futures wallet transfer).
 */
class InternalTransferRecord(
    val refid: String,
    val asset: String,
    val amount: BigDecimal,
    val time: Instant,
    val sourceWallet: String? = null,
    val destinationWallet: String? = null,
)

/**
 * Resolver interface correlating ledger events with authoritative external/internal funding evidence.
 */
fun interface FundingProvenanceResolver {
    fun resolve(event: LedgerEvent): FundingEvidence

    companion object {
        /** Fallback resolver with no external provenance (all unproven funding classifies as UNRESOLVED). */
        val NONE: FundingProvenanceResolver = FundingProvenanceResolver { FundingEvidence.UNRESOLVED }
    }
}

/**
 * Simple in-memory funding provenance resolver matching ledger rows against authoritative funding records
 * by reference ID, or by close timestamp and asset amount.
 */
class SimpleFundingProvenanceResolver(
    deposits: Collection<DepositStatusRecord> = emptyList(),
    withdrawals: Collection<WithdrawStatusRecord> = emptyList(),
    internalTransfers: Collection<InternalTransferRecord> = emptyList(),
) : FundingProvenanceResolver {

    private val depositsByRefid = deposits.associateBy { it.refid }
    private val withdrawalsByRefid = withdrawals.associateBy { it.refid }
    private val internalTransfersByRefid = internalTransfers.associateBy { it.refid }

    private val allDeposits = deposits.toList()
    private val allWithdrawals = withdrawals.toList()
    private val allInternalTransfers = internalTransfers.toList()

    override fun resolve(event: LedgerEvent): FundingEvidence {
        val refid = event.refid
        // 1. Direct match by reference ID
        if (!refid.isNullOrBlank()) {
            if (internalTransfersByRefid.containsKey(refid)) {
                return FundingEvidence.INTERNAL
            }
            depositsByRefid[refid]?.let { dep ->
                if (isConfirmedExternalDeposit(dep)) return FundingEvidence.EXTERNAL
            }
            withdrawalsByRefid[refid]?.let { with ->
                if (isConfirmedExternalWithdrawal(with)) return FundingEvidence.EXTERNAL
            }
        }

        // 2. Correlation match by asset, amount, and close timestamp (+/- 180s)
        val eventAsset = Asset.normalizeLedgerAsset(event.asset).uppercase()
        val eventAmount = event.amount.abs()

        val matchingInternal = allInternalTransfers.firstOrNull { transfer ->
            Asset.normalizeLedgerAsset(transfer.asset).equals(eventAsset, ignoreCase = true) &&
                transfer.amount.abs().compareTo(eventAmount) == 0 &&
                kotlin.math.abs(transfer.time.epochSecond - event.time.epochSecond) <= 180
        }
        if (matchingInternal != null) return FundingEvidence.INTERNAL

        if (event.type == KrakenApiConstants.LEDGER_TYPE_DEPOSIT) {
            val matchingDep = allDeposits.firstOrNull { dep ->
                Asset.normalizeLedgerAsset(dep.asset).equals(eventAsset, ignoreCase = true) &&
                    dep.amount.abs().compareTo(eventAmount) == 0 &&
                    kotlin.math.abs(dep.time.epochSecond - event.time.epochSecond) <= 180 &&
                    isConfirmedExternalDeposit(dep)
            }
            if (matchingDep != null) return FundingEvidence.EXTERNAL
        } else if (event.type == KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL) {
            val matchingWith = allWithdrawals.firstOrNull { with ->
                Asset.normalizeLedgerAsset(with.asset).equals(eventAsset, ignoreCase = true) &&
                    with.amount.abs().compareTo(eventAmount) == 0 &&
                    kotlin.math.abs(with.time.epochSecond - event.time.epochSecond) <= 180 &&
                    isConfirmedExternalWithdrawal(with)
            }
            if (matchingWith != null) return FundingEvidence.EXTERNAL
        }

        return FundingEvidence.UNRESOLVED
    }

    private fun isConfirmedExternalDeposit(record: DepositStatusRecord): Boolean =
        isStatusConfirmed(record.status) && hasExternalProof(record.txid, record.method)

    private fun isConfirmedExternalWithdrawal(record: WithdrawStatusRecord): Boolean =
        isStatusConfirmed(record.status) && hasExternalProof(record.txid, record.method)

    private fun isStatusConfirmed(status: String): Boolean =
        status.equals("Success", ignoreCase = true) || status.equals("Settled", ignoreCase = true)

    private fun hasExternalProof(txid: String?, method: String?): Boolean =
        !txid.isNullOrBlank() || !method.isNullOrBlank()
}
