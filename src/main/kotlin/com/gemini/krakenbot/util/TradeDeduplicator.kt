package com.gemini.krakenbot.util

import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.model.feePercentDiffersMateriallyFrom
import com.gemini.krakenbot.model.hasDifferentTradeProvenanceFrom
import com.gemini.krakenbot.model.isLocalEstimateDuplicateOf
import com.gemini.krakenbot.model.isPairAliasDuplicateOf
import com.gemini.krakenbot.model.isSettledApiFill

/**
 * Finds DB row IDs to delete when the same fill was stored twice (pair-string aliases, or a
 * local estimate later reconciled by an API fill). Outer scan spans 5 minutes; estimate↔API
 * matching also requires [TradeRecord.isLocalEstimateDuplicateOf]'s 10s window.
 */
object TradeDeduplicator {
    fun findDuplicateTradeIds(records: List<TradeRecord>): List<Int> {
        val toDelete = linkedSetOf<Int>()
        val sorted = records.sortedBy { it.timestamp }

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

                val pairAliasDuplicate = record1.isPairAliasDuplicateOf(record2)
                // Estimate↔API only when fee rates diverge (≥0.1 pp) — identical fees look like
                // two real fills, not an estimate replaced by a settle.
                val localEstimateDuplicate =
                    record1.isLocalEstimateDuplicateOf(record2) &&
                        record1.feePercentDiffersMateriallyFrom(record2) &&
                        record1.hasDifferentTradeProvenanceFrom(record2)

                if (pairAliasDuplicate || localEstimateDuplicate) {
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
}
