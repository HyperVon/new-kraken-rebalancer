package com.gemini.krakenbot.repository.impl

import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.repository.HistoricalOhlcRepository
import com.gemini.krakenbot.repository.HistoricalOhlcSeries
import com.gemini.krakenbot.repository.OhlcCoverage
import com.gemini.krakenbot.repository.authoritativeOhlcCoverage
import com.gemini.krakenbot.repository.table.HistoricalOhlcCandleTable
import com.gemini.krakenbot.repository.table.HistoricalOhlcFetchTable
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import org.slf4j.LoggerFactory
import java.math.BigDecimal

class SqliteHistoricalOhlcRepositoryImpl(private val database: Database) : HistoricalOhlcRepository {
    private val log = LoggerFactory.getLogger(SqliteHistoricalOhlcRepositoryImpl::class.java)

    private companion object {
        const val RETAINED_FETCH_PROOFS = 3
        const val MAX_FETCH_PROOFS_PER_REQUEST = 24
        const val SQLITE_IN_CHUNK_SIZE = 500
    }

    override suspend fun loadCovered(
        pair: String,
        intervalMinutes: Int,
        sinceEpochSecond: Long,
        upToEpochSecond: Long,
    ): HistoricalOhlcSeries? = database.readTransactionIO {
        // Only the PROVEN span counts: the original request since and fetch wall must
        // never substitute for coverage. Legacy rows with null coverage fail closed.
        val fetch = HistoricalOhlcFetchTable
            .selectAll()
            .where {
                (HistoricalOhlcFetchTable.pair eq pair) and
                    (HistoricalOhlcFetchTable.intervalMinutes eq intervalMinutes) and
                    HistoricalOhlcFetchTable.coverageFromEpochSecond.isNotNull() and
                    HistoricalOhlcFetchTable.coverageUntilEpochSecond.isNotNull() and
                    (HistoricalOhlcFetchTable.coverageFromEpochSecond lessEq sinceEpochSecond) and
                    (HistoricalOhlcFetchTable.coverageUntilEpochSecond greaterEq upToEpochSecond)
            }
            .orderBy(HistoricalOhlcFetchTable.fetchedAtEpochSecond, SortOrder.DESC)
            .limit(1)
            .firstOrNull()
            ?: return@readTransactionIO null

        val coverageFrom = fetch[HistoricalOhlcFetchTable.coverageFromEpochSecond]
            ?: return@readTransactionIO null
        val coverageUntil = fetch[HistoricalOhlcFetchTable.coverageUntilEpochSecond]
            ?: return@readTransactionIO null
        // Returned rows correspond to evidence valid under this proof: only candles
        // inside the proven span, never preserved rows outside it.
        val candles = HistoricalOhlcCandleTable
            .selectAll()
            .where {
                (HistoricalOhlcCandleTable.pair eq pair) and
                    (HistoricalOhlcCandleTable.intervalMinutes eq intervalMinutes) and
                    (HistoricalOhlcCandleTable.candleStartEpochSecond greaterEq coverageFrom) and
                    (HistoricalOhlcCandleTable.candleStartEpochSecond less coverageUntil)
            }
            .orderBy(HistoricalOhlcCandleTable.candleStartEpochSecond, SortOrder.ASC)
            .map { it[HistoricalOhlcCandleTable.candleStartEpochSecond] to it[HistoricalOhlcCandleTable.close] }

        HistoricalOhlcSeries(
            requestSinceEpochSecond = fetch[HistoricalOhlcFetchTable.sinceEpochSecond],
            coverageFromEpochSecond = coverageFrom,
            coverageUntilEpochSecond = coverageUntil,
            fetchedAtEpochSecond = fetch[HistoricalOhlcFetchTable.fetchedAtEpochSecond],
            candles = candles,
        )
    }

