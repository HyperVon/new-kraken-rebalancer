package com.gemini.krakenbot.model

import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.util.KrakenSymbols
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant

class ModelTest : StringSpec({
    "testPortfolioSnapshot" {
        val asset = PortfolioSnapshot.AssetSnapshot(
            symbol = KrakenSymbols.BTC,
            balance = BigDecimal.ONE,
            price = BigDecimal.TEN,
            valueUSD = BigDecimal.TEN,
            targetPercent = BigDecimal.ONE,
            currentPercent = BigDecimal.ONE,
            deviationPercent = BigDecimal.ZERO,
            deviationUSD = BigDecimal.ZERO
        )
        val asset2 = asset.copy()
        asset2 shouldBe asset
        asset.hashCode() shouldBe asset2.hashCode()
        asset.toString().shouldNotBeNull()
        asset.symbol.value shouldBe KrakenSymbols.BTC

        val snapshot = PortfolioSnapshot(
            timestamp = Instant.EPOCH,
            totalValueUSD = BigDecimal.TEN,
            assets = mapOf(KrakenSymbols.BTC to asset),
            actions = listOf("BUY"),
            drawdownPercent = BigDecimal.ZERO,
            fiatDeploymentPercent = BigDecimal.ZERO,
            effectiveUsdTargetPercent = BigDecimal.ZERO
        )
        val snapshot2 = snapshot.copy()
        snapshot2 shouldBe snapshot
        snapshot.hashCode() shouldBe snapshot2.hashCode()
        snapshot.toString().shouldNotBeNull()
    }

    "testSettings" {
        val settings = Settings(
            60,
            2.0,
            1.0,
            true,
            50.0,
            1.0
        )
        val settings5 = settings.copy()
        settings5 shouldBe settings
        settings.hashCode() shouldBe settings5.hashCode()
        settings.toString().shouldNotBeNull()
    }

    "testPortfolioStats" {
        val stats = PortfolioStats(allTimeHigh = BigDecimal.TEN)
        val stats2 = stats.copy()
        stats2 shouldBe stats
        stats.hashCode() shouldBe stats2.hashCode()
        stats.toString().shouldNotBeNull()
    }
})
