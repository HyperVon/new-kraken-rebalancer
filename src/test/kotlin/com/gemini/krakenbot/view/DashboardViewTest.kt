package com.gemini.krakenbot.view

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.view.component.AllocationChartComponent
import com.gemini.krakenbot.view.component.DashboardFragmentComponent
import com.gemini.krakenbot.view.component.DashboardShellComponent
import com.gemini.krakenbot.view.component.HistoryPageComponent
import com.gemini.krakenbot.view.component.OverviewGridComponent
import com.gemini.krakenbot.view.component.PerformanceTableComponent
import com.gemini.krakenbot.view.component.RecentActivityComponent
import com.gemini.krakenbot.view.component.SettingsFormComponent
import com.gemini.krakenbot.view.util.CdnIntegrity
import com.gemini.krakenbot.view.util.CdnUrls
import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.FormFields.CSRF_TOKEN
import com.gemini.krakenbot.view.util.FormFields.DEVIATION_TRIGGER_PERCENT
import com.gemini.krakenbot.view.util.FormFields.DUST_THRESHOLD_USD
import com.gemini.krakenbot.view.util.FormFields.FIAT_DEPLOYMENT_EXPONENT
import com.gemini.krakenbot.view.util.FormFields.FIAT_MAX_DRAWDOWN
import com.gemini.krakenbot.view.util.FormFields.LOOP_DELAY_SECONDS
import com.gemini.krakenbot.view.util.FormFields.TARGETS
import com.gemini.krakenbot.view.util.HtmlIds
import com.gemini.krakenbot.view.util.Icons
import com.gemini.krakenbot.view.util.Routes.API_STATUS_STREAM
import com.gemini.krakenbot.view.util.Routes.STATIC_STYLE_CSS
import com.gemini.krakenbot.view.util.ViewText.ACTIVITY_NO_TRADES
import com.gemini.krakenbot.view.util.ViewText.APP_TITLE
import com.gemini.krakenbot.view.util.ViewText.ASSETS_SUFFIX
import com.gemini.krakenbot.view.util.ViewText.CASH_USD
import com.gemini.krakenbot.view.util.ViewText.CONNECTING
import com.gemini.krakenbot.view.util.ViewText.CRYPTO_ASSETS
import com.gemini.krakenbot.view.util.ViewText.DEV_PREFIX
import com.gemini.krakenbot.view.util.ViewText.MODE_DRY_RUN
import com.gemini.krakenbot.view.util.ViewText.MODE_SIMULATION
import com.gemini.krakenbot.view.util.ViewText.NO_TRADING_HISTORY
import com.gemini.krakenbot.view.util.ViewText.NO_USD_DATA
import com.gemini.krakenbot.view.util.ViewText.SAFETY_MODES
import com.gemini.krakenbot.view.util.ViewText.SETTINGS_TITLE
import com.gemini.krakenbot.view.util.ViewText.STREAM
import com.gemini.krakenbot.view.util.ViewText.STREAM_STALE
import com.gemini.krakenbot.view.util.ViewText.TARGET_PREFIX
import com.gemini.krakenbot.view.util.ViewText.TOTAL_PORTFOLIO
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

    private val ALLOCATION_BAR_LABEL = CssClass.AllocationChart.BarLabel.toString()
    private val BADGE_BUY = CssClass.Badge.Buy.toString()
    private val BADGE_INFO = CssClass.Badge.Info.toString()
    private val BADGE_SELL = CssClass.Badge.Sell.toString()
    private val ERROR_BANNER = CssClass.Utility.ErrorBanner.toString()
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

    init {
        "renderDashboardShell_containsExpectedContent" {
            val html = createHTML().html { view.renderDashboardShell(baseConfig.settings) }
            html shouldContain "title>${APP_TITLE}"
            html shouldContain "link href=\"${STATIC_STYLE_CSS}?v="
            html shouldContain "script src=\"${CdnUrls.HTMX}\""
            html shouldContain "integrity=\"${CdnIntegrity.HTMX}\""
            html shouldContain "crossorigin=\"anonymous\""
            html shouldContain "hx-ext=\"sse\""
            html shouldContain "sse-connect=\"${API_STATUS_STREAM}\""
            html shouldContain CONNECTING
            html shouldContain MODE_DRY_RUN
            html shouldContain "id=\"${HtmlIds.HEADER_STATUS}\""
            html shouldContain STREAM
        }

        "renderDashboardShell_simulationMode_rendersSimulationPlate" {
            val simSettings = baseConfig.settings.copy(simulation = true)
            val html = createHTML().html { view.renderDashboardShell(simSettings) }
            html shouldContain MODE_SIMULATION
        }

        "renderSettingsPage_withNoError_containsForm" {
            val html = createHTML().html {
                view.renderSettingsPage(baseConfig, null, testCsrfToken)
            }
            html shouldContain "title>${SETTINGS_TITLE} - $APP_TITLE"
            listOf(
                LOOP_DELAY_SECONDS,
                DEVIATION_TRIGGER_PERCENT,
                DUST_THRESHOLD_USD,
                FIAT_MAX_DRAWDOWN,
                FIAT_DEPLOYMENT_EXPONENT,
            ).forEach { field ->
                html shouldContain "name=\"$field\""
                html shouldContain "id=\"$field\""
                html shouldContain "for=\"$field\""
            }
            html shouldContain "value=\"60\""
            html shouldContain "name=\"${DEVIATION_TRIGGER_PERCENT}\""
            html shouldContain "value=\"2.0\""
            html shouldContain "name=\"$CSRF_TOKEN\""
            html shouldContain "value=\"$testCsrfToken\""
            html shouldContain SAFETY_MODES
            html shouldContain "safety-state-on"
            html shouldContain "safety-state-off"
            html shouldContain "id=\"mode-plate\""
            html shouldNotContain ERROR_BANNER
        }

        "renderSettingsPage_allocationTargets_carryPercentBounds" {
            val html = createHTML().html {
                view.renderSettingsPage(baseConfig, null, testCsrfToken)
            }
            val targetInput = Regex("<input[^>]*name=\"$TARGETS\"[^>]*>").find(html)?.value
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

            namedInput(DUST_THRESHOLD_USD) shouldContain "min=\"0\""
            val fiatMax = namedInput(FIAT_MAX_DRAWDOWN)
            fiatMax shouldContain "min=\"0\""
            fiatMax shouldContain "max=\"100\""
            namedInput(FIAT_DEPLOYMENT_EXPONENT) shouldContain "min=\"0.1\""
        }

        "renderSettingsPage_withError_displaysError" {
            val errMsg = "Invalid configuration: must sum to 100%"
            val html = createHTML().html {
                view.renderSettingsPage(baseConfig, errMsg, testCsrfToken)
            }
            html shouldContain errMsg
            html shouldContain ERROR_BANNER
        }

        "renderDashboardFragment_withLiveSnapshotAndHistory_rendersCorrectly" {
            val now = Instant.now()
            val latest = PortfolioSnapshot(
                timestamp = now,
                totalValueUSD = BigDecimal("10000.00"),
                assets = mapOf(
                    Asset.USD to PortfolioSnapshot.AssetSnapshot(
                        symbol = Asset.USD,
                        balance = BigDecimal("1000.0"),
                        price = BigDecimal("1.0"),
                        valueUSD = BigDecimal("1000.0"),
                        targetPercent = BigDecimal("10.0"),
                        currentPercent = BigDecimal("10.0"),
                        deviationPercent = BigDecimal("0.0"),
                        deviationUSD = BigDecimal("0.0"),
                    ),
                    Asset.BTC to PortfolioSnapshot.AssetSnapshot(
                        symbol = Asset.BTC,
                        balance = BigDecimal("0.1"),
                        price = BigDecimal("50000.0"),
                        valueUSD = BigDecimal("5000.0"),
                        targetPercent = BigDecimal("50.0"),
                        currentPercent = BigDecimal("50.0"),
                        deviationPercent = BigDecimal("5.0"),
                        deviationUSD = BigDecimal("250.0"),
                    ),
                    Asset.ETH to PortfolioSnapshot.AssetSnapshot(
                        symbol = Asset.ETH,
                        balance = BigDecimal("2.0"),
                        price = BigDecimal("2000.0"),
                        valueUSD = BigDecimal("4000.0"),
                        targetPercent = BigDecimal("40.0"),
                        currentPercent = BigDecimal("40.0"),
                        deviationPercent = BigDecimal("-2.5"),
                        deviationUSD = BigDecimal("-100.0"),
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

            html shouldContain STREAM
            html shouldContain "id=\"header-status\""
            html shouldContain "hx-swap-oob=\"true\""
            html shouldContain TOTAL_PORTFOLIO
            html shouldContain "$10,000.00"
            html shouldContain CASH_USD
            html shouldContain "$1,000.00"
            html shouldContain "${TARGET_PREFIX}7.50%"
            html shouldContain "(Base: 10.00%)"
            html shouldContain "${DEV_PREFIX}0.00%"
            html shouldContain "Drawdown: 5.00%"
            html shouldContain CRYPTO_ASSETS
            html shouldContain "$9,000.00"
            html shouldContain "${TARGET_PREFIX}90.00% | 2${ASSETS_SUFFIX}"

            html shouldContain "${ALLOCATION_BAR_LABEL}\">BTC"
            html shouldContain "${ALLOCATION_BAR_LABEL}\">ETH"

            html shouldContain "${BADGE_BUY}\">BUY"
            html shouldContain "${BADGE_SELL}\">SELL"
        }

        "renderDashboardFragment_withStaleData_rendersDelayedBadge" {
            val oldTime = Instant.now().minusSeconds(100)
            val latest = PortfolioSnapshot(
                timestamp = oldTime,
                totalValueUSD = BigDecimal("1000.00"),
                assets = mapOf(
                    Asset.USD to PortfolioSnapshot.AssetSnapshot(
                        symbol = Asset.USD,
                        balance = BigDecimal("1000.0"),
                        price = BigDecimal("1.0"),
                        valueUSD = BigDecimal("1000.0"),
                        targetPercent = BigDecimal("100.0"),
                        currentPercent = BigDecimal("100.0"),
                        deviationPercent = BigDecimal("0.0"),
                        deviationUSD = BigDecimal("0.0"),
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

            html shouldContain STREAM_STALE
            html shouldContain NO_TRADING_HISTORY
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
                    Asset.BTC to PortfolioSnapshot.AssetSnapshot(
                        symbol = Asset.BTC,
                        balance = BigDecimal.ZERO,
                        price = BigDecimal.ZERO,
                        valueUSD = BigDecimal.ZERO,
                        targetPercent = BigDecimal.ZERO,
                        currentPercent = BigDecimal.ZERO,
                        deviationPercent = BigDecimal.ZERO,
                        deviationUSD = BigDecimal.ZERO,
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

            html shouldContain NO_USD_DATA
            html shouldContain "${BADGE_INFO}\">INFO"
            html shouldContain ACTIVITY_NO_TRADES
        }

        "renderDashboardFragment_usdTargetEqual_doesNotPrintBaseTarget" {
            val now = Instant.now()
            val latest = PortfolioSnapshot(
                timestamp = now,
                totalValueUSD = BigDecimal("1000.00"),
                assets = mapOf(
                    Asset.USD to PortfolioSnapshot.AssetSnapshot(
                        symbol = Asset.USD,
                        balance = BigDecimal("100.0"),
                        price = BigDecimal("1.0"),
                        valueUSD = BigDecimal("100.0"),
                        targetPercent = BigDecimal("10.0"),
                        currentPercent = BigDecimal("10.0"),
                        deviationPercent = BigDecimal("0.0"),
                        deviationUSD = BigDecimal("0.0"),
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

            html shouldContain "${TARGET_PREFIX}10.00%"
            html shouldNotContain "(Base: 10.00%)"
        }

        "compute24hDelta_coversAllBranches" {
            OverviewGridComponent.compute24hDelta(snap(0, "100"), emptyList()) shouldBe null

            val latestUp = snap(0, "11000")
            val olderBase = snap(90_000, "10000")
            OverviewGridComponent.compute24hDelta(latestUp, listOf(latestUp, olderBase))!!
                .shouldBeEqualComparingTo(BigDecimal("10.000000"))

            // A shorter history must not be mislabeled as a 24-hour delta.
            val latestDown = snap(0, "9000")
            val recent = snap(3_600, "10000")
            OverviewGridComponent.compute24hDelta(latestDown, listOf(latestDown, recent)) shouldBe null

            val latestZeroBase = snap(0, "5000")
            val zeroBase = snap(90_000, "0")
            OverviewGridComponent.compute24hDelta(latestZeroBase, listOf(latestZeroBase, zeroBase)) shouldBe null
        }

        "sparklineSvg_coversRangeBranches" {
            OverviewGridComponent.sparklineSvg(emptyList()) shouldBe ""

            val flat = OverviewGridComponent.sparklineSvg(listOf(snap(0, "1000"), snap(3_600, "1000")))
            flat shouldContain "<svg"

            val varied = OverviewGridComponent.sparklineSvg(listOf(snap(0, "1200"), snap(3_600, "1000")))
            varied shouldContain "polyline"
        }

        "renderDashboardFragment_deltaChip_rendersUpDownAndRelativeTimes" {
            val deltaUp = CssClass.Hero.DeltaUp.toString()
            val deltaDown = CssClass.Hero.DeltaDown.toString()

            val latestUp = snap(0, "11000")
            val historyUp =
                listOf(
                    latestUp,
                    snap(120, "10900"),
                    snap(7_200, "10500"),
                    snap(90_000, "10000"),
                )
            val htmlUp = createHTML().div {
                view.renderDashboardFragment(latestUp, historyUp)
            }
            htmlUp shouldContain deltaUp
            htmlUp shouldContain "m ago"
            htmlUp shouldContain "h ago"
            htmlUp shouldContain "d ago"

            val latestDown = snap(0, "9000")
            val historyDown = listOf(latestDown, snap(90_000, "10000"))
            val htmlDown = createHTML().div {
                view.renderDashboardFragment(latestDown, historyDown)
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
