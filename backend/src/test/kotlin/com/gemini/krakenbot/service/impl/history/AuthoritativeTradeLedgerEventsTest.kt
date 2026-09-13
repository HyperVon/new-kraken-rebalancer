package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.KrakenApiConstants
import com.gemini.krakenbot.model.LedgerEvent
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant

class AuthoritativeTradeLedgerEventsTest :
    StringSpec({
        isolationMode = IsolationMode.InstancePerTest

        val t0 = Instant.parse("2026-01-01T00:00:00Z")
        val spot = AuthoritativeLedgerBalanceValidator.LedgerWalletScope.SPOT
        val staking = AuthoritativeLedgerBalanceValidator.LedgerWalletScope.STAKING

        fun leg(
            ledgerId: String,
            refid: String?,
            asset: String,
            amount: String,
            time: Instant = t0,
            hasAuthoritativeBalance: Boolean = true,
            hasValidFee: Boolean = true,
            hasValidAmount: Boolean = true,
        ) = LedgerEvent(
            ledgerId = ledgerId,
            refid = refid,
            time = time,
            type = KrakenApiConstants.LEDGER_TYPE_TRADE,
            asset = asset,
            amount = BigDecimal(amount),
            hasAuthoritativeBalance = hasAuthoritativeBalance,
            hasValidFee = hasValidFee,
            hasValidAmount = hasValidAmount,
        )

        fun trade(tradeId: String?) = TestFixtures.tradeRecord(
            timestamp = t0,
            pair = Asset.BTC_USD_PAIR,
            side = "buy",
            symbol = Asset.BTC,
            volume = BigDecimal("1.0"),
            usdAmount = BigDecimal("100.00"),
            tradeId = tradeId,
        )

        "a group whose retained trade identity matches is never replayed from ledger rows" {
            val inventory = AuthoritativeTradeLedgerEvents.collect(
                ledgers = listOf(
                    leg("l1", "trade-1", Asset.USD, "-100.00"),
                    leg("l2", "trade-1", Asset.BTC, "0.001", time = t0.plusMillis(500)),
                ),
                trades = listOf(trade("trade-1"), trade(null), trade("  ")),
                resolvedScopes = emptyMap(),
            )

            inventory.replayableLegs shouldBe emptyList()
            inventory.replayableRefIds shouldBe emptySet()
            inventory.incompleteRefIds shouldBe emptySet()
            inventory.contradictoryRefIds shouldBe emptySet()
            inventory.nonSpotRefIds shouldBe emptySet()
        }

        "only non-blank refids are grouped and non-Spot scopes are inventoried separately" {
            val inventory = AuthoritativeTradeLedgerEvents.collect(
                ledgers = listOf(
                    leg("l1", null, Asset.USD, "-100.00"),
                    leg("l2", "  ", Asset.BTC, "0.001"),
                    leg("l3", "staking-1", Asset.USD, "-50.00"),
                    leg("l4", "staking-1", Asset.BTC, "0.0005", time = t0.plusMillis(100)),
                    leg("l5", "spot-1", Asset.USD, "-25.00"),
                    leg("l6", "spot-1", Asset.BTC, "0.00025", time = t0.plusMillis(100)),
                ),
                trades = emptyList(),
                resolvedScopes = mapOf("l3" to staking, "l5" to spot),
            )

            inventory.nonSpotRefIds shouldContainExactlyInAnyOrder setOf("staking-1")
            inventory.replayableRefIds shouldContainExactlyInAnyOrder setOf("spot-1")
            inventory.replayableLegs.map(LedgerEvent::ledgerId) shouldContainExactlyInAnyOrder listOf("l5", "l6")
            inventory.incompleteRefIds shouldBe emptySet()
            inventory.contradictoryRefIds shouldBe emptySet()
        }

        "a single leg whose net movement is provably zero stays replayable" {
            val inventory = AuthoritativeTradeLedgerEvents.collect(
                ledgers = listOf(leg("l1", "zero-1", Asset.BTC, "0.00", hasAuthoritativeBalance = false)),
                trades = emptyList(),
                resolvedScopes = emptyMap(),
            )

            inventory.replayableRefIds shouldContainExactlyInAnyOrder setOf("zero-1")
            inventory.replayableLegs.map(LedgerEvent::ledgerId) shouldContainExactlyInAnyOrder listOf("l1")
        }

        "structurally contradictory groups are refused" {
            val inventory = AuthoritativeTradeLedgerEvents.collect(
                ledgers = listOf(
                    leg("l1", "one-leg", Asset.BTC, "0.001"),
                    leg("l2", "three-legs", Asset.USD, "-100.00"),
                    leg("l3", "three-legs", Asset.BTC, "0.001"),
                    leg("l4", "three-legs", Asset.ETH, "0.002"),
                    leg("l5", "duplicate", Asset.BTC, "-1.0"),
                    leg("l6", "duplicate", Asset.BTC, "0.001"),
                    leg("l7", "two-credits", Asset.USD, "100.00"),
                    leg("l8", "two-credits", Asset.BTC, "0.001"),
                    leg("l9", "two-debits", Asset.USD, "-100.00"),
                    leg("l10", "two-debits", Asset.BTC, "-0.001"),
                    leg("l11", "skewed", Asset.USD, "-100.00"),
                    leg("l12", "skewed", Asset.BTC, "0.001", time = t0.plusSeconds(5)),
                    leg("l13", "zero-counter-leg", Asset.USD, "-100.00"),
                    leg("l14", "zero-counter-leg", Asset.BTC, "0.00"),
                ),
                trades = emptyList(),
                resolvedScopes = emptyMap(),
            )

            inventory.contradictoryRefIds shouldContainExactlyInAnyOrder
                setOf(
                    "one-leg",
                    "three-legs",
                    "duplicate",
                    "two-credits",
                    "two-debits",
                    "skewed",
                    "zero-counter-leg",
                )
            inventory.replayableRefIds shouldBe emptySet()
            inventory.incompleteRefIds shouldBe emptySet()
        }

        "a structurally complete group without authoritative wire fields is incomplete" {
            val inventory = AuthoritativeTradeLedgerEvents.collect(
                ledgers = listOf(
                    leg("l1", "no-balance", Asset.USD, "-100.00", hasAuthoritativeBalance = false),
                    leg("l2", "no-balance", Asset.BTC, "0.001"),
                    leg("l3", "bad-fee", Asset.USD, "-100.00"),
                    leg("l4", "bad-fee", Asset.BTC, "0.001", hasValidFee = false),
                    leg("l5", "bad-amount", Asset.USD, "-100.00"),
                    leg("l6", "bad-amount", Asset.BTC, "0.001", hasValidAmount = false),
                ),
                trades = emptyList(),
                resolvedScopes = emptyMap(),
            )

            inventory.incompleteRefIds shouldContainExactlyInAnyOrder setOf("no-balance", "bad-fee", "bad-amount")
            inventory.replayableRefIds shouldBe emptySet()
            inventory.contradictoryRefIds shouldBe emptySet()
        }

        "a complete in-skew Spot group replays once" {
            val inventory = AuthoritativeTradeLedgerEvents.collect(
                ledgers = listOf(
                    leg("l1", "trade-legs", Asset.USD, "-100.00"),
                    leg("l2", "trade-legs", Asset.BTC, "0.001", time = t0.plusMillis(1000)),
                ),
                trades = emptyList(),
                resolvedScopes = mapOf("l1" to spot, "l2" to spot),
            )

            inventory.replayableRefIds shouldContainExactlyInAnyOrder setOf("trade-legs")
            inventory.replayableLegs.map(LedgerEvent::ledgerId) shouldContainExactlyInAnyOrder listOf("l1", "l2")
        }
    })
