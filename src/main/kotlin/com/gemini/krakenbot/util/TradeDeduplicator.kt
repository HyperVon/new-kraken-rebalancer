package com.gemini.krakenbot.util

import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.model.feePercentDiffersMateriallyFrom
import com.gemini.krakenbot.model.hasDifferentTradeProvenanceFrom
import com.gemini.krakenbot.model.isLocalEstimateDuplicateOf
import com.gemini.krakenbot.model.isPairAliasDuplicateOf
import com.gemini.krakenbot.model.isSettledApiFill

/**
 * Pure domain utility for identifying duplicate local trade records that should be cleaned up.
 */
object TradeDeduplicator {
    /**
     * Given a list of trade records sorted chronologically, identifies duplicate trade record IDs
     * created by pair alias mismatches or local estimated trades vs settled API fills.
     */
    fun findDuplicateTradeIds(records: List<TradeRecord>): List<Int> {
        val toDelete = mutableListOf<Int>()
        val sorted = records.sortedBy { it.timestamp }

        for (i in sorted.indices) {
            val record1 = sorted[i]
            for (j in i + 1 until sorted.size) {
                val record2 = sorted[j]
                val id2 = record2.id ?: continue
                val diff = record2.timestamp.toEpochMilli() - record1.timestamp.toEpochMilli()
                if (diff > 300_000) break

                val pairAliasDuplicate = record1.isPairAliasDuplicateOf(record2)
                val localEstimateDuplicate =
                    record1.isLocalEstimateDuplicateOf(record2) &&
                        record1.feePercentDiffersMateriallyFrom(record2) &&
                        record1.hasDifferentTradeProvenanceFrom(record2)

                if (pairAliasDuplicate || localEstimateDuplicate) {
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
        return toDelete.distinct()
    }
}
