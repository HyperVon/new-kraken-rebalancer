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
import com.gemini.krakenbot.service.actual.benchmark.BenchmarkComparisonResult
import com.gemini.krakenbot.service.actual.benchmark.BenchmarkSegment
import com.gemini.krakenbot.service.actual.benchmark.BenchmarkSegmentStatus
import com.gemini.krakenbot.service.actual.benchmark.BenchmarkStatus
import com.gemini.krakenbot.service.actual.benchmark.ProspectiveBenchmarkService
import com.gemini.krakenbot.service.impl.ExecutionAccountBindingService
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ActualObservationDispatcherTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "direct dry-run captures persist without an execution binding and continue across restart" {
            runTest {
                withTempDirectory { directory ->
                    val paths = Paths(
                        actual = directory.resolve("actual.db"),
                        reporting = directory.resolve("reporting.db"),
                        execution = directory.resolve("execution.db"),
                    )
                    val store = store(paths)
                    val credentialScope = "test-credential-generation"
                    val exchange = mockk<KrakenService>(relaxed = true)
                    coEvery { exchange.getAuthenticatedAccountIdentity() } returns ACCOUNT_IDENTITY
                    val kraken = object : KrakenService by exchange {
                        override suspend fun getFundingEvidenceScope(): String = credentialScope

                        override suspend fun <T> withStableBackend(block: suspend (KrakenService) -> T): T = block(this)
                    }
                    val config = TestFixtures.config(
                        settings = TestFixtures.settings(dryRun = true, simulation = false),
                        allocations = listOf(Allocation(Asset.BTC, 80.0), Allocation(Asset.USD, 20.0)),
                    )
                    val first = ActualObservationDispatcher(
                        store = store,
                        krakenService = kraken,
                        nowProvider = { NOW.plusSeconds(20) },
                    )
                    val firstWorker = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                    try {
                        first.start(firstWorker)
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

                    val restarted = ActualObservationDispatcher(
                        store = store(paths),
                        krakenService = kraken,
                        nowProvider = { NOW.plusSeconds(20) },
                    )
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

                    coVerify(atLeast = 1) { exchange.getAuthenticatedAccountIdentity() }
                    coVerify(exactly = 0) {
                        exchange.executeOrder(any(), any(), any(), any(), any(), any())
                    }
                    Files.exists(paths.reporting) shouldBe false
                    Files.exists(paths.execution) shouldBe false
                }
            }
        }

        "missing authenticated identity prevents account history access; simulation stays unavailable" {
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
                    coEvery { exchange.getAuthenticatedAccountIdentity() } returns null
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
                    coVerify(exactly = 1) { exchange.getAuthenticatedAccountIdentity() }
                }
            }
        }

        "authentication endpoint errors fail closed for Actual and baseline start" {
            runTest {
                val config = TestFixtures.config(
                    settings = TestFixtures.settings(dryRun = true, simulation = false),
                    allocations = listOf(Allocation(Asset.BTC, 80.0), Allocation(Asset.USD, 20.0)),
                )
                val exchange = mockk<KrakenService>(relaxed = true)
                coEvery { exchange.getAuthenticatedAccountIdentity() } throws
                    IllegalStateException("isolated identity failure")
                val kraken = object : KrakenService by exchange {
                    override suspend fun getFundingEvidenceScope(): String = "test-credential-generation"

                    override suspend fun <T> withStableBackend(block: suspend (KrakenService) -> T): T = block(this)
                }
                val actualStore = mockk<ActualObservationStore>()
                val benchmark = mockk<ProspectiveBenchmarkService>()
                val target = dispatcher(actualStore, mockk(), mockk(), kraken, benchmark)

                val page = target.readCurrent(config)
                page.state shouldBe ActualPageState.ACCOUNT_UNVERIFIED
                page.lastCaptureIssue shouldBe com.gemini.krakenbot.view.util.ViewText.ACTUAL_IDENTITY_UNAVAILABLE
                target.startBenchmark(config) shouldBe false
                coVerify(exactly = 0) { actualStore.querySegment(any(), any(), any()) }
                coVerify(exactly = 0) { benchmark.startBenchmark(any()) }

                val scopeUnavailableExchange = mockk<KrakenService>(relaxed = true)
                val scopeUnavailableBackend = object : KrakenService by scopeUnavailableExchange {
                    override suspend fun getFundingEvidenceScope(): String = "scope-unavailable"

                    override suspend fun <T> withStableBackend(block: suspend (KrakenService) -> T): T = block(this)
                }
                val unavailablePage = dispatcher(
                    mockk(),
                    mockk(),
                    mockk(),
                    scopeUnavailableBackend,
                ).readCurrent(config)
                unavailablePage.state shouldBe ActualPageState.ACCOUNT_UNVERIFIED
                coVerify(exactly = 0) { scopeUnavailableExchange.getAuthenticatedAccountIdentity() }

                val cancelledExchange = mockk<KrakenService>(relaxed = true)
                val cancellation = kotlinx.coroutines.CancellationException("isolated identity cancellation")
                coEvery { cancelledExchange.getAuthenticatedAccountIdentity() } throws cancellation
                val cancelledBackend = object : KrakenService by cancelledExchange {
                    override suspend fun getFundingEvidenceScope(): String = "test-credential-generation"

                    override suspend fun <T> withStableBackend(block: suspend (KrakenService) -> T): T = block(this)
                }
                val cancelledTarget = dispatcher(mockk(), mockk(), mockk(), cancelledBackend, benchmark)
                var historyCancellationPropagated = false
                try {
                    cancelledTarget.readCurrent(config)
                } catch (failure: kotlinx.coroutines.CancellationException) {
                    failure shouldBe cancellation
                    historyCancellationPropagated = true
                }
                historyCancellationPropagated shouldBe true
                var benchmarkCancellationPropagated = false
                try {
                    cancelledTarget.startBenchmark(config)
                } catch (failure: kotlinx.coroutines.CancellationException) {
                    failure shouldBe cancellation
                    benchmarkCancellationPropagated = true
                }
                benchmarkCancellationPropagated shouldBe true
            }
        }

        "queued observations are discarded if the credential scope changes before persistence" {
            runTest {
                val config = TestFixtures.config(
                    settings = TestFixtures.settings(dryRun = true, simulation = false),
                    allocations = listOf(Allocation(Asset.BTC, 80.0), Allocation(Asset.USD, 20.0)),
                )
                val originalScope = "test-credential-generation"
                val rotatedScope = "rotated-credential-generation"
                val scopeCalls = AtomicInteger()
                val identityCheckStarted = CompletableDeferred<Unit>()
                val continueIdentityCheck = CompletableDeferred<Unit>()
                val exchange = mockk<KrakenService>(relaxed = true)
                coEvery { exchange.getAuthenticatedAccountIdentity() } returns ACCOUNT_IDENTITY
                val kraken = object : KrakenService by exchange {
                    override suspend fun getFundingEvidenceScope(): String = when (scopeCalls.incrementAndGet()) {
                        1 -> originalScope

                        2 -> {
                            identityCheckStarted.complete(Unit)
                            continueIdentityCheck.await()
                            rotatedScope
                        }

                        else -> rotatedScope
                    }

                    override suspend fun <T> withStableBackend(block: suspend (KrakenService) -> T): T = block(this)
                }
                val accountDigest = digest(ACCOUNT_IDENTITY)
                val scopeFingerprint = ActualObservationValuator.scopeFingerprint(listOf("BTC", "USD"))
                val actualStore = mockk<ActualObservationStore>()
                coEvery { actualStore.querySegment(scopeFingerprint, accountDigest, 250) } returns emptyList()
                val target = dispatcher(actualStore, mockk(), mockk(), kraken)
                val writer = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
                try {
                    target.start(writer)
                    target.captureAfterCycle(
                        "credential-rotated",
                        config,
                        observedBalances(NOW, "1.0", "100.00"),
                        observedPrices(NOW, "100.00"),
                    ) shouldBe true
                    withTimeout(5_000) { identityCheckStarted.await() }
                    continueIdentityCheck.complete(Unit)

                    var page = target.readCurrent(config)
                    withTimeout(5_000) {
                        while (
                            page.lastCaptureIssue !=
                            com.gemini.krakenbot.view.util.ViewText.ACTUAL_IDENTITY_UNAVAILABLE
                        ) {
                            delay(10)
                            page = target.readCurrent(config)
                        }
                    }
                    coVerify(exactly = 0) { actualStore.append(any()) }
                } finally {
                    continueIdentityCheck.complete(Unit)
                    writer.cancel()
                }
            }
        }

        "capture rejects non-live evidence and preserves a bounded-queue issue" {
            val bindings = mockk<ExecutionAccountBindingRepository>()
            coEvery { bindings.load() } returns null
            val accountService = mockk<ExecutionAccountBindingService>()
            val exchange = mockk<KrakenService>(relaxed = true)
            coEvery { exchange.getAuthenticatedAccountIdentity() } returns ACCOUNT_IDENTITY
            var credentialScope = "test-credential-generation"
            val kraken = object : KrakenService by exchange {
                override suspend fun getFundingEvidenceScope(): String = credentialScope

                override suspend fun <T> withStableBackend(block: suspend (KrakenService) -> T): T = block(this)
            }
            val target = dispatcher(
                store = mockk<ActualObservationStore>(),
                accountService = accountService,
                bindings = bindings,
                kraken = kraken,
            )
            val config = TestFixtures.config(
                settings = TestFixtures.settings(dryRun = true, simulation = false),
                allocations = listOf(Allocation(Asset.BTC, 80.0), Allocation(Asset.USD, 20.0)),
            )
            val balances = observedBalances(NOW, "1.0", "100.00")
            val prices = observedPrices(NOW, "100.00")
            val queueStore = mockk<ActualObservationStore>()
            coEvery {
                queueStore.querySegment(
                    ActualObservationValuator.scopeFingerprint(listOf("BTC", "USD")),
                    digest(ACCOUNT_IDENTITY),
                    250,
                )
            } returns emptyList()
            val queueTarget = dispatcher(
                store = queueStore,
                accountService = accountService,
                bindings = bindings,
                kraken = kraken,
            )

            target.captureAfterCycle(
                "simulation",
                config.copy(settings = config.settings.copy(simulation = true)),
                balances,
                prices,
            ) shouldBe false
            target.captureAfterCycle("missing-balance", config, balances.copy(directCapture = null), prices) shouldBe
                false
            target.captureAfterCycle("missing-price", config, balances, prices.copy(directCapture = null)) shouldBe
                false

            credentialScope = " "
            target.captureAfterCycle("blank-scope", config, balances, prices) shouldBe false
            credentialScope = "scope-unavailable"
            target.captureAfterCycle("unavailable-scope", config, balances, prices) shouldBe false
            credentialScope = "test-credential-generation"

            queueTarget.captureAfterCycle("queued-1", config, balances, prices) shouldBe true
            queueTarget.captureAfterCycle("queued-2", config, balances, prices) shouldBe true
            queueTarget.captureAfterCycle("queue-full", config, balances, prices) shouldBe false
            val page = queueTarget.readCurrent(config)
            page.state shouldBe ActualPageState.NO_OBSERVATIONS
            page.lastCaptureIssue?.contains("bounded writer queue is full") shouldBe true
        }

        "credential lookup and observation storage failures stay outside the live cycle path" {
            val config = TestFixtures.config(
                settings = TestFixtures.settings(dryRun = true, simulation = false),
                allocations = listOf(Allocation(Asset.BTC, 80.0), Allocation(Asset.USD, 20.0)),
            )
            val balances = observedBalances(NOW, "1.0", "100.00")
            val prices = observedPrices(NOW, "100.00")
            val exchange = mockk<KrakenService>(relaxed = true)
            val failingCredentialBackend = object : KrakenService by exchange {
                override suspend fun getFundingEvidenceScope(): String =
                    throw IllegalStateException("credential scope is unavailable")

                override suspend fun <T> withStableBackend(block: suspend (KrakenService) -> T): T = block(this)
            }
            val captureDispatcher = dispatcher(
                store = mockk(),
                accountService = mockk(),
                bindings = mockk(),
                kraken = failingCredentialBackend,
            )
            captureDispatcher.captureAfterCycle("scope-failure", config, balances, prices) shouldBe false

            val credentialScope = "test-credential-generation"
            val accountDigest = digest(ACCOUNT_IDENTITY)
            coEvery { exchange.getAuthenticatedAccountIdentity() } returns ACCOUNT_IDENTITY
            val bindings = mockk<ExecutionAccountBindingRepository>()
            coEvery { bindings.load() } returns ExecutionAccountBinding(
                accountIdentityDigest = accountDigest,
                credentialGenerationDigest = digest(credentialScope),
                bindingVersion = 1,
                verificationMethod = "isolated-test",
                verifiedAtMillis = NOW.toEpochMilli(),
                auditVerified = true,
            )
            val failingStore = mockk<ActualObservationStore>()
            val scopeFingerprint = ActualObservationValuator.scopeFingerprint(listOf("BTC", "USD"))
            coEvery { failingStore.querySegment(scopeFingerprint, accountDigest, 250) } throws
                IllegalStateException("isolated database failure")
            val readableCredentialBackend = object : KrakenService by exchange {
                override suspend fun getFundingEvidenceScope(): String = credentialScope

                override suspend fun <T> withStableBackend(block: suspend (KrakenService) -> T): T = block(this)
            }
            val historyDispatcher = dispatcher(
                store = failingStore,
                accountService = mockk(),
                bindings = bindings,
                kraken = readableCredentialBackend,
            )

            val page = historyDispatcher.readCurrent(config)
            page.state shouldBe ActualPageState.STORAGE_UNAVAILABLE
            page.observations shouldBe emptyList()
            page.lastCaptureIssue shouldBe "Observation history is temporarily unavailable."
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
                    val accountDigest = digest(ACCOUNT_IDENTITY)
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
                    coEvery { exchange.getAuthenticatedAccountIdentity() } returns ACCOUNT_IDENTITY
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

        "slow benchmark evaluation times out without indefinitely delaying Actual observation page" {
            runTest {
                withTempDirectory { directory ->
                    val paths = Paths(
                        actual = directory.resolve("actual.db"),
                        reporting = directory.resolve("reporting.db"),
                        execution = directory.resolve("execution.db"),
                    )
                    val store = store(paths)
                    val accountDigest = digest(ACCOUNT_IDENTITY)
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
                    coEvery { exchange.getAuthenticatedAccountIdentity() } returns ACCOUNT_IDENTITY
                    val kraken = object : KrakenService by exchange {
                        override suspend fun getFundingEvidenceScope(): String = credentialScope

                        override suspend fun <T> withStableBackend(block: suspend (KrakenService) -> T): T = block(this)
                    }
                    val benchmarkService = mockk<ProspectiveBenchmarkService>()
                    // Simulate an indefinitely hanging or slow benchmark call
                    coEvery { benchmarkService.evaluate(any(), any(), any()) } coAnswers {
                        delay(500)
                        mockk<BenchmarkComparisonResult>()
                    }

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
                        benchmarkTimeoutMs = 50L,
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

        "writer reports missing identity and storage failures outside the live-cycle caller" {
            runTest {
                val credentialScope = "test-credential-generation"
                val accountDigest = digest(ACCOUNT_IDENTITY)
                val config = TestFixtures.config(
                    settings = TestFixtures.settings(dryRun = true, simulation = false),
                    allocations = listOf(Allocation(Asset.BTC, 80.0), Allocation(Asset.USD, 20.0)),
                )
                val scopeFingerprint = ActualObservationValuator.scopeFingerprint(listOf("BTC", "USD"))
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
                val exchange = mockk<KrakenService>(relaxed = true)
                coEvery { exchange.getAuthenticatedAccountIdentity() } returns ACCOUNT_IDENTITY
                val kraken = object : KrakenService by exchange {
                    override suspend fun getFundingEvidenceScope(): String = credentialScope

                    override suspend fun <T> withStableBackend(block: suspend (KrakenService) -> T): T = block(this)
                }
                val actualStoreWithoutBinding = mockk<ActualObservationStore>()
                coEvery { actualStoreWithoutBinding.querySegment(scopeFingerprint, accountDigest, 250) } returns
                    emptyList()
                var identityCalls = 0
                val krakenWithoutIdentity = object : KrakenService by exchange {
                    override suspend fun getFundingEvidenceScope(): String = credentialScope

                    override suspend fun getAuthenticatedAccountIdentity(): String? {
                        identityCalls += 1
                        return null
                    }

                    override suspend fun <T> withStableBackend(block: suspend (KrakenService) -> T): T = block(this)
                }
                val noBindingDispatcher = dispatcher(
                    actualStoreWithoutBinding,
                    mockk(),
                    bindings,
                    krakenWithoutIdentity,
                )
                val noBindingWorker = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                try {
                    noBindingDispatcher.start(noBindingWorker)
                    noBindingDispatcher.captureAfterCycle(
                        "no-verified-binding",
                        config,
                        observedBalances(NOW, "1.0", "100.00"),
                        observedPrices(NOW, "100.00"),
                    ) shouldBe true
                    var page = noBindingDispatcher.readCurrent(config)
                    withTimeout(5_000) {
                        while (page.lastCaptureIssue !=
                            com.gemini.krakenbot.view.util.ViewText.ACTUAL_IDENTITY_UNAVAILABLE
                        ) {
                            delay(10)
                            page = noBindingDispatcher.readCurrent(config)
                        }
                    }
                    (identityCalls > 0) shouldBe true
                } finally {
                    noBindingWorker.cancel()
                }

                val failingStore = mockk<ActualObservationStore>()
                coEvery { failingStore.querySegment(scopeFingerprint, accountDigest, 250) } returns emptyList()
                coEvery { failingStore.append(any()) } throws IllegalStateException("isolated storage failure")
                val failingDispatcher = dispatcher(failingStore, mockk(), bindings, kraken)
                val failingWorker = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                try {
                    failingDispatcher.start(failingWorker)
                    failingDispatcher.captureAfterCycle(
                        "storage-failure",
                        config,
                        observedBalances(NOW, "1.0", "100.00"),
                        observedPrices(NOW, "100.00"),
                    ) shouldBe true
                    coVerify(timeout = 5_000) { failingStore.append(any()) }
                    var page = failingDispatcher.readCurrent(config)
                    withTimeout(5_000) {
                        while (page.lastCaptureIssue != "Actual observation could not be saved.") {
                            delay(10)
                            page = failingDispatcher.readCurrent(config)
                        }
                    }
                } finally {
                    failingWorker.cancel()
                }
            }
        }

        "benchmark start requires authenticated identity and a complete current observation" {
            val accountDigest = digest(ACCOUNT_IDENTITY)
            val config = TestFixtures.config(
                settings = TestFixtures.settings(dryRun = true, simulation = false),
                allocations = listOf(Allocation(Asset.BTC, 80.0), Allocation(Asset.USD, 20.0)),
            )
            val balances = checkNotNull(observedBalances(NOW, "1.0", "100.00").directCapture)
            val prices = checkNotNull(observedPrices(NOW, "100.00").directCapture)
            val complete = ActualObservationValuator.build(
                observationId = "benchmark-baseline",
                scopeSymbols = listOf("BTC", "USD"),
                accountIdentityDigest = accountDigest,
                balances = balances,
                prices = prices,
                now = NOW.plusSeconds(20),
            )
            val actualStore = mockk<ActualObservationStore>()
            val scopeFingerprint = ActualObservationValuator.scopeFingerprint(listOf("BTC", "USD"))
            coEvery { actualStore.querySegment(scopeFingerprint, accountDigest, 50) } returns listOf(complete)
            val bindings = mockk<ExecutionAccountBindingRepository>()
            val benchmarkService = mockk<ProspectiveBenchmarkService>()
            val segment = BenchmarkSegment(
                segmentId = "segment-1",
                baselineObservationId = complete.observationId,
                accountIdentityDigest = accountDigest,
                scopeFingerprint = scopeFingerprint,
                scopeSymbols = complete.scopeSymbols,
                baselineAt = complete.observedAt,
                initialHoldings = mapOf("BTC" to BigDecimal.ONE, "USD" to BigDecimal("100")),
                baselineMarks = mapOf("BTC" to BigDecimal("100"), "USD" to BigDecimal.ONE),
                baselineTotalUsd = BigDecimal("200"),
                status = BenchmarkSegmentStatus.TRACKING,
                lastVerifiedEventTime = complete.observedAt,
                createdAt = NOW.plusSeconds(10),
            )
            coEvery { benchmarkService.startBenchmark(complete) } returns segment
            val credentialScope = "test-credential-generation"
            val benchmarkBackend = object : KrakenService by mockk(relaxed = true) {
                override suspend fun getFundingEvidenceScope(): String = credentialScope

                override suspend fun getAuthenticatedAccountIdentity(): String = ACCOUNT_IDENTITY

                override suspend fun <T> withStableBackend(block: suspend (KrakenService) -> T): T = block(this)
            }
            val target = dispatcher(
                store = actualStore,
                accountService = mockk(),
                bindings = bindings,
                kraken = benchmarkBackend,
                benchmarkService = benchmarkService,
            )

            target.startBenchmark(config.copy(settings = config.settings.copy(simulation = true))) shouldBe false
            dispatcher(
                store = actualStore,
                accountService = mockk(),
                bindings = bindings,
                kraken = mockk(relaxed = true),
            ).startBenchmark(config) shouldBe false

            coEvery { actualStore.querySegment(scopeFingerprint, accountDigest, 50) } returns
                listOf(complete.copy(status = ActualObservationStatus.INCOMPLETE))
            target.startBenchmark(config) shouldBe false

            coEvery { actualStore.querySegment(scopeFingerprint, accountDigest, 50) } returns listOf(complete)
            target.startBenchmark(config) shouldBe true
            coVerify(exactly = 1) { benchmarkService.startBenchmark(complete) }
        }
    }

    private fun dispatcher(
        store: ActualObservationStore,
        accountService: ExecutionAccountBindingService,
        bindings: ExecutionAccountBindingRepository,
        kraken: KrakenService,
        benchmarkService: ProspectiveBenchmarkService? = null,
        benchmarkTimeoutMs: Long = 3_000L,
    ) = ActualObservationDispatcher(
        store = store,
        krakenService = kraken,
        nowProvider = { NOW.plusSeconds(20) },
        benchmarkService = benchmarkService,
        benchmarkTimeoutMs = benchmarkTimeoutMs,
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
        const val ACCOUNT_IDENTITY = "IIBAN-ISOLATED-TEST-ACCOUNT"
    }
}
