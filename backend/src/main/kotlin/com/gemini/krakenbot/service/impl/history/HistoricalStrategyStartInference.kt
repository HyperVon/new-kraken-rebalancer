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

/**
 * Inception semantics are separate operations from candidate presentation ranking. The earliest
 * strategy-start-eligible candidate anchors [inferredStart]; its evidence is carried separately in
 * [inferredStartStrength], [inferredStartReasons], and [inferredStartContradictions]. The
 * highest-ranked candidate stays visible as [strongestObservedStart] with its own
 * [strongestEpisodeStrength], [strongestEpisodeReasons], and [strongestEpisodeContradictions], so
 * a strong later episode is never mistaken for evidence about the estimated start. Candidates that
 * are not strategy-start-eligible remain as ambiguous activity via [earliestAmbiguousStart] and
 * [earlierAmbiguousCandidateCount] instead of silently claiming the start.
 */
internal data class HistoricalStrategyStartInference(
    val candidates: List<HistoricalStrategyStartCandidate>,
    val inferredStart: Instant?,
    val inferredWindowStart: Instant?,
    val inferredWindowEnd: Instant?,
    val inferredStartStrength: InferenceStrength?,
    val inferredStartReasons: List<String>,
    val inferredStartContradictions: List<String>,
    val strongestObservedStart: Instant?,
    val strongestEpisodeStrength: InferenceStrength?,
    val strongestEpisodeReasons: List<String>,
    val strongestEpisodeContradictions: List<String>,
    val earliestAmbiguousStart: Instant?,
    val earlierAmbiguousCandidateCount: Int,
    val competingCandidateCount: Int,
    val firstPositivelyOwnedTrade: Instant?,
    val unsupportedMarkets: List<String>,
    val coverageStart: Instant? = null,
    val coverageEnd: Instant? = null,
)

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
    val cohesionWindow: Duration = Duration.ofHours(1),
)

/** Pure, deterministic evidence extraction. It never promotes UNKNOWN ownership to POSITIVE. */
internal object HistoricalStrategyStartDetector {
    val defaultPolicy = HistoricalInferencePolicy()

    private const val REASON_MULTI_ASSET = "MULTI_ASSET_EPISODE"
    private const val REASON_REDISTRIBUTION = "REDISTRIBUTION_SELL_THEN_BUY"
    private const val REASON_MIXED = "MIXED_SIDE_EPISODE"
    private const val REASON_PURCHASE_ONLY = "PURCHASE_ONLY_EPISODE"
    private const val REASON_SELL_ONLY = "SELL_ONLY_EPISODE"
    private const val REASON_UNIFORM_QUOTE = "UNIFORM_QUOTE_AMOUNTS"
    private const val REASON_SELL_BEFORE_BUY = "SELL_BEFORE_BUY"
    private const val REASON_UNKNOWN_OWNERSHIP = "UNKNOWN_OWNERSHIP"
    private const val REASON_MULTI_TIMESCALE = "MULTI_TIMESCALE_MATCH"
    private const val REASON_REPEATED_EPISODE = "REPEATED_EPISODE_EVIDENCE"
    private const val CONTRADICTION_EARLIER_ACTIVITY = "EARLIER_ACTIVITY_IN_WINDOW"

