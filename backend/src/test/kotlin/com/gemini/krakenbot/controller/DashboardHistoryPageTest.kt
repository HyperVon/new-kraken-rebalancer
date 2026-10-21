package com.gemini.krakenbot.controller

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.service.history.HISTORY_MAX_PAGE_LIMIT
import com.gemini.krakenbot.service.history.HistoryBalanceCheckpoint
import com.gemini.krakenbot.service.history.HistoryBalanceStatus
import com.gemini.krakenbot.service.history.HistoryEventQuery
import com.gemini.krakenbot.service.history.HistoryFeed
import com.gemini.krakenbot.service.history.HistoryLedger
import com.gemini.krakenbot.service.history.HistoryObservation
import com.gemini.krakenbot.service.history.HistoryPage
import com.gemini.krakenbot.service.history.HistoryRangePreset
import com.gemini.krakenbot.service.history.HistoryRangeSelection
import com.gemini.krakenbot.service.history.HistorySourceMode
import com.gemini.krakenbot.service.history.HistorySourceScope
import com.gemini.krakenbot.service.history.HistorySyncState
import com.gemini.krakenbot.service.history.HistorySyncStatus
import com.gemini.krakenbot.service.history.HistoryTrade
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.every
import io.mockk.verify
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

class DashboardHistoryPageTest : DashboardControllerTestBase() {
    private val validHash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    private val historyScope = HistorySourceScope(
        mode = HistorySourceMode.ACCOUNT,
        managedScopeFingerprint = validHash,
        managedScopeSymbols = listOf("BTC", "USD"),
        accountIdentityDigest = validHash,
    )

    init {
        "history range selection resolves rolling, one-year, custom, and invalid presets" {
            historyRangeSelection(null, null, null) shouldBe null
            historyRangeSelection("bogus", null, null) shouldBe null
            historyRangeSelection("1y", null, null) shouldBe
                HistoryRangeSelection.rolling(HistoryRangePreset.ONE_YEAR)
            historyRangeSelection("custom", "2026-01-01", "2026-01-03") shouldBe
                HistoryRangeSelection.custom(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 3))
            historyRangeSelection("custom", null, "2026-01-03") shouldBe null
            historyRangeSelection("custom", "2026-01-01", null) shouldBe null
            historyRangeSelection("custom", "2026-01-03", "2026-01-01") shouldBe null
            historyRangeSelection("custom", "bogus", "2026-01-03") shouldBe null
            historyRangeSelection("custom", "2026-01-01", "bogus") shouldBe null
        }

        "GET /history resolves custom and one-year ranges with explicit and invalid timezones" {
            every { configService.getConfig() } returns TestFixtures.DEFAULT_TEST_CONFIG
            coEvery { historyObservationRecorder.currentSourceScope(any()) } returns historyScope
            coEvery { historyService.queryValues(any()) } returns HistoryPage(emptyList(), 0L, 50, 0, false)
            val queries = mutableListOf<HistoryEventQuery>()
            coEvery { historyService.queryTrades(capture(queries)) } returns
                HistoryPage(emptyList(), 0L, 50, 0, false)
            coEvery { historyService.queryLedgers(any()) } returns HistoryPage(emptyList(), 0L, 50, 0, false)
            coEvery { historyService.queryBalanceCheckpoints(any()) } returns HistoryPage(emptyList(), 0L, 50, 0, false)
            coEvery { historyService.getSyncStatus(any()) } returns emptyList()

            testApplication {
                application { configureTestEnv() }

                client.get("/history?range=1y&tz=UTC").status shouldBe HttpStatusCode.OK
                client.get("/history?range=custom&start=2026-01-01&end=2026-01-03&tz=UTC")
                    .status shouldBe HttpStatusCode.OK
                client.get("/history?range=custom&start=2026-01-03&end=2026-01-01&tz=Not/AZone")
                    .status shouldBe HttpStatusCode.OK
            }

            val custom = queries.first {
                it.from == Instant.parse("2026-01-01T00:00:00Z")
            }
            custom.until shouldBe Instant.parse("2026-01-04T00:00:00Z")
            val fallback = queries.last()
            val fallbackFrom = requireNotNull(fallback.from)
            val fallbackUntil = requireNotNull(fallback.until)
            (Duration.between(fallbackFrom, fallbackUntil).toDays().toInt() in 29..31) shouldBe true
            queries.any { query ->
                val from = query.from
                val until = query.until
                from != null && until != null && (Duration.between(from, until).toDays().toInt() in 364..366)
            } shouldBe true
        }

