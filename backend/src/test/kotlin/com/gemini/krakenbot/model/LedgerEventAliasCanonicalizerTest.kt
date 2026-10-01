package com.gemini.krakenbot.model

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant

class LedgerEventAliasCanonicalizerTest :
    StringSpec({
        val sol = LedgerEvent(
            ledgerId = "SOL-ALIAS",
            refid = "SOL-ALIAS-REF",
            time = Instant.parse("2026-01-01T00:00:00Z"),
            type = KrakenApiConstants.LEDGER_TYPE_TRANSFER,
            subtype = "spottostaking",
            aclass = "currency",
            asset = Asset.SOL,
            amount = BigDecimal("1.0"),
            fee = BigDecimal("0.0"),
            balance = BigDecimal("1.0"),
            hasAuthoritativeBalance = true,
            hasAuthoritativeFee = true,
        )

        "collapses only numerically equivalent SOL and SOL03 mirror evidence" {
            val sol03 = sol.copy(
                asset = "SOL03",
                amount = BigDecimal("1.00"),
                fee = BigDecimal("0.00"),
                balance = BigDecimal("1.00"),
            )

            LedgerEventAliasCanonicalizer.collapseExactSolAliasMirrors(listOf(sol, sol03)) shouldBe listOf(sol)
        }

        "keeps alias rows visible when any retained evidence field conflicts" {
            val sol03 = sol.copy(asset = "SOL03")
            val conflictingRows = listOf(
                sol03.copy(refid = "other-ref"),
                sol03.copy(time = sol.time.plusNanos(1)),
                sol03.copy(type = KrakenApiConstants.LEDGER_TYPE_STAKING),
                sol03.copy(subtype = "other-subtype"),
                sol03.copy(aclass = "fiat"),
                sol03.copy(amount = BigDecimal("1.1")),
                sol03.copy(fee = BigDecimal("0.1")),
                sol03.copy(balance = BigDecimal("1.1")),
                sol03.copy(hasAuthoritativeBalance = false),
                sol03.copy(hasAuthoritativeFee = false),
                sol03.copy(hasValidFee = false),
                sol03.copy(hasValidAmount = false),
            )

            conflictingRows.forEach { conflictingSol03 ->
                LedgerEventAliasCanonicalizer.collapseExactSolAliasMirrors(listOf(sol, conflictingSol03)) shouldBe
                    listOf(sol, conflictingSol03)
            }
        }
    })
