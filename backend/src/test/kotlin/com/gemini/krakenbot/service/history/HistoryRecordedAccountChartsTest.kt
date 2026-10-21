package com.gemini.krakenbot.service.history

import com.gemini.krakenbot.service.reporting.HistoryReportingService
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import java.math.BigDecimal
import java.time.Instant

class HistoryRecordedAccountChartsTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    private val now = Instant.parse("2026-03-10T12:00:00Z")
    private val digest = "a".repeat(64)
    private val current = accountScope("b", listOf("BTC", "ETH", "USD"))
    private val earlier = accountScope("c", listOf("BTC", "USD"))

    init {
        "default portfolio automatically shows earlier disjoint asset sets with direct provenance and a break" {
            withPresenter { store, api ->
                store.appendObservation(sample(earlier, 3600, "100.00"))
                store.appendObservation(sample(earlier, 3000, null))
                store.appendObservation(sample(current, 1200, "200.00"))
                store.appendObservation(sample(current, 600, "210.00"))

                val response = charts(api)

                response.scopeSelection shouldBe HistoryApiScopeSelection.ACCOUNT_RECORDED
                response.series.map { it.key }.toSet() shouldBe setOf(earlier.sourceKey, current.sourceKey)
                response.fullRange.totalObservations shouldBe 4L
                val old = response.series.single { it.key == earlier.sourceKey }
                old.label shouldBe "BTC + USD | 2026-03-10T11:00:00Z .. 2026-03-10T11:10:00Z"
                old.points.first().v!!.shouldBeEqualComparingTo(BigDecimal("100.00"))
                old.points.last().v.shouldBeNull()
                old.points.last().complete shouldBe false
                old.provenance!!.scope.current shouldBe false
                old.provenance.balanceSources shouldContainExactly listOf("KRAKEN_PRIVATE_BALANCE")
                old.provenance.priceSources shouldContainExactly listOf("KRAKEN_PUBLIC_TICKER_LAST_TRADE_C0")
                old.provenance.walletScopes shouldContainExactly listOf("KRAKEN_DEFAULT_WALLET")
                response.boundaries shouldBe listOf(
                    HistoryApiBoundary(
                        HistoryApiBoundaryKind.ASSET_SET_CHANGE,
                        "2026-03-10T11:10:00Z",
                        "2026-03-10T11:40:00Z",
                        listOf(earlier.sourceKey, current.sourceKey),
                    ),
                )
                response.coverage.valuesFirstIso shouldBe "2026-03-10T11:00:00Z"
                response.coverage.gapCount shouldBe 1
            }
        }

        "chronological scope episodes keep both totals and break the line between episodes" {
            withPresenter { store, api ->
                store.appendObservation(sample(current, 3600, "100.00"))
                store.appendObservation(sample(current, 600, "200.00"))
                store.appendObservation(sample(earlier, 2400, "300.00"))
                store.appendObservation(sample(earlier, 1800, "400.00"))

                val response = charts(api, maxPoints = 2)

                response.boundaries.map { it.kind } shouldContainExactly listOf(
                    HistoryApiBoundaryKind.ASSET_SET_CHANGE,
                    HistoryApiBoundaryKind.ASSET_SET_CHANGE,
                )
                response.boundaries.map { it.fromIso } shouldContainExactly listOf(
                    "2026-03-10T11:00:00Z",
                    "2026-03-10T11:30:00Z",
                )
                val earlierSeries = response.series.single { it.key == earlier.sourceKey }
                earlierSeries.points.map { it.complete } shouldContainExactly listOf(true, true)
                earlierSeries.points.first().v!!.shouldBeEqualComparingTo(BigDecimal("300.00"))
                earlierSeries.points.last().v!!.shouldBeEqualComparingTo(BigDecimal("400.00"))
                val currentSeries = response.series.single { it.key == current.sourceKey }
                currentSeries.points.map { it.complete } shouldContainExactly listOf(true, false, true)
                currentSeries.points.first().v!!.shouldBeEqualComparingTo(BigDecimal("100.00"))
                currentSeries.points.last().v!!.shouldBeEqualComparingTo(BigDecimal("200.00"))
                response.fullRange.totalObservations shouldBe 4L
                response.coverage.incomplete shouldBe true

                val zoomed = charts(
                    api,
                    params = HistoryApiRangeParams(
                        preset = "all",
                        from = "2026-03-10T10:50:00Z",
                        to = "2026-03-10T11:25:00Z",
                    ),
                )
                zoomed.boundaries.single().kind shouldBe HistoryApiBoundaryKind.ASSET_SET_CHANGE
                zoomed.boundaries.single().fromIso shouldBe "2026-03-10T11:00:00Z"
                zoomed.boundaries.single().toIso shouldBe "2026-03-10T11:20:00Z"
                zoomed.series.single { it.key == earlier.sourceKey }.points.single()
                    .v!!.shouldBeEqualComparingTo(BigDecimal("300.00"))
            }
        }

        "two scopes observed at the same instant withhold both totals" {
            withPresenter { store, api ->
                store.appendObservation(sample(current, 1800, "100.00"))
                store.appendObservation(sample(earlier, 1800, "300.00"))

                val response = charts(api)

                response.boundaries.single().kind shouldBe HistoryApiBoundaryKind.OVERLAPPING_SCOPES
                response.boundaries.single().fromIso shouldBe "2026-03-10T11:30:00Z"
                response.series.forEach { series ->
                    series.points.forEach { point ->
                        point.v.shouldBeNull()
                        point.complete shouldBe false
                    }
                }
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

        "automatic account history excludes other accounts and simulation evidence" {
            withPresenter { store, api ->
                val foreign = current.copy(accountIdentityDigest = "d".repeat(64))
                val simulated = simulationScope("other-process")
                store.appendObservation(sample(current, 600, "200.00"))
                store.appendObservation(sample(foreign, 600, "900.00"))
                store.appendObservation(sample(simulated, 600, "800.00"))

                val response = charts(api)

                response.recordedScopes.map { it.id } shouldContainExactly listOf(current.sourceKey)
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
