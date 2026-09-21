package com.gemini.krakenbot.repository.impl

import com.gemini.krakenbot.repository.HistoricalOhlcRepository
import com.gemini.krakenbot.repository.HistoricalOhlcSeries
import com.gemini.krakenbot.repository.table.HistoricalOhlcCandleTable
import com.gemini.krakenbot.repository.table.HistoricalOhlcFetchTable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import org.slf4j.LoggerFactory
import java.math.BigDecimal

class SqliteHistoricalOhlcRepositoryImpl(private val database: Database) : HistoricalOhlcRepository {
    private val log = LoggerFactory.getLogger(SqliteHistoricalOhlcRepositoryImpl::class.java)

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

        val candles = HistoricalOhlcCandleTable
            .selectAll()
            .where {
                (HistoricalOhlcCandleTable.pair eq pair) and
                    (HistoricalOhlcCandleTable.intervalMinutes eq intervalMinutes) and
                    (HistoricalOhlcCandleTable.candleStartEpochSecond greaterEq sinceEpochSecond)
            }
            .orderBy(HistoricalOhlcCandleTable.candleStartEpochSecond, SortOrder.ASC)
            .map { it[HistoricalOhlcCandleTable.candleStartEpochSecond] to it[HistoricalOhlcCandleTable.close] }

        HistoricalOhlcSeries(
            sinceEpochSecond = fetch[HistoricalOhlcFetchTable.sinceEpochSecond],
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
    ) {
        database.safeTransactionIO(log, "Failed to persist historical OHLC evidence") {
            candles.distinctBy { it.first }.forEach { (candleStart, close) ->
                HistoricalOhlcCandleTable.upsert {
                    it[HistoricalOhlcCandleTable.pair] = pair
                    it[HistoricalOhlcCandleTable.intervalMinutes] = intervalMinutes
                    it[HistoricalOhlcCandleTable.candleStartEpochSecond] = candleStart
                    it[HistoricalOhlcCandleTable.close] = close
                    it[HistoricalOhlcCandleTable.fetchedAtEpochSecond] = fetchedAtEpochSecond
                }
            }
            HistoricalOhlcFetchTable.upsert {
                it[HistoricalOhlcFetchTable.pair] = pair
                it[HistoricalOhlcFetchTable.intervalMinutes] = intervalMinutes
                it[HistoricalOhlcFetchTable.sinceEpochSecond] = sinceEpochSecond
                it[HistoricalOhlcFetchTable.fetchedAtEpochSecond] = fetchedAtEpochSecond
            }
            bumpComparisonEvidenceRevision()
        }
    }
}
