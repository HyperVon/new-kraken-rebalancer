package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.ComparisonAvailability
import com.gemini.krakenbot.model.ComparisonConfidence
import com.gemini.krakenbot.model.ComparisonProposalStatus
import com.gemini.krakenbot.model.ComparisonUnavailableReason
import com.gemini.krakenbot.model.DepositStatusRecord
import com.gemini.krakenbot.model.FundingEvidence
import com.gemini.krakenbot.model.FundingProvenanceFailure
import com.gemini.krakenbot.model.FundingProvenanceFailureReason
import com.gemini.krakenbot.model.FundingProvenanceResolver
import com.gemini.krakenbot.model.KrakenApiConstants
import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.model.OrderSide
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.RebalancerOrderIdentities
import com.gemini.krakenbot.model.SimpleFundingProvenanceResolver
import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.model.TradeSource
import com.gemini.krakenbot.repository.LedgerRepository
import com.gemini.krakenbot.repository.OrderIntentRepository
import com.gemini.krakenbot.repository.PortfolioStatsRepository
import com.gemini.krakenbot.repository.TradeRepository
import com.gemini.krakenbot.service.AutomaticBaselineStatus
import com.gemini.krakenbot.service.FakeKrakenService
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.service.impl.KrakenFundingProvenanceResolver
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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

    private val openEndedRangeEnd = Instant.ofEpochMilli(Long.MAX_VALUE)
    private val repository = mockk<TradeRepository>(relaxed = true)
    private val statsRepository = mockk<PortfolioStatsRepository>(relaxed = true)
    private val ledgerRepository = mockk<LedgerRepository>(relaxed = true)
    private val orderIntentRepository = mockk<OrderIntentRepository>(relaxed = true)
    private val service = TradeHistoryQueryService(repository, statsRepository, ledgerRepository, orderIntentRepository)

    private val now = Instant.parse("2026-07-01T12:00:00Z")

    init {
        coEvery { repository.getAllSnapshotsInRange(any(), any()) } coAnswers {
            repository.getSnapshotsInRange(firstArg(), secondArg())
        }
        // Relaxed mocks answer getSyncMetadata with "" → null coverage, which defers the
        // Settings evaluation; default to far-future certified coverage so tests exercise
        // the stable-history path. Defer-specific tests stub these keys to null.
        coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC) } returns "4102444800"
        coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC) } returns
            "4102444800"

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

        "getSnapshotsInRange retains the final snapshot of duplicate instants" {
            runTest {
                val t = now
                val snap1 = snapshot(t, "100000.00", btc = "1.0" to "50000.00")
                val snap2 = snapshot(t, "100100.00", btc = "1.0" to "50000.00")
                coEvery { repository.getAllSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
                coEvery { repository.getSyncMetadata(any()) } returns null

                val result = service.getSnapshotsInRange(t.minusSeconds(60), t.plusSeconds(60))
                result.size shouldBe 1
                result.single().totalValueUSD.shouldBeEqualComparingTo(BigDecimal("100100.00"))
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

        "getRewardsOverTime_IncludesEarnAndAirdropRewardsButExcludesAllocationMechanics" {
            runTest {
                val snap = snapshot(now, "100000.00", btc = "1.0" to "50000.00")
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(snap)
                val earnReward = ledgerEvent("EARN-REWARD", now.minusSeconds(3600), "BTC", "0.1")
                    .copy(type = KrakenApiConstants.LEDGER_TYPE_EARN, subtype = "reward")
                val earnAllocation = ledgerEvent("EARN-ALLOCATION", now.minusSeconds(1800), "BTC", "-1.0")
                    .copy(type = KrakenApiConstants.LEDGER_TYPE_EARN, subtype = "allocation")
                val airdrop = ledgerEvent("AIRDROP", now.minusSeconds(900), "BTC", "0.05")
                    .copy(type = KrakenApiConstants.LEDGER_TYPE_TRANSFER, subtype = "airdrop")
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns
                    listOf(earnReward, earnAllocation, airdrop)

                val rewards = service.getRewardsOverTime(Instant.EPOCH, now)

                rewards.points[0].perAssetUSD.getValue(Asset.BTC)
                    .shouldBeEqualComparingTo(BigDecimal("7500.00"))
                rewards.totalRewardsUSD.shouldBeEqualComparingTo(BigDecimal("7500.00"))
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
                    snapshot(now.plusSeconds(3600), "112000.00", btc = "1.2" to "60000.00", usdBalance = "40000.00")
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
                // Buy & Hold stays 1.0 BTC + 50k USD valued at 60k = 110,000. Actual = 1.2 BTC @ 60k + 40k USD = 112,000.
                comparison.points.last().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("110000.00"))
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
                val queriedTradesTo = mutableListOf<Instant>()
                val queriedLedgersTo = mutableListOf<Instant>()
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
                coEvery { repository.getTradesInRange(any(), any()) } answers {
                    queriedTradesTo += secondArg<Instant>()
                    listOf(trade)
                }
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } answers {
                    queriedLedgersTo += secondArg<Instant>()
                    emptyList()
                }
                coEvery { orderIntentRepository.getKnownRebalancerOrderIdentities(any(), any()) } returns
                    RebalancerOrderIdentities(orderTxids = setOf("TERMINAL-LATE-ORDER"))

                val comparison = service.getRebalancerComparison(Instant.EPOCH, last.plusSeconds(1))

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.confidence shouldBe ComparisonConfidence.RECONCILED
                queriedTradesTo.contains(last.plusMillis(1_000)) shouldBe true
                queriedLedgersTo.contains(last.plusMillis(1_000)) shouldBe true
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

                val queriedTradesFrom = mutableListOf<Instant>()
                val queriedLedgersFrom = mutableListOf<Instant>()
                coEvery { repository.getSnapshotBefore(now) } returns anchor
                coEvery { repository.getSnapshotsInRange(now, now.plusSeconds(3600)) } returns listOf(snap1, snap2)
                coEvery { repository.getTradesInRange(any(), any()) } answers {
                    queriedTradesFrom += firstArg<Instant>()
                    listOf(trade)
                }
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } answers {
                    queriedLedgersFrom += firstArg<Instant>()
                    emptyList()
                }
                coEvery { orderIntentRepository.getKnownRebalancerOrderIdentities(any(), any()) } returns
                    RebalancerOrderIdentities(orderTxids = setOf("O-SKEW"))

                val comparison = service.getRebalancerComparison(now, now.plusSeconds(3600))

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.confidence shouldBe ComparisonConfidence.RECONCILED
                queriedTradesFrom.contains(anchorTime) shouldBe true
                queriedLedgersFrom.contains(anchorTime) shouldBe true
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
                // The legacy boundary row predates the recorded baseline and must not be
                // injected into the passive basket. The recorded BTC holding is still repriced
                // from 50,000 to 50,000 here, so the later 1.1 BTC snapshot is 105,000.
                comparison.points.last().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("105000.00"))
                coVerify(exactly = 1) {
                    repository.getTradesInRange(now.minusMillis(1_000), now.plusSeconds(3600).plusMillis(1_000))
                }
                coVerify(exactly = 1) {
                    ledgerRepository.getLedgersInRange(now.minusMillis(1_000), now.plusSeconds(3600).plusMillis(1_000))
                }
            }
        }

        "getRebalancerComparison_TradeIdentityDoesNotAffectPureBuyAndHold" {
            runTest {
                // Baseline snapshot at T+0 (now)
                val snap1 = snapshot(now, "100000.00", btc = "1.0" to "50000.00")
                // Subsequent snapshot at T+3600 with a higher BTC price, so a replayed trade
                // would visibly drift the synthetic basket.
                val snap2 =
                    snapshot(now.plusSeconds(3600), "111000.00", btc = "1.1" to "60000.00", usdBalance = "45000.00")
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
                // Trade at T+1800 with an order identity that would previously have been resolved
                // against a local order intent. Pure Buy & Hold must not need that classification.
                val trade = TradeRecord(
                    id = 1,
                    pair = "BTCUSD",
                    symbol = "BTC",
                    side = "BUY",
                    timestamp = now.plusSeconds(1800),
                    volume = BigDecimal("0.1"),
                    usdAmount = BigDecimal("5000.00"),
                    success = true,
                    dryRun = false,
                    price = BigDecimal("50000.00"),
                    fee = BigDecimal.ZERO,
                    source = TradeSource.API_FILL,
                    tradeId = "FILL-1",
                    orderTxid = "ORDER-TXID-1",
                    cycleId = "cycle-1",
                    clientOrderId = null,
                )
                coEvery { repository.getTradesInRange(any(), any()) } returns listOf(trade)
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()
                val comparison = service.getRebalancerComparison(Instant.EPOCH, now.plusSeconds(3600))

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.confidence shouldBe ComparisonConfidence.RECONCILED
                // The order identity is reconciliation evidence only; it creates no synthetic
                // trade, so the basket keeps 1.0 BTC repriced to 60,000 plus its cash.
                comparison.points.last().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("110000.00"))
            }
        }

        "getRebalancerComparison_ManualTradeDoesNotAffectBuyAndHold" {
            runTest {
                val snap1 = snapshot(now, "100000.00", btc = "1.0" to "50000.00")
                val snap2 =
                    snapshot(now.plusSeconds(3600), "112000.00", btc = "1.2" to "60000.00", usdBalance = "40000.00")
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
                    source = TradeSource.MANUAL,
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
                // Pure Buy & Hold retains initial portfolio holdings
                comparison.points.last().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("110000.00"))
                comparison.points.last().differenceUSD.shouldBeEqualComparingTo(BigDecimal("2000.00"))
            }
        }

        "getRebalancerComparison_UnknownTradeDoesNotFailOrAffectBuyAndHold" {
            runTest {
                val snap1 = snapshot(now, "100000.00", btc = "1.0" to "50000.00")
                val snap2 =
                    snapshot(now.plusSeconds(3600), "112000.00", btc = "1.2" to "60000.00", usdBalance = "40000.00")
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

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.confidence shouldBe ComparisonConfidence.RECONCILED
                comparison.points.last().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("110000.00"))
                comparison.points.last().differenceUSD.shouldBeEqualComparingTo(BigDecimal("2000.00"))
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

        "getRebalancerComparison evaluates snapshots beyond certified coverage without the stable-horizon gate" {
            runTest {
                val snap1 = snapshot(now, "100000.00", btc = "1.0" to "50000.00")
                val snap2 = snapshot(
                    now.plusSeconds(3600),
                    "100000.00",
                    btc = "1.0" to "50000.00",
                    usdBalance = "50000.00",
                    balancesObservedAt = now.plusSeconds(7200),
                )
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC) } returns
                    (now.epochSecond - 1).toString()
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC) } returns
                    (now.epochSecond - 1).toString()

                val comparison = service.getRebalancerComparison(Instant.EPOCH, now.plusSeconds(7200))

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.baselineTimestamp shouldBe now
                comparison.confidence shouldBe ComparisonConfidence.RECONCILED
            }
        }

        "getRebalancerComparison_IdentifiesBotTradeViaClientOrderId" {
            runTest {
                val snap1 = snapshot(now, "100000.00", btc = "1.0" to "50000.00")
                val snap2 =
                    snapshot(now.plusSeconds(3600), "112000.00", btc = "1.2" to "60000.00", usdBalance = "40000.00")
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
                // Inception-resolution path applies only when no provably recorded anchor exists:
                // unobserved rows never qualify as the passive re-anchor baseline.
                val snap1 = snapshot(now, "100000.00", btc = "1.0" to "50000.00", balancesObservedAt = null)
                val snap2 =
                    snapshot(now.plusSeconds(3600), "110000.00", btc = "1.0" to "60000.00", balancesObservedAt = null)

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
                // Inception-resolution path applies only when no provably recorded anchor exists:
                // unobserved rows never qualify as the passive re-anchor baseline.
                val snap1 = snapshot(now, "100000.00", btc = "1.0" to "50000.00", balancesObservedAt = null)
                val snap2 =
                    snapshot(now.plusSeconds(3600), "110000.00", btc = "1.0" to "60000.00", balancesObservedAt = null)

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

        "getRebalancerComparison_OwnerCryptoDeposit_UsesSharedHistoricalLadderWhenGatewayAvailable" {
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
                val nearContributionSnapshot = snap1.copy(
                    timestamp = tMid.minusSeconds(60),
                    balancesObservedAt = tMid.minusSeconds(60),
                )
                val mockInceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
                coEvery { mockInceptionService.resolveInception() } returns InceptionResolution(
                    inceptionTime = t0,
                    inceptionSnapshot = snap0,
                    isAutoDetected = true,
                )
                coEvery { repository.getSnapshotsInRange(any(), any()) } answers {
                    if (secondArg<Instant>() == tMid) {
                        listOf(nearContributionSnapshot)
                    } else {
                        listOf(snap1, snap2)
                    }
                }
                coEvery { repository.getSnapshotBefore(any()) } returns null
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns
                    listOf(
                        ledgerEvent(
                            ledgerId = "qs-ladder-btc",
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
                                refid = "tx-qs-ladder-btc",
                                txid = "0xladder123",
                                asset = "BTC",
                                amount = BigDecimal("0.50000000"),
                                time = tMid,
                                status = "Success",
                                method = "Bitcoin",
                            ),
                        ),
                    ),
                    krakenService = mockk<KrakenService>(relaxed = true),
                )

                val comparison = serviceWithInception.getRebalancerComparison(now, now.plusSeconds(3600))

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("125000.00")
            }
        }

        "getRebalancerComparison_KrakenGatewayOutageFallsBackToRecordedSnapshots" {
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
                val outageGateway = mockk<KrakenService>(relaxed = true)
                coEvery { outageGateway.getOHLC(any(), any(), any()) } throws
                    IllegalStateException("gateway outage")
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
                            ledgerId = "qs-outage-btc",
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
                                refid = "tx-qs-outage-btc",
                                txid = "0xoutage123",
                                asset = "BTC",
                                amount = BigDecimal("0.50000000"),
                                time = tMid,
                                status = "Success",
                                method = "Bitcoin",
                            ),
                        ),
                    ),
                    krakenService = outageGateway,
                )

                val comparison = serviceWithInception.getRebalancerComparison(now, now.plusSeconds(3600))

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("125000.00")
            }
        }

        "getRebalancerComparison_UnpriceableOwnerCryptoDepositFailsClosed" {
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
                    "100000.00",
                    btc = "1.0" to "50000.00",
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
                            ledgerId = "qs-xlm-deposit",
                            timestamp = tMid,
                            asset = "XLM",
                            amount = "1.00000000",
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
                                refid = "tx-qs-xlm-deposit",
                                txid = "0xxlm123",
                                asset = "XLM",
                                amount = BigDecimal("1.00000000"),
                                time = tMid,
                                status = "Success",
                                method = "Bitcoin",
                            ),
                        ),
                    ),
                )

                val comparison = serviceWithInception.getRebalancerComparison(now, now.plusSeconds(3600))

                comparison.availability shouldBe ComparisonAvailability.UNAVAILABLE
                comparison.unavailableReason shouldBe ComparisonUnavailableReason.MISSING_PRICE

                val outageGateway = mockk<KrakenService>(relaxed = true)
                coEvery { outageGateway.getOHLC(any(), any(), any()) } throws
                    IllegalStateException("historical source outage")
                val outageService = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = mockInceptionService,
                    fundingProvenanceResolver = SimpleFundingProvenanceResolver(
                        deposits = listOf(
                            DepositStatusRecord(
                                refid = "tx-qs-xlm-deposit",
                                txid = "0xxlm123",
                                asset = "XLM",
                                amount = BigDecimal("1.00000000"),
                                time = tMid,
                                status = "Success",
                                method = "Bitcoin",
                            ),
                        ),
                    ),
                    krakenService = outageGateway,
                )

                val outageComparison = outageService.getRebalancerComparison(now, now.plusSeconds(3600))

                outageComparison.availability shouldBe ComparisonAvailability.UNAVAILABLE
                outageComparison.unavailableReason shouldBe ComparisonUnavailableReason.HISTORICAL_PRICE_SOURCE_ERROR
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
                coEvery {
                    repository.getAllSnapshotsInRange(
                        Instant.EPOCH,
                        openEndedRangeEnd,
                    )
                } returns emptyList()

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

        "getRebalancerComparison_ReanchorsAtEarliestRecordedInvestedSnapshot" {
            runTest {
                val anchorTime = Instant.EPOCH.plusSeconds(300)
                val laterTime = anchorTime.plusSeconds(3600)
                val reconstructedAtFloor = snapshot(
                    Instant.EPOCH,
                    "1000.00",
                    btc = "1.0" to "500.00",
                    usdBalance = "500.00",
                    balancesObservedAt = null,
                )
                val recordedAnchor = snapshot(
                    anchorTime,
                    "1000.00",
                    btc = "1.0" to "500.00",
                    usdBalance = "500.00",
                )
                val later = snapshot(
                    laterTime,
                    "1100.00",
                    btc = "1.0" to "600.00",
                    usdBalance = "500.00",
                )
                val mockInceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
                coEvery { mockInceptionService.resolveInception() } returns InceptionResolution(
                    inceptionTime = now.minusSeconds(86400),
                    inceptionSnapshot = null,
                    isAutoDetected = false,
                    confidence = InceptionConfidence.RECOVERY_INCOMPLETE,
                    unavailableReason = ComparisonUnavailableReason.INCEPTION_AMBIGUOUS,
                )
                coEvery { repository.getSnapshotsInRange(anchorTime, laterTime) } returns
                    listOf(recordedAnchor, later)
                coEvery {
                    repository.getAllSnapshotsInRange(
                        Instant.EPOCH,
                        openEndedRangeEnd,
                    )
                } returns listOf(reconstructedAtFloor, recordedAnchor, later)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val serviceWithInception = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = mockInceptionService,
                )

                val comparison = serviceWithInception.getRebalancerComparison(anchorTime, laterTime)

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.baselineTimestamp shouldBe anchorTime
                comparison.points.first().timestamp shouldBe anchorTime
                comparison.points.first().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("1000.00"))
                comparison.points.last().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("1100.00"))
                coVerify(exactly = 1) {
                    repository.getAllSnapshotsInRange(
                        Instant.EPOCH,
                        openEndedRangeEnd,
                    )
                }
            }
        }

        "getRebalancerComparison_SkipsCashInvalidAndPricelessCandidatesForFirstInvestedAnchor" {
            runTest {
                val cashTime = Instant.EPOCH.plusSeconds(1000)
                val invalidTime = Instant.EPOCH.plusSeconds(2000)
                val pricelessTime = Instant.EPOCH.plusSeconds(3000)
                val anchorTime = Instant.EPOCH.plusSeconds(4000)
                val laterTime = anchorTime.plusSeconds(3600)
                val allCash = snapshot(
                    cashTime,
                    "900.00",
                    btc = "0.0" to "450.00",
                    usdBalance = "900.00",
                )
                val invalidBalances = snapshot(
                    invalidTime,
                    "1000.00",
                    btc = "-1.0" to "500.00",
                    usdBalance = "1500.00",
                )
                val pricelessCrypto = snapshot(
                    pricelessTime,
                    "500.00",
                    btc = "1.0" to "0.00",
                    usdBalance = "500.00",
                )
                val investedAnchor = snapshot(
                    anchorTime,
                    "1000.00",
                    btc = "1.0" to "600.00",
                    usdBalance = "400.00",
                )
                val later = snapshot(
                    laterTime,
                    "1000.00",
                    btc = "1.0" to "600.00",
                    usdBalance = "400.00",
                )
                val mockInceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
                coEvery { mockInceptionService.resolveInception() } returns InceptionResolution(
                    inceptionTime = cashTime,
                    inceptionSnapshot = null,
                    isAutoDetected = false,
                    confidence = InceptionConfidence.RECOVERY_INCOMPLETE,
                    unavailableReason = ComparisonUnavailableReason.INCEPTION_AMBIGUOUS,
                )
                coEvery { repository.getSnapshotsInRange(anchorTime, laterTime) } returns
                    listOf(investedAnchor, later)
                coEvery {
                    repository.getAllSnapshotsInRange(
                        Instant.EPOCH,
                        openEndedRangeEnd,
                    )
                } returns listOf(allCash, invalidBalances, pricelessCrypto, investedAnchor, later)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val serviceWithInception = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = mockInceptionService,
                )

                val comparison = serviceWithInception.getRebalancerComparison(anchorTime, laterTime)

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.baselineTimestamp shouldBe anchorTime
                comparison.points.first().timestamp shouldBe anchorTime
                comparison.points.first().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("1000.00"))
            }
        }

        "getRebalancerComparison_WindowStartingAfterGlobalAnchorRediscoversInWindowAnchor" {
            runTest {
                val preWindowAnchorTime = Instant.EPOCH.plusSeconds(100)
                val windowFrom = Instant.EPOCH.plusSeconds(1000)
                val windowAnchorTime = Instant.EPOCH.plusSeconds(2000)
                val laterTime = windowAnchorTime.plusSeconds(3600)
                val preWindowAnchor = snapshot(
                    preWindowAnchorTime,
                    "1000.00",
                    btc = "1.0" to "600.00",
                    usdBalance = "400.00",
                )
                val windowAnchor = snapshot(
                    windowAnchorTime,
                    "2000.00",
                    btc = "1.0" to "1200.00",
                    usdBalance = "800.00",
                )
                val later = snapshot(
                    laterTime,
                    "2200.00",
                    btc = "1.0" to "1400.00",
                    usdBalance = "800.00",
                )
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(windowAnchor, later)
                coEvery {
                    repository.getAllSnapshotsInRange(
                        Instant.EPOCH,
                        openEndedRangeEnd,
                    )
                } returns listOf(preWindowAnchor, windowAnchor, later)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val comparison = service.getRebalancerComparison(windowFrom, laterTime)

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.baselineTimestamp shouldBe windowAnchorTime
                comparison.points.first().timestamp shouldBe windowAnchorTime
                comparison.points.first().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("2000.00"))
                comparison.points.last().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("2200.00"))
            }
        }

        "getRebalancerComparison_AnchorsFirstMaterialPositionEvenWhenCashDominated" {
            runTest {
                val dustTime = Instant.EPOCH.plusSeconds(100)
                val materialTime = Instant.EPOCH.plusSeconds(200)
                val laterTime = materialTime.plusSeconds(3600)
                val dust = snapshot(
                    dustTime,
                    "1000.00",
                    btc = "0.000002" to "89000.00",
                    usdBalance = "999.822",
                )
                val material = snapshot(
                    materialTime,
                    "1000.00",
                    btc = "0.001" to "100000.00",
                    usdBalance = "900.00",
                )
                val later = snapshot(
                    laterTime,
                    "1010.00",
                    btc = "0.001" to "110000.00",
                    usdBalance = "900.00",
                )
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(dust, material, later)
                coEvery {
                    repository.getAllSnapshotsInRange(
                        Instant.EPOCH,
                        openEndedRangeEnd,
                    )
                } returns listOf(dust, material, later)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val comparison = service.getRebalancerComparison(dustTime, laterTime)

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.baselineTimestamp shouldBe materialTime
                comparison.points.first().timestamp shouldBe materialTime
                comparison.points.first().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("1000.00"))
                comparison.points.last().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("1010.00"))
            }
        }

        "getRebalancerComparison_MalformedSupportedTradeFailsClosed" {
            runTest {
                val materialTime = Instant.EPOCH.plusSeconds(200)
                val tradeTime = materialTime.plusSeconds(600)
                val laterTime = materialTime.plusSeconds(3600)
                val material = snapshot(
                    materialTime,
                    "2000.00",
                    btc = "1.0" to "1200.00",
                    usdBalance = "800.00",
                )
                val later = snapshot(
                    laterTime,
                    "2200.00",
                    btc = "1.0" to "1400.00",
                    usdBalance = "800.00",
                )
                // Replayable BTCUSD pair but malformed economics: must fail closed, never a zero fill.
                val malformed = TradeRecord(
                    id = 1,
                    pair = "BTCUSD",
                    symbol = "BTC",
                    side = "BUY",
                    timestamp = tradeTime,
                    volume = BigDecimal.ZERO,
                    usdAmount = BigDecimal.ZERO,
                    success = true,
                    dryRun = false,
                    price = BigDecimal.ZERO,
                    fee = BigDecimal.ZERO,
                    source = TradeSource.API_FILL,
                    tradeId = "MALFORMED-1",
                    orderTxid = "O-MALFORMED-1",
                    cycleId = null,
                    clientOrderId = null,
                    hasValidVolume = false,
                )
                // Dry-run, failed, and out-of-window rows are not economic evidence: the scan
                // must skip them (repositories return recorded rows; the scan owns the window).
                val dryRunTrade = malformed.copy(
                    id = 2,
                    dryRun = true,
                    tradeId = "MALFORMED-2",
                    orderTxid = "O-MALFORMED-2",
                )
                val failedTrade = malformed.copy(
                    id = 3,
                    success = false,
                    tradeId = "MALFORMED-3",
                    orderTxid = "O-MALFORMED-3",
                )
                val preWindowTrade = malformed.copy(
                    id = 4,
                    timestamp = materialTime.minusSeconds(60),
                    tradeId = "MALFORMED-4",
                    orderTxid = "O-MALFORMED-4",
                )
                val postWindowTrade = malformed.copy(
                    id = 5,
                    timestamp = laterTime.plusSeconds(60),
                    tradeId = "MALFORMED-5",
                    orderTxid = "O-MALFORMED-5",
                )
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(material, later)
                coEvery {
                    repository.getAllSnapshotsInRange(
                        Instant.EPOCH,
                        openEndedRangeEnd,
                    )
                } returns listOf(material, later)
                coEvery { repository.getTradesInRange(any(), any()) } returns
                    listOf(dryRunTrade, failedTrade, preWindowTrade, malformed, postWindowTrade)
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val comparison = service.getRebalancerComparison(materialTime, laterTime)

                comparison.availability shouldBe ComparisonAvailability.UNAVAILABLE
                comparison.unavailableReason shouldBe ComparisonUnavailableReason.UNSUPPORTED_TRADE
                comparison.unavailableAt shouldBe tradeTime
            }
        }

        "getRebalancerComparison_SkipsRecordedPricelessCryptoWithNoPricedExposure" {
            runTest {
                val materialTime = Instant.EPOCH.plusSeconds(200)
                val pricelessTime = materialTime.plusSeconds(1)
                val laterTime = materialTime.plusSeconds(3600)
                val priceless = snapshot(
                    pricelessTime,
                    "1000.00",
                    btc = "1.0" to "0",
                    usdBalance = "1000.00",
                )
                val material = snapshot(
                    materialTime,
                    "1000.00",
                    btc = "0.001" to "100000.00",
                    usdBalance = "900.00",
                )
                val later = snapshot(
                    laterTime,
                    "1010.00",
                    btc = "0.001" to "110000.00",
                    usdBalance = "900.00",
                )
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(material, later)
                coEvery {
                    repository.getAllSnapshotsInRange(
                        Instant.EPOCH,
                        openEndedRangeEnd,
                    )
                } returns listOf(material, priceless, later)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val comparison = service.getRebalancerComparison(materialTime, laterTime)

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.baselineTimestamp shouldBe materialTime
                comparison.points.first().timestamp shouldBe materialTime
                comparison.points.first().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("1000.00"))
                comparison.points.last().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("1010.00"))
            }
        }

        "getRebalancerComparison_AnchorsSnapshotWithZeroBalanceRowFirst" {
            runTest {
                val materialTime = Instant.EPOCH.plusSeconds(200)
                val laterTime = materialTime.plusSeconds(3600)
                // Row order must not affect anchor eligibility: a zero-balance row listed before
                // the invested rows still anchors when the state as a whole is invested.
                val base = snapshot(
                    materialTime,
                    "910.00",
                    btc = "0.0" to "100000.00",
                    usdBalance = "900.00",
                )
                val btcKey = base.assets.keys.single { Asset.normalizeLedgerAsset(it).uppercase() == "BTC" }
                val usdKey = base.assets.keys.single { Asset.normalizeLedgerAsset(it).uppercase() == Asset.USD }
                val ethRow = TestFixtures.assetSnapshot(
                    symbol = "ETH",
                    balance = BigDecimal("0.1"),
                    price = BigDecimal("100"),
                    valueUSD = BigDecimal("10.00"),
                    targetPercent = BigDecimal.ZERO,
                )
                val orderedAssets = linkedMapOf(
                    btcKey to base.assets.getValue(btcKey),
                    "ETH" to ethRow,
                    usdKey to base.assets.getValue(usdKey),
                )
                val zeroFirst = base.copy(assets = orderedAssets)
                val laterBase = snapshot(
                    laterTime,
                    "910.00",
                    btc = "0.0" to "100000.00",
                    usdBalance = "900.00",
                )
                val later = laterBase.copy(assets = orderedAssets)
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(zeroFirst, later)
                coEvery {
                    repository.getAllSnapshotsInRange(
                        Instant.EPOCH,
                        openEndedRangeEnd,
                    )
                } returns listOf(zeroFirst, later)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val comparison = service.getRebalancerComparison(materialTime, laterTime)

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.baselineTimestamp shouldBe materialTime
                comparison.points.first().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("910.00"))
                comparison.points.last().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("910.00"))
            }
        }

        "getRebalancerComparison_SkipsZeroStateSnapshotWithNoHoldings" {
            runTest {
                val materialTime = Instant.EPOCH.plusSeconds(200)
                val zeroTime = materialTime.plusSeconds(1)
                val laterTime = materialTime.plusSeconds(3600)
                val zeroState = snapshot(
                    zeroTime,
                    "0.00",
                    btc = "0" to "100000.00",
                    usdBalance = "0.00",
                )
                val material = snapshot(
                    materialTime,
                    "1000.00",
                    btc = "0.001" to "100000.00",
                    usdBalance = "900.00",
                )
                val later = snapshot(
                    laterTime,
                    "1010.00",
                    btc = "0.001" to "110000.00",
                    usdBalance = "900.00",
                )
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(material, later)
                coEvery {
                    repository.getAllSnapshotsInRange(
                        Instant.EPOCH,
                        openEndedRangeEnd,
                    )
                } returns listOf(material, zeroState, later)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val comparison = service.getRebalancerComparison(materialTime, laterTime)

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.baselineTimestamp shouldBe materialTime
                comparison.points.first().timestamp shouldBe materialTime
                comparison.points.first().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("1000.00"))
                comparison.points.last().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("1010.00"))
            }
        }

        "getRebalancerComparison_SkipsSnapshotWhoseTotalContradictsItsHoldings" {
            runTest {
                val materialTime = Instant.EPOCH.plusSeconds(200)
                val corruptTime = materialTime.plusSeconds(1)
                val laterTime = materialTime.plusSeconds(3600)
                val corruptTotal = snapshot(
                    corruptTime,
                    "0.00",
                    btc = "0.001" to "100000.00",
                    usdBalance = "900.00",
                )
                val material = snapshot(
                    materialTime,
                    "1000.00",
                    btc = "0.001" to "100000.00",
                    usdBalance = "900.00",
                )
                val later = snapshot(
                    laterTime,
                    "1010.00",
                    btc = "0.001" to "110000.00",
                    usdBalance = "900.00",
                )
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(material, later)
                coEvery {
                    repository.getAllSnapshotsInRange(
                        Instant.EPOCH,
                        openEndedRangeEnd,
                    )
                } returns listOf(material, corruptTotal, later)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val comparison = service.getRebalancerComparison(materialTime, laterTime)

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.baselineTimestamp shouldBe materialTime
                comparison.points.first().timestamp shouldBe materialTime
                comparison.points.first().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("1000.00"))
                comparison.points.last().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("1010.00"))
            }
        }

        "getRebalancerComparison_SkipsSnapshotWithNegativeBalanceHolding" {
            runTest {
                val materialTime = Instant.EPOCH.plusSeconds(200)
                val corruptTime = materialTime.plusSeconds(1)
                val laterTime = materialTime.plusSeconds(3600)
                val negativeHolding = corruptHoldingSnapshot(
                    timestamp = corruptTime,
                    symbol = "ATOM",
                    balance = "-5",
                    price = "1",
                    valueUSD = "-5",
                )
                val material = snapshot(
                    materialTime,
                    "1000.00",
                    btc = "0.001" to "100000.00",
                    usdBalance = "900.00",
                )
                val later = snapshot(
                    laterTime,
                    "1010.00",
                    btc = "0.001" to "110000.00",
                    usdBalance = "900.00",
                )
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(material, later)
                coEvery {
                    repository.getAllSnapshotsInRange(
                        Instant.EPOCH,
                        openEndedRangeEnd,
                    )
                } returns listOf(material, negativeHolding, later)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val comparison = service.getRebalancerComparison(materialTime, laterTime)

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.baselineTimestamp shouldBe materialTime
                comparison.points.first().timestamp shouldBe materialTime
                comparison.points.first().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("1000.00"))
                comparison.points.last().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("1010.00"))
            }
        }

        "getRebalancerComparison_SkipsSnapshotWithNegativeValueHolding" {
            runTest {
                val materialTime = Instant.EPOCH.plusSeconds(200)
                val corruptTime = materialTime.plusSeconds(1)
                val laterTime = materialTime.plusSeconds(3600)
                val negativeValue = corruptHoldingSnapshot(
                    timestamp = corruptTime,
                    symbol = "ATOM",
                    balance = "5",
                    price = "1",
                    valueUSD = "-5",
                )
                val material = snapshot(
                    materialTime,
                    "1000.00",
                    btc = "0.001" to "100000.00",
                    usdBalance = "900.00",
                )
                val later = snapshot(
                    laterTime,
                    "1010.00",
                    btc = "0.001" to "110000.00",
                    usdBalance = "900.00",
                )
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(material, later)
                coEvery {
                    repository.getAllSnapshotsInRange(
                        Instant.EPOCH,
                        openEndedRangeEnd,
                    )
                } returns listOf(material, negativeValue, later)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val comparison = service.getRebalancerComparison(materialTime, laterTime)

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.baselineTimestamp shouldBe materialTime
                comparison.points.first().timestamp shouldBe materialTime
                comparison.points.first().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("1000.00"))
                comparison.points.last().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("1010.00"))
            }
        }

        "getRebalancerComparison_SkipsSnapshotWithPhantomValueAndZeroBalances" {
            runTest {
                val materialTime = Instant.EPOCH.plusSeconds(200)
                val corruptTime = materialTime.plusSeconds(1)
                val laterTime = materialTime.plusSeconds(3600)
                val phantomValue = PortfolioSnapshot(
                    timestamp = corruptTime,
                    totalValueUSD = BigDecimal("100.00"),
                    assets = mapOf(
                        Asset.BTC to TestFixtures.assetSnapshot(
                            symbol = Asset.BTC,
                            balance = BigDecimal.ZERO,
                            price = BigDecimal("100000"),
                            valueUSD = BigDecimal("100.00"),
                            targetPercent = BigDecimal.ZERO,
                        ),
                    ),
                    actions = emptyList(),
                    drawdownPercent = BigDecimal.ZERO,
                    fiatDeploymentPercent = BigDecimal.ZERO,
                    effectiveUsdTargetPercent = BigDecimal.ZERO,
                    balancesObservedAt = corruptTime,
                )
                val material = snapshot(
                    materialTime,
                    "1000.00",
                    btc = "0.001" to "100000.00",
                    usdBalance = "900.00",
                )
                val later = snapshot(
                    laterTime,
                    "1010.00",
                    btc = "0.001" to "110000.00",
                    usdBalance = "900.00",
                )
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(material, later)
                coEvery {
                    repository.getAllSnapshotsInRange(
                        Instant.EPOCH,
                        openEndedRangeEnd,
                    )
                } returns listOf(material, phantomValue, later)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val comparison = service.getRebalancerComparison(materialTime, laterTime)

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.baselineTimestamp shouldBe materialTime
                comparison.points.first().timestamp shouldBe materialTime
                comparison.points.first().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("1000.00"))
                comparison.points.last().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("1010.00"))
            }
        }

        "getRebalancerComparison_SkipsProvablyRecordedAllZeroSnapshot" {
            runTest {
                val materialTime = Instant.EPOCH.plusSeconds(200)
                val zeroTime = materialTime.plusSeconds(1)
                val laterTime = materialTime.plusSeconds(3600)
                // Provably recorded (observation strictly before the row instant) yet
                // holding nothing: reaches the balance gate and must be skipped, so a
                // recorded-but-empty row can never become the benchmark anchor.
                val allZero = PortfolioSnapshot(
                    timestamp = zeroTime,
                    totalValueUSD = BigDecimal.ZERO,
                    assets = mapOf(
                        Asset.BTC to TestFixtures.assetSnapshot(
                            symbol = Asset.BTC,
                            balance = BigDecimal.ZERO,
                            price = BigDecimal("100000"),
                            valueUSD = BigDecimal.ZERO,
                            targetPercent = BigDecimal.ZERO,
                        ),
                    ),
                    actions = emptyList(),
                    drawdownPercent = BigDecimal.ZERO,
                    fiatDeploymentPercent = BigDecimal.ZERO,
                    effectiveUsdTargetPercent = BigDecimal.ZERO,
                    balancesObservedAt = zeroTime.minusMillis(1),
                )
                val material = snapshot(
                    materialTime,
                    "1000.00",
                    btc = "0.001" to "100000.00",
                    usdBalance = "900.00",
                )
                val later = snapshot(
                    laterTime,
                    "1010.00",
                    btc = "0.001" to "110000.00",
                    usdBalance = "900.00",
                )
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(material, later)
                coEvery {
                    repository.getAllSnapshotsInRange(
                        Instant.EPOCH,
                        openEndedRangeEnd,
                    )
                } returns listOf(material, allZero, later)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val comparison = service.getRebalancerComparison(materialTime, laterTime)

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.baselineTimestamp shouldBe materialTime
                comparison.points.first().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("1000.00"))
                comparison.points.last().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("1010.00"))
            }
        }

        "getRebalancerComparison_AnchorsSnapshotWithZeroRowBeforePositiveRows" {
            runTest {
                val materialTime = Instant.EPOCH.plusSeconds(200)
                val laterTime = materialTime.plusSeconds(3600)
                val base = snapshot(
                    materialTime,
                    "900.00",
                    btc = "0.0" to "100000.00",
                    usdBalance = "900.00",
                )
                // A zero-balance row listed before invested rows must not affect discovery:
                // row order is a serialization accident, not portfolio meaning.
                val zeroFirst = base.copy(
                    totalValueUSD = BigDecimal("910.00"),
                    assets = linkedMapOf(
                        Asset.BTC to TestFixtures.assetSnapshot(
                            symbol = Asset.BTC,
                            balance = BigDecimal.ZERO,
                            price = BigDecimal("100000"),
                            valueUSD = BigDecimal.ZERO,
                            targetPercent = BigDecimal.ZERO,
                        ),
                        "ETH" to TestFixtures.assetSnapshot(
                            symbol = "ETH",
                            balance = BigDecimal("0.1"),
                            price = BigDecimal("100.00"),
                            valueUSD = BigDecimal("10.00"),
                            targetPercent = BigDecimal.ZERO,
                        ),
                        Asset.USD to TestFixtures.assetSnapshot(
                            symbol = Asset.USD,
                            balance = BigDecimal("900.00"),
                            price = BigDecimal.ONE,
                            valueUSD = BigDecimal("900.00"),
                            targetPercent = BigDecimal.ZERO,
                        ),
                    ),
                )
                val later = base.copy(
                    timestamp = laterTime,
                    totalValueUSD = BigDecimal("910.00"),
                    assets = zeroFirst.assets,
                )
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(zeroFirst, later)
                coEvery {
                    repository.getAllSnapshotsInRange(
                        Instant.EPOCH,
                        openEndedRangeEnd,
                    )
                } returns listOf(zeroFirst, later)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val comparison = service.getRebalancerComparison(materialTime, laterTime)

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.baselineTimestamp shouldBe materialTime
                comparison.points.first().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("910.00"))
            }
        }

        "getRebalancerComparison_SkipsSnapshotWithPriceValueInconsistency" {
            runTest {
                val materialTime = Instant.EPOCH.plusSeconds(200)
                val corruptTime = materialTime.plusSeconds(1)
                val laterTime = materialTime.plusSeconds(3600)
                val inconsistentPrice = corruptHoldingSnapshot(
                    timestamp = corruptTime,
                    symbol = "ATOM",
                    balance = "600",
                    price = "0",
                    valueUSD = "600",
                )
                val material = snapshot(
                    materialTime,
                    "1000.00",
                    btc = "0.001" to "100000.00",
                    usdBalance = "900.00",
                )
                val later = snapshot(
                    laterTime,
                    "1010.00",
                    btc = "0.001" to "110000.00",
                    usdBalance = "900.00",
                )
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(material, later)
                coEvery {
                    repository.getAllSnapshotsInRange(
                        Instant.EPOCH,
                        openEndedRangeEnd,
                    )
                } returns listOf(material, inconsistentPrice, later)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val comparison = service.getRebalancerComparison(materialTime, laterTime)

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.baselineTimestamp shouldBe materialTime
                comparison.points.first().timestamp shouldBe materialTime
                comparison.points.first().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("1000.00"))
                comparison.points.last().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("1010.00"))
            }
        }

        "getRebalancerComparison_DoesNotAnchorBeforeHistoryBoundEvenWhenEarlierHistoryIsClean" {
            runTest {
                val floor = Instant.EPOCH
                val cleanEarly = snapshot(
                    floor.minusSeconds(86400),
                    "900.00",
                    btc = "1.0" to "450.00",
                    usdBalance = "450.00",
                )
                val anchorTime = floor.plusSeconds(300)
                val laterTime = anchorTime.plusSeconds(3600)
                val recordedAnchor = snapshot(
                    anchorTime,
                    "1000.00",
                    btc = "1.0" to "500.00",
                    usdBalance = "500.00",
                )
                val later = snapshot(
                    laterTime,
                    "1100.00",
                    btc = "1.0" to "600.00",
                    usdBalance = "500.00",
                )
                val mockInceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
                coEvery { mockInceptionService.resolveInception() } returns InceptionResolution(
                    inceptionTime = cleanEarly.timestamp,
                    inceptionSnapshot = cleanEarly,
                    isAutoDetected = false,
                    confidence = InceptionConfidence.CONFIDENT,
                )
                coEvery { repository.getSnapshotsInRange(anchorTime, laterTime) } returns
                    listOf(recordedAnchor, later)
                coEvery {
                    repository.getAllSnapshotsInRange(
                        floor,
                        openEndedRangeEnd,
                    )
                } returns listOf(recordedAnchor, later)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val serviceWithInception = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = mockInceptionService,
                )

                val comparison = serviceWithInception.getRebalancerComparison(anchorTime, laterTime)

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.baselineTimestamp shouldBe anchorTime
                comparison.points.first().timestamp shouldBe anchorTime
                comparison.points.first().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("1000.00"))
                comparison.points.last().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("1100.00"))
                coVerify(exactly = 1) {
                    repository.getAllSnapshotsInRange(
                        floor,
                        openEndedRangeEnd,
                    )
                }
            }
        }

        "getRebalancerComparison_ExcludesLegacyDerivedRowsThroughTheirMetadataSecond" {
            runTest {
                // A non-zero reconstruction base: epoch-second metadata uses 0/blank as the
                // never-reconstructed sentinel, so the legacy window needs a real base instant.
                val base = Instant.parse("2026-01-01T00:00:00Z")
                val derivedTime = base.plusMillis(500)
                val anchorTime = base.plusSeconds(2)
                val laterTime = anchorTime.plusSeconds(3600)
                val legacyDerived = snapshot(
                    derivedTime,
                    "999.00",
                    btc = "1.0" to "499.00",
                    usdBalance = "500.00",
                )
                val recordedAnchor = snapshot(
                    anchorTime,
                    "1000.00",
                    btc = "1.0" to "500.00",
                    usdBalance = "500.00",
                )
                val later = snapshot(
                    laterTime,
                    "1100.00",
                    btc = "1.0" to "600.00",
                    usdBalance = "500.00",
                )
                val metadata = mapOf(
                    SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION to "legacy-reconstruction",
                    SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_START_EPOCH_SEC to base.epochSecond.toString(),
                    SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_THROUGH_EPOCH_SEC to base.epochSecond.toString(),
                )
                val mockInceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
                coEvery { mockInceptionService.resolveInception() } returns InceptionResolution(
                    inceptionTime = now.minusSeconds(86400),
                    inceptionSnapshot = null,
                    isAutoDetected = false,
                    confidence = InceptionConfidence.RECOVERY_INCOMPLETE,
                    unavailableReason = ComparisonUnavailableReason.INCEPTION_AMBIGUOUS,
                )
                coEvery { repository.getSyncMetadata(any()) } answers { metadata[firstArg()] }
                coEvery { repository.getSnapshotsInRange(anchorTime, laterTime) } returns
                    listOf(recordedAnchor, later)
                coEvery {
                    repository.getAllSnapshotsInRange(Instant.EPOCH, openEndedRangeEnd)
                } returns listOf(legacyDerived, recordedAnchor, later)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val serviceWithInception = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = mockInceptionService,
                    nowProvider = { now },
                )

                val comparison = serviceWithInception.getRebalancerComparison(anchorTime, laterTime)

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.baselineTimestamp shouldBe anchorTime
                comparison.points.first().buyAndHoldValueUSD
                    .shouldBeEqualComparingTo(BigDecimal("1000.00"))
            }
        }

        "getRebalancerComparison_restart_reproduces_the_same_reanchor_result" {
            runTest {
                val floor = Instant.EPOCH
                val anchorTime = floor.plusSeconds(300)
                val laterTime = anchorTime.plusSeconds(3600)
                val liveAnchor = snapshot(
                    anchorTime,
                    "1000.00",
                    btc = "1.0" to "500.00",
                    usdBalance = "500.00",
                    balancesObservedAt = anchorTime.minusMillis(800),
                )
                val later = snapshot(
                    laterTime,
                    "1100.00",
                    btc = "1.0" to "600.00",
                    usdBalance = "500.00",
                    balancesObservedAt = laterTime.minusMillis(700),
                )
                val metadata = mapOf(SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION to "7")
                val mockInceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
                coEvery { mockInceptionService.resolveInception() } returns InceptionResolution(
                    inceptionTime = now.minusSeconds(86400),
                    inceptionSnapshot = null,
                    isAutoDetected = false,
                    confidence = InceptionConfidence.RECOVERY_INCOMPLETE,
                    unavailableReason = ComparisonUnavailableReason.INCEPTION_AMBIGUOUS,
                )
                coEvery { repository.getSyncMetadata(any()) } answers { metadata[firstArg()] }
                coEvery { repository.getSnapshotsInRange(anchorTime, laterTime) } returns
                    listOf(liveAnchor, later)
                coEvery { repository.getAllSnapshotsInRange(floor, openEndedRangeEnd) } returns
                    listOf(liveAnchor, later)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                fun newService() = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = mockInceptionService,
                    nowProvider = { now },
                )

                val beforeRestart = newService().getRebalancerComparison(anchorTime, laterTime)
                val afterRestart = newService().getRebalancerComparison(anchorTime, laterTime)

                afterRestart shouldBe beforeRestart
                afterRestart.availability shouldBe ComparisonAvailability.AVAILABLE
                afterRestart.baselineTimestamp shouldBe anchorTime
            }
        }

        "getRebalancerComparison_reconstructedRowsAnchorWhenTheReconstructionIsCurrent" {
            runTest {
                val floor = Instant.EPOCH
                val anchorTime = floor.plusSeconds(300)
                val laterTime = anchorTime.plusSeconds(3600)
                val reconstructedAnchor = snapshot(
                    anchorTime,
                    "1000.00",
                    btc = "1.0" to "500.00",
                    usdBalance = "500.00",
                    balancesObservedAt = null,
                )
                val later = snapshot(
                    laterTime,
                    "1100.00",
                    btc = "1.0" to "600.00",
                    usdBalance = "500.00",
                    balancesObservedAt = null,
                )
                val metadata = mapOf(
                    SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION to
                        TradeHistoryReconstructionService.CURRENT_RECONSTRUCTION_VERSION,
                    SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_LEDGER_COVERAGE_VERSION to
                        LedgersSyncService.CURRENT_LEDGER_COVERAGE_VERSION,
                    SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_TRADE_COVERAGE_VERSION to
                        TradeHistorySyncService.CURRENT_TRADE_COVERAGE_VERSION,
                    SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_START_EPOCH_SEC to floor.epochSecond.toString(),
                    SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_THROUGH_EPOCH_SEC to laterTime.epochSecond.toString(),
                )
                val mockInceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
                coEvery { mockInceptionService.resolveInception() } returns InceptionResolution(
                    inceptionTime = now.minusSeconds(86400),
                    inceptionSnapshot = null,
                    isAutoDetected = false,
                    confidence = InceptionConfidence.RECOVERY_INCOMPLETE,
                    unavailableReason = ComparisonUnavailableReason.INCEPTION_AMBIGUOUS,
                )
                coEvery { repository.getSyncMetadata(any()) } answers { metadata[firstArg()] }
                coEvery { repository.getSnapshotsInRange(anchorTime, laterTime) } returns
                    listOf(reconstructedAnchor, later)
                coEvery { repository.getAllSnapshotsInRange(floor, openEndedRangeEnd) } returns
                    listOf(reconstructedAnchor, later)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val comparison = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = mockInceptionService,
                    nowProvider = { now },
                ).getRebalancerComparison(anchorTime, laterTime)

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.baselineTimestamp shouldBe anchorTime
                comparison.points.first().buyAndHoldValueUSD
                    .shouldBeEqualComparingTo(BigDecimal("1000.00"))
            }
        }

        "getRebalancerComparison_reconstructedRowsStayExcludedWhenTheReconstructionIsOutdated" {
            runTest {
                val floor = Instant.EPOCH
                val anchorTime = floor.plusSeconds(600)
                val laterTime = anchorTime.plusSeconds(3600)
                val reconstructedRow = snapshot(
                    floor.plusSeconds(300),
                    "900.00",
                    btc = "1.0" to "400.00",
                    usdBalance = "500.00",
                    balancesObservedAt = null,
                )
                val recordedAnchor = snapshot(
                    anchorTime,
                    "1000.00",
                    btc = "1.0" to "500.00",
                    usdBalance = "500.00",
                    balancesObservedAt = anchorTime.minusMillis(800),
                )
                val later = snapshot(
                    laterTime,
                    "1100.00",
                    btc = "1.0" to "600.00",
                    usdBalance = "500.00",
                    balancesObservedAt = laterTime.minusMillis(700),
                )
                val metadata = mapOf(
                    SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION to "17",
                    SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_LEDGER_COVERAGE_VERSION to
                        LedgersSyncService.CURRENT_LEDGER_COVERAGE_VERSION,
                    SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_TRADE_COVERAGE_VERSION to
                        TradeHistorySyncService.CURRENT_TRADE_COVERAGE_VERSION,
                    SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_START_EPOCH_SEC to floor.epochSecond.toString(),
                    SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_THROUGH_EPOCH_SEC to
                        floor.plusSeconds(100).epochSecond.toString(),
                )
                val mockInceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
                coEvery { mockInceptionService.resolveInception() } returns InceptionResolution(
                    inceptionTime = now.minusSeconds(86400),
                    inceptionSnapshot = null,
                    isAutoDetected = false,
                    confidence = InceptionConfidence.RECOVERY_INCOMPLETE,
                    unavailableReason = ComparisonUnavailableReason.INCEPTION_AMBIGUOUS,
                )
                coEvery { repository.getSyncMetadata(any()) } answers { metadata[firstArg()] }
                coEvery { repository.getSnapshotsInRange(anchorTime, laterTime) } returns
                    listOf(recordedAnchor, later)
                coEvery { repository.getAllSnapshotsInRange(floor, openEndedRangeEnd) } returns
                    listOf(reconstructedRow, recordedAnchor, later)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val comparison = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = mockInceptionService,
                    nowProvider = { now },
                ).getRebalancerComparison(anchorTime, laterTime)

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.baselineTimestamp shouldBe anchorTime
            }
        }

        "getRebalancerComparison_reconstructedRowsOutsideTheRecordedWindowStayExcluded" {
            runTest {
                val floor = Instant.EPOCH
                val anchorTime = floor.plusSeconds(600)
                val laterTime = anchorTime.plusSeconds(3600)
                val legacyDerivedRow = snapshot(
                    floor.plusSeconds(50),
                    "800.00",
                    btc = "1.0" to "300.00",
                    usdBalance = "500.00",
                )
                val reconstructedRow = snapshot(
                    floor.plusSeconds(300),
                    "900.00",
                    btc = "1.0" to "400.00",
                    usdBalance = "500.00",
                    balancesObservedAt = null,
                )
                val recordedAnchor = snapshot(
                    anchorTime,
                    "1000.00",
                    btc = "1.0" to "500.00",
                    usdBalance = "500.00",
                    balancesObservedAt = anchorTime.minusMillis(800),
                )
                val later = snapshot(
                    laterTime,
                    "1100.00",
                    btc = "1.0" to "600.00",
                    usdBalance = "500.00",
                    balancesObservedAt = laterTime.minusMillis(700),
                )
                val metadata = mapOf(
                    SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION to
                        TradeHistoryReconstructionService.CURRENT_RECONSTRUCTION_VERSION,
                    SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_LEDGER_COVERAGE_VERSION to
                        LedgersSyncService.CURRENT_LEDGER_COVERAGE_VERSION,
                    SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_TRADE_COVERAGE_VERSION to
                        TradeHistorySyncService.CURRENT_TRADE_COVERAGE_VERSION,
                    SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_START_EPOCH_SEC to floor.epochSecond.toString(),
                    SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_THROUGH_EPOCH_SEC to
                        floor.plusSeconds(100).epochSecond.toString(),
                )
                val mockInceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
                coEvery { mockInceptionService.resolveInception() } returns InceptionResolution(
                    inceptionTime = now.minusSeconds(86400),
                    inceptionSnapshot = null,
                    isAutoDetected = false,
                    confidence = InceptionConfidence.RECOVERY_INCOMPLETE,
                    unavailableReason = ComparisonUnavailableReason.INCEPTION_AMBIGUOUS,
                )
                coEvery { repository.getSyncMetadata(any()) } answers { metadata[firstArg()] }
                coEvery { repository.getSnapshotsInRange(anchorTime, laterTime) } returns
                    listOf(recordedAnchor, later)
                coEvery { repository.getAllSnapshotsInRange(floor, openEndedRangeEnd) } returns
                    listOf(legacyDerivedRow, reconstructedRow, recordedAnchor, later)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val comparison = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = mockInceptionService,
                    nowProvider = { now },
                ).getRebalancerComparison(anchorTime, laterTime)

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.baselineTimestamp shouldBe anchorTime
            }
        }

        "getRebalancerComparison_anchorSearchRejectsUnqualifiedRows" {
            runTest {
                val floor = Instant.EPOCH
                val anchorTime = floor.plusSeconds(300)
                val laterTime = anchorTime.plusSeconds(3600)

                fun custom(
                    timestamp: Instant,
                    totalValueUSD: String,
                    rows: Map<String, Triple<String, String, String>>,
                    observedAt: Instant?,
                ): PortfolioSnapshot = PortfolioSnapshot(
                    timestamp = timestamp,
                    totalValueUSD = BigDecimal(totalValueUSD),
                    assets = rows.mapValues { (symbol, row) ->
                        TestFixtures.assetSnapshot(
                            symbol = symbol,
                            balance = BigDecimal(row.first),
                            price = BigDecimal(row.second),
                            valueUSD = BigDecimal(row.third),
                            targetPercent = BigDecimal.ZERO,
                        )
                    },
                    actions = emptyList(),
                    drawdownPercent = BigDecimal.ZERO,
                    fiatDeploymentPercent = BigDecimal.ZERO,
                    effectiveUsdTargetPercent = BigDecimal.ZERO,
                    balancesObservedAt = observedAt,
                )

                val btcRow = Triple("1.00000000", "500.00", "500.00")
                val usdRow = Triple("500.00", "1.00", "500.00")
                val rejected = listOf(
                    custom(
                        floor.minusSeconds(1),
                        "1000.00",
                        mapOf("BTC" to btcRow, "USD" to usdRow),
                        floor.minusSeconds(2),
                    ),
                    custom(
                        now.plusSeconds(3600),
                        "1000.00",
                        mapOf("BTC" to btcRow, "USD" to usdRow),
                        now.plusSeconds(3500),
                    ),
                    custom(floor.plusSeconds(10), "0", mapOf("BTC" to btcRow, "USD" to usdRow), floor.plusSeconds(9)),
                    custom(
                        floor.plusSeconds(20),
                        "0",
                        mapOf("BTC" to Triple("0", "500.00", "0"), "USD" to Triple("0", "1.00", "0")),
                        floor.plusSeconds(19),
                    ),
                    custom(
                        floor.plusSeconds(30),
                        "1000.00",
                        mapOf("BTC" to btcRow, "USD" to Triple("-1.00", "1.00", "-1.00")),
                        floor.plusSeconds(29),
                    ),
                    custom(
                        floor.plusSeconds(40),
                        "1000.00",
                        mapOf("BTC" to btcRow, "USD" to Triple("1.00", "1.00", "-1.00")),
                        floor.plusSeconds(39),
                    ),
                    custom(
                        floor.plusSeconds(50),
                        "1500.00",
                        mapOf("BTC" to btcRow, "XRP" to Triple("2.00", "0", "0")),
                        floor.plusSeconds(49),
                    ),
                    custom(floor.plusSeconds(80), "1500.00", mapOf("BTC" to btcRow, "USD" to usdRow), null),
                )
                // Rows that pass every validity filter but sit after the anchor must not displace it.
                val zeroBalanceRow = custom(
                    anchorTime.plusSeconds(120),
                    "1000.00",
                    mapOf("BTC" to btcRow, "USD" to usdRow, "XRP" to Triple("0", "0", "0")),
                    anchorTime.plusSeconds(119),
                )
                val zeroPricedUsdRow = custom(
                    anchorTime.plusSeconds(180),
                    "1000.00",
                    mapOf("BTC" to btcRow, "USD" to Triple("500.00", "0", "500.00")),
                    anchorTime.plusSeconds(179),
                )
                val liveAnchor = snapshot(
                    anchorTime,
                    "1000.00",
                    btc = "1.0" to "500.00",
                    usdBalance = "500.00",
                    balancesObservedAt = anchorTime.minusMillis(800),
                )
                val later = snapshot(
                    laterTime,
                    "1100.00",
                    btc = "1.0" to "600.00",
                    usdBalance = "500.00",
                    balancesObservedAt = laterTime.minusMillis(700),
                )
                val metadata = mapOf(SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION to "7")
                val mockInceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
                coEvery { mockInceptionService.resolveInception() } returns InceptionResolution(
                    inceptionTime = now.minusSeconds(86400),
                    inceptionSnapshot = null,
                    isAutoDetected = false,
                    confidence = InceptionConfidence.RECOVERY_INCOMPLETE,
                    unavailableReason = ComparisonUnavailableReason.INCEPTION_AMBIGUOUS,
                )
                coEvery { repository.getSyncMetadata(any()) } answers { metadata[firstArg()] }
                coEvery { repository.getSnapshotsInRange(anchorTime, laterTime) } returns
                    listOf(liveAnchor, zeroBalanceRow, zeroPricedUsdRow, later)
                coEvery { repository.getAllSnapshotsInRange(floor, openEndedRangeEnd) } returns
                    rejected + listOf(liveAnchor, zeroBalanceRow, zeroPricedUsdRow, later)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val service = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = mockInceptionService,
                    nowProvider = { now },
                )

                val comparison = service.getRebalancerComparison(anchorTime, laterTime)

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.baselineTimestamp shouldBe anchorTime
                comparison.points.first().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("1000.00")
            }
        }

        "getRebalancerComparison_reconstructionMetadataVariantsStayConsistent" {
            runTest {
                val floor = Instant.EPOCH
                val anchorTime = floor.plusSeconds(300)
                val laterTime = anchorTime.plusSeconds(3600)
                val liveAnchor = snapshot(
                    anchorTime,
                    "1000.00",
                    btc = "1.0" to "500.00",
                    usdBalance = "500.00",
                    balancesObservedAt = anchorTime.minusMillis(800),
                )
                val later = snapshot(
                    laterTime,
                    "1100.00",
                    btc = "1.0" to "600.00",
                    usdBalance = "500.00",
                    balancesObservedAt = laterTime.minusMillis(700),
                )
                var metadata: Map<String, String> = emptyMap()
                val mockInceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
                coEvery { mockInceptionService.resolveInception() } returns InceptionResolution(
                    inceptionTime = now.minusSeconds(86400),
                    inceptionSnapshot = null,
                    isAutoDetected = false,
                    confidence = InceptionConfidence.RECOVERY_INCOMPLETE,
                    unavailableReason = ComparisonUnavailableReason.INCEPTION_AMBIGUOUS,
                )
                coEvery { repository.getSyncMetadata(any()) } answers { metadata[firstArg()] }
                coEvery { repository.getSnapshotsInRange(anchorTime, laterTime) } returns listOf(liveAnchor, later)
                coEvery { repository.getAllSnapshotsInRange(floor, openEndedRangeEnd) } returns
                    listOf(liveAnchor, later)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val service = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = mockInceptionService,
                    nowProvider = { now },
                )

                val versionKey = SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION
                val throughKey = SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_THROUGH_EPOCH_SEC
                val startKey = SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_START_EPOCH_SEC
                val variants = listOf(
                    emptyMap(),
                    mapOf(throughKey to "abc"),
                    mapOf(versionKey to "", throughKey to "0"),
                    mapOf(versionKey to "7"),
                    mapOf(versionKey to "7", throughKey to "abc"),
                    mapOf(versionKey to "7", throughKey to "0"),
                    mapOf(versionKey to "7", throughKey to "100", startKey to "-5"),
                    mapOf(versionKey to "7", throughKey to "100", startKey to "200"),
                    mapOf(versionKey to "7", throughKey to "9223372036854775807", startKey to "100"),
                    mapOf(
                        versionKey to TradeHistoryReconstructionService.CURRENT_RECONSTRUCTION_VERSION,
                        SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_LEDGER_COVERAGE_VERSION to
                            LedgersSyncService.CURRENT_LEDGER_COVERAGE_VERSION,
                        SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_TRADE_COVERAGE_VERSION to
                            TradeHistorySyncService.CURRENT_TRADE_COVERAGE_VERSION,
                        throughKey to "100",
                        startKey to "50",
                    ),
                )

                variants.forEach { variant ->
                    metadata = variant
                    val comparison = service.getRebalancerComparison(anchorTime, laterTime)
                    comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                    comparison.baselineTimestamp shouldBe anchorTime
                }
            }
        }

        "getRebalancerComparison_truncatedInceptionWithoutRecordedAnchorStaysUnavailable" {
            runTest {
                val floor = Instant.EPOCH
                val anchorTime = floor.plusSeconds(300)
                val laterTime = anchorTime.plusSeconds(3600)
                val liveAnchor = snapshot(
                    anchorTime,
                    "1000.00",
                    btc = "1.0" to "500.00",
                    usdBalance = "500.00",
                    balancesObservedAt = anchorTime.minusMillis(800),
                )
                val later = snapshot(
                    laterTime,
                    "1100.00",
                    btc = "1.0" to "600.00",
                    usdBalance = "500.00",
                    balancesObservedAt = laterTime.minusMillis(700),
                )
                val mockInceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
                coEvery { mockInceptionService.resolveInception() } returns InceptionResolution(
                    inceptionTime = now.minusSeconds(86400),
                    inceptionSnapshot = null,
                    isAutoDetected = false,
                    confidence = InceptionConfidence.TRUNCATED,
                    unavailableReason = ComparisonUnavailableReason.INCEPTION_HISTORY_TRUNCATED,
                )
                coEvery { repository.getSyncMetadata(any()) } returns null
                coEvery { repository.getAllSnapshotsInRange(floor, openEndedRangeEnd) } returns emptyList()
                coEvery { repository.getSnapshotsInRange(anchorTime, laterTime) } returns listOf(liveAnchor, later)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val service = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = mockInceptionService,
                    nowProvider = { now },
                )
                val comparison = service.getRebalancerComparison(anchorTime, laterTime)

                comparison.availability shouldBe ComparisonAvailability.UNAVAILABLE
                comparison.unavailableReason shouldBe ComparisonUnavailableReason.INCEPTION_HISTORY_TRUNCATED
            }
        }

        "getRebalancerComparison_anchorSearchSkipsCandidatesWithoutPositiveHoldings" {
            runTest {
                val floor = Instant.EPOCH
                val anchorTime = floor.plusSeconds(300)
                val laterTime = anchorTime.plusSeconds(3600)
                val emptyCandidate = snapshot(
                    floor.plusSeconds(10),
                    "1000.00",
                    btc = "0" to "500.00",
                    usdBalance = "0",
                    balancesObservedAt = floor.plusSeconds(9),
                )
                val liveAnchor = snapshot(
                    anchorTime,
                    "1000.00",
                    btc = "1.0" to "500.00",
                    usdBalance = "500.00",
                    balancesObservedAt = anchorTime.minusMillis(800),
                )
                val later = snapshot(
                    laterTime,
                    "1000.00",
                    btc = "1.0" to "600.00",
                    usdBalance = "500.00",
                    balancesObservedAt = laterTime.minusMillis(700),
                )
                val metadata = mapOf(SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION to "7")
                val mockInceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
                coEvery { mockInceptionService.resolveInception() } returns InceptionResolution(
                    inceptionTime = now.minusSeconds(86400),
                    inceptionSnapshot = null,
                    isAutoDetected = false,
                    confidence = InceptionConfidence.RECOVERY_INCOMPLETE,
                    unavailableReason = ComparisonUnavailableReason.INCEPTION_AMBIGUOUS,
                )
                coEvery { repository.getSyncMetadata(any()) } answers { metadata[firstArg()] }
                coEvery { repository.getAllSnapshotsInRange(floor, openEndedRangeEnd) } returns
                    listOf(emptyCandidate, liveAnchor, later)
                coEvery { repository.getSnapshotsInRange(anchorTime, laterTime) } returns listOf(liveAnchor, later)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val service = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = mockInceptionService,
                    nowProvider = { now },
                )
                val comparison = service.getRebalancerComparison(anchorTime, laterTime)

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.baselineTimestamp shouldBe anchorTime
                comparison.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("1100.00")
            }
        }

        "getRebalancerComparison_staleRowInsideTheDisplayedWindowFailsClosed" {
            runTest {
                val floor = Instant.EPOCH
                val anchorTime = floor.plusSeconds(300)
                val laterTime = anchorTime.plusSeconds(3600)
                val liveAnchor = snapshot(
                    anchorTime,
                    "1000.00",
                    btc = "1.0" to "500.00",
                    usdBalance = "500.00",
                    balancesObservedAt = anchorTime.minusMillis(800),
                )
                val unprovableRow = snapshot(
                    anchorTime.plusSeconds(120),
                    "1000.00",
                    btc = "1.0" to "500.00",
                    usdBalance = "500.00",
                )
                val later = snapshot(
                    laterTime,
                    "1100.00",
                    btc = "1.0" to "600.00",
                    usdBalance = "500.00",
                    balancesObservedAt = laterTime.minusMillis(700),
                )
                val metadata = mapOf(SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION to "7")
                val mockInceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
                coEvery { mockInceptionService.resolveInception() } returns InceptionResolution(
                    inceptionTime = now.minusSeconds(86400),
                    inceptionSnapshot = null,
                    isAutoDetected = false,
                    confidence = InceptionConfidence.RECOVERY_INCOMPLETE,
                    unavailableReason = ComparisonUnavailableReason.INCEPTION_AMBIGUOUS,
                )
                coEvery { repository.getSyncMetadata(any()) } answers { metadata[firstArg()] }
                coEvery { repository.getAllSnapshotsInRange(floor, openEndedRangeEnd) } returns
                    listOf(liveAnchor, unprovableRow, later)
                coEvery { repository.getSnapshotsInRange(anchorTime, laterTime) } returns
                    listOf(liveAnchor, unprovableRow, later)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val service = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = mockInceptionService,
                    nowProvider = { now },
                )
                val comparison = service.getRebalancerComparison(anchorTime, laterTime)

                comparison.availability shouldBe ComparisonAvailability.UNAVAILABLE
                comparison.unavailableReason shouldBe ComparisonUnavailableReason.HISTORICAL_COVERAGE_GAP
            }
        }

        "getRebalancerComparison_windowMetadataShapesStayConsistent" {
            runTest {
                val floor = Instant.EPOCH
                val anchorTime = floor.plusSeconds(300)
                val laterTime = anchorTime.plusSeconds(3600)
                val liveAnchor = snapshot(
                    anchorTime,
                    "1000.00",
                    btc = "1.0" to "500.00",
                    usdBalance = "500.00",
                    balancesObservedAt = anchorTime.minusMillis(800),
                )
                val later = snapshot(
                    laterTime,
                    "1100.00",
                    btc = "1.0" to "600.00",
                    usdBalance = "500.00",
                    balancesObservedAt = laterTime.minusMillis(700),
                )
                var metadata: Map<String, String> = emptyMap()
                val mockInceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
                coEvery { mockInceptionService.resolveInception() } returns InceptionResolution(
                    inceptionTime = now.minusSeconds(86400),
                    inceptionSnapshot = null,
                    isAutoDetected = false,
                    confidence = InceptionConfidence.RECOVERY_INCOMPLETE,
                    unavailableReason = ComparisonUnavailableReason.INCEPTION_AMBIGUOUS,
                )
                coEvery { repository.getSyncMetadata(any()) } answers { metadata[firstArg()] }
                coEvery { repository.getAllSnapshotsInRange(floor, openEndedRangeEnd) } returns
                    listOf(liveAnchor, later)
                coEvery { repository.getSnapshotsInRange(anchorTime, laterTime) } returns listOf(liveAnchor, later)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val service = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = mockInceptionService,
                    nowProvider = { now },
                )
                val shapes = listOf(
                    mapOf(SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_THROUGH_EPOCH_SEC to "abc"),
                    mapOf(
                        SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION to "7",
                        SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_THROUGH_EPOCH_SEC to "100",
                    ),
                    mapOf(
                        SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION to "",
                        SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_THROUGH_EPOCH_SEC to "0",
                    ),
                )
                shapes.forEach { shape ->
                    metadata = shape
                    val comparison = service.getRebalancerComparison(anchorTime, laterTime)
                    comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                    comparison.baselineTimestamp shouldBe anchorTime
                }
            }
        }

        "getRebalancerComparison_truncatedInceptionStillUsesTheRecordedAnchor" {
            runTest {
                val floor = Instant.EPOCH
                val anchorTime = floor.plusSeconds(300)
                val laterTime = anchorTime.plusSeconds(3600)
                val liveAnchor = snapshot(
                    anchorTime,
                    "1000.00",
                    btc = "1.0" to "500.00",
                    usdBalance = "500.00",
                    balancesObservedAt = anchorTime.minusMillis(800),
                )
                val later = snapshot(
                    laterTime,
                    "1100.00",
                    btc = "1.0" to "600.00",
                    usdBalance = "500.00",
                    balancesObservedAt = laterTime.minusMillis(700),
                )
                val mockInceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
                coEvery { mockInceptionService.resolveInception() } returns InceptionResolution(
                    inceptionTime = now.minusSeconds(86400),
                    inceptionSnapshot = null,
                    isAutoDetected = false,
                    confidence = InceptionConfidence.TRUNCATED,
                    unavailableReason = ComparisonUnavailableReason.INCEPTION_HISTORY_TRUNCATED,
                )
                coEvery { repository.getSyncMetadata(any()) } returns null
                coEvery { repository.getSnapshotsInRange(anchorTime, laterTime) } returns listOf(liveAnchor, later)
                coEvery { repository.getAllSnapshotsInRange(floor, openEndedRangeEnd) } returns
                    listOf(liveAnchor, later)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val service = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = mockInceptionService,
                    nowProvider = { now },
                )
                val comparison = service.getRebalancerComparison(anchorTime, laterTime)

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.baselineTimestamp shouldBe anchorTime
                comparison.points.first().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("1000.00")
            }
        }

        "getRebalancerComparison_UnclassifiableLegacyWindowStillFindsLiveRecordedAnchor" {
            runTest {
                val floor = Instant.EPOCH
                val derivedTime = floor.plusSeconds(1)
                val anchorTime = floor.plusSeconds(300)
                val laterTime = anchorTime.plusSeconds(3600)
                val legacyDerived = snapshot(
                    derivedTime,
                    "999.00",
                    btc = "1.0" to "499.00",
                    usdBalance = "500.00",
                )
                val liveAnchor = snapshot(
                    anchorTime,
                    "1000.00",
                    btc = "1.0" to "500.00",
                    usdBalance = "500.00",
                    balancesObservedAt = anchorTime.minusMillis(800),
                )
                val later = snapshot(
                    laterTime,
                    "1100.00",
                    btc = "1.0" to "600.00",
                    usdBalance = "500.00",
                    balancesObservedAt = laterTime.minusMillis(700),
                )
                // Legacy v7 writers persisted a reconstruction marker without the window keys, so
                // derived rows can only be excluded through the live-observation signature.
                val metadata = mapOf(
                    SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION to "7",
                )
                val mockInceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
                coEvery { mockInceptionService.resolveInception() } returns InceptionResolution(
                    inceptionTime = now.minusSeconds(86400),
                    inceptionSnapshot = null,
                    isAutoDetected = false,
                    confidence = InceptionConfidence.RECOVERY_INCOMPLETE,
                    unavailableReason = ComparisonUnavailableReason.INCEPTION_AMBIGUOUS,
                )
                coEvery { repository.getSyncMetadata(any()) } answers { metadata[firstArg()] }
                coEvery { repository.getSnapshotsInRange(anchorTime, laterTime) } returns
                    listOf(liveAnchor, later)
                coEvery { repository.getAllSnapshotsInRange(floor, openEndedRangeEnd) } returns
                    listOf(legacyDerived, liveAnchor, later)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val serviceWithInception = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = mockInceptionService,
                    nowProvider = { now },
                )

                val comparison = serviceWithInception.getRebalancerComparison(anchorTime, laterTime)

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.baselineTimestamp shouldBe anchorTime
                comparison.points.first().buyAndHoldValueUSD
                    .shouldBeEqualComparingTo(BigDecimal("1000.00"))
            }
        }

        "getRebalancerComparison_LiveSignatureRowInsideReconstructionWindowIsRecorded" {
            runTest {
                val floor = Instant.EPOCH
                val windowEnd = floor.plusSeconds(10)
                val anchorTime = floor.plusSeconds(5)
                val laterTime = floor.plusSeconds(3600)
                val derivedInsideWindow = snapshot(
                    floor.plusSeconds(1),
                    "999.00",
                    btc = "1.0" to "499.00",
                    usdBalance = "500.00",
                )
                val liveAnchor = snapshot(
                    anchorTime,
                    "1000.00",
                    btc = "1.0" to "500.00",
                    usdBalance = "500.00",
                    balancesObservedAt = anchorTime.minusMillis(500),
                )
                val later = snapshot(
                    laterTime,
                    "1100.00",
                    btc = "1.0" to "600.00",
                    usdBalance = "500.00",
                )
                val metadata = mapOf(
                    SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION to "7",
                    SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_START_EPOCH_SEC to floor.epochSecond.toString(),
                    SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_THROUGH_EPOCH_SEC to windowEnd.epochSecond.toString(),
                )
                val mockInceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
                coEvery { mockInceptionService.resolveInception() } returns InceptionResolution(
                    inceptionTime = now.minusSeconds(86400),
                    inceptionSnapshot = null,
                    isAutoDetected = false,
                    confidence = InceptionConfidence.RECOVERY_INCOMPLETE,
                    unavailableReason = ComparisonUnavailableReason.INCEPTION_AMBIGUOUS,
                )
                coEvery { repository.getSyncMetadata(any()) } answers { metadata[firstArg()] }
                coEvery { repository.getSnapshotsInRange(anchorTime, laterTime) } returns
                    listOf(liveAnchor, later)
                coEvery { repository.getAllSnapshotsInRange(floor, openEndedRangeEnd) } returns
                    listOf(derivedInsideWindow, liveAnchor, later)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val serviceWithInception = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = mockInceptionService,
                    nowProvider = { now },
                )

                val comparison = serviceWithInception.getRebalancerComparison(anchorTime, laterTime)

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.baselineTimestamp shouldBe anchorTime
                comparison.points.first().buyAndHoldValueUSD
                    .shouldBeEqualComparingTo(BigDecimal("1000.00"))
            }
        }

        "getRebalancerComparison_PreservesPostAnchorFailureReason" {
            runTest {
                val oldInception = now.minusSeconds(90 * 86_400L)
                val anchorTime = Instant.EPOCH.plusSeconds(300)
                val anchor = snapshot(anchorTime, "1000.00", btc = "1.0" to "500.00", usdBalance = "500.00")
                val later = snapshot(
                    anchorTime.plusSeconds(3600),
                    "1500.00",
                    btc = "2.0" to "500.00",
                    usdBalance = "500.00",
                )
                val mockInceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
                coEvery { mockInceptionService.resolveInception() } returns InceptionResolution(
                    inceptionTime = oldInception,
                    inceptionSnapshot = null,
                    isAutoDetected = false,
                    confidence = InceptionConfidence.RECOVERY_INCOMPLETE,
                    unavailableReason = ComparisonUnavailableReason.INCEPTION_AMBIGUOUS,
                )
                coEvery { repository.getSnapshotsInRange(anchorTime, anchorTime.plusSeconds(3600)) } returns
                    listOf(anchor, later)
                coEvery { repository.getAllSnapshotsInRange(any(), openEndedRangeEnd) } returns
                    listOf(anchor, later)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val serviceWithInception = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = mockInceptionService,
                )

                val comparison = serviceWithInception.getRebalancerComparison(anchorTime, later.timestamp)

                comparison.availability shouldBe ComparisonAvailability.UNAVAILABLE
                comparison.unavailableReason shouldBe ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE
                comparison.baselineTimestamp shouldBe anchorTime
            }
        }

        "getRebalancerComparison_UnavailableBaseline_ProposesEarliestVerifiedLaterStart" {
            runTest {
                val t0 = now
                val t1 = now.plusSeconds(3600)
                val t2 = now.plusSeconds(7200)
                val snap0 = snapshot(t0, "90000.00", btc = "1.0" to "40000.00")
                val snap1 = snapshot(t1, "100000.00", btc = "1.0" to "50000.00")
                val snap2 = snapshot(t2, "110000.00", btc = "1.0" to "60000.00")

                val mockInceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
                coEvery { mockInceptionService.resolveInception() } returns InceptionResolution(
                    inceptionTime = t0,
                    inceptionSnapshot = null,
                    isAutoDetected = false,
                    confidence = InceptionConfidence.RECOVERY_INCOMPLETE,
                    unavailableReason = ComparisonUnavailableReason.INCEPTION_BASELINE_UNAVAILABLE,
                )
                coEvery { repository.getSnapshotsInRange(t0, t2) } returns listOf(snap0, snap1, snap2)
                coEvery { repository.getSnapshotsInRange(t0, openEndedRangeEnd) } returns listOf(snap0, snap1, snap2)
                coEvery { repository.getSnapshotBefore(any()) } returns snap0
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val serviceWithInception = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = mockInceptionService,
                )

                val comparison = serviceWithInception.getRebalancerComparison(t0, t2)

                comparison.availability shouldBe ComparisonAvailability.UNAVAILABLE
                comparison.unavailableReason shouldBe ComparisonUnavailableReason.INCEPTION_BASELINE_UNAVAILABLE
                comparison.proposedBaselineTimestamp shouldBe t1
                comparison.proposalSearchStatus shouldBe ComparisonProposalStatus.VERIFIED
            }
        }

        "getRebalancerComparison_Proposal_IsIndependentOfDisplayWindow" {
            runTest {
                val t0 = now
                val t1 = now.plusSeconds(3600)
                val t2 = now.plusSeconds(7200)
                val snap0 = snapshot(t0, "90000.00", btc = "1.0" to "40000.00")
                val snap1 = snapshot(t1, "100000.00", btc = "1.0" to "50000.00")
                val snap2 = snapshot(t2, "110000.00", btc = "1.0" to "60000.00")
                val snap3 = snapshot(t2.plusSeconds(3600), "110000.00", btc = "1.0" to "60000.00")

                val mockInceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
                coEvery { mockInceptionService.resolveInception() } returns InceptionResolution(
                    inceptionTime = t0,
                    inceptionSnapshot = null,
                    isAutoDetected = false,
                    confidence = InceptionConfidence.RECOVERY_INCOMPLETE,
                    unavailableReason = ComparisonUnavailableReason.INCEPTION_BASELINE_UNAVAILABLE,
                )
                coEvery {
                    repository.getSnapshotsInRange(t2.minusSeconds(60), t2.plusSeconds(3600))
                } returns listOf(snap2, snap3)
                coEvery { repository.getSnapshotsInRange(t0, openEndedRangeEnd) } returns
                    listOf(snap0, snap1, snap2, snap3)
                coEvery { repository.getSnapshotBefore(any()) } returns snap0
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val serviceWithInception = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = mockInceptionService,
                )

                // Display window excludes snap1 entirely, yet the verified
                // proposal is still its timestamp: the scan reads the full
                // retained snapshot range, not the displayed zoom range.
                val comparison =
                    serviceWithInception.getRebalancerComparison(t2.minusSeconds(60), t2.plusSeconds(3600))

                comparison.availability shouldBe ComparisonAvailability.UNAVAILABLE
                comparison.proposedBaselineTimestamp shouldBe t1
                comparison.proposalSearchStatus shouldBe ComparisonProposalStatus.VERIFIED
            }
        }

        "getRebalancerComparison_PendingRecovery_ProposesNothing" {
            runTest {
                val t0 = now
                val t1 = now.plusSeconds(3600)
                val snap0 = snapshot(t0, "90000.00", btc = "1.0" to "40000.00")
                val snap1 = snapshot(t1, "100000.00", btc = "1.0" to "50000.00")

                val mockInceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
                coEvery { mockInceptionService.resolveInception() } returns InceptionResolution(
                    inceptionTime = t0,
                    inceptionSnapshot = null,
                    isAutoDetected = false,
                    confidence = InceptionConfidence.RECOVERY_INCOMPLETE,
                    unavailableReason = ComparisonUnavailableReason.INCEPTION_RECOVERY_INCOMPLETE,
                )
                coEvery { repository.getSnapshotsInRange(t0, t1) } returns listOf(snap0, snap1)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val serviceWithInception = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = mockInceptionService,
                )

                val comparison = serviceWithInception.getRebalancerComparison(t0, t1)

                comparison.availability shouldBe ComparisonAvailability.UNAVAILABLE
                comparison.unavailableReason shouldBe ComparisonUnavailableReason.INCEPTION_RECOVERY_INCOMPLETE
                comparison.proposedBaselineTimestamp.shouldBeNull()
                comparison.proposalSearchStatus.shouldBeNull()
            }
        }

        "findVerifiedLaterComparisonStart_ReturnsEarliestPassingAnchorOverFullRange" {
            runTest {
                val t0 = now
                val t1 = now.plusSeconds(3600)
                val t2 = now.plusSeconds(7200)
                val snap0 = snapshot(t0, "90000.00", btc = "1.0" to "40000.00")
                val snap1 = snapshot(t1, "100000.00", btc = "1.0" to "50000.00")
                val snap2 = snapshot(t2, "110000.00", btc = "1.0" to "60000.00")

                val mockInceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
                coEvery { repository.getAllSnapshotsInRange(t0, openEndedRangeEnd) } returns
                    listOf(snap0, snap1, snap2)
                // Keep the sampled chart query empty so this regression proves that the proposal
                // scanner uses the full-retention repository path.
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns emptyList()
                coEvery { repository.getSnapshotBefore(any()) } returns snap0
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val serviceWithInception = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = mockInceptionService,
                )

                serviceWithInception.findVerifiedLaterComparisonStart(t0) shouldBe t1
                coVerify(exactly = 1) { repository.getAllSnapshotsInRange(t0, openEndedRangeEnd) }
            }
        }

        "findVerifiedLaterComparisonStart_DoesNotTreatFutureStartAsCoverageGap" {
            runTest {
                val futureStart = now.plusSeconds(3600)
                val serviceWithClock = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    nowProvider = { now },
                )
                coEvery { repository.getAllSnapshotsInRange(futureStart, openEndedRangeEnd) } returns emptyList()

                serviceWithClock.findVerifiedLaterComparisonStart(futureStart).shouldBeNull()
            }
        }

        "findVerifiedLaterComparisonStart_DoesNotFlagMissingSnapshotsWhenStartIsWithinRetention" {
            runTest {
                val recentStart = now.minusSeconds(3600)
                val recentSnapshot = snapshot(recentStart, "90000.00", btc = "1.0" to "40000.00")
                val latestSnapshot = snapshot(now, "100000.00", btc = "1.0" to "50000.00")
                val serviceWithClock = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    nowProvider = { now },
                )
                coEvery { repository.getAllSnapshotsInRange(recentStart, openEndedRangeEnd) } returns
                    listOf(recentSnapshot, latestSnapshot)
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns emptyList()
                coEvery { repository.getSnapshotBefore(any()) } returns recentSnapshot
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                serviceWithClock.findVerifiedLaterComparisonStart(recentStart).shouldBeNull()
            }
        }

        "findVerifiedLaterComparisonStart_DoesNotFlagAnEmptyHistoricalRangeAsCoverageGap" {
            runTest {
                val historicalStart = now.minusSeconds(91 * 86_400L)
                val serviceWithClock = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    nowProvider = { now },
                )
                coEvery { repository.getAllSnapshotsInRange(historicalStart, openEndedRangeEnd) } returns emptyList()

                serviceWithClock.findVerifiedLaterComparisonStart(historicalStart).shouldBeNull()
            }
        }

        "findVerifiedLaterComparisonStart_FailsClosedForAGapCrossingRetentionBoundary" {
            runTest {
                val historicalStart = now.minusSeconds(100 * 86_400L)
                val retentionCutoff = now.minusSeconds(90 * 86_400L)
                val beforeStrategyStart = snapshot(
                    historicalStart.minusSeconds(3600),
                    "80000.00",
                    btc = "1.0" to "30000.00",
                )
                val oldSnapshot = snapshot(
                    retentionCutoff.minusSeconds(3600),
                    "90000.00",
                    btc = "1.0" to "40000.00",
                )
                val retainedSnapshot = snapshot(
                    retentionCutoff.plusSeconds(25 * 3600L),
                    "100000.00",
                    btc = "1.0" to "50000.00",
                )
                val afterNow = snapshot(
                    now.plusSeconds(3600),
                    "110000.00",
                    btc = "1.0" to "60000.00",
                )
                val serviceWithClock = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    nowProvider = { now },
                )
                coEvery { repository.getAllSnapshotsInRange(historicalStart, openEndedRangeEnd) } returns listOf(
                    beforeStrategyStart,
                    historicalStart.let {
                        snapshot(it, "85000.00", btc = "1.0" to "35000.00")
                    },
                    oldSnapshot,
                    retainedSnapshot,
                    afterNow,
                )

                serviceWithClock.findVerifiedLaterComparisonStart(historicalStart).shouldBeNull()
            }
        }

        "findVerifiedLaterComparisonStart_FailsClosedWhenFirstRetainedSnapshotStartsAfterTheBoundary" {
            runTest {
                val historicalStart = now.minusSeconds(100 * 86_400L)
                val retentionCutoff = now.minusSeconds(90 * 86_400L)
                val firstRetainedSnapshot = snapshot(
                    retentionCutoff.plusSeconds(25 * 3600L),
                    "100000.00",
                    btc = "1.0" to "50000.00",
                )
                val latestSnapshot = snapshot(
                    now,
                    "110000.00",
                    btc = "1.0" to "60000.00",
                )
                val serviceWithClock = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    nowProvider = { now },
                )
                coEvery { repository.getAllSnapshotsInRange(historicalStart, openEndedRangeEnd) } returns listOf(
                    firstRetainedSnapshot,
                    latestSnapshot,
                )

                serviceWithClock.findVerifiedLaterComparisonStart(historicalStart).shouldBeNull()
            }
        }

        "findVerifiedLaterComparisonStart advances past duplicate-millisecond candidates" {
            runTest {
                val duplicateTime = now.plusSeconds(3600)
                val candidates = (1..9).map {
                    snapshot(duplicateTime, "100000.00", btc = "1.0" to "50000.00")
                }
                val metadata = mutableMapOf<String, String>()
                coEvery { repository.getSyncMetadata(any()) } coAnswers { metadata[firstArg()] }
                coEvery { repository.setSyncMetadataAtomically(any()) } coAnswers {
                    metadata.putAll(firstArg())
                }
                coEvery { repository.getAllSnapshotsInRange(now, openEndedRangeEnd) } returns candidates
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns emptyList()
                coEvery { repository.getSnapshotBefore(any()) } returns null
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns listOf(
                    ledgerEvent(
                        ledgerId = "blocking-deposit",
                        timestamp = duplicateTime,
                        asset = Asset.BTC,
                        amount = "0.1",
                        type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    ),
                )

                val serviceWithInception = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = null,
                )

                serviceWithInception.findVerifiedLaterComparisonStart(now).shouldBeNull()
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_STATUS] shouldBe
                    ComparisonProposalStatus.INCOMPLETE.name
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_CURSOR_EPOCH_MS] shouldBe
                    "${duplicateTime.toEpochMilli()}:8"

                serviceWithInception.findVerifiedLaterComparisonStart(now).shouldBeNull()
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_STATUS] shouldBe
                    ComparisonProposalStatus.EXHAUSTED.name
            }
        }

        "findVerifiedLaterComparisonStart retries after transient provenance preparation failure" {
            runTest {
                val t0 = now
                val t1 = now.plusSeconds(3600)
                val t2 = now.plusSeconds(7200)
                val baseline = snapshot(t0, "90000.00", btc = "1.0" to "40000.00")
                val later = snapshot(t1, "100000.00", btc = "1.0" to "50000.00")
                val final = snapshot(t2, "110000.00", btc = "1.0" to "60000.00")
                val metadata = mutableMapOf<String, String>()
                var provenanceAvailable = false
                val provenanceResolver = object : FundingProvenanceResolver {
                    override fun resolve(event: LedgerEvent): FundingEvidence = FundingEvidence.UNRESOLVED

                    override suspend fun prepare(events: Collection<LedgerEvent>): FundingProvenanceResolver =
                        if (provenanceAvailable) {
                            SimpleFundingProvenanceResolver()
                        } else {
                            FundingProvenanceResolver.unavailable(
                                FundingProvenanceFailure(
                                    reason = FundingProvenanceFailureReason.REQUEST_FAILED,
                                    message = "temporary funding evidence failure",
                                ),
                            )
                        }
                }
                coEvery { repository.getSyncMetadata(any()) } coAnswers { metadata[firstArg()] }
                coEvery { repository.setSyncMetadataAtomically(any()) } coAnswers {
                    metadata.putAll(firstArg())
                }
                coEvery { repository.getAllSnapshotsInRange(t0, openEndedRangeEnd) } returns listOf(later, final)
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns emptyList()
                coEvery { repository.getSnapshotBefore(any()) } returns baseline
                coEvery { repository.getSnapshotId(t1, 0) } returns 77
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val serviceWithInception = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = null,
                    fundingProvenanceResolver = provenanceResolver,
                )

                serviceWithInception.findVerifiedLaterComparisonStart(t0).shouldBeNull()
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_STATUS] shouldBe
                    ComparisonProposalStatus.INCOMPLETE.name

                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_STATUS] =
                    ComparisonProposalStatus.VERIFIED.name
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_SNAPSHOT_ID] = "77"
                serviceWithInception.findVerifiedLaterComparisonStart(t0) shouldBe t1
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_STATUS] shouldBe
                    ComparisonProposalStatus.VERIFIED.name

                val verifiedCursor = requireNotNull(
                    metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_CURSOR_EPOCH_MS],
                )
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_CURSOR_EPOCH_MS] = "not-a-cursor"
                serviceWithInception.findVerifiedLaterComparisonStart(t0).shouldBeNull()
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_STATUS] shouldBe
                    ComparisonProposalStatus.INCOMPLETE.name

                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_STATUS] =
                    ComparisonProposalStatus.VERIFIED.name
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_CURSOR_EPOCH_MS] = verifiedCursor
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_SNAPSHOT_ID] = "not-a-snapshot-id"
                serviceWithInception.findVerifiedLaterComparisonStart(t0).shouldBeNull()
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_STATUS] shouldBe
                    ComparisonProposalStatus.INCOMPLETE.name

                provenanceAvailable = true
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_STATUS] =
                    ComparisonProposalStatus.VERIFIED.name
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_CURSOR_EPOCH_MS] = verifiedCursor
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_SNAPSHOT_ID] = "77"
                serviceWithInception.findVerifiedLaterComparisonStart(t0) shouldBe t1
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_STATUS] shouldBe
                    ComparisonProposalStatus.VERIFIED.name
            }
        }

        "getRebalancerComparison_ValuesStakingContributionThroughSnapshotPrices" {
            runTest {
                val t0 = now
                val t1 = now.plusSeconds(3600)
                val baseline = snapshot(t0, "100000.00", btc = "1.0" to "50000.00")
                val later = snapshot(t1, "105000.00", btc = "1.1" to "50000.00")
                val mockInceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
                coEvery { mockInceptionService.resolveInception() } returns
                    InceptionResolution(t0, baseline, isAutoDetected = false)
                coEvery { repository.getSnapshotsInRange(t0, t1) } returns listOf(baseline, later)
                coEvery { repository.getSnapshotBefore(any()) } returns baseline
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns
                    listOf(ledgerEvent("L1", t1, "BTC", "0.1"))

                val serviceWithInception = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = mockInceptionService,
                )

                val comparison = serviceWithInception.getRebalancerComparison(t0, t1)

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.points.size shouldBe 2
            }
        }

        "findVerifiedLaterComparisonStart_DoesNotSkipPotentiallyValidEarlierAnchor" {
            runTest {
                val t0 = now
                val t1 = now.plusSeconds(3600)
                val t2 = now.plusSeconds(7200)
                val t3 = now.plusSeconds(10800)
                val t4 = now.plusSeconds(14400)
                val snap0 = snapshot(t0, "90000.00", btc = "1.0" to "40000.00")
                val snap1 = snapshot(t1, "130000.00", btc = "2.0" to "40000.00")
                val snap2 = snapshot(t2, "170000.00", btc = "3.0" to "40000.00")
                val snap3 = snapshot(t3, "170000.00", btc = "3.0" to "40000.00")
                val snap4 = snapshot(t4, "170000.00", btc = "3.0" to "40000.00")

                val mockInceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
                coEvery { repository.getSnapshotsInRange(t0, t4) } returns
                    listOf(snap0, snap1, snap2, snap3, snap4)
                coEvery { repository.getSnapshotsInRange(t0, openEndedRangeEnd) } returns
                    listOf(snap0, snap1, snap2, snap3, snap4)
                coEvery { repository.getSnapshotBefore(any()) } returns null
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val serviceWithInception = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = mockInceptionService,
                )

                // The first trial may report an unavailableAt after more than one candidate, but
                // that does not prove the intermediate anchor is invalid. Check every candidate
                // in order so the earliest verified start remains trustworthy.
                serviceWithInception.findVerifiedLaterComparisonStart(t0) shouldBe t2
            }
        }

        "findVerifiedLaterComparisonStart_StopsAfterBoundedTrialBudget" {
            runTest {
                val balances = (1..10).map { index -> "1.$index" }
                val snaps = balances.mapIndexed { index, balance ->
                    val stamp = now.plusSeconds(3600L * (index + 1))
                    snapshot(stamp, "90000.00", btc = balance to "40000.00")
                }

                val mockInceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
                coEvery {
                    repository.getSnapshotsInRange(now, now.plusSeconds(36000))
                } returns snaps
                coEvery { repository.getSnapshotsInRange(now, openEndedRangeEnd) } returns snaps
                coEvery { repository.getSnapshotBefore(any()) } returns snaps.first()
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val serviceWithInception = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = mockInceptionService,
                )

                serviceWithInception.findVerifiedLaterComparisonStart(now).shouldBeNull()
            }
        }

        "findVerifiedLaterComparisonStart_ResumesAfterPerRunBudget" {
            runTest {
                val firstEight = (1..8).map { index ->
                    snapshot(
                        now.plusSeconds(3600L * index),
                        "90000.00",
                        btc = "1.$index" to "40000.00",
                    )
                }
                val verifiedStart = now.plusSeconds(3600L * 9)
                val verifiedContinuation = now.plusSeconds(3600L * 10)
                val snaps = firstEight + listOf(
                    snapshot(verifiedStart, "130000.00", btc = "2.0" to "40000.00"),
                    snapshot(verifiedContinuation, "130000.00", btc = "2.0" to "40000.00"),
                )
                val metadata = mutableMapOf<String, String>()
                coEvery { repository.getSyncMetadata(any()) } coAnswers { metadata[firstArg()] }
                coEvery { repository.setSyncMetadataAtomically(any()) } coAnswers {
                    metadata.putAll(firstArg())
                }
                coEvery { repository.getSnapshotsInRange(now, openEndedRangeEnd) } returns snaps
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()
                coEvery { repository.getSnapshotBefore(any()) } returns null

                val serviceWithInception = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = null,
                )

                serviceWithInception.findVerifiedLaterComparisonStart(now).shouldBeNull()
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_STATUS] shouldBe
                    ComparisonProposalStatus.INCOMPLETE.name
                serviceWithInception.findVerifiedLaterComparisonStart(now) shouldBe verifiedStart
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_STATUS] shouldBe
                    ComparisonProposalStatus.VERIFIED.name
            }
        }

        "proposal search invalidates its cursor when configuration or account evidence changes" {
            runTest {
                val firstEight = (1..8).map { index ->
                    snapshot(
                        now.plusSeconds(3600L * index),
                        "90000.00",
                        btc = "1.$index" to "40000.00",
                    )
                }
                val verifiedStart = now.plusSeconds(3600L * 9)
                val verifiedContinuation = now.plusSeconds(3600L * 10)
                var snapshots = firstEight + listOf(
                    snapshot(verifiedStart, "130000.00", btc = "2.0" to "40000.00"),
                    snapshot(verifiedContinuation, "130000.00", btc = "2.0" to "40000.00"),
                )
                val metadata = mutableMapOf<String, String>(
                    SyncMetadataKeys.INCEPTION_CONFIG_FINGERPRINT to "config-a",
                    SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST to "account-a",
                )
                coEvery { repository.getSyncMetadata(any()) } coAnswers { metadata[firstArg()] }
                coEvery { repository.setSyncMetadataAtomically(any()) } coAnswers {
                    metadata.putAll(firstArg())
                }
                coEvery { repository.getSnapshotsInRange(now, openEndedRangeEnd) } coAnswers { snapshots }
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()
                coEvery { repository.getSnapshotBefore(any()) } returns null

                val serviceWithInception = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = null,
                )

                serviceWithInception.findVerifiedLaterComparisonStart(now).shouldBeNull()
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_STATUS] shouldBe
                    ComparisonProposalStatus.INCOMPLETE.name

                metadata[SyncMetadataKeys.INCEPTION_CONFIG_FINGERPRINT] = "config-b"
                metadata[SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST] = "account-b"
                serviceWithInception.findVerifiedLaterComparisonStart(now).shouldBeNull()
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_STATUS] shouldBe
                    ComparisonProposalStatus.INCOMPLETE.name

                serviceWithInception.findVerifiedLaterComparisonStart(now) shouldBe verifiedStart

                snapshots = listOf(
                    snapshots.first().copy(totalValueUSD = BigDecimal("90001.00")),
                ) + snapshots.drop(1)
                serviceWithInception.findVerifiedLaterComparisonStart(now).shouldBeNull()
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_STATUS] shouldBe
                    ComparisonProposalStatus.INCOMPLETE.name
            }
        }

        "getComparisonStartProposal_reportsExhaustedAfterCheckingEveryCandidate" {
            runTest {
                val t0 = now
                val exhaustedCandidates = listOf(
                    snapshot(now.plusSeconds(3600), "130000.00", btc = "2.0" to "40000.00"),
                    snapshot(now.plusSeconds(7200), "170000.00", btc = "3.0" to "40000.00"),
                    snapshot(now.plusSeconds(10800), "210000.00", btc = "4.0" to "40000.00"),
                )
                val metadata = mutableMapOf<String, String>()
                metadata[SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC] = "4102444800"
                coEvery { repository.getSyncMetadata(any()) } coAnswers { metadata[firstArg()] }
                coEvery { repository.setSyncMetadataAtomically(any()) } coAnswers {
                    metadata.putAll(firstArg())
                }
                val mockInceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
                coEvery { mockInceptionService.resolveInception() } returns InceptionResolution(
                    inceptionTime = t0,
                    inceptionSnapshot = null,
                    isAutoDetected = false,
                    confidence = InceptionConfidence.RECOVERY_INCOMPLETE,
                    unavailableReason = ComparisonUnavailableReason.INCEPTION_BASELINE_UNAVAILABLE,
                )
                coEvery { repository.getSnapshotsInRange(t0, openEndedRangeEnd) } returns exhaustedCandidates
                coEvery { repository.getSnapshotBefore(any()) } returns null
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val serviceWithInception = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = mockInceptionService,
                )

                val exhausted = serviceWithInception.getComparisonStartProposal(t0)

                exhausted?.status shouldBe ComparisonProposalStatus.EXHAUSTED
                exhausted?.timestamp.shouldBeNull()
                serviceWithInception.getComparisonStartProposal(t0)?.status shouldBe
                    ComparisonProposalStatus.EXHAUSTED
            }
        }

        "terminal proposal state is rechecked when prepared funding evidence changes" {
            runTest {
                val t0 = now
                val candidates = listOf(
                    snapshot(now.plusSeconds(3600), "130000.00", btc = "2.0" to "40000.00"),
                    snapshot(now.plusSeconds(7200), "170000.00", btc = "3.0" to "40000.00"),
                    snapshot(now.plusSeconds(10800), "210000.00", btc = "4.0" to "40000.00"),
                )
                val metadata = mutableMapOf<String, String>()
                metadata[SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC] = "4102444800"
                var evidenceRevision = "revision-a"
                var prepareCalls = 0
                val mockInceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
                coEvery { mockInceptionService.resolveInception() } returns InceptionResolution(
                    inceptionTime = t0,
                    inceptionSnapshot = null,
                    isAutoDetected = false,
                    confidence = InceptionConfidence.RECOVERY_INCOMPLETE,
                    unavailableReason = ComparisonUnavailableReason.INCEPTION_BASELINE_UNAVAILABLE,
                )
                val provenanceResolver = object : FundingProvenanceResolver {
                    override fun resolve(event: LedgerEvent): FundingEvidence = FundingEvidence.UNRESOLVED

                    override val evidenceFingerprint: String
                        get() = evidenceRevision

                    override suspend fun prepare(events: Collection<LedgerEvent>): FundingProvenanceResolver {
                        prepareCalls++
                        return this
                    }
                }
                coEvery { repository.getSyncMetadata(any()) } coAnswers { metadata[firstArg()] }
                coEvery { repository.setSyncMetadataAtomically(any()) } coAnswers {
                    metadata.putAll(firstArg())
                }
                coEvery { repository.getAllSnapshotsInRange(t0, openEndedRangeEnd) } returns candidates
                coEvery { repository.getSnapshotBefore(any()) } returns null
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val serviceWithInception = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = mockInceptionService,
                    fundingProvenanceResolver = provenanceResolver,
                )

                serviceWithInception.getComparisonStartProposal(t0)?.status shouldBe
                    ComparisonProposalStatus.EXHAUSTED
                serviceWithInception.getComparisonStartProposal(t0)?.status shouldBe
                    ComparisonProposalStatus.EXHAUSTED
                val callsBeforeRevisionChange = prepareCalls

                evidenceRevision = "revision-b"
                serviceWithInception.getComparisonStartProposal(t0)?.status shouldBe
                    ComparisonProposalStatus.EXHAUSTED

                prepareCalls shouldBeGreaterThan callsBeforeRevisionChange
            }
        }

        "getComparisonStartProposal_returnsNullWhenThereIsNoEligibleUnavailableComparison" {
            runTest {
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns emptyList()

                service.getComparisonStartProposal(now).shouldBeNull()
            }
        }

        "getComparisonStartProposal_doesNotSearchWhenTheCurrentComparisonIsAvailable" {
            runTest {
                val first = snapshot(now, "100000.00", btc = "1.0" to "50000.00")
                val second = snapshot(now.plusSeconds(3600), "100000.00", btc = "1.0" to "50000.00")
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(first, second)
                coEvery { repository.getSnapshotBefore(any()) } returns null
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                service.getComparisonStartProposal(now).shouldBeNull()
            }
        }

        "getComparisonStartProposal_doesNotSearchAnIneligibleRecoveryFailure" {
            runTest {
                val first = snapshot(now, "100000.00", btc = "1.0" to "50000.00")
                val second = snapshot(now.plusSeconds(3600), "100000.00", btc = "1.0" to "50000.00")
                val mockInceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
                coEvery { mockInceptionService.resolveInception() } returns InceptionResolution(
                    inceptionTime = now,
                    inceptionSnapshot = null,
                    isAutoDetected = false,
                    confidence = InceptionConfidence.RECOVERY_INCOMPLETE,
                    unavailableReason = null,
                )
                coEvery { repository.getSnapshotsInRange(now, openEndedRangeEnd) } returns listOf(first, second)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()
                coEvery { repository.getSnapshotBefore(any()) } returns null

                val serviceWithInception = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = mockInceptionService,
                )

                serviceWithInception.getComparisonStartProposal(now).shouldBeNull()
            }
        }

        "getComparisonStartProposal_doesNotExposeAutoDetectedInception" {
            runTest {
                val first = snapshot(now, "90000.00", btc = "1.0" to "40000.00")
                val second = snapshot(now.plusSeconds(3600), "100000.00", btc = "1.0" to "50000.00")
                val mockInceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
                coEvery { mockInceptionService.resolveInception() } returns InceptionResolution(
                    inceptionTime = now,
                    inceptionSnapshot = first,
                    isAutoDetected = true,
                )
                coEvery { repository.getAllSnapshotsInRange(now, openEndedRangeEnd) } returns listOf(first, second)

                val serviceWithInception = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = mockInceptionService,
                )

                serviceWithInception.getComparisonStartProposal(now).shouldBeNull()
                coVerify(exactly = 0) { repository.getAllSnapshotsInRange(any(), any()) }
            }
        }

        "verified proposal state is reused and an invalid stored cursor restarts safely" {
            runTest {
                val first = snapshot(now, "90000.00", btc = "1.0" to "40000.00")
                val verified = snapshot(now.plusSeconds(3600), "100000.00", btc = "1.0" to "50000.00")
                val last = snapshot(now.plusSeconds(7200), "110000.00", btc = "1.0" to "60000.00")
                val metadata = mutableMapOf<String, String>()
                coEvery { repository.getSyncMetadata(any()) } coAnswers { metadata[firstArg()] }
                coEvery { repository.setSyncMetadataAtomically(any()) } coAnswers {
                    metadata.putAll(firstArg())
                }
                coEvery { repository.getSnapshotsInRange(now, openEndedRangeEnd) } returns
                    listOf(first, verified, last)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()
                coEvery { repository.getSnapshotBefore(any()) } returns null

                service.findVerifiedLaterComparisonStart(now) shouldBe verified.timestamp
                service.findVerifiedLaterComparisonStart(now) shouldBe verified.timestamp

                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_CURSOR_EPOCH_MS] = "invalid-cursor"
                service.findVerifiedLaterComparisonStart(now) shouldBe verified.timestamp

                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_CURSOR_EPOCH_MS] = "${now.toEpochMilli()}:-1"
                service.findVerifiedLaterComparisonStart(now) shouldBe verified.timestamp

                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_CURSOR_EPOCH_MS] =
                    "${now.toEpochMilli()}:not-an-ordinal"
                service.findVerifiedLaterComparisonStart(now) shouldBe verified.timestamp

                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_CURSOR_EPOCH_MS] = "not-an-epoch:0"
                service.findVerifiedLaterComparisonStart(now) shouldBe verified.timestamp

                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_CURSOR_EPOCH_MS] = "invalid:cursor:shape"
                service.findVerifiedLaterComparisonStart(now) shouldBe verified.timestamp

                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_STATUS] =
                    ComparisonProposalStatus.INCOMPLETE.name
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_CURSOR_EPOCH_MS] =
                    now.plusSeconds(9999).toEpochMilli().toString()
                service.findVerifiedLaterComparisonStart(now) shouldBe verified.timestamp

                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_STATUS] =
                    ComparisonProposalStatus.VERIFIED.name
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_CURSOR_EPOCH_MS] =
                    now.plusSeconds(9999).toEpochMilli().toString()
                service.findVerifiedLaterComparisonStart(now) shouldBe verified.timestamp

                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_CURSOR_EPOCH_MS] =
                    "${verified.timestamp.toEpochMilli()}:0"
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_SNAPSHOT_ID] = "42"
                service.findVerifiedLaterComparisonStart(now) shouldBe verified.timestamp
            }
        }

        "proposal evidence fingerprint includes retained trade ledger and order identity data" {
            runTest {
                val first = snapshot(now, "100000.00", btc = "1.0" to "50000.00")
                val verified = snapshot(
                    now.plusSeconds(3600),
                    "100000.00",
                    btc = "1.0" to "50000.00",
                )
                val last = snapshot(now.plusSeconds(7200), "100000.00", btc = "1.0" to "50000.00")
                val retainedTrade = TestFixtures.tradeRecord(
                    timestamp = now.plusSeconds(5400),
                    pair = Asset.BTC_USD_PAIR,
                    side = OrderSide.BUY.apiValue,
                    symbol = Asset.BTC,
                    volume = BigDecimal("0.01"),
                    usdAmount = BigDecimal("500.00"),
                    price = BigDecimal("50000.00"),
                    fee = BigDecimal("0.50"),
                    slippagePercent = BigDecimal("0.10"),
                    expectedPrice = BigDecimal("49950.00"),
                    source = TradeSource.API_FILL,
                    id = 1,
                    orderTxid = "proposal-order",
                    tradeId = "proposal-trade",
                    clientOrderId = "proposal-client",
                )
                val failedTrade = retainedTrade.copy(id = 2, success = false)
                val dryRunTrade = retainedTrade.copy(id = 3, dryRun = true)
                val unidentifiedFailedTrade = retainedTrade.copy(
                    id = null,
                    success = false,
                    orderTxid = null,
                    tradeId = null,
                    clientOrderId = null,
                )
                val retainedLedger = ledgerEvent(
                    ledgerId = "proposal-ledger",
                    timestamp = now.plusSeconds(5401),
                    asset = Asset.BTC,
                    amount = "0",
                )
                coEvery { repository.getSnapshotsInRange(now, openEndedRangeEnd) } returns
                    listOf(first, verified, last)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { repository.getTradesInRange(Instant.EPOCH, openEndedRangeEnd) } returns
                    listOf(failedTrade, dryRunTrade, unidentifiedFailedTrade, retainedTrade)
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(Instant.EPOCH, openEndedRangeEnd) } returns
                    listOf(retainedLedger)
                coEvery { repository.getSnapshotBefore(any()) } returns null
                coEvery { orderIntentRepository.getKnownRebalancerOrderIdentities(any(), any()) } returns
                    RebalancerOrderIdentities(orderTxids = setOf("proposal-order"))

                service.findVerifiedLaterComparisonStart(now) shouldBe verified.timestamp
            }
        }

        "resolveContinuousHistoryStart_ReturnsStoredMetadataWhenPresent" {
            runTest {
                val storedTime = now.minusSeconds(86400 * 10)
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.CONTINUOUS_HISTORY_START_EPOCH_MS)
                } returns storedTime.toEpochMilli().toString()

                val snap1 = snapshot(now.minusSeconds(86400 * 20), "100000.00", btc = "1.0" to "50000.00")
                val snap2 = snapshot(now, "100000.00", btc = "1.0" to "50000.00")
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)

                val proposal = service.getComparisonStartProposal(now.minusSeconds(86400 * 20))
                proposal.shouldBeNull()
            }
        }

        "resolveContinuousHistoryStart_ReturnsStoredWhenEarliestSnapshotNotBeforeStored" {
            runTest {
                val storedTime = now.minusSeconds(86400 * 20)
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.CONTINUOUS_HISTORY_START_EPOCH_MS)
                } returns storedTime.toEpochMilli().toString()

                val snap1 = snapshot(now.minusSeconds(86400 * 10), "100000.00", btc = "1.0" to "50000.00")
                val snap2 = snapshot(now, "100000.00", btc = "1.0" to "50000.00")
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)

                val proposal = service.getComparisonStartProposal(now.minusSeconds(86400 * 5))
                proposal.shouldBeNull()
            }
        }

        "resolveContinuousHistoryStart_SetsEpochForFreshInstall" {
            runTest {
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.CONTINUOUS_HISTORY_START_EPOCH_MS)
                } returns null
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_INSTALL_TYPE)
                } returns InceptionDiscoveryService.INSTALL_TYPE_FRESH
                coEvery { repository.isHistorySeeded() } returns false

                val snap1 = snapshot(now.minusSeconds(3600), "100000.00", btc = "1.0" to "50000.00")
                val snap2 = snapshot(now, "100000.00", btc = "1.0" to "50000.00")
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)

                val storedSlot = slot<String>()
                coEvery {
                    repository.setSyncMetadata(
                        SyncMetadataKeys.CONTINUOUS_HISTORY_START_EPOCH_MS,
                        capture(storedSlot),
                    )
                } returns Unit

                service.findVerifiedLaterComparisonStart(now.minusSeconds(3600))
                storedSlot.captured shouldBe "0"
            }
        }

        "resolveContinuousHistoryStart_FindsContinuousBoundaryAcrossGap" {
            runTest {
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.CONTINUOUS_HISTORY_START_EPOCH_MS)
                } returns null
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_INSTALL_TYPE)
                } returns InceptionDiscoveryService.INSTALL_TYPE_UPGRADED
                coEvery { repository.isHistorySeeded() } returns false

                val oldSnap = snapshot(now.minusSeconds(86400 * 10), "100000.00", btc = "1.0" to "50000.00")
                val continuousBoundary = now.minusSeconds(86400 * 2)
                val snap1 = snapshot(continuousBoundary, "100000.00", btc = "1.0" to "50000.00")
                val snap2 = snapshot(now.minusSeconds(86400), "100000.00", btc = "1.0" to "50000.00")
                val snap3 = snapshot(now, "100000.00", btc = "1.0" to "50000.00")
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(oldSnap, snap1, snap2, snap3)

                val storedSlot = slot<String>()
                coEvery {
                    repository.setSyncMetadata(
                        SyncMetadataKeys.CONTINUOUS_HISTORY_START_EPOCH_MS,
                        capture(storedSlot),
                    )
                } returns Unit

                val result = service.findVerifiedLaterComparisonStart(oldSnap.timestamp)
                result.shouldBeNull()
                storedSlot.captured shouldBe continuousBoundary.toEpochMilli().toString()
            }
        }

        "historicalCoverageGapExists_ConsecutiveGapInRetainedHistoryBlocksProposal" {
            runTest {
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.CONTINUOUS_HISTORY_START_EPOCH_MS)
                } returns "0"
                val snap1 = snapshot(now.minusSeconds(86400 * 10), "100000.00", btc = "1.0" to "50000.00")
                val snap2 = snapshot(now.minusSeconds(86400 * 5), "100000.00", btc = "1.0" to "50000.00")
                val snap3 = snapshot(now, "100000.00", btc = "1.0" to "50000.00")
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2, snap3)

                val proposal = service.findVerifiedLaterComparisonStart(snap1.timestamp)
                proposal.shouldBeNull()
            }
        }

        "getSnapshotsInRange drops an identity anchor when a reconstructed snapshot shares the instant" {
            runTest {
                val anchor = snapshot(now, "1235.68", btc = "0.0" to "0.00")
                val reconstructed = snapshot(now, "1490.81", btc = "0.5" to "40000.00")
                val later = snapshot(now.plusSeconds(1800), "1500.00", btc = "0.6" to "40000.00")
                coEvery { repository.getSnapshotsInRange(now, now) } returns listOf(anchor, reconstructed)
                coEvery { repository.getSnapshotsInRange(now, now.plusSeconds(3600)) } returns
                    listOf(anchor, reconstructed, later)
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_APPROVED_BASELINE_SNAPSHOT_ID) } returns
                    "7"
                coEvery { repository.getSnapshotById(7) } returns anchor

                service.getSnapshotsInRange(now, now.plusSeconds(3600)) shouldBe listOf(reconstructed, later)
            }
        }

        "getSnapshotsInRange keeps an identity anchor when no snapshot shares the instant" {
            runTest {
                val anchor = snapshot(now, "1235.68", btc = "0.0" to "0.00")
                val later = snapshot(now.plusSeconds(1800), "1500.00", btc = "0.6" to "40000.00")
                coEvery { repository.getSnapshotsInRange(now, now) } returns listOf(anchor)
                coEvery { repository.getSnapshotsInRange(now, now.plusSeconds(3600)) } returns listOf(anchor, later)
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_APPROVED_BASELINE_SNAPSHOT_ID) } returns
                    "7"
                coEvery { repository.getSnapshotById(7) } returns anchor

                service.getSnapshotsInRange(now, now.plusSeconds(3600)) shouldBe listOf(anchor, later)
            }
        }

        "settings comparison status persists the automatic baseline proof on first successful verification" {
            runTest {
                val fixture = automaticBaselineFixture()
                val service = automaticBaselineService(fixture)

                val status = service.getSettingsComparisonStatus(fixture.anchorTime)

                status.comparisonAvailability shouldBe ComparisonAvailability.AVAILABLE
                status.baselineStatus shouldBe AutomaticBaselineStatus.VERIFIED
                status.baselineTimestamp shouldBe fixture.anchorTime.toString()
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_STATUS] shouldBe "VERIFIED"
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_VERIFICATION_VERSION] shouldBe "1"
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_TIMESTAMP_EPOCH_MS] shouldBe
                    fixture.anchorTime.toEpochMilli().toString()
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_INCEPTION_EPOCH_MS] shouldBe
                    fixture.anchorTime.toEpochMilli().toString()
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_SNAPSHOT_CURSOR] shouldBe
                    "${fixture.anchorTime.toEpochMilli()}:0"
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_SNAPSHOT_ID].shouldNotBeNull()
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_EVIDENCE_FINGERPRINT]
                    .shouldNotBeNull()
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_EVIDENCE_HORIZON_MS] shouldBe
                    fixture.laterTime.toEpochMilli().toString()
            }
        }

        "settings comparison status takes the persisted fast path on unchanged evidence" {
            runTest {
                val fixture = automaticBaselineFixture()
                val service = automaticBaselineService(fixture)
                service.getSettingsComparisonStatus(fixture.anchorTime)

                val reloaded = service.getSettingsComparisonStatus(fixture.anchorTime)

                reloaded.baselineStatus shouldBe AutomaticBaselineStatus.VERIFIED
                reloaded.baselineTimestamp shouldBe fixture.anchorTime.toString()
                // The fast path proves baseline identity only; it must not claim that the
                // current comparison was evaluated.
                reloaded.comparisonAvailability.shouldBeNull()
                // The full evaluation ran once: the recompute side effects (anchor lookup and the
                // baseline id write) are not repeated by the fast path.
                coVerify(exactly = 3) { repository.getSnapshotBefore(any()) }
                coVerify(exactly = 2) { repository.getSnapshotId(any(), any()) }
                // The full verification prepared funding evidence once; the fast path must not
                // repeat the preparation.
                coVerify(exactly = 1) { fixture.fundingProvenanceResolver.prepare(any()) }
            }
        }

        "settings comparison status takes the persisted fast path in a fresh service instance" {
            runTest {
                val fixture = automaticBaselineFixture()
                automaticBaselineService(fixture).getSettingsComparisonStatus(fixture.anchorTime)

                val restarted = automaticBaselineService(fixture).getSettingsComparisonStatus(fixture.anchorTime)

                restarted.baselineStatus shouldBe AutomaticBaselineStatus.VERIFIED
                restarted.baselineTimestamp shouldBe fixture.anchorTime.toString()
                restarted.comparisonAvailability.shouldBeNull()
                coVerify(exactly = 3) { repository.getSnapshotBefore(any()) }
                coVerify(exactly = 1) { fixture.fundingProvenanceResolver.prepare(any()) }
            }
        }

        "a new live snapshot after verification does not invalidate the persisted proof" {
            runTest {
                val fixture = automaticBaselineFixture()
                val service = automaticBaselineService(fixture)
                service.getSettingsComparisonStatus(fixture.anchorTime)
                val tailTime = fixture.laterTime.plusSeconds(3600)
                fixture.snapshotRows += snapshot(tailTime, "1200.00", btc = "1.0" to "700.00")

                val reloaded = service.getSettingsComparisonStatus(fixture.anchorTime)

                reloaded.baselineStatus shouldBe AutomaticBaselineStatus.VERIFIED
                reloaded.baselineTimestamp shouldBe fixture.anchorTime.toString()
                reloaded.comparisonAvailability.shouldBeNull()
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_STATUS] shouldBe "VERIFIED"
                coVerify(exactly = 3) { repository.getSnapshotBefore(any()) }
            }
        }

        "a post-horizon tail event breaks the current comparison " +
            "while the fast path keeps serving the proven baseline" {
                runTest {
                    val fixture = automaticBaselineFixture()
                    val service = automaticBaselineService(fixture)
                    service.getSettingsComparisonStatus(fixture.anchorTime)
                    val tailTime = fixture.laterTime.plusMillis(500)
                    val tailEvent = ledgerEvent("TAIL-1", tailTime, "USD", "1.00", type = "MARGIN_ROLLFORWARD")
                    coEvery { ledgerRepository.getLedgersInRange(any(), any()) } coAnswers {
                        if (secondArg<Instant>() >= tailTime) listOf(tailEvent) else emptyList()
                    }

                    val fastPath = service.getSettingsComparisonStatus(fixture.anchorTime)
                    val fullPath = service.getSettingsComparisonStatus(
                        fixture.anchorTime,
                        allowPersistedBaselineFastPath = false,
                    )

                    // The persisted proof vouches for baseline identity only: the fast path keeps
                    // reporting it while refusing to claim the current comparison was evaluated.
                    fastPath.baselineStatus shouldBe AutomaticBaselineStatus.VERIFIED
                    fastPath.baselineTimestamp shouldBe fixture.anchorTime.toString()
                    fastPath.comparisonAvailability.shouldBeNull()
                    // The full evaluation honestly reports the tail event and marks no baseline
                    // status: an unavailable comparison carries no proof it did not just make.
                    fullPath.comparisonAvailability shouldBe ComparisonAvailability.UNAVAILABLE
                    fullPath.unavailableReason shouldBe ComparisonUnavailableReason.UNSUPPORTED_LEDGER_TYPE
                    fullPath.baselineStatus.shouldBeNull()
                    fullPath.baselineTimestamp.shouldBeNull()
                    fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_STATUS] shouldBe "VERIFIED"
                }
            }

        "a degraded inception resolution fails closed without destroying the persisted proof" {
            runTest {
                val fixture = automaticBaselineFixture()
                val service = automaticBaselineService(fixture)
                service.getSettingsComparisonStatus(fixture.anchorTime)
                coEvery { fixture.inceptionService.resolveInception() } returns InceptionResolution(
                    inceptionTime = fixture.anchorTime.minusSeconds(3600),
                    inceptionSnapshot = null,
                    isAutoDetected = false,
                    confidence = InceptionConfidence.RECOVERY_INCOMPLETE,
                    unavailableReason = ComparisonUnavailableReason.INCEPTION_AMBIGUOUS,
                )

                val degraded = service.getSettingsComparisonStatus(fixture.anchorTime)

                // A degraded resolution withholds trust from the current evidence, so the
                // full evaluation runs (the passive-anchor path does not consult the
                // snapshot-before lookup) instead of serving the record.
                degraded.comparisonAvailability shouldBe ComparisonAvailability.AVAILABLE
                // The degraded resolution re-anchors passively: the comparison is available,
                // but no automatic-inception proof exists behind that anchor, so the status
                // marks no baseline certification.
                degraded.baselineStatus.shouldBeNull()
                coVerify(exactly = 1) { repository.getSnapshotId(any(), any()) }
                // The proof survives a transient degraded resolution: the next confident
                // resolution can reuse it without a re-verification.
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_STATUS] shouldBe "VERIFIED"
            }
        }

        "the proposal evaluation path bypasses the persisted fast path" {
            runTest {
                val fixture = automaticBaselineFixture()
                val service = automaticBaselineService(fixture)
                service.getSettingsComparisonStatus(fixture.anchorTime)

                val proposal = service.getComparisonStartProposal(fixture.anchorTime)

                // Proposal semantics are preserved exactly: the persisted record answers the
                // Settings display only, never the proposal chain.
                coVerify(exactly = 4) { repository.getSnapshotBefore(any()) }
                proposal.shouldBeNull()
            }
        }

        "a changed strategy inception invalidates the persisted proof" {
            runTest {
                val fixture = automaticBaselineFixture()
                val service = automaticBaselineService(fixture)
                service.getSettingsComparisonStatus(fixture.anchorTime)
                coEvery { fixture.inceptionService.resolveInception() } returns InceptionResolution(
                    inceptionTime = fixture.laterTime,
                    inceptionSnapshot = fixture.snapshotRows[1],
                    isAutoDetected = false,
                    confidence = InceptionConfidence.CONFIDENT,
                )

                service.getSettingsComparisonStatus(fixture.anchorTime)

                // The fast path rejects the record before resolving the baseline id, so the
                // only id resolution left is the first verification's persist write.
                coVerify(exactly = 1) { repository.getSnapshotId(any(), any()) }
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_STATUS] shouldNotBe "VERIFIED"
            }
        }

        "an unresolvable baseline snapshot id invalidates the persisted proof" {
            runTest {
                val fixture = automaticBaselineFixture()
                val service = automaticBaselineService(fixture)
                service.getSettingsComparisonStatus(fixture.anchorTime)
                coEvery { repository.getSnapshotId(any(), any()) } returns null

                service.getSettingsComparisonStatus(fixture.anchorTime)

                // The recompute's re-persist aborts at the unresolvable id before consuming
                // evidence, so the count is first verification (calc + digest) plus one
                // re-evaluation calculation.
                coVerify(exactly = 3) { repository.getSnapshotBefore(any()) }
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_STATUS] shouldNotBe "VERIFIED"
            }
        }

        "a changed config universe invalidates and re-persists the proof" {
            runTest {
                val fixture = automaticBaselineFixture()
                val service = automaticBaselineService(fixture)
                service.getSettingsComparisonStatus(fixture.anchorTime)
                fixture.metadata[SyncMetadataKeys.INCEPTION_CONFIG_FINGERPRINT] = "universe-2"

                service.getSettingsComparisonStatus(fixture.anchorTime)

                coVerify(exactly = 4) { repository.getSnapshotBefore(any()) }
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_STATUS] shouldBe "VERIFIED"
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_CONFIG_FINGERPRINT] shouldBe
                    "universe-2"
            }
        }

        "a changed account scope invalidates and re-persists the proof" {
            runTest {
                val fixture = automaticBaselineFixture()
                val service = automaticBaselineService(fixture)
                service.getSettingsComparisonStatus(fixture.anchorTime)
                fixture.ledgerMetadata[SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST] = "scope-2"

                service.getSettingsComparisonStatus(fixture.anchorTime)

                coVerify(exactly = 4) { repository.getSnapshotBefore(any()) }
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_STATUS] shouldBe "VERIFIED"
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_ACCOUNT_SCOPE_DIGEST] shouldBe
                    "scope-2"
            }
        }

        "a changed reconstruction contract invalidates the persisted proof" {
            runTest {
                val fixture = automaticBaselineFixture()
                val service = automaticBaselineService(fixture)
                service.getSettingsComparisonStatus(fixture.anchorTime)
                fixture.metadata[SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION] = "legacy"

                service.getSettingsComparisonStatus(fixture.anchorTime)

                coVerify(exactly = 4) { repository.getSnapshotBefore(any()) }
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_STATUS] shouldBe "VERIFIED"
            }
        }

        "a rewritten evidence row inside the verified horizon invalidates the persisted proof" {
            runTest {
                val fixture = automaticBaselineFixture()
                val service = automaticBaselineService(fixture)
                service.getSettingsComparisonStatus(fixture.anchorTime)
                val firstFingerprint =
                    fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_EVIDENCE_FINGERPRINT]
                fixture.snapshotRows[1] =
                    fixture.snapshotRows[1].copy(totalValueUSD = BigDecimal("1200.00"))

                service.getSettingsComparisonStatus(fixture.anchorTime)

                // First verification (calc + digest), the failing validation digest, and the
                // re-verification (calc + digest).
                coVerify(exactly = 5) { repository.getSnapshotBefore(any()) }
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_STATUS] shouldBe "VERIFIED"
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_EVIDENCE_FINGERPRINT]
                    .shouldNotBeNull()
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_EVIDENCE_FINGERPRINT] shouldNotBe
                    firstFingerprint
            }
        }

        "malformed persisted state fails closed and recomputes" {
            runTest {
                val fixture = automaticBaselineFixture()
                val service = automaticBaselineService(fixture)
                service.getSettingsComparisonStatus(fixture.anchorTime)
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_STATUS] = "CORRUPT"

                val reloaded = service.getSettingsComparisonStatus(fixture.anchorTime)

                reloaded.comparisonAvailability shouldBe ComparisonAvailability.AVAILABLE
                reloaded.baselineStatus shouldBe AutomaticBaselineStatus.VERIFIED
                reloaded.baselineTimestamp shouldBe fixture.anchorTime.toString()
                coVerify(exactly = 4) { repository.getSnapshotBefore(any()) }
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_STATUS] shouldBe "VERIFIED"
            }
        }

        "a partially persisted record fails closed and recomputes" {
            runTest {
                val fixture = automaticBaselineFixture()
                val service = automaticBaselineService(fixture)
                service.getSettingsComparisonStatus(fixture.anchorTime)
                fixture.metadata.remove(SyncMetadataKeys.INCEPTION_AUTO_BASELINE_EVIDENCE_FINGERPRINT)

                val reloaded = service.getSettingsComparisonStatus(fixture.anchorTime)

                reloaded.comparisonAvailability shouldBe ComparisonAvailability.AVAILABLE
                reloaded.baselineStatus shouldBe AutomaticBaselineStatus.VERIFIED
                coVerify(exactly = 4) { repository.getSnapshotBefore(any()) }
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_STATUS] shouldBe "VERIFIED"
            }
        }

        "a record written by another contract version is rejected and re-persisted" {
            runTest {
                val fixture = automaticBaselineFixture()
                val service = automaticBaselineService(fixture)
                service.getSettingsComparisonStatus(fixture.anchorTime)
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_VERIFICATION_VERSION] = "999"

                service.getSettingsComparisonStatus(fixture.anchorTime)

                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_STATUS] shouldBe "VERIFIED"
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_VERIFICATION_VERSION] shouldBe "1"
            }
        }

        "a confident resolution carrying an unavailable reason does not serve the record" {
            runTest {
                val fixture = automaticBaselineFixture()
                val service = automaticBaselineService(fixture)
                service.getSettingsComparisonStatus(fixture.anchorTime)
                coEvery { fixture.inceptionService.resolveInception() } returns InceptionResolution(
                    inceptionTime = fixture.anchorTime,
                    inceptionSnapshot = fixture.snapshotRows.first(),
                    isAutoDetected = false,
                    confidence = InceptionConfidence.CONFIDENT,
                    unavailableReason = ComparisonUnavailableReason.INCEPTION_AMBIGUOUS,
                )

                service.getSettingsComparisonStatus(fixture.anchorTime)

                coVerify(exactly = 4) { repository.getSnapshotBefore(any()) }
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_STATUS] shouldBe "VERIFIED"
            }
        }

        "a stale reconstruction interval outside the verified interval does not invalidate" {
            runTest {
                val fixture = automaticBaselineFixture()
                val service = automaticBaselineService(fixture)
                service.getSettingsComparisonStatus(fixture.anchorTime)
                fixture.metadata[SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION] = "legacy"
                fixture.metadata[SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_THROUGH_EPOCH_SEC] =
                    (fixture.anchorTime.epochSecond - 100).toString()

                val reloaded = service.getSettingsComparisonStatus(fixture.anchorTime)

                reloaded.baselineStatus shouldBe AutomaticBaselineStatus.VERIFIED
                reloaded.comparisonAvailability.shouldBeNull()
                coVerify(exactly = 3) { repository.getSnapshotBefore(any()) }
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_STATUS] shouldBe "VERIFIED"
            }
        }

        "each missing record field fails closed and is re-persisted" {
            runTest {
                val fixture = automaticBaselineFixture()
                val service = automaticBaselineService(fixture)
                service.getSettingsComparisonStatus(fixture.anchorTime)
                val mutations = listOf<Pair<String, String?>>(
                    SyncMetadataKeys.INCEPTION_AUTO_BASELINE_TIMESTAMP_EPOCH_MS to null,
                    SyncMetadataKeys.INCEPTION_AUTO_BASELINE_INCEPTION_EPOCH_MS to null,
                    SyncMetadataKeys.INCEPTION_AUTO_BASELINE_SNAPSHOT_ID to null,
                    SyncMetadataKeys.INCEPTION_AUTO_BASELINE_SNAPSHOT_CURSOR to null,
                    SyncMetadataKeys.INCEPTION_AUTO_BASELINE_CONFIG_FINGERPRINT to null,
                    SyncMetadataKeys.INCEPTION_AUTO_BASELINE_ACCOUNT_SCOPE_DIGEST to null,
                    SyncMetadataKeys.INCEPTION_AUTO_BASELINE_EVIDENCE_FINGERPRINT to null,
                    SyncMetadataKeys.INCEPTION_AUTO_BASELINE_EVIDENCE_HORIZON_MS to null,
                    SyncMetadataKeys.INCEPTION_AUTO_BASELINE_TIMESTAMP_EPOCH_MS to
                        (fixture.anchorTime.toEpochMilli() + 1).toString(),
                )
                for ((key, value) in mutations) {
                    if (value == null) fixture.metadata.remove(key) else fixture.metadata[key] = value

                    service.getSettingsComparisonStatus(fixture.anchorTime)

                    fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_STATUS] shouldBe "VERIFIED"
                }
                // First verification plus one full re-verification per mutation.
                coVerify(exactly = 2 + mutations.size * 2) { repository.getSnapshotBefore(any()) }
            }
        }

        "an already-invalidated record is skipped silently until re-verification succeeds" {
            runTest {
                val fixture = automaticBaselineFixture()
                val service = automaticBaselineService(fixture)
                service.getSettingsComparisonStatus(fixture.anchorTime)
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_STATUS] = "INVALIDATED"
                coEvery { repository.getSnapshotId(any(), any()) } returns null

                service.getSettingsComparisonStatus(fixture.anchorTime)

                // The full evaluation ran (digest consults the predecessor anchor again) and
                // its own re-persist aborted at the unresolvable id, so the record stays dead
                // instead of re-logging the same invalidation on every load.
                coVerify(exactly = 3) { repository.getSnapshotBefore(any()) }
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_STATUS] shouldBe "INVALIDATED"
            }
        }

        "the predecessor anchor is part of the verified evidence" {
            runTest {
                val fixture = automaticBaselineFixture()
                val predecessor = snapshot(
                    fixture.anchorTime.minusSeconds(3600),
                    "1000.00",
                    btc = "1.0" to "500.00",
                    usdBalance = "500.00",
                )
                coEvery { repository.getSnapshotBefore(any()) } returns predecessor
                val service = automaticBaselineService(fixture)
                service.getSettingsComparisonStatus(fixture.anchorTime)
                val firstFingerprint =
                    fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_EVIDENCE_FINGERPRINT]
                coEvery { repository.getSnapshotBefore(any()) } returns predecessor.copy(
                    totalValueUSD = BigDecimal("1050.00"),
                )

                service.getSettingsComparisonStatus(fixture.anchorTime)

                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_STATUS] shouldBe "VERIFIED"
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_EVIDENCE_FINGERPRINT] shouldNotBe
                    firstFingerprint
                coVerify(exactly = 5) { repository.getSnapshotBefore(any()) }
            }
        }

        "a different proposal search window does not invalidate the persisted proof" {
            runTest {
                val fixture = automaticBaselineFixture()
                val service = automaticBaselineService(fixture)
                service.getSettingsComparisonStatus(fixture.anchorTime)

                val overridden =
                    service.getSettingsComparisonStatus(fixture.laterTime.plusSeconds(3600))

                overridden.baselineStatus shouldBe AutomaticBaselineStatus.VERIFIED
                overridden.baselineTimestamp shouldBe fixture.anchorTime.toString()
                overridden.comparisonAvailability.shouldBeNull()
                coVerify(exactly = 3) { repository.getSnapshotBefore(any()) }
            }
        }

        "newest live snapshot beyond ledger coverage is excluded from automatic baseline verification" {
            runTest {
                val fixture = automaticBaselineFixture()
                val midTime = fixture.anchorTime.plusSeconds(1800)
                fixture.snapshotRows += snapshot(
                    midTime,
                    "1050.00",
                    btc = "1.0" to "550.00",
                    usdBalance = "500.00",
                    balancesObservedAt = midTime.minusMillis(800),
                )
                fixture.ledgerMetadata[SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC] =
                    (fixture.laterTime.epochSecond - 2).toString()
                val service = automaticBaselineService(fixture)

                val status = service.getSettingsComparisonStatus(fixture.anchorTime)

                status.comparisonAvailability shouldBe ComparisonAvailability.AVAILABLE
                status.baselineStatus shouldBe AutomaticBaselineStatus.VERIFIED
                status.baselineTimestamp shouldBe fixture.anchorTime.toString()
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_EVIDENCE_HORIZON_MS] shouldBe
                    midTime.toEpochMilli().toString()
            }
        }

        "newest live snapshot beyond trade coverage is excluded from automatic baseline verification" {
            runTest {
                val fixture = automaticBaselineFixture()
                val midTime = fixture.anchorTime.plusSeconds(1800)
                fixture.snapshotRows += snapshot(
                    midTime,
                    "1050.00",
                    btc = "1.0" to "550.00",
                    usdBalance = "500.00",
                    balancesObservedAt = midTime.minusMillis(800),
                )
                fixture.metadata[SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC] =
                    (fixture.laterTime.epochSecond - 2).toString()
                val service = automaticBaselineService(fixture)

                val status = service.getSettingsComparisonStatus(fixture.anchorTime)

                status.comparisonAvailability shouldBe ComparisonAvailability.AVAILABLE
                status.baselineStatus shouldBe AutomaticBaselineStatus.VERIFIED
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_EVIDENCE_HORIZON_MS] shouldBe
                    midTime.toEpochMilli().toString()
            }
        }

        "verification horizon is the minimum of ledger and trade coverage" {
            runTest {
                val fixture = automaticBaselineFixture()
                val midTime = fixture.anchorTime.plusSeconds(1800)
                fixture.snapshotRows += snapshot(
                    midTime,
                    "1050.00",
                    btc = "1.0" to "550.00",
                    usdBalance = "500.00",
                    balancesObservedAt = midTime.minusMillis(800),
                )
                fixture.metadata[SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC] =
                    (fixture.laterTime.epochSecond - 1).toString()
                fixture.ledgerMetadata[SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC] =
                    (fixture.laterTime.epochSecond - 2).toString()
                val service = automaticBaselineService(fixture)

                val status = service.getSettingsComparisonStatus(fixture.anchorTime)

                status.comparisonAvailability shouldBe ComparisonAvailability.AVAILABLE
                status.baselineStatus shouldBe AutomaticBaselineStatus.VERIFIED
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_EVIDENCE_HORIZON_MS] shouldBe
                    midTime.toEpochMilli().toString()
            }
        }

        "balance observation time rather than snapshot write time decides live-tail eligibility" {
            runTest {
                val fixture = automaticBaselineFixture()
                fixture.metadata[SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC] =
                    (fixture.laterTime.epochSecond - 1).toString()
                fixture.ledgerMetadata[SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC] =
                    (fixture.laterTime.epochSecond - 1).toString()
                val service = automaticBaselineService(fixture)

                val status = service.getSettingsComparisonStatus(fixture.anchorTime)

                status.comparisonAvailability shouldBe ComparisonAvailability.AVAILABLE
                status.baselineStatus shouldBe AutomaticBaselineStatus.VERIFIED
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_EVIDENCE_HORIZON_MS] shouldBe
                    ((fixture.laterTime.epochSecond - 1) * 1000).toString()
            }
        }

        "coverage catch-up makes a previously unstable snapshot eligible again" {
            runTest {
                val fixture = automaticBaselineFixture()
                val midTime = fixture.anchorTime.plusSeconds(1800)
                fixture.snapshotRows += snapshot(
                    midTime,
                    "1050.00",
                    btc = "1.0" to "550.00",
                    usdBalance = "500.00",
                    balancesObservedAt = midTime.minusMillis(800),
                )
                fixture.ledgerMetadata[SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC] =
                    (fixture.laterTime.epochSecond - 2).toString()
                val service = automaticBaselineService(fixture)
                service.getSettingsComparisonStatus(fixture.anchorTime)
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_EVIDENCE_HORIZON_MS] shouldBe
                    midTime.toEpochMilli().toString()

                fixture.ledgerMetadata[SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC] =
                    (fixture.laterTime.epochSecond + 60).toString()

                val caughtUp = service.getSettingsComparisonStatus(
                    fixture.anchorTime,
                    allowPersistedBaselineFastPath = false,
                )

                caughtUp.comparisonAvailability shouldBe ComparisonAvailability.AVAILABLE
                caughtUp.baselineStatus shouldBe AutomaticBaselineStatus.VERIFIED
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_EVIDENCE_HORIZON_MS] shouldBe
                    fixture.laterTime.toEpochMilli().toString()
            }
        }

        "a covered snapshot reappearing after an uncovered one defers verification" {
            runTest {
                val fixture = automaticBaselineFixture()
                val uncoveredInterior = snapshot(
                    fixture.laterTime,
                    "1150.00",
                    btc = "1.1" to "600.00",
                    usdBalance = "550.00",
                    balancesObservedAt = fixture.laterTime.plusSeconds(60),
                )
                fixture.snapshotRows += uncoveredInterior
                fixture.snapshotRows += snapshot(
                    fixture.laterTime.plusSeconds(5),
                    "1000.00",
                    btc = "1.0" to "500.00",
                    usdBalance = "500.00",
                    balancesObservedAt = fixture.laterTime.minusSeconds(100),
                )
                fixture.metadata[SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC] =
                    (fixture.laterTime.epochSecond - 2).toString()
                fixture.ledgerMetadata[SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC] =
                    (fixture.laterTime.epochSecond - 2).toString()
                val service = automaticBaselineService(fixture)

                val status = service.getSettingsComparisonStatus(
                    fixture.anchorTime,
                    allowPersistedBaselineFastPath = false,
                )

                status.comparisonAvailability.shouldBeNull()
                status.baselineStatus.shouldBeNull()
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_STATUS].shouldBeNull()
            }
        }

        "a second uncovered snapshot after a covered re-entry also defers verification" {
            runTest {
                val fixture = automaticBaselineFixture()
                fixture.snapshotRows += snapshot(
                    fixture.laterTime,
                    "1150.00",
                    btc = "1.1" to "600.00",
                    usdBalance = "550.00",
                    balancesObservedAt = fixture.laterTime.plusSeconds(60),
                )
                fixture.snapshotRows += snapshot(
                    fixture.laterTime.plusSeconds(5),
                    "1000.00",
                    btc = "1.0" to "500.00",
                    usdBalance = "500.00",
                    balancesObservedAt = fixture.laterTime.minusSeconds(100),
                )
                fixture.snapshotRows += snapshot(
                    fixture.laterTime.plusSeconds(10),
                    "1000.00",
                    btc = "1.0" to "500.00",
                    usdBalance = "500.00",
                    balancesObservedAt = fixture.laterTime.plusSeconds(120),
                )
                fixture.metadata[SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC] =
                    (fixture.laterTime.epochSecond - 2).toString()
                fixture.ledgerMetadata[SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC] =
                    (fixture.laterTime.epochSecond - 2).toString()
                val service = automaticBaselineService(fixture)

                val status = service.getSettingsComparisonStatus(
                    fixture.anchorTime,
                    allowPersistedBaselineFastPath = false,
                )

                status.comparisonAvailability.shouldBeNull()
                status.baselineStatus.shouldBeNull()
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_STATUS].shouldBeNull()
            }
        }

        "multiple unstable tail snapshots are trimmed together" {
            runTest {
                val fixture = automaticBaselineFixture()
                val midTime = fixture.anchorTime.plusSeconds(1800)
                val tailTime = fixture.laterTime.plusSeconds(1800)
                fixture.snapshotRows += snapshot(
                    midTime,
                    "1050.00",
                    btc = "1.0" to "550.00",
                    usdBalance = "500.00",
                    balancesObservedAt = midTime.minusMillis(800),
                )
                fixture.snapshotRows += snapshot(
                    tailTime,
                    "1200.00",
                    btc = "1.0" to "650.00",
                    usdBalance = "500.00",
                    balancesObservedAt = tailTime.minusMillis(800),
                )
                fixture.snapshotRows += snapshot(
                    fixture.laterTime.plusSeconds(3600),
                    "1250.00",
                    btc = "1.0" to "700.00",
                    usdBalance = "500.00",
                    balancesObservedAt = fixture.laterTime.plusSeconds(3600).minusMillis(800),
                )
                fixture.metadata[SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC] =
                    (fixture.laterTime.epochSecond - 2).toString()
                fixture.ledgerMetadata[SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC] =
                    (fixture.laterTime.epochSecond - 2).toString()
                val service = automaticBaselineService(fixture)

                val status = service.getSettingsComparisonStatus(fixture.anchorTime)

                status.comparisonAvailability shouldBe ComparisonAvailability.AVAILABLE
                status.baselineStatus shouldBe AutomaticBaselineStatus.VERIFIED
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_EVIDENCE_HORIZON_MS] shouldBe
                    midTime.toEpochMilli().toString()
            }
        }

        "real reconciliation failure inside the stable horizon still fails closed" {
            runTest {
                val fixture = automaticBaselineFixture()
                val midTime = fixture.anchorTime.plusSeconds(1800)
                fixture.snapshotRows += snapshot(
                    midTime,
                    "1000.00",
                    btc = "0.9" to "500.00",
                    usdBalance = "550.00",
                    balancesObservedAt = midTime.minusMillis(800),
                )
                val service = automaticBaselineService(fixture)

                val status = service.getSettingsComparisonStatus(
                    fixture.anchorTime,
                    allowPersistedBaselineFastPath = false,
                )

                status.comparisonAvailability shouldBe ComparisonAvailability.UNAVAILABLE
                status.unavailableReason shouldBe ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE
                status.unavailableAt shouldBe midTime.toString()
                status.baselineStatus.shouldBeNull()
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_STATUS].shouldBeNull()
            }
        }

        "fewer than two stable snapshots defers baseline verification" {
            runTest {
                val fixture = automaticBaselineFixture()
                fixture.metadata[SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC] =
                    (fixture.anchorTime.epochSecond - 2).toString()
                fixture.ledgerMetadata[SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC] =
                    (fixture.anchorTime.epochSecond - 2).toString()
                val service = automaticBaselineService(fixture)

                val status = service.getSettingsComparisonStatus(fixture.anchorTime)

                status.comparisonAvailability.shouldBeNull()
                status.baselineStatus.shouldBeNull()
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_STATUS].shouldBeNull()
            }
        }

        "missing certified coverage metadata defers baseline verification" {
            runTest {
                val fixture = automaticBaselineFixture()
                fixture.metadata.remove(SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC)
                fixture.ledgerMetadata.remove(SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC)
                val service = automaticBaselineService(fixture)

                val status = service.getSettingsComparisonStatus(fixture.anchorTime)

                status.comparisonAvailability.shouldBeNull()
                status.baselineStatus.shouldBeNull()
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_STATUS].shouldBeNull()
            }
        }

        "persisted fast path remains usable while newer unstable snapshots exist" {
            runTest {
                val fixture = automaticBaselineFixture()
                val service = automaticBaselineService(fixture)
                service.getSettingsComparisonStatus(fixture.anchorTime)
                val persistedDigest = fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_EVIDENCE_FINGERPRINT]

                val tailTime = fixture.laterTime.plusSeconds(3600)
                fixture.snapshotRows += snapshot(
                    tailTime,
                    "1200.00",
                    btc = "1.0" to "700.00",
                    usdBalance = "500.00",
                    balancesObservedAt = tailTime.minusMillis(800),
                )
                fixture.ledgerMetadata[SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC] =
                    (fixture.laterTime.epochSecond - 2).toString()
                val reloaded = automaticBaselineService(fixture)

                val status = reloaded.getSettingsComparisonStatus(fixture.anchorTime)

                status.baselineStatus shouldBe AutomaticBaselineStatus.VERIFIED
                status.baselineTimestamp shouldBe fixture.anchorTime.toString()
                status.comparisonAvailability.shouldBeNull()
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_EVIDENCE_FINGERPRINT] shouldBe
                    persistedDigest
            }
        }

        "post-sell live snapshot beyond confirmed coverage fails the evaluation without coverage gating" {
            runTest {
                val fixture = automaticBaselineFixture()
                val postSellTime = fixture.laterTime.plusSeconds(7).plusMillis(819)
                fixture.snapshotRows += snapshot(
                    postSellTime,
                    "1005.00",
                    btc = "0.5" to "520.00",
                    usdBalance = "745.00",
                    balancesObservedAt = postSellTime.minusMillis(800),
                )
                val service = automaticBaselineService(fixture)

                val status = service.getSettingsComparisonStatus(
                    fixture.anchorTime,
                    allowPersistedBaselineFastPath = false,
                )

                status.comparisonAvailability shouldBe ComparisonAvailability.UNAVAILABLE
                status.unavailableReason shouldBe ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE
                status.unavailableAt shouldBe postSellTime.toString()
                status.baselineStatus.shouldBeNull()
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_STATUS].shouldBeNull()
            }
        }

        "post-sell live snapshot beyond confirmed coverage verifies through the stable horizon" {
            runTest {
                val fixture = automaticBaselineFixture()
                val postSellTime = fixture.laterTime.plusSeconds(7).plusMillis(819)
                fixture.snapshotRows += snapshot(
                    postSellTime,
                    "1005.00",
                    btc = "0.5" to "520.00",
                    usdBalance = "745.00",
                    balancesObservedAt = postSellTime.minusMillis(800),
                )
                fixture.metadata[SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC] =
                    (fixture.laterTime.epochSecond + 1).toString()
                fixture.ledgerMetadata[SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC] =
                    (fixture.laterTime.epochSecond + 1).toString()
                val service = automaticBaselineService(fixture)

                val status = service.getSettingsComparisonStatus(fixture.anchorTime)

                status.comparisonAvailability shouldBe ComparisonAvailability.AVAILABLE
                status.baselineStatus shouldBe AutomaticBaselineStatus.VERIFIED
                status.baselineTimestamp shouldBe fixture.anchorTime.toString()
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_EVIDENCE_HORIZON_MS] shouldBe
                    fixture.laterTime.toEpochMilli().toString()
            }
        }

        "evidence horizon is capped at the stable verification horizon when write time runs ahead" {
            runTest {
                val fixture = automaticBaselineFixture()
                val lateWriteTime = fixture.laterTime.plusSeconds(10)
                fixture.snapshotRows += snapshot(
                    lateWriteTime,
                    "1100.00",
                    btc = "1.0" to "600.00",
                    usdBalance = "500.00",
                    balancesObservedAt = fixture.laterTime.minusMillis(700),
                )
                fixture.metadata[SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC] =
                    (fixture.laterTime.epochSecond + 1).toString()
                fixture.ledgerMetadata[SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC] =
                    (fixture.laterTime.epochSecond + 1).toString()
                val service = automaticBaselineService(fixture)

                val status = service.getSettingsComparisonStatus(fixture.anchorTime)

                status.comparisonAvailability shouldBe ComparisonAvailability.AVAILABLE
                status.baselineStatus shouldBe AutomaticBaselineStatus.VERIFIED
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_EVIDENCE_HORIZON_MS] shouldBe
                    ((fixture.laterTime.epochSecond + 1) * 1000).toString()
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

    private fun corruptHoldingSnapshot(
        timestamp: Instant,
        symbol: String,
        balance: String,
        price: String,
        valueUSD: String,
    ): PortfolioSnapshot {
        val base = snapshot(
            timestamp,
            "1000.00",
            btc = "0.001" to "100000.00",
            usdBalance = "900.00",
        )
        return base.copy(
            assets = base.assets + (
                symbol to TestFixtures.assetSnapshot(
                    symbol = symbol,
                    balance = BigDecimal(balance),
                    price = BigDecimal(price),
                    valueUSD = BigDecimal(valueUSD),
                    targetPercent = BigDecimal.ZERO,
                )
                ),
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

    /** Shared state for the durable automatic baseline verification tests. */
    private data class AutomaticBaselineFixture(
        val anchorTime: Instant,
        val laterTime: Instant,
        val metadata: MutableMap<String, String>,
        val ledgerMetadata: MutableMap<String, String>,
        val snapshotRows: MutableList<PortfolioSnapshot>,
        val inceptionService: InceptionDiscoveryService,
        val fundingProvenanceResolver: FundingProvenanceResolver,
    )

    private fun automaticBaselineFixture(): AutomaticBaselineFixture {
        val anchorTime = now.minusSeconds(86400)
        val laterTime = anchorTime.plusSeconds(3600)
        val metadata = mutableMapOf<String, String>()
        val ledgerMetadata = mutableMapOf<String, String>()
        // Certified coverage defaults; tests that shape the live tail override these.
        metadata[SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC] = "4102444800"
        ledgerMetadata[SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC] = "4102444800"
        val anchorSnapshot = snapshot(
            anchorTime,
            "1000.00",
            btc = "1.0" to "500.00",
            usdBalance = "500.00",
            balancesObservedAt = anchorTime.minusMillis(800),
        )
        val laterSnapshot = snapshot(
            laterTime,
            "1100.00",
            btc = "1.0" to "600.00",
            usdBalance = "500.00",
            balancesObservedAt = laterTime.minusMillis(700),
        )
        val snapshotRows = mutableListOf(anchorSnapshot, laterSnapshot)
        val inceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
        coEvery { inceptionService.resolveInception() } returns InceptionResolution(
            inceptionTime = anchorTime,
            inceptionSnapshot = anchorSnapshot,
            isAutoDetected = false,
            confidence = InceptionConfidence.CONFIDENT,
        )
        val fundingProvenanceResolver = mockk<FundingProvenanceResolver>()
        coEvery { fundingProvenanceResolver.resolve(any()) } returns FundingEvidence.UNRESOLVED
        coEvery { fundingProvenanceResolver.isCardFunding(any()) } returns false
        coEvery { fundingProvenanceResolver.preparationFailure } returns null
        coEvery { fundingProvenanceResolver.evidenceFingerprint } returns null
        coEvery { fundingProvenanceResolver.prepare(any()) } coAnswers { fundingProvenanceResolver }
        coEvery { repository.getSyncMetadata(any()) } coAnswers { metadata[firstArg()] }
        coEvery { repository.setSyncMetadata(any(), any()) } coAnswers { metadata[firstArg()] = secondArg() }
        coEvery { repository.setSyncMetadataAtomically(any()) } coAnswers { metadata.putAll(firstArg()) }
        coEvery { ledgerRepository.getSyncMetadata(any()) } coAnswers { ledgerMetadata[firstArg()] }
        coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()
        coEvery { repository.getAllSnapshotsInRange(any(), any()) } coAnswers {
            snapshotRows.filter { !it.timestamp.isBefore(firstArg()) && !it.timestamp.isAfter(secondArg()) }
        }
        coEvery { repository.getSnapshotBefore(any()) } returns null
        coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
        coEvery { repository.getSnapshotId(any(), any()) } coAnswers {
            snapshotRows.filter { it.timestamp == firstArg<Instant>() }.getOrNull(secondArg<Int>())?.hashCode()
        }
        return AutomaticBaselineFixture(
            anchorTime,
            laterTime,
            metadata,
            ledgerMetadata,
            snapshotRows,
            inceptionService,
            fundingProvenanceResolver,
        )
    }

    private fun automaticBaselineService(fixture: AutomaticBaselineFixture) = TradeHistoryQueryService(
        repository = repository,
        portfolioStatsRepository = statsRepository,
        ledgerRepository = ledgerRepository,
        orderIntentRepository = orderIntentRepository,
        inceptionDiscoveryService = fixture.inceptionService,
        fundingProvenanceResolver = fixture.fundingProvenanceResolver,
        nowProvider = { now },
    )
}
