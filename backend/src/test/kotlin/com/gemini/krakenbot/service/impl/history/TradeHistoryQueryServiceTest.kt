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
import com.gemini.krakenbot.service.FakeKrakenService
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.service.impl.KrakenFundingProvenanceResolver
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
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
                        TradeHistoryQueryService.PURE_BENCHMARK_ANCHOR_FLOOR,
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

        "getRebalancerComparison_ReanchorsAtEarliestRecordedSnapshotAfterFloor" {
            runTest {
                val anchorTime = TradeHistoryQueryService.PURE_BENCHMARK_ANCHOR_FLOOR.plusSeconds(300)
                val laterTime = anchorTime.plusSeconds(3600)
                val reconstructedAtFloor = snapshot(
                    TradeHistoryQueryService.PURE_BENCHMARK_ANCHOR_FLOOR,
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
                        TradeHistoryQueryService.PURE_BENCHMARK_ANCHOR_FLOOR,
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
                        TradeHistoryQueryService.PURE_BENCHMARK_ANCHOR_FLOOR,
                        openEndedRangeEnd,
                    )
                }
            }
        }

        "getRebalancerComparison_ExcludesLegacyDerivedRowsThroughTheirMetadataSecond" {
            runTest {
                val floor = TradeHistoryQueryService.PURE_BENCHMARK_ANCHOR_FLOOR
                val derivedTime = floor.plusMillis(500)
                val anchorTime = floor.plusSeconds(2)
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
                    SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_START_EPOCH_SEC to floor.epochSecond.toString(),
                    SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_THROUGH_EPOCH_SEC to floor.epochSecond.toString(),
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
                    repository.getAllSnapshotsInRange(floor, openEndedRangeEnd)
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
                val floor = TradeHistoryQueryService.PURE_BENCHMARK_ANCHOR_FLOOR
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

        "getRebalancerComparison_anchorSearchRejectsUnqualifiedRows" {
            runTest {
                val floor = TradeHistoryQueryService.PURE_BENCHMARK_ANCHOR_FLOOR
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
                val floor = TradeHistoryQueryService.PURE_BENCHMARK_ANCHOR_FLOOR
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
                val floor = TradeHistoryQueryService.PURE_BENCHMARK_ANCHOR_FLOOR
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
                val floor = TradeHistoryQueryService.PURE_BENCHMARK_ANCHOR_FLOOR
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
                val floor = TradeHistoryQueryService.PURE_BENCHMARK_ANCHOR_FLOOR
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
                val floor = TradeHistoryQueryService.PURE_BENCHMARK_ANCHOR_FLOOR
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
                val floor = TradeHistoryQueryService.PURE_BENCHMARK_ANCHOR_FLOOR
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
                val floor = TradeHistoryQueryService.PURE_BENCHMARK_ANCHOR_FLOOR
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
                val floor = TradeHistoryQueryService.PURE_BENCHMARK_ANCHOR_FLOOR
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
                val anchorTime = TradeHistoryQueryService.PURE_BENCHMARK_ANCHOR_FLOOR.plusSeconds(300)
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
                val candidates = listOf(
                    snapshot(now.plusSeconds(3600), "130000.00", btc = "2.0" to "40000.00"),
                    snapshot(now.plusSeconds(7200), "170000.00", btc = "3.0" to "40000.00"),
                    snapshot(now.plusSeconds(10800), "210000.00", btc = "4.0" to "40000.00"),
                )
                val metadata = mutableMapOf<String, String>()
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
                coEvery { repository.getSnapshotsInRange(t0, openEndedRangeEnd) } returns candidates
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
