package com.gemini.krakenbot.view.util

import com.gemini.krakenbot.model.OrderSide
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal

class ActionLogFormatterTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "formatDeviationTrigger should format deviation message" {
            val msg = ActionLogFormatter.formatDeviationTrigger("BTC", BigDecimal("5.2"))
            msg shouldBe "Deviation: BTC 5.2%"
        }

        "formatFiatCorrectionEnforced should return constant message" {
            ActionLogFormatter.formatFiatCorrectionEnforced() shouldBe
                "USD Deviation Triggered. Enforcing fiat correction."
        }

        "formatFiatCorrectionDistribution should format distribution message" {
            val msg = ActionLogFormatter.formatFiatCorrectionDistribution(BigDecimal("250.00"), 3)
            msg shouldBe "Distributing Fiat Correction ($250.00) among 3 candidates."
        }

        "formatOrderExecution should format buy and sell order messages" {
            val sellMsg = ActionLogFormatter.formatOrderExecution(
                side = OrderSide.SELL,
                symbol = "ETH",
                volume = BigDecimal("1.5"),
                usdAmount = BigDecimal("3000.00"),
                isDryRun = true,
            )
            sellMsg shouldBe "[DRY RUN] SELL ETH Volume: 1.5 Value: $3000.00"

            val buyMsg = ActionLogFormatter.formatOrderExecution(
                side = OrderSide.BUY,
                symbol = "BTC",
                volume = BigDecimal("0.1"),
                usdAmount = BigDecimal("5000.00"),
                isDryRun = false,
            )
            buyMsg shouldBe "BUY BTC Volume: 0.1 Cost: $5000.00"
        }

        "formatOrderExecution should trim USD to 2 decimals" {
            val msg = ActionLogFormatter.formatOrderExecution(
                side = OrderSide.BUY,
                symbol = "BTC",
                volume = BigDecimal("0.012345678"),
                usdAmount = BigDecimal("1063.3999959596"),
                isDryRun = false,
            )
            msg shouldBe "BUY BTC Volume: 0.01234568 Cost: $1063.40"
        }

        "formatOrderFailure should format error message" {
            val msg = ActionLogFormatter.formatOrderFailure(OrderSide.BUY, "SOL", "Insufficient funds")
            msg shouldBe "FAILED BUY SOL: Insufficient funds"
        }

        "formatSkippedDust should format dust message" {
            val msg = ActionLogFormatter.formatSkippedDust(OrderSide.BUY, "DOGE", BigDecimal("0.50"))
            msg shouldBe "Skipping dust buy for DOGE ($0.50)"
        }

        "formatSkippedMissingPrice should format missing-price message" {
            val msg = ActionLogFormatter.formatSkippedMissingPrice(OrderSide.SELL, "XBT")
            msg shouldBe "Skipping — no price for sell XBT"
        }
    }
}
