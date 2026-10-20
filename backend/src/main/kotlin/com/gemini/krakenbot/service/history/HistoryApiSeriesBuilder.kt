package com.gemini.krakenbot.service.history

import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.KrakenApiConstants
import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.util.PrecisionConstants
import com.gemini.krakenbot.view.util.ViewText
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

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

internal fun recordedScopeBoundaries(
    scopes: List<HistoryApiScope>,
    window: HistoryApiWindow,
): List<HistoryApiBoundary> {
    val spans = scopes.mapNotNull { scope ->
        val first = scope.earliestIso?.let(Instant::parse) ?: return@mapNotNull null
        val last = scope.latestIso?.let(Instant::parse) ?: return@mapNotNull null
        Triple(scope.id, first, last)
    }.sortedWith(compareBy({ it.second }, { it.first }))
    val boundaries = mutableListOf<HistoryApiBoundary>()
    fun add(kind: HistoryApiBoundaryKind, from: Instant, to: Instant, ids: List<String>) {
        if (from >= window.until || (window.from != null && to < window.from)) return
        boundaries += HistoryApiBoundary(
            kind = kind,
            fromIso = maxOf(from, window.from ?: from).toString(),
            toIso = minOf(to, window.until.minusNanos(1)).toString(),
            scopeIds = ids,
        )
    }
    spans.forEachIndexed { index, first ->
        spans.drop(index + 1).forEach { second ->
            val from = maxOf(first.second, second.second)
            val to = minOf(first.third, second.third)
            if (from <= to) {
                add(HistoryApiBoundaryKind.OVERLAPPING_SCOPES, from, to, listOf(first.first, second.first))
            }
        }
    }
    spans.zipWithNext().forEach { (first, second) ->
        if (first.third < second.second) {
            add(
                HistoryApiBoundaryKind.ASSET_SET_CHANGE,
                first.third,
                second.second,
                listOf(first.first, second.first),
            )
        }
    }
    return boundaries.sortedWith(compareBy({ Instant.parse(it.fromIso) }, { it.kind.name }))
}

internal fun recordedPortfolioSeries(
    scope: HistoryApiScope,
    observations: List<HistoryObservation>,
    boundaries: List<HistoryApiBoundary>,
): HistoryApiSeries {
    val conflicts = boundaries.filter {
        it.kind == HistoryApiBoundaryKind.OVERLAPPING_SCOPES && scope.id in it.scopeIds
    }.map { Instant.parse(it.fromIso) to Instant.parse(it.toIso) }
    // Scope lifespans are conservative conflict windows, not evidence of interchangeable account totals.
    val points = observations.map { observation ->
        val point = historyPoint(observation)
        if (conflicts.any { (from, to) -> observation.observedAt >= from && observation.observedAt <= to }) {
            point.copy(v = null, complete = false)
        } else {
            point
        }
    } + conflicts.flatMap { (from, to) ->
        listOf(from, to).map { at ->
            HistoryApiPoint(t = at.toEpochMilli(), isoTime = at.toString(), v = null, complete = false)
        }
    }
    val unit = observations.firstOrNull()?.valuationCurrency ?: "USD"
    return HistoryApiSeries(
        key = scope.id,
        label = "${scope.symbols.joinToString(" + ")} | ${scope.earliestIso ?: "?"} .. ${scope.latestIso ?: "?"}",
        legendLabel = scope.symbols.joinToString(" + ") + if (scope.current) " (current)" else "",
        unit = unit,
        points = points.distinctBy { it.isoTime }.sortedBy { Instant.parse(it.isoTime) },
        provenance = HistoryApiSeriesProvenance(
            scope = scope,
            walletScopes = observations.map { it.walletScope }.distinct().sorted(),
            balanceSources = observations.map { it.balanceSource }.distinct().sorted(),
            priceSources = observations.map { it.priceSource }.distinct().sorted(),
            valuationCurrency = unit,
        ),
    )
}

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
            label = Asset.normalizeLedgerAsset(asset),
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
            label = Asset.normalizeLedgerAsset(asset),
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
