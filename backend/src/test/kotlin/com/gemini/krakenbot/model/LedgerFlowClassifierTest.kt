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
    ): LedgerEvent = LedgerEvent(
        ledgerId = id,
        refid = refid,
        time = now,
        type = type,
        subtype = subtype,
        asset = asset,
        amount = BigDecimal(amount),
    )

    init {
        "deposit defaults to owner capital" {
            LedgerFlowClassifier.classify(event("1", "deposit", "100.00")) shouldBe FlowCategory.OWNER_CAPITAL
        }

        "withdrawal defaults to owner capital" {
            LedgerFlowClassifier.classify(event("1", "withdrawal", "-50.00")) shouldBe FlowCategory.OWNER_CAPITAL
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

        "spot/futures wallet subtypes are internal moves" {
            LedgerFlowClassifier.classify(
                event("1", "deposit", "0.50", subtype = "spotfromfutures", asset = "BTC"),
            ) shouldBe FlowCategory.INTERNAL_MOVE
            LedgerFlowClassifier.classify(
                event("2", "withdrawal", "-0.50", subtype = "spottofutures", asset = "BTC"),
            ) shouldBe FlowCategory.INTERNAL_MOVE
        }

        "staking wallet and earn subtypes are internal moves" {
            LedgerFlowClassifier.classify(
                event("1", "transfer", "10.00", subtype = "spottostaking"),
            ) shouldBe FlowCategory.INTERNAL_MOVE
            LedgerFlowClassifier.classify(
                event("2", "deposit", "10.00", subtype = "stakingfromspot"),
            ) shouldBe FlowCategory.INTERNAL_MOVE
            LedgerFlowClassifier.classify(
                event("3", "deposit", "10.00", subtype = "allocation"),
            ) shouldBe FlowCategory.INTERNAL_MOVE
            LedgerFlowClassifier.classify(
                event("4", "withdrawal", "-10.00", subtype = "deallocation"),
            ) shouldBe FlowCategory.INTERNAL_MOVE
            LedgerFlowClassifier.classify(
                event("5", "deposit", "10.00", subtype = "migration"),
            ) shouldBe FlowCategory.INTERNAL_MOVE
        }

        "cross-asset refid group funding legs are ambiguous" {
            val legs =
                listOf(
                    event("1", "withdrawal", "-1.00", refid = "CX", asset = "BTC"),
                    event("2", "deposit", "50000.00", refid = "CX", asset = "USD"),
                )
            val result = LedgerFlowClassifier.classifyAll(legs)
            result["1"] shouldBe FlowCategory.AMBIGUOUS
            result["2"] shouldBe FlowCategory.AMBIGUOUS
        }

        "mixed refid and plain rows classify together without pairing" {
            val result = LedgerFlowClassifier.classifyAll(
                listOf(
                    event("1", "transfer", "25.00", refid = "R1", asset = "BTC"),
                    event("2", "deposit", "100.00"),
                ),
            )
            result["1"] shouldBe FlowCategory.INTERNAL_MOVE
            result["2"] shouldBe FlowCategory.OWNER_CAPITAL
        }

        "blank subtype behaves like no subtype" {
            LedgerFlowClassifier.classify(
                event("1", "deposit", "100.00", subtype = ""),
            ) shouldBe FlowCategory.OWNER_CAPITAL
        }

        "single-leg refid group still uses single rules" {
            LedgerFlowClassifier.classify(
                event("1", "deposit", "100.00", refid = "SOLO"),
            ) shouldBe FlowCategory.OWNER_CAPITAL
        }

        "refid-paired zero-net legs classify as internal move" {
            val legs =
                listOf(
                    event("1", "transfer", "25.00", refid = "R1", asset = "BTC"),
                    event("2", "transfer", "-25.00", refid = "R1", asset = "BTC"),
                )
            val result = LedgerFlowClassifier.classifyAll(legs)
            result["1"] shouldBe FlowCategory.INTERNAL_MOVE
            result["2"] shouldBe FlowCategory.INTERNAL_MOVE
        }

        "refid-paired non-zero-net legs fall back to single rules" {
            val legs =
                listOf(
                    event("1", "deposit", "100.00", refid = "R2"),
                    event("2", "deposit", "50.00", refid = "R2"),
                )
            val result = LedgerFlowClassifier.classifyAll(legs)
            result["1"] shouldBe FlowCategory.OWNER_CAPITAL
            result["2"] shouldBe FlowCategory.OWNER_CAPITAL
        }

        "internal subtype keyword forces internal move even for deposits" {
            LedgerFlowClassifier.classify(
                event("1", "deposit", "10.00", subtype = "spotfromspot"),
            ) shouldBe FlowCategory.INTERNAL_MOVE
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
