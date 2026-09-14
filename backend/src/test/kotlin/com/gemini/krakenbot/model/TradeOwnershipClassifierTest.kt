package com.gemini.krakenbot.model

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant

@Suppress("unused")
class TradeOwnershipClassifierTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    private val now = Instant.parse("2026-07-01T12:00:00Z")

    private fun sampleTrade(
        source: TradeSource? = null,
        cycleId: String? = null,
        clientOrderId: String? = null,
        orderTxid: String? = null,
        tradeId: String? = null,
        success: Boolean = true,
        dryRun: Boolean = false,
    ): TradeRecord = TradeRecord(
        timestamp = now,
        pair = "BTCUSD",
        side = "BUY",
        symbol = "BTC",
        volume = BigDecimal.ONE,
        usdAmount = BigDecimal("50000.00"),
        price = BigDecimal("50000.00"),
        fee = BigDecimal.ZERO,
        success = success,
        dryRun = dryRun,
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
                source = TradeSource.MANUAL,
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

        "classifies explicit MANUAL evidence as MANUAL_OR_EXTERNAL" {
            val trade = sampleTrade(source = TradeSource.MANUAL, orderTxid = "TX-MANUAL", tradeId = "TR-MANUAL")
            TradeOwnershipClassifier.classify(trade) shouldBe TradeOwnership.MANUAL_OR_EXTERNAL
        }

        "does not infer manual ownership from an unmatched API_FILL identity" {
            val trade = sampleTrade(source = TradeSource.API_FILL, orderTxid = "TX-MANUAL", tradeId = "TR-MANUAL")
            TradeOwnershipClassifier.classify(trade, knownRebalancerOrderTxids = setOf("TX-OTHER")) shouldBe
                TradeOwnership.UNKNOWN
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

        "classifies API_FILL with only non-blank tradeId as UNKNOWN" {
            val trade = sampleTrade(source = TradeSource.API_FILL, orderTxid = null, tradeId = "TR-123")
            TradeOwnershipClassifier.classify(trade) shouldBe TradeOwnership.UNKNOWN
        }

        "classifies persisted clientOrderId enrichment as REBALANCER" {
            val trade =
                sampleTrade(
                    source = TradeSource.API_FILL,
                    clientOrderId = "cl-ord-456",
                    orderTxid = "TX-EXT",
                    tradeId = "TR-EXT",
                )
            TradeOwnershipClassifier.classify(trade) shouldBe TradeOwnership.REBALANCER
        }

        "one positively owned fill proves sibling fills with the exact same orderTxid" {
            val fillWithMetadata = sampleTrade(
                source = TradeSource.API_FILL,
                orderTxid = "TX-SHARED-BOT",
                tradeId = "TR-1",
                cycleId = "cycle-shared",
            )
            val siblingFillRaw = sampleTrade(
                source = TradeSource.API_FILL,
                orderTxid = "TX-SHARED-BOT",
                tradeId = "TR-2",
            )
            val resolved = TradeOwnershipClassifier.resolveOrderOwnership(listOf(fillWithMetadata, siblingFillRaw))
            resolved.rebalancerOrderTxids shouldBe setOf("TX-SHARED-BOT")
            resolved.classify(fillWithMetadata) shouldBe TradeOwnership.REBALANCER
            resolved.classify(siblingFillRaw) shouldBe TradeOwnership.REBALANCER
        }

        "one LOCAL_ESTIMATE with exact orderTxid proves its corresponding API fills" {
            val localEstimate = sampleTrade(
                source = TradeSource.LOCAL_ESTIMATE,
                orderTxid = "TX-ESTIMATE",
                tradeId = null,
            )
            val apiFill = sampleTrade(
                source = TradeSource.API_FILL,
                orderTxid = "TX-ESTIMATE",
                tradeId = "TR-FILL",
            )
            val resolved = TradeOwnershipClassifier.resolveOrderOwnership(listOf(localEstimate, apiFill))
            resolved.rebalancerOrderTxids shouldBe setOf("TX-ESTIMATE")
            resolved.classify(apiFill) shouldBe TradeOwnership.REBALANCER
        }

        "unrelated orderTxids remain UNKNOWN" {
            val botTrade = sampleTrade(
                source = TradeSource.API_FILL,
                orderTxid = "TX-BOT",
                tradeId = "TR-BOT",
                clientOrderId = "cl-1",
            )
            val unrelatedTrade = sampleTrade(
                source = TradeSource.API_FILL,
                orderTxid = "TX-UNRELATED",
                tradeId = "TR-UNRELATED",
            )
            val resolved = TradeOwnershipClassifier.resolveOrderOwnership(listOf(botTrade, unrelatedTrade))
            resolved.classify(botTrade) shouldBe TradeOwnership.REBALANCER
            resolved.classify(unrelatedTrade) shouldBe TradeOwnership.UNKNOWN
        }

        "matching amount and time without identity remains UNKNOWN" {
            val botTrade = sampleTrade(
                source = TradeSource.API_FILL,
                orderTxid = "TX-BOT-1",
                tradeId = "TR-1",
                cycleId = "cycle-1",
            )
            val otherTrade = sampleTrade(
                source = TradeSource.API_FILL,
                orderTxid = null,
                tradeId = "TR-2",
            )
            val resolved = TradeOwnershipClassifier.resolveOrderOwnership(listOf(botTrade, otherTrade))
            resolved.classify(botTrade) shouldBe TradeOwnership.REBALANCER
            resolved.classify(otherTrade) shouldBe TradeOwnership.UNKNOWN
        }

        "multiple conflicting owners fail closed" {
            val botFill = sampleTrade(
                source = TradeSource.API_FILL,
                orderTxid = "TX-CONFLICT",
                tradeId = "TR-BOT",
                cycleId = "cycle-conflict",
            )
            val manualFill = sampleTrade(
                source = TradeSource.MANUAL,
                orderTxid = "TX-CONFLICT",
                tradeId = "TR-MANUAL",
            )
            val resolved = TradeOwnershipClassifier.resolveOrderOwnership(listOf(botFill, manualFill))
            resolved.conflictingOrderTxids shouldBe setOf("TX-CONFLICT")
            resolved.rebalancerOrderTxids shouldBe emptySet()
            resolved.manualOrderTxids shouldBe emptySet()
            resolved.classify(botFill) shouldBe TradeOwnership.UNKNOWN
            resolved.classify(manualFill) shouldBe TradeOwnership.UNKNOWN
        }

        "manual evidence remains manual and propagates to exact sibling fills" {
            val manualFill = sampleTrade(
                source = TradeSource.MANUAL,
                orderTxid = "TX-MANUAL-SHARED",
                tradeId = "TR-M1",
            )
            val siblingApiFill = sampleTrade(
                source = TradeSource.API_FILL,
                orderTxid = "TX-MANUAL-SHARED",
                tradeId = "TR-M2",
            )
            val resolved = TradeOwnershipClassifier.resolveOrderOwnership(listOf(manualFill, siblingApiFill))
            resolved.manualOrderTxids shouldBe setOf("TX-MANUAL-SHARED")
            resolved.classify(manualFill) shouldBe TradeOwnership.MANUAL_OR_EXTERNAL
            resolved.classify(siblingApiFill) shouldBe TradeOwnership.MANUAL_OR_EXTERNAL
        }

        "OrderIntent exact binding remains bot" {
            val apiFill = sampleTrade(
                source = TradeSource.API_FILL,
                orderTxid = "TX-INTENT-MATCH",
                tradeId = "TR-INTENT",
            )
            val resolved = TradeOwnershipClassifier.resolveOrderOwnership(
                trades = listOf(apiFill),
                knownRebalancerOrderTxids = setOf("TX-INTENT-MATCH"),
            )
            resolved.classify(apiFill) shouldBe TradeOwnership.REBALANCER
        }

        "rejects blank or malformed orderTxid values in resolution" {
            val tradeWithBlankTxid = sampleTrade(
                source = TradeSource.API_FILL,
                orderTxid = "   ",
                cycleId = "cycle-blank-txid",
            )
            val resolved = TradeOwnershipClassifier.resolveOrderOwnership(listOf(tradeWithBlankTxid))
            resolved.rebalancerOrderTxids shouldBe emptySet()
            resolved.classify(tradeWithBlankTxid) shouldBe TradeOwnership.REBALANCER
        }

        "failed or dry-run trades do not propagate orderTxid ownership" {
            val failedTrade = sampleTrade(
                source = TradeSource.API_FILL,
                orderTxid = "TX-FAILED",
                cycleId = "cycle-fail",
                success = false,
            )
            val dryRunTrade = sampleTrade(
                source = TradeSource.API_FILL,
                orderTxid = "TX-DRYRUN",
                clientOrderId = "cl-dry",
                dryRun = true,
            )
            val siblingFailed = sampleTrade(
                source = TradeSource.API_FILL,
                orderTxid = "TX-FAILED",
                tradeId = "TR-1",
            )
            val siblingDryRun = sampleTrade(
                source = TradeSource.API_FILL,
                orderTxid = "TX-DRYRUN",
                tradeId = "TR-2",
            )
            val resolved = TradeOwnershipClassifier.resolveOrderOwnership(
                listOf(failedTrade, dryRunTrade, siblingFailed, siblingDryRun),
            )
            resolved.rebalancerOrderTxids shouldBe emptySet()
            resolved.classify(siblingFailed) shouldBe TradeOwnership.UNKNOWN
            resolved.classify(siblingDryRun) shouldBe TradeOwnership.UNKNOWN
        }

        "trade with clientOrderId but null cycleId propagates orderTxid to siblings" {
            val botTrade = sampleTrade(
                source = TradeSource.API_FILL,
                orderTxid = "TX-CL-ONLY",
                clientOrderId = "cl-only-1",
                cycleId = null,
            )
            val siblingTrade = sampleTrade(
                source = TradeSource.API_FILL,
                orderTxid = "TX-CL-ONLY",
                tradeId = "TR-CL-SIB",
            )
            val resolved = TradeOwnershipClassifier.resolveOrderOwnership(listOf(botTrade, siblingTrade))
            resolved.rebalancerOrderTxids shouldBe setOf("TX-CL-ONLY")
            resolved.classify(siblingTrade) shouldBe TradeOwnership.REBALANCER
        }

        "resolves external knownManualOrderTxids and ignores blank external order IDs" {
            val apiFill = sampleTrade(
                source = TradeSource.API_FILL,
                orderTxid = "TX-MANUAL-EXT",
                tradeId = "TR-M-EXT",
            )
            val resolved = TradeOwnershipClassifier.resolveOrderOwnership(
                trades = listOf(apiFill),
                knownRebalancerOrderTxids = setOf("  ", "TX-BOT-EXT"),
                knownManualOrderTxids = setOf(" ", "TX-MANUAL-EXT"),
            )
            resolved.manualOrderTxids shouldBe setOf("TX-MANUAL-EXT")
            resolved.rebalancerOrderTxids shouldBe setOf("TX-BOT-EXT")
            resolved.classify(apiFill) shouldBe TradeOwnership.MANUAL_OR_EXTERNAL
        }

        "manual trade with null or blank orderTxid classifies correctly and does not propagate" {
            val manualNullTxid = sampleTrade(
                source = TradeSource.MANUAL,
                orderTxid = null,
                tradeId = "TR-MN-1",
            )
            val manualBlankTxid = sampleTrade(
                source = TradeSource.MANUAL,
                orderTxid = "  ",
                tradeId = "TR-MN-2",
            )
            val resolved = TradeOwnershipClassifier.resolveOrderOwnership(listOf(manualNullTxid, manualBlankTxid))
            resolved.manualOrderTxids shouldBe emptySet()
            resolved.classify(manualNullTxid) shouldBe TradeOwnership.MANUAL_OR_EXTERNAL
            resolved.classify(manualBlankTxid) shouldBe TradeOwnership.MANUAL_OR_EXTERNAL
        }

        "trade with both bot and manual evidence fails closed to UNKNOWN directly" {
            val contradictoryTrade = sampleTrade(
                source = TradeSource.MANUAL,
                clientOrderId = "cl-contradictory",
                orderTxid = "TX-CONTRADICTORY",
            )
            TradeOwnershipClassifier.classify(contradictoryTrade) shouldBe TradeOwnership.UNKNOWN

            val tradeWithBothTxidEvidence = sampleTrade(
                source = TradeSource.API_FILL,
                orderTxid = "TX-BOTH",
            )
            TradeOwnershipClassifier.classify(
                trade = tradeWithBothTxidEvidence,
                knownRebalancerOrderTxids = setOf("TX-BOTH"),
                knownManualOrderTxids = setOf("TX-BOTH"),
            ) shouldBe TradeOwnership.UNKNOWN
        }
    }
}
