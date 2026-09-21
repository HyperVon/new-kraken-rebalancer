package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.service.FakeKrakenService
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

class HistoricalOhlcCacheTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    private val pair = "BABYUSD"
    private val interval = 15
    private val durationSeconds = interval * 60L

    private fun fake(
        candles: List<Pair<Long, String>>,
        callCounter: AtomicInteger,
        failureOnFirstCall: Boolean = false,
        perCallDelayMillis: Long = 0,
    ): FakeKrakenService = FakeKrakenService().apply {
        ohlcSupplier = { _, _, _ ->
            if (perCallDelayMillis > 0) Thread.sleep(perCallDelayMillis)
            val callIndex = callCounter.incrementAndGet()
            if (failureOnFirstCall && callIndex == 1) error("transient kraken failure")
            candles.map { (start, close) -> start to BigDecimal(close) }
        }
    }

    init {
        "identical request is served from memory without a second network call" {
            val counter = AtomicInteger(0)
            val now = Instant.now().epochSecond
            val completed = now - 2 * durationSeconds
            val cache = HistoricalOhlcCache(fake(listOf(completed to "0.0175"), counter))
            val since = completed - durationSeconds
            val upTo = Instant.ofEpochSecond(now)

            val first = cache.getOHLC(pair, interval, since, upTo)
            val second = cache.getOHLC(pair, interval, since, upTo)

            counter.get() shouldBe 1
            first shouldBe second
            first.size shouldBe 1
        }

        "later point inside the covered window is served without refetch" {
            val counter = AtomicInteger(0)
            val now = Instant.now().epochSecond
            val earliest = now - 4 * durationSeconds
            val cache =
                HistoricalOhlcCache(
                    fake(
                        listOf(earliest to "0.0175", (earliest + durationSeconds) to "0.0179"),
                        counter,
                    ),
                )
            cache.getOHLC(pair, interval, earliest, Instant.ofEpochSecond(earliest + durationSeconds))

            val second = cache.getOHLC(pair, interval, earliest, Instant.ofEpochSecond(now))

            counter.get() shouldBe 1
            second.size shouldBe 2
        }

        "valuation instant past the fetch wall time refetches for fresh candles" {
            val counter = AtomicInteger(0)
            val now = Instant.now().epochSecond
            val completed = now - 2 * durationSeconds
            val cache = HistoricalOhlcCache(fake(listOf(completed to "0.0175"), counter))
            val since = completed - durationSeconds
            cache.getOHLC(pair, interval, since, Instant.ofEpochSecond(now))

            cache.getOHLC(pair, interval, since, Instant.ofEpochSecond(now + 3600))

            counter.get() shouldBe 2
        }

        "identical in-flight requests share one network call" {
            val counter = AtomicInteger(0)
            val now = Instant.now().epochSecond
            val completed = now - 2 * durationSeconds
            val cache = HistoricalOhlcCache(fake(listOf(completed to "0.0175"), counter, perCallDelayMillis = 100))
            val since = completed - durationSeconds
            val upTo = Instant.ofEpochSecond(now)

            val results =
                withContext(Dispatchers.IO) {
                    (1..4).map { async { cache.getOHLC(pair, interval, since, upTo) } }.awaitAll()
                }

            counter.get() shouldBe 1
            results.forEach { result -> result.size shouldBe 1 }
        }

        "failed fetch is not cached and a retry hits the network again" {
            val counter = AtomicInteger(0)
            val now = Instant.now().epochSecond
            val completed = now - 2 * durationSeconds
            val cache = HistoricalOhlcCache(fake(listOf(completed to "0.0175"), counter, failureOnFirstCall = true))
            val since = completed - durationSeconds
            val upTo = Instant.ofEpochSecond(now)

            runCatching { cache.getOHLC(pair, interval, since, upTo) }
            val recovered = cache.getOHLC(pair, interval, since, upTo)

            counter.get() shouldBe 2
            recovered.size shouldBe 1
        }

        "trailing in-progress candle is never retained" {
            val counter = AtomicInteger(0)
            val wall = Instant.now().epochSecond
            val completedStart = wall - 6 * durationSeconds
            val inProgressStart = wall - 100
            val cache =
                HistoricalOhlcCache(
                    fake(
                        listOf(
                            completedStart to "0.0175",
                            inProgressStart to "0.0999",
                        ),
                        counter,
                    ),
                )
            val since = completedStart - durationSeconds

            val served = cache.getOHLC(pair, interval, since, Instant.ofEpochSecond(wall))

            counter.get() shouldBe 1
            served.map { it.first }.shouldContainExactlyInAnyOrder(listOf(completedStart))
            served.size shouldBe 1
        }

        "coverage is per pair and per interval" {
            val counter = AtomicInteger(0)
            val now = Instant.now().epochSecond
            val completed = now - 4 * durationSeconds
            val cache = HistoricalOhlcCache(fake(listOf(completed to "0.0175"), counter))

            cache.getOHLC(pair, interval, completed, Instant.ofEpochSecond(now))
            cache.getOHLC("OTHERUSD", interval, completed, Instant.ofEpochSecond(now))
            cache.getOHLC(pair, 1440, completed, Instant.ofEpochSecond(now))

            counter.get() shouldBe 3
        }

        "empty series for a delisted pair is served from memory on the second request" {
            val counter = AtomicInteger(0)
            val now = Instant.now().epochSecond
            val cache = HistoricalOhlcCache(fake(emptyList(), counter))
            val since = now - 6 * durationSeconds
            val upTo = Instant.ofEpochSecond(now)

            val first = cache.getOHLC(pair, interval, since, upTo)
            val second = cache.getOHLC(pair, interval, since, upTo)

            counter.get() shouldBe 1
            first shouldBe emptyList()
            second shouldBe emptyList()
        }

        "joiner with a stricter window than the leader covered refetches" {
            val counter = AtomicInteger(0)
            val now = Instant.now().epochSecond
            val completed = now - 2 * durationSeconds
            val cache = HistoricalOhlcCache(fake(listOf(completed to "0.0175"), counter))
            val since = completed - durationSeconds

            cache.getOHLC(pair, interval, since, Instant.ofEpochSecond(completed))
            val stricter = cache.getOHLC(pair, interval, since, Instant.ofEpochSecond(now + 3600))

            counter.get() shouldBe 2
            stricter.size shouldBe 1
        }

        "joiner whose window the leader did not cover refetches after joining" {
            val counter = AtomicInteger(0)
            val now = Instant.now().epochSecond
            val completed = now - 2 * durationSeconds
            val cache = HistoricalOhlcCache(fake(listOf(completed to "0.0175"), counter, perCallDelayMillis = 200))
            val since = completed - durationSeconds
            val upTo = Instant.ofEpochSecond(now)

            val results =
                withContext(Dispatchers.IO) {
                    val leader = async { cache.getOHLC(pair, interval, since, upTo) }
                    delay(100)
                    val joiner = async { cache.getOHLC(pair, interval, since, Instant.ofEpochSecond(now + 3600)) }
                    listOf(leader.await(), joiner.await())
                }

            results.forEach { result -> result.size shouldBe 1 }
            counter.get() shouldBe 2
        }

        "series keys compare by pair and interval" {
            val counter = AtomicInteger(0)
            val now = Instant.now().epochSecond
            val completed = now - 2 * durationSeconds
            val cache = HistoricalOhlcCache(fake(listOf(completed to "0.0175"), counter))
            val since = completed - durationSeconds

            cache.getOHLC(" babyusd ", interval, since, Instant.ofEpochSecond(now))
            cache.getOHLC(pair.uppercase(), 15, since, Instant.ofEpochSecond(now))

            counter.get() shouldBe 1
        }
    }
}
