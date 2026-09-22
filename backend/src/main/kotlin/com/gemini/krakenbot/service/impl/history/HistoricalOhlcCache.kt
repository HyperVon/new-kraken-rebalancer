package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.model.KrakenApiConstants
import com.gemini.krakenbot.repository.ConsumedOhlcDependency
import com.gemini.krakenbot.repository.HistoricalOhlcRepository
import com.gemini.krakenbot.repository.OhlcCoverage
import com.gemini.krakenbot.repository.authoritativeOhlcCoverage
import com.gemini.krakenbot.service.KrakenService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap

sealed interface OhlcRevalidationResult {
    data class Unchanged(val updatedDependency: ConsumedOhlcDependency) : OhlcRevalidationResult
    data object ContentChanged : OhlcRevalidationResult
}

/**
 * Bounded refresh policy for stored OHLC fetches. A covering fetch is trusted only for its
 * freshness window; after expiry the stored evidence is revalidated against the live source
 * once per window, and stale data keeps serving until that attempt succeeds (the next attempt
 * waits one full window again). Empty fetch proofs revalidate on a short cadence because a
 * later provider backfill can cure a previously empty window; completed historical candles
 * are stable evidence and revalidate on a long cadence, with recently fetched candles on a
 * middle cadence since the provider still actively corrects them.
 */
class OhlcRefreshPolicy(
    private val emptyResultRevalidationSeconds: Long,
    private val recentCandleTtlSeconds: Long,
    private val historicalCandleTtlSeconds: Long,
    private val recentCandleAgeSeconds: Long,
) {
    constructor() : this(
        emptyResultRevalidationSeconds = 600,
        recentCandleTtlSeconds = 3_600,
        historicalCandleTtlSeconds = 604_800,
        recentCandleAgeSeconds = 86_400,
    )

    fun isExpired(
        candles: Collection<Pair<Long, BigDecimal>>,
        fetchedAtEpochSecond: Long,
        intervalMinutes: Int,
        nowEpochSecond: Long,
    ): Boolean =
        nowEpochSecond >= fetchedAtEpochSecond + freshnessSeconds(candles, fetchedAtEpochSecond, intervalMinutes)

    /** Freshness window for one stored fetch: how long its proof may serve and how long a
     *  failed revalidation of it waits before the next live attempt. */
    fun freshnessSeconds(
        candles: Collection<Pair<Long, BigDecimal>>,
        fetchedAtEpochSecond: Long,
        intervalMinutes: Int,
    ): Long {
        if (candles.isEmpty()) return emptyResultRevalidationSeconds
        var newestCandleStart = Long.MIN_VALUE
        for (candle in candles) if (candle.first > newestCandleStart) newestCandleStart = candle.first
        val newestCompletedCandleEnd = newestCandleStart + intervalMinutes * 60L
        val dataAgeSeconds = (fetchedAtEpochSecond - newestCompletedCandleEnd).coerceAtLeast(0)
        return if (dataAgeSeconds < recentCandleAgeSeconds) recentCandleTtlSeconds else historicalCandleTtlSeconds
    }
}

/**
 * Cache for completed Kraken OHLC candles, eliminating the per-valuation-point
 * refetch storm: every historical priceAt lookup used to issue its own live getOHLC call per
 * interval tier, multiplying one comparison across hundreds of identical and near-identical
 * requests. When [persistentRepository] is supplied, the cache also survives application
 * restarts. Completed candles are normally stable evidence, while a later fresh response
 * replaces the stored contents of its fetched domain when the provider corrects, backfills,
 * or removes a historical candle.
 *
 * Cache contract:
 * - Key granularity is pair / interval; fetched ranges are recorded per fetch.
 * - Only candles that closed strictly before the fetch wall time are stored. Kraken's trailing
 *   in-progress candle (close at or after the fetch) is dropped; it must never become
 *   completed-candle evidence for history.
 * - A request is served from memory only when a SINGLE stored fetch provably contains every
 *   completed candle a fresh fetch could contribute for that window: that fetch's PROVEN
 *   coverage must contain the window (short responses prove `[requestSince, wall)`, truncated
 *   responses prove only their returned span, truncated-empty responses prove nothing).
 *   Two different fetches are never combined into such a proof. A joiner whose stricter window
 *   is not covered performs its own fetch instead.
 * - A fetching initiator returns exactly its own completed-candle response, so uncached callers
 *   observe uncached semantics; the store only serves other requests under the proof above.
 * - Identical in-flight (pair, interval, since) requests join one flight; failures complete the
 *   flight exceptionally and are never cached.
 * - A covering fetch additionally ages out under [refreshPolicy]: an expired fetch is
 *   revalidated with a single live request (identical concurrent revalidations share one
 *   flight). A successful revalidation replaces the stored proof and may correct candle
 *   values; a failed one keeps serving the stale series for windows that fetch still
 *   covers and waits one full freshness window before the next live attempt — bounded
 *   retry, never a per-lookup refetch storm. A failed revalidation never extends the
 *   stored fetch's coverage: requests beyond its recorded wall time are not served stale
 *   evidence, they fail closed (or fetch live) until the next attempt succeeds. Failures
 *   are never persisted as empty or partial evidence.
 * - Only the live OHLC endpoint participates; never a ticker or any other live-priced source.
 */
