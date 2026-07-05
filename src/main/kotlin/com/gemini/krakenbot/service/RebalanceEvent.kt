package com.gemini.krakenbot.service

import kotlinx.coroutines.flow.Flow
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

/**
 * Emitted when a rebalancing cycle completes successfully.
 */
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
 * Portfolio snapshot at a point in time.
 */
data class PortfolioSnapshot(
    val totalValueUSD: String,
    val timestamp: Instant = Instant.now()
)
