package com.gemini.krakenbot.frontend

import kotlinx.browser.document
import kotlinx.browser.window

fun main() {
    // 1. Unconditionally register global functions and variables
    registerDashboardGlobals()
    registerSettingsGlobals()
    registerHistoryGlobals()

    // 2. Unconditionally register global HTMX event listener
    document.addEventListener("htmx:afterSwap", {
        updateAge()
        reapplySort()
    })

    // 3. Unconditionally registered age interval timer
    window.setInterval({ updateAge() }, 1000)

    // 4. Run initial page load checks
    if (document.body != null) {
        initOnLoad()
    } else {
        document.addEventListener("DOMContentLoaded", {
            initOnLoad()
        })
    }
}

fun initOnLoad() {
    // Initial dashboard checks
    updateAge()
    reapplySort()

    // Settings page initialization
    if (document.getElementById("total-allocated-display") != null) {
        initSettings()
    }

    // History page initialization
    if (document.getElementById("portfolio-value-chart") != null) {
        initHistory()
    }
}
