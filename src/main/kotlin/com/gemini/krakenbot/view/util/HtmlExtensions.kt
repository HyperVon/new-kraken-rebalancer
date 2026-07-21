package com.gemini.krakenbot.view.util

import kotlinx.html.*

/** Type-safe kotlinx.html DSL extension functions accepting CssClass instances directly. */
inline fun FlowContent.div(cssClass: CssClass, crossinline block: DIV.() -> Unit = {}): Unit =
    div(classes = cssClass.value, block = block)

inline fun FlowContent.span(cssClass: CssClass, crossinline block: SPAN.() -> Unit = {}): Unit =
    span(classes = cssClass.value, block = block)

inline fun FlowContent.button(cssClass: CssClass, type: ButtonType? = null, crossinline block: BUTTON.() -> Unit = {}): Unit =
    button(classes = cssClass.value, type = type, block = block)

inline fun FlowContent.a(cssClass: CssClass, href: String? = null, target: String? = null, crossinline block: A.() -> Unit = {}): Unit =
    a(classes = cssClass.value, href = href, target = target, block = block)

inline fun FlowContent.h1(cssClass: CssClass, crossinline block: H1.() -> Unit = {}): Unit =
    h1(classes = cssClass.value, block = block)

inline fun FlowContent.h2(cssClass: CssClass, crossinline block: H2.() -> Unit = {}): Unit =
    h2(classes = cssClass.value, block = block)

inline fun FlowContent.h3(cssClass: CssClass, crossinline block: H3.() -> Unit = {}): Unit =
    h3(classes = cssClass.value, block = block)

inline fun FlowContent.p(cssClass: CssClass, crossinline block: P.() -> Unit = {}): Unit =
    p(classes = cssClass.value, block = block)

inline fun TR.th(cssClass: CssClass, scope: ThScope? = null, crossinline block: TH.() -> Unit = {}): Unit =
    th(classes = cssClass.value, scope = scope, block = block)

inline fun TABLE.tr(cssClass: CssClass, crossinline block: TR.() -> Unit = {}): Unit =
    tr(classes = cssClass.value, block = block)

inline fun TBODY.tr(cssClass: CssClass, crossinline block: TR.() -> Unit = {}): Unit =
    tr(classes = cssClass.value, block = block)

inline fun THEAD.tr(cssClass: CssClass, crossinline block: TR.() -> Unit = {}): Unit =
    tr(classes = cssClass.value, block = block)

inline fun FlowContent.table(cssClass: CssClass, crossinline block: TABLE.() -> Unit = {}): Unit =
    table(classes = cssClass.value, block = block)
