package com.gemini.krakenbot.service.history

import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.service.impl.KrakenPublicApiException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class HistoryDailyValuationTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    private val accountDigest = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    private val date = LocalDate.parse("2026-03-31")
    private val dayEnd = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()
    private val builder = HistoryDailyBalanceBuilder()

    init {
        "replays fee-adjusted USD cash and excludes every equity-class share unit" {
            val checkpoints = listOf(
                checkpoint("cash-deposit", "2026-03-31T09:00:00Z", "100", "0.5", "99.5", "99.5"),
                checkpoint("equity-settlement", "2026-03-31T11:00:00Z", "-9.5", "0.1", "89.9", "89.9"),
                checkpoint("cash-dividend", "2026-03-31T16:00:00Z", "3", "0", "92.9", "92.9"),
                checkpoint(
                    "equity-share",
                    "2026-03-31T16:00:00Z",
                    "1",
                    "0",
                    "1",
                    "1",
                    rawAssetClass = "equity",
                    rawAssetKey = "AAPL",
                ),
                checkpoint(
                    "unidentified-equity-share",
                    "2026-03-31T16:00:00Z",
                    "1",
                    "0",
                    "1",
                    "1",
                    rawAssetClass = "equity",
                    rawAssetKey = null,
                ),
            )

            val balances = build(checkpoints, coverage = coverage(dayEnd))

            balances.size shouldBe 1
            balances.single().rawAssetKey shouldBe "ZUSD"
            checkNotNull(balances.single().quantity) shouldBeEqualComparingTo BigDecimal("92.9")
            balances.single().status shouldBe HistoryDailyBalanceStatus.VALID
            HistoryDailyBalanceBuilder.isIncludedAccountBucket("equity", "AAPL") shouldBe false
            HistoryDailyBalanceBuilder.isIncludedAccountBucket("equity", "TSLA") shouldBe false
            HistoryDailyBalanceBuilder.isIncludedAccountBucket("currency", "ZUSD") shouldBe true
        }

        "blocks canonical assets classified as both currency and equity" {
            val checkpoints = listOf(
                checkpoint("crypto-checkpoint", "2026-03-31T09:00:00Z", "1", "0", "1", "1", rawAssetKey = "XETH"),
                checkpoint(
                    "equity-checkpoint",
                    "2026-03-31T10:00:00Z",
                    "1",
                    "0",
                    "1",
                    "1",
                    rawAssetClass = "equity",
                    rawAssetKey = "ETH",
                ),
            )

            val balances = build(checkpoints, coverage(dayEnd))

            balances.map { it.status }.distinct() shouldBe listOf(HistoryDailyBalanceStatus.INVALID_CHECKPOINT)
            balances.all { it.quantity == null } shouldBe true
            balances.first { it.rawAssetKey == "XETH" }.reason shouldBe
                "Ledger checkpoints report conflicting currency and equity classes for the same asset."
        }

        "does not apply future asset class conflicts to earlier daily balances" {
            val checkpoints = listOf(
                checkpoint("crypto-checkpoint", "2026-03-31T09:00:00Z", "1", "0", "1", "1", rawAssetKey = "XETH"),
                checkpoint(
                    "equity-checkpoint",
                    "2026-04-01T09:00:00Z",
                    "1",
                    "0",
                    "1",
                    "1",
                    rawAssetClass = "equity",
                    rawAssetKey = "ETH",
                ),
            )

            val balances = buildAcross(date, date.plusDays(1), checkpoints)

            val beforeConflict = balances.first { it.dayLabel == date }
            beforeConflict.status shouldBe HistoryDailyBalanceStatus.VALID
            beforeConflict.quantity shouldBe BigDecimal("1")
            val afterConflict = balances.first { it.dayLabel == date.plusDays(1) }
            afterConflict.status shouldBe HistoryDailyBalanceStatus.INVALID_CHECKPOINT
            afterConflict.quantity shouldBe null
        }

        "orders same-time ledger transitions only when one balance path reconciles" {
            val checkpoints = listOf(
                checkpoint("second", "2026-03-31T12:00:00Z", "5", "0", "15", "15"),
                checkpoint("first", "2026-03-31T12:00:00Z", "10", "0", "10", "10"),
            )

            val balance = build(checkpoints, coverage(dayEnd)).single()

            checkNotNull(balance.quantity) shouldBeEqualComparingTo BigDecimal("15")
            balance.status shouldBe HistoryDailyBalanceStatus.VALID
            balance.sourceLedgerId shouldBe "second"
        }

        "blocks same-time transitions when multiple balance paths reconcile" {
            val checkpoints = listOf(
                checkpoint("opening", "2026-03-30T12:00:00Z", "10", "0", "10", "10"),
                checkpoint("zero-a", "2026-03-31T12:00:00Z", "0", "0", "10", "10"),
                checkpoint("zero-b", "2026-03-31T12:00:00Z", "0", "0", "10", "10"),
            )

            val balance = build(checkpoints, coverage(dayEnd)).single()

            balance.quantity shouldBe null
            balance.status shouldBe HistoryDailyBalanceStatus.AMBIGUOUS_ORDER
        }

        "uses balance continuity instead of ledger id order at a same-time zero boundary" {
            val firstDay = LocalDate.parse("2025-12-05")
            val checkpoints = listOf(
                checkpoint("opening", "2025-12-04T12:00:00Z", "10", "0", "10", "10", rawAssetKey = "XZEC"),
                checkpoint("a-zero", "2025-12-05T12:00:00Z", "-15", "0", "0", "0", rawAssetKey = "XZEC"),
                checkpoint("z-positive", "2025-12-05T12:00:00Z", "5", "0", "15", "15", rawAssetKey = "XZEC"),
            )

            val balances = builder.build(
                accountIdentityDigest = accountDigest,
                startDay = firstDay,
                endDay = firstDay.plusDays(1),
                checkpoints = checkpoints,
                coverage = listOf(
                    HistorySyncCoverageWindow(
                        0,
                        firstDay.plusDays(2).atStartOfDay(ZoneOffset.UTC).toEpochSecond() - 1,
                    ),
                ),
                sourceRevision = 1,
                generationId = "zero-boundary",
            ).filter { it.rawAssetKey == "XZEC" }

            balances.map { it.quantity } shouldContainExactly listOf(BigDecimal.ZERO, BigDecimal.ZERO)
            balances.all { it.status == HistoryDailyBalanceStatus.VALID } shouldBe true
        }

        "does not create zero without coverage or bridge an interior coverage gap" {
            val empty = build(emptyList(), emptyList()).single()
            empty.quantity shouldBe null
            empty.status shouldBe HistoryDailyBalanceStatus.NO_OPENING_ANCHOR

            val checkpoint = checkpoint("cash", "2026-03-31T10:00:00Z", "25", "0", "25", "25")
            val gap = build(
                checkpoints = listOf(checkpoint),
                coverage = listOf(
                    HistorySyncCoverageWindow(0, checkpoint.observedAt!!.epochSecond),
                    HistorySyncCoverageWindow(dayEnd.epochSecond - 600, dayEnd.epochSecond - 1),
                ),
            ).single()
            gap.quantity shouldBe null
            gap.status shouldBe HistoryDailyBalanceStatus.COVERAGE_GAP
        }

        "blocks unidentified or unsupported account-class evidence" {
            val unsupported = checkpoint(
                "margin-position",
                "2026-03-31T10:00:00Z",
                "1",
                "0",
                "1",
                "1",
                rawAssetClass = "margin",
                rawAssetKey = "BTC",
            )

            val result = build(listOf(unsupported), coverage(dayEnd)).single()

            result.rawAssetKey shouldBe HistoryDailyBalanceBuilder.UNKNOWN_ASSET_KEY
            result.quantity shouldBe null
            result.status shouldBe HistoryDailyBalanceStatus.INVALID_CHECKPOINT
        }

        "blocks negative spot balances from daily holdings and totals" {
            val negativeCheckpoint = checkpoint(
                "negative-cash",
                "2026-03-31T10:00:00Z",
                "-1",
                "0",
                "-1",
                "-1",
            )

            val balance = build(listOf(negativeCheckpoint), coverage(dayEnd)).single()

            balance.quantity shouldBe null
            balance.status shouldBe HistoryDailyBalanceStatus.INVALID_CHECKPOINT
            balance.reason shouldBe "A ledger balance checkpoint has a negative quantity."

            val value = HistoryDailyValueBuilder().build(
                listOf(balance),
                listOf(dailyPrice("USD", BigDecimal.ONE)),
            ).single()
            value.totalUsd shouldBe null
            value.status shouldBe HistoryDailyValueStatus.NO_BALANCE

            val legacyNegative = dailyBalance("USD", BigDecimal("-1"))
            val guardedValue = HistoryDailyValueBuilder().build(listOf(legacyNegative), emptyList()).single()
            guardedValue.totalUsd shouldBe null
            guardedValue.status shouldBe HistoryDailyValueStatus.NO_BALANCE
            guardedValue.assets.single().quantity shouldBe null
        }

        "retains missing asset identity and missing ledger times as blocking evidence" {
            val unidentified = HistoryBalanceCheckpoint(
                accountIdentityDigest = accountDigest,
                ledgerId = "no-asset",
                observedAt = Instant.parse("2026-03-31T10:00:00Z"),
                rawAssetClass = "currency",
                rawAssetKey = null,
                rawAmount = "1",
                rawFee = "0",
                rawBalance = "1",
                quantity = BigDecimal.ONE,
                status = HistoryBalanceStatus.VALID,
            )
            val unknown = build(listOf(unidentified), coverage(dayEnd)).single()
            unknown.rawAssetKey shouldBe HistoryDailyBalanceBuilder.UNKNOWN_ASSET_KEY
            unknown.rawAssetClass shouldBe null
            unknown.quantity shouldBe null
            unknown.status shouldBe HistoryDailyBalanceStatus.INVALID_CHECKPOINT
            unknown.reason shouldBe
                "A ledger checkpoint has no asset identity, so the full account total is incomplete."

            val missingTime = HistoryBalanceCheckpoint(
                accountIdentityDigest = accountDigest,
                ledgerId = "no-time",
                observedAt = null,
                rawAssetClass = "currency",
                rawAssetKey = "ZUSD",
                rawAmount = "1",
                rawFee = "0",
                rawBalance = "1",
                quantity = BigDecimal.ONE,
                status = HistoryBalanceStatus.VALID,
            )
            val untimed = build(listOf(missingTime), coverage(dayEnd)).single()
            untimed.quantity shouldBe null
            untimed.status shouldBe HistoryDailyBalanceStatus.INVALID_CHECKPOINT
            untimed.reason shouldBe "A ledger checkpoint has no valid timestamp."
        }

        "does not let a later timed balance checkpoint repair an untimed ledger event" {
            val missingTime = HistoryBalanceCheckpoint(
                accountIdentityDigest = accountDigest,
                ledgerId = "no-time",
                observedAt = null,
                rawAssetClass = "currency",
                rawAssetKey = "ZUSD",
                rawAmount = "1",
                rawFee = "0",
                rawBalance = "1",
                quantity = BigDecimal.ONE,
                status = HistoryBalanceStatus.VALID,
            )
            val laterTimedCheckpoint = checkpoint(
                "later-absolute-balance",
                "2026-03-31T18:00:00Z",
                "5",
                "0",
                "5",
                "5",
            )

            val balance = build(listOf(missingTime, laterTimedCheckpoint), coverage(dayEnd)).single()

            balance.quantity shouldBe null
            balance.status shouldBe HistoryDailyBalanceStatus.INVALID_CHECKPOINT
            balance.reason shouldBe "A ledger checkpoint has no valid timestamp."
        }

        "daily totals are exact only when every nonzero asset has a same-day price" {
            val balances = listOf(
                dailyBalance("ZUSD", BigDecimal("10")),
                dailyBalance("XBT", BigDecimal("2")),
                dailyBalance("ETH", BigDecimal.ZERO),
            )
            val prices = listOf(
                dailyPrice("USD", BigDecimal.ONE),
                dailyPrice("BTC", BigDecimal("5")),
            )

            val complete = HistoryDailyValueBuilder().build(balances, prices).single()
            complete.status shouldBe HistoryDailyValueStatus.COMPLETE
            checkNotNull(complete.totalUsd) shouldBeEqualComparingTo BigDecimal("20")
            complete.assets.first { it.rawAssetKey == "ETH" }.priceUsd shouldBe null
            checkNotNull(complete.assets.first { it.rawAssetKey == "ETH" }.valueUsd) shouldBeEqualComparingTo
                BigDecimal.ZERO

            val missing = HistoryDailyValueBuilder().build(balances, prices - prices.last()).single()
            missing.totalUsd shouldBe null
            missing.status shouldBe HistoryDailyValueStatus.NO_PRICE

            val zeroPrice = dailyPrice("BTC", BigDecimal.ZERO)
            val invalid = HistoryDailyValueBuilder().build(
                listOf(dailyBalance("BTC", BigDecimal.ONE)),
                listOf(zeroPrice),
            ).single()
            invalid.totalUsd shouldBe null
            invalid.status shouldBe HistoryDailyValueStatus.NO_PRICE
        }

        "canonicalizes cached price aliases without merging raw balance partitions" {
            val balances = listOf(
                dailyBalance("ZUSD", BigDecimal("10")),
                dailyBalance("USD", BigDecimal("20")),
                dailyBalance("XXBT", BigDecimal("2")),
                dailyBalance("BTC", BigDecimal("3")),
                dailyBalance("DOT.S", BigDecimal("4")),
            )
            val prices = listOf(
                dailyPrice("ZUSD", BigDecimal.ONE).copy(source = "alias-unit"),
                dailyPrice("USD", BigDecimal.ONE).copy(source = "canonical-unit"),
                dailyPrice("XXBT", BigDecimal("5")).copy(source = "alias-market"),
                dailyPrice("BTC", BigDecimal("5")).copy(source = "canonical-market"),
                dailyPrice("DOT", BigDecimal("2")).copy(source = "earn-market"),
            )

            val result = HistoryDailyValueBuilder().build(balances, prices).single()

            result.status shouldBe HistoryDailyValueStatus.COMPLETE
            checkNotNull(result.totalUsd) shouldBeEqualComparingTo BigDecimal("63")
            result.assets.map { it.rawAssetKey } shouldContainExactly listOf("ZUSD", "USD", "XXBT", "BTC", "DOT.S")
            result.assets.map { it.priceUsd } shouldContainExactly listOf(
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal("5"),
                BigDecimal("5"),
                BigDecimal("2"),
            )
            result.assets.filter { it.rawAssetKey in setOf("ZUSD", "USD") }.all {
                it.priceUsd == BigDecimal.ONE && it.valueUsd == it.quantity
            } shouldBe true
            result.assets.filter { it.rawAssetKey in setOf("XXBT", "BTC") }.all {
                it.priceSource == "canonical-market" && it.pricePair == "BTCUSD"
            } shouldBe true
            result.assets.single { it.rawAssetKey == "DOT.S" }.valueUsd shouldBe BigDecimal("8")
        }

        "chooses identical canonical price aliases deterministically and orders identities" {
            val prices = listOf(
                dailyPrice("ZUSD", BigDecimal.ONE),
                dailyPrice("XBT", BigDecimal("5")).copy(pair = "XXBTZUSD"),
                dailyPrice("BTC", BigDecimal("5")).copy(pair = "XXBTZUSD"),
                dailyPrice("BTC", BigDecimal("5")).copy(pair = "XBTUSD"),
                dailyPrice("XXBT", BigDecimal("5")).copy(pair = "XXBTZUSD"),
            )

            val selected = HistoryDailyPriceIndex.from(prices.reversed()).selectedPrices

            selected.map { it.rawAssetKey } shouldContainExactly listOf("BTC", "ZUSD")
            selected.first().pair shouldBe "XBTUSD"
            selected.first().closeUsd shouldBe BigDecimal("5")
            selected.last().closeUsd shouldBe BigDecimal.ONE
        }

        "orders equivalent price provenance legs deterministically" {
            val canonicalLeg = HistoryDailyPriceLeg("BTCUSD", BigDecimal("5"), "USD")
            val aliasLeg = HistoryDailyPriceLeg("XBTUSD", BigDecimal("5"), "USD")
            val prices = listOf(
                dailyPrice("BTC", BigDecimal("5")).copy(
                    pair = "XBTUSD",
                    source = "same-source",
                    legs = listOf(aliasLeg),
                ),
                dailyPrice("BTC", BigDecimal("5")).copy(
                    pair = "XBTUSD",
                    source = "same-source",
                    legs = listOf(canonicalLeg),
                ),
            )

            val forward = HistoryDailyPriceIndex.from(prices).selectedPrices.single()
            val reversed = HistoryDailyPriceIndex.from(prices.reversed()).selectedPrices.single()

            forward.legs shouldBe listOf(canonicalLeg)
            reversed.legs shouldBe listOf(canonicalLeg)
        }

        "leaves conflicting alias-only prices unavailable and uses one canonical resolver price" {
            val balance = dailyBalance("XXBT", BigDecimal("2"))
            val aliasPrices = listOf(
                dailyPrice("XXBT", BigDecimal("10")),
                dailyPrice("XBT", BigDecimal("11")),
            )

            val ambiguous = HistoryDailyValueBuilder().build(listOf(balance), aliasPrices).single()

            ambiguous.status shouldBe HistoryDailyValueStatus.NO_PRICE
            ambiguous.totalUsd shouldBe null
            ambiguous.assets.single().status shouldBe HistoryDailyValueStatus.NO_PRICE
            ambiguous.assets.single().reason shouldBe
                "Conflicting completed Kraken USD closes are available for this holding on this UTC date."

            val resolved = HistoryDailyValueBuilder().build(
                listOf(balance),
                aliasPrices + dailyPrice("BTC", BigDecimal("12")).copy(source = "KRAKEN_COMPLETED_DAILY_OHLC"),
            ).single()

            resolved.status shouldBe HistoryDailyValueStatus.COMPLETE
            checkNotNull(resolved.totalUsd) shouldBeEqualComparingTo BigDecimal("24")
            resolved.assets.single().rawAssetKey shouldBe "XXBT"
            resolved.assets.single().priceUsd shouldBe BigDecimal("12")
            resolved.assets.single().priceSource shouldBe "KRAKEN_COMPLETED_DAILY_OHLC"
        }

        "keeps invalid and ambiguous balance states visible without publishing partial totals" {
            val balances = listOf(
                dailyBalance("XBT", BigDecimal.ONE),
                dailyBalance("ETH", BigDecimal.ZERO).copy(
                    quantity = null,
                    status = HistoryDailyBalanceStatus.NO_OPENING_ANCHOR,
                    reason = "No opening balance anchor.",
                ),
                dailyBalance("XRP", BigDecimal.ZERO).copy(
                    quantity = null,
                    status = HistoryDailyBalanceStatus.AMBIGUOUS_ORDER,
                    reason = "Same-time order is ambiguous.",
                ),
            )
            val result = HistoryDailyValueBuilder().build(balances, emptyList()).single()

            result.totalUsd shouldBe null
            result.status shouldBe HistoryDailyValueStatus.AMBIGUOUS_BALANCE
            result.assets.map { it.status } shouldContainExactly listOf(
                HistoryDailyValueStatus.NO_PRICE,
                HistoryDailyValueStatus.NO_BALANCE,
                HistoryDailyValueStatus.AMBIGUOUS_BALANCE,
            )
            result.assets.map { it.quantity } shouldContainExactly listOf(BigDecimal.ONE, null, null)
        }

        "rejects invalid balance ranges and mixed valuation provenance" {
            shouldThrow<IllegalArgumentException> {
                builder.build(
                    accountIdentityDigest = accountDigest,
                    startDay = date.plusDays(1),
                    endDay = date,
                    checkpoints = emptyList(),
                    coverage = emptyList(),
                    sourceRevision = 1,
                    generationId = "invalid-range",
                )
            }
            shouldThrow<IllegalArgumentException> {
                builder.build(
                    accountIdentityDigest = accountDigest,
                    startDay = date,
                    endDay = date,
                    checkpoints = emptyList(),
                    coverage = emptyList(),
                    sourceRevision = -1,
                    generationId = "invalid-revision",
                )
            }

            val balance = dailyBalance("USD", BigDecimal.ONE)
            val values = HistoryDailyValueBuilder()
            shouldThrow<IllegalArgumentException> {
                values.build(listOf(balance, balance.copy(accountIdentityDigest = "another-account")), emptyList())
            }
            shouldThrow<IllegalArgumentException> {
                values.build(listOf(balance, balance.copy(sourceAssetScope = "another-scope")), emptyList())
            }
            shouldThrow<IllegalArgumentException> {
                values.build(listOf(balance, balance.copy(generationId = "another-generation")), emptyList())
            }
        }

        "recovers after an invalid checkpoint only from a later validated absolute balance" {
            val invalid = HistoryBalanceCheckpoint(
                accountIdentityDigest = accountDigest,
                ledgerId = "bad",
                observedAt = Instant.parse("2026-03-31T09:00:00Z"),
                rawAssetClass = "currency",
                rawAssetKey = "ZUSD",
                rawAmount = "not-a-number",
                rawFee = "0",
                rawBalance = "bad",
                quantity = null,
                status = HistoryBalanceStatus.INVALID,
                reason = "Bad source evidence.",
            )
            val anchor = checkpoint("reanchor", "2026-03-31T10:00:00Z", "3", "0", "3", "3")
            val recovered = build(listOf(invalid, anchor), coverage(dayEnd)).single()

            checkNotNull(recovered.quantity) shouldBeEqualComparingTo BigDecimal("3")
            recovered.status shouldBe HistoryDailyBalanceStatus.VALID
            recovered.sourceLedgerId shouldBe "reanchor"

            val ambiguous = build(
                listOf(
                    checkpoint("one", "2026-03-31T11:00:00Z", "1", "0", "10", "10"),
                    checkpoint("two", "2026-03-31T11:00:00Z", "1", "0", "20", "20"),
                ),
                coverage(dayEnd),
            ).single()
            ambiguous.quantity shouldBe null
            ambiguous.status shouldBe HistoryDailyBalanceStatus.AMBIGUOUS_ORDER
        }

        "uses verified full-day coverage for an empty mark but never peeks at later checkpoints" {
            val afterMark = checkpoint("future", "2026-04-01T00:00:00Z", "5", "0", "5", "5")

            val empty = build(listOf(afterMark), coverage(dayEnd)).single()

            checkNotNull(empty.quantity) shouldBeEqualComparingTo BigDecimal.ZERO
            empty.status shouldBe HistoryDailyBalanceStatus.VALID
            empty.sourceLedgerId shouldBe null
        }

        "future unsupported evidence does not invalidate independently established earlier days" {
            val start = LocalDate.parse("2026-07-01")
            val end = LocalDate.parse("2026-10-16")
            val balances = buildAcross(
                start,
                end,
                listOf(
                    checkpoint("usd-anchor", "2026-06-30T12:00:00Z", "25", "0", "25", "25"),
                    checkpoint(
                        "future-tokenized",
                        "2026-10-15T08:00:00Z",
                        "1",
                        "0",
                        "1",
                        "1",
                        rawAssetClass = "tokenized_asset",
                        rawAssetKey = "TOKENIZED_SHARE",
                    ),
                ),
            ).associateBy { it.dayLabel }

            balances.getValue(LocalDate.parse("2026-10-14")).status shouldBe HistoryDailyBalanceStatus.VALID
            balances.getValue(LocalDate.parse("2026-10-15")).status shouldBe
                HistoryDailyBalanceStatus.INVALID_CHECKPOINT
            balances.getValue(LocalDate.parse("2026-10-16")).reason shouldBe
                "Ledger history contains an unsupported wallet or asset class for account totals."
        }

        "unsupported evidence invalidates known balance quantities for the same day" {
            val unsupported = checkpoint(
                "unsupported-tokenized",
                "2026-03-31T11:00:00Z",
                "1",
                "0",
                "1",
                "1",
                rawAssetClass = "tokenized_asset",
                rawAssetKey = "TOKENIZED_SHARE",
            )
            val balances = build(
                listOf(
                    checkpoint("usd-anchor", "2026-03-31T10:00:00Z", "25", "0", "25", "25"),
                    unsupported,
                ),
                coverage(dayEnd),
            )

            balances.size shouldBe 2
            balances.forEach { balance ->
                balance.status shouldBe HistoryDailyBalanceStatus.INVALID_CHECKPOINT
                balance.quantity shouldBe null
            }
        }

        "unknown evidence before the period and untimed unknown evidence remain fail closed" {
            val start = LocalDate.parse("2026-07-01")
            val end = LocalDate.parse("2026-07-03")
            val before = checkpoint(
                "old-unsupported",
                "2026-06-30T12:00:00Z",
                "1",
                "0",
                "1",
                "1",
                rawAssetClass = "tokenized_asset",
                rawAssetKey = "OLD_SHARE",
            )
            val untimed = checkpoint(
                "untimed-unknown",
                null,
                "1",
                "0",
                "1",
                "1",
                rawAssetClass = "currency",
                rawAssetKey = null,
            )

            val oldEvidence = buildAcross(
                start,
                end,
                listOf(
                    before,
                    checkpoint(
                        "usd-anchor",
                        "2026-06-30T13:00:00Z",
                        "25",
                        "0",
                        "25",
                        "25",
                    ),
                ),
            )
            val untimedEvidence = buildAcross(
                start,
                end,
                listOf(
                    checkpoint(
                        "usd-anchor",
                        "2026-06-30T13:00:00Z",
                        "25",
                        "0",
                        "25",
                        "25",
                    ),
                    untimed,
                ),
            )

            oldEvidence.map { it.status }.distinct() shouldBe listOf(HistoryDailyBalanceStatus.INVALID_CHECKPOINT)
            untimedEvidence.map { it.status }.distinct() shouldBe listOf(HistoryDailyBalanceStatus.INVALID_CHECKPOINT)
        }

        "later absolute anchor restores a known stream after an invalid checkpoint" {
            val start = LocalDate.parse("2026-07-01")
            val end = LocalDate.parse("2026-07-05")
            val balances = buildAcross(
                start,
                end,
                listOf(
                    checkpoint("usd-anchor", "2026-06-30T12:00:00Z", "25", "0", "25", "25"),
                    checkpoint(
                        "bad-row",
                        "2026-07-02T12:00:00Z",
                        "1",
                        "0",
                        "26",
                        "26",
                        status = HistoryBalanceStatus.INVALID,
                    ),
                    checkpoint("later-anchor", "2026-07-04T12:00:00Z", "30", "0", "30", "30"),
                ),
            ).associateBy { it.dayLabel }

            balances.getValue(LocalDate.parse("2026-07-02")).status shouldBe
                HistoryDailyBalanceStatus.INVALID_CHECKPOINT
            balances.getValue(LocalDate.parse("2026-07-04")).status shouldBe HistoryDailyBalanceStatus.VALID
            checkNotNull(balances.getValue(LocalDate.parse("2026-07-04")).quantity)
                .shouldBeEqualComparingTo(BigDecimal("30"))
        }

        "resolves only completed daily candles and leaves USD at par without a request" {
            val service = mockk<KrakenService>(relaxed = true)
            val now = Instant.parse("2026-04-02T12:00:00Z")
            val jan2Open = LocalDate.parse("2026-04-01").atStartOfDay(ZoneOffset.UTC).toEpochSecond()
            val jan3Open = LocalDate.parse("2026-04-02").atStartOfDay(ZoneOffset.UTC).toEpochSecond()
            coEvery { service.getOHLC("XBTUSD", 1_440, any()) } returns listOf(
                jan2Open to BigDecimal("60000"),
                jan3Open to BigDecimal("61000"),
            )
            val resolver = HistoryDailyPriceResolver(service, nowProvider = { now })

            val prices = resolver.resolve(
                accountDigest,
                setOf(
                    HistoryDailyPriceRequest("XBT", LocalDate.parse("2026-04-01")),
                    HistoryDailyPriceRequest("XBT", LocalDate.parse("2026-04-02")),
                    HistoryDailyPriceRequest("ZUSD", LocalDate.parse("2026-04-01")),
                ),
            )

            prices.sortedBy { it.rawAssetKey }.map { it.rawAssetKey to it.closeUsd } shouldContainExactly listOf(
                "BTC" to BigDecimal("60000"),
                "USD" to BigDecimal.ONE,
            )
        }

        "retains both same-day conversion legs for a BTC-quoted asset" {
            val service = mockk<KrakenService>()
            val open = date.atStartOfDay(ZoneOffset.UTC).toEpochSecond()
            val now = dayEnd.plusSeconds(60)
            coEvery { service.getOHLC("ETHUSD", 1_440, any()) } returns emptyList()
            coEvery { service.getOHLC("ETHXBT", 1_440, any()) } returns listOf(open to BigDecimal("0.05"))
            coEvery { service.getOHLC("XBTUSD", 1_440, any()) } returns listOf(open to BigDecimal("60000"))
            val resolver = HistoryDailyPriceResolver(service, nowProvider = { now })

            val price = resolver.resolve(
                accountDigest,
                setOf(HistoryDailyPriceRequest("ETH", date)),
            ).single()

            price.closeUsd shouldBeEqualComparingTo BigDecimal("3000.00")
            price.legs.map { it.pair } shouldContainExactly listOf("ETHXBT", "XBTUSD")
            price.legs.map { it.quoteAsset } shouldContainExactly listOf("BTC", "USD")
        }

        "falls back to completed BTC pricing when Kraken explicitly rejects the direct USD market" {
            val service = mockk<KrakenService>(relaxed = true)
            val open = date.atStartOfDay(ZoneOffset.UTC).toEpochSecond()
            val now = dayEnd.plusSeconds(60)
            coEvery { service.getOHLC("ETHUSD", 1_440, any()) } throws unknownPair("ETHUSD")
            coEvery { service.getOHLC("ETHXBT", 1_440, any()) } returns listOf(open to BigDecimal("0.05"))
            coEvery { service.getOHLC("XBTUSD", 1_440, any()) } returns listOf(open to BigDecimal("60000"))
            val resolver = HistoryDailyPriceResolver(service, nowProvider = { now })

            val price = resolver.resolve(accountDigest, setOf(HistoryDailyPriceRequest("ETH", date))).single()

            price.closeUsd shouldBeEqualComparingTo BigDecimal("3000.00")
            price.pair shouldBe "ETHXBT × XBTUSD"
            price.candleOpen shouldBe Instant.ofEpochSecond(open)
            price.legs.map { it.pair } shouldContainExactly listOf("ETHXBT", "XBTUSD")
            coVerify(exactly = 1) { service.getOHLC("ETHUSD", 1_440, any()) }
        }

        "falls back to completed EUR pricing when Kraken explicitly rejects the direct and BTC markets" {
            val service = mockk<KrakenService>(relaxed = true)
            val open = date.atStartOfDay(ZoneOffset.UTC).toEpochSecond()
            val now = dayEnd.plusSeconds(60)
            coEvery { service.getOHLC("ETHUSD", 1_440, any()) } throws unknownPair("ETHUSD")
            coEvery { service.getOHLC("ETHXBT", 1_440, any()) } throws unknownPair("ETHXBT")
            coEvery { service.getOHLC("ETHXXBT", 1_440, any()) } throws unknownPair("ETHXXBT")
            coEvery { service.getOHLC("ETHZEUR", 1_440, any()) } returns listOf(open to BigDecimal("0.50"))
            coEvery { service.getOHLC("EURUSD", 1_440, any()) } returns listOf(open to BigDecimal("1.20"))
            val resolver = HistoryDailyPriceResolver(service, nowProvider = { now })

            val price = resolver.resolve(accountDigest, setOf(HistoryDailyPriceRequest("ETH", date))).single()

            price.closeUsd shouldBeEqualComparingTo BigDecimal("0.6000")
            price.pair shouldBe "ETHZEUR × EURUSD"
            price.legs.map { it.pair } shouldContainExactly listOf("ETHZEUR", "EURUSD")
        }

        "does not treat transient market failures or rate limits as evidence of an unsupported pair" {
            val transient = mockk<KrakenService>(relaxed = true)
            val networkFailure = java.io.IOException("network unavailable")
            coEvery { transient.getOHLC("ETHUSD", 1_440, any()) } throws networkFailure
            shouldThrow<java.io.IOException> {
                HistoryDailyPriceResolver(transient, nowProvider = { dayEnd.plusSeconds(60) })
                    .resolve(accountDigest, setOf(HistoryDailyPriceRequest("ETH", date)))
            } shouldBe networkFailure
            coVerify(exactly = 0) { transient.getOHLC("ETHXBT", 1_440, any()) }

            val rateLimited = mockk<KrakenService>(relaxed = true)
            coEvery { rateLimited.getOHLC("ETHUSD", 1_440, any()) } throws KrakenPublicApiException(
                endpoint = "OHLC",
                errors = listOf("EAPI:Rate limit exceeded"),
                message = "Kraken Public API Error: [EAPI:Rate limit exceeded]",
            )
            shouldThrow<KrakenPublicApiException> {
                HistoryDailyPriceResolver(rateLimited, nowProvider = { dayEnd.plusSeconds(60) })
                    .resolve(accountDigest, setOf(HistoryDailyPriceRequest("ETH", date)))
            }.errors shouldBe listOf("EAPI:Rate limit exceeded")
            coVerify(exactly = 0) { rateLimited.getOHLC("ETHXBT", 1_440, any()) }
        }

        "uses completed EUR conversion legs when a BTC close cannot be converted on that day" {
            val service = mockk<KrakenService>(relaxed = true)
            val open = date.atStartOfDay(ZoneOffset.UTC).toEpochSecond()
            val now = dayEnd.plusSeconds(60)
            coEvery { service.getOHLC("ETHUSD", 1_440, any()) } returns emptyList()
            coEvery { service.getOHLC("ETHXBT", 1_440, any()) } returns emptyList()
            coEvery { service.getOHLC("ETHXXBT", 1_440, any()) } returns listOf(open to BigDecimal("0.08"))
            coEvery { service.getOHLC("XBTUSD", 1_440, any()) } returns emptyList()
            coEvery { service.getOHLC("ETHZEUR", 1_440, any()) } returns listOf(open to BigDecimal("0.50"))
            coEvery { service.getOHLC("EURUSD", 1_440, any()) } returns listOf(open to BigDecimal("1.20"))
            val resolver = HistoryDailyPriceResolver(service, nowProvider = { now })

            val price = resolver.resolve(accountDigest, setOf(HistoryDailyPriceRequest("ETH", date))).single()

            price.closeUsd shouldBeEqualComparingTo BigDecimal("0.6000")
            price.pair shouldBe "ETHZEUR × EURUSD"
            price.legs.map { it.pair } shouldContainExactly listOf("ETHZEUR", "EURUSD")
        }

        "leaves a price gap when neither USD conversion leg is available" {
            val service = mockk<KrakenService>(relaxed = true)
            val open = date.atStartOfDay(ZoneOffset.UTC).toEpochSecond()
            val now = dayEnd.plusSeconds(60)
            coEvery { service.getOHLC("ETHUSD", 1_440, any()) } returns emptyList()
            coEvery { service.getOHLC("ETHXBT", 1_440, any()) } returns listOf(open to BigDecimal("0.05"))
            coEvery { service.getOHLC("XBTUSD", 1_440, any()) } returns emptyList()
            coEvery { service.getOHLC("ETHZEUR", 1_440, any()) } returns listOf(open to BigDecimal("2"))
            coEvery { service.getOHLC("EURUSD", 1_440, any()) } returns emptyList()
            val resolver = HistoryDailyPriceResolver(service, nowProvider = { now })

            resolver.resolve(accountDigest, setOf(HistoryDailyPriceRequest("ETH", date))) shouldBe emptyList()
        }

        "skips empty, future, invalid, zero, and unfinished market candles" {
            val service = mockk<KrakenService>(relaxed = true)
            val requestedDay = LocalDate.parse("2026-03-30")
            val requestedOpen = requestedDay.atStartOfDay(ZoneOffset.UTC).toEpochSecond()
            val currentOpen = LocalDate.parse("2026-04-02").atStartOfDay(ZoneOffset.UTC).toEpochSecond()
            val now = Instant.parse("2026-04-02T12:00:00Z")
            coEvery { service.getOHLC("XBTUSD", 1_440, any()) } returns listOf(
                -1L to BigDecimal("50000"),
                requestedOpen to BigDecimal.ZERO,
                currentOpen to BigDecimal("61000"),
            )
            val resolver = HistoryDailyPriceResolver(service, nowProvider = { now })

            resolver.resolve(accountDigest, emptySet()) shouldBe emptyList()
            resolver.resolve(
                accountDigest,
                setOf(
                    HistoryDailyPriceRequest("XBT", requestedDay),
                    HistoryDailyPriceRequest("XBT", LocalDate.parse("2026-04-02")),
                ),
            ) shouldBe emptyList()
        }
    }

    private fun build(checkpoints: List<HistoryBalanceCheckpoint>, coverage: List<HistorySyncCoverageWindow>) =
        builder.build(
            accountIdentityDigest = accountDigest,
            startDay = date,
            endDay = date,
            checkpoints = checkpoints,
            coverage = coverage,
            sourceRevision = 1,
            generationId = "generation-1",
        )

    private fun buildAcross(startDay: LocalDate, endDay: LocalDate, checkpoints: List<HistoryBalanceCheckpoint>) =
        builder.build(
            accountIdentityDigest = accountDigest,
            startDay = startDay,
            endDay = endDay,
            checkpoints = checkpoints,
            coverage = coverage(endDay.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()),
            sourceRevision = 1,
            generationId = "generation-1",
        )

    private fun checkpoint(
        id: String,
        at: String?,
        amount: String,
        fee: String,
        balance: String,
        quantity: String,
        rawAssetClass: String = "currency",
        rawAssetKey: String? = "ZUSD",
        status: HistoryBalanceStatus = HistoryBalanceStatus.VALID,
    ) = HistoryBalanceCheckpoint(
        accountIdentityDigest = accountDigest,
        ledgerId = id,
        observedAt = at?.let(Instant::parse),
        rawAssetClass = rawAssetClass,
        rawAssetKey = rawAssetKey,
        rawAmount = amount,
        rawFee = fee,
        rawBalance = balance,
        quantity = quantity.toBigDecimal(),
        status = status,
    )

    private fun coverage(until: Instant) = listOf(HistorySyncCoverageWindow(0, until.epochSecond - 1))

    private fun dailyBalance(asset: String, quantity: BigDecimal) = HistoryDailyBalance(
        dayEnd = dayEnd,
        dayLabel = date,
        accountIdentityDigest = accountDigest,
        sourceAssetScope = HISTORY_ACCOUNT_DAILY_SCOPE,
        rawAssetClass = "currency",
        rawAssetKey = asset,
        quantity = quantity,
        sourceLedgerId = "checkpoint-$asset",
        sourceObservedAt = dayEnd.minusSeconds(1),
        sourceRevision = 1,
        generationId = "generation-1",
        status = HistoryDailyBalanceStatus.VALID,
    )

    private fun dailyPrice(asset: String, price: BigDecimal) = HistoryDailyPrice(
        accountIdentityDigest = accountDigest,
        rawAssetKey = asset,
        pair = "${asset}USD",
        candleOpen = date.atStartOfDay(ZoneOffset.UTC).toInstant(),
        candleEnd = dayEnd,
        closeUsd = price,
    )
    private fun unknownPair(pair: String) = KrakenPublicApiException(
        endpoint = "/0/public/OHLC?pair=$pair",
        errors = listOf("EQuery:Unknown asset pair"),
        message = "Kraken Public API Error: [EQuery:Unknown asset pair]",
    )
}
