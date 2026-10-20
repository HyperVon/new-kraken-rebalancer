package com.gemini.krakenbot.service.reporting

import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.model.KrakenApiConstants
import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.service.history.HISTORY_MAX_PAGE_LIMIT
import com.gemini.krakenbot.service.history.HistoryEventQuery
import com.gemini.krakenbot.service.history.HistoryLedger
import com.gemini.krakenbot.service.history.HistoryObservation
import com.gemini.krakenbot.service.history.HistoryObservationStatus
import com.gemini.krakenbot.service.history.HistoryPage
import com.gemini.krakenbot.service.history.HistoryRangeQuery
import com.gemini.krakenbot.service.history.HistoryService
import com.gemini.krakenbot.service.history.HistorySourceScope
import com.gemini.krakenbot.service.history.HistoryTrade
import com.gemini.krakenbot.util.PrecisionConstants
import com.gemini.krakenbot.view.util.ViewText
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

// Matches SqliteHistoryStore.MAX_CYCLE_ACTIVITY_LIMIT; larger limits are rejected by the store.
private const val CYCLE_ACTIVITY_FETCH_LIMIT = 100

private const val REWARD_SUBTYPE = "reward"
private val REWARD_TRANSFER_SUBTYPES = setOf("airdrop", "reward")

class HistoryReportingService(
    private val historyService: HistoryService,
    private val nowProvider: () -> Instant = Instant::now,
) {
    suspend fun latestObservedValue(period: HistoryReportingPeriod): LatestObservedValue {
        val latest = completeObservations(period.sourceScope).maxByOrNull { it.observedAt }
            ?: return LatestObservedValue(
                valueUsd = null,
                observedAt = null,
                outsideSelectedPeriod = false,
                complete = false,
                incompleteReason = ViewText.HISTORY_REPORT_UNAVAILABLE,
            )
        return LatestObservedValue(
            valueUsd = latest.totalUsd,
            observedAt = latest.observedAt,
            outsideSelectedPeriod = period.isOutside(latest.observedAt),
            complete = true,
            incompleteReason = null,
        )
    }

    suspend fun observedValueChange(period: HistoryReportingPeriod): ObservedValueChange {
        val notice = ViewText.HISTORY_VALUE_CHANGE_NOTICE
        val points = completeObservations(period.sourceScope, period.from, period.until)
            .map { observation -> observation.observedAt to observation.totalUsd!! }
            .sortedBy { (observedAt, _) -> observedAt }
        if (points.size < 2) {
            return ObservedValueChange(
                changeUsd = null,
                firstValueUsd = null,
                firstObservedAt = null,
                lastValueUsd = null,
                lastObservedAt = null,
                includesFlowsNotice = notice,
                complete = false,
                incompleteReason = ViewText.HISTORY_REPORT_UNAVAILABLE,
            )
        }
        val (firstObservedAt, firstValueUsd) = points.first()
        val (lastObservedAt, lastValueUsd) = points.last()
        return ObservedValueChange(
            changeUsd = lastValueUsd.subtract(firstValueUsd),
            firstValueUsd = firstValueUsd,
            firstObservedAt = firstObservedAt,
            lastValueUsd = lastValueUsd,
            lastObservedAt = lastObservedAt,
            includesFlowsNotice = notice,
            complete = true,
            incompleteReason = null,
        )
    }

    suspend fun rangeExtrema(period: HistoryReportingPeriod): RangeExtrema {
        val points = completeObservations(period.sourceScope, period.from, period.until)
            .map { observation -> observation.observedAt to observation.totalUsd!! }
        if (points.isEmpty()) {
            return RangeExtrema(
                high = null,
                low = null,
                complete = false,
                incompleteReason = ViewText.HISTORY_REPORT_UNAVAILABLE,
            )
        }
        val high = points.maxBy { (_, valueUsd) -> valueUsd }
        val low = points.minBy { (_, valueUsd) -> valueUsd }
        return RangeExtrema(
            high = RangeExtremum(ViewText.HISTORY_RANGE_HIGH, high.second, high.first),
            low = RangeExtremum(ViewText.HISTORY_RANGE_LOW, low.second, low.first),
            complete = true,
            incompleteReason = null,
        )
    }

    suspend fun tradeStatistics(period: HistoryReportingPeriod): TradeStatistics {
        val digest = period.sourceScope.accountIdentityDigest
            ?: return TradeStatistics(
                tradeCount = null,
                volumeByCurrency = emptyMap(),
                feesByCurrency = emptyMap(),
                excludedCount = 0,
                complete = false,
                incompleteReason = ViewText.HISTORY_REPORT_UNAVAILABLE,
            )
        val trades = fetchAllPages { offset ->
            historyService.queryTrades(
                HistoryEventQuery(
                    from = period.from,
                    until = period.until,
                    accountIdentityDigest = digest,
                    limit = HISTORY_MAX_PAGE_LIMIT,
                    offset = offset,
                ),
            )
        }
        val volumeByCurrency = LinkedHashMap<String, BigDecimal>()
        val feesByCurrency = LinkedHashMap<String, BigDecimal>()
        var excludedCount = 0
        for (trade in trades) {
            val costCurrency = trade.quoteCurrency?.trim()?.takeIf(String::isNotEmpty)
            val cost = trade.cost?.toBigDecimalOrNull()
            if (trade.hasValidCost && cost != null && costCurrency != null) {
                volumeByCurrency.addAmount(costCurrency, cost)
            } else {
                excludedCount++
            }
            val feeCurrency = trade.normalizedFeeCurrency(costCurrency)
            val fee = trade.fee?.toBigDecimalOrNull()
            if (trade.hasValidFee && fee != null && feeCurrency != null) {
                feesByCurrency.addAmount(feeCurrency, fee)
            } else {
                excludedCount++
            }
        }
        return TradeStatistics(
            tradeCount = trades.size.toLong(),
            volumeByCurrency = volumeByCurrency,
            feesByCurrency = feesByCurrency,
            excludedCount = excludedCount,
            complete = excludedCount == 0,
            incompleteReason = if (excludedCount == 0) null else ViewText.HISTORY_REPORT_INCOMPLETE,
        )
    }

    suspend fun botCycles(period: HistoryReportingPeriod): BotCycles {
        val cycles = historyService.queryCycleActivities(period.sourceScope, CYCLE_ACTIVITY_FETCH_LIMIT)
        val from = period.from
        val sawBeforePeriodStart = from != null && cycles.any { it.occurredAt < from }
        val truncated = cycles.size >= CYCLE_ACTIVITY_FETCH_LIMIT && !sawBeforePeriodStart
        return BotCycles(
            cycleCount = cycles.count { period.contains(it.occurredAt) }.toLong(),
            complete = !truncated,
            incompleteReason = if (truncated) ViewText.HISTORY_REPORT_INCOMPLETE else null,
        )
    }

    suspend fun allocationDeviation(period: HistoryReportingPeriod, targets: List<Allocation>): AllocationDeviation {
        val label = ViewText.HISTORY_ALLOCATION_DEVIATION
        val targetsUsed = LinkedHashMap<String, BigDecimal>()
        for (allocation in targets) {
            targetsUsed[allocation.symbol.value] = BigDecimal.valueOf(allocation.targetPercent)
        }
        if (targetsUsed.isEmpty()) {
            return AllocationDeviation(label, targetsUsed, emptyMap(), null, false, ViewText.HISTORY_REPORT_UNAVAILABLE)
        }
        val latest = completeObservations(period.sourceScope).maxByOrNull { it.observedAt }
        val totalUsd = latest?.totalUsd
        if (totalUsd == null || totalUsd.signum() <= 0) {
            return AllocationDeviation(
                label = label,
                targetsUsed = targetsUsed,
                deviationsBySymbol = emptyMap(),
                observedAt = latest?.observedAt,
                complete = false,
                incompleteReason = ViewText.HISTORY_REPORT_UNAVAILABLE,
            )
        }
        val valuesBySymbol = latest.assets.mapNotNull { asset -> asset.valueUsd?.let { asset.symbol to it } }.toMap()
        val deviationsBySymbol = LinkedHashMap<String, BigDecimal>()
        for ((symbol, targetPercent) in targetsUsed) {
            val valueUsd = valuesBySymbol[symbol] ?: continue
            val actualPercent = valueUsd
                .multiply(BigDecimal.valueOf(PrecisionConstants.HUNDRED_INT.toLong()))
                .divide(totalUsd, PrecisionConstants.SCALE_PERCENT, RoundingMode.HALF_UP)
            deviationsBySymbol[symbol] = actualPercent.subtract(targetPercent)
        }
        val complete = deviationsBySymbol.size == targetsUsed.size
        return AllocationDeviation(
            label = label,
            targetsUsed = targetsUsed,
            deviationsBySymbol = deviationsBySymbol,
            observedAt = latest.observedAt,
            complete = complete,
            incompleteReason = if (complete) null else ViewText.HISTORY_REPORT_INCOMPLETE,
        )
    }

    suspend fun depositsWithdrawals(period: HistoryReportingPeriod): DepositsWithdrawals {
        val notice = ViewText.HISTORY_LEDGER_CLASSIFICATION_NOTICE
        val digest = period.sourceScope.accountIdentityDigest
            ?: return DepositsWithdrawals(
                notice = notice,
                deposits = NativeAssetQuantities(emptyMap(), 0, false, ViewText.HISTORY_REPORT_UNAVAILABLE),
                withdrawals = NativeAssetQuantities(emptyMap(), 0, false, ViewText.HISTORY_REPORT_UNAVAILABLE),
                complete = false,
                incompleteReason = ViewText.HISTORY_REPORT_UNAVAILABLE,
            )
        val ledgers = fetchAllPages { offset ->
            historyService.queryLedgers(
                HistoryEventQuery(
                    from = period.from,
                    until = period.until,
                    accountIdentityDigest = digest,
                    limit = HISTORY_MAX_PAGE_LIMIT,
                    offset = offset,
                ),
            )
        }
        val deposits = ledgers.filter { it.normalizedType() == KrakenApiConstants.LEDGER_TYPE_DEPOSIT }
        val withdrawals = ledgers.filter { it.normalizedType() == KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL }
        val depositTotals = sumNativeQuantities(deposits)
        val withdrawalTotals = sumNativeQuantities(withdrawals)
        val complete = depositTotals.complete && withdrawalTotals.complete
        return DepositsWithdrawals(
            notice = notice,
            deposits = depositTotals,
            withdrawals = withdrawalTotals,
            complete = complete,
            incompleteReason = if (complete) null else ViewText.HISTORY_REPORT_INCOMPLETE,
        )
    }

    suspend fun rewards(period: HistoryReportingPeriod): NativeAssetQuantities {
        val digest = period.sourceScope.accountIdentityDigest
            ?: return NativeAssetQuantities(emptyMap(), 0, false, ViewText.HISTORY_REPORT_UNAVAILABLE)
        val rewardLedgers = fetchAllPages { offset ->
            historyService.queryLedgers(
                HistoryEventQuery(
                    from = period.from,
                    until = period.until,
                    accountIdentityDigest = digest,
                    limit = HISTORY_MAX_PAGE_LIMIT,
                    offset = offset,
                ),
            )
        }.filter { it.isRewardLedger() }
        return sumNativeQuantities(rewardLedgers)
    }

    fun fundingQuantitySeries(ledgers: List<HistoryLedger>): List<NativeQuantitySeries> =
        nativeQuantitySeries(ledgers) { ledger ->
            val amount = ledger.amount?.toBigDecimalOrNull()?.takeIf { ledger.hasValidAmount }
                ?: return@nativeQuantitySeries null
            when (ledger.normalizedType()) {
                KrakenApiConstants.LEDGER_TYPE_DEPOSIT -> amount.abs()
                KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL -> amount.abs().negate()
                else -> null
            }
        }

    fun rewardQuantitySeries(ledgers: List<HistoryLedger>): List<NativeQuantitySeries> =
        nativeQuantitySeries(ledgers.filter { it.isRewardLedger() }) { ledger ->
            ledger.amount?.toBigDecimalOrNull()?.takeIf { ledger.hasValidAmount }
        }

    suspend fun computeStatistics(
        period: HistoryReportingPeriod,
        targetAllocations: List<Allocation> = emptyList(),
    ): HistoryPeriodStatistics = HistoryPeriodStatistics(
        generatedAt = nowProvider(),
        latestObservedValue = latestObservedValue(period),
        observedValueChange = observedValueChange(period),
        rangeExtrema = rangeExtrema(period),
        trades = tradeStatistics(period),
        botCycles = botCycles(period),
        allocationDeviation = allocationDeviation(period, targetAllocations),
        depositsWithdrawals = depositsWithdrawals(period),
        rewards = rewards(period),
    )

    private suspend fun completeObservations(
        sourceScope: HistorySourceScope,
        from: Instant? = null,
        until: Instant? = null,
    ): List<HistoryObservation> = fetchAllPages { offset ->
        historyService.queryValues(
            HistoryRangeQuery(
                from = from,
                until = until,
                sourceScope = sourceScope,
                limit = HISTORY_MAX_PAGE_LIMIT,
                offset = offset,
            ),
        )
    }.filter { it.status == HistoryObservationStatus.COMPLETE }

    private suspend fun <T> fetchAllPages(fetch: suspend (Int) -> HistoryPage<T>): List<T> {
        val items = mutableListOf<T>()
        var offset = 0
        while (true) {
            val page = fetch(offset)
            items.addAll(page.items)
            if (!page.hasMore || page.items.isEmpty()) return items
            offset += page.items.size
        }
    }
}

