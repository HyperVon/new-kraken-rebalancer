package com.gemini.krakenbot.service

import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.service.impl.history.AccountHistoryScopeGuard
import com.gemini.krakenbot.service.impl.history.AccountScopeValidationResult
import com.gemini.krakenbot.service.impl.history.AccountScopeValidationStatus
import com.gemini.krakenbot.service.impl.history.InceptionDiscoveryService
import com.gemini.krakenbot.service.impl.history.InceptionRecoveryService
import com.gemini.krakenbot.service.impl.history.LedgersSyncService
import com.gemini.krakenbot.service.impl.history.TradeHistoryQueryService
import com.gemini.krakenbot.service.impl.history.TradeHistoryServiceImpl
import com.gemini.krakenbot.service.impl.history.TradeHistorySnapshotStore
import com.gemini.krakenbot.service.impl.history.TradeHistorySyncService
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.time.Instant

class InceptionDisplayInfoTest : TradeHistoryServiceTestBase() {

    private fun testConfig(
        apiKey: String = "key-a",
        privateKey: String = "secret-a",
        inceptionDate: String? = null,
        simulation: Boolean = false,
        allocations: List<Allocation> = listOf(
            Allocation(symbol = Asset("BTC"), targetPercent = 60.0),
            Allocation(symbol = Asset("USD"), targetPercent = 40.0),
        ),
    ): AppConfig = AppConfig(
        kraken = KrakenCredentials(apiKey, privateKey),
        settings = Settings(
            dryRun = true,
            loopDelaySeconds = 60,
            deviationTriggerPercent = 5.0,
            minimumOrderSizeUSD = 10.0,
            fiatMaxDrawdown = 30.0,
            simulation = simulation,
            inceptionDate = inceptionDate,
        ),
        allocations = allocations,
    )

    private fun createRecovery(
        guard: AccountHistoryScopeGuard = mockk(),
        config: AppConfig = testConfig(),
        now: Instant = Instant.parse("2024-03-20T12:00:00Z"),
    ): InceptionRecoveryService {
        every { configService.getConfig() } returns config
        return InceptionRecoveryService(
            repository = repository,
            ledgerRepository = ledgerRepository,
            krakenService = krakenService,
            configService = configService,
            tradeHistorySyncService = mockk(relaxed = true),
            accountHistoryScopeGuard = guard,
            nowProvider = { now },
        )
    }

    private fun createServiceWithRecovery(recovery: InceptionRecoveryService): TradeHistoryServiceImpl =
        TradeHistoryServiceImpl(
            snapshotStore = mockk<TradeHistorySnapshotStore>(relaxed = true),
            queryService = mockk<TradeHistoryQueryService>(relaxed = true),
            syncService = mockk<TradeHistorySyncService>(relaxed = true),
            ledgersSyncService = mockk<LedgersSyncService>(relaxed = true),
            inceptionRecoveryService = recovery,
        )

