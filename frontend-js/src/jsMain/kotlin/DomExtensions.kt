package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.HtmlTags
import org.w3c.dom.*

/** Type-safe DOMTokenList extension functions for CssClass sealed class hierarchies. */
fun DOMTokenList.add(cssClass: CssClass) {
    add(cssClass.value)
}

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

/** Safely checks if a dynamic JS value is boolean true or string "true". */
fun isTrue(value: dynamic): Boolean {
    if (value == null || value == undefined) return false
    if (value == true) return true
    return value.toString().lowercase() == "true"
}

/** Type-safe Document element creation extension functions using HtmlTags. */
fun Document.createDiv(): HTMLDivElement = createElement(HtmlTags.DIV) as HTMLDivElement

fun Document.createSpan(): HTMLSpanElement = createElement(HtmlTags.SPAN) as HTMLSpanElement

fun Document.createInput(): HTMLInputElement = createElement(HtmlTags.INPUT) as HTMLInputElement

fun Document.createButton(): HTMLButtonElement = createElement(HtmlTags.BUTTON) as HTMLButtonElement

fun Document.createLabel(): HTMLLabelElement = createElement(HtmlTags.LABEL) as HTMLLabelElement
