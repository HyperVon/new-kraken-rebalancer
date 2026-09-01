package com.gemini.krakenbot.repository.table

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.DatabaseConfig
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.PortfolioSnapshot
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import java.time.Instant

@Suppress("unused")
class PortfolioSnapshotTableTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "applyTo and toModel round-trip snapshot, asset snapshot, and action log fields" {
            val db = DatabaseConfig.init(TestFixtures.MEMORY_)
            val assetSnapshot = PortfolioSnapshot.AssetSnapshot(
                symbol = Asset("BTC"),
                balance = BigDecimal("1.25000000"),
                price = BigDecimal("65000.00"),
                valueUSD = BigDecimal("81250.00"),
                targetPercent = BigDecimal("40.00"),
                currentPercent = BigDecimal("42.50"),
                deviationPercent = BigDecimal("2.50"),
                deviationUSD = BigDecimal("4750.00"),
            )
            val original = PortfolioSnapshot(
                timestamp = Instant.parse("2026-07-03T12:00:00Z"),
                totalValueUSD = BigDecimal("190000.00"),
                assets = mapOf("BTC" to assetSnapshot),
                actions = listOf("Action 1: rebalanced BTC", "Action 2: settled USD"),
                drawdownPercent = BigDecimal("5.2500"),
                fiatDeploymentPercent = BigDecimal("12.5000"),
                effectiveUsdTargetPercent = BigDecimal("15.0000"),
            )

            transaction(db) {
                val snapshotId = PortfolioSnapshotTable.insert {
                    PortfolioSnapshotTable.applyTo(it, original)
                }[PortfolioSnapshotTable.id]

                for ((_, asset) in original.assets) {
                    AssetSnapshotTable.insert {
                        AssetSnapshotTable.applyTo(it, snapshotId, asset)
                    }
                }

                for (action in original.actions) {
                    ActionLogTable.insert {
                        ActionLogTable.applyTo(it, snapshotId, action)
                    }
                }

                val snapshotRow = PortfolioSnapshotTable.selectAll().single()
                val assetRows = AssetSnapshotTable.selectAll().toList()
                val actionRows = ActionLogTable.selectAll().toList()

                val loadedAssets = assetRows.associate(AssetSnapshotTable::toModel)
                val loadedActions = actionRows.map { it[ActionLogTable.message] }
                val loaded = PortfolioSnapshotTable.toModel(snapshotRow, loadedAssets, loadedActions)

                loaded.timestamp shouldBe original.timestamp
                loaded.totalValueUSD.shouldBeEqualComparingTo(original.totalValueUSD)
                loaded.drawdownPercent.shouldBeEqualComparingTo(original.drawdownPercent)
                loaded.fiatDeploymentPercent.shouldBeEqualComparingTo(original.fiatDeploymentPercent)
                loaded.effectiveUsdTargetPercent.shouldBeEqualComparingTo(original.effectiveUsdTargetPercent)
                loaded.balancesObservedAt shouldBe original.balancesObservedAt
                loaded.actions shouldBe original.actions

                val loadedAsset = requireNotNull(loaded.assets["BTC"])
                loadedAsset.symbol shouldBe assetSnapshot.symbol
                loadedAsset.balance.shouldBeEqualComparingTo(assetSnapshot.balance)
                loadedAsset.price.shouldBeEqualComparingTo(assetSnapshot.price)
                loadedAsset.valueUSD.shouldBeEqualComparingTo(assetSnapshot.valueUSD)
                loadedAsset.targetPercent.shouldBeEqualComparingTo(assetSnapshot.targetPercent)
                loadedAsset.currentPercent.shouldBeEqualComparingTo(assetSnapshot.currentPercent)
                loadedAsset.deviationPercent.shouldBeEqualComparingTo(assetSnapshot.deviationPercent)
                loadedAsset.deviationUSD.shouldBeEqualComparingTo(assetSnapshot.deviationUSD)
            }
        }

        "toModel falls back to timestamp when balances_observed_at is null or zero" {
            val db = DatabaseConfig.init(TestFixtures.MEMORY_)
            val snapshotTime = Instant.parse("2026-07-03T12:00:00Z")

            transaction(db) {
                PortfolioSnapshotTable.insert {
                    it[timestamp] = snapshotTime.toEpochMilli()
                    it[totalValueUSD] = BigDecimal("1000.00")
                    it[drawdownPercent] = BigDecimal.ZERO
                    it[fiatDeploymentPercent] = BigDecimal.ZERO
                    it[effectiveUsdTargetPercent] = BigDecimal.ZERO
                    it[balancesObservedAt] = null
                }

                val row = PortfolioSnapshotTable.selectAll().single()
                val model = PortfolioSnapshotTable.toModel(row, emptyMap(), emptyList())
                model.timestamp shouldBe snapshotTime
                model.balancesObservedAt shouldBe snapshotTime
            }
        }
    }
}
