package com.gemini.krakenbot.service.actual.benchmark

import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.service.actual.ActualAssetStatus
import com.gemini.krakenbot.service.actual.ActualObservation
import com.gemini.krakenbot.service.actual.ActualObservationStatus
import java.math.BigDecimal
import java.math.RoundingMode

object ProspectiveBenchmarkCalculator {
    fun calculatePoint(segment: BenchmarkSegment, observation: ActualObservation): BenchmarkComparisonPoint? {
        if (observation.status != ActualObservationStatus.COMPLETE) return null
        if (observation.accountIdentityDigest != segment.accountIdentityDigest) return null
        if (observation.scopeFingerprint != segment.scopeFingerprint) return null
        if (observation.observedAt.isBefore(segment.baselineAt)) return null
        val actualTotalUsd = observation.totalUsd ?: return null

        val assetsBySymbol = observation.assets.associateBy { it.symbol }
        var holdTotalUsd = BigDecimal.ZERO

        for ((symbol, initialQty) in segment.initialHoldings) {
            val isUsd = Asset.normalizeLedgerAsset(symbol).equals(Asset.USD, ignoreCase = true)
            val priceUsd = if (isUsd) {
                BigDecimal.ONE
            } else {
                val assetObs = assetsBySymbol[symbol] ?: return null
                if (assetObs.status != ActualAssetStatus.COMPLETE || assetObs.priceUsd == null ||
                    assetObs.priceUsd.signum() <= 0
                ) {
                    return null
                }
                assetObs.priceUsd
            }
            val assetValue = initialQty.multiply(priceUsd)
            holdTotalUsd = holdTotalUsd.add(assetValue)
        }

        val diffUsd = actualTotalUsd.subtract(holdTotalUsd)
        val diffPct = if (holdTotalUsd.signum() != 0) {
            diffUsd.divide(holdTotalUsd, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal(100))
                .setScale(2, RoundingMode.HALF_UP)
        } else {
            BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
        }

        return BenchmarkComparisonPoint(
            observationId = observation.observationId,
            observedAt = observation.observedAt,
            actualValueUsd = actualTotalUsd,
            holdValueUsd = holdTotalUsd,
            differenceUsd = diffUsd,
            differencePercent = diffPct,
        )
    }
}
