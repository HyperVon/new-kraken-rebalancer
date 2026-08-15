package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.ComparisonAvailability
import com.gemini.krakenbot.model.ComparisonConfidence
import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.repository.LedgerRepository
import com.gemini.krakenbot.repository.PortfolioStatsRepository
import com.gemini.krakenbot.repository.TradeRepository
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import java.time.Instant

class TradeHistoryQueryServiceTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    private val repository = mockk<TradeRepository>(relaxed = true)
    private val statsRepository = mockk<PortfolioStatsRepository>(relaxed = true)
    private val ledgerRepository = mockk<LedgerRepository>(relaxed = true)
    private val service = TradeHistoryQueryService(repository, statsRepository, ledgerRepository)

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
                val dividend = ledgerEvent("L2", now.plusSeconds(1200), "STRC", "1.25", LedgerEvent.TYPE_DIVIDEND)
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns listOf(staking, dividend)

                val comparison = service.getRebalancerComparison(Instant.EPOCH, now.plusSeconds(1800))

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.confidence shouldBe ComparisonConfidence.RECONCILED
            }
        }

        "getRebalancerComparison_DividendOnly_DoesNotReconcile" {
            runTest {
                val snap1 = snapshot(now, "100000.00", btc = "1.0" to "50000.00")
                val snap2 = snapshot(now.plusSeconds(1800), "105000.00", btc = "1.1" to "50000.00")
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns
                    listOf(ledgerEvent("L1", now.plusSeconds(600), "STRC", "1.25", LedgerEvent.TYPE_DIVIDEND))

                val comparison = service.getRebalancerComparison(Instant.EPOCH, now.plusSeconds(1800))

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.confidence shouldBe ComparisonConfidence.ESTIMATED
            }
        }
    }

    private fun snapshot(timestamp: Instant, totalValueUSD: String, btc: Pair<String, String>): PortfolioSnapshot {
        val (btcBalance, btcPrice) = btc
        val btcValue = BigDecimal(btcBalance).multiply(BigDecimal(btcPrice))
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
                    balance = BigDecimal("50000.00"),
                    price = BigDecimal.ONE,
                    valueUSD = BigDecimal("50000.00"),
                    targetPercent = BigDecimal.ZERO,
                ),
            ),
            actions = emptyList(),
            drawdownPercent = BigDecimal.ZERO,
            fiatDeploymentPercent = BigDecimal.ZERO,
            effectiveUsdTargetPercent = BigDecimal.ZERO,
        )
    }

    private fun ledgerEvent(
        ledgerId: String,
        timestamp: Instant,
        asset: String,
        amount: String,
        type: String = LedgerEvent.TYPE_STAKING,
    ): LedgerEvent = LedgerEvent(
        ledgerId = ledgerId,
        time = timestamp,
        type = type,
        asset = asset,
        amount = BigDecimal(amount),
    )
}
