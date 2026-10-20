package com.gemini.krakenbot.controller

import com.fasterxml.jackson.databind.JsonNode
import com.gemini.krakenbot.service.history.HistoryAssetValue
import com.gemini.krakenbot.service.history.HistoryAssetValueStatus
import com.gemini.krakenbot.service.history.HistoryBalanceCheckpoint
import com.gemini.krakenbot.service.history.HistoryBalanceStatus
import com.gemini.krakenbot.service.history.HistoryFeed
import com.gemini.krakenbot.service.history.HistoryLedger
import com.gemini.krakenbot.service.history.HistoryObservation
import com.gemini.krakenbot.service.history.HistoryObservationStatus
import com.gemini.krakenbot.service.history.HistoryPage
import com.gemini.krakenbot.service.history.HistoryRecordedScope
import com.gemini.krakenbot.service.history.HistorySourceMode
import com.gemini.krakenbot.service.history.HistorySourceScope
import com.gemini.krakenbot.service.history.HistorySyncJob
import com.gemini.krakenbot.service.history.HistorySyncJobState
import com.gemini.krakenbot.service.history.HistorySyncProgressSummary
import com.gemini.krakenbot.service.history.HistorySyncPurpose
import com.gemini.krakenbot.service.history.HistoryValuesCoverage
import com.gemini.krakenbot.view.util.FormFields
import com.gemini.krakenbot.view.util.Routes
import io.kotest.core.spec.IsolationMode
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeBlank
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.formUrlEncode
import io.ktor.http.parametersOf
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.verify
import java.math.BigDecimal
import java.time.Instant

