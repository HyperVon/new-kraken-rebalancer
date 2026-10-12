package com.gemini.krakenbot.service

import com.gemini.krakenbot.TestFixtures
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant

class CurrentPortfolioStateTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "dashboard view retains only the latest 24 completed snapshots in publish order" {
            val state = CurrentPortfolioState()
            val start = Instant.parse("2026-10-11T12:00:00Z")

            repeat(CurrentPortfolioState.MAX_RECENT_SNAPSHOTS + 3) { index ->
                state.publish(
                    TestFixtures.emptySnapshot(
                        timestamp = start.plusSeconds(index.toLong()),
                        totalValueUSD = BigDecimal.valueOf(index.toLong()),
                    ),
                )
            }

            val view = state.readDashboardView()
            view.recentSnapshots shouldHaveSize CurrentPortfolioState.MAX_RECENT_SNAPSHOTS
            view.recentSnapshots.first().totalValueUSD shouldBe BigDecimal("3")
            view.latest shouldBe view.recentSnapshots.last()
            view.recentSnapshots.last().totalValueUSD shouldBe BigDecimal("26")
        }
    }
}
