package com.gemini.krakenbot.view.util

import com.gemini.krakenbot.view.util.CssClasses
import com.gemini.krakenbot.view.util.Icons.icon
import kotlinx.html.*

object Layouts {
    fun FlowContent.glassPanel(title: String, iconSvg: String? = null, block: DIV.() -> Unit) {
        div(CssClasses.GLASS_PANEL) {
            h2("glass-panel-title") {
                if (iconSvg != null) icon(iconSvg)
                +title
            }
            block()
        }
    }

    fun FlowContent.statusCard(title: String, iconSvg: String, value: String, isSuccess: Boolean = false, block: DIV.() -> Unit) {
        val cardClass = if (isSuccess) "glass-panel status-card success" else "glass-panel status-card"
        div(cardClass) {
            div("status-card-header") {
                span("status-card-title") { +title }
                div("status-card-icon") { icon(iconSvg) }
            }
            div("status-card-value") { +value }
            div("status-card-sub") { block() }
        }
    }

    fun DIV.formSection(title: String, iconSvg: String, block: DIV.() -> Unit) {
        div("form-section") {
            h3("form-section-title") {
                icon(iconSvg)
                +title
            }
            block()
        }
    }

    fun DIV.formGroup(label: String, block: DIV.() -> Unit) {
        div("form-group") {
            label(classes = "form-label") { +label }
            block()
        }
    }
}
