package com.gemini.krakenbot.model

import java.math.BigDecimal
import java.time.Duration
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

/** Operational failure while preparing authoritative funding evidence. */
enum class FundingProvenanceFailureReason {
    PERMISSION_DENIED,
    REQUEST_FAILED,
}

data class FundingProvenanceFailure(val reason: FundingProvenanceFailureReason, val message: String)

/**
 * Authoritative record of a deposit transaction from Kraken's DepositStatus API.
 *
 * Kraken exposes both an amount and a fee, but different account-history
 * surfaces may represent the amount as either the requested/gross quantity or
 * the credited/net quantity. The correlator therefore compares both the
 * record amount and its deposit credit (`amount - fee`) to the ledger amount
 * and net delta. [hasAuthoritativeFee] lets it avoid treating a default zero
 * as a confirmed fee value.
 */
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
)

/**
 * Authoritative record of a withdrawal transaction from Kraken's WithdrawStatus API.
 * For correlation, a withdrawal debit is compared using both the requested
 * amount and its account debit magnitude (`amount + fee`).
 */
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
)

/**
 * Authoritative record of an internal transfer (e.g. Spot <-> Futures wallet transfer).
 */
data class InternalTransferRecord(
    val refid: String,
    val asset: String,
    val amount: BigDecimal,
    val time: Instant,
    val sourceWallet: String? = null,
    val destinationWallet: String? = null,
    /** Optional ledger family when the evidence came from a deposit/withdrawal surface. */
    val ledgerType: String? = null,
)

/**
 * Resolver interface correlating ledger events with authoritative external/internal funding evidence.
 */
fun interface FundingProvenanceResolver {
    fun resolve(event: LedgerEvent): FundingEvidence

    fun isCardFunding(event: LedgerEvent): Boolean = false

    /** Non-null when the immutable evidence snapshot could not be prepared. */
    val preparationFailure: FundingProvenanceFailure?
        get() = null

    /**
     * Loads/caches all evidence needed for a batch of ledger rows before
     * [resolve] is called. The returned resolver is the immutable evidence
     * snapshot for this operation; callers must use that return value when
     * classifying the batch. The default keeps the classifier usable by pure,
     * synchronous test resolvers and offline callers.
     */
    suspend fun prepare(events: Collection<LedgerEvent>): FundingProvenanceResolver = this

    companion object {
        /** Fallback resolver with no external provenance (all unproven funding classifies as UNRESOLVED). */
        val NONE: FundingProvenanceResolver = FundingProvenanceResolver { FundingEvidence.UNRESOLVED }

        /** Resolver that keeps all funding rows unresolved while preserving the operational failure reason. */
        fun unavailable(failure: FundingProvenanceFailure): FundingProvenanceResolver =
            object : FundingProvenanceResolver {
                override fun resolve(event: LedgerEvent): FundingEvidence = FundingEvidence.UNRESOLVED

                override val preparationFailure: FundingProvenanceFailure = failure
            }
    }
}

/**
 * Simple in-memory funding provenance resolver matching ledger rows against authoritative funding records.
 *
 * Direct reference matches are still validated against the ledger family,
 * normalized asset, direction, amount/net amount, fee (when both sides know
 * it), timestamp, and terminal status. Fuzzy correlation is accepted only for
 * exactly one compatible candidate; duplicate candidates and external/internal
 * conflicts remain unresolved. A status record is treated as external only
 * when it has a confirmed terminal state and a non-internal transaction proof;
 * the Kraken Spot REST API cannot disambiguate an indistinguishable
 * Spot/Futures ledger leg without an additional internal-transfer source.
 */
