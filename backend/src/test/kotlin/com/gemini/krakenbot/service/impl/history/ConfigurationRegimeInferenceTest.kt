package com.gemini.krakenbot.service.impl.history

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.time.Instant

class ConfigurationRegimeInferenceTest : StringSpec() {

    private val t0: Instant = Instant.parse("2025-12-05T17:00:00Z")

    private fun at(days: Long, hours: Long = 0): Instant = t0.plus(Duration.ofDays(days).plusHours(hours))

    private fun activity(
        symbol: String,
        fillCount: Int = 0,
        firstFill: Instant? = null,
        lastFill: Instant? = null,
        fullExitAt: Instant? = null,
        establishedAt: Instant? = null,
        materiallyPresentAtBaseline: Boolean = false,
    ) = AssetRegimeActivity(
        symbol = symbol,
        fillCount = fillCount,
        firstFill = firstFill,
        lastFill = lastFill,
        fullExitAt = fullExitAt,
        // Economically present from establishment until it leaves, or until the last observation
        // when it is still held; a still-held member is present through its last observed fill.
        economicallyPresentSpanMillis = establishedAt?.let { start ->
            (fullExitAt ?: lastFill)?.let { end -> Duration.between(start, end).toMillis() }
        },
        establishedAt = establishedAt,
        materiallyPresentAtBaseline = materiallyPresentAtBaseline,
    )

    /** An asset that satisfies the persistence rule by trading steadily from [from] to [to]. */
    private fun persistent(
        symbol: String,
        from: Instant,
        to: Instant,
        establishedAt: Instant? = from,
        fullExitAt: Instant? = null,
        materiallyPresentAtBaseline: Boolean = false,
    ) = activity(
        symbol = symbol,
        fillCount = ConfigurationRegimeInference.MIN_FILLS + 10,
        firstFill = from,
        lastFill = to,
        fullExitAt = fullExitAt,
        establishedAt = establishedAt,
        materiallyPresentAtBaseline = materiallyPresentAtBaseline,
    )

