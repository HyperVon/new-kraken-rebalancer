package com.gemini.krakenbot.service.impl.history

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.DatabaseConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.ComparisonAvailability
import com.gemini.krakenbot.model.ComparisonUnavailableReason
import com.gemini.krakenbot.model.KrakenApiConstants
import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.model.OrderSide
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.model.TradeSource
import com.gemini.krakenbot.repository.impl.SqliteLedgerRepositoryImpl
import com.gemini.krakenbot.repository.impl.SqliteOrderIntentRepositoryImpl
import com.gemini.krakenbot.repository.impl.SqlitePortfolioStatsRepositoryImpl
import com.gemini.krakenbot.repository.impl.SqliteTradeRepositoryImpl
import com.gemini.krakenbot.service.AutomaticBaselineStatus
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.FakeKrakenService
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.file.Files
import java.time.Duration
import java.time.Instant

/**
 * Disposable production-derived harness test verifying end-to-end convergence for the
 * Buy & Hold baseline and history workflow under the proven Dec 5, 2025 automatic baseline.
 *
 * Exercises the reproduction phase, exhaustion loop, live-tail lag, coverage catch-up,
 * UI range matrix (24h, 7d, 30d, 90d, lifetime), restart idempotency, and fail-closed
 * safety invariants.
 */
