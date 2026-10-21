package com.gemini.krakenbot.browserfixture

import com.gemini.krakenbot.model.ExchangeHistoryPage
import com.gemini.krakenbot.model.ExchangeLedgerHistoryEntry
import com.gemini.krakenbot.model.ExchangeTradeHistoryEntry
import com.gemini.krakenbot.model.KrakenApiConstants
import com.gemini.krakenbot.service.DirectBalanceCapture
import com.gemini.krakenbot.service.DirectEvidenceStatus
import com.gemini.krakenbot.service.DirectTickerCapture
import com.gemini.krakenbot.service.DirectTickerMark
import com.gemini.krakenbot.service.DirectValueEvidence
import com.gemini.krakenbot.service.history.HistoryCycleActivity
import com.gemini.krakenbot.service.history.HistoryCycleOutcome
import com.gemini.krakenbot.service.history.HistoryFeed
import com.gemini.krakenbot.service.history.HistoryObservationValuator
import com.gemini.krakenbot.service.history.HistoryPageRequest
import com.gemini.krakenbot.service.history.HistorySampleSelection
import com.gemini.krakenbot.service.history.HistorySourceScope
import com.gemini.krakenbot.service.history.HistoryStore
import com.gemini.krakenbot.service.history.HistorySyncPurpose
import com.gemini.krakenbot.service.history.HistorySyncWindow
import java.math.BigDecimal
import java.time.Instant

internal const val BROWSER_FIXTURE_SAMPLE_COUNT = 100 * 24 + 1
internal const val BROWSER_FIXTURE_RANGE_EDGE_COUNT = 2
internal const val BROWSER_FIXTURE_EVENT_COUNT = 103
internal const val BROWSER_FIXTURE_EVENT_DAY_COUNT = 101
private const val SECONDS_PER_DAY = 24L * 60 * 60
private const val SAMPLE_INTERVAL_SECONDS = 60L * 60

internal object BrowserFixtureHistorySeed {
    suspend fun seed(
        store: HistoryStore,
        accountScope: HistorySourceScope,
        alternateScope: HistorySourceScope,
        now: Instant,
    ) {
        val firstSampleAt = now.minusSeconds(100 * SECONDS_PER_DAY)
        repeat(BROWSER_FIXTURE_SAMPLE_COUNT) { index ->
            val at = firstSampleAt.plusSeconds(index * SAMPLE_INTERVAL_SECONDS)
            val cycleId = "fixture-account-cycle-${index.toString().padStart(4, '0')}"
            val missingEthTicker = index % 503 == 0 || index == BROWSER_FIXTURE_SAMPLE_COUNT - 5
            val observation = directObservation(
                cycleId = cycleId,
                scope = accountScope,
                at = at,
                balances = linkedMapOf(
                    "XXBT" to BigDecimal("0.10"),
                    "XETH" to BigDecimal("1.20"),
                    "ZUSD" to BigDecimal("3000.00"),
                ),
                prices = buildMap {
                    put("BTC", BigDecimal("40000").add(BigDecimal(index % 31 * 25)))
                    if (!missingEthTicker) {
                        put("ETH", BigDecimal("2500").add(BigDecimal(index % 17 * 5)))
                    }
                },
            )
            check(
                store.appendCycle(
                    observation,
                    activity(
                        cycleId = cycleId,
                        scope = accountScope,
                        at = at,
                        outcome = if (missingEthTicker) HistoryCycleOutcome.PROBLEM else HistoryCycleOutcome.NO_TRADE,
                        action = if (missingEthTicker) {
                            "Fixture ticker evidence intentionally missing"
                        } else {
                            "Fixture account cycle recorded"
                        },
                    ),
                ),
            )
        }

        val boundary = now.minusSeconds(30 * SECONDS_PER_DAY)
        listOf(boundary.minusNanos(1), boundary.plusNanos(1)).forEachIndexed { index, at ->
            val cycleId = "fixture-range-edge-$index"
            check(
                store.appendCycle(
                    directObservation(
                        cycleId = cycleId,
                        scope = accountScope,
                        at = at,
                        balances = linkedMapOf(
                            "XXBT" to BigDecimal("0.10"),
                            "XETH" to BigDecimal("1.20"),
                            "ZUSD" to BigDecimal("3000.00"),
                        ),
                        prices = mapOf("BTC" to BigDecimal("40000"), "ETH" to BigDecimal("2500")),
                    ),
                    activity(cycleId, accountScope, at, HistoryCycleOutcome.NO_TRADE, "Fixture range-edge sample"),
                ),
            )
        }

        listOf(24L, 12L, 0L).forEachIndexed { index, hoursAgo ->
            val at = now.minusSeconds(hoursAgo * 60 * 60)
            val cycleId = "fixture-alternate-scope-$index"
            check(
                store.appendCycle(
                    directObservation(
                        cycleId = cycleId,
                        scope = alternateScope,
                        at = at,
                        balances = linkedMapOf("XXBT" to BigDecimal("0.05"), "ZUSD" to BigDecimal("2500.00")),
                        prices = mapOf("BTC" to BigDecimal("40000")),
                    ),
                    activity(cycleId, alternateScope, at, HistoryCycleOutcome.NO_TRADE, "Alternate asset scope"),
                ),
            )
        }

        seedTrades(store, accountScope, now)
        seedLedgers(store, accountScope, now)
    }

