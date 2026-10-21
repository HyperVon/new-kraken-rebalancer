package com.gemini.krakenbot.service.history

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.DataDirectoryIdentity
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.DirectBalanceCapture
import com.gemini.krakenbot.service.DirectEvidenceStatus
import com.gemini.krakenbot.service.DirectTickerCapture
import com.gemini.krakenbot.service.DirectTickerMark
import com.gemini.krakenbot.service.DirectValueEvidence
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.service.ObservedBalances
import com.gemini.krakenbot.service.ObservedPrices
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryObservationRecorderTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    private val store = mockk<HistoryStore>(relaxed = true)
    private val krakenService = mockk<KrakenService>(relaxed = true)
    private val configService = mockk<ConfigService>(relaxed = true)
    private val dataDirectoryIdentity = mockk<DataDirectoryIdentity>(relaxed = true)

    private val recorder = HistoryObservationRecorder(
        store = store,
        krakenService = krakenService,
        configService = configService,
        dataDirectoryIdentity = dataDirectoryIdentity,
    )

    init {
        "currentSourceScope returns simulation scope when configured for simulation" {
            val config = TestFixtures.DEFAULT_TEST_CONFIG.let {
                it.copy(settings = it.settings.copy(simulation = true))
            }

            runTest {
                val scope = recorder.currentSourceScope(config)
                scope shouldNotBe null
                scope!!.mode shouldBe HistorySourceMode.SIMULATION
                scope.simulationSessionId shouldNotBe null
            }
        }

        "currentSourceScope resolves the authenticated account after recorder restart" {
            val config = TestFixtures.DEFAULT_TEST_CONFIG.copy(
                settings = TestFixtures.settings(simulation = false, dryRun = true),
            )
            coEvery { krakenService.getFundingEvidenceScope() } returns "restart-credential-scope"
            coEvery { krakenService.getAuthenticatedAccountIdentity() } returns "RESTARTED_ACCOUNT"
            coEvery { krakenService.withStableBackend<Any?>(any()) } coAnswers {
                val block = firstArg<suspend (KrakenService) -> Any?>()
                block(krakenService)
            }
            val restartedRecorder = HistoryObservationRecorder(
                store = store,
                krakenService = krakenService,
                configService = configService,
                dataDirectoryIdentity = dataDirectoryIdentity,
            )

            val scope = restartedRecorder.currentSourceScope(config)

            scope?.mode shouldBe HistorySourceMode.ACCOUNT
            scope?.accountIdentityDigest shouldBe
                java.security.MessageDigest.getInstance("SHA-256")
                    .digest("RESTARTED_ACCOUNT".toByteArray(Charsets.UTF_8))
                    .joinToString("") { byte -> "%02x".format(byte) }
            coVerify { dataDirectoryIdentity.bindOrVerify(any()) }
        }

        "currentSourceScope revalidates credential rotation and managed-asset changes" {
            val config = TestFixtures.DEFAULT_TEST_CONFIG.copy(
                settings = TestFixtures.settings(simulation = false, dryRun = true),
            )
            val changedConfig = config.copy(allocations = config.allocations.take(1))
            coEvery { krakenService.getFundingEvidenceScope() } returnsMany
                listOf("credential-v1", "credential-v2", "credential-v2")
            coEvery { krakenService.getAuthenticatedAccountIdentity() } returns "SAME_ACCOUNT"
            coEvery { krakenService.withStableBackend<Any?>(any()) } coAnswers {
                firstArg<suspend (KrakenService) -> Any?>()(krakenService)
            }

            runTest {
                val originalScope = recorder.currentSourceScope(config)
                val rotatedCredentialScope = recorder.currentSourceScope(config)
                val changedAssetScope = recorder.currentSourceScope(changedConfig)
                val cachedChangedAssetScope = recorder.currentSourceScope(changedConfig)

                originalScope?.accountIdentityDigest shouldBe rotatedCredentialScope?.accountIdentityDigest
                rotatedCredentialScope?.accountIdentityDigest shouldBe changedAssetScope?.accountIdentityDigest
                changedAssetScope?.managedScopeSymbols shouldBe HistoryObservationValuator.scopeSymbols(changedConfig)
                cachedChangedAssetScope shouldBe changedAssetScope
                coVerify(exactly = 3) { krakenService.getAuthenticatedAccountIdentity() }
                coVerify(exactly = 4) { dataDirectoryIdentity.bindOrVerify(any()) }
            }
        }

        "currentSourceScope remains unavailable when the data directory cannot be bound" {
            val config = TestFixtures.DEFAULT_TEST_CONFIG.copy(
                settings = TestFixtures.settings(simulation = false, dryRun = true),
            )
            coEvery { krakenService.getFundingEvidenceScope() } returns "verified-credential"
            coEvery { krakenService.getAuthenticatedAccountIdentity() } returns "VERIFIED_ACCOUNT"
            coEvery { krakenService.withStableBackend<Any?>(any()) } coAnswers {
                firstArg<suspend (KrakenService) -> Any?>()(krakenService)
            }
            coEvery { dataDirectoryIdentity.bindOrVerify(any()) } throws
                IllegalStateException("The populated directory is missing its account marker.")

            runTest {
                recorder.currentSourceScope(config) shouldBe null
                coVerify { dataDirectoryIdentity.bindOrVerify(any()) }
            }
        }

        "captureIncompleteCycle persists direct evidence without publishing a total" {
            val config = TestFixtures.DEFAULT_TEST_CONFIG.copy(
                settings = TestFixtures.settings(simulation = false, dryRun = true),
            )
            val now = Instant.now()
            val balances = ObservedBalances(
                balances = emptyMap(),
                observedAt = now,
                directCapture = DirectBalanceCapture(
                    requestStartedAt = now,
                    responseEndedAt = now,
                    resultShapeValid = true,
                    valuesByAssetId = mapOf(
                        "XXBT" to DirectValueEvidence("1", BigDecimal.ONE, DirectEvidenceStatus.VALID),
                        "XETH" to DirectValueEvidence("2", BigDecimal("2"), DirectEvidenceStatus.VALID),
                        "ZUSD" to DirectValueEvidence("100", BigDecimal("100"), DirectEvidenceStatus.VALID),
                    ),
                ),
            )
            val prices = ObservedPrices(
                prices = emptyMap(),
                directCapture = DirectTickerCapture(
                    requestStartedAt = now,
                    responseEndedAt = now,
                    resultShapeValid = true,
                    marksBySymbol = mapOf(
                        "BTC" to directMark("BTC", "50000"),
                        "ETH" to directMark("ETH", "3000"),
                    ),
                ),
            )
            coEvery { krakenService.getFundingEvidenceScope() } returns "incomplete-credential-scope"
            coEvery { krakenService.getAuthenticatedAccountIdentity() } returns "INCOMPLETE_ACCOUNT"
            coEvery { krakenService.withStableBackend<Any?>(any()) } coAnswers {
                val block = firstArg<suspend (KrakenService) -> Any?>()
                block(krakenService)
            }

            runTest {
                recorder.start(this)
                try {
                    recorder.captureIncompleteCycle(
                        cycleId = "failed-valuation-cycle",
                        config = config,
                        balances = balances,
                        prices = prices,
                        reason = "Portfolio valuation failed before a snapshot could be built.",
                        actions = listOf("valuation failed"),
                        dryRun = true,
                    ) shouldBe true
                    advanceUntilIdle()
                    coVerify {
                        store.appendCycle(
                            match {
                                it.status == HistoryObservationStatus.INCOMPLETE && it.totalUsd == null &&
                                    it.incompleteReasons.contains(
                                        "Portfolio valuation failed before a snapshot could be built.",
                                    ) &&
                                    it.assets.all { asset -> asset.status == HistoryAssetValueStatus.COMPLETE }
                            },
                            match {
                                it.cycleId == "failed-valuation-cycle" && it.actions == listOf("valuation failed")
                            },
                        )
                    }
                } finally {
                    recorder.stop()
                }
            }
        }

        "captureIncompleteCycle records unavailable simulation evidence without inventing values" {
            val config = TestFixtures.DEFAULT_TEST_CONFIG.copy(
                settings = TestFixtures.settings(simulation = true, dryRun = true),
            )
            val now = Instant.now()
            val priceObservedAt = now.plusSeconds(5)

            runTest {
                recorder.start(this)
                try {
                    recorder.captureIncompleteCycle(
                        cycleId = "failed-simulation-cycle",
                        config = config,
                        balances = ObservedBalances(emptyMap(), now),
                        prices = ObservedPrices(emptyMap(), observedAt = priceObservedAt),
                        reason = "Simulated portfolio valuation failed.",
                        dryRun = true,
                    ) shouldBe true
                    advanceUntilIdle()
                    coVerify {
                        store.appendCycle(
                            match {
                                it.observedAt == priceObservedAt &&
                                    it.status == HistoryObservationStatus.INCOMPLETE && it.totalUsd == null &&
                                    it.incompleteReasons == listOf("Simulated portfolio valuation failed.") &&
                                    it.assets.all { asset ->
                                        asset.status == HistoryAssetValueStatus.UNAVAILABLE_CYCLE_EVIDENCE &&
                                            asset.quantity == null
                                    }
                            },
                            match { it.cycleId == "failed-simulation-cycle" },
                        )
                    }
                } finally {
                    recorder.stop()
                }
            }
        }

        "queued account observations are discarded if credentials rotate before persistence" {
            val config = TestFixtures.DEFAULT_TEST_CONFIG.copy(
                settings = TestFixtures.settings(simulation = false, dryRun = true),
            )
            val now = Instant.now()
            coEvery { krakenService.getFundingEvidenceScope() } returnsMany
                listOf("credential-before-queue", "credential-after-queue")
            coEvery { krakenService.getAuthenticatedAccountIdentity() } returns "SAME_ACCOUNT"
            coEvery { krakenService.withStableBackend<Any?>(any()) } coAnswers {
                firstArg<suspend (KrakenService) -> Any?>()(krakenService)
            }

            runTest {
                val snapshot = PortfolioSnapshot(
                    timestamp = now,
                    totalValueUSD = BigDecimal("100"),
                    assets = emptyMap(),
                    actions = emptyList(),
                    effectiveUsdTargetPercent = BigDecimal.ZERO,
                )
                recorder.captureFinalCycle(
                    cycleId = "credential-rotation-cycle",
                    config = config,
                    balances = ObservedBalances(emptyMap(), now),
                    prices = ObservedPrices(emptyMap()),
                    snapshot = snapshot,
                    sampleSelection = HistorySampleSelection.FINAL,
                    dryRun = true,
                ) shouldBe true

                recorder.start(this)
                try {
                    advanceUntilIdle()
                    recorder.captureIssue() shouldBe "History account identity is unavailable or changed."
                    coVerify(exactly = 0) { store.appendCycle(any(), any()) }
                } finally {
                    recorder.stop()
                }
            }
        }

        "captureFailedCycle persists an activity-only problem without fabricating values" {
            val config = TestFixtures.DEFAULT_TEST_CONFIG.let {
                it.copy(settings = it.settings.copy(simulation = true))
            }

            runTest {
                recorder.start(this)
                try {
                    recorder.captureFailedCycle(
                        cycleId = "failed-cycle-1",
                        config = config,
                        reason = "Order execution failed before settlement.",
                        actions = listOf("SELL BTC"),
                        dryRun = true,
                    ) shouldBe true
                    advanceUntilIdle()

                    coVerify(exactly = 1) {
                        store.appendCycle(
                            null,
                            match {
                                it.cycleId == "failed-cycle-1" &&
                                    it.outcome == HistoryCycleOutcome.PROBLEM &&
                                    it.actions == listOf("SELL BTC") &&
                                    it.dryRun
                            },
                        )
                    }
                    coVerify(exactly = 0) { store.appendObservation(any()) }
                } finally {
                    recorder.stop()
                }
            }
        }

        "captureFailedCycle uses default actions and reports a failed save" {
            val config = TestFixtures.DEFAULT_TEST_CONFIG.let {
                it.copy(settings = it.settings.copy(simulation = true))
            }

            runTest {
                recorder.start(this)
                try {
                    recorder.captureFailedCycle(
                        cycleId = "failed-cycle-2",
                        config = config,
                        reason = "Balances could not be read.",
                        dryRun = true,
                    ) shouldBe true
                    advanceUntilIdle()

                    coVerify(exactly = 1) {
                        store.appendCycle(
                            null,
                            match { it.actions == emptyList<String>() && it.outcome == HistoryCycleOutcome.PROBLEM },
                        )
                    }

                    coEvery { store.appendCycle(any(), any()) } throws IllegalStateException("store unavailable")
                    recorder.captureFailedCycle(
                        cycleId = "failed-cycle-3",
                        config = config,
                        reason = "Storage failed.",
                        dryRun = true,
                    ) shouldBe true
                    advanceUntilIdle()
                    recorder.captureIssue() shouldBe "History cycle sample could not be saved."
                } finally {
                    recorder.stop()
                }
            }
        }

        "captureFailedCycle records an account-mode problem after credential verification" {
            val config = TestFixtures.DEFAULT_TEST_CONFIG.copy(
                settings = TestFixtures.settings(simulation = false, dryRun = true),
            )
            coEvery { krakenService.getFundingEvidenceScope() } returns "failed-cycle-credential"
            coEvery { krakenService.getAuthenticatedAccountIdentity() } returns "FAILED_CYCLE_ACCOUNT"
            coEvery { krakenService.withStableBackend<Any?>(any()) } coAnswers {
                firstArg<suspend (KrakenService) -> Any?>()(krakenService)
            }

            runTest {
                recorder.start(this)
                try {
                    recorder.captureFailedCycle(
                        cycleId = "failed-account-cycle",
                        config = config,
                        reason = "Sell settlement aborted the cycle.",
                        dryRun = true,
                    ) shouldBe true
                    advanceUntilIdle()

                    coVerify(exactly = 1) {
                        store.appendCycle(
                            null,
                            match {
                                it.outcome == HistoryCycleOutcome.PROBLEM &&
                                    it.sourceScope.mode == HistorySourceMode.ACCOUNT
                            },
                        )
                    }
                    coVerify { dataDirectoryIdentity.bindOrVerify(any()) }
                } finally {
                    recorder.stop()
                }
            }
        }

        "an account identity that vanishes before persistence is never fabricated" {
            val config = TestFixtures.DEFAULT_TEST_CONFIG.copy(
                settings = TestFixtures.settings(simulation = false, dryRun = true),
            )
            val now = Instant.now()
            coEvery { krakenService.getFundingEvidenceScope() } returns "vanishing-credential"
            coEvery { krakenService.getAuthenticatedAccountIdentity() } returns null
            coEvery { krakenService.withStableBackend<Any?>(any()) } coAnswers {
                firstArg<suspend (KrakenService) -> Any?>()(krakenService)
            }

            runTest {
                val snapshot = PortfolioSnapshot(
                    timestamp = now,
                    totalValueUSD = BigDecimal("100"),
                    assets = emptyMap(),
                    actions = emptyList(),
                    effectiveUsdTargetPercent = BigDecimal.ZERO,
                )
                recorder.start(this)
                try {
                    recorder.captureFinalCycle(
                        cycleId = "vanishing-account-cycle",
                        config = config,
                        balances = ObservedBalances(emptyMap(), now),
                        prices = ObservedPrices(emptyMap()),
                        snapshot = snapshot,
                        sampleSelection = HistorySampleSelection.FINAL,
                        dryRun = true,
                    ) shouldBe true
                    advanceUntilIdle()

                    coVerify(exactly = 0) { store.appendCycle(any(), any()) }
                    recorder.captureIssue() shouldBe "History account identity is unavailable or changed."
                } finally {
                    recorder.stop()
                }
            }
        }

        "cycle outcomes round-trip through the persisted activity record" {
            val config = TestFixtures.DEFAULT_TEST_CONFIG.let {
                it.copy(settings = it.settings.copy(simulation = true))
            }
            val now = Instant.now()
            val snapshot = PortfolioSnapshot(
                timestamp = now,
                totalValueUSD = BigDecimal("1000.00"),
                assets = emptyMap(),
                actions = listOf("HOLD"),
                effectiveUsdTargetPercent = BigDecimal("60.00"),
            )

            runTest {
                recorder.start(this)
                try {
                    recorder.captureFinalCycle(
                        cycleId = "outcome-cycle-1",
                        config = config,
                        balances = ObservedBalances(balances = emptyMap(), observedAt = now),
                        prices = ObservedPrices(prices = emptyMap()),
                        snapshot = snapshot,
                        sampleSelection = HistorySampleSelection.FINAL,
                        dryRun = true,
                        outcome = HistoryCycleOutcome.ORDERS_ATTEMPTED,
                    ) shouldBe true
                    advanceUntilIdle()

                    coVerify(exactly = 1) {
                        store.appendCycle(
                            withArg { it shouldNotBe null },
                            match { it.outcome == HistoryCycleOutcome.ORDERS_ATTEMPTED },
                        )
                    }
                } finally {
                    recorder.stop()
                }
            }
        }

        "captures and persists simulation cycle sample" {
            val config = TestFixtures.DEFAULT_TEST_CONFIG.let {
                it.copy(settings = it.settings.copy(simulation = true))
            }
            val now = Instant.now()
            val snapshot = PortfolioSnapshot(
                timestamp = now,
                totalValueUSD = BigDecimal("50000.00"),
                assets = mapOf(
                    "BTC" to
                        TestFixtures.assetSnapshot(
                            "BTC",
                            BigDecimal("1.0"),
                            BigDecimal("50000.00"),
                            BigDecimal("50000.00"),
                            BigDecimal("50.00"),
                        ),
                    "USD" to
                        TestFixtures.assetSnapshot(
                            "USD",
                            BigDecimal("0.0"),
                            BigDecimal.ONE,
                            BigDecimal("0.00"),
                            BigDecimal("20.00"),
                        ),
                    "ETH" to
                        TestFixtures.assetSnapshot(
                            "ETH",
                            BigDecimal("0.0"),
                            BigDecimal("3000.00"),
                            BigDecimal("0.00"),
                            BigDecimal("30.00"),
                        ),
                ),
                actions = listOf("BUY BTC"),
                effectiveUsdTargetPercent = BigDecimal("0.00"),
            )

            runTest {
                recorder.start(this)

                try {
                    val queued = recorder.captureFinalCycle(
                        cycleId = "sim-cycle-1",
                        config = config,
                        balances = ObservedBalances(balances = mapOf("XXBT" to BigDecimal("1.0")), observedAt = now),
                        prices = ObservedPrices(prices = mapOf("BTC" to BigDecimal("50000.00"))),
                        snapshot = snapshot,
                        sampleSelection = HistorySampleSelection.FINAL,
                        actions = listOf("BUY BTC"),
                        dryRun = true,
                    )

                    queued shouldBe true
                    advanceUntilIdle()

                    coVerify {
                        store.appendCycle(
                            match { it.cycleId == "sim-cycle-1" && it.status == HistoryObservationStatus.COMPLETE },
                            match { it.cycleId == "sim-cycle-1" && it.actions == listOf("BUY BTC") },
                        )
                    }
                } finally {
                    recorder.stop()
                }
            }
        }

        "captures and persists account cycle sample after authenticating identity" {
            val config = TestFixtures.DEFAULT_TEST_CONFIG.let {
                it.copy(settings = it.settings.copy(simulation = false, dryRun = true))
            }
            val now = Instant.now()
            val snapshot = PortfolioSnapshot(
                timestamp = now,
                totalValueUSD = BigDecimal("50000.00"),
                assets = mapOf(
                    "BTC" to
                        TestFixtures.assetSnapshot(
                            "BTC",
                            BigDecimal("1.0"),
                            BigDecimal("50000.00"),
                            BigDecimal("50000.00"),
                            BigDecimal("50.00"),
                        ),
                    "USD" to
                        TestFixtures.assetSnapshot(
                            "USD",
                            BigDecimal("0.0"),
                            BigDecimal.ONE,
                            BigDecimal("0.00"),
                            BigDecimal("20.00"),
                        ),
                    "ETH" to
                        TestFixtures.assetSnapshot(
                            "ETH",
                            BigDecimal("0.0"),
                            BigDecimal("3000.00"),
                            BigDecimal("0.00"),
                            BigDecimal("30.00"),
                        ),
                ),
                actions = emptyList(),
                effectiveUsdTargetPercent = BigDecimal("0.00"),
            )

            coEvery { krakenService.getFundingEvidenceScope() } returns "test-credential-scope"
            coEvery { krakenService.getAuthenticatedAccountIdentity() } returns "TEST_ACCOUNT_123"
            coEvery { krakenService.withStableBackend<Any?>(any()) } coAnswers {
                val block = firstArg<suspend (KrakenService) -> Any?>()
                block(krakenService)
            }

            runTest {
                recorder.start(this)

                try {
                    val queued = recorder.captureFinalCycle(
                        cycleId = "acc-cycle-1",
                        config = config,
                        balances = ObservedBalances(balances = mapOf("XXBT" to BigDecimal("1.0")), observedAt = now),
                        prices = ObservedPrices(prices = mapOf("BTC" to BigDecimal("50000.00"))),
                        snapshot = snapshot,
                        sampleSelection = HistorySampleSelection.FINAL,
                        dryRun = true,
                    )

                    queued shouldBe true
                    advanceUntilIdle()

                    coVerify {
                        store.appendCycle(
                            match { it.cycleId == "acc-cycle-1" },
                            match { it.cycleId == "acc-cycle-1" },
                        )
                    }

                    // Subsequent call to currentSourceScope should return cached verified scope
                    val currentScope = recorder.currentSourceScope(config)
                    currentScope shouldNotBe null
                    currentScope!!.mode shouldBe HistorySourceMode.ACCOUNT
                } finally {
                    recorder.stop()
                }
            }
        }

        "captureFinalCycle fails when credential scope is unavailable in account mode" {
            val config = TestFixtures.DEFAULT_TEST_CONFIG.copy(
                settings = TestFixtures.settings(simulation = false, dryRun = true),
            )
            coEvery { krakenService.getFundingEvidenceScope() } returns "scope-unavailable"
            coEvery { krakenService.withStableBackend<Any?>(any()) } coAnswers {
                val block = firstArg<suspend (KrakenService) -> Any?>()
                block(krakenService)
            }
            runTest {
                val snapshot = PortfolioSnapshot(
                    timestamp = Instant.now(),
                    totalValueUSD = BigDecimal("50000.00"),
                    assets = emptyMap(),
                    actions = emptyList(),
                    effectiveUsdTargetPercent = BigDecimal("0.00"),
                )
                val queued = recorder.captureFinalCycle(
                    cycleId = "c-err",
                    config = config,
                    balances = ObservedBalances(emptyMap(), Instant.now()),
                    prices = ObservedPrices(emptyMap()),
                    snapshot = snapshot,
                    sampleSelection = HistorySampleSelection.FINAL,
                    dryRun = true,
                )
                queued shouldBe false
                recorder.captureIssue() shouldBe "History account identity is unavailable or changed."
            }
        }

        "persistCandidate logs warning and skips when dataDirectoryIdentity binding fails" {
            val config = TestFixtures.DEFAULT_TEST_CONFIG.copy(
                settings = TestFixtures.settings(simulation = false, dryRun = true),
            )
            coEvery { krakenService.getFundingEvidenceScope() } returns "scope-1"
            coEvery { krakenService.getAuthenticatedAccountIdentity() } returns "ACCOUNT_FAIL"
            coEvery { krakenService.withStableBackend<Any?>(any()) } coAnswers {
                val block = firstArg<suspend (KrakenService) -> Any?>()
                block(krakenService)
            }
            coEvery { dataDirectoryIdentity.bindOrVerify(any()) } throws
                IllegalStateException("Directory bound to another account")

            runTest {
                recorder.start(this)
                try {
                    val snapshot = PortfolioSnapshot(
                        timestamp = Instant.now(),
                        totalValueUSD = BigDecimal("50000.00"),
                        assets = emptyMap(),
                        actions = emptyList(),
                        effectiveUsdTargetPercent = BigDecimal("0.00"),
                    )
                    recorder.captureFinalCycle(
                        cycleId = "c-fail-bind",
                        config = config,
                        balances = ObservedBalances(emptyMap(), Instant.now()),
                        prices = ObservedPrices(emptyMap()),
                        snapshot = snapshot,
                        sampleSelection = HistorySampleSelection.FINAL,
                        dryRun = true,
                    )
                    advanceUntilIdle()
                    recorder.captureIssue() shouldBe "History account identity is unavailable or changed."
                } finally {
                    recorder.stop()
                }
            }
        }

        "start is idempotent when worker is already active" {
            runTest {
                recorder.start(this)
                recorder.start(this)
                recorder.stop()
            }
        }

        "currentSourceScope returns null when credential scope changes" {
            val config = TestFixtures.DEFAULT_TEST_CONFIG.copy(
                settings = TestFixtures.settings(simulation = false, dryRun = true),
            )
            coEvery { krakenService.getFundingEvidenceScope() } returns "scope-changed"
            runTest {
                recorder.currentSourceScope(config) shouldBe null
            }
        }

        "persistCandidate sets capture issue when store appendCycle throws exception" {
            val config = TestFixtures.DEFAULT_TEST_CONFIG.let {
                it.copy(settings = it.settings.copy(simulation = true))
            }
            coEvery { store.appendCycle(any(), any()) } throws RuntimeException("Disk write error")
            runTest {
                recorder.start(this)
                try {
                    val snapshot = PortfolioSnapshot(
                        timestamp = Instant.now(),
                        totalValueUSD = BigDecimal("50000.00"),
                        assets = emptyMap(),
                        actions = emptyList(),
                        effectiveUsdTargetPercent = BigDecimal.ZERO,
                    )
                    recorder.captureFinalCycle(
                        cycleId = "err-cycle-1",
                        config = config,
                        balances = ObservedBalances(emptyMap(), Instant.now()),
                        prices = ObservedPrices(emptyMap()),
                        snapshot = snapshot,
                        sampleSelection = HistorySampleSelection.FINAL,
                        dryRun = true,
                    )
                    advanceUntilIdle()
                    recorder.captureIssue() shouldBe "History cycle sample could not be saved."
                } finally {
                    recorder.stop()
                }
            }
        }

        "captureFinalCycle rejects when queue is full" {
            val config = TestFixtures.DEFAULT_TEST_CONFIG.let {
                it.copy(settings = it.settings.copy(simulation = true))
            }
            val snapshot = PortfolioSnapshot(
                timestamp = Instant.now(),
                totalValueUSD = BigDecimal("50000.00"),
                assets = emptyMap(),
                actions = emptyList(),
                effectiveUsdTargetPercent = BigDecimal.ZERO,
            )
            runTest {
                // Do not start worker so queue fills up (capacity = 16)
                var queuedAll = true
                for (i in 0 until 17) {
                    val q = recorder.captureFinalCycle(
                        cycleId = "q-$i",
                        config = config,
                        balances = ObservedBalances(emptyMap(), Instant.now()),
                        prices = ObservedPrices(emptyMap()),
                        snapshot = snapshot,
                        sampleSelection = HistorySampleSelection.FINAL,
                        dryRun = true,
                    )
                    if (!q) queuedAll = false
                }
                queuedAll shouldBe false
                recorder.captureIssue() shouldBe "History sample skipped because the bounded writer queue is full."
            }
        }

        "persistCandidate appends missing direct evidence reasons when direct captures are null" {
            val config = TestFixtures.DEFAULT_TEST_CONFIG.copy(
                settings = TestFixtures.settings(simulation = false, dryRun = true),
            )
            val balanceObservedAt = Instant.now().minusSeconds(10)
            val priceObservedAt = balanceObservedAt.plusSeconds(5)
            coEvery { krakenService.getFundingEvidenceScope() } returns "scope-evidence"
            coEvery { krakenService.getAuthenticatedAccountIdentity() } returns "ACCOUNT_OK"
            coEvery { krakenService.withStableBackend<Any?>(any()) } coAnswers {
                val block = firstArg<suspend (KrakenService) -> Any?>()
                block(krakenService)
            }
            runTest {
                recorder.start(this)
                try {
                    val snapshot = PortfolioSnapshot(
                        timestamp = balanceObservedAt,
                        totalValueUSD = BigDecimal("50000.00"),
                        assets = emptyMap(),
                        actions = emptyList(),
                        effectiveUsdTargetPercent = BigDecimal.ZERO,
                    )
                    recorder.captureFinalCycle(
                        cycleId = "c-no-direct",
                        config = config,
                        balances = ObservedBalances(emptyMap(), balanceObservedAt, directCapture = null),
                        prices = ObservedPrices(
                            emptyMap(),
                            directCapture = null,
                            observedAt = priceObservedAt,
                        ),
                        snapshot = snapshot,
                        sampleSelection = HistorySampleSelection.FINAL,
                        dryRun = true,
                    )
                    advanceUntilIdle()
                    coVerify {
                        store.appendCycle(
                            match {
                                it.observedAt == priceObservedAt &&
                                    it.incompleteReasons.contains("Direct balance evidence is unavailable.") &&
                                    it.incompleteReasons.contains("Direct ticker evidence is unavailable.")
                            },
                            any(),
                        )
                    }
                } finally {
                    recorder.stop()
                }
            }
        }

        "currentSourceScope propagates cancellation instead of reporting an unavailable scope" {
            val config = TestFixtures.DEFAULT_TEST_CONFIG.copy(
                settings = TestFixtures.settings(simulation = false, dryRun = true),
            )
            coEvery { krakenService.withStableBackend<Any?>(any()) } throws
                CancellationException("execution session cancelled")

            runTest {
                shouldThrow<CancellationException> { recorder.currentSourceScope(config) }
            }
        }

        "an identity lookup that stalls past its timeout skips the sample instead of hanging" {
            val config = TestFixtures.DEFAULT_TEST_CONFIG.copy(
                settings = TestFixtures.settings(simulation = false, dryRun = true),
            )
            val stallRecorder = HistoryObservationRecorder(
                store = store,
                krakenService = krakenService,
                configService = configService,
                dataDirectoryIdentity = dataDirectoryIdentity,
                identityLookupTimeoutMs = 50,
            )
            coEvery { krakenService.getFundingEvidenceScope() } returns "stalled-credential"
            coEvery { krakenService.getAuthenticatedAccountIdentity() } coAnswers { awaitCancellation() }
            coEvery { krakenService.withStableBackend<Any?>(any()) } coAnswers {
                val block = firstArg<suspend (KrakenService) -> Any?>()
                block(krakenService)
            }

            runTest {
                stallRecorder.start(this)
                try {
                    val queued = stallRecorder.captureFailedCycle(
                        cycleId = "stalled-cycle-1",
                        config = config,
                        reason = "Rebalance cycle failed: CycleFailure",
                        dryRun = true,
                    )
                    queued shouldBe true
                    advanceUntilIdle()

                    stallRecorder.captureIssue() shouldBe "History account identity is unavailable or changed."
                    coVerify(exactly = 0) { store.appendCycle(any(), any()) }
                } finally {
                    stallRecorder.stop()
                }
            }
        }
    }

    private fun directMark(symbol: String, price: String) = DirectTickerMark(
        symbol = symbol,
        requestedPair = "${symbol}USD",
        responsePair = "${symbol}USD",
        candidateResponsePairs = listOf("${symbol}USD"),
        rawPrice = price,
        price = BigDecimal(price),
        status = DirectEvidenceStatus.VALID,
        candidateValuesByPair = mapOf(
            "${symbol}USD" to DirectValueEvidence(price, BigDecimal(price), DirectEvidenceStatus.VALID),
        ),
    )
}
