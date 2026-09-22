package com.gemini.krakenbot.repository

import java.math.BigDecimal

data class HistoricalOhlcSeries(
    val sinceEpochSecond: Long,
    val fetchedAtEpochSecond: Long,
    val candles: List<Pair<Long, BigDecimal>>,
)

interface HistoricalOhlcRepository {
    /**
     * Returns one persisted fetch and its candles when that single fetch covers the requested
     * window. A null result means the database cannot prove coverage and the caller must fetch
     * fresh evidence.
     */
    suspend fun loadCovered(
        pair: String,
        intervalMinutes: Int,
        sinceEpochSecond: Long,
        upToEpochSecond: Long,
    ): HistoricalOhlcSeries?

    /**
     * Persists a completed-candle response and its coverage proof atomically.
     * Returns true if any candle content was added, modified, or removed.
     *
     * A successful response replaces the authoritative contents of its fetched domain:
     * stored completed candles in `[since, wall)` that the response omits are deleted,
     * response candles are upserted, and rows witnessed later than this fetch are always
     * kept. A full page proves only its covered span, since it may be truncated.
     */
    suspend fun saveFetch(
        pair: String,
        intervalMinutes: Int,
        sinceEpochSecond: Long,
        fetchedAtEpochSecond: Long,
        candles: List<Pair<Long, BigDecimal>>,
    ): Boolean
}
