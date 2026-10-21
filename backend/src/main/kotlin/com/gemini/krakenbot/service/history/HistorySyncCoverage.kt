package com.gemini.krakenbot.service.history

import java.sql.Connection

/**
 * Merges one verified interval into the durable coverage of a feed. Overlapping and adjacent
 * intervals collapse into one segment, so the stored union stays small and gap free.
 *
 * The schema migration and the live store share this merge, so a seeded interval and a scanned
 * interval end up in the same union shape.
 */
internal fun mergeCoverageSegment(
    connection: Connection,
    accountIdentityDigest: String,
    feed: HistoryFeed,
    startSec: Long,
    endSec: Long,
    updatedAtNs: Long,
) {
    val overlapping = mutableListOf<LongRange>()
    connection.prepareStatement(
        "SELECT start_sec, end_sec FROM history_sync_coverage WHERE account_identity_digest = ? " +
            "AND feed = ? AND end_sec >= ? AND start_sec <= ?",
    ).use { statement ->
        statement.setString(1, accountIdentityDigest)
        statement.setString(2, feed.name)
        statement.setLong(3, startSec - 1)
        statement.setLong(4, endSec + 1)
        statement.executeQuery().use { resultSet ->
            while (resultSet.next()) {
                overlapping += resultSet.getLong("start_sec")..resultSet.getLong("end_sec")
            }
        }
    }
    val mergedStart = overlapping.minOfOrNull { it.first }?.coerceAtMost(startSec) ?: startSec
    val mergedEnd = overlapping.maxOfOrNull { it.last }?.coerceAtLeast(endSec) ?: endSec
    connection.prepareStatement(
        "DELETE FROM history_sync_coverage WHERE account_identity_digest = ? AND feed = ? " +
            "AND end_sec >= ? AND start_sec <= ?",
    ).use { statement ->
        statement.setString(1, accountIdentityDigest)
        statement.setString(2, feed.name)
        statement.setLong(3, mergedStart - 1)
        statement.setLong(4, mergedEnd + 1)
        statement.executeUpdate()
    }
    connection.prepareStatement(
        "INSERT INTO history_sync_coverage(account_identity_digest, feed, start_sec, end_sec, updated_at_ns) " +
            "VALUES (?, ?, ?, ?, ?)",
    ).use { statement ->
        statement.setString(1, accountIdentityDigest)
        statement.setString(2, feed.name)
        statement.setLong(3, mergedStart)
        statement.setLong(4, mergedEnd)
        statement.setLong(5, updatedAtNs)
        statement.executeUpdate()
    }
}
