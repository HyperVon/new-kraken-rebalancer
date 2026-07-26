package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.model.Asset
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class HelperTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "getUniqueSymbols excludes and includes USD correctly" {
            val snapshots =
                listOf(
                    mockSnapshotRecord(
                        assets =
                        mapOf(
                            Asset.BTC to mockSnapshotRecord().assets.getValue(Asset.BTC),
                            Asset.ETH to mockSnapshotRecord().assets.getValue(Asset.BTC).copy(symbol = Asset.ETH),
                            Asset.USD to mockSnapshotRecord().assets.getValue(Asset.BTC).copy(symbol = Asset.USD),
                        ),
                    ),
                    mockSnapshotRecord(assets = emptyMap()),
                    mockSnapshotRecord(assets = emptyMap()),
                )
            val symbolsExcludeUsd = getUniqueSymbols(snapshots, excludeUsd = true)
            symbolsExcludeUsd shouldBe listOf(Asset.BTC, Asset.ETH)

            val symbolsIncludeUsd = getUniqueSymbols(snapshots, excludeUsd = false)
            symbolsIncludeUsd shouldBe listOf(Asset.BTC, Asset.ETH, Asset.USD)
        }

        "getUniqueSymbols returns empty list when no assets" {
            val snapshots =
                listOf(
                    mockSnapshotRecord(assets = emptyMap()),
                    mockSnapshotRecord(assets = emptyMap()),
                )
            getUniqueSymbols(snapshots, excludeUsd = true) shouldBe emptyList()
        }
    }
}
