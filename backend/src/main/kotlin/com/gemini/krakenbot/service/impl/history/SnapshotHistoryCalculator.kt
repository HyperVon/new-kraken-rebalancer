package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.domain.PortfolioCalculations
import com.gemini.krakenbot.domain.RebalancerEngine
import com.gemini.krakenbot.domain.isNegative
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.model.OrderSide
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.util.PrecisionConstants
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

        data class RewardEvent(override val timestamp: Instant, val event: LedgerEvent) : TimelineEvent()

        data class DailyCloseEvent(override val timestamp: Instant) : TimelineEvent()

        // Newest first — [calculateHistoricalSnapshots] undoes trades after each snapshot.
        override fun compareTo(other: TimelineEvent): Int = other.timestamp.compareTo(this.timestamp)
    }

    private val externalLedgerTypes = LedgerEvent.EXTERNAL_BALANCE_TYPES

    fun buildTimelineEvents(
        historicalTrades: List<TradeRecord>,
        historicalRewards: List<LedgerEvent> = emptyList(),
        cutoffTime: Instant,
        now: Instant = Instant.now(),
    ): List<TimelineEvent> {
        val events = historicalTrades
            .map { TimelineEvent.TradeEvent(it.timestamp, it) }
            .toMutableList<TimelineEvent>()
        events += historicalRewards
            .filter { it.type in externalLedgerTypes }
            .map { TimelineEvent.RewardEvent(it.time, it) }
        events += (0..PrecisionConstants.HISTORICAL_DAYS_BACK).mapNotNull { day ->
            val dailyTime =
                now
                    .minus(day.toLong(), ChronoUnit.DAYS)
                    .truncatedTo(ChronoUnit.DAYS)
                    .plus(PrecisionConstants.LAST_HOUR_OF_DAY.toLong(), ChronoUnit.HOURS)
                    .plus(PrecisionConstants.LAST_MINUTE_OF_HOUR.toLong(), ChronoUnit.MINUTES)
                    .plus(PrecisionConstants.LAST_SECOND_OF_MINUTE.toLong(), ChronoUnit.SECONDS)
            dailyTime.takeIf { it.isBefore(cutoffTime) }?.let(TimelineEvent::DailyCloseEvent)
        }

        events.sort()
        return events
    }

    private data class RawHistoricalPoint(
        /** Buffered snapshot points collected newest-first; reversed for ATH-aware chronological computation. */
        val timestamp: Instant,
        val exactPortfolioValue: BigDecimal,
        val calculatedAssets: List<CalculatedAsset>,
    )

    fun calculateHistoricalSnapshots(
        events: List<TimelineEvent>,
        allocations: List<Allocation>,
        runningBalances: MutableMap<String, BigDecimal>,
        currentPrices: Map<String, BigDecimal>,
        ohlcData: Map<String, List<Pair<Long, BigDecimal>>>,
        tradePrices: Map<String, List<Pair<Instant, BigDecimal>>>,
        settings: Settings,
        currentAth: BigDecimal = BigDecimal.ZERO,
    ): List<PortfolioSnapshot> {
        val rawPoints = mutableListOf<RawHistoricalPoint>()

        // [runningBalances] starts at the reconstruction cutoff (the oldest retained snapshot, or current balances
        // when none exists); after each trade snapshot, undo that fill so older points see pre-trade balances.
        for (ev in events) {
            val snapshotTimestamp = ev.timestamp
            var exactPortfolioValue = BigDecimal.ZERO

            val calculatedAssets =
                allocations.map { alloc ->
                    val symbol = alloc.symbol.value.uppercase()
                    val rawBal = runningBalances[symbol] ?: BigDecimal.ZERO
                    val balance = if (rawBal.isNegative) BigDecimal.ZERO else rawBal
                    val price = getPriceForTimestamp(symbol, snapshotTimestamp, ohlcData, tradePrices, currentPrices)
                    val valueUSD = balance.multiply(price)
                    exactPortfolioValue = exactPortfolioValue.add(valueUSD)
                    CalculatedAsset(symbol, balance, price, valueUSD, alloc.targetPercent)
                }

            rawPoints.add(RawHistoricalPoint(snapshotTimestamp, exactPortfolioValue, calculatedAssets))

            if (ev is TimelineEvent.TradeEvent) {
                reverseApplyTrade(ev.trade, runningBalances)
            } else if (ev is TimelineEvent.RewardEvent) {
                reverseApplyReward(ev.event, runningBalances)
            }
        }

        return buildSnapshotsChronological(rawPoints, allocations, settings, currentAth)
    }

    /** Undo one fill: buy spent usd+fee for volume; sell received usd−fee for volume. */
    private fun reverseApplyTrade(trade: TradeRecord, runningBalances: MutableMap<String, BigDecimal>) {
        val volume = trade.volume
        val usdAmount = trade.usdAmount
        val fee = trade.fee
        val symbol = trade.symbol.uppercase()
        require(OrderSide.isBuy(trade.side) || OrderSide.isSell(trade.side)) {
            "Unsupported trade side during historical reconstruction: ${trade.side}"
        }

        if (OrderSide.isBuy(trade.side)) {
            runningBalances[symbol] = (runningBalances[symbol] ?: BigDecimal.ZERO).subtract(volume)
            runningBalances[Asset.USD] = (runningBalances[Asset.USD] ?: BigDecimal.ZERO).add(usdAmount).add(fee)
        } else if (OrderSide.isSell(trade.side)) {
            runningBalances[symbol] = (runningBalances[symbol] ?: BigDecimal.ZERO).add(volume)
            runningBalances[Asset.USD] = (runningBalances[Asset.USD] ?: BigDecimal.ZERO).subtract(usdAmount).add(fee)
        }
    }

    /** Undo one external ledger balance delta, including both legs of a consumer transaction. */
    private fun reverseApplyReward(event: LedgerEvent, runningBalances: MutableMap<String, BigDecimal>) {
        val symbol = Asset.normalizeLedgerAsset(event.asset).uppercase()
        if (symbol !in runningBalances) return
        val netDelta = event.netBalanceDelta()
        runningBalances[symbol] = runningBalances.getValue(symbol).subtract(netDelta)
    }

    private fun getPriceForTimestamp(
        symbol: String,
        timestamp: Instant,
        ohlcData: Map<String, List<Pair<Long, BigDecimal>>>,
        tradePrices: Map<String, List<Pair<Instant, BigDecimal>>>,
        currentPrices: Map<String, BigDecimal>,
    ): BigDecimal {
        if (symbol.equals(Asset.USD, ignoreCase = true)) return BigDecimal.ONE

        val prices = ohlcData[symbol.uppercase()].orEmpty().filter { it.second.signum() > 0 }
        if (prices.isNotEmpty()) {
            val targetSec = timestamp.epochSecond
            return prices.filter { it.first <= targetSec }
                .maxByOrNull { it.first }
                ?.second
                ?: prices.minBy { it.first }.second
        }

        val tPrices = tradePrices[symbol.uppercase()].orEmpty().filter { it.second.signum() > 0 }
        if (tPrices.isNotEmpty()) {
            return findClosest(
                tPrices,
                timestamp.toEpochMilli(),
                { it.first.toEpochMilli() },
                { it.second },
            )
        }

        return currentPrices[symbol.uppercase()]?.takeIf { it.signum() > 0 }
            ?: throw HistoricalPriceUnavailableException(
                "No trustworthy price for $symbol at $timestamp during historical reconstruction.",
            )
    }

    /** Validates the same source precedence used by snapshot calculation without a zero fallback. */
    fun requireTrustworthyPrice(
        symbol: String,
        timestamp: Instant,
        ohlcData: Map<String, List<Pair<Long, BigDecimal>>>,
        tradePrices: Map<String, List<Pair<Instant, BigDecimal>>>,
        currentPrices: Map<String, BigDecimal>,
    ): BigDecimal = getPriceForTimestamp(symbol, timestamp, ohlcData, tradePrices, currentPrices)

    private fun buildSnapshotsChronological(
        rawPoints: List<RawHistoricalPoint>,
        allocations: List<Allocation>,
        settings: Settings,
        currentAth: BigDecimal,
    ): List<PortfolioSnapshot> {
        val snapshotsChronological = mutableListOf<PortfolioSnapshot>()
        var runningAth = currentAth

        for (point in rawPoints.asReversed()) {
            val exactPortfolioValue = point.exactPortfolioValue
            runningAth = updateAthForPoint(runningAth, exactPortfolioValue)

            val drawdownPct = RebalancerEngine.calculateDrawdown(exactPortfolioValue, runningAth)
            val fiatDeploymentPct = RebalancerEngine.calculateFiatDeployment(drawdownPct, settings)
            val effectiveUsdTarget = RebalancerEngine.calculateEffectiveUsdTarget(fiatDeploymentPct, allocations)
            val cryptoScaleFactor = RebalancerEngine.calculateCryptoScaleFactor(effectiveUsdTarget, allocations)
            val minimumOrderSize = settings.minimumOrderSizeUSD

            val assetSnapshots = mutableMapOf<String, PortfolioSnapshot.AssetSnapshot>()
            for ((symbol, balance, price, valueUSD, targetPercent) in point.calculatedAssets) {
                val symbolAsset = Asset(symbol)
                val metrics =
                    PortfolioCalculations.calculateAssetMetrics(
                        symbol = symbolAsset,
                        baseTargetPercent = BigDecimal.valueOf(targetPercent),
                        currentValueUSD = valueUSD,
                        totalPortfolioValueUSD = exactPortfolioValue,
                        effectiveUsdTarget = effectiveUsdTarget,
                        cryptoScaleFactor = cryptoScaleFactor,
                        minimumOrderSizeUSD = minimumOrderSize,
                    )

                assetSnapshots[symbol] =
                    PortfolioCalculations.createAssetSnapshot(
                        symbol = symbol,
                        balance = balance,
                        price = price,
                        valueUSD = valueUSD,
                        metrics = metrics,
                    )
            }

            val snapshot =
                PortfolioSnapshot(
                    timestamp = point.timestamp,
                    totalValueUSD = exactPortfolioValue.setScale(PrecisionConstants.SCALE_USD, RoundingMode.HALF_UP),
                    assets = assetSnapshots,
                    actions = emptyList(),
                    drawdownPercent = drawdownPct,
                    fiatDeploymentPercent = fiatDeploymentPct,
                    effectiveUsdTargetPercent = effectiveUsdTarget,
                )

            snapshotsChronological.add(snapshot)
        }

        return snapshotsChronological.asReversed()
    }

    private fun updateAthForPoint(currentAth: BigDecimal, pointValue: BigDecimal): BigDecimal =
        if (pointValue > currentAth) pointValue else currentAth

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
