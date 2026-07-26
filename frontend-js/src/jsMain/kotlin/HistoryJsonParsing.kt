package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.api.HistoryStats
import com.gemini.krakenbot.api.PortfolioSnapshot
import com.gemini.krakenbot.api.SyncProgressResponse
import com.gemini.krakenbot.api.TradeRecord
import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.view.util.DataProps
import kotlin.js.JsName
import kotlin.js.json

@JsName("Object")
private external object JsObject {
    fun keys(obj: dynamic): Array<String>
}

private fun dynamicString(value: dynamic): String? {
    if (value == null || value == undefined) return null
    return value.toString()
}

private fun dynamicBoolean(value: dynamic, default: Boolean = false): Boolean = when {
    value == null || value == undefined -> default
    value is Boolean -> value
    else -> value.toString().toBoolean()
}

private fun dynamicInt(value: dynamic): Int? {
    if (value == null || value == undefined) return null
    return value.toString().toDoubleOrNull()?.toInt()
}

private fun dynamicArrayLength(raw: dynamic): Int {
    val length = raw.length
    if (length != null && length != undefined) {
        return length.unsafeCast<Number>().toInt()
    }
    val size = raw.size
    if (size != null && size != undefined) {
        return size.unsafeCast<Number>().toInt()
    }
    return 0
}

private fun dynamicArrayElement(raw: dynamic, index: Int): dynamic {
    val indexed = raw[index]
    if (indexed != null && indexed != undefined) return indexed
    return raw.get(index)
}

private fun parseAssetSnapshot(raw: dynamic): PortfolioSnapshot.AssetSnapshot = PortfolioSnapshot.AssetSnapshot(
    symbol = dynamicString(raw.symbol).orEmpty(),
    balance = dynamicString(raw.balance).orEmpty(),
    price = dynamicString(raw.price).orEmpty(),
    valueUSD = dynamicString(raw.valueUSD).orEmpty(),
    targetPercent = dynamicString(raw.targetPercent).orEmpty(),
    currentPercent = dynamicString(raw.currentPercent).orEmpty(),
    deviationPercent = dynamicString(raw.deviationPercent).orEmpty(),
    deviationUSD = dynamicString(raw.deviationUSD).orEmpty(),
)

private fun parseAssetsMap(raw: dynamic): Map<String, PortfolioSnapshot.AssetSnapshot> {
    if (raw == null || raw == undefined) return emptyMap()
    val keys =
        try {
            JsObject.keys(raw)
        } catch (_: Throwable) {
            emptyArray()
        }
    return keys.associateWith { key -> parseAssetSnapshot(raw[key]) }
}

fun parsePortfolioSnapshot(raw: dynamic): PortfolioSnapshot {
    val actionsRaw = raw.actions
    val actions =
        if (actionsRaw == null || actionsRaw == undefined) {
            emptyList()
        } else {
            val length = dynamicArrayLength(actionsRaw)
            (0 until length).mapNotNull { index -> dynamicString(dynamicArrayElement(actionsRaw, index)) }
        }
    return PortfolioSnapshot(
        timestamp = dynamicString(raw.timestamp).orEmpty(),
        totalValueUSD = dynamicString(raw.totalValueUSD).orEmpty(),
        assets = parseAssetsMap(raw.assets),
        actions = actions,
        drawdownPercent = dynamicString(raw.drawdownPercent).orEmpty(),
        fiatDeploymentPercent = dynamicString(raw.fiatDeploymentPercent).orEmpty(),
        effectiveUsdTargetPercent = dynamicString(raw.effectiveUsdTargetPercent).orEmpty(),
    )
}

fun parsePortfolioSnapshots(raw: dynamic): List<PortfolioSnapshot> {
    if (raw == null || raw == undefined) return emptyList()
    val length = dynamicArrayLength(raw)
    return (0 until length).map { index -> parsePortfolioSnapshot(dynamicArrayElement(raw, index)) }
}

fun parseTradeRecord(raw: dynamic): TradeRecord = TradeRecord(
    timestamp = dynamicString(raw.timestamp).orEmpty(),
    pair = dynamicString(raw.pair).orEmpty(),
    side = dynamicString(raw.side).orEmpty(),
    symbol = dynamicString(raw.symbol).orEmpty(),
    volume = dynamicString(raw.volume).orEmpty(),
    usdAmount = dynamicString(raw.usdAmount).orEmpty(),
    success = dynamicBoolean(raw.success),
    dryRun = dynamicBoolean(raw.dryRun),
    errorMessage = dynamicString(raw.errorMessage),
    price = dynamicString(raw.price) ?: "0",
    fee = dynamicString(raw.fee) ?: "0",
    slippagePercent = dynamicString(raw.slippagePercent),
    expectedPrice = dynamicString(raw.expectedPrice),
    source = dynamicString(raw.source),
    id = dynamicInt(raw.id),
)

fun parseTradeRecords(raw: dynamic): List<TradeRecord> {
    if (raw == null || raw == undefined) return emptyList()
    val length = dynamicArrayLength(raw)
    return (0 until length).map { index -> parseTradeRecord(dynamicArrayElement(raw, index)) }
}

fun parseHistoryStats(raw: dynamic): HistoryStats = HistoryStats(
    allTimeHigh = dynamicString(raw.allTimeHigh).orEmpty(),
    totalTradesExecuted = dynamicString(raw.totalTradesExecuted)?.toLongOrNull() ?: 0L,
    totalVolumeTraded = dynamicString(raw.totalVolumeTraded).orEmpty(),
    totalFeesPaid = dynamicString(raw.totalFeesPaid).orEmpty(),
    latestSnapshotTime = dynamicString(raw.latestSnapshotTime),
    avgFeeRatePercent = dynamicString(raw.avgFeeRatePercent) ?: "0",
    avgSlippagePercent = dynamicString(raw.avgSlippagePercent),
    failedTradeCount = dynamicString(raw.failedTradeCount)?.toLongOrNull() ?: 0L,
    dryRunTradeCount = dynamicString(raw.dryRunTradeCount)?.toLongOrNull() ?: 0L,
)

fun parseSyncProgressResponse(raw: dynamic): SyncProgressResponse = SyncProgressResponse(
    seeded = dynamicBoolean(raw[SyncMetadataKeys.IS_SEEDED]),
    offset = dynamicString(raw[SyncMetadataKeys.OFFSET]).orEmpty(),
    total = dynamicString(raw[SyncMetadataKeys.TOTAL]).orEmpty(),
)

/** Build a dynamic trade JSON object for tests using [DataProps] keys. */
fun tradeRecordToDynamic(trade: TradeRecord): dynamic = json(
    DataProps.TIMESTAMP to trade.timestamp,
    DataProps.SYMBOL to trade.symbol,
    DataProps.SIDE to trade.side,
    "pair" to trade.pair,
    DataProps.VOLUME to trade.volume,
    DataProps.USD_AMOUNT to trade.usdAmount,
    DataProps.SUCCESS to trade.success,
    DataProps.DRY_RUN to trade.dryRun,
    DataProps.PRICE to trade.price,
    DataProps.FEE to trade.fee,
    DataProps.SLIPPAGE_PERCENT to trade.slippagePercent,
    DataProps.EXPECTED_PRICE to trade.expectedPrice,
    DataProps.SOURCE to trade.source,
    DataProps.ERROR_MESSAGE to trade.errorMessage,
    "id" to trade.id,
)

/** Build a dynamic stats JSON object for tests. */
fun historyStatsToDynamic(stats: HistoryStats): dynamic = json(
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

fun portfolioSnapshotToDynamic(snapshot: PortfolioSnapshot): dynamic {
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
