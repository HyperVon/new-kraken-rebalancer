package com.gemini.krakenbot.model

import java.math.BigDecimal

/**
 * Classification of a [LedgerEvent] by economic meaning.
 *
 * Kraken reuses coarse ledger `type` values for economically distinct activity
 * (e.g. `transfer` covers both fiat funding between Kraken sub-accounts and
 * internal spot-from-spot wallet moves; `adjustment` covers both manual
 * corrections and fee-rebate credits). Netting every row of a coarse type as
 * owner capital therefore mis-scales ATH and mis-seeds the Buy & Hold
 * benchmark. This classifier applies deterministic, offline-safe rules:
 *
 * - `refid` pairing: rows sharing a non-blank `refid` describe legs of one
 *   economic event. Legs denominated in the SAME normalized asset whose signed
 *   [LedgerEvent.netBalanceDelta] values sum to ~zero (within
 *   [ZERO_NET_TOLERANCE]) are an internal move, not capital. Raw quantities
 *   across different assets never share units, so mixed-asset groups are not
 *   netted: their legs fall back to single-row rules, except funding legs
 *   (`deposit`/`withdrawal`) in a multi-leg group, which become
 *   [FlowCategory.AMBIGUOUS] (part of a larger event, e.g. a conversion).
 * - `subtype` and `refid` internal keywords: Kraken marks internal wallet moves
 *   with subtypes such as `spotfromspot`, `spot to futures`, `allocation`, or
 *   `migration`, or refids matching Futures signatures (e.g. `KF...` or `futures`).
 * - Authoritative external funding rule: Kraken can represent internal Spot/Futures
 *   movements as deposit or withdrawal rows. Absence of subtype is not proof of
 *   external capital, and string-shape heuristics on `refid` are not authoritative.
 *   Only classify as [FlowCategory.OWNER_CAPITAL] when affirmative evidence from a
 *   [FundingProvenanceResolver] proves external provenance (e.g. matching DepositStatus
 *   or WithdrawStatus).
 * - When external provenance is confirmed, owner capital is the net amount entering or
 *   leaving the portfolio ([LedgerEvent.netBalanceDelta]), even if Kraken deducted a fee.
 * - Insufficient evidence: bare deposits or withdrawals without affirmative external
 *   or internal provenance fall back conservatively to [FlowCategory.AMBIGUOUS].
 * - Conservative default: an unpaired `transfer` with no internal subtype is
 *   [FlowCategory.INTERNAL_MOVE] (never owner capital).
 */
enum class FlowCategory {
    /** Genuine funding entering/leaving the strategy; scales ATH and seeds B&H. */
    OWNER_CAPITAL,

    /**
     * Economically unclear funding (e.g. a `deposit` or `withdrawal` with
     * insufficient provenance to exclude internal Futures/wallet moves).
     * Defers ATH updates fail-closed; fails closed in the Buy & Hold comparison.
     */
    AMBIGUOUS,

    /** Strategy-neutral yield or balance change; replays in-kind, never scales ATH. */
    EXTERNAL_BALANCE,

    /** Internal wallet move; ignored by ATH and B&H. */
    INTERNAL_MOVE,

    /** Trade execution row; `TradesHistory` is authoritative, always ignored. */
    TRADE_IGNORED,

    /**
     * Unknown ledger type outside Kraken's documented set; surfaces
     * UNAVAILABLE rather than silently dropping a balance-affecting flow.
     */
    UNSUPPORTED,
}

/** Pure, offline-safe ledger flow classifier. No network, no database. */
object LedgerFlowClassifier {
    private val INTERNAL_SUBTYPE_KEYWORDS =
        listOf(
            "spotfromspot",
            "spottospot",
            "spot to spot",
            "spot-from-spot",
            "internal",
            "wallet",
            // Spot <-> futures wallet movements.
            "futures",
            "spottofutures",
            "spotfromfutures",
            "spot to futures",
            "spot from futures",
            "futurespot",
            "futures to spot",
            // Spot <-> staking wallet movements.
            "spottostaking",
            "spotfromstaking",
            "spot to staking",
            "spot from staking",
            "stakingtospot",
            "stakingfromspot",
            "staking to spot",
            "staking from spot",
            // Earn allocation mechanics and account migrations.
            "allocation",
            "deallocation",
            "migration",
        )

    private val ZERO_NET_TOLERANCE = BigDecimal("0.00000001")

    /**
     * Classifies a single event without group context.
     * Unpaired `transfer` rows default to [FlowCategory.INTERNAL_MOVE].
     */
    fun classify(
        event: LedgerEvent,
        provenanceResolver: FundingProvenanceResolver = FundingProvenanceResolver.NONE,
    ): FlowCategory = classifyAll(listOf(event), provenanceResolver).getValue(event.ledgerId)

