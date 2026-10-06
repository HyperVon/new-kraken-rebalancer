package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.config.DatabaseConfig
import com.gemini.krakenbot.repository.HistoricalOhlcFetchProof
import com.gemini.krakenbot.repository.HistoricalOhlcRepository
import com.gemini.krakenbot.repository.HistoricalOhlcSeries
import com.gemini.krakenbot.repository.OhlcReachabilityFrontier
import com.gemini.krakenbot.repository.impl.SqliteHistoricalOhlcRepositoryImpl
import com.gemini.krakenbot.service.FakeKrakenService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

class HistoricalOhlcReadBatchTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    private val pair = "XBTUSD"
    private val interval = 15
    private val wall = 2_000_000_000L
    private val since = wall - 180 * 24 * 60 * 60L
    private val upTo = since + 7 * 24 * 60 * 60L
    private val repository = SqliteHistoricalOhlcRepositoryImpl(DatabaseConfig.init(":memory:"))
    private val frontier = OhlcReachabilityFrontier(pair, interval, wall - 60 * 24 * 60 * 60L, wall, wall + 3_600)

    private fun cache(repository: HistoricalOhlcRepository = this.repository): HistoricalOhlcCache =
        HistoricalOhlcCache(
            FakeKrakenService().apply { ohlcSupplier = { _, _, _ -> error("unexpected live OHLC fetch") } },
            persistentRepository = repository,
            nowProvider = { Instant.ofEpochSecond(wall) },
        )

    init {
        "one hundred shifted frontier misses use one metadata index and recheck it once" {
            val coveringReads = AtomicInteger(0)
            val proofReads = AtomicInteger(0)
            val counting = object : HistoricalOhlcRepository by repository {
                override suspend fun loadCovered(
                    pair: String,
                    intervalMinutes: Int,
                    sinceEpochSecond: Long,
                    upToEpochSecond: Long,
                ): HistoricalOhlcSeries? {
                    coveringReads.incrementAndGet()
                    return repository.loadCovered(pair, intervalMinutes, sinceEpochSecond, upToEpochSecond)
                }

                override suspend fun loadFetchProofs(
                    pair: String,
                    intervalMinutes: Int,
                ): List<HistoricalOhlcFetchProof>? {
                    proofReads.incrementAndGet()
                    return repository.loadFetchProofs(pair, intervalMinutes)
                }
            }
            repository.saveReachabilityFrontier(frontier)
            val subject = cache(counting)
            subject.withReadBatch { batch ->
                repeat(100) { index ->
                    withContext(Dispatchers.Default) {
                        subject.getOHLC(pair, interval, since + index * 67L, Instant.ofEpochSecond(upTo + index * 67L))
                            .shouldBe(emptyList())
                    }
                }
                coveringReads.get() shouldBe 1
                proofReads.get() shouldBe 1
                requireNotNull(batch).isStillCurrent() shouldBe true
                proofReads.get() shouldBe 2
            }
        }

        "stored narrow coverage wins over a frontier after an earlier shifted window missed" {
            repository.saveFetch(pair, interval, since + 67, wall - 1, listOf((upTo - 900) to BigDecimal("123")), false)
            repository.saveReachabilityFrontier(frontier)
            val subject = cache()
            subject.withReadBatch { batch ->
                subject.getOHLC(pair, interval, since, Instant.ofEpochSecond(upTo)) shouldBe emptyList()
                subject.getOHLC(pair, interval, since + 1, Instant.ofEpochSecond(upTo + 1)) shouldBe emptyList()
                val covered = subject.getOHLC(pair, interval, since + 67, Instant.ofEpochSecond(upTo + 67))
                covered.single().second.shouldBeEqualComparingTo(BigDecimal("123"))
                requireNotNull(batch).isStillCurrent() shouldBe true
            }
        }

        "a separate writer can invalidate a skipped window without moving the frontier" {
            repository.saveReachabilityFrontier(frontier)
            val subject = cache()
            subject.withReadBatch { batch ->
                subject.getOHLC(pair, interval, since, Instant.ofEpochSecond(upTo)) shouldBe emptyList()
                subject.getOHLC(pair, interval, since + 67, Instant.ofEpochSecond(upTo + 67)) shouldBe emptyList()
                // No cache notification or frontier change: publication must read durable proofs again.
                repository.saveFetch(pair, interval, since, wall, listOf((upTo - 900) to BigDecimal("124")), false)
                repository.loadReachabilityFrontier(pair, interval) shouldBe frontier
                requireNotNull(batch).isStillCurrent() shouldBe false
            }
            subject.withReadBatch { batch ->
                subject.getOHLC(pair, interval, since + 67, Instant.ofEpochSecond(upTo + 67))
                    .single().second.shouldBeEqualComparingTo(BigDecimal("124"))
                requireNotNull(batch).isStillCurrent() shouldBe true
            }
        }

        "concurrent batches on one cache do not share negative proof snapshots" {
            repository.saveReachabilityFrontier(frontier)
            val subject = cache()
            val firstCaptured = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            coroutineScope {
                val first = async {
                    subject.withReadBatch { batch ->
                        subject.getOHLC(pair, interval, since, Instant.ofEpochSecond(upTo)) shouldBe emptyList()
                        subject.getOHLC(pair, interval, since + 67, Instant.ofEpochSecond(upTo + 67)) shouldBe
                            emptyList()
                        firstCaptured.complete(Unit)
                        releaseFirst.await()
                        requireNotNull(batch).isStillCurrent() shouldBe false
                    }
                }
                firstCaptured.await()
                repository.saveFetch(pair, interval, since, wall, listOf((upTo - 900) to BigDecimal("126")), false)
                val second = async {
                    subject.withReadBatch { batch ->
                        subject.getOHLC(pair, interval, since + 67, Instant.ofEpochSecond(upTo + 67))
                            .single().second.shouldBeEqualComparingTo(BigDecimal("126"))
                        requireNotNull(batch).isStillCurrent() shouldBe true
                    }
                }
                second.await()
                releaseFirst.complete(Unit)
                first.await()
            }
        }

        "new proofs outside skipped windows do not invalidate the calculation" {
            repository.saveReachabilityFrontier(frontier)
            val subject = cache()
            subject.withReadBatch { batch ->
                subject.getOHLC(pair, interval, since, Instant.ofEpochSecond(upTo)) shouldBe emptyList()
                subject.getOHLC(pair, interval, since + 67, Instant.ofEpochSecond(upTo + 67)) shouldBe emptyList()
                repository.saveFetch(
                    pair,
                    interval,
                    wall - 1_800,
                    wall,
                    listOf((wall - 1_800) to BigDecimal.ONE),
                    false,
                )
                requireNotNull(batch).isStillCurrent() shouldBe true
            }
        }

        "failed proof reads fall back to covering lookups and failed publication reads reject reuse" {
            val failReads = AtomicInteger(1)
            val coveringReads = AtomicInteger(0)
            val failing = object : HistoricalOhlcRepository by repository {
                override suspend fun loadFetchProofs(
                    pair: String,
                    intervalMinutes: Int,
                ): List<HistoricalOhlcFetchProof>? {
                    if (failReads.getAndDecrement() > 0) error("temporary proof read failure")
                    return repository.loadFetchProofs(pair, intervalMinutes)
                }

                override suspend fun loadCovered(
                    pair: String,
                    intervalMinutes: Int,
                    sinceEpochSecond: Long,
                    upToEpochSecond: Long,
                ): HistoricalOhlcSeries? {
                    coveringReads.incrementAndGet()
                    return repository.loadCovered(pair, intervalMinutes, sinceEpochSecond, upToEpochSecond)
                }
            }
            repository.saveReachabilityFrontier(frontier)
            val subject = cache(failing)
            subject.withReadBatch { batch ->
                repeat(3) { index ->
                    subject.getOHLC(pair, interval, since + index * 67L, Instant.ofEpochSecond(upTo + index * 67L))
                        .shouldBe(emptyList())
                }
                coveringReads.get() shouldBe 2
                failReads.set(1)
                requireNotNull(batch).isStillCurrent() shouldBe false
                batch.isStillCurrent() shouldBe true
            }
        }

        "repositories without batched metadata retain per-window coverage checks" {
            val coveringReads = AtomicInteger(0)
            val unsupported = object : HistoricalOhlcRepository by repository {
                override suspend fun loadFetchProofs(
                    pair: String,
                    intervalMinutes: Int,
                ): List<HistoricalOhlcFetchProof>? = null

                override suspend fun loadCovered(
                    pair: String,
                    intervalMinutes: Int,
                    sinceEpochSecond: Long,
                    upToEpochSecond: Long,
                ): HistoricalOhlcSeries? {
                    coveringReads.incrementAndGet()
                    return repository.loadCovered(pair, intervalMinutes, sinceEpochSecond, upToEpochSecond)
                }
            }
            repository.saveReachabilityFrontier(frontier)
            val subject = cache(unsupported)
            subject.withReadBatch { batch ->
                repeat(3) { index ->
                    subject.getOHLC(pair, interval, since + index * 67L, Instant.ofEpochSecond(upTo + index * 67L))
                        .shouldBe(emptyList())
                }
                coveringReads.get() shouldBe 3
                requireNotNull(batch).isStillCurrent() shouldBe true
            }
        }

        "a local live fetch invalidates its batch proof index even with unchanged candle content" {
            repository.saveReachabilityFrontier(frontier)
            val liveCalls = AtomicInteger(0)
            val recent = (wall - 1_800) to BigDecimal("125")
            val subject = HistoricalOhlcCache(
                FakeKrakenService().apply {
                    ohlcSupplier = { _, _, _ ->
                        liveCalls.incrementAndGet()
                        listOf(recent)
                    }
                },
                persistentRepository = repository,
                nowProvider = { Instant.ofEpochSecond(wall) },
            )
            // Pre-existing candles without broad coverage: the new proof changes metadata only.
            repository.saveFetch(pair, interval, wall - 1_800, wall, listOf(recent), false)
            subject.withReadBatch { batch ->
                subject.getOHLC(pair, interval, since, Instant.ofEpochSecond(upTo)) shouldBe emptyList()
                subject.getOHLC(pair, interval, since + 67, Instant.ofEpochSecond(upTo + 67)) shouldBe emptyList()
                subject.getOHLC(pair, interval, since, Instant.ofEpochSecond(wall - 900)) shouldBe listOf(recent)
                liveCalls.get() shouldBe 1
                requireNotNull(batch).mayCover(pair, interval, since + 67, upTo + 67) shouldBe true
                batch.isStillCurrent() shouldBe false
            }
        }

        "cancellation is propagated instead of certifying an empty proof batch" {
            val cancelling = object : HistoricalOhlcRepository by repository {
                override suspend fun loadFetchProofs(
                    pair: String,
                    intervalMinutes: Int,
                ): List<HistoricalOhlcFetchProof>? = throw CancellationException("cancelled proof read")
            }
            repository.saveReachabilityFrontier(frontier)
            val subject = cache(cancelling)
            shouldThrow<CancellationException> {
                subject.withReadBatch {
                    subject.getOHLC(pair, interval, since, Instant.ofEpochSecond(upTo)) shouldBe emptyList()
                    subject.getOHLC(pair, interval, since + 67, Instant.ofEpochSecond(upTo + 67))
                }
            }
        }
    }
}
