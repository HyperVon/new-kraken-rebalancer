package com.gemini.krakenbot.view.component

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.model.PortfolioSnapshot
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.html.div
import kotlinx.html.stream.createHTML
import java.math.BigDecimal
import java.time.Instant

class OverviewGridComponentTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "dashboard hero chart starts after the second in-session snapshot" {
            val first = TestFixtures.emptySnapshot(Instant.parse("2026-10-11T12:00:00Z"), BigDecimal("100.00"))
            val second = TestFixtures.emptySnapshot(Instant.parse("2026-10-11T12:01:00Z"), BigDecimal("101.00"))

            val oneSample = createHTML().div { OverviewGridComponent().render(first) }
            val twoSamples = createHTML().div { OverviewGridComponent().render(second, listOf(first, second)) }

            oneSample shouldContain "Trend appears after the next completed cycle."
            oneSample shouldNotContain "hero-trend-svg"
            twoSamples shouldContain "hero-trend-svg"
            twoSamples shouldContain "2 completed cycles"
            twoSamples shouldContain "<polyline"
            twoSamples shouldContain "<polygon"
            twoSamples shouldContain "hero-trend-fill"
            twoSamples shouldContain "hero-trend-delta positive"
            twoSamples shouldContain "+1%"
            twoSamples shouldContain "over shown cycles"
            twoSamples shouldNotContain "this run"
            twoSamples shouldNotContain "aria-label"
        }

        "dashboard hero trend delta chip signs up and down windows" {
            val first = TestFixtures.emptySnapshot(Instant.parse("2026-10-11T12:00:00Z"), BigDecimal("101.00"))
            val lowerLast = TestFixtures.emptySnapshot(Instant.parse("2026-10-11T12:01:00Z"), BigDecimal("100.00"))
            val higherLast = TestFixtures.emptySnapshot(Instant.parse("2026-10-11T12:01:00Z"), BigDecimal("102.00"))

            val downHtml = createHTML().div { OverviewGridComponent().render(lowerLast, listOf(first, lowerLast)) }
            val upHtml = createHTML().div { OverviewGridComponent().render(higherLast, listOf(first, higherLast)) }

            downHtml shouldContain "hero-trend-delta negative"
            downHtml shouldContain "-0.99%"
            upHtml shouldContain "hero-trend-delta positive"
            upHtml shouldContain "+0.99%"
        }

        "cash tile shows the effective target and base allocation when USD data is available" {
            val snapshot = PortfolioSnapshot(
                timestamp = Instant.parse("2026-10-11T12:00:00Z"),
                totalValueUSD = BigDecimal("100.00"),
                assets = mapOf(
                    "USD" to TestFixtures.assetSnapshot(
                        symbol = "USD",
                        balance = BigDecimal("20.00"),
                        price = BigDecimal.ONE,
                        valueUSD = BigDecimal("20.00"),
                        targetPercent = BigDecimal("10.00"),
                        currentPercent = BigDecimal("20.00"),
                        deviationPercent = BigDecimal("10.00"),
                        deviationUSD = BigDecimal("10.00"),
                    ),
                ),
                actions = emptyList(),
                effectiveUsdTargetPercent = BigDecimal("20.00"),
            )

            val html = createHTML().div { OverviewGridComponent().render(snapshot) }

            html shouldContain "Target: 20% (Base: 10%)"
            html shouldContain "Dev: +10%"
        }
    }
}
