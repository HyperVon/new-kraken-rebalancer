package com.gemini.krakenbot.repository.impl

import com.gemini.krakenbot.model.KrakenApiConstants
import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.repository.HistoricalOhlcRepository
import com.gemini.krakenbot.repository.HistoricalOhlcSeries
import com.gemini.krakenbot.repository.table.HistoricalOhlcCandleTable
import com.gemini.krakenbot.repository.table.HistoricalOhlcFetchTable
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import org.slf4j.LoggerFactory
import java.math.BigDecimal

class SqliteHistoricalOhlcRepositoryImpl(private val database: Database) : HistoricalOhlcRepository {
    private val log = LoggerFactory.getLogger(SqliteHistoricalOhlcRepositoryImpl::class.java)

    private companion object {
        const val RETAINED_FETCH_PROOFS = 3
        const val SQLITE_IN_CHUNK_SIZE = 500
    }

    override suspend fun loadCovered(
        pair: String,
        intervalMinutes: Int,
        sinceEpochSecond: Long,
        upToEpochSecond: Long,
    ): HistoricalOhlcSeries? = database.readTransactionIO {
        val fetch = HistoricalOhlcFetchTable
            .selectAll()
            .where {
                (HistoricalOhlcFetchTable.pair eq pair) and
                    (HistoricalOhlcFetchTable.intervalMinutes eq intervalMinutes) and
                    (HistoricalOhlcFetchTable.sinceEpochSecond lessEq sinceEpochSecond) and
                    (HistoricalOhlcFetchTable.fetchedAtEpochSecond greaterEq upToEpochSecond)
            }
            .orderBy(HistoricalOhlcFetchTable.fetchedAtEpochSecond, SortOrder.DESC)
            .limit(1)
            .firstOrNull()
            ?: return@readTransactionIO null

        val fetchSince = fetch[HistoricalOhlcFetchTable.sinceEpochSecond]
        val fetchedAt = fetch[HistoricalOhlcFetchTable.fetchedAtEpochSecond]
        val candles = HistoricalOhlcCandleTable
            .selectAll()
            .where {
                (HistoricalOhlcCandleTable.pair eq pair) and
                    (HistoricalOhlcCandleTable.intervalMinutes eq intervalMinutes) and
                    (HistoricalOhlcCandleTable.candleStartEpochSecond greaterEq fetchSince) and
                    (HistoricalOhlcCandleTable.candleStartEpochSecond less fetchedAt)
            }
            .orderBy(HistoricalOhlcCandleTable.candleStartEpochSecond, SortOrder.ASC)
            .map { it[HistoricalOhlcCandleTable.candleStartEpochSecond] to it[HistoricalOhlcCandleTable.close] }

        HistoricalOhlcSeries(
            sinceEpochSecond = fetchSince,
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
    ): Boolean {
        var contentChanged = false
        database.safeTransactionIO(log, "Failed to persist historical OHLC evidence") {
            val durationSeconds = intervalMinutes * 60L
            val distinct = candles.distinctBy { it.first }
            val responseByStart = distinct.associate { it.first to it.second }
            // Authoritative deletion domain, mirroring the in-memory replacement: a short
            // response is complete for [since, wall), while a full page may be truncated
            // (Kraken serves oldest-first with a `last` cursor this cache does not follow)
            // and only proves its covered [first, last] span. Either way only completed
            // candles (start + duration < wall) can be judged absent.
            val completedBefore = fetchedAtEpochSecond - durationSeconds
            val domainFrom: Long
            val domainToInclusive: Long
            if (distinct.size >= KrakenApiConstants.OHLC_PAGE_SIZE) {
                domainFrom = maxOf(sinceEpochSecond, distinct.minOf { it.first })
                domainToInclusive = minOf(distinct.maxOf { it.first }, completedBefore - 1)
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
            HistoricalOhlcFetchTable.upsert {
                it[HistoricalOhlcFetchTable.pair] = pair
                it[HistoricalOhlcFetchTable.intervalMinutes] = intervalMinutes
                it[HistoricalOhlcFetchTable.sinceEpochSecond] = sinceEpochSecond
                it[HistoricalOhlcFetchTable.fetchedAtEpochSecond] = fetchedAtEpochSecond
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
     * The newest few proofs are retained (the just-inserted one among them) and superseded
     * ones are deleted inside the same transaction that commits their replacement, mirroring
     * the comparison-cache retention policy. Candle rows are shared evidence and are never
     * pruned here.
     */
    private fun pruneFetchProofs(pair: String, intervalMinutes: Int, sinceEpochSecond: Long) {
        val retainedFetchedAt = HistoricalOhlcFetchTable
            .selectAll()
            .where {
                (HistoricalOhlcFetchTable.pair eq pair) and
                    (HistoricalOhlcFetchTable.intervalMinutes eq intervalMinutes) and
                    (HistoricalOhlcFetchTable.sinceEpochSecond eq sinceEpochSecond)
            }
            .orderBy(HistoricalOhlcFetchTable.fetchedAtEpochSecond, SortOrder.DESC)
            .limit(RETAINED_FETCH_PROOFS)
            .map { it[HistoricalOhlcFetchTable.fetchedAtEpochSecond] }
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
