package com.gemini.krakenbot.repository

import com.gemini.krakenbot.config.DatabaseConfig
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

                repository.saveFetch(pair, intervalMinutes, 0L, 5_000L, candles)
                val revisionAfterFirst = comparisonRevision(database)
                val ohlcRevisionAfterFirst = ohlcRevision(database)
                revisionAfterFirst shouldBe "1"
                ohlcRevisionAfterFirst shouldBe "1"

                // A revalidation returning identical candle content must not invalidate any
                // comparison cache: only the fetch-proof timestamp moves.
                repository.saveFetch(pair, intervalMinutes, 0L, 9_000L, candles)
                comparisonRevision(database) shouldBe revisionAfterFirst
                ohlcRevision(database) shouldBe ohlcRevisionAfterFirst

                // An empty revalidation of a window with no candles changes no evidence.
                repository.saveFetch("DELISTEDUSD", intervalMinutes, 0L, 9_000L, emptyList())
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
                repository.saveFetch(pair, intervalMinutes, 0L, 5_000L, candles)

                // A provider correction of a stored close is real evidence change.
                repository.saveFetch(pair, intervalMinutes, 0L, 9_000L, listOf(1_000L to BigDecimal("0.0199")))
                comparisonRevision(database) shouldBe "2"
                ohlcRevision(database) shouldBe "2"

                // A backfilled candle in the same series is real evidence change too.
                repository.saveFetch(
                    pair,
                    intervalMinutes,
                    0L,
                    12_000L,
                    listOf(1_000L to BigDecimal("0.0199"), 1_900L to BigDecimal("0.0200")),
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
                repository.saveFetch(pair, intervalMinutes, 0L, 5_000L, listOf(1_000L to BigDecimal("0.0175")))
                repository.saveFetch(pair, intervalMinutes, 0L, 9_000L, listOf(1_000L to BigDecimal("0.0175")))

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
                    repository.saveFetch(pair, intervalMinutes, 0L, 5_000L + attempt * 4_000L, candles)
                }
                // A second lineage for the same series keeps its own bounded history.
                repository.saveFetch(pair, intervalMinutes, 100_000L, 105_000L, candles)

                fetchProofCount(database, pair, intervalMinutes, 0L) shouldBe 3
                fetchProofCount(database, pair, intervalMinutes, 100_000L) shouldBe 1
                // The newest proofs survive; a request up to the latest wall stays covered.
                val covered = repository.loadCovered(pair, intervalMinutes, 0L, 21_000L)
                covered?.fetchedAtEpochSecond shouldBe 21_000L
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
