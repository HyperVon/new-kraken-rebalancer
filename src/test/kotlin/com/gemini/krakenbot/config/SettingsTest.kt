package com.gemini.krakenbot.config

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe

@Suppress("unused")
class SettingsTest : StringSpec({
    "constructor_defaultsNullValues" {
        val settings = Settings(10L, 1.5, 5.0, true, 0.0, 1.0)
        settings.dustThresholdUSD shouldBe 5.0
        settings.fiatMaxDrawdown shouldBe 0.0
        settings.fiatDeploymentExponent shouldBe 1.0
        settings.dryRun.shouldBeTrue()
        settings.loopDelaySeconds shouldBe 10L
        settings.deviationTriggerPercent shouldBe 1.5
    }

    "constructor_retainsNonNullValues" {
        val settings = Settings(20L, 2.5, 10.0, false, 15.0, 2.0)
        settings.dustThresholdUSD shouldBe 10.0
        settings.fiatMaxDrawdown shouldBe 15.0
        settings.fiatDeploymentExponent shouldBe 2.0
        settings.dryRun shouldBe false
        settings.loopDelaySeconds shouldBe 20L
        settings.deviationTriggerPercent shouldBe 2.5
    }
})
