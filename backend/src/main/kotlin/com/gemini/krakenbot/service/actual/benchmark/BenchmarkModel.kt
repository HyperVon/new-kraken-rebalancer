package com.gemini.krakenbot.service.actual.benchmark

import java.math.BigDecimal
import java.time.Instant

enum class BenchmarkSegmentStatus {
    TRACKING,
    TERMINATED,
    INVALID,
}

enum class BenchmarkStatus {
    READY,
    NO_ACTIVE_SEGMENT,
    TERMINATED,
    PENDING_EVIDENCE,
    UNAVAILABLE,
}

object BenchmarkTerminationReason {
    const val EXTERNAL_FUNDING = "External deposit or withdrawal detected"
    const val EXTERNAL_TRANSFER = "Wallet transfer detected"
    const val REWARD_OR_EARN = "Staking or Earn activity detected"
    const val ASSET_CONVERSION = "Asset conversion or non-rebalancer trade detected"
    const val MANUAL_TRADE = "Manual or out-of-scope trade detected"
    const val UNCLASSIFIED_EVENT = "Unsupported ledger event detected"
    const val SCOPE_CHANGED = "Managed asset universe or configuration changed"
    const val ACCOUNT_CHANGED = "Bound Kraken account changed"
    const val EVENT_GAP = "Event continuity gap or incomplete evidence"
}

data class BenchmarkSegment(
    val segmentId: String,
    val baselineObservationId: String,
    val accountIdentityDigest: String,
    val scopeFingerprint: String,
    val scopeSymbols: List<String>,
    val baselineAt: Instant,
    val initialHoldings: Map<String, BigDecimal>,
    val baselineMarks: Map<String, BigDecimal>,
    val baselineTotalUsd: BigDecimal,
    val status: BenchmarkSegmentStatus,
    val terminationReason: String? = null,
    val lastVerifiedEventTime: Instant,
    val createdAt: Instant,
)

data class BenchmarkComparisonPoint(
    val observationId: String,
    val observedAt: Instant,
    val actualValueUsd: BigDecimal,
    val holdValueUsd: BigDecimal,
    val differenceUsd: BigDecimal,
    val differencePercent: BigDecimal,
)

data class BenchmarkComparisonResult(
    val status: BenchmarkStatus,
    val segment: BenchmarkSegment?,
    val points: List<BenchmarkComparisonPoint>,
    val latestPoint: BenchmarkComparisonPoint?,
    val unavailableReason: String?,
)
