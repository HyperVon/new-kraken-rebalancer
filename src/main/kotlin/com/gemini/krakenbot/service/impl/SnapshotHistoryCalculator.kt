package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.OrderSide
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.util.PrecisionConstants
import com.gemini.krakenbot.util.isNegative
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * Reconstructs historical portfolio snapshots by walking a newest→oldest timeline and
 * reverse-applying trades so balances at each point match pre-trade state for older events.
 */
object SnapshotHistoryCalculator {
    private data class CalculatedAsset(
        val symbol: String,
        val balance: BigDecimal,
        val price: BigDecimal,
        val valueUSD: BigDecimal,
        val targetPercent: Double,
    )

    sealed class TimelineEvent : Comparable<TimelineEvent> {
        abstract val timestamp: Instant

        data class TradeEvent(override val timestamp: Instant, val trade: TradeRecord) : TimelineEvent()

        data class DailyCloseEvent(override val timestamp: Instant) : TimelineEvent()

        // Newest first — [calculateHistoricalSnapshots] undoes trades after each snapshot.
        override fun compareTo(other: TimelineEvent): Int = other.timestamp.compareTo(this.timestamp)
    }

    fun buildTimelineEvents(
        historicalTrades: List<TradeRecord>,
        cutoffTime: Instant,
        now: Instant = Instant.now(),
    ): List<TimelineEvent> {
        val events = mutableListOf<TimelineEvent>()
        for (trade in historicalTrades) {
            events.add(TimelineEvent.TradeEvent(trade.timestamp, trade))
        }

        for (day in 0..PrecisionConstants.HISTORICAL_DAYS_BACK) {
            val dailyTime =
                now
                    .minus(day.toLong(), ChronoUnit.DAYS)
                    .truncatedTo(ChronoUnit.DAYS)
                    .plus(PrecisionConstants.LAST_HOUR_OF_DAY.toLong(), ChronoUnit.HOURS)
                    .plus(PrecisionConstants.LAST_MINUTE_OF_HOUR.toLong(), ChronoUnit.MINUTES)
                    .plus(PrecisionConstants.LAST_SECOND_OF_MINUTE.toLong(), ChronoUnit.SECONDS)
            if (dailyTime.isBefore(cutoffTime)) {
                events.add(TimelineEvent.DailyCloseEvent(dailyTime))
            }
        }

        events.sort()
        return events
    }

    fun calculateHistoricalSnapshots(
        events: List<TimelineEvent>,
        allocations: List<Allocation>,
        runningBalances: MutableMap<String, BigDecimal>,
        currentPrices: Map<String, BigDecimal>,
        ohlcData: Map<String, List<Pair<Long, BigDecimal>>>,
        tradePrices: Map<String, List<Pair<Instant, BigDecimal>>>,
    ): List<PortfolioSnapshot> {
        val snapshotsToSave = mutableListOf<PortfolioSnapshot>()

        // [runningBalances] starts at "now"; after each trade snapshot, undo that fill so older
        // points see pre-trade balances. [OrderSide.isBuy]/[isSell] accept any casing.
        for (ev in events) {
            val snapshotTimestamp = ev.timestamp
            var exactPortfolioValue = BigDecimal.ZERO
            val assetSnapshots = mutableMapOf<String, PortfolioSnapshot.AssetSnapshot>()

            val calculatedAssets =
                allocations.map { alloc ->
                    val symbol = alloc.symbol.value.uppercase()
                    val rawBal = runningBalances[symbol] ?: BigDecimal.ZERO
                    val balance = if (rawBal.isNegative) BigDecimal.ZERO else rawBal
                    val price = getPriceForTimestamp(symbol, snapshotTimestamp, ohlcData, tradePrices, currentPrices)
                    val valueUSD = balance.multiply(price).setScale(PrecisionConstants.SCALE_USD, RoundingMode.HALF_UP)
                    exactPortfolioValue = exactPortfolioValue.add(valueUSD)
                    CalculatedAsset(symbol, balance, price, valueUSD, alloc.targetPercent)
                }

            for ((symbol, balance, price, valueUSD, targetPercent) in calculatedAssets) {
                assetSnapshots[symbol] =
                    PortfolioCalculations.createAssetSnapshot(
                        symbol = symbol,
                        balance = balance,
                        price = price,
                        valueUSD = valueUSD,
                        targetPercent = BigDecimal.valueOf(targetPercent),
                        totalPortfolioValueUSD = exactPortfolioValue,
                    )
            }

            val targetUsdPercent =
                BigDecimal
                    .valueOf(
                        allocations.firstOrNull { it.symbol.isUsd }?.targetPercent
                            ?: PrecisionConstants.DEFAULT_USD_TARGET_PERCENT,
                    ).setScale(PrecisionConstants.SCALE_USD, RoundingMode.HALF_UP)

            val snapshot =
                PortfolioSnapshot(
                    timestamp = snapshotTimestamp,
                    totalValueUSD = exactPortfolioValue.setScale(PrecisionConstants.SCALE_USD, RoundingMode.HALF_UP),
                    assets = assetSnapshots,
                    actions = emptyList(),
                    drawdownPercent = BigDecimal.ZERO,
                    fiatDeploymentPercent = BigDecimal.ZERO,
                    effectiveUsdTargetPercent = targetUsdPercent,
                )

            snapshotsToSave.add(snapshot)

            if (ev is TimelineEvent.TradeEvent) {
                reverseApplyTrade(ev.trade, runningBalances)
            }
        }

        return snapshotsToSave
    }

