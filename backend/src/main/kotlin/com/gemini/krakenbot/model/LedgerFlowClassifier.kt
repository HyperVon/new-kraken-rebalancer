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
 *   economic event. Legs whose signed [LedgerEvent.netBalanceDelta] values sum
 *   to ~zero (within [ZERO_NET_TOLERANCE]) are an internal move, not capital.
 * - `subtype` keywords: Kraken marks internal wallet moves with subtypes such
 *   as `spotfromspot`, `spot to spot`, or `internal`.
 * - Conservative default: an unpaired `transfer` with no internal subtype is
 *   [FlowCategory.INTERNAL_MOVE] (never owner capital). Only `deposit` and
 *   `withdrawal` default to [FlowCategory.OWNER_CAPITAL].
 */
enum class FlowCategory {
    /** Genuine funding entering/leaving the strategy; scales ATH and seeds B&H. */
    OWNER_CAPITAL,

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
        )

    private val ZERO_NET_TOLERANCE = BigDecimal("0.00000001")

    /**
     * Classifies a single event without group context.
     * Unpaired `transfer` rows default to [FlowCategory.INTERNAL_MOVE].
     */
    fun classify(event: LedgerEvent): FlowCategory = classifyAll(listOf(event)).getValue(event.ledgerId)

    /**
     * Classifies [events], pairing legs by non-blank `refid` first.
     * Returns a map from `ledgerId` to category; unknown ids are absent.
     */
    fun classifyAll(events: List<LedgerEvent>): Map<String, FlowCategory> {
        if (events.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, FlowCategory>()
        val byRefid = events.filter { !it.refid.isNullOrBlank() }.groupBy { it.refid!! }
        val pairedIds = mutableSetOf<String>()
        for ((_, legs) in byRefid) {
            if (legs.size < 2) continue
            val net =
                legs.fold(BigDecimal.ZERO) { acc, e ->
                    acc.add(e.netBalanceDelta())
                }
            if (net.abs().compareTo(ZERO_NET_TOLERANCE) <= 0) {
                for (leg in legs) {
                    result[leg.ledgerId] = FlowCategory.INTERNAL_MOVE
                    pairedIds.add(leg.ledgerId)
                }
            }
        }
        for (event in events) {
            if (result.containsKey(event.ledgerId)) continue
            result[event.ledgerId] = classifySingle(event)
        }
        return result
    }

    private fun classifySingle(event: LedgerEvent): FlowCategory {
        if (isInternalSubtype(event.subtype)) return FlowCategory.INTERNAL_MOVE
        return when (event.type) {
            KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
            KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL,
            -> FlowCategory.OWNER_CAPITAL

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

    private fun isInternalSubtype(subtype: String?): Boolean {
        if (subtype.isNullOrBlank()) return false
        val normalized = subtype.lowercase().replace("_", "").replace("-", "")
        return INTERNAL_SUBTYPE_KEYWORDS.any { keyword ->
            normalized.contains(keyword.replace(" ", "").replace("_", "").replace("-", ""))
        }
    }
}
