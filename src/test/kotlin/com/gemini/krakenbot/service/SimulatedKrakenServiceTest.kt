package com.gemini.krakenbot.service

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.service.impl.SimulatedKrakenService
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.math.BigDecimal
import java.math.RoundingMode

class SimulatedKrakenServiceTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    private val btcUsdConfig =
        TestFixtures.DEFAULT_TEST_CONFIG.copy(
            allocations =
            listOf(
                Allocation(Asset.BTC, 50.0),
                Allocation(Asset.USD, 50.0),
            ),
        )

    init {
        "should initialize prices and drifted balances based on config allocations" {
            val configService = mockk<ConfigService>()
            every { configService.getConfig() } returns TestFixtures.DEFAULT_TEST_CONFIG

            val simulatedService = SimulatedKrakenService(configService)

            val prices = simulatedService.getTickerPrices("XXBTZUSD,XETHZUSD")
            prices[TestFixtures.XXBTZUSD] shouldNotBe null
            prices[TestFixtures.XETHZUSD] shouldNotBe null
            (prices[TestFixtures.XXBTZUSD]!! > BigDecimal.ZERO) shouldBe true

            val balances = simulatedService.getBalances()
            balances[Asset.BTC] shouldNotBe null
            balances[Asset.ETH] shouldNotBe null
            balances[Asset.USD] shouldNotBe null

            val totalValue =
                balances[Asset.USD]!!
                    .add(balances[Asset.BTC]!!.multiply(prices[TestFixtures.XXBTZUSD]!!))
                    .add(balances[Asset.ETH]!!.multiply(prices[TestFixtures.XETHZUSD]!!))

            (totalValue > BigDecimal("70000")) shouldBe true
            (totalValue < BigDecimal("130000")) shouldBe true
        }

        "should execute buy orders and update balances" {
            val configService = mockk<ConfigService>()
            every { configService.getConfig() } returns btcUsdConfig

            val simulatedService = SimulatedKrakenService(configService)

            val initialBalances = simulatedService.getBalances()
            val initialBtc = initialBalances[Asset.BTC] ?: BigDecimal.ZERO
            val initialUsd = initialBalances[Asset.USD] ?: BigDecimal.ZERO

            val prices = simulatedService.getTickerPrices(TestFixtures.BTCUSD)
            val btcPrice = prices[TestFixtures.BTCUSD]!!

            val buyVolume = BigDecimal("0.5")
            val result =
                simulatedService.executeOrder(
                    TestFixtures.BTCUSD,
                    TestFixtures.MARKET,
                    TestFixtures.BUY,
                    buyVolume,
                )

            result.success shouldBe true

            val newBalances = simulatedService.getBalances()
            newBalances[Asset.BTC]!!.shouldBeEqualComparingTo(initialBtc.add(buyVolume))
            newBalances[Asset.USD]!!.shouldBeEqualComparingTo(
                initialUsd.subtract(buyVolume.multiply(btcPrice).setScale(2, RoundingMode.HALF_UP)),
            )
        }

        "should execute sell orders and update balances" {
            val configService = mockk<ConfigService>()
            every { configService.getConfig() } returns btcUsdConfig

            val simulatedService = SimulatedKrakenService(configService)

            val initialBalances = simulatedService.getBalances()
            val initialBtc = initialBalances[Asset.BTC] ?: BigDecimal.ZERO
            val initialUsd = initialBalances[Asset.USD] ?: BigDecimal.ZERO

            val prices = simulatedService.getTickerPrices(TestFixtures.BTCUSD)
            val btcPrice = prices[TestFixtures.BTCUSD]!!

            val sellVolume = BigDecimal("0.2")
            val result =
                simulatedService.executeOrder(
                    TestFixtures.BTCUSD,
                    TestFixtures.MARKET,
                    TestFixtures.SELL,
                    sellVolume,
                )

            result.success shouldBe true

            val newBalances = simulatedService.getBalances()
            newBalances[Asset.BTC]!!.shouldBeEqualComparingTo(initialBtc.subtract(sellVolume))
            newBalances[Asset.USD]!!.shouldBeEqualComparingTo(
                initialUsd.add(sellVolume.multiply(btcPrice).setScale(2, RoundingMode.HALF_UP)),
            )
        }

        "should resolve lower-case allocation aliases to canonical simulator balances and prices" {
            val configService = mockk<ConfigService>()
            every { configService.getConfig() } returns
                btcUsdConfig.copy(
                    allocations = listOf(
                        Allocation("btc", 50.0),
                        Allocation("usd", 50.0),
                    ),
                )
            val simulatedService = SimulatedKrakenService(configService)

            val price = simulatedService.getTickerPrices(TestFixtures.XBTUSD).getValue(TestFixtures.XBTUSD)
            (price > BigDecimal("100.00")) shouldBe true
            val initialBtc = simulatedService.getBalances().getValue(Asset.BTC)

            val result = simulatedService.executeOrder(
                TestFixtures.XBTUSD,
                TestFixtures.MARKET,
                TestFixtures.SELL,
                BigDecimal("0.0001"),
            )

            result.success shouldBe true
            simulatedService.getBalances().getValue(Asset.BTC).shouldBeEqualComparingTo(
                initialBtc.subtract(BigDecimal("0.0001")),
            )
        }

        "should fail orders if balance is insufficient" {
            val configService = mockk<ConfigService>()
            every { configService.getConfig() } returns btcUsdConfig

            val simulatedService = SimulatedKrakenService(configService)

            val initialBalances = simulatedService.getBalances()
            val initialBtc = initialBalances[Asset.BTC] ?: BigDecimal.ZERO

            val sellVolume = initialBtc.add(BigDecimal.TEN)
            val result =
                simulatedService.executeOrder(
                    TestFixtures.BTCUSD,
                    TestFixtures.MARKET,
                    TestFixtures.SELL,
                    sellVolume,
                )

            result.success shouldBe false
            result.errorMessage shouldNotBe null
        }

        "should serialize concurrent buys so accepted orders cannot overspend USD" {
            val configService = mockk<ConfigService>()
            every { configService.getConfig() } returns btcUsdConfig
            val simulatedService = SimulatedKrakenService(configService)
            val initialUsd = simulatedService.getBalances().getValue(Asset.USD)
            val btcPrice = simulatedService.getTickerPrices(TestFixtures.BTCUSD).getValue(TestFixtures.BTCUSD)
            val buyVolume =
                initialUsd
                    .multiply(BigDecimal("0.60"))
                    .divide(btcPrice, 8, RoundingMode.HALF_UP)
            val acceptedCost = buyVolume.multiply(btcPrice).setScale(2, RoundingMode.HALF_UP)
            val start = CompletableDeferred<Unit>()

            val results = coroutineScope {
                List(64) {
                    async(Dispatchers.Default) {
                        start.await()
                        simulatedService.executeOrder(
                            TestFixtures.BTCUSD,
                            TestFixtures.MARKET,
                            TestFixtures.BUY,
                            buyVolume,
                        )
                    }
                }.also { start.complete(Unit) }.awaitAll()
            }

            results.count { it.success } shouldBe 1
            simulatedService.getBalances().getValue(Asset.USD).shouldBeEqualComparingTo(
                initialUsd.subtract(acceptedCost),
            )
        }

        "should support dryRun mode when executing orders" {
            val configService = mockk<ConfigService>()
            every { configService.getConfig() } returns
                btcUsdConfig.copy(settings = TestFixtures.DEFAULT_TEST_SETTINGS.copy(dryRun = true))

            val simulatedService = SimulatedKrakenService(configService)
            val initialBalances = simulatedService.getBalances()
            val initialBtc = initialBalances[Asset.BTC] ?: BigDecimal.ZERO
            val initialUsd = initialBalances[Asset.USD] ?: BigDecimal.ZERO

            val result =
                simulatedService.executeOrder(
                    TestFixtures.BTCUSD,
                    TestFixtures.MARKET,
                    TestFixtures.BUY,
                    BigDecimal("0.1"),
                )

            result.success shouldBe true
            result.dryRun shouldBe true

            val afterBalances = simulatedService.getBalances()
            afterBalances[Asset.BTC]!!.shouldBeEqualComparingTo(initialBtc)
            afterBalances[Asset.USD]!!.shouldBeEqualComparingTo(initialUsd)
        }

        "should return trade history and support filtering" {
            val configService = mockk<ConfigService>()
            every { configService.getConfig() } returns btcUsdConfig

            val simulatedService = SimulatedKrakenService(configService)

            // First history call seeds 14 simulated trades (7 paired rebalances) into the in-memory ledger.
            val history = simulatedService.getTradeHistory(null, null)
            history.size shouldBe 14

            val halfTime = history[7].timestamp.epochSecond
            val filteredTime = simulatedService.getTradeHistory(halfTime, null)
            filteredTime.all { it.timestamp.epochSecond >= halfTime } shouldBe true

            val paginated = simulatedService.getTradeHistory(null, 5)
            paginated.size shouldBe 9
        }

        "should cap trade history pages at 50 records and advance offsets" {
            val configService = mockk<ConfigService>()
            every { configService.getConfig() } returns btcUsdConfig
            val simulatedService = SimulatedKrakenService(configService)

            repeat(40) {
                simulatedService.executeOrder(
                    TestFixtures.BTCUSD,
                    TestFixtures.MARKET,
                    TestFixtures.BUY,
                    BigDecimal("0.00001"),
                ).success shouldBe true
            }

            val firstPage = simulatedService.getTradeHistory(null, 0)
            val secondPage = simulatedService.getTradeHistory(null, 50)

            firstPage.size shouldBe 50
            secondPage.size shouldBe 4
            // Newest-first (Kraken-like): page 0 ends at or after the start of page 1.
            (firstPage.last().timestamp >= secondPage.first().timestamp) shouldBe true
            (firstPage.first().timestamp >= firstPage.last().timestamp) shouldBe true
        }

        "should initialize assets added after the simulation portfolio already exists" {
            val configService = mockk<ConfigService>()
            var config = btcUsdConfig
            every { configService.getConfig() } answers { config }
            val simulatedService = SimulatedKrakenService(configService)

            val initialBalances = simulatedService.getBalances()
            initialBalances[Asset.ETH] shouldBe null

            config =
                config.copy(
                    allocations =
                    listOf(
                        Allocation(Asset.BTC, 40.0),
                        Allocation(Asset.ETH, 20.0),
                        Allocation(Asset.USD, 40.0),
                    ),
                )

            val updatedBalances = simulatedService.getBalances()
            updatedBalances[Asset.ETH] shouldNotBe null
            (updatedBalances.getValue(Asset.ETH) > BigDecimal.ZERO) shouldBe true
            simulatedService.getTickerPrices(TestFixtures.ETHUSD)[TestFixtures.ETHUSD] shouldNotBe null
        }

        "should seed nothing if there are no non-usd allocations" {
            val configService = mockk<ConfigService>()
            every { configService.getConfig() } returns
                TestFixtures.DEFAULT_TEST_CONFIG.copy(
                    allocations = listOf(Allocation(Asset.USD, 100.0)),
                )

            val simulatedService = SimulatedKrakenService(configService)
            val history = simulatedService.getTradeHistory(null, null)
            history.size shouldBe 0
        }

        "should handle unknown symbols and missing balances/prices in edge cases" {
            val configService = mockk<ConfigService>()
            every { configService.getConfig() } returns
                TestFixtures.DEFAULT_TEST_CONFIG.copy(
                    // "UNKNOWN" exercises initialPrices and simulatedPrices fallback paths
                    allocations =
                    listOf(
                        Allocation("UNKNOWN", 50.0),
                        Allocation(Asset.USD, 50.0),
                    ),
                )

            val simulatedService = SimulatedKrakenService(configService)

            val balances = simulatedService.getBalances()
            balances["UNKNOWN"] shouldNotBe null

            val prices = simulatedService.getTickerPrices(TestFixtures.ADAEUR)
            prices[TestFixtures.ADAEUR]!!.shouldBeEqualComparingTo(BigDecimal.TEN)

            val buyResult =
                simulatedService.executeOrder(
                    TestFixtures.ADAEUR,
                    TestFixtures.MARKET,
                    TestFixtures.BUY,
                    BigDecimal("0.1"),
                )
            buyResult.success shouldBe true

            val sellResult =
                simulatedService.executeOrder(
                    TestFixtures.ADAEUR,
                    TestFixtures.MARKET,
                    TestFixtures.SELL,
                    BigDecimal("10.0"),
                )
            sellResult.success shouldBe false
            sellResult.errorMessage?.contains("Insufficient ADAEUR funds") shouldBe true

            val buyTooMuchResult =
                simulatedService.executeOrder(
                    TestFixtures.ADAEUR,
                    TestFixtures.MARKET,
                    TestFixtures.BUY,
                    BigDecimal("100000.0"),
                )
            buyTooMuchResult.success shouldBe false
            buyTooMuchResult.errorMessage?.contains("Insufficient USD funds") shouldBe true

            val invalidResult =
                simulatedService.executeOrder(
                    TestFixtures.ADAEUR,
                    TestFixtures.MARKET,
                    "hold",
                    BigDecimal.ONE,
                )
            invalidResult.success shouldBe false
            invalidResult.errorMessage?.contains("Unsupported order side") shouldBe true

            // 15 seeded + 1 successful buy above; the rejected "hold" adds nothing,
            // and an offset past the end returns an empty page (not the whole history).
            val emptyHistory = simulatedService.getTradeHistory(null, 100)
            emptyHistory.size shouldBe 0
        }

        "should reject unsupported order types" {
            val configService = mockk<ConfigService>()
            every { configService.getConfig() } returns btcUsdConfig

            val simulatedService = SimulatedKrakenService(configService)
            val result =
                simulatedService.executeOrder(
                    TestFixtures.BTCUSD,
                    "limit",
                    TestFixtures.BUY,
                    BigDecimal.ONE,
                )

            result.success shouldBe false
            result.errorMessage?.contains("Unsupported order type") shouldBe true
        }

        "getOHLC should return empty list" {
            val configService = mockk<ConfigService>(relaxed = true)
            val simulatedService = SimulatedKrakenService(configService)
            val ohlc = simulatedService.getOHLC(TestFixtures.BTCUSD, 1440, null)
            ohlc.isEmpty() shouldBe true
        }
    }
}
