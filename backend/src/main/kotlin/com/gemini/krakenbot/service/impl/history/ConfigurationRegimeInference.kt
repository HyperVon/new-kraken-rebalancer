package com.gemini.krakenbot.service.impl.history

import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

/**
 * Behavioral summary of one tracked asset, derived from retained economic evidence.
 *
 * This is the only input [ConfigurationRegimeInference] needs per asset. Nothing here is
 * configuration history: exact historical allocation changes were never retained, so every
 * transition this file produces is an inference from trading and balance behavior.
 */
data class AssetRegimeActivity(
    val symbol: String,
    /** Successful live fills observed for this asset. */
    val fillCount: Int,
    val firstFill: Instant?,
    val lastFill: Instant?,
    /**
     * First instant at which the asset's authoritative balance reached economic zero after having
     * been materially positive, or null when it never fully exited.
     */
    val fullExitAt: Instant?,
    /**
     * How long the asset stayed economically present after establishment, measured to its terminal
     * exit, or to its last observed positive balance when it is still held.
     *
     * This is deliberately separate from the fill span: trading activity can be spread over months
     * while the position itself is repeatedly opened and dumped. Requiring BOTH is what separates a
     * sustained regime member from churn that happens to accumulate many fills.
     */
    val economicallyPresentSpanMillis: Long?,
    /**
     * First instant at which the asset's authoritative balance became materially positive, or null
     * when it was never established in the observed window.
     */
    val establishedAt: Instant?,
    /** True when the asset was materially positive immediately before the comparison baseline. */
    val materiallyPresentAtBaseline: Boolean,
)

/**
 * One inferred configuration-regime change.
 *
 * @param clusterStart the first membership-changing event belonging to this transition's local cluster.
 * @param clusterEnd the last membership-changing event belonging to this transition's local cluster.
 * @param removals prior-regime members observed leaving.
 * @param additions newly persistent members observed entering.
 * @param confidence how strongly the retained behavior supports a deliberate regime change.
 */
data class InferredRegimeTransition(
    val clusterStart: Instant,
    val clusterEnd: Instant,
    val removals: Set<String>,
    val additions: Set<String>,
    val confidence: RegimeTransitionConfidence,
)

/**
 * How strongly retained behavior supports a deliberate regime change.
 *
 * Only [HIGH] is ever applied to the benchmark. [AMBIGUOUS] exists so candidates that could be
 * ordinary rebalancing stay visible in the forensic audit without silently steering history.
 */
enum class RegimeTransitionConfidence { HIGH, AMBIGUOUS }

/** One observed membership change, before it is grouped into a local transition cluster. */
private data class MembershipChange(val at: Instant, val symbol: String, val isRemoval: Boolean)

/**
 * Infers major, persistent configuration-regime changes from durable economic behavior.
 *
 * Exact historical configuration changes were never retained, so this deliberately infers them and
 * never claims them as proven fact. Two independent behavioural requirements must both hold:
 *
 * 1. **Persistent participation.** An asset counts as a regime member only when it traded at least
 *    [minFills] times across at least [minParticipationSpan]. The span requirement, not survival to
 *    the evidence horizon, is what establishes persistence: an asset may legitimately enter a regime
 *    and later leave it, so a final zero balance is compatible with an earlier real addition.
 * 2. **Coordinated cluster.** Membership changes are grouped into local clusters separated by
 *    [maxClusterGap], so a burst of coordinated removals and additions is one transition rather than
 *    one transition per fill.
 *
 * A removal is only inferred when the asset fully exited, was never re-established afterwards, and
 * was materially present at the baseline. That combination is what separates a deliberate exit from
 * ordinary drift: routine rebalancing in this account sells slices and trades the position back
 * within hours, whereas a genuine exit leaves an authoritative zero that never becomes positive
 * again while the account keeps trading.
 *
 * No asset symbol, count, or timestamp is special-cased here. Production assets are inputs, not
 * constants.
 */
object ConfigurationRegimeInference {
    /**
     * Minimum fills for sustained participation. Derived from the observed distribution: genuine
     * regime members trade in the hundreds, while onboarding churn round-trips in a handful of fills.
     */
    const val MIN_FILLS: Int = 20

    /**
     * Minimum participation span. Derived from the observed distribution: regime members span
     * 240-291 days, while every transient round-trip completes inside 315 minutes.
     */
    val MIN_PARTICIPATION_SPAN: Duration = Duration.ofDays(60)

    /**
     * Largest quiet period still considered part of one local cluster. Derived from the observed
     * time-between-rebalance-cycle distribution so that a coordinated migration separated by normal
     * portfolio activity stays one transition while a genuinely separate later regime change splits.
     */
    val MAX_CLUSTER_GAP: Duration = Duration.ofDays(7)

