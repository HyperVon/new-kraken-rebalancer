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
import com.gemini.krakenbot.service.history.HistoryTradeSummary
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.html.html
import kotlinx.html.stream.createHTML
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset

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
            HistoryPageComponent(ZoneOffset.UTC).render(settings, data, csrfToken, paused)
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
            html shouldContain "Select an asset in the filter to chart its exchange-reported balance."
            html shouldContain
                "No matching records in this range. Try another range or clear the event filters."
            html shouldContain
                "No portfolio observations are available for this range yet. Portfolio values are captured during rebalance cycles."
            html shouldContain "Exchange History Sync"
            html shouldNotContain "Synchronizing Exchange History"
            html shouldNotContain "action=\"/api/history/sync/start\""
            html shouldNotContain "id=\"history-sync-button\""
            val verifiedHtml = render(emptyData.copy(accountAvailable = true))
            verifiedHtml shouldContain "No account history sync has been recorded yet."
            verifiedHtml shouldContain "id=\"history-sync-button\""
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
            listOf("Running", "Complete", "Paused", "Failed", "Not started", "Rate limit hit").forEach {
                html shouldContain ">$it<"
            }
            html shouldContain "<summary>Sync details</summary>"
            html shouldContain "<dt>Stored records</dt>"
            html shouldContain "<dd>5</dd>"
            html shouldContain "<dt>Fetched this scan</dt>"
            html shouldContain "<dd>10</dd>"
            html shouldContain "<dt>Scan window</dt>"
            html shouldContain "<dt>Last page received</dt>"
            html shouldContain "2023-11-14 22:13:20"
            html shouldContain "method=\"post\""
            html shouldContain "action=\"/api/history/sync/start\""
            html shouldContain "name=\"csrfToken\" value=\"test-csrf\""
            html shouldContain "Sync now"
            (html.indexOf("Rate limit hit") < html.indexOf("<details>")) shouldBe true
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
            html.countOccurrences("<select") shouldBe 4
            html shouldContain "name=\"asset\""
            html shouldContain "name=\"pair\""
            html shouldContain "name=\"type\""
            html shouldContain "XXBT (BTC)"
            html shouldContain "selected"
            html shouldNotContain "type=\"text\""
            html shouldContain "href=\"#history-portfolio-section\""
            html shouldContain "href=\"#history-trades-section\""
            html shouldContain "href=\"#history-ledgers-section\""
            html shouldContain "id=\"history-portfolio-section\""
            html shouldContain "id=\"history-trades-section\""
            html shouldContain "id=\"history-ledgers-section\""
            html shouldContain "range=30d&amp;asset=BTC&amp;pair=XXBTZUSD&amp;type=buy"
            html shouldContain "Asset, pair and type filters apply to account records."
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
            html.chartSvg("history-value-chart").countOccurrences("<circle") shouldBe 4
            html.chartSvg("history-asset-chart").countOccurrences("<circle") shouldBe 4
            html shouldContain "data-time=\"1699999900000\" data-value=\"50000.00\""
            html shouldContain "2023-11-14 22:13:20 · $55,000.00"
            html shouldContain "data-history-zoom=\"reset\""
            html shouldContain "data-history-view=\"points\""
            html shouldContain "chart.umd.min.js"
            html shouldContain "id=\"history-saved-views\""
            html shouldContain "Portfolio Value"
            html shouldContain "Balance Checkpoints (BTC)"
            html shouldContain "Asset Holdings Over Time"
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
            html.chartSvg("history-value-chart").countOccurrences("<polyline") shouldBe 2
            html.chartSvg("history-value-chart").countOccurrences("<circle") shouldBe 9
            html.countOccurrences("class=\"history-gap-marker\"") shouldBe 1
            html shouldContain "data-time=\"1699999940000\" data-gap=\"true\""
            html shouldContain "Lines connect complete cycle samples; incomplete cycles break the line."
            html shouldContain "No values are inferred between samples."
            html shouldContain "Some cycles have incomplete values. Gaps remain visible in the chart."
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
                feeCurrency = null,
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
            html shouldContain "BTC/USD"
            html shouldContain "\$8.00"
            html shouldContain "Trade (buy)"
            html shouldContain "Previous"
            html shouldContain "Next"
            html shouldContain "51–51 of 150"
            html shouldContain "tradesOffset=100&amp;ledgersOffset=50#history-trades-section"
            html shouldContain "tradesOffset=50&amp;ledgersOffset=100#history-ledgers-section"
        }

        "renders stat cards from trade summary and observed all-time high" {
            val now = Instant.ofEpochSecond(1_700_000_000)
            val obs = HistoryObservation(
                observationId = "o1",
                cycleId = "c1",
                sourceScope = historyScope,
                observedAt = now,
                balanceRequestStartedAt = now,
                balanceResponseEndedAt = now,
                priceRequestStartedAt = now,
                priceResponseEndedAt = now,
                status = HistoryObservationStatus.COMPLETE,
                totalUsd = BigDecimal("1234.56"),
                incompleteReasons = emptyList(),
                assets = emptyList(),
            )
            val data = HistoryPageData(
                range = "30d",
                filters = HistoryEventQuery(),
                tradesOffset = 0,
                ledgersOffset = 0,
                observations = listOf(obs),
                checkpoints = HistoryPage(emptyList(), 0L, 50, 0, false),
                trades = HistoryPage(emptyList(), 0L, 50, 0, false),
                ledgers = HistoryPage(emptyList(), 0L, 50, 0, false),
                syncStatuses = emptyList(),
                accountAvailable = true,
                tradeSummary = HistoryTradeSummary(
                    totalTrades = 1234L,
                    totalVolumeUsd = BigDecimal("5000.00"),
                    totalFeesPaid = BigDecimal("24.00"),
                    avgFeeRatePercent = BigDecimal("0.4800"),
                ),
            )
            val html = render(data)
            html shouldContain "All-Time High"
            html shouldContain "\$1,234.56"
            html shouldContain "Total Trades"
            html shouldContain "1,234"
            html shouldContain "Total Volume Traded"
            html shouldContain "\$5,000.00"
            html shouldContain "Total Fees Paid"
            html shouldContain "\$24.00"
            html shouldContain "Avg Fee Rate"
            html shouldContain "0.48%"
        }

        "renders dash stat cards when the summary is empty" {
            val data = HistoryPageData(
                range = "30d",
                filters = HistoryEventQuery(),
                tradesOffset = 0,
                ledgersOffset = 0,
                observations = emptyList(),
                checkpoints = HistoryPage(emptyList(), 0L, 50, 0, false),
                trades = HistoryPage(emptyList(), 0L, 50, 0, false),
                ledgers = HistoryPage(emptyList(), 0L, 50, 0, false),
                syncStatuses = emptyList(),
                accountAvailable = true,
            )
            val html = render(data)
            html shouldContain """id="stat-all-time-high">—<"""
            html shouldContain """id="stat-total-trades">0<"""
            html shouldContain """id="stat-avg-fee-rate">—<"""
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
            html shouldContain ">Failed<"
            html shouldContain ">Unknown error<"
            html shouldNotContain "<dt>Scan window</dt>"
            html shouldNotContain "<dt>Last page received</dt>"
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
                quoteCurrency = null,
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
            html.countOccurrences("title=\"1700000000\"") shouldBe 2
            html.countOccurrences(">1700000000<") shouldBe 2
            html shouldContain "1–1"
        }

        "renders unparsable numeric cells as raw source strings" {
            val trade = HistoryTrade(
                accountIdentityDigest = validHash,
                tradeId = "T3",
                orderTxid = null,
                postTxid = null,
                pair = "XXBTZUSD",
                baseAsset = "BTC",
                quoteCurrency = "USD",
                time = null,
                rawTime = null,
                type = "buy",
                orderType = "limit",
                price = "2000.0",
                cost = "??",
                fee = "0.0",
                volume = "n/a",
                margin = null,
                misc = null,
                tradeSequenceId = null,
                maker = true,
                ledgerIds = emptyList(),
                rawRecordJson = null,
                hasValidTime = false,
                hasValidPrice = true,
                hasValidCost = false,
                hasValidFee = true,
                hasValidVolume = false,
                isValidRecord = true,
            )
            val ledger = HistoryLedger(
                accountIdentityDigest = validHash,
                ledgerId = "L3",
                refid = null,
                time = null,
                rawTime = null,
                type = "trade",
                subtype = "tradespot",
                assetClass = "currency",
                asset = "USD",
                amount = "x",
                fee = "0.0",
                balance = "y",
                rawRecordJson = null,
                hasValidTime = false,
                hasValidAmount = false,
                hasValidFee = true,
                hasValidBalance = false,
                isValidRecord = true,
            )
            val data = HistoryPageData(
                range = "24h",
                filters = HistoryEventQuery(),
                tradesOffset = 0,
                ledgersOffset = 0,
                observations = emptyList(),
                checkpoints = HistoryPage(emptyList(), 0L, 50, 0, false),
                trades = HistoryPage(listOf(trade), 1L, 50, 0, hasMore = false),
                ledgers = HistoryPage(listOf(ledger), 1L, 50, 0, hasMore = false),
                syncStatuses = emptyList(),
                accountAvailable = true,
            )
            val html = render(data)
            html.countOccurrences(">n/a<") shouldBe 1
            html.countOccurrences(">??<") shouldBe 1
            html.countOccurrences(">x<") shouldBe 1
            html.countOccurrences(">y<") shouldBe 1
        }

        "renders restored analytics charts with rewards total, ATH tile, and scrubbers" {
            val now = Instant.ofEpochSecond(1_700_000_000)
            val data = HistoryPageData(
                range = "30d",
                filters = HistoryEventQuery(),
                tradesOffset = 0,
                ledgersOffset = 0,
                observations = listOf(
                    sampleObservation(
                        "o1",
                        now,
                        "1000.00",
                        listOf(Triple("BTC", "600.00", "60000.00"), Triple("ETH", "400.00", "3000.00")),
                    ),
                ),
                checkpoints = HistoryPage(emptyList(), 0L, 50, 0, false),
                trades = HistoryPage(emptyList(), 0L, 50, 0, false),
                ledgers = HistoryPage(emptyList(), 0L, 50, 0, false),
                syncStatuses = emptyList(),
                accountAvailable = true,
                fundingLedgers = listOf(
                    sampleLedger("dep", now.minusSeconds(60), "deposit", "ZUSD", "100.00"),
                    sampleLedger("wd", now.minusSeconds(30), "withdrawal", "ZUSD", "-40.00"),
                ),
                rewardLedgers = listOf(
                    sampleLedger("r1", now.minusSeconds(30), "staking", "XXBT", "0.001", subtype = "reward"),
                ),
                allocations = TestFixtures.DEFAULT_TEST_ALLOCATIONS,
            )

            val html = render(data)
            listOf(
                "Asset Holdings Over Time",
                "Allocation Deviation from Target",
                "Cumulative Net Cash Flow",
                "Staking Rewards",
                "All-Time High",
                "Balance Checkpoints",
                "id=\"history-holdings-chart\"",
                "id=\"history-allocation-chart\"",
                "id=\"history-cash-flow-chart\"",
                "id=\"history-rewards-chart\"",
                "id=\"history-rewards-total\"",
                "id=\"stat-all-time-high\"",
                "Total rewards: \$60.00",
                "#history-allocation-section",
                "#history-cash-flow-section",
                "#history-rewards-section",
                "id=\"history-sync-text\"",
            ).forEach { html shouldContain it }
            html.countOccurrences("class=\"history-chart-scrubber-input\"") shouldBe 5
        }

        "asset value and allocation deviation series follow observed samples" {
            val now = Instant.ofEpochSecond(1_700_000_000)
            val first = sampleObservation(
                "o1",
                now.minusSeconds(60),
                "1000.00",
                listOf(Triple("BTC", "600.00", "60000.00"), Triple("ETH", "400.00", "3000.00")),
            )
            val second = sampleObservation("o2", now, "2000.00", listOf(Triple("BTC", "800.00", "40000.00")))
            val incomplete = second.copy(
                observationId = "o3",
                cycleId = "c-o3",
                observedAt = now.plusSeconds(60),
                status = HistoryObservationStatus.INCOMPLETE,
                totalUsd = null,
            )

            val holdings = assetValueSeries(listOf(second, first, incomplete))
            holdings.map { it.first } shouldBe listOf("BTC", "ETH")
            holdings[0].second.size shouldBe 2
            holdings[0].second[0].value shouldBeEqualComparingTo BigDecimal("600.00")
            holdings[0].second[1].value shouldBeEqualComparingTo BigDecimal("800.00")
            holdings[1].second.size shouldBe 1
            holdings[1].second[0].value shouldBeEqualComparingTo BigDecimal("400.00")

            val deviations = allocationDeviationSeries(
                listOf(first, second, incomplete),
                mapOf("BTC" to BigDecimal("50"), "ETH" to BigDecimal("30")),
            )
            deviations.map { it.first } shouldBe listOf("BTC", "ETH")
            deviations[0].second.size shouldBe 2
            deviations[0].second[0].value shouldBeEqualComparingTo BigDecimal("10")
            deviations[0].second[1].value shouldBeEqualComparingTo BigDecimal("-10")
            deviations[1].second.size shouldBe 2
            deviations[1].second[0].value shouldBeEqualComparingTo BigDecimal("10")
            deviations[1].second[1].value shouldBeEqualComparingTo BigDecimal("-30")
        }

        "cumulative funding and reward series value ledger legs at observed prices" {
            val now = Instant.ofEpochSecond(1_700_000_000)
            val early =
                sampleObservation("o1", now.minusSeconds(120), "1000.00", listOf(Triple("BTC", "600.00", "50000.00")))
            val late = sampleObservation("o2", now, "1000.00", listOf(Triple("BTC", "600.00", "60000.00")))

            val flow = cumulativeFundingFlow(
                listOf(
                    sampleLedger("d1", now.minusSeconds(120), "deposit", "ZUSD", "100.00"),
                    sampleLedger("w1", now.minusSeconds(60), "withdrawal", "ZUSD", "-40.00"),
                    sampleLedger("d2", now.minusSeconds(30), "deposit", "XXBT", "0.5"),
                    sampleLedger("t1", now.minusSeconds(10), "trade", "ZUSD", "999.00"),
                    sampleLedger("bad", now.minusSeconds(5), "deposit", "ZUSD", "??"),
                ),
                listOf(early, late),
            )
            flow.size shouldBe 3
            flow[0].value shouldBeEqualComparingTo BigDecimal("100.00")
            flow[1].value shouldBeEqualComparingTo BigDecimal("60.00")
            flow[2].value shouldBeEqualComparingTo BigDecimal("30060")

            val (rewardPoints, total) = cumulativeRewardValue(
                listOf(
                    sampleLedger("r1", now.minusSeconds(100), "staking", "XXBT", "0.001", subtype = "reward"),
                    sampleLedger("r2", now.minusSeconds(10), "staking", "XXBT", "0.001", subtype = "reward"),
                    sampleLedger("r3", now.minusSeconds(5), "staking", "XETH", "0.1", subtype = "reward"),
                ),
                listOf(early, late),
            )
            rewardPoints.size shouldBe 2
            rewardPoints[0].value shouldBeEqualComparingTo BigDecimal("50")
            rewardPoints[1].value shouldBeEqualComparingTo BigDecimal("110")
            total shouldBeEqualComparingTo BigDecimal("110")
        }
    }

    private fun sampleObservation(
        id: String,
        at: Instant,
        totalUsd: String,
        assets: List<Triple<String, String, String>>,
    ): HistoryObservation = HistoryObservation(
        observationId = id,
        cycleId = "c-$id",
        sourceScope = historyScope,
        observedAt = at,
        balanceRequestStartedAt = at,
        balanceResponseEndedAt = at,
        priceRequestStartedAt = at,
        priceResponseEndedAt = at,
        status = HistoryObservationStatus.COMPLETE,
        totalUsd = BigDecimal(totalUsd),
        incompleteReasons = emptyList(),
        assets = assets.map { (symbol, valueUsd, priceUsd) ->
            HistoryAssetValue(
                symbol,
                HistoryAssetValueStatus.COMPLETE,
                quantity = BigDecimal.ONE,
                priceUsd = BigDecimal(priceUsd),
                valueUsd = BigDecimal(valueUsd),
            )
        },
    )

    private fun sampleLedger(
        id: String,
        at: Instant,
        type: String,
        asset: String,
        amount: String,
        subtype: String = "tradespot",
    ): HistoryLedger = HistoryLedger(
        accountIdentityDigest = validHash,
        ledgerId = id,
        refid = null,
        time = at,
        rawTime = null,
        type = type,
        subtype = subtype,
        assetClass = "currency",
        asset = asset,
        amount = amount,
        fee = "0.0",
        balance = "0.0",
        rawRecordJson = null,
        hasValidTime = true,
        hasValidAmount = true,
        hasValidFee = true,
        hasValidBalance = true,
        isValidRecord = true,
    )

    private fun String.countOccurrences(value: String): Int = split(value).size - 1

    /** Shape counts scoped to chart SVGs so inline icon markup cannot inflate them. */
    private fun String.countChartShapes(value: String): Int =
        Regex("""<svg class="history-chart-svg".*?</svg>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(this)
            .sumOf { it.value.countOccurrences(value) }

    /** First chart SVG under a chart container id, so assertions stay scoped to one chart. */
    private fun String.chartSvg(chartId: String): String =
        Regex("""id="$chartId".*?</svg>""", RegexOption.DOT_MATCHES_ALL)
            .find(this)
            ?.value
            ?: error("no chart svg for $chartId")
}
