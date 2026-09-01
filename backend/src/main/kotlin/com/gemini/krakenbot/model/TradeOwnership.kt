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

data class RebalancerOrderIdentities(
    val orderTxids: Set<String> = emptySet(),
    val clientOrderIds: Set<String> = emptySet(),
)

object TradeOwnershipClassifier {
    /**
     * Authoritatively classifies the ownership of a trade record.
     *
     * @param trade the trade record to classify
     * @param knownRebalancerOrderTxids set of order transaction IDs known to belong to bot executions
     * @param knownRebalancerClientOrderIds set of client order IDs known to belong to bot executions
     */
    fun classify(
        trade: TradeRecord,
        knownRebalancerOrderTxids: Set<String> = emptySet(),
        knownRebalancerClientOrderIds: Set<String> = emptySet(),
    ): TradeOwnership {
        if (!trade.cycleId.isNullOrBlank() || !trade.clientOrderId.isNullOrBlank()) {
            return TradeOwnership.REBALANCER
        }
        if (trade.source == TradeSource.LOCAL_ESTIMATE) {
            return TradeOwnership.REBALANCER
        }
        val txid = trade.orderTxid?.takeIf(String::isNotBlank)
        if (txid != null && txid in knownRebalancerOrderTxids) {
            return TradeOwnership.REBALANCER
        }
        if (trade.source == TradeSource.API_FILL && trade.hasAuthoritativeIdentity()) {
            return TradeOwnership.MANUAL_OR_EXTERNAL
        }
        return TradeOwnership.UNKNOWN
    }
}
