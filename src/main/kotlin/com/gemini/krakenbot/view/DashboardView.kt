package com.gemini.krakenbot.view

import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.view.component.DashboardFragmentComponent
import com.gemini.krakenbot.view.component.DashboardShellComponent
import com.gemini.krakenbot.view.component.HistoryPageComponent
import com.gemini.krakenbot.view.component.SettingsFormComponent
import com.gemini.krakenbot.view.util.ViewText.APP_TITLE
import com.gemini.krakenbot.view.util.ViewText.SETTINGS_TITLE
import com.gemini.krakenbot.view.util.commonMetadataAndStyles
import kotlinx.html.*

class DashboardView(
    private val shellComponent: DashboardShellComponent,
    private val settingsFormComponent: SettingsFormComponent,
    private val fragmentComponent: DashboardFragmentComponent,
    private val historyPageComponent: HistoryPageComponent,
) {

    context(html: HTML)
    fun renderDashboardShell(settings: Settings) {
        shellComponent.render(settings)
    }

    context(html: HTML)
    fun renderSettingsPage(config: AppConfig, errorMessage: String?) {
        html.head {
            commonMetadataAndStyles()
            title("$SETTINGS_TITLE - $APP_TITLE")
            script(src = "https://unpkg.com/htmx.org@2.0.4") {}
        }
        html.body {
            settingsFormComponent.render(config, errorMessage)
        }
    }

    fun renderSettingsFormFragment(parent: FlowContent, config: AppConfig, errorMessage: String?) {
        settingsFormComponent.renderForm(parent, config, errorMessage)
    }

    context(html: HTML)
    fun renderHistoryPage(settings: Settings) {
        historyPageComponent.render(settings)
    }

    context(div: DIV)
    fun renderDashboardFragment(latest: PortfolioSnapshot, history: List<PortfolioSnapshot>) {
        fragmentComponent.render(latest, history)
    }
}
