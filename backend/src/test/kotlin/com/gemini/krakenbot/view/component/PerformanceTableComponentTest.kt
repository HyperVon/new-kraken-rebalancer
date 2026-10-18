package com.gemini.krakenbot.view.component

import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.PortfolioSnapshot
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.html.div
import kotlinx.html.stream.createHTML
import java.math.BigDecimal
import java.time.Instant

class PerformanceTableComponentTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    private fun render(scores: Map<String, BigDecimal> = emptyMap()): String {
        val asset = PortfolioSnapshot.AssetSnapshot(
            symbol = "DOGE",
            balance = BigDecimal("12345.67890123"),
            price = BigDecimal("0.00456789"),
            valueUSD = BigDecimal("56.39"),
            targetPercent = BigDecimal("50"),
            currentPercent = BigDecimal("40"),
            deviationPercent = BigDecimal("-20"),
            deviationUSD = BigDecimal("-14.10"),
        )
        val snapshot = PortfolioSnapshot(
            timestamp = Instant.EPOCH,
            totalValueUSD = BigDecimal("140.975"),
            assets = mapOf("DOGE" to asset, "USD" to asset.copy(symbol = Asset("USD"))),
            actions = emptyList(),
            effectiveUsdTargetPercent = BigDecimal("50"),
        )
        return createHTML().div { PerformanceTableComponent().render(snapshot, scores) }
    }

    init {
        "holdings show precise amounts and configured quality with numeric sorting and History links" {
            val html = render(mapOf("DOGE" to BigDecimal("7.5")))

            html shouldContain "href=\"/history?asset=DOGE\""
            html shouldContain "12,345.67890123"
            html shouldContain "data-sort-value=\"12345.67890123\""
            html shouldContain "data-sort-value=\"7.5\""
            html shouldContain "sortTable(this, 5)"
            html shouldContain "sortTable(this, 6)"
            html shouldContain "sortTable(this, 7)"
            Regex("<th ").findAll(html).count() shouldBe 8
            Regex("<td ").findAll(html).count() shouldBe 6
            html shouldNotContain "asset=USD"
        }

        "sub-cent prices stay visible and negative dollar deviations use the money sign convention" {
            val html = render()

            html shouldContain "$0.00456789"
            html shouldContain "(-$14.10)"
            html shouldNotContain "$-14.10"
            html shouldContain "—"
        }
    }
}
