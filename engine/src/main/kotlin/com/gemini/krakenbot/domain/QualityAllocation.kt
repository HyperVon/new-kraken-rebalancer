package com.gemini.krakenbot.domain

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Derives target weights from fundamental quality scores, and measures the quality/diversity
 * a given weighting actually buys.
 *
 * Scores cluster tightly on a 0-10 scale, so plain proportionality barely differentiates
 * (a 9.5-vs-6.0 spread gives a ~1.6x weight ratio). [emphasis] steepens the mapping so the
 * allocator can express conviction without leaving the score scale: weight is proportional to
 * score raised to [emphasis].
 *
 * Nothing here mutates configuration. Callers preview the result and let the operator decide.
 */
object QualityAllocation {

    /** Maximum supported steepness; beyond this the allocation collapses onto the top score. */
    const val MAX_EMPHASIS = 8

    /**
     * Emphasis the settings form pre-selects. Steep enough that a clearly better score wins
     * clearly, which is the point of scoring assets at all, while leaving room to flatten it.
     */
    const val DEFAULT_EMPHASIS = 4

    /**
     * Emphasis applied when a request supplies none. Deliberately the flattest weighting rather
     * than [DEFAULT_EMPHASIS]: an absent field is "not chosen", not "chosen as the default".
     */
    const val FALLBACK_EMPHASIS = 1

    /** Percent scale used for allocation targets. */
    private const val PERCENT_SCALE = 2

    /**
     * Distributes [sleevePercent] across [scores] proportionally to `score^emphasis`.
     *
     * Rounding is largest-remainder so the result sums to [sleevePercent] exactly rather than
     * drifting a cent at a time; callers rely on the total matching the sleeve.
     */
    fun proportional(
        scores: Map<String, BigDecimal>,
        sleevePercent: BigDecimal,
        emphasis: Int,
    ): Map<String, BigDecimal> {
        require(scores.isNotEmpty()) { "cannot allocate without scores" }
        require(sleevePercent.signum() >= 0) { "sleeve must not be negative" }
        require(emphasis in 1..MAX_EMPHASIS) { "emphasis must be 1..$MAX_EMPHASIS" }
        require(scores.values.all { it.signum() > 0 }) { "scores must be positive" }

        if (scores.size == 1) {
            return mapOf(scores.keys.first() to sleevePercent.setScale(PERCENT_SCALE, RoundingMode.HALF_UP))
        }

        val powered = scores.mapValues { it.value.pow(emphasis) }
        val totalPower = powered.values.fold(BigDecimal.ZERO, BigDecimal::add)
        require(totalPower.signum() > 0) { "scores must not all be zero" }

        val exact = powered.mapValues { sleevePercent.multiply(it.value).divide(totalPower, 12, RoundingMode.DOWN) }
        val targetSleeve = sleevePercent.setScale(PERCENT_SCALE, RoundingMode.HALF_UP)
        val truncated = exact.mapValues { it.value.setScale(PERCENT_SCALE, RoundingMode.DOWN) }
        val totalTruncated = truncated.values.fold(BigDecimal.ZERO, BigDecimal::add)
        var residual = targetSleeve.subtract(totalTruncated)

        // Hand the leftover cents to the largest fractional remainders, largest first.
        // Because truncated <= exact, residual is always non-negative and bounded by the leg count.
        val remainders = exact.entries.sortedByDescending { (symbol, exactVal) ->
            exactVal.subtract(truncated.getValue(symbol))
        }
        val adjusted = truncated.toMutableMap()
        for ((symbol, _) in remainders) {
            if (residual.signum() <= 0) break
            adjusted[symbol] = adjusted.getValue(symbol).add(CENT)
            residual = residual.subtract(CENT)
        }
        return adjusted
    }

    /** Capital-weighted mean score, ignoring legs that carry no score (cash, gold). */
    fun weightedScore(weights: Map<String, BigDecimal>, scores: Map<String, BigDecimal>): BigDecimal {
        var numerator = BigDecimal.ZERO
        var denominator = BigDecimal.ZERO
        for ((symbol, weight) in weights) {
            val score = scores[symbol] ?: continue
            numerator = numerator.add(weight.multiply(score))
            denominator = denominator.add(weight)
        }
        require(denominator.signum() > 0) { "no scored weight to average" }
        return numerator.divide(denominator, PERCENT_SCALE, RoundingMode.HALF_UP)
    }

    /**
     * A single-cycle quality/structure reading, used to compare the managed book against a
     * buy-and-hold book on the same capital.
     */
    data class Profile(
        val weightedScore: BigDecimal,
        val maxWeightPercent: BigDecimal,
        val effectiveAssetCount: BigDecimal,
    )

    /**
     * Measures [values] (current USD value per symbol) against [scores].
     *
     * Legs with no score (cash, gold) are excluded from the weighted score but still count
     * toward concentration, because holding cash is itself a diversification choice. Returns
     * null when nothing is scored, so a caller can skip rather than report a meaningless 0.
     */
    fun profile(values: Map<String, BigDecimal>, scores: Map<String, BigDecimal>): Profile? {
        val scored = values.filter { (symbol, value) -> scores.containsKey(symbol) && value.signum() > 0 }
        if (scored.isEmpty()) return null
        val totalValue = values.values.fold(BigDecimal.ZERO, BigDecimal::add)
        if (totalValue.signum() <= 0) return null
        val shares = values.mapValues { it.value.divide(totalValue, 12, RoundingMode.HALF_UP) }
        val scoredWeight = shares.filterKeys { it in scored }.values.fold(BigDecimal.ZERO, BigDecimal::add)
        if (scoredWeight.signum() <= 0) return null
        var hhi = BigDecimal.ZERO
        for (share in shares.values) {
            hhi = hhi.add(share.multiply(share))
        }
        return Profile(
            weightedScore = weightedScore(scored, scores),
            maxWeightPercent = maxWeightPercent(shares),
            effectiveAssetCount = BigDecimal.ONE.divide(hhi, PERCENT_SCALE, RoundingMode.HALF_UP),
        )
    }

    /** Largest single-leg share of the scored sleeve, in percent. */
    fun maxWeightPercent(weights: Map<String, BigDecimal>): BigDecimal {
        val total = weights.values.fold(BigDecimal.ZERO, BigDecimal::add)
        require(total.signum() > 0) { "no weights to measure" }
        val largest = weights.values.max()
        return largest.multiply(HUNDRED).divide(total, PERCENT_SCALE, RoundingMode.HALF_UP)
    }

    /**
     * Effective number of independent bets, `1 / sum(share^2)`. Equals the leg count when
     * perfectly even and 1 when fully concentrated, so it reads directly as diversification.
     */
    fun effectiveAssetCount(weights: Map<String, BigDecimal>): BigDecimal {
        val total = weights.values.fold(BigDecimal.ZERO, BigDecimal::add)
        require(total.signum() > 0) { "no weights to measure" }
        var hhi = BigDecimal.ZERO
        for (weight in weights.values) {
            val share = weight.divide(total, 12, RoundingMode.HALF_UP)
            hhi = hhi.add(share.multiply(share))
        }
        return BigDecimal.ONE.divide(hhi, PERCENT_SCALE, RoundingMode.HALF_UP)
    }

    private val CENT: BigDecimal = BigDecimal("0.01")
    private val HUNDRED: BigDecimal = BigDecimal("100")
}
