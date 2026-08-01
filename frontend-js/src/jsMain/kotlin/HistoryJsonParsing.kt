package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.api.HistoryStats
import com.gemini.krakenbot.api.PortfolioSnapshot
import com.gemini.krakenbot.api.RebalancerComparison
import com.gemini.krakenbot.api.RebalancerComparisonPoint
import com.gemini.krakenbot.api.SyncProgressResponse
import com.gemini.krakenbot.api.TradeRecord
import com.gemini.krakenbot.model.ComparisonAvailability
import com.gemini.krakenbot.model.ComparisonUnavailableReason
import com.gemini.krakenbot.model.SyncMetadataKeys
import kotlin.js.Date
import kotlin.js.JsName

@JsName("Object")
private external object JsObject {
    fun keys(obj: dynamic): Array<String>
}

private fun dynamicString(value: dynamic): String? {
    if (value == null || value == undefined) return null
    return value.toString()
}

private fun dynamicBoolean(value: dynamic, default: Boolean = false): Boolean = when (value) {
    null, undefined -> default
    is Boolean -> value
    else -> value.toString().toBoolean()
}

private fun dynamicInt(value: dynamic): Int? {
    if (value == null || value == undefined) return null
    return value.toString().toDoubleOrNull()?.toInt()
}

// Payload numbers may arrive as JS numbers OR strings (BigDecimal serializes as text).
// Try a numeric parse first; otherwise treat the value as an ISO timestamp → epoch ms.
internal fun dynamicNumber(value: dynamic): Double? {
    if (value == null || value == undefined) return null
    value.toString().toDoubleOrNull()?.let { parsed -> return parsed.takeIf { it.isFinite() } }
    return Date(value.toString()).getTime().takeIf { it.isFinite() }
}

private fun dynamicArrayLength(raw: dynamic): Int {
    val length = raw.length
    if (length != null && length != undefined) {
        return length.unsafeCast<Number>().toInt()
    }
    return 0
}

private fun <T> parseArray(raw: dynamic, transform: (dynamic) -> T?): List<T> {
    if (raw == null || raw == undefined) return emptyList()
    val length = dynamicArrayLength(raw)
    return (0 until length).mapNotNull { index -> transform(raw[index]) }
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
    val actions = parseArray(actionsRaw, ::dynamicString)
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

fun parsePortfolioSnapshots(raw: dynamic): List<PortfolioSnapshot> = parseArray(raw, ::parsePortfolioSnapshot)

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

fun parseTradeRecords(raw: dynamic): List<TradeRecord> = parseArray(raw, ::parseTradeRecord)

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

fun parseRebalancerComparisonPoint(raw: dynamic): RebalancerComparisonPoint = RebalancerComparisonPoint(
    timestamp = dynamicString(raw.timestamp).orEmpty(),
    rebalancerValueUSD = dynamicString(raw.rebalancerValueUSD).orEmpty(),
    buyAndHoldValueUSD = dynamicString(raw.buyAndHoldValueUSD).orEmpty(),
    differenceUSD = dynamicString(raw.differenceUSD).orEmpty(),
    differencePercent = dynamicString(raw.differencePercent).orEmpty(),
)

fun parseRebalancerComparison(raw: dynamic): RebalancerComparison {
    if (raw == null || raw == undefined) {
        return RebalancerComparison(
            availability = ComparisonAvailability.UNAVAILABLE.name,
            confidence = null,
            baselineTimestamp = null,
            points = emptyList(),
            latestDifferenceUSD = null,
            latestDifferencePercent = null,
            unavailableReason = ComparisonUnavailableReason.INSUFFICIENT_SNAPSHOTS.name,
            unavailableAt = null,
        )
    }
    val availability =
        if (dynamicString(raw.availability) == ComparisonAvailability.AVAILABLE.name) {
            ComparisonAvailability.AVAILABLE.name
        } else {
            ComparisonAvailability.UNAVAILABLE.name
        }
    val pointsRaw = raw.points
    val points = parseArray(pointsRaw, ::parseRebalancerComparisonPoint)
    return RebalancerComparison(
        availability = availability,
        confidence = dynamicString(raw.confidence),
        baselineTimestamp = dynamicString(raw.baselineTimestamp),
        points = points,
        latestDifferenceUSD = dynamicString(raw.latestDifferenceUSD),
        latestDifferencePercent = dynamicString(raw.latestDifferencePercent),
        unavailableReason = dynamicString(raw.unavailableReason),
        unavailableAt = dynamicString(raw.unavailableAt),
    )
}
