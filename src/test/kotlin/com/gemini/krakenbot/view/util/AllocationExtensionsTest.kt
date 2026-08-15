package com.gemini.krakenbot.view.util

import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.model.Asset
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe

class AllocationExtensionsTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "symbolColorMap extracts uppercase symbol to color map only for non-null colors" {
            val allocations = listOf(
                Allocation(Asset.BTC, 50.0, "#fbbf24"),
                Allocation(Asset.ETH, 30.0, null),
                Allocation(Asset.USD, 20.0, "#94a3b8"),
            )
            val result = allocations.symbolColorMap()
            result shouldContainExactly mapOf(
                "BTC" to "#fbbf24",
                "USD" to "#94a3b8",
            )
        }

        "symbolColorMap returns empty map when no allocations have colors" {
            val allocations = listOf(
                Allocation(Asset.BTC, 50.0, null),
                Allocation(Asset.USD, 50.0, null),
            )
            allocations.symbolColorMap().shouldBeEmpty()
        }
    }
}
