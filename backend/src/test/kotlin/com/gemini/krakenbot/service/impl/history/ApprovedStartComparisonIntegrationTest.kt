package com.gemini.krakenbot.service.impl.history

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.DatabaseConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.ComparisonAvailability
import com.gemini.krakenbot.model.ComparisonProposalStatus
import com.gemini.krakenbot.model.ComparisonUnavailableReason
import com.gemini.krakenbot.model.OrderSide
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.model.TradeSource
import com.gemini.krakenbot.repository.OrderIntentRepository
import com.gemini.krakenbot.repository.impl.SqliteLedgerRepositoryImpl
import com.gemini.krakenbot.repository.impl.SqlitePortfolioStatsRepositoryImpl
import com.gemini.krakenbot.repository.impl.SqliteTradeRepositoryImpl
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.FakeKrakenService
import com.gemini.krakenbot.service.InceptionRecoveryStatus
import com.gemini.krakenbot.service.impl.history.TradeHistoryReconstructionService
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.file.Files
import java.time.Instant

class ApprovedStartComparisonIntegrationTest :
    StringSpec({

        isolationMode = IsolationMode.InstancePerTest

        val database = DatabaseConfig.init(TestFixtures.MEMORY_)
        val repository = SqliteTradeRepositoryImpl(database)
        val ledgerRepository = SqliteLedgerRepositoryImpl(database)
        val statsRepository =
            SqlitePortfolioStatsRepositoryImpl(
                database,
                jacksonObjectMapper(),
                Files.createTempDirectory("approved-start-stats").resolve("portfolio-stats.json").toString(),
            )
        val krakenService = FakeKrakenService()
        val configService = mockk<ConfigService>(relaxed = true)
        val reconstructionService = mockk<TradeHistoryReconstructionService>(relaxed = true)
        val trustedScopeGuard = mockk<AccountHistoryScopeGuard>(relaxed = true)

        val strategyStart = Instant.parse("2026-01-02T00:00:00Z")
        var now = Instant.parse("2026-05-01T00:00:00Z")
        var config =
            AppConfig(
                kraken = KrakenCredentials(TestFixtures.TRADE_HISTORY_API_KEY, TestFixtures.TRADE_HISTORY_API_SECRET),
                settings = TestFixtures.settings(dryRun = false, simulation = false)
                    .copy(inceptionDate = strategyStart.toString()),
                allocations = listOf(Allocation(Asset.BTC, 50.0), Allocation(Asset.USD, 50.0)),
            )

        every { configService.getConfig() } answers { config }
        coEvery { trustedScopeGuard.validateAccountScope() } coAnswers {
            when {
                config.settings.simulation -> AccountScopeValidationResult.SIMULATION

                !config.kraken.hasValidCredentials() ->
                    AccountScopeValidationResult.scopeUnavailable("credentials unavailable")

                else ->
                    AccountScopeValidationResult(
                        status = AccountScopeValidationStatus.VALID,
                        currentScopeDigest = AccountHistoryScopeGuard.digestAccountScope("fake-account"),
                    )
            }
        }
        coEvery { trustedScopeGuard.readLocalTrustState() } coAnswers {
            AccountScopeValidationResult(
                status = AccountScopeValidationStatus.VALID,
                currentScopeDigest = AccountHistoryScopeGuard.digestAccountScope("fake-account"),
            )
        }

        val tradeHistorySyncService =
            TradeHistorySyncService(
                repository,
                krakenService,
                configService,
                reconstructionService,
                nowProvider = { now },
            )

        fun newRecoveryService(orderIntentRepository: OrderIntentRepository? = null) = InceptionRecoveryService(
            repository = repository,
            ledgerRepository = ledgerRepository,
            krakenService = krakenService,
            configService = configService,
            tradeHistorySyncService = tradeHistorySyncService,
            orderIntentRepository = orderIntentRepository,
            accountHistoryScopeGuard = trustedScopeGuard,
            nowProvider = { now },
        )

        fun newDiscoveryService(recoveryService: InceptionRecoveryService) =
            InceptionDiscoveryService(repository, configService, nowProvider = {
                now
            }, recoveryService = recoveryService)

        fun newQueryService(discoveryService: InceptionDiscoveryService) = TradeHistoryQueryService(
            repository = repository,
            portfolioStatsRepository = statsRepository,
            ledgerRepository = ledgerRepository,
            inceptionDiscoveryService = discoveryService,
            nowProvider = { now },
        )

        fun seedTrade(id: String, timestamp: Instant, owned: Boolean) = runTest {
            val trade =
                TestFixtures.tradeRecord(
                    timestamp = timestamp,
                    pair = Asset.tradingPair(Asset.BTC),
                    side = OrderSide.BUY.apiValue,
                    symbol = Asset.BTC,
                    volume = BigDecimal("0.01"),
                    usdAmount = BigDecimal("1.00"),
                    price = BigDecimal("100.00"),
                    fee = BigDecimal("0.01"),
                    source = TradeSource.API_FILL,
                    cycleId = if (owned) "cycle-$id" else null,
                    orderTxid = "order-$id",
                    tradeId = "trade-$id",
                    clientOrderId = if (owned) "client-$id" else null,
                )
            repository.saveTrade(trade)
        }

        fun seedUnknownTrade(timestamp: Instant) = runTest {
            repository.saveTrade(
                TestFixtures.tradeRecord(
                    timestamp = timestamp,
                    pair = Asset.tradingPair(Asset.BTC),
                    side = OrderSide.BUY.apiValue,
                    symbol = Asset.BTC,
                    volume = BigDecimal("0.01"),
                    usdAmount = BigDecimal("1.00"),
                    price = BigDecimal("100.00"),
                    fee = BigDecimal("0.01"),
                    source = TradeSource.LEGACY_UNKNOWN,
                    cycleId = null,
                    orderTxid = null,
                    tradeId = null,
                    clientOrderId = null,
                ),
            )
        }

        fun seedSnapshot(timestamp: Instant, btcPrice: String, btcBalance: String, usdBalance: String) = runTest {
            val price = BigDecimal(btcPrice)
            val btcBalanceValue = BigDecimal(btcBalance)
            val usdBalanceValue = BigDecimal(usdBalance)
            val btcValue = btcBalanceValue.multiply(price).setScale(2, RoundingMode.HALF_UP)
            val total = btcValue.add(usdBalanceValue)
            val snapshot =
                PortfolioSnapshot(
                    timestamp = timestamp,
                    totalValueUSD = total,
                    assets = mapOf(
                        Asset.BTC to TestFixtures.assetSnapshot(
                            symbol = Asset.BTC,
                            balance = btcBalanceValue,
                            price = price,
                            valueUSD = btcValue,
                            targetPercent = BigDecimal.valueOf(50.0),
                        ),
                        Asset.USD to TestFixtures.assetSnapshot(
                            symbol = Asset.USD,
                            balance = usdBalanceValue,
                            price = BigDecimal.ONE,
                            valueUSD = usdBalanceValue,
                            targetPercent = BigDecimal.valueOf(50.0),
                        ),
                    ),
                    actions = emptyList(),
                    drawdownPercent = BigDecimal.ZERO,
                    fiatDeploymentPercent = BigDecimal.ZERO,
                    effectiveUsdTargetPercent = BigDecimal.valueOf(50.0),
                    balancesObservedAt = timestamp,
                )
            repository.saveSnapshot(snapshot)
        }

        suspend fun baselineSnapshot(): PortfolioSnapshot {
            val snapshots = repository.getSnapshotsInRange(Instant.EPOCH, Instant.parse("2026-01-03T00:00:00Z"))
            return snapshots.first { it.balancesObservedAt == null }
        }

        val comparisonEnd = Instant.parse("2026-01-04T00:00:00Z")

        "approved start with complete history produces available comparison data" {
            runTest {
                seedTrade("t0", strategyStart.minusSeconds(60), owned = false)
                seedTrade("t1", strategyStart.plusSeconds(60), owned = true)
                seedSnapshot(Instant.parse("2026-01-03T00:00:00Z"), "100.00", "0.03", "999.00")
                seedSnapshot(Instant.parse("2026-01-04T00:00:00Z"), "200.00", "0.03", "999.00")
                krakenService.tradeHistoryTotalCountOverride = 2

                val recovery = newRecoveryService()
                val status = recovery.recoverOneBoundedRun()

                status.status shouldBe InceptionRecoveryStatus.CONFIRMED
                status.reason shouldBe "approved-start baseline ready"

                val baseline = baselineSnapshot()
                baseline.timestamp shouldBe strategyStart
                baseline.assets.getValue(Asset.BTC).balance shouldBeEqualComparingTo BigDecimal("0.02")
                baseline.assets.getValue(Asset.USD).balance shouldBeEqualComparingTo BigDecimal("1000.01")
                baseline.assets.getValue(Asset.BTC).price shouldBeEqualComparingTo BigDecimal("100.00000000")
                baseline.totalValueUSD shouldBeEqualComparingTo BigDecimal("1002.01")

                val discovery = newDiscoveryService(recovery)
                val comparison =
                    newQueryService(discovery).getRebalancerComparison(strategyStart, comparisonEnd)

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.baselineTimestamp shouldBe strategyStart
                comparison.proposedBaselineTimestamp.shouldBeNull()
                comparison.points.map { it.rebalancerValueUSD } shouldBe listOf(
                    BigDecimal("1002.01"),
                    BigDecimal("1002.00"),
                    BigDecimal("1005.00"),
                )
                comparison.points.map { it.buyAndHoldValueUSD } shouldBe listOf(
                    BigDecimal("1002.01"),
                    BigDecimal("1002.01"),
                    BigDecimal("1004.01"),
                )
                requireNotNull(comparison.latestDifferenceUSD) shouldBeEqualComparingTo BigDecimal("0.99")
            }
        }

        "restart after an approved baseline is idempotent and keeps a single baseline" {
            runTest {
                seedTrade("t0", strategyStart.minusSeconds(60), owned = false)
                seedTrade("t1", strategyStart.plusSeconds(60), owned = true)
                seedSnapshot(Instant.parse("2026-01-03T00:00:00Z"), "100.00", "0.03", "999.00")
                krakenService.tradeHistoryTotalCountOverride = 2

                val recovery = newRecoveryService()
                recovery.recoverOneBoundedRun().status shouldBe InceptionRecoveryStatus.CONFIRMED
                val baselineId = repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_APPROVED_BASELINE_SNAPSHOT_ID)
                val snapshotsAfterFirstRun = repository.getSnapshotsInRange(Instant.EPOCH, now).size

                now = now.plusSeconds(301)
                recovery.recoverOneBoundedRun().status shouldBe InceptionRecoveryStatus.CONFIRMED

                repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_APPROVED_BASELINE_SNAPSHOT_ID) shouldBe baselineId
                repository.getSnapshotsInRange(Instant.EPOCH, now).size shouldBe snapshotsAfterFirstRun
            }
        }

        "confirmed baseline still proposes and accepts a later start when later ownership is unknown" {
            runTest {
                seedTrade("t0", strategyStart.minusSeconds(60), owned = false)
                seedTrade("t1", strategyStart.plusSeconds(60), owned = true)
                val unknownTime = strategyStart.plusSeconds(3_600)
                seedUnknownTrade(unknownTime)
                seedSnapshot(unknownTime.plusSeconds(60), "100.00", "0.04", "998.00")
                val verifiedStart = unknownTime.plusSeconds(120)
                seedSnapshot(verifiedStart, "100.00", "0.04", "998.00")
                seedSnapshot(unknownTime.plusSeconds(180), "100.00", "0.04", "998.00")
                seedSnapshot(Instant.parse("2026-01-03T00:00:00Z"), "100.00", "0.04", "998.00")
                krakenService.tradeHistoryTotalCountOverride = 2

                val recovery = newRecoveryService()
                recovery.recoverOneBoundedRun().status shouldBe InceptionRecoveryStatus.CONFIRMED
                val discovery = newDiscoveryService(recovery)
                val query = newQueryService(discovery)

                val blocked = query.getRebalancerComparison(strategyStart, comparisonEnd)

                blocked.availability shouldBe ComparisonAvailability.UNAVAILABLE
                blocked.unavailableReason shouldBe ComparisonUnavailableReason.AMBIGUOUS_TRADE_OWNERSHIP
                blocked.proposedBaselineTimestamp shouldBe verifiedStart
                blocked.proposalSearchStatus shouldBe ComparisonProposalStatus.VERIFIED
                val settingsProposal = query.getComparisonStartProposal(strategyStart)
                settingsProposal?.status shouldBe ComparisonProposalStatus.VERIFIED
                settingsProposal?.timestamp shouldBe verifiedStart

                config = config.copy(
                    settings = config.settings.copy(comparisonStartDate = verifiedStart.toString()),
                )
                val acceptedDiscovery = newDiscoveryService(recovery)
                acceptedDiscovery.resolveInception().inceptionTime shouldBe verifiedStart
                recovery.getStatus().status shouldBe InceptionRecoveryStatus.CONFIRMED
                val accepted = newQueryService(acceptedDiscovery)
                    .getRebalancerComparison(strategyStart, comparisonEnd)

                accepted.availability shouldBe ComparisonAvailability.AVAILABLE
                accepted.baselineTimestamp shouldBe verifiedStart
                accepted.proposedBaselineTimestamp.shouldBeNull()
                accepted.proposalSearchStatus.shouldBeNull()
                config.settings.inceptionDate shouldBe strategyStart.toString()
            }
        }

        "a legacy retention gap blocks a later proposal even when a later anchor reconciles" {
            runTest {
                val unknownTime = strategyStart.plusSeconds(3_600)
                val firstSurvivingSnapshot = Instant.parse("2026-02-01T00:00:00Z")
                val laterStart = firstSurvivingSnapshot.plusSeconds(86_400)
                val comparisonEnd = laterStart.plusSeconds(86_400)

                seedSnapshot(strategyStart, "100.00", "0.03", "999.00")
                seedUnknownTrade(unknownTime)
                seedSnapshot(firstSurvivingSnapshot, "100.00", "0.04", "998.00")
                seedSnapshot(laterStart, "100.00", "0.04", "998.00")
                seedSnapshot(comparisonEnd, "100.00", "0.04", "998.00")
                krakenService.tradeHistoryTotalCountOverride = 0

                val recovery = newRecoveryService()
                recovery.recoverOneBoundedRun().status shouldBe InceptionRecoveryStatus.CONFIRMED
                val query = newQueryService(newDiscoveryService(recovery))

                val blocked = query.getRebalancerComparison(strategyStart, comparisonEnd)

                blocked.availability shouldBe ComparisonAvailability.UNAVAILABLE
                blocked.unavailableReason shouldBe ComparisonUnavailableReason.HISTORICAL_COVERAGE_GAP
                blocked.proposedBaselineTimestamp.shouldBeNull()
                blocked.proposalSearchStatus.shouldBeNull()
                query.getComparisonStartProposal(strategyStart).shouldBeNull()
                query.findVerifiedLaterComparisonStart(strategyStart).shouldBeNull()

                // The later candidate itself is reconcilable; the missing retained era is what
                // makes it unsafe to claim that candidate is the earliest trustworthy start.
                config = config.copy(
                    settings = config.settings.copy(comparisonStartDate = laterStart.toString()),
                )
                val laterComparison = newQueryService(newDiscoveryService(recovery))
                    .getRebalancerComparison(strategyStart, comparisonEnd)
                laterComparison.availability shouldBe ComparisonAvailability.AVAILABLE
                laterComparison.baselineTimestamp shouldBe laterStart
            }
        }

        "continuous snapshots across the retention boundary keep later-start proposals working" {
            runTest {
                seedSnapshot(strategyStart, "100.00", "0.03", "999.00")
                val unknownTime = strategyStart.plusSeconds(2 * 86_400L + 3_600L)
                seedUnknownTrade(unknownTime)
                val days = ((now.epochSecond - strategyStart.epochSecond) / 86_400L).toInt()
                for (day in 1..days) {
                    seedSnapshot(
                        strategyStart.plusSeconds(day * 86_400L),
                        "100.00",
                        "0.04",
                        "998.00",
                    )
                }
                krakenService.tradeHistoryTotalCountOverride = 0

                val recovery = newRecoveryService()
                recovery.recoverOneBoundedRun().status shouldBe InceptionRecoveryStatus.CONFIRMED
                val query = newQueryService(newDiscoveryService(recovery))
                val comparison = query.getRebalancerComparison(strategyStart, now)

                comparison.availability shouldBe ComparisonAvailability.UNAVAILABLE
                comparison.unavailableReason shouldBe ComparisonUnavailableReason.AMBIGUOUS_TRADE_OWNERSHIP
                // The candidate immediately after the unknown event still has that event in
                // its reconciliation window; the following retained observation is the first
                // one that proves a clean comparison start.
                comparison.proposedBaselineTimestamp shouldBe strategyStart.plusSeconds(4 * 86_400L)
                comparison.proposalSearchStatus shouldBe ComparisonProposalStatus.VERIFIED
            }
        }

        "pending approved-start recovery keeps the comparison unavailable without a proposal" {
            runTest {
                seedTrade("t0", strategyStart.minusSeconds(60), owned = false)
                seedTrade("t1", strategyStart.plusSeconds(60), owned = true)
                seedSnapshot(Instant.parse("2026-01-03T00:00:00Z"), "100.00", "0.03", "999.00")
                seedSnapshot(Instant.parse("2026-01-04T00:00:00Z"), "200.00", "0.03", "999.00")
                krakenService.tradeHistoryTotalCountOverride = 251

                val recovery = newRecoveryService()
                recovery.recoverOneBoundedRun().status shouldBe InceptionRecoveryStatus.IN_PROGRESS

                val discovery = newDiscoveryService(recovery)
                val comparison =
                    newQueryService(discovery).getRebalancerComparison(strategyStart, comparisonEnd)

                comparison.availability shouldBe ComparisonAvailability.UNAVAILABLE
                comparison.unavailableReason shouldBe ComparisonUnavailableReason.INCEPTION_RECOVERY_INCOMPLETE
                comparison.proposedBaselineTimestamp.shouldBeNull()
            }
        }

        "later-start proposal scan tolerates the open-ended snapshot range" {
            runTest {
                val discovery = newDiscoveryService(newRecoveryService())
                val proposal =
                    newQueryService(discovery).findVerifiedLaterComparisonStart(
                        Instant.parse("2026-06-01T00:00:00Z"),
                    )

                proposal.shouldBeNull()
            }
        }
    })
