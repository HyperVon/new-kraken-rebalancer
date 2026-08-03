package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.api.HistoryStats
import com.gemini.krakenbot.api.PortfolioSnapshot
import com.gemini.krakenbot.api.RebalancerComparison
import com.gemini.krakenbot.api.RebalancerComparisonPoint
import com.gemini.krakenbot.api.TradeRecord
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.OrderSide
import kotlinx.coroutines.await
import kotlin.js.Promise
import kotlin.js.json

/** Plain JS object via `json().apply` — property writes in [builder] land on the object, not a typed map. */
inline fun jsObject(builder: dynamic.() -> Unit = {}): dynamic = json().apply(builder)

fun mockTradeRecord(
    symbol: String? = Asset.BTC,
    side: String = OrderSide.BUY.name,
    usdAmount: String = "100.0",
    success: Boolean = true,
    dryRun: Boolean = false,
    timestamp: String = "2023-01-01",
    volume: String = "1.0",
    price: String = "50000.0",
    fee: String = "2.6",
    slippagePercent: String? = null,
    source: String? = null,
    errorMessage: String? = null,
    pair: String = "${symbol ?: Asset.BTC}/USD",
): TradeRecord = TradeRecord(
    timestamp = timestamp,
    pair = pair,
    side = side,
    symbol = symbol.orEmpty(),
    volume = volume,
    usdAmount = usdAmount,
    success = success,
    dryRun = dryRun,
    errorMessage = errorMessage,
    price = price,
    fee = fee,
    slippagePercent = slippagePercent,
    source = source,
)

fun mockSnapshotRecord(
    timestamp: String = "2023-01-01",
    totalValueUSD: String = "100.0",
    assets: Map<String, PortfolioSnapshot.AssetSnapshot> =
        mapOf(
            Asset.BTC to
                PortfolioSnapshot.AssetSnapshot(
                    symbol = Asset.BTC,
                    balance = "1",
                    price = "100",
                    valueUSD = "100",
                    targetPercent = "50",
                    currentPercent = "100",
                    deviationPercent = "0",
                    deviationUSD = "0",
                ),
        ),
): PortfolioSnapshot = PortfolioSnapshot(
    timestamp = timestamp,
    totalValueUSD = totalValueUSD,
    assets = assets,
    actions = emptyList(),
    drawdownPercent = "0",
    fiatDeploymentPercent = "0",
    effectiveUsdTargetPercent = "0",
)

fun mockPortfolioStatsRecord(
    allTimeHigh: String = "15000.5",
    totalTradesExecuted: Long = 42L,
    totalVolumeTraded: String = "1000000.0",
    totalFeesPaid: String = "250.75",
    avgFeeRatePercent: String? = "0.26",
    avgSlippagePercent: String? = "0.15",
): HistoryStats = HistoryStats(
    allTimeHigh = allTimeHigh,
    totalTradesExecuted = totalTradesExecuted,
    totalVolumeTraded = totalVolumeTraded,
    totalFeesPaid = totalFeesPaid,
    latestSnapshotTime = null,
    avgFeeRatePercent = avgFeeRatePercent ?: "0",
    avgSlippagePercent = avgSlippagePercent,
)

fun mockAvailableComparison(): RebalancerComparison = RebalancerComparison(
    availability = "AVAILABLE",
    confidence = "RECONCILED",
    baselineTimestamp = "2026-07-01T12:00:00Z",
    points = listOf(
        RebalancerComparisonPoint(
            timestamp = "2026-07-01T12:00:00Z",
            rebalancerValueUSD = "100000.00",
            buyAndHoldValueUSD = "100000.00",
            differenceUSD = "0.00",
            differencePercent = "0.0000",
        ),
        RebalancerComparisonPoint(
            timestamp = "2026-07-02T12:00:00Z",
            rebalancerValueUSD = "110000.00",
            buyAndHoldValueUSD = "105000.00",
            differenceUSD = "5000.00",
            differencePercent = "4.7619",
        ),
    ),
    latestDifferenceUSD = "5000.00",
    latestDifferencePercent = "4.7619",
    unavailableReason = null,
    unavailableAt = null,
)

fun mockUnavailableComparison(reason: String = "INSUFFICIENT_SNAPSHOTS"): RebalancerComparison = RebalancerComparison(
    availability = "UNAVAILABLE",
    confidence = null,
    baselineTimestamp = null,
    points = emptyList(),
    latestDifferenceUSD = null,
    latestDifferencePercent = null,
    unavailableReason = reason,
    unavailableAt = "2026-07-01T12:00:00Z",
)

