package com.gemini.krakenbot.config

import com.gemini.krakenbot.TestFixtures
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe

class SettingsTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "constructor_defaultsNullValues" {
            val settings =
                TestFixtures.settings(loopDelaySeconds = 10L, deviationTriggerPercent = 1.5, dustThresholdUSD = 5.0)
            settings.dustThresholdUSD shouldBe 5.0
            settings.fiatMaxDrawdown shouldBe 0.0
            settings.fiatDeploymentExponent shouldBe 1.0
            settings.dryRun.shouldBeTrue()
            settings.loopDelaySeconds shouldBe 10L
            settings.deviationTriggerPercent shouldBe 1.5
        }

        "constructor_retainsNonNullValues" {
            val settings =
                TestFixtures.settings(
                    dryRun = false,
                    loopDelaySeconds = 20L,
                    deviationTriggerPercent = 2.5,
                    dustThresholdUSD = 10.0,
                    fiatMaxDrawdown = 15.0,
                    fiatDeploymentExponent = 2.0,
                )
            settings.dustThresholdUSD shouldBe 10.0
            settings.fiatMaxDrawdown shouldBe 15.0
            settings.fiatDeploymentExponent shouldBe 2.0
            settings.dryRun shouldBe false
            settings.loopDelaySeconds shouldBe 20L
            settings.deviationTriggerPercent shouldBe 2.5
        }
    }
}
