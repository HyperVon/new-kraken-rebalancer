package com.gemini.krakenbot.domain

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal

class RebalanceEventFormatterTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "formats DeviationTriggered event" {
            val event = RebalanceEvent.DeviationTriggered("BTC", BigDecimal("10.5"))
            RebalanceEventFormatter.format(event) shouldBe "Deviation: BTC 10.5%"
        }

        "formats FiatCorrectionEnforced event" {
            val event = RebalanceEvent.FiatCorrectionEnforced
            RebalanceEventFormatter.format(event) shouldBe "USD Deviation Triggered. Enforcing fiat correction."
        }

        "formats FiatCorrectionDistributed event" {
            val event = RebalanceEvent.FiatCorrectionDistributed(BigDecimal("500.00"), 2)
            RebalanceEventFormatter.format(event) shouldBe "Distributing Fiat Correction ($500.00) among 2 candidates."
        }

        "formats NoCounterBalancingAssets event" {
            val event = RebalanceEvent.NoCounterBalancingAssets
            RebalanceEventFormatter.format(event) shouldBe "Fiat correction: no counter-balancing candidates"
        }
    }
}
