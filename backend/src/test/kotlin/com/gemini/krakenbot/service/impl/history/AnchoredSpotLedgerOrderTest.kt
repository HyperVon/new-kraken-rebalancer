package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.model.KrakenApiConstants
import com.gemini.krakenbot.model.LedgerEvent
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant

class AnchoredSpotLedgerOrderTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    private val time = Instant.parse("2026-09-30T12:00:00Z")

    init {
        "orders an open authoritative Spot chain from its starting balance" {
            val result = RebalancerComparisonCalculator.anchoredSpotLedgerOrder(
                events = listOf(
                    ledger("later-withdrawal", "-5.00", "105.00", KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL),
                    ledger("earlier-deposit", "10.00", "110.00", KrakenApiConstants.LEDGER_TYPE_DEPOSIT),
                ),
                openingBalance = BigDecimal("100.00"),
            )

            result shouldBe RebalancerComparisonCalculator.AnchoredSpotLedgerOrder(
                orderedLedgerIds = listOf("earlier-deposit", "later-withdrawal"),
                isClosedCycle = false,
                closingBalance = BigDecimal("105.00"),
            )
        }

        "rejects a lone checkpoint that cannot establish a deterministic order" {
            val result = RebalancerComparisonCalculator.anchoredSpotLedgerOrder(
                events = listOf(
                    ledger("single-deposit", "10.00", "110.00", KrakenApiConstants.LEDGER_TYPE_DEPOSIT),
                ),
                openingBalance = BigDecimal("100.00"),
            )

            result shouldBe null
        }

        "rejects duplicate identities, mixed assets, and mixed checkpoint times" {
            val deposit = ledger("duplicate-id", "10.00", "110.00", KrakenApiConstants.LEDGER_TYPE_DEPOSIT)
            val withdrawal = ledger("duplicate-id", "-10.00", "100.00", KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL)
            val structuralVariants = listOf(
                listOf(deposit, withdrawal),
                listOf(
                    deposit,
                    ledger(
                        "other-asset",
                        "-10.00",
                        "100.00",
                        KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL,
                    ).copy(asset = "ETH"),
                ),
                listOf(
                    deposit,
                    ledger(
                        "other-time",
                        "-10.00",
                        "100.00",
                        KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL,
                    ).copy(time = time.plusSeconds(1)),
                ),
            )

            structuralVariants.forEach { events ->
                RebalancerComparisonCalculator.anchoredSpotLedgerOrder(
                    events = events,
                    openingBalance = BigDecimal("100.00"),
                ) shouldBe null
            }
        }

        "rejects non-authoritative balances from synthetic checkpoints" {
            val result = RebalancerComparisonCalculator.anchoredSpotLedgerOrder(
                events = listOf(
                    ledger("synthetic-checkpoint", "10.00", "110.00", KrakenApiConstants.LEDGER_TYPE_DEPOSIT)
                        .copy(hasAuthoritativeBalance = false),
                    ledger("synthetic-followup", "-10.00", "100.00", KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL),
                ),
                openingBalance = BigDecimal("100.00"),
            )

            result shouldBe null
        }

        "rejects zero-only checkpoints and an open chain with a mismatched starting balance" {
            val zeroOnly = listOf(
                ledger("zero-one", "0.00", "100.00", KrakenApiConstants.LEDGER_TYPE_ADJUSTMENT),
                ledger("zero-two", "0.00", "100.00", KrakenApiConstants.LEDGER_TYPE_ADJUSTMENT),
            )
            val mismatchedStart = listOf(
                ledger("start-mismatch-deposit", "10.00", "110.00", KrakenApiConstants.LEDGER_TYPE_DEPOSIT),
                ledger("start-mismatch-withdrawal", "-5.00", "105.00", KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL),
            )

            RebalancerComparisonCalculator.anchoredSpotLedgerOrder(
                events = zeroOnly,
                openingBalance = BigDecimal("100.00"),
            ) shouldBe null
            RebalancerComparisonCalculator.anchoredSpotLedgerOrder(
                events = mismatchedStart,
                openingBalance = BigDecimal("99.00"),
            ) shouldBe null
        }

        "anchors a closed Spot checkpoint cycle at the unique opening balance" {
            val result = RebalancerComparisonCalculator.anchoredSpotLedgerOrder(
                events = listOf(
                    ledger("cycle-withdrawal", "-10.00", "100.00", KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL),
                    ledger("cycle-deposit", "10.00", "110.00", KrakenApiConstants.LEDGER_TYPE_DEPOSIT),
                ),
                openingBalance = BigDecimal("100.00"),
            )

            result shouldBe RebalancerComparisonCalculator.AnchoredSpotLedgerOrder(
                orderedLedgerIds = listOf("cycle-deposit", "cycle-withdrawal"),
                isClosedCycle = true,
                closingBalance = BigDecimal("100.00"),
            )
        }

        "rejects a closed cycle when its opening balance does not identify an entry point" {
            val result = RebalancerComparisonCalculator.anchoredSpotLedgerOrder(
                events = listOf(
                    ledger("unanchored-withdrawal", "-10.00", "100.00", KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL),
                    ledger("unanchored-deposit", "10.00", "110.00", KrakenApiConstants.LEDGER_TYPE_DEPOSIT),
                ),
                openingBalance = BigDecimal("105.00"),
            )

            result shouldBe null
        }

        "rejects forked and merged balance chains instead of selecting one ledger order" {
            val forked = listOf(
                ledger("fork-start", "10.00", "110.00", KrakenApiConstants.LEDGER_TYPE_DEPOSIT),
                ledger("fork-left", "-5.00", "105.00", KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL),
                ledger("fork-right", "-4.00", "106.00", KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL),
            )
            val merged = listOf(
                ledger("merge-start-a", "10.00", "110.00", KrakenApiConstants.LEDGER_TYPE_DEPOSIT),
                ledger("merge-start-b", "20.00", "120.00", KrakenApiConstants.LEDGER_TYPE_DEPOSIT),
                ledger("merge-middle", "-10.00", "110.00", KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL),
                ledger("merge-end", "-10.00", "100.00", KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL),
            )

            RebalancerComparisonCalculator.anchoredSpotLedgerOrder(
                events = forked,
                openingBalance = BigDecimal("100.00"),
            ) shouldBe null
            RebalancerComparisonCalculator.anchoredSpotLedgerOrder(
                events = merged,
                openingBalance = BigDecimal("100.00"),
            ) shouldBe null
        }

        "rejects multiple independent open components" {
            val result = RebalancerComparisonCalculator.anchoredSpotLedgerOrder(
                events = listOf(
                    ledger("component-a", "10.00", "110.00", KrakenApiConstants.LEDGER_TYPE_DEPOSIT),
                    ledger("component-b", "20.00", "220.00", KrakenApiConstants.LEDGER_TYPE_DEPOSIT),
                ),
                openingBalance = BigDecimal("100.00"),
            )

            result shouldBe null
        }

        "rejects an open path that does not cover every disconnected closed component" {
            val result = RebalancerComparisonCalculator.anchoredSpotLedgerOrder(
                events = listOf(
                    ledger("open-component", "10.00", "110.00", KrakenApiConstants.LEDGER_TYPE_DEPOSIT),
                    ledger("closed-component-deposit", "20.00", "220.00", KrakenApiConstants.LEDGER_TYPE_DEPOSIT),
                    ledger(
                        "closed-component-withdrawal",
                        "-20.00",
                        "200.00",
                        KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL,
                    ),
                ),
                openingBalance = BigDecimal("100.00"),
            )

            result shouldBe null
        }

        "rejects multiple closed cycles even when only one matches the opening balance" {
            val result = RebalancerComparisonCalculator.anchoredSpotLedgerOrder(
                events = listOf(
                    ledger("first-cycle-deposit", "10.00", "110.00", KrakenApiConstants.LEDGER_TYPE_DEPOSIT),
                    ledger("first-cycle-withdrawal", "-10.00", "100.00", KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL),
                    ledger("second-cycle-deposit", "20.00", "220.00", KrakenApiConstants.LEDGER_TYPE_DEPOSIT),
                    ledger("second-cycle-withdrawal", "-20.00", "200.00", KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL),
                ),
                openingBalance = BigDecimal("100.00"),
            )

            result shouldBe null
        }

        "places zero-delta checkpoints at unique intermediate and closing boundaries" {
            val result = RebalancerComparisonCalculator.anchoredSpotLedgerOrder(
                events = listOf(
                    ledger("zero-after-deposit", "0.00", "110.00", KrakenApiConstants.LEDGER_TYPE_ADJUSTMENT),
                    ledger("boundary-withdrawal", "-5.00", "105.00", KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL),
                    ledger("boundary-deposit", "10.00", "110.00", KrakenApiConstants.LEDGER_TYPE_DEPOSIT),
                    ledger("zero-at-close", "0.00", "105.00", KrakenApiConstants.LEDGER_TYPE_ADJUSTMENT),
                ),
                openingBalance = BigDecimal("100.00"),
            )

            result?.orderedLedgerIds shouldBe listOf(
                "boundary-deposit",
                "zero-after-deposit",
                "boundary-withdrawal",
                "zero-at-close",
            )
        }

        "rejects zero-delta checkpoints whose balance matches repeated cycle boundaries" {
            val result = RebalancerComparisonCalculator.anchoredSpotLedgerOrder(
                events = listOf(
                    ledger("repeated-cycle-deposit", "10.00", "110.00", KrakenApiConstants.LEDGER_TYPE_DEPOSIT),
                    ledger("repeated-cycle-withdrawal", "-10.00", "100.00", KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL),
                    ledger("repeated-cycle-zero", "0.00", "100.00", KrakenApiConstants.LEDGER_TYPE_ADJUSTMENT),
                ),
                openingBalance = BigDecimal("100.00"),
            )

            result shouldBe null
        }

        "rejects malformed economics that cannot establish fixed Spot checkpoint order" {
            val malformedEvents = listOf(
                listOf(
                    ledger(
                        "bad-fee-deposit",
                        "10.00",
                        "109.90",
                        KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                        fee = "0.10",
                        hasAuthoritativeFee = false,
                    ),
                    ledger("bad-fee-withdrawal", "-10.00", "99.90", KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL),
                ),
                listOf(
                    ledger("negative-deposit", "-10.00", "90.00", KrakenApiConstants.LEDGER_TYPE_DEPOSIT),
                    ledger("negative-deposit-followup", "10.00", "100.00", KrakenApiConstants.LEDGER_TYPE_ADJUSTMENT),
                ),
                listOf(
                    ledger(
                        "invalid-amount",
                        "10.00",
                        "110.00",
                        KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    ).copy(hasValidAmount = false),
                    ledger("invalid-amount-followup", "-10.00", "100.00", KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL),
                ),
                listOf(
                    ledger(
                        "invalid-fee",
                        "10.00",
                        "110.00",
                        KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    ).copy(hasValidFee = false),
                    ledger("invalid-fee-followup", "-10.00", "100.00", KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL),
                ),
                listOf(
                    ledger(
                        "negative-fee",
                        "10.00",
                        "110.00",
                        KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    ).copy(fee = BigDecimal("-0.10")),
                    ledger("negative-fee-followup", "-10.00", "100.00", KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL),
                ),
                listOf(
                    ledger("unsupported-type", "10.00", "110.00", "trade"),
                    ledger("unsupported-type-followup", "-10.00", "100.00", KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL),
                ),
            )

            malformedEvents.forEach { events ->
                RebalancerComparisonCalculator.anchoredSpotLedgerOrder(
                    events = events,
                    openingBalance = BigDecimal("100.00"),
                ) shouldBe null
            }
        }

        "accepts a nonzero fee only when its reported fee is authoritative" {
            val result = RebalancerComparisonCalculator.anchoredSpotLedgerOrder(
                events = listOf(
                    ledger(
                        "verified-fee-deposit",
                        "10.00",
                        "109.90",
                        KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                        fee = "0.10",
                    ),
                    ledger("verified-fee-withdrawal", "-10.00", "99.90", KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL),
                ),
                openingBalance = BigDecimal("100.00"),
            )

            result shouldBe RebalancerComparisonCalculator.AnchoredSpotLedgerOrder(
                orderedLedgerIds = listOf("verified-fee-deposit", "verified-fee-withdrawal"),
                isClosedCycle = false,
                closingBalance = BigDecimal("99.90"),
            )
        }
    }

    private fun ledger(
        id: String,
        amount: String,
        balance: String,
        type: String,
        fee: String = "0.00",
        hasAuthoritativeFee: Boolean = fee != "0.00",
    ) = LedgerEvent(
        ledgerId = id,
        time = time,
        type = type,
        asset = "BTC",
        amount = BigDecimal(amount),
        fee = BigDecimal(fee),
        balance = BigDecimal(balance),
        hasAuthoritativeBalance = true,
        hasAuthoritativeFee = hasAuthoritativeFee,
    )
}
