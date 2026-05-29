package com.gemini.krakenbot.view

import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.view.component.DashboardFragmentComponent
import com.gemini.krakenbot.view.component.DashboardShellComponent
import com.gemini.krakenbot.view.component.SettingsFormComponent
import com.gemini.krakenbot.view.util.Routes
import com.gemini.krakenbot.view.util.ViewText
import kotlinx.html.*

class DashboardView(
    private val shellComponent: DashboardShellComponent,
    private val settingsFormComponent: SettingsFormComponent,
    private val fragmentComponent: DashboardFragmentComponent
) {

    fun HTML.renderDashboardShell() {
        with(shellComponent) { render() }
    }

    fun HTML.renderSettingsPage(config: AppConfig, errorMessage: String?) {
        head {
            meta(charset = "utf-8")
            meta(
                name = "viewport",
                content = "width=device-width, initial-scale=1.0"
            )
            title("${ViewText.SETTINGS_TITLE} - ${ViewText.APP_TITLE}")
            link(rel = "stylesheet", href = Routes.STATIC_STYLE_CSS)
            script(src = "https://unpkg.com/htmx.org@2.0.4") {}
        }
        body {
            with(settingsFormComponent) { render(config, errorMessage) }
        }
    }

    fun DIV.renderDashboardFragment(
        latest: PortfolioSnapshot,
        history: List<PortfolioSnapshot>
    ) {
        with(fragmentComponent) { render(latest, history) }
    }
}

