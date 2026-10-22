package com.gemini.krakenbot.service.history

import com.gemini.krakenbot.service.reporting.HistoryReportingService
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class HistoryRecordedAccountChartsTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    private val now = Instant.parse("2026-03-10T12:00:00Z")
    private val digest = "a".repeat(64)
    private val current = accountScope("b", listOf("BTC", "ETH", "USD"))
    private val earlier = accountScope("c", listOf("BTC", "USD"))

    init {
        "default account chart uses imported daily totals instead of stitching managed-only observations" {
            withPresenter { store, api ->
                store.appendObservation(sample(earlier, 3600, "100.00"))
                store.appendObservation(sample(current, 1200, "200.00"))
                val firstDay = LocalDate.parse("2026-03-08")
                val secondDay = firstDay.plusDays(1)
                store.saveDailyValues(
                    listOf(
                        dailyValue(firstDay, "300.00"),
                        dailyValue(secondDay, "340.00"),
                    ),
                )

                val response = charts(api)

                response.scopeSelection shouldBe HistoryApiScopeSelection.CURRENT_SCOPE
                response.scope.id shouldBe current.sourceKey
                response.series.single().key shouldBe HISTORY_API_PORTFOLIO_KEY
                response.series.single().points.map { it.v } shouldBe listOf(
                    BigDecimal("300.00"),
                    BigDecimal("340.00"),
                )
                response.fullRange.totalObservations shouldBe 2L
                response.resolution shouldBe "1d"
                response.valuationBasis shouldBe "Daily value · historical closing prices"
                response.recordedScopes shouldBe emptyList()
                response.boundaries shouldBe emptyList()
                response.availableValueFromIso shouldBe "2026-03-08T00:00:00Z"
            }
        }

        "asset filter normalizes symbols excludes equities and uses zero only for complete absent holdings" {
            withPresenter { store, api ->
                val firstDay = LocalDate.parse("2026-03-08")
                val secondDay = firstDay.plusDays(1)
                store.saveDailyValues(
                    listOf(
                        dailyValue(
                            firstDay,
                            "150.00",
                            listOf(
                                asset(firstDay, "currency", "XXBT", "100.00"),
                                asset(firstDay, "currency", "ZUSD", "50.00"),
                                asset(firstDay, "equity", "AAPL", "900.00"),
                            ),
                        ),
                        dailyValue(
                            secondDay,
                            "50.00",
                            listOf(asset(secondDay, "currency", "ZUSD", "50.00")),
                        ),
                    ),
                )

                val response = charts(
                    api,
                    params = HistoryApiRangeParams(preset = "all", valueAsset = "XXBT"),
                )

                response.selectedValueAsset shouldBe "BTC"
                response.availableValueAssets shouldBe listOf("BTC", "USD")
                response.series.single().key shouldBe "asset:BTC"
                response.series.single().points.map { it.v } shouldBe listOf(BigDecimal("100.00"), BigDecimal.ZERO)
                response.series.single().points.all { it.complete } shouldBe true
            }
        }

        "an incomplete daily account total remains a gap instead of exposing a known subtotal" {
            withPresenter { store, api ->
                val day = LocalDate.parse("2026-03-09")
                store.saveDailyValues(
                    listOf(
                        dailyValue(
                            day,
                            totalUsd = null,
                            assets = listOf(
                                asset(day, "currency", "XBT", "100.00"),
                                asset(
                                    day,
                                    "currency",
                                    "ETH",
                                    null,
                                    HistoryDailyValueStatus.NO_PRICE,
                                    "Historical USD price unavailable.",
                                ),
                            ),
                            status = HistoryDailyValueStatus.NO_PRICE,
                        ),
                    ),
                )

                val response = charts(api)

                response.series.single().points.single().v.shouldBeNull()
                response.series.single().points.single().complete shouldBe false
                response.series.single().points.single().reasons shouldBe
                    listOf("ETH: Historical USD price unavailable.")
                response.coverage.incomplete shouldBe true
            }
        }

        "explicit scope requests keep the existing exact-scope series and monetary values" {
            withPresenter { store, api ->
                store.appendObservation(sample(current, 1200, "200.00"))
                store.appendObservation(sample(earlier, 1200, "100.00"))

                val response = charts(api, scopeId = earlier.sourceKey)

                response.scopeSelection shouldBe HistoryApiScopeSelection.EXPLICIT_SCOPE
                response.scope.id shouldBe earlier.sourceKey
                response.series.single().key shouldBe "portfolio"
                response.series.single().points.single().v!!.shouldBeEqualComparingTo(BigDecimal("100.00"))
                response.boundaries shouldBe emptyList()
            }
        }

        "daily account chart excludes other account values and all managed-only observations" {
            withPresenter { store, api ->
                val foreign = current.copy(accountIdentityDigest = "d".repeat(64))
                val simulated = simulationScope("other-process")
                store.appendObservation(sample(current, 600, "200.00"))
                store.appendObservation(sample(foreign, 600, "900.00"))
                store.appendObservation(sample(simulated, 600, "800.00"))
                val day = LocalDate.parse("2026-03-09")
                store.saveDailyValues(
                    listOf(
                        dailyValue(day, "200.00"),
                        dailyValue(day, "900.00", accountIdentityDigest = foreign.accountIdentityDigest!!),
                    ),
                )

                val response = charts(api)

                response.scopeSelection shouldBe HistoryApiScopeSelection.CURRENT_SCOPE
                response.series.single().points.single().v!!.shouldBeEqualComparingTo(BigDecimal("200.00"))
                rejectsScope { charts(api, scopeId = foreign.sourceKey) }
                rejectsScope { charts(api, scopeId = simulated.sourceKey) }
            }
        }

        "simulation charts remain limited to the active process and reject prior process scope ids" {
            withPresenter { store, api ->
                val active = simulationScope("active-process")
                val previous = simulationScope("previous-process")
                store.appendObservation(sample(active, 600, "50.00"))
                store.appendObservation(sample(previous, 600, "90.00"))
                store.appendObservation(sample(current, 600, "200.00"))

                val response = charts(api, currentScope = active)

                response.scopeSelection shouldBe HistoryApiScopeSelection.CURRENT_SCOPE
                response.scope.id shouldBe active.sourceKey
                response.series.single().key shouldBe "portfolio"
                response.series.single().points.single().v!!.shouldBeEqualComparingTo(BigDecimal("50.00"))
                response.recordedScopes shouldBe emptyList()
                response.boundaries shouldBe emptyList()
                rejectsScope { charts(api, currentScope = active, scopeId = previous.sourceKey) }
            }
        }

        "summary explicitly describes current scope rather than inventing a cross-scope change" {
            withPresenter { store, api ->
                store.appendObservation(sample(earlier, 3600, "100.00"))
                store.appendObservation(sample(current, 600, "200.00"))

                val summary = api.summary(HistoryApiRangeParams(preset = "all"), current, null, emptyList())
                val explicit = api.summary(
                    HistoryApiRangeParams(preset = "all"),
                    current,
                    earlier.sourceKey,
                    emptyList(),
                )

                summary.scopeSelection shouldBe HistoryApiScopeSelection.CURRENT_SCOPE
                summary.scope.id shouldBe current.sourceKey
                explicit.scopeSelection shouldBe HistoryApiScopeSelection.EXPLICIT_SCOPE
                explicit.scope.id shouldBe earlier.sourceKey
            }
        }
    }

    private fun accountScope(fingerprint: String, symbols: List<String>) = HistorySourceScope(
        mode = HistorySourceMode.ACCOUNT,
        managedScopeFingerprint = fingerprint.repeat(64),
        managedScopeSymbols = symbols,
        accountIdentityDigest = digest,
    )

    private fun simulationScope(sessionId: String) = HistorySourceScope(
        mode = HistorySourceMode.SIMULATION,
        managedScopeFingerprint = "e".repeat(64),
        managedScopeSymbols = listOf("BTC", "USD"),
        simulationSessionId = sessionId,
    )

    private fun sample(scope: HistorySourceScope, secondsAgo: Long, total: String?): HistoryObservation {
        val at = now.minusSeconds(secondsAgo)
        val id = "${scope.sourceKey}:$secondsAgo"
        return HistoryObservation(
            observationId = id,
            cycleId = id,
            sourceScope = scope,
            observedAt = at,
            balanceRequestStartedAt = at.minusSeconds(2),
            balanceResponseEndedAt = at.minusSeconds(1),
            priceRequestStartedAt = at.minusSeconds(1),
            priceResponseEndedAt = at,
            status = if (total == null) HistoryObservationStatus.INCOMPLETE else HistoryObservationStatus.COMPLETE,
            totalUsd = total?.let(::BigDecimal),
            incompleteReasons = if (total == null) listOf("missing-price") else emptyList(),
            assets = emptyList(),
        )
    }

    private fun dailyValue(
        day: LocalDate,
        totalUsd: String?,
        assets: List<HistoryDailyAssetValue> = emptyList(),
        accountIdentityDigest: String = digest,
        status: HistoryDailyValueStatus = if (totalUsd ==
            null
        ) {
            HistoryDailyValueStatus.INCOMPLETE
        } else {
            HistoryDailyValueStatus.COMPLETE
        },
    ) = HistoryDailyValue(
        dayEnd = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant(),
        accountIdentityDigest = accountIdentityDigest,
        sourceAssetScope = HISTORY_ACCOUNT_DAILY_SCOPE,
        totalUsd = totalUsd?.let(::BigDecimal),
        status = status,
        assets = assets,
        dayLabel = day,
        generationId = "daily-chart-test",
        calculationPolicyVersion = HISTORY_DAILY_VALUE_POLICY_VERSION,
        sourceRevision = 1,
    )

    private fun asset(
        day: LocalDate,
        assetClass: String,
        key: String,
        valueUsd: String?,
        status: HistoryDailyValueStatus = HistoryDailyValueStatus.COMPLETE,
        reason: String? = null,
    ) = HistoryDailyAssetValue(
        dayEnd = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant(),
        accountIdentityDigest = digest,
        rawAssetClass = assetClass,
        rawAssetKey = key,
        quantity = BigDecimal.ONE,
        priceUsd = valueUsd?.let(::BigDecimal),
        valueUsd = valueUsd?.let(::BigDecimal),
        pricePair = null,
        priceCandleOpen = null,
        priceSource = null,
        status = status,
        reason = reason,
    )

    private suspend fun charts(
        api: HistoryApiPresenter,
        currentScope: HistorySourceScope = current,
        scopeId: String? = null,
        maxPoints: Int? = null,
        params: HistoryApiRangeParams = HistoryApiRangeParams(preset = "all"),
    ) = api.charts(params, currentScope, null, scopeId, maxPoints)

    private suspend fun rejectsScope(block: suspend () -> Unit) {
        val error = try {
            block()
            null
        } catch (error: HistoryApiRequestException) {
            error.message
        }
        error shouldBe "Unknown recorded portfolio scope."
    }

    private suspend fun withPresenter(block: suspend (HistoryStore, HistoryApiPresenter) -> Unit) {
        HistoryDatabase.inMemory().use { database ->
            database.initializeFreshSchema()
            val store = SqliteHistoryStore(database)
            val service = HistoryServiceImpl(store)
            block(
                store,
                HistoryApiPresenter(
                    historyService = service,
                    historyStore = store,
                    reportingService = HistoryReportingService(service) { now },
                    syncCoordinator = mockk(relaxed = true),
                    nowProvider = { now },
                ),
            )
        }
    }
}
