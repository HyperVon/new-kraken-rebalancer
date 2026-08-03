package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.HtmlAttrs
import kotlinx.browser.document
import org.w3c.dom.*
import com.gemini.krakenbot.view.util.HtmlQueries.CHART_SCRUBBERS as CHART_SCRUBBERS_QUERY

/** Type-safe DOMTokenList extension functions for CssClass sealed class hierarchies. */
fun DOMTokenList.remove(vararg cssClasses: CssClass) {
    val names = cssClasses.map { it.value }.toTypedArray()
    remove(*names)
}

fun DOMTokenList.toggle(cssClass: CssClass, force: Boolean? = null): Boolean = if (force !=
    null
) {
    toggle(cssClass.value, force)
} else {
    toggle(cssClass.value)
}

fun DOMTokenList.contains(cssClass: CssClass): Boolean = contains(cssClass.value)

/** Locate a history chart's scrubber input by `data-chart-id`. */
fun queryChartScrubber(canvasId: String): HTMLInputElement? =
    document.querySelector("$CHART_SCRUBBERS_QUERY[${HtmlAttrs.DATA_CHART_ID}=\"$canvasId\"]") as? HTMLInputElement
