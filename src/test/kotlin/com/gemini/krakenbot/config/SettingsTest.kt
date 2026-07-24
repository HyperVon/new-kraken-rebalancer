package com.gemini.krakenbot.config

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe

@Suppress("unused")
class SettingsTest : StringSpec() {
    init {
        "constructor_defaultsNullValues" {
            val settings =
                Settings(
                    loopDelaySeconds = 10L,
                    deviationTriggerPercent = 1.5,
                    dustThresholdUSD = 5.0,
                    dryRun = true,
                    fiatMaxDrawdown = 0.0,
                    fiatDeploymentExponent = 1.0,
                )
            settings.dustThresholdUSD shouldBe 5.0
            settings.fiatMaxDrawdown shouldBe 0.0
            settings.fiatDeploymentExponent shouldBe 1.0
            settings.dryRun.shouldBeTrue()
            settings.loopDelaySeconds shouldBe 10L
            settings.deviationTriggerPercent shouldBe 1.5
        }

        "constructor_retainsNonNullValues" {
            val settings =
                Settings(
                    loopDelaySeconds = 20L,
                    deviationTriggerPercent = 2.5,
                    dustThresholdUSD = 10.0,
                    dryRun = false,
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
