package com.gemini.krakenbot.model

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

@Suppress("unused")
class TradeSourceTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "fromDbValue maps persisted provenance and rejects unknown values" {
            TradeSource.fromDbValue("LOCAL_ESTIMATE") shouldBe TradeSource.LOCAL_ESTIMATE
            TradeSource.fromDbValue("API_FILL") shouldBe TradeSource.API_FILL
            TradeSource.fromDbValue("LEGACY_UNKNOWN") shouldBe TradeSource.LEGACY_UNKNOWN
            TradeSource.fromDbValue("unknown") shouldBe null
            TradeSource.fromDbValue(null) shouldBe null
        }
    }
}