    fun infer(
        trades: List<HistoricalInferenceTrade>,
        policy: HistoricalInferencePolicy = defaultPolicy,
    ): HistoricalStrategyStartInference {
        val ownershipValidTrades = trades.filter(::isOwnershipValid)
        val economicsValidTrades = ownershipValidTrades.filter(::isEconomicsValid)
        val normalized = economicsValidTrades
            .mapNotNull(::normalize)
            .groupBy(NormalizedTrade::orderIdentity)
            .values
            .map(::collapseOrder)
            .sortedWith(compareBy<NormalizedTrade> { it.timestamp }.thenBy { it.orderIdentity })

        val unsupportedMarkets = ownershipValidTrades
            .mapNotNull { trade ->
                val pair = trade.pair.trim().uppercase()
                pair.takeIf { it.isNotEmpty() && Asset.fromTradingPair(it, emptyList()) == null }
            }
            .distinct()
            .sorted()

        val firstPositive = firstPositivelyOwnedTrade(ownershipValidTrades)

        if (normalized.isEmpty()) {
            return emptyInference(
                firstPositive = firstPositive,
                unsupportedMarkets = unsupportedMarkets,
                coverage = ownershipValidTrades.coverageSpan(),
            )
        }

        val rawCandidates = policy.episodeGaps
            .distinct()
            .sorted()
            .flatMap { gap ->
                buildEpisodes(normalized, gap).mapNotNull { episode ->
                    candidateFor(episode, normalized, policy)?.copy(timescalesSeconds = setOf(gap.seconds))
                }
            }

        val enrichedCandidates = rawCandidates.map { candidate ->
            val timescales = rawCandidates
                .filter { it.matchesEvidence(candidate) }
                .flatMapTo(linkedSetOf()) { it.timescalesSeconds }
            val repeatedCount = rawCandidates
                .filter { it.timescalesSeconds == candidate.timescalesSeconds && it.matchesEvidence(candidate) }
                .map { it.observedStart }
                .distinct()
                .size
            val reasons = buildList {
                addAll(candidate.reasons)
                if (timescales.size > 1) add(REASON_MULTI_TIMESCALE)
                if (repeatedCount > 1) add(REASON_REPEATED_EPISODE)
            }.distinct()
            candidate.copy(
                timescalesSeconds = timescales,
                repeatedEvidenceCount = maxOf(candidate.repeatedEvidenceCount, repeatedCount),
                reasons = reasons,
            )
        }

        val deduped = enrichedCandidates.distinctBy { candidate ->
            listOf(
                candidate.observedStart,
                candidate.observedEnd,
                candidate.windowStart,
                candidate.windowEnd,
                candidate.timescalesSeconds,
            )
        }

        val inception = deriveInceptionWindow(deduped, policy)
        val ranked = deduped
            .sortedWith(
                compareByDescending<HistoricalStrategyStartCandidate> { it.strength.rank }
                    .thenByDescending { it.repeatedEvidenceCount }
                    .thenByDescending { it.distinctAssets.size }
                    .thenBy { it.observedStart },
            )
            .take(policy.maximumCandidates)

        val ambiguous = deduped.filterNot(::isStrategyStartEligible)
        return HistoricalStrategyStartInference(
            candidates = ranked,
            inferredStart = inception.start,
            inferredWindowStart = inception.windowStart,
            inferredWindowEnd = inception.windowEnd,
            inferredStartStrength = inception.anchorStrength,
            inferredStartReasons = inception.anchorReasons,
            inferredStartContradictions = inception.anchorContradictions,
            strongestObservedStart = ranked.firstOrNull()?.observedStart,
            strongestEpisodeStrength = ranked.firstOrNull()?.strength,
            strongestEpisodeReasons = ranked.firstOrNull()?.reasons.orEmpty(),
            strongestEpisodeContradictions = ranked.firstOrNull()?.contradictions.orEmpty(),
            earliestAmbiguousStart = ambiguous.minOfOrNull { it.observedStart },
            earlierAmbiguousCandidateCount = if (inception.start != null) {
                ambiguous.count { it.observedStart.isBefore(inception.start) }
            } else {
                ambiguous.size
            },
            competingCandidateCount = (deduped.size - 1).coerceAtLeast(0),
            firstPositivelyOwnedTrade = firstPositive,
            unsupportedMarkets = unsupportedMarkets,
            coverageStart = ownershipValidTrades.minOfOrNull { it.timestamp },
            coverageEnd = ownershipValidTrades.maxOfOrNull { it.timestamp },
        )
    }

