package com.gemini.krakenbot.view.component

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.model.PortfolioSnapshot
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.string.shouldContain
import kotlinx.html.div
import kotlinx.html.stream.createHTML
import java.math.BigDecimal
import java.time.Instant

class OverviewGridComponentTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "cash tile shows the effective target and base allocation when USD data is available" {
            val snapshot = PortfolioSnapshot(
                timestamp = Instant.parse("2026-10-11T12:00:00Z"),
                totalValueUSD = BigDecimal("100.00"),
                assets = mapOf(
                    "USD" to TestFixtures.assetSnapshot(
                        symbol = "USD",
                        balance = BigDecimal("20.00"),
                        price = BigDecimal.ONE,
                        valueUSD = BigDecimal("20.00"),
                        targetPercent = BigDecimal("10.00"),
                        currentPercent = BigDecimal("20.00"),
                        deviationPercent = BigDecimal("10.00"),
                        deviationUSD = BigDecimal("10.00"),
                    ),
                ),
                actions = emptyList(),
                effectiveUsdTargetPercent = BigDecimal("20.00"),
            )

            val html = createHTML().div { OverviewGridComponent().render(snapshot) }

            html shouldContain "Target: 20% (Base: 10%)"
            html shouldContain "Dev: +10%"
        }
    }
}
