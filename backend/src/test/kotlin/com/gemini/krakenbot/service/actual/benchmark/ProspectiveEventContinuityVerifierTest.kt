package com.gemini.krakenbot.service.actual.benchmark

import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.model.RebalancerOrderIdentities
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.repository.ExecutionOrderIntentRepository
import com.gemini.krakenbot.service.KrakenService
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.math.BigDecimal
import java.time.Instant

class ProspectiveEventContinuityVerifierTest :
    StringSpec({
        val t0 = Instant.parse("2026-03-01T10:00:00Z")
        val t1 = Instant.parse("2026-03-01T10:05:00Z")

        val segment = BenchmarkSegment(
            segmentId = "seg-1",
            baselineObservationId = "obs-0",
            accountIdentityDigest = "a".repeat(64),
            scopeFingerprint = "b".repeat(64),
            scopeSymbols = listOf("BTC", "ETH", "USD"),
            baselineAt = t0,
            initialHoldings = mapOf("BTC" to BigDecimal("1.0")),
            baselineMarks = mapOf("BTC" to BigDecimal("100.00")),
            baselineTotalUsd = BigDecimal("100.00"),
            status = BenchmarkSegmentStatus.TRACKING,
            lastVerifiedEventTime = t0,
            createdAt = t0,
        )

        "Oracle D: external deposit or withdrawal terminates segment with flow-free termination reason" {
            val krakenService = mockk<KrakenService>()
            val orderIntentRepo = mockk<ExecutionOrderIntentRepository>()

            val depositLedger = LedgerEvent(
                ledgerId = "L-DEP-1",
                refid = "ref-1",
                time = t1.minusSeconds(60),
                type = "deposit",
                subtype = "",
                aclass = "currency",
                asset = "XXBT",
                amount = BigDecimal("0.5"),
                fee = BigDecimal.ZERO,
                balance = BigDecimal("1.5"),
            )

            coEvery { krakenService.getLedgers(startSec = any(), endSec = any()) } returns listOf(depositLedger)
            every { krakenService.hasLastLedgerPageShape() } returns true
            every { krakenService.hasLastLedgerTotalCount() } returns true

            val verifier = ProspectiveEventContinuityVerifier(krakenService, orderIntentRepo)
            val result = verifier.verifyContinuity(segment, t1)

            result.shouldBeInstanceOf<ContinuityVerificationResult.Terminated>()
            result.reason shouldBe "External deposit or withdrawal detected: deposit 0.5 XXBT"
            result.eventTime shouldBe depositLedger.time
        }

        "Oracle E: API failure or missing count returns PendingEvidence without terminating segment" {
            val krakenService = mockk<KrakenService>()
            val orderIntentRepo = mockk<ExecutionOrderIntentRepository>()

            coEvery { krakenService.getLedgers(startSec = any(), endSec = any()) } throws
                RuntimeException("Kraken rate limit exceeded")

            val verifier = ProspectiveEventContinuityVerifier(krakenService, orderIntentRepo)
            val result = verifier.verifyContinuity(segment, t1)

            result.shouldBeInstanceOf<ContinuityVerificationResult.PendingEvidence>()
            result.message shouldBe "Failed to fetch ledgers from Kraken: Kraken rate limit exceeded"
        }

        "rebalancer-owned trade in scope maintains continuous verification" {
            val krakenService = mockk<KrakenService>()
            val orderIntentRepo = mockk<ExecutionOrderIntentRepository>()

            val tradeLedger = LedgerEvent(
                ledgerId = "L-TR-1",
                refid = "ref-trade-1",
                time = t1.minusSeconds(30),
                type = "trade",
                subtype = "",
                aclass = "currency",
                asset = "XXBT",
                amount = BigDecimal("-0.1"),
                fee = BigDecimal("0.001"),
                balance = BigDecimal("0.9"),
            )
            val trade = TradeRecord(
                timestamp = t1.minusSeconds(30),
                pair = "XBTUSD",
                side = "sell",
                symbol = "BTC",
                volume = BigDecimal("0.1"),
                usdAmount = BigDecimal("10.00"),
                success = true,
                dryRun = false,
                orderTxid = "ORD-KRAKEN-123",
            )

            coEvery { krakenService.getLedgers(startSec = any(), endSec = any()) } returns listOf(tradeLedger)
            every { krakenService.hasLastLedgerPageShape() } returns true
            every { krakenService.hasLastLedgerTotalCount() } returns true
            coEvery { krakenService.getTradeHistory(startSec = any()) } returns listOf(trade)
            every { krakenService.hasLastTradeHistoryPageShape() } returns true
            coEvery {
                orderIntentRepo.getKnownRebalancerOrderIdentities(
                    orderTxids = setOf("ORD-KRAKEN-123"),
                    clientOrderIds = emptySet(),
                )
            } returns RebalancerOrderIdentities(
                orderTxids = setOf("ORD-KRAKEN-123"),
            )

            val verifier = ProspectiveEventContinuityVerifier(krakenService, orderIntentRepo)
            val result = verifier.verifyContinuity(segment, t1)

            result.shouldBeInstanceOf<ContinuityVerificationResult.Continuous>()
            result.updatedVerifiedTime shouldBe t1
        }

        "unmatched or manual trade terminates segment" {
            val krakenService = mockk<KrakenService>()
            val orderIntentRepo = mockk<ExecutionOrderIntentRepository>()

            val tradeLedger = LedgerEvent(
                ledgerId = "L-TR-2",
                refid = "ref-trade-2",
                time = t1.minusSeconds(30),
                type = "trade",
                subtype = "",
                aclass = "currency",
                asset = "XXBT",
                amount = BigDecimal("0.1"),
                fee = BigDecimal("0.001"),
                balance = BigDecimal("1.1"),
            )
            val trade = TradeRecord(
                timestamp = t1.minusSeconds(30),
                pair = "XBTUSD",
                side = "buy",
                symbol = "BTC",
                volume = BigDecimal("0.1"),
                usdAmount = BigDecimal("10.00"),
                success = true,
                dryRun = false,
                orderTxid = "MANUAL-ORDER-999",
            )

            coEvery { krakenService.getLedgers(startSec = any(), endSec = any()) } returns listOf(tradeLedger)
            every { krakenService.hasLastLedgerPageShape() } returns true
            every { krakenService.hasLastLedgerTotalCount() } returns true
            coEvery { krakenService.getTradeHistory(startSec = any()) } returns listOf(trade)
            every { krakenService.hasLastTradeHistoryPageShape() } returns true
            coEvery {
                orderIntentRepo.getKnownRebalancerOrderIdentities(
                    orderTxids = setOf("MANUAL-ORDER-999"),
                    clientOrderIds = emptySet(),
                )
            } returns RebalancerOrderIdentities(
                orderTxids = emptySet(),
            )

            val verifier = ProspectiveEventContinuityVerifier(krakenService, orderIntentRepo)
            val result = verifier.verifyContinuity(segment, t1)

            result.shouldBeInstanceOf<ContinuityVerificationResult.Terminated>()
            result.reason shouldBe "Manual or out-of-scope trade detected: trade in XBTUSD (orderTxid=MANUAL-ORDER-999)"
        }
    })
