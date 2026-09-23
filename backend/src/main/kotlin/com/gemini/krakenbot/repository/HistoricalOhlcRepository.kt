package com.gemini.krakenbot.repository

import java.math.BigDecimal

/**
 * The historical range one OHLC provider response actually proved, as a half-open time span
 * `[fromEpochSecond, untilEpochSecond)`. A request window `[since, upTo]` is covered only when
 * the proven span contains it ([contains]); anything else must refetch live or fail closed.
 *
 * The critical distinction is request bounds versus proven coverage bounds: a short complete
 * response proves its whole requested domain, while a page-limited response proves only the
 * completed span it actually returned, even though both were requested with the same `since`.
 */
data class OhlcCoverage(
    /** Inclusive first proven instant: the request `since` for a short response, otherwise the
     *  first returned completed candle start (never before the request `since`). */
    val fromEpochSecond: Long,
    /** Exclusive end of proven coverage: the fetch wall for a short response, otherwise one
     *  candle past the last returned completed candle start. A window ending exactly here is
     *  covered; anything beyond it is not. */
    val untilEpochSecond: Long,
) {
    fun contains(sinceEpochSecond: Long, upToEpochSecond: Long): Boolean =
        fromEpochSecond <= sinceEpochSecond && upToEpochSecond <= untilEpochSecond

    fun contains(other: OhlcCoverage): Boolean =
        fromEpochSecond <= other.fromEpochSecond && other.untilEpochSecond <= untilEpochSecond
}

/**
 * Computes the authoritative coverage of one completed-candle response. This is the single
 * source of truth used by both the in-memory cache and the SQLite store, so both layers
 * always agree on what a response proved:
 * - short response ([mayBeTruncated] false): complete for the requested domain, so coverage
 *   is `[requestSince, fetchWall)` — including a short EMPTY response, which is valid
 *   negative evidence for its requested range;
 * - truncated response with completed rows: only the observed returned span
 *   `[firstReturned, lastReturned + duration)`, never the unreturned head of the request;
 * - truncated response with zero completed rows, or with every row predating the request:
 *   proves nothing reusable ([null]).
 *
 * Invariant: if a response was not authoritative enough to delete evidence for a range, it
 * is also not authoritative enough to certify that range as freshly covered — the coverage
 * domain never exceeds the replacement domain's proven span.
 */
fun authoritativeOhlcCoverage(
    requestSinceEpochSecond: Long,
    fetchWallEpochSecond: Long,
    intervalMinutes: Int,
    completedCandles: List<Pair<Long, BigDecimal>>,
    mayBeTruncated: Boolean,
): OhlcCoverage? {
    if (!mayBeTruncated) {
        return OhlcCoverage(requestSinceEpochSecond, fetchWallEpochSecond)
    }
    val distinct = completedCandles.distinctBy { it.first }
    val firstReturned = distinct.minOfOrNull { it.first } ?: return null
    val lastReturned = distinct.maxOf { it.first }
    // Every returned row predates the request: newer unreturned data may exist past
    // the page cut, so no span here is anchored by in-range evidence. No proof.
    if (lastReturned < requestSinceEpochSecond) {
        return null
    }
    return OhlcCoverage(
        fromEpochSecond = maxOf(requestSinceEpochSecond, firstReturned),
        untilEpochSecond = lastReturned + intervalMinutes * 60L,
    )
}

data class HistoricalOhlcSeries(
    val requestSinceEpochSecond: Long,
    val coverageFromEpochSecond: Long,
    val coverageUntilEpochSecond: Long,
    val fetchedAtEpochSecond: Long,
    val candles: List<Pair<Long, BigDecimal>>,
)

/**
 * A bounded negative discovery result for one pair and interval. A truncated provider page
 * established that no candle can close before [earliestReachableEpochSecond] for the observed
 * historical request. This is selection evidence only; it never proves positive candle coverage.
 */
data class OhlcReachabilityFrontier(
    val pair: String,
    val intervalMinutes: Int,
    val earliestReachableEpochSecond: Long,
    val observedAtEpochSecond: Long,
    val retryAfterEpochSecond: Long,
) {
    fun isFresh(nowEpochSecond: Long): Boolean = nowEpochSecond < retryAfterEpochSecond

    /** Whether the frontier proves that no returned candle can close by this valuation time. */
    fun blocks(upToEpochSecond: Long): Boolean = upToEpochSecond <= observedAtEpochSecond &&
        upToEpochSecond < earliestReachableEpochSecond + intervalMinutes * 60L
}

