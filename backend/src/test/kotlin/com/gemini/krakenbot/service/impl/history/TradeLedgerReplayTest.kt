package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.model.KrakenApiConstants
import com.gemini.krakenbot.model.LedgerEvent
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.math.BigDecimal
import java.time.Instant

@Suppress("unused")
class TradeLedgerReplayTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    private val now = Instant.parse("2026-01-05T12:00:00Z")

    private fun leg(
        ledgerId: String,
        asset: String,
        amount: String,
        fee: String = "0",
        balance: String? = null,
        refid: String = "TRADE-1",
    ): LedgerEvent = LedgerEvent(
        ledgerId = ledgerId,
        refid = refid,
        time = now,
        type = KrakenApiConstants.LEDGER_TYPE_TRADE,
        asset = asset,
        amount = BigDecimal(amount),
        fee = BigDecimal(fee),
        balance = balance?.let(::BigDecimal) ?: BigDecimal.ZERO,
        hasAuthoritativeBalance = balance != null,
    )

    private fun legMap(vararg legs: LedgerEvent, refid: String = "TRADE-1"): Map<String, List<LedgerEvent>> =
        mapOf(refid to legs.toList())

    init {
        "base-denominated buy fee inverts the base wallet exactly once" {
            // Production fill id 1218: Kraken charged 0.9223 USD-equivalent as 0.00001406 BTC.
            val trade = TestFixtures.tradeRecord(
                timestamp = now,
                pair = "XXBTZUSD",
                side = "buy",
                symbol = "BTC",
                volume = BigDecimal("0.00703085"),
                usdAmount = BigDecimal("461.14"),
                price = BigDecimal("65588"),
                fee = BigDecimal("0.9223"),
                tradeId = "TRADE-1",
            )
            val base = leg("base", "XXBT", "0.00703085", fee = "0.00001406", balance = "0.06541898")
            val quote = leg("quote", "ZUSD", "-461.1394", balance = "0.0103")

            val replay = TradeLedgerReplay.classify(trade, legMap(base, quote))
                .shouldBeInstanceOf<TradeLedgerReplay.Classification.Replayable>()
            val effect = replay.ledgerEffect ?: error("expected an authoritative ledger effect")
            effect.baseNetDelta shouldBeEqualComparingTo BigDecimal("0.00701679")
            effect.quoteNetDelta shouldBeEqualComparingTo BigDecimal("-461.1394")

            val balances = mutableMapOf(
                "BTC" to BigDecimal("0.06541898"),
                "USD" to BigDecimal("0.0103"),
            )
            TradeLedgerReplay.reverseApply(replay, balances).shouldBeTrue()
            balances.getValue("BTC") shouldBeEqualComparingTo BigDecimal("0.05840219")
            balances.getValue("USD") shouldBeEqualComparingTo BigDecimal("461.1497")
        }

        "base-denominated sell fee inverts the base wallet exactly once" {
            // Production fill id 4532: the sell debit is volume + base fee.
            val trade = TestFixtures.tradeRecord(
                timestamp = now,
                pair = "XXBTZUSD",
                side = "sell",
                symbol = "BTC",
                volume = BigDecimal("0.00223943"),
                usdAmount = BigDecimal("200"),
                price = BigDecimal("89308.6"),
                fee = BigDecimal("0.7"),
                tradeId = "TRADE-1",
            )
            val base = leg("base", "XXBT", "-0.00223943", fee = "0.00000784", balance = "0.0044363")
            val quote = leg("quote", "ZUSD", "200.0004", balance = "794.1776")

            val replay = TradeLedgerReplay.classify(trade, legMap(base, quote))
                .shouldBeInstanceOf<TradeLedgerReplay.Classification.Replayable>()
            val effect = replay.ledgerEffect ?: error("expected an authoritative ledger effect")
            effect.baseNetDelta shouldBeEqualComparingTo BigDecimal("-0.00224727")

            val balances = mutableMapOf(
                "BTC" to BigDecimal("0.0044363"),
                "USD" to BigDecimal("794.1776"),
            )
            TradeLedgerReplay.reverseApply(replay, balances).shouldBeTrue()
            balances.getValue("BTC") shouldBeEqualComparingTo BigDecimal("0.00668357")
            balances.getValue("USD") shouldBeEqualComparingTo BigDecimal("594.1772")
        }

        "quote-denominated buy fee stays on the quote wallet" {
            val trade = TestFixtures.tradeRecord(
                timestamp = now,
                pair = "BTCUSD",
                side = "buy",
                symbol = "BTC",
                volume = BigDecimal("0.5"),
                usdAmount = BigDecimal("100.00"),
                price = BigDecimal("200"),
                fee = BigDecimal("0.10"),
                tradeId = "TRADE-1",
            )
            val base = leg("base", "XXBT", "0.5", balance = "1.5")
            val quote = leg("quote", "ZUSD", "-100.00", fee = "0.10", balance = "899.90")

            val replay = TradeLedgerReplay.classify(trade, legMap(base, quote))
                .shouldBeInstanceOf<TradeLedgerReplay.Classification.Replayable>()
            val balances = mutableMapOf(
                "BTC" to BigDecimal("1.5"),
                "USD" to BigDecimal("899.90"),
            )
            TradeLedgerReplay.reverseApply(replay, balances).shouldBeTrue()
            balances.getValue("BTC") shouldBeEqualComparingTo BigDecimal("1.0")
            balances.getValue("USD") shouldBeEqualComparingTo BigDecimal("1000.00")
        }

        "quote-denominated sell fee stays on the quote wallet" {
            val trade = TestFixtures.tradeRecord(
                timestamp = now,
                pair = "BTCUSD",
                side = "sell",
                symbol = "BTC",
                volume = BigDecimal("0.5"),
                usdAmount = BigDecimal("100.00"),
                price = BigDecimal("200"),
                fee = BigDecimal("0.10"),
                tradeId = "TRADE-1",
            )
            val base = leg("base", "XXBT", "-0.5", balance = "0.5")
            val quote = leg("quote", "ZUSD", "100.00", fee = "0.10", balance = "1100.10")

            val replay = TradeLedgerReplay.classify(trade, legMap(base, quote))
                .shouldBeInstanceOf<TradeLedgerReplay.Classification.Replayable>()
            val effect = replay.ledgerEffect ?: error("expected an authoritative ledger effect")
            effect.quoteNetDelta shouldBeEqualComparingTo BigDecimal("99.90")

            val balances = mutableMapOf(
                "BTC" to BigDecimal("0.5"),
                "USD" to BigDecimal("1100.10"),
            )
            TradeLedgerReplay.reverseApply(replay, balances).shouldBeTrue()
            balances.getValue("BTC") shouldBeEqualComparingTo BigDecimal("1.0")
            balances.getValue("USD") shouldBeEqualComparingTo BigDecimal("1000.20")
        }

        "leg amounts own the wallet effect even when they differ from the reported volume" {
            // Production fill id 4345: both legs carry a fee and the base leg rounds to 4dp.
            val trade = TestFixtures.tradeRecord(
                timestamp = now,
                pair = "SEIUSD",
                side = "buy",
                symbol = "SEI",
                volume = BigDecimal("1116.07142"),
                usdAmount = BigDecimal("124.27"),
                price = BigDecimal("0.111336"),
                fee = BigDecimal("2.333"),
                tradeId = "TRADE-1",
            )
            val base = leg("base", "SEI", "1116.0714", fee = "2.333", balance = "5000")
            val quote = leg("quote", "ZUSD", "-124.2679", fee = "0.1752", balance = "700")

            val replay = TradeLedgerReplay.classify(trade, legMap(base, quote))
                .shouldBeInstanceOf<TradeLedgerReplay.Classification.Replayable>()
            val effect = replay.ledgerEffect ?: error("expected an authoritative ledger effect")
            effect.baseNetDelta shouldBeEqualComparingTo BigDecimal("1113.7384")
            effect.quoteNetDelta shouldBeEqualComparingTo BigDecimal("-124.4431")

            val balances = mutableMapOf(
                "SEI" to BigDecimal("5000"),
                "USD" to BigDecimal("700"),
            )
            TradeLedgerReplay.reverseApply(replay, balances).shouldBeTrue()
            balances.getValue("SEI") shouldBeEqualComparingTo BigDecimal("3886.2616")
            balances.getValue("USD") shouldBeEqualComparingTo BigDecimal("824.4431")
        }

        "missing quote leg with zero reported movement is complete evidence" {
            // Production fill id 4495: a sell whose USD proceeds rounded below ledger precision.
            val trade = TestFixtures.tradeRecord(
                timestamp = now,
                pair = "XXLMZUSD",
                side = "sell",
                symbol = "XLM",
                volume = BigDecimal("0.00007082"),
                usdAmount = BigDecimal("0"),
                price = BigDecimal("0.241537"),
                fee = BigDecimal("0"),
                tradeId = "TRADE-1",
            )
            val base = leg("base", "XXLM", "-0.00007082", balance = "458.5553988")

            val replay = TradeLedgerReplay.classify(trade, legMap(base))
                .shouldBeInstanceOf<TradeLedgerReplay.Classification.Replayable>()
            val effect = replay.ledgerEffect ?: error("expected an authoritative ledger effect")
            effect.baseNetDelta shouldBeEqualComparingTo BigDecimal("-0.00007082")
            effect.quoteNetDelta shouldBeEqualComparingTo BigDecimal.ZERO

            val balances = mutableMapOf(
                "XLM" to BigDecimal("458.5553988"),
                "USD" to BigDecimal("1000"),
            )
            TradeLedgerReplay.reverseApply(replay, balances).shouldBeTrue()
            balances.getValue("XLM") shouldBeEqualComparingTo BigDecimal("458.55546962")
            balances.getValue("USD") shouldBeEqualComparingTo BigDecimal("1000")
        }

        "non-USD quote keeps true base and quote economics" {
            val trade = TestFixtures.tradeRecord(
                timestamp = now,
                pair = "ATOMUSDT",
                side = "buy",
                symbol = "ATOM",
                volume = BigDecimal("10"),
                usdAmount = BigDecimal("999.00"),
                price = BigDecimal("2.00"),
                fee = BigDecimal("0.05"),
                tradeId = "TRADE-1",
            )
            val base = leg("base", "ATOM", "10", fee = "0.05", balance = "100")
            val quote = leg("quote", "USDT", "-20", balance = "500")

            val replay = TradeLedgerReplay.classify(trade, legMap(base, quote))
                .shouldBeInstanceOf<TradeLedgerReplay.Classification.Replayable>()
            replay.base shouldBe "ATOM"
            replay.quote shouldBe "USDT"
            replay.quoteCost shouldBeEqualComparingTo BigDecimal("20")
            val effect = replay.ledgerEffect ?: error("expected an authoritative ledger effect")
            effect.baseNetDelta shouldBeEqualComparingTo BigDecimal("9.95")
            effect.quoteNetDelta shouldBeEqualComparingTo BigDecimal("-20")
        }

        "non-USD quote fee is inverted from the quote leg" {
            val trade = TestFixtures.tradeRecord(
                timestamp = now,
                pair = "ATOMUSDT",
                side = "sell",
                symbol = "ATOM",
                volume = BigDecimal("10"),
                usdAmount = BigDecimal("999.00"),
                price = BigDecimal("2.00"),
                fee = BigDecimal("0.05"),
                tradeId = "TRADE-1",
            )
            val base = leg("base", "ATOM", "-10", balance = "90")
            val quote = leg("quote", "USDT", "19.95", fee = "0.05", balance = "519.95")

            val replay = TradeLedgerReplay.classify(trade, legMap(base, quote))
                .shouldBeInstanceOf<TradeLedgerReplay.Classification.Replayable>()
            val effect = replay.ledgerEffect ?: error("expected an authoritative ledger effect")
            effect.quoteNetDelta shouldBeEqualComparingTo BigDecimal("19.90")

            val balances = mutableMapOf(
                "ATOM" to BigDecimal("90"),
                "USDT" to BigDecimal("519.95"),
            )
            TradeLedgerReplay.reverseApply(replay, balances).shouldBeTrue()
            balances.getValue("ATOM") shouldBeEqualComparingTo BigDecimal("100")
            balances.getValue("USDT") shouldBeEqualComparingTo BigDecimal("500.05")
        }

        "a trade without retained legs keeps the legacy TradeRecord economics" {
            val trade = TestFixtures.tradeRecord(
                timestamp = now,
                pair = "XXBTZUSD",
                side = "buy",
                symbol = "BTC",
                volume = BigDecimal("0.00703085"),
                usdAmount = BigDecimal("461.14"),
                price = BigDecimal("65588"),
                fee = BigDecimal("0.9223"),
                tradeId = "TRADE-1",
            )

            val replay = TradeLedgerReplay.classify(trade, emptyMap())
                .shouldBeInstanceOf<TradeLedgerReplay.Classification.Replayable>()
            replay.ledgerEffect shouldBe null

            val balances = mutableMapOf(
                "BTC" to BigDecimal("0.06541898"),
                "USD" to BigDecimal("0.0103"),
            )
            TradeLedgerReplay.reverseApply(replay, balances).shouldBeTrue()
            balances.getValue("BTC") shouldBeEqualComparingTo BigDecimal("0.05838813")
            balances.getValue("USD") shouldBeEqualComparingTo BigDecimal("462.0726")
        }

        "legs keyed by another trade identity are ignored" {
            val trade = TestFixtures.tradeRecord(
                timestamp = now,
                pair = "BTCUSD",
                side = "buy",
                symbol = "BTC",
                volume = BigDecimal("0.5"),
                usdAmount = BigDecimal("100.00"),
                price = BigDecimal("200"),
                fee = BigDecimal("0.10"),
                tradeId = "TRADE-1",
            )
            val base = leg("base", "XXBT", "0.5", balance = "1.5", refid = "OTHER")
            val quote = leg("quote", "ZUSD", "-100.00", fee = "0.10", balance = "899.90", refid = "OTHER")

            val replay = TradeLedgerReplay.classify(trade, mapOf("OTHER" to listOf(base, quote)))
                .shouldBeInstanceOf<TradeLedgerReplay.Classification.Replayable>()
            replay.ledgerEffect shouldBe null
        }

        "duplicate and unexpected legs fail closed" {
            val trade = TestFixtures.tradeRecord(
                timestamp = now,
                pair = "BTCUSD",
                side = "buy",
                symbol = "BTC",
                volume = BigDecimal("0.5"),
                usdAmount = BigDecimal("100.00"),
                price = BigDecimal("200"),
                fee = BigDecimal("0.10"),
                tradeId = "TRADE-1",
            )
            val firstBase = leg("base-1", "XXBT", "0.5", balance = "1.5")
            val secondBase = leg("base-2", "XXBT", "0.5", balance = "1.0")
            val quote = leg("quote", "ZUSD", "-100.00", fee = "0.10", balance = "899.90")
            val foreign = leg("foreign", "XETH", "0.01", balance = "0.01")

            val duplicates = TradeLedgerReplay.classify(trade, legMap(firstBase, secondBase, quote))
                .shouldBeInstanceOf<TradeLedgerReplay.Classification.Unsupported>()
            duplicates.reason shouldBe "unexpected historical trade ledger legs"

            val mixed = TradeLedgerReplay.classify(trade, legMap(firstBase, quote, foreign))
                .shouldBeInstanceOf<TradeLedgerReplay.Classification.Unsupported>()
            mixed.reason shouldBe "unexpected historical trade ledger legs"
        }

        "missing legs fail closed unless the movement is provably zero" {
            val buy = TestFixtures.tradeRecord(
                timestamp = now,
                pair = "BTCUSD",
                side = "buy",
                symbol = "BTC",
                volume = BigDecimal("0.5"),
                usdAmount = BigDecimal("100.00"),
                price = BigDecimal("200"),
                fee = BigDecimal("0.10"),
                tradeId = "TRADE-1",
            )
            val baseOnly = leg("base", "XXBT", "0.5", balance = "1.5")
            val quoteOnly = leg("quote", "ZUSD", "-100.00", fee = "0.10", balance = "899.90")

            val missingQuote = TradeLedgerReplay.classify(buy, legMap(baseOnly))
                .shouldBeInstanceOf<TradeLedgerReplay.Classification.Unsupported>()
            missingQuote.reason shouldBe "missing historical trade ledger leg"

            val missingBase = TradeLedgerReplay.classify(buy, legMap(quoteOnly))
                .shouldBeInstanceOf<TradeLedgerReplay.Classification.Unsupported>()
            missingBase.reason shouldBe "missing historical trade ledger leg"
        }

        "contradictory leg directions fail closed" {
            val buy = TestFixtures.tradeRecord(
                timestamp = now,
                pair = "BTCUSD",
                side = "buy",
                symbol = "BTC",
                volume = BigDecimal("0.5"),
                usdAmount = BigDecimal("100.00"),
                price = BigDecimal("200"),
                fee = BigDecimal("0.10"),
                tradeId = "TRADE-1",
            )
            val negativeBase = leg("base", "XXBT", "-0.5", balance = "1.0")
            val quote = leg("quote", "ZUSD", "-100.00", fee = "0.10", balance = "899.90")

            val result = TradeLedgerReplay.classify(buy, legMap(negativeBase, quote))
                .shouldBeInstanceOf<TradeLedgerReplay.Classification.Unsupported>()
            result.reason shouldBe "contradictory historical trade ledger effect"
        }

        "zero-volume trades with ledger movement fail closed" {
            val trade = TestFixtures.tradeRecord(
                timestamp = now,
                pair = "BTCUSD",
                side = "buy",
                symbol = "BTC",
                volume = BigDecimal.ZERO,
                usdAmount = BigDecimal("0"),
                price = BigDecimal("200"),
                fee = BigDecimal("0"),
                tradeId = "TRADE-1",
            )
            val base = leg("base", "XXBT", "0.1", balance = "1.1")
            val quote = leg("quote", "ZUSD", "0", balance = "999.90")

            val result = TradeLedgerReplay.classify(trade, legMap(base, quote))
                .shouldBeInstanceOf<TradeLedgerReplay.Classification.Unsupported>()
            result.reason shouldBe "zero-volume historical trade moved a wallet balance"
        }

        "invalid trade economics fail closed before any leg matching" {
            val unsupportedMarket = TestFixtures.tradeRecord(
                timestamp = now,
                pair = "ADAEUR",
                side = "buy",
                symbol = "ADA",
                volume = BigDecimal("1"),
                usdAmount = BigDecimal("1"),
                tradeId = "TRADE-1",
            )
            val market = TradeLedgerReplay.classify(unsupportedMarket, emptyMap())
                .shouldBeInstanceOf<TradeLedgerReplay.Classification.Unsupported>()
            market.reason shouldBe "unsupported historical market ADAEUR"

            val unsupportedSide = TestFixtures.tradeRecord(
                timestamp = now,
                pair = "BTCUSD",
                side = "hold",
                symbol = "BTC",
                volume = BigDecimal("1"),
                usdAmount = BigDecimal("1"),
                tradeId = "TRADE-1",
            )
            val side = TradeLedgerReplay.classify(unsupportedSide, emptyMap())
                .shouldBeInstanceOf<TradeLedgerReplay.Classification.Unsupported>()
            side.reason shouldBe "unsupported historical trade side"

            val negativeVolume = TestFixtures.tradeRecord(
                timestamp = now,
                pair = "BTCUSD",
                side = "buy",
                symbol = "BTC",
                volume = BigDecimal("-1"),
                usdAmount = BigDecimal("1"),
                tradeId = "TRADE-1",
            )
            val volume = TradeLedgerReplay.classify(negativeVolume, emptyMap())
                .shouldBeInstanceOf<TradeLedgerReplay.Classification.Unsupported>()
            volume.reason shouldBe "malformed historical trade economics"

            val negativeFee = TestFixtures.tradeRecord(
                timestamp = now,
                pair = "BTCUSD",
                side = "buy",
                symbol = "BTC",
                volume = BigDecimal("1"),
                usdAmount = BigDecimal("1"),
                fee = BigDecimal("-0.01"),
                tradeId = "TRADE-1",
            )
            val fee = TradeLedgerReplay.classify(negativeFee, emptyMap())
                .shouldBeInstanceOf<TradeLedgerReplay.Classification.Unsupported>()
            fee.reason shouldBe "malformed historical trade economics"

            val missingCost = TestFixtures.tradeRecord(
                timestamp = now,
                pair = "BTCUSD",
                side = "buy",
                symbol = "BTC",
                volume = BigDecimal("1"),
                usdAmount = BigDecimal("0"),
                price = BigDecimal("0"),
                tradeId = "TRADE-1",
            )
            val cost = TradeLedgerReplay.classify(missingCost, emptyMap())
                .shouldBeInstanceOf<TradeLedgerReplay.Classification.Unsupported>()
            cost.reason shouldBe "missing historical trade cost"
        }

        "reverse apply rejects missing balances and malformed replay state" {
            val trade = TestFixtures.tradeRecord(
                timestamp = now,
                pair = "BTCUSD",
                side = "buy",
                symbol = "BTC",
                volume = BigDecimal("0.5"),
                usdAmount = BigDecimal("100.00"),
                price = BigDecimal("200"),
                fee = BigDecimal("0.10"),
                tradeId = "TRADE-1",
            )
            val replay = TradeLedgerReplay.classify(trade, emptyMap())
                .shouldBeInstanceOf<TradeLedgerReplay.Classification.Replayable>()

            val missingQuote = mutableMapOf("BTC" to BigDecimal("1.0"))
            TradeLedgerReplay.reverseApply(replay, missingQuote).shouldBeFalse()

            val malformed = replay.copy(volume = BigDecimal("-1"))
            val balances = mutableMapOf("BTC" to BigDecimal("1.0"), "USD" to BigDecimal("1000.0"))
            TradeLedgerReplay.reverseApply(malformed, balances).shouldBeFalse()
        }

        "trade identities without retained legs keep the legacy path" {
            val trade = TestFixtures.tradeRecord(
                timestamp = now,
                pair = "BTCUSD",
                side = "buy",
                symbol = "BTC",
                volume = BigDecimal("0.5"),
                usdAmount = BigDecimal("100.00"),
                price = BigDecimal("200"),
                fee = BigDecimal("0.10"),
                tradeId = "TRADE-1",
            )
            val base = leg("base", "XXBT", "0.5", balance = "1.5")
            val quote = leg("quote", "ZUSD", "-100.00", balance = "899.90")

            val withoutIdentity = TradeLedgerReplay.classify(trade.copy(tradeId = null), legMap(base, quote))
                .shouldBeInstanceOf<TradeLedgerReplay.Classification.Replayable>()
            withoutIdentity.ledgerEffect shouldBe null

            val blankIdentity = TradeLedgerReplay.classify(trade.copy(tradeId = " "), legMap(base, quote))
                .shouldBeInstanceOf<TradeLedgerReplay.Classification.Replayable>()
            blankIdentity.ledgerEffect shouldBe null

            val sell = TestFixtures.tradeRecord(
                timestamp = now,
                pair = "BTCUSD",
                side = "sell",
                symbol = "BTC",
                volume = BigDecimal("0.5"),
                usdAmount = BigDecimal("100.00"),
                price = BigDecimal("200"),
                fee = BigDecimal("0.10"),
                tradeId = "TRADE-2",
            )
            val legacySell = TradeLedgerReplay.classify(sell, emptyMap())
                .shouldBeInstanceOf<TradeLedgerReplay.Classification.Replayable>()
            val balances = mutableMapOf("BTC" to BigDecimal("1.0"), "USD" to BigDecimal("1000.0"))
            TradeLedgerReplay.reverseApply(legacySell, balances).shouldBeTrue()
            balances.getValue("BTC") shouldBeEqualComparingTo BigDecimal("1.5")
            balances.getValue("USD") shouldBeEqualComparingTo BigDecimal("900.10")
        }

        "reverse apply rejects malformed quote cost and fee values" {
            val trade = TestFixtures.tradeRecord(
                timestamp = now,
                pair = "BTCUSD",
                side = "buy",
                symbol = "BTC",
                volume = BigDecimal("0.5"),
                usdAmount = BigDecimal("100.00"),
                price = BigDecimal("200"),
                fee = BigDecimal("0.10"),
                tradeId = "TRADE-1",
            )
            val replay = TradeLedgerReplay.classify(trade, emptyMap())
                .shouldBeInstanceOf<TradeLedgerReplay.Classification.Replayable>()
            val balances = mutableMapOf("BTC" to BigDecimal("1.0"), "USD" to BigDecimal("1000.0"))

            TradeLedgerReplay.reverseApply(replay.copy(quoteCost = BigDecimal("-1")), balances).shouldBeFalse()
            TradeLedgerReplay.reverseApply(replay.copy(fee = BigDecimal("-1")), balances).shouldBeFalse()

            val missingBase = mutableMapOf("USD" to BigDecimal("1000.0"))
            TradeLedgerReplay.reverseApply(replay, missingBase).shouldBeFalse()
        }

        "leg shapes beyond a clean base and quote pair fail closed" {
            val trade = TestFixtures.tradeRecord(
                timestamp = now,
                pair = "BTCUSD",
                side = "buy",
                symbol = "BTC",
                volume = BigDecimal("0.5"),
                usdAmount = BigDecimal("100.00"),
                price = BigDecimal("200"),
                fee = BigDecimal("0.10"),
                tradeId = "TRADE-1",
            )

            val empty = TradeLedgerReplay.classify(trade, mapOf("TRADE-1" to emptyList()))
                .shouldBeInstanceOf<TradeLedgerReplay.Classification.Unsupported>()
            empty.reason shouldBe "unexpected historical trade ledger legs"

            val base = leg("base", "XXBT", "0.5", balance = "1.5")
            val quote = leg("quote", "ZUSD", "-100.10", balance = "899.90")
            val duplicateQuote = leg("quote-2", "ZUSD", "-1.00", balance = "898.90")
            val duplicated = TradeLedgerReplay.classify(trade, legMap(base, quote, duplicateQuote))
                .shouldBeInstanceOf<TradeLedgerReplay.Classification.Unsupported>()
            duplicated.reason shouldBe "unexpected historical trade ledger legs"
        }

        "zero-volume trades without wallet movement are complete evidence" {
            val trade = TestFixtures.tradeRecord(
                timestamp = now,
                pair = "BTCUSD",
                side = "buy",
                symbol = "BTC",
                volume = BigDecimal.ZERO,
                usdAmount = BigDecimal.ZERO,
                price = BigDecimal("200"),
                fee = BigDecimal.ZERO,
                tradeId = "TRADE-1",
            )
            val quote = leg("quote", "ZUSD", "0", balance = "999.90")

            val replay = TradeLedgerReplay.classify(trade, legMap(quote))
                .shouldBeInstanceOf<TradeLedgerReplay.Classification.Replayable>()
            val effect = replay.ledgerEffect ?: error("expected an authoritative ledger effect")
            effect.baseNetDelta shouldBeEqualComparingTo BigDecimal.ZERO
            effect.quoteNetDelta shouldBeEqualComparingTo BigDecimal.ZERO

            val balances = mutableMapOf("BTC" to BigDecimal("1.0"), "USD" to BigDecimal("999.90"))
            TradeLedgerReplay.reverseApply(replay, balances).shouldBeTrue()
            balances.getValue("BTC") shouldBeEqualComparingTo BigDecimal("1.0")
            balances.getValue("USD") shouldBeEqualComparingTo BigDecimal("999.90")
        }

        "contradictory leg directions fail closed for both sides" {
            val buy = TestFixtures.tradeRecord(
                timestamp = now,
                pair = "BTCUSD",
                side = "buy",
                symbol = "BTC",
                volume = BigDecimal("0.5"),
                usdAmount = BigDecimal("100.00"),
                price = BigDecimal("200"),
                fee = BigDecimal.ZERO,
                tradeId = "TRADE-1",
            )
            val positiveQuote = TradeLedgerReplay.classify(
                buy,
                legMap(
                    leg("base", "XXBT", "0.5", balance = "1.5"),
                    leg("quote", "ZUSD", "100.00", balance = "1100.00"),
                ),
            ).shouldBeInstanceOf<TradeLedgerReplay.Classification.Unsupported>()
            positiveQuote.reason shouldBe "contradictory historical trade ledger effect"

            val sell = buy.copy(side = "sell")
            val positiveBase = TradeLedgerReplay.classify(
                sell,
                legMap(
                    leg("base", "XXBT", "0.5", balance = "1.5"),
                    leg("quote", "ZUSD", "-100.00", balance = "900.00"),
                ),
            ).shouldBeInstanceOf<TradeLedgerReplay.Classification.Unsupported>()
            positiveBase.reason shouldBe "contradictory historical trade ledger effect"

            val negativeQuote = TradeLedgerReplay.classify(
                sell,
                legMap(
                    leg("base", "XXBT", "-0.5", balance = "0.5"),
                    leg("quote", "ZUSD", "-100.00", balance = "900.00"),
                ),
            ).shouldBeInstanceOf<TradeLedgerReplay.Classification.Unsupported>()
            negativeQuote.reason shouldBe "contradictory historical trade ledger effect"
        }

        "non-authoritative legs invert from the running balance" {
            val trade = TestFixtures.tradeRecord(
                timestamp = now,
                pair = "BTCUSD",
                side = "buy",
                symbol = "BTC",
                volume = BigDecimal("0.5"),
                usdAmount = BigDecimal("100.00"),
                price = BigDecimal("200"),
                fee = BigDecimal("0.10"),
                tradeId = "TRADE-1",
            )
            val replay = TradeLedgerReplay.classify(
                trade,
                legMap(
                    leg("base", "XXBT", "0.5"),
                    leg("quote", "ZUSD", "-100.10"),
                ),
            ).shouldBeInstanceOf<TradeLedgerReplay.Classification.Replayable>()
            val effect = replay.ledgerEffect ?: error("expected an authoritative ledger effect")
            effect.baseCheckpoint shouldBe null
            effect.quoteCheckpoint shouldBe null

            val balances = mutableMapOf("BTC" to BigDecimal("1.5"), "USD" to BigDecimal("999.90"))
            TradeLedgerReplay.reverseApply(replay, balances).shouldBeTrue()
            balances.getValue("BTC") shouldBeEqualComparingTo BigDecimal("1.0")
            balances.getValue("USD") shouldBeEqualComparingTo BigDecimal("1100.00")
        }
    }
}
