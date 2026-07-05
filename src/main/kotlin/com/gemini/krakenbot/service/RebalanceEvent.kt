package com.gemini.krakenbot.service

import com.gemini.krakenbot.model.PortfolioSnapshot
import java.time.Instant
import java.time.Duration

/**
 * Events emitted by the portfolio rebalancing loop.
 * Enables event-driven monitoring and metrics collection.
 */
sealed class RebalanceEvent {
    abstract val timestamp: Instant
}

/**
 * Emitted when a rebalancing cycle starts.
 */
data class RebalanceCycleStarted(override val timestamp: Instant = Instant.now()) : RebalanceEvent()

data class RebalanceCycleCompleted(
    val snapshot: PortfolioSnapshot?,
    val duration: Duration,
    override val timestamp: Instant = Instant.now()
) : RebalanceEvent()

/**
 * Emitted when a rebalancing cycle encounters an error.
 */
data class RebalanceCycleError(
    val error: Throwable,
    override val timestamp: Instant = Instant.now()
) : RebalanceEvent()

/**
 * Emitted when an individual order is executed (successfully or failed).
 */
data class OrderExecuted(
    val result: com.gemini.krakenbot.model.OrderResult,
    override val timestamp: Instant = Instant.now()
) : RebalanceEvent()

