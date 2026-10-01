package com.gemini.krakenbot.model

/**
 * Collapses the exact SOL/SOL03 mirror rows retained by older Kraken history imports.
 * Conflicting rows and every other repeated ledger id remain visible for validation to reject.
 */
object LedgerEventAliasCanonicalizer {
    fun collapseExactSolAliasMirrors(events: List<LedgerEvent>): List<LedgerEvent> {
        val canonicalEventsById = events
            .groupBy(LedgerEvent::ledgerId)
            .mapNotNull { (ledgerId, rows) ->
                canonicalSolMirror(rows)?.let { ledgerId to it }
            }
            .toMap()
        if (canonicalEventsById.isEmpty()) return events

        val emittedIds = mutableSetOf<String>()
        return events.mapNotNull { event ->
            val canonical = canonicalEventsById[event.ledgerId] ?: return@mapNotNull event
            if (emittedIds.add(event.ledgerId)) canonical else null
        }
    }

    private fun canonicalSolMirror(rows: List<LedgerEvent>): LedgerEvent? {
        if (rows.size != 2) return null
        val sol = rows.singleOrNull { it.asset.trim().uppercase() == Asset.SOL } ?: return null
        val sol03 = rows.singleOrNull { it.asset.trim().uppercase() == SOL03_ALIAS } ?: return null
        if (!sameEvidenceExceptAsset(sol, sol03)) return null
        return sol.copy(asset = Asset.SOL)
    }

    private fun sameEvidenceExceptAsset(first: LedgerEvent, second: LedgerEvent): Boolean =
        first.ledgerId == second.ledgerId &&
            first.refid == second.refid &&
            first.time == second.time &&
            first.type == second.type &&
            first.subtype == second.subtype &&
            first.aclass == second.aclass &&
            first.amount.compareTo(second.amount) == 0 &&
            first.fee.compareTo(second.fee) == 0 &&
            first.balance.compareTo(second.balance) == 0 &&
            first.hasAuthoritativeBalance == second.hasAuthoritativeBalance &&
            first.hasAuthoritativeFee == second.hasAuthoritativeFee &&
            first.hasValidFee == second.hasValidFee &&
            first.hasValidAmount == second.hasValidAmount

    private const val SOL03_ALIAS = "SOL03"
}
