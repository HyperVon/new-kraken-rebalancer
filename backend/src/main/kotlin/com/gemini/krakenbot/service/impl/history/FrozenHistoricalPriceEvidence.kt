package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.TradeRecord
import java.time.Instant

/**
 * Immutable in-memory index over captured trade and snapshot evidence.
 * Provides exact parity with the SQL query contracts of [SqliteTradeRepositoryImpl]:
 * - Trades: [from, to] inclusive, ordered by timestamp DESC.
 * - Snapshots: [from, to] inclusive, ordered by timestamp ASC.
 *
 * All range lookups use O(log N) binary search over immutable collections,
 * avoiding repeated database queries during price resolution and historical calculations.
 */
class FrozenHistoricalPriceEvidence(trades: List<TradeRecord>, snapshots: List<PortfolioSnapshot>) :
    HistoricalPriceEvidenceReader {

    // Stable sort preserves relative ordering of identical-timestamp rows
    private val sortedTrades: List<TradeRecord> = trades.sortedWith(
        compareByDescending<TradeRecord> { it.timestamp.toEpochMilli() },
    )

    private val sortedSnapshots: List<PortfolioSnapshot> = snapshots.sortedWith(
        compareBy<PortfolioSnapshot> { it.timestamp.toEpochMilli() },
    )

    override suspend fun getTradesInRange(from: Instant, to: Instant): List<TradeRecord> {
        val fromMillis = from.toEpochMilli()
        val toMillis = to.toEpochMilli()
        if (fromMillis > toMillis || sortedTrades.isEmpty()) return emptyList()

        val startIndex = findFirstTradeAtOrBefore(toMillis)
        val endIndex = findFirstTradeStrictlyBefore(fromMillis)
        if (startIndex >= endIndex) return emptyList()
        return sortedTrades.subList(startIndex, endIndex)
    }

    override suspend fun getSnapshotsInRange(from: Instant, to: Instant): List<PortfolioSnapshot> {
        val fromMillis = from.toEpochMilli()
        val toMillis = to.toEpochMilli()
        if (fromMillis > toMillis || sortedSnapshots.isEmpty()) return emptyList()

        val startIndex = findFirstSnapshotAtOrAfter(fromMillis)
        val endIndex = findFirstSnapshotStrictlyAfter(toMillis)
        if (startIndex >= endIndex) return emptyList()
        return sortedSnapshots.subList(startIndex, endIndex)
    }

    private fun findFirstTradeAtOrBefore(toMillis: Long): Int {
        var low = 0
        var high = sortedTrades.size - 1
        var result = sortedTrades.size
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (sortedTrades[mid].timestamp.toEpochMilli() <= toMillis) {
                result = mid
                high = mid - 1
            } else {
                low = mid + 1
            }
        }
        return result
    }

    private fun findFirstTradeStrictlyBefore(fromMillis: Long): Int {
        var low = 0
        var high = sortedTrades.size - 1
        var result = sortedTrades.size
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (sortedTrades[mid].timestamp.toEpochMilli() < fromMillis) {
                result = mid
                high = mid - 1
            } else {
                low = mid + 1
            }
        }
        return result
    }

    private fun findFirstSnapshotAtOrAfter(fromMillis: Long): Int {
        var low = 0
        var high = sortedSnapshots.size - 1
        var result = sortedSnapshots.size
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (sortedSnapshots[mid].timestamp.toEpochMilli() >= fromMillis) {
                result = mid
                high = mid - 1
            } else {
                low = mid + 1
            }
        }
        return result
    }

    private fun findFirstSnapshotStrictlyAfter(toMillis: Long): Int {
        var low = 0
        var high = sortedSnapshots.size - 1
        var result = sortedSnapshots.size
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (sortedSnapshots[mid].timestamp.toEpochMilli() > toMillis) {
                result = mid
                high = mid - 1
            } else {
                low = mid + 1
            }
        }
        return result
    }
}
