package com.gemini.krakenbot.view

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.view.component.*
import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.FormFields.DEVIATION_TRIGGER_PERCENT
import com.gemini.krakenbot.view.util.FormFields.LOOP_DELAY_SECONDS
import com.gemini.krakenbot.view.util.Routes.API_STATUS_STREAM
import com.gemini.krakenbot.view.util.Routes.STATIC_STYLE_CSS
import com.gemini.krakenbot.view.util.ViewText.APP_TITLE
import com.gemini.krakenbot.view.util.ViewText.ASSETS_SUFFIX
import com.gemini.krakenbot.view.util.ViewText.BASE_PREFIX
import com.gemini.krakenbot.view.util.ViewText.CASH_USD
import com.gemini.krakenbot.view.util.ViewText.CONNECTING
import com.gemini.krakenbot.view.util.ViewText.CRYPTO_ASSETS
import com.gemini.krakenbot.view.util.ViewText.DELAYED
import com.gemini.krakenbot.view.util.ViewText.DEV_PREFIX
import com.gemini.krakenbot.view.util.ViewText.DRAWDOWN_PREFIX
import com.gemini.krakenbot.view.util.ViewText.LIVE
import com.gemini.krakenbot.view.util.ViewText.NO_TRADES_EXECUTED
import com.gemini.krakenbot.view.util.ViewText.NO_TRADING_HISTORY
import com.gemini.krakenbot.view.util.ViewText.NO_USD_DATA
import com.gemini.krakenbot.view.util.ViewText.SETTINGS_TITLE
import com.gemini.krakenbot.view.util.ViewText.TARGET_PREFIX
import com.gemini.krakenbot.view.util.ViewText.TOTAL_PORTFOLIO
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import com.gemini.krakenbot.view.util.Icons
import kotlinx.html.div
import kotlinx.html.html
import kotlinx.html.stream.createHTML
import java.math.BigDecimal
import java.time.Instant

@Suppress("unused")
class DashboardViewTest : StringSpec() {
    private val shell = DashboardShellComponent()
    private val overview = OverviewGridComponent()
    private val chart = AllocationChartComponent()
    private val table = PerformanceTableComponent()
    private val activity = RecentActivityComponent()
    private val fragment = DashboardFragmentComponent(
        overviewGridComponent = overview,
        allocationChartComponent = chart,
        performanceTableComponent = table,
        recentActivityComponent = activity
    )
    private val view = DashboardView(
        shellComponent = shell,
        settingsFormComponent = SettingsFormComponent(),
        fragmentComponent = fragment,
        historyPageComponent = HistoryPageComponent()
    )

    private val ALLOCATION_BAR_LABEL = CssClass.AllocationChart.BarLabel.value
    private val BADGE_BUY = CssClass.Badge.Buy.value
    private val BADGE_INFO = CssClass.Badge.Info.value
    private val BADGE_SELL = CssClass.Badge.Sell.value
    private val ERROR_BANNER = CssClass.Utility.ErrorBanner.value

    private val baseConfig = AppConfig(
        KrakenCredentials(
            apiKey = TestFixtures.TEST_API_KEY,
            privateKey = "privateKey"
        ),
        Settings(
            loopDelaySeconds = 60L,
            deviationTriggerPercent = 2.0,
            dustThresholdUSD = 5.0,
            dryRun = true,
            fiatMaxDrawdown = 20.0,
            fiatDeploymentExponent = 1.0
        ),
        listOf(
            Allocation(Asset.USD, 10.0),
            Allocation(Asset.BTC, 50.0),
            Allocation(Asset.ETH, 40.0)
        )
    )

    init {
        "renderDashboardShell_containsExpectedContent" {
            val html = createHTML().html { view.renderDashboardShell() }
            html shouldContain "title>${APP_TITLE}"
            html shouldContain "link href=\"${STATIC_STYLE_CSS}\" rel=\"stylesheet\""
            html shouldContain "script src=\"https://unpkg.com/htmx.org@2.0.4\""
            html shouldContain "hx-ext=\"sse\""
            html shouldContain "sse-connect=\"${API_STATUS_STREAM}\""
            html shouldContain CONNECTING
        }

        "renderSettingsPage_withNoError_containsForm" {
            val html = createHTML().html {
                view.renderSettingsPage(baseConfig, null)
            }
            html shouldContain "title>${SETTINGS_TITLE} - $APP_TITLE"
            html shouldContain "name=\"${LOOP_DELAY_SECONDS}\""
            html shouldContain "value=\"60\""
            html shouldContain "name=\"${DEVIATION_TRIGGER_PERCENT}\""
            html shouldContain "value=\"2.0\""
            html shouldNotContain ERROR_BANNER
        }

        "renderSettingsPage_withError_displaysError" {
            val errMsg = "Invalid configuration: must sum to 100%"
            val html = createHTML().html {
                view.renderSettingsPage(baseConfig, errMsg)
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
                        deviationUSD = BigDecimal("0.0")
                    ),
                    Asset.BTC to PortfolioSnapshot.AssetSnapshot(
                        symbol = Asset.BTC,
                        balance = BigDecimal("0.1"),
                        price = BigDecimal("50000.0"),
                        valueUSD = BigDecimal("5000.0"),
                        targetPercent = BigDecimal("50.0"),
                        currentPercent = BigDecimal("50.0"),
                        deviationPercent = BigDecimal("5.0"),
                        deviationUSD = BigDecimal("250.0")
                    ),
                    Asset.ETH to PortfolioSnapshot.AssetSnapshot(
                        symbol = Asset.ETH,
                        balance = BigDecimal("2.0"),
                        price = BigDecimal("2000.0"),
                        valueUSD = BigDecimal("4000.0"),
                        targetPercent = BigDecimal("40.0"),
                        currentPercent = BigDecimal("40.0"),
                        deviationPercent = BigDecimal("-2.5"),
                        deviationUSD = BigDecimal("-100.0")
                    )
                ),
                actions = listOf(
                    "BUY BTC Volume: 0.05 Value: $2500.0",
                    "SELL ETH Volume: 1.0 Value: $2000.0"
                ),
                drawdownPercent = BigDecimal("5.0"),
                fiatDeploymentPercent = BigDecimal("25.0"),
                effectiveUsdTargetPercent = BigDecimal("7.5")
            )

