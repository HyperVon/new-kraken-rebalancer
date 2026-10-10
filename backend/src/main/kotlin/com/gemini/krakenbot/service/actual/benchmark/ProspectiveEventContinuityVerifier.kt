package com.gemini.krakenbot.service.actual.benchmark

import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.model.hasValidEconomicFields
import com.gemini.krakenbot.repository.OrderIntentRepository
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.service.getRecoveryTradeHistoryUntil
import kotlinx.coroutines.CancellationException
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
        val endSec = throughTime.epochSecond + 1

        val allLedgers = mutableListOf<LedgerEvent>()
        val seenLedgerIds = mutableSetOf<String>()
        var initialLedgerTotalCount: Int? = null
        var ledgerOffset = 0
        while (true) {
            val page = try {
                krakenService.getLedgers(startSec = startSec, offset = ledgerOffset, endSec = endSec)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return ContinuityVerificationResult.PendingEvidence("Failed to fetch ledgers from Kraken: ${e.message}")
            }

            if (!krakenService.hasLastLedgerPageShape()) {
                return ContinuityVerificationResult.PendingEvidence("Kraken returned a malformed ledger page shape")
            }
            if (!krakenService.hasLastLedgerTotalCount()) {
                return ContinuityVerificationResult.PendingEvidence(
                    "Kraken ledger page is missing an authoritative count",
                )
            }

            val totalCount = krakenService.getLastLedgerTotalCount()
            if (initialLedgerTotalCount == null) {
                initialLedgerTotalCount = totalCount
            } else if (totalCount != initialLedgerTotalCount) {
                return ContinuityVerificationResult.PendingEvidence(
                    "Inconsistent ledger total count across pages: initial $initialLedgerTotalCount, " +
                        "page at offset $ledgerOffset reported $totalCount",
                )
            }

            for (event in page) {
                if (event.ledgerId.isBlank()) {
                    return ContinuityVerificationResult.PendingEvidence("Ledger entry has blank ledgerId")
                }
                if (event.time <= Instant.EPOCH) {
                    return ContinuityVerificationResult.PendingEvidence(
                        "Ledger entry ${event.ledgerId} has malformed or missing timestamp (${event.time})",
                    )
                }
                if (!seenLedgerIds.add(event.ledgerId)) {
                    return ContinuityVerificationResult.PendingEvidence(
                        "Duplicate ledger entry detected across pages (${event.ledgerId}); " +
                            "pagination consistency uncertain",
                    )
                }
                allLedgers.add(event)
            }

            val rawPageSize = krakenService.getLastLedgerRawPageSize()
            val advance = if (rawPageSize > 0) rawPageSize else page.size
            ledgerOffset += advance

            if (advance == 0 || ledgerOffset >= initialLedgerTotalCount || page.isEmpty()) {
                break
            }
        }

        val expectedLedgerTotal = initialLedgerTotalCount
        if (seenLedgerIds.size != expectedLedgerTotal) {
            return ContinuityVerificationResult.PendingEvidence(
                "Incomplete ledger pagination: retrieved ${seenLedgerIds.size} distinct entries " +
                    "but Kraken reported total count $expectedLedgerTotal",
            )
        }

        val isInitialBaseline = (segment.lastVerifiedEventTime == segment.baselineAt)
        val relevantLedgers = allLedgers.filter { event ->
            val afterStart = if (isInitialBaseline) {
                !event.time.isBefore(segment.baselineAt)
            } else {
                event.time.isAfter(segment.lastVerifiedEventTime)
            }
            afterStart && !event.time.isAfter(throughTime)
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
            val allTrades = mutableListOf<TradeRecord>()
            val seenTradeIds = mutableSetOf<String>()
            var initialTradeTotalCount: Int? = null
            var tradeOffset = 0
            while (true) {
                val page = try {
                    krakenService.getRecoveryTradeHistoryUntil(
                        startSec = startSec,
                        offset = tradeOffset,
                        endSec = endSec,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return ContinuityVerificationResult.PendingEvidence(
                        "Failed to fetch trade history from Kraken: ${e.message}",
                    )
                }

                if (!krakenService.hasLastTradeHistoryPageShape()) {
                    return ContinuityVerificationResult.PendingEvidence(
                        "Kraken returned a malformed trade history page",
                    )
                }
                if (!krakenService.hasLastTradeHistoryTotalCount()) {
                    return ContinuityVerificationResult.PendingEvidence(
                        "Kraken trade history page is missing an authoritative count",
                    )
                }

                val totalCount = krakenService.getLastTradeHistoryTotalCount()
                if (initialTradeTotalCount == null) {
                    initialTradeTotalCount = totalCount
                } else if (totalCount != initialTradeTotalCount) {
                    return ContinuityVerificationResult.PendingEvidence(
                        "Inconsistent trade history total count across pages: initial $initialTradeTotalCount, " +
                            "page at offset $tradeOffset reported $totalCount",
                    )
                }

                for (trade in page) {
                    val tradeId = trade.tradeId?.trim()
                    if (tradeId.isNullOrBlank()) {
                        return ContinuityVerificationResult.PendingEvidence("Trade record has missing or blank tradeId")
                    }
                    if (trade.timestamp <= Instant.EPOCH) {
                        return ContinuityVerificationResult.PendingEvidence(
                            "Trade record $tradeId has malformed or missing timestamp (${trade.timestamp})",
                        )
                    }
                    if (!trade.hasValidEconomicFields()) {
                        return ContinuityVerificationResult.PendingEvidence(
                            "Trade $tradeId contains invalid or unparseable economic fields",
                        )
                    }
                    if (!seenTradeIds.add(tradeId)) {
                        return ContinuityVerificationResult.PendingEvidence(
                            "Duplicate trade entry detected across pages ($tradeId); " +
                                "pagination consistency uncertain",
                        )
                    }
                    allTrades.add(trade)
                }

                val rawPageSize = krakenService.getLastTradeHistoryRawPageSize()
                val advance = if (rawPageSize > 0) rawPageSize else page.size
                tradeOffset += advance

                if (advance == 0 || tradeOffset >= initialTradeTotalCount || page.isEmpty()) {
                    break
                }
            }

            val expectedTradeTotal = initialTradeTotalCount
            if (seenTradeIds.size != expectedTradeTotal) {
                return ContinuityVerificationResult.PendingEvidence(
                    "Incomplete trade history pagination: retrieved ${seenTradeIds.size} distinct trades " +
                        "but Kraken reported total count $expectedTradeTotal",
                )
            }

            val relevantTrades = allTrades.filter { trade ->
                val afterStart = if (isInitialBaseline) {
                    !trade.timestamp.isBefore(segment.baselineAt)
                } else {
                    trade.timestamp.isAfter(segment.lastVerifiedEventTime)
                }
                afterStart && !trade.timestamp.isAfter(throughTime)
            }

            for (ledger in tradeLedgers) {
                val refid = ledger.refid
                if (refid.isNullOrBlank()) {
                    return ContinuityVerificationResult.Terminated(
                        "${BenchmarkTerminationReason.MANUAL_TRADE}: Trade ledger entry ${ledger.ledgerId} " +
                            "has missing or blank refid",
                        ledger.time,
                    )
                }

                val matchingTrade = relevantTrades.find { trade ->
                    (trade.tradeId != null && trade.tradeId == refid) ||
                        (trade.orderTxid != null && trade.orderTxid == refid)
                }

                if (matchingTrade == null) {
                    return ContinuityVerificationResult.Terminated(
                        "${BenchmarkTerminationReason.MANUAL_TRADE}: Trade ledger entry ${ledger.ledgerId} " +
                            "(refid=$refid) has no matching trade history execution",
                        ledger.time,
                    )
                }
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
