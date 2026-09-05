package com.gemini.krakenbot.model

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant

@Suppress("unused")
class LedgerFlowClassifierTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    private val now = Instant.parse("2026-07-01T12:00:00Z")

    private fun event(
        id: String,
        type: String,
        amount: String,
        refid: String? = null,
        subtype: String? = null,
        asset: String = "USD",
        fee: String = "0",
    ): LedgerEvent = LedgerEvent(
        ledgerId = id,
        refid = refid,
        time = now,
        type = type,
        subtype = subtype,
        asset = asset,
        amount = BigDecimal(amount),
        fee = BigDecimal(fee),
    )

    init {
        "bare deposit with insufficient evidence => AMBIGUOUS" {
            LedgerFlowClassifier.classify(event("1", "deposit", "100.00")) shouldBe FlowCategory.AMBIGUOUS
            LedgerFlowClassifier.classify(event("2", "deposit", "0.5", asset = "BTC")) shouldBe FlowCategory.AMBIGUOUS
            LedgerFlowClassifier.classify(event("3", "deposit", "100.00", refid = "FT123456")) shouldBe
                FlowCategory.AMBIGUOUS
            LedgerFlowClassifier.classify(event("4", "deposit", "5000.00", refid = "WIRE-FED-99")) shouldBe
                FlowCategory.AMBIGUOUS
        }

        "bare withdrawal with insufficient evidence => AMBIGUOUS" {
            LedgerFlowClassifier.classify(event("1", "withdrawal", "-50.00")) shouldBe FlowCategory.AMBIGUOUS
            LedgerFlowClassifier.classify(event("2", "withdrawal", "-0.1", asset = "ETH")) shouldBe
                FlowCategory.AMBIGUOUS
            LedgerFlowClassifier.classify(event("3", "withdrawal", "-50.00", fee = "0.25")) shouldBe
                FlowCategory.AMBIGUOUS
            LedgerFlowClassifier.classify(event("4", "withdrawal", "-0.1", asset = "BTC", fee = "0.0002")) shouldBe
                FlowCategory.AMBIGUOUS
        }

        "confirmed external fiat deposit => OWNER_CAPITAL" {
            val resolver = SimpleFundingProvenanceResolver(
                deposits = listOf(
                    DepositStatusRecord(
                        refid = "DEP-FIAT-1",
                        asset = "USD",
                        amount = BigDecimal("100.00"),
                        time = now,
                        status = "Success",
                        method = "Wire",
                    ),
                ),
            )
            LedgerFlowClassifier.classify(
                event("1", "deposit", "100.00", refid = "DEP-FIAT-1"),
                resolver,
            ) shouldBe FlowCategory.OWNER_CAPITAL
        }

        "confirmed external crypto deposit without fee => OWNER_CAPITAL" {
            val resolver = SimpleFundingProvenanceResolver(
                deposits = listOf(
                    DepositStatusRecord(
                        refid = "DEP-CRYPTO-1",
                        txid = "0xabcdef1234567890",
                        asset = "BTC",
                        amount = BigDecimal("0.5"),
                        time = now,
                        status = "Success",
                    ),
                ),
            )
            LedgerFlowClassifier.classify(
                event("1", "deposit", "0.5", asset = "BTC", refid = "DEP-CRYPTO-1"),
                resolver,
            ) shouldBe FlowCategory.OWNER_CAPITAL
        }

        "confirmed external crypto deposit WITH fee is OWNER_CAPITAL (not EXTERNAL_BALANCE)" {
            val resolver = SimpleFundingProvenanceResolver(
                deposits = listOf(
                    DepositStatusRecord(
                        refid = "DEP-FEE-1",
                        txid = "0x9876543210abcdef",
                        asset = "BTC",
                        amount = BigDecimal("0.5"),
                        fee = BigDecimal("0.001"),
                        time = now,
                        status = "Settled",
                    ),
                ),
            )
            LedgerFlowClassifier.classify(
                event("1", "deposit", "0.5", asset = "BTC", fee = "0.001", refid = "DEP-FEE-1"),
                resolver,
            ) shouldBe FlowCategory.OWNER_CAPITAL
        }

        "confirmed external withdrawal => OWNER_CAPITAL" {
            val resolver = SimpleFundingProvenanceResolver(
                withdrawals = listOf(
                    WithdrawStatusRecord(
                        refid = "WITH-1",
                        txid = "0x112233445566",
                        asset = "ETH",
                        amount = BigDecimal("2.0"),
                        fee = BigDecimal("0.005"),
                        time = now,
                        status = "Success",
                    ),
                ),
            )
            LedgerFlowClassifier.classify(
                event("1", "withdrawal", "-2.0", asset = "ETH", fee = "0.005", refid = "WITH-1"),
                resolver,
            ) shouldBe FlowCategory.OWNER_CAPITAL
        }

        "confirmed internal transfer via resolver => INTERNAL_MOVE" {
            val resolver = SimpleFundingProvenanceResolver(
                internalTransfers = listOf(
                    InternalTransferRecord(
                        refid = "INT-1",
                        asset = "BTC",
                        amount = BigDecimal("0.5"),
                        time = now,
                    ),
                ),
            )
            LedgerFlowClassifier.classify(
                event("1", "deposit", "0.5", asset = "BTC", refid = "INT-1"),
                resolver,
            ) shouldBe FlowCategory.INTERNAL_MOVE
        }

        "unmatched deposit falls back to AMBIGUOUS with active resolver" {
            val resolver = SimpleFundingProvenanceResolver(
                deposits = listOf(
                    DepositStatusRecord(
                        refid = "OTHER-DEP",
                        txid = "0x123",
                        asset = "USD",
                        amount = BigDecimal("50.0"),
                        time = now,
                        status = "Success",
                    ),
                ),
            )
            LedgerFlowClassifier.classify(
                event("1", "deposit", "100.00", refid = "UNKNOWN-DEP"),
                resolver,
            ) shouldBe FlowCategory.AMBIGUOUS
        }

        "Futures -> Spot deposit with refid or subtype" {
            LedgerFlowClassifier.classify(
                event("1", "deposit", "1000.00", refid = "KF-futures-pnl"),
            ) shouldBe FlowCategory.INTERNAL_MOVE
            LedgerFlowClassifier.classify(
                event("2", "deposit", "0.50", subtype = "spotfromfutures", asset = "BTC"),
            ) shouldBe FlowCategory.INTERNAL_MOVE
            LedgerFlowClassifier.classify(
                event("3", "deposit", "100.00", refid = "INTERNAL-TRANSFER-01"),
            ) shouldBe FlowCategory.INTERNAL_MOVE
        }

        "Spot -> Futures withdrawal with refid or subtype" {
            LedgerFlowClassifier.classify(
                event("1", "withdrawal", "-1000.00", refid = "KF-margin-topup"),
            ) shouldBe FlowCategory.INTERNAL_MOVE
            LedgerFlowClassifier.classify(
                event("2", "withdrawal", "-0.50", subtype = "spottofutures", asset = "BTC"),
            ) shouldBe FlowCategory.INTERNAL_MOVE
        }

        "known internal subtype => INTERNAL_MOVE" {
            LedgerFlowClassifier.classify(
                event("1", "deposit", "10.00", subtype = "spotfromspot"),
            ) shouldBe FlowCategory.INTERNAL_MOVE
            LedgerFlowClassifier.classify(
                event("2", "deposit", "10.00", subtype = "allocation"),
            ) shouldBe FlowCategory.INTERNAL_MOVE
            LedgerFlowClassifier.classify(
                event("3", "withdrawal", "-10.00", subtype = "deallocation"),
            ) shouldBe FlowCategory.INTERNAL_MOVE
            LedgerFlowClassifier.classify(
                event("4", "deposit", "10.00", subtype = "migration"),
            ) shouldBe FlowCategory.INTERNAL_MOVE
            LedgerFlowClassifier.classify(
                event("5", "transfer", "10.00", subtype = "spottostaking"),
            ) shouldBe FlowCategory.INTERNAL_MOVE
        }

        "unpaired transfer defaults to internal move, never owner capital" {
            LedgerFlowClassifier.classify(event("1", "transfer", "25.00")) shouldBe FlowCategory.INTERNAL_MOVE
        }

        "unrecognized subtype on funding is ambiguous, never owner capital" {
            LedgerFlowClassifier.classify(
                event("1", "deposit", "100.00", subtype = "staking-reward"),
            ) shouldBe FlowCategory.AMBIGUOUS
            LedgerFlowClassifier.classify(
                event("2", "withdrawal", "-50.00", subtype = "external"),
            ) shouldBe FlowCategory.AMBIGUOUS
        }

        "cross-asset refid group funding legs are ambiguous" {
            val legs = listOf(
                event("1", "withdrawal", "-1.00", refid = "CX", asset = "BTC"),
                event("2", "deposit", "50000.00", refid = "CX", asset = "USD"),
            )
            val result = LedgerFlowClassifier.classifyAll(legs)
            result["1"] shouldBe FlowCategory.AMBIGUOUS
            result["2"] shouldBe FlowCategory.AMBIGUOUS
        }

        "refid-paired zero-net legs classify as internal move" {
            val legs = listOf(
                event("1", "transfer", "25.00", refid = "R1", asset = "BTC"),
                event("2", "transfer", "-25.00", refid = "R1", asset = "BTC"),
            )
            val result = LedgerFlowClassifier.classifyAll(legs)
            result["1"] shouldBe FlowCategory.INTERNAL_MOVE
            result["2"] shouldBe FlowCategory.INTERNAL_MOVE
        }

        "refid-linked funding legs without zero net are ambiguous, not capital" {
            val legs = listOf(
                event("1", "deposit", "100.00", refid = "R2"),
                event("2", "deposit", "50.00", refid = "R2"),
            )
            val result = LedgerFlowClassifier.classifyAll(legs)
            result["1"] shouldBe FlowCategory.AMBIGUOUS
            result["2"] shouldBe FlowCategory.AMBIGUOUS
        }

        "non-funding legs in a linked group still use single rules" {
            val legs = listOf(
                event("1", "transfer", "25.00", refid = "R3", asset = "BTC"),
                event("2", "transfer", "-10.00", refid = "R3", asset = "BTC"),
            )
            val result = LedgerFlowClassifier.classifyAll(legs)
            result["1"] shouldBe FlowCategory.INTERNAL_MOVE
            result["2"] shouldBe FlowCategory.INTERNAL_MOVE
        }

        "staking and rewards are external balance, not owner capital" {
            LedgerFlowClassifier.classify(event("1", "staking", "0.10")) shouldBe FlowCategory.EXTERNAL_BALANCE
            LedgerFlowClassifier.classify(event("2", "dividend", "1.00")) shouldBe FlowCategory.EXTERNAL_BALANCE
            LedgerFlowClassifier.classify(event("3", "spend", "-5.00")) shouldBe FlowCategory.EXTERNAL_BALANCE
            LedgerFlowClassifier.classify(event("4", "receive", "5.00")) shouldBe FlowCategory.EXTERNAL_BALANCE
        }

        "trade rows are ignored" {
            LedgerFlowClassifier.classify(event("1", "trade", "0.50")) shouldBe FlowCategory.TRADE_IGNORED
        }

        "margin-family and sale rows replay as external balance" {
            LedgerFlowClassifier.classify(event("1", "margin", "5.00")) shouldBe FlowCategory.EXTERNAL_BALANCE
            LedgerFlowClassifier.classify(event("2", "rollover", "-1.00")) shouldBe FlowCategory.EXTERNAL_BALANCE
            LedgerFlowClassifier.classify(event("3", "settled", "2.00")) shouldBe FlowCategory.EXTERNAL_BALANCE
            LedgerFlowClassifier.classify(event("4", "credit", "3.00")) shouldBe FlowCategory.EXTERNAL_BALANCE
            LedgerFlowClassifier.classify(event("5", "sale", "4.00")) shouldBe FlowCategory.EXTERNAL_BALANCE
        }

        "unknown ledger types are unsupported" {
            LedgerFlowClassifier.classify(event("1", "mystery", "5.00")) shouldBe FlowCategory.UNSUPPORTED
        }
    }
}