    private fun directObservation(
        cycleId: String,
        scope: HistorySourceScope,
        at: Instant,
        balances: Map<String, BigDecimal>,
        prices: Map<String, BigDecimal>,
    ) = HistoryObservationValuator.buildDirect(
        observationId = cycleId,
        cycleId = cycleId,
        sourceScope = scope,
        balances = DirectBalanceCapture(
            requestStartedAt = at.minusSeconds(4),
            responseEndedAt = at.minusSeconds(3),
            resultShapeValid = true,
            valuesByAssetId = balances.mapValues { (_, amount) -> amount.evidence() },
        ),
        prices = DirectTickerCapture(
            requestStartedAt = at.minusSeconds(1),
            responseEndedAt = at,
            resultShapeValid = true,
            marksBySymbol = prices.mapValues { (symbol, price) ->
                val pair = if (symbol == "BTC") "XXBTZUSD" else "XETHZUSD"
                DirectTickerMark(
                    symbol = symbol,
                    requestedPair = pair,
                    responsePair = pair,
                    candidateResponsePairs = listOf(pair),
                    rawPrice = price.toPlainString(),
                    price = price,
                    status = DirectEvidenceStatus.VALID,
                    candidateValuesByPair = mapOf(pair to price.evidence()),
                )
            },
        ),
        sampleSelection = HistorySampleSelection.FINAL,
        now = at,
    ).copy(
        balanceSource = "FAKE_VERIFIED_ACCOUNT_BALANCES",
        priceSource = "FAKE_VERIFIED_ACCOUNT_TICKERS",
        persistedAt = at,
    )

    private fun BigDecimal.evidence() = DirectValueEvidence(
        rawValue = toPlainString(),
        value = this,
        status = DirectEvidenceStatus.VALID,
    )

    private fun activity(
        cycleId: String,
        scope: HistorySourceScope,
        at: Instant,
        outcome: HistoryCycleOutcome,
        action: String,
    ) = HistoryCycleActivity(
        cycleId = cycleId,
        sourceScope = scope,
        occurredAt = at,
        dryRun = true,
        actions = listOf(action),
        outcome = outcome,
    )