    private fun emptyInference(
        firstPositive: Instant?,
        unsupportedMarkets: List<String>,
        coverage: Pair<Instant?, Instant?>,
    ): HistoricalStrategyStartInference = HistoricalStrategyStartInference(
        candidates = emptyList(),
        inferredStart = null,
        inferredWindowStart = null,
        inferredWindowEnd = null,
        inferredStartStrength = null,
        inferredStartReasons = emptyList(),
        inferredStartContradictions = emptyList(),
        strongestObservedStart = null,
        strongestEpisodeStrength = null,
        strongestEpisodeReasons = emptyList(),
        strongestEpisodeContradictions = emptyList(),
        earliestAmbiguousStart = null,
        earlierAmbiguousCandidateCount = 0,
        competingCandidateCount = 0,
        firstPositivelyOwnedTrade = firstPositive,
        unsupportedMarkets = unsupportedMarkets,
        coverageStart = coverage.first,
        coverageEnd = coverage.second,
    )

    /**
     * First positive ownership stands on its own: unsupported or unparseable quote markets never
     * erase positive-ownership evidence, so this is computed from the ownership-valid stream
     * without requiring market normalization or historical quote valuation.
     */
    private fun firstPositivelyOwnedTrade(trades: List<HistoricalInferenceTrade>): Instant? = trades
        .filter { it.ownership == InferenceOwnership.POSITIVE }
        .minByOrNull { it.timestamp }
        ?.timestamp

    private fun List<HistoricalInferenceTrade>.coverageSpan(): Pair<Instant?, Instant?> =
        if (isEmpty()) null to null else minOf { it.timestamp } to maxOf { it.timestamp }

    /** Ownership validity needs an identity and executed volume, never a quote valuation. */
    private fun isOwnershipValid(trade: HistoricalInferenceTrade): Boolean =
        trade.id.isNotBlank() && trade.volume.signum() > 0

