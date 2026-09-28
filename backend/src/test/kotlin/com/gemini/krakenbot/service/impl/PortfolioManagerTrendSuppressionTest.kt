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
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
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
        tradeHistoryService = mockk<TradeHistoryService>(),
        portfolioAnalyzer = mockk<PortfolioAnalyzer>(),
        orderExecutor = mockk<OrderExecutor>(),
        krakenService = krakenService,
    )

    private val allocations = listOf(Allocation(Asset.BTC, 50.0), Allocation(Asset.USD, 50.0))
    private val prices = mapOf(Asset.BTC to BigDecimal("60000"), Asset.USD to BigDecimal.ONE)
    private val now = 1_800_000_000L

    init {
        "resolveAtRecentHigh returns nothing without a Kraken service" {
            manager(krakenService = null)
                .resolveAtRecentHigh(allocations, prices, now)
                .shouldBeEmpty()
        }

        "resolveAtRecentHigh marks an asset trading at its highest completed close" {
            val kraken = mockk<KrakenService>()
            coEvery { kraken.getOHLC(any(), any(), any()) } returns listOf(
                1L to BigDecimal("50000"),
                2L to BigDecimal("59000"),
                3L to BigDecimal("60000"),
            )

            manager(kraken)
                .resolveAtRecentHigh(allocations, prices, now)
                .shouldContainExactly(Asset.BTC)
        }

        "resolveAtRecentHigh omits an asset trading below its recent high" {
            val kraken = mockk<KrakenService>()
            coEvery { kraken.getOHLC(any(), any(), any()) } returns listOf(
                1L to BigDecimal("50000"),
                2L to BigDecimal("70000"),
            )

            manager(kraken)
                .resolveAtRecentHigh(allocations, prices, now)
                .shouldBeEmpty()
        }

        "resolveAtRecentHigh skips a symbol with no current price" {
            val kraken = mockk<KrakenService>()
            coEvery { kraken.getOHLC(any(), any(), any()) } returns listOf(1L to BigDecimal("1"))

            manager(kraken)
                .resolveAtRecentHigh(allocations, mapOf(Asset.USD to BigDecimal.ONE), now)
                .shouldBeEmpty()
        }

        "resolveAtRecentHigh skips a symbol priced at zero" {
            val kraken = mockk<KrakenService>()
            coEvery { kraken.getOHLC(any(), any(), any()) } returns listOf(1L to BigDecimal("1"))

            manager(kraken)
                .resolveAtRecentHigh(allocations, mapOf(Asset.BTC to BigDecimal.ZERO), now)
                .shouldBeEmpty()
        }

        "resolveAtRecentHigh fails open when the lookback cannot be fetched" {
            val kraken = mockk<KrakenService>()
            coEvery { kraken.getOHLC(any(), any(), any()) } throws RuntimeException("ohlc outage")

            manager(kraken)
                .resolveAtRecentHigh(allocations, prices, now)
                .shouldBeEmpty()
        }

        "resolveAtRecentHigh fails open when no candles are returned" {
            val kraken = mockk<KrakenService>()
            coEvery { kraken.getOHLC(any(), any(), any()) } returns emptyList()

            manager(kraken)
                .resolveAtRecentHigh(allocations, prices, now)
                .shouldBeEmpty()
        }

        "resolveAtRecentHigh fails open when a close is not positive" {
            val kraken = mockk<KrakenService>()
            coEvery { kraken.getOHLC(any(), any(), any()) } returns listOf(1L to BigDecimal.ZERO)

            manager(kraken)
                .resolveAtRecentHigh(allocations, prices, now)
                .shouldBeEmpty()
        }

        "resolveAtRecentHigh never swallows cancellation" {
            val kraken = mockk<KrakenService>()
            coEvery { kraken.getOHLC(any(), any(), any()) } throws CancellationException("stop")

            runCatching {
                manager(kraken).resolveAtRecentHigh(allocations, prices, now)
            }.exceptionOrNull().shouldBeInstanceOf<CancellationException>()
        }

        "resolveAtRecentHigh ignores the USD allocation leg" {
            val kraken = mockk<KrakenService>()
            coEvery { kraken.getOHLC(any(), any(), any()) } returns listOf(1L to BigDecimal("1"))

            // USD's price equals its only close, so it would qualify were it not excluded.
            manager(kraken).resolveAtRecentHigh(allocations, prices, now)
                .shouldContainExactly(Asset.BTC)
        }
    }
}
