package com.gemini.krakenbot.service.actual.benchmark

import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.repository.OrderIntentRepository
import com.gemini.krakenbot.service.KrakenService
import java.time.Instant

sealed class ContinuityVerificationResult {
    data class Continuous(val updatedVerifiedTime: Instant) : ContinuityVerificationResult()
    data class Terminated(val reason: String, val eventTime: Instant) : ContinuityVerificationResult()
    data class PendingEvidence(val message: String) : ContinuityVerificationResult()
}

class ProspectiveEventContinuityVerifier(
    private val krakenService: KrakenService,
    private val orderIntentRepository: OrderIntentRepository?,
) {
    suspend fun verifyContinuity(segment: BenchmarkSegment, throughTime: Instant): ContinuityVerificationResult {
        if (!throughTime.isAfter(segment.lastVerifiedEventTime)) {
            return ContinuityVerificationResult.Continuous(segment.lastVerifiedEventTime)
        }

        val startSec = segment.lastVerifiedEventTime.epochSecond
        val endSec = throughTime.epochSecond

        val ledgers = try {
            krakenService.getLedgers(startSec = startSec, endSec = endSec)
        } catch (e: Exception) {
            return ContinuityVerificationResult.PendingEvidence("Failed to fetch ledgers from Kraken: ${e.message}")
        }

        if (!krakenService.hasLastLedgerPageShape()) {
            return ContinuityVerificationResult.PendingEvidence("Kraken returned a malformed ledger page shape")
        }
        if (!krakenService.hasLastLedgerTotalCount()) {
            return ContinuityVerificationResult.PendingEvidence("Kraken ledger page is missing an authoritative count")
        }

        val relevantLedgers = ledgers.filter { event ->
            event.time.isAfter(segment.lastVerifiedEventTime) && !event.time.isAfter(throughTime)
        }.sortedBy { it.time }

        for (event in relevantLedgers) {
            when {
                event.type.equals("deposit", ignoreCase = true) || event.type.equals("withdrawal", ignoreCase = true) ->
                    return ContinuityVerificationResult.Terminated(
                        "${BenchmarkTerminationReason.EXTERNAL_FUNDING}: ${event.type} ${event.amount} ${event.asset}",
                        event.time,
                    )

                event.type.equals("transfer", ignoreCase = true) ->
                    return ContinuityVerificationResult.Terminated(
                        "${BenchmarkTerminationReason.EXTERNAL_TRANSFER}: transfer ${event.subtype ?: ""} " +
                            "${event.amount} ${event.asset}".trim(),
                        event.time,
                    )

                event.type.lowercase() in setOf("staking", "earn", "reward", "dividend") ->
                    return ContinuityVerificationResult.Terminated(
                        "${BenchmarkTerminationReason.REWARD_OR_EARN}: ${event.type} ${event.amount} ${event.asset}",
                        event.time,
                    )

                event.type.lowercase() in setOf("spend", "receive", "conversion") ->
                    return ContinuityVerificationResult.Terminated(
                        "${BenchmarkTerminationReason.ASSET_CONVERSION}: ${event.type} ${event.amount} ${event.asset}",
                        event.time,
                    )

                !event.type.equals("trade", ignoreCase = true) ->
                    return ContinuityVerificationResult.Terminated(
                        "${BenchmarkTerminationReason.UNCLASSIFIED_EVENT}: " +
                            "${event.type} ${event.amount} ${event.asset}",
                        event.time,
                    )
            }
        }

        val tradeLedgers = relevantLedgers.filter { it.type.equals("trade", ignoreCase = true) }
        if (tradeLedgers.isNotEmpty()) {
            val trades = try {
                krakenService.getTradeHistory(startSec = startSec)
            } catch (e: Exception) {
                return ContinuityVerificationResult.PendingEvidence(
                    "Failed to fetch trade history from Kraken: ${e.message}",
                )
            }
            if (!krakenService.hasLastTradeHistoryPageShape()) {
                return ContinuityVerificationResult.PendingEvidence("Kraken returned a malformed trade history page")
            }

            val relevantTrades = trades.filter { trade ->
                trade.timestamp.isAfter(segment.lastVerifiedEventTime) && !trade.timestamp.isAfter(throughTime)
            }

            val tradeOrderTxids = relevantTrades.mapNotNull { it.orderTxid }.toSet()
            val knownRebalancerIdentities = orderIntentRepository?.getKnownRebalancerOrderIdentities(
                orderTxids = tradeOrderTxids,
                clientOrderIds = emptySet(),
            )?.orderTxids ?: emptySet()

            for (trade in relevantTrades) {
                val isKnownRebalancer = trade.orderTxid != null && trade.orderTxid in knownRebalancerIdentities
                val inScope = isPairInScope(trade.pair, segment.scopeSymbols)
                if (!isKnownRebalancer || !inScope) {
                    return ContinuityVerificationResult.Terminated(
                        "${BenchmarkTerminationReason.MANUAL_TRADE}: trade in ${trade.pair} " +
                            "(orderTxid=${trade.orderTxid ?: "none"})",
                        trade.timestamp,
                    )
                }
            }
        }

        return ContinuityVerificationResult.Continuous(throughTime)
    }

    private fun isPairInScope(pair: String, scopeSymbols: List<String>): Boolean {
        val upper = pair.uppercase()
        return scopeSymbols.any { sym1 ->
            scopeSymbols.any { sym2 ->
                sym1 != sym2 &&
                    (upper.contains(sym1) || upper.contains(Asset.toKrakenTicker(sym1))) &&
                    (upper.contains(sym2) || upper.contains(Asset.toKrakenTicker(sym2)))
            }
        }
    }
}
