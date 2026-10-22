package com.gemini.krakenbot.service.history

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class HistorySnapshotConsistencyTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    private val accountA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    private val accountB = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"

    init {
        "status summary stays on one published generation while a writer commits" {
            withDatabase { store, _ ->
                val start = LocalDate.parse("2026-03-08")
                publish(store, accountA, start, listOf("100", "101"), "account-a-old")
                val summaryRead = CountDownLatch(1)
                val continueRead = CountDownLatch(1)
                store.dailyValueSnapshotReadHookForTest = { point, digest ->
                    if (point == DailyValueSnapshotReadPoint.AFTER_STATUS_SUMMARY && digest == accountA) {
                        summaryRead.countDown()
                        check(continueRead.await(5, TimeUnit.SECONDS)) { "Status summary reader was not released." }
                    }
                }

                val reader = async(Dispatchers.IO) {
                    store.queryDailyValueStatusSnapshot(accountA, HISTORY_ACCOUNT_DAILY_SCOPE)
                }
                try {
                    await(summaryRead)
                    publish(store, accountA, start, listOf("200", "201", "202"), "account-a-new")
                } finally {
                    continueRead.countDown()
                }

                val old = reader.await()
                old.availableDays shouldBe 2L
                old.firstDayLabel shouldBe start
                old.lastDayLabel shouldBe start.plusDays(1)
                old.valuationJob?.generationId shouldBe "account-a-old"
                old.blockedReasons shouldBe emptyList()

                store.dailyValueSnapshotReadHookForTest = null
                val current = store.queryDailyValueStatusSnapshot(accountA, HISTORY_ACCOUNT_DAILY_SCOPE)
                current.availableDays shouldBe 3L
                current.valuationJob?.generationId shouldBe "account-a-new"
            }
        }

        "status summary bounds blocked reasons and retains incomplete dates without asset rows" {
            withDatabase { store, _ ->
                val start = LocalDate.parse("2026-01-01")
                val (completeJob, completeValues) = generation(
                    accountA,
                    start,
                    List(53) { "0" },
                    "account-a-blocked",
                )
                val blockedValues = completeValues.map { value ->
                    value.copy(
                        totalUsd = null,
                        status = HistoryDailyValueStatus.NO_PRICE,
                        assets = emptyList(),
                    )
                }
                store.publishDailyValues(
                    completeJob.copy(
                        state = HistoryValuationJobState.NEEDS_ATTENTION,
                        completeValueDays = 0,
                        blockedValueDays = blockedValues.size,
                    ),
                    blockedValues,
                )

                val snapshot = store.queryDailyValueStatusSnapshot(accountA, HISTORY_ACCOUNT_DAILY_SCOPE)

                snapshot.availableDays shouldBe 53L
                snapshot.incompleteDays shouldBe 53L
                snapshot.valuationJob?.blockedValueDays shouldBe 53
                snapshot.blockedReasons.size shouldBe 50
                snapshot.blockedReasons.first().dayLabel shouldBe start
                snapshot.blockedReasons.first().status shouldBe HistoryDailyValueStatus.NO_PRICE
                snapshot.blockedReasons.first().rawAssetKey shouldBe null
            }
        }

        "status summary caps incomplete asset reasons at fifty rows" {
            withDatabase { store, _ ->
                val day = LocalDate.parse("2026-01-01")
                val (completeJob, completeValues) = generation(accountA, day, listOf("0"), "account-a-blocked")
                val completeValue = completeValues.single()
                val dayEnd = completeValue.dayEnd
                val blockedAssets = (0 until 60).map { index ->
                    HistoryDailyAssetValue(
                        dayEnd = dayEnd,
                        accountIdentityDigest = accountA,
                        rawAssetClass = "currency",
                        rawAssetKey = "ASSET-$index",
                        quantity = BigDecimal.ONE,
                        priceUsd = null,
                        valueUsd = null,
                        pricePair = null,
                        priceCandleOpen = null,
                        priceSource = null,
                        status = HistoryDailyValueStatus.NO_PRICE,
                    )
                }
                val blockedValue = completeValue.copy(
                    totalUsd = null,
                    status = HistoryDailyValueStatus.NO_PRICE,
                    assets = blockedAssets,
                )
                store.publishDailyValues(
                    completeJob.copy(
                        state = HistoryValuationJobState.NEEDS_ATTENTION,
                        completeValueDays = 0,
                        blockedValueDays = 1,
                    ),
                    listOf(blockedValue),
                )

                val snapshot = store.queryDailyValueStatusSnapshot(accountA, HISTORY_ACCOUNT_DAILY_SCOPE)

                snapshot.blockedReasons.size shouldBe 50
                snapshot.blockedReasons.all { it.rawAssetKey != null } shouldBe true
                snapshot.blockedReasons.first().rawAssetKey shouldBe "ASSET-0"
            }
        }

        "snapshot keeps count and rows from one generation while publishing between count and page" {
            withDatabase { store, _ ->
                val day = LocalDate.parse("2026-03-08")
                publish(store, accountA, day, listOf("100"), "account-a-old")
                val countRead = CountDownLatch(1)
                val continueRead = CountDownLatch(1)
                store.dailyValueSnapshotReadHookForTest = { point, digest ->
                    if (point == DailyValueSnapshotReadPoint.AFTER_COUNT && digest == accountA) {
                        countRead.countDown()
                        check(continueRead.await(5, TimeUnit.SECONDS)) { "Snapshot test reader was not released." }
                    }
                }

                val reader = async(Dispatchers.IO) {
                    store.queryDailyValueSnapshot(accountA, HISTORY_ACCOUNT_DAILY_SCOPE)
                }
                try {
                    await(countRead)
                    publish(store, accountA, day, listOf("200", "201"), "account-a-new")
                } finally {
                    continueRead.countDown()
                }

                val snapshot = reader.await()
                snapshot.values.size shouldBe 1
                snapshot.values.single().generationId shouldBe "account-a-old"
                snapshot.values.single().totalUsd shouldBe BigDecimal("100")
                snapshot.valuationJob?.generationId shouldBe "account-a-old"

                store.dailyValueSnapshotReadHookForTest = null
                val current = store.queryDailyValueSnapshot(accountA, HISTORY_ACCOUNT_DAILY_SCOPE)
                current.values.size shouldBe 2
                current.values.map { it.generationId }.distinct() shouldBe listOf("account-a-new")
                current.valuationJob?.generationId shouldBe "account-a-new"
            }
        }

        "snapshot keeps headers, holdings, and valuation job consistent while a writer commits" {
            withDatabase { store, _ ->
                val day = LocalDate.parse("2026-03-08")
                publish(store, accountA, day, listOf("100"), "account-a-old")
                publish(store, accountB, day, listOf("900"), "account-b-old")
                val headersRead = CountDownLatch(1)
                val continueRead = CountDownLatch(1)
                store.dailyValueSnapshotReadHookForTest = { point, digest ->
                    if (point == DailyValueSnapshotReadPoint.AFTER_HEADERS && digest == accountA) {
                        headersRead.countDown()
                        check(continueRead.await(5, TimeUnit.SECONDS)) { "Snapshot test reader was not released." }
                    }
                }

                val accountAReader = async(Dispatchers.IO) {
                    store.queryDailyValueSnapshot(accountA, HISTORY_ACCOUNT_DAILY_SCOPE)
                }
                try {
                    await(headersRead)
                    val accountBSnapshot = store.queryDailyValueSnapshot(accountB, HISTORY_ACCOUNT_DAILY_SCOPE)
                    accountBSnapshot.values.single().totalUsd shouldBe BigDecimal("900")
                    accountBSnapshot.valuationJob?.generationId shouldBe "account-b-old"

                    publish(store, accountA, day, listOf("200", "201"), "account-a-new")
                    publish(store, accountB, day, listOf("700"), "account-b-new")
                } finally {
                    continueRead.countDown()
                }

                val accountASnapshot = accountAReader.await()
                val oldValue = accountASnapshot.values.single()
                oldValue.generationId shouldBe "account-a-old"
                oldValue.totalUsd shouldBe BigDecimal("100")
                oldValue.assets.single().rawAssetKey shouldBe "ZUSD"
                oldValue.assets.single().quantity shouldBe BigDecimal("100")
                accountASnapshot.valuationJob?.generationId shouldBe "account-a-old"

                store.dailyValueSnapshotReadHookForTest = null
                val currentA = store.queryDailyValueSnapshot(accountA, HISTORY_ACCOUNT_DAILY_SCOPE)
                val currentB = store.queryDailyValueSnapshot(accountB, HISTORY_ACCOUNT_DAILY_SCOPE)
                currentA.values.map { it.totalUsd } shouldBe listOf(BigDecimal("200"), BigDecimal("201"))
                currentA.valuationJob?.generationId shouldBe "account-a-new"
                currentB.values.single().totalUsd shouldBe BigDecimal("700")
                currentB.valuationJob?.generationId shouldBe "account-b-new"
            }
        }

        "cancellation during a snapshot closes its read transaction and connection" {
            withDatabase { store, _ ->
                val day = LocalDate.parse("2026-03-08")
                publish(store, accountA, day, listOf("100"), "account-a-old")
                val countRead = CountDownLatch(1)
                val continueRead = CountDownLatch(1)
                store.dailyValueSnapshotReadHookForTest = { point, digest ->
                    if (point == DailyValueSnapshotReadPoint.AFTER_COUNT && digest == accountA) {
                        countRead.countDown()
                        check(continueRead.await(5, TimeUnit.SECONDS)) { "Snapshot test reader was not released." }
                    }
                }

                val reader = async(Dispatchers.IO) {
                    store.queryDailyValueSnapshot(accountA, HISTORY_ACCOUNT_DAILY_SCOPE)
                }
                try {
                    await(countRead)
                    reader.cancel()
                } finally {
                    continueRead.countDown()
                }
                reader.join()
                reader.isCancelled shouldBe true

                store.dailyValueSnapshotReadHookForTest = null
                val afterCancellation = store.queryDailyValueSnapshot(accountA, HISTORY_ACCOUNT_DAILY_SCOPE)
                afterCancellation.values.single().totalUsd shouldBe BigDecimal("100")
                afterCancellation.valuationJob?.generationId shouldBe "account-a-old"
            }
        }

        "daily value CSV materializes one generation before the first output chunk" {
            withDatabase { store, _ ->
                val day = LocalDate.parse("2026-03-08")
                val old = publish(store, accountA, day, listOf("100"), "account-a-old")
                val replacement = publish(store, accountA, day, listOf("200"), "account-a-new")
                store.publishDailyValues(old.first, old.second)
                val exporter = HistoryCsvExporter(mockk(relaxed = true), store)
                val chunks = mutableListOf<String>()

                val rowsWritten = exporter.exportDailyValues(
                    HistoryDailyValueExportRange(
                        accountA,
                        day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant(),
                        day.plusDays(2).atStartOfDay(ZoneOffset.UTC).toInstant(),
                    ),
                ) { chunk ->
                    chunks += chunk
                    if (chunks.size == 1) store.publishDailyValues(replacement.first, replacement.second)
                }

                rowsWritten shouldBe 1L
                val csv = chunks.joinToString("")
                csv shouldContainCsvValue "100"
                csv shouldContainCsvValue "account-a-old"
                csv shouldNotContainCsvValue "200"
                csv shouldNotContainCsvValue "account-a-new"
                store.queryDailyValueSnapshot(accountA, HISTORY_ACCOUNT_DAILY_SCOPE)
                    .values.single().totalUsd shouldBe BigDecimal("200")
            }
        }

        "holdings CSV keeps its aggregate and assets from one generation during publication" {
            withDatabase { store, _ ->
                val day = LocalDate.parse("2026-03-08")
                val old = publish(store, accountA, day, listOf("100"), "account-a-old")
                val replacement = publish(store, accountA, day, listOf("200"), "account-a-new")
                store.publishDailyValues(old.first, old.second)
                val exporter = HistoryCsvExporter(mockk(relaxed = true), store)
                val chunks = mutableListOf<String>()

                val rowsWritten = exporter.exportDailyHoldings(
                    HistoryDailyHoldingsExportQuery(accountA, day),
                ) { chunk ->
                    chunks += chunk
                    if (chunks.size == 1) store.publishDailyValues(replacement.first, replacement.second)
                }

                rowsWritten shouldBe 1L
                val csv = chunks.joinToString("")
                csv shouldContainCsvValue "100"
                csv shouldContainCsvValue "USD"
                csv shouldNotContainCsvValue "200"
                store.queryDailyValueSnapshot(accountA, HISTORY_ACCOUNT_DAILY_SCOPE)
                    .values.single().totalUsd shouldBe BigDecimal("200")
            }
        }

        "failed generation publication rolls back and preserves the previous generation" {
            withDatabase { store, database ->
                val day = LocalDate.parse("2026-03-08")
                publish(store, accountA, day, listOf("100"), "account-a-old")
                database.connect().use { connection ->
                    connection.createStatement().use { statement ->
                        statement.execute(
                            "CREATE TRIGGER fail_daily_asset_insert BEFORE INSERT ON history_daily_assets " +
                                "WHEN NEW.account_identity_digest = '$accountA' " +
                                "BEGIN SELECT RAISE(ABORT, 'injected publication failure'); END",
                        )
                    }
                }
                val replacement = generation(accountA, day, listOf("200"), "account-a-new")

                val failure = runCatching {
                    store.publishDailyValues(replacement.first, replacement.second)
                }.exceptionOrNull()
                (failure is java.sql.SQLException) shouldBe true

                val snapshot = store.queryDailyValueSnapshot(accountA, HISTORY_ACCOUNT_DAILY_SCOPE)
                snapshot.values.single().generationId shouldBe "account-a-old"
                snapshot.values.single().totalUsd shouldBe BigDecimal("100")
                snapshot.values.single().assets.single().quantity shouldBe BigDecimal("100")
                snapshot.valuationJob?.generationId shouldBe "account-a-old"
            }
        }
    }

    private suspend fun publish(
        store: SqliteHistoryStore,
        account: String,
        start: LocalDate,
        amounts: List<String>,
        generation: String,
    ): Pair<HistoryValuationJob, List<HistoryDailyValue>> =
        generation(account, start, amounts, generation).also { (job, values) ->
            store.publishDailyValues(job, values)
        }

    private fun generation(
        account: String,
        start: LocalDate,
        amounts: List<String>,
        generation: String,
    ): Pair<HistoryValuationJob, List<HistoryDailyValue>> {
        val end = start.plusDays(amounts.size - 1L)
        val now = Instant.parse("2026-03-12T00:00:00Z")
        val revision = 0L
        val job = HistoryValuationJob(
            accountIdentityDigest = account,
            sourceAssetScope = HISTORY_ACCOUNT_DAILY_SCOPE,
            generationId = generation,
            state = HistoryValuationJobState.COMPLETE,
            stage = HistoryValuationStage.PRICE_AND_VALUE,
            targetStartDay = start,
            targetEndDay = end,
            nextDay = null,
            targetDays = amounts.size,
            balancesProcessed = amounts.size,
            balancesValid = amounts.size,
            balancesBlocked = 0,
            requiredPriceTasks = 0,
            completedPriceTasks = 0,
            completeValueDays = amounts.size,
            blockedValueDays = 0,
            sourceRevision = revision,
            calculationPolicyVersion = HISTORY_DAILY_VALUE_POLICY_VERSION,
            lastProgressAt = now,
            nextRetryAt = null,
            lastSuccessAt = now,
            lastError = null,
            updatedAt = now,
        )
        val values = amounts.mapIndexed { index, amount ->
            val day = start.plusDays(index.toLong())
            val dayEnd = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()
            val total = BigDecimal(amount)
            HistoryDailyValue(
                dayEnd = dayEnd,
                accountIdentityDigest = account,
                sourceAssetScope = HISTORY_ACCOUNT_DAILY_SCOPE,
                totalUsd = total,
                status = HistoryDailyValueStatus.COMPLETE,
                assets = listOf(
                    HistoryDailyAssetValue(
                        dayEnd = dayEnd,
                        accountIdentityDigest = account,
                        rawAssetClass = "currency",
                        rawAssetKey = "ZUSD",
                        quantity = total,
                        priceUsd = BigDecimal.ONE,
                        valueUsd = total,
                        pricePair = "USD",
                        priceCandleOpen = null,
                        priceSource = "EXACT_USD_UNIT",
                        status = HistoryDailyValueStatus.COMPLETE,
                    ),
                ),
                dayLabel = day,
                generationId = generation,
                calculationPolicyVersion = HISTORY_DAILY_VALUE_POLICY_VERSION,
                sourceRevision = revision,
            )
        }
        return job to values
    }

    private fun withDatabase(block: suspend (SqliteHistoryStore, HistoryDatabase) -> Unit) = runTest {
        val directory = Files.createTempDirectory("history-snapshot-consistency-")
        val database = HistoryDatabase(directory.resolve("history.db"))
        try {
            database.initializeFreshSchema()
            block(SqliteHistoryStore(database), database)
        } finally {
            database.close()
            directory.toFile().deleteRecursively()
        }
    }

    private suspend fun await(latch: CountDownLatch) {
        check(withContext(Dispatchers.IO) { latch.await(5, TimeUnit.SECONDS) }) {
            "Snapshot test reader did not reach its coordination point."
        }
    }

    private infix fun String.shouldContainCsvValue(value: String) {
        split("\r\n").any { row -> row.split(',').any { cell -> cell.trim('"') == value } } shouldBe true
    }

    private infix fun String.shouldNotContainCsvValue(value: String) {
        split("\r\n").any { row -> row.split(',').any { cell -> cell.trim('"') == value } } shouldBe false
    }
}
