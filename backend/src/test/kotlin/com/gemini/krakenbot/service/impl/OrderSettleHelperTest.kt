package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.ExchangeTradeFill
import com.gemini.krakenbot.model.ExchangeTradePage
import com.gemini.krakenbot.service.KrakenService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.math.BigDecimal

class OrderSettleHelperTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    private val backend = mockk<KrakenService>(relaxed = true)

    init {
        "settles only USD fee-denominated fills and caps to spendable USD" {
            runTest {
                coEvery { backend.getRecentTradePage(any(), any(), any(), any()) } returns page(
                    trade(id = "fill-1", txid = "sell-1", cost = "5000.00", fee = "10.00", feeCurrency = Asset.USD),
                )
                coEvery { backend.getBalances() } returns mapOf(Asset.USD to BigDecimal("4800.00"))

                val settled = OrderSettleHelper.settleUsdAfterSells(
                    backend = backend,
                    openingUsd = BigDecimal.ZERO,
                    projectedCash = BigDecimal("5000.00"),
                    sellOrderTxids = listOf("sell-1"),
                )

                settled.shouldBeEqualComparingTo(BigDecimal("4800.00"))
            }
        }

        "unknown nonzero fee denomination falls back to the observed USD balance" {
            runTest {
                coEvery { backend.getRecentTradePage(any(), any(), any(), any()) } returns page(
                    trade(id = "fill-unknown-fee", txid = "sell-unknown-fee", cost = "5000.00", fee = "10.00"),
                )
                coEvery { backend.getBalances() } returns mapOf(Asset.USD to BigDecimal("4700.00"))

                val settled = OrderSettleHelper.settleUsdAfterSells(
                    backend = backend,
                    openingUsd = BigDecimal.ZERO,
                    projectedCash = BigDecimal("5000.00"),
                    sellOrderTxids = listOf("sell-unknown-fee"),
                )

                settled.shouldBeEqualComparingTo(BigDecimal("4700.00"))
            }
        }

        "positive non-USD fees cannot confirm USD proceeds" {
            runTest {
                coEvery { backend.getRecentTradePage(any(), any(), any(), any()) } returns page(
                    trade(
                        id = "fill-bitcoin-fee",
                        txid = "sell-bitcoin-fee",
                        cost = "5000.00",
                        fee = "0.0001",
                        feeCurrency = Asset.BTC,
                    ),
                )
                coEvery { backend.getBalances() } returns mapOf(Asset.USD to BigDecimal("4700.00"))

                val settled = OrderSettleHelper.settleUsdAfterSells(
                    backend = backend,
                    openingUsd = BigDecimal.ZERO,
                    projectedCash = BigDecimal("5000.00"),
                    sellOrderTxids = listOf("sell-bitcoin-fee"),
                )

                settled.shouldBeEqualComparingTo(BigDecimal("4700.00"))
            }
        }

        "zero fee without a denomination may confirm proceeds subject to the balance cap" {
            runTest {
                coEvery { backend.getRecentTradePage(any(), any(), any(), any()) } returns page(
                    trade(id = "fill-zero-fee", txid = "sell-zero-fee", cost = "5000.00", fee = "0.00"),
                )
                coEvery { backend.getBalances() } returns mapOf(Asset.USD to BigDecimal("4800.00"))

                val settled = OrderSettleHelper.settleUsdAfterSells(
                    backend = backend,
                    openingUsd = BigDecimal.ZERO,
                    projectedCash = BigDecimal("5000.00"),
                    sellOrderTxids = listOf("sell-zero-fee"),
                )

                settled.shouldBeEqualComparingTo(BigDecimal("4800.00"))
            }
        }

        "one unknown matched fee prevents a mixed page from claiming partial proceeds" {
            runTest {
                coEvery { backend.getRecentTradePage(any(), any(), any(), any()) } returns page(
                    trade(id = "fill-known", txid = "sell-mixed", cost = "2500.00", fee = "0.00"),
                    trade(id = "fill-unknown", txid = "sell-mixed", cost = "2500.00", fee = "5.00"),
                )
                coEvery { backend.getBalances() } returns mapOf(Asset.USD to BigDecimal("4900.00"))

                val settled = OrderSettleHelper.settleUsdAfterSells(
                    backend = backend,
                    openingUsd = BigDecimal.ZERO,
                    projectedCash = BigDecimal("5000.00"),
                    sellOrderTxids = listOf("sell-mixed"),
                )

                settled.shouldBeEqualComparingTo(BigDecimal("4900.00"))
            }
        }

        "continues recent fill pages at the cursor and settles a matching later row" {
            runTest {
                val requestedCursors = mutableListOf<String?>()
                coEvery { backend.getRecentTradePage(any(), any(), any(), any()) } coAnswers {
                    val cursor = thirdArg<String?>()
                    requestedCursors += cursor
                    if (cursor == null) {
                        page(
                            trade(id = "unrelated", txid = "other-order", cost = "500.00", fee = "0.00"),
                            nextCursor = "cursor-2",
                        )
                    } else {
                        page(
                            trade(
                                id = "fill-page-2",
                                txid = "sell-page-2",
                                cost = "505.00",
                                fee = "5.00",
                                feeCurrency = Asset.USD,
                            ),
                        )
                    }
                }
                coEvery { backend.getBalances() } throws IOException("balance peek unavailable")

                val settled = OrderSettleHelper.settleUsdAfterSells(
                    backend = backend,
                    openingUsd = BigDecimal.ZERO,
                    projectedCash = BigDecimal("520.00"),
                    sellOrderTxids = listOf("sell-page-2"),
                )

                requestedCursors shouldBe listOf(null, "cursor-2")
                settled.shouldBeEqualComparingTo(BigDecimal("500.00"))
            }
        }

        "concurrent settlements filter their own order IDs without shared page metadata" {
            runTest {
                coEvery { backend.getRecentTradePage(any(), any(), any(), any()) } returns page(
                    trade(id = "fill-a", txid = "sell-a", cost = "100.00", fee = "0.00"),
                    trade(id = "fill-b", txid = "sell-b", cost = "200.00", fee = "0.00"),
                )
                coEvery { backend.getBalances() } returns mapOf(Asset.USD to BigDecimal("1000.00"))

                val first = async {
                    OrderSettleHelper.settleUsdAfterSells(
                        backend = backend,
                        openingUsd = BigDecimal.ZERO,
                        projectedCash = BigDecimal("100.00"),
                        sellOrderTxids = listOf("sell-a"),
                    )
                }
                val second = async {
                    OrderSettleHelper.settleUsdAfterSells(
                        backend = backend,
                        openingUsd = BigDecimal.ZERO,
                        projectedCash = BigDecimal("200.00"),
                        sellOrderTxids = listOf("sell-b"),
                    )
                }

                first.await().shouldBeEqualComparingTo(BigDecimal("100.00"))
                second.await().shouldBeEqualComparingTo(BigDecimal("200.00"))
            }
        }

        "invalid page cannot confirm fills and settlement uses balance polling" {
            runTest {
                coEvery { backend.getRecentTradePage(any(), any(), any(), any()) } returns page(
                    trade(id = "untrusted", txid = "sell-untrusted", cost = "5000.00", fee = "0.00"),
                    valid = false,
                )
                coEvery { backend.getBalances() } returns mapOf(Asset.USD to BigDecimal("4600.00"))

                val settled = OrderSettleHelper.settleUsdAfterSells(
                    backend = backend,
                    openingUsd = BigDecimal.ZERO,
                    projectedCash = BigDecimal("5000.00"),
                    sellOrderTxids = listOf("sell-untrusted"),
                )

                settled.shouldBeEqualComparingTo(BigDecimal("4600.00"))
            }
        }

        "cancellation from spendable balance peek propagates" {
            runTest {
                coEvery { backend.getRecentTradePage(any(), any(), any(), any()) } returns page(
                    trade(id = "fill-cancel", txid = "sell-cancel", cost = "505.00", fee = "0.00"),
                )
                coEvery { backend.getBalances() } throws CancellationException("settlement cancelled")

                val thrown = shouldThrow<CancellationException> {
                    OrderSettleHelper.settleUsdAfterSells(
                        backend = backend,
                        openingUsd = BigDecimal.ZERO,
                        projectedCash = BigDecimal("520.00"),
                        sellOrderTxids = listOf("sell-cancel"),
                    )
                }

                thrown.message shouldBe "settlement cancelled"
            }
        }

        "empty sell IDs use observed balance directly" {
            runTest {
                coEvery { backend.getBalances() } returns mapOf(Asset.USD to BigDecimal("3000.00"))

                val settled = OrderSettleHelper.settleUsdAfterSells(
                    backend = backend,
                    openingUsd = BigDecimal.ZERO,
                    projectedCash = BigDecimal("3000.00"),
                    sellOrderTxids = emptyList(),
                )

                settled.shouldBeEqualComparingTo(BigDecimal("3000.00"))
            }
        }
    }

    private fun page(vararg entries: ExchangeTradeFill, nextCursor: String? = null, valid: Boolean = true) =
        ExchangeTradePage(
            entries = entries.toList(),
            nextCursor = nextCursor,
            valid = valid,
        )

    private fun trade(id: String, txid: String, cost: String, fee: String, feeCurrency: String? = null) =
        ExchangeTradeFill(
            tradeId = id,
            orderTxid = txid,
            baseAsset = Asset.BTC,
            quoteCurrency = Asset.USD,
            feeCurrency = feeCurrency,
            type = "sell",
            price = "50000.00",
            cost = cost,
            fee = fee,
            volume = "0.10000000",
            hasValidTime = true,
            hasValidPrice = true,
            hasValidCost = true,
            hasValidFee = true,
            hasValidVolume = true,
            isValidRecord = true,
        )
}
