package com.gemini.krakenbot.domain

import com.gemini.krakenbot.model.OrderSide
import com.gemini.krakenbot.model.TradeSource
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant

@Suppress("unused")
class TradeCalculatorTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    init {

        "calculateExecutedPrice should divide usdAmount by volume when positive" {
            val price = TradeCalculator.calculateExecutedPrice(BigDecimal("100.00"), BigDecimal("2.0"))
            price.shouldBeEqualComparingTo(BigDecimal("50.00"))
        }

        "calculateExecutedPrice should return zero when volume is zero" {
            val price = TradeCalculator.calculateExecutedPrice(BigDecimal("100.00"), BigDecimal.ZERO)
            price.shouldBeEqualComparingTo(BigDecimal.ZERO)
        }

        "calculateSlippage should return zero when expected price is zero" {
            val slippage = TradeCalculator.calculateSlippage(
                OrderSide.BUY.uppercaseName,
                BigDecimal("105.0"),
                BigDecimal.ZERO,
            )
            slippage.shouldBeEqualComparingTo(BigDecimal.ZERO)
        }

        "calculateSlippage should return zero when expected price is negative" {
            val slippage = TradeCalculator.calculateSlippage(
                OrderSide.BUY.uppercaseName,
                BigDecimal("105.0"),
                BigDecimal("-10.0"),
            )
            slippage.shouldBeEqualComparingTo(BigDecimal.ZERO)
        }

        "calculateSlippage should compute buy slippage correctly" {
            val slippage = TradeCalculator.calculateSlippage(
                OrderSide.BUY.uppercaseName,
                BigDecimal("105.0"),
                BigDecimal("100.0"),
            )
            slippage.shouldBeEqualComparingTo(BigDecimal("5.0000"))
        }

        "calculateSlippage should retain four-decimal percentage precision" {
            val slippage = TradeCalculator.calculateSlippage(
                OrderSide.BUY.uppercaseName,
                BigDecimal("100.1234"),
                BigDecimal("100.0000"),
            )
            slippage.shouldBeEqualComparingTo(BigDecimal("0.1234"))
        }

        "calculateSlippage should treat lowercase buy like BUY" {
            val slippage = TradeCalculator.calculateSlippage(
                OrderSide.BUY.apiValue,
                BigDecimal("105.0"),
                BigDecimal("100.0"),
            )
            slippage.shouldBeEqualComparingTo(BigDecimal("5.0000"))
        }

        "calculateSlippage should return zero for unknown side instead of sell formula" {
            val slippage = TradeCalculator.calculateSlippage(
                "hold",
                BigDecimal("105.0"),
                BigDecimal("100.0"),
            )
            slippage.shouldBeEqualComparingTo(BigDecimal.ZERO)
        }

        "calculateSlippage should compute sell slippage correctly" {
            val slippage = TradeCalculator.calculateSlippage(
                OrderSide.SELL.uppercaseName,
                BigDecimal("95.0"),
                BigDecimal("100.0"),
            )
            slippage.shouldBeEqualComparingTo(BigDecimal("5.0000"))
        }

        "estimateFee should calculate fee correctly" {
            val fee = TradeCalculator.estimateFee(BigDecimal("1000.00"))
            fee.shouldBeEqualComparingTo(BigDecimal("6.0000"))
        }

        "createTradeRecord should assemble full TradeRecord" {
            val orderResult = OrderResult(
                success = true,
                dryRun = true,
                pair = "XBTUSD",
                side = OrderSide.BUY.uppercaseName,
                volume = BigDecimal("0.1"),
            )
            val prices = mapOf("BTC" to BigDecimal("50000.00"))
            val timestamp = Instant.now()

            val trade = TradeCalculator.createTradeRecord(
                result = orderResult,
                symbol = "BTC",
                pair = "XBTUSD",
                side = OrderSide.BUY.uppercaseName,
                volume = BigDecimal("0.1"),
                usdAmount = BigDecimal("5000.00"),
                prices = prices,
                timestamp = timestamp,
            )

            trade.symbol shouldBe "BTC"
            trade.pair shouldBe "XBTUSD"
            trade.side shouldBe OrderSide.BUY.uppercaseName
            trade.dryRun shouldBe true
            trade.price.shouldBeEqualComparingTo(BigDecimal("50000.00"))
            trade.expectedPrice!!.shouldBeEqualComparingTo(BigDecimal("50000.00"))
            trade.source shouldBe TradeSource.LOCAL_ESTIMATE
            trade.slippagePercent!!.shouldBeEqualComparingTo(BigDecimal.ZERO)
        }

        "createTradeRecord should normalize lowercase side to BUY" {
            val orderResult = OrderResult(
                success = true,
                dryRun = true,
                pair = "XBTUSD",
                side = OrderSide.BUY.apiValue,
                volume = BigDecimal("0.1"),
            )
            val trade = TradeCalculator.createTradeRecord(
                result = orderResult,
                symbol = "BTC",
                pair = "XBTUSD",
                side = OrderSide.BUY.apiValue,
                volume = BigDecimal("0.1"),
                usdAmount = BigDecimal("5000.00"),
                prices = mapOf("BTC" to BigDecimal("50000.00")),
            )
            trade.side shouldBe OrderSide.BUY.uppercaseName
        }

        "createTradeRecord falls back to BigDecimal.ZERO when symbol missing in prices" {
            val orderResult = OrderResult(
                success = true,
                dryRun = false,
                pair = "XBTUSD",
                side = OrderSide.BUY.uppercaseName,
                volume = BigDecimal("0.1"),
            )
            val trade = TradeCalculator.createTradeRecord(
                result = orderResult,
                symbol = "BTC",
                pair = "XBTUSD",
                side = OrderSide.BUY.uppercaseName,
                volume = BigDecimal("0.1"),
                usdAmount = BigDecimal("5000.00"),
                prices = emptyMap(),
            )
            trade.expectedPrice shouldBe BigDecimal.ZERO
            trade.slippagePercent shouldBe BigDecimal.ZERO
        }

        "createTradeRecord propagates order outcome and cycle provenance into the durable record" {
            val result = OrderResult(
                success = true,
                pair = "XXBTZUSD",
                side = "buy",
                volume = BigDecimal("0.5"),
                orderTxid = "TX-9",
            )

            val trade = TradeCalculator.createTradeRecord(
                result = result,
                symbol = "BTC",
                pair = "XXBTZUSD",
                side = "buy",
                volume = BigDecimal("0.5"),
                usdAmount = BigDecimal("25000.00"),
                prices = mapOf("BTC" to BigDecimal("50000")),
                timestamp = Instant.parse("2026-08-21T00:00:00Z"),
                cycleId = "cycle-7",
            )

            trade.orderTxid shouldBe "TX-9"
            trade.cycleId shouldBe "cycle-7"
            trade.success shouldBe true
            trade.source shouldBe TradeSource.LOCAL_ESTIMATE
            trade.price.shouldBeEqualComparingTo(BigDecimal("50000"))
        }
    }
}
