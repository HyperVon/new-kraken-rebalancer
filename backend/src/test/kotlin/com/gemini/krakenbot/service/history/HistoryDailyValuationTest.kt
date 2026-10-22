package com.gemini.krakenbot.service.history

import com.gemini.krakenbot.service.KrakenService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
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

    private fun checkpoint(
        id: String,
        at: String,
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
        observedAt = Instant.parse(at),
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
}