class SimpleFundingProvenanceResolver(
    deposits: Collection<DepositStatusRecord> = emptyList(),
    withdrawals: Collection<WithdrawStatusRecord> = emptyList(),
    internalTransfers: Collection<InternalTransferRecord> = emptyList(),
) : FundingProvenanceResolver {

    private val allDeposits = deposits.toList()
    private val allWithdrawals = withdrawals.toList()
    private val allInternalTransfers = internalTransfers.toList()

    private val allRecords = buildList<Any> {
        addAll(allDeposits)
        addAll(allWithdrawals)
        addAll(allInternalTransfers)
    }

    override fun resolve(event: LedgerEvent): FundingEvidence {
        if (event.type.lowercase() !in SUPPORTED_FUNDING_TYPES) {
            return FundingEvidence.UNRESOLVED
        }

        // A non-blank ledger refid is an attempted identity match. If the
        // evidence store contains that identity, do not silently fall back to
        // a coincidental fuzzy record after a family/status/amount mismatch.
        val refid = event.refid?.trim()?.takeIf(String::isNotEmpty)
        if (refid != null) {
            val directRecords = allRecords.filter { recordRefid(it)?.trim() == refid }
            if (directRecords.isNotEmpty()) {
                if (directRecords.size != 1) return FundingEvidence.UNRESOLVED
                val directRecord = directRecords.single()
                val directCandidate = compatibleCandidate(event, directRecord)
                    ?: return FundingEvidence.UNRESOLVED
                // An exact refid is strong identity evidence, but it must not
                // hide a second, independently matching internal-transfer
                // record. A contradiction is safer to leave unresolved than
                // to classify as owner capital on one source alone.
                val competingEvidence = allRecords.asSequence()
                    .filterNot { it === directRecord }
                    .mapNotNull { compatibleCandidate(event, it)?.evidence }
                    .toSet()
                if (competingEvidence.any { it != directCandidate.evidence }) {
                    return FundingEvidence.UNRESOLVED
                }
                return directCandidate.evidence
            }
        }

        val fuzzyCandidates = allRecords.mapNotNull { compatibleCandidate(event, it) }
        return if (fuzzyCandidates.size == 1) {
            fuzzyCandidates.single().evidence
        } else {
            // 0, duplicate, or external/internal conflict are all fail-closed.
            FundingEvidence.UNRESOLVED
        }
    }

    override fun isCardFunding(event: LedgerEvent): Boolean {
        val refid = event.refid?.trim()?.takeIf(String::isNotEmpty)
        val deposit = if (refid != null) {
            allDeposits.firstOrNull { it.refid.trim() == refid }
        } else {
            allDeposits.firstOrNull {
                matchesFundingRecord(
                    event = event,
                    recordAsset = it.asset,
                    recordAmount = it.amount,
                    recordFee = it.fee,
                    recordHasFee = it.hasAuthoritativeFee,
                    recordTime = it.time,
                    eventType = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                )
            }
        } ?: return false
        val method = deposit.method?.lowercase() ?: return false
        return CARD_METHOD_MARKERS.any(method::contains)
    }

    private fun compatibleCandidate(event: LedgerEvent, record: Any): Candidate? = when (record) {
        is DepositStatusRecord -> if (event.type.equals(KrakenApiConstants.LEDGER_TYPE_DEPOSIT, true)) {
            if (matchesFundingRecord(
                    event = event,
                    recordAsset = record.asset,
                    recordAmount = record.amount,
                    recordFee = record.fee,
                    recordHasFee = record.hasAuthoritativeFee,
                    recordTime = record.time,
                    eventType = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                )
            ) {
                Candidate(
                    if (isConfirmedExternalDeposit(record)) FundingEvidence.EXTERNAL else FundingEvidence.UNRESOLVED,
                )
            } else {
                null
            }
        } else {
            null
        }

        is WithdrawStatusRecord -> if (event.type.equals(KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL, true)) {
            if (matchesFundingRecord(
                    event = event,
                    recordAsset = record.asset,
                    recordAmount = record.amount,
                    recordFee = record.fee,
                    recordHasFee = record.hasAuthoritativeFee,
                    recordTime = record.time,
                    eventType = KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL,
                )
            ) {
                Candidate(
                    if (isConfirmedExternalWithdrawal(record)) FundingEvidence.EXTERNAL else FundingEvidence.UNRESOLVED,
                )
            } else {
                null
            }
        } else {
            null
        }

        is InternalTransferRecord -> if (
            matchesInternalLedgerType(event.type, record.ledgerType) &&
            matchesFundingRecord(
                event = event,
                recordAsset = record.asset,
                recordAmount = record.amount,
                recordFee = BigDecimal.ZERO,
                recordHasFee = false,
                recordTime = record.time,
                eventType = event.type,
            )
        ) {
            Candidate(FundingEvidence.INTERNAL)
        } else {
            null
        }

        else -> null
    }

    private fun matchesFundingRecord(
        event: LedgerEvent,
        recordAsset: String,
        recordAmount: BigDecimal,
        recordFee: BigDecimal,
        recordHasFee: Boolean,
        recordTime: Instant,
        eventType: String,
    ): Boolean {
        if (!event.type.equals(eventType, ignoreCase = true)) return false
        if (!event.hasValidFee) return false
        if (event.amount.signum() == 0 || recordAmount.signum() <= 0) return false
        if (eventType.equals(KrakenApiConstants.LEDGER_TYPE_DEPOSIT, ignoreCase = true) && event.amount.signum() < 0) {
            return false
        }
        if (eventType.equals(KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL, ignoreCase = true) &&
            event.amount.signum() > 0
        ) {
            return false
        }
        val normalizedRecordAsset = Asset.normalizeLedgerAsset(recordAsset).trim()
        val normalizedEventAsset = Asset.normalizeLedgerAsset(event.asset).trim()
        if (normalizedRecordAsset.isBlank() || normalizedEventAsset.isBlank() ||
            !normalizedRecordAsset.equals(normalizedEventAsset, ignoreCase = true)
        ) {
            return false
        }
        if (!amountCompatible(event, recordAmount, recordFee, recordHasFee, eventType)) return false
        if (!feeCompatible(event, recordFee, recordHasFee)) return false
        return Duration.between(event.time, recordTime).abs() <= CORRELATION_WINDOW
    }

    private fun matchesInternalLedgerType(eventType: String, recordType: String?): Boolean =
        recordType?.equals(eventType, ignoreCase = true)
            ?: (eventType.lowercase() in OWNER_CAPITAL_TYPES)

    private fun amountCompatible(
        event: LedgerEvent,
        recordAmount: BigDecimal,
        recordFee: BigDecimal,
        recordHasFee: Boolean,
        eventType: String,
    ): Boolean {
        val eventViews = listOf(event.amount.abs(), event.netBalanceDelta().abs())
        val recordViews = buildList {
            add(recordAmount.abs())
            if (recordHasFee) {
                // DepositStatus reports can be represented as either the
                // requested/gross quantity or the credited/net quantity.
                // A withdrawal ledger debit is amount + fee in magnitude,
                // whereas a deposit credit is amount - fee.
                val recordNetAmount = if (
                    eventType.equals(KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL, ignoreCase = true)
                ) {
                    recordAmount.add(recordFee)
                } else {
                    recordAmount.subtract(recordFee)
                }
                add(recordNetAmount.abs())
            }
        }
        return eventViews.any { eventView -> recordViews.any { recordView -> closeEnough(eventView, recordView) } }
    }

    private fun feeCompatible(event: LedgerEvent, recordFee: BigDecimal, recordHasFee: Boolean): Boolean {
        if (event.fee.signum() < 0 || recordFee.signum() < 0) return false
        if (!event.hasAuthoritativeFee || !recordHasFee) return true
        return closeEnough(event.fee.abs(), recordFee.abs())
    }

    private fun closeEnough(left: BigDecimal, right: BigDecimal): Boolean =
        left.subtract(right).abs() <= AMOUNT_TOLERANCE

    private fun recordRefid(record: Any): String? = when (record) {
        is DepositStatusRecord -> record.refid
        is WithdrawStatusRecord -> record.refid
        is InternalTransferRecord -> record.refid
        else -> null
    }

    private data class Candidate(val evidence: FundingEvidence)

    private fun isConfirmedExternalDeposit(record: DepositStatusRecord): Boolean =
        isStatusConfirmed(record.status) && hasExternalProof(record.txid, record.method)

    private fun isConfirmedExternalWithdrawal(record: WithdrawStatusRecord): Boolean =
        isStatusConfirmed(record.status) && hasExternalProof(record.txid, record.method)

    private fun isStatusConfirmed(status: String): Boolean =
        status.equals("Success", ignoreCase = true) || status.equals("Settled", ignoreCase = true)

    private fun hasExternalProof(txid: String?, method: String?): Boolean =
        // A known wallet/Futures marker is evidence against external capital.
        // An unmarked status record is the strongest external evidence exposed
        // by this port; rows absent from that source remain unresolved.
        !method.isInternalFundingMethod() && (!txid.isNullOrBlank() || !method.isNullOrBlank())

    private fun String?.isInternalFundingMethod(): Boolean {
        val normalized = this?.lowercase() ?: return false
        return INTERNAL_METHOD_MARKERS.any(normalized::contains)
    }

    private companion object {
        @JvmField val AMOUNT_TOLERANCE = BigDecimal("0.00000001")

        @JvmField val INTERNAL_METHOD_MARKERS = setOf("futures", "internal", "wallet", "spot")

        @JvmField val CARD_METHOD_MARKERS = setOf("visa", "mastercard", "card", "apple", "google", "pay")

        @JvmField val SUPPORTED_FUNDING_TYPES = setOf(
            KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
            KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL,
            KrakenApiConstants.LEDGER_TYPE_TRANSFER,
        )

        @JvmField val OWNER_CAPITAL_TYPES = SUPPORTED_FUNDING_TYPES

        @JvmField val CORRELATION_WINDOW = Duration.ofSeconds(180)
    }
}
