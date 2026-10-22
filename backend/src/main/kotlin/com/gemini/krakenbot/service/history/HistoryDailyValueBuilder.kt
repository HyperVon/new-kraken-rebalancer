package com.gemini.krakenbot.service.history

import com.gemini.krakenbot.model.Asset
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneOffset

/** Pure join of daily quantities and same-UTC-day close prices. */
class HistoryDailyValueBuilder {
    fun build(
        balances: List<HistoryDailyBalance>,
        prices: List<HistoryDailyPrice>,
        calculationPolicyVersion: String = HISTORY_DAILY_VALUE_POLICY_VERSION,
    ): List<HistoryDailyValue> {
        val byAssetDay = prices.associateBy { price -> price.rawAssetKey to price.candleOpen.epochSecond }
        return balances.groupBy { it.dayEnd.epochSecond }
            .toSortedMap()
            .map { (_, dayBalances) ->
                val first = dayBalances.first()
                require(dayBalances.all { it.accountIdentityDigest == first.accountIdentityDigest })
                require(dayBalances.all { it.sourceAssetScope == first.sourceAssetScope })
                require(dayBalances.all { it.generationId == first.generationId })
                val assets = dayBalances.map { balance ->
                    assetValue(balance, byAssetDay)
                }
                val balanceIncomplete = dayBalances.any { it.status != HistoryDailyBalanceStatus.VALID }
                val incompleteAsset = assets.firstOrNull { it.status != HistoryDailyValueStatus.COMPLETE }
                val complete = !balanceIncomplete && incompleteAsset == null
                val total = if (complete) {
                    assets.fold(BigDecimal.ZERO) { sum, asset ->
                        sum.add(asset.valueUsd!!)
                    }
                } else {
                    null
                }
                val status = when {
                    complete -> HistoryDailyValueStatus.COMPLETE

                    dayBalances.any { it.status == HistoryDailyBalanceStatus.AMBIGUOUS_ORDER } ->
                        HistoryDailyValueStatus.AMBIGUOUS_BALANCE

                    dayBalances.any { it.status != HistoryDailyBalanceStatus.VALID } ->
                        HistoryDailyValueStatus.NO_BALANCE

                    assets.any { it.status == HistoryDailyValueStatus.NO_PRICE } -> HistoryDailyValueStatus.NO_PRICE

                    else -> HistoryDailyValueStatus.INCOMPLETE
                }
                HistoryDailyValue(
                    dayEnd = first.dayEnd,
                    accountIdentityDigest = first.accountIdentityDigest,
                    sourceAssetScope = first.sourceAssetScope,
                    totalUsd = total,
                    status = status,
                    assets = assets,
                    dayLabel = first.dayLabel,
                    generationId = first.generationId,
                    calculationPolicyVersion = calculationPolicyVersion,
                    sourceRevision = dayBalances.maxOf { it.sourceRevision },
                )
            }
    }

    private fun assetValue(
        balance: HistoryDailyBalance,
        prices: Map<Pair<String, Long>, HistoryDailyPrice>,
    ): HistoryDailyAssetValue {
        if (balance.status != HistoryDailyBalanceStatus.VALID || balance.quantity == null) {
            val status = if (balance.status == HistoryDailyBalanceStatus.AMBIGUOUS_ORDER) {
                HistoryDailyValueStatus.AMBIGUOUS_BALANCE
            } else {
                HistoryDailyValueStatus.NO_BALANCE
            }
            return HistoryDailyAssetValue(
                dayEnd = balance.dayEnd,
                accountIdentityDigest = balance.accountIdentityDigest,
                rawAssetClass = balance.rawAssetClass,
                rawAssetKey = balance.rawAssetKey,
                quantity = null,
                priceUsd = null,
                valueUsd = null,
                pricePair = null,
                priceCandleOpen = null,
                priceSource = null,
                status = status,
                reason = balance.reason,
            )
        }
        if (balance.quantity.signum() == 0) {
            return HistoryDailyAssetValue(
                dayEnd = balance.dayEnd,
                accountIdentityDigest = balance.accountIdentityDigest,
                rawAssetClass = balance.rawAssetClass,
                rawAssetKey = balance.rawAssetKey,
                quantity = balance.quantity,
                priceUsd = null,
                valueUsd = BigDecimal.ZERO,
                pricePair = null,
                priceCandleOpen = null,
                priceSource = null,
                status = HistoryDailyValueStatus.COMPLETE,
            )
        }
        val price = prices[priceLookupKey(balance.rawAssetKey, balance.dayLabel)]
        if (price == null || price.closeUsd.signum() <= 0) {
            return HistoryDailyAssetValue(
                dayEnd = balance.dayEnd,
                accountIdentityDigest = balance.accountIdentityDigest,
                rawAssetClass = balance.rawAssetClass,
                rawAssetKey = balance.rawAssetKey,
                quantity = balance.quantity,
                priceUsd = null,
                valueUsd = null,
                pricePair = null,
                priceCandleOpen = null,
                priceSource = null,
                status = HistoryDailyValueStatus.NO_PRICE,
                reason = "No valid completed Kraken USD close is available for this holding on this UTC date.",
            )
        }
        return HistoryDailyAssetValue(
            dayEnd = balance.dayEnd,
            accountIdentityDigest = balance.accountIdentityDigest,
            rawAssetClass = balance.rawAssetClass,
            rawAssetKey = balance.rawAssetKey,
            quantity = balance.quantity,
            priceUsd = price.closeUsd,
            valueUsd = balance.quantity.multiply(price.closeUsd),
            pricePair = price.pair,
            priceCandleOpen = price.candleOpen,
            priceSource = price.source,
            status = HistoryDailyValueStatus.COMPLETE,
        )
    }

    private fun priceLookupKey(rawAssetKey: String, day: LocalDate): Pair<String, Long> {
        val canonical = Asset.normalizeLedgerAsset(rawAssetKey)
        val dayStart = day.atStartOfDay(ZoneOffset.UTC).toEpochSecond()
        // Every raw bucket stays distinct in balance storage; normalization is used only to
        // reuse the verified market close for explicitly mapped Earn and Kraken asset aliases.
        return canonical to dayStart
    }

    companion object {
        const val SECONDS_PER_DAY = 86_400L
    }
}
