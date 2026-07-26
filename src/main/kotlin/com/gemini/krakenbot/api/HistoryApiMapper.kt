package com.gemini.krakenbot.api

import java.math.BigDecimal
import com.gemini.krakenbot.model.HistoryStats as DomainHistoryStats
import com.gemini.krakenbot.model.PortfolioSnapshot as DomainPortfolioSnapshot
import com.gemini.krakenbot.model.TradeRecord as DomainTradeRecord

private fun BigDecimal.toApiString(): String = toPlainString()

private fun BigDecimal?.toApiStringOrNull(): String? = this?.toPlainString()

fun DomainPortfolioSnapshot.toApiDto(): PortfolioSnapshot = PortfolioSnapshot(
    timestamp = timestamp.toString(),
    totalValueUSD = totalValueUSD.toApiString(),
    assets = assets.mapValues { (_, asset) -> asset.toApiDto() },
    actions = actions,
    drawdownPercent = drawdownPercent.toApiString(),
    fiatDeploymentPercent = fiatDeploymentPercent.toApiString(),
    effectiveUsdTargetPercent = effectiveUsdTargetPercent.toApiString(),
)

fun DomainPortfolioSnapshot.AssetSnapshot.toApiDto(): PortfolioSnapshot.AssetSnapshot = PortfolioSnapshot.AssetSnapshot(
    symbol = symbol.value,
    balance = balance.toApiString(),
    price = price.toApiString(),
    valueUSD = valueUSD.toApiString(),
    targetPercent = targetPercent.toApiString(),
    currentPercent = currentPercent.toApiString(),
    deviationPercent = deviationPercent.toApiString(),
    deviationUSD = deviationUSD.toApiString(),
)

fun DomainTradeRecord.toApiDto(): TradeRecord = TradeRecord(
    timestamp = timestamp.toString(),
    pair = pair,
    side = side,
    symbol = symbol,
    volume = volume.toApiString(),
    usdAmount = usdAmount.toApiString(),
    success = success,
    dryRun = dryRun,
    errorMessage = errorMessage,
    price = price.toApiString(),
    fee = fee.toApiString(),
    slippagePercent = slippagePercent.toApiStringOrNull(),
    expectedPrice = expectedPrice.toApiStringOrNull(),
    source = source?.name,
    id = id,
)

fun DomainHistoryStats.toApiDto(): HistoryStats = HistoryStats(
    allTimeHigh = allTimeHigh.toApiString(),
    totalTradesExecuted = totalTradesExecuted,
    totalVolumeTraded = totalVolumeTraded.toApiString(),
    totalFeesPaid = totalFeesPaid.toApiString(),
    latestSnapshotTime = latestSnapshotTime?.toString(),
    avgFeeRatePercent = avgFeeRatePercent.toApiString(),
    avgSlippagePercent = avgSlippagePercent.toApiStringOrNull(),
    failedTradeCount = failedTradeCount,
    dryRunTradeCount = dryRunTradeCount,
)

fun buildSyncProgressResponse(seeded: Boolean, offset: String?, total: String?): SyncProgressResponse =
    SyncProgressResponse(
        seeded = seeded,
        offset = offset.orEmpty(),
        total = total.orEmpty(),
    )
