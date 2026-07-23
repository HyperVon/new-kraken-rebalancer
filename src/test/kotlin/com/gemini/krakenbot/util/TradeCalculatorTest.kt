package com.gemini.krakenbot.util

import com.gemini.krakenbot.model.OrderResult
import com.gemini.krakenbot.model.OrderSide
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant

@Suppress("unused")
class TradeCalculatorTest : StringSpec({

    "calculateExecutedPrice should divide usdAmount by volume when positive" {
        val price = TradeCalculator.calculateExecutedPrice(BigDecimal("100.00"), BigDecimal("2.0"))
        price.compareTo(BigDecimal("50.00")) shouldBe 0
    }

    "calculateExecutedPrice should return zero when volume is zero" {
        val price = TradeCalculator.calculateExecutedPrice(BigDecimal("100.00"), BigDecimal.ZERO)
        price.compareTo(BigDecimal.ZERO) shouldBe 0
    }

    "calculateSlippage should return zero when expected price is zero" {
        val slippage = TradeCalculator.calculateSlippage(OrderSide.BUY.uppercaseName, BigDecimal("105.0"), BigDecimal.ZERO)
        slippage.compareTo(BigDecimal.ZERO) shouldBe 0
    }

    "calculateSlippage should compute buy slippage correctly" {
        val slippage = TradeCalculator.calculateSlippage(OrderSide.BUY.uppercaseName, BigDecimal("105.0"), BigDecimal("100.0"))
        slippage.compareTo(BigDecimal("5.0000")) shouldBe 0
    }

    "calculateSlippage should compute sell slippage correctly" {
        val slippage = TradeCalculator.calculateSlippage(OrderSide.SELL.uppercaseName, BigDecimal("95.0"), BigDecimal("100.0"))
        slippage.compareTo(BigDecimal("5.0000")) shouldBe 0
    }

    "estimateFee should calculate fee correctly" {
        val fee = TradeCalculator.estimateFee(BigDecimal("1000.00"))
        fee.compareTo(BigDecimal("2.6000")) shouldBe 0
    }

    "createTradeRecord should assemble full TradeRecord" {
        val orderResult = OrderResult(
            success = true,
            dryRun = true,
            pair = "XBTUSD",
            side = OrderSide.BUY.uppercaseName,
            volume = BigDecimal("0.1")
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
            timestamp = timestamp
        )

        trade.symbol shouldBe "BTC"
        trade.pair shouldBe "XBTUSD"
        trade.side shouldBe OrderSide.BUY.uppercaseName
        trade.dryRun shouldBe true
        trade.price.compareTo(BigDecimal("50000.00")) shouldBe 0
    }
})
