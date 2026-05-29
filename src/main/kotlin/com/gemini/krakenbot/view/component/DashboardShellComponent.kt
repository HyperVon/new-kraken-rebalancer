package com.gemini.krakenbot.view.component

import com.gemini.krakenbot.view.util.ViewText
import kotlinx.html.*

class DashboardShellComponent {

    fun HTML.render() {
        head {
            meta(charset = "utf-8")
            meta(name = "viewport", content = "width=device-width, initial-scale=1.0")
            title(ViewText.APP_TITLE)
            link(rel = "stylesheet", href = "/static/style.css")
            script(src = "https://unpkg.com/htmx.org@2.0.4") {}
            script(src = "https://unpkg.com/htmx-ext-sse@2.2.2/sse.js") {}
        }
        body {
            div("container") {
                div {
                    attributes["hx-ext"] = "sse"
                    attributes["sse-connect"] = "/api/status/stream"

                    div {
                        attributes["hx-get"] = "/fragments/dashboard"
                        attributes["hx-trigger"] = "load, sse:message"

                        div("spinner-container") {
                            div("spinner") {}
                            p { +ViewText.CONNECTING }
                        }
                    }
                }
            }
            script(src = "/static/dashboard.js") {}
        }
    }
}
