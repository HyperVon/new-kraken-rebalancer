package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.view.util.HtmlEvents
import com.gemini.krakenbot.view.util.HtmlIds
import com.gemini.krakenbot.view.util.HtmlQueries
import com.gemini.krakenbot.view.util.Routes
import com.gemini.krakenbot.view.util.ViewText
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLInputElement

/**
 * Only server-marked HTML error fragments are eligible for an error swap: settings-form 403/422
 * responses replace a rejected or invalid form, and order-intent 4xx responses replace the
 * dedicated feedback slot without discarding the operator's form or dashboard.
 */
private const val HTTP_UNPROCESSABLE_ENTITY = 422
private const val ERROR_FRAGMENT_HEADER = "X-Rebalancer-Error-Fragment"
private const val CSRF_TOKEN_RESPONSE_HEADER = "X-Rebalancer-CSRF-Token"
private const val CSRF_SESSION_EXPIRED_HEADER = "X-Rebalancer-CSRF-Session-Expired"
private const val SETTINGS_FORM_ERROR_FRAGMENT = "settings-form"
private const val ORDER_INTENT_ERROR_FRAGMENT = "order-intent"
private const val HTML_CONTENT_TYPE = "text/html"

/** Whether a known, server-rendered operator error fragment should be swapped despite its status. */
internal fun shouldSwapRenderedError(
    status: Int?,
    responseText: String?,
    contentType: String?,
    fragmentKind: String?,
): Boolean {
    if (status == null || responseText.isNullOrBlank()) return false
    if (contentType?.substringBefore(';')?.trim()?.equals(HTML_CONTENT_TYPE, ignoreCase = true) != true) return false
    return when (fragmentKind) {
        SETTINGS_FORM_ERROR_FRAGMENT -> status == HTTP_UNPROCESSABLE_ENTITY || status == 403
        ORDER_INTENT_ERROR_FRAGMENT -> status in 400..499
        else -> false
    }
}

internal fun shouldClearSettingsError(verb: String?, path: String?): Boolean {
    if (!verb.equals("POST", ignoreCase = true)) return false
    val requestPath = path?.substringBefore('?')?.substringBefore('#') ?: return false
    return requestPath == Routes.SETTINGS || requestPath == Routes.FRAGMENT_SETTINGS_ALLOCATIONS_PREVIEW
}

/**
 * Brings a freshly rendered settings or intent error into view after its targeted swap.
 */
private fun revealFormError() {
    document.querySelector(HtmlQueries.FORM_ERROR_BANNER)?.scrollIntoView()
    document.getElementById(HtmlIds.ORDER_INTENT_FEEDBACK)
        ?.takeIf { !it.textContent.isNullOrBlank() }
        ?.scrollIntoView()
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

    // A form error belongs to the operator's latest settings mutation. Background proposal-slot
    // GETs must not clear it while they resolve in parallel with editing.
    document.addEventListener(HtmlEvents.HTMX_BEFORE_REQUEST, {
        val detail = it.asDynamic().detail
        val requestConfig = detail?.requestConfig
        if (requestConfig != null &&
            shouldClearSettingsError(requestConfig.verb as? String, requestConfig.path as? String)
        ) {
            document.querySelector(HtmlQueries.FORM_ERROR_BANNER)?.remove()
        }
    })

    // htmx refuses non-2xx responses by default. The server marker and HTML content type keep
    // rendered settings and order-intent messages visible without swapping JSON API errors.
    document.addEventListener(HtmlEvents.HTMX_BEFORE_SWAP, { event ->
        val detail = event.asDynamic().detail
        val xhr = detail.xhr
        val refreshedCsrfToken = xhr.getResponseHeader(CSRF_TOKEN_RESPONSE_HEADER) as? String
        if (!refreshedCsrfToken.isNullOrBlank()) {
            val csrfInputs = document.querySelectorAll(HtmlQueries.CSRF_TOKEN_FIELDS)
            repeat(csrfInputs.length) { index ->
                (csrfInputs.item(index) as? HTMLInputElement)?.value = refreshedCsrfToken
            }
        }
        val csrfSessionExpired = xhr.getResponseHeader(CSRF_SESSION_EXPIRED_HEADER) == "true"
        if (csrfSessionExpired) {
            window.alert(ViewText.CSRF_SESSION_EXPIRED)
            // Keep the open form's edited controls in place while the replacement token takes effect.
            detail.shouldSwap = false
        }
        val shouldSwap = shouldSwapRenderedError(
            status = xhr.status as? Int,
            responseText = xhr.responseText as? String,
            contentType = xhr.getResponseHeader("Content-Type") as? String,
            fragmentKind = xhr.getResponseHeader(ERROR_FRAGMENT_HEADER) as? String,
        )
        if (shouldSwap) {
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
