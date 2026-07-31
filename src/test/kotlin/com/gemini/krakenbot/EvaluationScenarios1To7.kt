package com.gemini.krakenbot

import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.DatabaseConfig
import com.gemini.krakenbot.config.InvalidConfigurationException
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.OrderResult
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.repository.PortfolioStatsRepository
import com.gemini.krakenbot.repository.impl.SqlitePortfolioStatsRepositoryImpl
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.FakeKrakenService
import com.gemini.krakenbot.service.TradeHistoryService
import com.gemini.krakenbot.service.impl.OrderExecutorImpl
import com.gemini.krakenbot.service.impl.PortfolioAnalyzerImpl
import com.gemini.krakenbot.service.impl.PortfolioManagerImpl
import com.gemini.krakenbot.view.util.FormFields
import com.gemini.krakenbot.view.util.HtmxHeaders
import com.gemini.krakenbot.view.util.Routes
import com.gemini.krakenbot.view.util.ViewText
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.formUrlEncode
import io.ktor.http.parametersOf
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.io.File
import java.io.IOException
import java.math.BigDecimal
import java.time.Instant
import io.ktor.client.plugins.sse.SSE as ClientSSE

internal fun EvaluationScenariosTest.registerScenarios1To7() {
    "Scenario 1: Standard Rebalancing Sequence (Phase 3 Sequencing & Projected Cash)" {
        runTest {
            val fakeKraken = FakeKrakenService()
            val mockConfig = mockk<ConfigService>(relaxed = true)
            val appConfig =
                TestFixtures.config(
                    settings =
                    TestFixtures.settings(dryRun = false, loopDelaySeconds = 60L),
                    allocations =
                    listOf(
                        Allocation(Asset.BTC, 25.0),
                        Allocation(Asset.ETH, 25.0),
                        Allocation(Asset.USD, 50.0),
                    ),
                )
            every { mockConfig.getConfig() } returns appConfig

            // Total portfolio value = $30,000
            // Target USD = 50% ($15,000)
            // Target BTC = 25% ($7,500)
            // Target ETH = 25% ($7,500)
            // Current USD = $10,000
            // Current BTC = 0.3 (Price = $50,000) -> BTC Value = $15,000
            // Current ETH = 2.5 (Price = $2,000) -> ETH Value = $5,000
            fakeKraken.balanceSupplier = {
                mapOf(
                    Asset.BTC to 0.3,
                    Asset.ETH to 2.5,
                    Asset.USD to 10000.0,
                )
            }
            fakeKraken.pricesSupplier = { _ ->
                mapOf(
                    Asset.BTC_USD_PAIR to 50000.0,
                    TestFixtures.ETHUSD to 2000.0,
                )
            }

            val orderExecutionLog = mutableListOf<String>()
            fakeKraken.executeOrderAction = { pair, _, side, volume ->
                orderExecutionLog.add("$side $pair volume=$volume")
            }

            val statsRepo = mockk<PortfolioStatsRepository>(relaxed = true)
            val analyzer =
                PortfolioAnalyzerImpl(
                    fakeKraken,
                    mockConfig,
                    statsRepo,
                )
            val executor =
                OrderExecutorImpl(fakeKraken, tradeHistoryService)
            val pm =
                PortfolioManagerImpl(
                    mockConfig,
                    mockk(relaxed = true),
                    analyzer,
                    executor,
                )

            // Sub-case 1: Success path (Verify Sequencing)
            pm.performRebalanceCycle()

            val firstOrder = orderExecutionLog.firstOrNull()
            val secondOrder = orderExecutionLog.getOrNull(1)

            val seqPass =
                firstOrder != null &&
                    firstOrder.startsWith("sell XBTUSD") &&
                    secondOrder != null &&
                    secondOrder.startsWith("buy ETHUSD")

            // Sub-case 2: Failed Sell prevents cash inflation and caps buy correctly
            // Config: BTC 40% ($8,000), ETH 50% ($10,000), USD 10% ($2,000). Total value = $20,000.
            // Current BTC = 0.38 (Price = $50,000) -> BTC Value = $19,000 (Target = $8,000). Overweight by $11,000.
            // Current ETH = 0 (Target = $10,000). Underweight by $10,000.
            // Current USD = $1,000 (Target = $2,000). Underweight by $1,000.
            // SELL BTC $11,000 (0.22 BTC), BUY ETH $10,000 (5.0 ETH).
            val appConfigSub =
                TestFixtures.config(
                    settings =
                    TestFixtures.settings(dryRun = false, loopDelaySeconds = 60L),
                    allocations =
                    listOf(
                        Allocation(Asset.BTC, 40.0),
                        Allocation(Asset.ETH, 50.0),
                        Allocation(Asset.USD, 10.0),
                    ),
                )
            every { mockConfig.getConfig() } returns appConfigSub

            fakeKraken.balanceSupplier = {
                mapOf(
                    Asset.BTC to 0.38,
                    Asset.ETH to 0.0,
                    Asset.USD to 1000.0,
                )
            }

            // If sell BTC fails
            fakeKraken.orderResultFactory = { pair, _, side, volume ->
                if (side == TestFixtures.SELL) {
                    OrderResult(false, pair, side, volume, errorMessage = "Simulated Sell Failure")
                } else {
                    OrderResult(true, pair, side, volume)
                }
            }

            orderExecutionLog.clear()
            pm.performRebalanceCycle()

            // Since BTC sell failed, projectedCash is NOT incremented (remains $1,000).
            // Buying ETH requires $10,000. But since actualCash is $1,000,
            // the buy is capped at 99% of $1,000 = $990.
            // So buy order should be for $990 / $2,000 price = 0.495 ETH.
            val ethBuy = orderExecutionLog.firstOrNull { it.contains("buy ETHUSD") }
            val cappedPass = ethBuy != null && ethBuy.contains("volume=0.495")

            val finalPass = seqPass && cappedPass
            val evidence =
                "Sub-case 1 (Sequencing): Sell first, then Buy is $seqPass (Log: $firstOrder -> $secondOrder)\n" +
                    "Sub-case 2 (Capped buy on failed sell): Buy order capped at 0.495 ETH is $cappedPass (Log: $ethBuy)"

            finalPass.shouldBeTrue()
            EvaluationScenariosTest.recordResult(
                "Scenario 1",
                "Standard Rebalancing Sequence (Phase 3 Sequencing & Projected Cash)",
                TestFixtures.PASS,
                evidence,
            )
        }
    }

    "Scenario 2: Dynamic Drawdown-Based Fiat Deployment" {
        runTest {
            val fakeKraken = FakeKrakenService()
            val mockConfig = mockk<ConfigService>(relaxed = true)
            val testStatsFile = "scenario2-stats.json"
            val f = File(testStatsFile)
            val db = DatabaseConfig.init(TestFixtures.MEMORY_)
            val statsRepo = SqlitePortfolioStatsRepositoryImpl(db, objectMapper, testStatsFile)

            val appConfig =
                TestFixtures.config(
                    settings =
                    TestFixtures.settings(
                        dryRun = false,
                        loopDelaySeconds = 60L,
                        fiatMaxDrawdown = 20.0,
                        fiatDeploymentExponent = 2.0, // Conservative deployment curve
                    ),
                    allocations =
                    listOf(
                        Allocation(Asset.BTC, 40.0),
                        Allocation(Asset.ETH, 40.0),
                        Allocation(Asset.USD, 20.0),
                    ),
                )
            every { mockConfig.getConfig() } returns appConfig

            val analyzer =
                PortfolioAnalyzerImpl(
                    fakeKraken,
                    mockConfig,
                    statsRepo,
                )

            // 1. Initial run: Set ATH to $10,000
            val balances = mapOf(Asset.BTC to 0.1, Asset.ETH to 2.5, Asset.USD to 0.0).toBigDecimalMap()
            val prices = mapOf(Asset.BTC to BigDecimal("50000.0"), Asset.ETH to BigDecimal("2000.0"))
            // Total portfolio value = 0.1*50000 + 2.5*2000 = $10,000
            val valInitial = analyzer.calculatePortfolioValues(balances, prices).getOrNull()!!
            valInitial.totalValueUSD.shouldBeEqualComparingTo(BigDecimal("10000.0"))

            val drawdown1 = analyzer.updateAthAndCalculateDrawdown(valInitial.totalValueUSD)
            drawdown1.shouldBeEqualComparingTo(BigDecimal("0.0"))

            // Stats file should exist and record ATH = $10,000
            val statsLoaded = statsRepo.load()
            statsLoaded.allTimeHigh.shouldBeEqualComparingTo(BigDecimal("10000.0"))

            // 2. Next run: Deep Drawdown to $8,000 (20% drawdown)
            // Drawdown is 20%. fiatMaxDrawdown is 20%.
            // Deployment pct = (20 / 20) ^ 2 * 100 = 100% deployment of the USD target.
            // Effective USD target = 20% * (1 - 1.0) = 0%.
            // Scale factor for crypto = (100 - 0) / 80 = 1.25.
            // Adjusted target: BTC = 50%, ETH = 50%, USD = 0%.
            val drawdown2 = analyzer.updateAthAndCalculateDrawdown(BigDecimal("8000.00"))
            drawdown2.shouldBeEqualComparingTo(BigDecimal("20.0"))

            val deployPct2 = analyzer.calculateFiatDeployment(drawdown2, appConfig.settings)
            deployPct2.shouldBeEqualComparingTo(BigDecimal("100.0"))

            val effectiveUsd2 = analyzer.calculateEffectiveUsdTarget(deployPct2)
            effectiveUsd2.shouldBeEqualComparingTo(BigDecimal("0.0"))

            val scaleFactor2 = analyzer.calculateCryptoScaleFactor(effectiveUsd2)
            scaleFactor2.shouldBeEqualComparingTo(BigDecimal("1.25"))

            // 3. Sub-case 3: Drawdown to $9,000 (10% drawdown)
            // Drawdown is 10%. fiatMaxDrawdown is 20%.
            // Deployment pct = (10 / 20) ^ 2 * 100 = 25% deployment of USD target.
            // Effective USD target = 20% * (1 - 0.25) = 15%.
            // Scale factor for crypto = (100 - 15) / 80 = 85 / 80 = 1.0625.
            // Adjusted target: BTC = 42.5%, ETH = 42.5%, USD = 15%.
            val drawdown3 = analyzer.updateAthAndCalculateDrawdown(BigDecimal("9000.00"))
            drawdown3.shouldBeEqualComparingTo(BigDecimal("10.0"))

            val deployPct3 = analyzer.calculateFiatDeployment(drawdown3, appConfig.settings)
            deployPct3.shouldBeEqualComparingTo(BigDecimal("25.0"))

            val effectiveUsd3 = analyzer.calculateEffectiveUsdTarget(deployPct3)
            effectiveUsd3.shouldBeEqualComparingTo(BigDecimal("15.0"))

            val scaleFactor3 = analyzer.calculateCryptoScaleFactor(effectiveUsd3)
            scaleFactor3.shouldBeEqualComparingTo(BigDecimal("1.0625"))

            val success =
                (
                    deployPct2.toDouble() == 100.0 && effectiveUsd2.toDouble() == 0.0 &&
                        scaleFactor2.toDouble() == 1.25
                    ) &&
                    (
                        deployPct3.toDouble() == 25.0 && effectiveUsd3.toDouble() == 15.0 &&
                            scaleFactor3.toDouble() == 1.0625
                        )

            val evidence =
                "ATH Saved: ${statsLoaded.allTimeHigh}\n" +
                    "Case 20% Drawdown: Deployment Pct = $deployPct2%, Effective USD Target = $effectiveUsd2%, " +
                    "Crypto Scale Factor = $scaleFactor2\n" +
                    "Case 10% Drawdown: Deployment Pct = $deployPct3%, Effective USD Target = $effectiveUsd3%, " +
                    "Crypto Scale Factor = $scaleFactor3"

            success.shouldBeTrue()
            if (f.exists()) {
                f.delete()
            }

            EvaluationScenariosTest.recordResult(
                "Scenario 2",
                "Dynamic Drawdown-Based Fiat Deployment",
                TestFixtures.PASS,
                evidence,
            )
        }
    }

    "Scenario 3: Intelligent Fiat Correction (Deposit/Withdrawal)" {
        runTest {
            val fakeKraken = FakeKrakenService()
            val mockConfig = mockk<ConfigService>(relaxed = true)
            val appConfig =
                TestFixtures.config(
                    settings =
                    TestFixtures.settings(dryRun = false, loopDelaySeconds = 60L, deviationTriggerPercent = 10.0),
                    allocations =
                    listOf(
                        Allocation(Asset.BTC, 45.0),
                        Allocation(Asset.ETH, 45.0),
                        Allocation(Asset.USD, 10.0),
                    ),
                )
            every { mockConfig.getConfig() } returns appConfig

            val statsRepo = mockk<PortfolioStatsRepository>(relaxed = true)
            val analyzer =
                PortfolioAnalyzerImpl(
                    fakeKraken,
                    mockConfig,
                    statsRepo,
                )
            val executor =
                OrderExecutorImpl(fakeKraken, tradeHistoryService)
            val pm =
                PortfolioManagerImpl(
                    mockConfig,
                    mockk(relaxed = true),
                    analyzer,
                    executor,
                )

            // Sub-case A: Deposit
            // Total portfolio = $10,000 (BTC=$4,500, ETH=$4,500, USD=$1,000)
            // Now deposit $1,000 USD -> USD = $2,000, Total = $11,000
            // Targets: BTC=$4,950, ETH=$4,950, USD=$1,100
            // Current: BTC=$4,500 (dev = -9.09% < 10%), ETH=$4,500 (dev = -9.09% < 10%), USD=$2,000 (dev = 81.81% >= 10%)
            // Only USD triggers. USD excess is $900.
            // Distributed proportionally to underweight assets (BTC & ETH, each has $450 deficit, so each gets 50% = $450).
            fakeKraken.balanceSupplier = {
                mapOf(
                    Asset.BTC to 0.09, // 0.09 * 50000 = 4500
                    Asset.ETH to 2.25, // 2.25 * 2000 = 4500
                    Asset.USD to 2000.0,
                )
            }
            fakeKraken.pricesSupplier = { _ ->
                mapOf(
                    TestFixtures.XBTUSD to 50000.0,
                    TestFixtures.ETHUSD to 2000.0,
                )
            }

            fakeKraken.executedOrders.clear()
            pm.performRebalanceCycle()

            val depositPass =
                fakeKraken.executedOrders.size == 2 &&
                    fakeKraken.executedOrders.any {
                        it.pair == TestFixtures.XBTUSD && it.side == TestFixtures.BUY &&
                            it.volume.compareTo(BigDecimal("0.009")) == 0
                    } &&
                    fakeKraken.executedOrders.any {
                        it.pair == TestFixtures.ETHUSD && it.side == TestFixtures.BUY &&
                            it.volume.compareTo(BigDecimal("0.225")) == 0
                    }

            // Sub-case B: Withdrawal
            // Total portfolio = $10,000 (BTC=$4,500, ETH=$4,500, USD=$1,000)
            // Now withdraw $500 USD -> USD = $500, Total = $9,500
            // Targets: BTC=$4,275, ETH=$4,275, USD=$950
            // Current: BTC=$4,500 (dev = +5.26% < 10%), ETH=$4,500 (dev = +5.26% < 10%), USD=$500 (dev = -47.36% >= 10%)
            // Only USD triggers. USD deficit is $450.
            // Distributed proportionally to overweight assets (BTC & ETH, each has $225 surplus, so each gets 50% = $225 sell).
            fakeKraken.balanceSupplier = {
                mapOf(
                    Asset.BTC to 0.09, // 0.09 * 50000 = 4500
                    Asset.ETH to 2.25, // 2.25 * 2000 = 4500
                    Asset.USD to 500.0,
                )
            }

            fakeKraken.executedOrders.clear()
            pm.performRebalanceCycle()

            val withdrawalPass =
                fakeKraken.executedOrders.size == 2 &&
                    fakeKraken.executedOrders.any {
                        it.pair == TestFixtures.XBTUSD && it.side == TestFixtures.SELL &&
                            it.volume.compareTo(BigDecimal("0.0045")) == 0
                    } &&
                    fakeKraken.executedOrders.any {
                        it.pair == TestFixtures.ETHUSD && it.side == TestFixtures.SELL &&
                            it.volume.compareTo(BigDecimal("0.1125")) == 0
                    }

            val finalPass = depositPass && withdrawalPass
            val evidence =
                "Sub-case A (Deposit Fiat Correction): $depositPass " +
                    "(Orders: ${fakeKraken.executedOrders.size} orders generated)\n" +
                    "Sub-case B (Withdrawal Fiat Correction): $withdrawalPass"

            finalPass.shouldBeTrue()
            EvaluationScenariosTest.recordResult(
                "Scenario 3",
                "Intelligent Fiat Correction (Deposit/Withdrawal)",
                TestFixtures.PASS,
                evidence,
            )
        }
    }

    "Scenario 4: Live Dashboard & Config Hot-Reload" {
        testApplication {
            application {
                configureTestEnv()
            }

            // 1. GET Dashboard Shell
            val getShellResponse = client.get(Routes.ROOT)
            getShellResponse.status shouldBe HttpStatusCode.OK
            getShellResponse.headers[HttpHeaders.ContentType] shouldContain TestFixtures.TEXT_HTML
            val bodyShell = getShellResponse.bodyAsText()
            bodyShell shouldContain ViewText.APP_TITLE

            // 2. POST Valid Settings (Hot-Reload)
            val testKey = "api-reloaded"
            val testSecret = "secret-reloaded"
            val validConfig =
                AppConfig(
                    kraken = KrakenCredentials(testKey, testSecret),
                    settings =
                    TestFixtures.settings(
                        loopDelaySeconds = 120L,
                        deviationTriggerPercent = 3.5,
                        dustThresholdUSD = 2.0,
                    ),
                    allocations =
                    listOf(
                        Allocation(Asset.USD, 100.0),
                    ),
                )

            every { configService.getConfig() } returns validConfig
            val settingsResponse = client.get(Routes.SETTINGS)
            val csrfToken =
                Regex("""name="csrfToken" value="([^"]+)"""")
                    .find(settingsResponse.bodyAsText())
                    ?.groupValues
                    ?.get(1)
                    ?: error("Settings page did not contain a CSRF token")
            val csrfCookie = settingsResponse.headers[HttpHeaders.SetCookie]?.substringBefore(';')
                ?: error("Settings page did not issue a CSRF cookie")

            val postResponse =
                client.post(Routes.SETTINGS) {
                    setBody(
                        parametersOf(
                            FormFields.LOOP_DELAY_SECONDS to listOf("120"),
                            FormFields.DEVIATION_TRIGGER_PERCENT to listOf("3.5"),
                            FormFields.DUST_THRESHOLD_USD to listOf("2.0"),
                            FormFields.FIAT_MAX_DRAWDOWN to listOf("0.0"),
                            FormFields.FIAT_DEPLOYMENT_EXPONENT to listOf("1.0"),
                            FormFields.CSRF_TOKEN to listOf(csrfToken),
                            FormFields.SYMBOLS to listOf(Asset.USD),
                            FormFields.TARGETS to listOf("100.0"),
                            FormFields.COLORS to listOf("#94a3b8"),
                        ).formUrlEncode(),
                    )
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                    header(HttpHeaders.Cookie, csrfCookie)
                }
            postResponse.status shouldBe HttpStatusCode.OK
            postResponse.headers[HtmxHeaders.HX_REDIRECT] shouldBe Routes.ROOT
            verify { configService.updateConfig(any()) }

            // 3. POST Invalid Settings (Validation fails)
            every { configService.updateConfig(any()) } throws
                InvalidConfigurationException(
                    "Total allocation percentage must be exactly 100%.",
                )

            val postInvalidResponse =
                client.post(Routes.SETTINGS) {
                    setBody(
                        parametersOf(
                            FormFields.LOOP_DELAY_SECONDS to listOf("60"),
                            FormFields.DEVIATION_TRIGGER_PERCENT to listOf("2.0"),
                            FormFields.DUST_THRESHOLD_USD to listOf("1.0"),
                            FormFields.FIAT_MAX_DRAWDOWN to listOf("0.0"),
                            FormFields.FIAT_DEPLOYMENT_EXPONENT to listOf("1.0"),
                            FormFields.CSRF_TOKEN to listOf(csrfToken),
                            FormFields.SYMBOLS to listOf(Asset.USD),
                            FormFields.TARGETS to listOf("90.0"), // 90% sum != 100%
                            FormFields.COLORS to listOf("#94a3b8"),
                        ).formUrlEncode(),
                    )
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                    header(HttpHeaders.Cookie, csrfCookie)
                }
            postInvalidResponse.status shouldBe HttpStatusCode.OK
            postInvalidResponse.bodyAsText() shouldContain "Total allocation percentage must be exactly 100%."

            // 4. SSE Stream Broadcast
            val snapshot =
                PortfolioSnapshot(
                    timestamp = Instant.now(),
                    totalValueUSD = BigDecimal("5000.0"),
                    assets = emptyMap(),
                    actions = listOf("BROADCAST TEST"),
                    drawdownPercent = BigDecimal.ZERO,
                    fiatDeploymentPercent = BigDecimal.ZERO,
                    effectiveUsdTargetPercent = BigDecimal.ZERO,
                )
            coEvery { tradeHistoryService.getLatestSnapshot() } returns snapshot
            every { tradeHistoryService.getHistoryFlow() } returns flowOf(snapshot)

            val clientSse = createClient { install(ClientSSE) }
            clientSse.sse(Routes.API_STATUS_STREAM) {
                val events = incoming.take(1).toList()
                events[0].data shouldContain "BROADCAST TEST"
            }

            val evidence =
                "GET Dashboard Shell returns 200 OK & ${ViewText.APP_TITLE}\n" +
                    "POST settings updates configuration safely and redirects via HX-Redirect header\n" +
                    "POST invalid settings fails with allocation verification exception\n" +
                    "SSE stream successfully broadcasts snapshot payload updates to HTMX clients"

            EvaluationScenariosTest.recordResult(
                "Scenario 4",
                "Live Dashboard & Config Hot-Reload",
                TestFixtures.PASS,
                evidence,
            )
        }
    }

    "Scenario 5: Safety and Resilience (Dry Run & Cycle Failure Propagation)" {
        runTest {
            val fakeKraken = FakeKrakenService()
            val mockConfig = mockk<ConfigService>(relaxed = true)

            // Sub-case A: Dry Run
            val appConfigDry =
                TestFixtures.config(
                    settings =
                    TestFixtures.settings(loopDelaySeconds = 60L), // DRY RUN IS ACTIVE
                    allocations =
                    listOf(
                        Allocation(Asset.BTC, 50.0),
                        Allocation(Asset.USD, 50.0),
                    ),
                )
            every { mockConfig.getConfig() } returns appConfigDry

            // Balances triggers standard rebalance (BTC overweight by $1,000)
            fakeKraken.balanceSupplier = {
                mapOf(
                    Asset.BTC to 0.12, // Value = $6,000
                    Asset.USD to 4000.0, // Total = $10,000
                )
            }
            fakeKraken.pricesSupplier = { _ -> mapOf(TestFixtures.XBTUSD to 50000.0) }

            fakeKraken.orderResultFactory = { pair, _, side, volume ->
                OrderResult(
                    success = true,
                    pair = pair,
                    side = side,
                    volume = volume,
                    dryRun = true, // Fake returns dryRun = true
                )
            }

            val statsRepo = mockk<PortfolioStatsRepository>(relaxed = true)
            val analyzer =
                PortfolioAnalyzerImpl(
                    fakeKraken,
                    mockConfig,
                    statsRepo,
                )
            val executor =
                OrderExecutorImpl(fakeKraken, tradeHistoryService)

            val capturedActions = mutableListOf<String>()
            val mockHistory = mockk<TradeHistoryService>(relaxed = true)
            coEvery { mockHistory.addSnapshot(any()) } answers {
                capturedActions.addAll(firstArg<PortfolioSnapshot>().actions)
            }

            val pm =
                PortfolioManagerImpl(
                    mockConfig,
                    mockHistory,
                    analyzer,
                    executor,
                )
            pm.performRebalanceCycle()

            val dryRunPass = capturedActions.any { it.startsWith("[DRY RUN]") }

            // Sub-case B: Dust Threshold Filtering
            val appConfigDust =
                TestFixtures.config(
                    settings =
                    TestFixtures.settings(dryRun = false, dustThresholdUSD = 10.0, loopDelaySeconds = 60L),
                    allocations =
                    listOf(
                        Allocation(Asset.BTC, 50.0),
                        Allocation(Asset.USD, 50.0),
                    ),
                )
            every { mockConfig.getConfig() } returns appConfigDust

            // Deviation: BTC overweight by $5.00 (triggers deviation of 10% on BTC value but below dust limit of $10)
            fakeKraken.balanceSupplier = {
                mapOf(
                    Asset.BTC to 0.0011, // Value = $55.00
                    Asset.USD to 45.00, // Total = $100.00. Target is $50.00. Dev = $5.00.
                )
            }
            fakeKraken.orderResultFactory = null
            fakeKraken.executedOrders.clear()

            val pmDust =
                PortfolioManagerImpl(
                    mockConfig,
                    mockk(relaxed = true),
                    analyzer,
                    executor,
                )
            pmDust.performRebalanceCycle()

            val dustPass = fakeKraken.executedOrders.isEmpty()

            // Sub-case C: the cycle propagates network failures to its caller at the loop boundary.
            // This does not exercise or make claims about runLoop's recovery behavior.
            fakeKraken.balanceSupplier = { throw IOException("502 Bad Gateway") }
            val propagatedFailure =
                shouldThrow<IOException> {
                    pmDust.performRebalanceCycle()
                }
            propagatedFailure.message shouldBe "502 Bad Gateway"
            val networkFailurePropagationPass = true

            // Sub-case D: Price Lookup Failure
            // If price lookup fails, performRebalanceCycle returns early without throwing and without orders
            fakeKraken.balanceSupplier = { mapOf(Asset.BTC to 1.0, Asset.USD to 1.0) }
            fakeKraken.pricesSupplier = { emptyMap() } // No prices returned
            fakeKraken.executedOrders.clear()

            pmDust.performRebalanceCycle()
            val priceFailPass = fakeKraken.executedOrders.isEmpty()

            val finalPass = dryRunPass && dustPass && networkFailurePropagationPass && priceFailPass
            val evidence =
                "Sub-case A (Dry Run Mode): $dryRunPass (Actions: $capturedActions)\n" +
                    "Sub-case B (Dust Threshold): $dustPass " +
                    "(Trades executed: ${fakeKraken.executedOrders.size})\n" +
                    "Sub-case C (Network Failure propagated out of cycle to loop boundary): " +
                    "$networkFailurePropagationPass\n" +
                    "Sub-case D (Price Lookup Failure aborts cycle): $priceFailPass"

            finalPass.shouldBeTrue()
            EvaluationScenariosTest.recordResult(
                "Scenario 5",
                "Safety and Resilience (Dry Run & Cycle Failure Propagation)",
                TestFixtures.PASS,
                evidence,
            )
        }
    }

    "Scenario 6: Zero Target Allocation (Total Liquidation)" {
        runTest {
            val fakeKraken = FakeKrakenService()
            val mockConfig = mockk<ConfigService>(relaxed = true)
            val appConfig =
                TestFixtures.config(
                    settings =
                    TestFixtures.settings(dryRun = false, loopDelaySeconds = 60L),
                    allocations =
                    listOf(
                        Allocation(Asset.BTC, 0.0),
                        Allocation(Asset.USD, 100.0),
                    ),
                )
            every { mockConfig.getConfig() } returns appConfig

            fakeKraken.balanceSupplier = {
                mapOf(
                    Asset.BTC to 0.5,
                    Asset.USD to 0.0,
                )
            }
            fakeKraken.pricesSupplier = { _ -> mapOf(TestFixtures.XBTUSD to 50000.0) }

            val statsRepo = mockk<PortfolioStatsRepository>(relaxed = true)
            val analyzer =
                PortfolioAnalyzerImpl(
                    fakeKraken,
                    mockConfig,
                    statsRepo,
                )
            val executor =
                OrderExecutorImpl(fakeKraken, tradeHistoryService)
            val pm =
                PortfolioManagerImpl(
                    mockConfig,
                    mockk(relaxed = true),
                    analyzer,
                    executor,
                )

            fakeKraken.executedOrders.clear()
            pm.performRebalanceCycle()

            val success =
                fakeKraken.executedOrders.size == 1 &&
                    fakeKraken.executedOrders.any {
                        it.pair == TestFixtures.XBTUSD && it.side == TestFixtures.SELL &&
                            it.volume.compareTo(BigDecimal("0.5")) == 0
                    }
            val evidence = "Trades: ${fakeKraken.executedOrders.size} generated. Details: ${fakeKraken.executedOrders}"

            success.shouldBeTrue()
            EvaluationScenariosTest.recordResult(
                "Scenario 6",
                "Zero Target Allocation (Total Liquidation)",
                TestFixtures.PASS,
                evidence,
            )
        }
    }

    "Scenario 7: Kraken Symbol Mapping Quirks (DOGE/BTC)" {
        runTest {
            val fakeKraken = FakeKrakenService()
            val mockConfig = mockk<ConfigService>(relaxed = true)
            val appConfig =
                TestFixtures.config(
                    settings =
                    TestFixtures.settings(dryRun = false, loopDelaySeconds = 60L),
                    allocations =
                    listOf(
                        Allocation(Asset(Asset.DOGE), 30.0),
                        Allocation(Asset(Asset.BTC), 30.0),
                        Allocation(Asset.USD, 40.0),
                    ),
                )
            every { mockConfig.getConfig() } returns appConfig

            // Kraken quirk: a DOGE allocation has to be priced and traded as XDGUSD, so the pair
            // string handed to the ticker call is asserted alongside the resulting orders.
            fakeKraken.balanceSupplier = {
                mapOf(
                    "DOGE" to 0.0,
                    Asset.BTC to 0.0,
                    Asset.USD to 10000.0,
                )
            }
            var queriedPairs: String? = null
            fakeKraken.pricesSupplier = { pairs ->
                queriedPairs = pairs
                mapOf(
                    "XDGUSD" to 0.10,
                    TestFixtures.XBTUSD to 50000.0,
                )
            }

            val statsRepo = mockk<PortfolioStatsRepository>(relaxed = true)
            val analyzer =
                PortfolioAnalyzerImpl(
                    fakeKraken,
                    mockConfig,
                    statsRepo,
                )
            val executor =
                OrderExecutorImpl(fakeKraken, tradeHistoryService)
            val pm =
                PortfolioManagerImpl(
                    mockConfig,
                    mockk(relaxed = true),
                    analyzer,
                    executor,
                )

            fakeKraken.executedOrders.clear()
            pm.performRebalanceCycle()

            val dogeBuy = fakeKraken.executedOrders.firstOrNull { it.pair == "XDGUSD" }
            val btcBuy = fakeKraken.executedOrders.firstOrNull { it.pair == TestFixtures.XBTUSD }

            val dogePass =
                dogeBuy != null && dogeBuy.side == TestFixtures.BUY &&
                    dogeBuy.volume.compareTo(BigDecimal("30000")) == 0
            val btcPass =
                btcBuy != null && btcBuy.side == TestFixtures.BUY &&
                    btcBuy.volume.compareTo(BigDecimal("0.06")) == 0
            val queryPass =
                queriedPairs != null && queriedPairs.contains("XDGUSD") &&
                    queriedPairs.contains(TestFixtures.XBTUSD)

            val success = dogePass && btcPass && queryPass
            val evidence =
                "Queried pairs: $queriedPairs\n" +
                    "DOGE buy order: $dogeBuy\n" +
                    "BTC buy order: $btcBuy"

            success.shouldBeTrue()
            EvaluationScenariosTest.recordResult(
                "Scenario 7",
                "Kraken Symbol Mapping Quirks (DOGE/BTC)",
                TestFixtures.PASS,
                evidence,
            )
        }
    }
}
