@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.gemini.krakenbot.service.history

import com.gemini.krakenbot.config.DataDirectoryIdentity
import com.gemini.krakenbot.model.ExchangeHistoryPage
import com.gemini.krakenbot.model.ExchangeLedgerHistoryEntry
import com.gemini.krakenbot.service.AuthenticatedAccountInfo
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.KrakenService
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.CoroutineScope
import java.io.IOException
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Comparator

class HistoryCompoundImportRecoveryAcceptanceTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "keeps the published generation through malformed import and network failure, then repairs after reopen" {
            kotlinx.coroutines.test.runTest {
                val now = Instant.parse("2026-10-21T12:00:00Z")
                val targetEnd = now.atZone(ZoneOffset.UTC).toLocalDate().minusDays(1)
                val targetStart = targetEnd.minusDays(364)
                val lateDay = targetStart.plusDays(100)
                val startSec = targetStart.atStartOfDay(ZoneOffset.UTC).toEpochSecond()
                val lateScanStart = lateDay.atStartOfDay(ZoneOffset.UTC).toEpochSecond()
                val throughSec = targetEnd.plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond() - 1
                val directory = Files.createTempDirectory("history-compound-recovery-")
                val databasePath = directory.resolve("history.db")
                val digest = accountDigest("TEST HISTORY ACCOUNT")
                val identity = DataDirectoryIdentity(directory) { false }
                identity.bindOrVerify(digest)
                var database: HistoryDatabase? = null
                try {
                    database = HistoryDatabase(databasePath)
                    database.initializeFreshSchema()
                    var store = SqliteHistoryStore(database)
                    seedOpeningBalance(store, digest, now, throughSec, startSec)

                    val valuationAccount = VerifiedHistoryAccount(digest, null, null)
                    val valuationSynchronizer = mockk<HistorySynchronizer>()
                    coEvery { valuationSynchronizer.verifiedAccount() } returns valuationAccount
                    val priceService = mockk<KrakenService>()
                    val oldValues = runValuation(this, store, valuationSynchronizer, priceService, now)
                    oldValues.size shouldBe 365
                    oldValues.all {
                        it.status == HistoryDailyValueStatus.COMPLETE && it.totalUsd == BigDecimal("100")
                    } shouldBe
                        true
                    val oldGeneration = oldValues.first().generationId
                    val originalRevision = store.currentLedgerRevision(digest)
                    originalRevision shouldBe 1L
                    val firstPublished = store.queryDailyValueStatusSnapshot(digest, HISTORY_ACCOUNT_DAILY_SCOPE)
                    firstPublished.publishedGenerationId shouldBe oldGeneration
                    firstPublished.publishedSourceRevision shouldBe originalRevision

                    var networkFailure = true
                    val importer = ledgerSynchronizer(
                        scope = this,
                        store = store,
                        digest = digest,
                        identity = identity,
                        lateScanStart = lateScanStart,
                        throughSec = throughSec,
                        lateDay = lateDay,
                        networkFailure = { networkFailure },
                    )
                    val failed = importer.syncWindow(
                        feed = HistoryFeed.LEDGERS,
                        startSec = lateScanStart,
                        endSec = throughSec,
                        purpose = HistorySyncPurpose.RECENT,
                    )
                    failed.state shouldBe HistorySyncState.FAILED
                    failed.nextCursor shouldBe "repair-page"
                    (failed.lastError != null) shouldBe true
                    val failedRevision = store.currentLedgerRevision(digest)
                    failedRevision shouldBe originalRevision + 1
                    val importedAfterFailure = store.queryLedgers(
                        HistoryEventQuery(accountIdentityDigest = digest, limit = 20),
                    ).items
                    importedAfterFailure.map { it.ledgerId }.toSet() shouldBe
                        setOf("opening-cash", "malformed-late-row")
                    importedAfterFailure.single { it.ledgerId == "malformed-late-row" }.isValidRecord shouldBe false
                    store.queryDailyValueSnapshot(digest, HISTORY_ACCOUNT_DAILY_SCOPE).values
                        .map { it.generationId }.distinct() shouldBe listOf(oldGeneration)

                    database.close()
                    database = HistoryDatabase(databasePath)
                    store = SqliteHistoryStore(database)
                    val reopenedStatus = store.syncStatus(digest).single()
                    reopenedStatus.state shouldBe HistorySyncState.FAILED
                    reopenedStatus.nextCursor shouldBe "repair-page"
                    store.currentLedgerRevision(digest) shouldBe failedRevision
                    val stillPublished = store.queryDailyValueSnapshot(digest, HISTORY_ACCOUNT_DAILY_SCOPE).values
                    stillPublished.map { it.generationId }.distinct() shouldBe listOf(oldGeneration)
                    stillPublished.all { it.totalUsd == BigDecimal("100") } shouldBe true
                    val stillPublishedStatus = store.queryDailyValueStatusSnapshot(digest, HISTORY_ACCOUNT_DAILY_SCOPE)
                    stillPublishedStatus.publishedGenerationId shouldBe oldGeneration
                    stillPublishedStatus.publishedSourceRevision shouldBe originalRevision

                    networkFailure = false
                    val recoveredImporter = ledgerSynchronizer(
                        scope = this,
                        store = store,
                        digest = digest,
                        identity = identity,
                        lateScanStart = lateScanStart,
                        throughSec = throughSec,
                        lateDay = lateDay,
                        networkFailure = { networkFailure },
                    )
                    val resumed = recoveredImporter.syncWindow(
                        feed = HistoryFeed.LEDGERS,
                        startSec = lateScanStart,
                        endSec = throughSec,
                        purpose = HistorySyncPurpose.RECENT,
                        resumeCursor = reopenedStatus.nextCursor,
                    )
                    resumed.state shouldBe HistorySyncState.COMPLETE
                    resumed.nextCursor shouldBe null
                    store.currentLedgerRevision(digest) shouldBe failedRevision + 1
                    val importedAfterRecovery = store.queryLedgers(
                        HistoryEventQuery(accountIdentityDigest = digest, limit = 20),
                    ).items
                    importedAfterRecovery.size shouldBe 3
                    importedAfterRecovery.map { it.ledgerId }.toSet() shouldBe
                        setOf("opening-cash", "malformed-late-row", "late-balance-anchor")
                    importedAfterRecovery.single { it.ledgerId == "malformed-late-row" }.isValidRecord shouldBe false

                    val beforePublication = store.queryDailyValueSnapshot(digest, HISTORY_ACCOUNT_DAILY_SCOPE).values
                    beforePublication.map { it.generationId }.distinct() shouldBe listOf(oldGeneration)
                    store.queryDailyValueStatusSnapshot(digest, HISTORY_ACCOUNT_DAILY_SCOPE)
                        .publishedGenerationId shouldBe oldGeneration
                    val correctedValues = runValuation(this, store, valuationSynchronizer, priceService, now)
                    correctedValues.size shouldBe 365
                    correctedValues.map { it.generationId }.distinct().size shouldBe 1
                    (correctedValues.first().generationId != oldGeneration) shouldBe true
                    correctedValues.single { it.dayLabel == lateDay.minusDays(1) }.totalUsd shouldBe BigDecimal("100")
                    correctedValues.single { it.dayLabel == lateDay }.totalUsd shouldBe BigDecimal("101")
                    correctedValues.last().totalUsd shouldBe BigDecimal("101")
                    store.loadValuationJob(digest, HISTORY_ACCOUNT_DAILY_SCOPE)?.state shouldBe
                        HistoryValuationJobState.COMPLETE
                    val correctedStatus = store.queryDailyValueStatusSnapshot(digest, HISTORY_ACCOUNT_DAILY_SCOPE)
                    correctedStatus.publishedGenerationId shouldBe correctedValues.first().generationId
                    correctedStatus.publishedSourceRevision shouldBe failedRevision + 1

                    val revisionBeforeReplay = store.currentLedgerRevision(digest)
                    val replayStatus = recoveredImporter.syncWindow(
                        feed = HistoryFeed.LEDGERS,
                        startSec = lateScanStart,
                        endSec = throughSec,
                        purpose = HistorySyncPurpose.RECENT,
                    )
                    replayStatus.state shouldBe HistorySyncState.COMPLETE
                    store.currentLedgerRevision(digest) shouldBe revisionBeforeReplay
                    store.queryLedgers(
                        HistoryEventQuery(accountIdentityDigest = digest, limit = 20),
                    ).items.size shouldBe
                        3
                    store.queryDailyValueSnapshot(digest, HISTORY_ACCOUNT_DAILY_SCOPE).values
                        .map { it.generationId }.distinct() shouldBe listOf(correctedValues.first().generationId)
                } finally {
                    database?.close()
                    deleteDirectory(directory)
                }
            }
        }
    }

    private suspend fun seedOpeningBalance(
        store: HistoryStore,
        digest: String,
        now: Instant,
        throughSec: Long,
        startSec: Long,
    ) {
        val scanId = "initial-coverage"
        store.beginSyncWindow(
            HistorySyncWindow(
                accountIdentityDigest = digest,
                feed = HistoryFeed.LEDGERS,
                scanId = scanId,
                startSec = 0,
                endSec = throughSec,
                purpose = HistorySyncPurpose.BACKFILL,
            ),
        )
        val row = ledger("opening-cash", Instant.ofEpochSecond(startSec), "ZUSD", "100", "100")
        store.commitLedgerPage(
            HistoryPageRequest(
                accountIdentityDigest = digest,
                feed = HistoryFeed.LEDGERS,
                scanId = scanId,
                startSec = 0,
                endSec = throughSec,
                requestedCursor = null,
                committedAt = now,
                purpose = HistorySyncPurpose.BACKFILL,
            ),
            page(
                entries = listOf(row),
                startSec = 0,
                endSec = throughSec,
                requestedCursor = null,
                nextCursor = null,
            ),
        )
    }

    private fun ledgerSynchronizer(
        scope: CoroutineScope,
        store: HistoryStore,
        digest: String,
        identity: DataDirectoryIdentity,
        lateScanStart: Long,
        throughSec: Long,
        lateDay: LocalDate,
        networkFailure: () -> Boolean,
    ): HistorySynchronizer {
        val config = mockk<ConfigService>()
        coEvery { config.beginExecutionSession() } just runs
        coEvery { config.endExecutionSession() } just runs
        val kraken = mockk<KrakenService>()
        coEvery { kraken.getFundingEvidenceScope() } returns "test-scope"
        coEvery { kraken.getAuthenticatedAccountInfo() } returns
            AuthenticatedAccountInfo("TEST HISTORY ACCOUNT", queryBoundsVerified = true)
        coEvery { kraken.withStableBackend<Any?>(any()) } coAnswers {
            val block = firstArg<suspend (KrakenService) -> Any?>()
            block(kraken)
        }
        val malformedTime = lateDay.atTime(9, 0).toInstant(ZoneOffset.UTC)
        val repairTime = lateDay.atTime(10, 0).toInstant(ZoneOffset.UTC)
        coEvery { kraken.getAccountLedgerPage(any(), any(), any()) } coAnswers {
            val cursor = thirdArg<String?>()
            when (cursor) {
                null -> page(
                    entries = listOf(
                        ledger(
                            id = "malformed-late-row",
                            time = malformedTime,
                            asset = "ZUSD",
                            amount = "invalid",
                            balance = "invalid",
                            valid = false,
                        ),
                    ),
                    startSec = lateScanStart,
                    endSec = throughSec,
                    requestedCursor = null,
                    nextCursor = "repair-page",
                )

                "repair-page" -> {
                    if (networkFailure()) throw IOException("simulated temporary ledger network failure")
                    page(
                        entries = listOf(ledger("late-balance-anchor", repairTime, "ZUSD", "1", "101")),
                        startSec = lateScanStart,
                        endSec = throughSec,
                        requestedCursor = "repair-page",
                        nextCursor = null,
                    )
                }

                else -> error("Unexpected ledger cursor '$cursor'.")
            }
        }
        return HistorySynchronizer(
            applicationScope = scope,
            krakenService = kraken,
            configService = config,
            dataDirectoryIdentity = identity,
            store = store,
        )
    }

    private suspend fun runValuation(
        scope: CoroutineScope,
        store: HistoryStore,
        synchronizer: HistorySynchronizer,
        priceService: KrakenService,
        now: Instant,
    ): List<HistoryDailyValue> {
        HistoryValuationCoordinator(
            applicationScope = scope,
            synchronizer = synchronizer,
            store = store,
            priceResolver = HistoryDailyPriceResolver(priceService, nowProvider = { now }),
            nowProvider = { now },
            pollInterval = Duration.ofDays(1),
        ).runOnce()
        return store.queryDailyValueSnapshot(
            accountDigest("TEST HISTORY ACCOUNT"),
            HISTORY_ACCOUNT_DAILY_SCOPE,
        ).values
    }

    private fun ledger(
        id: String,
        time: Instant,
        asset: String,
        amount: String,
        balance: String,
        valid: Boolean = true,
    ) = ExchangeLedgerHistoryEntry(
        ledgerId = id,
        refid = null,
        time = time,
        rawTime = time.toString(),
        type = "deposit",
        subtype = null,
        assetClass = "currency",
        asset = asset,
        amount = amount,
        fee = "0",
        balance = balance,
        rawRecordJson = "{}",
        hasValidTime = true,
        hasValidAmount = valid,
        hasValidFee = true,
        hasValidBalance = valid,
        isValidRecord = valid,
    )

    private fun page(
        entries: List<ExchangeLedgerHistoryEntry>,
        startSec: Long,
        endSec: Long,
        requestedCursor: String?,
        nextCursor: String?,
    ) = ExchangeHistoryPage(
        entries = entries,
        startSec = startSec,
        endSec = endSec,
        requestedCursor = requestedCursor,
        nextCursor = nextCursor,
        count = entries.size.toLong(),
        rawSize = entries.size,
        valid = true,
    )

    private fun accountDigest(identity: String): String = MessageDigest.getInstance("SHA-256")
        .digest(identity.filterNot(Char::isWhitespace).uppercase().toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun deleteDirectory(directory: Path) {
        Files.walk(directory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}
