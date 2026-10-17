package com.gemini.krakenbot.service.history

import com.gemini.krakenbot.model.ExchangeHistoryPage
import com.gemini.krakenbot.model.ExchangeLedgerHistoryEntry
import com.gemini.krakenbot.model.ExchangeTradeHistoryEntry
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.math.BigDecimal
import java.nio.file.Files
import java.time.Instant

class HistoryStoreConcurrencyTest : StringSpec() {
    init {
        "concurrent trade ledger and cycle writers commit without lock upgrade failures" {
            val directory = Files.createTempDirectory("history-concurrent-writers-")
            try {
                HistoryDatabase(directory.resolve("history.db")).use { database ->
                    database.initializeFreshSchema()
                    HistoryDatabase(directory.resolve("history.db")).use { otherDatabase ->
                        val store = SqliteHistoryStore(database)
                        val otherStore = SqliteHistoryStore(otherDatabase)
                        val failures = mutableListOf<String>()
                        repeat(30) { round ->
                            val start = 1_700_000_000L + round
                            val go = CompletableDeferred<Unit>()
                            val outcomes = coroutineScope {
                                val writes = HistoryFeed.entries.map { feed ->
                                    async(Dispatchers.IO) {
                                        go.await()
                                        runCatching {
                                            val activeStore = if (feed == HistoryFeed.TRADES) store else otherStore
                                            val status = activeStore.beginSyncWindow(
                                                HistorySyncWindow(
                                                    DIGEST,
                                                    feed,
                                                    "${feed.name}-$round",
                                                    start,
                                                    start + 1,
                                                ),
                                            )
                                            val request = HistoryPageRequest(
                                                DIGEST,
                                                feed,
                                                status.scanId,
                                                start,
                                                start + 1,
                                                null,
                                                Instant.now(),
                                            )
                                            val result = when (feed) {
                                                HistoryFeed.TRADES -> activeStore.commitTradePage(
                                                    request,
                                                    ExchangeHistoryPage<ExchangeTradeHistoryEntry>(
                                                        emptyList(),
                                                        start,
                                                        start + 1,
                                                        null,
                                                        null,
                                                        0,
                                                        0,
                                                        true,
                                                    ),
                                                )

                                                HistoryFeed.LEDGERS -> activeStore.commitLedgerPage(
                                                    request,
                                                    ExchangeHistoryPage<ExchangeLedgerHistoryEntry>(
                                                        emptyList(),
                                                        start,
                                                        start + 1,
                                                        null,
                                                        null,
                                                        0,
                                                        0,
                                                        true,
                                                    ),
                                                )
                                            }
                                            result.state shouldBe HistorySyncState.COMPLETE
                                        }
                                    }
                                } + async(Dispatchers.IO) {
                                    go.await()
                                    runCatching {
                                        store.appendObservation(
                                            observation(round, Instant.ofEpochSecond(start)),
                                        ) shouldBe
                                            true
                                    }
                                }
                                go.complete(Unit)
                                writes.awaitAll()
                            }
                            failures += outcomes.mapNotNull { it.exceptionOrNull()?.toString() }
                        }
                        failures shouldBe emptyList()
                        store.syncStatus(DIGEST).map { it.state } shouldBe List(2) { HistorySyncState.COMPLETE }
                        store.queryObservations(HistoryRangeQuery(sourceScope = SCOPE)).total shouldBe 30L
                    }
                }
            } finally {
                directory.toFile().deleteRecursively()
            }
        }
    }

    private fun observation(round: Int, time: Instant) = HistoryObservation(
        observationId = "observation-$round",
        cycleId = "cycle-$round",
        sourceScope = SCOPE,
        observedAt = time,
        balanceRequestStartedAt = time,
        balanceResponseEndedAt = time,
        priceRequestStartedAt = time,
        priceResponseEndedAt = time,
        status = HistoryObservationStatus.COMPLETE,
        totalUsd = BigDecimal.TEN,
        incompleteReasons = emptyList(),
        assets = emptyList(),
    )

    private companion object {
        const val DIGEST = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        val SCOPE = HistorySourceScope(
            mode = HistorySourceMode.ACCOUNT,
            managedScopeFingerprint = DIGEST,
            managedScopeSymbols = listOf("USD"),
            accountIdentityDigest = DIGEST,
        )
    }
}