    /** Undo one fill: buy spent usd+fee for volume; sell received usd−fee for volume. */
    private fun reverseApplyTrade(trade: TradeRecord, runningBalances: MutableMap<String, BigDecimal>) {
        val volume = trade.volume
        val usdAmount = trade.usdAmount
        val fee = trade.fee
        val symbol = trade.symbol.uppercase()

        if (OrderSide.isBuy(trade.side)) {
            runningBalances[symbol] = (runningBalances[symbol] ?: BigDecimal.ZERO).subtract(volume)
            runningBalances[Asset.USD] = (runningBalances[Asset.USD] ?: BigDecimal.ZERO).add(usdAmount).add(fee)
        } else if (OrderSide.isSell(trade.side)) {
            runningBalances[symbol] = (runningBalances[symbol] ?: BigDecimal.ZERO).add(volume)
            runningBalances[Asset.USD] = (runningBalances[Asset.USD] ?: BigDecimal.ZERO).subtract(usdAmount).add(fee)
        }
    }

    private fun getPriceForTimestamp(
        symbol: String,
        timestamp: Instant,
        ohlcData: Map<String, List<Pair<Long, BigDecimal>>>,
        tradePrices: Map<String, List<Pair<Instant, BigDecimal>>>,
        currentPrices: Map<String, BigDecimal>,
    ): BigDecimal {
        if (symbol.equals(Asset.USD, ignoreCase = true)) return BigDecimal.ONE

        val prices = ohlcData[symbol.uppercase()]
        if (!prices.isNullOrEmpty()) {
            // OHLC keys are epoch seconds; trade-price keys below are epoch millis.
            return findClosest(
                prices,
                timestamp.epochSecond,
                { it.first },
                { it.second },
            )
        }

        val tPrices = tradePrices[symbol.uppercase()]
        if (!tPrices.isNullOrEmpty()) {
            return findClosest(
                tPrices,
                timestamp.toEpochMilli(),
                { it.first.toEpochMilli() },
                { it.second },
            )
        }

        return currentPrices[symbol.uppercase()] ?: BigDecimal.ZERO
    }

    private fun <T> findClosest(
        list: List<T>,
        targetTime: Long,
        timeExtractor: (T) -> Long,
        valueExtractor: (T) -> BigDecimal,
    ): BigDecimal {
        var closestValue = valueExtractor(list[0])
        var minDiff = abs(timeExtractor(list[0]) - targetTime)
        for (item in list) {
            val diff = abs(timeExtractor(item) - targetTime)
            if (diff < minDiff) {
                minDiff = diff
                closestValue = valueExtractor(item)
            }
        }
        return closestValue
    }
}
