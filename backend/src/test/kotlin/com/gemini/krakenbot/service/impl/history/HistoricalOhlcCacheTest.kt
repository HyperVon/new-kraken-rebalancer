package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.config.DatabaseConfig
import com.gemini.krakenbot.model.KrakenApiConstants
import com.gemini.krakenbot.repository.ConsumedOhlcDependency
import com.gemini.krakenbot.repository.HistoricalOhlcRepository
import com.gemini.krakenbot.repository.impl.SqliteHistoricalOhlcRepositoryImpl
import com.gemini.krakenbot.service.FakeKrakenService
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
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

        "persistence failures fall back to the live response without poisoning the cache" {
            val counter = AtomicInteger(0)
            val now = Instant.now().epochSecond
            val completed = now - 2 * durationSeconds
            val repository = object : HistoricalOhlcRepository {
                override suspend fun loadCovered(
                    pair: String,
                    intervalMinutes: Int,
                    sinceEpochSecond: Long,
                    upToEpochSecond: Long,
                ): com.gemini.krakenbot.repository.HistoricalOhlcSeries = error("read failure")

                override suspend fun saveFetch(
                    pair: String,
                    intervalMinutes: Int,
                    sinceEpochSecond: Long,
                    fetchedAtEpochSecond: Long,
                    candles: List<Pair<Long, BigDecimal>>,
                    mayBeTruncated: Boolean,
                ): Boolean {
                    error("write failure")
                }
            }
            val cache = HistoricalOhlcCache(
                fake(listOf(completed to "0.0175"), counter),
                persistentRepository = repository,
            )

            val result = cache.getOHLC(
                pair,
                interval,
                completed - durationSeconds,
                Instant.ofEpochSecond(now),
            )

            counter.get() shouldBe 1
            result.size shouldBe 1
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

        "completed candles and empty fetch coverage survive a cache restart" {
            val database = DatabaseConfig.init(":memory:")
            val repository = SqliteHistoricalOhlcRepositoryImpl(database)
            val firstCounter = AtomicInteger(0)
            val secondCounter = AtomicInteger(0)
            val now = Instant.now().epochSecond
            val completed = now - 4 * durationSeconds
            val since = completed - durationSeconds
            val upTo = Instant.ofEpochSecond(now - durationSeconds)

            val first = HistoricalOhlcCache(
                fake(listOf(completed to "0.0175"), firstCounter),
                persistentRepository = repository,
            )
            val initial = first.getOHLC(pair, interval, since, upTo)

            val restarted = HistoricalOhlcCache(
                fake(emptyList(), secondCounter),
                persistentRepository = repository,
            )
            val restored = restarted.getOHLC(pair, interval, since, upTo)

            firstCounter.get() shouldBe 1
            secondCounter.get() shouldBe 0
            restored.size shouldBe initial.size
            restored.single().first shouldBe initial.single().first
            restored.single().second shouldBeEqualComparingTo initial.single().second

            val emptyPair = "DELISTEDUSD"
            val emptyFirst = HistoricalOhlcCache(
                fake(emptyList(), firstCounter),
                persistentRepository = repository,
            )
            emptyFirst.getOHLC(emptyPair, interval, since, upTo) shouldBe emptyList()
            val emptyRestarted = HistoricalOhlcCache(
                fake(emptyList(), secondCounter),
                persistentRepository = repository,
            )
            emptyRestarted.getOHLC(emptyPair, interval, since, upTo) shouldBe emptyList()
            secondCounter.get() shouldBe 0
        }

        "empty fetch coverage revalidates against the live source after its revalidation window" {
            val counter = AtomicInteger(0)
            var clock = Instant.now()
            val fixedUpTo = clock
            val cache = HistoricalOhlcCache(
                fake(emptyList(), counter),
                nowProvider = { clock },
            )
            val since = clock.epochSecond - 6 * durationSeconds

            cache.getOHLC(pair, interval, since, fixedUpTo) shouldBe emptyList()
            clock = clock.plusSeconds(500)
            cache.getOHLC(pair, interval, since, fixedUpTo) shouldBe emptyList()
            counter.get() shouldBe 1

            clock = clock.plusSeconds(200)
            cache.getOHLC(pair, interval, since, fixedUpTo) shouldBe emptyList()
            counter.get() shouldBe 2
        }

        "revalidated response applies provider corrections and backfills" {
            val counter = AtomicInteger(0)
            var clock = Instant.now()
            val fixedUpTo = clock
            val candleStart = clock.epochSecond - 2 * durationSeconds
            val candles = mutableListOf(candleStart to BigDecimal("0.0175"))
            val cache = HistoricalOhlcCache(
                FakeKrakenService().apply {
                    ohlcSupplier = { _, _, _ ->
                        counter.incrementAndGet()
                        candles.toList()
                    }
                },
                nowProvider = { clock },
            )
            val since = candleStart - durationSeconds

            cache.getOHLC(pair, interval, since, fixedUpTo).single().second shouldBeEqualComparingTo
                (BigDecimal("0.0175"))

            // Inside the freshness window nothing is refetched; after it the corrected close
            // and a later backfilled candle are both visible, and the provider's trailing
            // in-progress candle is still dropped. The backfilled candle is listed first so
            // the freshness computation sees out-of-order candle starts.
            clock = clock.plusSeconds(3_601)
            candles += (clock.epochSecond - 100) to BigDecimal("0.0999")
            candles[0] = candleStart to BigDecimal("0.0199")
            candles += (candleStart + durationSeconds) to BigDecimal("0.0200")
            val revalidated = cache.getOHLC(pair, interval, since, fixedUpTo)

            counter.get() shouldBe 2
            revalidated.map { it.second } shouldBe listOf(BigDecimal("0.0199"), BigDecimal("0.0200"))
        }

        "stale history keeps serving while revalidation fails and retries after another window" {
            val counter = AtomicInteger(0)
            var clock = Instant.now()
            val fixedUpTo = clock
            val candleStart = clock.epochSecond - 2 * durationSeconds
            val candles = mutableListOf(candleStart to BigDecimal("0.0175"))
            var failAll = false
            val cache = HistoricalOhlcCache(
                FakeKrakenService().apply {
                    ohlcSupplier = { _, _, _ ->
                        val call = counter.incrementAndGet()
                        if (failAll) error("live source down")
                        if (call > 1) candles += (candleStart + durationSeconds) to BigDecimal("0.0200")
                        candles.toList()
                    }
                },
                nowProvider = { clock },
            )
            val since = candleStart - durationSeconds

            cache.getOHLC(pair, interval, since, fixedUpTo).size shouldBe 1
            // A second, wider fetch proof for the same series: the slide-on-failure sweep
            // must preserve it while replacing only the failed proof.
            val widerSince = since - durationSeconds
            cache.getOHLC(pair, interval, widerSince, fixedUpTo)

            clock = clock.plusSeconds(3_601)
            failAll = true
            val stale = cache.getOHLC(pair, interval, since, fixedUpTo)
            counter.get() shouldBe 3
            stale.map { it.first } shouldBe listOf(candleStart)

            // The stale proof was re-armed: inside the next window no further attempts happen.
            clock = clock.plusSeconds(1_000)
            cache.getOHLC(pair, interval, since, fixedUpTo)
            counter.get() shouldBe 3

            clock = clock.plusSeconds(2_700)
            failAll = false
            val recovered = cache.getOHLC(pair, interval, since, fixedUpTo)
            counter.get() shouldBe 4
            recovered.size shouldBe 2
        }

        "a failed revalidation never overwrites the persisted successful evidence" {
            val database = DatabaseConfig.init(":memory:")
            val repository = SqliteHistoricalOhlcRepositoryImpl(database)
            var clock = Instant.now()
            val fixedUpTo = clock
            val candleStart = clock.epochSecond - 2 * durationSeconds
            val candles = mutableListOf(candleStart to BigDecimal("0.0175"))
            var failAll = false
            val firstCounter = AtomicInteger(0)
            val first = HistoricalOhlcCache(
                FakeKrakenService().apply {
                    ohlcSupplier = { _, _, _ ->
                        firstCounter.incrementAndGet()
                        if (failAll) error("live source down")
                        candles.toList()
                    }
                },
                persistentRepository = repository,
                nowProvider = { clock },
            )
            first.getOHLC(pair, interval, candleStart - durationSeconds, fixedUpTo).size shouldBe 1

            // A restarted cache whose revalidation fails keeps serving the persisted stale
            // candle; the persisted fetch proof is untouched.
            val failingCounter = AtomicInteger(0)
            failAll = true
            clock = clock.plusSeconds(3_601)
            val failing = HistoricalOhlcCache(
                FakeKrakenService().apply {
                    ohlcSupplier = { _, _, _ ->
                        failingCounter.incrementAndGet()
                        error("live source down")
                    }
                },
                persistentRepository = repository,
                nowProvider = { clock },
            )
            val stale = failing.getOHLC(pair, interval, candleStart - durationSeconds, fixedUpTo)
            failingCounter.get() shouldBe 1
            stale.single().second shouldBeEqualComparingTo (BigDecimal("0.0175"))

            // The durable row still holds the original successful fetch: original wall time
            // and candle value, never a failed attempt recorded as empty or partial evidence.
            val persisted = repository.loadCovered(
                pair = pair,
                intervalMinutes = interval,
                sinceEpochSecond = candleStart - durationSeconds,
                upToEpochSecond = fixedUpTo.epochSecond,
            )
            persisted?.fetchedAtEpochSecond shouldBe fixedUpTo.epochSecond
            persisted?.candles?.single()?.second?.compareTo(BigDecimal("0.0175")) shouldBe 0

            // A later healthy process revalidates the expired but intact evidence normally.
            val recoveredCounter = AtomicInteger(0)
            val recovered = HistoricalOhlcCache(
                FakeKrakenService().apply {
                    ohlcSupplier = { _, _, _ ->
                        recoveredCounter.incrementAndGet()
                        candles.toList()
                    }
                },
                persistentRepository = repository,
                nowProvider = { clock },
            )
            recovered.getOHLC(pair, interval, candleStart - durationSeconds, fixedUpTo).size shouldBe 1
            recoveredCounter.get() shouldBe 1
        }

        "transient initial fetch failure is not persisted as successful empty evidence" {
            val database = DatabaseConfig.init(":memory:")
            val repository = SqliteHistoricalOhlcRepositoryImpl(database)
            val clock = Instant.now()
            val candleStart = clock.epochSecond - 2 * durationSeconds
            val failingCounter = AtomicInteger(0)
            val failing = HistoricalOhlcCache(
                FakeKrakenService().apply {
                    ohlcSupplier = { _, _, _ ->
                        failingCounter.incrementAndGet()
                        error("transient kraken failure")
                    }
                },
                persistentRepository = repository,
            )

            runCatching { failing.getOHLC(pair, interval, candleStart - durationSeconds, clock) }

            val recoveredCounter = AtomicInteger(0)
            val recovered = HistoricalOhlcCache(
                fake(listOf(candleStart to "0.0175"), recoveredCounter),
                persistentRepository = repository,
            )
            val served = recovered.getOHLC(pair, interval, candleStart - durationSeconds, clock)

            recoveredCounter.get() shouldBe 1
            served.single().second shouldBeEqualComparingTo (BigDecimal("0.0175"))
        }

        "concurrent post-expiry identical requests share one revalidation flight" {
            val counter = AtomicInteger(0)
            var clock = Instant.now()
            val fixedUpTo = clock
            val candleStart = clock.epochSecond - 2 * durationSeconds
            val cache = HistoricalOhlcCache(
                fake(listOf(candleStart to "0.0175"), counter, perCallDelayMillis = 150),
                nowProvider = { clock },
            )
            val since = candleStart - durationSeconds
            cache.getOHLC(pair, interval, since, fixedUpTo)

            clock = clock.plusSeconds(3_601)
            val results =
                withContext(Dispatchers.IO) {
                    (1..4).map { async { cache.getOHLC(pair, interval, since, fixedUpTo) } }.awaitAll()
                }

            counter.get() shouldBe 2
            results.forEach { result -> result.single().second shouldBeEqualComparingTo (BigDecimal("0.0175")) }
        }

        "historical candles past the recent age use the long revalidation window" {
            val counter = AtomicInteger(0)
            var clock = Instant.now()
            val fixedUpTo = clock
            val candleStart = clock.epochSecond - 100 * durationSeconds
            val cache = HistoricalOhlcCache(
                fake(listOf(candleStart to "0.0175"), counter),
                nowProvider = { clock },
            )
            val since = candleStart - durationSeconds

            cache.getOHLC(pair, interval, since, fixedUpTo)
            clock = clock.plusSeconds(5_000)
            cache.getOHLC(pair, interval, since, fixedUpTo)
            counter.get() shouldBe 1

            clock = clock.plusSeconds(600_000)
            cache.getOHLC(pair, interval, since, fixedUpTo)
            counter.get() shouldBe 2
        }

        "refresh policy expires empty, recent, and historical fetches on their own windows" {
            val policy = OhlcRefreshPolicy()

            val empty = emptyList<Pair<Long, BigDecimal>>()
            policy.isExpired(empty, fetchedAtEpochSecond = 1_000, intervalMinutes = 15, nowEpochSecond = 1_599) shouldBe
                false
            policy.isExpired(empty, fetchedAtEpochSecond = 1_000, intervalMinutes = 15, nowEpochSecond = 1_600) shouldBe
                true

            // A candle ending 100s before the fetch is recent: expires on the hourly window.
            val recent = listOf(200L to BigDecimal.ONE)
            policy.isExpired(
                recent,
                fetchedAtEpochSecond = 1_000,
                intervalMinutes = 15,
                nowEpochSecond = 4_599,
            ) shouldBe
                false
            policy.isExpired(
                recent,
                fetchedAtEpochSecond = 1_000,
                intervalMinutes = 15,
                nowEpochSecond = 4_600,
            ) shouldBe
                true

            // A candle ending more than a day before the fetch is historical: expires on the
            // weekly window.
            val historical = listOf((-90_000L) to BigDecimal.ONE)
            policy.isExpired(
                historical,
                fetchedAtEpochSecond = 1_000,
                intervalMinutes = 15,
                nowEpochSecond = 605_799,
            ) shouldBe false
            policy.isExpired(
                historical,
                fetchedAtEpochSecond = 1_000,
                intervalMinutes = 15,
                nowEpochSecond = 605_800,
            ) shouldBe true
        }

        "revalidation cancellation propagates and leaves the retry bounded" {
            val counter = AtomicInteger(0)
            var clock = Instant.now()
            val fixedUpTo = clock
            val candleStart = clock.epochSecond - 2 * durationSeconds
            var cancel = false
            val cache = HistoricalOhlcCache(
                FakeKrakenService().apply {
                    ohlcSupplier = { _, _, _ ->
                        counter.incrementAndGet()
                        if (cancel) throw kotlinx.coroutines.CancellationException("caller cancelled")
                        listOf(candleStart to BigDecimal("0.0175"))
                    }
                },
                nowProvider = { clock },
            )
            val since = candleStart - durationSeconds
            cache.getOHLC(pair, interval, since, fixedUpTo)

            clock = clock.plusSeconds(3_601)
            cancel = true
            val thrown = runCatching { cache.getOHLC(pair, interval, since, fixedUpTo) }.exceptionOrNull()
            (thrown is CancellationException) shouldBe true
            clock = clock.plusSeconds(3_601)
            cancel = false
            cache.getOHLC(pair, interval, since, fixedUpTo).size shouldBe 1
            counter.get() shouldBe 3
        }

        "the newest covering proof serves requests the older proof also covers" {
            val counter = AtomicInteger(0)
            val now = Instant.now().epochSecond
            val completed = now - 4 * durationSeconds
            val cache = HistoricalOhlcCache(
                fake(
                    listOf(completed to "0.0175", (completed + durationSeconds) to "0.0179"),
                    counter,
                ),
            )

            // Two fetch proofs with different starts; both cover the final request window.
            cache.getOHLC(pair, interval, completed, Instant.ofEpochSecond(completed + durationSeconds))
            cache.getOHLC(pair, interval, completed - durationSeconds, Instant.ofEpochSecond(now))
            val served = cache.getOHLC(pair, interval, completed, Instant.ofEpochSecond(now))

            counter.get() shouldBe 2
            served.size shouldBe 2
        }

        "a failed revalidation never extends coverage beyond its recorded fetch wall" {
            val counter = AtomicInteger(0)
            var clock = Instant.now()
            val candleStart = clock.epochSecond - 2 * durationSeconds
            val candles = mutableListOf(candleStart to BigDecimal("0.0175"))
            var failAll = false
            val cache = HistoricalOhlcCache(
                FakeKrakenService().apply {
                    ohlcSupplier = { _, _, _ ->
                        val call = counter.incrementAndGet()
                        if (failAll) error("live source down")
                        if (call > 2) candles += (candleStart + durationSeconds) to BigDecimal("0.0200")
                        candles.toList()
                    }
                },
                nowProvider = { clock },
            )
            val since = candleStart - durationSeconds
            cache.getOHLC(pair, interval, since, clock)

            // The stored proof expires and its revalidation fails; windows up to the original
            // wall keep serving stale data, but a request beyond that wall must not be served
            // stale evidence as if it were complete.
            clock = clock.plusSeconds(3_601)
            failAll = true
            cache.getOHLC(pair, interval, since, clock.minusSeconds(3_601)).size shouldBe 1
            val thrown = runCatching { cache.getOHLC(pair, interval, since, clock) }.exceptionOrNull()
            (thrown != null) shouldBe true

            // When the source recovers, the uncovered request fetches live fresh evidence.
            failAll = false
            val fresh = cache.getOHLC(pair, interval, since, clock)
            counter.get() shouldBe 4
            fresh.size shouldBe 2
        }

        "concurrent revalidation joiners serve stale data instead of throwing when the flight fails" {
            val counter = AtomicInteger(0)
            var clock = Instant.now()
            val fixedUpTo = clock
            val candleStart = clock.epochSecond - 2 * durationSeconds
            var failAll = false
            val cache = HistoricalOhlcCache(
                FakeKrakenService().apply {
                    ohlcSupplier = { _, _, _ ->
                        counter.incrementAndGet()
                        if (failAll) error("live source down")
                        Thread.sleep(150)
                        listOf(candleStart to BigDecimal("0.0175"))
                    }
                },
                nowProvider = { clock },
            )
            val since = candleStart - durationSeconds
            cache.getOHLC(pair, interval, since, fixedUpTo)

            clock = clock.plusSeconds(3_601)
            failAll = true
            val results = withContext(Dispatchers.IO) {
                (1..4).map { async { runCatching { cache.getOHLC(pair, interval, since, fixedUpTo) } } }.awaitAll()
            }

            counter.get() shouldBe 2
            results.forEach { result ->
                result.isSuccess shouldBe true
                result.getOrNull()?.single()?.second?.compareTo(BigDecimal("0.0175")) shouldBe 0
            }
        }

        "revalidateDependency returns Unchanged with updated deadlines when external candles are unchanged" {
            val counter = AtomicInteger(0)
            var clock = Instant.ofEpochSecond(1_000_000L)
            val candleStart = clock.epochSecond - 2 * durationSeconds
            val candles = listOf(candleStart to BigDecimal("0.0175"))
            val cache = HistoricalOhlcCache(
                FakeKrakenService().apply {
                    ohlcSupplier = { _, _, _ ->
                        counter.incrementAndGet()
                        candles
                    }
                },
                nowProvider = { clock },
            )
            val since = candleStart - durationSeconds

            var recordedDep: ConsumedOhlcDependency? = null
            cache.getOHLC(pair, interval, since, clock) { d ->
                recordedDep = d
            }
            counter.get() shouldBe 1
            val dep = checkNotNull(recordedDep)
            dep shouldBe ConsumedOhlcDependency(
                pair = pair,
                intervalMinutes = interval,
                sinceEpochSecond = since,
                upToEpochSecond = clock.epochSecond,
                fetchedAtEpochSecond = clock.epochSecond,
                freshnessDeadlineEpochSecond = clock.epochSecond + 3600L,
                candleContentHash = HistoricalOhlcCache.consumedCandleContentHash(
                    candles,
                    interval,
                    since,
                    clock.epochSecond,
                ),
            )

            // Advance time past freshness deadline
            clock = clock.plusSeconds(3601L)
            dep.isFresh(clock.epochSecond) shouldBe false

            val result = cache.revalidateDependency(dep)
            counter.get() shouldBe 2
            result shouldBe io.kotest.matchers.types.beInstanceOf<OhlcRevalidationResult.Unchanged>()
            val updated = (result as OhlcRevalidationResult.Unchanged).updatedDependency
            updated.pair shouldBe pair
            updated.fetchedAtEpochSecond shouldBe clock.epochSecond
            updated.freshnessDeadlineEpochSecond shouldBe clock.epochSecond + 3600L
            updated.candleContentHash shouldBe dep.candleContentHash
            updated.isFresh(clock.epochSecond) shouldBe true
        }

        "revalidateDependency returns ContentChanged when external candles differ" {
            val counter = AtomicInteger(0)
            var clock = Instant.ofEpochSecond(1_000_000L)
            val candleStart = clock.epochSecond - 2 * durationSeconds
            var candles = listOf(candleStart to BigDecimal("0.0175"))
            val cache = HistoricalOhlcCache(
                FakeKrakenService().apply {
                    ohlcSupplier = { _, _, _ ->
                        counter.incrementAndGet()
                        candles
                    }
                },
                nowProvider = { clock },
            )
            val since = candleStart - durationSeconds

            var recordedDep: ConsumedOhlcDependency? = null
            cache.getOHLC(pair, interval, since, clock) { d ->
                recordedDep = d
            }
            counter.get() shouldBe 1
            val dep = checkNotNull(recordedDep)

            // Provider updates candle price
            candles = listOf(candleStart to BigDecimal("0.0200"))
            clock = clock.plusSeconds(3601L)

            val result = cache.revalidateDependency(dep)
            counter.get() shouldBe 2
            result shouldBe OhlcRevalidationResult.ContentChanged
        }

        "revalidateDependency single-flights concurrent calls for the same key" {
            val counter = AtomicInteger(0)
            var clock = Instant.ofEpochSecond(1_000_000L)
            val candleStart = clock.epochSecond - 2 * durationSeconds
            val candles = listOf(candleStart to BigDecimal("0.0175"))
            val cache = HistoricalOhlcCache(
                FakeKrakenService().apply {
                    ohlcSupplier = { _, _, _ ->
                        counter.incrementAndGet()
                        Thread.sleep(100)
                        candles
                    }
                },
                nowProvider = { clock },
            )
            val since = candleStart - durationSeconds

            var recordedDep: ConsumedOhlcDependency? = null
            cache.getOHLC(pair, interval, since, clock) { d ->
                recordedDep = d
            }
            counter.get() shouldBe 1
            val dep = checkNotNull(recordedDep)

            clock = clock.plusSeconds(3601L)
            val results = withContext(Dispatchers.IO) {
                (1..4).map { async { cache.revalidateDependency(dep) } }.awaitAll()
            }

            counter.get() shouldBe 2
            results.forEach { result ->
                result shouldBe io.kotest.matchers.types.beInstanceOf<OhlcRevalidationResult.Unchanged>()
            }
        }

        "revalidateDependency serves stale unchanged dependency on provider error" {
            val counter = AtomicInteger(0)
            var clock = Instant.ofEpochSecond(1_000_000L)
            val candleStart = clock.epochSecond - 2 * durationSeconds
            val candles = listOf(candleStart to BigDecimal("0.0175"))
            var fail = false
            val cache = HistoricalOhlcCache(
                FakeKrakenService().apply {
                    ohlcSupplier = { _, _, _ ->
                        counter.incrementAndGet()
                        if (fail) error("provider timeout")
                        candles
                    }
                },
                nowProvider = { clock },
            )
            val since = candleStart - durationSeconds

            var recordedDep: ConsumedOhlcDependency? = null
            cache.getOHLC(pair, interval, since, clock) { d ->
                recordedDep = d
            }
            counter.get() shouldBe 1
            val dep = checkNotNull(recordedDep)

            clock = clock.plusSeconds(3601L)
            fail = true

            val result = cache.revalidateDependency(dep)
            counter.get() shouldBe 2
            result shouldBe OhlcRevalidationResult.Unchanged(dep)

            // Step 2: Immediate repeat within retry pacing window returns unchanged without calling Kraken
            val retryPaced = cache.revalidateDependency(dep)
            counter.get() shouldBe 2
            retryPaced shouldBe OhlcRevalidationResult.Unchanged(dep)
        }

        "revalidateDependency returns early when already fresh from another refresh" {
            val counter = AtomicInteger(0)
            var clock = Instant.ofEpochSecond(1_000_000L)
            val candleStart = clock.epochSecond - 2 * durationSeconds
            val candles = listOf(candleStart to BigDecimal("0.0175"))
            val cache = HistoricalOhlcCache(
                FakeKrakenService().apply {
                    ohlcSupplier = { _, _, _ ->
                        counter.incrementAndGet()
                        candles
                    }
                },
                nowProvider = { clock },
            )
            val since = candleStart - durationSeconds

            var recordedDep: ConsumedOhlcDependency? = null
            cache.getOHLC(pair, interval, since, clock) { d ->
                recordedDep = d
            }
            counter.get() shouldBe 1
            val dep = checkNotNull(recordedDep)

            // Advance time so dep is expired
            clock = clock.plusSeconds(3601L)

            // Another request fetches fresh candles for the same range
            cache.getOHLC(pair, interval, since, clock)
            counter.get() shouldBe 2

            // Now revalidateDependency finds it is already fresh!
            val freshResult = cache.revalidateDependency(dep)
            counter.get() shouldBe 2
            freshResult shouldBe io.kotest.matchers.types.beInstanceOf<OhlcRevalidationResult.Unchanged>()

            // If dependency has a different candle hash than the fresh cache
            val tamperedDep = dep.copy(candleContentHash = "mismatched-hash")
            val contentChanged = cache.revalidateDependency(tamperedDep)
            contentChanged shouldBe OhlcRevalidationResult.ContentChanged
            counter.get() shouldBe 2
        }

        "concurrent revalidateDependency joiner detects content change and handles error" {
            val counter = AtomicInteger(0)
            var clock = Instant.ofEpochSecond(1_000_000L)
            val candleStart = clock.epochSecond - 2 * durationSeconds
            var candles = listOf(candleStart to BigDecimal("0.0175"))
            var throwError = false
            val cache = HistoricalOhlcCache(
                FakeKrakenService().apply {
                    ohlcSupplier = { _, _, _ ->
                        counter.incrementAndGet()
                        Thread.sleep(50)
                        if (throwError) error("network failure")
                        candles
                    }
                },
                nowProvider = { clock },
            )
            val since = candleStart - durationSeconds

            var recordedDep: ConsumedOhlcDependency? = null
            cache.getOHLC(pair, interval, since, clock) { d ->
                recordedDep = d
            }
            val dep = checkNotNull(recordedDep)

            // 1. Content changed on concurrent revalidation
            clock = clock.plusSeconds(3601L)
            candles = listOf(candleStart to BigDecimal("0.0200"))
            val results = withContext(Dispatchers.IO) {
                (1..2).map { async { cache.revalidateDependency(dep) } }.awaitAll()
            }
            results.forEach { it shouldBe OhlcRevalidationResult.ContentChanged }

            // 2. Error on concurrent revalidation
            clock = clock.plusSeconds(3601L)
            throwError = true
            val errorResults = withContext(Dispatchers.IO) {
                (1..2).map { async { cache.revalidateDependency(dep) } }.awaitAll()
            }
            errorResults.forEach {
                it shouldBe io.kotest.matchers.types.beInstanceOf<OhlcRevalidationResult.Unchanged>()
            }
        }

        "revalidateDependency on empty cache revalidates and filters in-progress candle" {
            val counter = AtomicInteger(0)
            val clock = Instant.ofEpochSecond(1_000_000L)
            val candleStart = clock.epochSecond - 2 * durationSeconds
            val inProgressStart = clock.epochSecond - durationSeconds / 2
            val completedCandles = listOf(candleStart to BigDecimal("0.0175"))
            val rawCandles = listOf(
                candleStart to BigDecimal("0.0175"),
                inProgressStart to BigDecimal("0.0180"),
            )
            val cache = HistoricalOhlcCache(
                FakeKrakenService().apply {
                    ohlcSupplier = { _, _, _ ->
                        counter.incrementAndGet()
                        rawCandles
                    }
                },
                nowProvider = { clock },
            )
            val dep = ConsumedOhlcDependency(
                pair = pair,
                intervalMinutes = interval,
                sinceEpochSecond = candleStart - durationSeconds,
                upToEpochSecond = clock.epochSecond,
                fetchedAtEpochSecond = clock.epochSecond - 10_000L,
                freshnessDeadlineEpochSecond = clock.epochSecond - 5_000L,
                candleContentHash = HistoricalOhlcCache.consumedCandleContentHash(
                    completedCandles,
                    interval,
                    candleStart - durationSeconds,
                    clock.epochSecond,
                ),
            )

            val result = cache.revalidateDependency(dep)
            counter.get() shouldBe 1
            result shouldBe io.kotest.matchers.types.beInstanceOf<OhlcRevalidationResult.Unchanged>()
            (result as OhlcRevalidationResult.Unchanged).updatedDependency.fetchedAtEpochSecond shouldBe
                clock.epochSecond
        }

        "revalidateDependency on empty cache serves stale dependency on provider error" {
            val counter = AtomicInteger(0)
            val clock = Instant.ofEpochSecond(1_000_000L)
            val candleStart = clock.epochSecond - 2 * durationSeconds
            val completedCandles = listOf(candleStart to BigDecimal("0.0175"))
            val cache = HistoricalOhlcCache(
                FakeKrakenService().apply {
                    ohlcSupplier = { _, _, _ ->
                        counter.incrementAndGet()
                        error("provider unavailable")
                    }
                },
                nowProvider = { clock },
            )
            val dep = ConsumedOhlcDependency(
                pair = pair,
                intervalMinutes = interval,
                sinceEpochSecond = candleStart - durationSeconds,
                upToEpochSecond = clock.epochSecond,
                fetchedAtEpochSecond = clock.epochSecond - 10_000L,
                freshnessDeadlineEpochSecond = clock.epochSecond - 5_000L,
                candleContentHash = HistoricalOhlcCache.consumedCandleContentHash(
                    completedCandles,
                    interval,
                    candleStart - durationSeconds,
                    clock.epochSecond,
                ),
            )

            val result = cache.revalidateDependency(dep)
            counter.get() shouldBe 1
            result shouldBe OhlcRevalidationResult.Unchanged(dep)
        }

        "consumed dependency ignores future candle growth but detects in-domain change" {
            val counter = AtomicInteger(0)
            val upTo = 1_000_000L
            var clock = Instant.ofEpochSecond(upTo + 3_600L)
            val since = upTo - 86_400L
            val consumedStart = upTo - durationSeconds
            val futureStart = upTo + durationSeconds
            val newerFutureStart = upTo + 2 * durationSeconds
            var extraCandles: List<Pair<Long, BigDecimal>> = emptyList()
            val cache = HistoricalOhlcCache(
                FakeKrakenService().apply {
                    ohlcSupplier = { _, _, _ ->
                        counter.incrementAndGet()
                        listOf(
                            consumedStart to BigDecimal("0.0175"),
                            futureStart to BigDecimal("0.0180"),
                        ) + extraCandles
                    }
                },
                nowProvider = { clock },
            )

            var narrowDep: ConsumedOhlcDependency? = null
            cache.getOHLC(pair, interval, since, Instant.ofEpochSecond(upTo)) { narrowDep = it }
            var wideDep: ConsumedOhlcDependency? = null
            cache.getOHLC(pair, interval, since, Instant.ofEpochSecond(upTo + 3_600L)) { wideDep = it }
            counter.get() shouldBe 1
            val narrow = checkNotNull(narrowDep)
            val wide = checkNotNull(wideDep)
            // The same fetch response produces different dependency hashes: the valuation
            // bound excludes the future candle from the narrow window but not the wide one.
            narrow.upToEpochSecond shouldBe upTo
            (narrow.candleContentHash == wide.candleContentHash) shouldBe false

            // Past the freshness deadline, the provider appends newer candles beyond the valuation instant.
            clock = clock.plusSeconds(3_601L)
            extraCandles = listOf(newerFutureStart to BigDecimal("0.0185"))

            narrow.isFresh(clock.epochSecond) shouldBe false
            cache.revalidateDependency(narrow) shouldBe
                io.kotest.matchers.types.beInstanceOf<OhlcRevalidationResult.Unchanged>()
            // The wide window consumed the newer candle's region, so the same growth is a change.
            cache.revalidateDependency(wide) shouldBe OhlcRevalidationResult.ContentChanged
            // Both revalidations shared the single refresh flight for the range.
            counter.get() shouldBe 2
        }

        "a paced cross-range proof does not gate an unattempted exact-range refetch" {
            val counter = AtomicInteger(0)
            val wall0 = 2_000_000L
            var clock = Instant.ofEpochSecond(wall0)
            val sinceNewest = wall0 - 3 * durationSeconds
            val candleNewest = sinceNewest to BigDecimal("50000")
            val sinceExact = wall0 - 2 * durationSeconds
            var backfilled = false
            var failNext = false
            val cache = HistoricalOhlcCache(
                FakeKrakenService().apply {
                    ohlcSupplier = { _, _, _ ->
                        counter.incrementAndGet()
                        if (failNext) {
                            failNext = false
                            error("transient kraken failure")
                        }
                        if (backfilled) {
                            listOf(candleNewest, sinceExact to BigDecimal("51000"))
                        } else {
                            listOf(candleNewest)
                        }
                    }
                },
                nowProvider = { clock },
            )

            // Newest covering record for the series, then past its freshness deadline.
            var newestDep: ConsumedOhlcDependency? = null
            cache.getOHLC(pair, interval, sinceNewest, Instant.ofEpochSecond(wall0)) { newestDep = it }
            val newest = checkNotNull(newestDep)
            clock = clock.plusSeconds(3_601L)

            // The newest range fails: its own record is paced for a full freshness window.
            failNext = true
            cache.revalidateDependency(newest) shouldBe OhlcRevalidationResult.Unchanged(newest)
            counter.get() shouldBe 2

            // Past the short series backoff but inside the newest record's pacing, an exact
            // older range the cache never attempted must still refetch live — and must
            // detect the provider backfill inside its window.
            clock = clock.plusSeconds(120L)
            backfilled = true
            val exactDep = ConsumedOhlcDependency(
                pair = pair,
                intervalMinutes = interval,
                sinceEpochSecond = sinceExact,
                upToEpochSecond = wall0,
                fetchedAtEpochSecond = wall0,
                freshnessDeadlineEpochSecond = wall0 + 1,
                candleContentHash = "empty",
            )
            val result = cache.revalidateDependency(exactDep)
            counter.get() shouldBe 3
            result shouldBe OhlcRevalidationResult.ContentChanged
        }

        "a series failure briefly paces other ranges, then retries them live" {
            val counter = AtomicInteger(0)
            val wall0 = 2_000_000L
            var clock = Instant.ofEpochSecond(wall0)
            val sinceFailed = wall0 - 3 * durationSeconds
            var failNext = false
            val cache = HistoricalOhlcCache(
                FakeKrakenService().apply {
                    ohlcSupplier = { _, _, _ ->
                        counter.incrementAndGet()
                        if (failNext) {
                            failNext = false
                            error("transient kraken failure")
                        }
                        listOf(sinceFailed to BigDecimal("50000"))
                    }
                },
                nowProvider = { clock },
            )

            var failedDep: ConsumedOhlcDependency? = null
            cache.getOHLC(pair, interval, sinceFailed, Instant.ofEpochSecond(wall0)) { failedDep = it }
            clock = clock.plusSeconds(3_601L)
            failNext = true
            cache.revalidateDependency(checkNotNull(failedDep))
            val callsAfterFailure = counter.get()

            // Another range of the same series skips its live call during the short outage
            // backoff, then retries once the backoff lapses.
            val otherDep = ConsumedOhlcDependency(
                pair = pair,
                intervalMinutes = interval,
                sinceEpochSecond = wall0 - 2 * durationSeconds,
                upToEpochSecond = wall0,
                fetchedAtEpochSecond = wall0,
                freshnessDeadlineEpochSecond = wall0 + 1,
                candleContentHash = "empty",
            )
            val paced = cache.revalidateDependency(otherDep)
            paced shouldBe OhlcRevalidationResult.Unchanged(otherDep)
            counter.get() shouldBe callsAfterFailure

            clock = clock.plusSeconds(61L)
            val retried = cache.revalidateDependency(otherDep)
            counter.get() shouldBe callsAfterFailure + 1
            retried shouldBe io.kotest.matchers.types.beInstanceOf<OhlcRevalidationResult.Unchanged>()
        }

        "a failed range keeps its own long pacing for retries of that same range" {
            val counter = AtomicInteger(0)
            val wall0 = 2_000_000L
            var clock = Instant.ofEpochSecond(wall0)
            val since = wall0 - 3 * durationSeconds
            var failNext = false
            val cache = HistoricalOhlcCache(
                FakeKrakenService().apply {
                    ohlcSupplier = { _, _, _ ->
                        counter.incrementAndGet()
                        if (failNext) {
                            failNext = false
                            error("transient kraken failure")
                        }
                        listOf(since to BigDecimal("50000"))
                    }
                },
                nowProvider = { clock },
            )

            var recordedDep: ConsumedOhlcDependency? = null
            cache.getOHLC(pair, interval, since, Instant.ofEpochSecond(wall0)) { recordedDep = it }
            val dep = checkNotNull(recordedDep)
            clock = clock.plusSeconds(3_601L)
            failNext = true
            cache.revalidateDependency(dep) shouldBe OhlcRevalidationResult.Unchanged(dep)
            counter.get() shouldBe 2

            // Past the short series backoff but inside the range's own pacing: no retry.
            clock = clock.plusSeconds(120L)
            cache.revalidateDependency(dep) shouldBe OhlcRevalidationResult.Unchanged(dep)
            counter.get() shouldBe 2
        }

        "consumed hash delimits fields so crafted concatenations cannot collide" {
            // Undelimited "start+close" concatenation collides here: both join to the same
            // digit string, because the second start is all ones. Delimiters keep them apart.
            val firstStart = 1000L
            val secondStart = 1_111_111_111L
            val since = 0L
            val upTo = secondStart + durationSeconds
            val left = listOf(firstStart to BigDecimal("1"), secondStart to BigDecimal("15"))
            val right = listOf(firstStart to BigDecimal("11"), secondStart to BigDecimal("5"))
            val undelimitedLeft = left.joinToString("") { (start, close) -> "$start$close" }
            val undelimitedRight = right.joinToString("") { (start, close) -> "$start$close" }
            undelimitedLeft shouldBe undelimitedRight

            val hashLeft = HistoricalOhlcCache.consumedCandleContentHash(left, interval, since, upTo)
            val hashRight = HistoricalOhlcCache.consumedCandleContentHash(right, interval, since, upTo)
            (hashLeft == hashRight) shouldBe false
        }

        "revalidation joiner without a coverage proof fails closed to a content change" {
            val counter = AtomicInteger(0)
            val wall = 2_000_000L
            val clock = Instant.ofEpochSecond(wall)
            val candleStart = wall - 2 * durationSeconds
            val since = candleStart - durationSeconds
            val candle = candleStart to BigDecimal("0.0175")
            val cache = HistoricalOhlcCache(
                FakeKrakenService().apply {
                    ohlcSupplier = { _, _, _ ->
                        counter.incrementAndGet()
                        Thread.sleep(150)
                        listOf(candle)
                    }
                },
                nowProvider = { clock },
            )
            // Both deps carry matching hashes: only the joiner's missing coverage proof (its
            // future-dated upTo) may report a change.
            val pastDep = ConsumedOhlcDependency(
                pair = pair,
                intervalMinutes = interval,
                sinceEpochSecond = since,
                upToEpochSecond = wall,
                fetchedAtEpochSecond = wall - 10_000L,
                freshnessDeadlineEpochSecond = wall - 5_000L,
                candleContentHash = HistoricalOhlcCache.consumedCandleContentHash(
                    listOf(candle),
                    interval,
                    since,
                    wall,
                ),
            )
            val futureUpTo = wall + 3_600L
            val futureDep = pastDep.copy(
                upToEpochSecond = futureUpTo,
                candleContentHash = HistoricalOhlcCache.consumedCandleContentHash(
                    listOf(candle),
                    interval,
                    since,
                    futureUpTo,
                ),
            )

            val (initiatorResult, joinerResult) = withContext(Dispatchers.IO) {
                val initiator = async { cache.revalidateDependency(pastDep) }
                delay(50)
                val joiner = async { cache.revalidateDependency(futureDep) }
                initiator.await() to joiner.await()
            }
            counter.get() shouldBe 1
            initiatorResult shouldBe io.kotest.matchers.types.beInstanceOf<OhlcRevalidationResult.Unchanged>()
            joinerResult shouldBe OhlcRevalidationResult.ContentChanged
        }

        "revalidation initiator without a coverage proof fails closed to a content change" {
            val counter = AtomicInteger(0)
            val wall = 2_000_000L
            val clock = Instant.ofEpochSecond(wall)
            val candleStart = wall - 2 * durationSeconds
            val since = candleStart - durationSeconds
            val candle = candleStart to BigDecimal("0.0175")
            val cache = HistoricalOhlcCache(
                FakeKrakenService().apply {
                    ohlcSupplier = { _, _, _ ->
                        counter.incrementAndGet()
                        listOf(candle)
                    }
                },
                nowProvider = { clock },
            )
            // Matching hash over a future-dated window: the live fetch cannot cover the
            // window, so the initiator must report a change like a joiner would.
            val futureUpTo = wall + 3_600L
            val dep = ConsumedOhlcDependency(
                pair = pair,
                intervalMinutes = interval,
                sinceEpochSecond = since,
                upToEpochSecond = futureUpTo,
                fetchedAtEpochSecond = wall - 10_000L,
                freshnessDeadlineEpochSecond = wall - 5_000L,
                candleContentHash = HistoricalOhlcCache.consumedCandleContentHash(
                    listOf(candle),
                    interval,
                    since,
                    futureUpTo,
                ),
            )

            val result = cache.revalidateDependency(dep)
            counter.get() shouldBe 1
            result shouldBe OhlcRevalidationResult.ContentChanged
        }

        "backfill inside the consumed window invalidates an empty dependency" {
            val counter = AtomicInteger(0)
            val upTo = 1_000_000L
            var clock = Instant.ofEpochSecond(upTo + 3_600L)
            val since = upTo - 86_400L
            var backfilled = false
            val cache = HistoricalOhlcCache(
                FakeKrakenService().apply {
                    ohlcSupplier = { _, _, _ ->
                        counter.incrementAndGet()
                        if (backfilled) {
                            listOf(
                                (upTo - durationSeconds) to BigDecimal("0.0175"),
                                (upTo + durationSeconds) to BigDecimal("0.0180"),
                            )
                        } else {
                            listOf((upTo + durationSeconds) to BigDecimal("0.0180"))
                        }
                    }
                },
                nowProvider = { clock },
            )

            var recordedDep: ConsumedOhlcDependency? = null
            cache.getOHLC(pair, interval, since, Instant.ofEpochSecond(upTo)) { recordedDep = it }
            val dep = checkNotNull(recordedDep)
            // Negative evidence: no qualifying candle existed inside the consumed window.
            dep.candleContentHash shouldBe "empty"

            clock = clock.plusSeconds(3_601L)
            backfilled = true
            cache.revalidateDependency(dep) shouldBe OhlcRevalidationResult.ContentChanged
            counter.get() shouldBe 2
        }

        "revalidateDependency refetches its own range despite a fresher cross-range proof" {
            val counter = AtomicInteger(0)
            val wall0 = 2_000_000L
            var clock = Instant.ofEpochSecond(wall0)
            // Valuations a day apart so neither consumed candle falls in the other's window.
            val upTo1 = wall0 - 93_600L
            val upTo2 = wall0 - 3_600L
            val since1 = upTo1 - 86_400L
            val since2 = upTo2 - 86_400L
            val cache = HistoricalOhlcCache(
                FakeKrakenService().apply {
                    ohlcSupplier = { _, _, since ->
                        counter.incrementAndGet()
                        // Complete responses: every stored candle inside the fetched domain
                        // is returned, so authoritative replacement never deletes real
                        // evidence the way a partial single-candle fixture would.
                        listOf(
                            (upTo1 - durationSeconds) to BigDecimal("0.0175"),
                            (upTo2 - durationSeconds) to BigDecimal("0.0175"),
                        ).filter { (start, _) ->
                            (since ?: wall0) <= start && start + durationSeconds < clock.epochSecond
                        }
                    }
                },
                nowProvider = { clock },
            )

            var dep2: ConsumedOhlcDependency? = null
            cache.getOHLC(pair, interval, since2, Instant.ofEpochSecond(upTo2)) { dep2 = it }
            var dep1: ConsumedOhlcDependency? = null
            cache.getOHLC(pair, interval, since1, Instant.ofEpochSecond(upTo1)) { dep1 = it }
            counter.get() shouldBe 2
            val first = checkNotNull(dep1)
            val second = checkNotNull(dep2)

            clock = clock.plusSeconds(3_601L)
            cache.revalidateDependency(first) shouldBe
                io.kotest.matchers.types.beInstanceOf<OhlcRevalidationResult.Unchanged>()
            // The refreshed smaller-since proof covers the second window, but its response
            // cannot prove the second window's stored candles are current: the second range
            // must still refetch itself rather than compare stale union content as unchanged.
            cache.revalidateDependency(second) shouldBe
                io.kotest.matchers.types.beInstanceOf<OhlcRevalidationResult.Unchanged>()
            counter.get() shouldBe 4
        }

        "revalidateDependency serves fresh exact proof from persistent store after restart" {
            val repository = SqliteHistoricalOhlcRepositoryImpl(DatabaseConfig.init(":memory:"))
            val firstCounter = AtomicInteger(0)
            val secondCounter = AtomicInteger(0)
            val wall0 = 2_000_000L
            val clock = Instant.ofEpochSecond(wall0)
            val upTo = wall0 - 3_600L
            val since = upTo - 86_400L
            val candleStart = upTo - durationSeconds

            val first = HistoricalOhlcCache(
                fake(listOf(candleStart to "0.0175"), firstCounter),
                persistentRepository = repository,
                nowProvider = { clock },
            )
            var recorded: ConsumedOhlcDependency? = null
            first.getOHLC(pair, interval, since, Instant.ofEpochSecond(upTo)) { recorded = it }
            checkNotNull(recorded)
            firstCounter.get() shouldBe 1

            // Restart: empty memory, same durable store. The exact persisted proof is fresh,
            // so revalidation compares from the store without a live call.
            val restarted = HistoricalOhlcCache(
                fake(listOf(candleStart to "0.0175"), secondCounter),
                persistentRepository = repository,
                nowProvider = { clock },
            )
            val result = restarted.revalidateDependency(checkNotNull(recorded))
            result shouldBe io.kotest.matchers.types.beInstanceOf<OhlcRevalidationResult.Unchanged>()
            secondCounter.get() shouldBe 0
        }

        "consumed hash ignores candles starting before since" {
            val since = 1_000_000L
            val upTo = since + 86_400L
            val stale = (since - durationSeconds) to BigDecimal("0.0170")
            val consumed = (upTo - durationSeconds) to BigDecimal("0.0175")
            HistoricalOhlcCache.consumedCandleContentHash(listOf(stale), interval, since, upTo) shouldBe "empty"
            HistoricalOhlcCache.consumedCandleContentHash(listOf(stale, consumed), interval, since, upTo) shouldBe
                HistoricalOhlcCache.consumedCandleContentHash(listOf(consumed), interval, since, upTo)
        }

        "backfill outside the consumed window leaves an empty dependency unchanged" {
            val counter = AtomicInteger(0)
            val upTo = 1_000_000L
            var clock = Instant.ofEpochSecond(upTo + 3_600L)
            val since = upTo - 86_400L
            var backfilled = false
            val cache = HistoricalOhlcCache(
                FakeKrakenService().apply {
                    ohlcSupplier = { _, _, _ ->
                        counter.incrementAndGet()
                        if (backfilled) {
                            listOf((upTo + durationSeconds) to BigDecimal("0.0180"))
                        } else {
                            emptyList()
                        }
                    }
                },
                nowProvider = { clock },
            )

            var recordedDep: ConsumedOhlcDependency? = null
            cache.getOHLC(pair, interval, since, Instant.ofEpochSecond(upTo)) { recordedDep = it }
            val dep = checkNotNull(recordedDep)
            dep.candleContentHash shouldBe "empty"

            // Empty proofs revalidate on the short cadence.
            clock = clock.plusSeconds(601L)
            backfilled = true
            val result = cache.revalidateDependency(dep)
            result shouldBe io.kotest.matchers.types.beInstanceOf<OhlcRevalidationResult.Unchanged>()
            (result as OhlcRevalidationResult.Unchanged).updatedDependency.candleContentHash shouldBe "empty"
            counter.get() shouldBe 2
        }

        "authoritative empty revalidation removes the stale candle from memory and SQLite" {
            val counter = AtomicInteger(0)
            val wall0 = 2_000_000L
            var clock = Instant.ofEpochSecond(wall0)
            val upTo = wall0 - 3_600L
            val since = upTo - 86_400L
            val candleStart = upTo - durationSeconds
            var response: List<Pair<Long, BigDecimal>> = listOf(candleStart to BigDecimal("0.0175"))
            val repository = SqliteHistoricalOhlcRepositoryImpl(DatabaseConfig.init(":memory:"))
            val cache = HistoricalOhlcCache(
                FakeKrakenService().apply {
                    ohlcSupplier = { _, _, _ ->
                        counter.incrementAndGet()
                        response
                    }
                },
                persistentRepository = repository,
                nowProvider = { clock },
            )

            var recorded: ConsumedOhlcDependency? = null
            cache.getOHLC(pair, interval, since, Instant.ofEpochSecond(upTo)) { recorded = it }
            val dep = checkNotNull(recorded)
            counter.get() shouldBe 1

            // The provider authoritatively retracts the only candle: revalidation reports
            // ContentChanged and the stale close vanishes from both views.
            clock = clock.plusSeconds(3_601L)
            response = emptyList()
            val result = cache.revalidateDependency(dep)
            result shouldBe io.kotest.matchers.types.beInstanceOf<OhlcRevalidationResult.ContentChanged>()
            counter.get() shouldBe 2

            cache.getOHLC(pair, interval, since, Instant.ofEpochSecond(upTo)) shouldBe emptyList()
            counter.get() shouldBe 2
            repository.loadCovered(pair, interval, since, upTo)?.candles shouldBe emptyList()
        }

        "partial removal deletes only the absent consumed candle" {
            val counter = AtomicInteger(0)
            val wall0 = 2_000_000L
            var clock = Instant.ofEpochSecond(wall0)
            val upTo = wall0 - 3_600L
            val since = upTo - 86_400L
            val keptStart = upTo - 2 * durationSeconds
            val removedStart = upTo - durationSeconds
            var response: List<Pair<Long, BigDecimal>> = listOf(
                keptStart to BigDecimal("0.0175"),
                removedStart to BigDecimal("0.0179"),
            )
            val repository = SqliteHistoricalOhlcRepositoryImpl(DatabaseConfig.init(":memory:"))
            val cache = HistoricalOhlcCache(
                FakeKrakenService().apply {
                    ohlcSupplier = { _, _, _ ->
                        counter.incrementAndGet()
                        response
                    }
                },
                persistentRepository = repository,
                nowProvider = { clock },
            )

            var recorded: ConsumedOhlcDependency? = null
            cache.getOHLC(pair, interval, since, Instant.ofEpochSecond(upTo)) { recorded = it }
            val dep = checkNotNull(recorded)

            clock = clock.plusSeconds(3_601L)
            response = listOf(keptStart to BigDecimal("0.0175"))
            val result = cache.revalidateDependency(dep)
            result shouldBe io.kotest.matchers.types.beInstanceOf<OhlcRevalidationResult.ContentChanged>()
            counter.get() shouldBe 2

            cache.getOHLC(pair, interval, since, Instant.ofEpochSecond(upTo)) shouldBe
                listOf(keptStart to BigDecimal("0.0175"))
            counter.get() shouldBe 2
            repository.loadCovered(pair, interval, since, upTo)?.candles?.map { it.first } shouldBe
                listOf(keptStart)
        }

        "removal outside the consumed window leaves the dependency unchanged" {
            val counter = AtomicInteger(0)
            val wall0 = 2_000_000L
            var clock = Instant.ofEpochSecond(wall0)
            val upTo = wall0 - 3_600L
            val since = upTo - 86_400L
            val consumedStart = upTo - durationSeconds
            // Completed (close wall0 - 900) but closing after upTo, so never consumed.
            val futureStart = wall0 - 2 * durationSeconds
            var response: List<Pair<Long, BigDecimal>> = listOf(
                consumedStart to BigDecimal("0.0175"),
                futureStart to BigDecimal("0.0180"),
            )
            val repository = SqliteHistoricalOhlcRepositoryImpl(DatabaseConfig.init(":memory:"))
            val cache = HistoricalOhlcCache(
                FakeKrakenService().apply {
                    ohlcSupplier = { _, _, _ ->
                        counter.incrementAndGet()
                        response
                    }
                },
                persistentRepository = repository,
                nowProvider = { clock },
            )

            var recorded: ConsumedOhlcDependency? = null
            cache.getOHLC(pair, interval, since, Instant.ofEpochSecond(upTo)) { recorded = it }
            val dep = checkNotNull(recorded)

            // The provider retracts only the unconsumed future candle: replacement deletes
            // the row from both views, but the consumed hash is unchanged.
            clock = clock.plusSeconds(3_601L)
            response = listOf(consumedStart to BigDecimal("0.0175"))
            val result = cache.revalidateDependency(dep)
            result shouldBe io.kotest.matchers.types.beInstanceOf<OhlcRevalidationResult.Unchanged>()
            (result as OhlcRevalidationResult.Unchanged).updatedDependency.candleContentHash shouldBe
                dep.candleContentHash
            counter.get() shouldBe 2

            cache.getOHLC(pair, interval, since, Instant.ofEpochSecond(upTo)) shouldBe
                listOf(consumedStart to BigDecimal("0.0175"))
            counter.get() shouldBe 2
            repository.loadCovered(pair, interval, since, upTo)?.candles?.map { it.first } shouldBe
                listOf(consumedStart)
        }

        "correction plus removal converges exactly to the fresh response" {
            val counter = AtomicInteger(0)
            val wall0 = 2_000_000L
            var clock = Instant.ofEpochSecond(wall0)
            val upTo = wall0 - 3_600L
            val since = upTo - 86_400L
            val correctedStart = upTo - 2 * durationSeconds
            val removedStart = upTo - durationSeconds
            val backfilledStart = upTo - 3 * durationSeconds
            var response: List<Pair<Long, BigDecimal>> = listOf(
                correctedStart to BigDecimal("0.0175"),
                removedStart to BigDecimal("0.0179"),
            )
            val repository = SqliteHistoricalOhlcRepositoryImpl(DatabaseConfig.init(":memory:"))
            val cache = HistoricalOhlcCache(
                FakeKrakenService().apply {
                    ohlcSupplier = { _, _, _ ->
                        counter.incrementAndGet()
                        response
                    }
                },
                persistentRepository = repository,
                nowProvider = { clock },
            )

            var recorded: ConsumedOhlcDependency? = null
            cache.getOHLC(pair, interval, since, Instant.ofEpochSecond(upTo)) { recorded = it }
            val dep = checkNotNull(recorded)

            clock = clock.plusSeconds(3_601L)
            response = listOf(
                backfilledStart to BigDecimal("0.0200"),
                correctedStart to BigDecimal("0.0199"),
            )
            val result = cache.revalidateDependency(dep)
            result shouldBe io.kotest.matchers.types.beInstanceOf<OhlcRevalidationResult.ContentChanged>()

            val expectedMemory = listOf(
                backfilledStart to BigDecimal("0.0200"),
                correctedStart to BigDecimal("0.0199"),
            )
            cache.getOHLC(pair, interval, since, Instant.ofEpochSecond(upTo)) shouldBe expectedMemory
            val stored = repository.loadCovered(pair, interval, since, upTo)?.candles
            stored?.map { it.first } shouldBe listOf(backfilledStart, correctedStart)
            stored?.single { it.first == backfilledStart }?.second?.compareTo(BigDecimal("0.0200")) shouldBe 0
            stored?.single { it.first == correctedStart }?.second?.compareTo(BigDecimal("0.0199")) shouldBe 0
        }

        "restart after removal sees only fresh authoritative candles" {
            val counter = AtomicInteger(0)
            val wall0 = 2_000_000L
            var clock = Instant.ofEpochSecond(wall0)
            val upTo = wall0 - 3_600L
            val since = upTo - 86_400L
            val candleStart = upTo - durationSeconds
            var response: List<Pair<Long, BigDecimal>> = listOf(candleStart to BigDecimal("0.0175"))
            val repository = SqliteHistoricalOhlcRepositoryImpl(DatabaseConfig.init(":memory:"))
            val kraken = FakeKrakenService().apply {
                ohlcSupplier = { _, _, _ ->
                    counter.incrementAndGet()
                    response
                }
            }
            val cache = HistoricalOhlcCache(kraken, persistentRepository = repository, nowProvider = { clock })

            var recorded: ConsumedOhlcDependency? = null
            cache.getOHLC(pair, interval, since, Instant.ofEpochSecond(upTo)) { recorded = it }
            val dep = checkNotNull(recorded)

            clock = clock.plusSeconds(3_601L)
            response = emptyList()
            cache.revalidateDependency(dep) shouldBe
                io.kotest.matchers.types.beInstanceOf<OhlcRevalidationResult.ContentChanged>()
            counter.get() shouldBe 2

            // Restart: empty memory over the same durable store. The persisted proof is
            // fresh, so the restarted cache serves the post-removal evidence with no call.
            val restarted = HistoricalOhlcCache(kraken, persistentRepository = repository, nowProvider = { clock })
            var restartedDep: ConsumedOhlcDependency? = null
            restarted.getOHLC(pair, interval, since, Instant.ofEpochSecond(upTo)) { restartedDep = it } shouldBe
                emptyList()
            counter.get() shouldBe 2
            checkNotNull(restartedDep).isFresh(clock.epochSecond) shouldBe true
        }

        "full-page responses replace only their covered span" {
            val counter = AtomicInteger(0)
            val wall = 2_000_000L
            val clock = Instant.ofEpochSecond(wall)
            val earlyStart = 900_000L
            val since = 1_000_000L

            // A full 720-candle page proves only its covered [first, last] span: a later
            // full page retracts one in-span candle while candles outside the span stay.
            // Pages arrive out of order and start after their request since, pinning the
            // span-max/span-min domain for responses the provider may order freely.
            fun page(skipStart: Long?, extraStart: Long?): List<Pair<Long, BigDecimal>> {
                val starts = (0 until 720).map { i -> since + i * durationSeconds }.toMutableList()
                starts[1] = since
                starts[0] = since + durationSeconds
                return starts.filter { it != skipStart }.map { it to BigDecimal("0.0175") } +
                    (extraStart?.let { listOf(it to BigDecimal("0.0185")) } ?: emptyList())
            }
            val removedStart = since + 500 * durationSeconds
            val addedStart = since + 720 * durationSeconds
            // Difference-region witnesses: below the page span but inside [since, wall),
            // and above the page span but completed. Whole-domain deletion would remove
            // both; span-restricted replacement must keep them.
            val belowSpanStart = since - 5 * durationSeconds
            val aboveSpanStart = addedStart + 100 * durationSeconds
            val pageOne = page(null, null)
            val pageTwo = page(removedStart, addedStart)
            pageOne.size shouldBe 720
            pageTwo.size shouldBe 720
            val responses = mutableListOf(
                listOf(
                    earlyStart to BigDecimal("0.0170"),
                    belowSpanStart to BigDecimal("0.0171"),
                    aboveSpanStart to BigDecimal("0.0172"),
                ),
                pageOne,
                pageTwo,
            )
            val repository = SqliteHistoricalOhlcRepositoryImpl(DatabaseConfig.init(":memory:"))
            val cache = HistoricalOhlcCache(
                FakeKrakenService().apply {
                    ohlcSupplier = { _, _, _ ->
                        counter.incrementAndGet()
                        responses.removeFirst()
                    }
                },
                persistentRepository = repository,
                nowProvider = { clock },
            )

            cache.getOHLC(pair, interval, earlyStart, Instant.ofEpochSecond(wall))
            // Beyond-wall windows defeat cross-range coverage so each range fetches live.
            cache.getOHLC(pair, interval, since, Instant.ofEpochSecond(wall + 7_200L))
            cache.getOHLC(pair, interval, since - 10 * durationSeconds, Instant.ofEpochSecond(wall + 7_200L))
            counter.get() shouldBe 3

            val memory = cache.getOHLC(pair, interval, earlyStart, Instant.ofEpochSecond(wall))
            counter.get() shouldBe 3
            memory.size shouldBe 723
            memory.none { it.first == removedStart } shouldBe true
            memory.any { it.first == addedStart } shouldBe true
            memory.any { it.first == since } shouldBe true
            memory.any { it.first == earlyStart } shouldBe true
            memory.any { it.first == belowSpanStart } shouldBe true
            memory.any { it.first == aboveSpanStart } shouldBe true

            val stored = repository.loadCovered(pair, interval, since, wall)?.candles.orEmpty()
            stored.none { it.first == removedStart } shouldBe true
            stored.any { it.first == addedStart } shouldBe true
        }

        "out-of-order older fetch keeps evidence witnessed later" {
            val counter = AtomicInteger(0)
            val wallNew = 2_000_000L
            val wallOld = 1_900_000L
            var clock = Instant.ofEpochSecond(wallNew)
            val firstStart = 1_890_000L
            val secondStart = 1_890_900L
            val sinceNew = 1_880_000L
            val sinceOld = 1_885_000L
            var response: List<Pair<Long, BigDecimal>> = listOf(
                firstStart to BigDecimal("0.0175"),
                secondStart to BigDecimal("0.0179"),
            )
            val cache = HistoricalOhlcCache(
                FakeKrakenService().apply {
                    ohlcSupplier = { _, _, _ ->
                        counter.incrementAndGet()
                        response
                    }
                },
                nowProvider = { clock },
            )

            cache.getOHLC(pair, interval, sinceNew, Instant.ofEpochSecond(wallNew))
            counter.get() shouldBe 1

            // An older overlapping fetch completes late: its correction must not
            // overwrite the newer close and its omission must not delete the newer row.
            clock = Instant.ofEpochSecond(wallOld)
            response = listOf(firstStart to BigDecimal("0.0199"))
            cache.getOHLC(pair, interval, sinceOld, Instant.ofEpochSecond(wallNew + 100L))
            counter.get() shouldBe 2

            clock = Instant.ofEpochSecond(wallNew)
            cache.getOHLC(pair, interval, sinceNew, Instant.ofEpochSecond(wallNew)) shouldBe
                listOf(
                    firstStart to BigDecimal("0.0175"),
                    secondStart to BigDecimal("0.0179"),
                )
            counter.get() shouldBe 2
        }

        "same range refetched at an older wall records alongside the newer proof" {
            val counter = AtomicInteger(0)
            val wallNew = 2_000_000L
            val wallOld = 1_900_000L
            var clock = Instant.ofEpochSecond(wallNew)
            val since = 1_880_000L
            val candleStart = 1_890_000L
            val cache = HistoricalOhlcCache(
                fake(listOf(candleStart to "0.0175"), counter),
                nowProvider = { clock },
            )

            cache.getOHLC(pair, interval, since, Instant.ofEpochSecond(wallNew))
            counter.get() shouldBe 1
            // The same range completes late at an older wall: the newer proof survives
            // (newer evidence wins) and the older proof is recorded alongside it.
            clock = Instant.ofEpochSecond(wallOld)
            cache.getOHLC(pair, interval, since, Instant.ofEpochSecond(wallNew + 100L))
            counter.get() shouldBe 2

            // A window the older proof covers reads expired at the newer wall, so one
            // revalidation refreshes the range and prunes the older proof again.
            clock = Instant.ofEpochSecond(wallNew)
            cache.getOHLC(pair, interval, since, Instant.ofEpochSecond(wallOld)) shouldBe
                listOf(candleStart to BigDecimal("0.0175"))
            counter.get() shouldBe 3
        }

        "repeat fetch at the same wall does not duplicate the coverage proof" {
            val counter = AtomicInteger(0)
            val wall = 2_000_000L
            val clock = Instant.ofEpochSecond(wall)
            val since = wall - 86_400L
            val candleStart = wall - 2 * durationSeconds
            val cache = HistoricalOhlcCache(
                fake(listOf(candleStart to "0.0175"), counter),
                nowProvider = { clock },
            )

            cache.getOHLC(pair, interval, since, Instant.ofEpochSecond(wall - 3_600L))
            // A wider window the first proof cannot cover refetches at the same wall;
            // the identical proof is recorded once and both windows keep serving.
            cache.getOHLC(pair, interval, since, Instant.ofEpochSecond(wall + 7_200L)) shouldBe
                listOf(candleStart to BigDecimal("0.0175"))
            counter.get() shouldBe 2
            cache.getOHLC(pair, interval, since, Instant.ofEpochSecond(wall - 3_600L)) shouldBe
                listOf(candleStart to BigDecimal("0.0175"))
            counter.get() shouldBe 2
        }

        "historical consumed candle with recent future candles gets the historical TTL" {
            val counter = AtomicInteger(0)
            val wall0 = 2_000_000L
            val clock = Instant.ofEpochSecond(wall0)
            val upTo = wall0 - 100_000L
            val since = upTo - 86_400L
            val consumedStart = upTo - durationSeconds
            // Completed but closing after upTo: must influence neither hash nor cadence.
            val futureStart = wall0 - 2 * durationSeconds
            val cache = HistoricalOhlcCache(
                FakeKrakenService().apply {
                    ohlcSupplier = { _, _, _ ->
                        counter.incrementAndGet()
                        listOf(
                            consumedStart to BigDecimal("0.0175"),
                            futureStart to BigDecimal("0.0180"),
                        )
                    }
                },
                nowProvider = { clock },
            )

            var recorded: ConsumedOhlcDependency? = null
            cache.getOHLC(pair, interval, since, Instant.ofEpochSecond(upTo)) { recorded = it }
            val dep = checkNotNull(recorded)
            counter.get() shouldBe 1
            dep.candleContentHash shouldBe HistoricalOhlcCache.consumedCandleContentHash(
                listOf(consumedStart to BigDecimal("0.0175")),
                interval,
                since,
                upTo,
            )
            dep.freshnessDeadlineEpochSecond - dep.fetchedAtEpochSecond shouldBe 604_800L
        }

        "provider appends future candles: hash and historical cadence unchanged" {
            val counter = AtomicInteger(0)
            val wall0 = 2_000_000L
            var clock = Instant.ofEpochSecond(wall0)
            val upTo = wall0 - 100_000L
            val since = upTo - 86_400L
            val consumedStart = upTo - durationSeconds
            val futureStart = wall0 - 2 * durationSeconds
            var response: List<Pair<Long, BigDecimal>> = listOf(
                consumedStart to BigDecimal("0.0175"),
                futureStart to BigDecimal("0.0180"),
            )
            val cache = HistoricalOhlcCache(
                FakeKrakenService().apply {
                    ohlcSupplier = { _, _, _ ->
                        counter.incrementAndGet()
                        response
                    }
                },
                nowProvider = { clock },
            )

            var recorded: ConsumedOhlcDependency? = null
            cache.getOHLC(pair, interval, since, Instant.ofEpochSecond(upTo)) { recorded = it }
            val dep = checkNotNull(recorded)
            dep.freshnessDeadlineEpochSecond - dep.fetchedAtEpochSecond shouldBe 604_800L

            // Eight days later the historical dependency expires; the provider has grown
            // more future candles while the consumed close is untouched.
            clock = clock.plusSeconds(8 * 86_400L)
            dep.isFresh(clock.epochSecond) shouldBe false
            val grownStart = clock.epochSecond - 2 * durationSeconds
            response = response + (grownStart to BigDecimal("0.0185"))
            val result = cache.revalidateDependency(dep)
            result shouldBe io.kotest.matchers.types.beInstanceOf<OhlcRevalidationResult.Unchanged>()
            val updated = (result as OhlcRevalidationResult.Unchanged).updatedDependency
            updated.candleContentHash shouldBe dep.candleContentHash
            updated.freshnessDeadlineEpochSecond - updated.fetchedAtEpochSecond shouldBe 604_800L
            counter.get() shouldBe 2
        }

        "empty consumed window with future candles gets the empty-result TTL" {
            val counter = AtomicInteger(0)
            val wall0 = 2_000_000L
            val clock = Instant.ofEpochSecond(wall0)
            val upTo = wall0 - 100_000L
            val since = upTo - 86_400L
            val futureStart = wall0 - 2 * durationSeconds
            val cache = HistoricalOhlcCache(
                FakeKrakenService().apply {
                    ohlcSupplier = { _, _, _ ->
                        counter.incrementAndGet()
                        listOf(futureStart to BigDecimal("0.0180"))
                    }
                },
                nowProvider = { clock },
            )

            var recorded: ConsumedOhlcDependency? = null
            cache.getOHLC(pair, interval, since, Instant.ofEpochSecond(upTo)) { recorded = it }
            val dep = checkNotNull(recorded)
            dep.candleContentHash shouldBe "empty"
            dep.freshnessDeadlineEpochSecond - dep.fetchedAtEpochSecond shouldBe 600L
        }

        "recent consumed candle gets the recent TTL" {
            val counter = AtomicInteger(0)
            val wall0 = 2_000_000L
            val clock = Instant.ofEpochSecond(wall0)
            val upTo = wall0 - 3_600L
            val since = upTo - 86_400L
            val consumedStart = upTo - durationSeconds
            val cache = HistoricalOhlcCache(
                FakeKrakenService().apply {
                    ohlcSupplier = { _, _, _ ->
                        counter.incrementAndGet()
                        listOf(consumedStart to BigDecimal("0.0175"))
                    }
                },
                nowProvider = { clock },
            )

            var recorded: ConsumedOhlcDependency? = null
            cache.getOHLC(pair, interval, since, Instant.ofEpochSecond(upTo)) { recorded = it }
            val dep = checkNotNull(recorded)
            dep.freshnessDeadlineEpochSecond - dep.fetchedAtEpochSecond shouldBe 3_600L
        }

        "old consumed candle without future growth gets the historical TTL" {
            val counter = AtomicInteger(0)
            val wall0 = 2_000_000L
            val clock = Instant.ofEpochSecond(wall0)
            val upTo = wall0 - 100_000L
            val since = upTo - 86_400L
            val consumedStart = upTo - durationSeconds
            val cache = HistoricalOhlcCache(
                FakeKrakenService().apply {
                    ohlcSupplier = { _, _, _ ->
                        counter.incrementAndGet()
                        listOf(consumedStart to BigDecimal("0.0175"))
                    }
                },
                nowProvider = { clock },
            )

            var recorded: ConsumedOhlcDependency? = null
            cache.getOHLC(pair, interval, since, Instant.ofEpochSecond(upTo)) { recorded = it }
            val dep = checkNotNull(recorded)
            dep.freshnessDeadlineEpochSecond - dep.fetchedAtEpochSecond shouldBe 604_800L
        }

        "restart preserves the same freshness classification" {
            val counter = AtomicInteger(0)
            val wall0 = 2_000_000L
            val clock = Instant.ofEpochSecond(wall0)
            val upTo = wall0 - 100_000L
            val since = upTo - 86_400L
            val consumedStart = upTo - durationSeconds
            val futureStart = wall0 - 2 * durationSeconds
            val repository = SqliteHistoricalOhlcRepositoryImpl(DatabaseConfig.init(":memory:"))
            val kraken = FakeKrakenService().apply {
                ohlcSupplier = { _, _, _ ->
                    counter.incrementAndGet()
                    listOf(
                        consumedStart to BigDecimal("0.0175"),
                        futureStart to BigDecimal("0.0180"),
                    )
                }
            }
            val cache = HistoricalOhlcCache(kraken, persistentRepository = repository, nowProvider = { clock })

            var recorded: ConsumedOhlcDependency? = null
            cache.getOHLC(pair, interval, since, Instant.ofEpochSecond(upTo)) { recorded = it }
            val dep = checkNotNull(recorded)
            dep.freshnessDeadlineEpochSecond - dep.fetchedAtEpochSecond shouldBe 604_800L

            // Restart with no clock advance: the restored proof is fresh, so no live
            // call, and the refreshed deadline keeps the historical classification.
            val restarted = HistoricalOhlcCache(kraken, persistentRepository = repository, nowProvider = { clock })
            val result = restarted.revalidateDependency(dep)
            result shouldBe io.kotest.matchers.types.beInstanceOf<OhlcRevalidationResult.Unchanged>()
            val updated = (result as OhlcRevalidationResult.Unchanged).updatedDependency
            updated.candleContentHash shouldBe dep.candleContentHash
            updated.freshnessDeadlineEpochSecond - updated.fetchedAtEpochSecond shouldBe 604_800L
            counter.get() shouldBe 1
        }

        "expired empty dependency revalidates live even while its record reads fresh" {
            val counter = AtomicInteger(0)
            val wall0 = 2_000_000L
            var clock = Instant.ofEpochSecond(wall0)
            val upTo = wall0 - 100_000L
            val since = upTo - 86_400L
            // Completed and old (data age over a day) but closing after upTo, so the
            // consumed set is empty while the covering record carries the 7-day TTL.
            val futureStart = wall0 - 90_000L
            val cache = HistoricalOhlcCache(
                FakeKrakenService().apply {
                    ohlcSupplier = { _, _, _ ->
                        counter.incrementAndGet()
                        listOf(futureStart to BigDecimal("0.0180"))
                    }
                },
                nowProvider = { clock },
            )

            var recorded: ConsumedOhlcDependency? = null
            cache.getOHLC(pair, interval, since, Instant.ofEpochSecond(upTo)) { recorded = it }
            val dep = checkNotNull(recorded)
            dep.candleContentHash shouldBe "empty"
            dep.freshnessDeadlineEpochSecond - dep.fetchedAtEpochSecond shouldBe 600L

            // Past the empty-result deadline but years inside the record's 7-day window:
            // absence must be reconfirmed live (backfill discovery), never cheap-hit.
            clock = clock.plusSeconds(601L)
            val result = cache.revalidateDependency(dep)
            result shouldBe io.kotest.matchers.types.beInstanceOf<OhlcRevalidationResult.Unchanged>()
            counter.get() shouldBe 2
            val updated = (result as OhlcRevalidationResult.Unchanged).updatedDependency
            updated.fetchedAtEpochSecond shouldBe clock.epochSecond
            updated.freshnessDeadlineEpochSecond - updated.fetchedAtEpochSecond shouldBe 600L
        }

        "failed empty revalidation retries on the short cadence despite an old union" {
            val counter = AtomicInteger(0)
            val wall0 = 2_000_000L
            var clock = Instant.ofEpochSecond(wall0)
            val upTo = wall0 - 100_000L
            val since = upTo - 86_400L
            val futureStart = wall0 - 90_000L
            val cache = HistoricalOhlcCache(
                FakeKrakenService().apply {
                    ohlcSupplier = { _, _, _ ->
                        val call = counter.incrementAndGet()
                        if (call == 2) error("transient kraken failure")
                        listOf(futureStart to BigDecimal("0.0180"))
                    }
                },
                nowProvider = { clock },
            )

            var recorded: ConsumedOhlcDependency? = null
            cache.getOHLC(pair, interval, since, Instant.ofEpochSecond(upTo)) { recorded = it }
            val dep = checkNotNull(recorded)
            dep.candleContentHash shouldBe "empty"

            // The first revalidation attempts live (empty is never cheap-hit) and fails:
            // the retry is paced on the empty 600s cadence, not the old union's 7 days ...
            clock = clock.plusSeconds(601L)
            val failed = cache.revalidateDependency(dep)
            failed shouldBe io.kotest.matchers.types.beInstanceOf<OhlcRevalidationResult.Unchanged>()
            counter.get() shouldBe 2

            // ... so one TTL later the range retries live instead of staying blind.
            clock = clock.plusSeconds(601L)
            val retried = cache.revalidateDependency(dep)
            retried shouldBe io.kotest.matchers.types.beInstanceOf<OhlcRevalidationResult.Unchanged>()
            counter.get() shouldBe 3
            val updated = (retried as OhlcRevalidationResult.Unchanged).updatedDependency
            updated.fetchedAtEpochSecond shouldBe clock.epochSecond
        }

        "freshness boundary at exactly one day old selects the historical TTL" {
            val policy = OhlcRefreshPolicy()
            val candleStart = 1_000_000L
            val candle = candleStart to BigDecimal("0.0175")
            // dataAge strictly below a day stays recent; exactly a day is historical.
            policy.freshnessSeconds(listOf(candle), candleStart + 900L + 86_399L, interval) shouldBe 3_600L
            policy.freshnessSeconds(listOf(candle), candleStart + 900L + 86_400L, interval) shouldBe 604_800L
        }

        "freshness follows the newest consumed candle among several" {
            val policy = OhlcRefreshPolicy()
            val wall = 2_000_000L
            val old = (wall - 200_000L) to BigDecimal("0.0170")
            val new = (wall - 1_800L) to BigDecimal("0.0175")
            // One recent candle among old ones keeps the short TTL ...
            policy.freshnessSeconds(listOf(old, new), wall, interval) shouldBe 3_600L
            policy.freshnessSeconds(listOf(new, old), wall, interval) shouldBe 3_600L
            // ... while an all-old set rides the historical cadence regardless of order.
            val older = (wall - 300_000L) to BigDecimal("0.0169")
            policy.freshnessSeconds(listOf(old, older), wall, interval) shouldBe 604_800L
            policy.freshnessSeconds(listOf(older, old), wall, interval) shouldBe 604_800L
        }

        "full raw page with an in-progress candle preserves older cached candles" {
            val counter = AtomicInteger(0)
            val wall = 2_000_000L
            val clock = Instant.ofEpochSecond(wall)
            val since = 1_000_000L
            val pageStart = 1_100_000L
            val pageSize = KrakenApiConstants.OHLC_PAGE_SIZE
            val older = listOf(
                since to BigDecimal("0.0170"),
                (since + durationSeconds) to BigDecimal("0.0171"),
                (since + 2 * durationSeconds) to BigDecimal("0.0172"),
            )
            // RAW provider page at the endpoint limit: 719 completed candles plus the
            // current in-progress candle. The filtered list (719) looks short, but the
            // raw page (720) may be truncated, so replacement stays inside the returned
            // completed span and the older in-domain candles survive.
            val completedPage = (0 until pageSize - 1).map { i ->
                (pageStart + i * durationSeconds) to BigDecimal("0.0175")
            }
            val inProgressStart = wall - 100L
            val truncatedRaw = completedPage + listOf(inProgressStart to BigDecimal("0.0180"))
            truncatedRaw.size shouldBe pageSize
            val responses = mutableListOf(older, truncatedRaw)
            val cache = HistoricalOhlcCache(
                FakeKrakenService().apply {
                    ohlcSupplier = { _, _, _ ->
                        counter.incrementAndGet()
                        responses.removeFirst()
                    }
                },
                nowProvider = { clock },
            )

            cache.getOHLC(pair, interval, since, Instant.ofEpochSecond(wall))
            // Beyond-wall windows defeat cross-range coverage so the range refetches live.
            cache.getOHLC(pair, interval, since, Instant.ofEpochSecond(wall + 7_200L))
            counter.get() shouldBe 2

            val memory = cache.getOHLC(pair, interval, since, Instant.ofEpochSecond(wall))
            counter.get() shouldBe 2
            memory.size shouldBe older.size + completedPage.size
            older.forEach { (start, _) -> memory.any { it.first == start } shouldBe true }
            memory.any { it.first == pageStart } shouldBe true
            memory.any { it.first == pageStart + (pageSize - 2) * durationSeconds } shouldBe true
            memory.none { it.first == inProgressStart } shouldBe true
        }

        "genuinely short raw page with an in-progress candle replaces the full domain" {
            val counter = AtomicInteger(0)
            val wall = 2_000_000L
            val clock = Instant.ofEpochSecond(wall)
            val since = 1_000_000L
            val pageSize = KrakenApiConstants.OHLC_PAGE_SIZE
            val omittedStart = since + 3 * durationSeconds
            val older = listOf(
                since to BigDecimal("0.0170"),
                (since + durationSeconds) to BigDecimal("0.0171"),
                (since + 2 * durationSeconds) to BigDecimal("0.0172"),
                omittedStart to BigDecimal("0.0173"),
            )
            // RAW page below the endpoint limit: 718 completed candles plus one
            // in-progress candle. Short means complete for [since, wall), so the
            // omitted in-domain candle is authoritatively deleted.
            val completedPage = (0 until pageSize - 1)
                .filter { i -> since + i * durationSeconds != omittedStart }
                .map { i -> (since + i * durationSeconds) to BigDecimal("0.0175") }
            completedPage.size shouldBe pageSize - 2
            val shortRaw = completedPage + listOf((wall - 100L) to BigDecimal("0.0180"))
            shortRaw.size shouldBe pageSize - 1
            val responses = mutableListOf(older, shortRaw)
            val cache = HistoricalOhlcCache(
                FakeKrakenService().apply {
                    ohlcSupplier = { _, _, _ ->
                        counter.incrementAndGet()
                        responses.removeFirst()
                    }
                },
                nowProvider = { clock },
            )

            cache.getOHLC(pair, interval, since, Instant.ofEpochSecond(wall))
            cache.getOHLC(pair, interval, since, Instant.ofEpochSecond(wall + 7_200L))
            counter.get() shouldBe 2

            val memory = cache.getOHLC(pair, interval, since, Instant.ofEpochSecond(wall))
            counter.get() shouldBe 2
            memory.size shouldBe completedPage.size
            memory.none { it.first == omittedStart } shouldBe true
            memory.any { it.first == since } shouldBe true
        }

        "full raw page of completed candles keeps bounded-span replacement" {
            val counter = AtomicInteger(0)
            val wall = 2_000_000L
            val clock = Instant.ofEpochSecond(wall)
            val earlySince = 900_000L
            val since = 1_000_000L
            val pageSize = KrakenApiConstants.OHLC_PAGE_SIZE
            val retractedStart = since + 5 * durationSeconds
            val extraStart = since + pageSize * durationSeconds
            val seed = listOf(earlySince to BigDecimal("0.0169"))
            val inSpan = listOf(
                since to BigDecimal("0.0170"),
                retractedStart to BigDecimal("0.0171"),
            )
            // 720 RAW rows, all completed: the page may be truncated, so the
            // retracted in-span candle is deleted while the older pre-span candle
            // is preserved.
            val fullPage = (0 until pageSize)
                .filter { i -> since + i * durationSeconds != retractedStart }
                .map { i -> (since + i * durationSeconds) to BigDecimal("0.0175") } +
                listOf(extraStart to BigDecimal("0.0185"))
            fullPage.size shouldBe pageSize
            // Beyond-wall upTo defeats coverage so the in-span seed and the full
            // page each fetch live instead of being served from the seed proof.
            val responses = mutableListOf(seed, inSpan, fullPage)
            val cache = HistoricalOhlcCache(
                FakeKrakenService().apply {
                    ohlcSupplier = { _, _, _ ->
                        counter.incrementAndGet()
                        responses.removeFirst()
                    }
                },
                nowProvider = { clock },
            )

            cache.getOHLC(pair, interval, earlySince, Instant.ofEpochSecond(wall))
            cache.getOHLC(pair, interval, since, Instant.ofEpochSecond(wall + 7_200L))
            cache.getOHLC(pair, interval, since, Instant.ofEpochSecond(wall + 7_200L))
            counter.get() shouldBe 3

            val memory = cache.getOHLC(pair, interval, earlySince, Instant.ofEpochSecond(wall))
            counter.get() shouldBe 3
            memory.size shouldBe 1 + pageSize
            memory.none { it.first == retractedStart } shouldBe true
            memory.any { it.first == earlySince } shouldBe true
            memory.any { it.first == since } shouldBe true
            memory.any { it.first == extraStart } shouldBe true
        }

        "truncated page starting well after since preserves older and gap candles" {
            val counter = AtomicInteger(0)
            val wall = 2_000_000L
            val clock = Instant.ofEpochSecond(wall)
            val since = 1_000_000L
            val pageSize = KrakenApiConstants.OHLC_PAGE_SIZE
            // Latest 719-completed span that still closes before the wall: its first
            // row starts ~4 days after the requested since.
            val pageStart = since + 392 * durationSeconds
            val seeds = listOf(
                since to BigDecimal("0.0170"),
                (since + durationSeconds) to BigDecimal("0.0171"),
                (since + 200 * durationSeconds) to BigDecimal("0.0172"),
                (since + 300 * durationSeconds) to BigDecimal("0.0173"),
            )
            val completedPage = (0 until pageSize - 1).map { i ->
                (pageStart + i * durationSeconds) to BigDecimal("0.0175")
            }
            val truncatedRaw = completedPage + listOf((wall - 100L) to BigDecimal("0.0180"))
            truncatedRaw.size shouldBe pageSize
            val responses = mutableListOf(seeds, truncatedRaw)
            val cache = HistoricalOhlcCache(
                FakeKrakenService().apply {
                    ohlcSupplier = { _, _, _ ->
                        counter.incrementAndGet()
                        responses.removeFirst()
                    }
                },
                nowProvider = { clock },
            )

            cache.getOHLC(pair, interval, since, Instant.ofEpochSecond(wall))
            cache.getOHLC(pair, interval, since, Instant.ofEpochSecond(wall + 7_200L))
            counter.get() shouldBe 2

            val memory = cache.getOHLC(pair, interval, since, Instant.ofEpochSecond(wall))
            counter.get() shouldBe 2
            memory.size shouldBe seeds.size + completedPage.size
            seeds.forEach { (start, _) -> memory.any { it.first == start } shouldBe true }
        }

        "out-of-order truncated fetch keeps evidence witnessed later" {
            val counter = AtomicInteger(0)
            val wallNew = 2_000_000L
            val wallOld = 1_900_000L
            var clock = Instant.ofEpochSecond(wallNew)
            val sinceNew = 1_500_000L
            val sinceOld = 1_000_000L
            val witnessStart = sinceOld + 666 * durationSeconds
            val pageSize = KrakenApiConstants.OHLC_PAGE_SIZE
            // Newer fetch completes first; the older truncated page overlaps the
            // witness with a stale close and must neither overwrite nor delete it.
            val stalePage = (0 until pageSize - 1).map { i ->
                val start = sinceOld + i * durationSeconds
                val close = if (start == witnessStart) "0.0199" else "0.0175"
                start to BigDecimal(close)
            }
            val staleRaw = stalePage + listOf((wallOld - 500L) to BigDecimal("0.0180"))
            staleRaw.size shouldBe pageSize
            val responses = mutableListOf(
                listOf(witnessStart to BigDecimal("0.0179")),
                staleRaw,
            )
            val cache = HistoricalOhlcCache(
                FakeKrakenService().apply {
                    ohlcSupplier = { _, _, _ ->
                        counter.incrementAndGet()
                        responses.removeFirst()
                    }
                },
                nowProvider = { clock },
            )

            cache.getOHLC(pair, interval, sinceNew, Instant.ofEpochSecond(wallNew))
            counter.get() shouldBe 1

            clock = Instant.ofEpochSecond(wallOld)
            cache.getOHLC(pair, interval, sinceOld, Instant.ofEpochSecond(wallNew + 100L))
            counter.get() shouldBe 2

            clock = Instant.ofEpochSecond(wallNew)
            val memory = cache.getOHLC(pair, interval, sinceOld, Instant.ofEpochSecond(wallOld))
            counter.get() shouldBe 2
            memory.size shouldBe pageSize - 1
            memory.single { it.first == witnessStart }.second shouldBeEqualComparingTo BigDecimal("0.0179")
        }

        "oversized raw page is treated as potentially truncated" {
            val counter = AtomicInteger(0)
            val wall = 2_000_000L
            val clock = Instant.ofEpochSecond(wall)
            val since = 1_000_000L
            val pageStart = 1_100_000L
            val pageSize = KrakenApiConstants.OHLC_PAGE_SIZE
            val older = listOf(since to BigDecimal("0.0170"))
            // A provider (or fake) returning more rows than the documented page limit
            // is still only authoritative for its returned span.
            val completedPage = (0 until pageSize).map { i ->
                (pageStart + i * durationSeconds) to BigDecimal("0.0175")
            }
            val oversizedRaw = completedPage + listOf((wall - 100L) to BigDecimal("0.0180"))
            oversizedRaw.size shouldBe pageSize + 1
            val responses = mutableListOf(older, oversizedRaw)
            val cache = HistoricalOhlcCache(
                FakeKrakenService().apply {
                    ohlcSupplier = { _, _, _ ->
                        counter.incrementAndGet()
                        responses.removeFirst()
                    }
                },
                nowProvider = { clock },
            )

            cache.getOHLC(pair, interval, since, Instant.ofEpochSecond(wall))
            cache.getOHLC(pair, interval, since, Instant.ofEpochSecond(wall + 7_200L))
            counter.get() shouldBe 2

            val memory = cache.getOHLC(pair, interval, since, Instant.ofEpochSecond(wall))
            counter.get() shouldBe 2
            memory.size shouldBe older.size + completedPage.size
            memory.any { it.first == since } shouldBe true
        }

        "full raw page of only in-progress candles deletes nothing" {
            val counter = AtomicInteger(0)
            val wall = 2_000_000L
            val clock = Instant.ofEpochSecond(wall)
            val since = 1_000_000L
            val pageSize = KrakenApiConstants.OHLC_PAGE_SIZE
            val older = listOf(
                since to BigDecimal("0.0170"),
                (since + durationSeconds) to BigDecimal("0.0171"),
            )
            // Every raw row is still in progress: zero completed candles prove
            // nothing absent, and the older evidence keeps serving.
            val allInProgress = (0 until pageSize).map { i ->
                (wall - 800L + i) to BigDecimal("0.0180")
            }
            val responses = mutableListOf(older, allInProgress)
            val cache = HistoricalOhlcCache(
                FakeKrakenService().apply {
                    ohlcSupplier = { _, _, _ ->
                        counter.incrementAndGet()
                        responses.removeFirst()
                    }
                },
                nowProvider = { clock },
            )

            cache.getOHLC(pair, interval, since, Instant.ofEpochSecond(wall))
            val answered = cache.getOHLC(pair, interval, since, Instant.ofEpochSecond(wall + 7_200L))
            counter.get() shouldBe 2
            answered shouldBe emptyList()

            val memory = cache.getOHLC(pair, interval, since, Instant.ofEpochSecond(wall))
            counter.get() shouldBe 2
            memory.size shouldBe older.size
            older.forEach { (start, _) -> memory.any { it.first == start } shouldBe true }
        }

        "full raw page with a single completed candle deletes nothing outside it" {
            val counter = AtomicInteger(0)
            val wall = 2_000_000L
            val clock = Instant.ofEpochSecond(wall)
            val since = 1_000_000L
            val pageSize = KrakenApiConstants.OHLC_PAGE_SIZE
            val loneStart = 1_100_000L
            val older = listOf(
                since to BigDecimal("0.0170"),
                (since + durationSeconds) to BigDecimal("0.0171"),
            )
            val singleCompletedRaw = listOf(loneStart to BigDecimal("0.0175")) +
                (0 until pageSize - 1).map { i -> (wall - 800L + i) to BigDecimal("0.0180") }
            singleCompletedRaw.size shouldBe pageSize
            val responses = mutableListOf(older, singleCompletedRaw)
            val cache = HistoricalOhlcCache(
                FakeKrakenService().apply {
                    ohlcSupplier = { _, _, _ ->
                        counter.incrementAndGet()
                        responses.removeFirst()
                    }
                },
                nowProvider = { clock },
            )

            cache.getOHLC(pair, interval, since, Instant.ofEpochSecond(wall))
            cache.getOHLC(pair, interval, since, Instant.ofEpochSecond(wall + 7_200L))
            counter.get() shouldBe 2

            val memory = cache.getOHLC(pair, interval, since, Instant.ofEpochSecond(wall))
            counter.get() shouldBe 2
            memory.size shouldBe older.size + 1
            memory.any { it.first == loneStart } shouldBe true
            older.forEach { (start, _) -> memory.any { it.first == start } shouldBe true }
        }

        "restart after a truncated replacement keeps older persisted history" {
            val database = DatabaseConfig.init(":memory:")
            val repository = SqliteHistoricalOhlcRepositoryImpl(database)
            val firstCounter = AtomicInteger(0)
            val secondCounter = AtomicInteger(0)
            val wall = 2_000_000L
            val clock = Instant.ofEpochSecond(wall)
            val since = 1_000_000L
            val pageStart = 1_100_000L
            val pageSize = KrakenApiConstants.OHLC_PAGE_SIZE
            val older = listOf(
                since to BigDecimal("0.0170"),
                (since + durationSeconds) to BigDecimal("0.0171"),
                (since + 2 * durationSeconds) to BigDecimal("0.0172"),
            )
            val completedPage = (0 until pageSize - 1).map { i ->
                (pageStart + i * durationSeconds) to BigDecimal("0.0175")
            }
            val truncatedRaw = completedPage + listOf((wall - 100L) to BigDecimal("0.0180"))
            val responses = mutableListOf(older, truncatedRaw)
            val first = HistoricalOhlcCache(
                FakeKrakenService().apply {
                    ohlcSupplier = { _, _, _ ->
                        firstCounter.incrementAndGet()
                        responses.removeFirst()
                    }
                },
                persistentRepository = repository,
                nowProvider = { clock },
            )

            first.getOHLC(pair, interval, since, Instant.ofEpochSecond(wall))
            first.getOHLC(pair, interval, since, Instant.ofEpochSecond(wall + 7_200L))
            firstCounter.get() shouldBe 2

            val restarted = HistoricalOhlcCache(
                fake(emptyList(), secondCounter),
                persistentRepository = repository,
                nowProvider = { clock },
            )
            val restored = restarted.getOHLC(pair, interval, since, Instant.ofEpochSecond(wall))

            firstCounter.get() shouldBe 2
            secondCounter.get() shouldBe 0
            restored.size shouldBe older.size + completedPage.size
            older.forEach { (start, _) -> restored.any { it.first == start } shouldBe true }
        }

        "acceptance: truncated page preserves older history and dependency identity across restart" {
            val database = DatabaseConfig.init(":memory:")
            val repository = SqliteHistoricalOhlcRepositoryImpl(database)
            val firstCounter = AtomicInteger(0)
            val secondCounter = AtomicInteger(0)
            val wall = 2_000_000L
            val clock = Instant.ofEpochSecond(wall)
            val since = 1_000_000L
            val pageStart = 1_100_000L
            val pageSize = KrakenApiConstants.OHLC_PAGE_SIZE
            val older = listOf(
                since to BigDecimal("0.0170"),
                (since + durationSeconds) to BigDecimal("0.0171"),
                (since + 2 * durationSeconds) to BigDecimal("0.0172"),
            )
            val completedPage = (0 until pageSize - 1).map { i ->
                (pageStart + i * durationSeconds) to BigDecimal("0.0175")
            }
            val truncatedRaw = completedPage + listOf((wall - 100L) to BigDecimal("0.0180"))
            truncatedRaw.size shouldBe pageSize
            val shortCorrection = listOf(
                since to BigDecimal("0.0170"),
                (since + 2 * durationSeconds) to BigDecimal("0.0172"),
                pageStart to BigDecimal("0.0175"),
            )
            val responses = mutableListOf(older, truncatedRaw)
            val first = HistoricalOhlcCache(
                FakeKrakenService().apply {
                    ohlcSupplier = { _, _, _ ->
                        firstCounter.incrementAndGet()
                        responses.removeFirst()
                    }
                },
                persistentRepository = repository,
                nowProvider = { clock },
            )

            // Existing persisted older history: 3 rows at 1000000/1000900/1001800.
            first.getOHLC(pair, interval, since, Instant.ofEpochSecond(wall))
            var depBefore: ConsumedOhlcDependency? = null
            first.getOHLC(pair, interval, since, Instant.ofEpochSecond(pageStart)) { depBefore = it }
            firstCounter.get() shouldBe 1

            // Fresh 720-row page (719 completed + 1 in-progress) is processed as
            // potentially truncated: memory and SQLite keep 3 + 719 = 722 rows.
            first.getOHLC(pair, interval, since, Instant.ofEpochSecond(wall + 7_200L))
            firstCounter.get() shouldBe 2
            val afterTruncated = first.getOHLC(pair, interval, since, Instant.ofEpochSecond(wall))
            afterTruncated.size shouldBe 722
            var depAfter: ConsumedOhlcDependency? = null
            first.getOHLC(pair, interval, since, Instant.ofEpochSecond(pageStart)) { depAfter = it }
            firstCounter.get() shouldBe 2
            // The dependency over the older-only window resolves identically: the
            // truncated page neither removed nor rewrote the older rows.
            checkNotNull(depAfter).candleContentHash shouldBe checkNotNull(depBefore).candleContentHash
            val durableBeforeRestart = repository.loadCovered(pair, interval, since, wall)?.candles.orEmpty()
            durableBeforeRestart.size shouldBe 722

            // Restart: the older rows are still present with zero live calls.
            val restarted = HistoricalOhlcCache(
                FakeKrakenService().apply {
                    ohlcSupplier = { _, _, _ ->
                        secondCounter.incrementAndGet()
                        shortCorrection
                    }
                },
                persistentRepository = repository,
                nowProvider = { clock },
            )
            val restored = restarted.getOHLC(pair, interval, since, Instant.ofEpochSecond(wall))
            secondCounter.get() shouldBe 0
            restored.size shouldBe 722
            older.forEach { (start, _) -> restored.any { it.first == start } shouldBe true }

            // A genuinely short page is authoritative for [since, wall): the omitted
            // 1000900 candle (and the unlisted page rows) are deleted, leaving 3 rows.
            restarted.getOHLC(pair, interval, since, Instant.ofEpochSecond(wall + 7_200L))
            secondCounter.get() shouldBe 1
            val afterShort = restarted.getOHLC(pair, interval, since, Instant.ofEpochSecond(wall))
            secondCounter.get() shouldBe 1
            afterShort.size shouldBe shortCorrection.size
            afterShort.none { it.first == since + durationSeconds } shouldBe true
            val durableAfterShort = repository.loadCovered(pair, interval, since, wall)?.candles.orEmpty()
            durableAfterShort.size shouldBe shortCorrection.size
        }
    }
}
