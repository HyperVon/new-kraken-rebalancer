package com.gemini.krakenbot.service

import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.model.Asset
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

@Suppress("unused")
class AssetColorAssignerTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "normalizeHex accepts hashless and whitespace-padded values" {
            AssetColorAssigner.normalizeHex("60A5FA") shouldBe "#60a5fa"
            AssetColorAssigner.normalizeHex("  #60a5fa  ") shouldBe "#60a5fa"
        }

        "normalizeHex accepts lowercase and uppercase #rrggbb" {
            AssetColorAssigner.normalizeHex("#60A5FA") shouldBe "#60a5fa"
            AssetColorAssigner.normalizeHex("#60a5fa") shouldBe "#60a5fa"
        }

        "normalizeHex rejects blank, short, and non-hex values" {
            AssetColorAssigner.normalizeHex(null).shouldBeNull()
            AssetColorAssigner.normalizeHex("").shouldBeNull()
            AssetColorAssigner.normalizeHex("red").shouldBeNull()
            AssetColorAssigner.normalizeHex("#fff").shouldBeNull()
            AssetColorAssigner.normalizeHex("x\"};alert(1);//").shouldBeNull()
        }

        "assignMissingColors fills known defaults for BTC ETH USD" {
            val result = AssetColorAssigner.assignMissingColors(
                listOf(
                    Allocation(Asset.BTC, 40.0),
                    Allocation(Asset.ETH, 40.0),
                    Allocation(Asset.USD, 20.0),
                ),
            )
            result.map { it.color } shouldContainExactly listOf("#fbbf24", "#a78bfa", "#94a3b8")
        }

        "assignMissingColors preserves valid existing colors" {
            val result = AssetColorAssigner.assignMissingColors(
                listOf(Allocation(Asset.SOL, 100.0, "#ABCDEF")),
            )
            result.single().color shouldBe "#abcdef"
        }

        "assignMissingColors replaces invalid colors" {
            val result = AssetColorAssigner.assignMissingColors(
                listOf(Allocation(Asset.SOL, 100.0, "not-a-color")),
            )
            result.single().color.shouldNotBeNull()
            result.single().color shouldNotBe "not-a-color"
        }

        "assignMissingColors skips known default when already used" {
            val result = AssetColorAssigner.assignMissingColors(
                listOf(
                    Allocation(Asset.SOL, 50.0, "#fbbf24"),
                    Allocation(Asset.BTC, 50.0),
                ),
            )
            result[0].color shouldBe "#fbbf24"
            result[1].color.shouldNotBeNull()
            result[1].color shouldNotBe "#fbbf24"
        }

        "assignMissingColors is deterministic for the same symbol set" {
            val input = listOf(
                Allocation(Asset.SOL, 50.0),
                Allocation(Asset.DOGE, 50.0),
            )
            AssetColorAssigner.assignMissingColors(input) shouldBe
                AssetColorAssigner.assignMissingColors(input)
        }

        "assignMissingColors covers diverse symbols across all hue segments" {
            val symbols = listOf("ADA", "AVAX", "DOT", "LINK", "UNI", "MATIC", "XRP", "NEAR", "ATOM", "ALGO")
            val allocations = symbols.map { Allocation(it, 10.0) }
            val colored = AssetColorAssigner.assignMissingColors(allocations)
            colored.size shouldBe symbols.size
            colored.all { it.color != null } shouldBe true
        }
    }
}
