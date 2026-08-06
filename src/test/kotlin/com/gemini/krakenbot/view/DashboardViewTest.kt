package com.gemini.krakenbot.view

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.service.impl.PortfolioCalculations
import com.gemini.krakenbot.view.component.AllocationChartComponent
import com.gemini.krakenbot.view.component.DashboardFragmentComponent
import com.gemini.krakenbot.view.component.DashboardShellComponent
import com.gemini.krakenbot.view.component.HistoryPageComponent
import com.gemini.krakenbot.view.component.OverviewGridComponent
import com.gemini.krakenbot.view.component.PerformanceTableComponent
import com.gemini.krakenbot.view.component.RecentActivityComponent
import com.gemini.krakenbot.view.component.SettingsFormComponent
import com.gemini.krakenbot.view.util.Icons
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.html.div
import kotlinx.html.html
import kotlinx.html.stream.createHTML
import java.math.BigDecimal
import java.time.Instant

class DashboardViewTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    private val shell = DashboardShellComponent()
    private val overview = OverviewGridComponent()
    private val chart = AllocationChartComponent()
    private val table = PerformanceTableComponent()
    private val activity = RecentActivityComponent()
    private val fragment = DashboardFragmentComponent(
        overviewGridComponent = overview,
        allocationChartComponent = chart,
        performanceTableComponent = table,
        recentActivityComponent = activity,
    )
    private val view = DashboardView(
        shellComponent = shell,
        settingsFormComponent = SettingsFormComponent(),
        fragmentComponent = fragment,
        historyPageComponent = HistoryPageComponent(jacksonObjectMapper()),
    )

    private val testCsrfToken = "test-csrf-token"

    private val baseConfig = AppConfig(
        KrakenCredentials(
            apiKey = TestFixtures.TEST_API_KEY,
            privateKey = "privateKey",
        ),
        TestFixtures.settings(loopDelaySeconds = 60L, dustThresholdUSD = 5.0, fiatMaxDrawdown = 20.0),
        listOf(
            Allocation(Asset.USD, 10.0),
            Allocation(Asset.BTC, 50.0),
            Allocation(Asset.ETH, 40.0),
        ),
    )

    private fun snap(ageSeconds: Long, total: String) = PortfolioSnapshot(
        timestamp = Instant.now().minusSeconds(ageSeconds),
        totalValueUSD = BigDecimal(total),
        assets = emptyMap(),
        actions = emptyList(),
        drawdownPercent = BigDecimal.ZERO,
        fiatDeploymentPercent = BigDecimal.ZERO,
        effectiveUsdTargetPercent = BigDecimal("10.0"),
    )

    private fun assetSnapshot(
        symbol: String,
        balance: String,
        price: String,
        valueUSD: String,
        targetPercent: String,
        currentPercent: String = targetPercent,
        deviationPercent: String = "0.0",
        deviationUSD: String = "0.0",
    ) = PortfolioSnapshot.AssetSnapshot(
        symbol = Asset(symbol),
        balance = BigDecimal(balance),
        price = BigDecimal(price),
        valueUSD = BigDecimal(valueUSD),
        targetPercent = BigDecimal(targetPercent),
        currentPercent = BigDecimal(currentPercent),
        deviationPercent = BigDecimal(deviationPercent),
        deviationUSD = BigDecimal(deviationUSD),
    )

    init {
        "renderDashboardShell_containsExpectedContent" {
            val html = createHTML().html { view.renderDashboardShell(baseConfig.settings) }
            html shouldContain "title>Kraken Rebalancer"
            html shouldContain "link href=\"/static/style.css?v="
            html shouldContain "script src=\"https://unpkg.com/htmx.org@2.0.4\""
            html shouldContain "integrity=\"sha384-HGfztofotfshcF7+8n44JQL2oJmowVChPTg48S+jvZoztPfvwD79OC/LTtG6dMp+\""
            html shouldContain "crossorigin=\"anonymous\""
            html shouldContain "hx-ext=\"sse\""
            html shouldContain "sse-connect=\"/api/status/stream\""
            html shouldContain "Connecting to KrakenRebalancer..."
            html shouldContain "DRY RUN"
            html shouldContain "id=\"header-status\""
            html shouldContain "STREAM"
        }

        "renderDashboardShell_simulationMode_rendersSimulationPlate" {
            val simSettings = baseConfig.settings.copy(simulation = true)
            val html = createHTML().html { view.renderDashboardShell(simSettings) }
            html shouldContain "SIMULATION"
        }

        "renderSettingsPage_withNoError_containsForm" {
            val html = createHTML().html {
                view.renderSettingsPage(baseConfig, null, testCsrfToken)
            }
            html shouldContain "title>Settings - Kraken Rebalancer"
            listOf(
                "loopDelaySeconds",
                "deviationTriggerPercent",
                "dustThresholdUSD",
                "fiatMaxDrawdown",
                "fiatDeploymentExponent",
            ).forEach { field ->
                html shouldContain "name=\"$field\""
                html shouldContain "id=\"$field\""
                html shouldContain "for=\"$field\""
            }
            html shouldContain "value=\"60\""
            html shouldContain "name=\"deviationTriggerPercent\""
            html shouldContain "value=\"2.0\""
            html shouldContain "name=\"csrfToken\""
            html shouldContain "value=\"$testCsrfToken\""
            html shouldContain "Safety Modes"
            html shouldContain "safety-state-on"
            html shouldContain "safety-state-off"
            html shouldContain "id=\"mode-plate\""
            html shouldNotContain "error-banner"
        }

        "renderSettingsPage_allocationTargets_carryPercentBounds" {
            val html = createHTML().html {
                view.renderSettingsPage(baseConfig, null, testCsrfToken)
            }
            val targetInput = Regex("<input[^>]*name=\"targets\"[^>]*>").find(html)?.value
            targetInput.shouldNotBeNull()
            targetInput shouldContain "min=\"0.0\""
            targetInput shouldContain "max=\"100.0\""
        }

        "renderSettingsPage_globalParameters_carryValidationBounds" {
            val html = createHTML().html {
                view.renderSettingsPage(baseConfig, null, testCsrfToken)
            }
            fun namedInput(name: String): String {
                val input = Regex("<input[^>]*name=\"$name\"[^>]*>").find(html)?.value
                input.shouldNotBeNull()
                return input
            }

            namedInput("dustThresholdUSD") shouldContain "min=\"0\""
            val fiatMax = namedInput("fiatMaxDrawdown")
            fiatMax shouldContain "min=\"0\""
            fiatMax shouldContain "max=\"100\""
            namedInput("fiatDeploymentExponent") shouldContain "min=\"0.1\""
        }

        "renderSettingsPage_withError_displaysError" {
            val errMsg = "Invalid configuration: must sum to 100%"
            val html = createHTML().html {
                view.renderSettingsPage(baseConfig, errMsg, testCsrfToken)
            }
            html shouldContain errMsg
            html shouldContain "error-banner"
        }

        "renderDashboardFragment_withLiveSnapshotAndHistory_rendersCorrectly" {
            val now = Instant.now()
            val latest = PortfolioSnapshot(
                timestamp = now,
                totalValueUSD = BigDecimal("10000.00"),
                assets = mapOf(
                    Asset.USD to assetSnapshot(
                        symbol = Asset.USD,
                        balance = "1000.0",
                        price = "1.0",
                        valueUSD = "1000.0",
                        targetPercent = "10.0",
                    ),
                    Asset.BTC to assetSnapshot(
                        symbol = Asset.BTC,
                        balance = "0.1",
                        price = "50000.0",
                        valueUSD = "5000.0",
                        targetPercent = "50.0",
                        deviationPercent = "5.0",
                        deviationUSD = "250.0",
                    ),
                    Asset.ETH to assetSnapshot(
                        symbol = Asset.ETH,
                        balance = "2.0",
                        price = "2000.0",
                        valueUSD = "4000.0",
                        targetPercent = "40.0",
                        deviationPercent = "-2.5",
                        deviationUSD = "-100.0",
                    ),
                ),
                actions = listOf(
                    "BUY BTC Volume: 0.05 Value: $2500.00",
                    "SELL ETH Volume: 1.0 Value: $2000.00",
                ),
                drawdownPercent = BigDecimal("5.0"),
                fiatDeploymentPercent = BigDecimal("25.0"),
                effectiveUsdTargetPercent = BigDecimal("7.5"),
            )

            val history = listOf(latest)

            val html = createHTML().div {
                view.renderDashboardFragment(latest, history)
            }

            html shouldContain "STREAM"
            html shouldContain "id=\"header-status\""
            html shouldContain "hx-swap-oob=\"true\""
            html shouldContain "Total Portfolio"
            html shouldContain "$10,000.00"
            html shouldContain "Cash (USD)"
            html shouldContain "$1,000.00"
            html shouldContain "Target: 7.50%"
            html shouldContain "(Base: 10.00%)"
            html shouldContain "Dev: 0.00%"
            html shouldContain "Drawdown: 5.00%"
            html shouldContain "Crypto Assets"
            html shouldContain "$9,000.00"
            html shouldContain "Target: 90.00% | 2 Assets"

            html shouldContain "allocation-bar-label\">BTC"
            html shouldContain "allocation-bar-label\">ETH"

            html shouldContain "badge badge-buy\">BUY"
            html shouldContain "badge badge-sell\">SELL"
        }

        "renderDashboardFragment_withStaleData_rendersDelayedBadge" {
            val oldTime = Instant.now().minusSeconds(100)
            val latest = PortfolioSnapshot(
                timestamp = oldTime,
                totalValueUSD = BigDecimal("1000.00"),
                assets = mapOf(
                    Asset.USD to assetSnapshot(
                        symbol = Asset.USD,
                        balance = "1000.0",
                        price = "1.0",
                        valueUSD = "1000.0",
                        targetPercent = "100.0",
                    ),
                ),
                actions = emptyList(),
                drawdownPercent = BigDecimal.ZERO,
                fiatDeploymentPercent = BigDecimal.ZERO,
                effectiveUsdTargetPercent = BigDecimal("100.0"),
            )

            val html = createHTML().div {
                view.renderDashboardFragment(latest, emptyList())
            }

            html shouldContain "STALE"
            html shouldContain "No trading history available."
        }

        "renderDashboardFragment_edgeCases_coversUncoveredBranches" {
            val now = Instant.now()
            val emptyAssetsLatest = PortfolioSnapshot(
                timestamp = now,
                totalValueUSD = BigDecimal.ZERO,
                assets = emptyMap(),
                actions = listOf("INFO Rebalancer initialized"),
                drawdownPercent = BigDecimal.ZERO,
                fiatDeploymentPercent = BigDecimal.ZERO,
                effectiveUsdTargetPercent = BigDecimal("10.0"),
            )
            createHTML().div {
                view.renderDashboardFragment(emptyAssetsLatest, emptyList())
            }

            val latest = PortfolioSnapshot(
                timestamp = now,
                totalValueUSD = BigDecimal.ZERO,
                assets = mapOf(
                    Asset.BTC to assetSnapshot(
                        symbol = Asset.BTC,
                        balance = "0",
                        price = "0",
                        valueUSD = "0",
                        targetPercent = "0",
                    ),
                ),
                actions = listOf("INFO Rebalancer initialized"),
                drawdownPercent = BigDecimal.ZERO,
                fiatDeploymentPercent = BigDecimal.ZERO,
                effectiveUsdTargetPercent = BigDecimal("10.0"),
            )

            val noActionsSnapshot = PortfolioSnapshot(
                timestamp = now.minusSeconds(60),
                totalValueUSD = BigDecimal.ZERO,
                assets = emptyMap(),
                actions = emptyList(),
                drawdownPercent = BigDecimal.ZERO,
                fiatDeploymentPercent = BigDecimal.ZERO,
                effectiveUsdTargetPercent = BigDecimal("10.0"),
            )

            val html = createHTML().div {
                view.renderDashboardFragment(
                    latest,
                    listOf(latest, noActionsSnapshot),
                )
            }

            html shouldContain "No USD Data"
            html shouldContain "badge badge-info\">INFO"
            html shouldContain "No trades — portfolio within tolerance"
        }

        "renderDashboardFragment_usdTargetEqual_doesNotPrintBaseTarget" {
            val now = Instant.now()
            val latest = PortfolioSnapshot(
                timestamp = now,
                totalValueUSD = BigDecimal("1000.00"),
                assets = mapOf(
                    Asset.USD to assetSnapshot(
                        symbol = Asset.USD,
                        balance = "100.0",
                        price = "1.0",
                        valueUSD = "100.0",
                        targetPercent = "10.0",
                    ),
                ),
                actions = emptyList(),
                drawdownPercent = BigDecimal.ZERO,
                fiatDeploymentPercent = BigDecimal.ZERO,
                effectiveUsdTargetPercent = BigDecimal("10.0"),
            )

            val html = createHTML().div {
                view.renderDashboardFragment(latest, emptyList())
            }

            html shouldContain "Target: 10.00%"
            html shouldNotContain "(Base: 10.00%)"
        }

        "compute24hDelta_coversAllBranches" {
            PortfolioCalculations.compute24hDelta(snap(0, "100"), emptyList()) shouldBe null

            val latestUp = snap(0, "11000")
            val olderBase = snap(90_000, "10000")
            PortfolioCalculations.compute24hDelta(latestUp, listOf(latestUp, olderBase))!!
                .shouldBeEqualComparingTo(BigDecimal("10.000000"))

            val latestDown = snap(0, "9000")
            val recent = snap(3_600, "10000")
            PortfolioCalculations.compute24hDelta(latestDown, listOf(latestDown, recent)) shouldBe null

            val latestZeroBase = snap(0, "5000")
            val zeroBase = snap(90_000, "0")
            PortfolioCalculations.compute24hDelta(latestZeroBase, listOf(latestZeroBase, zeroBase)) shouldBe null
        }

        "sparklineSvg_coversRangeBranches" {
            OverviewGridComponent.sparklineSvg(emptyList()) shouldBe ""

            val flat = OverviewGridComponent.sparklineSvg(listOf(snap(0, "1000"), snap(3_600, "1000")))
            flat shouldContain "<svg"

            val varied = OverviewGridComponent.sparklineSvg(listOf(snap(0, "1200"), snap(3_600, "1000")))
            varied shouldContain "polyline"
        }

        "renderDashboardFragment_deltaChip_rendersUpDownAndRelativeTimes" {
            val deltaUp = "hero-delta up"
            val deltaDown = "hero-delta down"

            val latestUp = snap(0, "11000")
            val historyUp =
                listOf(
                    latestUp,
                    snap(120, "10900"),
                    snap(7_200, "10500"),
                    snap(90_000, "10000"),
                )
            val htmlUp = createHTML().div {
                view.renderDashboardFragment(
                    latestUp,
                    historyUp,
                    delta24h = PortfolioCalculations.compute24hDelta(latestUp, historyUp),
                )
            }
            htmlUp shouldContain deltaUp
            htmlUp shouldContain "m ago"
            htmlUp shouldContain "h ago"
            htmlUp shouldContain "d ago"

            val latestDown = snap(0, "9000")
            val historyDown = listOf(latestDown, snap(90_000, "10000"))
            val htmlDown = createHTML().div {
                view.renderDashboardFragment(
                    latestDown,
                    historyDown,
                    delta24h = PortfolioCalculations.compute24hDelta(latestDown, historyDown),
                )
            }
            htmlDown shouldContain deltaDown
        }

        "Icons_loadIcon_returnsEmptyOnMissingResource" {
            val method = Icons::class.java.getDeclaredMethod("loadIcon", String::class.java)
            method.isAccessible = true
            val result = method.invoke(Icons, "nonexistent.svg")
            result shouldBe ""
        }

        "PerformanceTableComponent_Companion_getCOLUMNS" {
            val companionClass = Class.forName(
                $$"$${PerformanceTableComponent::class.java.name}$Companion",
            )
            val getCOLUMNS = companionClass.getDeclaredMethod("getCOLUMNS")
            getCOLUMNS.isAccessible = true
            val companionField = PerformanceTableComponent::class.java.getDeclaredField("Companion")
            companionField.isAccessible = true
            val companionInstance = companionField.get(null)
            val columns = getCOLUMNS.invoke(companionInstance) as List<*>
            columns.size shouldBe 6
        }

        "DashboardView_renderHistoryPage" {
            val html = createHTML().html {
                view.renderHistoryPage(baseConfig.settings)
            }
            html shouldContain "History - Kraken Rebalancer"
        }
    }
}
