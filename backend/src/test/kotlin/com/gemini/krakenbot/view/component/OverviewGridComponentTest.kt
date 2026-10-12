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
            twoSamples shouldContain "text-anchor=\"start\""
            twoSamples shouldContain "text-anchor=\"end\""
            twoSamples shouldNotContain "aria-label"
        }

        "dashboard hero chart formats thousand and million axis values" {
            val thousandHtml =
                createHTML().div {
                    OverviewGridComponent().render(
                        TestFixtures.emptySnapshot(Instant.parse("2026-10-11T12:01:00Z"), BigDecimal("2000.00")),
                        listOf(
                            TestFixtures.emptySnapshot(
                                Instant.parse("2026-10-11T12:00:00Z"),
                                BigDecimal("1000.00"),
                            ),
                            TestFixtures.emptySnapshot(
                                Instant.parse("2026-10-11T12:01:00Z"),
                                BigDecimal("2000.00"),
                            ),
                        ),
                    )
                }
            val millionHtml =
                createHTML().div {
                    OverviewGridComponent().render(
                        TestFixtures.emptySnapshot(
                            Instant.parse("2026-10-11T12:01:00Z"),
                            BigDecimal("2000000.00"),
                        ),
                        listOf(
                            TestFixtures.emptySnapshot(
                                Instant.parse("2026-10-11T12:00:00Z"),
                                BigDecimal("1000000.00"),
                            ),
                            TestFixtures.emptySnapshot(
                                Instant.parse("2026-10-11T12:01:00Z"),
                                BigDecimal("2000000.00"),
                            ),
                        ),
                    )
                }

            thousandHtml shouldContain "$2.1k"
            thousandHtml shouldContain "$1.5k"
            millionHtml shouldContain "$2.1m"
            millionHtml shouldContain "$1.5m"
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
