package com.gemini.krakenbot.service.actual.benchmark

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.service.actual.ActualAssetObservation
import com.gemini.krakenbot.service.actual.ActualAssetStatus
import com.gemini.krakenbot.service.actual.ActualObservation
import com.gemini.krakenbot.service.actual.ActualObservationStatus
import com.gemini.krakenbot.service.actual.ActualObservationStore
import com.gemini.krakenbot.service.actual.ActualObservationValuator
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class ProspectiveBenchmarkServiceTest :
    StringSpec({
        val t0 = Instant.parse("2026-03-01T10:00:00Z")
        val t1 = Instant.parse("2026-03-01T10:05:00Z")
        val accountDigest = "a".repeat(64)
        val otherAccountDigest = "b".repeat(64)

        fun createConfig(symbols: List<String>): AppConfig {
            val allSymbols = (symbols + "USD").distinct()
            val count = allSymbols.size
            val target = 100.0 / count
            return TestFixtures.config(
                settings = TestFixtures.settings(dryRun = true, simulation = false, loopDelaySeconds = 60),
                allocations = allSymbols.map { sym ->
                    Allocation(symbol = Asset(sym), targetPercent = target)
                },
            )
        }

        fun createAsset(symbol: String, qty: BigDecimal?, price: BigDecimal?): ActualAssetObservation =
            ActualAssetObservation(
                symbol = symbol,
                status = ActualAssetStatus.COMPLETE,
                rawBalanceKeys = listOf(symbol),
                rawBalanceValues = mapOf(symbol to qty?.toPlainString()),
                quantity = qty,
                requestedPair = null,
                responsePair = null,
                candidateResponsePairs = emptyList(),
                candidateRawPrices = emptyMap(),
                rawPrice = price?.toPlainString(),
                priceUsd = price,
                valueUsd = if (qty != null && price != null) qty.multiply(price) else null,
                reason = null,
            )

        fun createObservation(
            id: String,
            time: Instant,
            account: String,
            symbols: List<String>,
            btcPrice: String = "100.00",
            ethPrice: String = "150.00",
        ): ActualObservation {
            val scopeSymbols = (symbols + "USD").distinct()
            val scopeFingerprint = ActualObservationValuator.scopeFingerprint(scopeSymbols)
            val assets = scopeSymbols.map { sym ->
                when (sym) {
                    "BTC" -> createAsset("BTC", BigDecimal("1.0"), BigDecimal(btcPrice))
                    "ETH" -> createAsset("ETH", BigDecimal("1.0"), BigDecimal(ethPrice))
                    else -> createAsset("USD", BigDecimal("10.00"), BigDecimal("1.00"))
                }
            }
            val totalUsd = assets.mapNotNull { it.valueUsd }.fold(BigDecimal.ZERO, BigDecimal::add)
            return ActualObservation(
                observationId = id,
                accountIdentityDigest = account,
                walletScope = "KRAKEN_DEFAULT_WALLET_CONFIGURED_ALLOCATIONS",
                scopeFingerprint = scopeFingerprint,
                scopeSymbols = scopeSymbols,
                observedAt = time,
                balanceRequestStartedAt = time.minusMillis(200),
                balanceResponseEndedAt = time.minusMillis(100),
                priceRequestStartedAt = time.minusMillis(100),
                priceResponseEndedAt = time,
                persistedAt = time,
                status = ActualObservationStatus.COMPLETE,
                totalUsd = totalUsd,
                incompleteReasons = emptyList(),
                assets = assets,
            )
        }

        fun createSegment(
            account: String = accountDigest,
            scopeSymbols: List<String> = listOf("BTC", "USD"),
            status: BenchmarkSegmentStatus = BenchmarkSegmentStatus.TRACKING,
            lastVerified: Instant = t0,
            terminationReason: String? = null,
        ) = BenchmarkSegment(
            segmentId = "segment-1",
            baselineObservationId = "obs-0",
            accountIdentityDigest = account,
            scopeFingerprint = ActualObservationValuator.scopeFingerprint(scopeSymbols),
            scopeSymbols = scopeSymbols,
            baselineAt = t0,
            initialHoldings = mapOf("BTC" to BigDecimal.ONE, "USD" to BigDecimal("10")),
            baselineMarks = mapOf("BTC" to BigDecimal("100"), "USD" to BigDecimal.ONE),
            baselineTotalUsd = BigDecimal("110"),
            status = status,
            terminationReason = terminationReason,
            lastVerifiedEventTime = lastVerified,
            createdAt = t0,
        )

        fun withTempDirectory(block: (Path) -> Unit) {
            val directory = Files.createTempDirectory("benchmark-test")
            try {
                block(directory)
            } finally {
                Files.walk(directory).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
                }
            }
        }

        "Oracle F: scope change terminates segment" {
            withTempDirectory { dir ->
                val actualDb = dir.resolve("actual.db").toString()
                val reportingDb = dir.resolve("reporting.db").toString()
                val executionDb = dir.resolve("execution.db").toString()

                val obsStore = ActualObservationStore(actualDb, reportingDb, executionDb)
                val benchmarkStore = BenchmarkStore(obsStore)
                val verifier = mockk<ProspectiveEventContinuityVerifier>()

                val service = ProspectiveBenchmarkService(benchmarkStore, verifier)

                val configBtc = createConfig(listOf("BTC"))
                val obs0 = createObservation("obs-0", t0, accountDigest, listOf("BTC"))

                runTest {
                    obsStore.append(obs0)
                    service.startBenchmark(obs0)

                    val configBtcEth = createConfig(listOf("BTC", "ETH"))
                    val obs1 = createObservation("obs-1", t1, accountDigest, listOf("BTC", "ETH"))
                    obsStore.append(obs1)

                    val result = service.evaluate(configBtcEth, accountDigest, listOf(obs0, obs1))
                    result.status shouldBe BenchmarkStatus.TERMINATED
                    result.unavailableReason shouldBe BenchmarkTerminationReason.SCOPE_CHANGED
                    val segment = checkNotNull(result.segment)
                    segment.status shouldBe BenchmarkSegmentStatus.TERMINATED
                    segment.terminationReason shouldBe BenchmarkTerminationReason.SCOPE_CHANGED
                }
            }
        }

        "Oracle G: restart recovers baseline and watermark from SQLite" {
            withTempDirectory { dir ->
                val actualDb = dir.resolve("actual.db").toString()
                val reportingDb = dir.resolve("reporting.db").toString()
                val executionDb = dir.resolve("execution.db").toString()

                val obsStore1 = ActualObservationStore(actualDb, reportingDb, executionDb)
                val benchmarkStore1 = BenchmarkStore(obsStore1)
                val verifier = mockk<ProspectiveEventContinuityVerifier>()
                val service1 = ProspectiveBenchmarkService(benchmarkStore1, verifier)

                val config = createConfig(listOf("BTC"))
                val obs0 = createObservation("obs-0", t0, accountDigest, listOf("BTC"))

                runTest {
                    obsStore1.append(obs0)
                    val started = checkNotNull(service1.startBenchmark(obs0))
                    started.baselineTotalUsd shouldBeEqualComparingTo BigDecimal("110.00")

                    // Restart: create new store instances from the same SQLite file
                    val obsStore2 = ActualObservationStore(actualDb, reportingDb, executionDb)
                    val benchmarkStore2 = BenchmarkStore(obsStore2)
                    val service2 = ProspectiveBenchmarkService(benchmarkStore2, verifier)

                    val scopeSymbols = listOf("BTC", "USD")
                    val scopeFingerprint = ActualObservationValuator.scopeFingerprint(scopeSymbols)
                    val recovered = checkNotNull(benchmarkStore2.getActiveSegment(scopeFingerprint, accountDigest))
                    recovered.segmentId shouldBe started.segmentId
                    recovered.baselineAt shouldBe t0
                    recovered.baselineTotalUsd shouldBeEqualComparingTo BigDecimal("110.00")
                    checkNotNull(recovered.initialHoldings["BTC"]) shouldBeEqualComparingTo BigDecimal("1.0")
                    recovered.lastVerifiedEventTime shouldBe t0

                    val obs1 = createObservation("obs-1", t1, accountDigest, listOf("BTC"), btcPrice = "120.00")
                    obsStore2.append(obs1)

                    coEvery { verifier.verifyContinuity(any(), t1) } returns ContinuityVerificationResult.Continuous(t1)

                    val eval = service2.evaluate(config, accountDigest, listOf(obs0, obs1))
                    eval.status shouldBe BenchmarkStatus.READY
                    eval.points.size shouldBe 2
                    val latestPoint = checkNotNull(eval.latestPoint)
                    latestPoint.holdValueUsd shouldBeEqualComparingTo BigDecimal("130.00") // 1.0*120 + 10 = 130
                    latestPoint.actualValueUsd shouldBeEqualComparingTo BigDecimal("130.00")
                }
            }
        }

        "Oracle H: account change terminates / isolates segment" {
            withTempDirectory { dir ->
                val actualDb = dir.resolve("actual.db").toString()
                val reportingDb = dir.resolve("reporting.db").toString()
                val executionDb = dir.resolve("execution.db").toString()

                val obsStore = ActualObservationStore(actualDb, reportingDb, executionDb)
                val benchmarkStore = BenchmarkStore(obsStore)
                val verifier = mockk<ProspectiveEventContinuityVerifier>()
                val service = ProspectiveBenchmarkService(benchmarkStore, verifier)

                val config = createConfig(listOf("BTC"))
                val obs0 = createObservation("obs-0", t0, accountDigest, listOf("BTC"))

                runTest {
                    obsStore.append(obs0)
                    service.startBenchmark(obs0)

                    // When evaluating under otherAccountDigest
                    val result = service.evaluate(config, otherAccountDigest, listOf(obs0))
                    result.status shouldBe BenchmarkStatus.NO_ACTIVE_SEGMENT
                }
            }
        }

        "benchmark baseline accepts only complete evidence with all required valuation fields" {
            val store = mockk<BenchmarkStore>(relaxed = true)
            val verifier = mockk<ProspectiveEventContinuityVerifier>()
            val service = ProspectiveBenchmarkService(store, verifier) { t1 }
            val observation = createObservation("obs-start", t0, accountDigest, listOf("BTC"))

            runTest {
                val segment = service.startBenchmark(observation)
                segment.baselineObservationId shouldBe "obs-start"
                segment.initialHoldings shouldBe mapOf("BTC" to BigDecimal("1.0"), "USD" to BigDecimal("10.00"))
                segment.baselineMarks shouldBe mapOf("BTC" to BigDecimal("100.00"), "USD" to BigDecimal.ONE)
                segment.baselineTotalUsd shouldBeEqualComparingTo BigDecimal("110.00")
                segment.status shouldBe BenchmarkSegmentStatus.TRACKING
                segment.createdAt shouldBe t1
                coVerify(exactly = 1) { store.saveSegment(segment) }

                shouldThrow<IllegalArgumentException> {
                    service.startBenchmark(observation.copy(status = ActualObservationStatus.INCOMPLETE))
                }
                val missingQuantity = observation.copy(
                    assets = observation.assets.map { asset ->
                        if (asset.symbol == "BTC") asset.copy(quantity = null) else asset
                    },
                )
                shouldThrow<IllegalStateException> { service.startBenchmark(missingQuantity) }
                val missingPrice = observation.copy(
                    assets = observation.assets.map { asset ->
                        if (asset.symbol == "BTC") asset.copy(priceUsd = null) else asset
                    },
                )
                shouldThrow<IllegalStateException> { service.startBenchmark(missingPrice) }
                val missingTotal = observation.copy(totalUsd = null)
                shouldThrow<IllegalStateException> { service.startBenchmark(missingTotal) }
            }
        }

        "benchmark evaluation returns not-started, account-changed, and terminal results" {
            val store = mockk<BenchmarkStore>(relaxed = true)
            val verifier = mockk<ProspectiveEventContinuityVerifier>()
            val config = createConfig(listOf("BTC"))
            val observation = createObservation("obs-0", t0, accountDigest, listOf("BTC"))

            runTest {
                coEvery { store.getActiveSegment(accountDigest) } returns null
                coEvery { store.getLatestSegment(accountDigest) } returns null
                val service = ProspectiveBenchmarkService(store, verifier)
                service.evaluate(config, accountDigest, emptyList()).status shouldBe BenchmarkStatus.NO_ACTIVE_SEGMENT

                coEvery { store.getActiveSegment(otherAccountDigest) } returns createSegment()
                service.evaluate(config, otherAccountDigest, listOf(observation)).let { result ->
                    result.status shouldBe BenchmarkStatus.TERMINATED
                    result.unavailableReason shouldBe BenchmarkTerminationReason.ACCOUNT_CHANGED
                }

                coEvery { store.getActiveSegment(accountDigest) } returns createSegment(
                    status = BenchmarkSegmentStatus.TERMINATED,
                    terminationReason = BenchmarkTerminationReason.MANUAL_TRADE,
                )
                service.evaluate(config, accountDigest, listOf(observation)).let { result ->
                    result.status shouldBe BenchmarkStatus.TERMINATED
                    result.segment?.terminationReason shouldBe BenchmarkTerminationReason.MANUAL_TRADE
                    result.points.size shouldBe 1
                }
                coVerify(exactly = 0) { verifier.verifyContinuity(any(), any()) }
            }
        }

        "active benchmark records pending or terminated continuity and ignores incomplete observations" {
            val store = mockk<BenchmarkStore>(relaxed = true)
            val verifier = mockk<ProspectiveEventContinuityVerifier>()
            val config = createConfig(listOf("BTC"))
            val segment = createSegment()
            val baseline = createObservation("obs-0", t0, accountDigest, listOf("BTC"))
            val later = createObservation("obs-1", t1, accountDigest, listOf("BTC"), btcPrice = "120.00")
            val incomplete = later.copy(status = ActualObservationStatus.INCOMPLETE, totalUsd = null)
            val service = ProspectiveBenchmarkService(store, verifier)

            runTest {
                coEvery { store.getActiveSegment(accountDigest) } returns segment
                coEvery { verifier.verifyContinuity(segment, t1) } returns
                    ContinuityVerificationResult.PendingEvidence("ledger evidence is incomplete")
                val pending = service.evaluate(config, accountDigest, listOf(baseline, incomplete, later))
                pending.status shouldBe BenchmarkStatus.PENDING_EVIDENCE
                pending.unavailableReason shouldBe "ledger evidence is incomplete"

                coEvery { verifier.verifyContinuity(segment, t1) } returns
                    ContinuityVerificationResult.Terminated("external deposit", t1)
                val terminated = service.evaluate(config, accountDigest, listOf(baseline, later))
                terminated.status shouldBe BenchmarkStatus.TERMINATED
                terminated.segment?.terminationReason shouldBe "external deposit"
                coVerify {
                    store.updateSegmentStatus(
                        segment.segmentId,
                        BenchmarkSegmentStatus.TERMINATED,
                        "external deposit",
                        t1,
                    )
                }

                val terminalScopeMismatch = createSegment(
                    scopeSymbols = listOf("ETH", "USD"),
                    status = BenchmarkSegmentStatus.TERMINATED,
                    terminationReason = "already terminated",
                )
                coEvery { store.getActiveSegment(accountDigest) } returns terminalScopeMismatch
                val changedScope = service.evaluate(config, accountDigest, listOf(baseline))
                changedScope.status shouldBe BenchmarkStatus.TERMINATED
                coVerify(exactly = 0) {
                    store.updateSegmentStatus(
                        terminalScopeMismatch.segmentId,
                        BenchmarkSegmentStatus.TERMINATED,
                        BenchmarkTerminationReason.SCOPE_CHANGED,
                        any(),
                    )
                }
            }
        }
    })
