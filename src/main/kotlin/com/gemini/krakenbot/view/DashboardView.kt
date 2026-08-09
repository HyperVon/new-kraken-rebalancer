package com.gemini.krakenbot.view

import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.view.component.DashboardFragmentComponent
import com.gemini.krakenbot.view.component.DashboardShellComponent
import com.gemini.krakenbot.view.component.HistoryPageComponent
import com.gemini.krakenbot.view.component.SettingsFormComponent
import com.gemini.krakenbot.view.util.CdnIntegrity
import com.gemini.krakenbot.view.util.CdnUrls
import com.gemini.krakenbot.view.util.ViewText.APP_TITLE
import com.gemini.krakenbot.view.util.ViewText.SETTINGS_TITLE
import com.gemini.krakenbot.view.util.cdnScript
import com.gemini.krakenbot.view.util.commonMetadataAndStyles
import kotlinx.html.DIV
import kotlinx.html.FlowContent
import kotlinx.html.HTML
import kotlinx.html.body
import kotlinx.html.head
import kotlinx.html.script
import kotlinx.html.title
import java.math.BigDecimal

class DashboardView(
    private val shellComponent: DashboardShellComponent,
    private val settingsFormComponent: SettingsFormComponent,
    private val fragmentComponent: DashboardFragmentComponent,
    private val historyPageComponent: HistoryPageComponent,
) {

    context(html: HTML)
    fun renderDashboardShell(settings: Settings, csrfToken: String? = null, paused: Boolean = false) {
        shellComponent.render(settings, csrfToken, paused)
    }

    context(html: HTML)
    fun renderSettingsPage(config: AppConfig, errorMessage: String?, csrfToken: String, paused: Boolean = false) {
        html.head {
            commonMetadataAndStyles()
            title("$SETTINGS_TITLE - $APP_TITLE")
            cdnScript(CdnUrls.HTMX, CdnIntegrity.HTMX)
        }
        html.body {
            settingsFormComponent.render(config, errorMessage, csrfToken, paused)
        }
    }

    fun renderSettingsFormFragment(
        parent: FlowContent,
        config: AppConfig,
        errorMessage: String?,
        csrfToken: String,
        paused: Boolean = false,
    ) {
        settingsFormComponent.renderForm(parent, config, errorMessage, csrfToken, paused)
    }

    context(html: HTML)
    fun renderHistoryPage(
        settings: Settings,
        symbolColorMap: Map<String, String> = emptyMap(),
        csrfToken: String? = null,
        paused: Boolean = false,
    ) {
        historyPageComponent.render(settings, symbolColorMap, csrfToken, paused)
    }

    context(div: DIV)
    fun renderDashboardFragment(
        latest: PortfolioSnapshot,
        history: List<PortfolioSnapshot>,
        allocations: List<Allocation> = emptyList(),
        delta24h: BigDecimal? = null,
    ) {
        fragmentComponent.render(latest, history, allocations, delta24h)
    }
}
