package com.gemini.krakenbot.service.history

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxy
import ch.qos.logback.core.read.ListAppender
import com.gemini.krakenbot.config.DataDirectoryIdentity
import com.gemini.krakenbot.model.ExchangeHistoryPage
import com.gemini.krakenbot.model.ExchangeLedgerHistoryEntry
import com.gemini.krakenbot.model.ExchangeTradeHistoryEntry
import com.gemini.krakenbot.service.AuthenticatedAccountInfo
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.KrakenService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.slf4j.LoggerFactory
import java.sql.SQLException
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class HistorySynchronizerTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    private val krakenService = mockk<KrakenService>(relaxed = true)
    private val configService = mockk<ConfigService>(relaxed = true)
    private val dataDirectoryIdentity = mockk<DataDirectoryIdentity>(relaxed = true)
    private val store = mockk<HistoryStore>(relaxed = true)

    private fun runningStatus(window: HistorySyncWindow) = HistorySyncStatus(
        accountIdentityDigest = window.accountIdentityDigest,
        feed = window.feed,
        scanId = window.scanId,
        state = HistorySyncState.RUNNING,
        windowStart = Instant.ofEpochSecond(window.startSec),
        windowEnd = Instant.ofEpochSecond(window.endSec),
        nextCursor = window.cursor,
        fetchedCount = 0L,
        storedCount = 0L,
        knownTotal = null,
        lastPageAt = null,
        lastError = null,
    )

    init {
        "syncs trades feed to completion" {
            coEvery { krakenService.getFundingEvidenceScope() } returns "scope-1"
            coEvery { krakenService.getAuthenticatedAccountInfo() } returns
                com.gemini.krakenbot.service.AuthenticatedAccountInfo("ACCOUNT_1", queryBoundsVerified = true)
            coEvery { krakenService.withStableBackend<Any?>(any()) } coAnswers {
                val block = firstArg<suspend (KrakenService) -> Any?>()
                block(krakenService)
            }

            val tradeEntry = ExchangeTradeHistoryEntry(
                tradeId = "T1",
                orderTxid = "O1",
                postTxid = "P1",
                pair = "XXBTZUSD",
                baseAsset = "BTC",
                quoteCurrency = "USD",
                feeCurrency = "USD",
                time = Instant.now(),
                rawTime = "1700000000",
                type = "buy",
                orderType = "limit",
                price = "50000",
                cost = "5000",
                fee = "8",
                volume = "0.1",
                margin = "0",
                misc = "",
                tradeSequenceId = "1",
                maker = true,
                ledgerIds = emptyList(),
                rawRecordJson = "{}",
                hasValidTime = true,
                hasValidPrice = true,
                hasValidCost = true,
                hasValidFee = true,
                hasValidVolume = true,
                isValidRecord = true,
            )

            coEvery { krakenService.getAccountTradePage(any(), any(), any(), any()) } returns ExchangeHistoryPage(
                entries = listOf(tradeEntry),
                startSec = 1_700_000_000,
                endSec = 1_700_010_000,
                requestedCursor = null,
                nextCursor = null,
                count = 1L,
                rawSize = 1,
                valid = true,
            )

            coEvery { store.beginSyncWindow(any()) } answers {
                val window = firstArg<HistorySyncWindow>()
                HistorySyncStatus(
                    accountIdentityDigest = window.accountIdentityDigest,
                    feed = window.feed,
                    scanId = window.scanId,
                    state = HistorySyncState.RUNNING,
                    windowStart = Instant.ofEpochSecond(window.startSec),
                    windowEnd = Instant.ofEpochSecond(window.endSec),
                    nextCursor = null,
                    fetchedCount = 0L,
                    storedCount = 0L,
                    knownTotal = null,
                    lastPageAt = null,
                    lastError = null,
                )
            }

            coEvery { store.commitTradePage(any(), any()) } answers {
                val req = firstArg<HistoryPageRequest>()
                HistorySyncStatus(
                    accountIdentityDigest = req.accountIdentityDigest,
                    feed = req.feed,
                    scanId = req.scanId,
                    state = HistorySyncState.COMPLETE,
                    windowStart = Instant.ofEpochSecond(req.startSec),
                    windowEnd = Instant.ofEpochSecond(req.endSec),
                    nextCursor = null,
                    fetchedCount = 1L,
                    storedCount = 1L,
                    knownTotal = 1L,
                    lastPageAt = Instant.now(),
                    lastError = null,
                )
            }

            runTest {
                val synchronizer = HistorySynchronizer(
                    applicationScope = backgroundScope,
                    krakenService = krakenService,
                    configService = configService,
                    dataDirectoryIdentity = dataDirectoryIdentity,
                    store = store,
                )
                val job = synchronizer.startSync(HistoryFeed.TRADES, 1_700_000_000, 1_700_010_000)
                job.join()

                coVerify { store.commitTradePage(any(), any()) }
            }
        }

        "intersects requested history windows with verified API-key dates" {
            val allowedStart = 1_700_000_100L
            val allowedEnd = 1_700_000_200L
            coEvery { krakenService.getFundingEvidenceScope() } returns "scope-1"
            coEvery { krakenService.getAuthenticatedAccountInfo() } returns
                com.gemini.krakenbot.service.AuthenticatedAccountInfo(
                    "ACCOUNT_1",
                    queryFromSec = allowedStart,
                    queryToSec = allowedEnd,
                    queryBoundsVerified = true,
                )
            coEvery { krakenService.withStableBackend<Any?>(any()) } coAnswers {
                val block = firstArg<suspend (KrakenService) -> Any?>()
                block(krakenService)
            }
            var begunWindow: HistorySyncWindow? = null
            coEvery { store.beginSyncWindow(any()) } answers {
                val window = firstArg<HistorySyncWindow>()
                begunWindow = window
                runningStatus(window)
            }
            coEvery { krakenService.getAccountTradePage(any(), any(), any(), any()) } returns
                ExchangeHistoryPage(
                    entries = emptyList(),
                    startSec = allowedStart,
                    endSec = allowedEnd,
                    requestedCursor = null,
                    nextCursor = null,
                    count = 0L,
                    rawSize = 0,
                    valid = true,
                )
            coEvery { store.commitTradePage(any(), any()) } answers {
                val request = firstArg<HistoryPageRequest>()
                runningStatus(
                    HistorySyncWindow(
                        accountIdentityDigest = request.accountIdentityDigest,
                        feed = request.feed,
                        scanId = request.scanId,
                        startSec = request.startSec,
                        endSec = request.endSec,
                    ),
                ).copy(state = HistorySyncState.COMPLETE)
            }

            runTest {
                val synchronizer = HistorySynchronizer(
                    applicationScope = backgroundScope,
                    krakenService = krakenService,
                    configService = configService,
                    dataDirectoryIdentity = dataDirectoryIdentity,
                    store = store,
                )
                val status = synchronizer.syncWindow(HistoryFeed.TRADES, allowedStart - 100, allowedEnd + 100)
                begunWindow?.startSec shouldBe allowedStart
                begunWindow?.endSec shouldBe allowedEnd
                status.state shouldBe HistorySyncState.COMPLETE
                coVerify { krakenService.getAccountTradePage(allowedStart, allowedEnd, null, any()) }
            }
        }

        "fails without fetching when the requested history range misses API-key dates" {
            val allowedStart = 1_700_000_200L
            val allowedEnd = 1_700_000_300L
            coEvery { krakenService.getFundingEvidenceScope() } returns "scope-1"
            coEvery { krakenService.getAuthenticatedAccountInfo() } returns
                com.gemini.krakenbot.service.AuthenticatedAccountInfo(
                    "ACCOUNT_1",
                    queryFromSec = allowedStart,
                    queryToSec = allowedEnd,
                    queryBoundsVerified = true,
                )
            coEvery { krakenService.withStableBackend<Any?>(any()) } coAnswers {
                val block = firstArg<suspend (KrakenService) -> Any?>()
                block(krakenService)
            }
            coEvery { store.beginSyncWindow(any()) } answers { runningStatus(firstArg<HistorySyncWindow>()) }
            coEvery { store.failSync(any(), any(), any(), any()) } just runs

            runTest {
                val synchronizer = HistorySynchronizer(
                    applicationScope = backgroundScope,
                    krakenService = krakenService,
                    configService = configService,
                    dataDirectoryIdentity = dataDirectoryIdentity,
                    store = store,
                )
                val status = synchronizer.syncWindow(HistoryFeed.TRADES, 1_700_000_000, 1_700_000_100)
                status.state shouldBe HistorySyncState.FAILED
                status.lastError shouldBe
                    "History sync window does not overlap this Kraken API key's allowed query dates."
                coVerify(exactly = 0) { krakenService.getAccountTradePage(any(), any(), any(), any()) }
                coVerify {
                    store.failSync(
                        any(),
                        HistoryFeed.TRADES,
                        any(),
                        "History sync window does not overlap this Kraken API key's allowed query dates.",
                    )
                }
            }
        }

        "fails closed when API-key query date restrictions cannot be verified" {
            coEvery { krakenService.getFundingEvidenceScope() } returns "scope-1"
            coEvery { krakenService.getAuthenticatedAccountInfo() } returns
                com.gemini.krakenbot.service.AuthenticatedAccountInfo("ACCOUNT_1", queryBoundsVerified = false)
            coEvery { krakenService.withStableBackend<Any?>(any()) } coAnswers {
                firstArg<suspend (KrakenService) -> Any?>()(krakenService)
            }
            coEvery { store.beginSyncWindow(any()) } answers { runningStatus(firstArg<HistorySyncWindow>()) }
            coEvery { store.failSync(any(), any(), any(), any()) } just runs

            runTest {
                val synchronizer = HistorySynchronizer(
                    applicationScope = backgroundScope,
                    krakenService = krakenService,
                    configService = configService,
                    dataDirectoryIdentity = dataDirectoryIdentity,
                    store = store,
                )
                val status = synchronizer.syncWindow(HistoryFeed.TRADES, 1_700_000_000, 1_700_000_100)

                status.state shouldBe HistorySyncState.FAILED
                status.lastError shouldBe
                    "History sync stopped because Kraken API-key time restrictions could not be verified."
                coVerify(exactly = 0) { krakenService.getAccountTradePage(any(), any(), any(), any()) }
            }
        }

        "stops before fetching if API-key query date restrictions change during a scan" {
            val allowed = com.gemini.krakenbot.service.AuthenticatedAccountInfo(
                "ACCOUNT_1",
                queryFromSec = 1_700_000_000,
                queryToSec = 1_700_000_100,
                queryBoundsVerified = true,
            )
            val narrowed = allowed.copy(queryFromSec = 1_700_000_050)
            coEvery { krakenService.getFundingEvidenceScope() } returns "scope-1"
            coEvery { krakenService.getAuthenticatedAccountInfo() } returnsMany listOf(allowed, allowed, narrowed)
            coEvery { krakenService.withStableBackend<Any?>(any()) } coAnswers {
                firstArg<suspend (KrakenService) -> Any?>()(krakenService)
            }
            coEvery { store.beginSyncWindow(any()) } answers { runningStatus(firstArg<HistorySyncWindow>()) }
            coEvery { store.failSync(any(), any(), any(), any()) } just runs

            runTest {
                val synchronizer = HistorySynchronizer(
                    applicationScope = backgroundScope,
                    krakenService = krakenService,
                    configService = configService,
                    dataDirectoryIdentity = dataDirectoryIdentity,
                    store = store,
                )
                val status = synchronizer.syncWindow(HistoryFeed.TRADES, 1_700_000_000, 1_700_000_100)

                status.state shouldBe HistorySyncState.FAILED
                status.lastError shouldBe "History sync stopped because Kraken API-key query restrictions changed."
                coVerify(exactly = 0) { krakenService.getAccountTradePage(any(), any(), any(), any()) }
            }
        }

        "syncs ledgers feed to completion" {
            coEvery { krakenService.getFundingEvidenceScope() } returns "scope-1"
            coEvery { krakenService.getAuthenticatedAccountInfo() } returns
                com.gemini.krakenbot.service.AuthenticatedAccountInfo("ACCOUNT_1", queryBoundsVerified = true)
            coEvery { krakenService.withStableBackend<Any?>(any()) } coAnswers {
                val block = firstArg<suspend (KrakenService) -> Any?>()
                block(krakenService)
            }

            val ledgerEntry = ExchangeLedgerHistoryEntry(
                ledgerId = "L1",
                refid = "T1",
                time = Instant.now(),
                rawTime = "1700000000",
                type = "trade",
                subtype = "",
                assetClass = "currency",
                asset = "XXBT",
                amount = "0.1",
                fee = "0.0001",
                balance = "1.5",
                rawRecordJson = "{}",
                hasValidTime = true,
                hasValidAmount = true,
                hasValidFee = true,
                hasValidBalance = true,
                isValidRecord = true,
            )

            coEvery { krakenService.getAccountLedgerPage(any(), any(), any()) } returns ExchangeHistoryPage(
                entries = listOf(ledgerEntry),
                startSec = 1_700_000_000,
                endSec = 1_700_010_000,
                requestedCursor = null,
                nextCursor = null,
                count = 1L,
                rawSize = 1,
                valid = true,
            )

            coEvery { store.beginSyncWindow(any()) } answers {
                val window = firstArg<HistorySyncWindow>()
                HistorySyncStatus(
                    accountIdentityDigest = window.accountIdentityDigest,
                    feed = window.feed,
                    scanId = window.scanId,
                    state = HistorySyncState.RUNNING,
                    windowStart = Instant.ofEpochSecond(window.startSec),
                    windowEnd = Instant.ofEpochSecond(window.endSec),
                    nextCursor = null,
                    fetchedCount = 0L,
                    storedCount = 0L,
                    knownTotal = null,
                    lastPageAt = null,
                    lastError = null,
                )
            }

            coEvery { store.commitLedgerPage(any(), any()) } answers {
                val req = firstArg<HistoryPageRequest>()
                HistorySyncStatus(
                    accountIdentityDigest = req.accountIdentityDigest,
                    feed = req.feed,
                    scanId = req.scanId,
                    state = HistorySyncState.COMPLETE,
                    windowStart = Instant.ofEpochSecond(req.startSec),
                    windowEnd = Instant.ofEpochSecond(req.endSec),
                    nextCursor = null,
                    fetchedCount = 1L,
                    storedCount = 1L,
                    knownTotal = 1L,
                    lastPageAt = Instant.now(),
                    lastError = null,
                )
            }

            runTest {
                val synchronizer = HistorySynchronizer(
                    applicationScope = backgroundScope,
                    krakenService = krakenService,
                    configService = configService,
                    dataDirectoryIdentity = dataDirectoryIdentity,
                    store = store,
                )
                val status = synchronizer.syncWindow(HistoryFeed.LEDGERS, 1_700_000_000, 1_700_010_000)
                status.state shouldBe HistorySyncState.COMPLETE
                coVerify { store.commitLedgerPage(any(), any()) }
            }
        }

        "rejects invalid time window where startSec exceeds endSec" {
            runTest {
                val synchronizer = HistorySynchronizer(
                    applicationScope = backgroundScope,
                    krakenService = krakenService,
                    configService = configService,
                    dataDirectoryIdentity = dataDirectoryIdentity,
                    store = store,
                )
                shouldThrow<IllegalArgumentException> {
                    synchronizer.startSync(HistoryFeed.TRADES, 100, 50)
                }
            }
        }

        "throws IllegalStateException when initial authentication fails" {
            val original = RuntimeException("Network down")
            coEvery { krakenService.withStableBackend<Any?>(any()) } throws original

            runTest {
                val synchronizer = HistorySynchronizer(
                    applicationScope = backgroundScope,
                    krakenService = krakenService,
                    configService = configService,
                    dataDirectoryIdentity = dataDirectoryIdentity,
                    store = store,
                )
                val failure = shouldThrow<IllegalStateException> {
                    synchronizer.syncWindow(HistoryFeed.TRADES, 1_700_000_000, 1_700_010_000)
                }
                failure.cause shouldBe original
            }
        }

        "does not create a scan when account identity, credentials, or directory binding is unavailable" {
            val account = com.gemini.krakenbot.service.AuthenticatedAccountInfo(
                "ACCOUNT_1",
                queryBoundsVerified = true,
            )
            coEvery { krakenService.getFundingEvidenceScope() } returnsMany
                listOf("scope-1", "scope-unavailable", "scope-1")
            coEvery { krakenService.getAuthenticatedAccountInfo() } returnsMany listOf(null, account)
            coEvery { krakenService.withStableBackend<Any?>(any()) } coAnswers {
                firstArg<suspend (KrakenService) -> Any?>()(krakenService)
            }
            coEvery { dataDirectoryIdentity.bindOrVerify(any()) } throws
                IllegalStateException("The account marker could not be verified.")

            runTest {
                val synchronizer = HistorySynchronizer(
                    applicationScope = backgroundScope,
                    krakenService = krakenService,
                    configService = configService,
                    dataDirectoryIdentity = dataDirectoryIdentity,
                    store = store,
                )

                repeat(3) {
                    shouldThrow<IllegalStateException> {
                        synchronizer.syncWindow(HistoryFeed.TRADES, 1_700_000_000, 1_700_000_100)
                    }
                }
                coVerify(exactly = 0) { store.beginSyncWindow(any()) }
                coVerify(exactly = 1) { dataDirectoryIdentity.bindOrVerify(any()) }
            }
        }

        "fails sync when account identity scope changes during page fetch" {
            coEvery { krakenService.getFundingEvidenceScope() } returns "scope-1"
            coEvery { krakenService.getAuthenticatedAccountInfo() } returnsMany listOf(
                com.gemini.krakenbot.service.AuthenticatedAccountInfo("ACCOUNT_1", queryBoundsVerified = true),
                com.gemini.krakenbot.service.AuthenticatedAccountInfo("ACCOUNT_2", queryBoundsVerified = true),
                com.gemini.krakenbot.service.AuthenticatedAccountInfo("ACCOUNT_2", queryBoundsVerified = true),
            )
            coEvery { krakenService.withStableBackend<Any?>(any()) } coAnswers {
                val block = firstArg<suspend (KrakenService) -> Any?>()
                block(krakenService)
            }
            coEvery { store.beginSyncWindow(any()) } answers {
                val window = firstArg<HistorySyncWindow>()
                HistorySyncStatus(
                    accountIdentityDigest = window.accountIdentityDigest,
                    feed = window.feed,
                    scanId = window.scanId,
                    state = HistorySyncState.RUNNING,
                    windowStart = Instant.ofEpochSecond(window.startSec),
                    windowEnd = Instant.ofEpochSecond(window.endSec),
                    nextCursor = null,
                    fetchedCount = 0L,
                    storedCount = 0L,
                    knownTotal = null,
                    lastPageAt = null,
                    lastError = null,
                )
            }
            coEvery { store.failSync(any(), any(), any(), any()) } just runs

            runTest {
                val synchronizer = HistorySynchronizer(
                    applicationScope = backgroundScope,
                    krakenService = krakenService,
                    configService = configService,
                    dataDirectoryIdentity = dataDirectoryIdentity,
                    store = store,
                )
                val status = synchronizer.syncWindow(HistoryFeed.TRADES, 1_700_000_000, 1_700_010_000)
                status.state shouldBe HistorySyncState.FAILED
                status.lastError shouldBe "History sync stopped because authenticated account or credentials changed."
                coVerify { store.failSync(any(), eq(HistoryFeed.TRADES), any(), any()) }
            }
        }

        "pauses and resumes when cycle gate is active" {
            coEvery { krakenService.getFundingEvidenceScope() } returns "scope-1"
            coEvery { krakenService.getAuthenticatedAccountInfo() } returns
                com.gemini.krakenbot.service.AuthenticatedAccountInfo("ACCOUNT_1", queryBoundsVerified = true)
            coEvery { krakenService.withStableBackend<Any?>(any()) } coAnswers {
                val block = firstArg<suspend (KrakenService) -> Any?>()
                block(krakenService)
            }

            var firstAttempt = true
            val cycleGate = HistoryCycleGate { block ->
                if (firstAttempt) {
                    firstAttempt = false
                    false
                } else {
                    block()
                    true
                }
            }

            coEvery { store.pauseSync(any(), any(), any()) } just runs
            coEvery { store.beginSyncWindow(any()) } answers {
                val window = firstArg<HistorySyncWindow>()
                HistorySyncStatus(
                    accountIdentityDigest = window.accountIdentityDigest,
                    feed = window.feed,
                    scanId = window.scanId,
                    state = HistorySyncState.RUNNING,
                    windowStart = Instant.ofEpochSecond(window.startSec),
                    windowEnd = Instant.ofEpochSecond(window.endSec),
                    nextCursor = null,
                    fetchedCount = 0L,
                    storedCount = 0L,
                    knownTotal = null,
                    lastPageAt = null,
                    lastError = null,
                )
            }

            coEvery { krakenService.getAccountTradePage(any(), any(), any(), any()) } returns ExchangeHistoryPage(
                entries = emptyList(),
                startSec = 1_700_000_000,
                endSec = 1_700_010_000,
                requestedCursor = null,
                nextCursor = null,
                count = 0L,
                rawSize = 0,
                valid = true,
            )

            coEvery { store.commitTradePage(any(), any()) } answers {
                val req = firstArg<HistoryPageRequest>()
                HistorySyncStatus(
                    accountIdentityDigest = req.accountIdentityDigest,
                    feed = req.feed,
                    scanId = req.scanId,
                    state = HistorySyncState.COMPLETE,
                    windowStart = Instant.ofEpochSecond(req.startSec),
                    windowEnd = Instant.ofEpochSecond(req.endSec),
                    nextCursor = null,
                    fetchedCount = 0L,
                    storedCount = 0L,
                    knownTotal = 0L,
                    lastPageAt = Instant.now(),
                    lastError = null,
                )
            }

            runTest {
                val synchronizer = HistorySynchronizer(
                    applicationScope = backgroundScope,
                    krakenService = krakenService,
                    configService = configService,
                    dataDirectoryIdentity = dataDirectoryIdentity,
                    store = store,
                    cycleGate = cycleGate,
                )
                val status = synchronizer.syncWindow(HistoryFeed.TRADES, 1_700_000_000, 1_700_010_000)
                status.state shouldBe HistorySyncState.COMPLETE
                coVerify { store.pauseSync(any(), eq(HistoryFeed.TRADES), any()) }
            }
        }

        "retries page once on exception and fails on second exception" {
            coEvery { krakenService.getFundingEvidenceScope() } returns "scope-1"
            coEvery { krakenService.getAuthenticatedAccountInfo() } returns
                com.gemini.krakenbot.service.AuthenticatedAccountInfo("ACCOUNT_1", queryBoundsVerified = true)
            coEvery { krakenService.withStableBackend<Any?>(any()) } coAnswers {
                val block = firstArg<suspend (KrakenService) -> Any?>()
                block(krakenService)
            }
            coEvery { krakenService.getAccountTradePage(any(), any(), any(), any()) } throws
                RuntimeException("API error")

            coEvery { store.beginSyncWindow(any()) } answers {
                val window = firstArg<HistorySyncWindow>()
                HistorySyncStatus(
                    accountIdentityDigest = window.accountIdentityDigest,
                    feed = window.feed,
                    scanId = window.scanId,
                    state = HistorySyncState.RUNNING,
                    windowStart = Instant.ofEpochSecond(window.startSec),
                    windowEnd = Instant.ofEpochSecond(window.endSec),
                    nextCursor = null,
                    fetchedCount = 0L,
                    storedCount = 0L,
                    knownTotal = null,
                    lastPageAt = null,
                    lastError = null,
                )
            }
            coEvery { store.failSync(any(), any(), any(), any()) } just runs

            runTest {
                val synchronizer = HistorySynchronizer(
                    applicationScope = backgroundScope,
                    krakenService = krakenService,
                    configService = configService,
                    dataDirectoryIdentity = dataDirectoryIdentity,
                    store = store,
                )
                val status = synchronizer.syncWindow(HistoryFeed.TRADES, 1_700_000_000, 1_700_010_000)
                status.state shouldBe HistorySyncState.FAILED
                status.lastError shouldBe "History page could not be validated after one bounded restart."
            }
        }

        "logs SQL page failures with the original stack trace and bounded retry context" {
            coEvery { krakenService.getFundingEvidenceScope() } returns "scope-1"
            coEvery { krakenService.getAuthenticatedAccountInfo() } returns
                AuthenticatedAccountInfo("ACCOUNT_1", queryBoundsVerified = true)
            coEvery { krakenService.withStableBackend<Any?>(any()) } coAnswers {
                firstArg<suspend (KrakenService) -> Any?>()(krakenService)
            }
            coEvery { store.beginSyncWindow(any()) } answers { runningStatus(firstArg<HistorySyncWindow>()) }
            coEvery { krakenService.getAccountTradePage(any(), any(), any(), any()) } returns ExchangeHistoryPage(
                entries = emptyList(),
                startSec = 1_700_000_000,
                endSec = 1_700_010_000,
                requestedCursor = null,
                nextCursor = null,
                count = 0L,
                rawSize = 0,
                valid = true,
            )
            val failure = SQLException("[SQLITE_BUSY] database is locked", "SQLITE_BUSY", 5)
            val rootCause = IllegalStateException("Synthetic storage failure")
            failure.initCause(rootCause)
            coEvery { store.commitTradePage(any(), any()) } throws failure
            coEvery { store.failSync(any(), any(), any(), any()) } just runs

            withHistoryLogs { events ->
                runTest {
                    val synchronizer = HistorySynchronizer(
                        applicationScope = backgroundScope,
                        krakenService = krakenService,
                        configService = configService,
                        dataDirectoryIdentity = dataDirectoryIdentity,
                        store = store,
                    )
                    val status = synchronizer.syncWindow(HistoryFeed.TRADES, 1_700_000_000, 1_700_010_000)
                    status.state shouldBe HistorySyncState.FAILED
                    val failures = events.filter { it.formattedMessage.contains("page failed") }
                    failures.size shouldBe 2
                    failures.forEachIndexed { restart, event ->
                        event.formattedMessage.contains("History trades page failed (scan=") shouldBe true
                        event.formattedMessage.contains("window=1700000000..1700010000") shouldBe true
                        event.formattedMessage.contains("restart=$restart/1") shouldBe true
                        event.formattedMessage.contains("ACCOUNT_1") shouldBe false
                        event.formattedMessage.contains(status.accountIdentityDigest) shouldBe false
                        val proxy = event.throwableProxy as ThrowableProxy
                        proxy.throwable shouldBe failure
                        proxy.message shouldBe "[SQLITE_BUSY] database is locked"
                        proxy.cause.message shouldBe "Synthetic storage failure"
                        proxy.stackTraceElementProxyArray.isNotEmpty() shouldBe true
                    }
                    coVerify(exactly = 2) { store.commitTradePage(any(), any()) }
                }
            }
        }

        "logs failures before the first page and rethrows them from the background job" {
            coEvery { krakenService.getFundingEvidenceScope() } returns "scope-1"
            coEvery { krakenService.getAuthenticatedAccountInfo() } returns
                AuthenticatedAccountInfo("ACCOUNT_1", queryBoundsVerified = true)
            coEvery { krakenService.withStableBackend<Any?>(any()) } coAnswers {
                firstArg<suspend (KrakenService) -> Any?>()(krakenService)
            }
            val failure = SQLException("Synthetic database unavailable")
            coEvery { store.beginSyncWindow(any()) } throws failure

            withHistoryLogs { events ->
                runTest {
                    var uncaught: Throwable? = null
                    val scope = CoroutineScope(
                        StandardTestDispatcher(testScheduler) + SupervisorJob() +
                            CoroutineExceptionHandler { _, error -> uncaught = error },
                    )
                    try {
                        val synchronizer = HistorySynchronizer(
                            applicationScope = scope,
                            krakenService = krakenService,
                            configService = configService,
                            dataDirectoryIdentity = dataDirectoryIdentity,
                            store = store,
                        )
                        synchronizer.startSync(HistoryFeed.LEDGERS, 1_700_000_000, 1_700_010_000).join()
                        uncaught shouldBe failure
                        val event = events.single()
                        event.formattedMessage shouldBe
                            "History ledgers sync failed (window=1700000000..1700010000)."
                        (event.throwableProxy as ThrowableProxy).throwable shouldBe failure
                        coVerify(exactly = 0) { krakenService.getAccountLedgerPage(any(), any(), any()) }
                    } finally {
                        scope.cancel()
                    }
                }
            }
        }

        "startSync returns already active job when sync is in progress" {
            coEvery { krakenService.getFundingEvidenceScope() } returns "scope-1"
            coEvery { krakenService.getAuthenticatedAccountInfo() } returns
                com.gemini.krakenbot.service.AuthenticatedAccountInfo("ACCOUNT_1", queryBoundsVerified = true)
            coEvery { krakenService.withStableBackend<Any?>(any()) } coAnswers {
                val block = firstArg<suspend (KrakenService) -> Any?>()
                block(krakenService)
            }
            coEvery { store.beginSyncWindow(any()) } answers {
                val window = firstArg<HistorySyncWindow>()
                HistorySyncStatus(
                    accountIdentityDigest = window.accountIdentityDigest,
                    feed = window.feed,
                    scanId = window.scanId,
                    state = HistorySyncState.RUNNING,
                    windowStart = Instant.ofEpochSecond(window.startSec),
                    windowEnd = Instant.ofEpochSecond(window.endSec),
                    nextCursor = null,
                    fetchedCount = 0L,
                    storedCount = 0L,
                    knownTotal = null,
                    lastPageAt = null,
                    lastError = null,
                )
            }
            coEvery { krakenService.getAccountTradePage(any(), any(), any(), any()) } returns ExchangeHistoryPage(
                entries = emptyList(),
                startSec = 1_700_000_000,
                endSec = 1_700_010_000,
                requestedCursor = null,
                nextCursor = null,
                count = 0L,
                rawSize = 0,
                valid = true,
            )
            coEvery { store.commitTradePage(any(), any()) } answers {
                val req = firstArg<HistoryPageRequest>()
                HistorySyncStatus(
                    accountIdentityDigest = req.accountIdentityDigest,
                    feed = req.feed,
                    scanId = req.scanId,
                    state = HistorySyncState.COMPLETE,
                    windowStart = Instant.ofEpochSecond(req.startSec),
                    windowEnd = Instant.ofEpochSecond(req.endSec),
                    nextCursor = null,
                    fetchedCount = 0L,
                    storedCount = 0L,
                    knownTotal = 0L,
                    lastPageAt = Instant.now(),
                    lastError = null,
                )
            }

            runTest {
                val synchronizer = HistorySynchronizer(
                    applicationScope = backgroundScope,
                    krakenService = krakenService,
                    configService = configService,
                    dataDirectoryIdentity = dataDirectoryIdentity,
                    store = store,
                )
                val job1 = synchronizer.startSync(HistoryFeed.TRADES, 1_700_000_000, 1_700_010_000)
                val job2 = synchronizer.startSync(HistoryFeed.TRADES, 1_700_000_000, 1_700_010_000)
                job1 shouldBe job2
                job1.join()
            }
        }

        "syncWindow restarts when committed status returns FAILED" {
            coEvery { krakenService.getFundingEvidenceScope() } returns "scope-1"
            coEvery { krakenService.getAuthenticatedAccountInfo() } returns
                com.gemini.krakenbot.service.AuthenticatedAccountInfo("ACCOUNT_1", queryBoundsVerified = true)
            coEvery { krakenService.withStableBackend<Any?>(any()) } coAnswers {
                val block = firstArg<suspend (KrakenService) -> Any?>()
                block(krakenService)
            }
            coEvery { krakenService.getAccountTradePage(any(), any(), any(), any()) } returns ExchangeHistoryPage(
                entries = emptyList(),
                startSec = 1_700_000_000,
                endSec = 1_700_010_000,
                requestedCursor = null,
                nextCursor = null,
                count = 0L,
                rawSize = 0,
                valid = true,
            )
            coEvery { store.beginSyncWindow(any()) } answers {
                val window = firstArg<HistorySyncWindow>()
                HistorySyncStatus(
                    accountIdentityDigest = window.accountIdentityDigest,
                    feed = window.feed,
                    scanId = window.scanId,
                    state = HistorySyncState.RUNNING,
                    windowStart = Instant.ofEpochSecond(window.startSec),
                    windowEnd = Instant.ofEpochSecond(window.endSec),
                    nextCursor = null,
                    fetchedCount = 0L,
                    storedCount = 0L,
                    knownTotal = null,
                    lastPageAt = null,
                    lastError = null,
                )
            }
            var commitCount = 0
            coEvery { store.commitTradePage(any(), any()) } answers {
                val req = firstArg<HistoryPageRequest>()
                commitCount++
                HistorySyncStatus(
                    accountIdentityDigest = req.accountIdentityDigest,
                    feed = req.feed,
                    scanId = req.scanId,
                    state = if (commitCount == 1) HistorySyncState.FAILED else HistorySyncState.COMPLETE,
                    windowStart = Instant.ofEpochSecond(req.startSec),
                    windowEnd = Instant.ofEpochSecond(req.endSec),
                    nextCursor = null,
                    fetchedCount = 0L,
                    storedCount = 0L,
                    knownTotal = 0L,
                    lastPageAt = Instant.now(),
                    lastError = if (commitCount == 1) "Page error" else null,
                )
            }
            coEvery { store.failSync(any(), any(), any(), any()) } just runs

            runTest {
                val synchronizer = HistorySynchronizer(
                    applicationScope = backgroundScope,
                    krakenService = krakenService,
                    configService = configService,
                    dataDirectoryIdentity = dataDirectoryIdentity,
                    store = store,
                )
                val status = synchronizer.syncWindow(HistoryFeed.TRADES, 1_700_000_000, 1_700_010_000)
                status.state shouldBe HistorySyncState.COMPLETE
                commitCount shouldBe 2
            }
        }

        "does not restart a scan after a repeated-cursor failure" {
            coEvery { krakenService.getFundingEvidenceScope() } returns "scope-1"
            coEvery { krakenService.getAuthenticatedAccountInfo() } returns
                com.gemini.krakenbot.service.AuthenticatedAccountInfo("ACCOUNT_1", queryBoundsVerified = true)
            coEvery { krakenService.withStableBackend<Any?>(any()) } coAnswers {
                firstArg<suspend (KrakenService) -> Any?>()(krakenService)
            }
            coEvery { store.beginSyncWindow(any()) } answers { runningStatus(firstArg<HistorySyncWindow>()) }
            coEvery { krakenService.getAccountTradePage(any(), any(), any(), any()) } returns ExchangeHistoryPage(
                entries = emptyList(),
                startSec = 1_700_000_000,
                endSec = 1_700_000_100,
                requestedCursor = null,
                nextCursor = "cycle-cursor",
                count = 0L,
                rawSize = 0,
                valid = true,
            )
            coEvery { store.commitTradePage(any(), any()) } answers {
                val request = firstArg<HistoryPageRequest>()
                runningStatus(
                    HistorySyncWindow(
                        accountIdentityDigest = request.accountIdentityDigest,
                        feed = request.feed,
                        scanId = request.scanId,
                        startSec = request.startSec,
                        endSec = request.endSec,
                    ),
                ).copy(
                    state = HistorySyncState.FAILED,
                    lastError = "Exchange trade cursor repeated within the current scan.",
                )
            }

            runTest {
                val synchronizer = HistorySynchronizer(
                    applicationScope = backgroundScope,
                    krakenService = krakenService,
                    configService = configService,
                    dataDirectoryIdentity = dataDirectoryIdentity,
                    store = store,
                )
                val status = synchronizer.syncWindow(HistoryFeed.TRADES, 1_700_000_000, 1_700_000_100)

                status.state shouldBe HistorySyncState.FAILED
                coVerify(exactly = 1) { store.beginSyncWindow(any()) }
                coVerify(exactly = 1) { store.commitTradePage(any(), any()) }
            }
        }

        "commits each trade cursor before fetching the next page" {
            val startSec = 1_700_000_000L
            val endSec = startSec + 100L
            coEvery { krakenService.getFundingEvidenceScope() } returns "scope-1"
            coEvery { krakenService.getAuthenticatedAccountInfo() } returns
                com.gemini.krakenbot.service.AuthenticatedAccountInfo("ACCOUNT_1", queryBoundsVerified = true)
            coEvery { krakenService.withStableBackend<Any?>(any()) } coAnswers {
                firstArg<suspend (KrakenService) -> Any?>()(krakenService)
            }
            coEvery { store.beginSyncWindow(any()) } answers { runningStatus(firstArg<HistorySyncWindow>()) }
            coEvery { krakenService.getAccountTradePage(startSec, endSec, any(), any()) } returnsMany listOf(
                ExchangeHistoryPage(
                    entries = emptyList(),
                    startSec = startSec,
                    endSec = endSec,
                    requestedCursor = null,
                    nextCursor = "cursor-next",
                    count = 0L,
                    rawSize = 0,
                    valid = true,
                ),
                ExchangeHistoryPage(
                    entries = emptyList(),
                    startSec = startSec,
                    endSec = endSec,
                    requestedCursor = "cursor-next",
                    nextCursor = null,
                    count = 0L,
                    rawSize = 0,
                    valid = true,
                ),
            )
            coEvery { store.commitTradePage(any(), any()) } answers {
                val request = firstArg<HistoryPageRequest>()
                val nextCursor = if (request.requestedCursor == null) "cursor-next" else null
                runningStatus(
                    HistorySyncWindow(
                        accountIdentityDigest = request.accountIdentityDigest,
                        feed = request.feed,
                        scanId = request.scanId,
                        startSec = request.startSec,
                        endSec = request.endSec,
                        cursor = nextCursor,
                    ),
                ).copy(state = if (nextCursor == null) HistorySyncState.COMPLETE else HistorySyncState.RUNNING)
            }

            runTest {
                val synchronizer = HistorySynchronizer(
                    applicationScope = backgroundScope,
                    krakenService = krakenService,
                    configService = configService,
                    dataDirectoryIdentity = dataDirectoryIdentity,
                    store = store,
                )
                val status = synchronizer.syncWindow(HistoryFeed.TRADES, startSec, endSec)

                status.state shouldBe HistorySyncState.COMPLETE
                coVerify(exactly = 2) { store.commitTradePage(any(), any()) }
                coVerify { krakenService.getAccountTradePage(startSec, endSec, null, any()) }
                coVerify { krakenService.getAccountTradePage(startSec, endSec, "cursor-next", any()) }
            }
        }

        "propagates cancellation without recording a page failure" {
            coEvery { krakenService.getFundingEvidenceScope() } returns "scope-1"
            coEvery { krakenService.getAuthenticatedAccountInfo() } returns
                com.gemini.krakenbot.service.AuthenticatedAccountInfo("ACCOUNT_1", queryBoundsVerified = true)
            coEvery { krakenService.withStableBackend<Any?>(any()) } coAnswers {
                firstArg<suspend (KrakenService) -> Any?>()(krakenService)
            }
            coEvery { store.beginSyncWindow(any()) } answers { runningStatus(firstArg<HistorySyncWindow>()) }
            coEvery { krakenService.getAccountTradePage(any(), any(), any(), any()) } throws
                CancellationException("test cancellation")

            withHistoryLogs { events ->
                runTest {
                    val synchronizer = HistorySynchronizer(
                        applicationScope = backgroundScope,
                        krakenService = krakenService,
                        configService = configService,
                        dataDirectoryIdentity = dataDirectoryIdentity,
                        store = store,
                    )

                    shouldThrow<CancellationException> {
                        synchronizer.syncWindow(HistoryFeed.TRADES, 1_700_000_000, 1_700_000_100)
                    }
                    val job = synchronizer.startSync(HistoryFeed.TRADES, 1_700_000_000, 1_700_000_100)
                    job.join()
                    job.isCancelled shouldBe true
                    events shouldBe emptyList()
                    coVerify(exactly = 0) { store.failSync(any(), any(), any(), any()) }
                }
            }
        }

        "syncWindow fails closed when backend identity is unavailable or blank" {
            coEvery { krakenService.getFundingEvidenceScope() } returns "scope-unavailable"
            coEvery { krakenService.withStableBackend<Any?>(any()) } coAnswers {
                val block = firstArg<suspend (KrakenService) -> Any?>()
                block(krakenService)
            }

            runTest {
                val synchronizer = HistorySynchronizer(
                    applicationScope = backgroundScope,
                    krakenService = krakenService,
                    configService = configService,
                    dataDirectoryIdentity = dataDirectoryIdentity,
                    store = store,
                )
                val failure = shouldThrow<IllegalStateException> {
                    synchronizer.syncWindow(HistoryFeed.TRADES, 1_700_000_000, 1_700_010_000)
                }
                failure.message shouldBe "History sync requires an authenticated, bound Kraken account."
            }
        }
    }

    private suspend fun withHistoryLogs(block: suspend (List<ILoggingEvent>) -> Unit) {
        val logger = LoggerFactory.getLogger(HistorySynchronizer::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        try {
            block(appender.list)
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }
}
