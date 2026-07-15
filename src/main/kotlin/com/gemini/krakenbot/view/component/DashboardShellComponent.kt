package com.gemini.krakenbot.view.component

import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.HtmxAttrs
import com.gemini.krakenbot.view.util.Routes
import com.gemini.krakenbot.view.util.ViewText
import com.gemini.krakenbot.view.util.commonMetadataAndStyles
import kotlinx.html.*

class DashboardShellComponent {

    context(html: HTML)
    fun render() {
        html.head {
            commonMetadataAndStyles()
            title(ViewText.APP_TITLE)
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
            script(src = Routes.STATIC_REBALANCER_JS) {}
        }
    }
}
