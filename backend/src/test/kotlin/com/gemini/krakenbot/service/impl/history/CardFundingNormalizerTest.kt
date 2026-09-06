package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.model.CardFeePriceProvider
import com.gemini.krakenbot.model.DepositStatusRecord
import com.gemini.krakenbot.model.FundingEvidence
import com.gemini.krakenbot.model.FundingProvenanceResolver
import com.gemini.krakenbot.model.InternalTransferRecord
import com.gemini.krakenbot.model.KrakenApiConstants
import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.model.NormalizedFundingTransaction
import com.gemini.krakenbot.model.SimpleFundingProvenanceResolver
import com.gemini.krakenbot.model.TimedAssetDelta
import com.gemini.krakenbot.model.WithdrawStatusRecord
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.math.BigDecimal
import java.time.Instant

@Suppress("unused")
class CardFundingNormalizerTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    private val baseTime = Instant.parse("2026-07-01T12:30:00.000Z")

    init {
        "recognizes both USD ledger aliases" {
            CardFundingNormalizer.isUsd("USD") shouldBe true
            CardFundingNormalizer.isUsd("ZUSD") shouldBe true
            CardFundingNormalizer.isUsd("BTC") shouldBe false
        }
    }

    private fun event(
        id: String,
        type: String,
        amount: String,
        refid: String? = "CARD-REF-1",
        time: Instant = baseTime,
        asset: String = "USD",
        fee: String = "0",
        subtype: String? = null,
    ): LedgerEvent = LedgerEvent(
        ledgerId = id,
        refid = refid,
        time = time,
        type = type,
        subtype = subtype,
        asset = asset,
        amount = BigDecimal(amount),
        fee = BigDecimal(fee),
    )

    private fun depositRecord(
        refid: String,
        amount: String,
        asset: String = "USD",
        time: Instant = baseTime,
        method: String? = "Wire",
    ) = DepositStatusRecord(
        refid = refid,
        asset = asset,
        amount = BigDecimal(amount).abs(),
        time = time,
        status = "Success",
        method = method,
    )

    private fun withdrawRecord(
        refid: String,
        amount: String,
        asset: String = "USD",
        time: Instant = baseTime,
        method: String? = "Wire",
    ) = WithdrawStatusRecord(
        refid = refid,
        asset = asset,
        amount = BigDecimal(amount).abs(),
        time = time,
        status = "Success",
        method = method,
    )

    private val externalCardResolver = SimpleFundingProvenanceResolver(
        deposits = listOf(
            depositRecord("CARD-REF-1", "5000.00", method = "Visa"),
        ),
    )

    private val externalNonCardResolver = SimpleFundingProvenanceResolver(
        deposits = listOf(
            depositRecord("WIRE-REF-1", "5000.00", method = "Wire"),
        ),
    )

    private val defaultPriceProvider = CardFeePriceProvider { asset, _ ->
        if (asset.equals("BTC", ignoreCase = true) || asset.equals("XXBT", ignoreCase = true)) {
            BigDecimal("50000.00")
        } else {
            null
        }
    }

    init {
        "helper predicates identify ledger types and assets correctly" {
            CardFundingNormalizer.isFundingLeg(event("1", "deposit", "100.00")) shouldBe true
            CardFundingNormalizer.isFundingLeg(event("2", "withdrawal", "-100.00")) shouldBe true
            CardFundingNormalizer.isFundingLeg(event("3", "spend", "-100.00")) shouldBe false
            CardFundingNormalizer.isFundingLeg(event("4", "trade", "100.00")) shouldBe false

            CardFundingNormalizer.isSpendLeg(event("1", "spend", "-100.00")) shouldBe true
            CardFundingNormalizer.isSpendLeg(event("2", "deposit", "100.00")) shouldBe false

            CardFundingNormalizer.isReceiveLeg(event("1", "receive", "1.00")) shouldBe true
            CardFundingNormalizer.isReceiveLeg(event("2", "spend", "-100.00")) shouldBe false

            CardFundingNormalizer.isPassthroughLeg(event("1", "spend", "-100.00")) shouldBe true
            CardFundingNormalizer.isPassthroughLeg(event("2", "receive", "1.00")) shouldBe true
            CardFundingNormalizer.isPassthroughLeg(event("3", "deposit", "100.00")) shouldBe false

            CardFundingNormalizer.isUsd("USD") shouldBe true
            CardFundingNormalizer.isUsd("ZUSD") shouldBe true
            CardFundingNormalizer.isUsd("usd") shouldBe true
            CardFundingNormalizer.isUsd("BTC") shouldBe false
            CardFundingNormalizer.isUsd("XXBT") shouldBe false
            CardFundingNormalizer.isUsd("EUR") shouldBe false
            CardFundingNormalizer.isUsd("ETH") shouldBe false
        }

        "identifyCandidateGroups filters blank refids and trims refids" {
            val events = listOf(
                event("1", "deposit", "100.00", refid = "  REF-A  "),
                event("2", "spend", "-100.00", refid = "REF-A"),
                event("3", "deposit", "50.00", refid = null),
                event("4", "spend", "-50.00", refid = "   "),
            )

            val groups = CardFundingNormalizer.identifyCandidateGroups(events)
            groups.keys shouldContainExactlyInAnyOrder listOf("REF-A")
            groups["REF-A"]!!.map { it.ledgerId } shouldContainExactlyInAnyOrder listOf("1", "2")
        }

        "normalizeAll returns empty list when no candidate groups or events exist" {
            CardFundingNormalizer.normalizeAll(
                emptyList(),
                FundingProvenanceResolver.NONE,
                defaultPriceProvider,
            ) shouldBe emptyList()

            CardFundingNormalizer.normalizeAll(
                listOf(event("1", "deposit", "100.00", refid = null)),
                FundingProvenanceResolver.NONE,
                defaultPriceProvider,
            ) shouldBe emptyList()
        }

        "normalizeGroup returns NotApplicable for empty group or group without funding/passthrough" {
            CardFundingNormalizer.normalizeGroup(
                "REF",
                emptyList(),
                FundingProvenanceResolver.NONE,
                defaultPriceProvider,
            ) shouldBe NormalizedFundingTransaction.NotApplicable

            val tradesOnly = listOf(
                event("1", KrakenApiConstants.LEDGER_TYPE_TRADE, "1.00", refid = "REF"),
            )
            CardFundingNormalizer.normalizeGroup(
                "REF",
                tradesOnly,
                FundingProvenanceResolver.NONE,
                defaultPriceProvider,
            ) shouldBe NormalizedFundingTransaction.NotApplicable
        }

        "legs exceeding maximum allowed span (120s) fail closed as Ambiguous" {
            val distantLegs = listOf(
                event("1", "deposit", "5000.00", time = baseTime),
                event("2", "spend", "-4980.00", fee = "20.00", time = baseTime.plusSeconds(60)),
                event("3", "receive", "0.0996", asset = "BTC", time = baseTime.plusSeconds(121)),
            )

            val result = CardFundingNormalizer.normalizeGroup(
                "CARD-REF-1",
                distantLegs,
                externalCardResolver,
                defaultPriceProvider,
            )

            result.shouldBeInstanceOf<NormalizedFundingTransaction.Ambiguous>()
            result.refid shouldBe "CARD-REF-1"
            result.reason shouldContain "exceeding maximum allowed span"
            result.unavailableAt shouldBe baseTime.plusSeconds(121)
        }

        "legs exceeding maximum allowed span with subsecond fraction fail closed as Ambiguous" {
            val distantLegs = listOf(
                event("1", "deposit", "5000.00", time = baseTime),
                event("2", "spend", "-4980.00", fee = "20.00", time = baseTime.plusSeconds(60)),
                event("3", "receive", "0.0996", asset = "BTC", time = baseTime.plusSeconds(120).plusMillis(100)),
            )

            val result = CardFundingNormalizer.normalizeGroup(
                "CARD-REF-1",
                distantLegs,
                externalCardResolver,
                defaultPriceProvider,
            )

            result.shouldBeInstanceOf<NormalizedFundingTransaction.Ambiguous>()
            result.refid shouldBe "CARD-REF-1"
            result.reason shouldContain "exceeding maximum allowed span"
        }

        "legs with sub-second offset are successfully normalized" {
            val subSecondLegs = listOf(
                event("1", "deposit", "5000.00", time = baseTime.plusMillis(100)),
                event("2", "spend", "-4980.00", fee = "20.00", time = baseTime.plusMillis(350)),
                event("3", "receive", "0.0996", asset = "BTC", time = baseTime.plusMillis(600)),
            )

            val result = CardFundingNormalizer.normalizeGroup(
                "CARD-REF-1",
                subSecondLegs,
                externalCardResolver,
                defaultPriceProvider,
            )

            result.shouldBeInstanceOf<NormalizedFundingTransaction.OwnerContribution>()
            result.refid shouldBe "CARD-REF-1"
            result.eventTime shouldBe baseTime.plusMillis(100)
            result.grossFundingUsd shouldBeEqualComparingTo BigDecimal("5000.00")
            result.feeUsd shouldBeEqualComparingTo BigDecimal("20.00")
            result.netOwnerCapitalUsd shouldBeEqualComparingTo BigDecimal("4980.00")
            result.actualPortfolioDeltas shouldBe listOf(
                TimedAssetDelta("1", baseTime.plusMillis(100), "USD", BigDecimal("5000.00")),
                TimedAssetDelta("2", baseTime.plusMillis(350), "USD", BigDecimal("-5000.00")),
                TimedAssetDelta("3", baseTime.plusMillis(600), "BTC", BigDecimal("0.0996")),
            )
            result.sourceLedgerIds shouldContainExactlyInAnyOrder listOf("1", "2", "3")
            result.representativeLedgerId shouldBe "1"
        }

        "legs with several-second offset within 120s are successfully normalized" {
            val offsetLegs = listOf(
                event("1", "deposit", "5000.00", time = baseTime),
                event("2", "spend", "-4980.00", fee = "20.00", time = baseTime.plusSeconds(15)),
                event("3", "receive", "0.0996", asset = "BTC", time = baseTime.plusSeconds(45)),
            )

            val result = CardFundingNormalizer.normalizeGroup(
                "CARD-REF-1",
                offsetLegs,
                externalCardResolver,
                defaultPriceProvider,
            )

            result.shouldBeInstanceOf<NormalizedFundingTransaction.OwnerContribution>()
            result.refid shouldBe "CARD-REF-1"
            result.netOwnerCapitalUsd shouldBeEqualComparingTo BigDecimal("4980.00")
        }

        "unexpected ledger type in card group fails closed as Ambiguous" {
            val mixedLegs = listOf(
                event("1", "deposit", "5000.00"),
                event("2", "spend", "-4980.00", fee = "20.00"),
                event("3", KrakenApiConstants.LEDGER_TYPE_STAKING, "1.00"),
            )

            val result = CardFundingNormalizer.normalizeGroup(
                "CARD-REF-1",
                mixedLegs,
                externalCardResolver,
                defaultPriceProvider,
            )

            result.shouldBeInstanceOf<NormalizedFundingTransaction.Ambiguous>()
            result.reason shouldContain "unexpected leg type"
        }

        "missing funding leg when passthrough exists returns NotApplicable" {
            val passthroughOnly = listOf(
                event("1", "spend", "-4980.00", fee = "20.00"),
                event("2", "receive", "0.0996", asset = "BTC"),
            )

            val result = CardFundingNormalizer.normalizeGroup(
                "CARD-REF-1",
                passthroughOnly,
                externalCardResolver,
                defaultPriceProvider,
            )

            result shouldBe NormalizedFundingTransaction.NotApplicable
        }

        "internal funding provenance returns NotApplicable" {
            val internalResolver = SimpleFundingProvenanceResolver(
                internalTransfers = listOf(
                    InternalTransferRecord(
                        refid = "CARD-REF-1",
                        asset = "USD",
                        amount = BigDecimal("5000.00"),
                        time = baseTime,
                    ),
                ),
            )
            val legs = listOf(
                event("1", "deposit", "5000.00"),
                event("2", "spend", "-5000.00"),
            )

            val result = CardFundingNormalizer.normalizeGroup(
                "CARD-REF-1",
                legs,
                internalResolver,
                defaultPriceProvider,
            )

            result shouldBe NormalizedFundingTransaction.NotApplicable
        }

        "unproven external funding with passthrough fails closed as Ambiguous" {
            val unprovenResolver = FundingProvenanceResolver.NONE
            val legs = listOf(
                event("1", "deposit", "5000.00"),
                event("2", "spend", "-4980.00", fee = "20.00"),
                event("3", "receive", "0.0996", asset = "BTC"),
            )

            val result = CardFundingNormalizer.normalizeGroup(
                "CARD-REF-1",
                legs,
                unprovenResolver,
                defaultPriceProvider,
            )

            result.shouldBeInstanceOf<NormalizedFundingTransaction.Ambiguous>()
            result.reason shouldContain "cannot be proven external"
        }

        "unproven external funding without passthrough returns NotApplicable" {
            val unprovenResolver = FundingProvenanceResolver.NONE
            val legs = listOf(
                event("1", "deposit", "5000.00"),
            )

            val result = CardFundingNormalizer.normalizeGroup(
                "CARD-REF-1",
                legs,
                unprovenResolver,
                defaultPriceProvider,
            )

            result shouldBe NormalizedFundingTransaction.NotApplicable
        }

        "conflicting external deposit and withdrawal legs fail closed as Ambiguous" {
            val conflictResolver = FundingProvenanceResolver { FundingEvidence.EXTERNAL }
            val legs = listOf(
                event("1", "deposit", "5000.00"),
                event("2", "withdrawal", "-5000.00"),
            )

            val result = CardFundingNormalizer.normalizeGroup(
                "CARD-REF-1",
                legs,
                conflictResolver,
                defaultPriceProvider,
            )

            result.shouldBeInstanceOf<NormalizedFundingTransaction.Ambiguous>()
            result.reason shouldContain "conflicting deposit and withdrawal"
        }

        "confirmed card deposit without passthrough remains pending" {
            val legs = listOf(
                event("1", "deposit", "5000.00"),
            )

            val result = CardFundingNormalizer.normalizeGroup(
                "CARD-REF-1",
                legs,
                externalCardResolver,
                defaultPriceProvider,
            )

            result.shouldBeInstanceOf<NormalizedFundingTransaction.Ambiguous>()
            result.reason shouldContain "missing spend and receive"
        }

        "confirmed Wire and ACH deposits without passthrough remain NotApplicable" {
            for ((refid, method) in listOf("WIRE-REF-1" to "Wire", "ACH-REF-1" to "ACH")) {
                val resolver = SimpleFundingProvenanceResolver(
                    deposits = listOf(depositRecord(refid, "5000.00", method = method)),
                )
                val result = CardFundingNormalizer.normalizeGroup(
                    refid,
                    listOf(event("$method-1", "deposit", "5000.00", refid = refid)),
                    resolver,
                    defaultPriceProvider,
                )

                result shouldBe NormalizedFundingTransaction.NotApplicable
            }
        }

        "external funding with an unresolved sibling fails closed" {
            val resolver = FundingProvenanceResolver { event ->
                if (event.ledgerId == "external") FundingEvidence.EXTERNAL else FundingEvidence.UNRESOLVED
            }
            val result = CardFundingNormalizer.normalizeGroup(
                "CARD-REF-1",
                listOf(
                    event("external", "deposit", "5000.00"),
                    event("unresolved", "deposit", "1.00"),
                    event("spend", "spend", "-4980.00", fee = "20.00"),
                    event("receive", "receive", "0.0996", asset = "BTC"),
                ),
                resolver,
                defaultPriceProvider,
            )

            result.shouldBeInstanceOf<NormalizedFundingTransaction.Ambiguous>()
            result.reason shouldContain "unresolved funding provenance"
        }

        "external and internal funding provenance fails closed" {
            val resolver = FundingProvenanceResolver { event ->
                if (event.ledgerId == "external") FundingEvidence.EXTERNAL else FundingEvidence.INTERNAL
            }
            val result = CardFundingNormalizer.normalizeGroup(
                "CARD-REF-1",
                listOf(
                    event("external", "deposit", "5000.00"),
                    event("internal", "deposit", "1.00"),
                    event("spend", "spend", "-4980.00", fee = "20.00"),
                    event("receive", "receive", "0.0996", asset = "BTC"),
                ),
                resolver,
                defaultPriceProvider,
            )

            result.shouldBeInstanceOf<NormalizedFundingTransaction.Ambiguous>()
            result.reason shouldContain "external and internal"
        }

        "unresolved and internal funding siblings fail closed" {
            val resolver = FundingProvenanceResolver { event ->
                if (event.ledgerId == "internal") FundingEvidence.INTERNAL else FundingEvidence.UNRESOLVED
            }
            val result = CardFundingNormalizer.normalizeGroup(
                "CARD-REF-1",
                listOf(
                    event("internal", "deposit", "5000.00"),
                    event("unresolved", "deposit", "1.00"),
                    event("spend", "spend", "-4980.00", fee = "20.00"),
                    event("receive", "receive", "0.0996", asset = "BTC"),
                ),
                resolver,
                defaultPriceProvider,
            )

            result.shouldBeInstanceOf<NormalizedFundingTransaction.Ambiguous>()
            result.reason shouldContain "unresolved funding provenance"
        }

        "card evidence remains pending even when its funding row is unresolved" {
            val resolver = object : FundingProvenanceResolver {
                override fun resolve(event: LedgerEvent): FundingEvidence = FundingEvidence.UNRESOLVED

                override fun isCardFunding(event: LedgerEvent): Boolean = true
            }
            val result = CardFundingNormalizer.normalizeGroup(
                "CARD-REF-1",
                listOf(event("card", "deposit", "5000.00")),
                resolver,
                defaultPriceProvider,
            )

            result.shouldBeInstanceOf<NormalizedFundingTransaction.Ambiguous>()
            result.reason shouldContain "cannot be proven external"
        }

        "ZUSD is treated as USD for a complete card group" {
            val resolver = FundingProvenanceResolver { FundingEvidence.EXTERNAL }
            val result = CardFundingNormalizer.normalizeGroup(
                "CARD-REF-1",
                listOf(
                    event("deposit", "deposit", "5000.00", asset = "ZUSD"),
                    event("spend", "spend", "-4980.00", asset = "ZUSD", fee = "20.00"),
                    event("receive", "receive", "0.0996", asset = "BTC"),
                ),
                resolver,
                defaultPriceProvider,
            )

            result.shouldBeInstanceOf<NormalizedFundingTransaction.OwnerContribution>()
            result.netOwnerCapitalUsd shouldBeEqualComparingTo BigDecimal("4980.00")
        }

        "multiple external funding legs are not collapsed into one owner event" {
            val resolver = FundingProvenanceResolver { FundingEvidence.EXTERNAL }
            val result = CardFundingNormalizer.normalizeGroup(
                "CARD-REF-1",
                listOf(
                    event("1", "deposit", "5000.00"),
                    event("2", "deposit", "1.00"),
                    event("3", "spend", "-4980.00", fee = "20.00"),
                    event("4", "receive", "0.0996", asset = "BTC"),
                ),
                resolver,
                defaultPriceProvider,
            )

            result.shouldBeInstanceOf<NormalizedFundingTransaction.Ambiguous>()
            result.reason shouldContain "Multiple external funding legs"
        }

        "card deposit missing spend plumbing leg fails closed as Ambiguous" {
            val legs = listOf(
                event("1", "deposit", "5000.00"),
                event("2", "receive", "0.0996", asset = "BTC"),
            )

            val result = CardFundingNormalizer.normalizeGroup(
                "CARD-REF-1",
                legs,
                externalCardResolver,
                defaultPriceProvider,
            )

            result.shouldBeInstanceOf<NormalizedFundingTransaction.Ambiguous>()
            result.reason shouldContain "missing USD spend plumbing leg"
        }

        "card deposit missing receive plumbing leg fails closed as Ambiguous" {
            val legs = listOf(
                event("1", "deposit", "5000.00"),
                event("2", "spend", "-4980.00", fee = "20.00"),
            )

            val result = CardFundingNormalizer.normalizeGroup(
                "CARD-REF-1",
                legs,
                externalCardResolver,
                defaultPriceProvider,
            )

            result.shouldBeInstanceOf<NormalizedFundingTransaction.Ambiguous>()
            result.reason shouldContain "missing crypto receive plumbing leg"
        }

        "card deposit where funding leg is non-USD fails closed as Ambiguous" {
            val resolver = SimpleFundingProvenanceResolver(
                deposits = listOf(
                    depositRecord("CARD-REF-1", "1.0", asset = "BTC", method = "Visa"),
                ),
            )
            val legs = listOf(
                event("1", "deposit", "1.0", asset = "BTC"),
                event("2", "spend", "-4980.00", fee = "20.00"),
                event("3", "receive", "0.0996", asset = "ETH"),
            )

            val result = CardFundingNormalizer.normalizeGroup(
                "CARD-REF-1",
                legs,
                resolver,
                defaultPriceProvider,
            )

            result.shouldBeInstanceOf<NormalizedFundingTransaction.Ambiguous>()
            result.reason shouldContain "deposit funding leg must be USD"
        }

        "card deposit where spend leg is non-USD fails closed as Ambiguous" {
            val legs = listOf(
                event("1", "deposit", "5000.00"),
                event("2", "spend", "-1.0", asset = "ETH"),
                event("3", "receive", "0.0996", asset = "BTC"),
            )

            val result = CardFundingNormalizer.normalizeGroup(
                "CARD-REF-1",
                legs,
                externalCardResolver,
                defaultPriceProvider,
            )

            result.shouldBeInstanceOf<NormalizedFundingTransaction.Ambiguous>()
            result.reason shouldContain "Spend plumbing leg must be USD"
        }

        "card deposit where receive leg is USD fails closed as Ambiguous" {
            val legs = listOf(
                event("1", "deposit", "5000.00"),
                event("2", "spend", "-4980.00", fee = "20.00"),
                event("3", "receive", "4980.00", asset = "USD"),
            )

            val result = CardFundingNormalizer.normalizeGroup(
                "CARD-REF-1",
                legs,
                externalCardResolver,
                defaultPriceProvider,
            )

            result.shouldBeInstanceOf<NormalizedFundingTransaction.Ambiguous>()
            result.reason shouldContain "Receive plumbing leg must be non-USD"
        }

        "card deposit with conflicting leg balance directions fails closed as Ambiguous" {
            val legs = listOf(
                event("1", "deposit", "5000.00"),
                event("2", "spend", "4980.00", fee = "20.00"), // wrong direction
                event("3", "receive", "0.0996", asset = "BTC"),
            )

            val result = CardFundingNormalizer.normalizeGroup(
                "CARD-REF-1",
                legs,
                externalCardResolver,
                defaultPriceProvider,
            )

            result.shouldBeInstanceOf<NormalizedFundingTransaction.Ambiguous>()
            result.reason shouldContain "Conflicting directions in card funding legs"
        }

        "card deposit with non-positive funding leg delta fails closed as Ambiguous" {
            val legs = listOf(
                event("1", "deposit", "-100.00"),
                event("2", "spend", "-90.00", fee = "10.00"),
                event("3", "receive", "0.001", asset = "BTC"),
            )
            val resolver = FundingProvenanceResolver { FundingEvidence.EXTERNAL }

            val result = CardFundingNormalizer.normalizeGroup(
                "CARD-REF-1",
                legs,
                resolver,
                defaultPriceProvider,
            )

            result.shouldBeInstanceOf<NormalizedFundingTransaction.Ambiguous>()
            result.reason shouldContain "Conflicting directions in card funding legs"
        }

        "card deposit with non-positive receive leg delta fails closed as Ambiguous" {
            val legs = listOf(
                event("1", "deposit", "100.00"),
                event("2", "spend", "-90.00", fee = "10.00"),
                event("3", "receive", "-0.001", asset = "BTC"),
            )
            val resolver = FundingProvenanceResolver { FundingEvidence.EXTERNAL }

            val result = CardFundingNormalizer.normalizeGroup(
                "CARD-REF-1",
                legs,
                resolver,
                defaultPriceProvider,
            )

            result.shouldBeInstanceOf<NormalizedFundingTransaction.Ambiguous>()
            result.reason shouldContain "Conflicting directions in card funding legs"
        }

        "cross-asset fee conversion converts non-USD fee to USD at event time" {
            val legs = listOf(
                event("1", "deposit", "5000.00"),
                event("2", "spend", "-4980.00", fee = "20.00"),
                // 0.0001 BTC fee @ $50,000 = $5.00
                event("3", "receive", "0.0996", asset = "BTC", fee = "0.0001"),
            )

            val result = CardFundingNormalizer.normalizeGroup(
                "CARD-REF-1",
                legs,
                externalCardResolver,
                defaultPriceProvider,
            )

            result.shouldBeInstanceOf<NormalizedFundingTransaction.OwnerContribution>()
            result.grossFundingUsd shouldBeEqualComparingTo BigDecimal("5000.00")
            result.feeUsd shouldBeEqualComparingTo BigDecimal("25.00")
            result.netOwnerCapitalUsd shouldBeEqualComparingTo BigDecimal("4975.00")
            result.actualPortfolioDeltas shouldBe listOf(
                TimedAssetDelta("1", baseTime, "USD", BigDecimal("5000.00")),
                TimedAssetDelta("2", baseTime, "USD", BigDecimal("-5000.00")),
                TimedAssetDelta("3", baseTime, "BTC", BigDecimal("0.0995")),
            )
        }

        "unpriceable crypto fee returns UnpriceableFee" {
            val unpriceableProvider = CardFeePriceProvider { _, _ -> null }
            val legs = listOf(
                event("1", "deposit", "5000.00"),
                event("2", "spend", "-4980.00", fee = "20.00"),
                event("3", "receive", "0.0996", asset = "BTC", fee = "0.0001"),
            )

            val result = CardFundingNormalizer.normalizeGroup(
                "CARD-REF-1",
                legs,
                externalCardResolver,
                unpriceableProvider,
            )

            result.shouldBeInstanceOf<NormalizedFundingTransaction.UnpriceableFee>()
            result.asset shouldBe "BTC"
            result.refid shouldBe "CARD-REF-1"
        }

        "crypto fee with zero or negative price returns UnpriceableFee" {
            val zeroPriceProvider = CardFeePriceProvider { _, _ -> BigDecimal.ZERO }
            val legs = listOf(
                event("1", "deposit", "5000.00"),
                event("2", "spend", "-4980.00", fee = "20.00"),
                event("3", "receive", "0.0996", asset = "BTC", fee = "0.0001"),
            )

            val result = CardFundingNormalizer.normalizeGroup(
                "CARD-REF-1",
                legs,
                externalCardResolver,
                zeroPriceProvider,
            )

            result.shouldBeInstanceOf<NormalizedFundingTransaction.UnpriceableFee>()
            result.asset shouldBe "BTC"
        }

        "card deposit where net capital is not positive fails closed as Ambiguous" {
            val legs = listOf(
                event("1", "deposit", "20.00"),
                event("2", "spend", "-20.00", fee = "25.00"),
                event("3", "receive", "0.0004", asset = "BTC"),
            )
            val resolver = SimpleFundingProvenanceResolver(
                deposits = listOf(
                    depositRecord("CARD-REF-1", "20.00", method = "Visa"),
                ),
            )

            val result = CardFundingNormalizer.normalizeGroup(
                "CARD-REF-1",
                legs,
                resolver,
                defaultPriceProvider,
            )

            result.shouldBeInstanceOf<NormalizedFundingTransaction.Ambiguous>()
            result.reason shouldContain "Net capital after fees"
        }

        "card withdrawal missing receive plumbing leg fails closed as Ambiguous" {
            val withdrawResolver = SimpleFundingProvenanceResolver(
                withdrawals = listOf(
                    withdrawRecord("CARD-REF-1", "-5000.00"),
                ),
            )
            val legs = listOf(
                event("1", "withdrawal", "-5000.00"),
                event("2", "spend", "-0.0996", asset = "BTC"),
            )

            val result = CardFundingNormalizer.normalizeGroup(
                "CARD-REF-1",
                legs,
                withdrawResolver,
                defaultPriceProvider,
            )

            result.shouldBeInstanceOf<NormalizedFundingTransaction.Ambiguous>()
            result.reason shouldContain "Card withdrawal missing receive plumbing leg"
        }

        "card withdrawal missing spend plumbing leg fails closed as Ambiguous" {
            val withdrawResolver = SimpleFundingProvenanceResolver(
                withdrawals = listOf(
                    withdrawRecord("CARD-REF-1", "5000.00"),
                ),
            )
            val legs = listOf(
                event("1", "withdrawal", "-5000.00"),
                event("2", "receive", "0.0996", asset = "BTC"),
            )

            val result = CardFundingNormalizer.normalizeGroup(
                "CARD-REF-1",
                legs,
                withdrawResolver,
                defaultPriceProvider,
            )

            result.shouldBeInstanceOf<NormalizedFundingTransaction.Ambiguous>()
            result.reason shouldContain "Card withdrawal missing spend plumbing leg"
        }

        "card withdrawal with conflicting leg balance directions fails closed as Ambiguous" {
            val withdrawResolver = SimpleFundingProvenanceResolver(
                withdrawals = listOf(
                    withdrawRecord("CARD-REF-1", "5000.00"),
                ),
            )
            val legs = listOf(
                event("1", "withdrawal", "-5000.00"),
                event("2", "spend", "0.0996", asset = "BTC"), // wrong direction for spend
                event("3", "receive", "4980.00", fee = "20.00"),
            )

            val result = CardFundingNormalizer.normalizeGroup(
                "CARD-REF-1",
                legs,
                withdrawResolver,
                defaultPriceProvider,
            )

            result.shouldBeInstanceOf<NormalizedFundingTransaction.Ambiguous>()
            result.reason shouldContain "Conflicting directions in card withdrawal legs"
        }

        "card withdrawal with non-negative funding leg delta fails closed as Ambiguous" {
            val legs = listOf(
                event("1", "withdrawal", "100.00"),
                event("2", "spend", "-0.001", asset = "BTC"),
                event("3", "receive", "90.00", fee = "10.00"),
            )
            val resolver = FundingProvenanceResolver { FundingEvidence.EXTERNAL }

            val result = CardFundingNormalizer.normalizeGroup(
                "CARD-REF-1",
                legs,
                resolver,
                defaultPriceProvider,
            )

            result.shouldBeInstanceOf<NormalizedFundingTransaction.Ambiguous>()
            result.reason shouldContain "Conflicting directions in card withdrawal legs"
        }

        "card withdrawal with non-positive receive leg delta fails closed as Ambiguous" {
            val legs = listOf(
                event("1", "withdrawal", "-100.00"),
                event("2", "spend", "-0.001", asset = "BTC"),
                event("3", "receive", "-90.00", fee = "10.00"),
            )
            val resolver = FundingProvenanceResolver { FundingEvidence.EXTERNAL }

            val result = CardFundingNormalizer.normalizeGroup(
                "CARD-REF-1",
                legs,
                resolver,
                defaultPriceProvider,
            )

            result.shouldBeInstanceOf<NormalizedFundingTransaction.Ambiguous>()
            result.reason shouldContain "Conflicting directions in card withdrawal legs"
        }

        "valid card withdrawal produces OwnerWithdrawal" {
            val withdrawResolver = SimpleFundingProvenanceResolver(
                withdrawals = listOf(
                    withdrawRecord("CARD-REF-1", "5000.00"),
                ),
            )
            val legs = listOf(
                event("1", "withdrawal", "-5000.00"),
                event("2", "spend", "-0.0996", asset = "BTC"),
                event("3", "receive", "4980.00", fee = "20.00"),
            )

            val result = CardFundingNormalizer.normalizeGroup(
                "CARD-REF-1",
                legs,
                withdrawResolver,
                defaultPriceProvider,
            )

            result.shouldBeInstanceOf<NormalizedFundingTransaction.OwnerWithdrawal>()
            result.refid shouldBe "CARD-REF-1"
            result.grossFundingUsd shouldBeEqualComparingTo BigDecimal("-5000.00")
            result.feeUsd shouldBeEqualComparingTo BigDecimal("20.00")
            result.netOwnerCapitalUsd shouldBeEqualComparingTo BigDecimal("-4980.00")
            result.sourceLedgerIds shouldContainExactlyInAnyOrder listOf("1", "2", "3")
        }

        "card withdrawal with non-negative net capital fails closed as Ambiguous" {
            val withdrawResolver = SimpleFundingProvenanceResolver(
                withdrawals = listOf(
                    withdrawRecord("CARD-REF-1", "10.00"),
                ),
            )
            val legs = listOf(
                event("1", "withdrawal", "-10.00"),
                event("2", "spend", "-0.0996", asset = "BTC"),
                event("3", "receive", "30.00", fee = "20.00"), // fee = 20, gross = -10, net = +10 >= 0
            )

            val result = CardFundingNormalizer.normalizeGroup(
                "CARD-REF-1",
                legs,
                withdrawResolver,
                defaultPriceProvider,
            )

            result.shouldBeInstanceOf<NormalizedFundingTransaction.Ambiguous>()
            result.reason shouldContain "Net capital after fees"
        }

        "USD-only funding plumbing netting to zero fails closed as Ambiguous" {
            val resolver = SimpleFundingProvenanceResolver(
                deposits = listOf(
                    depositRecord("WIRE-REF-1", "500.00", method = "Wire"),
                ),
            )
            val legs = listOf(
                event("1", "deposit", "500.00", refid = "WIRE-REF-1"),
                event("2", "spend", "-500.00", refid = "WIRE-REF-1"),
            )

            val result = CardFundingNormalizer.normalizeGroup(
                "WIRE-REF-1",
                legs,
                resolver,
                defaultPriceProvider,
            )

            result.shouldBeInstanceOf<NormalizedFundingTransaction.Ambiguous>()
            result.reason shouldContain "nets to zero; cannot erase owner capital"
        }

        "USD-only funding plumbing with positive net produces OwnerContribution" {
            val resolver = SimpleFundingProvenanceResolver(
                deposits = listOf(
                    depositRecord("WIRE-REF-1", "500.00", method = "Wire"),
                ),
            )
            val legs = listOf(
                event("1", "deposit", "500.00", refid = "WIRE-REF-1"),
                event("2", "spend", "-400.00", fee = "10.00", refid = "WIRE-REF-1"),
            )

            val result = CardFundingNormalizer.normalizeGroup(
                "WIRE-REF-1",
                legs,
                resolver,
                defaultPriceProvider,
            )

            result.shouldBeInstanceOf<NormalizedFundingTransaction.OwnerContribution>()
            result.grossFundingUsd shouldBeEqualComparingTo BigDecimal("500.00")
            result.feeUsd shouldBeEqualComparingTo BigDecimal("10.00")
            result.netOwnerCapitalUsd shouldBeEqualComparingTo BigDecimal("90.00")
        }

        "USD-only funding plumbing where spend exceeds deposit stays separately typed" {
            val resolver = SimpleFundingProvenanceResolver(
                deposits = listOf(
                    depositRecord("WIRE-REF-1", "100.00", method = "Wire"),
                ),
            )
            val legs = listOf(
                event("1", "deposit", "100.00", refid = "WIRE-REF-1"),
                event("2", "spend", "-200.00", refid = "WIRE-REF-1"),
            )

            val result = CardFundingNormalizer.normalizeGroup(
                "WIRE-REF-1",
                legs,
                resolver,
                defaultPriceProvider,
            )

            result shouldBe NormalizedFundingTransaction.NotApplicable
        }

        "USD-only withdrawal plumbing netting to zero fails closed as Ambiguous" {
            val withdrawResolver = SimpleFundingProvenanceResolver(
                withdrawals = listOf(
                    withdrawRecord("WIRE-W-1", "-500.00"),
                ),
            )
            val legs = listOf(
                event("1", "withdrawal", "-500.00", refid = "WIRE-W-1"),
                event("2", "receive", "500.00", refid = "WIRE-W-1"),
            )

            val result = CardFundingNormalizer.normalizeGroup(
                "WIRE-W-1",
                legs,
                withdrawResolver,
                defaultPriceProvider,
            )

            result.shouldBeInstanceOf<NormalizedFundingTransaction.Ambiguous>()
            result.reason shouldContain "USD withdrawal plumbing nets to zero"
        }

        "USD-only withdrawal plumbing with negative net produces OwnerWithdrawal" {
            val withdrawResolver = SimpleFundingProvenanceResolver(
                withdrawals = listOf(
                    withdrawRecord("WIRE-W-1", "-500.00"),
                ),
            )
            val legs = listOf(
                event("1", "withdrawal", "-500.00", fee = "10.00", refid = "WIRE-W-1"),
                event("2", "receive", "400.00", refid = "WIRE-W-1"),
            )

            val result = CardFundingNormalizer.normalizeGroup(
                "WIRE-W-1",
                legs,
                withdrawResolver,
                defaultPriceProvider,
            )

            result.shouldBeInstanceOf<NormalizedFundingTransaction.OwnerWithdrawal>()
            result.grossFundingUsd shouldBeEqualComparingTo BigDecimal("-500.00")
            result.feeUsd shouldBeEqualComparingTo BigDecimal("10.00")
            result.netOwnerCapitalUsd shouldBeEqualComparingTo BigDecimal("-110.00")
        }

        "USD-only withdrawal plumbing where receive exceeds withdrawal stays separately typed" {
            val withdrawResolver = SimpleFundingProvenanceResolver(
                withdrawals = listOf(
                    withdrawRecord("WIRE-W-1", "-100.00"),
                ),
            )
            val legs = listOf(
                event("1", "withdrawal", "-100.00", refid = "WIRE-W-1"),
                event("2", "receive", "200.00", refid = "WIRE-W-1"),
            )

            val result = CardFundingNormalizer.normalizeGroup(
                "WIRE-W-1",
                legs,
                withdrawResolver,
                defaultPriceProvider,
            )

            result shouldBe NormalizedFundingTransaction.NotApplicable
        }

        "extractActualPortfolioEffects extracts TimedAssetDelta without requiring fee pricing" {
            val legs = listOf(
                event("1", "deposit", "5000.00", time = baseTime),
                event("2", "spend", "-4980.00", fee = "20.00", time = baseTime.plusSeconds(5)),
                event("3", "receive", "0.0996", asset = "BTC", fee = "0.0001", time = baseTime.plusSeconds(10)),
            )

            val effects = CardFundingNormalizer.extractActualPortfolioEffects(
                "CARD-REF-1",
                legs,
                externalCardResolver,
            )

            effects.shouldBeInstanceOf<CardFundingNormalizer.CardActualPortfolioEffects>()
            effects.refid shouldBe "CARD-REF-1"
            effects.representativeLedgerId shouldBe "1"
            effects.actualPortfolioDeltas shouldBe listOf(
                TimedAssetDelta("1", baseTime, "USD", BigDecimal("5000.00")),
                TimedAssetDelta("2", baseTime.plusSeconds(5), "USD", BigDecimal("-5000.00")),
                TimedAssetDelta("3", baseTime.plusSeconds(10), "BTC", BigDecimal("0.0995")),
            )
        }

        "extractActualPortfolioEffects returns null for invalid card shape" {
            val incompleteLegs = listOf(
                event("1", "deposit", "5000.00", time = baseTime),
            )

            val effects = CardFundingNormalizer.extractActualPortfolioEffects(
                "CARD-REF-1",
                incompleteLegs,
                externalCardResolver,
            )

            effects shouldBe null
        }
    }
}
