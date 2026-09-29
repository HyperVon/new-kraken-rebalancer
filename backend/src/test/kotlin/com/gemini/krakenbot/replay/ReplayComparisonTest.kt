package com.gemini.krakenbot.replay

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.model.Asset
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import java.math.BigDecimal

/**
 * Guards the replay harness itself: it must conserve capital, price a flat book exactly, and
 * apply the trend rule through the production engine rather than a reimplementation.
 */
class ReplayComparisonTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    private val allocations = listOf(
        Allocation(Asset.BTC, 50.0),
        Allocation(Asset.ETH, 30.0),
        Allocation(Asset.USD, 20.0),
    )
    private val comparator = ReplayComparator(
        allocations = allocations,
        settings = TestFixtures.settings(dryRun = true, deviationTriggerPercent = 5.0, minimumOrderSizeUSD = 5.0),
        feeRate = BigDecimal("0.0035"),
    )

    private fun flat(days: Int, price: String): Map<String, List<BigDecimal>> = mapOf(
        Asset.BTC to List(days) { BigDecimal(price) },
        Asset.ETH to List(days) { BigDecimal(price) },
    )

    init {
        "buy-and-hold on a flat path returns capital less its acquisition fees" {
            val days = 30
            val outcome = comparator.run(
                closes = flat(days, "100"),
                flows = listOf(
                    ReplayComparator.Flow(0, BigDecimal("1000")),
                    ReplayComparator.Flow(10, BigDecimal("500")),
                    ReplayComparator.Flow(20, BigDecimal("-300")),
                ),
                openingCapital = BigDecimal("2000"),
            )

            // 2000 + 1000 + 500 - 300 = 3200 contributed; the book pays fees to acquire the
            // basket, so terminal value plus those fees must return exactly the contribution.
            outcome.buyAndHoldNav.add(outcome.buyAndHoldFees)
                .shouldBeEqualComparingTo(BigDecimal("3200.00"))
            outcome.buyAndHoldNav.shouldBeLessThan(BigDecimal("3200.00"))
        }

        "a crypto-funded withdrawal removes the full requested amount across assets" {
            val outcome = comparator.run(
                closes = flat(10, "100"),
                flows = listOf(ReplayComparator.Flow(5, BigDecimal("-3000"))),
                openingCapital = BigDecimal("10000"),
                lastDay = 5,
            )

            outcome.buyAndHoldNav.add(outcome.buyAndHoldFees)
                .subtract(BigDecimal("7000.00")).abs()
                .shouldBeLessThan(BigDecimal("0.01"))
        }

        "both books conserve capital on a flat path" {
            val netCapital = BigDecimal("2500.00")
            val outcome = comparator.run(
                closes = flat(30, "100"),
                flows = listOf(ReplayComparator.Flow(5, BigDecimal("500"))),
                openingCapital = BigDecimal("2000"),
            )

            outcome.buyAndHoldNav.add(outcome.buyAndHoldFees)
                .shouldBeEqualComparingTo(netCapital)
            outcome.nav.add(outcome.fees).subtract(netCapital).abs()
                .shouldBeLessThan(BigDecimal("0.02"))
        }

        "terminal value is non-negative and fees are never negative" {
            val outcome = comparator.run(
                closes = mapOf(
                    Asset.BTC to List(40) { BigDecimal("100").add(BigDecimal(it)) },
                    Asset.ETH to List(40) { BigDecimal("200").subtract(BigDecimal(it)) },
                ),
                flows = listOf(ReplayComparator.Flow(15, BigDecimal("-400"))),
                openingCapital = BigDecimal("5000"),
            )

            (outcome.nav.signum() >= 0).shouldBeTrue()
            (outcome.fees.signum() >= 0).shouldBeTrue()
            outcome.nav.shouldBeGreaterThan(BigDecimal.ZERO)
        }

        "the trend rule suppresses sells while an asset is at its recent high" {
            val days = 40
            // BTC climbs every day, so it is permanently at its highest completed close.
            val rising = List(days) { BigDecimal("100").add(BigDecimal(it * 10)) }
            val drifting = List(days) { BigDecimal("200") }
            val outcome = comparator.run(
                closes = mapOf(Asset.BTC to rising, Asset.ETH to drifting),
                flows = emptyList(),
                openingCapital = BigDecimal("10000"),
                trendingSymbols = { day, prices ->
                    val closes = rising.subList(0, day + 1)
                    if (closes.isEmpty()) {
                        emptySet()
                    } else if (prices.getValue(Asset.BTC) >= closes.max()) {
                        setOf(Asset.BTC)
                    } else {
                        emptySet()
                    }
                },
            )

            outcome.suppressedSells.shouldBeGreaterThan(0)
        }

        "without the trend rule the same path trades more" {
            val days = 40
            val rising = List(days) { BigDecimal("100").add(BigDecimal(it * 10)) }
            val drifting = List(days) { BigDecimal("200") }
            val closes = mapOf(Asset.BTC to rising, Asset.ETH to drifting)

            val withRule = comparator.run(
                closes = closes,
                flows = emptyList(),
                openingCapital = BigDecimal("10000"),
                trendingSymbols = { day, prices ->
                    val seen = rising.subList(0, day + 1)
                    if (seen.isNotEmpty() && prices.getValue(Asset.BTC) >= seen.max()) setOf(Asset.BTC) else emptySet()
                },
            )
            val withoutRule = comparator.run(
                closes = closes,
                flows = emptyList(),
                openingCapital = BigDecimal("10000"),
            )

            withoutRule.suppressedSells shouldBe 0
            (withoutRule.tradeCount >= withRule.tradeCount).shouldBeTrue()
        }

        "lastDay truncates both rebalanced and buy-and-hold arms to the same terminal day" {
            val totalDays = 40
            val splitDay = 15
            // Prices flat at 100 for days 0..15, then spike to 500 for days 16..39.
            val btcPrices = List(totalDays) { if (it <= splitDay) BigDecimal("100") else BigDecimal("500") }
            val ethPrices = List(totalDays) { if (it <= splitDay) BigDecimal("100") else BigDecimal("500") }
            val outcome = comparator.run(
                closes = mapOf(Asset.BTC to btcPrices, Asset.ETH to ethPrices),
                flows = listOf(
                    ReplayComparator.Flow(5, BigDecimal("500")),
                    // Flow after splitDay must be ignored by both arms
                    ReplayComparator.Flow(25, BigDecimal("5000")),
                ),
                openingCapital = BigDecimal("2000"),
                lastDay = splitDay,
            )

            // At splitDay (day 15), capital contributed is 2000 + 500 = 2500, prices are 100.
            // Neither arm should see the day 25 flow ($5000) or the day 16..39 price spike (500).
            outcome.buyAndHoldNav.add(outcome.buyAndHoldFees)
                .shouldBeEqualComparingTo(BigDecimal("2500.00"))
            outcome.nav.add(outcome.fees).subtract(BigDecimal("2500.00")).abs()
                .shouldBeLessThan(BigDecimal("0.02"))

            // Compare against a run that naturally only had 16 days (0..splitDay)
            val baselineOutcome = comparator.run(
                closes = mapOf(
                    Asset.BTC to btcPrices.subList(0, splitDay + 1),
                    Asset.ETH to ethPrices.subList(0, splitDay + 1),
                ),
                flows = listOf(ReplayComparator.Flow(5, BigDecimal("500"))),
                openingCapital = BigDecimal("2000"),
            )
            outcome.buyAndHoldNav.shouldBeEqualComparingTo(baselineOutcome.buyAndHoldNav)
            outcome.buyAndHoldFees.shouldBeEqualComparingTo(baselineOutcome.buyAndHoldFees)
            outcome.nav.shouldBeEqualComparingTo(baselineOutcome.nav)
            outcome.fees.shouldBeEqualComparingTo(baselineOutcome.fees)
            outcome.tradeCount shouldBe baselineOutcome.tradeCount
            outcome.suppressedSells shouldBe baselineOutcome.suppressedSells
        }
    }
}
