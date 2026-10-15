package com.gemini.krakenbot.service.history

import com.gemini.krakenbot.model.ExchangeLedgerHistoryEntry
import com.gemini.krakenbot.model.ExchangeTradeHistoryEntry
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant

class HistoryModelsTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    private val validHash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    init {
        "HistorySourceScope requires valid SHA-256 fingerprint" {
            shouldThrow<IllegalArgumentException> {
                HistorySourceScope(
                    mode = HistorySourceMode.ACCOUNT,
                    managedScopeFingerprint = "invalid-hash",
                    managedScopeSymbols = listOf("BTC", "USD"),
                    accountIdentityDigest = validHash,
                )
            }
        }

        "HistorySourceScope requires non-empty symbols" {
            shouldThrow<IllegalArgumentException> {
                HistorySourceScope(
                    mode = HistorySourceMode.ACCOUNT,
                    managedScopeFingerprint = validHash,
                    managedScopeSymbols = emptyList(),
                    accountIdentityDigest = validHash,
                )
            }
        }

        "HistorySourceScope validates ACCOUNT mode requirements" {
            shouldThrow<IllegalArgumentException> {
                HistorySourceScope(
                    mode = HistorySourceMode.ACCOUNT,
                    managedScopeFingerprint = validHash,
                    managedScopeSymbols = listOf("BTC"),
                    accountIdentityDigest = null,
                )
            }
            shouldThrow<IllegalArgumentException> {
                HistorySourceScope(
                    mode = HistorySourceMode.ACCOUNT,
                    managedScopeFingerprint = validHash,
                    managedScopeSymbols = listOf("BTC"),
                    accountIdentityDigest = validHash,
                    simulationSessionId = "sim-123",
                )
            }
            val scope = HistorySourceScope(
                mode = HistorySourceMode.ACCOUNT,
                managedScopeFingerprint = validHash,
                managedScopeSymbols = listOf("BTC"),
                accountIdentityDigest = validHash,
            )
            scope.sourceKey shouldBe "ACCOUNT:$validHash:$validHash"
        }

        "HistorySourceScope validates SIMULATION mode requirements" {
            shouldThrow<IllegalArgumentException> {
                HistorySourceScope(
                    mode = HistorySourceMode.SIMULATION,
                    managedScopeFingerprint = validHash,
                    managedScopeSymbols = listOf("BTC"),
                    simulationSessionId = null,
                )
            }
            shouldThrow<IllegalArgumentException> {
                HistorySourceScope(
                    mode = HistorySourceMode.SIMULATION,
                    managedScopeFingerprint = validHash,
                    managedScopeSymbols = listOf("BTC"),
                    simulationSessionId = "sim-123",
                    accountIdentityDigest = validHash,
                )
            }
            val scope = HistorySourceScope(
                mode = HistorySourceMode.SIMULATION,
                managedScopeFingerprint = validHash,
                managedScopeSymbols = listOf("BTC"),
                simulationSessionId = "sim-session-1",
            )
            scope.sourceKey shouldBe "SIMULATION:sim-session-1:$validHash"
        }

        "HistoryObservation enforces complete vs totalUsd invariants" {
            val scope = HistorySourceScope(
                mode = HistorySourceMode.ACCOUNT,
                managedScopeFingerprint = validHash,
                managedScopeSymbols = listOf("BTC"),
                accountIdentityDigest = validHash,
            )
            val now = Instant.now()
            val asset = HistoryAssetValue(
                symbol = "BTC",
                status = HistoryAssetValueStatus.COMPLETE,
                quantity = BigDecimal("1.5"),
                priceUsd = BigDecimal("50000"),
                valueUsd = BigDecimal("75000"),
            )

            // Complete must have totalUsd
            shouldThrow<IllegalArgumentException> {
                HistoryObservation(
                    observationId = "obs-1",
                    cycleId = "cycle-1",
                    sourceScope = scope,
                    observedAt = now,
                    balanceRequestStartedAt = now,
                    balanceResponseEndedAt = now,
                    priceRequestStartedAt = now,
                    priceResponseEndedAt = now,
                    status = HistoryObservationStatus.COMPLETE,
                    totalUsd = null,
                    incompleteReasons = emptyList(),
                    assets = listOf(asset),
                )
            }

            // Incomplete cannot have totalUsd
            shouldThrow<IllegalArgumentException> {
                HistoryObservation(
                    observationId = "obs-1",
                    cycleId = "cycle-1",
                    sourceScope = scope,
                    observedAt = now,
                    balanceRequestStartedAt = now,
                    balanceResponseEndedAt = now,
                    priceRequestStartedAt = now,
                    priceResponseEndedAt = now,
                    status = HistoryObservationStatus.INCOMPLETE,
                    totalUsd = BigDecimal("75000"),
                    incompleteReasons = listOf("Price missing"),
                    assets = listOf(asset.copy(status = HistoryAssetValueStatus.MISSING_PRICE)),
                )
            }

            // Duplicate assets rejected
            shouldThrow<IllegalArgumentException> {
                HistoryObservation(
                    observationId = "obs-1",
                    cycleId = "cycle-1",
                    sourceScope = scope,
                    observedAt = now,
                    balanceRequestStartedAt = now,
                    balanceResponseEndedAt = now,
                    priceRequestStartedAt = now,
                    priceResponseEndedAt = now,
                    status = HistoryObservationStatus.COMPLETE,
                    totalUsd = BigDecimal("150000"),
                    incompleteReasons = emptyList(),
                    assets = listOf(asset, asset),
                )
            }

            // Valid complete observation
            val validObs = HistoryObservation(
                observationId = "obs-1",
                cycleId = "cycle-1",
                sourceScope = scope,
                observedAt = now,
                balanceRequestStartedAt = now,
                balanceResponseEndedAt = now,
                priceRequestStartedAt = now,
                priceResponseEndedAt = now,
                status = HistoryObservationStatus.COMPLETE,
                totalUsd = BigDecimal("75000"),
                incompleteReasons = emptyList(),
                assets = listOf(asset),
            )
            validObs.status shouldBe HistoryObservationStatus.COMPLETE
        }