internal fun rebalancerComparisonToDynamic(comparison: RebalancerComparison): dynamic = json(
    "availability" to comparison.availability,
    "confidence" to comparison.confidence,
    "baselineTimestamp" to comparison.baselineTimestamp,
    "points" to comparison.points.map { point ->
        json(
            "timestamp" to point.timestamp,
            "rebalancerValueUSD" to point.rebalancerValueUSD,
            "buyAndHoldValueUSD" to point.buyAndHoldValueUSD,
            "differenceUSD" to point.differenceUSD,
            "differencePercent" to point.differencePercent,
        )
    }.toTypedArray(),
    "latestDifferenceUSD" to comparison.latestDifferenceUSD,
    "latestDifferencePercent" to comparison.latestDifferencePercent,
    "unavailableReason" to comparison.unavailableReason,
    "unavailableAt" to comparison.unavailableAt,
)

/** Build a dynamic trade JSON object with independent wire-contract keys. */
internal fun tradeRecordToDynamic(trade: TradeRecord): dynamic = json(
    "timestamp" to trade.timestamp,
    "symbol" to trade.symbol,
    "side" to trade.side,
    "pair" to trade.pair,
    "volume" to trade.volume,
    "usdAmount" to trade.usdAmount,
    "success" to trade.success,
    "dryRun" to trade.dryRun,
    "price" to trade.price,
    "fee" to trade.fee,
    "slippagePercent" to trade.slippagePercent,
    "expectedPrice" to trade.expectedPrice,
    "source" to trade.source,
    "errorMessage" to trade.errorMessage,
    "id" to trade.id,
)

/** Build a dynamic stats JSON object for tests. */
internal fun historyStatsToDynamic(stats: HistoryStats): dynamic = json(
    "allTimeHigh" to stats.allTimeHigh,
    "totalTradesExecuted" to stats.totalTradesExecuted,
    "totalVolumeTraded" to stats.totalVolumeTraded,
    "totalFeesPaid" to stats.totalFeesPaid,
    "latestSnapshotTime" to stats.latestSnapshotTime,
    "avgFeeRatePercent" to stats.avgFeeRatePercent,
    "avgSlippagePercent" to stats.avgSlippagePercent,
    "failedTradeCount" to stats.failedTradeCount,
    "dryRunTradeCount" to stats.dryRunTradeCount,
)

internal fun portfolioSnapshotToDynamic(snapshot: PortfolioSnapshot): dynamic {
    val assetsDynamic = json()
    snapshot.assets.forEach { (symbol, asset) ->
        assetsDynamic[symbol] =
            json(
                "symbol" to asset.symbol,
                "balance" to asset.balance,
                "price" to asset.price,
                "valueUSD" to asset.valueUSD,
                "targetPercent" to asset.targetPercent,
                "currentPercent" to asset.currentPercent,
                "deviationPercent" to asset.deviationPercent,
                "deviationUSD" to asset.deviationUSD,
            )
    }
    return json(
        "timestamp" to snapshot.timestamp,
        "totalValueUSD" to snapshot.totalValueUSD,
        "assets" to assetsDynamic,
        "actions" to snapshot.actions.toTypedArray(),
        "drawdownPercent" to snapshot.drawdownPercent,
        "fiatDeploymentPercent" to snapshot.fiatDeploymentPercent,
        "effectiveUsdTargetPercent" to snapshot.effectiveUsdTargetPercent,
    )
}

fun mockHistoryFetchHandler(
    snapshots: List<PortfolioSnapshot> = listOf(mockSnapshotRecord()),
    trades: List<TradeRecord> = listOf(mockTradeRecord()),
    stats: HistoryStats = mockPortfolioStatsRecord(),
    syncProgress: dynamic = json("seeded" to true),
    comparison: RebalancerComparison = mockAvailableComparison(),
): (String) -> Any? = { url ->
    when {
        url.contains("snapshots") -> snapshots.map { portfolioSnapshotToDynamic(it) }.toTypedArray()
        url.contains("trades") -> trades.map { tradeRecordToDynamic(it) }.toTypedArray()
        url.contains("comparison") -> rebalancerComparisonToDynamic(comparison)
        url.contains("sync-progress") -> syncProgress
        else -> historyStatsToDynamic(stats)
    }
}

fun mockFetch(handler: (String) -> Any?): dynamic = { url: String ->
    val responseData = handler(url)
    Promise.resolve(json("json" to { Promise.resolve(responseData) }))
}

/** Advance the native Promise queue without relying on a wall-clock sleep. */
suspend fun awaitPromiseQueue() {
    repeat(3) {
        Promise.resolve(Unit).await()
    }
}

/** Chart.js stand-in; default `isDatasetVisible` is true only for index 0 (feeds createOrUpdate snapshots). */
fun mockChartConstructor(onConfig: (dynamic) -> Unit = {}): dynamic = { _: dynamic, config: dynamic ->
    onConfig(config)
    jsObject {
        data = config.data
        destroy = { asDynamic().destroyed = true }
        isDatasetVisible = { index: Int -> index == 0 }
    }
}

/** `Object.defineProperty` getter (e.g. stub `document.body` to null in MainTest). */
fun defineGetter(obj: Any, prop: String, getter: () -> Any?, configurable: Boolean = true) {
    js("Object").defineProperty(
        obj,
        prop,
        jsObject {
            get = getter
            this.configurable = configurable
        },
    )
}
