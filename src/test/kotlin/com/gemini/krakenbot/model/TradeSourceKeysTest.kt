package com.gemini.krakenbot.model

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class TradeSourceKeysTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "LOCAL_ESTIMATE matches JVM TradeSource enum name" {
            TradeSourceKeys.LOCAL_ESTIMATE shouldBe TradeSource.LOCAL_ESTIMATE.name
        }
    }
}
