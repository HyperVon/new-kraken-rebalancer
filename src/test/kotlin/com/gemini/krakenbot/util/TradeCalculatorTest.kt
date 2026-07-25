package com.gemini.krakenbot.util

import com.gemini.krakenbot.model.OrderResult
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

        "calculateSlippage should compute buy slippage correctly" {
            val slippage = TradeCalculator.calculateSlippage(
                OrderSide.BUY.uppercaseName,
                BigDecimal("105.0"),
                BigDecimal("100.0"),
            )
            slippage.shouldBeEqualComparingTo(BigDecimal("5.0000"))
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
            fee.shouldBeEqualComparingTo(BigDecimal("2.6000"))
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
    }
}
