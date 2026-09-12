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

        "direct refid tolerates booking-time lag beyond the correlation window" {
            val deposit = DepositStatusRecord(
                refid = "REF-LAG",
                txid = "0xlag",
                asset = "ETH",
                amount = BigDecimal("2.5"),
                fee = BigDecimal("0.001"),
                time = now.plusSeconds(600),
                status = "Success",
                method = "Ethereum",
            )
            val resolver = SimpleFundingProvenanceResolver(
                deposits = listOf(deposit),
            )
            val event = LedgerEvent(
                ledgerId = "L-LAG",
                refid = "REF-LAG",
                time = now,
                type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                asset = "ETH",
                amount = BigDecimal("2.5"),
            )

            resolver.resolve(event) shouldBe FundingEvidence.EXTERNAL
        }

        "direct refid tolerates representation-level amount drift" {
            val deposit = DepositStatusRecord(
                refid = "REF-DRIFT",
                txid = "0xdrift",
                asset = "RENDER",
                amount = BigDecimal("214.20305909"),
                fee = BigDecimal.ZERO,
                time = now,
                status = "Success",
                method = "RENDER - Sol",
            )
            val resolver = SimpleFundingProvenanceResolver(
                deposits = listOf(deposit),
            )
            val event = LedgerEvent(
                ledgerId = "L-DRIFT",
                refid = "REF-DRIFT",
                time = now,
                type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                asset = "RENDER",
                amount = BigDecimal("214.203059"),
            )

            resolver.resolve(event) shouldBe FundingEvidence.EXTERNAL
        }

        "fuzzy matching keeps the strict correlation window" {
            val deposit = DepositStatusRecord(
                refid = "REF-FUZZY",
                txid = "0xfuzzy",
                asset = "ETH",
                amount = BigDecimal("2.5"),
                fee = BigDecimal("0.001"),
                time = now.plusSeconds(600),
                status = "Success",
                method = "Ethereum",
            )
            val resolver = SimpleFundingProvenanceResolver(
                deposits = listOf(deposit),
            )
            val event = LedgerEvent(
                ledgerId = "L-FUZZY",
                refid = null,
                time = now,
                type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                asset = "ETH",
                amount = BigDecimal("2.5"),
            )

            resolver.resolve(event) shouldBe FundingEvidence.UNRESOLVED
            resolver.explain(event) shouldBe "no funding record matched"
        }

        "explain reports bounded unresolved reasons without identifiers" {
            val pendingEvent = LedgerEvent(
                ledgerId = "L-PENDING",
                refid = "REF-PENDING",
                time = now,
                type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                asset = "ETH",
                amount = BigDecimal("2.5"),
            )
            val pending = SimpleFundingProvenanceResolver(
                deposits = listOf(
                    DepositStatusRecord(
                        refid = "REF-PENDING",
                        txid = "0xpending",
                        asset = "ETH",
                        amount = BigDecimal("2.5"),
                        fee = BigDecimal("0.001"),
                        time = now,
                        status = "Pending",
                        method = "Ethereum",
                    ),
                ),
            )
            pending.resolve(pendingEvent) shouldBe FundingEvidence.UNRESOLVED
            pending.explain(pendingEvent) shouldBe "funding record is not in a terminal status"

            val noProofEvent = LedgerEvent(
                ledgerId = "L-NOPROOF",
                refid = "REF-NOPROOF",
                time = now,
                type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                asset = "ETH",
                amount = BigDecimal("2.5"),
            )
            val noProof = SimpleFundingProvenanceResolver(
                deposits = listOf(
                    DepositStatusRecord(
                        refid = "REF-NOPROOF",
                        txid = null,
                        asset = "ETH",
                        amount = BigDecimal("2.5"),
                        fee = BigDecimal("0.001"),
                        time = now,
                        status = "Success",
                        method = null,
                    ),
                ),
            )
            noProof.resolve(noProofEvent) shouldBe FundingEvidence.UNRESOLVED
            noProof.explain(noProofEvent) shouldBe "funding record lacks external proof"

            val empty = SimpleFundingProvenanceResolver()
            empty.explain(noProofEvent) shouldBe "no funding record matched"

            val tradeEvent = LedgerEvent(
                ledgerId = "L-TRADE",
                refid = "REF-TRADE",
                time = now,
                type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                asset = "ETH",
                amount = BigDecimal("2.5"),
            )
            empty.explain(tradeEvent) shouldBe null
        }

        "unavailable provenance explains permission and request failures" {
            val event = LedgerEvent(
                ledgerId = "L-UNAVAILABLE",
                refid = "REF-UNAVAILABLE",
                time = now,
                type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                asset = "ETH",
                amount = BigDecimal("2.5"),
            )
            val permissionDenied = FundingProvenanceResolver.unavailable(
                FundingProvenanceFailure(FundingProvenanceFailureReason.PERMISSION_DENIED, "denied"),
            )
            permissionDenied.explain(event) shouldBe "funding evidence unavailable: permission denied"

            val requestFailed = FundingProvenanceResolver.unavailable(
                FundingProvenanceFailure(FundingProvenanceFailureReason.REQUEST_FAILED, "failed"),
            )
            requestFailed.explain(event) shouldBe "funding evidence unavailable: request failed"
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

        "fuzzy correlation does not discard an unconfirmed candidate before uniqueness is checked" {
            val pending = DepositStatusRecord(
                refid = "FUZZY-PENDING",
                asset = "USD",
                amount = BigDecimal("100.00"),
                time = now,
                status = "Pending",
                method = "Wire",
            )
            val confirmed = pending.copy(
                refid = "FUZZY-CONFIRMED",
                status = "Success",
            )
            val resolver = SimpleFundingProvenanceResolver(deposits = listOf(pending, confirmed))

            resolver.resolve(
                LedgerEvent(
                    ledgerId = "fuzzy-pending-and-confirmed",
                    time = now,
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    asset = "USD",
                    amount = BigDecimal("100.00"),
                ),
            ) shouldBe FundingEvidence.UNRESOLVED
        }

        "internal funding method is not treated as external proof" {
            val resolver = SimpleFundingProvenanceResolver(
                deposits = listOf(
                    DepositStatusRecord(
                        refid = "REF-FUTURES",
                        asset = "USD",
                        amount = BigDecimal("100.00"),
                        time = now,
                        status = "Success",
                        method = "Futures transfer",
                    ),
                ),
            )

            resolver.resolve(
                LedgerEvent(
                    ledgerId = "futures-deposit",
                    refid = "REF-FUTURES",
                    time = now,
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    asset = "USD",
                    amount = BigDecimal("100.00"),
                ),
            ) shouldBe FundingEvidence.UNRESOLVED
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

        "direct refid correlation rejects field contradictions" {
            val deposit = DepositStatusRecord(
                refid = "DIRECT-1",
                txid = "0x123",
                asset = "XBT",
                amount = BigDecimal("1.25"),
                time = now,
                status = "Success",
            )
            val resolver = SimpleFundingProvenanceResolver(
                deposits = listOf(
                    deposit,
                    deposit.copy(refid = "VALID-FEE", asset = "USD"),
                ),
            )

            resolver.resolve(
                LedgerEvent(
                    ledgerId = "wrong-asset",
                    refid = "DIRECT-1",
                    time = now,
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    asset = "ETH",
                    amount = BigDecimal("1.25"),
                ),
            ) shouldBe FundingEvidence.UNRESOLVED
            resolver.resolve(
                LedgerEvent(
                    ledgerId = "wrong-type",
                    refid = "DIRECT-1",
                    time = now,
                    type = KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL,
                    asset = "BTC",
                    amount = BigDecimal("-1.25"),
                ),
            ) shouldBe FundingEvidence.UNRESOLVED
            resolver.resolve(
                LedgerEvent(
                    ledgerId = "wrong-amount",
                    refid = "DIRECT-1",
                    time = now,
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    asset = "BTC",
                    amount = BigDecimal("1.24"),
                ),
            ) shouldBe FundingEvidence.UNRESOLVED
            resolver.resolve(
                LedgerEvent(
                    ledgerId = "wrong-direction",
                    refid = "DIRECT-1",
                    time = now,
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    asset = "BTC",
                    amount = BigDecimal("-1.25"),
                ),
            ) shouldBe FundingEvidence.UNRESOLVED
        }

        "correlation rejects blank normalized assets and malformed ledger fees" {
            val deposit = DepositStatusRecord(
                refid = "BLANK-ASSET",
                txid = "0xblank",
                asset = "",
                amount = BigDecimal("10.00"),
                time = now,
                status = "Success",
            )
            val resolver = SimpleFundingProvenanceResolver(
                deposits = listOf(
                    deposit,
                    deposit.copy(refid = "VALID-FEE", asset = "USD"),
                ),
            )

            resolver.resolve(
                LedgerEvent(
                    ledgerId = "blank-asset",
                    refid = "BLANK-ASSET",
                    time = now,
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    asset = "USD",
                    amount = BigDecimal("10.00"),
                ),
            ) shouldBe FundingEvidence.UNRESOLVED
            resolver.resolve(
                LedgerEvent(
                    ledgerId = "invalid-fee",
                    refid = "VALID-FEE",
                    time = now,
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    asset = "USD",
                    amount = BigDecimal("10.00"),
                    hasValidFee = false,
                ),
            ) shouldBe FundingEvidence.UNRESOLVED
        }

        "explain reports duplicate direct funding references" {
            val first = DepositStatusRecord(
                refid = "REF-DUPLICATE",
                txid = "0x1",
                asset = "ETH",
                amount = BigDecimal("2.5"),
                fee = BigDecimal("0.0"),
                time = now,
                status = "Success",
                method = "Ethereum",
            )
            val second = first.copy(txid = "0x2")
            val resolver = SimpleFundingProvenanceResolver(deposits = listOf(first, second))
            val event = LedgerEvent(
                ledgerId = "L-DUPLICATE",
                refid = "REF-DUPLICATE",
                time = now,
                type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                asset = "ETH",
                amount = BigDecimal("2.5"),
            )

            resolver.resolve(event) shouldBe FundingEvidence.UNRESOLVED
            resolver.explain(event) shouldBe "multiple funding records share the ledger reference"
        }

        "explain reports direct mismatches and conflicting evidence" {
            val mismatched = DepositStatusRecord(
                refid = "REF-MISMATCH",
                txid = "0xm",
                asset = "BTC",
                amount = BigDecimal("2.5"),
                fee = BigDecimal("0.0"),
                time = now,
                status = "Success",
                method = "Bitcoin",
            )
            val mismatchEvent = LedgerEvent(
                ledgerId = "L-MISMATCH",
                refid = "REF-MISMATCH",
                time = now,
                type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                asset = "ETH",
                amount = BigDecimal("2.5"),
            )
            val mismatchResolver = SimpleFundingProvenanceResolver(deposits = listOf(mismatched))

            mismatchResolver.explain(mismatchEvent) shouldBe "direct funding record does not match the ledger row"

            val direct = DepositStatusRecord(
                refid = "REF-DIRECT",
                txid = "0xd",
                asset = "ETH",
                amount = BigDecimal("2.5"),
                fee = BigDecimal("0.0"),
                time = now,
                status = "Success",
                method = "Ethereum",
            )
            val pending = direct.copy(refid = "REF-PENDING", status = "Pending")
            val conflictResolver = SimpleFundingProvenanceResolver(deposits = listOf(direct, pending))
            val conflictEvent = LedgerEvent(
                ledgerId = "L-CONFLICT",
                refid = "REF-DIRECT",
                time = now,
                type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                asset = "ETH",
                amount = BigDecimal("2.5"),
            )

            conflictResolver.resolve(conflictEvent) shouldBe FundingEvidence.UNRESOLVED
            conflictResolver.explain(conflictEvent) shouldBe "conflicting funding evidence"
        }

        "explain reports fuzzy duplicate matches" {
            val first = DepositStatusRecord(
                refid = "REF-FUZZY-A",
                txid = "0xa",
                asset = "ETH",
                amount = BigDecimal("2.5"),
                fee = BigDecimal("0.0"),
                time = now,
                status = "Success",
                method = "Ethereum",
            )
            val second = first.copy(refid = "REF-FUZZY-B", txid = "0xb")
            val resolver = SimpleFundingProvenanceResolver(deposits = listOf(first, second))
            val event = LedgerEvent(
                ledgerId = "L-FUZZY-DUPLICATE",
                refid = null,
                time = now,
                type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                asset = "ETH",
                amount = BigDecimal("2.5"),
            )

            resolver.resolve(event) shouldBe FundingEvidence.UNRESOLVED
            resolver.explain(event) shouldBe "multiple funding records matched"
        }

        "internal funding methods lack external proof and card methods are recognised" {
            val internalRecord = DepositStatusRecord(
                refid = "REF-INTERNAL-METHOD",
                txid = null,
                asset = "ETH",
                amount = BigDecimal("2.5"),
                fee = BigDecimal("0.0"),
                time = now,
                status = "Success",
                method = "Internal Transfer",
            )
            val resolver = SimpleFundingProvenanceResolver(deposits = listOf(internalRecord))
            val event = LedgerEvent(
                ledgerId = "L-INTERNAL-METHOD",
                refid = "REF-INTERNAL-METHOD",
                time = now,
                type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                asset = "ETH",
                amount = BigDecimal("2.5"),
            )

            resolver.resolve(event) shouldBe FundingEvidence.UNRESOLVED
            resolver.explain(event) shouldBe "funding record lacks external proof"

            val cardRecord = internalRecord.copy(refid = "REF-CARD", method = "Visa debit card")
            val cardResolver = SimpleFundingProvenanceResolver(deposits = listOf(cardRecord))
            val cardEvent = event.copy(ledgerId = "L-CARD", refid = "REF-CARD")

            cardResolver.isCardFunding(cardEvent) shouldBe true

            val nonCardResolver = SimpleFundingProvenanceResolver(
                deposits = listOf(cardRecord.copy(refid = "REF-NON-CARD", method = "Ethereum")),
            )
            nonCardResolver.isCardFunding(
                cardEvent.copy(ledgerId = "L-NON-CARD", refid = "REF-NON-CARD"),
            ) shouldBe false
        }

        "withdrawal correlation counts the authoritative fee" {
            val record = WithdrawStatusRecord(
                refid = "REF-WITHDRAW-FEE",
                txid = "0xw",
                asset = "USD",
                amount = BigDecimal("100.25"),
                fee = BigDecimal("0.25"),
                time = now,
                status = "Success",
                method = "Wire",
            )
            val resolver = SimpleFundingProvenanceResolver(withdrawals = listOf(record))
            val event = LedgerEvent(
                ledgerId = "L-WITHDRAW-FEE",
                refid = "REF-WITHDRAW-FEE",
                time = now,
                type = KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL,
                asset = "USD",
                amount = BigDecimal("-100.50"),
                fee = BigDecimal("0.25"),
                hasAuthoritativeFee = true,
            )

            resolver.resolve(event) shouldBe FundingEvidence.EXTERNAL
        }

        "negative fees never correlate" {
            val record = DepositStatusRecord(
                refid = "REF-NEGATIVE-FEE",
                txid = "0xn",
                asset = "ETH",
                amount = BigDecimal("2.5"),
                fee = BigDecimal("0.001"),
                time = now,
                status = "Success",
                method = "Ethereum",
            )
            val resolver = SimpleFundingProvenanceResolver(deposits = listOf(record))
            val event = LedgerEvent(
                ledgerId = "L-NEGATIVE-FEE",
                refid = "REF-NEGATIVE-FEE",
                time = now,
                type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                asset = "ETH",
                amount = BigDecimal("2.5"),
                fee = BigDecimal("-0.001"),
                hasAuthoritativeFee = true,
            )

            resolver.resolve(event) shouldBe FundingEvidence.UNRESOLVED
        }

        "explain returns null when funding evidence resolves" {
            val deposit = DepositStatusRecord(
                refid = "REF-EXPLAIN-OK",
                txid = "0xabc",
                asset = "ETH",
                amount = BigDecimal("1.0"),
                fee = BigDecimal("0.001"),
                time = now,
                status = "Success",
                method = "Ethereum",
            )
            val resolver = SimpleFundingProvenanceResolver(
                deposits = listOf(deposit),
            )
            val event = LedgerEvent(
                ledgerId = "L-EXPLAIN-OK",
                refid = "REF-EXPLAIN-OK",
                time = now,
                type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                asset = "ETH",
                amount = BigDecimal("1.0"),
            )

            resolver.resolve(event) shouldBe FundingEvidence.EXTERNAL
            resolver.explain(event) shouldBe null
        }

        "card funding requires a single external matching deposit" {
            val event = LedgerEvent(
                ledgerId = "L-CARD",
                refid = "REF-CARD",
                time = now,
                type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                asset = "USD",
                amount = BigDecimal("100.00"),
            )
            val cardDeposit = DepositStatusRecord(
                refid = "REF-CARD",
                txid = "0xcard",
                asset = "USD",
                amount = BigDecimal("100.00"),
                fee = BigDecimal("0.00"),
                time = now,
                status = "Success",
                method = "Visa debit card",
            )

            SimpleFundingProvenanceResolver(deposits = emptyList()).isCardFunding(event) shouldBe false
            SimpleFundingProvenanceResolver(
                deposits = listOf(cardDeposit.copy(status = "Pending")),
            ).isCardFunding(event) shouldBe false
            SimpleFundingProvenanceResolver(
                deposits = listOf(cardDeposit.copy(method = null)),
            ).isCardFunding(event) shouldBe false
        }

        "explain reports a single unresolved fuzzy match" {
            val withdrawal = WithdrawStatusRecord(
                refid = "W-FUZZY",
                txid = "0xfuzzy",
                asset = "USD",
                amount = BigDecimal("100.00"),
                fee = BigDecimal("0.25"),
                time = now,
                status = "Pending",
                method = "Ethereum",
            )
            val resolver = SimpleFundingProvenanceResolver(
                withdrawals = listOf(withdrawal),
            )
            val event = LedgerEvent(
                ledgerId = "L-W-FUZZY",
                refid = null,
                time = now,
                type = KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL,
                asset = "USD",
                amount = BigDecimal("-100.25"),
                fee = BigDecimal("0.25"),
                hasAuthoritativeFee = true,
            )

            resolver.resolve(event) shouldBe FundingEvidence.UNRESOLVED
            resolver.explain(event) shouldBe "funding record is not in a terminal status"
        }

        "card funding matches by direct reference" {
            val cardDeposit = DepositStatusRecord(
                refid = "CARD-REF",
                txid = "0xcard",
                asset = "USD",
                amount = BigDecimal("100.00"),
                fee = BigDecimal.ZERO,
                time = now,
                status = "Success",
                method = "Mastercard",
            )
            val cardEvent = LedgerEvent(
                ledgerId = "L-CARD",
                refid = "CARD-REF",
                time = now,
                type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                asset = "USD",
                amount = BigDecimal("100.00"),
            )
            val nonCardResolver = SimpleFundingProvenanceResolver(
                deposits = listOf(cardDeposit.copy(method = "Ethereum")),
            )

            SimpleFundingProvenanceResolver(deposits = listOf(cardDeposit)).isCardFunding(cardEvent) shouldBe true
            nonCardResolver.isCardFunding(cardEvent) shouldBe false
        }

        "non-funded events and zero amounts never resolve" {
            val withdrawalEvent = LedgerEvent(
                ledgerId = "L-CARD-W",
                refid = "CARD-W",
                time = now,
                type = KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL,
                asset = "USD",
                amount = BigDecimal("-100"),
            )
            SimpleFundingProvenanceResolver(
                deposits = listOf(
                    DepositStatusRecord(
                        refid = "CARD-W",
                        txid = "0xcard",
                        asset = "USD",
                        amount = BigDecimal("100"),
                        fee = BigDecimal.ZERO,
                        time = now,
                        status = "Success",
                        method = "Mastercard",
                    ),
                ),
            ).isCardFunding(withdrawalEvent) shouldBe false

            val zeroResolver = SimpleFundingProvenanceResolver(
                deposits = listOf(
                    DepositStatusRecord(
                        refid = "ZERO-RECORD",
                        txid = "0xzero",
                        asset = "ETH",
                        amount = BigDecimal.ZERO,
                        fee = BigDecimal.ZERO,
                        time = now,
                        status = "Success",
                        method = "Ethereum",
                    ),
                ),
            )
            val zeroEvent = LedgerEvent(
                ledgerId = "L-ZERO",
                refid = "ZERO-RECORD",
                time = now,
                type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                asset = "ETH",
                amount = BigDecimal.ZERO,
            )
            zeroResolver.resolve(zeroEvent) shouldBe FundingEvidence.UNRESOLVED

            val fundedEvent = zeroEvent.copy(amount = BigDecimal("1.0"))
            zeroResolver.resolve(fundedEvent) shouldBe FundingEvidence.UNRESOLVED
        }

        "card funding matches by fuzzy correlation without a reference" {
            val cardDeposit = DepositStatusRecord(
                refid = "CARD-FUZZY",
                txid = "0xcardfuzzy",
                asset = "USD",
                amount = BigDecimal("100.00"),
                fee = BigDecimal.ZERO,
                time = now,
                status = "Success",
                method = "Visa debit card",
            )
            val resolver = SimpleFundingProvenanceResolver(deposits = listOf(cardDeposit))
            val event = LedgerEvent(
                ledgerId = "L-CARD-FUZZY",
                refid = null,
                time = now,
                type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                asset = "USD",
                amount = BigDecimal("100.00"),
            )

            resolver.isCardFunding(event) shouldBe true
        }

        "explain ignores unrelated records during conflict scans" {
            val direct = DepositStatusRecord(
                refid = "REF-SCAN",
                txid = "0xscan",
                asset = "ETH",
                amount = BigDecimal("2.5"),
                fee = BigDecimal("0.001"),
                time = now,
                status = "Success",
                method = "Ethereum",
            )
            val unrelated = DepositStatusRecord(
                refid = "REF-UNRELATED",
                txid = "0xunrelated",
                asset = "BTC",
                amount = BigDecimal("0.5"),
                fee = BigDecimal("0.001"),
                time = now,
                status = "Success",
                method = "Bitcoin",
            )
            val resolver = SimpleFundingProvenanceResolver(
                deposits = listOf(direct, unrelated),
            )
            val event = LedgerEvent(
                ledgerId = "L-SCAN",
                refid = "REF-SCAN",
                time = now,
                type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                asset = "ETH",
                amount = BigDecimal("2.5"),
            )

            resolver.resolve(event) shouldBe FundingEvidence.EXTERNAL
            resolver.explain(event) shouldBe null
        }

        "correlation honors authoritative fee semantics and full timestamp precision" {
            val deposit = DepositStatusRecord(
                refid = "FEE-1",
                txid = "0xfee",
                asset = "USD",
                amount = BigDecimal("100.00"),
                fee = BigDecimal("2.00"),
                time = now,
                status = "Success",
                hasAuthoritativeFee = true,
            )
            val resolver = SimpleFundingProvenanceResolver(
                deposits = listOf(
                    deposit,
                    deposit.copy(refid = "VALID-FEE", asset = "USD"),
                ),
            )

            resolver.resolve(
                LedgerEvent(
                    ledgerId = "gross-net-compatible",
                    refid = "FEE-1",
                    time = now,
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    asset = "ZUSD",
                    amount = BigDecimal("102.00"),
                    fee = BigDecimal("2.00"),
                    hasAuthoritativeFee = true,
                ),
            ) shouldBe FundingEvidence.EXTERNAL
            resolver.resolve(
                LedgerEvent(
                    ledgerId = "fee-mismatch",
                    refid = "FEE-1",
                    time = now,
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    asset = "USD",
                    amount = BigDecimal("103.00"),
                    fee = BigDecimal("3.00"),
                    hasAuthoritativeFee = true,
                ),
            ) shouldBe FundingEvidence.UNRESOLVED
            SimpleFundingProvenanceResolver(deposits = listOf(deposit)).resolve(
                LedgerEvent(
                    ledgerId = "subsecond-outside-window",
                    refid = null,
                    time = now.plusMillis(180_001),
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    asset = "USD",
                    amount = BigDecimal("102.00"),
                    fee = BigDecimal("2.00"),
                    hasAuthoritativeFee = true,
                ),
            ) shouldBe FundingEvidence.UNRESOLVED
        }

        "withdrawal correlation treats the ledger debit as amount plus fee" {
            val withdrawal = WithdrawStatusRecord(
                refid = "WITHDRAWAL-GROSS-DEBIT",
                txid = "0xwithdrawal",
                asset = "BTC",
                amount = BigDecimal("1.00000000"),
                fee = BigDecimal("0.00010000"),
                time = now,
                status = "Success",
                hasAuthoritativeFee = true,
            )
            val resolver = SimpleFundingProvenanceResolver(withdrawals = listOf(withdrawal))

            resolver.resolve(
                LedgerEvent(
                    ledgerId = "withdrawal-net-ledger-amount",
                    refid = withdrawal.refid,
                    time = now,
                    type = KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL,
                    asset = "XBT",
                    amount = BigDecimal("-1.00000000"),
                    fee = BigDecimal("0.00010000"),
                    hasAuthoritativeFee = true,
                ),
            ) shouldBe FundingEvidence.EXTERNAL
            resolver.resolve(
                LedgerEvent(
                    ledgerId = "withdrawal-gross-ledger-amount",
                    refid = withdrawal.refid,
                    time = now,
                    type = KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL,
                    asset = "BTC",
                    amount = BigDecimal("-1.00010000"),
                ),
            ) shouldBe FundingEvidence.EXTERNAL
        }

        "fuzzy correlation requires one candidate and rejects external-internal conflicts" {
            val external = DepositStatusRecord(
                refid = "FUZZY-EXT",
                txid = "0x456",
                asset = "USD",
                amount = BigDecimal("100.00"),
                time = now,
                status = "Success",
            )
            val duplicate = external.copy(refid = "FUZZY-DUP")
            val uniqueResolver = SimpleFundingProvenanceResolver(deposits = listOf(external))
            val event = LedgerEvent(
                ledgerId = "fuzzy-event",
                time = now.plusSeconds(30),
                type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                asset = "ZUSD",
                amount = BigDecimal("100.00"),
            )
            uniqueResolver.resolve(event) shouldBe FundingEvidence.EXTERNAL

            SimpleFundingProvenanceResolver(deposits = listOf(external, duplicate)).resolve(event) shouldBe
                FundingEvidence.UNRESOLVED
            SimpleFundingProvenanceResolver(
                deposits = listOf(external),
                internalTransfers = listOf(
                    InternalTransferRecord(
                        refid = "FUZZY-INT",
                        asset = "USD",
                        amount = BigDecimal("100.00"),
                        time = now.plusSeconds(30),
                    ),
                ),
            ).resolve(event) shouldBe FundingEvidence.UNRESOLVED
        }

        "exact external refid does not hide a conflicting internal candidate" {
            val event = LedgerEvent(
                ledgerId = "direct-conflict-event",
                refid = "DIRECT-EXT",
                time = now,
                type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                asset = "USD",
                amount = BigDecimal("100.00"),
            )
            val resolver = SimpleFundingProvenanceResolver(
                deposits = listOf(
                    DepositStatusRecord(
                        refid = "DIRECT-EXT",
                        txid = "0x789",
                        asset = "USD",
                        amount = BigDecimal("100.00"),
                        time = now,
                        status = "Settled",
                    ),
                ),
                internalTransfers = listOf(
                    InternalTransferRecord(
                        refid = "OTHER-INTERNAL",
                        asset = "USD",
                        amount = BigDecimal("100.00"),
                        time = now,
                    ),
                ),
            )

            resolver.resolve(event) shouldBe FundingEvidence.UNRESOLVED
        }

        "correlation rejects duplicate identities and invalid signed or fee fields" {
            val deposit = DepositStatusRecord(
                refid = "DUPLICATE",
                txid = "0xduplicate",
                asset = "USD",
                amount = BigDecimal("10.00"),
                time = now,
                status = "Success",
            )
            val depositEvent = LedgerEvent(
                ledgerId = "deposit-event",
                refid = deposit.refid,
                time = now,
                type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                asset = "USD",
                amount = BigDecimal("10.00"),
            )
            SimpleFundingProvenanceResolver(deposits = listOf(deposit, deposit)).resolve(depositEvent) shouldBe
                FundingEvidence.UNRESOLVED

            val withdrawal = WithdrawStatusRecord(
                refid = "SIGNED-WITHDRAWAL",
                txid = "0xwithdrawal",
                asset = "BTC",
                amount = BigDecimal("1.00"),
                time = now,
                status = "Success",
            )
            val withdrawalResolver = SimpleFundingProvenanceResolver(withdrawals = listOf(withdrawal))
            withdrawalResolver.resolve(
                LedgerEvent(
                    ledgerId = "positive-withdrawal",
                    refid = withdrawal.refid,
                    time = now,
                    type = KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL,
                    asset = "BTC",
                    amount = BigDecimal("1.00"),
                ),
            ) shouldBe FundingEvidence.UNRESOLVED
            withdrawalResolver.resolve(
                LedgerEvent(
                    ledgerId = "zero-withdrawal",
                    refid = withdrawal.refid,
                    time = now,
                    type = KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL,
                    asset = "BTC",
                    amount = BigDecimal.ZERO,
                ),
            ) shouldBe FundingEvidence.UNRESOLVED

            SimpleFundingProvenanceResolver(
                withdrawals = listOf(withdrawal.copy(amount = BigDecimal.ZERO)),
            ).resolve(
                LedgerEvent(
                    ledgerId = "zero-record",
                    refid = withdrawal.refid,
                    time = now,
                    type = KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL,
                    asset = "BTC",
                    amount = BigDecimal("-1.00"),
                ),
            ) shouldBe FundingEvidence.UNRESOLVED

            SimpleFundingProvenanceResolver(
                deposits = listOf(deposit.copy(refid = "NEGATIVE-RECORD-FEE", fee = BigDecimal("-0.01"))),
            ).resolve(
                depositEvent.copy(refid = "NEGATIVE-RECORD-FEE"),
            ) shouldBe FundingEvidence.UNRESOLVED
            SimpleFundingProvenanceResolver(deposits = listOf(deposit)).resolve(
                depositEvent.copy(
                    refid = deposit.refid,
                    asset = "",
                    fee = BigDecimal("-0.01"),
                ),
            ) shouldBe FundingEvidence.UNRESOLVED

            SimpleFundingProvenanceResolver(
                internalTransfers = listOf(
                    InternalTransferRecord(
                        refid = "WRONG-FAMILY",
                        asset = "USD",
                        amount = BigDecimal("10.00"),
                        time = now,
                        ledgerType = KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL,
                    ),
                ),
            ).resolve(
                LedgerEvent(
                    ledgerId = "wrong-internal-family",
                    refid = "WRONG-FAMILY",
                    time = now,
                    type = KrakenApiConstants.LEDGER_TYPE_TRANSFER,
                    asset = "USD",
                    amount = BigDecimal("10.00"),
                ),
            ) shouldBe FundingEvidence.UNRESOLVED
        }

        "authoritative ledger fee remains compatible with a status record that omits its fee" {
            val deposit = DepositStatusRecord(
                refid = "FEE-UNKNOWN-STATUS",
                txid = "0xfee-unknown",
                asset = "USD",
                amount = BigDecimal("100.00"),
                fee = BigDecimal.ZERO,
                time = now,
                status = "Success",
                method = "Wire",
                hasAuthoritativeFee = false,
            )
            val resolver = SimpleFundingProvenanceResolver(deposits = listOf(deposit))

            resolver.resolve(
                LedgerEvent(
                    ledgerId = "known-ledger-fee",
                    refid = " ${deposit.refid} ",
                    time = now,
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    asset = "ZUSD",
                    amount = BigDecimal("100.00"),
                    fee = BigDecimal("0.25"),
                    hasAuthoritativeFee = true,
                ),
            ) shouldBe FundingEvidence.EXTERNAL
        }
    }
}
