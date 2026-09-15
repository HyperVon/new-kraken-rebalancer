package com.gemini.krakenbot.model

/**
 * Authoritative trade ownership classification for benchmark accounting.
 */
enum class TradeOwnership {
    /** Positively attributable to a rebalancer bot cycle or order intent. */
    REBALANCER,

    /** Positively known authoritative exchange fill executed outside the bot (e.g. manual user trade). */
    MANUAL_OR_EXTERNAL,

    /** Legacy or incomplete provenance where ownership cannot be authoritatively determined. */
    UNKNOWN,
}

data class RebalancerOrderIdentities(val orderTxids: Set<String> = emptySet())

data class ResolvedOrderOwnership(
    val rebalancerOrderTxids: Set<String> = emptySet(),
    val manualOrderTxids: Set<String> = emptySet(),
    val conflictingOrderTxids: Set<String> = emptySet(),
) {
    fun classify(trade: TradeRecord): TradeOwnership = TradeOwnershipClassifier.classify(
        trade = trade,
        knownRebalancerOrderTxids = rebalancerOrderTxids,
        knownManualOrderTxids = manualOrderTxids,
        conflictingOrderTxids = conflictingOrderTxids,
    )
}

object TradeOwnershipClassifier {
    /**
     * Resolves order-level ownership identities across a set of trades and external known order IDs.
     *
     * A non-blank [TradeRecord.clientOrderId], [TradeRecord.cycleId], or [TradeSource.LOCAL_ESTIMATE] proves
     * positive bot ownership for that trade and for any sibling fill sharing its exact [TradeRecord.orderTxid].
     * An explicit [TradeSource.MANUAL] proves manual ownership for that trade and sibling fills.
     * If an order transaction ID carries conflicting evidence (both bot and manual markers), it fails
     * closed as conflicting and all associated fills remain [TradeOwnership.UNKNOWN].
     */
    fun resolveOrderOwnership(
        trades: Collection<TradeRecord>,
        knownRebalancerOrderTxids: Set<String> = emptySet(),
        knownManualOrderTxids: Set<String> = emptySet(),
    ): ResolvedOrderOwnership {
        val candidateTrades = trades.filter { it.success && !it.dryRun }
        val botTxids = candidateTrades.filter {
            !it.cycleId.isNullOrBlank() ||
                !it.clientOrderId.isNullOrBlank() ||
                it.source == TradeSource.LOCAL_ESTIMATE
        }.mapNotNull { it.orderTxid?.trim()?.takeIf(String::isNotBlank) }.toSet() +
            knownRebalancerOrderTxids.mapNotNull { it.trim().takeIf(String::isNotBlank) }

        val manualTxids = candidateTrades.filter {
            it.source == TradeSource.MANUAL
        }.mapNotNull { it.orderTxid?.trim()?.takeIf(String::isNotBlank) }.toSet() +
            knownManualOrderTxids.mapNotNull { it.trim().takeIf(String::isNotBlank) }

        val conflicting = botTxids.intersect(manualTxids)
        return ResolvedOrderOwnership(
            rebalancerOrderTxids = botTxids - conflicting,
            manualOrderTxids = manualTxids - conflicting,
            conflictingOrderTxids = conflicting,
        )
    }

    /**
     * Authoritatively classifies the ownership of a trade record.
     *
     * In Kraken's REST API, the `TradesHistory` endpoint returns exchange fills without a `clientOrderId` field.
     * Non-blank [TradeRecord.clientOrderId] or [TradeRecord.cycleId] values on a trade record originate exclusively
     * from this application's local order execution and reconciliation workflow, establishing positive bot ownership.
     * For raw API fills where local metadata was not attached, [knownRebalancerOrderTxids] matches against the
     * durable [OrderIntent] journal or proven sibling order fills. A raw API fill that matches neither source is
     * intentionally UNKNOWN; exchange identity proves settlement, not who initiated the order.
     *
     * @param trade the trade record to classify
     * @param knownRebalancerOrderTxids set of order transaction IDs known to belong to bot executions
     * @param knownManualOrderTxids set of order transaction IDs known to belong to manual executions
     * @param conflictingOrderTxids set of order transaction IDs carrying conflicting bot and manual evidence
     */
    fun classify(
        trade: TradeRecord,
        knownRebalancerOrderTxids: Set<String> = emptySet(),
        knownManualOrderTxids: Set<String> = emptySet(),
        conflictingOrderTxids: Set<String> = emptySet(),
    ): TradeOwnership {
        val txid = trade.orderTxid?.trim()?.takeIf(String::isNotBlank)
        if (txid != null && txid in conflictingOrderTxids) {
            return TradeOwnership.UNKNOWN
        }

        val hasBotEvidence = !trade.cycleId.isNullOrBlank() ||
            !trade.clientOrderId.isNullOrBlank() ||
            trade.source == TradeSource.LOCAL_ESTIMATE ||
            (txid != null && txid in knownRebalancerOrderTxids)

        val hasManualEvidence = trade.source == TradeSource.MANUAL ||
            (txid != null && txid in knownManualOrderTxids)

        if (hasBotEvidence && hasManualEvidence) {
            return TradeOwnership.UNKNOWN
        }
        if (hasBotEvidence) {
            return TradeOwnership.REBALANCER
        }
        if (hasManualEvidence) {
            return TradeOwnership.MANUAL_OR_EXTERNAL
        }
        return TradeOwnership.UNKNOWN
    }
}
