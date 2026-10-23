package com.gemini.krakenbot.service.history

import com.gemini.krakenbot.model.Asset
import java.math.BigDecimal
import java.time.ZoneOffset

internal data class HistoryDailyPriceIdentity(
    val accountIdentityDigest: String,
    val canonicalAssetKey: String,
    val candleOpenEpochSecond: Long,
) {
    companion object {
        fun forAssetAndOpen(accountIdentityDigest: String, rawAssetKey: String, candleOpenEpochSecond: Long) =
            HistoryDailyPriceIdentity(
                accountIdentityDigest = accountIdentityDigest,
                canonicalAssetKey = Asset.normalizeLedgerAsset(rawAssetKey),
                candleOpenEpochSecond = candleOpenEpochSecond,
            )
    }
}

/** One collision-safe identity contract shared by the cache and value builder. */
internal class HistoryDailyPriceIndex private constructor(
    private val byIdentity: Map<HistoryDailyPriceIdentity, HistoryDailyPrice>,
    private val ambiguousIdentities: Set<HistoryDailyPriceIdentity>,
    private val candidates: List<HistoryDailyPrice>,
) {
    val selectedPrices: List<HistoryDailyPrice>
        get() = byIdentity.entries
            .sortedWith(
                compareBy({
                    it.key.accountIdentityDigest
                }, { it.key.canonicalAssetKey }, { it.key.candleOpenEpochSecond }),
            )
            .map { it.value }

    fun price(accountIdentityDigest: String, rawAssetKey: String, candleOpenEpochSecond: Long): HistoryDailyPrice? =
        byIdentity[
            HistoryDailyPriceIdentity.forAssetAndOpen(accountIdentityDigest, rawAssetKey, candleOpenEpochSecond),
        ]

    fun isAmbiguous(accountIdentityDigest: String, rawAssetKey: String, candleOpenEpochSecond: Long): Boolean =
        HistoryDailyPriceIdentity.forAssetAndOpen(accountIdentityDigest, rawAssetKey, candleOpenEpochSecond) in
            ambiguousIdentities

    fun plus(prices: Collection<HistoryDailyPrice>): HistoryDailyPriceIndex = from(candidates + prices)

    companion object {
        fun from(prices: Collection<HistoryDailyPrice>): HistoryDailyPriceIndex {
            val candidates = prices.filter(::isValid).distinct()
            val grouped = candidates.groupBy { price ->
                HistoryDailyPriceIdentity.forAssetAndOpen(
                    price.accountIdentityDigest,
                    price.rawAssetKey,
                    price.candleOpen.epochSecond,
                )
            }
            val selected = mutableMapOf<HistoryDailyPriceIdentity, HistoryDailyPrice>()
            val ambiguous = mutableSetOf<HistoryDailyPriceIdentity>()
            grouped.forEach { (identity, sameDayPrices) ->
                val byValue = sameDayPrices.groupBy { it.closeUsd.stripTrailingZeros() }
                val canonicalResolved = sameDayPrices.filter { price ->
                    price.rawAssetKey == identity.canonicalAssetKey &&
                        price.source in CANONICAL_RESOLVER_SOURCES
                }.groupBy { it.closeUsd.stripTrailingZeros() }
                val choices = when {
                    byValue.size == 1 -> sameDayPrices
                    canonicalResolved.size == 1 -> canonicalResolved.values.single()
                    else -> emptyList()
                }
                if (choices.isEmpty()) {
                    ambiguous += identity
                } else {
                    selected[identity] = choices.minWithOrNull(PRICE_PROVENANCE_ORDER)!!
                }
            }
            return HistoryDailyPriceIndex(selected, ambiguous, candidates)
        }

        private fun isValid(price: HistoryDailyPrice): Boolean {
            if (price.candleEnd != price.candleOpen.plusSeconds(HistoryDailyValueBuilder.SECONDS_PER_DAY)) {
                return false
            }
            return if (Asset.normalizeLedgerAsset(price.rawAssetKey) == Asset.USD) {
                price.closeUsd.compareTo(BigDecimal.ONE) == 0
            } else {
                price.closeUsd.signum() > 0
            }
        }

        private val CANONICAL_RESOLVER_SOURCES = setOf("EXACT_USD_UNIT", "KRAKEN_COMPLETED_DAILY_OHLC")

        private val PRICE_PROVENANCE_ORDER = compareBy<HistoryDailyPrice>(
            { if (it.rawAssetKey == Asset.normalizeLedgerAsset(it.rawAssetKey)) 0 else 1 },
            { it.rawAssetKey },
            { it.pair },
            { it.source },
            { it.candleOpen },
            { it.candleEnd },
            { it.legs.joinToString("|") { leg -> "${leg.pair}:${leg.close}:${leg.quoteAsset}" } },
            { it.closeUsd.toPlainString() },
            { it.accountIdentityDigest },
        )
    }
}

/** Pure join of daily quantities and same-UTC-day close prices. */
class HistoryDailyValueBuilder {
    fun build(
        balances: List<HistoryDailyBalance>,
        prices: List<HistoryDailyPrice>,
        calculationPolicyVersion: String = HISTORY_DAILY_VALUE_POLICY_VERSION,
    ): List<HistoryDailyValue> = build(balances, HistoryDailyPriceIndex.from(prices), calculationPolicyVersion)

    internal fun build(
        balances: List<HistoryDailyBalance>,
        prices: HistoryDailyPriceIndex,
        calculationPolicyVersion: String = HISTORY_DAILY_VALUE_POLICY_VERSION,
    ): List<HistoryDailyValue> = balances.groupBy { it.dayEnd.epochSecond }
        .toSortedMap()
        .map { (_, dayBalances) ->
            val first = dayBalances.first()
            require(dayBalances.all { it.accountIdentityDigest == first.accountIdentityDigest })
            require(dayBalances.all { it.sourceAssetScope == first.sourceAssetScope })
            require(dayBalances.all { it.generationId == first.generationId })
            val assets = dayBalances.map { balance ->
                assetValue(balance, prices)
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

                dayBalances.any { it.status != HistoryDailyBalanceStatus.VALID } ||
                    assets.any { it.status == HistoryDailyValueStatus.NO_BALANCE } ->
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

    private fun assetValue(balance: HistoryDailyBalance, prices: HistoryDailyPriceIndex): HistoryDailyAssetValue {
        if (balance.status != HistoryDailyBalanceStatus.VALID || balance.quantity == null ||
            balance.quantity.signum() < 0
        ) {
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
                quantity = if (balance.status == HistoryDailyBalanceStatus.VALID) {
                    balance.quantity?.takeUnless { it.signum() < 0 }
                } else {
                    null
                },
                priceUsd = null,
                valueUsd = null,
                pricePair = null,
                priceCandleOpen = null,
                priceSource = null,
                status = status,
                reason = balance.reason ?: if (balance.quantity?.signum() == -1) {
                    "A daily balance checkpoint has a negative quantity."
                } else {
                    null
                },
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
        val candleOpen = balance.dayLabel.atStartOfDay(ZoneOffset.UTC).toEpochSecond()
        val price = prices.price(balance.accountIdentityDigest, balance.rawAssetKey, candleOpen)
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
                reason = if (prices.isAmbiguous(balance.accountIdentityDigest, balance.rawAssetKey, candleOpen)) {
                    "Conflicting completed Kraken USD closes are available for this holding on this UTC date."
                } else {
                    "No valid completed Kraken USD close is available for this holding on this UTC date."
                },
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

    companion object {
        const val SECONDS_PER_DAY = 86_400L
    }
}
