package com.gemini.krakenbot.view.component

import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.HtmxAttrs
import com.gemini.krakenbot.view.util.Routes
import com.gemini.krakenbot.view.util.ViewText
import com.gemini.krakenbot.view.util.commonMetadataAndStyles
import com.gemini.krakenbot.view.util.div
import kotlinx.html.*

class DashboardShellComponent {

    context(html: HTML)
    fun render() {
        html.head {
            commonMetadataAndStyles()
            title(ViewText.APP_TITLE)
            script(src = Routes.CDN_HTMX) {}
            script(src = Routes.CDN_HTMX_SSE) {}
        }
        html.body {
            div(CssClass.Layout.Container) {
                div {
                    attributes[HtmxAttrs.HX_EXT] = "sse"
                    attributes[HtmxAttrs.SSE_CONNECT] = Routes.API_STATUS_STREAM

                    div {
                        attributes[HtmxAttrs.HX_GET] = Routes.FRAGMENT_DASHBOARD
                        attributes[HtmxAttrs.HX_TRIGGER] = "load, sse:message"

                        div(CssClass.Loading.SpinnerContainer) {
                            div(CssClass.Loading.Spinner) {}
                            p { +ViewText.CONNECTING }
                        }
                    }
                }
            }
            script(src = Routes.STATIC_REBALANCER_JS) {}
        }
    }
}
