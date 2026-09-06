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
 *   [ZERO_NET_TOLERANCE]) are an internal move only when every leg is
 *   independently classified as internal. Raw quantities across different
 *   assets are never netted. A mixed-asset funding/plumbing group is handled
 *   as one typed event after its funding provenance is checked.
 * - Exact documented subtype semantics: Kraken marks internal wallet moves with
 *   subtypes such as `spotfromspot`, `spottofutures`, `spottostaking`,
 *   `allocation`, or `migration`. Opaque `refid` strings are never parsed for
 *   business meaning.
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
 * - Conservative transfer rule: known internal transfer evidence is
 *   [FlowCategory.INTERNAL_MOVE], documented reward semantics are
 *   [FlowCategory.EXTERNAL_BALANCE], and an unproven bare transfer (including
 *   prose-only descriptions like airdrop, fork, or distribution that Kraken does not
 *   formally document as API subtype values) is [FlowCategory.AMBIGUOUS].
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
    private val INTERNAL_SUBTYPES = setOf(
        // Documented Spot/Futures and Spot/staking transfer subtypes.
        "spotfromspot",
        "spottospot",
        "spottostaking",
        "spotfromstaking",
        "stakingtospot",
        "stakingfromspot",
        "spottofutures",
        "spotfromfutures",
        // Earn allocation mechanics and account migrations.
        "allocation",
        "deallocation",
        "autoallocate",
        "migration",
    )

    private val EARN_REWARD_SUBTYPE = "reward"

    private val TRANSFER_EXTERNAL_SUBTYPES = setOf(
        "reward",
    )

    private val ZERO_NET_TOLERANCE = BigDecimal("0.00000001")

    /**
     * Classifies a single event without group context.
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
            val individualCategories = legs.associate { it.ledgerId to classifySingle(it, provenanceResolver) }
            if (sameAsset && legs.none { isPassthroughType(it.type) }) {
                val net =
                    legs.fold(BigDecimal.ZERO) { acc, e ->
                        acc.add(e.netBalanceDelta())
                    }
                if (net.abs().compareTo(ZERO_NET_TOLERANCE) <= 0) {
                    if (canInferInternalZeroNetGroup(legs, individualCategories)) {
                        for (leg in legs) {
                            result[leg.ledgerId] = FlowCategory.INTERNAL_MOVE
                        }
                        continue
                    }
                }
            }
            // A confirmed external deposit/withdrawal can be one leg of
            // Kraken's mixed-asset funding plumbing (for example a card
            // deposit followed by USD spend and BTC receive). Classify the
            // original funding row before benchmark netting, regardless of
            // whether the linked group contains one or several assets.
            if (legs.any { isFundingType(it.type) } &&
                legs.any { isPassthroughType(it.type) } &&
                legs.all { isFundingType(it.type) || isPassthroughType(it.type) }
            ) {
                classifyFundingPlumbing(legs, provenanceResolver, result)
                continue
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
        val type = event.type.lowercase()
        val evidence = if (isFundingType(type) || type == KrakenApiConstants.LEDGER_TYPE_TRANSFER) {
            provenanceResolver.resolve(event)
        } else {
            FundingEvidence.UNRESOLVED
        }
        val internalSubtype = isInternalSubtype(event.subtype)
        return when (type) {
            KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
            KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL,
            -> {
                when {
                    internalSubtype && evidence == FundingEvidence.EXTERNAL -> FlowCategory.AMBIGUOUS
                    internalSubtype || evidence == FundingEvidence.INTERNAL -> FlowCategory.INTERNAL_MOVE
                    !event.subtype.isNullOrBlank() -> FlowCategory.AMBIGUOUS
                    evidence == FundingEvidence.EXTERNAL -> FlowCategory.OWNER_CAPITAL
                    else -> FlowCategory.AMBIGUOUS
                }
            }

            KrakenApiConstants.LEDGER_TYPE_TRANSFER -> when {
                internalSubtype && evidence == FundingEvidence.EXTERNAL -> FlowCategory.AMBIGUOUS

                internalSubtype -> FlowCategory.INTERNAL_MOVE

                isKnownTransferExternalSubtype(event.subtype) && evidence == FundingEvidence.INTERNAL ->
                    FlowCategory.AMBIGUOUS

                isKnownTransferExternalSubtype(event.subtype) || evidence == FundingEvidence.EXTERNAL ->
                    FlowCategory.EXTERNAL_BALANCE

                evidence == FundingEvidence.INTERNAL -> FlowCategory.INTERNAL_MOVE

                else -> FlowCategory.AMBIGUOUS
            }

            KrakenApiConstants.LEDGER_TYPE_EARN -> when (normalizeSubtype(event.subtype)) {
                EARN_REWARD_SUBTYPE -> FlowCategory.EXTERNAL_BALANCE
                in INTERNAL_SUBTYPES -> FlowCategory.INTERNAL_MOVE
                else -> FlowCategory.AMBIGUOUS
            }

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

    private fun classifyFunding(event: LedgerEvent, provenanceResolver: FundingProvenanceResolver): FlowCategory =
        classifySingle(event, provenanceResolver)

    private fun classifyFundingPlumbing(
        legs: List<LedgerEvent>,
        provenanceResolver: FundingProvenanceResolver,
        result: MutableMap<String, FlowCategory>,
    ) {
        val fundingCategories = legs.filter { isFundingType(it.type) }
            .associate { it.ledgerId to classifyFunding(it, provenanceResolver) }
        val passthroughCategories = legs.filter { isPassthroughType(it.type) }
            .associate { it.ledgerId to classifySingle(it, provenanceResolver) }
        val hasInternalPassthroughSubtype = legs.any { isPassthroughType(it.type) && isInternalSubtype(it.subtype) }
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

            hasInternalPassthroughSubtype && distinctFundingCategories.singleOrNull() != FlowCategory.INTERNAL_MOVE -> {
                // A semantic internal marker on a passthrough leg conflicts
                // with the external-funding interpretation of this shared
                // event unless the funding leg was also proven internal.
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

    private fun isInternalSubtype(subtype: String?): Boolean = normalizeSubtype(subtype) in INTERNAL_SUBTYPES

    private fun isKnownTransferExternalSubtype(subtype: String?): Boolean =
        normalizeSubtype(subtype) in TRANSFER_EXTERNAL_SUBTYPES

    private fun normalizeSubtype(subtype: String?): String = subtype.orEmpty()
        .lowercase()
        .replace("_", "")
        .replace("-", "")
        .replace(" ", "")

    private fun canInferInternalZeroNetGroup(
        legs: List<LedgerEvent>,
        individualCategories: Map<String, FlowCategory>,
    ): Boolean {
        if (legs.any { isKnownTransferExternalSubtype(it.subtype) }) return false
        // A zero net is only a conservation check. It is not affirmative
        // provenance, so an unresolved deposit/withdrawal or transfer pair
        // must remain ambiguous rather than being silently discarded.
        if (individualCategories.values.any { it != FlowCategory.INTERNAL_MOVE }) return false
        return legs.all {
            isFundingType(it.type) ||
                it.type.equals(KrakenApiConstants.LEDGER_TYPE_TRANSFER, ignoreCase = true) ||
                (
                    it.type.equals(KrakenApiConstants.LEDGER_TYPE_EARN, ignoreCase = true) &&
                        isInternalSubtype(it.subtype)
                    )
        }
    }
}