    private suspend fun seedTrades(store: HistoryStore, scope: HistorySourceScope, now: Instant) {
        val digest = checkNotNull(scope.accountIdentityDigest)
        val startSec = now.minusSeconds(100 * SECONDS_PER_DAY).epochSecond
        val endSec = now.epochSecond + 1
        val boundary = now.minusSeconds(30 * SECONDS_PER_DAY)
        val timedTradeIds = buildList {
            add("fixture-trade-0" to boundary.minusNanos(1))
            add("fixture-trade-1" to boundary)
            add("fixture-trade-2" to boundary.plusNanos(1))
            (0..100).filterNot { it == 30 }.forEach { daysAgo ->
                add("fixture-trade-day-$daysAgo" to now.minusSeconds(daysAgo * SECONDS_PER_DAY))
            }
        }
        val entries = timedTradeIds.mapIndexed { index, (tradeId, at) ->
            val isBitcoin = index % 2 == 0
            val pair = if (isBitcoin) "XXBTZUSD" else "XETHZUSD"
            ExchangeTradeHistoryEntry(
                tradeId = tradeId,
                orderTxid = "fixture-order-$tradeId",
                postTxid = null,
                pair = pair,
                baseAsset = if (isBitcoin) "BTC" else "ETH",
                quoteCurrency = "USD",
                feeCurrency = null,
                time = at,
                rawTime = at.epochSecond.toString(),
                type = if (index % 2 == 0) "buy" else "sell",
                orderType = "market",
                price = if (isBitcoin) "40000" else "2500",
                cost = "100.00",
                fee = "0.20",
                volume = if (isBitcoin) "0.0025" else "0.04",
                margin = "0",
                misc = "",
                tradeSequenceId = "fixture-sequence-$tradeId",
                maker = false,
                ledgerIds = emptyList(),
                rawRecordJson = "{}",
                hasValidTime = true,
                hasValidPrice = true,
                hasValidCost = true,
                hasValidFee = true,
                hasValidVolume = true,
                isValidRecord = true,
            )
        }
        store.beginSyncWindow(
            HistorySyncWindow(digest, HistoryFeed.TRADES, "browser-fixture-trades", startSec, endSec),
        )
        store.commitTradePage(
            request = HistoryPageRequest(
                accountIdentityDigest = digest,
                feed = HistoryFeed.TRADES,
                scanId = "browser-fixture-trades",
                startSec = startSec,
                endSec = endSec,
                requestedCursor = null,
                committedAt = now,
                purpose = HistorySyncPurpose.RECENT,
            ),
            page = ExchangeHistoryPage(
                entries = entries,
                startSec = startSec,
                endSec = endSec,
                requestedCursor = null,
                nextCursor = null,
                count = entries.size.toLong(),
                rawSize = entries.size,
                valid = true,
            ),
        )
    }

    private suspend fun seedLedgers(store: HistoryStore, scope: HistorySourceScope, now: Instant) {
        val digest = checkNotNull(scope.accountIdentityDigest)
        val startSec = now.minusSeconds(100 * SECONDS_PER_DAY).epochSecond
        val endSec = now.epochSecond + 1
        val boundary = now.minusSeconds(30 * SECONDS_PER_DAY)
        val timedLedgerIds = buildList {
            add("fixture-ledger-0" to boundary.minusNanos(1))
            add("fixture-ledger-1" to boundary)
            add("fixture-ledger-2" to boundary.plusNanos(1))
            (0..100).filterNot { it == 30 }.forEach { daysAgo ->
                add("fixture-ledger-day-$daysAgo" to now.minusSeconds(daysAgo * SECONDS_PER_DAY))
            }
        }
        val types = listOf(
            KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
            KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL,
            KrakenApiConstants.LEDGER_TYPE_STAKING,
            KrakenApiConstants.LEDGER_TYPE_EARN,
        )
        val entries = timedLedgerIds.mapIndexed { index, (ledgerId, at) ->
            val asset = when (index % 3) {
                0 -> "ZUSD"
                1 -> "XXBT"
                else -> "XETH"
            }
            val type = types[index % types.size]
            val isReward = type == KrakenApiConstants.LEDGER_TYPE_STAKING ||
                type == KrakenApiConstants.LEDGER_TYPE_EARN
            ExchangeLedgerHistoryEntry(
                ledgerId = ledgerId,
                refid = "fixture-ledger-ref-$ledgerId",
                time = at,
                rawTime = at.epochSecond.toString(),
                type = type,
                subtype = if (isReward) "reward" else "spot",
                assetClass = "currency",
                asset = asset,
                amount = if (type == KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL) "-0.25" else "1.00",
                fee = "0.00",
                balance = "10.00",
                rawRecordJson = "{}",
                hasValidTime = true,
                hasValidAmount = true,
                hasValidFee = true,
                hasValidBalance = true,
                isValidRecord = true,
            )
        }
        store.beginSyncWindow(
            HistorySyncWindow(digest, HistoryFeed.LEDGERS, "browser-fixture-ledgers", startSec, endSec),
        )
        store.commitLedgerPage(
            request = HistoryPageRequest(
                accountIdentityDigest = digest,
                feed = HistoryFeed.LEDGERS,
                scanId = "browser-fixture-ledgers",
                startSec = startSec,
                endSec = endSec,
                requestedCursor = null,
                committedAt = now,
                purpose = HistorySyncPurpose.RECENT,
            ),
            page = ExchangeHistoryPage(
                entries = entries,
                startSec = startSec,
                endSec = endSec,
                requestedCursor = null,
                nextCursor = null,
                count = entries.size.toLong(),
                rawSize = entries.size,
                valid = true,
            ),
        )
    }
}
