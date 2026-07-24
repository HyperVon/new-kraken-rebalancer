package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.OrderSide
import kotlin.js.Promise
import kotlin.js.json

inline fun jsObject(builder: dynamic.() -> Unit = {}): dynamic = json().apply(builder)

fun mockTradeRecord(
    symbol: String? = Asset.BTC,
    side: String = OrderSide.BUY.name,
    usdAmount: Number = 100.0,
    success: Boolean = true,
    dryRun: Boolean = false,
    timestamp: String = "2023-01-01",
    volume: Number = 1.0,
): dynamic =
    json(
        "symbol" to symbol,
        "side" to side,
        "usdAmount" to usdAmount,
        "success" to success,
        "dryRun" to dryRun,
        "timestamp" to timestamp,
        "volume" to volume,
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
): dynamic =
    json(
        "timestamp" to timestamp,
        "totalValueUSD" to totalValueUSD,
        "assets" to assets,
    )

fun mockPortfolioStatsRecord(
    allTimeHigh: Number = 15000.5,
    totalTradesExecuted: Number = 42,
    totalVolumeTraded: Number = 1000000.0,
    totalFeesPaid: Number = 250.75,
): dynamic =
    json(
        "allTimeHigh" to allTimeHigh,
        "totalTradesExecuted" to totalTradesExecuted,
        "totalVolumeTraded" to totalVolumeTraded,
        "totalFeesPaid" to totalFeesPaid,
    )

fun mockFetch(handler: (String) -> Any?): dynamic =
    { url: String ->
        val responseData = handler(url)
        Promise.resolve(json("json" to { Promise.resolve(responseData) }))
    }

fun mockChartConstructor(onConfig: (dynamic) -> Unit = {}): dynamic =
    { _: dynamic, config: dynamic ->
        onConfig(config)
        jsObject {
            data = config.data
            destroy = { asDynamic().destroyed = true }
            isDatasetVisible = { index: Int -> index == 0 }
        }
    }

fun defineGetter(
    obj: Any,
    prop: String,
    getter: () -> Any?,
    configurable: Boolean = true,
) {
    js("Object").defineProperty(
        obj,
        prop,
        jsObject {
            get = getter
            this.configurable = configurable
        },
    )
}
