package com.gemini.krakenbot.view.util

import com.gemini.krakenbot.view.util.Icons.icon
import kotlinx.html.*

object Layouts {
    fun FlowContent.glassPanel(
        title: String,
        iconSvg: String? = null,
        block: DIV.() -> Unit
    ) {
        div(classes = CssClass.Layout.GlassPanel.value) {
            h2(classes = CssClass.Utility.GlassPanelTitle.value) {
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
                CssClass.StatusCard.Success.value
            } else {
                CssClass.StatusCard.Default.value
            }
        div(classes = cardClass) {
            div(classes = CssClass.StatusCard.Header.value) {
                span(classes = CssClass.StatusCard.Title.value) { +title }
                div(classes = CssClass.StatusCard.Icon.value) { icon(iconSvg) }
            }
            div(classes = CssClass.StatusCard.Value.value) { +value }
            div(classes = CssClass.StatusCard.Sub.value) { block() }
        }
    }

    fun DIV.formSection(title: String, iconSvg: String, block: DIV.() -> Unit) {
        div(classes = CssClass.Form.Section.value) {
            h3(classes = CssClass.Form.SectionTitle.value) {
                icon(iconSvg)
                +title
            }
            block()
        }
    }

    fun DIV.formGroup(label: String, block: DIV.() -> Unit) {
        div(classes = CssClass.Form.Group.value) {
            label(classes = CssClass.Form.Label.value) { +label }
            block()
        }
    }
}
