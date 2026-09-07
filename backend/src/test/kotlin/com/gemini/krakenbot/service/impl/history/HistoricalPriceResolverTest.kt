package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.model.TradeSource
import com.gemini.krakenbot.repository.TradeRepository
import com.gemini.krakenbot.service.KrakenService
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import java.time.Instant

class HistoricalPriceResolverTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    private val repository = mockk<TradeRepository>(relaxed = true)
    private val krakenService = mockk<KrakenService>(relaxed = true)
    private val eventTime = Instant.parse("2026-08-01T12:00:00Z")

    init {
        "USD and the narrow candidate exception resolve without market history" {
            runTest {
                HistoricalPriceResolver.resolveHistoricalPrice(
                    Asset.USD,
                    eventTime,
                    repository,
                    krakenService,
                )!! shouldBeEqualComparingTo BigDecimal.ONE
                HistoricalPriceResolver.resolveHistoricalPrice(
                    Asset.BTC,
                    eventTime,
                    repository,
                    krakenService,
                    candidatePriceException = BigDecimal("100.00"),
                )!! shouldBeEqualComparingTo BigDecimal("100.00")
                HistoricalPriceResolver.resolveHistoricalPrice(
                    Asset.BTC,
                    eventTime,
                    repository,
                    krakenService,
                    candidatePriceException = BigDecimal.ZERO,
                ) shouldBe null
            }
        }

        "a recent authoritative trade price wins and malformed economics fall back to its price" {
            runTest {
                val trade = trade(price = BigDecimal("101.00"), volume = BigDecimal("0.01"), usd = BigDecimal("1.01"))
                coEvery { repository.getTradesInRange(any(), any()) } returns listOf(trade)

                HistoricalPriceResolver.resolveHistoricalPrice(
                    Asset.BTC,
                    eventTime,
                    repository,
                    krakenService,
                )!! shouldBeEqualComparingTo BigDecimal("101.00")

                val volumeMissing = trade.copy(volume = BigDecimal.ZERO)
                coEvery { repository.getTradesInRange(any(), any()) } returns listOf(volumeMissing)
                HistoricalPriceResolver.resolveHistoricalPrice(
                    Asset.BTC,
                    eventTime,
                    repository,
                    krakenService,
                )!! shouldBeEqualComparingTo BigDecimal("101.00")

                val unusable = volumeMissing.copy(price = BigDecimal.ZERO)
                coEvery { repository.getTradesInRange(any(), any()) } returns listOf(unusable)
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns emptyList()
                coEvery { krakenService.getOHLC(any(), any(), any()) } returns emptyList()
                HistoricalPriceResolver.resolveHistoricalPrice(
                    Asset.BTC,
                    eventTime,
                    repository,
                    krakenService,
                ) shouldBe null

                val priceOnly = trade.copy(usdAmount = BigDecimal.ZERO)
                coEvery { repository.getTradesInRange(any(), any()) } returns listOf(priceOnly)
                HistoricalPriceResolver.resolveHistoricalPrice(
                    Asset.BTC,
                    eventTime,
                    repository,
                    krakenService,
                )!! shouldBeEqualComparingTo BigDecimal("101.00")
            }
        }

        "a recent snapshot or a completed fresh OHLC candle supplies the fallback price" {
            runTest {
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns
                    listOf(snapshot(eventTime.minusSeconds(60)))

                HistoricalPriceResolver.resolveHistoricalPrice(
                    Asset.BTC,
                    eventTime,
                    repository,
                    krakenService,
                )!! shouldBeEqualComparingTo BigDecimal("99.00")

                val zeroSnapshot = snapshot(eventTime.minusSeconds(60)).copy(
                    assets = mapOf(
                        Asset.BTC to TestFixtures.assetSnapshot(
                            symbol = Asset.BTC,
                            balance = BigDecimal("0.01"),
                            price = BigDecimal.ZERO,
                            valueUSD = BigDecimal.ZERO,
                            targetPercent = BigDecimal("100.00"),
                        ),
                    ),
                )
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(zeroSnapshot)
                coEvery { krakenService.getOHLC(any(), any(), any()) } returns emptyList()
                HistoricalPriceResolver.resolveHistoricalPrice(
                    Asset.BTC,
                    eventTime,
                    repository,
                    krakenService,
                ) shouldBe null

                coEvery { repository.getSnapshotsInRange(any(), any()) } returns emptyList()
                coEvery { krakenService.getOHLC(any(), any(), any()) } returns listOf(
                    eventTime.minusSeconds(901).epochSecond to BigDecimal("98.00"),
                )
                HistoricalPriceResolver.resolveHistoricalPrice(
                    Asset.BTC,
                    eventTime,
                    repository,
                    krakenService,
                )!! shouldBeEqualComparingTo BigDecimal("98.00")

                coEvery { krakenService.getOHLC(any(), any(), any()) } returns listOf(
                    eventTime.minusSeconds(901).epochSecond to BigDecimal.ZERO,
                )
                HistoricalPriceResolver.resolveHistoricalPrice(
                    Asset.BTC,
                    eventTime,
                    repository,
                    krakenService,
                ) shouldBe null
            }
        }

        "future, incomplete, stale, and failed OHLC evidence remains unavailable" {
            runTest {
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns emptyList()
                coEvery { krakenService.getOHLC(any(), any(), any()) } returns listOf(
                    eventTime.epochSecond to BigDecimal("98.00"),
                    eventTime.minusSeconds(1801).epochSecond to BigDecimal("97.00"),
                )
                HistoricalPriceResolver.resolveHistoricalPrice(
                    Asset.BTC,
                    eventTime,
                    repository,
                    krakenService,
                ) shouldBe null

                coEvery { krakenService.getOHLC(any(), any(), any()) } throws IllegalStateException("unavailable")
                HistoricalPriceResolver.resolveHistoricalPrice(
                    Asset.BTC,
                    eventTime,
                    repository,
                    krakenService,
                ) shouldBe null
            }
        }

        "future and unusable trade or snapshot observations are ignored" {
            runTest {
                val unusable = trade(price = BigDecimal.ZERO, volume = BigDecimal.ZERO, usd = BigDecimal.ZERO)
                    .copy(timestamp = eventTime.plusSeconds(1), success = false)
                val futureSnapshot = snapshot(eventTime.plusSeconds(1))
                coEvery { repository.getTradesInRange(any(), any()) } returns listOf(unusable)
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(futureSnapshot)
                coEvery { krakenService.getOHLC(any(), any(), any()) } returns emptyList()

                HistoricalPriceResolver.resolveHistoricalPrice(
                    Asset.BTC,
                    eventTime,
                    repository,
                    krakenService,
                ) shouldBe null

                val observationFuture = snapshot(eventTime.minusSeconds(60)).copy(
                    balancesObservedAt = eventTime.plusSeconds(1),
                )
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(observationFuture)
                coEvery { krakenService.getOHLC(any(), any(), any()) } returns emptyList()
                HistoricalPriceResolver.resolveHistoricalPrice(
                    Asset.BTC,
                    eventTime,
                    repository,
                    krakenService,
                ) shouldBe null
            }
        }
    }

    private fun trade(price: BigDecimal, volume: BigDecimal, usd: BigDecimal): TradeRecord = TestFixtures.tradeRecord(
        timestamp = eventTime.minusSeconds(30),
        pair = Asset.BTC_USD_PAIR,
        side = "buy",
        symbol = Asset.BTC,
        volume = volume,
        usdAmount = usd,
        price = price,
        source = TradeSource.API_FILL,
    )

    private fun snapshot(timestamp: Instant): PortfolioSnapshot = PortfolioSnapshot(
        timestamp = timestamp,
        totalValueUSD = BigDecimal("99.00"),
        assets = mapOf(
            Asset.BTC to TestFixtures.assetSnapshot(
                symbol = Asset.BTC,
                balance = BigDecimal("0.01"),
                price = BigDecimal("99.00"),
                valueUSD = BigDecimal("0.99"),
                targetPercent = BigDecimal("100.00"),
            ),
        ),
        actions = emptyList(),
        drawdownPercent = BigDecimal.ZERO,
        fiatDeploymentPercent = BigDecimal.ZERO,
        effectiveUsdTargetPercent = BigDecimal.ZERO,
    )
}
