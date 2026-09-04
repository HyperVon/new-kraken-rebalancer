package com.gemini.krakenbot.service.impl

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.DatabaseConfig
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.KrakenApiConstants
import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.model.PortfolioStats
import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.repository.impl.SqliteLedgerRepositoryImpl
import com.gemini.krakenbot.repository.impl.SqlitePortfolioStatsRepositoryImpl
import com.gemini.krakenbot.repository.impl.SqliteTradeRepositoryImpl
import com.gemini.krakenbot.service.AthUpdateResult
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.KrakenService
import io.kotest.assertions.throwables.shouldThrow
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
    private val t80 = t0.plusSeconds(80)
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
                // (Steady state: the flow watermark was initialized by an
                // earlier cycle; a missing watermark means "fresh database"
                // and only advances, by pre-existing init semantics.)
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
                // The journal row was pruned by the advancing watermark by
                // design; exact-once is proven by the unchanged rerun below.

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

        "worthless exact snapshot falls through to the residual basis" {
            runTest {
                statsRepository.save(PortfolioStats(BigDecimal("100000.00"), BigDecimal.ZERO))
                // A zero-total snapshot at the event instant carries no basis
                // information; with no predecessor the residual applies.
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
                (result as AthUpdateResult.Trusted).drawdownPct.shouldBeEqualComparingTo(BigDecimal("20.0000"))
                // Residual 80k: 100k * 100/80 = 125k; 100k against it is 20% down.
                statsRepository.load().allTimeHigh.shouldBeEqualComparingTo(BigDecimal("125000.00"))
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
            }
        }

        "unbasis-able flow with no positive residual fails closed" {
            runTest {
                statsRepository.save(PortfolioStats(BigDecimal("100000.00"), BigDecimal.ZERO))
                ledgerRepository.saveLedgers(listOf(deposit("dep-zero", t70, "10000.00")))
                tradeRepository.setSyncMetadata(
                    SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC,
                    t60.epochSecond.toString(),
                )
                ledgerRepository.setSyncMetadata(
                    SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC,
                    t80.epochSecond.toString(),
                )

                shouldThrow<IllegalStateException> {
                    analyzer(t80).updateAthAndCalculateDrawdown(
                        totalPortfolioValueUSD = BigDecimal.ZERO,
                        netExternalFlowUSD = BigDecimal.ZERO,
                        balancesObservedAt = t80,
                    )
                }
            }
        }
    }
}
