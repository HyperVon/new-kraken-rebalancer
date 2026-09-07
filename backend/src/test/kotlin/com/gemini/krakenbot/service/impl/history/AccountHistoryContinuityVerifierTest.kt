package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.DatabaseConfig
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.model.TradeSource
import com.gemini.krakenbot.repository.TradeRepository
import com.gemini.krakenbot.repository.impl.SqliteLedgerRepositoryImpl
import com.gemini.krakenbot.repository.impl.SqliteTradeRepositoryImpl
import com.gemini.krakenbot.service.FakeKrakenService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import java.time.Instant

class AccountHistoryContinuityVerifierTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    private val database = DatabaseConfig.init(TestFixtures.MEMORY_)
    private val tradeRepository = SqliteTradeRepositoryImpl(database)
    private val ledgerRepository = SqliteLedgerRepositoryImpl(database)
    private val krakenService = FakeKrakenService()
    private val now = Instant.parse("2026-05-01T00:00:00Z")
    private val verifier = AccountHistoryContinuityVerifier(
        krakenService,
        tradeRepository,
        ledgerRepository,
        nowProvider = { now },
    )

    init {
        "verifies a middle-history trade marker inside its timestamp window" {
            runTest {
                // 1,000 fills, 60s apart; the retained marker sits at global offset ~400,
                // invisible to both the newest page and the deepest global page.
                val base = Instant.parse("2026-04-01T00:00:00Z")
                val history = (0 until 1000).map { index ->
                    exchangeTrade(id = "fill-$index", timestamp = base.plusSeconds(index * 60L))
                }
                val marker = history[400]
                tradeRepository.saveTrade(storedTrade(id = marker.tradeId, timestamp = marker.timestamp))
                var sawWindowedStart = false
                krakenService.tradeHistorySupplier = { startSec, offset ->
                    if (startSec != null) sawWindowedStart = true
                    history
                        .filter { startSec == null || it.timestamp.epochSecond >= startSec }
                        .drop((offset ?: 0).coerceAtLeast(0))
                        .take(50)
                }
                krakenService.tradeHistoryTotalCountOverride = 1000

                verifier.verifyContinuity() shouldBe AccountHistoryContinuityStatus.VERIFIED
                sawWindowedStart shouldBe true
            }
        }

        "verifies a middle-history ledger marker inside its timestamp window" {
            runTest {
                val base = Instant.parse("2026-04-01T00:00:00Z")
                val entries = (0 until 300).map { index ->
                    exchangeLedger(id = "ledger-$index", time = base.plusSeconds(index * 60L))
                }
                val marker = entries[150]
                ledgerRepository.saveLedgers(
                    listOf(
                        storedLedger(id = " ", time = base),
                        storedLedger(id = marker.ledgerId, time = marker.time),
                    ),
                )
                krakenService.seedLedgerEntries(entries)

                verifier.verifyContinuity() shouldBe AccountHistoryContinuityStatus.VERIFIED
            }
        }

        "paginates a dense trade window to the third page with unknown total" {
            runTest {
                val markerTime = Instant.parse("2026-04-15T12:00:00Z")
                val window = (0 until 100).map { index ->
                    exchangeTrade(id = "dense-$index", timestamp = markerTime.minusSeconds(200L - index * 2L))
                } + exchangeTrade(id = "dense-marker", timestamp = markerTime)
                tradeRepository.saveTrade(storedTrade(id = "dense-marker", timestamp = markerTime))
                krakenService.tradeHistorySupplier = { startSec, offset ->
                    window
                        .filter { startSec == null || it.timestamp.epochSecond >= startSec }
                        .drop((offset ?: 0).coerceAtLeast(0))
                        .take(50)
                }
                krakenService.tradeHistoryTotalCountOverride = 0

                verifier.verifyContinuity() shouldBe AccountHistoryContinuityStatus.VERIFIED
                krakenService.getTradeHistoryCallCount shouldBe 3
            }
        }

        "paginates a dense ledger window across pages with authoritative total" {
            runTest {
                val markerTime = Instant.parse("2026-04-15T12:00:00Z")
                val entries = (0 until 100).map { index ->
                    exchangeLedger(id = "dense-$index", time = markerTime.plusSeconds((index + 1) * 2L))
                } + exchangeLedger(id = "dense-ledger-marker", time = markerTime)
                ledgerRepository.saveLedgers(
                    listOf(storedLedger(id = "dense-ledger-marker", time = markerTime)),
                )
                krakenService.seedLedgerEntries(entries)

                verifier.verifyContinuity() shouldBe AccountHistoryContinuityStatus.VERIFIED
                krakenService.getLedgersCallCount shouldBe 3
            }
        }

        "reports incomplete when a dense window stays full at the page cap" {
            runTest {
                val markerTime = Instant.parse("2026-04-15T12:00:00Z")
                val window = (0 until 250).map { index ->
                    exchangeTrade(id = "cap-$index", timestamp = markerTime)
                }
                tradeRepository.saveTrade(storedTrade(id = "absent-marker", timestamp = markerTime))
                krakenService.tradeHistorySupplier = { _, offset ->
                    window.drop((offset ?: 0).coerceAtLeast(0)).take(50)
                }
                krakenService.tradeHistoryTotalCountOverride = 0

                verifier.verifyContinuity() shouldBe AccountHistoryContinuityStatus.INCOMPLETE
                krakenService.getTradeHistoryCallCount shouldBe 4
            }
        }

        "reports incomplete when a dense ledger window stays full at the page cap" {
            runTest {
                val markerTime = Instant.parse("2026-04-15T12:00:00Z")
                ledgerRepository.saveLedgers(listOf(storedLedger(id = "absent-ledger", time = markerTime)))
                krakenService.ledgerSupplier = { _, _, _, _ ->
                    (0 until 50).map { exchangeLedger(id = "cap-ledger-$it", time = markerTime) }
                }
                krakenService.ledgerTotalCountOverride = 0

                verifier.verifyContinuity() shouldBe AccountHistoryContinuityStatus.INCOMPLETE
                krakenService.getLedgersCallCount shouldBe 4
            }
        }

        "searches only the newest and oldest markers within the marker budget" {
            runTest {
                val base = Instant.parse("2026-04-01T00:00:00Z")
                tradeRepository.saveTrade(storedTrade(id = "oldest-fill", timestamp = base))
                tradeRepository.saveTrade(storedTrade(id = "middle-fill", timestamp = base.plusSeconds(1_000)))
                tradeRepository.saveTrade(storedTrade(id = "newest-fill", timestamp = base.plusSeconds(2_000)))
                // Only the skipped middle marker exists on the exchange: the bounded
                // proof checks the newest and oldest windows and reports absence.
                krakenService.tradeHistorySupplier = { _, _ ->
                    listOf(exchangeTrade(id = "middle-fill", timestamp = base.plusSeconds(1_000)))
                }

                verifier.verifyContinuity() shouldBe AccountHistoryContinuityStatus.NO_OVERLAP
                krakenService.getTradeHistoryCallCount shouldBe 2
            }
        }

        "completes a full authoritative page without a match" {
            runTest {
                val markerTime = Instant.parse("2026-04-15T12:00:00Z")
                tradeRepository.saveTrade(storedTrade(id = "absent-fill", timestamp = markerTime))
                krakenService.tradeHistorySupplier = { _, _ ->
                    (0 until 50).map { exchangeTrade(id = "other-$it", timestamp = markerTime) }
                }
                krakenService.tradeHistoryTotalCountOverride = 50

                verifier.verifyContinuity() shouldBe AccountHistoryContinuityStatus.NO_OVERLAP
                krakenService.getTradeHistoryCallCount shouldBe 1
            }
        }

        "reports no overlap for same economics with a different exchange id" {
            runTest {
                val markerTime = Instant.parse("2026-04-15T12:00:00Z")
                tradeRepository.saveTrade(storedTrade(id = "local-FOO", timestamp = markerTime))
                krakenService.tradeHistorySupplier = { _, _ ->
                    // Same timestamp, pair, amount, and price — but different fill identities,
                    // including a row without any trade id at all.
                    listOf(
                        exchangeTrade(id = "remote-BAR", timestamp = markerTime),
                        exchangeTrade(id = null, timestamp = markerTime),
                    )
                }

                verifier.verifyContinuity() shouldBe AccountHistoryContinuityStatus.NO_OVERLAP
            }
        }

        "does not cross-match local tradeId against remote orderTxid" {
            runTest {
                val markerTime = Instant.parse("2026-04-15T12:00:00Z")
                tradeRepository.saveTrade(storedTrade(id = "ABC", timestamp = markerTime))
                krakenService.tradeHistorySupplier = { _, _ ->
                    listOf(exchangeTrade(id = "XYZ", orderId = "ABC", timestamp = markerTime))
                }

                verifier.verifyContinuity() shouldBe AccountHistoryContinuityStatus.NO_OVERLAP
            }
        }

        "orderTxid alone never verifies continuity" {
            runTest {
                val markerTime = Instant.parse("2026-04-15T12:00:00Z")
                tradeRepository.saveTrade(storedTrade(id = "  ", orderId = "order-9", timestamp = markerTime))
                krakenService.tradeHistorySupplier = { _, _ ->
                    listOf(exchangeTrade(id = null, orderId = "order-9", timestamp = markerTime))
                }

                verifier.verifyContinuity() shouldBe AccountHistoryContinuityStatus.NO_OVERLAP
            }
        }

        "dry-run, failed, and local-estimate rows cannot prove continuity" {
            runTest {
                val markerTime = Instant.parse("2026-04-15T12:00:00Z")
                tradeRepository.saveTrade(storedTrade(id = "shared-id", timestamp = markerTime, dryRun = true))
                tradeRepository.saveTrade(storedTrade(id = "shared-id", timestamp = markerTime, success = false))
                tradeRepository.saveTrade(
                    storedTrade(id = "shared-id", timestamp = markerTime, source = TradeSource.LOCAL_ESTIMATE),
                )
                krakenService.tradeHistorySupplier = { _, _ ->
                    listOf(exchangeTrade(id = "shared-id", timestamp = markerTime))
                }

                verifier.verifyContinuity() shouldBe AccountHistoryContinuityStatus.NO_OVERLAP
                krakenService.getTradeHistoryCallCount shouldBe 0
                krakenService.getLedgersCallCount shouldBe 0
            }
        }

        "legacy rows with unknown provenance still qualify on exact identity" {
            runTest {
                val markerTime = Instant.parse("2026-04-15T12:00:00Z")
                tradeRepository.saveTrade(
                    storedTrade(id = "legacy-fill", timestamp = markerTime, source = TradeSource.LEGACY_UNKNOWN),
                )
                krakenService.tradeHistorySupplier = { _, _ ->
                    listOf(exchangeTrade(id = "legacy-fill", timestamp = markerTime))
                }

                verifier.verifyContinuity() shouldBe AccountHistoryContinuityStatus.VERIFIED
            }
        }

        "duplicate exact identities count once" {
            runTest {
                val markerTime = Instant.parse("2026-04-15T12:00:00Z")
                tradeRepository.saveTrade(storedTrade(id = "dup-fill", timestamp = markerTime))
                tradeRepository.saveTrade(storedTrade(id = "dup-fill", timestamp = markerTime.plusSeconds(60)))
                krakenService.tradeHistorySupplier = { _, _ ->
                    listOf(
                        exchangeTrade(id = "dup-fill", timestamp = markerTime),
                        exchangeTrade(id = "dup-fill", timestamp = markerTime),
                    )
                }

                verifier.verifyContinuity() shouldBe AccountHistoryContinuityStatus.VERIFIED
            }
        }

        "reports no overlap without network calls when nothing exchange-attributable is stored" {
            runTest {
                tradeRepository.saveTrade(
                    TestFixtures.tradeRecord(
                        timestamp = Instant.parse("2026-01-01T00:00:00Z"),
                        pair = Asset.BTC_USD_PAIR,
                        side = "buy",
                        symbol = Asset.BTC,
                        volume = BigDecimal("0.01"),
                        usdAmount = BigDecimal("100.00"),
                        price = BigDecimal("10000.00"),
                    ),
                )
                krakenService.tradeHistorySupplier = { _, _ -> listOf(exchangeTrade(id = "foreign-fill")) }

                verifier.verifyContinuity() shouldBe AccountHistoryContinuityStatus.NO_OVERLAP
                krakenService.getTradeHistoryCallCount shouldBe 0
                krakenService.getLedgersCallCount shouldBe 0
            }
        }

        "reports unavailable when the exchange cannot be reached" {
            runTest {
                tradeRepository.saveTrade(storedTrade(id = "local-fill"))
                krakenService.tradeHistorySupplier = { _, _ -> error("network") }

                verifier.verifyContinuity() shouldBe AccountHistoryContinuityStatus.UNAVAILABLE
            }
        }

        "propagates cancellation instead of reporting unavailable" {
            runTest {
                val tradeRepository = mockk<TradeRepository>()
                coEvery { tradeRepository.getTradesInRange(any(), any()) } throws CancellationException("cancelled")
                val cancelling = AccountHistoryContinuityVerifier(
                    krakenService,
                    tradeRepository,
                    ledgerRepository,
                    nowProvider = { now },
                )

                shouldThrow<CancellationException> { cancelling.verifyContinuity() }
            }
        }

        "legacy consistency verifies when every sampled marker matches" {
            runTest {
                val base = Instant.parse("2026-01-01T00:00:00Z")
                val trades = (0 until 3).map {
                    storedTrade(id = "legacy-trade-$it", timestamp = base.plusSeconds(it * 1_000L))
                }
                trades.forEach { tradeRepository.saveTrade(it) }
                ledgerRepository.saveLedgers(
                    listOf(
                        storedLedger(id = "legacy-ledger-0", time = base),
                        storedLedger(id = "legacy-ledger-1", time = base.plusSeconds(2_000L)),
                    ),
                )
                krakenService.tradeHistorySupplier = { _, _ ->
                    trades.map { exchangeTrade(id = it.tradeId, timestamp = it.timestamp) }
                }
                krakenService.ledgerSupplier = { _, _, _, _ ->
                    listOf(
                        exchangeLedger(id = "legacy-ledger-0", time = base),
                        exchangeLedger(id = "legacy-ledger-1", time = base.plusSeconds(2_000L)),
                    )
                }

                verifier.verifyLegacyConsistency() shouldBe AccountHistoryContinuityStatus.VERIFIED
            }
        }

        "legacy consistency reports conflict when the newest marker matches but the oldest is absent" {
            runTest {
                val base = Instant.parse("2026-01-01T00:00:00Z")
                tradeRepository.saveTrade(storedTrade(id = "account-A-old", timestamp = base))
                tradeRepository.saveTrade(storedTrade(id = "account-B-new", timestamp = base.plusSeconds(2_000_000L)))
                krakenService.tradeHistorySupplier = { _, _ ->
                    listOf(exchangeTrade(id = "account-B-new", timestamp = base.plusSeconds(2_000_000L)))
                }

                verifier.verifyLegacyConsistency() shouldBe AccountHistoryContinuityStatus.CONFLICT
            }
        }

        "legacy consistency reports conflict when the oldest matches but the newest is absent" {
            runTest {
                val base = Instant.parse("2026-01-01T00:00:00Z")
                tradeRepository.saveTrade(storedTrade(id = "account-B-old", timestamp = base))
                tradeRepository.saveTrade(storedTrade(id = "account-A-new", timestamp = base.plusSeconds(2_000_000L)))
                krakenService.tradeHistorySupplier = { _, _ ->
                    listOf(exchangeTrade(id = "account-B-old", timestamp = base))
                }

                verifier.verifyLegacyConsistency() shouldBe AccountHistoryContinuityStatus.CONFLICT
            }
        }

        "legacy consistency reports conflict when trades match but a sampled ledger is absent" {
            runTest {
                val base = Instant.parse("2026-01-01T00:00:00Z")
                tradeRepository.saveTrade(storedTrade(id = "shared-trade-0", timestamp = base))
                tradeRepository.saveTrade(storedTrade(id = "shared-trade-1", timestamp = base.plusSeconds(1_000L)))
                ledgerRepository.saveLedgers(
                    listOf(
                        storedLedger(id = "foreign-ledger-0", time = base),
                        storedLedger(id = "foreign-ledger-1", time = base.plusSeconds(1_000L)),
                    ),
                )
                krakenService.tradeHistorySupplier = { _, _ ->
                    listOf(
                        exchangeTrade(id = "shared-trade-0", timestamp = base),
                        exchangeTrade(id = "shared-trade-1", timestamp = base.plusSeconds(1_000L)),
                    )
                }
                krakenService.ledgerSupplier = { _, _, _, _ -> emptyList() }

                verifier.verifyLegacyConsistency() shouldBe AccountHistoryContinuityStatus.CONFLICT
            }
        }

        "legacy consistency reports conflict when ledgers match but a sampled trade is absent" {
            runTest {
                val base = Instant.parse("2026-01-01T00:00:00Z")
                tradeRepository.saveTrade(storedTrade(id = "foreign-trade-0", timestamp = base))
                tradeRepository.saveTrade(storedTrade(id = "foreign-trade-1", timestamp = base.plusSeconds(1_000L)))
                ledgerRepository.saveLedgers(
                    listOf(
                        storedLedger(id = "shared-ledger-0", time = base),
                        storedLedger(id = "shared-ledger-1", time = base.plusSeconds(1_000L)),
                    ),
                )
                krakenService.tradeHistorySupplier = { _, _ -> emptyList() }
                krakenService.ledgerSupplier = { _, _, _, _ ->
                    listOf(
                        exchangeLedger(id = "shared-ledger-0", time = base),
                        exchangeLedger(id = "shared-ledger-1", time = base.plusSeconds(1_000L)),
                    )
                }

                verifier.verifyLegacyConsistency() shouldBe AccountHistoryContinuityStatus.CONFLICT
            }
        }

        "legacy consistency reports no overlap when sampled markers are absent without any match" {
            runTest {
                val base = Instant.parse("2026-01-01T00:00:00Z")
                tradeRepository.saveTrade(storedTrade(id = "local-old", timestamp = base))
                tradeRepository.saveTrade(storedTrade(id = "local-new", timestamp = base.plusSeconds(1_000L)))
                krakenService.tradeHistorySupplier = { _, _ ->
                    listOf(exchangeTrade(id = "foreign-fill", timestamp = base))
                }

                verifier.verifyLegacyConsistency() shouldBe AccountHistoryContinuityStatus.NO_OVERLAP
            }
        }

        "legacy consistency with a single retained identity is unproven" {
            runTest {
                val base = Instant.parse("2026-01-01T00:00:00Z")
                tradeRepository.saveTrade(storedTrade(id = "only-fill", timestamp = base))
                krakenService.tradeHistorySupplier = { _, _ ->
                    listOf(exchangeTrade(id = "only-fill", timestamp = base))
                }

                verifier.verifyLegacyConsistency() shouldBe AccountHistoryContinuityStatus.NO_OVERLAP
            }
        }

        "legacy consistency samples across the retained lifetime" {
            runTest {
                val base = Instant.parse("2026-01-01T00:00:00Z")
                (0 until 6).forEach { index ->
                    tradeRepository.saveTrade(
                        storedTrade(id = "fill-$index", timestamp = base.plusSeconds(index * 10_000L)),
                    )
                }
                krakenService.tradeHistorySupplier = { _, _ -> emptyList() }

                verifier.verifyLegacyConsistency() shouldBe AccountHistoryContinuityStatus.NO_OVERLAP
                // Quantiles 0/25/50/75/100% of six markers: five bounded windows searched.
                krakenService.getTradeHistoryCallCount shouldBe 5
            }
        }

        "legacy consistency reports incomplete when a sampled window cannot finish" {
            runTest {
                val base = Instant.parse("2026-01-01T00:00:00Z")
                tradeRepository.saveTrade(storedTrade(id = "dense-old", timestamp = base))
                tradeRepository.saveTrade(storedTrade(id = "dense-new", timestamp = base.plusSeconds(100_000L)))
                krakenService.tradeHistorySupplier = { _, _ ->
                    (0 until 50).map { exchangeTrade(id = "cap-$it", timestamp = base) }
                }
                krakenService.tradeHistoryTotalCountOverride = 0

                verifier.verifyLegacyConsistency() shouldBe AccountHistoryContinuityStatus.INCOMPLETE
            }
        }

        "legacy consistency reports unavailable when a sampled window errors" {
            runTest {
                val base = Instant.parse("2026-01-01T00:00:00Z")
                tradeRepository.saveTrade(storedTrade(id = "legacy-old", timestamp = base))
                tradeRepository.saveTrade(storedTrade(id = "legacy-new", timestamp = base.plusSeconds(1_000L)))
                krakenService.tradeHistorySupplier = { _, _ -> error("network") }

                verifier.verifyLegacyConsistency() shouldBe AccountHistoryContinuityStatus.UNAVAILABLE
            }
        }

        "legacy consistency propagates cancellation instead of reporting unavailable" {
            runTest {
                val tradeRepository = mockk<TradeRepository>()
                coEvery { tradeRepository.getTradesInRange(any(), any()) } throws CancellationException("cancelled")
                val cancelling = AccountHistoryContinuityVerifier(
                    krakenService,
                    tradeRepository,
                    ledgerRepository,
                    nowProvider = { now },
                )

                shouldThrow<CancellationException> { cancelling.verifyLegacyConsistency() }
            }
        }

        "ledger windows continue past filtered short pages using raw occupancy" {
            runTest {
                val markerTime = Instant.parse("2026-04-15T12:00:00Z")
                ledgerRepository.saveLedgers(listOf(storedLedger(id = "deep-ledger", time = markerTime)))
                // Kraken returns a full 50-row raw page both times, but parsing
                // drops rows: the first parsed page alone must not complete the window.
                krakenService.ledgerRawPageSizeOverride = 50
                krakenService.ledgerTotalCountOverride = 0
                krakenService.ledgerSupplier = { _, offset, _, _ ->
                    if ((offset ?: 0) == 0) {
                        (0 until 40).map { exchangeLedger(id = "parsed-$it", time = markerTime) }
                    } else {
                        listOf(exchangeLedger(id = "deep-ledger", time = markerTime))
                    }
                }

                verifier.verifyContinuity() shouldBe AccountHistoryContinuityStatus.VERIFIED
                krakenService.getLedgersCallCount shouldBe 2
            }
        }
    }

    private fun storedTrade(
        id: String?,
        timestamp: Instant = Instant.parse("2026-01-01T00:00:00Z"),
        orderId: String? = id?.let { "order-$it" },
        success: Boolean = true,
        dryRun: Boolean = false,
        source: TradeSource = TradeSource.API_FILL,
    ): TradeRecord = TestFixtures.tradeRecord(
        timestamp = timestamp,
        pair = Asset.BTC_USD_PAIR,
        side = "buy",
        symbol = Asset.BTC,
        volume = BigDecimal("0.01"),
        usdAmount = BigDecimal("100.00"),
        price = BigDecimal("10000.00"),
        success = success,
        dryRun = dryRun,
        source = source,
        tradeId = id,
        orderTxid = orderId,
    )

    private fun exchangeTrade(
        id: String?,
        timestamp: Instant = Instant.parse("2026-01-01T00:00:00Z"),
        orderId: String? = id?.let { "order-$it" },
    ): TradeRecord = TestFixtures.tradeRecord(
        timestamp = timestamp,
        pair = Asset.BTC_USD_PAIR,
        side = "buy",
        symbol = Asset.BTC,
        volume = BigDecimal("0.01"),
        usdAmount = BigDecimal("100.00"),
        price = BigDecimal("10000.00"),
        source = TradeSource.API_FILL,
        tradeId = id,
        orderTxid = orderId,
    )

    private fun storedLedger(id: String, time: Instant = Instant.parse("2026-01-01T00:00:00Z")): LedgerEvent =
        LedgerEvent(
            ledgerId = id,
            time = time,
            type = "staking",
            asset = Asset.BTC,
            amount = BigDecimal.ZERO,
        )

    private fun exchangeLedger(id: String, time: Instant = Instant.parse("2026-01-01T00:00:00Z")): LedgerEvent =
        storedLedger(id, time)
}
