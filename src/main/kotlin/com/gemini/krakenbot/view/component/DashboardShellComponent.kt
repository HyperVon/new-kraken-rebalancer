package com.gemini.krakenbot.view.component

import com.gemini.krakenbot.view.util.CssClasses
import com.gemini.krakenbot.view.util.HtmxAttrs
import com.gemini.krakenbot.view.util.Icons
import com.gemini.krakenbot.view.util.Icons.icon
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
            link(rel = "stylesheet", href = Routes.STATIC_STYLE_CSS)
            script(src = "https://unpkg.com/htmx.org@2.0.4") {}
            script(src = "https://unpkg.com/htmx-ext-sse@2.2.2/sse.js") {}
        }
        html.body {
            div(CssClasses.CONTAINER) {
                div {
                    attributes[HtmxAttrs.HX_EXT] = "sse"
                    attributes[HtmxAttrs.SSE_CONNECT] = Routes.API_STATUS_STREAM

                    div {
                        attributes[HtmxAttrs.HX_GET] = Routes.FRAGMENT_DASHBOARD
                        attributes[HtmxAttrs.HX_TRIGGER] = "load, sse:message"

                        div(CssClasses.SPINNER_CONTAINER) {
                            div(CssClasses.SPINNER) {}
                            p { +ViewText.CONNECTING }
                        }
                    }
                }
            }
            script(src = Routes.STATIC_DASHBOARD_JS) {}
        }
    }
}
