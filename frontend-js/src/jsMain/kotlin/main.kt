package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.view.util.HtmlEvents
import com.gemini.krakenbot.view.util.HtmlIds
import com.gemini.krakenbot.view.util.HtmlQueries
import kotlinx.browser.document
import kotlinx.browser.window

/**
 * The one error status this app renders a swap for. Settings rejections answer with a complete
 * form fragment plus a `ViewText` message, so discarding the body would hide the only feedback
 * the operator gets. Every other error status keeps htmx's default handling.
 */
private const val HTTP_UNPROCESSABLE_ENTITY = 422

/**
 * Whether an error response should be swapped in despite its status.
 *
 * Admitted: this app's own validation rejections ([HTTP_UNPROCESSABLE_ENTITY]) that actually carry
 * a body to render. Rejected: an empty body, and every other error status — a genuine 5xx is a
 * fault, not a message for the operator, and swapping it would put raw server output on the page.
 */
internal fun shouldSwapRejection(status: Int?, responseText: String?): Boolean =
    status == HTTP_UNPROCESSABLE_ENTITY && !responseText.isNullOrBlank()

/**
 * Brings a freshly rendered form rejection into view.
 *
 * A rejection swaps the whole form, but its trigger can sit far down the page — the allocation
 * preview button is at the bottom — so a banner rendered at the top would land above the fold with
 * no indication anything happened. Scoping to a banner that is already rendered means ordinary
 * swaps never move the page.
 */
private fun revealFormError() {
    document.querySelector(HtmlQueries.FORM_ERROR_BANNER)?.scrollIntoView()
}

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
        revealFormError()
    })

    // A rejection belongs to the attempt that produced it, so a new submission clears the previous
    // message before the request goes out. Doing it here rather than on the response means a
    // successful swap cannot leave a stale banner above freshly rendered content.
    document.addEventListener(HtmlEvents.HTMX_BEFORE_REQUEST, {
        document.querySelector(HtmlQueries.FORM_ERROR_BANNER)?.remove()
    })

    // htmx refuses to swap a non-2xx response, so a 422 that already carries a fully rendered
    // settings form — the shape every settings rejection returns — would be dropped and the
    // operator would see nothing at all after submitting invalid input. htmx always supplies
    // detail.xhr on beforeSwap, so this reads it directly.
    document.addEventListener(HtmlEvents.HTMX_BEFORE_SWAP, { event ->
        val detail = event.asDynamic().detail
        val xhr = detail.xhr
        if (shouldSwapRejection(xhr.status as? Int, xhr.responseText as? String)) {
            detail.shouldSwap = true
            detail.isError = false
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
