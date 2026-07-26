package com.gemini.krakenbot.view

import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.HtmxAttrs
import com.gemini.krakenbot.view.util.HtmxValues
import com.gemini.krakenbot.view.util.Routes
import com.gemini.krakenbot.view.util.ViewText
import com.gemini.krakenbot.view.util.div
import kotlinx.html.BODY
import kotlinx.html.p

/**
 * Minimal kotlinx.html example using real `:common` symbols and the project's
 * `div(CssClass)` helper — not production dashboard markup.
 */
fun BODY.renderConnectingPlaceholder() {
    div {
        attributes[HtmxAttrs.HX_GET] = Routes.FRAGMENT_DASHBOARD
        attributes[HtmxAttrs.HX_TRIGGER] = HtmxValues.TRIGGER_LOAD_SSE_MESSAGE
        div(CssClass.Loading.SpinnerContainer) {
            div(CssClass.Loading.Spinner) {}
            p { +ViewText.CONNECTING }
        }
    }
}
