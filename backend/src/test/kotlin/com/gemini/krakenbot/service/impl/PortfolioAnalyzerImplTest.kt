package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.service.BalanceRead
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.DirectBalanceCapture
import com.gemini.krakenbot.service.DirectEvidenceStatus
import com.gemini.krakenbot.service.DirectKrakenEvidenceService
import com.gemini.krakenbot.service.DirectTickerCapture
import com.gemini.krakenbot.service.DirectTickerMark
import com.gemini.krakenbot.service.DirectTickerRequest
import com.gemini.krakenbot.service.DirectValueEvidence
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.service.TickerRead
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import java.time.Instant

class PortfolioAnalyzerImplTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    private val configService = mockk<ConfigService>()
    private val krakenService = mockk<KrakenService>(relaxed = true)

    init {
        "snapshot uses a unit price for USD and the direct ticker mark for crypto" {
            every { configService.getConfig() } returns TestFixtures.config(
                settings = TestFixtures.settings(),
                allocations = listOf(Allocation(Asset.BTC, 50.0), Allocation(Asset.USD, 50.0)),
            )
            val analyzer = PortfolioAnalyzerImpl(krakenService, configService)

            val snapshot = analyzer.buildSnapshot(
                balances = mapOf("XXBT" to BigDecimal("1"), "ZUSD" to BigDecimal("100")),
                prices = mapOf("BTC" to BigDecimal("20000")),
                currentValuesUSD = mapOf("BTC" to BigDecimal("20000"), "USD" to BigDecimal("100")),
                totalPortfolioValueUSD = BigDecimal("20100"),
                effectiveUsdTarget = BigDecimal("50"),
                cryptoScaleFactor = BigDecimal.ONE,
                actionLog = listOf("current cycle"),
                balancesObservedAt = Instant.parse("2026-10-10T12:00:00Z"),
            )

            snapshot.assets.getValue("USD").price.shouldBeEqualComparingTo(BigDecimal.ONE)
            snapshot.assets.getValue("BTC").price.shouldBeEqualComparingTo(BigDecimal("20000"))
            snapshot.actions shouldBe listOf("current cycle")
            snapshot.balancesObservedAt shouldBe Instant.parse("2026-10-10T12:00:00Z")
        }

        "snapshot fails when a configured crypto asset has no resolved current price" {
            every { configService.getConfig() } returns TestFixtures.config(
                settings = TestFixtures.settings(),
                allocations = listOf(Allocation(Asset.ETH, 100.0)),
            )
            val analyzer = PortfolioAnalyzerImpl(krakenService, configService)

            shouldThrow<IllegalStateException> {
                analyzer.buildSnapshot(
                    balances = mapOf("XETH" to BigDecimal.ONE),
                    prices = emptyMap(),
                    currentValuesUSD = mapOf("ETH" to BigDecimal("1000")),
                    totalPortfolioValueUSD = BigDecimal("1000"),
                    effectiveUsdTarget = BigDecimal.ZERO,
                    cryptoScaleFactor = BigDecimal.ONE,
                    actionLog = emptyList(),
                )
            }
        }

        "balance observation timestamp is captured before the exchange read completes" {
            val before = Instant.parse("2026-10-10T12:00:00Z")
            var now = before
            coEvery { krakenService.getBalances() } coAnswers {
                now = before.plusSeconds(1)
                mapOf("ZUSD" to BigDecimal("125"))
            }
            val analyzer = PortfolioAnalyzerImpl(krakenService, configService, nowProvider = { now })

            val observed = analyzer.fetchObservedBalances()

            observed.observedAt shouldBe before
            observed.balances.getValue("ZUSD").shouldBeEqualComparingTo(BigDecimal("125"))
        }

        "direct balance observations retain raw evidence and perform one authenticated read" {
            val observedAt = Instant.parse("2026-10-10T12:10:00Z")
            val capture = DirectBalanceCapture(
                requestStartedAt = observedAt,
                responseEndedAt = observedAt.plusMillis(200),
                resultShapeValid = true,
                valuesByAssetId = mapOf(
                    "XXBT" to DirectValueEvidence("0.25", BigDecimal("0.25"), DirectEvidenceStatus.VALID),
                ),
            )
            var readCount = 0
            val directService = object : KrakenService by krakenService, DirectKrakenEvidenceService {
                override suspend fun readBalancesWithCapture(): BalanceRead {
                    readCount++
                    return BalanceRead(mapOf("XXBT" to BigDecimal("0.25")), capture)
                }

                override suspend fun readTickerPricesWithCapture(requests: List<DirectTickerRequest>): TickerRead =
                    error("ticker read not expected")
            }
            val analyzer = PortfolioAnalyzerImpl(directService, configService, nowProvider = { observedAt })

            val observed = analyzer.fetchObservedBalances()

            readCount shouldBe 1
            observed.directCapture shouldBe capture
            observed.balances.getValue("XXBT").shouldBeEqualComparingTo(BigDecimal("0.25"))
            coVerify(exactly = 0) { krakenService.getBalances() }
        }

        "direct ticker observations associate returned marks with requested configured symbols" {
            every { configService.getConfig() } returns TestFixtures.config(
                settings = TestFixtures.settings(),
                allocations = listOf(Allocation(Asset.BTC, 80.0), Allocation(Asset.USD, 20.0)),
            )
            val start = Instant.parse("2026-10-10T12:20:00Z")
            val mark = DirectTickerMark(
                symbol = "BTC",
                requestedPair = "XBTUSD",
                responsePair = "XXBTZUSD",
                candidateResponsePairs = listOf("XXBTZUSD"),
                rawPrice = "50000.25",
                price = BigDecimal("50000.25"),
                status = DirectEvidenceStatus.VALID,
            )
            val capture = DirectTickerCapture(
                requestStartedAt = start,
                responseEndedAt = start.plusMillis(200),
                resultShapeValid = true,
                marksBySymbol = mapOf("BTC" to mark),
            )
            var capturedRequests: List<DirectTickerRequest> = emptyList()
            val directService = object : KrakenService by krakenService, DirectKrakenEvidenceService {
                override suspend fun readBalancesWithCapture(): BalanceRead = error("balance read not expected")

                override suspend fun readTickerPricesWithCapture(requests: List<DirectTickerRequest>): TickerRead {
                    capturedRequests = requests
                    return TickerRead(mapOf("XXBTZUSD" to BigDecimal("50000.25")), capture)
                }
            }
            val analyzer = PortfolioAnalyzerImpl(directService, configService)

            val observed = analyzer.fetchObservedPrices()

            capturedRequests shouldBe listOf(DirectTickerRequest("BTC", "XBTUSD"))
            observed.prices.getValue("BTC").shouldBeEqualComparingTo(BigDecimal("50000.25"))
            observed.directCapture shouldBe capture
            coVerify(exactly = 0) { krakenService.getTickerPrices(any()) }
        }

        "USD-only allocations do not request ticker prices" {
            every { configService.getConfig() } returns TestFixtures.config(
                settings = TestFixtures.settings(),
                allocations = listOf(Allocation(Asset.USD, 100.0)),
            )
            val analyzer = PortfolioAnalyzerImpl(krakenService, configService)

            val observed = analyzer.fetchObservedPrices()

            observed.prices shouldBe emptyMap()
            observed.directCapture?.resultShapeValid shouldBe true
            coVerify(exactly = 0) { krakenService.getTickerPrices(any()) }
        }
    }
}
