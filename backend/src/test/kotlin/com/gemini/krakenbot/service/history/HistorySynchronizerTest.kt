package com.gemini.krakenbot.service.history

import com.gemini.krakenbot.config.DataDirectoryIdentity
import com.gemini.krakenbot.model.ExchangeHistoryPage
import com.gemini.krakenbot.model.ExchangeLedgerHistoryEntry
import com.gemini.krakenbot.model.ExchangeTradeHistoryEntry
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class HistorySynchronizerTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    private val krakenService = mockk<KrakenService>(relaxed = true)
    private val configService = mockk<ConfigService>(relaxed = true)
    private val dataDirectoryIdentity = mockk<DataDirectoryIdentity>(relaxed = true)
    private val store = mockk<HistoryStore>(relaxed = true)

    init {
        "syncs trades feed to completion" {
            coEvery { krakenService.getFundingEvidenceScope() } returns "scope-1"
            coEvery { krakenService.getAuthenticatedAccountIdentity() } returns "ACCOUNT_1"
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

        "syncs ledgers feed to completion" {
            coEvery { krakenService.getFundingEvidenceScope() } returns "scope-1"
            coEvery { krakenService.getAuthenticatedAccountIdentity() } returns "ACCOUNT_1"
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
            coEvery { krakenService.withStableBackend<Any?>(any()) } throws RuntimeException("Network down")

            runTest {
                val synchronizer = HistorySynchronizer(
                    applicationScope = backgroundScope,
                    krakenService = krakenService,
                    configService = configService,
                    dataDirectoryIdentity = dataDirectoryIdentity,
                    store = store,
                )
                shouldThrow<IllegalStateException> {
                    synchronizer.syncWindow(HistoryFeed.TRADES, 1_700_000_000, 1_700_010_000)
                }
            }
        }

        "fails sync when account identity scope changes during page fetch" {
            coEvery { krakenService.getFundingEvidenceScope() } returns "scope-1"
            coEvery { krakenService.getAuthenticatedAccountIdentity() } returnsMany listOf("ACCOUNT_1", "ACCOUNT_2")
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
            coEvery { krakenService.getAuthenticatedAccountIdentity() } returns "ACCOUNT_1"
            coEvery { krakenService.withStableBackend<Any?>(any()) } coAnswers {
                val block = firstArg<suspend (KrakenService) -> Any?>()
                block(krakenService)
            }

            var cycleActive = true
            val cycleGate = HistoryCycleGate {
                val current = cycleActive
                cycleActive = false
                current
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
            coEvery { krakenService.getAuthenticatedAccountIdentity() } returns "ACCOUNT_1"
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

        "startSync returns already active job when sync is in progress" {
            coEvery { krakenService.getFundingEvidenceScope() } returns "scope-1"
            coEvery { krakenService.getAuthenticatedAccountIdentity() } returns "ACCOUNT_1"
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
            coEvery { krakenService.getAuthenticatedAccountIdentity() } returns "ACCOUNT_1"
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
}
