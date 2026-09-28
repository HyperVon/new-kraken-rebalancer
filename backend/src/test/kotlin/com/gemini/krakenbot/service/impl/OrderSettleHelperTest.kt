package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.KrakenApiConstants
import com.gemini.krakenbot.model.OrderSide
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.service.SpendableBalanceService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
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

        "settleUsdAfterSells propagates cancellation from the balance peek" {
            runTest {
                val txid = "tx-cancelled-peek"
                val trade = TradeRecord(
                    timestamp = Instant.now(),
                    pair = "XXBTZUSD",
                    side = OrderSide.SELL.name,
                    symbol = Asset.BTC,
                    volume = BigDecimal("0.1"),
                    usdAmount = BigDecimal("505.00"),
                    success = true,
                    dryRun = false,
                    orderTxid = txid,
                    fee = BigDecimal("5.00"),
                    tradeId = "t-cancelled-peek",
                )
                coEvery { backend.getTradeHistory(any(), any()) } returns listOf(trade)
                coEvery { backend.getLastTradeHistoryTotalCount() } returns 1
                coEvery { backend.getBalances() } throws CancellationException("settlement cancelled")

                val thrown = shouldThrow<CancellationException> {
                    OrderSettleHelper.settleUsdAfterSells(
                        backend = backend,
                        openingUsd = BigDecimal.ZERO,
                        projectedCash = BigDecimal("520.00"),
                        sellOrderTxids = listOf(txid),
                    )
                }

                thrown.message shouldBe "settlement cancelled"
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

        "settleUsdAfterSells falls through to balance poll when fills are below the 95% threshold" {
            runTest {
                // Truncated trade-history view: only $3,000 of a $5,000 projected sell is visible.
                val txid = "tx-truncated"
                val partialTrade = TradeRecord(
                    timestamp = Instant.now(),
                    pair = "XXBTZUSD",
                    side = OrderSide.SELL.name,
                    symbol = Asset.BTC,
                    volume = BigDecimal("0.06"),
                    usdAmount = BigDecimal("3000.00"),
                    success = true,
                    dryRun = false,
                    orderTxid = txid,
                    fee = BigDecimal("6.00"),
                    tradeId = "t-3",
                )
                coEvery { backend.getTradeHistory(any(), any()) } returns listOf(partialTrade)
                coEvery { backend.getLastTradeHistoryTotalCount() } returns 1
                coEvery { backend.getBalances() } returns mapOf(TestFixtures.USD to BigDecimal("4990.00"))

                val settled = OrderSettleHelper.settleUsdAfterSells(
                    backend = backend,
                    openingUsd = BigDecimal("0.00"),
                    projectedCash = BigDecimal("5000.00"),
                    sellOrderTxids = listOf(txid),
                )

                // The balance poll sees the full ledger effect and outbids the truncated fill total.
                settled.shouldBeEqualComparingTo(BigDecimal("4990.00"))
            }
        }

        "settleUsdAfterSells caps fill proceeds to spendable USD when ordinary USD is higher" {
            runTest {
                val txid = "tx-spendable"
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
                    tradeId = "t-spendable",
                )
                val settlementBackend = object : KrakenService by backend, SpendableBalanceService {
                    override suspend fun getSpendableBalances() = mapOf(TestFixtures.USD to BigDecimal("4800.00"))
                }
                coEvery { backend.getTradeHistory(any(), any()) } returns listOf(trade)
                coEvery { backend.getLastTradeHistoryTotalCount() } returns 1
                coEvery { backend.getBalances() } returns mapOf(TestFixtures.USD to BigDecimal("9000.00"))

                val settled = OrderSettleHelper.settleUsdAfterSells(
                    backend = settlementBackend,
                    openingUsd = BigDecimal("0.00"),
                    projectedCash = BigDecimal("4990.00"),
                    sellOrderTxids = listOf(txid),
                )

                settled.shouldBeEqualComparingTo(BigDecimal("4800.00"))
            }
        }

        "settleUsdAfterSells continues a full unknown-total page at the next offset" {
            runTest {
                val pageSize = KrakenApiConstants.TRADE_HISTORY_PAGE_SIZE
                val txid = "tx-second-page"
                val unrelatedTrade = TradeRecord(
                    timestamp = Instant.now(),
                    pair = "XXBTZUSD",
                    side = OrderSide.BUY.name,
                    symbol = Asset.BTC,
                    volume = BigDecimal("0.1"),
                    usdAmount = BigDecimal("100.00"),
                    success = true,
                    dryRun = false,
                    orderTxid = "tx-unrelated",
                    fee = BigDecimal("0.00"),
                    tradeId = "t-unrelated",
                )
                val matchingTrade = TradeRecord(
                    timestamp = Instant.now(),
                    pair = "XXBTZUSD",
                    side = OrderSide.SELL.name,
                    symbol = Asset.BTC,
                    volume = BigDecimal("0.1"),
                    usdAmount = BigDecimal("505.00"),
                    success = true,
                    dryRun = false,
                    orderTxid = txid,
                    fee = BigDecimal("5.00"),
                    tradeId = "t-second-page",
                )
                val requestedOffsets = mutableListOf<Int?>()
                coEvery { backend.getTradeHistory(any(), any()) } coAnswers {
                    val offset = secondArg<Int?>()
                    requestedOffsets += offset
                    when (offset) {
                        0 -> List(pageSize) { unrelatedTrade }
                        pageSize -> listOf(matchingTrade)
                        else -> emptyList()
                    }
                }
                coEvery { backend.getLastTradeHistoryTotalCount() } returns 0
                coEvery { backend.getBalances() } throws IOException("Balance peek unavailable")

                val settled = OrderSettleHelper.settleUsdAfterSells(
                    backend = backend,
                    openingUsd = BigDecimal("0.00"),
                    projectedCash = BigDecimal("520.00"),
                    sellOrderTxids = listOf(txid),
                )

                requestedOffsets shouldBe listOf(0, pageSize)
                settled.shouldBeEqualComparingTo(BigDecimal("500.00"))
            }
        }
    }
}
