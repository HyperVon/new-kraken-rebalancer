package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.OrderSide
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

internal enum class InferenceOwnership {
    POSITIVE,
    UNKNOWN,
}

internal data class HistoricalInferenceTrade(
    val id: String,
    val timestamp: Instant,
    val pair: String,
    val side: String,
    val symbol: String,
    val volume: BigDecimal,
    val quoteAmount: BigDecimal,
    val orderTxid: String? = null,
    val tradeId: String? = null,
    val ownership: InferenceOwnership = InferenceOwnership.UNKNOWN,
)

internal enum class InferenceStrength {
    HIGH,
    MEDIUM,
    LOW,
}

internal data class HistoricalStrategyStartCandidate(
    val observedStart: Instant,
    val observedEnd: Instant,
    val windowStart: Instant,
    val windowEnd: Instant,
    val orderCount: Int,
    val distinctAssets: Set<String>,
    val strength: InferenceStrength,
    val reasons: List<String>,
    val contradictions: List<String>,
    val timescalesSeconds: Set<Long> = emptySet(),
    val repeatedEvidenceCount: Int = 1,
)

internal data class HistoricalStrategyStartInference(
    val candidates: List<HistoricalStrategyStartCandidate>,
    val firstPositivelyOwnedTrade: Instant?,
    val unsupportedMarkets: List<String>,
    val coverageStart: Instant? = null,
    val coverageEnd: Instant? = null,
) {
    val strongestCandidate: HistoricalStrategyStartCandidate? get() = candidates.firstOrNull()
}

internal data class HistoricalInferencePolicy(
    val episodeGaps: List<Duration> = listOf(
        Duration.ofSeconds(2),
        Duration.ofSeconds(5),
        Duration.ofSeconds(15),
    ),
    val precedingActivityWindow: Duration = Duration.ofMinutes(45),
    val minimumOrders: Int = 2,
    val minimumAssets: Int = 2,
    val maximumCandidates: Int = 8,
)

/** Pure, deterministic evidence extraction. It never promotes UNKNOWN ownership to POSITIVE. */
internal object HistoricalStrategyStartDetector {
    val defaultPolicy = HistoricalInferencePolicy()

    fun infer(
        trades: List<HistoricalInferenceTrade>,
        policy: HistoricalInferencePolicy = defaultPolicy,
    ): HistoricalStrategyStartInference {
        val normalized = trades
            .mapNotNull(::normalize)
            .groupBy(NormalizedTrade::orderIdentity)
            .values
            .map(::collapseOrder)
            .sortedWith(compareBy<NormalizedTrade> { it.timestamp }.thenBy { it.orderIdentity })

        val unsupportedMarkets = trades
            .mapNotNull { trade ->
                val pair = trade.pair.trim().uppercase()
                pair.takeIf { it.isNotEmpty() && Asset.fromTradingPair(it, emptyList()) == null }
            }
            .distinct()
            .sorted()

        if (normalized.isEmpty()) {
            return HistoricalStrategyStartInference(
                candidates = emptyList(),
                firstPositivelyOwnedTrade = null,
                unsupportedMarkets = unsupportedMarkets,
            )
        }

        val rawCandidates = policy.episodeGaps
            .distinct()
            .sorted()
            .flatMap { gap ->
                buildEpisodes(normalized, gap).mapNotNull { episode ->
                    candidateFor(episode, normalized, policy)
                        ?.copy(timescalesSeconds = setOf(gap.seconds))
                }
            }

        val enrichedCandidates = rawCandidates.map { candidate ->
            val matching = rawCandidates.filter { it.matchesEvidence(candidate) }
            val timescales = matching.flatMapTo(linkedSetOf()) { it.timescalesSeconds }
            val repeatedCount = matching.map { it.observedStart }.distinct().size
            val reasons = buildList {
                addAll(candidate.reasons)
                if (timescales.size > 1) add("MULTI_TIMESCALE_MATCH")
                if (repeatedCount > 1) add("REPEATED_EPISODE_EVIDENCE")
            }.distinct()
            candidate.copy(
                timescalesSeconds = timescales,
                repeatedEvidenceCount = maxOf(candidate.repeatedEvidenceCount, repeatedCount),
                reasons = reasons,
                strength = strengthFor(candidate, timescales.size, repeatedCount),
            )
        }

        val ranked = enrichedCandidates
            .distinctBy { candidate ->
                listOf(
                    candidate.observedStart,
                    candidate.observedEnd,
                    candidate.windowStart,
                    candidate.windowEnd,
                    candidate.timescalesSeconds,
                )
            }
            .sortedWith(
                compareByDescending<HistoricalStrategyStartCandidate> { it.strength.rank }
                    .thenByDescending { it.repeatedEvidenceCount }
                    .thenByDescending { it.distinctAssets.size }
                    .thenBy { it.observedStart },
            )
            .take(policy.maximumCandidates)

        return HistoricalStrategyStartInference(
            candidates = ranked,
            firstPositivelyOwnedTrade = normalized
                .filter { it.ownership == InferenceOwnership.POSITIVE }
                .minOfOrNull { it.timestamp },
            unsupportedMarkets = unsupportedMarkets,
            coverageStart = normalized.firstOrNull()?.timestamp,
            coverageEnd = normalized.lastOrNull()?.timestamp,
        )
    }

