package com.gemini.krakenbot.view.component

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.service.history.HistoryAssetValue
import com.gemini.krakenbot.service.history.HistoryAssetValueStatus
import com.gemini.krakenbot.service.history.HistoryBalanceCheckpoint
import com.gemini.krakenbot.service.history.HistoryBalanceStatus
import com.gemini.krakenbot.service.history.HistoryEventFilterOptions
import com.gemini.krakenbot.service.history.HistoryEventQuery
import com.gemini.krakenbot.service.history.HistoryFeed
import com.gemini.krakenbot.service.history.HistoryLedger
import com.gemini.krakenbot.service.history.HistoryObservation
import com.gemini.krakenbot.service.history.HistoryObservationStatus
import com.gemini.krakenbot.service.history.HistoryPage
import com.gemini.krakenbot.service.history.HistorySourceMode
import com.gemini.krakenbot.service.history.HistorySourceScope
import com.gemini.krakenbot.service.history.HistorySyncState
import com.gemini.krakenbot.service.history.HistorySyncStatus
import com.gemini.krakenbot.service.history.HistoryTrade
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.html.html
import kotlinx.html.stream.createHTML
import java.math.BigDecimal
import java.time.Instant

class HistoryPageComponentTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    private val settings = TestFixtures.DEFAULT_TEST_SETTINGS
    private val validHash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    private val historyScope = HistorySourceScope(
        mode = HistorySourceMode.ACCOUNT,
        managedScopeFingerprint = validHash,
        managedScopeSymbols = listOf("BTC", "USD"),
        accountIdentityDigest = validHash,
    )

    private fun render(data: HistoryPageData, csrfToken: String? = "test-csrf", paused: Boolean = false): String =
        createHTML().html {
            HistoryPageComponent().render(settings, data, csrfToken, paused)
        }

    init {
        "renders empty state when no account is available" {
            val emptyData = HistoryPageData(
                range = "24h",
                filters = HistoryEventQuery(),
                tradesOffset = 0,
                ledgersOffset = 0,
                observations = emptyList(),
                checkpoints = HistoryPage(emptyList(), 0L, 50, 0, false),
                trades = HistoryPage(emptyList(), 0L, 50, 0, false),
                ledgers = HistoryPage(emptyList(), 0L, 50, 0, false),
                syncStatuses = emptyList(),
                accountAvailable = false,
            )

            val html = render(emptyData)
            html shouldContain
                "Kraken account identity is not verified yet; imported history appears after the first successful authentication."
            html shouldContain "Enter an asset (for example XXBT) in the filter to chart its exchange-reported balance."
            html shouldContain
                "No historical data available yet. Synchronize account history to populate trades and ledger records."
            html shouldContain
                "No portfolio observations are available for this range yet. Portfolio values are captured during rebalance cycles."
            html shouldContain "Exchange History Sync"
            html shouldNotContain "Synchronizing Exchange History"
        }

        "renders sync banner with all sync states" {
            val now = Instant.ofEpochSecond(1_700_000_000)
            val statuses = listOf(
                HistorySyncStatus(
                    validHash,
                    HistoryFeed.TRADES,
                    "s1",
                    HistorySyncState.RUNNING,
                    now.minusSeconds(100),
                    now,
                    null,
                    10,
                    5,
                    20,
                    now,
                    null,
                ),
                HistorySyncStatus(
                    validHash,
                    HistoryFeed.LEDGERS,
                    "s2",
                    HistorySyncState.COMPLETE,
                    now.minusSeconds(200),
                    now.minusSeconds(100),
                    null,
                    50,
                    50,
                    50,
                    now,
                    null,
                ),
                HistorySyncStatus(
                    validHash,
                    HistoryFeed.TRADES,
                    "s3",
                    HistorySyncState.PAUSED,
                    null,
                    null,
                    null,
                    0,
                    0,
                    null,
                    null,
                    null,
                ),
                HistorySyncStatus(
                    validHash,
                    HistoryFeed.LEDGERS,
                    "s4",
                    HistorySyncState.FAILED,
                    null,
                    null,
                    null,
                    0,
                    0,
                    null,
                    null,
                    "Rate limit hit",
                ),
                HistorySyncStatus(
                    validHash,
                    HistoryFeed.TRADES,
                    "s5",
                    HistorySyncState.NOT_STARTED,
                    null,
                    null,
                    null,
                    0,
                    0,
                    null,
                    null,
                    null,
                ),
            )

            val data = HistoryPageData(
                range = "7d",
                filters = HistoryEventQuery(assetKey = "BTC", pair = "XXBTZUSD", type = "buy"),
                tradesOffset = 0,
                ledgersOffset = 0,
                observations = emptyList(),
                checkpoints = HistoryPage(emptyList(), 0L, 50, 0, false),
                trades = HistoryPage(emptyList(), 0L, 50, 0, false),
                ledgers = HistoryPage(emptyList(), 0L, 50, 0, false),
                syncStatuses = statuses,
                accountAvailable = true,
            )

            val html = render(data)
            html shouldContain "trades: running; 5 stored, 10 fetched"
            html shouldContain "ledgers: complete; 50 stored, 50 fetched"
            html shouldContain "trades: paused"
            html shouldContain "ledgers: failed: Rate limit hit"
            html shouldContain "trades: not started"
            html shouldContain "Sync now"
        }

        "renders account filters as dropdowns and keeps the selected options" {
            val data = HistoryPageData(
                range = "7d",
                filters = HistoryEventQuery(assetKey = "BTC", pair = "XXBTZUSD", type = "buy"),
                tradesOffset = 0,
                ledgersOffset = 0,
                observations = emptyList(),
                checkpoints = HistoryPage(emptyList(), 0L, 50, 0, false),
                trades = HistoryPage(emptyList(), 0L, 50, 0, false),
                ledgers = HistoryPage(emptyList(), 0L, 50, 0, false),
                syncStatuses = emptyList(),
                accountAvailable = true,
                filterOptions = HistoryEventFilterOptions(
                    assets = listOf("BTC", "XXBT"),
                    pairs = listOf("XXBTZUSD"),
                    types = listOf("buy"),
                ),
            )

            val html = render(data)
            html.countOccurrences("<select") shouldBe 3
            html shouldContain "name=\"asset\""
            html shouldContain "name=\"pair\""
            html shouldContain "name=\"type\""
            html shouldContain "XXBT (BTC)"
            html shouldContain "selected"
            html shouldNotContain "type=\"text\""
        }

        "renders value chart and checkpoint chart with SVG lines" {
            val now = Instant.ofEpochSecond(1_700_000_000)
            val obs1 = HistoryObservation(
                observationId = "o1",
                cycleId = "c1",
                sourceScope = historyScope,
                observedAt = now.minusSeconds(100),
                balanceRequestStartedAt = now.minusSeconds(100),
                balanceResponseEndedAt = now.minusSeconds(100),
                priceRequestStartedAt = now.minusSeconds(100),
                priceResponseEndedAt = now.minusSeconds(100),
                status = HistoryObservationStatus.COMPLETE,
                totalUsd = BigDecimal("50000.00"),
                incompleteReasons = emptyList(),
                assets = listOf(
                    HistoryAssetValue(
                        "BTC",
                        HistoryAssetValueStatus.COMPLETE,
                        quantity = BigDecimal.ONE,
                        priceUsd = BigDecimal("50000.00"),
                        valueUsd = BigDecimal("50000.00"),
                    ),
                ),
            )
            val obs2 = obs1.copy(
                observationId = "o2",
                cycleId = "c2",
                observedAt = now,
                totalUsd = BigDecimal("55000.00"),
            )

            val cp1 = HistoryBalanceCheckpoint(
                accountIdentityDigest = validHash,
                ledgerId = "L1",
                observedAt = now.minusSeconds(100),
                rawAssetClass = "currency",
                rawAssetKey = "XXBT",
                rawAmount = "0.1",
                rawFee = "0.0",
                rawBalance = "1.0",
                quantity = BigDecimal("1.0"),
                status = HistoryBalanceStatus.VALID,
            )
            val cp2 = HistoryBalanceCheckpoint(
                accountIdentityDigest = validHash,
                ledgerId = "L2",
                observedAt = now,
                rawAssetClass = "currency",
                rawAssetKey = "XXBT",
                rawAmount = "0.1",
                rawFee = "0.0",
                rawBalance = "1.5",
                quantity = BigDecimal("1.5"),
                status = HistoryBalanceStatus.VALID,
            )
            val invalidCp = HistoryBalanceCheckpoint(
                accountIdentityDigest = validHash,
                ledgerId = "L-invalid",
                observedAt = now.minusSeconds(50),
                rawAssetClass = "currency",
                rawAssetKey = "XXBT",
                rawAmount = "0.1",
                rawFee = "0.0",
                quantity = BigDecimal("99.0"),
                rawBalance = "malformed",
                status = HistoryBalanceStatus.INVALID,
            )

            val data = HistoryPageData(
                range = "24h",
                filters = HistoryEventQuery(assetKey = "BTC"),
                tradesOffset = 0,
                ledgersOffset = 0,
                observations = listOf(obs1, obs2),
                checkpoints = HistoryPage(listOf(cp2, invalidCp, cp1), 3L, 50, 0, false),
                trades = HistoryPage(emptyList(), 0L, 50, 0, false),
                ledgers = HistoryPage(emptyList(), 0L, 50, 0, false),
                syncStatuses = emptyList(),
                accountAvailable = true,
            )

            val html = render(data)
            html.countOccurrences("<circle") shouldBe 4
            html shouldContain "Portfolio Value"
            html shouldContain "Asset Holdings Over Time (BTC)"
            html shouldContain "/static/rebalancer.js?v="
        }

        "connects complete observations and breaks the value chart at incomplete cycles" {
            val now = Instant.ofEpochSecond(1_700_000_000)
            val observation = HistoryObservation(
                observationId = "before-gap",
                cycleId = "cycle-before-gap",
                sourceScope = historyScope,
                observedAt = now.minusSeconds(180),
                balanceRequestStartedAt = now.minusSeconds(180),
                balanceResponseEndedAt = now.minusSeconds(180),
                priceRequestStartedAt = now.minusSeconds(180),
                priceResponseEndedAt = now.minusSeconds(180),
                status = HistoryObservationStatus.COMPLETE,
                totalUsd = BigDecimal("50000"),
                incompleteReasons = emptyList(),
                assets = emptyList(),
            )
            val secondBeforeGap = observation.copy(
                observationId = "second-before-gap",
                cycleId = "cycle-second-before-gap",
                observedAt = now.minusSeconds(120),
                totalUsd = BigDecimal("51000"),
            )
            val incomplete = observation.copy(
                observationId = "incomplete",
                cycleId = "cycle-incomplete",
                observedAt = now.minusSeconds(60),
                status = HistoryObservationStatus.INCOMPLETE,
                totalUsd = null,
                incompleteReasons = listOf("missing price"),
            )
            val afterGap = observation.copy(
                observationId = "after-gap",
                cycleId = "cycle-after-gap",
                observedAt = now,
                totalUsd = BigDecimal("55000"),
            )
            val secondAfterGap = observation.copy(
                observationId = "second-after-gap",
                cycleId = "cycle-second-after-gap",
                observedAt = now.plusSeconds(60),
                totalUsd = BigDecimal("56000"),
            )
            val data = HistoryPageData(
                range = "24h",
                filters = HistoryEventQuery(),
                tradesOffset = 0,
                ledgersOffset = 0,
                observations = listOf(observation, secondBeforeGap, incomplete, afterGap, secondAfterGap),
                checkpoints = HistoryPage(emptyList(), 0L, 50, 0, false),
                trades = HistoryPage(emptyList(), 0L, 50, 0, false),
                ledgers = HistoryPage(emptyList(), 0L, 50, 0, false),
                syncStatuses = emptyList(),
                accountAvailable = true,
            )

            val html = render(data)
            html.countOccurrences("<polyline") shouldBe 2
            html.countOccurrences("<circle") shouldBe 5
            html.countOccurrences("class=\"history-gap-marker\"") shouldBe 1
            html shouldContain "Lines connect complete cycle samples; incomplete cycles break the line."
            html shouldContain "No values are inferred between samples."
        }

        "renders trades, ledgers, and pagination buttons" {
            val now = Instant.ofEpochSecond(1_700_000_000)
            val trade = HistoryTrade(
                accountIdentityDigest = validHash,
                tradeId = "T1",
                orderTxid = "O1",
                postTxid = "P1",
                pair = "XXBTZUSD",
                baseAsset = "BTC",
                quoteCurrency = "USD",
                feeCurrency = "USD",
                time = now,
                rawTime = "1700000000",
                type = "buy",
                orderType = "limit",
                price = "50000.0",
                cost = "5000.0",
                fee = "8.0",
                volume = "0.1",
                margin = "0.0",
                misc = "",
                tradeSequenceId = "1",
                maker = true,
                ledgerIds = listOf("L1"),
                rawRecordJson = "{}",
                hasValidTime = true,
                hasValidPrice = true,
                hasValidCost = true,
                hasValidFee = true,
                hasValidVolume = true,
                isValidRecord = true,
            )

            val ledger = HistoryLedger(
                accountIdentityDigest = validHash,
                ledgerId = "L1",
                refid = "T1",
                time = now,
                rawTime = "1700000000",
                type = "trade",
                subtype = "buy",
                assetClass = "currency",
                asset = "XXBT",
                amount = "0.1",
                fee = "0.0001",
                balance = "1.5",
                rawRecordJson = "{}",
                hasValidTime = true,
                hasValidAmount = true,
                hasValidFee = true,
                hasValidBalance = true,
                isValidRecord = true,
            )

            val data = HistoryPageData(
                range = "30d",
                filters = HistoryEventQuery(assetKey = "BTC"),
                tradesOffset = 50,
                ledgersOffset = 50,
                observations = emptyList(),
                checkpoints = HistoryPage(emptyList(), 0L, 50, 0, false),
                trades = HistoryPage(listOf(trade), 150L, 50, 50, hasMore = true),
                ledgers = HistoryPage(listOf(ledger), 150L, 50, 50, hasMore = true),
                syncStatuses = emptyList(),
                accountAvailable = true,
            )

            val html = render(data)
            html shouldContain "XXBTZUSD"
            html shouldContain "8.0 USD"
            html shouldContain "trade / buy"
            html shouldContain "Previous"
            html shouldContain "Next"
            html shouldContain "51–51 of 150"
        }

        "renders sync status with null window range and unknown error fallback" {
            val failedWithoutError = HistorySyncStatus(
                accountIdentityDigest = validHash,
                feed = HistoryFeed.TRADES,
                scanId = "scan-err",
                state = HistorySyncState.FAILED,
                windowStart = null,
                windowEnd = null,
                nextCursor = null,
                fetchedCount = 0,
                storedCount = 0,
                knownTotal = null,
                lastPageAt = null,
                lastError = null,
            )
            val data = HistoryPageData(
                range = "30d",
                filters = HistoryEventQuery(),
                tradesOffset = 0,
                ledgersOffset = 0,
                observations = emptyList(),
                checkpoints = HistoryPage(emptyList(), 0L, 50, 0, false),
                trades = HistoryPage(emptyList(), 0L, 50, 0, false),
                ledgers = HistoryPage(emptyList(), 0L, 50, 0, false),
                syncStatuses = listOf(failedWithoutError),
                accountAvailable = true,
            )
            val html = render(data)
            html shouldContain "trades: failed: unknown error; 0 stored, 0 fetched"
        }

        "renders charts with single points or flat values" {
            val now = Instant.ofEpochSecond(1_700_000_000)
            val obs1 = HistoryObservation(
                observationId = "o1",
                cycleId = "c1",
                sourceScope = historyScope,
                observedAt = now,
                balanceRequestStartedAt = now,
                balanceResponseEndedAt = now,
                priceRequestStartedAt = now,
                priceResponseEndedAt = now,
                status = HistoryObservationStatus.COMPLETE,
                totalUsd = BigDecimal("100.00"),
                incompleteReasons = emptyList(),
                assets = emptyList(),
            )
            val obs2 = obs1.copy(
                observationId = "o2",
                cycleId = "c2",
                observedAt = now.plusSeconds(60),
                totalUsd = BigDecimal("100.00"), // flat value
            )
            val data = HistoryPageData(
                range = "30d",
                filters = HistoryEventQuery(assetKey = "ETH"),
                tradesOffset = 0,
                ledgersOffset = 0,
                observations = listOf(obs1, obs2),
                checkpoints = HistoryPage(emptyList(), 0L, 50, 0, false),
                trades = HistoryPage(emptyList(), 0L, 50, 0, false),
                ledgers = HistoryPage(emptyList(), 0L, 50, 0, false),
                syncStatuses = emptyList(),
                accountAvailable = true,
            )
            val html = render(data)
            html shouldContain "<circle"
        }

        "renders trades and ledgers with null fields and pagination without total" {
            val trade = HistoryTrade(
                accountIdentityDigest = validHash,
                tradeId = "T2",
                orderTxid = null,
                postTxid = null,
                pair = "ETHUSD",
                baseAsset = "ETH",
                quoteCurrency = "USD",
                feeCurrency = null,
                time = null,
                rawTime = "1700000000",
                type = "sell",
                orderType = "market",
                price = "2000.0",
                cost = "200.0",
                fee = "0.5",
                volume = "0.1",
                margin = null,
                misc = null,
                tradeSequenceId = null,
                maker = false,
                ledgerIds = emptyList(),
                rawRecordJson = null,
                hasValidTime = false,
                hasValidPrice = true,
                hasValidCost = true,
                hasValidFee = true,
                hasValidVolume = true,
                isValidRecord = true,
            )
            val ledger = HistoryLedger(
                accountIdentityDigest = validHash,
                ledgerId = "L2",
                refid = null,
                time = null,
                rawTime = "1700000000",
                type = null,
                subtype = null,
                assetClass = "currency",
                asset = "USD",
                amount = "50.0",
                fee = "0.0",
                balance = "500.0",
                rawRecordJson = null,
                hasValidTime = false,
                hasValidAmount = true,
                hasValidFee = true,
                hasValidBalance = true,
                isValidRecord = true,
            )
            val data = HistoryPageData(
                range = "24h",
                filters = HistoryEventQuery(assetKey = "   "),
                tradesOffset = 0,
                ledgersOffset = 0,
                observations = emptyList(),
                checkpoints = HistoryPage(emptyList(), 0L, 50, 0, false),
                trades = HistoryPage(listOf(trade), null, 50, 0, hasMore = false),
                ledgers = HistoryPage(listOf(ledger), null, 50, 0, hasMore = false),
                syncStatuses = emptyList(),
                accountAvailable = true,
            )
            val html = render(data)
            html shouldContain "(currency not reported)"
            html shouldContain "1–1"
        }
    }

    private fun String.countOccurrences(value: String): Int = split(value).size - 1
}
