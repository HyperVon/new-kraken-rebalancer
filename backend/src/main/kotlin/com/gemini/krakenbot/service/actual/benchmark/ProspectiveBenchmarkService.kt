package com.gemini.krakenbot.service.actual.benchmark

import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.service.actual.ActualObservation
import com.gemini.krakenbot.service.actual.ActualObservationStatus
import com.gemini.krakenbot.service.actual.ActualObservationValuator
import com.gemini.krakenbot.view.util.ViewText
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class ProspectiveBenchmarkService(
    private val benchmarkStore: BenchmarkStore,
    private val continuityVerifier: ProspectiveEventContinuityVerifier,
    private val nowProvider: () -> Instant = Instant::now,
) {
    suspend fun startBenchmark(observation: ActualObservation): BenchmarkSegment {
        require(observation.status == ActualObservationStatus.COMPLETE) {
            "A prospective benchmark baseline requires a complete Actual observation."
        }
        val initialHoldings = observation.assets.associate { asset ->
            asset.symbol to checkNotNull(asset.quantity) { "Asset ${asset.symbol} missing quantity" }
        }
        val baselineMarks = observation.assets.associate { asset ->
            asset.symbol to checkNotNull(if (asset.symbol == "USD") BigDecimal.ONE else asset.priceUsd) {
                "Asset ${asset.symbol} missing USD mark"
            }
        }
        val segment = BenchmarkSegment(
            segmentId = UUID.randomUUID().toString(),
            baselineObservationId = observation.observationId,
            accountIdentityDigest = observation.accountIdentityDigest,
            scopeFingerprint = observation.scopeFingerprint,
            scopeSymbols = observation.scopeSymbols,
            baselineAt = observation.observedAt,
            initialHoldings = initialHoldings,
            baselineMarks = baselineMarks,
            baselineTotalUsd = checkNotNull(observation.totalUsd) { "Observation missing total USD" },
            status = BenchmarkSegmentStatus.TRACKING,
            terminationReason = null,
            lastVerifiedEventTime = observation.observedAt,
            createdAt = nowProvider(),
        )
        benchmarkStore.saveSegment(segment)
        return segment
    }

    suspend fun evaluate(
        config: AppConfig,
        accountIdentityDigest: String,
        observations: List<ActualObservation>,
    ): BenchmarkComparisonResult {
        val scopeSymbols = ActualObservationValuator.scopeSymbols(config)
        val scopeFingerprint = ActualObservationValuator.scopeFingerprint(scopeSymbols)

        val active = benchmarkStore.getActiveSegment(accountIdentityDigest)
        val segment = active ?: benchmarkStore.getLatestSegment(accountIdentityDigest)

        if (segment == null) {
            return BenchmarkComparisonResult(
                status = BenchmarkStatus.NO_ACTIVE_SEGMENT,
                segment = null,
                points = emptyList(),
                latestPoint = null,
                unavailableReason = ViewText.ACTUAL_BENCHMARK_STATUS_NOT_STARTED,
            )
        }

        if (segment.accountIdentityDigest != accountIdentityDigest) {
            return BenchmarkComparisonResult(
                status = BenchmarkStatus.TERMINATED,
                segment = segment,
                points = emptyList(),
                latestPoint = null,
                unavailableReason = BenchmarkTerminationReason.ACCOUNT_CHANGED,
            )
        }

        if (segment.scopeFingerprint != scopeFingerprint) {
            if (segment.status == BenchmarkSegmentStatus.TRACKING) {
                benchmarkStore.updateSegmentStatus(
                    segment.segmentId,
                    BenchmarkSegmentStatus.TERMINATED,
                    BenchmarkTerminationReason.SCOPE_CHANGED,
                    segment.lastVerifiedEventTime,
                )
            }
            return BenchmarkComparisonResult(
                status = BenchmarkStatus.TERMINATED,
                segment = segment.copy(
                    status = BenchmarkSegmentStatus.TERMINATED,
                    terminationReason = BenchmarkTerminationReason.SCOPE_CHANGED,
                ),
                points = emptyList(),
                latestPoint = null,
                unavailableReason = BenchmarkTerminationReason.SCOPE_CHANGED,
            )
        }

        val eligibleObservations = observations.filter { !it.observedAt.isBefore(segment.baselineAt) }
            .sortedBy { it.observedAt }

        if (segment.status == BenchmarkSegmentStatus.TERMINATED) {
            val points = eligibleObservations
                .filter { !it.observedAt.isAfter(segment.lastVerifiedEventTime) }
                .mapNotNull { ProspectiveBenchmarkCalculator.calculatePoint(segment, it) }
            return BenchmarkComparisonResult(
                status = BenchmarkStatus.TERMINATED,
                segment = segment,
                points = points,
                latestPoint = points.lastOrNull(),
                unavailableReason = segment.terminationReason,
            )
        }

        val startingSegment: BenchmarkSegment = segment
        var currentSegment: BenchmarkSegment = startingSegment
        val points = mutableListOf<BenchmarkComparisonPoint>()
        var pendingReason: String? = null

        for (obs in eligibleObservations) {
            if (obs.status != ActualObservationStatus.COMPLETE) continue

            val verification = if (!obs.observedAt.isAfter(currentSegment.lastVerifiedEventTime)) {
                ContinuityVerificationResult.Continuous(currentSegment.lastVerifiedEventTime)
            } else {
                continuityVerifier.verifyContinuity(currentSegment, obs.observedAt)
            }
            when (verification) {
                is ContinuityVerificationResult.Continuous -> {
                    benchmarkStore.updateVerifiedTime(currentSegment.segmentId, verification.updatedVerifiedTime)
                    currentSegment = currentSegment.copy(lastVerifiedEventTime = verification.updatedVerifiedTime)
                    val pt = ProspectiveBenchmarkCalculator.calculatePoint(currentSegment, obs)
                    if (pt != null) points += pt
                }

                is ContinuityVerificationResult.Terminated -> {
                    benchmarkStore.updateSegmentStatus(
                        currentSegment.segmentId,
                        BenchmarkSegmentStatus.TERMINATED,
                        verification.reason,
                        verification.eventTime,
                    )
                    currentSegment = currentSegment.copy(
                        status = BenchmarkSegmentStatus.TERMINATED,
                        terminationReason = verification.reason,
                        lastVerifiedEventTime = verification.eventTime,
                    )
                    break
                }

                is ContinuityVerificationResult.PendingEvidence -> {
                    pendingReason = verification.message
                    break
                }
            }
        }

        val finalStatus = when {
            currentSegment.status == BenchmarkSegmentStatus.TERMINATED -> BenchmarkStatus.TERMINATED
            pendingReason != null -> BenchmarkStatus.PENDING_EVIDENCE
            points.isEmpty() -> BenchmarkStatus.UNAVAILABLE
            else -> BenchmarkStatus.READY
        }

        return BenchmarkComparisonResult(
            status = finalStatus,
            segment = currentSegment,
            points = points,
            latestPoint = points.lastOrNull(),
            unavailableReason = currentSegment.terminationReason ?: pendingReason,
        )
    }
}
