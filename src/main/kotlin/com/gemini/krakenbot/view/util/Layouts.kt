package com.gemini.krakenbot.view.util

import com.gemini.krakenbot.view.util.Icons.icon
import kotlinx.html.*

object Layouts {
    fun FlowContent.glassPanel(
        title: String,
        iconSvg: String? = null,
        block: DIV.() -> Unit
    ) {
        div(CssClasses.GLASS_PANEL) {
            h2(CssClasses.GLASS_PANEL_TITLE) {
                if (iconSvg != null) {
                    icon(iconSvg)
                }
                +title
            }
            block()
        }
    }

    fun FlowContent.statusCard(
        title: String,
        iconSvg: String,
        value: String,
        isSuccess: Boolean = false,
        block: DIV.() -> Unit
    ) {
        val cardClass =
            if (isSuccess) {
                CssClasses.STATUS_CARD_SUCCESS
            } else {
                CssClasses.STATUS_CARD
            }
        div(cardClass) {
            div(CssClasses.STATUS_CARD_HEADER) {
                span(CssClasses.STATUS_CARD_TITLE) { +title }
                div(CssClasses.STATUS_CARD_ICON) { icon(iconSvg) }
            }
            div(CssClasses.STATUS_CARD_VALUE) { +value }
            div(CssClasses.STATUS_CARD_SUB) { block() }
        }
    }

    fun DIV.formSection(title: String, iconSvg: String, block: DIV.() -> Unit) {
        div(CssClasses.FORM_SECTION) {
            h3(CssClasses.FORM_SECTION_TITLE) {
                icon(iconSvg)
                +title
            }
            block()
        }
    }

    fun DIV.formGroup(label: String, block: DIV.() -> Unit) {
        div(CssClasses.FORM_GROUP) {
            label(classes = CssClasses.FORM_LABEL) { +label }
            block()
        }
    }
}
