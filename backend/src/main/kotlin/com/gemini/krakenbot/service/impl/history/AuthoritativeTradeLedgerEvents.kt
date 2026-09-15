package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.KrakenApiConstants
import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.model.LedgerFlowClassifier
import com.gemini.krakenbot.model.TradeRecord

/**
 * Authoritative `type=trade` ledger rows whose execution has no retained successful TradeRecord.
 *
 * Pre-inception retention prunes TradesHistory, so a complete ledger group can be the only
 * retained evidence of an execution that moved the strategy wallet. Only structurally proven
 * groups are replayable; incomplete or contradictory groups stay fail-closed at the call site.
 */
internal object AuthoritativeTradeLedgerEvents {
    private const val MAX_LEG_SKEW_MILLIS = 1000L

    data class Inventory(
        val replayableLegs: List<LedgerEvent>,
        val replayableRefIds: Set<String>,
        val incompleteRefIds: Set<String>,
        val contradictoryRefIds: Set<String>,
        val nonSpotRefIds: Set<String>,
        /** Refids that claim more than one retained durable trade identity. */
        val ambiguousIdentityRefIds: Set<String> = emptySet(),
        /** Secondary identity bindings for a retained trade's authoritative ledger legs. */
        val tradeLegsByTradeIdentity: Map<String, List<LedgerEvent>> = emptyMap(),
    )

    fun collect(
        ledgers: List<LedgerEvent>,
        trades: List<TradeRecord>,
        resolvedScopes: Map<String, AuthoritativeLedgerBalanceValidator.LedgerWalletScope>,
    ): Inventory {
        val matchedTradeIds = trades
            .asSequence()
            .mapNotNull { it.tradeId?.trim()?.takeIf(String::isNotEmpty) }
            .toSet()
        val groups = ledgers
            .asSequence()
            .filter { it.type.equals(KrakenApiConstants.LEDGER_TYPE_TRADE, ignoreCase = true) }
            .filter { !it.refid.isNullOrBlank() }
            .groupBy { it.refid!!.trim() }

        val replayableLegs = mutableListOf<LedgerEvent>()
        val replayableRefIds = linkedSetOf<String>()
        val incompleteRefIds = linkedSetOf<String>()
        val contradictoryRefIds = linkedSetOf<String>()
        val nonSpotRefIds = linkedSetOf<String>()
        val ambiguousIdentityRefIds = linkedSetOf<String>()
        val tradeLegsByTradeIdentity = mutableMapOf<String, List<LedgerEvent>>()
        val refIdByTradeIdentity = mutableMapOf<String, String>()

        for ((refId, legs) in groups) {
            if (refId in matchedTradeIds) continue
            val scopes = legs.map { resolvedScopes[it.ledgerId] }
            if (scopes.any { it != null && it != AuthoritativeLedgerBalanceValidator.LedgerWalletScope.SPOT }) {
                nonSpotRefIds += refId
                continue
            }
            val durableMatches = trades.filter { trade ->
                trade.success && !trade.dryRun &&
                    setOfNotNull(trade.orderTxid?.trim(), trade.clientOrderId?.trim()).contains(refId)
            }
            if (durableMatches.size > 1) {
                ambiguousIdentityRefIds += refId
                continue
            }
            if (durableMatches.size == 1) {
                val trade = durableMatches.single()
                // A durable match necessarily has a non-blank order/client identity, so the
                // shared key cannot be null here.
                val identityKey = TradeLedgerReplay.identityKey(trade)!!
                val previousRefId = refIdByTradeIdentity[identityKey]
                if (previousRefId != null) {
                    ambiguousIdentityRefIds += refId
                    ambiguousIdentityRefIds += previousRefId
                    refIdByTradeIdentity.remove(identityKey)
                    tradeLegsByTradeIdentity.remove(identityKey)
                    continue
                }
                refIdByTradeIdentity[identityKey] = refId
                tradeLegsByTradeIdentity[identityKey] = legs
                continue
            }

            val netDeltas = legs.map(LedgerEvent::netBalanceDelta)
            val assets = legs.map { Asset.normalizeLedgerAsset(it.asset).uppercase() }
            val spread = legs.maxOf(LedgerEvent::time).toEpochMilli() - legs.minOf(LedgerEvent::time).toEpochMilli()
            val debits = netDeltas.count { it.signum() < 0 }
            val credits = netDeltas.count { it.signum() > 0 }

            if (legs.size == 1 && netDeltas.all { it.signum() == 0 }) {
                replayableLegs += legs
                replayableRefIds += refId
                continue
            }
            val contradiction = legs.size != 2 ||
                assets.distinct().size != assets.size ||
                debits != 1 ||
                credits != 1 ||
                spread > MAX_LEG_SKEW_MILLIS
            if (contradiction) {
                contradictoryRefIds += refId
                continue
            }
            val complete = legs.all {
                it.hasAuthoritativeBalance && it.hasValidFee && LedgerFlowClassifier.hasValidAmountShape(it)
            }
            if (!complete) {
                incompleteRefIds += refId
                continue
            }
            replayableLegs += legs
            replayableRefIds += refId
        }

        return Inventory(
            replayableLegs = replayableLegs,
            replayableRefIds = replayableRefIds,
            incompleteRefIds = incompleteRefIds,
            contradictoryRefIds = contradictoryRefIds,
            nonSpotRefIds = nonSpotRefIds,
            ambiguousIdentityRefIds = ambiguousIdentityRefIds,
            tradeLegsByTradeIdentity = tradeLegsByTradeIdentity.toMap(),
        )
    }
}
