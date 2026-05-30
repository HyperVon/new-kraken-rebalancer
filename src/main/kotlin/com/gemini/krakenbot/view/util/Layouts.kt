package com.gemini.krakenbot.view.util

import com.gemini.krakenbot.view.util.CssClasses.FORM_GROUP
import com.gemini.krakenbot.view.util.CssClasses.FORM_LABEL
import com.gemini.krakenbot.view.util.CssClasses.FORM_SECTION
import com.gemini.krakenbot.view.util.CssClasses.FORM_SECTION_TITLE
import com.gemini.krakenbot.view.util.CssClasses.GLASS_PANEL
import com.gemini.krakenbot.view.util.CssClasses.GLASS_PANEL_TITLE
import com.gemini.krakenbot.view.util.CssClasses.STATUS_CARD
import com.gemini.krakenbot.view.util.CssClasses.STATUS_CARD_HEADER
import com.gemini.krakenbot.view.util.CssClasses.STATUS_CARD_ICON
import com.gemini.krakenbot.view.util.CssClasses.STATUS_CARD_SUB
import com.gemini.krakenbot.view.util.CssClasses.STATUS_CARD_SUCCESS
import com.gemini.krakenbot.view.util.CssClasses.STATUS_CARD_TITLE
import com.gemini.krakenbot.view.util.CssClasses.STATUS_CARD_VALUE
import com.gemini.krakenbot.view.util.Icons.icon
import kotlinx.html.*

object Layouts {
    fun FlowContent.glassPanel(
        title: String,
        iconSvg: String? = null,
        block: DIV.() -> Unit
    ) {
        div(GLASS_PANEL) {
            h2(GLASS_PANEL_TITLE) {
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
                STATUS_CARD_SUCCESS
            } else {
                STATUS_CARD
            }
        div(cardClass) {
            div(STATUS_CARD_HEADER) {
                span(STATUS_CARD_TITLE) { +title }
                div(STATUS_CARD_ICON) { icon(iconSvg) }
            }
            div(STATUS_CARD_VALUE) { +value }
            div(STATUS_CARD_SUB) { block() }
        }
    }

    fun DIV.formSection(title: String, iconSvg: String, block: DIV.() -> Unit) {
        div(FORM_SECTION) {
            h3(FORM_SECTION_TITLE) {
                icon(iconSvg)
                +title
            }
            block()
        }
    }

    fun DIV.formGroup(label: String, block: DIV.() -> Unit) {
        div(FORM_GROUP) {
            label(classes = FORM_LABEL) { +label }
            block()
        }
    }
}
