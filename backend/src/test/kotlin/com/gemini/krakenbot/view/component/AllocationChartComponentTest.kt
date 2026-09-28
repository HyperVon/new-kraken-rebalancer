package com.gemini.krakenbot.view.component

import com.gemini.krakenbot.model.PortfolioSnapshot
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.html.div
import kotlinx.html.stream.createHTML
import java.math.BigDecimal
import java.time.Instant

class AllocationChartComponentTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    private fun snapshotWith(vararg values: Pair<String, String>): PortfolioSnapshot = PortfolioSnapshot(
        timestamp = Instant.now(),
        totalValueUSD = values.fold(BigDecimal.ZERO) { acc, (_, v) -> acc.add(BigDecimal(v)) },
        assets = values.associate { (symbol, value) ->
            symbol to PortfolioSnapshot.AssetSnapshot(
                symbol = symbol,
                balance = BigDecimal.ONE,
                price = BigDecimal(value),
                valueUSD = BigDecimal(value),
                targetPercent = BigDecimal("25.00"),
                currentPercent = BigDecimal("25.00"),
                deviationPercent = BigDecimal.ZERO,
                deviationUSD = BigDecimal.ZERO,
            )
        },
        actions = emptyList(),
        drawdownPercent = BigDecimal.ZERO,
        fiatDeploymentPercent = BigDecimal.ZERO,
        effectiveUsdTargetPercent = BigDecimal("5.00"),
    )

    private fun render(snapshot: PortfolioSnapshot, scores: Map<String, BigDecimal> = emptyMap()): String =
        createHTML().div { AllocationChartComponent().render(snapshot, emptyList(), scores) }

    init {
        "quality profile is omitted when no scores are configured" {
            val htmlString = render(snapshotWith("BTC" to "600.00", "TAO" to "400.00"))

            htmlString shouldNotContain "Weighted quality score"
            // The allocation bars themselves are unchanged.
            htmlString shouldContain "BTC"
            htmlString shouldContain "TAO"
        }

        "quality profile is omitted when nothing in the book is scored" {
            val htmlString = render(
                snapshotWith("BTC" to "600.00", "TAO" to "400.00"),
                mapOf("XLM" to BigDecimal("9.0")),
            )

            htmlString shouldNotContain "Weighted quality score"
        }

        "quality profile reports score, concentration and effective bet count" {
            val htmlString = render(
                snapshotWith("BTC" to "600.00", "TAO" to "400.00"),
                mapOf("BTC" to BigDecimal("9.5"), "TAO" to BigDecimal("6.0")),
            )

            // (600 x 9.5 + 400 x 6.0) / 1000 = 8.10
            htmlString shouldContain "8.10"
            htmlString shouldContain "60.00%"
            // 1 / (0.6^2 + 0.4^2) = 1.92
            htmlString shouldContain "1.92"
        }
    }
}