    override suspend fun loadLatestProofForSince(
        pair: String,
        intervalMinutes: Int,
        sinceEpochSecond: Long,
    ): HistoricalOhlcSeries? = database.readTransactionIO {
        // Newest proof for this exact request range, covering or not: the cache paces
        // insufficient same-since requests off it while fresh. Legacy rows with null
        // coverage fail closed. Marker proofs (empty span) yield no candles.
        val fetch = HistoricalOhlcFetchTable
            .selectAll()
            .where {
                (HistoricalOhlcFetchTable.pair eq pair) and
                    (HistoricalOhlcFetchTable.intervalMinutes eq intervalMinutes) and
                    (HistoricalOhlcFetchTable.sinceEpochSecond eq sinceEpochSecond) and
                    HistoricalOhlcFetchTable.coverageFromEpochSecond.isNotNull() and
                    HistoricalOhlcFetchTable.coverageUntilEpochSecond.isNotNull()
            }
            .orderBy(HistoricalOhlcFetchTable.fetchedAtEpochSecond, SortOrder.DESC)
            .limit(1)
            .firstOrNull()
            ?: return@readTransactionIO null

        val coverageFrom = fetch[HistoricalOhlcFetchTable.coverageFromEpochSecond]
            ?: return@readTransactionIO null
        val coverageUntil = fetch[HistoricalOhlcFetchTable.coverageUntilEpochSecond]
            ?: return@readTransactionIO null
        val candles = HistoricalOhlcCandleTable
            .selectAll()
            .where {
                (HistoricalOhlcCandleTable.pair eq pair) and
                    (HistoricalOhlcCandleTable.intervalMinutes eq intervalMinutes) and
                    (HistoricalOhlcCandleTable.candleStartEpochSecond greaterEq coverageFrom) and
                    (HistoricalOhlcCandleTable.candleStartEpochSecond less coverageUntil)
            }
            .orderBy(HistoricalOhlcCandleTable.candleStartEpochSecond, SortOrder.ASC)
            .map { it[HistoricalOhlcCandleTable.candleStartEpochSecond] to it[HistoricalOhlcCandleTable.close] }

        HistoricalOhlcSeries(
            requestSinceEpochSecond = fetch[HistoricalOhlcFetchTable.sinceEpochSecond],
            coverageFromEpochSecond = coverageFrom,
            coverageUntilEpochSecond = coverageUntil,
            fetchedAtEpochSecond = fetch[HistoricalOhlcFetchTable.fetchedAtEpochSecond],
            candles = candles,
        )
    }

