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

        "confirmed external funding keeps owner provenance through same-refid spend plumbing" {
            val resolver = SimpleFundingProvenanceResolver(
                deposits = listOf(
                    DepositStatusRecord(
                        refid = "PLUMB-1",
                        asset = "USD",
                        amount = BigDecimal("100.00"),
                        time = now,
                        status = "Success",
                        method = "Wire",
                    ),
                ),
            )
            val result = LedgerFlowClassifier.classifyAll(
                listOf(
                    event("deposit", "deposit", "100.00", refid = "PLUMB-1"),
                    event("spend", "spend", "-60.00", refid = "PLUMB-1"),
                ),
                resolver,
            )

            result["deposit"] shouldBe FlowCategory.OWNER_CAPITAL
            result["spend"] shouldBe FlowCategory.EXTERNAL_BALANCE
        }

        "confirmed external funding stays owner capital when spend nets it to zero" {
            val resolver = SimpleFundingProvenanceResolver(
                deposits = listOf(
                    DepositStatusRecord(
                        refid = "PLUMB-ZERO",
                        asset = "USD",
                        amount = BigDecimal("100.00"),
                        time = now,
                        status = "Success",
                        method = "Wire",
                    ),
                ),
            )
            val result = LedgerFlowClassifier.classifyAll(
                listOf(
                    event("deposit", "deposit", "100.00", refid = "PLUMB-ZERO"),
                    event("spend", "spend", "-100.00", refid = "PLUMB-ZERO"),
                ),
                resolver,
            )

            result["deposit"] shouldBe FlowCategory.OWNER_CAPITAL
            result["spend"] shouldBe FlowCategory.EXTERNAL_BALANCE
        }

        "authoritative internal funding marks every linked passthrough leg internal" {
            val resolver = SimpleFundingProvenanceResolver(
                internalTransfers = listOf(
                    InternalTransferRecord(
                        refid = "GENERIC-INTERNAL",
                        asset = "USD",
                        amount = BigDecimal("100.00"),
                        time = now,
                    ),
                ),
            )
            val result = LedgerFlowClassifier.classifyAll(
                listOf(
                    event("deposit", "deposit", "100.00", refid = "GENERIC-INTERNAL"),
                    event("spend", "spend", "-60.00", refid = "GENERIC-INTERNAL"),
                ),
                resolver,
            )

            result["deposit"] shouldBe FlowCategory.INTERNAL_MOVE
            result["spend"] shouldBe FlowCategory.INTERNAL_MOVE
        }

        "linked funding with an unrecognized subtype remains ambiguous" {
            val result = LedgerFlowClassifier.classifyAll(
                listOf(
                    event(
                        "deposit",
                        "deposit",
                        "100.00",
                        refid = "PLUMB-SUBTYPE",
                        subtype = "staking-reward",
                    ),
                    event("spend", "spend", "-60.00", refid = "PLUMB-SUBTYPE"),
                ),
                FundingProvenanceResolver { FundingEvidence.EXTERNAL },
            )

            result["deposit"] shouldBe FlowCategory.AMBIGUOUS
            result["spend"] shouldBe FlowCategory.AMBIGUOUS
        }

        "explicit internal marker remains neutral in linked funding plumbing" {
            val result = LedgerFlowClassifier.classifyAll(
                listOf(
                    event("deposit", "deposit", "100.00", refid = "documented-internal", subtype = "spotfromfutures"),
                    event("spend", "spend", "-60.00", refid = "documented-internal"),
                ),
                FundingProvenanceResolver.NONE,
            )

            result["deposit"] shouldBe FlowCategory.INTERNAL_MOVE
            result["spend"] shouldBe FlowCategory.INTERNAL_MOVE
        }

        "external funding conflicts with an explicitly internal passthrough leg" {
            val result = LedgerFlowClassifier.classifyAll(
                listOf(
                    event("deposit", "deposit", "100.00", refid = "PLUMB-CONFLICT"),
                    event(
                        "spend",
                        "spend",
                        "-60.00",
                        refid = "PLUMB-CONFLICT",
                        subtype = "spottofutures",
                    ),
                ),
                FundingProvenanceResolver { FundingEvidence.EXTERNAL },
            )

            result["deposit"] shouldBe FlowCategory.AMBIGUOUS
            result["spend"] shouldBe FlowCategory.AMBIGUOUS
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

        "opaque Futures-looking refids do not prove internal funding" {
            LedgerFlowClassifier.classify(
                event("1", "deposit", "1000.00", refid = "KF-futures-pnl"),
            ) shouldBe FlowCategory.AMBIGUOUS
            LedgerFlowClassifier.classify(
                event("2", "deposit", "0.50", subtype = "spotfromfutures", asset = "BTC"),
            ) shouldBe FlowCategory.INTERNAL_MOVE
            LedgerFlowClassifier.classify(
                event("3", "deposit", "100.00", refid = "INTERNAL-TRANSFER-01"),
            ) shouldBe FlowCategory.AMBIGUOUS
        }

        "opaque Futures-looking withdrawal refid does not prove internal funding" {
            LedgerFlowClassifier.classify(
                event("1", "withdrawal", "-1000.00", refid = "KF-margin-topup"),
            ) shouldBe FlowCategory.AMBIGUOUS
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

        "earn subtypes preserve reward versus allocation semantics" {
            LedgerFlowClassifier.classify(
                event("earn-reward", KrakenApiConstants.LEDGER_TYPE_EARN, "0.10", subtype = "reward", asset = "ETH"),
            ) shouldBe FlowCategory.EXTERNAL_BALANCE
            LedgerFlowClassifier.classify(
                event(
                    "earn-allocation",
                    KrakenApiConstants.LEDGER_TYPE_EARN,
                    "-1.00",
                    subtype = "allocation",
                    asset = "ETH",
                ),
            ) shouldBe FlowCategory.INTERNAL_MOVE
            LedgerFlowClassifier.classify(
                event(
                    "earn-deallocation",
                    KrakenApiConstants.LEDGER_TYPE_EARN,
                    "1.00",
                    subtype = "deallocation",
                    asset = "ETH",
                ),
            ) shouldBe FlowCategory.INTERNAL_MOVE
            LedgerFlowClassifier.classify(
                event(
                    "earn-autoallocate",
                    KrakenApiConstants.LEDGER_TYPE_EARN,
                    "-1.00",
                    subtype = "autoallocate",
                    asset = "ETH",
                ),
            ) shouldBe FlowCategory.INTERNAL_MOVE
            LedgerFlowClassifier.classify(
                event(
                    "earn-migration",
                    KrakenApiConstants.LEDGER_TYPE_EARN,
                    "1.00",
                    subtype = "migration",
                    asset = "ETH",
                ),
            ) shouldBe FlowCategory.INTERNAL_MOVE
        }

        "reward predicate recognizes only legacy rewards and earn reward" {
            LedgerEvent.isRewardEvent(event("staking", KrakenApiConstants.LEDGER_TYPE_STAKING, "1.00")) shouldBe true
            LedgerEvent.isRewardEvent(
                event("earn-reward", KrakenApiConstants.LEDGER_TYPE_EARN, "1.00", subtype = " Reward "),
            ) shouldBe true
            LedgerEvent.isRewardEvent(
                event("earn-allocation", KrakenApiConstants.LEDGER_TYPE_EARN, "1.00", subtype = "allocation"),
            ) shouldBe false
            LedgerEvent.isRewardEvent(event("deposit", KrakenApiConstants.LEDGER_TYPE_DEPOSIT, "1.00")) shouldBe false
        }

        "unknown earn subtype is fail-closed" {
            LedgerFlowClassifier.classify(
                event("earn-unknown", KrakenApiConstants.LEDGER_TYPE_EARN, "1.00", subtype = "bonus", asset = "ETH"),
            ) shouldBe FlowCategory.AMBIGUOUS
            LedgerFlowClassifier.classify(
                event("earn-missing", KrakenApiConstants.LEDGER_TYPE_EARN, "1.00", asset = "ETH"),
            ) shouldBe FlowCategory.AMBIGUOUS
        }

        "documented transfer reward semantics are performance, while undocumented prose subtypes stay ambiguous" {
            LedgerFlowClassifier.classify(
                event("reward", KrakenApiConstants.LEDGER_TYPE_TRANSFER, "1.00", subtype = "reward", asset = "ETH"),
            ) shouldBe FlowCategory.EXTERNAL_BALANCE
            LedgerFlowClassifier.classify(
                event("airdrop", KrakenApiConstants.LEDGER_TYPE_TRANSFER, "1.00", subtype = "airdrop", asset = "ETH"),
            ) shouldBe FlowCategory.AMBIGUOUS
            LedgerFlowClassifier.classify(
                event("fork", KrakenApiConstants.LEDGER_TYPE_TRANSFER, "1.00", subtype = "fork", asset = "ETH"),
            ) shouldBe FlowCategory.AMBIGUOUS
            LedgerFlowClassifier.classify(
                event(
                    "distribution",
                    KrakenApiConstants.LEDGER_TYPE_TRANSFER,
                    "1.00",
                    subtype = "distribution",
                    asset = "ETH",
                ),
            ) shouldBe FlowCategory.AMBIGUOUS
        }

        "authoritative transfer provenance overrides an opaque refid" {
            val external = FundingProvenanceResolver { FundingEvidence.EXTERNAL }
            val internal = FundingProvenanceResolver { FundingEvidence.INTERNAL }

            LedgerFlowClassifier.classify(
                event("external-transfer", KrakenApiConstants.LEDGER_TYPE_TRANSFER, "1.00", refid = "KF-opaque"),
                external,
            ) shouldBe FlowCategory.EXTERNAL_BALANCE
            LedgerFlowClassifier.classify(
                event("internal-transfer", KrakenApiConstants.LEDGER_TYPE_TRANSFER, "1.00", refid = "futures-opaque"),
                internal,
            ) shouldBe FlowCategory.INTERNAL_MOVE
        }

        "known internal transfer subtype conflicts with authoritative external evidence" {
            LedgerFlowClassifier.classify(
                event(
                    "contradictory-transfer",
                    KrakenApiConstants.LEDGER_TYPE_TRANSFER,
                    "1.00",
                    subtype = "spottofutures",
                ),
                FundingProvenanceResolver { FundingEvidence.EXTERNAL },
            ) shouldBe FlowCategory.AMBIGUOUS
        }

        "funding and transfer semantic conflicts remain ambiguous" {
            LedgerFlowClassifier.classify(
                event(
                    "contradictory-deposit",
                    KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    "1.00",
                    subtype = "spotfromfutures",
                ),
                FundingProvenanceResolver { FundingEvidence.EXTERNAL },
            ) shouldBe FlowCategory.AMBIGUOUS
            LedgerFlowClassifier.classify(
                event(
                    "contradictory-reward",
                    KrakenApiConstants.LEDGER_TYPE_TRANSFER,
                    "1.00",
                    subtype = "reward",
                ),
                FundingProvenanceResolver { FundingEvidence.INTERNAL },
            ) shouldBe FlowCategory.AMBIGUOUS
        }

        "internal and external authoritative records conflict fail-closed" {
            val resolver = SimpleFundingProvenanceResolver(
                deposits = listOf(
                    DepositStatusRecord(
                        refid = "CONFLICT",
                        txid = "wire-1",
                        asset = "USD",
                        amount = BigDecimal("100.00"),
                        time = now,
                        status = "Success",
                        method = "Wire",
                    ),
                ),
                internalTransfers = listOf(
                    InternalTransferRecord(
                        refid = "CONFLICT",
                        asset = "USD",
                        amount = BigDecimal("100.00"),
                        time = now,
                        ledgerType = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    ),
                ),
            )

            LedgerFlowClassifier.classify(
                event("conflict", KrakenApiConstants.LEDGER_TYPE_DEPOSIT, "100.00", refid = "CONFLICT"),
                resolver,
            ) shouldBe FlowCategory.AMBIGUOUS
        }

        "unpaired transfer with no evidence is ambiguous" {
            LedgerFlowClassifier.classify(event("1", "transfer", "25.00")) shouldBe FlowCategory.AMBIGUOUS
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
            val result = LedgerFlowClassifier.classifyAll(
                legs,
                FundingProvenanceResolver { FundingEvidence.INTERNAL },
            )
            result["1"] shouldBe FlowCategory.INTERNAL_MOVE
            result["2"] shouldBe FlowCategory.INTERNAL_MOVE
        }

        "unproven zero-net funding and transfer pairs remain ambiguous" {
            val fundingResult = LedgerFlowClassifier.classifyAll(
                listOf(
                    event("deposit", "deposit", "25.00", refid = "R-UNPROVEN", asset = "USD"),
                    event("withdrawal", "withdrawal", "-25.00", refid = "R-UNPROVEN", asset = "USD"),
                ),
            )
            fundingResult.values.toSet() shouldBe setOf(FlowCategory.AMBIGUOUS)

            val transferResult = LedgerFlowClassifier.classifyAll(
                listOf(
                    event("transfer-in", "transfer", "25.00", refid = "R-UNPROVEN-TRANSFER", asset = "BTC"),
                    event("transfer-out", "transfer", "-25.00", refid = "R-UNPROVEN-TRANSFER", asset = "BTC"),
                ),
            )
            transferResult.values.toSet() shouldBe setOf(FlowCategory.AMBIGUOUS)
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

        "nonzero linked transfer legs without internal evidence remain ambiguous" {
            val legs = listOf(
                event("1", "transfer", "25.00", refid = "R3", asset = "BTC"),
                event("2", "transfer", "-10.00", refid = "R3", asset = "BTC"),
            )
            val result = LedgerFlowClassifier.classifyAll(legs)
            result["1"] shouldBe FlowCategory.AMBIGUOUS
            result["2"] shouldBe FlowCategory.AMBIGUOUS
        }

        "zero-net pairing rejects performance, trade, unknown, and semantic external legs" {
            val performance = LedgerFlowClassifier.classifyAll(
                listOf(
                    event("staking-in", KrakenApiConstants.LEDGER_TYPE_STAKING, "1.00", refid = "STAKING-PAIR"),
                    event("staking-out", KrakenApiConstants.LEDGER_TYPE_STAKING, "-1.00", refid = "STAKING-PAIR"),
                ),
            )
            performance.values.toSet() shouldBe setOf(FlowCategory.EXTERNAL_BALANCE)

            val trades = LedgerFlowClassifier.classifyAll(
                listOf(
                    event("trade-in", KrakenApiConstants.LEDGER_TYPE_TRADE, "1.00", refid = "TRADE-PAIR"),
                    event("trade-out", KrakenApiConstants.LEDGER_TYPE_TRADE, "-1.00", refid = "TRADE-PAIR"),
                ),
            )
            trades.values.toSet() shouldBe setOf(FlowCategory.TRADE_IGNORED)

            val unsupported = LedgerFlowClassifier.classifyAll(
                listOf(
                    event("unknown-in", "mystery", "1.00", refid = "UNKNOWN-PAIR"),
                    event("unknown-out", "mystery", "-1.00", refid = "UNKNOWN-PAIR"),
                ),
            )
            unsupported.values.toSet() shouldBe setOf(FlowCategory.UNSUPPORTED)

            val earnUnknown = LedgerFlowClassifier.classifyAll(
                listOf(
                    event(
                        "earn-unknown-in",
                        KrakenApiConstants.LEDGER_TYPE_EARN,
                        "1.00",
                        refid = "EARN-UNKNOWN-PAIR",
                        subtype = "bonus",
                        asset = "ETH",
                    ),
                    event(
                        "earn-unknown-out",
                        KrakenApiConstants.LEDGER_TYPE_EARN,
                        "-1.00",
                        refid = "EARN-UNKNOWN-PAIR",
                        subtype = "bonus",
                        asset = "ETH",
                    ),
                ),
            )
            earnUnknown.values.toSet() shouldBe setOf(FlowCategory.AMBIGUOUS)

            val earnInternal = LedgerFlowClassifier.classifyAll(
                listOf(
                    event(
                        "earn-internal-in",
                        KrakenApiConstants.LEDGER_TYPE_EARN,
                        "1.00",
                        refid = "EARN-INTERNAL-PAIR",
                        subtype = "allocation",
                        asset = "ETH",
                    ),
                    event(
                        "earn-internal-out",
                        KrakenApiConstants.LEDGER_TYPE_EARN,
                        "-1.00",
                        refid = "EARN-INTERNAL-PAIR",
                        subtype = "deallocation",
                        asset = "ETH",
                    ),
                ),
            )
            earnInternal.values.toSet() shouldBe setOf(FlowCategory.INTERNAL_MOVE)

            val transferReward = LedgerFlowClassifier.classifyAll(
                listOf(
                    event(
                        "transfer-reward-in",
                        KrakenApiConstants.LEDGER_TYPE_TRANSFER,
                        "1.00",
                        refid = "TRANSFER-REWARD-PAIR",
                        subtype = "reward",
                        asset = "ETH",
                    ),
                    event(
                        "transfer-reward-out",
                        KrakenApiConstants.LEDGER_TYPE_TRANSFER,
                        "-1.00",
                        refid = "TRANSFER-REWARD-PAIR",
                        subtype = "reward",
                        asset = "ETH",
                    ),
                ),
            )
            transferReward.values.toSet() shouldBe setOf(FlowCategory.EXTERNAL_BALANCE)

            val externallyProvenFunding = LedgerFlowClassifier.classifyAll(
                listOf(
                    event(
                        "external-in",
                        KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                        "100.00",
                        refid = "EXTERNAL-PAIR",
                    ),
                    event(
                        "external-out",
                        KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL,
                        "-100.00",
                        refid = "EXTERNAL-PAIR",
                    ),
                ),
                FundingProvenanceResolver { FundingEvidence.EXTERNAL },
            )
            // Two separately external funding legs sharing one refid are a
            // semantic conflict, not a neutral wallet move.
            externallyProvenFunding.values.toSet() shouldBe setOf(FlowCategory.AMBIGUOUS)
        }

        "authoritative internal funding covers an internally marked passthrough leg" {
            val result = LedgerFlowClassifier.classifyAll(
                listOf(
                    event(
                        "internal-funding",
                        KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                        "100.00",
                        refid = "INTERNAL-PLUMBING",
                    ),
                    event(
                        "internal-passthrough",
                        KrakenApiConstants.LEDGER_TYPE_SPEND,
                        "-60.00",
                        refid = "INTERNAL-PLUMBING",
                        subtype = "spottofutures",
                    ),
                ),
                FundingProvenanceResolver { FundingEvidence.INTERNAL },
            )

            result.values.toSet() shouldBe setOf(FlowCategory.INTERNAL_MOVE)
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

        "empty batches and conflicting funding legs fail closed" {
            LedgerFlowClassifier.classifyAll(emptyList()) shouldBe emptyMap()

            val resolver = FundingProvenanceResolver { fundingEvent ->
                when (fundingEvent.ledgerId) {
                    "external-funding" -> FundingEvidence.EXTERNAL
                    "internal-funding" -> FundingEvidence.INTERNAL
                    else -> FundingEvidence.UNRESOLVED
                }
            }
            val result = LedgerFlowClassifier.classifyAll(
                listOf(
                    event("external-funding", "deposit", "100.00", refid = "CONFLICTING-PLUMBING"),
                    event("internal-funding", "withdrawal", "-20.00", refid = "CONFLICTING-PLUMBING"),
                    event("passthrough", "spend", "-30.00", refid = "CONFLICTING-PLUMBING"),
                ),
                resolver,
            )

            result.values.toSet() shouldBe setOf(FlowCategory.AMBIGUOUS)
        }

        "linked funding groups with a non-plumbing leg do not get partial ownership" {
            val result = LedgerFlowClassifier.classifyAll(
                listOf(
                    event("funding", "deposit", "100.00", refid = "MIXED-LINK"),
                    event("spend", "spend", "-20.00", refid = "MIXED-LINK"),
                    event("reward", "staking", "1.00", refid = "MIXED-LINK"),
                ),
                FundingProvenanceResolver { FundingEvidence.EXTERNAL },
            )

            result["funding"] shouldBe FlowCategory.AMBIGUOUS
            result["spend"] shouldBe FlowCategory.EXTERNAL_BALANCE
            result["reward"] shouldBe FlowCategory.EXTERNAL_BALANCE
        }

        "zero-net linked funding with a non-plumbing leg stays fail-closed" {
            val result = LedgerFlowClassifier.classifyAll(
                listOf(
                    event("deposit", "deposit", "100.00", refid = "ZERO-MIXED"),
                    event("spend", "spend", "-100.00", refid = "ZERO-MIXED"),
                    event("reward", "staking", "0.00", refid = "ZERO-MIXED"),
                ),
                FundingProvenanceResolver { FundingEvidence.EXTERNAL },
            )

            result["deposit"] shouldBe FlowCategory.AMBIGUOUS
            result["spend"] shouldBe FlowCategory.EXTERNAL_BALANCE
            result["reward"] shouldBe FlowCategory.EXTERNAL_BALANCE
        }
    }
}
