package com.gemini.krakenbot.view.util

import com.gemini.krakenbot.view.util.Icons.icon
import kotlinx.html.*

object Layouts {
    fun FlowContent.glassPanel(
        title: String,
        iconSvg: String? = null,
        block: DIV.() -> Unit
    ) {
        div(CssClass.Layout.GlassPanel) {
            h2(CssClass.Utility.GlassPanelTitle) {
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
        valueId: String? = null,
        titleId: String? = null,
        block: (DIV.() -> Unit)? = null
    ) {
        val cardClass = if (isSuccess) CssClass.StatusCard.Success else CssClass.StatusCard.Default
        div(cardClass) {
            div(CssClass.StatusCard.Header) {
                span(CssClass.StatusCard.Title) {
                    if (titleId != null) id = titleId
                    +title
                }
                div(CssClass.StatusCard.Icon) { icon(iconSvg) }
            }
            div(CssClass.StatusCard.Value) {
                if (valueId != null) {
                    id = valueId
                }
                +value
            }
            if (block != null) {
                div(CssClass.StatusCard.Sub) { block() }
            }
        }
    }

    fun DIV.formSection(title: String, iconSvg: String, block: DIV.() -> Unit) {
        div(CssClass.Form.Section) {
            h3(CssClass.Form.SectionTitle) {
                icon(iconSvg)
                +title
            }
            block()
        }
    }

    fun DIV.formGroup(label: String, block: DIV.() -> Unit) {
        div(CssClass.Form.Group) {
            label(classes = CssClass.Form.Label.value) { +label }
            block()
        }
    }
}
