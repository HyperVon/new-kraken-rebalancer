package com.gemini.krakenbot.service.history

import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.KrakenApiConstants
import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.service.reporting.allocationDeviationPercent
import com.gemini.krakenbot.view.util.ViewText
import java.math.BigDecimal
import java.time.Duration
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

/**
 * One chronological recording episode: consecutive observations of the same recorded asset set.
 * A configuration change that later returns to an earlier asset set produces separate episodes
 * instead of one continuous lifespan, so valid history is never hidden by a reused fingerprint.
 */
internal data class HistoryRecordedEpisode(
    val scopeId: String,
    val from: Instant,
    val until: Instant,
    val observations: List<HistoryObservation>,
)

/** Episodes of every recorded scope, ordered by observation time. */
internal fun recordedEpisodes(
    observationsByScope: Map<String, List<HistoryObservation>>,
): List<HistoryRecordedEpisode> {
    val timeline = observationsByScope.values.flatten()
        .sortedWith(compareBy({ it.observedAt }, { it.sourceScope.sourceKey }))
    val episodes = mutableListOf<MutableEpisode>()
    for (observation in timeline) {
        val scopeId = observation.sourceScope.sourceKey
        val last = episodes.lastOrNull()
        if (last != null && last.scopeId == scopeId) {
            last.until = observation.observedAt
            last.observations += observation
        } else {
            episodes +=
                MutableEpisode(scopeId, observation.observedAt, observation.observedAt, mutableListOf(observation))
        }
    }
    return episodes.map { episode ->
        HistoryRecordedEpisode(episode.scopeId, episode.from, episode.until, episode.observations)
    }
}

private class MutableEpisode(
    val scopeId: String,
    val from: Instant,
    var until: Instant,
    val observations: MutableList<HistoryObservation>,
)

/**
 * Instants at which two different recorded scopes both observed the account. Only simultaneous
 * evidence is incompatible; a scope that returns later does not invalidate the episode in between.
 */
internal fun recordedConflictInstants(observationsByScope: Map<String, List<HistoryObservation>>): Set<Instant> =
    observationsByScope.values.flatten()
        .groupBy { it.observedAt }
        .filter { (_, group) -> group.map { it.sourceScope.sourceKey }.distinct().size > 1 }
        .keys

internal fun recordedScopeBoundaries(
    episodes: List<HistoryRecordedEpisode>,
    conflicts: Set<Instant>,
    window: HistoryApiWindow,
): List<HistoryApiBoundary> {
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
    episodes.zipWithNext().forEach { (first, second) ->
        if (first.scopeId == second.scopeId) return@forEach
        if (first.until < second.from) {
            add(
                HistoryApiBoundaryKind.ASSET_SET_CHANGE,
                first.until,
                second.from,
                listOf(first.scopeId, second.scopeId),
            )
        }
    }
    conflicts.sorted().forEach { instant ->
        val ids = episodes.filter { episode -> episode.observations.any { it.observedAt == instant } }
            .map { it.scopeId }
            .distinct()
        add(HistoryApiBoundaryKind.OVERLAPPING_SCOPES, instant, instant, ids)
    }
    return boundaries.sortedWith(compareBy({ Instant.parse(it.fromIso) }, { it.kind.name }))
}

/**
 * One recorded scope as its own series. Every episode keeps its direct observations, and a break
 * marker separates episodes so a line never spans an asset-set change.
 */
internal fun recordedPortfolioSeries(
    scope: HistoryApiScope,
    episodes: List<HistoryRecordedEpisode>,
    conflicts: Set<Instant>,
): HistoryApiSeries {
    val scopeEpisodes = episodes.filter { it.scopeId == scope.id }
    val points = mutableListOf<HistoryApiPoint>()
    scopeEpisodes.forEachIndexed { index, episode ->
        if (index > 0) {
            val previous = scopeEpisodes[index - 1]
            val breakAt = episodeBreakAt(previous.until, episode.from)
            points += HistoryApiPoint(
                t = breakAt.toEpochMilli(),
                isoTime = breakAt.toString(),
                v = null,
                complete = false,
            )
        }
        episode.observations.forEach { observation ->
            val conflicted = observation.observedAt in conflicts
            points += if (conflicted) {
                HistoryApiPoint(
                    t = observation.observedAt.toEpochMilli(),
                    isoTime = observation.observedAt.toString(),
                    v = null,
                    complete = false,
                )
            } else {
                historyPoint(observation)
            }
        }
    }
    val observations = scopeEpisodes.flatMap { it.observations }
    val unit = observations.firstOrNull()?.valuationCurrency ?: "USD"
    return HistoryApiSeries(
        key = scope.id,
        label = "${scope.symbols.joinToString(" + ")} | ${scope.earliestIso ?: "?"} .. ${scope.latestIso ?: "?"}",
        legendLabel = scope.symbols.joinToString(" + ") + if (scope.current) " (current)" else "",
        unit = unit,
        points = points.sortedBy { Instant.parse(it.isoTime) },
        provenance = HistoryApiSeriesProvenance(
            scope = scope,
            walletScopes = observations.map { it.walletScope }.distinct().sorted(),
            balanceSources = observations.map { it.balanceSource }.distinct().sorted(),
            priceSources = observations.map { it.priceSource }.distinct().sorted(),
            valuationCurrency = unit,
        ),
    )
}

