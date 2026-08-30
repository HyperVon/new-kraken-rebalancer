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

class RecentActivityComponentTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    private fun snapshot(vararg actions: String) = PortfolioSnapshot(
        timestamp = Instant.now(),
        totalValueUSD = BigDecimal("1000.00"),
        assets = emptyMap(),
        actions = actions.toList(),
        drawdownPercent = BigDecimal.ZERO,
        fiatDeploymentPercent = BigDecimal.ZERO,
        effectiveUsdTargetPercent = BigDecimal("100.00"),
    )

    private fun renderActions(vararg actions: String): String = createHTML().div {
        RecentActivityComponent().render(listOf(snapshot(*actions)))
    }

    init {
        "legacy live cost row humanizes and hides persisted markers" {
            val htmlString = renderActions($$"BUY BTC Volume: 0.1 Cost: $5000.00")

            htmlString shouldContain $$"BUY BTC · 0.1 · $5,000.00"
            htmlString shouldNotContain "Volume:"
            htmlString shouldNotContain $$"Cost: $5000.00"
        }

        "legacy dry-run value row humanizes and keeps the DRY RUN badge" {
            val htmlString = renderActions($$"[DRY RUN] SELL ETH Volume: 1.5 Value: $3000.00")

            htmlString shouldContain $$"SELL ETH · 1.5 · $3,000.00"
            htmlString shouldContain "· DRY RUN"
        }

        "unparseable volume falls back to the raw stored action" {
            val htmlString = renderActions($$"BUY BTC Volume: abc Cost: $5.00")

            htmlString shouldContain "Volume: abc"
        }

        "historical deviation row humanizes to drift text" {
            val htmlString = renderActions("Deviation: BTC -5%")

            htmlString shouldContain "BTC -5% drift detected"
            htmlString shouldNotContain "Deviation:"
        }

        "historical fiat-correction row humanizes to cash-drift text" {
            val htmlString = renderActions("USD Deviation Triggered. Enforcing fiat correction.")

            htmlString shouldContain "Cash drift detected; applying correction"
            htmlString shouldNotContain "Enforcing fiat correction"
        }

        "historical distributing row humanizes to correction-distributed text" {
            val htmlString = renderActions($$"Distributing Fiat Correction ($100.00) among 2 candidates.")

            htmlString shouldContain "Cash correction distributed across underweight assets"
            htmlString shouldNotContain "Distributing Fiat Correction"
        }
    }
}
