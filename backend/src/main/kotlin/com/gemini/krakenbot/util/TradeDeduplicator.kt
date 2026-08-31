package com.gemini.krakenbot.util

import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.model.TradeSource
import com.gemini.krakenbot.model.effectiveSource
import com.gemini.krakenbot.model.hasAuthoritativeIdentity
import com.gemini.krakenbot.model.hasDifferentTradeProvenanceFrom
import com.gemini.krakenbot.model.hasSharedAuthoritativeIdentity
import com.gemini.krakenbot.model.isLocalEstimateDuplicateOf
import com.gemini.krakenbot.model.isPairAliasDuplicateOf
import com.gemini.krakenbot.model.isSameSymbolAndSide
import com.gemini.krakenbot.model.isSettledApiFill
import java.math.BigDecimal

/**
 * Finds DB row IDs to delete when the same fill was stored twice (pair-string aliases, or a
 * local estimate later reconciled by an API fill). Outer scan spans 5 minutes; estimate↔API
 * matching also requires [TradeRecord.isLocalEstimateDuplicateOf]'s 10s window.
 */
object TradeDeduplicator {
    fun findDuplicateTradeIds(records: List<TradeRecord>): List<Int> {
        val toDelete = linkedSetOf<Int>()
        val sorted = records.sortedWith(compareBy<TradeRecord> { it.timestamp }.thenBy { it.id ?: Int.MAX_VALUE })

        for (i in sorted.indices) {
            val record1 = sorted[i]
            if (record1.id?.let { it in toDelete } == true) continue
            for (j in i + 1 until sorted.size) {
                if (record1.id?.let { it in toDelete } == true) break
                val record2 = sorted[j]
                val id2 = record2.id ?: continue
                if (id2 in toDelete) continue
                val diff = record2.timestamp.toEpochMilli() - record1.timestamp.toEpochMilli()
                // Sorted ascending: once the gap exceeds 5 minutes, later j cannot match record1.
                if (diff > 300_000) break

                val sameIdentity = hasCompatibleIdentity(record1, record2)
                val strongIdentityDuplicate = sameIdentity && record1.hasSharedAuthoritativeIdentity(record2) &&
                    record1.isSameSymbolAndSide(record2) &&
                    isEconomicallyCompatible(record1, record2)
                val pairAliasDuplicate = sameIdentity && record1.isPairAliasDuplicateOf(record2)
                val localEstimateDuplicate =
                    sameIdentity &&
                        !record1.hasAuthoritativeIdentity() &&
                        !record2.hasAuthoritativeIdentity() &&
                        record1.isLocalEstimateDuplicateOf(record2) &&
                        record1.hasDifferentTradeProvenanceFrom(record2)

                if (strongIdentityDuplicate || pairAliasDuplicate || localEstimateDuplicate) {
                    // Prefer keeping API_FILL; if both (or neither) are settled, drop the later row.
                    val idToDelete =
                        when {
                            record1.isSettledApiFill() && !record2.isSettledApiFill() -> id2
                            record2.isSettledApiFill() && !record1.isSettledApiFill() -> record1.id
                            else -> id2
                        }
                    idToDelete?.let(toDelete::add)
                }
            }
        }
        return toDelete.toList()
    }

    private fun hasCompatibleIdentity(first: TradeRecord, second: TradeRecord): Boolean {
        if (first.submissionState != null || second.submissionState != null) return false
        if (first.success != second.success || first.dryRun != second.dryRun) return false

        val firstTradeId = first.tradeId?.takeIf { it.isNotBlank() }
        val secondTradeId = second.tradeId?.takeIf { it.isNotBlank() }
        if (firstTradeId != null && secondTradeId != null && firstTradeId != secondTradeId) return false

        val firstOrderTxid = first.orderTxid?.takeIf { it.isNotBlank() }
        val secondOrderTxid = second.orderTxid?.takeIf { it.isNotBlank() }
        if (firstOrderTxid != null && secondOrderTxid != null && firstOrderTxid != secondOrderTxid) return false

        val firstSource = first.effectiveSource()
        val secondSource = second.effectiveSource()
        return firstSource == secondSource ||
            (firstSource == TradeSource.LOCAL_ESTIMATE && secondSource == TradeSource.API_FILL) ||
            (firstSource == TradeSource.API_FILL && secondSource == TradeSource.LOCAL_ESTIMATE)
    }

    private fun isEconomicallyCompatible(first: TradeRecord, second: TradeRecord): Boolean =
        first.volume.signum() >= 0 &&
            second.volume.signum() >= 0 &&
            first.volume.subtract(second.volume).abs() <= first.volume.abs().max(second.volume.abs())
                .multiply(BigDecimal("0.01")) &&
            first.usdAmount.subtract(second.usdAmount).abs() <= first.usdAmount.abs().max(second.usdAmount.abs())
                .multiply(BigDecimal("0.01"))
}
