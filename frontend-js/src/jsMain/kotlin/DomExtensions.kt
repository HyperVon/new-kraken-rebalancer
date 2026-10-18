package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.HtmlAttrs
import kotlinx.browser.document
import org.w3c.dom.*

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

/** Locate a history chart's scrubber input by its bound chart id. */
fun queryChartScrubber(chartId: String): HTMLInputElement? = document.querySelector(
    "${CssClass.History.ChartScrubberInput.querySelector}[${HtmlAttrs.DATA_CHART_ID}=\"$chartId\"]",
) as? HTMLInputElement
