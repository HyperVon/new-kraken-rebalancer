package com.gemini.krakenbot.service.impl.history

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

class HistoricalStrategyStartInferenceTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    private val start = Instant.parse("2025-12-22T02:08:00Z")

    init {
        "detects a multi-asset three-second batch across multiple timescales" {
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
            val candidate = requireNotNull(inference.strongestCandidate)

            candidate.observedStart shouldBe start
            candidate.orderCount shouldBe 12
            candidate.distinctAssets.size shouldBe 12
            candidate.timescalesSeconds shouldContainExactly setOf(5L, 15L)
            candidate.repeatedEvidenceCount shouldBe 2
            candidate.reasons shouldContain "SELL_BEFORE_BUY"
            candidate.reasons shouldContain "REPEATED_EPISODE_EVIDENCE"
            inference.firstPositivelyOwnedTrade shouldBe null
        }

        "collapses fills only when an authoritative order identity agrees" {
            val trades = listOf(
                trade("fill-1", start, "BTCUSD", "buy", orderTxid = "order-btc"),
                trade("fill-2", start.plusSeconds(1), "BTCUSD", "buy", orderTxid = "order-btc"),
                trade("fill-3", start.plusSeconds(2), "ETHUSD", "buy", orderTxid = "order-eth"),
            )

            val inference = HistoricalStrategyStartDetector.infer(trades)

            inference.strongestCandidate?.orderCount shouldBe 2
            inference.strongestCandidate?.distinctAssets shouldContainExactly setOf("BTC", "ETH")
        }

        "keeps unsupported historical markets as evidence without vetoing supported discovery" {
            val trades = listOf(
                trade("ada", start.minusSeconds(30), "ADAUSDT", "buy"),
                trade("btc", start, "BTCUSD", "buy", ownership = InferenceOwnership.POSITIVE),
                trade("eth", start.plusSeconds(2), "ETHUSD", "buy", ownership = InferenceOwnership.UNKNOWN),
            )

            val inference = HistoricalStrategyStartDetector.infer(trades)

            inference.candidates.shouldNotBeEmpty()
            inference.unsupportedMarkets shouldContain "ADAUSDT"
            inference.firstPositivelyOwnedTrade shouldBe start
            requireNotNull(inference.strongestCandidate).reasons shouldContain "UNKNOWN_OWNERSHIP"
        }

        "does not invent a strategy start for a single-asset or isolated activity" {
            val trades = listOf(
                trade("btc-1", start, "BTCUSD", "buy"),
                trade("btc-2", start.plusSeconds(1), "BTCUSD", "buy"),
            )

            val inference = HistoricalStrategyStartDetector.infer(trades)

            inference.candidates shouldBe emptyList()
            inference.firstPositivelyOwnedTrade shouldBe null
        }

        "retains competing fixed-purchase episodes and is input-order independent" {
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
            ordered.candidates.all { "PURCHASE_ONLY_EPISODE" in it.reasons } shouldBe true
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
            inference.coverageStart shouldBe null
            inference.coverageEnd shouldBe null
        }

        "preserves prior activity contradictions and evidence strength tiers" {
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
