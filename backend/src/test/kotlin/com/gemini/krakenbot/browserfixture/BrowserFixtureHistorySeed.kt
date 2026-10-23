package com.gemini.krakenbot.browserfixture

import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.ExchangeHistoryPage
import com.gemini.krakenbot.model.ExchangeLedgerHistoryEntry
import com.gemini.krakenbot.model.ExchangeTradeHistoryEntry
import com.gemini.krakenbot.model.KrakenApiConstants
import com.gemini.krakenbot.service.DirectBalanceCapture
import com.gemini.krakenbot.service.DirectEvidenceStatus
import com.gemini.krakenbot.service.DirectTickerCapture
import com.gemini.krakenbot.service.DirectTickerMark
import com.gemini.krakenbot.service.DirectValueEvidence
import com.gemini.krakenbot.service.history.HISTORY_ACCOUNT_DAILY_SCOPE
import com.gemini.krakenbot.service.history.HISTORY_DAILY_VALUE_POLICY_VERSION
import com.gemini.krakenbot.service.history.HistoryCycleActivity
import com.gemini.krakenbot.service.history.HistoryCycleOutcome
import com.gemini.krakenbot.service.history.HistoryDailyBalance
import com.gemini.krakenbot.service.history.HistoryDailyBalanceStatus
import com.gemini.krakenbot.service.history.HistoryDailyPrice
import com.gemini.krakenbot.service.history.HistoryDailyPriceLeg
import com.gemini.krakenbot.service.history.HistoryDailyValueBuilder
import com.gemini.krakenbot.service.history.HistoryFeed
import com.gemini.krakenbot.service.history.HistoryObservationValuator
import com.gemini.krakenbot.service.history.HistoryPageRequest
import com.gemini.krakenbot.service.history.HistorySampleSelection
import com.gemini.krakenbot.service.history.HistorySourceScope
import com.gemini.krakenbot.service.history.HistoryStore
import com.gemini.krakenbot.service.history.HistorySyncPurpose
import com.gemini.krakenbot.service.history.HistorySyncWindow
import com.gemini.krakenbot.service.history.HistoryValuationJob
import com.gemini.krakenbot.service.history.HistoryValuationJobState
import com.gemini.krakenbot.service.history.HistoryValuationStage
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

