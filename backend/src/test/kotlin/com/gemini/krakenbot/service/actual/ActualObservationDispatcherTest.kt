package com.gemini.krakenbot.service.actual

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.repository.ExecutionAccountBinding
import com.gemini.krakenbot.repository.ExecutionAccountBindingRepository
import com.gemini.krakenbot.service.DirectBalanceCapture
import com.gemini.krakenbot.service.DirectEvidenceStatus
import com.gemini.krakenbot.service.DirectTickerCapture
import com.gemini.krakenbot.service.DirectTickerMark
import com.gemini.krakenbot.service.DirectValueEvidence
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.service.ObservedBalances
import com.gemini.krakenbot.service.ObservedPrices
import com.gemini.krakenbot.service.actual.benchmark.BenchmarkStatus
import com.gemini.krakenbot.service.actual.benchmark.ProspectiveBenchmarkService
import com.gemini.krakenbot.service.impl.ExecutionAccountBindingService
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant

class ActualObservationDispatcherTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "direct captures persist asynchronously and continue as an append-only series after restart" {
            runTest {
                withTempDirectory { directory ->
                    val paths = Paths(
                        actual = directory.resolve("actual.db"),
                        reporting = directory.resolve("reporting.db"),
                        execution = directory.resolve("execution.db"),
                    )
                    val store = store(paths)
                    val accountDigest = "a".repeat(64)
                    val credentialScope = "test-credential-generation"
                    val binding = ExecutionAccountBinding(
                        accountIdentityDigest = accountDigest,
                        credentialGenerationDigest = digest(credentialScope),
                        bindingVersion = 1,
                        verificationMethod = "isolated-test",
                        verifiedAtMillis = NOW.toEpochMilli(),
                        auditVerified = true,
                    )
                    val bindings = mockk<ExecutionAccountBindingRepository>()
                    coEvery { bindings.load() } returns binding
                    val accountService = mockk<ExecutionAccountBindingService>()
                    coEvery {
                        accountService.verifiedAccountIdentityDigestForObservation(credentialScope)
                    } returns accountDigest
                    val exchange = mockk<KrakenService>(relaxed = true)
                    val kraken = object : KrakenService by exchange {
                        override suspend fun getFundingEvidenceScope(): String = credentialScope

                        override suspend fun <T> withStableBackend(block: suspend (KrakenService) -> T): T = block(this)
                    }
                    val config = TestFixtures.config(
                        settings = TestFixtures.settings(dryRun = true, simulation = false),
                        allocations = listOf(Allocation(Asset.BTC, 80.0), Allocation(Asset.USD, 20.0)),
                    )
                    val first = dispatcher(store, accountService, bindings, kraken)
                    val firstWorker = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                    try {
                        first.start(firstWorker)
                        first.captureAfterCycle(
                            "cycle-1",
                            config,
                            observedBalances(NOW, "1.0", "100.00"),
                            observedPrices(NOW, "100.00"),
                        ) shouldBe
                            true
                        val firstPage = awaitHistory(first, config, expectedCount = 1)
                        firstPage.state shouldBe ActualPageState.READY
                        firstPage.observations.map { it.observationId } shouldBe listOf("cycle-1")
                        firstPage.observations.single().totalUsd?.compareTo(BigDecimal("200.00")) shouldBe 0
                    } finally {
                        firstWorker.cancel()
                    }

                    val restarted = dispatcher(store(paths), accountService, bindings, kraken)
                    val persistedPage = restarted.readCurrent(config)
                    persistedPage.observations.single().observationId shouldBe "cycle-1"
                    persistedPage.observations.single().totalUsd?.compareTo(BigDecimal("200.00")) shouldBe 0

                    val secondWorker = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                    try {
                        restarted.start(secondWorker)
                        restarted.captureAfterCycle(
                            "cycle-2",
                            config,
                            observedBalances(NOW.plusSeconds(10), "1.0", "100.00"),
                            observedPrices(NOW.plusSeconds(10), "120.00"),
                        ) shouldBe true
                        val secondPage = awaitHistory(restarted, config, expectedCount = 2)
                        secondPage.observations.map { it.observationId } shouldBe listOf("cycle-1", "cycle-2")
                        secondPage.observations.map { it.totalUsd?.compareTo(BigDecimal("200")) } shouldBe
                            listOf(0, 1)
                        secondPage.observations.last().totalUsd?.compareTo(BigDecimal("220")) shouldBe 0
                    } finally {
                        secondWorker.cancel()
                    }

                    coVerify(exactly = 2) {
                        accountService.verifiedAccountIdentityDigestForObservation(credentialScope)
                    }
                    coVerify(exactly = 0) {
                        exchange.executeOrder(any(), any(), any(), any(), any(), any())
                    }
                    Files.exists(paths.reporting) shouldBe false
                    Files.exists(paths.execution) shouldBe false
                }
            }
        }

        "simulation and credential mismatch cannot query or expose authenticated Actual history" {
            runTest {
                withTempDirectory { directory ->
                    val paths = Paths(
                        actual = directory.resolve("actual.db"),
                        reporting = directory.resolve("reporting.db"),
                        execution = directory.resolve("execution.db"),
                    )
                    val credentialScope = "test-credential-generation"
                    val bindings = mockk<ExecutionAccountBindingRepository>()
                    coEvery { bindings.load() } returns ExecutionAccountBinding(
                        accountIdentityDigest = "a".repeat(64),
                        credentialGenerationDigest = "b".repeat(64),
                        bindingVersion = 1,
                        verificationMethod = "isolated-test",
                        verifiedAtMillis = NOW.toEpochMilli(),
                        auditVerified = true,
                    )
                    val accountService = mockk<ExecutionAccountBindingService>()
                    val exchange = mockk<KrakenService>(relaxed = true)
                    val kraken = object : KrakenService by exchange {
                        override suspend fun getFundingEvidenceScope(): String = credentialScope

                        override suspend fun <T> withStableBackend(block: suspend (KrakenService) -> T): T = block(this)
                    }
                    val dispatcher = dispatcher(store(paths), accountService, bindings, kraken)
                    val liveConfig = TestFixtures.config(
                        settings = TestFixtures.settings(dryRun = true, simulation = false),
                        allocations = listOf(Allocation(Asset.BTC, 80.0), Allocation(Asset.USD, 20.0)),
                    )

                    dispatcher.readCurrent(liveConfig).state shouldBe ActualPageState.ACCOUNT_UNVERIFIED
                    Files.exists(paths.actual) shouldBe false

                    val simulationConfig = liveConfig.copy(
                        settings = liveConfig.settings.copy(simulation = true),
                    )
                    dispatcher.readCurrent(simulationConfig).state shouldBe ActualPageState.SIMULATION
                    Files.exists(paths.actual) shouldBe false
                    coVerify(exactly = 1) { bindings.load() }
                    coVerify(exactly = 0) {
                        accountService.verifiedAccountIdentityDigestForObservation(any())
                    }
                }
            }
        }

        "regression F: benchmark verification failure does not hide valid Actual observations" {
            runTest {
                withTempDirectory { directory ->
                    val paths = Paths(
                        actual = directory.resolve("actual.db"),
                        reporting = directory.resolve("reporting.db"),
                        execution = directory.resolve("execution.db"),
                    )
                    val store = store(paths)
                    val accountDigest = "a".repeat(64)
                    val credentialScope = "test-credential-generation"
                    val binding = ExecutionAccountBinding(
                        accountIdentityDigest = accountDigest,
                        credentialGenerationDigest = digest(credentialScope),
                        bindingVersion = 1,
                        verificationMethod = "isolated-test",
                        verifiedAtMillis = NOW.toEpochMilli(),
                        auditVerified = true,
                    )
                    val bindings = mockk<ExecutionAccountBindingRepository>()
                    coEvery { bindings.load() } returns binding
                    val accountService = mockk<ExecutionAccountBindingService>()
                    coEvery {
                        accountService.verifiedAccountIdentityDigestForObservation(credentialScope)
                    } returns accountDigest
                    val exchange = mockk<KrakenService>(relaxed = true)
                    val kraken = object : KrakenService by exchange {
                        override suspend fun getFundingEvidenceScope(): String = credentialScope

                        override suspend fun <T> withStableBackend(block: suspend (KrakenService) -> T): T = block(this)
                    }
                    val benchmarkService = mockk<ProspectiveBenchmarkService>()
                    coEvery { benchmarkService.evaluate(any(), any(), any()) } throws
                        IllegalStateException("Simulated benchmark evaluation error")

                    val config = TestFixtures.config(
                        settings = TestFixtures.settings(dryRun = true, simulation = false),
                        allocations = listOf(Allocation(Asset.BTC, 80.0), Allocation(Asset.USD, 20.0)),
                    )
                    val dispatcher = dispatcher(
                        store = store,
                        accountService = accountService,
                        bindings = bindings,
                        kraken = kraken,
                        benchmarkService = benchmarkService,
                    )
                    val worker = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                    try {
                        dispatcher.start(worker)
                        dispatcher.captureAfterCycle(
                            "cycle-1",
                            config,
                            observedBalances(NOW, "1.0", "100.00"),
                            observedPrices(NOW, "100.00"),
                        ) shouldBe true
                        val page = awaitHistory(dispatcher, config, expectedCount = 1)
                        page.state shouldBe ActualPageState.READY
                        page.observations.map { it.observationId } shouldBe listOf("cycle-1")
                        page.benchmark?.status shouldBe BenchmarkStatus.UNAVAILABLE
                        page.benchmark?.unavailableReason shouldBe "Buy & Hold comparison unavailable"
                    } finally {
                        worker.cancel()
                    }
                }
            }
        }
    }

    private fun dispatcher(
        store: ActualObservationStore,
        accountService: ExecutionAccountBindingService,
        bindings: ExecutionAccountBindingRepository,
        kraken: KrakenService,
        benchmarkService: ProspectiveBenchmarkService? = null,
    ) = ActualObservationDispatcher(
        store = store,
        accountBindingService = accountService,
        bindingRepository = bindings,
        krakenService = kraken,
        nowProvider = { NOW.plusSeconds(20) },
        benchmarkService = benchmarkService,
    )

    private suspend fun awaitHistory(
        dispatcher: ActualObservationDispatcher,
        config: AppConfig,
        expectedCount: Int,
    ): ActualObservationPage {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (true) {
            val page = dispatcher.readCurrent(config)
            if (page.observations.size >= expectedCount) return page
            check(System.nanoTime() < deadline) { "Timed out waiting for persisted Actual observations." }
            withContext(Dispatchers.IO) { Thread.sleep(10) }
        }
    }

    private fun observedBalances(at: Instant, btc: String, usd: String) = ObservedBalances(
        balances = mapOf("XXBT" to BigDecimal(btc), "ZUSD" to BigDecimal(usd)),
        observedAt = at.plusSeconds(1),
        directCapture = DirectBalanceCapture(
            requestStartedAt = at,
            responseEndedAt = at.plusSeconds(1),
            resultShapeValid = true,
            valuesByAssetId = mapOf(
                "XXBT" to evidence(btc),
                "ZUSD" to evidence(usd),
            ),
        ),
    )

    private fun observedPrices(at: Instant, btcPrice: String) = ObservedPrices(
        prices = mapOf("BTC" to BigDecimal(btcPrice)),
        directCapture = DirectTickerCapture(
            requestStartedAt = at.plusSeconds(2),
            responseEndedAt = at.plusSeconds(3),
            resultShapeValid = true,
            marksBySymbol = mapOf(
                "BTC" to DirectTickerMark(
                    symbol = "BTC",
                    requestedPair = "XBTUSD",
                    responsePair = "XXBTZUSD",
                    candidateResponsePairs = listOf("XXBTZUSD"),
                    rawPrice = btcPrice,
                    price = BigDecimal(btcPrice),
                    status = DirectEvidenceStatus.VALID,
                    candidateValuesByPair = mapOf("XXBTZUSD" to evidence(btcPrice, allowZero = false)),
                ),
            ),
        ),
    )

    private fun evidence(value: String, allowZero: Boolean = true) = DirectValueEvidence(
        rawValue = value,
        value = BigDecimal(value),
        status = if (allowZero || BigDecimal(value).signum() > 0) {
            DirectEvidenceStatus.VALID
        } else {
            DirectEvidenceStatus.NON_POSITIVE
        },
    )

    private fun store(paths: Paths) = ActualObservationStore(
        paths.actual.toString(),
        paths.reporting.toString(),
        paths.execution.toString(),
    )

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private suspend fun withTempDirectory(block: suspend (Path) -> Unit) {
        val directory = Files.createTempDirectory("actual-dispatcher-test")
        try {
            block(directory)
        } finally {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    private data class Paths(val actual: Path, val reporting: Path, val execution: Path)

    private companion object {
        val NOW = Instant.parse("2026-10-09T12:00:00Z")
    }
}
