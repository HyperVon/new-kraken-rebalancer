package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.ComparisonAvailability
import com.gemini.krakenbot.model.ComparisonConfidence
import com.gemini.krakenbot.model.ComparisonUnavailableReason
import com.gemini.krakenbot.model.DepositStatusRecord
import com.gemini.krakenbot.model.FundingProvenanceResolver
import com.gemini.krakenbot.model.KrakenApiConstants
import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.RebalancerOrderIdentities
import com.gemini.krakenbot.model.SimpleFundingProvenanceResolver
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.model.TradeSource
import com.gemini.krakenbot.repository.LedgerRepository
import com.gemini.krakenbot.repository.OrderIntentRepository
import com.gemini.krakenbot.repository.PortfolioStatsRepository
import com.gemini.krakenbot.repository.TradeRepository
import com.gemini.krakenbot.service.FakeKrakenService
import com.gemini.krakenbot.service.impl.KrakenFundingProvenanceResolver
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import java.time.Instant

@Suppress("unused")
class TradeHistoryQueryServiceTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    private val repository = mockk<TradeRepository>(relaxed = true)
    private val statsRepository = mockk<PortfolioStatsRepository>(relaxed = true)
    private val ledgerRepository = mockk<LedgerRepository>(relaxed = true)
    private val orderIntentRepository = mockk<OrderIntentRepository>(relaxed = true)
    private val service = TradeHistoryQueryService(repository, statsRepository, ledgerRepository, orderIntentRepository)

    private val now = Instant.parse("2026-07-01T12:00:00Z")

    init {
        "getRewardsOverTime_CumulativePerSnapshotTime" {
            runTest {
                val snap1 = snapshot(now, "100000.00", btc = "1.0" to "50000.00")
                val snap2 = snapshot(now.plusSeconds(86400), "100000.00", btc = "1.0" to "50000.00")
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
                val e1 = ledgerEvent("L1", now.minusSeconds(3600), "BTC", "0.1")
                val e2 = ledgerEvent("L2", now.plusSeconds(3600), "BTC", "0.2")
                val e3 = ledgerEvent("L3", now.plusSeconds(90000), "BTC", "0.5")
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns listOf(e1, e2, e3)

                val rewards = service.getRewardsOverTime(Instant.EPOCH, now.plusSeconds(100000))

                rewards.points.size shouldBe 2
                rewards.points[0].cumulativeUSD.shouldBeEqualComparingTo(BigDecimal("5000.00"))
                rewards.points[0].perAssetUSD.getValue("BTC").shouldBeEqualComparingTo(BigDecimal("5000.00"))
                rewards.points[1].cumulativeUSD.shouldBeEqualComparingTo(BigDecimal("15000.00"))
                rewards.totalRewardsUSD.shouldBeEqualComparingTo(BigDecimal("15000.00"))
            }
        }

        "getRewardsOverTime_AccumulatesNetBalanceDeltaAccountingForFees" {
            runTest {
                val snap = snapshot(now, "100000.00", btc = "1.0" to "50000.00")
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(snap)
                val e1 = ledgerEvent("L1", now.minusSeconds(3600), "BTC", "0.1", fee = "0.01")
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns listOf(e1)

                val rewards = service.getRewardsOverTime(Instant.EPOCH, now)

                rewards.points.size shouldBe 1
                // 0.1 - 0.01 = 0.09 BTC * 50,000 = 4500.00 USD
                rewards.points[0].perAssetUSD.getValue("BTC").shouldBeEqualComparingTo(BigDecimal("4500.00"))
                rewards.totalRewardsUSD.shouldBeEqualComparingTo(BigDecimal("4500.00"))
            }
        }

        "getRewardsOverTime_EmptyRange_ReturnsZeroTotal" {
            runTest {
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns emptyList()

                val rewards = service.getRewardsOverTime(Instant.EPOCH, now)

                rewards.points.isEmpty() shouldBe true
                rewards.totalRewardsUSD.shouldBeEqualComparingTo(BigDecimal.ZERO)
            }
        }

        "getRewardsOverTime_SkipsAssetsMissingFromSnapshot" {
            runTest {
                val snap = snapshot(now, "100000.00", btc = "1.0" to "50000.00")
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(snap)
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns
                    listOf(ledgerEvent("L1", now.minusSeconds(3600), "SOL", "0.5"))

                val rewards = service.getRewardsOverTime(Instant.EPOCH, now)

                rewards.points.size shouldBe 1
                rewards.points[0].perAssetUSD.isEmpty() shouldBe true
                rewards.points[0].cumulativeUSD.shouldBeEqualComparingTo(BigDecimal("0.00"))
            }
        }

        "getRewardsOverTime_NormalizesEarnStakedAssetSymbols" {
            runTest {
                val snap = snapshot(now, "100000.00", btc = "1.0" to "50000.00")
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(snap)
                val e1 = ledgerEvent("L1", now.minusSeconds(3600), "XXBT", "0.1")
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns listOf(e1)

                val rewards = service.getRewardsOverTime(Instant.EPOCH, now)

                rewards.points.size shouldBe 1
                rewards.points[0].perAssetUSD.getValue(Asset.BTC).shouldBeEqualComparingTo(BigDecimal("5000.00"))
                rewards.totalRewardsUSD.shouldBeEqualComparingTo(BigDecimal("5000.00"))
            }
        }

        "getRewardsOverTime_IncludesEarnRewardsButExcludesAllocationMechanics" {
            runTest {
                val snap = snapshot(now, "100000.00", btc = "1.0" to "50000.00")
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(snap)
                val earnReward = ledgerEvent("EARN-REWARD", now.minusSeconds(3600), "BTC", "0.1")
                    .copy(type = KrakenApiConstants.LEDGER_TYPE_EARN, subtype = "reward")
                val earnAllocation = ledgerEvent("EARN-ALLOCATION", now.minusSeconds(1800), "BTC", "-1.0")
                    .copy(type = KrakenApiConstants.LEDGER_TYPE_EARN, subtype = "allocation")
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns
                    listOf(earnReward, earnAllocation)

                val rewards = service.getRewardsOverTime(Instant.EPOCH, now)

                rewards.points[0].perAssetUSD.getValue(Asset.BTC)
                    .shouldBeEqualComparingTo(BigDecimal("5000.00"))
                rewards.totalRewardsUSD.shouldBeEqualComparingTo(BigDecimal("5000.00"))
            }
        }

        "getLedgersInRange_DelegatesToLedgerRepository" {
            runTest {
                val expected = listOf(ledgerEvent("L1", now.minusSeconds(3600), "BTC", "0.1"))
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns expected

                val result = service.getLedgersInRange(Instant.EPOCH, now)

                result shouldBe expected
            }
        }

        "getRebalancerComparison_StakingRewardExplainsDelta_Reconciled" {
            runTest {
                val snap1 = snapshot(now, "100000.00", btc = "1.0" to "50000.00")
                val snap2 = snapshot(now.plusSeconds(1800), "105000.00", btc = "1.1" to "50000.00")
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                val staking = ledgerEvent("L1", now.plusSeconds(600), "BTC", "0.1")
                val dividend =
                    ledgerEvent("L2", now.plusSeconds(1200), "STRC", "1.25", KrakenApiConstants.LEDGER_TYPE_DIVIDEND)
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns listOf(staking, dividend)

                val comparison = service.getRebalancerComparison(Instant.EPOCH, now.plusSeconds(1800))

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.confidence shouldBe ComparisonConfidence.RECONCILED
            }
        }

        "getRebalancerComparison_UsesProductionFundingResolver" {
            runTest {
                val snap1 = snapshot(
                    now,
                    "1000.00",
                    btc = "0.0" to "50000.00",
                    usdBalance = "1000.00",
                )
                val snap2 = snapshot(
                    now.plusSeconds(3600),
                    "1100.00",
                    btc = "0.0" to "50000.00",
                    usdBalance = "1100.00",
                )
                val deposit = ledgerEvent(
                    ledgerId = "LIVE-DEPOSIT",
                    timestamp = now.plusSeconds(1800),
                    asset = Asset.USD,
                    amount = "100.00",
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    refid = "LIVE-DEPOSIT-REF",
                )
                val kraken = FakeKrakenService().apply {
                    depositStatusSupplier = { _, _ ->
                        listOf(
                            DepositStatusRecord(
                                refid = "LIVE-DEPOSIT-REF",
                                asset = Asset.USD,
                                amount = BigDecimal("100.00"),
                                time = deposit.time,
                                status = "Success",
                                method = "Wire",
                            ),
                        )
                    }
                }
                val productionBoundService = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    fundingProvenanceResolver = KrakenFundingProvenanceResolver(kraken),
                )
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns listOf(deposit)

                val comparison = productionBoundService.getRebalancerComparison(Instant.EPOCH, snap2.timestamp)

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.confidence shouldBe ComparisonConfidence.RECONCILED
                comparison.points.last().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("1100.00"))
                kraken.getDepositStatusCallCount shouldBe 1
                kraken.getWithdrawStatusCallCount shouldBe 0
                kraken.getInternalTransfersCallCount shouldBe 1
            }
        }

        "getRebalancerComparison_DurableOrderTxidIntentProvesBotOwnership" {
            runTest {
                val snap1 = snapshot(now, "100000.00", btc = "1.0" to "50000.00")
                val snap2 =
                    snapshot(now.plusSeconds(3600), "100000.00", btc = "1.2" to "50000.00", usdBalance = "40000.00")
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
                val trade = TradeRecord(
                    id = 1,
                    pair = "BTCUSD",
                    symbol = "BTC",
                    side = "BUY",
                    timestamp = now.plusSeconds(1800),
                    volume = BigDecimal("0.2"),
                    usdAmount = BigDecimal("10000.00"),
                    success = true,
                    dryRun = false,
                    price = BigDecimal("50000.00"),
                    fee = BigDecimal.ZERO,
                    source = TradeSource.API_FILL,
                    tradeId = "T1",
                    orderTxid = "BOT-ORDER-1",
                    cycleId = null,
                    clientOrderId = null,
                )
                coEvery { repository.getTradesInRange(any(), any()) } returns listOf(trade)
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()
                coEvery { orderIntentRepository.getKnownRebalancerOrderIdentities(any(), any()) } returns
                    RebalancerOrderIdentities(orderTxids = setOf("BOT-ORDER-1"))

                val comparison = service.getRebalancerComparison(Instant.EPOCH, now.plusSeconds(3600))

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.confidence shouldBe ComparisonConfidence.RECONCILED
                // Because trade is bot-owned (REBALANCER), it is NOT replayed into Buy & Hold.
                // Buy & Hold stays 1.0 BTC @ 50k + 50k USD = 100,000. Actual = 1.2 BTC @ 50k + 40k USD = 100,000.
                comparison.points.last().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("100000.00"))
            }
        }

        "getRebalancerComparison_FetchesTerminalLateFillWithinClockSkew" {
            runTest {
                val snap1 = snapshot(now, "100000.00", btc = "1.0" to "50000.00")
                val last = now.plusSeconds(3600)
                val snap2 = snapshot(last, "100000.00", btc = "1.2" to "50000.00", usdBalance = "40000.00")
                val trade = TradeRecord(
                    id = 1,
                    pair = "BTCUSD",
                    symbol = "BTC",
                    side = "BUY",
                    timestamp = last.plusMillis(500),
                    volume = BigDecimal("0.2"),
                    usdAmount = BigDecimal("10000.00"),
                    success = true,
                    dryRun = false,
                    price = BigDecimal("50000.00"),
                    fee = BigDecimal.ZERO,
                    source = TradeSource.API_FILL,
                    tradeId = "TERMINAL-LATE-FILL",
                    orderTxid = "TERMINAL-LATE-ORDER",
                    cycleId = null,
                    clientOrderId = null,
                )
                val queriedTradesTo = slot<Instant>()
                val queriedLedgersTo = slot<Instant>()
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
                coEvery { repository.getTradesInRange(any(), capture(queriedTradesTo)) } returns listOf(trade)
                coEvery { ledgerRepository.getLedgersInRange(any(), capture(queriedLedgersTo)) } returns emptyList()
                coEvery { orderIntentRepository.getKnownRebalancerOrderIdentities(any(), any()) } returns
                    RebalancerOrderIdentities()

                val comparison = service.getRebalancerComparison(Instant.EPOCH, last.plusSeconds(1))

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.confidence shouldBe ComparisonConfidence.RECONCILED
                queriedTradesTo.captured shouldBe last.plusMillis(1_000)
                queriedLedgersTo.captured shouldBe last.plusMillis(1_000)
                comparison.points.last().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("100000.00"))
            }
        }

        "getRebalancerComparison_QueriesFromAnchorSnapshotWhenAvailable" {
            runTest {
                val anchorTime = now.minusSeconds(3600)
                val anchor = snapshot(anchorTime, "100000.00", btc = "1.0" to "50000.00", usdBalance = "50000.00")
                val snap1 = snapshot(now, "100000.00", btc = "1.2" to "50000.00", usdBalance = "40000.00")
                val snap2 =
                    snapshot(now.plusSeconds(3600), "100000.00", btc = "1.2" to "50000.00", usdBalance = "40000.00")

                // Trade executed just after baseline observation time due to clock skew, but was in snap1 balances
                val trade = TradeRecord(
                    id = 1,
                    pair = "BTCUSD",
                    symbol = "BTC",
                    side = "BUY",
                    timestamp = now.plusMillis(250),
                    volume = BigDecimal("0.2"),
                    usdAmount = BigDecimal("10000.00"),
                    success = true,
                    dryRun = false,
                    price = BigDecimal("50000.00"),
                    fee = BigDecimal.ZERO,
                    source = TradeSource.API_FILL,
                    tradeId = "T-SKEW",
                    orderTxid = "O-SKEW",
                    cycleId = null,
                    clientOrderId = null,
                )

                val queriedTradesFrom = slot<Instant>()
                val queriedLedgersFrom = slot<Instant>()
                coEvery { repository.getSnapshotBefore(now) } returns anchor
                coEvery { repository.getSnapshotsInRange(now, now.plusSeconds(3600)) } returns listOf(snap1, snap2)
                coEvery { repository.getTradesInRange(capture(queriedTradesFrom), any()) } returns listOf(trade)
                coEvery { ledgerRepository.getLedgersInRange(capture(queriedLedgersFrom), any()) } returns emptyList()
                coEvery { orderIntentRepository.getKnownRebalancerOrderIdentities(any(), any()) } returns
                    RebalancerOrderIdentities()

                val comparison = service.getRebalancerComparison(now, now.plusSeconds(3600))

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.confidence shouldBe ComparisonConfidence.RECONCILED
                queriedTradesFrom.captured shouldBe anchorTime
                queriedLedgersFrom.captured shouldBe anchorTime
                comparison.points.size shouldBe 2
                comparison.baselineTimestamp shouldBe now
                comparison.points[0].timestamp shouldBe now
                // Assert trade is not replayed into Buy & Hold after baseline
                comparison.points.last().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("100000.00"))
            }
        }

        "getRebalancerComparison_ExpandsEventQueryForLegacyObservationRows" {
            runTest {
                val snap1 = snapshot(
                    now,
                    "100000.00",
                    btc = "1.0" to "50000.00",
                    balancesObservedAt = null,
                )
                val snap2 = snapshot(
                    now.plusSeconds(3600),
                    "105000.00",
                    btc = "1.1" to "50000.00",
                    usdBalance = "50000.00",
                    balancesObservedAt = null,
                )
                val boundaryLedger = ledgerEvent("LEGACY-BOUNDARY", now.minusMillis(500), "BTC", "0.1")
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
                coEvery { repository.getSnapshotBefore(now) } returns null
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery {
                    ledgerRepository.getLedgersInRange(
                        now.minusMillis(1_000),
                        now.plusSeconds(3600).plusMillis(1_000),
                    )
                } returns listOf(boundaryLedger)

                val comparison = service.getRebalancerComparison(Instant.EPOCH, now.plusSeconds(3600))

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.points.last().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("105000.00"))
                coVerify(exactly = 1) {
                    repository.getTradesInRange(now.minusMillis(1_000), now.plusSeconds(3600).plusMillis(1_000))
                }
                coVerify(exactly = 1) {
                    ledgerRepository.getLedgersInRange(now.minusMillis(1_000), now.plusSeconds(3600).plusMillis(1_000))
                }
            }
        }

        "getRebalancerComparison_OrderIntentCreatedBeforeBaselineIdentifiesBotFillAfterBaseline" {
            runTest {
                // Baseline snapshot at T+0 (now)
                val snap1 = snapshot(now, "100000.00", btc = "1.0" to "50000.00")
                // Subsequent snapshot at T+3600
                val snap2 =
                    snapshot(now.plusSeconds(3600), "100000.00", btc = "1.2" to "50000.00", usdBalance = "40000.00")
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)

                // Fill occurs at T+2s (after baseline), but intent was created at T-1s (before baseline)
                val trade = TradeRecord(
                    id = 1,
                    pair = "BTCUSD",
                    symbol = "BTC",
                    side = "BUY",
                    timestamp = now.plusSeconds(2),
                    volume = BigDecimal("0.2"),
                    usdAmount = BigDecimal("10000.00"),
                    success = true,
                    dryRun = false,
                    price = BigDecimal("50000.00"),
                    fee = BigDecimal.ZERO,
                    source = TradeSource.API_FILL,
                    tradeId = "T1",
                    orderTxid = "BOT-ORDER-EARLY-INTENT",
                    cycleId = null,
                    clientOrderId = null,
                )
                coEvery { repository.getTradesInRange(any(), any()) } returns listOf(trade)
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()
                val requestedOrderTxids = slot<Set<String>>()
                val requestedClientOrderIds = slot<Set<String>>()
                coEvery {
                    orderIntentRepository.getKnownRebalancerOrderIdentities(
                        capture(requestedOrderTxids),
                        capture(requestedClientOrderIds),
                    )
                } returns
                    RebalancerOrderIdentities(orderTxids = setOf("BOT-ORDER-EARLY-INTENT"))

                val comparison = service.getRebalancerComparison(now, now.plusSeconds(3600))

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.confidence shouldBe ComparisonConfidence.RECONCILED
                requestedOrderTxids.captured shouldBe setOf("BOT-ORDER-EARLY-INTENT")
                requestedClientOrderIds.captured shouldBe emptySet()
                // Correctly classified as REBALANCER (not manual or unknown)
                comparison.points.last().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("100000.00"))
            }
        }

        "getRebalancerComparison_ManualTradeReplaysIntoBuyAndHold" {
            runTest {
                val snap1 = snapshot(now, "100000.00", btc = "1.0" to "50000.00")
                val snap2 =
                    snapshot(now.plusSeconds(3600), "100000.00", btc = "1.2" to "50000.00", usdBalance = "40000.00")
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
                val trade = TradeRecord(
                    id = 1,
                    pair = "BTCUSD",
                    symbol = "BTC",
                    side = "BUY",
                    timestamp = now.plusSeconds(1800),
                    volume = BigDecimal("0.2"),
                    usdAmount = BigDecimal("10000.00"),
                    success = true,
                    dryRun = false,
                    price = BigDecimal("50000.00"),
                    fee = BigDecimal.ZERO,
                    source = TradeSource.API_FILL,
                    tradeId = "MANUAL-T1",
                    orderTxid = "MANUAL-O1",
                    cycleId = null,
                    clientOrderId = null,
                )
                coEvery { repository.getTradesInRange(any(), any()) } returns listOf(trade)
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()
                coEvery { orderIntentRepository.getKnownRebalancerOrderIdentities(any(), any()) } returns
                    RebalancerOrderIdentities(orderTxids = emptySet())

                val comparison = service.getRebalancerComparison(Instant.EPOCH, now.plusSeconds(3600))

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.confidence shouldBe ComparisonConfidence.RECONCILED
                // Manual trade is replayed into Buy & Hold, so Buy & Hold matches Actual
                comparison.points.last().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("100000.00"))
                comparison.points.last().differenceUSD.shouldBeEqualComparingTo(BigDecimal.ZERO)
            }
        }

        "getRebalancerComparison_UnknownTradeFailsClosedWithAmbiguousOwnership" {
            runTest {
                val snap1 = snapshot(now, "100000.00", btc = "1.0" to "50000.00")
                val snap2 =
                    snapshot(now.plusSeconds(3600), "100000.00", btc = "1.2" to "50000.00", usdBalance = "40000.00")
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
                val trade = TradeRecord(
                    id = 1,
                    pair = "BTCUSD",
                    symbol = "BTC",
                    side = "BUY",
                    timestamp = now.plusSeconds(1800),
                    volume = BigDecimal("0.2"),
                    usdAmount = BigDecimal("10000.00"),
                    success = true,
                    dryRun = false,
                    price = BigDecimal("50000.00"),
                    fee = BigDecimal.ZERO,
                    source = TradeSource.LEGACY_UNKNOWN,
                    tradeId = null,
                    orderTxid = null,
                    cycleId = null,
                    clientOrderId = null,
                )
                coEvery { repository.getTradesInRange(any(), any()) } returns listOf(trade)
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val comparison = service.getRebalancerComparison(Instant.EPOCH, now.plusSeconds(3600))

                comparison.availability shouldBe ComparisonAvailability.UNAVAILABLE
                comparison.unavailableReason shouldBe
                    ComparisonUnavailableReason.AMBIGUOUS_TRADE_OWNERSHIP
            }
        }

        "getRebalancerComparison_ReturnsUnavailableWhenLessThanTwoSnapshots" {
            runTest {
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(
                    snapshot(now, "100000.00", btc = "1.0" to "50000.00"),
                )
                val comparison = service.getRebalancerComparison(Instant.EPOCH, now.plusSeconds(3600))
                comparison.availability shouldBe ComparisonAvailability.UNAVAILABLE
                comparison.unavailableReason shouldBe
                    ComparisonUnavailableReason.INSUFFICIENT_SNAPSHOTS
            }
        }

        "getRebalancerComparison_HandlesNullOrderIntentRepositoryAndBlankIdentifiers" {
            runTest {
                val serviceNoIntent = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = null,
                )
                val snap1 = snapshot(now, "100000.00", btc = "1.0" to "50000.00")
                val snap2 = snapshot(now.plusSeconds(3600), "100000.00", btc = "1.0" to "50000.00")
                val tradeWithBlankIds = TradeRecord(
                    id = 1,
                    pair = "BTCUSD",
                    symbol = "BTC",
                    side = "BUY",
                    timestamp = now.plusSeconds(1800),
                    volume = BigDecimal.ZERO,
                    usdAmount = BigDecimal.ZERO,
                    success = false,
                    dryRun = false,
                    price = BigDecimal.ZERO,
                    fee = BigDecimal.ZERO,
                    tradeId = "   ",
                    orderTxid = "   ",
                    cycleId = null,
                    clientOrderId = "   ",
                )
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
                coEvery { repository.getTradesInRange(any(), any()) } returns listOf(tradeWithBlankIds)
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val comparison = serviceNoIntent.getRebalancerComparison(Instant.EPOCH, now.plusSeconds(3600))
                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.confidence shouldBe ComparisonConfidence.RECONCILED
            }
        }

        "getRebalancerComparison_IdentifiesBotTradeViaClientOrderId" {
            runTest {
                val snap1 = snapshot(now, "100000.00", btc = "1.0" to "50000.00")
                val snap2 =
                    snapshot(now.plusSeconds(3600), "100000.00", btc = "1.2" to "50000.00", usdBalance = "40000.00")
                val trade = TradeRecord(
                    id = 1,
                    pair = "BTCUSD",
                    symbol = "BTC",
                    side = "BUY",
                    timestamp = now.plusSeconds(1800),
                    volume = BigDecimal("0.2"),
                    usdAmount = BigDecimal("10000.00"),
                    success = true,
                    dryRun = false,
                    price = BigDecimal("50000.00"),
                    fee = BigDecimal.ZERO,
                    source = TradeSource.API_FILL,
                    tradeId = null,
                    orderTxid = null,
                    cycleId = null,
                    clientOrderId = "CLIENT-ORDER-99",
                )
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
                coEvery { repository.getTradesInRange(any(), any()) } returns listOf(trade)
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()
                coEvery {
                    orderIntentRepository.getKnownRebalancerOrderIdentities(any(), setOf("CLIENT-ORDER-99"))
                } returns RebalancerOrderIdentities(
                    orderTxids = setOf("BOT-ORDER-1"),
                )

                val comparison = service.getRebalancerComparison(Instant.EPOCH, now.plusSeconds(3600))
                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.confidence shouldBe ComparisonConfidence.RECONCILED
            }
        }

        "getRebalancerComparison_UsesInceptionDiscoveryServiceWhenProvided" {
            runTest {
                val inceptionTime = now.minusSeconds(86400 * 30)
                val snapInception =
                    snapshot(inceptionTime, "80000.00", btc = "1.0" to "40000.00", usdBalance = "40000.00")
                val snap1 = snapshot(now, "100000.00", btc = "1.0" to "50000.00")
                val snap2 = snapshot(now.plusSeconds(3600), "110000.00", btc = "1.0" to "60000.00")

                val mockInceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
                coEvery { mockInceptionService.resolveInception() } returns InceptionResolution(
                    inceptionTime = inceptionTime,
                    inceptionSnapshot = snapInception,
                    isAutoDetected = true,
                )

                val serviceWithInception = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = mockInceptionService,
                )

                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val comparison = serviceWithInception.getRebalancerComparison(now, now.plusSeconds(3600))

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.baselineTimestamp shouldBe inceptionTime
                comparison.points.size shouldBe 2
            }
        }

        "getRebalancerComparison_ResolvesSnapshotBeforeWhenInceptionSnapshotIsNull" {
            runTest {
                val inceptionTime = now.minusSeconds(86400 * 10)
                val snapInception = snapshot(
                    inceptionTime,
                    "85000.00",
                    btc = "1.0" to "45000.00",
                    usdBalance = "40000.00",
                    balancesObservedAt = null,
                )
                val snap1 = snapshot(now, "100000.00", btc = "1.0" to "50000.00")
                val snap2 = snapshot(now.plusSeconds(3600), "110000.00", btc = "1.0" to "60000.00")

                val mockInceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
                coEvery { mockInceptionService.resolveInception() } returns InceptionResolution(
                    inceptionTime = inceptionTime,
                    inceptionSnapshot = null,
                    isAutoDetected = true,
                )
                coEvery { repository.getSnapshotBefore(any()) } returns snapInception
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
                // Bounded inception fallback queries [inception-300s, inception+30s];
                // answer with the true inception snapshot, not the window snapshots.
                coEvery {
                    repository.getSnapshotsInRange(
                        inceptionTime.minusSeconds(300),
                        inceptionTime.plusSeconds(30),
                    )
                } returns listOf(snapInception)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val serviceWithInception = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = mockInceptionService,
                )

                val comparison = serviceWithInception.getRebalancerComparison(now, now.plusSeconds(3600))

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.baselineTimestamp shouldBe inceptionTime
                comparison.points.size shouldBe 2
            }
        }

        "getRebalancerComparison_OwnerDeposit PricedFromRecordedSnapshots" {
            runTest {
                val t0 = now.minusSeconds(86400 * 30)
                val tMid = now.plusSeconds(1800)
                val snap0 = snapshot(
                    t0,
                    "100000.00",
                    btc = "1.0" to "50000.00",
                    usdBalance = "50000.00",
                    balancesObservedAt = t0,
                )
                val snap1 = snapshot(now, "100000.00", btc = "1.0" to "50000.00", usdBalance = "50000.00")
                val snap2 = snapshot(
                    now.plusSeconds(3600),
                    "110000.00",
                    btc = "1.0" to "50000.00",
                    usdBalance = "60000.00",
                )

                val mockInceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
                coEvery { mockInceptionService.resolveInception() } returns InceptionResolution(
                    inceptionTime = t0,
                    inceptionSnapshot = snap0,
                    isAutoDetected = true,
                )
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
                coEvery { repository.getSnapshotBefore(any()) } returns null
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns
                    listOf(
                        ledgerEvent(
                            ledgerId = "qs-deposit-1",
                            timestamp = tMid,
                            asset = "USD",
                            amount = "10000.00",
                            type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                        ),
                    )

                val serviceWithInception = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = mockInceptionService,
                    fundingProvenanceResolver = SimpleFundingProvenanceResolver(
                        deposits = listOf(
                            DepositStatusRecord(
                                refid = "FT-qs-deposit-1",
                                asset = "USD",
                                amount = BigDecimal("10000.00"),
                                time = tMid,
                                status = "Success",
                                method = "Wire",
                            ),
                        ),
                    ),
                )

                val comparison = serviceWithInception.getRebalancerComparison(now, now.plusSeconds(3600))

                // $10k allocated by 50/50 inception weights at recorded 50k
                // BTC: no artificial alpha at flat prices.
                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("110000.00")
            }
        }

        "getRebalancerComparison_OwnerCryptoDeposit_PricedFromRecordedSnapshots" {
            runTest {
                val t0 = now.minusSeconds(86400 * 30)
                val tMid = now.plusSeconds(1800)
                val snap0 = snapshot(
                    t0,
                    "100000.00",
                    btc = "1.0" to "50000.00",
                    usdBalance = "50000.00",
                    balancesObservedAt = t0,
                )
                val snap1 = snapshot(now, "100000.00", btc = "1.0" to "50000.00", usdBalance = "50000.00")
                val snap2 = snapshot(
                    now.plusSeconds(3600),
                    "125000.00",
                    btc = "1.5" to "50000.00",
                    usdBalance = "50000.00",
                )

                val mockInceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
                coEvery { mockInceptionService.resolveInception() } returns InceptionResolution(
                    inceptionTime = t0,
                    inceptionSnapshot = snap0,
                    isAutoDetected = true,
                )
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
                coEvery { repository.getSnapshotBefore(any()) } returns null
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns
                    listOf(
                        ledgerEvent(
                            ledgerId = "qs-deposit-btc",
                            timestamp = tMid,
                            asset = "BTC",
                            amount = "0.50000000",
                            type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                        ),
                    )

                val serviceWithInception = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = mockInceptionService,
                    fundingProvenanceResolver = SimpleFundingProvenanceResolver(
                        deposits = listOf(
                            DepositStatusRecord(
                                refid = "tx-qs-deposit-btc",
                                txid = "0xbtc123",
                                asset = "BTC",
                                amount = BigDecimal("0.50000000"),
                                time = tMid,
                                status = "Success",
                                method = "Bitcoin",
                            ),
                        ),
                    ),
                )

                val comparison = serviceWithInception.getRebalancerComparison(now, now.plusSeconds(3600))
                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("125000.00")
            }
        }

        "getRebalancerComparison_RejectsFutureObservedPriceCandidates" {
            runTest {
                val t0 = now.minusSeconds(86400 * 30)
                val tMid = now.plusSeconds(1800)
                val snap0 = snapshot(
                    t0,
                    "100000.00",
                    btc = "1.0" to "50000.00",
                    usdBalance = "50000.00",
                    balancesObservedAt = t0,
                )
                val snap1 = snapshot(now, "100000.00", btc = "1.0" to "50000.00", usdBalance = "50000.00")
                val snap2 = snapshot(
                    now.plusSeconds(3600),
                    "125000.00",
                    btc = "1.5" to "50000.00",
                    usdBalance = "50000.00",
                )
                val futureObserved = snap1.copy(
                    timestamp = tMid.minusSeconds(60),
                    balancesObservedAt = tMid.plusSeconds(1),
                    assets = snap1.assets.mapValues { (symbol, asset) ->
                        if (symbol == Asset.BTC) asset.copy(price = BigDecimal("99999.00")) else asset
                    },
                )
                val missingAsset = snap1.copy(
                    timestamp = tMid.minusSeconds(90),
                    assets = snap1.assets - Asset.BTC,
                )
                val validPrice = snap1.copy(
                    timestamp = tMid.minusSeconds(120),
                    balancesObservedAt = tMid.minusSeconds(120),
                )
                val mockInceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
                coEvery { mockInceptionService.resolveInception() } returns InceptionResolution(
                    inceptionTime = t0,
                    inceptionSnapshot = snap0,
                    isAutoDetected = true,
                )
                coEvery { repository.getSnapshotBefore(any()) } returns null
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns listOf(
                    ledgerEvent(
                        ledgerId = "future-observation-deposit",
                        timestamp = tMid,
                        asset = Asset.BTC,
                        amount = "0.50000000",
                        type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    ),
                )
                coEvery { repository.getSnapshotsInRange(any(), any()) } answers {
                    if (secondArg<Instant>() == tMid) {
                        listOf(futureObserved, missingAsset, validPrice)
                    } else {
                        listOf(snap1, snap2)
                    }
                }

                val serviceWithInception = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = mockInceptionService,
                    fundingProvenanceResolver = SimpleFundingProvenanceResolver(
                        deposits = listOf(
                            DepositStatusRecord(
                                refid = "tx-future-observation-deposit",
                                txid = "0xfuture-observation",
                                asset = Asset.BTC,
                                amount = BigDecimal("0.50000000"),
                                time = tMid,
                                status = "Success",
                                method = "Bitcoin",
                            ),
                        ),
                    ),
                )

                val comparison = serviceWithInception.getRebalancerComparison(now, now.plusSeconds(3600))

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("125000.00")
            }
        }

        "getRebalancerComparison_TruncatedHistoryReturnsInformativeUnavailable" {
            runTest {
                val snap1 = snapshot(now, "100000.00", btc = "1.0" to "50000.00")
                val snap2 = snapshot(now.plusSeconds(3600), "100000.00", btc = "1.0" to "50000.00")

                val mockInceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
                coEvery { mockInceptionService.resolveInception() } returns InceptionResolution(
                    inceptionTime = now.minusSeconds(86400 * 100),
                    inceptionSnapshot = null,
                    isAutoDetected = true,
                    confidence = InceptionConfidence.TRUNCATED,
                )
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)

                val serviceWithInception = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = mockInceptionService,
                )

                val comparison = serviceWithInception.getRebalancerComparison(now, now.plusSeconds(3600))

                comparison.availability shouldBe ComparisonAvailability.UNAVAILABLE
                comparison.unavailableReason shouldBe ComparisonUnavailableReason.INCEPTION_HISTORY_TRUNCATED
            }
        }
    }

    private fun snapshot(
        timestamp: Instant,
        totalValueUSD: String,
        btc: Pair<String, String>,
        usdBalance: String = "50000.00",
        balancesObservedAt: Instant? = timestamp,
    ): PortfolioSnapshot {
        val (btcBalance, btcPrice) = btc
        val btcValue = BigDecimal(btcBalance).multiply(BigDecimal(btcPrice))
        val usdVal = BigDecimal(usdBalance)
        return PortfolioSnapshot(
            timestamp = timestamp,
            totalValueUSD = BigDecimal(totalValueUSD),
            assets = mapOf(
                Asset.BTC to TestFixtures.assetSnapshot(
                    symbol = Asset.BTC,
                    balance = BigDecimal(btcBalance),
                    price = BigDecimal(btcPrice),
                    valueUSD = btcValue,
                    targetPercent = BigDecimal.ZERO,
                ),
                TestFixtures.USD to TestFixtures.assetSnapshot(
                    symbol = TestFixtures.USD,
                    balance = usdVal,
                    price = BigDecimal.ONE,
                    valueUSD = usdVal,
                    targetPercent = BigDecimal.ZERO,
                ),
            ),
            actions = emptyList(),
            drawdownPercent = BigDecimal.ZERO,
            fiatDeploymentPercent = BigDecimal.ZERO,
            effectiveUsdTargetPercent = BigDecimal.ZERO,
            balancesObservedAt = balancesObservedAt,
        )
    }

    private fun ledgerEvent(
        ledgerId: String,
        timestamp: Instant,
        asset: String,
        amount: String,
        type: String = KrakenApiConstants.LEDGER_TYPE_STAKING,
        fee: String = "0",
        refid: String? = null,
    ): LedgerEvent {
        val resolvedRefid = refid ?: when (type) {
            KrakenApiConstants.LEDGER_TYPE_DEPOSIT -> {
                val norm = Asset.normalizeLedgerAsset(asset).uppercase()
                if (norm == Asset.USD) "FT-$ledgerId" else "tx-$ledgerId"
            }

            KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL -> "WIRE-$ledgerId"

            else -> null
        }
        return LedgerEvent(
            ledgerId = ledgerId,
            refid = resolvedRefid,
            time = timestamp,
            type = type,
            asset = asset,
            amount = BigDecimal(amount),
            fee = BigDecimal(fee),
        )
    }
}
