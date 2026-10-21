package com.gemini.krakenbot.controller

import com.gemini.krakenbot.service.history.HistoryActivityQuery
import com.gemini.krakenbot.service.history.HistoryCycleActivity
import com.gemini.krakenbot.service.history.HistoryCycleOutcome
import com.gemini.krakenbot.service.history.HistoryEventQuery
import com.gemini.krakenbot.service.history.HistoryLedger
import com.gemini.krakenbot.service.history.HistoryPage
import com.gemini.krakenbot.service.history.HistorySourceMode
import com.gemini.krakenbot.service.history.HistorySourceScope
import com.gemini.krakenbot.service.history.HistoryTrade
import io.kotest.core.spec.IsolationMode
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeBlank
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.verify
import java.time.Instant

class HistoryCsvExportRoutesTest : DashboardControllerTestBase() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    private val accountDigest = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    private val fingerprint = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210"
    private val fixedNow = Instant.parse("2026-03-10T12:00:00Z")
    private val accountScope = HistorySourceScope(
        mode = HistorySourceMode.ACCOUNT,
        managedScopeFingerprint = fingerprint,
        managedScopeSymbols = listOf("BTC", "USD"),
        accountIdentityDigest = accountDigest,
    )
    private val simulationScope = HistorySourceScope(
        mode = HistorySourceMode.SIMULATION,
        managedScopeFingerprint = fingerprint,
        managedScopeSymbols = listOf("BTC", "USD"),
        simulationSessionId = "sim-export-session",
    )

    init {
        historyApiNow = { fixedNow }
        coEvery { historyObservationRecorder.currentSourceScope(any()) } returns accountScope

        "trade export streams only filtered rows with a frozen range and browser timezone" {
            var nowCalls = 0
            historyApiNow = {
                nowCalls++
                fixedNow
            }
            val fixture = listOf(
                trade("trade-match", "XBTUSD", "buy", "XXBT", Instant.parse("2026-03-03T12:00:00Z")),
                trade("trade-other-asset", "XBTUSD", "buy", "XETH", Instant.parse("2026-03-03T12:00:00Z")),
                trade("trade-other-pair", "ETHUSD", "buy", "XXBT", Instant.parse("2026-03-03T12:00:00Z")),
                trade("trade-other-type", "XBTUSD", "sell", "XXBT", Instant.parse("2026-03-03T12:00:00Z")),
                trade("trade-after-cutoff", "XBTUSD", "buy", "XXBT", fixedNow.plusSeconds(1)),
            )
            val queries = mutableListOf<HistoryEventQuery>()
            coEvery { historyService.queryTrades(any()) } coAnswers {
                val query = firstArg<HistoryEventQuery>()
                queries += query
                page(query, fixture.filter { it.matches(query) })
            }

            testApplication {
                application { configureTestEnv() }
                val response = client.get(
                    "/api/history/export/trades?preset=custom&start=2026-03-01&end=2026-03-20" +
                        "&from=2026-03-02&tz=Europe%2FBerlin&asset=XXBT&pair=XBTUSD&type=buy" +
                        "&scope=${accountScope.sourceKey}&mode=ACCOUNT",
                )
                val csv = response.bodyAsText()

                assertCsv(
                    csv,
                    response.headers[HttpHeaders.ContentType],
                    response.headers[HttpHeaders.ContentDisposition],
                    "trades",
                    "\"trade_id\",\"order_txid\",\"post_txid\",\"pair\",\"side\",\"order_type\"," +
                        "\"price\",\"volume\",\"base_asset\",\"volume_currency\",\"cost\",\"quote_currency\"," +
                        "\"fee\",\"fee_currency\",\"margin\",\"misc\",\"trade_sequence_id\",\"maker\"," +
                        "\"ledger_ids\",\"utc_timestamp\",\"local_timestamp\",\"display_timezone\",\"mode\"," +
                        "\"bot_linkage\",\"raw_timestamp\"",
                )
                response.status shouldBe HttpStatusCode.OK
                recordIds(csv) shouldContainExactly listOf("trade-match")
                csv shouldContain "\"2026-03-03T13:00:00+01:00\""
                csv shouldContain "\"Europe/Berlin\""
                csv shouldContain "\"Unknown\""
            }

            nowCalls shouldBe 1
            queries.size shouldBe 1
            queries.single().from shouldBe Instant.parse("2026-03-01T23:00:00Z")
            queries.single().until shouldBe fixedNow
            queries.single().accountIdentityDigest shouldBe accountDigest
            queries.single().assetKey shouldBe "XXBT"
            queries.single().pair shouldBe "XBTUSD"
            queries.single().type shouldBe "buy"
            queries.single().limit shouldBe 500
            queries.single().offset shouldBe 0
            coVerify(exactly = 1) { historyCsvExporter.exportTrades(any(), any(), any()) }
            verify(exactly = 0) { historySynchronizer.startSync(any(), any(), any(), any(), any()) }
        }

        "ledger export streams filtered identifiers with its attachment and CSV headers" {
            val fixture = listOf(
                ledger("ledger-match", "XXBT", "deposit", Instant.parse("2026-03-09T12:00:00Z")),
                ledger("ledger-other-asset", "XETH", "deposit", Instant.parse("2026-03-09T12:00:00Z")),
                ledger("ledger-other-type", "XXBT", "trade", Instant.parse("2026-03-09T12:00:00Z")),
            )
            val queries = mutableListOf<HistoryEventQuery>()
            coEvery { historyService.queryLedgers(any()) } coAnswers {
                val query = firstArg<HistoryEventQuery>()
                queries += query
                page(query, fixture.filter { it.matches(query) })
            }

            testApplication {
                application { configureTestEnv() }
                val response = client.get(
                    "/api/history/export/ledgers?preset=all&tz=UTC&asset=XXBT&type=deposit" +
                        "&scope=${accountScope.sourceKey}&mode=ACCOUNT",
                )
                val csv = response.bodyAsText()

                assertCsv(
                    csv,
                    response.headers[HttpHeaders.ContentType],
                    response.headers[HttpHeaders.ContentDisposition],
                    "ledgers",
                    "\"ledger_id\",\"refid\",\"type\",\"subtype\",\"asset_class\",\"asset\",\"amount\"," +
                        "\"amount_currency\",\"fee\",\"fee_currency\",\"balance\",\"balance_currency\"," +
                        "\"utc_timestamp\",\"raw_timestamp\",\"local_timestamp\",\"display_timezone\",\"mode\"," +
                        "\"bot_linkage\"",
                )
                response.status shouldBe HttpStatusCode.OK
                recordIds(csv) shouldContainExactly listOf("ledger-match")
                csv shouldContain "\"Unknown\""
            }

            queries.single().from shouldBe null
            queries.single().until shouldBe fixedNow
            queries.single().accountIdentityDigest shouldBe accountDigest
            queries.single().assetKey shouldBe "XXBT"
            queries.single().type shouldBe "deposit"
            coVerify(exactly = 1) { historyCsvExporter.exportLedgers(any(), any(), any()) }
            verify(exactly = 0) { historySynchronizer.startSync(any(), any(), any(), any(), any()) }
        }

        "activity export preserves selected simulation scope and legacy outcome fallback" {
            coEvery { historyObservationRecorder.currentSourceScope(any()) } returns simulationScope
            var nowCalls = 0
            historyApiNow = {
                nowCalls++
                fixedNow
            }
            val otherScope = simulationScope.copy(simulationSessionId = "other-sim-session")
            val fixture = listOf(
                activity("cycle-selected", simulationScope, fixedNow.minusSeconds(60), null),
                activity("cycle-other-scope", otherScope, fixedNow.minusSeconds(60), HistoryCycleOutcome.NO_TRADE),
                activity("cycle-after-cutoff", simulationScope, fixedNow.plusSeconds(1), HistoryCycleOutcome.PROBLEM),
            )
            val queries = mutableListOf<HistoryActivityQuery>()
            coEvery { historyStore.queryActivityPage(any()) } coAnswers {
                val query = firstArg<HistoryActivityQuery>()
                queries += query
                val matching = fixture.filter { row ->
                    row.sourceScope.sourceKey == query.sourceScope.sourceKey &&
                        (query.from == null || !row.occurredAt.isBefore(query.from)) &&
                        (query.until == null || row.occurredAt.isBefore(query.until))
                }
                page(query, matching)
            }

            testApplication {
                application { configureTestEnv() }
                val response = client.get(
                    "/api/history/export/activity?preset=custom&start=2026-03-09&end=2026-03-20" +
                        "&tz=America%2FLos_Angeles&scope=${simulationScope.sourceKey}&mode=SIMULATION",
                )
                val csv = response.bodyAsText()

                assertCsv(
                    csv,
                    response.headers[HttpHeaders.ContentType],
                    response.headers[HttpHeaders.ContentDisposition],
                    "activity",
                    "\"cycle_id\",\"mode\",\"outcome\",\"dry_run\",\"actions\",\"utc_timestamp\"," +
                        "\"local_timestamp\",\"display_timezone\",\"source_linkage\"",
                )
                response.status shouldBe HttpStatusCode.OK
                recordIds(csv) shouldContainExactly listOf("cycle-selected")
                csv shouldContain "\"SIMULATION\""
                csv shouldContain "\"Recorded activity\""
                csv shouldContain "\"America/Los_Angeles\""
                csv shouldContain "\"2026-03-10T04:59:00-07:00\""
            }

            nowCalls shouldBe 1
            queries.single().sourceScope shouldBe simulationScope
            queries.single().from shouldBe Instant.parse("2026-03-09T07:00:00Z")
            queries.single().until shouldBe fixedNow
            coVerify(exactly = 1) { historyCsvExporter.exportActivity(any(), any(), any()) }
            verify(exactly = 0) { historySynchronizer.startSync(any(), any(), any(), any(), any()) }
        }

        "invalid ranges and mismatched activity modes return structured bad requests" {
            testApplication {
                application { configureTestEnv() }
                val inverted = client.get(
                    "/api/history/export/trades?preset=custom&start=2026-03-11&end=2026-03-10",
                )
                inverted.status shouldBe HttpStatusCode.BadRequest
                val invertedBody = objectMapper.readTree(inverted.bodyAsText())
                invertedBody.fieldNames().asSequence().toList() shouldContainExactly listOf("error")
                invertedBody["error"].asText().shouldNotBeBlank()

                val mismatchedMode = client.get(
                    "/api/history/export/activity?preset=all&scope=${accountScope.sourceKey}&mode=SIMULATION",
                )
                mismatchedMode.status shouldBe HttpStatusCode.BadRequest
                val modeBody = objectMapper.readTree(mismatchedMode.bodyAsText())
                modeBody.fieldNames().asSequence().toList() shouldContainExactly listOf("error")
                modeBody["error"].asText().shouldNotBeBlank()
            }

            coVerify(exactly = 0) { historyCsvExporter.exportTrades(any(), any(), any()) }
            coVerify(exactly = 0) { historyCsvExporter.exportActivity(any(), any(), any()) }
        }

        "unverified scope and unavailable export services return service unavailable" {
            coEvery { historyObservationRecorder.currentSourceScope(any()) } returns null
            testApplication {
                application { configureTestEnv() }
                val response = client.get("/api/history/export/trades?preset=all")
                response.status shouldBe HttpStatusCode.ServiceUnavailable
                objectMapper.readTree(response.bodyAsText()).fieldNames().asSequence().toList() shouldContainExactly
                    listOf("error")
            }

            coEvery { historyObservationRecorder.currentSourceScope(any()) } returns accountScope
            testApplication {
                application { configureTestEnv(csvExporter = null) }
                val response = client.get("/api/history/export/activity?preset=all")
                response.status shouldBe HttpStatusCode.ServiceUnavailable
            }

            testApplication {
                application { configureTestEnv(service = null) }
                val response = client.get("/api/history/export/trades?preset=all")
                response.status shouldBe HttpStatusCode.ServiceUnavailable
            }

            coVerify(exactly = 0) { historyCsvExporter.exportTrades(any(), any(), any()) }
            coVerify(exactly = 0) { historyCsvExporter.exportActivity(any(), any(), any()) }
        }
    }

    private fun assertCsv(
        csv: String,
        contentType: String?,
        disposition: String?,
        filename: String,
        expectedHeader: String,
    ) {
        contentType.orEmpty().lowercase() shouldContain "text/csv; charset=utf-8"
        disposition shouldBe "attachment; filename=\"history-$filename.csv\""
        csv.substringBefore("\r\n") shouldBe expectedHeader
    }

    private fun recordIds(csv: String): List<String> = csv.split("\r\n")
        .drop(1)
        .filter(String::isNotEmpty)
        .map { it.substringAfter('"').substringBefore('"') }

    private fun trade(id: String, pair: String, type: String, baseAsset: String, time: Instant) = HistoryTrade(
        accountIdentityDigest = accountDigest,
        tradeId = id,
        orderTxid = null,
        postTxid = null,
        pair = pair,
        baseAsset = baseAsset,
        quoteCurrency = "ZUSD",
        time = time,
        rawTime = time.epochSecond.toString(),
        type = type,
        orderType = "market",
        price = "10.00",
        cost = "10.00",
        fee = "0.01",
        volume = "1.00",
        margin = null,
        misc = null,
        tradeSequenceId = null,
        maker = false,
        ledgerIds = emptyList(),
        rawRecordJson = null,
        hasValidTime = true,
        hasValidPrice = true,
        hasValidCost = true,
        hasValidFee = true,
        hasValidVolume = true,
        isValidRecord = true,
    )

    private fun ledger(id: String, asset: String, type: String, time: Instant) = HistoryLedger(
        accountIdentityDigest = accountDigest,
        ledgerId = id,
        refid = null,
        time = time,
        rawTime = time.epochSecond.toString(),
        type = type,
        subtype = null,
        assetClass = "currency",
        asset = asset,
        amount = "1.00",
        fee = "0.00",
        balance = "1.00",
        rawRecordJson = null,
        hasValidTime = true,
        hasValidAmount = true,
        hasValidFee = true,
        hasValidBalance = true,
        isValidRecord = true,
    )

    private fun activity(
        cycleId: String,
        scope: HistorySourceScope,
        occurredAt: Instant,
        outcome: HistoryCycleOutcome?,
    ) = HistoryCycleActivity(
        cycleId = cycleId,
        sourceScope = scope,
        occurredAt = occurredAt,
        dryRun = scope.mode == HistorySourceMode.SIMULATION,
        actions = listOf("recorded action"),
        outcome = outcome,
    )

    private fun HistoryTrade.matches(query: HistoryEventQuery): Boolean =
        (query.from == null || time?.let { !it.isBefore(query.from) } == true) &&
            (query.until == null || time?.isBefore(query.until) == true) &&
            (query.accountIdentityDigest == null || accountIdentityDigest == query.accountIdentityDigest) &&
            (
                query.assetKey == null || query.assetKey.equals(baseAsset, ignoreCase = true) ||
                    query.assetKey.equals(quoteCurrency, ignoreCase = true)
                ) &&
            (query.pair == null || pair == query.pair) &&
            (query.type == null || type == query.type)

    private fun HistoryLedger.matches(query: HistoryEventQuery): Boolean =
        (query.from == null || time?.let { !it.isBefore(query.from) } == true) &&
            (query.until == null || time?.isBefore(query.until) == true) &&
            (query.accountIdentityDigest == null || accountIdentityDigest == query.accountIdentityDigest) &&
            (query.assetKey == null || asset == query.assetKey) &&
            (query.type == null || type == query.type)

    private fun <T> page(query: HistoryEventQuery, rows: List<T>) = HistoryPage(
        items = rows.drop(query.offset).take(query.limit),
        total = rows.size.toLong(),
        limit = query.limit,
        offset = query.offset,
        hasMore = query.offset + query.limit < rows.size,
    )

    private fun <T> page(query: HistoryActivityQuery, rows: List<T>) = HistoryPage(
        items = rows.drop(query.offset).take(query.limit),
        total = rows.size.toLong(),
        limit = query.limit,
        offset = query.offset,
        hasMore = query.offset + query.limit < rows.size,
    )
}
