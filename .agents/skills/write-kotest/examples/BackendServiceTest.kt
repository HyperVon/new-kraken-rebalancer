package com.gemini.krakenbot.service

import com.gemini.krakenbot.test.FakeKrakenService
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal

@Suppress("unused")
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

                val totalValue = BigDecimal("1.50000000") * BigDecimal("60000.00") + BigDecimal("5000.00")
                totalValue.shouldBeEqualComparingTo(BigDecimal("95000.00"))
            }
        }
    }
}
