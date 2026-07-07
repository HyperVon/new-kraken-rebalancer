package com.gemini.krakenbot.service

import com.gemini.krakenbot.model.OrderResult
import com.gemini.krakenbot.model.PortfolioSnapshot
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

@Suppress("unused")
class RebalanceEventTest : StringSpec({
    "RebalanceCycleStarted carries timestamp" {
        val event = RebalanceCycleStarted()
        event.shouldBeInstanceOf<RebalanceEvent>()
        event.timestamp.shouldBeInstanceOf<Instant>()
    }

    "RebalanceCycleCompleted carries snapshot and duration" {
        val snapshot = PortfolioSnapshot(
            timestamp = Instant.now(),
            totalValueUSD = BigDecimal("1000.00"),
            assets = emptyMap(),
            actions = emptyList(),
            drawdownPercent = BigDecimal.ZERO,
            fiatDeploymentPercent = BigDecimal.ZERO,
            effectiveUsdTargetPercent = BigDecimal.ZERO
        )
        val event = RebalanceCycleCompleted(snapshot, Duration.ofSeconds(5))
        event.snapshot shouldBe snapshot
        event.duration shouldBe Duration.ofSeconds(5)
    }

    "RebalanceCycleError carries error" {
        val error = IllegalStateException("boom")
        val event = RebalanceCycleError(error)
        event.error shouldBe error
    }

    "OrderExecuted carries order result" {
        val result = OrderResult(
            success = true,
            pair = "BTCUSD",
            side = "buy",
            volume = BigDecimal("0.1"),
            dryRun = false
        )
        val event = OrderExecuted(result)
        event.result shouldBe result
    }
})