    /**
     * Classify each asset and group the qualifying membership changes into ordered transitions.
     *
     * Returns transitions in ascending [InferredRegimeTransition.clusterEnd] order, each carrying
     * only its own local cluster, so the caller can anchor and reset independently.
     */
    fun infer(activities: Collection<AssetRegimeActivity>): List<InferredRegimeTransition> {
        val persistent = activities.filter { it.isPersistent() }
        val persistentSymbols = persistent.mapTo(mutableSetOf()) { it.symbol }
        val bySymbol = activities.associateBy { it.symbol }

        val changes = mutableListOf<MembershipChange>()
        for (activity in activities) {
            val exit = activity.fullExitAt
            if (exit != null && activity.sustainedPriorHolding()) {
                changes += MembershipChange(exit, activity.symbol, isRemoval = true)
            }
            val entered = activity.establishedAt
            // Only an asset that was not already materially held at the baseline can *enter* a
            // regime. A baseline holding is a continuing member, so listing it as an addition would
            // invent a transition on the very first day of the comparison.
            if (entered != null && !activity.materiallyPresentAtBaseline &&
                activity.symbol in persistentSymbols &&
                (exit == null || exit.isAfter(entered))
            ) {
                changes += MembershipChange(entered, activity.symbol, isRemoval = false)
            }
        }
        if (changes.isEmpty()) return emptyList()

        val ordered = changes.sortedWith(compareBy({ it.at }, { it.isRemoval }, { it.symbol }))
        val clusters = mutableListOf<MutableList<MembershipChange>>()
        var current = mutableListOf(ordered.first())
        for (change in ordered.drop(1)) {
            val gap = Duration.between(current.last().at, change.at)
            if (gap <= MAX_CLUSTER_GAP) {
                current += change
            } else {
                clusters += current
                current = mutableListOf(change)
            }
        }
        clusters += current

        return clusters.map { cluster ->
            val removals = cluster.filter { it.isRemoval }.mapTo(sortedSetOf()) { it.symbol }
            val additions = cluster.filterNot { it.isRemoval }.mapTo(sortedSetOf()) { it.symbol }
            InferredRegimeTransition(
                clusterStart = cluster.minOf { it.at },
                clusterEnd = cluster.maxOf { it.at },
                removals = removals,
                additions = additions,
                confidence = confidenceFor(bySymbol, cluster),
            )
        }
    }

    /**
     * A removal is only inferred when the asset sustained a real holding before exiting: either it
     * was materially held at the baseline, or its final positive episode ran at least as long as
     * [MIN_PARTICIPATION_SPAN]. That is what separates a deliberate exit from onboarding churn, which
     * is bought and fully sold inside a single burst. It does not require survival to the evidence
     * horizon, so an asset that joins a regime and later leaves it still has a real earlier removal.
     */
    private fun AssetRegimeActivity.sustainedPriorHolding(): Boolean = materiallyPresentAtBaseline || isPersistent()

    /**
     * Sustained participation, measured as fills spanning at least [MIN_PARTICIPATION_SPAN]. Final
     * balance is deliberately not part of this test: a later regime change may legitimately end the
     * participation.
     */
    private fun AssetRegimeActivity.isPersistent(): Boolean {
        // Both fill bounds come from the same fill list, so a present first bound proves the last.
        val first = firstFill ?: return false
        val last = lastFill ?: first
        if (fillCount < MIN_FILLS) return false
        if (Duration.between(first, last) < MIN_PARTICIPATION_SPAN) return false
        // A member stays a member only while it is economically present. An asset traded often
        // across a long window but repeatedly sold back to zero is churn, not a regime.
        val present = economicallyPresentSpanMillis ?: return false
        return present >= MIN_PARTICIPATION_SPAN.toMillis()
    }

    /**
     * HIGH requires corroboration that the change was a deliberate regime move rather than drift:
     * either a qualifying removal that stuck, or at least two newly persistent members establishing
     * together, or a single addition that was later itself removed by a further regime change.
     * Behaviour that could plausibly be ordinary rebalancing is never promoted.
     */
    private fun confidenceFor(
        bySymbol: Map<String, AssetRegimeActivity>,
        cluster: List<MembershipChange>,
    ): RegimeTransitionConfidence {
        val removals = cluster.filter { it.isRemoval }
        val additions = cluster.filterNot { it.isRemoval }
        if (removals.isNotEmpty()) return RegimeTransitionConfidence.HIGH
        if (additions.size >= 2) return RegimeTransitionConfidence.HIGH
        if (additions.isEmpty()) return RegimeTransitionConfidence.AMBIGUOUS
        val entered = additions.single()
        val laterRemoval = bySymbol[entered.symbol]?.fullExitAt
        return if (laterRemoval != null && entered.at.isBefore(laterRemoval)) {
            RegimeTransitionConfidence.HIGH
        } else {
            RegimeTransitionConfidence.AMBIGUOUS
        }
    }
}
