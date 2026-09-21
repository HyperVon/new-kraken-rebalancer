package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.config.DatabaseConfig
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
                ) {
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
    }
}
