package com.gemini.krakenbot.frontend

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class HelperTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "getUniqueSymbols excludes and includes USD correctly" {
            val snapshots = arrayOf(
                js("({ assets: { BTC: {}, ETH: {}, USD: {} } })"),
                js("({ assets: null })"),
                js("({ })")
            )
            val symbolsExcludeUsd = getUniqueSymbols(snapshots, excludeUsd = true)
            symbolsExcludeUsd shouldBe listOf("BTC", "ETH")

            val symbolsIncludeUsd = getUniqueSymbols(snapshots, excludeUsd = false)
            symbolsIncludeUsd shouldBe listOf("BTC", "ETH", "USD")
        }

        "getUniqueSymbols returns empty list when no assets" {
            val snapshots = arrayOf(
                js("({ })"),
                js("({ assets: null })")
            )
            getUniqueSymbols(snapshots, excludeUsd = true) shouldBe emptyList()
        }
    }
}