        "HistoryRangeQuery validates bounds and limits" {
            val now = Instant.now()
            shouldThrow<IllegalArgumentException> {
                HistoryRangeQuery(from = now, until = now.minusSeconds(10))
            }
            shouldThrow<IllegalArgumentException> {
                HistoryRangeQuery(limit = 0)
            }
            shouldThrow<IllegalArgumentException> {
                HistoryRangeQuery(limit = HISTORY_MAX_PAGE_LIMIT + 1)
            }
            shouldThrow<IllegalArgumentException> {
                HistoryRangeQuery(offset = -1)
            }
            val valid = HistoryRangeQuery(from = now.minusSeconds(10), until = now, limit = 100, offset = 0)
            valid.limit shouldBe 100
        }

        "HistoryEventQuery validates bounds and account digest" {
            val now = Instant.now()
            shouldThrow<IllegalArgumentException> {
                HistoryEventQuery(from = now, until = now.minusSeconds(5))
            }
            shouldThrow<IllegalArgumentException> {
                HistoryEventQuery(limit = 0)
            }
            shouldThrow<IllegalArgumentException> {
                HistoryEventQuery(accountIdentityDigest = "invalid-digest")
            }
            val valid = HistoryEventQuery(
                from = now.minusSeconds(5),
                until = now,
                accountIdentityDigest = validHash,
                assetKey = "BTC",
                limit = 50,
            )
            valid.assetKey shouldBe "BTC"
        }

        "HistoryLedgerCoverageWindow requires startSec <= endSec" {
            shouldThrow<IllegalArgumentException> {
                HistoryLedgerCoverageWindow(startSec = 1000L, endSec = 999L)
            }
            val valid = HistoryLedgerCoverageWindow(startSec = 100L, endSec = 200L)
            valid.startSec shouldBe 100L
        }

        "converts ExchangeTradeHistoryEntry to HistoryTrade" {
            val now = Instant.now()
            val tradeEntry = ExchangeTradeHistoryEntry(
                tradeId = "T123",
                orderTxid = "O123",
                postTxid = "P123",
                pair = "XXBTZUSD",
                baseAsset = "BTC",
                quoteCurrency = "USD",
                feeCurrency = null,
                time = now,
                rawTime = "1700000000.1234",
                type = "buy",
                orderType = "limit",
                price = "50000.0",
                cost = "5000.0",
                fee = "8.0",
                volume = "0.1",
                margin = "0.0",
                misc = "",
                tradeSequenceId = "seq-1",
                maker = true,
                ledgerIds = listOf("L1", "L2"),
                rawRecordJson = """{"trade":"json"}""",
                hasValidTime = true,
                hasValidPrice = true,
                hasValidCost = true,
                hasValidFee = true,
                hasValidVolume = true,
                isValidRecord = true,
            )

            val historyTrade = tradeEntry.toHistoryTrade(validHash)
            historyTrade.accountIdentityDigest shouldBe validHash
            historyTrade.tradeId shouldBe "T123"
            historyTrade.maker shouldBe true
            historyTrade.ledgerIds shouldBe listOf("L1", "L2")
            historyTrade.feeCurrency shouldBe null
        }

        "converts ExchangeLedgerHistoryEntry to HistoryLedger" {
            val now = Instant.now()
            val ledgerEntry = ExchangeLedgerHistoryEntry(
                ledgerId = "L456",
                refid = "T123",
                time = now,
                rawTime = "1700000000.1234",
                type = "trade",
                subtype = "",
                assetClass = "currency",
                asset = "XXBT",
                amount = "0.1",
                fee = "0.0001",
                balance = "1.5",
                rawRecordJson = """{"ledger":"json"}""",
                hasValidTime = true,
                hasValidAmount = true,
                hasValidFee = true,
                hasValidBalance = true,
                isValidRecord = true,
            )

            val historyLedger = ledgerEntry.toHistoryLedger(validHash)
            historyLedger.accountIdentityDigest shouldBe validHash
            historyLedger.ledgerId shouldBe "L456"
            historyLedger.feeCurrency shouldBe "XXBT"
        }
    }
}
