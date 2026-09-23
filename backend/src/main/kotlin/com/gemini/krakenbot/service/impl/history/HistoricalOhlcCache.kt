package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.model.KrakenApiConstants
import com.gemini.krakenbot.repository.ConsumedOhlcDependency
import com.gemini.krakenbot.repository.HistoricalOhlcRepository
import com.gemini.krakenbot.repository.OhlcCoverage
import com.gemini.krakenbot.repository.OhlcReachabilityDependency
import com.gemini.krakenbot.repository.OhlcReachabilityFrontier
import com.gemini.krakenbot.repository.authoritativeOhlcCoverage
import com.gemini.krakenbot.repository.selectReachabilityFrontier
import com.gemini.krakenbot.service.KrakenService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * Logical owner of a live OHLC request, carried from the top-level entry point
 * (Settings proposal, History comparison, dependency revalidation) through price
 * resolution into the cache so duplicate work is attributable in structured logs.
 */
enum class OhlcCallOwner {
    SETTINGS_PROPOSAL,
    HISTORY_COMPARISON,
    REVALIDATION,
    OTHER,
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
 *   Two different fetches are never combined into such a proof.
 * - A live fetch whose proven span does NOT contain its requested window still paces that
 *   exact (pair, interval, since) request when the valuation instant sits at or before the
 *   fetch wall: while the latest same-since proof is fresh under the refresh policy, further
 *   identical requests are served the proof's stored span (empty for a truncated-empty
 *   marker) instead of refetching live. A refetch right now could not complete any new
 *   in-window candle, so callers observe the same resolution outcome modulo provider
 *   corrections inside one freshness window — the same staleness class as stale serving of
 *   covering proofs. Truncated-empty responses record an explicit empty marker proof so they
 *   pace the same way; markers never satisfy a coverage check. A valuation beyond the wall
 *   is a live tail that may have grown and always refetches.
 * - A separate durable negative-reachability frontier is keyed only by pair / interval. It is
 *   learned only from a nonempty, completed, page-truncated response whose first returned candle
 *   begins after the requested history bound. While fresh, it skips valuation instants earlier
 *   than the first returned candle's close; it never supplies positive coverage or a synthetic
 *   candle. A newer page may move the boundary in either direction, and a contradictory positive
 *   span clears the obsolete frontier. Comparison caches record frontier skips in a separate
 *   selection manifest so expiry or movement invalidates the selected result.
 * - Distinct historical discovery requests that are old enough to be page-truncated join one
 *   per-series flight. A truncated page may be returned to a joiner when the requested valuation
 *   is in that exact page, but it is never persisted as positive coverage for the joiner's range.
 *   Live tails and requests within the provider page limit retain exact-since behavior. Frontier
 *   skip diagnostics are debug-only; one discovery message is throttled per series freshness
 *   window.
 * - A fetching initiator returns exactly its own completed-candle response, so uncached callers
 *   observe uncached semantics; the store only serves other requests under the proof above.
 * - Identical in-flight (pair, interval, since) requests join one flight; failures complete the
 *   flight exceptionally and are never cached. A joiner whose window the shared flight did
 *   not cover serves the flight's proven span like the initiator's insufficient answer
 *   instead of starting its own fetch.
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
         *  only anchors freshness to an older instant (earlier expiry, fail closed).
         *  Empty marker proofs (truncated-empty pacing records) never satisfy a coverage
         *  check, even for degenerate windows. */
        fun coveringFetch(sinceEpochSecond: Long, upToEpochSecond: Long): FetchRecord? = synchronized(fetches) {
            var newest: FetchRecord? = null
            for (fetch in fetches) {
                if (fetch.coverage.untilEpochSecond > fetch.coverage.fromEpochSecond &&
                    fetch.coverage.contains(sinceEpochSecond, upToEpochSecond)
                ) {
                    newest = fetch
                }
            }
            newest
        }

        /** Newest proof (by fetch wall) recorded for one exact request `since`, covering
         *  or not: the insufficient-coverage gate paces refetches off it while fresh. */
        fun latestProofForRequestSince(sinceEpochSecond: Long): FetchRecord? = synchronized(fetches) {
            fetches.filter { it.requestSinceEpochSecond == sinceEpochSecond }
                .maxByOrNull { it.fetchWallEpochSecond }
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
    private val reachabilityFrontiers = ConcurrentHashMap<SeriesKey, OhlcReachabilityFrontier>()
    private val reachabilityLoaded = ConcurrentHashMap.newKeySet<SeriesKey>()
    private val reachabilityLoads = ConcurrentHashMap<SeriesKey, CompletableDeferred<OhlcReachabilityFrontier?>>()
    private val reachabilityInFlight = ConcurrentHashMap<SeriesKey, CompletableDeferred<DiscoveryFlightResult>>()
    private val reachabilityObservationLocks = ConcurrentHashMap<SeriesKey, Mutex>()
    private val reachabilityObservationWalls = ConcurrentHashMap<SeriesKey, Long>()

    /** Positive evidence wins ties: an in-flight capped response at the same wall cannot
     * restore a frontier that a response at that wall already contradicted. */
    private val reachabilityPositiveClearWalls = ConcurrentHashMap<SeriesKey, Long>()
    private val loggedFrontierRetryAfter = ConcurrentHashMap<SeriesKey, Long>()

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
    private data class DiscoveryFlightResult(
        val retryAfterCancellation: Boolean = false,
        val completedCandles: List<Pair<Long, BigDecimal>> = emptyList(),
        val coverage: OhlcCoverage? = null,
        val mayBeTruncated: Boolean = false,
        val fetchWallEpochSecond: Long? = null,
    )

    /**
     * Returns completed candles for [pair]/[intervalMinutes] starting at [sinceEpochSecond],
     * serving stored history when a single recorded fetch provably equals a fresh fetch and is
     * still fresh under the refresh policy, revalidating an expired proof once, pacing an
     * insufficient same-since proof instead of refetching it, and fetching (once per identical
     * concurrent request) otherwise. [upTo] is the valuation instant the caller resolves;
     * candles that closed after it cannot be selected by callers. [callOwner] attributes the
     * request to its top-level entry point for structured duplicate-work diagnostics.
     */
    suspend fun getOHLC(
        pair: String,
        intervalMinutes: Int,
        sinceEpochSecond: Long,
        upTo: Instant,
        onDependencyResolved: ((ConsumedOhlcDependency) -> Unit)? = null,
        onReachabilityResolved: ((OhlcReachabilityDependency) -> Unit)? = null,
        callOwner: OhlcCallOwner = OhlcCallOwner.OTHER,
    ): List<Pair<Long, BigDecimal>> {
        val normalizedPair = pair.trim().uppercase()
        val seriesKey = SeriesKey(normalizedPair, intervalMinutes)
        val durationSeconds = intervalMinutes * 60L

        memoryCovered(seriesKey, sinceEpochSecond, upTo.epochSecond)?.let { covered ->
            if (!isExpired(covered, intervalMinutes)) {
                logCacheOutcome(
                    "MEMORY_HIT",
                    seriesKey,
                    intervalMinutes,
                    sinceEpochSecond,
                    upTo,
                    callOwner,
                    covered.fetch,
                )
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
                logCacheOutcome(
                    "SQLITE_HIT",
                    seriesKey,
                    intervalMinutes,
                    sinceEpochSecond,
                    upTo,
                    callOwner,
                    covered.fetch,
                )
                reportDependency(seriesKey, covered, sinceEpochSecond, upTo, intervalMinutes, onDependencyResolved)
                return covered.candles
            }
            val revalidated = revalidate(seriesKey, covered, normalizedPair, intervalMinutes, sinceEpochSecond, upTo)
            (memoryCovered(seriesKey, sinceEpochSecond, upTo.epochSecond) ?: covered).let {
                reportDependency(seriesKey, it, sinceEpochSecond, upTo, intervalMinutes, onDependencyResolved)
            }
            return revalidated
        }
        val existingFrontier = loadReachabilityFrontier(seriesKey)
        if (existingFrontier?.isFresh(nowProvider().epochSecond) == true && existingFrontier.blocks(upTo.epochSecond)) {
            logReachabilitySkip(existingFrontier, upTo.epochSecond)
            reportReachabilityDependency(existingFrontier, onReachabilityResolved)
            return emptyList()
        }
        // No proof covers this window: a fresh same-since proof still paces the exact
        // request instead of refetching an unreachable range on every lookup.
        memoryInsufficient(seriesKey, sinceEpochSecond, upTo.epochSecond, intervalMinutes)?.let { gated ->
            logCacheOutcome(
                "INSUFFICIENT_REUSE",
                seriesKey,
                intervalMinutes,
                sinceEpochSecond,
                upTo,
                callOwner,
                gated.fetch,
            )
            reportDependency(seriesKey, gated, sinceEpochSecond, upTo, intervalMinutes, onDependencyResolved)
            return gated.candles
        }
        loadInsufficientFromPersistent(seriesKey, sinceEpochSecond, upTo.epochSecond, intervalMinutes)?.let { gated ->
            logCacheOutcome(
                "INSUFFICIENT_REUSE",
                seriesKey,
                intervalMinutes,
                sinceEpochSecond,
                upTo,
                callOwner,
                gated.fetch,
            )
            reportDependency(seriesKey, gated, sinceEpochSecond, upTo, intervalMinutes, onDependencyResolved)
            return gated.candles
        }

        if (shouldDiscoverHistoricalReachability(sinceEpochSecond, upTo.epochSecond, intervalMinutes)) {
            val (discovery, created) = startOrJoinReachabilityFlight(seriesKey)
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
                    recordReachabilityObservation(
                        seriesKey = seriesKey,
                        sinceEpochSecond = sinceEpochSecond,
                        upToEpochSecond = upTo.epochSecond,
                        fetchWallEpochSecond = fetchWallEpochSecond,
                        completed = completed,
                        mayBeTruncated = mayBeTruncated,
                    )
                    discovery.complete(
                        DiscoveryFlightResult(
                            completedCandles = completed,
                            coverage = outcome.coverage,
                            mayBeTruncated = mayBeTruncated,
                            fetchWallEpochSecond = fetchWallEpochSecond,
                        ),
                    )
                    val frontier = loadReachabilityFrontier(seriesKey)
                    if (frontier?.isFresh(fetchWallEpochSecond) == true && frontier.blocks(upTo.epochSecond)) {
                        logReachabilitySkip(frontier, upTo.epochSecond)
                        reportReachabilityDependency(frontier, onReachabilityResolved)
                        return emptyList()
                    }
                    val coverage = outcome.coverage ?: OhlcCoverage(sinceEpochSecond, sinceEpochSecond)
                    val record = FetchRecord(sinceEpochSecond, coverage, fetchWallEpochSecond)
                    reportDependency(
                        seriesKey,
                        CoveredSeries(record, completed),
                        sinceEpochSecond,
                        upTo,
                        intervalMinutes,
                        onDependencyResolved,
                    )
                    return completed
                } catch (e: CancellationException) {
                    reachabilityInFlight.remove(seriesKey, discovery)
                    discovery.complete(DiscoveryFlightResult(retryAfterCancellation = true))
                    throw e
                } catch (e: Throwable) {
                    discovery.completeExceptionally(e)
                    throw e
                } finally {
                    reachabilityInFlight.remove(seriesKey, discovery)
                }
            }

            val shared = discovery.await()
            if (shared.retryAfterCancellation) {
                return getOHLC(
                    normalizedPair,
                    intervalMinutes,
                    sinceEpochSecond,
                    upTo,
                    onDependencyResolved,
                    onReachabilityResolved,
                    callOwner,
                )
            }
            val frontier = loadReachabilityFrontier(seriesKey)
            if (frontier?.isFresh(nowProvider().epochSecond) == true && frontier.blocks(upTo.epochSecond)) {
                logReachabilitySkip(frontier, upTo.epochSecond)
                reportReachabilityDependency(frontier, onReachabilityResolved)
                return emptyList()
            }
            // The shared truncated page is not positive coverage for this caller's older
            // request bound. Recheck the exact persisted proofs below, then fetch only if its
            // own range still lacks positive or same-since pacing evidence.
            memoryCovered(seriesKey, sinceEpochSecond, upTo.epochSecond)?.let { covered ->
                reportDependency(seriesKey, covered, sinceEpochSecond, upTo, intervalMinutes, onDependencyResolved)
                return covered.candles
            }
            memoryInsufficient(seriesKey, sinceEpochSecond, upTo.epochSecond, intervalMinutes)?.let { gated ->
                reportDependency(seriesKey, gated, sinceEpochSecond, upTo, intervalMinutes, onDependencyResolved)
                return gated.candles
            }
            loadInsufficientFromPersistent(
                seriesKey,
                sinceEpochSecond,
                upTo.epochSecond,
                intervalMinutes,
            )?.let { gated ->
                reportDependency(seriesKey, gated, sinceEpochSecond, upTo, intervalMinutes, onDependencyResolved)
                return gated.candles
            }
            reusableDiscoveryPage(shared, sinceEpochSecond, upTo.epochSecond, intervalMinutes)?.let { page ->
                val fetchWallEpochSecond = checkNotNull(shared.fetchWallEpochSecond)
                val coverage = checkNotNull(shared.coverage)
                reportDependency(
                    seriesKey,
                    CoveredSeries(
                        FetchRecord(sinceEpochSecond, coverage, fetchWallEpochSecond),
                        page,
                    ),
                    sinceEpochSecond,
                    upTo,
                    intervalMinutes,
                    onDependencyResolved,
                )
                return page
            }
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
                recordReachabilityObservation(
                    seriesKey,
                    sinceEpochSecond,
                    upTo.epochSecond,
                    fetchWallEpochSecond,
                    completed,
                    mayBeTruncated,
                )
                val frontier = loadReachabilityFrontier(seriesKey)
                if (frontier?.isFresh(fetchWallEpochSecond) == true && frontier.blocks(upTo.epochSecond)) {
                    logReachabilitySkip(frontier, upTo.epochSecond)
                    reportReachabilityDependency(frontier, onReachabilityResolved)
                    flight.complete(outcome)
                    return emptyList()
                }
                val outcomeCoverage = outcome.coverage
                    ?: OhlcCoverage(sinceEpochSecond, sinceEpochSecond)
                val outcomeRecord = FetchRecord(sinceEpochSecond, outcomeCoverage, fetchWallEpochSecond)
                val sufficient = outcomeCoverage.contains(sinceEpochSecond, upTo.epochSecond)
                logLiveFetch(
                    seriesKey,
                    intervalMinutes,
                    sinceEpochSecond,
                    upTo,
                    callOwner,
                    rawRowCount = fetched.size,
                    completedRowCount = completed.size,
                    mayBeTruncated = mayBeTruncated,
                    coverage = outcomeCoverage,
                    fetchWallEpochSecond = fetchWallEpochSecond,
                    sufficient = sufficient,
                )
                // Transient record for dependency reporting only (never stored): a
                // truncated-empty response proved nothing, so it carries an empty span.
                val covered = CoveredSeries(outcomeRecord, completed)
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
        val sharedFrontier = loadReachabilityFrontier(seriesKey)
        if (sharedFrontier?.isFresh(nowProvider().epochSecond) == true && sharedFrontier.blocks(upTo.epochSecond)) {
            logReachabilitySkip(sharedFrontier, upTo.epochSecond)
            reportReachabilityDependency(sharedFrontier, onReachabilityResolved)
            return emptyList()
        }
        serveFromMemory(seriesKey, sinceEpochSecond, upTo.epochSecond)?.let {
            memoryCovered(seriesKey, sinceEpochSecond, upTo.epochSecond)?.let { covered ->
                reportDependency(seriesKey, covered, sinceEpochSecond, upTo, intervalMinutes, onDependencyResolved)
            }
            return it
        }
        // The shared flight proved nothing covering this window: serve its proven span
        // like the initiator's insufficient answer instead of refetching live, so
        // concurrent identical requests share one network call.
        memoryInsufficient(seriesKey, sinceEpochSecond, upTo.epochSecond, intervalMinutes)?.let { gated ->
            reportDependency(seriesKey, gated, sinceEpochSecond, upTo, intervalMinutes, onDependencyResolved)
            return gated.candles
        }
        return getOHLC(
            normalizedPair,
            intervalMinutes,
            sinceEpochSecond,
            upTo,
            onDependencyResolved,
            onReachabilityResolved,
            callOwner,
        )
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
                recordReachabilityObservation(
                    seriesKey,
                    originalSince,
                    upTo.epochSecond,
                    fetchWallEpochSecond,
                    completed,
                    mayBeTruncated,
                )
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
        // The shared refresh proved nothing covering this joiner's window: pace its own
        // proof like a failed attempt and serve the CURRENT union instead of refetching.
        // The union is re-read (not the pre-refresh snapshot) so candles the refresh
        // just authoritatively deleted or corrected are not served back.
        paceFailedRevalidation(covered, intervalMinutes, sinceEpochSecond, upTo.epochSecond)
        val entry = store[seriesKey]
        return if (entry != null) candlesFrom(entry, sinceEpochSecond) else covered.candles
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
                recordReachabilityObservation(
                    seriesKey,
                    originalSince,
                    consumedUpTo,
                    fetchWallEpochSecond,
                    completed,
                    mayBeTruncated,
                )
                flight.complete(outcome)
                if (log.isDebugEnabled) {
                    val span = outcome.coverage?.let { "[${it.fromEpochSecond}, ${it.untilEpochSecond})" } ?: "none"
                    log.debug(
                        "OHLC cache REVALIDATION_REFRESH; pair={} interval={} since={} upTo={} owner={} " +
                            "rawRows={} completedRows={} truncated={} coverage={} wall={}",
                        normalizedPair,
                        dependency.intervalMinutes,
                        originalSince,
                        consumedUpTo,
                        OhlcCallOwner.REVALIDATION,
                        fetched.size,
                        completed.size,
                        mayBeTruncated,
                        span,
                        fetchWallEpochSecond,
                    )
                }
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

    private fun reportReachabilityDependency(
        frontier: OhlcReachabilityFrontier,
        callback: ((OhlcReachabilityDependency) -> Unit)?,
    ) {
        callback?.invoke(
            OhlcReachabilityDependency(
                pair = frontier.pair,
                intervalMinutes = frontier.intervalMinutes,
                earliestReachableEpochSecond = frontier.earliestReachableEpochSecond,
            ),
        )
    }

    private fun logReachabilitySkip(frontier: OhlcReachabilityFrontier, upToEpochSecond: Long) {
        if (!log.isDebugEnabled) return
        log.debug(
            "OHLC frontier skip; pair={} interval={} upTo={} earliestReachable={} wall={} retryAfter={}",
            frontier.pair,
            frontier.intervalMinutes,
            upToEpochSecond,
            frontier.earliestReachableEpochSecond,
            frontier.observedAtEpochSecond,
            frontier.retryAfterEpochSecond,
        )
    }

    /**
     * True only for a historical request old enough that the configured Kraken page limit
     * could truncate it. Valuations after the current fetch wall remain live-tail requests and
     * never join or create this series-wide discovery flight.
     */
    private fun shouldDiscoverHistoricalReachability(
        sinceEpochSecond: Long,
        upToEpochSecond: Long,
        intervalMinutes: Int,
    ): Boolean {
        val now = nowProvider().epochSecond
        if (upToEpochSecond > now || sinceEpochSecond >= upToEpochSecond) return false
        val pageSpanSeconds = KrakenApiConstants.OHLC_PAGE_SIZE * intervalMinutes * 60L
        return sinceEpochSecond <= now - pageSpanSeconds
    }

    /**
     * A truncated Kraken response is the same latest page for every request whose `since`
     * precedes its first returned candle. A discovery joiner may consume that exact response
     * when its valuation is in the page, without turning the response into a positive coverage
     * proof for the joiner's wider requested range.
     */
    private fun reusableDiscoveryPage(
        discovery: DiscoveryFlightResult,
        sinceEpochSecond: Long,
        upToEpochSecond: Long,
        intervalMinutes: Int,
    ): List<Pair<Long, BigDecimal>>? {
        if (!discovery.mayBeTruncated || sinceEpochSecond >= upToEpochSecond) return null
        val fetchWallEpochSecond = discovery.fetchWallEpochSecond ?: return null
        if (upToEpochSecond > fetchWallEpochSecond) return null
        val candles = discovery.completedCandles
        val firstReturned = candles.minOfOrNull { it.first } ?: return null
        if (sinceEpochSecond >= firstReturned) return null
        val pageSpanSeconds = KrakenApiConstants.OHLC_PAGE_SIZE * intervalMinutes * 60L
        if (sinceEpochSecond > fetchWallEpochSecond - pageSpanSeconds) return null
        val hasValuationCandle = candles.any { (start, _) ->
            start >= sinceEpochSecond && start + intervalMinutes * 60L <= upToEpochSecond
        }
        return candles.takeIf { hasValuationCandle && discovery.coverage != null }
    }

    /**
     * Records negative provider reachability only from a completed, nonempty truncated page
     * whose first proven candle starts after the old requested bound. A short page, empty page,
     * in-progress-only page, future valuation, or failed request cannot create a frontier.
     */
    private suspend fun recordReachabilityObservation(
        seriesKey: SeriesKey,
        sinceEpochSecond: Long,
        upToEpochSecond: Long,
        fetchWallEpochSecond: Long,
        completed: List<Pair<Long, BigDecimal>>,
        mayBeTruncated: Boolean,
    ) {
        if (sinceEpochSecond >= upToEpochSecond || upToEpochSecond > fetchWallEpochSecond) return
        val distinct = completed.distinctBy { it.first }
        val firstReturned = distinct.minOfOrNull { it.first } ?: return
        observationLock(seriesKey).withLock {
            val existing = loadReachabilityFrontierLocked(seriesKey)
            val positiveClearWall = reachabilityPositiveClearWalls[seriesKey] ?: Long.MIN_VALUE
            if (fetchWallEpochSecond <= positiveClearWall) return@withLock
            val latestObservation = reachabilityObservationWalls[seriesKey]
                ?: existing?.observedAtEpochSecond
                ?: Long.MIN_VALUE
            if (fetchWallEpochSecond < latestObservation) return@withLock
            // Retain a high-water mark even after a positive response clears the active
            // frontier. A delayed older fetch must not restore the negative evidence.
            reachabilityObservationWalls[seriesKey] = maxOf(latestObservation, fetchWallEpochSecond)

            val pageSpanSeconds = KrakenApiConstants.OHLC_PAGE_SIZE * seriesKey.intervalMinutes * 60L
            val requestCouldBePageTruncated = sinceEpochSecond <= fetchWallEpochSecond - pageSpanSeconds
            if (mayBeTruncated && requestCouldBePageTruncated && firstReturned > sinceEpochSecond) {
                val frontier = OhlcReachabilityFrontier(
                    pair = seriesKey.pair,
                    intervalMinutes = seriesKey.intervalMinutes,
                    earliestReachableEpochSecond = firstReturned,
                    observedAtEpochSecond = fetchWallEpochSecond,
                    retryAfterEpochSecond = fetchWallEpochSecond +
                        refreshPolicy.freshnessSeconds(distinct, fetchWallEpochSecond, seriesKey.intervalMinutes),
                )
                val selected = selectReachabilityFrontier(existing, frontier)
                if (selected != existing) {
                    reachabilityFrontiers[seriesKey] = selected
                    reachabilityLoaded.add(seriesKey)
                    try {
                        persistentRepository?.saveReachabilityFrontier(selected)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.warn(
                            "Unable to persist OHLC reachability frontier for pair {} interval {}: {}",
                            seriesKey.pair,
                            seriesKey.intervalMinutes,
                            e.message,
                        )
                    }
                    var logDiscovery = false
                    loggedFrontierRetryAfter.compute(seriesKey) { _, previous ->
                        if (previous == null || selected.observedAtEpochSecond >= previous) {
                            logDiscovery = true
                            selected.retryAfterEpochSecond
                        } else {
                            previous
                        }
                    }
                    if (logDiscovery) {
                        log.info(
                            "OHLC reachability frontier discovered; pair={} interval={} earliestReachable={} " +
                                "wall={} retryAfter={}",
                            selected.pair,
                            selected.intervalMinutes,
                            selected.earliestReachableEpochSecond,
                            selected.observedAtEpochSecond,
                            selected.retryAfterEpochSecond,
                        )
                    }
                }
                return@withLock
            }

            // A newer authoritative positive span that reaches before the old boundary makes
            // that frontier obsolete. The per-series lock plus wall high-water prevents a
            // delayed older or same-wall truncated fetch from reinstating it after this clear.
            if (existing != null && firstReturned < existing.earliestReachableEpochSecond) {
                reachabilityPositiveClearWalls.compute(seriesKey) { _, previous ->
                    maxOf(previous ?: Long.MIN_VALUE, fetchWallEpochSecond)
                }
                reachabilityFrontiers.remove(seriesKey, existing)
                try {
                    persistentRepository?.clearReachabilityFrontierIfContradicted(
                        pair = seriesKey.pair,
                        intervalMinutes = seriesKey.intervalMinutes,
                        observedAtEpochSecond = fetchWallEpochSecond,
                        provenReachableFromEpochSecond = firstReturned,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn(
                        "Unable to clear superseded OHLC reachability frontier for pair {} interval {}: {}",
                        seriesKey.pair,
                        seriesKey.intervalMinutes,
                        e.message,
                    )
                }
            }
        }
    }

    private fun observationLock(seriesKey: SeriesKey): Mutex =
        reachabilityObservationLocks.computeIfAbsent(seriesKey) { Mutex() }

    private suspend fun loadReachabilityFrontier(seriesKey: SeriesKey): OhlcReachabilityFrontier? =
        observationLock(seriesKey).withLock { loadReachabilityFrontierLocked(seriesKey) }

    private suspend fun loadReachabilityFrontierLocked(seriesKey: SeriesKey): OhlcReachabilityFrontier? {
        reachabilityFrontiers[seriesKey]?.let { return it }
        if (seriesKey in reachabilityLoaded) return null
        val repository = persistentRepository ?: run {
            reachabilityLoaded.add(seriesKey)
            return null
        }
        var created = false
        val loading = reachabilityLoads.compute(seriesKey) { _, existing ->
            if (existing == null) {
                created = true
                CompletableDeferred()
            } else {
                existing
            }
        }!!
        if (!created) return loading.await()
        try {
            val loaded = repository.loadReachabilityFrontier(seriesKey.pair, seriesKey.intervalMinutes)
            if (loaded != null) {
                reachabilityFrontiers[seriesKey] = loaded
                reachabilityObservationWalls.compute(seriesKey) { _, previous ->
                    maxOf(previous ?: Long.MIN_VALUE, loaded.observedAtEpochSecond)
                }
            }
            reachabilityLoaded.add(seriesKey)
            loading.complete(loaded)
            return loaded
        } catch (e: CancellationException) {
            reachabilityLoads.remove(seriesKey, loading)
            loading.complete(null)
            throw e
        } catch (e: Exception) {
            log.warn(
                "Unable to read persisted OHLC reachability frontier for pair {} interval {}: {}",
                seriesKey.pair,
                seriesKey.intervalMinutes,
                e.message,
            )
            loading.complete(null)
            return null
        } finally {
            reachabilityLoads.remove(seriesKey, loading)
        }
    }

    /** Reads the durable frontier again: comparison selection manifests must notice updates
     * made by another process as well as TTL expiry, not merely trust this cache's RAM copy. */
    suspend fun isReachabilityDependencyCurrent(dependency: OhlcReachabilityDependency): Boolean {
        val key = SeriesKey(dependency.pair.trim().uppercase(), dependency.intervalMinutes)
        return observationLock(key).withLock {
            val current = if (persistentRepository == null) {
                reachabilityFrontiers[key]
            } else {
                try {
                    persistentRepository.loadReachabilityFrontier(key.pair, key.intervalMinutes)
                        .also { loaded ->
                            if (loaded == null) {
                                reachabilityFrontiers.remove(key)
                            } else {
                                reachabilityFrontiers[key] = loaded
                                reachabilityObservationWalls.compute(key) { _, previous ->
                                    maxOf(previous ?: Long.MIN_VALUE, loaded.observedAtEpochSecond)
                                }
                            }
                            reachabilityLoaded.add(key)
                        }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn(
                        "Unable to recheck OHLC reachability frontier for pair {} interval {}: {}",
                        key.pair,
                        key.intervalMinutes,
                        e.message,
                    )
                    return@withLock false
                }
            } ?: return@withLock false
            current.isFresh(nowProvider().epochSecond) &&
                current.earliestReachableEpochSecond == dependency.earliestReachableEpochSecond
        }
    }

    /**
     * Structured per-request cache diagnostic: makes duplicate OHLC work attributable
     * by (owner, pair, interval, since, upTo). Debug-gated; production stays quiet.
     */
    private fun logCacheOutcome(
        outcome: String,
        seriesKey: SeriesKey,
        intervalMinutes: Int,
        sinceEpochSecond: Long,
        upTo: Instant,
        callOwner: OhlcCallOwner,
        fetch: FetchRecord,
    ) {
        if (!log.isDebugEnabled) return
        log.debug(
            "OHLC cache {}; pair={} interval={} since={} upTo={} owner={} coverage=[{}, {}) wall={}",
            outcome,
            seriesKey.pair,
            intervalMinutes,
            sinceEpochSecond,
            upTo.epochSecond,
            callOwner,
            fetch.coverage.fromEpochSecond,
            fetch.coverage.untilEpochSecond,
            fetch.fetchWallEpochSecond,
        )
    }

    /**
     * Structured live-fetch diagnostic with the raw page shape and the proven span.
     * Cache-level diagnostics are debug-only: resolver lookups can use a different `since`
     * for every historical event, so INFO logging an insufficient response creates another
     * per-event stream even after repeated provider work is reduced.
     */
    private fun logLiveFetch(
        seriesKey: SeriesKey,
        intervalMinutes: Int,
        sinceEpochSecond: Long,
        upTo: Instant,
        callOwner: OhlcCallOwner,
        rawRowCount: Int,
        completedRowCount: Int,
        mayBeTruncated: Boolean,
        coverage: OhlcCoverage,
        fetchWallEpochSecond: Long,
        sufficient: Boolean,
    ) {
        val exhausted = !sufficient && upTo.epochSecond <= fetchWallEpochSecond
        val outcome = if (exhausted) "LIVE_FETCH_TRUNCATED_INSUFFICIENT" else "LIVE_FETCH"
        if (!log.isDebugEnabled) return
        log.debug(
            "OHLC cache {}; pair={} interval={} since={} upTo={} owner={} rawRows={} completedRows={} " +
                "truncated={} coverage=[{}, {}) wall={}",
            outcome,
            seriesKey.pair,
            intervalMinutes,
            sinceEpochSecond,
            upTo.epochSecond,
            callOwner,
            rawRowCount,
            completedRowCount,
            mayBeTruncated,
            coverage.fromEpochSecond,
            coverage.untilEpochSecond,
            fetchWallEpochSecond,
        )
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

    /**
     * Insufficient-coverage gate: the newest proof recorded for this exact request
     * `since`, when it is still fresh under the refresh policy but does not contain
     * the requested window. Serves the proof's stored span (empty for a
     * truncated-empty marker) so an unreachable historical range is fetched once per
     * freshness window instead of on every lookup. Freshness is derived from the
     * proven span's own candles — the same cadence that would revalidate the span —
     * so expiry, window changes, and new covering evidence all naturally reopen it.
     *
     * The gate only applies when the valuation instant sits at or before the proof's
     * fetch wall: a refetch right now could not complete any new in-window candle,
     * only a provider correction or backfill (discovered at TTL expiry) could change
     * the answer. A valuation beyond the wall is a live tail that may have grown, so
     * it always refetches and the gate stays out of its way. At upTo == wall the gate
     * may withhold a candle closing exactly at the wall until TTL expiry — the same
     * boundary semantics as covering proofs (contains() is inclusive) — failing
     * closed (unresolved, never wrong) while pacing same-second bursts that a strict
     * inequality would refetch unboundedly. Markers never count as covering here,
     * mirroring coveringFetch, so even degenerate windows pace instead of spinning.
     */
    private fun memoryInsufficient(
        seriesKey: SeriesKey,
        sinceEpochSecond: Long,
        upToEpochSecond: Long,
        intervalMinutes: Int,
    ): CoveredSeries? {
        val entry = store[seriesKey] ?: return null
        val proof = entry.latestProofForRequestSince(sinceEpochSecond) ?: return null
        if (proof.coverage.untilEpochSecond > proof.coverage.fromEpochSecond &&
            proof.coverage.contains(sinceEpochSecond, upToEpochSecond)
        ) {
            return null
        }
        if (upToEpochSecond > proof.fetchWallEpochSecond) return null
        val gated = CoveredSeries(proof, spanCandles(entry, proof.coverage))
        if (isExpired(gated, intervalMinutes)) return null
        return gated
    }

    /** Stored candles inside one proven span, in start order. */
    private fun spanCandles(entry: SeriesEntry, coverage: OhlcCoverage): List<Pair<Long, BigDecimal>> =
        entry.candles.subMap(coverage.fromEpochSecond, true, coverage.untilEpochSecond, false)
            .entries.asSequence()
            .map { it.key to it.value }
            .toList()

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
     * Durable half of the insufficient-coverage gate: restores the newest persisted
     * proof for this exact request `since` (merge-only, no fresh provider evidence)
     * so a restart does not immediately re-hammer an unreachable range, then serves
     * through the same memory gate. Returns null when no proof exists or it is stale.
     */
    private suspend fun loadInsufficientFromPersistent(
        seriesKey: SeriesKey,
        sinceEpochSecond: Long,
        upToEpochSecond: Long,
        intervalMinutes: Int,
    ): CoveredSeries? {
        val repository = persistentRepository ?: return null
        // Every live fetch records in memory before persisting, so a same-since memory
        // proof (fresh ones were already served above; this one is expired) is always
        // at least as new as anything durable: skip the query, fail closed to a refetch.
        if (store[seriesKey]?.latestProofForRequestSince(sinceEpochSecond) != null) return null
        val stored = try {
            repository.loadLatestProofForSince(
                pair = seriesKey.pair,
                intervalMinutes = seriesKey.intervalMinutes,
                sinceEpochSecond = sinceEpochSecond,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(
                "Unable to read persisted OHLC proof for pair {} interval {}: {}",
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
            mayBeTruncated = false,
            restoredCoverage = OhlcCoverage(
                stored.coverageFromEpochSecond,
                stored.coverageUntilEpochSecond,
            ),
        )
        return memoryInsufficient(seriesKey, sinceEpochSecond, upToEpochSecond, intervalMinutes)
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
     * actually proved. A live truncated page with zero completed rows proves no reusable
     * coverage, so it records an explicit empty marker proof instead: the marker paces
     * the insufficient-coverage gate but never satisfies a coverage check. A restore
     * replays the durable span ([restoredCoverage]) instead of recomputing it, so memory
     * agrees with the database exactly. Returns the outcome for the flight.
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
        val computedCoverage = restoredCoverage ?: authoritativeOhlcCoverage(
            requestSinceEpochSecond = sinceEpochSecond,
            fetchWallEpochSecond = fetchWallEpochSecond,
            intervalMinutes = seriesKey.intervalMinutes,
            completedCandles = candles,
            mayBeTruncated = mayBeTruncated,
        )
        // A live truncated-empty page proves no span; its marker still paces the exact
        // request so the unreachable range is not refetched on every lookup. The flight
        // outcome keeps the null coverage (no reusable span proved); only the stored
        // record carries the marker. A restore with no span cannot happen (durable rows
        // always carry bounds); if it ever does, record nothing and fail closed.
        val coverage = computedCoverage
            ?: OhlcCoverage(sinceEpochSecond, sinceEpochSecond).takeIf { authoritative }
        val outcome = FetchOutcome(
            store.compute(seriesKey) { _, existing -> existing ?: SeriesEntry() }!!,
            computedCoverage,
            fetchWallEpochSecond,
        )
        val entry = outcome.entry
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
                // A marker proves nothing, so it collapses only older markers: it must
                // never evict a real proof recorded at the same wall.
                val newIsMarker = coverage.untilEpochSecond <= coverage.fromEpochSecond
                entry.fetches.removeAll {
                    it.requestSinceEpochSecond == sinceEpochSecond &&
                        (!newIsMarker || it.coverage.untilEpochSecond <= it.coverage.fromEpochSecond) &&
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
        return outcome
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

    private fun startOrJoinReachabilityFlight(
        seriesKey: SeriesKey,
    ): Pair<CompletableDeferred<DiscoveryFlightResult>, Boolean> {
        var created = false
        val deferred = reachabilityInFlight.compute(seriesKey) { _, existing ->
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