class HistoricalOhlcCache(
    private val krakenService: KrakenService,
    private val persistentRepository: HistoricalOhlcRepository? = null,
    private val refreshPolicy: OhlcRefreshPolicy = OhlcRefreshPolicy(),
    private val nowProvider: () -> Instant = Instant::now,
) {
    private val log = LoggerFactory.getLogger(HistoricalOhlcCache::class.java)

    private class FetchRecord(
        /** Original request `since`: refetch lineage and exact-range matching, never coverage. */
        val requestSinceEpochSecond: Long,
        /** The historical span this response actually proved (see [OhlcCoverage]). */
        val coverage: OhlcCoverage,
        val fetchWallEpochSecond: Long,
        /** Earliest epoch second at which a failed revalidation of this proof may retry the
         *  live source. Pacing only; never participates in coverage. */
        @JvmField var retryNotBeforeEpochSecond: Long = 0L,
    )

    private class SeriesEntry {
        val candles = ConcurrentSkipListMap<Long, BigDecimal>()

        /**
         * Witness wall per stored candle start: the fetch wall that last wrote the close.
         * Out-of-order overlapping fetches complete newest-first, so a replacement must not
         * delete or overwrite evidence witnessed later than its own wall. Every [candles]
         * insertion stamps this map under the same lock.
         */
        val candleWalls = ConcurrentSkipListMap<Long, Long>()
        val fetches = mutableListOf<FetchRecord>()

        /** A single-fetch proof whose PROVEN span contains the requested window: the last
         *  matching record in insertion order. Out-of-order completions and durable restores
         *  can append older walls later, so this is not always the newest wall — but that
         *  only anchors freshness to an older instant (earlier expiry, fail closed). */
        fun coveringFetch(sinceEpochSecond: Long, upToEpochSecond: Long): FetchRecord? = synchronized(fetches) {
            var newest: FetchRecord? = null
            for (fetch in fetches) {
                if (fetch.coverage.contains(sinceEpochSecond, upToEpochSecond)) {
                    newest = fetch
                }
            }
            newest
        }
    }

    private class CoveredSeries(val fetch: FetchRecord, val candles: List<Pair<Long, BigDecimal>>)

    /**
     * Result of recording one fetch: the series entry plus the reusable coverage the
     * response proved ([null] when a truncated page proved nothing), with the fetch wall
     * for freshness anchoring. Flights carry this so a joiner validates its own window
     * against the refresh it actually shared, not an older surviving proof.
     */
    private class FetchOutcome(val entry: SeriesEntry, val coverage: OhlcCoverage?, val fetchWallEpochSecond: Long)

    private val store = ConcurrentHashMap<SeriesKey, SeriesEntry>()
    private val inFlight = ConcurrentHashMap<FlightKey, CompletableDeferred<FetchOutcome>>()

    /**
     * Series-level outage backoff: earliest epoch second at which a revalidation of any range
     * of the series may retry the live source after a failure. Short and series-wide on purpose:
     * a paced cross-range proof must never validate a range it never attempted, so long backoff
     * applies only to a range's own record (see [FetchRecord.retryNotBeforeEpochSecond]), while
     * this backoff only stops a failing sweep from hammering the exchange range after range.
     */
    private val seriesRetryNotBefore = ConcurrentHashMap<SeriesKey, Long>()

    private data class SeriesKey(@JvmField val pair: String, @JvmField val intervalMinutes: Int)
    private data class FlightKey(
        @JvmField val pair: String,
        @JvmField val intervalMinutes: Long,
        @JvmField val sinceEpochSecond: Long,
    )

    /**
     * Returns completed candles for [pair]/[intervalMinutes] starting at [sinceEpochSecond],
     * serving stored history when a single recorded fetch provably equals a fresh fetch and is
     * still fresh under the refresh policy, revalidating an expired proof once, and fetching
     * (once per identical concurrent request) otherwise. [upTo] is the valuation instant the
     * caller resolves; candles that closed after it cannot be selected by callers.
     */
    suspend fun getOHLC(
        pair: String,
        intervalMinutes: Int,
        sinceEpochSecond: Long,
        upTo: Instant,
        onDependencyResolved: ((ConsumedOhlcDependency) -> Unit)? = null,
    ): List<Pair<Long, BigDecimal>> {
        val normalizedPair = pair.trim().uppercase()
        val seriesKey = SeriesKey(normalizedPair, intervalMinutes)
        val durationSeconds = intervalMinutes * 60L

        memoryCovered(seriesKey, sinceEpochSecond, upTo.epochSecond)?.let { covered ->
            if (!isExpired(covered, intervalMinutes)) {
                reportDependency(seriesKey, covered, sinceEpochSecond, upTo, intervalMinutes, onDependencyResolved)
                return covered.candles
            }
            val revalidated = revalidate(seriesKey, covered, normalizedPair, intervalMinutes, sinceEpochSecond, upTo)
            (memoryCovered(seriesKey, sinceEpochSecond, upTo.epochSecond) ?: covered).let {
                reportDependency(seriesKey, it, sinceEpochSecond, upTo, intervalMinutes, onDependencyResolved)
            }
            return revalidated
        }
        loadFromPersistent(seriesKey, sinceEpochSecond, upTo.epochSecond)?.let { covered ->
            if (!isExpired(covered, intervalMinutes)) {
                reportDependency(seriesKey, covered, sinceEpochSecond, upTo, intervalMinutes, onDependencyResolved)
                return covered.candles
            }
            val revalidated = revalidate(seriesKey, covered, normalizedPair, intervalMinutes, sinceEpochSecond, upTo)
            (memoryCovered(seriesKey, sinceEpochSecond, upTo.epochSecond) ?: covered).let {
                reportDependency(seriesKey, it, sinceEpochSecond, upTo, intervalMinutes, onDependencyResolved)
            }
            return revalidated
        }

        val flightKey = FlightKey(normalizedPair, intervalMinutes.toLong(), sinceEpochSecond)
        val (flight, created) = startOrJoinFlight(flightKey)
        if (created) {
            try {
                val fetchWallEpochSecond = nowProvider().epochSecond
                val fetched = krakenService.getOHLC(
                    pair = normalizedPair,
                    interval = intervalMinutes,
                    since = sinceEpochSecond,
                )
                val mayBeTruncated = fetched.size >= KrakenApiConstants.OHLC_PAGE_SIZE
                val completed = fetched.filter { it.first + durationSeconds < fetchWallEpochSecond }
                val outcome = rememberFetch(
                    seriesKey,
                    sinceEpochSecond,
                    fetchWallEpochSecond,
                    completed,
                    authoritative = true,
                    mayBeTruncated = mayBeTruncated,
                )
                persistFetch(seriesKey, sinceEpochSecond, fetchWallEpochSecond, completed, mayBeTruncated)
                // Transient record for dependency reporting only (never stored): a
                // truncated-empty response proved nothing, so it carries an empty span.
                val covered = CoveredSeries(
                    FetchRecord(
                        sinceEpochSecond,
                        outcome.coverage ?: OhlcCoverage(sinceEpochSecond, sinceEpochSecond),
                        fetchWallEpochSecond,
                    ),
                    completed,
                )
                reportDependency(seriesKey, covered, sinceEpochSecond, upTo, intervalMinutes, onDependencyResolved)
                flight.complete(outcome)
                // An initiator answers with exactly its own completed-candle response.
                return completed
            } catch (e: Throwable) {
                flight.completeExceptionally(e)
                throw e
            } finally {
                inFlight.remove(flightKey, flight)
            }
        }

        flight.await()
        serveFromMemory(seriesKey, sinceEpochSecond, upTo.epochSecond)?.let {
            memoryCovered(seriesKey, sinceEpochSecond, upTo.epochSecond)?.let { covered ->
                reportDependency(seriesKey, covered, sinceEpochSecond, upTo, intervalMinutes, onDependencyResolved)
            }
            return it
        }
        return getOHLC(normalizedPair, intervalMinutes, sinceEpochSecond, upTo, onDependencyResolved)
    }

    /**
     * Revalidates an expired covering fetch against the live source. The refetch reuses the
     * original fetch's request `since` so a complete replacement proof covers at least the
     * same range, and identical concurrent revalidations share one flight. A refresh that
     * proves nothing about this window (e.g. a truncated page for a later span) is treated
     * like a failure: the old proof is paced and its stale series keeps serving. On failure
     * the stored proof keeps its REAL wall time (coverage never extends beyond what was
     * actually fetched) and gains a retry-pacing timestamp one freshness window out: covered
     * windows keep serving stale data without network attempts, while windows beyond the
     * recorded coverage are not served stale evidence — they fail closed or fetch live.
     * A joiner whose shared flight failed serves its own covered stale series instead of
     * throwing.
     */
    private suspend fun revalidate(
        seriesKey: SeriesKey,
        covered: CoveredSeries,
        normalizedPair: String,
        intervalMinutes: Int,
        sinceEpochSecond: Long,
        upTo: Instant,
    ): List<Pair<Long, BigDecimal>> {
        if (nowProvider().epochSecond < covered.fetch.retryNotBeforeEpochSecond) {
            // A recent revalidation attempt failed; wait out the freshness window.
            return covered.candles
        }
        val durationSeconds = intervalMinutes * 60L
        val originalSince = covered.fetch.requestSinceEpochSecond
        val flightKey = FlightKey(normalizedPair, intervalMinutes.toLong(), originalSince)
        val (flight, created) = startOrJoinFlight(flightKey)
        if (created) {
            try {
                val fetchWallEpochSecond = nowProvider().epochSecond
                val fetched = krakenService.getOHLC(
                    pair = normalizedPair,
                    interval = intervalMinutes,
                    since = originalSince,
                )
                val mayBeTruncated = fetched.size >= KrakenApiConstants.OHLC_PAGE_SIZE
                val completed = fetched.filter { it.first + durationSeconds < fetchWallEpochSecond }
                val outcome = rememberFetch(
                    seriesKey,
                    originalSince,
                    fetchWallEpochSecond,
                    completed,
                    authoritative = true,
                    mayBeTruncated = mayBeTruncated,
                )
                persistFetch(seriesKey, originalSince, fetchWallEpochSecond, completed, mayBeTruncated)
                flight.complete(outcome)
                if (outcome.coverage?.contains(sinceEpochSecond, upTo.epochSecond) != true) {
                    // The refresh proved nothing about this window (e.g. a truncated page
                    // for a later span): pace the old proof like a failed attempt and keep
                    // serving its stale series instead of refetching live on every request.
                    paceFailedRevalidation(covered, intervalMinutes, sinceEpochSecond, upTo.epochSecond)
                    return covered.candles
                }
                return outcome.entry.candles.tailMap(sinceEpochSecond, true)
                    .entries.asSequence()
                    .map { it.key to it.value }
                    .toList()
            } catch (e: CancellationException) {
                flight.completeExceptionally(e)
                throw e
            } catch (e: Throwable) {
                flight.completeExceptionally(e)
                log.warn(
                    "OHLC revalidation failed; serving stale history for pair {} interval {}: {}",
                    normalizedPair,
                    intervalMinutes,
                    e.message,
                )
                paceFailedRevalidation(covered, intervalMinutes, sinceEpochSecond, upTo.epochSecond)
                return covered.candles
            } finally {
                inFlight.remove(flightKey, flight)
            }
        }

        try {
            flight.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // The initiating revalidation failed; serve this request's own covered stale
            // series rather than diverging from the initiator's stale answer.
            return covered.candles
        }
        serveFromMemory(seriesKey, sinceEpochSecond, upTo.epochSecond)?.let { return it }
        return getOHLC(normalizedPair, intervalMinutes, sinceEpochSecond, upTo)
    }

    /**
     * Revalidates an expired consumed OHLC dependency against the live source without running
     * price calculations. Returns [OhlcRevalidationResult.ContentChanged] if the candles in the
     * dependency's consumed `[since, upTo]` domain differ from the recorded candle content hash;
     * otherwise returns [OhlcRevalidationResult.Unchanged] with refreshed wall time and freshness
     * deadline. Candles closing after the dependency's `upTo` are not consumed evidence and never
     * cause a content change. The live refresh itself must cover the dependency's consumed
     * domain — a truncated page for another span fails closed to [ContentChanged].
     * Concurrent revalidations of the same range share a single flight.
     */
    suspend fun revalidateDependency(dependency: ConsumedOhlcDependency): OhlcRevalidationResult {
        val normalizedPair = dependency.pair.trim().uppercase()
        val seriesKey = SeriesKey(normalizedPair, dependency.intervalMinutes)
        val originalSince = dependency.sinceEpochSecond
        val consumedUpTo = dependency.upToEpochSecond
        val durationSeconds = dependency.intervalMinutes * 60L
        val flightKey = FlightKey(normalizedPair, dependency.intervalMinutes.toLong(), originalSince)

        // 1. Check if the dependency's own range was already refreshed and is fresh. Only an
        // exact-range proof validates this window: a range response carries at most the candles
        // from its own `since`, so a fresher cross-range proof cannot prove this window's stored
        // candles are current — comparing them would mistake stale union content for unchanged
        // evidence whenever the cross-range response did not cover this window.
        val nowEpochSecond = nowProvider().epochSecond
        val exactCovered = (
            memoryCovered(seriesKey, originalSince, consumedUpTo)
                ?: loadFromPersistent(seriesKey, originalSince, consumedUpTo)
            )?.takeIf { it.fetch.requestSinceEpochSecond == originalSince }
        if (exactCovered != null && !isExpired(exactCovered, dependency.intervalMinutes)) {
            val currentHash = consumedCandleContentHash(
                exactCovered.candles,
                dependency.intervalMinutes,
                originalSince,
                consumedUpTo,
            )
            if (currentHash != dependency.candleContentHash) {
                return OhlcRevalidationResult.ContentChanged
            }
            // Empty negative evidence is never validated cheaply: absence from stored
            // history cannot prove no backfill arrived, so an expired empty dependency
            // falls through to the live revalidation below on its short cadence instead
            // of short-circuiting here for as long as the covering record reads fresh.
            if (currentHash != "empty" || dependency.isFresh(nowEpochSecond)) {
                val updated = dependency.copy(
                    fetchedAtEpochSecond = exactCovered.fetch.fetchWallEpochSecond,
                    freshnessDeadlineEpochSecond = exactCovered.fetch.fetchWallEpochSecond +
                        refreshPolicy.freshnessSeconds(
                            consumedCandles(
                                exactCovered.candles,
                                dependency.intervalMinutes,
                                originalSince,
                                consumedUpTo,
                            ),
                            exactCovered.fetch.fetchWallEpochSecond,
                            dependency.intervalMinutes,
                        ),
                    candleContentHash = currentHash,
                )
                return OhlcRevalidationResult.Unchanged(updated)
            }
        }

        // 2. Obey failed-revalidation retry pacing without retrying Kraken. The series
        // outage backoff gates every range briefly after any series failure; a range's own
        // record pacing gates only retries of that same range. A paced cross-range proof
        // never attempted this range, so its pacing must not validate this window.
        if (nowEpochSecond < (seriesRetryNotBefore[seriesKey] ?: 0L)) {
            return OhlcRevalidationResult.Unchanged(dependency)
        }
        if (exactCovered != null && nowEpochSecond < exactCovered.fetch.retryNotBeforeEpochSecond) {
            return OhlcRevalidationResult.Unchanged(dependency)
        }

        // 3. Single-flight the live refresh.
        val (flight, created) = startOrJoinFlight(flightKey)
        if (created) {
            try {
                val fetchWallEpochSecond = nowProvider().epochSecond
                val fetched = krakenService.getOHLC(
                    pair = normalizedPair,
                    interval = dependency.intervalMinutes,
                    since = originalSince,
                )
                val mayBeTruncated = fetched.size >= KrakenApiConstants.OHLC_PAGE_SIZE
                val completed = fetched.filter { it.first + durationSeconds < fetchWallEpochSecond }
                val outcome = rememberFetch(
                    seriesKey,
                    originalSince,
                    fetchWallEpochSecond,
                    completed,
                    authoritative = true,
                    mayBeTruncated = mayBeTruncated,
                )
                persistFetch(seriesKey, originalSince, fetchWallEpochSecond, completed, mayBeTruncated)
                flight.complete(outcome)
                // The refresh itself must cover the dependency's consumed domain: a
                // truncated page for another span (or a corrupt/future-dated upTo) fails
                // closed to a replay rather than comparing stale union content. An older
                // surviving proof must never satisfy this check.
                if (outcome.coverage?.contains(originalSince, consumedUpTo) != true) {
                    return OhlcRevalidationResult.ContentChanged
                }
                val candles = candlesFrom(outcome.entry, originalSince)
                val newHash = consumedCandleContentHash(
                    candles,
                    dependency.intervalMinutes,
                    originalSince,
                    consumedUpTo,
                )
                return if (newHash != dependency.candleContentHash) {
                    OhlcRevalidationResult.ContentChanged
                } else {
                    val updated = dependency.copy(
                        fetchedAtEpochSecond = fetchWallEpochSecond,
                        freshnessDeadlineEpochSecond = fetchWallEpochSecond +
                            refreshPolicy.freshnessSeconds(
                                consumedCandles(candles, dependency.intervalMinutes, originalSince, consumedUpTo),
                                fetchWallEpochSecond,
                                dependency.intervalMinutes,
                            ),
                        candleContentHash = newHash,
                    )
                    OhlcRevalidationResult.Unchanged(updated)
                }
            } catch (e: CancellationException) {
                flight.completeExceptionally(e)
                throw e
            } catch (e: Throwable) {
                flight.completeExceptionally(e)
                log.warn(
                    "OHLC revalidation failed; serving stale history for pair {} interval {}: {}",
                    normalizedPair,
                    dependency.intervalMinutes,
                    e.message,
                )
                seriesRetryNotBefore[seriesKey] = nowProvider().epochSecond + SERIES_OUTAGE_BACKOFF_SECONDS
                if (exactCovered != null) {
                    paceFailedRevalidation(exactCovered, dependency.intervalMinutes, originalSince, consumedUpTo)
                }
                return OhlcRevalidationResult.Unchanged(dependency)
            } finally {
                inFlight.remove(flightKey, flight)
            }
        }

        // 4. Joiner awaits the shared flight.
        try {
            val outcome = flight.await()
            val candles = candlesFrom(outcome.entry, originalSince)
            val newHash = consumedCandleContentHash(
                candles,
                dependency.intervalMinutes,
                originalSince,
                consumedUpTo,
            )
            // The shared refresh itself must cover this joiner's window: a truncated
            // page for another span (or a corrupt/future-dated upTo) fails closed to
            // a replay rather than validating stale union content.
            if (outcome.coverage?.contains(originalSince, consumedUpTo) != true) {
                return OhlcRevalidationResult.ContentChanged
            }
            val wall = outcome.fetchWallEpochSecond
            return if (newHash != dependency.candleContentHash) {
                OhlcRevalidationResult.ContentChanged
            } else {
                val updated = dependency.copy(
                    fetchedAtEpochSecond = wall,
                    freshnessDeadlineEpochSecond = wall +
                        refreshPolicy.freshnessSeconds(
                            consumedCandles(candles, dependency.intervalMinutes, originalSince, consumedUpTo),
                            wall,
                            dependency.intervalMinutes,
                        ),
                    candleContentHash = newHash,
                )
                OhlcRevalidationResult.Unchanged(updated)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return OhlcRevalidationResult.Unchanged(dependency)
        }
    }

    private fun reportDependency(
        seriesKey: SeriesKey,
        covered: CoveredSeries,
        sinceEpochSecond: Long,
        upTo: Instant,
        intervalMinutes: Int,
        onDependencyResolved: ((ConsumedOhlcDependency) -> Unit)?,
    ) {
        if (onDependencyResolved == null) return
        val candles = covered.candles
        val fetch = covered.fetch
        val consumed = consumedCandles(candles, intervalMinutes, sinceEpochSecond, upTo.epochSecond)
        val freshness = refreshPolicy.freshnessSeconds(consumed, fetch.fetchWallEpochSecond, intervalMinutes)
        val dependency = ConsumedOhlcDependency(
            pair = seriesKey.pair,
            intervalMinutes = intervalMinutes,
            sinceEpochSecond = sinceEpochSecond,
            upToEpochSecond = upTo.epochSecond,
            fetchedAtEpochSecond = fetch.fetchWallEpochSecond,
            freshnessDeadlineEpochSecond = fetch.fetchWallEpochSecond + freshness,
            candleContentHash = consumedCandleContentHash(
                candles,
                intervalMinutes,
                sinceEpochSecond,
                upTo.epochSecond,
            ),
        )
        onDependencyResolved(dependency)
    }

    private fun paceFailedRevalidation(
        covered: CoveredSeries,
        intervalMinutes: Int,
        sinceEpochSecond: Long,
        upToEpochSecond: Long,
    ) {
        // Retry no later than either cadence requires: the failed window's consumed
        // TTL discovers backfills into empty windows on the short empty-result cadence
        // even when the covering record also carries old future candles, while the
        // union TTL preserves the pre-existing outage backoff for non-empty windows
        // (a historical range must recover hourly after a transient outage, not sit
        // blind for a week). For non-empty windows the union TTL is always the shorter
        // side (superset ⇒ newer-or-equal newest close), so only empty windows change.
        val consumedTtl = refreshPolicy.freshnessSeconds(
            consumedCandles(covered.candles, intervalMinutes, sinceEpochSecond, upToEpochSecond),
            covered.fetch.fetchWallEpochSecond,
            intervalMinutes,
        )
        val unionTtl = refreshPolicy.freshnessSeconds(
            covered.candles,
            covered.fetch.fetchWallEpochSecond,
            intervalMinutes,
        )
        covered.fetch.retryNotBeforeEpochSecond = nowProvider().epochSecond + minOf(consumedTtl, unionTtl)
    }

    private fun isExpired(covered: CoveredSeries, intervalMinutes: Int): Boolean = refreshPolicy.isExpired(
        candles = covered.candles,
        fetchedAtEpochSecond = covered.fetch.fetchWallEpochSecond,
        intervalMinutes = intervalMinutes,
        nowEpochSecond = nowProvider().epochSecond,
    )

    private fun memoryCovered(seriesKey: SeriesKey, sinceEpochSecond: Long, upToEpochSecond: Long): CoveredSeries? {
        val entry = store[seriesKey] ?: return null
        val fetch = entry.coveringFetch(sinceEpochSecond, upToEpochSecond) ?: return null
        return CoveredSeries(fetch, candlesFrom(entry, sinceEpochSecond))
    }

    private fun candlesFrom(entry: SeriesEntry, sinceEpochSecond: Long): List<Pair<Long, BigDecimal>> =
        entry.candles.tailMap(sinceEpochSecond, true)
            .entries.asSequence()
            .map { it.key to it.value }
            .toList()

    private fun serveFromMemory(
        seriesKey: SeriesKey,
        sinceEpochSecond: Long,
        upToEpochSecond: Long,
    ): List<Pair<Long, BigDecimal>>? {
        val entry = store[seriesKey] ?: return null
        if (entry.coveringFetch(sinceEpochSecond, upToEpochSecond) == null) {
            return null
        }
        return candlesFrom(entry, sinceEpochSecond)
    }

    private suspend fun loadFromPersistent(
        seriesKey: SeriesKey,
        sinceEpochSecond: Long,
        upToEpochSecond: Long,
    ): CoveredSeries? {
        val repository = persistentRepository ?: return null
        val stored = try {
            repository.loadCovered(
                pair = seriesKey.pair,
                intervalMinutes = seriesKey.intervalMinutes,
                sinceEpochSecond = sinceEpochSecond,
                upToEpochSecond = upToEpochSecond,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Persistence is an optimization boundary. A read failure should fall back to the
            // live OHLC source; an unavailable source still fails closed in the resolver.
            log.warn(
                "Unable to read persisted OHLC cache for pair {} interval {}: {}",
                seriesKey.pair,
                seriesKey.intervalMinutes,
                e.message,
            )
            null
        } ?: return null

        rememberFetch(
            seriesKey = seriesKey,
            sinceEpochSecond = stored.requestSinceEpochSecond,
            fetchWallEpochSecond = stored.fetchedAtEpochSecond,
            candles = stored.candles,
            authoritative = false,
            // A durable restore replays no fresh provider evidence, so replacement never
            // runs and the raw-page shape is irrelevant. The durable span is replayed
            // verbatim so memory agrees with the database exactly.
            mayBeTruncated = false,
            restoredCoverage = OhlcCoverage(
                stored.coverageFromEpochSecond,
                stored.coverageUntilEpochSecond,
            ),
        )
        // loadCovered only returns a fetch that provably covers the requested window.
        return memoryCovered(seriesKey, sinceEpochSecond, upToEpochSecond)
    }

    /**
     * Records one fetch and its completed candles. A live [authoritative] response replaces
     * the authoritative contents of its fetched domain (correcting, backfilling, and deleting);
     * a durable restore only merges, since it replays no fresh provider evidence. The
     * [mayBeTruncated] page-shape flag is measured on the RAW provider response (before
     * in-progress-candle filtering) at the fetch site and is the single source of truth
     * for whether the response may be page-limited.
     *
     * The recorded proof claims only [authoritativeOhlcCoverage] — the span the response
     * actually proved — or nothing at all for a truncated page with zero completed rows.
     * A restore replays the durable span ([restoredCoverage]) instead of recomputing it,
     * so memory agrees with the database exactly. Returns the outcome for the flight.
     */
    private fun rememberFetch(
        seriesKey: SeriesKey,
        sinceEpochSecond: Long,
        fetchWallEpochSecond: Long,
        candles: List<Pair<Long, BigDecimal>>,
        authoritative: Boolean,
        mayBeTruncated: Boolean,
        restoredCoverage: OhlcCoverage? = null,
    ): FetchOutcome {
        val coverage = restoredCoverage ?: authoritativeOhlcCoverage(
            requestSinceEpochSecond = sinceEpochSecond,
            fetchWallEpochSecond = fetchWallEpochSecond,
            intervalMinutes = seriesKey.intervalMinutes,
            completedCandles = candles,
            mayBeTruncated = mayBeTruncated,
        )
        val entry = store.compute(seriesKey) { _, existing -> existing ?: SeriesEntry() }!!
        synchronized(entry.fetches) {
            if (authoritative) {
                replaceAbsentInDomain(
                    entry,
                    durationSeconds = seriesKey.intervalMinutes * 60L,
                    sinceEpochSecond = sinceEpochSecond,
                    fetchWallEpochSecond = fetchWallEpochSecond,
                    candles = candles,
                    mayBeTruncated = mayBeTruncated,
                )
            }
            candles.forEach { (candleStart, close) ->
                // Newer evidence wins: an out-of-order older fetch must not overwrite a close
                // witnessed later, mirroring the persisted fetchedAt guard.
                if ((entry.candleWalls[candleStart] ?: Long.MIN_VALUE) <= fetchWallEpochSecond) {
                    entry.candles[candleStart] = close
                    entry.candleWalls[candleStart] = fetchWallEpochSecond
                }
            }
            if (coverage != null) {
                // Same (request since, wall) collapses to the newest response, mirroring
                // the durable upsert; older same-range proofs are dropped only when the
                // new span fully contains them. Disjoint or broader older spans coexist,
                // so a narrow truncated refresh never erases a proof it failed to
                // reproduce — and an expired record never shadows its own revalidation.
                entry.fetches.removeAll {
                    it.requestSinceEpochSecond == sinceEpochSecond &&
                        (
                            it.fetchWallEpochSecond == fetchWallEpochSecond ||
                                (
                                    it.fetchWallEpochSecond < fetchWallEpochSecond &&
                                        coverage.contains(it.coverage)
                                    )
                            )
                }
                entry.fetches += FetchRecord(sinceEpochSecond, coverage, fetchWallEpochSecond)
                // Bound same-range coexistence: repeated revalidations usually subsume, but
                // shifting truncated spans would otherwise accumulate one proof per refresh.
                // The oldest span goes first, preferring fresh evidence like every other
                // newer-wins rule here; eviction fails closed to a live refetch.
                val siblings = entry.fetches.filter { it.requestSinceEpochSecond == sinceEpochSecond }
                if (siblings.size > MAX_FETCH_PROOFS_PER_RANGE) {
                    entry.fetches.remove(siblings.minBy { it.fetchWallEpochSecond })
                }
            }
        }
        return FetchOutcome(entry, coverage, fetchWallEpochSecond)
    }

    /**
     * Deletes stored candles a fresh authoritative response proves removed: completed candles
     * inside the fetched domain that the response omits. Must run under the entry lock before
     * the response upserts, and must mirror the persisted replacement in
     * `SqliteHistoricalOhlcRepositoryImpl.saveFetch` so both views converge.
     *
     * Domain contract: a short RAW provider page is complete for `[since, wall)`; a RAW
     * page at the endpoint limit may be truncated (Kraken serves oldest-first with a `last`
     * cursor this cache does not follow), so it is authoritative only for the completed
     * span it actually returned. Truncation is a property of the RAW page measured before
     * in-progress-candle filtering — never of the filtered list — and arrives here via
     * [mayBeTruncated]. Either way only completed candles (`start + duration < wall`, the
     * same predicate that filtered the response) can be judged absent, and candles
     * witnessed later than this fetch are always kept. A truncated page with zero
     * returned completed candles proves nothing absent.
     */
    private fun replaceAbsentInDomain(
        entry: SeriesEntry,
        durationSeconds: Long,
        sinceEpochSecond: Long,
        fetchWallEpochSecond: Long,
        candles: List<Pair<Long, BigDecimal>>,
        mayBeTruncated: Boolean,
    ) {
        val distinct = candles.distinctBy { it.first }
        val present = distinct.mapTo(mutableSetOf()) { it.first }
        val domainFrom: Long
        val domainToExclusive: Long
        if (mayBeTruncated) {
            val firstReturned = distinct.minOfOrNull { it.first } ?: return
            domainFrom = maxOf(sinceEpochSecond, firstReturned)
            domainToExclusive = distinct.maxOf { it.first } + 1
        } else {
            domainFrom = sinceEpochSecond
            domainToExclusive = Long.MAX_VALUE
        }
        val effectiveToExclusive = minOf(domainToExclusive, fetchWallEpochSecond - durationSeconds)
        if (domainFrom >= effectiveToExclusive) return
        val stale = entry.candles.subMap(domainFrom, true, effectiveToExclusive, false).keys
            .filter { it !in present && (entry.candleWalls[it] ?: Long.MIN_VALUE) <= fetchWallEpochSecond }
            .toList()
        stale.forEach {
            entry.candles.remove(it)
            entry.candleWalls.remove(it)
        }
    }

    private suspend fun persistFetch(
        seriesKey: SeriesKey,
        sinceEpochSecond: Long,
        fetchWallEpochSecond: Long,
        candles: List<Pair<Long, BigDecimal>>,
        mayBeTruncated: Boolean,
    ) {
        val repository = persistentRepository ?: return
        try {
            repository.saveFetch(
                pair = seriesKey.pair,
                intervalMinutes = seriesKey.intervalMinutes,
                sinceEpochSecond = sinceEpochSecond,
                fetchedAtEpochSecond = fetchWallEpochSecond,
                candles = candles,
                mayBeTruncated = mayBeTruncated,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Keep the current request successful: the network response is still valid evidence,
            // and a later request can retry the durable optimization.
            log.warn(
                "Unable to persist OHLC cache for pair {} interval {}: {}",
                seriesKey.pair,
                seriesKey.intervalMinutes,
                e.message,
            )
        }
    }

    private fun startOrJoinFlight(flightKey: FlightKey): Pair<CompletableDeferred<FetchOutcome>, Boolean> {
        var created = false
        val deferred = inFlight.compute(flightKey) { _, existing ->
            if (existing == null) {
                created = true
                CompletableDeferred()
            } else {
                existing
            }
        }!!
        return deferred to created
    }

    companion object {
        /**
         * Series-wide outage backoff after any failed dependency revalidation. Recovery in an
         * unattempted range is detected at most this late; a range's own record pacing still
         * backs off retries of that same range for longer.
         */
        private const val SERIES_OUTAGE_BACKOFF_SECONDS = 60L

        /**
         * Fail-closed bound on coexisting proofs per request range: disjoint truncated
         * spans must not erase each other, but shifting spans must not accumulate one
         * proof per refresh either. Distinct ranges keep their own proofs exactly as
         * before; evicting a proof only costs a future live refetch, never a stale hit.
         */
        private const val MAX_FETCH_PROOFS_PER_RANGE = 4

        /**
         * Canonical consumed-candle domain: completed candles starting at or after
         * [sinceEpochSecond] and closing at or before [upToEpochSecond], deduplicated by
         * start and sorted. Both dependency content identity ([consumedCandleContentHash])
         * and dependency freshness cadence operate on exactly this set, so candles outside
         * the consumed window influence neither invalidation nor refresh frequency.
         */
        fun consumedCandles(
            candles: Collection<Pair<Long, BigDecimal>>,
            intervalMinutes: Int,
            sinceEpochSecond: Long,
            upToEpochSecond: Long,
        ): List<Pair<Long, BigDecimal>> {
            val durationSeconds = intervalMinutes * 60L
            return candles
                .distinctBy { it.first }
                .filter { (start, _) ->
                    start >= sinceEpochSecond && start + durationSeconds <= upToEpochSecond
                }
                .sortedBy { it.first }
        }

        /**
         * Canonical content identity for one consumed OHLC evidence domain.
         *
         * The hash covers a conservative superset of the completed candles the price
         * resolver could have consumed for the requesting valuation: every candle in
         * [consumedCandles], hashed in start order with normalized closes — including
         * older in-window candles the resolver's recency filter would not select.
         * Over-covering only causes extra replays, never stale hits. Dependency
         * identity (pair, interval, window) is carried by [ConsumedOhlcDependency]
         * itself, so the hash is compared only within one identical window.
         *
         * A normal future candle append (close after [upToEpochSecond]) leaves the hash
         * unchanged. A correction to a consumed candle, or a backfill inside the window
         * (including one curing previously empty negative evidence, hashed as `"empty"`),
         * changes it. This is the single helper for dependency content identity: report
         * and revalidation paths must both use it.
         */
        fun consumedCandleContentHash(
            candles: Collection<Pair<Long, BigDecimal>>,
            intervalMinutes: Int,
            sinceEpochSecond: Long,
            upToEpochSecond: Long,
        ): String {
            val consumed = consumedCandles(candles, intervalMinutes, sinceEpochSecond, upToEpochSecond)
            if (consumed.isEmpty()) return "empty"
            val md = MessageDigest.getInstance("SHA-256")
            consumed.forEach { (start, close) ->
                md.update("$start:${close.stripTrailingZeros().toPlainString()};".toByteArray(Charsets.UTF_8))
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
