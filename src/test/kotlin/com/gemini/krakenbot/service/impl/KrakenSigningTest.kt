package com.gemini.krakenbot.service.impl

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class KrakenSigningTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "matches the deterministic Kraken signing vector" {
            KrakenSigning.sign(
                path = "/0/private/Balance",
                nonce = "1700000000123",
                postData = "nonce=1700000000123&asset=XXBT",
                base64Secret = "dGVzdC1zZWNyZXQta2V5",
            ) shouldBe
                "n5uKrFkfc6ibkym5ndCh7d1X3XAmkDYJrpN6+usOEGK6EEQiJ2XKmN5QFJmLj0Ce9u6IcB4EkWqgZEwpVsYnGA=="
        }
    }
}
