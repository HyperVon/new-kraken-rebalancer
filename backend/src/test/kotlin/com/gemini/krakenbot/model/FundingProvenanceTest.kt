package com.gemini.krakenbot.model

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant

class FundingProvenanceTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        val now = Instant.parse("2026-06-01T12:00:00Z")

        "FundingProvenanceResolver NONE always returns UNRESOLVED" {
            val event = LedgerEvent(
                ledgerId = "L1",
                time = now,
                type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                asset = "USD",
                amount = BigDecimal("1000.00"),
            )
            FundingProvenanceResolver.NONE.resolve(event) shouldBe FundingEvidence.UNRESOLVED
        }

        "SimpleFundingProvenanceResolver resolves internal transfer by direct refid" {
            val transfer = InternalTransferRecord(
                refid = "REF-INT-1",
                asset = "USD",
                amount = BigDecimal("500.00"),
                time = now,
                sourceWallet = "Spot",
                destinationWallet = "Futures",
            )
            transfer.refid shouldBe "REF-INT-1"
            transfer.asset shouldBe "USD"
            transfer.amount shouldBe BigDecimal("500.00")
            transfer.time shouldBe now
            transfer.sourceWallet shouldBe "Spot"
            transfer.destinationWallet shouldBe "Futures"

            val resolver = SimpleFundingProvenanceResolver(
                internalTransfers = listOf(transfer),
            )
            val event = LedgerEvent(
                ledgerId = "L-INT",
                refid = "REF-INT-1",
                time = now,
                type = KrakenApiConstants.LEDGER_TYPE_TRANSFER,
                asset = "USD",
                amount = BigDecimal("500.00"),
            )
            resolver.resolve(event) shouldBe FundingEvidence.INTERNAL
        }

        "SimpleFundingProvenanceResolver resolves confirmed deposit by direct refid with method only" {
            val deposit = DepositStatusRecord(
                refid = "REF-DEP-M",
                txid = null,
                asset = "ETH",
                amount = BigDecimal("2.5"),
                fee = BigDecimal("0.001"),
                time = now,
                status = "Settled",
                method = "Ethereum",
            )
            val resolver = SimpleFundingProvenanceResolver(
                deposits = listOf(deposit),
            )
            val event = LedgerEvent(
                ledgerId = "L-DEP-M",
                refid = "REF-DEP-M",
                time = now,
                type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                asset = "ETH",
                amount = BigDecimal("2.5"),
            )
            resolver.resolve(event) shouldBe FundingEvidence.EXTERNAL
        }

        "SimpleFundingProvenanceResolver resolves confirmed deposit by direct refid with txid only" {
            val deposit = DepositStatusRecord(
                refid = "REF-DEP-1",
                txid = "0xabcdef",
                asset = "ETH",
                amount = BigDecimal("2.5"),
                fee = BigDecimal("0.001"),
                time = now,
                status = "Success",
                method = null,
            )
            val resolver = SimpleFundingProvenanceResolver(
                deposits = listOf(deposit),
            )
            val event = LedgerEvent(
                ledgerId = "L-DEP",
                refid = "REF-DEP-1",
                time = now,
                type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                asset = "ETH",
                amount = BigDecimal("2.5"),
            )
            resolver.resolve(event) shouldBe FundingEvidence.EXTERNAL
        }

        "SimpleFundingProvenanceResolver rejects unconfirmed deposit by direct refid" {
            val pendingDeposit = DepositStatusRecord(
                refid = "REF-DEP-PENDING",
                txid = "0xabcdef",
                asset = "ETH",
                amount = BigDecimal("2.5"),
                time = now,
                status = "Pending",
            )
            val unprovenDeposit = DepositStatusRecord(
                refid = "REF-DEP-NO-TXID",
                txid = null,
                asset = "ETH",
                amount = BigDecimal("2.5"),
                time = now,
                status = "Settled",
                method = null,
            )
            val failedDeposit = DepositStatusRecord(
                refid = "REF-DEP-FAILED",
                txid = "0xabcdef",
                asset = "ETH",
                amount = BigDecimal("2.5"),
                time = now,
                status = "Failed",
            )
            val resolver = SimpleFundingProvenanceResolver(
                deposits = listOf(pendingDeposit, unprovenDeposit, failedDeposit),
            )
            val eventPending = LedgerEvent(
                ledgerId = "L-P",
                refid = "REF-DEP-PENDING",
                time = now,
                type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                asset = "ETH",
                amount = BigDecimal("2.5"),
            )
            val eventNoTxid = LedgerEvent(
                ledgerId = "L-NT",
                refid = "REF-DEP-NO-TXID",
                time = now,
                type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                asset = "ETH",
                amount = BigDecimal("2.5"),
            )
            val eventFailed = LedgerEvent(
                ledgerId = "L-F",
                refid = "REF-DEP-FAILED",
                time = now,
                type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                asset = "ETH",
                amount = BigDecimal("2.5"),
            )
            resolver.resolve(eventPending) shouldBe FundingEvidence.UNRESOLVED
            resolver.resolve(eventNoTxid) shouldBe FundingEvidence.UNRESOLVED
            resolver.resolve(eventFailed) shouldBe FundingEvidence.UNRESOLVED
        }

        "SimpleFundingProvenanceResolver resolves confirmed withdrawal by direct refid with method only" {
            val withdrawal = WithdrawStatusRecord(
                refid = "REF-W-M",
                txid = null,
                asset = "BTC",
                amount = BigDecimal("1.0"),
                fee = BigDecimal("0.0001"),
                time = now,
                status = "Success",
                method = "Bitcoin",
            )
            val resolver = SimpleFundingProvenanceResolver(
                withdrawals = listOf(withdrawal),
            )
            val event = LedgerEvent(
                ledgerId = "L-W-M",
                refid = "REF-W-M",
                time = now,
                type = KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL,
                asset = "BTC",
                amount = BigDecimal("-1.0"),
            )
            resolver.resolve(event) shouldBe FundingEvidence.EXTERNAL
        }

        "SimpleFundingProvenanceResolver resolves confirmed withdrawal by direct refid with txid only" {
            val withdrawal = WithdrawStatusRecord(
                refid = "REF-W-1",
                txid = "0x123456",
                asset = "BTC",
                amount = BigDecimal("1.0"),
                fee = BigDecimal("0.0001"),
                time = now,
                status = "Settled",
                method = null,
            )
            val resolver = SimpleFundingProvenanceResolver(
                withdrawals = listOf(withdrawal),
            )
            val event = LedgerEvent(
                ledgerId = "L-W",
                refid = "REF-W-1",
                time = now,
                type = KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL,
                asset = "BTC",
                amount = BigDecimal("-1.0"),
            )
            resolver.resolve(event) shouldBe FundingEvidence.EXTERNAL
        }

        "SimpleFundingProvenanceResolver rejects unconfirmed withdrawal by direct refid" {
            val unconfirmed = WithdrawStatusRecord(
                refid = "REF-W-PENDING",
                txid = "0x123456",
                asset = "BTC",
                amount = BigDecimal("1.0"),
                time = now,
                status = "Pending",
            )
            val noProof = WithdrawStatusRecord(
                refid = "REF-W-NO-PROOF",
                txid = null,
                asset = "BTC",
                amount = BigDecimal("1.0"),
                time = now,
                status = "Settled",
                method = null,
            )
            val failed = WithdrawStatusRecord(
                refid = "REF-W-FAILED",
                txid = "0x123456",
                asset = "BTC",
                amount = BigDecimal("1.0"),
                time = now,
                status = "Failed",
            )
            val resolver = SimpleFundingProvenanceResolver(
                withdrawals = listOf(unconfirmed, noProof, failed),
            )
            val eventPending = LedgerEvent(
                ledgerId = "L-WP",
                refid = "REF-W-PENDING",
                time = now,
                type = KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL,
                asset = "BTC",
                amount = BigDecimal("-1.0"),
            )
            val eventNoProof = LedgerEvent(
                ledgerId = "L-NP",
                refid = "REF-W-NO-PROOF",
                time = now,
                type = KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL,
                asset = "BTC",
                amount = BigDecimal("-1.0"),
            )
            val eventFailed = LedgerEvent(
                ledgerId = "L-F",
                refid = "REF-W-FAILED",
                time = now,
                type = KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL,
                asset = "BTC",
                amount = BigDecimal("-1.0"),
            )
            resolver.resolve(eventPending) shouldBe FundingEvidence.UNRESOLVED
            resolver.resolve(eventNoProof) shouldBe FundingEvidence.UNRESOLVED
            resolver.resolve(eventFailed) shouldBe FundingEvidence.UNRESOLVED
        }

        "SimpleFundingProvenanceResolver handles blank refid gracefully" {
            val resolver = SimpleFundingProvenanceResolver()
            val event = LedgerEvent(
                ledgerId = "L-BLANK",
                refid = "   ",
                time = now,
                type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                asset = "USD",
                amount = BigDecimal("100.00"),
            )
            resolver.resolve(event) shouldBe FundingEvidence.UNRESOLVED
        }

        "SimpleFundingProvenanceResolver resolves internal transfer by correlation" {
            val transfer = InternalTransferRecord(
                refid = "OTHER-REF",
                asset = "USDT",
                amount = BigDecimal("100.00"),
                time = now.minusSeconds(60),
            )
            val resolver = SimpleFundingProvenanceResolver(
                internalTransfers = listOf(transfer),
            )
            val eventMatch = LedgerEvent(
                ledgerId = "L-CORR-INT",
                refid = null,
                time = now,
                type = KrakenApiConstants.LEDGER_TYPE_TRANSFER,
                asset = "USDT",
                amount = BigDecimal("100.00"),
            )
            val eventDiffAsset = LedgerEvent(
                ledgerId = "L-DIFF-ASSET",
                refid = null,
                time = now,
                type = KrakenApiConstants.LEDGER_TYPE_TRANSFER,
                asset = "BTC",
                amount = BigDecimal("100.00"),
            )
            val eventDiffAmount = LedgerEvent(
                ledgerId = "L-DIFF-AMT",
                refid = null,
                time = now,
                type = KrakenApiConstants.LEDGER_TYPE_TRANSFER,
                asset = "USDT",
                amount = BigDecimal("200.00"),
            )
            val eventDiffTime = LedgerEvent(
                ledgerId = "L-DIFF-TIME",
                refid = null,
                time = now.plusSeconds(300),
                type = KrakenApiConstants.LEDGER_TYPE_TRANSFER,
                asset = "USDT",
                amount = BigDecimal("100.00"),
            )
            resolver.resolve(eventMatch) shouldBe FundingEvidence.INTERNAL
            resolver.resolve(eventDiffAsset) shouldBe FundingEvidence.UNRESOLVED
            resolver.resolve(eventDiffAmount) shouldBe FundingEvidence.UNRESOLVED
            resolver.resolve(eventDiffTime) shouldBe FundingEvidence.UNRESOLVED
        }

        "SimpleFundingProvenanceResolver resolves deposit by correlation" {
            val deposit = DepositStatusRecord(
                refid = "OTHER-DEP-REF",
                txid = "0x987654",
                asset = "SOL",
                amount = BigDecimal("10.0"),
                time = now.plusSeconds(30),
                status = "Success",
            )
            val resolver = SimpleFundingProvenanceResolver(
                deposits = listOf(deposit),
            )
            val eventMatch = LedgerEvent(
                ledgerId = "L-CORR-DEP",
                refid = null,
                time = now,
                type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                asset = "SOL",
                amount = BigDecimal("10.0"),
            )
            val eventDiffAsset = LedgerEvent(
                ledgerId = "L-DIFF-A",
                refid = null,
                time = now,
                type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                asset = "ETH",
                amount = BigDecimal("10.0"),
            )
            val eventDiffAmount = LedgerEvent(
                ledgerId = "L-DIFF-AMT",
                refid = null,
                time = now,
                type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                asset = "SOL",
                amount = BigDecimal("20.0"),
            )
            val eventDiffTime = LedgerEvent(
                ledgerId = "L-DIFF-T",
                refid = null,
                time = now.plusSeconds(300),
                type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                asset = "SOL",
                amount = BigDecimal("10.0"),
            )
            resolver.resolve(eventMatch) shouldBe FundingEvidence.EXTERNAL
            resolver.resolve(eventDiffAsset) shouldBe FundingEvidence.UNRESOLVED
            resolver.resolve(eventDiffAmount) shouldBe FundingEvidence.UNRESOLVED
            resolver.resolve(eventDiffTime) shouldBe FundingEvidence.UNRESOLVED
        }

        "SimpleFundingProvenanceResolver resolves withdrawal by correlation" {
            val withdrawal = WithdrawStatusRecord(
                refid = "OTHER-W-REF",
                txid = "0x987654",
                asset = "SOL",
                amount = BigDecimal("5.0"),
                time = now.minusSeconds(120),
                status = "Success",
            )
            val resolver = SimpleFundingProvenanceResolver(
                withdrawals = listOf(withdrawal),
            )
            val eventMatch = LedgerEvent(
                ledgerId = "L-CORR-W",
                refid = null,
                time = now,
                type = KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL,
                asset = "SOL",
                amount = BigDecimal("-5.0"),
            )
            val eventDiffAsset = LedgerEvent(
                ledgerId = "L-DIFF-WA",
                refid = null,
                time = now,
                type = KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL,
                asset = "ETH",
                amount = BigDecimal("-5.0"),
            )
            val eventDiffAmount = LedgerEvent(
                ledgerId = "L-DIFF-WAMT",
                refid = null,
                time = now,
                type = KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL,
                asset = "SOL",
                amount = BigDecimal("-10.0"),
            )
            val eventDiffTime = LedgerEvent(
                ledgerId = "L-DIFF-WT",
                refid = null,
                time = now.plusSeconds(300),
                type = KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL,
                asset = "SOL",
                amount = BigDecimal("-5.0"),
            )
            resolver.resolve(eventMatch) shouldBe FundingEvidence.EXTERNAL
            resolver.resolve(eventDiffAsset) shouldBe FundingEvidence.UNRESOLVED
            resolver.resolve(eventDiffAmount) shouldBe FundingEvidence.UNRESOLVED
            resolver.resolve(eventDiffTime) shouldBe FundingEvidence.UNRESOLVED
        }

        "SimpleFundingProvenanceResolver returns UNRESOLVED when no records match correlation criteria" {
            val depositFar = DepositStatusRecord(
                refid = "FAR-DEP",
                txid = "0x987654",
                asset = "SOL",
                amount = BigDecimal("10.0"),
                time = now.minusSeconds(300),
                status = "Success",
            )
            val withdrawalDiffAsset = WithdrawStatusRecord(
                refid = "DIFF-W",
                txid = "0x987654",
                asset = "BTC",
                amount = BigDecimal("5.0"),
                time = now,
                status = "Success",
            )
            val resolver = SimpleFundingProvenanceResolver(
                deposits = listOf(depositFar),
                withdrawals = listOf(withdrawalDiffAsset),
            )
            val eventDeposit = LedgerEvent(
                ledgerId = "L-NONE-1",
                time = now,
                type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                asset = "SOL",
                amount = BigDecimal("10.0"),
            )
            val eventWithdrawal = LedgerEvent(
                ledgerId = "L-NONE-2",
                time = now,
                type = KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL,
                asset = "SOL",
                amount = BigDecimal("-5.0"),
            )
            val eventOther = LedgerEvent(
                ledgerId = "L-NONE-3",
                time = now,
                type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                asset = "SOL",
                amount = BigDecimal("1.0"),
            )
            resolver.resolve(eventDeposit) shouldBe FundingEvidence.UNRESOLVED
            resolver.resolve(eventWithdrawal) shouldBe FundingEvidence.UNRESOLVED
            resolver.resolve(eventOther) shouldBe FundingEvidence.UNRESOLVED
        }
    }
}
