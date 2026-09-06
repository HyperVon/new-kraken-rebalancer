package com.gemini.krakenbot.service.impl

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.DatabaseConfig
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.DepositStatusRecord
import com.gemini.krakenbot.model.FundingEvidence
import com.gemini.krakenbot.model.FundingProvenanceFailure
import com.gemini.krakenbot.model.FundingProvenanceFailureReason
import com.gemini.krakenbot.model.FundingProvenanceResolver
import com.gemini.krakenbot.model.KrakenApiConstants
import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.PortfolioStats
import com.gemini.krakenbot.model.SimpleFundingProvenanceResolver
import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.model.WithdrawStatusRecord
import com.gemini.krakenbot.repository.AppliedAthFlow
import com.gemini.krakenbot.repository.impl.SqliteLedgerRepositoryImpl
import com.gemini.krakenbot.repository.impl.SqlitePortfolioStatsRepositoryImpl
import com.gemini.krakenbot.repository.impl.SqliteTradeRepositoryImpl
import com.gemini.krakenbot.repository.table.LedgerTable
import com.gemini.krakenbot.service.AthTrustFailureReason
import com.gemini.krakenbot.service.AthUpdateResult
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.service.PortfolioAnalyzer
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
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

    private var testProvenanceResolver: FundingProvenanceResolver = FundingProvenanceResolver { event ->
        val ref = event.refid ?: ""
        if (ref.startsWith("FT-") || ref.startsWith("WIRE-") || ref.startsWith("CONFIRMED-") ||
            ref.startsWith("EXT-") || ref == "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855" ||
            ref in setOf("d-a", "d-b", "d-after", "z-in", "f0", "f3", "f4")
        ) {
            FundingEvidence.EXTERNAL
        } else {
            FundingEvidence.UNRESOLVED
        }
    }

    private fun analyzer(
        now: Instant,
        resolver: FundingProvenanceResolver = testProvenanceResolver,
    ): PortfolioAnalyzer = object : PortfolioAnalyzer by PortfolioAnalyzerImpl(
        krakenService = krakenService,
        configService = configService,
        portfolioStatsRepository = statsRepository,
        nowProvider = { now },
        ledgerRepository = ledgerRepository,
        tradeRepository = tradeRepository,
    ) {
        override suspend fun updateAthAndCalculateDrawdown(
            totalPortfolioValueUSD: BigDecimal,
            netExternalFlowUSD: BigDecimal,
            balancesObservedAt: Instant?,
        ): AthUpdateResult = updateAthAndCalculateDrawdown(
            totalPortfolioValueUSD,
            netExternalFlowUSD,
            balancesObservedAt,
            resolver,
        )
    }

    private fun deposit(id: String, time: Instant, amountUsd: String, refid: String? = null) = LedgerEvent(
        ledgerId = id,
        refid = refid ?: "FT-$id",
        time = time,
        type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
        asset = "USD",
        amount = BigDecimal(amountUsd),
    )

    init {
        beforeTest {
            testProvenanceResolver = FundingProvenanceResolver { event ->
                val ref = event.refid ?: ""
                if (ref.startsWith("FT-") || ref.startsWith("WIRE-") || ref.startsWith("CONFIRMED-") ||
                    ref.startsWith(
                        "EXT-",
                    ) || ref == "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855" ||
                    ref in setOf("d-a", "d-b", "d-after", "z-in", "f0", "f3", "f4")
                ) {
                    FundingEvidence.EXTERNAL
                } else {
                    FundingEvidence.UNRESOLVED
                }
            }
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
                stale shouldBe AthUpdateResult.Deferred(
                    BigDecimal("20.0000"),
                    AthTrustFailureReason.LEDGER_COVERAGE_STALE,
                )
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

        "late card legs defer the first cycle and normalize once when the complete group arrives" {
            runTest {
                statsRepository.save(PortfolioStats(BigDecimal("10000.00"), BigDecimal.ZERO))
                tradeRepository.setSyncMetadata(
                    SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC,
                    t60.epochSecond.toString(),
                )
                ledgerRepository.setSyncMetadata(
                    SyncMetadataKeys.ATH_FLOW_JOURNAL_MIGRATED,
                    "true",
                )
                tradeRepository.saveSnapshot(TestFixtures.emptySnapshot(t60, BigDecimal("10000.00")))
                val cardRef = "CARD-LATE-ARRIVAL"
                val cardTime = t70
                val deposit = LedgerEvent(
                    ledgerId = "late-deposit",
                    refid = cardRef,
                    time = cardTime,
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    asset = "USD",
                    amount = BigDecimal("5000.00"),
                )
                val resolver = SimpleFundingProvenanceResolver(
                    deposits = listOf(
                        DepositStatusRecord(
                            refid = cardRef,
                            asset = "USD",
                            amount = BigDecimal("5000.00"),
                            time = cardTime,
                            status = "Success",
                            method = "Visa",
                        ),
                    ),
                )
                ledgerRepository.saveLedgers(listOf(deposit))
                ledgerRepository.setSyncMetadata(
                    SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC,
                    t80.epochSecond.toString(),
                )

                val first = analyzer(t80, resolver).updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("15000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = t80,
                )
                first shouldBe AthUpdateResult.Deferred(
                    BigDecimal("0.0000"),
                    AthTrustFailureReason.AMBIGUOUS_FUNDING,
                )
                statsRepository.getAppliedAthFlowIds(listOf("late-deposit")) shouldBe emptySet()

                ledgerRepository.saveLedgers(
                    listOf(
                        LedgerEvent(
                            ledgerId = "late-spend",
                            refid = cardRef,
                            time = cardTime.plusMillis(100),
                            type = KrakenApiConstants.LEDGER_TYPE_SPEND,
                            asset = "USD",
                            amount = BigDecimal("-4980.00"),
                            fee = BigDecimal("20.00"),
                        ),
                        LedgerEvent(
                            ledgerId = "late-receive",
                            refid = cardRef,
                            time = cardTime.plusMillis(200),
                            type = KrakenApiConstants.LEDGER_TYPE_RECEIVE,
                            asset = "BTC",
                            amount = BigDecimal("0.0996"),
                        ),
                    ),
                )

                val second = analyzer(t80, resolver).updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("14980.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = t80,
                )
                second shouldBe AthUpdateResult.Trusted(BigDecimal.ZERO)
                statsRepository.load().allTimeHigh.shouldBeEqualComparingTo(BigDecimal("14980.00"))
                statsRepository.getAppliedAthFlowIds(
                    listOf("late-deposit", "late-spend", "late-receive"),
                ) shouldBe setOf("late-deposit", "late-spend", "late-receive")
            }
        }

        "initial ATH absorption also waits for card plumbing before journaling" {
            runTest {
                statsRepository.save(PortfolioStats(BigDecimal.ZERO, BigDecimal.ZERO))
                tradeRepository.setSyncMetadata(
                    SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC,
                    t60.epochSecond.toString(),
                )
                ledgerRepository.setSyncMetadata(SyncMetadataKeys.ATH_FLOW_JOURNAL_MIGRATED, "true")
                val cardRef = "CARD-INITIAL-LATE"
                val cardTime = t70
                val deposit = LedgerEvent(
                    ledgerId = "initial-late-deposit",
                    refid = cardRef,
                    time = cardTime,
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    asset = "USD",
                    amount = BigDecimal("5000.00"),
                )
                ledgerRepository.saveLedgers(listOf(deposit))
                ledgerRepository.setSyncMetadata(
                    SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC,
                    t80.epochSecond.toString(),
                )
                val resolver = SimpleFundingProvenanceResolver(
                    deposits = listOf(
                        DepositStatusRecord(
                            refid = cardRef,
                            asset = "USD",
                            amount = BigDecimal("5000.00"),
                            time = cardTime,
                            status = "Success",
                            method = "Visa",
                        ),
                    ),
                )

                val first = analyzer(t80, resolver).updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("15000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = t80,
                )
                val deferred = first.shouldBeInstanceOf<AthUpdateResult.Deferred>()
                deferred.reason shouldBe AthTrustFailureReason.AMBIGUOUS_FUNDING
                statsRepository.getAppliedAthFlowIds(listOf(deposit.ledgerId)) shouldBe emptySet()
            }
        }

        "initial ATH defers when a complete card group has an unpriceable crypto fee" {
            runTest {
                statsRepository.save(PortfolioStats(BigDecimal.ZERO, BigDecimal.ZERO))
                ledgerRepository.setSyncMetadata(
                    SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC,
                    t80.epochSecond.toString(),
                )
                val cardRef = "CARD-INITIAL-UNPRICEABLE"
                val cardTime = t70
                val events = listOf(
                    LedgerEvent(
                        ledgerId = "initial-unpriceable-deposit",
                        refid = cardRef,
                        time = cardTime,
                        type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                        asset = "USD",
                        amount = BigDecimal("5000.00"),
                    ),
                    LedgerEvent(
                        ledgerId = "initial-unpriceable-spend",
                        refid = cardRef,
                        time = cardTime.plusMillis(100),
                        type = KrakenApiConstants.LEDGER_TYPE_SPEND,
                        asset = "USD",
                        amount = BigDecimal("-4980.00"),
                        fee = BigDecimal("20.00"),
                    ),
                    LedgerEvent(
                        ledgerId = "initial-unpriceable-receive",
                        refid = cardRef,
                        time = cardTime.plusMillis(200),
                        type = KrakenApiConstants.LEDGER_TYPE_RECEIVE,
                        asset = "BTC",
                        amount = BigDecimal("0.0996"),
                        fee = BigDecimal("0.0001"),
                    ),
                )
                ledgerRepository.saveLedgers(events)
                val resolver = SimpleFundingProvenanceResolver(
                    deposits = listOf(
                        DepositStatusRecord(
                            refid = cardRef,
                            asset = "USD",
                            amount = BigDecimal("5000.00"),
                            time = cardTime,
                            status = "Success",
                            method = "Visa",
                        ),
                    ),
                )

                val result = analyzer(t80, resolver).updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("14975.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = t80,
                )

                result shouldBe AthUpdateResult.Deferred(
                    BigDecimal("0.0000"),
                    AthTrustFailureReason.HISTORICAL_PRICE_UNAVAILABLE,
                )
                statsRepository.getAppliedAthFlowIds(events.map { it.ledgerId }) shouldBe emptySet()
            }
        }

        "initial ATH absorbs a complete card contribution and journals every leg" {
            runTest {
                statsRepository.save(PortfolioStats(BigDecimal.ZERO, BigDecimal.ZERO))
                ledgerRepository.setSyncMetadata(
                    SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC,
                    t80.epochSecond.toString(),
                )
                val cardRef = "CARD-INITIAL-COMPLETE"
                val cardTime = t70
                val events = listOf(
                    LedgerEvent(
                        ledgerId = "initial-complete-deposit",
                        refid = cardRef,
                        time = cardTime,
                        type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                        asset = "USD",
                        amount = BigDecimal("5000.00"),
                    ),
                    LedgerEvent(
                        ledgerId = "initial-complete-spend",
                        refid = cardRef,
                        time = cardTime.plusMillis(100),
                        type = KrakenApiConstants.LEDGER_TYPE_SPEND,
                        asset = "USD",
                        amount = BigDecimal("-4980.00"),
                        fee = BigDecimal("20.00"),
                    ),
                    LedgerEvent(
                        ledgerId = "initial-complete-receive",
                        refid = cardRef,
                        time = cardTime.plusMillis(200),
                        type = KrakenApiConstants.LEDGER_TYPE_RECEIVE,
                        asset = "BTC",
                        amount = BigDecimal("0.0996"),
                    ),
                )
                ledgerRepository.saveLedgers(events)
                val resolver = SimpleFundingProvenanceResolver(
                    deposits = listOf(
                        DepositStatusRecord(
                            refid = cardRef,
                            asset = "USD",
                            amount = BigDecimal("5000.00"),
                            time = cardTime,
                            status = "Success",
                            method = "Visa",
                        ),
                    ),
                )

                val result = analyzer(t80, resolver).updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("14980.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = t80,
                )

                result shouldBe AthUpdateResult.Trusted(BigDecimal.ZERO)
                statsRepository.getAppliedAthFlowIds(events.map { it.ledgerId }) shouldBe
                    events.map { it.ledgerId }.toSet()
            }
        }

        "initial ATH absorbs a complete card withdrawal and journals every leg" {
            runTest {
                statsRepository.save(PortfolioStats(BigDecimal.ZERO, BigDecimal.ZERO))
                ledgerRepository.setSyncMetadata(
                    SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC,
                    t80.epochSecond.toString(),
                )
                val cardRef = "CARD-INITIAL-WITHDRAWAL"
                val cardTime = t70
                val events = listOf(
                    LedgerEvent(
                        ledgerId = "initial-withdrawal-funding",
                        refid = cardRef,
                        time = cardTime,
                        type = KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL,
                        asset = "USD",
                        amount = BigDecimal("-5000.00"),
                    ),
                    LedgerEvent(
                        ledgerId = "initial-withdrawal-spend",
                        refid = cardRef,
                        time = cardTime.plusMillis(100),
                        type = KrakenApiConstants.LEDGER_TYPE_SPEND,
                        asset = "BTC",
                        amount = BigDecimal("-0.0996"),
                    ),
                    LedgerEvent(
                        ledgerId = "initial-withdrawal-receive",
                        refid = cardRef,
                        time = cardTime.plusMillis(200),
                        type = KrakenApiConstants.LEDGER_TYPE_RECEIVE,
                        asset = "USD",
                        amount = BigDecimal("4980.00"),
                        fee = BigDecimal("20.00"),
                    ),
                )
                ledgerRepository.saveLedgers(events)
                val resolver = SimpleFundingProvenanceResolver(
                    withdrawals = listOf(
                        WithdrawStatusRecord(
                            refid = cardRef,
                            asset = "USD",
                            amount = BigDecimal("5000.00"),
                            time = cardTime,
                            status = "Success",
                            method = "Visa",
                        ),
                    ),
                )

                val result = analyzer(t80, resolver).updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("5000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = t80,
                )

                result shouldBe AthUpdateResult.Trusted(BigDecimal.ZERO)
                statsRepository.getAppliedAthFlowIds(events.map { it.ledgerId }) shouldBe
                    events.map { it.ledgerId }.toSet()
            }
        }

        "an old decided ambiguous card group does not block a new ordinary bank deposit" {
            runTest {
                statsRepository.save(PortfolioStats(BigDecimal("10000.00"), BigDecimal.ZERO))
                tradeRepository.setSyncMetadata(
                    SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC,
                    t60.epochSecond.toString(),
                )
                ledgerRepository.setSyncMetadata(SyncMetadataKeys.ATH_FLOW_JOURNAL_MIGRATED, "true")
                tradeRepository.saveSnapshot(TestFixtures.emptySnapshot(t60, BigDecimal("10000.00")))
                val oldRef = "CARD-OLD-INCOMPLETE"
                val bankRef = "ACH-NEW-BANK"
                val oldDeposit = LedgerEvent(
                    ledgerId = "old-card-deposit",
                    refid = oldRef,
                    time = t70,
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    asset = "USD",
                    amount = BigDecimal("5000.00"),
                )
                val bankDeposit = LedgerEvent(
                    ledgerId = "new-bank-deposit",
                    refid = bankRef,
                    time = t75,
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    asset = "USD",
                    amount = BigDecimal("1000.00"),
                )
                ledgerRepository.saveLedgers(listOf(oldDeposit, bankDeposit))
                statsRepository.journalPresumedDecidedFlows(
                    listOf(AppliedAthFlow(oldDeposit.ledgerId, oldDeposit.time.epochSecond)),
                )
                ledgerRepository.setSyncMetadata(
                    SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC,
                    t80.epochSecond.toString(),
                )
                val resolver = SimpleFundingProvenanceResolver(
                    deposits = listOf(
                        DepositStatusRecord(
                            refid = oldRef,
                            asset = "USD",
                            amount = BigDecimal("5000.00"),
                            time = t70,
                            status = "Success",
                            method = "Visa",
                        ),
                        DepositStatusRecord(
                            refid = bankRef,
                            asset = "USD",
                            amount = BigDecimal("1000.00"),
                            time = t75,
                            status = "Success",
                            method = "ACH",
                        ),
                    ),
                )

                val result = analyzer(t80, resolver).updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("11000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = t80,
                )

                result shouldBe AthUpdateResult.Trusted(BigDecimal.ZERO)
                statsRepository.load().allTimeHigh.shouldBeEqualComparingTo(BigDecimal("11000.00"))
                statsRepository.getAppliedAthFlowIds(listOf("old-card-deposit")) shouldBe setOf("old-card-deposit")
                statsRepository.getAppliedAthFlowIds(listOf("new-bank-deposit")) shouldBe setOf("new-bank-deposit")
            }
        }

        "a decided complete card group replays actual asset deltas before a later bank flow" {
            runTest {
                statsRepository.save(PortfolioStats(BigDecimal("30000.00"), BigDecimal.ZERO))
                tradeRepository.setSyncMetadata(
                    SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC,
                    t60.epochSecond.toString(),
                )
                ledgerRepository.setSyncMetadata(SyncMetadataKeys.ATH_FLOW_JOURNAL_MIGRATED, "true")
                tradeRepository.saveSnapshot(
                    PortfolioSnapshot(
                        timestamp = t60,
                        totalValueUSD = BigDecimal("10000.00"),
                        assets = mapOf(
                            "BTC" to TestFixtures.assetSnapshot(
                                symbol = "BTC",
                                balance = BigDecimal("0.10"),
                                price = BigDecimal("50000.00"),
                                valueUSD = BigDecimal("5000.00"),
                                targetPercent = BigDecimal("50"),
                            ),
                            "USD" to TestFixtures.assetSnapshot(
                                symbol = "USD",
                                balance = BigDecimal("5000.00"),
                                price = BigDecimal.ONE,
                                valueUSD = BigDecimal("5000.00"),
                                targetPercent = BigDecimal("50"),
                            ),
                        ),
                        actions = emptyList(),
                        drawdownPercent = BigDecimal.ZERO,
                        fiatDeploymentPercent = BigDecimal.ZERO,
                        effectiveUsdTargetPercent = BigDecimal.ZERO,
                    ),
                )
                val cardRef = "CARD-DECIDED-COMPLETE"
                val bankRef = "ACH-AFTER-CARD"
                val cardTime = t70
                val bankTime = t0.plusSeconds(300)
                val cardDeposit = LedgerEvent(
                    ledgerId = "decided-card-deposit",
                    refid = cardRef,
                    time = cardTime,
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    asset = "USD",
                    amount = BigDecimal("5000.00"),
                )
                val cardSpend = LedgerEvent(
                    ledgerId = "decided-card-spend",
                    refid = cardRef,
                    time = cardTime.plusMillis(100),
                    type = KrakenApiConstants.LEDGER_TYPE_SPEND,
                    asset = "USD",
                    amount = BigDecimal("-4980.00"),
                    fee = BigDecimal("20.00"),
                )
                val cardReceive = LedgerEvent(
                    ledgerId = "decided-card-receive",
                    refid = cardRef,
                    time = cardTime.plusMillis(200),
                    type = KrakenApiConstants.LEDGER_TYPE_RECEIVE,
                    asset = "BTC",
                    amount = BigDecimal("0.0996"),
                    fee = BigDecimal("0.0001"),
                )
                val bankDeposit = LedgerEvent(
                    ledgerId = "after-card-bank",
                    refid = bankRef,
                    time = bankTime,
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    asset = "USD",
                    amount = BigDecimal("1000.00"),
                )
                ledgerRepository.saveLedgers(listOf(cardDeposit, cardSpend, cardReceive, bankDeposit))
                statsRepository.journalPresumedDecidedFlows(
                    listOf(
                        AppliedAthFlow(cardDeposit.ledgerId, cardDeposit.time.epochSecond),
                        AppliedAthFlow(cardSpend.ledgerId, cardSpend.time.epochSecond),
                        AppliedAthFlow(cardReceive.ledgerId, cardReceive.time.epochSecond),
                    ),
                )
                ledgerRepository.setSyncMetadata(
                    SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC,
                    bankTime.epochSecond.toString(),
                )
                coEvery { krakenService.getOHLC(any(), any(), any()) } returns listOf(
                    bankTime.minusSeconds(1800).epochSecond to BigDecimal("60000.00"),
                )
                val resolver = SimpleFundingProvenanceResolver(
                    deposits = listOf(
                        DepositStatusRecord(
                            refid = cardRef,
                            asset = "USD",
                            amount = BigDecimal("5000.00"),
                            time = cardTime,
                            status = "Success",
                            method = "Visa",
                        ),
                        DepositStatusRecord(
                            refid = bankRef,
                            asset = "USD",
                            amount = BigDecimal("1000.00"),
                            time = bankTime,
                            status = "Success",
                            method = "ACH",
                        ),
                    ),
                )

                val result = analyzer(bankTime, resolver).updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("17970.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = bankTime,
                )

                result.shouldBeInstanceOf<AthUpdateResult.Trusted>()
                // Before the bank deposit: .1 BTC + .0995 BTC after the
                // crypto fee at $60k + $5k USD = $16,970. The $1,000 bank
                // deposit is the only new owner flow, so
                // 30,000 * 17,970 / 16,970 = 31,767.83. The card fee is
                // represented once in the actual delta, not replayed again.
                statsRepository.load().allTimeHigh.shouldBeEqualComparingTo(BigDecimal("31767.83"))
                statsRepository.getAppliedAthFlowIds(listOf(bankDeposit.ledgerId)) shouldBe setOf(bankDeposit.ledgerId)
            }
        }

        "a completed card asset outside the ATH universe is ignored during basis replay" {
            runTest {
                statsRepository.save(PortfolioStats(BigDecimal("30000.00"), BigDecimal.ZERO))
                tradeRepository.setSyncMetadata(
                    SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC,
                    t60.epochSecond.toString(),
                )
                ledgerRepository.setSyncMetadata(SyncMetadataKeys.ATH_FLOW_JOURNAL_MIGRATED, "true")
                tradeRepository.saveSnapshot(TestFixtures.emptySnapshot(t60, BigDecimal("10000.00")))
                val cardRef = "CARD-OFF-UNIVERSE"
                val bankRef = "ACH-AFTER-OFF-UNIVERSE"
                val cardTime = t70
                val bankTime = t0.plusSeconds(300)
                val cardEvents = listOf(
                    LedgerEvent(
                        ledgerId = "off-universe-card-deposit",
                        refid = cardRef,
                        time = cardTime,
                        type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                        asset = "USD",
                        amount = BigDecimal("5000.00"),
                    ),
                    LedgerEvent(
                        ledgerId = "off-universe-card-spend",
                        refid = cardRef,
                        time = cardTime.plusMillis(100),
                        type = KrakenApiConstants.LEDGER_TYPE_SPEND,
                        asset = "USD",
                        amount = BigDecimal("-4980.00"),
                        fee = BigDecimal("20.00"),
                    ),
                    LedgerEvent(
                        ledgerId = "off-universe-card-receive",
                        refid = cardRef,
                        time = cardTime.plusMillis(200),
                        type = KrakenApiConstants.LEDGER_TYPE_RECEIVE,
                        asset = "SOL",
                        amount = BigDecimal("0.0996"),
                    ),
                )
                val bankDeposit = deposit(
                    id = "off-universe-bank",
                    time = bankTime,
                    amountUsd = "1000.00",
                    refid = bankRef,
                )
                ledgerRepository.saveLedgers(cardEvents + bankDeposit)
                statsRepository.journalPresumedDecidedFlows(
                    cardEvents.map { AppliedAthFlow(it.ledgerId, it.time.epochSecond) },
                )
                ledgerRepository.setSyncMetadata(
                    SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC,
                    bankTime.epochSecond.toString(),
                )
                val resolver = SimpleFundingProvenanceResolver(
                    deposits = listOf(
                        DepositStatusRecord(
                            refid = cardRef,
                            asset = "USD",
                            amount = BigDecimal("5000.00"),
                            time = cardTime,
                            status = "Success",
                            method = "Visa",
                        ),
                        DepositStatusRecord(
                            refid = bankRef,
                            asset = "USD",
                            amount = BigDecimal("1000.00"),
                            time = bankTime,
                            status = "Success",
                            method = "ACH",
                        ),
                    ),
                )

                val result = analyzer(bankTime, resolver).updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("11000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = bankTime,
                )

                result shouldBe AthUpdateResult.Trusted(BigDecimal("66.6667"))
                statsRepository.load().allTimeHigh.shouldBeEqualComparingTo(BigDecimal("33000.00"))
                statsRepository.getAppliedAthFlowIds(listOf(bankDeposit.ledgerId)) shouldBe setOf(bankDeposit.ledgerId)
            }
        }

        "a current card flow uses actual deltas when a later bank flow shares the ATH batch" {
            runTest {
                statsRepository.save(PortfolioStats(BigDecimal("30000.00"), BigDecimal.ZERO))
                tradeRepository.setSyncMetadata(
                    SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC,
                    t60.epochSecond.toString(),
                )
                ledgerRepository.setSyncMetadata(SyncMetadataKeys.ATH_FLOW_JOURNAL_MIGRATED, "true")
                tradeRepository.saveSnapshot(
                    PortfolioSnapshot(
                        timestamp = t60,
                        totalValueUSD = BigDecimal("10000.00"),
                        assets = mapOf(
                            "BTC" to TestFixtures.assetSnapshot(
                                symbol = "BTC",
                                balance = BigDecimal("0.10"),
                                price = BigDecimal("50000.00"),
                                valueUSD = BigDecimal("5000.00"),
                                targetPercent = BigDecimal("50"),
                            ),
                            "USD" to TestFixtures.assetSnapshot(
                                symbol = "USD",
                                balance = BigDecimal("5000.00"),
                                price = BigDecimal.ONE,
                                valueUSD = BigDecimal("5000.00"),
                                targetPercent = BigDecimal("50"),
                            ),
                        ),
                        actions = emptyList(),
                        drawdownPercent = BigDecimal.ZERO,
                        fiatDeploymentPercent = BigDecimal.ZERO,
                        effectiveUsdTargetPercent = BigDecimal.ZERO,
                    ),
                )
                val cardRef = "CARD-CURRENT-BATCH"
                val bankRef = "ACH-CURRENT-BATCH"
                val cardTime = t70
                val bankTime = t0.plusSeconds(300)
                val cardDeposit = LedgerEvent(
                    ledgerId = "current-card-deposit",
                    refid = cardRef,
                    time = cardTime,
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    asset = "USD",
                    amount = BigDecimal("5000.00"),
                )
                val cardSpend = LedgerEvent(
                    ledgerId = "current-card-spend",
                    refid = cardRef,
                    time = cardTime.plusMillis(100),
                    type = KrakenApiConstants.LEDGER_TYPE_SPEND,
                    asset = "USD",
                    amount = BigDecimal("-4980.00"),
                    fee = BigDecimal("20.00"),
                )
                val cardReceive = LedgerEvent(
                    ledgerId = "current-card-receive",
                    refid = cardRef,
                    time = cardTime.plusMillis(200),
                    type = KrakenApiConstants.LEDGER_TYPE_RECEIVE,
                    asset = "BTC",
                    amount = BigDecimal("0.0996"),
                    fee = BigDecimal("0.0001"),
                )
                val bankDeposit = LedgerEvent(
                    ledgerId = "current-batch-bank",
                    refid = bankRef,
                    time = bankTime,
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    asset = "USD",
                    amount = BigDecimal("1000.00"),
                )
                ledgerRepository.saveLedgers(listOf(cardDeposit, cardSpend, cardReceive, bankDeposit))
                ledgerRepository.setSyncMetadata(
                    SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC,
                    bankTime.epochSecond.toString(),
                )
                coEvery { krakenService.getOHLC(any(), any(), any()) } returns listOf(
                    bankTime.minusSeconds(1800).epochSecond to BigDecimal("60000.00"),
                )
                val resolver = SimpleFundingProvenanceResolver(
                    deposits = listOf(
                        DepositStatusRecord(
                            refid = cardRef,
                            asset = "USD",
                            amount = BigDecimal("5000.00"),
                            time = cardTime,
                            status = "Success",
                            method = "Visa",
                        ),
                        DepositStatusRecord(
                            refid = bankRef,
                            asset = "USD",
                            amount = BigDecimal("1000.00"),
                            time = bankTime,
                            status = "Success",
                            method = "ACH",
                        ),
                    ),
                )

                val result = analyzer(bankTime, resolver).updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("17970.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = bankTime,
                )

                result.shouldBeInstanceOf<AthUpdateResult.Trusted>()
                // First card step: 30,000 * 14,975 / 10,000 = 44,925.
                // Second bank step must replay the card's .0995 BTC actual
                // delta and fee once: basis 16,970, final ATH 47,572.32.
                statsRepository.load().allTimeHigh.shouldBeEqualComparingTo(BigDecimal("47572.32"))
                statsRepository.getAppliedAthFlowIds(
                    listOf(
                        cardDeposit.ledgerId,
                        cardSpend.ledgerId,
                        cardReceive.ledgerId,
                        bankDeposit.ledgerId,
                    ),
                ) shouldBe setOf(
                    cardDeposit.ledgerId,
                    cardSpend.ledgerId,
                    cardReceive.ledgerId,
                    bankDeposit.ledgerId,
                )
            }
        }

        "a decided card flow near a later bank event defers uncertain ordering" {
            runTest {
                statsRepository.save(PortfolioStats(BigDecimal("30000.00"), BigDecimal.ZERO))
                tradeRepository.setSyncMetadata(
                    SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC,
                    t60.epochSecond.toString(),
                )
                ledgerRepository.setSyncMetadata(SyncMetadataKeys.ATH_FLOW_JOURNAL_MIGRATED, "true")
                tradeRepository.saveSnapshot(TestFixtures.emptySnapshot(t60, BigDecimal("10000.00")))

                val cardRef = "CARD-ORDERING-UNCERTAIN"
                val eventTime = t70
                val cardEvents = listOf(
                    LedgerEvent(
                        ledgerId = "ordering-card-deposit",
                        refid = cardRef,
                        time = eventTime,
                        type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                        asset = "USD",
                        amount = BigDecimal("5000.00"),
                    ),
                    LedgerEvent(
                        ledgerId = "ordering-card-spend",
                        refid = cardRef,
                        time = eventTime,
                        type = KrakenApiConstants.LEDGER_TYPE_SPEND,
                        asset = "USD",
                        amount = BigDecimal("-4980.00"),
                        fee = BigDecimal("20.00"),
                    ),
                    LedgerEvent(
                        ledgerId = "ordering-card-receive",
                        refid = cardRef,
                        time = eventTime,
                        type = KrakenApiConstants.LEDGER_TYPE_RECEIVE,
                        asset = "BTC",
                        amount = BigDecimal("0.0996"),
                    ),
                )
                val bankDeposit = deposit(
                    id = "ordering-bank-deposit",
                    time = eventTime,
                    amountUsd = "1000.00",
                    refid = "ACH-ORDERING-UNCERTAIN",
                )
                ledgerRepository.saveLedgers(cardEvents + bankDeposit)
                statsRepository.journalPresumedDecidedFlows(
                    cardEvents.map { AppliedAthFlow(it.ledgerId, it.time.epochSecond) },
                )
                ledgerRepository.setSyncMetadata(
                    SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC,
                    t80.epochSecond.toString(),
                )
                val resolver = SimpleFundingProvenanceResolver(
                    deposits = listOf(
                        DepositStatusRecord(
                            refid = cardRef,
                            asset = "USD",
                            amount = BigDecimal("5000.00"),
                            time = eventTime,
                            status = "Success",
                            method = "Visa",
                        ),
                        DepositStatusRecord(
                            refid = "ACH-ORDERING-UNCERTAIN",
                            asset = "USD",
                            amount = BigDecimal("1000.00"),
                            time = eventTime,
                            status = "Success",
                            method = "ACH",
                        ),
                    ),
                )

                val result = analyzer(t80, resolver).updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("11000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = t80,
                )

                result shouldBe AthUpdateResult.Deferred(
                    BigDecimal.ZERO.setScale(4),
                    AthTrustFailureReason.EVENT_ORDERING_UNCERTAIN,
                )
                statsRepository.getAppliedAthFlowIds(listOf(bankDeposit.ledgerId)) shouldBe emptySet()
            }
        }

        "a decided card group straddling the predecessor snapshot defers later ATH replay" {
            runTest {
                statsRepository.save(PortfolioStats(BigDecimal("30000.00"), BigDecimal.ZERO))
                tradeRepository.setSyncMetadata(
                    SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC,
                    t60.epochSecond.toString(),
                )
                ledgerRepository.setSyncMetadata(SyncMetadataKeys.ATH_FLOW_JOURNAL_MIGRATED, "true")
                tradeRepository.saveSnapshot(
                    PortfolioSnapshot(
                        timestamp = t60,
                        totalValueUSD = BigDecimal("10000.00"),
                        assets = mapOf(
                            "BTC" to TestFixtures.assetSnapshot(
                                symbol = "BTC",
                                balance = BigDecimal("0.10"),
                                price = BigDecimal("50000.00"),
                                valueUSD = BigDecimal("5000.00"),
                                targetPercent = BigDecimal("50"),
                            ),
                            "USD" to TestFixtures.assetSnapshot(
                                symbol = "USD",
                                balance = BigDecimal("5000.00"),
                                price = BigDecimal.ONE,
                                valueUSD = BigDecimal("5000.00"),
                                targetPercent = BigDecimal("50"),
                            ),
                        ),
                        actions = emptyList(),
                        drawdownPercent = BigDecimal.ZERO,
                        fiatDeploymentPercent = BigDecimal.ZERO,
                        effectiveUsdTargetPercent = BigDecimal.ZERO,
                    ),
                )
                val cardRef = "CARD-SNAPSHOT-STRADDLE"
                val bankRef = "ACH-AFTER-SNAPSHOT-STRADDLE"
                val cardEvents = listOf(
                    LedgerEvent(
                        ledgerId = "snapshot-straddle-deposit",
                        refid = cardRef,
                        time = t60.minusSeconds(5),
                        type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                        asset = "USD",
                        amount = BigDecimal("5000.00"),
                    ),
                    LedgerEvent(
                        ledgerId = "snapshot-straddle-spend",
                        refid = cardRef,
                        time = t60.plusSeconds(5),
                        type = KrakenApiConstants.LEDGER_TYPE_SPEND,
                        asset = "USD",
                        amount = BigDecimal("-4980.00"),
                    ),
                    LedgerEvent(
                        ledgerId = "snapshot-straddle-receive",
                        refid = cardRef,
                        time = t60.plusSeconds(5),
                        type = KrakenApiConstants.LEDGER_TYPE_RECEIVE,
                        asset = "BTC",
                        amount = BigDecimal("0.0996"),
                    ),
                )
                val bankDeposit = LedgerEvent(
                    ledgerId = "snapshot-straddle-bank",
                    refid = bankRef,
                    time = t0.plusSeconds(300),
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    asset = "USD",
                    amount = BigDecimal("1000.00"),
                )
                ledgerRepository.saveLedgers(cardEvents + bankDeposit)
                statsRepository.journalPresumedDecidedFlows(
                    cardEvents.map { AppliedAthFlow(it.ledgerId, it.time.epochSecond) },
                )
                ledgerRepository.setSyncMetadata(
                    SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC,
                    bankDeposit.time.epochSecond.toString(),
                )
                val resolver = SimpleFundingProvenanceResolver(
                    deposits = listOf(
                        DepositStatusRecord(
                            refid = cardRef,
                            asset = "USD",
                            amount = BigDecimal("5000.00"),
                            time = t60.minusSeconds(5),
                            status = "Success",
                            method = "Visa",
                        ),
                        DepositStatusRecord(
                            refid = bankRef,
                            asset = "USD",
                            amount = BigDecimal("1000.00"),
                            time = bankDeposit.time,
                            status = "Success",
                            method = "ACH",
                        ),
                    ),
                )

                val result = analyzer(bankDeposit.time, resolver).updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("11000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = bankDeposit.time,
                )

                result.shouldBeInstanceOf<AthUpdateResult.Deferred>().reason shouldBe
                    AthTrustFailureReason.BALANCE_OBSERVATION_UNCERTAIN
                statsRepository.getAppliedAthFlowIds(listOf(bankDeposit.ledgerId)) shouldBe emptySet()
            }
        }

        "a decided card group straddling the balance observation defers later ATH replay" {
            runTest {
                statsRepository.save(PortfolioStats(BigDecimal("30000.00"), BigDecimal.ZERO))
                tradeRepository.setSyncMetadata(
                    SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC,
                    t60.epochSecond.toString(),
                )
                ledgerRepository.setSyncMetadata(SyncMetadataKeys.ATH_FLOW_JOURNAL_MIGRATED, "true")
                tradeRepository.saveSnapshot(
                    PortfolioSnapshot(
                        timestamp = t60,
                        totalValueUSD = BigDecimal("10000.00"),
                        assets = mapOf(
                            "BTC" to TestFixtures.assetSnapshot(
                                symbol = "BTC",
                                balance = BigDecimal("0.10"),
                                price = BigDecimal("50000.00"),
                                valueUSD = BigDecimal("5000.00"),
                                targetPercent = BigDecimal("50"),
                            ),
                            "USD" to TestFixtures.assetSnapshot(
                                symbol = "USD",
                                balance = BigDecimal("5000.00"),
                                price = BigDecimal.ONE,
                                valueUSD = BigDecimal("5000.00"),
                                targetPercent = BigDecimal("50"),
                            ),
                        ),
                        actions = emptyList(),
                        drawdownPercent = BigDecimal.ZERO,
                        fiatDeploymentPercent = BigDecimal.ZERO,
                        effectiveUsdTargetPercent = BigDecimal.ZERO,
                        // The snapshot was saved before the exchange balance
                        // observation completed. The card group crosses that
                        // observation boundary and cannot be replayed safely.
                        balancesObservedAt = t70,
                    ),
                )
                val cardRef = "CARD-OBSERVATION-STRADDLE"
                val bankRef = "ACH-AFTER-OBSERVATION-STRADDLE"
                val cardDeposit = LedgerEvent(
                    ledgerId = "observation-straddle-deposit",
                    refid = cardRef,
                    time = t70.minusSeconds(5),
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    asset = "USD",
                    amount = BigDecimal("5000.00"),
                )
                val cardSpend = LedgerEvent(
                    ledgerId = "observation-straddle-spend",
                    refid = cardRef,
                    time = t70.plusSeconds(5),
                    type = KrakenApiConstants.LEDGER_TYPE_SPEND,
                    asset = "USD",
                    amount = BigDecimal("-4980.00"),
                    fee = BigDecimal("20.00"),
                )
                val cardReceive = LedgerEvent(
                    ledgerId = "observation-straddle-receive",
                    refid = cardRef,
                    time = t70.plusSeconds(5),
                    type = KrakenApiConstants.LEDGER_TYPE_RECEIVE,
                    asset = "BTC",
                    amount = BigDecimal("0.0996"),
                    fee = BigDecimal("0.0001"),
                )
                val bankDeposit = LedgerEvent(
                    ledgerId = "observation-straddle-bank",
                    refid = bankRef,
                    time = t0.plusSeconds(300),
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    asset = "USD",
                    amount = BigDecimal("1000.00"),
                )
                ledgerRepository.saveLedgers(listOf(cardDeposit, cardSpend, cardReceive, bankDeposit))
                statsRepository.journalPresumedDecidedFlows(
                    listOf(
                        cardDeposit,
                        cardSpend,
                        cardReceive,
                    ).map { AppliedAthFlow(it.ledgerId, it.time.epochSecond) },
                )
                ledgerRepository.setSyncMetadata(
                    SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC,
                    bankDeposit.time.epochSecond.toString(),
                )
                val resolver = SimpleFundingProvenanceResolver(
                    deposits = listOf(
                        DepositStatusRecord(
                            refid = cardRef,
                            asset = "USD",
                            amount = BigDecimal("5000.00"),
                            time = cardDeposit.time,
                            status = "Success",
                            method = "Visa",
                        ),
                        DepositStatusRecord(
                            refid = bankRef,
                            asset = "USD",
                            amount = BigDecimal("1000.00"),
                            time = bankDeposit.time,
                            status = "Success",
                            method = "ACH",
                        ),
                    ),
                )

                val result = analyzer(bankDeposit.time, resolver).updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("11000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = bankDeposit.time,
                )

                result.shouldBeInstanceOf<AthUpdateResult.Deferred>().reason shouldBe
                    AthTrustFailureReason.BALANCE_OBSERVATION_UNCERTAIN
                statsRepository.getAppliedAthFlowIds(listOf(bankDeposit.ledgerId)) shouldBe emptySet()
            }
        }

        "a group straddling decided and newly arrived card legs defers without journaling siblings" {
            runTest {
                statsRepository.save(PortfolioStats(BigDecimal("10000.00"), BigDecimal.ZERO))
                tradeRepository.setSyncMetadata(
                    SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC,
                    t60.epochSecond.toString(),
                )
                ledgerRepository.setSyncMetadata(SyncMetadataKeys.ATH_FLOW_JOURNAL_MIGRATED, "true")
                tradeRepository.saveSnapshot(TestFixtures.emptySnapshot(t60, BigDecimal("10000.00")))
                val cardRef = "CARD-STRADDLES-MIGRATION"
                val deposit = LedgerEvent(
                    ledgerId = "straddle-deposit",
                    refid = cardRef,
                    time = t70,
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    asset = "USD",
                    amount = BigDecimal("5000.00"),
                )
                ledgerRepository.saveLedgers(listOf(deposit))
                statsRepository.journalPresumedDecidedFlows(
                    listOf(AppliedAthFlow(deposit.ledgerId, deposit.time.epochSecond)),
                )
                ledgerRepository.saveLedgers(
                    listOf(
                        LedgerEvent(
                            ledgerId = "straddle-spend",
                            refid = cardRef,
                            time = t71,
                            type = KrakenApiConstants.LEDGER_TYPE_SPEND,
                            asset = "USD",
                            amount = BigDecimal("-4980.00"),
                            fee = BigDecimal("20.00"),
                        ),
                        LedgerEvent(
                            ledgerId = "straddle-receive",
                            refid = cardRef,
                            time = t71,
                            type = KrakenApiConstants.LEDGER_TYPE_RECEIVE,
                            asset = "BTC",
                            amount = BigDecimal("0.0996"),
                        ),
                    ),
                )
                ledgerRepository.setSyncMetadata(
                    SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC,
                    t80.epochSecond.toString(),
                )
                val resolver = SimpleFundingProvenanceResolver(
                    deposits = listOf(
                        DepositStatusRecord(
                            refid = cardRef,
                            asset = "USD",
                            amount = BigDecimal("5000.00"),
                            time = t70,
                            status = "Success",
                            method = "Visa",
                        ),
                    ),
                )

                val result = analyzer(t80, resolver).updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("14980.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = t80,
                )

                result shouldBe AthUpdateResult.Deferred(
                    BigDecimal("0.0000"),
                    AthTrustFailureReason.AMBIGUOUS_FUNDING,
                )
                statsRepository.getAppliedAthFlowIds(
                    listOf("straddle-deposit", "straddle-spend", "straddle-receive"),
                ) shouldBe setOf("straddle-deposit")
            }
        }

        "subsecond observation after second-precision ledger coverage defers" {
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

                val result = analyzer(t60.plusMillis(500)).updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("110000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = t60.plusMillis(500),
                )

                result shouldBe AthUpdateResult.Deferred(
                    BigDecimal("20.0000"),
                    AthTrustFailureReason.LEDGER_COVERAGE_STALE,
                )
                statsRepository.load().allTimeHigh.shouldBeEqualComparingTo(BigDecimal("100000.00"))
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

        "pre-flow basis restores positive fiat residual when snapshot omits USD" {
            runTest {
                statsRepository.save(PortfolioStats(BigDecimal("100000.00"), BigDecimal.ZERO))
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
                                targetPercent = BigDecimal("50.0"),
                            ),
                        ),
                        actions = emptyList(),
                        drawdownPercent = BigDecimal.ZERO,
                        fiatDeploymentPercent = BigDecimal.ZERO,
                        effectiveUsdTargetPercent = BigDecimal("50.0"),
                    ),
                )
                ledgerRepository.saveLedgers(listOf(deposit("residual-fiat-flow", t70, "20000.00")))
                tradeRepository.setSyncMetadata(
                    SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC,
                    t60.epochSecond.toString(),
                )
                ledgerRepository.setSyncMetadata(
                    SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC,
                    t80.epochSecond.toString(),
                )

                val result = analyzer(t80).updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("120000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = t80,
                )

                result shouldBe AthUpdateResult.Trusted(BigDecimal.ZERO)
                statsRepository.load().allTimeHigh.shouldBeEqualComparingTo(BigDecimal("120000.00"))
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
                            refid = "WIRE-w-full",
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

                result shouldBe AthUpdateResult.Deferred(
                    BigDecimal("7.5000"),
                    AthTrustFailureReason.PRE_FLOW_BASIS_UNCERTAIN,
                )
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
                            refid = "WIRE-z-out",
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
                            ledgerId = "stk-skip",
                            time = t70,
                            type = KrakenApiConstants.LEDGER_TYPE_STAKING,
                            asset = "BTC",
                            amount = BigDecimal("0.5"),
                            fee = BigDecimal.ZERO,
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
                statsRepository.getAppliedAthFlowIds(listOf("stk-skip")) shouldBe setOf("stk-skip")

                val snap = PortfolioSnapshot(
                    timestamp = t71,
                    totalValueUSD = BigDecimal("100000.00"),
                    assets = mapOf(
                        "USD" to TestFixtures.assetSnapshot(
                            symbol = "USD",
                            balance = BigDecimal("100000.00"),
                            price = BigDecimal.ONE,
                            valueUSD = BigDecimal("100000.00"),
                            targetPercent = BigDecimal("100.0"),
                        ),
                    ),
                    actions = emptyList(),
                    drawdownPercent = BigDecimal.ZERO,
                    fiatDeploymentPercent = BigDecimal.ZERO,
                    effectiveUsdTargetPercent = BigDecimal.ZERO,
                )
                tradeRepository.saveSnapshot(snap)
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
                            refid = "FT-dep-sell",
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
                            subtype = "spotfromfutures",
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
                            refid = "FT-PAIR",
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
                            refid = "FT-PAIR",
                            time = t71,
                            type = KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL,
                            asset = "USD",
                            amount = BigDecimal("-1000.00"),
                        ),
                    ),
                )
                val second = analyzer(t90, FundingProvenanceResolver { FundingEvidence.INTERNAL })
                    .updateAthAndCalculateDrawdown(
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

        "unsupported ledger event defers initial ATH without journaling" {
            runTest {
                statsRepository.save(PortfolioStats(BigDecimal.ZERO, BigDecimal.ZERO))
                ledgerRepository.setSyncMetadata(
                    SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC,
                    t80.epochSecond.toString(),
                )
                ledgerRepository.saveLedgers(
                    listOf(
                        LedgerEvent(
                            ledgerId = "unsupported-initial",
                            time = t70,
                            type = "unknown-type",
                            asset = "USD",
                            amount = BigDecimal("1000.00"),
                        ),
                    ),
                )

                val result = analyzer(t80).updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("101000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = t80,
                )

                result shouldBe AthUpdateResult.Deferred(
                    BigDecimal("0.0000"),
                    AthTrustFailureReason.UNSUPPORTED_LEDGER_EVENT,
                )
                statsRepository.load().allTimeHigh.shouldBeEqualComparingTo(BigDecimal.ZERO)
                statsRepository.getAppliedAthFlowIds(listOf("unsupported-initial")) shouldBe emptySet()
            }
        }

        "ambiguous deposit blocks ATH update, defers fail-closed, and is not journaled" {
            runTest {
                statsRepository.save(PortfolioStats(BigDecimal("100000.00"), BigDecimal("10.0000")))
                tradeRepository.setSyncMetadata(
                    SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC,
                    t60.epochSecond.toString(),
                )
                ledgerRepository.setSyncMetadata(
                    SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC,
                    t80.epochSecond.toString(),
                )
                ledgerRepository.saveLedgers(
                    listOf(
                        LedgerEvent(
                            ledgerId = "bare-dep",
                            refid = "DEP-1",
                            time = t70,
                            type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                            asset = "USD",
                            amount = BigDecimal("10000.00"),
                            fee = BigDecimal.ZERO,
                        ),
                    ),
                )

                val result = analyzer(t80).updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("110000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = t80,
                )
                result shouldBe AthUpdateResult.Deferred(
                    BigDecimal("10.0000"),
                    AthTrustFailureReason.AMBIGUOUS_FUNDING,
                )
                statsRepository.load().allTimeHigh.shouldBeEqualComparingTo(BigDecimal("100000.00"))
                statsRepository.getAppliedAthFlowIds(listOf("bare-dep")) shouldBe emptySet()

                // Rerunning sees the exact same unresolved flow and defers again
                val rerun = analyzer(t80).updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("110000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = t80,
                )
                rerun shouldBe AthUpdateResult.Deferred(
                    BigDecimal("10.0000"),
                    AthTrustFailureReason.AMBIGUOUS_FUNDING,
                )
                statsRepository.getAppliedAthFlowIds(listOf("bare-dep")) shouldBe emptySet()
            }
        }

        "bare transfer blocks ATH update, defers fail-closed, and is not journaled" {
            runTest {
                statsRepository.save(PortfolioStats(BigDecimal("100000.00"), BigDecimal("10.0000")))
                tradeRepository.setSyncMetadata(
                    SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC,
                    t60.epochSecond.toString(),
                )
                ledgerRepository.setSyncMetadata(
                    SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC,
                    t80.epochSecond.toString(),
                )
                ledgerRepository.saveLedgers(
                    listOf(
                        LedgerEvent(
                            ledgerId = "bare-transfer",
                            time = t70,
                            type = KrakenApiConstants.LEDGER_TYPE_TRANSFER,
                            asset = "USD",
                            amount = BigDecimal("10000.00"),
                            fee = BigDecimal.ZERO,
                        ),
                    ),
                )

                val result = analyzer(t80).updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("110000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = t80,
                )
                result shouldBe AthUpdateResult.Deferred(
                    BigDecimal("10.0000"),
                    AthTrustFailureReason.AMBIGUOUS_FUNDING,
                )
                statsRepository.load().allTimeHigh.shouldBeEqualComparingTo(BigDecimal("100000.00"))
                statsRepository.getAppliedAthFlowIds(listOf("bare-transfer")) shouldBe emptySet()
            }
        }

        "funding provenance preparation failure defers ATH without journaling" {
            runTest {
                statsRepository.save(PortfolioStats(BigDecimal("100000.00"), BigDecimal("10.0000")))
                tradeRepository.setSyncMetadata(
                    SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC,
                    t60.epochSecond.toString(),
                )
                ledgerRepository.setSyncMetadata(
                    SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC,
                    t80.epochSecond.toString(),
                )
                ledgerRepository.saveLedgers(listOf(deposit("provenance-failure", t70, "10000.00")))

                val resolver = FundingProvenanceResolver.unavailable(
                    FundingProvenanceFailure(
                        reason = FundingProvenanceFailureReason.PERMISSION_DENIED,
                        message = "DepositStatus permission denied",
                    ),
                )
                val result = analyzer(t80, resolver).updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("110000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = t80,
                )

                result shouldBe AthUpdateResult.Deferred(
                    BigDecimal("10.0000"),
                    AthTrustFailureReason.FUNDING_PROVENANCE_UNAVAILABLE,
                )
                statsRepository.load().allTimeHigh.shouldBeEqualComparingTo(BigDecimal("100000.00"))
                statsRepository.getAppliedAthFlowIds(listOf("provenance-failure")) shouldBe emptySet()

                val throwingResolver = object : FundingProvenanceResolver {
                    override fun resolve(event: LedgerEvent): FundingEvidence = FundingEvidence.UNRESOLVED

                    override suspend fun prepare(events: Collection<LedgerEvent>): FundingProvenanceResolver =
                        throw RuntimeException()
                }
                val thrownResult = analyzer(t80, throwingResolver).updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("110000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = t80,
                )
                thrownResult shouldBe AthUpdateResult.Deferred(
                    BigDecimal("10.0000"),
                    AthTrustFailureReason.FUNDING_PROVENANCE_UNAVAILABLE,
                )
            }
        }

        "unsupported ledger event defers an established ATH without journaling" {
            runTest {
                statsRepository.save(PortfolioStats(BigDecimal("100000.00"), BigDecimal("10.0000")))
                tradeRepository.setSyncMetadata(
                    SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC,
                    t60.epochSecond.toString(),
                )
                ledgerRepository.setSyncMetadata(
                    SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC,
                    t80.epochSecond.toString(),
                )
                ledgerRepository.saveLedgers(
                    listOf(
                        LedgerEvent(
                            ledgerId = "unsupported-established",
                            time = t70,
                            type = "unknown-type",
                            asset = "USD",
                            amount = BigDecimal("1000.00"),
                        ),
                    ),
                )

                val result = analyzer(t80).updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("101000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = t80,
                )

                result shouldBe AthUpdateResult.Deferred(
                    BigDecimal("10.0000"),
                    AthTrustFailureReason.UNSUPPORTED_LEDGER_EVENT,
                )
                statsRepository.load().allTimeHigh.shouldBeEqualComparingTo(BigDecimal("100000.00"))
                statsRepository.getAppliedAthFlowIds(listOf("unsupported-established")) shouldBe emptySet()
            }
        }

        "ambiguous withdrawal blocks ATH update, defers fail-closed, and is not journaled" {
            runTest {
                statsRepository.save(PortfolioStats(BigDecimal("100000.00"), BigDecimal("5.0000")))
                tradeRepository.setSyncMetadata(
                    SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC,
                    t60.epochSecond.toString(),
                )
                ledgerRepository.setSyncMetadata(
                    SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC,
                    t80.epochSecond.toString(),
                )
                ledgerRepository.saveLedgers(
                    listOf(
                        LedgerEvent(
                            ledgerId = "bare-wdr",
                            refid = "WDR-1",
                            time = t70,
                            type = KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL,
                            asset = "USD",
                            amount = BigDecimal("-10000.00"),
                            fee = BigDecimal.ZERO,
                        ),
                    ),
                )

                val result = analyzer(t80).updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("90000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = t80,
                )
                result shouldBe AthUpdateResult.Deferred(
                    BigDecimal("5.0000"),
                    AthTrustFailureReason.AMBIGUOUS_FUNDING,
                )
                statsRepository.load().allTimeHigh.shouldBeEqualComparingTo(BigDecimal("100000.00"))
                statsRepository.getAppliedAthFlowIds(listOf("bare-wdr")) shouldBe emptySet()
            }
        }

        "staking dividend remains performance and is terminal and journaled" {
            runTest {
                statsRepository.save(PortfolioStats(BigDecimal("100000.00"), BigDecimal.ZERO))
                tradeRepository.setSyncMetadata(
                    SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC,
                    t60.epochSecond.toString(),
                )
                ledgerRepository.setSyncMetadata(
                    SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC,
                    t80.epochSecond.toString(),
                )
                ledgerRepository.saveLedgers(
                    listOf(
                        LedgerEvent(
                            ledgerId = "stk-1",
                            refid = "STK-1",
                            time = t70,
                            type = KrakenApiConstants.LEDGER_TYPE_STAKING,
                            asset = "ETH",
                            amount = BigDecimal("0.5"),
                            fee = BigDecimal.ZERO,
                        ),
                    ),
                )

                val result = analyzer(t80).updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("100000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = t80,
                )
                (result as AthUpdateResult.Trusted).drawdownPct.shouldBeEqualComparingTo(BigDecimal.ZERO)
                statsRepository.load().allTimeHigh.shouldBeEqualComparingTo(BigDecimal("100000.00"))
                statsRepository.getAppliedAthFlowIds(listOf("stk-1")) shouldBe setOf("stk-1")
            }
        }

        "reclassifying an ambiguous event later allows ATH processing exactly once" {
            runTest {
                statsRepository.save(PortfolioStats(BigDecimal("100000.00"), BigDecimal.ZERO))
                tradeRepository.saveSnapshot(TestFixtures.emptySnapshot(t60, BigDecimal("80000.00")))
                tradeRepository.setSyncMetadata(
                    SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC,
                    t60.epochSecond.toString(),
                )
                ledgerRepository.setSyncMetadata(
                    SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC,
                    t80.epochSecond.toString(),
                )
                ledgerRepository.saveLedgers(
                    listOf(
                        LedgerEvent(
                            ledgerId = "dep-resolve",
                            refid = "AMB-1",
                            time = t70,
                            type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                            asset = "USD",
                            amount = BigDecimal("20000.00"),
                            fee = BigDecimal.ZERO,
                        ),
                    ),
                )

                // Ambiguous deposit defers
                val deferred = analyzer(t80).updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("100000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = t80,
                )
                requireNotNull((deferred as AthUpdateResult.Deferred).lastTrustedDrawdownPct)
                    .shouldBeEqualComparingTo(BigDecimal.ZERO)
                statsRepository.load().allTimeHigh.shouldBeEqualComparingTo(BigDecimal("100000.00"))
                statsRepository.getAppliedAthFlowIds(listOf("dep-resolve")) shouldBe emptySet()

                // Later update replaces row with affirmative banking refid
                transaction(db) {
                    LedgerTable.update({ LedgerTable.ledgerId eq "dep-resolve" }) {
                        it[refid] = "FT-RESOLVED-1"
                    }
                }

                val trusted = analyzer(t80).updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("100000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = t80,
                )
                (trusted as AthUpdateResult.Trusted).drawdownPct.shouldBeEqualComparingTo(BigDecimal("20.0000"))
                // 100k * 100/80 = 125k; 100k total against it is a 20% drawdown
                statsRepository.load().allTimeHigh.shouldBeEqualComparingTo(BigDecimal("125000.00"))
                statsRepository.getAppliedAthFlowIds(listOf("dep-resolve")) shouldBe setOf("dep-resolve")

                // Subsequent run does not re-apply the resolved deposit
                val replay = analyzer(t80).updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("100000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = t80,
                )
                (replay as AthUpdateResult.Trusted).drawdownPct.shouldBeEqualComparingTo(BigDecimal("20.0000"))
                statsRepository.load().allTimeHigh.shouldBeEqualComparingTo(BigDecimal("125000.00"))
            }
        }

        "pre-flow basis accounts for market price movement: snapshot BTC 80k, flow-time BTC 88k, deposit 20k" {
            runTest {
                statsRepository.save(PortfolioStats(BigDecimal("100000.00"), BigDecimal.ZERO))
                tradeRepository.setSyncMetadata(
                    SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC,
                    t0.epochSecond.toString(),
                )
                // Predecessor snapshot: 1 BTC at $80k, total 80k
                tradeRepository.saveSnapshot(
                    PortfolioSnapshot(
                        timestamp = t0,
                        totalValueUSD = BigDecimal("80000.00"),
                        assets = mapOf(
                            "BTC" to TestFixtures.assetSnapshot(
                                symbol = "BTC",
                                balance = BigDecimal.ONE,
                                price = BigDecimal("80000.00"),
                                valueUSD = BigDecimal("80000.00"),
                                targetPercent = BigDecimal("50"),
                            ),
                            "USD" to TestFixtures.assetSnapshot(
                                symbol = "USD",
                                balance = BigDecimal.ZERO,
                                price = BigDecimal.ONE,
                                valueUSD = BigDecimal.ZERO,
                                targetPercent = BigDecimal("50"),
                            ),
                        ),
                        actions = emptyList(),
                        drawdownPercent = BigDecimal.ZERO,
                        fiatDeploymentPercent = BigDecimal.ZERO,
                        effectiveUsdTargetPercent = BigDecimal.ZERO,
                    ),
                )

                // Recent trade right before flow establishes 88k flow-time execution price
                tradeRepository.saveTrade(
                    TestFixtures.tradeRecord(
                        timestamp = t70.minusSeconds(1),
                        pair = "USDBTC",
                        side = "buy",
                        symbol = "BTC",
                        volume = BigDecimal("0.1"),
                        usdAmount = BigDecimal("8800.00"),
                        fee = BigDecimal.ZERO,
                    ),
                )

                ledgerRepository.saveLedgers(listOf(deposit("dep-88k", t70, "20000.00")))
                ledgerRepository.setSyncMetadata(
                    SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC,
                    t80.epochSecond.toString(),
                )

                // Before deposit, holdings = 1.1 BTC, USD = -8800.
                // At 88k execution price: 1.1 * 88,000 - 8,800 = 88,000 basis (not 80,000)!
                // ATH adjustment: 100,000 * (88,000 + 20,000) / 88,000 = 100,000 * 108,000 / 88,000 = 122727.27.
                val result = analyzer(t80).updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("108000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = t80,
                )
                (result as AthUpdateResult.Trusted).drawdownPct.shouldBeEqualComparingTo(BigDecimal("12.0000"))
                statsRepository.load().allTimeHigh.shouldBeEqualComparingTo(BigDecimal("122727.27"))
                statsRepository.getAppliedAthFlowIds(listOf("dep-88k")) shouldBe setOf("dep-88k")
            }
        }

        "ETH and BTC mixed portfolio movement properly reconstructs pre-flow basis" {
            runTest {
                every { configService.getConfig() } returns TestFixtures.config(
                    settings = TestFixtures.settings(),
                    allocations = listOf(
                        Allocation(Asset.BTC, 50.0),
                        Allocation(Asset.ETH, 30.0),
                        Allocation(Asset.USD, 20.0),
                    ),
                )
                statsRepository.save(PortfolioStats(BigDecimal("150000.00"), BigDecimal.ZERO))
                tradeRepository.setSyncMetadata(
                    SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC,
                    t60.epochSecond.toString(),
                )
                // Predecessor snapshot at t60: BTC at 50k (1 BTC = 50k), ETH at 3k (10 ETH = 30k), USD = 20k. Total = 100k
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
                                balance = BigDecimal("10"),
                                price = BigDecimal("3000.00"),
                                valueUSD = BigDecimal("30000.00"),
                                targetPercent = BigDecimal("30"),
                            ),
                            "USD" to TestFixtures.assetSnapshot(
                                symbol = "USD",
                                balance = BigDecimal("20000.00"),
                                price = BigDecimal.ONE,
                                valueUSD = BigDecimal("20000.00"),
                                targetPercent = BigDecimal("20"),
                            ),
                        ),
                        actions = emptyList(),
                        drawdownPercent = BigDecimal.ZERO,
                        fiatDeploymentPercent = BigDecimal.ZERO,
                        effectiveUsdTargetPercent = BigDecimal.ZERO,
                    ),
                )

                // Snapshot at flow time t70 records market price movement: BTC 60k, ETH 3.5k
                tradeRepository.saveSnapshot(
                    PortfolioSnapshot(
                        timestamp = t70,
                        totalValueUSD = BigDecimal("115000.00"),
                        assets = mapOf(
                            "BTC" to TestFixtures.assetSnapshot(
                                symbol = "BTC",
                                balance = BigDecimal.ONE,
                                price = BigDecimal("60000.00"),
                                valueUSD = BigDecimal("60000.00"),
                                targetPercent = BigDecimal("50"),
                            ),
                            "ETH" to TestFixtures.assetSnapshot(
                                symbol = "ETH",
                                balance = BigDecimal("10"),
                                price = BigDecimal("3500.00"),
                                valueUSD = BigDecimal("35000.00"),
                                targetPercent = BigDecimal("30"),
                            ),
                            "USD" to TestFixtures.assetSnapshot(
                                symbol = "USD",
                                balance = BigDecimal("20000.00"),
                                price = BigDecimal.ONE,
                                valueUSD = BigDecimal("20000.00"),
                                targetPercent = BigDecimal("20"),
                            ),
                        ),
                        actions = emptyList(),
                        drawdownPercent = BigDecimal.ZERO,
                        fiatDeploymentPercent = BigDecimal.ZERO,
                        effectiveUsdTargetPercent = BigDecimal.ZERO,
                    ),
                )

                ledgerRepository.saveLedgers(listOf(deposit("dep-mix", t70, "15000.00")))
                ledgerRepository.setSyncMetadata(
                    SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC,
                    t80.epochSecond.toString(),
                )

                // Basis valued at flow-time prices = 1 * 60k + 10 * 3.5k + 20k = 115k.
                // Scaled ATH = 150k * (115k + 15k) / 115k = 150k * 130k / 115k = 169565.22.
                val result = analyzer(t80).updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("130000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = t80,
                )
                (result as AthUpdateResult.Trusted).drawdownPct.shouldBeEqualComparingTo(BigDecimal("23.3333"))
                statsRepository.load().allTimeHigh.shouldBeEqualComparingTo(BigDecimal("169565.22"))
                statsRepository.getAppliedAthFlowIds(listOf("dep-mix")) shouldBe setOf("dep-mix")
            }
        }

        "stale predecessor gap exceeding 7 days defers ATH trust fail-closed" {
            runTest {
                statsRepository.save(PortfolioStats(BigDecimal("100000.00"), BigDecimal.ZERO))
                tradeRepository.saveSnapshot(TestFixtures.emptySnapshot(t0, BigDecimal("80000.00")))
                tradeRepository.setSyncMetadata(
                    SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC,
                    t0.epochSecond.toString(),
                )
                val flowTime = t0.plusSeconds(8L * 86400L) // 8 days later > 7 day limit
                val horizonTime = flowTime.plusSeconds(60)
                ledgerRepository.setSyncMetadata(
                    SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC,
                    horizonTime.epochSecond.toString(),
                )
                ledgerRepository.saveLedgers(listOf(deposit("dep-stale-gap", flowTime, "10000.00")))

                val result = analyzer(horizonTime).updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("90000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = horizonTime,
                )
                requireNotNull((result as AthUpdateResult.Deferred).lastTrustedDrawdownPct)
                    .shouldBeEqualComparingTo(BigDecimal.ZERO)
                statsRepository.load().allTimeHigh.shouldBeEqualComparingTo(BigDecimal("100000.00"))
                statsRepository.getAppliedAthFlowIds(listOf("dep-stale-gap")) shouldBe emptySet()
            }
        }

        "missing flow-time price defers ATH trust fail-closed" {
            runTest {
                statsRepository.save(PortfolioStats(BigDecimal("100000.00"), BigDecimal.ZERO))
                val snapTime = t0
                val flowTime = t0.plusSeconds(48L * 3600L)
                val horizonTime = flowTime.plusSeconds(60)
                tradeRepository.setSyncMetadata(
                    SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC,
                    snapTime.epochSecond.toString(),
                )
                tradeRepository.saveSnapshot(
                    PortfolioSnapshot(
                        timestamp = snapTime,
                        totalValueUSD = BigDecimal("50000.00"),
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
                ledgerRepository.setSyncMetadata(
                    SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC,
                    horizonTime.epochSecond.toString(),
                )
                ledgerRepository.saveLedgers(listOf(deposit("dep-noprice", flowTime, "10000.00")))

                val result = analyzer(horizonTime).updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("60000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = horizonTime,
                )
                requireNotNull((result as AthUpdateResult.Deferred).lastTrustedDrawdownPct)
                    .shouldBeEqualComparingTo(BigDecimal.ZERO)
                statsRepository.load().allTimeHigh.shouldBeEqualComparingTo(BigDecimal("100000.00"))
                statsRepository.getAppliedAthFlowIds(listOf("dep-noprice")) shouldBe emptySet()
            }
        }

        "event-time basis correctly accounts for intervening sell trades and intervening prior flows" {
            runTest {
                statsRepository.save(PortfolioStats(BigDecimal("300000.00"), BigDecimal.ZERO))
                val snapTime = t0
                tradeRepository.saveSnapshot(
                    PortfolioSnapshot(
                        timestamp = snapTime,
                        totalValueUSD = BigDecimal("100000.00"),
                        assets = mapOf(
                            "BTC" to TestFixtures.assetSnapshot(
                                symbol = "BTC",
                                balance = BigDecimal.ONE,
                                price = BigDecimal("80000.00"),
                                valueUSD = BigDecimal("80000.00"),
                                targetPercent = BigDecimal("50"),
                            ),
                            "USD" to TestFixtures.assetSnapshot(
                                symbol = "USD",
                                balance = BigDecimal("20000.00"),
                                price = BigDecimal.ONE,
                                valueUSD = BigDecimal("20000.00"),
                                targetPercent = BigDecimal("50"),
                            ),
                        ),
                        actions = emptyList(),
                        drawdownPercent = BigDecimal.ZERO,
                        fiatDeploymentPercent = BigDecimal.ZERO,
                        effectiveUsdTargetPercent = BigDecimal.ZERO,
                    ),
                )
                tradeRepository.setSyncMetadata(
                    SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC,
                    snapTime.epochSecond.toString(),
                )

                // Trade at snapTime exactly: ignored by trade.timestamp.isAfter(predecessor.timestamp)
                val tradeAtSnap = TestFixtures.tradeRecord(
                    timestamp = snapTime,
                    pair = "XBTUSD",
                    side = "buy",
                    symbol = "BTC",
                    volume = BigDecimal("0.1"),
                    usdAmount = BigDecimal("8000.00"),
                    fee = BigDecimal("5.00"),
                )

                // Intervening SELL trade of tracked asset BTC
                val tradeSellBtc = TestFixtures.tradeRecord(
                    timestamp = snapTime.plusSeconds(1800),
                    pair = "XBTUSD",
                    side = "sell",
                    symbol = "BTC",
                    volume = BigDecimal("0.2"),
                    usdAmount = BigDecimal("16000.00"),
                    fee = BigDecimal("10.00"),
                )

                // Intervening SELL trade of untracked asset SOL
                val tradeSellSol = TestFixtures.tradeRecord(
                    timestamp = snapTime.plusSeconds(2000),
                    pair = "SOLUSD",
                    side = "sell",
                    symbol = "SOL",
                    volume = BigDecimal("10.0"),
                    usdAmount = BigDecimal("1000.00"),
                    fee = BigDecimal("5.00"),
                )

                // Trade at flow1 to establish BTC price
                val tradePrice1 = TestFixtures.tradeRecord(
                    timestamp = snapTime.plusSeconds(3595),
                    pair = "XBTUSD",
                    side = "buy",
                    symbol = "BTC",
                    volume = BigDecimal("0.01"),
                    usdAmount = BigDecimal("800.00"),
                    price = BigDecimal("80000.00"),
                    fee = BigDecimal("1.00"),
                )

                // Trade at flow3 to establish BTC price
                val tradePrice3 = TestFixtures.tradeRecord(
                    timestamp = snapTime.plusSeconds(7195),
                    pair = "XBTUSD",
                    side = "buy",
                    symbol = "BTC",
                    volume = BigDecimal("0.01"),
                    usdAmount = BigDecimal("800.00"),
                    price = BigDecimal("80000.00"),
                    fee = BigDecimal("1.00"),
                )

                // Trade at flow4 to establish BTC price
                val tradePrice4 = TestFixtures.tradeRecord(
                    timestamp = snapTime.plusSeconds(8995),
                    pair = "XBTUSD",
                    side = "buy",
                    symbol = "BTC",
                    volume = BigDecimal("0.01"),
                    usdAmount = BigDecimal("800.00"),
                    price = BigDecimal("80000.00"),
                    fee = BigDecimal("1.00"),
                )

                listOf(tradeAtSnap, tradeSellBtc, tradeSellSol, tradePrice1, tradePrice3, tradePrice4)
                    .forEach { tradeRepository.saveTrade(it) }

                // Ledger flow 0 at snapTime exactly: ignored by event.time.isAfter(predecessor.timestamp)
                val flow0 = deposit("f0", snapTime, "100.00")
                // Ledger flow 1: BTC deposit at snap + 3600
                val txHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
                val flow1 = LedgerEvent(
                    ledgerId = "f1",
                    refid = txHash,
                    time = snapTime.plusSeconds(3600),
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    asset = "BTC",
                    amount = BigDecimal("0.1"),
                    fee = BigDecimal.ZERO,
                )
                // Ledger flow 2: SOL deposit at snap + 5400 (untracked crypto, fee > 0 -> EXTERNAL_BALANCE)
                val flow2 = LedgerEvent(
                    ledgerId = "f2",
                    refid = null,
                    time = snapTime.plusSeconds(5400),
                    type = KrakenApiConstants.LEDGER_TYPE_STAKING,
                    asset = "SOL",
                    amount = BigDecimal("5.0"),
                    fee = BigDecimal.ZERO,
                )
                // Ledger flow 3: USD deposit at snap + 7200
                val flow3 = deposit("f3", snapTime.plusSeconds(7200), "5000.00")
                // Ledger flow 4: USD deposit at snap + 9000
                val flow4 = deposit("f4", snapTime.plusSeconds(9000), "10000.00")

                val horizonTime = snapTime.plusSeconds(9060)
                ledgerRepository.setSyncMetadata(
                    SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC,
                    horizonTime.epochSecond.toString(),
                )
                ledgerRepository.saveLedgers(listOf(flow0, flow1, flow2, flow3, flow4))

                val result = analyzer(horizonTime).updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("200000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = horizonTime,
                )
                (result as AthUpdateResult.Trusted).drawdownPct.shouldBeGreaterThan(BigDecimal.ZERO)
                statsRepository.getAppliedAthFlowIds(listOf("f0", "f1", "f2", "f3", "f4")) shouldBe
                    setOf("f0", "f1", "f2", "f3", "f4")
            }
        }

        "pre-flow basis includes intervening staking rewards before flow valuation" {
            runTest {
                statsRepository.save(PortfolioStats(BigDecimal("100000.00"), BigDecimal.ZERO))
                tradeRepository.setSyncMetadata(
                    SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC,
                    t60.epochSecond.toString(),
                )
                ledgerRepository.setSyncMetadata(
                    SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC,
                    t80.epochSecond.toString(),
                )

                val snap60 = PortfolioSnapshot(
                    timestamp = t60,
                    totalValueUSD = BigDecimal("100000.00"),
                    assets = mapOf(
                        "BTC" to TestFixtures.assetSnapshot(
                            symbol = "BTC",
                            balance = BigDecimal("1.00000000"),
                            price = BigDecimal("50000.00"),
                            valueUSD = BigDecimal("50000.00"),
                            targetPercent = BigDecimal("50.0"),
                        ),
                        "USD" to TestFixtures.assetSnapshot(
                            symbol = "USD",
                            balance = BigDecimal("50000.00"),
                            price = BigDecimal.ONE,
                            valueUSD = BigDecimal("50000.00"),
                            targetPercent = BigDecimal("50.0"),
                        ),
                    ),
                    actions = emptyList(),
                    drawdownPercent = BigDecimal.ZERO,
                    fiatDeploymentPercent = BigDecimal.ZERO,
                    effectiveUsdTargetPercent = BigDecimal("50.0"),
                    balancesObservedAt = t60,
                )
                tradeRepository.saveSnapshot(snap60)

                val stakingEvent = LedgerEvent(
                    ledgerId = "stk-btc-1",
                    refid = "STK-1",
                    time = t0.plusSeconds(65),
                    type = KrakenApiConstants.LEDGER_TYPE_STAKING,
                    asset = "BTC",
                    amount = BigDecimal("0.20000000"),
                    fee = BigDecimal.ZERO,
                )

                val depositEvent = deposit("dep-usd-1", t70, "10000.00", refid = "FT-CONFIRMED-1")

                tradeRepository.saveSnapshot(
                    snap60.copy(
                        timestamp = t71,
                        balancesObservedAt = t71,
                    ),
                )

                ledgerRepository.saveLedgers(listOf(stakingEvent, depositEvent))

                val expectedAth = BigDecimal("100000.00").multiply(BigDecimal("120000.00"))
                    .divide(BigDecimal("110000.00"), 2, java.math.RoundingMode.HALF_UP)

                val result = analyzer(t80).updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = expectedAth,
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = t80,
                )
                (result as AthUpdateResult.Trusted).drawdownPct.shouldBeEqualComparingTo(BigDecimal.ZERO)
                statsRepository.load().allTimeHigh.shouldBeEqualComparingTo(expectedAth)
            }
        }

        "same-instant reward and owner deposit defer instead of inventing an order" {
            runTest {
                statsRepository.save(PortfolioStats(BigDecimal("100000.00"), BigDecimal.ZERO))
                tradeRepository.setSyncMetadata(
                    SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC,
                    t60.epochSecond.toString(),
                )
                ledgerRepository.setSyncMetadata(
                    SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC,
                    t90.epochSecond.toString(),
                )
                tradeRepository.saveSnapshot(
                    PortfolioSnapshot(
                        timestamp = t71,
                        totalValueUSD = BigDecimal("100000.00"),
                        assets = mapOf(
                            "BTC" to TestFixtures.assetSnapshot(
                                symbol = "BTC",
                                balance = BigDecimal("1.00000000"),
                                price = BigDecimal("50000.00"),
                                valueUSD = BigDecimal("50000.00"),
                                targetPercent = BigDecimal("50.0"),
                            ),
                            Asset.USD to TestFixtures.assetSnapshot(
                                symbol = Asset.USD,
                                balance = BigDecimal("50000.00"),
                                price = BigDecimal.ONE,
                                valueUSD = BigDecimal("50000.00"),
                                targetPercent = BigDecimal("50.0"),
                            ),
                        ),
                        actions = emptyList(),
                        drawdownPercent = BigDecimal.ZERO,
                        fiatDeploymentPercent = BigDecimal.ZERO,
                        effectiveUsdTargetPercent = BigDecimal("50.0"),
                        balancesObservedAt = t71,
                    ),
                )
                ledgerRepository.saveLedgers(
                    listOf(
                        LedgerEvent(
                            ledgerId = "same-time-reward",
                            refid = "REWARD-SAME-TIME",
                            time = t80,
                            type = KrakenApiConstants.LEDGER_TYPE_STAKING,
                            asset = "BTC",
                            amount = BigDecimal("0.20000000"),
                        ),
                        deposit("same-time-owner", t80, "10000.00"),
                    ),
                )

                val result = analyzer(t90).updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("100000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = t90,
                )

                result shouldBe AthUpdateResult.Deferred(
                    BigDecimal("0.0000"),
                    AthTrustFailureReason.EVENT_ORDERING_UNCERTAIN,
                )
                statsRepository.load().allTimeHigh.shouldBeEqualComparingTo(BigDecimal("100000.00"))
                statsRepository.getAppliedAthFlowIds(listOf("same-time-reward", "same-time-owner")) shouldBe emptySet()
            }
        }

        "pre-flow replay uses the observation boundary and replays later rows" {
            runTest {
                val boundaryReward = LedgerEvent(
                    ledgerId = "boundary-reward",
                    refid = "REWARD-BOUNDARY",
                    time = t0.plusSeconds(65),
                    type = KrakenApiConstants.LEDGER_TYPE_STAKING,
                    asset = "BTC",
                    amount = BigDecimal("0.20000000"),
                    balance = BigDecimal("1.20000000"),
                    hasAuthoritativeBalance = true,
                )
                val laterReward = LedgerEvent(
                    ledgerId = "post-snapshot-reward",
                    refid = "REWARD-AFTER-SNAPSHOT",
                    time = t75,
                    type = KrakenApiConstants.LEDGER_TYPE_STAKING,
                    asset = "BTC",
                    amount = BigDecimal("0.10000000"),
                )
                seedObservationBoundaryCase(
                    predecessorBtc = "1.00000000",
                    predecessorTotal = "100000.00",
                    boundaryReward = boundaryReward,
                    additionalEvents = listOf(laterReward),
                )

                val result = analyzer(t90).updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("100000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = t90,
                )

                (result as AthUpdateResult.Trusted).drawdownPct.shouldBeEqualComparingTo(BigDecimal("8.0000"))
                statsRepository.load().allTimeHigh.shouldBeEqualComparingTo(BigDecimal("108695.65"))
                statsRepository.getAppliedAthFlowIds(
                    listOf("boundary-reward", "post-snapshot-reward", "boundary-owner"),
                ) shouldBe setOf("boundary-reward", "post-snapshot-reward", "boundary-owner")
            }
        }

        "embedded boundary rows are not double-counted" {
            runTest {
                val boundaryReward = LedgerEvent(
                    ledgerId = "embedded-reward",
                    refid = "REWARD-EMBEDDED",
                    time = t0.plusSeconds(65),
                    type = KrakenApiConstants.LEDGER_TYPE_STAKING,
                    asset = "BTC",
                    amount = BigDecimal("0.20000000"),
                    balance = BigDecimal("1.20000000"),
                    hasAuthoritativeBalance = true,
                )
                seedObservationBoundaryCase(
                    predecessorBtc = "1.20000000",
                    predecessorTotal = "110000.00",
                    boundaryReward = boundaryReward,
                )

                val result = analyzer(t90).updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("100000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = t90,
                )

                (result as AthUpdateResult.Trusted).drawdownPct.shouldBeEqualComparingTo(BigDecimal("8.3333"))
                statsRepository.load().allTimeHigh.shouldBeEqualComparingTo(BigDecimal("109090.91"))
            }
        }

        "ambiguous observation-boundary embedding defers without journaling" {
            runTest {
                val boundaryReward = LedgerEvent(
                    ledgerId = "ambiguous-reward",
                    refid = "REWARD-AMBIGUOUS",
                    time = t0.plusSeconds(65),
                    type = KrakenApiConstants.LEDGER_TYPE_STAKING,
                    asset = "BTC",
                    amount = BigDecimal("0.20000000"),
                    balance = BigDecimal("1.20000000"),
                    hasAuthoritativeBalance = true,
                )
                seedObservationBoundaryCase(
                    predecessorBtc = "1.10000000",
                    predecessorTotal = "105000.00",
                    boundaryReward = boundaryReward,
                )

                val result = analyzer(t90).updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("100000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = t90,
                )

                result shouldBe AthUpdateResult.Deferred(
                    BigDecimal("0.0000"),
                    AthTrustFailureReason.BALANCE_OBSERVATION_UNCERTAIN,
                )
                statsRepository.load().allTimeHigh.shouldBeEqualComparingTo(BigDecimal("100000.00"))
                statsRepository.getAppliedAthFlowIds(listOf("ambiguous-reward", "boundary-owner")) shouldBe emptySet()
            }
        }

        "inconsistent observation-boundary ledger chain defers without journaling" {
            runTest {
                statsRepository.save(PortfolioStats(BigDecimal("100000.00"), BigDecimal.ZERO))
                tradeRepository.saveSnapshot(
                    PortfolioSnapshot(
                        timestamp = t71,
                        totalValueUSD = BigDecimal("100000.00"),
                        assets = mapOf(
                            "BTC" to TestFixtures.assetSnapshot(
                                symbol = "BTC",
                                balance = BigDecimal("1.00000000"),
                                price = BigDecimal("50000.00"),
                                valueUSD = BigDecimal("50000.00"),
                                targetPercent = BigDecimal("50.0"),
                            ),
                            Asset.USD to TestFixtures.assetSnapshot(
                                symbol = Asset.USD,
                                balance = BigDecimal("50000.00"),
                                price = BigDecimal.ONE,
                                valueUSD = BigDecimal("50000.00"),
                                targetPercent = BigDecimal("50.0"),
                            ),
                        ),
                        actions = emptyList(),
                        drawdownPercent = BigDecimal.ZERO,
                        fiatDeploymentPercent = BigDecimal.ZERO,
                        effectiveUsdTargetPercent = BigDecimal("50.0"),
                        balancesObservedAt = t60,
                    ),
                )
                ledgerRepository.saveLedgers(
                    listOf(
                        LedgerEvent(
                            ledgerId = "chain-reward-1",
                            refid = "CHAIN-1",
                            time = t60.plusSeconds(1),
                            type = KrakenApiConstants.LEDGER_TYPE_STAKING,
                            asset = "BTC",
                            amount = BigDecimal("0.20000000"),
                            balance = BigDecimal("1.20000000"),
                            hasAuthoritativeBalance = true,
                        ),
                        LedgerEvent(
                            ledgerId = "chain-reward-2",
                            refid = "CHAIN-2",
                            time = t60.plusSeconds(2),
                            type = KrakenApiConstants.LEDGER_TYPE_STAKING,
                            asset = "BTC",
                            amount = BigDecimal("0.10000000"),
                            // The second row should be 1.30000000; the
                            // malformed authoritative chain must not become
                            // a trusted pre-flow basis.
                            balance = BigDecimal("1.40000000"),
                            hasAuthoritativeBalance = true,
                        ),
                        deposit("chain-owner", t80, "10000.00"),
                    ),
                )
                tradeRepository.setSyncMetadata(
                    SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC,
                    t60.epochSecond.toString(),
                )
                ledgerRepository.setSyncMetadata(
                    SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC,
                    t90.epochSecond.toString(),
                )

                val result = analyzer(t90).updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("110000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = t90,
                )

                result shouldBe AthUpdateResult.Deferred(
                    BigDecimal("0.0000"),
                    AthTrustFailureReason.BALANCE_OBSERVATION_UNCERTAIN,
                )
                statsRepository.load().allTimeHigh.shouldBeEqualComparingTo(BigDecimal("100000.00"))
                statsRepository.getAppliedAthFlowIds(listOf("chain-owner")) shouldBe emptySet()
            }
        }

        "a trade inside the observation boundary defers without journaling" {
            runTest {
                val beforeObservationReward = LedgerEvent(
                    ledgerId = "trade-boundary-reward",
                    refid = "REWARD-TRADE-BOUNDARY",
                    time = t0.plusSeconds(55),
                    type = KrakenApiConstants.LEDGER_TYPE_STAKING,
                    asset = "BTC",
                    amount = BigDecimal("0.20000000"),
                    balance = BigDecimal("1.20000000"),
                    hasAuthoritativeBalance = true,
                )
                seedObservationBoundaryCase(
                    predecessorBtc = "1.20000000",
                    predecessorTotal = "110000.00",
                    boundaryReward = beforeObservationReward,
                )
                tradeRepository.saveTrade(
                    TestFixtures.tradeRecord(
                        timestamp = t60.plusSeconds(5),
                        pair = "USDBTC",
                        side = "buy",
                        symbol = "BTC",
                        volume = BigDecimal("0.10000000"),
                        usdAmount = BigDecimal("5000.00"),
                    ),
                )

                val result = analyzer(t90).updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("120000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = t90,
                )

                result shouldBe AthUpdateResult.Deferred(
                    BigDecimal("0.0000"),
                    AthTrustFailureReason.BALANCE_OBSERVATION_UNCERTAIN,
                )
                statsRepository.load().allTimeHigh.shouldBeEqualComparingTo(BigDecimal("100000.00"))
                statsRepository.getAppliedAthFlowIds(listOf("boundary-owner")) shouldBe emptySet()
            }
        }

        "events before the observation boundary are not replayed" {
            runTest {
                val beforeObservationReward = LedgerEvent(
                    ledgerId = "before-observation-reward",
                    refid = "REWARD-BEFORE-OBSERVATION",
                    time = t0.plusSeconds(55),
                    type = KrakenApiConstants.LEDGER_TYPE_STAKING,
                    asset = "BTC",
                    amount = BigDecimal("0.20000000"),
                    balance = BigDecimal("1.20000000"),
                    hasAuthoritativeBalance = true,
                )
                seedObservationBoundaryCase(
                    predecessorBtc = "1.20000000",
                    predecessorTotal = "110000.00",
                    boundaryReward = beforeObservationReward,
                )

                val result = analyzer(t90).updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("100000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = t90,
                )

                (result as AthUpdateResult.Trusted).drawdownPct.shouldBeEqualComparingTo(BigDecimal("8.3333"))
                statsRepository.load().allTimeHigh.shouldBeEqualComparingTo(BigDecimal("109090.91"))
            }
        }

        "historical flow older than 300s without historical price fails closed and defers" {
            runTest {
                statsRepository.save(PortfolioStats(BigDecimal("100000.00"), BigDecimal("5.0000")))
                val flowTime = t0.plusSeconds(70)
                val obsTime = flowTime.plusSeconds(301)

                tradeRepository.setSyncMetadata(
                    SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC,
                    t60.epochSecond.toString(),
                )
                ledgerRepository.setSyncMetadata(
                    SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC,
                    obsTime.epochSecond.toString(),
                )

                tradeRepository.saveSnapshot(TestFixtures.emptySnapshot(t60, BigDecimal("80000.00")))

                val btcDeposit = LedgerEvent(
                    ledgerId = "dep-stale-btc",
                    refid = "FT-BTC-STALE",
                    time = flowTime,
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    asset = "BTC",
                    amount = BigDecimal("0.5"),
                    fee = BigDecimal.ZERO,
                )
                ledgerRepository.saveLedgers(listOf(btcDeposit))

                val result = analyzer(obsTime).updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("120000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = obsTime,
                )
                result shouldBe AthUpdateResult.Deferred(
                    BigDecimal("5.0000"),
                    AthTrustFailureReason.HISTORICAL_PRICE_UNAVAILABLE,
                )
                statsRepository.load().allTimeHigh.shouldBeEqualComparingTo(BigDecimal("100000.00"))
                statsRepository.getAppliedAthFlowIds(listOf("dep-stale-btc")) shouldBe emptySet()
            }
        }

        "near-real-time crypto deposit within 300s can use live ticker fallback" {
            runTest {
                statsRepository.save(PortfolioStats(BigDecimal("100000.00"), BigDecimal.ZERO))
                val flowTime = t0.plusSeconds(70)
                val obsTime = flowTime.plusSeconds(250)

                tradeRepository.setSyncMetadata(
                    SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC,
                    t60.epochSecond.toString(),
                )
                ledgerRepository.setSyncMetadata(
                    SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC,
                    obsTime.epochSecond.toString(),
                )

                tradeRepository.saveSnapshot(TestFixtures.emptySnapshot(t60, BigDecimal("80000.00")))

                coEvery { krakenService.getTickerPrices(any()) } returns mapOf(
                    "XBTUSD" to BigDecimal("60000.00"),
                    "XXBTZUSD" to BigDecimal("60000.00"),
                )

                val btcDeposit = LedgerEvent(
                    ledgerId = "dep-fresh-btc",
                    refid = "FT-BTC-FRESH",
                    time = flowTime,
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    asset = "BTC",
                    amount = BigDecimal("0.5"),
                    fee = BigDecimal.ZERO,
                )
                ledgerRepository.saveLedgers(listOf(btcDeposit))

                val result = analyzer(obsTime).updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("110000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = obsTime,
                )
                (result as AthUpdateResult.Trusted).drawdownPct.shouldBeEqualComparingTo(BigDecimal("20.0000"))
                statsRepository.load().allTimeHigh.shouldBeEqualComparingTo(BigDecimal("137500.00"))
                statsRepository.getAppliedAthFlowIds(listOf("dep-fresh-btc")) shouldBe setOf("dep-fresh-btc")
            }
        }

        "confirmed crypto deposit with fee scales ATH using net contribution" {
            runTest {
                statsRepository.save(PortfolioStats(BigDecimal("100000.00"), BigDecimal.ZERO))
                tradeRepository.setSyncMetadata(
                    SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC,
                    t60.epochSecond.toString(),
                )
                ledgerRepository.setSyncMetadata(
                    SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC,
                    t80.epochSecond.toString(),
                )

                tradeRepository.saveSnapshot(TestFixtures.emptySnapshot(t60, BigDecimal("100000.00")))

                tradeRepository.saveTrade(
                    TestFixtures.tradeRecord(
                        timestamp = t70.minusSeconds(1),
                        pair = "XBTUSD",
                        side = "buy",
                        symbol = "BTC",
                        volume = BigDecimal("1.0"),
                        usdAmount = BigDecimal("50000.00"),
                        price = BigDecimal("50000.00"),
                    ),
                )

                val feeDeposit = LedgerEvent(
                    ledgerId = "dep-btc-fee",
                    refid = "FT-BTC-FEE",
                    time = t70,
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    asset = "BTC",
                    amount = BigDecimal("0.50000000"),
                    fee = BigDecimal("0.00100000"),
                )
                ledgerRepository.saveLedgers(listOf(feeDeposit))

                val result = analyzer(t80).updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("124950.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = t80,
                )
                (result as AthUpdateResult.Trusted).drawdownPct.shouldBeEqualComparingTo(BigDecimal.ZERO)
                statsRepository.load().allTimeHigh.shouldBeEqualComparingTo(BigDecimal("124950.00"))
                statsRepository.getAppliedAthFlowIds(listOf("dep-btc-fee")) shouldBe setOf("dep-btc-fee")
            }
        }

        "flow prices from historical OHLC candle when no recent trade or snapshot exists" {
            runTest {
                statsRepository.save(PortfolioStats(BigDecimal("100000.00"), BigDecimal.ZERO))
                val flowTime = t0.plusSeconds(70)
                val obsTime = flowTime.plusSeconds(350)

                tradeRepository.setSyncMetadata(
                    SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC,
                    t60.epochSecond.toString(),
                )
                ledgerRepository.setSyncMetadata(
                    SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC,
                    obsTime.epochSecond.toString(),
                )

                tradeRepository.saveSnapshot(TestFixtures.emptySnapshot(t60, BigDecimal("80000.00")))

                coEvery { krakenService.getOHLC(any(), any(), any()) } returns listOf(
                    // The 15-minute candle ends exactly at the flow time;
                    // its close is historical information available at the
                    // event boundary, unlike an in-progress candle.
                    flowTime.minusSeconds(15 * 60L).epochSecond to BigDecimal("60000.00"),
                )

                val btcDeposit = LedgerEvent(
                    ledgerId = "dep-ohlc-btc",
                    refid = "FT-BTC-OHLC",
                    time = flowTime,
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    asset = "BTC",
                    amount = BigDecimal("0.5"),
                    fee = BigDecimal.ZERO,
                )
                ledgerRepository.saveLedgers(listOf(btcDeposit))

                val result = analyzer(obsTime).updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("110000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = obsTime,
                )
                (result as AthUpdateResult.Trusted).drawdownPct.shouldBeEqualComparingTo(BigDecimal("20.0000"))
                statsRepository.load().allTimeHigh.shouldBeEqualComparingTo(BigDecimal("137500.00"))
                statsRepository.getAppliedAthFlowIds(listOf("dep-ohlc-btc")) shouldBe setOf("dep-ohlc-btc")
            }
        }

        "in-progress intraday candle is ignored in favor of the latest completed candle" {
            runTest {
                statsRepository.save(PortfolioStats(BigDecimal("100000.00"), BigDecimal.ZERO))
                val flowTime = t0.plusSeconds(70)
                val obsTime = flowTime.plusSeconds(350)

                tradeRepository.setSyncMetadata(
                    SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC,
                    t60.epochSecond.toString(),
                )
                ledgerRepository.setSyncMetadata(
                    SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC,
                    obsTime.epochSecond.toString(),
                )
                tradeRepository.saveSnapshot(TestFixtures.emptySnapshot(t60, BigDecimal("80000.00")))

                coEvery { krakenService.getOHLC(any(), any(), any()) } returns listOf(
                    flowTime.minusSeconds(60).epochSecond to BigDecimal("90000.00"),
                    flowTime.minusSeconds(15 * 60L).epochSecond to BigDecimal("60000.00"),
                )
                ledgerRepository.saveLedgers(
                    listOf(
                        LedgerEvent(
                            ledgerId = "dep-ohlc-completed",
                            refid = "FT-BTC-OHLC-COMPLETED",
                            time = flowTime,
                            type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                            asset = "BTC",
                            amount = BigDecimal("0.5"),
                        ),
                    ),
                )

                val result = analyzer(obsTime).updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("110000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = obsTime,
                )

                (result as AthUpdateResult.Trusted).drawdownPct.shouldBeEqualComparingTo(BigDecimal("20.0000"))
                statsRepository.load().allTimeHigh.shouldBeEqualComparingTo(BigDecimal("137500.00"))
            }
        }

        "only an in-progress intraday candle fails closed for a stale flow" {
            runTest {
                statsRepository.save(PortfolioStats(BigDecimal("100000.00"), BigDecimal.ZERO))
                val flowTime = t0.plusSeconds(70)
                val obsTime = flowTime.plusSeconds(350)

                tradeRepository.setSyncMetadata(
                    SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC,
                    t60.epochSecond.toString(),
                )
                ledgerRepository.setSyncMetadata(
                    SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC,
                    obsTime.epochSecond.toString(),
                )
                tradeRepository.saveSnapshot(TestFixtures.emptySnapshot(t60, BigDecimal("80000.00")))
                coEvery { krakenService.getOHLC(any(), any(), any()) } returns listOf(
                    flowTime.minusSeconds(60).epochSecond to BigDecimal("90000.00"),
                )
                ledgerRepository.saveLedgers(
                    listOf(
                        LedgerEvent(
                            ledgerId = "dep-ohlc-active-only",
                            refid = "FT-BTC-OHLC-ACTIVE-ONLY",
                            time = flowTime,
                            type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                            asset = "BTC",
                            amount = BigDecimal("0.5"),
                        ),
                    ),
                )

                val result = analyzer(obsTime).updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("110000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = obsTime,
                )

                result shouldBe AthUpdateResult.Deferred(
                    BigDecimal("0.0000"),
                    AthTrustFailureReason.HISTORICAL_PRICE_UNAVAILABLE,
                )
                statsRepository.load().allTimeHigh.shouldBeEqualComparingTo(BigDecimal("100000.00"))
            }
        }

        "flow older than 300s defers when OHLC throws exception" {
            runTest {
                statsRepository.save(PortfolioStats(BigDecimal("100000.00"), BigDecimal.ZERO))
                val flowTime = t0.plusSeconds(70)
                val obsTime = flowTime.plusSeconds(350)

                tradeRepository.setSyncMetadata(
                    SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC,
                    t60.epochSecond.toString(),
                )
                ledgerRepository.setSyncMetadata(
                    SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC,
                    obsTime.epochSecond.toString(),
                )

                tradeRepository.saveSnapshot(TestFixtures.emptySnapshot(t60, BigDecimal("80000.00")))

                coEvery { krakenService.getOHLC(any(), any(), any()) } throws RuntimeException("network error")

                val btcDeposit = LedgerEvent(
                    ledgerId = "dep-ohlc-err-btc",
                    refid = "FT-BTC-ERR",
                    time = flowTime,
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    asset = "BTC",
                    amount = BigDecimal("0.5"),
                    fee = BigDecimal.ZERO,
                )
                ledgerRepository.saveLedgers(listOf(btcDeposit))

                val result = analyzer(obsTime).updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("110000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = obsTime,
                )
                (result as AthUpdateResult.Deferred).lastTrustedDrawdownPct!! shouldBeEqualComparingTo BigDecimal.ZERO
                statsRepository.load().allTimeHigh.shouldBeEqualComparingTo(BigDecimal("100000.00"))
            }
        }
    }

    private suspend fun seedObservationBoundaryCase(
        predecessorBtc: String,
        predecessorTotal: String,
        boundaryReward: LedgerEvent,
        additionalEvents: List<LedgerEvent> = emptyList(),
    ) {
        val btcBalance = BigDecimal(predecessorBtc)
        val total = BigDecimal(predecessorTotal)
        val btcValue = btcBalance.multiply(BigDecimal("50000.00"))
        val usdBalance = total.subtract(btcValue)
        statsRepository.save(PortfolioStats(BigDecimal("100000.00"), BigDecimal.ZERO))
        tradeRepository.saveSnapshot(
            PortfolioSnapshot(
                timestamp = t71,
                totalValueUSD = total,
                assets = mapOf(
                    "BTC" to TestFixtures.assetSnapshot(
                        symbol = "BTC",
                        balance = btcBalance,
                        price = BigDecimal("50000.00"),
                        valueUSD = btcValue,
                        targetPercent = BigDecimal("50.0"),
                    ),
                    Asset.USD to TestFixtures.assetSnapshot(
                        symbol = Asset.USD,
                        balance = usdBalance,
                        price = BigDecimal.ONE,
                        valueUSD = usdBalance,
                        targetPercent = BigDecimal("50.0"),
                    ),
                ),
                actions = emptyList(),
                drawdownPercent = BigDecimal.ZERO,
                fiatDeploymentPercent = BigDecimal.ZERO,
                effectiveUsdTargetPercent = BigDecimal("50.0"),
                balancesObservedAt = t60,
            ),
        )
        ledgerRepository.saveLedgers(
            listOf(boundaryReward) + additionalEvents + deposit("boundary-owner", t80, "10000.00"),
        )
        tradeRepository.setSyncMetadata(
            SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC,
            t60.epochSecond.toString(),
        )
        ledgerRepository.setSyncMetadata(
            SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC,
            t90.epochSecond.toString(),
        )
    }
}