/** A break marker strictly between two episodes, so the line is cut without losing a sample. */
private fun episodeBreakAt(previousUntil: Instant, nextFrom: Instant): Instant {
    if (!nextFrom.isAfter(previousUntil)) return previousUntil
    val gap = Duration.between(previousUntil, nextFrom)
    return previousUntil.plus(gap.dividedBy(2))
}

internal fun holdingsSeries(observations: List<HistoryObservation>): List<HistoryApiSeries> {
    val unit = observations.firstOrNull()?.valuationCurrency ?: "USD"
    return assetSeries(observations, unit) { _, asset -> asset.valueUsd }
}

/**
 * Allocation deviation in percentage points: the observed share minus the configured target.
 * The same helper serves the server-rendered chart, so the refreshed chart never changes meaning.
 */
internal fun allocationSeries(
    observations: List<HistoryObservation>,
    targets: Map<String, BigDecimal>,
): List<HistoryApiSeries> {
    val ordered = observations.sortedBy { it.observedAt }
    val symbols = (targets.keys + ordered.flatMap { observation -> observation.assets.map { it.symbol } })
        .toSortedSet()
    return symbols.map { symbol ->
        val target = targets[symbol] ?: BigDecimal.ZERO
        HistoryApiSeries(
            key = symbol,
            label = symbol,
            unit = HISTORY_API_ALLOCATION_UNIT,
            points = ordered.map { observation ->
                val deviation = allocationDeviationPercent(observation, symbol, target)
                HistoryApiPoint(
                    t = observation.observedAt.toEpochMilli(),
                    isoTime = observation.observedAt.toString(),
                    v = deviation,
                    complete = deviation != null,
                )
            },
        )
    }
}

/**
 * Cumulative native quantities per asset. Every event contributes its signed amount to a running
 * total, so a withdrawal reduces the funding curve instead of disappearing from it.
 */
internal fun ledgerQuantitySeries(
    rows: List<HistoryLedger>,
    signedAmountOf: (HistoryLedger) -> BigDecimal?,
    from: Instant? = null,
    until: Instant? = null,
): List<HistoryApiSeries> {
    val assets = rows.mapNotNull { row -> row.asset?.trim()?.takeIf(String::isNotEmpty) }.distinct()
    return assets.mapNotNull { asset ->
        val events = rows.filter { row -> row.asset?.trim() == asset }
        val points = cumulativeLedgerPoints(events, signedAmountOf, from, until)
        if (points.isEmpty()) {
            null
        } else {
            HistoryApiSeries(
                key = asset,
                label = Asset.normalizeLedgerAsset(asset),
                unit = asset,
                points = points,
            )
        }
    }
}

/** Signed amount of a funding event: a deposit adds capital, a withdrawal removes it. */
internal fun fundingAmountOf(row: HistoryLedger): BigDecimal? {
    val amount = row.amount?.toBigDecimalOrNull()?.takeIf { row.hasValidAmount } ?: return null
    return when (row.type?.trim()?.lowercase()) {
        KrakenApiConstants.LEDGER_TYPE_DEPOSIT -> amount.abs()
        KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL -> amount.abs().negate()
        else -> null
    }
}

/** Amount of a reward event; reward rows only. */
internal fun rewardAmountOf(row: HistoryLedger): BigDecimal? {
    if (!row.isRewardRow()) return null
    return row.amount?.toBigDecimalOrNull()?.takeIf { row.hasValidAmount }
}

/** Whether a ledger row is an owner-capital event that belongs to the funding series. */
internal fun HistoryLedger.isFundingRow(): Boolean = type?.trim()?.lowercase() in setOf(
    KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
    KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL,
)

/**
 * Running total of one asset's events, ordered by event time and then by the stable ledger id. An
 * event with an unusable amount stays visible as an incomplete point instead of a fabricated value.
 */
private fun cumulativeLedgerPoints(
    rows: List<HistoryLedger>,
    signedAmountOf: (HistoryLedger) -> BigDecimal?,
    from: Instant?,
    until: Instant?,
): List<HistoryApiPoint> {
    val events = rows.mapNotNull { row ->
        val observedAt = row.time ?: return@mapNotNull null
        Triple(observedAt, row.ledgerId, signedAmountOf(row))
    }.sortedWith(compareBy({ it.first }, { it.second }))
    var cumulative = BigDecimal.ZERO
    return events.mapNotNull { (observedAt, _, amount) ->
        if (amount != null) cumulative = cumulative.add(amount)
        if (from != null && observedAt.isBefore(from)) return@mapNotNull null
        if (until != null && !observedAt.isBefore(until)) return@mapNotNull null
        HistoryApiPoint(
            t = observedAt.toEpochMilli(),
            isoTime = observedAt.toString(),
            v = amount?.let { cumulative },
            complete = amount != null,
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
                val valid = row.status == HistoryBalanceStatus.VALID
                HistoryApiPoint(
                    t = observedAt.toEpochMilli(),
                    isoTime = observedAt.toString(),
                    v = row.quantity.takeIf { valid },
                    complete = valid && row.quantity != null,
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