internal const val BROWSER_FIXTURE_SAMPLE_COUNT = 100 * 24 + 1
internal const val BROWSER_FIXTURE_RANGE_EDGE_COUNT = 2
internal const val BROWSER_FIXTURE_EVENT_COUNT = 103
internal const val BROWSER_FIXTURE_EVENT_DAY_COUNT = 101
internal const val BROWSER_FIXTURE_LEDGER_EVENT_COUNT = 106
internal const val BROWSER_FIXTURE_LEDGER_EVENT_DAY_COUNT = 102
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
        seedDailyAccountValues(store, accountScope, now)
    }

    /** Synthetic, production-shaped marks for the browser fixture; never sourced from an account. */
    private suspend fun seedDailyAccountValues(store: HistoryStore, scope: HistorySourceScope, now: Instant) {
        val digest = checkNotNull(scope.accountIdentityDigest)
        val endDay = now.atZone(ZoneOffset.UTC).toLocalDate().minusDays(1)
        val startDay = endDay.minusDays(399)
        val generationId = "browser-fixture-daily-values-v1"
        val sourceRevision = store.currentLedgerRevision(digest)
        val allBalances = mutableListOf<HistoryDailyBalance>()
        val allPrices = mutableListOf<HistoryDailyPrice>()
        var targetPriceCount = 0
        repeat(400) { index ->
            val day = startDay.plusDays(index.toLong())
            val dayStart = day.atStartOfDay(ZoneOffset.UTC).toInstant()
            val dayEnd = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()
            val held = buildList {
                add("XXBT" to BigDecimal("0.10"))
                add("XETH" to BigDecimal("1.20"))
                add("ZUSD" to BigDecimal("3000.00"))
            }
            held.forEach { (rawAssetKey, quantity) ->
                allBalances += HistoryDailyBalance(
                    dayEnd = dayEnd,
                    dayLabel = day,
                    accountIdentityDigest = digest,
                    sourceAssetScope = HISTORY_ACCOUNT_DAILY_SCOPE,
                    rawAssetClass = "currency",
                    rawAssetKey = rawAssetKey,
                    quantity = quantity,
                    sourceLedgerId = "fixture-daily-$index-$rawAssetKey",
                    sourceObservedAt = dayEnd.minusSeconds(1),
                    sourceRevision = sourceRevision,
                    generationId = generationId,
                    status = HistoryDailyBalanceStatus.VALID,
                )
                val symbol = Asset.normalizeLedgerAsset(rawAssetKey)
                val close = when (symbol) {
                    "BTC" -> BigDecimal("40000").add(BigDecimal(index * 25))
                    "ETH" -> BigDecimal("2500").add(BigDecimal(index * 5))
                    "USD" -> BigDecimal.ONE
                    else -> error("Unexpected browser-fixture asset: $symbol")
                }
                val pair = if (symbol == "USD") "USD" else Asset.tradingPair(symbol)
                allPrices += HistoryDailyPrice(
                    accountIdentityDigest = digest,
                    rawAssetKey = symbol,
                    pair = pair,
                    candleOpen = dayStart,
                    candleEnd = dayEnd,
                    closeUsd = close,
                    source = if (symbol == "USD") "EXACT_USD_UNIT" else "KRAKEN_COMPLETED_DAILY_OHLC",
                    legs = if (symbol == "USD") emptyList() else listOf(HistoryDailyPriceLeg(pair, close, "USD")),
                )
            }
            if (day >= endDay.minusDays(364)) targetPriceCount += held.size
        }
        store.saveDailyBalances(allBalances)
        store.saveDailyPrices(allPrices)
        store.saveDailyValues(HistoryDailyValueBuilder().build(allBalances, allPrices))
        store.upsertValuationJob(
            HistoryValuationJob(
                accountIdentityDigest = digest,
                sourceAssetScope = HISTORY_ACCOUNT_DAILY_SCOPE,
                generationId = generationId,
                state = HistoryValuationJobState.COMPLETE,
                stage = HistoryValuationStage.PRICE_AND_VALUE,
                targetStartDay = endDay.minusDays(364),
                targetEndDay = endDay,
                nextDay = null,
                targetDays = 365,
                balancesProcessed = 365,
                balancesValid = 365,
                balancesBlocked = 0,
                requiredPriceTasks = targetPriceCount,
                completedPriceTasks = targetPriceCount,
                completeValueDays = 365,
                blockedValueDays = 0,
                sourceRevision = sourceRevision,
                calculationPolicyVersion = HISTORY_DAILY_VALUE_POLICY_VERSION,
                lastProgressAt = now,
                nextRetryAt = null,
                lastSuccessAt = now,
                lastError = null,
                updatedAt = now,
                startedAt = now.minusSeconds(90),
            ),
        )
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
        val startSec = now.minusSeconds(401 * SECONDS_PER_DAY).epochSecond
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
                purpose = HistorySyncPurpose.BACKFILL,
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
        val startSec = now.minusSeconds(401 * SECONDS_PER_DAY).epochSecond
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
        val firstValueDay = now.atZone(ZoneOffset.UTC).toLocalDate().minusDays(400)
        val openingAt = firstValueDay.atStartOfDay(ZoneOffset.UTC).toInstant().minusSeconds(1)
        fun ledger(
            ledgerId: String,
            at: Instant,
            type: String,
            asset: String,
            amount: String,
            balance: String,
            subtype: String = "spot",
        ) = ExchangeLedgerHistoryEntry(
            ledgerId = ledgerId,
            refid = "fixture-ledger-ref-$ledgerId",
            time = at,
            rawTime = at.epochSecond.toString(),
            type = type,
            subtype = subtype,
            assetClass = "currency",
            asset = asset,
            amount = amount,
            fee = "0.00",
            balance = balance,
            rawRecordJson = "{}",
            hasValidTime = true,
            hasValidAmount = true,
            hasValidFee = true,
            hasValidBalance = true,
            isValidRecord = true,
        )
        val openingBalances = listOf(
            "ZUSD" to "3000.00",
            "XXBT" to "0.10",
            "XETH" to "1.20",
        ).map { (asset, balance) ->
            ledger(
                ledgerId = "fixture-opening-$asset",
                at = openingAt,
                type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                asset = asset,
                amount = balance,
                balance = balance,
            )
        }
        val regularEntries = timedLedgerIds.mapIndexed { index, (ledgerId, at) ->
            val asset = when (index % 3) {
                0 -> "ZUSD"
                1 -> "XXBT"
                else -> "XETH"
            }
            val type = types[index % types.size]
            val isReward = type == KrakenApiConstants.LEDGER_TYPE_STAKING ||
                type == KrakenApiConstants.LEDGER_TYPE_EARN
            val balance = when (asset) {
                "ZUSD" -> "3000.00"
                "XXBT" -> "0.10"
                else -> "1.20"
            }
            ledger(
                ledgerId = ledgerId,
                at = at,
                type = type,
                asset = asset,
                amount = "0.00",
                balance = balance,
                subtype = if (isReward) "reward" else "spot",
            )
        }
        val entries = openingBalances + regularEntries
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
                purpose = HistorySyncPurpose.BACKFILL,
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