    init {
        "no membership change yields no transition" {
            val result = ConfigurationRegimeInference.infer(
                listOf(
                    persistent("BTC", t0, at(200), materiallyPresentAtBaseline = true),
                    persistent("ETH", t0, at(200), materiallyPresentAtBaseline = true),
                ),
            )

            result shouldHaveSize 0
        }

        "a single partial sell that never exits is not a removal" {
            val result = ConfigurationRegimeInference.infer(
                listOf(
                    activity(
                        symbol = "BTC",
                        fillCount = 40,
                        firstFill = t0,
                        lastFill = at(100),
                        fullExitAt = null,
                        establishedAt = t0,
                        materiallyPresentAtBaseline = true,
                    ),
                ),
            )

            result shouldHaveSize 0
        }

        "an asset that exits and is repurchased has no removal while it is still held" {
            // Production derives the exit as the first zero after the LAST positive balance, so a
            // repurchase moves the exit later. Still held at the horizon means no exit at all.
            val heldAgain = activity(
                symbol = "BTC",
                fillCount = 40,
                firstFill = t0,
                lastFill = t0.plus(Duration.ofDays(200)),
                fullExitAt = null,
                establishedAt = t0,
                materiallyPresentAtBaseline = true,
            )

            val result = ConfigurationRegimeInference.infer(listOf(heldAgain))

            result.none { it.removals.contains("BTC") } shouldBe true
        }

        "an asset that exits, is repurchased, then exits again is removed at the later exit" {
            val firstExit = t0.plus(Duration.ofDays(30))
            val secondExit = t0.plus(Duration.ofDays(200))
            val twice = activity(
                symbol = "BTC",
                fillCount = 60,
                firstFill = t0,
                lastFill = t0.plus(Duration.ofDays(200)),
                fullExitAt = secondExit,
                establishedAt = t0,
                materiallyPresentAtBaseline = true,
            )

            val result = ConfigurationRegimeInference.infer(listOf(twice))

            result.first().removals shouldBe setOf("BTC")
            // The inferred removal belongs to the terminal exit, not the transient first one.
            result.first().clusterEnd shouldBe secondExit
            firstExit.isBefore(secondExit) shouldBe true
        }

        "a fully exited baseline holding that is never repurchased is a removal" {
            val result = ConfigurationRegimeInference.infer(
                listOf(
                    activity(
                        symbol = "OLD",
                        fillCount = 1,
                        firstFill = t0,
                        lastFill = t0,
                        fullExitAt = t0.plusSeconds(27),
                        establishedAt = t0,
                        materiallyPresentAtBaseline = true,
                    ),
                    persistent("NEW", t0, at(200), establishedAt = t0.plusSeconds(600)),
                    persistent("NEW2", t0, at(200), establishedAt = t0.plusSeconds(900)),
                ),
            )

            result shouldHaveSize 1
            result.first().removals shouldBe setOf("OLD")
            result.first().additions shouldBe setOf("NEW", "NEW2")
            result.first().confidence shouldBe RegimeTransitionConfidence.HIGH
        }

        "a newly established asset that persists becomes an addition" {
            val result = ConfigurationRegimeInference.infer(
                listOf(
                    persistent("AAA", t0, at(200), establishedAt = t0.plus(Duration.ofDays(1))),
                    persistent("BBB", t0, at(200), establishedAt = t0.plus(Duration.ofDays(1))),
                ),
            )

            result shouldHaveSize 1
            result.first().additions shouldBe setOf("AAA", "BBB")
            result.first().removals shouldBe emptySet()
            result.first().confidence shouldBe RegimeTransitionConfidence.HIGH
        }

        "an asset traded too few times or for too short a span is not persistent" {
            val short = activity(
                symbol = "BRIEF",
                fillCount = ConfigurationRegimeInference.MIN_FILLS + 5,
                firstFill = t0,
                lastFill = t0.plus(Duration.ofDays(10)),
                establishedAt = t0,
            )
            val infrequent = activity(
                symbol = "SPARSE",
                fillCount = 4,
                firstFill = t0,
                lastFill = at(200),
                establishedAt = t0,
            )

            val result = ConfigurationRegimeInference.infer(listOf(short, infrequent))

            result shouldHaveSize 0
        }

        "a lone addition with no corroboration is ambiguous and never applied" {
            val result = ConfigurationRegimeInference.infer(
                listOf(persistent("LONE", t0, at(200), establishedAt = t0.plus(Duration.ofDays(1)))),
            )

            result shouldHaveSize 1
            result.first().confidence shouldBe RegimeTransitionConfidence.AMBIGUOUS
        }

        "a coordinated remove-and-add cluster is one transition, not one per fill" {
            val result = ConfigurationRegimeInference.infer(
                listOf(
                    activity(
                        symbol = "A",
                        fillCount = 1,
                        firstFill = t0,
                        lastFill = t0,
                        fullExitAt = t0.plusSeconds(30),
                        establishedAt = t0,
                        materiallyPresentAtBaseline = true,
                    ),
                    activity(
                        symbol = "B",
                        fillCount = 1,
                        firstFill = t0,
                        lastFill = t0,
                        fullExitAt = t0.plusSeconds(60),
                        establishedAt = t0,
                        materiallyPresentAtBaseline = true,
                    ),
                    persistent("C", t0, at(200), establishedAt = t0.plusSeconds(600)),
                    persistent("D", t0, at(200), establishedAt = t0.plusSeconds(900)),
                ),
            )

            result shouldHaveSize 1
            result.first().removals shouldBe setOf("A", "B")
            result.first().additions shouldBe setOf("C", "D")
        }

        "changes separated beyond the cluster gap become separate transitions" {
            val result = ConfigurationRegimeInference.infer(
                listOf(
                    persistent("EARLY", t0, at(300), establishedAt = t0.plusSeconds(600)),
                    persistent("EARLY2", t0, at(300), establishedAt = t0.plusSeconds(900)),
                    persistent(
                        "LATE",
                        t0,
                        at(300),
                        establishedAt = at(90),
                    ),
                    persistent("LATE2", t0, at(300), establishedAt = at(90).plusSeconds(300)),
                ),
            )

            result shouldHaveSize 2
            result[0].clusterEnd shouldBe t0.plusSeconds(900)
            result[1].clusterEnd shouldBe at(90).plusSeconds(300)
        }

        "persistence does not require surviving to the evidence horizon" {
            // Entered, traded for months, then removed in a later genuine regime change. The later
            // removal must be compatible with the earlier addition.
            val entered = t0.plus(Duration.ofDays(60))
            val removed = t0.plus(Duration.ofDays(170))
            val result = ConfigurationRegimeInference.infer(
                listOf(
                    persistent(
                        "MID",
                        entered,
                        removed.minus(Duration.ofDays(1)),
                        establishedAt = entered,
                        fullExitAt = removed,
                    ),
                    persistent("NEW", t0, at(300), establishedAt = t0.plusSeconds(600)),
                    persistent("NEW2", t0, at(300), establishedAt = t0.plusSeconds(900)),
                ),
            )

            // The earlier addition and the later removal are both recognised: a later regime change
            // ending the participation does not retroactively invalidate the original addition.
            result.any { it.additions.contains("MID") } shouldBe true
            val removal = result.first { it.removals.contains("MID") }
            removal.removals shouldBe setOf("MID")
            removal.additions shouldBe emptySet()
        }

        "a single addition paired with a removal is high confidence" {
            val result = ConfigurationRegimeInference.infer(
                listOf(
                    activity(
                        symbol = "OUT",
                        fillCount = 1,
                        firstFill = t0,
                        lastFill = t0,
                        fullExitAt = t0.plusSeconds(30),
                        establishedAt = t0,
                        materiallyPresentAtBaseline = true,
                    ),
                    persistent("LONE", t0, at(200), establishedAt = t0.plusSeconds(600)),
                ),
            )

            result shouldHaveSize 1
            result.first().confidence shouldBe RegimeTransitionConfidence.HIGH
        }

        "an addition that is later removed within its own cluster is corroborated" {
            val entered = t0.plus(Duration.ofDays(40))
            val removed = t0.plus(Duration.ofDays(130))
            val result = ConfigurationRegimeInference.infer(
                listOf(
                    persistent(
                        "COMES",
                        entered,
                        removed.minus(Duration.ofDays(1)),
                        establishedAt = entered,
                        fullExitAt = removed,
                    ),
                ),
            )

            // The single addition co-occurs with its own later removal, which is corroboration that
            // the asset was genuinely established and then genuinely left.
            result.first { it.additions.contains("COMES") }.additions shouldBe setOf("COMES")
            result.first { it.removals.contains("COMES") }.removals shouldBe setOf("COMES")
        }

        "no transition reaches high confidence without corroboration" {
            val result = ConfigurationRegimeInference.infer(
                listOf(persistent("SOLO", t0, at(200), establishedAt = t0.plusSeconds(300))),
            )

            result shouldHaveSize 1
            result.none { it.confidence == RegimeTransitionConfidence.HIGH } shouldBe true
        }

        "a lone addition later removed by a further regime change is corroborated" {
            val entered = t0.plus(Duration.ofDays(30))
            val removed = t0.plus(Duration.ofDays(150))
            val result = ConfigurationRegimeInference.infer(
                listOf(
                    persistent(
                        "SOLO",
                        entered,
                        removed.minus(Duration.ofDays(1)),
                        establishedAt = entered,
                        fullExitAt = removed,
                    ),
                ),
            )

            // One asset, two far-apart clusters: the entry is HIGH precisely because the same
            // asset is later seen leaving, which corroborates that it was genuinely established.
            result.size shouldBe 2
            result.first { it.additions.contains("SOLO") }.confidence shouldBe
                RegimeTransitionConfidence.HIGH
            result.first { it.removals.contains("SOLO") }.confidence shouldBe
                RegimeTransitionConfidence.HIGH
        }

        "many fills over a long window but economically absent is churn, not a member" {
            // The Track A regression: 30 fills across 200 days is active trading, but the position
            // itself was sold back to zero almost immediately, so it never became a regime member.
            val churn = AssetRegimeActivity(
                symbol = "CHURN",
                fillCount = 30,
                firstFill = t0,
                lastFill = t0.plus(Duration.ofDays(200)),
                fullExitAt = t0.plus(Duration.ofDays(1)),
                economicallyPresentSpanMillis = Duration.ofDays(1).toMillis(),
                establishedAt = t0,
                materiallyPresentAtBaseline = false,
            )

            val result = ConfigurationRegimeInference.infer(
                listOf(churn, persistent("PEER", t0, at(200), establishedAt = t0.plusSeconds(600))),
            )

            result.any { it.additions.contains("CHURN") } shouldBe false
            result.any { it.removals.contains("CHURN") } shouldBe false
        }

        "an asset with trading activity but no observed balance presence is not a member" {
            val unobserved = AssetRegimeActivity(
                symbol = "UNOBSERVED",
                fillCount = 30,
                firstFill = t0,
                lastFill = at(200),
                fullExitAt = null,
                economicallyPresentSpanMillis = null,
                establishedAt = null,
                materiallyPresentAtBaseline = false,
            )

            val result = ConfigurationRegimeInference.infer(listOf(unobserved))

            result shouldHaveSize 0
        }

        "an asset with fills but no last fill cannot be persistent" {
            val result = ConfigurationRegimeInference.infer(
                listOf(
                    activity(
                        symbol = "PARTIAL",
                        fillCount = 30,
                        firstFill = t0,
                        lastFill = null,
                        establishedAt = t0,
                    ),
                ),
            )

            result shouldHaveSize 0
        }

        "an exited asset that was neither baseline-held nor persistent is not a removal" {
            val result = ConfigurationRegimeInference.infer(
                listOf(
                    activity(
                        symbol = "BRIEF",
                        fillCount = 6,
                        firstFill = t0,
                        lastFill = t0.plus(Duration.ofDays(5)),
                        fullExitAt = t0.plus(Duration.ofDays(5)),
                        establishedAt = t0,
                        materiallyPresentAtBaseline = false,
                    ),
                    persistent("NEW", t0, at(200), establishedAt = t0.plusSeconds(600)),
                    persistent("NEW2", t0, at(200), establishedAt = t0.plusSeconds(900)),
                ),
            )

            result.forEach { transition ->
                transition.removals.contains("BRIEF") shouldBe false
            }
        }
    }
}
