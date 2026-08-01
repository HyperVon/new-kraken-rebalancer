package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.HtmlAttrs
import com.gemini.krakenbot.view.util.HtmlTags
import kotlinx.browser.document
import org.w3c.dom.*
import com.gemini.krakenbot.view.util.CssClass.Query.CHART_SCRUBBERS as CHART_SCRUBBERS_QUERY

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

/** Type-safe Document element creation extension functions using HtmlTags. */
fun Document.createDiv(): HTMLDivElement = createElement(HtmlTags.DIV) as HTMLDivElement

fun Document.createSpan(): HTMLSpanElement = createElement(HtmlTags.SPAN) as HTMLSpanElement

fun Document.createInput(): HTMLInputElement = createElement(HtmlTags.INPUT) as HTMLInputElement

fun Document.createButton(): HTMLButtonElement = createElement(HtmlTags.BUTTON) as HTMLButtonElement

fun Document.createLabel(): HTMLLabelElement = createElement(HtmlTags.LABEL) as HTMLLabelElement

/** Locate a history chart's scrubber input by `data-chart-id`. */
fun queryChartScrubber(canvasId: String): HTMLInputElement? =
    document.querySelector("$CHART_SCRUBBERS_QUERY[${HtmlAttrs.DATA_CHART_ID}=\"$canvasId\"]") as? HTMLInputElement
