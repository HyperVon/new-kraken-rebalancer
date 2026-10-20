package com.gemini.krakenbot.view.component

import com.gemini.krakenbot.service.CurrentCycleActivity
import com.gemini.krakenbot.service.CurrentCycleActivityMode
import com.gemini.krakenbot.service.history.HistoryCycleOutcome
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.html.div
import kotlinx.html.stream.createHTML
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class RecentActivityComponentTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    private fun activity(vararg actions: String) = CurrentCycleActivity(
        cycleId = "cycle-contract-1",
        occurredAt = Instant.parse("2026-10-11T12:34:56Z"),
        mode = CurrentCycleActivityMode.LIVE,
        outcome = HistoryCycleOutcome.ORDERS_ATTEMPTED,
        actions = actions.toList(),
    )

    private fun renderActions(vararg actions: String): String = createHTML().div {
        RecentActivityComponent().render(listOf(activity(*actions)), "source-1")
    }

    private fun visibleText(html: String) = html.replace(Regex("<[^>]*>"), "")

    init {
        "legacy live cost row humanizes and hides persisted markers" {
            val htmlString = renderActions($$"BUY BTC Volume: 0.1 Cost: $5000.00")

            htmlString shouldContain $$"BUY BTC · 0.1 · $5,000.00"
            visibleText(htmlString) shouldNotContain "Volume:"
            visibleText(htmlString) shouldNotContain $$"Cost: $5000.00"
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

        "current cycle deviation action humanizes to drift text" {
            val htmlString = renderActions("Deviation: BTC -5%")

            htmlString shouldContain "BTC -5% drift detected"
            visibleText(htmlString) shouldNotContain "Deviation:"
        }

        "current cycle fiat-correction action humanizes to cash-drift text" {
            val htmlString = renderActions("USD Deviation Triggered. Enforcing fiat correction.")

            htmlString shouldContain "Cash drift detected; applying correction"
            visibleText(htmlString) shouldNotContain "Enforcing fiat correction"
        }

        "current cycle cash distribution action humanizes to correction-distributed text" {
            val htmlString = renderActions($$"Distributing Fiat Correction ($100.00) among 2 candidates.")

            htmlString shouldContain "Cash correction distributed across underweight assets"
            visibleText(htmlString) shouldNotContain "Distributing Fiat Correction"
        }

        "cycle keeps action order and displays stable identity timestamp mode outcome and count" {
            val htmlString = createHTML().div {
                RecentActivityComponent().render(
                    listOf(
                        activity("first recorded warning", "ERROR: second recorded action", "last recorded note")
                            .copy(outcome = HistoryCycleOutcome.PROBLEM),
                    ),
                    "source-1",
                )
            }

            htmlString shouldContain "cycle-contract-1"
            htmlString shouldContain DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.systemDefault())
                .format(Instant.parse("2026-10-11T12:34:56Z"))
            htmlString shouldContain "LIVE TRADING"
            htmlString shouldContain "Problem"
            htmlString shouldContain "3 actions"
            htmlString.indexOf("first recorded warning") shouldBeLessThan
                htmlString.indexOf("ERROR: second recorded action")
            htmlString.indexOf("ERROR: second recorded action") shouldBeLessThan
                htmlString.indexOf("last recorded note")
        }

        "legacy activity with no outcome uses the recorded activity label" {
            val htmlString = createHTML().div {
                RecentActivityComponent().render(
                    listOf(activity("historical note").copy(outcome = null)),
                    "source-1",
                )
            }

            htmlString shouldContain "Recorded activity"
        }
    }
}
