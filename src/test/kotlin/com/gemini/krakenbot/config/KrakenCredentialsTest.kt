package com.gemini.krakenbot.config

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class KrakenCredentialsTest : StringSpec() {
    init {
        "hasValidCredentials is true only when both key and secret are real" {
            KrakenCredentials("real-api-key", "real-private-key").hasValidCredentials() shouldBe true
        }

        "hasValidCredentials is false for a blank or placeholder api key" {
            KrakenCredentials("", "real-private-key").hasValidCredentials() shouldBe false
            KrakenCredentials(
                KrakenCredentials.PLACEHOLDER_API_KEY,
                "real-private-key",
            ).hasValidCredentials() shouldBe false
        }

        "hasValidCredentials is false for a blank or placeholder private key" {
            KrakenCredentials("real-api-key", "").hasValidCredentials() shouldBe false
            KrakenCredentials(
                "real-api-key",
                KrakenCredentials.PLACEHOLDER_PRIVATE_KEY,
            ).hasValidCredentials() shouldBe false
        }
    }
}
