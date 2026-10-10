package com.gemini.krakenbot.service.actual.benchmark

import com.gemini.krakenbot.service.actual.ActualObservationStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.sql.ResultSet
import java.time.Instant

class BenchmarkStore(private val observationStore: ActualObservationStore) {
    suspend fun saveSegment(segment: BenchmarkSegment): Boolean = withContext(Dispatchers.IO) {
        observationStore.withConnection { connection ->
            connection.prepareStatement(
                """
                INSERT OR IGNORE INTO benchmark_segments (
                    segment_id, baseline_observation_id, account_identity_digest, scope_fingerprint,
                    scope_symbols, baseline_at_ns, initial_holdings, baseline_marks, baseline_total_usd,
                    status, termination_reason, last_verified_event_time_ns, created_at_ns
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, segment.segmentId)
                statement.setString(2, segment.baselineObservationId)
                statement.setString(3, segment.accountIdentityDigest)
                statement.setString(4, segment.scopeFingerprint)
                statement.setString(5, segment.scopeSymbols.joinToString("\n"))
                statement.setLong(6, segment.baselineAt.toEpochNanos())
                statement.setString(7, encodeMap(segment.initialHoldings))
                statement.setString(8, encodeMap(segment.baselineMarks))
                statement.setString(9, segment.baselineTotalUsd.toPlainString())
                statement.setString(10, segment.status.name)
                statement.setString(11, segment.terminationReason)
                statement.setLong(12, segment.lastVerifiedEventTime.toEpochNanos())
                statement.setLong(13, segment.createdAt.toEpochNanos())
                statement.executeUpdate() == 1
            }
        }
    }

    suspend fun getActiveSegment(accountIdentityDigest: String): BenchmarkSegment? = withContext(Dispatchers.IO) {
        observationStore.withConnection { connection ->
            connection.prepareStatement(
                """
                    SELECT * FROM benchmark_segments
                    WHERE account_identity_digest = ? AND status = 'TRACKING'
                    ORDER BY created_at_ns DESC LIMIT 1
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, accountIdentityDigest)
                statement.executeQuery().use { rs ->
                    if (rs.next()) parseSegment(rs) else null
                }
            }
        }
    }

    suspend fun getActiveSegment(scopeFingerprint: String, accountIdentityDigest: String): BenchmarkSegment? =
        withContext(Dispatchers.IO) {
            observationStore.withConnection { connection ->
                connection.prepareStatement(
                    """
                    SELECT * FROM benchmark_segments
                    WHERE scope_fingerprint = ? AND account_identity_digest = ? AND status = 'TRACKING'
                    ORDER BY created_at_ns DESC LIMIT 1
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, scopeFingerprint)
                    statement.setString(2, accountIdentityDigest)
                    statement.executeQuery().use { rs ->
                        if (rs.next()) parseSegment(rs) else null
                    }
                }
            }
        }

    suspend fun getLatestSegment(accountIdentityDigest: String): BenchmarkSegment? = withContext(Dispatchers.IO) {
        observationStore.withConnection { connection ->
            connection.prepareStatement(
                """
                    SELECT * FROM benchmark_segments
                    WHERE account_identity_digest = ?
                    ORDER BY created_at_ns DESC LIMIT 1
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, accountIdentityDigest)
                statement.executeQuery().use { rs ->
                    if (rs.next()) parseSegment(rs) else null
                }
            }
        }
    }

    suspend fun getLatestSegment(scopeFingerprint: String, accountIdentityDigest: String): BenchmarkSegment? =
        withContext(Dispatchers.IO) {
            observationStore.withConnection { connection ->
                connection.prepareStatement(
                    """
                    SELECT * FROM benchmark_segments
                    WHERE scope_fingerprint = ? AND account_identity_digest = ?
                    ORDER BY created_at_ns DESC LIMIT 1
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, scopeFingerprint)
                    statement.setString(2, accountIdentityDigest)
                    statement.executeQuery().use { rs ->
                        if (rs.next()) parseSegment(rs) else null
                    }
                }
            }
        }

    suspend fun updateSegmentStatus(
        segmentId: String,
        status: BenchmarkSegmentStatus,
        reason: String?,
        lastVerifiedTime: Instant,
    ): Boolean = withContext(Dispatchers.IO) {
        observationStore.withConnection { connection ->
            connection.prepareStatement(
                """
                UPDATE benchmark_segments
                SET status = ?, termination_reason = ?, last_verified_event_time_ns = ?
                WHERE segment_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, status.name)
                statement.setString(2, reason)
                statement.setLong(3, lastVerifiedTime.toEpochNanos())
                statement.setString(4, segmentId)
                statement.executeUpdate() > 0
            }
        }
    }

    suspend fun updateVerifiedTime(segmentId: String, lastVerifiedTime: Instant): Boolean =
        withContext(Dispatchers.IO) {
            observationStore.withConnection { connection ->
                connection.prepareStatement(
                    """
                    UPDATE benchmark_segments
                    SET last_verified_event_time_ns = ?
                    WHERE segment_id = ? AND last_verified_event_time_ns < ?
                    """.trimIndent(),
                ).use { statement ->
                    val nanos = lastVerifiedTime.toEpochNanos()
                    statement.setLong(1, nanos)
                    statement.setString(2, segmentId)
                    statement.setLong(3, nanos)
                    statement.executeUpdate() > 0
                }
            }
        }

    private fun parseSegment(rs: ResultSet): BenchmarkSegment = BenchmarkSegment(
        segmentId = rs.getString("segment_id"),
        baselineObservationId = rs.getString("baseline_observation_id"),
        accountIdentityDigest = rs.getString("account_identity_digest"),
        scopeFingerprint = rs.getString("scope_fingerprint"),
        scopeSymbols = rs.getString("scope_symbols").split("\n").filter(String::isNotBlank),
        baselineAt = rs.getLong("baseline_at_ns").toInstant(),
        initialHoldings = decodeMap(rs.getString("initial_holdings")),
        baselineMarks = decodeMap(rs.getString("baseline_marks")),
        baselineTotalUsd = BigDecimal(rs.getString("baseline_total_usd")),
        status = BenchmarkSegmentStatus.valueOf(rs.getString("status")),
        terminationReason = rs.getString("termination_reason"),
        lastVerifiedEventTime = rs.getLong("last_verified_event_time_ns").toInstant(),
        createdAt = rs.getLong("created_at_ns").toInstant(),
    )

    private fun encodeMap(map: Map<String, BigDecimal>): String =
        map.entries.joinToString("\n") { "${it.key}:${it.value.toPlainString()}" }

    private fun decodeMap(raw: String): Map<String, BigDecimal> = raw.split("\n")
        .filter(String::isNotBlank)
        .associate { line ->
            val parts = line.split(":")
            parts[0] to BigDecimal(parts[1])
        }

    private fun Instant.toEpochNanos(): Long = epochSecond * 1_000_000_000L + nano

    private fun Long.toInstant(): Instant = Instant.ofEpochSecond(this / 1_000_000_000L, this % 1_000_000_000L)
}