        "GET /history renders complete history page when account is bound" {
            val config = TestFixtures.DEFAULT_TEST_CONFIG
            every { configService.getConfig() } returns config
            coEvery { historyObservationRecorder.currentSourceScope(any()) } returns historyScope
            coEvery { historyService.queryValues(any()) } returns HistoryPage(emptyList(), 0L, 50, 0, false)
            coEvery { historyService.queryTrades(any()) } returns HistoryPage(emptyList(), 0L, 50, 0, false)
            coEvery { historyService.queryLedgers(any()) } returns HistoryPage(emptyList(), 0L, 50, 0, false)
            coEvery { historyService.queryBalanceCheckpoints(any()) } returns HistoryPage(emptyList(), 0L, 50, 0, false)
            coEvery { historyService.getSyncStatus(any()) } returns emptyList()

            testApplication {
                application { configureTestEnv() }
                val response = client.get("/history?range=7d&asset=BTC")
                response.status shouldBe HttpStatusCode.OK
                val html = response.bodyAsText()
                html shouldContain "History"
                html shouldContain "Portfolio Value"
                html shouldContain "Trade History"
                html shouldContain "Transactions &amp; Ledgers"
            }
        }

        "GET /history all range fetches every checkpoint page for the selected asset" {
            every { configService.getConfig() } returns TestFixtures.DEFAULT_TEST_CONFIG
            coEvery { historyObservationRecorder.currentSourceScope(any()) } returns historyScope
            coEvery { historyService.queryTrades(any()) } returns HistoryPage(emptyList(), 0L, 50, 0, false)
            coEvery { historyService.queryLedgers(any()) } returns HistoryPage(emptyList(), 0L, 50, 0, false)
            coEvery { historyService.getSyncStatus(any()) } returns emptyList()
            val requestedQueries = mutableListOf<HistoryEventQuery>()
            val firstPage = (2..(HISTORY_MAX_PAGE_LIMIT + 1)).reversed().map(::checkpoint)
            coEvery { historyService.queryBalanceCheckpoints(any()) } coAnswers {
                val query = firstArg<HistoryEventQuery>()
                requestedQueries += query
                if (query.offset == 0) {
                    HistoryPage(firstPage, 2_001L, query.limit, query.offset, true)
                } else {
                    HistoryPage(listOf(checkpoint(1)), 2_001L, query.limit, query.offset, false)
                }
            }

            testApplication {
                application { configureTestEnv() }
                val response = client.get("/history?range=all&asset=BTC")
                response.status shouldBe HttpStatusCode.OK
                val html = response.bodyAsText()
                html shouldContain ">1</text>"
                html shouldContain ">2001</text>"
                val markerXCoordinates = Regex("""cx="([0-9.]+)" cy="[0-9.]+" r="7" fill="transparent"""")
                    .findAll(html)
                    .map { it.groupValues[1] }
                    .toList()
                markerXCoordinates.size shouldBe 2_001
                markerXCoordinates.first() shouldBe "120.00"
                markerXCoordinates.last() shouldBe "928.00"
                markerXCoordinates.zipWithNext().all { (left, right) ->
                    left.toDouble() <= right.toDouble()
                } shouldBe true
            }

            requestedQueries.map { it.offset } shouldBe listOf(0, HISTORY_MAX_PAGE_LIMIT)
            requestedQueries.all {
                it.assetKey == "BTC" && it.from == null && it.limit == HISTORY_MAX_PAGE_LIMIT
            } shouldBe true
        }

        "GET /history renders history page with no asset selected and custom offsets" {
            val config = TestFixtures.DEFAULT_TEST_CONFIG
            every { configService.getConfig() } returns config
            coEvery { historyObservationRecorder.currentSourceScope(any()) } returns historyScope
            coEvery { historyService.queryValues(any()) } returns HistoryPage(emptyList(), 0L, 50, 0, false)
            coEvery { historyService.queryTrades(any()) } returns HistoryPage(emptyList(), 0L, 50, 0, false)
            coEvery { historyService.queryLedgers(any()) } returns HistoryPage(emptyList(), 0L, 50, 0, false)
            coEvery { historyService.getSyncStatus(any()) } returns emptyList()

            testApplication {
                application { configureTestEnv() }
                val response = client.get("/history?range=all&tradesOffset=5&ledgersOffset=10&pair=XXBTZUSD&type=buy")
                response.status shouldBe HttpStatusCode.OK
                val html = response.bodyAsText()
                html shouldContain "History"
            }
        }

        "GET /history renders in simulation mode without account binding" {
            val config = TestFixtures.DEFAULT_TEST_CONFIG.let {
                it.copy(settings = it.settings.copy(simulation = true))
            }
            every { configService.getConfig() } returns config
            val simScope = HistorySourceScope(
                mode = HistorySourceMode.SIMULATION,
                managedScopeFingerprint = validHash,
                managedScopeSymbols = listOf("BTC", "USD"),
                simulationSessionId = "sim-session-1",
            )
            coEvery { historyObservationRecorder.currentSourceScope(any()) } returns simScope
            coEvery { historyService.queryValues(any()) } returns HistoryPage(emptyList(), 0L, 50, 0, false)

            testApplication {
                application { configureTestEnv() }
                val response = client.get("/history")
                response.status shouldBe HttpStatusCode.OK
                val html = response.bodyAsText()
                html shouldContain "History"
            }
        }

        "GET /history renders when scope is unavailable" {
            val config = TestFixtures.DEFAULT_TEST_CONFIG
            every { configService.getConfig() } returns config
            coEvery { historyObservationRecorder.currentSourceScope(any()) } returns null

            testApplication {
                application { configureTestEnv() }
                val response = client.get("/history")
                response.status shouldBe HttpStatusCode.OK
                val html = response.bodyAsText()
                html shouldContain "History"
            }
        }

        "GET /api/history/trades returns 503 when service or digest is unavailable" {
            every { configService.getConfig() } returns TestFixtures.DEFAULT_TEST_CONFIG
            testApplication {
                application { configureTestEnv() }
                val response = client.get("/api/history/trades")
                response.status shouldBe HttpStatusCode.ServiceUnavailable
            }
        }

        "GET /api/history/sync-status returns sync statuses" {
            every { configService.getConfig() } returns TestFixtures.DEFAULT_TEST_CONFIG
            testApplication {
                application { configureTestEnv() }
                val response = client.get("/api/history/sync-status")
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldBe "[]"
            }
        }

        "POST /api/history/sync/start rejects invalid CSRF" {
            every { configService.getConfig() } returns TestFixtures.DEFAULT_TEST_CONFIG
            testApplication {
                application { configureTestEnv() }
                val response = client.submitForm(
                    url = "/api/history/sync/start",
                    formParameters = parameters { append("csrfToken", "invalid") },
                )
                response.status shouldBe HttpStatusCode.Forbidden
            }
        }

        "GET /api/history/trades returns 200 with trade query results when bound" {
            every { configService.getConfig() } returns TestFixtures.DEFAULT_TEST_CONFIG
            coEvery { historyObservationRecorder.currentSourceScope(any()) } returns historyScope
            coEvery { historyService.queryTrades(any()) } returns HistoryPage(emptyList(), 0L, 50, 0, false)

            testApplication {
                application { configureTestEnv() }
                val response = client.get(
                    "/api/history/trades?from=2026-01-01T00:00:00Z&until=2026-01-02T00:00:00Z&asset=BTC&pair=XXBTZUSD&type=buy&limit=10&offset=0",
                )
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain "items"
            }
        }

        "GET /api/history/ledgers returns 200 with ledger query results when bound" {
            every { configService.getConfig() } returns TestFixtures.DEFAULT_TEST_CONFIG
            coEvery { historyObservationRecorder.currentSourceScope(any()) } returns historyScope
            coEvery { historyService.queryLedgers(any()) } returns HistoryPage(emptyList(), 0L, 50, 0, false)

            testApplication {
                application { configureTestEnv() }
                val response = client.get("/api/history/ledgers?asset=BTC")
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain "items"
            }
        }

        "GET /api/history/checkpoints returns 200 with checkpoint query results when bound" {
            every { configService.getConfig() } returns TestFixtures.DEFAULT_TEST_CONFIG
            coEvery { historyObservationRecorder.currentSourceScope(any()) } returns historyScope
            coEvery { historyService.queryBalanceCheckpoints(any()) } returns HistoryPage(emptyList(), 0L, 50, 0, false)

            testApplication {
                application { configureTestEnv() }
                val response = client.get("/api/history/checkpoints?asset=BTC")
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain "items"
            }
        }

        "POST /api/history/sync/start starts sync with valid CSRF" {
            val liveConfig = TestFixtures.DEFAULT_TEST_CONFIG.copy(
                settings = TestFixtures.DEFAULT_TEST_CONFIG.settings.copy(simulation = false),
            )
            every { configService.getConfig() } returns liveConfig
            coEvery { historyObservationRecorder.currentSourceScope(any()) } returns historyScope
            coEvery { historyService.getSyncStatus(historyScope) } returns emptyList()
            testApplication {
                application { configureTestEnv() }
                val csrf = client.settingsCsrf()
                val response = client.submitForm(
                    url = "/api/history/sync/start",
                    formParameters = parameters { append("csrfToken", csrf.value) },
                ) {
                    header(HttpHeaders.Cookie, csrf.cookie)
                }
                response.status shouldBe HttpStatusCode.SeeOther
                verify { historySynchronizer.startSync(any(), any(), any()) }
            }
        }

        "POST /api/history/sync/start refuses simulation and unavailable account scope" {
            val simulationConfig = TestFixtures.DEFAULT_TEST_CONFIG.copy(
                settings = TestFixtures.DEFAULT_TEST_CONFIG.settings.copy(simulation = true),
            )
            val simulationScope = HistorySourceScope(
                mode = HistorySourceMode.SIMULATION,
                managedScopeFingerprint = validHash,
                managedScopeSymbols = listOf("BTC", "USD"),
                simulationSessionId = "sim-session",
            )
            every { configService.getConfig() } returns simulationConfig
            coEvery { historyObservationRecorder.currentSourceScope(any()) } returns simulationScope
            testApplication {
                application { configureTestEnv() }
                val csrf = client.settingsCsrf()
                val response = client.submitForm(
                    url = "/api/history/sync/start",
                    formParameters = parameters { append("csrfToken", csrf.value) },
                ) { header(HttpHeaders.Cookie, csrf.cookie) }
                response.status shouldBe HttpStatusCode.ServiceUnavailable
                verify(exactly = 0) { historySynchronizer.startSync(any(), any(), any()) }
            }

            every { configService.getConfig() } returns TestFixtures.DEFAULT_TEST_CONFIG.copy(
                settings = TestFixtures.DEFAULT_TEST_CONFIG.settings.copy(simulation = false),
            )
            coEvery { historyObservationRecorder.currentSourceScope(any()) } returns null
            testApplication {
                application { configureTestEnv() }
                val csrf = client.settingsCsrf()
                val response = client.submitForm(
                    url = "/api/history/sync/start",
                    formParameters = parameters { append("csrfToken", csrf.value) },
                ) { header(HttpHeaders.Cookie, csrf.cookie) }
                response.status shouldBe HttpStatusCode.ServiceUnavailable
                verify(exactly = 0) { historySynchronizer.startSync(any(), any(), any()) }
            }
        }

        "POST /api/history/sync/start resumes persisted bounds and cursors" {
            val liveConfig = TestFixtures.DEFAULT_TEST_CONFIG.copy(
                settings = TestFixtures.DEFAULT_TEST_CONFIG.settings.copy(simulation = false),
            )
            every { configService.getConfig() } returns liveConfig
            coEvery { historyObservationRecorder.currentSourceScope(any()) } returns historyScope
            val start = Instant.ofEpochSecond(1_700_000_000)
            val end = Instant.ofEpochSecond(1_700_010_000)
            coEvery { historyService.getSyncStatus(historyScope) } returns listOf(
                HistorySyncStatus(
                    validHash,
                    HistoryFeed.TRADES,
                    "running-scan",
                    HistorySyncState.RUNNING,
                    start,
                    end,
                    "cursor-5",
                    500,
                    500,
                    1000,
                    end,
                    null,
                ),
                HistorySyncStatus(
                    validHash,
                    HistoryFeed.LEDGERS,
                    "paused-scan",
                    HistorySyncState.PAUSED,
                    start.minusSeconds(10),
                    end.minusSeconds(10),
                    "cursor-9",
                    900,
                    890,
                    1200,
                    end,
                    null,
                ),
            )
            testApplication {
                application { configureTestEnv() }
                val csrf = client.settingsCsrf()
                val response = client.submitForm(
                    url = "/api/history/sync/start",
                    formParameters = parameters { append("csrfToken", csrf.value) },
                ) { header(HttpHeaders.Cookie, csrf.cookie) }
                response.status shouldBe HttpStatusCode.SeeOther
                verify { historySynchronizer.startSync(HistoryFeed.TRADES, start.epochSecond, end.epochSecond) }
                verify {
                    historySynchronizer.startSync(
                        HistoryFeed.LEDGERS,
                        start.minusSeconds(10).epochSecond,
                        end.minusSeconds(10).epochSecond,
                    )
                }
            }
        }

        "historyEventQuery enforces parameter limits and coercions" {
            every { configService.getConfig() } returns TestFixtures.DEFAULT_TEST_CONFIG
            coEvery { historyObservationRecorder.currentSourceScope(any()) } returns historyScope
            coEvery { historyService.queryTrades(any()) } returns HistoryPage(emptyList(), 0L, 50, 0, false)

            testApplication {
                application { configureTestEnv() }

                // Test min limit coercion, negative offset coercion, invalid dates, and whitespace fields
                val response1 = client.get(
                    "/api/history/trades?limit=0&offset=-10&from=bad-date&until=bad-date&asset=  &pair=  &type=  ",
                )
                response1.status shouldBe HttpStatusCode.OK

                // Test max limit coercion and non-int parse fallbacks
                val response2 = client.get("/api/history/trades?limit=50000&offset=not-int")
                response2.status shouldBe HttpStatusCode.OK
            }
        }

        "GET /history handles invalid range and negative or non-integer offsets" {
            val config = TestFixtures.DEFAULT_TEST_CONFIG
            every { configService.getConfig() } returns config
            coEvery { historyObservationRecorder.currentSourceScope(any()) } returns historyScope
            coEvery { historyService.queryValues(any()) } returns HistoryPage(emptyList(), 0L, 50, 0, false)
            coEvery { historyService.queryTrades(any()) } returns HistoryPage(emptyList(), 0L, 50, 0, false)
            coEvery { historyService.queryLedgers(any()) } returns HistoryPage(emptyList(), 0L, 50, 0, false)
            coEvery { historyService.getSyncStatus(any()) } returns emptyList()

            testApplication {
                application { configureTestEnv() }
                val response = client.get("/history?range=invalid-range&tradesOffset=-5&ledgersOffset=not-int")
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain "History"
            }
        }

        "POST /api/history/sync/start handles HTMX stale CSRF recovery" {
            every { configService.getConfig() } returns TestFixtures.DEFAULT_TEST_CONFIG
            testApplication {
                application { configureTestEnv() }

                val staleToken = "A".repeat(43) + "." + "B".repeat(43)
                val response = client.submitForm(
                    url = "/api/history/sync/start",
                    formParameters = parameters { append("csrfToken", staleToken) },
                ) {
                    header("HX-Request", "true")
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Origin, "http://localhost")
                    header(HttpHeaders.Cookie, "rebalancer-csrf=$staleToken")
                }
                response.status shouldBe HttpStatusCode.Forbidden
                response.headers["X-Rebalancer-CSRF-Session-Expired"] shouldBe "true"
                (response.headers["X-Rebalancer-CSRF-Token"] != null) shouldBe true
            }
        }
    }

    private fun checkpoint(quantity: Int) = HistoryBalanceCheckpoint(
        accountIdentityDigest = validHash,
        ledgerId = "ledger-$quantity",
        observedAt = Instant.ofEpochSecond(quantity.toLong()),
        rawAssetClass = "currency",
        rawAssetKey = "XXBT",
        rawAmount = quantity.toString(),
        rawFee = "0",
        rawBalance = quantity.toString(),
        quantity = BigDecimal(quantity),
        status = HistoryBalanceStatus.VALID,
    )
}