private fun MutableMap<String, BigDecimal>.addAmount(key: String, amount: BigDecimal) {
    put(key, (get(key) ?: BigDecimal.ZERO).add(amount))
}

private fun nativeQuantitySeries(
    ledgers: List<HistoryLedger>,
    amountFor: (HistoryLedger) -> BigDecimal?,
): List<NativeQuantitySeries> {
    val eventsByAsset = ledgers.mapNotNull { ledger ->
        val asset = ledger.asset?.trim()?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
        val observedAt = ledger.time ?: return@mapNotNull null
        val amount = amountFor(ledger) ?: return@mapNotNull null
        Triple(asset, ledger, observedAt to amount)
    }.groupBy { (asset, _, _) -> asset }
    return eventsByAsset.toSortedMap().map { (asset, events) ->
        var cumulative = BigDecimal.ZERO
        val points = events
            .sortedWith(compareBy({ (_, ledger, _) -> ledger.time }, { (_, ledger, _) -> ledger.ledgerId }))
            .map { (_, _, point) ->
                cumulative = cumulative.add(point.second)
                NativeQuantityPoint(point.first, cumulative)
            }
        NativeQuantitySeries(asset, points)
    }
}

private fun sumNativeQuantities(rows: List<HistoryLedger>): NativeAssetQuantities {
    val quantitiesByAsset = LinkedHashMap<String, BigDecimal>()
    var excludedCount = 0
    for (row in rows) {
        val asset = row.asset?.trim()?.takeIf(String::isNotEmpty)
        val amount = row.amount?.toBigDecimalOrNull()?.takeIf { row.hasValidAmount }
        if (asset == null || amount == null) {
            excludedCount++
            continue
        }
        quantitiesByAsset.addAmount(asset, amount)
    }
    return NativeAssetQuantities(
        quantitiesByAsset = quantitiesByAsset,
        excludedCount = excludedCount,
        complete = excludedCount == 0,
        incompleteReason = if (excludedCount == 0) null else ViewText.HISTORY_REPORT_INCOMPLETE,
    )
}

private fun HistoryTrade.normalizedFeeCurrency(costCurrency: String?): String? =
    feeCurrency?.trim()?.takeIf(String::isNotEmpty) ?: costCurrency

private fun HistoryLedger.normalizedType(): String = type?.trim()?.lowercase().orEmpty()

private fun HistoryLedger.isRewardLedger(): Boolean {
    val type = normalizedType()
    if (type in LedgerEvent.REWARD_TYPES) return true
    val subtype = subtype?.trim()?.lowercase().orEmpty()
    return (type == KrakenApiConstants.LEDGER_TYPE_EARN && subtype == REWARD_SUBTYPE) ||
        (type == KrakenApiConstants.LEDGER_TYPE_TRANSFER && subtype in REWARD_TRANSFER_SUBTYPES)
}
