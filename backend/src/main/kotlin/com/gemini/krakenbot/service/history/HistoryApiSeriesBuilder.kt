package com.gemini.krakenbot.service.history

import com.gemini.krakenbot.model.KrakenApiConstants
import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.util.PrecisionConstants
import com.gemini.krakenbot.view.util.ViewText
import java.math.BigDecimal
import java.math.RoundingMode

internal const val HISTORY_API_PORTFOLIO_KEY = "portfolio"
internal const val HISTORY_API_ALLOCATION_UNIT = "percent"

private const val REWARD_SUBTYPE = "reward"
private val REWARD_TRANSFER_SUBTYPES = setOf("airdrop", "reward")

internal fun portfolioSeries(observations: List<HistoryObservation>): List<HistoryApiSeries> = listOf(
    HistoryApiSeries(
        key = HISTORY_API_PORTFOLIO_KEY,
        label = ViewText.HISTORY_API_SERIES_PORTFOLIO,
        unit = observations.firstOrNull()?.valuationCurrency ?: "USD",
        points = observations.map(::historyPoint),
    ),
)

internal fun holdingsSeries(observations: List<HistoryObservation>): List<HistoryApiSeries> {
    val unit = observations.firstOrNull()?.valuationCurrency ?: "USD"
    return assetSeries(observations, unit) { _, asset -> asset.valueUsd }
}

internal fun allocationSeries(observations: List<HistoryObservation>): List<HistoryApiSeries> =
    assetSeries(observations, HISTORY_API_ALLOCATION_UNIT) { observation, asset ->
        val totalUsd = observation.totalUsd ?: return@assetSeries null
        if (totalUsd.signum() <= 0) return@assetSeries null
        asset.valueUsd
            ?.multiply(BigDecimal.valueOf(PrecisionConstants.HUNDRED_INT.toLong()))
            ?.divide(totalUsd, PrecisionConstants.SCALE_PERCENT, RoundingMode.HALF_UP)
    }

internal fun ledgerQuantitySeries(rows: List<HistoryLedger>): List<HistoryApiSeries> {
    val assets = rows.mapNotNull { row -> row.asset?.trim()?.takeIf(String::isNotEmpty) }.distinct()
    return assets.map { asset ->
        HistoryApiSeries(
            key = asset,
            label = asset,
            unit = asset,
            points = rows.mapNotNull { row ->
                if (row.asset?.trim() != asset) return@mapNotNull null
                val observedAt = row.time ?: return@mapNotNull null
                HistoryApiPoint(
                    t = observedAt.toEpochMilli(),
                    isoTime = observedAt.toString(),
                    v = row.amount?.toBigDecimalOrNull()?.takeIf { row.hasValidAmount },
                    complete = row.hasValidAmount && row.amount?.toBigDecimalOrNull() != null,
                )
            },
        )
    }
}

internal fun checkpointQuantitySeries(rows: List<HistoryBalanceCheckpoint>): List<HistoryApiSeries> {
    val assets = rows.mapNotNull { row -> row.rawAssetKey?.trim()?.takeIf(String::isNotEmpty) }.distinct()
    return assets.map { asset ->
        HistoryApiSeries(
            key = asset,
            label = asset,
            unit = asset,
            points = rows.mapNotNull { row ->
                if (row.rawAssetKey?.trim() != asset) return@mapNotNull null
                val observedAt = row.observedAt ?: return@mapNotNull null
                HistoryApiPoint(
                    t = observedAt.toEpochMilli(),
                    isoTime = observedAt.toString(),
                    v = row.quantity,
                    complete = row.status == HistoryBalanceStatus.VALID && row.quantity != null,
                )
            },
        )
    }
}

internal fun HistoryLedger.isRewardRow(): Boolean {
    val type = type?.trim()?.lowercase().orEmpty()
    if (type in LedgerEvent.REWARD_TYPES) return true
    val subtype = subtype?.trim()?.lowercase().orEmpty()
    return (type == KrakenApiConstants.LEDGER_TYPE_EARN && subtype == REWARD_SUBTYPE) ||
        (type == KrakenApiConstants.LEDGER_TYPE_TRANSFER && subtype in REWARD_TRANSFER_SUBTYPES)
}

internal fun HistoryLedger.isDepositRow(): Boolean =
    type?.trim()?.equals(KrakenApiConstants.LEDGER_TYPE_DEPOSIT, ignoreCase = true) == true

private fun assetSeries(
    observations: List<HistoryObservation>,
    unit: String,
    valueOf: (HistoryObservation, HistoryAssetValue) -> BigDecimal?,
): List<HistoryApiSeries> {
    val symbols = observations.flatMap { observation -> observation.assets.map { asset -> asset.symbol } }.distinct()
    return symbols.map { symbol ->
        HistoryApiSeries(
            key = symbol,
            label = symbol,
            unit = unit,
            points = observations.map { observation -> historyPoint(observation, symbol, valueOf) },
        )
    }
}

private fun historyPoint(observation: HistoryObservation): HistoryApiPoint = HistoryApiPoint(
    t = observation.observedAt.toEpochMilli(),
    isoTime = observation.observedAt.toString(),
    v = observation.totalUsd,
    complete = observation.status == HistoryObservationStatus.COMPLETE && observation.totalUsd != null,
)

private fun historyPoint(
    observation: HistoryObservation,
    symbol: String,
    valueOf: (HistoryObservation, HistoryAssetValue) -> BigDecimal?,
): HistoryApiPoint {
    val asset = observation.assets.firstOrNull { it.symbol == symbol }
    val t = observation.observedAt.toEpochMilli()
    val tIso = observation.observedAt.toString()
    if (observation.status != HistoryObservationStatus.COMPLETE || asset?.valueUsd == null) {
        return HistoryApiPoint(t = t, isoTime = tIso, v = null, complete = false)
    }
    val value = valueOf(observation, asset)
    return HistoryApiPoint(t = t, isoTime = tIso, v = value, complete = value != null)
}
