package com.gemini.krakenbot.service.history

import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.service.KrakenService
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Optional private acceptance runner for a disposable copy of the supplied History database. */
class HistoryArchiveAcceptanceTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        val databasePath = System.getenv(COPY_DATABASE_ENV)?.takeIf(String::isNotBlank)?.let(Path::of)
        if (databasePath != null) {
            "migrates and values the disposable imported account without network access" {
                runTest {
                    require(databasePath.toAbsolutePath().startsWith(Path.of("/tmp"))) {
                        "The acceptance runner only accepts a disposable database under /tmp."
                    }
                    val absolutePath = databasePath.toAbsolutePath()
                    val fixturePath = System.getenv(PRICE_FIXTURE_ENV)?.takeIf(String::isNotBlank)?.let(Path::of)
                    val before = readSourceCounts(absolutePath)
                    before.trades shouldBe 4_119
                    before.ledgers shouldBe 7_725
                    before.observations shouldBe 11_604

                    val database = HistoryDatabase(absolutePath)
                    try {
                        val store = SqliteHistoryStore(database)
                        val afterMigration = readSourceCounts(absolutePath)
                        afterMigration shouldBe before
                        readDerivedCounts(absolutePath) shouldBe listOf(0L, 0L, 0L)
                        database.connect().use { connection ->
                            connection.createStatement().use { statement ->
                                statement.executeQuery(
                                    "SELECT schema_version FROM history_schema_meta WHERE id = 1",
                                ).use { resultSet ->
                                    resultSet.next() shouldBe true
                                    resultSet.getInt(1) shouldBe 5
                                }
                            }
                        }

                        val digest = readAccountDigest(absolutePath)
                        val revision = store.currentLedgerRevision(digest)
                        val endDay = LocalDate.parse("2026-10-20")
                        val startDay = endDay.minusDays(364)
                        val source = readCheckpoints(store, digest, endDay)
                        val coverage = store.queryLedgerCoverageWindows(
                            digest,
                            overlappingStartSec = 0,
                            overlappingEndSec = endDay.plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond(),
                        ).map { HistorySyncCoverageWindow(it.startSec, it.endSec) }
                        val balances = HistoryDailyBalanceBuilder().build(
                            accountIdentityDigest = digest,
                            startDay = startDay,
                            endDay = endDay,
                            checkpoints = source,
                            coverage = coverage,
                            sourceRevision = revision,
                            generationId = "private-copy-acceptance",
                        )
                        exportBalanceEvidence(balances)
                        balances.groupBy { it.dayLabel }.size shouldBe 365
                        balances.none { it.rawAssetClass.equals("equity", ignoreCase = true) } shouldBe true
                        val requests = balances.asSequence()
                            .filter { it.status == HistoryDailyBalanceStatus.VALID && it.quantity?.signum() != 0 }
                            .map { HistoryDailyPriceRequest(it.rawAssetKey, it.dayLabel) }
                            .toSet()
                        check(requests.size == 4_293) {
                            "Unexpected private-copy price task count: ${requests.size}."
                        }
                        exportPriceRequests(databasePath, requests)

                        if (fixturePath != null) {
                            val prices = readPublicPriceFixture(fixturePath)
                            val service = mockk<KrakenService>()
                            coEvery { service.getOHLC(any(), 1_440, any()) } coAnswers {
                                val pair = firstArg<String>()
                                prices[pair].orEmpty()
                            }
                            val synchronizer = mockk<HistorySynchronizer>()
                            coEvery { synchronizer.verifiedAccount() } returns
                                VerifiedHistoryAccount(digest, null, null)
                            val now = Instant.parse("2026-10-21T12:00:00Z")
                            val coordinator = HistoryValuationCoordinator(
                                applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                                synchronizer = synchronizer,
                                store = store,
                                priceResolver = HistoryDailyPriceResolver(service, nowProvider = { now }),
                                nowProvider = { now },
                                pollInterval = Duration.ofDays(1),
                            )
                            coordinator.runOnce(forceRetry = true)

                            val job = store.loadValuationJob(digest, HISTORY_ACCOUNT_DAILY_SCOPE)
                            job?.state shouldBe HistoryValuationJobState.COMPLETE
                            job?.targetStartDay shouldBe startDay
                            job?.targetEndDay shouldBe endDay
                            job?.targetDays shouldBe 365
                            job?.balancesValid shouldBe 365
                            job?.completeValueDays shouldBe 365
                            job?.blockedValueDays shouldBe 0
                            job?.requiredPriceTasks shouldBe requests.size
                            job?.completedPriceTasks shouldBe requests.size
                            val dailyValues = store.queryDailyValues(
                                digest,
                                HISTORY_ACCOUNT_DAILY_SCOPE,
                                limit = 500,
                            )
                            dailyValues.total shouldBe 365L
                            dailyValues.items.size shouldBe 365
                            dailyValues.items.all { it.status == HistoryDailyValueStatus.COMPLETE } shouldBe true
                            val dailyAssets = readAllDailyAssets(store, digest)
                            dailyAssets.none { it.rawAssetClass.equals("equity", ignoreCase = true) } shouldBe true
                            dailyAssets.any { it.rawAssetKey == "USD" || it.rawAssetKey == "ZUSD" } shouldBe true
                            coVerify(exactly = 0) { service.executeOrder(any(), any(), any(), any(), any(), any()) }
                        }
                    } finally {
                        database.close()
                    }

                    val reopenedDatabase = HistoryDatabase(absolutePath)
                    try {
                        val reopenedStore = SqliteHistoryStore(reopenedDatabase)
                        readSourceCounts(absolutePath) shouldBe before
                        if (fixturePath != null) {
                            val digest = readAccountDigest(absolutePath)
                            reopenedStore.loadValuationJob(digest, HISTORY_ACCOUNT_DAILY_SCOPE)?.state shouldBe
                                HistoryValuationJobState.COMPLETE
                            val dailyValues = reopenedStore.queryDailyValues(
                                digest,
                                HISTORY_ACCOUNT_DAILY_SCOPE,
                                limit = 500,
                            )
                            dailyValues.total shouldBe 365L
                            dailyValues.items.all { it.status == HistoryDailyValueStatus.COMPLETE } shouldBe true
                        }
                    } finally {
                        reopenedDatabase.close()
                    }
                }
            }
        }
    }

    private suspend fun readCheckpoints(
        store: HistoryStore,
        digest: String,
        throughDay: LocalDate,
    ): List<HistoryBalanceCheckpoint> {
        val throughSec = throughDay.plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond()
        val rows = mutableListOf<HistoryBalanceCheckpoint>()
        var offset = 0
        var total: Long? = null
        while (true) {
            val page = store.queryDailyCheckpointInputPage(
                digest,
                throughSec,
                limit = HISTORY_MAX_PAGE_LIMIT,
                offset = offset,
            )
            if (total == null) total = page.total
            val expectedTotal = checkNotNull(total)
            page.total shouldBe expectedTotal
            rows += page.items
            offset += page.items.size
            check(page.items.isNotEmpty() || offset >= expectedTotal) { "Checkpoint page made no progress." }
            if (offset >= expectedTotal) break
        }
        return rows
    }

    private suspend fun readAllDailyAssets(store: HistoryStore, digest: String): List<HistoryDailyAssetValue> {
        val rows = mutableListOf<HistoryDailyAssetValue>()
        var offset = 0
        var total: Long? = null
        while (true) {
            val page = store.queryDailyAssetValues(
                digest,
                HISTORY_ACCOUNT_DAILY_SCOPE,
                limit = HISTORY_MAX_PAGE_LIMIT,
                offset = offset,
            )
            if (total == null) total = page.total
            val expectedTotal = checkNotNull(total)
            page.total shouldBe expectedTotal
            rows += page.items
            offset += page.items.size
            check(page.items.isNotEmpty() || offset >= expectedTotal) { "Daily asset page made no progress." }
            if (offset >= expectedTotal) break
        }
        return rows
    }

    private fun exportPriceRequests(databasePath: Path, requests: Set<HistoryDailyPriceRequest>) {
        val exportPath = System.getenv(PRICE_REQUESTS_ENV)?.takeIf(String::isNotBlank)?.let(Path::of) ?: return
        Files.writeString(
            exportPath,
            buildString {
                appendLine("pair,day,open_sec")
                requests.sortedWith(compareBy(HistoryDailyPriceRequest::rawAssetKey, HistoryDailyPriceRequest::day))
                    .forEach { request ->
                        val symbol = Asset.normalizeLedgerAsset(request.rawAssetKey)
                        if (symbol == Asset.USD) return@forEach
                        val pair = Asset.tradingPair(symbol)
                        val open = request.day.atStartOfDay(ZoneOffset.UTC).toEpochSecond()
                        appendLine("$pair,${request.day},$open")
                    }
            },
        )
        check(databasePath.fileName.toString() == "history.db")
    }

    private fun exportBalanceEvidence(balances: List<HistoryDailyBalance>) {
        val path = System.getenv(BALANCE_EVIDENCE_ENV)?.takeIf(String::isNotBlank)?.let(Path::of) ?: return
        Files.writeString(
            path,
            buildString {
                appendLine("day,asset,status,nonzero")
                balances.sortedWith(compareBy(HistoryDailyBalance::dayLabel, HistoryDailyBalance::rawAssetKey))
                    .forEach { balance ->
                        appendLine(
                            "${balance.dayLabel},${balance.rawAssetKey},${balance.status}," +
                                (balance.quantity?.signum()?.let { it != 0 } ?: "unknown"),
                        )
                    }
            },
        )
    }

    private fun readPublicPriceFixture(path: Path): Map<String, List<Pair<Long, BigDecimal>>> =
        Files.readAllLines(path).drop(1).filter(String::isNotBlank).map { row ->
            val columns = row.split(',')
            require(columns.size == 3)
            columns[0] to (columns[1].toLong() to columns[2].toBigDecimal())
        }.groupBy({ it.first }, { it.second })

    private fun readSourceCounts(path: Path): SourceCounts = DriverManager.getConnection(
        "jdbc:sqlite:file:${path.toAbsolutePath()}?mode=ro",
    ).use { connection ->
        fun count(table: String) = connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM $table").use { resultSet ->
                resultSet.next()
                resultSet.getLong(1)
            }
        }
        fun fingerprint(table: String, idColumn: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT $idColumn FROM $table ORDER BY $idColumn").use { resultSet ->
                    while (resultSet.next()) {
                        digest.update(resultSet.getString(1).toByteArray())
                        digest.update('\n'.code.toByte())
                    }
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        SourceCounts(
            trades = count("history_trades"),
            ledgers = count("history_ledgers"),
            observations = count("history_observations"),
            tradeIds = fingerprint("history_trades", "trade_id"),
            ledgerIds = fingerprint("history_ledgers", "ledger_id"),
            observationIds = fingerprint("history_observations", "observation_id"),
        )
    }

    private fun readDerivedCounts(path: Path): List<Long> = DriverManager.getConnection(
        "jdbc:sqlite:file:${path.toAbsolutePath()}?mode=ro",
    ).use { connection ->
        listOf("history_daily_values", "history_daily_assets", "history_daily_prices").map { table ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM $table").use { resultSet ->
                    resultSet.next()
                    resultSet.getLong(1)
                }
            }
        }
    }

    private fun readAccountDigest(path: Path): String = DriverManager.getConnection(
        "jdbc:sqlite:file:${path.toAbsolutePath()}?mode=ro",
    ).use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT DISTINCT account_identity_digest FROM history_ledgers").use { resultSet ->
                check(resultSet.next()) { "No imported account ledger records were found." }
                resultSet.getString(1).also { check(!resultSet.next()) { "Expected one imported account." } }
            }
        }
    }

    private data class SourceCounts(
        val trades: Long,
        val ledgers: Long,
        val observations: Long,
        val tradeIds: String,
        val ledgerIds: String,
        val observationIds: String,
    )

    private companion object {
        const val COPY_DATABASE_ENV = "HISTORY_RESTORATION_COPY_DB"
        const val PRICE_REQUESTS_ENV = "HISTORY_RESTORATION_PRICE_REQUESTS"
        const val PRICE_FIXTURE_ENV = "HISTORY_RESTORATION_PUBLIC_PRICE_FIXTURE"
        const val BALANCE_EVIDENCE_ENV = "HISTORY_RESTORATION_BALANCE_EVIDENCE"
    }
}