    override suspend fun saveFetch(
        pair: String,
        intervalMinutes: Int,
        sinceEpochSecond: Long,
        fetchedAtEpochSecond: Long,
        candles: List<Pair<Long, BigDecimal>>,
        mayBeTruncated: Boolean,
    ): Boolean {
        var contentChanged = false
        database.safeTransactionIO(log, "Failed to persist historical OHLC evidence") {
            val durationSeconds = intervalMinutes * 60L
            val distinct = candles.distinctBy { it.first }
            val responseByStart = distinct.associate { it.first to it.second }
            // Authoritative deletion domain, mirroring the in-memory replacement: a short
            // RAW provider page is complete for [since, wall), while a RAW page at the
            // endpoint limit may be truncated (Kraken serves oldest-first with a `last`
            // cursor this cache does not follow) and only proves the completed span it
            // actually returned. mayBeTruncated is measured on the RAW page before
            // in-progress-candle filtering — never inferred from the filtered list.
            // Either way only completed candles (start + duration < wall) can be
            // judged absent; a truncated page with zero returned candles proves
            // nothing absent.
            val completedBefore = fetchedAtEpochSecond - durationSeconds
            val domainFrom: Long
            val domainToInclusive: Long
            if (mayBeTruncated) {
                val firstReturned = distinct.minOfOrNull { it.first }
                if (firstReturned == null) {
                    domainFrom = 1L
                    domainToInclusive = 0L
                } else {
                    domainFrom = maxOf(sinceEpochSecond, firstReturned)
                    domainToInclusive = minOf(distinct.maxOf { it.first }, completedBefore - 1)
                }
            } else {
                domainFrom = sinceEpochSecond
                domainToInclusive = completedBefore - 1
            }
            // Load the deletion domain plus any response candle outside it, so out-of-domain
            // upserts still compare against the stored close instead of misfiring as added.
            var selection: Op<Boolean> =
                (HistoricalOhlcCandleTable.candleStartEpochSecond greaterEq domainFrom) and
                    (HistoricalOhlcCandleTable.candleStartEpochSecond lessEq domainToInclusive)
            responseByStart.keys.chunked(SQLITE_IN_CHUNK_SIZE).forEach { chunk ->
                selection = selection or (HistoricalOhlcCandleTable.candleStartEpochSecond inList chunk)
            }
            val stored = HistoricalOhlcCandleTable
                .selectAll()
                .where {
                    (HistoricalOhlcCandleTable.pair eq pair) and
                        (HistoricalOhlcCandleTable.intervalMinutes eq intervalMinutes) and
                        selection
                }
                .associate {
                    it[HistoricalOhlcCandleTable.candleStartEpochSecond] to
                        (it[HistoricalOhlcCandleTable.close] to it[HistoricalOhlcCandleTable.fetchedAtEpochSecond])
                }
            // Newer evidence wins: rows witnessed later than this fetch keep their close
            // and are never counted as changed, covering out-of-order overlapping fetches.
            val removed = stored.keys.filter { start ->
                start in domainFrom..domainToInclusive &&
                    start !in responseByStart &&
                    stored.getValue(start).second <= fetchedAtEpochSecond
            }
            responseByStart.forEach { (candleStart, close) ->
                val existing = stored[candleStart]
                if (existing == null ||
                    (existing.second <= fetchedAtEpochSecond && existing.first.compareTo(close) != 0)
                ) {
                    contentChanged = true
                }
                if (existing == null || existing.second <= fetchedAtEpochSecond) {
                    HistoricalOhlcCandleTable.upsert {
                        it[HistoricalOhlcCandleTable.pair] = pair
                        it[HistoricalOhlcCandleTable.intervalMinutes] = intervalMinutes
                        it[HistoricalOhlcCandleTable.candleStartEpochSecond] = candleStart
                        it[HistoricalOhlcCandleTable.close] = close
                        it[HistoricalOhlcCandleTable.fetchedAtEpochSecond] = fetchedAtEpochSecond
                    }
                }
            }
            if (removed.isNotEmpty()) {
                contentChanged = true
                removed.chunked(SQLITE_IN_CHUNK_SIZE).forEach { chunk ->
                    HistoricalOhlcCandleTable.deleteWhere {
                        (HistoricalOhlcCandleTable.pair eq pair) and
                            (HistoricalOhlcCandleTable.intervalMinutes eq intervalMinutes) and
                            (HistoricalOhlcCandleTable.candleStartEpochSecond inList chunk)
                    }
                }
            }
            // The persisted proof claims only the proven span, computed by the same
            // helper as the in-memory record. A truncated page with zero completed
            // rows proves no reusable coverage, so it persists an explicit empty
            // marker instead: the marker paces the exact request's refetch without
            // validating any window (its empty span contains no non-degenerate range).
            val computedCoverage = authoritativeOhlcCoverage(
                requestSinceEpochSecond = sinceEpochSecond,
                fetchWallEpochSecond = fetchedAtEpochSecond,
                intervalMinutes = intervalMinutes,
                completedCandles = distinct,
                mayBeTruncated = mayBeTruncated,
            )
            // A marker proves nothing, so it never overwrites a real proof recorded
            // at the same wall; a real proof still replaces a same-wall marker.
            val coverage = computedCoverage ?: OhlcCoverage(sinceEpochSecond, sinceEpochSecond)
            if (computedCoverage == null) {
                HistoricalOhlcFetchTable.insertIgnore {
                    it[HistoricalOhlcFetchTable.pair] = pair
                    it[HistoricalOhlcFetchTable.intervalMinutes] = intervalMinutes
                    it[HistoricalOhlcFetchTable.sinceEpochSecond] = sinceEpochSecond
                    it[HistoricalOhlcFetchTable.fetchedAtEpochSecond] = fetchedAtEpochSecond
                    it[HistoricalOhlcFetchTable.coverageFromEpochSecond] = coverage.fromEpochSecond
                    it[HistoricalOhlcFetchTable.coverageUntilEpochSecond] = coverage.untilEpochSecond
                }
            } else {
                HistoricalOhlcFetchTable.upsert {
                    it[HistoricalOhlcFetchTable.pair] = pair
                    it[HistoricalOhlcFetchTable.intervalMinutes] = intervalMinutes
                    it[HistoricalOhlcFetchTable.sinceEpochSecond] = sinceEpochSecond
                    it[HistoricalOhlcFetchTable.fetchedAtEpochSecond] = fetchedAtEpochSecond
                    it[HistoricalOhlcFetchTable.coverageFromEpochSecond] = coverage.fromEpochSecond
                    it[HistoricalOhlcFetchTable.coverageUntilEpochSecond] = coverage.untilEpochSecond
                }
            }
            pruneFetchProofs(pair, intervalMinutes, sinceEpochSecond)
            // Fetch-proof rows are metadata and change on every revalidation; only candle
            // content is evidence a comparison consumes. A content-identical refetch (or an
            // empty revalidation of an already-empty window) must not invalidate caches.
            // The OHLC revision below is an observability/coarse-change counter only: cached
            // comparisons validate their own consumed windows via the dependency manifest, so
            // unrelated pairs changing here never invalidate them.
            if (contentChanged) {
                bumpComparisonEvidenceRevision()
                bumpSyncMetadataCounter(SyncMetadataKeys.OHLC_CANDLE_CONTENT_REVISION)
            }
        }
        return contentChanged
    }

