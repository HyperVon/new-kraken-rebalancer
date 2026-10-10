package com.gemini.krakenbot.service.actual

import com.gemini.krakenbot.service.DirectBalanceCapture
import com.gemini.krakenbot.service.DirectEvidenceStatus
import com.gemini.krakenbot.service.DirectTickerCapture
import com.gemini.krakenbot.service.DirectTickerMark
import com.gemini.krakenbot.service.DirectValueEvidence
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant

class ActualObservationValuatorTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "basic direct managed valuation matches the hand-calculated oracle" {
            val observation = observe("300", "100", "200", "1.0", "0.5", "100.00")

            observation.status shouldBe ActualObservationStatus.COMPLETE
            observation.assets.associate { it.symbol to it.valueUsd?.toPlainString() } shouldBe mapOf(
                "BTC" to "100.0",
                "ETH" to "100.0",
                "USD" to "100.00",
            )
            observation.totalUsd?.compareTo(BigDecimal("300.00")) shouldBe 0
        }

        "market price changes create a new value while preserving the earlier observation" {
            val first = observe("first", "100", "200", "1.0", "0.5", "100.00")
            val second = observe("second", "120", "180", "1.0", "0.5", "100.00")

            first.totalUsd?.compareTo(BigDecimal("300.00")) shouldBe 0
            second.assets.associate { it.symbol to it.valueUsd?.toPlainString() } shouldBe mapOf(
                "BTC" to "120.0",
                "ETH" to "90.0",
                "USD" to "100.00",
            )
            second.totalUsd?.compareTo(BigDecimal("310.00")) shouldBe 0
        }

        "a new cash balance changes observed value without creating a return field" {
            val first = observe("first", "100", "200", "1.0", "0.5", "100.00")
            val afterDeposit = observe("deposit", "100", "200", "1.0", "0.5", "150.00")

            first.totalUsd?.compareTo(BigDecimal("300.00")) shouldBe 0
            afterDeposit.totalUsd?.compareTo(BigDecimal("350.00")) shouldBe 0
        }

        "scope membership changes use a different segment and do not mutate prior evidence" {
            val original = observe("original", "100", "200", "1.0", "0.5", "100.00")
            val revised = ActualObservationValuator.build(
                observationId = "revised",
                scopeSymbols = listOf("BTC", "USD"),
                accountIdentityDigest = ACCOUNT_DIGEST,
                balances = balances("1.0", "0.5", "100.00"),
                prices = prices("100", "200"),
                now = NOW.plusSeconds(10),
            )

            (original.scopeFingerprint == revised.scopeFingerprint) shouldBe false
            original.scopeSymbols shouldBe listOf("BTC", "ETH", "USD")
            original.assets.map { it.symbol } shouldBe listOf("BTC", "ETH", "USD")
            revised.scopeSymbols shouldBe listOf("BTC", "USD")
        }

        "missing required ticker marks never become a zero-priced complete total" {
            val missingEth = prices("100", null)
            val observation = ActualObservationValuator.build(
                observationId = "missing-price",
                scopeSymbols = SYMBOLS,
                accountIdentityDigest = ACCOUNT_DIGEST,
                balances = balances("1.0", "0.5", "100.00"),
                prices = missingEth,
                now = NOW.plusSeconds(10),
            )

            observation.status shouldBe ActualObservationStatus.INCOMPLETE
            observation.totalUsd shouldBe null
            observation.assets.single { it.symbol == "ETH" }.status shouldBe ActualAssetStatus.MISSING_PRICE
            observation.assets.single { it.symbol == "ETH" }.valueUsd shouldBe null
        }

        "a missing configured balance is unresolved rather than silently treated as zero" {
            val capture = balances("1.0", "0.5", "100.00").copy(
                valuesByAssetId = balances("1.0", "0.5", "100.00").valuesByAssetId - "XETH",
            )
            val observation = ActualObservationValuator.build(
                observationId = "missing-balance",
                scopeSymbols = SYMBOLS,
                accountIdentityDigest = ACCOUNT_DIGEST,
                balances = capture,
                prices = prices("100", "200"),
                now = NOW.plusSeconds(10),
            )

            observation.status shouldBe ActualObservationStatus.INCOMPLETE
            observation.totalUsd shouldBe null
            observation.assets.single { it.symbol == "ETH" }.status shouldBe ActualAssetStatus.MISSING_BALANCE
        }

        "fractional quantities and price multiplication preserve exact decimal precision" {
            val preciseBalances = DirectBalanceCapture(
                requestStartedAt = NOW,
                responseEndedAt = NOW.plusSeconds(1),
                resultShapeValid = true,
                valuesByAssetId = mapOf(
                    "XXBT" to evidence("0.123456789012345678"),
                    "XETH" to evidence("0.000000000000000001"),
                    "ZUSD" to evidence("0.10"),
                ),
            )
            val precisePrices = prices("3", "1.2")
            val observation = ActualObservationValuator.build(
                observationId = "precision",
                scopeSymbols = SYMBOLS,
                accountIdentityDigest = ACCOUNT_DIGEST,
                balances = preciseBalances,
                prices = precisePrices,
                now = NOW.plusSeconds(10),
            )

            observation.assets.single { it.symbol == "BTC" }.valueUsd?.toPlainString() shouldBe
                "0.370370367037037034"
            observation.assets.single { it.symbol == "ETH" }.valueUsd?.toPlainString() shouldBe
                "0.0000000000000000012"
            observation.totalUsd?.toPlainString() shouldBe "0.4703703670370370352"
        }

        "an observation more than sixty seconds from its marks remains incomplete" {
            val stalePrices = prices("100", "200").copy(
                requestStartedAt = NOW.plusSeconds(70),
                responseEndedAt = NOW.plusSeconds(71),
            )
            val observation = ActualObservationValuator.build(
                observationId = "skewed",
                scopeSymbols = SYMBOLS,
                accountIdentityDigest = ACCOUNT_DIGEST,
                balances = balances("1.0", "0.5", "100.00"),
                prices = stalePrices,
                now = NOW.plusSeconds(80),
            )

            observation.status shouldBe ActualObservationStatus.INCOMPLETE
            observation.totalUsd shouldBe null
            observation.assets.all { it.status == ActualAssetStatus.INVALID_TIME_WINDOW } shouldBe true
        }

        "a long balance request makes the observation incomplete while retaining its evidence" {
            val slowBalances = balances("1.0", "0.5", "100.00").copy(
                responseEndedAt = NOW.plusSeconds(3_600),
            )
            val followingPrices = prices("100", "200").copy(
                requestStartedAt = NOW.plusSeconds(3_601),
                responseEndedAt = NOW.plusSeconds(3_602),
            )
            val observation = ActualObservationValuator.build(
                observationId = "slow-balance",
                scopeSymbols = SYMBOLS,
                accountIdentityDigest = ACCOUNT_DIGEST,
                balances = slowBalances,
                prices = followingPrices,
                now = NOW.plusSeconds(3_603),
            )

            observation.status shouldBe ActualObservationStatus.INCOMPLETE
            observation.totalUsd shouldBe null
            observation.assets.all { it.status == ActualAssetStatus.INVALID_TIME_WINDOW } shouldBe true
            observation.assets.single { it.symbol == "BTC" }.rawBalanceValues["XXBT"] shouldBe "1.0"
            observation.balanceRequestStartedAt shouldBe NOW
            observation.balanceResponseEndedAt shouldBe NOW.plusSeconds(3_600)
            observation.incompleteReasons.all { it.contains("120 seconds") } shouldBe true
        }

        "a long ticker request makes the observation incomplete while retaining its timestamps" {
            val slowPrices = prices("100", "200").copy(
                responseEndedAt = NOW.plusSeconds(3_602),
            )
            val observation = ActualObservationValuator.build(
                observationId = "slow-ticker",
                scopeSymbols = SYMBOLS,
                accountIdentityDigest = ACCOUNT_DIGEST,
                balances = balances("1.0", "0.5", "100.00"),
                prices = slowPrices,
                now = NOW.plusSeconds(3_603),
            )

            observation.status shouldBe ActualObservationStatus.INCOMPLETE
            observation.totalUsd shouldBe null
            observation.assets.all { it.status == ActualAssetStatus.INVALID_TIME_WINDOW } shouldBe true
            observation.assets.single { it.symbol == "BTC" }.rawPrice shouldBe "100"
            observation.priceRequestStartedAt shouldBe NOW.plusSeconds(2)
            observation.priceResponseEndedAt shouldBe NOW.plusSeconds(3_602)
            observation.incompleteReasons.all { it.contains("120 seconds") } shouldBe true
        }

        "the exact total-window and inter-request-gap boundaries remain complete" {
            val observation = ActualObservationValuator.build(
                observationId = "window-boundary",
                scopeSymbols = SYMBOLS,
                accountIdentityDigest = ACCOUNT_DIGEST,
                balances = balances("1.0", "0.5", "100.00").copy(
                    responseEndedAt = NOW.plusSeconds(30),
                ),
                prices = prices("100", "200").copy(
                    requestStartedAt = NOW.plusSeconds(90),
                    responseEndedAt = NOW.plusSeconds(120),
                ),
                now = NOW.plusSeconds(121),
            )

            observation.status shouldBe ActualObservationStatus.COMPLETE
            observation.totalUsd?.compareTo(BigDecimal("300.00")) shouldBe 0
            observation.incompleteReasons shouldBe emptyList()
        }

        "reversed request timestamp pairs are retained as incomplete observations" {
            val invalidBalance = ActualObservationValuator.build(
                observationId = "reversed-balance-time",
                scopeSymbols = SYMBOLS,
                accountIdentityDigest = ACCOUNT_DIGEST,
                balances = balances("1.0", "0.5", "100.00").copy(
                    responseEndedAt = NOW.minusSeconds(1),
                ),
                prices = prices("100", "200"),
                now = NOW.plusSeconds(10),
            )
            val invalidTicker = ActualObservationValuator.build(
                observationId = "reversed-ticker-time",
                scopeSymbols = SYMBOLS,
                accountIdentityDigest = ACCOUNT_DIGEST,
                balances = balances("1.0", "0.5", "100.00"),
                prices = prices("100", "200").copy(
                    responseEndedAt = NOW.plusSeconds(1),
                ),
                now = NOW.plusSeconds(10),
            )

            listOf(invalidBalance, invalidTicker).forEach { observation ->
                observation.status shouldBe ActualObservationStatus.INCOMPLETE
                observation.totalUsd shouldBe null
                observation.assets.all { it.status == ActualAssetStatus.INVALID_TIME_WINDOW } shouldBe true
                observation.incompleteReasons.all { it.contains("timestamps are invalid") } shouldBe true
            }
        }

        "invalid balance price and future capture timestamps fail closed" {
            val badBalances = balances("-1", "0.5", "100.00")
            val badPrices = prices("0", "200")
            val invalid = ActualObservationValuator.build(
                observationId = "invalid-values",
                scopeSymbols = SYMBOLS,
                accountIdentityDigest = ACCOUNT_DIGEST,
                balances = badBalances,
                prices = badPrices,
                now = NOW.plusSeconds(10),
            )
            val future = balances("1.0", "0.5", "100.00").copy(
                responseEndedAt = NOW.plusSeconds(20),
            )

            invalid.status shouldBe ActualObservationStatus.INCOMPLETE
            invalid.assets.single { it.symbol == "BTC" }.status shouldBe ActualAssetStatus.INVALID_BALANCE
            invalid.assets.single { it.symbol == "BTC" }.priceUsd shouldBe BigDecimal.ZERO
            shouldThrow<IllegalArgumentException> {
                ActualObservationValuator.build(
                    observationId = "future",
                    scopeSymbols = SYMBOLS,
                    accountIdentityDigest = ACCOUNT_DIGEST,
                    balances = future,
                    prices = prices("100", "200"),
                    now = NOW.plusSeconds(10),
                )
            }
        }

        "multiple Kraken balance aliases are ambiguous rather than double counted" {
            val source = balances("1.0", "0.5", "100.00")
            val collision = source.copy(
                valuesByAssetId = source.valuesByAssetId + ("XBT" to evidence("2.0")),
            )
            val observation = ActualObservationValuator.build(
                observationId = "alias-collision",
                scopeSymbols = SYMBOLS,
                accountIdentityDigest = ACCOUNT_DIGEST,
                balances = collision,
                prices = prices("100", "200"),
                now = NOW.plusSeconds(10),
            )

            observation.status shouldBe ActualObservationStatus.INCOMPLETE
            observation.totalUsd shouldBe null
            observation.assets.single { it.symbol == "BTC" }.status shouldBe ActualAssetStatus.AMBIGUOUS_BALANCE
        }
    }

    private fun observe(
        id: String,
        btcPrice: String,
        ethPrice: String,
        btcQuantity: String,
        ethQuantity: String,
        usdQuantity: String,
    ): ActualObservation = ActualObservationValuator.build(
        observationId = id,
        scopeSymbols = SYMBOLS,
        accountIdentityDigest = ACCOUNT_DIGEST,
        balances = balances(btcQuantity, ethQuantity, usdQuantity),
        prices = prices(btcPrice, ethPrice),
        now = NOW.plusSeconds(10),
    )

    private fun balances(btc: String, eth: String, usd: String): DirectBalanceCapture = DirectBalanceCapture(
        requestStartedAt = NOW,
        responseEndedAt = NOW.plusSeconds(1),
        resultShapeValid = true,
        valuesByAssetId = mapOf(
            "XXBT" to evidence(btc),
            "XETH" to evidence(eth),
            "ZUSD" to evidence(usd),
        ),
    )

    private fun prices(btc: String?, eth: String?): DirectTickerCapture = DirectTickerCapture(
        requestStartedAt = NOW.plusSeconds(2),
        responseEndedAt = NOW.plusSeconds(3),
        resultShapeValid = true,
        marksBySymbol = buildMap {
            if (btc != null) put("BTC", mark("BTC", "XBTUSD", "XXBTZUSD", btc))
            if (eth != null) put("ETH", mark("ETH", "ETHUSD", "XETHZUSD", eth))
        },
    )

    private fun mark(symbol: String, request: String, response: String, value: String) = DirectTickerMark(
        symbol = symbol,
        requestedPair = request,
        responsePair = response,
        candidateResponsePairs = listOf(response),
        rawPrice = value,
        price = BigDecimal(value),
        status = if (BigDecimal(value).signum() > 0) DirectEvidenceStatus.VALID else DirectEvidenceStatus.NON_POSITIVE,
        candidateValuesByPair = mapOf(response to evidence(value, allowZero = false)),
    )

    private fun evidence(value: String, allowZero: Boolean = true) = DirectValueEvidence(
        rawValue = value,
        value = BigDecimal(value),
        status = when {
            BigDecimal(value).signum() < 0 -> DirectEvidenceStatus.NEGATIVE
            !allowZero && BigDecimal(value).signum() <= 0 -> DirectEvidenceStatus.NON_POSITIVE
            else -> DirectEvidenceStatus.VALID
        },
    )

    private companion object {
        val NOW = Instant.parse("2026-10-09T12:00:00Z")
        val ACCOUNT_DIGEST = "a".repeat(64)
        val SYMBOLS = listOf("BTC", "ETH", "USD")
    }
}
