package com.gemini.krakenbot.repository

import com.gemini.krakenbot.config.DatabaseConfig
import com.gemini.krakenbot.model.KrakenApiConstants
import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.repository.impl.SqliteHistoricalOhlcRepositoryImpl
import com.gemini.krakenbot.repository.impl.readSyncMetadata
import com.gemini.krakenbot.repository.table.HistoricalOhlcFetchTable
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import java.util.UUID

class SqliteHistoricalOhlcRepositoryImplTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    private val pair = "BABYUSD"
    private val intervalMinutes = 15

    init {
        "content-identical refetches and empty fetches never advance the evidence revisions" {
            runTest {
                val database = DatabaseConfig.init(
                    "jdbc:sqlite:file:ohlc-rev-unchanged-${UUID.randomUUID()}?mode=memory&cache=shared",
                )
                val repository = SqliteHistoricalOhlcRepositoryImpl(database)
                val candles = listOf(1_000L to BigDecimal("0.0175"), 1_900L to BigDecimal("0.0179"))

                val changedFirst = repository.saveFetch(
                    pair,
                    intervalMinutes,
                    0L,
                    5_000L,
                    candles,
                    mayBeTruncated = false,
                )
                changedFirst shouldBe true
                val revisionAfterFirst = comparisonRevision(database)
                val ohlcRevisionAfterFirst = ohlcRevision(database)
                revisionAfterFirst shouldBe "1"
                ohlcRevisionAfterFirst shouldBe "1"

                // A revalidation returning identical candle content must not invalidate any
                // comparison cache: only the fetch-proof timestamp moves.
                val changedIdentical = repository.saveFetch(
                    pair,
                    intervalMinutes,
                    0L,
                    9_000L,
                    candles,
                    mayBeTruncated = false,
                )
                changedIdentical shouldBe false
                comparisonRevision(database) shouldBe revisionAfterFirst
                ohlcRevision(database) shouldBe ohlcRevisionAfterFirst

                // An empty revalidation of a window with no candles changes no evidence.
                val changedEmpty = repository.saveFetch(
                    "DELISTEDUSD",
                    intervalMinutes,
                    0L,
                    9_000L,
                    emptyList(),
                    mayBeTruncated = false,
                )
                changedEmpty shouldBe false
                comparisonRevision(database) shouldBe revisionAfterFirst
                ohlcRevision(database) shouldBe ohlcRevisionAfterFirst
            }
        }

        "candle additions and corrections advance the evidence revisions" {
            runTest {
                val database = DatabaseConfig.init(
                    "jdbc:sqlite:file:ohlc-rev-changed-${UUID.randomUUID()}?mode=memory&cache=shared",
                )
                val repository = SqliteHistoricalOhlcRepositoryImpl(database)
                val candles = listOf(1_000L to BigDecimal("0.0175"))
                repository.saveFetch(pair, intervalMinutes, 0L, 5_000L, candles, mayBeTruncated = false)

                // A provider correction of a stored close is real evidence change.
                repository.saveFetch(
                    pair,
                    intervalMinutes,
                    0L,
                    9_000L,
                    listOf(1_000L to BigDecimal("0.0199")),
                    mayBeTruncated = false,
                )
                comparisonRevision(database) shouldBe "2"
                ohlcRevision(database) shouldBe "2"

                // A backfilled candle in the same series is real evidence change too.
                repository.saveFetch(
                    pair,
                    intervalMinutes,
                    0L,
                    12_000L,
                    listOf(1_000L to BigDecimal("0.0199"), 1_900L to BigDecimal("0.0200")),
                    mayBeTruncated = false,
                )
                comparisonRevision(database) shouldBe "3"
                ohlcRevision(database) shouldBe "3"
            }
        }

        "loadCovered returns the newest covering fetch with its stored candles" {
            runTest {
                val database = DatabaseConfig.init(
                    "jdbc:sqlite:file:ohlc-rev-covered-${UUID.randomUUID()}?mode=memory&cache=shared",
                )
                val repository = SqliteHistoricalOhlcRepositoryImpl(database)
                repository.saveFetch(
                    pair,
                    intervalMinutes,
                    0L,
                    5_000L,
                    listOf(1_000L to BigDecimal("0.0175")),
                    mayBeTruncated = false,
                )
                repository.saveFetch(
                    pair,
                    intervalMinutes,
                    0L,
                    9_000L,
                    listOf(1_000L to BigDecimal("0.0175")),
                    mayBeTruncated = false,
                )

                val covered = repository.loadCovered(pair, intervalMinutes, 0L, 8_000L)

                covered?.fetchedAtEpochSecond shouldBe 9_000L
                covered?.candles?.single()?.first shouldBe 1_000L
                covered?.candles?.single()?.second?.compareTo(BigDecimal("0.0175")) shouldBe 0
                repository.loadCovered(pair, intervalMinutes, 0L, 20_000L) shouldBe null
            }
        }

        "repeated revalidations keep the fetch-proof lineage bounded" {
            runTest {
                val database = DatabaseConfig.init(
                    "jdbc:sqlite:file:ohlc-fetch-prune-${UUID.randomUUID()}?mode=memory&cache=shared",
                )
                val repository = SqliteHistoricalOhlcRepositoryImpl(database)
                val candles = listOf(1_000L to BigDecimal("0.0175"))

                repeat(5) { attempt ->
                    repository.saveFetch(
                        pair,
                        intervalMinutes,
                        0L,
                        5_000L + attempt * 4_000L,
                        candles,
                        mayBeTruncated = false,
                    )
                }
                // A second lineage for the same series keeps its own bounded history.
                repository.saveFetch(pair, intervalMinutes, 100_000L, 105_000L, candles, mayBeTruncated = false)

                fetchProofCount(database, pair, intervalMinutes, 0L) shouldBe 3
                fetchProofCount(database, pair, intervalMinutes, 100_000L) shouldBe 1
                // The newest proofs survive; a request up to the latest wall stays covered.
                val covered = repository.loadCovered(pair, intervalMinutes, 0L, 21_000L)
                covered?.fetchedAtEpochSecond shouldBe 21_000L
            }
        }

        "authoritative empty refetch deletes its domain candles and reports change" {
            runTest {
                val database = DatabaseConfig.init(
                    "jdbc:sqlite:file:ohlc-replace-empty-${UUID.randomUUID()}?mode=memory&cache=shared",
                )
                val repository = SqliteHistoricalOhlcRepositoryImpl(database)
                repository.saveFetch(
                    pair,
                    intervalMinutes,
                    0L,
                    5_000L,
                    listOf(1_000L to BigDecimal("0.0175")),
                    mayBeTruncated = false,
                ) shouldBe true

                // A later authoritative response for the same domain is empty: C is deleted,
                // not unioned, and the removal counts as content change.
                repository.saveFetch(pair, intervalMinutes, 0L, 9_000L, emptyList(), mayBeTruncated = false) shouldBe
                    true
                repository.loadCovered(pair, intervalMinutes, 0L, 8_000L)?.candles shouldBe emptyList()
            }
        }

        "partial removal deletes only the absent candle" {
            runTest {
                val database = DatabaseConfig.init(
                    "jdbc:sqlite:file:ohlc-replace-partial-${UUID.randomUUID()}?mode=memory&cache=shared",
                )
                val repository = SqliteHistoricalOhlcRepositoryImpl(database)
                repository.saveFetch(
                    pair,
                    intervalMinutes,
                    0L,
                    5_000L,
                    listOf(1_000L to BigDecimal("0.0175"), 1_900L to BigDecimal("0.0179")),
                    mayBeTruncated = false,
                )

                repository.saveFetch(
                    pair,
                    intervalMinutes,
                    0L,
                    9_000L,
                    listOf(1_000L to BigDecimal("0.0175")),
                    mayBeTruncated = false,
                ) shouldBe true
                repository.loadCovered(pair, intervalMinutes, 0L, 8_000L)?.candles?.map { it.first } shouldBe
                    listOf(1_000L)
            }
        }

        "correction plus removal converges exactly to the fresh response" {
            runTest {
                val database = DatabaseConfig.init(
                    "jdbc:sqlite:file:ohlc-replace-mixed-${UUID.randomUUID()}?mode=memory&cache=shared",
                )
                val repository = SqliteHistoricalOhlcRepositoryImpl(database)
                repository.saveFetch(
                    pair,
                    intervalMinutes,
                    0L,
                    5_000L,
                    listOf(1_000L to BigDecimal("0.0175"), 1_900L to BigDecimal("0.0179")),
                    mayBeTruncated = false,
                )

                // One close corrected, one candle removed, one backfilled: the stored
                // series must equal the fresh response exactly.
                repository.saveFetch(
                    pair,
                    intervalMinutes,
                    0L,
                    9_000L,
                    listOf(1_000L to BigDecimal("0.0199"), 2_800L to BigDecimal("0.0200")),
                    mayBeTruncated = false,
                ) shouldBe true
                val stored = repository.loadCovered(pair, intervalMinutes, 0L, 8_000L)?.candles
                stored?.map { it.first } shouldBe listOf(1_000L, 2_800L)
                stored?.single { it.first == 1_000L }?.second?.compareTo(BigDecimal("0.0199")) shouldBe 0
                stored?.single { it.first == 2_800L }?.second?.compareTo(BigDecimal("0.0200")) shouldBe 0
            }
        }

        "replacement never deletes candles outside the fetched domain" {
            runTest {
                val database = DatabaseConfig.init(
                    "jdbc:sqlite:file:ohlc-replace-domain-${UUID.randomUUID()}?mode=memory&cache=shared",
                )
                val repository = SqliteHistoricalOhlcRepositoryImpl(database)
                repository.saveFetch(
                    pair,
                    intervalMinutes,
                    0L,
                    5_000L,
                    listOf(1_000L to BigDecimal("0.0175")),
                    mayBeTruncated = false,
                )

                // An empty refetch of a strictly newer domain leaves the older candle alone
                // and reports no change: nothing inside its own domain moved.
                repository.saveFetch(
                    pair,
                    intervalMinutes,
                    2_000L,
                    9_000L,
                    emptyList(),
                    mayBeTruncated = false,
                ) shouldBe
                    false
                repository.loadCovered(pair, intervalMinutes, 0L, 4_000L)?.candles?.map { it.first } shouldBe
                    listOf(1_000L)
            }
        }

        "out-of-order older fetch never deletes evidence witnessed later" {
            runTest {
                val database = DatabaseConfig.init(
                    "jdbc:sqlite:file:ohlc-replace-ooo-${UUID.randomUUID()}?mode=memory&cache=shared",
                )
                val repository = SqliteHistoricalOhlcRepositoryImpl(database)
                repository.saveFetch(
                    pair,
                    intervalMinutes,
                    0L,
                    9_000L,
                    listOf(1_000L to BigDecimal("0.0175")),
                    mayBeTruncated = false,
                )

                // A stale overlapping fetch completing late must not retract the close a
                // newer wall already witnessed.
                repository.saveFetch(pair, intervalMinutes, 0L, 5_000L, emptyList(), mayBeTruncated = false) shouldBe
                    false
                repository.loadCovered(pair, intervalMinutes, 0L, 4_000L)?.candles?.map { it.first } shouldBe
                    listOf(1_000L)
            }
        }

        "response candle outside the fetched domain never enters the deletion set" {
            runTest {
                val database = DatabaseConfig.init(
                    "jdbc:sqlite:file:ohlc-replace-ood-${UUID.randomUUID()}?mode=memory&cache=shared",
                )
                val repository = SqliteHistoricalOhlcRepositoryImpl(database)
                repository.saveFetch(
                    pair,
                    intervalMinutes,
                    0L,
                    5_000L,
                    listOf(1_000L to BigDecimal("0.0175")),
                    mayBeTruncated = false,
                )

                // The newer range echoes the older stored candle outside its own domain:
                // loaded for comparison, never judged absent, no change reported.
                repository.saveFetch(
                    pair,
                    intervalMinutes,
                    2_000L,
                    9_000L,
                    listOf(1_000L to BigDecimal("0.0175")),
                    mayBeTruncated = false,
                ) shouldBe false
                repository.loadCovered(pair, intervalMinutes, 0L, 4_000L)?.candles?.map { it.first } shouldBe
                    listOf(1_000L)
            }
        }

        "out-of-order older response keeps the newer close without reporting change" {
            runTest {
                val database = DatabaseConfig.init(
                    "jdbc:sqlite:file:ohlc-replace-ooow-${UUID.randomUUID()}?mode=memory&cache=shared",
                )
                val repository = SqliteHistoricalOhlcRepositoryImpl(database)
                repository.saveFetch(
                    pair,
                    intervalMinutes,
                    0L,
                    9_000L,
                    listOf(1_000L to BigDecimal("0.0199")),
                    mayBeTruncated = false,
                )

                // A stale overlapping fetch completing late must neither overwrite the
                // newer close nor report a change for the ignored correction.
                repository.saveFetch(
                    pair,
                    intervalMinutes,
                    0L,
                    5_000L,
                    listOf(1_000L to BigDecimal("0.0175")),
                    mayBeTruncated = false,
                ) shouldBe false
                val stored = repository.loadCovered(pair, intervalMinutes, 0L, 4_000L)?.candles
                stored?.single()?.second?.compareTo(BigDecimal("0.0199")) shouldBe 0
            }
        }

        "stored candle above the fetched domain is never judged absent" {
            runTest {
                val database = DatabaseConfig.init(
                    "jdbc:sqlite:file:ohlc-replace-above-${UUID.randomUUID()}?mode=memory&cache=shared",
                )
                val repository = SqliteHistoricalOhlcRepositoryImpl(database)
                repository.saveFetch(
                    pair,
                    intervalMinutes,
                    0L,
                    20_000L,
                    listOf(8_500L to BigDecimal("0.0175")),
                    mayBeTruncated = false,
                )

                // A narrower older-wall refetch echoes the stored candle above its own
                // domain: out of range, never deleted, and its newer close is kept.
                repository.saveFetch(
                    pair,
                    intervalMinutes,
                    0L,
                    9_000L,
                    listOf(8_500L to BigDecimal("0.0175")),
                    mayBeTruncated = false,
                ) shouldBe false
                val stored = repository.loadCovered(pair, intervalMinutes, 0L, 8_000L)?.candles
                stored?.map { it.first } shouldBe listOf(8_500L)
            }
        }

        "truncated raw page preserves older stored candles before its span" {
            runTest {
                val database = DatabaseConfig.init(
                    "jdbc:sqlite:file:ohlc-trunc-older-${UUID.randomUUID()}?mode=memory&cache=shared",
                )
                val repository = SqliteHistoricalOhlcRepositoryImpl(database)
                val since = 1_000_000L
                val wallFirst = 2_000_000L
                val wallSecond = 2_010_000L
                val pageStart = 1_100_000L
                val pageSize = KrakenApiConstants.OHLC_PAGE_SIZE
                val older = listOf(
                    1_000_000L to BigDecimal("0.0170"),
                    1_000_900L to BigDecimal("0.0171"),
                    1_001_800L to BigDecimal("0.0172"),
                )
                repository.saveFetch(
                    pair = pair,
                    intervalMinutes = intervalMinutes,
                    sinceEpochSecond = since,
                    fetchedAtEpochSecond = wallFirst,
                    candles = older,
                    mayBeTruncated = false,
                ) shouldBe true

                // 719 completed rows from a 720-row RAW page (one in-progress row was
                // filtered): mayBeTruncated restricts deletion to the returned span.
                val completedPage = (0 until pageSize - 1).map { i ->
                    (pageStart + i * 900L) to BigDecimal("0.0175")
                }
                val pageUntil = pageStart + (pageSize - 1) * 900L
                repository.saveFetch(
                    pair = pair,
                    intervalMinutes = intervalMinutes,
                    sinceEpochSecond = since,
                    fetchedAtEpochSecond = wallSecond,
                    candles = completedPage,
                    mayBeTruncated = true,
                ) shouldBe true

                // The older window still hits via the seed proof, with older rows kept.
                val olderStored = repository.loadCovered(pair, intervalMinutes, since, pageStart)?.candles.orEmpty()
                older.forEach { (start, _) -> olderStored.any { it.first == start } shouldBe true }
                // The truncated span is reusable exactly within its proven bounds.
                val spanStored = repository.loadCovered(pair, intervalMinutes, pageStart, pageUntil)?.candles.orEmpty()
                spanStored.size shouldBe completedPage.size
                // No single proof validated the union: the full window misses.
                repository.loadCovered(pair, intervalMinutes, since, wallSecond) shouldBe null
            }
        }

        "short raw page deletes the omitted in-domain candle" {
            runTest {
                val database = DatabaseConfig.init(
                    "jdbc:sqlite:file:ohlc-short-delete-${UUID.randomUUID()}?mode=memory&cache=shared",
                )
                val repository = SqliteHistoricalOhlcRepositoryImpl(database)
                val since = 1_000_000L
                val wall = 2_000_000L
                val pageSize = KrakenApiConstants.OHLC_PAGE_SIZE
                val omittedStart = since + 3 * 900L
                val older = listOf(
                    since to BigDecimal("0.0170"),
                    (since + 900L) to BigDecimal("0.0171"),
                    (since + 1_800L) to BigDecimal("0.0172"),
                    omittedStart to BigDecimal("0.0173"),
                )
                repository.saveFetch(
                    pair = pair,
                    intervalMinutes = intervalMinutes,
                    sinceEpochSecond = since,
                    fetchedAtEpochSecond = wall,
                    candles = older,
                    mayBeTruncated = false,
                ) shouldBe true

                // 718 completed rows from a 719-row RAW page: short means complete
                // for [since, wall), so the omitted candle is deleted.
                val completedPage = (0 until pageSize - 1)
                    .filter { i -> since + i * 900L != omittedStart }
                    .map { i -> (since + i * 900L) to BigDecimal("0.0175") }
                completedPage.size shouldBe pageSize - 2
                repository.saveFetch(
                    pair = pair,
                    intervalMinutes = intervalMinutes,
                    sinceEpochSecond = since,
                    fetchedAtEpochSecond = wall,
                    candles = completedPage,
                    mayBeTruncated = false,
                ) shouldBe true

                val stored = repository.loadCovered(pair, intervalMinutes, since, wall)?.candles.orEmpty()
                stored.size shouldBe completedPage.size
                stored.none { it.first == omittedStart } shouldBe true
            }
        }

        "full completed page retracts only its own span" {
            runTest {
                val database = DatabaseConfig.init(
                    "jdbc:sqlite:file:ohlc-full-span-${UUID.randomUUID()}?mode=memory&cache=shared",
                )
                val repository = SqliteHistoricalOhlcRepositoryImpl(database)
                val since = 1_000_000L
                val wall = 2_000_000L
                val pageSize = KrakenApiConstants.OHLC_PAGE_SIZE
                val retractedStart = since + 5 * 900L
                val extraStart = since + pageSize * 900L
                repository.saveFetch(
                    pair = pair,
                    intervalMinutes = intervalMinutes,
                    sinceEpochSecond = 900_000L,
                    fetchedAtEpochSecond = wall,
                    candles = listOf(900_000L to BigDecimal("0.0169")),
                    mayBeTruncated = false,
                ) shouldBe true
                repository.saveFetch(
                    pair = pair,
                    intervalMinutes = intervalMinutes,
                    sinceEpochSecond = since,
                    fetchedAtEpochSecond = wall,
                    candles = listOf(
                        since to BigDecimal("0.0170"),
                        retractedStart to BigDecimal("0.0171"),
                    ),
                    mayBeTruncated = false,
                ) shouldBe true

                // 720 completed rows from a full RAW page: the retracted in-span
                // candle is deleted while the pre-span candle survives.
                val fullPage = (0 until pageSize)
                    .filter { i -> since + i * 900L != retractedStart }
                    .map { i -> (since + i * 900L) to BigDecimal("0.0175") } +
                    listOf(extraStart to BigDecimal("0.0185"))
                fullPage.size shouldBe pageSize
                repository.saveFetch(
                    pair = pair,
                    intervalMinutes = intervalMinutes,
                    sinceEpochSecond = since,
                    fetchedAtEpochSecond = wall,
                    candles = fullPage,
                    mayBeTruncated = true,
                ) shouldBe true

                val stored = repository.loadCovered(pair, intervalMinutes, 900_000L, wall)?.candles.orEmpty()
                stored.size shouldBe 1 + pageSize
                stored.none { it.first == retractedStart } shouldBe true
                stored.any { it.first == 900_000L } shouldBe true
                stored.any { it.first == extraStart } shouldBe true
            }
        }

        "truncated page starting well after since preserves older and gap rows" {
            runTest {
                val database = DatabaseConfig.init(
                    "jdbc:sqlite:file:ohlc-trunc-gap-${UUID.randomUUID()}?mode=memory&cache=shared",
                )
                val repository = SqliteHistoricalOhlcRepositoryImpl(database)
                val since = 1_000_000L
                val wallFirst = 2_000_000L
                val wallSecond = 2_010_000L
                val pageStart = since + 392 * 900L
                val pageSize = KrakenApiConstants.OHLC_PAGE_SIZE
                val seeds = listOf(
                    since to BigDecimal("0.0170"),
                    (since + 900L) to BigDecimal("0.0171"),
                    (since + 200 * 900L) to BigDecimal("0.0172"),
                    (since + 300 * 900L) to BigDecimal("0.0173"),
                )
                repository.saveFetch(
                    pair = pair,
                    intervalMinutes = intervalMinutes,
                    sinceEpochSecond = since,
                    fetchedAtEpochSecond = wallFirst,
                    candles = seeds,
                    mayBeTruncated = false,
                ) shouldBe true

                val completedPage = (0 until pageSize - 1).map { i ->
                    (pageStart + i * 900L) to BigDecimal("0.0175")
                }
                val pageUntil = pageStart + (pageSize - 1) * 900L
                repository.saveFetch(
                    pair = pair,
                    intervalMinutes = intervalMinutes,
                    sinceEpochSecond = since,
                    fetchedAtEpochSecond = wallSecond,
                    candles = completedPage,
                    mayBeTruncated = true,
                ) shouldBe true

                // Older and gap rows are preserved and still hit via the seed proof.
                val olderStored = repository.loadCovered(pair, intervalMinutes, since, pageStart)?.candles.orEmpty()
                seeds.forEach { (start, _) -> olderStored.any { it.first == start } shouldBe true }
                // The late span is reusable exactly within its proven bounds.
                val spanStored = repository.loadCovered(pair, intervalMinutes, pageStart, pageUntil)?.candles.orEmpty()
                spanStored.size shouldBe completedPage.size
                // The truncated page is not coverage for the unreturned head.
                repository.loadCovered(pair, intervalMinutes, since, wallSecond) shouldBe null
            }
        }

        "out-of-order truncated fetch keeps rows witnessed later" {
            runTest {
                val database = DatabaseConfig.init(
                    "jdbc:sqlite:file:ohlc-trunc-ooo-${UUID.randomUUID()}?mode=memory&cache=shared",
                )
                val repository = SqliteHistoricalOhlcRepositoryImpl(database)
                val wallNew = 2_000_000L
                val wallOld = 1_900_000L
                val sinceOld = 1_000_000L
                val witnessStart = sinceOld + 666 * 900L
                val pageSize = KrakenApiConstants.OHLC_PAGE_SIZE
                repository.saveFetch(
                    pair = pair,
                    intervalMinutes = intervalMinutes,
                    sinceEpochSecond = 1_500_000L,
                    fetchedAtEpochSecond = wallNew,
                    candles = listOf(witnessStart to BigDecimal("0.0179")),
                    mayBeTruncated = false,
                ) shouldBe true

                // The older truncated page overlaps the witness with a stale close.
                val stalePage = (0 until pageSize - 1).map { i ->
                    val start = sinceOld + i * 900L
                    val close = if (start == witnessStart) "0.0199" else "0.0175"
                    start to BigDecimal(close)
                }
                repository.saveFetch(
                    pair = pair,
                    intervalMinutes = intervalMinutes,
                    sinceEpochSecond = sinceOld,
                    fetchedAtEpochSecond = wallOld,
                    candles = stalePage,
                    mayBeTruncated = true,
                ) shouldBe true

                val stored = repository.loadCovered(pair, intervalMinutes, 1_500_000L, wallOld)?.candles.orEmpty()
                stored.single { it.first == witnessStart }.second.compareTo(BigDecimal("0.0179")) shouldBe 0
            }
        }

        "truncated page with zero completed candles deletes nothing" {
            runTest {
                val database = DatabaseConfig.init(
                    "jdbc:sqlite:file:ohlc-trunc-empty-${UUID.randomUUID()}?mode=memory&cache=shared",
                )
                val repository = SqliteHistoricalOhlcRepositoryImpl(database)
                val since = 1_000_000L
                val wall = 2_000_000L
                val older = listOf(
                    since to BigDecimal("0.0170"),
                    (since + 900L) to BigDecimal("0.0171"),
                )
                repository.saveFetch(
                    pair = pair,
                    intervalMinutes = intervalMinutes,
                    sinceEpochSecond = since,
                    fetchedAtEpochSecond = wall,
                    candles = older,
                    mayBeTruncated = false,
                ) shouldBe true

                // A full RAW page whose every row was still in progress: nothing is
                // proven absent and no content changes.
                repository.saveFetch(
                    pair = pair,
                    intervalMinutes = intervalMinutes,
                    sinceEpochSecond = since,
                    fetchedAtEpochSecond = wall,
                    candles = emptyList(),
                    mayBeTruncated = true,
                ) shouldBe false

                val stored = repository.loadCovered(pair, intervalMinutes, since, wall)?.candles.orEmpty()
                stored.size shouldBe older.size
            }
        }

        "truncated page with duplicate timestamps stores each candle once" {
            runTest {
                val database = DatabaseConfig.init(
                    "jdbc:sqlite:file:ohlc-trunc-dup-${UUID.randomUUID()}?mode=memory&cache=shared",
                )
                val repository = SqliteHistoricalOhlcRepositoryImpl(database)
                val since = 1_000_000L
                val wallFirst = 2_000_000L
                val wallSecond = 2_010_000L
                val loneStart = 1_100_000L
                val older = listOf(since to BigDecimal("0.0170"))
                repository.saveFetch(
                    pair = pair,
                    intervalMinutes = intervalMinutes,
                    sinceEpochSecond = since,
                    fetchedAtEpochSecond = wallFirst,
                    candles = older,
                    mayBeTruncated = false,
                ) shouldBe true

                // Duplicate starts on a truncated page dedupe to one row; the older
                // pre-span candle is outside the returned span and survives.
                repository.saveFetch(
                    pair = pair,
                    intervalMinutes = intervalMinutes,
                    sinceEpochSecond = since,
                    fetchedAtEpochSecond = wallSecond,
                    candles = listOf(
                        loneStart to BigDecimal("0.0175"),
                        loneStart to BigDecimal("0.0175"),
                        (loneStart + 900L) to BigDecimal("0.0176"),
                    ),
                    mayBeTruncated = true,
                ) shouldBe true

                val olderStored = repository.loadCovered(pair, intervalMinutes, since, loneStart)?.candles.orEmpty()
                olderStored.any { it.first == since } shouldBe true
                val spanStored =
                    repository.loadCovered(pair, intervalMinutes, loneStart, loneStart + 2 * 900L)?.candles.orEmpty()
                spanStored.size shouldBe 2
                spanStored.count { it.first == loneStart } shouldBe 1
                repository.loadCovered(pair, intervalMinutes, since, wallSecond) shouldBe null
            }
        }

        "short empty response is reusable negative evidence" {
            runTest {
                val database = DatabaseConfig.init(
                    "jdbc:sqlite:file:ohlc-short-empty-${UUID.randomUUID()}?mode=memory&cache=shared",
                )
                val repository = SqliteHistoricalOhlcRepositoryImpl(database)
                repository.saveFetch(
                    pair = pair,
                    intervalMinutes = intervalMinutes,
                    sinceEpochSecond = 1_000_000L,
                    fetchedAtEpochSecond = 2_000_000L,
                    candles = emptyList(),
                    mayBeTruncated = false,
                ) shouldBe false

                // A definitely-complete empty page proves its requested range is empty.
                val stored = repository.loadCovered(pair, intervalMinutes, 1_000_000L, 1_100_000L)
                checkNotNull(stored).candles shouldBe emptyList()
            }
        }

        "truncated empty response writes a marker that validates no window" {
            runTest {
                val database = DatabaseConfig.init(
                    "jdbc:sqlite:file:ohlc-trunc-empty-only-${UUID.randomUUID()}?mode=memory&cache=shared",
                )
                val repository = SqliteHistoricalOhlcRepositoryImpl(database)
                repository.saveFetch(
                    pair = pair,
                    intervalMinutes = intervalMinutes,
                    sinceEpochSecond = 1_000_000L,
                    fetchedAtEpochSecond = 2_000_000L,
                    candles = emptyList(),
                    mayBeTruncated = true,
                ) shouldBe false

                // Nothing was proven: no window can be validated from this response,
                // but the empty marker persists so the exact request paces its refetch.
                repository.loadCovered(pair, intervalMinutes, 1_000_000L, 1_100_000L) shouldBe null
                fetchProofCount(database, pair, intervalMinutes, 1_000_000L) shouldBe 1
                val marker = repository.loadLatestProofForSince(pair, intervalMinutes, 1_000_000L)
                checkNotNull(marker).coverageFromEpochSecond shouldBe 1_000_000L
                marker.coverageUntilEpochSecond shouldBe 1_000_000L
                marker.candles shouldBe emptyList()
            }
        }

        "older full fetch completing after a truncated one keeps both coverages" {
            runTest {
                val database = DatabaseConfig.init(
                    "jdbc:sqlite:file:ohlc-ooo-full-${UUID.randomUUID()}?mode=memory&cache=shared",
                )
                val repository = SqliteHistoricalOhlcRepositoryImpl(database)
                val since = 900_000L
                val wallNew = 2_010_000L
                val wallOld = 2_000_000L
                val septemberPage = (0 until 719).map { i ->
                    (1_500_000L + i * 900L) to BigDecimal("0.0175")
                }
                repository.saveFetch(
                    pair = pair,
                    intervalMinutes = intervalMinutes,
                    sinceEpochSecond = since,
                    fetchedAtEpochSecond = wallNew,
                    candles = septemberPage,
                    mayBeTruncated = true,
                ) shouldBe true

                // The older full response covers the head range but must neither delete
                // nor overwrite the newer truncated span it never witnessed.
                repository.saveFetch(
                    pair = pair,
                    intervalMinutes = intervalMinutes,
                    sinceEpochSecond = since,
                    fetchedAtEpochSecond = wallOld,
                    candles = listOf(
                        1_000_000L to BigDecimal("0.0170"),
                        1_000_900L to BigDecimal("0.0171"),
                    ),
                    mayBeTruncated = false,
                ) shouldBe true

                val headStored = repository.loadCovered(pair, intervalMinutes, since, 1_100_000L)?.candles.orEmpty()
                headStored.any { it.first == 1_000_000L } shouldBe true
                val spanStored =
                    repository.loadCovered(pair, intervalMinutes, 1_500_000L, 2_147_100L)?.candles.orEmpty()
                spanStored.size shouldBe septemberPage.size
                spanStored.single { it.first == 1_500_000L }.second.compareTo(BigDecimal("0.0175")) shouldBe 0
            }
        }

        "retention keeps disjoint lineages of one request since" {
            runTest {
                val database = DatabaseConfig.init(
                    "jdbc:sqlite:file:ohlc-retain-lineage-${UUID.randomUUID()}?mode=memory&cache=shared",
                )
                val repository = SqliteHistoricalOhlcRepositoryImpl(database)
                val since = 900_000L
                repository.saveFetch(
                    pair = pair,
                    intervalMinutes = intervalMinutes,
                    sinceEpochSecond = since,
                    fetchedAtEpochSecond = 2_000_000L,
                    candles = listOf(1_000_000L to BigDecimal("0.0170")),
                    mayBeTruncated = false,
                ) shouldBe true

                // Three newer truncated proofs sharing the request since must not evict
                // the only proof covering the older required window.
                listOf(
                    Triple(2_001_000L, 1_500_000L, "0.0175"),
                    Triple(2_002_000L, 1_600_000L, "0.0176"),
                    Triple(2_003_000L, 1_700_000L, "0.0177"),
                ).forEach { (wall, start, close) ->
                    repository.saveFetch(
                        pair = pair,
                        intervalMinutes = intervalMinutes,
                        sinceEpochSecond = since,
                        fetchedAtEpochSecond = wall,
                        candles = listOf(
                            start to BigDecimal(close),
                            (start + 900L) to BigDecimal(close),
                        ),
                        mayBeTruncated = true,
                    ) shouldBe true
                }

                val olderStored = repository.loadCovered(
                    pair,
                    intervalMinutes,
                    1_000_000L,
                    1_100_000L,
                )?.candles.orEmpty()
                olderStored.any { it.first == 1_000_000L } shouldBe true
                val spanStored =
                    repository.loadCovered(pair, intervalMinutes, 1_600_000L, 1_601_800L)?.candles.orEmpty()
                spanStored.size shouldBe 2
            }
        }

        "absolute per-request cap evicts the oldest lineage first" {
            runTest {
                val database = DatabaseConfig.init(
                    "jdbc:sqlite:file:ohlc-retain-cap-${UUID.randomUUID()}?mode=memory&cache=shared",
                )
                val repository = SqliteHistoricalOhlcRepositoryImpl(database)
                val since = 900_000L
                (0 until 25).forEach { k ->
                    val wall = 2_000_000L + k * 1_000L
                    val start = 1_500_000L + k * 10_000L
                    repository.saveFetch(
                        pair = pair,
                        intervalMinutes = intervalMinutes,
                        sinceEpochSecond = since,
                        fetchedAtEpochSecond = wall,
                        candles = listOf(
                            start to BigDecimal("0.0175"),
                            (start + 900L) to BigDecimal("0.0176"),
                        ),
                        mayBeTruncated = true,
                    ) shouldBe true
                }

                // 25 disjoint lineages exceed the absolute bound: the newest 24
                // survive and the evicted oldest window fails closed to a miss.
                fetchProofCount(database, pair, intervalMinutes, since) shouldBe 24
                repository.loadCovered(pair, intervalMinutes, 1_500_000L, 1_501_800L) shouldBe null
                val newestStored =
                    repository.loadCovered(pair, intervalMinutes, 1_740_000L, 1_741_800L)?.candles.orEmpty()
                newestStored.size shouldBe 2
            }
        }

        "legacy proof rows without coverage fail closed and are pruned" {
            runTest {
                val database = DatabaseConfig.init(
                    "jdbc:sqlite:file:ohlc-legacy-proof-${UUID.randomUUID()}?mode=memory&cache=shared",
                )
                val repository = SqliteHistoricalOhlcRepositoryImpl(database)
                // Locals: the insert lambda is Table-scoped, so bare `pair` would
                // resolve to the column instead of the test fixture.
                val legacyPair = pair
                val legacyInterval = intervalMinutes
                transaction(database) {
                    // Explicit NULL coverage bounds: the pre-migration row shape.
                    HistoricalOhlcFetchTable.insert {
                        it[HistoricalOhlcFetchTable.pair] = legacyPair
                        it[HistoricalOhlcFetchTable.intervalMinutes] = legacyInterval
                        it[HistoricalOhlcFetchTable.sinceEpochSecond] = 1_000_000L
                        it[HistoricalOhlcFetchTable.fetchedAtEpochSecond] = 2_000_000L
                        it[HistoricalOhlcFetchTable.coverageFromEpochSecond] = null
                        it[HistoricalOhlcFetchTable.coverageUntilEpochSecond] = null
                    }
                }

                // Old rows cannot prove whether they were full or truncated: miss.
                repository.loadCovered(pair, intervalMinutes, 1_000_000L, 1_100_000L) shouldBe null

                // The next save of the lineage drops the dead row and writes a real proof.
                repository.saveFetch(
                    pair = pair,
                    intervalMinutes = intervalMinutes,
                    sinceEpochSecond = 1_000_000L,
                    fetchedAtEpochSecond = 2_010_000L,
                    candles = listOf(1_000_000L to BigDecimal("0.0170")),
                    mayBeTruncated = false,
                ) shouldBe true
                fetchProofCount(database, pair, intervalMinutes, 1_000_000L) shouldBe 1
                val stored = repository.loadCovered(pair, intervalMinutes, 1_000_000L, 1_100_000L)?.candles.orEmpty()
                stored.any { it.first == 1_000_000L } shouldBe true
            }
        }
    }

    private suspend fun fetchProofCount(
        database: Database,
        pair: String,
        intervalMinutes: Int,
        sinceEpochSecond: Long,
    ): Int = withContext(Dispatchers.IO) {
        transaction(database) {
            HistoricalOhlcFetchTable
                .selectAll()
                .where {
                    (HistoricalOhlcFetchTable.pair eq pair) and
                        (HistoricalOhlcFetchTable.intervalMinutes eq intervalMinutes) and
                        (HistoricalOhlcFetchTable.sinceEpochSecond eq sinceEpochSecond)
                }
                .count()
                .toInt()
        }
    }

    private suspend fun comparisonRevision(database: Database): String? =
        database.readSyncMetadata(SyncMetadataKeys.COMPARISON_EVIDENCE_REVISION)

    private suspend fun ohlcRevision(database: Database): String? =
        database.readSyncMetadata(SyncMetadataKeys.OHLC_CANDLE_CONTENT_REVISION)
}