    /** Behavioral episode analysis additionally requires a positive historical quote amount. */
    private fun isEconomicsValid(trade: HistoricalInferenceTrade): Boolean =
        isOwnershipValid(trade) && trade.quoteAmount.signum() > 0

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
        episodes += current
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
        val sellsBeforeBuys = hasBuy && hasSell &&
            episode.indexOfFirst { OrderSide.isBuy(it.side) } > episode.indexOfLast { OrderSide.isSell(it.side) }
        val composition = when {
            hasBuy && hasSell && sellsBeforeBuys -> REASON_REDISTRIBUTION
            hasBuy && hasSell -> REASON_MIXED
            hasSell -> REASON_SELL_ONLY
            else -> REASON_PURCHASE_ONLY
        }
        val uniformQuote = episode.map { it.quoteAmount.stripTrailingZeros() }.distinct().size == 1
        val episodeStart = episode.first().timestamp
        val prior = allTrades.filter {
            it.timestamp.isBefore(episodeStart) &&
                !it.timestamp.isBefore(episodeStart.minus(policy.precedingActivityWindow))
        }
        val reasons = buildList {
            add(REASON_MULTI_ASSET)
            add(composition)
            if (uniformQuote) add(REASON_UNIFORM_QUOTE)
            if (sellsBeforeBuys) add(REASON_SELL_BEFORE_BUY)
            if (episode.any { it.ownership == InferenceOwnership.UNKNOWN }) add(REASON_UNKNOWN_OWNERSHIP)
        }
        val contradictions = buildList {
            if (prior.isNotEmpty()) add(CONTRADICTION_EARLIER_ACTIVITY)
        }
        return HistoricalStrategyStartCandidate(
            observedStart = episodeStart,
            observedEnd = episode.last().timestamp,
            windowStart = prior.firstOrNull()?.timestamp ?: episodeStart,
            windowEnd = episode.last().timestamp,
            orderCount = episode.size,
            distinctAssets = distinctAssets,
            strength = baseStrength(composition, distinctAssets.size),
            reasons = reasons,
            contradictions = contradictions,
        )
    }

    /**
     * Strength reflects the episode's economic composition only. Recurrence and multi-timescale
     * robustness are recorded as evidence metadata and never upgrade strength, so repeated
     * purchase-only batches stay weak and cannot masquerade as rebalancer redistribution.
     */
    private fun baseStrength(composition: String, assetCount: Int): InferenceStrength = when {
        composition == REASON_REDISTRIBUTION && assetCount >= 4 -> InferenceStrength.HIGH
        composition == REASON_REDISTRIBUTION && assetCount >= 3 -> InferenceStrength.MEDIUM
        composition == REASON_REDISTRIBUTION -> InferenceStrength.LOW
        composition == REASON_MIXED && assetCount >= 4 -> InferenceStrength.MEDIUM
        else -> InferenceStrength.LOW
    }

    /**
     * Strategy-start eligibility is semantic, not a bare strength floor: a redistribution episode
     * demonstrates the sell-then-buy restructuring the strategy performs and is eligible at any
     * supported asset count, while a mixed-side episode needs enough assets (the tier that reaches
     * MEDIUM composition strength) to be eligible. Purchase-only and sell-only accumulation is
     * ambiguous-only and can never anchor the estimated start by itself. Note this is broader than
     * a MEDIUM strength floor: a two-asset redistribution stays LOW yet remains eligible, so a LOW
     * strength never silently hides a genuine restructuring episode.
     */
    private fun isStrategyStartEligible(candidate: HistoricalStrategyStartCandidate): Boolean =
        when (compositionOf(candidate.reasons)) {
            REASON_REDISTRIBUTION -> true
            REASON_MIXED -> candidate.distinctAssets.size >= 4
            else -> false
        }

    /**
     * The inception window is derived from candidate chronology, not presentation ranking: the
     * earliest strategy-start-eligible candidate anchors the estimated start, and plausible
     * episodes within one cohesion window extend the window. Earlier ambiguous-only activity
     * beyond the cohesion window stays as separate competing evidence instead of claiming the
     * start, and with no eligible candidate the inferred start stays unset.
     */
    private fun deriveInceptionWindow(
        candidates: List<HistoricalStrategyStartCandidate>,
        policy: HistoricalInferencePolicy,
    ): DerivedWindow {
        val anchor = candidates
            .filter(::isStrategyStartEligible)
            .minByOrNull { it.observedStart }
            ?: return DerivedWindow(null, null, null, null, emptyList(), emptyList())
        val contemporaneous = candidates.filter {
            Duration.between(anchor.observedStart, it.observedStart).abs() <= policy.cohesionWindow
        }
        return DerivedWindow(
            start = anchor.observedStart,
            windowStart = contemporaneous.minOf { it.windowStart },
            windowEnd = contemporaneous.maxOf { it.observedEnd },
            anchorStrength = anchor.strength,
            anchorReasons = anchor.reasons,
            anchorContradictions = anchor.contradictions,
        )
    }

    private data class DerivedWindow(
        val start: Instant?,
        val windowStart: Instant?,
        val windowEnd: Instant?,
        val anchorStrength: InferenceStrength?,
        val anchorReasons: List<String>,
        val anchorContradictions: List<String>,
    )

    private fun normalize(trade: HistoricalInferenceTrade): NormalizedTrade? {
        val pair = trade.pair.trim().uppercase()
        val baseAsset = Asset.fromTradingPair(pair, emptyList()) ?: return null
        return NormalizedTrade(
            orderIdentity = trade.orderTxid?.trim()?.takeIf(String::isNotEmpty) ?: "fill:${trade.id}",
            timestamp = trade.timestamp,
            baseAsset = baseAsset,
            side = trade.side,
            quoteAmount = trade.quoteAmount,
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
            compositionOf(reasons) == compositionOf(other.reasons) &&
            reasons.contains(REASON_UNIFORM_QUOTE) == other.reasons.contains(REASON_UNIFORM_QUOTE)

    private fun compositionOf(reasons: List<String>): String? = reasons.firstOrNull {
        it in listOf(REASON_REDISTRIBUTION, REASON_MIXED, REASON_PURCHASE_ONLY, REASON_SELL_ONLY)
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
        val quoteAmount: BigDecimal,
        val ownership: InferenceOwnership,
    )
}
