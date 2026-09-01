package com.gemini.krakenbot.model

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant

class TradeOwnershipClassifierTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    private val now = Instant.parse("2026-07-01T12:00:00Z")

    private fun sampleTrade(
        source: TradeSource? = null,
        cycleId: String? = null,
        clientOrderId: String? = null,
        orderTxid: String? = null,
        tradeId: String? = null,
    ): TradeRecord = TradeRecord(
        timestamp = now,
        pair = "BTCUSD",
        side = "BUY",
        symbol = "BTC",
        volume = BigDecimal.ONE,
        usdAmount = BigDecimal("50000.00"),
        price = BigDecimal("50000.00"),
        fee = BigDecimal.ZERO,
        success = true,
        dryRun = false,
        source = source,
        cycleId = cycleId,
        clientOrderId = clientOrderId,
        orderTxid = orderTxid,
        tradeId = tradeId,
    )

    init {
        "classifies trade with cycleId as REBALANCER" {
            val trade = sampleTrade(cycleId = "cycle-123")
            TradeOwnershipClassifier.classify(trade) shouldBe TradeOwnership.REBALANCER
        }

        "classifies trade with clientOrderId as REBALANCER" {
            val trade = sampleTrade(clientOrderId = "cl-ord-123")
            TradeOwnershipClassifier.classify(trade) shouldBe TradeOwnership.REBALANCER
        }

        "classifies trade with LOCAL_ESTIMATE source as REBALANCER" {
            val trade = sampleTrade(source = TradeSource.LOCAL_ESTIMATE)
            TradeOwnershipClassifier.classify(trade) shouldBe TradeOwnership.REBALANCER
        }

        "classifies trade with orderTxid matching known bot orders as REBALANCER" {
            val trade = sampleTrade(source = TradeSource.API_FILL, orderTxid = "TX-100", tradeId = "TR-100")
            TradeOwnershipClassifier.classify(trade, knownRebalancerOrderTxids = setOf("TX-100")) shouldBe
                TradeOwnership.REBALANCER
        }

        "classifies trade with blank cycleId and blank clientOrderId as UNKNOWN or MANUAL" {
            val tradeUnknown = sampleTrade(source = TradeSource.LEGACY_UNKNOWN, cycleId = "  ", clientOrderId = "")
            TradeOwnershipClassifier.classify(tradeUnknown) shouldBe TradeOwnership.UNKNOWN

            val tradeManual = sampleTrade(
                source = TradeSource.API_FILL,
                cycleId = "",
                clientOrderId = " ",
                orderTxid = "TX-MANUAL",
                tradeId = "TR-MANUAL",
            )
            TradeOwnershipClassifier.classify(tradeManual) shouldBe TradeOwnership.MANUAL_OR_EXTERNAL
        }

        "classifies trade with clientOrderId as REBALANCER even when orderTxid does not match" {
            val trade = sampleTrade(clientOrderId = "cl-ord-123", orderTxid = "TX-OTHER")
            TradeOwnershipClassifier.classify(trade, knownRebalancerOrderTxids = setOf("TX-100")) shouldBe
                TradeOwnership.REBALANCER
        }

        "classifies authoritative API_FILL without bot marks as MANUAL_OR_EXTERNAL" {
            val trade = sampleTrade(source = TradeSource.API_FILL, orderTxid = "TX-MANUAL", tradeId = "TR-MANUAL")
            TradeOwnershipClassifier.classify(trade) shouldBe TradeOwnership.MANUAL_OR_EXTERNAL
        }

        "classifies API_FILL with orderTxid not in knownRebalancerOrderTxids as MANUAL_OR_EXTERNAL" {
            val trade = sampleTrade(source = TradeSource.API_FILL, orderTxid = "TX-MANUAL", tradeId = "TR-MANUAL")
            TradeOwnershipClassifier.classify(trade, knownRebalancerOrderTxids = setOf("TX-OTHER")) shouldBe
                TradeOwnership.MANUAL_OR_EXTERNAL
        }

        "classifies trade with null source as UNKNOWN" {
            val trade = sampleTrade(source = null)
            TradeOwnershipClassifier.classify(trade) shouldBe TradeOwnership.UNKNOWN
        }

        "classifies trade with LEGACY_UNKNOWN source as UNKNOWN" {
            val trade = sampleTrade(source = TradeSource.LEGACY_UNKNOWN)
            TradeOwnershipClassifier.classify(trade) shouldBe TradeOwnership.UNKNOWN
        }

        "classifies API_FILL without authoritative identity as UNKNOWN" {
            val trade = sampleTrade(source = TradeSource.API_FILL, orderTxid = null, tradeId = null)
            TradeOwnershipClassifier.classify(trade) shouldBe TradeOwnership.UNKNOWN
        }

        "classifies API_FILL with blank orderTxid and blank tradeId as UNKNOWN" {
            val trade = sampleTrade(source = TradeSource.API_FILL, orderTxid = " ", tradeId = "")
            TradeOwnershipClassifier.classify(trade) shouldBe TradeOwnership.UNKNOWN
        }

        "classifies API_FILL with only non-blank tradeId as MANUAL_OR_EXTERNAL" {
            val trade = sampleTrade(source = TradeSource.API_FILL, orderTxid = null, tradeId = "TR-123")
            TradeOwnershipClassifier.classify(trade) shouldBe TradeOwnership.MANUAL_OR_EXTERNAL
        }

        "classifies trade matching knownRebalancerClientOrderIds as REBALANCER" {
            val trade =
                sampleTrade(
                    source = TradeSource.API_FILL,
                    clientOrderId = "cl-ord-456",
                    orderTxid = "TX-EXT",
                    tradeId = "TR-EXT",
                )
            TradeOwnershipClassifier.classify(trade, knownRebalancerClientOrderIds = setOf("cl-ord-456")) shouldBe
                TradeOwnership.REBALANCER
        }
    }
}
