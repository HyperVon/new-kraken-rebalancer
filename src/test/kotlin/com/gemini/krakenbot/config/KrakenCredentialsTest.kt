@file:Suppress("unused")

package com.gemini.krakenbot.config

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlin.io.encoding.Base64

class KrakenCredentialsTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    private val validPrivateKey = Base64.encode("test-private-key".encodeToByteArray())

    init {
        "hasValidCredentials is true only when both key and secret are real Base64 material" {
            KrakenCredentials("real-api-key", validPrivateKey).hasValidCredentials() shouldBe true
        }

        "hasValidCredentials is false for a blank or placeholder api key" {
            KrakenCredentials("", validPrivateKey).hasValidCredentials() shouldBe false
            KrakenCredentials(
                KrakenCredentials.PLACEHOLDER_API_KEY,
                validPrivateKey,
            ).hasValidCredentials() shouldBe false
        }

        "hasValidCredentials is false for a blank or placeholder private key" {
            KrakenCredentials("real-api-key", "").hasValidCredentials() shouldBe false
            KrakenCredentials(
                "real-api-key",
                KrakenCredentials.PLACEHOLDER_PRIVATE_KEY,
            ).hasValidCredentials() shouldBe false
        }

        "hasValidCredentials is false for malformed private key Base64" {
            KrakenCredentials("real-api-key", "invalid_base64_!@#$").hasValidCredentials() shouldBe false
            KrakenCredentials("real-api-key", "real-private-key").hasValidCredentials() shouldBe false
            KrakenCredentials("real-api-key", "not=valid=base64!!!").hasValidCredentials() shouldBe false
        }

        "string representations redact both credential values" {
            val credentials = KrakenCredentials("raw-api-key", validPrivateKey)

            credentials.apiKey.toString() shouldBe "***REDACTED***"
            credentials.privateKey.toString() shouldBe "***REDACTED***"
            credentials.toString() shouldNotContain "raw-api-key"
            credentials.toString() shouldNotContain validPrivateKey
        }
    }
}
