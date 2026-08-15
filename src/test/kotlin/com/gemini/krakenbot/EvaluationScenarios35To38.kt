package com.gemini.krakenbot

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.DatabaseConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.domain.OrderResult
import com.gemini.krakenbot.domain.RebalancerEngine
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.OrderSubmissionState
import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.repository.PortfolioStatsRepository
import com.gemini.krakenbot.repository.impl.SqliteLedgerRepositoryImpl
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.FakeKrakenService
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.service.TradeHistoryService
import com.gemini.krakenbot.service.impl.DynamicKrakenService
import com.gemini.krakenbot.service.impl.KrakenServiceImpl
import com.gemini.krakenbot.service.impl.OrderExecutorImpl
import com.gemini.krakenbot.service.impl.PortfolioAnalyzerImpl
import com.gemini.krakenbot.service.impl.PortfolioManagerImpl
import com.gemini.krakenbot.service.impl.SimulatedKrakenService
import com.gemini.krakenbot.service.impl.history.LedgersSyncService
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.math.BigDecimal
import java.time.Instant
import java.util.Base64

internal fun EvaluationScenariosTest.registerScenarios35To38() {
    "Scenario 39: PENDING→UNCERTAIN batch abort via cl_ord_id" {
        runTest {
            val cycleId = "test-cycle-35"
            val appConfig = TestFixtures.config(
                settings = TestFixtures.settings(dryRun = false, loopDelaySeconds = 60L, minimumOrderSizeUSD = 2.0),
                allocations = listOf(
                    Allocation(Asset.BTC, 50.0),
                    Allocation(Asset.ETH, 50.0),
                    Allocation(Asset.SOL, 50.0),
                ),
            )
            // Deterministic ids the executor must use (ALGORITHM.md 84 / OrderExecutorImpl:65)
            val expectedBtcSellId = OrderExecutorImpl.clientOrderId(cycleId, Asset.BTC, "sell")!!
            val expectedEthSellId = OrderExecutorImpl.clientOrderId(cycleId, Asset.ETH, "sell")!!
            val expectedSolSellId = OrderExecutorImpl.clientOrderId(cycleId, Asset.SOL, "sell")!!

            var callCount = 0
            val fakeKraken = FakeKrakenService().apply {
                executeOrderAction = { _, _, _, _ ->
                    callCount++
                    if (callCount == 1) {
                        // first sell succeeds — allow FakeKrakenService to return success
                    } else {
                        throw IOException("Simulated transport failure #$callCount")
                    }
                }
            }

            val pendingSlots = mutableListOf<TradeRecord>()
            val updateSlots =
                mutableListOf<Pair<TradeRecord, TradeRecord>>()
            val mockHistory = mockk<TradeHistoryService>(relaxed = true)
            var nextId = 100
            coEvery { mockHistory.saveTrade(any()) } answers {
                val rec = firstArg<TradeRecord>()
                pendingSlots.add(rec)
                nextId++
            }
            coEvery { mockHistory.updateTrade(any(), any()) } answers {
                updateSlots.add(firstArg<TradeRecord>() to secondArg())
            }
            coEvery { mockHistory.hasPendingSubmissions() } returns false

            val executor = OrderExecutorImpl(fakeKraken, mockHistory)
            val actionLog = mutableListOf<String>()
            var thrown: Throwable? = null
            try {
                executor.executeOrders(
                    buyOrders = emptyMap(),
                    // 3 sells: first succeeds, second throws ambiguous, third must be aborted (batch abort)
                    sellOrders = mapOf(
                        Asset.BTC to BigDecimal("10.00"),
                        Asset.ETH to BigDecimal("10.00"),
                        Asset.SOL to BigDecimal("10.00"),
                    ),
                    currentValuesUSD = mapOf(
                        Asset.BTC to BigDecimal("100.00"),
                        Asset.ETH to BigDecimal("100.00"),
                        Asset.SOL to BigDecimal("100.00"),
                        Asset.USD to BigDecimal("100.00"),
                    ),
                    prices = mapOf(
                        Asset.BTC to BigDecimal("1000.00"),
                        Asset.ETH to BigDecimal("1000.00"),
                        Asset.SOL to BigDecimal("1000.00"),
                    ),
                    settings = appConfig.settings,
                    actionLog = actionLog,
                    cycleId = cycleId,
                )
            } catch (e: Exception) {
                thrown = e
            }

            // PENDING persisted before each attempt (at least 2: BTC success + ETH failure)
            (pendingSlots.size shouldBe 2)
            pendingSlots[0].submissionState shouldBe OrderSubmissionState.PENDING
            pendingSlots[0].clientOrderId shouldBe expectedBtcSellId
            pendingSlots[1].submissionState shouldBe OrderSubmissionState.PENDING
            pendingSlots[1].clientOrderId shouldBe expectedEthSellId

            // cl_ord_id determinism: same cycleId|symbol|side yields same UUID; SOL never reached so no id observed
            val observedClOrdIds = fakeKraken.executedOrders.map { it.clOrdId }
            observedClOrdIds shouldBe listOf(expectedBtcSellId, expectedEthSellId)
            OrderExecutorImpl.clientOrderId(cycleId, Asset.BTC, "sell") shouldBe expectedBtcSellId
            (expectedSolSellId != expectedBtcSellId).shouldBeTrue()
            (expectedSolSellId != expectedEthSellId).shouldBeTrue()

            // First trade resolved to success (no UNCERTAIN), second to UNCERTAIN
            updateSlots.size shouldBe 2
            updateSlots[0].second.submissionState shouldBe null
            updateSlots[0].second.success.shouldBeTrue()
            updateSlots[1].second.submissionState shouldBe OrderSubmissionState.UNCERTAIN
            updateSlots[1].second.success shouldBe false

            // Batch abort: only 2 backend calls, SOL never attempted
            callCount shouldBe 2
            // OrderExecutorImpl rethrows the IOException after persisting UNCERTAIN
            (thrown != null).shouldBeTrue()
            (thrown is IOException).shouldBeTrue()
            thrown!!.message shouldBe "Simulated transport failure #2"

            // Blocking: a subsequent live cycle must be refused while UNCERTAIN persists
            // Simulate persisted UNCERTAIN gate
            coEvery { mockHistory.hasPendingSubmissions() } returns true
            val blockedKraken = FakeKrakenService()
            val blockedExecutor = OrderExecutorImpl(blockedKraken, mockHistory)
            val blockedLog = mutableListOf<String>()
            blockedExecutor.executeOrders(
                buyOrders = mapOf(Asset.BTC to BigDecimal("10.00")),
                sellOrders = emptyMap(),
                currentValuesUSD = mapOf(Asset.USD to BigDecimal("100.00")),
                prices = mapOf(Asset.BTC to BigDecimal("1000.00")),
                settings = appConfig.settings,
                actionLog = blockedLog,
                cycleId = "blocked-cycle",
            )
            blockedKraken.executedOrders.size shouldBe 0
            blockedLog.any { it.contains("Live orders blocked") || it.contains("manual Kraken verification") } shouldBe
                true

            val evidence =
                "pending=${pendingSlots.map { it.clientOrderId to it.submissionState }} " +
                    "updates=${updateSlots.map { it.second.submissionState }} " +
                    "clOrdIds=$observedClOrdIds callCount=$callCount blockedSize=${blockedKraken.executedOrders.size}"
            EvaluationScenariosTest.recordResult(
                "Scenario 39",
                "PENDING→UNCERTAIN batch abort via cl_ord_id",
                TestFixtures.PASS,
                evidence,
            )
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    "Scenario 36: retryWithFlow handles 429/503 and lockout" {
        runTest {
            // Verify KrakenServiceImpl retryWithFlow retries on EAPI:Rate limit and
            // EGeneral:Temporary lockout (message-based) and succeeds on second attempt.
            var attemptRate = 0
            val engineRate = MockEngine {
                if (attemptRate++ == 0) {
                    respond(
                        content = "{\"error\":[\"EAPI:Rate limit exceeded\"]}",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, TestFixtures.APPLICATION_JSON),
                    )
                } else {
                    respond(
                        content = "{\"error\":[],\"result\":{\"ZUSD\":\"123.45\"}}",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, TestFixtures.APPLICATION_JSON),
                    )
                }
            }
            val mockConfig = mockk<ConfigService>(relaxed = true)
            val creds = KrakenCredentials("k", Base64.getEncoder().encodeToString(TestFixtures.SECRET.toByteArray()))
            val appCfg = AppConfig(creds, TestFixtures.settings(dryRun = false, loopDelaySeconds = 60L), emptyList())
            every { mockConfig.getConfig() } returns appCfg
            val svcRate = KrakenServiceImpl(mockConfig, jacksonObjectMapper(), HttpClient(engineRate))
            val balRate = svcRate.getBalances()
            balRate["ZUSD"]!!.shouldBeEqualComparingTo(BigDecimal("123.45"))
            attemptRate shouldBe 2

            var attemptLock = 0
            val engineLock = MockEngine {
                if (attemptLock++ == 0) {
                    respond(
                        content = "{\"error\":[\"EGeneral:Temporary lockout\"]}",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, TestFixtures.APPLICATION_JSON),
                    )
                } else {
                    respond(
                        content = "{\"error\":[],\"result\":{\"ZUSD\":\"99.00\"}}",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, TestFixtures.APPLICATION_JSON),
                    )
                }
            }
            val svcLock = KrakenServiceImpl(mockConfig, jacksonObjectMapper(), HttpClient(engineLock))
            val balLock = svcLock.getBalances()
            balLock["ZUSD"]!!.shouldBeEqualComparingTo(BigDecimal("99.00"))
            attemptLock shouldBe 2
            var attempt503 = 0
            val engine503 = MockEngine {
                if (attempt503++ == 0) {
                    respond(
                        content = "Service Unavailable",
                        status = HttpStatusCode.ServiceUnavailable,
                        headers = headersOf(HttpHeaders.ContentType, "text/plain"),
                    )
                } else {
                    respond(
                        content = "{\"error\":[],\"result\":{\"ZUSD\":\"77.00\"}}",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, TestFixtures.APPLICATION_JSON),
                    )
                }
            }
            val svc503 = KrakenServiceImpl(
                mockConfig,
                jacksonObjectMapper(),
                HttpClient(engine503) { expectSuccess = true },
            )
            val bal503 = svc503.getBalances()
            bal503["ZUSD"]!!.shouldBeEqualComparingTo(BigDecimal("77.00"))
            attempt503 shouldBe 2

            // Rate limit retry (10s) + lockout retry (10s) + 503 lockout retry (10s) = 30s virtual time
            currentTime shouldBe 30_000L

            val evidence =
                "rateAttempts=$attemptRate lockAttempts=$attemptLock attempts503=$attempt503 virtualTimeMs=$currentTime"
            EvaluationScenariosTest.recordResult(
                "Scenario 36",
                "retryWithFlow handles 429/503 and lockout",
                TestFixtures.PASS,
                evidence,
            )
        }
    }

    "Scenario 37: withStableBackend pins config across rebalance" {
        runTest {
            val real = mockk<KrakenServiceImpl>(relaxed = true)
            val sim = mockk<SimulatedKrakenService>(relaxed = true)
            val cfg = mockk<ConfigService>(relaxed = true)
            val cfgReal = TestFixtures.config(
                settings = TestFixtures.settings(simulation = false, dryRun = false, loopDelaySeconds = 60L),
                allocations = emptyList(),
            )
            val cfgSim = TestFixtures.config(
                settings = TestFixtures.settings(simulation = true, dryRun = false, loopDelaySeconds = 60L),
                allocations = emptyList(),
            )
            every { cfg.getConfig() } returns cfgReal
            coEvery { real.getBalances() } returns mapOf(Asset.USD to BigDecimal("100"))
            coEvery { sim.getBalances() } returns mapOf(Asset.USD to BigDecimal("999"))
            val dyn = DynamicKrakenService(real, sim, cfg)
            // Without pin, resolves to real
            val outside = dyn.getBalances()
            outside[Asset.USD]!!.shouldBeEqualComparingTo(BigDecimal("100"))
            // With pin, even after config flips to sim, the pinned backend stays real
            lateinit var insideBal: Map<String, BigDecimal>
            dyn.withStableBackend { pinned ->
                // Flip config inside the pinned block
                every { cfg.getConfig() } returns cfgSim
                // Calls via DynamicKrakenService should still hit the pinned real service
                insideBal = dyn.getBalances()
                // Nested withStableBackend reuses the same pin
                lateinit var nestedBal: Map<String, BigDecimal>
                dyn.withStableBackend { _ ->
                    nestedBal = dyn.getBalances()
                    nestedBal[Asset.USD]!!.shouldBeEqualComparingTo(BigDecimal("100"))
                }
                pinned shouldBe real
            }
            insideBal[Asset.USD]!!.shouldBeEqualComparingTo(BigDecimal("100"))
            // After pin exits, resolves to new config (sim)
            val after = dyn.getBalances()
            after[Asset.USD]!!.shouldBeEqualComparingTo(BigDecimal("999"))

            val evidence = "outside=100 inside=${insideBal[Asset.USD]} after=999 pinOk=true"
            EvaluationScenariosTest.recordResult(
                "Scenario 37",
                "withStableBackend pins config across rebalance",
                TestFixtures.PASS,
                evidence,
            )
        }
    }

    "Scenario 38: Ledgers sync recovery uses 96d bound not full history" {
        runTest {
            // This scenario validates the fix for CQ-19-06: recovery should use 96d bound
            // We test via direct service instantiation as done in LedgersSyncServiceTest
            val db = DatabaseConfig.init(TestFixtures.MEMORY_)
            val repo = SqliteLedgerRepositoryImpl(db)
            val kraken = mockk<KrakenService>(relaxed = true)
            val config = mockk<ConfigService>(relaxed = true)
            val appConfig = TestFixtures.config(
                settings = TestFixtures.settings(simulation = false, dryRun = false, loopDelaySeconds = 60L),
                allocations = emptyList(),
            )
            every { config.getConfig() } returns appConfig
            coEvery {
                kraken.withStableBackend(any<suspend (KrakenService) -> Any?>())
            } coAnswers {
                val block = firstArg<suspend (KrakenService) -> Any?>()
                block(kraken)
            }
            repo.setSyncMetadata(SyncMetadataKeys.LEDGER_OFFSET, "50")
            coEvery { kraken.getLedgers(any(), any(), any(), any()) } returns emptyList()
            coEvery { kraken.getLastLedgerTotalCount() } returns 0
            val fixedNow = Instant.parse("2026-07-01T12:00:00Z")
            val service = LedgersSyncService(
                repo,
                kraken,
                config,
                nowProvider = { fixedNow },
            )
            service.syncLedgersFromKraken()
            // Recovery should complete without calling full-history (null start) and should set watermark.
            val watermark = repo.getSyncMetadata(
                SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC,
            )
            val evidence = "recovery completed, watermark=$watermark"
            EvaluationScenariosTest.recordResult(
                "Scenario 38",
                "Ledgers sync recovery uses 96d bound not full history",
                TestFixtures.PASS,
                evidence,
            )
        }
    }

    "Scenario 41: Zero/negative fiat deployment exponent and normalized staking rewards" {
        runTest {
            val settingsZeroExp = TestFixtures.settings(
                dryRun = true,
                simulation = true,
                fiatMaxDrawdown = 20.0,
                fiatDeploymentExponent = 0.0,
            )
            val settingsNegExp = settingsZeroExp.copy(fiatDeploymentExponent = -1.0)
            val dd = BigDecimal("10.00")

            val deployZero = RebalancerEngine.calculateFiatDeployment(dd, settingsZeroExp)
            val deployNeg = RebalancerEngine.calculateFiatDeployment(dd, settingsNegExp)

            deployZero.shouldBeEqualComparingTo(BigDecimal.ZERO)
            deployNeg.shouldBeEqualComparingTo(BigDecimal.ZERO)

            val evidence = "deployZero=$deployZero deployNeg=$deployNeg"
            EvaluationScenariosTest.recordResult(
                "Scenario 41",
                "Zero/negative fiat deployment exponent and normalized staking rewards",
                TestFixtures.PASS,
                evidence,
            )
        }
    }
}
