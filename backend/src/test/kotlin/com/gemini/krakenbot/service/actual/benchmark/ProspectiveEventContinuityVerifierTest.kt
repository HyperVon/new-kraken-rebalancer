package com.gemini.krakenbot.service.actual.benchmark

import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.model.RebalancerOrderIdentities
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.repository.ExecutionOrderIntentRepository
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.service.RecoveryTradeHistoryService
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.math.BigDecimal
import java.time.Instant

private interface VerifierKrakenService :
    KrakenService,
    RecoveryTradeHistoryService

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

        "regression A: ledger count larger than retrieved page cannot establish continuity" {
            val krakenService = mockk<VerifierKrakenService>()
            val orderIntentRepo = mockk<ExecutionOrderIntentRepository>()

            val ledger1 = LedgerEvent(
                ledgerId = "L-1",
                refid = "ref-1",
                time = t1.minusSeconds(60),
                type = "trade",
                asset = "XXBT",
                amount = BigDecimal("0.1"),
                balance = BigDecimal("1.1"),
            )

            // Kraken reports totalCount = 2, but only offset 0 is returned and pagination terminates
            coEvery {
                krakenService.getLedgers(startSec = any(), offset = 0, endSec = any(), types = any())
            } returns listOf(ledger1)
            every { krakenService.hasLastLedgerPageShape() } returns true
            every { krakenService.hasLastLedgerTotalCount() } returns true
            every { krakenService.getLastLedgerTotalCount() } returns 2
            every { krakenService.getLastLedgerRawPageSize() } returns 1

            // Offset 1 returns empty, so fewer than totalCount entries were retrieved
            coEvery {
                krakenService.getLedgers(startSec = any(), offset = 1, endSec = any(), types = any())
            } returns emptyList()

            val verifier = ProspectiveEventContinuityVerifier(krakenService, orderIntentRepo)
            val result = verifier.verifyContinuity(segment, t1)

            result.shouldBeInstanceOf<ContinuityVerificationResult.PendingEvidence>()
            result.message shouldContain "Incomplete ledger pagination"
            result.message shouldContain "total count 2"
        }

        "regression B: multiple ledger and trade pages are handled completely" {
            val krakenService = mockk<VerifierKrakenService>()
            val orderIntentRepo = mockk<ExecutionOrderIntentRepository>()

            val ledgerPage1 = LedgerEvent(
                ledgerId = "L-P1",
                refid = "TR-1",
                time = t1.minusSeconds(120),
                type = "trade",
                asset = "XXBT",
                amount = BigDecimal("-0.1"),
                balance = BigDecimal("0.9"),
            )
            val ledgerPage2 = LedgerEvent(
                ledgerId = "L-P2",
                refid = "TR-2",
                time = t1.minusSeconds(60),
                type = "trade",
                asset = "XETH",
                amount = BigDecimal("-1.0"),
                balance = BigDecimal("5.0"),
            )

            // Ledger pagination: total count 2 across 2 requests
            coEvery {
                krakenService.getLedgers(startSec = any(), offset = 0, endSec = any(), types = any())
            } returns listOf(ledgerPage1)
            coEvery {
                krakenService.getLedgers(startSec = any(), offset = 1, endSec = any(), types = any())
            } returns listOf(ledgerPage2)
            every { krakenService.hasLastLedgerPageShape() } returns true
            every { krakenService.hasLastLedgerTotalCount() } returns true
            every { krakenService.getLastLedgerTotalCount() } returns 2
            every { krakenService.getLastLedgerRawPageSize() } returns 1

            val tradePage1 = TradeRecord(
                timestamp = t1.minusSeconds(120),
                pair = "XBTUSD",
                side = "sell",
                symbol = "BTC",
                volume = BigDecimal("0.1"),
                usdAmount = BigDecimal("10.00"),
                success = true,
                dryRun = false,
                orderTxid = "ORD-1",
                tradeId = "TR-1",
            )
            val tradePage2 = TradeRecord(
                timestamp = t1.minusSeconds(60),
                pair = "ETHUSD",
                side = "sell",
                symbol = "ETH",
                volume = BigDecimal("1.0"),
                usdAmount = BigDecimal("150.00"),
                success = true,
                dryRun = false,
                orderTxid = "ORD-2",
                tradeId = "TR-2",
            )

            // Trade pagination: total count 2 across 2 requests
            coEvery {
                krakenService.getRecoveryTradeHistoryUntil(startSec = any(), offset = 0, endSec = any())
            } returns listOf(tradePage1)
            coEvery {
                krakenService.getRecoveryTradeHistoryUntil(startSec = any(), offset = 1, endSec = any())
            } returns listOf(tradePage2)
            every { krakenService.hasLastTradeHistoryPageShape() } returns true
            every { krakenService.hasLastTradeHistoryTotalCount() } returns true
            every { krakenService.getLastTradeHistoryTotalCount() } returns 2
            every { krakenService.getLastTradeHistoryRawPageSize() } returns 1

            coEvery {
                orderIntentRepo.getKnownRebalancerOrderIdentities(
                    orderTxids = setOf("ORD-1", "ORD-2"),
                    clientOrderIds = emptySet(),
                )
            } returns RebalancerOrderIdentities(orderTxids = setOf("ORD-1", "ORD-2"))

            val verifier = ProspectiveEventContinuityVerifier(krakenService, orderIntentRepo)
            val result = verifier.verifyContinuity(segment, t1)

            result.shouldBeInstanceOf<ContinuityVerificationResult.Continuous>()
            result.updatedVerifiedTime shouldBe t1
        }

        "regression C: manual trade ledger with no matching trade-history record prevents continuity" {
            val krakenService = mockk<VerifierKrakenService>()
            val orderIntentRepo = mockk<ExecutionOrderIntentRepository>()

            val tradeLedger = LedgerEvent(
                ledgerId = "L-MANUAL",
                refid = "TR-UNKNOWN",
                time = t1.minusSeconds(30),
                type = "trade",
                asset = "XXBT",
                amount = BigDecimal("0.5"),
                balance = BigDecimal("1.5"),
            )

            coEvery {
                krakenService.getLedgers(startSec = any(), offset = any(), endSec = any(), types = any())
            } returns listOf(tradeLedger)
            every { krakenService.hasLastLedgerPageShape() } returns true
            every { krakenService.hasLastLedgerTotalCount() } returns true
            every { krakenService.getLastLedgerTotalCount() } returns 1
            every { krakenService.getLastLedgerRawPageSize() } returns 1

            // Trade history returns a different trade (none matching TR-UNKNOWN)
            val otherTrade = TradeRecord(
                timestamp = t1.minusSeconds(30),
                pair = "XBTUSD",
                side = "buy",
                symbol = "BTC",
                volume = BigDecimal("0.1"),
                usdAmount = BigDecimal("10.00"),
                success = true,
                dryRun = false,
                orderTxid = "ORD-OTHER",
                tradeId = "TR-OTHER",
            )
            coEvery {
                krakenService.getRecoveryTradeHistoryUntil(startSec = any(), offset = any(), endSec = any())
            } returns listOf(otherTrade)
            every { krakenService.hasLastTradeHistoryPageShape() } returns true
            every { krakenService.hasLastTradeHistoryTotalCount() } returns true
            every { krakenService.getLastTradeHistoryTotalCount() } returns 1
            every { krakenService.getLastTradeHistoryRawPageSize() } returns 1

            val verifier = ProspectiveEventContinuityVerifier(krakenService, orderIntentRepo)
            val result = verifier.verifyContinuity(segment, t1)

            result.shouldBeInstanceOf<ContinuityVerificationResult.Terminated>()
            result.reason shouldContain "no matching trade history execution"
            result.reason shouldContain "TR-UNKNOWN"
        }

        "regression D1: missing trade-history count prevents continuity with PendingEvidence" {
            val krakenService = mockk<VerifierKrakenService>()
            val orderIntentRepo = mockk<ExecutionOrderIntentRepository>()

            val tradeLedger = LedgerEvent(
                ledgerId = "L-TR-1",
                refid = "TR-1",
                time = t1.minusSeconds(30),
                type = "trade",
                asset = "XXBT",
                amount = BigDecimal("-0.1"),
                balance = BigDecimal("0.9"),
            )

            coEvery {
                krakenService.getLedgers(startSec = any(), offset = any(), endSec = any(), types = any())
            } returns listOf(tradeLedger)
            every { krakenService.hasLastLedgerPageShape() } returns true
            every { krakenService.hasLastLedgerTotalCount() } returns true
            every { krakenService.getLastLedgerTotalCount() } returns 1
            every { krakenService.getLastLedgerRawPageSize() } returns 1

            coEvery {
                krakenService.getRecoveryTradeHistoryUntil(startSec = any(), offset = any(), endSec = any())
            } returns emptyList()
            every { krakenService.hasLastTradeHistoryPageShape() } returns true
            every { krakenService.hasLastTradeHistoryTotalCount() } returns false

            val verifier = ProspectiveEventContinuityVerifier(krakenService, orderIntentRepo)
            val result = verifier.verifyContinuity(segment, t1)

            result.shouldBeInstanceOf<ContinuityVerificationResult.PendingEvidence>()
            result.message shouldContain "missing an authoritative count"
        }

        "regression D2: malformed trade-history economic fields prevents continuity with PendingEvidence" {
            val krakenService = mockk<VerifierKrakenService>()
            val orderIntentRepo = mockk<ExecutionOrderIntentRepository>()

            val tradeLedger = LedgerEvent(
                ledgerId = "L-TR-1",
                refid = "TR-1",
                time = t1.minusSeconds(30),
                type = "trade",
                asset = "XXBT",
                amount = BigDecimal("-0.1"),
                balance = BigDecimal("0.9"),
            )

            coEvery {
                krakenService.getLedgers(startSec = any(), offset = any(), endSec = any(), types = any())
            } returns listOf(tradeLedger)
            every { krakenService.hasLastLedgerPageShape() } returns true
            every { krakenService.hasLastLedgerTotalCount() } returns true
            every { krakenService.getLastLedgerTotalCount() } returns 1
            every { krakenService.getLastLedgerRawPageSize() } returns 1

            val malformedTrade = TradeRecord(
                timestamp = t1.minusSeconds(30),
                pair = "XBTUSD",
                side = "sell",
                symbol = "BTC",
                volume = BigDecimal("0.1"),
                usdAmount = BigDecimal("10.00"),
                success = true,
                dryRun = false,
                orderTxid = "ORD-1",
                tradeId = "TR-1",
                hasValidCost = false,
            )
            coEvery {
                krakenService.getRecoveryTradeHistoryUntil(startSec = any(), offset = any(), endSec = any())
            } returns listOf(malformedTrade)
            every { krakenService.hasLastTradeHistoryPageShape() } returns true
            every { krakenService.hasLastTradeHistoryTotalCount() } returns true
            every { krakenService.getLastTradeHistoryTotalCount() } returns 1
            every { krakenService.getLastTradeHistoryRawPageSize() } returns 1

            val verifier = ProspectiveEventContinuityVerifier(krakenService, orderIntentRepo)
            val result = verifier.verifyContinuity(segment, t1)

            result.shouldBeInstanceOf<ContinuityVerificationResult.PendingEvidence>()
            result.message shouldContain "invalid or unparseable economic fields"
        }

        "regression D3: trade record with unmanaged pair terminates segment with MANUAL_TRADE" {
            val krakenService = mockk<VerifierKrakenService>()
            val orderIntentRepo = mockk<ExecutionOrderIntentRepository>()

            val tradeLedger = LedgerEvent(
                ledgerId = "L-DOGE",
                refid = "TR-DOGE",
                time = t1.minusSeconds(30),
                type = "trade",
                asset = "XXDG",
                amount = BigDecimal("100.0"),
                balance = BigDecimal("1000.0"),
            )

            coEvery {
                krakenService.getLedgers(startSec = any(), offset = any(), endSec = any(), types = any())
            } returns listOf(tradeLedger)
            every { krakenService.hasLastLedgerPageShape() } returns true
            every { krakenService.hasLastLedgerTotalCount() } returns true
            every { krakenService.getLastLedgerTotalCount() } returns 1
            every { krakenService.getLastLedgerRawPageSize() } returns 1

            val unmanagedTrade = TradeRecord(
                timestamp = t1.minusSeconds(30),
                pair = "XDGUSD",
                side = "buy",
                symbol = "DOGE",
                volume = BigDecimal("100.0"),
                usdAmount = BigDecimal("10.00"),
                success = true,
                dryRun = false,
                orderTxid = "ORD-DOGE",
                tradeId = "TR-DOGE",
            )
            coEvery {
                krakenService.getRecoveryTradeHistoryUntil(startSec = any(), offset = any(), endSec = any())
            } returns listOf(unmanagedTrade)
            every { krakenService.hasLastTradeHistoryPageShape() } returns true
            every { krakenService.hasLastTradeHistoryTotalCount() } returns true
            every { krakenService.getLastTradeHistoryTotalCount() } returns 1
            every { krakenService.getLastTradeHistoryRawPageSize() } returns 1

            coEvery {
                orderIntentRepo.getKnownRebalancerOrderIdentities(
                    orderTxids = setOf("ORD-DOGE"),
                    clientOrderIds = emptySet(),
                )
            } returns RebalancerOrderIdentities(orderTxids = setOf("ORD-DOGE"))

            val verifier = ProspectiveEventContinuityVerifier(krakenService, orderIntentRepo)
            val result = verifier.verifyContinuity(segment, t1)

            result.shouldBeInstanceOf<ContinuityVerificationResult.Terminated>()
            result.reason shouldContain "trade in XDGUSD"
        }

        "regression E: events around exact baseline checkpoint timestamp cannot be silently skipped" {
            val krakenService = mockk<VerifierKrakenService>()
            val orderIntentRepo = mockk<ExecutionOrderIntentRepository>()

            // Deposit ledger occurred at exact baseline timestamp t0
            val depositAtExactBaseline = LedgerEvent(
                ledgerId = "L-DEP-EXACT",
                refid = "ref-exact",
                time = t0,
                type = "deposit",
                asset = "XXBT",
                amount = BigDecimal("0.5"),
                balance = BigDecimal("1.5"),
            )

            coEvery {
                krakenService.getLedgers(startSec = any(), offset = any(), endSec = any(), types = any())
            } returns listOf(depositAtExactBaseline)
            every { krakenService.hasLastLedgerPageShape() } returns true
            every { krakenService.hasLastLedgerTotalCount() } returns true
            every { krakenService.getLastLedgerTotalCount() } returns 1
            every { krakenService.getLastLedgerRawPageSize() } returns 1

            val verifier = ProspectiveEventContinuityVerifier(krakenService, orderIntentRepo)
            val result = verifier.verifyContinuity(segment, t1)

            // The deposit at exact baseline t0 MUST NOT be skipped; it must terminate the segment!
            result.shouldBeInstanceOf<ContinuityVerificationResult.Terminated>()
            result.reason shouldContain "External deposit or withdrawal detected"
            result.eventTime shouldBe t0
        }

        "external deposit terminates segment with flow-free reason" {
            val krakenService = mockk<VerifierKrakenService>()
            val orderIntentRepo = mockk<ExecutionOrderIntentRepository>()

            val depositLedger = LedgerEvent(
                ledgerId = "L-DEP-1",
                refid = "ref-1",
                time = t1.minusSeconds(60),
                type = "deposit",
                asset = "XXBT",
                amount = BigDecimal("0.5"),
                balance = BigDecimal("1.5"),
            )

            coEvery {
                krakenService.getLedgers(startSec = any(), offset = any(), endSec = any(), types = any())
            } returns listOf(depositLedger)
            every { krakenService.hasLastLedgerPageShape() } returns true
            every { krakenService.hasLastLedgerTotalCount() } returns true
            every { krakenService.getLastLedgerTotalCount() } returns 1
            every { krakenService.getLastLedgerRawPageSize() } returns 1

            val verifier = ProspectiveEventContinuityVerifier(krakenService, orderIntentRepo)
            val result = verifier.verifyContinuity(segment, t1)

            result.shouldBeInstanceOf<ContinuityVerificationResult.Terminated>()
            result.reason shouldBe "External deposit or withdrawal detected: deposit 0.5 XXBT"
            result.eventTime shouldBe depositLedger.time
        }

        "API failure in getLedgers returns PendingEvidence without terminating" {
            val krakenService = mockk<VerifierKrakenService>()
            val orderIntentRepo = mockk<ExecutionOrderIntentRepository>()

            coEvery {
                krakenService.getLedgers(startSec = any(), offset = any(), endSec = any(), types = any())
            } throws RuntimeException("Kraken rate limit exceeded")

            val verifier = ProspectiveEventContinuityVerifier(krakenService, orderIntentRepo)
            val result = verifier.verifyContinuity(segment, t1)

            result.shouldBeInstanceOf<ContinuityVerificationResult.PendingEvidence>()
            result.message shouldBe "Failed to fetch ledgers from Kraken: Kraken rate limit exceeded"
        }
    })
