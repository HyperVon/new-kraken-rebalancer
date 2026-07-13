package com.gemini.krakenbot.view.component

import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.HtmxAttrs
import com.gemini.krakenbot.view.util.Routes
import com.gemini.krakenbot.view.util.ViewText
import kotlinx.html.*

class DashboardShellComponent {

    context(html: HTML)
    fun render() {
        html.head {
            meta(charset = "utf-8")
            meta(
                name = "viewport",
                content = "width=device-width, initial-scale=1.0"
            )
            title(ViewText.APP_TITLE)
            link(rel = "preconnect", href = "https://fonts.googleapis.com")
            link(rel = "preconnect", href = "https://fonts.gstatic.com") {
                attributes["crossorigin"] = ""
            }
            link(
                rel = "stylesheet",
                href = "https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700;800&family=Outfit:wght@400;500;600;700;800&family=Roboto+Mono:wght@400;500;700&display=swap"
            )
            link(rel = "stylesheet", href = Routes.STATIC_STYLE_CSS)
            script(src = "https://unpkg.com/htmx.org@2.0.4") {}
            script(src = "https://unpkg.com/htmx-ext-sse@2.2.2/sse.js") {}
        }
        html.body {
            div(classes = CssClass.Layout.Container.value) {
                div {
                    attributes[HtmxAttrs.HX_EXT] = "sse"
                    attributes[HtmxAttrs.SSE_CONNECT] = Routes.API_STATUS_STREAM

                    div {
                        attributes[HtmxAttrs.HX_GET] = Routes.FRAGMENT_DASHBOARD
                        attributes[HtmxAttrs.HX_TRIGGER] = "load, sse:message"

                        div(classes = CssClass.Loading.SpinnerContainer.value) {
                            div(classes = CssClass.Loading.Spinner.value) {}
                            p { +ViewText.CONNECTING }
                        }
                    }
                }
            }
            script(src = Routes.STATIC_DASHBOARD_JS) {}
        }
    }
}
