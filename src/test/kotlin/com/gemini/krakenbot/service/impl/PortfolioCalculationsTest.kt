package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.model.Asset
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import java.math.BigDecimal

class PortfolioCalculationsTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "should compute signed relative deviation as (current - target) / target" {
            // Current $120, target $100 → +20% overweight
            val deviationUSD = PortfolioCalculations.calculateDeviationUSD(
                currentValueUSD = BigDecimal("120.00"),
                targetValueUSD = BigDecimal("100.00"),
            )
            deviationUSD.shouldBeEqualComparingTo(BigDecimal("20.00"))

            val deviationPct = PortfolioCalculations.calculateDeviationPercent(
                deviationUSD = deviationUSD,
                targetValueUSD = BigDecimal("100.00"),
                currentValueUSD = BigDecimal("120.00"),
            )
            deviationPct.shouldBeEqualComparingTo(BigDecimal("20.0000"))
        }

        "should keep underweight deviations negative" {
            val deviationUSD = PortfolioCalculations.calculateDeviationUSD(
                currentValueUSD = BigDecimal("80.00"),
                targetValueUSD = BigDecimal("100.00"),
            )
            deviationUSD.shouldBeEqualComparingTo(BigDecimal("-20.00"))

            val deviationPct = PortfolioCalculations.calculateDeviationPercent(
                deviationUSD = deviationUSD,
                targetValueUSD = BigDecimal("100.00"),
                currentValueUSD = BigDecimal("80.00"),
            )
            deviationPct.shouldBeEqualComparingTo(BigDecimal("-20.0000"))
        }

        "should retain four decimal places for repeating percentage ratios" {
            PortfolioCalculations.calculateCurrentPercent(
                valueUSD = BigDecimal.ONE,
                totalPortfolioValueUSD = BigDecimal("3"),
            ).shouldBeEqualComparingTo(BigDecimal("33.3333"))

            PortfolioCalculations.calculateDeviationPercent(
                deviationUSD = BigDecimal.ONE,
                targetValueUSD = BigDecimal("3"),
                currentValueUSD = BigDecimal("4"),
            ).shouldBeEqualComparingTo(BigDecimal("33.3333"))
        }

        "should treat zero-target holdings as 100% deviation when value remains" {
            val deviationPct = PortfolioCalculations.calculateDeviationPercent(
                deviationUSD = BigDecimal("50.00"),
                targetValueUSD = BigDecimal.ZERO,
                currentValueUSD = BigDecimal("50.00"),
            )
            deviationPct.shouldBeEqualComparingTo(BigDecimal("100"))
        }

        // CQ-9-4: zero-target dust — 100% deviationPercent but below dust gate
        "should report 100 percent deviation but insignificant for zero-target dust holding" {
            val metrics = PortfolioCalculations.calculateAssetMetrics(
                symbol = Asset(Asset.BTC),
                baseTargetPercent = BigDecimal.ZERO,
                currentValueUSD = BigDecimal("0.50"),
                totalPortfolioValueUSD = BigDecimal("1000.00"),
                effectiveUsdTarget = BigDecimal("100.00"),
                cryptoScaleFactor = BigDecimal.ONE,
                minimumOrderSizeUSD = 1.0,
            )
            metrics.symbol shouldBe Asset(Asset.BTC)
            metrics.baseTargetPercent.shouldBeEqualComparingTo(BigDecimal.ZERO)
            metrics.currentPercent.shouldBeEqualComparingTo(BigDecimal("0.05"))
            metrics.targetValueUSD.shouldBeEqualComparingTo(BigDecimal.ZERO)
            metrics.deviationPercent.shouldBeEqualComparingTo(BigDecimal("100"))
            metrics.isSignificant.shouldBeFalse()
            metrics.deviationUSD.shouldBeEqualComparingTo(BigDecimal("0.50"))
        }

        "should return zero deviation percent for empty zero-target assets" {
            val deviationPct = PortfolioCalculations.calculateDeviationPercent(
                deviationUSD = BigDecimal.ZERO,
                targetValueUSD = BigDecimal.ZERO,
                currentValueUSD = BigDecimal.ZERO,
            )
            deviationPct.shouldBeEqualComparingTo(BigDecimal.ZERO)
        }

        "should return zero current percent when portfolio value is zero" {
            PortfolioCalculations
                .calculateCurrentPercent(BigDecimal("10.00"), BigDecimal.ZERO)
                .shouldBeEqualComparingTo(BigDecimal.ZERO)
        }

        "should scale crypto targets by cryptoScaleFactor and leave USD at effective target" {
            val usdTarget = PortfolioCalculations.calculateTargetPercent(
                symbol = Asset(Asset.USD),
                baseTargetPercent = BigDecimal("20.00"),
                effectiveUsdTarget = BigDecimal("10.00"),
                cryptoScaleFactor = BigDecimal("1.125"),
            )
            usdTarget.shouldBeEqualComparingTo(BigDecimal("10.00"))

            val btcTarget = PortfolioCalculations.calculateTargetPercent(
                symbol = Asset(Asset.BTC),
                baseTargetPercent = BigDecimal("40.00"),
                effectiveUsdTarget = BigDecimal("10.00"),
                cryptoScaleFactor = BigDecimal("1.125"),
            )
            btcTarget.shouldBeEqualComparingTo(BigDecimal("45.000"))
        }

        "should sum all USD target percentages in calculateUsdTargetPercent" {
            val allocations = listOf(
                Allocation("USD", 15.0),
                Allocation("ZUSD", 5.0),
                Allocation("BTC", 80.0),
            )
            PortfolioCalculations.calculateUsdTargetPercent(allocations)
                .shouldBeEqualComparingTo(BigDecimal("20.00"))
        }

        "should mark dust-sized deviations as insignificant" {
            val metrics = PortfolioCalculations.calculateAssetMetrics(
                symbol = Asset(Asset.BTC),
                baseTargetPercent = BigDecimal("50.00"),
                currentValueUSD = BigDecimal("500.40"),
                totalPortfolioValueUSD = BigDecimal("1000.00"),
                effectiveUsdTarget = BigDecimal("0"),
                cryptoScaleFactor = BigDecimal.ONE,
                minimumOrderSizeUSD = 1.0,
            )
            // Target $500, current $500.40 → $0.40 dust
            metrics.isSignificant.shouldBeFalse()
            metrics.deviationUSD.shouldBeEqualComparingTo(BigDecimal("0.40"))
        }

        "should mark deviation at exact minimum order size as significant" {
            val metrics = PortfolioCalculations.calculateAssetMetrics(
                symbol = Asset(Asset.BTC),
                baseTargetPercent = BigDecimal("50.00"),
                currentValueUSD = BigDecimal("501.00"),
                totalPortfolioValueUSD = BigDecimal("1000.00"),
                effectiveUsdTarget = BigDecimal("0"),
                cryptoScaleFactor = BigDecimal.ONE,
                minimumOrderSizeUSD = 1.0,
            )
            // Target $500, current $501.00 → |deviation| == $1.00 dust boundary
            metrics.isSignificant.shouldBeTrue()
            metrics.deviationUSD.shouldBeEqualComparingTo(BigDecimal("1.00"))
        }

        "should mark deviation just below minimum order size as insignificant" {
            val metrics = PortfolioCalculations.calculateAssetMetrics(
                symbol = Asset(Asset.BTC),
                baseTargetPercent = BigDecimal("50.00"),
                currentValueUSD = BigDecimal("500.99"),
                totalPortfolioValueUSD = BigDecimal("1000.00"),
                effectiveUsdTarget = BigDecimal("0"),
                cryptoScaleFactor = BigDecimal.ONE,
                minimumOrderSizeUSD = 1.0,
            )
            // Target $500, current $500.99 → |deviation| == $0.99 below dust boundary
            metrics.isSignificant.shouldBeFalse()
            metrics.deviationUSD.shouldBeEqualComparingTo(BigDecimal("0.99"))
        }

        "should mark significant deviations above the minimum order size" {
            val metrics = PortfolioCalculations.calculateAssetMetrics(
                symbol = Asset(Asset.BTC),
                baseTargetPercent = BigDecimal("50.00"),
                currentValueUSD = BigDecimal("600.00"),
                totalPortfolioValueUSD = BigDecimal("1000.00"),
                effectiveUsdTarget = BigDecimal("0"),
                cryptoScaleFactor = BigDecimal.ONE,
                minimumOrderSizeUSD = 1.0,
            )
            metrics.isSignificant.shouldBeTrue()
            metrics.deviationUSD.shouldBeEqualComparingTo(BigDecimal("100.00"))
            metrics.deviationPercent.shouldBeEqualComparingTo(BigDecimal("20.0000"))
        }

        // CQ-3-16: isSignificant uses abs(), so underweight |deviation| must match overweight boundaries.
        "should mark underweight deviation at exact minimum order size as significant" {
            val metrics = PortfolioCalculations.calculateAssetMetrics(
                symbol = Asset(Asset.BTC),
                baseTargetPercent = BigDecimal("50.00"),
                currentValueUSD = BigDecimal("499.00"),
                totalPortfolioValueUSD = BigDecimal("1000.00"),
                effectiveUsdTarget = BigDecimal("0"),
                cryptoScaleFactor = BigDecimal.ONE,
                minimumOrderSizeUSD = 1.0,
            )
            // Target $500, current $499.00 → |deviation| == $1.00 dust boundary
            metrics.isSignificant.shouldBeTrue()
            metrics.deviationUSD.shouldBeEqualComparingTo(BigDecimal("-1.00"))
        }

        "should mark underweight deviation just below minimum order size as insignificant" {
            val metrics = PortfolioCalculations.calculateAssetMetrics(
                symbol = Asset(Asset.BTC),
                baseTargetPercent = BigDecimal("50.00"),
                currentValueUSD = BigDecimal("499.01"),
                totalPortfolioValueUSD = BigDecimal("1000.00"),
                effectiveUsdTarget = BigDecimal("0"),
                cryptoScaleFactor = BigDecimal.ONE,
                minimumOrderSizeUSD = 1.0,
            )
            // Target $500, current $499.01 → |deviation| == $0.99 below dust boundary
            metrics.isSignificant.shouldBeFalse()
            metrics.deviationUSD.shouldBeEqualComparingTo(BigDecimal("-0.99"))
        }

        "should apply scale 8/2 when creating asset snapshots" {
            val snapshot = PortfolioCalculations.createAssetSnapshot(
                symbol = Asset.BTC,
                balance = BigDecimal("0.123456789"),
                price = BigDecimal("50000.123456789"),
                valueUSD = BigDecimal("6172.839506"),
                targetPercent = BigDecimal("50.123"),
                totalPortfolioValueUSD = BigDecimal("10000.00"),
            )

            snapshot.balance.shouldBeEqualComparingTo(BigDecimal("0.12345679"))
            snapshot.price.shouldBeEqualComparingTo(BigDecimal("50000.12345679"))
            snapshot.valueUSD.shouldBeEqualComparingTo(BigDecimal("6172.84"))
            snapshot.targetPercent.shouldBeEqualComparingTo(BigDecimal("50.12"))
        }

        "should fall back to default USD target when no allocations present" {
            PortfolioCalculations.calculateUsdTargetPercent(emptyList())
                .shouldBeEqualComparingTo(BigDecimal("5.00"))
        }

        "should fall back to default USD target when allocations contain no USD" {
            PortfolioCalculations.calculateUsdTargetPercent(listOf(Allocation("BTC", 80.0)))
                .shouldBeEqualComparingTo(BigDecimal("5.00"))
        }
    }
}
