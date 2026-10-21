package com.gemini.krakenbot.service.history

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset

class HistoryApiSeriesBuilderTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    private val digest = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    private val fingerprint = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210"
    private val accountScope = HistorySourceScope(
        mode = HistorySourceMode.ACCOUNT,
        managedScopeFingerprint = fingerprint,
        managedScopeSymbols = listOf("BTC", "USD"),
        accountIdentityDigest = digest,
    )

    init {
        "a boundary around the selected window is clipped to the window" {
            val episodes = listOf(
                episode("A", Instant.parse("2026-03-01T00:00:00Z"), Instant.parse("2026-03-01T00:00:00Z")),
                episode("B", Instant.parse("2026-03-05T00:00:00Z"), Instant.parse("2026-03-05T00:00:00Z")),
            )
            val window = window(
                from = Instant.parse("2026-03-02T00:00:00Z"),
                until = Instant.parse("2026-03-03T00:00:00Z"),
            )

            val boundary = recordedScopeBoundaries(episodes, emptySet(), window).single()

            boundary.fromIso shouldBe "2026-03-02T00:00:00Z"
            boundary.toIso shouldBe "2026-03-02T23:59:59.999999999Z"
        }

        "a boundary that starts after the selected window is not reported" {
            val episodes = listOf(
                episode("A", Instant.parse("2026-03-05T00:00:00Z"), Instant.parse("2026-03-05T00:00:00Z")),
                episode("B", Instant.parse("2026-03-06T00:00:00Z"), Instant.parse("2026-03-06T00:00:00Z")),
            )
            val window = window(
                from = Instant.parse("2026-03-01T00:00:00Z"),
                until = Instant.parse("2026-03-02T00:00:00Z"),
            )

            recordedScopeBoundaries(episodes, emptySet(), window) shouldBe emptyList()
        }

        "a window without a start keeps the boundary where the episodes changed" {
            val episodes = listOf(
                episode("A", Instant.parse("2026-03-01T00:00:00Z"), Instant.parse("2026-03-01T00:00:00Z")),
                episode("B", Instant.parse("2026-03-05T00:00:00Z"), Instant.parse("2026-03-05T00:00:00Z")),
            )
            val window = window(from = null, until = Instant.parse("2026-03-06T00:00:00Z"))

            val boundaries = recordedScopeBoundaries(episodes, emptySet(), window)

            boundaries.single().fromIso shouldBe "2026-03-01T00:00:00Z"
            boundaries.single().toIso shouldBe "2026-03-05T00:00:00Z"
        }

        "episodes of the same scope are one episode" {
            val episodes = listOf(
                episode("A", Instant.parse("2026-03-01T00:00:00Z"), Instant.parse("2026-03-02T00:00:00Z")),
                episode("A", Instant.parse("2026-03-03T00:00:00Z"), Instant.parse("2026-03-04T00:00:00Z")),
            )
            val window = window(
                from = Instant.parse("2026-03-01T00:00:00Z"),
                until = Instant.parse("2026-03-05T00:00:00Z"),
            )

            recordedScopeBoundaries(episodes, emptySet(), window) shouldBe emptyList()
        }

        "a scope without observations reports no series" {
            val series = recordedPortfolioSeries(
                HistoryApiScope(
                    id = "ACCOUNT:other",
                    symbols = listOf("BTC"),
                    earliestIso = null,
                    latestIso = null,
                    current = false,
                ),
                emptyList(),
                emptySet(),
            )

            series.points shouldBe emptyList()
            series.unit shouldBe "USD"
        }

        "a ledger series drops an asset whose events fall outside the window" {
            val rows = listOf(
                ledger(
                    "early",
                    type = "deposit",
                    asset = "ZUSD",
                    amount = "10.00",
                    time = Instant.parse("2026-03-01T00:00:00Z"),
                ),
                ledger(
                    "late",
                    type = "deposit",
                    asset = "XXBT",
                    amount = "1.00",
                    time = Instant.parse("2026-03-05T00:00:00Z"),
                ),
            )

            val series = ledgerQuantitySeries(
                rows = rows,
                signedAmountOf = ::fundingAmountOf,
                from = Instant.parse("2026-03-02T00:00:00Z"),
                until = Instant.parse("2026-03-06T00:00:00Z"),
            )

            series.map { it.key } shouldContainExactly listOf("XXBT")
            series.single().label shouldBe "BTC"
        }

        "a funding amount needs a valid amount and a capital event" {
            fundingAmountOf(ledger("invalid", type = "deposit", asset = "ZUSD", amount = "nope")).shouldBeNull()
            fundingAmountOf(ledger("other", type = "staking", asset = "ZUSD", amount = "1.00")).shouldBeNull()
            fundingAmountOf(ledger("ok", type = "deposit", asset = "ZUSD", amount = "-5.00"))!!
                .shouldBeEqualComparingTo(BigDecimal("5.00"))
        }

        "a reward amount needs a reward row and a valid amount" {
            rewardAmountOf(ledger("plain", type = "deposit", asset = "ZUSD", amount = "1.00")).shouldBeNull()
            rewardAmountOf(ledger("invalid", type = "staking", asset = "ZUSD", amount = "nope")).shouldBeNull()
            rewardAmountOf(ledger("ok", type = "staking", asset = "ZUSD", amount = "2.00"))!!
                .shouldBeEqualComparingTo(BigDecimal("2.00"))
        }

        "only capital events belong to the funding series" {
            ledger("deposit", type = "deposit", asset = "ZUSD", amount = "1.00").isFundingRow() shouldBe true
            ledger("withdrawal", type = "withdrawal", asset = "ZUSD", amount = "1.00").isFundingRow() shouldBe true
            ledger("staking", type = "staking", asset = "ZUSD", amount = "1.00").isFundingRow() shouldBe false
            ledger("none", type = null, asset = "ZUSD", amount = "1.00").isFundingRow() shouldBe false
        }

        "an unusable amount keeps its point incomplete without changing the running total" {
            val rows = listOf(
                ledger(
                    "good",
                    type = "deposit",
                    asset = "ZUSD",
                    amount = "10.00",
                    time = Instant.parse("2026-03-01T00:00:00Z"),
                ),
                ledger(
                    "bad",
                    type = "deposit",
                    asset = "ZUSD",
                    amount = "nope",
                    time = Instant.parse("2026-03-02T00:00:00Z"),
                ),
            )

            val series = ledgerQuantitySeries(rows, ::fundingAmountOf)

            val points = series.single().points
            points.map { it.complete } shouldContainExactly listOf(true, false)
            points.first().v!!.shouldBeEqualComparingTo(BigDecimal("10.00"))
            points.last().v.shouldBeNull()
        }
    }

    private fun window(from: Instant?, until: Instant) = HistoryApiWindow(
        preset = "all",
        zone = ZoneOffset.UTC,
        selectedFrom = from,
        selectedUntil = until,
        from = from,
        until = until,
        now = until,
        baselineFrom = from,
    )

    private fun episode(scopeId: String, from: Instant, until: Instant) = HistoryRecordedEpisode(
        scopeId = scopeId,
        from = from,
        until = until,
        observations = emptyList(),
    )

    private fun ledger(
        id: String,
        type: String?,
        asset: String?,
        amount: String?,
        time: Instant = Instant.parse("2026-03-01T00:00:00Z"),
    ) = HistoryLedger(
        accountIdentityDigest = digest,
        ledgerId = id,
        refid = null,
        time = time,
        rawTime = time.toString(),
        type = type,
        subtype = null,
        assetClass = "currency",
        asset = asset,
        amount = amount,
        fee = "0",
        balance = null,
        rawRecordJson = null,
        hasValidTime = true,
        hasValidAmount = amount?.toBigDecimalOrNull() != null,
        hasValidFee = true,
        hasValidBalance = false,
        isValidRecord = true,
    )
}
