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

    suspend fun mayCover(pair: String, intervalMinutes: Int, since: Long, upTo: Long): Boolean = mutex.withLock {
        val series = Series(pair, intervalMinutes)
        val index = proofs[series] ?: loadProofs(series)?.also { proofs[series] = it } ?: return@withLock true
        val window = Window(since, upTo)
        if (index.any { it.covers(window) }) return@withLock true
        misses.getOrPut(series) { mutableSetOf() }.add(window)
        false
    }

    /** A live fetch can add coverage without changing any candle content revision. */
    suspend fun invalidate(pair: String, intervalMinutes: Int) {
        mutex.withLock { proofs.remove(Series(pair, intervalMinutes)) }
    }

    suspend fun isStillCurrent(): Boolean {
        val skipped = mutex.withLock { misses.mapValues { (_, windows) -> windows.toList() } }
        for ((series, windows) in skipped) {
            val current = loadProofs(series) ?: return false
            if (windows.any { window -> current.any { it.covers(window) } }) return false
        }
        return true
    }

    private suspend fun loadProofs(series: Series): List<HistoricalOhlcFetchProof>? = try {
        repository.loadFetchProofs(series.pair, series.intervalMinutes)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // An unsupported or failed batch read never certifies absence. Ordinary lookups
        // retain their existing fallback; publication fails closed if rechecking fails.
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