    private fun buildEpisodes(trades: List<NormalizedTrade>, gap: Duration): List<List<NormalizedTrade>> {
        val episodes = mutableListOf<MutableList<NormalizedTrade>>()
        var current = mutableListOf<NormalizedTrade>()
        for (trade in trades) {
            if (current.isEmpty() || Duration.between(current.last().timestamp, trade.timestamp) <= gap) {
                current += trade
            } else {
                episodes += current
                current = mutableListOf(trade)
            }
        }
        if (current.isNotEmpty()) episodes += current
        return episodes
    }

    private fun candidateFor(
        episode: List<NormalizedTrade>,
        allTrades: List<NormalizedTrade>,
        policy: HistoricalInferencePolicy,
    ): HistoricalStrategyStartCandidate? {
        val distinctAssets = episode.map(NormalizedTrade::baseAsset).toSet()
        if (episode.size < policy.minimumOrders || distinctAssets.size < policy.minimumAssets) return null

        val hasBuy = episode.any { OrderSide.isBuy(it.side) }
        val hasSell = episode.any { OrderSide.isSell(it.side) }
        val sellBeforeBuy = hasBuy && hasSell && episode.indexOfFirst { OrderSide.isBuy(it.side) } >
            episode.indexOfLast { OrderSide.isSell(it.side) }
        val episodeStart = episode.first().timestamp
        val prior = allTrades.filter {
            it.timestamp.isBefore(episodeStart) &&
                !it.timestamp.isBefore(episodeStart.minus(policy.precedingActivityWindow))
        }
        val reasons = buildList {
            add("MULTI_ASSET_EPISODE")
            if (hasBuy && hasSell) add("BUY_SELL_ADJUSTMENT")
            if (sellBeforeBuy) add("SELL_BEFORE_BUY")
            if (episode.any { it.ownership == InferenceOwnership.UNKNOWN }) add("UNKNOWN_OWNERSHIP")
            if (!hasSell) add("PURCHASE_ONLY_EPISODE")
        }
        val contradictions = buildList {
            if (prior.isNotEmpty()) add("EARLIER_ACTIVITY_IN_WINDOW")
        }
        val baseStrength = when {
            distinctAssets.size >= 4 && hasBuy && hasSell -> InferenceStrength.HIGH
            distinctAssets.size >= 3 && hasBuy && hasSell -> InferenceStrength.MEDIUM
            else -> InferenceStrength.LOW
        }
        return HistoricalStrategyStartCandidate(
            observedStart = episodeStart,
            observedEnd = episode.last().timestamp,
            windowStart = prior.firstOrNull()?.timestamp ?: episodeStart,
            windowEnd = episode.last().timestamp,
            orderCount = episode.size,
            distinctAssets = distinctAssets,
            strength = baseStrength,
            reasons = reasons,
            contradictions = contradictions,
        )
    }

    private fun normalize(trade: HistoricalInferenceTrade): NormalizedTrade? {
        if (trade.id.isBlank() || trade.volume.signum() <= 0 || trade.quoteAmount.signum() <= 0) return null
        val pair = trade.pair.trim().uppercase()
        val baseAsset = Asset.fromTradingPair(pair, emptyList()) ?: return null
        return NormalizedTrade(
            orderIdentity = trade.orderTxid?.trim()?.takeIf(String::isNotEmpty) ?: "fill:${trade.id}",
            timestamp = trade.timestamp,
            baseAsset = baseAsset,
            side = trade.side,
            ownership = trade.ownership,
        )
    }

    private fun collapseOrder(fills: List<NormalizedTrade>): NormalizedTrade {
        val first = fills.minWith(compareBy<NormalizedTrade> { it.timestamp }.thenBy { it.orderIdentity })
        return first.copy(
            ownership = if (fills.any { it.ownership == InferenceOwnership.POSITIVE }) {
                InferenceOwnership.POSITIVE
            } else {
                InferenceOwnership.UNKNOWN
            },
        )
    }

    private fun HistoricalStrategyStartCandidate.matchesEvidence(other: HistoricalStrategyStartCandidate): Boolean =
        distinctAssets == other.distinctAssets &&
            orderCount == other.orderCount &&
            reasons.any { it == "BUY_SELL_ADJUSTMENT" } == other.reasons.any { it == "BUY_SELL_ADJUSTMENT" } &&
            reasons.any { it == "PURCHASE_ONLY_EPISODE" } == other.reasons.any { it == "PURCHASE_ONLY_EPISODE" }

    private fun strengthFor(
        candidate: HistoricalStrategyStartCandidate,
        timescaleCount: Int,
        repeatedCount: Int,
    ): InferenceStrength = when {
        candidate.strength == InferenceStrength.HIGH || (timescaleCount > 1 && repeatedCount > 1) ->
            InferenceStrength.HIGH

        candidate.strength == InferenceStrength.MEDIUM || timescaleCount > 1 || repeatedCount > 1 ->
            InferenceStrength.MEDIUM

        else -> candidate.strength
    }

    private val InferenceStrength.rank: Int
        get() = when (this) {
            InferenceStrength.HIGH -> 3
            InferenceStrength.MEDIUM -> 2
            InferenceStrength.LOW -> 1
        }

    private data class NormalizedTrade(
        val orderIdentity: String,
        val timestamp: Instant,
        val baseAsset: String,
        val side: String,
        val ownership: InferenceOwnership,
    )
}
