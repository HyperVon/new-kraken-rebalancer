package com.gemini.krakenbot.service.history

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.Duration
import java.time.Instant

class HistoryServiceTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    private val store = mockk<HistoryStore>(relaxed = true)
    private val service = HistoryServiceImpl(store)

    private val accountDigest = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    private val fp = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210"

    private val accScope = HistorySourceScope(
        mode = HistorySourceMode.ACCOUNT,
        managedScopeFingerprint = fp,
        managedScopeSymbols = listOf("BTC", "USD"),
        accountIdentityDigest = accountDigest,
    )

    private val simScope = HistorySourceScope(
        mode = HistorySourceMode.SIMULATION,
        managedScopeFingerprint = fp,
        managedScopeSymbols = listOf("BTC", "USD"),
        simulationSessionId = "sim-123",
    )

    init {
        "delegates queries to store" {
            val rangeQuery = HistoryRangeQuery(sourceScope = accScope)
            val eventQuery = HistoryEventQuery(accountIdentityDigest = accountDigest)

            service.queryValues(rangeQuery)
            coVerify { store.queryObservations(rangeQuery) }

            service.queryAssetValues(rangeQuery)
            coVerify { store.queryAssetValues(rangeQuery) }

            service.queryTrades(eventQuery)
            coVerify { store.queryTrades(eventQuery) }

            service.queryLedgers(eventQuery)
            coVerify { store.queryLedgers(eventQuery) }

            service.queryBalanceCheckpoints(eventQuery)
            coVerify { store.queryBalanceCheckpoints(eventQuery) }

            service.queryDailyValues(accountDigest, "BTC+USD")
            coVerify { store.queryDailyValues(accountDigest, "BTC+USD", any(), any(), any(), any()) }

            service.queryDailyAssetValues(accountDigest, "BTC+USD")
            coVerify { store.queryDailyAssetValues(accountDigest, "BTC+USD", any(), any(), any(), any()) }

            service.latestObservation(accScope)
            coVerify { store.latestObservation(accScope) }

            val now = Instant.now()
            service.observationAtOrBefore(accScope, now, Duration.ofMinutes(5))
            coVerify { store.observationAtOrBefore(accScope, now, Duration.ofMinutes(5)) }

            service.queryCycleActivities(accScope, 10)
            coVerify { store.queryCycleActivities(accScope, 10) }
        }

        "getSyncStatus returns empty for simulation and delegates for account" {
            service.getSyncStatus(simScope) shouldBe emptyList()

            service.getSyncStatus(accScope)
            coVerify { store.syncStatus(accountDigest) }
        }

        "getDashboardHistory validates scope and assembles dashboard data" {
            val now = Instant.now()
            val wrongScope = HistorySourceScope(
                mode = HistorySourceMode.ACCOUNT,
                managedScopeFingerprint = "1111111111111111111111111111111111111111111111111111111111111111",
                managedScopeSymbols = listOf("ETH"),
                accountIdentityDigest = accountDigest,
            )

            // Scope mismatch in range
            shouldThrow<IllegalArgumentException> {
                service.getDashboardHistory(
                    sourceScope = accScope,
                    range = HistoryRangeQuery(sourceScope = wrongScope),
                    baselineTime = now,
                    baselineMaxAge = Duration.ofHours(24),
                )
            }

            val observations = HistoryPage(
                items = emptyList<HistoryObservation>(),
                total = 0L,
                limit = 50,
                offset = 0,
                hasMore = false,
            )
            coEvery { store.latestObservation(accScope) } returns null
            coEvery { store.observationAtOrBefore(any(), any(), any()) } returns null
            coEvery { store.queryObservations(any()) } returns observations
            coEvery { store.queryCycleActivities(any(), any()) } returns emptyList()
            coEvery { store.syncStatus(any()) } returns emptyList()

            val data = service.getDashboardHistory(
                sourceScope = accScope,
                range = HistoryRangeQuery(),
                baselineTime = now,
                baselineMaxAge = Duration.ofHours(24),
            )

            data.sourceScope shouldBe accScope
            data.latestObservation shouldBe null
            data.baselineObservation shouldBe null
            data.observations shouldBe observations
            data.recentCycleActivity shouldBe emptyList()
            data.syncStatus shouldBe emptyList()
            coVerify { store.latestObservation(accScope) }
            coVerify { store.queryObservations(match { it.sourceScope == accScope }) }
            coVerify { store.observationAtOrBefore(accScope, now, Duration.ofHours(24)) }
            coVerify { store.queryCycleActivities(accScope, 6) }
            coVerify { store.syncStatus(accountDigest) }
        }
    }
}
