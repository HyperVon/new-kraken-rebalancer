package com.gemini.krakenbot.repository.impl

import com.fasterxml.jackson.databind.ObjectMapper
import com.gemini.krakenbot.model.ComparisonAvailability
import com.gemini.krakenbot.model.RebalancerComparison
import com.gemini.krakenbot.repository.RebalancerComparisonCacheEntry
import com.gemini.krakenbot.repository.RebalancerComparisonCacheRepository
import com.gemini.krakenbot.repository.table.RebalancerComparisonCacheTable
import kotlinx.coroutines.CancellationException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import org.slf4j.LoggerFactory
import java.time.Instant

class SqliteRebalancerComparisonCacheRepositoryImpl(
    private val database: Database,
    private val objectMapper: ObjectMapper,
) : RebalancerComparisonCacheRepository {
    private val log = LoggerFactory.getLogger(SqliteRebalancerComparisonCacheRepositoryImpl::class.java)

    override suspend fun load(fromEpochMillis: Long, toEpochMillis: Long): RebalancerComparisonCacheEntry? {
        val row = try {
            database.readTransactionIO {
                RebalancerComparisonCacheTable
                    .selectAll()
                    .where {
                        (RebalancerComparisonCacheTable.fromEpochMillis eq fromEpochMillis) and
                            (RebalancerComparisonCacheTable.toEpochMillis eq toEpochMillis)
                    }
                    .firstOrNull()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Unable to read cached comparison result: {}", e.message)
            return null
        } ?: return null

        return try {
            val comparison = objectMapper.readValue(
                row[RebalancerComparisonCacheTable.resultJson],
                RebalancerComparison::class.java,
            )
            // Never rehydrate an unavailable result: those outcomes can become valid after a
            // later evidence append or price-provider recovery and are deliberately not cached.
            comparison.takeIf { it.availability == ComparisonAvailability.AVAILABLE }
                ?.let {
                    RebalancerComparisonCacheEntry(
                        inputFingerprint = row[RebalancerComparisonCacheTable.inputFingerprint],
                        comparison = it,
                    )
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A schema/model upgrade must degrade to a cache miss rather than make History
            // unavailable. The next successful calculation replaces the stale payload.
            log.warn("Ignoring unreadable cached comparison result: {}", e.message)
            null
        }
    }

    override suspend fun save(
        fromEpochMillis: Long,
        toEpochMillis: Long,
        inputFingerprint: String,
        comparison: RebalancerComparison,
    ) {
        if (comparison.availability != ComparisonAvailability.AVAILABLE) return
        val resultJson = try {
            objectMapper.writeValueAsString(comparison)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Unable to serialize cached comparison result: {}", e.message)
            return
        }
        database.safeTransactionIO(log, "Failed to persist cached comparison result") {
            RebalancerComparisonCacheTable.upsert {
                it[RebalancerComparisonCacheTable.fromEpochMillis] = fromEpochMillis
                it[RebalancerComparisonCacheTable.toEpochMillis] = toEpochMillis
                it[RebalancerComparisonCacheTable.inputFingerprint] = inputFingerprint
                it[RebalancerComparisonCacheTable.resultJson] = resultJson
                it[RebalancerComparisonCacheTable.calculatedAtEpochMillis] = Instant.now().toEpochMilli()
            }
        }
    }
}
