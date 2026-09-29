package com.gemini.krakenbot.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.math.RoundingMode

class QualityAllocationTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    private val scores = mapOf(
        "BTC" to BigDecimal("9.5"),
        "ETH" to BigDecimal("8.5"),
        "SOL" to BigDecimal("8.5"),
        "TAO" to BigDecimal("6.0"),
    )
    private val sleeve = BigDecimal("90.00")

    init {
        "allocated weights sum exactly to the sleeve" {
            for (emphasis in 1..QualityAllocation.MAX_EMPHASIS) {
                val weights = QualityAllocation.proportional(scores, sleeve, emphasis)
                weights.values.fold(BigDecimal.ZERO, BigDecimal::add)
                    .shouldBeEqualComparingTo(sleeve)
            }
        }

        "allocated weights sum exactly even when rounding is awkward" {
            val awkward = mapOf(
                "A" to BigDecimal("7.1"),
                "B" to BigDecimal("7.1"),
                "C" to BigDecimal("7.1"),
                "D" to BigDecimal("7.1"),
            )
            val weights = QualityAllocation.proportional(awkward, BigDecimal("10"), emphasis = 1)
            weights.values.fold(BigDecimal.ZERO, BigDecimal::add)
                .shouldBeEqualComparingTo(BigDecimal("10.00"))
        }

        "proportional allocation awards remainder cents to largest fractional remainder without inversion" {
            val closeScores = mapOf(
                "A" to BigDecimal("33.336"),
                "B" to BigDecimal("33.336"),
                "C" to BigDecimal("33.328"),
            )
            val weights = QualityAllocation.proportional(closeScores, BigDecimal("100.00"), emphasis = 1)
            weights.values.fold(BigDecimal.ZERO, BigDecimal::add)
                .shouldBeEqualComparingTo(BigDecimal("100.00"))
            weights.getValue("C").shouldBeEqualComparingTo(BigDecimal("33.33"))
            (weights.getValue("A").add(weights.getValue("B"))).shouldBeEqualComparingTo(BigDecimal("66.67"))
        }

        "emphasis of one keeps close scores close together" {
            val weights = QualityAllocation.proportional(scores, sleeve, emphasis = 1)
            val btc = weights.getValue("BTC")
            val tao = weights.getValue("TAO")
            // 9.5 vs 6.0 is a 1.58x score ratio; proportionality preserves that, not much more.
            btc.divide(tao, 2, RoundingMode.HALF_UP)
                .shouldBeEqualComparingTo(BigDecimal("1.58"))
        }

        "higher emphasis concentrates weight on the higher scores" {
            val flat = QualityAllocation.proportional(scores, sleeve, emphasis = 1)
            val steep = QualityAllocation.proportional(scores, sleeve, emphasis = 5)
            QualityAllocation.maxWeightPercent(steep)
                .shouldBeGreaterThan(QualityAllocation.maxWeightPercent(flat))
            steep.getValue("BTC").shouldBeGreaterThan(flat.getValue("BTC"))
            steep.getValue("TAO").shouldBeGreaterThan(BigDecimal.ZERO)
        }

        "a single scored asset absorbs the whole sleeve" {
            val weights = QualityAllocation.proportional(
                mapOf("BTC" to BigDecimal("9.5")),
                sleeve,
                emphasis = 3,
            )
            weights.getValue("BTC").shouldBeEqualComparingTo(sleeve)
        }

        "weighted score ignores legs that carry no score" {
            val weights = mapOf(
                "BTC" to BigDecimal("40"),
                "TAO" to BigDecimal("10"),
                "USD" to BigDecimal("50"),
            )
            // USD has no score; the mean is over the scored 50 only:
            // (40 x 9.5 + 10 x 6.0) / 50 = 8.80
            QualityAllocation.weightedScore(weights, scores)
                .shouldBeEqualComparingTo(BigDecimal("8.80"))
        }

        "effective asset count is the leg count when perfectly even" {
            val even = mapOf(
                "A" to BigDecimal("25"),
                "B" to BigDecimal("25"),
                "C" to BigDecimal("25"),
                "D" to BigDecimal("25"),
            )
            QualityAllocation.effectiveAssetCount(even)
                .shouldBeEqualComparingTo(BigDecimal("4.00"))
        }

        "effective asset count collapses toward one when concentrated" {
            val lopsided = mapOf(
                "A" to BigDecimal("97"),
                "B" to BigDecimal("1"),
                "C" to BigDecimal("1"),
                "D" to BigDecimal("1"),
            )
            val effective = QualityAllocation.effectiveAssetCount(lopsided)
            (effective < BigDecimal("2")).shouldBeTrue()
        }

        "negative sleeve is rejected" {
            shouldThrow<IllegalArgumentException> {
                QualityAllocation.proportional(scores, BigDecimal("-1"), emphasis = 1)
            }
        }

        "zero or negative scores are rejected" {
            shouldThrow<IllegalArgumentException> {
                QualityAllocation.proportional(
                    mapOf("BTC" to BigDecimal.ZERO),
                    sleeve,
                    emphasis = 1,
                )
            }
        }

        "emphasis outside the supported range is rejected" {
            shouldThrow<IllegalArgumentException> {
                QualityAllocation.proportional(scores, sleeve, emphasis = 0)
            }
            shouldThrow<IllegalArgumentException> {
                QualityAllocation.proportional(
                    scores,
                    sleeve,
                    emphasis = QualityAllocation.MAX_EMPHASIS + 1,
                )
            }
        }

        "profile measures a scored book" {
            val values = mapOf(
                "BTC" to BigDecimal("24"),
                "TAO" to BigDecimal("6"),
                "USD" to BigDecimal("70"),
            )
            val profile = QualityAllocation.profile(values, scores)!!

            // Cash carries no score; the mean is over the scored 30 only:
            // (24 x 9.5 + 6 x 6.0) / 30 = 8.80
            profile.weightedScore.shouldBeEqualComparingTo(BigDecimal("8.80"))
            profile.maxWeightPercent.shouldBeEqualComparingTo(BigDecimal("70.00"))
        }

        "profile scores a drifted book below the target book" {
            val scoresForTest = mapOf("BTC" to BigDecimal("9.5"), "TAO" to BigDecimal("6.0"))
            // The rebalancer holds the target; buy-and-hold drifts toward whichever rose.
            val targetBook = mapOf("BTC" to BigDecimal("50"), "TAO" to BigDecimal("10"), "USD" to BigDecimal("40"))
            val driftedBook = mapOf("BTC" to BigDecimal("10"), "TAO" to BigDecimal("50"), "USD" to BigDecimal("40"))
            val targetProfile = QualityAllocation.profile(targetBook, scoresForTest)!!
            val driftedProfile = QualityAllocation.profile(driftedBook, scoresForTest)!!
            driftedProfile.weightedScore.shouldBeLessThan(targetProfile.weightedScore)
        }

        "profile returns null when nothing is scored" {
            QualityAllocation.profile(
                mapOf("USD" to BigDecimal("100")),
                scores,
            ).shouldBe(null)
        }

        "profile returns null for an empty book" {
            QualityAllocation.profile(emptyMap(), scores).shouldBe(null)
        }

        "profile returns null when the book has no value" {
            QualityAllocation.profile(
                mapOf("BTC" to BigDecimal.ZERO, "TAO" to BigDecimal.ZERO),
                scores,
            ).shouldBe(null)
        }

        "empty score set is rejected" {
            shouldThrow<IllegalArgumentException> {
                QualityAllocation.proportional(emptyMap(), sleeve, emphasis = 1)
            }
        }

        "over-rounded allocations are clawed back so the sleeve still sums exactly" {
            // 0.03 across two equal legs is 0.015 each; HALF_UP takes both to 0.02, overshooting
            // by a cent, which must be handed back rather than left in the total.
            val weights = QualityAllocation.proportional(
                mapOf("A" to BigDecimal("5.0"), "B" to BigDecimal("5.0")),
                BigDecimal("0.03"),
                emphasis = 1,
            )
            weights.values.fold(BigDecimal.ZERO, BigDecimal::add)
                .shouldBeEqualComparingTo(BigDecimal("0.03"))
        }

        "weighted score rejects a portfolio with no scored leg" {
            shouldThrow<IllegalArgumentException> {
                QualityAllocation.weightedScore(
                    mapOf("USD" to BigDecimal("100")),
                    scores,
                )
            }
        }

        "max weight rejects an empty weighting" {
            shouldThrow<IllegalArgumentException> {
                QualityAllocation.maxWeightPercent(emptyMap())
            }
        }

        "effective asset count rejects an empty weighting" {
            shouldThrow<IllegalArgumentException> {
                QualityAllocation.effectiveAssetCount(emptyMap())
            }
        }
    }
}
