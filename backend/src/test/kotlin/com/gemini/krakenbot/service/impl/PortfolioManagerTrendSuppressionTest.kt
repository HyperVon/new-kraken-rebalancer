package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.service.OrderExecutor
import com.gemini.krakenbot.service.PortfolioAnalyzer
import com.gemini.krakenbot.service.TradeHistoryService
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import java.math.BigDecimal

/**
 * The trend-window rule suppresses sells at a recent high and must never change behaviour when
 * the lookback cannot be resolved — so every fail-open path is exercised here explicitly.
 */
class PortfolioManagerTrendSuppressionTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    private fun manager(krakenService: KrakenService?): PortfolioManagerImpl = PortfolioManagerImpl(
        configService = mockk<ConfigService>(),
        portfolioAnalyzer = mockk<PortfolioAnalyzer>(),
        orderExecutor = mockk<OrderExecutor>(),
        krakenService = krakenService,
    )

    private val allocations = listOf(Allocation(Asset.BTC, 50.0), Allocation(Asset.USD, 50.0))
    private val prices = mapOf(Asset.BTC to BigDecimal("60000"), Asset.USD to BigDecimal.ONE)
    private val now = 1_800_000_000L
    private val secondsPerDay = 86_400L

    private fun completedCandle(daysAgo: Long, close: String) =
        (Math.floorDiv(now, secondsPerDay) * secondsPerDay - daysAgo * secondsPerDay) to BigDecimal(close)

    init {
        "resolveAtRecentHigh returns nothing without a Kraken service" {
            manager(krakenService = null)
                .resolveAtRecentHigh(allocations, prices, now, simulation = false)
                .shouldBeEmpty()
        }

        "resolveAtRecentHigh marks an asset trading at its highest completed close" {
            val kraken = mockk<KrakenService>()
            coEvery { kraken.getOHLC(any(), any(), any()) } returns listOf(
                completedCandle(3, "50000"),
                completedCandle(2, "59000"),
                completedCandle(1, "60000"),
            )

            manager(kraken)
                .resolveAtRecentHigh(allocations, prices, now, simulation = false)
                .shouldContainExactly(Asset.BTC)
        }

        "resolveAtRecentHigh omits an asset trading below its recent high" {
            val kraken = mockk<KrakenService>()
            coEvery { kraken.getOHLC(any(), any(), any()) } returns listOf(
                completedCandle(2, "50000"),
                completedCandle(1, "70000"),
            )

            manager(kraken)
                .resolveAtRecentHigh(allocations, prices, now, simulation = false)
                .shouldBeEmpty()
        }

        "resolveAtRecentHigh skips a symbol with no current price" {
            val kraken = mockk<KrakenService>()
            coEvery { kraken.getOHLC(any(), any(), any()) } returns listOf(1L to BigDecimal("1"))

            manager(kraken)
                .resolveAtRecentHigh(allocations, mapOf(Asset.USD to BigDecimal.ONE), now, simulation = false)
                .shouldBeEmpty()
        }

        "resolveAtRecentHigh skips a symbol priced at zero" {
            val kraken = mockk<KrakenService>()
            coEvery { kraken.getOHLC(any(), any(), any()) } returns listOf(1L to BigDecimal("1"))

            manager(kraken)
                .resolveAtRecentHigh(allocations, mapOf(Asset.BTC to BigDecimal.ZERO), now, simulation = false)
                .shouldBeEmpty()
        }

        "resolveAtRecentHigh fails open when the lookback cannot be fetched" {
            val kraken = mockk<KrakenService>()
            coEvery { kraken.getOHLC(any(), any(), any()) } throws RuntimeException("ohlc outage")

            manager(kraken)
                .resolveAtRecentHigh(allocations, prices, now, simulation = false)
                .shouldBeEmpty()
        }

        "resolveAtRecentHigh fails open when no candles are returned" {
            val kraken = mockk<KrakenService>()
            coEvery { kraken.getOHLC(any(), any(), any()) } returns emptyList()

            manager(kraken)
                .resolveAtRecentHigh(allocations, prices, now, simulation = false)
                .shouldBeEmpty()
        }

        "resolveAtRecentHigh fails open when a close is not positive" {
            val kraken = mockk<KrakenService>()
            coEvery { kraken.getOHLC(any(), any(), any()) } returns listOf(completedCandle(1, "0"))

            manager(kraken)
                .resolveAtRecentHigh(allocations, prices, now, simulation = false)
                .shouldBeEmpty()
        }

        "resolveAtRecentHigh never swallows cancellation" {
            val kraken = mockk<KrakenService>()
            coEvery { kraken.getOHLC(any(), any(), any()) } throws CancellationException("stop")

            runCatching {
                manager(kraken).resolveAtRecentHigh(allocations, prices, now, simulation = false)
            }.exceptionOrNull().shouldBeInstanceOf<CancellationException>()
        }

        "resolveAtRecentHigh ignores the USD allocation leg" {
            val kraken = mockk<KrakenService>()
            coEvery { kraken.getOHLC(any(), any(), any()) } returns listOf(completedCandle(1, "1"))

            // USD's price equals its only close, so it would qualify were it not excluded.
            manager(kraken).resolveAtRecentHigh(allocations, prices, now, simulation = false)
                .shouldContainExactly(Asset.BTC)
        }

        "resolveAtRecentHigh only queries candidate symbols when specified" {
            val kraken = mockk<KrakenService>()
            coEvery { kraken.getOHLC(any(), any(), any()) } returns listOf(1L to BigDecimal("50000"))

            val instance = manager(kraken)
            instance.resolveAtRecentHigh(allocations, prices, now, candidateSymbols = emptySet(), simulation = false)
                .shouldBeEmpty()
            coVerify(exactly = 0) { kraken.getOHLC(any(), any(), any()) }

            instance.resolveAtRecentHigh(allocations, prices, now, candidateSymbols = setOf("ETH"), simulation = false)
                .shouldBeEmpty()
            coVerify(exactly = 0) { kraken.getOHLC(any(), any(), any()) }
        }

        "resolveAtRecentHigh caches completed daily recent high within TTL" {
            val kraken = mockk<KrakenService>()
            coEvery { kraken.getOHLC(any(), any(), any()) } returns listOf(
                completedCandle(2, "50000"),
                completedCandle(1, "60000"),
            )

            val instance = manager(kraken)
            instance.resolveAtRecentHigh(allocations, prices, now, simulation = false)
                .shouldContainExactly(Asset.BTC)

            instance.resolveAtRecentHigh(allocations, prices, now + 60, simulation = false)
                .shouldContainExactly(Asset.BTC)

            coVerify(exactly = 1) { kraken.getOHLC(any(), any(), any()) }

            // Beyond TTL, cache expires and queries backend again
            instance.resolveAtRecentHigh(
                allocations,
                prices,
                now + PortfolioManagerImpl.RECENT_HIGH_CACHE_TTL_SECONDS + 10,
                simulation = false,
            ).shouldContainExactly(Asset.BTC)

            coVerify(exactly = 2) { kraken.getOHLC(any(), any(), any()) }
        }

        "resolveAtRecentHigh requests the full trailing window of completed daily candles" {
            val currentDayStart = Math.floorDiv(now, secondsPerDay) * secondsPerDay
            val since = slot<Long>()
            val kraken = mockk<KrakenService>()
            coEvery { kraken.getOHLC(any(), any(), capture(since)) } returns
                listOf((currentDayStart - secondsPerDay) to BigDecimal("60000"))

            manager(kraken).resolveAtRecentHigh(allocations, prices, now, simulation = false)

            since.captured shouldBe currentDayStart -
                PortfolioManagerImpl.RECENT_HIGH_LOOKBACK_DAYS * secondsPerDay
        }

        "resolveAtRecentHigh refreshes the completed-candle set at the UTC day boundary" {
            val currentDayStart = Math.floorDiv(now, secondsPerDay) * secondsPerDay
            val justBeforeMidnight = currentDayStart + secondsPerDay - 600
            val nextDayStart = currentDayStart + secondsPerDay
            val kraken = mockk<KrakenService>()
            coEvery { kraken.getOHLC(any(), any(), any()) } returnsMany listOf(
                listOf((currentDayStart - secondsPerDay) to BigDecimal("60000")),
                listOf(currentDayStart to BigDecimal("65000")),
            )
            val instance = manager(kraken)

            instance.resolveAtRecentHigh(allocations, prices, justBeforeMidnight, simulation = false)
                .shouldContainExactly(Asset.BTC)
            instance.resolveAtRecentHigh(allocations, prices, nextDayStart, simulation = false)
                .shouldBeEmpty()

            coVerify(exactly = 2) { kraken.getOHLC(any(), any(), any()) }
        }

        "resolveAtRecentHigh does not share a cached high across trading modes" {
            val kraken = mockk<KrakenService>()
            coEvery { kraken.getOHLC(any(), any(), any()) } returns listOf(
                completedCandle(2, "50000"),
                completedCandle(1, "60000"),
            )

            val instance = manager(kraken)
            instance.resolveAtRecentHigh(allocations, prices, now, simulation = false)
                .shouldContainExactly(Asset.BTC)

            // Simulation serves its own prices, so a high resolved against live candles must not
            // decide whether a simulated sell is suppressed.
            instance.resolveAtRecentHigh(allocations, prices, now, simulation = true)
                .shouldContainExactly(Asset.BTC)

            coVerify(exactly = 2) { kraken.getOHLC(any(), any(), any()) }
        }

        "resolveAtRecentHigh ignores incomplete current-day candles when completed candles exist" {
            val currentDayStart = Math.floorDiv(now, secondsPerDay) * secondsPerDay
            val kraken = mockk<KrakenService>()
            coEvery { kraken.getOHLC(any(), any(), any()) } returns listOf(
                currentDayStart - secondsPerDay to BigDecimal("55000"),
                currentDayStart + 10 to BigDecimal("70000"),
            )

            manager(kraken).resolveAtRecentHigh(allocations, prices, now, simulation = false)
                .shouldContainExactly(Asset.BTC)
        }

        "resolveAtRecentHigh fails open when only one incomplete candle is returned" {
            val currentDayStart = Math.floorDiv(now, secondsPerDay) * secondsPerDay
            val kraken = mockk<KrakenService>()
            coEvery { kraken.getOHLC(any(), any(), any()) } returns listOf(
                currentDayStart + 10 to BigDecimal("60000"),
            )

            manager(kraken).resolveAtRecentHigh(allocations, prices, now, simulation = false)
                .shouldBeEmpty()
        }

        "resolveAtRecentHigh fails open when multiple returned candles are all incomplete" {
            val currentDayStart = Math.floorDiv(now, secondsPerDay) * secondsPerDay
            val kraken = mockk<KrakenService>()
            coEvery { kraken.getOHLC(any(), any(), any()) } returns listOf(
                currentDayStart + 10 to BigDecimal("55000"),
                currentDayStart + 20 to BigDecimal("70000"),
            )

            manager(kraken).resolveAtRecentHigh(allocations, prices, now, simulation = false)
                .shouldBeEmpty()
        }

        "resolveAtRecentHigh handles empty closes from backend cleanly" {
            val kraken = mockk<KrakenService>()
            coEvery { kraken.getOHLC(any(), any(), any()) } returns emptyList()

            manager(kraken).resolveAtRecentHigh(allocations, prices, now, simulation = false)
                .shouldBeEmpty()
        }

        "resolveAtRecentHigh skips symbol when price is zero, negative, or missing" {
            val kraken = mockk<KrakenService>()
            coEvery { kraken.getOHLC(any(), any(), any()) } returns listOf(completedCandle(1, "50000"))

            val instance = manager(kraken)
            instance.resolveAtRecentHigh(allocations, emptyMap(), now, simulation = false).shouldBeEmpty()
            instance.resolveAtRecentHigh(
                allocations,
                mapOf(Asset.BTC to BigDecimal.ZERO, Asset.USD to BigDecimal.ONE),
                now,
                simulation = false,
            ).shouldBeEmpty()
            instance.resolveAtRecentHigh(
                allocations,
                mapOf(Asset.BTC to BigDecimal("-100"), Asset.USD to BigDecimal.ONE),
                now,
                simulation = false,
            ).shouldBeEmpty()

            coVerify(exactly = 0) { kraken.getOHLC(any(), any(), any()) }
        }

        "resolveAtRecentHigh queries backend when candidateSymbols contains the symbol" {
            val kraken = mockk<KrakenService>()
            coEvery { kraken.getOHLC(any(), any(), any()) } returns listOf(completedCandle(1, "50000"))

            manager(kraken).resolveAtRecentHigh(
                allocations,
                prices,
                now,
                candidateSymbols = setOf(Asset.BTC),
                simulation = false,
            ).shouldContainExactly(Asset.BTC)

            coVerify(exactly = 1) { kraken.getOHLC(any(), any(), any()) }
        }
    }
}
