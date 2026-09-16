package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.model.TradeSource
import com.gemini.krakenbot.repository.TradeRepository
import com.gemini.krakenbot.service.FakeKrakenService
import com.gemini.krakenbot.service.KrakenService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

class HistoricalPriceResolverTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    private val repository = mockk<TradeRepository>(relaxed = true)
    private val krakenService = mockk<KrakenService>(relaxed = true)
    private val eventTime = Instant.parse("2026-08-01T12:00:00Z")

    init {
        "the OHLC fallback reuses cached candles across repeated valuations" {
            runTest {
                val counter = AtomicInteger(0)
                val backing = FakeKrakenService().apply {
                    ohlcSupplier = { _, _, _ ->
                        counter.incrementAndGet()
                        listOf(eventTime.minusSeconds(90_000).epochSecond to BigDecimal("89332.40"))
                    }
                }
                val cache = HistoricalOhlcCache(backing)

                val first =
                    HistoricalPriceResolver.resolveHistoricalPrice(
                        Asset.BTC,
                        eventTime,
                        repository,
                        krakenService,
                        ohlcCache = cache,
                    )
                val second =
                    HistoricalPriceResolver.resolveHistoricalPrice(
                        Asset.BTC,
                        eventTime,
                        repository,
                        krakenService,
                        ohlcCache = cache,
                    )

                counter.get() shouldBe 4
                second!! shouldBeEqualComparingTo first!!
            }
        }

        "USD and the narrow candidate exception resolve without market history" {
            runTest {
                HistoricalPriceResolver.resolveHistoricalPrice(
                    Asset.USD,
                    eventTime,
                    repository,
                    krakenService,
                )!! shouldBeEqualComparingTo BigDecimal.ONE
                HistoricalPriceResolver.resolveHistoricalPrice(
                    Asset.BTC,
                    eventTime,
                    repository,
                    krakenService,
                    candidatePriceException = BigDecimal("100.00"),
                )!! shouldBeEqualComparingTo BigDecimal("100.00")
                HistoricalPriceResolver.resolveHistoricalPrice(
                    Asset.BTC,
                    eventTime,
                    repository,
                    krakenService,
                    candidatePriceException = BigDecimal.ZERO,
                ) shouldBe null
            }
        }

        "a recent authoritative trade price wins and malformed economics fall back to its price" {
            runTest {
                val trade = trade(price = BigDecimal("101.00"), volume = BigDecimal("0.01"), usd = BigDecimal("1.01"))
                coEvery { repository.getTradesInRange(any(), any()) } returns listOf(trade)

                HistoricalPriceResolver.resolveHistoricalPrice(
                    Asset.BTC,
                    eventTime,
                    repository,
                    krakenService,
                )!! shouldBeEqualComparingTo BigDecimal("101.00")

                val volumeMissing = trade.copy(volume = BigDecimal.ZERO)
                coEvery { repository.getTradesInRange(any(), any()) } returns listOf(volumeMissing)
                HistoricalPriceResolver.resolveHistoricalPrice(
                    Asset.BTC,
                    eventTime,
                    repository,
                    krakenService,
                )!! shouldBeEqualComparingTo BigDecimal("101.00")

                val unusable = volumeMissing.copy(price = BigDecimal.ZERO)
                coEvery { repository.getTradesInRange(any(), any()) } returns listOf(unusable)
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns emptyList()
                coEvery { krakenService.getOHLC(any(), any(), any()) } returns emptyList()
                HistoricalPriceResolver.resolveHistoricalPrice(
                    Asset.BTC,
                    eventTime,
                    repository,
                    krakenService,
                ) shouldBe null

                val priceOnly = trade.copy(usdAmount = BigDecimal.ZERO)
                coEvery { repository.getTradesInRange(any(), any()) } returns listOf(priceOnly)
                HistoricalPriceResolver.resolveHistoricalPrice(
                    Asset.BTC,
                    eventTime,
                    repository,
                    krakenService,
                )!! shouldBeEqualComparingTo BigDecimal("101.00")
            }
        }

        "a recent snapshot or a completed fresh OHLC candle supplies the fallback price" {
            runTest {
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns
                    listOf(snapshot(eventTime.minusSeconds(60)).copy(balancesObservedAt = null))

                HistoricalPriceResolver.resolveHistoricalPrice(
                    Asset.BTC,
                    eventTime,
                    repository,
                    krakenService,
                )!! shouldBeEqualComparingTo BigDecimal("99.00")

                val zeroSnapshot = snapshot(eventTime.minusSeconds(60)).copy(
                    assets = mapOf(
                        Asset.BTC to TestFixtures.assetSnapshot(
                            symbol = Asset.BTC,
                            balance = BigDecimal("0.01"),
                            price = BigDecimal.ZERO,
                            valueUSD = BigDecimal.ZERO,
                            targetPercent = BigDecimal("100.00"),
                        ),
                    ),
                )
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(zeroSnapshot)
                coEvery { krakenService.getOHLC(any(), any(), any()) } returns emptyList()
                HistoricalPriceResolver.resolveHistoricalPrice(
                    Asset.BTC,
                    eventTime,
                    repository,
                    krakenService,
                ) shouldBe null

                coEvery { repository.getSnapshotsInRange(any(), any()) } returns emptyList()
                coEvery { krakenService.getOHLC(any(), any(), any()) } returns listOf(
                    eventTime.minusSeconds(901).epochSecond to BigDecimal("98.00"),
                )
                HistoricalPriceResolver.resolveHistoricalPrice(
                    Asset.BTC,
                    eventTime,
                    repository,
                    krakenService,
                )!! shouldBeEqualComparingTo BigDecimal("98.00")

                coEvery { krakenService.getOHLC(any(), any(), any()) } returns listOf(
                    eventTime.minusSeconds(901).epochSecond to BigDecimal.ZERO,
                )
                HistoricalPriceResolver.resolveHistoricalPrice(
                    Asset.BTC,
                    eventTime,
                    repository,
                    krakenService,
                ) shouldBe null
            }
        }

        "future, incomplete, stale, and failed OHLC evidence remains unavailable" {
            runTest {
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns emptyList()
                coEvery { krakenService.getOHLC(any(), any(), any()) } returns listOf(
                    eventTime.epochSecond to BigDecimal("98.00"),
                    eventTime.minusSeconds(1801).epochSecond to BigDecimal("97.00"),
                )
                HistoricalPriceResolver.resolveHistoricalPrice(
                    Asset.BTC,
                    eventTime,
                    repository,
                    krakenService,
                ) shouldBe null

                coEvery { krakenService.getOHLC(any(), any(), any()) } throws IllegalStateException("unavailable")
                val failure = shouldThrow<HistoricalPriceSourceException> {
                    HistoricalPriceResolver.resolveHistoricalPrice(
                        Asset.BTC,
                        eventTime,
                        repository,
                        krakenService,
                    )
                }
                failure.asset shouldBe Asset.BTC
            }
        }

        "future and unusable trade or snapshot observations are ignored" {
            runTest {
                val unusable = trade(price = BigDecimal.ZERO, volume = BigDecimal.ZERO, usd = BigDecimal.ZERO)
                    .copy(timestamp = eventTime.plusSeconds(1), success = false)
                val futureSnapshot = snapshot(eventTime.plusSeconds(1))
                coEvery { repository.getTradesInRange(any(), any()) } returns listOf(unusable)
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(futureSnapshot)
                coEvery { krakenService.getOHLC(any(), any(), any()) } returns emptyList()

                HistoricalPriceResolver.resolveHistoricalPrice(
                    Asset.BTC,
                    eventTime,
                    repository,
                    krakenService,
                ) shouldBe null

                val observationFuture = snapshot(eventTime.minusSeconds(60)).copy(
                    balancesObservedAt = eventTime.plusSeconds(1),
                )
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(observationFuture)
                coEvery { krakenService.getOHLC(any(), any(), any()) } returns emptyList()
                HistoricalPriceResolver.resolveHistoricalPrice(
                    Asset.BTC,
                    eventTime,
                    repository,
                    krakenService,
                ) shouldBe null
            }
        }

        "the OHLC ladder falls back to a coarser interval that reaches the valuation instant" {
            runTest {
                val queriedIntervals = mutableListOf<Int>()
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns emptyList()
                coEvery { krakenService.getOHLC(any(), any(), any()) } answers {
                    queriedIntervals += secondArg<Int>()
                    if (secondArg<Int>() == HistoricalPriceResolver.HISTORICAL_OHLC_INTERVAL_CANDIDATES.last()) {
                        listOf(eventTime.minusSeconds(90_000).epochSecond to BigDecimal("89332.40"))
                    } else {
                        emptyList()
                    }
                }

                HistoricalPriceResolver.resolveHistoricalPrice(
                    Asset.BTC,
                    eventTime,
                    repository,
                    krakenService,
                )!! shouldBeEqualComparingTo BigDecimal("89332.40")
                queriedIntervals shouldBe HistoricalPriceResolver.HISTORICAL_OHLC_INTERVAL_CANDIDATES
            }
        }

        "the finest completed interval wins even when coarser tiers could price the instant" {
            runTest {
                val queriedIntervals = mutableListOf<Int>()
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns emptyList()
                coEvery { krakenService.getOHLC(any(), any(), any()) } answers {
                    queriedIntervals += secondArg<Int>()
                    listOf(eventTime.minusSeconds(901).epochSecond to BigDecimal("98.00"))
                }

                HistoricalPriceResolver.resolveHistoricalPrice(
                    Asset.BTC,
                    eventTime,
                    repository,
                    krakenService,
                )!! shouldBeEqualComparingTo BigDecimal("98.00")
                queriedIntervals shouldBe listOf(HistoricalPriceResolver.HISTORICAL_OHLC_INTERVAL_MINUTES)
            }
        }

        "trade evidence just after the valuation instant stays inside the bounded window" {
            runTest {
                val after = trade(price = BigDecimal("101.00"), volume = BigDecimal("0.01"), usd = BigDecimal("1.01"))
                    .copy(timestamp = eventTime.plusSeconds(30))
                coEvery { repository.getTradesInRange(any(), any()) } returns listOf(after)

                HistoricalPriceResolver.resolveHistoricalPrice(
                    Asset.BTC,
                    eventTime,
                    repository,
                    krakenService,
                )!! shouldBeEqualComparingTo BigDecimal("101.00")

                val tooLate = after.copy(timestamp = eventTime.plusSeconds(181))
                coEvery { repository.getTradesInRange(any(), any()) } returns listOf(tooLate)
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns emptyList()
                coEvery { krakenService.getOHLC(any(), any(), any()) } returns emptyList()
                HistoricalPriceResolver.resolveHistoricalPrice(
                    Asset.BTC,
                    eventTime,
                    repository,
                    krakenService,
                ) shouldBe null
            }
        }

        "a retained past execution wins over a later future execution" {
            runTest {
                val earlier = trade(price = BigDecimal("99.00"), volume = BigDecimal("0.01"), usd = BigDecimal("0.99"))
                    .copy(timestamp = eventTime.minusSeconds(1_000))
                val later = trade(price = BigDecimal("101.00"), volume = BigDecimal("0.01"), usd = BigDecimal("1.01"))
                    .copy(timestamp = eventTime.plusSeconds(30))
                coEvery { repository.getTradesInRange(any(), any()) } returns listOf(earlier, later)

                HistoricalPriceResolver.resolveHistoricalPrice(
                    Asset.BTC,
                    eventTime,
                    repository,
                    krakenService,
                    tradeLookbackSeconds = 2_000L,
                )!! shouldBeEqualComparingTo BigDecimal("99.00")
            }
        }

        "bounded future execution skew is allowed only when no past execution exists" {
            runTest {
                val nearFuture = trade(
                    price = BigDecimal("101.00"),
                    volume = BigDecimal("0.01"),
                    usd = BigDecimal("1.01"),
                )
                    .copy(timestamp = eventTime.plusSeconds(30))
                coEvery { repository.getTradesInRange(any(), any()) } returns listOf(nearFuture)

                HistoricalPriceResolver.resolveHistoricalPrice(
                    Asset.BTC,
                    eventTime,
                    repository,
                    krakenService,
                )!! shouldBeEqualComparingTo BigDecimal("101.00")

                val tooLate = nearFuture.copy(timestamp = eventTime.plusSeconds(181))
                coEvery { repository.getTradesInRange(any(), any()) } returns listOf(tooLate)
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns emptyList()
                coEvery { krakenService.getOHLC(any(), any(), any()) } returns emptyList()
                HistoricalPriceResolver.resolveHistoricalPrice(
                    Asset.BTC,
                    eventTime,
                    repository,
                    krakenService,
                ) shouldBe null
            }
        }

        "retained market pairs are consulted when the default pair has no history" {
            runTest {
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns emptyList()
                val queriedPairs = mutableListOf<String>()
                coEvery { krakenService.getOHLC(any(), any(), any()) } answers {
                    queriedPairs += firstArg<String>()
                    if (firstArg<String>() == "STRCZUSD") {
                        listOf(eventTime.minusSeconds(90_000).epochSecond to BigDecimal("100.50"))
                    } else {
                        emptyList()
                    }
                }

                HistoricalPriceResolver.resolveHistoricalPrice(
                    "STRC",
                    eventTime,
                    repository,
                    krakenService,
                    marketPairs = listOf("STRCZUSD"),
                )!! shouldBeEqualComparingTo BigDecimal("100.50")
                queriedPairs.first() shouldBe "STRCUSD"
                queriedPairs.contains("STRCZUSD") shouldBe true
            }
        }

        "a non-USD quoted candle is converted through the quote asset's historical USD rate" {
            runTest {
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns emptyList()
                coEvery { krakenService.getOHLC(any(), any(), any()) } answers {
                    when (firstArg<String>()) {
                        "XLMUSDT" -> listOf(eventTime.minusSeconds(900).epochSecond to BigDecimal("0.25"))
                        "USDTZUSD" -> listOf(eventTime.minusSeconds(900).epochSecond to BigDecimal("0.9992"))
                        else -> emptyList()
                    }
                }

                HistoricalPriceResolver.resolveHistoricalPrice(
                    "XLM",
                    eventTime,
                    repository,
                    krakenService,
                    marketPairs = listOf("XLMUSDT"),
                    marketPairsByBase = mapOf("USDT" to listOf("USDTZUSD")),
                )!! shouldBeEqualComparingTo BigDecimal("0.24980000")
            }
        }

        "a non-USD quote without its own historical USD rate stays unresolved" {
            runTest {
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns emptyList()
                val queriedPairs = mutableListOf<String>()
                coEvery { krakenService.getOHLC(any(), any(), any()) } answers {
                    queriedPairs += firstArg<String>()
                    if (firstArg<String>() == "XLMUSDT") {
                        listOf(eventTime.minusSeconds(900).epochSecond to BigDecimal("0.25"))
                    } else {
                        emptyList()
                    }
                }

                HistoricalPriceResolver.resolveHistoricalPrice(
                    "XLM",
                    eventTime,
                    repository,
                    krakenService,
                    marketPairs = listOf("XLMUSDT"),
                ) shouldBe null
                queriedPairs.contains("USDTUSD") shouldBe true
            }
        }

        "unparseable retained markets and nested quote conversion stay fail closed" {
            runTest {
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns emptyList()
                coEvery { krakenService.getOHLC(any(), any(), any()) } answers {
                    if (firstArg<String>() == "NOT_A_MARKET" || firstArg<String>() == "XLMUSDT") {
                        listOf(eventTime.minusSeconds(900).epochSecond to BigDecimal("0.25"))
                    } else {
                        emptyList()
                    }
                }

                HistoricalPriceResolver.resolveHistoricalPrice(
                    "XLM",
                    eventTime,
                    repository,
                    krakenService,
                    marketPairs = listOf("NOT_A_MARKET"),
                ) shouldBe null

                HistoricalPriceResolver.resolveHistoricalPrice(
                    "XLM",
                    eventTime,
                    repository,
                    krakenService,
                    marketPairs = listOf("XLMUSDT"),
                    quoteConversionDepth = 1,
                ) shouldBe null
            }
        }

        "a widened trade window admits a contribution-time fill outside the default window" {
            runTest {
                val farTrade = trade(
                    price = BigDecimal("0.239635"),
                    volume = BigDecimal("1.0"),
                    usd = BigDecimal("0.239635"),
                )
                    .copy(symbol = "XLM", pair = "XLMUSD", timestamp = eventTime.minusSeconds(4858))
                coEvery { repository.getTradesInRange(any(), any()) } returns listOf(farTrade)
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns emptyList()
                coEvery { krakenService.getOHLC(any(), any(), any()) } returns emptyList()

                HistoricalPriceResolver.resolveHistoricalPrice(
                    "XLM",
                    eventTime,
                    repository,
                    krakenService,
                ) shouldBe null

                HistoricalPriceResolver.resolveHistoricalPrice(
                    "XLM",
                    eventTime,
                    repository,
                    krakenService,
                    tradeLookbackSeconds = 21_600L,
                )!! shouldBeEqualComparingTo BigDecimal("0.239635")
            }
        }

        "blank market pair entries are ignored while a usable retained pair still prices" {
            runTest {
                val usdtTrade = trade(
                    price = BigDecimal("0.9992"),
                    volume = BigDecimal("1.0"),
                    usd = BigDecimal("0.9992"),
                )
                    .copy(pair = "USDTUSD", symbol = "USDT")
                coEvery { repository.getTradesInRange(any(), any()) } returns listOf(usdtTrade)
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns emptyList()
                coEvery { krakenService.getOHLC(any(), any(), any()) } answers {
                    if (firstArg<String>() == "XLMUSDT") {
                        listOf(eventTime.minusSeconds(900).epochSecond to BigDecimal("0.25"))
                    } else {
                        emptyList()
                    }
                }

                HistoricalPriceResolver.resolveHistoricalPrice(
                    "XLM",
                    eventTime,
                    repository,
                    krakenService,
                    marketPairs = listOf("   ", "XLMUSDT"),
                )!! shouldBeEqualComparingTo BigDecimal("0.24980000")
            }
        }

        "candles opened before the lookback window or closing in the future are ignored" {
            runTest {
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns emptyList()
                coEvery { krakenService.getOHLC(any(), any(), any()) } returns listOf(
                    eventTime.minusSeconds(100_000).epochSecond to BigDecimal("1.00"),
                    eventTime.epochSecond to BigDecimal("2.00"),
                    eventTime.minusSeconds(900).epochSecond to BigDecimal("98.00"),
                )

                HistoricalPriceResolver.resolveHistoricalPrice(
                    Asset.BTC,
                    eventTime,
                    repository,
                    krakenService,
                )!! shouldBeEqualComparingTo BigDecimal("98.00")
            }
        }

        "a snapshot observation time at or before the instant still supplies its price" {
            runTest {
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(
                    snapshot(eventTime.minusSeconds(30))
                        .copy(balancesObservedAt = eventTime.minusSeconds(10)),
                )
                coEvery { krakenService.getOHLC(any(), any(), any()) } returns emptyList()

                HistoricalPriceResolver.resolveHistoricalPrice(
                    Asset.BTC,
                    eventTime,
                    repository,
                    krakenService,
                )!! shouldBeEqualComparingTo BigDecimal("99.00")
            }
        }
    }

    private fun trade(price: BigDecimal, volume: BigDecimal, usd: BigDecimal): TradeRecord = TestFixtures.tradeRecord(
        timestamp = eventTime.minusSeconds(30),
        pair = Asset.BTC_USD_PAIR,
        side = "buy",
        symbol = Asset.BTC,
        volume = volume,
        usdAmount = usd,
        price = price,
        source = TradeSource.API_FILL,
    )

    private fun snapshot(timestamp: Instant): PortfolioSnapshot = PortfolioSnapshot(
        timestamp = timestamp,
        totalValueUSD = BigDecimal("99.00"),
        assets = mapOf(
            Asset.BTC to TestFixtures.assetSnapshot(
                symbol = Asset.BTC,
                balance = BigDecimal("0.01"),
                price = BigDecimal("99.00"),
                valueUSD = BigDecimal("0.99"),
                targetPercent = BigDecimal("100.00"),
            ),
        ),
        actions = emptyList(),
        drawdownPercent = BigDecimal.ZERO,
        fiatDeploymentPercent = BigDecimal.ZERO,
        effectiveUsdTargetPercent = BigDecimal.ZERO,
    )
}
