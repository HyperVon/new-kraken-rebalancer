package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.OrderSide
import kotlin.js.Promise
import kotlin.js.json

/** Plain JS object via `json().apply` — property writes in [builder] land on the object, not a typed map. */
inline fun jsObject(builder: dynamic.() -> Unit = {}): dynamic = json().apply(builder)

fun mockTradeRecord(
    symbol: String? = Asset.BTC,
    side: String = OrderSide.BUY.name,
    usdAmount: Number = 100.0,
    success: Boolean = true,
    dryRun: Boolean = false,
    timestamp: String = "2023-01-01",
    volume: Number = 1.0,
    price: Number = 50000.0,
    fee: Number = 2.6,
    slippagePercent: Number? = null,
    source: String? = null,
    errorMessage: String? = null,
): dynamic = json(
    "symbol" to symbol,
    "side" to side,
    "usdAmount" to usdAmount,
    "success" to success,
    "dryRun" to dryRun,
    "timestamp" to timestamp,
    "volume" to volume,
    "price" to price,
    "fee" to fee,
    "slippagePercent" to slippagePercent,
    "source" to source,
    "errorMessage" to errorMessage,
)

fun mockSnapshotRecord(
    timestamp: String = "2023-01-01",
    totalValueUSD: Any? = 100.0,
    assets: Any? =
        json(
            Asset.BTC to
                json(
                    "valueUSD" to 100,
                    "balance" to 1,
                    "currentPercent" to 100,
                    "deviationPercent" to 0,
                ),
        ),
): dynamic = json(
    "timestamp" to timestamp,
    "totalValueUSD" to totalValueUSD,
    "assets" to assets,
)

fun mockPortfolioStatsRecord(
    allTimeHigh: Number = 15000.5,
    totalTradesExecuted: Number = 42,
    totalVolumeTraded: Number = 1000000.0,
    totalFeesPaid: Number = 250.75,
    avgFeeRatePercent: Number? = 0.26,
    avgSlippagePercent: Number? = 0.15,
): dynamic = json(
    "allTimeHigh" to allTimeHigh,
    "totalTradesExecuted" to totalTradesExecuted,
    "totalVolumeTraded" to totalVolumeTraded,
    "totalFeesPaid" to totalFeesPaid,
    "avgFeeRatePercent" to avgFeeRatePercent,
    "avgSlippagePercent" to avgSlippagePercent,
)

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
