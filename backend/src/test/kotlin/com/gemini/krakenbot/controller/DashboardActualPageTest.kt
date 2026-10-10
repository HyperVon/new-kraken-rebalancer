package com.gemini.krakenbot.controller

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.KrakenCredentials
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
import com.gemini.krakenbot.service.actual.ActualAssetObservation
import com.gemini.krakenbot.service.actual.ActualAssetStatus
import com.gemini.krakenbot.service.actual.ActualObservation
import com.gemini.krakenbot.service.actual.ActualObservationDispatcher
import com.gemini.krakenbot.service.actual.ActualObservationPage
import com.gemini.krakenbot.service.actual.ActualObservationStatus
import com.gemini.krakenbot.service.actual.ActualObservationStore
import com.gemini.krakenbot.service.actual.ActualPageState
import com.gemini.krakenbot.service.impl.ExecutionAccountBindingService
import com.gemini.krakenbot.view.component.renderActualObservationChartSvg
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.mockk.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant

class DashboardActualPageTest : DashboardControllerTestBase() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "actual page shows latest direct evidence and breaks the value line at incomplete samples" {
            val config = AppConfig(
                kraken = KrakenCredentials(TestFixtures.TEST_SERVER_API_KEY, TestFixtures.TEST_SERVER_API_SECRET),
                settings = TestFixtures.settings(dryRun = false, loopDelaySeconds = 60, simulation = false),
                allocations = listOf(
                    Allocation(Asset.BTC, 60.0),
                    Allocation(Asset.ETH, 30.0),
                    Allocation(Asset.USD, 10.0),
                ),
            )
            val page = ActualObservationPage(
                state = ActualPageState.READY,
                scopeSymbols = listOf("BTC", "ETH", "USD"),
                scopeFingerprint = "f".repeat(64),
                accountReference = "ab12cd34",
                observations = listOf(
                    observation("cycle-1", "2026-10-09T12:00:00Z", ActualObservationStatus.COMPLETE, "1000.0000"),
                    observation("cycle-2", "2026-10-09T12:01:00Z", ActualObservationStatus.COMPLETE, "1100.0000"),
                    observation("cycle-3", "2026-10-09T12:02:00Z", ActualObservationStatus.INCOMPLETE, null),
                    observation("cycle-4", "2026-10-09T12:03:00Z", ActualObservationStatus.COMPLETE, "1234.5600"),
                    observation("cycle-5", "2026-10-09T12:04:00Z", ActualObservationStatus.COMPLETE, "1250.0000"),
                ),
                stale = false,
                lastCaptureIssue = null,
            )
            every { configService.getConfig() } returns config
            coEvery { actualObservationDispatcher.readCurrent(config, 250) } returns page

