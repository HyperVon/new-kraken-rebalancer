package com.gemini.krakenbot.view.component

import com.gemini.krakenbot.view.util.CssClasses.CONTAINER
import com.gemini.krakenbot.view.util.CssClasses.SPINNER
import com.gemini.krakenbot.view.util.CssClasses.SPINNER_CONTAINER
import com.gemini.krakenbot.view.util.HtmxAttrs.HX_EXT
import com.gemini.krakenbot.view.util.HtmxAttrs.HX_GET
import com.gemini.krakenbot.view.util.HtmxAttrs.HX_TRIGGER
import com.gemini.krakenbot.view.util.HtmxAttrs.SSE_CONNECT
import com.gemini.krakenbot.view.util.Routes.API_STATUS_STREAM
import com.gemini.krakenbot.view.util.Routes.FRAGMENT_DASHBOARD
import com.gemini.krakenbot.view.util.Routes.STATIC_DASHBOARD_JS
import com.gemini.krakenbot.view.util.Routes.STATIC_STYLE_CSS
import com.gemini.krakenbot.view.util.ViewText.APP_TITLE
import com.gemini.krakenbot.view.util.ViewText.CONNECTING
import kotlinx.html.*

class DashboardShellComponent {

    fun HTML.render() {
        head {
            meta(charset = "utf-8")
            meta(
                name = "viewport",
                content = "width=device-width, initial-scale=1.0"
            )
            title(APP_TITLE)
            link(rel = "stylesheet", href = STATIC_STYLE_CSS)
            script(src = "https://unpkg.com/htmx.org@2.0.4") {}
            script(src = "https://unpkg.com/htmx-ext-sse@2.2.2/sse.js") {}
        }
        body {
            div(CONTAINER) {
                div {
                    attributes[HX_EXT] = "sse"
                    attributes[SSE_CONNECT] = API_STATUS_STREAM

                    div {
                        attributes[HX_GET] = FRAGMENT_DASHBOARD
                        attributes[HX_TRIGGER] = "load, sse:message"

                        div(SPINNER_CONTAINER) {
                            div(SPINNER) {}
                            p { +CONNECTING }
                        }
                    }
                }
            }
            script(src = STATIC_DASHBOARD_JS) {}
        }
    }
}
