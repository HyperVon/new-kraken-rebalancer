package com.gemini.krakenbot.service.history

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.DataDirectoryIdentity
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.service.ObservedBalances
import com.gemini.krakenbot.service.ObservedPrices
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
                        timestamp = Instant.now(),
                        totalValueUSD = BigDecimal("50000.00"),
                        assets = emptyMap(),
                        actions = emptyList(),
                        effectiveUsdTargetPercent = BigDecimal.ZERO,
                    )
                    recorder.captureFinalCycle(
                        cycleId = "c-no-direct",
                        config = config,
                        balances = ObservedBalances(emptyMap(), Instant.now(), directCapture = null),
                        prices = ObservedPrices(emptyMap(), directCapture = null),
                        snapshot = snapshot,
                        sampleSelection = HistorySampleSelection.FINAL,
                        dryRun = true,
                    )
                    advanceUntilIdle()
                    coVerify {
                        store.appendCycle(
                            match {
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
    }
}
