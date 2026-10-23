package com.gemini.krakenbot.service.history

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.model.KrakenAssetMetadata
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.service.DirectBalanceCapture
import com.gemini.krakenbot.service.DirectEvidenceStatus
import com.gemini.krakenbot.service.DirectTickerCapture
import com.gemini.krakenbot.service.DirectTickerMark
import com.gemini.krakenbot.service.DirectValueEvidence
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.math.BigDecimal
import java.time.Instant

class HistoryObservationValuatorTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    private val accountDigest = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    private val config = TestFixtures.DEFAULT_TEST_CONFIG

    init {
        "scopeSymbols normalizes, dedupes, and sorts asset symbols" {
            val symbols = HistoryObservationValuator.scopeSymbols(config)
            symbols shouldBe listOf("BTC", "ETH", "USD")
        }

        "scopeFingerprint produces deterministic SHA-256" {
            val fp1 = HistoryObservationValuator.scopeFingerprint(listOf("BTC", "USD"))
            val fp2 = HistoryObservationValuator.scopeFingerprint(listOf("BTC", "USD"))
            val fp3 = HistoryObservationValuator.scopeFingerprint(listOf("ETH", "USD"))

            fp1.length shouldBe 64
            fp1 shouldBe fp2
            fp1 shouldNotBe fp3
        }

        "accountScope and simulationScope create valid scopes" {
            val acc = HistoryObservationValuator.accountScope(config, accountDigest)
            acc.mode shouldBe HistorySourceMode.ACCOUNT
            acc.accountIdentityDigest shouldBe accountDigest

            val sim = HistoryObservationValuator.simulationScope(config, "sim-1")
            sim.mode shouldBe HistorySourceMode.SIMULATION
            sim.simulationSessionId shouldBe "sim-1"
        }

        "full-account direct valuation aggregates raw partitions independently of managed scope" {
            val now = Instant.now()
            val balances = DirectBalanceCapture(
                requestStartedAt = now.minusMillis(500),
                responseEndedAt = now.minusMillis(300),
                resultShapeValid = true,
                valuesByAssetId = mapOf(
                    "XXBT" to directBalance("1.0"),
                    "XBT.M" to directBalance("0.5"),
                    "XETH" to directBalance("2"),
                    "ZUSD" to directBalance("100"),
                    "USD" to directBalance("50"),
                    "STRC" to directBalance("3"),
                ),
            )
            val prices = DirectTickerCapture(
                requestStartedAt = now.minusMillis(300),
                responseEndedAt = now.minusMillis(100),
                resultShapeValid = true,
                marksBySymbol = mapOf(
                    "BTC" to directTicker("BTC", "50000"),
                    "ETH" to directTicker("ETH", "3000"),
                    "STRC" to directTicker("STRC", "4"),
                ),
            )
            val metadata = listOf(
                KrakenAssetMetadata("XXBT", "currency"),
                KrakenAssetMetadata("XETH", "currency"),
                KrakenAssetMetadata("ZUSD", "currency"),
                KrakenAssetMetadata("USD", "currency"),
                KrakenAssetMetadata("STRC", "tokenized_asset"),
            )

            val observation = HistoryObservationValuator.buildFullAccountDirect(
                observationId = "full-account-sample",
                cycleId = "cycle-full-account-sample",
                accountIdentityDigest = accountDigest,
                balances = balances,
                prices = prices,
                assetMetadata = metadata,
                now = now,
            )

            observation.sourceScope shouldBe HistoryObservationValuator.fullAccountScope(accountDigest)
            observation.sourceScope shouldNotBe HistoryObservationValuator.accountScope(config, accountDigest)
            observation.status shouldBe HistoryObservationStatus.COMPLETE
            observation.totalUsd shouldBe BigDecimal("81162.0")
            observation.assets.single { it.symbol == "BTC" }.quantity shouldBe BigDecimal("1.5")
            observation.assets.single { it.symbol == "BTC" }.rawBalanceKeys shouldBe listOf("XBT.M", "XXBT")
            observation.assets.single { it.symbol == "USD" }.quantity shouldBe BigDecimal("150")
            observation.assets.single { it.symbol == "USD" }.priceUsd shouldBe BigDecimal.ONE
            observation.assets.single { it.symbol == "USD" }.valueUsd shouldBe BigDecimal("150")
            observation.assets.single { it.symbol == "STRC" }.valueUsd shouldBe BigDecimal("12")
            HistoryObservationValuator.fullAccountTickerSymbols(balances, prices, metadata) shouldBe emptyList()
        }

        "full-account valuation handles zero balances, equity, unsupported classes, and bad marks" {
            val now = Instant.now()
            val balances = DirectBalanceCapture(
                requestStartedAt = now.minusMillis(500),
                responseEndedAt = now.minusMillis(300),
                resultShapeValid = true,
                valuesByAssetId = mapOf(
                    "XXBT" to directBalance("0"),
                    "XETH" to directBalance("2"),
                    "ZUSD" to directBalance("100"),
                    "ADA" to directBalance("1"),
                    "XLM" to directBalance("1"),
                    "XMR" to directBalance("1"),
                    "AAPL" to directBalance("1"),
                ),
            )
            val prices = DirectTickerCapture(
                requestStartedAt = now.minusMillis(300),
                responseEndedAt = now.minusMillis(100),
                resultShapeValid = true,
                marksBySymbol = mapOf(
                    "ETH" to directTicker("ETH", "3000"),
                    "XLM" to directTicker("XLM", "0").copy(status = DirectEvidenceStatus.MALFORMED),
                ),
            )
            val metadata = listOf(
                KrakenAssetMetadata("XXBT", "currency"),
                KrakenAssetMetadata("XETH", "currency"),
                KrakenAssetMetadata("ZUSD", "currency"),
                KrakenAssetMetadata("ADA", "currency"),
                KrakenAssetMetadata("XLM", "currency"),
                KrakenAssetMetadata("XMR", "margin"),
                KrakenAssetMetadata("AAPL", "equity"),
            )

            val observation = HistoryObservationValuator.buildFullAccountDirect(
                observationId = "mixed-direct-evidence",
                cycleId = "cycle-mixed-direct-evidence",
                accountIdentityDigest = accountDigest,
                balances = balances,
                prices = prices,
                assetMetadata = metadata,
                now = now,
            )

            observation.status shouldBe HistoryObservationStatus.INCOMPLETE
            observation.totalUsd.shouldBeNull()
            observation.assets.map { it.symbol } shouldBe listOf("ADA", "BTC", "ETH", "USD", "XLM", "XMR")
            observation.assets.single { it.symbol == "BTC" }.valueUsd shouldBe BigDecimal.ZERO
            observation.assets.single { it.symbol == "ADA" }.status shouldBe HistoryAssetValueStatus.MISSING_PRICE
            observation.assets.single { it.symbol == "XLM" }.status shouldBe HistoryAssetValueStatus.INVALID_PRICE
            observation.assets.single { it.symbol == "XMR" }.status shouldBe
                HistoryAssetValueStatus.UNAVAILABLE_CYCLE_EVIDENCE
            HistoryObservationValuator.fullAccountTickerSymbols(balances, prices, metadata) shouldBe
                listOf("ADA", "XLM")
        }

        "full-account direct valuation leaves missing metadata and ticker evidence incomplete" {
            val now = Instant.now()
            val balances = DirectBalanceCapture(
                now.minusMillis(400),
                now.minusMillis(200),
                true,
                mapOf("XLM" to directBalance("2")),
            )
            val prices = DirectTickerCapture(now.minusMillis(200), now.minusMillis(100), true, emptyMap())
            val unknownMetadata = HistoryObservationValuator.buildFullAccountDirect(
                "unknown-metadata",
                "cycle-unknown-metadata",
                accountDigest,
                balances,
                prices,
                emptyList(),
                now = now,
            )
            unknownMetadata.status shouldBe HistoryObservationStatus.INCOMPLETE
            unknownMetadata.totalUsd.shouldBeNull()
            unknownMetadata.assets.single().status shouldBe HistoryAssetValueStatus.UNAVAILABLE_CYCLE_EVIDENCE

            val missingTicker = HistoryObservationValuator.buildFullAccountDirect(
                "missing-ticker",
                "cycle-missing-ticker",
                accountDigest,
                balances,
                prices,
                listOf(KrakenAssetMetadata("XLM", "currency")),
                now = now,
            )
            missingTicker.status shouldBe HistoryObservationStatus.INCOMPLETE
            missingTicker.totalUsd.shouldBeNull()
            missingTicker.assets.single().status shouldBe HistoryAssetValueStatus.MISSING_PRICE
            HistoryObservationValuator.fullAccountTickerSymbols(
                balances,
                prices,
                listOf(KrakenAssetMetadata("XLM", "currency")),
            ) shouldBe listOf("XLM")
        }

        "full-account direct valuation fails closed for valid-status balances without parsed values" {
            val now = Instant.now()
            val balances = DirectBalanceCapture(
                now.minusMillis(400),
                now.minusMillis(200),
                true,
                mapOf(
                    "XXLM" to
                        DirectValueEvidence(
                            rawValue = "not-a-number",
                            value = null,
                            status = DirectEvidenceStatus.VALID,
                        ),
                ),
            )
            val prices = DirectTickerCapture(
                now.minusMillis(200),
                now.minusMillis(100),
                true,
                mapOf("XLM" to directTicker("XLM", "0.25")),
            )
            val metadata = listOf(KrakenAssetMetadata("XXLM", "currency"))

            val observation = HistoryObservationValuator.buildFullAccountDirect(
                "null-balance-value",
                "cycle-null-balance-value",
                accountDigest,
                balances,
                prices,
                metadata,
                now = now,
            )

            observation.status shouldBe HistoryObservationStatus.INCOMPLETE
            observation.totalUsd shouldBe null
            observation.assets.single().status shouldBe HistoryAssetValueStatus.INVALID_BALANCE
            HistoryObservationValuator.fullAccountTickerSymbols(balances, null, metadata) shouldBe emptyList()
        }

        "full-account direct valuation excludes known equity asset from ledger and produces complete total" {
            val now = Instant.now()
            val balances = DirectBalanceCapture(
                requestStartedAt = now.minusMillis(500),
                responseEndedAt = now.minusMillis(300),
                resultShapeValid = true,
                valuesByAssetId = mapOf(
                    "XXBT" to directBalance("0.5"),
                    "XETH" to directBalance("2.0"),
                    "ZUSD" to directBalance("100.0"),
                    "STRC" to directBalance("10.0"),
                ),
            )
            val prices = DirectTickerCapture(
                requestStartedAt = now.minusMillis(300),
                responseEndedAt = now.minusMillis(100),
                resultShapeValid = true,
                marksBySymbol = mapOf(
                    "BTC" to directTicker("BTC", "50000"),
                    "ETH" to directTicker("ETH", "3000"),
                ),
            )
            // Production metadata does NOT contain STRC (public Assets does not return equities)
            val metadata = listOf(
                KrakenAssetMetadata("XXBT", "currency"),
                KrakenAssetMetadata("XETH", "currency"),
                KrakenAssetMetadata("ZUSD", "currency"),
            )
            // Authoritative ledger classes supply the equity classification
            val ledgerClasses = mapOf("STRC" to "equity")

            val observation = HistoryObservationValuator.buildFullAccountDirect(
                observationId = "full-account-equity-exclusion",
                cycleId = "cycle-equity-exclusion",
                accountIdentityDigest = accountDigest,
                balances = balances,
                prices = prices,
                assetMetadata = metadata,
                authoritativeAssetClasses = ledgerClasses,
                now = now,
            )

            observation.status shouldBe HistoryObservationStatus.COMPLETE
            observation.totalUsd shouldBe BigDecimal("31100.0")
            // STRC is excluded from the valuation assets
            observation.assets.map { it.symbol } shouldBe listOf("BTC", "ETH", "USD")
            observation.assets.single { it.symbol == "BTC" }.valueUsd shouldBe BigDecimal("25000.0")
            observation.assets.single { it.symbol == "ETH" }.valueUsd shouldBe BigDecimal("6000.0")
            observation.assets.single { it.symbol == "USD" }.valueUsd shouldBe BigDecimal("100.0")

            // STRC is not requested for ticker marks
            HistoryObservationValuator.fullAccountTickerSymbols(
                balances = balances,
                prices = prices,
                assetMetadata = metadata,
                authoritativeAssetClasses = ledgerClasses,
            ) shouldBe emptyList()
        }

        "full-account direct valuation excludes multiple equity holdings and preserves USD cash" {
            val now = Instant.now()
            val balances = DirectBalanceCapture(
                requestStartedAt = now.minusMillis(500),
                responseEndedAt = now.minusMillis(300),
                resultShapeValid = true,
                valuesByAssetId = mapOf(
                    "XXBT" to directBalance("1.0"),
                    "ZUSD" to directBalance("500.0"),
                    "STRC" to directBalance("25.0"),
                    "AAPL" to directBalance("10.0"),
                ),
            )
            val prices = DirectTickerCapture(
                requestStartedAt = now.minusMillis(300),
                responseEndedAt = now.minusMillis(100),
                resultShapeValid = true,
                marksBySymbol = mapOf("BTC" to directTicker("BTC", "60000")),
            )
            val metadata = listOf(
                KrakenAssetMetadata("XXBT", "currency"),
                KrakenAssetMetadata("ZUSD", "currency"),
            )
            val ledgerClasses = mapOf("STRC" to "equity", "AAPL" to "equity")

            val observation = HistoryObservationValuator.buildFullAccountDirect(
                observationId = "multi-equity",
                cycleId = "cycle-multi-equity",
                accountIdentityDigest = accountDigest,
                balances = balances,
                prices = prices,
                assetMetadata = metadata,
                authoritativeAssetClasses = ledgerClasses,
                now = now,
            )

            observation.status shouldBe HistoryObservationStatus.COMPLETE
            observation.totalUsd shouldBe BigDecimal("60500.0")
            observation.assets.map { it.symbol } shouldBe listOf("BTC", "USD")
        }

        "full-account direct valuation fails closed when metadata and ledger asset class conflict" {
            val now = Instant.now()
            val balances = DirectBalanceCapture(
                requestStartedAt = now.minusMillis(500),
                responseEndedAt = now.minusMillis(300),
                resultShapeValid = true,
                valuesByAssetId = mapOf(
                    "CONFLICT" to directBalance("10.0"),
                    "ZUSD" to directBalance("100.0"),
                ),
            )
            val prices = DirectTickerCapture(
                requestStartedAt = now.minusMillis(300),
                responseEndedAt = now.minusMillis(100),
                resultShapeValid = true,
                marksBySymbol = mapOf("CONFLICT" to directTicker("CONFLICT", "10")),
            )
            val metadata = listOf(
                KrakenAssetMetadata("CONFLICT", "currency"),
                KrakenAssetMetadata("ZUSD", "currency"),
            )
            val ledgerClasses = mapOf("CONFLICT" to "equity")

            val observation = HistoryObservationValuator.buildFullAccountDirect(
                observationId = "conflict",
                cycleId = "cycle-conflict",
                accountIdentityDigest = accountDigest,
                balances = balances,
                prices = prices,
                assetMetadata = metadata,
                authoritativeAssetClasses = ledgerClasses,
                now = now,
            )

            observation.status shouldBe HistoryObservationStatus.INCOMPLETE
            observation.totalUsd shouldBe null
            observation.assets.single { it.symbol == "CONFLICT" }.status shouldBe
                HistoryAssetValueStatus.UNAVAILABLE_CYCLE_EVIDENCE
            observation.assets.single { it.symbol == "CONFLICT" }.reason shouldBe
                "Kraken asset metadata conflicts with account ledger classification."
        }

        "full-account direct valuation handles all-cash account without ticker requests" {
            val now = Instant.now()
            val balances = DirectBalanceCapture(
                requestStartedAt = now.minusMillis(500),
                responseEndedAt = now.minusMillis(300),
                resultShapeValid = true,
                valuesByAssetId = mapOf("ZUSD" to directBalance("12345.67")),
            )
            val prices = DirectTickerCapture(
                requestStartedAt = now.minusMillis(300),
                responseEndedAt = now.minusMillis(100),
                resultShapeValid = true,
                marksBySymbol = emptyMap(),
            )
            val metadata = listOf(KrakenAssetMetadata("ZUSD", "currency"))

            val observation = HistoryObservationValuator.buildFullAccountDirect(
                observationId = "all-cash",
                cycleId = "cycle-all-cash",
                accountIdentityDigest = accountDigest,
                balances = balances,
                prices = prices,
                assetMetadata = metadata,
                now = now,
            )

            observation.status shouldBe HistoryObservationStatus.COMPLETE
            observation.totalUsd shouldBe BigDecimal("12345.67")
            observation.assets.single().symbol shouldBe "USD"
            observation.assets.single().valueUsd shouldBe BigDecimal("12345.67")
            HistoryObservationValuator.fullAccountTickerSymbols(balances, prices, metadata) shouldBe emptyList()
        }

        "full-account direct valuation includes unconfigured crypto holdings via ticker requests" {
            val now = Instant.now()
            val balances = DirectBalanceCapture(
                requestStartedAt = now.minusMillis(500),
                responseEndedAt = now.minusMillis(300),
                resultShapeValid = true,
                valuesByAssetId = mapOf(
                    "XXBT" to directBalance("1.0"),
                    "SOL" to directBalance("50.0"),
                    "ZUSD" to directBalance("200.0"),
                ),
            )
            val metadata = listOf(
                KrakenAssetMetadata("XXBT", "currency"),
                KrakenAssetMetadata("SOL", "currency"),
                KrakenAssetMetadata("ZUSD", "currency"),
            )
            val missing = HistoryObservationValuator.fullAccountTickerSymbols(
                balances = balances,
                prices = DirectTickerCapture(now, now, true, mapOf("BTC" to directTicker("BTC", "50000"))),
                assetMetadata = metadata,
            )
            missing shouldBe listOf("SOL")

            val prices = DirectTickerCapture(
                requestStartedAt = now.minusMillis(300),
                responseEndedAt = now.minusMillis(100),
                resultShapeValid = true,
                marksBySymbol = mapOf(
                    "BTC" to directTicker("BTC", "50000"),
                    "SOL" to directTicker("SOL", "150"),
                ),
            )
            val observation = HistoryObservationValuator.buildFullAccountDirect(
                observationId = "unconfigured-crypto",
                cycleId = "cycle-unconfigured-crypto",
                accountIdentityDigest = accountDigest,
                balances = balances,
                prices = prices,
                assetMetadata = metadata,
                now = now,
            )

            observation.status shouldBe HistoryObservationStatus.COMPLETE
            observation.totalUsd shouldBe BigDecimal("57700.0")
            observation.assets.map { it.symbol } shouldBe listOf("BTC", "SOL", "USD")
        }

        "buildIncompleteFullAccountDirect preserves balances and honest reasons while excluding equities" {
            val now = Instant.now()
            val balances = DirectBalanceCapture(
                requestStartedAt = now.minusMillis(500),
                responseEndedAt = now.minusMillis(300),
                resultShapeValid = true,
                valuesByAssetId = mapOf(
                    "XXBT" to directBalance("1.0"),
                    "ZUSD" to directBalance("500.0"),
                    "STRC" to directBalance("15.0"),
                ),
            )
            val ledgerClasses = mapOf("STRC" to "equity")

            val observation = HistoryObservationValuator.buildIncompleteFullAccountDirect(
                observationId = "incomplete-evidence",
                cycleId = "cycle-incomplete",
                accountIdentityDigest = accountDigest,
                balances = balances,
                reason = "Full-account evidence collection timed out.",
                authoritativeAssetClasses = ledgerClasses,
                now = now,
            )

            observation.status shouldBe HistoryObservationStatus.INCOMPLETE
            observation.totalUsd shouldBe null
            observation.incompleteReasons shouldBe listOf("Full-account evidence collection timed out.")
            observation.assets.map { it.symbol } shouldBe listOf("BTC", "USD")
            observation.assets.single { it.symbol == "USD" }.let {
                it.status shouldBe HistoryAssetValueStatus.COMPLETE
                it.valueUsd shouldBe BigDecimal("500.0")
            }
            observation.assets.single { it.symbol == "BTC" }.let {
                it.status shouldBe HistoryAssetValueStatus.UNAVAILABLE_CYCLE_EVIDENCE
                it.valueUsd shouldBe null
                it.reason shouldBe "Full-account evidence collection timed out."
            }
        }

        "full-account valuation uses ledger classes and rejects ambiguous public or ledger classes" {
            val now = Instant.now()
            val balances = DirectBalanceCapture(
                requestStartedAt = now.minusMillis(500),
                responseEndedAt = now.minusMillis(300),
                resultShapeValid = true,
                valuesByAssetId = mapOf(
                    "ALT" to directBalance("2"),
                    "TOKEN" to directBalance("3"),
                    "AMBIG" to directBalance("4"),
                    "CONFLICT" to directBalance("5"),
                    "PUBLIC_AMBIG" to directBalance("6"),
                    "ZUSD" to directBalance("100"),
                ),
            )
            val prices = DirectTickerCapture(
                requestStartedAt = now.minusMillis(300),
                responseEndedAt = now.minusMillis(100),
                resultShapeValid = true,
                marksBySymbol = emptyMap(),
            )
            val metadata = listOf(
                KrakenAssetMetadata("TOKEN", "tokenized_asset"),
                KrakenAssetMetadata("AMBIG", "currency"),
                KrakenAssetMetadata("CONFLICT", "tokenized_asset"),
                KrakenAssetMetadata("PUBLIC_AMBIG", "currency"),
                KrakenAssetMetadata("PUBLIC_AMBIG", "tokenized_asset"),
                KrakenAssetMetadata("ZUSD", "currency"),
            )
            val classes = mapOf(
                "ALT" to "currency",
                "TOKEN" to "tokenized_asset",
                "AMBIG" to "ambiguous",
                "CONFLICT" to "currency",
            )

            val observation = HistoryObservationValuator.buildFullAccountDirect(
                observationId = "asset-class-resolution",
                cycleId = "cycle-asset-class-resolution",
                accountIdentityDigest = accountDigest,
                balances = balances,
                prices = prices,
                assetMetadata = metadata,
                authoritativeAssetClasses = classes,
                now = now,
            )

            observation.status shouldBe HistoryObservationStatus.INCOMPLETE
            observation.totalUsd shouldBe null
            observation.assets.single { it.symbol == "ALT" }.status shouldBe HistoryAssetValueStatus.MISSING_PRICE
            observation.assets.single { it.symbol == "TOKEN" }.status shouldBe HistoryAssetValueStatus.MISSING_PRICE
            observation.assets.single { it.symbol == "AMBIG" }.reason shouldBe
                "Kraken account ledger history reports conflicting asset classes for this balance."
            observation.assets.single { it.symbol == "CONFLICT" }.reason shouldBe
                "Kraken asset metadata conflicts with account ledger classification."
            observation.assets.single { it.symbol == "PUBLIC_AMBIG" }.reason shouldBe
                "Kraken asset metadata is ambiguous for this balance."
            HistoryObservationValuator.fullAccountTickerSymbols(
                balances = balances,
                prices = prices,
                assetMetadata = metadata,
                authoritativeAssetClasses = classes,
            ) shouldBe listOf("ALT", "TOKEN")
        }

        "full-account valuation fails closed when raw balance partitions disagree on class" {
            val now = Instant.now()
            val balances = DirectBalanceCapture(
                requestStartedAt = now.minusMillis(500),
                responseEndedAt = now.minusMillis(300),
                resultShapeValid = true,
                valuesByAssetId = mapOf(
                    "XXBT" to directBalance("1"),
                    "XBT.M" to directBalance("0.5"),
                    "ZUSD" to directBalance("100"),
                ),
            )
            val prices = DirectTickerCapture(
                requestStartedAt = now.minusMillis(300),
                responseEndedAt = now.minusMillis(100),
                resultShapeValid = true,
                marksBySymbol = mapOf("BTC" to directTicker("BTC", "50000")),
            )
            val metadata = listOf(
                KrakenAssetMetadata("XXBT", "currency"),
                KrakenAssetMetadata("XBT.M", "tokenized_asset"),
                KrakenAssetMetadata("ZUSD", "currency"),
            )
            val classes = mapOf("XXBT" to "currency", "XBT.M" to "tokenized_asset")

            val observation = HistoryObservationValuator.buildFullAccountDirect(
                observationId = "partition-class-conflict",
                cycleId = "cycle-partition-class-conflict",
                accountIdentityDigest = accountDigest,
                balances = balances,
                prices = prices,
                assetMetadata = metadata,
                authoritativeAssetClasses = classes,
                now = now,
            )

            observation.status shouldBe HistoryObservationStatus.INCOMPLETE
            observation.totalUsd shouldBe null
            observation.assets.single { it.symbol == "BTC" }.reason shouldBe
                "Kraken asset metadata conflicts across balance partitions."
            HistoryObservationValuator.fullAccountTickerSymbols(
                balances = balances,
                prices = null,
                assetMetadata = metadata,
                authoritativeAssetClasses = classes,
            ) shouldBe emptyList()
        }

        "incomplete full-account observations retain invalid balance status without valuing it" {
            val now = Instant.now()
            val balances = DirectBalanceCapture(
                requestStartedAt = now.minusMillis(500),
                responseEndedAt = now.minusMillis(300),
                resultShapeValid = true,
                valuesByAssetId = mapOf(
                    "XXBT" to directBalance("1"),
                    "XETH" to DirectValueEvidence("-2", BigDecimal("-2"), DirectEvidenceStatus.VALID),
                    "ZUSD" to DirectValueEvidence("invalid", null, DirectEvidenceStatus.MALFORMED),
                    "STRC" to directBalance("15"),
                ),
            )

            val observation = HistoryObservationValuator.buildIncompleteFullAccountDirect(
                observationId = "invalid-incomplete-balances",
                cycleId = "cycle-invalid-incomplete-balances",
                accountIdentityDigest = accountDigest,
                balances = balances,
                reason = "Price evidence timed out.",
                authoritativeAssetClasses = mapOf("STRC" to "equity"),
                now = now,
            )

            observation.totalUsd shouldBe null
            observation.assets.map { it.symbol } shouldBe listOf("BTC", "ETH", "USD")
            observation.assets.single { it.symbol == "BTC" }.status shouldBe
                HistoryAssetValueStatus.UNAVAILABLE_CYCLE_EVIDENCE
            observation.assets.single { it.symbol == "ETH" }.status shouldBe HistoryAssetValueStatus.INVALID_BALANCE
            observation.assets.single { it.symbol == "USD" }.let {
                it.status shouldBe HistoryAssetValueStatus.INVALID_BALANCE
                it.valueUsd shouldBe null
                it.reason shouldBe "Balance amount is invalid or negative."
            }
        }

        "buildDirect constructs complete observation from valid balances and tickers" {
            val now = Instant.now()
            val scope = HistoryObservationValuator.accountScope(config, accountDigest)

            val balances = DirectBalanceCapture(
                requestStartedAt = now.minusMillis(500),
                responseEndedAt = now.minusMillis(200),
                resultShapeValid = true,
                valuesByAssetId = mapOf(
                    "XXBT" to DirectValueEvidence("1.0", BigDecimal("1.0"), DirectEvidenceStatus.VALID),
                    "XETH" to DirectValueEvidence("10.0", BigDecimal("10.0"), DirectEvidenceStatus.VALID),
                    "ZUSD" to DirectValueEvidence("1000.0", BigDecimal("1000.0"), DirectEvidenceStatus.VALID),
                ),
            )

            val prices = DirectTickerCapture(
                requestStartedAt = now.minusMillis(200),
                responseEndedAt = now.minusMillis(50),
                resultShapeValid = true,
                marksBySymbol = mapOf(
                    "BTC" to DirectTickerMark(
                        symbol = "BTC",
                        requestedPair = "XXBTZUSD",
                        responsePair = "XXBTZUSD",
                        status = DirectEvidenceStatus.VALID,
                        price = BigDecimal("50000"),
                        rawPrice = "50000",
                        candidateResponsePairs = listOf("XXBTZUSD"),
                        candidateValuesByPair = mapOf(
                            "XXBTZUSD" to DirectValueEvidence("50000", BigDecimal("50000"), DirectEvidenceStatus.VALID),
                        ),
                    ),
                    "ETH" to DirectTickerMark(
                        symbol = "ETH",
                        requestedPair = "XETHZUSD",
                        responsePair = "XETHZUSD",
                        status = DirectEvidenceStatus.VALID,
                        price = BigDecimal("3000"),
                        rawPrice = "3000",
                        candidateResponsePairs = listOf("XETHZUSD"),
                        candidateValuesByPair = mapOf(
                            "XETHZUSD" to DirectValueEvidence("3000", BigDecimal("3000"), DirectEvidenceStatus.VALID),
                        ),
                    ),
                ),
            )

            val obs = HistoryObservationValuator.buildDirect(
                observationId = "obs-dir-1",
                cycleId = "c-1",
                sourceScope = scope,
                balances = balances,
                prices = prices,
                now = now,
            )

            obs.status shouldBe HistoryObservationStatus.COMPLETE
            // BTC: 1 * 50,000 = 50,000
            // ETH: 10 * 3,000 = 30,000
            // USD: 1,000 * 1 = 1,000
            // Total = 81,000
            obs.totalUsd shouldBe BigDecimal("81000.0")
            obs.assets.size shouldBe 3
        }

        "buildDirect marks observation incomplete on missing price or balance" {
            val now = Instant.now()
            val scope = HistoryObservationValuator.accountScope(config, accountDigest)

            val balances = DirectBalanceCapture(
                requestStartedAt = now.minusMillis(500),
                responseEndedAt = now.minusMillis(200),
                resultShapeValid = true,
                valuesByAssetId = mapOf(
                    "XXBT" to DirectValueEvidence("1.0", BigDecimal("1.0"), DirectEvidenceStatus.VALID),
                    // ETH missing
                    "ZUSD" to DirectValueEvidence("1000.0", BigDecimal("1000.0"), DirectEvidenceStatus.VALID),
                ),
            )

            val prices = DirectTickerCapture(
                requestStartedAt = now.minusMillis(200),
                responseEndedAt = now.minusMillis(50),
                resultShapeValid = true,
                marksBySymbol = mapOf(
                    "BTC" to DirectTickerMark(
                        symbol = "BTC",
                        requestedPair = "XXBTZUSD",
                        responsePair = "XXBTZUSD",
                        candidateResponsePairs = listOf("XXBTZUSD"),
                        status = DirectEvidenceStatus.VALID,
                        price = BigDecimal("50000"),
                        rawPrice = "50000",
                    ),
                ),
            )

            val obs = HistoryObservationValuator.buildDirect(
                observationId = "obs-dir-2",
                cycleId = "c-2",
                sourceScope = scope,
                balances = balances,
                prices = prices,
                now = now,
            )

            obs.status shouldBe HistoryObservationStatus.INCOMPLETE
            obs.totalUsd shouldBe null
            obs.incompleteReasons.isNotEmpty() shouldBe true
        }

        "buildDirect detects timing and window violations" {
            val now = Instant.now()
            val scope = HistoryObservationValuator.accountScope(config, accountDigest)

            // Gap > 60 seconds
            val balances = DirectBalanceCapture(
                requestStartedAt = now.minusSeconds(100),
                responseEndedAt = now.minusSeconds(90),
                resultShapeValid = true,
                valuesByAssetId = emptyMap(),
            )
            val prices = DirectTickerCapture(
                requestStartedAt = now.minusSeconds(10),
                responseEndedAt = now,
                resultShapeValid = true,
                marksBySymbol = emptyMap(),
            )

            val obs = HistoryObservationValuator.buildDirect(
                observationId = "obs-timing",
                cycleId = "c-timing",
                sourceScope = scope,
                balances = balances,
                prices = prices,
                now = now,
            )

            obs.status shouldBe HistoryObservationStatus.INCOMPLETE
            obs.assets.any { it.status == HistoryAssetValueStatus.INVALID_TIME_WINDOW } shouldBe true
        }

        "buildDirect rejects fractional durations beyond both evidence cutoffs" {
            val now = Instant.parse("2026-10-15T12:00:00Z")
            val scope = HistoryObservationValuator.accountScope(config, accountDigest)
            val balances = DirectBalanceCapture(
                requestStartedAt = now.minusSeconds(90).minusMillis(500),
                responseEndedAt = now.minusSeconds(60).minusMillis(500),
                resultShapeValid = true,
                valuesByAssetId = emptyMap(),
            )
            val prices = DirectTickerCapture(
                requestStartedAt = now,
                responseEndedAt = now,
                resultShapeValid = true,
                marksBySymbol = emptyMap(),
            )
            val excessiveGap = HistoryObservationValuator.buildDirect(
                "fractional-gap",
                "fractional-gap-cycle",
                scope,
                balances,
                prices,
                now = now,
            )
            excessiveGap.status shouldBe HistoryObservationStatus.INCOMPLETE
            excessiveGap.incompleteReasons.any { it.contains("more than 60 seconds") } shouldBe true

            val longWindowBalances = balances.copy(
                requestStartedAt = now.minusSeconds(120).minusMillis(500),
                responseEndedAt = now.minusSeconds(110),
            )
            val closePrices = prices.copy(requestStartedAt = now.minusSeconds(100))
            val excessiveWindow = HistoryObservationValuator.buildDirect(
                "fractional-window",
                "fractional-window-cycle",
                scope,
                longWindowBalances,
                closePrices,
                now = now,
            )
            excessiveWindow.status shouldBe HistoryObservationStatus.INCOMPLETE
            excessiveWindow.incompleteReasons.any { it.contains("exceeds 120 seconds") } shouldBe true
        }

        "buildDirect rejects simulation mode or future timestamps" {
            val now = Instant.now()
            val simScope = HistoryObservationValuator.simulationScope(config, "sim-1")
            val balances = DirectBalanceCapture(now, now, true, emptyMap())
            val prices = DirectTickerCapture(now, now, true, emptyMap())

            shouldThrow<IllegalArgumentException> {
                HistoryObservationValuator.buildDirect("obs-err", "c-err", simScope, balances, prices, now = now)
            }

            val accScope = HistoryObservationValuator.accountScope(config, accountDigest)
            shouldThrow<IllegalArgumentException> {
                // observed in future relative to now
                HistoryObservationValuator.buildDirect(
                    "obs-err",
                    "c-err",
                    accScope,
                    DirectBalanceCapture(now.plusSeconds(10), now.plusSeconds(10), true, emptyMap()),
                    prices,
                    now = now,
                )
            }
        }

        "buildSimulation produces valid complete observation from portfolio snapshot" {
            val simScope = HistoryObservationValuator.simulationScope(config, "sim-session")
            val now = Instant.now()

            val snapshot = PortfolioSnapshot(
                timestamp = now,
                totalValueUSD = BigDecimal("55000.00"),
                assets = mapOf(
                    "BTC" to TestFixtures.assetSnapshot(
                        "BTC",
                        BigDecimal("1.0"),
                        BigDecimal("50000.00"),
                        BigDecimal("50000.00"),
                        BigDecimal("50.00"),
                    ),
                    "USD" to TestFixtures.assetSnapshot(
                        "USD",
                        BigDecimal("5000.00"),
                        BigDecimal.ONE,
                        BigDecimal("5000.00"),
                        BigDecimal("20.00"),
                    ),
                    "ETH" to TestFixtures.assetSnapshot(
                        "ETH",
                        BigDecimal("0.0"),
                        BigDecimal("3000.00"),
                        BigDecimal("0.00"),
                        BigDecimal("30.00"),
                    ),
                ),
                actions = emptyList(),
                effectiveUsdTargetPercent = BigDecimal("20.00"),
            )

            val obs = HistoryObservationValuator.buildSimulation(
                cycleId = "sim-c1",
                sourceScope = simScope,
                snapshot = snapshot,
            )

            obs.status shouldBe HistoryObservationStatus.COMPLETE
            obs.totalUsd shouldBe BigDecimal("55000.00")
            obs.assets.size shouldBe 3
            obs.assets.all { it.status == HistoryAssetValueStatus.SIMULATED } shouldBe true
        }

        "directCaptureFromBalances maps amounts and negative flags" {
            val now = Instant.now()
            val raw = mapOf(
                "XXBT" to BigDecimal("1.5"),
                "ZUSD" to BigDecimal("-10.0"),
            )

            val captured = HistoryObservationValuator.directCaptureFromBalances(raw, now)
            captured.resultShapeValid shouldBe true
            captured.valuesByAssetId["XXBT"]?.status shouldBe DirectEvidenceStatus.VALID
            captured.valuesByAssetId["ZUSD"]?.status shouldBe DirectEvidenceStatus.NEGATIVE
        }

        "buildDirect maps ambiguous balance, invalid balance, missing price, and invalid price statuses" {
            val now = Instant.now()
            val scope = HistoryObservationValuator.accountScope(config, accountDigest)

            val balances = DirectBalanceCapture(
                requestStartedAt = now.minusMillis(500),
                responseEndedAt = now.minusMillis(200),
                resultShapeValid = true,
                valuesByAssetId = mapOf(
                    "BTC" to DirectValueEvidence("1.0", BigDecimal("1.0"), DirectEvidenceStatus.VALID),
                    "XXBT" to DirectValueEvidence("2.0", BigDecimal("2.0"), DirectEvidenceStatus.VALID),
                    "XETH" to DirectValueEvidence("-5.0", BigDecimal("-5.0"), DirectEvidenceStatus.NEGATIVE),
                    "ZUSD" to DirectValueEvidence("1000.0", BigDecimal("1000.0"), DirectEvidenceStatus.VALID),
                ),
            )

            val prices = DirectTickerCapture(
                requestStartedAt = now.minusMillis(200),
                responseEndedAt = now.minusMillis(50),
                resultShapeValid = true,
                marksBySymbol = mapOf(
                    "BTC" to DirectTickerMark(
                        symbol = "BTC",
                        requestedPair = "XXBTZUSD",
                        responsePair = "XXBTZUSD",
                        candidateResponsePairs = listOf("XXBTZUSD"),
                        status = DirectEvidenceStatus.VALID,
                        price = BigDecimal.ZERO,
                        rawPrice = "0",
                    ),
                    "ETH" to DirectTickerMark(
                        symbol = "ETH",
                        requestedPair = "XETHZUSD",
                        responsePair = "XETHZUSD",
                        candidateResponsePairs = listOf("XETHZUSD"),
                        status = DirectEvidenceStatus.MALFORMED,
                        price = null,
                        rawPrice = "NaN",
                    ),
                ),
            )

            val obs = HistoryObservationValuator.buildDirect(
                observationId = "obs-edge-1",
                cycleId = "c-edge-1",
                sourceScope = scope,
                balances = balances,
                prices = prices,
                now = now,
            )

            obs.status shouldBe HistoryObservationStatus.INCOMPLETE
            obs.totalUsd shouldBe null
            val btcAsset = obs.assets.single { it.symbol == "BTC" }
            btcAsset.status shouldBe HistoryAssetValueStatus.AMBIGUOUS_BALANCE
            val ethAsset = obs.assets.single { it.symbol == "ETH" }
            ethAsset.status shouldBe HistoryAssetValueStatus.INVALID_BALANCE
        }

        "buildDirect distinguishes missing and invalid ticker evidence from valid balances" {
            val now = Instant.now()
            val scope = HistoryObservationValuator.accountScope(config, accountDigest)
            val balances = DirectBalanceCapture(
                requestStartedAt = now,
                responseEndedAt = now,
                resultShapeValid = true,
                valuesByAssetId = mapOf(
                    "XXBT" to DirectValueEvidence("1", BigDecimal.ONE, DirectEvidenceStatus.VALID),
                    "XETH" to DirectValueEvidence("2", BigDecimal("2"), DirectEvidenceStatus.VALID),
                    "ZUSD" to DirectValueEvidence("100", BigDecimal("100"), DirectEvidenceStatus.VALID),
                ),
            )
            val prices = DirectTickerCapture(
                requestStartedAt = now,
                responseEndedAt = now,
                resultShapeValid = true,
                marksBySymbol = mapOf(
                    "ETH" to DirectTickerMark(
                        symbol = "ETH",
                        requestedPair = "XETHZUSD",
                        responsePair = "XETHZUSD",
                        candidateResponsePairs = emptyList(),
                        status = DirectEvidenceStatus.MALFORMED,
                        price = null,
                        rawPrice = "invalid",
                    ),
                ),
            )

            val observation = HistoryObservationValuator.buildDirect(
                observationId = "invalid-prices",
                cycleId = "invalid-prices-cycle",
                sourceScope = scope,
                balances = balances,
                prices = prices,
                now = now,
            )

            observation.status shouldBe HistoryObservationStatus.INCOMPLETE
            observation.totalUsd shouldBe null
            observation.assets.single { it.symbol == "BTC" }.status shouldBe HistoryAssetValueStatus.MISSING_PRICE
            observation.assets.single { it.symbol == "ETH" }.status shouldBe HistoryAssetValueStatus.INVALID_PRICE
            observation.assets.single { it.symbol == "USD" }.status shouldBe HistoryAssetValueStatus.COMPLETE
        }

        "buildIncompleteSimulation rejects an authenticated account scope" {
            shouldThrow<IllegalArgumentException> {
                HistoryObservationValuator.buildIncompleteSimulation(
                    cycleId = "account-scope",
                    sourceScope = HistoryObservationValuator.accountScope(config, accountDigest),
                    observedAt = Instant.now(),
                    reason = "simulation evidence unavailable",
                )
            }
        }

        "direct valuation rejects a simulation scope and simulation valuation rejects an account scope" {
            val now = Instant.now()
            val balances = DirectBalanceCapture(now, now, true, emptyMap())
            val prices = DirectTickerCapture(now, now, true, emptyMap())

            shouldThrow<IllegalArgumentException> {
                HistoryObservationValuator.buildDirect(
                    observationId = "simulation-scope",
                    cycleId = "simulation-scope-cycle",
                    sourceScope = HistoryObservationValuator.simulationScope(config, "sim-session"),
                    balances = balances,
                    prices = prices,
                )
            }
            shouldThrow<IllegalArgumentException> {
                HistoryObservationValuator.buildSimulation(
                    cycleId = "account-scope-cycle",
                    sourceScope = HistoryObservationValuator.accountScope(config, accountDigest),
                    snapshot = PortfolioSnapshot(
                        timestamp = now,
                        totalValueUSD = BigDecimal.ZERO,
                        assets = emptyMap(),
                        actions = emptyList(),
                        effectiveUsdTargetPercent = BigDecimal.ZERO,
                    ),
                )
            }
        }

        "buildDirect detects inverted timestamps, oversized window, and invalid result shape" {
            val now = Instant.now()
            val scope = HistoryObservationValuator.accountScope(config, accountDigest)

            // Inverted timestamps
            val invertedBalances = DirectBalanceCapture(
                requestStartedAt = now.minusMillis(100),
                responseEndedAt = now.minusMillis(200),
                resultShapeValid = true,
                valuesByAssetId = emptyMap(),
            )
            val validPrices = DirectTickerCapture(
                requestStartedAt = now.minusMillis(100),
                responseEndedAt = now.minusMillis(50),
                resultShapeValid = true,
                marksBySymbol = emptyMap(),
            )
            val obsInverted = HistoryObservationValuator.buildDirect(
                "obs-inv",
                "c-inv",
                scope,
                invertedBalances,
                validPrices,
                now = now,
            )
            obsInverted.status shouldBe HistoryObservationStatus.INCOMPLETE
            obsInverted.assets.all { it.status == HistoryAssetValueStatus.INVALID_TIME_WINDOW } shouldBe true

            // Window > 120s
            val longWindowBalances = DirectBalanceCapture(
                requestStartedAt = now.minusSeconds(150),
                responseEndedAt = now.minusSeconds(140),
                resultShapeValid = true,
                valuesByAssetId = emptyMap(),
            )
            val obsLong = HistoryObservationValuator.buildDirect(
                "obs-long",
                "c-long",
                scope,
                longWindowBalances,
                validPrices,
                now = now,
            )
            obsLong.status shouldBe HistoryObservationStatus.INCOMPLETE

            // Invalid shape
            val invalidShapePrices = validPrices.copy(resultShapeValid = false)
            val obsShape = HistoryObservationValuator.buildDirect(
                "obs-shape",
                "c-shape",
                scope,
                DirectBalanceCapture(now.minusMillis(100), now.minusMillis(50), true, emptyMap()),
                invalidShapePrices,
                now = now,
            )
            obsShape.status shouldBe HistoryObservationStatus.INCOMPLETE

            // Reverse gap: prices ended before balances started > 60s
            val earlyPrices = DirectTickerCapture(
                requestStartedAt = now.minusSeconds(100),
                responseEndedAt = now.minusSeconds(90),
                resultShapeValid = true,
                marksBySymbol = emptyMap(),
            )
            val lateBalances = DirectBalanceCapture(
                requestStartedAt = now.minusSeconds(10),
                responseEndedAt = now,
                resultShapeValid = true,
                valuesByAssetId = emptyMap(),
            )
            val obsRevGap = HistoryObservationValuator.buildDirect(
                "obs-rev",
                "c-rev",
                scope,
                lateBalances,
                earlyPrices,
                now = now,
            )
            obsRevGap.status shouldBe HistoryObservationStatus.INCOMPLETE
        }

        "buildSimulation marks observation INCOMPLETE on missing or invalid asset" {
            val simScope = HistoryObservationValuator.simulationScope(config, "sim-invalid")
            val now = Instant.now()

            // Missing ETH
            val snapshotMissing = PortfolioSnapshot(
                timestamp = now,
                totalValueUSD = BigDecimal("50000.00"),
                assets = mapOf(
                    "BTC" to TestFixtures.assetSnapshot(
                        "BTC",
                        BigDecimal("1.0"),
                        BigDecimal("50000.00"),
                        BigDecimal("50000.00"),
                        BigDecimal("100.00"),
                    ),
                ),
                actions = emptyList(),
                effectiveUsdTargetPercent = BigDecimal("20.00"),
            )
            val obsMissing = HistoryObservationValuator.buildSimulation("sim-mis", simScope, snapshotMissing)
            obsMissing.status shouldBe HistoryObservationStatus.INCOMPLETE
            obsMissing.totalUsd shouldBe null

            // Negative balance
            val snapshotNeg = PortfolioSnapshot(
                timestamp = now,
                totalValueUSD = BigDecimal("50000.00"),
                assets = mapOf(
                    "BTC" to TestFixtures.assetSnapshot(
                        "BTC",
                        BigDecimal("-1.0"),
                        BigDecimal("50000.00"),
                        BigDecimal("50000.00"),
                        BigDecimal("50.00"),
                    ),
                    "USD" to TestFixtures.assetSnapshot(
                        "USD",
                        BigDecimal("5000.00"),
                        BigDecimal.ONE,
                        BigDecimal("5000.00"),
                        BigDecimal("20.00"),
                    ),
                    "ETH" to TestFixtures.assetSnapshot(
                        "ETH",
                        BigDecimal("0.0"),
                        BigDecimal("3000.00"),
                        BigDecimal("0.00"),
                        BigDecimal("30.00"),
                    ),
                ),
                actions = emptyList(),
                effectiveUsdTargetPercent = BigDecimal("20.00"),
            )
            val obsNeg = HistoryObservationValuator.buildSimulation("sim-neg", simScope, snapshotNeg)
            obsNeg.status shouldBe HistoryObservationStatus.INCOMPLETE
            obsNeg.totalUsd shouldBe null

            // Non-positive price
            val snapshotZeroPrice = PortfolioSnapshot(
                timestamp = now,
                totalValueUSD = BigDecimal("50000.00"),
                assets = mapOf(
                    "BTC" to TestFixtures.assetSnapshot(
                        "BTC",
                        BigDecimal("1.0"),
                        BigDecimal.ZERO,
                        BigDecimal("0.00"),
                        BigDecimal("50.00"),
                    ),
                    "USD" to TestFixtures.assetSnapshot(
                        "USD",
                        BigDecimal("5000.00"),
                        BigDecimal.ONE,
                        BigDecimal("5000.00"),
                        BigDecimal("20.00"),
                    ),
                    "ETH" to TestFixtures.assetSnapshot(
                        "ETH",
                        BigDecimal("0.0"),
                        BigDecimal("3000.00"),
                        BigDecimal("0.00"),
                        BigDecimal("30.00"),
                    ),
                ),
                actions = emptyList(),
                effectiveUsdTargetPercent = BigDecimal("20.00"),
            )
            val obsZero = HistoryObservationValuator.buildSimulation("sim-zero", simScope, snapshotZeroPrice)
            obsZero.status shouldBe HistoryObservationStatus.INCOMPLETE
        }

        "buildDirect throws IllegalArgumentException on non-ACCOUNT scope" {
            val simScope = HistoryObservationValuator.simulationScope(config, "sim-non-acc")
            val now = Instant.now()
            shouldThrow<IllegalArgumentException> {
                HistoryObservationValuator.buildDirect(
                    "obs-fail",
                    "c-fail",
                    simScope,
                    DirectBalanceCapture(now.minusMillis(100), now, true, emptyMap()),
                    DirectTickerCapture(now.minusMillis(100), now, true, emptyMap()),
                    now = now,
                )
            }
        }

        "buildDirect throws IllegalArgumentException when observation timestamp is in the future" {
            val scope = HistoryObservationValuator.accountScope(config, accountDigest)
            val now = Instant.now()
            shouldThrow<IllegalArgumentException> {
                HistoryObservationValuator.buildDirect(
                    "obs-fut",
                    "c-fut",
                    scope,
                    DirectBalanceCapture(now.plusSeconds(10), now.plusSeconds(20), true, emptyMap()),
                    DirectTickerCapture(now.plusSeconds(10), now.plusSeconds(20), true, emptyMap()),
                    now = now,
                )
            }
        }

        "buildDirect accepts reverse gap within 60 seconds" {
            val scope = HistoryObservationValuator.accountScope(config, accountDigest)
            val now = Instant.now()
            val earlyPrices = DirectTickerCapture(
                requestStartedAt = now.minusSeconds(20),
                responseEndedAt = now.minusSeconds(15),
                resultShapeValid = true,
                marksBySymbol = emptyMap(),
            )
            val lateBalances = DirectBalanceCapture(
                requestStartedAt = now.minusSeconds(10),
                responseEndedAt = now.minusSeconds(5),
                resultShapeValid = true,
                valuesByAssetId = emptyMap(),
            )
            val obs = HistoryObservationValuator.buildDirect(
                "obs-valid-rev",
                "c-valid-rev",
                scope,
                lateBalances,
                earlyPrices,
                now = now,
            )
            // Balances are empty, but the time window is valid.
            obs.status shouldBe HistoryObservationStatus.INCOMPLETE
        }
    }
}

private fun directBalance(value: String) = DirectValueEvidence(value, BigDecimal(value), DirectEvidenceStatus.VALID)

private fun directTicker(symbol: String, value: String) = DirectTickerMark(
    symbol = symbol,
    requestedPair = "${symbol}USD",
    responsePair = "${symbol}USD",
    candidateResponsePairs = listOf("${symbol}USD"),
    rawPrice = value,
    price = BigDecimal(value),
    status = DirectEvidenceStatus.VALID,
    candidateValuesByPair = mapOf(
        "${symbol}USD" to DirectValueEvidence(value, BigDecimal(value), DirectEvidenceStatus.VALID),
    ),
)
