package com.gemini.krakenbot.repository.impl

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.gemini.krakenbot.model.ComparisonAvailability
import com.gemini.krakenbot.model.RebalancerComparison
import com.gemini.krakenbot.repository.ConsumedOhlcDependency
import com.gemini.krakenbot.repository.OhlcReachabilityDependency
import com.gemini.krakenbot.repository.RebalancerComparisonCacheEntry
import com.gemini.krakenbot.repository.RebalancerComparisonCacheRepository
import com.gemini.krakenbot.repository.table.RebalancerComparisonCacheTable
import kotlinx.coroutines.CancellationException
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import org.slf4j.LoggerFactory
import java.time.Instant

class SqliteRebalancerComparisonCacheRepositoryImpl(
    private val database: Database,
    private val objectMapper: ObjectMapper,
) : RebalancerComparisonCacheRepository {
    private val log = LoggerFactory.getLogger(SqliteRebalancerComparisonCacheRepositoryImpl::class.java)

    private val dependenciesTypeRef = object : TypeReference<List<ConsumedOhlcDependency>>() {}
    private val reachabilityDependenciesTypeRef = object : TypeReference<List<OhlcReachabilityDependency>>() {}

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
            // An unreadable dependency manifest must miss, never validate as empty: an
            // empty manifest carries no freshness requirements, so defaulting here would
            // serve the cached result as a Hit without ever revalidating its OHLC evidence.
            val ohlcDependencies: List<ConsumedOhlcDependency> = objectMapper.readValue(
                row[RebalancerComparisonCacheTable.ohlcDependenciesJson],
                dependenciesTypeRef,
            )
            val ohlcReachabilityDependencies: List<OhlcReachabilityDependency> = objectMapper.readValue(
                row[RebalancerComparisonCacheTable.ohlcReachabilityDependenciesJson],
                reachabilityDependenciesTypeRef,
            )

            // Never rehydrate an unavailable result: those outcomes can become valid after a
            // later evidence append or price-provider recovery and are deliberately not cached.
            comparison.takeIf { it.availability == ComparisonAvailability.AVAILABLE }
                ?.let {
                    RebalancerComparisonCacheEntry(
                        inputFingerprint = row[RebalancerComparisonCacheTable.inputFingerprint],
                        comparison = it,
                        ohlcDependencies = ohlcDependencies,
                        ohlcReachabilityDependencies = ohlcReachabilityDependencies,
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
        ohlcDependencies: List<ConsumedOhlcDependency>,
        ohlcReachabilityDependencies: List<OhlcReachabilityDependency>,
    ) {
        if (comparison.availability != ComparisonAvailability.AVAILABLE) return
        val (resultJson, dependenciesJson, reachabilityDependenciesJson) = try {
            Triple(
                objectMapper.writeValueAsString(comparison),
                objectMapper.writeValueAsString(ohlcDependencies),
                objectMapper.writeValueAsString(ohlcReachabilityDependencies),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Unable to serialize cached comparison payload: {}", e.message)
            return
        }
        database.safeTransactionIO(log, "Failed to persist cached comparison result") {
            RebalancerComparisonCacheTable.upsert {
                it[RebalancerComparisonCacheTable.fromEpochMillis] = fromEpochMillis
                it[RebalancerComparisonCacheTable.toEpochMillis] = toEpochMillis
                it[RebalancerComparisonCacheTable.inputFingerprint] = inputFingerprint
                it[RebalancerComparisonCacheTable.resultJson] = resultJson
                it[RebalancerComparisonCacheTable.calculatedAtEpochMillis] = Instant.now().toEpochMilli()
                it[RebalancerComparisonCacheTable.ohlcDependenciesJson] = dependenciesJson
                it[RebalancerComparisonCacheTable.ohlcReachabilityDependenciesJson] = reachabilityDependenciesJson
            }
            pruneSuperseded()
        }
    }

    override suspend fun updateOhlcDependencies(
        fromEpochMillis: Long,
        toEpochMillis: Long,
        ohlcDependencies: List<ConsumedOhlcDependency>,
    ) {
        val dependenciesJson = try {
            objectMapper.writeValueAsString(ohlcDependencies)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Unable to serialize updated comparison cache dependencies: {}", e.message)
            return
        }
        database.safeTransactionIO(log, "Failed to update comparison cache dependencies") {
            RebalancerComparisonCacheTable.update({
                (RebalancerComparisonCacheTable.fromEpochMillis eq fromEpochMillis) and
                    (RebalancerComparisonCacheTable.toEpochMillis eq toEpochMillis)
            }) {
                it[RebalancerComparisonCacheTable.ohlcDependenciesJson] = dependenciesJson
            }
        }
    }

    override suspend fun updateOhlcDependenciesIfExpected(
        fromEpochMillis: Long,
        toEpochMillis: Long,
        expectedOhlcDependencies: List<ConsumedOhlcDependency>,
        ohlcDependencies: List<ConsumedOhlcDependency>,
    ): Boolean {
        val (expectedJson, dependenciesJson) = try {
            objectMapper.writeValueAsString(expectedOhlcDependencies) to
                objectMapper.writeValueAsString(ohlcDependencies)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Unable to serialize updated comparison cache dependencies: {}", e.message)
            return false
        }
        return database.safeTransactionIO(log, "Failed to update comparison cache dependencies") {
            RebalancerComparisonCacheTable.update({
                (RebalancerComparisonCacheTable.fromEpochMillis eq fromEpochMillis) and
                    (RebalancerComparisonCacheTable.toEpochMillis eq toEpochMillis) and
                    (RebalancerComparisonCacheTable.ohlcDependenciesJson eq expectedJson)
            }) {
                it[RebalancerComparisonCacheTable.ohlcDependenciesJson] = dependenciesJson
            } > 0
        }
    }

    override suspend fun delete(fromEpochMillis: Long, toEpochMillis: Long) {
        database.safeTransactionIO(log, "Failed to delete invalidated comparison cache entry") {
            RebalancerComparisonCacheTable.deleteWhere {
                (RebalancerComparisonCacheTable.fromEpochMillis eq fromEpochMillis) and
                    (RebalancerComparisonCacheTable.toEpochMillis eq toEpochMillis)
            }
        }
    }

    /**
     * Retains only the newest 3 successful comparison source ranges total. Every advancing certified horizon
     * would otherwise add a full serialized comparison row that nothing ever reads again;
     * pruning superseded rows in the same transaction as the replacement write guarantees
     * the new entry is durably committed before any old entry is deleted, so a crash can
     * only leave extra rows, never a missing result.
     */
    private fun JdbcTransaction.pruneSuperseded() {
        val retained = RebalancerComparisonCacheTable
            .selectAll()
            .orderBy(
                RebalancerComparisonCacheTable.calculatedAtEpochMillis to SortOrder.DESC,
                RebalancerComparisonCacheTable.toEpochMillis to SortOrder.DESC,
            )
            .limit(RETAINED_SUCCESSFUL_RANGES)
            .map {
                it[RebalancerComparisonCacheTable.fromEpochMillis] to
                    it[RebalancerComparisonCacheTable.toEpochMillis]
            }
            .toSet()
        RebalancerComparisonCacheTable.selectAll()
            .map {
                it[RebalancerComparisonCacheTable.fromEpochMillis] to
                    it[RebalancerComparisonCacheTable.toEpochMillis]
            }
            .distinct()
            .filterNot { it in retained }
            .forEach { (from, to) ->
                RebalancerComparisonCacheTable.deleteWhere {
                    (RebalancerComparisonCacheTable.fromEpochMillis eq from) and
                        (RebalancerComparisonCacheTable.toEpochMillis eq to)
                }
            }
    }

    private companion object {
        /** Small bounded set: the newest 3 successful comparison source ranges total. */
        const val RETAINED_SUCCESSFUL_RANGES = 3
    }
}