            val history = listOf(latest)

            val html = createHTML().div {
                view.renderDashboardFragment(latest, history)
            }

            html shouldContain LIVE
            html shouldNotContain DELAYED
            html shouldContain TOTAL_PORTFOLIO
            html shouldContain "$10,000.00"
            html shouldContain "${DRAWDOWN_PREFIX}5.00%"
            html shouldContain CASH_USD
            html shouldContain "$1,000.00"
            html shouldContain "10.00% | ${TARGET_PREFIX}7.50% (${BASE_PREFIX}10.00%)"
            html shouldContain "${DEV_PREFIX}0.00%"
            html shouldContain CRYPTO_ASSETS
            html shouldContain "$9,000.00"
            html shouldContain "90.00% | ${TARGET_PREFIX}90.00% | 2${ASSETS_SUFFIX}"

            // Allocation bars
            html shouldContain "${ALLOCATION_BAR_LABEL}\">BTC"
            html shouldContain "${ALLOCATION_BAR_LABEL}\">ETH"

            // Recent activity badges
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
                        deviationUSD = BigDecimal("0.0")
                    )
                ),
                actions = emptyList(),
                drawdownPercent = BigDecimal.ZERO,
                fiatDeploymentPercent = BigDecimal.ZERO,
                effectiveUsdTargetPercent = BigDecimal("100.0")
            )

            val html = createHTML().div {
                view.renderDashboardFragment(latest, emptyList())
            }

            html shouldContain DELAYED
            html shouldNotContain LIVE
            html shouldContain NO_TRADING_HISTORY
        }

        "renderDashboardFragment_edgeCases_coversUncoveredBranches" {
            val now = Instant.now()
            val emptyAssetsLatest = PortfolioSnapshot(
                timestamp = now,
                totalValueUSD = BigDecimal.ZERO,
                assets = emptyMap(), // covers empty assets / maxVal default path
                actions = listOf("INFO Rebalancer initialized"),
                drawdownPercent = BigDecimal.ZERO,
                fiatDeploymentPercent = BigDecimal.ZERO,
                effectiveUsdTargetPercent = BigDecimal("10.0")
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
                        deviationUSD = BigDecimal.ZERO
                    )
                ), // covers maxVal <= 0 in renderAllocationChart
                actions = listOf("INFO Rebalancer initialized"), // neither BUY nor SELL
                drawdownPercent = BigDecimal.ZERO, // drawdown is 0
                fiatDeploymentPercent = BigDecimal.ZERO,
                effectiveUsdTargetPercent = BigDecimal("10.0")
            )

            val noActionsSnapshot = PortfolioSnapshot(
                timestamp = now.minusSeconds(60),
                totalValueUSD = BigDecimal.ZERO,
                assets = emptyMap(),
                actions = emptyList(), // covers empty actions inside the recent activity table
                drawdownPercent = BigDecimal.ZERO,
                fiatDeploymentPercent = BigDecimal.ZERO,
                effectiveUsdTargetPercent = BigDecimal("10.0")
            )

            val html = createHTML().div {
                view.renderDashboardFragment(
                    latest,
                    listOf(latest, noActionsSnapshot)
                )
            }

            html shouldContain NO_USD_DATA
            html shouldContain "${DRAWDOWN_PREFIX}0.00%"
            html shouldContain "${BADGE_INFO}\">INFO"
            html shouldContain NO_TRADES_EXECUTED
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
                        deviationUSD = BigDecimal("0.0")
                    )
                ),
                actions = emptyList(),
                drawdownPercent = BigDecimal.ZERO,
                fiatDeploymentPercent = BigDecimal.ZERO,
                effectiveUsdTargetPercent = BigDecimal("10.0") // Equal to targetPercent
            )

            val html = createHTML().div {
                view.renderDashboardFragment(latest, emptyList())
            }

            html shouldContain "10.00% | ${TARGET_PREFIX}10.00%"
            html shouldNotContain "(Base: 10.00%)"
        }

        "Icons_loadIcon_returnsEmptyOnMissingResource" {
            val method = Icons::class.java.getDeclaredMethod("loadIcon", String::class.java)
            method.isAccessible = true
            val result = method.invoke(Icons, "nonexistent.svg")
            result shouldBe ""
        }

        "PerformanceTableComponent_Companion_getCOLUMNS" {
            val companionClass = Class.forName($$"com.gemini.krakenbot.view.component.PerformanceTableComponent$Companion")
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
                view.renderHistoryPage()
            }
            html shouldContain "History - Kraken Rebalancer"
        }
    }
}
