package com.gemini.krakenbot.service.history

import com.gemini.krakenbot.model.ExchangeHistoryPage
import com.gemini.krakenbot.model.ExchangeLedgerHistoryEntry
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import java.math.BigDecimal
import java.nio.file.Files
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class HistorySqliteContentionAcceptanceTest : StringSpec() {
    init {
        "concurrent ledger progress and publication writes honor SQLite busy timeout and recover" {
            runTest {
                val directory = Files.createTempDirectory("history-sqlite-contention-")
                val databasePath = directory.resolve("history.db")
                try {
                    HistoryDatabase(databasePath).use { firstDatabase ->
                        firstDatabase.initializeFreshSchema()
                        HistoryDatabase(databasePath).use { secondDatabase ->
                            // Initialize both connection factories before locking so this measures writes.
                            firstDatabase.connect().use { connection -> connection.busyTimeoutMillis() shouldBe 5_000 }
                            secondDatabase.connect().use { connection -> connection.busyTimeoutMillis() shouldBe 5_000 }
                            val firstStore = SqliteHistoryStore(firstDatabase)
                            val secondStore = SqliteHistoryStore(secondDatabase)
                            val day = LocalDate.parse("2026-03-31")
                            val dayStart = day.atStartOfDay(ZoneOffset.UTC).toEpochSecond()
                            val dayEnd = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond()
                            val oldGeneration = generation("generation-old", day, revision = 0, amount = "100")
                            firstStore.currentDataRevision() shouldBe 1L
                            firstStore.publishDailyValues(oldGeneration.first, oldGeneration.second)
                            firstStore.currentDataRevision() shouldBe 2L

                            val runningJob = job(
                                generationId = "generation-next",
                                day = day,
                                revision = 0,
                                state = HistoryValuationJobState.RUNNING,
                                balancesProcessed = 0,
                                completedPriceTasks = 0,
                                completeValueDays = 0,
                            )
                            firstStore.upsertValuationJob(runningJob)
                            val scanId = "locked-ledger-import"
                            firstStore.beginSyncWindow(
                                HistorySyncWindow(
                                    accountIdentityDigest = ACCOUNT,
                                    feed = HistoryFeed.LEDGERS,
                                    scanId = scanId,
                                    startSec = dayStart,
                                    endSec = dayEnd - 1,
                                    purpose = HistorySyncPurpose.BACKFILL,
                                ),
                            )
                            val request = HistoryPageRequest(
                                accountIdentityDigest = ACCOUNT,
                                feed = HistoryFeed.LEDGERS,
                                scanId = scanId,
                                startSec = dayStart,
                                endSec = dayEnd - 1,
                                requestedCursor = null,
                                committedAt = Instant.parse("2026-04-01T00:00:00Z"),
                                purpose = HistorySyncPurpose.BACKFILL,
                            )
                            val page = ExchangeHistoryPage(
                                entries = listOf(ledgerEntry(dayStart)),
                                startSec = dayStart,
                                endSec = dayEnd - 1,
                                requestedCursor = null,
                                nextCursor = null,
                                count = 1L,
                                rawSize = 1,
                                valid = true,
                            )
                            val attemptedProgress = runningJob.copy(
                                balancesProcessed = 1,
                                balancesValid = 1,
                                nextDay = null,
                                lastProgressAt = Instant.parse("2026-04-01T00:00:01Z"),
                                updatedAt = Instant.parse("2026-04-01T00:00:01Z"),
                            )
                            val attemptedPublication = generation(
                                "generation-next",
                                day,
                                revision = 0,
                                amount = "200",
                            )

                            val blocker = firstDatabase.connect()
                            try {
                                blocker.autoCommit = false
                                blocker.createStatement().use { statement ->
                                    statement.executeUpdate(
                                        "UPDATE history_schema_meta SET initialized_at_ns = initialized_at_ns + 1 " +
                                            "WHERE id = 1",
                                    ) shouldBe 1
                                }

                                val startSignals = List(3) { CompletableDeferred<Unit>() }
                                val cancellationStarted = CompletableDeferred<Unit>()
                                val attempts = listOf(
                                    async(Dispatchers.IO) {
                                        busyAttempt("valuation progress", startSignals[0]) {
                                            secondStore.upsertValuationJob(attemptedProgress)
                                        }
                                    },
                                    async(Dispatchers.IO) {
                                        busyAttempt("ledger page", startSignals[1]) {
                                            secondStore.commitLedgerPage(request, page)
                                        }
                                    },
                                    async(Dispatchers.IO) {
                                        busyAttempt("daily publication", startSignals[2]) {
                                            secondStore.publishDailyValues(
                                                attemptedPublication.first,
                                                attemptedPublication.second,
                                            )
                                        }
                                    },
                                )
                                val cancelledProgress = async(Dispatchers.IO) {
                                    cancellationStarted.complete(Unit)
                                    val attempt = runCatching {
                                        withTimeoutOrNull(250) {
                                            secondStore.upsertValuationJob(
                                                attemptedProgress.copy(
                                                    balancesProcessed = 2,
                                                    lastProgressAt = Instant.parse("2026-04-01T00:00:02Z"),
                                                    updatedAt = Instant.parse("2026-04-01T00:00:02Z"),
                                                ),
                                            )
                                        }
                                    }
                                    (attempt.isSuccess && attempt.getOrNull() == null) ||
                                        attempt.exceptionOrNull().let { failure ->
                                            failure is CancellationException || failure?.hasBusySqliteCause() == true
                                        }
                                }
                                startSignals.forEach { it.await() }
                                cancellationStarted.await()
                                val results = attempts.awaitAll()
                                cancelledProgress.await() shouldBe true

                                results.map { it.operation } shouldBe
                                    listOf("valuation progress", "ledger page", "daily publication")
                                results.forEach { attempt ->
                                    attempt.isSqliteBusy shouldBe true
                                    (attempt.elapsedMillis in 4_000L..11_000L) shouldBe true
                                }

                                firstStore.currentLedgerRevision(ACCOUNT) shouldBe 0L
                                firstStore.currentDataRevision() shouldBe 2L
                                firstStore.queryLedgerCoverageWindows(ACCOUNT, dayStart, dayEnd) shouldBe emptyList()
                                firstStore.queryLedgers(
                                    HistoryEventQuery(accountIdentityDigest = ACCOUNT),
                                ).total shouldBe 0L
                                firstStore.loadValuationJob(ACCOUNT, HISTORY_ACCOUNT_DAILY_SCOPE)?.let { persisted ->
                                    persisted.generationId shouldBe runningJob.generationId
                                    persisted.balancesProcessed shouldBe 0
                                    persisted.completedPriceTasks shouldBe 0
                                }
                                val beforeRecovery = firstStore.queryDailyValues(
                                    ACCOUNT,
                                    HISTORY_ACCOUNT_DAILY_SCOPE,
                                )
                                beforeRecovery.items.map { it.generationId } shouldBe listOf("generation-old")
                                beforeRecovery.items.single().totalUsd shouldBe BigDecimal("100")
                            } finally {
                                blocker.rollback()
                                blocker.close()
                            }

                            val recoveredPage = secondStore.commitLedgerPage(request, page)
                            recoveredPage.state shouldBe HistorySyncState.COMPLETE
                            val revision = secondStore.currentLedgerRevision(ACCOUNT)
                            (revision > 0L) shouldBe true
                            secondStore.currentDataRevision() shouldBe 3L
                            val imported = secondStore.queryLedgers(
                                HistoryEventQuery(accountIdentityDigest = ACCOUNT),
                            )
                            imported.total shouldBe 1L
                            imported.items.single().ledgerId shouldBe "locked-ledger-row"

                            val progressed = job(
                                generationId = "generation-next",
                                day = day,
                                revision = revision,
                                state = HistoryValuationJobState.RUNNING,
                                balancesProcessed = 1,
                                completedPriceTasks = 1,
                                completeValueDays = 0,
                            )
                            secondStore.upsertValuationJob(progressed)
                            val recovered = generation(
                                "generation-next",
                                day,
                                revision = revision,
                                amount = "200",
                            )
                            secondStore.publishDailyValues(recovered.first, recovered.second)
                            secondStore.currentDataRevision() shouldBe 4L

                            val afterRecovery = firstStore.queryDailyValues(ACCOUNT, HISTORY_ACCOUNT_DAILY_SCOPE)
                            afterRecovery.items.map { it.generationId } shouldBe listOf("generation-next")
                            afterRecovery.items.single().totalUsd shouldBe BigDecimal("200")
                            firstStore.loadValuationJob(ACCOUNT, HISTORY_ACCOUNT_DAILY_SCOPE)?.state shouldBe
                                HistoryValuationJobState.COMPLETE
                        }
                    }
                } finally {
                    directory.toFile().deleteRecursively()
                }
            }
        }
    }

    private suspend fun busyAttempt(
        operation: String,
        started: CompletableDeferred<Unit>,
        write: suspend () -> Unit,
    ): BusyAttempt {
        started.complete(Unit)
        val start = System.nanoTime()
        val failure = runCatching { write() }.exceptionOrNull()
        return BusyAttempt(
            operation = operation,
            elapsedMillis = (System.nanoTime() - start) / 1_000_000,
            isSqliteBusy = failure?.hasBusySqliteCause() == true,
        )
    }

    private fun Connection.busyTimeoutMillis(): Int = createStatement().use { statement ->
        statement.executeQuery("PRAGMA busy_timeout").use { resultSet ->
            check(resultSet.next()) { "SQLite busy timeout was not returned." }
            resultSet.getInt(1)
        }
    }

    private fun Throwable.hasBusySqliteCause(): Boolean {
        var cause: Throwable? = this
        while (cause != null) {
            if (cause is SQLException &&
                (cause.errorCode == SQLITE_BUSY_CODE || cause.message.orEmpty().contains("SQLITE_BUSY"))
            ) {
                return true
            }
            if (cause.message.orEmpty().contains("database is locked", ignoreCase = true)) return true
            cause = cause.cause
        }
        return false
    }

    private fun ledgerEntry(dayStart: Long) = ExchangeLedgerHistoryEntry(
        ledgerId = "locked-ledger-row",
        refid = null,
        time = Instant.ofEpochSecond(dayStart + 1),
        rawTime = Instant.ofEpochSecond(dayStart + 1).toString(),
        type = "deposit",
        subtype = null,
        assetClass = "currency",
        asset = "ZUSD",
        amount = "1",
        fee = "0",
        balance = "1",
        rawRecordJson = "{}",
        hasValidTime = true,
        hasValidAmount = true,
        hasValidFee = true,
        hasValidBalance = true,
        isValidRecord = true,
    )

    private fun generation(
        generationId: String,
        day: LocalDate,
        revision: Long,
        amount: String,
    ): Pair<HistoryValuationJob, List<HistoryDailyValue>> {
        val job = job(
            generationId = generationId,
            day = day,
            revision = revision,
            state = HistoryValuationJobState.COMPLETE,
            balancesProcessed = 1,
            completedPriceTasks = 1,
            completeValueDays = 1,
        )
        val dayEnd = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()
        val value = BigDecimal(amount)
        return job to listOf(
            HistoryDailyValue(
                dayEnd = dayEnd,
                accountIdentityDigest = ACCOUNT,
                sourceAssetScope = HISTORY_ACCOUNT_DAILY_SCOPE,
                totalUsd = value,
                status = HistoryDailyValueStatus.COMPLETE,
                assets = listOf(
                    HistoryDailyAssetValue(
                        dayEnd = dayEnd,
                        accountIdentityDigest = ACCOUNT,
                        rawAssetClass = "currency",
                        rawAssetKey = "ZUSD",
                        quantity = value,
                        priceUsd = BigDecimal.ONE,
                        valueUsd = value,
                        pricePair = "USD",
                        priceCandleOpen = day.atStartOfDay(ZoneOffset.UTC).toInstant(),
                        priceSource = "EXACT_USD_UNIT",
                        status = HistoryDailyValueStatus.COMPLETE,
                    ),
                ),
                dayLabel = day,
                generationId = generationId,
                calculationPolicyVersion = HISTORY_DAILY_VALUE_POLICY_VERSION,
                sourceRevision = revision,
            ),
        )
    }

    private fun job(
        generationId: String,
        day: LocalDate,
        revision: Long,
        state: HistoryValuationJobState,
        balancesProcessed: Int,
        completedPriceTasks: Int,
        completeValueDays: Int,
    ) = HistoryValuationJob(
        accountIdentityDigest = ACCOUNT,
        sourceAssetScope = HISTORY_ACCOUNT_DAILY_SCOPE,
        generationId = generationId,
        state = state,
        stage = if (balancesProcessed == 0) {
            HistoryValuationStage.ESTABLISH_BALANCES
        } else {
            HistoryValuationStage.PRICE_AND_VALUE
        },
        targetStartDay = day,
        targetEndDay = day,
        nextDay = day.takeIf { balancesProcessed == 0 },
        targetDays = 1,
        balancesProcessed = balancesProcessed,
        balancesValid = balancesProcessed,
        balancesBlocked = 0,
        requiredPriceTasks = 1,
        completedPriceTasks = completedPriceTasks,
        completeValueDays = completeValueDays,
        blockedValueDays = 0,
        sourceRevision = revision,
        calculationPolicyVersion = HISTORY_DAILY_VALUE_POLICY_VERSION,
        lastProgressAt = Instant.parse("2026-04-01T00:00:00Z"),
        nextRetryAt = null,
        lastSuccessAt = Instant.parse("2026-04-01T00:00:00Z").takeIf {
            state == HistoryValuationJobState.COMPLETE
        },
        lastError = null,
        updatedAt = Instant.parse("2026-04-01T00:00:00Z"),
    )

    private data class BusyAttempt(val operation: String, val elapsedMillis: Long, val isSqliteBusy: Boolean)

    private companion object {
        const val ACCOUNT = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        const val SQLITE_BUSY_CODE = 5
    }
}
