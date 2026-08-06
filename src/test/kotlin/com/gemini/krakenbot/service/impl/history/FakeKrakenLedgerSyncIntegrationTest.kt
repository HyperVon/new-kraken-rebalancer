package com.gemini.krakenbot.service.impl.history

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.DatabaseConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.repository.impl.SqliteLedgerRepositoryImpl
import com.gemini.krakenbot.repository.impl.SqlitePortfolioStatsRepositoryImpl
import com.gemini.krakenbot.repository.impl.SqliteTradeRepositoryImpl
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.FakeKrakenService
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class FakeKrakenLedgerSyncIntegrationTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    private val db = DatabaseConfig.init(TestFixtures.MEMORY_)
    private val ledgerRepository = SqliteLedgerRepositoryImpl(db)
    private val tradeRepository = SqliteTradeRepositoryImpl(db)
    private val portfolioStatsRepository = SqlitePortfolioStatsRepositoryImpl(db, jacksonObjectMapper())
    private val fakeKraken = FakeKrakenService()
    private val configService = mockk<ConfigService>(relaxed = true)

    private val baseTime = Instant.parse("2026-06-25T12:00:00Z")
    private val fixedNow = Instant.parse("2026-07-01T12:00:00Z")

    private val appConfig =
        AppConfig(
            kraken = KrakenCredentials(TestFixtures.TRADE_HISTORY_API_KEY, TestFixtures.TRADE_HISTORY_API_SECRET),
            settings = TestFixtures.settings(dryRun = false, simulation = false, loopDelaySeconds = 60),
            allocations = emptyList(),
        )

    init {
        "syncLedgersFromKraken persists seeded staking and dividend entries end to end" {
            every { configService.getConfig() } returns appConfig
            fakeKraken.seedLedgerEntries(
                listOf(
                    ledgerEntry(1, baseTime),
                    ledgerEntry(2, baseTime.plusSeconds(600)),
                    ledgerEntry(3, baseTime.plusSeconds(1200), LedgerEvent.TYPE_DIVIDEND, "STRC", "1.25"),
                    ledgerEntry(4, baseTime.plusSeconds(1800), "trade", "XBT"),
                ),
            )
            val syncService =
                LedgersSyncService(ledgerRepository, fakeKraken, configService, nowProvider = { fixedNow })

            syncService.syncLedgersFromKraken()

            val stored = ledgerRepository.getLedgersInRange(Instant.EPOCH, fixedNow.plusSeconds(86400))
            stored.size shouldBe 3
            stored.map { it.type }.toSet() shouldBe setOf(LedgerEvent.TYPE_STAKING, LedgerEvent.TYPE_DIVIDEND)
            stored.map { it.ledgerId }.toSet() shouldBe setOf("ledger-1", "ledger-2", "ledger-3")

            ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGERS_SEEDED) shouldBe "true"
            ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_OFFSET) shouldBe SyncMetadataKeys.COMPLETED
            ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_TOTAL) shouldBe SyncMetadataKeys.COMPLETED
            ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC) shouldBe
                fixedNow.epochSecond.toString()

            val callsAfterFirstSync = fakeKraken.getLedgersCallCount
            syncService.syncLedgersFromKraken()
            fakeKraken.getLedgersCallCount shouldBe callsAfterFirstSync
        }

        "getRewardsOverTime values seeded staking rewards at snapshot prices end to end" {
            every { configService.getConfig() } returns appConfig
            fakeKraken.seedLedgerEntries(
                listOf(
                    ledgerEntry(1, baseTime, asset = "BTC"),
                    ledgerEntry(2, baseTime.plusSeconds(900), asset = "BTC"),
                    ledgerEntry(3, baseTime.plusSeconds(1800), LedgerEvent.TYPE_DIVIDEND, "STRC", "1.25"),
                ),
            )
            LedgersSyncService(ledgerRepository, fakeKraken, configService, nowProvider = { fixedNow })
                .syncLedgersFromKraken()

            tradeRepository.saveSnapshot(snapshot(baseTime, "100000.00", btc = "1.0" to "50000.00"))
            tradeRepository.saveSnapshot(snapshot(baseTime.plusSeconds(1800), "100000.00", btc = "1.0" to "50000.00"))

            val rewards =
                TradeHistoryQueryService(tradeRepository, portfolioStatsRepository, ledgerRepository)
                    .getRewardsOverTime(baseTime.minusSeconds(60), baseTime.plusSeconds(3600))

            rewards.points.size shouldBe 2
            rewards.points[0].cumulativeUSD shouldBeEqualComparingTo BigDecimal("5000.00")
            rewards.points[1].cumulativeUSD shouldBeEqualComparingTo BigDecimal("10000.00")
            rewards.totalRewardsUSD shouldBeEqualComparingTo BigDecimal("10000.00")
        }
    }

    private fun ledgerEntry(
        index: Int,
        time: Instant,
        type: String = LedgerEvent.TYPE_STAKING,
        asset: String = "XBT",
        amount: String = "0.1",
    ): LedgerEvent = LedgerEvent(
        ledgerId = "ledger-$index",
        time = time,
        type = type,
        asset = asset,
        amount = BigDecimal(amount),
    )

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
}