            testApplication {
                application { configureTestEnv() }

                val response = client.get("/actual")
                val html = response.bodyAsText()
                response.status shouldBe HttpStatusCode.OK
                html shouldContain "Actual Observations - Kraken Rebalancer"
                html shouldContain "LIVE TRADING"
                html shouldContain "href=\"/actual\""
                html shouldContain "Bound account reference"
                html shouldContain "ab12cd34"
                html shouldContain "BTC, ETH, USD"
                html shouldContain "Other Kraken balances are excluded and not valued."
                html shouldContain "Observed managed portfolio value"
                html shouldContain "Price request window"
                html shouldContain "2026-10-09T12:03:59Z"
                html shouldContain "$1,250.0000"
                html shouldContain "Observed balance"
                html shouldContain "USD mark"
                html shouldContain "Marked value"
                html shouldContain "2026-10-09T12:04:00Z"
                html shouldContain "actual-gap-marker"
                html.countOccurrences("<polyline") shouldBe 2
                Regex("<polyline points=\"([^\"]+)\"")
                    .findAll(html)
                    .map { match ->
                        match.groupValues[1].split(' ').map { point -> point.substringBefore(',') }
                    }
                    .toList() shouldBe listOf(listOf("14.00", "247.00"), listOf("713.00", "946.00"))
                coVerify(exactly = 1) { actualObservationDispatcher.readCurrent(config, 250) }
            }
        }

        "actual chart uses elapsed time and restores chronological order" {
            val svg = renderActualObservationChartSvg(
                listOf(
                    observation("late", "2026-10-09T12:02:00Z", ActualObservationStatus.COMPLETE, "300"),
                    observation("middle", "2026-10-09T12:00:30Z", ActualObservationStatus.COMPLETE, "200"),
                    observation("early", "2026-10-09T12:00:00Z", ActualObservationStatus.COMPLETE, "100"),
                ),
                loopDelaySeconds = 60,
            )

            svg shouldContain "points=\"14.00,248.00 247.00,130.00 946.00,12.00\""
            svg shouldNotContain "NaN"
            svg shouldNotContain "Infinity"
        }

        "actual chart breaks long unobserved intervals and marks persisted incomplete samples" {
            val missingInterval = renderActualObservationChartSvg(
                listOf(
                    observation("before-gap", "2026-10-09T12:00:00Z", ActualObservationStatus.COMPLETE, "100"),
                    observation("near-gap", "2026-10-09T12:01:00Z", ActualObservationStatus.COMPLETE, "110"),
                    observation("after-gap", "2026-10-09T16:00:00Z", ActualObservationStatus.COMPLETE, "120"),
                ),
                loopDelaySeconds = 60,
            )
            val incompleteSample = renderActualObservationChartSvg(
                listOf(
                    observation("before-incomplete", "2026-10-09T12:00:00Z", ActualObservationStatus.COMPLETE, "100"),
                    observation("incomplete", "2026-10-09T12:01:00Z", ActualObservationStatus.INCOMPLETE, null),
                    observation("after-incomplete", "2026-10-09T12:02:00Z", ActualObservationStatus.COMPLETE, "120"),
                ),
                loopDelaySeconds = 60,
            )

            missingInterval.countOccurrences("<polyline") shouldBe 1
            missingInterval shouldContain "points=\"14.00,248.00 17.88,130.00\""
            missingInterval.countOccurrences("actual-gap-marker") shouldBe 0
            incompleteSample.countOccurrences("<polyline") shouldBe 0
            incompleteSample.countOccurrences("actual-gap-marker") shouldBe 1
            incompleteSample.countOccurrences("<circle") shouldBe 2
        }

        "actual chart centers one point and renders equal timestamps without invalid coordinates" {
            val single = renderActualObservationChartSvg(
                listOf(observation("single", "2026-10-09T12:00:00Z", ActualObservationStatus.COMPLETE, "100")),
                loopDelaySeconds = 60,
            )
            val zeroSpan = renderActualObservationChartSvg(
                listOf(
                    observation("same-time-low", "2026-10-09T12:00:00Z", ActualObservationStatus.COMPLETE, "100"),
                    observation("same-time-high", "2026-10-09T12:00:00Z", ActualObservationStatus.COMPLETE, "200"),
                ),
                loopDelaySeconds = 60,
            )

            single shouldContain "<circle cx=\"480.00\" cy=\"130.00\""
            single.countOccurrences("<polyline") shouldBe 0
            zeroSpan shouldContain "points=\"480.00,248.00 480.00,12.00\""
            zeroSpan shouldNotContain "NaN"
            zeroSpan shouldNotContain "Infinity"
        }

        "incomplete latest sample has no managed total and remains visibly incomplete" {
            val config = TestFixtures.config(
                settings = TestFixtures.settings(dryRun = false, simulation = false),
                allocations = TestFixtures.DEFAULT_TEST_ALLOCATIONS,
            )
            val incomplete = observation(
                "cycle-incomplete",
                "2026-10-09T12:05:00Z",
                ActualObservationStatus.INCOMPLETE,
                null,
            )
            every { configService.getConfig() } returns config
            coEvery { actualObservationDispatcher.readCurrent(config, 250) } returns ActualObservationPage(
                state = ActualPageState.READY,
                scopeSymbols = listOf("BTC", "ETH", "USD"),
                scopeFingerprint = "f".repeat(64),
                accountReference = "ab12cd34",
                observations = listOf(incomplete),
                stale = true,
                lastCaptureIssue = "Observation sample skipped.",
            )

            testApplication {
                application { configureTestEnv() }

                val html = client.get("/actual").bodyAsText()
                html shouldContain "No complete managed value has been captured for this account and scope yet."
                html shouldContain "Incomplete direct observation"
                html shouldContain "Stale"
                html shouldContain "Observation sample skipped."
                html shouldNotContain "$0.00"
            }
        }

        "file-backed direct capture reaches the Actual page without order or history calls" {
            runTest {
                withTempDirectory { directory ->
                    val now = Instant.parse("2026-10-09T12:10:00Z")
                    val credentialScope = "isolated-smoke-credential-generation"
                    val accountDigest = "a".repeat(64)
                    val bindingRepository = mockk<ExecutionAccountBindingRepository>()
                    coEvery { bindingRepository.load() } returns ExecutionAccountBinding(
                        accountIdentityDigest = accountDigest,
                        credentialGenerationDigest = digest(credentialScope),
                        bindingVersion = 1,
                        verificationMethod = "isolated-smoke",
                        verifiedAtMillis = now.toEpochMilli(),
                        auditVerified = true,
                    )
                    val bindingService = mockk<ExecutionAccountBindingService>()
                    coEvery {
                        bindingService.verifiedAccountIdentityDigestForObservation(credentialScope)
                    } returns accountDigest
                    val exchange = mockk<KrakenService>(relaxed = true)
                    val kraken = object : KrakenService by exchange {
                        override suspend fun getFundingEvidenceScope(): String = credentialScope

                        override suspend fun <T> withStableBackend(block: suspend (KrakenService) -> T): T = block(this)
                    }
                    val config = AppConfig(
                        kraken = KrakenCredentials(
                            TestFixtures.TEST_SERVER_API_KEY,
                            TestFixtures.TEST_SERVER_API_SECRET,
                        ),
                        settings = TestFixtures.settings(dryRun = true, simulation = false),
                        allocations = listOf(Allocation(Asset.BTC, 80.0), Allocation(Asset.USD, 20.0)),
                    )
                    every { configService.getConfig() } returns config
                    val realDispatcher = ActualObservationDispatcher(
                        store = ActualObservationStore(
                            actualDatabasePath = directory.resolve("actual.db").toString(),
                            reportingDatabasePath = directory.resolve("reporting.db").toString(),
                            executionDatabasePath = directory.resolve("execution.db").toString(),
                        ),
                        accountBindingService = bindingService,
                        bindingRepository = bindingRepository,
                        krakenService = kraken,
                        nowProvider = { now.plusSeconds(5) },
                    )
                    coEvery { actualObservationDispatcher.readCurrent(config, 250) } coAnswers {
                        realDispatcher.readCurrent(config, 250)
                    }
                    val worker = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                    try {
                        realDispatcher.start(worker)
                        val balanceCapture = DirectBalanceCapture(
                            requestStartedAt = now,
                            responseEndedAt = now.plusSeconds(1),
                            resultShapeValid = true,
                            valuesByAssetId = mapOf(
                                "XXBT" to directValue("1.0"),
                                "ZUSD" to directValue("100.00"),
                            ),
                        )
                        val tickerCapture = DirectTickerCapture(
                            requestStartedAt = now.plusSeconds(2),
                            responseEndedAt = now.plusSeconds(3),
                            resultShapeValid = true,
                            marksBySymbol = mapOf(
                                "BTC" to DirectTickerMark(
                                    symbol = "BTC",
                                    requestedPair = "XBTUSD",
                                    responsePair = "XXBTZUSD",
                                    candidateResponsePairs = listOf("XXBTZUSD"),
                                    rawPrice = "100.00",
                                    price = BigDecimal("100.00"),
                                    status = DirectEvidenceStatus.VALID,
                                    candidateValuesByPair = mapOf("XXBTZUSD" to directValue("100.00")),
                                ),
                            ),
                        )
                        realDispatcher.captureAfterCycle(
                            cycleId = "runtime-smoke-cycle-1",
                            config = config,
                            balances = ObservedBalances(
                                balances = mapOf("XXBT" to BigDecimal("1.0"), "ZUSD" to BigDecimal("100.00")),
                                observedAt = balanceCapture.responseEndedAt,
                                directCapture = balanceCapture,
                            ),
                            prices = ObservedPrices(
                                prices = mapOf("BTC" to BigDecimal("100.00")),
                                directCapture = tickerCapture,
                            ),
                        ) shouldBe true

                        val deadline = System.nanoTime() + 5_000_000_000L
                        while (realDispatcher.readCurrent(config).observations.isEmpty()) {
                            check(System.nanoTime() < deadline) { "Timed out waiting for persisted smoke observation." }
                            Thread.sleep(10)
                        }

                        testApplication {
                            application { configureTestEnv() }
                            val response = client.get("/actual")
                            val html = response.bodyAsText()
                            response.status shouldBe HttpStatusCode.OK
                            html shouldContain "Observed managed portfolio value"
                            html shouldContain "$200.00"
                            html shouldContain "Complete direct observation"
                            html shouldContain "BTC, USD"
                        }

                        coVerify(exactly = 0) {
                            exchange.executeOrder(any(), any(), any(), any(), any(), any())
                        }
                        Files.exists(directory.resolve("reporting.db")) shouldBe false
                        Files.exists(directory.resolve("execution.db")) shouldBe false
                    } finally {
                        worker.cancel()
                    }
                }
            }
        }
    }

    private suspend fun withTempDirectory(block: suspend (Path) -> Unit) {
        val directory = Files.createTempDirectory("actual-page-smoke")
        try {
            block(directory)
        } finally {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    private fun directValue(value: String) = DirectValueEvidence(
        rawValue = value,
        value = BigDecimal(value),
        status = DirectEvidenceStatus.VALID,
    )

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun observation(id: String, observedAt: String, status: ActualObservationStatus, totalUsd: String?) =
        ActualObservation(
            observationId = id,
            accountIdentityDigest = "a".repeat(64),
            walletScope = "KRAKEN_DEFAULT_WALLET_CONFIGURED_ALLOCATIONS",
            scopeFingerprint = "f".repeat(64),
            scopeSymbols = listOf("BTC", "ETH", "USD"),
            observedAt = Instant.parse(observedAt),
            balanceRequestStartedAt = Instant.parse(observedAt).minusSeconds(2),
            balanceResponseEndedAt = Instant.parse(observedAt),
            priceRequestStartedAt = Instant.parse(observedAt).minusSeconds(1),
            priceResponseEndedAt = Instant.parse(observedAt),
            status = status,
            totalUsd = totalUsd?.let(::BigDecimal),
            incompleteReasons = if (status ==
                ActualObservationStatus.INCOMPLETE
            ) {
                listOf("ETH: no direct USD mark")
            } else {
                emptyList()
            },
            assets = listOf(
                ActualAssetObservation(
                    symbol = "BTC",
                    status = if (status ==
                        ActualObservationStatus.COMPLETE
                    ) {
                        ActualAssetStatus.COMPLETE
                    } else {
                        ActualAssetStatus.MISSING_PRICE
                    },
                    rawBalanceKeys = listOf("XXBT"),
                    rawBalanceValues = mapOf("XXBT" to "0.5"),
                    quantity = BigDecimal("0.5"),
                    requestedPair = "XBTUSD",
                    responsePair = "XXBTZUSD",
                    candidateResponsePairs = emptyList(),
                    candidateRawPrices = emptyMap(),
                    rawPrice = "50000.00",
                    priceUsd = BigDecimal("50000.00"),
                    valueUsd = BigDecimal("25000.000"),
                    reason = null,
                ),
                ActualAssetObservation(
                    symbol = "ETH",
                    status = if (status ==
                        ActualObservationStatus.COMPLETE
                    ) {
                        ActualAssetStatus.COMPLETE
                    } else {
                        ActualAssetStatus.MISSING_PRICE
                    },
                    rawBalanceKeys = listOf("XETH"),
                    rawBalanceValues = mapOf("XETH" to "1.25"),
                    quantity = BigDecimal("1.25"),
                    requestedPair = "ETHUSD",
                    responsePair = "XETHZUSD",
                    candidateResponsePairs = emptyList(),
                    candidateRawPrices = emptyMap(),
                    rawPrice = if (status == ActualObservationStatus.COMPLETE) "2000.00" else null,
                    priceUsd = if (status == ActualObservationStatus.COMPLETE) BigDecimal("2000.00") else null,
                    valueUsd = if (status == ActualObservationStatus.COMPLETE) BigDecimal("2500.000") else null,
                    reason = if (status == ActualObservationStatus.COMPLETE) null else "No USD ticker mark.",
                ),
                ActualAssetObservation(
                    symbol = "USD",
                    status = ActualAssetStatus.COMPLETE,
                    rawBalanceKeys = listOf("ZUSD"),
                    rawBalanceValues = mapOf("ZUSD" to "1000.00"),
                    quantity = BigDecimal("1000.00"),
                    requestedPair = "USD",
                    responsePair = "USD",
                    candidateResponsePairs = emptyList(),
                    candidateRawPrices = emptyMap(),
                    rawPrice = "1",
                    priceUsd = BigDecimal.ONE,
                    valueUsd = BigDecimal("1000.00"),
                    reason = null,
                ),
            ),
        )

    private fun String.countOccurrences(value: String): Int = windowed(value.length).count { it == value }
}
