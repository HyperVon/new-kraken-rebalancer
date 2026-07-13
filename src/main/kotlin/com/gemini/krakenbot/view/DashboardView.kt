package com.gemini.krakenbot.view

import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.view.component.DashboardFragmentComponent
import com.gemini.krakenbot.view.component.DashboardShellComponent
import com.gemini.krakenbot.view.component.HistoryPageComponent
import com.gemini.krakenbot.view.component.SettingsFormComponent
import com.gemini.krakenbot.view.util.Routes.STATIC_STYLE_CSS
import com.gemini.krakenbot.view.util.ViewText.APP_TITLE
import com.gemini.krakenbot.view.util.ViewText.SETTINGS_TITLE
import kotlinx.html.*

class DashboardView(
    private val shellComponent: DashboardShellComponent,
    private val settingsFormComponent: SettingsFormComponent,
    private val fragmentComponent: DashboardFragmentComponent,
    private val historyPageComponent: HistoryPageComponent
) {

    context(html: HTML)
    fun renderDashboardShell() {
        shellComponent.render()
    }

    context(html: HTML)
    fun renderSettingsPage(config: AppConfig, errorMessage: String?) {
        html.head {
            meta(charset = "utf-8")
            meta(
                name = "viewport",
                content = "width=device-width, initial-scale=1.0"
            )
            title("$SETTINGS_TITLE - $APP_TITLE")
            link(rel = "preconnect", href = "https://fonts.googleapis.com")
            link(rel = "preconnect", href = "https://fonts.gstatic.com") {
                attributes["crossorigin"] = ""
            }
            link(
                rel = "stylesheet",
                href = "https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700;800&family=Outfit:wght@400;500;600;700;800&family=Roboto+Mono:wght@400;500;700&display=swap"
            )
            link(rel = "stylesheet", href = STATIC_STYLE_CSS)
            script(src = "https://unpkg.com/htmx.org@2.0.4") {}
        }
        html.body {
            settingsFormComponent.render(config, errorMessage)
        }
    }

    context(html: HTML)
    fun renderHistoryPage() {
        historyPageComponent.render()
    }

    context(div: DIV)
    fun renderDashboardFragment(
        latest: PortfolioSnapshot,
        history: List<PortfolioSnapshot>
    ) {
        fragmentComponent.render(latest, history)
    }
}