    /**
     * Bounds the fetch-proof lineage for one (pair, interval, since): every revalidation
     * appends a proof row, so without pruning a long-lived daemon grows this table forever.
     *
     * Retention is per coverage lineage. Proofs sharing a coverage start are repeated
     * revalidations of one range (the newest few are retained, the just-inserted one among
     * them), while distinct coverage starts are disjoint lineages that must never evict each
     * other: three newer truncated proofs must not prune the only proof covering an older
     * required window. A per-request absolute cap keeps storage finite even if truncation
     * starts shift on every save. Every eviction fails closed to a live refetch, never a
     * stale hit. Legacy rows without coverage prove nothing and are dropped from the lineage.
     * Candle rows are shared evidence and are never pruned here.
     */
    private fun pruneFetchProofs(pair: String, intervalMinutes: Int, sinceEpochSecond: Long) {
        HistoricalOhlcFetchTable.deleteWhere {
            (HistoricalOhlcFetchTable.pair eq pair) and
                (HistoricalOhlcFetchTable.intervalMinutes eq intervalMinutes) and
                (HistoricalOhlcFetchTable.sinceEpochSecond eq sinceEpochSecond) and
                (
                    HistoricalOhlcFetchTable.coverageFromEpochSecond.isNull() or
                        HistoricalOhlcFetchTable.coverageUntilEpochSecond.isNull()
                    )
        }
        // Newest first, so per-lineage take() keeps the freshest proofs.
        val lineageWalls = HistoricalOhlcFetchTable
            .selectAll()
            .where {
                (HistoricalOhlcFetchTable.pair eq pair) and
                    (HistoricalOhlcFetchTable.intervalMinutes eq intervalMinutes) and
                    (HistoricalOhlcFetchTable.sinceEpochSecond eq sinceEpochSecond)
            }
            .orderBy(HistoricalOhlcFetchTable.fetchedAtEpochSecond, SortOrder.DESC)
            .map {
                it[HistoricalOhlcFetchTable.coverageFromEpochSecond] to
                    it[HistoricalOhlcFetchTable.fetchedAtEpochSecond]
            }
        val retainedByLineage = lineageWalls
            .groupBy({ it.first }, { it.second })
            .values
            .flatMap { walls -> walls.take(RETAINED_FETCH_PROOFS) }
            .toSet()
        val retainedFetchedAt = lineageWalls
            .map { it.second }
            .filter { it in retainedByLineage }
            .take(MAX_FETCH_PROOFS_PER_REQUEST)
        if (retainedFetchedAt.isNotEmpty()) {
            HistoricalOhlcFetchTable.deleteWhere {
                (HistoricalOhlcFetchTable.pair eq pair) and
                    (HistoricalOhlcFetchTable.intervalMinutes eq intervalMinutes) and
                    (HistoricalOhlcFetchTable.sinceEpochSecond eq sinceEpochSecond) and
                    (HistoricalOhlcFetchTable.fetchedAtEpochSecond notInList retainedFetchedAt)
            }
        }
    }
}
