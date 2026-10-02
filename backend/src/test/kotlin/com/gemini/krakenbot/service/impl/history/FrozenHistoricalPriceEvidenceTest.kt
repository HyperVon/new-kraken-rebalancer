@file:OptIn(ExperimentalCoroutinesApi::class)

package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.repository.TradeRepository
import com.gemini.krakenbot.service.FakeKrakenService
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import java.time.Instant

class FrozenHistoricalPriceEvidenceTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    private val baseTime = Instant.parse("2026-07-01T12:00:00Z")

    private fun trade(
        id: Int,
        timestamp: Instant,
        pair: String = "XXBTZUSD",
        symbol: String = "BTC",
        usdAmount: String = "50000.00",
        volume: String = "1.0",
        success: Boolean = true,
        dryRun: Boolean = false,
    ) = TradeRecord(
        id = id,
        timestamp = timestamp,
        pair = pair,
        symbol = symbol,
        side = "buy",
        volume = BigDecimal(volume),
        price = BigDecimal(usdAmount).divide(BigDecimal(volume)),
        usdAmount = BigDecimal(usdAmount),
        fee = BigDecimal("10.00"),
        success = success,
        dryRun = dryRun,
    )

    private fun snapshot(
        timestamp: Instant,
        btcPrice: String = "50000.00",
        balancesObservedAt: Instant? = timestamp,
    ): PortfolioSnapshot {
        val btcVal = BigDecimal("1.0").multiply(BigDecimal(btcPrice))
        return PortfolioSnapshot(
            timestamp = timestamp,
            totalValueUSD = btcVal.add(BigDecimal("1000.00")),
            assets = mapOf(
                Asset.BTC to TestFixtures.assetSnapshot(
                    symbol = Asset.BTC,
                    balance = BigDecimal("1.0"),
                    price = BigDecimal(btcPrice),
                    valueUSD = btcVal,
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

    init {
        "trades query returns empty list when from is strictly after to" {
            runTest {
                val trades = listOf(
                    trade(1, baseTime),
                    trade(2, baseTime.plusSeconds(10)),
                )
                val frozen = FrozenHistoricalPriceEvidence(trades, emptyList())

                frozen.getTradesInRange(baseTime.plusSeconds(20), baseTime.plusSeconds(10)) shouldBe emptyList()
            }
        }

        "trades query returns empty list on empty evidence" {
            runTest {
                val frozen = FrozenHistoricalPriceEvidence(emptyList(), emptyList())
                frozen.getTradesInRange(baseTime, baseTime.plusSeconds(60)) shouldBe emptyList()
            }
        }

        "trades query returns descending order and includes boundary matches" {
            runTest {
                val t0 = baseTime
                val t1 = baseTime.plusSeconds(10)
                val t2 = baseTime.plusSeconds(20)
                val t3 = baseTime.plusSeconds(30)
                val t4 = baseTime.plusSeconds(40)

                val trades = listOf(
                    trade(1, t0),
                    trade(2, t1),
                    trade(3, t2),
                    trade(4, t3),
                    trade(5, t4),
                )
                val frozen = FrozenHistoricalPriceEvidence(trades, emptyList())

                val result = frozen.getTradesInRange(t1, t3)
                result.map { it.id } shouldBe listOf(4, 3, 2) // descending: t3, t2, t1
            }
        }

        "trades query preserves duplicate timestamps and their relative ordering" {
            runTest {
                val t1 = baseTime.plusSeconds(10)
                val t2 = baseTime.plusSeconds(20)

                val trade2a = trade(2, t1, usdAmount = "20000.00")
                val trade2b = trade(3, t1, usdAmount = "25000.00")
                val trade3 = trade(4, t2)

                val trades = listOf(trade(1, baseTime), trade2a, trade2b, trade3)
                val frozen = FrozenHistoricalPriceEvidence(trades, emptyList())

                val result = frozen.getTradesInRange(t1, t1)
                result.map { it.id } shouldBe listOf(2, 3)
            }
        }

        "snapshots query returns ascending order and includes boundary matches" {
            runTest {
                val s0 = snapshot(baseTime)
                val s1 = snapshot(baseTime.plusSeconds(60), btcPrice = "51000.00")
                val s2 = snapshot(baseTime.plusSeconds(120), btcPrice = "52000.00")
                val s3 = snapshot(baseTime.plusSeconds(180), btcPrice = "53000.00")

                val frozen = FrozenHistoricalPriceEvidence(emptyList(), listOf(s3, s0, s2, s1))

                val result = frozen.getSnapshotsInRange(baseTime.plusSeconds(60), baseTime.plusSeconds(120))
                result.map { it.timestamp } shouldBe listOf(baseTime.plusSeconds(60), baseTime.plusSeconds(120))
            }
        }

        "snapshots query returns empty list when window has no overlapping snapshots" {
            runTest {
                val s1 = snapshot(baseTime)
                val s2 = snapshot(baseTime.plusSeconds(120))
                val frozen = FrozenHistoricalPriceEvidence(emptyList(), listOf(s1, s2))

                frozen.getSnapshotsInRange(baseTime.plusSeconds(10), baseTime.plusSeconds(100)) shouldBe emptyList()
            }
        }

        "HistoricalPriceResolver gives identical prices with LiveTradeRepository vs FrozenPriceEvidence" {
            runTest {
                val tTrade = baseTime.minusSeconds(30)
                val tSnap = baseTime.minusSeconds(60)
                val trades = listOf(trade(1, tTrade, volume = "2.0", usdAmount = "104000.00"))
                val snapshots = listOf(snapshot(tSnap, btcPrice = "51000.00"))

                val mockRepo = mockk<TradeRepository>()
                coEvery { mockRepo.getTradesInRange(any(), any()) } coAnswers {
                    val from = firstArg<Instant>()
                    val to = secondArg<Instant>()
                    trades.filter { !it.timestamp.isBefore(from) && !it.timestamp.isAfter(to) }
                        .sortedByDescending { it.timestamp }
                }
                coEvery { mockRepo.getSnapshotsInRange(any(), any()) } coAnswers {
                    val from = firstArg<Instant>()
                    val to = secondArg<Instant>()
                    snapshots.filter { !it.timestamp.isBefore(from) && !it.timestamp.isAfter(to) }
                        .sortedBy { it.timestamp }
                }

                val liveReader = LiveTradeRepositoryPriceEvidenceReader(mockRepo)
                val frozenReader = FrozenHistoricalPriceEvidence(trades, snapshots)

                val fakeKraken = FakeKrakenService()

                val priceLive = HistoricalPriceResolver.resolveHistoricalPrice(
                    asset = "BTC",
                    eventTime = baseTime,
                    priceEvidence = liveReader,
                    krakenService = fakeKraken,
                )

                val priceFrozen = HistoricalPriceResolver.resolveHistoricalPrice(
                    asset = "BTC",
                    eventTime = baseTime,
                    priceEvidence = frozenReader,
                    krakenService = fakeKraken,
                )

                priceLive.shouldNotBeNull()
                priceFrozen.shouldNotBeNull()
                priceLive.shouldBeEqualComparingTo(priceFrozen)
                priceFrozen.shouldBeEqualComparingTo(BigDecimal("52000.00"))
            }
        }

        "HistoricalPriceResolver falls back to snapshot price when no trade is within window" {
            runTest {
                val tSnap = baseTime.minusSeconds(100)
                val snapshots = listOf(snapshot(tSnap, btcPrice = "58000.00"))
                val frozenReader = FrozenHistoricalPriceEvidence(emptyList(), snapshots)

                val price = HistoricalPriceResolver.resolveHistoricalPrice(
                    asset = "BTC",
                    eventTime = baseTime,
                    priceEvidence = frozenReader,
                    krakenService = FakeKrakenService(),
                )

                price.shouldNotBeNull()
                price.shouldBeEqualComparingTo(BigDecimal("58000.00"))
            }
        }

        "HistoricalPriceResolver respects observation times on snapshots" {
            runTest {
                // Snapshot written before eventTime, but observation is in the future
                val sFutureObs =
                    snapshot(
                        baseTime.minusSeconds(10),
                        btcPrice = "60000.00",
                        balancesObservedAt = baseTime.plusSeconds(5),
                    )
                val frozenReader = FrozenHistoricalPriceEvidence(emptyList(), listOf(sFutureObs))

                val price = HistoricalPriceResolver.resolveHistoricalPrice(
                    asset = "BTC",
                    eventTime = baseTime,
                    priceEvidence = frozenReader,
                    krakenService = FakeKrakenService(),
                )

                price shouldBe null
            }
        }

        "getTradesInRange and getSnapshotsInRange return empty list when from is after to" {
            runTest {
                val trades = listOf(trade(1, baseTime))
                val snapshots = listOf(snapshot(baseTime))
                val reader = FrozenHistoricalPriceEvidence(trades, snapshots)

                reader.getTradesInRange(baseTime.plusSeconds(10), baseTime) shouldBe emptyList()
                reader.getSnapshotsInRange(baseTime.plusSeconds(10), baseTime) shouldBe emptyList()
            }
        }

        "HistoricalPriceResolver returns null when futureTradeUpperBound is before tradeLookbackStart" {
            runTest {
                val t = trade(1, baseTime.minusSeconds(100))
                val reader = FrozenHistoricalPriceEvidence(listOf(t), emptyList())

                val price = HistoricalPriceResolver.resolveHistoricalPrice(
                    asset = "BTC",
                    eventTime = baseTime,
                    priceEvidence = reader,
                    krakenService = FakeKrakenService(),
                    futureTradeUpperBound = baseTime.minusSeconds(100000),
                )

                price shouldBe null
            }
        }
    }
}
