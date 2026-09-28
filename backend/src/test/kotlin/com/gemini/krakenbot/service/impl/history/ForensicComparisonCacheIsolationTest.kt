package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.BenchmarkMethod
import com.gemini.krakenbot.model.ComparisonAvailability
import com.gemini.krakenbot.model.ComparisonConfidence
import com.gemini.krakenbot.model.ConfigurationEvidence
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.repository.LedgerRepository
import com.gemini.krakenbot.repository.PortfolioStatsRepository
import com.gemini.krakenbot.repository.TradeRepository
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.FakeKrakenService
import com.gemini.krakenbot.service.impl.history.LedgersSyncService
import com.gemini.krakenbot.service.impl.history.TradeHistorySyncService
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import java.time.Instant

/**
 * The forensic seam must never touch comparison cache state.
 *
 * A hand-derived epoch table is not a product benchmark, so a forensic run may not be served from a
 * product cache row and may not persist one that a later product request could read. These tests
 * assert both directions against the real service, using a cache that records every access.
 */
class ForensicComparisonCacheIsolationTest : StringSpec() {

    private val now: Instant = Instant.parse("2026-07-01T12:00:00Z")

    private fun snapshot(timestamp: Instant, totalValueUSD: String, btc: Pair<String, String>) = PortfolioSnapshot(
        timestamp = timestamp,
        totalValueUSD = BigDecimal(totalValueUSD),
        assets = mapOf(
            "BTC" to PortfolioSnapshot.AssetSnapshot(
                symbol = "BTC",
                balance = BigDecimal(btc.first),
                price = BigDecimal(btc.second),
                valueUSD = BigDecimal(btc.first) * BigDecimal(btc.second),
                targetPercent = BigDecimal("50"),
                currentPercent = BigDecimal("50"),
                deviationPercent = BigDecimal.ZERO,
                deviationUSD = BigDecimal.ZERO,
            ),
            "USD" to PortfolioSnapshot.AssetSnapshot(
                symbol = "USD",
                balance = BigDecimal(totalValueUSD) - BigDecimal(btc.first) * BigDecimal(btc.second),
                price = BigDecimal.ONE,
                valueUSD = BigDecimal(totalValueUSD) - BigDecimal(btc.first) * BigDecimal(btc.second),
                targetPercent = BigDecimal("50"),
                currentPercent = BigDecimal("50"),
                deviationPercent = BigDecimal.ZERO,
                deviationUSD = BigDecimal.ZERO,
            ),
        ),
        actions = emptyList(),
        drawdownPercent = BigDecimal.ZERO,
        fiatDeploymentPercent = BigDecimal.ZERO,
        effectiveUsdTargetPercent = BigDecimal("50"),
    )

    private fun service(
        repository: TradeRepository,
        statsRepository: PortfolioStatsRepository,
        ledgerRepository: LedgerRepository,
        cache: InMemoryComparisonCache,
    ): TradeHistoryQueryService {
        val configService = mockk<ConfigService>(relaxed = true)
        every { configService.getConfig() } returns TestFixtures.config(
            allocations = listOf(Allocation(Asset.BTC, 50.0), Allocation(Asset.USD, 50.0)),
        )
        return TradeHistoryQueryService(
            repository = repository,
            portfolioStatsRepository = statsRepository,
            ledgerRepository = ledgerRepository,
            comparisonCacheRepository = cache,
            configService = configService,
            krakenService = FakeKrakenService(),
            nowProvider = { now },
        )
    }

    init {
        "a forensic comparison neither reads nor writes comparison cache state" {
            runTest {
                val repository = mockk<TradeRepository>(relaxed = true)
                val statsRepository = mockk<PortfolioStatsRepository>(relaxed = true)
                val ledgerRepository = mockk<LedgerRepository>(relaxed = true)
                val cache = InMemoryComparisonCache()
                val query = service(repository, statsRepository, ledgerRepository, cache)

                val first = snapshot(now, "100000.00", btc = "1.0" to "50000.00")
                val second = snapshot(now.plusSeconds(3600), "100000.00", btc = "1.0" to "50000.00")
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_VERSION) } returns
                    TradeHistorySyncService.CURRENT_TRADE_COVERAGE_VERSION
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_START_EPOCH_SEC) } returns "0"
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC) } returns
                    "4102444800"
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_ACCOUNT_SCOPE_DIGEST) } returns
                    "test-scope"
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST) } returns
                    "test-scope"
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_VERSION) } returns
                    LedgersSyncService.CURRENT_LEDGER_COVERAGE_VERSION
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_START_EPOCH_SEC) } returns
                    "0"
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC) } returns
                    "4102444800"
                coEvery {
                    ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_ACCOUNT_SCOPE_DIGEST)
                } returns
                    "test-scope"
                coEvery { repository.getAllSnapshotsInRange(any(), any()) } returns listOf(first, second)
                coEvery { repository.getSnapshotBefore(any()) } returns null
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val forensic = query.getForensicRebalancerComparison(
                    Instant.EPOCH,
                    second.timestamp,
                    listOf(
                        InferredRegimeTransition(
                            clusterStart = now.plusSeconds(60),
                            clusterEnd = now.plusSeconds(60),
                            removals = emptySet(),
                            additions = setOf("BTC"),
                            confidence = RegimeTransitionConfidence.HIGH,
                        ),
                    ),
                )

                // Reconciliation of forensic variants, and the method/evidence they report, are proven
                // by the production acceptance run. What this test pins is narrower and load-bearing:
                // the forensic path must not read a product row, nor leave one behind, whatever the
                // comparison outcome is.
                (
                    forensic.availability == ComparisonAvailability.AVAILABLE ||
                        forensic.availability == ComparisonAvailability.UNAVAILABLE
                    ) shouldBe true
                cache.loadCount shouldBe 0
                cache.saveCount shouldBe 0
                cache.deleteCount shouldBe 0
            }
        }

        "a product comparison still uses the cache it is entitled to" {
            runTest {
                val repository = mockk<TradeRepository>(relaxed = true)
                val statsRepository = mockk<PortfolioStatsRepository>(relaxed = true)
                val ledgerRepository = mockk<LedgerRepository>(relaxed = true)
                val cache = InMemoryComparisonCache()
                val query = service(repository, statsRepository, ledgerRepository, cache)

                val first = snapshot(now, "100000.00", btc = "1.0" to "50000.00")
                val second = snapshot(now.plusSeconds(3600), "100000.00", btc = "1.0" to "50000.00")
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_VERSION) } returns
                    TradeHistorySyncService.CURRENT_TRADE_COVERAGE_VERSION
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_START_EPOCH_SEC) } returns "0"
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC) } returns
                    "4102444800"
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_ACCOUNT_SCOPE_DIGEST) } returns
                    "test-scope"
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST) } returns
                    "test-scope"
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_VERSION) } returns
                    LedgersSyncService.CURRENT_LEDGER_COVERAGE_VERSION
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_START_EPOCH_SEC) } returns
                    "0"
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC) } returns
                    "4102444800"
                coEvery {
                    ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_ACCOUNT_SCOPE_DIGEST)
                } returns
                    "test-scope"
                coEvery { repository.getAllSnapshotsInRange(any(), any()) } returns listOf(first, second)
                coEvery { repository.getSnapshotBefore(any()) } returns null
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                query.getRebalancerComparison(Instant.EPOCH, second.timestamp)
                    .availability shouldBe ComparisonAvailability.AVAILABLE

                // The bypass is specific to forensic runs; the shipping path still caches, and the
                // difference between the two is what makes the isolation meaningful.
                cache.size shouldBe 1
            }
        }
    }
}
