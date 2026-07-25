package com.gemini.krakenbot.view.component

import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.HtmxAttrs
import com.gemini.krakenbot.view.util.HtmxValues
import com.gemini.krakenbot.view.util.Routes
import com.gemini.krakenbot.view.util.ViewText
import com.gemini.krakenbot.view.util.commonMetadataAndStyles
import com.gemini.krakenbot.view.util.div
import com.gemini.krakenbot.view.util.rebalancerJsSrc
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
            div(CssClass.Layout.Container) {
                div {
                    attributes[HtmxAttrs.HX_EXT] = HtmxValues.EXT_SSE
                    attributes[HtmxAttrs.SSE_CONNECT] = Routes.API_STATUS_STREAM

                    div {
                        attributes[HtmxAttrs.HX_GET] = Routes.FRAGMENT_DASHBOARD
                        attributes[HtmxAttrs.HX_TRIGGER] = HtmxValues.TRIGGER_LOAD_SSE_MESSAGE

                        div(CssClass.Loading.SpinnerContainer) {
                            div(CssClass.Loading.Spinner) {}
                            p { +ViewText.CONNECTING }
                        }
                    }
                }
            }
            script(src = rebalancerJsSrc()) {}
        }
    }
}
