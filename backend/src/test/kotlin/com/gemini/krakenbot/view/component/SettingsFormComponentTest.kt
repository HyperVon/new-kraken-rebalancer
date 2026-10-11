package com.gemini.krakenbot.view.component

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.model.Asset
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
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
    }
}