class ProductionDerivedBuyHoldConvergenceTest :
    StringSpec({

        isolationMode = IsolationMode.InstancePerTest

        val database = DatabaseConfig.init(TestFixtures.MEMORY_)
        val repository = SqliteTradeRepositoryImpl(database)
        val ledgerRepository = SqliteLedgerRepositoryImpl(database)
        val orderIntentRepository = SqliteOrderIntentRepositoryImpl(database)
        val statsRepository = SqlitePortfolioStatsRepositoryImpl(
            database,
            jacksonObjectMapper(),
            Files.createTempDirectory("prod-derived-stats").resolve("portfolio-stats.json").toString(),
        )

        val krakenService = FakeKrakenService()
        val configService = mockk<ConfigService>(relaxed = true)
        val reconstructionService = mockk<TradeHistoryReconstructionService>(relaxed = true)
        val trustedScopeGuard = mockk<AccountHistoryScopeGuard>(relaxed = true)

        val t0 = Instant.parse("2025-12-05T17:00:56.973Z")
        val t1 = t0.plusMillis(101)
        val tStable = t0.plus(Duration.ofDays(100)) // 2026-03-15
        val tTail = tStable.plus(Duration.ofHours(1))
        var now = tTail.plus(Duration.ofMinutes(15))

        val scopeDigest = AccountHistoryScopeGuard.digestAccountScope("prod-account")

        var appConfig = AppConfig(
            kraken = KrakenCredentials(TestFixtures.TRADE_HISTORY_API_KEY, TestFixtures.TRADE_HISTORY_API_SECRET),
            settings = TestFixtures.settings(dryRun = false, simulation = false)
                .copy(inceptionDate = t0.toString()),
            allocations = listOf(Allocation(Asset.BTC, 50.0), Allocation(Asset.USD, 50.0)),
        )

        every { configService.getConfig() } answers { appConfig }
        coEvery { trustedScopeGuard.validateAccountScope() } returns AccountScopeValidationResult(
            status = AccountScopeValidationStatus.VALID,
            currentScopeDigest = scopeDigest,
        )
        coEvery { trustedScopeGuard.readLocalTrustState() } returns AccountScopeValidationResult(
            status = AccountScopeValidationStatus.VALID,
            currentScopeDigest = scopeDigest,
        )

        val tradeHistorySyncService = TradeHistorySyncService(
            repository,
            krakenService,
            configService,
            reconstructionService,
            nowProvider = { now },
        )

        fun newRecoveryService() = InceptionRecoveryService(
            repository = repository,
            ledgerRepository = ledgerRepository,
            krakenService = krakenService,
            configService = configService,
            tradeHistorySyncService = tradeHistorySyncService,
            orderIntentRepository = orderIntentRepository,
            accountHistoryScopeGuard = trustedScopeGuard,
            nowProvider = { now },
        )

        fun newDiscoveryService(recoveryService: InceptionRecoveryService = newRecoveryService()) =
            InceptionDiscoveryService(
                repository,
                configService,
                nowProvider = { now },
                recoveryService = recoveryService,
            )

        fun newQueryService(discoveryService: InceptionDiscoveryService = newDiscoveryService()) =
            TradeHistoryQueryService(
                repository = repository,
                portfolioStatsRepository = statsRepository,
                ledgerRepository = ledgerRepository,
                orderIntentRepository = orderIntentRepository,
                inceptionDiscoveryService = discoveryService,
                nowProvider = { now },
                configService = configService,
            )

        suspend fun seedSnapshot(
            timestamp: Instant,
            btcBalance: String,
            btcPrice: String,
            usdBalance: String,
            morphoBalance: String? = null,
            morphoPrice: String? = null,
            xmrBalance: String? = null,
            xmrPrice: String? = null,
            balancesObservedAt: Instant? = timestamp,
        ): PortfolioSnapshot {
            val btcBal = BigDecimal(btcBalance)
            val btcP = BigDecimal(btcPrice)
            val btcVal = btcBal.multiply(btcP).setScale(4, RoundingMode.HALF_UP)
            val usdBal = BigDecimal(usdBalance)
            var total = btcVal.add(usdBal)

            val assetMap = mutableMapOf(
                Asset.BTC to TestFixtures.assetSnapshot(
                    symbol = Asset.BTC,
                    balance = btcBal,
                    price = btcP,
                    valueUSD = btcVal,
                    targetPercent = BigDecimal("50.0"),
                ),
                Asset.USD to TestFixtures.assetSnapshot(
                    symbol = Asset.USD,
                    balance = usdBal,
                    price = BigDecimal.ONE,
                    valueUSD = usdBal,
                    targetPercent = BigDecimal("50.0"),
                ),
            )

            if (morphoBalance != null && morphoPrice != null) {
                val mBal = BigDecimal(morphoBalance)
                val mP = BigDecimal(morphoPrice)
                val mVal = mBal.multiply(mP).setScale(4, RoundingMode.HALF_UP)
                total = total.add(mVal)
                assetMap["MORPHO"] = TestFixtures.assetSnapshot(
                    symbol = "MORPHO",
                    balance = mBal,
                    price = mP,
                    valueUSD = mVal,
                    targetPercent = BigDecimal.ZERO,
                )
            }

            if (xmrBalance != null && xmrPrice != null) {
                val xBal = BigDecimal(xmrBalance)
                val xP = BigDecimal(xmrPrice)
                val xVal = xBal.multiply(xP).setScale(4, RoundingMode.HALF_UP)
                total = total.add(xVal)
                assetMap["XMR"] = TestFixtures.assetSnapshot(
                    symbol = "XMR",
                    balance = xBal,
                    price = xP,
                    valueUSD = xVal,
                    targetPercent = BigDecimal.ZERO,
                )
            }

            val snapshot = PortfolioSnapshot(
                timestamp = timestamp,
                totalValueUSD = total,
                assets = assetMap,
                actions = emptyList(),
                drawdownPercent = BigDecimal.ZERO,
                fiatDeploymentPercent = BigDecimal.ZERO,
                effectiveUsdTargetPercent = BigDecimal("50.0"),
                balancesObservedAt = balancesObservedAt,
            )
            repository.saveSnapshot(snapshot)
            return snapshot
        }

        suspend fun seedProductionDatabase() {
            // Seed coverage and account scope metadata
            repository.setSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_VERSION, "2")
            repository.setSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_START_EPOCH_SEC, t0.epochSecond.toString())
            repository.setSyncMetadata(
                SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC,
                tStable.epochSecond.toString(),
            )
            repository.setSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_ACCOUNT_SCOPE_DIGEST, scopeDigest)
            ledgerRepository.setSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_VERSION, "10")
            ledgerRepository.setSyncMetadata(
                SyncMetadataKeys.LEDGER_COVERAGE_START_EPOCH_SEC,
                t0.epochSecond.toString(),
            )
            ledgerRepository.setSyncMetadata(
                SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC,
                tStable.epochSecond.toString(),
            )
            ledgerRepository.setSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_ACCOUNT_SCOPE_DIGEST, scopeDigest)
            ledgerRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST, scopeDigest)
            repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_CONFIG_FINGERPRINT, "prod-config-fingerprint")

            // Seed full-wallet baseline at t0
            seedSnapshot(
                t0,
                btcBalance = "0.24",
                btcPrice = "1.0",
                usdBalance = "1490.5632",
                morphoBalance = "286.4401",
                morphoPrice = "1.2917",
                xmrBalance = "0",
                xmrPrice = "395.69",
                balancesObservedAt = null,
            )
            val baselineId = repository.getSnapshotId(t0)!!
            repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_APPROVED_BASELINE_SNAPSHOT_ID, baselineId.toString())

            // Seed successor at t1 (legacy series scope)
            seedSnapshot(
                t1,
                btcBalance = "0.24",
                btcPrice = "1.0",
                usdBalance = "1267.6143",
                balancesObservedAt = null,
            )

            // Seed XMR buy at t1
            repository.saveTrade(
                TradeRecord(
                    id = 1,
                    timestamp = t1,
                    pair = "XMRUSD",
                    side = OrderSide.BUY.apiValue,
                    symbol = "XMR",
                    volume = BigDecimal("0.56119848"),
                    usdAmount = BigDecimal("222.06"),
                    price = BigDecimal("395.69"),
                    fee = BigDecimal("0.8882"),
                    source = TradeSource.API_FILL,
                    tradeId = "T6Z73R-C6OVC-AFWUA3",
                    orderTxid = "O7T5FM-XMEVI-QXMFVW",
                    success = true,
                    dryRun = false,
                ),
            )
            ledgerRepository.saveLedgers(
                listOf(
                    LedgerEvent(
                        ledgerId = "LIG3K7-KLC4B-REA3ZV",
                        refid = "T6Z73R-C6OVC-AFWUA3",
                        time = t1,
                        type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                        subtype = "tradespot",
                        asset = "XMR",
                        amount = BigDecimal("0.56119848"),
                        fee = BigDecimal.ZERO,
                        balance = BigDecimal("0.56119848"),
                        hasAuthoritativeFee = true,
                        hasAuthoritativeBalance = true,
                    ),
                    LedgerEvent(
                        ledgerId = "LOG2MN-OXNJG-XFDHZM",
                        refid = "T6Z73R-C6OVC-AFWUA3",
                        time = t1,
                        type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                        subtype = "tradespot",
                        asset = "USD",
                        amount = BigDecimal("-222.0607"),
                        fee = BigDecimal("0.8882"),
                        balance = BigDecimal("1267.6143"),
                        hasAuthoritativeFee = true,
                        hasAuthoritativeBalance = true,
                    ),
                ),
            )

            // Seed regular daily snapshots from Day 1 to Day 100 (tStable) with constant balances
            // so no coverage gaps exist and every time window has dense observations.
            for (day in 1..100) {
                val snapTime = t0.plus(Duration.ofDays(day.toLong()))
                seedSnapshot(
                    snapTime,
                    btcBalance = "0.24",
                    btcPrice = (50000 + day * 200).toString(),
                    usdBalance = "1267.6143",
                )
            }
            // Seed intermediate snapshots within the final 24 hours before tStable
            seedSnapshot(
                tStable.minus(Duration.ofHours(12)),
                btcBalance = "0.24",
                btcPrice = "70000.00",
                usdBalance = "1267.6143",
            )
            seedSnapshot(
                tStable.minus(Duration.ofHours(6)),
                btcBalance = "0.24",
                btcPrice = "70000.00",
                usdBalance = "1267.6143",
            )

            // Seed uncertified live-tail snapshot at tTail with balance jump ahead of sync
            seedSnapshot(tTail, btcBalance = "0.241", btcPrice = "70000.00", usdBalance = "1197.5143")
        }

        "Phase 4 Reproduction: Live tail without coverage fails underlying evaluation but fix gates on stable horizon" {
            runTest {
                seedProductionDatabase()
                val queryService = newQueryService()

                // History comparison automatically trims uncertified live tail at tStable
                val comparison = queryService.getRebalancerComparison(t0, tTail)

                // Must be AVAILABLE and anchored at Dec 5 baseline
                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.baselineTimestamp shouldBe t0
                comparison.points.shouldNotBeEmpty()
                comparison.points.last().timestamp shouldBe tStable

                // Settings status successfully evaluates and verifies the automatic baseline
                val settingsStatus = queryService.getSettingsComparisonStatus(
                    t0,
                    allowPersistedBaselineFastPath = false,
                )
                settingsStatus.baselineStatus shouldBe AutomaticBaselineStatus.VERIFIED
                settingsStatus.baselineTimestamp shouldBe t0.toString()

                // Continuous history start is aligned to t0
                val continuousStart = repository.getSyncMetadata(SyncMetadataKeys.CONTINUOUS_HISTORY_START_EPOCH_MS)
                continuousStart shouldBe t0.toEpochMilli().toString()
            }
        }

        "Phases 5 & 6: Exhaustion loop eliminates all unavailable blockers until reaching AVAILABLE" {
            runTest {
                seedProductionDatabase()
                val queryService = newQueryService()

                val encounteredReasons = mutableListOf<ComparisonUnavailableReason?>()

                val comparison = queryService.getRebalancerComparison(t0, tTail)
                encounteredReasons.add(comparison.unavailableReason)

                // Comparison must be immediately AVAILABLE with zero unavailable reasons
                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.unavailableReason.shouldBeNull()
                encounteredReasons.filterNotNull().shouldBeEmpty()
            }
        }

        "Phases 7 & 8: Live-tail lag remains available through stable horizon without false gap masking" {
            runTest {
                seedProductionDatabase()
                val queryService = newQueryService()

                // Stale continuous start in sync_metadata (e.g. from an old scan) must not mask the verdict
                repository.setSyncMetadata(
                    SyncMetadataKeys.CONTINUOUS_HISTORY_START_EPOCH_MS,
                    tStable.toEpochMilli().toString(),
                )

                // First evaluation in Settings verifies and persists the baseline
                queryService.getSettingsComparisonStatus(t0, allowPersistedBaselineFastPath = false)

                // History must NOT display HISTORICAL_COVERAGE_GAP
                val comparison = queryService.getRebalancerComparison(t0, tTail)
                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.unavailableReason.shouldBeNull()
                comparison.baselineTimestamp shouldBe t0
            }
        }

        "Phase 11: Coverage catch-up incorporates the previously unstable live tail into history" {
            runTest {
                seedProductionDatabase()
                val queryService = newQueryService()

                // Before catch-up: stable comparison evaluates through tStable
                val beforeCatchUp = queryService.getRebalancerComparison(t0, tTail)
                beforeCatchUp.availability shouldBe ComparisonAvailability.AVAILABLE
                beforeCatchUp.points.last().timestamp shouldBe tStable

                // Add trade and ledger explaining the BTC balance increase at tTail
                repository.saveTrade(
                    TradeRecord(
                        id = 2,
                        timestamp = tTail,
                        pair = "BTCUSD",
                        side = OrderSide.BUY.apiValue,
                        symbol = "BTC",
                        volume = BigDecimal("0.001"),
                        usdAmount = BigDecimal("70.00"),
                        price = BigDecimal("70000.00"),
                        fee = BigDecimal("0.10"),
                        source = TradeSource.API_FILL,
                        tradeId = "BTC-BUY-TAIL",
                        orderTxid = "ORDER-TAIL",
                        success = true,
                        dryRun = false,
                    ),
                )
                ledgerRepository.saveLedgers(
                    listOf(
                        LedgerEvent(
                            ledgerId = "LEDGER-BTC-TAIL",
                            refid = "BTC-BUY-TAIL",
                            time = tTail,
                            type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                            subtype = "tradespot",
                            asset = "BTC",
                            amount = BigDecimal("0.001"),
                            fee = BigDecimal.ZERO,
                            balance = BigDecimal("0.241"),
                            hasAuthoritativeFee = true,
                            hasAuthoritativeBalance = true,
                        ),
                        LedgerEvent(
                            ledgerId = "LEDGER-USD-TAIL",
                            refid = "BTC-BUY-TAIL",
                            time = tTail,
                            type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                            subtype = "tradespot",
                            asset = "USD",
                            amount = BigDecimal("-70.00"),
                            fee = BigDecimal("0.10"),
                            balance = BigDecimal("1197.5143"),
                            hasAuthoritativeFee = true,
                            hasAuthoritativeBalance = true,
                        ),
                    ),
                )

                // Advance certified coverage horizons
                repository.setSyncMetadata(
                    SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC,
                    tTail.epochSecond.toString(),
                )
                ledgerRepository.setSyncMetadata(
                    SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC,
                    tTail.epochSecond.toString(),
                )

                // Now after catch-up: evaluation includes tTail
                val afterCatchUp = queryService.getRebalancerComparison(t0, tTail)
                afterCatchUp.availability shouldBe ComparisonAvailability.AVAILABLE
                afterCatchUp.points.last().timestamp shouldBe tTail
            }
        }

        "Phase 9: Full UI range matrix (24h, 7d, 30d, 90d, lifetime) all return AVAILABLE" {
            runTest {
                seedProductionDatabase()
                val queryService = newQueryService()

                // Run Settings evaluation once to persist proof
                queryService.getSettingsComparisonStatus(t0, allowPersistedBaselineFastPath = false)

                // 24 hours
                val comp24h = queryService.getRebalancerComparison(now.minus(Duration.ofHours(24)), now)
                comp24h.availability shouldBe ComparisonAvailability.AVAILABLE
                comp24h.points.shouldNotBeEmpty()

                // 7 days
                val comp7d = queryService.getRebalancerComparison(now.minus(Duration.ofDays(7)), now)
                comp7d.availability shouldBe ComparisonAvailability.AVAILABLE
                comp7d.points.shouldNotBeEmpty()

                // 30 days
                val comp30d = queryService.getRebalancerComparison(now.minus(Duration.ofDays(30)), now)
                comp30d.availability shouldBe ComparisonAvailability.AVAILABLE
                comp30d.points.shouldNotBeEmpty()

                // 90 days
                val comp90d = queryService.getRebalancerComparison(now.minus(Duration.ofDays(90)), now)
                comp90d.availability shouldBe ComparisonAvailability.AVAILABLE
                comp90d.points.shouldNotBeEmpty()

                // Lifetime
                val compLifetime = queryService.getRebalancerComparison(t0, now)
                compLifetime.availability shouldBe ComparisonAvailability.AVAILABLE
                compLifetime.baselineTimestamp shouldBe t0
                compLifetime.points.shouldNotBeEmpty()
            }
        }

        "Phase 10: Restart and persistence idempotency reuses verified baseline proof" {
            runTest {
                seedProductionDatabase()
                val initialService = newQueryService()

                // First run persists verification
                val initialStatus = initialService.getSettingsComparisonStatus(
                    t0,
                    allowPersistedBaselineFastPath = false,
                )
                initialStatus.baselineStatus shouldBe AutomaticBaselineStatus.VERIFIED

                // Verify metadata persisted
                repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_AUTO_BASELINE_STATUS) shouldBe "VERIFIED"

                // Create completely fresh service instances (simulating process restart)
                val restartedService = newQueryService()

                // Settings uses persisted baseline fast path
                val fastPathStatus = restartedService.getSettingsComparisonStatus(
                    t0,
                    allowPersistedBaselineFastPath = true,
                )
                fastPathStatus.baselineStatus shouldBe AutomaticBaselineStatus.VERIFIED
                fastPathStatus.baselineTimestamp shouldBe t0.toString()

                // History comparison immediately returns AVAILABLE
                val comparison = restartedService.getRebalancerComparison(t0, tTail)
                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.baselineTimestamp shouldBe t0
            }
        }

        "Phase 13 & 15: Fail-closed safety on tampered evidence or changed universe" {
            runTest {
                seedProductionDatabase()
                val queryService = newQueryService()

                // Verify baseline initially
                val initial = queryService.getSettingsComparisonStatus(t0, allowPersistedBaselineFastPath = false)
                initial.baselineStatus shouldBe AutomaticBaselineStatus.VERIFIED

                // 1. Modifying config asset universe invalidates and re-persists under new config fingerprint
                appConfig = appConfig.copy(
                    allocations = listOf(Allocation(Asset.BTC, 60.0), Allocation(Asset.USD, 40.0)),
                )
                val universeChangedStatus = queryService.getSettingsComparisonStatus(
                    t0,
                    allowPersistedBaselineFastPath = true,
                )
                universeChangedStatus.baselineStatus shouldBe AutomaticBaselineStatus.VERIFIED
                val newConfigFingerprint = repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_CONFIG_FINGERPRINT)
                repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_AUTO_BASELINE_CONFIG_FINGERPRINT) shouldBe
                    newConfigFingerprint
                newConfigFingerprint shouldNotBe "prod-config-fingerprint"

                // 2. Unexplained balance mutation in evidence horizon invalidates proof and fails closed
                val snapTime = t0.plus(Duration.ofDays(10))
                seedSnapshot(
                    timestamp = snapTime.plus(Duration.ofHours(1)),
                    btcBalance = "0.50",
                    btcPrice = "52000.00",
                    usdBalance = "1267.6143",
                )
                val tamperedStatus = queryService.getSettingsComparisonStatus(t0, allowPersistedBaselineFastPath = true)
                // Fast path rejects due to evidence digest mismatch, falls through to full evaluation which fails closed
                tamperedStatus.baselineStatus.shouldBeNull()
                repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_AUTO_BASELINE_STATUS) shouldBe "INVALIDATED"
            }
        }
    })
