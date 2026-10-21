package com.gemini.krakenbot.service

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.service.history.HistoryCycleOutcome
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
                    sourceScope = "live:credential-generation-a",
                )
            }

            val view = state.readDashboardView()
            view.recentSnapshots shouldHaveSize CurrentPortfolioState.MAX_RECENT_SNAPSHOTS
            view.recentSnapshots.first().totalValueUSD shouldBe BigDecimal("3")
            view.latest shouldBe view.recentSnapshots.last()
            view.recentSnapshots.last().totalValueUSD shouldBe BigDecimal("26")
        }

        "dashboard trend clears when the portfolio source changes" {
            val state = CurrentPortfolioState()
            val start = Instant.parse("2026-10-11T12:00:00Z")

            state.publish(
                TestFixtures.emptySnapshot(start, BigDecimal("100")),
                sourceScope = "live:credential-generation-a",
            )
            state.publish(
                TestFixtures.emptySnapshot(start.plusSeconds(1), BigDecimal("110")),
                sourceScope = "live:credential-generation-a",
            )
            state.publish(
                TestFixtures.emptySnapshot(start.plusSeconds(2), BigDecimal("115")),
                sourceScope = "live:credential-generation-b",
            )

            state.readDashboardView().recentSnapshots.map { it.totalValueUSD } shouldBe listOf(BigDecimal("115"))

            state.publish(
                TestFixtures.emptySnapshot(start.plusSeconds(3), BigDecimal("20")),
                sourceScope = "simulation:simulated-backend",
            )

            val view = state.readDashboardView()
            view.recentSnapshots.map { it.totalValueUSD } shouldBe listOf(BigDecimal("20"))
            view.latest shouldBe view.recentSnapshots.single()
        }

        "activity keeps an independent 120-cycle ring and includes failed cycles without snapshots" {
            val state = CurrentPortfolioState()
            val start = Instant.parse("2026-10-11T12:00:00Z")

            repeat(CurrentPortfolioState.MAX_RECENT_ACTIVITIES + 10) { index ->
                state.publish(
                    TestFixtures.emptySnapshot(start.plusSeconds(index.toLong()), BigDecimal("100")),
                    sourceScope = "live:credential-generation-a",
                    activity = CurrentCycleActivity(
                        cycleId = "cycle-$index",
                        occurredAt = start.plusSeconds(index.toLong()),
                        mode = CurrentCycleActivityMode.LIVE,
                        outcome = HistoryCycleOutcome.NO_TRADE,
                        actions = listOf("check $index"),
                    ),
                )
            }

            val view = state.readDashboardView()
            view.recentSnapshots shouldHaveSize CurrentPortfolioState.MAX_RECENT_SNAPSHOTS
            view.recentActivities shouldHaveSize CurrentPortfolioState.MAX_RECENT_ACTIVITIES
            view.recentActivities.first().cycleId shouldBe "cycle-129"
            view.recentActivities.last().cycleId shouldBe "cycle-10"

            val failed = CurrentCycleActivity(
                cycleId = "failed-cycle",
                occurredAt = start.plusSeconds(200),
                mode = CurrentCycleActivityMode.LIVE,
                outcome = HistoryCycleOutcome.PROBLEM,
                actions = listOf("ERROR: cycle failed"),
            )
            state.publishActivity(failed, "live:credential-generation-a")
            state.readDashboardView().recentActivities.first() shouldBe failed
            state.readDashboardView().latest shouldBe view.latest
            state.activityUpdates.value shouldBe failed
        }

        "activity ring clears on source changes and replaces duplicate cycle identities" {
            val state = CurrentPortfolioState()
            val timestamp = Instant.parse("2026-10-11T12:00:00Z")
            val original = CurrentCycleActivity(
                cycleId = "same-cycle",
                occurredAt = timestamp,
                mode = CurrentCycleActivityMode.DRY_RUN,
                outcome = HistoryCycleOutcome.DRY_RUN_PLAN,
                actions = listOf("[DRY RUN] BUY BTC"),
            )
            val updated = original.copy(outcome = HistoryCycleOutcome.PROBLEM, actions = listOf("ERROR: submit failed"))

            state.publishActivity(original, "live:credential-generation-a")
            state.publishActivity(updated, "live:credential-generation-a")
            state.readDashboardView().recentActivities shouldBe listOf(updated)

            state.publishActivity(
                original.copy(cycleId = "account-b-cycle"),
                "live:credential-generation-b",
            )
            state.readDashboardView().recentActivities.map { it.cycleId } shouldBe listOf("account-b-cycle")
            state.readDashboardView().latest shouldBe null
        }
    }
}
