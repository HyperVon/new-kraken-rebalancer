package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.repository.TradeRepository
import com.gemini.krakenbot.service.ConfigService
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import java.time.Instant

class InceptionDiscoveryServiceTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    private val tradeRepository = mockk<TradeRepository>(relaxed = true)
    private val configService = mockk<ConfigService>(relaxed = true)
    private val fixedNow = Instant.parse("2026-08-01T12:00:00Z")
    private val service = InceptionDiscoveryService(tradeRepository, configService, nowProvider = { fixedNow })

    private fun testConfig(
        inceptionDate: String? = null,
        symbols: List<String> = listOf(Asset.BTC, Asset.ETH, TestFixtures.USD),
    ): AppConfig {
        val settings = TestFixtures.DEFAULT_TEST_SETTINGS.copy(inceptionDate = inceptionDate)
        val allocations = symbols.map {
            Allocation(it, 33.33)
        }
        return AppConfig(
            kraken = KrakenCredentials("test-key", "test-secret"),
            settings = settings,
            allocations = allocations,
        )
    }

    private fun dummySnapshot(timestamp: Instant): PortfolioSnapshot = PortfolioSnapshot(
        timestamp = timestamp,
        totalValueUSD = BigDecimal("10000.00"),
        assets = mapOf(
            Asset.BTC to TestFixtures.assetSnapshot(
                symbol = Asset.BTC,
                balance = BigDecimal("0.1"),
                price = BigDecimal("60000.00"),
                valueUSD = BigDecimal("6000.00"),
                targetPercent = BigDecimal("60.0"),
            ),
            TestFixtures.USD to TestFixtures.assetSnapshot(
                symbol = TestFixtures.USD,
                balance = BigDecimal("4000.00"),
                price = BigDecimal.ONE,
                valueUSD = BigDecimal("4000.00"),
                targetPercent = BigDecimal("40.0"),
            ),
        ),
        actions = emptyList(),
        drawdownPercent = BigDecimal.ZERO,
        fiatDeploymentPercent = BigDecimal.ZERO,
        effectiveUsdTargetPercent = BigDecimal.ZERO,
    )

    private fun dummyTrade(
        symbol: String,
        timestamp: Instant,
        success: Boolean = true,
        dryRun: Boolean = false,
    ): TradeRecord = TradeRecord(
        symbol = symbol,
        pair = "${symbol}USD",
        side = "buy",
        price = BigDecimal("60000.00"),
        volume = BigDecimal("0.05"),
        usdAmount = BigDecimal("3000.00"),
        fee = BigDecimal("7.80"),
        timestamp = timestamp,
        success = success,
        dryRun = dryRun,
    )

    init {
        "parseInceptionDate parses ISO-8601 and LocalDate, rejects invalid and epoch strings" {
            InceptionDiscoveryService.parseInceptionDate(null) shouldBe null
            InceptionDiscoveryService.parseInceptionDate("") shouldBe null
            InceptionDiscoveryService.parseInceptionDate("   ") shouldBe null
            InceptionDiscoveryService.parseInceptionDate("invalid-date-format") shouldBe null
            InceptionDiscoveryService.parseInceptionDate("1780740763000") shouldBe null

            val iso = "2026-06-06T10:12:43Z"
            InceptionDiscoveryService.parseInceptionDate(iso) shouldBe Instant.parse(iso)

            val dateOnly = "2026-06-06"
            InceptionDiscoveryService.parseInceptionDate(dateOnly) shouldBe
                Instant.parse("2026-06-06T00:00:00Z")
        }

        "resolveInception uses configured inception date when present" {
            runTest {
                val configuredInstant = Instant.parse("2026-06-06T00:00:00Z")
                coEvery { configService.getConfig() } returns testConfig(inceptionDate = "2026-06-06")
                val snap = dummySnapshot(configuredInstant.plusSeconds(5))
                coEvery { tradeRepository.getSnapshotsInRange(any(), any()) } returns listOf(snap)
                coEvery { tradeRepository.getSnapshotId(snap.timestamp) } returns 101

                val result = service.resolveInception()

                result.isAutoDetected shouldBe false
                result.inceptionTime shouldBe configuredInstant
                result.inceptionSnapshot shouldBe snap
                coVerify {
                    tradeRepository.setSyncMetadata(
                        SyncMetadataKeys.DETECTED_INCEPTION_EPOCH_MS,
                        configuredInstant.toEpochMilli().toString(),
                    )
                    tradeRepository.setSyncMetadata(
                        SyncMetadataKeys.INCEPTION_SNAPSHOT_ID,
                        "101",
                    )
                }
            }
        }

        "resolveInception auto-detects from rebalance trade burst when no date is configured" {
            runTest {
                coEvery { configService.getConfig() } returns testConfig(inceptionDate = null)
                val t0 = Instant.parse("2026-06-01T10:00:00Z")
                val burstTrades = listOf(
                    dummyTrade(Asset.BTC, t0),
                    dummyTrade(Asset.ETH, t0.plusMillis(1200)),
                )
                coEvery { tradeRepository.getTradesInRange(any(), any()) } returns burstTrades
                val snap = dummySnapshot(t0.minusSeconds(1))
                coEvery { tradeRepository.getSnapshotsInRange(any(), any()) } returns listOf(snap)
                coEvery { tradeRepository.getSnapshotId(snap.timestamp) } returns 102

                val result = service.resolveInception()

                result.isAutoDetected shouldBe true
                result.inceptionTime shouldBe t0
                result.inceptionSnapshot shouldBe snap
                coVerify {
                    tradeRepository.setSyncMetadata(
                        SyncMetadataKeys.DETECTED_INCEPTION_EPOCH_MS,
                        t0.toEpochMilli().toString(),
                    )
                    tradeRepository.setSyncMetadata(
                        SyncMetadataKeys.INCEPTION_SNAPSHOT_ID,
                        "102",
                    )
                }
            }
        }

        "resolveInception uses cached metadata when burst detection yields no burst" {
            runTest {
                coEvery { configService.getConfig() } returns testConfig(inceptionDate = null)
                coEvery { tradeRepository.getTradesInRange(any(), any()) } returns emptyList()
                val cachedEpoch = 1775000000000L
                coEvery {
                    tradeRepository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_EPOCH_MS)
                } returns cachedEpoch.toString()
                val snap = dummySnapshot(Instant.ofEpochMilli(cachedEpoch))
                coEvery { tradeRepository.getSnapshotsInRange(any(), any()) } returns listOf(snap)
                coEvery { tradeRepository.getSnapshotId(snap.timestamp) } returns 103

                val result = service.resolveInception()

                result.isAutoDetected shouldBe true
                result.inceptionTime shouldBe Instant.ofEpochMilli(cachedEpoch)
                result.inceptionSnapshot shouldBe snap
                coVerify {
                    tradeRepository.setSyncMetadata(
                        SyncMetadataKeys.INCEPTION_SNAPSHOT_ID,
                        "103",
                    )
                }
            }
        }

        "resolveInception falls back to earliest snapshot when no burst or cache exists" {
            runTest {
                coEvery { configService.getConfig() } returns testConfig(inceptionDate = null)
                coEvery { tradeRepository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { tradeRepository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_EPOCH_MS) } returns null
                val earliestSnap = dummySnapshot(Instant.parse("2026-05-01T00:00:00Z"))
                val laterSnap = dummySnapshot(Instant.parse("2026-05-02T00:00:00Z"))
                coEvery { tradeRepository.getSnapshotsInRange(Instant.EPOCH, any()) } returns
                    listOf(earliestSnap, laterSnap)
                coEvery { tradeRepository.getSnapshotId(earliestSnap.timestamp) } returns 104

                val result = service.resolveInception()

                result.isAutoDetected shouldBe true
                result.inceptionTime shouldBe earliestSnap.timestamp
                result.inceptionSnapshot shouldBe earliestSnap
                coVerify {
                    tradeRepository.setSyncMetadata(
                        SyncMetadataKeys.DETECTED_INCEPTION_EPOCH_MS,
                        earliestSnap.timestamp.toEpochMilli().toString(),
                    )
                    tradeRepository.setSyncMetadata(
                        SyncMetadataKeys.INCEPTION_SNAPSHOT_ID,
                        "104",
                    )
                }
            }
        }

        "resolveInception falls back to current time when database is completely empty" {
            runTest {
                coEvery { configService.getConfig() } returns testConfig(inceptionDate = null)
                coEvery { tradeRepository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { tradeRepository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_EPOCH_MS) } returns null
                coEvery { tradeRepository.getSnapshotsInRange(Instant.EPOCH, any()) } returns emptyList()

                val result = service.resolveInception()

                result.isAutoDetected shouldBe true
                result.inceptionTime shouldBe fixedNow
                result.inceptionSnapshot shouldBe null
            }
        }

        "resolveInception handles configured date with no snapshots found" {
            runTest {
                val configuredInstant = Instant.parse("2026-06-06T00:00:00Z")
                coEvery { configService.getConfig() } returns testConfig(inceptionDate = "2026-06-06")
                coEvery { tradeRepository.getSnapshotsInRange(any(), any()) } returns emptyList()
                coEvery { tradeRepository.getSnapshotBefore(any()) } returns null

                val result = service.resolveInception()

                result.isAutoDetected shouldBe false
                result.inceptionTime shouldBe configuredInstant
                result.inceptionSnapshot shouldBe null
                coVerify {
                    tradeRepository.setSyncMetadata(
                        SyncMetadataKeys.DETECTED_INCEPTION_EPOCH_MS,
                        configuredInstant.toEpochMilli().toString(),
                    )
                }
                coVerify(exactly = 0) {
                    tradeRepository.setSyncMetadata(
                        SyncMetadataKeys.INCEPTION_SNAPSHOT_ID,
                        any(),
                    )
                }
            }
        }

        "resolveInception ignores non-positive cached epoch and proceeds to burst detection" {
            runTest {
                coEvery { configService.getConfig() } returns testConfig(inceptionDate = null)
                coEvery {
                    tradeRepository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_EPOCH_MS)
                } returns "-100"
                val t0 = Instant.parse("2026-06-01T10:00:00Z")
                val burstTrades = listOf(
                    dummyTrade(Asset.BTC, t0),
                    dummyTrade(Asset.ETH, t0.plusMillis(1200)),
                )
                coEvery { tradeRepository.getTradesInRange(any(), any()) } returns burstTrades
                coEvery { tradeRepository.getSnapshotsInRange(any(), any()) } returns emptyList()
                coEvery { tradeRepository.getSnapshotBefore(any()) } returns null

                val result = service.resolveInception()

                result.isAutoDetected shouldBe true
                result.inceptionTime shouldBe t0
                result.inceptionSnapshot shouldBe null
            }
        }

        "detectBurstInception returns null when configured symbols has only USD" {
            runTest {
                coEvery { configService.getConfig() } returns testConfig(symbols = listOf(TestFixtures.USD))
                service.detectBurstInception() shouldBe null
            }
        }

        "detectBurstInception returns null when trade list is empty" {
            runTest {
                coEvery { configService.getConfig() } returns testConfig()
                coEvery { tradeRepository.getTradesInRange(any(), any()) } returns emptyList()
                service.detectBurstInception() shouldBe null
            }
        }

        "detectBurstInception ignores failed or dryRun trades and single isolated trades" {
            runTest {
                coEvery { configService.getConfig() } returns testConfig()
                val t0 = Instant.parse("2026-06-01T10:00:00Z")
                val trades = listOf(
                    dummyTrade(Asset.BTC, t0, success = false),
                    dummyTrade(Asset.ETH, t0.plusMillis(500), dryRun = true),
                    dummyTrade(Asset.BTC, t0.plusSeconds(60)),
                    dummyTrade(Asset.ETH, t0.plusSeconds(120)), // > 5000ms gap
                )
                coEvery { tradeRepository.getTradesInRange(any(), any()) } returns trades
                service.detectBurstInception() shouldBe null
            }
        }

        "detectBurstInception handles non-configured symbol in cluster gracefully" {
            runTest {
                coEvery { configService.getConfig() } returns testConfig(symbols = listOf(Asset.BTC, TestFixtures.USD))
                val t0 = Instant.parse("2026-06-01T10:00:00Z")
                val trades = listOf(
                    dummyTrade("SOL", t0), // Not in config
                    dummyTrade(Asset.BTC, t0.plusMillis(1000)),
                )
                coEvery { tradeRepository.getTradesInRange(any(), any()) } returns trades
                // Only 1 configured symbol in cluster (BTC), need 2
                service.detectBurstInception() shouldBe null
            }
        }

        "resolveInception ignores non-positive cached epoch and falls through" {
            runTest {
                coEvery { configService.getConfig() } returns testConfig(inceptionDate = null)
                coEvery { tradeRepository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery {
                    tradeRepository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_EPOCH_MS)
                } returns "0"
                val earliestSnap = dummySnapshot(Instant.parse("2026-05-01T00:00:00Z"))
                coEvery { tradeRepository.getSnapshotsInRange(Instant.EPOCH, any()) } returns listOf(earliestSnap)

                val result = service.resolveInception()
                result.inceptionTime shouldBe earliestSnap.timestamp
            }
        }

        "detectBurstInception filters out ZUSD and detects burst when unconfigured trades are present" {
            runTest {
                coEvery { configService.getConfig() } returns testConfig(symbols = listOf("BTC", "ETH", "ZUSD"))
                val t0 = Instant.parse("2026-06-01T10:00:00Z")
                val trades = listOf(
                    dummyTrade(Asset.BTC, t0),
                    dummyTrade("SOL", t0.plusMillis(1000)), // unconfigured inside burst
                    dummyTrade(Asset.ETH, t0.plusMillis(2000)), // configured inside burst -> triggers!
                )
                coEvery { tradeRepository.getTradesInRange(any(), any()) } returns trades
                val snap = dummySnapshot(t0)
                coEvery { tradeRepository.getSnapshotsInRange(any(), any()) } returns listOf(snap)

                val res = service.detectBurstInception()
                res?.inceptionTime shouldBe t0
            }
        }

        "detectBurstInception handles cluster reset with unconfigured symbol" {
            runTest {
                coEvery { configService.getConfig() } returns
                    testConfig(symbols = listOf("BTC", "ETH", TestFixtures.USD))
                val t0 = Instant.parse("2026-06-01T10:00:00Z")
                val trades = listOf(
                    dummyTrade(Asset.BTC, t0),
                    dummyTrade("SOL", t0.plusSeconds(10)), // gap > 5s, unconfigured
                    dummyTrade(Asset.ETH, t0.plusSeconds(20)), // gap > 5s, configured but alone
                )
                coEvery { tradeRepository.getTradesInRange(any(), any()) } returns trades

                service.detectBurstInception() shouldBe null
            }
        }

        "findClosestSnapshot respects proximity bound and rejects distant snapshots" {
            runTest {
                val targetTime = Instant.parse("2026-06-01T10:00:00Z")
                coEvery { tradeRepository.getSnapshotsInRange(any(), any()) } returns emptyList()
                val snapWithinBound = dummySnapshot(targetTime.minusSeconds(100))
                coEvery { tradeRepository.getSnapshotBefore(any()) } returns snapWithinBound

                val res1 = service.findClosestSnapshot(targetTime)
                res1 shouldBe snapWithinBound

                val snapTooFar = dummySnapshot(targetTime.minusSeconds(350))
                coEvery { tradeRepository.getSnapshotBefore(any()) } returns snapTooFar

                val res2 = service.findClosestSnapshot(targetTime)
                res2 shouldBe null
            }
        }

        "detectBurstInception rejects chained adjacent trades when total cluster span exceeds 5 seconds" {
            runTest {
                coEvery { configService.getConfig() } returns
                    testConfig(symbols = listOf(Asset.BTC, Asset.ETH, TestFixtures.USD))
                val t0 = Instant.parse("2026-06-01T10:00:00Z")
                // 3 trades: T0 (BTC), T0 + 4.9s (BTC), T0 + 9.8s (ETH).
                // Adjacent gaps are 4.9s <= 5s, but total cluster span is 9.8s > 5s.
                // It must NOT group them into a single 3-trade burst and trigger inception on ETH!
                val trades = listOf(
                    dummyTrade(Asset.BTC, t0),
                    dummyTrade(Asset.BTC, t0.plusMillis(4900)),
                    dummyTrade(Asset.ETH, t0.plusMillis(9800)),
                )
                coEvery { tradeRepository.getTradesInRange(any(), any()) } returns trades

                service.detectBurstInception() shouldBe null
            }
        }

        "InceptionResolution data class properties and copy" {
            val res = InceptionResolution(
                inceptionTime = fixedNow,
                inceptionSnapshot = null,
                isAutoDetected = true,
            )
            res.inceptionTime shouldBe fixedNow
            res.inceptionSnapshot shouldBe null
            res.isAutoDetected shouldBe true
            val copy = res.copy(isAutoDetected = false)
            copy.isAutoDetected shouldBe false
        }
    }
}