class HistoryReadApiRoutesTest : DashboardControllerTestBase() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    private val accountDigest = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    private val fingerprint = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210"
    private val fixedNow = Instant.parse("2026-03-10T12:00:00Z")

    private val accountScope = HistorySourceScope(
        mode = HistorySourceMode.ACCOUNT,
        managedScopeFingerprint = fingerprint,
        managedScopeSymbols = listOf("BTC", "ETH", "USD"),
        accountIdentityDigest = accountDigest,
    )

    init {
        historyApiNow = { fixedNow }
        coEvery { historyObservationRecorder.currentSourceScope(any()) } returns accountScope
    }

    private fun observation(
        id: String,
        time: Instant,
        totalUsd: String?,
        assets: List<HistoryAssetValue> = defaultAssets(totalUsd),
    ): HistoryObservation {
        val status = if (totalUsd == null) HistoryObservationStatus.INCOMPLETE else HistoryObservationStatus.COMPLETE
        return HistoryObservation(
            observationId = id,
            cycleId = "cycle-$id",
            sourceScope = accountScope,
            observedAt = time,
            balanceRequestStartedAt = time.minusMillis(50),
            balanceResponseEndedAt = time,
            priceRequestStartedAt = time.minusMillis(40),
            priceResponseEndedAt = time,
            status = status,
            totalUsd = totalUsd?.let(::BigDecimal),
            incompleteReasons = if (status ==
                HistoryObservationStatus.INCOMPLETE
            ) {
                listOf("missing-price")
            } else {
                emptyList()
            },
            assets = assets,
        )
    }

    private fun defaultAssets(totalUsd: String?): List<HistoryAssetValue> {
        val total = totalUsd?.let(::BigDecimal) ?: return listOf(
            HistoryAssetValue(symbol = "BTC", status = HistoryAssetValueStatus.MISSING_PRICE, quantity = null),
        )
        return listOf(
            HistoryAssetValue(
                symbol = "BTC",
                status = HistoryAssetValueStatus.COMPLETE,
                quantity = BigDecimal("1"),
                priceUsd = total,
                valueUsd = total,
            ),
        )
    }

    private fun stubReads(
        observations: List<HistoryObservation> = emptyList(),
        intervalTotal: Long = observations.size.toLong(),
        valuesFirst: Instant? = null,
        valuesLast: Instant? = null,
        incompleteCount: Long = 0,
    ) {
        coEvery { historyService.queryChartObservations(any(), any(), any(), any()) } returns observations
        coEvery { historyService.queryValues(any()) } returns HistoryPage(
            items = observations,
            total = intervalTotal,
            limit = 1,
            offset = 0,
            hasMore = false,
        )
        coEvery { historyStore.valuesCoverage(any()) } returns HistoryValuesCoverage(
            sourceKey = accountScope.sourceKey,
            firstRecordedAt = valuesFirst,
            lastRecordedAt = valuesLast,
            completeCount = observations.size.toLong(),
            incompleteCount = incompleteCount,
        )
        coEvery { historyStore.listRecordedScopes(any()) } returns emptyList()
        coEvery { historyService.queryTrades(any()) } returns HistoryPage(emptyList(), 0, 1, 0, false)
        coEvery { historyService.queryLedgers(any()) } returns HistoryPage(emptyList(), 0, 1, 0, false)
        coEvery { historyService.queryBalanceCheckpoints(any()) } returns HistoryPage(emptyList(), 0, 1, 0, false)
        coEvery { historyService.queryCycleActivities(any(), any()) } returns emptyList()
    }

    private fun ledgerRow(
        id: String,
        time: Instant,
        type: String,
        asset: String,
        amount: String,
        subtype: String? = null,
    ) = HistoryLedger(
        accountIdentityDigest = accountDigest,
        ledgerId = id,
        refid = null,
        time = time,
        rawTime = time.toString(),
        type = type,
        subtype = subtype,
        assetClass = "currency",
        asset = asset,
        amount = amount,
        fee = "0",
        balance = null,
        rawRecordJson = null,
        hasValidTime = true,
        hasValidAmount = true,
        hasValidFee = true,
        hasValidBalance = false,
        isValidRecord = true,
    )

    private fun body(json: String): JsonNode = objectMapper.readTree(json)

    private fun JsonNode.keys(): Set<String> = fieldNames().asSequence().toSet()

    init {
        "charts response exposes exactly the documented top-level and nested keys" {
            stubReads(
                observations = listOf(observation("obs-1", fixedNow.minusSeconds(3600), "1000.00")),
                valuesFirst = fixedNow.minusSeconds(3600),
                valuesLast = fixedNow.minusSeconds(3600),
            )

            testApplication {
                application { configureTestEnv() }
                val response = client.get(Routes.API_HISTORY_CHARTS)
                response.status shouldBe HttpStatusCode.OK
                val json = body(response.bodyAsText())
                json.keys() shouldBe setOf("range", "scope", "coverage", "series", "fullRange", "generationMs")
                json["range"].keys() shouldBe setOf("fromIso", "toIso", "tz", "preset")
                json["scope"].keys() shouldBe setOf("id", "symbols", "earliestIso", "latestIso", "current")
                json["coverage"].keys() shouldBe setOf(
                    "valuesFirstIso",
                    "valuesLastIso",
                    "tradesFirstIso",
                    "tradesLastIso",
                    "ledgersFirstIso",
                    "ledgersLastIso",
                    "incomplete",
                    "gapCount",
                )
                json["fullRange"].keys() shouldBe
                    setOf("fromIso", "toIso", "pointCount", "totalObservations", "representative")
                json["series"][0].keys() shouldBe setOf("key", "label", "unit", "points")
                json["series"][0]["points"][0].keys() shouldBe setOf("t", "tIso", "v", "complete")
                (json["generationMs"].asLong() >= 0) shouldBe true
            }
        }

        "custom range converts inclusive local dates to UTC bounds with exclusive next-day end" {
            stubReads()

            testApplication {
                application { configureTestEnv() }
                val response = client.get(
                    "${Routes.API_HISTORY_CHARTS}?preset=custom&start=2026-03-05&end=2026-03-07&tz=Europe/Berlin",
                )
                response.status shouldBe HttpStatusCode.OK
                val json = body(response.bodyAsText())
                json["range"]["fromIso"].asText() shouldBe "2026-03-04T23:00:00Z"
                json["range"]["toIso"].asText() shouldBe "2026-03-07T23:00:00Z"
                json["range"]["tz"].asText() shouldBe "Europe/Berlin"
                json["range"]["preset"].asText() shouldBe "custom"
                json["fullRange"]["fromIso"].asText() shouldBe "2026-03-04T23:00:00Z"
                json["fullRange"]["toIso"].asText() shouldBe "2026-03-07T23:00:00Z"
            }
        }

        "invalid range bounds return a structured error body" {
            stubReads()

            testApplication {
                application { configureTestEnv() }
                val inverted = client.get(
                    "${Routes.API_HISTORY_CHARTS}?preset=custom&start=2026-03-07&end=2026-03-05&tz=UTC",
                )
                inverted.status shouldBe HttpStatusCode.BadRequest
                body(inverted.bodyAsText()).keys() shouldBe setOf("error")
                body(inverted.bodyAsText())["error"].asText().shouldNotBeBlank()

                val unknownPreset = client.get("${Routes.API_HISTORY_CHARTS}?preset=nope")
                unknownPreset.status shouldBe HttpStatusCode.BadRequest
                body(unknownPreset.bodyAsText()).keys() shouldBe setOf("error")
                body(unknownPreset.bodyAsText())["error"].asText().shouldNotBeBlank()

                val badZone = client.get("${Routes.API_HISTORY_CHARTS}?tz=Not/AZone")
                badZone.status shouldBe HttpStatusCode.BadRequest
                body(badZone.bodyAsText()).keys() shouldBe setOf("error")
                body(badZone.bodyAsText())["error"].asText().shouldNotBeBlank()

                val outOfRangeEpoch = client.get(
                    "${Routes.API_HISTORY_CHARTS}?preset=all&from=999999999999999999",
                )
                outOfRangeEpoch.status shouldBe HttpStatusCode.BadRequest
                body(outOfRangeEpoch.bodyAsText()).keys() shouldBe setOf("error")
            }
        }

        "viewport from and to narrow the detail interval while fullRange keeps the selected bounds" {
            stubReads(
                observations = listOf(
                    observation("obs-in-1", Instant.parse("2026-03-01T06:00:00Z"), "10.00"),
                    observation("obs-in-2", Instant.parse("2026-03-04T06:00:00Z"), "11.00"),
                ),
                valuesFirst = fixedNow.minusSeconds(20 * 86_400),
                valuesLast = fixedNow,
            )

            testApplication {
                application { configureTestEnv() }
                val response = client.get(
                    "${Routes.API_HISTORY_CHARTS}?preset=30d&from=2026-03-01T00:00:00Z&to=2026-03-05T00:00:00Z",
                )
                response.status shouldBe HttpStatusCode.OK
                val json = body(response.bodyAsText())
                json["range"]["fromIso"].asText() shouldBe "2026-03-01T00:00:00Z"
                json["range"]["toIso"].asText() shouldBe "2026-03-05T00:00:00Z"
                json["fullRange"]["fromIso"].asText() shouldBe "2026-02-08T12:00:00Z"
                json["fullRange"]["toIso"].asText() shouldBe "2026-03-10T12:00:00Z"
                json["series"][0]["points"].size() shouldBe 2
                coVerify {
                    historyService.queryChartObservations(
                        accountScope,
                        Instant.parse("2026-03-01T00:00:00Z"),
                        Instant.parse("2026-03-05T00:00:00Z"),
                        any(),
                    )
                }
            }
        }

        "all preset requests detail without an artificial lower cutoff" {
            var capturedFrom: Instant? = Instant.MAX
            val oldObservation = observation("old", Instant.parse("2020-01-01T00:00:00Z"), "5.00")
            stubReads(observations = listOf(oldObservation))
            coEvery { historyService.queryChartObservations(any(), any(), any(), any()) } answers {
                capturedFrom = secondArg()
                listOf(oldObservation)
            }

            testApplication {
                application { configureTestEnv() }
                val response = client.get("${Routes.API_HISTORY_CHARTS}?preset=all")
                response.status shouldBe HttpStatusCode.OK
                val json = body(response.bodyAsText())
                json["range"]["fromIso"].isNull shouldBe true
                json["fullRange"]["fromIso"].isNull shouldBe true
                json["series"][0]["points"][0]["tIso"].asText() shouldBe "2020-01-01T00:00:00Z"
            }
            capturedFrom.shouldBeNull()
        }

        "fullRange marks representative sampling when fewer points than stored rows are returned" {
            stubReads(
                observations = listOf(
                    observation("a", fixedNow.minusSeconds(3000), "1.00"),
                    observation("b", fixedNow.minusSeconds(2000), "2.00"),
                    observation("c", fixedNow.minusSeconds(1000), "3.00"),
                ),
                intervalTotal = 10,
            )

            testApplication {
                application { configureTestEnv() }
                val response = client.get("${Routes.API_HISTORY_CHARTS}?preset=30d&maxPoints=4")
                response.status shouldBe HttpStatusCode.OK
                val json = body(response.bodyAsText())
                json["fullRange"]["representative"].asBoolean() shouldBe true
                json["fullRange"]["pointCount"].asInt() shouldBe json["series"][0]["points"].size()
                json["fullRange"]["totalObservations"].asLong() shouldBe 10L
            }
        }

        "holdings series keeps incomplete samples as breaks instead of fabricated values" {
            stubReads(
                observations = listOf(
                    observation(
                        "complete",
                        Instant.parse("2026-03-01T00:00:00Z"),
                        "1000.00",
                        assets = listOf(
                            HistoryAssetValue(
                                symbol = "BTC",
                                status = HistoryAssetValueStatus.COMPLETE,
                                quantity = BigDecimal("1"),
                                priceUsd = BigDecimal("600.00"),
                                valueUsd = BigDecimal("600.00"),
                            ),
                            HistoryAssetValue(
                                symbol = "ETH",
                                status = HistoryAssetValueStatus.COMPLETE,
                                quantity = BigDecimal("2"),
                                priceUsd = BigDecimal("200.00"),
                                valueUsd = BigDecimal("400.00"),
                            ),
                        ),
                    ),
                    observation(
                        "broken",
                        Instant.parse("2026-03-02T00:00:00Z"),
                        totalUsd = null,
                        assets = listOf(
                            HistoryAssetValue(
                                symbol = "BTC",
                                status = HistoryAssetValueStatus.MISSING_PRICE,
                                quantity = null,
                            ),
                            HistoryAssetValue(
                                symbol = "ETH",
                                status = HistoryAssetValueStatus.MISSING_PRICE,
                                quantity = null,
                            ),
                        ),
                    ),
                ),
            )

            testApplication {
                application { configureTestEnv() }
                val response = client.get("${Routes.API_HISTORY_CHARTS}?preset=30d&selection=holdings")
                response.status shouldBe HttpStatusCode.OK
                val json = body(response.bodyAsText())
                json["series"].map { it["key"].asText() } shouldContainExactly listOf("BTC", "ETH")
                val btc = json["series"][0]["points"]
                btc[0]["v"].decimalValue() shouldBeEqualComparingTo BigDecimal("600.00")
                btc[0]["complete"].asBoolean() shouldBe true
                btc[1]["v"].isNull shouldBe true
                btc[1]["complete"].asBoolean() shouldBe false
            }
        }

        "allocation series reports each asset share of the observation total" {
            stubReads(
                observations = listOf(
                    observation(
                        "alloc",
                        Instant.parse("2026-03-01T00:00:00Z"),
                        "1000.00",
                        assets = listOf(
                            HistoryAssetValue(
                                symbol = "BTC",
                                status = HistoryAssetValueStatus.COMPLETE,
                                quantity = BigDecimal("1"),
                                priceUsd = BigDecimal("600.00"),
                                valueUsd = BigDecimal("600.00"),
                            ),
                            HistoryAssetValue(
                                symbol = "USD",
                                status = HistoryAssetValueStatus.COMPLETE,
                                quantity = BigDecimal("400.00"),
                                priceUsd = BigDecimal.ONE,
                                valueUsd = BigDecimal("400.00"),
                            ),
                        ),
                    ),
                ),
            )

            testApplication {
                application { configureTestEnv() }
                val response = client.get("${Routes.API_HISTORY_CHARTS}?preset=30d&selection=allocation")
                response.status shouldBe HttpStatusCode.OK
                val json = body(response.bodyAsText())
                json["series"][0]["unit"].asText() shouldBe "percent"
                val values = json["series"].associate { it["key"].asText() to it["points"][0]["v"] }
                values.getValue("BTC").decimalValue() shouldBeEqualComparingTo BigDecimal("60.00")
                values.getValue("USD").decimalValue() shouldBeEqualComparingTo BigDecimal("40.00")
            }
        }

        "deposits and rewards selections plot only their own ledger rows" {
            stubReads()
            val deposit = ledgerRow("L1", Instant.parse("2026-03-01T00:00:00Z"), "deposit", "ZUSD", "100.00")
            val reward = ledgerRow("L2", Instant.parse("2026-03-02T00:00:00Z"), "staking", "XXBT", "0.01")
            coEvery { historyService.queryLedgers(any()) } returns HistoryPage(
                items = listOf(deposit, reward),
                total = 2,
                limit = 2000,
                offset = 0,
                hasMore = false,
            )

            testApplication {
                application { configureTestEnv() }
                val deposits =
                    body(client.get("${Routes.API_HISTORY_CHARTS}?preset=30d&selection=deposits").bodyAsText())
                deposits["series"].map { it["key"].asText() } shouldContainExactly listOf("ZUSD")
                deposits["series"][0]["points"][0]["v"].decimalValue() shouldBeEqualComparingTo BigDecimal("100.00")

                val rewards = body(client.get("${Routes.API_HISTORY_CHARTS}?preset=30d&selection=rewards").bodyAsText())
                rewards["series"].map { it["key"].asText() } shouldContainExactly listOf("XXBT")
                rewards["series"][0]["points"][0]["v"].decimalValue() shouldBeEqualComparingTo BigDecimal("0.01")
            }
        }

        "checkpoints selection plots per-asset balance checkpoint quantities" {
            stubReads()
            val checkpoint = HistoryBalanceCheckpoint(
                accountIdentityDigest = accountDigest,
                ledgerId = "L9",
                observedAt = Instant.parse("2026-03-03T00:00:00Z"),
                rawAssetClass = "currency",
                rawAssetKey = "XXBT",
                rawAmount = null,
                rawFee = null,
                rawBalance = "1.5",
                quantity = BigDecimal("1.5"),
                status = HistoryBalanceStatus.VALID,
            )
            coEvery { historyService.queryBalanceCheckpoints(any()) } returns HistoryPage(
                items = listOf(checkpoint),
                total = 1,
                limit = 2000,
                offset = 0,
                hasMore = false,
            )

            testApplication {
                application { configureTestEnv() }
                val response = client.get("${Routes.API_HISTORY_CHARTS}?preset=30d&selection=checkpoints")
                response.status shouldBe HttpStatusCode.OK
                val json = body(response.bodyAsText())
                json["series"].map { it["key"].asText() } shouldContainExactly listOf("XXBT")
                json["series"][0]["unit"].asText() shouldBe "XXBT"
                json["series"][0]["points"][0]["v"].decimalValue() shouldBe BigDecimal("1.5")
                json["series"][0]["points"][0]["complete"].asBoolean() shouldBe true
            }
        }

        "summary returns range, scope, coverage and reporting statistics" {
            stubReads(
                observations = listOf(observation("s1", Instant.parse("2026-03-01T00:00:00Z"), "100.00")),
                valuesFirst = Instant.parse("2026-03-01T00:00:00Z"),
                valuesLast = Instant.parse("2026-03-01T00:00:00Z"),
            )

            testApplication {
                application { configureTestEnv() }
                val response = client.get(Routes.API_HISTORY_SUMMARY)
                response.status shouldBe HttpStatusCode.OK
                val json = body(response.bodyAsText())
                json.keys() shouldBe setOf("range", "scope", "coverage", "statistics", "generationMs")
                json["statistics"].keys() shouldBe setOf(
                    "generatedAt",
                    "latestObservedValue",
                    "observedValueChange",
                    "rangeExtrema",
                    "trades",
                    "botCycles",
                    "allocationDeviation",
                    "depositsWithdrawals",
                    "rewards",
                )
                json["statistics"]["latestObservedValue"]["valueUsd"].decimalValue() shouldBeEqualComparingTo
                    BigDecimal("100.00")
            }
        }

        "scopes lists stored asset-set scopes with the current scope flag" {
            val record = HistoryRecordedScope(
                sourceKey = accountScope.sourceKey,
                mode = HistorySourceMode.ACCOUNT,
                managedScopeFingerprint = fingerprint,
                managedScopeSymbols = listOf("BTC", "ETH", "USD"),
                accountIdentityDigest = accountDigest,
                simulationSessionId = null,
                firstObservedAt = Instant.parse("2026-02-01T00:00:00Z"),
                lastObservedAt = Instant.parse("2026-03-01T00:00:00Z"),
                observationCount = 5,
            )
            stubReads(
                valuesFirst = Instant.parse("2026-02-01T00:00:00Z"),
                valuesLast = Instant.parse("2026-03-01T00:00:00Z"),
            )
            coEvery { historyStore.listRecordedScopes(any()) } returns listOf(record)

            testApplication {
                application { configureTestEnv() }
                val response = client.get(Routes.API_HISTORY_SCOPES)
                response.status shouldBe HttpStatusCode.OK
                val json = body(response.bodyAsText())
                json.keys() shouldBe setOf("scopes")
                json["scopes"].size() shouldBe 1
                val scope = json["scopes"][0]
                scope.keys() shouldBe setOf("id", "symbols", "earliestIso", "latestIso", "current")
                scope["id"].asText() shouldBe record.sourceKey
                scope["symbols"].map { it.asText() } shouldContainExactly listOf("BTC", "ETH", "USD")
                scope["current"].asBoolean() shouldBe true
                scope["earliestIso"].asText() shouldBe "2026-02-01T00:00:00Z"
                scope["latestIso"].asText() shouldBe "2026-03-01T00:00:00Z"
            }
        }

        "data status reports jobs per feed and purpose with revision and blocked reason" {
            stubReads()
            coEvery { historyStore.currentDataRevision() } returns 42L
            coEvery { historyStore.loadSyncJobs(any()) } returns listOf(
                HistorySyncJob(
                    accountIdentityDigest = accountDigest,
                    feed = HistoryFeed.TRADES,
                    purpose = HistorySyncPurpose.RECENT,
                    state = HistorySyncJobState.COMPLETE,
                    windowStartSec = null,
                    windowEndSec = null,
                    cursor = null,
                    completedThroughSec = null,
                    lastError = "boom",
                    nextRetryAt = Instant.parse("2026-03-10T13:00:00Z"),
                    lastSuccessAt = null,
                    updatedAt = fixedNow,
                ),
                HistorySyncJob(
                    accountIdentityDigest = accountDigest,
                    feed = HistoryFeed.TRADES,
                    purpose = HistorySyncPurpose.BACKFILL,
                    state = HistorySyncJobState.PENDING,
                    windowStartSec = null,
                    windowEndSec = null,
                    cursor = "c1",
                    completedThroughSec = null,
                    lastError = null,
                    nextRetryAt = null,
                    lastSuccessAt = null,
                    updatedAt = fixedNow,
                ),
                HistorySyncJob(
                    accountIdentityDigest = accountDigest,
                    feed = HistoryFeed.TRADES,
                    purpose = HistorySyncPurpose.REPAIR,
                    state = HistorySyncJobState.RUNNING,
                    windowStartSec = null,
                    windowEndSec = null,
                    cursor = null,
                    completedThroughSec = null,
                    lastError = null,
                    nextRetryAt = null,
                    lastSuccessAt = null,
                    updatedAt = fixedNow,
                ),
            )
            coEvery { historyStore.syncProgress(any()) } returns listOf(
                HistorySyncProgressSummary(
                    accountIdentityDigest = accountDigest,
                    feed = HistoryFeed.TRADES,
                    recentCompletedThroughSec = null,
                    recentLastSuccessAt = null,
                    backfillState = HistorySyncJobState.PENDING,
                    backfillCompletedThroughSec = null,
                    backfillCursor = null,
                    repairLastRunAt = null,
                    lastError = null,
                    nextRetryAt = null,
                ),
            )
            every { historySyncCoordinator.blockedReason() } returns "operator-blocked"

            testApplication {
                application { configureTestEnv() }
                val response = client.get(Routes.API_HISTORY_DATA_STATUS)
                response.status shouldBe HttpStatusCode.OK
                val json = body(response.bodyAsText())
                json.keys() shouldBe setOf("range", "dataRevision", "blockedReason", "coverage", "jobs", "progress")
                json["dataRevision"].asLong() shouldBe 42L
                json["blockedReason"].asText() shouldBe "operator-blocked"
                json["jobs"].map { it["purpose"].asText() } shouldContainExactly listOf("RECENT", "BACKFILL", "REPAIR")
                json["jobs"][0]["lastError"].asText() shouldBe "boom"
                json["jobs"][0]["nextRetryAtIso"].asText() shouldBe "2026-03-10T13:00:00Z"
                json["progress"][0]["feed"].asText() shouldBe "TRADES"
            }
        }

        "history repair rejects missing CSRF and accepts a valid selected-date rescan" {
            stubReads()

            testApplication {
                application { configureTestEnv() }
                val csrf = client.settingsCsrf()

                val rejected = client.post(Routes.API_HISTORY_REPAIR) {
                    setBody(parametersOf("date" to listOf("2026-03-05")).formUrlEncode())
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                    header(HttpHeaders.Cookie, csrf.cookie)
                    header(HttpHeaders.Origin, "http://localhost")
                }
                rejected.status shouldBe HttpStatusCode.Forbidden

                val accepted = client.post(Routes.API_HISTORY_REPAIR) {
                    setBody(
                        parametersOf(
                            FormFields.CSRF_TOKEN to listOf(csrf.value),
                            "date" to listOf("2026-03-05"),
                            "tz" to listOf("Europe/Berlin"),
                        ).formUrlEncode(),
                    )
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                    header(HttpHeaders.Cookie, csrf.cookie)
                    header(HttpHeaders.Origin, "http://localhost")
                }
                accepted.status shouldBe HttpStatusCode.OK
                val json = body(accepted.bodyAsText())
                json["accepted"].asBoolean() shouldBe true
                json["startIso"].asText() shouldBe "2026-03-04T23:00:00Z"
                json["endIso"].asText() shouldBe "2026-03-05T23:00:00Z"
                json["feeds"].map { it.asText() } shouldContainExactly listOf("TRADES", "LEDGERS")
            }
            verify {
                historySynchronizer.startSync(
                    HistoryFeed.TRADES,
                    Instant.parse("2026-03-04T23:00:00Z").epochSecond,
                    Instant.parse("2026-03-05T23:00:00Z").epochSecond,
                    HistorySyncPurpose.REPAIR,
                    null,
                )
            }
            verify {
                historySynchronizer.startSync(
                    HistoryFeed.LEDGERS,
                    Instant.parse("2026-03-04T23:00:00Z").epochSecond,
                    Instant.parse("2026-03-05T23:00:00Z").epochSecond,
                    HistorySyncPurpose.REPAIR,
                    null,
                )
            }
        }

        "unknown chart selection returns a structured error body" {
            stubReads()
            testApplication {
                application { configureTestEnv() }
                val response = client.get("${Routes.API_HISTORY_CHARTS}?selection=nope")
                response.status shouldBe HttpStatusCode.BadRequest
                body(response.bodyAsText())["error"].asText() shouldContain "selection"
            }
        }
    }
}
