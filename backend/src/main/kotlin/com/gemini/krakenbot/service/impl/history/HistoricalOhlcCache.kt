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
 * - Only the live OHLC endpoint participates; never a ticker or any other live-priced source.
 */
class HistoricalOhlcCache(
    private val krakenService: KrakenService,
    private val persistentRepository: HistoricalOhlcRepository? = null,
) {
    private val log = LoggerFactory.getLogger(HistoricalOhlcCache::class.java)

    private class FetchRecord(val sinceEpochSecond: Long, val fetchWallEpochSecond: Long)

    private class SeriesEntry {
        val candles = ConcurrentSkipListMap<Long, BigDecimal>()
        val fetches = mutableListOf<FetchRecord>()

        /** Single-fetch proof that this request needs no fresh network call. */
        fun coveringFetch(sinceEpochSecond: Long, upToEpochSecond: Long): FetchRecord? = synchronized(fetches) {
            fetches.firstOrNull {
                it.sinceEpochSecond <= sinceEpochSecond && it.fetchWallEpochSecond >= upToEpochSecond
            }
        }
    }

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
     * serving stored history when a single recorded fetch provably equals a fresh fetch,
     * fetching (once per identical concurrent request) otherwise. [upTo] is the valuation
     * instant the caller resolves; candles that closed after it cannot be selected by callers.
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

        serveFromMemory(seriesKey, sinceEpochSecond, upTo.epochSecond)?.let { return it }
        loadFromPersistent(seriesKey, sinceEpochSecond, upTo.epochSecond)?.let { return it }

        val flightKey = FlightKey(normalizedPair, intervalMinutes.toLong(), sinceEpochSecond)
        val (flight, created) = startOrJoinFlight(flightKey)
        if (created) {
            try {
                val fetchWallEpochSecond = Instant.now().epochSecond
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

    private fun serveFromMemory(
        seriesKey: SeriesKey,
        sinceEpochSecond: Long,
        upToEpochSecond: Long,
    ): List<Pair<Long, BigDecimal>>? {
        val entry = store[seriesKey] ?: return null
        if (entry.coveringFetch(sinceEpochSecond, upToEpochSecond) == null) {
            return null
        }
        return entry.candles.tailMap(sinceEpochSecond, true)
            .entries.asSequence()
            .map { it.key to it.value }
            .toList()
    }

    private suspend fun loadFromPersistent(
        seriesKey: SeriesKey,
        sinceEpochSecond: Long,
        upToEpochSecond: Long,
    ): List<Pair<Long, BigDecimal>>? {
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
        return stored.candles
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
