package com.gemini.krakenbot.service.impl.history

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

class HistoricalStrategyStartInferenceTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    private val start = Instant.parse("2025-12-22T02:08:00Z")

    init {
        "detects a dec-like multi-asset batch across multiple timescales as one repeated episode" {
            val trades = (0 until 12).map { index ->
                trade(
                    id = "dec-$index",
                    timestamp = start.plusSeconds(index * 3L),
                    pair = "ASSET${index + 1}USD",
                    side = if (index < 2) "sell" else "buy",
                    orderTxid = "dec-order-$index",
                )
            } + (0 until 12).map { index ->
                trade(
                    id = "repeat-$index",
                    timestamp = start.plusSeconds(3_600 + index * 3L),
                    pair = "ASSET${index + 1}USD",
                    side = if (index < 2) "sell" else "buy",
                    orderTxid = "repeat-order-$index",
                )
            }

            val inference = HistoricalStrategyStartDetector.infer(trades.shuffled(random = kotlin.random.Random(7)))
            val candidate = requireNotNull(inference.candidates.firstOrNull())

            candidate.observedStart shouldBe start
            candidate.orderCount shouldBe 12
            candidate.distinctAssets.size shouldBe 12
            candidate.timescalesSeconds shouldContainExactly setOf(5L, 15L)
            candidate.repeatedEvidenceCount shouldBe 2
            candidate.reasons shouldContain "REDISTRIBUTION_SELL_THEN_BUY"
            candidate.reasons shouldContain "SELL_BEFORE_BUY"
            candidate.reasons shouldContain "REPEATED_EPISODE_EVIDENCE"
            inference.inferredStart shouldBe start
            inference.inferredStartStrength shouldBe InferenceStrength.HIGH
            inference.strongestObservedStart shouldBe start
            inference.strongestEpisodeStrength shouldBe InferenceStrength.HIGH
            inference.competingCandidateCount shouldBe 1
            inference.firstPositivelyOwnedTrade shouldBe null
        }

        "collapses partial fills so one authoritative order counts once" {
            val trades = listOf(
                trade("fill-1", start, "BTCUSD", "buy", orderTxid = "order-btc"),
                trade("fill-2", start.plusSeconds(1), "BTCUSD", "buy", orderTxid = "order-btc"),
                trade("fill-3", start.plusSeconds(2), "ETHUSD", "buy", orderTxid = "order-eth"),
            )

            val inference = HistoricalStrategyStartDetector.infer(trades)

            inference.candidates.first().orderCount shouldBe 2
            inference.candidates.first().distinctAssets shouldContainExactly setOf("BTC", "ETH")
        }

        "keeps unsupported historical markets as explicit evidence without vetoing discovery" {
            val trades = listOf(
                trade("ada", start.minusSeconds(30), "ADAUSDT", "buy"),
                trade("btc", start, "BTCUSD", "buy", ownership = InferenceOwnership.POSITIVE),
                trade("eth", start.plusSeconds(2), "ETHUSD", "buy", ownership = InferenceOwnership.UNKNOWN),
            )

            val inference = HistoricalStrategyStartDetector.infer(trades)

            inference.candidates.shouldNotBeEmpty()
            inference.unsupportedMarkets shouldContain "ADAUSDT"
            inference.firstPositivelyOwnedTrade shouldBe start
            inference.coverageStart shouldBe start.minusSeconds(30)
            inference.coverageEnd shouldBe start.plusSeconds(2)
            inference.candidates.first().reasons shouldContain "UNKNOWN_OWNERSHIP"
        }

        "keeps first positive ownership evidence independent of behavioral candidates" {
            val trades = listOf(
                trade("solo-sell", start, "SOLUSD", "sell", ownership = InferenceOwnership.POSITIVE),
            )

            val inference = HistoricalStrategyStartDetector.infer(trades)

            inference.candidates shouldBe emptyList()
            inference.inferredStart shouldBe null
            inference.firstPositivelyOwnedTrade shouldBe start
            inference.coverageStart shouldBe start
        }

        "does not invent a strategy start for a single-asset or isolated activity" {
            val trades = listOf(
                trade("btc-1", start, "BTCUSD", "buy"),
                trade("btc-2", start.plusSeconds(1), "BTCUSD", "buy"),
            )

            val inference = HistoricalStrategyStartDetector.infer(trades)

            inference.candidates shouldBe emptyList()
            inference.inferredStart shouldBe null
            inference.firstPositivelyOwnedTrade shouldBe null
        }

        "retains repeated fixed-purchase episodes as weak competing evidence with input-order independence" {
            val first = listOf(
                trade("first-btc", start, "BTCUSD", "buy"),
                trade("first-eth", start.plusSeconds(1), "ETHUSD", "buy"),
            )
            val second = listOf(
                trade("second-btc", start.plusSeconds(120), "BTCUSD", "buy"),
                trade("second-eth", start.plusSeconds(121), "ETHUSD", "buy"),
            )
            val policy = HistoricalInferencePolicy(episodeGaps = listOf(Duration.ofSeconds(5)))

            val ordered = HistoricalStrategyStartDetector.infer(first + second, policy)
            val reversed = HistoricalStrategyStartDetector.infer((first + second).reversed(), policy)

            ordered.candidates.map { it.observedStart } shouldContainExactly
                listOf(start, start.plusSeconds(120))
            reversed.candidates shouldBe ordered.candidates
            ordered.candidates.forEach { candidate ->
                candidate.strength shouldBe InferenceStrength.LOW
                candidate.repeatedEvidenceCount shouldBe 2
                candidate.reasons shouldContain "PURCHASE_ONLY_EPISODE"
                candidate.reasons shouldContain "UNIFORM_QUOTE_AMOUNTS"
                candidate.reasons shouldContain "REPEATED_EPISODE_EVIDENCE"
            }
            ordered.inferredStart shouldBe null
            ordered.inferredWindowStart shouldBe null
            ordered.inferredWindowEnd shouldBe null
            ordered.earliestAmbiguousStart shouldBe start
            ordered.earlierAmbiguousCandidateCount shouldBe 2
        }

        "rejects invalid fills without hiding unsupported market evidence" {
            val inference = HistoricalStrategyStartDetector.infer(
                listOf(
                    trade("", start, "BTCUSD", "buy").copy(volume = BigDecimal.ZERO),
                    trade("bad-market", start, "ADAUSDT", "buy"),
                    trade("blank-market", start, "", "buy"),
                ),
            )

            inference.candidates shouldBe emptyList()
            inference.firstPositivelyOwnedTrade shouldBe null
            inference.unsupportedMarkets shouldContain "ADAUSDT"
            inference.coverageStart shouldBe start
            inference.coverageEnd shouldBe start
        }

        "preserves prior activity contradictions and composition strength tiers" {
            val policy = HistoricalInferencePolicy(episodeGaps = listOf(Duration.ofSeconds(5)))
            val trades = listOf(
                trade("low-sell", start, "BTCUSD", "sell"),
                trade("low-buy", start.plusSeconds(1), "ETHUSD", "buy"),
                trade("medium-sell", start.plusSeconds(120), "BTCUSD", "sell"),
                trade("medium-buy-eth", start.plusSeconds(121), "ETHUSD", "buy"),
                trade("medium-buy-xrp", start.plusSeconds(122), "XRPUSD", "buy"),
                trade("high-sell", start.plusSeconds(240), "BTCUSD", "sell"),
                trade("high-buy-eth", start.plusSeconds(241), "ETHUSD", "buy"),
                trade("high-buy-xrp", start.plusSeconds(242), "XRPUSD", "buy"),
                trade("high-buy-dot", start.plusSeconds(243), "DOTUSD", "buy"),
            )

            val candidates = HistoricalStrategyStartDetector.infer(trades, policy).candidates

            candidates.map { it.strength } shouldContainExactly listOf(
                InferenceStrength.HIGH,
                InferenceStrength.MEDIUM,
                InferenceStrength.LOW,
            )
            candidates.first().contradictions shouldContain "EARLIER_ACTIVITY_IN_WINDOW"
            candidates[1].contradictions shouldContain "EARLIER_ACTIVITY_IN_WINDOW"
            candidates[2].contradictions shouldBe emptyList()
        }

        "keeps a sell-only multi-asset episode as low-strength evidence" {
            val trades = (0 until 4).map { index ->
                trade(
                    id = "sell-only-$index",
                    timestamp = start.plusSeconds(index * 3L),
                    pair = "ASSET${index + 1}USD",
                    side = "sell",
                    orderTxid = "sell-only-order-$index",
                )
            }

            val candidate = requireNotNull(
                HistoricalStrategyStartDetector.infer(
                    trades,
                    HistoricalInferencePolicy(episodeGaps = listOf(Duration.ofSeconds(5))),
                ).candidates.firstOrNull(),
            )

            candidate.distinctAssets.size shouldBe 4
            candidate.strength shouldBe InferenceStrength.LOW
            candidate.reasons shouldContain "SELL_ONLY_EPISODE"
            candidate.reasons shouldNotContain "REDISTRIBUTION_SELL_THEN_BUY"
            candidate.reasons shouldNotContain "PURCHASE_ONLY_EPISODE"
        }

        "does not merge competing episodes whose order composition differs" {
            val trades = listOf(
                trade("buy-btc", start, "BTCUSD", "buy"),
                trade("buy-eth", start.plusSeconds(1), "ETHUSD", "buy"),
                trade("sell-btc", start.plusSeconds(120), "BTCUSD", "sell"),
                trade("sell-eth", start.plusSeconds(121), "ETHUSD", "sell"),
                trade("mixed-btc", start.plusSeconds(240), "BTCUSD", "buy"),
                trade("mixed-eth", start.plusSeconds(241), "ETHUSD", "sell"),
            )
            val policy = HistoricalInferencePolicy(episodeGaps = listOf(Duration.ofSeconds(5)))

            val candidates = HistoricalStrategyStartDetector.infer(trades, policy).candidates

            candidates.map { it.observedStart } shouldContainExactly listOf(
                start,
                start.plusSeconds(120),
                start.plusSeconds(240),
            )
            candidates.forEach { candidate ->
                candidate.distinctAssets shouldContainExactly setOf("BTC", "ETH")
                candidate.orderCount shouldBe 2
                candidate.repeatedEvidenceCount shouldBe 1
                candidate.timescalesSeconds shouldContainExactly setOf(5L)
            }
            candidates.map { "PURCHASE_ONLY_EPISODE" in it.reasons } shouldContainExactly
                listOf(true, false, false)
            candidates.map { "MIXED_SIDE_EPISODE" in it.reasons } shouldContainExactly
                listOf(false, false, true)
        }

        "treats whitespace order ids as absent so fills stay distinct" {
            val trades = listOf(
                trade("ws-btc", start, "BTCUSD", "buy", orderTxid = "   "),
                trade("ws-eth", start.plusSeconds(1), "ETHUSD", "buy", orderTxid = "   "),
            )

            val candidate = requireNotNull(HistoricalStrategyStartDetector.infer(trades).candidates.firstOrNull())

            candidate.orderCount shouldBe 2
            candidate.distinctAssets shouldContainExactly setOf("BTC", "ETH")
        }

        "keeps one episode across timescales as robustness evidence, not repeated episodes, and weak" {
            val trades = listOf(
                trade("mt-btc", start, "BTCUSD", "buy"),
                trade("mt-eth", start.plusSeconds(3), "ETHUSD", "buy"),
            )

            val candidate = requireNotNull(HistoricalStrategyStartDetector.infer(trades).candidates.firstOrNull())

            candidate.timescalesSeconds shouldContainExactly setOf(5L, 15L)
            candidate.repeatedEvidenceCount shouldBe 1
            candidate.strength shouldBe InferenceStrength.LOW
            candidate.reasons shouldNotContain "REPEATED_EPISODE_EVIDENCE"
            candidate.reasons shouldContain "MULTI_TIMESCALE_MATCH"
        }

        "earlier medium correction keeps inception semantics when a later stronger episode appears" {
            val policy = HistoricalInferencePolicy(episodeGaps = listOf(Duration.ofSeconds(5)))
            val trades = listOf(
                trade("early-sell", start, "BTCUSD", "sell"),
                trade("early-buy-eth", start.plusSeconds(1), "ETHUSD", "buy"),
                trade("early-buy-xrp", start.plusSeconds(2), "XRPUSD", "buy"),
                trade("strong-sell", start.plusSeconds(120), "BTCUSD", "sell"),
                trade("strong-buy-eth", start.plusSeconds(121), "ETHUSD", "buy"),
                trade("strong-buy-xrp", start.plusSeconds(122), "XRPUSD", "buy"),
                trade("strong-buy-dot", start.plusSeconds(123), "DOTUSD", "buy"),
            )

            val inference = HistoricalStrategyStartDetector.infer(trades, policy)

            inference.candidates.map { it.strength } shouldContainExactly
                listOf(InferenceStrength.HIGH, InferenceStrength.MEDIUM)
            inference.inferredStart shouldBe start
            inference.inferredStartStrength shouldBe InferenceStrength.MEDIUM
            inference.strongestObservedStart shouldBe start.plusSeconds(120)
            inference.strongestEpisodeStrength shouldBe InferenceStrength.HIGH
            inference.inferredWindowStart shouldBe start
            inference.inferredWindowEnd shouldBe start.plusSeconds(123)
            inference.competingCandidateCount shouldBe 1
            inference.earliestAmbiguousStart shouldBe null
            inference.earlierAmbiguousCandidateCount shouldBe 0
        }

        "repeated redistribution outranks repeated fixed-purchase batches without promoting purchases" {
            val purchaseEpisodes = listOf(
                trade("p1-btc", start, "BTCUSD", "buy"),
                trade("p1-eth", start.plusSeconds(1), "ETHUSD", "buy"),
                trade("p2-btc", start.plusSeconds(120), "BTCUSD", "buy"),
                trade("p2-eth", start.plusSeconds(121), "ETHUSD", "buy"),
            )
            val redistributionEpisodes = (0 until 4).map { index ->
                trade(
                    id = "r1-$index",
                    timestamp = start.plusSeconds(600L + index),
                    pair = "ASSET${index + 1}USD",
                    side = if (index < 2) "sell" else "buy",
                    orderTxid = "r1-order-$index",
                )
            } + (0 until 4).map { index ->
                trade(
                    id = "r2-$index",
                    timestamp = start.plusSeconds(720L + index),
                    pair = "ASSET${index + 1}USD",
                    side = if (index < 2) "sell" else "buy",
                    orderTxid = "r2-order-$index",
                )
            }
            val policy = HistoricalInferencePolicy(episodeGaps = listOf(Duration.ofSeconds(5)))

            val inference = HistoricalStrategyStartDetector.infer(purchaseEpisodes + redistributionEpisodes, policy)

            inference.candidates.map { it.strength } shouldContainExactly listOf(
                InferenceStrength.HIGH,
                InferenceStrength.HIGH,
                InferenceStrength.LOW,
                InferenceStrength.LOW,
            )
            inference.candidates.take(2).forEach { candidate ->
                candidate.reasons shouldContain "REDISTRIBUTION_SELL_THEN_BUY"
            }
            inference.candidates.drop(2).forEach { candidate ->
                candidate.strength shouldBe InferenceStrength.LOW
                candidate.reasons shouldContain "PURCHASE_ONLY_EPISODE"
                candidate.reasons shouldContain "UNIFORM_QUOTE_AMOUNTS"
                candidate.reasons shouldContain "REPEATED_EPISODE_EVIDENCE"
            }
            inference.inferredStart shouldBe start.plusSeconds(600)
            inference.inferredStartStrength shouldBe InferenceStrength.HIGH
            inference.strongestObservedStart shouldBe start.plusSeconds(600)
            inference.strongestEpisodeStrength shouldBe InferenceStrength.HIGH
            inference.earliestAmbiguousStart shouldBe start
            inference.earlierAmbiguousCandidateCount shouldBe 2
        }

        "truncated purchase-only history stays ambiguous and bounded by retained coverage" {
            val trades = listOf(
                trade("btc", start, "BTCUSD", "buy"),
                trade("eth", start.plusSeconds(1), "ETHUSD", "buy"),
            )

            val inference = HistoricalStrategyStartDetector.infer(trades)

            inference.coverageStart shouldBe start
            inference.coverageEnd shouldBe start.plusSeconds(1)
            inference.inferredStart shouldBe null
            inference.earliestAmbiguousStart shouldBe start
            inference.earlierAmbiguousCandidateCount shouldBe 1
        }

        "reports no coverage span when every trade is ownership-invalid" {
            val inference = HistoricalStrategyStartDetector.infer(
                listOf(
                    trade("", start, "BTCUSD", "buy"),
                    trade("zero", start, "ETHUSD", "buy").copy(volume = BigDecimal.ZERO),
                    trade("negative", start, "XRPUSD", "buy").copy(volume = BigDecimal.ZERO),
                ),
            )

            inference.candidates shouldBe emptyList()
            inference.coverageStart shouldBe null
            inference.coverageEnd shouldBe null
            inference.unsupportedMarkets shouldBe emptyList()
            inference.firstPositivelyOwnedTrade shouldBe null
        }

        "keeps mixed-side episodes below redistribution strength at four assets" {
            val trades = listOf(
                trade("m-sell-1", start, "ASSET1USD", "sell"),
                trade("m-buy-2", start.plusSeconds(1), "ASSET2USD", "buy"),
                trade("m-sell-3", start.plusSeconds(2), "ASSET3USD", "sell"),
                trade("m-buy-4", start.plusSeconds(3), "ASSET4USD", "buy"),
            )

            val candidate = requireNotNull(
                HistoricalStrategyStartDetector.infer(trades).candidates.firstOrNull(),
            )

            candidate.strength shouldBe InferenceStrength.MEDIUM
            candidate.reasons shouldContain "MIXED_SIDE_EPISODE"
            candidate.reasons shouldNotContain "REDISTRIBUTION_SELL_THEN_BUY"
        }

        "anchors a sufficiently strong mixed-side episode as strategy-start eligible" {
            val trades = listOf(
                trade("m-sell-1", start, "ASSET1USD", "sell"),
                trade("m-buy-2", start.plusSeconds(1), "ASSET2USD", "buy"),
                trade("m-sell-3", start.plusSeconds(2), "ASSET3USD", "sell"),
                trade("m-buy-4", start.plusSeconds(3), "ASSET4USD", "buy"),
            )

            val inference = HistoricalStrategyStartDetector.infer(trades)

            inference.inferredStart shouldBe start
            inference.inferredStartStrength shouldBe InferenceStrength.MEDIUM
            inference.strongestObservedStart shouldBe start
        }

        "keeps a three-asset mixed-side episode ambiguous and non-anchoring" {
            val trades = listOf(
                trade("m3-sell-1", start, "ASSET1USD", "sell"),
                trade("m3-buy-2", start.plusSeconds(1), "ASSET2USD", "buy"),
                trade("m3-sell-3", start.plusSeconds(2), "ASSET3USD", "sell"),
            )

            val inference = HistoricalStrategyStartDetector.infer(trades)

            inference.candidates.first().strength shouldBe InferenceStrength.LOW
            inference.inferredStart shouldBe null
            inference.earliestAmbiguousStart shouldBe start
        }

        "keeps distant purchase-only episodes outside the window as ambiguous competing evidence" {
            val near = listOf(
                trade("near-btc", start, "BTCUSD", "buy"),
                trade("near-eth", start.plusSeconds(1), "ETHUSD", "buy"),
            )
            val distant = listOf(
                trade("far-btc", start.plusSeconds(7_200), "BTCUSD", "buy"),
                trade("far-eth", start.plusSeconds(7_201), "ETHUSD", "buy"),
            )
            val policy = HistoricalInferencePolicy(episodeGaps = listOf(Duration.ofSeconds(5)))

            val inference = HistoricalStrategyStartDetector.infer(near + distant, policy)

            inference.candidates.map { it.observedStart } shouldContainExactly
                listOf(start, start.plusSeconds(7_200))
            inference.inferredStart shouldBe null
            inference.earliestAmbiguousStart shouldBe start
            inference.earlierAmbiguousCandidateCount shouldBe 2
            inference.competingCandidateCount shouldBe 1
        }

        "does not merge purchase episodes with differing quote-amount shapes" {
            val uniform = listOf(
                trade("u1-btc", start, "BTCUSD", "buy"),
                trade("u1-eth", start.plusSeconds(1), "ETHUSD", "buy"),
                trade("u2-btc", start.plusSeconds(120), "BTCUSD", "buy"),
                trade("u2-eth", start.plusSeconds(121), "ETHUSD", "buy"),
            )
            val varied = listOf(
                trade("v-btc", start.plusSeconds(240), "BTCUSD", "buy").copy(
                    quoteAmount = BigDecimal("200.00"),
                ),
                trade("v-eth", start.plusSeconds(241), "ETHUSD", "buy"),
            )
            val policy = HistoricalInferencePolicy(episodeGaps = listOf(Duration.ofSeconds(5)))

            val candidates = HistoricalStrategyStartDetector.infer(uniform + varied, policy).candidates

            candidates.map { it.observedStart } shouldContainExactly
                listOf(start, start.plusSeconds(120), start.plusSeconds(240))
            candidates.map { it.repeatedEvidenceCount } shouldContainExactly listOf(2, 2, 1)
            candidates.map { "UNIFORM_QUOTE_AMOUNTS" in it.reasons } shouldContainExactly
                listOf(true, true, false)
        }

        "exposes the default policy deterministically" {
            HistoricalStrategyStartDetector.defaultPolicy.maximumCandidates shouldBe 8
            HistoricalStrategyStartDetector.defaultPolicy.cohesionWindow shouldBe Duration.ofHours(1)
        }

        "keeps inferred-start evidence separate from strongest-episode evidence" {
            val earlyLow = listOf(
                trade("low-sell", start, "BTCUSD", "sell"),
                trade("low-buy", start.plusSeconds(1), "ETHUSD", "buy"),
            )
            val laterHigh = listOf(
                trade("high-sell", start.plusSeconds(120), "BTCUSD", "sell"),
                trade("high-buy-eth", start.plusSeconds(121), "ETHUSD", "buy"),
                trade("high-buy-xrp", start.plusSeconds(122), "XRPUSD", "buy"),
                trade("high-buy-dot", start.plusSeconds(123), "DOTUSD", "buy"),
            )
            val policy = HistoricalInferencePolicy(episodeGaps = listOf(Duration.ofSeconds(5)))

            val inference = HistoricalStrategyStartDetector.infer(earlyLow + laterHigh, policy)

            inference.inferredStart shouldBe start
            inference.inferredStartStrength shouldBe InferenceStrength.LOW
            inference.strongestObservedStart shouldBe start.plusSeconds(120)
            inference.strongestEpisodeStrength shouldBe InferenceStrength.HIGH
            inference.competingCandidateCount shouldBe 1
        }

        "records first positive ownership from a zero-quote fill without requiring candidates" {
            val trades = listOf(
                trade("solo-sell", start, "SOLUSD", "sell", ownership = InferenceOwnership.POSITIVE)
                    .copy(quoteAmount = BigDecimal.ZERO),
                trade("btc-1", start.plusSeconds(1), "BTCUSD", "buy"),
                trade("eth-1", start.plusSeconds(2), "ETHUSD", "buy"),
            )

            val inference = HistoricalStrategyStartDetector.infer(trades)

            inference.firstPositivelyOwnedTrade shouldBe start
            inference.coverageStart shouldBe start
            inference.inferredStart shouldBe null
            inference.earliestAmbiguousStart shouldBe start.plusSeconds(1)
        }

        "keeps a positive unsupported-market fill visible as ownership evidence without a candidate" {
            val trades = listOf(
                trade("ada-positive", start, "ADAUSDT", "sell", ownership = InferenceOwnership.POSITIVE),
                trade("ada-unknown", start.plusSeconds(1), "ADAUSDT", "sell"),
                trade("ada-unknown-2", start.plusSeconds(2), "ADAUSDT", "buy"),
            )

            val inference = HistoricalStrategyStartDetector.infer(trades)

            inference.firstPositivelyOwnedTrade shouldBe start
            inference.unsupportedMarkets shouldContain "ADAUSDT"
            inference.candidates shouldBe emptyList()
            inference.inferredStart shouldBe null
        }

        "never promotes an unknown fill with zero quote amount to positive ownership" {
            val inference = HistoricalStrategyStartDetector.infer(
                listOf(
                    trade("unknown-zero", start, "SOLUSD", "sell").copy(quoteAmount = BigDecimal.ZERO),
                ),
            )

            inference.firstPositivelyOwnedTrade shouldBe null
        }

        "anchors the inferred start on the first eligible episode while ambiguous activity stays earlier" {
            val ambiguousBuys = listOf(
                trade("day1-btc", start, "BTCUSD", "buy"),
                trade("day1-eth", start.plusSeconds(1), "ETHUSD", "buy"),
            )
            val redistribution = (0 until 4).map { index ->
                trade(
                    id = "redist-$index",
                    timestamp = start.plusSeconds(15 * 24 * 3_600L + index),
                    pair = "ASSET${index + 1}USD",
                    side = if (index < 2) "sell" else "buy",
                    orderTxid = "redist-order-$index",
                )
            }
            val policy = HistoricalInferencePolicy(episodeGaps = listOf(Duration.ofSeconds(5)))

            val inference = HistoricalStrategyStartDetector.infer(ambiguousBuys + redistribution, policy)
            val anchor = start.plusSeconds(15 * 24 * 3_600L)

            inference.inferredStart shouldBe anchor
            inference.inferredStartStrength shouldBe InferenceStrength.HIGH
            inference.strongestObservedStart shouldBe anchor
            inference.earliestAmbiguousStart shouldBe start
            inference.earlierAmbiguousCandidateCount shouldBe 1
            inference.inferredWindowStart shouldBe anchor
            inference.inferredWindowEnd shouldBe anchor.plusSeconds(3)
        }

        "keeps sell-only history ambiguous without an inferred start" {
            val trades = (0 until 4).map { index ->
                trade(
                    id = "sell-only-$index",
                    timestamp = start.plusSeconds(index * 3L),
                    pair = "ASSET${index + 1}USD",
                    side = "sell",
                    orderTxid = "sell-order-$index",
                )
            }

            val inference = HistoricalStrategyStartDetector.infer(trades)

            inference.candidates.first().strength shouldBe InferenceStrength.LOW
            inference.inferredStart shouldBe null
            inference.earliestAmbiguousStart shouldBe start
            inference.earlierAmbiguousCandidateCount shouldBe 1
        }

        "shows ambiguous evidence and first positive without an inferred start" {
            val trades = listOf(
                trade("p-btc-1", start, "BTCUSD", "buy"),
                trade("p-eth-1", start.plusSeconds(1), "ETHUSD", "buy"),
                trade("solo-sell", start.plusSeconds(600), "SOLUSD", "sell", ownership = InferenceOwnership.POSITIVE),
            )

            val inference = HistoricalStrategyStartDetector.infer(trades)

            inference.inferredStart shouldBe null
            inference.inferredStartStrength shouldBe null
            inference.firstPositivelyOwnedTrade shouldBe start.plusSeconds(600)
            inference.earliestAmbiguousStart shouldBe start
            inference.earlierAmbiguousCandidateCount shouldBe 1
        }
    }

    private fun trade(
        id: String,
        timestamp: Instant,
        pair: String,
        side: String,
        orderTxid: String? = null,
        ownership: InferenceOwnership = InferenceOwnership.UNKNOWN,
    ) = HistoricalInferenceTrade(
        id = id,
        timestamp = timestamp,
        pair = pair,
        side = side,
        symbol = pair.removeSuffix("USD"),
        volume = BigDecimal.ONE,
        quoteAmount = BigDecimal("100.00"),
        orderTxid = orderTxid,
        ownership = ownership,
    )
}
