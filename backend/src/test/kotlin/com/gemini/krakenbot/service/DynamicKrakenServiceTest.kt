package com.gemini.krakenbot.service

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.OrderSide
import com.gemini.krakenbot.model.OrderType
import com.gemini.krakenbot.service.impl.DynamicKrakenService
import com.gemini.krakenbot.service.impl.KrakenServiceImpl
import com.gemini.krakenbot.service.impl.SimulatedKrakenService
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal

class DynamicKrakenServiceTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    private val realService = mockk<KrakenServiceImpl>(relaxed = true)
    private val simulatedService = mockk<SimulatedKrakenService>(relaxed = true)
    private val configService = mockk<ConfigService>(relaxed = true)

    private fun createService(): DynamicKrakenService =
        DynamicKrakenService(realService, simulatedService, configService)

    private fun settings(simulation: Boolean, dryRun: Boolean = false) = TestFixtures.settings(
        dryRun = dryRun,
        simulation = simulation,
        loopDelaySeconds = 60,
        deviationTriggerPercent = 5.0,
        minimumOrderSizeUSD = 5.0,
        fiatMaxDrawdown = 30.0,
    )

    private fun appConfig(simulation: Boolean, dryRun: Boolean = false) = AppConfig(
        kraken = KrakenCredentials("test-api-key", "test-private-key"),
        settings = settings(simulation, dryRun),
        allocations = emptyList(),
    )

    init {
        "uses spendable balances when available and falls back to ordinary balances" {
            every { configService.getConfig() } returns appConfig(simulation = false)
            coEvery { realService.getSpendableBalances() } returns mapOf(Asset.USD to BigDecimal("90.00"))
            coEvery { realService.getBalances() } returns mapOf(Asset.USD to BigDecimal("100.00"))
            val dynamicService = createService()

            dynamicService.getSpendableBalances() shouldBe mapOf(Asset.USD to BigDecimal("90.00"))
            coVerify(exactly = 1) { realService.getSpendableBalances() }
            coVerify(exactly = 0) { realService.getBalances() }

            every { configService.getConfig() } returns appConfig(simulation = true)
            coEvery { simulatedService.getBalances() } returns mapOf(Asset.USD to BigDecimal("100.00"))
            dynamicService.getSpendableBalances() shouldBe mapOf(Asset.USD to BigDecimal("100.00"))
            coVerify(exactly = 1) { simulatedService.getBalances() }
        }

        "delegates to simulated service when simulation is true" {
            every { configService.getConfig() } returns appConfig(simulation = true)

            val dynamicService = createService()

            dynamicService.getBalances()
            coVerify(exactly = 1) { simulatedService.getBalances() }
            coVerify(exactly = 0) { realService.getBalances() }

            dynamicService.getTickerPrices(TestFixtures.BTCUSD)
            coVerify(exactly = 1) { simulatedService.getTickerPrices(TestFixtures.BTCUSD) }
            coVerify(exactly = 0) { realService.getTickerPrices(any()) }

            dynamicService.executeOrder(
                pair = Asset.BTC_USD_PAIR,
                type = OrderType.MARKET.apiValue,
                side = OrderSide.SELL.apiValue,
                volume = BigDecimal.ONE,
                dryRun = false,
            )
            coVerify(exactly = 1) {
                simulatedService.executeOrder(
                    Asset.BTC_USD_PAIR,
                    OrderType.MARKET.apiValue,
                    OrderSide.SELL.apiValue,
                    BigDecimal.ONE,
                    false,
                )
            }
            coVerify(exactly = 0) {
                realService.executeOrder(any(), any(), any(), any(), any(), any())
            }

            dynamicService.getTradeHistory(12345L, 10)
            coVerify(exactly = 1) { simulatedService.getTradeHistory(12345L, 10) }
            coVerify(exactly = 0) { realService.getTradeHistory(any(), any()) }

            dynamicService.getOHLC(TestFixtures.BTCUSD, 1440, null)
            coVerify(exactly = 1) { simulatedService.getOHLC(TestFixtures.BTCUSD, 1440, null) }
            coVerify(exactly = 0) { realService.getOHLC(any(), any(), any()) }
        }

        "forwards bounded trade history to the selected backend" {
            every { configService.getConfig() } returns appConfig(simulation = true)
            val dynamicService = createService()

            dynamicService.getTradeHistoryUntil(12345L, 10, 12399L)

            coVerify(exactly = 1) { simulatedService.getTradeHistoryUntil(12345L, 10, 12399L) }
            coVerify(exactly = 0) { realService.getTradeHistoryUntil(any(), any(), any()) }

            every { configService.getConfig() } returns appConfig(simulation = false)

            dynamicService.getTradeHistoryUntil(12345L, 10, 12399L)

            coVerify(exactly = 1) { realService.getTradeHistoryUntil(12345L, 10, 12399L) }
            coVerify(exactly = 1) { simulatedService.getTradeHistoryUntil(12345L, 10, 12399L) }
        }

        "forwards clOrdId to the real backend when simulation is false" {
            every { configService.getConfig() } returns appConfig(simulation = false)
            val dynamicService = createService()
            val clOrdId = "6d1b345e-2821-40e2-ad83-4ecb18a06876"

            dynamicService.executeOrder(
                pair = Asset.BTC_USD_PAIR,
                type = OrderType.MARKET.apiValue,
                side = OrderSide.BUY.apiValue,
                volume = BigDecimal.ONE,
                dryRun = false,
                clOrdId = clOrdId,
            )

            coVerify(exactly = 1) {
                realService.executeOrder(
                    Asset.BTC_USD_PAIR,
                    OrderType.MARKET.apiValue,
                    OrderSide.BUY.apiValue,
                    BigDecimal.ONE,
                    false,
                    clOrdId,
                )
            }
            coVerify(exactly = 0) {
                simulatedService.executeOrder(any(), any(), any(), any(), any(), any())
            }
        }

        "forwards clOrdId to the simulated backend when simulation is true" {
            every { configService.getConfig() } returns appConfig(simulation = true)
            val dynamicService = createService()
            val clOrdId = "da8e4ad5-9b78-481c-93e5-89746b0cf91f"

            dynamicService.executeOrder(
                pair = Asset.BTC_USD_PAIR,
                type = OrderType.MARKET.apiValue,
                side = OrderSide.SELL.apiValue,
                volume = BigDecimal.ONE,
                dryRun = false,
                clOrdId = clOrdId,
            )

            coVerify(exactly = 1) {
                simulatedService.executeOrder(
                    Asset.BTC_USD_PAIR,
                    OrderType.MARKET.apiValue,
                    OrderSide.SELL.apiValue,
                    BigDecimal.ONE,
                    false,
                    clOrdId,
                )
            }
            coVerify(exactly = 0) {
                realService.executeOrder(any(), any(), any(), any(), any(), any())
            }
        }

        "delegates to real service when simulation is false" {
            every { configService.getConfig() } returns appConfig(simulation = false)

            val dynamicService = createService()

            dynamicService.getBalances()
            coVerify(exactly = 1) { realService.getBalances() }
            coVerify(exactly = 0) { simulatedService.getBalances() }

            dynamicService.getTickerPrices(TestFixtures.BTCUSD)
            coVerify(exactly = 1) { realService.getTickerPrices(TestFixtures.BTCUSD) }
            coVerify(exactly = 0) { simulatedService.getTickerPrices(any()) }

            dynamicService.executeOrder(
                pair = Asset.BTC_USD_PAIR,
                type = OrderType.MARKET.apiValue,
                side = OrderSide.BUY.apiValue,
                volume = BigDecimal.ONE,
                dryRun = false,
            )
            coVerify(exactly = 1) {
                realService.executeOrder(
                    Asset.BTC_USD_PAIR,
                    OrderType.MARKET.apiValue,
                    OrderSide.BUY.apiValue,
                    BigDecimal.ONE,
                    false,
                )
            }
            coVerify(exactly = 0) {
                simulatedService.executeOrder(any(), any(), any(), any(), any(), any())
            }

            dynamicService.getTradeHistory(null, null)
            coVerify(exactly = 1) { realService.getTradeHistory(null, null) }
            coVerify(exactly = 0) { simulatedService.getTradeHistory(any(), any()) }

            dynamicService.getOHLC(TestFixtures.BTCUSD, 60, 1L)
            coVerify(exactly = 1) { realService.getOHLC(TestFixtures.BTCUSD, 60, 1L) }
            coVerify(exactly = 0) { simulatedService.getOHLC(any(), any(), any()) }
        }

        "withStableBackend keeps sell and buy on the backend pinned at entry despite mid-call flip" {
            every { configService.getConfig() } returns appConfig(simulation = true)

            val dynamicService = createService()
            dynamicService.withStableBackend { backend ->
                backend.executeOrder(
                    pair = Asset.BTC_USD_PAIR,
                    type = OrderType.MARKET.apiValue,
                    side = OrderSide.SELL.apiValue,
                    volume = BigDecimal.ONE,
                    dryRun = false,
                )

                every { configService.getConfig() } returns appConfig(simulation = false)

                backend.executeOrder(
                    pair = Asset.ETH_USD_PAIR,
                    type = OrderType.MARKET.apiValue,
                    side = OrderSide.BUY.apiValue,
                    volume = BigDecimal.ONE,
                    dryRun = false,
                )
            }

            coVerify(exactly = 2) {
                simulatedService.executeOrder(any(), any(), any(), any(), any(), any())
            }
            coVerify(exactly = 0) {
                realService.executeOrder(any(), any(), any(), any(), any(), any())
            }
        }

        "delegates to simulated service when simulation and dryRun are both true" {
            every { configService.getConfig() } returns appConfig(simulation = true, dryRun = true)

            val dynamicService = createService()

            dynamicService.getBalances()
            coVerify(exactly = 1) { simulatedService.getBalances() }
            coVerify(exactly = 0) { realService.getBalances() }

            dynamicService.executeOrder(
                pair = Asset.BTC_USD_PAIR,
                type = OrderType.MARKET.apiValue,
                side = OrderSide.BUY.apiValue,
                volume = BigDecimal.ONE,
                dryRun = true,
            )
            coVerify(exactly = 1) {
                simulatedService.executeOrder(
                    Asset.BTC_USD_PAIR,
                    OrderType.MARKET.apiValue,
                    OrderSide.BUY.apiValue,
                    BigDecimal.ONE,
                    true,
                )
            }
            coVerify(exactly = 0) {
                realService.executeOrder(any(), any(), any(), any(), any(), any())
            }
        }

        "delegates to live service when simulation is false even if dryRun is true" {
            every { configService.getConfig() } returns appConfig(simulation = false, dryRun = true)

            val dynamicService = createService()

            dynamicService.getBalances()
            coVerify(exactly = 1) { realService.getBalances() }
            coVerify(exactly = 0) { simulatedService.getBalances() }

            dynamicService.executeOrder(
                pair = Asset.BTC_USD_PAIR,
                type = OrderType.MARKET.apiValue,
                side = OrderSide.BUY.apiValue,
                volume = BigDecimal.ONE,
                dryRun = true,
            )
            coVerify(exactly = 1) {
                realService.executeOrder(
                    Asset.BTC_USD_PAIR,
                    OrderType.MARKET.apiValue,
                    OrderSide.BUY.apiValue,
                    BigDecimal.ONE,
                    true,
                    null,
                )
            }
            coVerify(exactly = 0) {
                simulatedService.executeOrder(any(), any(), any(), any(), any(), any())
            }
        }

        "nested withStableBackend reuses outer pin instead of re-resolving" {
            every { configService.getConfig() } returns appConfig(simulation = true)

            val dynamicService = createService()
            dynamicService.withStableBackend { outer ->
                outer.executeOrder(
                    pair = Asset.BTC_USD_PAIR,
                    type = OrderType.MARKET.apiValue,
                    side = OrderSide.SELL.apiValue,
                    volume = BigDecimal.ONE,
                    dryRun = false,
                )

                every { configService.getConfig() } returns appConfig(simulation = false)

                dynamicService.withStableBackend { inner ->
                    // Nested wrap must keep the outer pin (sim), not flip to live.
                    inner.executeOrder(
                        pair = Asset.ETH_USD_PAIR,
                        type = OrderType.MARKET.apiValue,
                        side = OrderSide.BUY.apiValue,
                        volume = BigDecimal.ONE,
                        dryRun = false,
                    )
                }

                // Outer withStableBackend pin stays on simulated even after config flips to live.
                outer.getBalances()
            }

            coVerify(exactly = 2) {
                simulatedService.executeOrder(any(), any(), any(), any(), any(), any())
            }
            coVerify(exactly = 0) {
                realService.executeOrder(any(), any(), any(), any(), any(), any())
            }
            coVerify(exactly = 1) { simulatedService.getBalances() }
            coVerify(exactly = 0) { realService.getBalances() }

            dynamicService.getBalances()
            coVerify(exactly = 1) { realService.getBalances() }
        }

        "concurrent withStableBackend blocks do not share pin state" {
            runTest {
                every { configService.getConfig() } returns appConfig(simulation = true)
                val dynamicService = createService()

                // Overlapping pins: each async keeps its own backend; a mid-flight config flip
                // must not retarget the other caller's captured service. Ordering is gated
                // deterministically instead of relying on wall-clock delay: `second` only flips
                // config to live after `first` has already read config at entry (pinning
                // simulated), and `first` stays pinned and executes its sell only after `second`
                // has finished. withStableBackend resolves the backend before invoking its block,
                // so completing `firstPinned` inside the block guarantees the sim pin is captured.
                val firstPinned = CompletableDeferred<Unit>()
                val secondFinished = CompletableDeferred<Unit>()
                coroutineScope {
                    val first = async {
                        dynamicService.withStableBackend { backend ->
                            firstPinned.complete(Unit)
                            secondFinished.await()
                            backend.executeOrder(
                                pair = Asset.BTC_USD_PAIR,
                                type = OrderType.MARKET.apiValue,
                                side = OrderSide.SELL.apiValue,
                                volume = BigDecimal.ONE,
                                dryRun = false,
                            )
                        }
                    }
                    val second = async {
                        try {
                            firstPinned.await()
                            every { configService.getConfig() } returns appConfig(simulation = false)
                            dynamicService.withStableBackend { backend ->
                                backend.executeOrder(
                                    pair = Asset.ETH_USD_PAIR,
                                    type = OrderType.MARKET.apiValue,
                                    side = OrderSide.BUY.apiValue,
                                    volume = BigDecimal.ONE,
                                    dryRun = false,
                                )
                            }
                        } finally {
                            // Unblock `first` even when this coroutine fails, so a pinning
                            // regression surfaces as the real error instead of a 60s timeout.
                            secondFinished.complete(Unit)
                        }
                    }
                    first.await()
                    second.await()
                }

                coVerify(exactly = 1) {
                    simulatedService.executeOrder(any(), any(), any(), any(), any(), any())
                }
                coVerify(exactly = 1) {
                    realService.executeOrder(any(), any(), any(), any(), any(), any())
                }
            }
        }

        "withStableBackend pins DynamicKrakenService reads after mid-block simulation flip" {
            every { configService.getConfig() } returns appConfig(simulation = true)
            val dynamicService = createService()

            dynamicService.withStableBackend {
                every { configService.getConfig() } returns appConfig(simulation = false)
                // Call via DynamicKrakenService (not the captured backend) — must stay sim.
                dynamicService.getBalances()
                dynamicService.getTickerPrices(TestFixtures.BTCUSD)
            }

            coVerify(exactly = 1) { simulatedService.getBalances() }
            coVerify(exactly = 0) { realService.getBalances() }
            coVerify(exactly = 1) { simulatedService.getTickerPrices(TestFixtures.BTCUSD) }
            coVerify(exactly = 0) { realService.getTickerPrices(any()) }

            dynamicService.getBalances()
            coVerify(exactly = 1) { realService.getBalances() }
        }

        "delegates ledger queries to the selected backend" {
            every { configService.getConfig() } returns appConfig(simulation = false)
            val dynamicService = createService()

            dynamicService.getLedgers(12345L, 10, 12399L, setOf("staking"))
            coVerify(exactly = 1) { realService.getLedgers(12345L, 10, 12399L, setOf("staking")) }
            coVerify(exactly = 0) { simulatedService.getLedgers(any(), any(), any(), any()) }

            every { configService.getConfig() } returns appConfig(simulation = true)

            dynamicService.getLedgers(12345L, 10, 12399L, setOf("dividend"))
            coVerify(exactly = 1) { simulatedService.getLedgers(12345L, 10, 12399L, setOf("dividend")) }
            coVerify(exactly = 0) { realService.getLedgers(12345L, 10, 12399L, setOf("dividend")) }
        }

        "delegates funding evidence and API counter queries to the selected backend" {
            every { configService.getConfig() } returns appConfig(simulation = false)
            val dynamicService = createService()

            dynamicService.getDepositStatus(10L, 20L)
            dynamicService.getWithdrawStatus(10L, 20L)
            dynamicService.getInternalTransfers(10L, 20L)
            dynamicService.getFundingEvidenceScope()
            dynamicService.getApiCallCounter()

            coVerify(exactly = 1) { realService.getDepositStatus(10L, 20L) }
            coVerify(exactly = 1) { realService.getWithdrawStatus(10L, 20L) }
            coVerify(exactly = 1) { realService.getInternalTransfers(10L, 20L) }
            coVerify(exactly = 1) { realService.getFundingEvidenceScope() }
            coVerify(exactly = 1) { realService.getApiCallCounter() }
            coVerify(exactly = 0) { simulatedService.getDepositStatus(any(), any()) }

            every { configService.getConfig() } returns appConfig(simulation = true)
            dynamicService.getDepositStatus(30L, 40L)
            dynamicService.getWithdrawStatus(30L, 40L)
            dynamicService.getInternalTransfers(30L, 40L)
            dynamicService.getFundingEvidenceScope()
            dynamicService.getApiCallCounter()

            coVerify(exactly = 1) { simulatedService.getDepositStatus(30L, 40L) }
            coVerify(exactly = 1) { simulatedService.getWithdrawStatus(30L, 40L) }
            coVerify(exactly = 1) { simulatedService.getInternalTransfers(30L, 40L) }
            coVerify(exactly = 1) { simulatedService.getFundingEvidenceScope() }
            coVerify(exactly = 1) { simulatedService.getApiCallCounter() }
        }

        "caches the last ledger total count from the selected backend" {
            every { configService.getConfig() } returns appConfig(simulation = false)
            every { realService.getLastLedgerTotalCount() } returns 7
            val dynamicService = createService()

            dynamicService.getLedgers()
            dynamicService.getLastLedgerTotalCount() shouldBe 7
            coVerify(exactly = 1) { realService.getLastLedgerTotalCount() }
        }
    }
}
