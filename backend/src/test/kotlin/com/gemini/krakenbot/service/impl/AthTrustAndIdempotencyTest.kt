package com.gemini.krakenbot.service.impl

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.DatabaseConfig
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.KrakenApiConstants
import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.PortfolioStats
import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.repository.impl.SqliteLedgerRepositoryImpl
import com.gemini.krakenbot.repository.impl.SqlitePortfolioStatsRepositoryImpl
import com.gemini.krakenbot.repository.impl.SqliteTradeRepositoryImpl
import com.gemini.krakenbot.service.AthUpdateResult
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.KrakenService
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import java.time.Instant

/**
 * Crash-trust integration tests over a shared in-memory SQLite database:
 * stale ledger coverage defers the whole ATH update (F1), applied flows are
 * checkpointed exactly once across restarts (F2), and pre-flow bases come
 * from event-time state (F5).
 */
class AthTrustAndIdempotencyTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    private val db = DatabaseConfig.init(TestFixtures.MEMORY_)
    private val ledgerRepository = SqliteLedgerRepositoryImpl(db)
    private val tradeRepository = SqliteTradeRepositoryImpl(db)
    private val statsRepository = SqlitePortfolioStatsRepositoryImpl(db, jacksonObjectMapper())
    private val configService = mockk<ConfigService>(relaxed = true)
    private val krakenService = mockk<KrakenService>(relaxed = true)

    private val t0 = Instant.parse("2026-06-01T12:00:00Z")
    private val t60 = t0.plusSeconds(60)
    private val t70 = t0.plusSeconds(70)
    private val t71 = t0.plusSeconds(71)
    private val t75 = t0.plusSeconds(75)
    private val t80 = t0.plusSeconds(80)
    private val t85 = t0.plusSeconds(85)
    private val t88 = t0.plusSeconds(88)
    private val t90 = t0.plusSeconds(90)
    private val t95 = t0.plusSeconds(95)
    private val t120 = t0.plusSeconds(120)

    private fun analyzer(now: Instant) = PortfolioAnalyzerImpl(
        krakenService = krakenService,
        configService = configService,
        portfolioStatsRepository = statsRepository,
        nowProvider = { now },
        ledgerRepository = ledgerRepository,
        tradeRepository = tradeRepository,
    )

    private fun deposit(id: String, time: Instant, amountUsd: String) = LedgerEvent(
        ledgerId = id,
        time = time,
        type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
        asset = "USD",
        amount = BigDecimal(amountUsd),
    )

    init {
        beforeTest {
            every { configService.getConfig() } returns TestFixtures.config(
                settings = TestFixtures.settings(),
                allocations = listOf(
                    Allocation(Asset.BTC, 50.0),
                    Allocation(Asset.USD, 50.0),
                ),
            )
        }

        "stale coverage defers, then the deposit applies exactly once at 137.5k and 20 percent drawdown" {
            runTest {
                statsRepository.save(PortfolioStats(BigDecimal("100000.00"), BigDecimal("20.0000")))
                tradeRepository.setSyncMetadata(
                    SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC,
                    t60.epochSecond.toString(),
                )
                ledgerRepository.setSyncMetadata(
                    SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC,
                    t60.epochSecond.toString(),
                )

                // Balances at T120 (110k) may already contain the +30k deposit
                // while ledgers only cover T60: no ratchet, no writes.
                val stale = analyzer(t120).updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("110000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = t120,
                )
                stale shouldBe AthUpdateResult.Deferred(BigDecimal("20.0000"))
                statsRepository.load().allTimeHigh.shouldBeEqualComparingTo(BigDecimal("100000.00"))

                // Ledger sync advances past the deposit; a fresh observation
                // inside coverage applies it once: 100k * 110/80 = 137.5k.
                // (The T60 snapshot pins the pre-flow basis; identity scanning
                // resolves flows by journal membership, not by watermark window.)
                tradeRepository.saveSnapshot(TestFixtures.emptySnapshot(t60, BigDecimal("80000.00")))
                tradeRepository.setSyncMetadata(
                    SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC,
                    t60.epochSecond.toString(),
                )
                ledgerRepository.saveLedgers(listOf(deposit("dep-1", t70, "30000.00")))
                tradeRepository.setSyncMetadata(
                    SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC,
                    t60.epochSecond.toString(),
                )
                ledgerRepository.setSyncMetadata(
                    SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC,
                    t80.epochSecond.toString(),
                )
                val trusted = analyzer(t80).updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("110000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = t80,
                )
                val drawdown = (trusted as AthUpdateResult.Trusted).drawdownPct
                drawdown.shouldBeEqualComparingTo(BigDecimal("20.0000"))
                statsRepository.load().allTimeHigh.shouldBeEqualComparingTo(BigDecimal("137500.00"))
                // The journal is a lifetime decision log: the applied identity
                // is retained (never watermark-pruned) and is what makes the
                // replay below exact-once.
                statsRepository.getAppliedAthFlowIds(listOf("dep-1")) shouldBe setOf("dep-1")

                // Restart reprocessing the same window changes nothing.
                val replay = analyzer(t80).updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("110000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = t80,
                )
                (replay as AthUpdateResult.Trusted).drawdownPct.shouldBeEqualComparingTo(BigDecimal("20.0000"))
                statsRepository.load().allTimeHigh.shouldBeEqualComparingTo(BigDecimal("137500.00"))
            }
        }

        "same-second deposits apply once each and survive reprocessing" {
            runTest {
                statsRepository.save(PortfolioStats(BigDecimal("100000.00"), BigDecimal.ZERO))
                tradeRepository.saveSnapshot(TestFixtures.emptySnapshot(t60, BigDecimal("100000.00")))
                ledgerRepository.saveLedgers(
                    listOf(
                        deposit("d-a", t70, "10000.00"),
                        deposit("d-b", t70, "5000.00"),
                    ),
                )
                tradeRepository.setSyncMetadata(
                    SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC,
                    t60.epochSecond.toString(),
                )
                ledgerRepository.setSyncMetadata(
                    SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC,
                    t80.epochSecond.toString(),
                )

                val first = analyzer(t80).updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("115000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = t80,
                )
                (first as AthUpdateResult.Trusted).drawdownPct.shouldBeEqualComparingTo(BigDecimal.ZERO)
                // One netted simultaneous step: 100k * 115/100 = 115k.
                statsRepository.load().allTimeHigh.shouldBeEqualComparingTo(BigDecimal("115000.00"))

                val second = analyzer(t80).updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("115000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = t80,
                )
                (second as AthUpdateResult.Trusted).drawdownPct.shouldBeEqualComparingTo(BigDecimal.ZERO)
                statsRepository.load().allTimeHigh.shouldBeEqualComparingTo(BigDecimal("115000.00"))
            }
        }

        "event-time snapshot is the exact pre-flow basis" {
            runTest {
                statsRepository.save(PortfolioStats(BigDecimal("100000.00"), BigDecimal.ZERO))
                tradeRepository.saveSnapshot(TestFixtures.emptySnapshot(t70, BigDecimal("80000.00")))
                ledgerRepository.saveLedgers(listOf(deposit("dep-exact", t70, "20000.00")))
                tradeRepository.setSyncMetadata(
                    SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC,
                    t60.epochSecond.toString(),
                )
                ledgerRepository.setSyncMetadata(
                    SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC,
                    t80.epochSecond.toString(),
                )

                // Basis 80k, not the residual 80k-by-coincidence: snapshot wins.
                val result = analyzer(t80).updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("100000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = t80,
                )
                (result as AthUpdateResult.Trusted).drawdownPct.shouldBeEqualComparingTo(BigDecimal("20.0000"))
                // 100k * 100/80 = 125k; 100k total against it is a 20% drawdown.
                statsRepository.load().allTimeHigh.shouldBeEqualComparingTo(BigDecimal("125000.00"))
            }
        }

        "worthless snapshot defers the ATH update instead of a residual guess" {
            runTest {
                statsRepository.save(PortfolioStats(BigDecimal("100000.00"), BigDecimal.ZERO))
                // A zero-total snapshot at the event instant carries no basis
                // information. The silent residual fallback is gone: the
                // update defers with no writes and the flow stays unjournaled
                // for a retry once real basis state exists.
                tradeRepository.saveSnapshot(TestFixtures.emptySnapshot(t70, BigDecimal.ZERO))
                ledgerRepository.saveLedgers(listOf(deposit("dep-zero-snap", t70, "20000.00")))
                tradeRepository.setSyncMetadata(
                    SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC,
                    t60.epochSecond.toString(),
                )
                ledgerRepository.setSyncMetadata(
                    SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC,
                    t80.epochSecond.toString(),
                )

                val result = analyzer(t80).updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("100000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = t80,
                )
                requireNotNull((result as AthUpdateResult.Deferred).lastTrustedDrawdownPct)
                    .shouldBeEqualComparingTo(BigDecimal.ZERO)
                statsRepository.load().allTimeHigh.shouldBeEqualComparingTo(BigDecimal("100000.00"))
                statsRepository.getAppliedAthFlowIds(listOf("dep-zero-snap")) shouldBe emptySet()
            }
        }

        "predecessor snapshot reconstructs the basis across market movement" {
            runTest {
                statsRepository.save(PortfolioStats(BigDecimal("100000.00"), BigDecimal.ZERO))
                // Snapshot 270s before the event is outside the exact window:
                // basis reconstructs from it (75k, no flows between).
                tradeRepository.saveSnapshot(TestFixtures.emptySnapshot(t0.minusSeconds(200), BigDecimal("75000.00")))
                ledgerRepository.saveLedgers(listOf(deposit("dep-pred", t70, "30000.00")))
                tradeRepository.setSyncMetadata(
                    SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC,
                    t60.epochSecond.toString(),
                )
                ledgerRepository.setSyncMetadata(
                    SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC,
                    t80.epochSecond.toString(),
                )

                val result = analyzer(t80).updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("105000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = t80,
                )
                (result as AthUpdateResult.Trusted).drawdownPct.shouldBeEqualComparingTo(BigDecimal("25.0000"))
                // 100k * 105/75 = 140k; 105k total against it is a 25% drawdown. (residual would wrongly use 75k too here
                // by coincidence of a single flow; two-flow separation below).
                statsRepository.load().allTimeHigh.shouldBeEqualComparingTo(BigDecimal("140000.00"))
            }
        }

        "two deposits separated by market movement use event-time bases, not residual" {
            runTest {
                statsRepository.save(PortfolioStats(BigDecimal("100000.00"), BigDecimal.ZERO))
                // Snapshots pin both event-time totals exactly.
                tradeRepository.saveSnapshot(TestFixtures.emptySnapshot(t0.plusSeconds(70), BigDecimal("80000.00")))
                tradeRepository.saveSnapshot(TestFixtures.emptySnapshot(t0.plusSeconds(100), BigDecimal("110000.00")))
                ledgerRepository.saveLedgers(
                    listOf(
                        deposit("dep-1", t0.plusSeconds(70), "20000.00"),
                        deposit("dep-2", t0.plusSeconds(100), "10000.00"),
                    ),
                )
                tradeRepository.setSyncMetadata(
                    SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC,
                    t60.epochSecond.toString(),
                )
                ledgerRepository.setSyncMetadata(
                    SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC,
                    t120.epochSecond.toString(),
                )

                val result = analyzer(t120).updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("120000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = t120,
                )
                (result as AthUpdateResult.Trusted).drawdownPct.shouldBeEqualComparingTo(BigDecimal("12.0000"))
                // First: 100k * 100/80 = 125k. Second: 125k * 120/110 = 136363.64.
                // Residual math would use 90k for the first basis and overshoot.
                statsRepository.load().allTimeHigh.shouldBeEqualComparingTo(BigDecimal("136363.64"))
            }
        }

        "partial application checkpoints the prefix, holds the watermark, and recovers on restart" {
            runTest {
                statsRepository.save(PortfolioStats(BigDecimal("100000.00"), BigDecimal.ZERO))
                tradeRepository.setSyncMetadata(
                    SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC,
                    t60.epochSecond.toString(),
                )
                tradeRepository.saveSnapshot(TestFixtures.emptySnapshot(t60, BigDecimal("100000.00")))
                // Full withdrawal zeroes ATH mid-list; the later deposit is
                // not applied yet and the watermark must not advance past it.
                ledgerRepository.saveLedgers(
                    listOf(
                        LedgerEvent(
                            ledgerId = "w-full",
                            time = t70,
                            type = KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL,
                            asset = "USD",
                            amount = BigDecimal("-100000.00"),
                        ),
                        deposit("d-after", t70.plusSeconds(1), "5000.00"),
                    ),
                )
                ledgerRepository.setSyncMetadata(
                    SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC,
                    t80.epochSecond.toString(),
                )

                val crashed = analyzer(t80).updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("5000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = t80,
                )
                (crashed as AthUpdateResult.Trusted).drawdownPct.shouldBeEqualComparingTo(BigDecimal.ZERO)
                statsRepository.load().allTimeHigh.shouldBeEqualComparingTo(BigDecimal.ZERO)
                // Prefix checkpointed, watermark held, later flow unacknowledged.
                statsRepository.getAppliedAthFlowIds(listOf("w-full", "d-after")) shouldBe setOf("w-full")
                tradeRepository.getSyncMetadata(
                    SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC,
                ) shouldBe t60.epochSecond.toString()

                // Restart with zero ATH folds everything into initial ATH:
                // the skipped deposit is absorbed, never double-applied.
                val recovered = analyzer(t80).updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("5000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = t80,
                )
                (recovered as AthUpdateResult.Trusted).drawdownPct.shouldBeEqualComparingTo(BigDecimal.ZERO)
                statsRepository.load().allTimeHigh.shouldBeEqualComparingTo(BigDecimal("5000.00"))
                tradeRepository.getSyncMetadata(
                    SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC,
                ) shouldBe t80.epochSecond.toString()
            }
        }

        "zero ATH folds flows into initial ATH without computation" {
            runTest {
                statsRepository.save(PortfolioStats(BigDecimal.ZERO, null))
                tradeRepository.setSyncMetadata(
                    SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC,
                    t60.epochSecond.toString(),
                )
                ledgerRepository.saveLedgers(listOf(deposit("dep-fresh", t70, "10000.00")))
                ledgerRepository.setSyncMetadata(
                    SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC,
                    t80.epochSecond.toString(),
                )

                val result = analyzer(t80).updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("10000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = t80,
                )
                (result as AthUpdateResult.Trusted).drawdownPct.shouldBeEqualComparingTo(BigDecimal.ZERO)
                statsRepository.load().allTimeHigh.shouldBeEqualComparingTo(BigDecimal("10000.00"))
                // Absorption: the initial ATH already contains the deposit, so
                // its identity is journaled — the next identity scan must not
                // re-apply it against the post-fold baseline.
                statsRepository.getAppliedAthFlowIds(listOf("dep-fresh")) shouldBe setOf("dep-fresh")
            }
        }

        "unbasis-able flow defers without writes instead of stalling the cycle" {
            runTest {
                statsRepository.save(PortfolioStats(BigDecimal("100000.00"), BigDecimal("7.5000")))
                tradeRepository.saveSnapshot(TestFixtures.emptySnapshot(t60, BigDecimal.ZERO))
                ledgerRepository.saveLedgers(listOf(deposit("dep-zero", t70, "10000.00")))
                tradeRepository.setSyncMetadata(
                    SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC,
                    t60.epochSecond.toString(),
                )
                ledgerRepository.setSyncMetadata(
                    SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC,
                    t80.epochSecond.toString(),
                )

                val result = analyzer(t80).updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal.ZERO,
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = t80,
                )

                result shouldBe AthUpdateResult.Deferred(BigDecimal("7.5000"))
                statsRepository.load().allTimeHigh.shouldBeEqualComparingTo(BigDecimal("100000.00"))
            }
        }

        "zero-net simultaneous group consumes its identities without scaling" {
            runTest {
                statsRepository.save(PortfolioStats(BigDecimal("100000.00"), BigDecimal.ZERO))
                tradeRepository.setSyncMetadata(
                    SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC,
                    t60.epochSecond.toString(),
                )
                ledgerRepository.saveLedgers(
                    listOf(
                        deposit("z-in", t70, "5000.00"),
                        LedgerEvent(
                            ledgerId = "z-out",
                            time = t70,
                            type = KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL,
                            asset = "USD",
                            amount = BigDecimal("-5000.00"),
                        ),
                    ),
                )
                ledgerRepository.setSyncMetadata(
                    SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC,
                    t80.epochSecond.toString(),
                )

                val result = analyzer(t80).updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("100000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = t80,
                )
                (result as AthUpdateResult.Trusted).drawdownPct.shouldBeEqualComparingTo(BigDecimal.ZERO)
                statsRepository.load().allTimeHigh.shouldBeEqualComparingTo(BigDecimal("100000.00"))
                // The identities are checkpointed and RETAINED: the journal is
                // a lifetime decision log (no watermark pruning), so the
                // net-zero group's ids are the exact record that keeps any
                // later re-scan from reprocessing them.
                statsRepository.getAppliedAthFlowIds(listOf("z-in", "z-out")) shouldBe setOf("z-in", "z-out")
            }
        }

        "malformed ATH flow watermark defers without advancing state" {
            runTest {
                statsRepository.save(PortfolioStats(BigDecimal("10000.00"), BigDecimal.ZERO))
                ledgerRepository.setSyncMetadata(
                    SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC,
                    t80.epochSecond.toString(),
                )
                tradeRepository.setSyncMetadata(
                    SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC,
                    "not-a-number",
                )

                // A corrupt watermark must not silently advance past unapplied
                // flows: skipped withdrawals would overstate drawdown and
                // over-deploy. The corrupt key is left for the operator; the
                // missing-watermark path re-establishes the window afterwards.
                val result = analyzer(t80).updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("12000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = t80,
                )
                requireNotNull((result as AthUpdateResult.Deferred).lastTrustedDrawdownPct)
                    .shouldBeEqualComparingTo(BigDecimal.ZERO)
                statsRepository.load().allTimeHigh.shouldBeEqualComparingTo(BigDecimal("10000.00"))
                tradeRepository.getSyncMetadata(
                    SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC,
                ) shouldBe "not-a-number"
            }
        }

        "missing ledger coverage defers a dated observation without ratcheting ATH" {
            runTest {
                statsRepository.save(PortfolioStats(BigDecimal("10000.00"), BigDecimal.ZERO))
                // No LEDGER_WATERMARK_EPOCH_SEC: startup sync never confirmed
                // coverage (e.g. network failure, swallowed by the sync
                // wrapper). The 12,000 total may contain unseen owner capital,
                // so it must neither establish nor ratchet ATH, and nothing is
                // persisted.
                val result = analyzer(t80).updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("12000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = t80,
                )
                requireNotNull((result as AthUpdateResult.Deferred).lastTrustedDrawdownPct)
                    .shouldBeEqualComparingTo(BigDecimal.ZERO)
                statsRepository.load().allTimeHigh.shouldBeEqualComparingTo(BigDecimal("10000.00"))
                tradeRepository.getSyncMetadata(
                    SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC,
                ) shouldBe null
            }
        }

        "upgrade migration presumes pre-watermark legacy rows decided instead of re-applying them" {
            runTest {
                statsRepository.save(PortfolioStats(BigDecimal("100000.00"), BigDecimal.ZERO))
                ledgerRepository.saveLedgers(listOf(deposit("l-legacy", t70, "30000.00")))
                tradeRepository.setSyncMetadata(
                    SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC,
                    t80.epochSecond.toString(),
                )
                ledgerRepository.setSyncMetadata(
                    SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC,
                    t90.epochSecond.toString(),
                )
                // Pre-upgrade state: legacy rows below the ATH watermark have
                // no journal entries (the old design pruned them). The one-time
                // migration presumes them decided so the identity scan neither
                // re-scales ATH nor defers forever.
                analyzer(t90).updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("100000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = t90,
                )
                ledgerRepository.getSyncMetadata(SyncMetadataKeys.ATH_FLOW_JOURNAL_MIGRATED) shouldBe "true"
                statsRepository.getAppliedAthFlowIds(listOf("l-legacy")) shouldBe setOf("l-legacy")
                statsRepository.load().allTimeHigh.shouldBeEqualComparingTo(BigDecimal("100000.00"))
            }
        }

        "consciously-skipped flow is journaled once and never blocks later scans" {
            runTest {
                statsRepository.save(PortfolioStats(BigDecimal("100000.00"), BigDecimal.ZERO))
                tradeRepository.setSyncMetadata(
                    SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC,
                    t60.epochSecond.toString(),
                )
                ledgerRepository.saveLedgers(
                    listOf(
                        LedgerEvent(
                            ledgerId = "btc-fee-dep",
                            time = t70,
                            type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                            asset = "BTC",
                            amount = BigDecimal("0.5"),
                            fee = BigDecimal("0.001"),
                        ),
                    ),
                )
                ledgerRepository.setSyncMetadata(
                    SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC,
                    t80.epochSecond.toString(),
                )

                val first = analyzer(t80).updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("100000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = t80,
                )
                (first as AthUpdateResult.Trusted).drawdownPct.shouldBeEqualComparingTo(BigDecimal.ZERO)
                statsRepository.load().allTimeHigh.shouldBeEqualComparingTo(BigDecimal("100000.00"))
                statsRepository.getAppliedAthFlowIds(listOf("btc-fee-dep")) shouldBe setOf("btc-fee-dep")

                // Second cycle: the journaled skip is not re-warned or
                // reprocessed; a genuinely new flow still decides normally.
                tradeRepository.saveSnapshot(TestFixtures.emptySnapshot(t60, BigDecimal("100000.00")))
                ledgerRepository.saveLedgers(listOf(deposit("dep-next", t75, "10000.00")))
                val second = analyzer(t80).updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("110000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = t80,
                )
                (second as AthUpdateResult.Trusted).drawdownPct.shouldBeEqualComparingTo(BigDecimal.ZERO)
                statsRepository.load().allTimeHigh.shouldBeEqualComparingTo(BigDecimal("110000.00"))
            }
        }

        "flow older than every retained snapshot is consciously skipped and journaled" {
            runTest {
                statsRepository.save(PortfolioStats(BigDecimal("100000.00"), BigDecimal.ZERO))
                tradeRepository.setSyncMetadata(
                    SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC,
                    t60.epochSecond.toString(),
                )
                ledgerRepository.saveLedgers(listOf(deposit("dep-nohist", t70, "5000.00")))
                ledgerRepository.setSyncMetadata(
                    SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC,
                    t80.epochSecond.toString(),
                )

                val result = analyzer(t80).updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("100000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = t80,
                )
                // No snapshot exists at or before the event: the residual
                // approximation is gone, the flow's effect is already inside
                // the ATH baseline, and its identity is journaled as skipped.
                (result as AthUpdateResult.Trusted).drawdownPct.shouldBeEqualComparingTo(BigDecimal.ZERO)
                statsRepository.load().allTimeHigh.shouldBeEqualComparingTo(BigDecimal("100000.00"))
                statsRepository.getAppliedAthFlowIds(listOf("dep-nohist")) shouldBe setOf("dep-nohist")
            }
        }

        "trades between snapshot and flow are replayed at predecessor prices in the basis" {
            runTest {
                statsRepository.save(PortfolioStats(BigDecimal("120000.00"), BigDecimal.ZERO))
                tradeRepository.setSyncMetadata(
                    SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC,
                    t60.epochSecond.toString(),
                )
                tradeRepository.saveSnapshot(
                    PortfolioSnapshot(
                        timestamp = t60,
                        totalValueUSD = BigDecimal("100000.00"),
                        assets = mapOf(
                            "BTC" to TestFixtures.assetSnapshot(
                                symbol = "BTC",
                                balance = BigDecimal.ONE,
                                price = BigDecimal("50000.00"),
                                valueUSD = BigDecimal("50000.00"),
                                targetPercent = BigDecimal("50"),
                            ),
                        ),
                        actions = emptyList(),
                        drawdownPercent = BigDecimal.ZERO,
                        fiatDeploymentPercent = BigDecimal.ZERO,
                        effectiveUsdTargetPercent = BigDecimal.ZERO,
                    ),
                )
                // BTC buy at snapshot price costs only the 50.00 fee in value;
                // the ADA buy leaves the tracked universe, so its full fiat
                // outlay (1000 + 10 fee) leaves the reconstructed basis.
                tradeRepository.saveTrade(
                    TestFixtures.tradeRecord(
                        timestamp = t60.plusSeconds(5),
                        pair = "USDBTC",
                        side = "buy",
                        symbol = "BTC",
                        volume = BigDecimal("0.1"),
                        usdAmount = BigDecimal("5000.00"),
                        fee = BigDecimal("50.00"),
                    ),
                )
                tradeRepository.saveTrade(
                    TestFixtures.tradeRecord(
                        timestamp = t60.plusSeconds(6),
                        pair = "USDADA",
                        side = "buy",
                        symbol = "ADA",
                        volume = BigDecimal("10"),
                        usdAmount = BigDecimal("1000.00"),
                        fee = BigDecimal("10.00"),
                    ),
                )
                ledgerRepository.saveLedgers(listOf(deposit("dep-1", t70, "10000.00")))
                ledgerRepository.setSyncMetadata(
                    SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC,
                    t80.epochSecond.toString(),
                )

                val result = analyzer(t80).updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("110000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = t80,
                )
                // Drawdown reflects the scaled ATH vs the 110k observed total;
                // the exact basis is what this test pins via ATH below.
                (result as AthUpdateResult.Trusted)
                // Basis = 100000 − 50 − 1010 = 98940; without trade replay the
                // basis would be 100000 and ATH 132000.00 — the replayed fee
                // drag and inventory change must show up in the scaled ATH.
                statsRepository.load().allTimeHigh.shouldBeEqualComparingTo(BigDecimal("132128.56"))
                statsRepository.getAppliedAthFlowIds(listOf("dep-1")) shouldBe setOf("dep-1")
            }
        }

        "sell trades replay against the basis while failed and dry-run trades are ignored" {
            runTest {
                statsRepository.save(PortfolioStats(BigDecimal("120000.00"), BigDecimal.ZERO))
                tradeRepository.setSyncMetadata(
                    SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC,
                    t60.epochSecond.toString(),
                )
                tradeRepository.saveSnapshot(
                    PortfolioSnapshot(
                        timestamp = t60,
                        totalValueUSD = BigDecimal("100000.00"),
                        assets = mapOf(
                            "BTC" to TestFixtures.assetSnapshot(
                                symbol = "BTC",
                                balance = BigDecimal.ONE,
                                price = BigDecimal("50000.00"),
                                valueUSD = BigDecimal("50000.00"),
                                targetPercent = BigDecimal("50"),
                            ),
                            "ETH" to TestFixtures.assetSnapshot(
                                symbol = "ETH",
                                balance = BigDecimal.ZERO,
                                price = BigDecimal.ZERO,
                                valueUSD = BigDecimal.ZERO,
                                targetPercent = BigDecimal.ZERO,
                            ),
                        ),
                        actions = emptyList(),
                        drawdownPercent = BigDecimal.ZERO,
                        fiatDeploymentPercent = BigDecimal.ZERO,
                        effectiveUsdTargetPercent = BigDecimal.ZERO,
                    ),
                )
                // SELL 0.1 BTC at snapshot price returns 5000 of value but only
                // 4950 net fiat: the 50.00 fee drag leaves the basis. The ADA
                // sell is outside the snapshot universe (fiat-only delta), the
                // ETH buy prices at a zero snapshot price (fiat-only delta),
                // and the failed and dry-run trades must not move it at all.
                tradeRepository.saveTrade(
                    TestFixtures.tradeRecord(
                        timestamp = t60.plusSeconds(5),
                        pair = "USDBTC",
                        side = "sell",
                        symbol = "BTC",
                        volume = BigDecimal("0.1"),
                        usdAmount = BigDecimal("5000.00"),
                        fee = BigDecimal("50.00"),
                    ),
                )
                tradeRepository.saveTrade(
                    TestFixtures.tradeRecord(
                        timestamp = t60.plusSeconds(6),
                        pair = "USDBTC",
                        side = "buy",
                        symbol = "BTC",
                        volume = BigDecimal("1"),
                        usdAmount = BigDecimal("50000.00"),
                        success = false,
                        errorMessage = "EOrder:Matched",
                    ),
                )
                tradeRepository.saveTrade(
                    TestFixtures.tradeRecord(
                        timestamp = t60.plusSeconds(7),
                        pair = "USDBTC",
                        side = "buy",
                        symbol = "BTC",
                        volume = BigDecimal("1"),
                        usdAmount = BigDecimal("50000.00"),
                        dryRun = true,
                    ),
                )
                tradeRepository.saveTrade(
                    TestFixtures.tradeRecord(
                        timestamp = t60.plusSeconds(8),
                        pair = "USDADA",
                        side = "sell",
                        symbol = "ADA",
                        volume = BigDecimal("10"),
                        usdAmount = BigDecimal("200.00"),
                        fee = BigDecimal("2.00"),
                    ),
                )
                tradeRepository.saveTrade(
                    TestFixtures.tradeRecord(
                        timestamp = t60.plusSeconds(9),
                        pair = "USETH",
                        side = "buy",
                        symbol = "ETH",
                        volume = BigDecimal("1"),
                        usdAmount = BigDecimal("100.00"),
                        fee = BigDecimal("1.00"),
                    ),
                )
                ledgerRepository.saveLedgers(
                    listOf(
                        LedgerEvent(
                            ledgerId = "dep-sell",
                            time = t70,
                            type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                            asset = "ZUSD",
                            amount = BigDecimal("10000.00"),
                        ),
                    ),
                )
                ledgerRepository.setSyncMetadata(
                    SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC,
                    t80.epochSecond.toString(),
                )

                analyzer(t80).updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("110000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = t80,
                )
                // Basis = 100000 − 50 + 198 − 101 = 100047;
                // ATH = 120000 × 110047 / 100047.
                statsRepository.load().allTimeHigh.shouldBeEqualComparingTo(BigDecimal("131994.36"))
                statsRepository.getAppliedAthFlowIds(listOf("dep-sell")) shouldBe setOf("dep-sell")
            }
        }

        "initial ATH absorption journals only decision-bearing rows" {
            runTest {
                statsRepository.save(PortfolioStats(BigDecimal.ZERO, BigDecimal.ZERO))
                ledgerRepository.saveLedgers(
                    listOf(
                        LedgerEvent(
                            ledgerId = "mv-1",
                            time = t70,
                            type = "transfer",
                            asset = "USD",
                            amount = BigDecimal("10.00"),
                        ),
                        LedgerEvent(
                            ledgerId = "tr-1",
                            time = t70.plusSeconds(1),
                            type = "trade",
                            asset = "BTC",
                            amount = BigDecimal("0.001"),
                        ),
                    ),
                )
                ledgerRepository.setSyncMetadata(
                    SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC,
                    t80.epochSecond.toString(),
                )

                val result = analyzer(t80).updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("50000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = t80,
                )
                (result as AthUpdateResult.Trusted).drawdownPct.shouldBeEqualComparingTo(BigDecimal.ZERO)
                statsRepository.load().allTimeHigh.shouldBeEqualComparingTo(BigDecimal("50000.00"))
                // Internal moves and trade legs carry no owner capital and
                // are re-derived cheaply, so nothing is journaled on fold.
                statsRepository.getAppliedAthFlowIds(listOf("mv-1", "tr-1")) shouldBe emptySet()
            }
        }

        "pre-migrated databases scan rows below the legacy watermark by identity" {
            runTest {
                statsRepository.save(PortfolioStats(BigDecimal("100000.00"), BigDecimal.ZERO))
                ledgerRepository.setSyncMetadata(SyncMetadataKeys.ATH_FLOW_JOURNAL_MIGRATED, "true")
                tradeRepository.setSyncMetadata(
                    SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC,
                    t80.epochSecond.toString(),
                )
                tradeRepository.saveSnapshot(TestFixtures.emptySnapshot(t60, BigDecimal("100000.00")))
                ledgerRepository.saveLedgers(listOf(deposit("dep-below-wm", t70, "5000.00")))
                ledgerRepository.setSyncMetadata(
                    SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC,
                    t90.epochSecond.toString(),
                )

                val result = analyzer(t90).updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("105000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = t90,
                )
                // The migration flag is already set, so the row below the
                // watermark is undecided and gets applied once by identity.
                (result as AthUpdateResult.Trusted).drawdownPct.shouldBeEqualComparingTo(BigDecimal.ZERO)
                statsRepository.load().allTimeHigh.shouldBeEqualComparingTo(BigDecimal("105000.00"))
                statsRepository.getAppliedAthFlowIds(listOf("dep-below-wm")) shouldBe setOf("dep-below-wm")
            }
        }

        "refid pairing sees decided partners so a backfilled internal-move leg never scales ATH" {
            runTest {
                statsRepository.save(PortfolioStats(BigDecimal("100000.00"), BigDecimal.ZERO))
                ledgerRepository.setSyncMetadata(SyncMetadataKeys.ATH_FLOW_JOURNAL_MIGRATED, "true")
                tradeRepository.setSyncMetadata(
                    SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC,
                    t60.epochSecond.toString(),
                )
                ledgerRepository.setSyncMetadata(
                    SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC,
                    t90.epochSecond.toString(),
                )
                tradeRepository.saveSnapshot(TestFixtures.emptySnapshot(t60, BigDecimal("100000.00")))
                ledgerRepository.saveLedgers(
                    listOf(
                        LedgerEvent(
                            ledgerId = "d-pair",
                            refid = "R1",
                            time = t70,
                            type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                            asset = "USD",
                            amount = BigDecimal("1000.00"),
                        ),
                    ),
                )

                val first = analyzer(t90).updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("101000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = t90,
                )
                (first as AthUpdateResult.Trusted)
                statsRepository.load().allTimeHigh.shouldBeEqualComparingTo(BigDecimal("101000.00"))

                // Backfill reveals the deposit was one leg of a same-refid
                // internal move. Pairing runs on the full retained set, so the
                // lone undecided withdrawal classifies INTERNAL_MOVE (not
                // owner capital) and cannot shrink the ATH a second time.
                ledgerRepository.saveLedgers(
                    listOf(
                        LedgerEvent(
                            ledgerId = "w-pair",
                            refid = "R1",
                            time = t71,
                            type = KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL,
                            asset = "USD",
                            amount = BigDecimal("-1000.00"),
                        ),
                    ),
                )
                val second = analyzer(t90).updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("100000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = t90,
                )
                (second as AthUpdateResult.Trusted)
                statsRepository.load().allTimeHigh.shouldBeEqualComparingTo(BigDecimal("101000.00"))
                statsRepository.getAppliedAthFlowIds(listOf("w-pair")) shouldBe emptySet()
            }
        }

        "initial ATH absorbs only rows at or below the observation, not coverage-only rows" {
            runTest {
                statsRepository.save(PortfolioStats(BigDecimal.ZERO, BigDecimal.ZERO))
                // Production ordering: the balance observation precedes the
                // sync, so coverage (t90) runs past the observation (t85).
                ledgerRepository.setSyncMetadata(
                    SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC,
                    t90.epochSecond.toString(),
                )
                ledgerRepository.saveLedgers(
                    listOf(
                        deposit("dep-at-obs", t85, "5000.00"),
                        deposit("dep-above-obs", t88, "5000.00"),
                    ),
                )

                val first = analyzer(t90).updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("55000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = t85,
                )
                // The 55k total at t85 contains dep-at-obs but not the t88
                // deposit: absorption stops at the observation and the
                // watermark is held there so migration cannot presume t88.
                (first as AthUpdateResult.Trusted)
                statsRepository.load().allTimeHigh.shouldBeEqualComparingTo(BigDecimal("55000.00"))
                statsRepository.getAppliedAthFlowIds(listOf("dep-at-obs")) shouldBe setOf("dep-at-obs")
                statsRepository.getAppliedAthFlowIds(listOf("dep-above-obs")) shouldBe emptySet()

                // The fold cycle's own snapshot carries the baseline value.
                tradeRepository.saveSnapshot(TestFixtures.emptySnapshot(t85, BigDecimal("55000.00")))
                ledgerRepository.setSyncMetadata(
                    SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC,
                    t95.epochSecond.toString(),
                )
                val second = analyzer(t95).updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("60000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = t95,
                )
                // dep-above-obs now sits inside the observation and scales
                // exactly once against the 55k baseline: 55k * 60/55 = 60k.
                (second as AthUpdateResult.Trusted).drawdownPct.shouldBeEqualComparingTo(BigDecimal.ZERO)
                statsRepository.load().allTimeHigh.shouldBeEqualComparingTo(BigDecimal("60000.00"))
                statsRepository.getAppliedAthFlowIds(listOf("dep-above-obs")) shouldBe setOf("dep-above-obs")
            }
        }

        "scan horizon is capped at the balance observation inside wider coverage" {
            runTest {
                statsRepository.save(PortfolioStats(BigDecimal("120000.00"), BigDecimal.ZERO))
                tradeRepository.saveSnapshot(TestFixtures.emptySnapshot(t60, BigDecimal("100000.00")))
                ledgerRepository.saveLedgers(listOf(deposit("dep-gap", t80, "5000.00")))
                // Coverage t90 extends past the t75 observation: the t80
                // deposit is not reflected in the 100k total yet.
                ledgerRepository.setSyncMetadata(
                    SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC,
                    t90.epochSecond.toString(),
                )
                val first = analyzer(t90).updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("100000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = t75,
                )
                (first as AthUpdateResult.Trusted).drawdownPct.shouldBeEqualComparingTo(
                    BigDecimal("16.6667"),
                )
                statsRepository.load().allTimeHigh.shouldBeEqualComparingTo(BigDecimal("120000.00"))
                statsRepository.getAppliedAthFlowIds(listOf("dep-gap")) shouldBe emptySet()

                // Next cycle observes past the deposit: it scales exactly once.
                ledgerRepository.setSyncMetadata(
                    SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC,
                    t95.epochSecond.toString(),
                )
                val second = analyzer(t95).updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("105000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = t95,
                )
                (second as AthUpdateResult.Trusted)
                statsRepository.load().allTimeHigh.shouldBeEqualComparingTo(BigDecimal("126000.00"))
                statsRepository.getAppliedAthFlowIds(listOf("dep-gap")) shouldBe setOf("dep-gap")
            }
        }

        "initial ATH defers when coverage predates the observation" {
            runTest {
                statsRepository.save(PortfolioStats(BigDecimal.ZERO, BigDecimal.ZERO))
                // Coverage t80 is behind the t85 observation: the baseline
                // total may contain unsynced flows, so no ATH may be folded.
                ledgerRepository.setSyncMetadata(
                    SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC,
                    t80.epochSecond.toString(),
                )
                ledgerRepository.saveLedgers(listOf(deposit("dep-gate", t70, "5000.00")))

                val result = analyzer(t85).updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("55000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = t85,
                )
                requireNotNull((result as AthUpdateResult.Deferred).lastTrustedDrawdownPct)
                    .shouldBeEqualComparingTo(BigDecimal.ZERO)
                statsRepository.load().allTimeHigh.shouldBeEqualComparingTo(BigDecimal.ZERO)
                statsRepository.getAppliedAthFlowIds(listOf("dep-gate")) shouldBe emptySet()
            }
        }
    }
}
