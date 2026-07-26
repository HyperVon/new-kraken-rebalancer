package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.api.HistoryStats
import com.gemini.krakenbot.api.PortfolioSnapshot
import com.gemini.krakenbot.api.TradeRecord
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.OrderSide
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

fun mockHistoryFetchHandler(
    snapshots: List<PortfolioSnapshot> = listOf(mockSnapshotRecord()),
    trades: List<TradeRecord> = listOf(mockTradeRecord()),
    stats: HistoryStats = mockPortfolioStatsRecord(),
    syncProgress: dynamic = json("seeded" to true),
): (String) -> Any? = { url ->
    when {
        url.contains("snapshots") -> snapshots.map { portfolioSnapshotToDynamic(it) }.toTypedArray()
        url.contains("trades") -> trades.map { tradeRecordToDynamic(it) }.toTypedArray()
        url.contains("sync-progress") -> syncProgress
        else -> historyStatsToDynamic(stats)
    }
}

fun mockFetch(handler: (String) -> Any?): dynamic = { url: String ->
    val responseData = handler(url)
    Promise.resolve(json("json" to { Promise.resolve(responseData) }))
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
