package com.gemini.krakenbot.controller

import com.fasterxml.jackson.databind.JsonNode
import com.gemini.krakenbot.service.history.HistoryActivityQuery
import com.gemini.krakenbot.service.history.HistoryApiPresenter
import com.gemini.krakenbot.service.history.HistoryCycleActivity
import com.gemini.krakenbot.service.history.HistoryCycleOutcome
import com.gemini.krakenbot.service.history.HistoryDatabase
import com.gemini.krakenbot.service.history.HistoryService
import com.gemini.krakenbot.service.history.HistoryServiceImpl
import com.gemini.krakenbot.service.history.HistorySourceMode
import com.gemini.krakenbot.service.history.HistorySourceScope
import com.gemini.krakenbot.service.history.HistoryStore
import com.gemini.krakenbot.service.history.SqliteHistoryStore
import com.gemini.krakenbot.service.reporting.HistoryReportingService
import com.gemini.krakenbot.view.util.Routes
import io.kotest.core.spec.IsolationMode
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import java.time.Instant

class HistoryActivitiesRoutesTest : DashboardControllerTestBase() {
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

    private val database = HistoryDatabase.inMemory().apply { initializeFreshSchema() }
    private val realStore: HistoryStore = SqliteHistoryStore(database)
    private val realService: HistoryService = HistoryServiceImpl(realStore)

    init {
        historyApiNow = { fixedNow }
        coEvery { historyObservationRecorder.currentSourceScope(any()) } returns accountScope
    }

    override fun historyApi(): HistoryApiPresenter = HistoryApiPresenter(
        historyService = realService,
        historyStore = realStore,
        reportingService = HistoryReportingService(realService, historyApiNow),
        syncCoordinator = historySyncCoordinator,
        nowProvider = historyApiNow,
    )

    private suspend fun seed(
        index: Int,
        actions: List<String>,
        outcome: HistoryCycleOutcome?,
        occurredAt: Instant? = null,
    ) {
        realStore.appendCycleActivity(
            HistoryCycleActivity(
                cycleId = "cycle-%03d".format(index),
                sourceScope = accountScope,
                occurredAt = occurredAt ?: fixedNow.minusSeconds((TOTAL_ROWS - index) * 30L + 1L),
                dryRun = false,
                actions = actions,
                outcome = outcome,
            ),
        )
    }

    private fun body(json: String): JsonNode = objectMapper.readTree(json)

    private fun JsonNode.keys(): Set<String> = fieldNames().asSequence().toSet()

    init {
        "activities page returns newest first and keeps the original action order" {
            seed(1, listOf("first-action", "second-action", "third-action"), HistoryCycleOutcome.NO_TRADE)
            seed(2, listOf("only-action"), HistoryCycleOutcome.ORDERS_ATTEMPTED)

            testApplication {
                application { configureTestEnv() }
                val response = client.get("${Routes.API_HISTORY_ACTIVITIES}?preset=30d&limit=50")
                response.status shouldBe HttpStatusCode.OK
                val json = body(response.bodyAsText())
                json.keys() shouldBe setOf("range", "items", "nextCursor", "hasMore")
                val items = json["items"]
                items.size() shouldBe 2
                items[0]["cycleId"].asText() shouldBe "cycle-002"
                items[1]["cycleId"].asText() shouldBe "cycle-001"
                items[0].keys() shouldBe setOf("cycleId", "occurredAt", "mode", "outcome", "actions")
                items[0]["mode"].asText() shouldBe "ACCOUNT"
                items[0]["outcome"].asText() shouldBe "Orders attempted"
                items[1]["actions"].map { it.asText() } shouldContainExactly listOf(
                    "first-action",
                    "second-action",
                    "third-action",
                )
                items[1]["occurredAt"].asText() shouldBe fixedNow.minusSeconds((TOTAL_ROWS - 1) * 30L + 1L).toString()
            }
        }

        "legacy activity rows without outcome evidence display Recorded activity" {
            seed(1, listOf("legacy-action"), null)

            testApplication {
                application { configureTestEnv() }
                val json = body(client.get("${Routes.API_HISTORY_ACTIVITIES}?preset=30d").bodyAsText())
                json["items"].size() shouldBe 1
                json["items"][0]["outcome"].asText() shouldBe "Recorded activity"
            }
        }

        "failed cycles without a complete portfolio snapshot still appear in activities" {
            seed(1, listOf("cycle aborted"), HistoryCycleOutcome.PROBLEM)

            testApplication {
                application { configureTestEnv() }
                val json = body(client.get("${Routes.API_HISTORY_ACTIVITIES}?preset=30d").bodyAsText())
                json["items"].size() shouldBe 1
                json["items"][0]["outcome"].asText() shouldBe "Problem"
                json["items"][0]["cycleId"].asText() shouldBe "cycle-001"
            }
        }

        "activity paging follows cursors and caps the page limit at 120" {
            for (index in 1..TOTAL_ROWS) {
                seed(index, listOf("action-$index"), HistoryCycleOutcome.DRY_RUN_PLAN)
            }
            realStore.queryActivityPage(HistoryActivityQuery(sourceScope = accountScope, limit = 200))
                .total shouldBe TOTAL_ROWS.toLong()

            testApplication {
                application { configureTestEnv() }
                val first = body(client.get("${Routes.API_HISTORY_ACTIVITIES}?preset=30d&limit=500").bodyAsText())
                first["items"].size() shouldBe 120
                first["hasMore"].asBoolean() shouldBe true
                first["nextCursor"].asText().shouldNotBeBlank()
                val firstIds = first["items"].map { it["cycleId"].asText() }
                firstIds.last() shouldBe "cycle-011"

                val second = body(
                    client.get(
                        "${Routes.API_HISTORY_ACTIVITIES}?preset=30d&limit=500&cursor=${first["nextCursor"].asText()}",
                    ).bodyAsText(),
                )
                val secondIds = second["items"].map { it["cycleId"].asText() }
                secondIds shouldContainExactly (10 downTo 1).map { "cycle-%03d".format(it) }
                second["hasMore"].asBoolean() shouldBe false
                second["nextCursor"].isNull shouldBe true

                (firstIds + secondIds).toSet().size shouldBe TOTAL_ROWS
                firstIds.first() shouldBe "cycle-130"
                secondIds.last() shouldBe "cycle-001"
            }
        }
    }

    companion object {
        private const val TOTAL_ROWS = 130
    }
}
