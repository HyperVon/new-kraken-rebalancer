package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.view.util.CssClass
import org.w3c.dom.DOMTokenList

/** Type-safe DOMTokenList extension functions for CssClass sealed class hierarchies. */
fun DOMTokenList.add(cssClass: CssClass) {
    add(cssClass.value)
}

fun DOMTokenList.remove(vararg cssClasses: CssClass) {
    val names = cssClasses.map { it.value }.toTypedArray()
    remove(*names)
}

fun DOMTokenList.toggle(cssClass: CssClass, force: Boolean? = null): Boolean {
    return if (force != null) toggle(cssClass.value, force) else toggle(cssClass.value)
}

fun DOMTokenList.contains(cssClass: CssClass): Boolean {
    return contains(cssClass.value)
}
