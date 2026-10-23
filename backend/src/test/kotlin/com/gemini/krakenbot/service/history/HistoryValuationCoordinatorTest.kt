@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.gemini.krakenbot.service.history

import com.gemini.krakenbot.model.ExchangeHistoryPage
import com.gemini.krakenbot.model.ExchangeLedgerHistoryEntry
import com.gemini.krakenbot.service.KrakenService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class HistoryValuationCoordinatorTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    private val accountDigest = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    init {
        "rebuilds one year without consuming retained balance rows before the target window" {
            runTest {
                val now = Instant.parse("2026-10-21T12:00:00Z")
                val targetEnd = LocalDate.parse("2026-10-20")
                val targetStart = targetEnd.minusDays(364)
                val scanEnd = targetEnd.plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond() - 1
                val observedAt = targetStart.atStartOfDay(ZoneOffset.UTC).toInstant()
                val scanId = "one-year-ledger-import"
                val database = HistoryDatabase.inMemory()
                try {
                    database.initializeFreshSchema()
                    val store = SqliteHistoryStore(database)
                    val scan = HistorySyncWindow(
                        accountIdentityDigest = accountDigest,
                        feed = HistoryFeed.LEDGERS,
                        scanId = scanId,
                        startSec = 0,
                        endSec = scanEnd,
                        purpose = HistorySyncPurpose.BACKFILL,
                    )
                    store.beginSyncWindow(scan)
                    store.commitLedgerPage(
                        request = HistoryPageRequest(
                            accountIdentityDigest = accountDigest,
                            feed = HistoryFeed.LEDGERS,
                            scanId = scanId,
                            startSec = 0,
                            endSec = scanEnd,
                            requestedCursor = null,
                            committedAt = now,
                            purpose = HistorySyncPurpose.BACKFILL,
                        ),
                        page = ExchangeHistoryPage(
                            entries = listOf(
                                ExchangeLedgerHistoryEntry(
                                    ledgerId = "year-opening-cash",
                                    refid = null,
                                    time = observedAt,
                                    rawTime = observedAt.toString(),
                                    type = "deposit",
                                    subtype = null,
                                    assetClass = "currency",
                                    asset = "ZUSD",
                                    amount = "100",
                                    fee = "0",
                                    balance = "100",
                                    rawRecordJson = "{}",
                                    hasValidTime = true,
                                    hasValidAmount = true,
                                    hasValidFee = true,
                                    hasValidBalance = true,
                                    isValidRecord = true,
                                ),
                                ExchangeLedgerHistoryEntry(
                                    ledgerId = "year-opening-btc",
                                    refid = null,
                                    time = observedAt,
                                    rawTime = observedAt.toString(),
                                    type = "deposit",
                                    subtype = null,
                                    assetClass = "currency",
                                    asset = "XXBT",
                                    amount = "1",
                                    fee = "0",
                                    balance = "1",
                                    rawRecordJson = "{}",
                                    hasValidTime = true,
                                    hasValidAmount = true,
                                    hasValidFee = true,
                                    hasValidBalance = true,
                                    isValidRecord = true,
                                ),
                            ),
                            startSec = 0,
                            endSec = scanEnd,
                            requestedCursor = null,
                            nextCursor = null,
                            count = 2,
                            rawSize = 2,
                            valid = true,
                        ),
                    )
                    store.saveDailyBalances(
                        (0 until 400).map { index ->
                            dailyBalance(
                                day = targetEnd.minusDays((399 - index).toLong()),
                                sourceRevision = 0,
                                generationId = "retained-older-generation",
                            )
                        },
                    )

                    val synchronizer = mockk<HistorySynchronizer>()
                    val krakenService = mockk<KrakenService>(relaxed = true)
                    val firstCandleOpen = targetStart.atStartOfDay(ZoneOffset.UTC).toEpochSecond()
                    coEvery { krakenService.getOHLC("XBTUSD", 1_440, firstCandleOpen) } returns
                        (0..364).map { dayOffset ->
                            (firstCandleOpen + dayOffset * HistoryDailyValueBuilder.SECONDS_PER_DAY) to
                                BigDecimal("100")
                        }
                    coEvery { synchronizer.verifiedAccount() } returns VerifiedHistoryAccount(accountDigest, null, null)
                    val coordinator = HistoryValuationCoordinator(
                        applicationScope = this,
                        synchronizer = synchronizer,
                        store = store,
                        priceResolver = HistoryDailyPriceResolver(krakenService, nowProvider = { now }),
                        nowProvider = { now },
                    )

                    coordinator.runOnce()

                    val job = store.loadValuationJob(accountDigest, HISTORY_ACCOUNT_DAILY_SCOPE)
                    job?.state shouldBe HistoryValuationJobState.COMPLETE
                    job?.targetStartDay shouldBe targetStart
                    job?.targetEndDay shouldBe targetEnd
                    job?.balancesProcessed shouldBe HistoryValuationCoordinator.TARGET_DAYS
                    job?.completeValueDays shouldBe HistoryValuationCoordinator.TARGET_DAYS
                    val values = store.queryDailyValues(accountDigest, HISTORY_ACCOUNT_DAILY_SCOPE, limit = 500)
                    values.total shouldBe HistoryValuationCoordinator.TARGET_DAYS.toLong()
                    values.items.size shouldBe HistoryValuationCoordinator.TARGET_DAYS
                    values.items.all {
                        it.status == HistoryDailyValueStatus.COMPLETE && it.totalUsd == BigDecimal("200") &&
                            it.assets.size == 2
                    } shouldBe true
                    val balances = store.queryDailyBalances(
                        accountIdentityDigest = accountDigest,
                        sourceAssetScope = HISTORY_ACCOUNT_DAILY_SCOPE,
                        from = targetStart.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant(),
                        until = targetEnd.plusDays(2).atStartOfDay(ZoneOffset.UTC).toInstant(),
                    )
                    balances.size shouldBe HistoryValuationCoordinator.TARGET_DAYS * 2
                    balances.map { it.dayLabel }.distinct().size shouldBe HistoryValuationCoordinator.TARGET_DAYS
                    store.queryDailyBalances(accountDigest, HISTORY_ACCOUNT_DAILY_SCOPE).size shouldBe
                        HistoryValuationCoordinator.TARGET_DAYS * 2 + 35
                    store.queryDailyPrices(
                        accountDigest,
                        "BTC",
                        firstCandleOpen,
                        targetEnd.plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond(),
                    ).size shouldBe
                        HistoryValuationCoordinator.TARGET_DAYS
                    coVerify(exactly = 1) { krakenService.getOHLC("XBTUSD", 1_440, firstCandleOpen) }
                } finally {
                    database.close()
                }
            }
        }

        "requires positive polling and bounded retry delays" {
            runTest {
                val synchronizer = mockk<HistorySynchronizer>()
                val store = mockk<HistoryStore>()
                val krakenService = mockk<KrakenService>()

                shouldThrow<IllegalArgumentException> {
                    HistoryValuationCoordinator(
                        this,
                        synchronizer,
                        store,
                        HistoryDailyPriceResolver(krakenService),
                        pollInterval = Duration.ZERO,
                    )
                }
                shouldThrow<IllegalArgumentException> {
                    HistoryValuationCoordinator(
                        this,
                        synchronizer,
                        store,
                        HistoryDailyPriceResolver(krakenService),
                        retryDelays = emptyList(),
                    )
                }
                shouldThrow<IllegalArgumentException> {
                    HistoryValuationCoordinator(
                        this,
                        synchronizer,
                        store,
                        HistoryDailyPriceResolver(krakenService),
                        retryDelays = listOf(Duration.ofSeconds(-1)),
                    )
                }
            }
        }

        "coalesces wakeups, supports manual retry, and stops its worker safely" {
            runTest {
                val synchronizer = mockk<HistorySynchronizer>()
                val store = mockk<HistoryStore>(relaxed = true)
                val krakenService = mockk<KrakenService>(relaxed = true)
                coEvery { synchronizer.verifiedAccount() } returns null
                val coordinator = HistoryValuationCoordinator(
                    applicationScope = this,
                    synchronizer = synchronizer,
                    store = store,
                    priceResolver = HistoryDailyPriceResolver(krakenService),
                    pollInterval = Duration.ofDays(1),
                )

                coordinator.runOnce()
                coordinator.start()
                coordinator.start()
                coordinator.requestRebuild()
                runCurrent()
                coordinator.retryFailedWork()
                runCurrent()
                coordinator.stop()
                coordinator.stop()

                coVerify(exactly = 3) { synchronizer.verifiedAccount() }
                coVerify(exactly = 0) { store.upsertValuationJob(any()) }
            }
        }

        "does not repeat completed, terminal, or not-yet-due valuation work" {
            runTest {
                val now = Instant.parse("2026-10-21T12:00:00Z")
                val account = VerifiedHistoryAccount(accountDigest, null, null)
                val synchronizer = mockk<HistorySynchronizer>()
                val store = mockk<HistoryStore>(relaxed = true)
                val krakenService = mockk<KrakenService>(relaxed = true)
                var state = HistoryValuationJobState.COMPLETE
                coEvery { synchronizer.verifiedAccount() } returns account
                coEvery { store.loadSyncJobs(accountDigest) } returns emptyList()
                coEvery { store.currentLedgerRevision(accountDigest) } returns 9L
                coEvery { store.loadValuationJob(accountDigest, HISTORY_ACCOUNT_DAILY_SCOPE) } coAnswers {
                    valuationJob(now, state)
                }
                val coordinator = HistoryValuationCoordinator(
                    applicationScope = this,
                    synchronizer = synchronizer,
                    store = store,
                    priceResolver = HistoryDailyPriceResolver(krakenService, nowProvider = { now }),
                    nowProvider = { now },
                )

                coordinator.runOnce()
                state = HistoryValuationJobState.NEEDS_ATTENTION
                coordinator.runOnce()
                state = HistoryValuationJobState.WAITING
                coordinator.runOnce()

                coVerify(exactly = 3) { synchronizer.verifiedAccount() }
                coVerify(exactly = 0) { store.queryDailyBalances(any(), any(), any(), any()) }
                coVerify(exactly = 0) { store.queryDailyCheckpointInputPage(any(), any(), any(), any()) }
                coVerify(exactly = 0) { store.upsertValuationJob(any()) }
            }
        }

        "resumes an interrupted balance generation from its durable day cursor" {
            runTest {
                val now = Instant.parse("2026-10-21T12:00:00Z")
                val account = VerifiedHistoryAccount(accountDigest, null, null)
                val previous = valuationJob(now, HistoryValuationJobState.RUNNING).copy(
                    stage = HistoryValuationStage.ESTABLISH_BALANCES,
                    balancesProcessed = 100,
                    balancesValid = 100,
                    balancesBlocked = 0,
                    requiredPriceTasks = null,
                    nextDay = valuationJob(now, HistoryValuationJobState.RUNNING).targetStartDay.plusDays(100),
                )
                val balances = (0 until previous.balancesProcessed).map { index ->
                    val day = previous.targetStartDay.plusDays(index.toLong())
                    HistoryDailyBalance(
                        dayEnd = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant(),
                        dayLabel = day,
                        accountIdentityDigest = accountDigest,
                        sourceAssetScope = HISTORY_ACCOUNT_DAILY_SCOPE,
                        rawAssetClass = "currency",
                        rawAssetKey = "USD",
                        quantity = BigDecimal.ZERO,
                        sourceLedgerId = null,
                        sourceObservedAt = null,
                        sourceRevision = previous.sourceRevision,
                        generationId = previous.generationId,
                        status = HistoryDailyBalanceStatus.VALID,
                    )
                }.toMutableList()
                var publishedJob: HistoryValuationJob? = null
                var publishedValues = emptyList<HistoryDailyValue>()
                var replacedDays = 0
                val synchronizer = mockk<HistorySynchronizer>()
                val store = mockk<HistoryStore>()
                val krakenService = mockk<KrakenService>()
                coEvery { synchronizer.verifiedAccount() } returns account
                coEvery { store.loadSyncJobs(accountDigest) } returns emptyList()
                coEvery { store.currentLedgerRevision(accountDigest) } returns previous.sourceRevision
                coEvery { store.loadValuationJob(accountDigest, HISTORY_ACCOUNT_DAILY_SCOPE) } returns previous
                coEvery { store.upsertValuationJob(any()) } just runs
                coEvery {
                    store.queryDailyBalances(accountDigest, HISTORY_ACCOUNT_DAILY_SCOPE, any(), any())
                } coAnswers { balances.toList() }
                coEvery {
                    store.queryDailyCheckpointInputPage(accountDigest, any(), any(), any())
                } returns HistoryPage(emptyList(), 0L, 500, 0, false)
                coEvery { store.queryLedgerCoverageWindows(accountDigest, any(), any()) } coAnswers {
                    listOf(HistoryLedgerCoverageWindow(0, arg<Long>(2) - 1))
                }
                coEvery { store.replaceDailyBalanceDay(any()) } coAnswers {
                    val dayBalances = firstArg<List<HistoryDailyBalance>>()
                    balances.removeAll { it.dayLabel == dayBalances.first().dayLabel }
                    balances += dayBalances
                    replacedDays++
                }
                coEvery { store.publishDailyValues(any(), any()) } coAnswers {
                    publishedJob = firstArg()
                    publishedValues = secondArg()
                }
                val coordinator = HistoryValuationCoordinator(
                    applicationScope = this,
                    synchronizer = synchronizer,
                    store = store,
                    priceResolver = HistoryDailyPriceResolver(krakenService, nowProvider = { now }),
                    nowProvider = { now },
                )

                coordinator.runOnce()

                replacedDays shouldBe HistoryValuationCoordinator.TARGET_DAYS - previous.balancesProcessed
                publishedJob?.state shouldBe HistoryValuationJobState.COMPLETE
                publishedJob?.balancesProcessed shouldBe HistoryValuationCoordinator.TARGET_DAYS
                publishedValues.size shouldBe HistoryValuationCoordinator.TARGET_DAYS
                publishedValues.all { it.totalUsd == BigDecimal.ZERO } shouldBe true
                coVerify(exactly = 1) {
                    store.queryDailyCheckpointInputPage(accountDigest, any(), any(), any())
                }
                coVerify(exactly = 0) { krakenService.getOHLC(any(), any(), any()) }
            }
        }

        "rebuilds a price stage when one persisted balance has a stale generation" {
            runTest {
                val now = Instant.parse("2026-10-21T12:00:00Z")
                val account = VerifiedHistoryAccount(accountDigest, null, null)
                val previous = valuationJob(now, HistoryValuationJobState.RUNNING).copy(
                    stage = HistoryValuationStage.PRICE_AND_VALUE,
                    balancesProcessed = HistoryValuationCoordinator.TARGET_DAYS,
                    balancesValid = HistoryValuationCoordinator.TARGET_DAYS,
                    requiredPriceTasks = 0,
                )
                val balances = (0 until HistoryValuationCoordinator.TARGET_DAYS).map { index ->
                    val day = previous.targetStartDay.plusDays(index.toLong())
                    dailyBalance(
                        day = day,
                        sourceRevision = previous.sourceRevision,
                        generationId = if (index == 17) "stale-generation" else previous.generationId,
                    )
                }.toMutableList()
                var publishedJob: HistoryValuationJob? = null
                var publishedValues = emptyList<HistoryDailyValue>()
                var replacedDays = 0
                val synchronizer = mockk<HistorySynchronizer>()
                val store = mockk<HistoryStore>()
                val krakenService = mockk<KrakenService>()
                coEvery { synchronizer.verifiedAccount() } returns account
                coEvery { store.loadSyncJobs(accountDigest) } returns emptyList()
                coEvery { store.currentLedgerRevision(accountDigest) } returns previous.sourceRevision
                coEvery { store.loadValuationJob(accountDigest, HISTORY_ACCOUNT_DAILY_SCOPE) } returns previous
                coEvery { store.upsertValuationJob(any()) } just runs
                coEvery {
                    store.queryDailyBalances(accountDigest, HISTORY_ACCOUNT_DAILY_SCOPE, any(), any())
                } coAnswers { balances.toList() }
                coEvery {
                    store.queryDailyCheckpointInputPage(accountDigest, any(), any(), any())
                } returns HistoryPage(emptyList(), 0L, 500, 0, false)
                coEvery { store.queryLedgerCoverageWindows(accountDigest, any(), any()) } coAnswers {
                    listOf(HistoryLedgerCoverageWindow(0, arg<Long>(2) - 1))
                }
                coEvery { store.replaceDailyBalanceDay(any()) } coAnswers {
                    val dayBalances = firstArg<List<HistoryDailyBalance>>()
                    balances.removeAll { it.dayLabel == dayBalances.first().dayLabel }
                    balances += dayBalances
                    replacedDays++
                }
                coEvery { store.publishDailyValues(any(), any()) } coAnswers {
                    publishedJob = firstArg()
                    publishedValues = secondArg()
                }
                val coordinator = HistoryValuationCoordinator(
                    applicationScope = this,
                    synchronizer = synchronizer,
                    store = store,
                    priceResolver = HistoryDailyPriceResolver(krakenService, nowProvider = { now }),
                    nowProvider = { now },
                )

                coordinator.runOnce()

                replacedDays shouldBe HistoryValuationCoordinator.TARGET_DAYS
                publishedJob?.state shouldBe HistoryValuationJobState.COMPLETE
                publishedValues.size shouldBe HistoryValuationCoordinator.TARGET_DAYS
                balances.all { it.generationId == previous.generationId } shouldBe true
                balances.all { it.status == HistoryDailyBalanceStatus.VALID } shouldBe true
                coVerify(exactly = 1) {
                    store.queryDailyCheckpointInputPage(accountDigest, any(), any(), any())
                }
                coVerify(exactly = 0) { krakenService.getOHLC(any(), any(), any()) }
            }
        }

        "aborts the balance pass when the durable checkpoint page total changes" {
            runTest {
                val now = Instant.parse("2026-10-21T12:00:00Z")
                val account = VerifiedHistoryAccount(accountDigest, null, null)
                val firstCheckpoint = checkpoint(
                    "checkpoint-one",
                    Instant.parse("2025-10-21T12:00:00Z"),
                    "100",
                    "100",
                )
                val synchronizer = mockk<HistorySynchronizer>()
                val store = mockk<HistoryStore>()
                val krakenService = mockk<KrakenService>()
                coEvery { synchronizer.verifiedAccount() } returns account
                coEvery { store.loadSyncJobs(accountDigest) } returns emptyList()
                coEvery { store.currentLedgerRevision(accountDigest) } returns 9L
                coEvery { store.loadValuationJob(accountDigest, HISTORY_ACCOUNT_DAILY_SCOPE) } returns null
                coEvery { store.upsertValuationJob(any()) } just runs
                coEvery {
                    store.queryDailyBalances(accountDigest, HISTORY_ACCOUNT_DAILY_SCOPE, any(), any())
                } returns emptyList()
                coEvery {
                    store.queryDailyCheckpointInputPage(accountDigest, any(), any(), 0)
                } returns HistoryPage(listOf(firstCheckpoint), 2L, 500, 0, true)
                coEvery {
                    store.queryDailyCheckpointInputPage(accountDigest, any(), any(), 1)
                } returns HistoryPage(emptyList(), 3L, 500, 1, false)
                val coordinator = HistoryValuationCoordinator(
                    applicationScope = this,
                    synchronizer = synchronizer,
                    store = store,
                    priceResolver = HistoryDailyPriceResolver(krakenService, nowProvider = { now }),
                    nowProvider = { now },
                )

                shouldThrow<IllegalStateException> { coordinator.runOnce() }

                coVerify(exactly = 0) { store.replaceDailyBalanceDay(any()) }
                coVerify(exactly = 0) { store.publishDailyValues(any(), any()) }
            }
        }

        "requests a clean rebuild when the ledger changes at atomic publication" {
            runTest {
                val now = Instant.parse("2026-10-21T12:00:00Z")
                val account = VerifiedHistoryAccount(accountDigest, null, null)
                val balances = mutableListOf<HistoryDailyBalance>()
                var revisionReads = 0
                var publicationAttempts = 0
                val synchronizer = mockk<HistorySynchronizer>()
                val store = mockk<HistoryStore>()
                val krakenService = mockk<KrakenService>()
                coEvery { synchronizer.verifiedAccount() } returns account
                coEvery { store.loadSyncJobs(accountDigest) } returns emptyList()
                coEvery { store.currentLedgerRevision(accountDigest) } coAnswers {
                    revisionReads++
                    if (revisionReads <= HistoryValuationCoordinator.TARGET_DAYS + 3) 9L else 10L
                }
                coEvery { store.loadValuationJob(accountDigest, HISTORY_ACCOUNT_DAILY_SCOPE) } returns null
                coEvery { store.upsertValuationJob(any()) } just runs
                coEvery {
                    store.queryDailyCheckpointInputPage(accountDigest, any(), any(), any())
                } returns HistoryPage(emptyList(), 0L, 500, 0, false)
                coEvery { store.queryLedgerCoverageWindows(accountDigest, any(), any()) } coAnswers {
                    listOf(HistoryLedgerCoverageWindow(0, arg<Long>(2) - 1))
                }
                coEvery {
                    store.queryDailyBalances(accountDigest, HISTORY_ACCOUNT_DAILY_SCOPE, any(), any())
                } coAnswers { balances.toList() }
                coEvery { store.replaceDailyBalanceDay(any()) } coAnswers {
                    val dayBalances = firstArg<List<HistoryDailyBalance>>()
                    balances.removeAll { it.dayLabel == dayBalances.first().dayLabel }
                    balances += dayBalances
                }
                coEvery { store.publishDailyValues(any(), any()) } coAnswers {
                    publicationAttempts++
                    throw IllegalStateException(
                        "Ledger evidence changed while this valuation generation was being calculated.",
                    )
                }
                val coordinator = HistoryValuationCoordinator(
                    applicationScope = this,
                    synchronizer = synchronizer,
                    store = store,
                    priceResolver = HistoryDailyPriceResolver(krakenService, nowProvider = { now }),
                    nowProvider = { now },
                )

                coordinator.runOnce()

                publicationAttempts shouldBe 1
                revisionReads shouldBe HistoryValuationCoordinator.TARGET_DAYS + 4
                balances.size shouldBe HistoryValuationCoordinator.TARGET_DAYS
                coVerify(exactly = 1) { store.publishDailyValues(any(), any()) }
                coVerify(exactly = 0) { krakenService.getOHLC(any(), any(), any()) }
            }
        }

        "reuses valid cached daily prices and replaces malformed USD cache rows at par" {
            runTest {
                val now = Instant.parse("2026-10-21T12:00:00Z")
                val account = VerifiedHistoryAccount(accountDigest, null, null)
                val existing = valuationJob(now, HistoryValuationJobState.RUNNING).copy(
                    generationId = "cached-generation",
                    stage = HistoryValuationStage.PRICE_AND_VALUE,
                    requiredPriceTasks = 365,
                )
                val balances = (0 until HistoryValuationCoordinator.TARGET_DAYS).map { index ->
                    val day = existing.targetStartDay.plusDays(index.toLong())
                    HistoryDailyBalance(
                        dayEnd = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant(),
                        dayLabel = day,
                        accountIdentityDigest = accountDigest,
                        sourceAssetScope = HISTORY_ACCOUNT_DAILY_SCOPE,
                        rawAssetClass = "currency",
                        rawAssetKey = "ZUSD",
                        quantity = BigDecimal("100"),
                        sourceLedgerId = "ledger-$index",
                        sourceObservedAt = day.atStartOfDay(ZoneOffset.UTC).toInstant(),
                        sourceRevision = 9,
                        generationId = existing.generationId,
                        status = HistoryDailyBalanceStatus.VALID,
                    )
                }
                val prices = balances.mapIndexed { index, balance ->
                    val candleOpen = balance.dayLabel.atStartOfDay(ZoneOffset.UTC).toInstant()
                    HistoryDailyPrice(
                        accountIdentityDigest = accountDigest,
                        rawAssetKey = if (index == 0) "ZUSD" else "USD",
                        pair = "USD",
                        candleOpen = candleOpen,
                        candleEnd = if (index == 4) candleOpen else balance.dayEnd,
                        closeUsd = when (index) {
                            0 -> BigDecimal.ZERO
                            1 -> BigDecimal("0.99")
                            2 -> BigDecimal("1.01")
                            3 -> BigDecimal("-1")
                            else -> BigDecimal.ONE
                        },
                        source = "EXACT_USD_UNIT",
                    )
                }
                var publishedJob: HistoryValuationJob? = null
                var publishedValues = emptyList<HistoryDailyValue>()
                var savedPrices = emptyList<HistoryDailyPrice>()
                val synchronizer = mockk<HistorySynchronizer>()
                val store = mockk<HistoryStore>()
                val krakenService = mockk<KrakenService>()
                coEvery { synchronizer.verifiedAccount() } returns account
                coEvery { store.loadSyncJobs(accountDigest) } returns emptyList()
                coEvery { store.currentLedgerRevision(accountDigest) } returns 9L
                coEvery { store.loadValuationJob(accountDigest, HISTORY_ACCOUNT_DAILY_SCOPE) } returns existing
                coEvery { store.upsertValuationJob(any()) } just runs
                coEvery { store.queryDailyBalances(accountDigest, HISTORY_ACCOUNT_DAILY_SCOPE, any(), any()) } returns
                    balances
                coEvery { store.queryDailyPrices(accountDigest, "ZUSD", any(), any()) } returns
                    prices.filter { it.rawAssetKey == "ZUSD" }
                coEvery { store.queryDailyPrices(accountDigest, "USD", any(), any()) } returns
                    prices.filter { it.rawAssetKey == "USD" }
                coEvery { store.saveDailyPrices(any()) } coAnswers { savedPrices = firstArg() }
                coEvery { store.publishDailyValues(any(), any()) } coAnswers {
                    publishedJob = firstArg()
                    publishedValues = secondArg()
                }
                val coordinator = HistoryValuationCoordinator(
                    applicationScope = this,
                    synchronizer = synchronizer,
                    store = store,
                    priceResolver = HistoryDailyPriceResolver(krakenService, nowProvider = { now }),
                    nowProvider = { now },
                )

                coordinator.runOnce()

                publishedJob?.state shouldBe HistoryValuationJobState.COMPLETE
                publishedJob?.completedPriceTasks shouldBe 365
                publishedJob?.completeValueDays shouldBe 365
                publishedValues.size shouldBe 365
                publishedValues.all { it.totalUsd == BigDecimal("100") } shouldBe true
                publishedValues.all { it.status == HistoryDailyValueStatus.COMPLETE } shouldBe true
                savedPrices.size shouldBe 5
                savedPrices.all { it.closeUsd == BigDecimal.ONE && it.candleEnd > it.candleOpen } shouldBe true
                savedPrices.all { it.rawAssetKey == "USD" } shouldBe true
                coVerify(exactly = 1) { store.queryDailyPrices(accountDigest, "ZUSD", any(), any()) }
                coVerify(exactly = 1) { store.queryDailyPrices(accountDigest, "USD", any(), any()) }
                coVerify(exactly = 0) { krakenService.getOHLC(any(), any(), any()) }
            }
        }

        "consumes a valid cached Kraken USD alias for cash valuation without fetching it again" {
            runTest {
                val now = Instant.parse("2026-10-21T12:00:00Z")
                val account = VerifiedHistoryAccount(accountDigest, null, null)
                val existing = valuationJob(now, HistoryValuationJobState.RUNNING).copy(
                    generationId = "cached-zusd-generation",
                    sourceRevision = 9,
                    requiredPriceTasks = HistoryValuationCoordinator.TARGET_DAYS,
                )
                val balances = (0 until HistoryValuationCoordinator.TARGET_DAYS).map { index ->
                    val day = existing.targetStartDay.plusDays(index.toLong())
                    HistoryDailyBalance(
                        dayEnd = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant(),
                        dayLabel = day,
                        accountIdentityDigest = accountDigest,
                        sourceAssetScope = HISTORY_ACCOUNT_DAILY_SCOPE,
                        rawAssetClass = "currency",
                        rawAssetKey = "ZUSD",
                        quantity = BigDecimal("100"),
                        sourceLedgerId = "usd-$index",
                        sourceObservedAt = day.atStartOfDay(ZoneOffset.UTC).toInstant(),
                        sourceRevision = 9,
                        generationId = existing.generationId,
                        status = HistoryDailyBalanceStatus.VALID,
                    )
                }
                val cachedPrices = balances.map { balance ->
                    val open = balance.dayLabel.atStartOfDay(ZoneOffset.UTC).toInstant()
                    HistoryDailyPrice(
                        accountIdentityDigest = accountDigest,
                        rawAssetKey = "ZUSD",
                        pair = "USD",
                        candleOpen = open,
                        candleEnd = balance.dayEnd,
                        closeUsd = BigDecimal.ONE,
                        source = "EXACT_USD_UNIT",
                    )
                }
                var publishedJob: HistoryValuationJob? = null
                var publishedValues = emptyList<HistoryDailyValue>()
                var savedPrices = emptyList<HistoryDailyPrice>()
                val synchronizer = mockk<HistorySynchronizer>()
                val store = mockk<HistoryStore>()
                val krakenService = mockk<KrakenService>()
                coEvery { synchronizer.verifiedAccount() } returns account
                coEvery { store.loadSyncJobs(accountDigest) } returns emptyList()
                coEvery { store.currentLedgerRevision(accountDigest) } returns 9L
                coEvery { store.loadValuationJob(accountDigest, HISTORY_ACCOUNT_DAILY_SCOPE) } returns existing
                coEvery { store.upsertValuationJob(any()) } just runs
                coEvery { store.queryDailyBalances(accountDigest, HISTORY_ACCOUNT_DAILY_SCOPE, any(), any()) } returns
                    balances
                coEvery { store.queryDailyPrices(accountDigest, "ZUSD", any(), any()) } returns cachedPrices
                coEvery { store.queryDailyPrices(accountDigest, "USD", any(), any()) } returns emptyList()
                coEvery { store.saveDailyPrices(any()) } coAnswers { savedPrices = firstArg() }
                coEvery { store.publishDailyValues(any(), any()) } coAnswers {
                    publishedJob = firstArg()
                    publishedValues = secondArg()
                }

                HistoryValuationCoordinator(
                    applicationScope = this,
                    synchronizer = synchronizer,
                    store = store,
                    priceResolver = HistoryDailyPriceResolver(krakenService, nowProvider = { now }),
                    nowProvider = { now },
                ).runOnce()

                publishedJob?.state shouldBe HistoryValuationJobState.COMPLETE
                publishedJob?.completeValueDays shouldBe HistoryValuationCoordinator.TARGET_DAYS
                publishedJob?.blockedValueDays shouldBe 0
                publishedJob?.completedPriceTasks shouldBe HistoryValuationCoordinator.TARGET_DAYS
                publishedValues.size shouldBe HistoryValuationCoordinator.TARGET_DAYS
                publishedValues.all { it.generationId == existing.generationId } shouldBe true
                publishedValues.all { it.status == HistoryDailyValueStatus.COMPLETE } shouldBe true
                publishedValues.all { it.totalUsd == BigDecimal("100") } shouldBe true
                publishedValues.flatMap { it.assets }.all { asset ->
                    asset.rawAssetKey == "ZUSD" &&
                        asset.priceSource == "EXACT_USD_UNIT" &&
                        asset.priceUsd == BigDecimal.ONE &&
                        asset.valueUsd == BigDecimal("100")
                } shouldBe true
                savedPrices shouldBe emptyList()
                coVerify(exactly = 1) { store.queryDailyPrices(accountDigest, "ZUSD", any(), any()) }
                coVerify(exactly = 1) { store.queryDailyPrices(accountDigest, "USD", any(), any()) }
                coVerify(exactly = 0) { krakenService.getOHLC(any(), any(), any()) }
            }
        }

        "repairs off-par cash cache durably and reuses exact unit prices after reopening SQLite" {
            runTest {
                val now = Instant.parse("2026-10-21T12:00:00Z")
                val directory = Files.createTempDirectory("history-usd-cache-repair-")
                val path = directory.resolve("history.db")
                val account = VerifiedHistoryAccount(accountDigest, null, null)
                val existing = valuationJob(now, HistoryValuationJobState.RUNNING).copy(
                    generationId = "cash-generation",
                    sourceRevision = 0,
                    requiredPriceTasks = HistoryValuationCoordinator.TARGET_DAYS,
                )
                val balances = (0 until HistoryValuationCoordinator.TARGET_DAYS).map { index ->
                    val day = existing.targetStartDay.plusDays(index.toLong())
                    HistoryDailyBalance(
                        dayEnd = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant(),
                        dayLabel = day,
                        accountIdentityDigest = accountDigest,
                        sourceAssetScope = HISTORY_ACCOUNT_DAILY_SCOPE,
                        rawAssetClass = "currency",
                        rawAssetKey = "ZUSD",
                        quantity = BigDecimal("100"),
                        sourceLedgerId = "cash-$index",
                        sourceObservedAt = day.atStartOfDay(ZoneOffset.UTC).toInstant(),
                        sourceRevision = 0,
                        generationId = existing.generationId,
                        status = HistoryDailyBalanceStatus.VALID,
                    )
                }
                val offParPrices = balances.map { balance ->
                    val open = balance.dayLabel.atStartOfDay(ZoneOffset.UTC).toInstant()
                    HistoryDailyPrice(
                        accountIdentityDigest = accountDigest,
                        rawAssetKey = "ZUSD",
                        pair = "USD",
                        candleOpen = open,
                        candleEnd = balance.dayEnd,
                        closeUsd = BigDecimal("0.99"),
                        source = "LEGACY_CACHE",
                    )
                }
                val synchronizer = mockk<HistorySynchronizer>()
                coEvery { synchronizer.verifiedAccount() } returns account

                val firstDatabase = HistoryDatabase(path)
                firstDatabase.initializeFreshSchema()
                try {
                    val firstStore = SqliteHistoryStore(firstDatabase)
                    firstStore.saveDailyBalances(balances)
                    firstStore.saveDailyPrices(offParPrices)
                    firstStore.upsertValuationJob(existing)
                    val firstKraken = mockk<KrakenService>(relaxed = true)

                    HistoryValuationCoordinator(
                        applicationScope = this,
                        synchronizer = synchronizer,
                        store = firstStore,
                        priceResolver = HistoryDailyPriceResolver(firstKraken, nowProvider = { now }),
                        nowProvider = { now },
                    ).runOnce()

                    val repaired = firstStore.queryDailyPrices(
                        accountDigest,
                        "USD",
                        existing.targetStartDay.atStartOfDay(ZoneOffset.UTC).toEpochSecond(),
                        existing.targetEndDay.plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond(),
                    )
                    repaired.size shouldBe HistoryValuationCoordinator.TARGET_DAYS
                    repaired.all { it.closeUsd == BigDecimal.ONE && it.source == "EXACT_USD_UNIT" } shouldBe true
                    coVerify(exactly = 0) { firstKraken.getOHLC(any(), any(), any()) }
                } finally {
                    firstDatabase.close()
                }

                val reopenedDatabase = HistoryDatabase(path)
                try {
                    val reopenedStore = SqliteHistoryStore(reopenedDatabase)
                    val cachedAfterRestart = reopenedStore.queryDailyPrices(
                        accountDigest,
                        "USD",
                        existing.targetStartDay.atStartOfDay(ZoneOffset.UTC).toEpochSecond(),
                        existing.targetEndDay.plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond(),
                    )
                    cachedAfterRestart.size shouldBe HistoryValuationCoordinator.TARGET_DAYS
                    cachedAfterRestart.all { it.closeUsd.compareTo(BigDecimal.ONE) == 0 } shouldBe true
                    val restartedKraken = mockk<KrakenService>(relaxed = true)

                    HistoryValuationCoordinator(
                        applicationScope = this,
                        synchronizer = synchronizer,
                        store = reopenedStore,
                        priceResolver = HistoryDailyPriceResolver(restartedKraken, nowProvider = { now }),
                        nowProvider = { now },
                    ).runOnce(forceRetry = true)

                    val values = reopenedStore.queryDailyValues(
                        accountDigest,
                        HISTORY_ACCOUNT_DAILY_SCOPE,
                        from = existing.targetStartDay.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant(),
                        until = existing.targetEndDay.plusDays(2).atStartOfDay(ZoneOffset.UTC).toInstant(),
                        limit = HistoryValuationCoordinator.TARGET_DAYS,
                    ).items
                    values.size shouldBe HistoryValuationCoordinator.TARGET_DAYS
                    values.all {
                        it.status == HistoryDailyValueStatus.COMPLETE && it.totalUsd == BigDecimal("100")
                    } shouldBe
                        true
                    coVerify(exactly = 0) { restartedKraken.getOHLC(any(), any(), any()) }
                } finally {
                    reopenedDatabase.close()
                    Files.deleteIfExists(path)
                    Files.deleteIfExists(directory)
                }
            }
        }

        "publishes a zero cash mark only after verified full ledger coverage with no assets" {
            runTest {
                val now = Instant.parse("2026-10-21T12:00:00Z")
                val account = VerifiedHistoryAccount(accountDigest, null, null)
                val start = now.atZone(ZoneOffset.UTC).toLocalDate().minusDays(365)
                val end = now.atZone(ZoneOffset.UTC).toLocalDate().minusDays(1)
                val balances = mutableListOf<HistoryDailyBalance>()
                var publishedJob: HistoryValuationJob? = null
                var publishedValues = emptyList<HistoryDailyValue>()
                val synchronizer = mockk<HistorySynchronizer>()
                val store = mockk<HistoryStore>()
                val krakenService = mockk<KrakenService>()
                coEvery { synchronizer.verifiedAccount() } returns account
                coEvery { store.loadSyncJobs(accountDigest) } returns emptyList()
                coEvery { store.currentLedgerRevision(accountDigest) } returns 3L
                coEvery { store.loadValuationJob(accountDigest, HISTORY_ACCOUNT_DAILY_SCOPE) } returns null
                coEvery { store.upsertValuationJob(any()) } just runs
                coEvery {
                    store.queryDailyCheckpointInputPage(accountDigest, any(), any(), any())
                } returns HistoryPage(emptyList(), 0L, 500, 0, false)
                coEvery { store.queryLedgerCoverageWindows(accountDigest, any(), any()) } coAnswers {
                    listOf(HistoryLedgerCoverageWindow(0, arg<Long>(2) - 1))
                }
                coEvery {
                    store.queryDailyBalances(accountDigest, HISTORY_ACCOUNT_DAILY_SCOPE, any(), any())
                } coAnswers { balances.toList() }
                coEvery { store.replaceDailyBalanceDay(any()) } coAnswers {
                    val day = firstArg<List<HistoryDailyBalance>>()
                    balances.removeAll { it.dayLabel == day.first().dayLabel }
                    balances += day
                }
                coEvery { store.publishDailyValues(any(), any()) } coAnswers {
                    publishedJob = firstArg()
                    publishedValues = secondArg()
                }
                val coordinator = HistoryValuationCoordinator(
                    applicationScope = this,
                    synchronizer = synchronizer,
                    store = store,
                    priceResolver = HistoryDailyPriceResolver(krakenService, nowProvider = { now }),
                    nowProvider = { now },
                )

                coordinator.runOnce()

                publishedJob?.state shouldBe HistoryValuationJobState.COMPLETE
                publishedJob?.requiredPriceTasks shouldBe 0
                publishedValues.size shouldBe 365
                publishedValues.all { it.totalUsd == BigDecimal.ZERO } shouldBe true
                publishedValues.all { it.status == HistoryDailyValueStatus.COMPLETE } shouldBe true
                coVerify(exactly = 1) { store.queryDailyCheckpointInputPage(accountDigest, any(), any(), any()) }
                coVerify(exactly = 0) { krakenService.getOHLC(any(), any(), any()) }
            }
        }

        "records price retry time and publishes unavailable values after an explicit retry" {
            runTest {
                var now = Instant.parse("2026-10-21T12:00:00Z")
                val account = VerifiedHistoryAccount(accountDigest, null, null)
                val start = now.atZone(ZoneOffset.UTC).toLocalDate().minusDays(365)
                val end = now.atZone(ZoneOffset.UTC).toLocalDate().minusDays(1)
                val checkpoint = HistoryBalanceCheckpoint(
                    accountIdentityDigest = accountDigest,
                    ledgerId = "btc-opening",
                    observedAt = start.atTime(12, 0).toInstant(ZoneOffset.UTC),
                    rawAssetClass = "currency",
                    rawAssetKey = "XBT",
                    rawAmount = "1",
                    rawFee = "0",
                    rawBalance = "1",
                    quantity = BigDecimal.ONE,
                    status = HistoryBalanceStatus.VALID,
                )
                val balances = mutableListOf<HistoryDailyBalance>()
                val jobs = mutableListOf<HistoryValuationJob>()
                var publishedJob: HistoryValuationJob? = null
                var publishedValues = emptyList<HistoryDailyValue>()
                var currentJob: HistoryValuationJob? = null
                val synchronizer = mockk<HistorySynchronizer>()
                val store = mockk<HistoryStore>()
                val krakenService = mockk<KrakenService>(relaxed = true)
                coEvery { synchronizer.verifiedAccount() } returns account
                coEvery { store.loadSyncJobs(accountDigest) } returns emptyList()
                coEvery { store.currentLedgerRevision(accountDigest) } returns 4L
                coEvery { store.loadValuationJob(accountDigest, HISTORY_ACCOUNT_DAILY_SCOPE) } coAnswers {
                    currentJob
                }
                coEvery { store.upsertValuationJob(any()) } coAnswers {
                    firstArg<HistoryValuationJob>().also {
                        jobs += it
                        currentJob = it
                    }
                }
                coEvery {
                    store.queryDailyCheckpointInputPage(accountDigest, any(), any(), any())
                } coAnswers {
                    val offset = arg<Int>(3)
                    val items = if (offset == 0) listOf(checkpoint) else emptyList()
                    HistoryPage(items, 1L, 500, offset, offset + items.size < 1)
                }
                coEvery { store.queryLedgerCoverageWindows(accountDigest, any(), any()) } coAnswers {
                    listOf(HistoryLedgerCoverageWindow(0, arg<Long>(2) - 1))
                }
                coEvery {
                    store.queryDailyBalances(accountDigest, HISTORY_ACCOUNT_DAILY_SCOPE, any(), any())
                } coAnswers { balances.toList() }
                coEvery { store.replaceDailyBalanceDay(any()) } coAnswers {
                    val dayBalances = firstArg<List<HistoryDailyBalance>>()
                    balances.removeAll { it.dayLabel == dayBalances.first().dayLabel }
                    balances += dayBalances
                }
                coEvery { store.queryDailyPrices(accountDigest, any(), any(), any()) } returns emptyList()
                coEvery { store.saveDailyPrices(any()) } just runs
                coEvery { store.publishDailyValues(any(), any()) } coAnswers {
                    publishedJob = firstArg()
                    publishedValues = secondArg()
                }
                coEvery { krakenService.getOHLC("XBTUSD", 1_440, any()) } throws IllegalStateException("offline")
                val coordinator = HistoryValuationCoordinator(
                    applicationScope = this,
                    synchronizer = synchronizer,
                    store = store,
                    priceResolver = HistoryDailyPriceResolver(krakenService, nowProvider = { now }),
                    nowProvider = { now },
                    retryDelays = listOf(Duration.ofMinutes(1), Duration.ofMinutes(2)),
                )

                coordinator.runOnce()
                val waiting = currentJob
                waiting?.state shouldBe HistoryValuationJobState.WAITING
                waiting?.retryAttempt shouldBe 1
                waiting?.nextRetryAt shouldBe now.plus(Duration.ofMinutes(1))
                waiting?.lastError shouldBe "Historical daily prices could not be fetched."
                waiting?.balancesProcessed shouldBe 365

                now = now.plus(Duration.ofMinutes(2))
                coordinator.runOnce()

                currentJob?.state shouldBe HistoryValuationJobState.NEEDS_ATTENTION
                currentJob?.retryAttempt shouldBe 2
                currentJob?.nextRetryAt shouldBe null
                jobs.any {
                    it.state == HistoryValuationJobState.RUNNING &&
                        it.stage == HistoryValuationStage.PRICE_AND_VALUE
                } shouldBe
                    true
                publishedValues shouldBe emptyList()

                coEvery { krakenService.getOHLC("XBTUSD", 1_440, any()) } returns emptyList()
                coordinator.runOnce(forceRetry = true)

                publishedJob?.state shouldBe HistoryValuationJobState.NEEDS_ATTENTION
                publishedJob?.retryAttempt shouldBe 0
                publishedJob?.blockedValueDays shouldBe 365
                publishedJob?.completedPriceTasks shouldBe 0
                publishedValues.size shouldBe 365
                publishedValues.all { it.status == HistoryDailyValueStatus.NO_PRICE } shouldBe true
                publishedValues.all { it.totalUsd == null } shouldBe true
                coVerify(exactly = 3) { krakenService.getOHLC("XBTUSD", 1_440, any()) }
            }
        }

        "waits for recent ledger sync before snapshotting and publishing affected daily values" {
            runTest {
                val now = Instant.parse("2026-10-21T12:00:00Z")
                val account = VerifiedHistoryAccount(accountDigest, null, null)
                val targetStart = LocalDate.parse("2025-10-21")
                val targetEnd = LocalDate.parse("2026-10-20")
                val lateDepositDay = LocalDate.parse("2026-01-10")
                val recentJob = HistorySyncJob(
                    accountIdentityDigest = accountDigest,
                    feed = HistoryFeed.LEDGERS,
                    purpose = HistorySyncPurpose.RECENT,
                    state = HistorySyncJobState.RUNNING,
                    windowStartSec = null,
                    windowEndSec = null,
                    cursor = null,
                    completedThroughSec = null,
                    lastError = null,
                    nextRetryAt = null,
                    lastSuccessAt = null,
                    updatedAt = now,
                )
                var recentSyncRunning = true
                var ledgerRevision = 1L
                val checkpoints = mutableListOf(
                    checkpoint("opening", targetStart.atStartOfDay(ZoneOffset.UTC).toInstant(), "100", "100"),
                )
                val balances = mutableListOf<HistoryDailyBalance>()
                val prices = mutableListOf<HistoryDailyPrice>()
                var publishedValues = emptyList<HistoryDailyValue>()

                val store = mockk<HistoryStore>()
                val synchronizer = mockk<HistorySynchronizer>()
                val krakenService = mockk<KrakenService>(relaxed = true)
                coEvery { synchronizer.verifiedAccount() } returns account
                coEvery { store.loadSyncJobs(accountDigest) } coAnswers {
                    if (recentSyncRunning) listOf(recentJob) else emptyList()
                }
                coEvery { store.currentLedgerRevision(accountDigest) } coAnswers { ledgerRevision }
                coEvery { store.loadValuationJob(accountDigest, HISTORY_ACCOUNT_DAILY_SCOPE) } returns null
                coEvery { store.upsertValuationJob(any()) } just runs
                coEvery {
                    store.queryDailyCheckpointInputPage(accountDigest, any(), any(), any())
                } coAnswers {
                    val throughSec = secondArg<Long>()
                    val limit = thirdArg<Int>()
                    val offset = arg<Int>(3)
                    val eligible = checkpoints.filter {
                        it.observedAt?.epochSecond?.let { time -> time <= throughSec }
                            ?: true
                    }
                    val items = eligible.drop(offset).take(limit)
                    HistoryPage(
                        items = items,
                        total = eligible.size.toLong(),
                        limit = limit,
                        offset = offset,
                        hasMore = offset + items.size < eligible.size,
                    )
                }
                coEvery { store.queryLedgerCoverageWindows(accountDigest, any(), any()) } coAnswers {
                    listOf(HistoryLedgerCoverageWindow(0, arg<Long>(2) - 1))
                }
                coEvery {
                    store.queryDailyBalances(accountDigest, HISTORY_ACCOUNT_DAILY_SCOPE, any(), any())
                } coAnswers { balances.toList() }
                coEvery { store.replaceDailyBalanceDay(any()) } coAnswers {
                    val day = firstArg<List<HistoryDailyBalance>>()
                    balances.removeAll { it.dayLabel == day.first().dayLabel }
                    balances += day
                }
                coEvery { store.queryDailyPrices(accountDigest, any(), any(), any()) } coAnswers {
                    val rawAssetKey = secondArg<String>()
                    val fromSec = thirdArg<Long>()
                    val throughSec = arg<Long>(3)
                    prices.filter {
                        it.rawAssetKey == rawAssetKey && it.candleOpen.epochSecond in fromSec..throughSec
                    }
                }
                coEvery { store.saveDailyPrices(any()) } coAnswers { prices += firstArg<List<HistoryDailyPrice>>() }
                coEvery { store.publishDailyValues(any(), any()) } coAnswers {
                    publishedValues = secondArg()
                }

                val coordinator = HistoryValuationCoordinator(
                    applicationScope = this,
                    synchronizer = synchronizer,
                    store = store,
                    priceResolver = HistoryDailyPriceResolver(krakenService, nowProvider = { now }),
                    nowProvider = { now },
                    pollInterval = Duration.ofDays(1),
                )
                val work = async { coordinator.runOnce() }

                runCurrent()
                publishedValues.isEmpty() shouldBe true

                // The newly committed page and revision become visible together when the sync settles.
                checkpoints += checkpoint(
                    "late-usd-deposit",
                    lateDepositDay.atTime(12, 0).toInstant(ZoneOffset.UTC),
                    "1",
                    "101",
                )
                ledgerRevision = 2
                recentSyncRunning = false
                advanceUntilIdle()
                work.await()

                publishedValues.size shouldBe HistoryValuationCoordinator.TARGET_DAYS
                publishedValues.all { it.status == HistoryDailyValueStatus.COMPLETE } shouldBe true
                publishedValues.all { it.sourceRevision == 2L } shouldBe true
                publishedValues.single { it.dayLabel == lateDepositDay.minusDays(1) }.totalUsd!!
                    .shouldBeEqualComparingTo(BigDecimal("100"))
                publishedValues.single { it.dayLabel == lateDepositDay }.totalUsd!!
                    .shouldBeEqualComparingTo(BigDecimal("101"))
                publishedValues.single { it.dayLabel == targetEnd }.totalUsd!!
                    .shouldBeEqualComparingTo(BigDecimal("101"))
            }
        }
    }

    private fun checkpoint(id: String, time: Instant, amount: String, balance: String) = HistoryBalanceCheckpoint(
        accountIdentityDigest = accountDigest,
        ledgerId = id,
        observedAt = time,
        rawAssetClass = "currency",
        rawAssetKey = "ZUSD",
        rawAmount = amount,
        rawFee = "0",
        rawBalance = balance,
        quantity = balance.toBigDecimal(),
        status = HistoryBalanceStatus.VALID,
    )

    private fun dailyBalance(day: LocalDate, sourceRevision: Long, generationId: String) = HistoryDailyBalance(
        dayEnd = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant(),
        dayLabel = day,
        accountIdentityDigest = accountDigest,
        sourceAssetScope = HISTORY_ACCOUNT_DAILY_SCOPE,
        rawAssetClass = "currency",
        rawAssetKey = "USD",
        quantity = BigDecimal.ZERO,
        sourceLedgerId = null,
        sourceObservedAt = null,
        sourceRevision = sourceRevision,
        generationId = generationId,
        status = HistoryDailyBalanceStatus.VALID,
    )

    private fun valuationJob(now: Instant, state: HistoryValuationJobState): HistoryValuationJob {
        val targetEnd = now.atZone(ZoneOffset.UTC).toLocalDate().minusDays(1)
        val targetStart = targetEnd.minusDays(HistoryValuationCoordinator.TARGET_DAYS - 1L)
        return HistoryValuationJob(
            accountIdentityDigest = accountDigest,
            sourceAssetScope = HISTORY_ACCOUNT_DAILY_SCOPE,
            generationId = "existing-generation",
            state = state,
            stage = HistoryValuationStage.PRICE_AND_VALUE,
            targetStartDay = targetStart,
            targetEndDay = targetEnd,
            nextDay = null,
            targetDays = HistoryValuationCoordinator.TARGET_DAYS,
            balancesProcessed = HistoryValuationCoordinator.TARGET_DAYS,
            balancesValid = HistoryValuationCoordinator.TARGET_DAYS,
            balancesBlocked = 0,
            requiredPriceTasks = 0,
            completedPriceTasks = 0,
            completeValueDays = 0,
            blockedValueDays = 0,
            sourceRevision = 9,
            calculationPolicyVersion = HISTORY_DAILY_VALUE_POLICY_VERSION,
            lastProgressAt = now,
            nextRetryAt = if (state == HistoryValuationJobState.WAITING) now.plusSeconds(60) else null,
            lastSuccessAt = null,
            lastError = null,
            updatedAt = now,
        )
    }
}
