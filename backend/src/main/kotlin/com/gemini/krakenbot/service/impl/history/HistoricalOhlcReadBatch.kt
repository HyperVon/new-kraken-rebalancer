package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.repository.HistoricalOhlcFetchProof
import com.gemini.krakenbot.repository.HistoricalOhlcRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Calculation-local index of durable proof metadata, never of candle values. Only certified
 * misses may bypass loadCovered; positive proofs retain the normal SQLite/memory path.
 * Every bypassed window must still lack a covering proof when the result is published.
 */
internal class HistoricalOhlcReadBatch(
    val cache: HistoricalOhlcCache,
    private val repository: HistoricalOhlcRepository,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<HistoricalOhlcReadBatch>

    private data class Series(val pair: String, val intervalMinutes: Int)
    private data class Window(val since: Long, val upTo: Long)

    private val log = LoggerFactory.getLogger(HistoricalOhlcReadBatch::class.java)
    private val mutex = Mutex()
    private val proofs = mutableMapOf<Series, List<HistoricalOhlcFetchProof>>()
    private val misses = mutableMapOf<Series, MutableSet<Window>>()

    /**
     * Whether the indexed proofs might cover this window. False lets the caller skip its
     * per-window SQLite lookup; true — including when the index cannot be read — always falls
     * back to that lookup. Presence of a proof is decided here; whether the window was actually
     * skipped is reported separately via [recordSkipped].
     */
    suspend fun mayCover(pair: String, intervalMinutes: Int, since: Long, upTo: Long): Boolean = mutex.withLock {
        val series = Series(pair, intervalMinutes)
        val index = proofs[series] ?: loadProofs(series)?.also { proofs[series] = it } ?: return@withLock true
        index.any { it.covers(Window(since, upTo)) }
    }

    /**
     * Records a window the cache really skipped on a reachability frontier, whichever lookup
     * path led there. [isStillCurrent] must re-prove each of these: a covering proof added by
     * any writer makes the calculated result unpublishable.
     */
    suspend fun recordSkipped(pair: String, intervalMinutes: Int, since: Long, upTo: Long) {
        mutex.withLock {
            misses.getOrPut(Series(pair, intervalMinutes)) { mutableSetOf() }.add(Window(since, upTo))
        }
    }

    /** A live fetch can add coverage without changing any candle content revision. */
    suspend fun invalidate(pair: String, intervalMinutes: Int) {
        mutex.withLock { proofs.remove(Series(pair, intervalMinutes)) }
    }

    /**
     * False means a skipped window may now be covered **or** its proof metadata could not be
     * re-read; callers must treat both as unpublishable and never as proof that history is absent.
     * A repository that exposes no batched proof metadata contributes no recheck signal and is
     * left on the pre-existing per-window lookup behavior.
     */
    suspend fun isStillCurrent(): Boolean {
        val skipped = mutex.withLock { misses.mapValues { (_, windows) -> windows.toList() } }
        for ((series, windows) in skipped) {
            val current = try {
                repository.loadFetchProofs(series.pair, series.intervalMinutes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A read failure cannot certify that history is still absent.
                log.warn(
                    "Unable to recheck OHLC coverage proofs for pair {} interval {}",
                    series.pair,
                    series.intervalMinutes,
                )
                return false
            } ?: continue
            if (windows.any { window -> current.any { it.covers(window) } }) return false
        }
        return true
    }

    /** Null means "no batched metadata for this series", never "no proofs exist". */
    private suspend fun loadProofs(series: Series): List<HistoricalOhlcFetchProof>? = try {
        repository.loadFetchProofs(series.pair, series.intervalMinutes)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // A failed batch read never certifies absence: the caller falls back to the ordinary
        // per-window lookup, and a later recheck failure keeps the result unpublishable.
        log.warn(
            "Unable to load OHLC coverage proof batch for pair {} interval {}",
            series.pair,
            series.intervalMinutes,
        )
        null
    }

    private fun HistoricalOhlcFetchProof.covers(window: Window): Boolean =
        coverageUntilEpochSecond > coverageFromEpochSecond &&
            coverageFromEpochSecond <= window.since && window.upTo <= coverageUntilEpochSecond
}
