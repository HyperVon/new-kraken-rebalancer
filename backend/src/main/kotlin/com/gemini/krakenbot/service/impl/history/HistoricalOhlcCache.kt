package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.repository.HistoricalOhlcRepository
import com.gemini.krakenbot.service.KrakenService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap

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
 * restarts. Completed candles are normally stable evidence, while a later fresh response may
 * replace a stored value if the provider corrects or backfills a historical candle.
 *
 * Cache contract:
 * - Key granularity is pair / interval; fetched ranges are recorded per fetch.
 * - Only candles that closed strictly before the fetch wall time are stored. Kraken's trailing
 *   in-progress candle (close at or after the fetch) is dropped; it must never become
 *   completed-candle evidence for history.
 * - A request is served from memory only when a SINGLE stored fetch provably contains every
 *   completed candle a fresh fetch could contribute for that window: that fetch must have
 *   started no later than this request's `since` and its wall time must be at or after `upTo`.
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
        val sinceEpochSecond: Long,
        val fetchWallEpochSecond: Long,
        /** Earliest epoch second at which a failed revalidation of this proof may retry the
         *  live source. Pacing only; never participates in coverage. */
        @JvmField var retryNotBeforeEpochSecond: Long = 0L,
    )

    private class SeriesEntry {
        val candles = ConcurrentSkipListMap<Long, BigDecimal>()
        val fetches = mutableListOf<FetchRecord>()

        /** Newest single-fetch proof that this request needs no fresh network call. Walls are
         *  inserted monotonically, so the last matching record is always the newest. */
        fun coveringFetch(sinceEpochSecond: Long, upToEpochSecond: Long): FetchRecord? = synchronized(fetches) {
            var newest: FetchRecord? = null
            for (fetch in fetches) {
                if (fetch.sinceEpochSecond <= sinceEpochSecond && fetch.fetchWallEpochSecond >= upToEpochSecond) {
                    newest = fetch
                }
            }
            newest
        }
    }

    private class CoveredSeries(val fetch: FetchRecord, val candles: List<Pair<Long, BigDecimal>>)

    private val store = ConcurrentHashMap<SeriesKey, SeriesEntry>()
    private val inFlight = ConcurrentHashMap<FlightKey, CompletableDeferred<SeriesEntry>>()

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
    ): List<Pair<Long, BigDecimal>> {
        val normalizedPair = pair.trim().uppercase()
        val seriesKey = SeriesKey(normalizedPair, intervalMinutes)
        val durationSeconds = intervalMinutes * 60L

        memoryCovered(seriesKey, sinceEpochSecond, upTo.epochSecond)?.let { covered ->
            if (!isExpired(covered, intervalMinutes)) return covered.candles
            return revalidate(seriesKey, covered, normalizedPair, intervalMinutes, sinceEpochSecond, upTo)
        }
        loadFromPersistent(seriesKey, sinceEpochSecond, upTo.epochSecond)?.let { covered ->
            if (!isExpired(covered, intervalMinutes)) return covered.candles
            return revalidate(seriesKey, covered, normalizedPair, intervalMinutes, sinceEpochSecond, upTo)
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
                val completed = fetched.filter { it.first + durationSeconds < fetchWallEpochSecond }
                val entry = rememberFetch(seriesKey, sinceEpochSecond, fetchWallEpochSecond, completed)
                persistFetch(seriesKey, sinceEpochSecond, fetchWallEpochSecond, completed)
                flight.complete(entry)
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
        serveFromMemory(seriesKey, sinceEpochSecond, upTo.epochSecond)?.let { return it }
        return getOHLC(normalizedPair, intervalMinutes, sinceEpochSecond, upTo)
    }

    /**
     * Revalidates an expired covering fetch against the live source. The refetch reuses the
     * original fetch's `since` so the replacement proof covers at least the same range, and
     * identical concurrent revalidations share one flight. On failure the stored proof keeps
     * its REAL wall time (coverage never extends beyond what was actually fetched) and gains
     * a retry-pacing timestamp one freshness window out: covered windows keep serving stale
     * data without network attempts, while windows beyond the recorded wall are not served
     * stale evidence — they fail closed or fetch live. A joiner whose shared flight failed
     * serves its own covered stale series instead of throwing.
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
        val originalSince = covered.fetch.sinceEpochSecond
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
                val completed = fetched.filter { it.first + durationSeconds < fetchWallEpochSecond }
                val entry = rememberFetch(seriesKey, originalSince, fetchWallEpochSecond, completed)
                persistFetch(seriesKey, originalSince, fetchWallEpochSecond, completed)
                flight.complete(entry)
                return entry.candles.tailMap(sinceEpochSecond, true)
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
                paceFailedRevalidation(covered, intervalMinutes)
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

    private fun paceFailedRevalidation(covered: CoveredSeries, intervalMinutes: Int) {
        covered.fetch.retryNotBeforeEpochSecond = nowProvider().epochSecond +
            refreshPolicy.freshnessSeconds(covered.candles, covered.fetch.fetchWallEpochSecond, intervalMinutes)
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
            sinceEpochSecond = stored.sinceEpochSecond,
            fetchWallEpochSecond = stored.fetchedAtEpochSecond,
            candles = stored.candles,
        )
        // loadCovered only returns a fetch that provably covers the requested window.
        return CoveredSeries(FetchRecord(stored.sinceEpochSecond, stored.fetchedAtEpochSecond), stored.candles)
    }

    private fun rememberFetch(
        seriesKey: SeriesKey,
        sinceEpochSecond: Long,
        fetchWallEpochSecond: Long,
        candles: List<Pair<Long, BigDecimal>>,
    ): SeriesEntry {
        val entry = store.compute(seriesKey) { _, existing -> existing ?: SeriesEntry() }!!
        synchronized(entry.fetches) {
            candles.forEach { (candleStart, close) ->
                // A fresh provider response is allowed to correct a previously cached candle.
                entry.candles[candleStart] = close
            }
            // A replaced proof supersedes older proofs of the same range; keeping only the
            // newest record per distinct `since` bounds the fetch list and stops an expired
            // record from shadowing its own revalidation.
            entry.fetches.removeAll {
                it.sinceEpochSecond == sinceEpochSecond && it.fetchWallEpochSecond < fetchWallEpochSecond
            }
            if (entry.fetches.none {
                    it.sinceEpochSecond == sinceEpochSecond &&
                        it.fetchWallEpochSecond == fetchWallEpochSecond
                }
            ) {
                entry.fetches += FetchRecord(sinceEpochSecond, fetchWallEpochSecond)
            }
        }
        return entry
    }

    private suspend fun persistFetch(
        seriesKey: SeriesKey,
        sinceEpochSecond: Long,
        fetchWallEpochSecond: Long,
        candles: List<Pair<Long, BigDecimal>>,
    ) {
        val repository = persistentRepository ?: return
        try {
            repository.saveFetch(
                pair = seriesKey.pair,
                intervalMinutes = seriesKey.intervalMinutes,
                sinceEpochSecond = sinceEpochSecond,
                fetchedAtEpochSecond = fetchWallEpochSecond,
                candles = candles,
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

    private fun startOrJoinFlight(flightKey: FlightKey): Pair<CompletableDeferred<SeriesEntry>, Boolean> {
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
}