    init {
        // Scenario A: Confirmed Account A -> credentials switched to Account B
        "getDetectedInceptionDisplayInfo_switchAccount_withholdsStaleDate" {
            runTest {
                val epochMs = Instant.parse("2024-03-15T12:00:00Z").toEpochMilli()
                val guard = mockk<AccountHistoryScopeGuard>()
                coEvery { guard.readLocalTrustState() } returns
                    AccountScopeValidationResult.scopeMismatch("account-b-digest")

                val recovery = createRecovery(guard = guard)
                val service = createServiceWithRecovery(recovery)

                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_EPOCH_MS)
                } returns epochMs.toString()
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_SOURCE)
                } returns InceptionRecoveryService.INCEPTION_SOURCE_AUTO_RECOVERED

                val info = service.getDetectedInceptionDisplayInfo()

                info.status shouldBe InceptionDisplayStatus.UNAVAILABLE
                info.dateText.shouldBeNull()
                info.toDisplayText() shouldBe
                    "Auto-detection unavailable — current account/history trust is unavailable."
                info.toDisplayText() shouldNotContain "2024-03-15"
                coVerify(exactly = 0) { krakenService.getTradeHistory(any(), any()) }
                coVerify(exactly = 0) { krakenService.getBalances() }
            }
        }

        // Scenario B: Confirmed detection -> current account validation busy
        "getDetectedInceptionDisplayInfo_validationPending_returnsPendingImmediately" {
            runTest {
                val epochMs = Instant.parse("2024-03-15T12:00:00Z").toEpochMilli()
                val guard = mockk<AccountHistoryScopeGuard>()
                coEvery { guard.readLocalTrustState() } returns
                    AccountScopeValidationResult(AccountScopeValidationStatus.VALIDATION_PENDING)

                val recovery = createRecovery(guard = guard)
                val service = createServiceWithRecovery(recovery)

                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_EPOCH_MS)
                } returns epochMs.toString()
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_SOURCE)
                } returns InceptionRecoveryService.INCEPTION_SOURCE_AUTO_RECOVERED

                val info = service.getDetectedInceptionDisplayInfo()

                info.status shouldBe InceptionDisplayStatus.VALIDATION_PENDING
                info.dateText.shouldBeNull()
                info.toDisplayText() shouldBe "Auto-detection pending — account validation is in progress."
                info.toDisplayText() shouldNotContain "2024-03-15"
            }
        }

        // Scenario C: Confirmed detection -> config fingerprint changed
        "getDetectedInceptionDisplayInfo_configFingerprintChanged_withholdsStaleDate" {
            runTest {
                val epochMs = Instant.parse("2024-03-15T12:00:00Z").toEpochMilli()
                val guard = mockk<AccountHistoryScopeGuard>()
                coEvery { guard.readLocalTrustState() } returns
                    AccountScopeValidationResult(AccountScopeValidationStatus.VALID, currentScopeDigest = "scope-a")

                val recovery = createRecovery(guard = guard)
                val service = createServiceWithRecovery(recovery)

                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_VERSION)
                } returns InceptionRecoveryService.CURRENT_RECOVERY_VERSION
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_CONFIG_FINGERPRINT)
                } returns "stale-fingerprint-under-old-allocations"
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_EPOCH_MS)
                } returns epochMs.toString()
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_SOURCE)
                } returns InceptionRecoveryService.INCEPTION_SOURCE_AUTO_RECOVERED
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_STATUS)
                } returns InceptionRecoveryStatus.CONFIRMED

                val info = service.getDetectedInceptionDisplayInfo()

                info.status shouldBe InceptionDisplayStatus.UNAVAILABLE
                info.dateText.shouldBeNull()
                info.toDisplayText() shouldBe
                    "Auto-detection unavailable — configuration changed; re-evaluation pending."
                info.toDisplayText() shouldNotContain "2024-03-15"
            }
        }

        // Scenario D: Confirmed detection -> same current account + matching config fingerprint
        "getDetectedInceptionDisplayInfo_matchingAccountAndConfig_returnsConfirmedDate" {
            runTest {
                val epochMs = Instant.parse("2024-03-15T12:00:00Z").toEpochMilli()
                val config = testConfig()
                val guard = mockk<AccountHistoryScopeGuard>()
                coEvery { guard.readLocalTrustState() } returns
                    AccountScopeValidationResult(AccountScopeValidationStatus.VALID, currentScopeDigest = "scope-a")

                val recovery = createRecovery(guard = guard, config = config)
                val expectedFingerprint = recovery.configurationFingerprint(config, "", "scope-a")
                val service = createServiceWithRecovery(recovery)

                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_VERSION)
                } returns InceptionRecoveryService.CURRENT_RECOVERY_VERSION
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_CONFIG_FINGERPRINT)
                } returns expectedFingerprint
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_EPOCH_MS)
                } returns epochMs.toString()
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_SOURCE)
                } returns InceptionRecoveryService.INCEPTION_SOURCE_AUTO_RECOVERED
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_STATUS)
                } returns InceptionRecoveryStatus.CONFIRMED

                val info = service.getDetectedInceptionDisplayInfo()

                info.status shouldBe InceptionDisplayStatus.CONFIRMED
                info.dateText shouldBe "2024-03-15"
                info.source shouldBe "auto-recovered"
                info.toDisplayText() shouldBe
                    "Auto-detected inception: 2024-03-15. Leave this field blank to use the auto-detected date."
            }
        }

        // Scenario E: Unknown detection source
        "getDetectedInceptionDisplayInfo_unknownSource_treatedAsAbsent" {
            runTest {
                val epochMs = Instant.parse("2024-03-15T12:00:00Z").toEpochMilli()
                val config = testConfig()
                val guard = mockk<AccountHistoryScopeGuard>()
                coEvery { guard.readLocalTrustState() } returns
                    AccountScopeValidationResult(AccountScopeValidationStatus.VALID, currentScopeDigest = "scope-a")

                val recovery = createRecovery(guard = guard, config = config)
                val expectedFingerprint = recovery.configurationFingerprint(config, "", "scope-a")
                val service = createServiceWithRecovery(recovery)

                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_VERSION)
                } returns InceptionRecoveryService.CURRENT_RECOVERY_VERSION
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_CONFIG_FINGERPRINT)
                } returns expectedFingerprint
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_EPOCH_MS)
                } returns epochMs.toString()
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_SOURCE)
                } returns "something-new-or-corrupt"
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_STATUS)
                } returns InceptionRecoveryStatus.CONFIRMED

                val info = service.getDetectedInceptionDisplayInfo()

                info.status shouldBe InceptionDisplayStatus.UNAVAILABLE
                info.toDisplayText() shouldBe
                    "Auto-detection unavailable — current account/history trust is unavailable."
                info.dateText.shouldBeNull()
            }
        }

        // Scenario F: Source auto
        "getDetectedInceptionDisplayInfo_autoSource_returnsConfirmed" {
            runTest {
                val epochMs = Instant.parse("2024-03-15T12:00:00Z").toEpochMilli()
                val config = testConfig()
                val guard = mockk<AccountHistoryScopeGuard>()
                coEvery { guard.readLocalTrustState() } returns
                    AccountScopeValidationResult(AccountScopeValidationStatus.VALID, currentScopeDigest = "scope-a")

                val recovery = createRecovery(guard = guard, config = config)
                val expectedFingerprint = recovery.configurationFingerprint(config, "", "scope-a")
                val service = createServiceWithRecovery(recovery)

                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_VERSION)
                } returns InceptionRecoveryService.CURRENT_RECOVERY_VERSION
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_CONFIG_FINGERPRINT)
                } returns expectedFingerprint
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_EPOCH_MS)
                } returns epochMs.toString()
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_SOURCE)
                } returns InceptionDiscoveryService.INCEPTION_SOURCE_AUTO
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_STATUS)
                } returns InceptionRecoveryStatus.CONFIRMED

                val info = service.getDetectedInceptionDisplayInfo()

                info.status shouldBe InceptionDisplayStatus.CONFIRMED
                info.dateText shouldBe "2024-03-15"
                info.source shouldBe "auto"
            }
        }

        // Scenario G: Source auto-recovered
        "getDetectedInceptionDisplayInfo_autoRecoveredSource_returnsConfirmed" {
            runTest {
                val epochMs = Instant.parse("2024-03-15T12:00:00Z").toEpochMilli()
                val config = testConfig()
                val guard = mockk<AccountHistoryScopeGuard>()
                coEvery { guard.readLocalTrustState() } returns
                    AccountScopeValidationResult(AccountScopeValidationStatus.VALID, currentScopeDigest = "scope-a")

                val recovery = createRecovery(guard = guard, config = config)
                val expectedFingerprint = recovery.configurationFingerprint(config, "", "scope-a")
                val service = createServiceWithRecovery(recovery)

                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_VERSION)
                } returns InceptionRecoveryService.CURRENT_RECOVERY_VERSION
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_CONFIG_FINGERPRINT)
                } returns expectedFingerprint
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_EPOCH_MS)
                } returns epochMs.toString()
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_SOURCE)
                } returns InceptionRecoveryService.INCEPTION_SOURCE_AUTO_RECOVERED
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_STATUS)
                } returns InceptionRecoveryStatus.CONFIRMED

                val info = service.getDetectedInceptionDisplayInfo()

                info.status shouldBe InceptionDisplayStatus.CONFIRMED
                info.dateText shouldBe "2024-03-15"
                info.source shouldBe "auto-recovered"
            }
        }

        // Clean-state / first run: storedFingerprint is null/blank
        "getDetectedInceptionDisplayInfo_cleanState_storedFingerprintMissing_returnsNotDetected" {
            runTest {
                val config = testConfig()
                val guard = mockk<AccountHistoryScopeGuard>()
                coEvery { guard.readLocalTrustState() } returns
                    AccountScopeValidationResult(AccountScopeValidationStatus.VALID, currentScopeDigest = "scope-a")

                val recovery = createRecovery(guard = guard, config = config)
                val service = createServiceWithRecovery(recovery)

                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_VERSION)
                } returns null
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_CONFIG_FINGERPRINT)
                } returns null

                val info = service.getDetectedInceptionDisplayInfo()

                info.status shouldBe InceptionDisplayStatus.NOT_DETECTED
                info.toDisplayText() shouldBe "Auto-detected inception: Not yet detected"
                info.dateText.shouldBeNull()
                info.source.shouldBeNull()
            }
        }

        // Scenario H: Source configured
        "getDetectedInceptionDisplayInfo_configuredSource_treatedAsAbsent" {
            runTest {
                val epochMs = Instant.parse("2024-03-15T12:00:00Z").toEpochMilli()
                val config = testConfig()
                val guard = mockk<AccountHistoryScopeGuard>()
                coEvery { guard.readLocalTrustState() } returns
                    AccountScopeValidationResult(AccountScopeValidationStatus.VALID, currentScopeDigest = "scope-a")

                val recovery = createRecovery(guard = guard, config = config)
                val expectedFingerprint = recovery.configurationFingerprint(config, "", "scope-a")
                val service = createServiceWithRecovery(recovery)

                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_VERSION)
                } returns InceptionRecoveryService.CURRENT_RECOVERY_VERSION
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_CONFIG_FINGERPRINT)
                } returns expectedFingerprint
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_EPOCH_MS)
                } returns epochMs.toString()
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_SOURCE)
                } returns "configured"
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_STATUS)
                } returns InceptionRecoveryStatus.NOT_STARTED

                val info = service.getDetectedInceptionDisplayInfo()

                info.status shouldBe InceptionDisplayStatus.NOT_DETECTED
                info.toDisplayText() shouldBe "Auto-detected inception: Not yet detected"
                info.dateText.shouldBeNull()
                info.source.shouldBeNull()
            }
        }

        // Scenario I: AMBIGUOUS recovery state
        "getDetectedInceptionDisplayInfo_ambiguousState_returnsAmbiguousMessage" {
            runTest {
                val config = testConfig()
                val guard = mockk<AccountHistoryScopeGuard>()
                coEvery { guard.readLocalTrustState() } returns
                    AccountScopeValidationResult(AccountScopeValidationStatus.VALID, currentScopeDigest = "scope-a")

                val recovery = createRecovery(guard = guard, config = config)
                val expectedFingerprint = recovery.configurationFingerprint(config, "", "scope-a")
                val service = createServiceWithRecovery(recovery)

                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_VERSION)
                } returns InceptionRecoveryService.CURRENT_RECOVERY_VERSION
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_CONFIG_FINGERPRINT)
                } returns expectedFingerprint
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_EPOCH_MS)
                } returns null
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_SOURCE)
                } returns null
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_STATUS)
                } returns InceptionRecoveryStatus.AMBIGUOUS

                val info = service.getDetectedInceptionDisplayInfo()

                info.status shouldBe InceptionDisplayStatus.AMBIGUOUS
                info.toDisplayText() shouldBe "Auto-detection unavailable — recovered history is ambiguous."
                info.toDisplayText() shouldNotContain "syncing"
            }
        }

        // Scenario J: BASELINE_UNAVAILABLE
        "getDetectedInceptionDisplayInfo_baselineUnavailableState_returnsBaselineMessage" {
            runTest {
                val config = testConfig()
                val guard = mockk<AccountHistoryScopeGuard>()
                coEvery { guard.readLocalTrustState() } returns
                    AccountScopeValidationResult(AccountScopeValidationStatus.VALID, currentScopeDigest = "scope-a")

                val recovery = createRecovery(guard = guard, config = config)
                val expectedFingerprint = recovery.configurationFingerprint(config, "", "scope-a")
                val service = createServiceWithRecovery(recovery)

                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_VERSION)
                } returns InceptionRecoveryService.CURRENT_RECOVERY_VERSION
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_CONFIG_FINGERPRINT)
                } returns expectedFingerprint
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_EPOCH_MS)
                } returns null
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_SOURCE)
                } returns null
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_STATUS)
                } returns InceptionRecoveryStatus.BASELINE_UNAVAILABLE

                val info = service.getDetectedInceptionDisplayInfo()

                info.status shouldBe InceptionDisplayStatus.BASELINE_UNAVAILABLE
                info.toDisplayText() shouldBe
                    "Auto-detection unavailable — a trustworthy historical baseline could not be established."
            }
        }

        // Scenario K: COMPLETE_NO_BOT_EVIDENCE
        "getDetectedInceptionDisplayInfo_completeNoBotEvidenceState_returnsNoBotEvidenceMessage" {
            runTest {
                val config = testConfig()
                val guard = mockk<AccountHistoryScopeGuard>()
                coEvery { guard.readLocalTrustState() } returns
                    AccountScopeValidationResult(AccountScopeValidationStatus.VALID, currentScopeDigest = "scope-a")

                val recovery = createRecovery(guard = guard, config = config)
                val expectedFingerprint = recovery.configurationFingerprint(config, "", "scope-a")
                val service = createServiceWithRecovery(recovery)

                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_VERSION)
                } returns InceptionRecoveryService.CURRENT_RECOVERY_VERSION
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_CONFIG_FINGERPRINT)
                } returns expectedFingerprint
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_EPOCH_MS)
                } returns null
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_SOURCE)
                } returns null
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_STATUS)
                } returns InceptionRecoveryStatus.COMPLETE_NO_BOT_EVIDENCE

                val info = service.getDetectedInceptionDisplayInfo()

                info.status shouldBe InceptionDisplayStatus.COMPLETE_NO_BOT_EVIDENCE
                info.toDisplayText() shouldBe
                    "Auto-detection unavailable — no trustworthy bot inception evidence was found."
            }
        }

        // Scenario L: FAILED
        "getDetectedInceptionDisplayInfo_failedState_returnsFailedMessage" {
            runTest {
                val config = testConfig()
                val guard = mockk<AccountHistoryScopeGuard>()
                coEvery { guard.readLocalTrustState() } returns
                    AccountScopeValidationResult(AccountScopeValidationStatus.VALID, currentScopeDigest = "scope-a")

                val recovery = createRecovery(guard = guard, config = config)
                val expectedFingerprint = recovery.configurationFingerprint(config, "", "scope-a")
                val service = createServiceWithRecovery(recovery)

                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_VERSION)
                } returns InceptionRecoveryService.CURRENT_RECOVERY_VERSION
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_CONFIG_FINGERPRINT)
                } returns expectedFingerprint
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_EPOCH_MS)
                } returns null
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_SOURCE)
                } returns null
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_STATUS)
                } returns InceptionRecoveryStatus.FAILED

                val info = service.getDetectedInceptionDisplayInfo()

                info.status shouldBe InceptionDisplayStatus.FAILED
                info.toDisplayText() shouldBe
                    "Auto-detection temporarily unavailable — history recovery failed and will retry."
            }
        }

        // Scenario M: IN_PROGRESS
        "getDetectedInceptionDisplayInfo_inProgressState_returnsInProgressMessage" {
            runTest {
                val config = testConfig()
                val guard = mockk<AccountHistoryScopeGuard>()
                coEvery { guard.readLocalTrustState() } returns
                    AccountScopeValidationResult(AccountScopeValidationStatus.VALID, currentScopeDigest = "scope-a")

                val recovery = createRecovery(guard = guard, config = config)
                val expectedFingerprint = recovery.configurationFingerprint(config, "", "scope-a")
                val service = createServiceWithRecovery(recovery)

                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_VERSION)
                } returns InceptionRecoveryService.CURRENT_RECOVERY_VERSION
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_CONFIG_FINGERPRINT)
                } returns expectedFingerprint
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_EPOCH_MS)
                } returns null
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_SOURCE)
                } returns null
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_STATUS)
                } returns InceptionRecoveryStatus.IN_PROGRESS

                val info = service.getDetectedInceptionDisplayInfo()

                info.status shouldBe InceptionDisplayStatus.IN_PROGRESS
                info.inProgress shouldBe true
                info.toDisplayText() shouldBe "Auto-detection in progress — syncing Kraken history…"
            }
        }

        // Scenario N: Manual override
        "getDetectedInceptionDisplayInfo_manualOverride_returnsManualOverrideStatus" {
            runTest {
                val config = testConfig(inceptionDate = "2023-01-01")
                val recovery = createRecovery(config = config)
                val service = createServiceWithRecovery(recovery)

                val info = service.getDetectedInceptionDisplayInfo()

                info.status shouldBe InceptionDisplayStatus.MANUAL_OVERRIDE
                info.dateText.shouldBeNull()
                info.toDisplayText() shouldBe "Manual override active."
            }
        }

        // Scenario O: Network trap
        "getDetectedInceptionDisplayInfo_networkTrap_makesZeroNetworkCalls" {
            runTest {
                val config = testConfig()
                val guard = mockk<AccountHistoryScopeGuard>()
                coEvery { guard.readLocalTrustState() } returns
                    AccountScopeValidationResult(AccountScopeValidationStatus.VALID, currentScopeDigest = "scope-a")

                coEvery { krakenService.getTradeHistory(any(), any()) } throws
                    AssertionError("Network call forbidden on display read path!")
                coEvery { krakenService.getLedgers(any(), any(), any(), any()) } throws
                    AssertionError("Network call forbidden on display read path!")
                coEvery { krakenService.getBalances() } throws
                    AssertionError("Network call forbidden on display read path!")

                val recovery = createRecovery(guard = guard, config = config)
                val expectedFingerprint = recovery.configurationFingerprint(config, "", "scope-a")
                val service = createServiceWithRecovery(recovery)

                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_VERSION)
                } returns InceptionRecoveryService.CURRENT_RECOVERY_VERSION
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_CONFIG_FINGERPRINT)
                } returns expectedFingerprint
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_EPOCH_MS)
                } returns Instant.parse("2024-03-15T12:00:00Z").toEpochMilli().toString()
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_SOURCE)
                } returns InceptionRecoveryService.INCEPTION_SOURCE_AUTO_RECOVERED
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_STATUS)
                } returns InceptionRecoveryStatus.CONFIRMED

                val info = service.getDetectedInceptionDisplayInfo()

                info.status shouldBe InceptionDisplayStatus.CONFIRMED
                info.dateText shouldBe "2024-03-15"
                coVerify(exactly = 0) { krakenService.getTradeHistory(any(), any()) }
                coVerify(exactly = 0) { krakenService.getLedgers(any(), any(), any(), any()) }
                coVerify(exactly = 0) { krakenService.getBalances() }
            }
        }

        // Additional edge case: future epoch treated as absent
        "getDetectedInceptionDisplayInfo_futureEpoch_treatedAsAbsent" {
            runTest {
                val now = Instant.parse("2024-03-20T12:00:00Z")
                val futureMs = now.toEpochMilli() + 86_400_000L
                val config = testConfig()
                val guard = mockk<AccountHistoryScopeGuard>()
                coEvery { guard.readLocalTrustState() } returns
                    AccountScopeValidationResult(AccountScopeValidationStatus.VALID, currentScopeDigest = "scope-a")

                val recovery = createRecovery(guard = guard, config = config, now = now)
                val expectedFingerprint = recovery.configurationFingerprint(config, "", "scope-a")
                val service = createServiceWithRecovery(recovery)

                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_VERSION)
                } returns InceptionRecoveryService.CURRENT_RECOVERY_VERSION
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_CONFIG_FINGERPRINT)
                } returns expectedFingerprint
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_EPOCH_MS)
                } returns futureMs.toString()
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_SOURCE)
                } returns "auto"
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_STATUS)
                } returns InceptionRecoveryStatus.CONFIRMED

                val info = service.getDetectedInceptionDisplayInfo()

                info.status shouldBe InceptionDisplayStatus.UNAVAILABLE
                info.toDisplayText() shouldBe
                    "Auto-detection unavailable — current account/history trust is unavailable."
                info.dateText.shouldBeNull()
            }
        }

        // Additional edge case: blank source treated as absent
        "getDetectedInceptionDisplayInfo_blankSource_treatedAsAbsent" {
            runTest {
                val epochMs = Instant.parse("2024-03-15T12:00:00Z").toEpochMilli()
                val config = testConfig()
                val guard = mockk<AccountHistoryScopeGuard>()
                coEvery { guard.readLocalTrustState() } returns
                    AccountScopeValidationResult(AccountScopeValidationStatus.VALID, currentScopeDigest = "scope-a")

                val recovery = createRecovery(guard = guard, config = config)
                val expectedFingerprint = recovery.configurationFingerprint(config, "", "scope-a")
                val service = createServiceWithRecovery(recovery)

                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_VERSION)
                } returns InceptionRecoveryService.CURRENT_RECOVERY_VERSION
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_CONFIG_FINGERPRINT)
                } returns expectedFingerprint
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_EPOCH_MS)
                } returns epochMs.toString()
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_SOURCE)
                } returns "   "
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_STATUS)
                } returns InceptionRecoveryStatus.CONFIRMED

                val info = service.getDetectedInceptionDisplayInfo()

                info.status shouldBe InceptionDisplayStatus.UNAVAILABLE
                info.toDisplayText() shouldBe
                    "Auto-detection unavailable — current account/history trust is unavailable."
                info.dateText.shouldBeNull()
            }
        }

        // Additional edge case: no recovery service wired returns empty
        "getDetectedInceptionDisplayInfo_noRecoveryService_returnsEmpty" {
            runTest {
                val service = createService()
                val info = service.getDetectedInceptionDisplayInfo()

                info.status shouldBe InceptionDisplayStatus.NOT_DETECTED
                info.dateText.shouldBeNull()
                info.source.shouldBeNull()
                info.inProgress shouldBe false
            }
        }

        // Concurrency / A -> B -> A credentials switch
        "getDetectedInceptionDisplayInfo_credentialsSwitchABA_restoresDateOnlyWhenValidAgain" {
            runTest {
                val epochMs = Instant.parse("2024-03-15T12:00:00Z").toEpochMilli()
                val configA = testConfig(apiKey = "key-a", privateKey = "secret-a")
                val configB = testConfig(apiKey = "key-b", privateKey = "secret-b")

                val guard = mockk<AccountHistoryScopeGuard>()
                val recovery = createRecovery(guard = guard, config = configA)
                val expectedFingerprint = recovery.configurationFingerprint(configA, "", "scope-a")
                val service = createServiceWithRecovery(recovery)

                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_VERSION)
                } returns InceptionRecoveryService.CURRENT_RECOVERY_VERSION
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_CONFIG_FINGERPRINT)
                } returns expectedFingerprint
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_EPOCH_MS)
                } returns epochMs.toString()
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_SOURCE)
                } returns InceptionRecoveryService.INCEPTION_SOURCE_AUTO_RECOVERED
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_STATUS)
                } returns InceptionRecoveryStatus.CONFIRMED

                // Step 1: Account A active and trusted
                coEvery { guard.readLocalTrustState() } returns
                    AccountScopeValidationResult(AccountScopeValidationStatus.VALID, currentScopeDigest = "scope-a")
                every { configService.getConfig() } returns configA

                val infoA = service.getDetectedInceptionDisplayInfo()
                infoA.status shouldBe InceptionDisplayStatus.CONFIRMED
                infoA.dateText shouldBe "2024-03-15"

                // Step 2: Credentials switch to Account B -> mismatch
                coEvery { guard.readLocalTrustState() } returns
                    AccountScopeValidationResult.scopeMismatch("scope-b")
                every { configService.getConfig() } returns configB

                val infoB = service.getDetectedInceptionDisplayInfo()
                infoB.status shouldBe InceptionDisplayStatus.UNAVAILABLE
                infoB.dateText.shouldBeNull()

                // Step 3: Credentials switch back to Account A -> valid again
                coEvery { guard.readLocalTrustState() } returns
                    AccountScopeValidationResult(AccountScopeValidationStatus.VALID, currentScopeDigest = "scope-a")
                every { configService.getConfig() } returns configA

                val infoA2 = service.getDetectedInceptionDisplayInfo()
                infoA2.status shouldBe InceptionDisplayStatus.CONFIRMED
                infoA2.dateText shouldBe "2024-03-15"

                // Verify read path performed zero writes
                coVerify(exactly = 0) { repository.setSyncMetadata(any(), any()) }
                coVerify(exactly = 0) { ledgerRepository.setSyncMetadata(any(), any()) }
            }
        }

        // Durable CONFIRMED recovery status requirement tests (Scenarios 1-6 and 10)
        // 1. auto-recovered date + IN_PROGRESS
        "getDetectedInceptionDisplayInfo_autoRecoveredDateWithInProgressStatus_withholdsDate" {
            runTest {
                val epochMs = Instant.parse("2024-03-15T12:00:00Z").toEpochMilli()
                val config = testConfig()
                val guard = mockk<AccountHistoryScopeGuard>()
                coEvery { guard.readLocalTrustState() } returns
                    AccountScopeValidationResult(AccountScopeValidationStatus.VALID, currentScopeDigest = "scope-a")

                val recovery = createRecovery(guard = guard, config = config)
                val expectedFingerprint = recovery.configurationFingerprint(config, "", "scope-a")
                val service = createServiceWithRecovery(recovery)

                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_VERSION)
                } returns InceptionRecoveryService.CURRENT_RECOVERY_VERSION
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_CONFIG_FINGERPRINT)
                } returns expectedFingerprint
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_EPOCH_MS)
                } returns epochMs.toString()
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_SOURCE)
                } returns InceptionRecoveryService.INCEPTION_SOURCE_AUTO_RECOVERED
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_STATUS)
                } returns InceptionRecoveryStatus.IN_PROGRESS

                val info = service.getDetectedInceptionDisplayInfo()

                info.status shouldBe InceptionDisplayStatus.IN_PROGRESS
                info.dateText.shouldBeNull()
                info.toDisplayText() shouldBe "Auto-detection in progress — syncing Kraken history…"
                info.toDisplayText() shouldNotContain "2024-03-15"
            }
        }

        // 2. auto-recovered date + FAILED
        "getDetectedInceptionDisplayInfo_autoRecoveredDateWithFailedStatus_withholdsDate" {
            runTest {
                val epochMs = Instant.parse("2024-03-15T12:00:00Z").toEpochMilli()
                val config = testConfig()
                val guard = mockk<AccountHistoryScopeGuard>()
                coEvery { guard.readLocalTrustState() } returns
                    AccountScopeValidationResult(AccountScopeValidationStatus.VALID, currentScopeDigest = "scope-a")

                val recovery = createRecovery(guard = guard, config = config)
                val expectedFingerprint = recovery.configurationFingerprint(config, "", "scope-a")
                val service = createServiceWithRecovery(recovery)

                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_VERSION)
                } returns InceptionRecoveryService.CURRENT_RECOVERY_VERSION
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_CONFIG_FINGERPRINT)
                } returns expectedFingerprint
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_EPOCH_MS)
                } returns epochMs.toString()
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_SOURCE)
                } returns InceptionRecoveryService.INCEPTION_SOURCE_AUTO_RECOVERED
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_STATUS)
                } returns InceptionRecoveryStatus.FAILED

                val info = service.getDetectedInceptionDisplayInfo()

                info.status shouldBe InceptionDisplayStatus.FAILED
                info.dateText.shouldBeNull()
                info.toDisplayText() shouldBe
                    "Auto-detection temporarily unavailable — history recovery failed and will retry."
                info.toDisplayText() shouldNotContain "2024-03-15"
            }
        }

        // 3. auto-recovered date + AMBIGUOUS
        "getDetectedInceptionDisplayInfo_autoRecoveredDateWithAmbiguousStatus_withholdsDate" {
            runTest {
                val epochMs = Instant.parse("2024-03-15T12:00:00Z").toEpochMilli()
                val config = testConfig()
                val guard = mockk<AccountHistoryScopeGuard>()
                coEvery { guard.readLocalTrustState() } returns
                    AccountScopeValidationResult(AccountScopeValidationStatus.VALID, currentScopeDigest = "scope-a")

                val recovery = createRecovery(guard = guard, config = config)
                val expectedFingerprint = recovery.configurationFingerprint(config, "", "scope-a")
                val service = createServiceWithRecovery(recovery)

                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_VERSION)
                } returns InceptionRecoveryService.CURRENT_RECOVERY_VERSION
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_CONFIG_FINGERPRINT)
                } returns expectedFingerprint
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_EPOCH_MS)
                } returns epochMs.toString()
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_SOURCE)
                } returns InceptionRecoveryService.INCEPTION_SOURCE_AUTO_RECOVERED
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_STATUS)
                } returns InceptionRecoveryStatus.AMBIGUOUS

                val info = service.getDetectedInceptionDisplayInfo()

                info.status shouldBe InceptionDisplayStatus.AMBIGUOUS
                info.dateText.shouldBeNull()
                info.toDisplayText() shouldBe "Auto-detection unavailable — recovered history is ambiguous."
                info.toDisplayText() shouldNotContain "2024-03-15"
            }
        }

        // 4. auto-recovered date + BASELINE_UNAVAILABLE
        "getDetectedInceptionDisplayInfo_autoRecoveredDateWithBaselineUnavailableStatus_withholdsDate" {
            runTest {
                val epochMs = Instant.parse("2024-03-15T12:00:00Z").toEpochMilli()
                val config = testConfig()
                val guard = mockk<AccountHistoryScopeGuard>()
                coEvery { guard.readLocalTrustState() } returns
                    AccountScopeValidationResult(AccountScopeValidationStatus.VALID, currentScopeDigest = "scope-a")

                val recovery = createRecovery(guard = guard, config = config)
                val expectedFingerprint = recovery.configurationFingerprint(config, "", "scope-a")
                val service = createServiceWithRecovery(recovery)

                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_VERSION)
                } returns InceptionRecoveryService.CURRENT_RECOVERY_VERSION
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_CONFIG_FINGERPRINT)
                } returns expectedFingerprint
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_EPOCH_MS)
                } returns epochMs.toString()
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_SOURCE)
                } returns InceptionRecoveryService.INCEPTION_SOURCE_AUTO_RECOVERED
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_STATUS)
                } returns InceptionRecoveryStatus.BASELINE_UNAVAILABLE

                val info = service.getDetectedInceptionDisplayInfo()

                info.status shouldBe InceptionDisplayStatus.BASELINE_UNAVAILABLE
                info.dateText.shouldBeNull()
                info.toDisplayText() shouldBe
                    "Auto-detection unavailable — a trustworthy historical baseline could not be established."
                info.toDisplayText() shouldNotContain "2024-03-15"
            }
        }

        // 5. auto-recovered date + UNAVAILABLE
        "getDetectedInceptionDisplayInfo_autoRecoveredDateWithUnavailableStatus_withholdsDate" {
            runTest {
                val epochMs = Instant.parse("2024-03-15T12:00:00Z").toEpochMilli()
                val config = testConfig()
                val guard = mockk<AccountHistoryScopeGuard>()
                coEvery { guard.readLocalTrustState() } returns
                    AccountScopeValidationResult(AccountScopeValidationStatus.VALID, currentScopeDigest = "scope-a")

                val recovery = createRecovery(guard = guard, config = config)
                val expectedFingerprint = recovery.configurationFingerprint(config, "", "scope-a")
                val service = createServiceWithRecovery(recovery)

                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_VERSION)
                } returns InceptionRecoveryService.CURRENT_RECOVERY_VERSION
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_CONFIG_FINGERPRINT)
                } returns expectedFingerprint
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_EPOCH_MS)
                } returns epochMs.toString()
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_SOURCE)
                } returns InceptionRecoveryService.INCEPTION_SOURCE_AUTO_RECOVERED
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_STATUS)
                } returns InceptionRecoveryStatus.UNAVAILABLE

                val info = service.getDetectedInceptionDisplayInfo()

                info.status shouldBe InceptionDisplayStatus.UNAVAILABLE
                info.dateText.shouldBeNull()
                info.toDisplayText() shouldBe
                    "Auto-detection unavailable — current account/history trust is unavailable."
                info.toDisplayText() shouldNotContain "2024-03-15"
            }
        }

        // 6. auto-recovered date + COMPLETE_NO_BOT_EVIDENCE
        "getDetectedInceptionDisplayInfo_autoRecoveredDateWithCompleteNoBotEvidenceStatus_withholdsDate" {
            runTest {
                val epochMs = Instant.parse("2024-03-15T12:00:00Z").toEpochMilli()
                val config = testConfig()
                val guard = mockk<AccountHistoryScopeGuard>()
                coEvery { guard.readLocalTrustState() } returns
                    AccountScopeValidationResult(AccountScopeValidationStatus.VALID, currentScopeDigest = "scope-a")

                val recovery = createRecovery(guard = guard, config = config)
                val expectedFingerprint = recovery.configurationFingerprint(config, "", "scope-a")
                val service = createServiceWithRecovery(recovery)

                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_VERSION)
                } returns InceptionRecoveryService.CURRENT_RECOVERY_VERSION
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_CONFIG_FINGERPRINT)
                } returns expectedFingerprint
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_EPOCH_MS)
                } returns epochMs.toString()
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_SOURCE)
                } returns InceptionRecoveryService.INCEPTION_SOURCE_AUTO_RECOVERED
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_STATUS)
                } returns InceptionRecoveryStatus.COMPLETE_NO_BOT_EVIDENCE

                val info = service.getDetectedInceptionDisplayInfo()

                info.status shouldBe InceptionDisplayStatus.COMPLETE_NO_BOT_EVIDENCE
                info.dateText.shouldBeNull()
                info.toDisplayText() shouldBe
                    "Auto-detection unavailable — no trustworthy bot inception evidence was found."
                info.toDisplayText() shouldNotContain "2024-03-15"
            }
        }

        // 10. Crash-like partial durable state: valid date/source and fingerprint, but status IN_PROGRESS
        "getDetectedInceptionDisplayInfo_crashLikePartialDurableState_withholdsDateAndDoesNotShowConfirmed" {
            runTest {
                val epochMs = Instant.parse("2024-03-15T12:00:00Z").toEpochMilli()
                val config = testConfig()
                val guard = mockk<AccountHistoryScopeGuard>()
                coEvery { guard.readLocalTrustState() } returns
                    AccountScopeValidationResult(AccountScopeValidationStatus.VALID, currentScopeDigest = "scope-a")

                val recovery = createRecovery(guard = guard, config = config)
                val expectedFingerprint = recovery.configurationFingerprint(config, "", "scope-a")
                val service = createServiceWithRecovery(recovery)

                // Sequence where epoch and source were written, but status remains IN_PROGRESS
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_VERSION)
                } returns InceptionRecoveryService.CURRENT_RECOVERY_VERSION
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_CONFIG_FINGERPRINT)
                } returns expectedFingerprint
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_EPOCH_MS)
                } returns epochMs.toString()
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_SOURCE)
                } returns InceptionRecoveryService.INCEPTION_SOURCE_AUTO_RECOVERED
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_STATUS)
                } returns InceptionRecoveryStatus.IN_PROGRESS

                val info = service.getDetectedInceptionDisplayInfo()

                info.status shouldBe InceptionDisplayStatus.IN_PROGRESS
                info.status shouldNotBe InceptionDisplayStatus.CONFIRMED
                info.dateText.shouldBeNull()
                info.toDisplayText() shouldNotContain "2024-03-15"
                info.toDisplayText() shouldBe "Auto-detection in progress — syncing Kraken history…"
            }
        }
    }
}
