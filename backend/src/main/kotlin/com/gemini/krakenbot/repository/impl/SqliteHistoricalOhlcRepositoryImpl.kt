package com.gemini.krakenbot.repository.impl

import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.repository.HistoricalOhlcRepository
import com.gemini.krakenbot.repository.HistoricalOhlcSeries
import com.gemini.krakenbot.repository.table.HistoricalOhlcCandleTable
import com.gemini.krakenbot.repository.table.HistoricalOhlcFetchTable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.notInList
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
            if (candles.isEmpty()) {
                val existingCount = HistoricalOhlcCandleTable
                    .selectAll()
                    .where {
                        (HistoricalOhlcCandleTable.pair eq pair) and
                            (HistoricalOhlcCandleTable.intervalMinutes eq intervalMinutes) and
                            (HistoricalOhlcCandleTable.candleStartEpochSecond greaterEq sinceEpochSecond)
                    }
                    .count()
                if (existingCount > 0L) {
                    contentChanged = true
                }
            } else {
                candles.distinctBy { it.first }.forEach { (candleStart, close) ->
                    val existing = HistoricalOhlcCandleTable
                        .selectAll()
                        .where {
                            (HistoricalOhlcCandleTable.pair eq pair) and
                                (HistoricalOhlcCandleTable.intervalMinutes eq intervalMinutes) and
                                (HistoricalOhlcCandleTable.candleStartEpochSecond eq candleStart)
                        }
                        .limit(1)
                        .firstOrNull()
                    if (existing == null || existing[HistoricalOhlcCandleTable.close].compareTo(close) != 0) {
                        contentChanged = true
                    }
                    HistoricalOhlcCandleTable.upsert {
                        it[HistoricalOhlcCandleTable.pair] = pair
                        it[HistoricalOhlcCandleTable.intervalMinutes] = intervalMinutes
                        it[HistoricalOhlcCandleTable.candleStartEpochSecond] = candleStart
                        it[HistoricalOhlcCandleTable.close] = close
                        it[HistoricalOhlcCandleTable.fetchedAtEpochSecond] = fetchedAtEpochSecond
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
