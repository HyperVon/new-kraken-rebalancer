package com.gemini.krakenbot.service

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal

class BackendServiceTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "should calculate asset valuations using strict BigDecimal precision" {
            runTest {
                val fakeKraken = FakeKrakenService().apply {
                    balanceSupplier = {
                        mapOf("XXBT" to BigDecimal("1.50000000"), "ZUSD" to BigDecimal("5000.00"))
                    }
                    pricesSupplier = {
                        mapOf("XBTUSD" to BigDecimal("60000.00"))
                    }
                }

                val balances = fakeKraken.getBalances()
                val prices = fakeKraken.getTickerPrices("XBTUSD")
                val totalValue = balances.getValue("XXBT") * prices.getValue("XBTUSD") + balances.getValue("ZUSD")
                totalValue.shouldBeEqualComparingTo(BigDecimal("95000.00"))
            }
        }
    }
}