    /**
     * Classifies [events], pairing legs by non-blank `refid` first.
     * Returns a map from `ledgerId` to category; unknown ids are absent.
     */
    fun classifyAll(
        events: List<LedgerEvent>,
        provenanceResolver: FundingProvenanceResolver = FundingProvenanceResolver.NONE,
    ): Map<String, FlowCategory> {
        if (events.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, FlowCategory>()
        val byRefid = events.filter { !it.refid.isNullOrBlank() }.groupBy { it.refid!!.trim() }
        for ((_, legs) in byRefid) {
            if (legs.size < 2) continue
            val sameAsset = legs.map { normalizeAsset(it.asset) }.toSet().size == 1
            if (sameAsset) {
                val net =
                    legs.fold(BigDecimal.ZERO) { acc, e ->
                        acc.add(e.netBalanceDelta())
                    }
                if (net.abs().compareTo(ZERO_NET_TOLERANCE) <= 0) {
                    if (legs.any { isFundingType(it.type) } &&
                        legs.any { isPassthroughType(it.type) } &&
                        legs.all { isFundingType(it.type) || isPassthroughType(it.type) }
                    ) {
                        // A confirmed deposit plus an equal USD spend is
                        // economically zero for the benchmark, but it is not
                        // an internal wallet move. Preserve the original
                        // funding provenance; benchmark netting may later
                        // discard the zero-valued typed event.
                        classifyFundingPlumbing(legs, provenanceResolver, result)
                    } else {
                        for (leg in legs) {
                            result[leg.ledgerId] = FlowCategory.INTERNAL_MOVE
                        }
                    }
                    continue
                }

                // A confirmed external deposit/withdrawal can be one leg of
                // Kraken's same-asset funding plumbing (for example a USD
                // deposit followed by a consumer `spend`). Classify the
                // original funding row with its authoritative resolver before
                // benchmark netting; an unresolved or non-plumbing linked
                // group remains ambiguous.
                if (legs.any { isFundingType(it.type) } &&
                    legs.any { isPassthroughType(it.type) } &&
                    legs.all { isFundingType(it.type) || isPassthroughType(it.type) }
                ) {
                    classifyFundingPlumbing(legs, provenanceResolver, result)
                    continue
                }
            }
            // Linked legs that do not prove an internal move are not
            // independent observations: a shared refid means Kraken booked
            // them as one economic event (fee-bearing internal moves,
            // conversions, batch funding). Funding legs inside such a group
            // cannot be proven to be external capital on their own, so they
            // are AMBIGUOUS rather than assumed owner capital.
            for (leg in legs) {
                if (isFundingType(leg.type)) {
                    result[leg.ledgerId] = FlowCategory.AMBIGUOUS
                }
            }
        }
        for (event in events) {
            if (result.containsKey(event.ledgerId)) continue
            result[event.ledgerId] = classifySingle(event, provenanceResolver)
        }
        return result
    }

    private fun classifySingle(event: LedgerEvent, provenanceResolver: FundingProvenanceResolver): FlowCategory {
        if (isInternalSubtype(event.subtype) || isInternalRefid(event.refid)) {
            return FlowCategory.INTERNAL_MOVE
        }
        if (isFundingType(event.type) && !event.subtype.isNullOrBlank()) {
            return FlowCategory.AMBIGUOUS
        }
        return when (event.type.lowercase()) {
            KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
            KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL,
            -> {
                when (provenanceResolver.resolve(event)) {
                    FundingEvidence.EXTERNAL -> FlowCategory.OWNER_CAPITAL
                    FundingEvidence.INTERNAL -> FlowCategory.INTERNAL_MOVE
                    FundingEvidence.UNRESOLVED -> FlowCategory.AMBIGUOUS
                }
            }

            KrakenApiConstants.LEDGER_TYPE_TRANSFER -> FlowCategory.INTERNAL_MOVE

            KrakenApiConstants.LEDGER_TYPE_STAKING,
            KrakenApiConstants.LEDGER_TYPE_DIVIDEND,
            KrakenApiConstants.LEDGER_TYPE_ADJUSTMENT,
            KrakenApiConstants.LEDGER_TYPE_SPEND,
            KrakenApiConstants.LEDGER_TYPE_RECEIVE,
            // Margin-family and sale rows change balances and have no
            // authoritative counterpart in TradesHistory, so the benchmark
            // replays them in-kind (conservative: B&H absorbs the same
            // drag). Only types outside Kraken's documented set fail closed.
            KrakenApiConstants.LEDGER_TYPE_SALE,
            KrakenApiConstants.LEDGER_TYPE_MARGIN,
            KrakenApiConstants.LEDGER_TYPE_ROLLOVER,
            KrakenApiConstants.LEDGER_TYPE_SETTLED,
            KrakenApiConstants.LEDGER_TYPE_CREDIT,
            -> FlowCategory.EXTERNAL_BALANCE

            KrakenApiConstants.LEDGER_TYPE_TRADE,
            -> FlowCategory.TRADE_IGNORED

            else -> FlowCategory.UNSUPPORTED
        }
    }

    private fun isFundingType(type: String): Boolean =
        type.equals(KrakenApiConstants.LEDGER_TYPE_DEPOSIT, ignoreCase = true) ||
            type.equals(KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL, ignoreCase = true)

    private fun isPassthroughType(type: String): Boolean =
        type.equals(KrakenApiConstants.LEDGER_TYPE_SPEND, ignoreCase = true) ||
            type.equals(KrakenApiConstants.LEDGER_TYPE_RECEIVE, ignoreCase = true)

    private fun classifyFunding(event: LedgerEvent, provenanceResolver: FundingProvenanceResolver): FlowCategory {
        // Preserve the deterministic internal markers used by the normal
        // single-row path. Authoritative external evidence cannot turn an
        // explicitly Futures/wallet-labelled leg into owner capital.
        if (isInternalSubtype(event.subtype) || isInternalRefid(event.refid)) {
            return FlowCategory.INTERNAL_MOVE
        }
        if (!event.subtype.isNullOrBlank()) return FlowCategory.AMBIGUOUS
        return when (provenanceResolver.resolve(event)) {
            FundingEvidence.EXTERNAL -> FlowCategory.OWNER_CAPITAL
            FundingEvidence.INTERNAL -> FlowCategory.INTERNAL_MOVE
            FundingEvidence.UNRESOLVED -> FlowCategory.AMBIGUOUS
        }
    }

    private fun classifyFundingPlumbing(
        legs: List<LedgerEvent>,
        provenanceResolver: FundingProvenanceResolver,
        result: MutableMap<String, FlowCategory>,
    ) {
        val fundingCategories = legs.filter { isFundingType(it.type) }
            .associate { it.ledgerId to classifyFunding(it, provenanceResolver) }
        val passthroughCategories = legs.filter { isPassthroughType(it.type) }
            .associate { it.ledgerId to classifySingle(it, provenanceResolver) }
        result.putAll(fundingCategories)
        result.putAll(passthroughCategories)
        val distinctFundingCategories = fundingCategories.values.toSet()
        when {
            distinctFundingCategories.size > 1 ||
                distinctFundingCategories.any { it == FlowCategory.AMBIGUOUS || it == FlowCategory.UNSUPPORTED } -> {
                // A shared refid is one economic event. If its funding legs
                // disagree or one is unproven, do not replay the passthrough
                // leg as an independently observed balance change.
                legs.forEach { result[it.ledgerId] = FlowCategory.AMBIGUOUS }
            }

            distinctFundingCategories.singleOrNull() == FlowCategory.INTERNAL_MOVE &&
                passthroughCategories.values.all {
                    it == FlowCategory.INTERNAL_MOVE ||
                        it == FlowCategory.EXTERNAL_BALANCE
                } -> {
                // Generic authoritative internal evidence must cover every
                // linked leg, not only the deposit/withdrawal row.
                legs.filter { isPassthroughType(it.type) }
                    .forEach { result[it.ledgerId] = FlowCategory.INTERNAL_MOVE }
            }

            passthroughCategories.values.any { it != FlowCategory.EXTERNAL_BALANCE } -> {
                // A linked passthrough leg carrying an explicit internal
                // marker conflicts with an externally proven funding leg.
                // Do not let one side of the shared event become owner
                // capital while the other side is neutral.
                legs.forEach { result[it.ledgerId] = FlowCategory.AMBIGUOUS }
            }
        }
    }

    private fun normalizeAsset(asset: String): String = Asset.normalizeLedgerAsset(asset).uppercase()

    private fun isInternalSubtype(subtype: String?): Boolean {
        if (subtype.isNullOrBlank()) return false
        val normalized = subtype.lowercase().replace("_", "").replace("-", "")
        return INTERNAL_SUBTYPE_KEYWORDS.any { keyword ->
            normalized.contains(keyword.replace(" ", "").replace("_", "").replace("-", ""))
        }
    }

    private fun isInternalRefid(refid: String?): Boolean {
        if (refid.isNullOrBlank()) return false
        val normalized = refid.lowercase().replace("_", "").replace("-", "")
        return normalized.contains("futures") ||
            normalized.startsWith("kf") ||
            normalized.contains("internal")
    }
}
