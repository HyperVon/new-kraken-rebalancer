package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.view.util.HtmlEvents
import com.gemini.krakenbot.view.util.HtmlIds
import kotlinx.browser.document
import kotlinx.browser.window

fun main() {
    // 1. Unconditionally register global functions and variables
    registerDashboardGlobals()
    registerSettingsGlobals()
    registerHistoryGlobals()

    // 2. Unconditionally register global HTMX event listener
    document.addEventListener(HtmlEvents.HTMX_AFTER_SWAP, {
        updateAge()
        reapplySort()
    })

    // 3. Tick the freshness chip every second so STREAM→STALE is detected client-side even
    //    when no new SSE snapshot arrives to trigger htmx:afterSwap.
    window.setInterval({ updateAge() }, 1000)

    // 4. Run initial page load checks
    if (document.body != null) {
        initOnLoad()
    } else {
        document.addEventListener(HtmlEvents.DOM_CONTENT_LOADED, {
            initOnLoad()
        })
    }
}

fun initOnLoad() {
    // Initial dashboard checks
    updateAge()
    reapplySort()

    // Settings page initialization
    if (document.getElementById(HtmlIds.TOTAL_ALLOCATED_DISPLAY) != null) {
        initSettings()
    }

    // History page initialization
    if (document.getElementById(HtmlIds.PORTFOLIO_VALUE_CHART) != null) {
        initHistory()
    }
}
