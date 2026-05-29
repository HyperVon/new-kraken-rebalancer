package com.gemini.krakenbot.view

import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.model.PortfolioSnapshot
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.html.html
import kotlinx.html.div
import kotlinx.html.stream.createHTML
import com.gemini.krakenbot.view.component.*
import com.gemini.krakenbot.view.util.CssClasses
import com.gemini.krakenbot.view.util.FormFields
import com.gemini.krakenbot.view.util.Routes
import com.gemini.krakenbot.view.util.ViewText
import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.util.KrakenSymbols
import java.math.BigDecimal
import java.time.Instant

@Suppress("unused")
class DashboardViewTest : StringSpec({
    val shell = DashboardShellComponent()
    val overview = OverviewGridComponent()
    val chart = AllocationChartComponent()
    val table = PerformanceTableComponent()
    val activity = RecentActivityComponent()
    val fragment = DashboardFragmentComponent(overview, chart, table, activity)
    val view = DashboardView(shell, SettingsFormComponent(), fragment)

    val baseConfig = AppConfig(
        KrakenCredentials(TestFixtures.TEST_API_KEY, "privateKey"),
        Settings(60L, 2.0, 5.0, true, 20.0, 1.0),
        listOf(
            Allocation(KrakenSymbols.USD, 10.0),
            Allocation(KrakenSymbols.BTC, 50.0),
            Allocation(KrakenSymbols.ETH, 40.0)
        )
    )

    "renderDashboardShell_containsExpectedContent" {
        val html = createHTML().html {
            with(view) {
                renderDashboardShell()
            }
        }
        html shouldContain "title>${ViewText.APP_TITLE}"
        html shouldContain "link href=\"${Routes.STATIC_STYLE_CSS}\" rel=\"stylesheet\""
        html shouldContain "script src=\"https://unpkg.com/htmx.org@2.0.4\""
        html shouldContain "hx-ext=\"sse\""
        html shouldContain "sse-connect=\"${Routes.API_STATUS_STREAM}\""
        html shouldContain ViewText.CONNECTING
    }

    "renderSettingsPage_withNoError_containsForm" {
        val html = createHTML().html {
            with(view) {
                renderSettingsPage(baseConfig, null)
            }
        }
        html shouldContain "title>${ViewText.SETTINGS_TITLE} - ${ViewText.APP_TITLE}"
        html shouldContain "name=\"${FormFields.LOOP_DELAY_SECONDS}\""
        html shouldContain "value=\"60\""
        html shouldContain "name=\"${FormFields.DEVIATION_TRIGGER_PERCENT}\""
        html shouldContain "value=\"2.0\""
        html shouldNotContain CssClasses.ERROR_BANNER
    }

    "renderSettingsPage_withError_displaysError" {
        val errMsg = "Invalid configuration: must sum to 100%"
        val html = createHTML().html {
            with(view) {
                renderSettingsPage(baseConfig, errMsg)
            }
        }
        html shouldContain errMsg
        html shouldContain CssClasses.ERROR_BANNER
    }

    "renderDashboardFragment_withLiveSnapshotAndHistory_rendersCorrectly" {
        val now = Instant.now()
        val latest = PortfolioSnapshot(
            timestamp = now,
            totalValueUSD = BigDecimal("10000.00"),
            assets = mapOf(
                KrakenSymbols.USD to PortfolioSnapshot.AssetSnapshot(KrakenSymbols.USD, BigDecimal("1000.0"), BigDecimal("1.0"), BigDecimal("1000.0"), BigDecimal("10.0"), BigDecimal("10.0"), BigDecimal("0.0"), BigDecimal("0.0")),
                KrakenSymbols.BTC to PortfolioSnapshot.AssetSnapshot(KrakenSymbols.BTC, BigDecimal("0.1"), BigDecimal("50000.0"), BigDecimal("5000.0"), BigDecimal("50.0"), BigDecimal("50.0"), BigDecimal("5.0"), BigDecimal("250.0")),
                KrakenSymbols.ETH to PortfolioSnapshot.AssetSnapshot(KrakenSymbols.ETH, BigDecimal("2.0"), BigDecimal("2000.0"), BigDecimal("4000.0"), BigDecimal("40.0"), BigDecimal("40.0"), BigDecimal("-2.5"), BigDecimal("-100.0"))
            ),
            actions = listOf("BUY BTC Volume: 0.05 Value: $2500.0", "SELL ETH Volume: 1.0 Value: $2000.0"),
            drawdownPercent = BigDecimal("5.0"),
            fiatDeploymentPercent = BigDecimal("25.0"),
            effectiveUsdTargetPercent = BigDecimal("7.5")
        )

        val history = listOf(latest)

        val html = createHTML().div {
            with(view) {
                renderDashboardFragment(latest, history)
            }
        }

        html shouldContain ViewText.LIVE
        html shouldNotContain ViewText.DELAYED
        html shouldContain ViewText.TOTAL_PORTFOLIO
        html shouldContain "$10,000.00"
        html shouldContain "${ViewText.DRAWDOWN_PREFIX}5.00%"
        html shouldContain ViewText.CASH_USD
        html shouldContain "$1,000.00"
        html shouldContain "10.00% | ${ViewText.TARGET_PREFIX}7.50% (${ViewText.BASE_PREFIX}10.00%)"
        html shouldContain "${ViewText.DEV_PREFIX}0.00%"
        html shouldContain ViewText.CRYPTO_ASSETS
        html shouldContain "$9,000.00"
        html shouldContain "90.00% | ${ViewText.TARGET_PREFIX}90.00% | 2${ViewText.ASSETS_SUFFIX}"
        
        // Allocation bars
        html shouldContain "${CssClasses.ALLOCATION_BAR_LABEL}\">BTC"
        html shouldContain "${CssClasses.ALLOCATION_BAR_LABEL}\">ETH"
        
        // Recent activity badges
        html shouldContain "${CssClasses.BADGE_BUY}\">BUY"
        html shouldContain "${CssClasses.BADGE_SELL}\">SELL"
    }

    "renderDashboardFragment_withStaleData_rendersDelayedBadge" {
        val oldTime = Instant.now().minusSeconds(100)
        val latest = PortfolioSnapshot(
            timestamp = oldTime,
            totalValueUSD = BigDecimal("1000.00"),
            assets = mapOf(
                KrakenSymbols.USD to PortfolioSnapshot.AssetSnapshot(KrakenSymbols.USD, BigDecimal("1000.0"), BigDecimal("1.0"), BigDecimal("1000.0"), BigDecimal("100.0"), BigDecimal("100.0"), BigDecimal("0.0"), BigDecimal("0.0"))
            ),
            actions = emptyList(),
            drawdownPercent = BigDecimal.ZERO,
            fiatDeploymentPercent = BigDecimal.ZERO,
            effectiveUsdTargetPercent = BigDecimal("100.0")
        )

        val html = createHTML().div {
            with(view) {
                renderDashboardFragment(latest, emptyList())
            }
        }

        html shouldContain ViewText.DELAYED
        html shouldNotContain ViewText.LIVE
        html shouldContain ViewText.NO_TRADING_HISTORY
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
            with(view) {
                renderDashboardFragment(emptyAssetsLatest, emptyList())
            }
        }

        val latest = PortfolioSnapshot(
            timestamp = now,
            totalValueUSD = BigDecimal.ZERO,
            assets = mapOf(
                KrakenSymbols.BTC to PortfolioSnapshot.AssetSnapshot(KrakenSymbols.BTC, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)
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
            with(view) {
                renderDashboardFragment(latest, listOf(latest, noActionsSnapshot))
            }
        }

        html shouldContain ViewText.NO_USD_DATA
        html shouldContain "${ViewText.DRAWDOWN_PREFIX}0.00%"
        html shouldContain "${CssClasses.BADGE_INFO}\">INFO"
        html shouldContain ViewText.NO_TRADES_EXECUTED
    }

    "renderDashboardFragment_usdTargetEqual_doesNotPrintBaseTarget" {
        val now = Instant.now()
        val latest = PortfolioSnapshot(
            timestamp = now,
            totalValueUSD = BigDecimal("1000.00"),
            assets = mapOf(
                KrakenSymbols.USD to PortfolioSnapshot.AssetSnapshot(KrakenSymbols.USD, BigDecimal("100.0"), BigDecimal("1.0"), BigDecimal("100.0"), BigDecimal("10.0"), BigDecimal("10.0"), BigDecimal("0.0"), BigDecimal("0.0"))
            ),
            actions = emptyList(),
            drawdownPercent = BigDecimal.ZERO,
            fiatDeploymentPercent = BigDecimal.ZERO,
            effectiveUsdTargetPercent = BigDecimal("10.0") // Equal to targetPercent
        )

        val html = createHTML().div {
            with(view) {
                renderDashboardFragment(latest, emptyList())
            }
        }

        html shouldContain "10.00% | ${ViewText.TARGET_PREFIX}10.00%"
        html shouldNotContain "(Base: 10.00%)"
    }
})
