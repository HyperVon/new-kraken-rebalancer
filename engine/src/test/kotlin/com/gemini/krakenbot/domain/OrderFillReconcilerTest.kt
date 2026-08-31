package com.gemini.krakenbot.domain

import com.gemini.krakenbot.model.OrderSide
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.model.TradeSource
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant

@Suppress("unused")
class OrderFillReconcilerTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    private val now = Instant.parse("2026-08-25T12:00:00Z")

    private fun sampleFill(
        tradeId: String,
        orderTxid: String,
        symbol: String = "LINK",
        pair: String = "LINKUSD",
        side: String = "SELL",
        volume: BigDecimal = BigDecimal("3.25000000"),
        usdAmount: BigDecimal = BigDecimal("28.22"),
        price: BigDecimal = BigDecimal("8.68307692"),
        fee: BigDecimal = BigDecimal("0.0733"),
        timestamp: Instant = now,
        source: TradeSource = TradeSource.API_FILL,
    ) = TradeRecord(
        timestamp = timestamp,
        pair = pair,
        side = side,
        symbol = symbol,
        volume = volume,
        usdAmount = usdAmount,
        price = price,
        fee = fee,
        success = true,
        dryRun = false,
        source = source,
        tradeId = tradeId,
        orderTxid = orderTxid,
    )

    init {
        "isInstrumentCompatible matches exact symbol, pair, and normalized side" {
            val fill = sampleFill(tradeId = "T1", orderTxid = "O1", side = "sell")
            OrderFillReconciler.isInstrumentCompatible(
                orderSymbol = "LINK",
                orderSide = "SELL",
                orderPair = "LINKUSD",
                apiFill = fill,
            ) shouldBe true
        }

        "isInstrumentCompatible matches USD quoted pair aliases" {
            val fill = sampleFill(tradeId = "T1", orderTxid = "O1", symbol = "BTC", pair = "XXBTZUSD", side = "BUY")
            OrderFillReconciler.isInstrumentCompatible(
                orderSymbol = "BTC",
                orderSide = "BUY",
                orderPair = "XBTUSD",
                apiFill = fill,
            ) shouldBe true
        }

        "isInstrumentCompatible matches with allocations list" {
            val fill = sampleFill(tradeId = "T1", orderTxid = "O1", symbol = "BTC", pair = "XXBTZUSD", side = "BUY")
            OrderFillReconciler.isInstrumentCompatible(
                orderSymbol = "BTC",
                orderSide = "BUY",
                orderPair = "XXBTZUSD",
                apiFill = fill,
                allocations = listOf("BTC", "LINK"),
            ) shouldBe true
        }

        "isInstrumentCompatible handles empty canonical symbols and non-usd quoted pair" {
            val fill = sampleFill(tradeId = "T1", orderTxid = "O1", symbol = "", pair = "XYZABC", side = "BUY")
            OrderFillReconciler.isInstrumentCompatible(
                orderSymbol = "",
                orderSide = "BUY",
                orderPair = "XYZABC",
                apiFill = fill,
            ) shouldBe true

            OrderFillReconciler.isInstrumentCompatible(
                orderSymbol = "FOO",
                orderSide = "BUY",
                orderPair = "FOOEUR",
                apiFill = fill.copy(symbol = "BAR", pair = "BAREUR"),
            ) shouldBe false
        }

        "isInstrumentCompatible rejects allocations symbol mismatch" {
            val fill = sampleFill(tradeId = "T1", orderTxid = "O1", symbol = "BTC", pair = "XXBTZUSD", side = "BUY")
            OrderFillReconciler.isInstrumentCompatible(
                orderSymbol = "LINK",
                orderSide = "BUY",
                orderPair = "LINKUSD",
                apiFill = fill,
                allocations = listOf("BTC", "LINK"),
            ) shouldBe false
        }

        "isInstrumentCompatible rejects side mismatch" {
            val fill = sampleFill(tradeId = "T1", orderTxid = "O1", side = "BUY")
            OrderFillReconciler.isInstrumentCompatible(
                orderSymbol = "LINK",
                orderSide = "SELL",
                orderPair = "LINKUSD",
                apiFill = fill,
            ) shouldBe false
        }

        "isInstrumentCompatible rejects symbol mismatch" {
            val fill = sampleFill(tradeId = "T1", orderTxid = "O1", symbol = "ETH", pair = "ETHUSD")
            OrderFillReconciler.isInstrumentCompatible(
                orderSymbol = "LINK",
                orderSide = "SELL",
                orderPair = "LINKUSD",
                apiFill = fill,
            ) shouldBe false
        }

        "isInstrumentCompatible rejects non-USD pair mismatch" {
            val fill = sampleFill(tradeId = "T1", orderTxid = "O1", symbol = "BTC", pair = "XBTEUR", side = "BUY")
            OrderFillReconciler.isInstrumentCompatible(
                orderSymbol = "BTC",
                orderSide = "BUY",
                orderPair = "XBTUSD",
                apiFill = fill,
            ) shouldBe false
        }

        "evaluateAuthoritativeFills returns complete aggregated fills for 2-fill order" {
            val fills = listOf(
                sampleFill(
                    tradeId = "T1",
                    orderTxid = "O1",
                    volume = BigDecimal("3.25000000"),
                    usdAmount = BigDecimal("28.22"),
                ),
                sampleFill(
                    tradeId = "T2",
                    orderTxid = "O1",
                    volume = BigDecimal("3.25000000"),
                    usdAmount = BigDecimal("28.22"),
                ),
            )
            val eval = OrderFillReconciler.evaluateAuthoritativeFills(
                orderSymbol = "LINK",
                orderSide = "SELL",
                orderPair = "LINKUSD",
                orderVolume = BigDecimal("6.50000000"),
                orderUsdAmount = BigDecimal("56.44"),
                orderTxid = "O1",
                candidateFills = fills,
            )
            eval.shouldNotBeNull()
            eval.fills.size shouldBe 2
            eval.totalVolume.shouldBeEqualComparingTo(BigDecimal("6.50000000"))
            eval.totalUsd.shouldBeEqualComparingTo(BigDecimal("56.44"))
            eval.isComplete shouldBe true
        }

        "evaluateAuthoritativeFills returns complete aggregated fills for 3-fill order" {
            val fills = listOf(
                sampleFill(
                    tradeId = "T1",
                    orderTxid = "O1",
                    volume = BigDecimal("2.00000000"),
                    usdAmount = BigDecimal("17.36"),
                ),
                sampleFill(
                    tradeId = "T2",
                    orderTxid = "O1",
                    volume = BigDecimal("2.00000000"),
                    usdAmount = BigDecimal("17.36"),
                ),
                sampleFill(
                    tradeId = "T3",
                    orderTxid = "O1",
                    volume = BigDecimal("2.50000000"),
                    usdAmount = BigDecimal("21.72"),
                ),
            )
            val eval = OrderFillReconciler.evaluateAuthoritativeFills(
                orderSymbol = "LINK",
                orderSide = "SELL",
                orderPair = "LINKUSD",
                orderVolume = BigDecimal("6.50000000"),
                orderUsdAmount = BigDecimal("56.44"),
                orderTxid = "O1",
                candidateFills = fills,
            )
            eval.shouldNotBeNull()
            eval.fills.size shouldBe 3
            eval.totalVolume.shouldBeEqualComparingTo(BigDecimal("6.50000000"))
            eval.isComplete shouldBe true
        }

        "evaluateAuthoritativeFills detects incomplete partial fill" {
            val fills = listOf(
                sampleFill(
                    tradeId = "T1",
                    orderTxid = "O1",
                    volume = BigDecimal("3.25000000"),
                    usdAmount = BigDecimal("28.22"),
                ),
            )
            val eval = OrderFillReconciler.evaluateAuthoritativeFills(
                orderSymbol = "LINK",
                orderSide = "SELL",
                orderPair = "LINKUSD",
                orderVolume = BigDecimal("6.50000000"),
                orderUsdAmount = BigDecimal("56.44"),
                orderTxid = "O1",
                candidateFills = fills,
            )
            eval.shouldNotBeNull()
            eval.fills.size shouldBe 1
            eval.totalVolume.shouldBeEqualComparingTo(BigDecimal("3.25000000"))
            eval.isComplete shouldBe false
        }

        "evaluateAuthoritativeFills returns null when fills have incompatible side or symbol" {
            val fills = listOf(
                sampleFill(tradeId = "T1", orderTxid = "O1", symbol = "ETH", pair = "ETHUSD"),
            )
            val eval = OrderFillReconciler.evaluateAuthoritativeFills(
                orderSymbol = "LINK",
                orderSide = "SELL",
                orderPair = "LINKUSD",
                orderVolume = BigDecimal("6.50000000"),
                orderUsdAmount = BigDecimal("56.44"),
                orderTxid = "O1",
                candidateFills = fills,
            )
            eval.shouldBeNull()
        }

        "evaluateAuthoritativeFills returns null on blank or unmatched orderTxid" {
            OrderFillReconciler.evaluateAuthoritativeFills(
                orderSymbol = "LINK",
                orderSide = "SELL",
                orderPair = "LINKUSD",
                orderVolume = BigDecimal("6.50000000"),
                orderUsdAmount = BigDecimal("56.44"),
                orderTxid = "  ",
                candidateFills = listOf(sampleFill(tradeId = "T1", orderTxid = "O1")),
            ).shouldBeNull()

            OrderFillReconciler.evaluateAuthoritativeFills(
                orderSymbol = "LINK",
                orderSide = "SELL",
                orderPair = "LINKUSD",
                orderVolume = BigDecimal("6.50000000"),
                orderUsdAmount = BigDecimal("56.44"),
                orderTxid = "O-DIFFERENT",
                candidateFills = listOf(sampleFill(tradeId = "T1", orderTxid = "O1")),
            ).shouldBeNull()
        }

        "evaluateAuthoritativeFills allows zero order volume when USD amount matches" {
            val fill = sampleFill(
                tradeId = "T1",
                orderTxid = "O1",
                volume = BigDecimal("1.00000000"),
                usdAmount = BigDecimal("50.00"),
            )
            val eval = OrderFillReconciler.evaluateAuthoritativeFills(
                orderSymbol = "LINK",
                orderSide = "SELL",
                orderPair = "LINKUSD",
                orderVolume = BigDecimal.ZERO,
                orderUsdAmount = BigDecimal("50.00"),
                orderTxid = "O1",
                candidateFills = listOf(fill),
            )
            eval.shouldNotBeNull()
            eval.isComplete shouldBe true

            val incompleteUsd = OrderFillReconciler.evaluateAuthoritativeFills(
                orderSymbol = "LINK",
                orderSide = "SELL",
                orderPair = "LINKUSD",
                orderVolume = BigDecimal.ZERO,
                orderUsdAmount = BigDecimal("50.00"),
                orderTxid = "O1",
                candidateFills = listOf(fill.copy(usdAmount = BigDecimal("49.00"))),
            )
            incompleteUsd.shouldNotBeNull()
            incompleteUsd.isComplete shouldBe false
        }

        "evaluateAuthoritativeFills strictly rejects 99 percent partial execution" {
            val fill = sampleFill(
                tradeId = "T1",
                orderTxid = "O1",
                side = "SELL",
                volume = BigDecimal("99.00000000"),
                usdAmount = BigDecimal("990.00"),
            )
            val eval = OrderFillReconciler.evaluateAuthoritativeFills(
                orderSymbol = "LINK",
                orderSide = "SELL",
                orderPair = "LINKUSD",
                orderVolume = BigDecimal("100.00000000"),
                orderUsdAmount = BigDecimal("1000.00"),
                orderTxid = "O1",
                candidateFills = listOf(fill),
            )
            eval.shouldNotBeNull()
            eval.isComplete shouldBe false
        }

        "evaluateAuthoritativeFills strictly rejects 99.9 percent partial execution" {
            val fill = sampleFill(
                tradeId = "T1",
                orderTxid = "O1",
                side = "SELL",
                volume = BigDecimal("99.90000000"),
                usdAmount = BigDecimal("999.00"),
            )
            val eval = OrderFillReconciler.evaluateAuthoritativeFills(
                orderSymbol = "LINK",
                orderSide = "SELL",
                orderPair = "LINKUSD",
                orderVolume = BigDecimal("100.00000000"),
                orderUsdAmount = BigDecimal("1000.00"),
                orderTxid = "O1",
                candidateFills = listOf(fill),
            )
            eval.shouldNotBeNull()
            eval.isComplete shouldBe false
        }

        "evaluateAuthoritativeFills marks sub-satoshi precision noise as complete" {
            val fill = sampleFill(
                tradeId = "T1",
                orderTxid = "O1",
                volume = BigDecimal("6.500000004"),
                usdAmount = BigDecimal("56.44"),
            )
            val eval = OrderFillReconciler.evaluateAuthoritativeFills(
                orderSymbol = "LINK",
                orderSide = "SELL",
                orderPair = "LINKUSD",
                orderVolume = BigDecimal("6.50000000"),
                orderUsdAmount = BigDecimal("56.44"),
                orderTxid = "O1",
                candidateFills = listOf(fill),
            )
            eval.shouldNotBeNull()
            eval.isComplete shouldBe true
        }

        "evaluateAuthoritativeFills rejects overfill execution" {
            val fill = sampleFill(
                tradeId = "T1",
                orderTxid = "O1",
                volume = BigDecimal("6.51000000"),
                usdAmount = BigDecimal("56.50"),
            )
            val eval = OrderFillReconciler.evaluateAuthoritativeFills(
                orderSymbol = "LINK",
                orderSide = "SELL",
                orderPair = "LINKUSD",
                orderVolume = BigDecimal("6.50000000"),
                orderUsdAmount = BigDecimal("56.44"),
                orderTxid = "O1",
                candidateFills = listOf(fill),
            )
            eval.shouldNotBeNull()
            eval.isComplete shouldBe false
        }

        "evaluateAuthoritativeFills allows timestamps outside 10s window and prices >1% different" {
            val fill = sampleFill(
                tradeId = "T1",
                orderTxid = "O1",
                volume = BigDecimal("6.50000000"),
                usdAmount = BigDecimal("70.00"),
                price = BigDecimal("10.76923076"),
                timestamp = now.plusSeconds(300),
            )
            val eval = OrderFillReconciler.evaluateAuthoritativeFills(
                orderSymbol = "LINK",
                orderSide = "SELL",
                orderPair = "LINKUSD",
                orderVolume = BigDecimal("6.50000000"),
                orderUsdAmount = BigDecimal("56.44"),
                orderTxid = "O1",
                candidateFills = listOf(fill),
            )
            eval.shouldNotBeNull()
            eval.isComplete shouldBe true
        }

        "matchesHeuristic evaluates Path B constraints correctly" {
            val baseFill = sampleFill(
                tradeId = "T1",
                orderTxid = "",
                volume = BigDecimal("6.50000000"),
                usdAmount = BigDecimal("56.44"),
                price = BigDecimal("8.68307692"),
                timestamp = now,
            )

            // Exact match passes
            OrderFillReconciler.matchesHeuristic(
                orderSymbol = "LINK",
                orderSide = "SELL",
                orderPair = "LINKUSD",
                orderVolume = BigDecimal("6.50000000"),
                orderUsdAmount = BigDecimal("56.44"),
                orderExpectedPrice = BigDecimal("8.68307692"),
                orderTimestamp = now,
                apiFill = baseFill,
            ) shouldBe true

            // When expected price is null, price comparison is skipped
            OrderFillReconciler.matchesHeuristic(
                orderSymbol = "LINK",
                orderSide = "SELL",
                orderPair = "LINKUSD",
                orderVolume = BigDecimal("6.50000000"),
                orderUsdAmount = BigDecimal("56.44"),
                orderExpectedPrice = null,
                orderTimestamp = now,
                apiFill = baseFill,
            ) shouldBe true

            // Volume slightly different but within 1%, USD within 1% passes
            OrderFillReconciler.matchesHeuristic(
                orderSymbol = "LINK",
                orderSide = "SELL",
                orderPair = "LINKUSD",
                orderVolume = BigDecimal("6.50000000"),
                orderUsdAmount = BigDecimal("56.44"),
                orderExpectedPrice = null,
                orderTimestamp = now,
                apiFill = baseFill.copy(
                    volume = BigDecimal("6.51000000"),
                    usdAmount = BigDecimal("56.50"),
                ),
            ) shouldBe true

            // Volume slightly different within 1% but USD differs by >1% fails
            OrderFillReconciler.matchesHeuristic(
                orderSymbol = "LINK",
                orderSide = "SELL",
                orderPair = "LINKUSD",
                orderVolume = BigDecimal("6.50000000"),
                orderUsdAmount = BigDecimal("56.44"),
                orderExpectedPrice = null,
                orderTimestamp = now,
                apiFill = baseFill.copy(
                    volume = BigDecimal("6.51000000"),
                    usdAmount = BigDecimal("60.00"),
                ),
            ) shouldBe false

            // Incompatible instrument fails
            OrderFillReconciler.matchesHeuristic(
                orderSymbol = "LINK",
                orderSide = "SELL",
                orderPair = "LINKUSD",
                orderVolume = BigDecimal("6.50000000"),
                orderUsdAmount = BigDecimal("56.44"),
                orderExpectedPrice = null,
                orderTimestamp = now,
                apiFill = baseFill.copy(symbol = "ETH", pair = "ETHUSD"),
            ) shouldBe false

            // Timestamp outside 10s fails
            OrderFillReconciler.matchesHeuristic(
                orderSymbol = "LINK",
                orderSide = "SELL",
                orderPair = "LINKUSD",
                orderVolume = BigDecimal("6.50000000"),
                orderUsdAmount = BigDecimal("56.44"),
                orderExpectedPrice = BigDecimal("8.68307692"),
                orderTimestamp = now.minusMillis(15000),
                apiFill = baseFill,
            ) shouldBe false

            // Volume differs by >1% fails
            OrderFillReconciler.matchesHeuristic(
                orderSymbol = "LINK",
                orderSide = "SELL",
                orderPair = "LINKUSD",
                orderVolume = BigDecimal("6.50000000"),
                orderUsdAmount = BigDecimal("56.44"),
                orderExpectedPrice = BigDecimal("8.68307692"),
                orderTimestamp = now,
                apiFill = baseFill.copy(volume = BigDecimal("5.00000000")),
            ) shouldBe false

            // Price differs by >1% above fails
            OrderFillReconciler.matchesHeuristic(
                orderSymbol = "LINK",
                orderSide = "SELL",
                orderPair = "LINKUSD",
                orderVolume = BigDecimal("6.50000000"),
                orderUsdAmount = BigDecimal("56.44"),
                orderExpectedPrice = BigDecimal("8.68307692"),
                orderTimestamp = now,
                apiFill = baseFill.copy(price = BigDecimal("12.00000000")),
            ) shouldBe false

            // Price differs by >1% below fails
            OrderFillReconciler.matchesHeuristic(
                orderSymbol = "LINK",
                orderSide = "SELL",
                orderPair = "LINKUSD",
                orderVolume = BigDecimal("6.50000000"),
                orderUsdAmount = BigDecimal("56.44"),
                orderExpectedPrice = BigDecimal("8.68307692"),
                orderTimestamp = now,
                apiFill = baseFill.copy(price = BigDecimal("5.00000000")),
            ) shouldBe false
        }

        "enrichApiFill applies order metadata and computes slippage" {
            val fill = sampleFill(
                tradeId = "T1",
                orderTxid = "O1",
                side = "SELL",
                price = BigDecimal("8.50"),
            )
            val enriched = OrderFillReconciler.enrichApiFill(
                apiFill = fill.copy(orderTxid = null),
                expectedPrice = BigDecimal("8.68"),
                cycleId = "cycle-101",
                clientOrderId = "cl-202",
                orderTxid = "O1",
            )
            enriched.expectedPrice!!.shouldBeEqualComparingTo(BigDecimal("8.68"))
            enriched.cycleId shouldBe "cycle-101"
            enriched.clientOrderId shouldBe "cl-202"
            enriched.orderTxid shouldBe "O1"
            enriched.source shouldBe TradeSource.API_FILL
            enriched.slippagePercent.shouldNotBeNull()

            // Preserves existing metadata on fill if already present
            val alreadyEnriched = fill.copy(
                expectedPrice = BigDecimal("9.00"),
                cycleId = "cycle-orig",
                clientOrderId = "cl-orig",
                orderTxid = "tx-orig",
            )
            val reEnriched = OrderFillReconciler.enrichApiFill(
                apiFill = alreadyEnriched,
                expectedPrice = BigDecimal("8.00"),
                cycleId = "cycle-new",
                clientOrderId = "cl-new",
                orderTxid = "tx-new",
            )
            reEnriched.expectedPrice!!.shouldBeEqualComparingTo(BigDecimal("9.00"))
            reEnriched.cycleId shouldBe "cycle-orig"
            reEnriched.clientOrderId shouldBe "cl-orig"
            reEnriched.orderTxid shouldBe "tx-orig"

            // Handles null expected price
            val nullExpected = OrderFillReconciler.enrichApiFill(
                apiFill = fill.copy(expectedPrice = null, slippagePercent = BigDecimal("1.23")),
                expectedPrice = null,
                cycleId = null,
                clientOrderId = null,
                orderTxid = null,
            )
            nullExpected.expectedPrice.shouldBeNull()
            nullExpected.slippagePercent!!.shouldBeEqualComparingTo(BigDecimal("1.23"))
        }
    }
}
