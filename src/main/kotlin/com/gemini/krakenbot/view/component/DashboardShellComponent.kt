package com.gemini.krakenbot.view.component

import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.view.util.ActiveNav
import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.HtmxAttrs
import com.gemini.krakenbot.view.util.HtmxValues
import com.gemini.krakenbot.view.util.Routes
import com.gemini.krakenbot.view.util.ViewText
import com.gemini.krakenbot.view.util.brandWithMode
import com.gemini.krakenbot.view.util.commonMetadataAndStyles
import com.gemini.krakenbot.view.util.div
import com.gemini.krakenbot.view.util.primaryNav
import com.gemini.krakenbot.view.util.rebalancerJsSrc
import kotlinx.html.*

class DashboardShellComponent {

    context(html: HTML)
    fun render(settings: Settings) {
        html.head {
            commonMetadataAndStyles()
            title(ViewText.APP_TITLE)
            script(src = "https://unpkg.com/htmx.org@2.0.4") {}
            script(src = "https://unpkg.com/htmx-ext-sse@2.2.2/sse.js") {}
        }
        html.body {
            div(CssClass.Layout.Container) {
                // Mode plate + nav live outside the HTMX fragment so the trading mode
                // is visible during the initial load (and if the fragment request fails).
                header {
                    brandWithMode(settings)
                    primaryNav(ActiveNav.DASHBOARD)
                }
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
