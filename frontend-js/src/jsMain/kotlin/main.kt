package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.view.util.HtmlEvents
import com.gemini.krakenbot.view.util.HtmlIds
import kotlinx.browser.document
import kotlinx.browser.window

fun main() {
    registerDashboardGlobals()
    registerSettingsGlobals()
    registerHistoryGlobals()

    document.addEventListener(HtmlEvents.HTMX_AFTER_SWAP, {
        updateAge()
        reapplySort()
        if (document.getElementById(HtmlIds.TOTAL_ALLOCATED_DISPLAY) != null) {
            initSettings()
        }
    })

    // Tick the freshness chip so STREAM→STALE is detected even when no SSE snapshot triggers htmx:afterSwap.
    window.setInterval({ updateAge() }, 1000)

    if (document.body != null) {
        initOnLoad()
    } else {
        document.addEventListener(HtmlEvents.DOM_CONTENT_LOADED, {
            initOnLoad()
        })
    }
}

fun initOnLoad() {
    updateAge()
    reapplySort()

    if (document.getElementById(HtmlIds.TOTAL_ALLOCATED_DISPLAY) != null) {
        initSettings()
    }

    if (document.getElementById(HtmlIds.PORTFOLIO_VALUE_CHART) != null) {
        initHistory()
    }
}
