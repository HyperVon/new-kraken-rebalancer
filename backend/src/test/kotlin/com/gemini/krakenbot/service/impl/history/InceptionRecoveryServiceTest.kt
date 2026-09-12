package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.DatabaseConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.FlowCategory
import com.gemini.krakenbot.model.FundingEvidence
import com.gemini.krakenbot.model.FundingProvenanceResolver
import com.gemini.krakenbot.model.KrakenApiConstants
import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.model.LedgerFlowClassifier
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
import com.gemini.krakenbot.service.InceptionDisplayStatus
import com.gemini.krakenbot.service.InceptionRecoveryStatus
import com.gemini.krakenbot.view.util.ViewText
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
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
    private val trustedScopeGuard = mockk<AccountHistoryScopeGuard>(relaxed = true)
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
        coEvery { trustedScopeGuard.validateAccountScope() } coAnswers {
            when {
                config.settings.simulation -> AccountScopeValidationResult.SIMULATION

                !config.kraken.hasValidCredentials() -> AccountScopeValidationResult.scopeUnavailable(
                    "credentials unavailable",
                )

                else -> AccountScopeValidationResult(
                    status = AccountScopeValidationStatus.VALID,
                    currentScopeDigest = AccountHistoryScopeGuard.digestAccountScope("fake-account"),
                )
            }
        }
        coEvery { trustedScopeGuard.readLocalTrustState() } coAnswers {
            when {
                config.settings.simulation -> AccountScopeValidationResult.SIMULATION

                !config.kraken.hasValidCredentials() -> AccountScopeValidationResult.scopeUnavailable(
                    "credentials unavailable",
                )

                else -> AccountScopeValidationResult(
                    status = AccountScopeValidationStatus.VALID,
                    currentScopeDigest = AccountHistoryScopeGuard.digestAccountScope("fake-account"),
                )
            }
        }

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

                status.status shouldBe InceptionRecoveryStatus.AMBIGUOUS
                status.tradeOffset shouldBe "completed"
                repository.getTradesInRange(Instant.EPOCH, now.plusSeconds(1)).size shouldBe shiftedHistory.size
                krakenService.getTradeHistoryCallCount shouldBe 12
                repository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_EPOCH_MS) shouldBe ""
            }
        }

        "completed empty recovery streams persist explicit zero totals" {
            runTest {
                newService().prepareForCurrentConfiguration(null) shouldBe true
                krakenService.ledgerTotalCountAvailable = true

                val status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.COMPLETE_NO_BOT_EVIDENCE
                repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_STATUS) shouldBe "COMPLETE"
                repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_OFFSET) shouldBe "completed"
                repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_TOTAL) shouldBe "0"
                ledgerRepository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_STATUS) shouldBe "COMPLETE"
                ledgerRepository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_OFFSET) shouldBe "completed"
                ledgerRepository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_TOTAL) shouldBe "0"
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

                status.status shouldBe InceptionRecoveryStatus.AMBIGUOUS
                status.tradeOffset shouldBe "completed"
                status.reason shouldBe "trade ownership is ambiguous"
                krakenService.getTradeHistoryCallCount shouldBe 2
                krakenService.getLedgersCallCount shouldBe 2
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

        "persisted trade total cannot complete a current unknown-total full page" {
            runTest {
                newService().prepareForCurrentConfiguration(null) shouldBe true
                repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_STATUS, "IN_PROGRESS")
                repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_OFFSET, "100")
                repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_TOTAL, "100")
                ledgerRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_STATUS, "COMPLETE")
                krakenService.tradeHistoryTotalCountOverride = 0
                krakenService.tradeHistorySupplier = { _, offset ->
                    when (offset) {
                        50, 100 -> (0 until 50).map { apiTrade("$offset-$it", now.minusSeconds(it.toLong() + 1)) }
                        else -> emptyList()
                    }
                }

                val status = newService().recoverOneBoundedRun()

                status.tradeOffset shouldBe "completed"
                krakenService.getTradeHistoryCallCount shouldBe 3
            }
        }

        "persisted ledger total cannot complete a current unknown-total full page" {
            runTest {
                newService().prepareForCurrentConfiguration(null) shouldBe true
                repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_STATUS, "COMPLETE")
                ledgerRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_STATUS, "IN_PROGRESS")
                ledgerRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_OFFSET, "100")
                ledgerRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_TOTAL, "100")
                krakenService.ledgerTotalCountOverride = 0
                krakenService.ledgerSupplier = { _, offset, _, _ ->
                    when (offset) {
                        50, 100 -> (0 until 50).map {
                            LedgerEvent(
                                ledgerId = "ledger-$offset-$it",
                                time = now.minusSeconds(it.toLong() + 1),
                                type = "staking",
                                asset = Asset.BTC,
                                amount = BigDecimal.ZERO,
                            )
                        }

                        else -> emptyList()
                    }
                }

                val status = newService().recoverOneBoundedRun()

                status.ledgerOffset shouldBe "completed"
                krakenService.getLedgersCallCount shouldBe 3
            }
        }

        "positive ledger totals do not complete from an empty page" {
            runTest {
                newService().prepareForCurrentConfiguration(null) shouldBe true
                repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_STATUS, "COMPLETE")
                repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_OFFSET, "completed")
                ledgerRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_STATUS, "IN_PROGRESS")
                ledgerRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_OFFSET, "0")
                krakenService.ledgerTotalCountOverride = 2
                krakenService.ledgerTotalCountAvailable = true
                krakenService.ledgerSupplier = { _, _, _, _ -> emptyList() }

                val status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.IN_PROGRESS
                status.ledgerOffset shouldBe "0"
                ledgerRepository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_OFFSET) shouldBe
                    "0"
            }
        }

        "positive ledger totals complete on an exact final page" {
            runTest {
                val history = (0 until 60).map { index ->
                    LedgerEvent(
                        ledgerId = "exact-final-ledger-$index",
                        time = now.minusSeconds(index.toLong() + 1),
                        type = KrakenApiConstants.LEDGER_TYPE_STAKING,
                        asset = Asset.BTC,
                        amount = BigDecimal.ZERO,
                    )
                }
                newService().prepareForCurrentConfiguration(null) shouldBe true
                repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_STATUS, "COMPLETE")
                repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_OFFSET, "completed")
                ledgerRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_STATUS, "IN_PROGRESS")
                ledgerRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_OFFSET, "0")
                krakenService.ledgerTotalCountOverride = history.size
                krakenService.ledgerTotalCountAvailable = true
                krakenService.ledgerSupplier = { _, offset, _, _ ->
                    history.drop(offset ?: 0).take(KrakenApiConstants.LEDGER_PAGE_SIZE)
                }

                val status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.COMPLETE_NO_BOT_EVIDENCE
                status.ledgerOffset shouldBe "completed"
                ledgerRepository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_TOTAL) shouldBe
                    history.size.toString()
                krakenService.getLedgersCallCount shouldBe 2
            }
        }

        "unknown positive ledger totals continue past a full page" {
            runTest {
                val history = (0 until KrakenApiConstants.LEDGER_PAGE_SIZE).map { index ->
                    LedgerEvent(
                        ledgerId = "unknown-total-ledger-$index",
                        time = now.minusSeconds(index.toLong() + 1),
                        type = KrakenApiConstants.LEDGER_TYPE_STAKING,
                        asset = Asset.BTC,
                        amount = BigDecimal.ZERO,
                    )
                }
                newService().prepareForCurrentConfiguration(null) shouldBe true
                repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_STATUS, "COMPLETE")
                repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_OFFSET, "completed")
                ledgerRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_STATUS, "IN_PROGRESS")
                ledgerRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_OFFSET, "0")
                krakenService.ledgerTotalCountOverride = history.size
                krakenService.ledgerSupplier = { _, offset, _, _ ->
                    history.drop(offset ?: 0).take(KrakenApiConstants.LEDGER_PAGE_SIZE)
                }

                val status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.COMPLETE_NO_BOT_EVIDENCE
                status.ledgerOffset shouldBe "completed"
                krakenService.getLedgersCallCount shouldBe 2
            }
        }

        "an authoritative ledger total appearing after an unknown page rewinds to page zero" {
            runTest {
                val history = (0 until 90).map { index ->
                    LedgerEvent(
                        ledgerId = "unknown-then-known-ledger-$index",
                        time = now.minusSeconds(index.toLong() + 1),
                        type = KrakenApiConstants.LEDGER_TYPE_STAKING,
                        asset = Asset.BTC,
                        amount = BigDecimal.ZERO,
                    )
                }
                val requestedOffsets = mutableListOf<Int?>()
                newService().prepareForCurrentConfiguration(null) shouldBe true
                repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_STATUS, "COMPLETE")
                repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_OFFSET, "completed")
                ledgerRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_STATUS, "IN_PROGRESS")
                ledgerRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_OFFSET, "0")
                var authoritativeCountSeen = false
                krakenService.ledgerSupplier = { _, offset, _, _ ->
                    requestedOffsets += offset
                    authoritativeCountSeen = authoritativeCountSeen || (offset != null && offset > 0)
                    krakenService.ledgerTotalCountAvailable = authoritativeCountSeen
                    krakenService.ledgerTotalCountOverride = if (krakenService.ledgerTotalCountAvailable) {
                        history.size
                    } else {
                        0
                    }
                    history.drop(offset ?: 0).take(KrakenApiConstants.LEDGER_PAGE_SIZE)
                }

                val status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.COMPLETE_NO_BOT_EVIDENCE
                status.ledgerOffset shouldBe "completed"
                requestedOffsets shouldBe listOf(0, 50, 0, 50)
                ledgerRepository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_TOTAL) shouldBe
                    history.size.toString()
            }
        }

        "positive ledger totals retain the offset for a short non-empty page" {
            runTest {
                newService().prepareForCurrentConfiguration(null) shouldBe true
                repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_STATUS, "COMPLETE")
                repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_OFFSET, "completed")
                ledgerRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_STATUS, "IN_PROGRESS")
                ledgerRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_OFFSET, "100")
                ledgerRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_TOTAL, "60")
                krakenService.ledgerTotalCountOverride = 60
                krakenService.ledgerTotalCountAvailable = true
                krakenService.ledgerSupplier = { _, _, _, _ ->
                    (0 until 9).map { index ->
                        LedgerEvent(
                            ledgerId = "short-ledger-$index",
                            time = now.minusSeconds(index.toLong() + 1),
                            type = KrakenApiConstants.LEDGER_TYPE_STAKING,
                            asset = Asset.BTC,
                            amount = BigDecimal.ZERO,
                        )
                    }
                }

                val status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.IN_PROGRESS
                status.ledgerOffset shouldBe "50"
                krakenService.getLedgersCallCount shouldBe InceptionRecoveryService.MAX_PAGES_PER_RUN
            }
        }

        "explicit zero ledger totals clear a prior positive total" {
            runTest {
                newService().prepareForCurrentConfiguration(null) shouldBe true
                repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_STATUS, "COMPLETE")
                repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_OFFSET, "completed")
                ledgerRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_STATUS, "IN_PROGRESS")
                ledgerRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_OFFSET, "100")
                ledgerRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_TOTAL, "100")
                krakenService.ledgerTotalCountOverride = 0
                krakenService.ledgerTotalCountAvailable = true
                krakenService.ledgerSupplier = { _, _, _, _ -> emptyList() }

                val status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.COMPLETE_NO_BOT_EVIDENCE
                status.ledgerOffset shouldBe "completed"
                ledgerRepository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_TOTAL) shouldBe "0"
                krakenService.getLedgersCallCount shouldBe 2
            }
        }

        "unknown empty ledger pages do not create reusable zero coverage" {
            runTest {
                newService().prepareForCurrentConfiguration(null) shouldBe true
                repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_STATUS, "COMPLETE")
                repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_OFFSET, "completed")
                ledgerRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_STATUS, "IN_PROGRESS")
                ledgerRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_OFFSET, "0")
                krakenService.ledgerSupplier = { _, _, _, _ -> emptyList() }

                val status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.COMPLETE_NO_BOT_EVIDENCE
                status.ledgerOffset shouldBe "completed"
                ledgerRepository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_TOTAL) shouldBe ""
            }
        }

        "malformed ledger envelopes fail closed instead of proving an empty recovery" {
            runTest {
                newService().prepareForCurrentConfiguration(null) shouldBe true
                repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_STATUS, "COMPLETE")
                repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_OFFSET, "completed")
                ledgerRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_STATUS, "IN_PROGRESS")
                ledgerRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_OFFSET, "0")
                krakenService.ledgerTotalCountAvailable = true
                krakenService.ledgerTotalCountOverride = 0
                krakenService.ledgerPageShapeValid = false
                krakenService.ledgerSupplier = { _, _, _, _ -> emptyList() }

                val status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.FAILED
                ledgerRepository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_STATUS) shouldBe "FAILED"
                ledgerRepository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_OFFSET) shouldBe "0"
                ledgerRepository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_TOTAL) shouldBe ""
            }
        }

        "decreased positive ledger totals reconcile after rewinding pagination" {
            runTest {
                val history = (0 until 90).map { index ->
                    LedgerEvent(
                        ledgerId = "decreased-total-ledger-$index",
                        time = now.minusSeconds(index.toLong() + 1),
                        type = KrakenApiConstants.LEDGER_TYPE_STAKING,
                        asset = Asset.BTC,
                        amount = BigDecimal.ZERO,
                    )
                }
                newService().prepareForCurrentConfiguration(null) shouldBe true
                repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_STATUS, "COMPLETE")
                repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_OFFSET, "completed")
                ledgerRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_STATUS, "IN_PROGRESS")
                ledgerRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_OFFSET, "100")
                ledgerRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_TOTAL, "100")
                krakenService.ledgerTotalCountOverride = history.size
                krakenService.ledgerTotalCountAvailable = true
                krakenService.ledgerSupplier = { _, offset, _, _ ->
                    history.drop(offset ?: 0).take(KrakenApiConstants.LEDGER_PAGE_SIZE)
                }

                val status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.COMPLETE_NO_BOT_EVIDENCE
                status.ledgerOffset shouldBe "completed"
                ledgerRepository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_TOTAL) shouldBe
                    history.size.toString()
                krakenService.getLedgersCallCount shouldBe 3
            }
        }

        "positive ledger totals reject oversized and out-of-range pages" {
            runTest {
                newService().prepareForCurrentConfiguration(null) shouldBe true
                repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_STATUS, "COMPLETE")
                repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_OFFSET, "completed")
                ledgerRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_STATUS, "IN_PROGRESS")
                ledgerRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_OFFSET, "0")
                krakenService.ledgerTotalCountOverride = 2
                krakenService.ledgerTotalCountAvailable = true
                krakenService.ledgerSupplier = { _, _, _, _ ->
                    (0 until KrakenApiConstants.LEDGER_PAGE_SIZE).map { index ->
                        LedgerEvent(
                            ledgerId = "oversized-ledger-$index",
                            time = now,
                            type = KrakenApiConstants.LEDGER_TYPE_STAKING,
                            asset = Asset.BTC,
                            amount = BigDecimal.ZERO,
                        )
                    }
                }

                var status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.IN_PROGRESS
                status.ledgerOffset shouldBe "0"

                ledgerRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_OFFSET, "100")
                krakenService.ledgerSupplier = { _, _, _, _ ->
                    listOf(
                        LedgerEvent(
                            ledgerId = "out-of-range-ledger",
                            time = now,
                            type = KrakenApiConstants.LEDGER_TYPE_STAKING,
                            asset = Asset.BTC,
                            amount = BigDecimal.ZERO,
                        ),
                    )
                }
                now = now.plusSeconds(InceptionRecoveryService.RETRY_INTERVAL_SECONDS + 1)

                status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.IN_PROGRESS
                status.ledgerOffset shouldBe "50"
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

                status.status shouldBe InceptionRecoveryStatus.AMBIGUOUS
                status.tradeOffset shouldBe "completed"
                krakenService.getTradeHistoryCallCount shouldBe 2
            }
        }

        "a ledger count shift with missing rows remains at the current page" {
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
                krakenService.ledgerTotalCountAvailable = true
                krakenService.ledgerSupplier = { _, offset, _, _ ->
                    if ((offset ?: 0) == 0) listOf(event) else emptyList()
                }

                val status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.IN_PROGRESS
                status.ledgerOffset shouldBe "0"
                krakenService.getLedgersCallCount shouldBe 4
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

        "recovery distinguishes approved start, simulation, credentials, and retry throttle" {
            runTest {
                config = config.copy(settings = config.settings.copy(inceptionDate = "2026-01-01"))
                val approvedStatus = newService().recoverOneBoundedRun()
                approvedStatus.status shouldBe InceptionRecoveryStatus.BASELINE_UNAVAILABLE
                approvedStatus.reason shouldBe "no retained balance anchor"
                krakenService.getTradeHistoryCallCount shouldBeGreaterThan 0
                val callsAfterApproved = krakenService.getTradeHistoryCallCount

                config = config.copy(settings = config.settings.copy(inceptionDate = null, simulation = true))
                newService().recoverOneBoundedRun().status shouldBe InceptionRecoveryStatus.UNAVAILABLE
                krakenService.getTradeHistoryCallCount shouldBe callsAfterApproved

                config = config.copy(
                    kraken = KrakenCredentials("", ""),
                    settings = config.settings.copy(simulation = false),
                )
                newService().recoverOneBoundedRun().status shouldBe InceptionRecoveryStatus.UNAVAILABLE
                krakenService.getTradeHistoryCallCount shouldBe callsAfterApproved

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
                status.reason shouldBe "baseline is newer than retained anchor"
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

        "recovery replays a complete cross-asset conversion without treating it as capital" {
            runTest {
                val botTime = Instant.parse("2026-01-02T00:00:00Z")
                config = appConfig(
                    listOf(
                        Allocation(Asset.BTC, 40.0),
                        Allocation(Asset.ETH, 40.0),
                        Allocation(Asset.USD, 20.0),
                    ),
                )
                val bot = apiTrade("conversion-bot", botTime)
                repository.saveTrade(localEstimate(botTime, bot))
                repository.saveSnapshot(
                    anchorSnapshot(
                        balances = mapOf(
                            Asset.BTC to BigDecimal("0.40"),
                            Asset.ETH to BigDecimal("1.00"),
                            Asset.USD to BigDecimal("999.00"),
                        ),
                        timestamp = Instant.parse("2026-01-03T00:00:00Z"),
                    ),
                )
                krakenService.tradeHistoryTotalCountOverride = 1
                krakenService.tradeHistorySupplier = { _, _ -> listOf(bot) }
                krakenService.ohlcSupplier = { _, _, _ ->
                    listOf(botTime.minusSeconds(901).epochSecond to BigDecimal("100.00"))
                }
                krakenService.seedLedgerEntries(
                    listOf(
                        LedgerEvent(
                            ledgerId = "conversion-source",
                            refid = "CONVERSION-RECOVERY",
                            time = botTime.plusSeconds(3600),
                            type = KrakenApiConstants.LEDGER_TYPE_CONVERSION,
                            asset = Asset.BTC,
                            amount = BigDecimal("-0.10"),
                            fee = BigDecimal("0.01"),
                            balance = BigDecimal("0.40"),
                            hasAuthoritativeBalance = true,
                            hasAuthoritativeFee = true,
                        ),
                        LedgerEvent(
                            ledgerId = "conversion-destination",
                            refid = "CONVERSION-RECOVERY",
                            time = botTime.plusSeconds(3600),
                            type = KrakenApiConstants.LEDGER_TYPE_CONVERSION,
                            asset = Asset.ETH,
                            amount = BigDecimal("1.00"),
                            fee = BigDecimal("0.02"),
                            balance = BigDecimal("1.00"),
                            hasAuthoritativeBalance = true,
                            hasAuthoritativeFee = true,
                        ),
                        LedgerEvent(
                            ledgerId = "untracked-conversion-source",
                            refid = "UNTRACKED-CONVERSION-RECOVERY",
                            time = botTime.plusSeconds(3600),
                            type = KrakenApiConstants.LEDGER_TYPE_CONVERSION,
                            asset = Asset.USDC,
                            amount = BigDecimal("-1.00"),
                            balance = BigDecimal.ZERO,
                            hasAuthoritativeBalance = true,
                            hasAuthoritativeFee = true,
                        ),
                        LedgerEvent(
                            ledgerId = "untracked-conversion-destination",
                            refid = "UNTRACKED-CONVERSION-RECOVERY",
                            time = botTime.plusSeconds(3600),
                            type = KrakenApiConstants.LEDGER_TYPE_CONVERSION,
                            asset = "EUR",
                            amount = BigDecimal("1.00"),
                            balance = BigDecimal("1.00"),
                            hasAuthoritativeBalance = true,
                            hasAuthoritativeFee = true,
                        ),
                    ),
                )

                val status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.CONFIRMED
                val baselineId = repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_SNAPSHOT_ID)
                    ?.toInt() ?: error("missing baseline id")
                val baseline = repository.getSnapshotById(baselineId) ?: error("missing baseline")
                baseline.assets.getValue(Asset.BTC).balance.shouldBeEqualComparingTo(BigDecimal("0.50"))
                baseline.assets.getValue(Asset.ETH).balance.shouldBeEqualComparingTo(BigDecimal("0.02"))
                baseline.assets.getValue(Asset.USD).balance.shouldBeEqualComparingTo(BigDecimal("1000.01"))
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

        "reverse accounting applies only the Spot-facing internal transfer leg" {
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
                        balances = mapOf(Asset.BTC to BigDecimal("0.4"), Asset.USD to BigDecimal("1059.40")),
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
                        LedgerEvent(
                            ledgerId = "internal-wallet-futures-debit",
                            time = botTime.plusSeconds(1800),
                            type = KrakenApiConstants.LEDGER_TYPE_TRANSFER,
                            subtype = "spotfromfutures",
                            asset = Asset.USD,
                            amount = BigDecimal("-100.00"),
                            balance = BigDecimal.ZERO,
                            refid = "internal-wallet-transfer",
                            hasAuthoritativeBalance = true,
                        ),
                        LedgerEvent(
                            ledgerId = "internal-wallet-spot-credit",
                            time = botTime.plusSeconds(1800),
                            type = KrakenApiConstants.LEDGER_TYPE_TRANSFER,
                            subtype = "spotfromfutures",
                            asset = Asset.USD,
                            amount = BigDecimal("100.00"),
                            balance = BigDecimal("1049.70"),
                            refid = "internal-wallet-transfer",
                            hasAuthoritativeBalance = true,
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

        "approved-start replay restores a Spot debit for Spot-to-staking" {
            runTest {
                val baseline = recoverApprovedInternalTransferBaseline(
                    asset = Asset.SOL,
                    allocations = listOf(Allocation(Asset.SOL, 50.0), Allocation(Asset.USD, 50.0)),
                    anchorBalances = mapOf(Asset.SOL to BigDecimal("6"), Asset.USD to BigDecimal("100")),
                    subtype = "spottostaking",
                    expectedBaseline = BigDecimal("10"),
                    legs = listOf(
                        TransferLeg("staking-credit", "4", "4"),
                        TransferLeg("spot-debit", "-4", "6"),
                    ),
                )

                baseline.assets.getValue(Asset.SOL).balance shouldBeEqualComparingTo BigDecimal("10")
                baseline.drawdownPercent shouldBeEqualComparingTo BigDecimal.ZERO
                baseline.fiatDeploymentPercent shouldBeEqualComparingTo BigDecimal.ZERO
            }
        }

        "approved-start replay removes a Spot credit for staking-to-Spot" {
            runTest {
                val baseline = recoverApprovedInternalTransferBaseline(
                    asset = Asset.SOL,
                    allocations = listOf(Allocation(Asset.SOL, 50.0), Allocation(Asset.USD, 50.0)),
                    anchorBalances = mapOf(Asset.SOL to BigDecimal("10"), Asset.USD to BigDecimal("100")),
                    subtype = "stakingtospot",
                    expectedBaseline = BigDecimal("6"),
                    legs = listOf(
                        TransferLeg("spot-credit", "4", "10"),
                        TransferLeg("staking-debit", "-4", "0"),
                    ),
                )

                baseline.assets.getValue(Asset.SOL).balance shouldBeEqualComparingTo BigDecimal("6")
            }
        }

        "approved-start replay removes a Futures-to-Spot credit from the Spot baseline" {
            runTest {
                val baseline = recoverApprovedInternalTransferBaseline(
                    asset = Asset.USD,
                    allocations = listOf(Allocation(Asset.USD, 100.0)),
                    anchorBalances = mapOf(Asset.USD to BigDecimal("1100")),
                    subtype = "spotfromfutures",
                    expectedBaseline = BigDecimal("1000"),
                    legs = listOf(
                        TransferLeg("spot-credit", "100", "1100"),
                        TransferLeg("futures-debit", "-100", "0"),
                    ),
                )

                baseline.assets.getValue(Asset.USD).balance shouldBeEqualComparingTo BigDecimal("1000")
            }
        }

        "approved-start replay restores a Spot debit for Spot-to-Futures" {
            runTest {
                val baseline = recoverApprovedInternalTransferBaseline(
                    asset = Asset.USD,
                    allocations = listOf(Allocation(Asset.USD, 100.0)),
                    anchorBalances = mapOf(Asset.USD to BigDecimal("1000")),
                    subtype = "spottofutures",
                    expectedBaseline = BigDecimal("1100"),
                    legs = listOf(
                        TransferLeg("futures-credit", "100", "100"),
                        TransferLeg("spot-debit", "-100", "1000"),
                    ),
                )

                baseline.assets.getValue(Asset.USD).balance shouldBeEqualComparingTo BigDecimal("1100")
            }
        }

        "approved-start replay does not double-apply a same-scope Spot transfer" {
            runTest {
                val baseline = recoverApprovedInternalTransferBaseline(
                    asset = Asset.SOL,
                    allocations = listOf(Allocation(Asset.SOL, 50.0), Allocation(Asset.USD, 50.0)),
                    anchorBalances = mapOf(Asset.SOL to BigDecimal("6"), Asset.USD to BigDecimal("100")),
                    subtype = "spottospot",
                    expectedBaseline = BigDecimal("6"),
                    legs = listOf(
                        TransferLeg("spot-debit", "-4", "2", secondsAfterStart = 60),
                        TransferLeg("spot-credit", "4", "6", secondsAfterStart = 61),
                    ),
                )

                baseline.assets.getValue(Asset.SOL).balance shouldBeEqualComparingTo BigDecimal("6")
            }
        }

        "approved-start replay excludes a staking-wallet reward from Spot" {
            runTest {
                val requestedStart = Instant.parse("2026-01-02T00:00:00Z")
                val stakingReward = LedgerEvent(
                    ledgerId = "staking-reward",
                    time = requestedStart.plusSeconds(90),
                    type = KrakenApiConstants.LEDGER_TYPE_STAKING,
                    asset = Asset.SOL,
                    amount = BigDecimal("0.1"),
                    balance = BigDecimal("4.1"),
                    hasAuthoritativeBalance = true,
                )
                val events = listOf(
                    LedgerEvent(
                        ledgerId = "spot-debit",
                        refid = "spot-to-staking-reward",
                        time = requestedStart.plusSeconds(60),
                        type = KrakenApiConstants.LEDGER_TYPE_TRANSFER,
                        subtype = "spottostaking",
                        asset = Asset.SOL,
                        amount = BigDecimal("-4"),
                        balance = BigDecimal("6"),
                        hasAuthoritativeBalance = true,
                    ),
                    LedgerEvent(
                        ledgerId = "staking-credit",
                        refid = "spot-to-staking-reward",
                        time = requestedStart.plusSeconds(60),
                        type = KrakenApiConstants.LEDGER_TYPE_TRANSFER,
                        subtype = "spottostaking",
                        asset = Asset.SOL,
                        amount = BigDecimal("4"),
                        balance = BigDecimal("4"),
                        hasAuthoritativeBalance = true,
                    ),
                    stakingReward,
                )

                val baseline = recoverApprovedScopedLedgerBaseline(
                    asset = Asset.SOL,
                    allocations = listOf(Allocation(Asset.SOL, 50.0), Allocation(Asset.USD, 50.0)),
                    anchorBalances = mapOf(Asset.SOL to BigDecimal("6"), Asset.USD to BigDecimal("100")),
                    events = events,
                    expectedBaseline = BigDecimal("10"),
                    expectedScopes = mapOf(
                        "spot-debit" to AuthoritativeLedgerBalanceValidator.LedgerWalletScope.SPOT,
                        "staking-credit" to AuthoritativeLedgerBalanceValidator.LedgerWalletScope.STAKING,
                        "staking-reward" to AuthoritativeLedgerBalanceValidator.LedgerWalletScope.STAKING,
                    ),
                )

                baseline.assets.getValue(Asset.SOL).balance shouldBeEqualComparingTo BigDecimal("10")
                LedgerFlowClassifier.classifyAll(events).getValue(stakingReward.ledgerId) shouldBe
                    FlowCategory.EXTERNAL_BALANCE
                LedgerEvent.isRewardEvent(stakingReward) shouldBe true
            }
        }

        "approved-start replay applies a staking row resolved to Spot" {
            runTest {
                val requestedStart = Instant.parse("2026-01-02T00:00:00Z")
                val stakingRow = LedgerEvent(
                    ledgerId = "spot-scoped-staking",
                    time = requestedStart.plusSeconds(60),
                    type = KrakenApiConstants.LEDGER_TYPE_STAKING,
                    asset = Asset.SOL,
                    amount = BigDecimal("0.1"),
                    balance = BigDecimal("10.1"),
                    hasAuthoritativeBalance = true,
                )
                val events = listOf(
                    LedgerEvent(
                        ledgerId = "spot-seed",
                        time = requestedStart.plusSeconds(30),
                        type = KrakenApiConstants.LEDGER_TYPE_RECEIVE,
                        asset = Asset.SOL,
                        amount = BigDecimal.ZERO,
                        balance = BigDecimal("10"),
                        hasAuthoritativeBalance = true,
                    ),
                    stakingRow,
                )

                val baseline = recoverApprovedScopedLedgerBaseline(
                    asset = Asset.SOL,
                    allocations = listOf(Allocation(Asset.SOL, 50.0), Allocation(Asset.USD, 50.0)),
                    anchorBalances = mapOf(Asset.SOL to BigDecimal("10.1"), Asset.USD to BigDecimal("100")),
                    events = events,
                    expectedBaseline = BigDecimal("10"),
                    expectedScopes = mapOf(
                        "spot-scoped-staking" to AuthoritativeLedgerBalanceValidator.LedgerWalletScope.SPOT,
                    ),
                )

                baseline.assets.getValue(Asset.SOL).balance shouldBeEqualComparingTo BigDecimal("10")
                LedgerFlowClassifier.classifyAll(events).getValue(stakingRow.ledgerId) shouldBe
                    FlowCategory.EXTERNAL_BALANCE
            }
        }

        "approved-start replay excludes a Futures-scoped external balance row from Spot" {
            runTest {
                val requestedStart = Instant.parse("2026-01-02T00:00:00Z")
                val futuresSweep = LedgerEvent(
                    ledgerId = "futures-dust-sweep",
                    time = requestedStart.plusSeconds(90),
                    type = KrakenApiConstants.LEDGER_TYPE_SPEND,
                    subtype = "dustsweeping",
                    asset = Asset.USD,
                    amount = BigDecimal("-1"),
                    balance = BigDecimal.ZERO,
                    hasAuthoritativeBalance = true,
                )
                val events = listOf(
                    LedgerEvent(
                        ledgerId = "spot-seed",
                        time = requestedStart.plusSeconds(30),
                        type = KrakenApiConstants.LEDGER_TYPE_RECEIVE,
                        asset = Asset.USD,
                        amount = BigDecimal.ZERO,
                        balance = BigDecimal("10"),
                        hasAuthoritativeBalance = true,
                    ),
                    LedgerEvent(
                        ledgerId = "spot-to-futures-debit",
                        refid = "spot-to-futures-sweep",
                        time = requestedStart.plusSeconds(60),
                        type = KrakenApiConstants.LEDGER_TYPE_TRANSFER,
                        subtype = "spottofutures",
                        asset = Asset.USD,
                        amount = BigDecimal("-1"),
                        balance = BigDecimal("9"),
                        hasAuthoritativeBalance = true,
                    ),
                    LedgerEvent(
                        ledgerId = "spot-to-futures-credit",
                        refid = "spot-to-futures-sweep",
                        time = requestedStart.plusSeconds(60),
                        type = KrakenApiConstants.LEDGER_TYPE_TRANSFER,
                        subtype = "spottofutures",
                        asset = Asset.USD,
                        amount = BigDecimal("1"),
                        balance = BigDecimal("1"),
                        hasAuthoritativeBalance = true,
                    ),
                    futuresSweep,
                )

                val baseline = recoverApprovedScopedLedgerBaseline(
                    asset = Asset.USD,
                    allocations = listOf(Allocation(Asset.USD, 100.0)),
                    anchorBalances = mapOf(Asset.USD to BigDecimal("9")),
                    events = events,
                    expectedBaseline = BigDecimal("10"),
                    expectedScopes = mapOf(
                        "spot-to-futures-debit" to AuthoritativeLedgerBalanceValidator.LedgerWalletScope.SPOT,
                        "spot-to-futures-credit" to AuthoritativeLedgerBalanceValidator.LedgerWalletScope.FUTURES,
                        "futures-dust-sweep" to AuthoritativeLedgerBalanceValidator.LedgerWalletScope.FUTURES,
                    ),
                )

                baseline.assets.getValue(Asset.USD).balance shouldBeEqualComparingTo BigDecimal("10")
            }
        }

        "approved-start replay excludes an opaque staking row from Spot" {
            runTest {
                val requestedStart = Instant.parse("2026-01-02T00:00:00Z")
                val opaqueStaking = LedgerEvent(
                    ledgerId = "opaque-staking-reward",
                    time = requestedStart.plusSeconds(60),
                    type = KrakenApiConstants.LEDGER_TYPE_STAKING,
                    asset = Asset.SOL,
                    amount = BigDecimal("0.1"),
                    balance = BigDecimal("0.1"),
                    hasAuthoritativeBalance = true,
                )
                val baseline = recoverApprovedScopedLedgerBaseline(
                    asset = Asset.SOL,
                    allocations = listOf(Allocation(Asset.SOL, 50.0), Allocation(Asset.USD, 50.0)),
                    anchorBalances = mapOf(Asset.SOL to BigDecimal("10"), Asset.USD to BigDecimal("100")),
                    events = listOf(opaqueStaking),
                    expectedBaseline = BigDecimal("10"),
                    expectedScopes = mapOf(
                        "opaque-staking-reward" to AuthoritativeLedgerBalanceValidator.LedgerWalletScope.OPAQUE_STAKING,
                    ),
                )

                baseline.assets.getValue(Asset.SOL).balance shouldBeEqualComparingTo BigDecimal("10")
            }
        }

        "approved-start replay ignores an opaque Earn wallet marker" {
            runTest {
                val requestedStart = Instant.parse("2026-01-02T00:00:00Z")
                config = appConfig(listOf(Allocation(Asset.USD, 100.0))).copy(
                    settings = config.settings.copy(inceptionDate = requestedStart.toString()),
                )
                repository.saveSnapshot(
                    anchorSnapshot(
                        balances = mapOf(Asset.USD to BigDecimal("100")),
                        timestamp = requestedStart.plusSeconds(120),
                    ),
                )
                krakenService.tradeHistoryTotalCountOverride = 0
                krakenService.tradeHistorySupplier = { _, _ -> emptyList() }
                krakenService.seedLedgerEntries(
                    listOf(
                        LedgerEvent(
                            ledgerId = "opaque-earn-marker",
                            time = requestedStart.plusSeconds(60),
                            type = KrakenApiConstants.LEDGER_TYPE_EARN,
                            subtype = "allocation",
                            asset = Asset.USD,
                            amount = BigDecimal("5"),
                            balance = BigDecimal.ZERO,
                            hasAuthoritativeBalance = false,
                        ),
                    ),
                )

                val status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.CONFIRMED
                val baselineId = requireNotNull(
                    repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_APPROVED_BASELINE_SNAPSHOT_ID)
                        ?.toIntOrNull(),
                )
                val baseline = requireNotNull(repository.getSnapshotById(baselineId))
                baseline.assets.getValue(Asset.USD).balance shouldBeEqualComparingTo BigDecimal("100")
            }
        }

        "approved-start replay fails closed for an unresolved non-authoritative balance change" {
            runTest {
                val requestedStart = Instant.parse("2026-01-02T00:00:00Z")
                config = appConfig(listOf(Allocation(Asset.USD, 100.0))).copy(
                    settings = config.settings.copy(inceptionDate = requestedStart.toString()),
                )
                repository.saveSnapshot(
                    anchorSnapshot(
                        balances = mapOf(Asset.USD to BigDecimal("1")),
                        timestamp = requestedStart.plusSeconds(120),
                    ),
                )
                krakenService.tradeHistoryTotalCountOverride = 0
                krakenService.tradeHistorySupplier = { _, _ -> emptyList() }
                krakenService.seedLedgerEntries(
                    listOf(
                        LedgerEvent(
                            ledgerId = "spot-seed",
                            time = requestedStart.plusSeconds(1),
                            type = KrakenApiConstants.LEDGER_TYPE_RECEIVE,
                            asset = Asset.USD,
                            amount = BigDecimal.ONE,
                            balance = BigDecimal.ONE,
                            hasAuthoritativeBalance = true,
                        ),
                        LedgerEvent(
                            ledgerId = "opaque-staking-seed",
                            time = requestedStart.plusSeconds(30),
                            type = KrakenApiConstants.LEDGER_TYPE_STAKING,
                            asset = Asset.USD,
                            amount = BigDecimal.ONE,
                            balance = BigDecimal.ONE,
                            hasAuthoritativeBalance = true,
                        ),
                        LedgerEvent(
                            ledgerId = "unresolved-staking-change",
                            time = requestedStart.plusSeconds(60),
                            type = KrakenApiConstants.LEDGER_TYPE_STAKING,
                            asset = Asset.USD,
                            amount = BigDecimal("0.1"),
                            hasAuthoritativeBalance = false,
                        ),
                        LedgerEvent(
                            ledgerId = "spot-checkpoint",
                            time = requestedStart.plusSeconds(90),
                            type = KrakenApiConstants.LEDGER_TYPE_RECEIVE,
                            asset = Asset.USD,
                            amount = BigDecimal.ZERO,
                            balance = BigDecimal.ONE,
                            hasAuthoritativeBalance = true,
                        ),
                    ),
                )

                val status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.AMBIGUOUS
                status.reason shouldBe "ledger changed tracked universe"
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

        "recovery rejects nonzero trades outside the configured universe without an authoritative balance" {
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
                status.reason shouldBe "no authoritative balance for historical asset ETH"
            }
        }

        "historical-only trades replay with their real quote asset and remain untargeted" {
            runTest {
                val botTime = Instant.parse("2026-01-02T00:00:00Z")
                val bot = apiTrade("bot", botTime)
                repository.saveTrade(localEstimate(botTime, bot))
                repository.saveTrade(
                    TestFixtures.tradeRecord(
                        timestamp = botTime.plusSeconds(1),
                        pair = "ATOMUSDT",
                        side = OrderSide.BUY.apiValue,
                        symbol = "ATOM",
                        volume = BigDecimal("0.5"),
                        usdAmount = BigDecimal("999.00"),
                        price = BigDecimal("2.00"),
                        fee = BigDecimal.ZERO,
                        source = TradeSource.LOCAL_ESTIMATE,
                        cycleId = "atom-buy-cycle",
                        orderTxid = "atom-buy-order",
                        tradeId = "atom-buy-trade",
                    ),
                )
                repository.saveTrade(
                    TestFixtures.tradeRecord(
                        timestamp = botTime.plusSeconds(2),
                        pair = "ATOMUSDT",
                        side = OrderSide.SELL.apiValue,
                        symbol = "ATOM",
                        volume = BigDecimal("0.5"),
                        usdAmount = BigDecimal("999.00"),
                        price = BigDecimal("2.00"),
                        fee = BigDecimal.ZERO,
                        source = TradeSource.LOCAL_ESTIMATE,
                        cycleId = "atom-sell-cycle",
                        orderTxid = "atom-sell-order",
                        tradeId = "atom-sell-trade",
                    ),
                )
                ledgerRepository.saveLedgers(
                    listOf(
                        LedgerEvent(
                            ledgerId = "seed-atom",
                            time = Instant.parse("2025-12-31T00:00:00Z"),
                            type = KrakenApiConstants.LEDGER_TYPE_ADJUSTMENT,
                            asset = "ATOM",
                            amount = BigDecimal.ZERO,
                            balance = BigDecimal.ZERO,
                            hasAuthoritativeBalance = true,
                        ),
                        LedgerEvent(
                            ledgerId = "seed-usdt",
                            time = Instant.parse("2025-12-31T00:00:00Z"),
                            type = KrakenApiConstants.LEDGER_TYPE_ADJUSTMENT,
                            asset = "USDT",
                            amount = BigDecimal.ZERO,
                            balance = BigDecimal.ZERO,
                            hasAuthoritativeBalance = true,
                        ),
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

                status.status shouldBe InceptionRecoveryStatus.CONFIRMED
                val baselineId = repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_SNAPSHOT_ID)
                    ?.toInt() ?: error("baseline snapshot id is missing")
                val baseline = repository.getSnapshotById(baselineId)
                baseline.shouldNotBeNull()
                baseline.totalValueUSD.shouldBeEqualComparingTo(BigDecimal("1000.01"))
                baseline.assets.getValue(Asset.USD).balance.shouldBeEqualComparingTo(BigDecimal("1000.01"))
                baseline.assets.getValue("ATOM").balance.shouldBeEqualComparingTo(BigDecimal.ZERO)
                baseline.assets.getValue("ATOM").targetPercent.shouldBeEqualComparingTo(BigDecimal.ZERO)
                baseline.assets.getValue("USDT").balance.shouldBeEqualComparingTo(BigDecimal.ZERO)
                baseline.assets.getValue("USDT").targetPercent.shouldBeEqualComparingTo(BigDecimal.ZERO)
            }
        }

        "production-shaped BTC fills with base-denominated fees reconstruct exact balances" {
            runTest {
                val botTime = Instant.parse("2026-01-02T00:00:00Z")
                val bot = apiTrade("bot", botTime)
                repository.saveTrade(localEstimate(botTime, bot))

                val fills = listOf(
                    TestFixtures.tradeRecord(
                        timestamp = botTime.plusSeconds(1),
                        pair = Asset.BTC_USD_PAIR,
                        side = OrderSide.BUY.apiValue,
                        symbol = Asset.BTC,
                        volume = BigDecimal("0.00703085"),
                        usdAmount = BigDecimal("461.14"),
                        price = BigDecimal("65588"),
                        fee = BigDecimal("0.9223"),
                        source = TradeSource.LOCAL_ESTIMATE,
                        cycleId = "btc-fill-1218-cycle",
                        orderTxid = "btc-fill-1218-order",
                        tradeId = "btc-fill-1218",
                    ),
                    TestFixtures.tradeRecord(
                        timestamp = botTime.plusSeconds(2),
                        pair = Asset.BTC_USD_PAIR,
                        side = OrderSide.BUY.apiValue,
                        symbol = Asset.BTC,
                        volume = BigDecimal("0.00456718"),
                        usdAmount = BigDecimal("354.87"),
                        price = BigDecimal("77700"),
                        fee = BigDecimal("0.7097"),
                        source = TradeSource.LOCAL_ESTIMATE,
                        cycleId = "btc-fill-2997-cycle",
                        orderTxid = "btc-fill-2997-order",
                        tradeId = "btc-fill-2997",
                    ),
                    TestFixtures.tradeRecord(
                        timestamp = botTime.plusSeconds(3),
                        pair = Asset.BTC_USD_PAIR,
                        side = OrderSide.BUY.apiValue,
                        symbol = Asset.BTC,
                        volume = BigDecimal("0.00022827"),
                        usdAmount = BigDecimal("19.91"),
                        price = BigDecimal("87218.7"),
                        fee = BigDecimal("0.0398"),
                        source = TradeSource.LOCAL_ESTIMATE,
                        cycleId = "btc-fill-4352-cycle",
                        orderTxid = "btc-fill-4352-order",
                        tradeId = "btc-fill-4352",
                    ),
                    TestFixtures.tradeRecord(
                        timestamp = botTime.plusSeconds(4),
                        pair = Asset.BTC_USD_PAIR,
                        side = OrderSide.BUY.apiValue,
                        symbol = Asset.BTC,
                        volume = BigDecimal("0.00400203"),
                        usdAmount = BigDecimal("361.56"),
                        price = BigDecimal("90343.4"),
                        fee = BigDecimal("1.2655"),
                        source = TradeSource.LOCAL_ESTIMATE,
                        cycleId = "btc-fill-4440-cycle",
                        orderTxid = "btc-fill-4440-order",
                        tradeId = "btc-fill-4440",
                    ),
                    TestFixtures.tradeRecord(
                        timestamp = botTime.plusSeconds(5),
                        pair = Asset.BTC_USD_PAIR,
                        side = OrderSide.SELL.apiValue,
                        symbol = Asset.BTC,
                        volume = BigDecimal("0.00223943"),
                        usdAmount = BigDecimal("200"),
                        price = BigDecimal("89308.6"),
                        fee = BigDecimal("0.7"),
                        source = TradeSource.LOCAL_ESTIMATE,
                        cycleId = "btc-fill-4532-cycle",
                        orderTxid = "btc-fill-4532-order",
                        tradeId = "btc-fill-4532",
                    ),
                )
                fills.forEach { repository.saveTrade(it) }

                ledgerRepository.saveLedgers(
                    listOf(
                        LedgerEvent(
                            ledgerId = "btc-fill-1218-base",
                            refid = "btc-fill-1218",
                            time = botTime.plusSeconds(1),
                            type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                            asset = Asset.BTC,
                            amount = BigDecimal("0.00703085"),
                            fee = BigDecimal("0.00001406"),
                            balance = BigDecimal("0.01701679"),
                            hasAuthoritativeBalance = true,
                        ),
                        LedgerEvent(
                            ledgerId = "btc-fill-1218-quote",
                            refid = "btc-fill-1218",
                            time = botTime.plusSeconds(1),
                            type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                            asset = Asset.USD,
                            amount = BigDecimal("-461.14"),
                            balance = BigDecimal("1000.00"),
                            hasAuthoritativeBalance = true,
                        ),
                        LedgerEvent(
                            ledgerId = "btc-fill-2997-base",
                            refid = "btc-fill-2997",
                            time = botTime.plusSeconds(2),
                            type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                            asset = Asset.BTC,
                            amount = BigDecimal("0.00456718"),
                            fee = BigDecimal("0.00000913"),
                            balance = BigDecimal("0.02157484"),
                            hasAuthoritativeBalance = true,
                        ),
                        LedgerEvent(
                            ledgerId = "btc-fill-2997-quote",
                            refid = "btc-fill-2997",
                            time = botTime.plusSeconds(2),
                            type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                            asset = Asset.USD,
                            amount = BigDecimal("-354.87"),
                            balance = BigDecimal("645.13"),
                            hasAuthoritativeBalance = true,
                        ),
                        LedgerEvent(
                            ledgerId = "btc-fill-4352-base",
                            refid = "btc-fill-4352",
                            time = botTime.plusSeconds(3),
                            type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                            asset = Asset.BTC,
                            amount = BigDecimal("0.00022827"),
                            fee = BigDecimal("0.00000046"),
                            balance = BigDecimal("0.02180265"),
                            hasAuthoritativeBalance = true,
                        ),
                        LedgerEvent(
                            ledgerId = "btc-fill-4352-quote",
                            refid = "btc-fill-4352",
                            time = botTime.plusSeconds(3),
                            type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                            asset = Asset.USD,
                            amount = BigDecimal("-19.91"),
                            balance = BigDecimal("625.22"),
                            hasAuthoritativeBalance = true,
                        ),
                        LedgerEvent(
                            ledgerId = "btc-fill-4440-base",
                            refid = "btc-fill-4440",
                            time = botTime.plusSeconds(4),
                            type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                            asset = Asset.BTC,
                            amount = BigDecimal("0.00400203"),
                            fee = BigDecimal("0.00001401"),
                            balance = BigDecimal("0.02579067"),
                            hasAuthoritativeBalance = true,
                        ),
                        LedgerEvent(
                            ledgerId = "btc-fill-4440-quote",
                            refid = "btc-fill-4440",
                            time = botTime.plusSeconds(4),
                            type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                            asset = Asset.USD,
                            amount = BigDecimal("-361.56"),
                            balance = BigDecimal("263.66"),
                            hasAuthoritativeBalance = true,
                        ),
                        LedgerEvent(
                            ledgerId = "btc-fill-4532-base",
                            refid = "btc-fill-4532",
                            time = botTime.plusSeconds(5),
                            type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                            asset = Asset.BTC,
                            amount = BigDecimal("-0.00223943"),
                            fee = BigDecimal("0.00000784"),
                            balance = BigDecimal("0.02354340"),
                            hasAuthoritativeBalance = true,
                        ),
                        LedgerEvent(
                            ledgerId = "btc-fill-4532-quote",
                            refid = "btc-fill-4532",
                            time = botTime.plusSeconds(5),
                            type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                            asset = Asset.USD,
                            amount = BigDecimal("200.00"),
                            balance = BigDecimal("463.66"),
                            hasAuthoritativeBalance = true,
                        ),
                    ),
                )
                repository.saveSnapshot(
                    anchorSnapshot(
                        balances = mapOf(Asset.BTC to BigDecimal("0.02"), Asset.USD to BigDecimal("463.66")),
                        timestamp = Instant.parse("2026-01-03T00:00:00Z"),
                    ),
                )
                krakenService.tradeHistoryTotalCountOverride = 1
                krakenService.tradeHistorySupplier = { _, _ -> listOf(bot) }

                val status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.CONFIRMED
                val baselineId = repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_SNAPSHOT_ID)
                    ?.toInt() ?: error("baseline snapshot id is missing")
                val baseline = repository.getSnapshotById(baselineId)
                baseline.shouldNotBeNull()
                // The five fills net to exactly the recorded pre-fill balance: the old quote-fee
                // replay dropped 0.00004550 BTC of base-denominated fees and went negative.
                baseline.assets.getValue(Asset.BTC).balance.shouldBeEqualComparingTo(BigDecimal.ZERO)
                baseline.assets.getValue(Asset.USD).balance.shouldBeEqualComparingTo(BigDecimal("1462.15"))
                baseline.totalValueUSD.shouldBeEqualComparingTo(BigDecimal("1462.15"))
            }
        }

        "recovery rejects trades on unsupported historical markets" {
            runTest {
                val botTime = Instant.parse("2026-01-02T00:00:00Z")
                val bot = apiTrade("bot", botTime)
                repository.saveTrade(localEstimate(botTime, bot))
                repository.saveTrade(
                    TestFixtures.tradeRecord(
                        timestamp = botTime.plusSeconds(1),
                        pair = "ADAEUR",
                        side = OrderSide.BUY.apiValue,
                        symbol = "ADA",
                        volume = BigDecimal("0.5"),
                        usdAmount = BigDecimal("10.00"),
                        price = BigDecimal("100.00"),
                        fee = BigDecimal.ZERO,
                        source = TradeSource.LOCAL_ESTIMATE,
                        cycleId = "unsupported-market-cycle",
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
                status.reason shouldBe "unsupported historical market ADAEUR"
            }
        }

        "recovery rejects trades without a replayable historical cost" {
            runTest {
                val botTime = Instant.parse("2026-01-02T00:00:00Z")
                repository.saveTrade(
                    TestFixtures.tradeRecord(
                        timestamp = botTime,
                        pair = Asset.BTC_USD_PAIR,
                        side = OrderSide.BUY.apiValue,
                        symbol = Asset.BTC,
                        volume = BigDecimal("0.5"),
                        usdAmount = BigDecimal.ZERO,
                        price = BigDecimal.ZERO,
                        fee = BigDecimal.ZERO,
                        source = TradeSource.LOCAL_ESTIMATE,
                        cycleId = "missing-cost-cycle",
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
                status.reason shouldBe "missing historical trade cost"
            }
        }

        "ambiguous funding rows surface the bounded provenance reason" {
            runTest {
                val botTime = Instant.parse("2026-01-02T00:00:00Z")
                val bot = apiTrade("bot", botTime)
                repository.saveTrade(localEstimate(botTime, bot))
                krakenService.tradeHistoryTotalCountOverride = 1
                krakenService.tradeHistorySupplier = { _, _ -> listOf(bot) }
                ledgerRepository.saveLedgers(
                    listOf(
                        LedgerEvent(
                            ledgerId = "ambiguous-deposit",
                            refid = "AMBIGUOUS-REF",
                            time = botTime.plusSeconds(3600),
                            type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                            asset = "USD",
                            amount = BigDecimal("100.00"),
                        ),
                    ),
                )
                repository.saveSnapshot(
                    anchorSnapshot(
                        balances = mapOf(Asset.BTC to BigDecimal("0.01"), Asset.USD to BigDecimal("999.00")),
                        timestamp = Instant.parse("2026-01-03T00:00:00Z"),
                    ),
                )
                val resolver = object : FundingProvenanceResolver {
                    override fun resolve(event: LedgerEvent): FundingEvidence = FundingEvidence.UNRESOLVED

                    override fun explain(event: LedgerEvent): String? = "no candidate"
                }

                val status = newService(fundingProvenanceResolver = resolver).recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.AMBIGUOUS
                status.reason shouldBe "ledger provenance unresolved: deposit: no candidate"
            }
        }

        "interleaved staking-wallet chains reconstruct spot exactly" {
            runTest {
                val botTime = Instant.parse("2026-01-02T00:00:00Z")
                val bot = apiTrade("bot", botTime)
                repository.saveTrade(localEstimate(botTime, bot))
                krakenService.tradeHistoryTotalCountOverride = 1
                krakenService.tradeHistorySupplier = { _, _ -> listOf(bot) }

                // The recorded balances interleave the spot and staking-wallet chains for the
                // same asset. The early staking reward's checkpoint misses the chain by -0.00004,
                // which its non-zero fee makes tolerable; a non-snapping replay folds that drift
                // into the pre-window spot balance and fails on a tiny negative SOL balance.
                fun solEvent(
                    id: String,
                    offsetSeconds: Long,
                    type: String,
                    subtype: String?,
                    amount: String,
                    fee: String,
                    balance: String,
                    refid: String? = null,
                ) = LedgerEvent(
                    ledgerId = id,
                    refid = refid,
                    time = botTime.plusSeconds(offsetSeconds),
                    type = type,
                    subtype = subtype,
                    asset = "SOL",
                    amount = BigDecimal(amount),
                    fee = BigDecimal(fee),
                    balance = BigDecimal(balance),
                    hasAuthoritativeBalance = true,
                )

                ledgerRepository.saveLedgers(
                    listOf(
                        solEvent(
                            "sol-spot-start",
                            1,
                            KrakenApiConstants.LEDGER_TYPE_REWARD,
                            "welcomebonus",
                            "0.01",
                            "0",
                            "0.01",
                        ),
                        solEvent(
                            "sol-early-reward",
                            2,
                            KrakenApiConstants.LEDGER_TYPE_STAKING,
                            null,
                            "0.0201",
                            "0.0001",
                            "0.02996",
                        ),
                        solEvent(
                            "sol-spot-stake-out",
                            3,
                            KrakenApiConstants.LEDGER_TYPE_TRANSFER,
                            "spottostaking",
                            "-0.02996",
                            "0",
                            "0.00",
                            refid = "sol-stake-move-1",
                        ),
                        solEvent(
                            "sol-stake-in",
                            3,
                            KrakenApiConstants.LEDGER_TYPE_TRANSFER,
                            "spottostaking",
                            "0.02996",
                            "0",
                            "0.02996",
                            refid = "sol-stake-move-1",
                        ),
                        solEvent(
                            "sol-stake-reward",
                            4,
                            KrakenApiConstants.LEDGER_TYPE_STAKING,
                            null,
                            "0.0001",
                            "0",
                            "0.03006",
                        ),
                        solEvent(
                            "sol-stake-spot-out",
                            5,
                            KrakenApiConstants.LEDGER_TYPE_TRANSFER,
                            "stakingtospot",
                            "-0.03006",
                            "0",
                            "0.00",
                            refid = "sol-stake-move-2",
                        ),
                        solEvent(
                            "sol-spot-in",
                            5,
                            KrakenApiConstants.LEDGER_TYPE_TRANSFER,
                            "stakingtospot",
                            "0.03006",
                            "0",
                            "0.03006",
                            refid = "sol-stake-move-2",
                        ),
                        solEvent(
                            "sol-late-reward",
                            6,
                            KrakenApiConstants.LEDGER_TYPE_STAKING,
                            null,
                            "0.0001",
                            "0",
                            "0.03016",
                        ),
                    ),
                )
                repository.saveSnapshot(
                    anchorSnapshot(
                        balances = mapOf(Asset.BTC to BigDecimal("0.01"), Asset.USD to BigDecimal("999.00")),
                        timestamp = Instant.parse("2026-01-03T00:00:00Z"),
                    ),
                )

                val status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.CONFIRMED
                val baselineId = repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_SNAPSHOT_ID)?.toInt()
                    ?: error("baseline snapshot id is missing")
                val baseline = repository.getSnapshotById(baselineId) ?: error("baseline snapshot is missing")
                baseline.assets.getValue(Asset.SOL).balance shouldBeEqualComparingTo BigDecimal.ZERO
            }
        }

        "baseline replay version reflects historical universe semantics" {
            InceptionRecoveryService.CURRENT_BASELINE_REPLAY_VERSION shouldBe "12"
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
                status.reason shouldBe "malformed historical trade economics"
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
                status.reason shouldBe "unsupported historical trade side"
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
                status.reason shouldBe "negative reconstructed balance for BTC"
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
                status.reason shouldBe "malformed historical trade economics"
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
                status.reason shouldBe "malformed historical trade economics"
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

                status.status shouldBe InceptionRecoveryStatus.AMBIGUOUS
                status.reason shouldBe "trade ownership is ambiguous"
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

                status.status shouldBe InceptionRecoveryStatus.AMBIGUOUS
                status.reason shouldBe "ownership before candidate is unresolved"
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

                val tradeHistoryCalls = krakenService.getTradeHistoryCallCount
                val ledgerCalls = krakenService.getLedgersCallCount
                now = now.plusSeconds(InceptionRecoveryService.RETRY_INTERVAL_SECONDS + 1)
                val retried = newService().recoverOneBoundedRun()

                retried.status shouldBe InceptionRecoveryStatus.AMBIGUOUS
                retried.reason shouldBe "ledger provenance unresolved: deposit"
                krakenService.getTradeHistoryCallCount shouldBe tradeHistoryCalls
                krakenService.getLedgersCallCount shouldBe ledgerCalls
            }
        }

        "inconsistent authoritative trade ledger balances leave the baseline ambiguous" {
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
                            ledgerId = "trade-ledger-seed",
                            time = botTime.plusSeconds(7200),
                            type = "trade",
                            asset = Asset.BTC,
                            amount = BigDecimal("0.50"),
                            balance = BigDecimal("0.50"),
                            hasAuthoritativeBalance = true,
                        ),
                        LedgerEvent(
                            ledgerId = "trade-ledger-invalid",
                            time = botTime.plusSeconds(10800),
                            type = "trade",
                            asset = Asset.BTC,
                            amount = BigDecimal.ZERO,
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
                status.reason shouldContain "balance"
                status.reason shouldContain "e="
                status.reason shouldContain "o="
                status.reason shouldContain "d="
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
                val ledgerCallsAfterConfirmation = krakenService.getLedgersCallCount
                newService().recoverOneBoundedRun().status shouldBe InceptionRecoveryStatus.CONFIRMED
                krakenService.getTradeHistoryCallCount shouldBe tradeCallsAfterConfirmation
                krakenService.getLedgersCallCount shouldBe ledgerCallsAfterConfirmation
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

                repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_BASELINE_REPLAY_VERSION, "2")
                newService().getStatus().status shouldBe InceptionRecoveryStatus.IN_PROGRESS
                now = now.plusSeconds(InceptionRecoveryService.RETRY_INTERVAL_SECONDS + 1)
                newService().recoverOneBoundedRun().status shouldBe InceptionRecoveryStatus.CONFIRMED
                krakenService.getTradeHistoryCallCount shouldBe tradeCallsAfterConfirmation
                krakenService.getLedgersCallCount shouldBe ledgerCallsAfterConfirmation

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
                            balance = BigDecimal("1049.50"),
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
                            ledgerId = "promotion-airdrop",
                            refid = "promotion-airdrop-ref",
                            time = botTime.plusSeconds(9000),
                            type = KrakenApiConstants.LEDGER_TYPE_TRANSFER,
                            subtype = "airdrop",
                            asset = Asset.BTC,
                            amount = BigDecimal("0.05"),
                        ),
                        LedgerEvent(
                            ledgerId = "withdrawal",
                            refid = "withdrawal-ref",
                            time = withdrawalTime,
                            type = "withdrawal",
                            asset = Asset.USD,
                            amount = BigDecimal("-20.00"),
                            fee = BigDecimal("0.20"),
                            balance = BigDecimal("1029.30"),
                            hasAuthoritativeBalance = true,
                            hasAuthoritativeFee = true,
                        ),
                        LedgerEvent(
                            ledgerId = "trade-ledger-seed",
                            time = botTime.plusSeconds(14400),
                            type = "trade",
                            asset = Asset.BTC,
                            amount = BigDecimal("0.50"),
                            balance = BigDecimal("0.50"),
                            hasAuthoritativeBalance = true,
                        ),
                        LedgerEvent(
                            ledgerId = "trade-ledger-checkpoint",
                            time = botTime.plusSeconds(18000),
                            type = "trade",
                            asset = Asset.BTC,
                            amount = BigDecimal.ZERO,
                            balance = BigDecimal("0.50"),
                            hasAuthoritativeBalance = true,
                        ),
                    ),
                )
                repository.saveSnapshot(
                    anchorSnapshot(
                        balances = mapOf(Asset.BTC to BigDecimal("0.65"), Asset.USD to BigDecimal("1029.30")),
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
                baseline.assets.getValue(Asset.BTC).balance.shouldBeEqualComparingTo(BigDecimal("0.10"))
                baseline.assets.getValue(Asset.USD).balance.shouldBeEqualComparingTo(BigDecimal("1000.00"))
                baseline.totalValueUSD.shouldBeEqualComparingTo(BigDecimal("1010.00"))
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

        "a lone card deposit without retained plumbing is accepted as owner capital" {
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
                            ledgerId = "lone-card-deposit",
                            refid = "lone-card-ref",
                            time = botTime.plusSeconds(3600),
                            type = "deposit",
                            asset = Asset.USD,
                            amount = BigDecimal("100.00"),
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
            }
        }

        "a card deposit with a distant retained sibling fails the lone-deposit gate" {
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
                            ledgerId = "lone-card-spend",
                            refid = "lone-card-ref",
                            time = botTime.minusSeconds(1800),
                            type = "spend",
                            asset = Asset.USD,
                            amount = BigDecimal("-100.00"),
                        ),
                        LedgerEvent(
                            ledgerId = "lone-card-deposit",
                            refid = "lone-card-ref",
                            time = botTime.plusSeconds(3600),
                            type = "deposit",
                            asset = Asset.USD,
                            amount = BigDecimal("100.00"),
                        ),
                    ),
                )
                repository.saveSnapshot(
                    anchorSnapshot(
                        balances = mapOf(Asset.BTC to BigDecimal("0.50"), Asset.USD to BigDecimal("899.50")),
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

                status.status shouldBe InceptionRecoveryStatus.AMBIGUOUS
                status.reason shouldContain "exceeding maximum"
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

        "future trade one hour after inception cannot seed baseline price" {
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
                            Asset.ETH to BigDecimal("0.2"),
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

                status.status shouldBe InceptionRecoveryStatus.BASELINE_UNAVAILABLE
                status.reason shouldBe "historical price unavailable"
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
                    interval shouldBe 15
                    listOf(botTime.minusSeconds(901).epochSecond to BigDecimal("200.00"))
                }

                val status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.CONFIRMED
                krakenService.getOHLCCallCount shouldBe 1
            }
        }

        "an OHLC request failure retries without repaginating when price evidence becomes available" {
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
                var ohlcAvailable = false
                krakenService.ohlcSupplier = { _, _, _ ->
                    if (!ohlcAvailable) {
                        error("OHLC unavailable")
                    }
                    listOf(botTime.minusSeconds(901).epochSecond to BigDecimal("200.00"))
                }

                val first = newService().recoverOneBoundedRun()

                first.status shouldBe InceptionRecoveryStatus.BASELINE_UNAVAILABLE
                first.reason shouldBe "historical price unavailable"
                val tradeHistoryCalls = krakenService.getTradeHistoryCallCount

                ohlcAvailable = true
                now = now.plusSeconds(InceptionRecoveryService.RETRY_INTERVAL_SECONDS + 1)
                val retried = newService().recoverOneBoundedRun()

                retried.status shouldBe InceptionRecoveryStatus.CONFIRMED
                krakenService.getTradeHistoryCallCount shouldBe tradeHistoryCalls
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

        "zero inception balance does not require a historical price" {
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

                status.status shouldBe InceptionRecoveryStatus.CONFIRMED
                repository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_EPOCH_MS) shouldBe
                    botTime.toEpochMilli().toString()
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

                val changed = newService()
                    .prepareForCurrentConfiguration(config.settings.copy(inceptionDate = "new"))

                changed shouldBe true
                repository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_EPOCH_MS) shouldBe ""
                repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_STATUS) shouldBe ""
                repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_OFFSET) shouldBe ""
                val fingerprint = repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_CONFIG_FINGERPRINT)
                fingerprint?.length shouldBe 64
                fingerprint shouldNotBe "old"
                newService()
                    .prepareForCurrentConfiguration(config.settings.copy(inceptionDate = "new")) shouldBe false
            }
        }

        "history-path preparation never starts network continuity proof" {
            runTest {
                krakenService.fundingEvidenceScopeSupplier = { "account-A" }
                repository.saveTrade(apiTrade("legacy-trade", Instant.parse("2026-01-01T00:00:00Z")))

                val changed = realScopeService().prepareForCurrentConfiguration(null)

                changed shouldBe false
                krakenService.getTradeHistoryCallCount shouldBe 0
                krakenService.getLedgersCallCount shouldBe 0
                krakenService.getBalancesCallCount shouldBe 0
            }
        }

        "history-path preparation reports simulation without touching scope" {
            runTest {
                config = config.copy(settings = config.settings.copy(simulation = true))

                newService().prepareForCurrentConfiguration(null) shouldBe false
                krakenService.getTradeHistoryCallCount shouldBe 0
                krakenService.getLedgersCallCount shouldBe 0
            }
        }

        "bounded recovery fails closed on pending or reasonless scope results" {
            runTest {
                coEvery { trustedScopeGuard.validateAccountScope() } returnsMany listOf(
                    AccountScopeValidationResult(
                        status = AccountScopeValidationStatus.VALIDATION_PENDING,
                        reason = null,
                    ),
                    AccountScopeValidationResult(
                        status = AccountScopeValidationStatus.SCOPE_MISMATCH,
                        reason = null,
                        currentScopeDigest = AccountHistoryScopeGuard.digestAccountScope("account-B"),
                    ),
                )

                val pending = newService().recoverOneBoundedRun()
                pending.status shouldBe InceptionRecoveryStatus.UNAVAILABLE
                pending.reason shouldBe "account validation pending"

                val mismatch = newService().recoverOneBoundedRun()
                mismatch.status shouldBe InceptionRecoveryStatus.UNAVAILABLE
                mismatch.reason shouldBe "account scope changed; use correct DB or perform reset"
            }
        }

        "account-scope lookup failure makes recovery unavailable" {
            runTest {
                krakenService.fundingEvidenceScopeSupplier = { error("scope unavailable") }

                realScopeService().prepareForCurrentConfiguration(null) shouldBe false

                val status = realScopeService().recoverOneBoundedRun()
                status.status shouldBe InceptionRecoveryStatus.UNAVAILABLE
                status.reason shouldBe "account scope unavailable"
                repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_HORIZON_EPOCH_SEC) shouldBe null
                repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_OFFSET) shouldBe null
                ledgerRepository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_OFFSET) shouldBe null
                krakenService.getTradeHistoryCallCount shouldBe 0
                krakenService.getLedgersCallCount shouldBe 0
            }
        }

        "recovery revalidates account scope after the execution session is pinned" {
            runTest {
                coEvery { trustedScopeGuard.validateAccountScope() } returnsMany listOf(
                    AccountScopeValidationResult(
                        status = AccountScopeValidationStatus.VALID,
                        currentScopeDigest = AccountHistoryScopeGuard.digestAccountScope("account-A"),
                    ),
                    AccountScopeValidationResult.scopeMismatch(
                        AccountHistoryScopeGuard.digestAccountScope("account-B"),
                    ),
                )

                val status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.UNAVAILABLE
                status.reason shouldBe "account scope changed; use correct DB or perform reset"
                krakenService.getTradeHistoryCallCount shouldBe 0
                krakenService.getLedgersCallCount shouldBe 0
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

        "approved-start evaluation failure retries with newly retained evidence" {
            runTest {
                val requestedStart = Instant.parse("2026-01-02T00:00:00Z")
                config = config.copy(settings = config.settings.copy(inceptionDate = requestedStart.toString()))
                repository.saveTrade(apiTrade("price", requestedStart))
                repository.saveSnapshot(
                    anchorSnapshot(
                        balances = mapOf(Asset.BTC to BigDecimal("0.50"), Asset.USD to BigDecimal("500.00")),
                        timestamp = now.plusSeconds(60),
                    ),
                )
                newService().prepareForCurrentConfiguration(config.settings) shouldBe true
                repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_STATUS, "COMPLETE")
                repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_OFFSET, "completed")
                ledgerRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_STATUS, "COMPLETE")
                ledgerRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_OFFSET, "completed")
                coEvery { configService.beginExecutionSession() } throws
                    IllegalStateException("session unavailable")

                val failed = newService().recoverOneBoundedRun()

                failed.status shouldBe InceptionRecoveryStatus.FAILED
                val initialHorizon = repository.getSyncMetadata(
                    SyncMetadataKeys.INCEPTION_RECOVERY_HORIZON_EPOCH_SEC,
                )
                now = now.plusSeconds(InceptionRecoveryService.RETRY_INTERVAL_SECONDS + 1)
                coEvery { configService.beginExecutionSession() } returns Unit

                val retried = newService().recoverOneBoundedRun()

                retried.status shouldBe InceptionRecoveryStatus.CONFIRMED
                repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_HORIZON_EPOCH_SEC) shouldBe
                    initialHorizon
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

        "recovery honors an approved start published after preflight" {
            runTest {
                val manualConfig = config.copy(settings = config.settings.copy(inceptionDate = "2026-01-01"))
                every { configService.getConfig() } returnsMany listOf(config, manualConfig)

                val status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.BASELINE_UNAVAILABLE
                status.reason shouldBe "no retained balance anchor"
                krakenService.getTradeHistoryCallCount shouldBeGreaterThan 0
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

        "removed historical asset does not veto discovery before ownership validation" {
            runTest {
                config = appConfig(
                    listOf(
                        Allocation(Asset.BTC, 50.0),
                        Allocation(Asset.ETH, 30.0),
                        Allocation(Asset.USD, 20.0),
                    ),
                )
                val solTrade = apiTrade(
                    id = "sol-trade",
                    timestamp = Instant.parse("2026-01-01T00:00:00Z"),
                    symbol = "SOL",
                    volume = BigDecimal("5.0"),
                    usdAmount = BigDecimal("50.00"),
                )
                val botTime = Instant.parse("2026-01-02T00:00:00Z")
                val bot = apiTrade(
                    id = "btc-bot",
                    timestamp = botTime,
                    symbol = Asset.BTC,
                    volume = BigDecimal("0.5"),
                    usdAmount = BigDecimal("50.00"),
                )
                repository.saveTrade(localEstimate(botTime, bot))
                repository.saveSnapshot(
                    anchorSnapshot(
                        balances = mapOf(
                            Asset.BTC to BigDecimal("0.5"),
                            Asset.ETH to BigDecimal.ZERO,
                            Asset.USD to BigDecimal("949.70"),
                        ),
                        timestamp = Instant.parse("2026-01-03T00:00:00Z"),
                    ),
                )
                krakenService.tradeHistoryTotalCountOverride = 2
                krakenService.tradeHistorySupplier = { _, offset -> listOf(solTrade, bot).drop(offset ?: 0).take(50) }

                val status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.AMBIGUOUS
                status.reason shouldBe "ownership before candidate is unresolved"
            }
        }

        "future snapshot rejected as baseline price leaves baseline unavailable" {
            runTest {
                config = appConfig(
                    listOf(
                        Allocation(Asset.BTC, 50.0),
                        Allocation(Asset.ETH, 30.0),
                        Allocation(Asset.USD, 20.0),
                    ),
                )
                val botTime = Instant.parse("2026-01-02T00:00:00Z")
                val bot = apiTrade(
                    id = "bot",
                    timestamp = botTime,
                    symbol = Asset.BTC,
                    volume = BigDecimal("0.5"),
                    usdAmount = BigDecimal("50.00"),
                )
                repository.saveTrade(localEstimate(botTime, bot))
                val futureSnapshot = anchorSnapshot(
                    balances = mapOf(
                        Asset.BTC to BigDecimal("0.5"),
                        Asset.ETH to BigDecimal("0.1"),
                        Asset.USD to BigDecimal("949.70"),
                    ),
                    prices = mapOf(
                        Asset.BTC to BigDecimal("100.00"),
                        Asset.ETH to BigDecimal("200.00"),
                        Asset.USD to BigDecimal.ONE,
                    ),
                    timestamp = Instant.parse("2026-01-03T00:00:00Z"),
                )
                repository.saveSnapshot(futureSnapshot)
                krakenService.tradeHistoryTotalCountOverride = 1
                krakenService.tradeHistorySupplier = { _, _ -> listOf(bot) }

                val status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.BASELINE_UNAVAILABLE
                status.reason shouldBe "historical price unavailable"
            }
        }

        "daily candle that begins before inception but closes after is rejected" {
            runTest {
                config = appConfig(
                    listOf(
                        Allocation(Asset.BTC, 50.0),
                        Allocation(Asset.ETH, 30.0),
                        Allocation(Asset.USD, 20.0),
                    ),
                )
                val botTime = Instant.parse("2026-01-02T12:00:00Z")
                val bot = apiTrade(
                    id = "bot",
                    timestamp = botTime,
                    symbol = Asset.BTC,
                    volume = BigDecimal("0.5"),
                    usdAmount = BigDecimal("50.00"),
                )
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
                val candleOpenSec = Instant.parse("2026-01-02T00:00:00Z").epochSecond
                krakenService.ohlcSupplier = { pair, interval, _ ->
                    pair shouldBe Asset.ETH_USD_PAIR
                    interval shouldBe 15
                    listOf(candleOpenSec to BigDecimal("200.00"))
                }

                val status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.BASELINE_UNAVAILABLE
                status.reason shouldBe "historical price unavailable"
            }
        }

        "101-row ledger recovery with missing authoritative total fetches and persists all entries" {
            runTest {
                val ledgerHistory = (0 until 101).map { index ->
                    LedgerEvent(
                        ledgerId = "ledger-101-$index",
                        time = Instant.parse("2026-04-01T00:00:00Z").minusSeconds(index.toLong()),
                        type = "staking",
                        asset = Asset.BTC,
                        amount = BigDecimal.ZERO,
                    )
                }
                krakenService.tradeHistoryTotalCountOverride = 0
                krakenService.ledgerTotalCountOverride = 0
                krakenService.ledgerSupplier = { _, offset, _, _ -> ledgerHistory.drop(offset ?: 0).take(50) }

                val status = newService().recoverOneBoundedRun()
                status.status shouldBe InceptionRecoveryStatus.COMPLETE_NO_BOT_EVIDENCE
                status.ledgerOffset shouldBe "completed"
                krakenService.getLedgersCallCount shouldBe 3
                ledgerRepository.getLedgersInRange(Instant.EPOCH, now.plusSeconds(1)).size shouldBe 101
            }
        }

        "switching Kraken account scope fails closed when historical data already exists" {
            runTest {
                krakenService.fundingEvidenceScopeSupplier = { "account-A" }
                // The History-path prepare is a local trust read: it stays pending on an
                // empty database and never binds. Background validation binds it.
                realScopeService().prepareForCurrentConfiguration(null) shouldBe false
                realScopeService().recoverOneBoundedRun()
                repository.saveTrade(apiTrade("existing-trade", Instant.parse("2026-01-01T00:00:00Z")))

                krakenService.fundingEvidenceScopeSupplier = { "account-B" }
                val service = realScopeService()
                service.prepareForCurrentConfiguration(null) shouldBe false

                val status = service.recoverOneBoundedRun()
                status.status shouldBe InceptionRecoveryStatus.UNAVAILABLE
                status.reason shouldBe "account scope changed; use correct DB or perform reset"
            }
        }

        "manual override with mismatched account scope stays unavailable" {
            runTest {
                config = config.copy(settings = config.settings.copy(inceptionDate = "2026-01-01"))
                krakenService.fundingEvidenceScopeSupplier = { "account-A" }
                realScopeService().prepareForCurrentConfiguration(null) shouldBe false
                realScopeService().recoverOneBoundedRun()
                repository.saveTrade(apiTrade("existing-trade", Instant.parse("2026-01-01T00:00:00Z")))

                krakenService.fundingEvidenceScopeSupplier = { "account-B" }
                val status = realScopeService().recoverOneBoundedRun()
                status.status shouldBe InceptionRecoveryStatus.UNAVAILABLE
                status.reason shouldBe "account scope changed; use correct DB or perform reset"
            }
        }

        "unbound legacy history stays unavailable until continuity is proven" {
            runTest {
                krakenService.fundingEvidenceScopeSupplier = { "account-A" }
                repository.saveTrade(apiTrade("legacy-trade", Instant.parse("2026-01-01T00:00:00Z")))

                val status = realScopeService().recoverOneBoundedRun()
                status.status shouldBe InceptionRecoveryStatus.UNAVAILABLE
                status.reason shouldBe "existing history cannot be verified for active credentials"
            }
        }

        "crash during bounded recovery resumes idempotently without corrupting progress" {
            runTest {
                val history = (0 until 60).map { index ->
                    apiTrade(
                        id = "crash-trade-$index",
                        timestamp = Instant.parse("2026-04-01T00:00:00Z").minusSeconds(index.toLong()),
                    )
                }
                krakenService.tradeHistoryTotalCountOverride = 60
                var failOnCall = 2
                var callCount = 0
                krakenService.tradeHistorySupplier = { _, offset ->
                    callCount++
                    if (callCount == failOnCall) {
                        throw RuntimeException("simulated network crash during pagination")
                    }
                    history.drop(offset ?: 0).take(50)
                }

                val firstStatus = newService().recoverOneBoundedRun()
                firstStatus.status shouldBe InceptionRecoveryStatus.FAILED
                firstStatus.reason shouldBe "history request failed"

                now = now.plusSeconds(InceptionRecoveryService.RETRY_INTERVAL_SECONDS + 1)
                failOnCall = -1
                val secondStatus = newService().recoverOneBoundedRun()
                secondStatus.status shouldBe InceptionRecoveryStatus.AMBIGUOUS
                secondStatus.tradeOffset shouldBe "completed"
                repository.getTradesInRange(Instant.EPOCH, now.plusSeconds(1)).size shouldBe 60
            }
        }

        "persists inference evidence with a behavioral candidate and independent first positive" {
            runTest {
                val batchStart = Instant.parse("2026-04-01T00:00:00Z")
                val batch = listOf(
                    apiTrade("b-sell-1", batchStart, symbol = "ASSET1").copy(side = OrderSide.SELL.apiValue),
                    apiTrade("b-sell-2", batchStart.plusSeconds(1), symbol = "ASSET2").copy(
                        side = OrderSide.SELL.apiValue,
                    ),
                    apiTrade("b-buy-3", batchStart.plusSeconds(2), symbol = "ASSET3"),
                    apiTrade("b-buy-4", batchStart.plusSeconds(3), symbol = "ASSET4"),
                )
                val ownedTrade = localEstimate(
                    batchStart.plusSeconds(600),
                    apiTrade("owned", batchStart.plusSeconds(600), symbol = "SOL"),
                )
                krakenService.tradeHistorySupplier = { _, _ -> batch + ownedTrade }
                krakenService.tradeHistoryTotalCountOverride = 0
                repository.setSyncMetadata(
                    SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST,
                    AccountHistoryScopeGuard.digestAccountScope("fake-account"),
                )

                val status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.AMBIGUOUS
                val scopeDigest = AccountHistoryScopeGuard.digestAccountScope("fake-account")
                val fingerprint = newService().inferenceFingerprint(config, scopeDigest)
                val record = requireNotNull(repository.findInceptionInferenceEvidence(fingerprint))

                record.inferredStart shouldBe batchStart
                record.strongestObservedStart shouldBe batchStart
                record.inferredStartStrength shouldBe "HIGH"
                record.strongestEpisodeStrength shouldBe "HIGH"
                record.candidates.size shouldBe 1
                record.candidates.first().orderCount shouldBe 4
                record.candidates.first().timescalesSeconds shouldBe setOf(2L, 5L, 15L)
                record.firstPositive shouldBe batchStart.plusSeconds(600)
                record.coverageStart shouldBe batchStart
                record.coverageEnd shouldBe batchStart.plusSeconds(600)
                repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_INFERENCE_VERSION) shouldBe
                    InceptionRecoveryService.CURRENT_INFERENCE_VERSION
                repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_INFERENCE_FINGERPRINT) shouldBe fingerprint

                val firstDigest = record.evidenceDigest
                now = now.plusSeconds(InceptionRecoveryService.RETRY_INTERVAL_SECONDS + 1)
                newService().recoverOneBoundedRun()
                val reloaded = requireNotNull(repository.findInceptionInferenceEvidence(fingerprint))
                reloaded.evidenceDigest shouldBe firstDigest
                reloaded.inferredStart shouldBe batchStart
                reloaded.candidates.size shouldBe 1
            }
        }

        "persists first positive evidence without behavioral candidates" {
            runTest {
                val firstUnknown = Instant.parse("2026-04-01T00:00:00Z")
                val trades = listOf(
                    apiTrade("u-1", firstUnknown),
                    apiTrade("u-2", firstUnknown.plusSeconds(3_600)),
                    localEstimate(
                        firstUnknown.plusSeconds(7_200),
                        apiTrade("owned", firstUnknown.plusSeconds(7_200), symbol = "SOL"),
                    ),
                )
                krakenService.tradeHistorySupplier = { _, _ -> trades }
                krakenService.tradeHistoryTotalCountOverride = 0
                repository.setSyncMetadata(
                    SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST,
                    AccountHistoryScopeGuard.digestAccountScope("fake-account"),
                )

                val status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.AMBIGUOUS
                val scopeDigest = AccountHistoryScopeGuard.digestAccountScope("fake-account")
                val fingerprint = newService().inferenceFingerprint(config, scopeDigest)
                val record = requireNotNull(repository.findInceptionInferenceEvidence(fingerprint))

                record.candidates shouldBe emptyList()
                record.inferredStart.shouldBeNull()
                record.inferredStartStrength.shouldBeNull()
                record.firstPositive shouldBe firstUnknown.plusSeconds(7_200)
                record.coverageStart shouldBe firstUnknown
                record.coverageEnd shouldBe firstUnknown.plusSeconds(7_200)
            }
        }

        "allocation-only change keeps inference visible for the same account" {
            runTest {
                val batchStart = Instant.parse("2026-04-01T00:00:00Z")
                val batch = listOf(
                    apiTrade("b-sell-1", batchStart, symbol = "ASSET1").copy(side = OrderSide.SELL.apiValue),
                    apiTrade("b-sell-2", batchStart.plusSeconds(1), symbol = "ASSET2").copy(
                        side = OrderSide.SELL.apiValue,
                    ),
                    apiTrade("b-buy-3", batchStart.plusSeconds(2), symbol = "ASSET3"),
                    apiTrade("b-buy-4", batchStart.plusSeconds(3), symbol = "ASSET4"),
                )
                krakenService.tradeHistorySupplier = { _, _ -> batch }
                krakenService.tradeHistoryTotalCountOverride = 0
                repository.setSyncMetadata(
                    SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST,
                    AccountHistoryScopeGuard.digestAccountScope("fake-account"),
                )
                newService().recoverOneBoundedRun()

                config = appConfig(listOf(Allocation(Asset.BTC, 45.0), Allocation(Asset.USD, 55.0)))
                val info = newService().getLocalInceptionDisplayInfo()

                info.status shouldBe InceptionDisplayStatus.UNAVAILABLE
                info.inferredStartText shouldBe batchStart.toString()
                info.inferredStartStrengthText shouldBe "HIGH"
            }
        }

        "classifies known order-intent identities as positive ownership evidence" {
            runTest {
                val firstUnknown = Instant.parse("2026-04-01T00:00:00Z")
                val unknownTrades = listOf(
                    apiTrade("u-1", firstUnknown),
                    apiTrade("u-2", firstUnknown.plusSeconds(3_600)),
                )
                val knownOrderTrade = apiTrade("known", firstUnknown.plusSeconds(7_200)).copy(
                    orderTxid = "order-known",
                )
                val clientOnlyTrade = apiTrade("client-owned", firstUnknown.plusSeconds(7_201)).copy(
                    clientOrderId = "client-known",
                )
                val orderIntentRepository = mockk<OrderIntentRepository>()
                coEvery {
                    orderIntentRepository.getKnownRebalancerOrderIdentities(any(), any())
                } returns RebalancerOrderIdentities(orderTxids = setOf("order-known"))
                krakenService.tradeHistorySupplier =
                    { _, _ -> unknownTrades + knownOrderTrade + clientOnlyTrade }
                krakenService.tradeHistoryTotalCountOverride = 0
                val scopeDigest = AccountHistoryScopeGuard.digestAccountScope("fake-account")
                repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST, scopeDigest)

                val status = newService(orderIntentRepository).recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.AMBIGUOUS
                val fingerprint = newService(orderIntentRepository).inferenceFingerprint(config, scopeDigest)
                val record = requireNotNull(repository.findInceptionInferenceEvidence(fingerprint))
                record.firstPositive shouldBe firstUnknown.plusSeconds(7_200)
                record.coverageEnd shouldBe firstUnknown.plusSeconds(7_201)
            }
        }

        "approved start reverse-replays a nearby post-start snapshot to the requested time" {
            runTest {
                val requestedStart = Instant.parse("2026-01-01T00:00:00Z")
                config = config.copy(settings = config.settings.copy(inceptionDate = requestedStart.toString()))
                repository.saveTrade(apiTrade("price", requestedStart))
                repository.saveTrade(apiTrade("post-start", requestedStart.plusSeconds(120)))
                repository.saveSnapshot(
                    anchorSnapshot(
                        balances = mapOf(Asset.BTC to BigDecimal("0.50"), Asset.USD to BigDecimal("500.00")),
                        timestamp = requestedStart.plusSeconds(120),
                    ),
                )

                val status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.CONFIRMED
                status.reason shouldBe "approved-start baseline ready"
                repository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_SOURCE) shouldBe
                    InceptionRecoveryService.INCEPTION_SOURCE_APPROVED
                repository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_EPOCH_MS) shouldBe
                    requestedStart.toEpochMilli().toString()
                val approvedId = requireNotNull(
                    repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_APPROVED_BASELINE_SNAPSHOT_ID)
                        ?.toIntOrNull(),
                )
                val baseline = requireNotNull(repository.getSnapshotById(approvedId))
                baseline.timestamp shouldBe requestedStart
                baseline.assets.getValue(Asset.BTC).balance shouldBeEqualComparingTo BigDecimal("0.49")
                baseline.assets.getValue(Asset.USD).balance shouldBeEqualComparingTo BigDecimal("501.01")
                repository.getSnapshotsInRange(Instant.EPOCH, now).size shouldBe 2
            }
        }

        "approved start reconstructs the baseline at the requested instant" {
            runTest {
                val requestedStart = Instant.parse("2026-01-02T00:00:00Z")
                config = config.copy(settings = config.settings.copy(inceptionDate = requestedStart.toString()))
                repository.saveTrade(apiTrade("price", requestedStart))
                repository.saveTrade(apiTrade("bot", requestedStart.plusSeconds(60)))
                repository.saveSnapshot(
                    anchorSnapshot(
                        balances = mapOf(Asset.BTC to BigDecimal("0.03"), Asset.USD to BigDecimal("999.00")),
                        timestamp = Instant.parse("2026-01-03T00:00:00Z"),
                    ),
                )
                krakenService.seedLedgerEntries(
                    listOf(
                        LedgerEvent(
                            ledgerId = "promotion-reward",
                            time = requestedStart.plusSeconds(7200),
                            type = KrakenApiConstants.LEDGER_TYPE_REWARD,
                            asset = Asset.BTC,
                            amount = BigDecimal("0.001"),
                            fee = BigDecimal("0.0001"),
                        ),
                    ),
                )
                krakenService.tradeHistorySupplier = { _, _ -> emptyList() }
                krakenService.tradeHistoryTotalCountOverride = 2

                val status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.CONFIRMED
                val baseline = requireNotNull(
                    repository.getSnapshotById(
                        requireNotNull(
                            repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_APPROVED_BASELINE_SNAPSHOT_ID)
                                ?.toIntOrNull(),
                        ),
                    ),
                )
                baseline.timestamp shouldBe requestedStart
                baseline.assets.getValue(Asset.BTC).balance shouldBeEqualComparingTo BigDecimal("0.0191")
                baseline.assets.getValue(Asset.BTC).price shouldBeEqualComparingTo BigDecimal("100.00000000")
                baseline.assets.getValue(Asset.USD).balance shouldBeEqualComparingTo BigDecimal("1000.01")
                baseline.totalValueUSD shouldBeEqualComparingTo BigDecimal("1001.92")
            }
        }

        "approved start stays pending until recovery streams complete" {
            runTest {
                val requestedStart = Instant.parse("2026-01-02T00:00:00Z")
                config = config.copy(settings = config.settings.copy(inceptionDate = requestedStart.toString()))
                repository.saveTrade(apiTrade("price", requestedStart))
                val botTrades = (1..250L).map { offset ->
                    apiTrade("bot-$offset", requestedStart.plusSeconds(offset))
                }
                botTrades.forEach { repository.saveTrade(it) }
                repository.saveSnapshot(
                    anchorSnapshot(
                        balances = mapOf(Asset.BTC to BigDecimal("2.53"), Asset.USD to BigDecimal("1000.00")),
                        timestamp = Instant.parse("2026-01-03T00:00:00Z"),
                    ),
                )
                krakenService.tradeHistorySupplier = { _, offset -> botTrades.drop(offset ?: 0).take(50) }
                krakenService.tradeHistoryTotalCountOverride = botTrades.size + 1

                val pending = newService().recoverOneBoundedRun()
                pending.status shouldBe InceptionRecoveryStatus.IN_PROGRESS
                repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_APPROVED_BASELINE_SNAPSHOT_ID).orEmpty() shouldBe
                    ""

                now = now.plusSeconds(InceptionRecoveryService.RETRY_INTERVAL_SECONDS + 1)
                val ready = newService().recoverOneBoundedRun()
                ready.status shouldBe InceptionRecoveryStatus.CONFIRMED
                val baseline = requireNotNull(
                    repository.getSnapshotById(
                        requireNotNull(
                            repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_APPROVED_BASELINE_SNAPSHOT_ID)
                                ?.toIntOrNull(),
                        ),
                    ),
                )
                baseline.assets.getValue(Asset.BTC).balance shouldBeEqualComparingTo BigDecimal("0.03")
                baseline.assets.getValue(Asset.USD).balance shouldBeEqualComparingTo BigDecimal("1252.50")

                val calls = krakenService.getTradeHistoryCallCount
                now = now.plusSeconds(InceptionRecoveryService.RETRY_INTERVAL_SECONDS + 1)
                newService().recoverOneBoundedRun().status shouldBe InceptionRecoveryStatus.CONFIRMED
                krakenService.getTradeHistoryCallCount shouldBe calls
            }
        }

        "approved start failure stays terminal until configuration changes" {
            runTest {
                val requestedStart = Instant.parse("2026-01-02T00:00:00Z")
                config = config.copy(settings = config.settings.copy(inceptionDate = requestedStart.toString()))
                repository.saveTrade(apiTrade("price", requestedStart))
                repository.saveTrade(apiTrade("bot", requestedStart.plusSeconds(60)))
                repository.saveSnapshot(
                    anchorSnapshot(
                        balances = mapOf(Asset.BTC to BigDecimal("0.03"), Asset.USD to BigDecimal("999.00")),
                        timestamp = Instant.parse("2026-01-03T00:00:00Z"),
                    ),
                )
                ledgerRepository.saveLedgers(
                    listOf(
                        LedgerEvent(
                            ledgerId = "airdrop-1",
                            time = requestedStart.plusSeconds(60),
                            type = "airdrop",
                            asset = Asset.BTC,
                            amount = BigDecimal("0.001"),
                        ),
                    ),
                )

                val failed = newService().recoverOneBoundedRun()
                failed.status shouldBe InceptionRecoveryStatus.AMBIGUOUS
                failed.reason shouldBe "unsupported ledger type airdrop"
                repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_APPROVED_BASELINE_SNAPSHOT_ID).orEmpty() shouldBe
                    ""

                val calls = krakenService.getTradeHistoryCallCount
                now = now.plusSeconds(InceptionRecoveryService.RETRY_INTERVAL_SECONDS + 1)
                val still = newService().recoverOneBoundedRun()
                still.status shouldBe InceptionRecoveryStatus.AMBIGUOUS
                still.reason shouldBe "unsupported ledger type airdrop"
                krakenService.getTradeHistoryCallCount shouldBe calls
            }
        }

        "approved start failure retries when local evidence changes without changing the strategy start" {
            runTest {
                val requestedStart = Instant.parse("2026-01-02T00:00:00Z")
                config = config.copy(settings = config.settings.copy(inceptionDate = requestedStart.toString()))
                krakenService.tradeHistorySupplier = { _, _ -> emptyList() }
                krakenService.tradeHistoryTotalCountOverride = 0
                repository.saveTrade(apiTrade("price", requestedStart))

                val failed = newService().recoverOneBoundedRun()

                failed.status shouldBe InceptionRecoveryStatus.BASELINE_UNAVAILABLE
                failed.reason shouldBe "no retained balance anchor"
                repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_EVIDENCE_FINGERPRINT)
                    .orEmpty() shouldNotBe ""
                val initialHorizon = repository.getSyncMetadata(
                    SyncMetadataKeys.INCEPTION_RECOVERY_HORIZON_EPOCH_SEC,
                )
                val initialTradeCalls = krakenService.getTradeHistoryCallCount
                val initialLedgerCalls = krakenService.getLedgersCallCount

                repository.saveSnapshot(
                    anchorSnapshot(
                        balances = mapOf(Asset.BTC to BigDecimal("0.50"), Asset.USD to BigDecimal("500.00")),
                        timestamp = now.plusSeconds(60),
                    ),
                )
                now = now.plusSeconds(InceptionRecoveryService.RETRY_INTERVAL_SECONDS + 1)

                val retried = newService().recoverOneBoundedRun()

                retried.status shouldBe InceptionRecoveryStatus.CONFIRMED
                config.settings.inceptionDate shouldBe requestedStart.toString()
                repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_EVIDENCE_FINGERPRINT) shouldBe ""
                repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_HORIZON_EPOCH_SEC) shouldBe
                    initialHorizon
                krakenService.getTradeHistoryCallCount shouldBe initialTradeCalls
                krakenService.getLedgersCallCount shouldBe initialLedgerCalls
            }
        }

        "approved start retries when funding provenance becomes available" {
            runTest {
                val botTime = Instant.parse("2026-01-02T00:00:00Z")
                val requestedStart = botTime.minusSeconds(60)
                config = config.copy(settings = config.settings.copy(inceptionDate = requestedStart.toString()))
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
                            ledgerId = "card-deposit-retry",
                            refid = "card-retry-ref",
                            time = botTime.plusSeconds(3600),
                            type = "deposit",
                            asset = Asset.USD,
                            amount = BigDecimal("100.00"),
                        ),
                        LedgerEvent(
                            ledgerId = "card-spend-retry",
                            refid = "card-retry-ref",
                            time = botTime.plusSeconds(3601),
                            type = "spend",
                            asset = Asset.USD,
                            amount = BigDecimal("-100.00"),
                        ),
                        LedgerEvent(
                            ledgerId = "card-receive-retry",
                            refid = "card-retry-ref",
                            time = botTime.plusSeconds(3602),
                            type = "receive",
                            asset = Asset.BTC,
                            amount = BigDecimal("0.50"),
                        ),
                    ),
                )
                repository.saveSnapshot(
                    anchorSnapshot(
                        balances = mapOf(Asset.BTC to BigDecimal("1.00"), Asset.USD to BigDecimal("949.50")),
                        timestamp = Instant.parse("2026-01-03T00:00:00Z"),
                    ),
                )
                krakenService.tradeHistoryTotalCountOverride = 1
                krakenService.tradeHistorySupplier = { _, _ -> listOf(bot) }
                var fundingAvailable = false
                val cardResolver = object : FundingProvenanceResolver {
                    override fun resolve(event: LedgerEvent): FundingEvidence =
                        if (fundingAvailable) FundingEvidence.EXTERNAL else FundingEvidence.UNRESOLVED

                    override fun isCardFunding(event: LedgerEvent): Boolean = true
                }

                val failed = newService(fundingProvenanceResolver = cardResolver).recoverOneBoundedRun()

                failed.status shouldBe InceptionRecoveryStatus.AMBIGUOUS
                failed.reason shouldBe "Funding legs in card group cannot be proven external"
                repository.setSyncMetadata(
                    SyncMetadataKeys.INCEPTION_RECOVERY_REASON,
                    "Funding legs in card group cannot be proven",
                )
                val historyCalls = krakenService.getTradeHistoryCallCount
                fundingAvailable = true
                now = now.plusSeconds(InceptionRecoveryService.RETRY_INTERVAL_SECONDS + 1)

                val retried = newService(fundingProvenanceResolver = cardResolver).recoverOneBoundedRun()

                retried.status shouldBe InceptionRecoveryStatus.CONFIRMED
                krakenService.getTradeHistoryCallCount shouldBe historyCalls
            }
        }

        "approved start in the future is unavailable" {
            runTest {
                config = config.copy(settings = config.settings.copy(inceptionDate = "2026-05-02"))

                val status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.UNAVAILABLE
                status.reason shouldBe "approved start is in the future"
            }
        }

        "invalid approved start date is unavailable" {
            runTest {
                config = config.copy(settings = config.settings.copy(inceptionDate = "not-a-date"))

                val status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.UNAVAILABLE
                status.reason shouldBe "invalid approved inception date"
            }
        }

        "approved start keeps a boundary event at the requested instant out of the baseline" {
            runTest {
                val requestedStart = Instant.parse("2026-01-02T00:00:00Z")
                config = config.copy(settings = config.settings.copy(inceptionDate = requestedStart.toString()))
                repository.saveTrade(apiTrade("at-boundary", requestedStart))
                repository.saveTrade(apiTrade("after-boundary", requestedStart.plusMillis(1)))
                repository.saveSnapshot(
                    anchorSnapshot(
                        balances = mapOf(Asset.BTC to BigDecimal("0.03"), Asset.USD to BigDecimal("999.00")),
                        timestamp = Instant.parse("2026-01-03T00:00:00Z"),
                    ),
                )
                krakenService.tradeHistorySupplier = { _, _ -> emptyList() }
                krakenService.tradeHistoryTotalCountOverride = 2

                val status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.CONFIRMED
                val baseline = requireNotNull(
                    repository.getSnapshotById(
                        requireNotNull(
                            repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_APPROVED_BASELINE_SNAPSHOT_ID)
                                ?.toIntOrNull(),
                        ),
                    ),
                )
                baseline.timestamp shouldBe requestedStart
                baseline.assets.getValue(Asset.BTC).balance shouldBeEqualComparingTo BigDecimal("0.02")
                baseline.assets.getValue(Asset.USD).balance shouldBeEqualComparingTo BigDecimal("1000.01")
            }
        }

        "allocation change clears an approved baseline and re-attempts reconstruction" {
            runTest {
                val requestedStart = Instant.parse("2026-01-02T00:00:00Z")
                config = config.copy(settings = config.settings.copy(inceptionDate = requestedStart.toString()))
                repository.saveTrade(apiTrade("price", requestedStart))
                repository.saveTrade(apiTrade("bot", requestedStart.plusSeconds(60)))
                repository.saveSnapshot(
                    anchorSnapshot(
                        balances = mapOf(Asset.BTC to BigDecimal("0.03"), Asset.USD to BigDecimal("999.00")),
                        timestamp = Instant.parse("2026-01-03T00:00:00Z"),
                    ),
                )
                krakenService.tradeHistorySupplier = { _, _ -> emptyList() }
                krakenService.tradeHistoryTotalCountOverride = 2
                newService().recoverOneBoundedRun().status shouldBe InceptionRecoveryStatus.CONFIRMED

                config = config.copy(
                    allocations = listOf(
                        Allocation(Asset.BTC, 40.0),
                        Allocation(Asset.ETH, 30.0),
                        Allocation(Asset.USD, 30.0),
                    ),
                )
                val reattempt = newService().recoverOneBoundedRun()

                reattempt.status shouldBe InceptionRecoveryStatus.AMBIGUOUS
                reattempt.reason shouldBe "configured asset universe changed"
                repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_APPROVED_BASELINE_SNAPSHOT_ID).orEmpty() shouldBe
                    ""
            }
        }

        "approved-start display maps confirmed, failed, and pending recovery states" {
            runTest {
                config = config.copy(settings = config.settings.copy(inceptionDate = "2026-01-01T00:00:00Z"))
                repository.setSyncMetadata(
                    SyncMetadataKeys.INCEPTION_RECOVERY_STATUS,
                    InceptionRecoveryStatus.CONFIRMED,
                )
                repository.setSyncMetadata(
                    SyncMetadataKeys.INCEPTION_BASELINE_REPLAY_VERSION,
                    InceptionRecoveryService.CURRENT_BASELINE_REPLAY_VERSION,
                )
                val confirmed = newService().getLocalInceptionDisplayInfo()
                confirmed.status shouldBe InceptionDisplayStatus.APPROVED_READY
                confirmed.message shouldContain ViewText.INCEPTION_APPROVED_BASELINE_READY_PREFIX

                repository.setSyncMetadata(
                    SyncMetadataKeys.INCEPTION_RECOVERY_STATUS,
                    InceptionRecoveryStatus.AMBIGUOUS,
                )
                repository.setSyncMetadata(
                    SyncMetadataKeys.INCEPTION_RECOVERY_REASON,
                    "unsupported ledger type staking",
                )
                val failed = newService().getLocalInceptionDisplayInfo()
                failed.status shouldBe InceptionDisplayStatus.APPROVED_UNAVAILABLE
                failed.message shouldContain "unsupported ledger type staking"

                repository.setSyncMetadata(
                    SyncMetadataKeys.INCEPTION_RECOVERY_STATUS,
                    InceptionRecoveryStatus.IN_PROGRESS,
                )
                val pending = newService().getLocalInceptionDisplayInfo()
                pending.status shouldBe InceptionDisplayStatus.APPROVED_PENDING
                pending.message shouldBe ViewText.INCEPTION_APPROVED_BASELINE_PENDING
            }
        }

        "display reports an in-progress automatic recovery" {
            repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_STATUS, InceptionRecoveryStatus.IN_PROGRESS)

            val display = newService().getLocalInceptionDisplayInfo()

            display.status shouldBe InceptionDisplayStatus.IN_PROGRESS
            display.dateText.shouldBeNull()
        }

        "a pinned scope change inside the execution session aborts the run" {
            val requestedStart = Instant.parse("2026-01-02T00:00:00Z")
            config = config.copy(settings = config.settings.copy(inceptionDate = requestedStart.toString()))
            val scopeResults = listOf(
                AccountScopeValidationResult(
                    status = AccountScopeValidationStatus.VALID,
                    currentScopeDigest = AccountHistoryScopeGuard.digestAccountScope("fake-account"),
                ),
                AccountScopeValidationResult.scopeUnavailable("credentials rotated mid-run"),
            )
            var call = 0
            coEvery { trustedScopeGuard.validateAccountScope() } coAnswers { scopeResults[call++] }

            val status = newService().recoverOneBoundedRun()

            status.status shouldBe InceptionRecoveryStatus.UNAVAILABLE
            status.reason shouldBe "credentials rotated mid-run"
        }

        "manual display with an untrusted scope withholds inference evidence" {
            config = config.copy(settings = config.settings.copy(inceptionDate = "2026-01-01T00:00:00Z"))
            coEvery { trustedScopeGuard.readLocalTrustState() } returns
                AccountScopeValidationResult.scopeMismatch("rotated-account-digest")

            val display = newService().getLocalInceptionDisplayInfo()

            display.inferredStartText.shouldBeNull()
            display.status shouldBe InceptionDisplayStatus.APPROVED_PENDING
        }

        "default constructor arguments still drive a bounded recovery run" {
            val defaultGuardService = InceptionRecoveryService(
                repository = repository,
                ledgerRepository = ledgerRepository,
                krakenService = krakenService,
                configService = configService,
                tradeHistorySyncService = tradeHistorySyncService,
                nowProvider = { now },
            )

            val status = defaultGuardService.recoverOneBoundedRun()

            status.status.shouldNotBeNull()
        }

        "a failing trade history request marks the recovery failed and stays resumable" {
            config = config.copy(settings = config.settings.copy(inceptionDate = ""))
            krakenService.tradeHistorySupplier = { _, _ -> throw RuntimeException("kraken down") }

            val failed = newService().recoverOneBoundedRun()

            failed.status shouldBe InceptionRecoveryStatus.FAILED
            failed.reason shouldBe "history request failed"

            now = now.plusSeconds(301)
            krakenService.tradeHistorySupplier = { _, _ -> emptyList() }
            val retried = newService().recoverOneBoundedRun()
            retried.status.shouldNotBeNull()
        }

        "an approved baseline id pointing at a vanished snapshot is refreshed" {
            val requestedStart = Instant.parse("2026-01-02T00:00:00Z")
            config = appConfig(listOf(Allocation(Asset.BTC, 50.0), Allocation(Asset.USD, 50.0)))
            config = config.copy(settings = config.settings.copy(inceptionDate = requestedStart.toString()))
            repository.saveTrade(apiTrade("price", requestedStart.minusSeconds(60)))
            repository.saveTrade(apiTrade("bot", requestedStart.plusSeconds(60)))
            repository.saveSnapshot(
                anchorSnapshot(
                    mapOf(Asset.BTC to BigDecimal("0.03"), Asset.USD to BigDecimal("999.00")),
                    timestamp = Instant.parse("2026-01-03T00:00:00Z"),
                ),
            )
            krakenService.tradeHistoryTotalCountOverride = 2
            newService().recoverOneBoundedRun()
            repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_STATUS, "")
            repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_APPROVED_BASELINE_SNAPSHOT_ID, "999")

            now = now.plusSeconds(301)
            val rerun = newService().recoverOneBoundedRun()

            rerun.status shouldBe InceptionRecoveryStatus.CONFIRMED
            val refreshed = repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_APPROVED_BASELINE_SNAPSHOT_ID)
            refreshed.shouldNotBeNull()
            refreshed shouldNotBe "999"
        }

        "approved start adopts the exact duplicate-timestamp row rather than the first row" {
            runTest {
                val requestedStart = Instant.parse("2026-01-01T00:00:00Z")
                config = config.copy(settings = config.settings.copy(inceptionDate = requestedStart.toString()))
                val nonExact = anchorSnapshot(
                    balances = mapOf(Asset.BTC to BigDecimal("0.50"), Asset.USD to BigDecimal("500.00")),
                    timestamp = requestedStart,
                ).copy(balancesObservedAt = requestedStart.plusSeconds(1))
                val exact = anchorSnapshot(
                    balances = mapOf(Asset.BTC to BigDecimal("0.49"), Asset.USD to BigDecimal("501.01")),
                    timestamp = requestedStart,
                )
                repository.saveSnapshot(nonExact)
                val exactId = repository.saveSnapshot(exact)

                val status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.CONFIRMED
                repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_APPROVED_BASELINE_SNAPSHOT_ID) shouldBe
                    exactId.toString()
            }
        }

        "an approved baseline snapshot is re-adopted after a status reset" {
            val requestedStart = Instant.parse("2026-01-02T00:00:00Z")
            config = appConfig(listOf(Allocation(Asset.BTC, 50.0), Allocation(Asset.USD, 50.0)))
            config = config.copy(settings = config.settings.copy(inceptionDate = requestedStart.toString()))
            repository.saveTrade(apiTrade("price", requestedStart.minusSeconds(60)))
            repository.saveTrade(apiTrade("bot", requestedStart.plusSeconds(60)))
            repository.saveTrade(apiTrade("bot2", requestedStart.plusSeconds(60)))
            val anchor =
                anchorSnapshot(
                    mapOf(Asset.BTC to BigDecimal("0.03"), Asset.USD to BigDecimal("999.00")),
                    timestamp = Instant.parse("2026-01-03T00:00:00Z"),
                )
            repository.saveSnapshot(anchor)
            krakenService.tradeHistoryTotalCountOverride = 2

            val firstRun = newService().recoverOneBoundedRun()
            firstRun.status shouldBe InceptionRecoveryStatus.CONFIRMED
            val baselineId = repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_APPROVED_BASELINE_SNAPSHOT_ID)

            repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_STATUS, "")
            repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_REASON, "")

            now = now.plusSeconds(301)
            val secondRun = newService().recoverOneBoundedRun()

            secondRun.status shouldBe InceptionRecoveryStatus.CONFIRMED
            repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_APPROVED_BASELINE_SNAPSHOT_ID) shouldBe baselineId
            repository.getSnapshotsInRange(Instant.EPOCH, now).size shouldBe 2
        }

        "confirmed baseline is invalidated when replay semantics change" {
            val requestedStart = Instant.parse("2026-01-02T00:00:00Z")
            config = appConfig(listOf(Allocation(Asset.BTC, 50.0), Allocation(Asset.USD, 50.0)))
            config = config.copy(settings = config.settings.copy(inceptionDate = requestedStart.toString()))
            repository.saveTrade(apiTrade("price", requestedStart.minusSeconds(60)))
            repository.saveTrade(apiTrade("bot", requestedStart.plusSeconds(60)))
            repository.saveSnapshot(
                anchorSnapshot(
                    mapOf(Asset.BTC to BigDecimal("0.03"), Asset.USD to BigDecimal("999.00")),
                    timestamp = Instant.parse("2026-01-03T00:00:00Z"),
                ),
            )
            krakenService.tradeHistoryTotalCountOverride = 2
            val recoveredLedgerTime = requestedStart.minusSeconds(30)
            krakenService.seedLedgerEntries(
                listOf(
                    LedgerEvent(
                        ledgerId = "zero-untracked",
                        time = recoveredLedgerTime,
                        type = KrakenApiConstants.LEDGER_TYPE_ADJUSTMENT,
                        asset = Asset.ETH,
                        amount = BigDecimal.ZERO,
                        balance = BigDecimal.ZERO,
                        hasAuthoritativeBalance = true,
                    ),
                ),
            )

            val firstRun = newService().recoverOneBoundedRun()
            firstRun.status shouldBe InceptionRecoveryStatus.CONFIRMED
            val baselineId = repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_APPROVED_BASELINE_SNAPSHOT_ID)
            val initialTradeCalls = krakenService.getTradeHistoryCallCount
            val initialLedgerCalls = krakenService.getLedgersCallCount
            val completedTradeMetadata = listOf(
                SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_TOTAL,
                SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_OLDEST_EPOCH_MS,
            ).associateWith { key -> repository.getSyncMetadata(key) }
            val completedLedgerMetadata = listOf(
                SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_TOTAL,
                SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_OLDEST_EPOCH_MS,
            ).associateWith { key -> ledgerRepository.getSyncMetadata(key) }
            completedTradeMetadata[SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_TOTAL] shouldBe "2"
            completedTradeMetadata[SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_OLDEST_EPOCH_MS] shouldBe
                requestedStart.minusSeconds(60).toEpochMilli().toString()
            completedLedgerMetadata[SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_TOTAL] shouldBe "1"
            completedLedgerMetadata[SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_OLDEST_EPOCH_MS] shouldBe
                recoveredLedgerTime.toEpochMilli().toString()
            repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_STATUS) shouldBe "COMPLETE"
            repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_OFFSET) shouldBe "completed"
            ledgerRepository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_STATUS) shouldBe "COMPLETE"
            ledgerRepository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_OFFSET) shouldBe "completed"

            repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_BASELINE_REPLAY_VERSION, "2")
            newService().getStatus().status shouldBe InceptionRecoveryStatus.IN_PROGRESS
            now = now.plusSeconds(301)

            val rerun = newService().recoverOneBoundedRun()

            rerun.status shouldBe InceptionRecoveryStatus.CONFIRMED
            repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_BASELINE_REPLAY_VERSION) shouldBe
                InceptionRecoveryService.CURRENT_BASELINE_REPLAY_VERSION
            repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_APPROVED_BASELINE_SNAPSHOT_ID) shouldBe baselineId
            repository.getSnapshotsInRange(Instant.EPOCH, now).size shouldBe 2
            krakenService.getTradeHistoryCallCount shouldBe initialTradeCalls
            krakenService.getLedgersCallCount shouldBe initialLedgerCalls
            repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_STATUS) shouldBe "COMPLETE"
            repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_OFFSET) shouldBe "completed"
            ledgerRepository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_STATUS) shouldBe "COMPLETE"
            ledgerRepository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_OFFSET) shouldBe "completed"
            completedTradeMetadata.forEach { (key, value) -> repository.getSyncMetadata(key) shouldBe value }
            completedLedgerMetadata.forEach { (key, value) -> ledgerRepository.getSyncMetadata(key) shouldBe value }
        }

        "healthy incomplete recovery uses short continuation interval" {
            runTest {
                val history = (0 until 500).map { index ->
                    apiTrade(
                        id = "history-$index",
                        timestamp = Instant.parse("2026-04-01T00:00:00Z").minusSeconds(index.toLong()),
                    )
                }
                krakenService.tradeHistoryTotalCountOverride = history.size
                krakenService.tradeHistorySupplier = { _, offset ->
                    history.drop(offset ?: 0).take(50)
                }

                val status = newService().recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.IN_PROGRESS
                status.reason shouldBe InceptionRecoveryService.RECOVERY_REASON_BOUNDED_CONTINUATION
                status.tradeOffset shouldBe "200"
                krakenService.getTradeHistoryCallCount shouldBe 4

                // Before short continuation interval expires (e.g. 15s): blocked, no new calls
                now = now.plusSeconds(15)
                val blocked = newService().recoverOneBoundedRun()
                blocked.status shouldBe InceptionRecoveryStatus.IN_PROGRESS
                blocked.tradeOffset shouldBe "200"
                krakenService.getTradeHistoryCallCount shouldBe 4

                // At 29s (still < 30s): blocked
                now = now.plusSeconds(14)
                val stillBlocked = newService().recoverOneBoundedRun()
                stillBlocked.tradeOffset shouldBe "200"
                krakenService.getTradeHistoryCallCount shouldBe 4

                // At 31s (> 30s but well before 300s failure retry): permitted to continue!
                now = now.plusSeconds(2)
                val continued = newService().recoverOneBoundedRun()
                continued.status shouldBe InceptionRecoveryStatus.IN_PROGRESS
                continued.reason shouldBe InceptionRecoveryService.RECOVERY_REASON_BOUNDED_CONTINUATION
                continued.tradeOffset shouldBe "350"
                krakenService.getTradeHistoryCallCount shouldBe 8
            }
        }

        "failure retains conservative retry delay" {
            runTest {
                krakenService.tradeHistorySupplier = { _, _ -> throw IllegalStateException("transient failure") }

                val failed = newService().recoverOneBoundedRun()

                failed.status shouldBe InceptionRecoveryStatus.FAILED
                failed.reason shouldBe "history request failed"
                val initialCallCount = krakenService.getTradeHistoryCallCount

                // Make service healthy now
                krakenService.tradeHistorySupplier = { _, _ -> emptyList() }
                krakenService.tradeHistoryTotalCountOverride = 0

                // At 31s (> short continuation interval 30s, but < failure retry 300s): must remain blocked!
                now = now.plusSeconds(InceptionRecoveryService.SUCCESSFUL_CONTINUATION_INTERVAL_SECONDS + 1)
                val blockedAfter31s = newService().recoverOneBoundedRun()
                blockedAfter31s.status shouldBe InceptionRecoveryStatus.FAILED
                krakenService.getTradeHistoryCallCount shouldBe initialCallCount

                // At 299s: still blocked
                now = now.plusSeconds(268)
                val blockedAt299s = newService().recoverOneBoundedRun()
                blockedAt299s.status shouldBe InceptionRecoveryStatus.FAILED
                krakenService.getTradeHistoryCallCount shouldBe initialCallCount

                // At 301s (> failure retry interval 300s): retry is now permitted!
                now = now.plusSeconds(2)
                val retried = newService().recoverOneBoundedRun()
                retried.status shouldBe InceptionRecoveryStatus.COMPLETE_NO_BOT_EVIDENCE
                krakenService.getTradeHistoryCallCount shouldBe initialCallCount + 1
            }
        }

        "restart during incomplete recovery resumes with intentional one-page overlap" {
            runTest {
                // Simulate persisted progress: stored offset = 500, stream status = IN_PROGRESS, healthy incomplete
                newService().prepareForCurrentConfiguration(config.settings) shouldBe true
                repository.setSyncMetadata(
                    SyncMetadataKeys.INCEPTION_RECOVERY_STATUS,
                    InceptionRecoveryStatus.IN_PROGRESS,
                )
                repository.setSyncMetadata(
                    SyncMetadataKeys.INCEPTION_RECOVERY_REASON,
                    InceptionRecoveryService.RECOVERY_REASON_BOUNDED_CONTINUATION,
                )
                repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_STATUS, "IN_PROGRESS")
                repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_OFFSET, "500")
                repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_TOTAL, "3455")
                ledgerRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_STATUS, "COMPLETE")
                ledgerRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_OFFSET, "completed")
                ledgerRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_TOTAL, "100")
                repository.setSyncMetadata(
                    SyncMetadataKeys.INCEPTION_RECOVERY_LAST_ATTEMPT_EPOCH_SEC,
                    now.minusSeconds(60).epochSecond.toString(),
                )

                var requestedOffset: Int? = null
                krakenService.tradeHistoryTotalCountOverride = 3455
                krakenService.tradeHistorySupplier = { _, ofs ->
                    if (requestedOffset == null) requestedOffset = ofs
                    (0 until 50).map { i ->
                        apiTrade("t-$i", now.minusSeconds(i.toLong()))
                    }
                }

                // Execute bounded run
                val result = newService().recoverOneBoundedRun()

                // Stored offset 500 minus page size 50 = resume offset 450
                requestedOffset shouldBe 450
                result.status shouldBe InceptionRecoveryStatus.IN_PROGRESS
                result.reason shouldBe InceptionRecoveryService.RECOVERY_REASON_BOUNDED_CONTINUATION
                krakenService.getTradeHistoryCallCount shouldBe 4
                repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_OFFSET) shouldBe "650"
            }
        }

        "completed streams do not repaginate when continuation cadence elapses" {
            runTest {
                // Trades already complete, but ledgers incomplete (healthy continuation)
                newService().prepareForCurrentConfiguration(config.settings) shouldBe true
                repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_STATUS, "COMPLETE")
                repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_OFFSET, "completed")
                repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_TOTAL, "100")
                ledgerRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_STATUS, "IN_PROGRESS")
                ledgerRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_OFFSET, "50")
                ledgerRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_TOTAL, "500")
                repository.setSyncMetadata(
                    SyncMetadataKeys.INCEPTION_RECOVERY_STATUS,
                    InceptionRecoveryStatus.IN_PROGRESS,
                )
                repository.setSyncMetadata(
                    SyncMetadataKeys.INCEPTION_RECOVERY_REASON,
                    InceptionRecoveryService.RECOVERY_REASON_BOUNDED_CONTINUATION,
                )
                repository.setSyncMetadata(
                    SyncMetadataKeys.INCEPTION_RECOVERY_LAST_ATTEMPT_EPOCH_SEC,
                    now.epochSecond.toString(),
                )

                krakenService.ledgerSupplier = { _, _, _, _ ->
                    (0 until 50).map { i ->
                        LedgerEvent(
                            ledgerId = "l-$i",
                            time = now.minusSeconds(i.toLong()),
                            type = "staking",
                            asset = Asset.BTC,
                            amount = BigDecimal.ZERO,
                        )
                    }
                }

                val initialTradeCalls = krakenService.getTradeHistoryCallCount
                val initialLedgerCalls = krakenService.getLedgersCallCount

                // Advance past short continuation interval (30s)
                now = now.plusSeconds(InceptionRecoveryService.SUCCESSFUL_CONTINUATION_INTERVAL_SECONDS + 1)
                val status = newService().recoverOneBoundedRun()

                // Healthy continuation ran: completed trade stream was skipped, incomplete ledger stream paginated
                status.status shouldBe InceptionRecoveryStatus.IN_PROGRESS
                krakenService.getTradeHistoryCallCount shouldBe initialTradeCalls
                krakenService.getLedgersCallCount shouldBe (initialLedgerCalls + 4)

                // Once both streams are complete, even past failure retry interval, neither repaginates
                ledgerRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_STATUS, "COMPLETE")
                ledgerRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_OFFSET, "completed")
                repository.setSyncMetadata(
                    SyncMetadataKeys.INCEPTION_RECOVERY_STATUS,
                    InceptionRecoveryStatus.COMPLETE_NO_BOT_EVIDENCE,
                )
                now = now.plusSeconds(InceptionRecoveryService.FAILURE_RETRY_INTERVAL_SECONDS + 1)
                val statusAfter300s = newService().recoverOneBoundedRun()
                statusAfter300s.status shouldBe InceptionRecoveryStatus.COMPLETE_NO_BOT_EVIDENCE
                krakenService.getTradeHistoryCallCount shouldBe initialTradeCalls
                krakenService.getLedgersCallCount shouldBe (initialLedgerCalls + 4)
            }
        }

        "classifyRecoveryCadence returns CONTINUATION only for healthy incomplete recovery" {
            runTest {
                val service = newService()

                // NOT_STARTED -> RETRY
                service.classifyRecoveryCadence(
                    InceptionRecoveryStatus(status = InceptionRecoveryStatus.NOT_STARTED),
                ) shouldBe InceptionRecoveryService.RecoveryCadence.RETRY

                // FAILED -> RETRY
                service.classifyRecoveryCadence(
                    InceptionRecoveryStatus(status = InceptionRecoveryStatus.FAILED),
                ) shouldBe InceptionRecoveryService.RecoveryCadence.RETRY

                // UNAVAILABLE -> RETRY
                service.classifyRecoveryCadence(
                    InceptionRecoveryStatus(status = InceptionRecoveryStatus.UNAVAILABLE),
                ) shouldBe InceptionRecoveryService.RecoveryCadence.RETRY

                // IN_PROGRESS with blank reason (e.g. crash mid-run) -> RETRY
                service.classifyRecoveryCadence(
                    InceptionRecoveryStatus(status = InceptionRecoveryStatus.IN_PROGRESS, reason = ""),
                ) shouldBe InceptionRecoveryService.RecoveryCadence.RETRY

                // IN_PROGRESS with "bounded recovery continues" and both streams IN_PROGRESS -> CONTINUATION
                repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_STATUS, "IN_PROGRESS")
                ledgerRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_STATUS, "IN_PROGRESS")
                service.classifyRecoveryCadence(
                    InceptionRecoveryStatus(
                        status = InceptionRecoveryStatus.IN_PROGRESS,
                        reason = InceptionRecoveryService.RECOVERY_REASON_BOUNDED_CONTINUATION,
                    ),
                ) shouldBe InceptionRecoveryService.RecoveryCadence.CONTINUATION

                // Trade COMPLETE, ledger IN_PROGRESS -> CONTINUATION
                repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_STATUS, "COMPLETE")
                ledgerRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_STATUS, "IN_PROGRESS")
                service.classifyRecoveryCadence(
                    InceptionRecoveryStatus(
                        status = InceptionRecoveryStatus.IN_PROGRESS,
                        reason = InceptionRecoveryService.RECOVERY_REASON_BOUNDED_CONTINUATION,
                    ),
                ) shouldBe InceptionRecoveryService.RecoveryCadence.CONTINUATION

                // Trade IN_PROGRESS, ledger COMPLETE -> CONTINUATION
                repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_STATUS, "IN_PROGRESS")
                ledgerRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_STATUS, "COMPLETE")
                service.classifyRecoveryCadence(
                    InceptionRecoveryStatus(
                        status = InceptionRecoveryStatus.IN_PROGRESS,
                        reason = InceptionRecoveryService.RECOVERY_REASON_BOUNDED_CONTINUATION,
                    ),
                ) shouldBe InceptionRecoveryService.RecoveryCadence.CONTINUATION

                // If trade stream failed -> RETRY
                repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_STATUS, "FAILED")
                service.classifyRecoveryCadence(
                    InceptionRecoveryStatus(
                        status = InceptionRecoveryStatus.IN_PROGRESS,
                        reason = InceptionRecoveryService.RECOVERY_REASON_BOUNDED_CONTINUATION,
                    ),
                ) shouldBe InceptionRecoveryService.RecoveryCadence.RETRY

                // Reset trade stream to IN_PROGRESS, but ledger stream failed -> RETRY
                repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_STATUS, "IN_PROGRESS")
                ledgerRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_STATUS, "FAILED")
                service.classifyRecoveryCadence(
                    InceptionRecoveryStatus(
                        status = InceptionRecoveryStatus.IN_PROGRESS,
                        reason = InceptionRecoveryService.RECOVERY_REASON_BOUNDED_CONTINUATION,
                    ),
                ) shouldBe InceptionRecoveryService.RecoveryCadence.RETRY

                // Both streams COMPLETE -> RETRY
                repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_STATUS, "COMPLETE")
                ledgerRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_STATUS, "COMPLETE")
                service.classifyRecoveryCadence(
                    InceptionRecoveryStatus(
                        status = InceptionRecoveryStatus.IN_PROGRESS,
                        reason = InceptionRecoveryService.RECOVERY_REASON_BOUNDED_CONTINUATION,
                    ),
                ) shouldBe InceptionRecoveryService.RecoveryCadence.RETRY
            }
        }
    }

    private data class TransferLeg(
        val id: String,
        val amount: String,
        val balance: String,
        val secondsAfterStart: Long = 60,
    )

    private suspend fun recoverApprovedInternalTransferBaseline(
        asset: String,
        allocations: List<Allocation>,
        anchorBalances: Map<String, BigDecimal>,
        subtype: String,
        expectedBaseline: BigDecimal,
        legs: List<TransferLeg>,
    ): PortfolioSnapshot {
        val requestedStart = Instant.parse("2026-01-02T00:00:00Z")
        config = appConfig(allocations).copy(
            settings = config.settings.copy(inceptionDate = requestedStart.toString()),
        )
        if (!Asset(asset).isUsd) {
            repository.saveTrade(
                apiTrade(
                    id = "price-$subtype",
                    timestamp = requestedStart,
                    symbol = asset,
                    volume = BigDecimal.ONE,
                    usdAmount = BigDecimal("100.00"),
                    fee = BigDecimal.ZERO,
                ),
            )
        }
        repository.saveSnapshot(
            anchorSnapshot(
                balances = anchorBalances,
                timestamp = requestedStart.plusSeconds(120),
            ),
        )
        val refid = "internal-transfer-$subtype"
        val events = legs.map { leg ->
            LedgerEvent(
                ledgerId = leg.id,
                refid = refid,
                time = requestedStart.plusSeconds(leg.secondsAfterStart),
                type = KrakenApiConstants.LEDGER_TYPE_TRANSFER,
                subtype = subtype,
                asset = asset,
                amount = BigDecimal(leg.amount),
                balance = BigDecimal(leg.balance),
                hasAuthoritativeBalance = true,
            )
        }
        val validation = AuthoritativeLedgerBalanceValidator.validate(events)
        validation.isValid shouldBe true
        validation.resolvedScopes.keys shouldBe events.map(LedgerEvent::ledgerId).toSet()
        validation.resolvedScopes.values.size shouldBe events.size
        LedgerFlowClassifier.classifyAll(events).values.toSet() shouldBe setOf(FlowCategory.INTERNAL_MOVE)
        events.any(LedgerEvent::isRewardEvent) shouldBe false

        krakenService.tradeHistoryTotalCountOverride = 0
        krakenService.tradeHistorySupplier = { _, _ -> emptyList() }
        krakenService.seedLedgerEntries(events)
        val status = newService().recoverOneBoundedRun()

        status.status shouldBe InceptionRecoveryStatus.CONFIRMED
        val baselineId = requireNotNull(
            repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_APPROVED_BASELINE_SNAPSHOT_ID)?.toIntOrNull(),
        )
        val baseline = requireNotNull(repository.getSnapshotById(baselineId))
        baseline.assets.getValue(asset).balance shouldBeEqualComparingTo expectedBaseline
        baseline.drawdownPercent shouldBeEqualComparingTo BigDecimal.ZERO
        baseline.fiatDeploymentPercent shouldBeEqualComparingTo BigDecimal.ZERO
        baseline.actions shouldBe emptyList()
        return baseline
    }

    private suspend fun recoverApprovedScopedLedgerBaseline(
        asset: String,
        allocations: List<Allocation>,
        anchorBalances: Map<String, BigDecimal>,
        events: List<LedgerEvent>,
        expectedBaseline: BigDecimal,
        expectedScopes: Map<String, AuthoritativeLedgerBalanceValidator.LedgerWalletScope> = emptyMap(),
    ): PortfolioSnapshot {
        val requestedStart = Instant.parse("2026-01-02T00:00:00Z")
        config = appConfig(allocations).copy(
            settings = config.settings.copy(inceptionDate = requestedStart.toString()),
        )
        if (!Asset(asset).isUsd) {
            repository.saveTrade(
                apiTrade(
                    id = "price-scoped-${events.first().ledgerId}",
                    timestamp = requestedStart,
                    symbol = asset,
                    volume = BigDecimal.ONE,
                    usdAmount = BigDecimal("100.00"),
                    fee = BigDecimal.ZERO,
                ),
            )
        }
        repository.saveSnapshot(
            anchorSnapshot(
                balances = anchorBalances,
                timestamp = requestedStart.plusSeconds(120),
            ),
        )

        val validation = AuthoritativeLedgerBalanceValidator.validate(events)
        validation.isValid shouldBe true
        events.filter { it.hasAuthoritativeBalance }.map(LedgerEvent::ledgerId).toSet() shouldBe
            validation.resolvedScopes.keys
        expectedScopes.forEach { (ledgerId, scope) ->
            validation.resolvedScopes[ledgerId] shouldBe scope
        }

        krakenService.tradeHistoryTotalCountOverride = 0
        krakenService.tradeHistorySupplier = { _, _ -> emptyList() }
        krakenService.seedLedgerEntries(events)
        val status = newService().recoverOneBoundedRun()

        status.status shouldBe InceptionRecoveryStatus.CONFIRMED
        val baselineId = requireNotNull(
            repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_APPROVED_BASELINE_SNAPSHOT_ID)?.toIntOrNull(),
        )
        val baseline = requireNotNull(repository.getSnapshotById(baselineId))
        baseline.assets.getValue(asset).balance shouldBeEqualComparingTo expectedBaseline
        baseline.drawdownPercent shouldBeEqualComparingTo BigDecimal.ZERO
        baseline.fiatDeploymentPercent shouldBeEqualComparingTo BigDecimal.ZERO
        baseline.actions shouldBe emptyList()
        return baseline
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
        accountHistoryScopeGuard = trustedScopeGuard,
        nowProvider = { now },
    )

    private fun realScopeService(): InceptionRecoveryService = InceptionRecoveryService(
        repository = repository,
        ledgerRepository = ledgerRepository,
        krakenService = krakenService,
        configService = configService,
        tradeHistorySyncService = tradeHistorySyncService,
        accountHistoryScopeGuard = AccountHistoryScopeGuard(
            krakenService = krakenService,
            tradeRepository = repository,
            ledgerRepository = ledgerRepository,
            configService = configService,
        ),
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
