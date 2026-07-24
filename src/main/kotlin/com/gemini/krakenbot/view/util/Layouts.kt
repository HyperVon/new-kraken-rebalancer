package com.gemini.krakenbot.view.util

import com.gemini.krakenbot.view.util.Icons.icon
import kotlinx.html.*

fun FlowContent.glassPanel(
    title: String,
    iconSvg: String? = null,
    block: DIV.() -> Unit,
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

enum class ActiveNav {
    DASHBOARD,
    HISTORY,
    SETTINGS,
}

fun FlowContent.brandMark() {
    h1(CssClass.Layout.BrandMark) {
        span(CssClass.Layout.BrandPrimary) { +ViewText.APP_BRAND_PRIMARY }
        +" "
        span(CssClass.Layout.BrandAccent) { +ViewText.APP_BRAND_ACCENT }
    }
}

fun FlowContent.primaryNav(active: ActiveNav) {
    nav(CssClass.Navigation.Bar) {
        a(
            cssClass = if (active == ActiveNav.DASHBOARD) CssClass.Navigation.LinkActive else CssClass.Navigation.Link,
            href = Routes.ROOT,
        ) {
            +ViewText.NAV_DASHBOARD
        }
        a(
            cssClass = if (active == ActiveNav.HISTORY) CssClass.Navigation.LinkActive else CssClass.Navigation.Link,
            href = Routes.HISTORY,
        ) {
            +ViewText.NAV_HISTORY
        }
        a(
            cssClass = if (active == ActiveNav.SETTINGS) CssClass.Navigation.LinkActive else CssClass.Navigation.Link,
            href = Routes.SETTINGS,
        ) {
            +ViewText.NAV_SETTINGS
        }
    }
}

fun FlowContent.statusCard(
    title: String,
    iconSvg: String,
    value: String,
    isSuccess: Boolean = false,
    valueId: String? = null,
    titleId: String? = null,
    block: (DIV.() -> Unit)? = null,
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
        label(CssClass.Form.Label) { +label }
        block()
    }
}
