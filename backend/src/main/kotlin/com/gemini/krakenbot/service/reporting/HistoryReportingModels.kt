package com.gemini.krakenbot.service.reporting

import com.gemini.krakenbot.service.history.HistorySourceScope
import java.math.BigDecimal
import java.time.Instant

data class HistoryReportingPeriod(
    val sourceScope: HistorySourceScope,
    val from: Instant? = null,
    val until: Instant? = null,
) {
    init {
        require(from == null || until == null || from <= until) { "Reporting period start must not follow its end." }
    }

    fun contains(instant: Instant): Boolean = (from == null || instant >= from) && (until == null || instant < until)

    fun isOutside(instant: Instant): Boolean = !contains(instant)
}

data class LatestObservedValue(
    val valueUsd: BigDecimal?,
    val observedAt: Instant?,
    val outsideSelectedPeriod: Boolean,
    val complete: Boolean,
    val incompleteReason: String?,
)

data class ObservedValueChange(
    val changeUsd: BigDecimal?,
    val firstValueUsd: BigDecimal?,
    val firstObservedAt: Instant?,
    val lastValueUsd: BigDecimal?,
    val lastObservedAt: Instant?,
    val includesFlowsNotice: String,
    val complete: Boolean,
    val incompleteReason: String?,
)

data class RangeExtremum(val label: String, val valueUsd: BigDecimal, val observedAt: Instant)

data class RangeExtrema(
    val high: RangeExtremum?,
    val low: RangeExtremum?,
    val complete: Boolean,
    val incompleteReason: String?,
)

data class TradeStatistics(
    val tradeCount: Long?,
    val volumeByCurrency: Map<String, BigDecimal>,
    val feesByCurrency: Map<String, BigDecimal>,
    val excludedCount: Int,
    val complete: Boolean,
    val incompleteReason: String?,
)

data class BotCycles(val cycleCount: Long?, val complete: Boolean, val incompleteReason: String?)

data class AllocationDeviation(
    val label: String,
    val targetsUsed: Map<String, BigDecimal>,
    val deviationsBySymbol: Map<String, BigDecimal>,
    val observedAt: Instant?,
    val complete: Boolean,
    val incompleteReason: String?,
)

data class NativeAssetQuantities(
    val quantitiesByAsset: Map<String, BigDecimal>,
    val excludedCount: Int,
    val complete: Boolean,
    val incompleteReason: String?,
)

data class NativeQuantityPoint(val observedAt: Instant, val cumulativeQuantity: BigDecimal)

data class NativeQuantitySeries(val asset: String, val points: List<NativeQuantityPoint>)

data class DepositsWithdrawals(
    val notice: String,
    val deposits: NativeAssetQuantities,
    val withdrawals: NativeAssetQuantities,
    val complete: Boolean,
    val incompleteReason: String?,
)

data class HistoryPeriodStatistics(
    val generatedAt: Instant,
    val latestObservedValue: LatestObservedValue,
    val observedValueChange: ObservedValueChange,
    val rangeExtrema: RangeExtrema,
    val trades: TradeStatistics,
    val botCycles: BotCycles,
    val allocationDeviation: AllocationDeviation,
    val depositsWithdrawals: DepositsWithdrawals,
    val rewards: NativeAssetQuantities,
)
