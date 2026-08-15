package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.OrderSide
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.service.KrakenService
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.math.BigDecimal
import java.time.Instant

class OrderSettleHelperTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    private val backend = mockk<KrakenService>(relaxed = true)

    init {
        "settleUsdAfterSells caps fill confirmed USD to balance peek when available" {
            runTest {
                val txid = "tx-123"
                val trade = TradeRecord(
                    timestamp = Instant.now(),
                    pair = "XXBTZUSD",
                    side = OrderSide.SELL.name,
                    symbol = Asset.BTC,
                    volume = BigDecimal("0.1"),
                    usdAmount = BigDecimal("5000.00"),
                    success = true,
                    dryRun = false,
                    orderTxid = txid,
                    fee = BigDecimal("10.00"),
                    tradeId = "t-1",
                )
                coEvery { backend.getTradeHistory(any(), any()) } returns listOf(trade)
                coEvery { backend.getLastTradeHistoryTotalCount() } returns 1
                coEvery { backend.getBalances() } returns mapOf(TestFixtures.USD to BigDecimal("4500.00"))

                val settled = OrderSettleHelper.settleUsdAfterSells(
                    backend = backend,
                    openingUsd = BigDecimal("0.00"),
                    projectedCash = BigDecimal("5000.00"),
                    sellOrderTxids = listOf(txid),
                )

                settled.shouldBeEqualComparingTo(BigDecimal("4500.00"))
            }
        }

        "settleUsdAfterSells caps fill confirmed USD to projectedCash when peekUsdBalance throws" {
            runTest {
                val txid = "tx-456"
                val trade = TradeRecord(
                    timestamp = Instant.now(),
                    pair = "XXBTZUSD",
                    side = OrderSide.SELL.name,
                    symbol = Asset.BTC,
                    volume = BigDecimal("0.1"),
                    usdAmount = BigDecimal("6000.00"),
                    success = true,
                    dryRun = false,
                    orderTxid = txid,
                    fee = BigDecimal("10.00"),
                    tradeId = "t-2",
                )
                coEvery { backend.getTradeHistory(any(), any()) } returns listOf(trade)
                coEvery { backend.getLastTradeHistoryTotalCount() } returns 1
                coEvery { backend.getBalances() } throws IOException("Balance peek network timeout")

                val settled = OrderSettleHelper.settleUsdAfterSells(
                    backend = backend,
                    openingUsd = BigDecimal("0.00"),
                    projectedCash = BigDecimal("5500.00"),
                    sellOrderTxids = listOf(txid),
                )

                settled.shouldBeEqualComparingTo(BigDecimal("5500.00"))
            }
        }

        "settleUsdAfterSells falls back to balance poll when sellOrderTxids is empty" {
            runTest {
                coEvery { backend.getBalances() } returns mapOf(TestFixtures.USD to BigDecimal("3000.00"))

                val settled = OrderSettleHelper.settleUsdAfterSells(
                    backend = backend,
                    openingUsd = BigDecimal("0.00"),
                    projectedCash = BigDecimal("3000.00"),
                    sellOrderTxids = emptyList(),
                )

                settled.shouldBeEqualComparingTo(BigDecimal("3000.00"))
            }
        }

        "settleUsdAfterSells falls back to balance poll when fill confirmation finds no matching proceeds" {
            runTest {
                coEvery { backend.getTradeHistory(any(), any()) } returns emptyList()
                coEvery { backend.getLastTradeHistoryTotalCount() } returns 0
                coEvery { backend.getBalances() } returns mapOf(TestFixtures.USD to BigDecimal("2000.00"))

                val settled = OrderSettleHelper.settleUsdAfterSells(
                    backend = backend,
                    openingUsd = BigDecimal("0.00"),
                    projectedCash = BigDecimal("2000.00"),
                    sellOrderTxids = listOf("tx-nomatch"),
                )

                settled.shouldBeEqualComparingTo(BigDecimal("2000.00"))
            }
        }
    }
}
