@file:Suppress("unused")

package com.gemini.krakenbot.view.component

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.model.Asset
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.html.div
import kotlinx.html.stream.createHTML

class SettingsFormComponentTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "settings shows active trading controls and preserved allocation values only" {
            val config = AppConfig(
                kraken = KrakenCredentials("key", "secret"),
                settings = TestFixtures.settings(dryRun = true, simulation = true, loopDelaySeconds = 60),
                allocations = listOf(Allocation(Asset.BTC, 50.0), Allocation(Asset.USD, 50.0)),
            )
            val html = createHTML().div {
                SettingsFormComponent().renderForm(this, config, null, "csrf-token")
            }

            html shouldContain "Simulation Mode"
            html shouldContain "Dry Run Mode"
            html shouldContain "value=\"50.0\""
            html shouldContain "loopDelaySeconds"
            html shouldContain "deviationTriggerPercent"
            html shouldContain "minimumOrderSizeUSD"
            html.lowercase() shouldNotContain "inception"
            html.lowercase() shouldNotContain "deployment"
            html.lowercase() shouldNotContain "drawdown"
            html.lowercase() shouldNotContain "sync-progress"
        }

        "settings rejection renders submitted controls against the saved baseline" {
            val config = AppConfig(
                kraken = KrakenCredentials("key", "secret"),
                settings = TestFixtures.settings(dryRun = false, simulation = false, loopDelaySeconds = 60),
                allocations = listOf(Allocation(Asset.BTC, 50.0), Allocation(Asset.USD, 50.0)),
            )
            val formValues = SettingsFormValues(
                loopDelaySeconds = "99",
                deviationTriggerPercent = "2.5",
                minimumOrderSizeUsd = "25.0",
                simulation = true,
                dryRun = true,
                allocations = listOf(
                    SettingsAllocationFormValue(Asset.ETH, "#112233", "40.0", "8"),
                    SettingsAllocationFormValue(Asset.USD, "#445566", "60.0", ""),
                ),
                scoreEmphasis = "4",
                scoreSleevePercent = "20.0",
            )
            val html = createHTML().div {
                SettingsFormComponent().renderForm(this, config, "Rejected", "csrf-token", formValues = formValues)
            }

            html shouldContain "value=\"99\""
            html shouldContain "value=\"2.5\""
            html shouldContain "value=\"25.0\""
            html shouldContain "checked"
            html shouldContain "value=\"ETH\""
            html shouldContain "value=\"#112233\""
            html shouldContain "value=\"40.0\""
            html shouldContain "value=\"8\""
            html shouldContain "value=\"20.0\""
            html shouldContain "data-settings-baseline=\"16:loopDelaySeconds2:60"
            html shouldContain "Rejected"
            val baseline = Regex("data-settings-baseline=\"([^\"]+)\"").find(html)!!.groupValues[1]
            baseline shouldContain "10:simulation5:false"
            baseline shouldContain "6:dryRun5:false"
            baseline shouldContain "7:symbols3:BTC"
            baseline shouldContain "7:targets4:50.0"
            baseline shouldContain "7:symbols3:USD"
            baseline shouldNotContain "7:symbols3:ETH"
            baseline.split("7:targets4:50.0").size shouldBe 3
            baseline shouldNotContain "4:40.0"
            baseline shouldNotContain "4:60.0"
            listOf("simulation", "dryRun").forEach { name ->
                Regex("""<input\b(?=[^>]*name="$name")(?=[^>]*checked)[^>]*>""")
                    .containsMatchIn(html) shouldBe true
            }
        }
    }
}