/**
 * Selects the newest provider observation. Same-wall observations merge conservatively: retain
 * the earlier boundary (which skips fewer valuations) and the earlier retry deadline.
 */
internal fun selectReachabilityFrontier(
    current: OhlcReachabilityFrontier?,
    candidate: OhlcReachabilityFrontier,
): OhlcReachabilityFrontier = when {
    current == null || candidate.observedAtEpochSecond > current.observedAtEpochSecond -> candidate

    candidate.observedAtEpochSecond < current.observedAtEpochSecond -> current

    else -> current.copy(
        earliestReachableEpochSecond = minOf(
            current.earliestReachableEpochSecond,
            candidate.earliestReachableEpochSecond,
        ),
        retryAfterEpochSecond = minOf(current.retryAfterEpochSecond, candidate.retryAfterEpochSecond),
    )
}

/** Separate cache-selection evidence: an interval was skipped because its frontier blocked it. */
data class OhlcReachabilityDependency(
    val pair: String,
    val intervalMinutes: Int,
    val earliestReachableEpochSecond: Long,
)

interface HistoricalOhlcRepository {
    /** Loads the single bounded negative reachability observation for this series, if present. */
    suspend fun loadReachabilityFrontier(pair: String, intervalMinutes: Int): OhlcReachabilityFrontier? = null

    /** Stores a newer reachability observation; older out-of-order observations are ignored. */
    suspend fun saveReachabilityFrontier(frontier: OhlcReachabilityFrontier) = Unit

    /**
     * Removes a frontier only when a newer authoritative response proves a candle earlier than
     * the stored boundary. A null frontier is never inferred from an empty, failed, or malformed
     * provider response.
     */
    suspend fun clearReachabilityFrontierIfContradicted(
        pair: String,
        intervalMinutes: Int,
        observedAtEpochSecond: Long,
        provenReachableFromEpochSecond: Long,
    ) = Unit

    /**
     * Returns one persisted fetch and its candles when that single fetch's proven [OhlcCoverage]
     * contains the requested window. A null result means the database cannot prove coverage and
     * the caller must fetch fresh evidence. Proofs written before coverage bounds were
     * persisted fail closed and are never returned.
     */
    suspend fun loadCovered(
        pair: String,
        intervalMinutes: Int,
        sinceEpochSecond: Long,
        upToEpochSecond: Long,
    ): HistoricalOhlcSeries?

    /**
     * Returns the newest persisted proof for one exact request `since` plus the candles
     * inside its proven span — whether or not that span covers any particular window —
     * so the cache can pace an insufficient same-since request without refetching live.
     * A null result (no proof, or only legacy rows without coverage) means the caller
     * must fetch fresh evidence. Truncated-empty marker proofs carry an empty span and
     * yield no candles; they pace the exact request but never validate a window.
     */
    suspend fun loadLatestProofForSince(
        pair: String,
        intervalMinutes: Int,
        sinceEpochSecond: Long,
    ): HistoricalOhlcSeries?

    /**
     * Persists a completed-candle response and its coverage proof atomically.
     * Returns true if any candle content was added, modified, or removed.
     *
     * A successful response replaces the authoritative contents of its fetched domain:
     * stored completed candles in `[since, wall)` that the response omits are deleted,
     * response candles are upserted, and rows witnessed later than this fetch are always
     * kept. [mayBeTruncated] is measured on the RAW provider page (before
     * in-progress-candle filtering); when true the response may be page-limited and
     * proves absent only the completed span it actually returned. When the filtered
     * [candles] list is empty and [mayBeTruncated] is true, nothing is judged absent.
     */
    suspend fun saveFetch(
        pair: String,
        intervalMinutes: Int,
        sinceEpochSecond: Long,
        fetchedAtEpochSecond: Long,
        candles: List<Pair<Long, BigDecimal>>,
        mayBeTruncated: Boolean,
    ): Boolean
}
