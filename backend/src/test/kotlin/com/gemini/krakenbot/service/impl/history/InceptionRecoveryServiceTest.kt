package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.DatabaseConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.FundingEvidence
import com.gemini.krakenbot.model.FundingProvenanceResolver
import com.gemini.krakenbot.model.KrakenApiConstants
import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.model.OrderSide
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.RebalancerOrderIdentities
import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.model.TradeSource
import com.gemini.krakenbot.repository.OrderIntentRepository
import com.gemini.krakenbot.repository.impl.SqliteLedgerRepositoryImpl
import com.gemini.krakenbot.repository.impl.SqliteTradeRepositoryImpl
import com.gemini.krakenbot.repository.table.HistorySyncMetadataTable
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.FakeKrakenService
import com.gemini.krakenbot.service.InceptionRecoveryStatus
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import java.time.Instant

class InceptionRecoveryServiceTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    private val database = DatabaseConfig.init(TestFixtures.MEMORY_)
    private val repository = SqliteTradeRepositoryImpl(database)
    private val ledgerRepository = SqliteLedgerRepositoryImpl(database)
    private val krakenService = FakeKrakenService()
    private val configService = mockk<ConfigService>(relaxed = true)
    private val reconstructionService = mockk<TradeHistoryReconstructionService>(relaxed = true)
    private var now = Instant.parse("2026-05-01T00:00:00Z")
    private var config = appConfig(listOf(Allocation(Asset.BTC, 50.0), Allocation(Asset.USD, 50.0)))

    private val tradeHistorySyncService = TradeHistorySyncService(
        repository = repository,
        krakenService = krakenService,
        configService = configService,
        reconstructionService = reconstructionService,
        nowProvider = { now },
    )

    init {
        every { configService.getConfig() } answers { config }

        "bounded recovery remains incomplete and resumes with an overlapping page" {
            runTest {
                val history = (0 until 250).map { index ->
                    apiTrade(
                        id = "history-$index",
                        timestamp = Instant.parse("2026-04-01T00:00:00Z").minusSeconds(index.toLong()),
                    )
                }
                krakenService.tradeHistoryTotalCountOverride = history.size
                krakenService.tradeHistorySupplier = { _, offset ->
                    history.drop(offset ?: 0).take(50)
                }

                var status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.IN_PROGRESS
                status.tradeOffset shouldBe "200"
                status.ledgerOffset shouldBe ""
                repository.getSyncMetadata(SyncMetadataKeys.SYNC_OFFSET) shouldBe null
                repository.getTradesInRange(Instant.EPOCH, now.plusSeconds(1)).size shouldBe 200
                repository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_EPOCH_MS) shouldBe ""

                now = now.plusSeconds(InceptionRecoveryService.RETRY_INTERVAL_SECONDS + 1)
                val shiftedHistory = listOf(
                    apiTrade(
                        id = "history-new",
                        timestamp = Instant.parse("2026-04-01T00:00:01Z"),
                    ),
                ) + history
                krakenService.tradeHistoryTotalCountOverride = shiftedHistory.size
                krakenService.tradeHistorySupplier = { _, offset ->
                    shiftedHistory.drop(offset ?: 0).take(50)
                }
                status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.IN_PROGRESS
                status.tradeOffset shouldBe "150"
                repository.getTradesInRange(Instant.EPOCH, now.plusSeconds(1)).size shouldBe 201

                now = now.plusSeconds(InceptionRecoveryService.RETRY_INTERVAL_SECONDS + 1)
                status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.IN_PROGRESS
                status.tradeOffset shouldBe "completed"
                repository.getTradesInRange(Instant.EPOCH, now.plusSeconds(1)).size shouldBe shiftedHistory.size

                now = now.plusSeconds(InceptionRecoveryService.RETRY_INTERVAL_SECONDS + 1)
                status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.COMPLETE_NO_BOT_EVIDENCE
                status.tradeOffset shouldBe "completed"
                repository.getTradesInRange(Instant.EPOCH, now.plusSeconds(1)).size shouldBe shiftedHistory.size
                krakenService.getTradeHistoryCallCount shouldBe 12
                repository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_EPOCH_MS) shouldBe ""
            }
        }

        "bounded recovery rewinds ledger offsets when newest-first totals shift" {
            runTest {
                val ledgerHistory = (0 until 250).map { index ->
                    LedgerEvent(
                        ledgerId = "ledger-$index",
                        time = Instant.parse("2026-04-01T00:00:00Z").minusSeconds(index.toLong()),
                        type = "staking",
                        asset = Asset.BTC,
                        amount = BigDecimal.ZERO,
                    )
                }
                krakenService.tradeHistoryTotalCountOverride = 0
                krakenService.seedLedgerEntries(ledgerHistory)
                newService().prepareForCurrentConfiguration(null) shouldBe true
                repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_STATUS, "COMPLETE")
                repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_OFFSET, "completed")

                var status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.IN_PROGRESS
                status.ledgerOffset shouldBe "200"
                krakenService.getLedgersCallCount shouldBe 4
                ledgerRepository.getLedgersInRange(Instant.EPOCH, now.plusSeconds(1)).size shouldBe 200

                now = now.plusSeconds(InceptionRecoveryService.RETRY_INTERVAL_SECONDS + 1)
                val shiftedHistory = listOf(
                    LedgerEvent(
                        ledgerId = "ledger-new",
                        time = Instant.parse("2026-04-01T00:00:01Z"),
                        type = "staking",
                        asset = Asset.BTC,
                        amount = BigDecimal.ZERO,
                    ),
                ) + ledgerHistory
                krakenService.seedLedgerEntries(shiftedHistory)
                status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.IN_PROGRESS
                status.ledgerOffset shouldBe "150"
                ledgerRepository.getLedgersInRange(Instant.EPOCH, now.plusSeconds(1)).size shouldBe 201

                now = now.plusSeconds(InceptionRecoveryService.RETRY_INTERVAL_SECONDS + 1)
                status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.COMPLETE_NO_BOT_EVIDENCE
                status.ledgerOffset shouldBe "completed"
                ledgerRepository.getLedgersInRange(Instant.EPOCH, now.plusSeconds(1)).size shouldBe 251
                krakenService.getLedgersCallCount shouldBe 12
            }
        }

        "recovery continues past an exact full page when Kraken omits the total" {
            runTest {
                val history = (0 until KrakenApiConstants.TRADE_HISTORY_PAGE_SIZE).map { index ->
                    apiTrade(
                        id = "full-page-$index",
                        timestamp = Instant.parse("2026-04-01T00:00:00Z").minusSeconds(index.toLong()),
                    )
                }
                krakenService.tradeHistoryTotalCountOverride = 0
                krakenService.tradeHistorySupplier = { _, offset -> history.drop(offset ?: 0).take(50) }
                val ledgerHistory = (0 until KrakenApiConstants.LEDGER_PAGE_SIZE).map { index ->
                    LedgerEvent(
                        ledgerId = "full-ledger-$index",
                        time = Instant.parse("2026-04-01T00:00:00Z").minusSeconds(index.toLong()),
                        type = "staking",
                        asset = Asset.BTC,
                        amount = BigDecimal.ZERO,
                    )
                }
                krakenService.ledgerTotalCountOverride = 0
                krakenService.ledgerSupplier = { _, offset, _, _ -> ledgerHistory.drop(offset ?: 0).take(50) }

                val status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.COMPLETE_NO_BOT_EVIDENCE
                status.tradeOffset shouldBe "completed"
                status.reason shouldBe "no positively owned bot fill"
                krakenService.getTradeHistoryCallCount shouldBe 2
            }
        }

        "recovery clamps stale pagination metadata before fetching an overlap" {
            runTest {
                newService().prepareForCurrentConfiguration(null) shouldBe true
                repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_STATUS, "IN_PROGRESS")
                repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_OFFSET, "100")
                repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_TOTAL, "50")
                ledgerRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_STATUS, "IN_PROGRESS")
                ledgerRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_OFFSET, "100")
                ledgerRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_TOTAL, "50")
                var requestedOffset: Int? = null
                var requestedLedgerOffset: Int? = null
                krakenService.tradeHistoryTotalCountOverride = -1
                krakenService.ledgerTotalCountOverride = -1
                krakenService.tradeHistorySupplier = { _, offset ->
                    requestedOffset = offset
                    emptyList()
                }
                krakenService.ledgerSupplier = { _, offset, _, _ ->
                    requestedLedgerOffset = offset
                    emptyList()
                }

                val status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.COMPLETE_NO_BOT_EVIDENCE
                requestedOffset shouldBe 50
                requestedLedgerOffset shouldBe 50
                status.tradeOffset shouldBe "completed"
                status.ledgerOffset shouldBe "completed"
            }
        }

        "a count shift on page zero continues from the next page boundary" {
            runTest {
                val bot = apiTrade("page-zero-shift", Instant.parse("2026-04-01T00:00:00Z"))
                newService().prepareForCurrentConfiguration(null) shouldBe true
                repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_STATUS, "IN_PROGRESS")
                repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_OFFSET, "0")
                repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_TOTAL, "1")
                ledgerRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_STATUS, "COMPLETE")
                ledgerRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_OFFSET, "completed")
                krakenService.tradeHistoryTotalCountOverride = 2
                krakenService.tradeHistorySupplier = { _, offset ->
                    if ((offset ?: 0) == 0) listOf(bot) else emptyList()
                }

                val status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.COMPLETE_NO_BOT_EVIDENCE
                status.tradeOffset shouldBe "completed"
                krakenService.getTradeHistoryCallCount shouldBe 2
            }
        }

        "a ledger count shift on page zero continues from the next page boundary" {
            runTest {
                val event = LedgerEvent(
                    ledgerId = "ledger-page-zero-shift",
                    time = Instant.parse("2026-04-01T00:00:00Z"),
                    type = "staking",
                    asset = Asset.BTC,
                    amount = BigDecimal.ZERO,
                )
                newService().prepareForCurrentConfiguration(null) shouldBe true
                repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_STATUS, "COMPLETE")
                repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_OFFSET, "completed")
                ledgerRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_STATUS, "IN_PROGRESS")
                ledgerRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_OFFSET, "0")
                ledgerRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_TOTAL, "1")
                krakenService.ledgerTotalCountOverride = 2
                krakenService.ledgerSupplier = { _, offset, _, _ ->
                    if ((offset ?: 0) == 0) listOf(event) else emptyList()
                }

                val status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.COMPLETE_NO_BOT_EVIDENCE
                status.ledgerOffset shouldBe "completed"
                krakenService.getLedgersCallCount shouldBe 2
            }
        }

        "status exposes durable recovery progress without fetching history" {
            runTest {
                newService().getStatus().status shouldBe InceptionRecoveryStatus.NOT_STARTED

                repository.setSyncMetadata(
                    SyncMetadataKeys.INCEPTION_RECOVERY_STATUS,
                    InceptionRecoveryStatus.IN_PROGRESS,
                )
                repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_OFFSET, "50")
                repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_TOTAL, "100")
                ledgerRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_OFFSET, "completed")
                ledgerRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_TOTAL, "25")
                repository.setSyncMetadata(
                    SyncMetadataKeys.INCEPTION_RECOVERY_CANDIDATE_EPOCH_MS,
                    Instant.parse("2026-01-02T00:00:00Z").toEpochMilli().toString(),
                )
                repository.setSyncMetadata(
                    SyncMetadataKeys.INCEPTION_RECOVERY_HORIZON_EPOCH_SEC,
                    now.epochSecond.toString(),
                )

                val status = newService().getStatus()

                status.status shouldBe InceptionRecoveryStatus.IN_PROGRESS
                status.tradeOffset shouldBe "50"
                status.tradeTotal shouldBe "100"
                status.ledgerOffset shouldBe "completed"
                status.ledgerTotal shouldBe "25"
                status.candidateTime shouldBe "2026-01-02T00:00:00Z"
                status.coverageHorizon shouldBe now.toString()
                krakenService.getTradeHistoryCallCount shouldBe 0
                krakenService.getLedgersCallCount shouldBe 0
            }
        }

        "recovery tolerates legacy databases missing optional progress rows" {
            runTest {
                val botTime = Instant.parse("2026-01-02T00:00:00Z")
                val bot = apiTrade("bot", botTime)
                repository.saveTrade(localEstimate(botTime, bot))
                repository.saveSnapshot(
                    anchorSnapshot(
                        balances = mapOf(Asset.BTC to BigDecimal("0.01"), Asset.USD to BigDecimal("999.00")),
                        timestamp = Instant.parse("2026-01-03T00:00:00Z"),
                    ),
                )
                krakenService.tradeHistoryTotalCountOverride = 1
                krakenService.tradeHistorySupplier = { _, _ -> listOf(bot) }
                newService().prepareForCurrentConfiguration(null) shouldBe true
                deleteMetadata(
                    SyncMetadataKeys.INCEPTION_RECOVERY_LAST_ATTEMPT_EPOCH_SEC,
                    SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_TOTAL,
                    SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_TOTAL,
                    SyncMetadataKeys.INCEPTION_RECOVERY_BASELINE_SNAPSHOT_ID,
                )

                val status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.CONFIRMED
            }
        }

        "recovery distinguishes manual override, simulation, credentials, and retry throttle" {
            runTest {
                config = config.copy(settings = config.settings.copy(inceptionDate = "2026-01-01"))
                newService().recoverOneBoundedRun().status shouldBe InceptionRecoveryStatus.MANUAL_OVERRIDE
                krakenService.getTradeHistoryCallCount shouldBe 0

                config = config.copy(settings = config.settings.copy(inceptionDate = null, simulation = true))
                newService().recoverOneBoundedRun().status shouldBe InceptionRecoveryStatus.UNAVAILABLE
                krakenService.getTradeHistoryCallCount shouldBe 0

                config = config.copy(
                    kraken = KrakenCredentials("", ""),
                    settings = config.settings.copy(simulation = false),
                )
                newService().recoverOneBoundedRun().status shouldBe InceptionRecoveryStatus.UNAVAILABLE
                krakenService.getTradeHistoryCallCount shouldBe 0

                config = appConfig(listOf(Allocation(Asset.BTC, 50.0), Allocation(Asset.USD, 50.0)))
                krakenService.tradeHistoryTotalCountOverride = 0
                val first = newService().recoverOneBoundedRun()
                first.status shouldBe InceptionRecoveryStatus.COMPLETE_NO_BOT_EVIDENCE
                val calls = krakenService.getTradeHistoryCallCount
                newService().recoverOneBoundedRun().status shouldBe InceptionRecoveryStatus.COMPLETE_NO_BOT_EVIDENCE
                krakenService.getTradeHistoryCallCount shouldBe calls

                now = now.plusSeconds(InceptionRecoveryService.RETRY_INTERVAL_SECONDS + 1)
                newService().recoverOneBoundedRun().status shouldBe InceptionRecoveryStatus.COMPLETE_NO_BOT_EVIDENCE
                krakenService.getTradeHistoryCallCount shouldBe calls

                repository.setSyncMetadata(
                    SyncMetadataKeys.INCEPTION_RECOVERY_LAST_ATTEMPT_EPOCH_SEC,
                    now.plusSeconds(60).epochSecond.toString(),
                )
                newService().recoverOneBoundedRun().status shouldBe InceptionRecoveryStatus.COMPLETE_NO_BOT_EVIDENCE
                krakenService.getTradeHistoryCallCount shouldBe calls

                repository.setSyncMetadata(
                    SyncMetadataKeys.INCEPTION_RECOVERY_LAST_ATTEMPT_EPOCH_SEC,
                    "not-a-timestamp",
                )
                newService().recoverOneBoundedRun().status shouldBe InceptionRecoveryStatus.COMPLETE_NO_BOT_EVIDENCE
                krakenService.getTradeHistoryCallCount shouldBe calls
            }
        }

        "a blank inception override remains eligible for automatic recovery" {
            runTest {
                config = config.copy(settings = config.settings.copy(inceptionDate = " "))
                krakenService.tradeHistoryTotalCountOverride = 0

                val status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.COMPLETE_NO_BOT_EVIDENCE
                status.reason shouldBe "no positively owned bot fill"
            }
        }

        "recovery reports a missing retained anchor instead of inventing balances" {
            runTest {
                val bot = apiTrade(
                    "bot",
                    Instant.parse("2026-01-02T00:00:00Z"),
                    volume = BigDecimal("0.5"),
                    usdAmount = BigDecimal("50.00"),
                    fee = BigDecimal("0.50"),
                )
                repository.saveTrade(localEstimate(bot.timestamp, bot))
                krakenService.tradeHistoryTotalCountOverride = 1
                krakenService.tradeHistorySupplier = { _, _ -> listOf(bot) }

                val status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.BASELINE_UNAVAILABLE
                status.reason shouldBe "no retained balance anchor"
                repository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_EPOCH_MS) shouldBe ""
            }
        }

        "recovery reports no retained anchor when the only snapshot is observed after the horizon" {
            runTest {
                val botTime = Instant.parse("2026-01-02T00:00:00Z")
                val bot = apiTrade("bot", botTime)
                repository.saveTrade(localEstimate(botTime, bot))
                repository.saveSnapshot(
                    anchorSnapshot(
                        balances = mapOf(Asset.BTC to BigDecimal("0.01"), Asset.USD to BigDecimal("999.00")),
                        timestamp = Instant.parse("2026-01-03T00:00:00Z"),
                    ).copy(balancesObservedAt = now.plusSeconds(60)),
                )
                krakenService.tradeHistoryTotalCountOverride = 1
                krakenService.tradeHistorySupplier = { _, _ -> listOf(bot) }

                val status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.BASELINE_UNAVAILABLE
                status.reason shouldBe "no retained balance anchor"
            }
        }

        "recovery rejects a candidate newer than the retained balance anchor" {
            runTest {
                val bot = apiTrade("bot", Instant.parse("2026-01-02T00:00:00Z"))
                repository.saveTrade(localEstimate(bot.timestamp, bot))
                repository.saveSnapshot(
                    anchorSnapshot(
                        balances = mapOf(Asset.BTC to BigDecimal("0.01"), Asset.USD to BigDecimal("999.00")),
                        timestamp = Instant.parse("2026-01-01T00:00:00Z"),
                    ),
                )
                krakenService.tradeHistoryTotalCountOverride = 1
                krakenService.tradeHistorySupplier = { _, _ -> listOf(bot) }

                val status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.BASELINE_UNAVAILABLE
                status.reason shouldBe "candidate is newer than retained anchor"
            }
        }

        "recovery backwalks an anchor whose balance observation is after the fixed horizon" {
            runTest {
                val botTime = Instant.parse("2026-01-01T00:00:00Z")
                val bot = apiTrade("bot", botTime)
                repository.saveTrade(localEstimate(botTime, bot))
                repository.saveSnapshot(
                    anchorSnapshot(
                        balances = mapOf(Asset.BTC to BigDecimal("0.01"), Asset.USD to BigDecimal("999.00")),
                        timestamp = Instant.parse("2026-01-02T00:00:00Z"),
                    ),
                )
                repository.saveSnapshot(
                    anchorSnapshot(
                        balances = mapOf(Asset.BTC to BigDecimal("0.01"), Asset.USD to BigDecimal("999.00")),
                        timestamp = Instant.parse("2026-01-03T00:00:00Z"),
                    ).copy(balancesObservedAt = now.plusSeconds(60)),
                )
                newService().prepareForCurrentConfiguration(null) shouldBe true
                repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_BASELINE_SNAPSHOT_ID, "999")
                krakenService.tradeHistoryTotalCountOverride = 1
                krakenService.tradeHistorySupplier = { _, _ -> listOf(bot) }

                val status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.CONFIRMED
                val baselineId = repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_SNAPSHOT_ID)?.toIntOrNull()
                    ?: error("missing baseline id")
                repository.getSnapshotById(baselineId)?.timestamp shouldBe botTime.minusMillis(1)
                val retainedAnchorId = repository.getSnapshotId(Instant.parse("2026-01-03T00:00:00Z"))
                    ?: error("missing retained anchor id")
                repository.setSyncMetadata(
                    SyncMetadataKeys.INCEPTION_RECOVERY_BASELINE_SNAPSHOT_ID,
                    retainedAnchorId.toString(),
                )
                repository.setSyncMetadata(
                    SyncMetadataKeys.INCEPTION_RECOVERY_STATUS,
                    InceptionRecoveryStatus.IN_PROGRESS,
                )
                now = now.plusSeconds(InceptionRecoveryService.RETRY_INTERVAL_SECONDS + 1)
                newService().recoverOneBoundedRun().status shouldBe InceptionRecoveryStatus.CONFIRMED
            }
        }

        "recovery fails closed when the retained anchor has a different asset universe" {
            runTest {
                val bot = apiTrade("bot", Instant.parse("2026-01-02T00:00:00Z"))
                repository.saveTrade(localEstimate(bot.timestamp, bot))
                repository.saveSnapshot(
                    anchorSnapshot(
                        balances = mapOf(Asset.BTC to BigDecimal("0.01"), Asset.USD to BigDecimal("999.00")),
                        timestamp = Instant.parse("2026-01-03T00:00:00Z"),
                    ),
                )
                config = appConfig(
                    listOf(
                        Allocation(Asset.BTC, 40.0),
                        Allocation(Asset.ETH, 40.0),
                        Allocation(Asset.USD, 20.0),
                    ),
                )
                krakenService.tradeHistoryTotalCountOverride = 1
                krakenService.tradeHistorySupplier = { _, _ -> listOf(bot) }

                val status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.AMBIGUOUS
                status.reason shouldBe "configured asset universe changed"
            }
        }

        "recovery keeps unsupported ledger rows ambiguous" {
            runTest {
                val bot = apiTrade("bot", Instant.parse("2026-01-02T00:00:00Z"))
                repository.saveTrade(localEstimate(bot.timestamp, bot))
                repository.saveSnapshot(
                    anchorSnapshot(
                        balances = mapOf(Asset.BTC to BigDecimal("0.01"), Asset.USD to BigDecimal("999.00")),
                        timestamp = Instant.parse("2026-01-03T00:00:00Z"),
                    ),
                )
                krakenService.tradeHistoryTotalCountOverride = 1
                krakenService.tradeHistorySupplier = { _, _ -> listOf(bot) }
                krakenService.seedLedgerEntries(
                    listOf(
                        LedgerEvent(
                            ledgerId = "unknown-ledger",
                            time = bot.timestamp.plusSeconds(3600),
                            type = "mystery",
                            asset = Asset.USD,
                            amount = BigDecimal("1.00"),
                        ),
                    ),
                )

                val status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.AMBIGUOUS
                status.reason shouldBe "unsupported ledger type mystery"
            }
        }

        "recovery keeps malformed ledger fees ambiguous" {
            runTest {
                val bot = apiTrade("bot", Instant.parse("2026-01-02T00:00:00Z"))
                repository.saveTrade(localEstimate(bot.timestamp, bot))
                repository.saveSnapshot(
                    anchorSnapshot(
                        balances = mapOf(Asset.BTC to BigDecimal("0.01"), Asset.USD to BigDecimal("999.00")),
                        timestamp = Instant.parse("2026-01-03T00:00:00Z"),
                    ),
                )
                krakenService.tradeHistoryTotalCountOverride = 1
                krakenService.tradeHistorySupplier = { _, _ -> listOf(bot) }
                krakenService.seedLedgerEntries(
                    listOf(
                        LedgerEvent(
                            ledgerId = "invalid-fee",
                            time = bot.timestamp.plusSeconds(3600),
                            type = "staking",
                            asset = Asset.BTC,
                            amount = BigDecimal("0.01"),
                            fee = BigDecimal("-0.01"),
                            hasAuthoritativeFee = true,
                            hasValidFee = false,
                        ),
                    ),
                )

                val status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.AMBIGUOUS
                status.reason shouldBe "invalid ledger fee"
            }
        }

        "reverse accounting handles sells and harmless out-of-universe zero ledger rows" {
            runTest {
                val botTime = Instant.parse("2026-01-02T00:00:00Z")
                val buy = apiTrade(
                    "buy",
                    botTime,
                    volume = BigDecimal("0.5"),
                    usdAmount = BigDecimal("50.00"),
                    fee = BigDecimal("0.50"),
                )
                val sell = apiTrade(
                    "sell",
                    botTime.plusSeconds(3600),
                    volume = BigDecimal("0.1"),
                    usdAmount = BigDecimal("10.00"),
                    fee = BigDecimal("0.10"),
                ).copy(side = OrderSide.SELL.apiValue)
                repository.saveTrade(localEstimate(botTime, buy))
                repository.saveTrade(localEstimate(sell.timestamp, sell))
                repository.saveSnapshot(
                    anchorSnapshot(
                        balances = mapOf(Asset.BTC to BigDecimal("0.4"), Asset.USD to BigDecimal("959.40")),
                        timestamp = Instant.parse("2026-01-03T00:00:00Z"),
                    ),
                )
                krakenService.tradeHistoryTotalCountOverride = 0
                krakenService.seedLedgerEntries(
                    listOf(
                        LedgerEvent(
                            ledgerId = "zero-untracked",
                            time = botTime.plusSeconds(1800),
                            type = "adjustment",
                            asset = Asset.ETH,
                            amount = BigDecimal.ZERO,
                        ),
                    ),
                )

                val status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.CONFIRMED
                val baselineId = repository
                    .getSyncMetadata(SyncMetadataKeys.INCEPTION_SNAPSHOT_ID)
                    ?.toInt() ?: error("missing baseline id")
                repository.getSnapshotById(baselineId)?.let { baseline ->
                    baseline.assets.getValue(Asset.BTC).balance.shouldBeEqualComparingTo(BigDecimal.ZERO)
                    baseline.assets.getValue(Asset.USD).balance.shouldBeEqualComparingTo(BigDecimal("1000.00"))
                }
            }
        }

        "recovery rejects a nonzero ledger change outside the configured universe" {
            runTest {
                val bot = apiTrade("bot", Instant.parse("2026-01-02T00:00:00Z"))
                repository.saveTrade(localEstimate(bot.timestamp, bot))
                repository.saveSnapshot(
                    anchorSnapshot(
                        balances = mapOf(Asset.BTC to BigDecimal("0.01"), Asset.USD to BigDecimal("999.00")),
                        timestamp = Instant.parse("2026-01-03T00:00:00Z"),
                    ),
                )
                krakenService.tradeHistoryTotalCountOverride = 1
                krakenService.tradeHistorySupplier = { _, _ -> listOf(bot) }
                krakenService.seedLedgerEntries(
                    listOf(
                        LedgerEvent(
                            ledgerId = "untracked-ledger",
                            time = bot.timestamp.plusSeconds(3600),
                            type = "adjustment",
                            asset = Asset.ETH,
                            amount = BigDecimal("0.10"),
                        ),
                    ),
                )

                val status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.AMBIGUOUS
                status.reason shouldBe "ledger changed tracked universe"
            }
        }

        "a zero-volume trade outside the configured universe still fails closed" {
            runTest {
                val botTime = Instant.parse("2026-01-02T00:00:00Z")
                val bot = apiTrade("bot", botTime)
                repository.saveTrade(localEstimate(botTime, bot))
                repository.saveTrade(
                    TestFixtures.tradeRecord(
                        timestamp = botTime.plusSeconds(1),
                        pair = Asset.ETH_USD_PAIR,
                        side = OrderSide.BUY.apiValue,
                        symbol = Asset.ETH,
                        volume = BigDecimal.ZERO,
                        usdAmount = BigDecimal.ZERO,
                        price = BigDecimal("100.00"),
                        source = TradeSource.LOCAL_ESTIMATE,
                        cycleId = "outside-zero-cycle",
                    ),
                )
                repository.saveSnapshot(
                    anchorSnapshot(
                        balances = mapOf(Asset.BTC to BigDecimal("0.01"), Asset.USD to BigDecimal("999.00")),
                        timestamp = Instant.parse("2026-01-03T00:00:00Z"),
                    ),
                )
                krakenService.tradeHistoryTotalCountOverride = 1
                krakenService.tradeHistorySupplier = { _, _ -> listOf(bot) }

                val status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.AMBIGUOUS
                status.reason shouldBe "unsupported trade economics"
            }
        }

        "recovery rejects nonzero trades outside the configured universe" {
            runTest {
                val botTime = Instant.parse("2026-01-02T00:00:00Z")
                val bot = apiTrade("bot", botTime)
                repository.saveTrade(localEstimate(botTime, bot))
                repository.saveTrade(
                    TestFixtures.tradeRecord(
                        timestamp = botTime.plusSeconds(1),
                        pair = Asset.ETH_USD_PAIR,
                        side = OrderSide.BUY.apiValue,
                        symbol = Asset.ETH,
                        volume = BigDecimal("0.1"),
                        usdAmount = BigDecimal("10.00"),
                        price = BigDecimal("100.00"),
                        source = TradeSource.LOCAL_ESTIMATE,
                        cycleId = "outside-cycle",
                    ),
                )
                repository.saveSnapshot(
                    anchorSnapshot(
                        balances = mapOf(Asset.BTC to BigDecimal("0.01"), Asset.USD to BigDecimal("999.00")),
                        timestamp = Instant.parse("2026-01-03T00:00:00Z"),
                    ),
                )
                krakenService.tradeHistoryTotalCountOverride = 1
                krakenService.tradeHistorySupplier = { _, _ -> listOf(bot) }

                val status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.AMBIGUOUS
                status.reason shouldBe "trade outside configured universe"
            }
        }

        "recovery rejects unsupported trade economics" {
            runTest {
                val botTime = Instant.parse("2026-01-02T00:00:00Z")
                val invalidTrade = TestFixtures.tradeRecord(
                    timestamp = botTime,
                    pair = Asset.BTC_USD_PAIR,
                    side = OrderSide.BUY.apiValue,
                    symbol = Asset.BTC,
                    volume = BigDecimal("-0.01"),
                    usdAmount = BigDecimal("1.00"),
                    price = BigDecimal("100.00"),
                    fee = BigDecimal.ZERO,
                    source = TradeSource.LOCAL_ESTIMATE,
                    cycleId = "invalid-cycle",
                )
                repository.saveTrade(invalidTrade)
                repository.saveSnapshot(
                    anchorSnapshot(
                        balances = mapOf(Asset.BTC to BigDecimal("0.01"), Asset.USD to BigDecimal("999.00")),
                        timestamp = Instant.parse("2026-01-03T00:00:00Z"),
                    ),
                )
                krakenService.tradeHistoryTotalCountOverride = 0

                val status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.AMBIGUOUS
                status.reason shouldBe "unsupported trade economics"
            }
        }

        "recovery rejects a trade with an unsupported side" {
            runTest {
                val botTime = Instant.parse("2026-01-02T00:00:00Z")
                repository.saveTrade(
                    TestFixtures.tradeRecord(
                        timestamp = botTime,
                        pair = Asset.BTC_USD_PAIR,
                        side = "hold",
                        symbol = Asset.BTC,
                        volume = BigDecimal("0.01"),
                        usdAmount = BigDecimal("1.00"),
                        price = BigDecimal("100.00"),
                        fee = BigDecimal.ZERO,
                        source = TradeSource.LOCAL_ESTIMATE,
                        cycleId = "invalid-side-cycle",
                    ),
                )
                repository.saveSnapshot(
                    anchorSnapshot(
                        balances = mapOf(Asset.BTC to BigDecimal("0.01"), Asset.USD to BigDecimal("999.00")),
                        timestamp = Instant.parse("2026-01-03T00:00:00Z"),
                    ),
                )
                krakenService.tradeHistoryTotalCountOverride = 0

                val status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.AMBIGUOUS
                status.reason shouldBe "unsupported trade economics"
            }
        }

        "duplicate local and API rows are replayed once using the authoritative fill" {
            runTest {
                val botTime = Instant.parse("2026-01-02T00:00:00Z")
                val bot = apiTrade("bot", botTime)
                repository.saveTrade(localEstimate(botTime, bot))
                repository.saveTrade(bot)
                repository.saveSnapshot(
                    anchorSnapshot(
                        balances = mapOf(Asset.BTC to BigDecimal("0.01"), Asset.USD to BigDecimal("999.00")),
                        timestamp = Instant.parse("2026-01-03T00:00:00Z"),
                    ),
                )
                krakenService.tradeHistoryTotalCountOverride = 0
                val orderIntentRepository = mockk<OrderIntentRepository>()
                coEvery {
                    orderIntentRepository.getKnownRebalancerOrderIdentities(any(), any())
                } returns RebalancerOrderIdentities(setOf(bot.orderTxid!!))

                val status = newService(orderIntentRepository = orderIntentRepository).recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.CONFIRMED
                val baselineId = repository
                    .getSyncMetadata(SyncMetadataKeys.INCEPTION_SNAPSHOT_ID)
                    ?.toInt() ?: error("missing baseline id")
                val baseline = repository.getSnapshotById(baselineId) ?: error("missing baseline")
                baseline.assets.getValue(Asset.BTC).balance.shouldBeEqualComparingTo(BigDecimal.ZERO)
                baseline.assets.getValue(Asset.USD).balance.shouldBeEqualComparingTo(BigDecimal("1000.01"))
            }
        }

        "recovery rejects negative reconstructed balances" {
            runTest {
                val botTime = Instant.parse("2026-01-02T00:00:00Z")
                val negativeBot = apiTrade(
                    "negative",
                    botTime,
                    volume = BigDecimal("0.50000002"),
                    usdAmount = BigDecimal("50.00"),
                    fee = BigDecimal("0.50"),
                    price = BigDecimal("100.00"),
                )
                repository.saveTrade(localEstimate(botTime, negativeBot))
                repository.saveSnapshot(
                    anchorSnapshot(
                        balances = mapOf(Asset.BTC to BigDecimal("0.50"), Asset.USD to BigDecimal("949.50")),
                        timestamp = Instant.parse("2026-01-03T00:00:00Z"),
                    ),
                )
                krakenService.tradeHistoryTotalCountOverride = 1
                krakenService.tradeHistorySupplier = { _, _ -> listOf(negativeBot) }

                val status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.BASELINE_UNAVAILABLE
                status.reason shouldBe "negative reconstructed balance"
            }
        }

        "recovery rejects a negative ledger fee during reverse replay" {
            runTest {
                val botTime = Instant.parse("2026-01-02T00:00:00Z")
                val bot = apiTrade("bot", botTime, volume = BigDecimal("0.5"), usdAmount = BigDecimal("50.00"))
                repository.saveTrade(localEstimate(botTime, bot))
                repository.saveTrade(
                    localEstimate(
                        botTime.plusSeconds(3600),
                        apiTrade("invalid-fee", botTime.plusSeconds(3600)),
                    ).copy(fee = BigDecimal("-0.01")),
                )
                repository.saveSnapshot(
                    anchorSnapshot(
                        balances = mapOf(Asset.BTC to BigDecimal("0.51"), Asset.USD to BigDecimal("948.49")),
                        timestamp = Instant.parse("2026-01-03T00:00:00Z"),
                    ),
                )
                krakenService.tradeHistoryTotalCountOverride = 1
                krakenService.tradeHistorySupplier = { _, _ -> listOf(bot) }

                val status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.AMBIGUOUS
                status.reason shouldBe "unsupported trade economics"
            }
        }

        "recovery rejects a negative trade notional during reverse replay" {
            runTest {
                val botTime = Instant.parse("2026-01-02T00:00:00Z")
                val bot = apiTrade("bot", botTime, volume = BigDecimal("0.5"), usdAmount = BigDecimal("50.00"))
                repository.saveTrade(localEstimate(botTime, bot))
                repository.saveTrade(
                    localEstimate(
                        botTime.plusSeconds(3600),
                        apiTrade("invalid-notional", botTime.plusSeconds(3600)),
                    ).copy(usdAmount = BigDecimal("-1.00")),
                )
                repository.saveSnapshot(
                    anchorSnapshot(
                        balances = mapOf(Asset.BTC to BigDecimal("0.51"), Asset.USD to BigDecimal("948.49")),
                        timestamp = Instant.parse("2026-01-03T00:00:00Z"),
                    ),
                )
                krakenService.tradeHistoryTotalCountOverride = 1
                krakenService.tradeHistorySupplier = { _, _ -> listOf(bot) }

                val status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.AMBIGUOUS
                status.reason shouldBe "unsupported trade economics"
            }
        }

        "a zero-value recovered fill leaves a non-positive baseline unavailable" {
            runTest {
                val botTime = Instant.parse("2026-01-02T00:00:00Z")
                val bot = apiTrade(
                    "zero",
                    botTime,
                    volume = BigDecimal.ZERO,
                    usdAmount = BigDecimal.ZERO,
                    fee = BigDecimal.ZERO,
                    price = BigDecimal("100.00"),
                )
                repository.saveTrade(localEstimate(botTime, bot))
                repository.saveSnapshot(
                    anchorSnapshot(
                        balances = mapOf(Asset.BTC to BigDecimal.ZERO, Asset.USD to BigDecimal.ZERO),
                        timestamp = Instant.parse("2026-01-03T00:00:00Z"),
                    ),
                )
                krakenService.tradeHistoryTotalCountOverride = 1
                krakenService.tradeHistorySupplier = { _, _ -> listOf(bot) }

                val status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.BASELINE_UNAVAILABLE
                status.reason shouldBe "non-positive reconstructed baseline"
            }
        }

        "the negative-balance tolerance clamps a tiny reconstruction residue" {
            runTest {
                val botTime = Instant.parse("2026-01-02T00:00:00Z")
                val bot = apiTrade(
                    "tiny-residue",
                    botTime,
                    volume = BigDecimal("0.500000005"),
                    usdAmount = BigDecimal("50.00"),
                    fee = BigDecimal("0.50"),
                    price = BigDecimal("100.00"),
                )
                repository.saveTrade(localEstimate(botTime, bot))
                repository.saveSnapshot(
                    anchorSnapshot(
                        balances = mapOf(Asset.BTC to BigDecimal("0.5"), Asset.USD to BigDecimal("949.50")),
                        timestamp = Instant.parse("2026-01-03T00:00:00Z"),
                    ),
                )
                krakenService.tradeHistoryTotalCountOverride = 1
                krakenService.tradeHistorySupplier = { _, _ -> listOf(bot) }

                val status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.CONFIRMED
                val baselineId = repository
                    .getSyncMetadata(SyncMetadataKeys.INCEPTION_SNAPSHOT_ID)
                    ?.toInt() ?: error("missing baseline id")
                repository.getSnapshotById(baselineId)?.assets?.getValue(Asset.BTC)?.balance
                    ?.shouldBeEqualComparingTo(BigDecimal.ZERO)
            }
        }

        "failed trade and ledger pages remain resumable and do not claim coverage" {
            runTest {
                krakenService.tradeHistorySupplier = { _, _ -> throw IllegalStateException("trades unavailable") }

                var status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.FAILED
                repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_STATUS) shouldBe "FAILED"
                repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_OFFSET) shouldBe ""

                now = now.plusSeconds(InceptionRecoveryService.RETRY_INTERVAL_SECONDS + 1)
                krakenService.tradeHistorySupplier = { _, _ -> emptyList() }
                krakenService.tradeHistoryTotalCountOverride = 0
                krakenService.ledgerSupplier = { _, _, _, _ -> throw IllegalStateException("ledgers unavailable") }
                status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.FAILED
                repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_STATUS) shouldBe "COMPLETE"
                ledgerRepository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_STATUS) shouldBe "FAILED"

                now = now.plusSeconds(InceptionRecoveryService.RETRY_INTERVAL_SECONDS + 1)
                krakenService.ledgerSupplier = { _, _, _, _ -> emptyList() }
                status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.COMPLETE_NO_BOT_EVIDENCE
                ledgerRepository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_STATUS) shouldBe "COMPLETE"
            }
        }

        "manual fills and a misleading multi-symbol burst do not establish inception" {
            runTest {
                config = appConfig(
                    listOf(
                        Allocation(Asset.BTC, 40.0),
                        Allocation(Asset.ETH, 40.0),
                        Allocation(Asset.USD, 20.0),
                    ),
                )
                val burst = listOf(
                    apiTrade("manual-btc", Instant.parse("2026-01-01T00:00:00Z"), symbol = Asset.BTC),
                    apiTrade("manual-eth", Instant.parse("2026-01-01T00:00:01Z"), symbol = Asset.ETH),
                )
                krakenService.tradeHistoryTotalCountOverride = burst.size
                krakenService.tradeHistorySupplier = { _, offset -> burst.drop(offset ?: 0).take(50) }

                val status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.COMPLETE_NO_BOT_EVIDENCE
                status.reason shouldBe "no positively owned bot fill"
                repository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_EPOCH_MS) shouldBe ""
            }
        }

        "unknown recovered trade ownership remains ambiguous without a bot candidate" {
            runTest {
                repository.saveTrade(
                    TestFixtures.tradeRecord(
                        timestamp = Instant.parse("2026-01-01T00:00:00Z"),
                        pair = Asset.tradingPair(Asset.BTC),
                        side = OrderSide.BUY.name,
                        symbol = Asset.BTC,
                        volume = BigDecimal("0.1"),
                        usdAmount = BigDecimal("10.00"),
                        price = BigDecimal("100.00"),
                        source = TradeSource.LEGACY_UNKNOWN,
                    ),
                )
                krakenService.tradeHistoryTotalCountOverride = 0

                val status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.AMBIGUOUS
                status.reason shouldBe "trade ownership is ambiguous"
            }
        }

        "ineligible trade rows do not become inception candidates" {
            runTest {
                repository.saveTrade(
                    TestFixtures.tradeRecord(
                        timestamp = Instant.parse("2026-01-01T00:00:00Z"),
                        pair = Asset.BTC_USD_PAIR,
                        side = OrderSide.BUY.apiValue,
                        symbol = "",
                        volume = BigDecimal("0.1"),
                        usdAmount = BigDecimal("10.00"),
                        price = BigDecimal("100.00"),
                        source = TradeSource.LEGACY_UNKNOWN,
                    ),
                )
                repository.saveTrade(
                    TestFixtures.tradeRecord(
                        timestamp = Instant.parse("2026-01-01T00:00:01Z"),
                        pair = Asset.BTC_USD_PAIR,
                        side = OrderSide.BUY.apiValue,
                        symbol = Asset.USD,
                        volume = BigDecimal("10.00"),
                        usdAmount = BigDecimal("10.00"),
                        price = BigDecimal.ONE,
                        source = TradeSource.LEGACY_UNKNOWN,
                    ),
                )
                repository.saveTrade(
                    TestFixtures.tradeRecord(
                        timestamp = Instant.parse("2026-01-01T00:00:02Z"),
                        pair = Asset.BTC_USD_PAIR,
                        side = OrderSide.BUY.apiValue,
                        symbol = Asset.BTC,
                        volume = BigDecimal("0.1"),
                        usdAmount = BigDecimal("10.00"),
                        price = BigDecimal("100.00"),
                        source = TradeSource.API_FILL,
                        tradeId = "failed-fill",
                    ).copy(success = false),
                )
                repository.saveTrade(
                    TestFixtures.tradeRecord(
                        timestamp = Instant.parse("2026-01-01T00:00:03Z"),
                        pair = Asset.BTC_USD_PAIR,
                        side = OrderSide.BUY.apiValue,
                        symbol = Asset.BTC,
                        volume = BigDecimal("0.1"),
                        usdAmount = BigDecimal("10.00"),
                        price = BigDecimal("100.00"),
                        source = TradeSource.API_FILL,
                        tradeId = "dry-run-fill",
                    ).copy(dryRun = true),
                )
                krakenService.tradeHistoryTotalCountOverride = 0

                val status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.COMPLETE_NO_BOT_EVIDENCE
                status.reason shouldBe "no positively owned bot fill"
            }
        }

        "manual activity before a locally identified bot fill is excluded from the candidate" {
            runTest {
                val manual = apiTrade(
                    id = "manual",
                    timestamp = Instant.parse("2026-01-01T00:00:00Z"),
                    volume = BigDecimal("0.1"),
                    usdAmount = BigDecimal("10.00"),
                    fee = BigDecimal("0.10"),
                )
                val botTime = Instant.parse("2026-01-02T00:00:00Z")
                val bot = apiTrade(
                    id = "bot",
                    timestamp = botTime,
                    volume = BigDecimal("0.5"),
                    usdAmount = BigDecimal("50.00"),
                    fee = BigDecimal("0.50"),
                )
                repository.saveTrade(localEstimate(botTime, bot))
                repository.saveSnapshot(
                    anchorSnapshot(
                        balances = mapOf(Asset.BTC to BigDecimal("0.6"), Asset.USD to BigDecimal("939.40")),
                        timestamp = Instant.parse("2026-01-03T00:00:00Z"),
                    ),
                )
                krakenService.tradeHistoryTotalCountOverride = 2
                krakenService.tradeHistorySupplier = { _, offset -> listOf(manual, bot).drop(offset ?: 0).take(50) }

                val status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.CONFIRMED
                repository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_EPOCH_MS) shouldBe
                    botTime.toEpochMilli().toString()
                val baselineId = repository
                    .getSyncMetadata(SyncMetadataKeys.INCEPTION_SNAPSHOT_ID)
                    ?.toInt() ?: error("missing baseline id")
                repository.getSnapshotById(baselineId)?.assets?.getValue(Asset.BTC)?.balance
                    ?.shouldBeEqualComparingTo(BigDecimal("0.10"))
                repository.getSnapshotById(baselineId)?.assets?.getValue(Asset.USD)?.balance
                    ?.shouldBeEqualComparingTo(BigDecimal("989.90"))
            }
        }

        "unresolved activity after a bot candidate does not retroactively change its inception" {
            runTest {
                val botTime = Instant.parse("2026-01-02T00:00:00Z")
                val bot = apiTrade(
                    "bot",
                    botTime,
                    volume = BigDecimal("0.5"),
                    usdAmount = BigDecimal("50.00"),
                    fee = BigDecimal("0.50"),
                )
                repository.saveTrade(localEstimate(botTime, bot))
                repository.saveTrade(
                    TestFixtures.tradeRecord(
                        timestamp = botTime.plusSeconds(3600),
                        pair = Asset.BTC_USD_PAIR,
                        side = OrderSide.BUY.apiValue,
                        symbol = Asset.BTC,
                        volume = BigDecimal("0.1"),
                        usdAmount = BigDecimal("10.00"),
                        price = BigDecimal("100.00"),
                        source = TradeSource.LEGACY_UNKNOWN,
                    ),
                )
                repository.saveTrade(
                    apiTrade("failed-after-candidate", botTime.plusSeconds(7200)).copy(success = false),
                )
                repository.saveTrade(
                    apiTrade("dry-run-after-candidate", botTime.plusSeconds(10800)).copy(dryRun = true),
                )
                repository.saveSnapshot(
                    anchorSnapshot(
                        balances = mapOf(Asset.BTC to BigDecimal("0.6"), Asset.USD to BigDecimal("939.50")),
                        timestamp = Instant.parse("2026-01-03T00:00:00Z"),
                    ),
                )
                krakenService.tradeHistoryTotalCountOverride = 1
                krakenService.tradeHistorySupplier = { _, _ -> listOf(bot) }

                val status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.CONFIRMED
                repository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_EPOCH_MS) shouldBe
                    botTime.toEpochMilli().toString()
            }
        }

        "an unresolved legacy trade before a bot fill leaves inception ambiguous" {
            runTest {
                val botTime = Instant.parse("2026-01-02T00:00:00Z")
                repository.saveTrade(
                    TestFixtures.tradeRecord(
                        timestamp = Instant.parse("2026-01-01T00:00:00Z"),
                        pair = Asset.tradingPair(Asset.BTC),
                        side = OrderSide.BUY.name,
                        symbol = Asset.BTC,
                        volume = BigDecimal("0.1"),
                        usdAmount = BigDecimal("10.00"),
                        price = BigDecimal("100.00"),
                        source = TradeSource.LEGACY_UNKNOWN,
                    ),
                )
                val bot = apiTrade(
                    "bot",
                    botTime,
                    volume = BigDecimal("0.5"),
                    usdAmount = BigDecimal("50.00"),
                    fee = BigDecimal("0.50"),
                )
                repository.saveTrade(localEstimate(botTime, bot))
                krakenService.tradeHistoryTotalCountOverride = 1
                krakenService.tradeHistorySupplier = { _, _ -> listOf(bot) }

                val status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.AMBIGUOUS
                status.reason shouldBe "ownership before candidate is unresolved"
                repository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_EPOCH_MS) shouldBe ""
            }
        }

        "an unproven balance contribution after the candidate leaves the baseline ambiguous" {
            runTest {
                val botTime = Instant.parse("2026-01-02T00:00:00Z")
                val bot = apiTrade(
                    "bot",
                    botTime,
                    volume = BigDecimal("0.5"),
                    usdAmount = BigDecimal("50.00"),
                    fee = BigDecimal("0.50"),
                )
                repository.saveTrade(localEstimate(botTime, bot))
                krakenService.seedLedgerEntries(
                    listOf(
                        LedgerEvent(
                            ledgerId = "deposit-1",
                            time = botTime.plusSeconds(12 * 60 * 60L),
                            type = "deposit",
                            asset = Asset.USD,
                            amount = BigDecimal("100.00"),
                            balance = BigDecimal("1049.50"),
                        ),
                    ),
                )
                repository.saveSnapshot(
                    anchorSnapshot(
                        balances = mapOf(Asset.BTC to BigDecimal("0.5"), Asset.USD to BigDecimal("1049.50")),
                        timestamp = Instant.parse("2026-01-03T00:00:00Z"),
                    ),
                )
                krakenService.tradeHistoryTotalCountOverride = 1
                krakenService.tradeHistorySupplier = { _, _ -> listOf(bot) }

                val status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.AMBIGUOUS
                status.reason shouldBe "ledger provenance unresolved: deposit"
                repository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_EPOCH_MS) shouldBe ""
            }
        }

        "inconsistent authoritative ledger balances leave the baseline ambiguous" {
            runTest {
                val botTime = Instant.parse("2026-01-02T00:00:00Z")
                val bot = apiTrade("bot", botTime, volume = BigDecimal("0.5"), usdAmount = BigDecimal("50.00"))
                repository.saveTrade(localEstimate(botTime, bot))
                krakenService.seedLedgerEntries(
                    listOf(
                        LedgerEvent(
                            ledgerId = "reward-1",
                            time = botTime.plusSeconds(3600),
                            type = "staking",
                            asset = Asset.BTC,
                            amount = BigDecimal("0.10"),
                            balance = BigDecimal("0.60"),
                            hasAuthoritativeBalance = true,
                        ),
                        LedgerEvent(
                            ledgerId = "reward-2",
                            time = botTime.plusSeconds(7200),
                            type = "staking",
                            asset = Asset.BTC,
                            amount = BigDecimal("0.10"),
                            balance = BigDecimal("0.90"),
                            hasAuthoritativeBalance = true,
                        ),
                    ),
                )
                repository.saveSnapshot(
                    anchorSnapshot(
                        balances = mapOf(Asset.BTC to BigDecimal("0.70"), Asset.USD to BigDecimal("949.50")),
                        timestamp = Instant.parse("2026-01-03T00:00:00Z"),
                    ),
                )
                krakenService.tradeHistoryTotalCountOverride = 1
                krakenService.tradeHistorySupplier = { _, _ -> listOf(bot) }

                val status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.AMBIGUOUS
                status.reason shouldBe "inconsistent ledger balances"
            }
        }

        "a recovered candidate older than the ordinary seed window gets a durable baseline" {
            runTest {
                val botTime = Instant.parse("2026-01-02T00:00:00Z")
                val bot = apiTrade(
                    "bot",
                    botTime,
                    volume = BigDecimal("0.5"),
                    usdAmount = BigDecimal("50.00"),
                    fee = BigDecimal("0.50"),
                )
                repository.saveTrade(localEstimate(botTime, bot))
                repository.saveSnapshot(
                    anchorSnapshot(
                        balances = mapOf(Asset.BTC to BigDecimal("0.5"), Asset.USD to BigDecimal("949.50")),
                        timestamp = Instant.parse("2026-01-03T00:00:00Z"),
                    ),
                )
                krakenService.tradeHistoryTotalCountOverride = 1
                krakenService.tradeHistorySupplier = { _, _ -> listOf(bot) }

                val status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.CONFIRMED
                repository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_SOURCE) shouldBe
                    InceptionRecoveryService.INCEPTION_SOURCE_AUTO_RECOVERED
                val tradeCallsAfterConfirmation = krakenService.getTradeHistoryCallCount
                newService().recoverOneBoundedRun().status shouldBe InceptionRecoveryStatus.CONFIRMED
                krakenService.getTradeHistoryCallCount shouldBe tradeCallsAfterConfirmation
                val baselineId = repository
                    .getSyncMetadata(SyncMetadataKeys.INCEPTION_SNAPSHOT_ID)
                    ?.toInt() ?: error("missing baseline id")
                val baseline = repository.getSnapshotById(baselineId) ?: error("missing baseline")
                baseline.timestamp shouldBe botTime.minusMillis(1)
                baseline.balancesObservedAt shouldBe null
                baseline.totalValueUSD.shouldBeEqualComparingTo(BigDecimal("1000.00"))
                baseline.assets.getValue(Asset.BTC).balance.shouldBeEqualComparingTo(BigDecimal("0.00"))
                baseline.assets.getValue(Asset.USD).balance.shouldBeEqualComparingTo(BigDecimal("1000.00"))
                repository.getSnapshotById(baselineId)?.let { snapshot ->
                    repository.getSnapshotId(snapshot.timestamp) shouldBe baselineId
                }

                repository.setSyncMetadata(
                    SyncMetadataKeys.INCEPTION_RECOVERY_STATUS,
                    InceptionRecoveryStatus.IN_PROGRESS,
                )
                now = now.plusSeconds(InceptionRecoveryService.RETRY_INTERVAL_SECONDS + 1)
                newService().recoverOneBoundedRun().status shouldBe InceptionRecoveryStatus.CONFIRMED
            }
        }

        "supported contributions, withdrawals, rewards, and trade ledger rows replay exactly" {
            runTest {
                val botTime = Instant.parse("2026-01-02T00:00:00Z")
                val depositTime = botTime.plusSeconds(3600)
                val rewardTime = botTime.plusSeconds(7200)
                val withdrawalTime = botTime.plusSeconds(10800)
                val bot = apiTrade(
                    "bot",
                    botTime,
                    volume = BigDecimal("0.5"),
                    usdAmount = BigDecimal("50.00"),
                    fee = BigDecimal("0.50"),
                )
                repository.saveTrade(localEstimate(botTime, bot))
                krakenService.seedLedgerEntries(
                    listOf(
                        LedgerEvent(
                            ledgerId = "deposit",
                            refid = "deposit-ref",
                            time = depositTime,
                            type = "deposit",
                            asset = Asset.USD,
                            amount = BigDecimal("100.00"),
                            balance = BigDecimal("100.00"),
                            hasAuthoritativeBalance = true,
                        ),
                        LedgerEvent(
                            ledgerId = "reward",
                            time = rewardTime,
                            type = "staking",
                            asset = Asset.BTC,
                            amount = BigDecimal("0.10"),
                            balance = BigDecimal("0.10"),
                            hasAuthoritativeBalance = true,
                        ),
                        LedgerEvent(
                            ledgerId = "same-time-reward",
                            time = rewardTime,
                            type = "staking",
                            asset = Asset.BTC,
                            amount = BigDecimal.ZERO,
                        ),
                        LedgerEvent(
                            ledgerId = "withdrawal",
                            refid = "withdrawal-ref",
                            time = withdrawalTime,
                            type = "withdrawal",
                            asset = Asset.USD,
                            amount = BigDecimal("-20.00"),
                            fee = BigDecimal("0.20"),
                            balance = BigDecimal("79.80"),
                            hasAuthoritativeBalance = true,
                            hasAuthoritativeFee = true,
                        ),
                        LedgerEvent(
                            ledgerId = "trade-ledger",
                            time = botTime.plusSeconds(14400),
                            type = "trade",
                            asset = Asset.BTC,
                            amount = BigDecimal.ZERO,
                            balance = BigDecimal.ZERO,
                        ),
                    ),
                )
                repository.saveSnapshot(
                    anchorSnapshot(
                        balances = mapOf(Asset.BTC to BigDecimal("0.60"), Asset.USD to BigDecimal("1029.30")),
                        timestamp = Instant.parse("2026-01-03T00:00:00Z"),
                    ),
                )
                krakenService.tradeHistoryTotalCountOverride = 1
                krakenService.tradeHistorySupplier = { _, _ -> listOf(bot) }

                val externalResolver = FundingProvenanceResolver { FundingEvidence.EXTERNAL }
                val status = newService(fundingProvenanceResolver = externalResolver).recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.CONFIRMED
                val baselineId = repository
                    .getSyncMetadata(SyncMetadataKeys.INCEPTION_SNAPSHOT_ID)
                    ?.toInt() ?: error("missing baseline id")
                val baseline = repository.getSnapshotById(baselineId) ?: error("missing baseline")
                baseline.assets.getValue(Asset.BTC).balance.shouldBeEqualComparingTo(BigDecimal.ZERO)
                baseline.assets.getValue(Asset.USD).balance.shouldBeEqualComparingTo(BigDecimal("1000.00"))
                baseline.totalValueUSD.shouldBeEqualComparingTo(BigDecimal("1000.00"))
            }
        }

        "a complete card funding group is validated before reverse replay" {
            runTest {
                val botTime = Instant.parse("2026-01-02T00:00:00Z")
                val bot = apiTrade(
                    "bot",
                    botTime,
                    volume = BigDecimal("0.5"),
                    usdAmount = BigDecimal("50.00"),
                    fee = BigDecimal("0.50"),
                )
                repository.saveTrade(localEstimate(botTime, bot))
                krakenService.seedLedgerEntries(
                    listOf(
                        LedgerEvent(
                            ledgerId = "old-card-deposit",
                            refid = "old-card-ref",
                            time = botTime.minusSeconds(3600),
                            type = "deposit",
                            asset = Asset.USD,
                            amount = BigDecimal("10.00"),
                        ),
                        LedgerEvent(
                            ledgerId = "card-deposit",
                            refid = "card-ref",
                            time = botTime.plusSeconds(3600),
                            type = "deposit",
                            asset = Asset.USD,
                            amount = BigDecimal("100.00"),
                        ),
                        LedgerEvent(
                            ledgerId = "card-spend",
                            refid = "card-ref",
                            time = botTime.plusSeconds(3601),
                            type = "spend",
                            asset = Asset.USD,
                            amount = BigDecimal("-100.00"),
                        ),
                        LedgerEvent(
                            ledgerId = "card-receive",
                            refid = "card-ref",
                            time = botTime.plusSeconds(3602),
                            type = "receive",
                            asset = Asset.BTC,
                            amount = BigDecimal("0.50"),
                        ),
                        LedgerEvent(
                            ledgerId = "future-card-deposit",
                            refid = "future-card-ref",
                            time = Instant.parse("2026-01-03T00:01:00Z"),
                            type = "deposit",
                            asset = Asset.USD,
                            amount = BigDecimal("10.00"),
                        ),
                    ),
                )
                repository.saveSnapshot(
                    anchorSnapshot(
                        balances = mapOf(Asset.BTC to BigDecimal("1.00"), Asset.USD to BigDecimal("949.50")),
                        timestamp = Instant.parse("2026-01-03T00:00:00Z"),
                    ).copy(balancesObservedAt = null),
                )
                krakenService.tradeHistoryTotalCountOverride = 1
                krakenService.tradeHistorySupplier = { _, _ -> listOf(bot) }
                val cardResolver = object : FundingProvenanceResolver {
                    override fun resolve(event: LedgerEvent): FundingEvidence = FundingEvidence.EXTERNAL

                    override fun isCardFunding(event: LedgerEvent): Boolean = true
                }

                val status = newService(fundingProvenanceResolver = cardResolver).recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.CONFIRMED
                val baselineId = repository
                    .getSyncMetadata(SyncMetadataKeys.INCEPTION_SNAPSHOT_ID)
                    ?.toInt() ?: error("missing baseline id")
                repository.getSnapshotById(baselineId)?.totalValueUSD
                    ?.shouldBeEqualComparingTo(BigDecimal("1000.00"))
            }
        }

        "a card funding group straddling the balance anchor is evaluated as one group" {
            runTest {
                val botTime = Instant.parse("2026-01-02T00:00:00Z")
                val anchorTime = Instant.parse("2026-01-03T00:00:00Z")
                val bot = apiTrade("bot", botTime)
                repository.saveTrade(localEstimate(botTime, bot))
                krakenService.seedLedgerEntries(
                    listOf(
                        LedgerEvent(
                            ledgerId = "straddle-deposit",
                            refid = "straddle-ref",
                            time = anchorTime.minusSeconds(30),
                            type = "deposit",
                            asset = Asset.USD,
                            amount = BigDecimal("100.00"),
                        ),
                        LedgerEvent(
                            ledgerId = "straddle-spend",
                            refid = "straddle-ref",
                            time = anchorTime.plusSeconds(30),
                            type = "spend",
                            asset = Asset.USD,
                            amount = BigDecimal("-100.00"),
                        ),
                    ),
                )
                repository.saveSnapshot(
                    anchorSnapshot(
                        balances = mapOf(Asset.BTC to BigDecimal("0.01"), Asset.USD to BigDecimal("999.00")),
                        timestamp = anchorTime,
                    ),
                )
                krakenService.tradeHistoryTotalCountOverride = 1
                krakenService.tradeHistorySupplier = { _, _ -> listOf(bot) }
                val cardResolver = object : FundingProvenanceResolver {
                    override fun resolve(event: LedgerEvent): FundingEvidence = FundingEvidence.EXTERNAL

                    override fun isCardFunding(event: LedgerEvent): Boolean = true
                }

                val status = newService(fundingProvenanceResolver = cardResolver).recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.AMBIGUOUS
                status.reason shouldBe "Card deposit missing crypto receive plumbing leg"
            }
        }

        "a funding provenance preparation failure falls back conservatively for non-funding rows" {
            runTest {
                val botTime = Instant.parse("2026-01-02T00:00:00Z")
                val bot = apiTrade("bot", botTime, volume = BigDecimal("0.5"), usdAmount = BigDecimal("50.00"))
                repository.saveTrade(localEstimate(botTime, bot))
                repository.saveSnapshot(
                    anchorSnapshot(
                        balances = mapOf(Asset.BTC to BigDecimal("0.5"), Asset.USD to BigDecimal("949.70")),
                        timestamp = Instant.parse("2026-01-03T00:00:00Z"),
                    ),
                )
                krakenService.tradeHistoryTotalCountOverride = 1
                krakenService.tradeHistorySupplier = { _, _ -> listOf(bot) }
                val failingResolver = object : FundingProvenanceResolver {
                    override fun resolve(event: LedgerEvent): FundingEvidence = FundingEvidence.UNRESOLVED

                    override suspend fun prepare(events: Collection<LedgerEvent>): FundingProvenanceResolver =
                        error("provenance unavailable")
                }

                val status = newService(fundingProvenanceResolver = failingResolver).recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.CONFIRMED
            }
        }

        "funding provenance cancellation is not converted into an ambiguous result" {
            runTest {
                val botTime = Instant.parse("2026-01-02T00:00:00Z")
                val bot = apiTrade("bot", botTime, volume = BigDecimal("0.5"), usdAmount = BigDecimal("50.00"))
                repository.saveTrade(localEstimate(botTime, bot))
                repository.saveSnapshot(
                    anchorSnapshot(
                        balances = mapOf(Asset.BTC to BigDecimal("0.5"), Asset.USD to BigDecimal("949.50")),
                        timestamp = Instant.parse("2026-01-03T00:00:00Z"),
                    ),
                )
                krakenService.tradeHistoryTotalCountOverride = 1
                krakenService.tradeHistorySupplier = { _, _ -> listOf(bot) }
                val cancellingResolver = object : FundingProvenanceResolver {
                    override fun resolve(event: LedgerEvent): FundingEvidence = FundingEvidence.UNRESOLVED

                    override suspend fun prepare(events: Collection<LedgerEvent>): FundingProvenanceResolver =
                        throw CancellationException("cancelled")
                }

                shouldThrow<CancellationException> {
                    newService(fundingProvenanceResolver = cancellingResolver).recoverOneBoundedRun()
                }
                repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_STATUS) shouldBe
                    InceptionRecoveryStatus.IN_PROGRESS
                repository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_EPOCH_MS) shouldBe ""
            }
        }

        "an order intent identity positively attributes a raw API fill" {
            runTest {
                val botTime = Instant.parse("2026-01-02T00:00:00Z")
                val bot = apiTrade("bot", botTime, volume = BigDecimal("0.5"), usdAmount = BigDecimal("50.00"))
                repository.saveSnapshot(
                    anchorSnapshot(
                        balances = mapOf(Asset.BTC to BigDecimal("0.5"), Asset.USD to BigDecimal("949.50")),
                        timestamp = Instant.parse("2026-01-03T00:00:00Z"),
                    ),
                )
                krakenService.tradeHistoryTotalCountOverride = 1
                krakenService.tradeHistorySupplier = { _, _ -> listOf(bot) }
                val orderIntentRepository = mockk<OrderIntentRepository>()
                coEvery {
                    orderIntentRepository.getKnownRebalancerOrderIdentities(any(), any())
                } returns RebalancerOrderIdentities(setOf(bot.orderTxid!!))

                val status = newService(orderIntentRepository = orderIntentRepository).recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.CONFIRMED
                repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_OWNERSHIP_EVIDENCE) shouldBe
                    "order intent"
            }
        }

        "a local client identifier without exchange identifiers still preserves candidate evidence" {
            runTest {
                val botTime = Instant.parse("2026-01-02T00:00:00Z")
                repository.saveTrade(
                    TestFixtures.tradeRecord(
                        timestamp = botTime,
                        pair = Asset.BTC_USD_PAIR,
                        side = OrderSide.BUY.apiValue,
                        symbol = Asset.BTC,
                        volume = BigDecimal("0.5"),
                        usdAmount = BigDecimal("50.00"),
                        price = BigDecimal("100.00"),
                        fee = BigDecimal("0.50"),
                        source = TradeSource.LOCAL_ESTIMATE,
                        clientOrderId = "client-without-exchange-id",
                    ),
                )
                repository.saveTrade(
                    TestFixtures.tradeRecord(
                        timestamp = botTime.plusSeconds(1),
                        pair = Asset.BTC_USD_PAIR,
                        side = OrderSide.BUY.apiValue,
                        symbol = Asset.BTC,
                        volume = BigDecimal.ZERO,
                        usdAmount = BigDecimal.ZERO,
                        price = BigDecimal("100.00"),
                        source = TradeSource.LOCAL_ESTIMATE,
                        orderTxid = " ",
                        clientOrderId = " ",
                    ),
                )
                repository.saveSnapshot(
                    anchorSnapshot(
                        balances = mapOf(Asset.BTC to BigDecimal("0.5"), Asset.USD to BigDecimal("949.50")),
                        timestamp = Instant.parse("2026-01-03T00:00:00Z"),
                    ),
                )
                krakenService.tradeHistoryTotalCountOverride = 0

                val status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.CONFIRMED
                repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_CANDIDATE_TRADE_ID) shouldBe ""
                repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_CANDIDATE_ORDER_TXID) shouldBe ""
                repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_OWNERSHIP_EVIDENCE) shouldBe
                    "local cycle/client"
            }
        }

        "historical trade prices and local-estimate provenance can seed the baseline" {
            runTest {
                config = appConfig(
                    listOf(
                        Allocation(Asset.BTC, 40.0),
                        Allocation(Asset.ETH, 40.0),
                        Allocation(Asset.USD, 20.0),
                    ),
                )
                val botTime = Instant.parse("2026-01-02T00:00:00Z")
                val bot = apiTrade("bot", botTime, volume = BigDecimal("0.5"), usdAmount = BigDecimal("50.00"))
                val ethFill = apiTrade(
                    "eth",
                    botTime.plusSeconds(3600),
                    symbol = Asset.ETH,
                    volume = BigDecimal("0.1"),
                    usdAmount = BigDecimal("10.00"),
                )
                repository.saveTrade(localEstimate(botTime, bot).copy(cycleId = " ", clientOrderId = ""))
                repository.saveTrade(localEstimate(ethFill.timestamp, ethFill))
                repository.saveSnapshot(
                    anchorSnapshot(
                        balances = mapOf(
                            Asset.BTC to BigDecimal("0.5"),
                            Asset.ETH to BigDecimal("0.1"),
                            Asset.USD to BigDecimal("939.40"),
                        ),
                        prices = mapOf(
                            Asset.BTC to BigDecimal("100.00"),
                            Asset.ETH to BigDecimal.ZERO,
                            Asset.USD to BigDecimal.ONE,
                        ),
                        timestamp = Instant.parse("2026-01-03T00:00:00Z"),
                    ),
                )
                krakenService.tradeHistoryTotalCountOverride = 0

                val status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.CONFIRMED
                repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_OWNERSHIP_EVIDENCE) shouldBe
                    "local estimate"
            }
        }

        "historical OHLC fills a missing retained price" {
            runTest {
                config = appConfig(
                    listOf(
                        Allocation(Asset.BTC, 40.0),
                        Allocation(Asset.ETH, 40.0),
                        Allocation(Asset.USD, 20.0),
                    ),
                )
                val botTime = Instant.parse("2026-01-02T00:00:00Z")
                val bot = apiTrade("bot", botTime, volume = BigDecimal("0.5"), usdAmount = BigDecimal("50.00"))
                repository.saveTrade(localEstimate(botTime, bot))
                repository.saveTrade(
                    localEstimate(
                        botTime.plusSeconds(1),
                        apiTrade(
                            "eth-zero-price",
                            botTime.plusSeconds(1),
                            symbol = Asset.ETH,
                            volume = BigDecimal("0.1"),
                            usdAmount = BigDecimal("10.00"),
                            fee = BigDecimal.ZERO,
                            price = BigDecimal.ZERO,
                        ),
                    ),
                )
                val anchor = anchorSnapshot(
                    balances = mapOf(
                        Asset.BTC to BigDecimal("0.5"),
                        Asset.ETH to BigDecimal("0.1"),
                        Asset.USD to BigDecimal("949.70"),
                    ),
                    prices = mapOf(
                        Asset.BTC to BigDecimal("100.00"),
                        Asset.ETH to BigDecimal.ZERO,
                        Asset.USD to BigDecimal.ONE,
                    ),
                    timestamp = Instant.parse("2026-01-03T00:00:00Z"),
                )
                repository.saveSnapshot(
                    anchor.copy(
                        timestamp = botTime.minusSeconds(3600),
                        assets = anchor.assets - Asset.ETH,
                    ),
                )
                repository.saveSnapshot(
                    anchor,
                )
                krakenService.tradeHistoryTotalCountOverride = 1
                krakenService.tradeHistorySupplier = { _, _ -> listOf(bot) }
                krakenService.ohlcSupplier = { pair, interval, _ ->
                    pair shouldBe Asset.ETH_USD_PAIR
                    interval shouldBe 1440
                    listOf(botTime.epochSecond to BigDecimal("200.00"))
                }

                val status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.CONFIRMED
                krakenService.getOHLCCallCount shouldBe 1
            }
        }

        "an OHLC request failure leaves missing historical prices unavailable" {
            runTest {
                config = appConfig(
                    listOf(
                        Allocation(Asset.BTC, 40.0),
                        Allocation(Asset.ETH, 40.0),
                        Allocation(Asset.USD, 20.0),
                    ),
                )
                val botTime = Instant.parse("2026-01-02T00:00:00Z")
                val bot = apiTrade("bot", botTime, volume = BigDecimal("0.5"), usdAmount = BigDecimal("50.00"))
                repository.saveTrade(localEstimate(botTime, bot))
                repository.saveSnapshot(
                    anchorSnapshot(
                        balances = mapOf(
                            Asset.BTC to BigDecimal("0.5"),
                            Asset.ETH to BigDecimal("0.1"),
                            Asset.USD to BigDecimal("949.70"),
                        ),
                        prices = mapOf(
                            Asset.BTC to BigDecimal("100.00"),
                            Asset.ETH to BigDecimal.ZERO,
                            Asset.USD to BigDecimal.ONE,
                        ),
                        timestamp = Instant.parse("2026-01-03T00:00:00Z"),
                    ),
                )
                krakenService.tradeHistoryTotalCountOverride = 1
                krakenService.tradeHistorySupplier = { _, _ -> listOf(bot) }
                krakenService.ohlcSupplier = { _, _, _ -> error("OHLC unavailable") }

                val status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.BASELINE_UNAVAILABLE
                status.reason shouldBe "historical price unavailable"
            }
        }

        "an out-of-range OHLC candle cannot stand in for a historical price" {
            runTest {
                config = appConfig(
                    listOf(
                        Allocation(Asset.BTC, 40.0),
                        Allocation(Asset.ETH, 40.0),
                        Allocation(Asset.USD, 20.0),
                    ),
                )
                val botTime = Instant.parse("2026-01-02T00:00:00Z")
                val bot = apiTrade("bot", botTime, volume = BigDecimal("0.5"), usdAmount = BigDecimal("50.00"))
                repository.saveTrade(localEstimate(botTime, bot))
                repository.saveSnapshot(
                    anchorSnapshot(
                        balances = mapOf(
                            Asset.BTC to BigDecimal("0.5"),
                            Asset.ETH to BigDecimal("0.1"),
                            Asset.USD to BigDecimal("949.70"),
                        ),
                        prices = mapOf(
                            Asset.BTC to BigDecimal("100.00"),
                            Asset.ETH to BigDecimal.ZERO,
                            Asset.USD to BigDecimal.ONE,
                        ),
                        timestamp = Instant.parse("2026-01-03T00:00:00Z"),
                    ),
                )
                krakenService.tradeHistoryTotalCountOverride = 1
                krakenService.tradeHistorySupplier = { _, _ -> listOf(bot) }
                krakenService.ohlcSupplier = { _, _, _ ->
                    listOf(
                        botTime.epochSecond to BigDecimal.ZERO,
                        botTime.minusSeconds(3 * 24 * 60 * 60L).epochSecond to BigDecimal("200.00"),
                    )
                }

                val status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.BASELINE_UNAVAILABLE
                status.reason shouldBe "historical price unavailable"
            }
        }

        "an incomplete card funding group keeps recovery ambiguous" {
            runTest {
                val botTime = Instant.parse("2026-01-02T00:00:00Z")
                val bot = apiTrade("bot", botTime, volume = BigDecimal("0.5"), usdAmount = BigDecimal("50.00"))
                repository.saveTrade(localEstimate(botTime, bot))
                krakenService.seedLedgerEntries(
                    listOf(
                        LedgerEvent(
                            ledgerId = "card-deposit",
                            refid = "card-ref",
                            time = botTime.plusSeconds(3600),
                            type = "deposit",
                            asset = Asset.USD,
                            amount = BigDecimal("100.00"),
                        ),
                        LedgerEvent(
                            ledgerId = "card-spend",
                            refid = "card-ref",
                            time = botTime.plusSeconds(3601),
                            type = "spend",
                            asset = Asset.USD,
                            amount = BigDecimal("-100.00"),
                        ),
                    ),
                )
                repository.saveSnapshot(
                    anchorSnapshot(
                        balances = mapOf(Asset.BTC to BigDecimal("0.5"), Asset.USD to BigDecimal("949.50")),
                        timestamp = Instant.parse("2026-01-03T00:00:00Z"),
                    ),
                )
                krakenService.tradeHistoryTotalCountOverride = 1
                krakenService.tradeHistorySupplier = { _, _ -> listOf(bot) }
                val cardResolver = object : FundingProvenanceResolver {
                    override fun resolve(event: LedgerEvent): FundingEvidence = FundingEvidence.EXTERNAL

                    override fun isCardFunding(event: LedgerEvent): Boolean = true
                }

                val status = newService(fundingProvenanceResolver = cardResolver).recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.AMBIGUOUS
                status.reason shouldBe "Card deposit missing crypto receive plumbing leg"
            }
        }

        "missing historical prices keep the recovered comparison unavailable" {
            runTest {
                config = appConfig(
                    listOf(
                        Allocation(Asset.BTC, 40.0),
                        Allocation(Asset.ETH, 40.0),
                        Allocation(Asset.USD, 20.0),
                    ),
                )
                val botTime = Instant.parse("2026-01-02T00:00:00Z")
                val bot = apiTrade("bot", botTime, volume = BigDecimal("0.5"), usdAmount = BigDecimal("50.00"))
                repository.saveTrade(localEstimate(botTime, bot))
                repository.saveSnapshot(
                    anchorSnapshot(
                        balances = mapOf(
                            Asset.BTC to BigDecimal("0.5"),
                            Asset.ETH to BigDecimal.ZERO,
                            Asset.USD to BigDecimal("949.50"),
                        ),
                        prices = mapOf(
                            Asset.BTC to BigDecimal("100.00"),
                            Asset.ETH to BigDecimal.ZERO,
                            Asset.USD to BigDecimal.ONE,
                        ),
                        timestamp = Instant.parse("2026-01-03T00:00:00Z"),
                    ),
                )
                krakenService.tradeHistoryTotalCountOverride = 1
                krakenService.tradeHistorySupplier = { _, _ -> listOf(bot) }

                val status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.BASELINE_UNAVAILABLE
                status.reason shouldBe "historical price unavailable"
                repository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_EPOCH_MS) shouldBe ""
            }
        }

        "configuration changes clear automatic recovery evidence" {
            runTest {
                repository.setSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_EPOCH_MS, "123")
                repository.setSyncMetadata(
                    SyncMetadataKeys.INCEPTION_RECOVERY_STATUS,
                    InceptionRecoveryStatus.CONFIRMED,
                )
                repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_OFFSET, "completed")
                repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_CONFIG_FINGERPRINT, "old")
                repository.setSyncMetadata(
                    SyncMetadataKeys.INCEPTION_RECOVERY_VERSION,
                    InceptionRecoveryService.CURRENT_RECOVERY_VERSION,
                )

                val changed = newService().prepareForCurrentConfiguration("new")

                changed shouldBe true
                repository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_EPOCH_MS) shouldBe ""
                repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_STATUS) shouldBe ""
                repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_OFFSET) shouldBe ""
                val fingerprint = repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_CONFIG_FINGERPRINT)
                fingerprint?.length shouldBe 64
                fingerprint shouldNotBe "old"
                newService().prepareForCurrentConfiguration("new") shouldBe false
            }
        }

        "account-scope lookup failure still produces a secret-free fingerprint" {
            runTest {
                krakenService.fundingEvidenceScopeSupplier = { error("scope unavailable") }

                newService().prepareForCurrentConfiguration(null) shouldBe true

                val fingerprint = repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_CONFIG_FINGERPRINT)
                fingerprint?.length shouldBe 64
                fingerprint?.all { it in "0123456789abcdef" } shouldBe true
            }
        }

        "recovery reports a failure when the execution session cannot start" {
            runTest {
                coEvery { configService.beginExecutionSession() } throws IllegalStateException("session unavailable")

                val status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.FAILED
                status.reason shouldBe "history request failed"
                krakenService.getTradeHistoryCallCount shouldBe 0
            }
        }

        "configuration preparation does not wait behind a network recovery run" {
            runTest {
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                coEvery { configService.beginExecutionSession() } coAnswers {
                    entered.complete(Unit)
                    release.await()
                }
                val service = newService()
                val recovery = async { service.recoverOneBoundedRun() }

                entered.await()
                service.prepareForCurrentConfiguration(null) shouldBe false
                release.complete(Unit)

                recovery.await().status shouldBe InceptionRecoveryStatus.COMPLETE_NO_BOT_EVIDENCE
            }
        }

        "recovery honors a manual override published after preflight" {
            runTest {
                val manualConfig = config.copy(settings = config.settings.copy(inceptionDate = "2026-01-01"))
                every { configService.getConfig() } returnsMany listOf(config, manualConfig)

                val status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.MANUAL_OVERRIDE
                status.reason shouldBe "explicit inception date"
                krakenService.getTradeHistoryCallCount shouldBe 0
            }
        }

        "recovery continues when a blank override is published after preflight" {
            runTest {
                val blankConfig = config.copy(settings = config.settings.copy(inceptionDate = " "))
                every { configService.getConfig() } returnsMany listOf(config, blankConfig)
                krakenService.tradeHistoryTotalCountOverride = 0

                val status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.COMPLETE_NO_BOT_EVIDENCE
                status.reason shouldBe "no positively owned bot fill"
            }
        }

        "recovery honors simulation published after preflight" {
            runTest {
                val simulationConfig = config.copy(settings = config.settings.copy(simulation = true))
                every { configService.getConfig() } returnsMany listOf(config, simulationConfig)

                val status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.UNAVAILABLE
                status.reason shouldBe "simulation backend"
                krakenService.getTradeHistoryCallCount shouldBe 0
            }
        }

        "recovery honors credentials removed after preflight" {
            runTest {
                val invalidConfig = config.copy(kraken = KrakenCredentials("", ""))
                every { configService.getConfig() } returnsMany listOf(config, invalidConfig)

                val status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.UNAVAILABLE
                status.reason shouldBe "credentials unavailable"
                krakenService.getTradeHistoryCallCount shouldBe 0
            }
        }

        "cancellation never converts an incomplete page into confirmed inception" {
            runTest {
                krakenService.tradeHistorySupplier = { _, _ -> throw CancellationException("cancelled") }

                shouldThrow<CancellationException> { newService().recoverOneBoundedRun() }

                repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_STATUS) shouldBe
                    InceptionRecoveryStatus.IN_PROGRESS
                repository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_EPOCH_MS) shouldBe ""
            }
        }
    }

    private fun newService(
        orderIntentRepository: OrderIntentRepository? = null,
        fundingProvenanceResolver: FundingProvenanceResolver = FundingProvenanceResolver.NONE,
    ): InceptionRecoveryService = InceptionRecoveryService(
        repository = repository,
        ledgerRepository = ledgerRepository,
        krakenService = krakenService,
        configService = configService,
        tradeHistorySyncService = tradeHistorySyncService,
        orderIntentRepository = orderIntentRepository,
        fundingProvenanceResolver = fundingProvenanceResolver,
        nowProvider = { now },
    )

    private fun apiTrade(
        id: String,
        timestamp: Instant,
        symbol: String = Asset.BTC,
        volume: BigDecimal = BigDecimal("0.01"),
        usdAmount: BigDecimal = BigDecimal("1.00"),
        fee: BigDecimal = BigDecimal("0.01"),
        price: BigDecimal = usdAmount.divide(volume),
    ): TradeRecord = TestFixtures.tradeRecord(
        timestamp = timestamp,
        pair = Asset.tradingPair(symbol),
        side = OrderSide.BUY.apiValue,
        symbol = symbol,
        volume = volume,
        usdAmount = usdAmount,
        price = price,
        fee = fee,
        source = TradeSource.API_FILL,
        orderTxid = "order-$id",
        tradeId = "trade-$id",
    )

    private fun localEstimate(timestamp: Instant, apiTrade: TradeRecord): TradeRecord = TestFixtures.tradeRecord(
        timestamp = timestamp,
        pair = apiTrade.pair,
        side = apiTrade.side,
        symbol = apiTrade.symbol,
        volume = apiTrade.volume,
        usdAmount = apiTrade.usdAmount,
        price = apiTrade.price,
        fee = BigDecimal("0.30"),
        slippagePercent = BigDecimal.ZERO,
        expectedPrice = apiTrade.price,
        source = TradeSource.LOCAL_ESTIMATE,
        cycleId = "cycle-${apiTrade.tradeId}",
        clientOrderId = "client-${apiTrade.tradeId}",
        orderTxid = apiTrade.orderTxid,
    )

    private fun anchorSnapshot(
        balances: Map<String, BigDecimal>,
        prices: Map<String, BigDecimal> = balances.keys.associateWith {
            if (it == Asset.USD) BigDecimal.ONE else BigDecimal("100.00")
        },
        timestamp: Instant,
    ): PortfolioSnapshot {
        val assets = config.allocations.associate { allocation ->
            val symbol = allocation.symbol.value
            val balance = balances.getValue(symbol)
            val price = prices.getValue(symbol)
            symbol to TestFixtures.assetSnapshot(
                symbol = symbol,
                balance = balance,
                price = price,
                valueUSD = balance.multiply(price),
                targetPercent = BigDecimal.valueOf(allocation.targetPercent),
            )
        }
        val total = assets.values.fold(BigDecimal.ZERO) { sum, asset -> sum.add(asset.valueUSD) }
        return PortfolioSnapshot(
            timestamp = timestamp,
            totalValueUSD = total.setScale(2),
            assets = assets,
            actions = emptyList(),
            drawdownPercent = BigDecimal.ZERO,
            fiatDeploymentPercent = BigDecimal.ZERO,
            effectiveUsdTargetPercent = config.allocations
                .first { it.symbol.isUsd }
                .targetPercent
                .let(BigDecimal::valueOf),
        )
    }

    private fun appConfig(allocations: List<Allocation>): AppConfig = AppConfig(
        kraken = KrakenCredentials(TestFixtures.TRADE_HISTORY_API_KEY, TestFixtures.TRADE_HISTORY_API_SECRET),
        settings = TestFixtures.settings(dryRun = false, simulation = false),
        allocations = allocations,
    )

    private fun deleteMetadata(vararg keys: String) {
        transaction(database) {
            keys.forEach { key ->
                HistorySyncMetadataTable.deleteWhere { HistorySyncMetadataTable.key eq key }
            }
        }
    }
}
