package com.gemini.krakenbot.service

import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.InceptionInferenceEvidence
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
import io.kotest.matchers.nulls.shouldNotBeNull
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
        coEvery {
            repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_BASELINE_REPLAY_VERSION)
        } returns InceptionRecoveryService.CURRENT_BASELINE_REPLAY_VERSION
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

    private fun inferenceRecord(
        fingerprint: String,
        inferredStart: Instant? = null,
        windowStart: Instant? = inferredStart,
        windowEnd: Instant? = inferredStart?.plusSeconds(33),
        firstPositive: Instant? = null,
        strongestObserved: Instant? = null,
        strength: String? = null,
        reasons: List<String> = emptyList(),
        contradictions: List<String> = emptyList(),
        strongestStrength: String? = strength,
        strongestReasons: List<String> = reasons,
        strongestContradictions: List<String> = contradictions,
        competingCandidateCount: Int = 0,
        unsupportedMarketCount: Int = 0,
        unsupportedMarketSamples: List<String> = emptyList(),
        coverageStart: Instant? = null,
        coverageEnd: Instant? = null,
        earliestAmbiguousStart: Instant? = null,
        earlierAmbiguousCandidateCount: Int = 0,
    ): InceptionInferenceEvidence = InceptionInferenceEvidence(
        fingerprint = fingerprint,
        evidenceDigest = "evidence-digest",
        modelVersion = InceptionRecoveryService.CURRENT_INFERENCE_VERSION,
        coverageStart = coverageStart,
        coverageEnd = coverageEnd,
        horizon = null,
        firstPositive = firstPositive,
        inferredStart = inferredStart,
        inferredWindowStart = windowStart,
        inferredWindowEnd = windowEnd,
        inferredStartStrength = strength,
        inferredStartReasons = reasons,
        inferredStartContradictions = contradictions,
        strongestObservedStart = strongestObserved,
        strongestEpisodeStrength = strongestStrength,
        strongestEpisodeReasons = strongestReasons,
        strongestEpisodeContradictions = strongestContradictions,
        earliestAmbiguousStart = earliestAmbiguousStart,
        earlierAmbiguousCandidateCount = earlierAmbiguousCandidateCount,
        unsupportedMarketCount = unsupportedMarketCount,
        unsupportedMarketSamples = unsupportedMarketSamples,
        competingCandidateCount = competingCandidateCount,
    )

    private fun stubInferenceRecord(
        recovery: InceptionRecoveryService,
        config: AppConfig,
        record: InceptionInferenceEvidence,
    ) {
        val fingerprint = recovery.inferenceFingerprint(config, "scope-a")
        coEvery {
            repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_INFERENCE_VERSION)
        } returns InceptionRecoveryService.CURRENT_INFERENCE_VERSION
        coEvery {
            repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_INFERENCE_FINGERPRINT)
        } returns fingerprint
        coEvery { repository.findInceptionInferenceEvidence(fingerprint) } returns record
        coEvery {
            repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_VERSION)
        } returns InceptionRecoveryService.CURRENT_RECOVERY_VERSION
        coEvery {
            repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_CONFIG_FINGERPRINT)
        } returns "stale-confirmed-fingerprint"
    }

    init {
        "inference evidence variants hide only their invalid groups" {
            val guard = mockk<AccountHistoryScopeGuard>()
            coEvery { guard.readLocalTrustState() } returns
                AccountScopeValidationResult(AccountScopeValidationStatus.VALID, currentScopeDigest = "scope-a")
            val recovery = createRecovery(guard = guard)
            val record = inferenceRecord(
                fingerprint = "fp-partial",
                inferredStart = null,
                firstPositive = Instant.parse("2024-03-01T00:00:00Z"),
                earliestAmbiguousStart = Instant.parse("2024-02-01T00:00:00Z"),
                earlierAmbiguousCandidateCount = 1,
            )
            stubInferenceRecord(recovery, testConfig(), record)

            val noStart = recovery.getLocalInceptionDisplayInfo()
            noStart.inferredStartText shouldBe null
            noStart.firstPositiveText shouldBe "2024-03-01T00:00:00Z"
            noStart.earlierAmbiguousCountText shouldBe "1"
            noStart.strongestEpisodeText shouldBe null

            val badWindowRecord = inferenceRecord(
                fingerprint = "fp-bad-window",
                inferredStart = Instant.parse("2024-03-01T00:00:00Z"),
                windowStart = Instant.parse("2024-03-02T00:00:00Z"),
            )
            stubInferenceRecord(recovery, testConfig(), badWindowRecord)
            val badWindow = recovery.getLocalInceptionDisplayInfo()
            badWindow.inferredStartText shouldBe null
            badWindow.inferredWindowEndText shouldBe null

            val sameEpisodeRecord = inferenceRecord(
                fingerprint = "fp-same-episode",
                inferredStart = Instant.parse("2024-03-01T00:00:00Z"),
                windowEnd = Instant.parse("2024-03-01T00:00:33Z"),
                strongestObserved = Instant.parse("2024-03-01T00:00:00Z"),
                strongestStrength = "HIGH",
                strongestReasons = listOf("bot burst"),
            )
            stubInferenceRecord(recovery, testConfig(), sameEpisodeRecord)
            val sameEpisode = recovery.getLocalInceptionDisplayInfo()
            sameEpisode.inferredStartText shouldBe "2024-03-01T00:00:00Z"
            sameEpisode.strongestEpisodeText shouldBe null
            sameEpisode.strongestEpisodeStrengthText shouldBe null
            sameEpisode.strongestEpisodeReasonsText shouldBe null

            val weakStrengthRecord = inferenceRecord(
                fingerprint = "fp-weak",
                inferredStart = Instant.parse("2024-03-01T00:00:00Z"),
                strength = "WEAK",
                reasons = listOf("bot burst"),
                competingCandidateCount = 0,
            )
            stubInferenceRecord(recovery, testConfig(), weakStrengthRecord)
            val weak = recovery.getLocalInceptionDisplayInfo()
            weak.inferredStartText shouldBe "2024-03-01T00:00:00Z"
            weak.inferredStartStrengthText shouldBe null
            weak.inferredStartReasonsText shouldBe "bot burst"
            weak.inferredStartContradictionsText shouldBe null
        }

        "inference evidence with quiet groups and coverage renders nulls for absent lists" {
            val guard = mockk<AccountHistoryScopeGuard>()
            coEvery { guard.readLocalTrustState() } returns
                AccountScopeValidationResult(AccountScopeValidationStatus.VALID, currentScopeDigest = "scope-a")
            val recovery = createRecovery(guard = guard)
            val record = inferenceRecord(
                fingerprint = "fp-quiet",
                inferredStart = Instant.parse("2024-03-01T00:00:00Z"),
                strength = "LOW",
                contradictions = listOf("gap before start"),
                coverageStart = Instant.parse("2024-01-01T00:00:00Z"),
                coverageEnd = Instant.parse("2024-03-20T00:00:00Z"),
                competingCandidateCount = 2,
                unsupportedMarketCount = 1,
                unsupportedMarketSamples = listOf("SOL"),
            )
            stubInferenceRecord(recovery, testConfig(), record)

            val display = recovery.getLocalInceptionDisplayInfo()

            display.inferredStartReasonsText shouldBe null
            display.inferredStartContradictionsText shouldBe "gap before start"
            display.earliestAmbiguousText shouldBe null
            display.earlierAmbiguousCountText shouldBe null
            display.competingCandidatesText.shouldNotBeNull()
            display.unsupportedMarketsText.shouldNotBeNull()
            display.coverageText.shouldNotBeNull()
        }

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
                val expectedFingerprint = recovery.configurationFingerprint(
                    config,
                    config.settings.copy(inceptionDate = "", comparisonStartDate = null),
                    "scope-a",
                )
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

                val recovery = createRecovery(
                    guard = guard,
                    config = config,
                )
                val expectedFingerprint = recovery.configurationFingerprint(
                    config,
                    config.settings.copy(inceptionDate = "", comparisonStartDate = null),
                    "scope-a",
                )
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

        "getDetectedInceptionDisplayInfo_inferredEvidence_isIndependentOfConfirmedRecovery" {
            runTest {
                val config = testConfig()
                val guard = mockk<AccountHistoryScopeGuard>()
                coEvery { guard.readLocalTrustState() } returns
                    AccountScopeValidationResult(AccountScopeValidationStatus.VALID, currentScopeDigest = "scope-a")

                val recovery = createRecovery(
                    guard = guard,
                    config = config,
                    now = Instant.parse("2026-01-01T00:00:00Z"),
                )
                val service = createServiceWithRecovery(recovery)
                val inferredStart = Instant.parse("2025-12-22T02:08:00Z")
                val windowEnd = inferredStart.plusSeconds(33)
                val coverageStart = Instant.parse("2025-12-01T00:00:00Z")
                val record = inferenceRecord(
                    fingerprint = recovery.inferenceFingerprint(config, "scope-a"),
                    inferredStart = inferredStart,
                    windowEnd = windowEnd,
                    strongestObserved = inferredStart.plusSeconds(1500),
                    strength = "MEDIUM",
                    reasons = listOf("MULTI_ASSET_EPISODE", "REDISTRIBUTION_SELL_THEN_BUY"),
                    contradictions = listOf("EARLIER_ACTIVITY_IN_WINDOW"),
                    strongestStrength = "HIGH",
                    strongestReasons = listOf("REDISTRIBUTION_SELL_THEN_BUY"),
                    strongestContradictions = emptyList(),
                    competingCandidateCount = 2,
                    unsupportedMarketCount = 1,
                    unsupportedMarketSamples = listOf("ADAUSDT"),
                    coverageStart = coverageStart,
                    coverageEnd = windowEnd,
                )
                stubInferenceRecord(recovery, config, record)

                val info = service.getDetectedInceptionDisplayInfo()

                info.status shouldBe InceptionDisplayStatus.UNAVAILABLE
                info.dateText.shouldBeNull()
                info.inferredStartText shouldBe inferredStart.toString()
                info.inferredWindowStartText shouldBe inferredStart.toString()
                info.inferredWindowEndText shouldBe windowEnd.toString()
                info.firstPositiveText.shouldBeNull()
                info.inferredStartStrengthText shouldBe "MEDIUM"
                info.inferredStartReasonsText shouldBe "multi asset episode, redistribution sell then buy"
                info.inferredStartContradictionsText shouldBe "earlier activity in window"
                info.strongestEpisodeText shouldBe inferredStart.plusSeconds(1500).toString()
                info.strongestEpisodeStrengthText shouldBe "HIGH"
                info.strongestEpisodeReasonsText shouldBe "redistribution sell then buy"
                info.competingCandidatesText shouldBe "2"
                info.unsupportedMarketsText shouldBe "1 (ADAUSDT)"
                info.coverageText shouldBe "$coverageStart to $windowEnd"
            }
        }

        "getDetectedInceptionDisplayInfo_invalidInferredWindow_isNotDisplayed" {
            runTest {
                val config = testConfig()
                val guard = mockk<AccountHistoryScopeGuard>()
                coEvery { guard.readLocalTrustState() } returns
                    AccountScopeValidationResult(AccountScopeValidationStatus.VALID, currentScopeDigest = "scope-a")

                val recovery = createRecovery(guard = guard, config = config)
                val service = createServiceWithRecovery(recovery)
                val inferredStart = Instant.parse("2025-12-22T02:08:00Z")
                val record = inferenceRecord(
                    fingerprint = recovery.inferenceFingerprint(config, "scope-a"),
                    inferredStart = inferredStart,
                    windowStart = inferredStart.plusSeconds(1),
                    windowEnd = inferredStart,
                    strength = "HIGH",
                )
                stubInferenceRecord(recovery, config, record)

                val info = service.getDetectedInceptionDisplayInfo()

                info.inferredStartText.shouldBeNull()
                info.inferredWindowStartText.shouldBeNull()
                info.inferredWindowEndText.shouldBeNull()
                info.strongestEpisodeText.shouldBeNull()
                info.inferredStartStrengthText.shouldBeNull()
            }
        }

        "getDetectedInceptionDisplayInfo_inferredFingerprintMismatch_hidesInference" {
            runTest {
                val config = testConfig()
                val guard = mockk<AccountHistoryScopeGuard>()
                coEvery { guard.readLocalTrustState() } returns
                    AccountScopeValidationResult(AccountScopeValidationStatus.VALID, currentScopeDigest = "scope-a")

                val recovery = createRecovery(
                    guard = guard,
                    config = config,
                    now = Instant.parse("2026-01-01T00:00:00Z"),
                )
                val service = createServiceWithRecovery(recovery)
                val inferredStart = Instant.parse("2025-12-22T02:08:00Z")
                val record = inferenceRecord(
                    fingerprint = recovery.inferenceFingerprint(config, "scope-a"),
                    inferredStart = inferredStart,
                    firstPositive = inferredStart.minusSeconds(10),
                )
                stubInferenceRecord(recovery, config, record)
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_INFERENCE_FINGERPRINT)
                } returns recovery.inferenceFingerprint(config, "different-scope")

                val info = service.getDetectedInceptionDisplayInfo()

                info.inferredStartText.shouldBeNull()
                info.inferredWindowStartText.shouldBeNull()
                info.inferredWindowEndText.shouldBeNull()
                info.firstPositiveText.shouldBeNull()
            }
        }

        "getDetectedInceptionDisplayInfo_impossibleInferredInstants_areHidden" {
            runTest {
                val config = testConfig()
                val guard = mockk<AccountHistoryScopeGuard>()
                coEvery { guard.readLocalTrustState() } returns
                    AccountScopeValidationResult(AccountScopeValidationStatus.VALID, currentScopeDigest = "scope-a")

                val recovery = createRecovery(
                    guard = guard,
                    config = config,
                    now = Instant.parse("2026-01-01T00:00:00Z"),
                )
                val service = createServiceWithRecovery(recovery)
                val fingerprint = recovery.inferenceFingerprint(config, "scope-a")

                suspend fun expectHidden(record: InceptionInferenceEvidence) {
                    coEvery { repository.findInceptionInferenceEvidence(fingerprint) } returns record

                    val info = service.getDetectedInceptionDisplayInfo()

                    info.inferredStartText.shouldBeNull()
                    info.inferredWindowStartText.shouldBeNull()
                    info.inferredWindowEndText.shouldBeNull()
                    info.firstPositiveText.shouldBeNull()
                }

                expectHidden(inferenceRecord(fingerprint, inferredStart = null))
                expectHidden(inferenceRecord(fingerprint, inferredStart = Instant.EPOCH))
                expectHidden(inferenceRecord(fingerprint, inferredStart = Instant.ofEpochMilli(Long.MIN_VALUE)))
                expectHidden(
                    inferenceRecord(fingerprint, inferredStart = Instant.parse("2026-01-02T00:00:00Z")),
                )
                expectHidden(
                    inferenceRecord(
                        fingerprint,
                        inferredStart = Instant.parse("2025-12-22T02:08:00Z"),
                        windowStart = Instant.parse("2025-12-22T02:08:01Z"),
                        windowEnd = Instant.parse("2025-12-22T02:08:00Z"),
                    ),
                )
            }
        }

        "getDetectedInceptionDisplayInfo_firstPositive_isIndependentOfCandidateEvidence" {
            runTest {
                val config = testConfig()
                val guard = mockk<AccountHistoryScopeGuard>()
                coEvery { guard.readLocalTrustState() } returns
                    AccountScopeValidationResult(AccountScopeValidationStatus.VALID, currentScopeDigest = "scope-a")

                val recovery = createRecovery(
                    guard = guard,
                    config = config,
                    now = Instant.parse("2026-01-01T00:00:00Z"),
                )
                val service = createServiceWithRecovery(recovery)
                val fingerprint = recovery.inferenceFingerprint(config, "scope-a")
                val inferredStart = Instant.parse("2025-12-22T02:08:00Z")
                val firstPositive = inferredStart.minusSeconds(10)

                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_INFERENCE_VERSION)
                } returns InceptionRecoveryService.CURRENT_INFERENCE_VERSION
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_INFERENCE_FINGERPRINT)
                } returns fingerprint

                // Valid first positive alongside valid inference -> both shown.
                coEvery { repository.findInceptionInferenceEvidence(fingerprint) } returns
                    inferenceRecord(fingerprint, inferredStart = inferredStart, firstPositive = firstPositive)
                var info = service.getDetectedInceptionDisplayInfo()
                info.inferredStartText shouldBe inferredStart.toString()
                info.inferredWindowEndText.shouldNotBeNull()
                info.firstPositiveText shouldBe firstPositive.toString()

                // No first positive recorded -> inference unaffected.
                coEvery { repository.findInceptionInferenceEvidence(fingerprint) } returns
                    inferenceRecord(fingerprint, inferredStart = inferredStart)
                info = service.getDetectedInceptionDisplayInfo()
                info.inferredStartText shouldBe inferredStart.toString()
                info.firstPositiveText.shouldBeNull()

                // Invalid (future) first positive hides only itself, not the candidate evidence.
                coEvery { repository.findInceptionInferenceEvidence(fingerprint) } returns
                    inferenceRecord(
                        fingerprint,
                        inferredStart = inferredStart,
                        firstPositive = Instant.parse("2026-06-01T00:00:00Z"),
                    )
                info = service.getDetectedInceptionDisplayInfo()
                info.inferredStartText shouldBe inferredStart.toString()
                info.firstPositiveText.shouldBeNull()

                // First positive alone (no behavioral candidates) is still displayed.
                coEvery { repository.findInceptionInferenceEvidence(fingerprint) } returns
                    inferenceRecord(fingerprint, firstPositive = firstPositive)
                info = service.getDetectedInceptionDisplayInfo()
                info.inferredStartText.shouldBeNull()
                info.inferredWindowStartText.shouldBeNull()
                info.inferredWindowEndText.shouldBeNull()
                info.firstPositiveText shouldBe firstPositive.toString()
            }
        }

        "getDetectedInceptionDisplayInfo_allocationOnlyChange_keepsInferenceVisible" {
            runTest {
                val allocations = listOf(
                    Allocation(symbol = Asset("BTC"), targetPercent = 50.0),
                    Allocation(symbol = Asset("USD"), targetPercent = 50.0),
                )
                val config = testConfig(allocations = allocations)
                val guard = mockk<AccountHistoryScopeGuard>()
                coEvery { guard.readLocalTrustState() } returns
                    AccountScopeValidationResult(AccountScopeValidationStatus.VALID, currentScopeDigest = "scope-a")

                val recovery = createRecovery(
                    guard = guard,
                    config = config,
                    now = Instant.parse("2026-01-01T00:00:00Z"),
                )
                val service = createServiceWithRecovery(recovery)
                val inferredStart = Instant.parse("2025-12-22T02:08:00Z")
                val record = inferenceRecord(
                    fingerprint = recovery.inferenceFingerprint(config, "scope-a"),
                    inferredStart = inferredStart,
                    strength = "MEDIUM",
                )
                stubInferenceRecord(recovery, config, record)

                val info = service.getDetectedInceptionDisplayInfo()

                info.status shouldBe InceptionDisplayStatus.UNAVAILABLE
                info.inferredStartText shouldBe inferredStart.toString()
                info.inferredStartStrengthText shouldBe "MEDIUM"
            }
        }

        "getDetectedInferenceRead_makesNoWritesAndNoNetworkCalls" {
            runTest {
                val config = testConfig()
                val guard = mockk<AccountHistoryScopeGuard>()
                coEvery { guard.readLocalTrustState() } returns
                    AccountScopeValidationResult(AccountScopeValidationStatus.VALID, currentScopeDigest = "scope-a")
                coEvery { krakenService.getTradeHistory(any(), any()) } throws
                    AssertionError("Network call forbidden on inference read path!")
                coEvery { krakenService.getBalances() } throws
                    AssertionError("Network call forbidden on inference read path!")

                val recovery = createRecovery(
                    guard = guard,
                    config = config,
                    now = Instant.parse("2026-01-01T00:00:00Z"),
                )
                val service = createServiceWithRecovery(recovery)
                val inferredStart = Instant.parse("2025-12-22T02:08:00Z")
                val record = inferenceRecord(
                    fingerprint = recovery.inferenceFingerprint(config, "scope-a"),
                    inferredStart = inferredStart,
                    strength = "LOW",
                )
                stubInferenceRecord(recovery, config, record)

                val info = service.getDetectedInceptionDisplayInfo()

                info.inferredStartText shouldBe inferredStart.toString()
                coVerify(exactly = 0) { repository.setSyncMetadata(any(), any()) }
                coVerify(exactly = 0) { ledgerRepository.setSyncMetadata(any(), any()) }
                coVerify(exactly = 0) { repository.setSyncMetadataAtomically(any()) }
                coVerify(exactly = 0) { krakenService.getTradeHistory(any(), any()) }
                coVerify(exactly = 0) { krakenService.getBalances() }
            }
        }

        "getDetectedInceptionDisplayInfo_invalidStrengthText_isHiddenButInferenceRemains" {
            runTest {
                val config = testConfig()
                val guard = mockk<AccountHistoryScopeGuard>()
                coEvery { guard.readLocalTrustState() } returns
                    AccountScopeValidationResult(AccountScopeValidationStatus.VALID, currentScopeDigest = "scope-a")

                val recovery = createRecovery(
                    guard = guard,
                    config = config,
                    now = Instant.parse("2026-01-01T00:00:00Z"),
                )
                val service = createServiceWithRecovery(recovery)
                val inferredStart = Instant.parse("2025-12-22T02:08:00Z")
                val record = inferenceRecord(
                    fingerprint = recovery.inferenceFingerprint(config, "scope-a"),
                    inferredStart = inferredStart,
                    strength = "SECRET",
                    competingCandidateCount = -1,
                )
                stubInferenceRecord(recovery, config, record)

                val info = service.getDetectedInceptionDisplayInfo()

                info.inferredStartStrengthText.shouldBeNull()
                info.competingCandidatesText.shouldBeNull()
                info.inferredStartText shouldBe inferredStart.toString()
            }
        }

        "getDetectedInceptionDisplayInfo_invalidWindowOrCoverageVariants_hideOnlyTheirGroups" {
            runTest {
                val config = testConfig()
                val guard = mockk<AccountHistoryScopeGuard>()
                coEvery { guard.readLocalTrustState() } returns
                    AccountScopeValidationResult(AccountScopeValidationStatus.VALID, currentScopeDigest = "scope-a")

                val recovery = createRecovery(
                    guard = guard,
                    config = config,
                    now = Instant.parse("2026-01-01T00:00:00Z"),
                )
                val service = createServiceWithRecovery(recovery)
                val fingerprint = recovery.inferenceFingerprint(config, "scope-a")
                val inferredStart = Instant.parse("2025-12-22T02:08:00Z")
                val coverageStart = Instant.parse("2025-12-01T00:00:00Z")

                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_INFERENCE_VERSION)
                } returns InceptionRecoveryService.CURRENT_INFERENCE_VERSION
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_INFERENCE_FINGERPRINT)
                } returns fingerprint

                suspend fun assertHiddenGroups(record: InceptionInferenceEvidence) {
                    coEvery { repository.findInceptionInferenceEvidence(fingerprint) } returns record
                    val info = service.getDetectedInceptionDisplayInfo()
                    info.inferredStartText.shouldBeNull()
                    info.inferredWindowStartText.shouldBeNull()
                    info.inferredWindowEndText.shouldBeNull()
                    info.inferredStartStrengthText.shouldBeNull()
                    info.inferredStartReasonsText.shouldBeNull()
                    info.inferredStartContradictionsText.shouldBeNull()
                    info.strongestEpisodeText.shouldBeNull()
                    info.competingCandidatesText.shouldBeNull()
                }

                assertHiddenGroups(
                    inferenceRecord(
                        fingerprint,
                        inferredStart = inferredStart,
                        windowStart = null,
                        windowEnd = inferredStart.plusSeconds(33),
                        strength = "LOW",
                    ),
                )
                assertHiddenGroups(
                    inferenceRecord(
                        fingerprint,
                        inferredStart = inferredStart,
                        windowStart = inferredStart,
                        windowEnd = null,
                        strength = "LOW",
                    ),
                )

                coEvery { repository.findInceptionInferenceEvidence(fingerprint) } returns
                    inferenceRecord(
                        fingerprint,
                        inferredStart = inferredStart,
                        strength = "LOW",
                        coverageStart = coverageStart,
                        coverageEnd = null,
                    )
                var info = service.getDetectedInceptionDisplayInfo()
                info.inferredStartText shouldBe inferredStart.toString()
                info.coverageText.shouldBeNull()

                coEvery { repository.findInceptionInferenceEvidence(fingerprint) } returns
                    inferenceRecord(
                        fingerprint,
                        inferredStart = inferredStart,
                        strength = "LOW",
                        coverageStart = coverageStart.plusSeconds(3_600),
                        coverageEnd = coverageStart,
                    )
                info = service.getDetectedInceptionDisplayInfo()
                info.inferredStartText shouldBe inferredStart.toString()
                info.coverageText.shouldBeNull()

                coEvery { repository.findInceptionInferenceEvidence(fingerprint) } returns
                    inferenceRecord(
                        fingerprint,
                        inferredStart = inferredStart,
                        strength = "LOW",
                        coverageStart = Instant.parse("2026-06-01T00:00:00Z"),
                        coverageEnd = Instant.parse("2026-06-02T00:00:00Z"),
                    )
                info = service.getDetectedInceptionDisplayInfo()
                info.inferredStartText shouldBe inferredStart.toString()
                info.coverageText.shouldBeNull()
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
                val expectedFingerprint = recovery.configurationFingerprint(
                    config,
                    config.settings.copy(inceptionDate = "", comparisonStartDate = null),
                    "scope-a",
                )
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
                val expectedFingerprint = recovery.configurationFingerprint(
                    config,
                    config.settings.copy(inceptionDate = "", comparisonStartDate = null),
                    "scope-a",
                )
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
                val expectedFingerprint = recovery.configurationFingerprint(
                    config,
                    config.settings.copy(inceptionDate = "", comparisonStartDate = null),
                    "scope-a",
                )
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
                val expectedFingerprint = recovery.configurationFingerprint(
                    config,
                    config.settings.copy(inceptionDate = "", comparisonStartDate = null),
                    "scope-a",
                )
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
                val expectedFingerprint = recovery.configurationFingerprint(
                    config,
                    config.settings.copy(inceptionDate = "", comparisonStartDate = null),
                    "scope-a",
                )
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
                val expectedFingerprint = recovery.configurationFingerprint(
                    config,
                    config.settings.copy(inceptionDate = "", comparisonStartDate = null),
                    "scope-a",
                )
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
                val expectedFingerprint = recovery.configurationFingerprint(
                    config,
                    config.settings.copy(inceptionDate = "", comparisonStartDate = null),
                    "scope-a",
                )
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
                val expectedFingerprint = recovery.configurationFingerprint(
                    config,
                    config.settings.copy(inceptionDate = "", comparisonStartDate = null),
                    "scope-a",
                )
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

        // Scenario N: Approved start pending
        "getDetectedInceptionDisplayInfo_approvedStart_returnsApprovedPendingStatus" {
            runTest {
                val config = testConfig(inceptionDate = "2023-01-01")
                val guard = mockk<AccountHistoryScopeGuard>()
                coEvery { guard.readLocalTrustState() } returns
                    AccountScopeValidationResult(AccountScopeValidationStatus.VALID, currentScopeDigest = "scope-a")
                val recovery = createRecovery(guard = guard, config = config)
                val service = createServiceWithRecovery(recovery)

                val info = service.getDetectedInceptionDisplayInfo()

                info.status shouldBe InceptionDisplayStatus.APPROVED_PENDING
                info.dateText.shouldBeNull()
                info.toDisplayText() shouldBe
                    "Approved start saved — establishing the historical baseline. " +
                    "Kraken history recovery is in progress."
            }
        }

        "getDetectedInceptionDisplayInfo_approvedStart_stillShowsHistoricalEvidence" {
            runTest {
                val config = testConfig(inceptionDate = "2023-01-01")
                val guard = mockk<AccountHistoryScopeGuard>()
                coEvery { guard.readLocalTrustState() } returns
                    AccountScopeValidationResult(AccountScopeValidationStatus.VALID, currentScopeDigest = "scope-a")

                val recovery = createRecovery(
                    guard = guard,
                    config = config,
                    now = Instant.parse("2026-01-01T00:00:00Z"),
                )
                val service = createServiceWithRecovery(recovery)
                val inferredStart = Instant.parse("2025-12-22T02:08:00Z")
                val record = inferenceRecord(
                    fingerprint = recovery.inferenceFingerprint(config, "scope-a"),
                    inferredStart = inferredStart,
                    strength = "LOW",
                    earliestAmbiguousStart = inferredStart.minusSeconds(3_600),
                    earlierAmbiguousCandidateCount = 2,
                )
                stubInferenceRecord(recovery, config, record)

                val info = service.getDetectedInceptionDisplayInfo()

                info.status shouldBe InceptionDisplayStatus.APPROVED_PENDING
                info.inferredStartText shouldBe inferredStart.toString()
                info.inferredStartStrengthText shouldBe "LOW"
                info.earliestAmbiguousText shouldBe inferredStart.minusSeconds(3_600).toString()
                info.earlierAmbiguousCountText shouldBe "2"
                info.dateText.shouldBeNull()
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
                val expectedFingerprint = recovery.configurationFingerprint(
                    config,
                    config.settings.copy(inceptionDate = "", comparisonStartDate = null),
                    "scope-a",
                )
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
                val expectedFingerprint = recovery.configurationFingerprint(
                    config,
                    config.settings.copy(inceptionDate = "", comparisonStartDate = null),
                    "scope-a",
                )
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
                val expectedFingerprint = recovery.configurationFingerprint(
                    config,
                    config.settings.copy(inceptionDate = "", comparisonStartDate = null),
                    "scope-a",
                )
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
                val expectedFingerprint = recovery.configurationFingerprint(
                    configA,
                    configA.settings.copy(inceptionDate = "", comparisonStartDate = null),
                    "scope-a",
                )
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
                val inferredStart = Instant.ofEpochMilli(epochMs)
                stubInferenceRecord(
                    recovery,
                    configA,
                    inferenceRecord(
                        recovery.inferenceFingerprint(configA, "scope-a"),
                        inferredStart = inferredStart,
                        windowStart = inferredStart,
                        windowEnd = inferredStart,
                    ),
                )
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_CONFIG_FINGERPRINT)
                } returns expectedFingerprint
                coEvery { guard.readLocalTrustState() } returns
                    AccountScopeValidationResult(AccountScopeValidationStatus.VALID, currentScopeDigest = "scope-a")
                every { configService.getConfig() } returns configA

                val infoA = service.getDetectedInceptionDisplayInfo()
                infoA.status shouldBe InceptionDisplayStatus.CONFIRMED
                infoA.dateText shouldBe "2024-03-15"
                infoA.inferredStartText shouldBe inferredStart.toString()

                // Step 2: Credentials switch to Account B -> mismatch
                coEvery { guard.readLocalTrustState() } returns
                    AccountScopeValidationResult.scopeMismatch("scope-b")
                every { configService.getConfig() } returns configB

                val infoB = service.getDetectedInceptionDisplayInfo()
                infoB.status shouldBe InceptionDisplayStatus.UNAVAILABLE
                infoB.dateText.shouldBeNull()
                infoB.inferredStartText.shouldBeNull()
                infoB.inferredWindowStartText.shouldBeNull()
                infoB.inferredWindowEndText.shouldBeNull()
                infoB.firstPositiveText.shouldBeNull()

                // Step 3: Credentials switch back to Account A -> valid again
                coEvery { guard.readLocalTrustState() } returns
                    AccountScopeValidationResult(AccountScopeValidationStatus.VALID, currentScopeDigest = "scope-a")
                every { configService.getConfig() } returns configA

                val infoA2 = service.getDetectedInceptionDisplayInfo()
                infoA2.status shouldBe InceptionDisplayStatus.CONFIRMED
                infoA2.dateText shouldBe "2024-03-15"
                infoA2.inferredStartText shouldBe inferredStart.toString()

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
                val expectedFingerprint = recovery.configurationFingerprint(
                    config,
                    config.settings.copy(inceptionDate = "", comparisonStartDate = null),
                    "scope-a",
                )
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
                val expectedFingerprint = recovery.configurationFingerprint(
                    config,
                    config.settings.copy(inceptionDate = "", comparisonStartDate = null),
                    "scope-a",
                )
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
                val expectedFingerprint = recovery.configurationFingerprint(
                    config,
                    config.settings.copy(inceptionDate = "", comparisonStartDate = null),
                    "scope-a",
                )
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
                val expectedFingerprint = recovery.configurationFingerprint(
                    config,
                    config.settings.copy(inceptionDate = "", comparisonStartDate = null),
                    "scope-a",
                )
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
                val expectedFingerprint = recovery.configurationFingerprint(
                    config,
                    config.settings.copy(inceptionDate = "", comparisonStartDate = null),
                    "scope-a",
                )
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
                val expectedFingerprint = recovery.configurationFingerprint(
                    config,
                    config.settings.copy(inceptionDate = "", comparisonStartDate = null),
                    "scope-a",
                )
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
                val expectedFingerprint = recovery.configurationFingerprint(
                    config,
                    config.settings.copy(inceptionDate = "", comparisonStartDate = null),
                    "scope-a",
                )
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

        "getInceptionRecoveryStatus_usesRecoveryServiceStatusWhenPresent" {
            runTest {
                val recovery = mockk<InceptionRecoveryService>(relaxed = true)
                coEvery { recovery.getStatus() } returns
                    InceptionRecoveryStatus(status = InceptionRecoveryStatus.CONFIRMED, candidateTime = "2024-03-15")
                val service = createServiceWithRecovery(recovery)

                val status = service.getInceptionRecoveryStatus()

                status.status shouldBe "CONFIRMED"
                status.candidateTime shouldBe "2024-03-15"
            }
        }

        "getInceptionRecoveryStatus_fallsBackToDefaultWhenRecoveryServiceAbsent" {
            runTest {
                val service = TradeHistoryServiceImpl(
                    snapshotStore = mockk<TradeHistorySnapshotStore>(relaxed = true),
                    queryService = mockk<TradeHistoryQueryService>(relaxed = true),
                    syncService = mockk<TradeHistorySyncService>(relaxed = true),
                    ledgersSyncService = mockk<LedgersSyncService>(relaxed = true),
                )

                val status = service.getInceptionRecoveryStatus()

                status.status shouldBe "NOT_STARTED"
                status.candidateTime.shouldBeNull()
            }
        }

        "manualOverride_usesDefaultMessageWhenMessageMissing" {
            val text = InceptionDisplayInfo(status = InceptionDisplayStatus.MANUAL_OVERRIDE).toDisplayText()

            text shouldBe "Manual override active."
        }

        "manualOverride_usesCustomMessageWhenPresent" {
            val text = InceptionDisplayInfo(
                status = InceptionDisplayStatus.MANUAL_OVERRIDE,
                message = "Manual inception applied.",
            ).toDisplayText()

            text shouldBe "Manual inception applied."
        }
    }
}
